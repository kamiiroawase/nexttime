package io.github.kamiiroawase.nexttime

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
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.until
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
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

/**
 * 推算越过支持范围的内部信号（公历 0001..9999 与农历可靠年表共用）。作为
 * [IllegalStateException] 的私有子类型存在：不带 until 的 nextTarget 查询把它
 * 原样抛给调用方（契约异常，答案不可表示）；带 until 的有界查询在 nextTarget
 * 顶层捕获并按完结语义转为 null——出现序列自锚点单调，步进越过范围界即界内
 * 已无不早于 now 的出现。previousTarget 不经此分流：界前收尾返回，不触发守护。
 */
private class ProjectionRangeException(
    message: String,
) : IllegalStateException(message)

/** 公历推算越界错误（[RANGE_MESSAGE]），完结分流语义见 [ProjectionRangeException]。 */
private fun solarRangeError(): ProjectionRangeException = ProjectionRangeException(RANGE_MESSAGE)

/** 按「日期 + 时刻 + 时区」组合时刻；夏令时缺口自动顺延、重叠取较早一次。 */
private fun compose(
    date: LocalDate,
    time: LocalTime,
    zone: TimeZone,
): Instant = LocalDateTime(date, time).toInstant(zone)

/** 时分秒全选取目标时刻，全未选整体按 00:00:00（部分选择已在构造期拒绝） */
private fun Schedule.resolveTime(): LocalTime =
    if (targetHour >= 0 && targetMinute >= 0 && targetSecond >= 0) {
        LocalTime(targetHour, targetMinute, targetSecond)
    } else {
        LocalTime(0, 0)
    }

/** [until] 封顶判定：重复推进的出现晚于 until 即丢弃（出现序列单调，后续必然全部超限） */
private fun capped(
    target: Instant,
    until: Instant?,
): Instant? = if (until != null && target > until) null else target

/**
 * [Schedule.repeatCount] 封顶判定：出现序号（0 基，锚点为 0）越过 count-1 即完结。
 * 与 [capped] 对偶——一个按时间界、一个按次数界，两者先到者生效；完结一律返回
 * null，不抛越界异常
 */
private fun Schedule.beyondCount(occurrenceIndex: Long): Boolean = repeatCount > 0 && occurrenceIndex > repeatCount - 1L

