package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.DeviceCallException
import pro.ikili.kibicomputers.core.device.DeviceInfo
import pro.ikili.kibicomputers.core.device.DeviceLimits
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.process.ComponentSuspend
import pro.ikili.kibicomputers.core.kernel.process.DeviceInvokeStart
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.wasi.kibi.KibiDevice
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import pro.ikili.kibicomputers.core.wasi.p2.KibiP2.body

/**
 * `kibi:device/bus`・`kibi:device/bus-poll` の p2 の実装（#685。ADR 0685）。意味は p1 と同じ（ADR 0077）:
 *
 * - `open`: 36 バイトでない address は `detached`。マシン全体の上限は `kernel(nfile)`、表が満杯なら `kernel(mfile)`
 * - `invoke`・`start` の引数は p1 の lift と同じ順・同じコード（method が 64 バイトを超えたら `name-too-long`、引数が 32 個を超える・
 *   text・bytes の合計が 64 KiB を超えたら `too-big`）
 * - `invoke` は待ちうる関数（[WasiP2Io.waiting]。#800）。待機点型では p1 の UNWIND と同じく巻き戻して待ち、ブロッキング型（と作っている途中）は
 *   `ctx.invokeDevice` で待つ。待機点型で、メインスレッドのメソッドを呼んだときにホストの表が満杯なら `kernel(mfile)`（`pending-call` を置けない。
 *   ブロッキング型では置かないのでならない。p1 の待機点型と同じ差）
 * - `start` の `pending-call` の数（`MAX_PENDING_CALLS`）は fd 表と p2 の表を合わせて数え、超えたら `kernel(again)`
 * - 燃料は p1 と同じ（`list-devices`・`methods` は件数分、`invoke`・`start`・`take` はカーネルが払う）
 */
internal object KibiP2Device {
    const val BUS: String = "kibi:device/bus"
    const val BUS_POLL: String = "kibi:device/bus-poll"
    const val TYPES: String = "kibi:device/types"

    const val DEVICE: String = "device"
    const val PENDING_CALL: String = "pending-call"

    // call-error の判別子（宣言の順）
    private const val CALL_ERROR_KERNEL = 0
    private const val CALL_ERROR_DETACHED = 1
    private const val CALL_ERROR_NO_SUCH_METHOD = 2
    private const val CALL_ERROR_BAD_ARGUMENTS = 3
    private const val CALL_ERROR_FAILED = 4

    fun functions(t: WasiP2Types): Map<String, Map<String, HostFunctionBody>> {
        val bus = mapOf(
            "list-devices" to body { ctx, _ ->
                val entries = ctx.listDevices()
                ctx.chargeFuel(entries.size * KibiDevice.DEVICE_LIST_FUEL_PER_ENTRY)
                CanonValue.Items(entries.map(::deviceInfo))
            },
            "[static]device.open" to body { ctx, a ->
                callResult {
                    val address = P2.str(a[0])
                    if (address.toByteArray(Charsets.UTF_8).size != KibiDevice.ADDRESS_LENGTH) throw DeviceCallException(CallError.Detached)
                    CanonValue.Handle(ctx.openDevice(address, KibiP2.placement(ctx, DEVICE)))
                }
            },
            "[method]device.info" to body { ctx, a -> deviceInfo(ctx.deviceInfo(KibiP2.handle(ctx, a[0], DEVICE))) },
            "[method]device.methods" to body { ctx, a ->
                val list = ctx.deviceMethods(KibiP2.handle(ctx, a[0], DEVICE))
                ctx.chargeFuel(list.size * KibiDevice.DEVICE_LIST_FUEL_PER_ENTRY)
                CanonValue.Items(list.map { CanonValue.Fields(listOf(CanonValue.str(it.name), CanonValue.Bool(it.mainThread))) })
            },
            "[method]device.invoke" to WasiP2Io.waiting { ctx, w, a -> invoke(ctx, w, a) },
            "[method]device.start" to body { ctx, a ->
                callResult {
                    val (method, args) = callArgs(a[1], a[2])
                    CanonValue.Handle(ctx.startDeviceCall(KibiP2.handle(ctx, a[0], DEVICE), method, args, KibiP2.placement(ctx, PENDING_CALL)))
                }
            },
            "[method]pending-call.take" to body { ctx, a ->
                val result = try {
                    ctx.takeDeviceCall(KibiP2.handle(ctx, a[0], PENDING_CALL))
                } catch (e: FsException) {
                    CallResult.Error(KibiDevice.kernelError(e))
                }
                when (result) {
                    null -> CanonValue.NONE
                    is CallResult.Values -> CanonValue.some(CanonValue.ok(values(result.values)))
                    is CallResult.Error -> CanonValue.some(CanonValue.err(callError(result.error)))
                }
            },
        )
        val busPoll = mapOf("subscribe" to body { ctx, a -> KibiP2.subscribe(ctx, a[0], PENDING_CALL) })
        return mapOf(TYPES to emptyMap(), BUS to bus, BUS_POLL to busPoll)
    }

