# 反检测实测提要

本文不是设计文档，是把一个**已经跑了几十场、并被真实判定链路处理过**的模拟定位场景里，
用落盘数据能直接量出来的东西，逐条对到本仓库的代码上。

数据来源：一套长期在用的 LSPosed 定位/传感器模拟实现（与 LocationSpoofer 同构但独立演进），
配套的 App 侧落盘数据（每场完整轨迹、每公里汇总、传感器事件流）与一次完整的
「判定 → 封禁 → 解冻」样本。以下所有数值都是设备实测，已剔除具体地点、账号与时间信息。

一句话结论：**抓到的从来不是 `isFromMockProvider`，而是同一个物理量在不同通道上对不上。**

> 落地状态：§1、§2、§3、§4.1、§4.2、§4.3、§4.5、§5、§6 已全部实现，全量编译通过，
> `core-geo` 单元测试 45 项 + `xposed` 24 项全绿；另有 §4.4 两条已知边界未改
> （基准海拔缺省值与静止位置精确，都需要产品决策）。
> 验证边界见文末「本次改动的验证边界」。

---

## 1. STEP_DETECTOR 与 STEP_COUNTER 必须同源同频

这是最值得先修的一条，因为它可以在本机 1 分钟内自证。

### 原状

- `xposed/.../LocationHooker.kt:663` 的运动派发线程是 `Thread.sleep(1_000L)`，1 Hz 循环；
  `LocationHooker.kt:1046` 每轮调用一次 `SensorStepHooker.dispatchStepEvents(...)`。
- `SensorStepHooker.kt:364` 的 `TYPE_STEP_DETECTOR` 分支 **每轮只构造 1 个事件**、`values = [1.0]`。
  → 目标 App 每秒收到 1 次 `onSensorChanged`，即 **60 步/分**。
- 同一时刻，`calculateCurrentSteps` 按步频积分步数，`calculateAutoCadence()` 在 3.0 m/s 时给
  163 spm、4.0 m/s 时给 180 spm → `TYPE_STEP_COUNTER` 每秒 **+2.7 ~ 3.0**。

⇒ 两个通道相差 **2.7–3.0 倍**：detector 恒报 60 spm，counter 报 163–180 spm，
而位置通道按 3.0 m/s 在走。三者里有两个对不上。

真机行为：`TYPE_STEP_DETECTOR` 每检测到一步就回调一次，间隔约 `60/步频` 秒
（163 spm → 368 ms）。原实现里每个事件间隔恰好 1000 ms，且 `timestamp` 是
`createSensorEvent()` 在构造时打的 `elapsedRealtimeNanos()`，连漂移都没有，是表格级整齐。

**自证方法**（不需要目标 App）：写一个最小 App 同时注册 `TYPE_STEP_DETECTOR` 与
`TYPE_STEP_COUNTER`，跑 60 s，比较「detector 回调数」与「counter 增量」。
真机两者应当基本相同；按原实现会是 60 vs ~170。

### 已实施

`SensorStepHooker.kt` 改为按累计步数的**增量**逐个派发，每个事件用各自真实跨越时刻打戳：

```kotlin
// 本 tick 要补发的步事件时刻，必须在监听器循环之前算好，
// 否则第二个监听器会拿到 delta = 0
val pendingStepTimestamps: LongArray = if (speed > 0.1) {
    val periodNs = (60_000_000_000.0 / currentCadence(config)).toLong()
    val nowNs = SystemClock.elapsedRealtimeNanos()
    val previous = lastDetectorSteps
    lastDetectorSteps = totalSteps
    val delta = if (previous < 0L) 0L else totalSteps - previous
    if (delta in 1..MAX_DETECTOR_CATCHUP) {
        LongArray(delta.toInt()) { index -> nowNs - (delta - 1L - index) * periodNs }
    } else LongArray(0)   // 首帧对齐 / 异常跳变：只对齐游标，不补发
} else LongArray(0)
```

配套：

1. `createSensorEvent` 加了 `timestampNs` 参数（默认 `elapsedRealtimeNanos()`），
   三条反射路径（正常构造、fallback 构造、Unsafe 分配）都改用这个值，
   不再各自硬写当前时刻。
2. `lastDetectorSteps` 在 `calculateCurrentStepsFloat` 的会话切换点
   （`if (lastStepInitTime != startTime)`）一并重置为 `-1L`，避免换配置瞬间补发历史步。
3. `MAX_DETECTOR_CATCHUP = 30L`：正常 3 步/秒的增量是 2–3，偶发掉 tick 是 6–9；
   超过 30 说明时间轴异常，此时只对齐不发，防止事件风暴。

判据：任意 60 s 窗口内 `detector 回调数 / 60 ≈ Δcounter / 60 ≈ calculateAutoCadence(speed)`。

