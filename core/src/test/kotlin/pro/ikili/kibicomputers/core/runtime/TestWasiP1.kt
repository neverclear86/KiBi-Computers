package pro.ikili.kibicomputers.core.runtime

import java.io.ByteArrayOutputStream

/**
 * テスト用の最小の WASI p1（`wasi_snapshot_preview1`）。ランタイムの抽象（[WasmHostFunction]）だけで組む。
 *
 * zig cc でビルドした実際のプログラムを、ランタイムの抽象を通して動かすために使う。
 * カーネルの WASI（#259）ではない。標準入出力・引数・時計・乱数だけを持ち、ファイルや preopen はない。
 * 知らない関数は `ENOSYS` を返す。
 */
class TestWasiP1(private val args: List<String>, stdin: String = "") {
    /** `proc_exit` で投げる。 */
    class Exit(val code: Int) : RuntimeException("exit $code", null, false, false)

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    private val stdinBytes = stdin.toByteArray(Charsets.UTF_8)
    private var stdinPos = 0

    /** [module] の WASI の import をすべてつないだ設定。[configure] でほかの設定（燃料など）を足せる */
    fun config(module: WasmModule, configure: WasmInstanceConfig.Builder.() -> Unit = {}): WasmInstanceConfig {
        val builder = WasmInstanceConfig.builder().apply(configure)
        for (imp in module.imports) {
            if (imp.module != MODULE || imp.kind != WasmExternKind.FUNCTION) continue
            val type = imp.functionType!!
            builder.hostFunction(MODULE, imp.name, type, function(imp.name, type))
        }
        return builder.build()
    }

    /** `_start` を呼び、終了コードを返す。 */
    fun run(module: WasmModule): Int = module.instantiate(config(module)).use { instance ->
        try {
            instance.call("_start")
            0
        } catch (e: Exit) {
            e.code
        }
    }

    private fun function(name: String, type: WasmFunctionType): WasmHostFunction = when (name) {
        "args_sizes_get" -> errno { m, a -> sizes(m, a, args) }
        "args_get" -> errno { m, a -> strings(m, a, args) }
        "environ_sizes_get" -> errno { m, a -> sizes(m, a, emptyList()) }
        "environ_get" -> errno { m, a -> strings(m, a, emptyList()) }
        "fd_write" -> errno(::fdWrite)
        "fd_read" -> errno(::fdRead)
        "fd_fdstat_get" -> errno(::fdFdstatGet)
        "fd_prestat_get" -> errno { _, _ -> EBADF }
        "fd_close" -> errno { _, _ -> SUCCESS }
        "fd_seek" -> errno { _, _ -> ESPIPE }
        "clock_time_get" -> errno { m, a ->
            m.writeI64(a[2].toInt(), System.nanoTime())
            SUCCESS
        }
        "random_get" -> errno { m, a ->
            for (i in 0 until a[1].toInt()) m.writeU8(a[0].toInt() + i, i * 31)
            SUCCESS
        }
        "proc_exit" -> WasmHostFunction { _, a -> throw Exit(a[0].toInt()) }
        else -> WasmHostFunction { _, _ -> LongArray(type.results.size).also { if (it.isNotEmpty()) it[0] = ENOSYS.toLong() } }
    }

    private fun errno(body: (WasmMemory, LongArray) -> Int): WasmHostFunction = WasmHostFunction { instance, args ->
        longArrayOf(body(instance.memory!!, args).toLong())
    }

    private fun sizes(memory: WasmMemory, a: LongArray, values: List<String>): Int {
        memory.writeI32(a[0].toInt(), values.size)
        memory.writeI32(a[1].toInt(), values.sumOf { it.toByteArray().size + 1 })
        return SUCCESS
    }

    private fun strings(memory: WasmMemory, a: LongArray, values: List<String>): Int {
        var ptr = a[0].toInt()
        var buf = a[1].toInt()
        for (value in values) {
            val bytes = value.toByteArray() + 0
            memory.writeI32(ptr, buf)
            memory.write(buf, bytes)
            ptr += 4
            buf += bytes.size
        }
        return SUCCESS
    }

    private fun fdWrite(memory: WasmMemory, a: LongArray): Int {
        val out = when (a[0].toInt()) {
            1 -> stdout
            2 -> stderr
            else -> return EBADF
        }
        var total = 0
        for (i in 0 until a[2].toInt()) {
            val iov = a[1].toInt() + i * 8
            val len = memory.readI32(iov + 4)
            out.write(memory.read(memory.readI32(iov), len))
            total += len
        }
        memory.writeI32(a[3].toInt(), total)
        return SUCCESS
    }

    private fun fdRead(memory: WasmMemory, a: LongArray): Int {
        if (a[0].toInt() != 0) return EBADF
        var total = 0
        for (i in 0 until a[2].toInt()) {
            val iov = a[1].toInt() + i * 8
            val n = minOf(memory.readI32(iov + 4), stdinBytes.size - stdinPos)
            memory.write(memory.readI32(iov), stdinBytes, stdinPos, n)
            stdinPos += n
            total += n
        }
        memory.writeI32(a[3].toInt(), total)
        return SUCCESS
    }

    private fun fdFdstatGet(memory: WasmMemory, a: LongArray): Int {
        if (a[0].toInt() !in 0..2) return EBADF
        val buf = a[1].toInt()
        memory.write(buf, ByteArray(24))
        memory.writeU8(buf, FILETYPE_CHARACTER_DEVICE)
        memory.writeI64(buf + 8, -1L)
        memory.writeI64(buf + 16, -1L)
        return SUCCESS
    }

    companion object {
        const val MODULE = "wasi_snapshot_preview1"
        private const val SUCCESS = 0
        private const val EBADF = 8
        private const val ENOSYS = 52
        private const val ESPIPE = 70
        private const val FILETYPE_CHARACTER_DEVICE = 2
    }
}
