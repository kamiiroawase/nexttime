package com.github.kamiiroawase.nexttime

import com.nlf.calendar.Lunar
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 结果不变量：对一组典型重复日程断言普适性质，防回归 */
class InvariantTest {
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private val apia: ZoneId = ZoneId.of("Pacific/Apia")

    private val repeatingSchedules =
        listOf(
            schedule(utcMillis(LocalDate.of(2020, 1, 1)), interval = 1, unit = 4),
            schedule(utcMillis(LocalDate.of(2026, 8, 20)), interval = 2, unit = 1),
            schedule(utcMillis(LocalDate.of(2026, 8, 10)), interval = 2, unit = 2),
            schedule(utcMillis(LocalDate.of(2026, 1, 31)), interval = 1, unit = 3),
            schedule(solarMillis(Lunar.fromYmd(2025, 6, 10).solar), lunar = true, interval = 1, unit = 4),
            schedule(solarMillis(Lunar.fromYmd(2025, 5, 1).solar), lunar = true, interval = 1, unit = 3),
            schedule(solarMillis(Lunar.fromYmd(2025, -6, 5).solar), lunar = true, leapCount = false, interval = 1, unit = 3),
            schedule(solarMillis(Lunar.fromYmd(2026, 6, 1).solar), lunar = true, interval = 1, unit = 3),
            // 锚点在未来、多间隔月末收缩、闰日多间隔回弹、闰月计入步数等形态
            schedule(utcMillis(LocalDate.of(2026, 12, 24)), 10, 0, 0, interval = 7, unit = 2),
            schedule(utcMillis(LocalDate.of(2025, 12, 31)), interval = 2, unit = 3),
            schedule(utcMillis(LocalDate.of(2024, 2, 29)), interval = 2, unit = 4),
            schedule(solarMillis(Lunar.fromYmd(2025, 5, 1).solar), lunar = true, leapCount = true, interval = 2, unit = 3),
            // 1970 前锚点、农历间隔 2 年重复、闰月参与年重复（Apia 时区见 cases）
            schedule(utcMillis(LocalDate.of(1965, 6, 15)), interval = 1, unit = 4),
            schedule(solarMillis(Lunar.fromYmd(2025, 6, 10).solar), lunar = true, interval = 2, unit = 4),
            schedule(solarMillis(Lunar.fromYmd(2025, -6, 1).solar), lunar = true, leapCount = true, interval = 1, unit = 4),
        )

    // 扫多个 now：2027-03 已跨过 2026 农历年（无闰月）的腊月边界，
    // 农历月步进若不在跨年处推进年份会在此死循环；纽约时区扫 DST 边界组合
    private val cases: List<Pair<ZonedDateTime, ZoneId>> =
        listOf(
            zdt(2026, 8, 23, 12, 0, 0) to shanghai,
            zdt(2027, 3, 15, 12, 0, 0) to shanghai,
            ZonedDateTime.of(2026, 8, 23, 12, 0, 0, 0, newYork) to newYork,
            ZonedDateTime.of(2027, 3, 15, 12, 0, 0, 0, newYork) to newYork,
            ZonedDateTime.of(2026, 8, 23, 12, 0, 0, 0, apia) to apia,
        )

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `重复日程结果不早于now且倒计时未过`() {
        for ((now, zone) in cases) {
            for (schedule in repeatingSchedules) {
                val target = schedule.nextTarget(now, zone)!!
                assertFalse(target.isBefore(now), "结果早于 now: $target")
                assertFalse(countdown(target, now).past, "倒计时误报已过: $target")
            }
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `now越过目标后得到更晚的目标`() {
        for ((now, zone) in cases) {
            for (schedule in repeatingSchedules) {
                val first = schedule.nextTarget(now, zone)!!
                val second = schedule.nextTarget(first.plusSeconds(1), zone)!!
                assertTrue(second.isAfter(first), "目标未推进: $first -> $second")
            }
        }
    }
}
