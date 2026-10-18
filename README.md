# nexttime

[![Build](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/nexttime.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/nexttime)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

倒计时目标日推算库：给定日程的**目标日、时刻与重复规则，推算下一个目标时刻并给出倒计时状态**——公历与农历（闰月语义）重复、天/周/月/年/小时/分钟单位、星期与月内日选择器、出现次数上限与出现序列枚举、任意时区夏令时安全。库只做计算、不输出任何语言文案，渲染交给消费方（见[本地化渲染](#本地化渲染)）。

Kotlin Multiplatform 库（commonMain 单一代码），农历历法基于 [tyme4kt](https://github.com/6tail/tyme4kt)，日期时间基于 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) 与 `kotlin.time`。

[English version](README.en.md)

## 使用

发布于 [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/nexttime)，版本跟随 `v*` git tag。支持 Android（minSdk 24）、JVM 11+、iOS（arm64 真机与模拟器）与 wasmJs。

```kotlin
repositories { mavenCentral() }

dependencies {
    // KMP 消费方在 commonMain 引用根坐标（Gradle module metadata 自动解析平台变体）；
    // 需要钉住具体变体时见下表
    implementation("io.github.kamiiroawase:nexttime:4.0.0")
}
```

需要钉住具体平台变体时（Maven 依赖、锁坐标等场景）：

| 消费平台 | 坐标 |
|---|---|
| Android | `io.github.kamiiroawase:nexttime-android:4.0.0` |
| JVM | `io.github.kamiiroawase:nexttime-jvm:4.0.0` |
| iOS 真机（arm64） | `io.github.kamiiroawase:nexttime-iosarm64:4.0.0` |
| iOS 模拟器（arm64） | `io.github.kamiiroawase:nexttime-iossimulatorarm64:4.0.0` |
| wasmJs | `io.github.kamiiroawase:nexttime-wasm-js:4.0.0` |

版本目录写法：

```toml
[versions]
nexttime = "4.0.0"

[libraries]
nexttime = { module = "io.github.kamiiroawase:nexttime", version.ref = "nexttime" }
```

tyme4kt 与 kotlinx-datetime 以传递依赖自动引入（tyme4kt 的 `com.tyme.*` API 亦可直接使用）；wasmJs 平台的 IANA 时区库已内嵌并随 klib 传递，消费方零配置。

- **正反向推算**：`nextTarget()` 推进到下一个目标时刻（可带上限 `until`）；`previousTarget()` 取不晚于某时刻的最近一次出现；`anchor()` 暴露第一次出现；`occurrences()` 惰性枚举出现序列（取前 N 次预览）
- **公历与农历重复**：小时/分钟/天/周/月/年；小时/分钟为真实时长间隔（每 8 小时 = 8 个真实小时，跨日连续），天及以上为日历格点；农历月/年重复沿农历推进，闰月可选参与或跳过；月末收缩以锚点日为基准、后续回弹（1月31日 → 2月28日 → 3月31日），公历农历一致
- **选择器与次数上限**：周重复配星期集合（每周一三五）、月重复配月内日（含每月最后一天）或第 n 个星期几（每月第二个周二、最后一个周五）；缺失日可选跳过或收缩到月末；`repeatCount` 限定出现总次数（含锚点，与 `until` 先到者生效）
- **时区与夏令时安全**：目标日按 UTC 毫秒存储，组合时刻按指定时区；缺口时刻自动顺延（如纽约 02:30 → 03:30）、重叠取较早一次
- **倒计时**：`countdown()` 输出「已过/未到 + 量级 + 单位」的结构化状态（可选进一模式）；`calendarCountdown()` 按日历细分年/月/日/时/分/秒（适合「X年X月X天」展示）
- **性能**：天/周与农历重复的长跨度按周期直算或跳过远期历法换算；公历月/年重复沿日历逐步推进，量级有界（最坏约 12 万步、实测 JVM 数毫秒，见 benchmark 模块）；反向推算复用同一套快路径

### 快速上手

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

// 单次日程：2026-10-01 10:00:00（上海时区）
// 时分秒要么全选、要么全不选：部分选择（如只设 targetHour）构造时抛异常
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

展示日期时间：`next.toLocalDateTime(zone)` 得到 `LocalDateTime` 后按平台格式化。

### 常见场景

以下片段可直接复制试运行（`zone`、`now` 与 `utcMillis` 在块内定义）：

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

// 目标日的 UTC 毫秒（MaterialDatePicker 的返回值本身即是，负值同样可用）
fun utcMillis(date: LocalDate): Long =
    date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

// 公历每年重复：周年纪念日
val anniversary = Schedule(
    targetDay = utcMillis(LocalDate(2020, 6, 15)),
    targetHour = 9, targetMinute = 0, targetSecond = 0,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// 公历每两周重复：倒垃圾日
val trashDay = Schedule(
    targetDay = utcMillis(LocalDate(2026, 8, 3)),
    repeatInterval = 2,
    repeatUnit = RepeatUnit.WEEK
)

// 农历每年重复：生日（八月初十，闰月年不另过一次）
val birthday = Schedule(
    lunar = true,
    targetDay = utcMillis(LocalDate(2026, 9, 20)),  // 锚点公历日期
    targetHour = 8, targetMinute = 0, targetSecond = 0,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// 农历每月重复：初一十五类日程，闰月不参与
val fullMoon = Schedule(
    lunar = true,
    targetDay = utcMillis(LocalDate(2026, 8, 13)),  // 锚点：农历七月初一
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH
)

// 纯间隔重复：每 8 小时（真实时长，跨日连续、跨夏令时间隔不变）
val every8h = Schedule(
    targetDay = utcMillis(LocalDate(2026, 8, 26)),
    targetHour = 8, targetMinute = 0, targetSecond = 0,
    repeatInterval = 8,
    repeatUnit = RepeatUnit.HOUR
)

// 每周一三五：锚点日自身的星期须在集合内（2026-08-10 是周一）
// 锚点周内早于锚点日的规则日不算出现——锚点恒为第一次出现
val monWedFri = Schedule(
    targetDay = utcMillis(LocalDate(2026, 8, 10)),
    targetHour = 9, targetMinute = 0, targetSecond = 0,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.WEEK,
    weekdays = setOf(WeekDay.MONDAY, WeekDay.WEDNESDAY, WeekDay.FRIDAY)
)

// 每月最后一天：-1 从月末起算；锚点日须是当月的规则日（1 月的 -1 是 31 日）
val monthEnd = Schedule(
    targetDay = utcMillis(LocalDate(2026, 1, 31)),
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH,
    monthDays = setOf(-1)
)

// 每月第二个周二：星期 × 序数（1..5 第 n 个、-1..-5 倒数第 n 个）
val secondTuesday = Schedule(
    targetDay = utcMillis(LocalDate(2026, 9, 8)),  // 2026-09-08 正是第二个周二
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH,
    weekdays = setOf(WeekDay.TUESDAY),
    weekdayOrdinals = setOf(2)
)

// 只提醒有限次：出现总次数上限（含锚点），耗尽后 nextTarget 返回 null
// 与 until 先到者生效——完结展示改锚定末次出现
val tenTimes = anniversary.copy(repeatCount = 10)

// 未来 5 次预览：惰性序列，首个元素等于 nextTarget(now, zone)
val upcoming = monWedFri.occurrences(now, zone).take(5).toList()

// 带结束时间的重复：结束后正向返回 null，完结展示改锚定「结束前最后一次出现」
val end = LocalDateTime(2026, 12, 31).toInstant(zone)
anniversary.nextTarget(now, zone, end)   // 出现晚于 end → null（锚点本身不受限）
anniversary.previousTarget(end, zone)    // 不晚于 end 的最后一次出现
anniversary.anchor(zone)                 // 第一次出现
```

## 可选模块

核心库零新增依赖，以下配套模块按需引入，与核心同版本发布、同一批平台变体（`-android`/`-jvm`/`-iosarm64`/`-iossimulatorarm64`/`-wasm-js`，钉变体写法同[上表](#使用)）。

### nexttime-serialization

`Schedule` 的 kotlinx-serialization 序列化器与 Android Parcelable 包装（依赖 `kotlinx-serialization-core`，Json 由消费方自带）：

```kotlin
implementation("io.github.kamiiroawase:nexttime-serialization:4.0.0")
```

```kotlin
import io.github.kamiiroawase.nexttime.Schedule
import io.github.kamiiroawase.nexttime.serialization.ScheduleSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 持久化（Room TypeConverter、DataStore 等）
val text = Json.encodeToString(ScheduleSerializer, schedule)
val restored = Json.decodeFromString(ScheduleSerializer, text)

// 嵌入自己的可序列化模型
@Serializable
data class CountdownItem(
    val title: String,
    @Serializable(with = ScheduleSerializer::class) val schedule: Schedule,
)
```

- **线格式字段名即契约**：13 个字段与 [Schedule 字段](#schedule-字段)的属性名一一对应、跨版本稳定；`Set<Int>` 字段编码为排序后的 JSON 数组（确定性线格式），解码接受任意顺序
- 始终写全字段（与 `encodeDefaults` 无关）；解码走 `Schedule` 构造器**复跑全部校验**——损坏数据（字段越界、组合非法）抛 `SerializationException`，不静默产出非法日程
- 未知字段的容忍性由消费方 Json 配置决定（`Json { ignoreUnknownKeys = true }` 跳过、默认报错）
- Android 侧另有 `ScheduleParcel`（`Parcelable` 包装，进程重建与 Fragment/Activity 传参）：`ScheduleParcel(schedule)`，读回同样复跑校验

### nexttime-rrule

RFC 5545 RRULE 无损子集的双向转换——与系统日历、Google Calendar 等 iCalendar 生态互通：

```kotlin
implementation("io.github.kamiiroawase:nexttime-rrule:4.0.0")
```

```kotlin
import io.github.kamiiroawase.nexttime.rrule.parseRrule
import io.github.kamiiroawase.nexttime.rrule.toRrule
import io.github.kamiiroawase.nexttime.rrule.toRruleOrNull
import kotlinx.datetime.LocalDateTime

// 导出：规则串只描述重复规则，锚点日期时刻（iCalendar 的 DTSTART）由调用方另行保存
val rrule = schedule.toRrule()             // 如 "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH"
val maybe = schedule.toRruleOrNull()       // 不可无损表达时 null

// 导入：DTSTART 日期时刻 + RRULE 规则串（允许携带 RRULE: 前缀）
val imported = parseRrule("FREQ=MONTHLY;BYDAY=2TU", LocalDateTime(2026, 9, 8, 9, 0))
```

**无损子集**——两侧语义完全一致才转换，否则抛 `RruleConversionException`：

| 方向 | 接受 | 拒绝 |
|---|---|---|
| 导出 | 天/小时/分钟重复；周重复（含星期选择器，无选择器按锚点星期导出 BYDAY）；月重复配 `monthDays`（clamp=false，或条目全部在 1..28 ∪ -28..-1）或 `weekdays`±`weekdayOrdinals`；年重复（锚点非 2/29）；`repeatCount` → COUNT | 农历；月重复无选择器或 clamp 可达；锚点 2/29 的年重复（本库平年收 2/28、RFC 跳过该年）；不重复日程；`targetDay` 未选 |
| 导入 | `FREQ=DAILY/WEEKLY/MONTHLY/YEARLY/HOURLY/MINUTELY`（WEEKLY 缺省 BYDAY 即 DTSTART 星期；MONTHLY 无 BY\* 映射为 `monthDays = {锚点日}`、clamp=false）；COUNT；`WKST=MO` | `UNTIL`（until 是 nextTarget 的查询期参数、非日程状态，结束时间请自行保存并与查询同传）；WEEKLY 带序数 BYDAY；`BYSECOND/BYMINUTE/BYHOUR/BYMONTH/BYWEEKNO/BYYEARDAY/BYSETPOS`；YEARLY 带 BY\*；锚点 2/29 的 YEARLY；锚点非规则日；语法错、重复键、未知键 |

月重复的语义分界务必注意：本库**无选择器**月重复是锚点日收缩回弹（1/31 → 2/28 → 3/31），RFC BYMONTHDAY 是**缺日跳过**——`monthDays = {31}`（clamp=false）才是与 RFC 精确对应的形式，导入方向自动作此映射，导出方向要求显式选择器。

## API

`Instant` / `Clock` 来自 `kotlin.time`。

### Schedule 字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `lunar` | Boolean | false | 目标日按农历解释，月/年重复沿农历推进（天/周/小时/分钟重复与公历无异）；月/年重复锚点不得落在 1582-10-05..14（UTC），构造期拒绝 |
| `leapCount` | Boolean | false | 农历时闰月是否参与重复推算，详见[农历重复语义](#农历重复语义) |
| `targetDay` | Long | -1 | 目标日 UTC 毫秒值，-1 表示未选（推算函数返回 null） |
| `targetHour` | Int | -1 | 目标时，-1 表示未选；**三个时刻字段要么全选、要么全不选**，部分选择构造时抛 `IllegalArgumentException` |
| `targetMinute` | Int | -1 | 目标分，-1 表示未选 |
| `targetSecond` | Int | -1 | 目标秒，-1 表示未选 |
| `repeatInterval` | Int | 0 | 重复间隔（0..100000），0 视为不重复 |
| `repeatUnit` | Int | RepeatUnit.NONE | 重复单位，取 `RepeatUnit.NONE/DAY/WEEK/MONTH/YEAR/HOUR/MINUTE`；`NONE` 时无论间隔为何均按不重复处理（间隔被忽略，与 `repeatInterval = 0` 同效） |
| `weekdays` | Set\<Int\> | 空集 | 星期选择器，取 `WeekDay.MONDAY..SUNDAY`（ISO：1..7）；配周或月重复。周重复在锚点周起每 interval 周的选中星期各出现一次；月重复配 `weekdayOrdinals` 定位每月第 n 个；**锚点日的星期须在集合内**，详见[选择器语义](#选择器语义) |
| `weekdayOrdinals` | Set\<Int\> | 空集 | 月内序数（仅月重复 + `weekdays` 非空）：1..5 = 每月第 n 个、-1..-5 = 倒数第 n 个选中星期；空集 = 每月全部命中日；序数超界（第 5 个周二遇只有 4 个的月）该月跳过 |
| `monthDays` | Set\<Int\> | 空集 | 月内日选择器（仅月重复、与 `weekdays` 互斥）：1..31 从月初、-31..-1 从月末（-1 即每月最后一天）；**锚点日的日号须在当月解析结果内** |
| `clampMonthDays` | Boolean | false | `monthDays` 的正数日超出当月天数时：false = 该月跳过（iCalendar BYMONTHDAY 惯例）；true = 收缩到最近的端（正数收到月末、负数收到月初）并去重。空集时无效果 |
| `repeatCount` | Int | 0 | 出现总次数上限（**含锚点**，RRULE COUNT 惯例），0 不限；耗尽后 `nextTarget` 返回 null、`previousTarget` 钳在末次出现，与 `until` 先到者生效；不重复日程配正次数构造期拒绝 |

关于 `targetDay`：

- 只取**日期**部分，固定按 UTC 解析；时分秒由 `targetHour/Minute/Second` 单独存储
- Android 上可直接存 `MaterialDatePicker` 的返回值（本身就是 UTC 毫秒，负值同样可用）；其他平台用 `localDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()`
- 支持 **0001-01-01 至 9999-12-31**：1970-01-01 之前的日期以负毫秒表示，`-1` 是「未选」哨兵
- 农历日程同样存锚点日期的公历 UTC 毫秒，库自动换算为农历月日参与推算

非法取值在构造时抛 `IllegalArgumentException`。

### 推算：nextTarget / previousTarget / anchor / occurrences

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

| 场景 | 行为 |
|---|---|
| `targetDay == -1`（未选） | 三个推算函数返回 null，`occurrences()` 为空序列 |
| 不重复（`repeatInterval = 0` 或 `repeatUnit = NONE`，任一即视为不重复） | 返回目标日组合时刻，**已过也原样返回过去时刻**，不推进；`occurrences()` 为单元素序列 |
| 重复正向 | 从锚点按周期推进到不早于 `now`；恰好等于 `now` 时不再推进 |
| `until` 上限 | 重复出现晚于 `until` 时返回 null（序列单调，后续必然超限）；**锚点与非重复日程不受约束**——重复结束不能追溯取消锚点；`until` 早于 `now`，或界内已无不早于 `now` 的出现（推算越过支持范围即完结；9999-12-31 末秒组合加 48 小时余量只是免推算的快路径上界，实际最后一次出现可能更早）时直接返回 null，不为凑结论抛越界异常——`[now, until]` 内不可能再有界内出现 |
| `repeatCount` 耗尽 | 正向返回 null（完结语义与 `until` 同形，两者**先到者生效**）；反向不返回序号越限的出现，钳在末次出现；count 含锚点——锚点自身不受影响 |
| 选择器路径 | 周重复 + `weekdays`：锚点周起每 interval 周的选中星期各一次出现；月重复 + 选择器：出现日完全由选择器决定（**取代锚点日收缩回弹**）；锚点周/月内早于锚点日的规则日不算出现，详见[选择器语义](#选择器语义) |
| `occurrences()` 序列 | 惰性枚举自首个不早于 `now` 的出现（含恰等于），重复沿用 nextTarget 全部语义——`until`/`repeatCount` 完结即序列终止；首个元素恒等于 `nextTarget(now, zone, until)`；取前 N 个用 `.take(n)`，每元素成本约等于一次 `nextTarget` 调用 |
| 重复反向 | 不晚于 `before` 的最近一次出现（含恰等于）；`before` 早于锚点返回 null |
| 推算越过 0001..9999 | 抛 `IllegalStateException`，不会死循环；例外——`previousTarget` 返回界内最后一次出现，`nextTarget` 带 `until` 时界内出现耗尽即完结返回 null（不带 `until` 的越界查询仍抛异常，答案不可表示） |
| 时分秒全未选 | 按 00:00:00 组合（部分选择已在构造期拒绝，要整点须显式写全三个字段） |
| 农历 + 天/周重复 | 与公历相同 |
| 小时/分钟重复 | **真实时长格点**：出现 = 锚点 + 步数×间隔，跨日连续；`lunar` 无关；**不受 0001..9999 上界守护**（Instant 值域内任意推进） |
| 月/年重复遇短月/平年 | 收缩到当月最接近锚点日的一天，后续回弹（1/31 → 2/28 → 3/31；2/29 → 平年 2/28 → 闰年 2/29）；配选择器的月重复不走此路径（由选择器决定，缺失日跳过或按 `clampMonthDays` 收缩） |

夏令时的两套语义：天及以上单位每次出现独立按「日期 + 时刻 + 时区」组合（钟面格点）——缺口时刻顺延（仅影响当日）、重叠取较早一次，跨夏令时每天出现在同一本地时刻；`anchor()` 在锚点日落缺口时同样返回顺延后的时刻，恒等于第一次出现。小时/分钟按真实时长推进（ISO 8601 time-based 惯例）——间隔不变、跨夏令时本地钟面漂移 1 小时。选择器只决定出现**日期**，时刻组合与夏令时语义与无选择器完全一致。

### 选择器语义

选择器把「哪天出现」从锚点日推进改为显式规则，仅公历、仅周/月单位、须激活重复（lunar、DAY/HOUR/MINUTE/YEAR、不重复日程配选择器均构造期拒绝）：

- **周格点**：ISO 周（周一起始）；锚点周 = 锚点日所在周，此后每 `repeatInterval` 周一个格点周，出现 = 格点周的选中星期 × 目标时刻
- **锚点不变量**：出现序列 = 规则日中 **≥ 锚点日** 者——锚点周/锚点月内早于锚点日的规则日不产生出现；构造期要求锚点日自身是规则日（周：锚点星期 ∈ `weekdays`；月：锚点日 ∈ 当月解析结果，含 clamp 模式），保证 `anchor()` 恒为第一次出现
- **月内规则**：`weekdays` + `weekdayOrdinals` 取笛卡尔积、月内按日期升序（`{周二}×{1,-1}` = 每月第一个和最后一个周二）；`monthDays` 与 `weekdays` 互斥，多日按升序（`{1, 15}` = 每月 1 号和 15 号）
- **缺失日两模式**（`clampMonthDays`）：正数日超出当月天数时 false 跳过该月（`{30}` 遇 2 月无出现，RRULE BYMONTHDAY 惯例）、true 收缩到最近的端并去重（`{30, 31}` 在 2 月收为 28 日单次出现；`{31}` + true 与无选择器的锚点 31 日收缩语义等价）；负数日越界同理；**序数超界恒跳过**（第 5 个周二遇只有 4 个周二的月）
- **选择器取代收缩**：配选择器的月重复中 `targetDay` 的日分量不参与推算——出现日完全由选择器决定

| 目标 | 字段组合（interval = 1，unit 对应） |
|---|---|
| 每周一三五 | `unit = WEEK`，`weekdays = {MONDAY, WEDNESDAY, FRIDAY}` |
| 每月最后一天 | `unit = MONTH`，`monthDays = {-1}` |
| 每月 1 号和 15 号 | `unit = MONTH`，`monthDays = {1, 15}` |
| 每月第二个周二 | `unit = MONTH`，`weekdays = {TUESDAY}`，`weekdayOrdinals = {2}` |
| 每月最后一个周五 | `unit = MONTH`，`weekdays = {FRIDAY}`，`weekdayOrdinals = {-1}` |
| 每月所有周一 | `unit = MONTH`，`weekdays = {MONDAY}`（ordinals 留空） |

### 倒计时：countdown / calendarCountdown

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

`countdown()` 规则（已过与未到对称）：

- 时长先**向上取整到完整秒**（秒级 tick 进位不闪跳）；同一瞬间输出 0 秒
- 满一天取 `DAYS`，不足一天取 `HOURS`，不足一小时取 `MINUTES`，不足一分取 `SECONDS`
- 按**真实时刻差**计算：跨夏令时变化的一天实隔 23 或 25 小时，量级随真实时长
- `Rounding.CEIL_FUTURE`：未到方向向上取整（差一秒满整单位也进位：86399 秒 + 1 纳秒 = 1 天、3599 秒 = 1 小时；24 时 → 1 天、60 分 → 1 时边界进位）；**已过方向恒截断**（指天/时/分单位细分层面，亚秒进整两方向一致）

`calendarCountdown()`：按 `zone` 的**钟面**细分到年/月/日/时/分/秒，闰年与月长由日历自动处理；跨夏令时变化的一天计 1 天 0 小时（与 `countdown()` 的真实时长语义不同）；月末钳制与 java.time `Period` 一致（1/31 → 2/28 为 0 个月 28 天）。零分量原样输出，省略由渲染决定。

### 农历重复语义

- **月重复**：沿农历月序列步进；`leapCount = true` 时闰月算独立一步，`false` 时跳过闰月
- **年重复**：保持农历月日；闰月日日程在无该闰月的年份，`true` 当年退化为普通月，`false` 推进到下一个有该闰月的农历年（`repeatInterval` 沿农历年格点推进）
- **月末收缩**：日超出目标月天数时取月末（六月三十 → 闰六月廿九）
- **历史历法**：农历↔公历换算沿用 tyme 历表——其公历自 1582-10 起为格里高利历、之前按儒略历解释，且不含 1582-10-05..14（儒略→格里高利换算缺口日；农历月/年重复锚定这十天时构造期拒绝）。锚点与推算结果在同一历表内往返自洽，但与按外推格里高利历（`LocalDate` 的历法语义）计算的外部工具对比，1582-10-15 之前的农历换算存在数日偏差。1645 年（时宪历颁行）之前另按现代天文规则回推，与当时实际置闰可能有出入；生日、纪念日等近代场景不受影响

以 2025 农历年（闰六月）为例，锚点六月初一、每 1 月重复：

| leapCount | 六月过后的下一个目标 |
|---|---|
| true | 闰六月初一（闰月算独立一步） |
| false | 七月初一（跳过闰月） |

### 本地化渲染

库不产生文案，各显示元素的数据来源：

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
// Countdown(false, 20, HOURS) → "还有20小时"
```

- **日历分量**：「X年(X月)(XX天)」类展示用 `CalendarCountdown` 六分量，零分量渲染时省略
- **公历日期**：`instant.toLocalDateTime(zone)` 转为 `LocalDateTime` 后按平台格式化
- **农历日期**：传递依赖 tyme4kt 取结构（`SolarDay.fromYmd(y, m, d).lunarDay`，中文名从 `getName()` 取得）

## 已知限制

- **`targetDay` 只取 UTC 日期**，支持范围 0001-01-01..9999-12-31；`-1` 是「未选」哨兵，0 与负毫秒（1970-01-01 及更早）是合法日期
- **农历月/年重复的锚点不得落在 1582-10-05..14（UTC）**：这十天是儒略→格里高利历换算缺口，农历历表（tyme）不含，构造时抛 `IllegalArgumentException`；农历天/周/小时/分钟重复与非农历日程不受限，1582-10-04 及更早、1582-10-15 及更晚均可正常推算
- **时分秒「要么全选、要么全不选」**：部分选择（如只设 `targetHour = 8`）在构造时抛 `IllegalArgumentException`，不会静默按零点；要 8 点整须显式写 `targetMinute = 0, targetSecond = 0`，全不选则整体按 00:00:00 组合
- **`nextTarget(now, zone)` 成对传**：传入非默认时区的 `now` 时务必同传 `zone`，否则日期按系统时区组合、时刻与 `now` 比较，语义静默分裂
- **不重复日程不推进**：目标已过仍原样返回过去时刻，`countdown()` 会如实报告「已过」；需要自动推进请配置重复规则
- **`countdown()` 只有单一量级**：没有周单位，也没有时分混合的复合细分（如「3天4小时」；钟面年月日时分秒复合细分用 `calendarCountdown()`）
- **选择器仅公历、仅周/月单位**：`YEAR` 不支持选择器（构造拒绝）——「每年五月第二个周日」类规则暂不可表达；lunar 与 DAY/HOUR/MINUTE 单位配选择器同样拒绝；配选择器的月重复**不走锚点日收缩回弹**（出现日由选择器决定，见[选择器语义](#选择器语义)）
- **`occurrences()` 每元素成本 ≈ 一次 `nextTarget` 调用**：含远期快路径，「未来 N 次」类预览绰绰有余；大跨度全量枚举（数万次出现）请评估成本后使用
- **两套重复语义按单位分界**（ISO 8601 惯例）：天及以上为钟面格点（跨夏令时每天出现在同一本地时刻），小时/分钟为真实时长格点（间隔不变、本地钟面跨夏令时漂移 1 小时，且不受 0001..9999 上界守护、不抛越界异常）
- **iOS 无 x64 模拟器目标**（对齐 tyme4kt 发布面，仅 arm64 真机与模拟器）；Android minSdk 24+、JVM 11+，无需 desugaring
- **iOS 真机目标不执行测试**：Build 与 Release 工作流的 macOS job 均只跑模拟器目标（`iosSimulatorArm64Test`；Linux 上 iOS 测试无法运行，Release 的发布步骤以其通过为先决），真机目标（`iosArm64`）仅交叉编译验证——两者运行的是同一份 commonTest 代码
- **3.x → 4.0 迁移**：`Schedule` 构造器新增 `weekdays` / `weekdayOrdinals` / `monthDays` / `clampMonthDays` / `repeatCount` 五个字段（均带默认值）——命名参数用法**源码兼容**，但构造器签名变更对已编译消费方为**二进制不兼容**，升级 4.0.0 需重新编译；行为无破坏性变更（全部新字段默认关闭，默认值下推算结果与 3.x 一致）
- **2.x → 3.0 迁移**：坐标从 JitPack 的 `com.github.kamiiroawase.nexttime:nexttime-*` 迁到 Maven Central 的 `io.github.kamiiroawase:nexttime*`，包名同步从 `com.github.kamiiroawase.nexttime` 改为 `io.github.kamiiroawase.nexttime`（import 全量替换即可）；JitPack 上的 2.x 旧版本冻结不再维护

## 开发

```bash
git clone https://github.com/kamiiroawase/nexttime.git
cd nexttime
./gradlew build        # JDK 21+：编译全部 target + 宿主可执行测试 + 格式检查
./gradlew jvmTest      # 单独跑某个平台
./gradlew :benchmark:benchmark   # 回归基准（仅 JVM、可选、不进 CI 门禁）
```

244 个用例（`kotlin.test`，commonTest，核心 227 + serialization 8 + rrule 9）覆盖公历/农历推算、闰月、月末收缩、DST 缺口/重叠/跳日、小时/分钟真实时长格点（含跨 DST 漂移与两千年长跨度）、范围边界与完结判定、正反对偶不变量、倒计时取整与日历分量、周/月选择器（星期集合、月内日、序数与缺失日两模式、锚点隶属）、出现次数上限完结与出现序列、序列化线格式与解码校验、RRULE 无损子集双向转换；另有固定种子的**属性测试**（确定性模糊：随机日程 × 随机查询时刻 × 含 ±30/45 分钟夏令时极值的时区池，失败消息携带种子与用例可精确复现）锚定七大普适不变量；在 JVM、Android 单元测试与 wasm(Node) 上运行，iOS 模拟器由 macOS CI 执行。`:benchmark` 模块（kotlinx-benchmark，仅 JVM、不发布）锚定最坏路径的量级——公元 1 年锚点月重复推到 9999 年约 3 毫秒。质量门禁全挂在 `build` 上（三模块同门禁）：Spotless 格式、`explicitApi()` 显式 API、binary-compatibility-validator 公开 API 快照（有意变更时跑 `./gradlew apiDump`，子模块为 `:serialization:apiDump` / `:rrule:apiDump`）。

## 许可

[The Unlicense](LICENSE) —— 公共领域，随意使用。

本库依赖 [tyme4kt](https://github.com/6tail/tyme4kt)（MIT License, Copyright (c) 6tail）与 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime)（Apache 2.0, JetBrains）。两者以独立构件由消费方自行解析，其许可不随本库重新授权；将其打入分发包（如 APK/IPA）时请按各自许可要求附上版权与许可声明。
