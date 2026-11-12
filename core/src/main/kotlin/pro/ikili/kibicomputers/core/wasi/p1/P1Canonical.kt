package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.event.EventBatch
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.fd.PipeFds
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.ProcessArgs
import pro.ikili.kibicomputers.core.kernel.tty.TtySizeInfo
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.wasi.WasiUtf8
import pro.ikili.kibicomputers.core.wasi.kibi.KibiProcess
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * p1 向けの独自拡張の import（#73。ADR 0073 の Q2-B）に要る、Canonical ABI の最小限の lift・lower（`kibi:io` は #75）。
 * Component Model の層（#264）ができたら置き換える。
 *
 * 配置は Canonical ABI どおり（リトルエンディアン、wasm32）:
 * - `list<T>`・`string`: `{ ptr: i32 @0, len: i32 @4 }`（8 / 4）
 * - `result<own<_>, error-code>`・`result<u32, error-code>`: `{ tag: u8 @0, payload @4 }`（ok ならハンドル・値 i32、err なら判別子 u8。8 / 4）
 * - `result<_, error-code>`: `{ tag: u8 @0, err: u8 @1 }`（2 / 1）
 * - `result<u64, error-code>`: `{ tag: u8 @0, ok: u64 @8 | err: u8 @8 }`（16 / 8。#426）
 * - `result<pipe-ends, error-code>`: `{ tag: u8 @0, ok: { read: u32 @4, write: u32 @8 } | err: u8 @4 }`（12 / 4）
 * - `result<mode, error-code>`（8 個までのフラグ）: `{ tag: u8 @0, ok: u8 @1 | err: u8 @1 }`（2 / 1。#427）
 * - `result<size, error-code>`: `{ tag: u8 @0, ok: { columns: u32 @8, rows: u32 @12, generation: u64 @16 } | err: u8 @8 }`（24 / 8。#427）
 * - `result<event-batch, error-code>`: `{ tag: u8 @0, ok: { events.ptr @8, events.len @12, dropped: u64 @16 } | err: u8 @8 }`（24 / 8）
 * - `event`: `{ kind @0, source @8, values @16 }`（24 / 4）
 * - `event-value`: `{ tag: u8 @0, payload @8 }`（s64・f64 は 8 バイト、bool は u8、text・bytes は (ptr, len)。16 / 8）
 * - `tuple<string, string>`（`kibi:process` の env の要素）: `{ name.ptr @0, name.len @4, value.ptr @8, value.len @12 }`（16 / 4。#74）
 * - `fd-assign`: `{ fd: u32 @0, source.tag: u8 @4, source.parent: u32 @8 }`（12 / 4。#74）
 * - `result<option<exit-status>, error-code>`: `{ tag @0, ok: { opt_tag @4, status_tag @8, exited: u32 @12 | killed: { sig_is_some @12, sig @13 } }
 *   | err: u8 @4 }`（16 / 4。#74）
 * - `string` の結果（`get-cwd`）: `{ ptr @0, len @4 }`（8 / 4。`cabi_realloc` で場所を取る。#74）
 * - `list<process-info>` の結果（`list-processes`）: `{ ptr @0, len @4 }`（8 / 4）。`process-info` は
 *   `{ pid @0, parent-pid @4, state.tag: u8 @8, state.finished: exit-status @12, args.ptr @20, args.len @24 }`（28 / 4。#452）
 *
 * ゲストの引数の誤り（上限・UTF-8・範囲外）は [FsException]（`INVAL`・`FAULT`）で返し、呼んだ側が `error-code` にする。
 * 書き先（retptr）や `cabi_realloc` が返した場所の誤りは、書き先がないのでトラップ（Canonical ABI と同じ）。
 * 引数の `list` の ptr が要素の align に揃っていないときもトラップ（Canonical ABI の lift と同じ。長さ 0 でも、その list のほかの検査より先に確かめる。#430）。
 * ただし list を複数取る関数（`spawn`）は引数の順に読むので、先に読む引数の誤り（error-code）が、あとの list の align のトラップより先に返ることがある（ADR 0073・0074）。
 */
