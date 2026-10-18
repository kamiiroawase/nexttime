plugins {
    `kotlin-dsl`
}

dependencies {
    // 约定插件内按 id 应用 spotless，须把插件标记构件放上类路径；发布相关的
    // vanniktech/KGP 刻意不入列——插件间的类加载域隔离会让它们与各模块经
    // alias 应用的本体分处两域（vanniktech 反射不到 Kotlin 插件类、alias 又与
    // 类路径上的同 id 插件冲突），发布接线由根脚本的 allprojects 钩子承担。
    // 版本经 buildSrc/settings.gradle.kts 重导入的主版本目录解析
    implementation(libs.spotless.gradle.plugin)
}
