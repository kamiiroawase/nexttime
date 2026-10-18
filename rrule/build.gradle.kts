import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish)

    // 二进制兼容守护不在子模块应用：BCV 由根模块 apply 时经 allprojects 覆盖所有
    // 子项目（含 apiDump/apiCheck 任务注册与 klib 开关），重复 apply 会在创建
    // bcv-rt-jvm-cp 配置时冲突。有意改动后跑 ./gradlew :rrule:apiDump
    //
    // KDoc 生成的 API 参考文档（同根模块，配置取 Dokka 多平台默认值）
    alias(libs.plugins.dokka)
}

group = "io.github.kamiiroawase"

// 版本唯一来源在根模块（v* tag 精确匹配推导）；子模块沿用根的推导结果，
// 同一次发布（tag）下三个构件版本严格一致
version = rootProject.version

kotlin {
    // 目标平台与核心库对齐（见根 build.gradle.kts 的注释）
    android {
        namespace = "io.github.kamiiroawase.nexttime.rrule"
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
            // 核心库：api 暴露（Schedule 类型出现在公开签名里）；纯字符串解析无其他依赖
            api(project(":"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// klib ABI 守护经根模块的 BCV 配置统一开启（apiValidation { klib { enabled = true } }，
// 对 allprojects 生效），此处无需重复声明

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

// 发布签名守卫（同根模块的签名私钥校验，空值视为未配置）
val signingKey =
    providers
        .gradleProperty("signingInMemoryKey")
        .orNull
        ?.replace("\r\n", "\n")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
if (signingKey != null) {
    require(
        signingKey.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----") &&
            signingKey.endsWith("-----END PGP PRIVATE KEY BLOCK-----"),
    ) {
        "signingInMemoryKey is not a complete ASCII-armored secret key (expected the " +
            "-----BEGIN/END PGP PRIVATE KEY BLOCK-- lines); re-export with " +
            "'gpg --export-secret-keys --armor <key id>' and store the full output"
    }
}

mavenPublishing {
    // Gradle 项目名（rrule）不是构件名：显式指定 nexttime-rrule，
    // KMP 平台变体（-android/-jvm/-iosarm64/…）由插件从构件名派生
    coordinates(artifactId = "nexttime-rrule")

    publishToMavenCentral(automaticRelease = true)

    if (signingKey != null) {
        signAllPublications()
    }

    pom {
        name.set("nexttime-rrule")
        description.set("RFC 5545 RRULE conversion for nexttime schedules (lossless subset)")
        url.set("https://github.com/kamiiroawase/nexttime")
        licenses {
            license {
                name.set("The Unlicense")
                url.set("https://unlicense.org/")
            }
        }
        developers {
            developer {
                id.set("kamiiroawase")
                name.set("紅葉")
                url.set("https://github.com/kamiiroawase/nexttime")
            }
        }
        scm {
            url.set("https://github.com/kamiiroawase/nexttime")
            connection.set("scm:git:https://github.com/kamiiroawase/nexttime.git")
            developerConnection.set("scm:git:git@github.com:kamiiroawase/nexttime.git")
        }
    }
}
