package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.DeviceCallException
import pro.ikili.kibicomputers.core.device.DeviceHandle
import pro.ikili.kibicomputers.core.device.DeviceLimits
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.process.DeviceInvokeStart
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.kibi.KibiDevice

/**
 * 独自拡張 `kibi:device/bus@0.1.0`（#77。ADR 0077）の p1 の import。プロセスごとに1つ作る（[WasiP1Kibi.register]）。
 *
 * | 名前 | core の型 |
 * | --- | --- |
 * | `list-devices` | `(ret_ptr: i32) -> ()` |
 * | `[static]device.open` | `(addr_ptr, addr_len, ret_ptr: i32) -> ()` |
 * | `[method]device.info` | `(self, ret_ptr: i32) -> ()` |
 * | `[method]device.methods` | `(self, ret_ptr: i32) -> ()` |
 * | `[method]device.invoke` | `(self, m_ptr, m_len, a_ptr, a_len, ret_ptr: i32) -> ()` |
 * | `[method]device.start` | `(self, m_ptr, m_len, a_ptr, a_len, ret_ptr: i32) -> ()` |
 * | `[method]pending-call.take` | `(self, ret_ptr: i32) -> ()` |
 * | `[resource-drop]device`・`[resource-drop]pending-call` | `(self: i32) -> ()` |
 *
 * drop 以外は `cabi_realloc` が要る（結果に string・list がある。`open`・`start` も `call-error` に string がある）。
 *
 * `invoke` だけが止まりうる import（[WasiP1.WAIT_IMPORTS]。メインスレッドのメソッドの結果を待つ）。`cabi_realloc` の中で呼ぶと、
 * メソッドの種類も待ち方も見ずにトラップ（ADR 0073）。待ち方は [wait]:
 * - [P1Wait.UNWIND] で巻き戻せるとき: `ctx.beginDeviceInvoke` でデバイスを1回だけ引いて始める。直接のメソッドはその場の結果を返す
 *   （`pending-call` も fd も作らない）。メインスレッドのメソッドは、呼んだプロセスの fd 表に入れた `pending-call` を [pendingInvoke] に持って
 *   巻き戻し、巻き直して着いたら引数を読み直さずにその結果を受け取って閉じる。待つ間の状態はその `pending-call` だけなので、保存・復元を
 *   またいでもメソッドは1回だけ走り、実行中のものは `failed("interrupted")` になる（#65 の Q6）。復元などで [pendingInvoke] がないときは、
 *   起こした key（[WasiP1Suspend.resumedReady]）から引く
 * - [P1Wait.BLOCK]（と、巻き戻せない作っている途中）: `ctx.invokeDevice`（スレッドを持ったまま待つ）
 * - [P1Wait.NONE]: 直接のメソッドはその場の結果、メインスレッドのメソッドは積まずに `kernel(again)`
 *
 * 引数の誤りは `kernel(error-code)`: メソッド名が 64 バイトを超えたら `name-too-long`、引数が 32 個を超える・text・bytes の合計が 64 KiB を
 * 超えたら `too-big`（どれも中身を読む前に）、判別子・UTF-8 の誤りは `invalid`、範囲外は `fault`。確かめる順は引数の順（method → args）→ ハンドル。
 * 引数の list の ptr が align 8 に揃っていなければトラップ（長さ 0 でも。#430）。
 * 不正なハンドル: `info`・`methods`・`[resource-drop]*` はトラップ（結果に error がない。Component Model と同じ）、
 * `invoke`・`start`・`take` は `kernel(bad-descriptor)`。
 * 燃料（仮の値。確定は #251）: 呼び出しごとに [WasiP1Fd.CALL_FUEL]（巻き直して着いた `invoke` も1回と数える）。`list-devices`・`methods` は加えて
 * 件数 × [KibiDevice.DEVICE_LIST_FUEL_PER_ENTRY]。`invoke`・`start`・`take` は加えてカーネルが [DeviceLimits] の分を払う。
 */
