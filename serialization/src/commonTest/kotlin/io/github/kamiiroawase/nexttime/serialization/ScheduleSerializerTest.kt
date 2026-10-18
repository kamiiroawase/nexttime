package io.github.kamiiroawase.nexttime.serialization

import io.github.kamiiroawase.nexttime.RepeatUnit
import io.github.kamiiroawase.nexttime.Schedule
import io.github.kamiiroawase.nexttime.WeekDay
import io.github.kamiiroawase.nexttime.nextTarget
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ScheduleSerializer：往返、线格式稳定性、解码校验复跑与异常转译 */
@Suppress("NonAsciiCharacters", "RemoveRedundantBackticks")
class ScheduleSerializerTest {
    private val json = Json

    private fun utcMillis(date: LocalDate): Long = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    @Test
    fun `全字段日程往返等值`() {
        val schedule =
            Schedule(
                lunar = false,
                leapCount = false,
                targetDay = utcMillis(LocalDate(2026, 9, 8)),
                targetHour = 9,
                targetMinute = 30,
                targetSecond = 15,
                repeatInterval = 2,
                repeatUnit = RepeatUnit.MONTH,
                weekdays = setOf(WeekDay.TUESDAY),
                weekdayOrdinals = setOf(2, -1),
                monthDays = emptySet(),
                clampMonthDays = true,
                repeatCount = 10,
            )

        assertEquals(schedule, json.decodeFromString(ScheduleSerializer, json.encodeToString(ScheduleSerializer, schedule)))
    }

    @Test
    fun `线格式字段名与顺序锁定`() {
        // golden：字段名即契约（13 个、构造器序），数组编码为排序后的确定性形态
        val schedule = scheduleOf(weekdays = setOf(WeekDay.FRIDAY, WeekDay.MONDAY, WeekDay.WEDNESDAY))
        val encoded = json.encodeToString(ScheduleSerializer, schedule)

        assertEquals(
            "{\"lunar\":false,\"leapCount\":false,\"targetDay\":1769731200000," +
                "\"targetHour\":9,\"targetMinute\":30,\"targetSecond\":0," +
                "\"repeatInterval\":1,\"repeatUnit\":2," +
                "\"weekdays\":[1,3,5],\"weekdayOrdinals\":[],\"monthDays\":[]," +
                "\"clampMonthDays\":false,\"repeatCount\":0}",
            encoded,
        )
    }

    @Test
    fun `默认值日程往返等值`() {
        val schedule = Schedule(targetDay = utcMillis(LocalDate(2026, 1, 1)))

        assertEquals(schedule, json.decodeFromString(ScheduleSerializer, json.encodeToString(ScheduleSerializer, schedule)))
    }

    @Test
    fun `往返后推算语义不变`() {
        // 农历 + DST 样本：roundtrip 后 nextTarget 与原日程逐纳秒一致
        val zone = TimeZone.of("America/New_York")
        val schedule =
            Schedule(
                lunar = true,
                targetDay = utcMillis(LocalDate(2026, 9, 20)),
                targetHour = 2,
                targetMinute = 30,
                targetSecond = 0,
                repeatInterval = 1,
                repeatUnit = RepeatUnit.YEAR,
            )
        val now = LocalDateTime(2027, 3, 10, 12, 0).toInstant(zone)

        val restored = json.decodeFromString(ScheduleSerializer, json.encodeToString(ScheduleSerializer, schedule))
        assertEquals(schedule.nextTarget(now, zone)!!, restored.nextTarget(now, zone))
    }

    @Test
    fun `非法字段值解码时转译为SerializationException`() {
        // 构造期拒绝的取值（targetHour 越界）在读端复跑并按 kotlinx 惯例转译
        val encoded = json.encodeToString(ScheduleSerializer, Schedule(targetDay = 0)).replace("\"targetHour\":-1", "\"targetHour\":99")

        val exception = assertFailsWith<SerializationException> { json.decodeFromString(ScheduleSerializer, encoded) }
        assertTrue(exception.message!!.contains("valid Schedule"))
        assertTrue(exception.cause is IllegalArgumentException)
    }

    @Test
    fun `缺字段抛SerializationException`() {
        val encoded = json.encodeToString(ScheduleSerializer, Schedule(targetDay = 0)).replace("\"leapCount\":false,", "")

        val exception = assertFailsWith<SerializationException> { json.decodeFromString(ScheduleSerializer, encoded) }
        assertTrue(exception.message!!.contains("leapCount"))
    }

    @Test
    fun `未知字段按消费方Json配置处理`() {
        val encoded =
            json
                .encodeToString(ScheduleSerializer, Schedule(targetDay = 0))
                .replace("{", "{\"futureField\":42,")

        // 默认配置：未知字段报错
        assertFailsWith<SerializationException> { json.decodeFromString(ScheduleSerializer, encoded) }

        // ignoreUnknownKeys：跳过后正常解码
        val lenient = Json { ignoreUnknownKeys = true }
        assertEquals(Schedule(targetDay = 0), lenient.decodeFromString(ScheduleSerializer, encoded))
    }

    @Test
    fun `数组字段解码接受任意顺序`() {
        val base = scheduleOf(weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY))
        val encoded = json.encodeToString(ScheduleSerializer, base).replace("\"weekdays\":[1,3,5]", "\"weekdays\":[5,1,3]")

        assertEquals(base, json.decodeFromString(ScheduleSerializer, encoded))
    }

    private fun scheduleOf(weekdays: Set<Int>) =
        Schedule(
            targetDay = utcMillis(LocalDate(2026, 1, 30)),
            targetHour = 9,
            targetMinute = 30,
            targetSecond = 0,
            repeatInterval = 1,
            repeatUnit = RepeatUnit.WEEK,
            weekdays = weekdays,
        )
}
