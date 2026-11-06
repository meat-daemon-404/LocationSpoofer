# 反检测实测提要

本文不是设计文档，是把一个**已经跑了几十场、并被真实判定链路处理过**的模拟定位场景里，
用落盘数据能直接量出来的东西，逐条对到本仓库的代码上。

数据来源：一套长期在用的 LSPosed 定位/传感器模拟实现（与 LocationSpoofer 同构但独立演进），
配套的 App 侧落盘数据（每场完整轨迹、每公里汇总、传感器事件流）与一次完整的
「判定 → 封禁 → 解冻」样本。以下所有数值都是设备实测，已剔除具体地点、账号与时间信息。

一句话结论：**抓到的从来不是 `isFromMockProvider`，而是同一个物理量在不同通道上对不上。**

> 落地状态：§1、§2、§3、§4.1、§4.2、§4.3、§4.5、§4.6、§4.7、§4.8、§4.9、§5、§6 已全部实现，全量编译通过，
> `core-geo` 单元测试 64 项 + `xposed` 29 项全绿；另有 §4.4 两条已知边界未改
> （基准海拔缺省值与静止位置精确，都需要产品决策）。
> **已上机验证**（Android 15 x86_64 模拟器，LSPosed 注入目标进程、十五项改动在设备上被真实求值），
> 过程中抓到并修掉两个只在真机暴露的 `Chain.args` 不可变问题，详见 §12；仍未走通的是「有 root 的端到端链路」。
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

### 4.6 气压计完全没做（已实施）

前面几条都是「改错了值」，这一条更彻底：**整条通道根本没有**。

`xposed` 目录里 `TYPE_PRESSURE` / `barometer` / `pressure` 三个词一次都没出现过，
也就是说模拟只改了 GPS 海拔，气压计给目标应用的仍然是设备所在真实环境的气压。于是：

* 轨迹声称在海拔 800 m 的山地，手机实际放在 50 m 的房间里 → 气压计换算出的海拔
  比 GPS 海拔低几百米，两条通道当场对不上；
* 手环类 App 用气压计算「累计爬升」，模拟路线的高低起伏在气压通道上是**零**，
  而 GPS 侧却在变（§4.1 之后落差有 1~4 m 量级），同样是自相矛盾；
* 更进一步，一部分机型没有气压计。这时目标应用调 `getDefaultSensor(TYPE_PRESSURE)`
  会拿到 `null`，而它的"设备能力表"里本机是有气压计的——这是设备身份层面的不一致。

改法不是"再造一条随机曲线"，而是让气压**由同一份海拔换算出来**，两条通道在任何时刻自洽：

`core-geo` 新增 `BarometerModel`：

| 量 | 构造 |
|---|---|
| 基准换算 | `P = 1013.25 · (1 − h / 44330) ^ 5.255`（国际标准大气对流层近似） |
| 海拔量程 | 夹到 −500 … 10000 m，避免算出 0 或负压 |
| 慢波动 | `SmoothNoise(seed, 40s, 180s)` × 0.35 hPa，`seed = start_timestamp` |
| 分辨率 | 量化到 0.01 hPa（主流气压计的读数分辨率） |

选 44330 / 5.255 这组常数的原因很实际：Android 的 `SensorManager.getAltitude()`
用的就是同一组常数，所以目标应用把我们的气压换算回海拔，落点与 GPS 海拔只差几米，
不会出现"两个海拔"。

`xposed` 侧新增唯一出口 `RouteEngine.realisticPressureHpa(config, now)`（内部调用
`realisticAltitude`），并接三条路径：

| 设备情况 | 路径 | 做法 |
|---|---|---|
| 本机有气压计 | `dispatchSensorEvent` / `onSensorChanged` | **改写**真实事件的值与时间戳，保留系统自己的上报频率 |
| 本机没有气压计 | `getDefaultSensor` / `getSensorList` | 注入虚拟 `TYPE_PRESSURE`（分辨率 0.01 hPa、功耗 0.007 mA，与 BMP 系列一致） |
| 注入的虚拟传感器 | `dispatchPressureEvents` | 独立 1 Hz 派发，静止时也不停 |

第三条要独立于计步派发，是因为 `dispatchStepEvents` 在速度 ≤ 0.05 时整段提前返回
（静止时不该推进数），而真机气压计在静止时照样按固定频率上报——挂在计步链上会让
静止阶段的读数断流，`Δp/Δt` 出现不该有的空档。

实测（30 分钟 @1 Hz，基准海拔 25 m，变化范围 10 m，漂移 2 m）：

| 指标 | 数值 |
|---|---|
| 读数跨度 | 0.720 hPa（1010.28 ~ 1011.00） |
| 不同取值个数 | 73 |
| 换算回海拔的最大偏差 | 2.59 m（纯公式部分为 0.000 m，偏差全来自那 0.35 hPa 波动） |
| 20 个不同场次同一时刻的取值 | 14 种 |
| 相邻两秒最大变化 | 0.03 hPa（1200 秒里 372 秒读数不变） |
| 海平面 / 100 m / 1000 m | 1013.25 / 1001.30 / 898.76 hPa |

