package pro.ikili.kibicomputers.poc.waitpointsave

import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.Memory
import run.endive.wasm.WasmModule
import run.endive.wasm.types.FunctionImport

/** `proc_exit` で終了した。 */
class ProcExit(val code: Int) : RuntimeException("proc_exit($code)", null, false, false)

/**
 * PoC 用の WASI p1 の最小実装と、`kibi_poc` の import。
 *
 * 状態はすべて [MiniKernel] に持つ（Endive の `WasiPreview1` は fd 表などの状態を取り出せないので使わない）。
 * モジュールの import のうち実装しないものは `ENOSYS` を返す関数で埋める。
 */
class MiniWasi(private val kernel: MiniKernel) {
    /** 実装していないので ENOSYS で埋めた import（確認用） */
    val unimplemented: MutableList<String> = mutableListOf()

    fun imports(module: WasmModule): ImportValues {
        val builder = ImportValues.builder()
        val section = module.importSection()
        for (i in 0 until section.importCount()) {
            val imp = section.getImport(i)
            require(imp is FunctionImport) { "関数でない import には対応していない: $imp" }
            val type = module.typeSection().getType(imp.typeIndex())
            val body = function(imp.module(), imp.name()) ?: run {
                unimplemented += "${imp.module()}.${imp.name()}"
                val results = LongArray(type.returns().size)
                if (results.isNotEmpty()) results[0] = ENOSYS.toLong()
                Body { _, _ -> results.copyOf() }
            }
            builder.addFunction(HostFunction(imp.module(), imp.name(), type) { instance, args -> body.call(instance.memory(), args) })
        }
        return builder.build()
    }

    private fun interface Body {
        fun call(memory: Memory, args: LongArray): LongArray
    }

    private fun ok(errno: Int = SUCCESS) = longArrayOf(errno.toLong())

    private fun function(module: String, name: String): Body? = when (module) {
        KIBI_POC -> when (name) {
            // ホスト関数の中で待つ「ブロッキング型」の例。戻り値は引数 + 1
            "block" -> Body { _, args ->
                kernel.blockingWait("$KIBI_POC.block")
                longArrayOf(args[0] + 1)
            }
            else -> null
        }
        WASI -> when (name) {
            "fd_write" -> Body { mem, args -> ok(fdWrite(mem, args[0].toInt(), args[1].toInt(), args[2].toInt(), args[3].toInt())) }
            "fd_read" -> Body { mem, args -> ok(fdRead(mem, args[0].toInt(), args[1].toInt(), args[2].toInt(), args[3].toInt())) }
            "fd_fdstat_get" -> Body { mem, args -> ok(fdstat(mem, args[0].toInt(), args[1].toInt())) }
            "fd_prestat_get" -> Body { _, _ -> ok(EBADF) }
            "fd_close" -> Body { _, args -> ok(if (args[0] in 0L..2L) SUCCESS else EBADF) }
            "fd_seek" -> Body { _, args -> ok(if (args[0] in 0L..2L) ESPIPE else EBADF) }
            "clock_time_get" -> Body { mem, args ->
                mem.writeLong(args[2].toInt(), kernel.clockNanos)
                kernel.clockNanos += CLOCK_STEP
                ok()
            }
            "clock_res_get" -> Body { mem, args ->
                mem.writeLong(args[1].toInt(), 1)
                ok()
            }
            "random_get" -> Body { mem, args ->
                val buf = args[0].toInt()
                val len = args[1].toInt()
                val bytes = ByteArray(len)
                var i = 0
                while (i < len) {
                    var r = kernel.nextRandom()
                    for (j in 0 until minOf(8, len - i)) {
                        bytes[i + j] = r.toByte()
                        r = r ushr 8
                    }
                    i += 8
                }
                mem.write(buf, bytes)
                ok()
            }
            "args_sizes_get" -> Body { mem, args -> ok(sizes(mem, kernel.args, args[0].toInt(), args[1].toInt())) }
            "args_get" -> Body { mem, args -> ok(strings(mem, kernel.args, args[0].toInt(), args[1].toInt())) }
            "environ_sizes_get" -> Body { mem, args -> ok(sizes(mem, emptyList(), args[0].toInt(), args[1].toInt())) }
            "environ_get" -> Body { _, _ -> ok() }
            "proc_exit" -> Body { _, args -> throw ProcExit(args[0].toInt()) }
            else -> null
        }
        else -> null
    }

    private fun fdWrite(mem: Memory, fd: Int, iovs: Int, count: Int, nwritten: Int): Int {
        val out = when (fd) {
            1 -> kernel.stdout
            2 -> kernel.stderr
            else -> return EBADF
        }
        var total = 0
        for (i in 0 until count) {
            val buf = mem.readInt(iovs + i * 8)
            val len = mem.readInt(iovs + i * 8 + 4)
            out.write(mem.readBytes(buf, len))
            total += len
        }
        mem.writeI32(nwritten, total)
        return SUCCESS
    }

    private fun fdRead(mem: Memory, fd: Int, iovs: Int, count: Int, nread: Int): Int {
        if (fd != 0) return EBADF
        // 入力がなければ、ホスト関数の中で待つ（フックが入力を足すことがある）。それでもなければ EOF
        if (kernel.stdin.isEmpty() && !kernel.stdinClosed) kernel.blockingWait("$WASI.fd_read")
        var total = 0
        for (i in 0 until count) {
            if (kernel.stdin.isEmpty()) break
            val buf = mem.readInt(iovs + i * 8)
            val len = minOf(mem.readInt(iovs + i * 8 + 4), kernel.stdin.size)
            mem.write(buf, kernel.stdin, 0, len)
            kernel.stdin = kernel.stdin.copyOfRange(len, kernel.stdin.size)
            total += len
        }
        mem.writeI32(nread, total)
        return SUCCESS
    }

    private fun fdstat(mem: Memory, fd: Int, buf: Int): Int {
        if (fd !in 0..2) return EBADF
        // 端末（文字デバイスで seek・tell できない）として見せる。wasi-libc の isatty が真になり、stdout は行バッファになる
        mem.writeByte(buf, FILETYPE_CHARACTER_DEVICE)
        mem.writeShort(buf + 2, 0)
        mem.writeLong(buf + 8, TTY_RIGHTS)
        mem.writeLong(buf + 16, TTY_RIGHTS)
        return SUCCESS
    }

    private fun sizes(mem: Memory, list: List<String>, countPtr: Int, sizePtr: Int): Int {
        mem.writeI32(countPtr, list.size)
        mem.writeI32(sizePtr, list.sumOf { it.toByteArray().size + 1 })
        return SUCCESS
    }

    private fun strings(mem: Memory, list: List<String>, ptrs: Int, buf: Int): Int {
        var p = buf
        list.forEachIndexed { i, s ->
            mem.writeI32(ptrs + i * 4, p)
            val b = s.toByteArray() + 0
            mem.write(p, b)
            p += b.size
        }
        return SUCCESS
    }

    companion object {
        const val WASI = "wasi_snapshot_preview1"
        const val KIBI_POC = "kibi_poc"
        const val SUCCESS = 0
        const val EBADF = 8
        const val ENOSYS = 52
        const val ESPIPE = 70
        const val FILETYPE_CHARACTER_DEVICE: Byte = 2

        /** 端末の権利: すべてから fd_seek（1 << 2）と fd_tell（1 << 5）を除いたもの */
        const val TTY_RIGHTS: Long = -1L and ((1L shl 2) or (1L shl 5)).inv()

        /** `clock_time_get` のたびに進める仮想時間（1 µs） */
        const val CLOCK_STEP = 1_000L
    }
}
