package com.vincenthzr.locationspoofer.utils

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRealismTest {

    private val start = 1_789_398_037_217L

    @Test
    fun `level off with no speed fluctuation keeps the legacy uniform motion`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        assertEquals(3.0 * 125.0, s.distance(3.0, 125.0), 1e-9)
        assertEquals(3.0, s.speed(3.0, 125.0), 1e-12)
        assertEquals(165.0 / 60.0 * 125.0, s.steps(165.0, 125.0), 1e-9)
    }

    @Test
    fun `sessions with the same start timestamp agree across processes`() {
        val a = MotionRealism.Session(start, MotionRealism.Level.HIGH, 20)
        val b = MotionRealism.Session(start, MotionRealism.Level.HIGH, 20)
        val c = MotionRealism.Session(start + 1, MotionRealism.Level.HIGH, 20)
        assertEquals(a.distance(3.0, 777.0), b.distance(3.0, 777.0), 0.0)
        assertEquals(a.steps(160.0, 777.0), b.steps(160.0, 777.0), 0.0)
        assertNotEquals(a.distance(3.0, 777.0), c.distance(3.0, 777.0), 1e-6)
    }

    @Test
    fun `speed stays within the configured fluctuation and matches the distance derivative`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 20)
        var sawVariation = false
        for (t in 0..3600 step 7) {
            val v = s.speed(3.0, t.toDouble())
            assertTrue("speed $v at $t", v in 3.0 * 0.8 - 1e-9..3.0 * 1.2 + 1e-9)
            if (abs(v - 3.0) > 0.1) sawVariation = true
            val h = 1e-3
            val derivative = (s.distance(3.0, t + h) - s.distance(3.0, t.toDouble())) / h
            assertEquals(v, derivative, 1e-3)
        }
        assertTrue(sawVariation)
    }

    @Test
    fun `step count keeps increasing and cadence varies around the base`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.HIGH, 15)
        var last = -1.0
        var minCadence = Double.MAX_VALUE
        var maxCadence = 0.0
        for (t in 0..1800) {
            val steps = s.steps(170.0, t.toDouble())
            assertTrue(steps > last)
            last = steps
            val c = s.cadence(170.0, t.toDouble())
            minCadence = minOf(minCadence, c)
            maxCadence = maxOf(maxCadence, c)
        }
        assertTrue(maxCadence - minCadence > 5)
        assertTrue(minCadence > 170 * 0.85 && maxCadence < 170 * 1.15)
    }

    @Test
    fun `procedural gait averages to gravity over whole cycles`() {
        for (speed in listOf(1.3, 3.2)) {
            val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
            val rnd = Random(1)
            val n = 20_000
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for (i in 0 until n) {
                val stepsFloat = i * 40.0 / n // 恰好 40 步 = 20 个完整跨步
                val a = s.accelerometer(stepsFloat, speed, null, rnd)
                sx += a[0]; sy += a[1]; sz += a[2]
            }
            assertEquals(0.0, sx / n, 0.05)
            assertEquals(0.0, sy / n, 0.05)
            assertEquals(9.80665, sz / n, 0.05)
        }
    }

    @Test
    fun `step impact strength differs between steps when randomness is on`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 0)
        val quiet = Random(0)
        // 关闭噪声的对照：同一相位、不同步序号，冲击峰值应当不同
        val peaks = (0 until 6).map { step -> s.accelerometer(step.toDouble(), 1.4, null, quiet)[2] }
        assertTrue(peaks.toSet().size > 1)
    }

    @Test
    fun `session cache returns the same instance for the same parameters`() {
        val a = MotionRealism.session(start, 2, 10)
        val b = MotionRealism.session(start, 2, 10)
        val c = MotionRealism.session(start, 2, 20)
        assertTrue(a === b)
        assertTrue(a !== c)
    }

    @Test
    fun `altitude drift never falls back to zero, whatever the realism level`() {
        // OFF 档的 altitudeAmplitudeM 是 0；直接用它会让原地模拟的海拔变成一条常量直线。
        assertEquals(MotionRealism.MIN_ALTITUDE_DRIFT_M, MotionRealism.altitudeDriftM(MotionRealism.Level.OFF), 0.0)
        assertEquals(MotionRealism.MIN_ALTITUDE_DRIFT_M, MotionRealism.altitudeDriftM(MotionRealism.Level.LOW), 0.0)
        // 高于下限的档位保持原值，行为不变
        assertEquals(3.0, MotionRealism.altitudeDriftM(MotionRealism.Level.MEDIUM), 0.0)
        assertEquals(5.0, MotionRealism.altitudeDriftM(MotionRealism.Level.HIGH), 0.0)

        // 下限必须真的产生随时间变化的量：站在原地 10 分钟，海拔不能是一条直线
        val t0 = start
        val values = (0..600 step 10).map {
            AltitudeModel.altitude(
                50.0,
                MotionRealism.MIN_ALTITUDE_DRIFT_M,
                MotionRealism.altitudeDriftM(MotionRealism.Level.OFF),
                23.1066,
                113.3245,
                t0 + it * 1000L
            )
        }
        assertTrue("span ${values.max() - values.min()}", values.max() - values.min() > 0.2)
    }

    @Test
    fun `gravity is a constant vector of standard magnitude`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 10)
        val g = s.gravity()
        assertEquals(0f, g[0], 0f)
        assertEquals(0f, g[1], 0f)
        assertEquals(9.80665f, g[2], 1e-4f)
        assertEquals(3, g.size)
    }

    @Test
    fun `linear acceleration is exactly the accelerometer minus gravity`() {
        // 同一时刻的两路读数必须能相互还原，否则应用把两路一减就看出通道不自洽
        val s = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 10)
        val noise = Random(7)
        val g = s.gravity()
        var sawMotion = false
        for (t in 0..120 step 2) {
            val stepsFloat = s.steps(170.0, t.toDouble())
            val accel = s.accelerometer(stepsFloat, 3.0, null, noise)
            val linear = s.linearAcceleration(accel)
            for (i in 0..2) {
                assertEquals("axis $i at $t", accel[i] - g[i], linear[i], 1e-6f)
            }
            if (abs(linear[2]) > 0.5f) sawMotion = true
        }
        assertTrue("linear acceleration should show the gait waveform", sawMotion)
    }

    @Test
    fun `linear acceleration averages to zero over a stride while gravity carries the mean`() {
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        val noise = Random(11)
        var sumZ = 0.0
        var sumAccelZ = 0.0
        var n = 0
        // 采样步长必须远小于步态周期，否则会落在同一步的同一相位上、把波形混叠成常数
        var i = 0
        while (i < 4000) {
            val t = i * 0.015
            val stepsFloat = s.steps(170.0, t)
            val accel = s.accelerometer(stepsFloat, 3.0, null, noise)
            val linear = s.linearAcceleration(accel)
            sumZ += linear[2]
            sumAccelZ += accel[2]
            n++
            i++
        }
        assertTrue("mean linear z ${sumZ / n}", abs(sumZ / n) < 0.6)
        assertTrue("mean accel z ${sumAccelZ / n}", abs(sumAccelZ / n - 9.80665) < 0.6)
    }

    @Test
    fun `standing still leaves only gravity on the accelerometer`() {
        // 静止时加速度计若还在摆步态波形，就与「线性加速度≈0、步数不增长」相互矛盾
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 0)
        val noise = Random(3)
        var maxDeviation = 0f
        var i = 0
        while (i < 200) {
            val accel = s.accelerometer((i * 0.37), 0.0, null, noise)
            assertEquals(0f, accel[0], 0f)
            assertEquals(0f, accel[1], 0f)
            maxDeviation = maxOf(maxDeviation, abs(accel[2] - 9.80665f))
            i++
        }
        assertTrue("max deviation $maxDeviation", maxDeviation < 1e-3f)
    }
}
