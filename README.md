# nexttime

[![Build](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/nexttime.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/nexttime)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

倒计时目标日推算库：给定日程的**目标日、时刻与重复规则，推算下一个目标时刻并给出倒计时状态**——公历与农历（闰月语义）重复、天/周/月/年/小时/分钟单位、任意时区夏令时安全。库只做计算、不输出任何语言文案，渲染交给消费方（见[本地化渲染](#本地化渲染)）。

Kotlin Multiplatform 库（commonMain 单一代码），农历历法基于 [tyme4kt](https://github.com/6tail/tyme4kt)，日期时间基于 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) 与 `kotlin.time`。

[English version](README.en.md)

## 使用

发布于 [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/nexttime)，版本跟随 `v*` git tag。支持 Android（minSdk 24）、JVM 11+、iOS（arm64 真机与模拟器）与 wasmJs。

```kotlin
repositories { mavenCentral() }

dependencies {
    // KMP 消费方在 commonMain 引用根坐标（Gradle module metadata 自动解析平台变体）；
    // 需要钉住具体变体时见下表
    implementation("io.github.kamiiroawase:nexttime:3.2.1")
}
```

需要钉住具体平台变体时（Maven 依赖、锁坐标等场景）：

| 消费平台 | 坐标 |
|---|---|
| Android | `io.github.kamiiroawase:nexttime-android:3.2.1` |
| JVM | `io.github.kamiiroawase:nexttime-jvm:3.2.1` |
| iOS 真机（arm64） | `io.github.kamiiroawase:nexttime-iosarm64:3.2.1` |
| iOS 模拟器（arm64） | `io.github.kamiiroawase:nexttime-iossimulatorarm64:3.2.1` |
| wasmJs | `io.github.kamiiroawase:nexttime-wasm-js:3.2.1` |

版本目录写法：

```toml
[versions]
nexttime = "3.2.1"

[libraries]
nexttime = { module = "io.github.kamiiroawase:nexttime", version.ref = "nexttime" }
```

tyme4kt 与 kotlinx-datetime 以传递依赖自动引入（tyme4kt 的 `com.tyme.*` API 亦可直接使用）；wasmJs 平台的 IANA 时区库已内嵌并随 klib 传递，消费方零配置。

- **正反向推算**：`nextTarget()` 推进到下一个目标时刻（可带上限 `until`）；`previousTarget()` 取不晚于某时刻的最近一次出现；`anchor()` 暴露第一次出现
- **公历与农历重复**：小时/分钟/天/周/月/年；小时/分钟为真实时长间隔（每 8 小时 = 8 个真实小时，跨日连续），天及以上为日历格点；农历月/年重复沿农历推进，闰月可选参与或跳过；月末收缩以锚点日为基准、后续回弹（1月31日 → 2月28日 → 3月31日），公历农历一致
- **时区与夏令时安全**：目标日按 UTC 毫秒存储，组合时刻按指定时区；缺口时刻自动顺延（如纽约 02:30 → 03:30）、重叠取较早一次
- **倒计时**：`countdown()` 输出「已过/未到 + 量级 + 单位」的结构化状态（可选进一模式）；`calendarCountdown()` 按日历细分年/月/日/时/分/秒（适合「X年X月X天」展示）
- **性能**：天/周与农历重复的长跨度按周期直算或跳过远期历法换算；公历月/年重复沿日历逐步推进，量级有界（最坏约 12 万步、实测 JVM 数十毫秒）；反向推算复用同一套快路径

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

// 带结束时间的重复：结束后正向返回 null，完结展示改锚定「结束前最后一次出现」
val end = LocalDateTime(2026, 12, 31).toInstant(zone)
anniversary.nextTarget(now, zone, end)   // 出现晚于 end → null（锚点本身不受限）
anniversary.previousTarget(end, zone)    // 不晚于 end 的最后一次出现
anniversary.anchor(zone)                 // 第一次出现
```

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

关于 `targetDay`：

- 只取**日期**部分，固定按 UTC 解析；时分秒由 `targetHour/Minute/Second` 单独存储
- Android 上可直接存 `MaterialDatePicker` 的返回值（本身就是 UTC 毫秒，负值同样可用）；其他平台用 `localDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()`
- 支持 **0001-01-01 至 9999-12-31**：1970-01-01 之前的日期以负毫秒表示，`-1` 是「未选」哨兵
- 农历日程同样存锚点日期的公历 UTC 毫秒，库自动换算为农历月日参与推算

非法取值在构造时抛 `IllegalArgumentException`。

### 推算：nextTarget / previousTarget / anchor

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
```

| 场景 | 行为 |
|---|---|
| `targetDay == -1`（未选） | 三个函数都返回 null |
| 不重复（`repeatInterval = 0` 或 `repeatUnit = NONE`，任一即视为不重复） | 返回目标日组合时刻，**已过也原样返回过去时刻**，不推进 |
| 重复正向 | 从锚点按周期推进到不早于 `now`；恰好等于 `now` 时不再推进 |
| `until` 上限 | 重复出现晚于 `until` 时返回 null（序列单调，后续必然超限）；**锚点与非重复日程不受约束**——重复结束不能追溯取消锚点；`until` 早于 `now`，或界内已无不早于 `now` 的出现（推算越过支持范围即完结；9999-12-31 末秒组合加 48 小时余量只是免推算的快路径上界，实际最后一次出现可能更早）时直接返回 null，不为凑结论抛越界异常——`[now, until]` 内不可能再有界内出现 |
| 重复反向 | 不晚于 `before` 的最近一次出现（含恰等于）；`before` 早于锚点返回 null |
| 推算越过 0001..9999 | 抛 `IllegalStateException`，不会死循环；例外——`previousTarget` 返回界内最后一次出现，`nextTarget` 带 `until` 时界内出现耗尽即完结返回 null（不带 `until` 的越界查询仍抛异常，答案不可表示） |
| 时分秒全未选 | 按 00:00:00 组合（部分选择已在构造期拒绝，要整点须显式写全三个字段） |
| 农历 + 天/周重复 | 与公历相同 |
| 小时/分钟重复 | **真实时长格点**：出现 = 锚点 + 步数×间隔，跨日连续；`lunar` 无关；**不受 0001..9999 上界守护**（Instant 值域内任意推进） |
| 月/年重复遇短月/平年 | 收缩到当月最接近锚点日的一天，后续回弹（1/31 → 2/28 → 3/31；2/29 → 平年 2/28 → 闰年 2/29） |

夏令时的两套语义：天及以上单位每次出现独立按「日期 + 时刻 + 时区」组合（钟面格点）——缺口时刻顺延（仅影响当日）、重叠取较早一次，跨夏令时每天出现在同一本地时刻；`anchor()` 在锚点日落缺口时同样返回顺延后的时刻，恒等于第一次出现。小时/分钟按真实时长推进（ISO 8601 time-based 惯例）——间隔不变、跨夏令时本地钟面漂移 1 小时。

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
- **两套重复语义按单位分界**（ISO 8601 惯例）：天及以上为钟面格点（跨夏令时每天出现在同一本地时刻），小时/分钟为真实时长格点（间隔不变、本地钟面跨夏令时漂移 1 小时，且不受 0001..9999 上界守护、不抛越界异常）
- **iOS 无 x64 模拟器目标**（对齐 tyme4kt 发布面，仅 arm64 真机与模拟器）；Android minSdk 24+、JVM 11+，无需 desugaring
- **iOS 真机目标不执行测试**：Build 与 Release 工作流的 macOS job 均只跑模拟器目标（`iosSimulatorArm64Test`；Linux 上 iOS 测试无法运行，Release 的发布步骤以其通过为先决），真机目标（`iosArm64`）仅交叉编译验证——两者运行的是同一份 commonTest 代码
- **2.x → 3.0 迁移**：坐标从 JitPack 的 `com.github.kamiiroawase.nexttime:nexttime-*` 迁到 Maven Central 的 `io.github.kamiiroawase:nexttime*`，包名同步从 `com.github.kamiiroawase.nexttime` 改为 `io.github.kamiiroawase.nexttime`（import 全量替换即可）；JitPack 上的 2.x 旧版本冻结不再维护

## 开发

```bash
git clone https://github.com/kamiiroawase/nexttime.git
cd nexttime
./gradlew build        # JDK 21+：编译全部 target + 宿主可执行测试 + 格式检查
./gradlew jvmTest      # 单独跑某个平台
```

170 个用例（`kotlin.test`，commonTest）覆盖公历/农历推算、闰月、月末收缩、DST 缺口/重叠/跳日、小时/分钟真实时长格点（含跨 DST 漂移与两千年长跨度）、范围边界与完结判定、正反对偶不变量、倒计时取整与日历分量；在 JVM、Android 单元测试与 wasm(Node) 上运行，iOS 模拟器由 macOS CI 执行。质量门禁全挂在 `build` 上：Spotless 格式、`explicitApi()` 显式 API、binary-compatibility-validator 公开 API 快照（有意变更时跑 `./gradlew apiDump`）。

## 许可

[The Unlicense](LICENSE) —— 公共领域，随意使用。

本库依赖 [tyme4kt](https://github.com/6tail/tyme4kt)（MIT License, Copyright (c) 6tail）与 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime)（Apache 2.0, JetBrains）。两者以独立构件由消费方自行解析，其许可不随本库重新授权；将其打入分发包（如 APK/IPA）时请按各自许可要求附上版权与许可声明。
