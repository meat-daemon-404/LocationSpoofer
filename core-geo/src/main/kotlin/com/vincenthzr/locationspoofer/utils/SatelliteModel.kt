package com.vincenthzr.locationspoofer.utils

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可见卫星数与定位解算卫星数模型。
 *
 * 存在的理由只有一个：卫星数如果是一个常量，它就是一个可跨会话聚合的指纹——
 * 每一次跑步都是 20 颗可见、12 颗参与解算，四十分钟一条直线，任何统计都能把它挑出来。
 * 真机的可见卫星数随时间缓慢起伏（天空遮蔽、卫星过境、信号遮挡），而且
 * 「参与解算」通常是「可见」的子集，「可见」又通常小于「在视野内」。
 *
 * 所以这里提供三件事：
 * 1. 同一个物理量在**所有通道**（Location.extras、GnssStatus、高德/百度 SDK、NMEA）用同一份计算；
 * 2. 数值随会话时间缓慢起伏，不是常量；
 * 3. 三者之间的包含关系恒成立：`usedInFix ≤ visible ≤ inView`。
 *
 * 计算是纯函数式的：输入「基准值 + 种子 + 绝对时刻」就能直接算出结果，
 * 不依赖任何跨进程共享的状态，因此不同 App 进程在同一时刻拿到的是同一组数。
 */
object SatelliteModel {
    /** 可见卫星数的合法区间（真机低于 8 基本等同于定位不可用） */
    const val MIN_VISIBLE = 8
    const val MAX_VISIBLE = 32

    /** 参与解算的卫星数下限 */
    const val MIN_USED_IN_FIX = 4

    /** 可见数围绕基准值的摆动幅度（颗） */
    private const val VISIBLE_SWING = 3

    private const val VISIBLE_SALT = 0x5A7E11A7L
    private const val USED_SALT = 0x3C0FFEE1L
    private const val IN_VIEW_SALT = 0x1DEA5EEDL

    /** 组数：可见 / 视野内 / 参与解算 */
    data class Counts(val visible: Int, val inView: Int, val usedInFix: Int)

    private class Model(seed: Long) {
        val visible = MotionRealism.SmoothNoise(seed xor VISIBLE_SALT, 240.0, 900.0)
        val used = MotionRealism.SmoothNoise(seed xor USED_SALT, 180.0, 700.0)
        val inView = MotionRealism.SmoothNoise(seed xor IN_VIEW_SALT, 300.0, 1200.0)
    }

    @Volatile
    private var cachedSeed: Long = Long.MIN_VALUE

    @Volatile
    private var cachedModel: Model = Model(0L)

    private fun model(seed: Long): Model {
        if (cachedSeed == seed) return cachedModel
        synchronized(this) {
            if (cachedSeed != seed) {
                cachedModel = Model(seed)
                cachedSeed = seed
            }
            return cachedModel
        }
    }

    /**
     * @param baseCount 用户配置的基准卫星数（`satellite_count`）
     * @param seed 会话种子，取配置里的 `start_timestamp`，保证同一次模拟内各通道一致
     * @param epochMs 绝对时刻（墙钟毫秒）
     */
    fun counts(baseCount: Int, seed: Long, epochMs: Long): Counts {
        val base = baseCount.coerceIn(MIN_VISIBLE, MAX_VISIBLE)
        val t = epochMs / 1000.0
        val m = model(seed)

        val visible = (base + m.visible.value(t) * VISIBLE_SWING).roundToInt()
            .coerceIn(MIN_VISIBLE, MAX_VISIBLE)

        // 可见里有多少颗没参与解算：2~6 颗，随时间缓慢变化
        val drop = (2.0 + (m.used.value(t) + 1.0) * 2.0).roundToInt().coerceIn(2, 6)
        val usedInFix = (visible - drop).coerceIn(MIN_USED_IN_FIX, visible)

        // 视野内（含被遮挡的）比可见多 0~3 颗
        val extra = ((m.inView.value(t) + 1.0) * 1.5).roundToInt().coerceIn(0, 3)
        val inView = (visible + extra).coerceAtMost(MAX_VISIBLE)

        return Counts(visible = visible, inView = inView, usedInFix = usedInFix)
    }

    /** 是否真的在起伏（自检用：常量就是缺陷，这里直接给判据） */
    fun varies(baseCount: Int, seed: Long, fromMs: Long, toMs: Long, stepMs: Long = 60_000L): Boolean {
        var minVisible = Int.MAX_VALUE
        var maxVisible = Int.MIN_VALUE
        var minUsed = Int.MAX_VALUE
        var maxUsed = Int.MIN_VALUE
        var t = fromMs
        while (t <= toMs) {
            val c = counts(baseCount, seed, t)
            minVisible = minOf(minVisible, c.visible)
            maxVisible = maxOf(maxVisible, c.visible)
            minUsed = minOf(minUsed, c.usedInFix)
            maxUsed = maxOf(maxUsed, c.usedInFix)
            t += stepMs
        }
        return abs(maxVisible - minVisible) >= 2 || abs(maxUsed - minUsed) >= 2
    }
}
