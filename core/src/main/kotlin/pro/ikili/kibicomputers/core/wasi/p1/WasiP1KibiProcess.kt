package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.process.ExecWait
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import pro.ikili.kibicomputers.core.wasi.kibi.KibiProcess

/**
 * 独自拡張 `kibi:process/process@0.1.0`（#74。ADR 0074）の p1 の import。プロセスごとに1つ作る（[WasiP1Kibi.register]）。
 *
 * | 名前 | core の型 |
 * | --- | --- |
 * | `[static]process.spawn` | `(path_ptr, path_len, args_ptr, args_len, env_is_some, env_ptr, env_len, cwd_is_some, cwd_ptr, cwd_len, fds_ptr, fds_len, ret_ptr: i32) -> ()` |
 * | `[method]process.pid` | `(self: i32) -> i32` |
 * | `[method]process.status` | `(self, ret_ptr: i32) -> ()` |
 * | `[method]process.kill` | `(self, sig, ret_ptr: i32) -> ()` |
 * | `[resource-drop]process` | `(self: i32) -> ()` |
 * | `kill-pid` | `(pid, sig, ret_ptr: i32) -> ()` |
 * | `list-processes` | `(ret_ptr: i32) -> ()`（`cabi_realloc` が要る。#452） |
 * | `self-pid`・`parent-pid` | `() -> i32` |
 * | `set-signal-ignored` | `(sig, ignored, ret_ptr: i32) -> ()` |
 * | `get-cwd` | `(ret_ptr: i32) -> ()`（`cabi_realloc` が要る） |
 * | `set-cwd` | `(path_ptr, path_len, ret_ptr: i32) -> ()` |
 *
 * `spawn` だけが止まりうる import（[WasiP1.WAIT_IMPORTS]。読み込みが終わるまで待つ）。待ち方は [wait]:
 * - [P1Wait.UNWIND] で巻き戻せるとき: `ctx.beginExec` の `ExecWait` を [pendingSpawn] に持って巻き戻し、巻き直して着いたら
 *   引数を読み直さずにそれを使って `ctx.finishExecWithHandle` で子を作る。`ExecWait` は実行時だけの状態で、`AsyncWait` には入れない
 *   （巻き直して着いたのに手元にない — 保存からの復元・インスタンスの作り直し — なら、`beginExec` からやり直す。子はまだ作っていない）。
 *   読み込みが始めから終わっていれば（共有の読み込みに当たった）、巻き戻さずに子を作る
 * - [P1Wait.BLOCK]（と、巻き戻せない作っている途中）: `ctx.execWithHandle`（読み込みの間は CPU と許可を返す）
 * - [P1Wait.NONE]: 待たずに `again`（読み込みも始めない）
 *
 * 待っている間に止められたときは、カーネルの後始末（`ProcessRunner.finishLocked` が `HostCompletion` を閉じ、始まっていない読み込みを
 * 取り消す）に任せる。`finishExecWithHandle` を呼ばないので子は作られない。
 * 不正なハンドル（`pid`・`[resource-drop]`）はトラップ（結果に error-code がない。Component Model と同じ）。
 * 燃料（仮の値。確定は #251）: 呼び出しごとに [WasiP1Fd.CALL_FUEL]（巻き直して着いた `spawn` も1回と数える）。
 * `list-processes` は加えて件数 × [KibiProcess.PROCESS_LIST_FUEL_PER_ENTRY]。
 * `list-processes` は待たず、`cabi_realloc` の中からも呼べる。一覧はカーネルロックの中で写し、ロックを放してから `cabi_realloc` を呼ぶ
 * （`cabi_realloc` の中でほかのプロセスが変わっても、返すのは呼んだ時点の写し）。
 */
