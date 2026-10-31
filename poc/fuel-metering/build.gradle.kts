import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// #28 燃料計測の方式の比較（PoC）。MOD の jar には入れない。
//
// `./gradlew :poc:fuel-metering:run` で測定結果を表で出す（`build/bench/results.md` にも書く）。
// `-Pbench.args=quick` で回数を減らす。
// 比べる方式: A（読み込み時に WASM へ計測コードを挿入する）/ B（ランタイム側で命令数を数える）
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    id("kibicomputers.wasm")
}

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

application {
    mainClass = "pro.ikili.kibicomputers.poc.fuel.MainKt"
}

// CPU ベンチマークは #26 と同じもの（WASI なし）
kibiWasm.module("bench") {
    sources.from("../endive-bench/src/main/wasm/bench.c")
    target = "wasm32-freestanding"
    compilerArgs.addAll(
        "-O2",
        "-s",
        "-nostdlib",
        "-Wl,--no-entry",
        "-Wl,--export=fib",
        "-Wl,--export=sieve",
        "-Wl,--export=matmul",
        "-Wl,--export=crc32",
        "-Wl,--export=spin",
    )
}

// Lua のスクリプトも #26 と同じもの
sourceSets.main { resources.srcDir("../endive-bench/src/main/resources") }

dependencies {
    // lua.wasm（#91）は core のリソース
    implementation(project(":core"))
    implementation(libs.endive.runtime)
    implementation(libs.endive.wasm)
    implementation(libs.endive.wasi)
    implementation(libs.endive.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// 測定は MC 1.21.1 と同じ Java 21 で行う（#26 で、Java 17 と 21 の差は小さいと分かっている）。
// MC のサーバーに近い条件（G1、ヒープは固定）
tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
    // 確かめ用に JVM の引数を足す（例: `-Pbench.jvmArgs=-XX:-DontCompileHugeMethods`）
    project.findProperty("bench.jvmArgs")?.toString()?.let { jvmArgs(it.split(' ')) }
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}
