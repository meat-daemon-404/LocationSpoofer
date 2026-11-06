package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteModelTest {

    private val t0 = 1_789_398_037_217L
    private val seed = 1_789_398_030_000L

    @Test
    fun `counts stay inside the legal range and keep the containment order`() {
        var t = t0
        val end = t0 + 3_600_000L
        while (t <= end) {
            for (base in listOf(8, 10, 16, 20, 26, 32, 40)) {
                val c = SatelliteModel.counts(base, seed, t)
                assertTrue("base=$base t=$t", c.usedInFix >= SatelliteModel.MIN_USED_IN_FIX)
                assertTrue("base=$base t=$t", c.usedInFix <= c.visible)
                assertTrue("base=$base t=$t", c.visible <= c.inView)
                assertTrue("base=$base t=$t", c.visible >= SatelliteModel.MIN_VISIBLE)
                assertTrue("base=$base t=$t", c.inView <= SatelliteModel.MAX_VISIBLE)
            }
            t += 60_000L
        }
    }

    @Test
    fun `a default run is not a straight line`() {
        // 默认基准 20 颗，一小时内可见数与参与解算数都要真的动起来
        assertTrue(SatelliteModel.varies(20, seed, t0, t0 + 3_600_000L))
    }

    @Test
    fun `same instant gives the same counts regardless of sampling order`() {
        // 各通道各自调用，必须拿到同一组数：结果只依赖参数，不依赖调用顺序或内部状态
        val direct = SatelliteModel.counts(20, seed, t0 + 123_000L)
        repeat(5) { SatelliteModel.counts(16, seed + 7, t0) }
        assertEquals(direct, SatelliteModel.counts(20, seed, t0 + 123_000L))
    }

    @Test
    fun `different sessions do not all report the same numbers`() {
        // 不同 start_timestamp（即不同会话）应当落在不同的取值上，否则全体用户共享一条曲线
        val values = (0 until 12).map { i ->
            SatelliteModel.counts(20, seed + i * 1_000_003L, t0 + 300_000L).visible
        }.toSet()
        assertTrue("distinct=$values", values.size >= 3)
    }

    @Test
    fun `visible count tracks the configured base`() {
        val low = SatelliteModel.counts(10, seed, t0).visible
        val high = SatelliteModel.counts(28, seed, t0).visible
        assertTrue("low=$low high=$high", high - low >= 12)
    }
}
