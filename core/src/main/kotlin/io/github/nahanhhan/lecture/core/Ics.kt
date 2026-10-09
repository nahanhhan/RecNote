package io.github.nahanhhan.lecture.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** ICS 文件无法使用（不是 ICS、没有任何事件）时抛出，消息可直接展示给用户。 */
class IcsParseException(message: String) : Exception(message)

/** 展开折行后的一行属性：名称（大写）、参数（键大写）与未转义的原始值。 */
data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

/** 行级处理：折行展开、属性拆分、文本转义还原。 */
object IcsLines {
    private val lineBreak = Regex("\r\n|\n|\r")

    /** 行首为空格或制表符的行并入上一行（RFC 5545 折行），并丢弃空行。 */
    fun unfold(text: String): List<String> {
        val lines = mutableListOf<StringBuilder>()
        for (raw in lineBreak.split(text.removePrefix("\uFEFF"))) {
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t') && lines.isNotEmpty()) lines.last().append(raw.substring(1))
            else lines.add(StringBuilder(raw))
        }
        return lines.map { it.toString() }.filter { it.isNotBlank() }
    }

    /** 拆分为名称、参数和值；引号内的冒号与分号不作分隔。无法拆分返回 null。 */
    fun parse(line: String): IcsProperty? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = splitOutsideQuotes(line.substring(0, colon), ';')
        val params = mutableMapOf<String, String>()
        for (part in head.drop(1)) {
            val eq = part.indexOf('=')
            if (eq > 0) params[part.substring(0, eq).trim().uppercase()] = part.substring(eq + 1).trim().removeSurrounding("\"")
        }
        return IcsProperty(head[0].trim().uppercase(), params, line.substring(colon + 1))
    }

    /** 还原 TEXT 值转义：反斜杠加 n 为换行，反斜杠加逗号、分号、反斜杠为字符本身。 */
    fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                if (next == 'n' || next == 'N') out.append('\n') else out.append(next)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun splitOutsideQuotes(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        var quoted = false
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (c == '"') quoted = !quoted
            else if (c == separator && !quoted) { parts.add(text.substring(start, i)); start = i + 1 }
        }
        parts.add(text.substring(start))
        return parts
    }
}

/** 带时区语义的时刻：本地墙钟时间 + 时区；全天事件只有日期有效。 */
data class IcsMoment(val local: LocalDateTime, val zone: ZoneId, val allDay: Boolean) {
    val epochMs: Long get() = local.atZone(zone).toInstant().toEpochMilli()
}

enum class RecurrenceFreq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** 受支持的重复规则子集；[untilMs] 为含边界的绝对时刻。 */
data class RecurrenceRule(
    val freq: RecurrenceFreq, val interval: Int, val count: Int?, val untilMs: Long?, val byDay: Set<DayOfWeek>
)

/** 规则解析结果：不支持时 [rule] 为空并给出 [unsupported] 原因。 */
data class RuleParse(val rule: RecurrenceRule?, val unsupported: String?)

