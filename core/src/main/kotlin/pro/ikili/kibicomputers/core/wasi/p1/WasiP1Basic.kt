package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/**
 * WASI p1 の、ファイル以外の基本的な関数（#69）。`args_*`・`environ_*`・`clock_*`・`random_get`・`sched_yield`。
 * `proc_exit` は [WasiP1Exit] を投げて、ドライバー（[WasiP1Driver]）が終了コードにする。
 *
 * 各関数は p1 の `errno` の値を返す（成功は 0）。ゲストのメモリの範囲外は、書く前に確かめて `FAULT` にする。
 *
 * 時計:
 * - `REALTIME`: 環境の実時間（`MachineClock.realtimeNanos`）
 * - `MONOTONIC`: マシンの単調時計（[guestMonotonicNanos]。マシンを作ったときが 0。ADR 0064）
 * - `PROCESS_CPUTIME`・`THREAD_CPUTIME`: このプロセスの p1 の層を作ってからの単調時計の経過（CPU を使った時間ではなく、
 *   待っていた時間も含む。p1 のプロセスはスレッドが1つなので、2つは同じ）
 * - 分解能はどれも [CLOCK_RESOLUTION_NANOS]。`precision` は使わない
 */
internal class WasiP1Basic(private val ctx: ProcessContext) {
    /** 引数を UTF-8 にしたもの（作った後は変わらない） */
    private val argBytes: List<ByteArray> by lazy { ctx.args.map { it.toByteArray(Charsets.UTF_8) } }

    /** 環境変数を `名前=値` の UTF-8 にしたもの（作った後は変わらない） */
    private val envBytes: List<ByteArray> by lazy { ctx.env.map { (k, v) -> "$k=$v".toByteArray(Charsets.UTF_8) } }

    /** `PROCESS_CPUTIME`・`THREAD_CPUTIME` の起点（マシンの単調時計） */
    private val startNanos: Long = guestMonotonicNanos()

    // ---- 引数・環境変数 ----

    /** `args_sizes_get(argc*, argv_buf_size*)` */
    fun argsSizesGet(memory: WasmMemory, countPtr: Int, sizePtr: Int): Int = sizesGet(memory, argBytes, countPtr, sizePtr)

    /** `args_get(argv*, argv_buf*)` */
    fun argsGet(memory: WasmMemory, argvPtr: Int, bufPtr: Int): Int = stringsGet(memory, argBytes, argvPtr, bufPtr)

    /** `environ_sizes_get(environc*, environ_buf_size*)` */
    fun environSizesGet(memory: WasmMemory, countPtr: Int, sizePtr: Int): Int = sizesGet(memory, envBytes, countPtr, sizePtr)

    /** `environ_get(environ*, environ_buf*)` */
    fun environGet(memory: WasmMemory, environPtr: Int, bufPtr: Int): Int = stringsGet(memory, envBytes, environPtr, bufPtr)

    private fun sizesGet(memory: WasmMemory, strings: List<ByteArray>, countPtr: Int, sizePtr: Int): Int {
        if (!P1.inBounds(memory, countPtr, 4) || !P1.inBounds(memory, sizePtr, 4)) return Errno.FAULT.code
        memory.writeI32(countPtr, strings.size)
        memory.writeI32(sizePtr, bufSize(strings).toInt())
        return 0
    }

    /** ポインタの並びを [listPtr] に、NUL で終わる文字列を続けて [bufPtr] に書く */
    private fun stringsGet(memory: WasmMemory, strings: List<ByteArray>, listPtr: Int, bufPtr: Int): Int {
        val total = bufSize(strings)
        if (!P1.inBounds(memory, listPtr, strings.size * 4L) || !P1.inBounds(memory, bufPtr, total)) return Errno.FAULT.code
        val buf = ByteArray(total.toInt())
        var at = 0
        strings.forEachIndexed { i, s ->
            memory.writeI32(listPtr + i * 4, bufPtr + at)
            s.copyInto(buf, at)
            at += s.size + 1
        }
        memory.write(bufPtr, buf)
        ctx.chargeFuel(total / WasiP1Fd.BYTES_PER_FUEL)
        return 0
    }

    private fun bufSize(strings: List<ByteArray>): Long = strings.sumOf { it.size + 1L }

    // ---- 時計 ----

    /** `clock_res_get(id, resolution*)` */
    fun clockResGet(memory: WasmMemory, id: Int, resolutionPtr: Int): Int {
        if (id !in P1.CLOCK_REALTIME..P1.CLOCK_THREAD_CPUTIME) return Errno.INVAL.code
        if (!P1.inBounds(memory, resolutionPtr, 8)) return Errno.FAULT.code
        memory.writeI64(resolutionPtr, CLOCK_RESOLUTION_NANOS)
        return 0
    }

    /** `clock_time_get(id, precision, time*)` */
    fun clockTimeGet(memory: WasmMemory, id: Int, timePtr: Int): Int {
        val now = when (id) {
            P1.CLOCK_REALTIME -> ctx.environment.clock.realtimeNanos()
            P1.CLOCK_MONOTONIC -> guestMonotonicNanos()
            P1.CLOCK_PROCESS_CPUTIME, P1.CLOCK_THREAD_CPUTIME -> maxOf(0L, guestMonotonicNanos() - startNanos)
            else -> return Errno.INVAL.code
        }
        if (!P1.inBounds(memory, timePtr, 8)) return Errno.FAULT.code
        memory.writeI64(timePtr, now)
        return 0
    }

    /** マシンの単調時計（ナノ秒。マシンを作ったときが 0。負にはしない） */
    private fun guestMonotonicNanos(): Long = ctx.monotonicNanos()

    // ---- 乱数 ----

    /** `random_get(buf*, buf_len)`。環境の乱数の元（`EntropySource`）で埋める */
    fun randomGet(memory: WasmMemory, bufPtr: Int, length: Int): Int {
        val len = P1.u32(length.toLong())
        if (!P1.inBounds(memory, bufPtr, len)) return Errno.FAULT.code
        val chunk = ByteArray(minOf(len, RANDOM_CHUNK.toLong()).toInt())
        var done = 0L
        while (done < len) {
            val n = minOf(len - done, chunk.size.toLong()).toInt()
            val part = if (n == chunk.size) chunk else ByteArray(n)
            ctx.environment.entropy.fill(part)
            memory.write((P1.u32(bufPtr.toLong()) + done).toInt(), part, 0, n)
            done += n
        }
        ctx.chargeFuel(len / WasiP1Fd.BYTES_PER_FUEL)
        return 0
    }

    // ---- スケジューリング ----

    /** `sched_yield()`。割り込みは燃料で行うので、ここでは CPU を手放さない（呼び出しの燃料だけかかる） */
    fun schedYield(): Int = 0

    companion object {
        /** 時計の分解能（ナノ秒） */
        const val CLOCK_RESOLUTION_NANOS: Long = 1_000

        /** `random_get` で一度に埋める大きさ */
        private const val RANDOM_CHUNK: Int = 64 * 1024
    }
}

/**
 * `proc_exit(rval)` で投げる。ゲストの呼び出しを抜けて、[WasiP1Driver] が [code] を終了コードにする。
 * スタックトレースは持たない（例外ではなく、制御の移動に使うため）
 */
internal class WasiP1Exit(val code: Int) : RuntimeException("proc_exit($code)", null, false, false)