开关：配置键 `barometer_simulation`（缺省 **true**，只有 `active` 时生效）。设备本身没有
气压计时，注入虚拟传感器也受这个开关控制——否则会凭空多出一个传感器，成了新指纹。

仍未做：

* App 侧没有这个开关的 UI（只能通过配置文件里显式写 `false` 关掉）；
* 没做温度通道（`TYPE_AMBIENT_TEMPERATURE`），真机气压计通常同时暴露温度；
* 虚拟气压计的传感器名/厂商是固定的 `Pressure Sensor / Android`，与真实设备型号无关。

### 4.7 海拔只覆盖了椭球高，海拔高还是真实值（已实施）

`Location.getAltitude()` 报的是**椭球高**（WGS-84 椭球面之上的高度）；Android 14（API 34）起另有一组
`hasMslAltitude()` / `getMslAltitudeMeters()`，报的是**海拔高**（大地水准面之上）。真机 GNSS 定位两者
同时带，差值就是当地的大地水准面差距——在中国境内大致是 −10 ~ +40 m 量级，随位置变化。
**两者本就该一起动，只改一个反而给了对方一个参照物。**

原来的做法只改前者：

| 路径 | 原状 |
|---|---|
| 新建 `Location`（`buildFakeLocation`） | 从头构造，不带 MSL 字段——属于「缺失」，相对安全 |
| 就地改写既有 `Location`（`applyFakeLocationFields`，7 个调用点） | 保留系统填进去的**真实 MSL**，于是同一个对象上「椭球高是模拟值、海拔高是真实值」 |
| Google 融合定位（`GoogleAndAddressHooker` 的 `onLocationResult` / `onSuccess`） | 只改坐标，**海拔根本没改**，连椭球高都还是真实设备的 |

改法：**不设，统一清。** 没有大地水准面模型，硬编一个 MSL 值只会引入新的错误差值；而「模拟定位没有
MSL」在多数场景下反而与普通网络定位一致（网络定位通常不带 MSL）。实现是一个最小反射工具：

```kotlin
internal object LocationAltitudeMetadata {
    private val CLEAR_METHODS = arrayOf("removeMslAltitude", "removeMslAltitudeAccuracy")
    fun clear(location: Any?) {
        if (location == null) return
        for (name in CLEAR_METHODS) {
            try { location.javaClass.getMethod(name).invoke(location) } catch (_: Throwable) {}
        }
    }
}
```

用反射而不是直接调用：API 34 以下没有这两个方法，反射保证编译期与运行期都不受影响。

落点选公共咽喉：`applyFakeLocationFields` 末尾统一清一次，覆盖它的 7 个调用点；另外 4 处就地改写
（`GoogleAndAddressHooker` 2 处、`LocationProviderHooker` 2 处）各自补一次。ColorOS 16 原本自己清
（`removeMslAltitude`），现在统一由公共咽喉处理，那条路径上的重复调用已删掉。

顺带修掉同一个函数里的两个常量：

* `setVerticalAccuracyMeters(1.5f)` → `(accuracy * 1.7f).coerceIn(3.0, 20.0)`。真机垂直精度通常是水平精度的
  1.5~2 倍，并随水平精度一起变；写死 1.5 等于全设备同一个数，而且是这个函数里唯一没跟随精度的字段。
* extras 里的 `satellites=20 / satellites_in_view=20 / satellites_used_in_fix=18` → 改用 §4.5 的三口径模型。
  原来那组数既是常量，又与 `GnssStatus` 通道报的颗数不同源，是这轮里最容易对齐的跨通道矛盾。

验证：`xposed` 新增 3 项单测——带两个 MSL 方法的对象（两个都被调用）、不带任何相关方法的对象
（不误伤其它方法）、只有其中一个方法的对象（仍被调用），外加 `clear(null)` 与 `clear(Any())` 不抛异常。

诚实边界：`removeMslAltitude` 是 API 34 才有的接口，Android 13 及以下本来就没有这个字段，清与不清
没有区别；另外在 API 34+ 上「系统 GNSS 定位却完全没有 MSL」本身也是一种可探测特征——只是在
「有 MSL 但数值是真机的」与「没有 MSL」之间，后者暴露的信息更少。

### 4.8 首个 fix 的耗时被写死成 1000 ms（已实施）

`GnssStatus.Callback#onFirstFix(ttffMillis)` 报的是「从启动 GNSS 到拿到第一个定位」的耗时。真机上这个数
不固定：冷启动秒级到十几秒，热启动几百毫秒；但**一次会话内稳定**，不会来回跳。

原来这条通道固定回 `1000`（`SystemLocationServiceHooker.kt:365`），同时 `GpsStatus` 那条老通道的
`mTimeToFirstFix` 根本没动——于是同一次会话里两条通道给出两个不同的数（一个是常量 1000、一个来自框架），
而且 1000 本身是所有用户共享的定值。

改法：`RouteEngine.realisticTimeToFirstFixMs(config)` 用会话的 `start_timestamp` 派生一个
`700~2999 ms` 的值——同场次恒定（符合真机语义）、换场次就变；`onFirstFix` 与 `GpsStatus.mTimeToFirstFix`
两处都用它，两条通道因此同值。