/** 时间、时长与重复规则的文本解析。 */
object IcsTime {
    private val windowsZones = mapOf(
        "China Standard Time" to "Asia/Shanghai", "Asia/Beijing" to "Asia/Shanghai",
        "Taipei Standard Time" to "Asia/Taipei", "Tokyo Standard Time" to "Asia/Tokyo",
        "Singapore Standard Time" to "Asia/Singapore", "UTC" to "UTC", "GMT Standard Time" to "Europe/London",
        "W. Europe Standard Time" to "Europe/Berlin", "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago", "Pacific Standard Time" to "America/Los_Angeles"
    )
    private val dayCodes = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY
    )
    private val unsupportedRuleKeys = setOf(
        "BYSETPOS", "BYMONTHDAY", "BYMONTH", "BYWEEKNO", "BYYEARDAY", "BYHOUR", "BYMINUTE", "BYSECOND"
    )
    private val durationPattern = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

    /** 解析 TZID；无法识别返回 null。 */
    fun zone(tzid: String): ZoneId? {
        val name = tzid.trim().removeSurrounding("\"").trimStart('/')
        windowsZones[name]?.let { return ZoneId.of(it) }
        return try { ZoneId.of(name) } catch (e: Exception) { null }
    }

    /** 解析 `yyyyMMdd` 或 `yyyyMMddTHHmmss[Z]` 的数字部分；失败返回 null。 */
    fun local(value: String): LocalDateTime? = try {
        val v = value.trim()
        val date = LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
        if (v.length >= 13 && (v[8] == 'T' || v[8] == 't')) {
            val second = if (v.length >= 15) v.substring(13, 15).toInt().coerceAtMost(59) else 0
            date.atTime(v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), second)
        } else date.atStartOfDay()
    } catch (e: Exception) { null }

    /** 解析一个时间属性：UTC、带 TZID、浮动（按 [device] 时区）、全天。 */
    fun moment(property: IcsProperty, device: ZoneId): IcsMoment? {
        val v = property.value.trim()
        val dateTime = local(v) ?: return null
        val allDay = property.params["VALUE"].equals("DATE", true) || !v.contains('T', true)
        if (allDay) return IcsMoment(dateTime.toLocalDate().atStartOfDay(), device, true)
        val resolved: ZoneId = if (v.endsWith("Z", true)) ZoneOffset.UTC else (property.params["TZID"]?.let { zone(it) } ?: device)
        return IcsMoment(dateTime, resolved, false)
    }

    /** 解析 ISO 8601 风格的时长，如 `PT1H40M`、`P1D`、`-PT15M`。 */
    fun duration(value: String): Duration? {
        val m = durationPattern.matchEntire(value.trim().uppercase()) ?: return null
        fun n(i: Int): Long = m.groupValues[i].toLongOrNull() ?: 0L
        val d = Duration.ofDays(n(2) * 7 + n(3)).plusHours(n(4)).plusMinutes(n(5)).plusSeconds(n(6))
        return if (m.groupValues[1] == "-") d.negated() else d
    }

    /** 解析 RRULE，仅支持 [RecurrenceRule] 描述的子集；其余返回不支持原因。 */
    fun rule(value: String, start: IcsMoment): RuleParse {
        val map = value.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim().uppercase() to part.substring(i + 1).trim()
        }.toMap()
        map.keys.firstOrNull { it in unsupportedRuleKeys }?.let { return RuleParse(null, "使用了不支持的重复参数 $it") }
        val freq = when (map["FREQ"]?.uppercase()) {
            "DAILY" -> RecurrenceFreq.DAILY
            "WEEKLY" -> RecurrenceFreq.WEEKLY
            "MONTHLY" -> RecurrenceFreq.MONTHLY
            "YEARLY" -> RecurrenceFreq.YEARLY
            else -> return RuleParse(null, "使用了不支持的重复频率 ${map["FREQ"] ?: "空"}")
        }
        val days = mutableSetOf<DayOfWeek>()
        val dayTokens = map["BYDAY"]?.split(',') ?: emptyList()
        for (token in dayTokens) {
            val day = dayCodes[token.trim().uppercase()] ?: return RuleParse(null, "使用了不支持的 BYDAY 值 $token")
            days.add(day)
        }
        if (freq == RecurrenceFreq.YEARLY && days.isNotEmpty()) return RuleParse(null, "年重复不支持 BYDAY")
        val untilText = map["UNTIL"]
        val until = untilText?.let { untilMs(it, start) }
        if (untilText != null && until == null) return RuleParse(null, "无法解析 UNTIL $untilText")
        val interval = map["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = map["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
        return RuleParse(RecurrenceRule(freq, interval, count, until, days), null)
    }

    private fun untilMs(value: String, start: IcsMoment): Long? {
        val v = value.trim()
        val dateTime = local(v) ?: return null
        return when {
            v.endsWith("Z", true) -> dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
            !v.contains('T', true) -> dateTime.toLocalDate().atTime(23, 59, 59).atZone(start.zone).toInstant().toEpochMilli()
            else -> dateTime.atZone(start.zone).toInstant().toEpochMilli()
        }
    }
}

