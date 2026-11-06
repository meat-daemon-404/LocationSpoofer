# 反检测实测提要

本文不是设计文档，是把一个**已经跑了几十场、并被真实判定链路处理过**的模拟定位场景里，
用落盘数据能直接量出来的东西，逐条对到本仓库的代码上。

数据来源：一套长期在用的 LSPosed 定位/传感器模拟实现（与 LocationSpoofer 同构但独立演进），
配套的 App 侧落盘数据（每场完整轨迹、每公里汇总、传感器事件流）与一次完整的
「判定 → 封禁 → 解冻」样本。以下所有数值都是设备实测，已剔除具体地点、账号与时间信息。

一句话结论：**抓到的从来不是 `isFromMockProvider`，而是同一个物理量在不同通道上对不上。**

> 落地状态：§1、§2、§4.2、§4.3 已在本次改动中实现并全量编译通过；§3、§4.1、§5、§6 仍是建议，
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

## 3. 折线顶点会制造瞬时转向尖峰（建议，未改）

`core-geo/.../RoutePath.kt:61` 的 `positionAt()` 是**沿折线线性插值**，
`bearing` 直接取当前段的 `GeoMath.bearing(from, to)`（`RoutePath.kt:88`）。

后果：每到一个折点，bearing 发生一次**阶跃**，阶跃幅度就是这个折点的转角。
实测（19 点折线、1.4 m/s、相邻采样 0.4 m）：bearing 逐点变化 **p99 = 36°，max = 70°**，
而且每个顶点都恰好出现一次。真实跑者过一个 90° 弯是 1–2 s 连续转，不是一帧转完。

修法：对闭合轨迹做**二次** B 样条重采样（每段 8 个采样点，19 → 152 点）。
实测效果：环长变化 −1.4%，转角被拉成连续过程，下游任何按 bearing 序列做一致性/FFT
分析的风控都看不到台阶。

两个坑：

- 用**三次**样条会向外鼓（环长 +2%），二次不会。
- 重采样后必须同步更新 `totalDistance` 与 `cumulative`，否则 `positionAt(distanceTraveled)`
  的时间轴会整体漂移，速度会跟着错。

---

## 4. 海拔与计步初值：常量是最廉价的特征

### 4.1 `realism_level = OFF` 时海拔就是常量（建议，未改）

`RouteEngine.kt:91` 的 `realisticAltitude()` 本身设计是对的——按 `realism_level` 叠加浮动。
但 `MotionRealism.kt:32` 的 `OFF(0, 0.0, ...)` 里 `altitudeAmplitudeM = 0.0`，
于是 `RouteEngine.kt:95` 直接 `return base`，`base` 是 `config.optDouble("altitude", 25.0)`。
而 `realismSession` 的 `realism_level` 默认取 `Level.OFF.id`。

即：**没开真实度（默认）的所有用户，海拔恒等于配置值；没配过的所有人恒等于 25.0。**

常量本身不是"某个账号异常"，而是**可跨账号聚合的全局指纹**——服务端把同一版本的
上报值按字段一聚合，一堆账号共享同一个常量，这种特征比单个账号的异常轨迹好抓得多。

建议：把"未配置"与"配置为固定值"区分开（例如用 `NaN` / 缺键表示未配置），
未配置时走内置的连续变化模型，而不是落回一个固定默认值。

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

---

## 5. 精度：现在是好的，但范围偏乐观（建议，未改）

`getJitteredAccuracy()` 用 OU 式游走（`2.2 + drift`，截断到 1.5–3.5 m），且不受
`realism_level` 影响，恒有起伏——这一点没问题。

只提一句：1.5–3.5 m 是"满格信号"档位。实测真实设备在城市环境上报的水平精度多在
**3–8 m** 之间起伏，遮挡场景会更差。始终报 1.5–3.5 m 的账号集合，本身就是
"永远在空旷操场中央"的分布——不是破绽，但它把可利用的分布信息压平了。
若要更贴，可让基准随所在区域/时段做慢变，而不是恒定基准。

---

## 6. 派发要钉绝对时间网格（建议，未改）

1 Hz 派发如果用「上一轮 + 1000 ms」自增（当前 `LocationHooker.kt:663` 的
`Thread.sleep(1_000L)` 本质如此），采样相位会一直漂移；下游按窗口算
`speed = Δd / Δt` 时，`Δt` 在窗口边界上抖动，速度就会出现**量化台阶**（在几个离散值之间跳）。

修法：每轮算 `next = ceil(now / period) * period`，然后 `postDelayed(next - now)`。
自检方式：记录连续派发时刻，量 `minGap`/`maxGap`——同构实现上做到
`minGap = 996 ms`、`maxGap = 1004 ms` 即认为干净。

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
| P1 | 折线顶点二次 B 样条平滑 | `RoutePath.kt:61` | 相邻 0.4 m 的 bearing 变化 p99 < 10° | 建议 |
| P1 | 未配置时海拔不落回固定默认值 | `RouteEngine.kt:91` | 连续 60 s、跨账号不共享同一常量 | 建议 |
| P2 | 派发钉绝对时间网格 | `LocationHooker.kt:663` | 派发间隔抖动 < 5 ms | 建议 |
| P2 | 精度基准随区域/时段慢变 | `getJitteredAccuracy()` | 长期分布的方差来自数据本身而非固定截断 | 建议 |

---

## 11. 本次改动的验证边界

已改的四项**只做了编译级验证**，没有打 APK、也没有上真机：

- 用 `kotlin-compiler-embeddable 2.0.0` 直接以命令行编译 `core-geo` + `xposed` 两个模块的
  全部 67 个 Kotlin 源文件（classpath = `android-34/android.jar` + libxposed API jar），
  产出 358 个 class，**退出码 0、无错误**。
- 因此可以确定的是：类型、符号、可见性、lambda 捕获都没有问题；
  **不能**确定的是真机运行时行为（尤其反射构造 `SensorEvent` 的三条 fallback 路径，
  以及不同 ROM 下 `TYPE_STEP_DETECTOR` 的实际投递节奏）。

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
