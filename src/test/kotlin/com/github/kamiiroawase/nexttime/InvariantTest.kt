package com.github.kamiiroawase.nexttime

import com.nlf.calendar.Lunar
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.LocalDate

/** 结果不变量：对一组典型重复日程断言普适性质，防回归 */
class InvariantTest {
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
        )

    // 扫多个 now：2027-03 已跨过 2026 农历年（无闰月）的腊月边界，
    // 农历月步进若不在跨年处推进年份会在此死循环
    private val nows =
        listOf(
            zdt(2026, 8, 23, 12, 0, 0),
            zdt(2027, 3, 15, 12, 0, 0),
        )

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `重复日程结果不早于now且倒计时未过`() {
        for (now in nows) {
            for (schedule in repeatingSchedules) {
                val target = schedule.nextTarget(now, shanghai)!!
                assertFalse(target.isBefore(now), "结果早于 now: $target")
                assertFalse(countdown(target, now).past, "倒计时误报已过: $target")
            }
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `now越过目标后得到更晚的目标`() {
        for (now in nows) {
            for (schedule in repeatingSchedules) {
                val first = schedule.nextTarget(now, shanghai)!!
                val second = schedule.nextTarget(first.plusSeconds(1), shanghai)!!
                assertTrue(second.isAfter(first), "目标未推进: $first -> $second")
            }
        }
    }
}