internal object P1Canonical {
    /** ゲストの `cabi_realloc(0, 0, align, size)`。p1 の層が `ctx.callGuest { }` の中で呼ぶ */
    fun interface Realloc {
        fun alloc(align: Int, size: Int): Int
    }

    /** `result<own<_>, error-code>` の大きさ・align */
    const val HANDLE_RESULT_SIZE: Int = 8
    const val HANDLE_RESULT_ALIGN: Int = 4

    /** `result<_, error-code>` の大きさ・align */
    const val UNIT_RESULT_SIZE: Int = 2
    const val UNIT_RESULT_ALIGN: Int = 1

    /** `result<u64, error-code>`（`kibi:kernel/timers` の `read`）の大きさ・align */
    const val U64_RESULT_SIZE: Int = 16
    const val U64_RESULT_ALIGN: Int = 8

    /** `result<pipe-ends, error-code>`（`kibi:io/pipe.create`）の大きさ・align */
    const val PIPE_RESULT_SIZE: Int = 12
    const val PIPE_RESULT_ALIGN: Int = 4

    /** `result<flags, error-code>`（フラグが 8 個まで。`kibi:io/tty.get-mode`）の大きさ・align */
    const val U8_RESULT_SIZE: Int = 2
    const val U8_RESULT_ALIGN: Int = 1

    /** `result<size, error-code>`（`kibi:io/tty.get-size`）の大きさ・align */
    const val TTY_SIZE_RESULT_SIZE: Int = 24
    const val TTY_SIZE_RESULT_ALIGN: Int = 8

    /** `result<event-batch, error-code>` の大きさ・align */
    const val BATCH_RESULT_SIZE: Int = 24
    const val BATCH_RESULT_ALIGN: Int = 8

    /** `event` の大きさ・align */
    const val EVENT_SIZE: Int = 24
    const val EVENT_ALIGN: Int = 4

    /** `event-value` の大きさ・align */
    const val VALUE_SIZE: Int = 16
    const val VALUE_ALIGN: Int = 8

    /** `result<option<exit-status>, error-code>`（`kibi:process` の `status`）の大きさ・align */
    const val STATUS_RESULT_SIZE: Int = 16
    const val STATUS_RESULT_ALIGN: Int = 4

    /** `string` の結果（`get-cwd`）の大きさ・align */
    const val STRING_RESULT_SIZE: Int = 8
    const val STRING_RESULT_ALIGN: Int = 4

    /** `list<T>` の結果（`list-processes`）の大きさ・align */
    const val LIST_RESULT_SIZE: Int = 8
    const val LIST_RESULT_ALIGN: Int = 4

    /** `process-info`（`kibi:process` の `list-processes` の要素。#452）の大きさ・align */
    const val PROCESS_INFO_SIZE: Int = 28
    const val PROCESS_INFO_ALIGN: Int = 4

    /** `string`（`list<string>` の要素）の大きさ */
    private const val STRING_SIZE: Int = 8

    /** 引数の `list` の要素（`string`・`tuple<string, string>`・`fd-assign`）の align。どれも 4 */
    private const val LIST_ELEMENT_ALIGN: Int = 4

    /** `tuple<string, string>`（env の要素）の大きさ */
    const val ENV_ENTRY_SIZE: Int = 16

    /** `fd-assign` の大きさ */
    const val FD_ASSIGN_SIZE: Int = 12

    /** `event-value` の判別子 */
    const val VALUE_INTEGER: Int = 0
    const val VALUE_FLOAT: Int = 1
    const val VALUE_BOOLEAN: Int = 2
    const val VALUE_TEXT: Int = 3
    const val VALUE_BYTES: Int = 4