/**
 * 日程锚点：targetDay 的 UTC 日期 + 目标时分秒（全未选按 00:00:00）组合到 [zone]
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
 * 不会死循环；锚点日期范围已在构造期校验。带 until 的完结判定一律返回 null、
 * 不触发越界异常：until 早于 now，或界内已无不早于 now 的出现——出现序列自
 * 锚点单调，推算步进越过范围界即界内无解（含 now 已越过界内最后一次实际出现
 * 的场景；「9999-12-31 末秒组合加时区不连续余量」只是免推算的快路径上界，
 * 实际最后一次界内出现可能早于它数日，落在其间同样完结）；不带 until 的同一
 * 查询越界仍抛异常（答案不可表示）。
 *
 * 小时/分钟重复按**真实时长格点**（出现 = 锚点 + 步数×间隔，ISO 8601 的
 * time-based 惯例）：不经「日期 + 时刻 + 时区」组合，跨夏令时出现时刻的本地
 * 钟面随真实间隔漂移（无缺口顺延/重叠取较早语义）；只做 Instant 算术，
 * **不受 0001..9999 日期界守护约束**（Instant 值域内任意推进，不抛越界异常）。
 * lunar 标志与小时/分钟无关（同天/周）。
 *
 * 周重复配 [Schedule.weekdays] 时改为周格点选择器语义：自锚点周（ISO 周）起每
 * interval 周的选中星期各一次出现，锚点周内早于锚点日的规则日不算出现；月重复
 * 配 weekdays / weekdayOrdinals / monthDays 时改由选择器决定出现日（取代锚点日
 * 收缩回弹），月内命中日升序、缺失日的跳过或收缩由 [Schedule.clampMonthDays]
 * 决定。时刻组合与夏令时语义与无选择器时一致。
 *
 * [Schedule.repeatCount]（出现总次数上限，锚点为第 1 次）耗尽后本函数返回 null
 * （完结语义与 until 同形，两者先到者生效）；previousTarget 同样不返回越限出现。
 *
 * @param until 重复推进的可选上限：出现晚于 until 时返回 null（出现序列单调，
 * 后续必然全部超限，重复已完结）。仅约束重复推进——非重复日程与锚点本身
 * （尚在未来、原样返回的路径）不受 until 影响，重复结束不能追溯取消锚点；
 * until 早于 now 时任何不早于 now 的出现必然超限，直接返回 null；界内出现
 * 耗尽（推算越过支持范围）时同理——界内不可能再有满足 [now, until] 的出现，
 * 直接返回 null（不带 until 的同一查询越界仍抛异常，答案不可表示）
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

    // 锚点（第一次出现）统一在此返回：HOUR/MINUTE、公历、农历三条路径的锚点同为
    // 「锚点日 + 时刻 + 时区」的组合时刻（农历首候选即锚点公历日期的往返换算，
    // 取值恒等）。锚点豁免 until——重复结束不能追溯取消锚点
    val anchor = compose(date, time, zone)
    if (anchor >= now) return anchor

    // until 早于 now：出现序列自锚点起单调，任何不早于 now 的出现必然晚于
    // until（重复已完结），直接返回 null——顺带兜住「now 远到把推算推过
    // 0001..9999 范围界」的场景，此时正确答案同样是 null 而非越界异常
    if (until != null && until < now) return null

    if (repeatUnit == RepeatUnit.HOUR || repeatUnit == RepeatUnit.MINUTE) {
        // 真实时长格点：出现 = 锚点 + 步数×周期秒。epoch 秒整型运算而非 Duration：
        // Duration 表示分档（约 292 年内纳秒精度、更长降为毫秒，纳秒分量超档
        // 即饱和失真），整型直算绕开这些表示细节。秒差向下取整后真实步数至多
        // 多一步，小步前推兜住亚秒分量
        val periodSeconds =
            if (repeatUnit == RepeatUnit.HOUR) {
                repeatInterval * 3600L
            } else {
                repeatInterval * 60L
            }
        val anchorSeconds = anchor.epochSeconds
        var step = (now.epochSeconds - anchorSeconds) / periodSeconds
        while (true) {
            // 次数封顶先于组合：step 即出现序号（锚点为 0），完结返回 null
            if (beyondCount(step)) return null
            val next = Instant.fromEpochSeconds(anchorSeconds + step * periodSeconds)
            if (next >= now) return capped(next, until)
            step++
        }
    }

    // 带 until 的完结判定（免推算的快路径），与上方「until 早于 now」的方向对偶：
    // now 晚于界内最后一个可能出现的保守上界——9999-12-31 末秒的组合结果加时区
    // 不连续余量（缺口顺延至多把末出现推后约 26 小时，48 小时冗余覆盖，见
    // ZONE_DISCONTINUITY_SLACK_SECONDS）——时，任何不早于 now 的出现必然在支持
    // 范围外，无需推算直接返回 null。上界刻意保守而非精确：实际最后一次界内
    // 出现可能早于它数日（无缺口顺延的时区），落在两者之间的 now 由下方范围
    // 守护的分流兜底完结。仅限带 until 的有界查询：不带 until 的越界查询答案
    // 不可表示，仍按契约抛 IllegalStateException；小时/分钟为真实时长格点，已在
    // 上方分支返回，不受此限
    if (until != null) {
        val lastPossibleOccurrence =
            compose(MAX_SUPPORTED_DATE, LocalTime(23, 59, 59), zone) + ZONE_DISCONTINUITY_SLACK_SECONDS.seconds
        if (now > lastPossibleOccurrence) return null
    }

    // 范围守护的统一分流：出现序列自锚点起单调，推算步进越过支持范围即界内已无
    // 不早于 now 的出现——带 until 的有界查询在 [now, until] 内不可能再有界内
    // 出现，按完结语义返回 null（上一段快路径的精确兜底）；不带 until 的查询
    // 答案不可表示，原样重抛契约异常
    try {
        if (!lunar || repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
            // 选择器路径（构造期已保证：非农历、单位匹配、锚点日自身是规则日）——
            // weekdays 配周重复走周格点，配月重复或 monthDays 走月选择器格点
            if (weekdays.isNotEmpty() || monthDays.isNotEmpty()) {
                return if (repeatUnit == RepeatUnit.WEEK) {
                    nextWeekdayTarget(date, time, now, zone, until)
                } else {
                    nextMonthSelectorTarget(date, time, now, zone, until)
                }
            }

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
                // epoch 秒差而非 Duration：Duration 表示分档（约 292 年内纳秒、更长
                // 降为毫秒），整型直算不涉档位与分量换算
                var step =
                    maxOf(
                        1L,
                        (now.epochSeconds - anchor.epochSeconds - ZONE_DISCONTINUITY_SLACK_SECONDS) /
                            (periodDays * 86400),
                    )
                while (true) {
                    // 次数封顶先于范围守护：step 即出现序号（锚点为 0），完结返回 null
                    if (beyondCount(step)) return null
                    val days = step * periodDays
                    if (days > MAX_SUPPORTED_EPOCH_DAYS - date.toEpochDays()) throw solarRangeError()
                    val next =
                        compose(date + DatePeriod(days = days.toInt()), time, zone)
                    if (next >= now) return capped(next, until)
                    step++
                }
            }

            // 月/年重复以锚点日为基准收缩回弹（1/31 → 2/28 → 3/31），须逐步自锚点推进；
            // 迭代数 = 日历距离 ÷ 间隔，随锚点距今变远线性增长：数十年数百次，最坏
            // （公元 1 年锚点月重复推到 9999 年）约 12 万次，实测 JVM 数十毫秒——
            // 量级有界，无需直算快路径
            var step = repeatInterval.toLong()
            while (true) {
                // 出现序号 = step ÷ 间隔（锚点为 0），次数封顶先于范围守护
                if (beyondCount(step / repeatInterval)) return null
                val nextDate =
                    when (repeatUnit) {
                        RepeatUnit.MONTH -> {
                            if (date.year * 12L + date.month.ordinal + step > 9999L * 12 + 11) throw solarRangeError()
                            date + DatePeriod(months = step.toInt())
                        }

                        else -> {
                            if (date.year + step > 9999L) throw solarRangeError()
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
    } catch (e: ProjectionRangeException) {
        if (until == null) throw e
        return null
    }
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
 * 一致：两者遍历的是同一条出现序列；小时/分钟重复同为真实时长格点（不晚于
 * before 的最后一步，同样不受 0001..9999 日期界守护约束）。周/月选择器与
 * [Schedule.repeatCount] 封顶同样与 [nextTarget] 对偶——同一条出现序列上的
 * 「最后一个不晚于 before 的出现」，封顶后不返回序号越限的出现。
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

    if (repeatUnit == RepeatUnit.HOUR || repeatUnit == RepeatUnit.MINUTE) {
        // 与 nextTarget 同一真实时长格点：不晚于 before 的最后一步（含等于）。
        // epoch 秒向下取整保证该步出现必然不晚于 before，无需回退；次数封顶把
        // 步数钳到 count-1（锚点为 0）
        val periodSeconds =
            if (repeatUnit == RepeatUnit.HOUR) {
                repeatInterval * 3600L
            } else {
                repeatInterval * 60L
            }
        val anchorSeconds = anchor.epochSeconds
        var step = (before.epochSeconds - anchorSeconds) / periodSeconds
        if (repeatCount > 0) step = minOf(step, repeatCount - 1L)
        return Instant.fromEpochSeconds(anchorSeconds + step * periodSeconds)
    }

    if (!lunar || repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
        // 选择器路径与 nextTarget 对偶（构造期语义保证见彼处注释）
        if (weekdays.isNotEmpty() || monthDays.isNotEmpty()) {
            return if (repeatUnit == RepeatUnit.WEEK) {
                previousWeekdayTarget(date, time, before, zone)
            } else {
                previousMonthSelectorTarget(date, time, before, zone)
            }
        }

        if (repeatUnit == RepeatUnit.DAY || repeatUnit == RepeatUnit.WEEK) {
            // 与 nextTarget 对偶：周期秒偏大估计（留时区不连续余量）得到步数上界，
            // 再回退到最后一个不晚于 before 的出现；次数封顶同样把步数上界钳到
            // count-1（step 即出现序号，锚点为 0）
            val periodDays =
                if (repeatUnit == RepeatUnit.DAY) {
                    repeatInterval.toLong()
                } else {
                    repeatInterval * 7L
                }
            // epoch 秒差而非 Duration：Duration 表示分档（约 292 年内纳秒、更长
            // 降为毫秒），整型直算不涉档位与分量换算
            val estimate =
                maxOf(
                    0L,
                    (before.epochSeconds - anchor.epochSeconds + ZONE_DISCONTINUITY_SLACK_SECONDS) /
                        (periodDays * 86400),
                )
            val maxSteps = (MAX_SUPPORTED_EPOCH_DAYS - date.toEpochDays()) / periodDays
            var step = minOf(estimate, maxSteps)
            if (repeatCount > 0) step = minOf(step, repeatCount - 1L)
            while (true) {
                val next = compose(date + DatePeriod(days = (step * periodDays).toInt()), time, zone)
                if (next <= before) return next
                step--
            }
            // step=0 即锚点组合，前置已保证不晚于 before，回退必在 step≥0 停止
        }

        // 月/年：格点同 nextTarget（锚点日为基准收缩回弹），以日历距离偏大估计
        // 步数上界再回退；before 超出可表示年份时按上界日期估计（格点必在范围内）。
        // 次数封顶把步数上界钳到 (count-1)×间隔——出现序号 = step ÷ 间隔
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
        if (repeatCount > 0) step = minOf(step, (repeatCount - 1L) * repeatInterval)
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
 * 出现序列枚举：自首个不早于 [now] 的出现起惰性产出（含恰等于 now），重复沿用
 * [nextTarget] 的全部语义——夏令时缺口顺延/重叠取较早、农历重复、选择器规则、
 * [Schedule.repeatCount] 与 [until] 的完结判定（任一界到即序列终止）。
 *
 * 首个元素恒等于 `nextTarget(now, zone, until)`；targetDay 未选得空序列；不重复
 * 日程得单元素序列（目标时刻本身，已过也原样产出）。取前 N 个出现用
 * `occurrences(now, zone).take(n)`。
 *
 * 每个元素的成本约等于一次 [nextTarget] 调用（含远期快路径），适合「未来 N 次
 * 预览」类 UI 场景；大跨度全量枚举（数万次出现）请评估成本后使用。
 *
 * @param now 序列起点：早于 now 的出现不产出
 * @param zone 组合出现时刻的时区（与 [nextTarget] 的 zone 同语义，成对传）
 * @param until 可选时间上限：出现晚于 until 即序列终止（完结语义见 [nextTarget]）
 */
