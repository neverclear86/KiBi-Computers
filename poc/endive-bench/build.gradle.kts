import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import pro.ikili.kibicomputers.buildlogic.ZigCcWasm

// #26 Endive の基本性能の測定（PoC）。MOD の jar には入れない。
//
// `./gradlew :poc:endive-bench:runJava17`（`runJava21`・`runJava25` も同じ）で測定結果を表で出す。
// `./gradlew :poc:endive-bench:benchAll` は 3 つを続けて実行する。
// 比べる実行方式: インタプリタ / ランタイムコンパイラ / ビルド時 AOT / Redline（ネイティブ、実験的）
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
    mainClass = "pro.ikili.kibicomputers.poc.endivebench.MainKt"
}

// CPU ベンチマーク（WASI なし）
val benchWasm = kibiWasm.module("bench") {
    sources.from("src/main/wasm/bench.c")
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
val luaWasm = project(":core").tasks.named<ZigCcWasm>("compileLuaWasm")

fun ZigCcWasm.wasmFile(): Provider<RegularFile> = outputDir.file(outputPath)

// ビルド時 AOT の生成器（Endive の build-time compiler と Redline のビルド時コンパイラ）
val aotgen: SourceSet by sourceSets.creating
val aotgenImplementation by configurations.getting

dependencies {
    implementation(project(":core"))
    implementation(libs.endive.wasi)
    implementation(libs.endive.compiler)
    // Redline（ネイティブ）。Java 17 / 21 では jffi で呼ぶ。Java 25 では Panama（FFM）の runner も足す（下の runJava25）
    implementation(libs.endive.redline.runner.jffi)
    implementation(libs.jffi)
    implementation(variantOf(libs.jffi) { classifier("native") })

    aotgenImplementation(libs.endive.build.time.compiler)
    aotgenImplementation(libs.endive.redline.build.time.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Java 25 以上でだけ使える Redline の Panama runner
val panamaRuntime by configurations.creating
dependencies {
    panamaRuntime(libs.endive.redline.runner)
}

/**
 * WASM をビルド時に JVM のバイトコード（と、[redlineTargets] があればネイティブコード）に変換するタスクを登録し、
 * 生成物を main のソース・クラスパスに加える。
 */
fun aot(taskName: String, wasm: Provider<RegularFile>, className: String, redlineTargets: List<String> = emptyList()) {
    val out = layout.buildDirectory.dir("generated/aot/$taskName")
    val task = tasks.register<JavaExec>(taskName) {
        group = "build"
        description = "Compiles $className from WASM at build time."
        classpath = aotgen.runtimeClasspath
        mainClass = "pro.ikili.kibicomputers.poc.endivebench.aotgen.AotGenKt"
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
        inputs.file(wasm)
        inputs.property("redlineTargets", redlineTargets)
        outputs.dir(out)
        doFirst { delete(out) }
        argumentProviders.add(
            CommandLineArgumentProvider {
                val dir = out.get().asFile
                listOf(
                    wasm.get().asFile.absolutePath,
                    className,
                    dir.resolve("classes").absolutePath,
                    dir.resolve("sources").absolutePath,
                    dir.resolve("wasm").absolutePath,
                    redlineTargets.joinToString(","),
                )
            },
        )
    }
    sourceSets.main {
        java.srcDir(task.map { out.get().dir("sources") })
        resources.srcDir(task.map { out.get().dir("wasm") })
    }
    // 生成済みの Machine のクラスとネイティブコード（javac が参照するのでコンパイル時のクラスパスにも入れる）
    dependencies { implementation(files(out.map { it.dir("classes") }).builtBy(task)) }
}

aot("aotBench", benchWasm.flatMap { it.wasmFile() }, "pro.ikili.kibicomputers.poc.endivebench.aot.Bench", listOf("x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-apple-darwin"))
aot("aotLua", luaWasm.flatMap { it.wasmFile() }, "pro.ikili.kibicomputers.poc.endivebench.aot.Lua")

// 生成された Java の中のコメントなど
tasks.compileJava { options.encoding = "UTF-8" }

tasks.test {
    useJUnitPlatform()
}

/** 指定した Java のバージョンで測定を実行するタスク。 */
fun benchTask(javaVersion: Int, extraClasspath: FileCollection? = null) = tasks.register<JavaExec>("runJava$javaVersion") {
    group = "application"
    description = "Runs the Endive benchmarks on Java $javaVersion."
    classpath = sourceSets.main.get().runtimeClasspath + (extraClasspath ?: files())
    mainClass = application.mainClass
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
    // MC のサーバーに近い条件（G1、ヒープは固定）。Redline の jffi が JNI を使うので警告を止める
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
    if (javaVersion >= 24) jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}

val runJava17 = benchTask(17)
val runJava21 = benchTask(21)
val runJava25 = benchTask(25, panamaRuntime)

tasks.register("benchAll") {
    group = "application"
    description = "Runs the Endive benchmarks on Java 17, 21 and 25."
    dependsOn(runJava17, runJava21, runJava25)
}
runJava21 { mustRunAfter(runJava17) }
runJava25 { mustRunAfter(runJava21) }
