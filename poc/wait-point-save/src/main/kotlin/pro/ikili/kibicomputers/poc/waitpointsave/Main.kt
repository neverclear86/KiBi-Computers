package pro.ikili.kibicomputers.poc.waitpointsave

import pro.ikili.kibicomputers.poc.waitpointsave.WaiterEvents.ALLOC
import pro.ikili.kibicomputers.poc.waitpointsave.WaiterEvents.PUSH
import run.endive.runtime.Memory
import java.io.File
import java.util.zip.CRC32C
import java.util.zip.Deflater

/**
 * #30 の測定。シナリオごとに、待機点での保存データのサイズと、取り出し・変換・書き戻しの時間を測って表にする。
 *
 * 引数 `quick` で 50 MB のシナリオを省き、回数を減らす。
 */
fun main(args: Array<String>) {
    val quick = "quick" in args
    val reps = if (quick) 3 else 9
    val out = StringBuilder()
    fun line(s: String = "") {
        println(s)
        out.append(s).append('\n')
    }

    line("# 待機点で保存する方式の測定結果（#30）")
    line()
    line("- `./gradlew :poc:wait-point-save:run` の出力。Endive 1.2.0、${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}、${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    line("- JVM 引数: ${java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")}")
    line()
    line("- 時間は $reps 回の中央値（1回目はウォームアップとして捨てる）。実行方式はランタイムコンパイラ")
    line("- A: 生のバイト列 / B: A を Deflate（標準ライブラリ、既定のレベル 6）/ C: 全部 0 のページを省いて Deflate / D: 小さなイベントを1つ処理したあとの、前回から変わったページだけ（ページは CRC32C で比べる）")
    line("- 取り出し: 線形メモリ・可変グローバル・テーブル・カーネルの状態をコピーする時間。変換: A のバイト列にする時間。圧縮: B・C を作る時間")
    line("- 書き戻し（生成込み）: 新しいインスタンスを作って書き戻す時間。書き戻しのみ: 作ってあるインスタンスにメモリなどを書く時間")
    line()

    line("## モジュール")
    line()
    line("| WASM | サイズ | export |")
    line("| --- | ---: | --- |")
    for (p in WasmProgram.entries) {
        val es = p.module.exportSection()
        line("| ${p.name.lowercase()} | ${size(p.bytes.size.toLong())} | ${(0 until es.exportCount()).joinToString(", ") { "`${es.getExport(it).name()}`" }} |")
    }
    line()

    // シナリオ 0: #91 の lua.wasm（コマンド形式）
    line("## シナリオ 0: #91 の lua.wasm（コマンド形式）")
    line()
    line("| 待ち方 | 待ちに入るまで | 待っている関数 | 保存 | ページ数 | 生（測定用） | Deflate（測定用） |")
    line("| --- | ---: | --- | --- | ---: | ---: | ---: |")
    for ((label, argv) in listOf("REPL（引数なし）" to listOf("lua"), "`lua -e \"local l = io.read(); print(l)\"`" to listOf("lua", "-e", "local l = io.read(); print(l)"))) {
        val times = mutableListOf<Long>()
        var row = ""
        repeat(reps + 1) { rep ->
            val p = Process.start(WasmProgram.LUA_COMMAND, Mode.RUNTIME_COMPILER, argv)
            val t0 = System.nanoTime()
            p.kernel.hostWaitHook = HostWaitHook { reason ->
                if (p.kernel.hostWaits == 1) {
                    times += System.nanoTime() - t0
                    val result = runCatching { Snapshot.capture(p) }.exceptionOrNull()
                    val m = Snapshot.measureOnly(p)
                    val raw = m.memory.size.toLong() + m.kernel.size
                    if (rep == 0) row = "$reason | ${if (result is NotAtWaitPointException) "できない（WASM の中）" else "できた"} | ${m.pages} | ${size(raw)} | ${size(deflate(m.memory).size.toLong())}"
                }
                p.kernel.stdinClosed = true
            }
            p.runCommand()
        }
        line("| $label | ${time(median(times.drop(1)))} | $row |")
    }
    line()

    // シナリオ 1〜5: reactor の待機点
    line("## シナリオ 1〜5: reactor の待機点")
    line()
    line("| シナリオ | ページ数 | A 生 | B Deflate | C ゼロ省略+Deflate | D 差分（ページ数 / 生 / Deflate） | 取り出し | 変換 | 圧縮 B / C | 書き戻し（生成込み） | 書き戻しのみ |")
    line("| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: | ---: |")

    data class Scenario(val label: String, val program: WasmProgram, val boot: () -> Process, val nextEvent: ByteArray)

    fun waiter(vararg allocs: Int) = { boot(WasmProgram.WAITER, Mode.RUNTIME_COMPILER).also { p -> allocs.forEach { p.deliver(waiterEvent(ALLOC, it)) } } }
    fun lua(script: String) = { boot(WasmProgram.LUA_HOST, Mode.RUNTIME_COMPILER, script) }
    val scenarios = buildList {
        add(Scenario("1. waiter 起動直後", WasmProgram.WAITER, waiter(), waiterEvent(PUSH, 1)))
        add(Scenario("1. waiter 1 MB 確保", WasmProgram.WAITER, waiter(1 shl 20), waiterEvent(PUSH, 1)))
        add(Scenario("1. waiter 16 MB 確保", WasmProgram.WAITER, waiter(16 shl 20), waiterEvent(PUSH, 1)))
        add(Scenario("2. lua_host 待つだけ", WasmProgram.LUA_HOST, lua(Scripts.IDLE), "x".toByteArray()))
        for (mb in if (quick) listOf(1, 10) else listOf(1, 10, 50)) {
            add(Scenario("3. lua_host データ $mb MB", WasmProgram.LUA_HOST, lua(Scripts.data(mb)), "1".toByteArray()))
        }
        add(Scenario("4. lua_host コルーチン 1,000 個", WasmProgram.LUA_HOST, lua(Scripts.coroutines(1000)), "1:1".toByteArray()))
        add(Scenario("5. lua_host pcall・wrap の入れ子", WasmProgram.LUA_HOST, lua(Scripts.NESTED), "a".toByteArray()))
    }

    for (s in scenarios) {
        val p = s.boot()
        val snap = Snapshot.capture(p)
        val raw = snap.toBytes()
        val b = deflate(raw)
        val c = deflate(snap.toBytesSkippingZeroPages())

        // D: 小さなイベントを1つ処理したあとの差分
        val before = pageHashes(snap.memory)
        p.deliver(s.nextEvent)
        val after = Snapshot.capture(p)
        val afterHashes = pageHashes(after.memory)
        val changed = afterHashes.indices.filter { it >= before.size || afterHashes[it] != before[it] }
        val delta = java.io.ByteArrayOutputStream().also { o -> changed.forEach { o.write(after.memory, it * Memory.PAGE_SIZE, Memory.PAGE_SIZE) }; o.write(after.kernel) }.toByteArray()

        val tCapture = measure(reps) { Snapshot.capture(p) }
        val tEncode = measure(reps) { snap.toBytes() }
        val tB = measure(reps) { deflate(snap.toBytes()) }
        val tC = measure(reps) { deflate(snap.toBytesSkippingZeroPages()) }
        val tRestore = measure(reps) { snap.restore(s.program, Mode.RUNTIME_COMPILER).instance.close() }
        val tApply = measureWith(reps, { Process.instantiate(s.program, Mode.RUNTIME_COMPILER, MiniKernel()) }) { snap.apply(it) }
        p.instance.close()

        line(
            "| ${s.label} | ${snap.pages} | ${size(raw.size.toLong())} | ${size(b.size.toLong())} | ${size(c.size.toLong())} | " +
                "${changed.size} / ${size(delta.size.toLong())} / ${size(deflate(delta).size.toLong())} | " +
                "${time(tCapture)} | ${time(tEncode)} | ${time(tB)} / ${time(tC)} | ${time(tRestore)} | ${time(tApply)} |",
        )
    }
    line()

    // 待ちに入るまでの時間（シナリオ 0 と 2 を比べる）
    val bootTimes = measure(reps) { boot(WasmProgram.LUA_HOST, Mode.RUNTIME_COMPILER, Scripts.IDLE).instance.close() }
    line("lua_host（シナリオ 2）がインスタンスを作って最初の待機点に来るまで: ${time(bootTimes)}")

    val file = File("build/bench/results.md")
    file.parentFile.mkdirs()
    file.writeText(out.toString())
}

private fun deflate(bytes: ByteArray): ByteArray {
    val d = Deflater(Deflater.DEFAULT_COMPRESSION)
    d.setInput(bytes)
    d.finish()
    val out = java.io.ByteArrayOutputStream(bytes.size / 4 + 64)
    val buf = ByteArray(64 * 1024)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return out.toByteArray()
}

private fun pageHashes(memory: ByteArray): LongArray = LongArray(memory.size / Memory.PAGE_SIZE) { i ->
    val crc = CRC32C()
    crc.update(memory, i * Memory.PAGE_SIZE, Memory.PAGE_SIZE)
    crc.value
}

private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

private inline fun measure(reps: Int, block: () -> Unit): Long = measureWith(reps, {}) { block() }

private inline fun <T> measureWith(reps: Int, setup: () -> T, block: (T) -> Unit): Long {
    val times = (0..reps).map {
        val s = setup()
        val t0 = System.nanoTime()
        block(s)
        System.nanoTime() - t0
    }
    return median(times.drop(1))
}

private fun size(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.2f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun time(nanos: Long): String = when {
    nanos >= 1_000_000_000 -> "%.2f s".format(nanos / 1e9)
    nanos >= 1_000_000 -> "%.1f ms".format(nanos / 1e6)
    else -> "%d µs".format(nanos / 1000)
}
