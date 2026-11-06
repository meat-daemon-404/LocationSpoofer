package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地磁场的极简偶极模型。
 *
 * 真实地磁需要球谐模型（WMM/IGRF）加日变、区域异常，这里只保留两个对"通道自洽"有用的事实：
 * 总量随纬度升高、倾角随纬度增大（`tan I = 2 tan λ`）。绝对量级取 34 µT 的赤道基准，
 * 使 30°N 附近落在 45 µT 上下，与华东一带的实测总量同量级。
 *
 * 只用来让磁力计与航向 / 重力说同一件事：**手机平放、机头朝航向时，磁场的水平分量
 * 顺着机头方向、竖直分量朝下**。不追求区域精度。
 */
object GeomagneticModel {

    /** 赤道处的偶极场总量（µT）。 */
    const val EQUATORIAL_MICRO_TESLA: Double = 34.0

    /** 叠加的慢漂移幅度（µT），对应日变与附近电器干扰的量级。 */
    const val DRIFT_MICRO_TESLA: Double = 0.8

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI
    private const val DRIFT_PERIOD_SEC_MIN = 90.0
    private const val DRIFT_PERIOD_SEC_MAX = 480.0
    private const val DRIFT_SALT = 0x6D2B79F5L

    @Volatile
    private var cachedSeed: Long = Long.MIN_VALUE

    @Volatile
    private var cachedNoise: MotionRealism.SmoothNoise? = null

    private fun driftNoise(seed: Long): MotionRealism.SmoothNoise {
        val cached = cachedNoise
        if (seed == cachedSeed && cached != null) return cached
        return synchronized(this) {
            val again = cachedNoise
            if (seed == cachedSeed && again != null) {
                again
            } else {
                MotionRealism.SmoothNoise(
                    seed xor DRIFT_SALT,
                    DRIFT_PERIOD_SEC_MIN,
                    DRIFT_PERIOD_SEC_MAX
                ).also {
                    cachedNoise = it
                    cachedSeed = seed
                }
            }
        }
    }

    /** 该纬度上的磁场总量（µT）。 */
    fun totalMicroTesla(latitudeDeg: Double): Double {
        val s = sin(latitudeDeg.coerceIn(-90.0, 90.0) * DEG_TO_RAD)
        return EQUATORIAL_MICRO_TESLA * sqrt(1.0 + 3.0 * s * s)
    }

    /** 该纬度上的磁倾角（度，正值表示磁场指向地面）。 */
    fun inclinationDeg(latitudeDeg: Double): Double {
        val lat = abs(latitudeDeg.coerceIn(-90.0, 90.0)) * DEG_TO_RAD
        val s = sin(lat)
        val c = abs(cos(lat))
        return if (c < 1e-9) 90.0 else Math.atan2(2.0 * s, c) * RAD_TO_DEG
    }

    /**
     * 世界坐标系（东-北-天）里的磁场向量，单位 µT。
     * 忽略磁偏角，所以东向分量为 0、水平分量全部落在北向。
     */
    fun worldField(latitudeDeg: Double, seed: Long = 0L, elapsedSec: Double = 0.0): FloatArray {
        val total = totalMicroTesla(latitudeDeg) + drift(seed, elapsedSec)
        val inclination = inclinationDeg(latitudeDeg) * DEG_TO_RAD
        val horizontal = total * cos(inclination)
        val vertical = -total * sin(inclination)
        return floatArrayOf(0.0f, horizontal.toFloat(), vertical.toFloat())
    }

    /**
     * 设备坐标系里的磁场向量，单位 µT。
     *
     * 约定与 [MotionRealism.Session.gravity] 一致：Z 轴朝上、Y 轴朝机头、X 轴朝右手边。
     * 机头航向 `headingDeg` 相对正北顺时针。
     */
    fun deviceField(
        latitudeDeg: Double,
        headingDeg: Double,
        seed: Long = 0L,
        elapsedSec: Double = 0.0
    ): FloatArray {
        val world = worldField(latitudeDeg, seed, elapsedSec)
        val heading = headingDeg * DEG_TO_RAD
        val x = -world[1] * sin(heading)
        val y = world[1] * cos(heading)
        return floatArrayOf(x.toFloat(), y.toFloat(), world[2])
    }

    /**
     * 磁力计一次采样：在 [deviceField] 上叠一点各轴独立的慢噪声。
     * 噪声用同一条曲线派生三个相位，保证同一时刻重复调用结果一致。
     */
    fun sample(
        latitudeDeg: Double,
        headingDeg: Double,
        seed: Long,
        elapsedSec: Double
    ): FloatArray {
        val base = deviceField(latitudeDeg, headingDeg, seed, elapsedSec)
        val noise = driftNoise(seed xor 0x5F356495L)
        return floatArrayOf(
            (base[0] + DRIFT_MICRO_TESLA * noise.value(elapsedSec * 1.7)).toFloat(),
            (base[1] + DRIFT_MICRO_TESLA * noise.value(elapsedSec * 1.3 + 11.0)).toFloat(),
            (base[2] + DRIFT_MICRO_TESLA * noise.value(elapsedSec * 0.9 + 23.0)).toFloat()
        )
    }

    /** 采样序列里是否真的在变（自检用）。 */
    fun varies(seed: Long, latitudeDeg: Double, fromSec: Double, toSec: Double, stepSec: Double = 20.0): Boolean {
        var first: FloatArray? = null
        var t = fromSec
        while (t <= toSec) {
            val v = sample(latitudeDeg, headingDeg = 0.0, seed = seed, elapsedSec = t)
            val base = first
            if (base == null) {
                first = v
            } else if (base[0] != v[0] || base[1] != v[1] || base[2] != v[2]) {
                return true
            }
            t += stepSec
        }
        return false
    }

    private fun drift(seed: Long, elapsedSec: Double): Double =
        DRIFT_MICRO_TESLA * driftNoise(seed).value(elapsedSec)
}