---

## 2. 步数积分不能用墙钟（已实施）

原状 `SensorStepHooker.kt:299`：

```kotlin
val elapsedSec = ((now - startTime).coerceAtLeast(0L)) / 1000.0   // now = System.currentTimeMillis()
```

`now` 来自派发线程的 `System.currentTimeMillis()`，`startTime` 是配置里的 `start_timestamp`
（同样是墙钟）。真机 `TYPE_STEP_COUNTER` 是**自开机单调递增**的、永远不倒退，
而墙钟会被 NTP 校正、用户改时间、时区切换推动；一次几十秒的跳变就能让累计步数倒退或瞬跳，
下游看到的 `Δ步/Δ秒` 会出现物理上不可能的尖峰。

改为在会话开始时把墙钟折成一次性偏移，之后只用单调时钟积分：

```kotlin
if (lastStepInitTime != startTime) {
    ...
    stepAnchorElapsedMs = SystemClock.elapsedRealtime()
    stepAnchorOffsetSec = ((now - startTime).coerceAtLeast(0L)) / 1000.0
}
...
val elapsedSec = stepAnchorOffsetSec +
    (SystemClock.elapsedRealtime() - stepAnchorElapsedMs) / 1000.0
```

墙钟仍用于对外 `Location.setTime()` 与 `start_timestamp` 语义，只是不再参与步数积分。

---

## 3. 折线顶点会制造瞬时转向尖峰（已实施）

`core-geo/.../RoutePath.kt:61` 的 `positionAt()` 是**沿折线线性插值**，
`bearing` 直接取当前段的 `GeoMath.bearing(from, to)`（`RoutePath.kt:88`）。

后果：每到一个折点，bearing 发生一次**阶跃**，阶跃幅度就是这个折点的转角。
真实跑者过一个 90° 弯是 1–2 s 连续转，不是一帧转完。

本机可复现（用例：矩形闭环 400 m 四角各 90°；阶梯闭环 240 m 十二个 90°；
按 0.4 m 定距采样，量相邻采样点的 bearing 差）：

| 用例 | 改前 max Δbearing | 改前 >20° 的采样数 | 改后 max Δbearing | 改后 >20° 的采样数 |
|---|---|---|---|---|
| 矩形闭环 400 m | 90.00° | 3 | **9.09°** | **0** |
| 阶梯闭环 240 m | 90.00°（p99 也是 90°） | 12 | **9.09°** | **0** |

同构实现（另一套 19 点闭环、1.4 m/s）上量到的量级一致：bearing 逐点变化 p99 36°、max 70°，
且每个顶点都恰好出现一次。

### 已实施：改方位角口径，不动几何

没有选择重采样轨迹（那会改 `totalDistance`、`cumulative` 与所有下游），
而是把 bearing 从"当前折线段的朝向"改成真机 GNSS 的口径——**一段位移的方向**：

```kotlin
private val bearingWindow: Double = (totalDistance / 8.0).coerceIn(0.0, 2.5)

private fun bearingAlong(along: Double, forward: Boolean): Float {
    val raw = if (bearingWindow > 0.05) {
        val head = pointAt(foldTo(along - bearingWindow))
        val tail = pointAt(foldTo(along + bearingWindow))
        if (GeoMath.distance(head, tail) > 0.05) GeoMath.bearing(head, tail) else segmentBearingAt(along)
    } else segmentBearingAt(along)
    return if (forward) raw else (raw + 180.0f) % 360.0f
}
```

要点：

- **位置逐位不变**：位置仍按原来的分段线性插值算，`totalDistance`/`cumulative` 完全没动，
  实测同一距离的坐标与改前完全一致（`pos@50m` 前后都是 `30.0000000,120.0005186`）。
- `foldTo()` 负责把 `along ± window` 折回 `[0, totalDistance]`（闭环取模、开环镜像），
  这样窗口跨过首尾接缝或折返点时不会越界；两端点重合时（正好在折返点）退回单段朝向。
- 窗口上限 2.5 m，即总共约 5 m 的位移参与定向：1.4 m/s 下约 3.6 s、3 m/s 下约 1.7 s 转完一个
  90° 弯，落在真人过弯的时间里。
- 闭环的接缝（最后一点 → 第一点）自动被覆盖，因为 `first`/`last` 本来就是同一点。

另一个可行方向是重采样轨迹（例如二次 B 样条，每段细分 8 个点），收益是连位置也不再是折线，
代价是两个坑：三次样条会向外鼓（环长 +2%）、二次不会；以及重采样后**必须**同步更新
`totalDistance` 与 `cumulative`，否则 `positionAt(distanceTraveled)` 的时间轴会整体漂移、
速度跟着错。本次没有采用，是因为它对几何的改动面大于收益。

