package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GyroscopeModelTest {

    private val seed = 1_762_500_000_000L

    @Test
    fun `still keeps the gyroscope quiet but never frozen`() {
        val limit = GyroscopeModel.STILL_SWING_RAD_PER_SEC * 1.01
        var t = 0.0
        while (t <= 30.0) {
            val values = GyroscopeModel.sample(0.0, 0.0, 0.0, seed, t)
            for (axis in 0..2) {
                assertTrue(
                    "静止时角速度应只剩本底噪声，实际 $axis = ${values[axis]}",
                    abs(values[axis].toDouble()) <= limit
                )
            }
            t += 0.1
        }
        assertTrue(
            "静止时的读数不能是恒定值（恒定本身就是指纹）",
            GyroscopeModel.varies(0.0, 0.0, 0.0, seed, 0.0, 30.0)
        )
    }

    @Test
    fun `motion keeps every axis inside the hardware range`() {
        val cases = listOf(
            Triple(1.2, 110.0, 0.0),
            Triple(2.6, 155.0, 0.4),
            Triple(3.4, 172.0, -0.6),
            Triple(5.0, 198.0, 0.0)
        )
        for ((speed, cadence, headingRate) in cases) {
            var t = 0.0
            while (t <= 120.0) {
                val values = GyroscopeModel.sample(speed, cadence, headingRate, seed, t)
                for (axis in 0..2) {
                    assertTrue(
                        "speed=$speed cadence=$cadence 时 $axis = ${values[axis]} 超出量程",
                        abs(values[axis].toDouble()) <= GyroscopeModel.MAX_ABS_RAD_PER_SEC
                    )
                }
                t += 0.1
            }
        }
    }

    @Test
    fun `yaw rate averages to the heading rate`() {
        val headingRate = 0.35 // ≈ 20°/s 的转弯
        val average = GyroscopeModel.averageYawRate(3.0, 168.0, headingRate, seed, 0.0, 300.0)
        assertEquals("转弯时 z 轴均值必须回到航向变化率", headingRate, average, 0.1)
    }

    @Test
    fun `zero heading rate does not turn in place`() {
        val average = GyroscopeModel.averageYawRate(3.0, 168.0, 0.0, seed, 0.0, 300.0)
        assertTrue("直线跑时平均角速度应接近 0，实际 $average", abs(average) < 0.1)
    }

    @Test
    fun `swing amplitude grows with speed`() {
        fun meanPitch(speed: Double): Double {
            var sum = 0.0
            var count = 0
            var t = 0.0
            while (t <= 60.0) {
                sum += abs(GyroscopeModel.sample(speed, 168.0, 0.0, seed, t)[0].toDouble())
                count++
                t += 0.1
            }
            return sum / count
        }
        val slow = meanPitch(1.0)
        val fast = meanPitch(4.0)
        assertTrue("速度更快时俯仰摆动应更大：$slow vs $fast", fast > slow * 1.5)
    }

    @Test
    fun `different sessions do not look like the same recording`() {
        val a = GyroscopeModel.sample(3.0, 168.0, 0.0, seed, 12.0)
        val b = GyroscopeModel.sample(3.0, 168.0, 0.0, seed + 1_000_003L, 12.0)
        assertNotEquals(a[0], b[0])
    }
}
