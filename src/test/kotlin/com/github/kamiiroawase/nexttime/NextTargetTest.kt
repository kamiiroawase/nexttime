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
    fun `配了间隔但单位未选视为不重复`() {
        // interval>0 + unit=NONE（或 interval=0 + unit=DAY）都按不重复处理，已过也不推进
        val noneUnit = schedule(utcMillis(LocalDate.of(2026, 1, 1)), 8, 0, 0, interval = 5, unit = 0)
        val zeroInterval = schedule(utcMillis(LocalDate.of(2026, 1, 1)), 8, 0, 0, interval = 0, unit = 1)

        val now = zdt(2026, 8, 23)
        assertEquals(LocalDate.of(2026, 1, 1), noneUnit.nextTarget(now, shanghai)!!.toLocalDate())
        assertEquals(LocalDate.of(2026, 1, 1), zeroInterval.nextTarget(now, shanghai)!!.toLocalDate())
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
    fun `now与zone分处不同时区按zone组合与now比较`() {
        // README 已知坑的既定行为：日期按 zone（上海）组合，时刻与 now（纽约）的 instant 比较
        val newYork = ZoneId.of("America/New_York")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 20)), 12, 0, 0, interval = 1, unit = 1)

        val now = ZonedDateTime.of(2026, 8, 23, 12, 0, 0, 0, newYork)

        val target = schedule.nextTarget(now, shanghai)!!

        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
        assertEquals("+08:00", target.offset.id)
        assertFalse(target.isBefore(now))
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
    fun `公历周重复跨秋令时回拨保持对齐`() {
        // 直算路径估算余量的另一半方向：秋令时回拨使相邻出现实隔 25 墙钟小时，
        // 1970 锚点推进 57 年跨约百次回拨，仍与锚点日按周对齐
        val newYork = ZoneId.of("America/New_York")
        val anchor = LocalDate.of(1970, 1, 5)
        val schedule = schedule(utcMillis(anchor), 1, 30, 0, interval = 1, unit = 2)
        val now = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, newYork)

        val target = schedule.nextTarget(now, newYork)!!

        assertEquals(0L, ChronoUnit.DAYS.between(anchor, target.toLocalDate()) % 7)
        assertFalse(target.isBefore(now))
        assertTrue(target.isBefore(now.plusWeeks(1)))
    }

    @Test
    fun `公历天重复跨跳日时区整天缺口顺延`() {
        // Apia 2011-12-30 整天不存在（12/29 末自 -10 跳 +14）：当日出现顺延到
        // 12/31 零点，24 小时跳变仍在直算步数的估算余量内
        val apia = ZoneId.of("Pacific/Apia")
        val schedule = schedule(utcMillis(LocalDate.of(2011, 12, 1)), interval = 1, unit = 1)

        val now = ZonedDateTime.of(2011, 12, 29, 12, 0, 0, 0, apia)

        val target = schedule.nextTarget(now, apia)!!

        assertEquals(LocalDate.of(2011, 12, 31), target.toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, target.toLocalTime())
        assertEquals("+14:00", target.offset.id)
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
    fun `公历月重复多间隔收缩以锚点日为基准`() {
        // 12/31 每 2 月：+2 月落 2 月收缩到 28、+4 月落 4 月收缩到 30，
        // 均自锚点日 31 直算而非逐步累加（逐步累加会丢失锚点日）
        val schedule = schedule(utcMillis(LocalDate.of(2025, 12, 31)), interval = 2, unit = 3)

        assertEquals(LocalDate.of(2026, 2, 28), schedule.nextTarget(zdt(2026, 1, 15), shanghai)!!.toLocalDate())
        assertEquals(LocalDate.of(2026, 4, 30), schedule.nextTarget(zdt(2026, 3, 1), shanghai)!!.toLocalDate())
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
    fun `公历闰日年重复多间隔回弹`() {
        // 2024-02-29 每 2 年：格点 2026 收缩到 2/28，格点 2028 闰年回弹到 2/29
        val schedule = schedule(utcMillis(LocalDate.of(2024, 2, 29)), interval = 2, unit = 4)

        assertEquals(LocalDate.of(2026, 2, 28), schedule.nextTarget(zdt(2026, 1, 15), shanghai)!!.toLocalDate())
        assertEquals(LocalDate.of(2028, 2, 29), schedule.nextTarget(zdt(2026, 6, 1), shanghai)!!.toLocalDate())
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
    fun `夏令时重叠日重复出现仍取较早一次`() {
        // 每月 1 日 01:30：11/1 回拨日出现两次，重复推算到该日时同样取较早的 EDT
        // （缺口方向的「顺延不泄漏」已测，重叠方向对称补齐）
        val newYork = ZoneId.of("America/New_York")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 6, 1)), 1, 30, 0, interval = 1, unit = 3)

        val target = schedule.nextTarget(ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, newYork), newYork)!!

        assertEquals(LocalDate.of(2026, 11, 1), target.toLocalDate())
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
    fun `重复日程锚点在未来直接返回锚点`() {
        // 首次出现尚未到达时不推进，原样返回锚点组合时刻
        val schedule = schedule(utcMillis(LocalDate.of(2026, 12, 24)), 10, 0, 0, interval = 7, unit = 2)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2026, 12, 24), target.toLocalDate())
        assertEquals(LocalTime.of(10, 0, 0), target.toLocalTime())
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
        assertThrows(IllegalArgumentException::class.java) { Schedule(repeatUnit = -1) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(repeatUnit = 5) }
    }

    @Test
    fun `targetDay为零即1970年当天合法`() {
        // 纪元日 1970-01-01 的毫秒值恰为 0，曾是校验盲区；年重复自纪元日推进
        val schedule = schedule(targetDay = 0L, interval = 1, unit = 4)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(2027, 1, 1), target.toLocalDate())
    }

    @Test
    fun `targetDay只取日期部分忽略毫秒中的时分秒`() {
        // 正午与日末 23:59:59.999 的 UTC 毫秒都只取日期，与零点毫秒同一目标日；
        // MaterialDatePicker 等来源的返回值未必是零点毫秒
        val noon = utcMillis(LocalDate.of(2026, 10, 1)) + 12L * 3600 * 1000
        val dayEnd = utcMillis(LocalDate.of(2026, 10, 1)) + 86_399_999L

        assertEquals(
            LocalDate.of(2026, 10, 1),
            schedule(noon, 10, 0, 0).nextTarget(zdt(2026, 8, 23), shanghai)!!.toLocalDate(),
        )
        assertEquals(
            LocalDate.of(2026, 10, 1),
            schedule(dayEnd, 10, 0, 0).nextTarget(zdt(2026, 8, 23), shanghai)!!.toLocalDate(),
        )
    }

    @Test
    fun `targetDay上界含9999年日末毫秒`() {
        // 上界为 10000-01-01 零点减一毫秒：9999-12-31 当天日末毫秒在界内（下界已测）
        val dayEnd = utcMillis(LocalDate.of(10000, 1, 1)) - 1

        val target = schedule(dayEnd).nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(9999, 12, 31), target.toLocalDate())
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

    @Test
    fun `公历月重复上界间隔结果可越过9999年`() {
        // 公历路径无 9999 守护（守护只在农历侧），上界保证的是不超出 java.time
        // 年份范围：100000 个月自 2026 组合出 10359-05-01，如常返回
        val schedule = schedule(utcMillis(LocalDate.of(2026, 1, 1)), interval = Schedule.MAX_REPEAT_INTERVAL, unit = 3)

        val target = schedule.nextTarget(zdt(2026, 8, 23), shanghai)!!

        assertEquals(LocalDate.of(10359, 5, 1), target.toLocalDate())
    }

    @Test
    fun `README快速上手示例`() {
        // 钉住文档端到端示例：2026-10-01 10:00（上海），自 8/23 12:00 起
        // 实隔 38 天 22 小时，量级取天、数值向下取整为 38
        val schedule = schedule(utcMillis(LocalDate.of(2026, 10, 1)), 10, 0, 0)

        val now = zdt(2026, 8, 23, 12, 0, 0)
        val target = schedule.nextTarget(now, shanghai)!!

        assertEquals(ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, shanghai), target)
        assertEquals(Countdown(false, 38, CountdownUnit.DAYS), countdown(target, now))
    }

    @Test
    fun `公历天重复跨秋令时回拨保持对齐`() {
        // 每日 01:30 跨 11/1 回拨：回拨日取较早的 EDT（锚点本身即重叠日 01:30），
        // 下一出现按原时刻重新组合为 EST；直算路径须越过重叠日落到 11/2
        val newYork = ZoneId.of("America/New_York")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 11, 1)), 1, 30, 0, interval = 1, unit = 1)

        val now = ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, newYork)

        val target = schedule.nextTarget(now, newYork)!!

        assertEquals(LocalDate.of(2026, 11, 2), target.toLocalDate())
        assertEquals(LocalTime.of(1, 30), target.toLocalTime())
        assertEquals("-05:00", target.offset.id)
    }

    @Test
    fun `公历周重复跨春令时缺口直算对齐`() {
        // 每周日 02:30：3/8 缺口日（周日）顺延 03:30 仅影响当日；直算步数须
        // 覆盖缺口顺延取到该日（周×缺口方向与天×回拨方向对称补齐）
        val newYork = ZoneId.of("America/New_York")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 3, 1)), 2, 30, 0, interval = 1, unit = 2)

        val now = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, newYork)

        val target = schedule.nextTarget(now, newYork)!!

        assertEquals(LocalDate.of(2026, 3, 8), target.toLocalDate())
        assertEquals(LocalTime.of(3, 30), target.toLocalTime())
        assertEquals("-04:00", target.offset.id)
    }

    @Test
    fun `公历周重复跨Apia跳日整天缺口顺延`() {
        // 每周五 00:00：2011-12-30（周五）整天不存在，该次出现顺延到 12/31
        // 零点 +14:00；周直算与天直算同受 48 小时余量覆盖
        val apia = ZoneId.of("Pacific/Apia")
        val schedule = schedule(utcMillis(LocalDate.of(2011, 12, 2)), interval = 1, unit = 2)

        val now = ZonedDateTime.of(2011, 12, 26, 12, 0, 0, 0, apia)

        val target = schedule.nextTarget(now, apia)!!

        assertEquals(LocalDate.of(2011, 12, 31), target.toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, target.toLocalTime())
        assertEquals("+14:00", target.offset.id)
    }

    @Test
    fun `公历天重复跨Kwajalein跳日整天缺口顺延`() {
        // Kwajalein 1993-08-21 整天不存在（8/20 末自 -12 跳 +12 到 8/22）：当日
        // 出现顺延到 8/22 零点，24 小时跳变仍在直算步数的估算余量内
        val kwajalein = ZoneId.of("Pacific/Kwajalein")
        val schedule = schedule(utcMillis(LocalDate.of(1993, 8, 15)), interval = 1, unit = 1)

        val now = ZonedDateTime.of(1993, 8, 20, 12, 0, 0, 0, kwajalein)

        val target = schedule.nextTarget(now, kwajalein)!!

        assertEquals(LocalDate.of(1993, 8, 22), target.toLocalDate())
        assertEquals(LocalTime.MIDNIGHT, target.toLocalTime())
        assertEquals("+12:00", target.offset.id)
    }

    @Test
    fun `南半球夏令时缺口时刻顺延`() {
        // 悉尼 2026-10-04 02:30 不存在（2 点跳 3 点，季节与北半球相反），
        // 缺口顺延同样适用
        val sydney = ZoneId.of("Australia/Sydney")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 10, 4)), 2, 30, 0)

        val now = ZonedDateTime.of(2026, 9, 20, 12, 0, 0, 0, sydney)

        val target = schedule.nextTarget(now, sydney)!!

        assertEquals(LocalTime.of(3, 30), target.toLocalTime())
        assertEquals("+11:00", target.offset.id)
    }

    @Test
    fun `南半球夏令时重叠时刻取较早一次`() {
        // 悉尼 2026-04-05 02:30 出现两次（回拨前 AEDT 与回拨后 AEST），取较早的 AEDT
        val sydney = ZoneId.of("Australia/Sydney")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 4, 5)), 2, 30, 0)

        val target = schedule.nextTarget(ZonedDateTime.of(2026, 3, 20, 12, 0, 0, 0, sydney), sydney)!!

        assertEquals(LocalTime.of(2, 30), target.toLocalTime())
        assertEquals("+11:00", target.offset.id)
    }

    @Test
    fun `半时区按日程时区组合`() {
        // 半小时偏移时区无夏令时，组合偏移 +05:30
        val kolkata = ZoneId.of("Asia/Kolkata")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 24)), 8, 30, 0)

        val target = schedule.nextTarget(zdt(2026, 8, 23, 12, 0, 0), kolkata)!!

        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
        assertEquals(LocalTime.of(8, 30), target.toLocalTime())
        assertEquals("+05:30", target.offset.id)
    }

    @Test
    fun `午夜跳变时区零时刻顺延`() {
        // 哈瓦那 2026-03-08 00:00 起跳（午夜即缺口）：未选时刻的默认零点顺延到 01:00
        val havana = ZoneId.of("America/Havana")
        val schedule = schedule(utcMillis(LocalDate.of(2026, 3, 8)))

        val now = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, havana)

        val target = schedule.nextTarget(now, havana)!!

        assertEquals(LocalTime.of(1, 0), target.toLocalTime())
        assertEquals("-04:00", target.offset.id)
    }

    @Test
    fun `时分秒上界组合合法`() {
        // 23:59:59 是合法上界组合，须与 -1 之外的非法取值区分
        val schedule = schedule(utcMillis(LocalDate.of(2026, 8, 24)), 23, 59, 59)

        val target = schedule.nextTarget(zdt(2026, 8, 23, 12, 0, 0), shanghai)!!

        assertEquals(LocalDate.of(2026, 8, 24), target.toLocalDate())
        assertEquals(LocalTime.of(23, 59, 59), target.toLocalTime())
    }
}
