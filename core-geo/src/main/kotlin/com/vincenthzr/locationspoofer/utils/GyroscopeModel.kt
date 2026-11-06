package com.vincenthzr.locationspoofer.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 陀螺仪（[android.hardware.Sensor.TYPE_GYROSCOPE]，角速度 rad/s）的仿真模型。
 *
 * 为什么需要它：位置、步数、加速度、气压都能对得上，但设备陀螺仪仍在上报**真实**角速度。
 * 真机被放在桌上模拟"跑步"时，这个通道会稳定输出"完全静止"，与同一时刻 GPS 给出的
 * 3 m/s、步频 170 spm 直接矛盾——这是同一物理量在不同通道上对不上，比任何单一通道的
 * 数值异常都更显眼。
 *
 * 三条设计约束：
 *  1. **与航向自洽**：`z` 轴（绕竖直轴）的角速度以一个周期内均值为 [headingRateRadPerSec] 的
 *     形式给出，绕圈时积分回去就是航向变化率，不会出现"在转弯但陀螺仪不转"。
 *  2. **与步频同源**：俯仰（x）与横滚（y）按当前步频摆动，幅度随速度增长——走得越快，摆得越大。
 *  3. **静止时有微抖但绝不旋转**：速度 ≤ [STILL_SPEED_MPS] 时只剩 [STILL_SWING_RAD_PER_SEC] 量级的
 *     慢噪声，既不是死平的 0.000（那反而更假），也不会有任何"原地打转"。
 *
 * 纯函数设计：同一 `(seed, elapsedSec)` 在任何进程都应算出同一结果，因此不持有可变状态，
 * 与 [AltitudeModel] / [SatelliteModel] / [BarometerModel] 一致。
 */
object GyroscopeModel {

    /** 低于这个速度按静止处理（与步数积分、加速度改写用的是同一个门槛） */
    const val STILL_SPEED_MPS: Double = 0.05

    /** 静止时的抖动幅度（rad/s）：真机陀螺仪在桌面上通常就是这个量级 */
    const val STILL_SWING_RAD_PER_SEC: Double = 0.012

    /** 参考速度下的俯仰摆动幅度（rad/s）：跑步时躯干前后摆动约 0.5 rad/s 量级 */
    const val PITCH_SWING_RAD_PER_SEC: Double = 0.55

    /** 参考速度下的横滚摆动幅度（rad/s） */
    const val ROLL_SWING_RAD_PER_SEC: Double = 0.34

    /** 参考速度下的偏航摆动幅度（rad/s），叠加在航向变化率之上 */
    const val YAW_SWING_RAD_PER_SEC: Double = 0.22

    /** 摆动幅度按速度线性放大，到这个速度达到标称幅度 */
    const val REFERENCE_SPEED_MPS: Double = 3.0

    /** 速度放大系数的上限（再快也不会无限摆） */
    const val MAX_SPEED_FACTOR: Double = 1.6

    /** 单轴输出上限（rad/s），防止航向变化率异常时给出不可能的读数 */
    const val MAX_ABS_RAD_PER_SEC: Double = 8.0

    /** 静止时最小的步频占位，仅用于让噪声周期不随会话抖动 */
    private const val TWO_PI = 2.0 * PI

    private const val PITCH_SALT = 0x6A09E667L
    private const val ROLL_SALT = 0x3C6EF372L
    private const val YAW_SALT = 0x1F83D9ABL
    private const val STILL_X_SALT = 0x510E527FL
    private const val STILL_Y_SALT = 0x9B05688CL
    private const val STILL_Z_SALT = 0x1F83D9ABL xor 0x5BE0CD19L

    private class Model(seed: Long) {
        /** 步频摆动的慢调制（走一会儿大、一会儿小，避免幅度纹丝不动） */
        val pitch = MotionRealism.SmoothNoise(seed xor PITCH_SALT, 8.0, 40.0)
        val roll = MotionRealism.SmoothNoise(seed xor ROLL_SALT, 8.0, 40.0)
        val yaw = MotionRealism.SmoothNoise(seed xor YAW_SALT, 8.0, 40.0)

        /** 静止/行进都叠加的传感器本底噪声 */
        val stillX = MotionRealism.SmoothNoise(seed xor STILL_X_SALT, 0.2, 1.1)
        val stillY = MotionRealism.SmoothNoise(seed xor STILL_Y_SALT, 0.2, 1.1)
        val stillZ = MotionRelaxedNoise(seed xor STILL_Z_SALT)
    }

    /**
     * 静止时的 z 轴噪声：周期比 x/y 更长一些，因为绕竖直轴的漂移本来就慢。
     */
    private class MotionRelaxedNoise(seed: Long) {
        private val inner = MotionRealism.SmoothNoise(seed, 0.5, 3.0)
        fun value(t: Double): Double = inner.value(t)
    }

    @Volatile
    private var cachedSeed: Long = Long.MIN_VALUE

    @Volatile
    private var cachedModel: Model? = null