public fun Schedule.occurrences(
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
    until: Instant? = null,
): Sequence<Instant> =
    sequence {
        if (targetDay == -1L) return@sequence
        // 不重复日程不推进（nextTarget 对已过目标恒返回原值），序列即锚点单元素——
        // 未到产出锚点、已过也原样产出（与 nextTarget 的「已过原样返回」语义一致）
        if (repeatInterval <= 0 || repeatUnit == RepeatUnit.NONE) {
            yield(compose(targetDate(), resolveTime(), zone))
            return@sequence
        }
        var next = nextTarget(now, zone, until)
        while (next != null) {
            yield(next)
            // 产出后自其后一纳秒继续：重复日程的出现严格推进（出现时刻均为秒对齐，
            // 1 纳秒不会跨过任何出现）
            next = nextTarget(next + 1.nanoseconds, zone, until)
        }
    }

/**
 * 周+星期选择器正向推算：自锚点周（ISO 周，周一起始）起每 [Schedule.repeatInterval]
 * 周为一个格点周，选中星期各产生一次出现，时刻逐出现按「日期 + 时刻 + 时区」
 * 组合（夏令时缺口顺延/重叠取较早的既有语义不变）。周索引自 now 的本地周直入
 * ——更早的周整体早于 now（本地日期序蕴含时刻序），无需逐周组合；锚点周内早于
 * 锚点日的规则日不是出现（锚点恒为第一次出现，锚点星期在集合内已由构造期保证）。
 *
 * 次数封顶以闭式出现序号判定：周 0 自锚点星期起共 k−rank₀ 个出现（rank₀ 为锚点
 * 星期在升序集合中的位次），其后每周整 k 个。范围守护逐候选执行——周内升序，
 * 首个越界候选即抛，位于 nextTarget 的 [ProjectionRangeException] 分流之内。
 */
