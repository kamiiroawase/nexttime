import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // 对外 API 必须显式声明可见性并附 KDoc，防误暴露
    explicitApi()

    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
    }
}

java {
    // 编译与测试固定跑在 JDK 21 上（与 CI、JitPack 一致），缺失时由 foojay 解析器自动获取；
    // 产物仍以 JVM 8 为目标（下方 source/target 与 kotlin jvmTarget）
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

dependencies {
    api(libs.lunar)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Gradle 9 不再自动注入，测试运行时需要显式提供 platform launcher
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
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
        create<MavenPublication>("maven") {
            from(components["kotlin"])

            artifact(tasks.named("sourcesJar"))

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