    /**
     * `[method]device.invoke`。巻き戻せるとき（#800）は、デバイスを 1 回だけ引いて始め（`ctx.beginDeviceInvoke`）、メインスレッドのメソッドは
     * `pending-call` をホストの表の隠れた項目に置いて巻き戻す。巻き直して着いたら、引数を確かめ直さずにその結果を受け取って閉じる
     * （メソッドは 1 回だけ走る）。巻き戻せないとき（ブロッキング型・作っている途中）は `ctx.invokeDevice` でスレッドを持って待つ。
     * 巻き戻したら `null`（戻り値は捨てられる）
     */
    private fun invoke(ctx: ProcessContext, w: WasiP2Io.Waiter, a: List<CanonValue>): CanonValue? {
        val s = w.suspend
        if (w.resumed) {
            // 巻き直して着いたのに待ちがない（コンポーネントは保存できないので起きないはず）: やり直すとメソッドが二重に走りうるので、やり直さない
            val pending = s.pendingInvoke ?: return CanonValue.err(callError(CallError.INTERRUPTED))
            return takeOrUnwind(ctx, w, pending)
        }
        s.pendingInvoke?.let {
            // 新しい呼び出しなのに前の呼び出しが残っている（起きないはず）。閉じてから最初から行う
            s.pendingInvoke = null
            KibiP2.discard(ctx, it.slot)
        }
        if (!w.canUnwind()) {
            return callResult {
                val (method, args) = callArgs(a[1], a[2])
                values(ctx.invokeDevice(KibiP2.handle(ctx, a[0], DEVICE), method, args))
            }
        }
        return callFailure {
            val (method, args) = callArgs(a[1], a[2])
            val device = KibiP2.handle(ctx, a[0], DEVICE)
            when (val started = ctx.beginDeviceInvoke(device, method, args, mayWait = true, KibiP2.placement(ctx, PENDING_CALL))) {
                is DeviceInvokeStart.Done -> fromResult(started.result)
                is DeviceInvokeStart.Waiting -> {
                    val p = ComponentSuspend.PendingInvoke(started.callFd, started.key)
                    s.pendingInvoke = p
                    takeOrUnwind(ctx, w, p)
                }
            }
        }
    }

    /** [pending] の結果があれば受け取って表から外し、なければその key で巻き戻して `null` */
    private fun takeOrUnwind(ctx: ProcessContext, w: WasiP2Io.Waiter, pending: ComponentSuspend.PendingInvoke): CanonValue? {
        val s = w.suspend
        val interrupted = CanonValue.err(callError(CallError.INTERRUPTED))
        val handle = ctx.withKernelLock { (P2.tableLocked(ctx)[pending.slot] as? KernelEntry)?.takeIf { it.label == PENDING_CALL }?.handle }
        if (handle == null) {
            // 表の項目がもうない（起きないはず）
            s.pendingInvoke = null
            return interrupted
        }
        val result = try {
            ctx.takeDeviceCall(handle)
        } catch (e: FsException) {
            // 項目がもう pending-call でない（起きないはず）
            s.pendingInvoke = null
            KibiP2.discard(ctx, pending.slot)
            return interrupted
        }
        if (result == null) {
            w.unwind(listOf(pending.key), null)
            return null
        }
        s.pendingInvoke = null
        KibiP2.discard(ctx, pending.slot)
        return fromResult(result)
    }

    private fun fromResult(r: CallResult): CanonValue = when (r) {
        is CallResult.Values -> CanonValue.ok(values(r.values))
        is CallResult.Error -> CanonValue.err(callError(r.error))
    }