private fun Schedule.nextWeekdayTarget(
    date: LocalDate,
    time: LocalTime,
    now: Instant,
    zone: TimeZone,
    until: Instant?,
): Instant? {
    val sorted = weekdays.sorted()
    val anchorRank = sorted.indexOf(date.dayOfWeek.isoDayNumber)
    val anchorEpochDays = date.toEpochDays()
    val weekStart = anchorEpochDays - (date.dayOfWeek.isoDayNumber - 1)
    val stride = repeatInterval * 7L

    // now 超出 LocalDate 可表示年份（>9999）时取不到本地日期：以极值参与估计，
    // 随后候选日期的范围守护立即拦截
    val nowEpochDays =
        runCatching { now.toLocalDateTime(zone).date.toEpochDays() }.getOrDefault(Int.MAX_VALUE.toLong())
    var week = maxOf(0L, (nowEpochDays - weekStart) / stride)

    while (true) {
        for (rank in sorted.indices) {
            val dayEpochDays = weekStart + week * stride + (sorted[rank] - 1)
            if (week == 0L && dayEpochDays < anchorEpochDays) continue
            if (dayEpochDays > MAX_SUPPORTED_EPOCH_DAYS) throw solarRangeError()
            val target = compose(LocalDate.fromEpochDays(dayEpochDays.toInt()), time, zone)
            if (target >= now) {
                if (beyondCount(weekdayOccurrenceIndex(sorted.size, anchorRank, week, rank))) return null
                return capped(target, until)
            }
        }
        week++
    }
}

/**
 * 周+星期选择器反向推算：自 before 的本地周回退、周内降序扫描，取最后一个不晚于
 * before 的出现（含恰等于）。起始周另受两重钳制：范围上界（界外候选直接跳过、
 * 反向语义不抛）与次数封顶（count-1 序号所在周——封顶后答案必不晚于该周，避免
 * 远期 before 逐周回退）。回退终止于锚点：锚点不晚于 before 已由调用方前置保证。
 */
private fun Schedule.previousWeekdayTarget(
    date: LocalDate,
    time: LocalTime,
    before: Instant,
    zone: TimeZone,
): Instant {
    val sorted = weekdays.sorted()
    val anchorRank = sorted.indexOf(date.dayOfWeek.isoDayNumber)
    val anchorEpochDays = date.toEpochDays()
    val weekStart = anchorEpochDays - (date.dayOfWeek.isoDayNumber - 1)
    val stride = repeatInterval * 7L

    val beforeEpochDays =
        runCatching { before.toLocalDateTime(zone).date.toEpochDays() }.getOrDefault(Long.MAX_VALUE)
    val maxSupportedWeek = (MAX_SUPPORTED_EPOCH_DAYS - weekStart) / stride
    var week =
        minOf(
            maxOf(0L, (beforeEpochDays - weekStart) / stride),
            maxOf(0L, maxSupportedWeek),
        )
    if (repeatCount > 0) week = minOf(week, weekdayOccurrenceWeek(sorted.size, anchorRank, repeatCount - 1L))

    while (true) {
        for (rank in sorted.indices.reversed()) {
            val dayEpochDays = weekStart + week * stride + (sorted[rank] - 1)
            if (week == 0L && dayEpochDays < anchorEpochDays) continue
            if (dayEpochDays > MAX_SUPPORTED_EPOCH_DAYS) continue
            val target = compose(LocalDate.fromEpochDays(dayEpochDays.toInt()), time, zone)
            if (target <= before) {
                // 封顶序号之后的出现不存在：继续向更早候选回退（降序扫描保证后续
                // 序号递减，锚点处必然满足）
                if (beyondCount(weekdayOccurrenceIndex(sorted.size, anchorRank, week, rank))) continue
                return target
            }
        }
        week--
    }
}

