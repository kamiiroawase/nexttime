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

dependencyResolutionManagement {
    // PREFER_PROJECT：wasmJs 的 Node.js 分发仓库（nodejs.org/dist，ivy 布局）由插件按工程注入，
    // 需允许工程仓库；其余依赖仍可命中 mavenCentral/google
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "nexttime"
