package com.github.kamiiroawase.nexttime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** countdown：量级细分、已过/未到对称与秒级取整 */
class CountdownTest {
    @Test
    fun `满一天取天`() {
        // 25 小时 → 1 天
        assertEquals(
            Countdown(false, 1, CountdownUnit.DAYS),
            countdown(zdt(2026, 8, 24, 13, 0, 0), zdt(2026, 8, 23, 12, 0, 0)),
        )
        // 已过 25 小时 → 1 天
        assertEquals(
            Countdown(true, 1, CountdownUnit.DAYS),
            countdown(zdt(2026, 8, 22), zdt(2026, 8, 23, 1, 0, 0)),
        )
    }

    @Test
    fun `跨日历日不足一天取小时`() {
        // 目标明早 0 点，实际只剩 10 小时
        assertEquals(
            Countdown(false, 10, CountdownUnit.HOURS),
            countdown(zdt(2026, 8, 24), zdt(2026, 8, 23, 14, 0, 0)),
        )
    }

    @Test
    fun `同天按时分秒细分`() {
        val now = zdt(2026, 8, 23, 12, 0, 0)

        assertEquals(
            Countdown(false, 5, CountdownUnit.HOURS),
            countdown(zdt(2026, 8, 23, 17, 0, 0), now),
        )
        assertEquals(
            Countdown(false, 30, CountdownUnit.MINUTES),
            countdown(zdt(2026, 8, 23, 12, 30, 0), now),
        )
        assertEquals(
            Countdown(false, 20, CountdownUnit.SECONDS),
            countdown(zdt(2026, 8, 23, 12, 0, 20), now),
        )
    }

    @Test
    fun `同天已过对称细分`() {
        val now = zdt(2026, 8, 23, 12, 0, 0)

        assertEquals(
            Countdown(true, 3, CountdownUnit.HOURS),
            countdown(zdt(2026, 8, 23, 9, 0, 0), now),
        )
        assertEquals(
            Countdown(true, 30, CountdownUnit.MINUTES),
            countdown(zdt(2026, 8, 23, 11, 30, 0), now),
        )
        assertEquals(
            Countdown(true, 20, CountdownUnit.SECONDS),
            countdown(zdt(2026, 8, 23, 11, 59, 40), now),
        )
    }

    @Test
    fun `不足一秒向上取整不为零秒`() {
        val now = zdt(2026, 8, 23, 12, 0, 0)

        // 剩余 0.5s / 刚过 0.5s，都不出现 0 秒
        assertEquals(
            Countdown(false, 1, CountdownUnit.SECONDS),
            countdown(zdt(2026, 8, 23, 12, 0, 0, nano = 500_000_000), now),
        )
        assertEquals(
            Countdown(true, 1, CountdownUnit.SECONDS),
            countdown(zdt(2026, 8, 23, 11, 59, 59, nano = 500_000_000), now),
        )
    }

    @Test
    fun `秒边界向上取整进位为分`() {
        // tick 晚于整秒 0.3s：剩余 59.7s 取整成 60s，取分而非 59 秒
        val now = zdt(2026, 8, 23, 12, 0, 0, nano = 300_000_000)

        assertEquals(
            Countdown(false, 1, CountdownUnit.MINUTES),
            countdown(zdt(2026, 8, 23, 12, 1, 0), now),
        )
    }

    @Test
    fun `已过秒边界向上取整进位为分`() {
        // 已过 59.7s 取整成 60s（与未来侧对称）
        val now = zdt(2026, 8, 23, 11, 59, 59, nano = 700_000_000)

        assertEquals(
            Countdown(true, 1, CountdownUnit.MINUTES),
            countdown(zdt(2026, 8, 23, 11, 59, 0), now),
        )
    }

    @Test
    fun `目标与当前恰为同一瞬间为零秒`() {
        // KDoc 承诺：同一瞬间不进位、不报已过，输出未到 0 秒
        val now = zdt(2026, 8, 23, 12, 0, 0)

        assertEquals(Countdown(false, 0, CountdownUnit.SECONDS), countdown(now, now))
    }

    @Test
    fun `跨夏令时按真实时长细分`() {
        // Duration 按时刻差计算：墙钟同为一天，跨春令时的一天实隔 23 小时取小时
        // （缺口日 3/8 当天），跨秋令时回拨的一天实隔 25 小时满一天取天（回拨日 11/1 当天）
        val newYork = ZoneId.of("America/New_York")

        assertEquals(
            Countdown(false, 23, CountdownUnit.HOURS),
            countdown(
                ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, newYork),
                ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork),
            ),
        )
        assertEquals(
            Countdown(false, 1, CountdownUnit.DAYS),
            countdown(
                ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, newYork),
                ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, newYork),
            ),
        )
    }

    @Test
    fun `目标与当前分处不同时区按时刻差计算`() {
        // 纽约 8/23 12:00(-04) 到上海 8/24 12:00(+08) 实隔 12 小时
        assertEquals(
            Countdown(false, 12, CountdownUnit.HOURS),
            countdown(
                zdt(2026, 8, 24, 12, 0, 0),
                ZonedDateTime.of(2026, 8, 23, 12, 0, 0, 0, ZoneId.of("America/New_York")),
            ),
        )
    }
}
