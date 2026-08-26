package com.github.kamiiroawase.nexttime

/**
 * 倒计时状态：目标时刻已过（[past] 为 true）或未到，以及取整后细分出的展示量级。
 * 不含任何语言文案，渲染（如中文「还有3天」「已过2小时」）由消费方按语言实现。
 *
 * @param past true 表示目标已过
 * @param value 展示数值
 * @param unit 展示单位
 */
public data class Countdown(
    public val past: Boolean,
    public val value: Long,
    public val unit: CountdownUnit,
)

/**
 * 倒计时展示单位：满一天取天，不足一天取小时，不足一小时取分，不足一分取秒。
 */
public enum class CountdownUnit {
    DAYS,
    HOURS,
    MINUTES,
    SECONDS,
}
