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
    // 公開 API のダンプ（api/<プロジェクト名>.api）と食い違ったら check（build）で落とす（#845）。
    // 公開 API を変えたら ./gradlew updateKotlinAbi で作り直してコミットする。
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            include {
                byNames.add("pro.ikili.kibicomputers.api.**")
            }
        }
    }
}

dependencies {
    api(libs.endive.runtime)
    api(libs.endive.wasm)
    // プレイヤーの WASM はランタイムコンパイラで動かす（#29 の決定、#37）。ASM が要る（MC ではローダーが持つものを使う）
    implementation(libs.endive.compiler)
    // コンポーネントの検証（#262。ADR 0262）。wasm-tools を WASM にしたものを、Endive の WASI p1 の上で動かす。
    // zerofs（仮想 FS）は validate では使わないので外す（テストでは下の testImplementation で足す）
    implementation(libs.endive.wasi)
    implementation(libs.endive.wasm.tools) { exclude(group = "io.roastedroot", module = "zerofs") }
    // wasi と wasm-tools が依存し、ComponentValidator も直接使う（ログを黙らせる SystemLogger）
    implementation(libs.endive.log)
    // `pro.ikili.kibicomputers.api` 以外の public には `@ApiStatus.Internal` を付ける（#366）。
    // 注釈は実行時に要らない（Minecraft も同じものを持つ）。使う側の IDE が警告を出せるよう、コンパイル時には見せる
    compileOnlyApi(libs.jetbrains.annotations)

    // WIT（src/main/wit）を wasm-tools で解析するテスト（KibiWitParseTest。#75）の仮想 FS
    testImplementation(libs.zerofs)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // 失敗の本文（assert の説明。StackExhaustionLockTest の「停止: ...」の様子など）を、コンソールと CI のログに出す（#868。
    // 既定は例外の型と行番号だけで、本文はテストレポートを開かないと見えない）
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // 負荷の下で 10 秒を超えてからそろった遅れ（StackExhaustionLockTest が「#868 遅れ」で始まる行を標準出力に出す）は、
    // 通った回もコンソールと CI のログに写す（#868。標準出力のすべては写さない）
    val testLogger = logger
    addTestOutputListener { descriptor, event ->
        if (event.message.startsWith("#868 遅れ")) testLogger.lifecycle("${descriptor.className} > ${descriptor.name}: ${event.message.trimEnd()}")
    }
    // システムコール仕様書（#79）が実装と WIT からずれていないことを確かめる SyscallDocTest が読むファイル。
    // 入力に宣言しないと、文書だけを変えた変更では :core:test が UP-TO-DATE かビルドキャッシュからの復元になり、テストが走らない
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/syscalls")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("syscallDocs")
    inputs.dir(layout.projectDirectory.dir("src/main/wit")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("witSources")
    // ctx.instantiate の入口の確かめを外した・縮めた結果を手元で測る（#884。`-Pkibi.test.instantiateGateFrames=<段数>`。ふつうは渡さない）
    providers.gradleProperty("kibi.test.instantiateGateFrames").orNull?.let { systemProperty("kibi.test.instantiateGateFrames", it) }
    // 前借りの間の燃料切れの処理の費用を測る（#858。`-Pkibi.overdraftBenchmark=true`。ふつうは動かさない）
    if (providers.gradleProperty("kibi.overdraftBenchmark").orNull == "true") {
        systemProperty("kibi.overdraftBenchmark", "true")
        for (name in listOf("machines", "inner", "rounds")) {
            providers.gradleProperty("kibi.overdraftBenchmark.$name").orNull?.let { systemProperty("kibi.overdraftBenchmark.$name", it) }
        }
    }
    // 待機点への変換の速さを測る（#338。`-Pkibi.asyncifyBenchmark=true`。ふつうは動かさない）
    if (providers.gradleProperty("kibi.asyncifyBenchmark").orNull == "true") {
        systemProperty("kibi.asyncifyBenchmark", "true")
        providers.gradleProperty("kibi.asyncifyBenchmark.rounds").orNull?.let { systemProperty("kibi.asyncifyBenchmark.rounds", it) }
    }
    // 変換したモジュールの燃料 1 あたりの CPU 時間を測る（#389。`-Pkibi.asyncifyFuelBenchmark=true`。ふつうは動かさない）
    if (providers.gradleProperty("kibi.asyncifyFuelBenchmark").orNull == "true") {
        systemProperty("kibi.asyncifyFuelBenchmark", "true")
        providers.gradleProperty("kibi.asyncifyFuelBenchmark.rounds").orNull?.let { systemProperty("kibi.asyncifyFuelBenchmark.rounds", it) }
    }
    // Lua の REPL を何台で測るか（#334。`-Pkibi.luaReplMachines=1000` で 1,000 台を測る）
    providers.gradleProperty("kibi.luaReplMachines").orNull?.let {
        systemProperty("kibi.luaReplMachines", it)
        maxHeapSize = "4g"
    }
}

// スタックの余裕の確かめ（StackHeadroom。#828・#858）のテストを、ふつうの :core:test と違う JVM で回す（-Pkibi.stackHeadroom=true のときだけ
// 作る。build・CI には入れない）。結果は ADR 0043 と StackHeadroom の KDoc に書く。
// - stackHeadroomColdTest: StackHeadroom.probe だけを JIT し、ほかはすべてインタプリタで動かす（確かめは小さいフレームで測り、守る道は
//   大きいフレームで動く、いちばん悪い組み合わせ）
// - stackHeadroomXintTest: 全部インタプリタ（比べるため。回数 1/4 で、全体が十数分。#903）
// - stackHeadroomJdk21Test: JDK 21（1.21.1 の本番の JDK。JDK 19 で ReentrantLock の中身（AQS）が書き直された）
if (providers.gradleProperty("kibi.stackHeadroom").orNull == "true") {
    val headroomTests = listOf(
        "StackExhaustionLockTest",
        "ComponentStackExhaustionLockTest",
        "P1StackExhaustionLockTest",
        "P1DiskCloseStackExhaustionTest",
        "TmpFileSystemStackExhaustionKernelTest",
        "InstantiateStackExhaustionTest",
        "InstantiateStackExhaustionKernelTest",
    )
    fun registerHeadroomTest(name: String, java: Int, args: List<String>) = tasks.register<Test>(name) {
        group = "verification"
        description = "StackHeadroom の確かめを、JDK $java・$args で回す（#858）"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
        filter {
            headroomTests.forEach { includeTestsMatching("pro.ikili.kibicomputers.core.kernel.$it") }
            includeTestsMatching("pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystemStackExhaustionTest")
            includeTestsMatching("pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystemStackExhaustionTest")
            includeTestsMatching("pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystemTreeStackExhaustionTest")
        }
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(java) }
        jvmArgs(args)
        // インタプリタでは遅いので、1 本の時間の上限を延ばす（`-Pkibi.stackHeadroom.timeoutSeconds=<秒>` で変えられる。#903）
        systemProperty("kibi.stackHeadroom.timeoutSeconds", providers.gradleProperty("kibi.stackHeadroom.timeoutSeconds").orNull ?: "1800")
        // インタプリタの道は 1 回が遅いので、回数を減らす（尽きる位置は、詰め物の段数を変えてずらすのは同じ）。
        // `-Pkibi.stackHeadroom.roundsScale=<割合>` で、どの JVM でも上書きできる（#903。1 にすると、各テストの回数が全部に戻る。ディスクの掃引の幅は、割合では縮めない。割合が効くのは `WARMUP` と `refuseOpen` の `ROUNDS` など、回す回数だけ）
        val roundsScale = providers.gradleProperty("kibi.stackHeadroom.roundsScale").orNull ?: if (args.isNotEmpty()) "0.25" else null
        roundsScale?.let { systemProperty("kibi.stackHeadroom.roundsScale", it) }
        testLogging.showStandardStreams = true
        outputs.upToDateWhen { false }
    }
    registerHeadroomTest(
        "stackHeadroomColdTest",
        17,
        listOf(
            "-XX:CompileCommand=quiet",
            "-XX:CompileCommand=compileonly,pro.ikili.kibicomputers.core.kernel.process.StackHeadroom::probe",
            "-Xbatch",
        ),
    )
    registerHeadroomTest("stackHeadroomXintTest", 17, listOf("-Xint"))
    registerHeadroomTest("stackHeadroomJdk21Test", 21, emptyList())
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
    // /dev/device/<address> の fd の種類と isatty を確かめるプログラム（#865）
    module("dev-device-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/dev_device_wasi.c")
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
    // スタックを使い切るまで再帰する CLI（#858。待機点型の燃料切れの道を、尽きる位置をずらして通す）
    module("stack-deep-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/stack_deep_wasi.c")
        // 線形メモリのスタック（volatile の局所変数）が、Java のスタックより先に尽きないように大きくする
        compilerArgs.addAll("-O2", "-s", "-Wl,-z,stack-size=4194304")
    }
    // RAM を書き換えた 4 KiB の区画で数える（#407）ことと、メモリ不足の表示を確かめる CLI
    module("oom-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/oom_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // Lua のアロケーターの包みが写した malloc（BrkAllocator）の区画の計算（kibi_brk.h。#784）が、実際の malloc と合うかを確かめる CLI
    module("brk-slots-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/brk_slots_wasi.c")
        includeDirs.from("src/main/wasm/lua")
        compilerArgs.addAll("-O2", "-s")
    }
    // Lua のアロケーターの包み（kibi_wasi.c）を Lua を通さずに呼び、決まった道を通す CLI（#802）
    module("lua-alloc-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/lua_alloc_wasi.c", "src/main/wasm/lua/kibi_wasi.c")
        includeDirs.from("src/main/wasm/lua", "src/main/wasm/common")
        // KIBI_LUA_KEEP などは kibi_wasi.h にある（lua.wasm と同じく -include で渡す）。キー待ちの端末の包みは入れない
        compilerArgs.addAll("-O2", "-s", "-include", "kibi_wasi.h", "-DKIBI_LUA_NO_KEY_TTY", "-mexception-handling")
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
    // 独自拡張 kibi:device/bus（#77）の p1 の import を確かめる CLI
    module("kibi-device-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_device.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:computer/power（#306）の p1 の import を確かめる CLI
    module("kibi-power-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_power.c")
        includeDirs.from("src/main/wasm/common")
        compilerArgs.addAll("-O2", "-s")
    }
    // 独自拡張 kibi:computer/access（#110）の p1 の import を確かめる CLI
    module("kibi-access-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/kibi_access.c")
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

