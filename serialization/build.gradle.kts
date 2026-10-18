import io.github.kamiiroawase.nexttime.conventions.NexttimePublishingExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)

    // 共享的格式约定（buildSrc 约定插件）；发布形态与签名守卫经根脚本的
    // allprojects 接线统一，差异经 nexttimePublishing 扩展声明（见文件尾）
    id("nexttime.spotless")

    // 二进制兼容守护不在子模块应用：BCV 由根模块 apply 时经 allprojects 覆盖所有
    // 子项目（含 apiDump/apiCheck 任务注册与 klib 开关），重复 apply 会在创建
    // bcv-rt-jvm-cp 配置时冲突。有意改动后跑 ./gradlew :serialization:apiDump
    //
    // KDoc 生成的 API 参考文档（同根模块，配置取 Dokka 多平台默认值）
    alias(libs.plugins.dokka)
}

// 版本唯一来源在根模块（v* tag 精确匹配推导）；子模块沿用根的推导结果，
// 同一次发布（tag）下三个构件版本严格一致
version = rootProject.version

kotlin {
    // 目标平台与核心库对齐（见根 build.gradle.kts 的注释）
    android {
        namespace = "io.github.kamiiroawase.nexttime.serialization"
        compileSdk = 37
        minSdk = 24

        // AGP KMP 插件默认不启用 host 测试；commonTest 须在 Android 单元测试上运行
        withHostTest { }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    explicitApi()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            // 核心库：api 暴露（Schedule 类型出现在公开签名里）
            api(project(":"))
            // 手写 KSerializer 只需 core；Json 编解码由消费方自带（core 随传递解析）
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // golden/round-trip 测试用 Json 实编实解
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// klib ABI 守护经根模块的 BCV 配置统一开启（apiValidation { klib { enabled = true } }，
// 对 allprojects 生效），此处无需重复声明

// 发布坐标的模块差异（group、Central 发布形态、pom 公共字段与签名守卫在根脚本
// 的 allprojects 接线内）
configure<NexttimePublishingExtension> {
    artifactId = "nexttime-serialization"
    displayName = "nexttime-serialization"
    descriptionText = "kotlinx-serialization and Android Parcelable support for nexttime schedules"
}
