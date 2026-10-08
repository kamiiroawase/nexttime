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
        // 返回 null；修复前会先撞范围守护抛 IllegalStateException。now 取
        // +12000 越过「9999-12-31 末秒组合 + 48 小时余量」的快路径上界，免推算
        // 直接完结；尚在余量窗口内的完结由下面两个用例覆盖
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
    fun `now在余量窗口内带until完结不抛越界`() {
        // 快路径上界（9999-12-31 末秒组合 + 48 小时余量）是保守近似：上海无
        // 夏令时，每日 00:00 出现的最后一次界内出现是 9999-12-31T00:00+08，
        // 比上界早约 72 小时。now 落在两者之间（+10000-01-01T16:00Z）：界内已
        // 无不早于 now 的出现，[now, until] 内无解——完结返回 null；修复前这里
        // 会先撞范围守护抛 IllegalStateException
        val schedule =
            schedule(utcMillis(LocalDate(2020, 1, 1)), interval = 1, unit = 1)

        assertNull(
            schedule.nextTarget(
                Instant.parse("+10000-01-01T16:00:00Z"),
                shanghai,
                Instant.parse("+10000-06-01T00:00:00Z"),
            ),
        )

        // 对照：不带 until 的同一查询仍是诚实的越界异常（答案不可表示）
        assertFailsWith<IllegalStateException> {
            schedule.nextTarget(Instant.parse("+10000-01-01T16:00:00Z"), shanghai)
        }
    }

    @Test
    fun `now仍在范围内但晚于最后一次出现同样完结`() {
        // now 尚在支持范围内、但已晚于界内最后一次出现（每日 00:00 的最后出现
        // 是 9999-12-31T00:00+08）：下一个出现已越出支持范围，[now, until] 内
        // 不可能有界内出现——完结返回 null，不为有界查询抛越界异常
        val schedule =
            schedule(utcMillis(LocalDate(2020, 1, 1)), interval = 1, unit = 1)

        assertNull(
            schedule.nextTarget(zdt(9999, 12, 31, 12), shanghai, Instant.parse("+10000-06-01T00:00:00Z")),
        )
    }

    @Test
    fun `月重复在余量窗口内完结`() {
        // 月/年路径同语义：now 在余量窗口内（最后一次月出现 9999-12-01T00:00+08
        // 之后、上界之前）时，步进越过范围界按完结分流返回 null。锚点取 9998 年
        // 缩短逐步推进的迭代数，语义与远期锚点一致
        val schedule =
            schedule(utcMillis(LocalDate(9998, 1, 1)), interval = 1, unit = 3)

        assertNull(
            schedule.nextTarget(
                Instant.parse("+10000-01-01T16:00:00Z"),
                shanghai,
                Instant.parse("+10000-06-01T00:00:00Z"),
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
    fun `农历年重复在余量窗口内完结`() {
        // 农历路径同语义：now 在余量窗口内时年表推进越过 9999，按完结分流返回
        // null，不撞农历年表越界守护（对照上一用例越过上界的快路径完结）
        val schedule =
            schedule(
                solarMillis(LunarDay.fromYmd(2020, 1, 1).getSolarDay()),
                lunar = true,
                interval = 1,
                unit = 4,
            )

        assertNull(
            schedule.nextTarget(
                Instant.parse("+10000-01-01T16:00:00Z"),
                shanghai,
                Instant.parse("+10000-06-01T00:00:00Z"),
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
