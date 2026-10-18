package io.github.kamiiroawase.nexttime

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/** 周重复 + 星期选择器：周格点、锚点周过滤、锚点隶属与构造校验、夏令时缺口 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class WeekdayRepeatTest {
    // 2026-08-10 是周一，08-12 周三，08-14 周五，08-17 下周一；2026-09-01 周二
    private val monday = utcMillis(LocalDate(2026, 8, 10))
    private val wednesday = utcMillis(LocalDate(2026, 8, 12))

    private val monWedFri = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY)

    @Test
    fun `每周一三五在选中星期各出现一次`() {
        val schedule = schedule(monday, 9, 0, 0, interval = 1, unit = RepeatUnit.WEEK, weekdays = monWedFri)

        assertEquals(LocalDate(2026, 8, 12), dateOf(schedule.nextTarget(zdt(2026, 8, 11, 12, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 8, 14), dateOf(schedule.nextTarget(zdt(2026, 8, 12, 10, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 8, 17), dateOf(schedule.nextTarget(zdt(2026, 8, 15, 0, 0, 0), shanghai)!!, shanghai))
    }

    @Test
    fun `锚点周内早于锚点日的规则日不算出现`() {
        // 锚点周三：锚点周的周一不是出现，出现序列自锚点起为周三、周五、下周一……
        val schedule = schedule(wednesday, 9, 0, 0, interval = 1, unit = RepeatUnit.WEEK, weekdays = monWedFri)

        assertEquals(LocalDate(2026, 8, 12), dateOf(schedule.nextTarget(zdt(2026, 8, 11, 0, 0, 0), shanghai)!!, shanghai))
        // 反向同样不落在锚点前的周一
        assertEquals(LocalDate(2026, 8, 12), dateOf(schedule.previousTarget(zdt(2026, 8, 13, 8, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 8, 14), dateOf(schedule.previousTarget(zdt(2026, 8, 15, 8, 0, 0), shanghai)!!, shanghai))
    }

    @Test
    fun `间隔周在格点周内的选中星期出现`() {
        // 锚点周一 8/10，间隔 2：格点周 8/10-16、8/24-30；选中周一与周四
        val schedule = schedule(monday, interval = 2, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.MONDAY, WeekDay.THURSDAY))

        assertEquals(LocalDate(2026, 8, 24), dateOf(schedule.nextTarget(zdt(2026, 8, 18, 0, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 8, 27), dateOf(schedule.nextTarget(zdt(2026, 8, 25, 0, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 8, 27), dateOf(schedule.previousTarget(zdt(2026, 8, 29, 0, 0, 0), shanghai)!!, shanghai))
    }

    @Test
    fun `锚点日等于now时原样返回`() {
        val schedule = schedule(monday, 9, 0, 0, interval = 1, unit = RepeatUnit.WEEK, weekdays = monWedFri)

        assertEquals(zdt(2026, 8, 10, 9, 0, 0), schedule.nextTarget(zdt(2026, 8, 10, 9, 0, 0), shanghai))
    }

    @Test
    fun `跨年周连续推进`() {
        // 2026-12-28 周一，周日 2027-01-03 与之同属一个 ISO 周
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 12, 28)),
                interval = 1,
                unit = RepeatUnit.WEEK,
                weekdays = setOf(WeekDay.MONDAY, WeekDay.SUNDAY),
            )

        assertEquals(LocalDate(2027, 1, 3), dateOf(schedule.nextTarget(zdt(2026, 12, 31, 0, 0, 0), shanghai)!!, shanghai))
    }

    @Test
    fun `远期now按周索引直入`() {
        // 2027-01-18 是 2026-08-10 之后的第 23 个周一（22 周）；2027-01-15 周五的下一个周一
        val schedule = schedule(monday, interval = 1, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.MONDAY))

        assertEquals(LocalDate(2027, 1, 18), dateOf(schedule.nextTarget(zdt(2027, 1, 15, 12, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2027, 1, 18), dateOf(schedule.previousTarget(zdt(2027, 1, 19, 12, 0, 0), shanghai)!!, shanghai))
    }

    @Test
    fun `夏令时缺口日顺延后续出现回到原时刻`() {
        // 纽约 2027-03-14（周日）02:00 跳到 03:00：02:30 目标时刻落在缺口内顺延到 03:30
        val schedule =
            schedule(
                utcMillis(LocalDate(2027, 3, 7)),
                2,
                30,
                0,
                interval = 1,
                unit = RepeatUnit.WEEK,
                weekdays = setOf(WeekDay.SUNDAY),
            )

        val gapDay = schedule.nextTarget(instantOf(newYork, 2027, 3, 10, 12, 0, 0), newYork)!!
        assertEquals(LocalDate(2027, 3, 14), dateOf(gapDay, newYork))
        assertEquals(kotlinx.datetime.LocalTime(3, 30), timeOf(gapDay, newYork))

        // 缺口仅当日顺延：下周日回到 02:30
        val after = schedule.nextTarget(instantOf(newYork, 2027, 3, 16, 12, 0, 0), newYork)!!
        assertEquals(LocalDate(2027, 3, 21), dateOf(after, newYork))
        assertEquals(kotlinx.datetime.LocalTime(2, 30), timeOf(after, newYork))
    }

    @Test
    fun `now与zone成对比较`() {
        // 时刻语义与既有路径一致：按 zone 组合、与 now 的 instant 比较
        val schedule = schedule(monday, 12, 0, 0, interval = 1, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY))

        val now = instantOf(newYork, 2026, 8, 11, 12, 0, 0)
        val target = schedule.nextTarget(now, shanghai)!!

        // 上海 8/12 12:00 = UTC 04:00；now 为纽约 8/11 12:00 = UTC 16:00 前一日
        assertEquals(Instant.parse("2026-08-12T04:00:00Z"), target)
    }

    @Test
    fun `锚点星期不在集合内构造期拒绝`() {
        // 2026-09-01 是周二，不在周一三五内
        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(2026, 9, 1)), interval = 1, unit = RepeatUnit.WEEK, weekdays = monWedFri)
        }
        // 锚点周三配周一周五
        assertFailsWith<IllegalArgumentException> {
            schedule(wednesday, interval = 1, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.MONDAY, WeekDay.FRIDAY))
        }
    }

    @Test
    fun `星期选择器要求周或月单位且非农历`() {
        assertFailsWith<IllegalArgumentException> {
            schedule(monday, interval = 1, unit = RepeatUnit.DAY, weekdays = setOf(WeekDay.MONDAY))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(monday, interval = 1, unit = RepeatUnit.HOUR, weekdays = setOf(WeekDay.MONDAY))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(monday, interval = 1, unit = RepeatUnit.YEAR, weekdays = setOf(WeekDay.MONDAY))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(monday, interval = 1, unit = RepeatUnit.NONE, weekdays = setOf(WeekDay.MONDAY))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(monday, lunar = true, interval = 1, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.MONDAY))
        }
        // 不重复日程配选择器：只会被静默吞掉，构造期拒绝
        assertFailsWith<IllegalArgumentException> {
            schedule(monday, interval = 0, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.MONDAY))
        }
        // 星期编号越界
        assertFailsWith<IllegalArgumentException> {
            schedule(monday, interval = 1, unit = RepeatUnit.WEEK, weekdays = setOf(0, 8))
        }
    }

    @Test
    fun `targetDay未选时跳过锚点隶属检查`() {
        // 未选目标日：推算恒 null，但构造不应因无从验证隶属而拒绝
        val schedule = schedule(-1L, interval = 1, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.MONDAY))

        assertNull(schedule.nextTarget(zdt(2026, 8, 11), shanghai))
    }
}
