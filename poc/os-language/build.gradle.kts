import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// #32 標準 OS の実装言語の比較（Zig / MoonBit）の PoC。MOD の jar には入れない。
//
// 同じ振る舞いのプログラム（echo・cat・ls・シェルの読み取りループ・kibi の import）を Zig と MoonBit で書き、
// 大きさ・インスタンス生成・燃料・速度、カーネル（待機点への変換、書き換えた RAM）の上での振る舞いを比べる。
// `./gradlew :poc:os-language:run` で測定結果を表で出す（`build/bench/results.md` にも書く）。テストも同じ表を標準出力に出す。
//
// MoonBit は、Gradle のプロパティ `kibi.moonbit=true` か CI のときだけビルドする（kibiWasm.moonbit。#32 のオーナーの回答）。
// ビルドしないときは Zig だけを測る。
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    id("kibicomputers.wasm")
}

evaluationDependsOn(":core")

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

// カーネル（Machine.exec など）は core の内部の型なので、core を friend にしてコンパイルする
val coreClasses = project(":core").layout.buildDirectory.dir("classes/kotlin/main")
val coreJar = project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        freeCompilerArgs.add(coreClasses.zip(coreJar) { dir, jar -> "-Xfriend-paths=${dir.asFile.absolutePath},${jar.asFile.absolutePath}" })
    }
}

application {
    mainClass = "pro.ikili.kibicomputers.poc.oslanguage.MainKt"
}

val programs = listOf("echo", "cat", "ls", "shell", "kmem")

kibiWasm {
    for (name in programs) {
        zigModule("$name-zig", resourceDir = "kibicomputers/poc/os-language") {
            root = layout.projectDirectory.file("src/main/zig/$name.zig")
            sources.from("src/main/zig/sys.zig")
        }
    }
    moonbit("os-language") {
        moduleDir = layout.projectDirectory.dir("src/main/moonbit")
        packages.putAll(programs.associate { "cmd/$it" to "kibicomputers/poc/os-language/$it-moonbit.wasm" })
        // 同梱の Binaryen（moon-wasm-opt）を通した版も比べる（引数のあるブロックを外せるか、大きさ・速さが変わるか）
        wasmOptArgs.addAll("-all", "-O2")
    }
}

// 手元では MoonBit をビルドしないので、CI がビルドした WASM（成果物 os-language-moonbit-wasm）を
// `-Pkibi.moonbitWasmDir=<展開したディレクトリの os-language>` で読ませて測れるようにする
providers.gradleProperty("kibi.moonbitWasmDir").orNull?.let { dir ->
    sourceSets.main { resources.srcDir(dir) }
}

dependencies {
    implementation(project(":core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // 測定の表を CI のログで読めるようにする
    testLogging {
        showStandardStreams = true
    }
    // MoonBit をビルドした（kibi.moonbit か CI）なら、MoonBit のプログラムがないことを失敗にする
    systemProperty("kibi.moonbit.required", kibiWasm.moonbitEnabled.toString())
}

tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    jvmArgs("-Xms1g", "-Xmx1g", "-XX:+UseG1GC")
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}
