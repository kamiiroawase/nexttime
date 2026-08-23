package com.github.kamiiroawase.nexttime

import com.nlf.calendar.Lunar
import com.nlf.calendar.LunarMonth
import com.nlf.calendar.LunarYear
import com.nlf.calendar.Solar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.LocalDate
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
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `农历月重复腊月锚点跨无闰月年推进到次年正月`() {
        // 2026 农历年无闰月，月表末尾是 2027 正月：自腊月步进必须连年份一并
        // 采用下一项，否则 year 停在 2026、在正月与腊月间死循环（回归）
        assertEquals(0, LunarYear.fromYear(2026).leapMonth)

        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2026, 12, 1).solar),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val now = solarDate(Lunar.fromYmd(2026, 12, 1).solar).atStartOfDay(shanghai).plusHours(1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2027, targetLunar.year)
        assertEquals(1, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `农历月重复闰月参与跨无闰月年继续推进`() {
        // 闰六月初一、闰月参与：先跨 2025（闰年，表尾是本年腊月）再跨 2026
        // （无闰月，表尾是次年正月）两类年份边界，均须正常推进（回归：死循环）
        val schedule =
            schedule(
                solarMillis(Lunar.fromYmd(2025, -6, 1).solar),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val now = solarDate(Lunar.fromYmd(2027, 1, 1).solar).atStartOfDay(shanghai).plusHours(1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2027, targetLunar.year)
        assertEquals(2, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `农历月重复自1970年锚点长跨步推进`() {
        // 锚点 1970-01-02（农历 1969 冬月廿五）推进到 2026：跨约 57 个农历年、
        // 多数无闰月，修复前在首个无闰月年的腊月边界即死循环（回归）
        val schedule =
            schedule(
                utcMillis(LocalDate.of(1970, 1, 2)),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val now = zdt(2026, 8, 23, 12, 0, 0)

        val target = schedule.nextTarget(now, shanghai)!!

        // 月重复保持锚点日廿五（任何农历月都有廿五），且落在 now 后的一个多月内
        val targetLunar = lunarOf(target)
        assertEquals(25, targetLunar.day)
        assertFalse(target.isBefore(now))
        assertTrue(target.isBefore(now.plusDays(45)))
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

    @Test
    fun `农历月年重复锚点超出范围构造即拒`() {
        // 范围校验前移到构造期并对全部日程生效：0001-01-01..9999-12-31 之外的
        // 锚点日期（不区分公历/农历）在 Schedule 构造时即抛 IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            schedule(utcMillis(LocalDate.of(10000, 1, 1)), lunar = true, interval = 1, unit = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            schedule(utcMillis(LocalDate.of(0, 1, 1)), lunar = true, interval = 1, unit = 4)
        }
    }

    @Test
    fun `负毫秒农历锚点1949年国庆年重复保持农历月日`() {
        // 1949-10-01 = 农历己丑年八月初十；年重复推到 2026 年仍为农历八月初十
        // （2026/8/10 = 公历 2026-09-20，在 now 之后）
        val schedule = schedule(utcMillis(LocalDate.of(1949, 10, 1)), lunar = true, interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2026, targetLunar.year)
        assertEquals(8, targetLunar.month)
        assertEquals(10, targetLunar.day)
    }

    @Test
    fun `农历公元一年锚点落在农历零年可用`() {
        // 0001-01-15 属农历 0 年腊月（实测 0/12/2），农历年表自 0 年可靠；
        // 年重复保持农历腊月初二，推到 2026 年
        val schedule = schedule(utcMillis(LocalDate.of(1, 1, 15)), lunar = true, interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        val targetLunar = lunarOf(target)
        assertEquals(2026, targetLunar.year)
        assertEquals(12, targetLunar.month)
        assertEquals(2, targetLunar.day)
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `农历月重复上界间隔越过9999年立即抛IllegalStateException`() {
        // 逐月推进在内层循环越过 9999 时必须立即失败：不得向 lunar-java 索取越界
        // 年表；取上界间隔即可覆盖（约八千年月步进，毫秒级触达）
        val schedule =
            schedule(
                utcMillis(LocalDate.of(2026, 1, 1)),
                lunar = true,
                interval = Schedule.MAX_REPEAT_INTERVAL,
                unit = 3,
            )

        assertThrows(IllegalStateException::class.java) { schedule.nextTarget(zdt(2026, 2, 1), shanghai) }
    }
}
