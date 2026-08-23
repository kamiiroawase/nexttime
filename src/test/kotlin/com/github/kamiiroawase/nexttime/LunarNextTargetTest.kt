package com.github.kamiiroawase.nexttime

import com.nlf.calendar.Lunar
import com.nlf.calendar.LunarMonth
import com.nlf.calendar.LunarYear
import com.nlf.calendar.Solar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** 农历 nextTarget：以 2025 农历年（闰六月）为核心场景 */
class LunarNextTargetTest {
    private fun lunarOf(target: ZonedDateTime): Lunar = Solar.fromYmd(target.year, target.monthValue, target.dayOfMonth).lunar

    @Test
    fun `农历年重复保持农历月日`() {
        assertEquals(6, LunarYear.fromYear(2025).leapMonth)

        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 6, 10).solar),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2026, targetLunar.year)
        assertEquals(6, targetLunar.month)
        assertEquals(10, targetLunar.day)
    }

    @Test
    fun `闰月日年重复参与时当年退化普通月`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, -6, 1).solar),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2026, targetLunar.year)
        assertEquals(6, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `闰月日年重复不参与时等待真闰月年`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, -6, 1).solar),
                lunar = true,
                leapCount = false,
                interval = 1,
                unit = 4,
            )

        val now = zdt(2026, 1, 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
        assertEquals(6, LunarYear.fromYear(targetLunar.year).leapMonth)
        assertTrue(target.isAfter(now))
    }

    @Test
    fun `农历月重复不参与时跳过闰月`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 5, 1).solar),
                lunar = true,
                leapCount = false,
                interval = 1,
                unit = 3,
            )

        // now 在普通六月初十（闰六月之前）
        val now = solarDate(Lunar.fromYmd(2025, 6, 10).solar).atStartOfDay(shanghai)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target)
        // 五月初一、六月初一均已过，跳过闰六月，下一个是七月初一
        assertEquals(7, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复参与时闰月算独立一步`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 5, 1).solar),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val now = solarDate(Lunar.fromYmd(2025, 6, 10).solar).atStartOfDay(shanghai)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target)
        // 六月初一已过，闰六月初一在 now 之后
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复三十在小月收缩到廿九`() {
        // 2025 六月大三十天、闰六月小廿九天：六月三十下一步落到闰六月廿九
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 6, 30).solar),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2025, 8, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(-6, targetLunar.month)
        assertEquals(29, targetLunar.day)
    }

    @Test
    fun `农历月重复跨闰月后按锚点日收缩`() {
        // 六月三十 → 闰六月廿九（小月收缩）之后的下一步，仍按锚点日三十对七月收缩：
        // 七月大则七月三十。锚点日为基准是农历月重复的既定语义（见农历重复语义）
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 6, 30).solar),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2025, 9, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(7, targetLunar.month)
        assertEquals(minOf(30, LunarMonth.fromYm(2025, 7)!!.dayCount), targetLunar.day)
    }

    @Test
    fun `农历月重复间隔二跳过闰月`() {
        // 六月初一每2月，闰六月不占步数，下一步是八月初一
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 6, 1).solar),
                lunar = true,
                leapCount = false,
                interval = 2,
                unit = 3,
            )

        val now = solarDate(Lunar.fromYmd(2025, 6, 1).solar).atStartOfDay(shanghai).plusHours(1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2025, targetLunar.year)
        assertEquals(8, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复跨年推进到次年正月`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 12, 1).solar),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2026, 2, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2026, targetLunar.year)
        assertEquals(1, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历冬月重复跨年`() {
        // 冬月与腊月在月表头都存在前一年同名月，回归死循环修复
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 11, 1).solar),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2026, 2, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2026, targetLunar.year)
        assertEquals(1, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `闰月日日程月重复沿月序继续`() {
        // 闰六月初五 + 闰月不参与：闰六月过后沿月序走到七月初五，而非跳到次年正月
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, -6, 5).solar),
                lunar = true,
                leapCount = false,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2025, 8, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(7, targetLunar.month)
        assertEquals(5, targetLunar.day)
    }

    @Test
    fun `农历日程天重复按公历推进`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2026, 7, 1).solar),
                lunar = true,
                interval = 3,
                unit = 1,
            )

        val now = solarDate(Lunar.fromYmd(2026, 7, 1).solar).atStartOfDay(shanghai).plusHours(1)

        val target = schedule.nextTarget(now, shanghai)!!

        assertEquals(solarDate(Lunar.fromYmd(2026, 7, 1).solar).plusDays(3), target.toLocalDate())
    }

    @Test
    fun `农历日程周重复按公历推进`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2026, 7, 1).solar),
                lunar = true,
                interval = 1,
                unit = 2,
            )

        val now = solarDate(Lunar.fromYmd(2026, 7, 1).solar).atStartOfDay(shanghai).plusHours(1)

        val target = schedule.nextTarget(now, shanghai)!!

        assertEquals(solarDate(Lunar.fromYmd(2026, 7, 1).solar).plusWeeks(1), target.toLocalDate())
    }

    @Test
    fun `农历年重复间隔二隔年命中`() {
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, 6, 10).solar),
                lunar = true,
                interval = 2,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2027, targetLunar.year)
        assertEquals(6, targetLunar.month)
        assertEquals(10, targetLunar.day)
    }

    @Test
    fun `农历闰月年重复按间隔格点命中`() {
        // 闰六月初一每 2 年、闰月不参与：自 2025 起沿偶数年格点找恰有闰六月的年份，
        // 不在格点上的真实闰月年被跳过（2025 本身已过，从 2027 起找）
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, -6, 1).solar),
                lunar = true,
                leapCount = false,
                interval = 2,
                unit = 4,
            )

        val now = zdt(2026, 1, 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
        assertEquals(6, LunarYear.fromYear(targetLunar.year).leapMonth)
        assertEquals(0, (targetLunar.year - 2025) % 2)
        assertTrue(target.isAfter(now))
    }
}
