package com.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import com.tyme.lunar.LunarMonth
import com.tyme.lunar.LunarYear
import com.tyme.solar.SolarDay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.until
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * 农历推算的年份下限：公元 1 年的年初日期属农历 0 年（实测 0001-01-15 为农历
 * 0/12/2），tyme 年表自 0 年起经往返一致、月表结构与史实锚点验证可靠。
 */
private const val MIN_LUNAR_YEAR = 0

/** 农历推算的年份上限：tyme 年表越界后静默返回错误数据，越界必须显式拦截。 */
private const val MAX_LUNAR_YEAR = 9999

/**
 * 时区不连续余量：出现时刻按「日期 + 时刻 + 时区」独立组合，与自锚点按周期秒
 * 累计的估算之间的偏差等于该时区两个时刻偏移之差，任一时区历史偏移极差不超过
 * 约 26 小时（改线跳日 24 小时 + 夏令时），取 48 小时冗余。
 */
private const val ZONE_DISCONTINUITY_SLACK_SECONDS = 48L * 3600

/**
 * 公历推算的日期上界：kotlinx-datetime 的 [LocalDate] 年份范围止于 9999，
 * 与农历守护统一，公历/农历推算越界一律抛 [IllegalStateException]。
 */
private val MAX_SUPPORTED_DATE = LocalDate(9999, 12, 31)

private val MAX_SUPPORTED_EPOCH_DAYS = MAX_SUPPORTED_DATE.toEpochDays()

private const val RANGE_MESSAGE =
    "Solar projection left the supported date range 0001-01-01..9999-12-31; the repeat rule may never match"

/** 按「日期 + 时刻 + 时区」组合时刻；夏令时缺口自动顺延、重叠取较早一次。 */
private fun compose(
    date: LocalDate,
    time: LocalTime,
    zone: TimeZone,
): Instant = LocalDateTime(date, time).toInstant(zone)

/** 时分秒全选取目标时刻，任一未选整体按 00:00:00（已选字段静默丢弃） */
private fun Schedule.resolveTime(): LocalTime =
    if (targetHour >= 0 && targetMinute >= 0 && targetSecond >= 0) {
        LocalTime(targetHour, targetMinute, targetSecond)
    } else {
        LocalTime(0, 0)
    }

/** 目标日的 UTC 日期部分（构造期已保证范围合法，调用方须先排除未选） */
private fun Schedule.targetDate(): LocalDate = Instant.fromEpochMilliseconds(targetDay).toLocalDateTime(TimeZone.UTC).date

/** [until] 封顶判定：重复推进的出现晚于 until 即丢弃（出现序列单调，后续必然全部超限） */
private fun capped(
    target: Instant,
    until: Instant?,
): Instant? = if (until != null && target > until) null else target

/**
 * 日程锚点：targetDay 的 UTC 日期 + 目标时分秒（未选按 00:00:00）组合到 [zone]
 * 的时刻，即出现序列的第一次出现。锚点日目标时刻落在夏令时缺口时按缺口顺延
 * （如 02:30 → 03:30），因此结果等于「第一次出现」而非朴素组合——
 * nextTarget(锚点前一瞬) 恰为本值。targetDay 未选返回 null。
 */
public fun Schedule.anchor(zone: TimeZone = TimeZone.currentSystemDefault()): Instant? {
    ensureIanaTzdb()
    if (targetDay == -1L) return null
    return compose(targetDate(), resolveTime(), zone)
}

/**
 * 下一个目标时刻：非重复日程取目标日本身；重复时按周期推进到不早于 now。
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
 * 推算年份（公历与农历）越过支持范围 0001..9999 时抛 [IllegalStateException]，
 * 不会死循环；锚点日期范围已在构造期校验。
 *
 * @param until 重复推进的可选上限：出现晚于 until 时返回 null（出现序列单调，
 * 后续必然全部超限，重复已完结）。仅约束重复推进——非重复日程与锚点本身
 * （尚在未来、原样返回的路径）不受 until 影响，重复结束不能追溯取消锚点
 */
