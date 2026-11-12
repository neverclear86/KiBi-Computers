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
    // プレイヤーの WASM はランタイムコンパイラで動かす（#29 の決定、#37）。ASM が要る（MC ではローダーが持つものを使う）
    implementation(libs.endive.compiler)
    // `pro.ikili.kibicomputers.api` 以外の public には `@ApiStatus.Internal` を付ける（#366）。
    // 注釈は実行時に要らない（Minecraft も同じものを持つ）。使う側の IDE が警告を出せるよう、コンパイル時には見せる
    compileOnlyApi(libs.jetbrains.annotations)

    // lua.wasm などの WASI p1 のプログラムをテストで動かすため（実行時のカーネルは自前の WASI を使う）
    testImplementation(libs.endive.wasi)
    // WIT（src/main/wit）を wasm-tools で解析するテスト（KibiWitParseTest。#75）。テストだけで使い、jarJar には入れない
    testImplementation(libs.endive.wasm.tools)
    testImplementation(libs.zerofs)
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
    // SIMD（v128）を含むモジュール（#424）。SIMD を使う関数だけをインタプリタで動かし、ほかはコンパイルする
    module("simd", sourceSet = "test") {
        sources.from("src/test/wasm/simd.c")
        target = "wasm32-freestanding"
        compilerArgs.addAll(
            "-O2",
            "-s",
            "-nostdlib",
            "-msimd128",
            "-Wl,--no-entry",
            "-Wl,--export=run",
            "-Wl,--export=sum",
            "-Wl,--export=fill_seq",
            "-Wl,--export=dot4",
            "-Wl,--export=fib",
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
    // WASI p1 の cwd を PWD から合わせる起動処理（#332）を確かめるプログラム
    module("cwd-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/cwd_wasi.c", "src/main/wasm/common/kibi_cwd.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // 待機点への変換（#329）を確かめる、fgets で待つ CLI。深い再帰の底で待つ版のため、C のスタックを大きくする
    module("prompt-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/prompt_wasi.c")
        compilerArgs.addAll("-O2", "-s")
        stackSize = 4 * 1024 * 1024
    }
    // 燃料切れの待ち点化（#339）を確かめる、計算を続ける CLI
    module("fuel-spin-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/fuel_spin_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // RAM を書き換えた 4 KiB の区画で数える（#407）ことと、メモリ不足の表示を確かめる CLI
    module("oom-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/oom_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:computer/memory（#408・#73）の p1 の import を確かめる CLI
    module("kibi-memory-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_memory_wasi.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:kernel/events（#73）の p1 の import と cabi_realloc の呼び出しを確かめる CLI
    module("kibi-events-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_events.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:kernel/timers・kibi:computer/clock（#426）の p1 の import を確かめる CLI
    module("kibi-timers-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_timers.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:io/fd・kibi:io/pipe（#75）の p1 の import を確かめる CLI
    module("kibi-io-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_io.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:process（#74）の p1 の import を確かめる CLI（親と子を兼ねる）
    module("kibi-process-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_process.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:computer/power（#306）の p1 の import を確かめる CLI
    module("kibi-power-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_power.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:io/tty（#427）の p1 の import を確かめる CLI
    module("kibi-tty-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_tty.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:fs/mounts（#56）の p1 の import を確かめる CLI
    module("kibi-fs-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_fs.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
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
    // 起動時に PWD（カーネルの cwd）へ chdir する（#332）
    sources.from("src/main/wasm/common/kibi_cwd.c")
    includeDirs.from("src/main/wasm/lua")
    // アロケーターの包みが kibi:memory（#408）を呼ぶ（#409）
    includeDirs.from("src/main/wasm/common")
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
// lua.wasm（#91 の compileLua の出力）をイメージの `/bin/lua` にする。testboot と標準 OS（#603）で同じものを使い、
// 中身を同じにして ProgramCache のコンパイルを1回に共有する（#37）
val luaBinary = copySpec {
    from(compileLua.flatMap { it.outputDir }) {
        include("kibicomputers/wasm/lua.wasm")
        eachFile { path = "bin/lua" }
        includeEmptyDirs = false
    }
}
val zipTestbootImage = tasks.register<Zip>("zipTestbootImage") {
    from("src/main/images/testboot")
    with(luaBinary)
    archiveFileName = "testboot.zip"
    destinationDirectory = layout.buildDirectory.dir("generated/images/main/kibicomputers/images")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/images/main")) { builtBy(zipTestbootImage) })
}

// 標準 OS（#98。言語は Zig。ADR 0032）。ソースはリポジトリの `os/`:
// - `os/lib/kibi/`: 共通ライブラリ（システムコールの包み）。プログラムは `@import("kibi")` で使う
// - `os/bin/<名前>.zig` → イメージの `/bin/<名前>`、`os/sbin/<名前>.zig` → `/sbin/<名前>`（ファイルを置けば足される）
// - `os/etc/` → イメージの `/etc/`（設定ファイル。そのまま入れる）
// - `os/test/<名前>.zig`: 共通ライブラリを確かめるプログラム。テストのリソース `/kibicomputers/wasm/os-<名前>.wasm` にする
// 全部を同梱イメージ os（core のリソース `/kibicomputers/images/os.zip`。起動ディスクの識別子は `image:os`）にまとめる。
// OS 入りのフロッピー（#97）はこのイメージを中身にする。ビルド時 AOT（#36）は、このイメージのプログラムを変換の対象に足す
val osDir = rootProject.layout.projectDirectory.dir("os")
val osLibrary = osDir.dir("lib/kibi")
val osLibrarySources = fileTree(osLibrary) { include("**/*.zig") }

// `kibi.events`（`cabi_realloc` を export する必要がある）を使うプログラム。使うものを足したら、ここにも足す
// （足し忘れると、読み込みで NOEXEC になる。`StandardOsRestoreTest` が検出する）
val osEventsPrograms = mapOf("bin" to setOf("sh", "nano"))

// `os/<dir>/` の `.zig` を1つずつ WASM にする（sourceSet が null ならリソースに入れない）
fun osPrograms(dir: String, sourceSet: String?, resourceDir: String) = osDir.dir(dir).asFile
    .listFiles { f -> f.isFile && f.name.endsWith(".zig") }.orEmpty()
    .sortedBy { it.name }
    .map { file ->
        val name = file.name.removeSuffix(".zig")
        name to kibiWasm.zigModule("os-$dir-$name", sourceSet = sourceSet, resourceDir = resourceDir) {
            root = file
            sources.from(osLibrarySources)
            // 大きなプログラムは `os/<dir>/<名前>/` に分けて置ける（`@import("<名前>/x.zig")`）
            sources.from(fileTree(file.resolveSibling(name)) { include("**/*.zig") })
            // シェルは構文の入れ子・関数の呼び出しで深く再帰するので、スタックを大きくする（触るまで RAM に数えない）
            if (dir == "bin" && name == "sh") stackSize = 256 * 1024
            // `kibi.events` を使うプログラムだけ、`cabi_realloc`（#87）を export する。Zig は WASM の実行ファイルで `_start` のほかを
            // export しないため。`-rdynamic` は可変グローバル `__stack_pointer` まで全プログラムに足すので使わない
            if (dir in osEventsPrograms.keys && name in osEventsPrograms.getValue(dir)) compilerArgs.add("--export=cabi_realloc")
            modules.put("kibi", osLibrary.file("kibi.zig").asFile)
            outputPath = "$name.wasm"
        }
    }

val osImageDirs = listOf("bin", "sbin")
val osImagePrograms = osImageDirs.associateWith { osPrograms(it, null, "") }
osPrograms("test", "test", "kibicomputers/wasm").forEach { (name, task) ->
    task.configure { outputPath = "kibicomputers/wasm/os-$name.wasm" }
}

// イメージに入れる空のディレクトリ（#461。HDD をマウントする `/home`、RAM のファイルシステムをマウントする `/tmp`）。
// Git は空のディレクトリを持てないので、ビルドで作る
val osImageSkeleton = tasks.register("osImageSkeleton") {
    val out = layout.buildDirectory.dir("generated/os-skeleton")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        for (name in listOf("home", "tmp")) dir.resolve(name).mkdirs()
    }
}

val zipOsImage = tasks.register<Zip>("zipOsImage") {
    group = "build"
    description = "Builds the standard OS image (os.zip) from the Zig programs under os/."
    for ((dir, programs) in osImagePrograms) {
        for ((name, task) in programs) {
            from(task.flatMap { it.outputDir }) {
                include("$name.wasm")
                eachFile { path = "$dir/$name" }
                includeEmptyDirs = false
            }
        }
    }
    // `/bin/lua` は C の Lua（compileLua。#91）の出力。Zig のプログラムではないので明示的に足す（#603。ADR 0098）
    with(luaBinary)
    // 設定ファイル（`os/etc/` → イメージの `/etc/`。#100 の `/etc/inittab` など）
    from(osDir.dir("etc")) { into("etc") }
    from(osImageSkeleton)
    archiveFileName = "os.zip"
    destinationDirectory = layout.buildDirectory.dir("generated/images/os/kibicomputers/images")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/images/os")) { builtBy(zipOsImage) })
}
