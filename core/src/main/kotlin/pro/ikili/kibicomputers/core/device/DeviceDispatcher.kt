package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.api.device.DeviceArguments
import pro.ikili.kibicomputers.api.device.DeviceCallContext
import pro.ikili.kibicomputers.api.device.DeviceCompletion
import pro.ikili.kibicomputers.api.device.DeviceException
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.HostCall
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * デバイスの呼び出しの振り分け（#65、ADR 0065）。[DeviceBus] ごとに1つ。
 *
 * - 直接（`direct`）: 呼んだスレッドでその場で実行する。カーネルロックを持たずに呼ぶ（デバイスは自分のロックで守る）
 * - メインスレッド（`mainThread`）: `Machine.submitHostCall`（#358）で列に積み、`tick()` の中で実行する
 *   （1 tick に `maxHostCallsPerTick` まで）
 * - 後で返す（[DeviceCallContext.defer]）: メインスレッドで受け取った [DeviceCompletion] を後の tick で終える。
 *   終わるまでは「実行中」で、ホスト呼び出しの件数は使わない。マシンを止めたら [cancelAllLocked] で取り消す
 *
 * 結果は future で返す。失敗は [DeviceCallException]（[CallError]）で終える。上限は [DeviceLimits]。
 */
internal class DeviceDispatcher(private val bus: DeviceBus) {
    /** 実行中の後で返す呼び出し */
    private val running: MutableSet<Deferred> = ConcurrentHashMap.newKeySet()

    /** 始めた呼び出し。[fuel] は呼んだプロセスが払う燃料（基本の費用 + 引数のバイト数 + 直接のメソッドが使った分。結果の分は別） */
    class Started(val future: CompletableFuture<List<DeviceValue>>, val fuel: Long)

    /** 実行中の後で返す呼び出しの数（テスト用） */
    val runningCount: Int get() = running.size

    /**
     * [address] のデバイスのメソッド [method] を引数 [args] で呼び始める。直接のメソッドは、返る前に終わっている。
     * [pid] は出したプロセス（0 = プロセスでない）。[onRun] は、実行し始める直前に（実行するスレッドで）呼ぶ。
     * 待たない。投げない（失敗は future の [DeviceCallException]。メインスレッドの呼び出しを止めて取り消したときは
     * `ProcessKilledException`）
     */
    fun start(pid: Int, address: String, method: String, args: List<DeviceValue>, onRun: (() -> Unit)? = null): Started {
        val argBytes = DeviceLimits.payloadBytes(args)
        val fuel = DeviceLimits.CALL_FUEL + argBytes * DeviceLimits.FUEL_PER_BYTE
        if (args.size > DeviceLimits.MAX_VALUES || argBytes > DeviceLimits.MAX_PAYLOAD_BYTES) {
            return Started(failed(CallError.Kernel(Errno.TOOBIG)), fuel)
        }
        if (DeviceLimits.utf8Length(method) > DeviceLimits.MAX_METHOD_BYTES) {
            return Started(failed(CallError.Kernel(Errno.NAMETOOLONG)), fuel)
        }
        val device = bus.find(address) ?: return Started(failed(CallError.Detached), fuel)
        val entry = device.methods.find(method) ?: return Started(failed(CallError.NoSuchMethod), fuel)
        val arguments = DeviceArguments(args.toList())
        if (!entry.mainThread) {
            onRun?.invoke()
            val context = CallContext(device, method, mainThread = false)
            val future = invokeHandler(entry, context, arguments)
            return Started(future, saturatingAdd(fuel, context.consumed))
        }
        val outer = bus.machine.submitHostCall(
            pid,
            HostCall {
                onRun?.invoke()
                if (!device.attached) failed(CallError.Detached) else invokeHandler(entry, CallContext(device, method, mainThread = true), arguments)
            },
        )
        return Started(outer.thenCompose { it }, fuel)
    }

    /** 実行中の後で返す呼び出しを、すべて `failed("interrupted")` で終え、[DeviceCompletion.isCancelled] を立てる（マシンを止めた） */
    fun cancelAllLocked() {
        if (running.isEmpty()) return
        for (d in running.toList()) {
            d.cancelled = true
            d.finish(CallError.INTERRUPTED)
        }
    }

