package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.endive.WrittenPagesMemory
import pro.ikili.kibicomputers.core.runtime.endive.commitWritten
import pro.ikili.kibicomputers.core.runtime.endive.committedPages
import pro.ikili.kibicomputers.core.runtime.endive.countedBytes
import pro.ikili.kibicomputers.core.runtime.endive.isCompiled
import pro.ikili.kibicomputers.core.runtime.endive.pendingWrittenBytes
import pro.ikili.kibicomputers.core.runtime.endive.romChunks
import pro.ikili.kibicomputers.core.runtime.endive.writtenChunks
import run.endive.wasm.Parser
import run.endive.wasm.types.ActiveDataSegment
import java.io.ByteArrayOutputStream

/**
 * 書き換えたメモリだけを RAM として数える PoC（#370）の計測。`-Pkibi.ramPoc` を付けたときだけ動かす（CI では動かさない）。
 *
 * ```
 * ./gradlew :core:test --tests '*RamPocMeasurements*' -Pkibi.ramPoc
 * ```
 *
 * 結果は Markdown の表にして標準出力に出す。実行はコンパイルしたコード（本番と同じ）。
 * カーネルを通すのは REPL の待ち（1）だけで、ほかはランタイムの抽象を直接使う（[TestWasiP1]。カーネルの p1 と同じ数え方のホスト関数をつなぐ）。
 */