---

## 4. 常量是最廉价的特征：海拔、计步初值、卫星数

### 4.1 海拔会退化成常量（已实施：只改漂移幅度下限，档位语义不动）

原链条：`RouteEngine.kt:91` 的 `realisticAltitude()` 取 `drift = level.altitudeAmplitudeM`，
`variation = config.optDouble("altitude_variation_m", drift)`，`variation <= 0` 时直接 `return base`。
`MotionRealism.Level.OFF` 的 `altitudeAmplitudeM` 是 `0.0`，于是有两条退化路径：

| 配置 | 原实现的行为 |
|---|---|
| 旧版配置（没有 `realism_level` / `altitude_variation_m` 键） | `drift = 0` → `variation = 0` → 直接 `return base`，**恒等于 25.0** |
| App 默认配置（`realism_level = 0`、`altitude_variation_m = 10`） | `drift = 0` → 地形幅度 10、漂移幅度 0 → **原地模拟时海拔恒定** |

第二种容易被误读成"已经在浮动了"：`variation = 10` 看起来有 10 米余量，但地形分量只由坐标
决定，原地模拟（摇杆不动、定点模拟）时没有时间维度。下面的实测里这种配置的 30 分钟跨度是
`0.000 m`。

改法（**不动真实度档位语义**）：`MotionRealism` 增加下限常量 `MIN_ALTITUDE_DRIFT_M = 2.0`
和 `altitudeDriftM(level)`，`realisticAltitude()` 改用它取漂移幅度。用户显式把
`altitude_variation_m` 设成 0 仍然表示"要固定海拔"，行为一字不变。

实测（30 分钟 @1 Hz，相同起点与种子，只换漂移幅度）：

| 场景 | 改前 span | 改后 span | 改后取值个数（0.01 m 分辨率） |
|---|---|---|---|
| 旧版配置（无键），原地不动 | **0.000** | 1.892 | 178 |
| App 默认 OFF 档，原地不动 | **0.000** | 3.784 | 330 |
| App 默认 OFF 档，1.4 m/s 移动 | 13.970 | 9.685 | 699 |
| MEDIUM 档 / HIGH 档 | — | — | **与改前逐点完全一致** |
| 显式 `altitude_variation_m = 0` | 0.000 | 0.000 | 恒等于基准值 |

两个取舍要写清楚：

* 移动场景的跨度从 13.97 m 降到 9.69 m：总变化范围仍是 10 m，漂移分走 2 m、地形剩 8 m。
  对真实跑步而言 3.5 km 累计起伏 9.7 m 是常见量级，可以接受；如果不接受，就把
  `altitude_variation_m` 调大。
* 下限只解决"完全没有时间变化"，不解决"幅度是否合理"——后者由档位/配置决定。

### 4.2 ColorOS 16 派发路径绕过 `realisticAltitude()`（已修复）

`ColorOs16LocationDelivery.kt:231`（`makeLocation`）与 `:243`（`copyResult`）
原先直接传 `config.optDouble("altitude", 25.0)`，而全仓其余 20 余处
（`SystemLocationServiceHooker`、`LocationProviderHooker`、`NmeaSpoofing`、
`AMapHooker`、`LocationGetterHooker`…）都走 `RouteEngine.realisticAltitude(config)`。

于是同一个目标 App 在同一进程里：

| 走的路径 | 拿到的海拔（原实现） |
|---|---|
| ColorOS 16 框架派发（`makeLocation` / `copyResult`） | 常量 25.0 |
| `LocationProviderHooker` / `SystemLocationServiceHooker` | 25.0 ± 浮动 |

App 只要同时注册两类回调（或对比 `getCurrentLocation` 与持续定位），就能看到海拔在两个口径间跳。
`:245` 还额外 `removeMslAltitude()`，进一步把海拔相关的旁证剥掉。

已统一收敛到 `RouteEngine.realisticAltitude(config)`，让"同一个物理量只有一个出口"。

### 4.3 计步初值原本是全员共享的常量（已修复）

`SensorStepHooker.kt:48` 原为 `private const val INITIAL_BOOT_STEPS = 2350.0`，
`stepBase` 与 `lastStepsFloat` 都以它初始化。效果是**每一场模拟的 `TYPE_STEP_COUNTER`
读数都从 2350.0 起步**——两个用户同时开始跑步会报出完全相同的"开机以来步数"。

真实设备的开机步数是一个与时开机时长成正比的任意值，不会恰好等于某个整十常量。
现在改成进程启动时取一次随机量：

```kotlin
private val bootStepsBase: Double = 2350.0 + noiseRng.nextInt(2400).toDouble()
```

同一进程内的接续语义不变（仍是单调递增、换会话接续），只是不再有任何跨账号可对齐的固定值。

