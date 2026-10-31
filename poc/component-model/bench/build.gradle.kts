import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

// core と同じく Java 17 で動かす（1.20.1 の条件）
java.toolchain.languageVersion = JavaLanguageVersion.of(17)
kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation(project(":cm-parser"))
    implementation(project(":cm-runtime"))
    implementation(project(":cm-wasi-p2"))
    implementation(project(":cm-component-corpus"))
    // 検証（wasm-tools validate）を使う場合の依存の大きさも測る
    implementation(project(":cm-wasm-tools"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    // #28 で決めた燃料の計測（コードの挿入）がコンポーネントの中で使えるか
    implementation(libs.endive.compiler)
}

// #28 の PoC の挿入処理（FuelInjector）をそのまま使う
val copyFuelInjector by tasks.registering(Copy::class) {
    from("../../fuel-metering/src/main/kotlin/pro/ikili/kibicomputers/poc/fuel/FuelInjector.kt")
    into(layout.buildDirectory.dir("fuel-src/pro/ikili/kibicomputers/poc/fuel"))
}
kotlin.sourceSets.main {
    kotlin.srcDir(copyFuelInjector.map { layout.buildDirectory.dir("fuel-src").get() })
}

application {
    mainClass = "pro.ikili.kibicomputers.poc.cm.BenchKt"
}

// ---- #261: p1 モジュール（lua.wasm）を、p1 を直接 / アダプタで p2 として動かして比べる ----
//   ./gradlew -p poc/component-model :bench:runP1Adapter

// Lua のベンチマークのスクリプトは #26 のものを使う（/bench/*.lua）
sourceSets.main {
    resources.srcDir("../../endive-bench/src/main/resources")
}

// 公式の wasi_snapshot_preview1 アダプタ（Wasmtime のリリースに付いているもの）
val adapterVersion = "49.0.4"
val adapterSha256 = "9d0499d48b19675bcd85e693e44157f406059fe69676ecca13d28ab89df65df8"
val adapterFile = layout.buildDirectory.file("adapter/wasi_snapshot_preview1.command-$adapterVersion.wasm")
val downloadAdapter = tasks.register("downloadAdapter") {
    val out = adapterFile
    outputs.file(out)
    doLast {
        val url = URI(
            "https://github.com/bytecodealliance/wasmtime/releases/download/v$adapterVersion/wasi_snapshot_preview1.command.wasm",
        ).toURL()
        val bytes = url.openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == adapterSha256) { "アダプタの SHA-256 が違う: $actual" }
        out.get().asFile.apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}

// lua.wasm（#91）は本体のビルド（:core:compileLuaWasm）で作る
val repoRoot = rootDir.resolve("../..").canonicalFile
val luaWasmFile = repoRoot.resolve("core/build/generated/wasm/lua/kibicomputers/wasm/lua.wasm")
val buildLuaWasm = tasks.register<Exec>("buildLuaWasm") {
    workingDir = repoRoot
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    commandLine(if (windows) listOf("cmd", "/c", repoRoot.resolve("gradlew.bat").path) else listOf(repoRoot.resolve("gradlew").path))
    args(":core:compileLuaWasm", "-q")
}

tasks.register<JavaExec>("runP1Adapter") {
    dependsOn(downloadAdapter, buildLuaWasm)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "pro.ikili.kibicomputers.poc.cm.P1AdapterKt"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    systemProperty("kibi.luaWasm", luaWasmFile.path)
    systemProperty("kibi.adapter", adapterFile.get().asFile.path)
    jvmArgs("-Xss8m")
}
