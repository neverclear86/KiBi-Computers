import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// 開発クライアントを外から操作するための HTTP の口（Minecraft 非依存の部分）。
// 開発環境の実行時にだけ読み込み、リリースの jar には入れない。
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // 1.20.1（KFF 4.x、Kotlin 2.2 系）でも動かすため
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