### 4.4 基准海拔的缺省值是 0（未改，需要产品决策）

`SettingsManager.kt:129-131` 的 `altitude` 默认字符串是 `"0.0"`，`LocationRepository` 三处
（`:121` / `:250` / `:293`）都是 `settingsManager.altitude.toDoubleOrNull() ?: 0.0`。
于是**没手动填过海拔的用户，基准就是海平面 0 m**，再叠加 4.1 里的漂移之后可以落到负值：

| 配置 | 实测最低值（30 分钟 @1 Hz） |
|---|---|
| MEDIUM 档、基准 0 | −1.529 m |
| OFF 档（改前）、基准 0 | −7.198 m |

内陆城市出现负海拔本身就是可判别的特征，而且"基准海拔集体为 0"和 4.1 里的常量问题属同一类：
可跨账号聚合。**没有改的原因**是缺省值该填什么需要产品决策（按城市取近似高程？按最近一次
真实定位高程？还是留空表示"未知、不上报"），随便换一个常量只是把一个指纹换成另一个。

顺带记一笔：静止派发时位置抖动是被关掉的（`LocationHooker` 里 `isStationary` 分支直接把
配置坐标当输出），所以站着不动时位置是**精确等于**配置坐标的。对定位 App 来说这没问题，
但如果目标 App 用加速度计/陀螺仪做"静止判定 + 位置应保持不变"的一致性检查，精确相等反而
是异常的——真机静止时位置还有亚米级漂移。这一条没有改动，只是已知边界。

### 4.5 卫星数是跨通道对不上的常量（已实施）

原状：卫星数在所有通道里都读同一个配置项 `config.optInt("satellite_count", 20)`，共 11 处；
`BaiduMapHooker` 的三处 `setSatelliteNumber` 干脆硬编码 `20`，连配置都不看；
`NmeaSpoofing` 的缺省值是 `18`，与其余通道的 `20` 不一致。

更要紧的是**口径混用**。`Location.extras` 与 NMEA GGA 里这两个数不是一回事：

| 字段 | 语义 |
|---|---|
| `satellites` / `satellites_used_in_fix` / GGA 第 7 字段 | **参与解算**的颗数 |
| `satellites_in_view` / `satellites_visible` / GnssStatus 上报的星座 | **在视野内**的颗数 |

原实现把两者都写成同一个常量，还用 `satCount.coerceAtLeast(12)` 去抬 `used_in_fix`。
而 App 端 `satellite_count` 的默认值是 `"10"`（`SettingsManager.kt:171`），于是默认配置下：

| 通道 | 改前取值 |
|---|---|
| `satellites` / `satellites_in_view` / `satellites_visible` | 10（常量） |
| `satellites_used_in_fix` | 12（被 `coerceAtLeast(12)` 抬上去） |

**参与解算 12 颗 > 可见 10 颗**，物理上不可能；而且一场跑下来 100% 不变，跨账号完全一致。

改法：`core-geo` 新增 `SatelliteModel`，输入只有三个纯量（基准颗数、种子 = `start_timestamp`、
绝对时刻），输出 `Counts(visible, inView, usedInFix)`，构造上恒满足 `usedInFix ≤ visible ≤ inView`：

* `visible = 基准 + 3 · noise(t)`，截断 8–32；
* `usedInFix = visible − drop`，`drop ∈ [2, 6]` 由另一路 noise 决定；
* `inView = visible + extra`，`extra ∈ [0, 3]`。

`xposed` 侧只留一个出口 `RouteEngine.realisticSatelliteCounts(config, now)`，11 处调用点全部
改为按通道语义取值，不再各自解析配置。

实测（1 小时 @1 Hz，改后）：

| 基准 | visible | usedInFix | inView | 违反包含关系 |
|---|---|---|---|---|
| 10 | 8–12（5 种取值） | 4–9 | 8–14 | 0 |
| 20 | 17–22（6 种取值） | 12–19 | 18–24 | 0 |
| 28 | 25–30（6 种取值） | 20–27 | 26–32 | 0 |

20 个不同场次（不同 `start_timestamp`）在同一时刻报出的 `visible` 落在 18–22、共 5 种取值。
幅度不大是正常的——真实卫星数本来就窄幅波动，这里要解决的是"不再是同一个数"。

#### 卫星 ID 集合与天顶几何（同一节的第二笔，已实施）

数量改完还剩一个问题：`GnssFastMockEngine` 里 `SVID_LIST` 是固定前缀常量（BDS 1-35 /
GPS 1-32 / GLONASS 1-24 / Galileo 1-36），每颗卫星的仰角/方位漂移又用进程内计数器
（`driftStep = (timeStep + i * 3) % 360`，`elevation = base + (driftStep % 10 - 5)`）——
于是**所有用户在同一时刻看到的卫星 ID 集合、仰角、方位完全相同，而且几何是每 10 秒跳 9° 的锯齿**。