验证：新增 1 项单测（同场次重复取值相等、落在 700~2999、40 个不同场次至少 10 种取值、
缺配置时仍给出合法值而不是 0 或 −1）。

诚实边界：TTFF 与「实际等了多久」没有关系——我们在注册后立即派发，真机是等若干秒才首个 fix。
这一条只消除了「数值本身是常量/跨通道不一致」，没有改派发时序（那会改变应用的等待行为，需要单独评估）。

### 4.9 NMEA 的 GSV/GSA 根本没改写（已实施）

这是同一类问题的另一面，但更直接：**GSV/GSA 里带着真实的卫星编号**。

#### 原状

`NmeaSpoofing` 只改 `GGA`/`RMC`/`GLL` 的坐标与时间，`GSV`/`GSA` 原样放行。于是：

| 通道 | 内容 | 来源 |
|---|---|---|
| `GGA` 第 8 字段 | 我们伪造的参与解算颗数（`SatelliteModel`） | 伪造 |
| `GSV` | 每颗卫星的 **PRN、仰角、方位、信噪比** | **真实星座** |
| `GSA` | 参与解算的 **PRN 列表** 与 PDOP/HDOP/VDOP | **真实值** |

任何解析 NMEA 的应用（地图、导航、轨迹、打车、以及各种反作弊 SDK）都能直接读到「当前看到的是哪几颗
真卫星、它们在天上的哪个方位、信噪比多少」。这不是「数值可疑」，是**把真实观测原样交了底**；而 `GGA`
里的颗数还是我们编的，两条信息一对就露。

#### 改法：整条重建，不用真机的数字

新文件 `core-geo/.../NmeaRewrite.kt` 负责把「一份卫星快照」翻译成合法的 NMEA 报文：

* `satellites(sky)` 把 `SatelliteModel.Sky` 转成带 PRN 的卫星表；信噪比沿用 GnssStatus 通道的
  同一条算式 `34 + 仰角/90*8`，落到 30~43 dB-Hz —— **两条通道对同一颗卫星给出同一个信噪比**。
* `gsvSentence(...)` 按每页 4 颗分页重建 `$XXGSV,总页数,页号,视野内总数,prn,仰角,方位,信噪比,...*校验`。
* `gsaSentence(...)` 重建 `$XXGSA,A,3,` + 12 个 PRN 槽位 + 三个 DOP；空槽留空（这是合法写法）。
* `dopFromAccuracy(accuracyM)` 让 DOP 与 `Location.getAccuracy()` 同源：
  `hdop = 精度/5`（夹 0.6~6.0）、`vdop = hdop*1.7`（夹 1~10）、`pdop = √(hdop²+vdop²)`。
  原来是写死的 `0.8`/`1.2,0.8,0.9`。
* 卫星号来自 `core-geo/.../SatelliteCatalog.kt`（与 GnssStatus 通道**同一份目录**），
  其中 GLONASS 的 PRN 要 `+64`，落在 65~96 段——直接把星座内编号当 PRN 发出去是常见错法。

#### 两个不改写解决不了的问题，也一起处理了

1. **页数不够**：真机可能只发 1 页 GPS 的 GSV（8 颗），我们这份表需要 3 页。光把头部页数改成 3 是没用的，
   第 3 页永远不来，任何按页聚合的解析器都停在半个周期上。→ `NmeaRewrite.missingPages(真机总页数, 真机页号, 我们的页数)`
   算出还差哪几页，由 hook 在真机最后一页之后补发（`NmeaSpoofing.emitMissingGsvPages`）。
2. **星座缺席**：真机在此刻可能根本不发北斗/GLONASS 的 GSV，而 `GGA` 报的是四个星座合起来的颗数。
   → 每收到一条 NMEA 报文，就把这一秒还没出现过的星座补齐（`emitPendingConstellationSentences`），
   并给这一秒做个记账：**已经由我们补出来的星座，真机后面再发同一星座就整条丢弃**，否则同一页会来两次。

补发与丢页都走「`spoofNmeaMessage` 返回 null 即丢弃」和「直接调用目标监听器」两条既有路径，
不新增注入通道。

#### 顺带修掉的目录问题

原模板把同一星座的槽位排在连续区间里（12 北斗 + 12 GPS + 4 GLONASS + 4 Galileo）。`SatelliteModel.sky()`
按会话种子取连续窗口，于是某些会话的窗口整段落进一个星座 —— 实测出现过「GPS 只剩 1 颗可见、北斗 12 颗」，
在亚太也说不通，而且 `GGA` 的颗数与分星座 GSV 的颗数明显失衡。改成四家交错排列后，任何窗口都能取到全部四家。

#### 实测（纯函数探针，`SatelliteModel` + `NmeaRewrite`）

一次会话（种子 42、`inView=22`、`usedInFix=15`）：GPS 9 颗 / 北斗 9 颗 / 每颗都有合法 PRN。

真机只给一页 GPS GSV（真 PRN 5/13/15/18）时，重建后的三页与 `GSA`：

