package io.github.kamiiroawase.nexttime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * [Schedule.targetDay] 的支持范围：UTC 解析出的日期须落在 0001-01-01 至
 * 9999-12-31，与 kotlinx-datetime 的 [LocalDate] 年份范围及农历可靠年表对齐；
 * 上界取 9999-12-31 当天日末（等效 10000-01-01 零点减一毫秒），使当天任意
 * 时刻的毫秒值都在界内。
 */
private val MIN_TARGET_DAY_MILLIS =
    LocalDate(1, 1, 1)
        .atStartOfDayIn(TimeZone.UTC)
        .toEpochMilliseconds()

private val MAX_TARGET_DAY_MILLIS =
    LocalDate(9999, 12, 31)
        .atStartOfDayIn(TimeZone.UTC)
        .toEpochMilliseconds() + 86_399_999L

/**
 * 农历月/年重复的锚点禁区（UTC 日期 1582-10-05..14 当天任意毫秒）：儒略→格里
 * 高利历换算缺口，农历历表（tyme）不含这十天，锚点无法换算为农历月日，构造期
 * 拒绝。与推算路径的触发条件精确一致：仅 lunar + interval > 0 + 月/年单位的
 * 日程须做农历换算，天/周/小时/分钟重复与非农历日程不受限。
 */
private val JULIAN_GREGORIAN_GAP_START_MILLIS =
    LocalDate(1582, 10, 5)
        .atStartOfDayIn(TimeZone.UTC)
        .toEpochMilliseconds()

private val JULIAN_GREGORIAN_GAP_END_MILLIS =
    LocalDate(1582, 10, 14)
        .atStartOfDayIn(TimeZone.UTC)
        .toEpochMilliseconds() + 86_399_999L

/**
 * 倒计时日程的目标时间描述：锚点目标日、时刻与重复规则。
 *
 * 构造时校验全部字段，非法取值抛 [IllegalArgumentException]：
 * [targetDay] 仅允许 -1（未选）或能解析到 0001-01-01..9999-12-31（UTC 日期）
 * 的毫秒值——0 表示 1970-01-01 当天、负毫秒表示 1970 年之前，均合法；时分秒
 * 仅允许 -1（未选）或各自合法区间，且须**全选或全不选**——部分选择（如只设
 * 时）曾静默按零点吞掉已选字段，现构造期拒绝；[repeatInterval] 须在
 * 0..[MAX_REPEAT_INTERVAL]；[repeatUnit] 须为 [RepeatUnit] 常量之一。农历月/年
 * 重复（lunar 且 interval > 0 且单位为月/年）的 [targetDay] 另不得落在
 * 1582-10-05..14（UTC）——儒略→格里高利历换算缺口，农历历表不含这十天，
 * 锚点无法换算为农历月日。
 *
 * 选择器（[weekdays] / [weekdayOrdinals] / [monthDays]）非空时另要求：重复规则
 * 激活（interval > 0 且单位匹配）、非农历（选择器是公历格点概念）、锚点日自身
 * 是规则日（锚点恒为第一次出现）；[targetDay] 未选时跳过锚点隶属检查。
 *
 * @param lunar 目标日按农历解释：月/年重复沿农历推进，天/周重复与公历无异
 * @param leapCount 农历时闰月是否参与重复推算
 * @param targetDay 目标日 UTC 毫秒值，-1 表示未选；支持 0001-01-01 至 9999-12-31 的日期（1970 年前为负毫秒）
 * @param targetHour 目标时，-1 表示未选；三个时刻字段须全选或全不选（全不选整体按当天零点，部分选择构造期抛异常）
 * @param targetMinute 目标分，-1 表示未选
 * @param targetSecond 目标秒，-1 表示未选
 * @param repeatInterval 重复间隔，0 表示不重复
 * @param repeatUnit 重复单位，取 [RepeatUnit] 常量；[RepeatUnit.NONE] 时无论
 * [repeatInterval] 取值为何均按不重复处理（间隔被忽略，与 interval = 0 同效）
 * @param weekdays 星期选择器：取 [WeekDay] 常量（ISO：1=周一..7=周日），配周/月
 * 重复。周重复在锚点周起每 interval 周的选中星期各出现一次；月重复配
 * [weekdayOrdinals] 定位每月第 n 个选中星期。锚点日的星期须在集合内
 * @param weekdayOrdinals 月内序数（仅月重复 + [weekdays] 非空）：1..5 = 每月
 * 第 n 个、-1..-5 = 每月倒数第 n 个选中星期；空集 = 每月全部命中日。序数超界
 * （如第 5 个周二遇只有 4 个周二的月）该月跳过，无收缩
 * @param monthDays 月内日选择器（仅月重复，与 [weekdays] 互斥）：1..31 从月初、
 * -31..-1 从月末（-1 即每月最后一天）。正数日超出当月天数时按
 * [clampMonthDays] 决定跳过或收缩。锚点日的日号须在当月解析结果内
 * @param clampMonthDays [monthDays] 的正数日超出当月天数（如 30 遇 2 月）时：
 * false = 该月跳过（iCalendar BYMONTHDAY 惯例，缺失日不出现）；true = 收缩到
 * 当月最接近的一天（正数溢出收到月末、负数溢出收到月初，对齐无选择器时的
 * 锚点日收缩语义），收缩后去重。空集时无效果
 * @param repeatCount 出现总次数上限（含锚点，RRULE COUNT 惯例），0 表示不限。
 * 耗尽后 nextTarget 返回 null（与 until 完结同形，两者先到者生效）、
 * previousTarget 不返回越限出现。仅对激活的重复规则有意义——不重复日程配
 * 正次数构造期拒绝
 */
