package io.github.kamiiroawase.nexttime.rrule

import io.github.kamiiroawase.nexttime.RepeatUnit
import io.github.kamiiroawase.nexttime.Schedule
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** ISO 星期编号（1..7）到 RRULE 两字母星期代码的映射，下标 = 编号 - 1 */
private val weekDayCodes = arrayOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/** 本库重复单位到 RRULE FREQ 的对应 */
private val freqByUnit =
    mapOf(
        RepeatUnit.DAY to "DAILY",
        RepeatUnit.WEEK to "WEEKLY",
        RepeatUnit.MONTH to "MONTHLY",
        RepeatUnit.YEAR to "YEARLY",
        RepeatUnit.HOUR to "HOURLY",
        RepeatUnit.MINUTE to "MINUTELY",
    )

/**
 * RRULE 与 [Schedule] 互转失败：规则串语法错误、落在无损子集之外（如 UNTIL、
 * BYSETPOS），或语义在两个体系间无法无损表达（如农历、锚点日 2/29 的年重复）。
 * 继承 [IllegalArgumentException]——互转函数的入参合法性问题的惯用类型。
 */
public class RruleConversionException(
    message: String,
) : IllegalArgumentException(message)

/**
 * 把日程的重复规则导出为 RFC 5545 RRULE 属性值（如 `FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH`）。
 * 不可无损表达时抛 [RruleConversionException]（子集说明见下），null 语义用 [toRruleOrNull]。
 *
 * 规则串只描述重复规则本身；锚点日期时刻（iCalendar 的 DTSTART）由调用方另行
 * 保存——本库的锚点即日程的目标日与时刻。输出为确定性形态：参数序 FREQ、
 * INTERVAL（=1 省略）、BYDAY/BYMONTHDAY（集合值按月内时间序）、COUNT，便于
 * golden 测试与缓存键。
 *
 * **无损子集（导出）**：
 * - 天/小时/分钟重复：`FREQ=DAILY/HOURLY/MINUTELY`——小时/分钟为真实时长格点，
 *   与 RFC time-based 惯例一致
 * - 周重复：BYDAY = 星期集合；未配选择器时 BYDAY = 锚点日星期（每 interval 周
 *   在锚点星期出现，与单元素选择器同构）
 * - 月重复：**必须配选择器**——`monthDays`（须 clampMonthDays = false 或收缩不可达：
 *   全部条目落在 1..28 ∪ -28..-1，任何月都含 28 天）或 `weekdays`（±
 *   `weekdayOrdinals`，BYDAY 的序数形态）。无选择器的月重复是锚点日收缩回弹
 *   （1/31 → 2/28 → 3/31），RFC 的 BYMONTHDAY 缺日跳过语义无法表达
 * - 年重复：`FREQ=YEARLY`——锚点日为 2/29 时抛（本库平年收缩到 2/28、RFC 跳过
 *   该年，语义分歧）
 * - `repeatCount` → `COUNT`；lunar、不重复日程、targetDay 未选不可导出（锚点
 *   星期无从取）
 */
public fun Schedule.toRrule(): String {
    if (lunar) throw unsupported("lunar repeats have no RFC 5545 representation")
    if (repeatInterval <= 0 || repeatUnit == RepeatUnit.NONE) {
        throw unsupported("non-repeating schedules carry no RRULE (a one-shot event is DTSTART alone)")
    }
    val freq =
        freqByUnit[repeatUnit]
            ?: throw unsupported("repeatUnit $repeatUnit has no RRULE frequency counterpart")

    val parts = mutableListOf("FREQ=$freq")
    if (repeatInterval > 1) parts += "INTERVAL=$repeatInterval"

    when (repeatUnit) {
        RepeatUnit.WEEK -> {
            parts +=
                "BYDAY=" +
                (
                    if (weekdays.isEmpty()) {
                        // 无选择器周重复 = 每 interval 周的锚点星期，同构于单元素选择器；
                        // 未选目标日时锚点星期无从取
                        listOf(anchorWeekday().dayOfWeekCode())
                    } else {
                        weekdays.sorted().map { it.dayOfWeekCode() }
                    }
                ).joinToString(",")
        }

        RepeatUnit.MONTH -> {
            if (monthDays.isNotEmpty()) {
                // clamp=true 且有条目可能收缩（正数 > 28 或负数绝对值 > 28）时语义不可表达
                if (clampMonthDays && monthDays.any { it > 28 || it < -28 }) {
                    throw unsupported(
                        "clampMonthDays = true shrinks missing days to the month end, which RFC 5545 BYMONTHDAY " +
                            "(skip semantics) cannot express; exportable only when every entry is within 1..28 or -28..-1",
                    )
                }
                parts += "BYMONTHDAY=" + monthDays.canonicalMonthDays().joinToString(",")
            } else if (weekdays.isNotEmpty()) {
                parts += "BYDAY=" + canonicalByDay().joinToString(",")
            } else {
                throw unsupported(
                    "month repeats without selectors use anchor-day shrink/rebound (1/31 → 2/28 → 3/31), " +
                        "which RFC 5545 BYMONTHDAY (skip semantics) cannot express; use monthDays for RFC semantics",
                )
            }
        }

        RepeatUnit.YEAR -> {
            if (anchorDate().let { it.month.ordinal + 1 == 2 && it.day == 29 }) {
                throw unsupported(
                    "a Feb 29 anchor shrinks to Feb 28 in non-leap years here while RFC 5545 skips the year — " +
                        "the semantics diverge, no lossless RRULE exists",
                )
            }
        }

        else -> {
            // 天/小时/分钟无 BY 部分
        }
    }

    if (repeatCount > 0) parts += "COUNT=$repeatCount"
    return parts.joinToString(";")
}

