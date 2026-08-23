package com.github.kamiiroawase.nexttime

/**
 * 倒计时日程的目标时间描述：锚点目标日、时刻与重复规则。
 *
 * 构造时校验全部字段，非法取值抛 [IllegalArgumentException]：
 * [targetDay] 仅允许 -1（未选）或正的 UTC 毫秒；时分秒仅允许 -1（未选）或各自
 * 合法区间（-1 以外没有「未选」语义，如 0 与负数都会被拒绝）；[repeatInterval]
 * 须非负；[repeatUnit] 须为 [RepeatUnit] 常量之一。
 *
 * @param lunar 目标日按农历解释：月/年重复沿农历推进，天/周重复与公历无异
 * @param leapCount 农历时闰月是否参与重复推算
 * @param targetDay 目标日 UTC 毫秒值，-1 表示未选
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
        require(targetDay == -1L || targetDay > 0) { "targetDay must be positive UTC millis or -1 for unset, got: $targetDay" }
        require(targetHour in -1..23) { "targetHour must be in 0..23 or -1 for unset, got: $targetHour" }
        require(targetMinute in -1..59) { "targetMinute must be in 0..59 or -1 for unset, got: $targetMinute" }
        require(targetSecond in -1..59) { "targetSecond must be in 0..59 or -1 for unset, got: $targetSecond" }
        require(repeatInterval >= 0) { "repeatInterval must be non-negative, got: $repeatInterval" }
        require(repeatUnit in RepeatUnit.NONE..RepeatUnit.YEAR) { "repeatUnit must be a RepeatUnit constant, got: $repeatUnit" }
    }
}

/**
 * [Schedule.repeatUnit] 的取值常量。
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
}
