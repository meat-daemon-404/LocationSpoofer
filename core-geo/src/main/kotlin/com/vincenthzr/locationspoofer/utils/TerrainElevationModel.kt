package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 没配过海拔时的兜底值：按坐标估一个"地形高度"。
 *
 * 原来的缺省是 `"0.0"`，于是**所有没配过海拔的用户都停在海平面**——这是一个跨账号完全相同的常量，
 * 而且叠加变化范围后还能出现负海拔。真实跑步的人不会都在 0 米：绝大多数人在几十米到几百米。
 *
 * 这里不引入任何高程数据表（那会把安装包和网络依赖都拖进来），只用一个纯函数：
 * 三个尺度（约 7°、1.9°、0.45°）的正弦叠加造一个连续起伏场，再做立方偏斜让结果偏向低处，
 * 使中位数落在几十米、极少数位置才到千米级。同一坐标永远得到同一个值，跨进程、跨会话一致。
 *
 * 这是"量级上说得过去"，不是真实高程：真要精确到某座山，用户仍然要在设置里自己填。
 */
object TerrainElevationModel {

    /** 兜底值的下限（米）。不产生负海拔。 */
    const val MIN_M: Double = 0.0

    /** 兜底值的上限（米）。普通跑步场景里不会超过这个量级。 */
    const val MAX_M: Double = 2_000.0

    /**
     * 兜底值的下限起点（米）。
     *
     * 立方偏斜会把大量位置压到接近 0，再叠加 0.5 m 量化就会出现"很多人又都等于 0"——
     * 那等于把海平面常量换成另一个常量。加一个 20 m 的地面起点，让最平的地方也是几十米。
     */
    const val FLOOR_M: Double = 20.0

    /** 输出量化步长（米）：高程报数不会是无理数。 */
    const val RESOLUTION_M: Double = 0.5

    private const val LARGE_SCALE_DEG = 7.0
    private const val MEDIUM_SCALE_DEG = 1.9
    private const val SMALL_SCALE_DEG = 0.45

    private const val LARGE_PHASE_X = 0.83
    private const val LARGE_PHASE_Y = 2.31
    private const val MEDIUM_PHASE_X = 4.07
    private const val MEDIUM_PHASE_Y = 1.13
    private const val SMALL_PHASE_X = 5.51
    private const val SMALL_PHASE_Y = 3.67

    /**
     * 按坐标给出的兜底海拔（米）。纯函数：同一坐标任何时刻、任何进程都得到同一个值。
     */
    fun baselineMeters(latitudeDeg: Double, longitudeDeg: Double): Double {
        if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) return MIN_M
        val lat = latitudeDeg.coerceIn(-90.0, 90.0)
        val lng = normalizeLongitude(longitudeDeg)

        val large = wave(lng, lat, LARGE_SCALE_DEG, LARGE_PHASE_X, LARGE_PHASE_Y)
        val medium = wave(lng, lat, MEDIUM_SCALE_DEG, MEDIUM_PHASE_X, MEDIUM_PHASE_Y)
        val small = wave(lng, lat, SMALL_SCALE_DEG, SMALL_PHASE_X, SMALL_PHASE_Y)
        val combined = (0.6 * large + 0.3 * medium + 0.1 * small).coerceIn(-1.0, 1.0)

        // 映射到 [0,1] 后做立方偏斜：大多数位置落在低处，只有接近 1 的地方才是山。
        // 立方（而不是更高次）是个折中：再高一点分布就被压成一条窄带，量化后又变成少数几个值。
        val t = ((combined + 1.0) / 2.0).coerceIn(0.0, 1.0)
        val skewed = t * t * t
        return quantize(FLOOR_M + (MAX_M - FLOOR_M) * skewed)
    }

    /** 设置里留空（或只有空白）表示"按坐标自动"，这是本次改动的入口。 */
    fun isAuto(configuredValue: String?): Boolean = configuredValue.isNullOrBlank()

    fun describe(baselineM: Double): String = "baseline=%.1f m".format(baselineM)

    private fun wave(lng: Double, lat: Double, scaleDeg: Double, phaseX: Double, phaseY: Double): Double {
        val angle = 2.0 * Math.PI / scaleDeg
        return sin(lng * angle + phaseX) * cos(lat * angle * 0.7 + phaseY)
    }

    private fun normalizeLongitude(lng: Double): Double {
        var value = lng
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }

    private fun quantize(meters: Double): Double =
        ((meters / RESOLUTION_M).roundToLong() * RESOLUTION_M).coerceIn(MIN_M, MAX_M)

    /** 仅用于自检：两个坐标之间的兜底值差（米） */
    fun differenceMeters(latA: Double, lngA: Double, latB: Double, lngB: Double): Double =
        abs(baselineMeters(latA, lngA) - baselineMeters(latB, lngB))
}
