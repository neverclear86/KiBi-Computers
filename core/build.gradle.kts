import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties

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

    // lua.wasm などの WASI p1 のプログラムをテストで動かすため（実行時のカーネルは自前の WASI を使う）
    testImplementation(libs.endive.wasi)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Lua の REPL を何台で測るか（#334。`-Pkibi.luaReplMachines=1000` で 1,000 台を測る）
    providers.gradleProperty("kibi.luaReplMachines").orNull?.let {
        systemProperty("kibi.luaReplMachines", it)
        maxHeapSize = "4g"
    }
}

// テスト用の WASM（#9）。zig cc でビルドし、テストのリソースに入れる
kibiWasm {
    // WASI を使わない最小のモジュール。ランタイムで実行して結果を確かめる
    module("fib", sourceSet = "test") {
        sources.from("src/test/wasm/fib.c")
        target = "wasm32-freestanding"
        compilerArgs.addAll("-O2", "-s", "-nostdlib", "-Wl,--no-entry", "-Wl,--export=fib")
    }
    // 燃料の計測（#38）を確かめるモジュール。一括メモリ命令と末尾呼び出しを出す
    module("fuel", sourceSet = "test") {
        sources.from("src/test/wasm/fuel.c")
        target = "wasm32-freestanding"
        compilerArgs.addAll(
            "-O2",
            "-s",
            "-nostdlib",
            "-mbulk-memory",
            "-mtail-call",
            "-Wl,--no-entry",
            "-Wl,--export=fib",
            "-Wl,--export=forever",
            "-Wl,--export=fill",
            "-Wl,--export=copy",
            "-Wl,--export=grow",
            "-Wl,--export=is_even",
        )
    }
    // wasi-libc をリンクする WASI p1 のプログラム（lua.wasm などと同じビルドの形）
    module("hello-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/hello_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // WASI p1 の fd_* をカーネルの上で確かめるプログラム（#70）
    module("fd-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/fd_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // WASI p1 の path_* と preopen を確かめるプログラム（#71）
    module("path-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/path_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // WASI p1 の args・environ・clock・random・sched_yield・proc_exit を確かめるプログラム（#69）
    module("basic-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/basic_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // WASI p1 の poll_oneoff（poll・nanosleep・clock_nanosleep）を確かめるプログラム（#72）
    module("poll-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/poll_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // 待機点への変換（#329）を確かめる、fgets で待つ CLI。深い再帰の底で待つ版のため、C のスタックを大きくする
    module("prompt-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/prompt_wasi.c")
        compilerArgs.addAll("-O2", "-s")
        stackSize = 4 * 1024 * 1024
    }
}

// Lua 5.4 を WASI p1 向けにビルドした lua.wasm（#91）。core のリソース `/kibicomputers/wasm/lua.wasm` に入れる
val luaProps = Properties().apply {
    rootProject.file("gradle/lua.properties").reader(Charsets.UTF_8).use(::load)
}
val luaVersion: String = luaProps.getProperty("version")
val luaSource = kibiWasm.sourceArchive("lua") {
    url = "https://www.lua.org/ftp/lua-$luaVersion.tar.gz"
    sha256 = luaProps.getProperty("sha256")
}
val compileLua = kibiWasm.module("lua") {
    // 標準のインタープリター（lua.c）とライブラリ。luac.c（コンパイラー）は入れない
    sources.from(luaSource.map { task -> task.outputDir.dir("src").get().asFileTree.matching { include("*.c"); exclude("luac.c") } })
    sources.from("src/main/wasm/lua/kibi_wasi.c")
    includeDirs.from("src/main/wasm/lua")
    compilerArgs.addAll(
        "-O2",
        "-s",
        // os.execute などの扱い（ソースは書き換えない）
        "-include",
        "kibi_wasi.h",
        // liolib.c の tmpfile は kibi_wasi.c で補うので、wasi-libc の「WASI にない」の警告を出さない
        "-Wno-deprecated-declarations",
        // Lua のエラー処理（setjmp/longjmp）を WASM の例外処理（exnref）で実装する。Endive は exnref に対応している
        "-mexception-handling",
        "-mllvm",
        "-wasm-enable-sjlj",
        "-mllvm",
        "-wasm-use-legacy-eh=false",
        // lua.c は signal、os.clock は clock を使う。wasi-libc のエミュレーションを使う
        "-D_WASI_EMULATED_SIGNAL",
        "-D_WASI_EMULATED_PROCESS_CLOCKS",
        "-lwasi-emulated-signal",
        "-lwasi-emulated-process-clocks",
    )
    // C のスタック（仮の値。#334）。Lua の C 呼び出しの深さ（LUAI_MAXCCALLS = 200）に足りる大きさにする
    stackSize = 256 * 1024
}

// テスト用の同梱イメージ（#55）。src/test/images/sample/ を zip にして、テストのリソース `/kibicomputers/images/sample.zip` に入れる
val zipTestImage = tasks.register<Zip>("zipTestImage") {
    from("src/test/images/sample")
    archiveFileName = "sample.zip"
    destinationDirectory = layout.buildDirectory.dir("generated/images/test/kibicomputers/images")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
sourceSets.test {
    resources.srcDir(files(layout.buildDirectory.dir("generated/images/test")) { builtBy(zipTestImage) })
}

// テスト用コンピューターの同梱イメージ testboot（#307 の仮の結線。標準OS #98 ができたら置き換える）。
// src/main/images/testboot/（`/sbin/init` は Lua のスクリプト）に、lua.wasm を `/bin/lua` として足して zip にし、
// core のリソース `/kibicomputers/images/testboot.zip` にする（#315）
val zipTestbootImage = tasks.register<Zip>("zipTestbootImage") {
    from("src/main/images/testboot")
    from(compileLua.flatMap { it.outputDir }) {
        include("kibicomputers/wasm/lua.wasm")
        eachFile { path = "bin/lua" }
        includeEmptyDirs = false
    }
    archiveFileName = "testboot.zip"
    destinationDirectory = layout.buildDirectory.dir("generated/images/main/kibicomputers/images")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/images/main")) { builtBy(zipTestbootImage) })
}
