package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GeomagneticModelTest {

    @Test
    fun `total field grows from the equator towards the poles`() {
        var previous = GeomagneticModel.totalMicroTesla(0.0)
        assertEquals(GeomagneticModel.EQUATORIAL_MICRO_TESLA, previous, 1e-9)
        var latitude = 5.0
        while (latitude <= 85.0) {
            val current = GeomagneticModel.totalMicroTesla(latitude)
            assertTrue("lat=$latitude current=$current previous=$previous", current > previous)
            previous = current
            latitude += 5.0
        }
        // 南北半球对称
        for (latitude in doubleArrayOf(12.0, 30.0, 47.0, 66.0)) {
            assertEquals(
                GeomagneticModel.totalMicroTesla(latitude),
                GeomagneticModel.totalMicroTesla(-latitude),
                1e-9
            )
        }
    }

    @Test
    fun `inclination follows the dipole relation and stays physical`() {
        assertEquals(0.0, GeomagneticModel.inclinationDeg(0.0), 1e-6)
        for (latitude in doubleArrayOf(10.0, 18.25, 30.27, 45.0, 60.0, 80.0)) {
            val expected = Math.atan2(
                2.0 * kotlin.math.sin(latitude * Math.PI / 180.0),
                kotlin.math.cos(latitude * Math.PI / 180.0)
            ) * 180.0 / Math.PI
            assertEquals("lat=$latitude", expected, GeomagneticModel.inclinationDeg(latitude), 1e-6)
            assertTrue(GeomagneticModel.inclinationDeg(latitude) < 90.0)
        }
        // 倾角随纬度单调增
        assertTrue(GeomagneticModel.inclinationDeg(18.25) < GeomagneticModel.inclinationDeg(30.27))
        assertTrue(GeomagneticModel.inclinationDeg(30.27) < GeomagneticModel.inclinationDeg(45.0))
    }

    @Test
    fun `device field points down and along the heading`() {
        val latitude = 30.27
        val north = GeomagneticModel.deviceField(latitude, 0.0)
        assertEquals("x", 0.0, north[0].toDouble(), 1e-4)
        assertTrue("y should be positive, got ${north[1]}", north[1] > 10f)
        assertTrue("z should be negative, got ${north[2]}", north[2] < -10f)

        val east = GeomagneticModel.deviceField(latitude, 90.0)
        assertTrue("east x should be negative, got ${east[0]}", east[0] < -10f)
        assertEquals("east y", 0.0, east[1].toDouble(), 1e-3)
        assertEquals("east z", north[2].toDouble(), east[2].toDouble(), 1e-6)

        // 磁场总量在 20~70 µT 之间，且水平/竖直分量与倾角一致
        for (heading in doubleArrayOf(0.0, 45.0, 137.0, 300.0)) {
            val field = GeomagneticModel.deviceField(latitude, heading)
            val magnitude = sqrt(
                field[0].toDouble() * field[0] + field[1].toDouble() * field[1] +
                    field[2].toDouble() * field[2]
            )
            assertTrue("magnitude=$magnitude", magnitude in 20.0..70.0)
            // 设备坐标系里的总量必须等于世界坐标系里的总量（含慢漂移项），只在旋转里守恒
            val world = GeomagneticModel.worldField(latitude)
            val worldMagnitude = sqrt(
                world[0].toDouble() * world[0] + world[1].toDouble() * world[1] +
                    world[2].toDouble() * world[2]
            )
            assertEquals(worldMagnitude, magnitude, 1e-4)
            // 与不含漂移的偶极总量最多差一个漂移幅度
            assertEquals(GeomagneticModel.totalMicroTesla(latitude), magnitude, GeomagneticModel.DRIFT_MICRO_TESLA + 1e-4)
        }
    }

    @Test
    fun `sampling repeats within the same instant and drifts over time`() {
        val first = GeomagneticModel.sample(30.27, 45.0, seed = 9001L, elapsedSec = 120.0)
        val again = GeomagneticModel.sample(30.27, 45.0, seed = 9001L, elapsedSec = 120.0)
        assertEquals(first[0], again[0], 0.0f)
        assertEquals(first[1], again[1], 0.0f)
        assertEquals(first[2], again[2], 0.0f)

        assertTrue(GeomagneticModel.varies(9001L, 30.27, fromSec = 0.0, toSec = 3600.0))
        // 一秒内不应出现大跳变（慢漂移 + 平滑噪声）
        val a = GeomagneticModel.sample(30.27, 45.0, 9001L, 100.0)
        val b = GeomagneticModel.sample(30.27, 45.0, 9001L, 101.0)
        for (i in 0..2) {
            assertTrue("axis=$i delta=${abs(a[i] - b[i])}", abs(a[i] - b[i]) < 1.0f)
        }
    }

    @Test
    fun `different sessions do not all report the same field`() {
        val values = ArrayList<String>()
        for (seed in longArrayOf(1L, 2L, 3L, 1001L, 90210L, 123456789L)) {
            val field = GeomagneticModel.sample(30.27, 0.0, seed, 600.0)
            values += "${field[0] / 0.05f},${field[1] / 0.05f},${field[2] / 0.05f}"
        }
        assertTrue("distinct=${values.toSet().size}", values.toSet().size >= 3)
        assertNotEquals(values[0], values[1])
    }
}