```
$GPGSV,3,1,09,07,052,169,38,08,027,347,36,09,070,092,40,11,068,198,40*74
$GPGSV,3,2,09,14,055,303,38,17,030,049,36,19,050,148,38,21,036,270,37*7E
$GPGSV,3,3,09,22,030,019,36*7E
$GPGSA,A,3,07,09,11,14,19,21,,,,,,,1.8,0.9,1.6*35
```

* 真机的 4 个 PRN 一个都没留下；我们的 PRN 全部来自目录。
* 校验和独立异或复核一致（`61 == 61`）。
* 5 个不同会话在同一时刻的 GPS 可见集合互不相同（`[1,3,6,7,8,9]` / `[3,6,7,8,9,11,14,17]` / …）。
* 分星座颗数 **加起来恒等于整份天空**，所以 `GGA` 的「参与解算颗数」不可能超过 `GSV` 里能数出来的
  「在视野颗数」——单测在 5 个种子 × 3 个时刻上把这个划分性质钉住。

#### 上机验收

监听器替换是本节的**前置条件**：proxy 没挂上，一条报文都不会被重建。上机时这里先出过一次事——
`LocationProviderHooker` 里写的是 `val args = chain.args`（别名）再 `args[i] = proxy`，而
`Chain.getArgs()` 在 Android 15 上返回不可变 List，于是 `UnsupportedOperationException` 被框架吞掉，
监听器换不上去、这一节等于没生效（细节见 §12.5）。改成 `ArrayList<Any?>(chain.args)` 后复验：

```
UnsupportedOperationException 计数 = 0
Exception in hooker 计数 = 0
I LocationSpoofer: [GPS_Spoofer] Detected addNmeaListener(OnNmeaMessageListener)! Starting active injector.   ← 出现 2 次
```

仍未验证的是**目标应用侧**：它的 NMEA 监听器收到的到底是不是这几行，需要应用自己把报文打出来才看得见，
本轮没有做（见 §12.6）。

#### 诚实边界

* 只改写了真机**实际发出**的报文；真机连 `GGA`/`GSV` 都不发（只用融合定位、不开放 NMEA）时，这条通道无事可做。
* 卫星编号仍是固定目录里的那 32 颗（换会话换窗口段，但全体用户共享同一份目录），要进一步去模板化需要
  与各星座真实的编号规则对齐，收益不明显、风险不小。
* 仰角/方位来自 `SatelliteModel.sky()` 的正弦演化，与真实星历无关——只要应用不做 TLE 校验就看不出来，
  做星历校验的（极少数）能看出这不像真实轨道。

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
| P1 | 气压计通道完全没做 | `BarometerModel` + `RouteEngine.realisticPressureHpa()` + `SensorStepHooker` | 气压与 GPS 海拔自洽（反算偏差 < 5 m）；1 小时读数不止一种；静止时不断流 | 已实施 |
| P1 | 就地改写只覆盖椭球高，MSL 海拔仍是真实值 | `LocationAltitudeMetadata` + `applyFakeLocationFields` | 同一 `Location` 上椭球高与 MSL 不出现「一真一假」；Google 融合定位路径不再漏改海拔 | 已实施 |
| P2 | 首个 fix 耗时写死 1000 ms、两通道不一致 | `RouteEngine.realisticTimeToFirstFixMs()` | 同场次恒定、换场次变化、`onFirstFix` 与 `GpsStatus.mTimeToFirstFix` 同值 | 已实施 |
| P1 | NMEA 的 GSV/GSA 原样放行，真实卫星号/几何/DOP 全泄露 | `NmeaRewrite` + `SatelliteCatalog` + `NmeaSpoofing` | GSV 里出现的 PRN 全部来自目录；三页拼起来无重复；GGA 颗数 ≤ GSV 数出来的在视野颗数 | 已实施 |
| P2 | 基准海拔缺省 0 导致全员海平面 | `SettingsManager.kt:129` / `LocationRepository` | 不出现跨账号的同一基准值 | 未改（需产品决策，见 §4.4） |
| P2 | 静止时位置精确等于配置坐标 | `LocationHooker` `isStationary` 分支 | 静止时仍有亚米级漂移 | 未改（见 §4.4） |

---

## 11. 本次改动的验证边界

本仓库这一轮改了 15 项（§1、§2、§3、§4.1、§4.2、§4.3、§4.5 卫星数与天空几何、§4.6 气压计、
§4.7 MSL 海拔与垂直精度、§4.8 首个 fix 耗时、§4.9 NMEA 的 GSV/GSA、§5、§6、计步初值）。
这批改动里有**一次真正的上机运行**（§12：APK 装进 Android 15 模拟器、Xposed 注入目标进程成功），
并且做到了**坐标这一项的端到端对照**（改配置即换城市，§12.6）；
但**没有做过带 root 的完整真机链路验证**，除坐标之外的数值也只有派发侧证据。
按验证强度分九类：


**① 全量编译（`core-geo` + `xposed`）**

- 用 `kotlin-compiler-embeddable 2.0.0` 直接以命令行编译两个模块的全部 73 个 Kotlin 源文件
  加 1 份 `BuildConfig` 桩（classpath = `android-34/android.jar` + libxposed API jar），
  产出 373 个 class，**退出码 0、无错误**。
- `core-geo` 用到的 `BuildConfig` 需要一份桩（真 BuildConfig 由 flavor 生成），
  桩只要 `const val GLOBAL_SCHEME: Boolean`。