// ネイティブのツールチェーン（wasi-sdk の clang と wasm-component-ld。gradle/wasi-sdk.properties）で作るコンポーネント（#775）。
// アダプターで包んだ p1 のプログラムではなく、wasi-libc が p2・p3 の import を直接呼ぶ形。テストのリソースにだけ入れる（MOD には入れない）
kibiWasm {
    wasiSdkModule("hello-wasi-p2") {
        sources.from("src/test/wasm/hello_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // main が 0・1 以外を返す。wasi-libc の crt1 は wasi:cli/exit を呼ばず、wasi:cli/run の run から err を返す
    wasiSdkModule("exit3-p2") {
        sources.from("src/test/wasm/exit3.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // main から戻らずに exit() を呼ぶ。wasi-libc が wasi:cli/exit の exit を直接呼ぶ経路（exit3-p2 は run の err で、exit を呼ばない）
    wasiSdkModule("exit-native-p2") {
        sources.from("src/test/wasm/exit_native.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // wasi-libc が p2 の wasi:filesystem（preopen・descriptor）を直接呼ぶ形で、パスの操作を確かめる（path_wasi.c はアダプター経由の p1 と同じソース）
    wasiSdkModule("path-wasi-p2") {
        sources.from("src/test/wasm/path_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // /dev/device/<address> と /dev/tty の fd の isatty・fstat を、wasi-libc が p2 の wasi:filesystem を直接呼ぶ形で確かめる（#865・#885。dev_device_wasi.c は p1 と同じソース）
    wasiSdkModule("dev-device-wasi-p2") {
        sources.from("src/test/wasm/dev_device_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // args・environ・時計・乱数・sched_yield を、wasi-libc が wasi:cli・wasi:clocks・wasi:random を直接呼ぶ形で確かめる
    wasiSdkModule("basic-native-p2") {
        sources.from("src/test/wasm/basic_native.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // 計算し続ける CLI（fuel_spin_wasi.c）を、wasi-libc が p2 の import を直接呼ぶ形で。燃料切れの待ち点（#803）を確かめる
    wasiSdkModule("fuel-spin-p2") {
        sources.from("src/test/wasm/fuel_spin_wasi.c")
        compilerArgs.addAll("-O2", "-s")
    }
    // p3 は #268 まで読み込みで断る（NOEXEC）ことを確かめる
    wasiSdkModule("hello-wasi-p3") {
        sources.from("src/test/wasm/hello_wasi.c")
        target = "wasm32-wasip3"
        compilerArgs.addAll("-O2", "-s")
    }
}

// WASI p1 のモジュールを p2 のコンポーネントにするアダプター（wasmtime の配布物。#263）。
// テストで `wasm-tools component new --adapt` に渡し、実際のツールチェーンの形のコンポーネントを作る（MOD には入れない）
val wasiAdapterProps = Properties().apply {
    rootProject.file("gradle/wasi-adapter.properties").reader(Charsets.UTF_8).use(::load)
}
val wasiAdapter = kibiWasm.sourceArchive("wasi-adapter") {
    url = "https://github.com/bytecodealliance/wasmtime/releases/download/v${wasiAdapterProps.getProperty("version")}/wasi_snapshot_preview1.command.wasm"
    sha256 = wasiAdapterProps.getProperty("sha256")
    outputDir = layout.buildDirectory.dir("generated/wasi-adapter/kibicomputers/wasi-adapter")
}
sourceSets.named("test") {
    resources.srcDir(layout.buildDirectory.dir("generated/wasi-adapter"))
}
tasks.named("processTestResources") {
    dependsOn(wasiAdapter)
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
    // 標準のインタープリター（lua.c）とライブラリ。luac.c（コンパイラー）は入れない。
    // linit.c は KiBi の API（#94。ADR 0094）を開く kibi_linit.c に差し替える
    sources.from(luaSource.map { task -> task.outputDir.dir("src").get().asFileTree.matching { include("*.c"); exclude("luac.c", "linit.c") } })
    sources.from("src/main/wasm/lua/kibi_wasi.c")
    // Lua 向けの KiBi API（#94・#95）: peripheral・parallel・os の拡張と、可変長の結果を受ける cabi_realloc
    sources.from(
        "src/main/wasm/lua/kibi_linit.c",
        "src/main/wasm/lua/kibi_lua_canon.c",
        "src/main/wasm/lua/kibi_lua_peripheral.c",
        "src/main/wasm/lua/kibi_lua_os.c",
        "src/main/wasm/lua/kibi_lua_parallel.c",
        // term・colors・colours・keys・fs（#724。ADR 0724）
        "src/main/wasm/lua/kibi_lua_colors.c",
        "src/main/wasm/lua/kibi_lua_keys.c",
        "src/main/wasm/lua/kibi_lua_term.c",
        "src/main/wasm/lua/kibi_lua_fs.c",
        // キー待ちの間の端末のモード（#798。ADR 0798）
        "src/main/wasm/lua/kibi_lua_tty.c",
    )
    includeDirs.from(luaSource.map { task -> task.outputDir.dir("src") })
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

// 可変長の結果を返す import（`kibi.events` の `take`・`process.listProcesses`・`mounts.listMounts`/`listDisks`・`kibi.device`）を使い、
// `cabi_realloc`（`os/lib/kibi/canon.zig`）を export する必要があるプログラム。使うものを足したら、ここにも足す
// （足し忘れると、読み込みで NOEXEC になる。`StandardOsRestoreTest` が import から検出する）
val osReallocPrograms = mapOf("bin" to setOf("sh", "nano", "ps", "df", "mount", "man", "access", "lsdev", "devcall", "vi"))

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
            // `cabi_realloc` の要る import を使うプログラムだけ、`cabi_realloc`（#87・#454）を export する。Zig は WASM の実行ファイルで `_start` のほかを
            // export しないため。`-rdynamic` は可変グローバル `__stack_pointer` まで全プログラムに足すので使わない
            if (dir in osReallocPrograms.keys && name in osReallocPrograms.getValue(dir)) compilerArgs.add("--export=cabi_realloc")
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
    // man ページ（#106。`os/man/[言語/]man<節>/<名前>.<節>` → `/usr/share/man/...`。書式は docs/adr/0106-man.md）
    from(osDir.dir("man")) { into("usr/share/man") }
    from(osImageSkeleton)
    archiveFileName = "os.zip"
    destinationDirectory = layout.buildDirectory.dir("generated/images/os/kibicomputers/images")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/images/os")) { builtBy(zipOsImage) })
}
