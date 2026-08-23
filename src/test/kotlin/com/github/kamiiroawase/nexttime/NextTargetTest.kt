package com.github.kamiiroawase.nexttime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** 公历 nextTarget：组合、步进、月末收缩、夏令时与入参校验 */
class NextTargetTest {
    @Test
    fun `不重复返回目标日组合时刻`() {
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 24)), 8, 30, 0)

        val target = schedule.nextTarget(zdt(2026, 8, 23, 12, 0, 0), shanghai)!!

        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
        assertEquals(LocalTime.of(8, 30, 0), target.toLocalTime())
    }

    @Test
    fun `未选时间按当天零点`() {
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 24)), hour = -1, minute = -1, second = -1)

        val target = schedule.nextTarget(zdt(2026, 8, 23, 12, 0, 0), shanghai)!!

        assertEquals(LocalTime.MIDNIGHT, target.toLocalTime())
    }

    @Test
    fun `时分秒任一未选整体按零点`() {
        // 时选了分未选、分选了时未选，都整体按零点，不取部分时刻
        val hourOnly = schedule(utcMillis(LocalDate.of(2026, 8, 24)), hour = 8, minute = -1, second = -1)
        val minuteOnly = schedule(utcMillis(LocalDate.of(2026, 8, 24)), hour = -1, minute = 30, second = 0)

        assertEquals(LocalTime.MIDNIGHT, hourOnly.nextTarget(zdt(2026, 8, 23), shanghai)!!.toLocalTime())
        assertEquals(LocalTime.MIDNIGHT, minuteOnly.nextTarget(zdt(2026, 8, 23), shanghai)!!.toLocalTime())
    }

    @Test
    fun `时刻按日程时区组合`() {
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 24)))

        val target = schedule.nextTarget(zdt(2026, 8, 23, 12, 0, 0), ZoneId.of("America/New_York"))!!

        assertEquals("-04:00", target.offset.id)
        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
    }

    @Test
    fun `公历年重复推进到未来`() {
        val schedule = schedule(utcMillis(LocalDate.of(2020, 1, 1)), interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2027, 1, 1), target.toLocalDate())
    }

    @Test
    fun `公历天重复按间隔推进`() {
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 20)), interval = 2, unit = 1)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
    }

    @Test
    fun `公历周重复按间隔推进`() {
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 10)), interval = 2, unit = 2)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
    }

    @Test
    fun `公历天重复自1970年锚点直算远期目标`() {
        // 远期出现按周期秒数直算步数而非逐周期组合时区；当日零点已过 now 取次日
        val schedule = schedule(utcMillis(LocalDate.of(1970, 1, 2)), interval = 1, unit = 1)

        val target = schedule.nextTarget(zdt(2026, 8, 23, 12, 0, 0), shanghai)!!

        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, target.toLocalTime())
    }

    @Test
    fun `公历周重复自1970年锚点保持对齐`() {
        // 直算路径须保持与锚点的周对齐：与锚点日差为 7 的倍数、落在 now 后一周内
        val anchor = LocalDate.of(1970, 1, 5)
        val schedule = schedule(utcMillis(anchor), interval = 1, unit = 2)
        val now = zdt(2026, 8, 23, 12, 0, 0)

        val target = schedule.nextTarget(now, shanghai)!!

        assertEquals(0L, ChronoUnit.DAYS.between(anchor, target.toLocalDate()) % 7)
        assertFalse(target.isBefore(now))
        assertTrue(target.isBefore(now.plusWeeks(1)))
    }

    @Test
    fun `公历天重复跨夏令时缺口直算对齐`() {
        // 纽约每日 02:30 跨 3/8 缺口：缺口日顺延 03:30 仅影响当日；直算步数的
        // 估算余量须覆盖缺口顺延，仍取到 3/10 的原时刻 02:30
        val newYork = ZoneId.of("America/New_York")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 3, 1)), 2, 30, 0, interval = 1, unit = 1)

        val now = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, newYork)

        val target = schedule.nextTarget(now, newYork)!!

        assertEquals(LocalDate.of(2026, 3, 10), target.toLocalDate())
        assertEquals(LocalTime.of(2, 30), target.toLocalTime())
    }

    @Test
    fun `公历月重复大月末收缩到月末`() {
        // 1月31日每月重复，2月只有28天，收缩到2月末
        val schedule = schedule(utcMillis(LocalDate.of(2026, 1, 31)), interval = 1, unit = 3)

        val target = schedule.nextTarget(zdt(2026, 2, 1), shanghai)!!

        assertEquals(LocalDate.of(2026, 2, 28), target.toLocalDate())
    }

    @Test
    fun `公历月重复收缩后回弹到锚点日`() {
        // 1月31日重复：2月收缩到28，3月是大月回弹到31（以锚点日为基准，与农历一致）
        val schedule = schedule(utcMillis(LocalDate.of(2026, 1, 31)), interval = 1, unit = 3)

        val target = schedule.nextTarget(zdt(2026, 3, 1), shanghai)!!

        assertEquals(LocalDate.of(2026, 3, 31), target.toLocalDate())
    }

    @Test
    fun `公历闰日月重复平年落月末`() {
        // 2024-02-29 每年重复，平年收缩到2月末
        val schedule = schedule(utcMillis(LocalDate.of(2024, 2, 29)), interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 3, 1), shanghai)!!

        assertEquals(LocalDate.of(2027, 2, 28), target.toLocalDate())
    }

    @Test
    fun `公历闰日月重复闰年回弹到二十九`() {
        // 2024-02-29 每年重复：平年收缩到2/28，闰年回到2/29
        val schedule = schedule(utcMillis(LocalDate.of(2024, 2, 29)), interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2027, 3, 1), shanghai)!!

        assertEquals(LocalDate.of(2028, 2, 29), target.toLocalDate())
    }

    @Test
    fun `夏令时缺口时刻顺延`() {
        // 纽约 2026-03-08 02:30 不存在（2点跳3点），组合时刻顺延为 03:30
        val schedule = schedule(utcMillis(LocalDate.of(2026, 3, 8)), 2, 30, 0)
        val newYork = ZoneId.of("America/New_York")

        val now = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, newYork)

        val target = schedule.nextTarget(now, newYork)!!

        assertEquals(LocalTime.of(3, 30), target.toLocalTime())
        assertEquals("-04:00", target.offset.id)
    }

    @Test
    fun `夏令时缺口顺延不泄漏到后续重复`() {
        // 锚点 2026-03-08 02:30 落在纽约缺口当日顺延 03:30；每月重复的后续月
        // 由日期 + 时刻重新组合，回到原时刻 02:30，而非沿用顺延结果
        val newYork = ZoneId.of("America/New_York")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 3, 8)), 2, 30, 0, interval = 1, unit = 3)

        val now = ZonedDateTime.of(2026, 4, 1, 12, 0, 0, 0, newYork)

        val target = schedule.nextTarget(now, newYork)!!

        assertEquals(LocalDate.of(2026, 4, 8), target.toLocalDate())
        assertEquals(LocalTime.of(2, 30), target.toLocalTime())
    }

    @Test
    fun `夏令时重叠时刻取较早一次`() {
        // 纽约 2026-11-01 01:30 出现两次（回拨前 EDT 与回拨后 EST），取较早的 EDT
        val newYork = ZoneId.of("America/New_York")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 11, 1)), 1, 30, 0)

        val target = schedule.nextTarget(ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, newYork), newYork)!!

        assertEquals(LocalTime.of(1, 30), target.toLocalTime())
        assertEquals("-04:00", target.offset.id)
    }

    @Test
    fun `重复时刻恰好等于now不再推进`() {
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 23)), 12, 0, 0, interval = 1, unit = 1)

        val now = zdt(2026, 8, 23, 12, 0, 0)

        assertEquals(now, schedule.nextTarget(now, shanghai))
    }

    @Test
    fun `不重复已过目标按原样返回`() {
        val schedule = schedule(utcMillis(LocalDate.of(2026, 1, 1)), 8, 0, 0)

        val now = zdt(2026, 8, 23)

        val target = schedule.nextTarget(now, shanghai)!!

        assertTrue(target.isBefore(now))
    }

    @Test
    fun `目标日未选返回null`() {
        assertNull(schedule(-1L).nextTarget(zdt(2026, 8, 23), shanghai))
    }

    @Test
    fun `非法入参构造即拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { Schedule(targetHour = 24) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(targetMinute = 60) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(targetSecond = 60) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(targetHour = -5) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(targetDay = utcMillis(LocalDate.of(0, 1, 1))) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(targetDay = utcMillis(LocalDate.of(10000, 1, 1))) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(repeatInterval = -1) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(repeatInterval = Schedule.MAX_REPEAT_INTERVAL + 1) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(repeatUnit = 7) }
    }

    @Test
    fun `targetDay为零即1970年当天合法`() {
        // 纪元日 1970-01-01 的毫秒值恰为 0，曾是校验盲区；年重复自纪元日推进
        val schedule = schedule(targetDay = 0L, interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2027, 1, 1), target.toLocalDate())
    }

    @Test
    fun `负毫秒支持1970年前公历生日年重复`() {
        val schedule = schedule(utcMillis(LocalDate.of(1965, 6, 15)), interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2027, 6, 15), target.toLocalDate())
    }

    @Test
    fun `公元一年锚点在范围下界可用`() {
        val schedule = schedule(utcMillis(LocalDate.of(1, 1, 1)), interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2027, 1, 1), target.toLocalDate())
    }

    @Test
    fun `公历重复上界间隔不抛JDK异常`() {
        // repeatInterval 上界保证合法最大间隔组合出的日期仍在 LocalDate 年份范围内
        // （年、周是最易越界的单位），推算不裸抛 java.time 的 DateTimeException
        val yearly = schedule(utcMillis(LocalDate.of(2026, 1, 1)), interval = Schedule.MAX_REPEAT_INTERVAL, unit = 4)
        val weekly = schedule(utcMillis(LocalDate.of(2026, 1, 1)), interval = Schedule.MAX_REPEAT_INTERVAL, unit = 2)

        assertEquals(
            LocalDate.of(2026 + Schedule.MAX_REPEAT_INTERVAL, 1, 1),
            yearly.nextTarget(zdt(2026, 8, 23), shanghai)!!.toLocalDate(),
        )
        assertEquals(
            LocalDate.of(2026, 1, 1).plusDays(Schedule.MAX_REPEAT_INTERVAL * 7L),
            weekly.nextTarget(zdt(2026, 8, 23), shanghai)!!.toLocalDate(),
        )
    }
}