/** 周选择器出现序号（0 基，锚点为 0）：周 0 自锚点星期起，其后每周整 [weekSize] 个 */
private fun weekdayOccurrenceIndex(
    weekSize: Int,
    anchorRank: Int,
    week: Long,
    rank: Int,
): Long =
    if (week == 0L) {
        (rank - anchorRank).toLong()
    } else {
        (weekSize - anchorRank) + (week - 1) * weekSize + rank
    }

/** 周选择器序号 [index]（0 基）所在的格点周（[weekdayOccurrenceIndex] 的逆运算之一） */
private fun weekdayOccurrenceWeek(
    weekSize: Int,
    anchorRank: Int,
    index: Long,
): Long {
    val anchorWeekCount = (weekSize - anchorRank).toLong()
    return if (index < anchorWeekCount) 0L else 1 + (index - anchorWeekCount) / weekSize
}

/**
 * 月+选择器正向推算：自锚点月按 [Schedule.repeatInterval] 步进，每月以
 * [resolveMonthDays] 解析命中日（显式选择器取代锚点日收缩回弹），锚点月过滤
 * 早于锚点日的规则日。整月早于 now 本地日期的月份只推进出现计数、不逐出现组合
 * （月首日序蕴含时刻序）；边界月逐出现组合取首个不早于 now 者。次数封顶在计数
 * 上判定（锚点为第 1 次出现），完结返回 null。范围守护位于 nextTarget 的
 * [ProjectionRangeException] 分流之内。
 */
private fun Schedule.nextMonthSelectorTarget(
    date: LocalDate,
    time: LocalTime,
    now: Instant,
    zone: TimeZone,
    until: Instant?,
): Instant? {
    val anchorMonthIndex = date.year * 12L + date.month.ordinal
    // now 超出 LocalDate 可表示年份（>9999）时取不到本地日期：以极值参与跳过区
    // 判定，随后月份范围守护立即拦截
    val nowDate = runCatching { now.toLocalDateTime(zone).date }.getOrDefault(MAX_SUPPORTED_DATE)
    var seen = 0L

    var monthIndex = anchorMonthIndex
    while (true) {
        if (monthIndex > 9999L * 12 + 11) throw solarRangeError()
        val year = (monthIndex / 12).toInt()
        val monthNumber = (monthIndex % 12).toInt() + 1
        val days = resolveMonthDays(year, monthNumber, monthDays, weekdays, weekdayOrdinals, clampMonthDays)
        val occurrences =
            if (monthIndex == anchorMonthIndex) {
                days.filter { it >= date.day }
            } else {
                days
            }
        if (occurrences.isEmpty()) {
            monthIndex += repeatInterval
            continue
        }
        // 整月早于 now：候选必然已过（本地日期序蕴含时刻序），只推进计数；
        // 该月末出现的序号越过封顶即完结（首个不早于 now 的出现序号只会更大）
        if (LocalDate(year, monthNumber, occurrences.last()) < nowDate) {
            seen += occurrences.size
            if (beyondCount(seen - 1)) return null
            monthIndex += repeatInterval
            continue
        }
        for (day in occurrences) {
            seen++
            val target = compose(LocalDate(year, monthNumber, day), time, zone)
            if (target >= now) {
                if (beyondCount(seen - 1)) return null
                return capped(target, until)
            }
        }
        monthIndex += repeatInterval
    }
}

/**
 * 月+选择器反向推算：自锚点月正向步进（与正向路径同构——出现序列单调，最后一个
 * 不晚于 before 者可沿正向遍历取得，避免回退估计在空月上失准）。整月不晚于
 * before 的月份只记录最近格点不组合；边界月逐出现组合，首个晚于 before 的候选
 * 出现即收尾。次数封顶：跳过区计数到达 count 时第 count 次出现的日号可由计数
 * 直定位（该月整体不晚于 before），组合即返；边界区取到第 count 次出现即返。
 * 推至范围上界仍未晚于 before 时返回界内最后一次出现（previous 语义不抛）。
 */
