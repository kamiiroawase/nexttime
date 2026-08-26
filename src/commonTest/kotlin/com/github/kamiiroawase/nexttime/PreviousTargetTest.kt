package com.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds

/** previousTarget：反向取出现序列中不晚于 before 的最后一项（含等于、锚点下界） */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class PreviousTargetTest {
    @Test
    fun `非重复返回锚点或 null`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 10, 1)), hour = 9)

        assertEquals(zdt(2026, 10, 1, 9), schedule.previousTarget(zdt(2027, 1, 1), shanghai))
        // 恰等于锚点：含等于
        assertEquals(zdt(2026, 10, 1, 9), schedule.previousTarget(zdt(2026, 10, 1, 9), shanghai))
        // 锚点前一瞬：出现序列为空侧，返回 null
        assertNull(schedule.previousTarget(zdt(2026, 10, 1, 8, 59, 59), shanghai))
        assertNull(schedule(targetDay = -1L).previousTarget(zdt(2026, 1, 1), shanghai))
    }

    @Test
    fun `天重复含等于与锚点下界`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 1)), hour = 12, interval = 3, unit = 1)

        // 出现：08-01、08-04、08-07、08-10……
        assertEquals(zdt(2026, 8, 10, 12), schedule.previousTarget(zdt(2026, 8, 10, 12), shanghai))
        assertEquals(zdt(2026, 8, 7, 12), schedule.previousTarget(zdt(2026, 8, 10, 11, 59, 59), shanghai))
        assertNull(schedule.previousTarget(zdt(2026, 8, 1, 11, 59, 59), shanghai))
        // 锚点与第二次出现之间：返回锚点
        assertEquals(zdt(2026, 8, 1, 12), schedule.previousTarget(zdt(2026, 8, 2), shanghai))
    }

    @Test
    fun `天重复跨夏令时重叠日取较早出现`() {
        // 纽约 2026-11-01 秋令时回拨，01:30 出现两次：出现取较早（EDT，05:30 UTC）；
        // before 落在重叠后段（同钟面 01:30 EST，06:30 UTC）时，上一个出现仍是较早者
        val schedule =
            schedule(utcMillis(LocalDate(2026, 11, 1)), hour = 1, minute = 30, interval = 1, unit = 1)

        val occurrence = schedule.previousTarget(instantOf(newYork, 2026, 11, 2), newYork)!!

        assertEquals(instantOf(newYork, 2026, 11, 1, 1, 30), occurrence)
        assertEquals(occurrence, schedule.previousTarget(instantOf(TimeZone.UTC, 2026, 11, 1, 6, 30), newYork))
    }

    @Test
    fun `周重复多间隔反向`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 3)), interval = 2, unit = 2)

        // 出现：08-03、08-17、08-31……
        assertEquals(zdt(2026, 8, 17), schedule.previousTarget(zdt(2026, 8, 24), shanghai))
        assertEquals(zdt(2026, 8, 31), schedule.previousTarget(zdt(2026, 8, 31), shanghai))
    }

    @Test
    fun `1970 前负毫秒锚点天重复`() {
        // 锚点 1961-07-01（负毫秒）每日出现：before 2026-08-26 12:00 → 当日零点
        val schedule =
            schedule(utcMillis(LocalDate(1961, 7, 1)), interval = 1, unit = 1)

        assertEquals(zdt(2026, 8, 26), schedule.previousTarget(zdt(2026, 8, 26, 12), shanghai))
    }

    @Test
    fun `月重复收缩回弹反向`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = 3)

        // 出现：1/31、2/28、3/31、4/30……收缩以锚点日为基准
        assertEquals(zdt(2026, 2, 28), schedule.previousTarget(zdt(2026, 3, 15), shanghai))
        assertEquals(zdt(2026, 3, 31), schedule.previousTarget(zdt(2026, 4, 2), shanghai))
        assertEquals(zdt(2026, 1, 31), schedule.previousTarget(zdt(2026, 2, 5), shanghai))
    }

    @Test
    fun `年重复闰日回弹反向`() {
        val schedule =
            schedule(utcMillis(LocalDate(2024, 2, 29)), interval = 1, unit = 4)

        // 出现：2024/2/29、2025→2/28、2026→2/28、……、2028→2/29
        assertEquals(zdt(2025, 2, 28), schedule.previousTarget(zdt(2026, 1, 1), shanghai))
        assertEquals(zdt(2028, 2, 29), schedule.previousTarget(zdt(2028, 3, 1), shanghai))
    }

    @Test
    fun `年重复多间隔估计回退`() {
        val schedule =
            schedule(utcMillis(LocalDate(1990, 6, 15)), hour = 9, interval = 5, unit = 4)

        // 格点：1990、1995、……、2025、2030
        assertEquals(zdt(2025, 6, 15, 9), schedule.previousTarget(zdt(2026, 8, 26), shanghai))
        assertEquals(zdt(2030, 6, 15, 9), schedule.previousTarget(zdt(2030, 6, 15, 9), shanghai))
    }

    @Test
    fun `月重复跨半世纪估计回退`() {
        val schedule =
            schedule(utcMillis(LocalDate(1975, 3, 31)), interval = 1, unit = 3)

        // 3/31 序列在 7 月有 31 可回弹：before 2026-08-26 的上一个出现是 2026-07-31
        assertEquals(zdt(2026, 7, 31), schedule.previousTarget(zdt(2026, 8, 26), shanghai))
    }

    @Test
    fun `before 超出支持范围返回界内最后一次出现`() {
        // 公历年重复：格点到 9999 为止，before 超界不抛、返回界内最后格点
        val schedule =
            schedule(utcMillis(LocalDate(9990, 6, 1)), interval = 1, unit = 4)

        assertEquals(zdt(9999, 6, 1), schedule.previousTarget(zdt(9999, 12, 31) + 1.days, shanghai))

        // 农历同理：年表上界 9999 收尾
        val lunarSchedule =
            schedule(
                solarMillis(LunarDay.fromYmd(9990, 6, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        assertEquals(9999, lunarDayOf(lunarSchedule.previousTarget(zdt(9999, 12, 31) + 1.days, shanghai)!!, shanghai).year)
    }

    @Test
    fun `previousTarget 与 nextTarget 互为对偶`() {
        val schedules =
            listOf(
                schedule(utcMillis(LocalDate(2026, 8, 3)), hour = 8, interval = 2, unit = 2),
                schedule(utcMillis(LocalDate(2026, 1, 31)), interval = 1, unit = 3),
                schedule(utcMillis(LocalDate(2020, 6, 15)), hour = 9, interval = 1, unit = 4),
                schedule(utcMillis(LocalDate(1961, 7, 1)), interval = 5, unit = 1),
                schedule(
                    solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                    lunar = true,
                    interval = 1,
                    unit = 4,
                ),
                schedule(
                    solarMillis(LunarDay.fromYmd(2025, 7, 1).getSolarDay()),
                    lunar = true,
                    interval = 1,
                    unit = 3,
                ),
            )

        val now = zdt(2026, 8, 26, 12)
        for (schedule in schedules) {
            val next = schedule.nextTarget(now, shanghai)!!
            val previous = schedule.previousTarget(now, shanghai)!!

            // 含等于：出现本身的 previous 是自身；出现前一瞬的 nextTarget 也是自身
            assertEquals(next, schedule.previousTarget(next, shanghai))
            assertEquals(previous, schedule.nextTarget(previous - 1.nanoseconds, shanghai))
            // previous 与 next 夹住 now，且 nextTarget 含等于语义下 previous 自身即可返回
            assertTrue(previous <= now)
            assertTrue(next >= now)
            assertEquals(previous, schedule.nextTarget(previous, shanghai))
        }
    }

    @Test
    fun `农历月重复闰月不参与反向跳过闰月`() {
        // 锚点 = 出现 #0 = 农历六月初一：出现序列六月、（跳过闰六月）七月、八月……
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, 6, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val july = schedule.previousTarget(zdt(2025, 8, 25), shanghai)!!
        val julyLunar = lunarDayOf(july, shanghai)
        assertEquals(2025, julyLunar.year)
        assertEquals(7, julyLunar.month)
        assertEquals(1, julyLunar.day)

        val june = schedule.previousTarget(july - 1.nanoseconds, shanghai)!!
        val juneLunar = lunarDayOf(june, shanghai)
        assertEquals(2025, juneLunar.year)
        assertEquals(6, juneLunar.month)
        assertEquals(1, juneLunar.day)
    }

    @Test
    fun `闰六月初一年重复不参与反向命中格点年`() {
        // 闰六年探针实测：2025/2036/2055/2074；闰六月初一 2025-07-25、2036-07-23
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2025, -6, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        assertEquals(
            LocalDate(2036, 7, 23),
            dateOf(schedule.previousTarget(zdt(2036, 7, 23), shanghai)!!, shanghai),
        )
        assertEquals(
            LocalDate(2025, 7, 25),
            dateOf(schedule.previousTarget(zdt(2036, 7, 22), shanghai)!!, shanghai),
        )
    }

    @Test
    fun `农历年重复锚点未到返回锚点或 null`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2026, 8, 15).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        // 锚点 2026 农历八月十五 = 公历 2026-09-25
        assertNull(schedule.previousTarget(zdt(2026, 9, 24), shanghai))
        assertEquals(zdt(2026, 9, 25), schedule.previousTarget(zdt(2026, 9, 25), shanghai))
    }

    @Test
    fun `农历月重复跨半世纪快路径落正确格点`() {
        // 远古锚点：跳过区只记录格点，回退组合必须落在 ≤ before 的最后一个出现
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(1990, 1, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 3,
            )

        val before = zdt(2026, 8, 26)
        val previous = schedule.previousTarget(before, shanghai)!!

        // 正确性定义：previous 是出现、不晚于 before，且下一个出现已过 before
        assertEquals(previous, schedule.nextTarget(previous - 1.nanoseconds, shanghai))
        assertTrue(previous <= before)
        assertTrue(schedule.nextTarget(before, shanghai)!! > before)
    }
}
