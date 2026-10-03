package io.github.kamiiroawase.nexttime

import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
@JsModule("@js-joda/timezone")
private external object JsJodaTimezone

/**
 * 引用模块对象以建立 ESM 导入（副作用：向 kotlinx-datetime 的 ZoneRulesProvider
 * 注入 IANA 时区库），kotlinx-datetime 官方推荐的加载写法。导入失败时模块
 * 初始化本身即抛错，无需也不可能在调用点另行检查。
 */
internal actual fun ensureIanaTzdb() {
    JsJodaTimezone
}
