package io.github.kamiiroawase.nexttime.bench

import com.tyme.lunar.LunarDay
import io.github.kamiiroawase.nexttime.RepeatUnit
import io.github.kamiiroawase.nexttime.Schedule
import io.github.kamiiroawase.nexttime.WeekDay
import io.github.kamiiroawase.nexttime.nextTarget
import io.github.kamiiroawase.nexttime.occurrences
import io.github.kamiiroawase.nexttime.previousTarget
import kotlinx.benchmark.Benchmark
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * 核心推算路径的回归基准：README 性能主张（最坏约 12 万步、实测 JVM 数十毫秒）
 * 的复核锚点。全部固定输入（上海时区），只有被测函数本身的热点变化会反映到
 * 数字上。open + @State 供 JMH 生成字节码子类（生成器要求基准类非 final、
 * 带字段的类须标 @State），故放 jvmMain 而非 commonMain。
 */
@org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class NextTimeBenchmark {
    private val zone: TimeZone = TimeZone.of("Asia/Shanghai")

    private fun utcMillis(date: LocalDate): Long = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun instantOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
    ): Instant = LocalDateTime(year, month, day, hour, 0, 0).toInstant(zone)

    // 最坏路径：公元 1 年锚点的月重复推到 9999 年（约 12 万个月步）
    private val monthWorst = Schedule(targetDay = utcMillis(LocalDate(1, 1, 31)), repeatInterval = 1, repeatUnit = RepeatUnit.MONTH)
    private val monthWorstNow = instantOf(9999, 6, 1)

    // 世纪跨度：月/周/日重复与选择器路径的常归远期查询
    private val monthCentury = Schedule(targetDay = utcMillis(LocalDate(1970, 1, 15)), repeatInterval = 1, repeatUnit = RepeatUnit.MONTH)
    private val weekSelectorFar =
        Schedule(
            targetDay = utcMillis(LocalDate(1970, 1, 5)),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.WEEK,
            weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY),
        )
    private val monthSelectorFar =
        Schedule(
            targetDay = utcMillis(LocalDate(1970, 1, 15)),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.MONTH,
            monthDays = setOf(1, 15),
        )
    private val dailyFar = Schedule(targetDay = utcMillis(LocalDate(1970, 1, 1)), repeatInterval = 1, repeatUnit = RepeatUnit.DAY)
    private val farNow = instantOf(2099, 6, 1)

    // 农历年重复的世纪跨度（年表缓存路径）；反向推算远期
    private val lunarAnchor = LunarDay.fromYmd(1900, 6, 10).getSolarDay()

    private val lunarYearFar =
        Schedule(
            lunar = true,
            targetDay = utcMillis(LocalDate(lunarAnchor.year, lunarAnchor.month, lunarAnchor.day)),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.YEAR,
        )

    private val dailyRecent = Schedule(targetDay = utcMillis(LocalDate(2026, 8, 1)), repeatInterval = 1, repeatUnit = RepeatUnit.DAY)
    private val recentNow = instantOf(2026, 8, 23)

    @Benchmark
    fun monthRepeatWorstCase(): Instant? = monthWorst.nextTarget(monthWorstNow, zone)

    @Benchmark
    fun monthRepeatCentury(): Instant? = monthCentury.nextTarget(farNow, zone)

    @Benchmark
    fun weekSelectorFarJump(): Instant? = weekSelectorFar.nextTarget(farNow, zone)

    @Benchmark
    fun monthSelectorFarWalk(): Instant? = monthSelectorFar.nextTarget(farNow, zone)

    @Benchmark
    fun dailyFarSpan(): Instant? = dailyFar.nextTarget(farNow, zone)

    @Benchmark
    fun lunarYearRepeatSpan(): Instant? = lunarYearFar.nextTarget(farNow, zone)

    @Benchmark
    fun monthRepeatCenturyPrevious(): Instant? = monthCentury.previousTarget(farNow, zone)

    @Benchmark
    fun occurrencesTake5Daily(): List<Instant> = dailyRecent.occurrences(recentNow, zone).take(5).toList()
}
