package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.kibi.KibiClock
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import pro.ikili.kibicomputers.core.wasi.kibi.KibiEvents
import pro.ikili.kibicomputers.core.wasi.kibi.KibiFs
import pro.ikili.kibicomputers.core.wasi.kibi.KibiIo
import pro.ikili.kibicomputers.core.wasi.kibi.KibiMemory
import pro.ikili.kibicomputers.core.wasi.kibi.KibiPower
import pro.ikili.kibicomputers.core.wasi.kibi.KibiProcess
import pro.ikili.kibicomputers.core.wasi.kibi.KibiTimers
import pro.ikili.kibicomputers.core.wasi.kibi.KibiTty

/**
 * 独自拡張（`kibi:*` の WIT。`core/src/main/wit/`）を p1 のプロセスに import として出す層（#408・#73・#75・#426・#427。ADR 0073・0075・0426・0427）。
 *
 * import は WIT から Canonical ABI の core の形そのままに導く（Q2-B）: モジュール名は `<ns>:<pkg>/<interface>@<版>`、
 * 関数名は wit-bindgen と同じ（`[static]`・`[method]`・`[resource-drop]`）。可変長の結果はゲストの `cabi_realloc` で場所を取る。
 * resource のハンドルはカーネルの fd 番号（Q3-A）。版は完全一致だけを受け付ける（Q5-A）。
 *
 * | モジュール | 名前 | core の型 |
 * | --- | --- | --- |
 * | [EVENTS] | `[static]event-queue.open` | `(kinds_ptr, kinds_len, cap_is_some, cap, ret_ptr: i32) -> ()` |
 * | [EVENTS] | `[method]event-queue.take` | `(self, max, ret_ptr: i32) -> ()`（`cabi_realloc` が要る） |
 * | [EVENTS] | `[resource-drop]event-queue` | `(self: i32) -> ()` |
 * | [TIMERS] | `[static]timer.create` | `(events_is_some, events: i32, tag: i64, ret_ptr: i32) -> ()` |
 * | [TIMERS] | `[method]timer.set-after` | `(self: i32, duration, interval: i64, ret_ptr: i32) -> ()` |
 * | [TIMERS] | `[method]timer.set-at` | `(self: i32, instant, interval: i64, ret_ptr: i32) -> ()` |
 * | [TIMERS] | `[method]timer.cancel` | `(self, ret_ptr: i32) -> ()` |
 * | [TIMERS] | `[method]timer.read` | `(self, ret_ptr: i32) -> ()` |
 * | [TIMERS] | `[resource-drop]timer` | `(self: i32) -> ()` |
 * | [MEMORY] | `remaining` | `() -> i64` |
 * | [MEMORY] | `commit` | `(ptr, len: i32, keep: i64) -> i32` |
 * | [CLOCK] | `uptime-ticks` | `() -> i64` |
 * | [CLOCK] | `world-ticks` | `() -> i64` |
 * | [POWER] | `shutdown` | `() -> ()`（戻らない） |
 * | [POWER] | `reboot` | `(ret_ptr: i32) -> ()`（受け付けたら戻らない。断ったときだけ書く） |
 * | [IO_FD] | `dup` | `(fd, ret_ptr: i32) -> ()` |
 * | [IO_FD] | `dup-to` | `(fd, to, ret_ptr: i32) -> ()` |
 * | [IO_PIPE] | `create` | `(ret_ptr: i32) -> ()` |
 * | [IO_TTY] | `is-tty` | `(fd: i32) -> i32` |
 * | [IO_TTY] | `get-mode` | `(fd, ret_ptr: i32) -> ()` |
 * | [IO_TTY] | `set-mode` | `(fd, mode, ret_ptr: i32) -> ()` |
 * | [IO_TTY] | `get-size` | `(fd, ret_ptr: i32) -> ()` |
 * | [IO_TTY] | `get-foreground` | `(fd, ret_ptr: i32) -> ()` |
 * | [IO_TTY] | `set-foreground` | `(fd, pid, ret_ptr: i32) -> ()` |
 * | [FS_MOUNTS] | `mount` | `(src_ptr, src_len, pt_ptr, pt_len, ro, ret_ptr: i32) -> ()`（#56） |
 * | [FS_MOUNTS] | `unmount` | `(pt_ptr, pt_len, ret_ptr: i32) -> ()` |
 * | [FS_MOUNTS] | `list-mounts` | `(ret_ptr: i32) -> ()`（`cabi_realloc` が要る。#453） |
 * | [FS_MOUNTS] | `list-disks` | `(ret_ptr: i32) -> ()`（`cabi_realloc` が要る。#453） |
 * | [PROCESS] | （[WasiP1KibiProcess] の表。#74） | |
 *
 * p2・p3 だけの interface（`kibi:io` の `pipe-streams`・`pipe-async`、`kibi:kernel` の `timers-poll`・`events-poll`・
 * `timers-async`・`events-async`、`kibi:process` の `process-poll`・`process-async`）は出さない（表にないので、import すると `NOEXEC`）。
 * `kibi:io/tty` の `mode`（`flags`）は下位 5 ビットだけを読む（上のビットは捨てる。Canonical ABI の lift と同じ）。
 * `u64` の時間の引数（`set-after`・`set-at`）は、2^63 以上を `Long.MAX_VALUE` に頭打ちにしてカーネルへ渡す（ADR 0426）。
 *
 * `kibi:computer/power` の `shutdown`・`reboot` は、受け付けたら呼んだプロセスも止まるので戻らない（ADR 0306）。
 * `kibi:process` の `[static]process.spawn` だけが待つ（[WasiP1.WAIT_IMPORTS]。[WasiP1KibiProcess]）。ほかは待たない。
 * 燃料（仮の値。確定は #251）: 呼び出しごとに [WasiP1Fd.CALL_FUEL]、`take` は加えて渡したイベントの数、
 * `kibi:process` の `list-processes` は加えて件数 × [KibiProcess.PROCESS_LIST_FUEL_PER_ENTRY]（#452）、
 * `kibi:fs/mounts` の `list-mounts`・`list-disks` は加えて件数 × [KibiFs.MOUNT_LIST_FUEL_PER_ENTRY]（#453）。
 * `list-mounts`・`list-disks` は待たず、`cabi_realloc` の中からも呼べる（一覧はカーネルロックの中で写し、ロックを放してから `cabi_realloc` を呼ぶ）。
 */