public data class Schedule(
    public val lunar: Boolean = false,
    public val leapCount: Boolean = false,
    public val targetDay: Long = -1L,
    public val targetHour: Int = -1,
    public val targetMinute: Int = -1,
    public val targetSecond: Int = -1,
    public val repeatInterval: Int = 0,
    public val repeatUnit: Int = RepeatUnit.NONE,
    public val weekdays: Set<Int> = emptySet(),
    public val weekdayOrdinals: Set<Int> = emptySet(),
    public val monthDays: Set<Int> = emptySet(),
    public val clampMonthDays: Boolean = false,
    public val repeatCount: Int = 0,
) {
    init {
        require(targetDay == -1L || targetDay in MIN_TARGET_DAY_MILLIS..MAX_TARGET_DAY_MILLIS) {
            "targetDay must resolve to a UTC date within 0001-01-01..9999-12-31, or be -1 for unset, got: $targetDay"
        }
        require(targetHour in -1..23) { "targetHour must be in 0..23 or -1 for unset, got: $targetHour" }
        require(targetMinute in -1..59) { "targetMinute must be in 0..59 or -1 for unset, got: $targetMinute" }
        require(targetSecond in -1..59) { "targetSecond must be in 0..59 or -1 for unset, got: $targetSecond" }
        // 全选或全不选：部分选择（如只设时）曾静默按零点吞掉已选字段，改为构造期快速失败
        val timeFields = listOf(targetHour, targetMinute, targetSecond)
        require(timeFields.all { it == -1 } || timeFields.all { it >= 0 }) {
            "targetHour/targetMinute/targetSecond must be all set or all unset (-1); " +
                "set the missing ones to 0 explicitly, got: $targetHour/$targetMinute/$targetSecond"
        }
        require(repeatInterval in 0..MAX_REPEAT_INTERVAL) { "repeatInterval must be in 0..$MAX_REPEAT_INTERVAL, got: $repeatInterval" }
        require(repeatUnit in RepeatUnit.NONE..RepeatUnit.MINUTE) { "repeatUnit must be a RepeatUnit constant, got: $repeatUnit" }
        // 农历月/年重复须把锚点换算为农历月日：锚点落在 1582 历法缺口内时换算必
        // 失败（tyme 的 SolarDay 直接拒绝这十天），构造期快速拒绝而非推算期泄漏
        // tyme 的 IllegalArgumentException（illegal solar day）
        require(
            !(
                lunar && repeatInterval > 0 &&
                    (repeatUnit == RepeatUnit.MONTH || repeatUnit == RepeatUnit.YEAR) &&
                    targetDay in JULIAN_GREGORIAN_GAP_START_MILLIS..JULIAN_GREGORIAN_GAP_END_MILLIS
            ),
        ) {
            "lunar month/year repeat cannot anchor on 1582-10-05..14 (UTC), the Julian-to-Gregorian " +
                "calendar switch gap absent from the lunar calendar table, got targetDay: $targetDay"
        }
        // —— 选择器与次数上限的校验矩阵（详见类 KDoc）——
        val hasSelectors = weekdays.isNotEmpty() || weekdayOrdinals.isNotEmpty() || monthDays.isNotEmpty()
        if (hasSelectors) {
            // 选择器依附于激活的重复规则：不重复日程（含 unit = NONE 的间隔忽略路径）
            // 配选择器只会被静默吞掉，构造期拒绝
            require(repeatInterval > 0 && repeatUnit != RepeatUnit.NONE) {
                "selectors require an active repeat rule (repeatInterval > 0 and repeatUnit != NONE), " +
                    "got interval: $repeatInterval, unit: $repeatUnit"
            }
            // 星期/月内日是公历格点概念，农历月重复沿农历月序推进、无星期与月内日可言
            require(!lunar) { "weekday/month-day selectors are solar-only and cannot combine with lunar = true" }
        }
        if (weekdays.isNotEmpty()) {
            require(repeatUnit == RepeatUnit.WEEK || repeatUnit == RepeatUnit.MONTH) {
                "weekdays require repeatUnit WEEK or MONTH, got: $repeatUnit"
            }
            require(weekdays.all { it in WeekDay.MONDAY..WeekDay.SUNDAY }) {
                "weekdays must be ISO day numbers 1..7 (WeekDay.MONDAY..SUNDAY), got: $weekdays"
            }
        }
        if (weekdayOrdinals.isNotEmpty()) {
            require(repeatUnit == RepeatUnit.MONTH && weekdays.isNotEmpty()) {
                "weekdayOrdinals require repeatUnit MONTH together with a non-empty weekdays set, " +
                    "got unit: $repeatUnit, weekdays: $weekdays"
            }
            require(weekdayOrdinals.all { it in -5..-1 || it in 1..5 }) {
                "weekdayOrdinals must be nth or nth-from-last positions in -5..-1 or 1..5, got: $weekdayOrdinals"
            }
        }
        if (monthDays.isNotEmpty()) {
            require(repeatUnit == RepeatUnit.MONTH && weekdays.isEmpty()) {
                "monthDays require repeatUnit MONTH without weekdays (the two selectors are exclusive), " +
                    "got unit: $repeatUnit, weekdays: $weekdays"
            }
            require(monthDays.all { it in -31..-1 || it in 1..31 }) {
                "monthDays must be day-of-month positions from either end in -31..-1 or 1..31, got: $monthDays"
            }
        }
        if (repeatCount != 0) {
            require(repeatCount > 0) { "repeatCount must be >= 0 (0 = unlimited), got: $repeatCount" }
            // 不重复日程的「出现」只有锚点本身，配正次数只会制造假界限，构造期拒绝
            require(repeatInterval > 0 && repeatUnit != RepeatUnit.NONE) {
                "repeatCount requires an active repeat rule (repeatInterval > 0 and repeatUnit != NONE), " +
                    "got interval: $repeatInterval, unit: $repeatUnit"
            }
        }
        // 锚点隶属：锚点恒为第一次出现（anchor() 契约），锚点日必须是规则日本身——
        // 周重复要求锚点星期在集合内，月重复要求锚点日号在当月解析结果内（含 clamp
        // 模式，如 monthDays = {30} + clamp 时 2 月 28 日是合法锚点）。targetDay 未选
        // 无从验证，跳过
        if ((weekdays.isNotEmpty() || monthDays.isNotEmpty()) && targetDay != -1L) {
            val anchorDate = Instant.fromEpochMilliseconds(targetDay).toLocalDateTime(TimeZone.UTC).date
            val anchorIsRuleDay =
                if (repeatUnit == RepeatUnit.WEEK) {
                    anchorDate.dayOfWeek.isoDayNumber in weekdays
                } else {
                    anchorDate.day in
                        resolveMonthDays(
                            anchorDate.year,
                            anchorDate.month.ordinal + 1,
                            monthDays,
                            weekdays,
                            weekdayOrdinals,
                            clampMonthDays,
                        )
                }
            require(anchorIsRuleDay) {
                "the anchor date must itself be a rule day (the anchor is contractually the first " +
                    "occurrence): WEEK repeat requires the anchor weekday in weekdays, MONTH repeat " +
                    "requires the anchor day-of-month in the resolved month days, got anchor: $anchorDate, " +
                    "weekdays: $weekdays, weekdayOrdinals: $weekdayOrdinals, monthDays: $monthDays"
            }
        }
    }

    public companion object {
        /**
         * [Schedule.repeatInterval] 的上界：任意重复单位取上界时仍可在构造期
         * 完成全部校验；推算中组合出的日期一旦越出支持范围（0001-01-01..
         * 9999-12-31，公历与农历统一）立即抛 [IllegalStateException]。
         */
        public const val MAX_REPEAT_INTERVAL: Int = 100_000
    }
}

