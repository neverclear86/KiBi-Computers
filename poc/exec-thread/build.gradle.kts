import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// #27 WASM を実行するスレッドの方式の比較（PoC）。MOD の jar には入れない。
//
// `./gradlew :poc:exec-thread:run` で測定結果を表で出す（`build/bench/results.md` にも書く）。
// `-Pbench.args=quick` で回数を減らす。
// 比べる方式: A（メインスレッドの tick の中で、燃料の分だけ実行）/ B（別スレッドで実行し、ワールド操作だけメインスレッドに渡す）
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
    mainClass = "pro.ikili.kibicomputers.poc.execthread.MainKt"
}

// 負荷の WASM（WASI なし）。燃料とワールド操作はホストの import
kibiWasm.module("work") {
    // 測ったときと同じ Zig の既定のスタック（16 MiB）。kibicomputers.wasm の既定（#334）を使わない
    stackSize = 16 * 1024 * 1024
    sources.from("src/main/wasm/work.c")
    target = "wasm32-freestanding"
    compilerArgs.addAll(
        "-O2",
        "-s",
        "-nostdlib",
        "-Wl,--no-entry",
        "-Wl,--export=burn",
        "-Wl,--export=run_forever",
        "-Wl,--export=world_forever",
        "-Wl,--export=recurse",
    )
}

dependencies {
    implementation(libs.endive.runtime)
    implementation(libs.endive.wasm)
    implementation(libs.endive.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// 測定は MC 1.21.1 と同じ Java 21 で行う（#26 で、Java 17 と 21 の差は小さいと分かっている）。
// MC のサーバーに近い条件（G1、ヒープは固定）
tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}
