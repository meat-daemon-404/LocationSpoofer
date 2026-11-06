package com.vincenthzr.locationspoofer.utils

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 气压计模型：把「海拔」换算成「气压」，供 `Sensor.TYPE_PRESSURE` 通道使用。
 *
 * 为什么需要它：真机上的气压计与 GPS 海拔是两个独立的物理通道，但它们在同一个大气模型下
 * 必须互相自洽。轨迹模拟只改 GPS 海拔、不碰气压计时，目标应用把两个通道一对比就能发现
 * 「GPS 说在山上、气压计说在海平面」。反过来，如果气压计只是照抄一个常量，又会退化成
 * 「跨会话完全相同的曲线」这一类最廉价的特征。
 *
 * 计算口径取国际标准大气（ISA）的对流层近似：
 * `P = P0 * (1 - h / 44330) ^ 5.255`，`P0 = 1013.25 hPa`。
 * 上式在 `h ∈ [-500, 10000] m` 内单调、可逆，和 Android 侧
 * `SensorManager.getAltitude` 用的正是同一组常数，因此我们给出的气压与系统自带的
 * 气压-海拔换算结果一致（同一台设备、同一时刻换算回去必须落在同一个海拔上）。
 *
 * 叠加的慢波动是真实气压计每天都有的量级：天气与室内气流带来的几个十分之一 hPa 的缓慢起伏。
 * 用 [MotionRealism.SmoothNoise] 而不是随机数，是为了让任意进程、任意时刻都能独立复算出
 * 同一条曲线（与海拔、卫星数两个模型保持同一套可复现约定）。
 */
object BarometerModel {

    /** 海平面标准大气压（hPa）。 */
    const val SEA_LEVEL_PRESSURE_HPA: Double = 1013.25

    /** ISA 对流层近似指数。 */
    private const val EXPONENT: Double = 5.255

    /** 气压计只在这段海拔范围内有物理意义，超出就夹住，避免算出 0 或负数。 */
    const val MIN_ALTITUDE_M: Double = -500.0
    const val MAX_ALTITUDE_M: Double = 10_000.0

    /** 慢波动幅度（hPa）：真机静止时气压分钟内起伏约 0.05~0.3 hPa，取上限附近以保证可见。 */
    const val SWING_HPA: Double = 0.35

    /** 气压分辨率（hPa）：主流气压计（BMP 系列）的读数分辨率在 0.01 hPa 量级。 */
    const val RESOLUTION_HPA: Double = 0.01

    /**
     * 纯海拔换算，不含波动。[altitudeM] 会被夹到 [MIN_ALTITUDE_M]..[MAX_ALTITUDE_M]。
     */
    fun pressureHpa(altitudeM: Double): Double {
        val altitude = altitudeM.coerceIn(MIN_ALTITUDE_M, MAX_ALTITUDE_M)
        return SEA_LEVEL_PRESSURE_HPA * (1.0 - altitude / 44_330.0).pow(EXPONENT)
    }

    /**
     * 带慢波动的气压：[seed] 决定曲线相位（用会话的 `start_timestamp`），
     * [elapsedSec] 是从会话开始算起的秒数。
     */
    fun pressureHpa(altitudeM: Double, seed: Long, elapsedSec: Double): Double {
        val base = pressureHpa(altitudeM)
        if (SWING_HPA <= 0.0) return base
        return base + SWING_HPA * noise(seed).value(elapsedSec)
    }

    /** 气压变化是否肉眼可见（同一会话内不同时刻取值不同）。 */
    fun varies(seed: Long, fromSec: Double, toSec: Double, stepSec: Double = 30.0): Boolean {
        if (stepSec <= 0.0 || toSec <= fromSec) return false
        var first = Double.NaN
        var t = fromSec
        while (t <= toSec) {
            val value = noise(seed).value(t)
            if (first.isNaN()) first = value else if (kotlin.math.abs(value - first) > 1e-9) return true
            t += stepSec
        }
        return false
    }

    /** 把气压换算回海拔，供自检与单元测试核对（与 [pressureHpa] 互逆）。 */
    fun altitudeM(pressureHpa: Double): Double {
        if (pressureHpa <= 0.0) return MIN_ALTITUDE_M
        return 44_330.0 * (1.0 - (pressureHpa / SEA_LEVEL_PRESSURE_HPA).pow(1.0 / EXPONENT))
    }

    /** 气压计读数按分辨率取整，模拟传感器的量化步进。 */
    fun quantize(pressureHpa: Double): Double =
        (pressureHpa / RESOLUTION_HPA).roundToLong() * RESOLUTION_HPA

    // 同一个 seed 复用同一条曲线；进程里只保留最近一条，避免每秒新建对象
    @Volatile private var cachedSeed: Long = Long.MIN_VALUE
    @Volatile private var cachedNoise: MotionRealism.SmoothNoise? = null

    private fun noise(seed: Long): MotionRealism.SmoothNoise {
        val existing = cachedNoise
        if (existing != null && cachedSeed == seed) return existing
        synchronized(this) {
            val again = cachedNoise
            if (again != null && cachedSeed == seed) return again
            val created = MotionRealism.SmoothNoise(seed xor SWING_SALT, 40.0, 180.0)
            cachedNoise = created
            cachedSeed = seed
            return created
        }
    }

    private const val SWING_SALT = 0x9E3779B9L
}