private fun Schedule.previousMonthSelectorTarget(
    date: LocalDate,
    time: LocalTime,
    before: Instant,
    zone: TimeZone,
): Instant {
    val anchorMonthIndex = date.year * 12L + date.month.ordinal
    val beforeDate = runCatching { before.toLocalDateTime(zone).date }.getOrDefault(MAX_SUPPORTED_DATE)
    var seen = 0L
    var lastBelow: Instant? = null
    // 跳过区最近格点（必然不晚于 before、未组合），收尾回退用；初值即锚点本身
    var pendingYear = date.year
    var pendingMonthNumber = date.month.ordinal + 1
    var pendingDay = date.day

    fun pendingOccurrence(): Instant = compose(LocalDate(pendingYear, pendingMonthNumber, pendingDay), time, zone)

    var monthIndex = anchorMonthIndex
    while (true) {
        if (monthIndex > 9999L * 12 + 11) return lastBelow ?: pendingOccurrence()
        val year = (monthIndex / 12).toInt()
        val monthNumber = (monthIndex % 12).toInt() + 1
        val days = resolveMonthDays(year, monthNumber, monthDays, weekdays, weekdayOrdinals, clampMonthDays)
        val occurrences =
            if (monthIndex == anchorMonthIndex) {
                days.filter { it >= date.day }
            } else {
                days
            }
        if (occurrences.isEmpty()) {
            monthIndex += repeatInterval
            continue
        }
        // 整月不晚于 before：候选必然不晚于 before，只记录格点与计数；计数到达
        // count 时该月内直定位第 count 次出现组合即返（封顶后它就是最后的存在出现）
        if (LocalDate(year, monthNumber, occurrences.last()) < beforeDate) {
            if (repeatCount > 0 && seen + occurrences.size >= repeatCount) {
                val cappedDay = occurrences[(repeatCount - seen - 1).toInt()]
                return compose(LocalDate(year, monthNumber, cappedDay), time, zone)
            }
            seen += occurrences.size
            pendingYear = year
            pendingMonthNumber = monthNumber
            pendingDay = occurrences.last()
            monthIndex += repeatInterval
            continue
        }
        for (day in occurrences) {
            seen++
            val target = compose(LocalDate(year, monthNumber, day), time, zone)
            if (target > before) return lastBelow ?: pendingOccurrence()
            lastBelow = target
            if (repeatCount > 0 && seen >= repeatCount) return target
        }
        monthIndex += repeatInterval
    }
}

/**
 * 倒计时状态：时长先向上取整到完整秒（tick 与秒边界不对齐时进位不闪跳；
 * 目标与当前恰为同一瞬间时输出 0 秒），再细分出展示量级；已过的时间对称细分。
 *
 * 等价于三参重载取 [Rounding.TRUNCATE]，逻辑统一由其实现。本重载留在本文件以
 * 维持已发布版本的 JVM 二进制兼容（编译产物为 NextTimeKt.countdown），勿移动。
 */
public fun countdown(
    target: Instant,
    now: Instant,
): Countdown = countdown(target, now, Rounding.TRUNCATE)

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
 * 不会死循环；农历 9999 年的末月跨入公历 10000 年，候选先按年表日数定位、
 * 拉出支持范围即按越界处理（不依赖 tyme 对越界换算的报错形态——其年表越界
 * 的另一形态是静默返回错值）。长跨度走快路径：早于 now 两年的农历年跳过候选
 * 换算，年表按年缓存。
 *
 * 锚点（第一次出现）由调用方在进入前处理：anchor 不早于 now 时已提前返回，
 * 函数内的候选均为非锚点出现，返回值一律受 until 封顶。出现计数（1 基，锚点为
 * 1）在非零候选上推进——跳过区不组合但同样计数，[Schedule.repeatCount] 耗尽
 * 即返回 null。
 *
 * @param until 重复出现的可选上限（见 [nextTarget]）
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

    val table = LunarYearTable()

    // now 超出 LocalDate 可表示年份（>9999）时取不到本地年份：以极值参与快路径
    // 比较，候选全部视为已过、只步进，随后年份越界守护立即失败
    val nowYear = runCatching { now.toLocalDateTime(zone).year }.getOrDefault(Int.MAX_VALUE)

    // 出现计数（1 基，锚点为 1）：每个非零候选都是一次出现——跳过区不组合但同样
    // 计数，次数封顶的完结判定不依赖历法换算
    var seen = 0L

    while (true) {
        table.refresh(year)

        val candidate = candidateMonth(month, table.leapMonth, leapCount)

        if (candidate != 0) {
            // 先判次数封顶再进快路径：跳过区的候选同样受封顶约束
            seen++
            if (beyondCount(seen - 1)) return null

            // 农历年 Y 的候选最晚落在公历次年春节前（约次年 2 月中）：Y 早于 now 两年
            // 及以上时候选必然已过，跳过昂贵的历法换算只步进（跨数十年即数百次换算）
            if (year > nowYear - 2) {
                val day = minOf(first.day, LunarMonth.fromYm(year, candidate).getDayCount())

                // 农历 9999 年的末月跨入公历 10000 年：候选先按年表日数定位（见
                // LunarYearTable.beyondSupportedRange），拉出支持范围即按年表越界处理，
                // 不把越界换算交给 tyme——其年表越界的另一形态是静默返回错值，防线
                // 不能依赖它抛异常
                if (table.beyondSupportedRange(candidate, day)) throw lunarRangeError()

                val solar = LunarDay.fromYmd(year, candidate, day).getSolarDay()

                val target = compose(LocalDate(solar.year, solar.month, solar.day), time, zone)

                if (target >= now) return capped(target, until)
            }
        }

        if (repeatUnit == RepeatUnit.MONTH) {
            repeat(repeatInterval) {
                // 内层逐月推进的年份越界检查在 stepLunarMonth 的 refresh 内，
                // 越过界即抛（next 语义）
                val stepped = stepLunarMonth(table, leapCount, year, month)
                year = stepped.first
                month = stepped.second
            }
        } else {
            year += repeatInterval
        }
    }
}