- `app-ui` / `core-data` / `service` 三个模块的 Compose、高德/百度 Maps 依赖在本机拿不到，
  没有本地编译；它们的编译由仓库的 `build` 工作流在 CI 上兜（`assembleScopedDebug`）。

**② 单元测试（`core-geo` 10 个测试类、64 项 + `xposed` 5 个测试类、29 项）**

- 同样绕开 Gradle：编译 `core-geo/src/test` + JUnit 4.13.2 + hamcrest-core 1.3，
  用 `org.junit.runner.JUnitCore` 直接跑 → **OK (64 tests)**。
- `xposed` 侧同样方式跑 `RouteEngineTest` / `HotReloadHostTest` / `VendorRegistryTest` /
  `ColorOs16VendorTest` / `LocationAltitudeMetadataTest` → **OK (29 tests)**，其中包含两条既有断言：
  显式 `altitude_variation_m = 0` 时海拔恒为基准值（`assertEquals(80.0, …, 0.0)`），
  以及老配置（不写该键）落在 `base ± drift` 区间内。这两条是 §4.1 改动的回归护栏。
- §4.5 的卫星数在两侧都有护栏：`SatelliteModelTest` 9 项（区间、包含关系、时间起伏、
  同刻一致、跨场次分散，加天空几何的仰角范围、`usedInFix` 计数与仰角排名一致、连续无锯齿、
  不同会话看到不同星座段），`RouteEngineTest` 里 1 项从配置 JSON 走完整条链路断言同一组关系。
- 其中 `RoutePathTest` 覆盖开环折返、闭环前进、到站停留与 `GeoMath` 一致性，
  bearing 平滑改动后全部仍通过（关键：位置与朝向在直段上逐位不变）。
- §4.6 的气压通道两侧同样有护栏：`BarometerModelTest` 7 项（海平面基准、海拔单调性、
  低空梯度、往返换算、时间起伏、跨场次分散、量程夹断、量化步进），`RouteEngineTest` 里
  1 项从配置 JSON 走完整链路，断言「气压换算回的海拔」与「GPS 海拔」相差不超过一个量化步进。
- §4.7 的 MSL 清理有 3 项单测，覆盖「两个方法都有 / 一个都没有 / 只有其中一个」三种对象，
  外加 `null` 与无关对象不得抛异常。
- §4.9 的 NMEA 重建有 12 项单测：分页覆盖每颗卫星且头部字段正确、卫星不足时不留空页、
  校验和独立异或复核、GLONASS 的 PRN 落在 65~96、`ofTalker` 只留该星座、GSA 最多 12 颗且
  DOP 满足勾股关系、空槽留空、DOP 随精度单调且 VDOP>HDOP、各字段落在 NMEA 允许的整数范围、
  `missingPages` 只在真机最后一页后才补、补出来的页与前面的页拼起来不重复，
  以及「四个前缀的颗数加起来等于整份天空」这条划分性质（5 个种子 × 3 个时刻）。

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

**⑦ 数值对照（气压计，30 分钟 @1 Hz）**

- 同一份采样代码，基准海拔 25 m、变化范围 10 m、漂移 2 m：读数 `1010.28 ~ 1011.00`，
  跨度 **0.720 hPa**、**73 种**取值；改前这条通道根本不存在，也就没有读数。
- 把我们的气压按 ISA 反算回海拔，与 GPS 海拔的最大偏差 **2.59 m**；纯公式部分偏差
  **0.000 m**，那 2.59 m 全部来自 0.35 hPa 的慢波动——也就是说目标应用无论用哪条通道，
  海拔都对得上（真实 GNSS 海拔自身的误差本来就在 5~10 m）。
- 20 个不同场次在同一时刻：14 种取值（1010.3 ~ 1010.7 hPa），不是全体同一个数。
- 相邻两秒的最大变化 0.03 hPa，1200 秒里有 372 秒读数不变——这是 0.01 hPa 分辨率下
  应有的表现，不是每帧乱跳。
- ISA 基准点自检：0 m → 1013.25 hPa、100 m → 1001.30 hPa、1000 m → 898.76 hPa。

**⑧ 数值对照（NMEA 报文重建，纯函数）**

- 一次会话（`seed=42`、`inView=22`、`usedInFix=15`）分星座为 GPS 9 颗 / 北斗 9 颗，
  真机给的 `PRN 5/13/15/18` 一个都没留下。
- 真机只念 1 页 GPS 的 GSV 时，重建后是 3 页（`,3,1,09,…` / `,3,2,09,…` / `,3,3,09,…`），
  三页拼起来正好 9 颗、无重复；`GSA` 的 12 个 PRN 槽位里前 6 个有值、其余留空。
- 校验和独立异或复核一致（`61 == 61`）。
- 5 个不同场次同一时刻的 GPS 可见集合互不相同（`[1,3,6,7,8,9]` / `[3,6,7,8,9,11,14,17]` / …）。
- 这一节证明的是「报文本身合法、内部自洽、不是真机那份」，**不证明目标应用真的会走这条通道**——
  多数应用直接用 `Location`/`GnssStatus`，只有解析 NMEA 的才看得到。

