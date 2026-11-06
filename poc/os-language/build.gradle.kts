import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// #32 標準 OS の実装言語の比較（Zig / MoonBit）の PoC。MOD の jar には入れない。
//
// 同じ振る舞いのプログラム（echo・cat・ls・シェルの読み取りループ・kibi の import）を Zig と MoonBit で書き、
// 大きさ・インスタンス生成・燃料・速度、カーネル（待機点への変換、書き換えた RAM）の上での振る舞いを比べる。
// `./gradlew :poc:os-language:run` で測定結果を表で出す（`build/bench/results.md` にも書く）。テストも同じ表を標準出力に出す。
//
// MoonBit は、Gradle のプロパティ `kibi.moonbit=true` のときだけビルドする（kibiWasm.moonbit）。#32 で標準 OS は Zig に決めたので、CI ではビルドしない。
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

// #32 の追加調査（燃料の内訳と原因）: MoonBit の書き方・最適化の比較。kibi.moonbit のときだけ動かし、失敗してもビルドは止めない。
//
// - 名前つき（`moon build --release --no-strip`）の元のプログラムと、`src/experiments/moonbit/cmd/<名前>` の書き換え版を、
//   1つずつ元のモジュールに足してビルドする（書き換え版がコンパイルできなくても、ほかには響かない）
// - それぞれに moon-wasm-opt を、引数を変えてかける（`-g` で名前を残す）
// - 出力は build/generated/moonbit/experiments/（CI の成果物 os-language-moonbit-wasm に入る）。リソースには入れない。
//   手元では `./gradlew :poc:os-language:run "-Pbench.args=profile <展開したディレクトリの experiments>"` で内訳を出す
val moonbitExperiments = tasks.register("moonbitExperiments") {
    group = "verification"
    description = "Builds MoonBit variants for the fuel investigation of #32 (CI only; never fails the build)."
    val enabled = kibiWasm.moonbitEnabled
    onlyIf("kibi.moonbit のときだけ") { enabled }
    dependsOn("compileOsLanguageMoonBit")
    val toolchains = gradle.gradleUserHomeDir.resolve("kibicomputers/moonbit")
    val moduleDir = layout.projectDirectory.dir("src/main/moonbit").asFile
    val experimentsDir = layout.projectDirectory.dir("src/experiments/moonbit/cmd").asFile
    val out = layout.buildDirectory.dir("generated/moonbit/experiments").get().asFile
    val work = layout.buildDirectory.dir("tmp/moonbitExperiments").get().asFile
    val baseline = programs
    inputs.dir(experimentsDir)
    inputs.dir(moduleDir)
    outputs.dir(out)
    doLast {
        val log = StringBuilder()
        val home = toolchains.listFiles()?.firstOrNull { it.isDirectory && it.resolve("bin/moon").isFile }
        if (home == null) {
            logger.warn("moonbitExperiments: MoonBit のツールチェーンが見つからない（$toolchains）")
            return@doLast
        }
        val bin = home.resolve("bin")
        fun exec(dir: File, vararg cmd: String): Int {
            val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
            pb.environment()["MOON_HOME"] = home.absolutePath
            pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: "")
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            log.appendLine("$ ${cmd.joinToString(" ")}  (exit $code)").appendLine(text.take(20_000))
            return code
        }
        out.deleteRecursively()
        out.mkdirs()
        work.deleteRecursively()
        work.mkdirs()
        // moonc の最適化の引数を調べる
        exec(work, bin.resolve("moonc").absolutePath, "build-package", "-h")
        exec(work, bin.resolve("moonc").absolutePath, "link-core", "-h")
        exec(work, bin.resolve("moon").absolutePath, "build", "-h")
        val opts = linkedMapOf(
            "-O2" to listOf("-all", "-O2", "-g"),
            "-O3" to listOf("-all", "-O3", "-g"),
            "-O4" to listOf("-all", "-O4", "-g"),
            // 参考: トラップが起きないと仮定させる（範囲検査の unreachable を消せる。安全性は変わる）
            "-tnh" to listOf("-all", "-O3", "-g", "-tnh"),
        )
        val variants = listOf("") + (experimentsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList())
        for (variant in variants) {
            try {
                val w = work.resolve(variant.ifEmpty { "base" })
                moduleDir.copyRecursively(w, overwrite = true)
                if (variant.isNotEmpty()) experimentsDir.resolve(variant).copyRecursively(w.resolve("cmd/$variant"), overwrite = true)
                val target = work.resolve("${variant.ifEmpty { "base" }}-target")
                if (exec(w, bin.resolve("moon").absolutePath, "build", "--target", "wasm", "--release", "--no-strip", "--target-dir", target.absolutePath) != 0) continue
                val built = target.walkTopDown().filter { it.isFile && it.extension == "wasm" }.toList()
                // 名前を落とした普通のリリースのビルド（--no-strip は名前のほかにコードも少し変わるので、燃料はこちらでも測る）
                val stripTarget = work.resolve("${variant.ifEmpty { "base" }}-strip-target")
                val stripped = if (exec(w, bin.resolve("moon").absolutePath, "build", "--target", "wasm", "--release", "--target-dir", stripTarget.absolutePath) == 0) {
                    stripTarget.walkTopDown().filter { it.isFile && it.extension == "wasm" }.toList()
                } else {
                    emptyList()
                }
                for (p in if (variant.isEmpty()) baseline else listOf(variant)) {
                    val wasm = built.firstOrNull { it.invariantSeparatorsPath.endsWith("/cmd/$p/$p.wasm") && "/release/" in it.invariantSeparatorsPath }
                        ?: built.firstOrNull { it.invariantSeparatorsPath.endsWith("/cmd/$p/$p.wasm") }
                    if (wasm == null) {
                        log.appendLine("!! $p の .wasm がない: ${built.map { it.relativeTo(target).invariantSeparatorsPath }}")
                        continue
                    }
                    wasm.copyTo(out.resolve("$p.wasm"), overwrite = true)
                    stripped.firstOrNull { it.invariantSeparatorsPath.endsWith("/cmd/$p/$p.wasm") }?.let { s ->
                        s.copyTo(out.resolve("$p-strip.wasm"), overwrite = true)
                        val dst = out.resolve("$p-strip-O3.wasm")
                        if (exec(w, bin.resolve("moon-wasm-opt").absolutePath, s.absolutePath, "-all", "-O3", "-o", dst.absolutePath) != 0) dst.delete()
                    }
                    for ((suffix, args) in opts) {
                        val dst = out.resolve("$p$suffix.wasm")
                        if (exec(w, bin.resolve("moon-wasm-opt").absolutePath, wasm.absolutePath, *args.toTypedArray(), "-o", dst.absolutePath) != 0) dst.delete()
                    }
                }
            } catch (e: Exception) {
                log.appendLine("!! $variant: $e")
            }
        }
        out.resolve("log.txt").writeText(log.toString())
        println(log)
    }
}
tasks.named("build") { dependsOn(moonbitExperiments) }

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
    // MoonBit をビルドした（kibi.moonbit）なら、MoonBit のプログラムがないことを失敗にする
    systemProperty("kibi.moonbit.required", kibiWasm.moonbitEnabled.toString())
}

tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
    jvmArgs("-Xms1g", "-Xmx1g", "-XX:+UseG1GC")
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}
