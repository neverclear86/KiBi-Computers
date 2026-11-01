import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import pro.ikili.kibicomputers.buildlogic.ZigCcWasm

// #316 入力待ちの p1 プログラムにスレッドを持たせない方式の比較（PoC）。MOD の jar には入れない。
//
// `./gradlew :poc:threadless-wait:run` で測定結果を表で出す（`build/bench/results.md` にも書く）。
// `-Pbench.args=quick` で台数と回数を減らす。
// 比べる方式: A（読み込み時の asyncify 相当の変換）/ B（仮想スレッド。スタックの切り替えは Endive が未対応）/
// C（ブロッキング型のまま、プラットフォームスレッドで待つ）
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    id("kibicomputers.wasm")
}

// lua.wasm（#91）は core のビルドを使う
evaluationDependsOn(":core")

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
    mainClass = "pro.ikili.kibicomputers.poc.threadlesswait.MainKt"
}

// 普通のコマンド形式の C の CLI（`_start` から動き、fgets で標準入力を待つ）
kibiWasm.module("prompt") {
    sources.from("src/main/wasm/prompt.c")
    // スタックは lua.wasm（core）と同じ 1 MiB（Zig の既定の 16 MiB だと 1,000 台で線形メモリが 16 GiB になる）
    compilerArgs.addAll("-O2", "-s", "-Wl,-z,stack-size=1048576")
}

// #91 の lua.wasm（コマンド形式。REPL は fd_read で待つ）。core のリソースに入っているものをそのまま使う
val luaWasm = project(":core").tasks.named<ZigCcWasm>("compileLuaWasm")
sourceSets.main { resources.srcDir(luaWasm) }

dependencies {
    implementation(libs.endive.runtime)
    implementation(libs.endive.wasm)
    implementation(libs.endive.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// 仮想スレッド（B）を確かめるため、テストは Java 21 で動かす（コードは Java 17 向けにコンパイルし、仮想スレッドはリフレクションで使う）
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
}

// 測定は MC 1.21.1 と同じ Java 21（B の仮想スレッドが要る）。MC のサーバーに近い条件（G1、ヒープは固定）。
// スレッドのスタックの使用量は NMT（Native Memory Tracking）で測る
tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    jvmArgs("-Xms4g", "-Xmx4g", "-XX:+UseG1GC", "-XX:NativeMemoryTracking=summary")
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}
