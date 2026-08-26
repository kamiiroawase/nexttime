# nexttime

[![Build](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/nexttime/actions)
[![License: Unlicense](https://img.shields.io/badge/License-Unlicense-blue.svg)](http://unlicense.org/)

倒计时目标日推算库：给定日程的目标日、时刻与重复规则，推算下一个目标时刻并给出倒计时状态。支持公历与农历（闰月语义）、天/周/月/年重复、任意时区。

Kotlin Multiplatform 库（commonMain 单一代码），目标平台：**Android（minSdk 24）、JVM（11+）、iOS（arm64 真机与模拟器）、wasmJs**。农历历法基于 [tyme4kt](https://github.com/6tail/tyme4kt)，日期时间基于 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) 与 `kotlin.time`。库只做计算、不输出任何语言文案，渲染交给消费方（见[本地化渲染](#本地化渲染)）。

## 特性

- **正反向推算**：`nextTarget()` 推进到下一个目标时刻（可带上限 `until`）；`previousTarget()` 取不晚于某时刻的最近一次出现；`anchor()` 暴露第一次出现
- **公历与农历重复**：天/周/月/年；农历月/年重复沿农历推进，闰月可选参与或跳过；月末收缩以锚点日为基准、后续回弹（1月31日 → 2月28日 → 3月31日），公历农历一致
- **时区与夏令时安全**：目标日按 UTC 毫秒存储，组合时刻按指定时区；缺口时刻自动顺延（如纽约 02:30 → 03:30）、重叠取较早一次
- **倒计时**：`countdown()` 输出「已过/未到 + 量级 + 单位」的结构化状态（可选进一模式）；`calendarCountdown()` 按日历细分年/月/日/时/分/秒（适合「X年X月X天」展示）
- **性能**：长跨度推算按周期直算或跳过远期历法换算，不逐周期迭代，反向推算复用同一套快路径

## 引入

```kotlin
repositories {
    maven("https://jitpack.io")
    mavenCentral()   // 传递依赖 tyme4kt / kotlinx-datetime
}
```

**按平台引变体模块，不要用根坐标**：JitPack 把 KMP 模块发布在 `com.github.kamiiroawase.nexttime` 组下（组名带仓库名），根模块 `com.github.kamiiroawase:nexttime` 的 POM 连带声明全部平台变体（含 native klib），Android 工程直接依赖会报 `No matching variant`。

| 消费平台 | 坐标 |
|---|---|
| Android | `com.github.kamiiroawase.nexttime:nexttime-android:2.1.0` |
| JVM | `com.github.kamiiroawase.nexttime:nexttime-jvm:2.1.0` |
| iOS 真机（arm64） | `com.github.kamiiroawase.nexttime:nexttime-iosarm64:2.1.0` |
| iOS 模拟器（arm64） | `com.github.kamiiroawase.nexttime:nexttime-iossimulatorarm64:2.1.0` |
| wasmJs | `com.github.kamiiroawase.nexttime:nexttime-wasm-js:2.1.0` |

单平台工程（版本目录写法，JVM 工程换 `nexttime-jvm`）：

```toml
[versions]
nexttime = "2.1.0"

[libraries]
nexttime-android = { module = "com.github.kamiiroawase.nexttime:nexttime-android", version.ref = "nexttime" }
```

```kotlin
dependencies {
    implementation(libs.nexttime.android)
}
```

KMP 工程按目标源集各引变体。JitPack 上没有 common 元数据，`commonMain` 无法直接引用本库 API；需要在共享代码中调用时，用 GitHub Release 附件自建 Maven 仓库（附件为发布产物原件、原始组 `com.github.kamiiroawase`，根模块元数据完整），或在 `commonMain` 定义自有接口、平台源集引变体实现：

```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies { implementation("com.github.kamiiroawase.nexttime:nexttime-android:2.1.0") }
        // jvmMain / iosArm64Main / iosSimulatorArm64Main / wasmJsMain 换对应变体
    }
}
```

tyme4kt 与 kotlinx-datetime 以传递依赖自动引入（tyme4kt 的 `com.tyme.*` API 亦可直接使用）；wasmJs 平台的 IANA 时区库已内嵌并随 klib 传递，消费方零配置。

## 快速上手

```kotlin
import com.github.kamiiroawase.nexttime.Schedule
import com.github.kamiiroawase.nexttime.countdown
import com.github.kamiiroawase.nexttime.nextTarget
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// 单次日程：2026-10-01 10:00:00（上海时区）
val schedule = Schedule(
    targetDay = LocalDate(2026, 10, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
    targetHour = 10,
)

val zone = TimeZone.of("Asia/Shanghai")
val now = LocalDateTime(2026, 8, 23, 12, 0).toInstant(zone)

val next = schedule.nextTarget(now, zone)!!   // 2026-10-01T10:00+08:00
val state = countdown(next, now)              // Countdown(past = false, value = 38, unit = DAYS)
```

展示日期时间：`next.toLocalDateTime(zone)` 得到 `LocalDateTime` 后按平台格式化。

## 常见场景

```kotlin
// 公历每年重复：周年纪念日
Schedule(
    targetDay = utcMillis(LocalDate(2020, 6, 15)),
    targetHour = 9,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// 公历每两周重复：倒垃圾日
Schedule(
    targetDay = utcMillis(LocalDate(2026, 8, 3)),
    repeatInterval = 2,
    repeatUnit = RepeatUnit.WEEK
)

// 农历每年重复：生日（八月初十，闰月年不另过一次）
Schedule(
    lunar = true,
    targetDay = utcMillis(LocalDate(2026, 9, 20)),  // 锚点公历日期
    targetHour = 8,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// 农历每月重复：初一十五类日程，闰月不参与
Schedule(
    lunar = true,
    targetDay = utcMillis(LocalDate(2026, 8, 13)),  // 锚点：农历七月初一
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH
)

// 带结束时间的重复：结束后正向返回 null，完结展示改锚定「结束前最后一次出现」
val end = LocalDateTime(2026, 12, 31).toInstant(zone)
schedule.nextTarget(now, zone, end)   // 出现晚于 end → null（锚点本身不受限）
schedule.previousTarget(end, zone)    // 不晚于 end 的最后一次出现
schedule.anchor(zone)                 // 第一次出现
```

## API

`Instant` / `Clock` 来自 `kotlin.time`。

### Schedule 字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `lunar` | Boolean | false | 目标日按农历解释，月/年重复沿农历推进（天/周重复与公历无异） |
| `leapCount` | Boolean | false | 农历时闰月是否参与重复推算，详见[农历重复语义](#农历重复语义) |
| `targetDay` | Long | -1 | 目标日 UTC 毫秒值，-1 表示未选（推算函数返回 null） |
| `targetHour` | Int | -1 | 目标时，-1 表示未选 |
| `targetMinute` | Int | -1 | 目标分，-1 表示未选 |
| `targetSecond` | Int | -1 | 目标秒，-1 表示未选 |
| `repeatInterval` | Int | 0 | 重复间隔（0..100000），0 视为不重复 |
| `repeatUnit` | Int | RepeatUnit.NONE | 重复单位，取 `RepeatUnit.NONE/DAY/WEEK/MONTH/YEAR` |

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
| 不重复 | 返回目标日组合时刻，**已过也原样返回过去时刻**，不推进 |
| 重复正向 | 从锚点按周期推进到不早于 `now`；恰好等于 `now` 时不再推进 |
| `until` 上限 | 重复出现晚于 `until` 时返回 null（序列单调，后续必然超限）；**锚点与非重复日程不受约束**——重复结束不能追溯取消锚点 |
| 重复反向 | 不晚于 `before` 的最近一次出现（含恰等于）；`before` 早于锚点返回 null |
| 推算越过 0001..9999 | 抛 `IllegalStateException`，不会死循环；`previousTarget` 例外：返回界内最后一次出现 |
| 时分秒任一未选 | 按 00:00:00 组合 |
| 农历 + 天/周重复 | 与公历相同 |
| 月/年重复遇短月/平年 | 收缩到当月最接近锚点日的一天，后续回弹（1/31 → 2/28 → 3/31；2/29 → 平年 2/28 → 闰年 2/29） |

夏令时：每次出现独立按「日期 + 时刻 + 时区」组合——缺口时刻顺延（仅影响当日）、重叠取较早一次；`anchor()` 在锚点日落缺口时同样返回顺延后的时刻，恒等于第一次出现。

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
- `Rounding.CEIL_FUTURE`：未到方向向上取整（差一秒满整单位也进位：86399 秒 + 1 纳秒 = 1 天、3599 秒 = 1 小时；24 时 → 1 天、60 分 → 1 时边界进位）；**已过方向恒截断**

`calendarCountdown()`：按 `zone` 的**钟面**细分到年/月/日/时/分/秒，闰年与月长由日历自动处理；跨夏令时变化的一天计 1 天 0 小时（与 `countdown()` 的真实时长语义不同）；月末钳制与 java.time `Period` 一致（1/31 → 2/28 为 0 个月 28 天）。零分量原样输出，省略由渲染决定。

### 农历重复语义

- **月重复**：沿农历月序列步进；`leapCount = true` 时闰月算独立一步，`false` 时跳过闰月
- **年重复**：保持农历月日；闰月日日程在无该闰月的年份，`true` 当年退化为普通月，`false` 推进到下一个有该闰月的农历年（`repeatInterval` 沿农历年格点推进）
- **月末收缩**：日超出目标月天数时取月末（六月三十 → 闰六月廿九）
- **历史历法**：1645 年（时宪历颁行）之前按现代天文规则回推，与当时实际置闰可能有出入；生日、纪念日等近代场景不受影响

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

## 已知限制与常见坑

- **`targetDay` 只取 UTC 日期**，支持范围 0001-01-01..9999-12-31；`-1` 是「未选」哨兵，0 与负毫秒（1970-01-01 及更早）是合法日期
- **时分秒「要么全选、要么全不选」**：任一字段为 -1 时整体按 00:00:00 组合——`targetHour = 8` 而 `targetMinute = -1` 得到的是零点，不是 08:00
- **`nextTarget(now, zone)` 成对传**：传入非默认时区的 `now` 时务必同传 `zone`，否则日期按系统时区组合、时刻与 `now` 比较，语义静默分裂
- **不重复日程不推进**：目标已过仍原样返回过去时刻，`countdown()` 会如实报告「已过」；需要自动推进请配置重复规则
- **`countdown()` 只有单一量级**：没有周单位，也没有时分混合的复合细分（如「3天4小时」；钟面年月日时分秒复合细分用 `calendarCountdown()`）
- **iOS 无 x64 模拟器目标**（对齐 tyme4kt 发布面，仅 arm64 真机与模拟器）；Android minSdk 24+、JVM 11+，无需 desugaring
- **JitPack 按平台引变体**，根坐标不可用（见[引入](#引入)）

## 测试

138 个用例（`kotlin.test`，commonTest）覆盖公历/农历推算、闰月、月末收缩、DST 缺口/重叠/跳日、范围边界、正反对偶不变量、倒计时取整与日历分量；在 JVM、Android 单元测试与 wasm(Node) 三平台运行，iOS 模拟器由 macOS CI 执行。

```
./gradlew build
```

## 版本历史

- **2.1.0**（2026-08-26）：新增 `previousTarget` / `anchor` / `nextTarget(until)` / `countdown` 取整模式 / `calendarCountdown`（纯增量，无破坏性变更）
- **2.0.0**（2026-08-26）：迁移 Kotlin Multiplatform（Android/JVM/iOS/wasmJs）；API 改用 `kotlin.time.Instant` 与 `kotlinx-datetime.TimeZone`，1.x 的 `ZonedDateTime` 调用方以 `instant.toLocalDateTime(zone)` 迁移；JitPack 坐标按平台拆分
- **1.x**（2026-08）：单平台版本（java.time + lunar-java），历史明细见 [Releases](https://github.com/kamiiroawase/nexttime/releases)

## 许可

[The Unlicense](LICENSE)

本库依赖 [tyme4kt](https://github.com/6tail/tyme4kt)（MIT License, Copyright (c) 6tail）与 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime)（Apache 2.0, JetBrains）。两者以独立构件由消费方自行解析，其许可不随本库重新授权；将其打入分发包（如 APK/IPA）时请按各自许可要求附上版权与许可声明。