public fun Schedule.nextTarget(
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
    until: Instant? = null,
): Instant? {
    ensureIanaTzdb()
    if (targetDay == -1L) return null

    val date = targetDate()
    val time = resolveTime()

    if (repeatInterval <= 0 || repeatUnit == RepeatUnit.NONE) {
        return compose(date, time, zone)
    }

    if (!lunar || repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
        val anchor = compose(date, time, zone)
        if (anchor >= now) return anchor

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
                    ((now - anchor).inWholeSeconds - ZONE_DISCONTINUITY_SLACK_SECONDS) / (periodDays * 86400),
                )
            while (true) {
                val days = step * periodDays
                check(days <= MAX_SUPPORTED_EPOCH_DAYS - date.toEpochDays()) { RANGE_MESSAGE }
                val next =
                    compose(date + DatePeriod(days = days.toInt()), time, zone)
                if (next >= now) return capped(next, until)
                step++
            }
        }

        // 月/年重复以锚点日为基准收缩回弹（1/31 → 2/28 → 3/31），须逐步自锚点推进；
        // 月/年跨度下迭代数天然有限（数十年仅数百次）
        var step = repeatInterval.toLong()
        while (true) {
            val nextDate =
                when (repeatUnit) {
                    RepeatUnit.MONTH -> {
                        check(date.year * 12L + date.month.ordinal + step <= 9999L * 12 + 11) { RANGE_MESSAGE }
                        date + DatePeriod(months = step.toInt())
                    }

                    else -> {
                        check(date.year + step <= 9999L) { RANGE_MESSAGE }
                        date + DatePeriod(years = step.toInt())
                    }
                }
            val next = compose(nextDate, time, zone)
            if (next >= now) return capped(next, until)
            step += repeatInterval
        }
    }

    // 锚点日期范围已在构造期校验（0001..9999），换算出的农历年落在可靠年表
    // 范围内，无需在此重复拦截
    return nextLunarTarget(
        SolarDay.fromYmd(date.year, date.month.ordinal + 1, date.day).getLunarDay(),
        time,
        now,
        zone,
        until,
    )
}

/**
 * 不晚于 [before] 的最近一次目标出现（含恰等于 before 的出现）：非重复日程为
 * 目标日本身；重复时取出现序列中不晚于 before 的最后一项。出现序列自锚点
 * （第一次出现）起单调递增，反向查询同样从锚点正向推进（复用 [nextTarget] 的
 * 快路径与格点语义），无需逐出现倒退或区间二分。
 *
 * [before] 早于第一次出现（含 targetDay 未选）返回 null。推算至支持范围上界
 * （9999-12-31，公历与农历统一）仍未晚于 before 时，返回范围内最后一次出现，
 * 不抛异常——出现序列在界内天然有尽，与 [nextTarget] 的越界语义不同。
 *
 * 组合时刻、夏令时缺口顺延/重叠取较早、月末收缩等语义与 [nextTarget] 完全
 * 一致：两者遍历的是同一条出现序列。
 */
