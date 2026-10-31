import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Minecraft に依存しないコア。1.20.1（Java 17）でも動かすため Java 17 をターゲットにする。
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("kibicomputers.wasm")
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
    api(libs.endive.runtime)
    api(libs.endive.wasm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// テスト用の WASM（#9）。zig cc でビルドし、テストのリソースに入れる
kibiWasm {
    // WASI を使わない最小のモジュール。ランタイムで実行して結果を確かめる
    module("fib", sourceSet = "test") {
        sources.from("src/test/wasm/fib.c")
        target = "wasm32-freestanding"
        compilerArgs.addAll("-O2", "-s", "-nostdlib", "-Wl,--no-entry", "-Wl,--export=fib")
    }
    // wasi-libc をリンクする WASI p1 のプログラム（lua.wasm などと同じビルドの形）
    module("hello-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/hello_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
}