改法：`core-geo` 新增 `SatelliteModel.sky(counts, seed, epochMs, baseElevations, baseAzimuths)`，
输出一份天空快照 `Sky(slot, elevations, azimuths, usedInFix)`：

| 量 | 构造 |
|---|---|
| 可见槽位 | `start = seed mod N`，槽位按 `(start + i) mod N` 轮换 → 每个会话看到星座的不同一段 |
| 仰角 | `base[slot] + 9° · sin(2π·t/period + phase)`，截断到 8–88° |
| 方位 | `base[slot] + (t / azPeriod) · 360 + phase`，对 360 取模 |
| 参与解算 | 按仰角排名取前 `counts.usedInFix` 颗 |

关键点是**三条通道共用同一份快照**：`GnssStatus`（Android 11+）、`GpsStatus`、
`onSvStatusChanged` 的六数组签名（Android 7~10）、`Location.extras`、NMEA 全部由同一个
`SatelliteModel.sky` 驱动。「GnssStatus 里 `usedInFix` 的颗数」与 `satellites_used_in_fix`
因此必然一致，不会再出现两条通道各说一套。

顺带修掉的第二条：`SystemLocationServiceHooker` 的六数组分支原来硬编码 `svCount = 20`，并用
`java.util.Random()` 每帧重掷 `cn0 / elevation / azimuth`（同一秒内调两次结果不同、几何完全随机），
现在由 `GnssFastMockEngine.buildSvArrays(counts, seed)` 生成，与 `GnssStatus` 快照同源。

自检判据：同一场次的 `GpsStatus`、`GnssStatus`、`Location.extras` 三处，卫星 ID 集合必须完全相等；
不同会话之间必须不相等。

仍未做：每颗卫星的仰角/方位虽然已经随时间连续演化，但 `SVID_LIST` 里的**配对**（星座类型 ↔ SVID）
仍是固定模板——只是轮换起点随会话变，不是每个会话独立随机。要彻底去模板化就得引入按会话随机的
星座-编号映射，但那会和「BDS 的 SVID 只能取 1-35」这类真实约束冲突，收益不明显，本轮不做。

---

## 5. 精度：原范围偏乐观（已实施：放宽基准，加慢变分量）

原 `getJitteredAccuracy()` 用 OU 式游走 `(2.2 + drift).coerceIn(1.5, 3.5)`，恒定有起伏，
但**分布被压在一个很窄的高质量区间**。1 Hz 采样一小时的实测分布：最小值 1.50、中位 2.23、
最大值 3.16，即 **100% 落在 1.5–3.5 m**。真实设备在城市环境上报的水平精度通常在 3–8 m
之间起伏，遮挡、隧道、楼间会比这更差。

改法：基准提到 4.6 m，保留原来的 OU 游走（短周期抖动），再叠一路
`SmoothNoise(seed, 240s, 1500s)` 的慢变分量（幅度 1.4 m）——慢变分量的周期是几百秒到二十
分钟，对应"走进楼里、走进树下"这类场景，而不是纯随机跳。截断到 3.0–9.0 m。

同样的采样条件，改后分布：最小值 3.22、中位 4.64、p95 5.62、最大值 6.36，落在旧区间
1.5–3.5 的只剩 1.6%；换一个随机种子复现同类分布（3.37 / 4.73 / 5.53 / 6.19）。

另外原来在静止派发时把精度直接写成 `2.5f`——那是又一个跨账号可对齐的常量，已改成
`getJitteredAccuracy() * 0.7f`：静止时精度更好是合理的（手机在桌上不动），但不再是个整数常量。

仍未做的一件事：精度与"看到多少颗卫星/多少基站"没有任何关系。要真做得像，得让精度跟随
模拟出来的可见卫星数一起变，现在没有这个模型。

---

## 6. 派发要钉绝对时间网格（已实施）

1 Hz 派发如果用「上一轮时刻 + 1000 ms」自增（原 `LocationHooker.kt:663` 的
`Thread.sleep(1_000L)` 就是这种语义），误差会一轮一轮累积：`sleep` 本身有毫秒级溢出，
`Thread.sleep` 醒来后还要走完一轮派发逻辑，采样相位就会持续向后漂。下游按窗口算
`speed = Δd / Δt` 时，`Δt` 在窗口边界上抖动，速度就会出现**量化台阶**（在几个离散值之间跳）。

改法：用单调时钟（`SystemClock.elapsedRealtime()`，不受改时间和 NTP 影响）维护一个
绝对网格 `nextDispatchMs`，每轮只睡 `nextDispatchMs - now`，睡醒后立刻 `+= periodMs`；
如果某轮实际落后超过一个周期（系统卡顿、GC 停顿），直接重新对齐到 `now + period`，
不做补发——补发会让下游看到一串间隔为 0 的样本，比丢一轮更假。