/**
 * [Schedule.repeatUnit] 的取值常量。天/周/月/年为日历单位——出现按「日期 + 时刻 +
 * 时区」组合（钟面格点），跨夏令时缺口顺延、重叠取较早；小时/分钟为时间单位——
 * 出现 = 锚点 + 步数×间隔（真实时长格点），跨夏令时本地时刻随真实间隔漂移。
 * 与 ISO 8601 划分 date-based / time-based 的惯例一致。
 */
public object RepeatUnit {
    /** 不重复 */
    public const val NONE: Int = 0

    /** 天重复 */
    public const val DAY: Int = 1

    /** 周重复 */
    public const val WEEK: Int = 2

    /** 月重复 */
    public const val MONTH: Int = 3

    /** 年重复 */
    public const val YEAR: Int = 4

    /** 小时重复（真实时长格点，不受 0001..9999 日期界守护约束） */
    public const val HOUR: Int = 5

    /** 分钟重复（真实时长格点，不受 0001..9999 日期界守护约束） */
    public const val MINUTE: Int = 6
}

/**
 * [Schedule.weekdays] 的取值常量：ISO 8601 星期编号，1 = 周一 .. 7 = 周日。
 * 周格点以周一为一周之始（锚点周 = 锚点日所在的 ISO 周）。
 */