    /**
     * `list<string>` を読む。[ptr] が align 4 に揃っていなければトラップ、[len] が [maxCount] を超えたら要素を読まずに `INVAL`、要素のバイト数が [maxBytes] の外なら中身を読まずに `INVAL`、
     * 中身が正しい UTF-8 でなければ `INVAL`、配列・要素が線形メモリの外なら `FAULT`。
     * 確かめる順は ptr の align → len → 配列の範囲 → 各要素について（バイト数 → 範囲 → UTF-8）。ホストが読むのは最大で maxCount × (8 + maxBytes.last) バイト
     */
    @Throws(FsException::class)
    fun liftStringList(memory: WasmMemory, ptr: Int, len: Int, maxCount: Int, maxBytes: IntRange): List<String> {
        checkListAlign(ptr, LIST_ELEMENT_ALIGN)
        val count = u32(len)
        if (count > maxCount) fsError(Errno.INVAL, "要素は $maxCount 個まで（$count）")
        if (!P1.inBounds(memory, ptr, count * 8)) fsError(Errno.FAULT, "list の範囲が線形メモリの外")
        if (count == 0L) return emptyList()
        val out = ArrayList<String>(count.toInt())
        for (i in 0 until count.toInt()) {
            val base = ptr + i * 8
            val sp = memory.readI32(base)
            val sl = u32(memory.readI32(base + 4))
            if (sl < maxBytes.first || sl > maxBytes.last) fsError(Errno.INVAL, "文字列は ${maxBytes.first}..${maxBytes.last} バイト（$sl）")
            if (!P1.inBounds(memory, sp, sl)) fsError(Errno.FAULT, "文字列の範囲が線形メモリの外")
            out += decodeUtf8(memory.read(sp, sl.toInt()))
        }
        return out
    }

    /**
     * `string`（引数の (ptr, len)）を読む。バイト数が [maxBytes] を超えたら中身を読まずに [tooLong]、範囲外なら `FAULT`、
     * 正しい UTF-8 でなければ `INVAL`
     */
    @Throws(FsException::class)
    fun liftString(memory: WasmMemory, ptr: Int, len: Int, maxBytes: Int, tooLong: Errno): String {
        val n = u32(len)
        if (n > maxBytes) fsError(tooLong, "文字列は $maxBytes バイトまで（$n）")
        if (!P1.inBounds(memory, ptr, n)) fsError(Errno.FAULT, "文字列の範囲が線形メモリの外")
        return decodeUtf8(memory.read(ptr, n.toInt()))
    }

    /**
     * 引数と環境変数の合計の大きさの残り（[ProcessArgs.MAX_BYTES] から。数え方も [ProcessArgs.check] と同じ: 文字列のバイト数と終わりの NUL、
     * 環境変数は `名前=値`）。中身を読む前に [take] で引き、足りなければ `TOOBIG`
     */
    class ArgBudget(var remaining: Long = ProcessArgs.MAX_BYTES.toLong()) {
        @Throws(FsException::class)
        fun take(bytes: Long) {
            if (bytes > remaining) fsError(Errno.TOOBIG, "引数と環境変数が大きすぎる（上限 ${ProcessArgs.MAX_BYTES} バイト）")
            remaining -= bytes
        }
    }

    /**
     * `kibi:process` の `args`（`list<string>`）を読む。[ptr] が align 4 に揃っていなければトラップ、[len] が [maxCount] を超えたら要素を読まずに `TOOBIG`、配列・要素が範囲外なら `FAULT`、
     * 要素の中身を読む前に [budget] から引き（足りなければ `TOOBIG`）、正しい UTF-8 でなければ `INVAL`
     */
    @Throws(FsException::class)
    fun liftArgList(memory: WasmMemory, ptr: Int, len: Int, maxCount: Int, budget: ArgBudget): List<String> {
        checkListAlign(ptr, LIST_ELEMENT_ALIGN)
        val count = u32(len)
        if (count > maxCount) fsError(Errno.TOOBIG, "引数は $maxCount 個まで（$count）")
        if (!P1.inBounds(memory, ptr, count * 8)) fsError(Errno.FAULT, "list の範囲が線形メモリの外")
        val out = ArrayList<String>(count.toInt())
        for (i in 0 until count.toInt()) {
            val base = ptr + i * 8
            val sp = memory.readI32(base)
            val sl = u32(memory.readI32(base + 4))
            budget.take(sl + 1)
            if (!P1.inBounds(memory, sp, sl)) fsError(Errno.FAULT, "文字列の範囲が線形メモリの外")
            out += decodeUtf8(memory.read(sp, sl.toInt()))
        }
        return out
    }