/** 重复规则展开：在事件所在时区的墙钟时间上按序产生每次出现的本地开始时间。 */
object IcsRecurrence {
    fun expand(start: LocalDateTime, rule: RecurrenceRule): Sequence<LocalDateTime> = sequence {
        val time = start.toLocalTime()
        when (rule.freq) {
            RecurrenceFreq.DAILY -> {
                var k = 0L
                while (true) {
                    val candidate = start.plusDays(k * rule.interval)
                    if (rule.byDay.isEmpty() || candidate.dayOfWeek in rule.byDay) yield(candidate)
                    k++
                }
            }
            RecurrenceFreq.WEEKLY -> {
                val days = (if (rule.byDay.isEmpty()) listOf(start.dayOfWeek) else rule.byDay.toList()).sortedBy { it.value }
                val firstMonday = start.toLocalDate().minusDays((start.dayOfWeek.value - 1).toLong())
                var week = 0L
                while (true) {
                    val monday = firstMonday.plusWeeks(week * rule.interval)
                    for (day in days) {
                        val candidate = monday.plusDays((day.value - 1).toLong()).atTime(time)
                        if (!candidate.isBefore(start)) yield(candidate)
                    }
                    week++
                }
            }
            RecurrenceFreq.MONTHLY -> {
                var k = 0L
                while (true) {
                    val month = YearMonth.from(start).plusMonths(k * rule.interval)
                    if (rule.byDay.isEmpty()) {
                        if (start.dayOfMonth <= month.lengthOfMonth()) yield(month.atDay(start.dayOfMonth).atTime(time))
                    } else {
                        for (d in 1..month.lengthOfMonth()) {
                            val date = month.atDay(d)
                            if (date.dayOfWeek in rule.byDay) {
                                val candidate = date.atTime(time)
                                if (!candidate.isBefore(start)) yield(candidate)
                            }
                        }
                    }
                    k++
                }
            }
            RecurrenceFreq.YEARLY -> {
                var k = 0L
                while (true) {
                    val year = YearMonth.of((start.year + k * rule.interval).toInt(), start.monthValue)
                    if (start.dayOfMonth <= year.lengthOfMonth()) yield(year.atDay(start.dayOfMonth).atTime(time))
                    k++
                }
            }
        }
    }
}

/**
 * 一次具体的日程出现。[key] 由来源、UID 与该次出现的原始开始时刻组成，
 * 订阅刷新后只要事件未被改动就保持不变，调课覆盖仍沿用被覆盖的原始时刻。
 */
data class Occurrence(
    val sourceId: String, val uid: String, val key: String, val title: String,
    val startMs: Long, val endMs: Long, val allDay: Boolean
)

data class IcsEvent(
    val uid: String, val summary: String?, val start: IcsMoment, val end: IcsMoment?, val duration: Duration?,
    val rule: RecurrenceRule?, val exdates: List<IcsMoment>, val recurrenceId: IcsMoment?, val cancelled: Boolean
) {
    val title: String get() = summary ?: DEFAULT_TITLE

    /** 单次持续毫秒数：优先 DTEND，其次 DURATION，全天默认一天，否则为零。 */
    fun spanMs(): Long = when {
        end != null -> (end.epochMs - start.epochMs).coerceAtLeast(0L)
        duration != null -> duration.toMillis().coerceAtLeast(0L)
        start.allDay -> DAY_MS
        else -> 0L
    }

    fun isExcluded(local: LocalDateTime, startMs: Long): Boolean = exdates.any {
        if (it.allDay) it.local.toLocalDate() == local.toLocalDate() else it.epochMs == startMs
    }

    companion object {
        const val DEFAULT_TITLE = "未命名日程"
        const val DAY_MS = 86_400_000L
    }
}

class IcsCalendar(val events: List<IcsEvent>, val warnings: List<String>) {
    /**
     * 展开与 `[fromMs, toMs]` 有交集的出现项，按开始时刻排序。
     * 展开只针对被查询的区间；单个事件迭代次数有上限，防止病态规则拖垮查询。
     */
    fun occurrences(sourceId: String, fromMs: Long, toMs: Long, limit: Int = MAX_OCCURRENCES): List<Occurrence> {
        val bases = events.filter { it.recurrenceId == null && !it.cancelled }
        val changes = events.filter { it.recurrenceId != null }
        val overrideKeys = changes.groupBy({ it.uid }, { it.recurrenceId!!.epochMs }).mapValues { it.value.toSet() }
        val baseByUid = bases.associateBy { it.uid }
        val result = mutableListOf<Occurrence>()
        fun add(o: Occurrence) {
            if (o.endMs >= fromMs && o.startMs <= toMs) result.add(o)
        }
        for (event in bases) {
            val suppressed = overrideKeys[event.uid].orEmpty()
            val span = event.spanMs()
            val rule = event.rule
            val starts = if (rule == null) sequenceOf(event.start.local) else IcsRecurrence.expand(event.start.local, rule)
            val countLimit = rule?.count
            val untilMs = rule?.untilMs
            var emitted = 0
            var iterations = 0
            for (local in starts) {
                if (++iterations > MAX_ITERATIONS) break
                if (countLimit != null && emitted >= countLimit) break
                emitted++
                val startMs = local.atZone(event.start.zone).toInstant().toEpochMilli()
                if (untilMs != null && startMs > untilMs) break
                if (startMs > toMs) break
                if (startMs in suppressed || event.isExcluded(local, startMs)) continue
                add(Occurrence(sourceId, event.uid, keyOf(sourceId, event.uid, startMs), event.title, startMs, startMs + span, event.start.allDay))
            }
        }
        for (change in changes) {
            if (change.cancelled) continue
            val base = baseByUid[change.uid]
            val explicit = change.end != null || change.duration != null
            val span = if (explicit) change.spanMs() else (base?.spanMs() ?: change.spanMs())
            val startMs = change.start.epochMs
            add(Occurrence(sourceId, change.uid, keyOf(sourceId, change.uid, change.recurrenceId!!.epochMs),
                change.summary ?: base?.title ?: IcsEvent.DEFAULT_TITLE, startMs, startMs + span, change.start.allDay))
        }
        return result.sortedWith(compareBy<Occurrence>({ it.startMs }, { it.key })).take(limit)
    }

