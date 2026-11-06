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

    // ------------------------------------------------------------------
    // 天空模型
    // ------------------------------------------------------------------

    private val templateElevations = FloatArray(32) { 20f + (it * 17 % 60) }
    private val templateAzimuths = FloatArray(32) { (it * 41 % 360).toFloat() }

    private fun sky(seedValue: Long = seed, atMs: Long = t0): SatelliteModel.Sky =
        SatelliteModel.sky(
            SatelliteModel.counts(20, seedValue, atMs),
            seedValue,
            atMs,
            templateElevations,
            templateAzimuths
        )

    @Test
    fun `sky keeps elevations and azimuths inside their physical ranges`() {
        var t = t0
        val end = t0 + 3_600_000L
        while (t <= end) {
            val s = sky(atMs = t)
            assertEquals(s.size, s.slot.size)
            assertEquals(s.size, s.usedInFix.size)
            for (i in 0 until s.size) {
                assertTrue("i=$i elev=${s.elevations[i]}", s.elevations[i] in SatelliteModel.MIN_ELEVATION_DEG..SatelliteModel.MAX_ELEVATION_DEG)
                assertTrue("i=$i az=${s.azimuths[i]}", s.azimuths[i] >= 0f && s.azimuths[i] < 360f)
            }
            t += 60_000L
        }
    }

    @Test
    fun `sky marks exactly the announced number of satellites as used in fix`() {
        var t = t0
        val end = t0 + 3_600_000L
        while (t <= end) {
            val counts = SatelliteModel.counts(20, seed, t)
            val s = SatelliteModel.sky(counts, seed, t, templateElevations, templateAzimuths)
            assertEquals(counts.usedInFix, s.usedInFix.count { it })
            // 参与解算的必须是仰角最高的那几颗
            val usedMin = (0 until s.size).filter { s.usedInFix[it] }.minOfOrNull { s.elevations[it] } ?: Float.MAX_VALUE
            val unusedMax = (0 until s.size).filter { !s.usedInFix[it] }.maxOfOrNull { s.elevations[it] } ?: Float.MIN_VALUE
            assertTrue("usedMin=$usedMin unusedMax=$unusedMax", usedMin >= unusedMax)
            t += 60_000L
        }
    }

    @Test
    fun `sky motion is continuous, not a sawtooth`() {
        // 每秒推进一次，仰角单步变化必须很小；(step % 10 - 5) 那种写法会每 10 秒跳 9°
        var t = t0
        val end = t0 + 600_000L
        var previous = sky(atMs = t).elevations
        t += 1_000L
        while (t <= end) {
            val current = sky(atMs = t).elevations
            for (i in 0 until minOf(previous.size, current.size)) {
                val jump = kotlin.math.abs(current[i] - previous[i])
                assertTrue("i=$i jump=$jump t=$t", jump < 0.5f)
            }
            previous = current
            t += 1_000L
        }
    }

    @Test
    fun `different sessions see a different part of the sky`() {
        // 会话种子不同 -> 轮换起点不同 -> 同一时刻的槽位排布不同
        val a = sky(seedValue = seed).slot
        val b = sky(seedValue = seed + 1_000_003L).slot
        assertTrue("a=${a.toList()} b=${b.toList()}", !a.contentEquals(b))
        // 轮换本身是排列：一个会话内部槽位不重复
        assertEquals(a.size, a.toSet().size)
    }
}