    /**
     * `kibi:process` の `env`（`list<tuple<string, string>>`）を読む。[ptr] が align 4 に揃っていなければトラップ、[len] が [maxCount] を超えたら要素を読まずに `TOOBIG`、
     * 配列・要素が範囲外なら `FAULT`、組の中身を読む前に [budget] から引き（足りなければ `TOOBIG`）、正しい UTF-8 でなければ `INVAL`
     */
    @Throws(FsException::class)
    fun liftEnvList(memory: WasmMemory, ptr: Int, len: Int, maxCount: Int, budget: ArgBudget): List<Pair<String, String>> {
        checkListAlign(ptr, LIST_ELEMENT_ALIGN)
        val count = u32(len)
        if (count > maxCount) fsError(Errno.TOOBIG, "環境変数は $maxCount 組まで（$count）")
        if (!P1.inBounds(memory, ptr, count * ENV_ENTRY_SIZE)) fsError(Errno.FAULT, "list の範囲が線形メモリの外")
        val out = ArrayList<Pair<String, String>>(count.toInt())
        for (i in 0 until count.toInt()) {
            val base = ptr + i * ENV_ENTRY_SIZE
            val np = memory.readI32(base)
            val nl = u32(memory.readI32(base + 4))
            val vp = memory.readI32(base + 8)
            val vl = u32(memory.readI32(base + 12))
            budget.take(nl + 1 + vl + 1)
            if (!P1.inBounds(memory, np, nl) || !P1.inBounds(memory, vp, vl)) fsError(Errno.FAULT, "文字列の範囲が線形メモリの外")
            out += decodeUtf8(memory.read(np, nl.toInt())) to decodeUtf8(memory.read(vp, vl.toInt()))
        }
        return out
    }

    /**
     * `kibi:process` の `fds`（`list<fd-assign>`）を読む。[ptr] が align 4 に揃っていなければトラップ、[len] が [maxCount] を超えたら要素を読まずに `INVAL`、配列が範囲外なら `FAULT`、
     * `fd-source` の判別子が 0（`parent`）・1（`closed`）でなければ `INVAL`。値は符号なしで広げる
     */
    @Throws(FsException::class)
    fun liftFdAssigns(memory: WasmMemory, ptr: Int, len: Int, maxCount: Int): List<KibiProcess.FdAssign> {
        checkListAlign(ptr, LIST_ELEMENT_ALIGN)
        val count = u32(len)
        if (count > maxCount) fsError(Errno.INVAL, "fds は $maxCount 個まで（$count）")
        if (!P1.inBounds(memory, ptr, count * FD_ASSIGN_SIZE)) fsError(Errno.FAULT, "list の範囲が線形メモリの外")
        val out = ArrayList<KibiProcess.FdAssign>(count.toInt())
        for (i in 0 until count.toInt()) {
            val base = ptr + i * FD_ASSIGN_SIZE
            val fd = u32(memory.readI32(base))
            out += when (val tag = memory.readU8(base + 4)) {
                0 -> KibiProcess.FdAssign(fd, u32(memory.readI32(base + 8)))
                1 -> KibiProcess.FdAssign(fd, null)
                else -> fsError(Errno.INVAL, "fd-source の判別子が不正（$tag）")
            }
        }
        return out
    }

