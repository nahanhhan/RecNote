package io.github.nahanhhan.lecture.core

import kotlin.math.abs

/**
 * 自动开录区间，以日程开始时刻为 0：正数表示早于开始，负数表示晚于开始。
 * 区间为 `lowerMin..upperMin` 分钟（闭区间），例如默认 -3..+3 即开始前后各 3 分钟。
 */
data class AutoStartWindow(val lowerMin: Int, val upperMin: Int) {
    init {
        require(lowerMin in MIN..MAX && upperMin in MIN..MAX && lowerMin <= upperMin) { "无效的自动开录区间" }
    }

    /** 日程开始时刻 [startMs] 相对 [nowMs] 是否落在区间内。 */
    fun contains(startMs: Long, nowMs: Long): Boolean {
        val offset = startMs - nowMs
        return offset >= lowerMin * MINUTE_MS && offset <= upperMin * MINUTE_MS
    }

    /** 最早允许开录的时刻：开始前 [upperMin] 分钟。 */
    fun earliestMs(startMs: Long): Long = startMs - upperMin * MINUTE_MS

    companion object {
        const val MIN = -10
        const val MAX = 5
        const val MINUTE_MS = 60_000L
        val DEFAULT = AutoStartWindow(-3, 3)

        /** 界面拖动时使用：越界值夹到范围内，并保证下拉杆不超过上拉杆。 */
        fun clamped(first: Int, second: Int): AutoStartWindow {
            val a = first.coerceIn(MIN, MAX)
            val b = second.coerceIn(MIN, MAX)
            return AutoStartWindow(minOf(a, b), maxOf(a, b))
        }

        /** 读取持久化值：缺失、越界或顺序错误一律回落默认区间。 */
        fun fromStorage(lower: Int?, upper: Int?): AutoStartWindow {
            if (lower == null || upper == null) return DEFAULT
            if (lower !in MIN..MAX || upper !in MIN..MAX || lower > upper) return DEFAULT
            return AutoStartWindow(lower, upper)
        }
    }
}

/** 根据当前时刻和阈值从日程出现项中选出应录制的那一个。全天事件永不参与。 */
object ScheduleResolver {
    /**
     * 自动开录：当前时刻落在区间内且该出现项尚无关联课堂（[existingKeys]）的候选，
     * 取开始时刻最接近当前的一个，距离相同取开始较早者。
     */
    fun resolveAutoStart(nowMs: Long, occurrences: List<Occurrence>, window: AutoStartWindow, existingKeys: Set<String>): Occurrence? =
        nearest(nowMs, occurrences.filter { !it.allDay && it.key !in existingKeys && window.contains(it.startMs, nowMs) })

    /**
     * 当前进行中的日程：从可开录的最早时刻起到结束时刻之前，或位于自动开录区间内。
     * 用于手动开始时判断是否跳过标题对话框。
     */
    fun resolveActive(nowMs: Long, occurrences: List<Occurrence>, window: AutoStartWindow): Occurrence? =
        nearest(nowMs, occurrences.filter {
            !it.allDay && ((nowMs >= window.earliestMs(it.startMs) && nowMs < it.endMs) || window.contains(it.startMs, nowMs))
        })

    private fun nearest(nowMs: Long, candidates: List<Occurrence>): Occurrence? =
        candidates.minWithOrNull(compareBy<Occurrence>({ abs(it.startMs - nowMs) }, { it.startMs }))
}

/** 同一日程出现项下的课堂标题：首次用日程名，之后依次为「日程名-1」「日程名-2」。 */
object SessionNaming {
    /**
     * [existingTitles] 为该日程出现项已有课堂的标题。没有已有课堂时返回 [scheduleTitle]；
     * 否则返回「最大已有后缀 + 1」，后缀只统计形如「日程名-整数」的标题，且保证不与已有标题重名。
     */
    fun titleFor(scheduleTitle: String, existingTitles: Collection<String>): String {
        if (existingTitles.isEmpty()) return scheduleTitle
        val pattern = Regex("^" + Regex.escape(scheduleTitle) + "-(\\d+)$")
        var next = (existingTitles.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        while ("$scheduleTitle-$next" in existingTitles) next++
        return "$scheduleTitle-$next"
    }
}
