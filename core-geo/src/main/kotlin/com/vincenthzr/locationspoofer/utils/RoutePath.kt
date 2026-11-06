package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_M = 6378137.0

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 从 from 指向 to 的方位角（度，正北为 0，顺时针） */
    fun bearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** 从 start 沿 bearing 方向前进 meters 米后的位置 */
    fun destination(start: LatLng, bearingDeg: Double, meters: Double): LatLng {
        val angular = meters / EARTH_RADIUS_M
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.lat)
        val lng1 = Math.toRadians(start.lng)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearingRad))
        val lng2 = lng1 + atan2(sin(bearingRad) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lng2))
    }
}

/**
 * 一条路线上"已行进距离 → 位置"的映射，App 和 Xposed 两端共用，保证两边对同一距离算出同一位置。
 *
 * 规则：首尾相距 5 米以内视为闭环，走到终点后从起点继续；否则走到终点后原路折返，来回往复。
 * stopAtDestination 为真时走到终点就停下。
 */
class RoutePath(val points: List<LatLng>) {

    data class Position(val lat: Double, val lng: Double, val bearing: Float, val arrived: Boolean)

    private val cumulative: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + GeoMath.distance(points[i - 1], points[i])
    }

    val totalDistance: Double = if (points.isEmpty()) 0.0 else cumulative.last()
    val isClosedLoop: Boolean = points.size >= 2 && GeoMath.distance(points.first(), points.last()) <= 5.0
    val isValid: Boolean get() = points.size >= 2 && totalDistance > 0.0

    /**
     * 方位角平滑半窗（米）。
     *
     * 真机 GNSS 报的方位角是"一段位移的方向"（course over ground），不是当前折线段的朝向；
     * 直接取当前段朝向会让每个折点出现一次阶跃，阶跃幅度等于该折点的转角——
     * 下游按 bearing 序列做一致性分析时，这是一眼可见的合成痕迹。
     * 这里改成取 [along - w, along + w] 两端点连线的方向，把转角摊到一段距离里。
     */
    private val bearingWindow: Double = (totalDistance / 8.0).coerceIn(0.0, 2.5)

    fun positionAt(distanceTraveled: Double, stopAtDestination: Boolean): Position {
        require(isValid) { "route needs at least two distinct points" }
        val traveled = distanceTraveled.coerceAtLeast(0.0)

        if (stopAtDestination && traveled >= totalDistance) {
            val last = points.last()
            return Position(last.lat, last.lng, GeoMath.bearing(points[points.size - 2], last), arrived = true)
        }

        val forward: Boolean
        val along: Double
        if (isClosedLoop) {
            forward = true
            along = traveled % totalDistance
        } else {
            val cycle = totalDistance * 2
            val inCycle = traveled % cycle
            forward = inCycle <= totalDistance
            along = if (forward) inCycle else cycle - inCycle
        }

        val point = pointAt(along)
        return Position(point.lat, point.lng, bearingAlong(along, forward), arrived = false)
    }

    /** 沿路径的行进方向（已考虑折返方向），用于平滑方位角 */
    private fun bearingAlong(along: Double, forward: Boolean): Float {
        val raw = if (bearingWindow > 0.05) {
            val head = pointAt(foldTo(along - bearingWindow))
            val tail = pointAt(foldTo(along + bearingWindow))
            if (GeoMath.distance(head, tail) > 0.05) GeoMath.bearing(head, tail) else segmentBearingAt(along)
        } else {
            segmentBearingAt(along)
        }
        return if (forward) raw else (raw + 180.0f) % 360.0f
    }

    /** 把任意距离折回 [0, totalDistance]：闭环取模，开环按来回往复镜像 */
    private fun foldTo(distance: Double): Double {
        if (totalDistance <= 0.0) return 0.0
        return if (isClosedLoop) {
            val m = distance % totalDistance
            if (m < 0.0) m + totalDistance else m
        } else {
            val cycle = totalDistance * 2.0
            var c = distance % cycle
            if (c < 0.0) c += cycle
            if (c <= totalDistance) c else cycle - c
        }
    }

    private fun segmentIndexAt(along: Double): Int {
        val d = along.coerceIn(0.0, totalDistance)
        var seg = 0
        while (seg < points.size - 2 && cumulative[seg + 1] < d) seg++
        return seg
    }

    private fun pointAt(along: Double): LatLng {
        val d = along.coerceIn(0.0, totalDistance)
        val seg = segmentIndexAt(d)
        val from = points[seg]
        val to = points[seg + 1]
        val segLen = (cumulative[seg + 1] - cumulative[seg]).coerceAtLeast(0.0001)
        val ratio = ((d - cumulative[seg]) / segLen).coerceIn(0.0, 1.0)
        return LatLng(from.lat + (to.lat - from.lat) * ratio, from.lng + (to.lng - from.lng) * ratio)
    }

    private fun segmentBearingAt(along: Double): Float {
        val seg = segmentIndexAt(along)
        return GeoMath.bearing(points[seg], points[seg + 1])
    }
}
