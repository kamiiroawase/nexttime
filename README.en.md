# nexttime

[![Build](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/nexttime.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/nexttime)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

A countdown target-date library: given a schedule's **target day, time of day and repeat rule, it derives the next target instant and reports the countdown state** — solar and lunar (leap-month-aware) repeats, day/week/month/year/hour/minute units, weekday and month-day selectors, an occurrence-count cap and occurrence-sequence enumeration, DST-safe in any time zone. The library computes only and emits no localized strings; rendering stays with the consumer (see [Localized rendering](#localized-rendering)).

A Kotlin Multiplatform library (single commonMain codebase); the lunar calendar comes from [tyme4kt](https://github.com/6tail/tyme4kt), date-time from [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) and `kotlin.time`.

[中文版](README.md)

## Usage

Published on [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/nexttime); versions follow `v*` git tags. Targets Android (minSdk 24), JVM 11+, iOS (arm64 device and simulator) and wasmJs.

```kotlin
repositories { mavenCentral() }

dependencies {
    // KMP consumers reference the root coordinate from commonMain (Gradle module
    // metadata resolves the platform variant); to pin a variant see the table below
    implementation("io.github.kamiiroawase:nexttime:4.0.0")
}
```

To pin a specific platform variant (Maven dependencies, locked coordinates, …):

| Consumer | Coordinate |
|---|---|
| Android | `io.github.kamiiroawase:nexttime-android:4.0.0` |
| JVM | `io.github.kamiiroawase:nexttime-jvm:4.0.0` |
| iOS device (arm64) | `io.github.kamiiroawase:nexttime-iosarm64:4.0.0` |
| iOS simulator (arm64) | `io.github.kamiiroawase:nexttime-iossimulatorarm64:4.0.0` |
| wasmJs | `io.github.kamiiroawase:nexttime-wasm-js:4.0.0` |

Version catalog entry:

```toml
[versions]
nexttime = "4.0.0"

[libraries]
nexttime = { module = "io.github.kamiiroawase:nexttime", version.ref = "nexttime" }
```

tyme4kt and kotlinx-datetime arrive as transitive dependencies (tyme4kt's `com.tyme.*` API is usable directly); the wasmJs IANA timezone database is embedded and passed along in the klib — zero configuration for consumers.

- **Forward and backward derivation**: `nextTarget()` advances to the next target instant (optionally capped by `until`); `previousTarget()` returns the latest occurrence at or before an instant; `anchor()` exposes the first occurrence; `occurrences()` lazily enumerates the occurrence sequence (next-N previews)
- **Solar and lunar repeats**: hour/minute/day/week/month/year; hours and minutes are true-duration intervals (every 8 hours = 8 real hours, continuing across days), day and above are calendar grid points; lunar month/year repeats advance along the lunar calendar with leap months optionally counted or skipped; month-end shrink anchors to the anchor day and rebounds later (Jan 31 → Feb 28 → Mar 31), identically for solar and lunar
- **Selectors and the occurrence-count cap**: week repeats take a weekday set (every Mon/Wed/Fri); month repeats take month-day positions (including the last day of the month) or nth weekdays (the second Tuesday, the last Friday); missing days either skip the month or shrink to the month end; `repeatCount` caps the total number of occurrences (anchor included; whichever of it and `until` comes first applies)
- **Timezone and DST safety**: target days are stored as UTC milliseconds, instants combine in the specified zone; gap instants shift forward (New York 02:30 → 03:30), overlaps take the earlier instant
- **Countdown**: `countdown()` reports a structured "past/upcoming + magnitude + unit" state (optional ceiling mode); `calendarCountdown()` splits into calendar years/months/days/hours/minutes/seconds (for "X years Y months Z days" displays)
- **Performance**: day/week and lunar repeats compute long spans by period directly or skip far-future calendar conversions; solar month/year repeats step along the calendar with bounded work (worst case ~120k steps, a few milliseconds measured on JVM — see the benchmark module); backward derivation reuses the same fast paths

### Quick start

```kotlin
import io.github.kamiiroawase.nexttime.Schedule
import io.github.kamiiroawase.nexttime.countdown
import io.github.kamiiroawase.nexttime.nextTarget
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// One-shot schedule: 2026-10-01 10:00:00 (Shanghai zone)
// The three time-of-day fields are all-or-none: a partial selection (e.g. only
// targetHour) throws at construction
val schedule = Schedule(
    targetDay = LocalDate(2026, 10, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
    targetHour = 10,
    targetMinute = 0,
    targetSecond = 0,
)

val zone = TimeZone.of("Asia/Shanghai")
val now = LocalDateTime(2026, 8, 23, 12, 0).toInstant(zone)

val next = schedule.nextTarget(now, zone)!!   // 2026-10-01T10:00+08:00
val state = countdown(next, now)              // Countdown(past = false, value = 38, unit = DAYS)
```

To display the date-time: `next.toLocalDateTime(zone)` yields a `LocalDateTime` to format per platform.

### Common scenarios

The snippets below are copy-paste runnable (`zone`, `now` and `utcMillis` are defined in the block):

```kotlin
import io.github.kamiiroawase.nexttime.RepeatUnit
import io.github.kamiiroawase.nexttime.Schedule
import io.github.kamiiroawase.nexttime.WeekDay
import io.github.kamiiroawase.nexttime.occurrences
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.time.Clock

val zone = TimeZone.of("Asia/Shanghai")
val now = Clock.System.now()

// The target day as UTC milliseconds (MaterialDatePicker's return value already is
// this; negatives work too)
fun utcMillis(date: LocalDate): Long =
    date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

// Solar yearly repeat: a wedding anniversary
val anniversary = Schedule(
    targetDay = utcMillis(LocalDate(2020, 6, 15)),
    targetHour = 9, targetMinute = 0, targetSecond = 0,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// Solar biweekly repeat: trash day
val trashDay = Schedule(
    targetDay = utcMillis(LocalDate(2026, 8, 3)),
    repeatInterval = 2,
    repeatUnit = RepeatUnit.WEEK
)

// Lunar yearly repeat: a birthday (lunar 8th month, 10th day; leap-month years do
// not add an extra occurrence)
val birthday = Schedule(
    lunar = true,
    targetDay = utcMillis(LocalDate(2026, 9, 20)),  // the anchor as a solar date
    targetHour = 8, targetMinute = 0, targetSecond = 0,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// Lunar monthly repeat: new-moon / full-moon style schedules, leap months skipped
val fullMoon = Schedule(
    lunar = true,
    targetDay = utcMillis(LocalDate(2026, 8, 13)),  // anchor: lunar 7th month, 1st day
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH
)

// Pure interval repeat: every 8 hours (true duration, continuous across days,
// unchanged across DST transitions)
val every8h = Schedule(
    targetDay = utcMillis(LocalDate(2026, 8, 26)),
    targetHour = 8, targetMinute = 0, targetSecond = 0,
    repeatInterval = 8,
    repeatUnit = RepeatUnit.HOUR
)

// Every Mon/Wed/Fri: the anchor date's own weekday must be in the set
// (2026-08-10 is a Monday); rule days earlier than the anchor within the
// anchor week are not occurrences — the anchor is always the first one
val monWedFri = Schedule(
    targetDay = utcMillis(LocalDate(2026, 8, 10)),
    targetHour = 9, targetMinute = 0, targetSecond = 0,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.WEEK,
    weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY)
)

// The last day of every month: -1 counts from the month end; the anchor must
// be a rule day of its own month (January's -1 is the 31st)
val monthEnd = Schedule(
    targetDay = utcMillis(LocalDate(2026, 1, 31)),
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH,
    monthDays = setOf(-1)
)

// The second Tuesday of every month: weekday × ordinal (1..5 = nth,
// -1..-5 = nth from the end)
val secondTuesday = Schedule(
    targetDay = utcMillis(LocalDate(2026, 9, 8)),  // 2026-09-08 is that month's second Tuesday
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH,
    weekdays = setOf(WeekDay.TUESDAY),
    weekdayOrdinals = setOf(2)
)

// A finite number of reminders: caps the total occurrences (anchor included);
// nextTarget returns null once exhausted — whichever of it and until comes first
val tenTimes = anniversary.copy(repeatCount = 10)

// Next-5 preview: a lazy sequence whose first element equals nextTarget(now, zone)
val upcoming = monWedFri.occurrences(now, zone).take(5).toList()

// A repeat with an end time: forward queries return null after it ends; "finished"
// displays anchor to the last occurrence before the end
val end = LocalDateTime(2026, 12, 31).toInstant(zone)
anniversary.nextTarget(now, zone, end)   // occurrence later than end → null (the anchor itself is exempt)
anniversary.previousTarget(end, zone)    // the last occurrence at or before end
anniversary.anchor(zone)                 // the first occurrence
```

## Optional modules

The core library takes zero new dependencies; these companion modules are opt-in, published at the same version with the same platform variants (`-android`/`-jvm`/`-iosarm64`/`-iossimulatorarm64`/`-wasm-js`, pinned like the [table above](#usage)).

### nexttime-serialization

A kotlinx-serialization serializer for `Schedule` plus an Android Parcelable wrapper (depends on `kotlinx-serialization-core`; Json stays with the consumer):

```kotlin
implementation("io.github.kamiiroawase:nexttime-serialization:4.0.0")
```

```kotlin
import io.github.kamiiroawase.nexttime.Schedule
import io.github.kamiiroawase.nexttime.serialization.ScheduleSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Persistence (a Room TypeConverter, DataStore, …)
val text = Json.encodeToString(ScheduleSerializer, schedule)
val restored = Json.decodeFromString(ScheduleSerializer, text)

// Embed in your own serializable models
@Serializable
data class CountdownItem(
    val title: String,
    @Serializable(with = ScheduleSerializer::class) val schedule: Schedule,
)
```

- **Wire field names are the contract**: the 13 fields map one-to-one onto the [Schedule fields](#schedule-fields) property names, stable across versions; `Set<Int>` fields encode as sorted JSON arrays (deterministic wire format), decoding accepts any order
- All fields are always written (independent of `encodeDefaults`); decoding goes through the `Schedule` constructor and **re-runs every validation** — corrupted data (out-of-range fields, illegal combinations) throws `SerializationException` instead of silently producing an invalid schedule
- Unknown-field tolerance is the consumer's Json configuration (`Json { ignoreUnknownKeys = true }` skips; the default errors)
- Android additionally gets `ScheduleParcel` (a `Parcelable` wrapper for process death and Fragment/Activity arguments): `ScheduleParcel(schedule)`, re-validated on read

### nexttime-rrule

Bidirectional conversion of a lossless RFC 5545 RRULE subset — interop with the iCalendar ecosystem (system calendars, Google Calendar):

```kotlin
implementation("io.github.kamiiroawase:nexttime-rrule:4.0.0")
```

```kotlin
import io.github.kamiiroawase.nexttime.rrule.parseRrule
import io.github.kamiiroawase.nexttime.rrule.toRrule
import io.github.kamiiroawase.nexttime.rrule.toRruleOrNull
import kotlinx.datetime.LocalDateTime

// Export: the rule string describes the repeat rule only; the anchor date-time
// (iCalendar's DTSTART) is kept by the caller
val rrule = schedule.toRrule()             // e.g. "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH"
val maybe = schedule.toRruleOrNull()       // null when not losslessly expressible

// Import: the DTSTART date-time + the RRULE string (an RRULE: prefix is accepted)
val imported = parseRrule("FREQ=MONTHLY;BYDAY=2TU", LocalDateTime(2026, 9, 8, 9, 0))
```

**Lossless subset** — conversion happens only when both sides agree semantically; otherwise an `RruleConversionException` is thrown:

| Direction | Accepted | Rejected |
|---|---|---|
| Export | day/hour/minute repeats; week repeats (weekday selectors included; without selectors BYDAY is the anchor weekday); month repeats with `monthDays` (clamp = false, or every entry within 1..28 ∪ -28..-1) or `weekdays`±`weekdayOrdinals`; year repeats (anchor not Feb 29); `repeatCount` → COUNT | lunar; month repeats without selectors or with reachable clamping; Feb 29-anchored year repeats (this library shrinks to Feb 28, RFC skips the year); non-repeating schedules; unset `targetDay` |
| Import | `FREQ=DAILY/WEEKLY/MONTHLY/YEARLY/HOURLY/MINUTELY` (WEEKLY's default BYDAY is the DTSTART weekday; MONTHLY without BY\* maps to `monthDays = {anchor day}`, clamp = false; ordinal BYDAY on MONTHLY must be a complete cartesian product — a shared single ordinal (`1MO,1TU`) or every weekday carrying every ordinal (`1MO,-1MO,1TU,-1TU`), since RFC entries expand independently and partial products have no lossless form); COUNT; `WKST=MO` | `UNTIL` (until is a per-query argument of nextTarget, not schedule state — keep end times on your side and pass them with queries); ordinal BYDAY on WEEKLY; ordinal BYDAY on MONTHLY that is not a complete product or mixes ordinal-less entries (`1MO,-1TU`, `MO,1TU`); `BYSECOND/BYMINUTE/BYHOUR/BYMONTH/BYWEEKNO/BYYEARDAY/BYSETPOS`; YEARLY with BY\*; YEARLY anchored on Feb 29; an anchor that is not a rule day; syntax errors, duplicate or unknown keys |

Mind the month-repeat semantic divide: this library's **selector-less** month repeat is anchor-day shrink/rebound (1/31 → 2/28 → 3/31) while RFC BYMONTHDAY **skips missing days** — `monthDays = {31}` (clamp = false) is the exact RFC counterpart; the import direction applies that mapping automatically, and export requires explicit selectors.

## API

`Instant` / `Clock` come from `kotlin.time`.

### Schedule fields

| Field | Type | Default | Notes |
|---|---|---|---|
| `lunar` | Boolean | false | The target day is interpreted on the lunar calendar; month/year repeats advance along it (day/week/hour/minute repeats behave as solar); month/year anchors must not fall on 1582-10-05..14 (UTC) — rejected at construction |
| `leapCount` | Boolean | false | Whether leap months participate in lunar repeats; see [Lunar repeat semantics](#lunar-repeat-semantics) |
| `targetDay` | Long | -1 | The target day as UTC milliseconds; -1 means unselected (derivation functions return null) |
| `targetHour` | Int | -1 | Target hour, -1 unselected; **the three time-of-day fields are all-or-none** — a partial selection throws `IllegalArgumentException` at construction |
| `targetMinute` | Int | -1 | Target minute, -1 unselected |
| `targetSecond` | Int | -1 | Target second, -1 unselected |
| `repeatInterval` | Int | 0 | Repeat interval (0..100000); 0 counts as non-repeating |
| `repeatUnit` | Int | RepeatUnit.NONE | Repeat unit, one of `RepeatUnit.NONE/DAY/WEEK/MONTH/YEAR/HOUR/MINUTE`; with `NONE` the schedule is non-repeating whatever the interval (ignored, same effect as `repeatInterval = 0`) |
| `weekdays` | Set\<Int\> | empty | Weekday selector, one of `WeekDay.MONDAY..SUNDAY` (ISO: 1..7); pairs with week or month repeats. Week repeats occur once per selected weekday in each interval week from the anchor week; month repeats combine with `weekdayOrdinals` for nth weekdays; **the anchor date's weekday must be in the set** — see [Selector semantics](#selector-semantics) |
| `weekdayOrdinals` | Set\<Int\> | empty | Ordinals within the month (month repeats + non-empty `weekdays` only): 1..5 = the nth, -1..-5 = the nth-from-last selected weekday; empty = every hit weekday of the month; an out-of-range ordinal (a 5th Tuesday in a month with four) skips that month |
| `monthDays` | Set\<Int\> | empty | Day-of-month selector (month repeats only, exclusive with `weekdays`): 1..31 from the month start, -31..-1 from the month end (-1 = the last day); **the anchor day must be in its month's resolved days** |
| `clampMonthDays` | Boolean | false | When a positive `monthDays` entry exceeds the month's length: false = skip that month (iCalendar BYMONTHDAY convention); true = shrink to the nearest end (positive overflows to the month end, negative to the month start) and deduplicate. No effect when empty |
| `repeatCount` | Int | 0 | Cap on the total number of occurrences (**anchor included**, RRULE COUNT convention); 0 = unlimited; once exhausted `nextTarget` returns null and `previousTarget` clamps to the last occurrence — whichever of it and `until` comes first applies; a positive count on a non-repeating schedule is rejected at construction |

About `targetDay`:

- Only the **date** part is taken, always resolved as UTC; the time of day is stored separately in `targetHour/Minute/Second`
- On Android you can store `MaterialDatePicker`'s return value directly (already UTC milliseconds, negatives included); elsewhere use `localDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()`
- Supports **0001-01-01 through 9999-12-31**: dates before 1970-01-01 are negative milliseconds; `-1` is the "unselected" sentinel
- Lunar schedules likewise store the anchor date's solar UTC milliseconds; the library converts to lunar month/day for derivation

Illegal values throw `IllegalArgumentException` at construction.

### Derivation: nextTarget / previousTarget / anchor / occurrences

```kotlin
fun Schedule.nextTarget(
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
    until: Instant? = null,
): Instant?

fun Schedule.previousTarget(
    before: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Instant?

fun Schedule.anchor(zone: TimeZone = TimeZone.currentSystemDefault()): Instant?

fun Schedule.occurrences(
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
    until: Instant? = null,
): Sequence<Instant>
```

| Case | Behavior |
|---|---|
| `targetDay == -1` (unselected) | The three derivation functions return null; `occurrences()` is an empty sequence |
| Non-repeating (`repeatInterval = 0` or `repeatUnit = NONE` — either suffices) | Returns the target day's combined instant, **past instants returned as-is**, no advancing; `occurrences()` is a single-element sequence |
| Repeating, forward | Advances from the anchor by periods to not-before `now`; exactly equal to `now` does not advance further |
| `until` cap | Returns null when the next occurrence is later than `until` (the sequence is monotone, everything after is over the cap too); **the anchor and non-repeating schedules are exempt** — an ended repeat cannot retroactively cancel its anchor; returns null outright when `until` is earlier than `now`, or when no in-range occurrence at or after `now` remains (derivation past the supported range means the repeat is finished; the "9999-12-31 last-second combination + 48-hour slack" is only the fast path's no-derivation bound, the actual last occurrence may be earlier) rather than throwing a range exception to force a verdict — no in-range occurrence can remain within `[now, until]` |
| `repeatCount` exhausted | Forward returns null (the same completion semantics as `until`; **whichever comes first applies**); backward never returns an occurrence past the cap, clamping to the last one; the count includes the anchor — the anchor itself is unaffected |
| Selector paths | Week repeat + `weekdays`: one occurrence per selected weekday in each interval week from the anchor week; month repeat + selectors: occurrence days are decided entirely by the selectors (**replacing anchor-day shrink/rebound**); rule days earlier than the anchor within the anchor week/month are not occurrences — see [Selector semantics](#selector-semantics) |
| `occurrences()` sequence | Lazily enumerates from the first occurrence at or after `now` (equality included), reusing every nextTarget semantic — the sequence terminates when `until`/`repeatCount` completes it; the first element always equals `nextTarget(now, zone, until)`; take the first N with `.take(n)`; per-element cost is about one `nextTarget` call |
| Repeating, backward | The latest occurrence at or before `before` (equality included); `before` earlier than the anchor returns null |
| Derivation crosses 0001..9999 | Throws `IllegalStateException`, never loops forever; exceptions — `previousTarget` returns the last in-range occurrence, and `nextTarget` with `until` returns null when in-range occurrences are exhausted (out-of-range queries without `until` still throw — the answer is not representable) |
| Time-of-day all unselected | Combines as 00:00:00 (partial selection is already rejected at construction; spell out all three fields for an exact hour) |
| Lunar + day/week repeat | Same as solar |
| Hour/minute repeat | **True-duration grid points**: occurrence = anchor + steps × interval, continuous across days; `lunar` is irrelevant; **not guarded by the 0001..9999 bound** (advances freely within the Instant range) |
| Month/year repeat meets a short month / non-leap year | Shrinks to the day closest to the anchor day, rebounding later (1/31 → 2/28 → 3/31; 2/29 → 2/28 in a non-leap year → 2/29 in a leap year); month repeats with selectors skip this path (the selectors decide, missing days skip or shrink per `clampMonthDays`) |

Two DST semantics: day-and-above units combine each occurrence independently as "date + time of day + zone" (clock-face grid points) — gap instants shift forward (affecting that day only), overlaps take the earlier instant, and across a DST change every day occurs at the same local time; `anchor()` likewise returns the shifted instant when the anchor day falls in a gap, always equal to the first occurrence. Hours/minutes advance by true duration (ISO 8601 time-based convention) — the interval is constant and the local clock face drifts one hour across a DST change. Selectors decide only the occurrence **date**; time-of-day combination and DST semantics are identical with and without selectors.

### Selector semantics

Selectors replace anchor-day advancement with an explicit rule for "which days occur" — solar only, week/month units only, an active repeat required (lunar, DAY/HOUR/MINUTE/YEAR and non-repeating schedules with selectors are all rejected at construction):

- **Week grid**: ISO weeks (Monday start); the anchor week is the week containing the anchor date, then one grid week per `repeatInterval` weeks; occurrences = selected weekdays of the grid week × the target time of day
- **Anchor invariant**: the occurrence sequence is the rule days **at or after the anchor** — rule days earlier than the anchor within the anchor week/month produce no occurrence; construction requires the anchor date itself to be a rule day (week: the anchor weekday ∈ `weekdays`; month: the anchor day ∈ its month's resolved days, clamp mode included), so `anchor()` is always the first occurrence
- **Month rules**: `weekdays` × `weekdayOrdinals` take the cartesian product, ascending by date within the month (`{Tuesday}×{1,-1}` = the first and last Tuesday of each month); `monthDays` is exclusive with `weekdays`, multiple days ascending (`{1, 15}` = the 1st and 15th of every month)
- **Missing days, two modes** (`clampMonthDays`): a positive day beyond the month's length either skips the month (false, the RRULE BYMONTHDAY convention — `{30}` produces nothing in February) or shrinks to the nearest end and deduplicates (true — `{30, 31}` becomes a single occurrence on Feb 28; `{31}` + true is equivalent to the anchor-day-31 shrink without selectors); negative overflows behave the same; **out-of-range ordinals always skip** (a 5th Tuesday in a month with four)
- **Selectors replace shrink**: in month repeats with selectors, `targetDay`'s day component does not participate — occurrence days are decided entirely by the selectors

| Goal | Field combination (interval = 1, unit as implied) |
|---|---|
| Every Mon/Wed/Fri | `unit = WEEK`, `weekdays = {MONDAY, WEDNESDAY, FRIDAY}` |
| The last day of every month | `unit = MONTH`, `monthDays = {-1}` |
| The 1st and 15th of every month | `unit = MONTH`, `monthDays = {1, 15}` |
| The second Tuesday of every month | `unit = MONTH`, `weekdays = {TUESDAY}`, `weekdayOrdinals = {2}` |
| The last Friday of every month | `unit = MONTH`, `weekdays = {FRIDAY}`, `weekdayOrdinals = {-1}` |
| Every Monday of the month | `unit = MONTH`, `weekdays = {MONDAY}` (ordinals left empty) |

### Countdown: countdown / calendarCountdown

```kotlin
fun countdown(
    target: Instant,
    now: Instant,
    rounding: Rounding = Rounding.TRUNCATE,
): Countdown

fun calendarCountdown(target: Instant, now: Instant, zone: TimeZone): CalendarCountdown

data class Countdown(val past: Boolean, val value: Long, val unit: CountdownUnit)
// CountdownUnit: DAYS / HOURS / MINUTES / SECONDS

data class CalendarCountdown(
    val past: Boolean,
    val years: Int, val months: Int, val days: Int,
    val hours: Int, val minutes: Int, val seconds: Int,
)
```

`countdown()` rules (past and upcoming symmetric):

- The duration is first **ceiled to whole seconds** (second-level ticks carry without flicker); the same instant reports 0 seconds
- A full day takes `DAYS`, less than a day `HOURS`, less than an hour `MINUTES`, less than a minute `SECONDS`
- Computed from the **true instant difference**: a day crossing a DST change actually spans 23 or 25 hours, and the magnitude follows the true duration
- `Rounding.CEIL_FUTURE`: ceils toward the upcoming direction (one second short of a full unit still carries: 86399 s + 1 ns = 1 day, 3599 s = 1 hour; 24 h → 1 day and 60 min → 1 h carry at the boundary); **the past direction always truncates** (at the day/hour/minute subdivision level; sub-second ceiling applies in both directions alike)

`calendarCountdown()`: splits into years/months/days/hours/minutes/seconds on `zone`'s **clock face**, with leap years and month lengths handled by the calendar; a day crossing a DST change counts as 1 day 0 hours (unlike `countdown()`'s true-duration semantics); month-end clamping matches java.time `Period` (1/31 → 2/28 is 0 months 28 days). Zero components are output as-is; omitting them is the renderer's choice.

### Lunar repeat semantics

- **Month repeat**: steps along the lunar month sequence; with `leapCount = true` a leap month is its own step, with `false` it is skipped
- **Year repeat**: keeps the lunar month/day; a leap-month-day schedule in a year without that leap month degrades to the plain month when `true`, or advances to the next lunar year containing it when `false` (`repeatInterval` steps along lunar-year grid points)
- **Month-end shrink**: a day beyond the target month's length takes the month end (lunar 6/30 → leap 6/29)
- **Historical calendars**: lunar↔solar conversion follows tyme's tables — its solar calendar is Gregorian from 1582-10 on, Julian before, and lacks 1582-10-05..14 (the Julian→Gregorian cutover gap; lunar month/year repeats anchored on those ten days are rejected at construction). Anchor and derivation round-trip consistently within the same tables, but compared with external tools computing on the proleptic Gregorian calendar (`LocalDate`'s calendar semantics), lunar conversions before 1582-10-15 differ by a few days. Before 1645 (when the Shixian calendar took effect) the tables additionally extrapolate by modern astronomical rules, which may diverge from the actual intercalation of the time; modern use cases like birthdays and anniversaries are unaffected

With lunar year 2025 (leap 6th month), anchored on 6/1 repeating every 1 month:

| leapCount | Next target after the 6th month |
|---|---|
| true | leap 6/1 (the leap month is its own step) |
| false | 7/1 (the leap month is skipped) |

### Localized rendering

The library emits no strings; where each display element's data comes from:

```kotlin
fun Countdown.zhText(): String {
    val prefix = if (past) "已过" else "还有"
    val unitText = when (unit) {
        CountdownUnit.DAYS -> "天"
        CountdownUnit.HOURS -> "小时"
        CountdownUnit.MINUTES -> "分"
        CountdownUnit.SECONDS -> "秒"
    }
    return "$prefix$value$unitText"
}
// Countdown(false, 20, HOURS) → "还有20小时" ("20 hours to go")
```

- **Calendar components**: "X years (Y months) (Z days)" style displays use `CalendarCountdown`'s six components, omitting zeros at render time
- **Solar dates**: `instant.toLocalDateTime(zone)` to a `LocalDateTime`, formatted per platform
- **Lunar dates**: the transitive tyme4kt provides the structure (`SolarDay.fromYmd(y, m, d).lunarDay`; Chinese names via `getName()`)

## Known limitations

- **`targetDay` takes the UTC date only**, supported range 0001-01-01..9999-12-31; `-1` is the "unselected" sentinel; 0 and negative milliseconds (1970-01-01 and earlier) are valid dates
- **Lunar month/year repeat anchors must not fall on 1582-10-05..14 (UTC)**: those ten days are the Julian→Gregorian cutover gap, absent from the lunar tables (tyme); construction throws `IllegalArgumentException`. Lunar day/week/hour/minute repeats and non-lunar schedules are unrestricted — 1582-10-04 and earlier, 1582-10-15 and later derive normally
- **Time-of-day is all-or-none**: a partial selection (e.g. only `targetHour = 8`) throws `IllegalArgumentException` at construction rather than silently defaulting to midnight; for 8:00 sharp spell out `targetMinute = 0, targetSecond = 0`; fully unselected combines as 00:00:00
- **Pass `nextTarget(now, zone)` as a pair**: when passing a `now` in a non-default zone, always pass the same `zone` — otherwise the date combines in the system zone while the instant compares against `now`, silently splitting the semantics
- **Non-repeating schedules do not advance**: a past target is returned as-is and `countdown()` faithfully reports "past"; configure a repeat rule for automatic advancement
- **`countdown()` reports a single magnitude**: no week unit and no mixed compound subdivision like "3 days 4 hours" (use `calendarCountdown()` for the clock-face year/month/day/hour/minute/second compound)
- **Selectors are solar-only, week/month units only**: `YEAR` does not support selectors (rejected at construction) — rules like "the second Sunday of May, every year" are not expressible yet; lunar and DAY/HOUR/MINUTE units with selectors are likewise rejected; month repeats with selectors **skip the anchor-day shrink/rebound** (the selectors decide, see [Selector semantics](#selector-semantics))
- **`occurrences()` costs about one `nextTarget` call per element**: with the far-future fast paths included, next-N previews are comfortably cheap; evaluate the cost before enumerating huge spans (tens of thousands of occurrences)
- **Two repeat semantics split by unit** (ISO 8601 convention): day and above are clock-face grid points (every day occurs at the same local time across DST), hours/minutes are true-duration grid points (constant interval, local clock face drifts one hour across DST, not guarded by the 0001..9999 bound, no range exception)
- **No iOS x64 simulator target** (matching tyme4kt's published surface — arm64 device and simulator only); Android minSdk 24+, JVM 11+, no desugaring
- **The iOS device target runs no tests**: the macOS jobs of the Build and Release workflows run the simulator target only (`iosSimulatorArm64Test`; iOS tests cannot run on Linux, and the Release publish step gates on its passing), while the device target (`iosArm64`) is cross-compile-verified only — both run the same commonTest sources
- **3.x → 4.0 migration**: `Schedule`'s constructor gains five fields — `weekdays` / `weekdayOrdinals` / `monthDays` / `clampMonthDays` / `repeatCount` (all defaulted) — named-argument call sites stay **source-compatible**, but the constructor signature change is **binary-incompatible** for compiled consumers: recompile against 4.0.0. No behavioral break (all new fields default off; with defaults, derivation results match 3.x exactly)
- **2.x → 3.0 migration**: coordinates moved from JitPack's `com.github.kamiiroawase.nexttime:nexttime-*` to Maven Central's `io.github.kamiiroawase:nexttime*`, and the package name changed from `com.github.kamiiroawase.nexttime` to `io.github.kamiiroawase.nexttime` (a bulk import replacement suffices); the old 2.x versions on JitPack are frozen and unmaintained

## Development

```bash
git clone https://github.com/kamiiroawase/nexttime.git
cd nexttime
./gradlew build        # JDK 21+: all targets + host-runnable tests + format checks
./gradlew jvmTest      # one platform
./gradlew :benchmark:benchmark   # regression benchmarks (JVM-only, optional, not CI-gated)
```

244 test cases (`kotlin.test`, commonTest — 227 core + 8 serialization + 9 rrule) cover solar/lunar derivation, leap months, month-end shrink, DST gaps/overlaps/skipped days, hour/minute true-duration grid points (including DST drift and two-millennia spans), range boundaries and completion semantics, forward/backward dual invariants, countdown rounding and calendar components, week/month selectors (weekday sets, month days, ordinals and the two missing-day modes, anchor membership), occurrence-count completion and occurrence sequences, serialization wire format and decode validation, and bidirectional RRULE conversion; seed-fixed **property tests** on top (deterministic fuzzing: random schedules × random query instants × a zone pool including ±30/45-minute DST extremes, with reproducible failure messages carrying the seed) pin down seven universal invariants; they run on JVM, Android unit tests and wasm (Node), with the iOS simulator executed by macOS CI. The `:benchmark` module (kotlinx-benchmark, JVM-only, unpublished) pins the worst-case magnitude — a year-1 monthly anchor projected to 9999 takes about 3 milliseconds. Quality gates hang off `build` for all three modules: Spotless formatting, `explicitApi()` explicit API, binary-compatibility-validator public-API snapshots (run `./gradlew apiDump` for intentional changes; child modules via `:serialization:apiDump` / `:rrule:apiDump`).

## License

[The Unlicense](LICENSE) — public domain.

This library depends on [tyme4kt](https://github.com/6tail/tyme4kt) (MIT License, Copyright (c) 6tail) and [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) (Apache 2.0, JetBrains). Both resolve as independent artifacts by the consumer; their licenses are not re-licensed through this library — when bundling them into a distribution (an APK/IPA, say), attach their copyright and license notices as each license requires.
