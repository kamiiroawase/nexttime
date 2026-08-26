package com.github.kamiiroawase.nexttime

import com.tyme.lunar.LunarDay
import com.tyme.solar.SolarDay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal val shanghai: TimeZone = TimeZone.of("Asia/Shanghai")

internal val newYork: TimeZone = TimeZone.of("America/New_York")

internal fun schedule(
    targetDay: Long,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
    lunar: Boolean = false,
    leapCount: Boolean = false,
    interval: Int = 0,
    unit: Int = 0,
): Schedule =
    Schedule(
        lunar = lunar,
        leapCount = leapCount,
        targetDay = targetDay,
        targetHour = hour,
        targetMinute = minute,
        targetSecond = second,
        repeatInterval = interval,
        repeatUnit = unit,
    )

internal fun utcMillis(date: LocalDate): Long = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

internal fun solarMillis(solarDay: SolarDay): Long = utcMillis(solarDate(solarDay))

internal fun solarDate(solarDay: SolarDay): LocalDate = LocalDate(solarDay.year, solarDay.month, solarDay.day)

internal fun lunarDayOf(
    instant: Instant,
    zone: TimeZone,
): LunarDay {
    val date = instant.toLocalDateTime(zone).date
    return SolarDay.fromYmd(date.year, date.month.ordinal + 1, date.day).getLunarDay()
}

internal fun dateOf(
    instant: Instant,
    zone: TimeZone,
): LocalDate = instant.toLocalDateTime(zone).date

internal fun timeOf(
    instant: Instant,
    zone: TimeZone,
): LocalTime = instant.toLocalDateTime(zone).time

/** 上海时区的指定时刻（旧测试中 zdt 助手的等价物，nano 为纳秒分量） */
internal fun zdt(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
    nano: Int = 0,
): Instant = LocalDateTime(year, month, day, hour, minute, second, nano).toInstant(shanghai)

/** 指定时区组合出的当地时刻 */
internal fun instantOf(
    zone: TimeZone,
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
): Instant = LocalDateTime(year, month, day, hour, minute, second).toInstant(zone)

/** 上海时区的某日某时刻（用于农历用例的「锚点日零点/加一小时」类构造） */
internal fun instantOf(
    date: LocalDate,
    hour: Int = 0,
    minute: Int = 0,
): Instant = LocalDateTime(date, LocalTime(hour, minute)).toInstant(shanghai)

internal operator fun LocalDate.plus(days: Int): LocalDate = this + DatePeriod(days = days)