internal class WasiP1KibiProcess(
    private val ctx: ProcessContext,
    private val suspend: WasiP1Suspend,
    private val wait: P1Wait,
) {
    /** 巻き戻して待っている `spawn` の読み込み（実行時だけ。保存しない） */
    @Volatile
    private var pendingSpawn: ExecWait? = null

    /** テスト専用。巻き戻して待っている `spawn` の読み込みがあるか */
    internal val hasPendingSpawnForTest: Boolean get() = pendingSpawn != null

    /**
     * テスト専用。本体のコードから呼ばない。巻き戻して待っている `spawn` の読み込みを忘れる（保存からの復元で実行時の状態が消えたときと同じ状況を作る）。
     * 待ちのオブジェクトは閉じない（プロセスの終わりにカーネルが閉じる）
     */
    internal fun forgetPendingSpawnForTest() {
        pendingSpawn = null
    }

    /** 関数1つ: 名前、型、`cabi_realloc` が要るか、止まりうるか、本体 */
    private class Fn(
        val name: String,
        val type: WasmFunctionType,
        val needsRealloc: Boolean,
        val mayWait: Boolean,
        val body: WasiP1KibiProcess.(WasmInstance, LongArray) -> LongArray,
    )

    /** 関数を [builder] に登録する */
    fun register(builder: WasmInstanceConfig.Builder) {
        for (fn in FUNCTIONS) {
            builder.hostFunction(
                MODULE,
                fn.name,
                fn.type,
                ctx.hostFunction { c, caller, args ->
                    if (fn.mayWait) {
                        // ホスト関数の中から呼んだゲスト（cabi_realloc）の中では、待ち方に依らず止まりうる import を呼べない（ADR 0073）
                        if (c.inGuestCall) throw WasmTrapException(WasmTrapKind.OTHER, "止まりうる import $MODULE.${fn.name} を cabi_realloc の中で呼んだ")
                        // 巻き直して着いたなら、ここで NORMAL に戻す（燃料を引くより前。巻き直しも普通の呼び出しとして数える）
                        if (wait == P1Wait.UNWIND) suspend.enter(caller)
                    }
                    c.chargeFuel(WasiP1Fd.CALL_FUEL)
                    try {
                        fn.body(this, caller, args)
                    } catch (e: WasmMemoryAccessException) {
                        throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, "$MODULE.${fn.name}: 線形メモリの外に書こうとした", e)
                    }
                },
            )
        }
    }

    // ---- spawn ----

    private fun spawn(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val retPtr = a[12].i()
        val fd = try {
            spawnFd(memory, a) ?: return NONE
        } catch (e: FsException) {
            P1Canonical.lowerHandleResult(memory, retPtr, null, KibiErrors.code(e))
            return NONE
        }
        P1Canonical.lowerHandleResult(memory, retPtr, fd, 0)
        return NONE
    }

    /** 子を作ってハンドルの fd を返す。巻き戻したら `null`（結果は書かない） */
    @Throws(FsException::class, ProcessKilledException::class)
    private fun spawnFd(memory: WasmMemory, a: LongArray): Int? {
        if (wait == P1Wait.NONE) fsError(Errno.AGAIN, "待たない設定では spawn できない")
        if (wait == P1Wait.UNWIND && suspend.canUnwind()) return spawnUnwinding(memory, a)
        val request = readRequest(memory, a)
        val options = KibiProcess.options(request)
        return KibiProcess.limits { ctx.execWithHandle(request.path, KernelDrivers.DEFAULT, options) }.fd
    }

    /** 巻き戻して読み込みを待つ形。待つなら巻き戻して `null` */
    @Throws(FsException::class, ProcessKilledException::class)
    private fun spawnUnwinding(memory: WasmMemory, a: LongArray): Int? {
        var pending = pendingSpawn
        if (pending != null && !suspend.rewound) {
            // 新しい呼び出しなのに前の読み込みが残っている（起きないはず）。閉じてから最初から行う
            pendingSpawn = null
            ctx.closeHostWait(pending.wait)
            pending = null
        }
        if (pending == null) {
            // 新しい呼び出し、または巻き直して着いたのに手元にない（保存からの復元など）: 最初から行う
            val request = readRequest(memory, a)
            val options = KibiProcess.options(request)
            // fd 表の空きは読み込みの前に確かめる（子を作る前にカーネルがもう一度確かめる）
            ctx.checkHandleRoom()
            pending = KibiProcess.limits { ctx.beginExec(request.path, KernelDrivers.DEFAULT, options) }
            pendingSpawn = pending
        }
        if (!pending.wait.future.isDone) {
            suspend.unwind(listOf(pending.key), null)
            return null
        }
        pendingSpawn = null
        return KibiProcess.limits { ctx.finishExecWithHandle(pending) }.fd
    }

    /** `spawn` の引数を読む（確かめる順は引数の順: path → args → env → cwd → fds） */
    @Throws(FsException::class)
    private fun readRequest(memory: WasmMemory, a: LongArray): KibiProcess.Request {
        val budget = P1Canonical.ArgBudget()
        val path = P1Canonical.liftString(memory, a[0].i(), a[1].i(), VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG)
        val args = P1Canonical.liftArgList(memory, a[2].i(), a[3].i(), KibiProcess.MAX_ARGS, budget)
        val envSome = P1Canonical.liftOptionDisc(a[4].i()) ?: fsError(Errno.INVAL, "option の判別子が不正（${a[4].i()}）")
        val env = if (envSome) P1Canonical.liftEnvList(memory, a[5].i(), a[6].i(), KibiProcess.MAX_ENV, budget) else null
        val cwdSome = P1Canonical.liftOptionDisc(a[7].i()) ?: fsError(Errno.INVAL, "option の判別子が不正（${a[7].i()}）")
        val cwd = if (cwdSome) P1Canonical.liftString(memory, a[8].i(), a[9].i(), VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG) else null
        val fds = P1Canonical.liftFdAssigns(memory, a[10].i(), a[11].i(), KibiProcess.MAX_FDS)
        return KibiProcess.Request(path, args, env, cwd, fds)
    }

    // ---- ハンドルの操作 ----

    private fun status(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val status: ExitStatus? = try {
            KibiProcess.status(ctx, a[0].i())
        } catch (e: FsException) {
            P1Canonical.lowerStatusResult(memory, a[1].i(), null, KibiErrors.code(e))
            return NONE
        }
        P1Canonical.lowerStatusResult(memory, a[1].i(), status, null)
        return NONE
    }

    private fun unit(caller: WasmInstance, retPtr: Int, block: () -> Unit): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val error = try {
            block()
            null
        } catch (e: FsException) {
            KibiErrors.code(e)
        }
        P1Canonical.lowerUnitResult(memory, retPtr, error)
        return NONE
    }

    /** 不正なハンドルはトラップ（Component Model と同じ） */
    private fun <T> handleOrTrap(name: String, handle: Int, block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw WasmTrapException(WasmTrapKind.OTHER, "$name: 不正なハンドル $handle（${e.errno}）")
    }

    private fun drop(handle: Int) {
        ctx.withKernelLock {
            val table = FdTable(checkNotNull(ctx.kernel.processes[ctx.pid]), ctx.kernel.objects, ctx.wakeSink)
            table.objectOf(handle) as? ProcessObject ?: fsError(Errno.BADF, "fd $handle は子のハンドルではない")
            table.close(handle)
        }
    }

    companion object {
        /** モジュール名 */
        const val MODULE: String = "kibi:process/process@0.1.0"

        private val I32 = WasmValueType.I32
        private val NONE = LongArray(0)

        private fun Long.i(): Int = toInt()

        /** `u32` の引数を符号なしで広げる */
        private fun Long.u32(): Long = this and 0xffff_ffffL

        private fun type(params: Int, result: Boolean = false): WasmFunctionType =
            WasmFunctionType.of(List(params) { I32 }, if (result) listOf(I32) else emptyList())

        private val FUNCTIONS: List<Fn> = listOf(
            Fn("[static]process.spawn", type(13), needsRealloc = false, mayWait = true) { caller, a -> spawn(caller, a) },
            Fn("[method]process.pid", type(1, result = true), needsRealloc = false, mayWait = false) { _, a ->
                longArrayOf(handleOrTrap("[method]process.pid", a[0].i()) { KibiProcess.pid(ctx, a[0].i()) }.toLong())
            },
            Fn("[method]process.status", type(2), needsRealloc = false, mayWait = false) { caller, a -> status(caller, a) },
            Fn("[method]process.kill", type(3), needsRealloc = false, mayWait = false) { caller, a ->
                unit(caller, a[2].i()) { KibiProcess.kill(ctx, a[0].i(), KibiProcess.signalOf(a[1].i())) }
            },
            Fn("[resource-drop]process", type(1), needsRealloc = false, mayWait = false) { _, a ->
                handleOrTrap("[resource-drop]process", a[0].i()) { drop(a[0].i()) }
                NONE
            },
            Fn("kill-pid", type(3), needsRealloc = false, mayWait = false) { caller, a ->
                unit(caller, a[2].i()) { KibiProcess.killPid(ctx, a[0].u32(), KibiProcess.signalOf(a[1].i())) }
            },
            Fn("list-processes", type(1), needsRealloc = true, mayWait = false) { caller, a ->
                val memory = WasiP1Kibi.memoryOf(caller)
                val entries = KibiProcess.list(ctx)
                P1Canonical.lowerProcessListResult(memory, WasiP1Kibi.realloc(ctx, caller), a[0].i(), entries)
                NONE
            },
            Fn("self-pid", type(0, result = true), needsRealloc = false, mayWait = false) { _, _ -> longArrayOf(ctx.pid.toLong()) },
            Fn("parent-pid", type(0, result = true), needsRealloc = false, mayWait = false) { _, _ -> longArrayOf(ctx.parentPid.toLong()) },
            Fn("set-signal-ignored", type(3), needsRealloc = false, mayWait = false) { caller, a ->
                // bool の lift: 0 以外は true（Canonical ABI）
                unit(caller, a[2].i()) { KibiProcess.setSignalIgnored(ctx, KibiProcess.signalOf(a[0].i()), a[1].i() != 0) }
            },
            Fn("get-cwd", type(1), needsRealloc = true, mayWait = false) { caller, a ->
                val memory = WasiP1Kibi.memoryOf(caller)
                P1Canonical.lowerStringResult(memory, WasiP1Kibi.realloc(ctx, caller), a[0].i(), ctx.cwd.toString())
                NONE
            },
            Fn("set-cwd", type(3), needsRealloc = false, mayWait = false) { caller, a ->
                val memory = WasiP1Kibi.memoryOf(caller)
                unit(caller, a[2].i()) {
                    KibiProcess.setCwd(ctx, P1Canonical.liftString(memory, a[0].i(), a[1].i(), VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG))
                }
            },
        )

        /** 出している関数の名前 */
        val names: List<String> = FUNCTIONS.map { it.name }

        /** 関数 [name] の型（なければ `null`） */
        fun typeOf(name: String): WasmFunctionType? = FUNCTIONS.firstOrNull { it.name == name }?.type

        /** 関数 [name] が `cabi_realloc` を要するか */
        fun needsRealloc(name: String): Boolean = FUNCTIONS.firstOrNull { it.name == name }?.needsRealloc == true

        /** 止まりうる関数の名前（[WasiP1.WAIT_IMPORTS] に入れる） */
        val waitNames: List<String> = FUNCTIONS.filter { it.mayWait }.map { it.name }
    }
}