/** [toRrule] 的 null 语义版本：不可无损表达返回 null，不抛。 */
public fun Schedule.toRruleOrNull(): String? =
    try {
        toRrule()
    } catch (e: RruleConversionException) {
        null
    }

/**
 * 解析 RFC 5545 RRULE 属性值并配合锚点（iCalendar 的 DTSTART）构造 [Schedule]：
 * `targetDay` 取锚点日期的 UTC 毫秒、时刻取锚点时分秒，重复规则映射为对应字段。
 * 语法错误、子集外构造与语义不可无损表达均抛 [RruleConversionException]——含
 * 锚点不是规则日等本库构造校验的转译。
 *
 * **无损子集（导入）**：
 * - `FREQ=DAILY/HOURLY/MINUTELY`（不得带 BYDAY/BYMONTHDAY）；`FREQ=WEEKLY`
 *   （BYDAY 可省——RFC 缺省即 DTSTART 星期；不得带序数、BYMONTHDAY）；
 *   `FREQ=MONTHLY`（无 BY* 时按 RFC「DTSTART 日号、缺日跳过」映射为
 *   `monthDays = {锚点日}`；BYMONTHDAY 与 BYDAY 互斥——对应本库互斥选择器；
 *   带序数 BYDAY 须为完整笛卡尔积——共享单一序数（1MO,1TU）或每星期带全部
 *   序数（1MO,-1MO,1TU,-1TU），混合序数（MO,1TU）与非完整积（1MO,-1TU）
 *   拒绝：RFC 条目独立展开，非完整积导入会静默扩集）；
 *   `FREQ=YEARLY`（不得带任何 BY*；锚点 2/29 的语义分歧见 [toRrule]）
 * - `COUNT` 接受；`UNTIL` 拒绝——本库的 until 是 nextTarget 的查询期上限而非
 *   日程状态，持久化结束时间请在调用方保存并与查询同传
 * - `WKST=MO`（缺省值）接受、其他拒绝——本库周格点以周一锚定
 * - `BYSECOND/BYMINUTE/BYHOUR/BYMONTH/BYWEEKNO/BYYEARDAY/BYSETPOS` 拒绝
 *
 * `INTERVAL` 缺省为 1；允许携带 `RRULE:` 前缀；键重复、值非法、未知键均拒绝
 * （不静默忽略——静默丢规则比大声失败更危险）。
 */
