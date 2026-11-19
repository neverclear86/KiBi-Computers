package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.fd.HandlePlacement
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.process.HostResourceTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.component.ComponentImportPolicy
import pro.ikili.kibicomputers.core.runtime.component.InterfaceName
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentCall
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunction
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.runtime.component.link.HostInterface
import pro.ikili.kibicomputers.core.runtime.component.link.HostResource
import pro.ikili.kibicomputers.core.runtime.component.link.HostResourceIds
import pro.ikili.kibicomputers.core.runtime.component.link.HostWait
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors

/**
 * カーネルがコンポーネントをリンクするホスト（#685。ADR 0685・0263）。`wasi:*` は [WasiP2Host]（`@0.2.<n>`）、`kibi:*` は [KibiP2Host]
 * （`@0.1.0` の完全一致。ADR 0073 の Q5-A）に渡す。どちらも固定で状態を持たないので、これも固定（`KernelDrivers.COMPONENT_HOST`）
 */
internal object KernelComponentHost : ComponentHost {
    override fun interfaceOf(name: String): HostInterface? = when {
        name.startsWith("wasi:") -> WasiP2Host.interfaceOf(name)
        name.startsWith("kibi:") -> KibiP2Host.interfaceOf(name)
        else -> null
    }
}

/**
 * `kibi:*` の p2 のホスト（#685。ADR 0685）。出すのは各パッケージの `imports-p2` の world の interface（[KibiP2Wit.TEXT]）:
 * `kibi:kernel/{types,events,timers,events-poll,timers-poll}`、`kibi:process/{process,process-poll}`、`kibi:io/{tty,pipe-streams}`、
 * `kibi:fs/mounts`、`kibi:device/{types,bus,bus-poll}`、`kibi:computer/{memory,clock,power,access}`。
 * p1 だけの `kibi:io/fd`・`kibi:io/pipe`、p3 の `*-async` は出さない（`null`。読み込みの失敗 → `NOEXEC`）。
 * `kibi:computer/memory` の `commit` は、lower した `commit` を import した core インスタンスの線形メモリ（[ComponentCall.callerMemory]）の区画を数える（#777。ADR 0777）。
 * 待たないので [HostWait.NONE]（[WasiP2Io.waiting] で作らない）
 *
 * 待ちうる関数（#757・#800）は、`kibi:device/bus` の `[method]device.invoke` と、`kibi:process` の `[static]process.spawn`・
 * `spawn-with-handles` だけ。どれも [WasiP2Io.waiting] で作り（[HostWait.UNWIND]）、巻き戻せるときは巻き戻して待つ。待ち方は
 * `wasi:*` と同じく本体から決める（[WasiP2Io.waitOf]。名前の表を持たないので、本体と食い違わない）
 *
 * - 型は [KibiP2Wit.TEXT] を [WitReader] で読んだもの。`use wasi:…@0.2.12` の型は [WasiP2Host] が読んだものに解決するので、
 *   `pollable`・`input-stream`・`output-stream`・`descriptor` は `wasi:*` と同じ resource（同じ識別子・同じ dtor）になる
 * - 関数の実装は WIT の全関数にそろえる（足りない・余るのは作りの誤り）。`fallback` は使わない
 * - 状態を持たない（[WasiP2Host] と同じ）。実体はプロセスの [HostResourceTable] に [KernelEntry] として置き、中身は p1 と同じ
 *   カーネルのオブジェクト。デバイスがあるかは呼び出しのときに見る
 * - 燃料は呼び出しごとに p1 と同じ（[P2.guard]）。エラーは `kibi:kernel/types.error-code`（[KibiErrors]）。ハンドル表が満杯なら
 *   `mfile`、マシン全体の上限（[ResourceLimitException]）は `nfile`。どちらもトラップにしない
 */
internal object KibiP2Host : ComponentHost {
    private class Built(val interfaces: Map<String, HostInterface>, val wit: Map<String, WitInterface>)

    private val built: Built by lazy { build() }

    /** 版を外した名前 → インターフェース */
    val interfaces: Map<String, HostInterface> get() = built.interfaces

    /** 版を外した名前 → 読んだ WIT */
    val wit: Map<String, WitInterface> get() = built.wit

    override fun interfaceOf(name: String): HostInterface? {
        val n = InterfaceName.parse(name) ?: return null
        if (n.namespace != "kibi" || n.version != ComponentImportPolicy.KIBI_VERSION) return null
        return built.interfaces["kibi:${n.pkg}/${n.iface}"]
    }

