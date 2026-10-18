package io.github.kamiiroawase.nexttime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlin.random.Random
import kotlin.time.Instant

/**
 * 属性测试的确定性随机生成：跨平台同种子同序列（kotlinx.random 保证），失败可精确复现。
 *
 * Lord Howe 有 ±30 分钟夏令时、Chatham 为 +12:45/+13:45——45 分钟跳变检验推算
 * 快路径的时区不连续余量假设。
 */

internal val lordHowe: TimeZone = TimeZone.of("Australia/Lord_Howe")

internal val chatham: TimeZone = TimeZone.of("Pacific/Chatham")

internal val apia: TimeZone = TimeZone.of("Pacific/Apia")

internal val fuzzZones = listOf(shanghai, newYork, apia, lordHowe, chatham)

/**
 * 随机生成构造合法的日程。有界域：锚点 1920..2080——农历年表可靠、避开 1582
 * 历法缺口、覆盖 20 世纪以来的各时区夏令时与改线史；查询时刻（[randomInstant]）
 * 落在 1910..2090，推算最坏约两个世纪的月步。
 *
 * 生成即合法（不依赖构造失败重试）：锚点隶属用「强制并入锚点」或「按锚点计算
 * 序数」保证；选择器只配公历周/月重复；repeatCount 的最坏展开（40 次 × 年间隔 5）
 * 仍在 9999 支持界内。
 */
internal fun randomSchedule(rng: Random): Schedule {
    val year = rng.nextInt(1920, 2081)
    val monthNumber = rng.nextInt(1, 13)
    val day = rng.nextInt(1, YearMonth(year, monthNumber).numberOfDays + 1)
    val date = LocalDate(year, monthNumber, day)

    val withTime = rng.nextBoolean()
    val timeFields =
        if (withTime) {
            Triple(rng.nextInt(24), rng.nextInt(60), rng.nextInt(60))
        } else {
            Triple(-1, -1, -1)
        }

    val unit = rng.nextInt(0, 7)
    val interval =
        when (unit) {
            0 -> 0
            RepeatUnit.HOUR -> rng.nextInt(1, 13)
            RepeatUnit.MINUTE -> rng.nextInt(1, 61)
            else -> rng.nextInt(1, 6)
        }

    val lunar = rng.nextBoolean() && (unit == RepeatUnit.MONTH || unit == RepeatUnit.YEAR) && interval > 0
    var weekdays: Set<Int> = emptySet()
    var weekdayOrdinals: Set<Int> = emptySet()
    var monthDays: Set<Int> = emptySet()
    var clampMonthDays = false

    // 选择器仅公历周/月重复（构造矩阵其余组合全拒绝）；月重复保留无选择器形态
    // （锚点日收缩回弹路径）
    if (!lunar && interval > 0) {
        when (unit) {
            RepeatUnit.WEEK -> {
                if (rng.nextBoolean()) {
                    weekdays = randomWeekdaysWithAnchor(rng, date)
                }
            }

            RepeatUnit.MONTH -> {
                when (rng.nextInt(0, 5)) {
                    0 -> {
                        weekdays = randomWeekdaysWithAnchor(rng, date)
                    }

                    1 -> {
                        weekdays = randomWeekdaysWithAnchor(rng, date)
                        // 序数按锚点自身在第几周计算并入，保证隶属；附带随机项可能超界——
                        // 超界月份跳过是既定语义，由推算性质覆盖
                        if (rng.nextBoolean()) {
                            val positive = (day - 1) / 7 + 1
                            weekdayOrdinals = setOf(positive) + randomOrdinals(rng)
                        } else {
                            val negative = (YearMonth(year, monthNumber).numberOfDays - day) / 7 + 1
                            weekdayOrdinals = setOf(-negative) + randomOrdinals(rng)
                        }
                    }

                    2, 3 -> {
                        // 强制并入锚点日号（正数日恒在当月解析结果内）；clamp 随机开启
                        monthDays = setOf(day) + randomMonthDayEntries(rng)
                        clampMonthDays = rng.nextBoolean()
                    }

                    else -> {
                        // 无选择器：锚点日收缩回弹路径
                    }
                }
            }

            else -> {
                // 天/小时/分钟不带选择器
            }
        }
    }

    val repeatCount =
        if (interval > 0 && unit != RepeatUnit.NONE && rng.nextInt(10) < 3) {
            rng.nextInt(2, 41)
        } else {
            0
        }

    return Schedule(
        lunar = lunar,
        leapCount = lunar && rng.nextBoolean(),
        targetDay = utcMillis(date),
        targetHour = timeFields.first,
        targetMinute = timeFields.second,
        targetSecond = timeFields.third,
        repeatInterval = interval,
        repeatUnit = unit,
        weekdays = weekdays,
        weekdayOrdinals = weekdayOrdinals,
        monthDays = monthDays,
        clampMonthDays = clampMonthDays,
        repeatCount = repeatCount,
    )
}

/** 非空星期子集且必含锚点星期（周选择器的锚点隶属条件） */
private fun randomWeekdaysWithAnchor(
    rng: Random,
    anchor: LocalDate,
): Set<Int> {
    val anchorWeekday = anchor.dayOfWeek.isoDayNumber
    val extra = (1..7).filter { it != anchorWeekday }.filter { rng.nextInt(4) > 0 }
    return (setOf(anchorWeekday) + extra).also { check(it.isNotEmpty()) }
}

/** 1..5 ∪ -5..-1 内的随机序数 0..2 个（不含 0；锚点序数由调用方另行并入） */
private fun randomOrdinals(rng: Random): Set<Int> =
    buildSet {
        repeat(rng.nextInt(3)) {
            val value = rng.nextInt(1, 6)
            add(if (rng.nextBoolean()) value else -value)
        }
    }

/** 1..31 ∪ -31..-1 内的随机月内日 0..3 个（锚点日号由调用方另行并入） */
private fun randomMonthDayEntries(rng: Random): Set<Int> =
    buildSet {
        repeat(rng.nextInt(4)) {
            val value = rng.nextInt(1, 32)
            add(if (rng.nextBoolean()) value else -value)
        }
    }

/** 1910..2090 的随机本地时刻（日 ≤ 28 避免构造非法日期；缺口时刻由组合语义顺延） */
internal fun randomInstant(
    rng: Random,
    zone: TimeZone,
): Instant =
    LocalDateTime(
        LocalDate(rng.nextInt(1910, 2091), rng.nextInt(1, 13), rng.nextInt(1, 29)),
        LocalTime(rng.nextInt(24), rng.nextInt(60), rng.nextInt(60)),
    ).toInstant(zone)