**⑨ 上机（模拟器：模块注入 + DEBUG 兜底 + 派发侧日志，详见 §12）**

- debug 包（`assembleScopedDebug`，`versionName 3.0.21-beta-3`，arm64-v8a）装进 Android 15 x86_64 模拟器，
  启动正常；LSPosed 启用模块并把 `com.autonavi.minimap` 加进作用域后，目标进程日志里出现
  `[INJECTED] Hooking package`、`Anti-detection hooks installed`、`GnssStatus hooks installed`、
  `Wi-Fi environment hooks installed`——**Hook 确实装上了**。
- 应用拿不到 root，正式配置快照发布不出来（§12.3 有完整证据），因此加了 **DEBUG 构建专用**的兜底配置源
  与一条模型摊平日志（§12.4）；这条兜底在设备上**跑通了**，日志里有：
  `pos=… speed=0.00 alt=121.44 hPa=998.84 sat=20/21/17 ttff=1127 nmea=$GPGSV,2,1,07,…*7D`，
  20 秒后同一文件重载给出 `alt=121.66 / hPa=998.78`——**十五项改动第一次在设备上被真实求值**。
- 上机当场抓到并修掉两个 `Chain.args` 不可变问题（§12.5），修完后
  `UnsupportedOperationException` 与 `Exception in hooker` 计数**归零**，
  `[GPS_Spoofer] Detected addNmeaListener(...)` 出现两次（= §4.9 的 proxy 真的挂上了）。
- **坐标做到了端到端**：只改配置文件里的经纬度（杭州 → 三亚），目标应用首页的城市与天气随之改变
  （`uiautomator dump` 数城市名，换前 `杭州:1/三亚:0`，换后 `杭州:0/三亚:1`，详见 §12.6）。
- 其余数值（卫星颗数、海拔、气压、NMEA 原文）仍只有派发侧证据：应用内部读数取不到。

**仍然不能确定的是真机运行时行为**：反射构造 `SensorEvent` 的三条 fallback 路径、
不同 ROM 下 `TYPE_STEP_DETECTOR` 的实际投递节奏、`Thread.sleep` 网格在真机上的实际抖动
（§6 的 996/1004 ms 来自另一套独立实现的外部实测，不是本仓库测的）、
虚拟 `TYPE_PRESSURE` 传感器能否被目标应用正常枚举、
`removeMslAltitude` 在真机 Location 对象上的实际可用性（反射失败会静默跳过）、
以及 ColorOS 16 适配路径的实机命中情况、有 root 的真机上「应用发布配置 → 模块收到快照」这条正式链路。


上真机后建议按下面附录逐条量，并把结果回填到本文件。

## 12. 上机验证：跑到哪一步、卡在哪

这一节记录一次真实上机（Android 15 / x86_64 模拟器），包括失败的部分——失败的原因本身
就是「本项目的部署前提」的一部分。

### 12.1 环境

- 模拟器 MuMu，Android 15，`ro.product.cpu.abi=x86_64`。我们的产物只有 `arm64-v8a` 与 `armeabi-v7a`，
  靠模拟器的转译层安装：`adb install` 成功，`versionName=3.0.21-beta-3`、
  `primaryCpuAbi=arm64-v8a`、`targetSdk=37`、`minSdk=26`。
- 设备上已装 LSPosed（zygisk，`lspctl status` 显示 `API 102`、`System server: connected`）与 KernelSU。

### 12.2 注入验证（通过）

```
lspctl module enable --user 0 com.vincenthzr.locationspoofer
lspctl scope add    --user 0 com.vincenthzr.locationspoofer com.autonavi.minimap
```

之后启动目标应用，logcat：

```
I LocationSpoofer: [INJECTED] Hooking package: com.autonavi.minimap process=com.autonavi.minimap
I LocationSpoofer: [LocationSpoofer] Anti-detection hooks installed
I LocationSpoofer: [LocationSpoofer] GnssStatus hooks installed (High-Performance Engine)
I LocationSpoofer: [LocationSpoofer] Wi-Fi environment hooks installed
```

同一进程里其余 SDK 探测都按预期跳过（`TencentLocation SDK not found`、
`MyLocationData class found=false`、`BaiduMap class found=false`、Google Play Services 未装）。

**这一步证明**：模块在 Android 15 + LSPosed API 102 上能装载、能按作用域注入目标进程、
能把各条通道的 hook 装完。**不证明**改写出来的数值对不对——那要有 active 配置。

### 12.3 配置通道（失败，且有解释）

- 模块不自己读配置文件，而是接收框架下发的快照：`FrameworkConfigReceiver` 用
  `getRemotePreferences("locationspoofer_config")` 拿指针，必要时 `openRemoteFile(...)` 拿超限的 payload。
  写入方是应用进程，经 libxposed Service 的 `FrameworkConfigStore.commit(...)` 落到框架自己的存储里
  （`core-data` 的 `FrameworkConfigPublisher` 注释写明「The framework owns storage」）。
