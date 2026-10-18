package io.github.kamiiroawase.nexttime.rrule

import io.github.kamiiroawase.nexttime.RepeatUnit
import io.github.kamiiroawase.nexttime.Schedule
import io.github.kamiiroawase.nexttime.WeekDay
import io.github.kamiiroawase.nexttime.nextTarget
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** RRULE 互转：确定性导出、解析映射、往返性质、无损子集的拒绝面 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class RruleTest {
    private val shanghai = TimeZone.of("Asia/Shanghai")

    private fun utcMillis(date: LocalDate): Long = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    /** 日程的锚点 LocalDateTime（UTC 日期 + 日程时刻），往返测试用 */
    private fun anchorOf(schedule: Schedule): LocalDateTime {
        val date = Instant.fromEpochMilliseconds(schedule.targetDay).toLocalDateTime(TimeZone.UTC).date
        return LocalDateTime(date, LocalTime(schedule.targetHour, schedule.targetMinute, schedule.targetSecond))
    }

    private fun scheduleOf(
        anchor: LocalDate,
        hour: Int = 9,
        minute: Int = 0,
        second: Int = 0,
        interval: Int = 1,
        unit: Int,
        weekdays: Set<Int> = emptySet(),
        weekdayOrdinals: Set<Int> = emptySet(),
        monthDays: Set<Int> = emptySet(),
        clampMonthDays: Boolean = false,
        repeatCount: Int = 0,
    ): Schedule =
        Schedule(
            targetDay = utcMillis(anchor),
            targetHour = hour,
            targetMinute = minute,
            targetSecond = second,
            repeatInterval = interval,
            repeatUnit = unit,
            weekdays = weekdays,
            weekdayOrdinals = weekdayOrdinals,
            monthDays = monthDays,
            clampMonthDays = clampMonthDays,
            repeatCount = repeatCount,
        )

    // —— 导出：确定性 golden ——

    @Test
    fun `各单位导出的golden串`() {
        // 2026-08-10 是周一；2026-09-08 是九月的第二个周二
        assertEquals(
            "FREQ=DAILY",
            scheduleOf(LocalDate(2026, 8, 10), unit = RepeatUnit.DAY).toRrule(),
        )
        assertEquals(
            "FREQ=DAILY;INTERVAL=3;COUNT=5",
            scheduleOf(LocalDate(2026, 8, 10), unit = RepeatUnit.DAY, interval = 3, repeatCount = 5).toRrule(),
        )
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO",
            scheduleOf(LocalDate(2026, 8, 10), unit = RepeatUnit.WEEK).toRrule(),
        )
        assertEquals(
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR",
            scheduleOf(
                LocalDate(2026, 8, 10),
                unit = RepeatUnit.WEEK,
                interval = 2,
                weekdays = setOf(WeekDay.FRIDAY, WeekDay.MONDAY),
            ).toRrule(),
        )
        assertEquals(
            "FREQ=MONTHLY;BYMONTHDAY=15",
            scheduleOf(LocalDate(2026, 1, 15), unit = RepeatUnit.MONTH, monthDays = setOf(15)).toRrule(),
        )
        assertEquals(
            "FREQ=MONTHLY;BYMONTHDAY=1,15,-1",
            scheduleOf(LocalDate(2026, 1, 15), unit = RepeatUnit.MONTH, monthDays = setOf(-1, 1, 15)).toRrule(),
        )
        assertEquals(
            "FREQ=MONTHLY;BYDAY=2TU",
            scheduleOf(
                LocalDate(2026, 9, 8),
                unit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.TUESDAY),
                weekdayOrdinals = setOf(2),
            ).toRrule(),
        )
        assertEquals(
            "FREQ=MONTHLY;BYDAY=1TU,-1TU",
            scheduleOf(
                LocalDate(2026, 9, 1),
                unit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.TUESDAY),
                weekdayOrdinals = setOf(-1, 1),
            ).toRrule(),
        )
        assertEquals(
            "FREQ=MONTHLY;BYDAY=MO,WE",
            scheduleOf(LocalDate(2026, 9, 7), unit = RepeatUnit.MONTH, weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY)).toRrule(),
        )
        assertEquals(
            "FREQ=YEARLY",
            scheduleOf(LocalDate(2020, 6, 15), unit = RepeatUnit.YEAR).toRrule(),
        )
        assertEquals(
            "FREQ=HOURLY;INTERVAL=8",
            scheduleOf(LocalDate(2026, 8, 26), hour = 8, unit = RepeatUnit.HOUR, interval = 8).toRrule(),
        )
        assertEquals(
            "FREQ=MINUTELY;INTERVAL=30",
            scheduleOf(LocalDate(2026, 8, 26), unit = RepeatUnit.MINUTE, interval = 30).toRrule(),
        )
    }

    @Test
    fun `收缩不可达的clamp月内日可导出`() {
        // {1, 15} + clamp：任何月都含 1/15，收缩不可达 → 与 RFC 语义一致，可导出
        assertEquals(
            "FREQ=MONTHLY;BYMONTHDAY=1,15",
            scheduleOf(LocalDate(2026, 1, 15), unit = RepeatUnit.MONTH, monthDays = setOf(1, 15), clampMonthDays = true).toRrule(),
        )
    }

    // —— 解析：映射与缺省 ——

    @Test
    fun `解析各形态映射到字段`() {
        val weeklyDefault =
            parseRrule("FREQ=WEEKLY", LocalDateTime(LocalDate(2026, 8, 12), LocalTime(9, 30)))
        // RFC 缺省 BYDAY = DTSTART 星期（2026-08-12 是周三）
        assertEquals(setOf(WeekDay.WEDNESDAY), weeklyDefault.weekdays)
        assertEquals(1, weeklyDefault.repeatInterval)
        assertEquals(0, weeklyDefault.repeatCount)

        val weeklyExplicit =
            parseRrule("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;COUNT=10", LocalDateTime(LocalDate(2026, 8, 10), LocalTime(9, 0)))
        assertEquals(setOf(WeekDay.FRIDAY, WeekDay.MONDAY), weeklyExplicit.weekdays)
        assertEquals(2, weeklyExplicit.repeatInterval)
        assertEquals(10, weeklyExplicit.repeatCount)
        assertEquals(RepeatUnit.WEEK, weeklyExplicit.repeatUnit)

        val monthlyDefault = parseRrule("FREQ=MONTHLY", LocalDateTime(LocalDate(2026, 9, 30), LocalTime(8, 0)))
        // 无 BY*：按 RFC「DTSTART 日号、缺日跳过」→ 单元素选择器、clamp=false
        assertEquals(setOf(30), monthlyDefault.monthDays)
        assertEquals(false, monthlyDefault.clampMonthDays)

        val nthTuesday = parseRrule("FREQ=MONTHLY;BYDAY=2TU,-1TU", LocalDateTime(LocalDate(2026, 9, 8), LocalTime(9, 0)))
        assertEquals(setOf(WeekDay.TUESDAY), nthTuesday.weekdays)
        assertEquals(setOf(2, -1), nthTuesday.weekdayOrdinals)

        val hourly = parseRrule("FREQ=HOURLY;INTERVAL=8", LocalDateTime(LocalDate(2026, 8, 26), LocalTime(8, 0)))
        assertEquals(RepeatUnit.HOUR, hourly.repeatUnit)
        assertEquals(8, hourly.repeatInterval)
    }

    // —— 往返性质 ——

    @Test
    fun `显式选择器日程往返等值`() {
        val battery =
            listOf(
                scheduleOf(LocalDate(2026, 8, 10), unit = RepeatUnit.DAY),
                scheduleOf(LocalDate(2026, 8, 10), unit = RepeatUnit.DAY, interval = 3, repeatCount = 5),
                scheduleOf(
                    LocalDate(2026, 8, 10),
                    unit = RepeatUnit.WEEK,
                    weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY),
                ),
                scheduleOf(
                    LocalDate(2026, 8, 12),
                    unit = RepeatUnit.WEEK,
                    interval = 2,
                    weekdays = setOf(WeekDay.WEDNESDAY, WeekDay.THURSDAY),
                ),
                scheduleOf(LocalDate(2026, 1, 31), unit = RepeatUnit.MONTH, monthDays = setOf(-1)),
                scheduleOf(LocalDate(2026, 1, 15), unit = RepeatUnit.MONTH, monthDays = setOf(1, 15), repeatCount = 12),
                scheduleOf(LocalDate(2026, 9, 8), unit = RepeatUnit.MONTH, weekdays = setOf(WeekDay.TUESDAY), weekdayOrdinals = setOf(2)),
                scheduleOf(LocalDate(2020, 6, 15), unit = RepeatUnit.YEAR, interval = 1),
                scheduleOf(LocalDate(2026, 8, 26), hour = 8, unit = RepeatUnit.HOUR, interval = 8),
                scheduleOf(LocalDate(2026, 8, 26), unit = RepeatUnit.MINUTE, interval = 30, repeatCount = 4),
            )
        for (schedule in battery) {
            assertEquals(schedule, parseRrule(schedule.toRrule(), anchorOf(schedule)), "round-trip failed for: ${schedule.toRrule()}")
        }
    }

    @Test
    fun `无选择器周重复导出后推算序列不变`() {
        // 表述形态改变（隐式锚点星期 → 显式 BYDAY），出现序列必须一致
        val original = scheduleOf(LocalDate(2026, 8, 10), 9, 0, 0, unit = RepeatUnit.WEEK)
        val reparsed = parseRrule(original.toRrule(), anchorOf(original))

        var now = LocalDateTime(LocalDate(2026, 8, 11), LocalTime(12, 0)).toInstant(shanghai)
        repeat(5) {
            assertEquals(original.nextTarget(now, shanghai), reparsed.nextTarget(now, shanghai))
            now += 7.days
        }
    }

    // —— 拒绝面：导出 ——

    @Test
    fun `导出拒绝面`() {
        val lunar = scheduleOf(LocalDate(2026, 9, 20), unit = RepeatUnit.YEAR).let { it.copy(lunar = true) }
        val nonRepeating = scheduleOf(LocalDate(2026, 8, 10), unit = RepeatUnit.NONE).copy(repeatInterval = 0)
        val unsetTarget = scheduleOf(LocalDate(2026, 8, 10), unit = RepeatUnit.WEEK).copy(targetDay = -1L)
        val monthNoSelector = scheduleOf(LocalDate(2026, 1, 31), unit = RepeatUnit.MONTH)
        val monthClampShrinkable = scheduleOf(LocalDate(2026, 1, 31), unit = RepeatUnit.MONTH, monthDays = setOf(31), clampMonthDays = true)
        val leapDayYearly = scheduleOf(LocalDate(2024, 2, 29), unit = RepeatUnit.YEAR)

        for (case in listOf(lunar, nonRepeating, unsetTarget, monthNoSelector, monthClampShrinkable, leapDayYearly)) {
            assertFailsWith<RruleConversionException> { case.toRrule() }
            assertNull(case.toRruleOrNull())
        }
    }

    // —— 拒绝面：解析 ——

    @Test
    fun `解析拒绝面`() {
        val anchor = LocalDateTime(LocalDate(2026, 9, 8), LocalTime(9, 0))
        val cases =
            listOf(
                "" to "空串",
                "FREQ=WEEKLY;UNTIL=20271231T000000Z" to "UNTIL",
                "FREQ=WEEKLY;COUNT=3;UNTIL=20271231T000000Z" to "COUNT 与 UNTIL 并存",
                "FREQ=WEEKLY;WKST=SU" to "非周一 WKST",
                "FREQ=MONTHLY;BYSETPOS=1;BYDAY=TU" to "BYSETPOS",
                "FREQ=MONTHLY;BYMONTH=5" to "BYMONTH",
                "FREQ=WEEKLY;INTERVAL=0" to "INTERVAL=0",
                "FREQ=WEEKLY;INTERVAL=x" to "INTERVAL 非数字",
                "FREQ=WEEKLY;COUNT=-2" to "COUNT 负数",
                "INTERVAL=2" to "缺 FREQ",
                "FREQ=SECONDLY" to "不支持的 FREQ",
                "FREQ=WEEKLY;FREQ=MONTHLY" to "重复键",
                "FREQ=WEEKLY;BYDAY=2TU" to "周重复带序数 BYDAY",
                "FREQ=DAILY;BYDAY=MO" to "日重复带 BYDAY",
                "FREQ=YEARLY;BYMONTHDAY=15" to "年重复带 BYMONTHDAY",
                "FREQ=MONTHLY;BYDAY=TU;BYMONTHDAY=15" to "BYDAY 与 BYMONTHDAY 并存",
                "FREQ=MONTHLY;BYMONTHDAY=0" to "BYMONTHDAY 为 0",
                "FREQ=MONTHLY;BYDAY=XU" to "未知星期代码",
                "FREQ=WEEKLY;RRULE:X" to "未知键",
                "freq=weekly" to "键小写",
            )
        for ((rrule, description) in cases) {
            assertFailsWith<RruleConversionException>(description) { parseRrule(rrule, anchor) }
        }
    }

    @Test
    fun `二月二十九DTSTART的年重复解析拒绝`() {
        val anchor = LocalDateTime(LocalDate(2028, 2, 29), LocalTime(9, 0))

        assertFailsWith<RruleConversionException> { parseRrule("FREQ=YEARLY", anchor) }
    }

    @Test
    fun `锚点不是规则日时转译构造校验`() {
        // 2026-09-09 是周三，不是 BYDAY=MO 的规则日
        val anchor = LocalDateTime(LocalDate(2026, 9, 9), LocalTime(9, 0))

        val exception = assertFailsWith<RruleConversionException> { parseRrule("FREQ=WEEKLY;BYDAY=MO", anchor) }
        assertEquals(true, exception.message!!.contains("valid Schedule"))
    }
}
