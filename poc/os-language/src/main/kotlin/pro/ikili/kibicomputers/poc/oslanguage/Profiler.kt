package pro.ikili.kibicomputers.poc.oslanguage

import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.Memory
import run.endive.runtime.ProfilingMachine
import run.endive.wasm.Parser
import run.endive.wasm.types.FunctionImport
import run.endive.wasm.types.OpCode
import java.io.File

/**
 * 関数ごとの燃料の内訳（#32 の追加調査）。
 *
 * 燃料の計測コードは挿入せず、元のモジュールを Endive のインタプリタで動かし、実行した命令を関数ごとに数える。
 * 燃料は「命令 1 つで 1」（`FuelInjector`。`memory.copy`・`memory.fill` は長さ / 8 を足す）で、セグメントの先頭でまとめて引くので、
 * ここでも同じセグメントに分け、先頭の命令を実行したときにその長さを数える。合計は [MiniWasi] で測った燃料と同じになる（合計を並べて確かめる）。関数の名前は name セクションから引く（なければ番号）。
 *
 * `./gradlew :poc:os-language:run -Pbench.args=profile` で、表を標準出力と `build/bench/profile.md` に、
 * ホットな関数の命令ごとの回数を `build/bench/profile/<シナリオ>-<言語>.txt` に書く。
 */
internal object Profiler {
    /** 1 回分の測定 */
    class Profile(
        val label: String,
        val names: List<String>,
        /** 関数ごとの、その関数の中で実行した命令（燃料） */
        val self: LongArray,
        /** 関数ごとの、その関数と呼んだ先で実行した命令 */
        val total: LongArray,
        /** 関数ごとの、呼ばれた回数 */
        val calls: LongArray,
        /** インスタンスの生成（start 関数）で使った燃料 */
        val startFuel: Long,
        /** 全体の燃料 */
        val fuel: Long,
        /** 命令の位置（モジュールの先頭からのバイト）ごとの回数 */
        val perAddress: LongArray,
        val module: run.endive.wasm.WasmModule,
        val importCount: Int,
        val stdout: ByteArray,
    )