- 应用在这台设备上拿不到 root：KernelSU 只有内核态（`ksud` 能跑、模块列表正常），
  但**没有 `su` 用户态二进制**——`/system/bin/su`、`/system/xbin/su`、`/data/adb/ksu/bin/su` 都不存在，
  `ksud install` 报 `No such file or directory`。应用于是停在「需要 Root 权限」页，也不会发布配置。
- 试过把快照信封手写进 `shared_prefs`（两个可能的名字、正确的属主与 660 权限），
  模块一直报 `[Config] Framework receive failed: IllegalArgumentException: Unsupported config version`；
  把内容改成非法 JSON 后**错误信息一模一样**，说明它读到的并不是这两个文件。
  ⇒ 快照只能由应用侧发布，绕不过去。这条死路不再尝试。

### 12.4 为此加的 DEBUG 兜底（只在 debug 构建里存在）

`LocationHooker` 新增 `startDebugConfigFallback()`：

- 第一行 `if (!BuildConfig.DEBUG) return`——Release 构建里这条路径不存在；
- debug 构建下起一个守护线程，每 2 s 看 `/data/local/tmp/locationspoofer-debug-config.json`
  （`lastModified` 变了就重新加载），加载时打一行 `[DebugConfig] 已加载 ...`；
- 同一处再打一行**模型摊平日志**，把这一轮所有新增模型一次摊开：
  `pos=… speed=… alt=… hPa=… sat=可见/视野/参与解算 ttff=… nmea=<GP GSV 第 1 页>`，
  上机时 `adb logcat -s LocationSpoofer` 就能逐项核对，不需要目标应用配合。

这是**测试脚手架**而不是产品功能：它存在的理由只是「在没有可靠 root 的设备上也能验证模型」，
边界写在代码注释里，Release 构建里不可达。

### 12.5 上机立刻抓到一个只在真机上才会犯的错

调试兜底一生效，每个 `onSatelliteStatusChanged` 后面都跟着一条：

```
W LSPosedFramework: Exception in hooker
W LSPosedFramework: java.lang.UnsupportedOperationException
W LSPosedFramework: 	at java.util.Collections$UnmodifiableList.set(Collections.java:1548)
W LSPosedFramework: 	at com.vincenthzr.locationspoofer.xposed.hooks.GnssStatusHookerKt$hookGnssStatus$...
```

原因：`XposedInterface.Chain.getArgs()` 返回的是 `List`，这里按习惯写成了
「`chain.args[0] = 伪造值` 然后 `proceed(args)`」，而 Android 15 上这个 List 是
`Collections.unmodifiableList(...)`——下标赋值直接抛 `UnsupportedOperationException`。
后果很隐蔽：**整条卫星通道的替换被吞掉，应用看到的仍是真机的真实卫星**，
而且外层有保护性 Hook 兜住了异常，界面不崩、只在日志里留一行 `W`。

全仓扫过一遍，同样的写法还有一处：`AMapHooker` 的 `setMockEnable`。

修法：新增扩展 `Chain.replaceArg(index, value)`——复制一份实参数组再 `proceed(patched)`，
不碰框架给的只读 List；两处都改用它。

**但第一版修完后异常并没有归零**，还剩两处，栈换成了另一条通道：

```
W LSPosedFramework: java.lang.UnsupportedOperationException
W LSPosedFramework: 	at java.util.Collections$UnmodifiableList.set(Collections.java:1548)
W LSPosedFramework: 	at ...LocationProviderHookerKt$hookLocationProviders$...intercept
W LSPosedFramework: 	at android.provider.Braistnt.addNmeaListener(Braistnt.java)
```

`LocationProviderHooker` 里写的是 `val args = chain.args`（**别名**），再 `args[i] = proxy`。
这条通道恰好就是本轮 §4.9 新增的 NMEA 重建入口——也就是说：**§4.9 的代码在真机上第一次跑，
`addNmeaListener` 的监听器根本没被换成 proxy，一条 GSV/GSA 都没机会重建**。
编译过、单测全绿、纯函数数值对照完全对，但它只在「真的有一个框架在回调你」的时候才暴露。

修法：`val args = ArrayList<Any?>(chain.args)`，只动这一处；`proceed` 用 `args.toTypedArray()`。

补一句踩坑记录：本想把这件事一次性解决——在 `XposedHelpers` 的 DSL 层包一个「实参可变」的
`Chain` 实现，所有调用点自动受益。试了两种写法都过不了编译：

- 直接 `class MutableArgsChain(private val delegate: Chain) : Chain` → 报
  `does not implement abstract member 'proceedWith'`（`Array<out Any?>` / `Array<Any?>` / `vararg` 都报同一句）；
- 改 `by delegate` 委托再覆盖属性 → 报 `'args' overrides nothing`（`args` / `executable` / `thisObject`
  三个属性都无法显式覆盖）。

libxposed API 102 的 `Chain` 在 Kotlin 里不接受这两种形态，于是放弃包装层，改为**逐个调用点修**，
并用「按唯一上下文锚点替换 + 断言只命中一次」的脚本（`String.Replace` 会误伤同名的 12 处 `proceed`）。

修完后在模拟器上复验（装的是修好后的 CI 产物）：

