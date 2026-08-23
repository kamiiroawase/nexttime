package com.github.kamiiroawase.nexttime

import com.nlf.calendar.Solar
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

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

internal fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun solarMillis(solar: Solar): Long = utcMillis(solarDate(solar))

internal fun solarDate(solar: Solar): LocalDate = LocalDate.of(solar.year, solar.month, solar.day)

internal fun zdt(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
    nano: Int = 0,
): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, second, nano, shanghai)