internal object WasiP1Kibi {
    /** `kibi:kernel/events` のモジュール名 */
    const val EVENTS: String = "kibi:kernel/events@0.1.0"

    /** `kibi:kernel/timers` のモジュール名 */
    const val TIMERS: String = "kibi:kernel/timers@0.1.0"

    /** `kibi:computer/clock` のモジュール名 */
    const val CLOCK: String = "kibi:computer/clock@0.1.0"

    /** `kibi:computer/memory` のモジュール名 */
    const val MEMORY: String = "kibi:computer/memory@0.1.0"

    /** `kibi:computer/power` のモジュール名（#306） */
    const val POWER: String = "kibi:computer/power@0.1.0"

    /** `kibi:io/fd` のモジュール名 */
    const val IO_FD: String = "kibi:io/fd@0.1.0"

    /** `kibi:io/pipe` のモジュール名 */
    const val IO_PIPE: String = "kibi:io/pipe@0.1.0"

    /** `kibi:io/tty` のモジュール名 */
    const val IO_TTY: String = "kibi:io/tty@0.1.0"

    /** `kibi:fs/mounts` のモジュール名（#56） */
    const val FS_MOUNTS: String = "kibi:fs/mounts@0.1.0"

    /** `kibi:process/process` のモジュール名（[WasiP1KibiProcess]） */
    const val PROCESS: String = WasiP1KibiProcess.MODULE

    /** `kibi:io/tty` の `flags mode` のフラグの数 */
    private const val TTY_MODE_FLAGS = 5

    /** 独自拡張のモジュール名の始まり（これで始まるモジュールは [typeOf] の表で確かめる） */
    const val PREFIX: String = "kibi:"

    /** `cabi_realloc` の export の名前と型 */
    const val REALLOC: String = "cabi_realloc"
    val REALLOC_TYPE: WasmFunctionType = WasmFunctionType.of(
        listOf(WasmValueType.I32, WasmValueType.I32, WasmValueType.I32, WasmValueType.I32),
        listOf(WasmValueType.I32),
    )

    private val I32 = WasmValueType.I32
    private val I64 = WasmValueType.I64

    /** 関数1つ: モジュール名、名前、型、`cabi_realloc` が要るか、本体 */
    private class Fn(
        val module: String,
        val name: String,
        val type: WasmFunctionType,
        val needsRealloc: Boolean,
        val body: (ProcessContext, WasmInstance, LongArray) -> LongArray,
    )

    private val NONE = LongArray(0)

    private fun Long.i(): Int = toInt()

    /** `u32` の引数を符号なしで広げる */
    private fun Long.u32(): Long = this and 0xffff_ffffL

