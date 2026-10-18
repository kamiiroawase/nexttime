package io.github.kamiiroawase.nexttime.conventions

import org.gradle.api.provider.Property

/**
 * 发布坐标的模块差异参数（根脚本 allprojects 钩子统一接线的输入）。公共部分
 * ——group、Central 发布形态、pom 的 url/许可/开发者/scm、签名私钥守卫——在
 * 根脚本单一副本内固定；各模块只声明这三项。纯值对象、不引用任何插件类型，
 * buildSrc 与主构建的类加载域隔离对它无影响
 */
interface NexttimePublishingExtension {
    /** 构件基名：vanniktech 从它派生 KMP 平台变体 -android/-jvm/-iosarm64/…；缺省取项目名 */
    val artifactId: Property<String>

    /** pom 的 name；缺省取项目名 */
    val displayName: Property<String>

    /** pom 的 description；无缺省——遗漏时由 vanniktech 的 pom 完整性校验大声失败 */
    val descriptionText: Property<String>
}