public object WeekDay {
    /** 周一 */
    public const val MONDAY: Int = 1

    /** 周二 */
    public const val TUESDAY: Int = 2

    /** 周三 */
    public const val WEDNESDAY: Int = 3

    /** 周四 */
    public const val THURSDAY: Int = 4

    /** 周五 */
    public const val FRIDAY: Int = 5

    /** 周六 */
    public const val SATURDAY: Int = 6

    /** 周日 */
    public const val SUNDAY: Int = 7
}

/** [Schedule.targetDay] 的 UTC 日期部分（构造期已保证范围合法，调用方须先排除未选） */
internal fun Schedule.targetDate(): LocalDate = Instant.fromEpochMilliseconds(targetDay).toLocalDateTime(TimeZone.UTC).date

/**
 * 解析公历 (year, monthNumber) 月的选择器命中日，升序去重返回；构造期锚点隶属
 * 检查与月选择器推算路径共用。
 *
 * - [weekdays] 非空（月重复模式）：[weekdayOrdinals] 为空时返回当月全部命中
 *   星期；非空时取笛卡尔积——正序数 n = 当月第 n 个命中星期、负序数 = 倒数
 *   第 n 个，序数超界该组合跳过（无收缩）。纯整型模算术定位，不逐日构造日期
 * - 否则（[monthDays] 模式）：正数日超界按 [clampMonthDays] 跳过或收缩到最近的
 *   一端（正数收到月末、负数收到月初），负数日同样受 clamp 约束；收缩后经
 *   [MutableSet] 去重
 */
internal fun resolveMonthDays(
    year: Int,
    monthNumber: Int,
    monthDays: Set<Int>,
    weekdays: Set<Int>,
    weekdayOrdinals: Set<Int>,
    clampMonthDays: Boolean,
): List<Int> {
    val yearMonth = YearMonth(year, monthNumber)
    val length = yearMonth.numberOfDays
    val days = mutableSetOf<Int>()

    if (weekdays.isNotEmpty()) {
        // 月首的 ISO 星期：命中星期 w 在当月的首个日号 = 1 + 偏移；纯模算术
        val firstDow = yearMonth.firstDay.dayOfWeek.isoDayNumber
        val lastDow = ((firstDow - 1 + length - 1) % 7) + 1
        for (weekday in weekdays) {
            val first = 1 + (weekday - firstDow + 7) % 7
            val last = length - (lastDow - weekday + 7) % 7
            if (weekdayOrdinals.isEmpty()) {
                var day = first
                while (day <= length) {
                    days += day
                    day += 7
                }
            } else {
                for (ordinal in weekdayOrdinals) {
                    val day =
                        if (ordinal > 0) {
                            first + 7 * (ordinal - 1)
                        } else {
                            last - 7 * (-ordinal - 1)
                        }
                    // 序数超界（第 5 个周二遇只有 4 个周二的月）跳过，无收缩
                    if (day in 1..length) days += day
                }
            }
        }
    } else {
        for (monthDay in monthDays) {
            val day =
                if (monthDay > 0) {
                    when {
                        monthDay <= length -> monthDay
                        clampMonthDays -> length
                        else -> continue
                    }
                } else {
                    val fromEnd = length + monthDay + 1
                    when {
                        fromEnd >= 1 -> fromEnd
                        clampMonthDays -> 1
                        else -> continue
                    }
                }
            days += day
        }
    }

    return days.sorted()
}
