package io.github.kamiiroawase.nexttime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** 月重复 + 月内日选择器：月末定位、缺失日跳过/收缩、多日排序、锚点隶属与互斥校验 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class MonthDayRepeatTest {
    @Test
    fun `负一日即每月最后一天`() {
        // 2026 年非闰：2 月末 28；2028 闰年：2 月末 29
        val schedule = schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(-1))

        assertEquals(LocalDate(2026, 2, 28), dateOf(schedule.nextTarget(zdt(2026, 2, 10), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 3, 31), dateOf(schedule.nextTarget(zdt(2026, 2, 28, 12, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2028, 2, 29), dateOf(schedule.nextTarget(zdt(2028, 2, 5), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 2, 28), dateOf(schedule.previousTarget(zdt(2026, 3, 1), shanghai)!!, shanghai))
    }

    @Test
    fun `正数日缺失的月份跳过`() {
        // 2 月没有 30 号：1/30 后下一次出现是 3/30
        val schedule = schedule(utcMillis(LocalDate(2026, 1, 30)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(30))

        assertEquals(LocalDate(2026, 3, 30), dateOf(schedule.nextTarget(zdt(2026, 2, 10), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 3, 30), dateOf(schedule.previousTarget(zdt(2026, 4, 2), shanghai)!!, shanghai))
    }

    @Test
    fun `收缩模式把缺失日收到月末并去重`() {
        // {30, 31} + clamp：2 月两个选择日都收到 28，去重后仅一次出现
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 1, 31)),
                interval = 1,
                unit = RepeatUnit.MONTH,
                monthDays = setOf(30, 31),
                clampMonthDays = true,
            )

        val days =
            schedule
                .occurrences(zdt(2026, 1, 1), shanghai)
                .take(4)
                .map { dateOf(it, shanghai) }
                .toList()

        assertEquals(
            listOf(LocalDate(2026, 1, 31), LocalDate(2026, 2, 28), LocalDate(2026, 3, 30), LocalDate(2026, 3, 31)),
            days,
        )
    }

    @Test
    fun `收缩模式等价于锚点日收缩语义`() {
        // {31} + clamp 与无选择器锚点 31 日月重复同序列：1/31 → 2/28 → 3/31
        val clamped =
            schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(31), clampMonthDays = true)
        val contracted = schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = RepeatUnit.MONTH)

        for (now in listOf(zdt(2026, 2, 10), zdt(2026, 4, 20), zdt(2027, 7, 1))) {
            assertEquals(contracted.nextTarget(now, shanghai), clamped.nextTarget(now, shanghai))
        }
    }

    @Test
    fun `负二日定位每月倒数第二天`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 1, 30)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(-2))

        assertEquals(LocalDate(2026, 2, 27), dateOf(schedule.nextTarget(zdt(2026, 2, 10), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 3, 30), dateOf(schedule.nextTarget(zdt(2026, 2, 28), shanghai)!!, shanghai))
    }

    @Test
    fun `多日选择器月内升序产出`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 1, 1)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(15, 1))

        assertEquals(LocalDate(2026, 1, 15), dateOf(schedule.nextTarget(zdt(2026, 1, 10), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 2, 1), dateOf(schedule.nextTarget(zdt(2026, 1, 16), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 1, 15), dateOf(schedule.previousTarget(zdt(2026, 1, 20), shanghai)!!, shanghai))
    }

    @Test
    fun `间隔月与空月组合`() {
        // 间隔 2、{30}：1/30 → 3/30（2 月跳过本是空月，间隔下 2 月本就不出现）
        val schedule = schedule(utcMillis(LocalDate(2026, 1, 30)), interval = 2, unit = RepeatUnit.MONTH, monthDays = setOf(30))

        assertEquals(LocalDate(2026, 3, 30), dateOf(schedule.nextTarget(zdt(2026, 2, 10), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 5, 30), dateOf(schedule.nextTarget(zdt(2026, 4, 1), shanghai)!!, shanghai))
    }

    @Test
    fun `整日跳变缺口的月选择器出现不被整月跳过`() {
        // Apia 2011-12-30 整天不存在（12/29 末自 -10 跳 +14）：{30} 的 12 月出现
        // 组合后顺延到 12-31 00:30 当地，本地日期序不再蕴含时刻序——整月跳过须
        // 以组合时刻判定，now 在顺延窗口内时它仍是下一个目标（而非次年 1 月）
        val apia = TimeZone.of("Pacific/Apia")
        val schedule = schedule(utcMillis(LocalDate(2011, 11, 30)), 0, 30, 0, interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(30))

        assertEquals(Instant.parse("2011-12-30T10:30:00Z"), schedule.nextTarget(instantOf(apia, 2011, 12, 31, 0, 0, 0), apia))
    }

    @Test
    fun `整日跳变缺口的月选择器previous不返回晚于before的出现`() {
        // 同上一用例的缺口：顺延后的 12 月出现（12-31 00:30）晚于 before
        // （12-31 00:00），最近一次不晚于 before 的是 11 月锚点本身——按本地
        // 日期序整月跳过会把顺延出现错记为不晚于 before 而泄漏出去
        val apia = TimeZone.of("Pacific/Apia")
        val schedule = schedule(utcMillis(LocalDate(2011, 11, 30)), 0, 30, 0, interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(30))

        assertEquals(Instant.parse("2011-11-30T10:30:00Z"), schedule.previousTarget(instantOf(apia, 2011, 12, 31, 0, 0, 0), apia))
    }

    @Test
    fun `锚点日不在解析结果内构造期拒绝`() {
        // -1 在 1 月解析为 31，锚点 30 不隶属
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(2026, 1, 30)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(-1))
        }
        // 非收缩模式下 2 月不含 30，锚点 2/28 不隶属；收缩模式下同一锚点合法
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(2026, 2, 28)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(30))
        }
        schedule(utcMillis(LocalDate(2026, 2, 28)), interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(30), clampMonthDays = true)
    }

    @Test
    fun `月内日与星期选择器互斥且限月单位非农历`() {
        val anchor = utcMillis(LocalDate(2026, 1, 15))
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(15), weekdays = setOf(WeekDay.THURSDAY))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.WEEK, monthDays = setOf(15))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.YEAR, monthDays = setOf(15))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, lunar = true, interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(15))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.MONTH, monthDays = setOf(0, 32, -32))
        }
    }
}
