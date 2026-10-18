// buildSrc 复用主构建的版本目录（插件标记构件的版本与 alias 引用同源，
// Dependabot 仍在单一 [versions] 键上 bump）
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
