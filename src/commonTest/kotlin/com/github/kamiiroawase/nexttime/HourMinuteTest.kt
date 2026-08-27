package com.github.kamiiroawase.nexttime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

/** 小时/分钟重复：真实时长格点、DST 漂移、长跨度 epoch 秒运算与入参校验 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class HourMinuteTest {
    @Test
    fun `小时重复推进与含等于`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 26)), hour = 8, interval = 2, unit = RepeatUnit.HOUR)

        assertEquals(zdt(2026, 8, 26, 14), schedule.nextTarget(zdt(2026, 8, 26, 12, 30), shanghai))
        assertEquals(zdt(2026, 8, 26, 14), schedule.nextTarget(zdt(2026, 8, 26, 14), shanghai))
        // 纯间隔跨日连续：23:30 后的下一个出现是次日 00:00（格点不按日重开）
        assertEquals(zdt(2026, 8, 27), schedule.nextTarget(zdt(2026, 8, 26, 23, 30), shanghai))
    }

    @Test
    fun `分钟重复推进`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 26)), hour = 8, interval = 15, unit = RepeatUnit.MINUTE)

        assertEquals(zdt(2026, 8, 26, 8, 15), schedule.nextTarget(zdt(2026, 8, 26, 8, 7, 30), shanghai))
        assertEquals(zdt(2026, 8, 26, 8, 45), schedule.nextTarget(zdt(2026, 8, 26, 8, 45), shanghai))
    }

    @Test
    fun `锚点在未来返回锚点且豁免 until`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 12, 1)), hour = 10, interval = 1, unit = RepeatUnit.HOUR)

        assertEquals(
            zdt(2026, 12, 1, 10),
            schedule.nextTarget(zdt(2026, 8, 26), shanghai, zdt(2026, 11, 1)),
        )
    }

    @Test
    fun `until 封顶与恰等于保留`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 26)), hour = 8, interval = 2, unit = RepeatUnit.HOUR)

        assertNull(schedule.nextTarget(zdt(2026, 8, 26, 9), shanghai, zdt(2026, 8, 26, 9, 59)))
        assertEquals(
            zdt(2026, 8, 26, 10),
            schedule.nextTarget(zdt(2026, 8, 26, 9), shanghai, zdt(2026, 8, 26, 10)),
        )
    }

    @Test
    fun `跨春令时按真实时长漂移本地钟面`() {
        // 纽约 2026-03-08 02:00→03:00：每 6 小时的真实间隔不变。锚点 03-07 09:00 EST
        // 起 +18 真实小时 = 03-08 04:00 EDT（钟面 +18h 才是 03:00——春令时前进拨，
        // 真实时长格点的本地钟面比钟面格点多走 1 小时）
        val schedule =
            schedule(utcMillis(LocalDate(2026, 3, 7)), hour = 9, interval = 6, unit = RepeatUnit.HOUR)

        val anchor = instantOf(newYork, 2026, 3, 7, 9)
        val crossing = anchor + 18.hours

        assertEquals(crossing, schedule.nextTarget(crossing, newYork))
        assertEquals(4, crossing.toLocalDateTime(newYork).hour)
        // 真实间隔保持 6 小时：crossing 前一瞬的 previous 恰为 anchor + 12h
        assertEquals(anchor + 12.hours, schedule.previousTarget(crossing - 1.nanoseconds, newYork))
    }

    @Test
    fun `previousTarget 含等于与锚点下界`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 26)), hour = 8, interval = 2, unit = RepeatUnit.HOUR)

        assertEquals(zdt(2026, 8, 26, 12), schedule.previousTarget(zdt(2026, 8, 26, 12), shanghai))
        assertNull(schedule.previousTarget(zdt(2026, 8, 26, 7, 59, 59), shanghai))
        // 锚点与第二次出现之间返回锚点
        assertEquals(zdt(2026, 8, 26, 8), schedule.previousTarget(zdt(2026, 8, 26, 9, 30), shanghai))
    }

    @Test
    fun `长跨度分钟格点不溢出`() {
        // 0001-01-01 锚点每 1 分钟推到 2026 年：约两千年的纳秒差（约 6.4e19）远超
        // Duration 的 Long 表示（约 292 年即饱和）——epoch 秒运算不受影响。
        // 格点参照取 anchor(zone)：远古年份的 LMT 偏移带秒级分量（上海 +08:05:43），
        // 格点锚在组合时刻而非 UTC 零点
        val schedule =
            schedule(utcMillis(LocalDate(1, 1, 1)), interval = 1, unit = RepeatUnit.MINUTE)

        val now = zdt(2026, 8, 26, 12)
        val anchorSeconds = schedule.anchor(shanghai)!!.epochSeconds

        val next = schedule.nextTarget(now, shanghai)!!
        assertTrue(next >= now)
        assertTrue(next - now < 1.minutes)
        assertEquals(0L, (next.epochSeconds - anchorSeconds) % 60)

        val previous = schedule.previousTarget(now, shanghai)!!
        assertTrue(previous <= now)
        assertTrue(now - previous < 1.minutes)
        assertEquals(0L, (previous.epochSeconds - anchorSeconds) % 60)
    }

    @Test
    fun `远古锚点天重复不再误抛越界`() {
        // 回归：天/周快路径的估计曾用 Duration 纳秒差，锚点早于 now 约 292 年以上
        // 即饱和溢出、误抛「left the supported date range」；改 epoch 秒差后
        // 0001 年锚点的每日格点正常落位
        val schedule =
            schedule(utcMillis(LocalDate(1, 1, 1)), interval = 1, unit = RepeatUnit.DAY)

        val now = zdt(2026, 8, 26, 12)

        assertEquals(zdt(2026, 8, 27), schedule.nextTarget(now, shanghai))
        assertEquals(zdt(2026, 8, 26), schedule.previousTarget(now, shanghai))
    }

    @Test
    fun `远古锚点周重复跨饱和界`() {
        // 1500 年锚点距 now 超 500 年（Duration 纳秒饱和区）：epoch 秒差修复后正常落位。
        // 天/周是钟面格点（按本地日期重组），跨 1901 年 LMT→CST 偏移变化后 epoch 秒
        // 对齐不保持，断言按本地日期与本地时刻对齐
        val schedule =
            schedule(utcMillis(LocalDate(1500, 1, 1)), interval = 1, unit = RepeatUnit.WEEK)

        val now = zdt(2026, 8, 26, 12)
        val next = schedule.nextTarget(now, shanghai)!!

        assertTrue(next >= now)
        assertEquals(0, timeOf(next, shanghai).hour)
        assertEquals(0, timeOf(next, shanghai).minute)
        assertEquals(
            0,
            (dateOf(next, shanghai).toEpochDays() - LocalDate(1500, 1, 1).toEpochDays()) % 7,
        )
        assertEquals(next, schedule.previousTarget(next, shanghai))
    }

    @Test
    fun `repeatUnit 新常量与非法值`() {
        schedule(utcMillis(LocalDate(2026, 8, 26)), interval = 1, unit = RepeatUnit.HOUR)
        schedule(utcMillis(LocalDate(2026, 8, 26)), interval = 1, unit = RepeatUnit.MINUTE)

        assertFailsWith<IllegalArgumentException> {
            schedule(utcMillis(LocalDate(2026, 8, 26)), interval = 1, unit = 7)
        }
    }

    @Test
    fun `lunar 与小时重复无关`() {
        val day = utcMillis(LocalDate(2026, 8, 26))

        assertEquals(
            schedule(day, hour = 8, interval = 2, unit = RepeatUnit.HOUR)
                .nextTarget(zdt(2026, 8, 26, 9), shanghai),
            schedule(day, hour = 8, interval = 2, unit = RepeatUnit.HOUR, lunar = true)
                .nextTarget(zdt(2026, 8, 26, 9), shanghai),
        )
    }

    @Test
    fun `正反对偶不变量`() {
        val schedules =
            listOf(
                schedule(utcMillis(LocalDate(2026, 8, 3)), hour = 8, interval = 6, unit = RepeatUnit.HOUR),
                schedule(utcMillis(LocalDate(2026, 8, 26)), hour = 8, interval = 15, unit = RepeatUnit.MINUTE),
            )

        val now = zdt(2026, 8, 26, 12, 30, 45)
        for (schedule in schedules) {
            val next = schedule.nextTarget(now, shanghai)!!
            val previous = schedule.previousTarget(now, shanghai)!!

            assertEquals(next, schedule.previousTarget(next, shanghai))
            assertEquals(previous, schedule.nextTarget(previous - 1.nanoseconds, shanghai))
            assertTrue(previous <= now && now <= next)
        }
    }
}
