package com.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import com.tyme.lunar.LunarMonth
import com.tyme.lunar.LunarYear
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 农历 nextTarget：以 2025 农历年（闰六月）为核心场景 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class LunarNextTargetTest {
    private fun lunarOf(
        target: Instant,
        zone: kotlinx.datetime.TimeZone,
    ): LunarDay = lunarDayOf(target, zone)

    @Test
    fun `农历年重复保持农历月日`() {
        assertEquals(6, LunarYear.fromYear(2025).getLeapMonth())

        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 6, 10).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(6, targetLunar.month)
        assertEquals(10, targetLunar.day)
    }

    @Test
    fun `农历重复锚点在未来直接返回锚点`() {
        // 锚点 2026-8-15（公历 2026-09-25）尚未到达：首个候选即锚点本身，不推进
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2026, 8, 15).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(8, targetLunar.month)
        assertEquals(15, targetLunar.day)
    }

    @Test
    fun `闰月日年重复参与时当年退化普通月`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(6, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `闰月日年重复参与时此后闰月年恢复取闰月`() {
        // KDoc 承诺「仅当年退化，之后有闰月的年份仍取闰月」：2036 是 2025 后首个
        // 闰六月年，now 已过 2036 普通六月十五，当年候选恢复取闰六月初一而非次年普通六月
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 4,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2036, 6, 15).getSolarDay()))

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2036, targetLunar.year)
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
        assertEquals(6, LunarYear.fromYear(2036).getLeapMonth())
        assertTrue(target > now)
    }

    @Test
    fun `闰月日年重复不参与时等待真闰月年`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                lunar = true,
                leapCount = false,
                interval = 1,
                unit = 4,
            )

        val now = zdt(2026, 1, 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
        assertEquals(6, LunarYear.fromYear(targetLunar.year).getLeapMonth())
        assertTrue(target > now)
    }

    @Test
    fun `农历月重复不参与时跳过闰月`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 5, 1).getSolarDay()),
                lunar = true,
                leapCount = false,
                interval = 1,
                unit = 3,
            )

        // now 在普通六月初十（闰六月之前）
        val now = instantOf(solarDate(LunarDay.fromYmd(2025, 6, 10).getSolarDay()))

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        // 五月初一、六月初一均已过，跳过闰六月，下一个是七月初一
        assertEquals(7, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复参与时闰月算独立一步`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 5, 1).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2025, 6, 10).getSolarDay()))

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        // 六月初一已过，闰六月初一在 now 之后
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复三十在小月收缩到廿九`() {
        // 2025 六月大三十天、闰六月小廿九天：六月三十下一步落到闰六月廿九
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 6, 30).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2025, 8, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(-6, targetLunar.month)
        assertEquals(29, targetLunar.day)
    }

    @Test
    fun `农历月重复跨闰月后按锚点日收缩`() {
        // 六月三十 → 闰六月廿九（小月收缩）之后的下一步，仍按锚点日三十对七月收缩：
        // 七月大则七月三十。锚点日为基准是农历月重复的既定语义（见农历重复语义）
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 6, 30).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2025, 9, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(7, targetLunar.month)
        assertEquals(minOf(30, LunarMonth.fromYm(2025, 7).getDayCount()), targetLunar.day)
    }

    @Test
    fun `农历月重复间隔二跳过闰月`() {
        // 六月初一每2月，闰六月不占步数，下一步是八月初一
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 6, 1).getSolarDay()),
                lunar = true,
                leapCount = false,
                interval = 2,
                unit = 3,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2025, 6, 1).getSolarDay()), 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2025, targetLunar.year)
        assertEquals(8, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复多间隔闰月计入步数`() {
        // 五月初一每2月、闰月参与：六月占第 1 步、闰六月占第 2 步，落在闰六月初一
        // （同配置闰月不参与时跳到八月初一，见上）
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 5, 1).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 2,
                unit = 3,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2025, 6, 10).getSolarDay()))

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2025, targetLunar.year)
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
        assertTrue(target > now)
    }

    @Test
    fun `农历月重复候选日落夏令时缺口顺延`() {
        // 农历推算 × DST 时区组合：腊月二十每月重复，下一候选正月二十恰为公历
        // 2026-03-08，纽约 02:30 落缺口顺延 03:30，仅影响当日
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 12, 20).getSolarDay()),
                2,
                30,
                0,
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val now = instantOf(newYork, 2026, 2, 20, 12, 0, 0)

        val target = schedule.nextTarget(now, newYork)!!

        val targetLunar = lunarOf(target, newYork)
        assertEquals(2026, targetLunar.year)
        assertEquals(1, targetLunar.month)
        assertEquals(20, targetLunar.day)
        assertEquals(LocalTime(3, 30), timeOf(target, newYork))
        // 顺延后的 03:30 EDT = 07:30Z
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), target)
    }

    @Test
    fun `农历月重复候选日落夏令时重叠取较早一次`() {
        // 九月廿三每月重复：2026-11-01 01:30 在纽约出现两次，取较早的 EDT
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 9, 23).getSolarDay()),
                1,
                30,
                0,
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val now = instantOf(newYork, 2026, 10, 15, 12, 0, 0)

        val target = schedule.nextTarget(now, newYork)!!

        val targetLunar = lunarOf(target, newYork)
        assertEquals(2026, targetLunar.year)
        assertEquals(9, targetLunar.month)
        assertEquals(23, targetLunar.day)
        assertEquals(LocalTime(1, 30), timeOf(target, newYork))
        // 取较早的 EDT：01:30 EDT = 05:30Z
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), target)
    }

    @Test
    fun `农历月重复跨年推进到次年正月`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 12, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2026, 2, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(1, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历冬月重复跨年`() {
        // 冬月与腊月在月表头都存在前一年同名月，回归死循环修复
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 11, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2026, 2, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(1, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复腊月锚点跨无闰月年推进到次年正月`() {
        // 2026 农历年无闰月，月表末尾是本年腊月：自腊月步进到月表末尾后须跨年
        // 推进到次年正月，否则 year 不推进、在正月与腊月间死循环（回归）
        assertEquals(0, LunarYear.fromYear(2026).getLeapMonth())

        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2026, 12, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2026, 12, 1).getSolarDay()), 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2027, targetLunar.year)
        assertEquals(1, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复闰月参与跨无闰月年继续推进`() {
        // 闰六月初一、闰月参与：先跨 2025（闰年）再跨 2026（无闰月）两类年份边界，
        // 均须正常推进（回归：死循环）
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2027, 1, 1).getSolarDay()), 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2027, targetLunar.year)
        assertEquals(2, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复自1970年锚点长跨步推进`() {
        // 锚点 1970-01-02（农历 1969 冬月廿五）推进到 2026：跨约 57 个农历年、
        // 多数无闰月，修复前在首个无闰月年的腊月边界即死循环（回归）
        val schedule =
            schedule(
                utcMillis(LocalDate(1970, 1, 2)),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val now = zdt(2026, 8, 23, 12, 0, 0)

        val target = schedule.nextTarget(now, shanghai)!!

        // 月重复保持锚点日廿五（任何农历月都有廿五），且落在 now 后的一个多月内
        val targetLunar = lunarOf(target, shanghai)
        assertEquals(25, targetLunar.day)
        assertFalse(target < now)
        assertTrue(target < now + 45.days)
    }

    @Test
    fun `闰月日日程月重复沿月序继续`() {
        // 闰六月初五 + 闰月不参与：闰六月过后沿月序走到七月初五，而非跳到次年正月
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 5).getSolarDay()),
                lunar = true,
                leapCount = false,
                interval = 1,
                unit = 3,
            )

        val target = schedule.nextTarget(zdt(2025, 8, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(7, targetLunar.month)
        assertEquals(5, targetLunar.day)
    }

    @Test
    fun `农历日程天重复按公历推进`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2026, 7, 1).getSolarDay()),
                lunar = true,
                interval = 3,
                unit = 1,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2026, 7, 1).getSolarDay()), 1)

        val target = schedule.nextTarget(now, shanghai)!!

        assertEquals(solarDate(LunarDay.fromYmd(2026, 7, 1).getSolarDay()) + 3, dateOf(target, shanghai))
    }

    @Test
    fun `农历日程周重复按公历推进`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2026, 7, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 2,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2026, 7, 1).getSolarDay()), 1)

        val target = schedule.nextTarget(now, shanghai)!!

        assertEquals(solarDate(LunarDay.fromYmd(2026, 7, 1).getSolarDay()) + 7, dateOf(target, shanghai))
    }

    @Test
    fun `农历年重复间隔二隔年命中`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 6, 10).getSolarDay()),
                lunar = true,
                interval = 2,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
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
                solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                lunar = true,
                leapCount = false,
                interval = 2,
                unit = 4,
            )

        val now = zdt(2026, 1, 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(-6, targetLunar.month)
        assertEquals(1, targetLunar.day)
        assertEquals(6, LunarYear.fromYear(targetLunar.year).getLeapMonth())
        assertEquals(0, (targetLunar.year - 2025) % 2)
        assertTrue(target > now)
    }

    @Test
    fun `农历月年重复锚点超出范围构造即拒`() {
        // 范围校验前移到构造期并对全部日程生效：0001-01-01..9999-12-31 之外的
        // 锚点日期（不区分公历/农历）在 Schedule 构造时即抛 IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(9999, 12, 31)) + 86_400_000L, lunar = true, interval = 1, unit = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(0, 1, 1)), lunar = true, interval = 1, unit = 4)
        }
    }

    @Test
    fun `负毫秒农历锚点1949年国庆年重复保持农历月日`() {
        // 1949-10-01 = 农历己丑年八月初十；年重复推到 2026 年仍为农历八月初十
        // （2026/8/10 = 公历 2026-09-20，在 now 之后）
        val schedule = schedule(utcMillis(LocalDate(1949, 10, 1)), lunar = true, interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(8, targetLunar.month)
        assertEquals(10, targetLunar.day)
    }

    @Test
    fun `农历公元一年锚点落在农历零年可用`() {
        // 0001-01-15 属农历 0 年腊月（实测 0/12/2），农历年表自 0 年可靠；
        // 年重复保持农历腊月初二，推到 2026 年
        val schedule = schedule(utcMillis(LocalDate(1, 1, 15)), lunar = true, interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(12, targetLunar.month)
        assertEquals(2, targetLunar.day)
    }

    @Test
    fun `农历月重复上界间隔越过9999年立即抛IllegalStateException`() {
        // 逐月推进在内层循环越过 9999 时必须立即失败：不得向 tyme 索取越界
        // 年表；取上界间隔即可覆盖（约八千年月步进，毫秒级触达）
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 1, 1)),
                lunar = true,
                interval = Schedule.MAX_REPEAT_INTERVAL,
                unit = 3,
            )

        assertFailsWith<IllegalStateException> { schedule.nextTarget(zdt(2026, 2, 1), shanghai) }
    }

    @Test
    fun `农历年重复上界间隔越过9999年立即抛IllegalStateException`() {
        // 年重复沿 year += repeatInterval 推进（与逐月步进不同的路径），越过可靠
        // 年表同样必须立即失败，不得向 tyme 索取越界数据
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 1, 1)),
                lunar = true,
                interval = Schedule.MAX_REPEAT_INTERVAL,
                unit = 4,
            )

        assertFailsWith<IllegalStateException> { schedule.nextTarget(zdt(2026, 2, 1), shanghai) }
    }

    @Test
    fun `农历年重复三十锚点小月年收缩到廿九`() {
        // 2025 七月三十（大月）每年重复：2026 七月只有 29 天，收缩到七月廿九
        // （年重复与月重复共用 minOf 收缩，此前只有月重复路径被钉住）
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 7, 30).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2025, 7, 30).getSolarDay()) + 1)

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(7, targetLunar.month)
        assertEquals(29, targetLunar.day)
    }

    @Test
    fun `闰月三十锚点年重复闰月年收缩到廿九`() {
        // 2017 闰六月三十（闰六月大三十天）每年重复、闰月参与：无闰六月的年份
        // 退化普通六月，2025 闰六月只有 29 天收缩到闰六月廿九
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2017, -6, 30).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 4,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2025, 6, 15).getSolarDay()))

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2025, targetLunar.year)
        assertEquals(-6, targetLunar.month)
        assertEquals(29, targetLunar.day)
    }

    @Test
    fun `农历月重复间隔二跨年推进到次年二月`() {
        // 腊月初一每 2 月：第 1 步跨年落入 2026 正月（月表末尾跨年，年份连月采用），
        // 第 2 步落到二月；跨年 + 多间隔组合此前只有单间隔被钉住
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 12, 1).getSolarDay()),
                lunar = true,
                interval = 2,
                unit = 3,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2026, 1, 15).getSolarDay()))

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2026, targetLunar.year)
        assertEquals(2, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `闰月日日程月重复闰月参与沿月序到次月`() {
        // 闰六月初五、闰月参与：自闰六月起沿含闰月的月表步进到七月初五
        // （同场景闰月不参与已测，闰月参与方向对称补齐）
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 5).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 1,
                unit = 3,
            )

        val now = instantOf(solarDate(LunarDay.fromYmd(2025, -6, 6).getSolarDay()))

        val target = schedule.nextTarget(now, shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2025, targetLunar.year)
        assertEquals(7, targetLunar.month)
        assertEquals(5, targetLunar.day)
    }

    @Test
    fun `闰月日年重复参与间隔二格点年退化普通月`() {
        // 闰六月初一每 2 年、闰月参与：格点年 2027 无闰六月，当年退化为普通六月初一
        // （格点 + 退化组合此前只有单间隔被钉住）
        assertEquals(0, LunarYear.fromYear(2027).getLeapMonth())

        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                lunar = true,
                leapCount = true,
                interval = 2,
                unit = 4,
            )

        val target = schedule.nextTarget(zdt(2026, 1, 1), shanghai)!!

        val targetLunar = lunarOf(target, shanghai)
        assertEquals(2027, targetLunar.year)
        assertEquals(6, targetLunar.month)
        assertEquals(1, targetLunar.day)
    }

    @Test
    fun `农历月重复锚点在上界立即抛IllegalStateException`() {
        // 锚点本身即农历 9999 年腊月（9999-12-31 = 腊月初二）：单步跨入 10000 年
        // 必须立即失败，而非消费越界年表数据（大间隔起跳已测，边界锚点补齐）。
        // now 取 10000-01-01T00:00Z（LocalDate 年份上限 9999，改用毫秒直接构造）
        val schedule =
            schedule(
                utcMillis(LocalDate(9999, 12, 31)),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val now = Instant.fromEpochMilliseconds(utcMillis(LocalDate(9999, 12, 31)) + 86_400_000L)

        assertFailsWith<IllegalStateException> { schedule.nextTarget(now, shanghai) }
    }

    @Test
    fun `农历年重复锚点在上界立即抛IllegalStateException`() {
        // 年重复沿 year += repeatInterval 推进（与逐月步进不同的路径），边界锚点
        // 同样单步越界立即失败
        val schedule =
            schedule(
                utcMillis(LocalDate(9999, 12, 31)),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        val now = Instant.fromEpochMilliseconds(utcMillis(LocalDate(9999, 12, 31)) + 86_400_000L)

        assertFailsWith<IllegalStateException> { schedule.nextTarget(now, shanghai) }
    }

    @Test
    fun `农历非重复日程返回公历锚点日`() {
        // lunar 标志只在月/年重复时生效：非重复日程原样返回锚点公历日期，不做农历换算
        val anchor = solarDate(LunarDay.fromYmd(2026, 8, 10).getSolarDay())
        val schedule = schedule(utcMillis(anchor), lunar = true)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(anchor, dateOf(target, shanghai))
        assertEquals(LocalTime(0, 0), timeOf(target, shanghai))
    }
}
