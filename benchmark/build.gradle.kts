import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.benchmark)

    // 共享的格式约定（buildSrc 约定插件）；本模块不发布，不走 nexttime.publishing
    id("nexttime.spotless")
}

// 回归基准模块：仅 JVM 目标、不发布（无 maven-publish/dokka）、不进 CI 门禁——
// 计时抖动不适合做门禁，定位是本地/手动回归工具（README「最坏约 12 万步、实测
// JVM 数毫秒」等性能主张的复核锚点）。./gradlew build 只编译不执行基准，
// 跑基准用 ./gradlew :benchmark:benchmark；基准类放 jvmMain（模块仅 JVM 目标，
// benchmark 插件从 @Benchmark 方法生成 JMH 载体）
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.benchmark.runtime)
            implementation(project(":"))
        }
    }
}

benchmark {
    targets.register("jvm")

    // 均值模式适配「单次调用数十毫秒 ~ 数微秒」的量级跨度；预热与迭代取中庸
    // 值，本地复跑一轮约一两分钟
    configurations.named("main") {
        warmups = 3
        iterations = 5
        iterationTime = 500
        iterationTimeUnit = "ms"
        mode = "avgt"
        outputTimeUnit = "us"
    }
}
