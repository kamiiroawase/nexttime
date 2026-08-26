# nexttime

[![Build](https://github.com/kamiiroawase/nexttime/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/nexttime/actions)
[![License: Unlicense](https://img.shields.io/badge/License-Unlicense-blue.svg)](http://unlicense.org/)

倒计时目标日推算库：给定日程的目标日、时刻与重复规则，推算下一个目标时刻并给出倒计时状态。支持公历与农历（含闰月语义）、天/周/月/年重复、任意时区。

Kotlin Multiplatform 库（commonMain 单一代码），目标平台：**Android（minSdk 24）、JVM（11+）、iOS（arm64 真机与模拟器）、wasmJs**。农历历法基于同作者的 [tyme4kt](https://github.com/6tail/tyme4kt)（[lunar-java](https://github.com/6tail/lunar-java) 的 KMP 升级版），日期时间基于 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) 与 `kotlin.time`。库只做计算、不输出任何语言文案，渲染交给消费方按语言实现（见[本地化渲染](#本地化渲染)）。

## 特性

- **目标时刻推算** `Schedule.nextTarget()`：非重复日程取目标日本身；重复时按周期推进到不早于当前时刻
- **公历重复**：天/周/月/年；月末收缩以锚点日为基准、后续回弹（1月31日 → 2月28日 → 3月31日），与农历一致
- **农历重复**：月/年重复沿农历推进，闰月可选参与或跳过；日超出当月天数取月末
- **时区安全**：目标日按 UTC 毫秒存储，组合时刻按指定时区；夏令时缺口时刻自动顺延（如纽约 02:30 → 03:30）
- **性能**：公历天/周重复按周期直算远期出现（不逐周期组合时区）；农历长跨度推算跳过远期年份的历法换算、年表按年缓存
- **倒计时状态** `countdown()`：输出「已过/未到 + 量级 + 单位」的结构化状态，「还有」「已过」语义对称，秒级向上取整、进位不闪跳，目标与当前恰为同一瞬间时为 0 秒

## 环境要求

- Kotlin Multiplatform 项目；Android `minSdk 24+`（2.0.0 起不再使用 `java.time`，无需 26+/desugaring）；JVM 11+（对齐 tyme4kt 字节码）；iOS 为 arm64 真机与模拟器（无 x64 目标，对齐 tyme4kt 发布面）
- `tyme4kt` 与 `kotlinx-datetime` 以传递依赖自动引入，tyme4kt 的 API（`com.tyme.*`）亦可直接使用
- wasmJs 平台的 IANA 时区库（`@js-joda/timezone` npm 包）已由本库内嵌并随 klib 传递，消费方零配置

## 引入

```kotlin
repositories {
    maven("https://jitpack.io")
    mavenCentral()   // 传递依赖 tyme4kt / kotlinx-datetime
}
```

**2.0.0 起按平台引变体模块，1.x 的根坐标不可用**。JitPack 把 KMP 六模块改发到组 `com.github.kamiiroawase.nexttime`（组名带仓库名），不托管根模块的变体元数据（`.module`），并把根 POM 改写为连带声明全部六模块（含 `klib` 类型的 iosarm64 / iosSimulatorArm64 / wasm-js 等 native 依赖）——Android 等单平台工程直接依赖 `com.github.kamiiroawase:nexttime` 会解析报 `No matching variant`。各平台变体模块自身完整（产物 + `.module` + POM 及 tyme4kt / kotlinx-datetime 传递依赖），按消费平台取坐标：

| 消费平台 | 坐标 | 产物 |
|---|---|---|
| Android | `com.github.kamiiroawase.nexttime:nexttime-android:2.0.1` | aar |
| JVM | `com.github.kamiiroawase.nexttime:nexttime-jvm:2.0.1` | jar |
| iOS 真机（arm64） | `com.github.kamiiroawase.nexttime:nexttime-iosarm64:2.0.1` | klib |
| iOS 模拟器（arm64） | `com.github.kamiiroawase.nexttime:nexttime-iossimulatorarm64:2.0.1` | klib |
| wasmJs | `com.github.kamiiroawase.nexttime:nexttime-wasm-js:2.0.1` | klib |

单平台工程（版本目录写法；JVM 工程把模块名换成 `nexttime-jvm`）：

```toml
[versions]
nexttime = "2.0.1"

[libraries]
nexttime-android = { module = "com.github.kamiiroawase.nexttime:nexttime-android", version.ref = "nexttime" }
```

```kotlin
dependencies {
    implementation(libs.nexttime.android)
}
```

KMP 工程按目标源集各引对应变体。JitPack 上没有可解析的 common 元数据，`commonMain` 无法直接引用本库 API；需要在共享代码中调用时，要么用 GitHub Release 附件自建 Maven 仓库（附件为发布产物原件，原始组 `com.github.kamiiroawase`，按 maven 仓库目录结构放回后根模块 `.module` 完整可用，`commonMain` 声明一次根坐标即全平台自动解析），要么在 `commonMain` 定义自有接口与数据类型、各平台源集引变体实现：

```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies { implementation("com.github.kamiiroawase.nexttime:nexttime-android:2.0.1") }
        jvmMain.dependencies { implementation("com.github.kamiiroawase.nexttime:nexttime-jvm:2.0.1") }
        iosArm64Main.dependencies { implementation("com.github.kamiiroawase.nexttime:nexttime-iosarm64:2.0.1") }
        iosSimulatorArm64Main.dependencies { implementation("com.github.kamiiroawase.nexttime:nexttime-iossimulatorarm64:2.0.1") }
        wasmJsMain.dependencies { implementation("com.github.kamiiroawase.nexttime:nexttime-wasm-js:2.0.1") }
    }
}
```

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
val now = LocalDateTime(2026, 8, 23, 12, 0).toInstant(zone)   // 以 2026-08-23 12:00 为例

val next = schedule.nextTarget(now, zone)!!        // 2026-10-01T10:00+08:00（上海本地时刻）
val state = countdown(next, now)                   // Countdown(past = false, value = 38, unit = DAYS)（实隔 38 天 22 小时，取天向下取整）
```

展示日期时间：`next.toLocalDateTime(zone)` 得到 `LocalDateTime` 后按平台格式化。

## API

### Schedule 字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `lunar` | Boolean | false | 目标日按农历解释，月/年重复沿农历推进（天/周重复与公历无异） |
| `leapCount` | Boolean | false | 农历时闰月是否参与重复推算，详见[农历重复语义](#农历重复语义) |
| `targetDay` | Long | -1 | 目标日 UTC 毫秒值，-1 表示未选（`nextTarget` 返回 null） |
| `targetHour` | Int | -1 | 目标时，-1 表示未选 |
| `targetMinute` | Int | -1 | 目标分，-1 表示未选 |
| `targetSecond` | Int | -1 | 目标秒，-1 表示未选 |
| `repeatInterval` | Int | 0 | 重复间隔（0..100000，上界 `Schedule.MAX_REPEAT_INTERVAL`），0（或 `repeatUnit` 为 NONE）视为不重复 |
| `repeatUnit` | Int | RepeatUnit.NONE | 重复单位，取 `RepeatUnit.NONE/DAY/WEEK/MONTH/YEAR` |

关于 `targetDay`：

- 只取**日期**部分，固定按 UTC 解析；时分秒由 `targetHour/Minute/Second` 单独存储
- Android 上可直接存 `MaterialDatePicker` 的返回值（本身就是 UTC 毫秒，1970 前日期的负值同样直接可用）；其他平台用 `localDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()`
- 农历日程同样存锚点日期的公历 UTC 毫秒，库自动换算为农历月日参与推算
- 支持 **0001-01-01 至 9999-12-31**（UTC 日期）：1970-01-01 之前的日期以负毫秒表示（纪元日当天毫秒恰为 0，同样合法），1970 年前出生的生日可直接录入

所有字段在构造时校验：`targetDay` 仅接受 -1（未选）或解析到 0001-01-01..9999-12-31 的毫秒值（0 与负毫秒对应 1970-01-01 及更早日期，合法），时分秒仅接受 -1（未选）或各自合法区间（-1 以外没有「未选」语义），`repeatInterval` 在 0..100000，`repeatUnit` 必须是 `RepeatUnit` 常量；非法取值抛 `IllegalArgumentException`。

### nextTarget()

```kotlin
fun Schedule.nextTarget(
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Instant?
```

`Instant`/`Clock` 来自 `kotlin.time`（kotlinx-datetime 0.7+ 已并入）。自 1.x 迁移：原 `ZonedDateTime` 入参改传 `Instant`（`zdt.toInstant()`），结果用 `instant.toLocalDateTime(zone)` 取本地日期时间。

| 场景 | 行为 |
|---|---|
| `targetDay == -1`（未选） | 返回 null（0 与负毫秒是 1970-01-01 及更早的合法日期，不在此列） |
| 不重复 | 返回目标日组合时刻；**已过也原样返回过去时刻**，不推进 |
| 重复 | 从目标日起按周期推进到不早于 `now`；恰好等于 `now` 时不再推进 |
| 时分秒任一未选 | 按 00:00:00 组合 |
| 农历 + 天/周重复 | 与公历相同（农历日期不参与天/周步进） |
| 公历月/年重复遇短月/平年 | 收缩到当月最接近锚点日的一天，后续回弹（1/31 → 2/28 → 3/31；2/29 → 平年 2/28 → 闰年 2/29） |
| 锚点日期超出 0001-01-01..9999-12-31 | 构造 `Schedule` 即抛 `IllegalArgumentException`（全部日程生效，不区分公历/农历） |
| 推算越过支持日期范围（公历与农历统一 0001..9999） | 抛 `IllegalStateException`，不会死循环 |

### countdown()

```kotlin
fun countdown(target: Instant, now: Instant): Countdown

data class Countdown(
    val past: Boolean,      // true = 目标已过
    val value: Long,        // 展示数值
    val unit: CountdownUnit // DAYS / HOURS / MINUTES / SECONDS
)
```

细分规则（已过与未到对称）：

- 时长先**向上取整到完整秒**（用于秒级 tick 时与秒边界不对齐的场景，进位不闪跳）；目标与当前恰为同一瞬间时输出 0 秒
- 满一天取 `DAYS`，不足一天取 `HOURS`，不足一小时取 `MINUTES`，不足一分取 `SECONDS`
- 时长按真实时刻差计算：跨夏令时变化的一天实隔 23 或 25 小时，量级随真实时长（23 小时取小时、25 小时取天）；目标与当前分处不同时区同理

## 常见场景

```kotlin
// 公历每年重复：周年纪念日
Schedule(
    targetDay = utcMillis(LocalDate.of(2020, 6, 15)),
    targetHour = 9,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// 公历每两周重复：倒垃圾日
Schedule(
    targetDay = utcMillis(LocalDate.of(2026, 8, 3)),
    repeatInterval = 2,
    repeatUnit = RepeatUnit.WEEK
)

// 农历每年重复：生日（八月初十，闰月年不另过一次）
Schedule(
    lunar = true,
    targetDay = utcMillis(LocalDate.of(2026, 9, 20)),  // 锚点公历日期
    targetHour = 8,
    repeatInterval = 1,
    repeatUnit = RepeatUnit.YEAR
)

// 农历每月重复：初一十五类日程，闰月不参与
Schedule(
    lunar = true,
    leapCount = false,
    targetDay = utcMillis(LocalDate.of(2026, 8, 13)),  // 锚点：农历七月初一
    repeatInterval = 1,
    repeatUnit = RepeatUnit.MONTH
)
```

## 本地化渲染

库不产生文案，各显示元素的数据来源：

- **倒计时**：`Countdown.past / value / unit`，按语言拼接。中文示例：

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

- **公历日期**：`nextTarget` 返回的 `Instant`，用 `instant.toLocalDateTime(zone)` 转为 `LocalDateTime` 后按平台格式化（JVM/Android 可用 `DateTimeFormatter.ofLocalizedDateTime`）
- **农历日期**：用传递依赖 tyme4kt 取结构，`SolarDay.fromYmd(y, m, d).lunarDay`（`com.tyme.*`）；中文名可从 `LunarDay`/`LunarMonth` 的 `getName()` 取得，其他语言按 `month`（负数为闰月）/ `day` 数字自行命名

## 农历重复语义

- **月重复**：沿农历月序列步进；`leapCount = true` 时闰月算独立一步，`false` 时跳过闰月
- **年重复**：保持农历月日；闰月日日程在无该闰月的年份，`leapCount = true` 当年退化为普通月，`false` 推进到下一个有该闰月的农历年
- **年重复间隔**：`repeatInterval` 自锚点年沿农历年格点推进；`leapCount = false` 的闰月日程只在恰有该闰月的格点年命中，落在格点外的真实闰月年会被跳过
- **月末收缩**：日超出目标月天数时取月末（六月三十 → 闰六月廿九）；公历与农历一律以锚点日为基准收缩、后续回弹
- **数据边界**：农历推算限于可靠年表（农历 0..9999 年，公元 1 年年初日期即属农历 0 年），逐月推进在内层越过边界同样立即抛 `IllegalStateException`（不使用越界的农历年表数据）；锚点日期范围（0001-01-01..9999-12-31）由构造期校验统一保证
- **历史历法**：1645 年（时宪历颁行）之前的历史日期按现代天文规则回推，与当时各朝历法的实际置闰可能有出入；生日、纪念日等近代场景不受影响

以 2025 农历年（闰六月）为例，锚点六月初一、每 1 月重复：

| leapCount | 六月过后的下一个目标 |
|---|---|
| true | 闰六月初一（闰月算独立一步） |
| false | 七月初一（跳过闰月） |

## 时区与夏令时

- `targetDay` 是 locale 无关的 UTC 毫秒，同一份数据在任何时区解释出同一个**日期**
- 组合时刻按 `zone` 参数（默认系统时区）进行，跨时区用户看到各自本地时刻的倒计时
- 夏令时缺口时刻自动顺延：如纽约 2026-03-08 02:30 不存在（2 点跳 3 点），组合结果为 03:30；顺延只影响当日，重复的后续出现按原时刻重新组合（4 月回到 02:30）
- 夏令时重叠时刻（秋令时回拨，如纽约 2026-11-01 01:30 出现两次）取较早的一次

## 已知限制与常见坑

- **`targetDay` 支持范围为 0001-01-01 至 9999-12-31（UTC 日期）**：-1 是「未选」哨兵；1970 年前日期以负毫秒表示（`MaterialDatePicker` 的负返回值可直接存入），纪元日 1970-01-01 的毫秒值 0 同样合法；范围外的年份构造 `Schedule` 即拒
- **JitPack 坐标 2.0.0 起按平台拆分**：JitPack 把 KMP 六模块改发到组 `com.github.kamiiroawase.nexttime`，且不托管根模块的 `.module`、把根 POM 改写为连带声明全部平台变体（含 native `klib`）——Android 工程直接依赖根坐标 `com.github.kamiiroawase:nexttime` 解析报 `No matching variant`，必须按[引入](#引入)的平台表引 `nexttime-android` / `nexttime-jvm` / `nexttime-iosarm64` / `nexttime-iossimulatorarm64` / `nexttime-wasm-js`；`commonMain` 共享消费需自建 Maven 仓库镜像 Release 附件（原始组 `com.github.kamiiroawase`，根模块元数据完整）
- **2.0.0 API 破坏性变更**：`nextTarget`/`countdown` 全面改用 `kotlin.time.Instant` 与 `kotlinx-datetime.TimeZone`（1.x 为 `java.time.ZonedDateTime/ZoneId`）；1.x 调用方以 `instant.toLocalDateTime(zone)` 迁移
- **推算结果统一以 9999-12-31 为界**：公历与农历越过即抛 `IllegalStateException`。1.2.x 公历月/年重复曾可组合出 10359 年等界外结果，受 kotlinx-datetime 的 `LocalDate` 年份范围（0000..9999）限制不再支持；`repeatInterval` 上界 100000 不变，周单位上界间隔（约 1916 年）仍在范围内正常组合
- **时分秒「要么全选、要么全不选」**：任一字段为 -1 时整体按 00:00:00 组合，其余已选字段被静默丢弃——`targetHour = 8` 而 `targetMinute = -1` 得到的是零点，不是 08:00
- **`nextTarget(now, zone)` 成对传**：`zone` 默认系统时区；传入非默认时区的 `now` 时务必同传 `zone`，否则日期按系统时区组合、时刻与 `now` 比较，语义会静默分裂
- **不重复日程不推进**：目标已过仍原样返回过去时刻，`countdown()` 会如实报告「已过」；需要自动推进请配置重复规则
- **`countdown()` 只有单一量级**：满一天取天、不足一天取小时……没有周单位，也没有复合细分（如「3天4小时」）；复合展示由消费方用两个 `Instant` 自行计算
- **iOS 无 x64 模拟器目标**：对齐 tyme4kt 的发布面，仅 arm64 真机与模拟器；如需 x64 可在同生态提 issue
- **Android minSdk 24+ / JVM 11+**：分别为 tyme4kt 的 android 门槛与 jvm 字节码版本；common 代码不使用 `java.time`，无需 desugaring

## 测试

101 个 JUnit 兼容用例（`kotlin.test`，commonTest）覆盖公历/农历推算（含 1970 年前负毫秒锚点与范围上下界、边界锚点越界守护）、闰月（含多间隔计入步数、闰月年恢复与格点年退化）、月末收缩（含多间隔收缩回弹、农历年重复与闰月收缩）、跨年（含无闰月年腊月边界与月重复多间隔跨年）、DST 缺口与重叠（含重复出现与农历候选日、跳日时区、南半球与午夜跳变时区、Apia/Kwajalein 整天缺口）、时区组合（含 now 与 zone 分处不同时区、半时区）、入参与数据边界校验、结果不变量与倒计时状态细分（含跨 DST 真实时长、精确量级边界与亚秒进位翻量级、跨时区同瞬间）。用例在 JVM、Android 单元测试与 wasm(Node) 三平台运行，iOS 模拟器由 macOS CI 执行。

```
./gradlew build
```

## 版本历史

### 2.0.1（2026-08-26）

- 文档修正：**JitPack 坐标按平台拆分**（2.0.0 转成 KMP 库后引入坐标变化，非仅版本号）——JitPack 把六模块改发到组 `com.github.kamiiroawase.nexttime` 且不托管根模块 `.module`、把根 POM 改写为连带声明全部平台变体（含 native `klib`），Android 工程直接依赖根坐标 `com.github.kamiiroawase:nexttime` 解析报 `No matching variant`；README「引入」改为五平台变体坐标表（Android / JVM / iOS 真机 / iOS 模拟器 / wasmJs，均经 JitPack 产物探针实测 200），KMP 工程按目标源集各引变体，`commonMain` 共享消费给出 Release 附件自建镜像与接口抽象两条路径；「已知限制」同步补记
- 消除废弃 API 警告：`LocalDate.dayOfMonth` → `day`（kotlinx-datetime 0.8.0 更名，行为不变）
- 测试类补充 IDE 检查抑制（`@Suppress`），用例数不变（101 × 三平台）

### 2.0.0（2026-08-26）

- 迁移到 **Kotlin Multiplatform**：Android（minSdk 24）/ JVM（11+）/ iOS（arm64 真机与模拟器）/ wasmJs 五目标，commonMain 单一代码；Android 不再要求 minSdk 26 与 desugaring
- 依赖切换：农历历法由 lunar-java 换为同作者 KMP 库 **tyme4kt**（`cn.6tail:tyme4kt`，MIT，`com.tyme.*` API；全部农历断言实测与 lunar-java 一致，含农历 0 年下界与 2025 闰六月场景）；`java.time` 换为 **kotlinx-datetime 0.8.0** + `kotlin.time.Instant/Duration/Clock`
- **API 破坏性变更**：`nextTarget(now: Instant = Clock.System.now(), zone: TimeZone = TimeZone.currentSystemDefault()): Instant?`、`countdown(target: Instant, now: Instant)`；1.x 的 `ZonedDateTime` 调用方以 `instant.toLocalDateTime(zone)` 迁移
- 推算结果统一以 0001-01-01..9999-12-31 为界：公历月/年重复越过 9999 现与农历一致抛 `IllegalStateException`（1.2.x 曾可组合出 10359 年的结果，受 kotlinx-datetime `LocalDate` 年份范围限制不再支持）；周单位上界间隔仍在范围内正常组合
- wasmJs 平台内嵌 `@js-joda/timezone` npm 依赖：kotlinx-datetime 在 wasm 的 IANA 时区库随 klib 传递给消费方，零配置
- 测试迁移到 commonTest（`kotlin.test`）：101 用例 × JVM / Android / wasm(Node) 三平台运行，iOS 模拟器由 macOS CI 执行
- 构建与 CI：AGP 9（Gradle 9.7）、spotless 8；ubuntu 全目标构建与 KMP 六模块发布产物验证（根 + android/jvm/iosArm64/iosSimulatorArm64/wasmJs），新增 macos iOS 模拟器测试 job；JitPack 发布流程不变

### 1.2.2（2026-08-24）

- 测试 77 → 101：倒计时补齐精确量级边界（恰满一天/一小时/一分、亚秒进位翻量级、已过多日）与分处不同时区的同瞬间零秒；公历天/周重复跨秋令时回拨与春令时缺口直算、Apia 与 Kwajalein 跳日整天缺口、南半球夏令时缺口与重叠、半时区（如 +05:30）与午夜跳变时区组合、时分秒上界组合
- 农历补测：年重复三十锚点在无三十小年收缩、闰月三十锚点闰月年收缩、月重复多间隔跨年、闰月日日程闰月参与沿月序推进、闰月日年重复间隔二格点年退化、锚点恰在上界（月/年重复）立即抛 `IllegalStateException`、非重复农历日程返回公历锚点日

### 1.2.1（2026-08-24）

- 测试 56 → 77，补齐此前无测试钉住的承诺行为与组合缺口：`countdown` 同一瞬间输出 0 秒、配了间隔但单位未选视为不重复、重复日程锚点在未来原样返回、`targetDay` 只取日期部分（非零点毫秒）与范围上界日末毫秒、README 快速上手示例端到端
- 组合缺口：夏令时重叠 × 重复、农历候选日 × 缺口与重叠、周重复跨秋令时回拨与 Apia 跳日整天缺口的直算对齐、倒计时跨夏令时与跨时区按真实时刻差细分、月/年重复多间隔收缩回弹、闰月计入步数与闰月年恢复取闰月、农历年重复越界守护；不变量矩阵扩至纽约时区与新日程形态
- 文档修正：快速上手示例倒计时注释 39 → 38（实隔 38 天 22 小时，取天向下取整）；`nextTarget` 行为表「`targetDay <= 0` 返回 null」为 1.2.0 前残留，改为「`targetDay == -1`」；补记倒计时跨夏令时细分与公历推算无 9999 结果上限

### 1.2.0（2026-08-23）

- `targetDay` 支持 1970 年前日期：范围放宽为 0001-01-01 至 9999-12-31（UTC 日期），0 与负毫秒（1970-01-01 当天及更早）合法，`-1` 仍为「未选」哨兵；1970 年前出生的生日可直接录入，`MaterialDatePicker` 的负毫秒返回值直接可用
- 日期范围校验前移到构造期并对全部日程生效（此前仅农历月/年重复在 `nextTarget` 抛锚点异常）
- 农历年表下界经实测标定（往返一致、月表结构、史实锚点三重验证），推算守护双侧化（农历 0..9999 年）；文档注明 1645 年前历史日期按现代规则回推
- 文档修正：快速上手示例的时变输出注释改为固定时刻；「不重复取目标日本身」调整为「非重复日程取目标日本身」
- 测试 52 → 56

### 1.1.0（2026-08-23）

- 修复农历月重复跨无闰月农历年腊月边界的死循环：月表末尾的次年正月须连年份一并采用，`MAX_LUNAR_YEAR` 守护恢复有效
- 农历推算加固：锚点年超出 9999 在历法换算前即抛 `IllegalArgumentException`；逐月推进越过 9999 立即抛 `IllegalStateException`，不消费 lunar-java 越界年表的错误数据
- `repeatInterval` 增加上界 100000（`Schedule.MAX_REPEAT_INTERVAL`），任意单位组合日期均不超出 `java.time` 范围，不再裸抛 JDK `DateTimeException`
- 性能：公历天/周重复按周期秒直算远期出现（1970→2026 每日重复单次调用 1.63ms → 0.01ms）；农历长跨度跳过远期年份的历法换算、年表按年缓存（农历月重复 95.77ms → 16.10ms）
- 异常文案统一英文；README 新增「已知限制与常见坑」章节
- CI 改完整克隆并断言发布版本非 `0.0.0-SNAPSHOT`，tag 推导失效立即红
- 测试 42 → 52

### 1.0.0（2026-08-23）

- 公历 / 农历（闰月语义）目标时刻推算，天/周/月/年重复；月/年重复的月末收缩以锚点日为基准、后续回弹（1/31 → 2/28 → 3/31；2/29 → 平年 2/28 → 闰年 2/29），公历与农历一致
- `countdown()` 倒计时状态细分：秒级向上取整、进位不闪跳，目标与当前恰为同一瞬间时输出 0 秒
- 夏令时缺口时刻自动顺延（只影响当日、不泄漏到后续重复出现）；重叠时刻（秋令时回拨）取较早一次
- `Schedule` 构造期全字段校验，非法取值抛 `IllegalArgumentException`
- 农历推算以公历 9999 年为上限，极端规则无法命中时抛 `IllegalStateException`，不会死循环
- 公共 API 全面显式声明（`explicitApi`），KDoc 补全
- 42 个 JUnit 5 用例，拆分为公历推算 / 农历推算 / 倒计时 / 结果不变量四个套件
- 构建与 CI：版本号按 `-Pversion` ＞ git tag ＞ `0.0.0-SNAPSHOT` 三级推导；CI 校验发布产物（本地发布后断言 jar / sources / pom / module 齐全及传递依赖）

## 许可

[The Unlicense](LICENSE)

本库依赖 [tyme4kt](https://github.com/6tail/tyme4kt)（MIT License, Copyright (c) 6tail）与 [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime)（Apache 2.0, JetBrains）。两者以独立构件由消费方自行解析，其许可不随本库重新授权；将其打入分发包（如 APK/IPA）时请按各自许可要求附上版权与许可声明。
