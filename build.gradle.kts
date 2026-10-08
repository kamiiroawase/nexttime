import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish)

    // 公开 API 二进制兼容守护：api/nexttime.api 是已发布 API 的快照，有意改动
    // public 签名后跑 ./gradlew apiDump 更新并随提交评审；apiCheck 挂在 check
    // 上，./gradlew build 与两个 CI 工作流即含校验，防止无意的 API 破坏流出到
    // Maven Central
    alias(libs.plugins.binary.compatibility.validator)
}

group = "io.github.kamiiroawase"

// 版本唯一来源：HEAD 恰好落在 v* tag 上（CI 需完整克隆 fetch-depth=0；发布工作流
// 的 tag 检出正是这个形态）＞ 0.0.0-SNAPSHOT（其余一切：HEAD 不在 tag 上、无 git 环境）。
// 刻意不做「最近可达 tag」回退：tag 之后的提交沿用已发布版本号，会让本地
// publishToMavenLocal 在同名坐标下遮蔽已发布构件。版本不可硬编码——tag 是发布
// 号的唯一来源
version =
    providers
        .of(GitTagVersionSource::class.java) {}
        .orElse("0.0.0-SNAPSHOT")
        .get()

kotlin {
    // 目标平台与依赖 tyme4kt 的发布面对齐：Android / JVM / iOS（真机与模拟器）/ wasmJs；
    // Android 走 AGP 的 KMP 库插件（kotlin { android { } }，无顶层 android 块、无 androidTarget）
    android {
        namespace = "io.github.kamiiroawase.nexttime"
        compileSdk = 37

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

    // 编译与测试固定跑在 JDK 21 上（与 CI 一致），缺失时由 foojay 解析器自动获取
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
                implementation(
                    npm(
                        "@js-joda/timezone",
                        libs.versions.js.joda.timezone
                            .get(),
                    ),
                )
            }
        }
    }
}

// klib 目标（iOS 真机/模拟器、wasmJs）的 ABI 守护：JVM 快照不覆盖 klib 消费方，
// iOS/wasmJs 侧的 ABI 破坏此前无门禁；Android 变体与 JVM 同源同声明
// （explicitApi 编译期强制），由 JVM 快照代理。klib 校验是 BCV 的 alpha 能力，
// dump 含 ABI 签名、跨 Kotlin 版本可能重排，apiCheck 失败时人工 diff 确认后
// 重跑 apiDump 更新
apiValidation {
    klib {
        enabled = true
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

// 发布工作流以 ORG_GRADLE_PROJECT_signingInMemoryKey 传入 GPG 私钥，插件自行把它接入
// Gradle 的 signing 扩展。此处守门两件事：空值视为未配置——publishToMavenLocal 无
// 密钥也能跑（Build 工作流的发布校验依赖这一点）；非空值必须是完整的 ASCII 甲胄
// 私钥，缺失/改名/拷坏的 secret 带着指引失败，而不是 Gradle 玄妙的
// "Could not read PGP secret key"
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
        "signingInMemoryKey is not a complete ASCII-armored PGP secret key (expected the " +
            "-----BEGIN/END PGP PRIVATE KEY BLOCK----- lines). Re-export with " +
            "'gpg --export-secret-keys --armor <key id>' and store the full output in the " +
            "ORG_GRADLE_PROJECT_signingInMemoryKey secret — a partially copied key or one " +
            "stored with literal \\n escapes fails to parse"
    }
}

// vanniktech 插件走 Maven Central 发布：每个 KMP 目标的构件（含 KMP 消费方在
// commonMain 引用的根 Gradle 模块构件）一次 publishToMavenCentral 完成签名并上传
// Central Portal。发布工作流以环境变量映射的 gradle 属性提供凭据与 GPG 私钥
// （ORG_GRADLE_PROJECT_mavenCentralUsername/Password、
// ORG_GRADLE_PROJECT_signingInMemoryKey/KeyPassword）
mavenPublishing {
    // automaticRelease：上传后立即关闭并发布 staging 部署，绿色 tag 推送无需人工访问门户
    publishToMavenCentral(automaticRelease = true)

    if (signingKey != null) {
        signAllPublications()
    }

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
        developers {
            developer {
                id.set("kamiiroawase")
                name.set("紅葉")
                url.set("https://github.com/kamiiroawase")
            }
        }
        scm {
            url.set("https://github.com/kamiiroawase/nexttime")
            connection.set("scm:git:https://github.com/kamiiroawase/nexttime.git")
            developerConnection.set("scm:git:git@github.com:kamiiroawase/nexttime.git")
        }
    }
}

// ValueSource 方式读取 git tag，对配置缓存安全（重用缓存时也会重新求值）；类体不
// 引用脚本层成员，否则会成为非静态内部类
abstract class GitTagVersionSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String? {
        val process =
            try {
                // --exact-match：HEAD 不恰在匹配 tag 上时 describe 以非零退出——
                // 这正是发布形态的判定
                ProcessBuilder("git", "describe", "--tags", "--exact-match", "--match=v*")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
                return null
            }
        // 无 tag 时 git describe 非零退出且错误信息混入输出流——不能用作版本号
        if (process.waitFor() != 0) return null
        return process
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
            .removePrefix("v")
            .ifEmpty { null }
    }
}
