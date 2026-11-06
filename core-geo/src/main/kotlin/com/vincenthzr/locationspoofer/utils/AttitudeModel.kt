package com.vincenthzr.locationspoofer.utils

/**
 * 姿态的极简模型：假设手机**平放、屏幕朝上、机头朝行进方向**。
 *
 * 这个假设与传感器通道里的既有约定必须一致：
 * * [MotionRealism.Session.gravity] 给出 `(0, 0, g)`，即竖直轴朝上；
 * * [GyroscopeModel] 的 z 轴偏差项取的是航向变化率。
 *
 * [rotationVector] 生成的旋转向量就是把这个假设写成四元数，使它满足
 * `rotateToDevice(q, 世界重力) == gravity`、`rotateToDevice(q, 世界磁场) == 设备磁场`——
 * 应用拿旋转向量去反解重力或磁场，得到的值与本仓库其它通道给的值一致。
 *
 * 四元数采用 Android `Sensor.TYPE_ROTATION_VECTOR` 的约定：`(x, y, z, w)`，
 * 表示把**设备坐标系**的向量旋转到世界坐标系（东-北-天）。
 */
object AttitudeModel {

    const val GRAVITY: Double = 9.80665

    private const val DEG_TO_RAD = Math.PI / 180.0

    /**
     * 平放、机头朝 `headingDeg`（相对正北顺时针）时的旋转向量。
     *
     * 推导：设备三轴在世界系里分别是 X=(cos h, -sin h, 0)、Y=(sin h, cos h, 0)、Z=(0, 0, 1)，
     * 因此 R 是绕 Z 轴转 -h，四元数为 `(0, 0, -sin(h/2), cos(h/2))`。
     */
    fun rotationVector(headingDeg: Double): FloatArray {
        val half = headingDeg * DEG_TO_RAD / 2.0
        return floatArrayOf(0.0f, 0.0f, (-kotlin.math.sin(half)).toFloat(), kotlin.math.cos(half).toFloat())
    }

    /** 旋转矩阵（行优先，3×3）：把设备坐标变换到世界坐标。 */
    fun rotationMatrix(rotationVector: FloatArray): DoubleArray {
        val x = rotationVector[0].toDouble()
        val y = rotationVector[1].toDouble()
        val z = rotationVector[2].toDouble()
        val w = rotationVector[3].toDouble()
        val norm = x * x + y * y + z * z + w * w
        val s = if (norm > 1e-12) 2.0 / norm else 0.0
        return doubleArrayOf(
            1.0 - s * (y * y + z * z), s * (x * y - w * z), s * (x * z + w * y),
            s * (x * y + w * z), 1.0 - s * (x * x + z * z), s * (y * z - w * x),
            s * (x * z - w * y), s * (y * z + w * x), 1.0 - s * (x * x + y * y)
        )
    }

    /** 世界坐标 → 设备坐标（等价于 `SensorManager` 里用旋转矩阵的转置乘）。 */
    fun rotateToDevice(rotationVector: FloatArray, world: FloatArray): FloatArray {
        val m = rotationMatrix(rotationVector)
        val wx = world[0].toDouble()
        val wy = world[1].toDouble()
        val wz = world[2].toDouble()
        // R^T * v：(行 i, 列 j) 的转置即 (列 i, 行 j)
        return floatArrayOf(
            (m[0] * wx + m[3] * wy + m[6] * wz).toFloat(),
            (m[1] * wx + m[4] * wy + m[7] * wz).toFloat(),
            (m[2] * wx + m[5] * wy + m[8] * wz).toFloat()
        )
    }

    /** 设备坐标 → 世界坐标。 */
    fun rotateToWorld(rotationVector: FloatArray, device: FloatArray): FloatArray {
        val m = rotationMatrix(rotationVector)
        val dx = device[0].toDouble()
        val dy = device[1].toDouble()
        val dz = device[2].toDouble()
        return floatArrayOf(
            (m[0] * dx + m[1] * dy + m[2] * dz).toFloat(),
            (m[3] * dx + m[4] * dy + m[5] * dz).toFloat(),
            (m[6] * dx + m[7] * dy + m[8] * dz).toFloat()
        )
    }

    /** 世界坐标系里的重力向量（天向为正，与 [MotionRealism.Session.gravity] 的符号约定一致）。 */
    fun worldGravity(): FloatArray = floatArrayOf(0.0f, 0.0f, GRAVITY.toFloat())
}
