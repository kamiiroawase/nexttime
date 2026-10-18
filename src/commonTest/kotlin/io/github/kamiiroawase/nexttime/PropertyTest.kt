package io.github.kamiiroawase.nexttime

import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * 属性测试：固定种子的确定性模糊——对随机日程 × 随机查询时刻断言普适不变量。
 * 失败时消息携带种子、日程与查询时刻，可用同种子在 [FuzzSupport] 精确复现。
 * 有界域（锚点 1920..2080、查询 1910..2090）保证推算不触 0001..9999 范围界，
 * 最坏展开（repeatCount ≤ 40 × 年间隔 5）仍在界内——不变量可在全域断言。
 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class PropertyTest {
    private val seeds = intArrayOf(20260101, 20261018)

    private val cases = 200

    private val queriesPerCase = 4

    private fun forEachCase(block: (Schedule, Instant, TimeZone) -> Unit) {
        for (seed in seeds) {
            val rng = kotlin.random.Random(seed)
            repeat(cases) {
                val schedule = randomSchedule(rng)
                val zone = fuzzZones.random(rng)
                repeat(queriesPerCase) {
                    block(schedule, randomInstant(rng, zone), zone)
                }
            }
        }
    }

    private fun message(
        schedule: Schedule,
        moment: Instant,
        zone: TimeZone,
    ): String = "schedule: $schedule, moment: $moment, zone: $zone"

    @Test
    fun `nextTarget不早于now且倒计时未过`() {
        // 不重复日程对已过目标恒返回原值（既定语义），「不早于 now」仅对重复日程成立
        forEachCase { schedule, now, zone ->
            if (schedule.repeatInterval <= 0 || schedule.repeatUnit == RepeatUnit.NONE) return@forEachCase
            val next = schedule.nextTarget(now, zone) ?: return@forEachCase
            assertFalse(next < now, message(schedule, now, zone))
            assertFalse(countdown(next, now).past, message(schedule, now, zone))
        }
    }

    @Test
    fun `previousTarget不晚于before`() {
        forEachCase { schedule, before, zone ->
            val previous = schedule.previousTarget(before, zone) ?: return@forEachCase
            assertFalse(previous > before, message(schedule, before, zone))
        }
    }

    @Test
    fun `previousTarget与nextTarget在同一出现上对偶`() {
        forEachCase { schedule, now, zone ->
            val next = schedule.nextTarget(now, zone) ?: return@forEachCase
            assertEquals(next, schedule.previousTarget(next + 1.nanoseconds, zone), message(schedule, now, zone))
        }
    }

    @Test
    fun `重复日程越过目标后得到更晚目标或完结`() {
        // 不重复日程对已过目标恒返回原值（既定语义），不适用单调推进断言
        forEachCase { schedule, now, zone ->
            if (schedule.repeatInterval <= 0 || schedule.repeatUnit == RepeatUnit.NONE) return@forEachCase
            val next = schedule.nextTarget(now, zone) ?: return@forEachCase
            val second = schedule.nextTarget(next + 1.nanoseconds, zone)
            assertTrue(second == null || second > next, message(schedule, now, zone))
        }
    }

    @Test
    fun `锚点前一瞬的nextTarget即锚点`() {
        forEachCase { schedule, now, zone ->
            val anchor = schedule.anchor(zone) ?: return@forEachCase
            assertEquals(anchor, schedule.nextTarget(anchor - 1.nanoseconds, zone), message(schedule, now, zone))
        }
    }

    @Test
    fun `选择器日程的出现落在规则日上`() {
        forEachCase { schedule, now, zone ->
            if (schedule.weekdays.isEmpty() && schedule.monthDays.isEmpty()) return@forEachCase
            val anchorDate = dateOf(schedule.anchor(zone)!!, zone)
            for (occurrence in schedule.occurrences(now, zone).take(12)) {
                val date = dateOf(occurrence, zone)
                assertTrue(date >= anchorDate, "出现早于锚点日: ${message(schedule, now, zone)}")
                if (schedule.weekdays.isNotEmpty()) {
                    assertTrue(date.dayOfWeek.isoDayNumber in schedule.weekdays, "出现星期不在集合内: ${message(schedule, now, zone)}")
                }
                if (schedule.monthDays.isNotEmpty()) {
                    val resolved =
                        resolveMonthDays(
                            date.year,
                            date.month.ordinal + 1,
                            schedule.monthDays,
                            schedule.weekdays,
                            schedule.weekdayOrdinals,
                            schedule.clampMonthDays,
                        )
                    assertTrue(date.day in resolved, "出现日不在解析结果内: ${message(schedule, now, zone)}")
                }
            }
        }
    }

    @Test
    fun `次数上限日程自锚点起恰产出该次数`() {
        forEachCase { schedule, now, zone ->
            if (schedule.repeatCount <= 0) return@forEachCase
            val anchor = schedule.anchor(zone)!!
            val produced = schedule.occurrences(anchor - 1.nanoseconds, zone).take(schedule.repeatCount + 1).count()
            assertEquals(schedule.repeatCount, produced, message(schedule, now, zone))
        }
    }
}