    /** `option<u32>`（平らにした判別子と値）。値は符号なしで広げる。判別子が 0・1 でなければ `INVAL` */
    @Throws(FsException::class)
    fun liftOptionU32(isSome: Int, value: Int): Long? = when (isSome) {
        0 -> null
        1 -> u32(value)
        else -> fsError(Errno.INVAL, "option の判別子が不正（$isSome）")
    }

    /** `option<T>` の平らにした判別子。0 なら `false`（none）、1 なら `true`（some）、ほかは `null`（呼んだ側が `INVAL` にする） */
    fun liftOptionDisc(isSome: Int): Boolean? = when (isSome) {
        0 -> false
        1 -> true
        else -> null
    }

    /**
     * `flags`（[count] 個。32 個まで）の引数（平らにした i32）。宣言した下位 [count] ビットだけを読み、上のビットは捨てる
     * （Canonical ABI の lift と同じ。エラーにしない）
     */
    fun liftFlags(value: Int, count: Int): Int {
        require(count in 1..32) { "flags は 1..32 個（$count）" }
        return if (count == 32) value else value and ((1 shl count) - 1)
    }

    /** `u64` の引数（core の i64 のビット列）を 0..`Long.MAX_VALUE` にする。2^63 以上（`Long` で負）は `Long.MAX_VALUE` に頭打ち */
    fun u64Arg(v: Long): Long = if (v < 0) Long.MAX_VALUE else v

    /** `result<u64, error-code>` を [retPtr] に書く。[value] が `null` なら err（[error] は判別子）。使わない所は 0 にする */
    fun lowerU64Result(memory: WasmMemory, retPtr: Int, value: Long?, error: Int) {
        checkStore(memory, retPtr, U64_RESULT_SIZE, U64_RESULT_ALIGN)
        memory.write(retPtr, ByteArray(U64_RESULT_SIZE))
        if (value != null) {
            memory.writeI64(retPtr + 8, value)
        } else {
            memory.writeU8(retPtr, 1)
            memory.writeU8(retPtr + 8, error)
        }
    }

    /**
     * 1 バイトの ok を持つ `result<T, error-code>`（`T` は 8 個までの `flags` など）を [retPtr] に書く。
     * [value] が `null` なら err（[error] は判別子）。ok も err もオフセット 1
     */
    fun lowerU8Result(memory: WasmMemory, retPtr: Int, value: Int?, error: Int) {
        checkStore(memory, retPtr, U8_RESULT_SIZE, U8_RESULT_ALIGN)
        memory.writeU8(retPtr, if (value == null) 1 else 0)
        memory.writeU8(retPtr + 1, value ?: error)
    }

    /** `result<size, error-code>`（`kibi:io/tty.get-size`）を [retPtr] に書く。[info] が `null` なら err（[error] は判別子）。使わない所は 0 にする */
    fun lowerTtySizeResult(memory: WasmMemory, retPtr: Int, info: TtySizeInfo?, error: Int) {
        checkStore(memory, retPtr, TTY_SIZE_RESULT_SIZE, TTY_SIZE_RESULT_ALIGN)
        memory.write(retPtr, ByteArray(TTY_SIZE_RESULT_SIZE))
        if (info != null) {
            memory.writeI32(retPtr + 8, info.size.columns)
            memory.writeI32(retPtr + 12, info.size.rows)
            memory.writeI64(retPtr + 16, info.generation)
        } else {
            memory.writeU8(retPtr, 1)
            memory.writeU8(retPtr + 8, error)
        }
    }

    /** `result<own<_>, error-code>` を [retPtr] に書く。[handle] が `null` なら err（[error] は判別子） */
    fun lowerHandleResult(memory: WasmMemory, retPtr: Int, handle: Int?, error: Int) {
        checkStore(memory, retPtr, HANDLE_RESULT_SIZE, HANDLE_RESULT_ALIGN)
        if (handle != null) {
            memory.writeU8(retPtr, 0)
            memory.writeI32(retPtr + 4, handle)
        } else {
            memory.writeU8(retPtr, 1)
            memory.writeU8(retPtr + 4, error)
        }
    }