    fun profile(label: String, bytes: ByteArray, scenario: Scenario): Profile {
        val module = Parser.parse(bytes)
        val imports = (0 until module.importSection().importCount()).map { module.importSection().getImport(it) }.filterIsInstance<FunctionImport>()
        val importCount = imports.size
        val functionCount = importCount + module.codeSection().functionBodyCount()
        val nameSection = runCatching { module.nameSection() }.getOrNull()
        val names = (0 until functionCount).map { i ->
            runCatching { nameSection?.nameOfFunction(i) }.getOrNull()
                ?: if (i < importCount) "${imports[i].module()}.${imports[i].name()}" else "func[$i]"
        }
        val firstAddress = IntArray(functionCount) { -1 }
        // FuelInjector と同じく、分岐の出入りのない命令の並び（セグメント）の先頭で、その命令の数を数える
        val segmentCost = LongArray(bytes.size + 1)
        // 命令の位置 → 本体の中で1つ前の命令の位置（先頭は -1）
        val previous = IntArray(bytes.size + 1) { -1 }
        for (i in 0 until module.codeSection().functionBodyCount()) {
            val ins = module.codeSection().getFunctionBody(i).instructions()
            firstAddress[importCount + i] = ins.first().address()
            for (k in 1 until ins.size) previous[ins[k].address()] = ins[k - 1].address()
            var k = 0
            while (k < ins.size) {
                var j = k
                while (j < ins.size - 1 && ins[j].opcode() !in SEGMENT_END) j++
                segmentCost[ins[k].address()] = (j - k + 1).toLong()
                k = j + 1
            }
        }

        val host = MiniWasi(scenario.args, scenario.stdin, scenario.files)
        val self = LongArray(functionCount)
        val total = LongArray(functionCount)
        val calls = LongArray(functionCount)
        val perAddress = LongArray(bytes.size + 1)
        val stack = IntArray(256)
        val seen = BooleanArray(functionCount)
        val opcodeAt = arrayOfNulls<OpCode>(bytes.size + 1)
        for (i in 0 until module.codeSection().functionBodyCount()) {
            for (ins in module.codeSection().getFunctionBody(i).instructions()) opcodeAt[ins.address()] = ins.opcode()
        }
        var last = -1
        lateinit var machine: ProfilingMachine
        lateinit var adapter: Adapter
        val seenImports = HashSet<String>()
        val hostFunctions = imports.mapNotNull { imp ->
            if (!seenImports.add("${imp.module()}.${imp.name()}")) return@mapNotNull null
            val f = host.function(imp.module(), imp.name()) ?: return@mapNotNull null
            HostFunction(imp.module(), imp.name(), module.typeSection().getType(imp.typeIndex())) { _, args ->
                // 呼ばれた import も 1 回と数える
                f.call(adapter, args)
            }
        }
        val instance = Instance.builder(module)
            .withImportValues(ImportValues.builder().withFunctions(hostFunctions).build())
            .withMachineFactory { ProfilingMachine(it).also { m -> machine = m } }
            .withUnsafeExecutionListener { ins, mstack ->
                val n = machine.stackFuncs(stack)
                if (n == 0) return@withUnsafeExecutionListener
                val f = stack[0]
                var cost = segmentCost[ins.address()]
                // 分岐でブロックの外に出るとき、Endive は `end` に飛んでそれを実行するが、燃料の計測コードは `end` の前に
                // 引くので数えない。`end` から始まるセグメントは、1つ前の命令から順に進んできたときだけ数える
                if (cost > 0 && ins.opcode() == OpCode.END) {
                    val prev = previous[ins.address()]
                    val fell = prev >= 0 && prev == last && opcodeAt[prev] !in NO_FALLTHROUGH
                    if (!fell) cost = 0
                }
                last = ins.address()
                when (ins.opcode()) {
                    OpCode.MEMORY_COPY, OpCode.MEMORY_FILL, OpCode.MEMORY_INIT -> cost += (mstack.peek() and 0xFFFFFFFFL) shr 3
                    else -> Unit
                }
                self[f] += cost
                perAddress[ins.address()] += 1
                if (ins.address() == firstAddress[f]) calls[f]++
                for (k in 0 until n) {
                    val g = stack[k]
                    if (!seen[g]) {
                        seen[g] = true
                        total[g] += cost
                    }
                }
                for (k in 0 until n) seen[stack[k]] = false
            }
            .withInitialize(true)
            .withStart(false)
            .build()
        adapter = Adapter(instance.memory())
        // start セクションの関数（MoonBit の初期化）は build の中で実行される。_start と分けて数える
        val startFuel = self.sum()
        try {
            instance.export("_start").apply()
        } catch (e: Throwable) {
            if (generateSequence(e) { it.cause }.none { it is MiniWasi.Exit }) throw e
        }
        return Profile(label, names, self, total, calls, startFuel, self.sum(), perAddress, module, importCount, host.stdout.toByteArray())
    }

    /** 次の命令に進まない命令 */
    private val NO_FALLTHROUGH = setOf(
        OpCode.ELSE, OpCode.BR, OpCode.BR_TABLE, OpCode.RETURN, OpCode.UNREACHABLE, OpCode.THROW, OpCode.THROW_REF,
        OpCode.RETURN_CALL, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF,
    )

    /** FuelInjector.SEGMENT_END と同じ */
    private val SEGMENT_END = setOf(
        OpCode.LOOP, OpCode.IF, OpCode.ELSE, OpCode.END,
        OpCode.BR, OpCode.BR_IF, OpCode.BR_TABLE, OpCode.RETURN, OpCode.UNREACHABLE,
        OpCode.THROW, OpCode.THROW_REF, OpCode.RETURN_CALL, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF,
        OpCode.BR_ON_NULL, OpCode.BR_ON_NON_NULL, OpCode.BR_ON_CAST, OpCode.BR_ON_CAST_FAIL,
    )