```
UnsupportedOperationException 计数 = 0
Exception in hooker 计数 = 0
I LocationSpoofer: [GPS_Spoofer] Detected addNmeaListener(OnNmeaMessageListener)! Starting active injector.   ← 两个监听器
I LocationSpoofer: [DebugConfig] pos=...,... alt=121.44 hPa=998.84 sat=20/21/17 ttff=1127 nmea=$GPGSV,2,1,07,...*7D
I LocationSpoofer: [getCellLocation:called:true:0] ... lat=...,... lng=...
```

⇒ NMEA 监听器这次真的被替换成功了（`Starting active injector` 出现两次，对应两个注册的监听器），
§4.9 的 GSV/GSA 重建在真机上接通；海拔、气压、卫星三口径都随时间在变。

### 12.6 端到端证据：改配置 → 目标应用跟着变

到 12.5 为止只能证明「我们派发了什么」。为了证明「目标应用真的读了我们的坐标」，做了一次
**只改一个变量**的对照：除了配置文件里的经纬度，其余（作用域、APK、目标应用、模拟器）全部不动。

| 配置里的坐标 | 目标应用（高德地图）首页读到的内容 |
|---|---|
| `30.274085, 120.15507` | 「今日**杭州市**天气」「今日 15° 至 20°，小雨」 |
| `18.254925, 109.512026` | 「今日**三亚市**天气」「今日 22° 至 32°，多云」，状态栏气温 26℃ |

判定手段不是肉眼看截图，而是 `adb shell uiautomator dump` 把界面树抓成 XML 后数城市名：
换配置前 `杭州 : 0 / 三亚 : 1` 的反面是换之前 `杭州 : 1 / 三亚 : 0`，两次都只有一个城市命中。
即：**应用取到的城市完全由我们的配置决定**——这条链路（模块 → 目标进程的 `LocationManager`/定位回调
→ 应用的地理围栏与天气请求）是通的。

还没取的数值是「应用侧读到的卫星颗数、海拔、NMEA 原文」——那需要应用自己打印，
模拟器上没有可用的宿体；本轮只做到「坐标这一项端到端」。

### 12.7 这一步跑到哪儿了

**已经跑通**：

- 模块在 Android 15（x86_64 模拟器，ARM 转译）上装载、hook 目标进程（12.2）；
- DEBUG 兜底读文件、把整轮模型摊平进一行日志（12.4）——**这是十五项改动第一次在设备上被求值**；
- `Chain.args` 不可变这个真机专属 bug 修完并复验归零（12.5）；
- 坐标这一项**端到端**：换配置即换城市（12.6）。

**还没跑通**：

- 有 root 的真机链路（应用发布配置 → 模块收到快照 → 目标应用读到数值）完全没走：
  这版 MuMu 上 KernelSU 只有内核态没有 `su`，应用永远过不了自己的 root 门禁；
- 因此上机验证用的是**调试兜底配置源**，不是正式通道；
- 除坐标外的其余数值（卫星颗数、海拔、气压、NMEA 原文）只有派发侧证据，没有应用侧证据。

所以本轮的结论强度是：**「编译 + 单测 + 纯函数数值对照 + 注入验证 + 派发侧上机日志 + 坐标端到端」**。

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
9. **气压计与海拔自洽**：注册 `TYPE_PRESSURE`，把读数按 ISA 换算成海拔，与同一时刻
   `Location.getAltitude()` 比——差值应在一个量化步进到几米之间，不能是"气压在海平面、
   GPS 在山上"这种量级差。再静止不动 5 分钟，读数应仍有小幅变化（真机气压计不会跳数停摆）。
   设备本身没有气压计时，确认目标应用枚举到的是我们注入的虚拟气压计，且关掉开关后它消失。
10. **海拔高与椭球高同源**：API 34+ 上同一时刻读 `Location.hasMslAltitude()` / `getMslAltitudeMeters()`
    与 `getAltitude()`——不能让「椭球高是模拟值、海拔高还是设备真实值」同时出现在一个对象里。
    再确认 `hasVerticalAccuracy()` 的字段不是恒定值（真机随定位质量变化）。
11. **TTFF 与首个 fix 一致**：同一时刻读 `GnssStatus.getTimeToFirstFix()`（或 `onFirstFix` 回调参数）
    与 `GpsStatus.getTimeToFirstFix()`——两处应相等且落在几百毫秒到几秒之间，不能是同一个常量、
    也不能一条是常量而另一条来自真实框架。
12. **卫星 extras 与 GnssStatus 同源**：同一时刻取 `Location.extras` 的
    `satellites / satellites_in_view / satellites_used_in_fix`，与 `GnssStatus` 里
    `usedInFix` 的颗数对比——两处必须相等，不能各说一套。
13. **NMEA 里没有真卫星**：注册 `OnNmeaMessageListener`，把收到的 `GSV` 全部收集起来，
    比对两件事——① 出现的 PRN 集合与真实设备开定位时记录的 PRN 集合**没有交集**；
    ② 各星座 GSV 里数出来的颗数加起来 ≥ 同一时刻 `GGA` 第 8 字段的颗数。
    再确认同一秒内 `GSV` 的「总页数/页号」能完整走完一圈（没有永远等不到的那一页），
    以及不同星座的 `GSV` 不会被同一页发两遍。
