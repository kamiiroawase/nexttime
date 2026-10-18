package io.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** 出现次数上限 repeatCount：含锚点计数、耗尽完结、previous 钳制、与 until 先到者、各路径贯通 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class CountTest {
    @Test
    fun `天重复耗尽后返回null且previous钳在末次出现`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = RepeatUnit.DAY, repeatCount = 3)

        assertEquals(LocalDate(2026, 8, 3), dateOf(schedule.nextTarget(zdt(2026, 8, 3, 0, 0, 0), shanghai)!!, shanghai))
        assertNull(schedule.nextTarget(zdt(2026, 8, 4), shanghai))
        assertEquals(LocalDate(2026, 8, 3), dateOf(schedule.previousTarget(zdt(2026, 12, 31), shanghai)!!, shanghai))
    }

    @Test
    fun `小时重复按格点步数计数`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 8, 1)), 0, 0, 0, interval = 8, unit = RepeatUnit.HOUR, repeatCount = 3)

        // 出现：08-01 00:00、08:00、16:00
        assertEquals(zdt(2026, 8, 1, 16, 0, 0), schedule.nextTarget(zdt(2026, 8, 1, 15, 0, 0), shanghai))
        assertNull(schedule.nextTarget(zdt(2026, 8, 1, 17, 0, 0), shanghai))
        assertEquals(zdt(2026, 8, 1, 16, 0, 0), schedule.previousTarget(zdt(2026, 8, 2, 5, 0, 0), shanghai))
    }

    @Test
    fun `分钟重复previous钳到末次出现`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 8, 1)), 0, 0, 0, interval = 30, unit = RepeatUnit.MINUTE, repeatCount = 4)

        // 出现：00:00、00:30、01:00、01:30；before 02:00 的最后一次出现是 01:30
        assertEquals(zdt(2026, 8, 1, 1, 30, 0), schedule.previousTarget(zdt(2026, 8, 1, 2, 0, 0), shanghai))
        assertNull(schedule.nextTarget(zdt(2026, 8, 1, 1, 31, 0), shanghai))
    }

    @Test
    fun `周重复与月年重复耗尽完结`() {
        val weekly = schedule(utcMillis(LocalDate(2026, 8, 10)), interval = 1, unit = RepeatUnit.WEEK, repeatCount = 2)
        assertNull(weekly.nextTarget(zdt(2026, 8, 18), shanghai))
        assertEquals(LocalDate(2026, 8, 17), dateOf(weekly.previousTarget(zdt(2026, 8, 20), shanghai)!!, shanghai))

        val monthly = schedule(utcMillis(LocalDate(2026, 1, 15)), interval = 1, unit = RepeatUnit.MONTH, repeatCount = 2)
        assertEquals(LocalDate(2026, 2, 15), dateOf(monthly.nextTarget(zdt(2026, 1, 16), shanghai)!!, shanghai))
        assertNull(monthly.nextTarget(zdt(2026, 2, 16), shanghai))
        assertEquals(LocalDate(2026, 2, 15), dateOf(monthly.previousTarget(zdt(2030, 1, 1), shanghai)!!, shanghai))

        val yearly = schedule(utcMillis(LocalDate(2020, 6, 15)), interval = 1, unit = RepeatUnit.YEAR, repeatCount = 2)
        assertNull(yearly.nextTarget(zdt(2022, 1, 1), shanghai))
        assertEquals(LocalDate(2021, 6, 15), dateOf(yearly.previousTarget(zdt(2030, 6, 15), shanghai)!!, shanghai))
    }

    @Test
    fun `次数为一仅锚点`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 8, 1)), 9, 0, 0, interval = 1, unit = RepeatUnit.DAY, repeatCount = 1)

        // 锚点豁免于 until 同理不受次数影响：count 本就含锚点
        assertEquals(zdt(2026, 8, 1, 9, 0, 0), schedule.nextTarget(zdt(2026, 7, 30), shanghai))
        assertNull(schedule.nextTarget(zdt(2026, 8, 1, 9, 0, 1), shanghai))
        assertEquals(zdt(2026, 8, 1, 9, 0, 0), schedule.previousTarget(zdt(2026, 9, 1), shanghai))
    }

    @Test
    fun `次数与until先到者生效`() {
        // count 富余、until 先到：2026-06-15 晚于 2026-01-01 且不晚于 2026-12-31
        val untilBounded =
            schedule(utcMillis(LocalDate(2020, 6, 15)), interval = 1, unit = RepeatUnit.YEAR, repeatCount = 100)
        assertEquals(LocalDate(2026, 6, 15), dateOf(untilBounded.nextTarget(zdt(2026, 1, 1), shanghai, zdt(2026, 12, 31))!!, shanghai))
        assertNull(untilBounded.nextTarget(zdt(2027, 1, 1), shanghai, zdt(2026, 12, 31)))

        // until 富余、count 先到：三次出现 2020/2021/2022，2023 起完结
        val countBounded =
            schedule(utcMillis(LocalDate(2020, 6, 15)), interval = 1, unit = RepeatUnit.YEAR, repeatCount = 3)
        assertNull(countBounded.nextTarget(zdt(2023, 1, 1), shanghai, zdt(2030, 1, 1)))
    }

    @Test
    fun `周选择器按出现序号计数含锚点周部分`() {
        // 锚点周三 8/12、每周一三五：出现 8/12、8/14、8/17（锚点周的周一不算出现）
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 8, 12)),
                interval = 1,
                unit = RepeatUnit.WEEK,
                weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY),
                repeatCount = 3,
            )

        assertEquals(LocalDate(2026, 8, 17), dateOf(schedule.nextTarget(zdt(2026, 8, 15), shanghai)!!, shanghai))
        assertNull(schedule.nextTarget(zdt(2026, 8, 18), shanghai))
        assertEquals(LocalDate(2026, 8, 17), dateOf(schedule.previousTarget(zdt(2026, 8, 20), shanghai)!!, shanghai))
    }

    @Test
    fun `周选择器previous远期按次数封顶直定位`() {
        // 五次出现：8/10、8/17、8/24、8/31、9/7；before 远在 2027 年也应钳到 9/7
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 8, 10)),
                interval = 1,
                unit = RepeatUnit.WEEK,
                weekdays = setOf(WeekDay.MONDAY),
                repeatCount = 5,
            )

        assertEquals(LocalDate(2026, 9, 7), dateOf(schedule.previousTarget(zdt(2027, 3, 1), shanghai)!!, shanghai))
        assertNull(schedule.nextTarget(zdt(2026, 9, 8), shanghai))
    }

    @Test
    fun `月选择器计数跨空月`() {
        // {30} 的出现：1/30、3/30、4/30（2 月无 30 号跳过）——空月不消耗次数
        val schedule =
            schedule(utcMillis(LocalDate(2026, 1, 30)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(30), repeatCount = 3)

        assertEquals(LocalDate(2026, 4, 30), dateOf(schedule.nextTarget(zdt(2026, 4, 1), shanghai)!!, shanghai))
        assertNull(schedule.nextTarget(zdt(2026, 5, 1), shanghai))
        assertEquals(LocalDate(2026, 4, 30), dateOf(schedule.previousTarget(zdt(2026, 12, 31), shanghai)!!, shanghai))
    }

    @Test
    fun `农历年重复按出现计数`() {
        // 八月初十、每年一次、共两次：锚点（2026-09-20）为第 1 次，2027 年为第 2 次，
        // 此后完结。previous 远期返回与 next 推进一致的第 2 次出现
        val anchor = solarMillis(LunarDay.fromYmd(2026, 8, 10).getSolarDay())
        val schedule = schedule(anchor, 8, 0, 0, lunar = true, interval = 1, unit = RepeatUnit.YEAR, repeatCount = 2)

        val second = assertNotNull(schedule.nextTarget(zdt(2026, 9, 21), shanghai))
        val lunarSecond = lunarDayOf(second, shanghai)
        assertEquals(8, lunarSecond.month)
        assertEquals(10, lunarSecond.day)
        assertEquals(second, schedule.previousTarget(zdt(2030, 1, 1), shanghai))
        assertNull(schedule.nextTarget(second + 1.seconds, shanghai))
    }

    @Test
    fun `农历闰月跳年的候选同样计数`() {
        // 闰六月初一（2025-09 前的公历日）、闰月不参与：只在有闰六月的年份出现
        // （下一个是 2036 年），两次后完结——跳年期间无闰六月的候选不消耗次数
        val anchorDate = solarDate(LunarDay.fromYmd(2025, -6, 1).getSolarDay())
        val schedule =
            schedule(utcMillis(anchorDate), lunar = true, leapCount = false, interval = 1, unit = RepeatUnit.YEAR, repeatCount = 2)

        val second = assertNotNull(schedule.nextTarget(instantOf(anchorDate + 1, 12), shanghai))
        val lunarSecond = lunarDayOf(second, shanghai)
        assertEquals(-6, lunarSecond.month)
        assertEquals(1, lunarSecond.day)
        assertEquals(second, schedule.previousTarget(zdt(2040, 1, 1), shanghai))
        assertNull(schedule.nextTarget(second + 1.seconds, shanghai))
    }

    @Test
    fun `次数上限零表示不限`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = RepeatUnit.DAY, repeatCount = 0)

        assertEquals(LocalDate(2027, 3, 15), dateOf(schedule.nextTarget(zdt(2027, 3, 14, 12, 0, 0), shanghai)!!, shanghai))
    }

    @Test
    fun `次数上限的构造校验`() {
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = RepeatUnit.DAY, repeatCount = -1)
        }
        // 不重复日程配正次数：出现只有锚点本身，界限无意义，构造期拒绝
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 0, unit = RepeatUnit.DAY, repeatCount = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = RepeatUnit.NONE, repeatCount = 3)
        }
    }
}
