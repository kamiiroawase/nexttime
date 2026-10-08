package io.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/** anchor 与 nextTarget(until)：锚点暴露、封顶与锚点豁免语义 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class ProjectionTest {
    @Test
    fun `anchor 等于第一次出现`() {
        // 消费方原以「锚点前一瞬的 nextTarget」逆向推第一次出现，现由库直接暴露
        val schedule =
            schedule(utcMillis(LocalDate(2026, 10, 1)), hour = 10, interval = 3, unit = 1)

        val anchor = schedule.anchor(shanghai)!!

        assertEquals(zdt(2026, 10, 1, 10), anchor)
        assertEquals(anchor, schedule.nextTarget(anchor - 1.nanoseconds, shanghai))
    }

    @Test
    fun `anchor 落在夏令时缺口顺延为第一次出现`() {
        // 纽约 2026-03-08 02:30 不存在（2 点跳 3 点）：anchor 是顺延后的 03:30，
        // 与 nextTarget 语义一致，而非「按偏移硬算的不存在时刻」
        val schedule =
            schedule(utcMillis(LocalDate(2026, 3, 8)), hour = 2, minute = 30, interval = 1, unit = 1)

        val anchor = schedule.anchor(newYork)!!

        assertEquals(instantOf(newYork, 2026, 3, 8, 3, 30), anchor)
        assertEquals(anchor, schedule.nextTarget(anchor - 1.nanoseconds, newYork))
    }

    @Test
    fun `anchor 未选与时分秒未选`() {
        assertNull(schedule(targetDay = -1L).anchor(shanghai))

        // 时分秒未选（-1）整体按零点：schedule() 助手默认全 0，这里直接构造走未选分支
        assertEquals(
            zdt(2026, 10, 1),
            Schedule(targetDay = utcMillis(LocalDate(2026, 10, 1))).anchor(shanghai),
        )
    }

    @Test
    fun `until 封顶非锚点出现返回 null`() {
        // 天重复锚点 08-01、now 08-10：nextTarget = 08-11，晚于 until 08-05 → null
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = 1)

        assertNull(schedule.nextTarget(zdt(2026, 8, 10), shanghai, zdt(2026, 8, 5)))
    }

    @Test
    fun `until 恰等于出现保留`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = 1)

        // now 08-10 本身即出现（含等于）：恰被 until 08-10 封在界内，保留不丢弃
        assertEquals(
            zdt(2026, 8, 10),
            schedule.nextTarget(zdt(2026, 8, 10), shanghai, zdt(2026, 8, 10)),
        )
    }

    @Test
    fun `锚点超过 until 仍返回锚点`() {
        // takeIf { it <= end } 式封顶会误杀的反例：锚点 08-01 晚于 until 07-01，
        // 但锚点豁免——重复结束只限制后续推进，不能追溯取消锚点
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 1, unit = 1)

        assertEquals(
            zdt(2026, 8, 1),
            schedule.nextTarget(zdt(2026, 1, 1), shanghai, zdt(2026, 7, 1)),
        )
    }

    @Test
    fun `until 不约束非重复日程`() {
        // 非重复 + 早于目标的 until：照常返回目标时刻（消费方的「结束时间」
        // 语义通常对非重复日程不生效，库侧等价于忽略 until）
        val schedule =
            schedule(utcMillis(LocalDate(2026, 10, 1)), hour = 9)

        assertEquals(
            zdt(2026, 10, 1, 9),
            schedule.nextTarget(zdt(2026, 8, 26), shanghai, zdt(2026, 9, 1)),
        )
    }

    @Test
    fun `until 早于now时远期推算直接完结不抛越界`() {
        // now 越过范围界（9999-12-31 之后）而 until 在界内且早于 now：出现序列
        // 单调，正确答案是 null——修复前会先撞范围守护抛 IllegalStateException
        val schedule =
            schedule(utcMillis(LocalDate(2020, 1, 1)), interval = 1, unit = 1)

        assertNull(
            schedule.nextTarget(Instant.parse("+10000-01-01T00:00:00Z"), shanghai, zdt(2026, 1, 1)),
        )

        // 对照：不带 until 的同一查询仍是诚实的越界异常（答案不可表示）
        assertFailsWith<IllegalStateException> {
            schedule.nextTarget(Instant.parse("+10000-01-01T00:00:00Z"), shanghai)
        }
    }

    @Test
    fun `now越过界内最后出现而until更晚同样完结`() {
        // 与「until 早于 now」对偶的完结方向：now 与 until 都越过 9999 界时，
        // 任何不早于 now 的出现必然界外，[now, until] 内不可能再有界内出现——
        // 返回 null；修复前会先撞范围守护抛 IllegalStateException。now 须越过
        // 9999-12-31 末秒组合 + 48 小时余量（+10000-01-01 尚在余量内，不触发）
        val schedule =
            schedule(utcMillis(LocalDate(2020, 1, 1)), interval = 1, unit = 1)

        assertNull(
            schedule.nextTarget(
                Instant.parse("+12000-01-01T00:00:00Z"),
                shanghai,
                Instant.parse("+15000-01-01T00:00:00Z"),
            ),
        )
        assertNull(
            schedule.nextTarget(
                Instant.parse("+12000-01-01T00:00:00Z"),
                newYork,
                Instant.parse("+15000-01-01T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `农历重复now越过界内最后出现同样完结`() {
        // 农历路径同语义：now 越过界内最后可能出现（农历 9999 年末月的最晚公历
        // 落点 + 余量）时完结返回 null，不撞农历年表越界守护
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2020, 1, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        assertNull(
            schedule.nextTarget(
                Instant.parse("+12000-01-01T00:00:00Z"),
                shanghai,
                Instant.parse("+15000-01-01T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `农历重复封顶返回 null 且锚点豁免`() {
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2026, 8, 15).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        // 锚点 2026-08-15（公历 2026-09-25）尚未到：返回锚点，不受 until 06-01 约束
        assertEquals(
            zdt(2026, 9, 25),
            schedule.nextTarget(zdt(2026, 1, 1), shanghai, zdt(2026, 6, 1)),
        )

        // 锚点已过：下一出现 2027 农历八月十五，晚于 until 2027-06-01 → null
        assertNull(schedule.nextTarget(zdt(2027, 1, 1), shanghai, zdt(2027, 6, 1)))
    }

    @Test
    fun `until 为 null 保持现行为`() {
        val schedule =
            schedule(utcMillis(LocalDate(2026, 8, 1)), interval = 3, unit = 1)

        assertEquals(
            schedule.nextTarget(zdt(2026, 8, 26), shanghai),
            schedule.nextTarget(zdt(2026, 8, 26), shanghai, null),
        )
    }
}