/**
 * 农历月/年重复的反向推算：取出现序列中不晚于 before 的最后一项。结构与
 * [nextLunarTarget] 对偶——同样自锚点格点正向步进，只是把「首个不早于 now」
 * 换成「最后一个不晚于 before」：早于 before 两年的农历年跳过候选换算、只记录
 * 格点（这些候选必然不晚于 before，留作回退位），组合区一旦出现晚于 before 的
 * 候选即返回上一个不晚于者。推算至年表上界仍未晚于 before 时返回界内最后一次
 * 出现，不抛异常（出现序列在界内有尽）；农历 9999 年末月落入公历 10000 年的
 * 界外候选同此处理——按年表日数先行定位判定，界外者不参与组合。出现计数
 * （1 基，锚点为 1）沿格点推进，[Schedule.repeatCount] 到达 count 时该格点即
 * 封顶后的最后存在出现——落在跳过区内时直接组合返回。
 */
private fun Schedule.previousLunarTarget(
    first: LunarDay,
    time: LocalTime,
    before: Instant,
    zone: TimeZone,
): Instant {
    var year = first.year
    var month = first.month

    val table = LunarYearTable()

    // 日收缩 + 历法换算 + 组合；跳过区回退时也要组合记录的格点，单处复用。仅用于
    // 界内格点（初始锚点自带界内公历日，跳过区只记录年份 < 9999 的格点，农历年
    // 9998 及更早整年落在支持范围内）
    fun composeGrid(
        y: Int,
        m: Int,
    ): Instant {
        val day = minOf(first.day, LunarMonth.fromYm(y, m).getDayCount())
        val solar = LunarDay.fromYmd(y, m, day).getSolarDay()
        return compose(LocalDate(solar.year, solar.month, solar.day), time, zone)
    }

    // 组合候选格点：农历 9999 年末月跨入公历 10000 年的界外候选返回 null，由
    // 调用方按界外处理。界先按年表日数定位（见 LunarYearTable.beyondSupportedRange），
    // 不把越界换算交给 tyme——其年表越界的另一形态是静默返回错值，防线不能
    // 依赖它抛异常
    fun composeCandidate(
        y: Int,
        m: Int,
    ): Instant? {
        val day = minOf(first.day, LunarMonth.fromYm(y, m).getDayCount())
        if (table.beyondSupportedRange(m, day)) return null
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

    // 出现计数（1 基，锚点为 1）：跳过区格点不组合但同样计数——计数到达 count 时
    // 该格点即封顶后的最后存在出现，在跳过区内直接组合返回，无需走到组合区
    var seen = 0L

    while (true) {
        if (year > MAX_LUNAR_YEAR) return lastBelow ?: composeGrid(pendingYear, pendingMonth)

        table.refresh(year)

        val candidate = candidateMonth(month, table.leapMonth, leapCount)

        if (candidate != 0) {
            seen++
            // 与 nextLunarTarget 的跳过条件对偶：农历年 Y 的候选最晚落在公历次年
            // 春节前（约次年 2 月中），Y 早于 before 两年及以上时候选必然不晚于
            // before，跳过昂贵的历法换算只记录格点（跨数十年即数百次换算）。
            // 9999 年不进跳过区——它的末月候选可落入公历 10000 年，须组合出时刻
            // 才能判定界内界外，界外格点不得留在回退位
            if (year <= beforeYear - 2 && year < MAX_LUNAR_YEAR) {
                if (repeatCount > 0 && seen >= repeatCount) return composeGrid(year, candidate)
                pendingYear = year
                pendingMonth = candidate
            } else {
                // 界外候选视同晚于 before：出现序列此后必然全部界外，返回上一个
                // 界内出现即「界内最后一次出现」
                val target = composeCandidate(year, candidate)
                if (target == null || target > before) return lastBelow ?: composeGrid(pendingYear, pendingMonth)
                lastBelow = target
                // 第 count 次出现已取得且不晚于 before：它就是封顶后的答案
                if (repeatCount > 0 && seen >= repeatCount) return target
            }
        }

        if (repeatUnit == RepeatUnit.MONTH) {
            repeat(repeatInterval) {
                // 内层逐月推进不经过外层循环顶部的年份检查：previous 语义在界前
                // 收尾返回（不抛异常），须赶在 stepLunarMonth 的 refresh 抛出前返回
                if (year > MAX_LUNAR_YEAR) return lastBelow ?: composeGrid(pendingYear, pendingMonth)
                val stepped = stepLunarMonth(table, leapCount, year, month)
                year = stepped.first
                month = stepped.second
            }
        } else {
            year += repeatInterval
        }
    }
}

/**
 * 农历年表越界错误：越界年表数据不可信（tyme 静默返回错值），且规则可能永远
 * 无法命中。带 until 查询的完结分流见 [ProjectionRangeException]。
 */
private fun lunarRangeError(): ProjectionRangeException =
    ProjectionRangeException(
        "Lunar projection left the reliable lunar calendar range $MIN_LUNAR_YEAR..$MAX_LUNAR_YEAR; the repeat rule may never match",
    )

/**
 * 农历年表缓存：同一农历年的连续步进复用 LunarYear 与月表，跨年时重建并做
 * 年份越界检查（抛 [lunarRangeError]）。正反向推算共用同一实现。
 */
private class LunarYearTable {
    var year: Int = Int.MIN_VALUE
        private set

    /** 当年闰月（闰 m 月为 m，0 表示无闰月；tyme 的 getLeapMonth 恒非负，负数月序仅存在于 LunarMonth/LunarDay 的月份值） */
    var leapMonth: Int = 0
        private set

    /** 当年全部月表（含闰月）；月表项自带所属农历年 */
    var months: List<LunarMonth> = emptyList()
        private set

    /** 过滤闰月后的月表（闰月不参与步进时使用） */
    var normalMonths: List<LunarMonth> = emptyList()
        private set

    fun refresh(year: Int) {
        if (year !in MIN_LUNAR_YEAR..MAX_LUNAR_YEAR) throw lunarRangeError()
        if (this.year == year) return
        val lunarYear = LunarYear.fromYear(year)
        this.year = year
        leapMonth = lunarYear.getLeapMonth()
        months = lunarYear.getMonths()
        normalMonths = months.filter { it.getMonthWithLeap() > 0 }
    }

    /**
     * 当年候选（月 + 收缩后日）是否越出支持范围 9999-12-31。农历年 ≤ 9998 的
     * 全部日子都早于正月初一(9999)——春节恒落在公历 1/21..2/21，结构性地界内；
     * 只有 9999 年的末月可跨入公历 10000 年，自正月初一累加月表天数定位
     * （9999 年远在 1582 历法缺口之后，纯日数推算无历法偏差）。界判定只做
     * 日数算术，不把越界换算交给 tyme——其年表越界的另一形态是静默返回错值。
     */
    fun beyondSupportedRange(
        month: Int,
        day: Int,
    ): Boolean {
        if (year != MAX_LUNAR_YEAR) return false
        val cny = LunarDay.fromYmd(year, 1, 1).getSolarDay()
        var start = LocalDate(cny.year, cny.month, cny.day).toEpochDays()
        for (m in months) {
            if (m.getMonthWithLeap() == month) break
            start += m.getDayCount()
        }
        return start + day - 1 > MAX_SUPPORTED_EPOCH_DAYS
    }
}

/** 当年候选月：闰月日日程遇「当年无该闰月且闰月不参与」时无候选（0），闰月参与时退化为普通月（-month）。 */
private fun candidateMonth(
    month: Int,
    leapMonth: Int,
    leapCount: Boolean,
): Int =
    when {
        month > 0 || leapMonth == -month -> month
        leapCount -> -month
        else -> 0
    }

/**
 * 沿农历月序自 (year, month) 推进一步，闰月是否在月表内由 [leapCount] 决定。
 * 年份越界检查由内部的 [LunarYearTable.refresh] 承担，越界即抛（next 语义）；
 * previous 语义的调用方须在界前自行收尾返回。
 */
private fun stepLunarMonth(
    table: LunarYearTable,
    leapCount: Boolean,
    year: Int,
    month: Int,
): Pair<Int, Int> {
    table.refresh(year)
    val months = if (leapCount) table.months else table.normalMonths

    // 月表项自带所属农历年，必须同年同月匹配，否则冬月/腊月会匹配到相邻年份的
    // 同名月导致年份不推进、死循环
    var index = months.indexOfFirst { it.getMonthWithLeap() == month && it.year == year }

    // 闰月被过滤时（闰月日日程 + 闰月不参与），按普通月定位继续沿月序推进
    if (index < 0 && month < 0) {
        index = months.indexOfFirst { it.getMonthWithLeap() == -month && it.year == year }
    }

    if (index in 0 until months.size - 1) {
        // 月表项自带所属农历年，后继的年份与月份须一并采用、不能沿用当前 year——
        // 否则跨年处的年份不推进、在当年正月与腊月间死循环
        val next = months[index + 1]
        return next.year to next.getMonthWithLeap()
    }
    // 末项（当年腊月，tyme4kt 的月表只含当年 12/13 个月）的后继是次年正月
    return year + 1 to 1
}

/**
 * 确保平台的 IANA 时区库可用：wasm 平台的 kotlinx-datetime 依赖
 * @js-joda/timezone 以副作用注入时区库，须在其首次使用前加载。
 */
internal expect fun ensureIanaTzdb()
