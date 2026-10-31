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
}

// テスト用の WASM（#9）。zig cc でビルドし、テストのリソースに入れる
kibiWasm {
    // WASI を使わない最小のモジュール。ランタイムで実行して結果を確かめる
    module("fib", sourceSet = "test") {
        sources.from("src/test/wasm/fib.c")
        target = "wasm32-freestanding"
        compilerArgs.addAll("-O2", "-s", "-nostdlib", "-Wl,--no-entry", "-Wl,--export=fib")
    }
    // wasi-libc をリンクする WASI p1 のプログラム（lua.wasm などと同じビルドの形）
    module("hello-wasi", sourceSet = "test") {
        sources.from("src/test/wasm/hello_wasi.c")
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
kibiWasm.module("lua") {
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
        // C のスタック（仮の値）。Lua の C 呼び出しの深さ（LUAI_MAXCCALLS = 200）に足りる大きさにする
        "-Wl,-z,stack-size=1048576",
    )
}
