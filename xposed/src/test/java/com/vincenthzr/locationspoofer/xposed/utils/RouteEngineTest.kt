package com.vincenthzr.locationspoofer.xposed.utils

import com.vincenthzr.locationspoofer.utils.BarometerModel
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.PositionJitter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEngineTest {

    private fun pointsJson(vararg latLng: Pair<Double, Double>): JSONArray {
        val arr = JSONArray()
        latLng.forEach { (lat, lng) ->
            arr.put(JSONObject().apply {
                put("lat", lat)
                put("lng", lng)
            })
        }
        return arr
    }

    @Test
    fun `non route mode falls back to the raw config coordinate`() {
        val config = JSONObject().apply {
            put("is_route_mode", false)
            put("lat", 31.5)
            put("lng", 120.5)
            put("sim_bearing", 88.0)
        }
        val motion = RouteEngine.calculateCurrentPosition(config, now = 0L)
        // 静止坐标不再是"逐位等于配置值"：真实 GPS 停着也会在几米内漂（见 PositionJitter），
        // 这里只要求落在抖动半径内，且速度仍为 0。
        assertTrue(GeoMath.distance(LatLng(31.5, 120.5), LatLng(motion.lat, motion.lng)) <= PositionJitter.MAX_RADIUS_M)
        assertEquals(88f, motion.bearing)
        assertEquals(0f, motion.speed)
    }

    @Test
    fun `route mode with fewer than two points falls back to the raw config coordinate`() {
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(1.0 to 2.0))
            put("lat", 31.5)
            put("lng", 120.5)
        }
        val motion = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertTrue(GeoMath.distance(LatLng(31.5, 120.5), LatLng(motion.lat, motion.lng)) <= PositionJitter.MAX_RADIUS_M)
    }

    @Test
    fun `straight two-point route starts at the first point`() {
        // 用这个测试专属的坐标，避免和其它用例撞上 RouteEngine 内部的路线缓存签名(count+首尾纬度)。
        val start = 10.0 to 100.0
        val end = 10.02 to 100.0 // 正北方向，约 2.2km，明显不是闭环(缺口远大于5米阈值)
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(start, end))
            put("speed_m_s", 10.0)
            put("start_timestamp", 0L)
        }
        val motion = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertEquals(start.first, motion.lat, 1e-6)
        assertEquals(start.second, motion.lng, 1e-6)
    }

    @Test
    fun `straight two-point route with stop_at_destination stops exactly at the last point`() {
        val start = 10.0 to 100.0
        val end = 10.02 to 100.0
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(start, end))
            put("speed_m_s", 10.0)
            // start_timestamp 必须是正数——RouteEngine 把 <=0 的值当作"还没设置开始时间"，
            // 会直接拿 now 当起点，导致 elapsedSec 恒为 0，永远走不到终点。
            put("start_timestamp", 1L)
            put("stop_at_destination", true)
        }
        // 全程约 2.2km，10m/s 走完要 220s；给 10000s 保证早已到达终点。
        val motion = RouteEngine.calculateCurrentPosition(config, now = 10_000_000L)
        assertEquals(end.first, motion.lat, 1e-6)
        assertEquals(end.second, motion.lng, 1e-6)
        assertEquals(0f, motion.speed)
    }

    @Test
    fun `closed loop route treats the near-identical last point as returning to the start`() {
        // 首尾几乎重合(小于 RouteEngine 5 米的闭环判定阈值)，构成一个三角形闭环。
        val a = 20.0 to 110.0
        val b = 20.01 to 110.0
        val c = 20.01 to 110.01
        val closeToA = 20.0 to 110.0 // 与 a 完全相同，缺口为 0，必然判定为闭环
        val config = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(a, b, c, closeToA))
            put("speed_m_s", 5.0)
            put("start_timestamp", 0L)
        }
        // t=0 时应该正好在起点 a
        val atStart = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertEquals(a.first, atStart.lat, 1e-6)
        assertEquals(a.second, atStart.lng, 1e-6)
        assertTrue(atStart.speed > 0f)
    }

    private fun joystickConfig(speed: Double, bearing: Double, startTimestamp: Long) = JSONObject().apply {
        put("is_route_mode", false)
        put("sim_mode", "JOYSTICK")
        put("lat", 31.5)
        put("lng", 120.5)
        put("sim_bearing", bearing)
        put("speed_m_s", speed)
        put("start_timestamp", startTimestamp)
    }

    @Test
    fun `joystick mode extrapolates along the bearing between config writes`() {
        val motion = RouteEngine.calculateCurrentPosition(joystickConfig(5.0, 0.0, 1_000L), now = 2_000L)
        // 正北 5 m/s 走 1 秒 ≈ 纬度增加 5 / 111 km
        assertEquals(31.5 + 5.0 / 111_320.0, motion.lat, 1e-6)
        assertEquals(120.5, motion.lng, 1e-9)
        assertEquals(5f, motion.speed)
    }

    @Test
    fun `joystick extrapolation is capped when the app stops writing config`() {
        val capped = RouteEngine.calculateCurrentPosition(joystickConfig(5.0, 90.0, 1_000L), now = 61_000L)
        val atCap = RouteEngine.calculateCurrentPosition(joystickConfig(5.0, 90.0, 1_000L), now = 4_000L)
        assertEquals(atCap.lng, capped.lng, 1e-12)
        assertTrue(capped.lng > 120.5)
    }

    @Test
    fun `released joystick stays around the written coordinate`() {
        val motion = RouteEngine.calculateCurrentPosition(joystickConfig(0.0, 45.0, 1_000L), now = 5_000L)
        assertTrue(GeoMath.distance(LatLng(31.5, 120.5), LatLng(motion.lat, motion.lng)) <= PositionJitter.MAX_RADIUS_M)
        assertEquals(0f, motion.speed)
    }

    @Test
    fun `stationary jitter can be switched off to pin the exact coordinate`() {
        val config = JSONObject().apply {
            put("is_route_mode", false)
            put("lat", 31.5)
            put("lng", 120.5)
            put("sim_bearing", 88.0)
            put("stationary_jitter", false)
        }
        val motion = RouteEngine.calculateCurrentPosition(config, now = 0L)
        assertEquals(31.5, motion.lat, 0.0)
        assertEquals(120.5, motion.lng, 0.0)
    }

    @Test
    fun `stationary positions are not bit identical across two samples`() {
        val config = JSONObject().apply {
            put("is_route_mode", false)
            put("lat", 31.5)
            put("lng", 120.5)
            put("start_timestamp", 1_700_000_000_000L)
        }
        val first = RouteEngine.calculateCurrentPosition(config, now = 1_700_000_000_000L)
        val second = RouteEngine.calculateCurrentPosition(config, now = 1_700_000_090_000L)
        assertTrue(first.lat != second.lat || first.lng != second.lng)
    }

    @Test
    fun `route resumes from the paused distance offset`() {
        // 一条正北方向约 1113 米的直线路线
        val route = pointsJson(0.0 to 0.0, 0.01 to 0.0)
        fun cfg(offset: Double, start: Long) = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", route)
            put("speed_m_s", 2.0)
            put("start_timestamp", start)
            put("route_distance_offset", offset)
        }
        val paused = RouteEngine.calculateCurrentPosition(cfg(0.0, 1_000L), now = 101_000L) // 走了 200 米时暂停
        val resumed = RouteEngine.calculateCurrentPosition(cfg(200.0, 500_000L), now = 500_000L) // 继续的瞬间
        assertEquals(paused.lat, resumed.lat, 1e-9)
        val later = RouteEngine.calculateCurrentPosition(cfg(200.0, 500_000L), now = 510_000L)
        assertEquals(220.0 / 111_320.0, later.lat, 2e-6)
    }

    @Test
    fun `route altitude follows the terrain within the configured range`() {
        fun cfg(variation: Double?) = JSONObject().apply {
            put("is_route_mode", true)
            put("route_points", pointsJson(40.0 to 116.0, 40.05 to 116.0))
            put("speed_m_s", 1.4)
            put("start_timestamp", 1_000_000L)
            put("altitude", 80.0)
            put("realism_level", 2)
            variation?.let { put("altitude_variation_m", it) }
        }
        assertEquals(80.0, RouteEngine.realisticAltitude(cfg(0.0), now = 2_000_000L), 0.0)

        val values = (0..3600 step 5).map { RouteEngine.realisticAltitude(cfg(30.0), now = 1_000_000L + it * 1000L) }
        assertTrue(values.all { it in 50.0..110.0 })
        assertTrue(values.max() - values.min() > 5.0)
        // 相邻 5 秒（步行 7 米）的海拔变化不超过坡度上限加漂移
        assertTrue(values.zipWithNext { a, b -> kotlin.math.abs(b - a) }.max() < 7 * 0.08 + 0.5)

        // 旧版配置没有变化范围字段时按随机强度的漂移幅度（中档 ±3 米）处理
        val legacy = (0..600 step 10).map { RouteEngine.realisticAltitude(cfg(null), now = 1_000_000L + it * 1000L) }
        assertTrue(legacy.all { it in 77.0..83.0 })
    }

    @Test
    fun `satellite counts vary over time and stay physically consistent`() {
        fun cfg(base: Int, start: Long) = JSONObject().apply {
            put("is_route_mode", false)
            put("lat", 31.5)
            put("lng", 120.5)
            put("satellite_count", base)
            put("start_timestamp", start)
        }

        // 一场跑下来不该每分钟都报同一个数
        val samples = (0..3600 step 5).map {
            RouteEngine.realisticSatelliteCounts(cfg(20, 1_000_000L), now = 1_000_000L + it * 1000L)
        }
        assertTrue(samples.map { it.visible }.toSet().size >= 2)

        // 任何时刻都满足 参与解算 <= 可见 <= 视野内，而且都落在合法区间
        samples.forEach { counts ->
            assertTrue(counts.usedInFix in 4..counts.visible)
            assertTrue(counts.visible in 8..32)
            assertTrue(counts.inView >= counts.visible)
            assertTrue(counts.inView <= 32)
        }

        // 基准颗数仍然起作用：基准 10 与 28 的可见颗数要拉开
        val low = RouteEngine.realisticSatelliteCounts(cfg(10, 1_000_000L), now = 1_500_000L).visible
        val high = RouteEngine.realisticSatelliteCounts(cfg(28, 1_000_000L), now = 1_500_000L).visible
        assertTrue(high - low >= 12)

        // 不同场次（不同起始时刻）不该全员报同一组数字
        val perRun = (0 until 12L)
            .map { RouteEngine.realisticSatelliteCounts(cfg(20, 1_000_000L + it * 37 * 60_000L), now = 1_700_000L).visible }
            .toSet()
        assertTrue(perRun.size >= 3)
    }

    @Test
    fun `barometer pressure follows the spoofed altitude and still drifts`() {
        fun cfg(start: Long, variation: Double? = null) = JSONObject().apply {
            put("is_route_mode", false)
            put("lat", 31.5)
            put("lng", 120.5)
            put("altitude", 25.0)
            put("start_timestamp", start)
            if (variation != null) put("altitude_variation_m", variation)
        }

        val start = 1_000_000L
        // 气压必须由同一时刻的海拔换算而来：把海拔代回标准大气公式应与读数一致（差在量化步进内）
        (0..600 step 60).forEach { offset ->
            val now = start + offset * 1000L
            val altitude = RouteEngine.realisticAltitude(cfg(start), now = now)
            val pressure = RouteEngine.realisticPressureHpa(cfg(start), now = now)
            val expected = BarometerModel.pressureHpa(altitude, start, (now - start) / 1000.0)
            assertEquals(expected, pressure, BarometerModel.RESOLUTION_HPA)
        }

        // 一场跑下来读数不能是一条直线（否则又是"跨会话相同的常量"指纹）
        val readings = (0..1800 step 30).map { RouteEngine.realisticPressureHpa(cfg(start), now = start + it * 1000L) }.toSet()
        assertTrue(readings.size >= 5)

        // 显式关掉海拔变化时，气压就只剩波动，不应出现几十 hPa 的跳变
        val fixed = (0..1800 step 30).map {
            RouteEngine.realisticPressureHpa(cfg(start, variation = 0.0), now = start + it * 1000L)
        }
        assertTrue(fixed.max() - fixed.min() <= 1.0)
    }

    @Test
    fun `time to first fix is stable per session and differs across sessions`() {
        fun cfg(start: Long) = JSONObject().apply { put("start_timestamp", start) }

        // 同一场次内必须是同一个值：真机 TTFF 在一次会话里不会来回跳
        val one = RouteEngine.realisticTimeToFirstFixMs(cfg(1_700_000_000_000L))
        repeat(5) { assertEquals(one, RouteEngine.realisticTimeToFirstFixMs(cfg(1_700_000_000_000L))) }

        // 必须落在真机的量级里（热启动几百毫秒到冷启动几秒）
        assertTrue(one in 700..2999)

        // 不同场次不能全是同一个数——原来写死 1000 就是这个毛病
        val distinct = (0 until 40)
            .map { RouteEngine.realisticTimeToFirstFixMs(cfg(1_700_000_000_000L + it * 86_400_000L)) }
            .toSet()
        assertTrue(distinct.size >= 10)

        // 缺配置时也要给出一个合法值，而不是 0 或 -1
        assertTrue(RouteEngine.realisticTimeToFirstFixMs(JSONObject()) in 700..2999)
    }
}
