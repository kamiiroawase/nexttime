package io.github.kamiiroawase.nexttime

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 月重复 + 星期×序数选择器：第 n 个/倒数第 n 个星期几、序数超界跳过、锚点隶属校验 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class NthWeekdayRepeatTest {
    // 2026-09-01 是周二：九月周二为 1、8、15、22、29，第二个周二 = 9/8
    // 2026-09-04 是周五：九月周五为 4、11、18、25，最后一个周五 = 9/25
    private val secondTuesday = utcMillis(LocalDate(2026, 9, 8))

    @Test
    fun `每月第二个周二`() {
        val schedule =
            schedule(secondTuesday, interval = 1, unit = RepeatUnit.MONTH, weekdays = setOf(WeekDay.TUESDAY), weekdayOrdinals = setOf(2))

        // 十月周二为 6、13、20、27，第二个 = 10/13
        assertEquals(LocalDate(2026, 10, 13), dateOf(schedule.nextTarget(zdt(2026, 9, 20), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 9, 8), dateOf(schedule.previousTarget(zdt(2026, 10, 12, 23, 0, 0), shanghai)!!, shanghai))
    }

    @Test
    fun `每月最后一个周五`() {
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 9, 25)),
                interval = 1,
                unit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.FRIDAY),
                weekdayOrdinals = setOf(-1),
            )

        // 十月周五为 2、9、16、23、30，最后一个 = 10/30
        assertEquals(LocalDate(2026, 10, 30), dateOf(schedule.nextTarget(zdt(2026, 9, 26), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 10, 30), dateOf(schedule.previousTarget(zdt(2026, 11, 2), shanghai)!!, shanghai))
    }

    @Test
    fun `第五个星期几超界的月份跳过`() {
        // 2026-12-29 是十二月的第五个周二（12/1、8、15、22、29）；2027 年 1、2 月
        // 都只有四个周二，跳到 2027-03-30（三月周二 2、9、16、23、30）
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 12, 29)),
                interval = 1,
                unit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.TUESDAY),
                weekdayOrdinals = setOf(5),
            )

        assertEquals(LocalDate(2027, 3, 30), dateOf(schedule.nextTarget(zdt(2027, 1, 1), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 12, 29), dateOf(schedule.previousTarget(zdt(2027, 1, 1), shanghai)!!, shanghai))
    }

    @Test
    fun `首尾序数每月各出现一次`() {
        // 每月第一个与最后一个周二：锚点 9/1（首个周二），九月产出 9/1 与 9/29
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 9, 1)),
                interval = 1,
                unit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.TUESDAY),
                weekdayOrdinals = setOf(1, -1),
            )

        val days =
            schedule
                .occurrences(zdt(2026, 8, 30), shanghai)
                .take(4)
                .map { dateOf(it, shanghai) }
                .toList()

        // 十月周二 6、13、20、27：首 10/6、尾 10/27
        assertEquals(
            listOf(LocalDate(2026, 9, 1), LocalDate(2026, 9, 29), LocalDate(2026, 10, 6), LocalDate(2026, 10, 27)),
            days,
        )
    }

    @Test
    fun `空序数即每月全部命中星期`() {
        // 2026-09-07 周一：九月周一为 7、14、21、28
        val schedule =
            schedule(utcMillis(LocalDate(2026, 9, 7)), interval = 1, unit = RepeatUnit.MONTH, weekdays = setOf(WeekDay.MONDAY))

        assertEquals(LocalDate(2026, 9, 14), dateOf(schedule.nextTarget(zdt(2026, 9, 8), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 10, 5), dateOf(schedule.nextTarget(zdt(2026, 9, 29, 12, 0, 0), shanghai)!!, shanghai))
        assertEquals(LocalDate(2026, 9, 28), dateOf(schedule.previousTarget(zdt(2026, 9, 30), shanghai)!!, shanghai))
    }

    @Test
    fun `锚点月内早于锚点日的规则日不算出现`() {
        // 首尾序数、锚点 9/29（尾）：九月的首个周二 9/1 早于锚点，不是出现
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 9, 29)),
                interval = 1,
                unit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.TUESDAY),
                weekdayOrdinals = setOf(1, -1),
            )

        assertEquals(LocalDate(2026, 9, 29), dateOf(schedule.previousTarget(zdt(2026, 9, 30), shanghai)!!, shanghai))
    }

    @Test
    fun `锚点不是目标序数日构造期拒绝`() {
        // 9/1 是第一个周二，配序数 2 不隶属
        assertFailsWith<IllegalArgumentException> {
            schedule(
                utcMillis(LocalDate(2026, 9, 1)),
                interval = 1,
                unit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.TUESDAY),
                weekdayOrdinals = setOf(2),
            )
        }
        // 月重复配星期时锚点星期须在集合内：9/8 周二配周一
        assertFailsWith<IllegalArgumentException> {
            schedule(secondTuesday, interval = 1, unit = RepeatUnit.MONTH, weekdays = setOf(WeekDay.MONDAY))
        }
    }

    @Test
    fun `序数选择器的构造校验`() {
        val anchor = secondTuesday
        // 序数必须配星期集合
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.MONTH, weekdayOrdinals = setOf(2))
        }
        // 序数必须配月单位
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.WEEK, weekdays = setOf(WeekDay.TUESDAY), weekdayOrdinals = setOf(2))
        }
        // 0 与 ±6 越界
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.MONTH, weekdays = setOf(WeekDay.TUESDAY), weekdayOrdinals = setOf(0))
        }
        assertFailsWith<IllegalArgumentException> {
            schedule(anchor, interval = 1, unit = RepeatUnit.MONTH, weekdays = setOf(WeekDay.TUESDAY), weekdayOrdinals = setOf(6, -6))
        }
    }
}