    private fun build(): Built {
        val imported = WasiP2Host.wit.mapKeys { "${it.key}@${WasiP2Wit.BASE_VERSION}" }
        val (wit, names) = WitReader.read(KibiP2Wit.TEXT, HostResourceIds.KIBI_FIRST, imported)
        check(names.keys.all { it < HostResourceIds.KIBI_END }) { "kibi:* の resource が多すぎる" }
        val resources = HashMap<Int, HostResource>(WasiP2Host.resources)
        for (id in names.keys) resources[id] = HostResource(id, WasiP2Host.DROP)
        val t = WasiP2Types(wit)
        val bodies = HashMap<String, Map<String, HostFunctionBody>>()
        bodies.putAll(KibiP2Kernel.functions(t))
        bodies.putAll(KibiP2Process.functions(t))
        bodies.putAll(KibiP2Device.functions(t))
        val out = LinkedHashMap<String, HostInterface>()
        for ((key, w) in wit) {
            val impl = bodies.remove(key) ?: emptyMap()
            val missing = w.functions.keys - impl.keys
            val extra = impl.keys - w.functions.keys
            check(missing.isEmpty() && extra.isEmpty()) { "$key の実装が WIT と合わない（ない: $missing、余り: $extra）" }
            // 待ち方は本体から決める（`wasi:*` と同じ。#800）。P2.guard で包む前の本体に当てる
            val functions = w.functions.mapValues { (n, type) ->
                val body = impl.getValue(n)
                HostFunction(type, WasiP2Io.waitOf(body), P2.guard(body))
            }
            out[key] = HostInterface("$key@${ComponentImportPolicy.KIBI_VERSION}", functions, w.resources.mapValues { resources.getValue(it.value) })
        }
        check(bodies.isEmpty()) { "WIT にないインターフェースの実装: ${bodies.keys}" }
        return Built(out, wit)
    }
}

/** `kibi:*` の p2 の実装の共通の部品（#685） */
internal object KibiP2 {
    /** `error-code` の値（判別子 = p1 の errno − 1） */
    fun code(errno: Errno): CanonValue = CanonValue.Case(KibiErrors.code(errno), null)

    /** `result<_, error-code>`・`result<T, error-code>`。[FsException] は err、マシン全体の上限（[ResourceLimitException]）は `nfile` */
    inline fun result(block: () -> CanonValue?): CanonValue = try {
        CanonValue.ok(block())
    } catch (e: FsException) {
        CanonValue.err(code(e.errno))
    } catch (e: ResourceLimitException) {
        CanonValue.err(code(Errno.NFILE))
    }

    /** [label] の resource を、プロセスのホストの resource の表に [KernelEntry] として置く置き場（満杯なら `MFILE`） */
    fun placement(ctx: ProcessContext, label: String): HandlePlacement = object : HandlePlacement {
        override fun checkRoomLocked() {
            if (P2.tableLocked(ctx).size >= HostResourceTable.LIMIT) fsError(Errno.MFILE, "ホストの resource の数が上限（${HostResourceTable.LIMIT}）")
        }

        override fun placeLocked(entry: HandleEntry): Int {
            checkRoomLocked()
            return try {
                P2.tableLocked(ctx).add(KernelEntry(entry, label))
            } catch (e: ResourceLimitException) {
                fsError(Errno.MFILE, e.message)
            }
        }

        override fun unplaceLocked(slot: Int) {
            P2.tableLocked(ctx).remove(slot)
        }
    }

    /**
     * ゲストに rep を渡していない表の項目 [slot]（巻き戻して待つ `invoke` の `pending-call`。#800）を外し、持っている参照を返す。
     * もうなければ何もしない
     */
    fun discard(ctx: ProcessContext, slot: Int) {
        ctx.withKernelLock {
            val e = P2.tableLocked(ctx).remove(slot) ?: return@withKernelLock
            val id = e.objectId
            if (id != 0 && ctx.kernel.objects[id] != null) ctx.kernel.objects.release(id, ctx.wakeSink)
        }
    }

    /** own・borrow の値 [v] の [label] の実体（カーネルロックの中）。種類が違えば内部の誤り（トラップ） */
    fun entryLocked(ctx: ProcessContext, v: CanonValue, label: String): KernelEntry {
        val e = P2.entryLocked<KernelEntry>(ctx, v)
        if (e.label != label) P2.trap("ホストの resource ${(v as CanonValue.Handle).value} は $label でない（${e.label}）")
        return e
    }

    /** [entryLocked] のハンドルをロックを取って引く */
    fun handle(ctx: ProcessContext, v: CanonValue, label: String): HandleEntry = ctx.withKernelLock { entryLocked(ctx, v, label).handle }

    /** [label] の resource の pollable（`*-poll` の `subscribe`） */
    fun subscribe(ctx: ProcessContext, v: CanonValue, label: String): CanonValue {
        ctx.withKernelLock { entryLocked(ctx, v, label) }
        return WasiP2Io.subscribeKernel(ctx, (v as CanonValue.Handle).value)
    }

    /** `u32` の値を符号なしで広げる */
    fun u32(v: CanonValue): Long = (v as CanonValue.I32).value.toLong() and 0xffff_ffffL

    /** [s] の UTF-8 のバイト数が [max] を超えたら [errno]（p1 が lift で確かめるのと同じ上限） */
    @Throws(FsException::class)
    fun checkBytes(s: String, max: Int, errno: Errno, what: String) {
        val n = s.toByteArray(Charsets.UTF_8).size
        if (n > max) fsError(errno, "$what は $max バイトまで（$n）")
    }

    fun body(f: (ProcessContext, List<CanonValue>) -> CanonValue?): HostFunctionBody = HostFunctionBody { call, args -> f(P2.ctx(call), args) }
}