public fun Schedule.previousTarget(
    before: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Instant? {
    ensureIanaTzdb()
    if (targetDay == -1L) return null

    val date = targetDate()
    val time = resolveTime()
    val anchor = compose(date, time, zone)
    if (anchor > before) return null

    if (repeatInterval <= 0 || repeatUnit == RepeatUnit.NONE) return anchor

    if (!lunar || repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
        if (repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
            // 与 nextTarget 对偶：周期秒偏大估计（留时区不连续余量）得到步数上界，
            // 再回退到最后一个不晚于 before 的出现
            val periodDays =
                if (repeatUnit == RepeatUnit.DAY) {
                    repeatInterval.toLong()
                } else {
                    repeatInterval * 7L
                }
            val estimate =
                maxOf(
                    0L,
                    ((before - anchor).inWholeSeconds + ZONE_DISCONTINUITY_SLACK_SECONDS) / (periodDays * 86400),
                )
            val maxSteps = (MAX_SUPPORTED_EPOCH_DAYS - date.toEpochDays()) / periodDays
            var step = minOf(estimate, maxSteps)
            while (true) {
                val next = compose(date + DatePeriod(days = (step * periodDays).toInt()), time, zone)
                if (next <= before) return next
                step--
            }
            // step=0 即锚点组合，前置已保证不晚于 before，回退必在 step≥0 停止
        }

        // 月/年：格点同 nextTarget（锚点日为基准收缩回弹），以日历距离偏大估计
        // 步数上界再回退；before 超出可表示年份时按上界日期估计（格点必在范围内）
        val beforeDate = runCatching { before.toLocalDateTime(zone).date }.getOrDefault(MAX_SUPPORTED_DATE)
        var step =
            when (repeatUnit) {
                RepeatUnit.MONTH -> date.until(beforeDate, DateTimeUnit.MONTH) / repeatInterval + 1L
                else -> date.until(beforeDate, DateTimeUnit.YEAR) / repeatInterval + 1L
            }
        val maxSteps =
            when (repeatUnit) {
                RepeatUnit.MONTH -> (9999L * 12 + 11 - (date.year * 12L + date.month.ordinal)) / repeatInterval
                else -> (9999L - date.year) / repeatInterval
            }
        step = minOf(step, maxSteps)
        while (true) {
            val nextDate =
                when (repeatUnit) {
                    RepeatUnit.MONTH -> date + DatePeriod(months = (step * repeatInterval).toInt())
                    else -> date + DatePeriod(years = (step * repeatInterval).toInt())
                }
            val next = compose(nextDate, time, zone)
            if (next <= before) return next
            step--
        }
        // 同上，step=0 的锚点保证回退终止
    }

    return previousLunarTarget(
        SolarDay.fromYmd(date.year, date.month.ordinal + 1, date.day).getLunarDay(),
        time,
        before,
        zone,
    )
}

/**
 * 倒计时状态：时长先向上取整到完整秒（tick 与秒边界不对齐时进位不闪跳；
 * 目标与当前恰为同一瞬间时输出 0 秒），再细分出展示量级；已过的时间对称细分。
 */
public fun countdown(
    target: Instant,
    now: Instant,
): Countdown {
    val duration = target - now

    return if (duration.isNegative()) {
        breakdown(duration.absoluteValue, past = true)
    } else {
        breakdown(duration, past = false)
    }
}

private fun breakdown(
    duration: Duration,
    past: Boolean,
): Countdown {
    val totalSeconds =
        duration.toComponents { seconds, nanoseconds ->
            seconds + if (nanoseconds > 0) 1L else 0L
        }

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
 * 推算限于可靠农历年表（含内层逐月推进），越界抛 [IllegalStateException]，
 * 不会死循环。长跨度走快路径：早于 now 两年的农历年跳过候选换算，年表按年缓存。
 *
 * @param until 重复出现的可选上限（见 [nextTarget]）；锚点候选同锚点路径豁免
 */
private fun Schedule.nextLunarTarget(
    first: LunarDay,
    time: LocalTime,
    now: Instant,
    zone: TimeZone,
    until: Instant?,
): Instant? {
    var year = first.year
    var month = first.month

    // 年表缓存：同一农历年的连续步进复用 LunarYear 与月表，跨年时在 refresh 中
    // 重建并做年份越界检查
    var cachedYear = Int.MIN_VALUE
    var cachedLeapMonth = 0
    var cachedMonths: List<LunarMonth> = emptyList()
    var cachedNormalMonths: List<LunarMonth> = emptyList()

    fun refreshYearCache() {
        checkLunarYear(year)
        if (cachedYear == year) return
        val lunarYear = LunarYear.fromYear(year)
        cachedYear = year
        cachedLeapMonth = lunarYear.getLeapMonth()
        cachedMonths = lunarYear.getMonths()
        cachedNormalMonths = cachedMonths.filter { it.getMonthWithLeap() > 0 }
    }

    // now 超出 LocalDate 可表示年份（>9999）时取不到本地年份：以极值参与快路径
    // 比较，候选全部视为已过、只步进，随后年份越界守护立即失败
    val nowYear = runCatching { now.toLocalDateTime(zone).year }.getOrDefault(Int.MAX_VALUE)

    // 首轮候选即锚点（出现 #0），与公历路径的锚点豁免一致：不受 until 封顶
    var anchorGrid = true

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
        if (candidateMonth != 0 && year > nowYear - 2) {
            val day = minOf(first.day, LunarMonth.fromYm(year, candidateMonth).getDayCount())

            val solar = LunarDay.fromYmd(year, candidateMonth, day).getSolarDay()

            val target = compose(LocalDate(solar.year, solar.month, solar.day), time, zone)

            if (target >= now) return if (anchorGrid) target else capped(target, until)
        }

        if (repeatUnit == RepeatUnit.MONTH) {
            repeat(repeatInterval) {
                // 内层逐月推进不经过外层循环顶部的年份检查，越界须在此立即失败
                refreshYearCache()

                val months = if (leapCount) cachedMonths else cachedNormalMonths

                // 月表项自带所属农历年，必须同年同月匹配，否则冬月/腊月会匹配到
                // 相邻年份的同名月导致年份不推进、死循环
                var index = months.indexOfFirst { it.getMonthWithLeap() == month && it.year == year }

                // 闰月被过滤时（闰月日日程 + 闰月不参与），按普通月定位继续沿月序推进
                if (index < 0 && month < 0) {
                    index = months.indexOfFirst { it.getMonthWithLeap() == -month && it.year == year }
                }

                if (index in 0 until months.size - 1) {
                    // 月表末尾可能是次年正月：年份须与月份一并采用，否则 year 不推进、
                    // 在当年正月与腊月间死循环
                    val next = months[index + 1]
                    year = next.year
                    month = next.getMonthWithLeap()
                } else {
                    year += 1
                    month = 1
                }
            }
        } else {
            year += repeatInterval
        }

        anchorGrid = false
    }
}

/**
 * 农历月/年重复的反向推算：取出现序列中不晚于 before 的最后一项。结构与
 * [nextLunarTarget] 对偶——同样自锚点格点正向步进，只是把「首个不早于 now」
 * 换成「最后一个不晚于 before」：早于 before 两年的农历年跳过候选换算、只记录
 * 格点（这些候选必然不晚于 before，留作回退位），组合区一旦出现晚于 before 的
 * 候选即返回上一个不晚于者。推算至年表上界仍未晚于 before 时返回界内最后一次
 * 出现，不抛异常（出现序列在界内有尽）。
 */
private fun Schedule.previousLunarTarget(
    first: LunarDay,
    time: LocalTime,
    before: Instant,
    zone: TimeZone,
): Instant {
    var year = first.year
    var month = first.month

    // 年表缓存：同一农历年的连续步进复用 LunarYear 与月表，跨年时在 refresh 中
    // 重建并做年份越界检查
    var cachedYear = Int.MIN_VALUE
    var cachedLeapMonth = 0
    var cachedMonths: List<LunarMonth> = emptyList()
    var cachedNormalMonths: List<LunarMonth> = emptyList()

    fun refreshYearCache() {
        checkLunarYear(year)
        if (cachedYear == year) return
        val lunarYear = LunarYear.fromYear(year)
        cachedYear = year
        cachedLeapMonth = lunarYear.getLeapMonth()
        cachedMonths = lunarYear.getMonths()
        cachedNormalMonths = cachedMonths.filter { it.getMonthWithLeap() > 0 }
    }

    // 日收缩 + 历法换算 + 组合；跳过区回退时也要组合记录的格点，单处复用
    fun composeGrid(
        y: Int,
        m: Int,
    ): Instant {
        val day = minOf(first.day, LunarMonth.fromYm(y, m).getDayCount())
        val solar = LunarDay.fromYmd(y, m, day).getSolarDay()
        return compose(LocalDate(solar.year, solar.month, solar.day), time, zone)
    }

    // before 超出 LocalDate 可表示年份（>9999）时取不到本地年份：组合区条件对
    // 极大值恒成立，所有界内候选都参与组合，推到年表上界自然收尾
    val beforeYear = runCatching { before.toLocalDateTime(zone).year }.getOrDefault(Int.MAX_VALUE)

    // 已组合的不晚于 before 候选；跳过区记录的最近格点（必然不晚于 before 但
    // 未组合），组合区首个晚于 before 的候选出现时取二者中较晚者
    var lastBelow: Instant? = null
    var pendingYear = year
    var pendingMonth = month

    while (true) {
        if (year > MAX_LUNAR_YEAR) return lastBelow ?: composeGrid(pendingYear, pendingMonth)

        refreshYearCache()

        val leapMonth = cachedLeapMonth

        val candidateMonth =
            when {
                month > 0 || leapMonth == -month -> month
                leapCount -> -month
                else -> 0
            }

        if (candidateMonth != 0) {
            // 与 nextLunarTarget 的跳过条件对偶：农历年 Y 的候选最晚落在公历次年
            // 春节前（约次年 2 月中），Y 早于 before 两年及以上时候选必然不晚于
            // before，跳过昂贵的历法换算只记录格点（跨数十年即数百次换算）
            if (year <= beforeYear - 2) {
                pendingYear = year
                pendingMonth = candidateMonth
            } else {
                val target = composeGrid(year, candidateMonth)
                if (target > before) return lastBelow ?: composeGrid(pendingYear, pendingMonth)
                lastBelow = target
            }
        }

        if (repeatUnit == RepeatUnit.MONTH) {
            repeat(repeatInterval) {
                // 内层逐月推进不经过外层循环顶部的年份检查，越界须在此立即收尾
                if (year > MAX_LUNAR_YEAR) return lastBelow ?: composeGrid(pendingYear, pendingMonth)

                refreshYearCache()

                val months = if (leapCount) cachedMonths else cachedNormalMonths

                // 月表项自带所属农历年，必须同年同月匹配，否则冬月/腊月会匹配到
                // 相邻年份的同名月导致年份不推进、死循环
                var index = months.indexOfFirst { it.getMonthWithLeap() == month && it.year == year }

                // 闰月被过滤时（闰月日日程 + 闰月不参与），按普通月定位继续沿月序推进
                if (index < 0 && month < 0) {
                    index = months.indexOfFirst { it.getMonthWithLeap() == -month && it.year == year }
                }

                if (index in 0 until months.size - 1) {
                    // 月表末尾可能是次年正月：年份须与月份一并采用，否则 year 不推进、
                    // 在当年正月与腊月间死循环
                    val next = months[index + 1]
                    year = next.year
                    month = next.getMonthWithLeap()
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

/** 推算年份超出 tyme 可靠范围时立即失败：越界年表数据不可信，且规则可能永远无法命中。 */
private fun checkLunarYear(year: Int) {
    check(year in MIN_LUNAR_YEAR..MAX_LUNAR_YEAR) {
        "Lunar projection left the reliable lunar calendar range $MIN_LUNAR_YEAR..$MAX_LUNAR_YEAR; the repeat rule may never match"
    }
}

/**
 * 确保平台的 IANA 时区库可用：wasm 平台的 kotlinx-datetime 依赖
 * @js-joda/timezone 以副作用注入时区库，须在其首次使用前加载。
 */
internal expect fun ensureIanaTzdb()
