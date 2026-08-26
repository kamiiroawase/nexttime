import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.spotless)
    `maven-publish`
}

group = "com.github.kamiiroawase"

// 版本优先级：-Pversion（JitPack 打 tag 时传入，如 v1.1.0 → 1.1.0）＞ git tag 推导
// （CI 需完整克隆 fetch-depth=0；HEAD 恰在 tag 上得到精确版本，之后沿用最近可达 tag）
// ＞ 0.0.0-SNAPSHOT（无 tag 或无 git 环境）
// （无 tag 或无 git 环境）。不可硬编码版本号，否则会覆盖 JitPack 传入值造成 tag 与产物版本脱节
version =
    providers
        .gradleProperty("version")
        .orElse(providers.of(GitTagVersionSource::class.java) {})
        .orElse("0.0.0-SNAPSHOT")
        .get()

kotlin {
    // 目标平台与依赖 tyme4kt 的发布面对齐：Android / JVM / iOS（真机与模拟器）/ wasmJs；
    // Android 走 AGP 的 KMP 库插件（kotlin { android { } }，无顶层 android 块、无 androidTarget）
    android {
        namespace = "com.github.kamiiroawase.nexttime"
        compileSdk = 36

        // tyme4kt 的 android 门槛；common 代码不使用 java.time，不再要求 minSdk 26 / desugaring
        minSdk = 24

        // AGP KMP 插件默认不启用 host 测试；commonTest 须在 Android 单元测试上运行
        withHostTest { }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // tyme4kt-jvm 产物为 JVM 11 字节码，JVM 侧统一以 11 为目标
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

    // 对外 API 必须显式声明可见性并附 KDoc，防误暴露
    explicitApi()

    // 编译与测试固定跑在 JDK 21 上（与 CI、JitPack 一致），缺失时由 foojay 解析器自动获取
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            // tyme4kt：lunar-java 同作者的 KMP 升级版（com.tyme.*），api 暴露给消费方直接使用
            api(libs.tyme4kt)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain {
            dependencies {
                // kotlinx-datetime 在 wasm 平台的 IANA 时区库取自 @js-joda/timezone（副作用注入
                // ZoneRulesProvider），由本库引入并随 klib 传递给消费方
                implementation(npm("@js-joda/timezone", "2.25.2"))
            }
        }
    }
}

// ktlint 风格与 gradle.properties 的 kotlin.code.style=official 一致；
// spotlessCheck 自动挂在 check 上，./gradlew build 即含格式检查
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            pom {
                name.set("nexttime")
                description.set("Countdown target date calculation for solar and lunar calendars")
                url.set("https://github.com/kamiiroawase/nexttime")
                licenses {
                    license {
                        name.set("The Unlicense")
                        url.set("https://unlicense.org")
                    }
                }
            }
        }
    }
}

// ValueSource 方式读取 git tag，对配置缓存安全（重用缓存时也会重新求值）
abstract class GitTagVersionSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String? =
        git("describe", "--tags", "--abbrev=0", "--match=v*")
            ?.removePrefix("v")
            ?.ifEmpty { null }

    private fun git(vararg args: String): String? =
        try {
            val process = ProcessBuilder("git", *args).redirectErrorStream(true).start()
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
                .ifEmpty { null }
                .takeIf { process.waitFor() == 0 }
        } catch (_: Exception) {
            null
        }
}
