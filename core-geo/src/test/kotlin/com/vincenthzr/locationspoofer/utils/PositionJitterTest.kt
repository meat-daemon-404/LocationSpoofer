package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PositionJitterTest {

    @Test
    fun `offset never leaves the configured radius`() {
        for (seed in longArrayOf(0L, 1L, 12_345L, 1_700_000_000_000L, -42L)) {
            var t = 0.0
            while (t <= 3_600.0) {
                val offset = PositionJitter.offsetMeters(seed, t)
                val radius = hypot(offset[0].toDouble(), offset[1].toDouble())
                assertTrue("radius $radius for seed $seed at $t", radius <= PositionJitter.MAX_RADIUS_M + 1e-3)
                t += 30.0
            }
        }
    }

    @Test
    fun `standing still is not a constant`() {
        assertTrue(PositionJitter.varies(1_700_000_000_000L, 0.0, 1_800.0))
        // 十分钟内至少要出现足够多的不同取值，避免"量化之后又变成一串相同数字"
        val seen = HashSet<Pair<Float, Float>>()
        var t = 0.0
        while (t <= 600.0) {
            val offset = PositionJitter.offsetMeters(1_700_000_000_000L, t)
            seen += offset[0] to offset[1]
            t += 5.0
        }
        assertTrue("distinct=${seen.size}", seen.size >= 10)
    }

    @Test
    fun `the same instant gives the same offset`() {
        val first = PositionJitter.offsetMeters(777L, 123.5)
        val second = PositionJitter.offsetMeters(777L, 123.5)
        assertEquals(first[0], second[0], 0f)
        assertEquals(first[1], second[1], 0f)
    }

    @Test
    fun `different sessions do not share a curve`() {
        val a = PositionJitter.offsetMeters(1_000L, 300.0)
        val b = PositionJitter.offsetMeters(2_000L, 300.0)
        assertNotEquals(a[0] to a[1], b[0] to b[1])
    }

    @Test
    fun `motion between neighbouring seconds stays small`() {
        var previous = PositionJitter.offsetMeters(42L, 0.0)
        var t = 1.0
        while (t <= 600.0) {
            val current = PositionJitter.offsetMeters(42L, t)
            val step = hypot((current[0] - previous[0]).toDouble(), (current[1] - previous[1]).toDouble())
            assertTrue("step $step at $t", step <= PositionJitter.MAX_STEP_PER_SEC_M)
            previous = current
            t += 1.0
        }
    }

    @Test
    fun `offset is quantised to the resolution`() {
        val offset = PositionJitter.offsetMeters(9_999L, 77.0)
        for (value in offset) {
            val steps = value / PositionJitter.RESOLUTION_M.toFloat()
            assertEquals(Math.round(steps).toFloat(), steps, 1e-3f)
        }
    }

    @Test
    fun `negative or non finite elapsed time is treated as zero`() {
        val atZero = PositionJitter.offsetMeters(5L, 0.0)
        val negative = PositionJitter.offsetMeters(5L, -100.0)
        assertEquals(atZero[0], negative[0], 0f)
        assertEquals(atZero[1], negative[1], 0f)
    }
}
