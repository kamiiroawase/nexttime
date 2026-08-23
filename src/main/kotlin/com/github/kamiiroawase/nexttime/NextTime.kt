package com.github.kamiiroawase.nexttime

import com.nlf.calendar.Lunar
import com.nlf.calendar.LunarMonth
import com.nlf.calendar.LunarYear
import com.nlf.calendar.Solar
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** 农历推算的年份上限：超出视为超出农历数据可靠范围，规则可能永远无法命中。 */
private const val MAX_LUNAR_YEAR = 9999

/**
 * 下一个目标时刻：不重复取目标日本身；重复时按周期推进到不早于 now。
 * 公历日程按公历天/周/月/年推进；农历日程的月/年重复按农历推进，
 * 天/周重复与公历无异，直接按公历算。targetDay 未选返回 null。
 * 目标日的日期固定按 UTC 解析；组合时刻用 zone（默认系统时区）。
 *
 * 重复的每次出现都由「日期 + 时刻 + 时区」重新组合：夏令时缺口日仅当日顺延
 * （如纽约 02:30 → 03:30），后续出现回到原时刻；重叠日（秋令时回拨）取较早一次。
 *
 * 公历月/年重复的月末收缩以锚点日为基准：短月/平年收缩到当月最接近锚点日的
 * 一天，后续月/闰年回弹（1/31 → 2/28 → 3/31；2/29 → 平年 2/28 → 闰年 2/29），
 * 与农历路径一致。
 */
public fun Schedule.nextTarget(
    now: ZonedDateTime = ZonedDateTime.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): ZonedDateTime? {
    if (targetDay <= 0) return null

    val date = Instant.ofEpochMilli(targetDay).atZone(ZoneOffset.UTC).toLocalDate()

    val time =
        if (targetHour >= 0 && targetMinute >= 0 && targetSecond >= 0) {
            LocalTime.of(targetHour, targetMinute, targetSecond)
        } else {
            LocalTime.MIN
        }

    if (repeatInterval <= 0 || repeatUnit == RepeatUnit.NONE) {
        return date.atTime(time).atZone(zone)
    }

    if (!lunar || repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
        // 每一步都自锚点日推进，使月/年重复的收缩以锚点日为基准、后续可回弹；
        // 天/周无收缩概念，自锚点累计与逐步推进等价
        var step = repeatInterval.toLong()
        var next = date.atTime(time).atZone(zone)

        while (next.isBefore(now)) {
            val nextDate =
                when (repeatUnit) {
                    RepeatUnit.DAY -> date.plusDays(step)
                    RepeatUnit.WEEK -> date.plusWeeks(step)
                    RepeatUnit.MONTH -> date.plusMonths(step)
                    else -> date.plusYears(step)
                }
            next = nextDate.atTime(time).atZone(zone)
            step += repeatInterval
        }

        return next
    }

    return nextLunarTarget(
        Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar,
        time,
        now,
        zone,
    )
}

/**
 * 倒计时状态：时长先向上取整到完整秒（tick 与秒边界不对齐时进位不闪跳；
 * 目标与当前恰为同一瞬间时输出 0 秒），再细分出展示量级；已过的时间对称细分。
 */
public fun countdown(
    target: ZonedDateTime,
    now: ZonedDateTime,
): Countdown {
    val duration = Duration.between(now, target)

    return if (duration.isNegative) {
        breakdown(duration.negated(), past = true)
    } else {
        breakdown(duration, past = false)
    }
}

private fun breakdown(
    duration: Duration,
    past: Boolean,
): Countdown {
    val totalSeconds = duration.seconds + if (duration.nano > 0) 1L else 0L

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

/**
 * 农历月/年重复推算：月重复沿农历月序列步进，年重复保持农历月日。
 * leapCount=true 时闰月参与：月重复把闰月算独立一步；年重复在目标年无该闰月时
 * 退化为普通月（仅当年退化，之后有闰月的年份仍取闰月），每年命中一次。
 * leapCount=false 时闰月不参与：月重复跳过闰月；年重复在无该闰月的年份不命中，
 * 推进到下一个有该闰月的农历年。日超出当月天数取月末。
 *
 * 年重复的 repeatInterval 自锚点年沿农历年格点推进：leapCount=false 的闰月日程
 * 只在恰有该闰月的格点年命中，落在格点外的真实闰月年会被跳过。
 *
 * 推算以公历 9999 年为上限，超出抛 [IllegalStateException]，不会死循环。
 */
private fun Schedule.nextLunarTarget(
    first: Lunar,
    time: LocalTime,
    now: ZonedDateTime,
    zone: ZoneId,
): ZonedDateTime {
    var year = first.year
    var month = first.month

    while (true) {
        check(year <= MAX_LUNAR_YEAR) { "农历推算超出公历 $MAX_LUNAR_YEAR 年，重复规则可能永远无法命中" }

        val leapMonth = LunarYear.fromYear(year).leapMonth

        val candidateMonth =
            when {
                month > 0 || leapMonth == -month -> month

                leapCount -> -month

                else -> 0
            }

        if (candidateMonth != 0) {
            val day = minOf(first.day, LunarMonth.fromYm(year, candidateMonth)?.dayCount ?: first.day)

            val solar = Lunar.fromYmd(year, candidateMonth, day).solar

            val target =
                LocalDate
                    .of(solar.year, solar.month, solar.day)
                    .atTime(time)
                    .atZone(zone)

            if (!target.isBefore(now)) return target
        }

        if (repeatUnit == RepeatUnit.MONTH) {
            repeat(repeatInterval) {
                val months =
                    if (leapCount) {
                        LunarYear.fromYear(year).months
                    } else {
                        LunarYear.fromYear(year).months.filter { it.month > 0 }
                    }

                // lunar-java 的 months 从前一年冬月起共 15 个月，必须同年同月匹配，
                // 否则腊月会匹配到前一年腊月导致年份不推进、死循环
                var index = months.indexOfFirst { it.month == month && it.year == year }

                // 闰月被过滤时（闰月日日程 + 闰月不参与），按普通月定位继续沿月序推进
                if (index < 0 && month < 0) {
                    index = months.indexOfFirst { it.month == -month && it.year == year }
                }

                if (index in 0 until months.size - 1) {
                    month = months[index + 1].month
                } else {
                    year += 1
                    month = 1
                }
            }
        } else {
            year += repeatInterval
        }
    }
}