    /** Endive の [Instance] を、[MiniWasi] のホストの関数が使う [WasmInstance] に見せる（使うのは memory だけ） */
    private class Adapter(memory: Memory) : WasmInstance {
        override val module: WasmModule get() = error("使わない")
        override val memory: WasmMemory = MemoryAdapter(memory)
        override var fuel: Long = 0
        override var asyncState: WasmAsyncState
            get() = error("使わない")
            set(_) = error("使わない")
        override fun call(name: String, vararg args: Long): LongArray = error("使わない")
        override fun close() = Unit
    }

    private class MemoryAdapter(private val m: Memory) : WasmMemory {
        override val pages: Int get() = m.pages()
        override fun grow(deltaPages: Int): Int = m.grow(deltaPages)
        override fun read(address: Int, dst: ByteArray, dstOffset: Int, length: Int) {
            m.readBytes(address, length).copyInto(dst, dstOffset)
        }
        override fun write(address: Int, src: ByteArray, srcOffset: Int, length: Int) = m.write(address, src, srcOffset, length)
        override fun readU8(address: Int): Int = m.read(address).toInt() and 0xFF
        override fun writeU8(address: Int, value: Int) = m.writeByte(address, value.toByte())
        override fun readI32(address: Int): Int = m.readInt(address)
        override fun writeI32(address: Int, value: Int) = m.writeI32(address, value)
        override fun readI64(address: Int): Long = m.readLong(address)
        override fun writeI64(address: Int, value: Long) = m.writeLong(address, value)
    }

    private fun fmt(n: Long): String = "%,d".format(n)

    private fun pct(n: Long, of: Long): String = if (of == 0L) "-" else "%.1f%%".format(n * 100.0 / of)