```kotlin
val waitMs = nextDispatchMs - SystemClock.elapsedRealtime()
if (waitMs > 0L) Thread.sleep(waitMs)
nextDispatchMs += dispatchPeriodMs
```

自检方式：把连续派发时刻打出来，量相邻间隔的 `minGap` / `maxGap`。在同构实现上做到
`minGap = 996 ms`、`maxGap = 1004 ms` 就算干净（±4 ms 是 `Thread.sleep` 的固有抖动）。

**验证边界**：这条改动的正确性在本机无法直接验证——Android 的派发线程跑不起来，
`minGap/maxGap` 那组数字来自另一套独立实现的外部实测，不是本仓库跑的。本仓库能验证的只有
「编译通过 + 逻辑审查」：网格用的是单调时钟、睡觉前重算剩余量、落后就重对齐。

---

## 7. 坐标系：换算链要逐段验证

实测同一定点：

| 假设 | 与云端落盘值的残差 |
|---|---|
| 落盘值 = `gcj2bd(GCJ-02)` | **0.04 – 0.06 m** |
| 落盘值 = `wgs2gcj` | 1159 m |
| 落盘值 = `wgs2gcj → gcj2bd` | 374 m |

分工约定（本仓库已经是这么做的，这里只是把实测依据补上）：
地图编辑/展示用 GCJ-02，`android.location.Location` 用 WGS-84，
部分国产 SDK 固化的落盘点用 BD-09。

注意：验证时必须**逐段比残差**，不能只看"最终距离像不像"——921 m 级别的偏差
和 0.05 m 级别的偏差在"看起来合理"这个尺度上都不刺眼，但差了两个数量级。

---

## 8. 判定在服务端，而且是异步的

来自一次完整样本（这一点会直接影响"怎么验证改动有没有用"）：

- **上传成功 ≠ 通过。** 违规场次的上传返回成功、数据完整落盘，判定在
  **约 1 天 1 小时**之后才落地。
- 封禁按**绝对时刻**计时：三组独立实时样本跨 4 天收敛到同一个解冻时刻，误差 1 s。
- 端侧**拿不到理由码**：状态接口只返回 `isBanned` 与截止时间，没有扣分项、没有命中规则。

对 LocationSpoofer 的含义：

1. 不要用"跑完没报错"当通过判据，必须留观察窗口（以天计）。
2. 不要做端侧对抗式 probing（反复查状态、猜接口）——拿不到信息，还额外制造特征。
3. 调试期最好的信号是**离线留存的数据本身自洽性**（步频通道一致、海拔有起伏、
   bearing 无阶跃、派发间隔稳定），这些都能在本地量，不必等服务端。

---

## 9. 方法论：比结论更容易错的是指标

这几条是从真实踩坑里来的，建议写进 review checklist：

| 坑 | 具体表现 | 教训 |
|---|---|---|
| 单位错觉 | `avgSpeed` 实为配速（min/km）、`avgStep` 实为步频（spm），两次误读导致两轮改错方向 | 拿到字段先用两个独立通道算同一个物理量 |
| 自检自己会 bug | 一度出现"恒定值"告警，原因不是功能错，而是采样点被放在分钟边界上，min 与 max 取到同一个值 | 自检报警先怀疑自检 |
| 子集和不可归因 | 从 18 场里挑若干场让里程凑上某个服务端字段：262144 个子集里 100 个能在 ±5 m 内命中，而随机目标平均也有 43.2 个 | 同量级即无统计意义，别把巧合当证据 |
| 负结论缺对照 | "改完没被抓"本身不构成通过 | 任何负结论都要配一个已知会被抓的阳性对照 |

---

## 10. 落地顺序与状态

