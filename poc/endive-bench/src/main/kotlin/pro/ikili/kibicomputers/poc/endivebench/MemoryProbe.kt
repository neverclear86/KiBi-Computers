package pro.ikili.kibicomputers.poc.endivebench

import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.Instance
import run.endive.wasm.Parser
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * 準備・インスタンス生成の時間とメモリ。初回の時間（クラスの読み込みを含む）を測るため、1件ごとに新しい JVM で測る。
 *
 * - Metaspace は、準備と1つ目のインスタンス生成で増えた分（Endive 自体のクラスの読み込みを含む）
 * - ヒープは、GC の後の使用量の差。1インスタンスあたりは、20 個を保持したときの平均（線形メモリを含む）
 * - Redline の線形メモリとコードはヒープの外（ネイティブ）にあるので、ここには出ない
 */
class MemoryProbe(
    val prepareNanos: Long,
    val firstInstanceNanos: Long,
    val warmInstanceNanos: Long,
    val metaspaceBytes: Long,
    val prepareHeapBytes: Long,
    val perInstanceHeapBytes: Long,
) {
    fun encode(): String = "PROBE " + listOf(prepareNanos, firstInstanceNanos, warmInstanceNanos, metaspaceBytes, prepareHeapBytes, perInstanceHeapBytes).joinToString(",")

    /** ランタイムコンパイラで毎回コンパイルし直したときの、1回の時間と Metaspace の増え方。 */
    class Recompile(val compileNanos: Long, val metaspaceAfter10: Long, val metaspaceAfterGc: Long) {
        fun encode(): String = "RECOMPILE $compileNanos,$metaspaceAfter10,$metaspaceAfterGc"
    }

    companion object {
        private const val INSTANCES = 20

        fun run(wasm: Wasm, mode: Mode): MemoryProbe {
            val meta0 = metaspace()
            val heap0 = heapAfterGc()
            val t0 = System.nanoTime()
            val engine = Engine(wasm, mode)
            val t1 = System.nanoTime()
            val first = engine.newInstance(imports(wasm))
            val t2 = System.nanoTime()
            val meta1 = metaspace()
            val heap1 = heapAfterGc()
            // 2回目以降の生成時間
            repeat(20) { engine.newInstance(imports(wasm)) }
            val warm = (0 until 50).map {
                val s = System.nanoTime()
                engine.newInstance(imports(wasm))
                System.nanoTime() - s
            }.sorted()[25]
            val heapBefore = heapAfterGc()
            val kept = (0 until INSTANCES).map { engine.newInstance(imports(wasm)) }
            val heapAfter = heapAfterGc()
            check(kept.size == INSTANCES && first.exports() != null)
            return MemoryProbe(
                prepareNanos = t1 - t0,
                firstInstanceNanos = t2 - t1,
                warmInstanceNanos = warm,
                metaspaceBytes = meta1 - meta0,
                // 準備のヒープ = 準備 + 1つ目のインスタンス - 1インスタンス分
                prepareHeapBytes = (heap1 - heap0) - (heapAfter - heapBefore) / INSTANCES,
                perInstanceHeapBytes = (heapAfter - heapBefore) / INSTANCES,
            )
        }

        fun recompile(wasm: Wasm): Recompile {
            val bytes = wasm.bytes()
            // 1回目はクラスの読み込みを含むので外す
            compileOnce(wasm, bytes)
            heapAfterGc()
            val meta0 = metaspace()
            val t0 = System.nanoTime()
            repeat(10) { compileOnce(wasm, bytes) }
            val t1 = System.nanoTime()
            val meta1 = metaspace()
            heapAfterGc()
            val meta2 = metaspace()
            return Recompile((t1 - t0) / 10, meta1 - meta0, meta2 - meta0)
        }

        private fun compileOnce(wasm: Wasm, bytes: ByteArray) {
            val compiled = MachineFactoryCompiler.builder(Parser.parse(bytes)).withInterpreterFallback(InterpreterFallback.FAIL).compileModule()
            // 生成したクラスを実際に読み込ませる
            val builder = Instance.builder(compiled).withStart(false)
            imports(wasm)?.let(builder::withImportValues)
            builder.build()
        }

        /** lua.wasm の WASI の import。インスタンス生成だけなので中身は使わない */
        private fun imports(wasm: Wasm) = when (wasm) {
            Wasm.BENCH -> null
            Wasm.LUA -> run.endive.runtime.ImportValues.builder()
                .addFunction(*run.endive.wasi.WasiPreview1.builder().build().toHostFunctions())
                .build()
        }

        private fun metaspace(): Long = ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.NON_HEAP && it.name == "Metaspace" }
            .sumOf { it.usage.used }

        private fun heapAfterGc(): Long {
            val runtime = Runtime.getRuntime()
            repeat(3) {
                System.gc()
                Thread.sleep(50)
            }
            return runtime.totalMemory() - runtime.freeMemory()
        }

        private fun childJvm(vararg args: String): String {
            val java = ProcessHandle.current().info().command().orElse("java")
            val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments.filter { !it.startsWith("-agentlib") && !it.startsWith("-javaagent") }
            val command = listOf(java) + jvmArgs + listOf("-cp", System.getProperty("java.class.path"), "pro.ikili.kibicomputers.poc.endivebench.MainKt") + args
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).directory(File(".")).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "子の JVM が失敗した: $command\n$output" }
            return output
        }

        fun inChildJvm(wasm: Wasm, mode: Mode): MemoryProbe {
            val line = childJvm("memory", wasm.name, mode.name).lines().first { it.startsWith("PROBE ") }
            val v = line.removePrefix("PROBE ").split(',').map(String::toLong)
            return MemoryProbe(v[0], v[1], v[2], v[3], v[4], v[5])
        }

        fun recompileInChildJvm(wasm: Wasm): Recompile {
            val line = childJvm("recompile", wasm.name).lines().first { it.startsWith("RECOMPILE ") }
            val v = line.removePrefix("RECOMPILE ").split(',').map(String::toLong)
            return Recompile(v[0], v[1], v[2])
        }
    }
}