    /** `result<T, call-error>`。カーネルの失敗は `kernel(error-code)`、マシン全体の上限は `kernel(nfile)` */
    private inline fun callResult(block: () -> CanonValue): CanonValue = checkNotNull(callFailure { CanonValue.ok(block()) })

    /** [block] の失敗を `err(call-error)` にする（[callResult] と同じ書き換え。[block] は結果の値をそのまま返す） */
    private inline fun callFailure(block: () -> CanonValue?): CanonValue? = try {
        block()
    } catch (e: FsException) {
        CanonValue.err(callError(KibiDevice.kernelError(e)))
    } catch (e: DeviceCallException) {
        CanonValue.err(callError(e.error))
    } catch (e: ResourceLimitException) {
        CanonValue.err(callError(CallError.Kernel(Errno.NFILE)))
    }

    /** `invoke`・`start` の method と args を、p1 の lift と同じ順・同じ上限で確かめる */
    @Throws(FsException::class)
    private fun callArgs(methodValue: CanonValue, argsValue: CanonValue): Pair<String, List<DeviceValue>> {
        val method = P2.str(methodValue)
        KibiP2.checkBytes(method, DeviceLimits.MAX_METHOD_BYTES, Errno.NAMETOOLONG, "method")
        val items = (argsValue as CanonValue.Items).items
        if (items.size > DeviceLimits.MAX_VALUES) fsError(Errno.TOOBIG, "引数は ${DeviceLimits.MAX_VALUES} 個まで（${items.size}）")
        var payload = 0L
        val out = ArrayList<DeviceValue>(items.size)
        for (v in items) {
            val c = v as CanonValue.Case
            out += when (c.index) {
                0 -> DeviceValue.none()
                1 -> DeviceValue.of((c.payload as CanonValue.I64).value)
                2 -> DeviceValue.of((c.payload as CanonValue.F64).value)
                3 -> DeviceValue.of((c.payload as CanonValue.Bool).value)
                4 -> {
                    val s = P2.str(checkNotNull(c.payload))
                    payload += s.toByteArray(Charsets.UTF_8).size
                    DeviceValue.of(s)
                }
                5 -> {
                    val b = (c.payload as CanonValue.Bytes).bytes
                    payload += b.size
                    DeviceValue.of(b)
                }
                else -> fsError(Errno.INVAL, "value の判別子が不正（${c.index}）")
            }
        }
        if (payload > DeviceLimits.MAX_PAYLOAD_BYTES) {
            fsError(Errno.TOOBIG, "text・bytes の合計は ${DeviceLimits.MAX_PAYLOAD_BYTES} バイトまで（$payload）")
        }
        return method to out
    }

    private fun deviceInfo(d: DeviceInfo): CanonValue = CanonValue.Fields(listOf(CanonValue.str(d.address), CanonValue.str(d.kind)))

    /** `list<value>` */
    private fun values(list: List<DeviceValue>): CanonValue = CanonValue.Items(list.map(::value))

    private fun value(v: DeviceValue): CanonValue = when (v.type) {
        DeviceValue.Type.NONE -> CanonValue.Case(0, null)
        DeviceValue.Type.INTEGER -> CanonValue.Case(1, CanonValue.I64(v.asLong()))
        DeviceValue.Type.FLOAT -> CanonValue.Case(2, CanonValue.F64(v.asDouble()))
        DeviceValue.Type.BOOLEAN -> CanonValue.Case(3, CanonValue.Bool(v.asBoolean()))
        DeviceValue.Type.TEXT -> CanonValue.Case(4, CanonValue.str(v.asText()))
        DeviceValue.Type.BYTES -> CanonValue.Case(5, CanonValue.Bytes(v.asBytes()))
    }

    private fun callError(e: CallError): CanonValue = when (e) {
        is CallError.Kernel -> CanonValue.Case(CALL_ERROR_KERNEL, CanonValue.Case(KibiErrors.code(e.errno), null))
        CallError.Detached -> CanonValue.Case(CALL_ERROR_DETACHED, null)
        CallError.NoSuchMethod -> CanonValue.Case(CALL_ERROR_NO_SUCH_METHOD, null)
        is CallError.BadArguments -> CanonValue.Case(CALL_ERROR_BAD_ARGUMENTS, CanonValue.str(e.message))
        is CallError.Failed -> CanonValue.Case(CALL_ERROR_FAILED, CanonValue.str(e.message))
    }
}
