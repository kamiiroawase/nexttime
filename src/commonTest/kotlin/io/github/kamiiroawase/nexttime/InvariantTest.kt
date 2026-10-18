package io.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** 结果不变量：对一组典型重复日程断言普适性质，防回归 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class InvariantTest {
    private val apia: TimeZone = TimeZone.of("Pacific/Apia")

    private val repeatingSchedules =
        listOf(
            schedule(utcMillis(LocalDate(2020, 1, 1)), interval = 1, unit = 4),
            schedule(utcMillis(LocalDate(2026, 8, 20)), interval = 2, unit = 1),
            schedule(utcMillis(LocalDate(2026, 8, 10)), interval = 2, unit = 2),
            schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = 3),
            schedule(solarMillis(LunarDay.fromYmd(2025, 6, 10).getSolarDay()), lunar = true, interval = 1, unit = 4),
            schedule(solarMillis(LunarDay.fromYmd(2025, 5, 1).getSolarDay()), lunar = true, interval = 1, unit = 3),
            schedule(solarMillis(LunarDay.fromYmd(2025, -6, 5).getSolarDay()), lunar = true, leapCount = false, interval = 1, unit = 3),
            schedule(solarMillis(LunarDay.fromYmd(2026, 6, 1).getSolarDay()), lunar = true, interval = 1, unit = 3),
            // 锚点在未来、多间隔月末收缩、闰日多间隔回弹、闰月计入步数等形态
            schedule(utcMillis(LocalDate(2026, 12, 24)), 10, 0, 0, interval = 7, unit = 2),
            schedule(utcMillis(LocalDate(2025, 12, 31)), interval = 2, unit = 3),
            schedule(utcMillis(LocalDate(2024, 2, 29)), interval = 2, unit = 4),
            schedule(solarMillis(LunarDay.fromYmd(2025, 5, 1).getSolarDay()), lunar = true, leapCount = true, interval = 2, unit = 3),
            // 1970 前锚点、农历间隔 2 年重复、闰月参与年重复（Apia 时区见 cases）
            schedule(utcMillis(LocalDate(1965, 6, 15)), interval = 1, unit = 4),
            schedule(solarMillis(LunarDay.fromYmd(2025, 6, 10).getSolarDay()), lunar = true, interval = 2, unit = 4),
            schedule(solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()), lunar = true, leapCount = true, interval = 1, unit = 4),
            // 周选择器：每周一三五、间隔 2 周的周三维度、锚点周部分（锚点周三）
            schedule(utcMillis(LocalDate(2026, 8, 10)), 9, 0, 0, interval = 1, unit = 2, weekdays = setOf(1, 3, 5)),
            schedule(utcMillis(LocalDate(2026, 8, 12)), interval = 2, unit = 2, weekdays = setOf(3, 4)),
            // 月选择器：每月最后一天、每 2 月的 15 号、每月第二个周二、每月全部周一
            schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = 3, monthDays = setOf(-1)),
            schedule(utcMillis(LocalDate(2026, 1, 15)), interval = 2, unit = 3, monthDays = setOf(15)),
            schedule(utcMillis(LocalDate(2026, 9, 8)), interval = 1, unit = 3, weekdays = setOf(2), weekdayOrdinals = setOf(2)),
            schedule(utcMillis(LocalDate(2026, 9, 7)), interval = 1, unit = 3, weekdays = setOf(1)),
            // 收缩模式多日：{30, 31} 在短月收月末
            schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = 3, monthDays = setOf(30, 31), clampMonthDays = true),
        )

    // 扫多个 now：2027-03 已跨过 2026 农历年（无闰月）的腊月边界，
    // 农历月步进若不在跨年处推进年份会在此死循环；纽约/Apia 时区扫 DST 与跳日组合
    private val cases: List<Pair<kotlin.time.Instant, TimeZone>> =
        listOf(
            zdt(2026, 8, 23, 12, 0, 0) to shanghai,
            zdt(2027, 3, 15, 12, 0, 0) to shanghai,
            instantOf(newYork, 2026, 8, 23, 12, 0, 0) to newYork,
            instantOf(newYork, 2027, 3, 15, 12, 0, 0) to newYork,
            instantOf(apia, 2026, 8, 23, 12, 0, 0) to apia,
        )

    @Test
    fun `重复日程结果不早于now且倒计时未过`() {
        for ((now, zone) in cases) {
            for (schedule in repeatingSchedules) {
                val target = schedule.nextTarget(now, zone)!!
                assertFalse(target < now, "结果早于 now: $target")
                assertFalse(countdown(target, now).past, "倒计时误报已过: $target")
            }
        }
    }

    @Test
    fun `now越过目标后得到更晚的目标`() {
        for ((now, zone) in cases) {
            for (schedule in repeatingSchedules) {
                val first = schedule.nextTarget(now, zone)!!
                val second = schedule.nextTarget(first + 1.seconds, zone)!!
                assertTrue(second > first, "目标未推进: $first -> $second")
            }
        }
    }

    @Test
    fun `previousTarget结果不晚于before`() {
        // 锚点晚于 now 的日程（如 2026-12-24 锚点配 2026-08 的 now）返回 null，
        // 非空结果须不晚于 before
        for ((now, zone) in cases) {
            for (schedule in repeatingSchedules) {
                val previous = schedule.previousTarget(now, zone) ?: continue
                assertFalse(previous > now, "结果晚于 before: $previous")
            }
        }
    }

    @Test
    fun `previousTarget与nextTarget在同一出现上对偶`() {
        // next 后一瞬的反向推算回到 next 本身：next 是该瞬间「不晚于的最后出现」
        for ((now, zone) in cases) {
            for (schedule in repeatingSchedules) {
                val next = schedule.nextTarget(now, zone)!!
                assertEquals(next, schedule.previousTarget(next + 1.nanoseconds, zone))
            }
        }
    }

    @Test
    fun `previousTarget不晚于nextTarget`() {
        // 同一 now：不晚于 now 的最后出现不早于不早于 now 的首次出现（now 恰为
        // 出现时两者相等）
        for ((now, zone) in cases) {
            for (schedule in repeatingSchedules) {
                val previous = schedule.previousTarget(now, zone) ?: continue
                assertFalse(previous > schedule.nextTarget(now, zone)!!, "previous 晚于 next: $previous")
            }
        }
    }

    @Test
    fun `星期选择器的出现落在选中星期上`() {
        val weekdaySchedules = repeatingSchedules.filter { it.weekdays.isNotEmpty() }
        for ((now, zone) in cases) {
            for (schedule in weekdaySchedules) {
                val next = schedule.nextTarget(now, zone)!!
                // 锚点晚于 now 的日程（9 月锚点配 8 月 case）反向合法返回 null
                val previous = schedule.previousTarget(now, zone) ?: continue
                for (target in listOf(next, previous)) {
                    val weekday = dateOf(target, zone).dayOfWeek.isoDayNumber
                    assertTrue(weekday in schedule.weekdays, "出现落在未选中星期: $weekday")
                }
            }
        }
    }

    @Test
    fun `月内日选择器的出现落在当月解析结果内`() {
        val monthDaySchedules = repeatingSchedules.filter { it.monthDays.isNotEmpty() }
        for ((now, zone) in cases) {
            for (schedule in monthDaySchedules) {
                val next = schedule.nextTarget(now, zone)!!
                val previous = schedule.previousTarget(now, zone) ?: continue
                for (target in listOf(next, previous)) {
                    val date = dateOf(target, zone)
                    val resolved =
                        resolveMonthDays(
                            date.year,
                            date.month.ordinal + 1,
                            schedule.monthDays,
                            schedule.weekdays,
                            schedule.weekdayOrdinals,
                            schedule.clampMonthDays,
                        )
                    assertTrue(date.day in resolved, "出现日不在解析结果内: $date")
                }
            }
        }
    }

    // 次数封顶日程组：完结判定使 next 可为 null，性质按完结与否分别断言
    private val boundedSchedules =
        listOf(
            schedule(utcMillis(LocalDate(2026, 8, 20)), interval = 1, unit = 1, repeatCount = 10),
            schedule(utcMillis(LocalDate(2026, 8, 19)), interval = 1, unit = 2, weekdays = setOf(1, 3, 5), repeatCount = 10),
            schedule(utcMillis(LocalDate(2026, 8, 20)), interval = 1, unit = 3, monthDays = setOf(5, 20), repeatCount = 8),
            schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = 3, monthDays = setOf(-1), repeatCount = 6),
            schedule(solarMillis(LunarDay.fromYmd(2025, 6, 10).getSolarDay()), lunar = true, interval = 1, unit = 4, repeatCount = 2),
        )

    @Test
    fun `次数封顶日程完结后previous钉在末次出现`() {
        for ((now, zone) in cases) {
            for (schedule in boundedSchedules) {
                val next = schedule.nextTarget(now, zone)
                val previous = schedule.previousTarget(now, zone)!!
                if (next == null) {
                    // 完结：previous 即第 count 次出现，其后一步也完结
                    assertNull(schedule.nextTarget(previous + 1.nanoseconds, zone), "完结后仍有出现: $previous")
                } else {
                    assertFalse(previous > next, "previous 晚于 next: $previous")
                    assertFalse(countdown(next, now).past)
                }
            }
        }
    }
}
