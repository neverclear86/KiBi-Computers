package pro.ikili.kibicomputers.poc.threadlesswait

import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.Memory
import run.endive.wasm.WasmModule
import run.endive.wasm.types.FunctionImport
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** `proc_exit` で終了した。 */
class ProcExit(val code: Int) : RuntimeException("proc_exit($code)", null, false, false)

/** 待ち方。 */
enum class WaitStyle {
    /** ホスト関数の中でスレッドを止めて待つ（方式 B・C） */
    BLOCK,

    /** asyncify で WASM のスタックを退避して戻る（方式 A） */
    UNWIND,
}

/**
 * PoC 用のカーネルの状態（1プロセス分）と、WASI p1 の最小実装。
 *
 * 標準入力はキュー。空なら [WaitStyle] に従って待つ。状態はすべてここに持つ（待機点保存の確認に使う）。
 */
class P1Kernel(val args: List<String>, val style: WaitStyle) {
    private val lock = ReentrantLock()
    private val readable = lock.newCondition()

    private var stdin = ByteArray(0)
    private var stdinClosed = false
    val stdout = ByteArrayOutputStream()

    /** 待ちに入った回数 */
    @Volatile var waits: Int = 0
        private set

    /** 待ちに入ったときに呼ぶ（測定用。待ちに入ったスレッドから呼ぶ） */
    @Volatile var onWait: (() -> Unit)? = null

    /** asyncify の退避先（ローカル変数と呼び出しの番号を i64 で積む） */
    var frames = LongArray(64)
    var frameTop = 0

    /** asyncify の状態のグローバル（[WaitStyle.UNWIND] のとき） */
    var asyncState: run.endive.runtime.GlobalInstance? = null

    fun send(bytes: ByteArray) = lock.withLock {
        stdin += bytes
        readable.signalAll()
    }

    fun closeStdin() = lock.withLock {
        stdinClosed = true
        readable.signalAll()
    }

    fun hasInput(): Boolean = lock.withLock { stdin.isNotEmpty() || stdinClosed }

    fun stdoutText(): String = synchronized(stdout) { stdout.toString(Charsets.UTF_8) }

    fun push(v: Long) {
        if (frameTop == frames.size) frames = frames.copyOf(frames.size * 2)
        frames[frameTop++] = v
    }

    fun pop(): Long {
        check(frameTop > 0) { "退避した値がない" }
        return frames[--frameTop]
    }

    /** 標準入力を読む。読めなければ待つ。UNWIND で巻き戻しに入ったときは null */
    private fun readStdin(max: Int): ByteArray? {
        val state = asyncState
        if (style == WaitStyle.UNWIND) {
            val s = state!!.getValue().toInt()
            if (s == Asyncify.REWINDING) {
                check(frameTop == 0) { "巻き直しの終わりに退避した値が残っている: $frameTop" }
                state.setValue(Asyncify.NORMAL.toLong())
            }
        }
        lock.lock()
        try {
            if (stdin.isEmpty() && !stdinClosed) {
                waits++
                if (style == WaitStyle.UNWIND) {
                    state!!.setValue(Asyncify.UNWINDING.toLong())
                    return null
                }
                onWait?.invoke()
                while (stdin.isEmpty() && !stdinClosed) readable.await()
            }
            val n = minOf(max, stdin.size)
            val out = stdin.copyOfRange(0, n)
            stdin = stdin.copyOfRange(n, stdin.size)
            return out
        } finally {
            lock.unlock()
        }
    }

    fun imports(module: WasmModule): ImportValues {
        val builder = ImportValues.builder()
        val section = module.importSection()
        for (i in 0 until section.importCount()) {
            val imp = section.getImport(i) as FunctionImport
            val type = module.typeSection().getType(imp.typeIndex())
            val body: (Instance, LongArray) -> LongArray = function(imp.module(), imp.name()) ?: run {
                val results = LongArray(type.returns().size)
                if (results.isNotEmpty()) results[0] = ENOSYS.toLong()
                val f: (Instance, LongArray) -> LongArray = { _, _ -> results.copyOf() }
                f
            }
            builder.addFunction(HostFunction(imp.module(), imp.name(), type) { instance, args -> body(instance, args) })
        }
        return builder.build()
    }

    private fun ok(errno: Int = 0) = longArrayOf(errno.toLong())