    /** 関数ごとの表（上位 [top] 個） */
    fun table(p: Profile, top: Int = 15): String = buildString {
        appendLine("### ${p.label}（燃料 ${fmt(p.fuel)}。うち start 関数 ${fmt(p.startFuel)}）")
        appendLine()
        appendLine("| 関数 | 自分 | 割合 | 呼んだ先を含む | 呼ばれた回数 | 1 回あたり |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
        val order = p.self.indices.filter { p.self[it] > 0 }.sortedByDescending { p.self[it] }
        for (i in order.take(top)) {
            val per = if (p.calls[i] > 0) "%.1f".format(p.self[i].toDouble() / p.calls[i]) else "-"
            appendLine("| `${p.names[i]}` | ${fmt(p.self[i])} | ${pct(p.self[i], p.fuel)} | ${fmt(p.total[i])} | ${fmt(p.calls[i])} | $per |")
        }
        if (order.size > top) {
            val rest = order.drop(top).sumOf { p.self[it] }
            appendLine("| ほか ${order.size - top} 個 | ${fmt(rest)} | ${pct(rest, p.fuel)} | | | |")
        }
        appendLine()
    }

    /** 自分の燃料が [minShare] 以上の関数の、命令ごとの実行回数（逆アセンブル） */
    fun listing(p: Profile, minShare: Double = 0.01): String = buildString {
        val order = p.self.indices.filter { p.self[it] >= p.fuel * minShare && it >= p.importCount }.sortedByDescending { p.self[it] }
        for (f in order) {
            val body = p.module.codeSection().getFunctionBody(f - p.importCount)
            appendLine(";; ${p.names[f]} (func $f): 自分 ${fmt(p.self[f])} (${pct(p.self[f], p.fuel)})、呼ばれた回数 ${fmt(p.calls[f])}、locals ${body.localTypes()}")
            for (ins in body.instructions()) {
                val count = p.perAddress[ins.address()]
                val ops = ins.operands().joinToString(" ")
                val callee = if (ins.opcode() == OpCode.CALL) "  ;; ${p.names[ins.operand(0).toInt()]}" else ""
                appendLine("%12s  %s%s %s%s".format(if (count == 0L) "." else fmt(count), "  ".repeat(maxOf(0, ins.depth())), ins.opcode().name.lowercase(), ops, callee))
            }
            appendLine()
        }
    }

    /** プログラムの名前 → シナリオ。燃料の差が大きい shell・ls と、起動だけの kmem・echo */
    private val targets = listOf("shell", "ls", "kmem", "echo", "cat")

    private fun resource(path: String): ByteArray? = Profiler::class.java.getResourceAsStream("/kibicomputers/poc/os-language/$path")?.use { it.readBytes() }

    /** 比べるもの（プログラムの名前 → ラベルと WASM の並び）。CI がビルドしたリソースの Zig・MoonBit・MoonBit+wasm-opt */
    fun defaultVariants(): (String) -> List<Pair<String, ByteArray>> = { p ->
        listOfNotNull(
            resource("$p-zig.wasm")?.let { "Zig" to it },
            resource("$p-moonbit.wasm")?.let { "MoonBit" to it },
            resource("$p-moonbit-opt.wasm")?.let { "MoonBit+wasm-opt" to it },
        )
    }

    /**
     * Zig と、[dir]（CI の成果物の `experiments`。`moonbitExperiments` タスクの出力）の `<プログラム>*.wasm` を比べる。
     * ファイル名がそのままラベルになる（`shell.wasm` は名前つきの元のプログラム、`shell_fast-O2.wasm` は書き換え版に wasm-opt をかけたもの）
     */
    fun directoryVariants(dir: File): (String) -> List<Pair<String, ByteArray>> = { p ->
        val files = dir.listFiles { f -> f.isFile && f.extension == "wasm" && f.name.startsWith(p) && f.name.getOrNull(p.length) in setOf('.', '-', '_') }
            ?.sortedBy { it.name } ?: emptyList()
        listOfNotNull(resource("$p-zig.wasm")?.let { "Zig" to it }) + files.map { it.nameWithoutExtension to it.readBytes() }
    }

    fun run(variants: (String) -> List<Pair<String, ByteArray>>, listings: Boolean = true): String = buildString {
        appendLine("# 関数ごとの燃料の内訳（#32 の追加調査）")
        appendLine()
        File("build/bench/profile").mkdirs()
        val details = StringBuilder()
        val runtime = EndiveRuntime()
        appendLine("実行: 燃料の計測コードを入れて、ランタイムコンパイラ（SIMD の関数はインタプリタ）で動かした `_start` の時間（5 回の中央値。目安）")
        appendLine()
        appendLine("| シナリオ | 版 | 燃料 | Zig 比 | うち start 関数 | 出力が Zig と同じ | 実行 |")
        appendLine("| --- | --- | ---: | ---: | ---: | --- | ---: |")
        for (program in targets) {
            for (scenario in Bench.scenarios.filter { it.program == program }) {
                details.appendLine("## ${scenario.label}").appendLine()
                var zig: Profile? = null
                for ((label, bytes) in variants(program)) {
                    val p = try {
                        profile(label, bytes, scenario)
                    } catch (e: Exception) {
                        appendLine("| ${scenario.label} | $label | 失敗: ${e.message?.take(80)} | | | | |")
                        continue
                    }
                    if (label == "Zig") zig = p
                    val ratio = zig?.let { "%.2f".format(p.fuel.toDouble() / it.fuel) } ?: "-"
                    val same = zig?.let { if (it.stdout.contentEquals(p.stdout)) "同じ" else "**違う**" } ?: "-"
                    val module = MiniWasi.load(runtime, bytes)
                    val times = (0..5).map { MiniWasi(scenario.args, scenario.stdin, scenario.files).run(module).runNanos }.drop(1).sorted()
                    val ms = "%.2f ms".format(times[times.size / 2] / 1e6)
                    appendLine("| ${scenario.label} | $label | ${fmt(p.fuel)} | $ratio | ${fmt(p.startFuel)} | $same | $ms |")
                    details.append(table(p))
                    if (listings) {
                        val file = "build/bench/profile/${scenario.label.substringBefore('（')}-${scenario.args.size}-${label.replace('+', '_')}.txt"
                        File(file).writeText(listing(p))
                    }
                }
            }
        }
        appendLine()
        append(details)
    }
}
