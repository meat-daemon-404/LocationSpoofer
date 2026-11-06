package com.vincenthzr.locationspoofer.xposed.utils

import com.vincenthzr.locationspoofer.utils.AltitudeModel
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.MotionRealism
import com.vincenthzr.locationspoofer.utils.RoutePath
import org.json.JSONArray
import org.json.JSONObject

data class SpoofedMotion(
    val lat: Double,
    val lng: Double,
    val bearing: Float,
    val speed: Float
)

/**
 * 根据配置和当前时间推导模拟位置：路线模式沿路线插值，摇杆模式按方向和速度推算，其余返回配置里的固定坐标。
 * 路线几何与 App 端共用 [RoutePath]，保证两端对同一时刻算出同一位置。
 */
object RouteEngine {

    /**
     * 摇杆模式：App 约每秒写一次"当前位置 + 方向 + 速度"，两次写入之间在这里按方向和速度往前推算，
     * 让被 Hook 的 App 看到连续移动而不是每秒跳一下。推算时长有上限，防止 App 被杀后位置一直漂走。
     */
    private const val JOYSTICK_MAX_EXTRAPOLATION_MS = 3000L

    // 配置 JSON 只在文件变化时才重新解析，同一个 JSONArray 实例对应同一条路线，按实例缓存即可
    @Volatile private var cachedRouteArray: JSONArray? = null
    @Volatile private var cachedPath: RoutePath? = null

    private fun routePath(routeArray: JSONArray): RoutePath {
        val cached = cachedPath
        if (routeArray === cachedRouteArray && cached != null) return cached
        val points = ArrayList<LatLng>(routeArray.length())
        for (i in 0 until routeArray.length()) {
            val obj = routeArray.optJSONObject(i) ?: continue
            points += LatLng(obj.optDouble("lat", 0.0), obj.optDouble("lng", 0.0))
        }
        return RoutePath(points).also {
            cachedPath = it
            cachedRouteArray = routeArray
        }
    }

    private fun extrapolateJoystick(config: JSONObject, now: Long, base: LatLng, bearing: Float): SpoofedMotion {
        val speed = config.optDouble("speed_m_s", 0.0)
        val startTime = config.optLong("start_timestamp", 0L)
        if (speed <= 0.0 || startTime <= 0L) return SpoofedMotion(base.lat, base.lng, bearing, 0f)

        val elapsedSec = (now - startTime).coerceIn(0L, JOYSTICK_MAX_EXTRAPOLATION_MS) / 1000.0
        val pos = GeoMath.destination(base, bearing.toDouble(), speed * elapsedSec)
        return SpoofedMotion(pos.lat, pos.lng, bearing, speed.toFloat())
    }

    fun calculateCurrentPosition(config: JSONObject, now: Long = System.currentTimeMillis()): SpoofedMotion {
        val base = LatLng(config.optDouble("lat", 0.0), config.optDouble("lng", 0.0))
        val baseBearing = config.optDouble("sim_bearing", 0.0).toFloat()
        val routeArray = config.optJSONArray("route_points")

        if (!config.optBoolean("is_route_mode", false) || routeArray == null || routeArray.length() < 2) {
            if (config.optString("sim_mode") == "JOYSTICK") {
                return extrapolateJoystick(config, now, base, baseBearing)
            }
            return SpoofedMotion(base.lat, base.lng, baseBearing, 0f)
        }

        val path = routePath(routeArray)
        if (!path.isValid) return SpoofedMotion(base.lat, base.lng, baseBearing, 0f)

        val speed = config.optDouble("speed_m_s", 3.0).coerceAtLeast(0.1)
        val rawStartTime = config.optLong("start_timestamp", 0L)
        val startTime = if (rawStartTime > 0L) rawStartTime else now
        val elapsedSec = (now - startTime).coerceAtLeast(0L) / 1000.0
        val realism = realismSession(config, startTime)
        // 暂停后继续时，路线从暂停处的累计距离接着走（start_timestamp 重置为继续的时刻）
        val offset = config.optDouble("route_distance_offset", 0.0).coerceAtLeast(0.0)
        val traveled = offset + realism.distance(speed, elapsedSec)

        val pos = path.positionAt(traveled, config.optBoolean("stop_at_destination", false))
        val currentSpeed = if (pos.arrived) 0f else realism.speed(speed, elapsedSec).toFloat()
        return SpoofedMotion(pos.lat, pos.lng, pos.bearing, currentSpeed)
    }

    /**
     * 当前位置的海拔：基准海拔 ± 变化范围内随地形起伏，叠加缓慢漂移（见 [AltitudeModel]），
     * 各进程对同一时刻给出一致的值。旧版配置没有变化范围字段时，按漂移幅度处理。
     *
     * 漂移幅度不再直接取真实度档位：档位 OFF 的幅度是 0，海拔就退化成只由坐标决定的地形函数，
     * 原地模拟时是一条常量直线（见 [MotionRealism.MIN_ALTITUDE_DRIFT_M]）。这里用带下限的
     * 档位幅度，使"没调过真实度"的配置也有随时间变化的分量；而 `altitude_variation_m = 0`
     * 是用户显式要求固定海拔，仍原样返回基准值。
     */
    fun realisticAltitude(config: JSONObject, now: Long = System.currentTimeMillis()): Double {
        val base = config.optDouble("altitude", 25.0)
        val drift = MotionRealism.altitudeDriftM(
            MotionRealism.Level.fromId(config.optInt("realism_level", MotionRealism.Level.OFF.id))
        )
        val variation = config.optDouble("altitude_variation_m", drift)
        if (variation <= 0.0) return base
        val pos = calculateCurrentPosition(config, now)
        return AltitudeModel.altitude(base, variation, drift, pos.lat, pos.lng, now)
    }

    /** 配置里缺少真实度字段（旧版 App 写的配置）时按"关闭 + 不浮动"处理，行为与旧版一致 */
    fun realismSession(config: JSONObject, startTimestamp: Long = config.optLong("start_timestamp", 0L)): MotionRealism.Session =
        MotionRealism.session(
            startTimestamp,
            config.optInt("realism_level", MotionRealism.Level.OFF.id),
            config.optInt("speed_fluctuation_pct", 0)
        )
}
