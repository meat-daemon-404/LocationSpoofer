package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AttitudeModelTest {

    private val headings = doubleArrayOf(0.0, 17.0, 90.0, 145.0, 180.0, 271.0, 359.0)

    @Test
    fun `rotation vector is a unit quaternion at every heading`() {
        for (heading in headings) {
            val q = AttitudeModel.rotationVector(heading)
            val norm = sqrt(
                q[0].toDouble() * q[0] + q[1].toDouble() * q[1] +
                    q[2].toDouble() * q[2] + q[3].toDouble() * q[3]
            )
            assertEquals("heading=$heading", 1.0, norm, 1e-6)
        }
    }

    @Test
    fun `rotation vector reprojects world gravity onto the gravity channel`() {
        // 这是本项改动的核心恒等式：应用拿旋转向量反解重力，必须得到重力通道给的值
        val expected = MotionRealism.session(1234L, MotionRealism.Level.MEDIUM.id, 10).gravity()
        for (heading in headings) {
            val q = AttitudeModel.rotationVector(heading)
            val device = AttitudeModel.rotateToDevice(q, AttitudeModel.worldGravity())
            assertEquals("x heading=$heading", expected[0].toDouble(), device[0].toDouble(), 1e-5)
            assertEquals("y heading=$heading", expected[1].toDouble(), device[1].toDouble(), 1e-5)
            assertEquals("z heading=$heading", expected[2].toDouble(), device[2].toDouble(), 1e-5)
        }
    }

    @Test
    fun `rotation vector reprojects world magnetic field onto the magnetic channel`() {
        // 应用用旋转向量反解磁场，必须得到磁力计通道给的值（含噪声前的基值）
        for (latitude in doubleArrayOf(-33.0, 0.0, 18.25, 30.27, 45.0, 62.0)) {
            val world = GeomagneticModel.worldField(latitude)
            for (heading in headings) {
                val q = AttitudeModel.rotationVector(heading)
                val reprojected = AttitudeModel.rotateToDevice(q, world)
                val direct = GeomagneticModel.deviceField(latitude, heading)
                assertEquals("lat=$latitude h=$heading x", direct[0].toDouble(), reprojected[0].toDouble(), 1e-4)
                assertEquals("lat=$latitude h=$heading y", direct[1].toDouble(), reprojected[1].toDouble(), 1e-4)
                assertEquals("lat=$latitude h=$heading z", direct[2].toDouble(), reprojected[2].toDouble(), 1e-4)
            }
        }
    }

    @Test
    fun `rotating to world and back returns the original vector`() {
        for (heading in headings) {
            val q = AttitudeModel.rotationVector(heading)
            val device = floatArrayOf(1.5f, -2.25f, 9.75f)
            val world = AttitudeModel.rotateToWorld(q, device)
            val back = AttitudeModel.rotateToDevice(q, world)
            assertEquals(device[0].toDouble(), back[0].toDouble(), 1e-5)
            assertEquals(device[1].toDouble(), back[1].toDouble(), 1e-5)
            assertEquals(device[2].toDouble(), back[2].toDouble(), 1e-5)
        }
    }

    @Test
    fun `a north facing phone carries gravity on its up axis only`() {
        val q = AttitudeModel.rotationVector(0.0)
        val matrix = AttitudeModel.rotationMatrix(q)
        // 平放朝北时旋转矩阵接近单位阵
        val expected = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (i in expected.indices) {
            assertTrue("i=$i value=${matrix[i]}", abs(matrix[i] - expected[i]) < 1e-6)
        }
    }
}
