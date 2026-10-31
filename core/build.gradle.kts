import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Minecraft に依存しないコア。1.20.1（Java 17）でも動かすため Java 17 をターゲットにする。
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
    // 公開APIは Java からも使うので、可視性と戻り値の型を明示させる
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // 実行時の Kotlin 標準ライブラリは Kotlin for Forge が提供する。
        // 1.20.1 の KFF 4.x は Kotlin 2.2 系を同梱しているので、それより新しい API を使わない。
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    api(libs.chicory.runtime)
    api(libs.chicory.wasm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
