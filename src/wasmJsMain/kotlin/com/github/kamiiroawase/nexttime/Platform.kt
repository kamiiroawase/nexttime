package com.github.kamiiroawase.nexttime

import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
@JsModule("@js-joda/timezone")
private external object JsJodaTimezone

/**
 * 引用模块对象以建立 ESM 导入（副作用：向 kotlinx-datetime 的 ZoneRulesProvider
 * 注入 IANA 时区库）；=== 仅比较 externref，不调用任何 JS 方法。
 */
internal actual fun ensureIanaTzdb() {
    if (JsJodaTimezone !== JsJodaTimezone) error("@js-joda/timezone failed to load")
}
