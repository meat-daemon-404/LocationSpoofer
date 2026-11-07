package com.vincenthzr.locationspoofer.utils

/**
 * 未校准（uncalibrated）传感器通道的零偏估计。
 *
 * Android 的 `TYPE_GYROSCOPE_UNCALIBRATED` / `TYPE_ACCELEROMETER_UNCALIBRATED` /
 * `TYPE_MAGNETIC_FIELD_UNCALIBRATED` 每次给 6 个值：前三个是**含零偏的原始读数**，
 * 后三个是器件自己估出来的零偏。系统还保证 `校准值 ≡ 原始值 − 零偏`。
 *
 * 只改写校准通道、不动未校准通道会留下一个显眼的不一致：应用把未校准的前三个值
 * 减掉后三个，得到的曲线与校准通道完全对不上（一个在动、一个静止）。
 *
 * 这里的做法是给每条通道一个**有界、缓慢漂移**的零偏，然后让上层按
 * `raw = calibrated + bias` 输出，于是 `raw − bias` 与校准通道逐位相等。
 *
 * 零偏量级参考真机器件：MEMS 陀螺在常温下零偏常在 0.01~0.05 rad/s，
 * 加速度计 0.02~0.1 m/s²，磁力计受硬铁干扰常在 0.5~3 µT。
 */
object SensorBiasModel {

    /** 陀螺零偏幅度上限（rad/s）。 */
    const val GYROSCOPE_BIAS_RAD_PER_SEC: Double = 0.02

    /** 加速度计零偏幅度上限（m/s²）。 */
    const val ACCELEROMETER_BIAS_MPS2: Double = 0.06

    /** 磁力计零偏幅度上限（µT）。 */
    const val MAGNETIC_FIELD_BIAS_MICRO_TESLA: Double = 1.1

    /** 零偏漂移的最短/最长周期（秒）：真机器件的零偏跟温度走，是分钟到十分钟量级。 */
    private const val MIN_PERIOD_SEC: Double = 240.0
    private const val MAX_PERIOD_SEC: Double = 1_200.0

    private const val GYROSCOPE_SALT = 0x51D0B1A5L
    private const val ACCELEROMETER_SALT = 0x2C7F3E11L
    private const val MAGNETIC_SALT = 0x7A1E55C3L

    /** 三轴的取样相位错开量（秒），避免三轴同步摆动。 */
    private const val AXIS_PHASE_SEC: Double = 37.0

    /** 哪条通道的零偏。 */
    enum class Kind(val scale: Double, val salt: Long) {
        GYROSCOPE(GYROSCOPE_BIAS_RAD_PER_SEC, GYROSCOPE_SALT),
        ACCELEROMETER(ACCELEROMETER_BIAS_MPS2, ACCELEROMETER_SALT),
        MAGNETIC_FIELD(MAGNETIC_FIELD_BIAS_MICRO_TESLA, MAGNETIC_SALT)
    }

    private class Model(private val seed: Long) {
        private fun noiseFor(kind: Kind, axis: Int): MotionRealism.SmoothNoise =
            MotionRealism.SmoothNoise(
                seed xor kind.salt xor (axis.toLong() * 0x9E3779B9L),
                MIN_PERIOD_SEC,
                MAX_PERIOD_SEC
            )

        private val gyroscope = Array(3) { noiseFor(Kind.GYROSCOPE, it) }
        private val accelerometer = Array(3) { noiseFor(Kind.ACCELEROMETER, it) }
        private val magnetic = Array(3) { noiseFor(Kind.MAGNETIC_FIELD, it) }

        fun bias(kind: Kind, elapsedSec: Double): FloatArray {
            val noises = when (kind) {
                Kind.GYROSCOPE -> gyroscope
                Kind.ACCELEROMETER -> accelerometer
                Kind.MAGNETIC_FIELD -> magnetic
            }
            return FloatArray(3) { axis ->
                (kind.scale * noises[axis].value(elapsedSec + axis * AXIS_PHASE_SEC)).toFloat()
            }
        }
    }

    @Volatile
    private var cachedSeed: Long = Long.MIN_VALUE

    @Volatile
    private var cachedModel: Model? = null

    private fun model(seed: Long): Model {
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
     * 某一时刻某条通道的三轴零偏。
     *
     * @param seed 会话种子（与其它模型一样取配置里的 `start_timestamp`），同种子同刻结果恒定。
     * @param elapsedSec 会话内已过的秒数；为负按 0 处理。
     */
    fun bias(kind: Kind, seed: Long, elapsedSec: Double): FloatArray =
        model(seed).bias(kind, if (elapsedSec > 0.0) elapsedSec else 0.0)

    /** 一段时间内零偏是否真的在变（自检用：不变就说明退化成常量了）。 */
    fun varies(
        kind: Kind,
        seed: Long,
        fromSec: Double,
        toSec: Double,
        stepSec: Double = 30.0
    ): Boolean {
        if (toSec <= fromSec || stepSec <= 0.0) return false
        val first = bias(kind, seed, fromSec)
        var t = fromSec + stepSec
        while (t <= toSec) {
            val current = bias(kind, seed, t)
            for (i in 0 until 3) {
                if (current[i] != first[i]) return true
            }
            t += stepSec
        }
        return false
    }

    /** 便于日志：`x,y,z` 保留四位小数。 */
    fun describe(values: FloatArray): String =
        values.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
}
