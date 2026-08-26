package com.github.kamiiroawase.nexttime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** calendarCountdown：日历分量细分（钟面语义、月末钳制、闰年、与真实时长的对照） */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class CalendarCountdownTest {
    @Test
    fun `全分量年月日时分秒`() {
        // 探针对拍值：2026-08-26T10:00 → 2027-09-27T09:30 = 1年1月0天23小时30分
        val breakdown =
            calendarCountdown(zdt(2027, 9, 27, 9, 30), zdt(2026, 8, 26, 10, 0), shanghai)

        assertFalse(breakdown.past)
        assertEquals(1, breakdown.years)
        assertEquals(1, breakdown.months)
        assertEquals(0, breakdown.days)
        assertEquals(23, breakdown.hours)
        assertEquals(30, breakdown.minutes)
        assertEquals(0, breakdown.seconds)
    }

    @Test
    fun `月末钳制不足整月按天数`() {
        // 1/31 → 2/28：与 java.time Period.between 一致为 0 个月 28 天
        // （不是「+1 月钳制到月末所以差 1 个月」）
        val breakdown =
            calendarCountdown(zdt(2026, 2, 28, 10), zdt(2026, 1, 31, 10), shanghai)

        assertEquals(0, breakdown.months)
        assertEquals(28, breakdown.days)
        assertEquals(0, breakdown.hours)
    }

    @Test
    fun `闰日到次年平年`() {
        // 探针对拍值：2024-02-29 → 2025-02-28 = 0 年 11 个月 30 天
        val breakdown =
            calendarCountdown(zdt(2025, 2, 28), zdt(2024, 2, 29), shanghai)

        assertEquals(0, breakdown.years)
        assertEquals(11, breakdown.months)
        assertEquals(30, breakdown.days)
    }

    @Test
    fun `跨夏令时按钟面一天而非真实二十三小时`() {
        // 纽约 2026-03-08 01:00 → 03-09 01:00：真实 23 小时，钟面走满 24 时
        // ——日历分量为 1 天 0 小时，与 countdown() 的真实时长语义相反
        val breakdown =
            calendarCountdown(
                instantOf(newYork, 2026, 3, 9, 1),
                instantOf(newYork, 2026, 3, 8, 1),
                newYork,
            )

        assertEquals(1, breakdown.days)
        assertEquals(0, breakdown.hours)

        assertEquals(
            Countdown(false, 23, CountdownUnit.HOURS),
            countdown(instantOf(newYork, 2026, 3, 9, 1), instantOf(newYork, 2026, 3, 8, 1)),
        )
    }

    @Test
    fun `已过方向分量对称`() {
        val earlier = zdt(2026, 2, 28, 10)
        val later = zdt(2027, 3, 30, 12, 30, 15)

        // 未到：later 作为目标（earlier 视角「还有多久到 later」）；已过：earlier 作为目标
        val future = calendarCountdown(later, earlier, shanghai)
        val past = calendarCountdown(earlier, later, shanghai)

        assertFalse(future.past)
        assertTrue(past.past)
        assertEquals(future.years, past.years)
        assertEquals(future.months, past.months)
        assertEquals(future.days, past.days)
        assertEquals(future.hours, past.hours)
        assertEquals(future.minutes, past.minutes)
        assertEquals(future.seconds, past.seconds)
    }

    @Test
    fun `同一瞬间全分量为零`() {
        val now = zdt(2026, 8, 26, 12, 30, 45)

        val breakdown = calendarCountdown(now, now, shanghai)

        assertFalse(breakdown.past)
        assertEquals(0, breakdown.years)
        assertEquals(0, breakdown.months)
        assertEquals(0, breakdown.days)
        assertEquals(0, breakdown.hours)
        assertEquals(0, breakdown.minutes)
        assertEquals(0, breakdown.seconds)
    }
}