    companion object {
        const val MAX_OCCURRENCES = 400
        const val MAX_ITERATIONS = 100_000
        fun keyOf(sourceId: String, uid: String, originalStartMs: Long) = "$sourceId|$uid|$originalStartMs"
    }
}

object IcsParser {
    /**
     * 解析 ICS 文本。不是 ICS 或没有任何可识别事件时抛出 [IcsParseException]；
     * 单个事件无法识别或规则不受支持时降级并记入 [IcsCalendar.warnings]，不影响其余事件。
     * 浮动时间与无法识别的 TZID 使用 [deviceZone]。
     */
    fun parse(text: String, deviceZone: ZoneId = ZoneId.systemDefault()): IcsCalendar {
        val lines = IcsLines.unfold(text)
        if (lines.none { it.trim().equals("BEGIN:VCALENDAR", true) }) throw IcsParseException("不是有效的 ICS 日程文件")
        val warnings = mutableListOf<String>()
        val events = mutableListOf<IcsEvent>()
        var current: MutableList<IcsProperty>? = null
        var nested = 0
        for (line in lines) {
            val property = IcsLines.parse(line) ?: continue
            val active = current
            if (property.name == "BEGIN") {
                if (active == null) {
                    if (property.value.trim().equals("VEVENT", true)) { current = mutableListOf(); nested = 0 }
                } else nested++
            } else if (property.name == "END") {
                if (active != null) {
                    if (nested > 0) nested--
                    else if (property.value.trim().equals("VEVENT", true)) {
                        buildEvent(active, deviceZone, warnings)?.let { events.add(it) }
                        current = null
                    }
                }
            } else if (active != null && nested == 0) active.add(property)
        }
        if (events.isEmpty()) throw IcsParseException("未找到任何日程事件")
        return IcsCalendar(events, warnings)
    }

    private fun buildEvent(props: List<IcsProperty>, device: ZoneId, warnings: MutableList<String>): IcsEvent? {
        fun first(name: String): IcsProperty? = props.firstOrNull { it.name == name }
        val startProperty = first("DTSTART")
        if (startProperty == null) { warnings.add("忽略缺少 DTSTART 的事件"); return null }
        val start = IcsTime.moment(startProperty, device)
        if (start == null) { warnings.add("忽略开始时间无法解析的事件：${startProperty.value}"); return null }
        val summary = first("SUMMARY")?.let { IcsLines.unescape(it.value).trim() }?.takeIf { it.isNotEmpty() }
        val title = summary ?: IcsEvent.DEFAULT_TITLE
        val uid = first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() } ?: "$title@${start.epochMs}"
        val end = first("DTEND")?.let { IcsTime.moment(it, device) }
        val duration = first("DURATION")?.let { IcsTime.duration(it.value) }
        val recurrenceId = first("RECURRENCE-ID")?.let { IcsTime.moment(it, device) }
        val cancelled = first("STATUS")?.value?.trim().equals("CANCELLED", true)
        val exdates = props.filter { it.name == "EXDATE" }.flatMap { p ->
            p.value.split(',').mapNotNull { v -> IcsTime.moment(p.copy(value = v), device) }
        }
        var rule: RecurrenceRule? = null
        val ruleProperty = first("RRULE")
        if (ruleProperty != null && recurrenceId == null) {
            val parsed = IcsTime.rule(ruleProperty.value, start)
            rule = parsed.rule
            if (parsed.unsupported != null) warnings.add("事件「$title」${parsed.unsupported}，仅保留首次出现")
        }
        return IcsEvent(uid, summary, start, end, duration, rule, exdates, recurrenceId, cancelled)
    }
}
