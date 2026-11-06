package com.vincenthzr.locationspoofer.utils

import kotlin.math.floor

/**
 * 把「连续累计步数」换算成「每一步真正跨越的时刻」。
 *
 * 背景：`TYPE_STEP_COUNTER` 给的是累计步数（整数），[MotionRealism.Session.steps] 给的是连续值
 * （整数部分是步数，小数部分是这一步走了多少）。`TYPE_STEP_DETECTOR` 在真机上每检测到一步就回调一次，
 * 间隔就是 `60/步频` 秒，而且**每一步各自的时刻不同**。
 *
 * 早先的实现是「每秒补发 N 个事件，时刻按当前步频等间隔往回推」。这能骗过"平均步频"，但有两个破绽：
 *  1. 步频变化时，同一秒内的步间隔会与相邻秒不一致（真机是连续的相位推进）；
 *  2. 事件时刻与 [MotionRealism.Session.steps] 的整数穿越点不对齐，下游拿步数差 / 时间差算出来的
 *     瞬时步频会出现锯齿。
 *
 * 这里改成插值：记下上次采样的 `(步数, 时刻)`，这次采样如果步数增加了，就在这两个采样点之间按
 * 步数比例线性插值出每一个整数步的跨越时刻——那才是"这一步落地"的真实时刻。
 *
 * 与 [MotionRealism] 一样保持纯计算、无外部依赖，便于单测。
 */
class StepEventTimeline(
    /** 单次最多补发多少步：跨度过大（切会话、长时间卡顿）时只保留最近这么多，避免事件风暴 */
    private val maxCatchUp: Int = DEFAULT_MAX_CATCH_UP,
    /** 早于「当前时刻 - 这个窗口」的步事件直接丢弃：补发几秒前的事件只会让下游的步频统计错乱 */
    private val maxAgeNs: Long = DEFAULT_MAX_AGE_NS
) {

    private var previousNanos: Long = 0L
    private var previousSteps: Double? = null

    /** 切会话（换 `start_timestamp`）时调用，避免把上一场的步数当成这一段增量 */
    fun reset() {
        previousNanos = 0L
        previousSteps = null
    }

    /**
     * 采样当前连续步数与时刻，返回本次需要补发的步事件时刻（纳秒，升序）。
     *
     * 返回空数组表示：首帧对齐、步数没有增加、时间倒退，或所有跨越点都已过期。
     */
    fun sample(stepsFloat: Double, nowNs: Long): LongArray {
        val previous = previousSteps
        // 时间倒退（换会话 / 时钟回拨）不做任何补发，但要把基准纠正过来
        if (previous != null && nowNs <= previousNanos) {
            if (nowNs < previousNanos) {
                previousSteps = stepsFloat
                previousNanos = nowNs
            }
            return EMPTY
        }

        val previousAt = previousNanos
        previousSteps = stepsFloat
        previousNanos = nowNs

        if (previous == null || !(stepsFloat > previous)) return EMPTY

        val span = stepsFloat - previous
        if (!(span > 0.0)) return EMPTY

        val firstCrossing = floor(previous).toLong() + 1L
        val lastCrossing = floor(stepsFloat).toLong()
        if (lastCrossing < firstCrossing) return EMPTY

        // maxCatchUp 指的是"最多补发这么多条"，所以下界要按条数回推，而不是按跨过的整数个数
        val earliest = lastCrossing - (maxCatchUp.coerceAtLeast(1) - 1).toLong()
        val start = if (firstCrossing > earliest) firstCrossing else earliest
        if (start > lastCrossing) return EMPTY

        val oldestAllowed = nowNs - maxAgeNs
        val interval = (nowNs - previousAt).toDouble()
        val count = (lastCrossing - start + 1L).toInt()
        val out = LongArray(count)
        var filled = 0
        var step = start
        while (step <= lastCrossing) {
            val ratio = (step - previous) / span
            val at = (ratio * interval).toLong() + previousAt
            if (at >= oldestAllowed) {
                out[filled] = at
                filled++
            }
            step++
        }
        return if (filled == count) out else out.copyOf(filled)
    }

    companion object {
        const val DEFAULT_MAX_CATCH_UP: Int = 8

        /** 2 秒：真机补发更早的步事件已经不在任何步频统计窗口里了 */
        const val DEFAULT_MAX_AGE_NS: Long = 2_000_000_000L

        private val EMPTY = LongArray(0)
    }
}
