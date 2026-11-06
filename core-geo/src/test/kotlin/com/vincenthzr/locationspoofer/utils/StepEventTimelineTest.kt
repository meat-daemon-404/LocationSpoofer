package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepEventTimelineTest {

    private val t0 = 1_000_000_000L

    @Test
    fun `first sample only aligns the cursor`() {
        val timeline = StepEventTimeline()
        assertTrue(timeline.sample(12.0, t0).isEmpty())
    }

    @Test
    fun `each crossing is interpolated between the two samples`() {
        val timeline = StepEventTimeline()
        timeline.sample(10.0, t0)
        // 1 秒内从 10.0 走到 12.0：两个整数穿越点应落在 0.5 s 与 1.0 s
        val events = timeline.sample(12.0, t0 + 1_000_000_000L)
        assertEquals(2, events.size)
        assertEquals(t0 + 500_000_000L, events[0])
        assertEquals(t0 + 1_000_000_000L, events[1])
        assertTrue(events[1] > events[0])
    }

    @Test
    fun `no crossing inside a sample yields nothing`() {
        val timeline = StepEventTimeline()
        timeline.sample(10.0, t0)
        assertTrue(timeline.sample(10.4, t0 + 200_000_000L).isEmpty())
    }

    @Test
    fun `time going backwards reports nothing and re-aligns`() {
        val timeline = StepEventTimeline()
        timeline.sample(10.0, t0)
        assertTrue(timeline.sample(15.0, t0 - 500_000_000L).isEmpty())
        // 纠正基准后再前进，只补发从 15 开始的那一段
        val events = timeline.sample(16.0, t0 + 100_000_000L)
        assertEquals(1, events.size)
    }

    @Test
    fun `a huge jump is capped to the most recent steps`() {
        val timeline = StepEventTimeline()
        timeline.sample(0.0, t0)
        val events = timeline.sample(100.0, t0 + 1_000_000_000L)
        assertEquals(StepEventTimeline.DEFAULT_MAX_CATCH_UP, events.size)
        // 保留下来的应该是最近的那几步
        assertEquals(t0 + 1_000_000_000L, events.last())
        assertEquals(t0 + 930_000_000L, events.first())
    }

    @Test
    fun `events older than the age window are dropped`() {
        val timeline = StepEventTimeline()
        timeline.sample(10.0, t0)
        // 5 秒走两步：第一个穿越点在 2.5 s（相对这次采样的 nowNs 已超过 2 秒窗口）
        val events = timeline.sample(12.0, t0 + 5_000_000_000L)
        assertEquals(1, events.size)
        assertEquals(t0 + 5_000_000_000L, events[0])
    }

    @Test
    fun `reset forgets the previous sample`() {
        val timeline = StepEventTimeline()
        timeline.sample(10.0, t0)
        timeline.reset()
        assertArrayEquals(LongArray(0), timeline.sample(20.0, t0 + 1_000_000_000L))
    }

    @Test
    fun `a steady cadence produces evenly spaced step events`() {
        val timeline = StepEventTimeline()
        timeline.sample(0.0, t0)
        val all = ArrayList<Long>()
        var second = 1
        while (second <= 20) {
            val events = timeline.sample(STEPS_PER_SECOND * second, t0 + second * 1_000_000_000L)
            all.addAll(events.toList())
            second++
        }
        // 20 秒 × 2.7 步/秒 = 54 步，整数穿越点正好 1..54
        assertEquals(54, all.size)
        // 相邻步事件间隔应稳定在 1/步频 秒（370 ms 量级）
        val expectedGap = (1_000_000_000.0 / STEPS_PER_SECOND).toLong()
        for (index in 1 until all.size) {
            val gap = all[index] - all[index - 1]
            assertTrue(
                "第 $index 个步间隔 $gap 与期望 $expectedGap 偏差超过 3 ms",
                kotlin.math.abs(gap - expectedGap) <= 3_000_000L
            )
        }
    }

    private companion object {
        const val STEPS_PER_SECOND: Double = 2.7
    }
}