@EnabledIfSystemProperty(named = "kibi.ramPoc", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RamPocMeasurements {
    private val runtime = EndiveRuntime()
    private val kib = 1024L
    private val modules = HashMap<String, WasmModule>()

    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name.wasm")) { "$name.wasm がない" }.use { it.readBytes() }

    private fun module(name: String): WasmModule = modules.getOrPut(name) { runtime.load(bytes(name)).also { check(isCompiled(it)) { "$name がコンパイルされていない" } } }

    /** 上限 [limitBytes] のバイト数の勘定（カーネルの ProcessMemory と同じ扱い） */
    private class Budget(val limitBytes: Long) : WasmTableBudget, WasmMemoryBudget {
        var used = 0L
        var peak = 0L

        override fun tryReserveBytes(bytes: Long): Boolean {
            if (used + bytes > limitBytes) return false
            used += bytes
            peak = maxOf(peak, used)
            return true
        }

        override fun releaseBytes(bytes: Long) {
            used -= bytes
        }

        override fun tryReserve(pages: Int): Boolean = tryReserveBytes(pages.toLong() * WasmMemory.PAGE_SIZE)

        override fun release(pages: Int) = releaseBytes(pages.toLong() * WasmMemory.PAGE_SIZE)
    }

    private enum class Mode(val label: String) {
        DECLARED("今の方式（宣言したページ数）"),
        WRITTEN("書き換えた 4 KiB"),
        GROWN("最初の大きさは書き換えた 4 KiB、grow はページ数"),
    }

    private class Run(val result: String, val stdout: String, val stderr: String, val counted: Long, val committedPages: Int, val millis: Double)

    /** [name] を [args] で動かす。[mode] と RAM [limitBytes] で数える */
    private fun run(name: String, args: List<String>, mode: Mode, limitBytes: Long = 64L * 1024 * 1024): Run {
        val module = module(name)
        val budget = Budget(limitBytes)
        val wasi = TestWasiP1(args)
        val config = wasi.config(module) {
            when (mode) {
                Mode.DECLARED -> memoryLimit((limitBytes / WasmMemory.PAGE_SIZE).toInt(), budget)
                Mode.WRITTEN -> writtenMemoryLimit(WrittenPagesMemory.POC_MAX_PAGES, budget)
                Mode.GROWN -> writtenMemoryLimit(WrittenPagesMemory.POC_MAX_PAGES, budget, countGrownPages = true)
            }
            if (module.imports.any { it.module == RamPoc.MODULE }) {
                hostFunction(RamPoc.MODULE, RamPoc.REMAINING, RamPoc.REMAINING_TYPE) { _, _ -> longArrayOf(budget.limitBytes - budget.used) }
                hostFunction(RamPoc.MODULE, RamPoc.COMMIT, RamPoc.COMMIT_TYPE) { instance, a ->
                    val m = instance.memory!!
                    val pending = pendingWrittenBytes(m, a[0].toInt(), a[1].toInt())
                    val ok = (pending == 0L || budget.limitBytes - budget.used - pending >= a[2]) && commitWritten(m, a[0].toInt(), a[1].toInt())
                    longArrayOf(if (ok) 1L else 0L)
                }
            }
        }
        val start = System.nanoTime()
        val instance = try {
            module.instantiate(config)
        } catch (e: Exception) {
            return Run("起動できない", "", e.message.orEmpty(), budget.peak, 0, 0.0)
        }
        return instance.use {
            val result = try {
                instance.call("_start")
                "exit 0"
            } catch (e: TestWasiP1.Exit) {
                "exit ${e.code}"
            } catch (e: WasmTrapException) {
                "トラップ（${e.kind}）"
            }
            val millis = (System.nanoTime() - start) / 1e6
            val memory = instance.memory!!
            val counted = if (mode == Mode.DECLARED) memory.pages.toLong() * WasmMemory.PAGE_SIZE else countedBytes(memory)
            Run(result, wasi.stdout.toString(Charsets.UTF_8), wasi.stderr.toString(Charsets.UTF_8), counted, committedPages(memory), millis)
        }
    }

    private fun kb(bytes: Long): String = "${bytes / kib} KiB"

    private fun oneLine(s: String, max: Int = 60): String = s.replace("\n", " ").replace("|", "\\|").trim().let { if (it.length > max) it.take(max) + "…" else it }

    /** 例のプログラム（Issue の「テーブルを作る、文字列をつなぐ、再帰」） */
    private val examples: List<Pair<String, String>> = listOf(
        "何もしない" to "",
        "テーブル 1,000 要素" to "local t = {} for i = 1, 1000 do t[i] = i end",
        "テーブル 10,000 要素" to "local t = {} for i = 1, 10000 do t[i] = i end",
        "文字列のテーブル 1,000 個" to "local t = {} for i = 1, 1000 do t[i] = 'item' .. i end",
        "`..` で 1,000 回つなぐ" to "local s = '' for i = 1, 1000 do s = s .. i end",
        "`table.concat` で 10,000 個" to "local t = {} for i = 1, 10000 do t[i] = tostring(i) end local s = table.concat(t, ',')",
        "再帰 深さ 50" to "local function f(n) if n == 0 then return 0 end return 1 + f(n - 1) end f(50)",
        "再帰 深さ 150" to "local function f(n) if n == 0 then return 0 end return 1 + f(n - 1) end f(150)",
        "再帰 深さ 10,000（Lua のスタック）" to "local function f(n) if n == 0 then return 0 end return 1 + f(n - 1) end f(10000)",
    )

    /** C のスタックを深く使うもの */
    private val cStackHeavy: List<Pair<String, String>> = listOf(
        "string.gsub（10 万文字）" to "local s = ('x'):rep(100000):gsub('.', '%0%0') assert(#s == 200000)",
        "string.match（`a*` を 150 個。項目ごとに C の再帰）" to "assert(string.match(('a'):rep(150), '^' .. ('a*'):rep(150) .. '\$'))",
        "table.sort（10 万要素、比較関数あり）" to "local t = {} for i = 1, 100000 do t[i] = (i * 7919) % 100003 end table.sort(t, function(a, b) return a > b end)",
        "pcall の入れ子 180 段" to "local function f(n) if n == 0 then return 1 end local ok, v = pcall(f, n - 1) return v end assert(f(180) == 1)",
        "load: 括弧の入れ子 190 段" to "assert(load('return ' .. ('('):rep(190) .. '1' .. (')'):rep(190))() == 1)",
        "load: 括弧の入れ子 250 段（Lua の上限超え）" to "local f, err = load('return ' .. ('('):rep(250) .. '1' .. (')'):rep(250)) assert(not f) print(err)",
        "coroutine 100 個" to "local cs = {} for i = 1, 100 do cs[i] = coroutine.wrap(function() coroutine.yield(i) end) cs[i]() end",
    )

    @Test
    fun `1 REPLが入力を待つまでと、例のプログラムで書き換える量`() {
        val out = StringBuilder("\n### 1. 書き換える量（lua.wasm、C のスタック 256 KiB）\n\n")
        // カーネルを通して、REPL が入力を待つ（WAITING_EVENT）まで
        Harness(execRuntime = EndiveRuntime(), ramAccounting = RamAccounting.WRITTEN_4K).use { h ->
            val console = ByteArrayOutputStream()
            val m = h.machine(fuelPerTick = 10_000_000, host = consoleHost(console), memoryBytes = 4 * 1024 * kib)
            m.lock.withLockValue {
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                val b = bytes("lua")
                val f = m.vfs.open(VfsPath.parse("/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, b, 0, b.size)
            }
            val pid = m.exec("/lua", KernelDrivers.RAM_POC, SpawnOptions(args = listOf("lua")))
            eventually(30_000) {
                h.scheduler.tick()
                Thread.sleep(1)
                synchronized(console) { console.toString(Charsets.UTF_8) }.endsWith("> ") && m.stateOf(pid) == ProcessState.WAITING_EVENT
            }
            val memory = m.rt(pid).instances.mapNotNull { it.memory }.single()
            out.append("- カーネルを通した REPL（待機点型）が入力を待つとき: 書き換え ${writtenChunks(memory)} 個（${kb(writtenChunks(memory) * 4096L)}）、")
            out.append("ROM ${romChunks(memory)} 個（${kb(romChunks(memory) * 4096L)}。数えない）、宣言 ${memory.pages} ページ（${kb(memory.pages * 65536L)}）、")
            out.append("マシンの RAM の使用量 ${kb(m.memoryAccount.usedBytes)}（テーブルの分を含む）\n")
            m.stop()
            eventually(30_000) {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
        }
        // 端末の REPL（入力が空で、待たずに終わる）。終わったときの量 = 最高値
        val repl = run("lua", listOf("lua"), Mode.WRITTEN)
        out.append("- ブロッキング型の REPL（入力が空ですぐ終わる）: ${kb(repl.counted)}\n\n")
        out.append("| 例 | 書き換えた量（4 KiB 単位） | 今の LazyMemory が確保した量（64 KiB 単位） | 宣言したページ数（今の RAM の数え方） |\n| --- | --- | --- | --- |\n")
        for ((label, script) in examples) {
            val w = run("lua", listOf("lua", "-e", script), Mode.WRITTEN)
            val d = run("lua", listOf("lua", "-e", script), Mode.DECLARED)
            check(w.result == "exit 0" && d.result == "exit 0") { "$label: ${w.result} ${w.stderr} / ${d.result} ${d.stderr}" }
            out.append("| $label | ${kb(w.counted)} | ${kb(d.committedPages * 65536L)} | ${kb(d.counted)} |\n")
        }
        report(out)
    }

    @Test
    fun `2 Cのスタックを減らしたとき`() {
        val out = StringBuilder("\n### 2. C のスタックを減らしたとき（書き換えた 4 KiB で数え、RAM は 64 MiB）\n\n")
        val stacks = listOf("lua-stack32k" to "32 KiB", "lua-stack64k" to "64 KiB", "lua-stack128k" to "128 KiB", "lua" to "256 KiB")
        // スタックとデータの配置
        out.append("| lua.wasm | `__stack_pointer` の初期値 | データの開始 | 最初のメモリ |\n| --- | --- | --- | --- |\n")
        for ((name, label) in stacks) {
            val parsed = Parser.parse(bytes(name))
            val sp = parsed.globalSection().getGlobal(0).initInstructions()[0].operand(0)
            val dataStart = parsed.dataSection().dataSegments().filterIsInstance<ActiveDataSegment>().minOf { it.offsetInstructions()[0].operand(0) }
            val initial = parsed.memorySection().get().getMemory(0).limits().initialPages()
            out.append("| スタック $label | $sp | $dataStart | $initial ページ |\n")
        }
        out.append("\n| プログラム | " + stacks.joinToString(" | ") { "スタック ${it.second}" } + " |\n|" + " --- |".repeat(stacks.size + 1) + "\n")
        for ((label, script) in examples + cStackHeavy) {
            val cells = stacks.map { (name, _) ->
                val r = run(name, listOf("lua", "-e", script), Mode.WRITTEN)
                when {
                    r.result == "exit 0" -> "通る（${kb(r.counted)}）"
                    else -> "${r.result} ${oneLine(r.stderr, 50)}"
                }
            }
            out.append("| $label | ${cells.joinToString(" | ")} |\n")
        }
        report(out)
    }

    @Test
    fun `3 速度の差`() {
        val out = StringBuilder("\n### 3. 速度（ミリ秒。インスタンスを作るところから終わるまで。温めて 2 回捨て、5 回の中央値）\n\n")
        val loads = listOf(
            Triple("Lua: fib(27)", "lua", listOf("lua", "-e", "local function fib(n) if n < 2 then return n end return fib(n-1) + fib(n-2) end print(fib(27))")),
            Triple("Lua: テーブルに 30 万回挿入", "lua", listOf("lua", "-e", "local t = {} for i = 1, 300000 do t[#t + 1] = i end print(#t)")),
            Triple("Lua: 文字列を 20 万個つなぐ", "lua", listOf("lua", "-e", "local t = {} for i = 1, 200000 do t[i] = 'x' .. i end print(#table.concat(t))")),
            Triple(
                "Lua: binary-trees（深さ 14）",
                "lua",
                listOf("lua", "-e", "local function make(d) if d == 0 then return {} end return {make(d-1), make(d-1)} end local function check(t) if not t[1] then return 1 end return 1 + check(t[1]) + check(t[2]) end local n = 0 for i = 1, 8 do n = n + check(make(14)) end print(n)"),
            ),
            Triple("C: memset 8 MiB × 40", "memwrite", listOf("memwrite", "memset", "8", "40")),
            Triple("C: memcpy 8 MiB × 40", "memwrite", listOf("memwrite", "memcpy", "8", "40")),
            Triple("C: 8 MiB に 4 KiB ごと 1 バイト × 100", "memwrite", listOf("memwrite", "stride", "8", "100")),
            Triple("C: 8 MiB に飛び飛びに 4 バイト × 100 万回 × 10", "memwrite", listOf("memwrite", "scatter", "8", "10")),
        )
        out.append("| 負荷 | LazyMemory（64 KiB） | 書き換えた 4 KiB | 比 | 4 KiB の初めての書き込みの数 |\n| --- | --- | --- | --- | --- |\n")
        for ((label, name, args) in loads) {
            val lazy = ArrayList<Double>()
            val written = ArrayList<Double>()
            var chunks = 0L
            repeat(7) { i ->
                val a = run(name, args, Mode.DECLARED)
                val b = run(name, args, Mode.WRITTEN)
                check(a.result == "exit 0" && b.result == "exit 0" && a.stdout == b.stdout) { "$label: ${a.result} ${a.stderr} / ${b.result} ${b.stderr}" }
                if (i >= 2) {
                    lazy += a.millis
                    written += b.millis
                }
                chunks = b.counted / 4096
            }
            val l = lazy.sorted()[2]
            val w = written.sorted()[2]
            out.append("| $label | ${"%.0f".format(l)} | ${"%.0f".format(w)} | ${"%.2f".format(w / l)} | $chunks |\n")
        }
        report(out)
    }

    @Test
    fun `4 上限を超えたとき`() {
        val out = StringBuilder("\n### 4. 上限を超えたとき\n\n")
        val script = "local n = 0 local ok, err = pcall(function() local t = {} for i = 1, 1e7 do t[i] = ('x'):rep(64) .. i n = i end end) " +
            "io.stderr:write('[', tostring(err), ' n=', n, ']') collectgarbage() " +
            "for k = 1, 20 do local s = {} for i = 1, 300 do s[i] = ('y'):rep(32) .. i end end io.stderr:write('[続けられた]')"
        val cases = listOf(
            Triple("包みなしの lua.wasm、書き換えた 4 KiB", "lua", Mode.WRITTEN),
            Triple("包み（ram_commit）を入れた lua、書き換えた 4 KiB", "lua-rampoc", Mode.WRITTEN),
            Triple("包みなしの lua.wasm、最初は書き換えた 4 KiB・grow はページ数", "lua", Mode.GROWN),
            Triple("包みなしの lua.wasm、今の方式", "lua", Mode.DECLARED),
        )
        val limits = listOf(64L, 128L, 192L, 256L, 512L, 1024L)
        out.append("| 形 | " + limits.joinToString(" | ") { "RAM $it KiB" } + " |\n|" + " --- |".repeat(limits.size + 1) + "\n")
        for ((label, name, mode) in cases) {
            val cells = limits.map { limit ->
                val r = run(name, listOf("lua", "-e", script), mode, limit * kib)
                "${r.result} ${oneLine(r.stderr, 80)}"
            }
            out.append("| $label | ${cells.joinToString(" | ")} |\n")
        }
        report(out)
    }

    @Test
    fun `5 今の方式でLuaが動く最小の容量`() {
        val out = StringBuilder("\n### 5. 今の方式（宣言したページ数）で Lua が動く最小の RAM\n\n")
        val stacks = listOf("lua-stack32k" to "32 KiB", "lua-stack64k" to "64 KiB", "lua-stack128k" to "128 KiB", "lua" to "256 KiB")
        val programs = listOf(
            "`print(1)`" to "print(1)",
            "テーブル 10,000 要素" to examples[2].second,
            "`table.concat` で 10,000 個" to examples[5].second,
        )
        out.append("| プログラム | " + stacks.joinToString(" | ") { "スタック ${it.second}" } + " |\n|" + " --- |".repeat(stacks.size + 1) + "\n")
        for (mode in listOf(Mode.DECLARED, Mode.WRITTEN, Mode.GROWN)) {
            for ((label, script) in programs) {
                val cells = stacks.map { (name, _) ->
                    val found = minimumRam(name, script, mode)
                    if (found == null) "8 MiB でも動かない" else kb(found)
                }
                out.append("| ${mode.label}: $label | ${cells.joinToString(" | ")} |\n")
            }
        }
        report(out)
    }

    /** [script] が通る最小の RAM（[mode] が今の方式ならページ単位、ほかは 4 KiB 単位。通るかは RAM について単調とみなして二分探索する） */
    private fun minimumRam(name: String, script: String, mode: Mode): Long? {
        val step = if (mode == Mode.DECLARED) 64 * kib else 4 * kib
        fun ok(units: Long): Boolean = run(name, listOf("lua", "-e", script), mode, units * step).result == "exit 0"
        var hi = 8 * 1024 * kib / step
        if (!ok(hi)) return null
        var lo = 0L
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (ok(mid)) hi = mid else lo = mid
        }
        return hi * step
    }

    /** 結果を標準出力と build/ram-poc/ に出す */
    private fun report(out: StringBuilder) {
        println(out)
        val dir = java.io.File("build/ram-poc").apply { mkdirs() }
        java.io.File(dir, "results.md").appendText(out.toString(), Charsets.UTF_8)
    }

    private fun consoleHost(out: ByteArrayOutputStream): MachineHost = object : MachineHost {
        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }
    }
}