    /**
     * `result<option<exit-status>, error-code>` を [retPtr] に書く。[error] が `null` でなければ err（判別子）、そうでなければ ok で、
     * [status] が `null` なら none。使わない所は 0 にする
     */
    fun lowerStatusResult(memory: WasmMemory, retPtr: Int, status: ExitStatus?, error: Int?) {
        checkStore(memory, retPtr, STATUS_RESULT_SIZE, STATUS_RESULT_ALIGN)
        memory.write(retPtr, ByteArray(STATUS_RESULT_SIZE))
        if (error != null) {
            memory.writeU8(retPtr, 1)
            memory.writeU8(retPtr + 4, error)
            return
        }
        if (status == null) return
        memory.writeU8(retPtr + 4, 1)
        writeExitStatus(memory, retPtr + 8, status)
    }

    /**
     * `exit-status`（8 / 4）を [at] に書く: 判別子 u8 @0、`exited` は u32 @4、`killed` は `is_some` @4・`sig` @5。
     * 詰め物と使わない payload は書かないので、呼ぶ側が先に 0 で埋めておく
     */
    private fun writeExitStatus(memory: WasmMemory, at: Int, status: ExitStatus) {
        val tag = KibiProcess.statusTag(status)
        memory.writeU8(at, tag)
        when (tag) {
            0 -> memory.writeI32(at + 4, status.code)
            1 -> status.signal?.let {
                memory.writeU8(at + 4, 1)
                memory.writeU8(at + 5, KibiProcess.SIGNALS.indexOf(it))
            }
        }
    }

    /**
     * `list<process-info>` の結果（`list-processes`。#452、ADR 0452）を [retPtr] に書く。retptr の範囲と align を先に確かめ、
     * 場所は Canonical ABI の lower の順に [realloc] で取る: 外側の配列（align 4、28 × 件数）→ 要素ごとに `args` の配列
     * （align 4、8 × 個数）→ その各文字列（align 1）。0 件・0 バイトでも呼ぶ。
     * `process-info` の配置: `pid` @0、`parent-pid` @4、`state` の判別子 u8 @8、`finished` の `exit-status` @12（8 バイト）、
     * `args.ptr` @20、`args.len` @24。詰め物と使わない payload は 0
     */
    fun lowerProcessListResult(memory: WasmMemory, realloc: Realloc, retPtr: Int, entries: List<KibiProcess.Entry>) {
        checkStore(memory, retPtr, LIST_RESULT_SIZE, LIST_RESULT_ALIGN)
        val listPtr = alloc(memory, realloc, PROCESS_INFO_ALIGN, allocSize(entries.size, PROCESS_INFO_SIZE))
        for ((i, e) in entries.withIndex()) {
            val at = listPtr + i * PROCESS_INFO_SIZE
            memory.write(at, ByteArray(PROCESS_INFO_SIZE))
            memory.writeI32(at, e.pid)
            memory.writeI32(at + 4, e.parentPid)
            memory.writeU8(at + 8, e.state.ordinal)
            e.exit?.let { writeExitStatus(memory, at + 12, it) }
            val argsPtr = alloc(memory, realloc, LIST_ELEMENT_ALIGN, allocSize(e.args.size, STRING_SIZE))
            for ((j, arg) in e.args.withIndex()) {
                val (p, l) = lowerBytes(memory, realloc, arg)
                memory.writeI32(argsPtr + j * STRING_SIZE, p)
                memory.writeI32(argsPtr + j * STRING_SIZE + 4, l)
            }
            memory.writeI32(at + 20, argsPtr)
            memory.writeI32(at + 24, e.args.size)
        }
        memory.writeI32(retPtr, listPtr)
        memory.writeI32(retPtr + 4, entries.size)
    }

