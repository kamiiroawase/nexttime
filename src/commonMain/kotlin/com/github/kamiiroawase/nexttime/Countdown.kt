package com.github.kamiiroawase.nexttime

import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlin.time.Duration
import kotlin.time.Instant

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

/**
 * [countdown] 的取整模式。
 */
public enum class Rounding {
    /**
     * 截断：满才算（86399 秒 = 23 小时）。历史默认行为。
     */
    TRUNCATE,

    /**
     * 未到方向向上取整：不足一天算一天、差一秒满整单位也进位
     * （86399 秒 + 1 纳秒 = 1 天、3599 秒 = 1 小时），撞单位边界时进位
     * （24 时 → 1 天、60 分 → 1 时）；已过方向恒截断（累计满才算）。
     */
    CEIL_FUTURE,
}

/**
 * 倒计时状态：时长先向上取整到完整秒（tick 与秒边界不对齐时进位不闪跳；
 * 目标与当前恰为同一瞬间时输出 0 秒），再细分出展示量级；已过的时间对称细分。
 *
 * @param rounding 展示数值取整模式，默认 [Rounding.TRUNCATE]；已过方向不受
 * 模式影响，恒为截断
 */
public fun countdown(
    target: Instant,
    now: Instant,
    rounding: Rounding = Rounding.TRUNCATE,
): Countdown {
    val duration = target - now

    return if (duration.isNegative()) {
        breakdown(duration.absoluteValue, past = true, ceil = false)
    } else {
        breakdown(duration, past = false, ceil = rounding == Rounding.CEIL_FUTURE)
    }
}

private fun breakdown(
    duration: Duration,
    past: Boolean,
    ceil: Boolean,
): Countdown {
    val totalSeconds =
        duration.toComponents { seconds, nanoseconds ->
            seconds + if (nanoseconds > 0) 1L else 0L
        }

    if (!ceil) {
        val days = totalSeconds / 86400
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60

        return when {
            days >= 1 -> Countdown(past, days, CountdownUnit.DAYS)
            hours >= 1 -> Countdown(past, hours, CountdownUnit.HOURS)
            minutes >= 1 -> Countdown(past, minutes, CountdownUnit.MINUTES)
            else -> Countdown(past, totalSeconds, CountdownUnit.SECONDS)
        }
    }

    fun ceilDiv(
        dividend: Long,
        divisor: Long,
    ): Long = (dividend + divisor - 1) / divisor

    return when {
        totalSeconds >= 86_400L -> {
            Countdown(past, ceilDiv(totalSeconds, 86_400L), CountdownUnit.DAYS)
        }

        totalSeconds >= 3_600L -> {
            ceilDiv(totalSeconds, 3_600L).let {
                if (it == 24L) Countdown(past, 1L, CountdownUnit.DAYS) else Countdown(past, it, CountdownUnit.HOURS)
            }
        }

        totalSeconds >= 60L -> {
            ceilDiv(totalSeconds, 60L).let {
                if (it == 60L) Countdown(past, 1L, CountdownUnit.HOURS) else Countdown(past, it, CountdownUnit.MINUTES)
            }
        }

        else -> {
            Countdown(past, totalSeconds, CountdownUnit.SECONDS)
        }
    }
}

/**
 * 按日历分量细分的倒计时状态：目标与当前在 [zone] 的本地日期时间之差，细分到
 * 年/月/日/时/分/秒（闰年、月长由日历自动处理），已过与未到对称。
 *
 * 与 [countdown] 的语义区别：这里按**钟面分量**计算——跨夏令时变化的一天计为
 * 1 天 0 小时（钟面走满 24 时），而 [countdown] 按真实时长计 23 或 25 小时。
 * 月末钳制与 java.time 的 `Period.between` 一致：1 月 31 日至 2 月 28 日为
 * 0 个月 28 天。零分量原样输出，省略哪些（如「X年(X月)(XX天)」省零）由消费方
 * 渲染时决定。
 *
 * @param past true 表示目标已过，分量按 now → target 方向计算
 * @param years 年分量（未满整年不计入，落在 [months] 里）
 * @param months 月分量（0..11）
 * @param days 日分量（0..30，随月长变化）
 * @param hours 时分量（0..23）
 * @param minutes 分分量（0..59）
 * @param seconds 秒分量（0..59，亚秒丢弃）
 */
public data class CalendarCountdown(
    public val past: Boolean,
    public val years: Int,
    public val months: Int,
    public val days: Int,
    public val hours: Int,
    public val minutes: Int,
    public val seconds: Int,
)

/**
 * 按日历分量计算倒计时（见 [CalendarCountdown]）。目标与当前分处不同时区时，
 * 以 [zone] 的本地钟面为基准。
 */
public fun calendarCountdown(
    target: Instant,
    now: Instant,
    zone: TimeZone,
): CalendarCountdown {
    val past = target < now
    val period = if (past) target.periodUntil(now, zone) else now.periodUntil(target, zone)

    return CalendarCountdown(
        past = past,
        years = period.years,
        months = period.months,
        days = period.days ?: 0,
        hours = period.hours,
        minutes = period.minutes,
        seconds = period.seconds,
    )
}
