package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorBiasModelTest {

    @Test
    fun `bias stays inside the documented bound for every kind`() {
        val seed = 1_700_000_000_000L
        var t = 0.0
        while (t <= 3_600.0) {
            for (kind in SensorBiasModel.Kind.entries) {
                val bias = SensorBiasModel.bias(kind, seed, t)
                assertEquals(3, bias.size)
                for (value in bias) {
                    assertTrue(
                        "bias $value out of bound for $kind at t=$t",
                        kotlin.math.abs(value) <= kind.scale + 1e-6
                    )
                }
            }
            t += 30.0
        }
    }

    @Test
    fun `bias drifts over an hour instead of staying constant`() {
        for (kind in SensorBiasModel.Kind.entries) {
            assertTrue(
                "$kind bias should drift",
                SensorBiasModel.varies(kind, 1_700_000_000_000L, 0.0, 3_600.0, 30.0)
            )
        }
    }

    @Test
    fun `bias drifts slowly enough to look like a device`() {
        val seed = 42L
        for (kind in SensorBiasModel.Kind.entries) {
            val first = SensorBiasModel.bias(kind, seed, 1_000.0)
            val next = SensorBiasModel.bias(kind, seed, 1_001.0)
            for (i in 0 until 3) {
                val step = kotlin.math.abs(next[i] - first[i])
                assertTrue(
                    "$kind axis $i jumped $step in one second",
                    step <= kind.scale * 0.05
                )
            }
        }
    }

    @Test
    fun `same session and instant give the same bias`() {
        val a = SensorBiasModel.bias(SensorBiasModel.Kind.GYROSCOPE, 7L, 512.0)
        val b = SensorBiasModel.bias(SensorBiasModel.Kind.GYROSCOPE, 7L, 512.0)
        assertEquals(a[0], b[0], 0.0f)
        assertEquals(a[1], b[1], 0.0f)
        assertEquals(a[2], b[2], 0.0f)
    }

    @Test
    fun `different sessions do not share the same bias`() {
        val instants = listOf(13L, 999L, 1_700_000_000_000L, 1_700_000_000_001L)
        val samples = instants.map { SensorBiasModel.bias(SensorBiasModel.Kind.ACCELEROMETER, it, 300.0)[0] }
        assertTrue("sessions should differ", samples.toSet().size >= 2)
        assertNotEquals(samples[0], samples[1])
    }

    @Test
    fun `kinds are scaled differently`() {
        val seed = 1_234L
        val gyro = SensorBiasModel.bias(SensorBiasModel.Kind.GYROSCOPE, seed, 100.0)
        val accel = SensorBiasModel.bias(SensorBiasModel.Kind.ACCELEROMETER, seed, 100.0)
        val magnetic = SensorBiasModel.bias(SensorBiasModel.Kind.MAGNETIC_FIELD, seed, 100.0)
        // 三者的量级差一个数量级以上，混用会立刻穿帮
        for (i in 0 until 3) {
            assertTrue(kotlin.math.abs(gyro[i]) <= SensorBiasModel.GYROSCOPE_BIAS_RAD_PER_SEC)
            assertTrue(kotlin.math.abs(accel[i]) <= SensorBiasModel.ACCELEROMETER_BIAS_MPS2)
            assertTrue(kotlin.math.abs(magnetic[i]) <= SensorBiasModel.MAGNETIC_FIELD_BIAS_MICRO_TESLA)
        }
    }

    @Test
    fun `negative elapsed time is clamped instead of extrapolated`() {
        val clamped = SensorBiasModel.bias(SensorBiasModel.Kind.GYROSCOPE, 5L, -3600.0)
        val atZero = SensorBiasModel.bias(SensorBiasModel.Kind.GYROSCOPE, 5L, 0.0)
        assertEquals(atZero[0], clamped[0], 0.0f)
        assertEquals(atZero[1], clamped[1], 0.0f)
        assertEquals(atZero[2], clamped[2], 0.0f)
    }
}