    private fun modelFor(seed: Long): Model {
        val hit = cachedModel
        if (hit != null && cachedSeed == seed) return hit
        synchronized(this) {
            val again = cachedModel
            if (again != null && cachedSeed == seed) return again
            val fresh = Model(seed)
            cachedModel = fresh
            cachedSeed = seed
            return fresh
        }
    }

    /**
     * 生成某一时刻的三轴角速度（rad/s）。
     *
     * @param speedMps 当前仿真速度；≤ [STILL_SPEED_MPS] 时按静止处理
     * @param cadenceSpm 当前步频（步/分）；静止时忽略
     * @param headingRateRadPerSec 航向变化率（rad/s，右转为正）；静止时应为 0
     * @param seed 会话种子（用 `start_timestamp`），保证同一场次可复现、不同场次不同
     * @param elapsedSec 该会话已过去的秒数
     */
    fun sample(
        speedMps: Double,
        cadenceSpm: Double,
        headingRateRadPerSec: Double,
        seed: Long,
        elapsedSec: Double
    ): FloatArray {
        val model = modelFor(seed)
        val t = if (elapsedSec.isFinite()) elapsedSec else 0.0
        val stillX = STILL_SWING_RAD_PER_SEC * model.stillX.value(t)
        val stillY = STILL_SWING_RAD_PER_SEC * model.stillY.value(t)
        val stillZ = STILL_SWING_RAD_PER_SEC * model.stillZ.value(t)

        if (!(speedMps > STILL_SPEED_MPS)) {
            return floatArrayOf(
                clamp(stillX).toFloat(),
                clamp(stillY).toFloat(),
                clamp(stillZ).toFloat()
            )
        }

        val speedFactor = (speedMps / REFERENCE_SPEED_MPS).coerceIn(0.0, MAX_SPEED_FACTOR)
        val cadence = if (cadenceSpm.isFinite()) cadenceSpm.coerceIn(30.0, 240.0) else 160.0
        val stepHz = cadence / 60.0
        val phase = TWO_PI * stepHz * t

        val pitch = PITCH_SWING_RAD_PER_SEC * speedFactor * model.pitch.value(t) * sin(phase)
        val roll = ROLL_SWING_RAD_PER_SEC * speedFactor * model.roll.value(t) * sin(phase + 1.9)
        // 绕竖直轴的躯干反向旋转每两步完成一个周期，所以取半步频
        val yawSwing = YAW_SWING_RAD_PER_SEC * speedFactor * model.yaw.value(t) * sin(phase * 0.5)

        val headingRate = if (headingRateRadPerSec.isFinite()) headingRateRadPerSec else 0.0
        return floatArrayOf(
            clamp(pitch + stillX).toFloat(),
            clamp(roll + stillY).toFloat(),
            clamp(headingRate + yawSwing + stillZ).toFloat()
        )
    }

    /** 一段时间内读数是否真的在变（自检用：恒定值本身就是指纹） */
    fun varies(
        speedMps: Double,
        cadenceSpm: Double,
        headingRateRadPerSec: Double,
        seed: Long,
        fromSec: Double,
        toSec: Double,
        stepSec: Double = 0.1
    ): Boolean {
        if (!(toSec > fromSec) || !(stepSec > 0.0)) return false
        val first = sample(speedMps, cadenceSpm, headingRateRadPerSec, seed, fromSec)
        var t = fromSec + stepSec
        while (t <= toSec) {
            val next = sample(speedMps, cadenceSpm, headingRateRadPerSec, seed, t)
            if (next[0] != first[0] || next[1] != first[1] || next[2] != first[2]) return true
            t += stepSec
        }
        return false
    }

    /** 一分钟内的平均 z 轴角速度（自检用：应当接近传入的航向变化率） */
    fun averageYawRate(
        speedMps: Double,
        cadenceSpm: Double,
        headingRateRadPerSec: Double,
        seed: Long,
        fromSec: Double,
        toSec: Double,
        stepSec: Double = 0.1
    ): Double {
        if (!(toSec > fromSec) || !(stepSec > 0.0)) return 0.0
        var sum = 0.0
        var count = 0
        var t = fromSec
        while (t <= toSec) {
            sum += sample(speedMps, cadenceSpm, headingRateRadPerSec, seed, t)[2]
            count++
            t += stepSec
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun clamp(value: Double): Double = max(
        -MAX_ABS_RAD_PER_SEC,
        kotlin.math.min(MAX_ABS_RAD_PER_SEC, value)
    )

    /** 仅用于自检打印：把三轴读数压成一行 */
    fun describe(values: FloatArray): String =
        "gyro=%.3f/%.3f/%.3f rad/s".format(values[0], values[1], values[2])

    /** 供测试拿整数刻度，避免浮点末位比较 */
    fun millisPerSec(values: FloatArray): IntArray =
        IntArray(3) { (abs(values[it].toDouble()) * 1000.0).roundToInt() }
}
