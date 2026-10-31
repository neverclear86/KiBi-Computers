package pro.ikili.kibicomputers.poc.playerexec

import run.endive.wasi.WasiExitException
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random

/** 1回の起動の結果。 */
class RunResult(val output: String, val nanos: Long, val fuel: Long, val tier: String)

/**
 * `lua -` で [script] を標準入力から読ませて最後まで実行する（1回の起動）。
 * 時間は、読み込み（初めてなら挿入・パース、B なら初回のコンパイル待ち）から終わるまで。
 */
fun PlayerRuntime.runLua(program: Program, script: String): RunResult {
    val t0 = System.nanoTime()
    val lm = load(program)
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val options = WasiOptions.builder()
        .withArguments(listOf("lua", "-"))
        .withStdin(ByteArrayInputStream(script.toByteArray()))
        .withStdout(stdout)
        .withStderr(stderr)
        // Lua はハッシュの種に時刻を混ぜるので、命令数をそろえるため時計と乱数を固定する（#28 と同じ）
        .withClock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
        .withRandom(Random(0))
        .build()
    WasiPreview1.builder().withOptions(options).build().use { wasi ->
        val launch = launch(lm, wasi.toHostFunctions().toList())
        val tier = launch.tier
        val exit = try {
            launch.instance.export("_start").apply()
            0
        } catch (e: WasiExitException) {
            e.exitCode()
        }
        launch.finish()
        check(exit == 0) { "lua が $exit で終了した: $stderr" }
        return RunResult(stdout.toString(Charsets.UTF_8), System.nanoTime() - t0, launch.used(), tier)
    }
}

/** bench.wasm の関数を1回呼ぶ（1回の起動）。 */
fun PlayerRuntime.runBench(program: Program, function: String, vararg args: Long): RunResult {
    val t0 = System.nanoTime()
    val launch = launch(load(program))
    val tier = launch.tier
    val r = launch.instance.export(function).apply(*args)[0]
    launch.finish()
    return RunResult(r.toString(), System.nanoTime() - t0, launch.used(), tier)
}

/** 測定に使う Lua のスクリプト。 */
object Scripts {
    /** 起動してすぐ終わる（短いコマンド） */
    const val TINY: String = "print('hi')"

    /** 少し計算する（インタプリタで 0.1〜0.5 秒ほど） */
    const val SMALL: String = "local function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end print(fib(18))"

    /** 重い計算（#26 の fib.lua。fib(23)） */
    const val HEAVY: String = "local function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end print(fib(23))"
}
