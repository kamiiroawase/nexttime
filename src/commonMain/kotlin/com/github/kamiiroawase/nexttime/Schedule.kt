package com.github.kamiiroawase.nexttime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

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
 * 倒计时日程的目标时间描述：锚点目标日、时刻与重复规则。
 *
 * 构造时校验全部字段，非法取值抛 [IllegalArgumentException]：
 * [targetDay] 仅允许 -1（未选）或能解析到 0001-01-01..9999-12-31（UTC 日期）
 * 的毫秒值——0 表示 1970-01-01 当天、负毫秒表示 1970 年之前，均合法；时分秒
 * 仅允许 -1（未选）或各自合法区间（-1 以外没有「未选」语义，如 0 与负数都会
 * 被拒绝）；[repeatInterval] 须在 0..[MAX_REPEAT_INTERVAL]；[repeatUnit] 须为
 * [RepeatUnit] 常量之一。
 *
 * @param lunar 目标日按农历解释：月/年重复沿农历推进，天/周/小时/分钟重复与公历无异
 * @param leapCount 农历时闰月是否参与重复推算
 * @param targetDay 目标日 UTC 毫秒值，-1 表示未选；支持 0001-01-01 至 9999-12-31 的日期（1970 年前为负毫秒）
 * @param targetHour 目标时，-1 表示未选（时分秒任一未选按当天零点）
 * @param targetMinute 目标分，-1 表示未选
 * @param targetSecond 目标秒，-1 表示未选
 * @param repeatInterval 重复间隔，0 表示不重复
 * @param repeatUnit 重复单位，取 [RepeatUnit] 常量
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
) {
    init {
        require(targetDay == -1L || targetDay in MIN_TARGET_DAY_MILLIS..MAX_TARGET_DAY_MILLIS) {
            "targetDay must resolve to a UTC date within 0001-01-01..9999-12-31, or be -1 for unset, got: $targetDay"
        }
        require(targetHour in -1..23) { "targetHour must be in 0..23 or -1 for unset, got: $targetHour" }
        require(targetMinute in -1..59) { "targetMinute must be in 0..59 or -1 for unset, got: $targetMinute" }
        require(targetSecond in -1..59) { "targetSecond must be in 0..59 or -1 for unset, got: $targetSecond" }
        require(repeatInterval in 0..MAX_REPEAT_INTERVAL) { "repeatInterval must be in 0..$MAX_REPEAT_INTERVAL, got: $repeatInterval" }
        require(repeatUnit in RepeatUnit.NONE..RepeatUnit.MINUTE) { "repeatUnit must be a RepeatUnit constant, got: $repeatUnit" }
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
