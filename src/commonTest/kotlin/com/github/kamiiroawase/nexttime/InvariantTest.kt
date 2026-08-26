package com.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 结果不变量：对一组典型重复日程断言普适性质，防回归 */
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
}
