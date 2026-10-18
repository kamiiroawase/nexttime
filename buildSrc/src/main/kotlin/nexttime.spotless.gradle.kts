// 格式约定：ktlint 风格与 gradle.properties 的 kotlin.code.style=official 一致；
// spotlessCheck 自动挂在 check 上，./gradlew build 即含格式检查。buildSrc 的脚本
// 与源码只存在于根项目，两个额外模式在子项目匹配不到文件、自然无操作
plugins {
    id("com.diffplug.spotless")
}

spotless {
    kotlin {
        target("src/**/*.kt", "buildSrc/src/main/kotlin/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts", "buildSrc/*.gradle.kts", "buildSrc/src/main/kotlin/*.gradle.kts")
        ktlint()
    }
}