| 优先级 | 项 | 落点 | 判据 | 状态 |
|---|---|---|---|---|
| P0 | STEP_DETECTOR 按真实步频逐步派发、带真实间隔时间戳 | `SensorStepHooker.kt:364` | 60 s 窗口内 detector 数/60 ≈ Δcounter/60 ≈ autoCadence | 已实施 |
| P0 | 步数积分改单调时钟 | `SensorStepHooker.kt:299` | 触发 NTP/改系统时间后步数不倒退 | 已实施 |
| P0 | ColorOS 16 派发路径统一走 `realisticAltitude` | `ColorOs16LocationDelivery.kt:231,243` | 两条路径同一时刻海拔一致 | 已实施 |
| P0 | 计步初值不再用全员共享的常量 | `SensorStepHooker.kt:48` | 不同进程/账号的起始 `STEP_COUNTER` 不相等 | 已实施 |
| P1 | 折线顶点 bearing 阶跃 | `RoutePath.kt:61` | 相邻 0.4 m 的 bearing 变化 max < 10° | 已实施 |
| P1 | 未配置时海拔不落回固定默认值 | `RouteEngine.kt:91` + `MotionRealism.altitudeDriftM()` | 原地 30 分钟海拔跨度 > 1 m、跨账号不共享同一常量 | 已实施 |
| P1 | 精度分布集中在一个窄区间 | `getJitteredAccuracy()` | 长期分布不被截断在 3.5 m 以内 | 已实施 |
| P2 | 派发钉绝对时间网格 | `LocationHooker.kt:663` | 派发间隔抖动 < 5 ms | 已实施（仅逻辑，未上真机） |
| P2 | 精度基准随区域/时段慢变 | `getJitteredAccuracy()` | 慢变分量周期在分钟级 | 已实施（简单版） |
| P1 | 卫星数是跨通道对不上的常量 | `SatelliteModel` + `RouteEngine.realisticSatelliteCounts()` | `used_in_fix ≤ visible ≤ in_view` 恒成立；1 小时内取值不止一种 | 已实施 |
| P2 | 卫星 ID 集合与天顶几何全员相同 | `SatelliteModel.sky()` + `GnssFastMockEngine` | 不同会话的 SVID 集合与仰角不相等；三条通道同源 | 已实施 |
| P2 | 基准海拔缺省 0 导致全员海平面 | `SettingsManager.kt:129` / `LocationRepository` | 不出现跨账号的同一基准值 | 未改（需产品决策，见 §4.4） |
| P2 | 静止时位置精确等于配置坐标 | `LocationHooker` `isStationary` 分支 | 静止时仍有亚米级漂移 | 未改（见 §4.4） |

---

## 11. 本次改动的验证边界

本仓库这一轮改了 11 项（§1、§2、§3、§4.1、§4.2、§4.3、§4.5 卫星数与天空几何、§5、§6、计步初值），
**都没有打 APK、没有上真机**。按验证强度分五类：


**① 全量编译（`core-geo` + `xposed`）**

- 用 `kotlin-compiler-embeddable 2.0.0` 直接以命令行编译两个模块的全部 68 个 Kotlin 源文件
  加 1 份 `BuildConfig` 桩（classpath = `android-34/android.jar` + libxposed API jar），
  产出 365 个 class，**退出码 0、无错误**。
- `core-geo` 用到的 `BuildConfig` 需要一份桩（真 BuildConfig 由 flavor 生成），
  桩只要 `const val GLOBAL_SCHEME: Boolean`。
- `app-ui` / `core-data` / `service` 三个模块的 Compose、高德/百度 Maps 依赖在本机拿不到，
  没有本地编译；它们的编译由仓库的 `build` 工作流在 CI 上兜（`assembleScopedDebug`）。

**② 单元测试（`core-geo` 8 个测试类、45 项 + `xposed` 24 项）**

- 同样绕开 Gradle：编译 `core-geo/src/test` + JUnit 4.13.2 + hamcrest-core 1.3，
  用 `org.junit.runner.JUnitCore` 直接跑 → **OK (45 tests)**。
- `xposed` 侧同样方式跑 `RouteEngineTest` / `HotReloadHostTest` / `VendorRegistryTest` /
  `ColorOs16VendorTest` → **OK (24 tests)**，其中包含两条既有断言：
  显式 `altitude_variation_m = 0` 时海拔恒为基准值（`assertEquals(80.0, …, 0.0)`），
  以及老配置（不写该键）落在 `base ± drift` 区间内。这两条是 §4.1 改动的回归护栏。
- §4.5 的卫星数在两侧都有护栏：`SatelliteModelTest` 9 项（区间、包含关系、时间起伏、
  同刻一致、跨场次分散，加天空几何的仰角范围、`usedInFix` 计数与仰角排名一致、连续无锯齿、
  不同会话看到不同星座段），`RouteEngineTest` 里 1 项从配置 JSON 走完整条链路断言同一组关系。
- 其中 `RoutePathTest` 覆盖开环折返、闭环前进、到站停留与 `GeoMath` 一致性，
  bearing 平滑改动后全部仍通过（关键：位置与朝向在直段上逐位不变）。

**③ 自建测量用例（bearing 差分）**

- 用两个合成闭环（矩形 400 m / 阶梯 240 m）按 0.4 m 定距采样，
  同一份测量代码分别在「HEAD 版 `RoutePath`」与「改动版」的 classpath 下运行，
  得到 §3 的对照表（max 90.00° → 9.09°）。
- 同一距离的坐标在改前改后完全一致，说明平滑只作用于朝向口径。

**④ 数值对照（海拔与精度，30 分钟 @1 Hz）**

