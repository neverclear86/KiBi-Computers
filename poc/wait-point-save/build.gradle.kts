import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import pro.ikili.kibicomputers.buildlogic.SourceArchive
import pro.ikili.kibicomputers.buildlogic.ZigCcWasm

// #30 待機点で保存する方式の検証（PoC）。MOD の jar には入れない。
//
// `./gradlew :poc:wait-point-save:run` で測定結果を表で出す（`build/bench/results.md` にも書く）。
// `-Pbench.args=quick` で 50 MB のシナリオを省き、回数を減らす。
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    id("kibicomputers.wasm")
}

// lua.wasm（#91）と Lua のソースは core のビルドを使う
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
    mainClass = "pro.ikili.kibicomputers.poc.waitpointsave.MainKt"
}

// C だけの最小の reactor（例外処理なし。いちばん簡単な場合の基準）
kibiWasm.module("waiter") {
    sources.from("src/main/wasm/waiter.c")
    compilerArgs.addAll("-O2", "-s", "-mexec-model=reactor", "-Wl,--export=poc_step")
}

// Lua 5.4 のライブラリとリンクする reactor（検証用。本番の lua.wasm の代わりではない）。
// フラグは core の kibiWasm.module("lua") と同じ値にしている。core 側を変えたら、ここも合わせる
val luaSource = project(":core").tasks.named<SourceArchive>("downloadLuaSource")
kibiWasm.module("lua_host") {
    sources.from(luaSource.map { task -> task.outputDir.dir("src").get().asFileTree.matching { include("*.c"); exclude("lua.c", "luac.c") } })
    sources.from(rootProject.file("core/src/main/wasm/lua/kibi_wasi.c"))
    sources.from("src/main/wasm/lua_host.c")
    includeDirs.from(rootProject.file("core/src/main/wasm/lua"))
    includeDirs.from(luaSource.map { it.outputDir.dir("src") })
    compilerArgs.addAll(
        // ここから core と同じ
        "-O2",
        "-s",
        "-include",
        "kibi_wasi.h",
        "-Wno-deprecated-declarations",
        "-mexception-handling",
        "-mllvm",
        "-wasm-enable-sjlj",
        "-mllvm",
        "-wasm-use-legacy-eh=false",
        "-D_WASI_EMULATED_SIGNAL",
        "-D_WASI_EMULATED_PROCESS_CLOCKS",
        "-lwasi-emulated-signal",
        "-lwasi-emulated-process-clocks",
        "-Wl,-z,stack-size=1048576",
        // ここまで core と同じ。以下は reactor 用
        "-mexec-model=reactor",
        "-Wl,--export=poc_alloc,--export=poc_load,--export=poc_resume",
    )
}

// #91 の lua.wasm（コマンド形式）。core のリソースに入っているものをそのまま使う
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

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// 測定は Java 17（ADR 0026 で、17 と 21 の差は小さいと分かっている）。MC のサーバーに近い条件（G1、ヒープは固定）。
// 50 MB のシナリオは線形メモリが数百 MB になり、そのコピーを何度も持つので、ヒープは 8 GB にする
tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    jvmArgs("-Xms8g", "-Xmx8g", "-XX:+UseG1GC")
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}