    fun memoryOf(caller: WasmInstance): WasmMemory =
        caller.memory ?: throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, "線形メモリを export していない")

    private val FUNCTIONS: List<Fn> = listOf(
        Fn(EVENTS, "[static]event-queue.open", WasmFunctionType.of(listOf(I32, I32, I32, I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val fd = try {
                val kinds = P1Canonical.liftStringList(memory, a[0].i(), a[1].i(), EventLimits.MAX_FILTER_TYPES, 1..EventLimits.MAX_TYPE_BYTES)
                val capacity = P1Canonical.liftOptionU32(a[2].i(), a[3].i())
                KibiEvents.open(ctx, kinds, capacity)
            } catch (e: FsException) {
                P1Canonical.lowerHandleResult(memory, a[4].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerHandleResult(memory, a[4].i(), fd, 0)
            NONE
        },
        Fn(EVENTS, "[method]event-queue.take", WasmFunctionType.of(listOf(I32, I32, I32), emptyList()), true) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val batch = try {
                KibiEvents.take(ctx, a[0].i(), a[1] and 0xffff_ffffL)
            } catch (e: FsException) {
                P1Canonical.lowerBatchResult(memory, realloc(ctx, caller), a[2].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            ctx.chargeFuel(batch.events.size.toLong())
            P1Canonical.lowerBatchResult(memory, realloc(ctx, caller), a[2].i(), batch, 0)
            NONE
        },
        Fn(EVENTS, "[resource-drop]event-queue", WasmFunctionType.of(listOf(I32), emptyList()), false) { ctx, _, a ->
            try {
                KibiEvents.drop(ctx, a[0].i())
            } catch (e: FsException) {
                // Component Model と同じく、不正なハンドルの drop はトラップ
                throw WasmTrapException(WasmTrapKind.OTHER, "[resource-drop]event-queue: 不正なハンドル ${a[0].i()}（${e.errno}）")
            }
            NONE
        },
        Fn(TIMERS, "[static]timer.create", WasmFunctionType.of(listOf(I32, I32, I64, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val fd = try {
                // 判別子が 0 なら後ろの2値は読まない（Canonical ABI どおり）
                val some = P1Canonical.liftOptionDisc(a[0].i()) ?: fsError(Errno.INVAL, "option の判別子が不正（${a[0].i()}）")
                KibiTimers.create(ctx, if (some) a[1].i() to a[2] else null)
            } catch (e: FsException) {
                P1Canonical.lowerHandleResult(memory, a[3].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerHandleResult(memory, a[3].i(), fd, 0)
            NONE
        },
        Fn(TIMERS, "[method]timer.set-after", WasmFunctionType.of(listOf(I32, I64, I64, I32), emptyList()), false) { ctx, caller, a ->
            setTimer(ctx, caller, a, absolute = false)
        },
        Fn(TIMERS, "[method]timer.set-at", WasmFunctionType.of(listOf(I32, I64, I64, I32), emptyList()), false) { ctx, caller, a ->
            setTimer(ctx, caller, a, absolute = true)
        },
        Fn(TIMERS, "[method]timer.cancel", WasmFunctionType.of(listOf(I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val error = try {
                KibiTimers.cancel(ctx, a[0].i())
                null
            } catch (e: FsException) {
                KibiErrors.code(e)
            }
            P1Canonical.lowerUnitResult(memory, a[1].i(), error)
            NONE
        },
        Fn(TIMERS, "[method]timer.read", WasmFunctionType.of(listOf(I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val count = try {
                KibiTimers.read(ctx, a[0].i())
            } catch (e: FsException) {
                P1Canonical.lowerU64Result(memory, a[1].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerU64Result(memory, a[1].i(), count, 0)
            NONE
        },
        Fn(TIMERS, "[resource-drop]timer", WasmFunctionType.of(listOf(I32), emptyList()), false) { ctx, _, a ->
            try {
                KibiTimers.drop(ctx, a[0].i())
            } catch (e: FsException) {
                // Component Model と同じく、不正なハンドルの drop はトラップ
                throw WasmTrapException(WasmTrapKind.OTHER, "[resource-drop]timer: 不正なハンドル ${a[0].i()}（${e.errno}）")
            }
            NONE
        },
        Fn(CLOCK, "uptime-ticks", WasmFunctionType.of(emptyList(), listOf(I64)), false) { ctx, _, _ ->
            longArrayOf(KibiClock.uptimeTicks(ctx))
        },
        Fn(CLOCK, "world-ticks", WasmFunctionType.of(emptyList(), listOf(I64)), false) { ctx, _, _ ->
            longArrayOf(KibiClock.worldTicks(ctx))
        },
        Fn(MEMORY, "remaining", WasmFunctionType.of(emptyList(), listOf(I64)), false) { ctx, _, _ ->
            longArrayOf(KibiMemory.remaining(ctx))
        },
        Fn(MEMORY, "commit", WasmFunctionType.of(listOf(I32, I32, I64), listOf(I32)), false) { ctx, caller, a ->
            val memory = caller.memory
            val ok = memory != null && KibiMemory.commit(ctx, memory, a[0].toInt(), a[1].toInt(), a[2])
            longArrayOf(if (ok) 1L else 0L)
        },
        Fn(POWER, "shutdown", WasmFunctionType.of(emptyList(), emptyList()), false) { ctx, _, _ ->
            KibiPower.shutdown(ctx)
        },
        Fn(POWER, "reboot", WasmFunctionType.of(listOf(I32), emptyList()), false) { ctx, caller, a ->
            // 受け付けたら戻らない（書き先は確かめない）。断ったときだけ書く（範囲外ならトラップ。ADR 0306）
            val error = KibiPower.reboot(ctx)
            P1Canonical.lowerUnitResult(memoryOf(caller), a[0].i(), error)
            NONE
        },
        Fn(IO_FD, "dup", WasmFunctionType.of(listOf(I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val fd = try {
                KibiIo.dup(ctx, a[0].u32())
            } catch (e: FsException) {
                P1Canonical.lowerHandleResult(memory, a[1].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerHandleResult(memory, a[1].i(), fd, 0)
            NONE
        },
        Fn(IO_FD, "dup-to", WasmFunctionType.of(listOf(I32, I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val error = try {
                KibiIo.dupTo(ctx, a[0].u32(), a[1].u32())
                null
            } catch (e: FsException) {
                KibiErrors.code(e)
            }
            P1Canonical.lowerUnitResult(memory, a[2].i(), error)
            NONE
        },
        Fn(IO_PIPE, "create", WasmFunctionType.of(listOf(I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val ends = try {
                KibiIo.pipe(ctx)
            } catch (e: FsException) {
                P1Canonical.lowerPipeResult(memory, a[0].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerPipeResult(memory, a[0].i(), ends, 0)
            NONE
        },
        Fn(IO_TTY, "is-tty", WasmFunctionType.of(listOf(I32), listOf(I32)), false) { ctx, _, a ->
            longArrayOf(if (KibiTty.isTty(ctx, a[0].u32())) 1L else 0L)
        },
        Fn(IO_TTY, "get-mode", WasmFunctionType.of(listOf(I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val bits = try {
                KibiTty.mode(ctx, a[0].u32())
            } catch (e: FsException) {
                P1Canonical.lowerU8Result(memory, a[1].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerU8Result(memory, a[1].i(), bits, 0)
            NONE
        },
        Fn(IO_TTY, "set-mode", WasmFunctionType.of(listOf(I32, I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val error = try {
                KibiTty.setMode(ctx, a[0].u32(), P1Canonical.liftFlags(a[1].i(), TTY_MODE_FLAGS))
                null
            } catch (e: FsException) {
                KibiErrors.code(e)
            }
            P1Canonical.lowerUnitResult(memory, a[2].i(), error)
            NONE
        },
        Fn(IO_TTY, "get-size", WasmFunctionType.of(listOf(I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val info = try {
                KibiTty.size(ctx, a[0].u32())
            } catch (e: FsException) {
                P1Canonical.lowerTtySizeResult(memory, a[1].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerTtySizeResult(memory, a[1].i(), info, 0)
            NONE
        },
        Fn(IO_TTY, "get-foreground", WasmFunctionType.of(listOf(I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val pid = try {
                KibiTty.foreground(ctx, a[0].u32())
            } catch (e: FsException) {
                P1Canonical.lowerHandleResult(memory, a[1].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerHandleResult(memory, a[1].i(), pid, 0)
            NONE
        },
        Fn(IO_TTY, "set-foreground", WasmFunctionType.of(listOf(I32, I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val error = try {
                KibiTty.setForeground(ctx, a[0].u32(), a[1].u32())
                null
            } catch (e: FsException) {
                KibiErrors.code(e)
            }
            P1Canonical.lowerUnitResult(memory, a[2].i(), error)
            NONE
        },
        Fn(FS_MOUNTS, "mount", WasmFunctionType.of(listOf(I32, I32, I32, I32, I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val error = try {
                val source = P1Canonical.liftString(memory, a[0].i(), a[1].i(), KibiFs.MAX_SOURCE_BYTES, Errno.INVAL)
                val point = P1Canonical.liftString(memory, a[2].i(), a[3].i(), VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG)
                // bool の lift: 0 以外は true（Canonical ABI）
                KibiFs.mount(ctx, source, point, a[4].i() != 0)
                null
            } catch (e: FsException) {
                KibiErrors.code(e)
            }
            P1Canonical.lowerUnitResult(memory, a[5].i(), error)
            NONE
        },
        Fn(FS_MOUNTS, "unmount", WasmFunctionType.of(listOf(I32, I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val error = try {
                KibiFs.unmount(ctx, P1Canonical.liftString(memory, a[0].i(), a[1].i(), VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG))
                null
            } catch (e: FsException) {
                KibiErrors.code(e)
            }
            P1Canonical.lowerUnitResult(memory, a[2].i(), error)
            NONE
        },
        Fn(FS_MOUNTS, "list-mounts", WasmFunctionType.of(listOf(I32), emptyList()), true) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val entries = KibiFs.listMounts(ctx)
            P1Canonical.lowerMountListResult(memory, realloc(ctx, caller), a[0].i(), entries)
            NONE
        },
        Fn(FS_MOUNTS, "list-disks", WasmFunctionType.of(listOf(I32), emptyList()), true) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val entries = KibiFs.listDisks(ctx)
            P1Canonical.lowerDiskListResult(memory, realloc(ctx, caller), a[0].i(), entries)
            NONE
        },
    )

    /** `set-after`・`set-at`: `(self: i32, time: i64, interval: i64, ret_ptr: i32)`。時間は頭打ちにしてから渡す */
    private fun setTimer(ctx: ProcessContext, caller: WasmInstance, a: LongArray, absolute: Boolean): LongArray {
        val memory = memoryOf(caller)
        val error = try {
            KibiTimers.set(ctx, a[0].i(), P1Canonical.u64Arg(a[1]), P1Canonical.u64Arg(a[2]), absolute)
            null
        } catch (e: FsException) {
            KibiErrors.code(e)
        }
        P1Canonical.lowerUnitResult(memory, a[3].i(), error)
        return NONE
    }

    /** ゲストの `cabi_realloc(0, 0, align, size)` を、ゲストを呼んでいる印を立てて呼ぶ */
    fun realloc(ctx: ProcessContext, caller: WasmInstance): P1Canonical.Realloc = P1Canonical.Realloc { align, size ->
        ctx.callGuest { caller.call(REALLOC, 0L, 0L, align.toLong(), size.toLong())[0].toInt() }
    }

    /** 出しているモジュール名 */
    val modules: Set<String> = FUNCTIONS.map { it.module }.toSet() + PROCESS

    /** 出している関数 [module].[name] の型（なければ `null`） */
    fun typeOf(module: String, name: String): WasmFunctionType? =
        if (module == PROCESS) WasiP1KibiProcess.typeOf(name) else find(module, name)?.type

    /** [module].[name] が `cabi_realloc` を要するか（出していない関数なら `false`） */
    fun needsRealloc(module: String, name: String): Boolean =
        if (module == PROCESS) WasiP1KibiProcess.needsRealloc(name) else find(module, name)?.needsRealloc == true

    /** 出している関数の (モジュール名, 名前) の一覧（`KibiWitTableTest` が WIT と突き合わせる） */
    val functions: List<Pair<String, String>> get() = FUNCTIONS.map { it.module to it.name } + WasiP1KibiProcess.names.map { PROCESS to it }

    private fun find(module: String, name: String): Fn? = FUNCTIONS.firstOrNull { it.module == module && it.name == name }

    /**
     * 独自拡張の関数を [builder] に登録する（`ProcessDriver.configure` の中から。[WasiP1.register] が呼ぶ）。
     * [suspend]・[wait] は止まりうる `kibi:process` の `spawn` の待ち方。戻り値はこのプロセスの `kibi:process` の import
     */
    fun register(ctx: ProcessContext, builder: WasmInstanceConfig.Builder, suspend: WasiP1Suspend, wait: P1Wait): WasiP1KibiProcess {
        for (fn in FUNCTIONS) {
            builder.hostFunction(
                fn.module,
                fn.name,
                fn.type,
                ctx.hostFunction { c, caller, args ->
                    c.chargeFuel(WasiP1Fd.CALL_FUEL)
                    try {
                        fn.body(c, caller, args)
                    } catch (e: WasmMemoryAccessException) {
                        // 結果の書き先は確かめてから書くので、ここに来るのはゲストが realloc の中でメモリを縮めたときなど
                        throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, "${fn.module}.${fn.name}: 線形メモリの外に書こうとした", e)
                    }
                },
            )
        }
        return WasiP1KibiProcess(ctx, suspend, wait).also { it.register(builder) }
    }
}
