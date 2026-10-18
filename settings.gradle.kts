pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "nexttime"

// 可选配套模块：依赖根模块（核心库零新依赖，按需引入）
// - serialization：Schedule 的 kotlinx-serialization 序列化器与 Android Parcelable 包装
// - rrule：RFC 5545 RRULE 无损子集的双向转换
// - benchmark：kotlinx-benchmark 回归基准（仅 JVM 目标、不发布、不进 CI 门禁）
include(":serialization", ":rrule", ":benchmark")