    private fun function(module: String, name: String): ((Instance, LongArray) -> LongArray)? = when (module) {
        Asyncify.IMPORT_MODULE -> when (name) {
            Asyncify.PUSH -> { _, a -> push(a[0]); LongArray(0) }
            Asyncify.POP -> { _, _ -> longArrayOf(pop()) }
            else -> null
        }
        WASI -> when (name) {
            "fd_write" -> { inst, a -> ok(fdWrite(inst.memory(), a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3].toInt())) }
            "fd_read" -> { inst, a -> ok(fdRead(inst.memory(), a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3].toInt())) }
            "fd_fdstat_get" -> { inst, a -> ok(fdstat(inst.memory(), a[0].toInt(), a[1].toInt())) }
            "fd_prestat_get" -> { _, _ -> ok(EBADF) }
            "fd_close" -> { _, a -> ok(if (a[0] in 0L..2L) 0 else EBADF) }
            "fd_seek" -> { _, a -> ok(if (a[0] in 0L..2L) ESPIPE else EBADF) }
            "clock_time_get" -> { inst, a ->
                inst.memory().writeLong(a[2].toInt(), System.nanoTime())
                ok()
            }
            "random_get" -> { inst, a ->
                inst.memory().write(a[0].toInt(), ByteArray(a[1].toInt()) { (it * 31 + 7).toByte() })
                ok()
            }
            "args_sizes_get" -> { inst, a ->
                inst.memory().writeI32(a[0].toInt(), args.size)
                inst.memory().writeI32(a[1].toInt(), args.sumOf { it.toByteArray().size + 1 })
                ok()
            }
            "args_get" -> { inst, a ->
                var p = a[1].toInt()
                args.forEachIndexed { i, s ->
                    inst.memory().writeI32(a[0].toInt() + i * 4, p)
                    val b = s.toByteArray() + 0
                    inst.memory().write(p, b)
                    p += b.size
                }
                ok()
            }
            "environ_sizes_get" -> { inst, a ->
                inst.memory().writeI32(a[0].toInt(), 0)
                inst.memory().writeI32(a[1].toInt(), 0)
                ok()
            }
            "environ_get" -> { _, _ -> ok() }
            "proc_exit" -> { _, a -> throw ProcExit(a[0].toInt()) }
            else -> null
        }
        else -> null
    }

    private fun fdWrite(mem: Memory, fd: Int, iovs: Int, count: Int, nwritten: Int): Int {
        if (fd != 1 && fd != 2) return EBADF
        var total = 0
        synchronized(stdout) {
            for (i in 0 until count) {
                val buf = mem.readInt(iovs + i * 8)
                val len = mem.readInt(iovs + i * 8 + 4)
                stdout.write(mem.readBytes(buf, len))
                total += len
            }
        }
        mem.writeI32(nwritten, total)
        return 0
    }

    private fun fdRead(mem: Memory, fd: Int, iovs: Int, count: Int, nread: Int): Int {
        if (fd != 0) return EBADF
        var want = 0
        for (i in 0 until count) want += mem.readInt(iovs + i * 8 + 4)
        val data = readStdin(want) ?: return 0
        var off = 0
        for (i in 0 until count) {
            if (off >= data.size) break
            val buf = mem.readInt(iovs + i * 8)
            val len = minOf(mem.readInt(iovs + i * 8 + 4), data.size - off)
            mem.write(buf, data, off, len)
            off += len
        }
        mem.writeI32(nread, data.size)
        return 0
    }

    private fun fdstat(mem: Memory, fd: Int, buf: Int): Int {
        if (fd !in 0..2) return EBADF
        mem.writeByte(buf, 2) // 文字デバイス（端末）
        mem.writeShort(buf + 2, 0)
        mem.writeLong(buf + 8, TTY_RIGHTS)
        mem.writeLong(buf + 16, TTY_RIGHTS)
        return 0
    }

    companion object {
        const val WASI: String = "wasi_snapshot_preview1"
        const val EBADF: Int = 8
        const val ENOSYS: Int = 52
        const val ESPIPE: Int = 70
        const val TTY_RIGHTS: Long = -1L and ((1L shl 2) or (1L shl 5)).inv()

        /** 待つ import（asyncify の対象）。PoC のプログラムが待つのは fd_read だけ。本番は poll_oneoff なども入る */
        val ASYNC_IMPORTS: Set<String> = setOf("$WASI.fd_read", "$WASI.poll_oneoff")
    }
}
