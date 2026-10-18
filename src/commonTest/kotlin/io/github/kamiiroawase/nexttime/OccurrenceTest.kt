package io.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/** 出现序列枚举 occurrences：惰性、与 nextTarget 一致、until/count 完结终止、边界形态 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class OccurrenceTest {
    @Test
    fun `首个元素等于nextTarget且惰性产出`() {
        // 无界日重复序列上 take(3) 正常返回：序列惰性求值，不预推无限出现
        val schedule = schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = RepeatUnit.DAY)
        val now = zdt(2026, 8, 10, 12, 0, 0)

        val taken = schedule.occurrences(now, shanghai).take(3).toList()

        assertEquals(schedule.nextTarget(now, shanghai), taken.first())
        assertEquals(
            listOf(LocalDate(2026, 8, 11), LocalDate(2026, 8, 12), LocalDate(2026, 8, 13)),
            taken.map { dateOf(it, shanghai) },
        )
    }

    @Test
    fun `周选择器序列沿规则日产出`() {
        val schedule =
            schedule(
                utcMillis(LocalDate(2026, 8, 10)),
                9,
                0,
                0,
                interval = 1,
                unit = RepeatUnit.WEEK,
                weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY),
            )

        val days =
            schedule
                .occurrences(zdt(2026, 8, 10, 9, 30, 0), shanghai)
                .take(5)
                .map { dateOf(it, shanghai) }
                .toList()

        assertEquals(
            listOf(LocalDate(2026, 8, 12), LocalDate(2026, 8, 14), LocalDate(2026, 8, 17), LocalDate(2026, 8, 19), LocalDate(2026, 8, 21)),
            days,
        )
    }

    @Test
    fun `until上限终止序列`() {
        val schedule = schedule(utcMillis(LocalDate(2020, 6, 15)), interval = 1, unit = RepeatUnit.YEAR)

        val days =
            schedule
                .occurrences(zdt(2020, 1, 1), shanghai, until = zdt(2022, 1, 1))
                .toList()

        assertEquals(listOf(LocalDate(2020, 6, 15), LocalDate(2021, 6, 15)), days.map { dateOf(it, shanghai) })
    }

    @Test
    fun `次数上限终止序列`() {
        val schedule = schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = RepeatUnit.DAY, repeatCount = 3)

        val days =
            schedule
                .occurrences(zdt(2026, 8, 3, 0, 0, 0), shanghai)
                .toList()

        assertEquals(listOf(LocalDate(2026, 8, 3)), days.map { dateOf(it, shanghai) })
    }

    @Test
    fun `未选目标日得空序列且不重复日程得单元素`() {
        assertTrue(schedule(-1L, interval = 1, unit = RepeatUnit.DAY).occurrences(zdt(2026, 8, 1), shanghai).toList().isEmpty())

        val single = schedule(utcMillis(LocalDate(2026, 8, 1)), 9, 0, 0)
        assertEquals(listOf(zdt(2026, 8, 1, 9, 0, 0)), single.occurrences(zdt(2026, 1, 1), shanghai).toList())
    }

    @Test
    fun `序列与逐步nextTarget步进一致`() {
        // 农历月重复（含闰月跳年形态）：序列枚举与「nextTarget → +1ns → 再 next」逐步
        // 推进完全一致——occurrences 的实现即逐步 nextTarget，此为语义锚定
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 5, 1).getSolarDay()),
                lunar = true,
                leapCount = false,
                interval = 1,
                unit = RepeatUnit.MONTH,
            )
        val now = zdt(2025, 6, 1)

        val fromSequence = schedule.occurrences(now, shanghai).take(4).toList()

        val stepped =
            buildList {
                var next = schedule.nextTarget(now, shanghai)
                while (next != null && size < 4) {
                    add(next)
                    next = schedule.nextTarget(next + 1.nanoseconds, shanghai)
                }
            }

        assertEquals(stepped, fromSequence)
        assertTrue(fromSequence.all { it >= now })
    }
}