    /** 配列の大きさ（[count] × [elementSize]）。`Long` で計算し、`Int` を超えるならトラップ（黙って小さく取らない） */
    private fun allocSize(count: Int, elementSize: Int): Int {
        val size = count.toLong() * elementSize
        if (size > Int.MAX_VALUE) throw WasmTrapException(WasmTrapKind.OTHER, "結果の配列が大きすぎる（$size バイト）")
        return size.toInt()
    }

    /** `string` の結果を [retPtr] に書く（中身の場所は [realloc] で取る。大きさ 0 でも呼ぶ） */
    fun lowerStringResult(memory: WasmMemory, realloc: Realloc, retPtr: Int, value: String) {
        checkStore(memory, retPtr, STRING_RESULT_SIZE, STRING_RESULT_ALIGN)
        val (p, l) = lowerBytes(memory, realloc, utf8(value))
        memory.writeI32(retPtr, p)
        memory.writeI32(retPtr + 4, l)
    }

    /** `result<_, error-code>` を [retPtr] に書く。[error] が `null` なら ok（err の場所は 0 にする）、そうでなければ err（判別子） */
    fun lowerUnitResult(memory: WasmMemory, retPtr: Int, error: Int?) {
        checkStore(memory, retPtr, UNIT_RESULT_SIZE, UNIT_RESULT_ALIGN)
        memory.writeU8(retPtr, if (error == null) 0 else 1)
        memory.writeU8(retPtr + 1, error ?: 0)
    }

    /** `result<pipe-ends, error-code>` を [retPtr] に書く。[ends] が `null` なら err（[error] は判別子） */
    fun lowerPipeResult(memory: WasmMemory, retPtr: Int, ends: PipeFds?, error: Int) {
        checkStore(memory, retPtr, PIPE_RESULT_SIZE, PIPE_RESULT_ALIGN)
        if (ends != null) {
            memory.writeU8(retPtr, 0)
            memory.writeI32(retPtr + 4, ends.read)
            memory.writeI32(retPtr + 8, ends.write)
        } else {
            memory.writeU8(retPtr, 1)
            memory.writeU8(retPtr + 4, error)
        }
    }

    /**
     * `result<event-batch, error-code>` を [retPtr] に書く。[batch] が `null` なら err（[error] は判別子）。
     * ok なら、リストと文字列の場所を [realloc] で取ってから書く（Canonical ABI の順: 先にイベントの配列、続けて各イベントの中身）
     */
    fun lowerBatchResult(memory: WasmMemory, realloc: Realloc, retPtr: Int, batch: EventBatch?, error: Int) {
        checkStore(memory, retPtr, BATCH_RESULT_SIZE, BATCH_RESULT_ALIGN)
        if (batch == null) {
            memory.writeU8(retPtr, 1)
            memory.writeU8(retPtr + 8, error)
            return
        }
        val events = batch.events
        val listPtr = alloc(memory, realloc, EVENT_ALIGN, events.size * EVENT_SIZE)
        for ((i, e) in events.withIndex()) {
            val at = listPtr + i * EVENT_SIZE
            val (kp, kl) = lowerBytes(memory, realloc, utf8(e.type))
            memory.writeI32(at, kp)
            memory.writeI32(at + 4, kl)
            val (sp, sl) = lowerBytes(memory, realloc, utf8(e.source))
            memory.writeI32(at + 8, sp)
            memory.writeI32(at + 12, sl)
            val valuesPtr = alloc(memory, realloc, VALUE_ALIGN, e.values.size * VALUE_SIZE)
            for ((j, v) in e.values.withIndex()) lowerValue(memory, realloc, valuesPtr + j * VALUE_SIZE, v)
            memory.writeI32(at + 16, valuesPtr)
            memory.writeI32(at + 20, e.values.size)
        }
        memory.writeU8(retPtr, 0)
        memory.writeI32(retPtr + 8, listPtr)
        memory.writeI32(retPtr + 12, events.size)
        memory.writeI64(retPtr + 16, batch.dropped)
    }

