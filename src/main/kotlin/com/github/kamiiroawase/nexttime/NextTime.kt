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

/** 农历推算的年份上限：lunar-java 年表越界后静默返回错误数据，越界必须显式拦截。 */
private const val MAX_LUNAR_YEAR = 9999

/**
 * 时区不连续余量：出现时刻按「日期 + 时刻 + 时区」独立组合，与自锚点按周期秒
 * 累计的估算之间的偏差等于该时区两个时刻偏移之差，任一时区历史偏移极差不超过
 * 约 26 小时（改线跳日 24 小时 + 夏令时），取 48 小时冗余。
 */
private const val ZONE_DISCONTINUITY_SLACK_SECONDS = 48L * 3600

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
 *
 * 农历月/年重复的锚点年超出 9999 抛 [IllegalArgumentException]（lunar-java
 * 年表越界后数据不可靠）；推算推进越过 9999 抛 [IllegalStateException]。
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
        val anchor = date.atTime(time).atZone(zone)
        if (!anchor.isBefore(now)) return anchor

        if (repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
            // 天/周无月末收缩，出现日期 = 锚点日 + 步数×周期天数：先按周期秒数估算
            // 步数、留出时区不连续余量（见 ZONE_DISCONTINUITY_SLACK_SECONDS），
            // 再小步前推到首个不早于 now 的出现，避免逐周期组合时区
            // （跨数十年每日重复即数万次组合）
            val periodDays =
                if (repeatUnit == RepeatUnit.DAY) {
                    repeatInterval.toLong()
                } else {
                    repeatInterval * 7L
                }
            var step =
                maxOf(
                    1L,
                    (Duration.between(anchor, now).seconds - ZONE_DISCONTINUITY_SLACK_SECONDS) / (periodDays * 86400),
                )
            while (true) {
                val next = date.plusDays(step * periodDays).atTime(time).atZone(zone)
                if (!next.isBefore(now)) return next
                step++
            }
        }

        // 月/年重复以锚点日为基准收缩回弹（1/31 → 2/28 → 3/31），须逐步自锚点推进；
        // 月/年跨度下迭代数天然有限（数十年仅数百次）
        var step = repeatInterval.toLong()
        while (true) {
            val nextDate =
                when (repeatUnit) {
                    RepeatUnit.MONTH -> date.plusMonths(step)
                    else -> date.plusYears(step)
                }
            val next = nextDate.atTime(time).atZone(zone)
            if (!next.isBefore(now)) return next
            step += repeatInterval
        }
    }

    // lunar-java 年表越界后静默返回错误数据（如虚构闰月），锚点须先于换算拦截
    require(date.year <= MAX_LUNAR_YEAR) {
        "targetDay resolves to year ${date.year}, beyond the reliable lunar calendar range of year $MAX_LUNAR_YEAR"
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
 * 推算以公历 9999 年为上限（含内层逐月推进），超出抛 [IllegalStateException]，
 * 不会死循环。长跨度走快路径：早于 now 两年的农历年跳过候选换算，年表按年缓存。
 */
private fun Schedule.nextLunarTarget(
    first: Lunar,
    time: LocalTime,
    now: ZonedDateTime,
    zone: ZoneId,
): ZonedDateTime {
    var year = first.year
    var month = first.month

    // 年表缓存：同一农历年的连续步进复用 LunarYear 与月表（每表 15 个月对象），
    // 跨年时在 refresh 中重建并做年份越界检查
    var cachedYear = Int.MIN_VALUE
    var cachedLeapMonth = 0
    var cachedMonths: List<LunarMonth> = emptyList()
    var cachedNormalMonths: List<LunarMonth> = emptyList()

    fun refreshYearCache() {
        checkLunarYear(year)
        if (cachedYear == year) return
        val lunarYear = LunarYear.fromYear(year)
        cachedYear = year
        cachedLeapMonth = lunarYear.leapMonth
        cachedMonths = lunarYear.months
        cachedNormalMonths = lunarYear.months.filter { it.month > 0 }
    }

    while (true) {
        refreshYearCache()

        val leapMonth = cachedLeapMonth

        val candidateMonth =
            when {
                month > 0 || leapMonth == -month -> month

                leapCount -> -month

                else -> 0
            }

        // 农历年 Y 的候选最晚落在公历次年春节前（约次年 2 月中）：Y 早于 now 两年
        // 及以上时候选必然已过，跳过昂贵的历法换算只步进（跨数十年即数百次换算）
        if (candidateMonth != 0 && year > now.year - 2) {
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
                // 内层逐月推进不经过外层循环顶部的年份检查，越界须在此立即失败
                refreshYearCache()

                val months = if (leapCount) cachedMonths else cachedNormalMonths

                // lunar-java 的 months 从前一年冬月起共 15 个月，必须同年同月匹配，
                // 否则腊月会匹配到前一年腊月导致年份不推进、死循环
                var index = months.indexOfFirst { it.month == month && it.year == year }

                // 闰月被过滤时（闰月日日程 + 闰月不参与），按普通月定位继续沿月序推进
                if (index < 0 && month < 0) {
                    index = months.indexOfFirst { it.month == -month && it.year == year }
                }

                if (index in 0 until months.size - 1) {
                    // 无闰月年的月表末尾是次年正月，下一项可能属于下一年；
                    // 年份须与月份一并采用，否则 year 不推进、在当年正月与腊月间死循环
                    val next = months[index + 1]
                    year = next.year
                    month = next.month
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

/** 推算年份超出 lunar-java 可靠范围时立即失败：越界年表数据不可信，且规则可能永远无法命中。 */
private fun checkLunarYear(year: Int) {
    check(year <= MAX_LUNAR_YEAR) {
        "Lunar projection exceeded year $MAX_LUNAR_YEAR, the limit of reliable lunar data; the repeat rule may never match"
    }
}
