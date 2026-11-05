package pro.ikili.kibicomputers.poc.oslanguage

import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream

/**
 * 燃料と速さを測るための、最小の WASI p1 のホスト（#32）。
 *
 * カーネル（スケジューラ・tick・コンソール）を通さず、燃料の計測コードだけを挿入した（待機点への変換はしない）モジュールを
 * Endive で直接動かす。燃料の計測コードは変換の前に挿入するので（[Instrumentation]）、使う燃料はカーネルの上と同じになる。
 * 燃料は使い切らない量を渡し、`初めの量 - 終わった後の残り` を使った燃料とする。
 *
 * ファイルは根 `/` の直下だけ（[files]）。ディレクトリは `/` だけ。
 */
internal class MiniWasi(
    private val args: List<String>,
    private val stdin: ByteArray = ByteArray(0),
    private val files: Map<String, ByteArray> = emptyMap(),
) {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()

    /** モジュールの import のうち、このホストが出していないもの（呼ばれると失敗する） */
    val missingImports = mutableListOf<String>()

    private class Exit(val code: Int) : RuntimeException(null, null, false, false)

    private sealed interface Fd
    private class FileFd(val data: ByteArray, var pos: Int = 0) : Fd
    private object RootFd : Fd

    private val fds = HashMap<Int, Fd>().apply { put(3, RootFd) }
    private var nextFd = 4
    private var stdinPos = 0

    class Result(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray, val fuel: Long, val instantiateNanos: Long, val runNanos: Long)

    /** [module]（[load] で読み込んだもの）を動かす */
    fun run(module: WasmModule): Result {
        val builder = WasmInstanceConfig.builder()
        builder.fuel(INITIAL_FUEL, WasmFuelHandler { _, _ -> error("燃料を使い切った（INITIAL_FUEL を増やす）") })
        val seen = HashSet<String>()
        for (imp in module.imports) {
            if (imp.kind != WasmExternKind.FUNCTION) continue
            // MoonBit は同じ import（fd_write）を2回宣言することがある。ホストの関数は1つだけ登録する
            if (!seen.add("${imp.module}.${imp.name}")) continue
            if (imp.module == "kibi" && (imp.name == "fuel" || imp.name.startsWith("async_"))) continue
            val type = imp.functionType ?: continue
            val f = function(imp.module, imp.name) ?: run {
                missingImports += "${imp.module}.${imp.name}"
                WasmHostFunction { _, _ -> error("出していない import: ${imp.module}.${imp.name}") }
            }
            builder.hostFunction(imp.module, imp.name, type, f)
        }
        val t0 = System.nanoTime()
        val instance = module.instantiate(builder.build())
        val t1 = System.nanoTime()
        val code = try {
            instance.call("_start")
            0
        } catch (e: Exit) {
            e.code
        }
        val t2 = System.nanoTime()
        val fuel = INITIAL_FUEL - instance.fuel
        instance.close()
        return Result(code, stdout.toByteArray(), stderr.toByteArray(), fuel, t1 - t0, t2 - t1)
    }

    private fun mem(instance: WasmInstance): WasmMemory = requireNotNull(instance.memory) { "線形メモリがない" }

    private fun ok(): LongArray = longArrayOf(0)

    private fun err(errno: Int): LongArray = longArrayOf(errno.toLong())

    private fun function(module: String, name: String): WasmHostFunction? = when ("$module.$name") {
        "wasi_snapshot_preview1.fd_write" -> WasmHostFunction { inst, a ->
            val m = mem(inst)
            val out = when (a[0].toInt()) {
                1 -> stdout
                2 -> stderr
                else -> return@WasmHostFunction err(BADF)
            }
            var total = 0
            for (i in 0 until a[2].toInt()) {
                val base = m.readI32(a[1].toInt() + i * 8)
                val len = m.readI32(a[1].toInt() + i * 8 + 4)
                out.write(m.read(base, len))
                total += len
            }
            m.writeI32(a[3].toInt(), total)
            ok()
        }
        "wasi_snapshot_preview1.fd_read" -> WasmHostFunction { inst, a ->
            val m = mem(inst)
            val fd = a[0].toInt()
            val file = fds[fd] as? FileFd
            if (fd != 0 && file == null) return@WasmHostFunction err(BADF)
            var total = 0
            for (i in 0 until a[2].toInt()) {
                val base = m.readI32(a[1].toInt() + i * 8)
                val len = m.readI32(a[1].toInt() + i * 8 + 4)
                val (data, pos) = if (file != null) file.data to file.pos else stdin to stdinPos
                val n = minOf(len, data.size - pos)
                if (n > 0) m.write(base, data, pos, n)
                if (file != null) file.pos += n else stdinPos += n
                total += n
                if (n < len) break
            }
            m.writeI32(a[3].toInt(), total)
            ok()
        }
        "wasi_snapshot_preview1.fd_close" -> WasmHostFunction { _, a ->
            if (fds.remove(a[0].toInt()) == null) err(BADF) else ok()
        }
        "wasi_snapshot_preview1.path_open" -> WasmHostFunction { inst, a ->
            val m = mem(inst)
            if (fds[a[0].toInt()] != RootFd) return@WasmHostFunction err(BADF)
            val path = String(m.read(a[2].toInt(), a[3].toInt()), Charsets.UTF_8)
            val directory = (a[4].toInt() and 2) != 0
            val fd = when {
                path == "." -> RootFd
                directory -> return@WasmHostFunction err(if (path in files) NOTDIR else NOENT)
                else -> FileFd(files[path] ?: return@WasmHostFunction err(NOENT))
            }
            val n = nextFd++
            fds[n] = fd
            m.writeI32(a[8].toInt(), n)
            ok()
        }
        "wasi_snapshot_preview1.fd_readdir" -> WasmHostFunction { inst, a ->
            val m = mem(inst)
            if (fds[a[0].toInt()] != RootFd) return@WasmHostFunction err(NOTDIR)
            val buf = a[1].toInt()
            val len = a[2].toInt()
            val names = listOf(".", "..") + files.keys.sorted()
            val out = ByteArrayOutputStream()
            for (i in a[3].toInt() until names.size) {
                val name = names[i].toByteArray(Charsets.UTF_8)
                val d = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                d.putLong(i + 1L).putLong(i + 1L).putInt(name.size).put(if (i < 2) 3 else 4)
                out.write(d.array())
                out.write(name)
                if (out.size() >= len) break
            }
            val bytes = out.toByteArray()
            val n = minOf(len, bytes.size)
            m.write(buf, bytes, 0, n)
            m.writeI32(a[4].toInt(), n)
            ok()
        }
        "wasi_snapshot_preview1.args_sizes_get" -> WasmHostFunction { inst, a ->
            val m = mem(inst)
            m.writeI32(a[0].toInt(), args.size)
            m.writeI32(a[1].toInt(), args.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 })
            ok()
        }
        "wasi_snapshot_preview1.args_get" -> WasmHostFunction { inst, a ->
            val m = mem(inst)
            var p = a[1].toInt()
            args.forEachIndexed { i, s ->
                val b = s.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                m.writeI32(a[0].toInt() + i * 4, p)
                m.write(p, b)
                p += b.size
            }
            ok()
        }
        "wasi_snapshot_preview1.proc_exit" -> WasmHostFunction { _, a -> throw Exit(a[0].toInt()) }
        "kibi.memory_commit" -> WasmHostFunction { _, _ -> longArrayOf(1) }
        "kibi.memory_remaining" -> WasmHostFunction { _, _ -> longArrayOf(1L shl 20) }
        else -> null
    }

    companion object {
        /** 使い切らない量の燃料 */
        const val INITIAL_FUEL: Long = 1L shl 50

        private const val BADF = 8
        private const val NOENT = 44
        private const val NOTDIR = 54

        /** [bytes] に燃料の計測コードだけを挿入して読み込む */
        fun load(runtime: EndiveRuntime, bytes: ByteArray): WasmModule = runtime.load(Instrumentation.applyDetailed(bytes, emptySet()).bytes)
    }
}