    private fun lowerValue(memory: WasmMemory, realloc: Realloc, at: Int, v: EventValue) {
        // 判別子の後ろの詰め物も 0 にする（ゲストが読まない所でも、前の中身を残さない）
        memory.write(at, ByteArray(VALUE_SIZE))
        when (v) {
            is EventValue.Integer -> {
                memory.writeU8(at, VALUE_INTEGER)
                memory.writeI64(at + 8, v.value)
            }
            is EventValue.Float -> {
                memory.writeU8(at, VALUE_FLOAT)
                memory.writeI64(at + 8, java.lang.Double.doubleToRawLongBits(v.value))
            }
            is EventValue.Boolean -> {
                memory.writeU8(at, VALUE_BOOLEAN)
                memory.writeU8(at + 8, if (v.value) 1 else 0)
            }
            is EventValue.Text -> {
                val (p, l) = lowerBytes(memory, realloc, utf8(v.value))
                memory.writeU8(at, VALUE_TEXT)
                memory.writeI32(at + 8, p)
                memory.writeI32(at + 12, l)
            }
            is EventValue.Bytes -> {
                val (p, l) = lowerBytes(memory, realloc, v.toByteArray())
                memory.writeU8(at, VALUE_BYTES)
                memory.writeI32(at + 8, p)
                memory.writeI32(at + 12, l)
            }
        }
    }

    /** [bytes] の場所を取って書き、(ptr, len) を返す */
    private fun lowerBytes(memory: WasmMemory, realloc: Realloc, bytes: ByteArray): Pair<Int, Int> {
        val p = alloc(memory, realloc, 1, bytes.size)
        if (bytes.isNotEmpty()) memory.write(p, bytes)
        return p to bytes.size
    }

    /** `cabi_realloc` で場所を取る。返した場所が線形メモリの外・align に揃っていなければトラップ */
    private fun alloc(memory: WasmMemory, realloc: Realloc, align: Int, size: Int): Int {
        val p = realloc.alloc(align, size)
        if (u32(p) % align != 0L) throw WasmTrapException(WasmTrapKind.OTHER, "cabi_realloc が align $align に揃っていない場所を返した（$p）")
        if (!P1.inBounds(memory, p, size.toLong())) throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, "cabi_realloc が線形メモリの外を返した（$p）")
        return p
    }

    /**
     * 引数の `list` の [ptr] が要素の [align] に揃っているか確かめる（違えばトラップ。Canonical ABI の lift と同じ）。
     * 長さ 0 でも確かめる。メモリは読まない
     */
    private fun checkListAlign(ptr: Int, align: Int) {
        if (u32(ptr) % align != 0L) throw WasmTrapException(WasmTrapKind.OTHER, "list の場所が align $align に揃っていない（$ptr）")
    }

    /** 書き先 [ptr] が範囲の中で align に揃っているか確かめる（違えばトラップ） */
    private fun checkStore(memory: WasmMemory, ptr: Int, size: Int, align: Int) {
        if (u32(ptr) % align != 0L) throw WasmTrapException(WasmTrapKind.OTHER, "結果の書き先が align $align に揃っていない（$ptr）")
        if (!P1.inBounds(memory, ptr, size.toLong())) throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, "結果の書き先が線形メモリの外（$ptr）")
    }

    /** UTF-8 として読む。正しくなければ `INVAL`（置き換えない） */
    @Throws(FsException::class)
    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        fsError(Errno.INVAL, "文字列が正しい UTF-8 でない")
    }

    /** UTF-8 にする。対になっていないサロゲートは U+FFFD にする（[WasiUtf8.encode]） */
    fun utf8(s: String): ByteArray = WasiUtf8.encode(s)

    private fun u32(v: Int): Long = v.toLong() and 0xffff_ffffL
}