internal class WasiP1KibiDevice(
    private val ctx: ProcessContext,
    private val suspend: WasiP1Suspend,
    private val wait: P1Wait,
) {
    /** 巻き戻して結果を待っている `invoke` の `pending-call`（fd と、その READABLE の key。実行時だけ。保存しない） */
    private class PendingInvoke(val fd: Int, val key: WaitKey)

    @Volatile
    private var pendingInvoke: PendingInvoke? = null

    /** テスト専用。巻き戻して待っている `invoke` があるか */
    internal val hasPendingInvokeForTest: Boolean get() = pendingInvoke != null

    /**
     * テスト専用。本体のコードから呼ばない。巻き戻して待っている `invoke` を忘れる（保存からの復元で実行時の状態が消えたときと同じ状況を作る）。
     * `pending-call` は閉じない（fd 表に残り、巻き直したときに起こした key から引く）
     */
    internal fun forgetPendingInvokeForTest() {
        pendingInvoke = null
    }

    /** 関数1つ: 名前、型、`cabi_realloc` が要るか、止まりうるか、本体 */
    private class Fn(
        val name: String,
        val type: WasmFunctionType,
        val needsRealloc: Boolean,
        val mayWait: Boolean,
        val body: WasiP1KibiDevice.(WasmInstance, LongArray) -> LongArray,
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
                        // ホスト関数の中から呼んだゲスト（cabi_realloc）の中では、待ち方・メソッドの種類に依らず止まりうる import を呼べない（ADR 0073）
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

    private fun realloc(caller: WasmInstance): P1Canonical.Realloc = WasiP1Kibi.realloc(ctx, caller)

    // ---- 一覧と開く ----

    private fun listDevices(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        // 作り置きの一覧を写すだけ（接続を変えたときだけ作り直す。ADR 0066）
        val entries = ctx.listDevices()
        ctx.chargeFuel(entries.size * KibiDevice.DEVICE_LIST_FUEL_PER_ENTRY)
        P1Canonical.lowerDeviceInfoList(memory, realloc(caller), a[0].i(), entries)
        return NONE
    }

    private fun open(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val retPtr = a[2].i()
        val error: CallError? = try {
            val fd = openFd(memory, a[0].i(), a[1].i())
            P1Canonical.lowerCallHandleResult(memory, realloc(caller), retPtr, fd, null)
            return NONE
        } catch (e: FsException) {
            KibiDevice.kernelError(e)
        } catch (e: DeviceCallException) {
            e.error
        }
        P1Canonical.lowerCallHandleResult(memory, realloc(caller), retPtr, 0, error)
        return NONE
    }

    @Throws(FsException::class, DeviceCallException::class)
    private fun openFd(memory: WasmMemory, ptr: Int, len: Int): Int {
        // 形の違う address（36 バイトでない）は、読まずに「ない」とする（ADR 0054）
        if ((len.toLong() and 0xffff_ffffL) != KibiDevice.ADDRESS_LENGTH.toLong()) throw DeviceCallException(CallError.Detached)
        val address = P1Canonical.liftString(memory, ptr, len, KibiDevice.ADDRESS_LENGTH, Errno.INVAL)
        return ctx.openDevice(address)
    }

    // ---- device のメソッド ----

    private fun info(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val info = handleOrTrap("[method]device.info", a[0].i()) { ctx.deviceInfo(a[0].i()) }
        P1Canonical.lowerDeviceInfo(memory, realloc(caller), a[1].i(), info)
        return NONE
    }

    private fun methods(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val list = handleOrTrap("[method]device.methods", a[0].i()) { ctx.deviceMethods(a[0].i()) }
        ctx.chargeFuel(list.size * KibiDevice.DEVICE_LIST_FUEL_PER_ENTRY)
        P1Canonical.lowerMethodInfoList(memory, realloc(caller), a[1].i(), list)
        return NONE
    }

    /** `invoke`・`start` の引数（method → args の順に読む） */
    private class CallArgs(val method: String, val args: List<DeviceValue>)

    @Throws(FsException::class)
    private fun readCall(memory: WasmMemory, a: LongArray): CallArgs {
        val method = P1Canonical.liftString(memory, a[1].i(), a[2].i(), DeviceLimits.MAX_METHOD_BYTES, Errno.NAMETOOLONG)
        val args = P1Canonical.liftDeviceValues(memory, a[3].i(), a[4].i())
        return CallArgs(method, args)
    }

    private fun invoke(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val result = try {
            invokeResult(memory, a) ?: return NONE
        } catch (e: FsException) {
            CallResult.Error(KibiDevice.kernelError(e))
        }
        // 結果がそろってから書く（cabi_realloc は callGuest の中で呼ぶ）
        P1Canonical.lowerCallResult(memory, realloc(caller), a[5].i(), result)
        return NONE
    }

    /** `invoke` の結果。巻き戻したら `null`（結果は書かない） */
    @Throws(FsException::class, ProcessKilledException::class)
    private fun invokeResult(memory: WasmMemory, a: LongArray): CallResult? {
        val handle = a[0].i()
        return when {
            wait == P1Wait.UNWIND && suspend.canUnwind() -> invokeUnwinding(memory, a)
            wait == P1Wait.NONE -> {
                val call = readCall(memory, a)
                when (val s = ctx.beginDeviceInvoke(handle, call.method, call.args, mayWait = false)) {
                    is DeviceInvokeStart.Done -> s.result
                    is DeviceInvokeStart.Waiting -> error("待たない設定で invoke が積まれた")
                }
            }
            else -> {
                val call = readCall(memory, a)
                try {
                    CallResult.Values(ctx.invokeDevice(handle, call.method, call.args))
                } catch (e: DeviceCallException) {
                    CallResult.Error(e.error)
                }
            }
        }
    }

    /** 巻き戻して待つ形。待つなら巻き戻して `null` */
    @Throws(FsException::class, ProcessKilledException::class)
    private fun invokeUnwinding(memory: WasmMemory, a: LongArray): CallResult? {
        var pending = pendingInvoke
        if (suspend.rewound) {
            if (pending == null) {
                // 実行時の状態がない（保存からの復元・インスタンスの作り直し）: 起こした key から前の呼び出しを引く
                pending = suspend.resumedReady.firstNotNullOfOrNull { key -> ctx.pendingDeviceCallFdOf(key)?.let { PendingInvoke(it, key) } }
                // 見つからない（起きないはず）: やり直すとメソッドが二重に走りうるので、やり直さずに interrupted を返す
                    ?: return CallResult.Error(CallError.INTERRUPTED)
                pendingInvoke = pending
            }
            return takeOrUnwind(pending)
        }
        if (pending != null) {
            // 新しい呼び出しなのに前の呼び出しが残っている（起きないはず）。閉じてから最初から行う
            pendingInvoke = null
            closeQuietly(pending.fd)
        }
        val call = readCall(memory, a)
        return when (val s = ctx.beginDeviceInvoke(a[0].i(), call.method, call.args, mayWait = true)) {
            is DeviceInvokeStart.Done -> s.result
            is DeviceInvokeStart.Waiting -> {
                val p = PendingInvoke(s.callFd, s.key)
                pendingInvoke = p
                takeOrUnwind(p)
            }
        }
    }

    /** [pending] の結果があれば受け取って閉じ、なければその key で巻き戻して `null` */
    private fun takeOrUnwind(pending: PendingInvoke): CallResult? {
        val result = try {
            ctx.takeDeviceCall(pending.fd)
        } catch (e: FsException) {
            // fd がもう pending-call でない（起きないはず）
            pendingInvoke = null
            return CallResult.Error(CallError.INTERRUPTED)
        }
        if (result == null) {
            suspend.unwind(listOf(pending.key), null)
            return null
        }
        pendingInvoke = null
        closeQuietly(pending.fd)
        return result
    }

    private fun start(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val retPtr = a[5].i()
        val error: CallError = try {
            val call = readCall(memory, a)
            val fd = ctx.startDeviceCall(a[0].i(), call.method, call.args)
            P1Canonical.lowerCallHandleResult(memory, realloc(caller), retPtr, fd, null)
            return NONE
        } catch (e: FsException) {
            KibiDevice.kernelError(e)
        }
        P1Canonical.lowerCallHandleResult(memory, realloc(caller), retPtr, 0, error)
        return NONE
    }

    private fun take(caller: WasmInstance, a: LongArray): LongArray {
        val memory = WasiP1Kibi.memoryOf(caller)
        val result: CallResult? = try {
            ctx.takeDeviceCall(a[0].i())
        } catch (e: FsException) {
            CallResult.Error(KibiDevice.kernelError(e))
        }
        P1Canonical.lowerTakeResult(memory, realloc(caller), a[1].i(), result)
        return NONE
    }

    // ---- ハンドル ----

    /** 不正なハンドルはトラップ（Component Model と同じ） */
    private fun <T> handleOrTrap(name: String, handle: Int, block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw WasmTrapException(WasmTrapKind.OTHER, "$name: 不正なハンドル $handle（${e.errno}）")
    }

    /** [handle] が [isType] の fd なら閉じる。違えば `BADF` */
    @Throws(FsException::class)
    private fun drop(handle: Int, what: String, isType: (KernelObject?) -> Boolean) {
        ctx.withKernelLock {
            val table = FdTable(checkNotNull(ctx.kernel.processes[ctx.pid]), ctx.kernel.objects, ctx.wakeSink)
            if (!isType(table.objectOf(handle))) fsError(Errno.BADF, "fd $handle は $what でない")
            table.close(handle)
        }
    }

    private fun closeQuietly(fd: Int) {
        try {
            drop(fd, "pending-call") { it is PendingDeviceCall }
        } catch (e: FsException) {
            // もう閉じている
        }
    }

    companion object {
        /** モジュール名 */
        const val MODULE: String = "kibi:device/bus@0.1.0"

        private val I32 = WasmValueType.I32
        private val NONE = LongArray(0)

        private fun Long.i(): Int = toInt()

        private fun type(params: Int): WasmFunctionType = WasmFunctionType.of(List(params) { I32 }, emptyList())

        private val FUNCTIONS: List<Fn> = listOf(
            Fn("list-devices", type(1), needsRealloc = true, mayWait = false) { caller, a -> listDevices(caller, a) },
            Fn("[static]device.open", type(3), needsRealloc = true, mayWait = false) { caller, a -> open(caller, a) },
            Fn("[method]device.info", type(2), needsRealloc = true, mayWait = false) { caller, a -> info(caller, a) },
            Fn("[method]device.methods", type(2), needsRealloc = true, mayWait = false) { caller, a -> methods(caller, a) },
            Fn("[method]device.invoke", type(6), needsRealloc = true, mayWait = true) { caller, a -> invoke(caller, a) },
            Fn("[method]device.start", type(6), needsRealloc = true, mayWait = false) { caller, a -> start(caller, a) },
            Fn("[method]pending-call.take", type(2), needsRealloc = true, mayWait = false) { caller, a -> take(caller, a) },
            Fn("[resource-drop]device", type(1), needsRealloc = false, mayWait = false) { _, a ->
                handleOrTrap("[resource-drop]device", a[0].i()) { drop(a[0].i(), "device") { it is DeviceHandle } }
                NONE
            },
            Fn("[resource-drop]pending-call", type(1), needsRealloc = false, mayWait = false) { _, a ->
                handleOrTrap("[resource-drop]pending-call", a[0].i()) { drop(a[0].i(), "pending-call") { it is PendingDeviceCall } }
                NONE
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