    private fun invokeHandler(entry: DeviceMethods.Entry, context: CallContext, args: DeviceArguments): CompletableFuture<List<DeviceValue>> {
        val error: CallError
        try {
            @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
            val values: Array<out DeviceValue?> = entry.handler.call(context, args) ?: arrayOfNulls(0)
            context.deferred?.let { return it.future }
            return checkResult(values, context)
        } catch (e: BadArgumentsException) {
            error = CallError.BadArguments(e.message ?: "")
        } catch (e: DeviceException) {
            error = CallError.Failed(e.message ?: "")
        } catch (e: Exception) {
            LOG.log(System.Logger.Level.WARNING, "デバイス ${context.address} のメソッド ${context.method} が例外を投げた", e)
            error = CallError.INTERNAL_ERROR
        }
        // 後で返すと決めてから投げたら、その呼び出しを失敗で終える
        val d = context.deferred ?: return failed(error)
        d.finish(error)
        return d.future
    }

    private fun checkResult(values: Array<out DeviceValue?>, context: CallContext): CompletableFuture<List<DeviceValue>> {
        val list = ArrayList<DeviceValue>(values.size)
        for (v in values) {
            if (v == null) {
                LOG.log(System.Logger.Level.WARNING, "デバイス ${context.address} のメソッド ${context.method} が null の値を返した")
                return failed(CallError.INTERNAL_ERROR)
            }
            list += v
        }
        if (!DeviceLimits.fits(list)) return failed(CallError.Kernel(Errno.TOOBIG))
        return CompletableFuture.completedFuture(list)
    }

    /** 呼び出し1回の [DeviceCallContext] */
    private inner class CallContext(
        private val device: AttachedDevice,
        override val method: String,
        mainThread: Boolean,
    ) : DeviceCallContext {
        override val address: String get() = device.address

        override val isMainThread: Boolean = mainThread

        /** [consumeFuel] で使った燃料 */
        var consumed: Long = 0
            private set

        /** [defer] で作ったもの */
        var deferred: Deferred? = null
            private set

        override fun consumeFuel(amount: Long) {
            if (isMainThread || amount <= 0) return
            consumed = saturatingAdd(consumed, amount)
        }

        override fun defer(): DeviceCompletion {
            check(isMainThread) { "direct のメソッドは defer() できない（メインスレッドのメソッドだけ）" }
            check(deferred == null) { "defer() は1回の呼び出しで1回だけ" }
            val d = Deferred()
            running += d
            deferred = d
            return d
        }
    }

    /** 後で返す呼び出しの結果（[DeviceCompletion]）。どのスレッドから終えてもよい */
    private inner class Deferred : DeviceCompletion {
        val future = CompletableFuture<List<DeviceValue>>()

        @Volatile
        var cancelled: Boolean = false

        override fun complete(vararg values: DeviceValue) {
            val list = values.toList()
            if (!DeviceLimits.fits(list)) {
                finish(CallError.Kernel(Errno.TOOBIG))
                return
            }
            if (future.complete(list)) running.remove(this)
        }

        override fun fail(message: String) = finish(CallError.Failed(message))

        fun finish(error: CallError) {
            if (future.completeExceptionally(DeviceCallException(error))) running.remove(this)
        }

        override val isCancelled: Boolean get() = cancelled
    }

    companion object {
        private val LOG: System.Logger = System.getLogger("kibicomputers.device")

        /** [error] で終えた future */
        fun failed(error: CallError): CompletableFuture<List<DeviceValue>> =
            CompletableFuture<List<DeviceValue>>().also { it.completeExceptionally(DeviceCallException(error)) }

        /**
         * 終わった [future] の結果を [CallResult] にする。[DeviceCallException] はその失敗、それ以外の失敗（止めて取り消したなど）は
         * `failed("interrupted")`
         */
        fun resultOf(future: CompletableFuture<List<DeviceValue>>): CallResult {
            check(future.isDone) { "まだ終わっていない" }
            return try {
                CallResult.Values(future.getNow(null))
            } catch (e: Throwable) {
                CallResult.Error(errorOf(e))
            }
        }

        /** 失敗 [t] の [CallError]（[CompletionException] は中を見る） */
        fun errorOf(t: Throwable): CallError {
            var e: Throwable = t
            while ((e is CompletionException || e is java.util.concurrent.ExecutionException) && e.cause != null) e = e.cause!!
            return (e as? DeviceCallException)?.error ?: CallError.INTERRUPTED
        }

        private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
