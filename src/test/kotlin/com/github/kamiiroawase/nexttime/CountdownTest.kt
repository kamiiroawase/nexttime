package com.github.kamiiroawase.nexttime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