public fun parseRrule(
    rrule: String,
    anchor: LocalDateTime,
): Schedule {
    val parts = parseParts(rrule)

    val freq = parts.removeValue("FREQ") ?: throw syntax("FREQ is required")
    val interval =
        when (val value = parts.removeValue("INTERVAL")) {
            null -> 1
            else -> value.toIntOrNull()?.takeIf { it >= 1 } ?: throw syntax("INTERVAL must be a positive integer, got: $value")
        }
    val count =
        when (val value = parts.removeValue("COUNT")) {
            null -> 0
            else -> value.toIntOrNull()?.takeIf { it >= 1 } ?: throw syntax("COUNT must be a positive integer, got: $value")
        }
    if (parts.contains("UNTIL")) {
        throw unsupported(
            "UNTIL is rejected: until is a per-query bound of nextTarget in this library, not persisted schedule " +
                "state — keep the end time on your side and pass it as the until argument",
        )
    }
    when (val wkst = parts.removeValue("WKST")) {
        null, "MO" -> {}

        else -> {
            throw unsupported("WKST must be MO (the library's week grid anchors on Monday), got: $wkst")
        }
    }
    for (key in listOf("BYSECOND", "BYMINUTE", "BYHOUR", "BYMONTH", "BYWEEKNO", "BYYEARDAY", "BYSETPOS")) {
        if (parts.contains(key)) throw unsupported("$key is outside the lossless subset")
    }

    val byMonthDay = parts.removeValue("BYMONTHDAY")?.parseMonthDays()
    val byDay = parts.removeValue("BYDAY")?.parseByDay()
    parts.requireEmpty()

    val unit =
        when (freq) {
            "DAILY" -> RepeatUnit.DAY
            "WEEKLY" -> RepeatUnit.WEEK
            "MONTHLY" -> RepeatUnit.MONTH
            "YEARLY" -> RepeatUnit.YEAR
            "HOURLY" -> RepeatUnit.HOUR
            "MINUTELY" -> RepeatUnit.MINUTE
            else -> throw syntax("FREQ must be one of DAILY/WEEKLY/MONTHLY/YEARLY/HOURLY/MINUTELY, got: $freq")
        }

    var weekdays: Set<Int> = emptySet()
    var weekdayOrdinals: Set<Int> = emptySet()
    var monthDays: Set<Int> = emptySet()

    val anchorDate = anchor.date
    when (unit) {
        RepeatUnit.DAY, RepeatUnit.HOUR, RepeatUnit.MINUTE -> {
            if (byDay != null || byMonthDay != null) {
                throw unsupported("BYDAY/BYMONTHDAY are only valid with WEEKLY/MONTHLY/YEARLY frequencies")
            }
        }

        RepeatUnit.WEEK -> {
            if (byMonthDay != null) throw unsupported("BYMONTHDAY is only valid with MONTHLY frequencies")
            // RFC 缺省 BYDAY = DTSTART 的星期
            weekdays =
                if (byDay == null) {
                    setOf(anchorDate.dayOfWeek.isoDayNumber)
                } else {
                    if (byDay.any { it.second != null }) {
                        throw unsupported("ordinal BYDAY (like 2TU) is only valid with MONTHLY frequencies")
                    }
                    byDay.map { it.first.codeToWeekday() }.toSet()
                }
        }

        RepeatUnit.MONTH -> {
            if (byDay != null && byMonthDay != null) {
                throw unsupported("BYDAY and BYMONTHDAY together are outside the subset (the library's selectors are exclusive)")
            }
            if (byMonthDay != null) {
                // RFC BYMONTHDAY 的缺日跳过语义 ↔ clampMonthDays = false
                monthDays = byMonthDay.toSet()
            } else if (byDay != null) {
                weekdays = byDay.map { it.first.codeToWeekday() }.toSet()
                weekdayOrdinals = byDay.mapNotNull { it.second }.toSet()
                // RFC BYDAY 的条目各自独立展开，本库选择器却是 weekdays × weekdayOrdinals 的
                // 笛卡尔积：仅完整积形态可无损导入——全无序数（积退化为纯星期集）、
                // 共享单一序数（1MO,1TU）或每星期都带全部序数（1MO,-1MO,1TU,-1TU）；
                // 其余（混合序数 MO,1TU、缺组合 1MO,-1TU）导入会把 RFC 的出现集
                // 静默扩成完整积，拒绝
                if (weekdayOrdinals.isNotEmpty()) {
                    if (byDay.any { it.second == null }) {
                        throw unsupported(
                            "mixed ordinal and ordinal-less BYDAY entries (like MO,1TU) do not form the cartesian " +
                                "product weekdays x ordinals that the library's selectors represent",
                        )
                    }
                    if (byDay.toSet().size != weekdays.size * weekdayOrdinals.size) {
                        throw unsupported(
                            "BYDAY entries expand independently in RFC 5545 while the library's selectors are the " +
                                "cartesian product weekdays x ordinals; only complete products (a shared single " +
                                "ordinal like 1MO,1TU, or every weekday carrying every ordinal) map losslessly",
                        )
                    }
                }
            } else {
                // 无 BY*：RFC 语义为「DTSTART 日号、缺日跳过」——精确映射为单元素选择器
                monthDays = setOf(anchorDate.day)
            }
        }

        RepeatUnit.YEAR -> {
            if (byDay != null || byMonthDay != null) {
                throw unsupported("BYDAY/BYMONTHDAY with YEARLY are outside the subset (the library's YEAR repeats take no selectors)")
            }
            if (anchorDate.month.ordinal + 1 == 2 && anchorDate.day == 29) {
                throw unsupported(
                    "a Feb 29 DTSTART shrinks to Feb 28 in non-leap years here while RFC 5545 skips the year — " +
                        "the semantics diverge, no lossless mapping exists",
                )
            }
        }
    }

    return try {
        Schedule(
            lunar = false,
            leapCount = false,
            targetDay = anchorDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            targetHour = anchor.hour,
            targetMinute = anchor.minute,
            targetSecond = anchor.second,
            repeatInterval = interval,
            repeatUnit = unit,
            weekdays = weekdays,
            weekdayOrdinals = weekdayOrdinals,
            monthDays = monthDays,
            clampMonthDays = false,
            repeatCount = count,
        )
    } catch (e: IllegalArgumentException) {
        // 本库构造校验（多为锚点不是规则日、字段组合非法）转译为互转异常
        throw RruleConversionException("RRULE does not map onto a valid Schedule with this anchor: ${e.message}")
    }
}

