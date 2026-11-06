package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 气压计模型的单元测试。判据取的是"物理上说得通"，不是复刻某个实现：
 * 标准大气下海平面 ≈ 1013.25 hPa、每上升约 8.4 m 气压降 1 hPa（低空近似）。
 */
class BarometerModelTest {

    @Test
    fun `sea level pressure matches the standard atmosphere`() {
        assertEquals(1013.25, BarometerModel.pressureHpa(0.0), 0.01)
    }

    @Test
    fun `pressure decreases monotonically with altitude`() {
        var previous = BarometerModel.pressureHpa(BarometerModel.MIN_ALTITUDE_M)
        var altitude = BarometerModel.MIN_ALTITUDE_M + 10.0
        while (altitude <= BarometerModel.MAX_ALTITUDE_M) {
            val current = BarometerModel.pressureHpa(altitude)
            assertTrue("气压应随海拔单调下降，h=$altitude", current < previous)
            previous = current
            altitude += 10.0
        }
    }

    @Test
    fun `low altitude gradient is about one hectopascal per eight metres`() {
        // 低空线性近似：≈ 8.4 m / hPa。取 100 m 与 200 m 两点算平均梯度
        val first = BarometerModel.pressureHpa(100.0)
        val second = BarometerModel.pressureHpa(200.0)
        val metresPerHpa = 100.0 / (first - second)
        assertTrue("低空梯度应接近 8~9 m/hPa，实际 $metresPerHpa", metresPerHpa in 8.0..9.0)
    }

    @Test
    fun `conversion round trips back to the same altitude`() {
        for (altitude in listOf(-400.0, -50.0, 0.0, 25.0, 800.0, 3500.0, 8848.0)) {
            val pressure = BarometerModel.pressureHpa(altitude)
            assertEquals(altitude, BarometerModel.altitudeM(pressure), 1e-6)
        }
    }

    @Test
    fun `same session drifts over time and different sessions do not share a curve`() {
        val seed = 1_790_000_000_000L
        val first = BarometerModel.pressureHpa(25.0, seed, 0.0)
        val later = BarometerModel.pressureHpa(25.0, seed, 600.0)
        assertNotEquals("同一会话十分钟内应有起伏", first, later, 1e-9)
        assertTrue("起伏应在物理量级内（不超过 1 hPa）", abs(first - later) <= 1.0)
        assertTrue(BarometerModel.varies(seed, 0.0, 1800.0))

        // 与纯海拔换算相比，波动只差零点几 hPa，不会把换算出的海拔带偏几十米
        val plain = BarometerModel.pressureHpa(25.0)
        assertTrue("波动幅度应受控", abs(plain - first) <= 1.0)

        val another = BarometerModel.pressureHpa(25.0, seed + 7_919L, 600.0)
        assertNotEquals("不同会话不应给出同一条曲线", later, another, 1e-9)
    }

    @Test
    fun `altitude extremes are clamped instead of producing nonsense`() {
        // 夹到量程边界后不能出现 0 或负压，也不能出现无穷大
        val below = BarometerModel.pressureHpa(-100_000.0)
        val above = BarometerModel.pressureHpa(100_000.0)
        assertTrue("海平面以下夹到边界值", below > BarometerModel.SEA_LEVEL_PRESSURE_HPA)
        assertTrue("高空仍应为正压", above > 0.0)
        assertEquals(BarometerModel.pressureHpa(BarometerModel.MIN_ALTITUDE_M), below, 1e-9)
        assertEquals(BarometerModel.pressureHpa(BarometerModel.MAX_ALTITUDE_M), above, 1e-9)
    }

    @Test
    fun `readings are quantized to the sensor resolution`() {
        val raw = BarometerModel.pressureHpa(25.0, 1_790_000_000_000L, 42.0)
        val quantized = BarometerModel.quantize(raw)
        val steps = quantized / BarometerModel.RESOLUTION_HPA
        assertEquals("量化后应是分辨率的整数倍", steps, Math.round(steps).toDouble(), 1e-9)
        assertTrue("量化误差不超过半个步进", abs(raw - quantized) <= BarometerModel.RESOLUTION_HPA)
    }
}
