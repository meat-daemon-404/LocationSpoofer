package com.vincenthzr.locationspoofer.utils

import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * 静止时的定位抖动：真机即使一动不动，GPS 报出来的坐标也在几米范围内来回晃。
 *
 * 原来的实现在非路线模式下直接返回配置里的固定坐标，一个像素都不动 —— 用
 * `getLatitude()` 连续取十分钟会得到完全相同的一串数字，而真机在同样条件下会有
 * 亚米到数米级的漂移。两者一比就是"这不是真定位"的直接证据，且这种恒定值可以跨
 * 用户聚合，是所有指纹里最容易做的一条。
 *
 * 抖动由会话种子与绝对时间决定，所以各进程、各通道对同一时刻算出的偏移一致
 * （和 [AltitudeModel]、[SatelliteModel]、[BarometerModel] 一样是纯函数）。
 */
object PositionJitter {

    /** 偏移半径上限（米）。与真机静止时的定位散布同量级，远小于普通定位精度，不会让"停着的人"跑到马路上。 */
    const val MAX_RADIUS_M: Double = 2.2

    /** 输出量化步长（米）：真机坐标换算回经纬度后也会有最小分辨率，这里避免出现无限精度的假数字。 */
    const val RESOLUTION_M: Double = 0.05

    private const val EAST_SALT = 0x2F6A1C3BL
    private const val NORTH_SALT = 0x6B1D47E2L

    /** 1 秒内单轴的最大变化（米）。给单测与自检一个可断言的量级。 */
    const val MAX_STEP_PER_SEC_M: Double = 0.6

    private class Model(seed: Long) {
        val east = MotionRealism.SmoothNoise(seed xor EAST_SALT, 24.0, 150.0)
        val north = MotionRealism.SmoothNoise(seed xor NORTH_SALT, 31.0, 190.0)
    }

    @Volatile private var cachedSeed: Long = Long.MIN_VALUE
    @Volatile private var cached: Model? = null

    private fun model(seed: Long): Model {
        val hit = cached
        if (hit != null && cachedSeed == seed) return hit
        synchronized(this) {
            val again = cached
            if (again != null && cachedSeed == seed) return again
            return Model(seed).also {
                cached = it
                cachedSeed = seed
            }
        }
    }

    /**
     * 静止偏移（东, 北），单位米。半径被夹到 [MAX_RADIUS_M] 内，并按 [RESOLUTION_M] 量化。
     */
    fun offsetMeters(seed: Long, elapsedSec: Double): FloatArray {
        val m = model(seed)
        val t = if (elapsedSec.isFinite() && elapsedSec > 0.0) elapsedSec else 0.0
        var east = MAX_RADIUS_M * m.east.value(t)
        var north = MAX_RADIUS_M * m.north.value(t)
        // 先按"上限减一个量化步长"夹紧：量化会把每轴最多再推半个步长，
        // 直接用 MAX_RADIUS_M 夹紧会让结果偶尔超出上限一丁点。
        val limit = MAX_RADIUS_M - RESOLUTION_M
        val radius = hypot(east, north)
        if (radius > limit) {
            val scale = limit / radius
            east *= scale
            north *= scale
        }
        return floatArrayOf(quantize(east), quantize(north))
    }

    /** 一段时间内的偏移是否真的在变（单测与自检用） */
    fun varies(seed: Long, fromSec: Double, toSec: Double, stepSec: Double = 30.0): Boolean {
        var previous: FloatArray? = null
        var t = fromSec
        while (t <= toSec) {
            val current = offsetMeters(seed, t)
            val last = previous
            if (last != null && (last[0] != current[0] || last[1] != current[1])) return true
            previous = current
            t += stepSec
        }
        return false
    }

    fun radiusMeters(offset: FloatArray): Double = hypot(offset[0].toDouble(), offset[1].toDouble())

    fun describe(offset: FloatArray): String =
        "east=%.2f north=%.2f r=%.2f".format(offset[0], offset[1], radiusMeters(offset))

    private fun quantize(meters: Double): Float =
        ((meters / RESOLUTION_M).roundToLong() * RESOLUTION_M).toFloat()
}