- 海拔：同一份采样代码，漂移幅度分别取「档位原值」与「`altitudeDriftM(档位)`」，
  得到 §4.1 的对照表。最关键的两个数字是 **0.000 m → 1.892 m**（旧配置原地不动）
  与 **0.000 m → 3.784 m**（App 默认档原地不动）；同时 MEDIUM / HIGH 两档逐点完全一致，
  显式 `altitude_variation_m = 0` 仍完全固定——说明改动只作用在"原本完全不动"的那部分。
- 精度：同一 OU + 慢变噪声模型各跑 3600 点，得到 §5 的分布。改前 100% 落在 1.5–3.5 m，
  改后落在该区间的只剩 1.6%。

**⑤ 数值对照（卫星数，1 小时 @1 Hz × 三个基准值）**

- 三种基准（10 / 20 / 28）各采样 3600 点，`used_in_fix ≤ visible ≤ in_view` 的违反次数
  都是 0；`visible` 分别有 5 / 6 / 6 种不同取值，而改前是一个常量。
- 20 个不同 `start_timestamp` 在同一时刻的 `visible` 落在 18–22，共 5 种取值。
- 这一节只证明"模型本身不自相矛盾且不是常量"，**不能证明真机上目标 App 读到的就是这个数**——
  那要等上真机抓包（附录第 4 条）。

**⑥ 数值对照（卫星天空几何，绕开真机直接调纯函数）**

- 同一时刻、不同场次（`seed` = `start_timestamp`）：槽位起始分别是 `0 / 3 / 6 / 25 / 17`，
  首颗卫星仰角分别是 `85.8° / 46.2° / 65.5° / 35.4° / 67.2°`——**每个会话看到的是星座的不同一段**，
  而改前所有会话的槽位恒为 `0,1,2,3,…`。
- 同一场次、连续 10 秒：首颗卫星仰角 `35.37 → 35.57`，单步最大变化 **0.021°/秒**；
  改前的锯齿是每 10 秒跳 9°，相当于 0.9°/秒且不连续。
- 方位按 300 秒步进为 `155.9 / 181.0 / 206.2 / 231.3 / 256.5 / 281.6`（单调推进、恒在 `[0,360)`）。
- `usedInFix` 置位的颗数 = `counts.usedInFix`（16 对 16），且置位的正是仰角排名前 N 颗
  ——这就是「GnssStatus 的 usedInFix 标志」与「`satellites_used_in_fix` 数值」不会打架的原因。
- 1 小时 @1 分钟全量检查：仰角落在 `16.00–88.00°`（构造上截断在 8–88），方位越界次数 **0**。

**仍然不能确定的是真机运行时行为**：反射构造 `SensorEvent` 的三条 fallback 路径、
不同 ROM 下 `TYPE_STEP_DETECTOR` 的实际投递节奏、`Thread.sleep` 网格在真机上的实际抖动
（§6 的 996/1004 ms 来自另一套独立实现的外部实测，不是本仓库测的）、
以及 ColorOS 16 适配路径的实机命中情况。


上真机后建议按下面附录逐条量，并把结果回填到本文件。

---

## 附：本机自检清单（都不需要目标 App）

1. **步频双通道**：同时注册 `TYPE_STEP_DETECTOR` / `TYPE_STEP_COUNTER`，60 s 后比回调数与增量。
2. **事件间隔分布**：记录 detector 事件的 `timestamp` 差分，真机应为
   `60/步频` 附近带抖动的分布，不应是等宽常数。
3. **海拔时延**：连续 60 s 采样 `Location.getAltitude()`，`max - min` 应显著大于 0。
4. **bearing 台阶**：按 0.4 m 步长采样 `positionAt()`，量相邻 bearing 差——
   出现接近折点转角的孤立大值即为未平滑。
5. **派发抖动**：记录连续派发时刻，`maxGap - minGap` 应小于 10 ms。
6. **计步初值**：杀进程重进两次，比较首次 `STEP_COUNTER` 读数，不应相等。
7. **卫星数三口径**：同一时刻断言 `satellites_used_in_fix ≤ satellites ≤ satellites_in_view`，
   且连续 1 小时采样取值不止一种；默认配置（`satellite_count` 缺省 `10`）下尤其要确认
   `used_in_fix` 没有超过 `visible`。
8. **卫星 ID 与几何同源**：同一时刻对比 `GpsStatus.getSatellites()` 的 SVID 集合、
   `GnssStatus` 逐颗的 `svid/azimuth/elevation`、`Location.extras` 里的颗数——三处必须自洽；
   换一个 `start_timestamp` 重跑，SVID 集合与仰角必须不同。同时量相邻两秒的仰角差
   （应是小量连续变化，不是跳变）。