/** 解析后的 RRULE 参数表（键 → 值）；重复键在解析期拒绝 */
private class RruleParts(
    private val values: MutableMap<String, String>,
) {
    fun contains(key: String): Boolean = key in values

    fun removeValue(key: String): String? = values.remove(key)

    fun requireEmpty() {
        if (values.isNotEmpty()) {
            throw syntax("unknown rule part: ${values.keys.first()}")
        }
    }
}

private fun parseParts(rrule: String): RruleParts {
    val body =
        rrule
            .trim()
            .removePrefix("RRULE:")
            .ifEmpty { throw syntax("empty RRULE") }
    val values = mutableMapOf<String, String>()
    for (part in body.split(';')) {
        if (part.isEmpty() || !part.contains('=')) throw syntax("malformed rule part: $part")
        val key = part.substringBefore('=')
        val value = part.substringAfter('=')
        if (value.isEmpty()) throw syntax("rule part $part carries no value")
        if (key in values) throw syntax("duplicate rule part: $key")
        values[key] = value
    }
    return RruleParts(values)
}

/** BYMONTHDAY 列表：-31..-1 ∪ 1..31，无 0 */
private fun String.parseMonthDays(): List<Int> =
    split(',').map { entry ->
        entry.toIntOrNull()?.takeIf { it in -31..-1 || it in 1..31 }
            ?: throw syntax("BYMONTHDAY entries must be within -31..-1 or 1..31, got: $entry")
    }

/** BYDAY 列表：[(星期代码, 序数?)]，序数 -53..-1 ∪ 1..53（更窄的 ±5 由 Schedule 构造校验兜底） */
private fun String.parseByDay(): List<Pair<String, Int?>> =
    split(',').map { entry ->
        val code = entry.takeLast(2)
        val ordinalText = entry.dropLast(2)
        val ordinal =
            if (ordinalText.isEmpty()) {
                null
            } else {
                ordinalText.toIntOrNull()?.takeIf { it in -53..-1 || it in 1..53 }
                    ?: throw syntax("BYDAY ordinal must be within -53..-1 or 1..53, got: $entry")
            }
        if (code !in weekDayCodes) throw syntax("BYDAY day code must be one of ${weekDayCodes.toList()}, got: $entry")
        code to ordinal
    }

/** 月内日选择集的确定性输出序：正数升序在前、负数按时间序（绝对值降序）在后 */
private fun Set<Int>.canonicalMonthDays(): List<Int> = sortedWith(compareBy({ it <= 0 }, { it }))

/** 月重复 BYDAY（weekdays × weekdayOrdinals 笛卡尔积）的确定性输出序：星期升序，序数按时间序 */
private fun Schedule.canonicalByDay(): List<String> {
    val entries = mutableListOf<String>()
    for (weekday in weekdays.sorted()) {
        if (weekdayOrdinals.isEmpty()) {
            entries += weekday.dayOfWeekCode()
        } else {
            // 序数按时间序：正数升序在前，负数按绝对值降序在后（倒数第 2 早于
            // 倒数第 1）——负数区间内升序恰为时间序，与 canonicalMonthDays 同构
            val chronological =
                weekdayOrdinals.sortedWith(
                    compareBy(
                        { it <= 0 },
                        { it },
                    ),
                )
            for (ordinal in chronological) entries += "$ordinal${weekday.dayOfWeekCode()}"
        }
    }
    return entries
}

private fun Int.dayOfWeekCode(): String = weekDayCodes[this - 1]

private fun String.codeToWeekday(): Int {
    val index = weekDayCodes.indexOf(this)
    if (index < 0) throw syntax("BYDAY day code must be one of ${weekDayCodes.toList()}, got: $this")
    return index + 1
}

/** 锚点日期（targetDay 的 UTC 日期部分；未选在此抛，晚于其余入参检查也无妨——同属导出失败） */
private fun Schedule.anchorDate(): LocalDate {
    if (targetDay == -1L) {
        throw unsupported("an unset targetDay leaves the anchor weekday unknown")
    }
    return Instant.fromEpochMilliseconds(targetDay).toLocalDateTime(TimeZone.UTC).date
}

private fun Schedule.anchorWeekday(): Int = anchorDate().dayOfWeek.isoDayNumber

private fun unsupported(message: String): RruleConversionException = RruleConversionException("Outside the lossless RRULE subset: $message")

private fun syntax(message: String): RruleConversionException = RruleConversionException("Malformed RRULE: $message")
