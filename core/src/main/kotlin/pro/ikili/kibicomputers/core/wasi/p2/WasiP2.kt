package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.process.HostResourceTable
import pro.ikili.kibicomputers.core.kernel.process.ComponentSuspend
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.ComponentImportPolicy
import pro.ikili.kibicomputers.core.runtime.component.InterfaceName
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentCall
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunction
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.runtime.component.link.HostInterface
import pro.ikili.kibicomputers.core.runtime.component.link.HostResource
import pro.ikili.kibicomputers.core.runtime.component.link.HostResourceDrop
import pro.ikili.kibicomputers.core.runtime.component.link.HostResourceIds
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Fd

/**
 * `wasi:*` の版の受け付け方（#267。ADR 0267）。受けるのは `@0.2.<n>` だけで、`<n>` は問わない（ホストの基準
 * [WasiP2Wit.BASE_VERSION] より新しくても古くてもよい。wasmtime の Linker と同じく semver の互換として扱う）。
 * 版なし・pre-release・`+build` 付き・`0.3.x`（#268 まで）・ほかの版は断る
 */
internal object WasiP2Versions {
    /** `wasi:*` の名前 [name] の版を断る理由。受けるなら `null`（[ComponentImportPolicy.rejectWasiVersion]） */
    fun reject(name: InterfaceName): String? = ComponentImportPolicy.rejectWasiVersion(name)

    /** import・export の名前 [text] が受け付ける `wasi:*` なら、版を外した名前（`wasi:io/streams`）。ほかは `null` */
    fun key(text: String): String? {
        val n = InterfaceName.parse(text) ?: return null
        if (n.namespace != "wasi" || reject(n) != null) return null
        return "wasi:${n.pkg}/${n.iface}"
    }
}

/**
 * `wasi:*` のホスト（#267。ADR 0267）。WASI 0.2（基準 [WasiP2Wit.BASE_VERSION]）の全インターフェースを出す。
 *
 * - 名前から版を外して引く（[WasiP2Versions]）。どの 0.2.x にも同じ [HostInterface]・同じ [HostResource] を返すので、
 *   `@0.2.3` と `@0.2.12` の pollable は同じ resource の型になる
 * - 型は [WasiP2Wit.TEXT] を [WitReader] で読んだもの。関数の実装は WIT の全関数にそろえる（足りない・余るのは作りの誤りで、
 *   最初に使うときに [IllegalStateException]。テストが落とす）。`fallback` は使わない（ゲストが宣言した、ホストにない関数は
 *   読み込みの失敗になる）
 * - 状態を持たない。計画（`ComponentPlan`）は `ProgramCache` で全マシンが共有するので、関数の本体は多数のプロセスのスレッドから
 *   同時に呼ばれる。プロセスの状態は `KernelLinkEnv.ctx` から引き、実体はプロセスの [HostResourceTable] に置く。
 *   出すインターフェースはマシンの構成で変えない（リンクは読み込みのときに 1 回だけ）
 * - 燃料は、呼び出しごとに p1 と同じ [WasiP1Fd.CALL_FUEL]、大きな量には [WasiP1Fd.BYTES_PER_FUEL] ごとに 1（仮。確定は #251）
 */
internal object WasiP2Host : ComponentHost {
    private class Built(val interfaces: Map<String, HostInterface>, val wit: Map<String, WitInterface>, val resources: Map<Int, HostResource>)

    private val built: Built by lazy { build() }

    /** コマンドの入口のインターフェース（版を外した名前） */
    const val RUN: String = "wasi:cli/run"

    /** 版を外した名前 → インターフェース */
    val interfaces: Map<String, HostInterface> get() = built.interfaces

    /** 版を外した名前 → 読んだ WIT */
    val wit: Map<String, WitInterface> get() = built.wit

    /** 識別子 → ホストの resource（`kibi:*` のホストが `use` した `pollable` などを同じものにする。#685） */
    val resources: Map<Int, HostResource> get() = built.resources

    override fun interfaceOf(name: String): HostInterface? = WasiP2Versions.key(name)?.let { built.interfaces[it] }

    /** 1 つの dtor（どの resource も、表から外して参照を返す。子の残る親はトラップ） */
    val DROP: HostResourceDrop = HostResourceDrop { call, rep -> P2.drop(P2.ctx(call), rep) }

    private fun build(): Built {
        val (wit, names) = WitReader.read(WasiP2Wit.TEXT, HostResourceIds.WASI_FIRST)
        check(names.keys.all { it < HostResourceIds.WASI_END }) { "wasi:* の resource が多すぎる" }
        val resources = names.keys.associateWith { HostResource(it, DROP) }
        val t = WasiP2Types(wit)
        val bodies = HashMap<String, Map<String, HostFunctionBody>>()
        bodies.putAll(WasiP2Io.functions(t))
        bodies.putAll(WasiP2Cli.functions(t))
        bodies.putAll(WasiP2Fs.functions(t))
        bodies.putAll(WasiP2Sockets.functions(t))
        val out = LinkedHashMap<String, HostInterface>()
        for ((key, w) in wit) {
            // `wasi:cli/run` はゲストが export するもの（ホストは出さない。ドライバーが呼ぶ）
            if (key == RUN) continue
            val impl = bodies.remove(key) ?: emptyMap()
            val missing = w.functions.keys - impl.keys
            val extra = impl.keys - w.functions.keys
            check(missing.isEmpty() && extra.isEmpty()) { "$key の実装が WIT と合わない（ない: $missing、余り: $extra）" }
            val functions = w.functions.mapValues { (n, type) ->
                val body = impl.getValue(n)
                HostFunction(type, WasiP2Io.waitOf(body), P2.guard(body))
            }
            out[key] = HostInterface("$key@${WasiP2Wit.BASE_VERSION}", functions, w.resources.mapValues { resources.getValue(it.value) })
        }
        check(bodies.isEmpty()) { "WIT にないインターフェースの実装: ${bodies.keys}" }
        return Built(out, wit, resources)
    }
}

/** 実装が値を作る・読むのに使う、名前の付いた型の引き方 */
internal class WasiP2Types(private val wit: Map<String, WitInterface>) {
    /** 版を外した名前 → インターフェース */
    val all: Map<String, WitInterface> get() = wit

    operator fun get(iface: String): WitInterface = checkNotNull(wit[iface]) { "WIT に $iface がない" }
}

/** p2 の層の共通の部品 */
internal object P2 {
    /** 1 回の `read`・`skip`・`splice` の上限（少なく返してよい関数なので、ゲストの決める長さで先に確保しない） */
    const val MAX_READ: Int = 64 * 1024

    /** `blocking-write-and-flush`・`blocking-write-zeroes-and-flush` の上限（仕様の 4096 バイト。超えたらトラップ） */
    const val MAX_BLOCKING_WRITE: Long = 4096

    /** ファイル・閉じない出力の `check-write` が許す量 */
    const val FILE_WRITE_ALLOWANCE: Long = 64 * 1024

    fun trap(message: String): Nothing = throw WasmTrapException(WasmTrapKind.OTHER, message)

    /** [call] のプロセスの口（カーネルの外で動かしたコンポーネントからは呼べない） */
    fun ctx(call: ComponentCall): ProcessContext = (call.env as? KernelLinkEnv)?.ctx ?: trap("wasi:* はカーネルの中でだけ呼べる")

    /** [call] のプロセスの、待ちうるホストの関数の入口と出口（#757） */
    fun suspend(call: ComponentCall): ComponentSuspend = (call.env as? KernelLinkEnv)?.suspend ?: trap("wasi:* はカーネルの中でだけ呼べる")

    /** 呼び出しごとの燃料と、ホストの限界（[ResourceLimitException]）のトラップへの書き換え */
    fun guard(body: HostFunctionBody): HostFunctionBody = HostFunctionBody { call, args ->
        val ctx = ctx(call)
        ctx.chargeFuel(WasiP1Fd.CALL_FUEL)
        try {
            body.call(call, args)
        } catch (e: ResourceLimitException) {
            trap("${call.functionName}: ${e.message}")
        }
    }

    /** このプロセスのホストの resource の表（カーネルロックの中） */
    fun tableLocked(ctx: ProcessContext): HostResourceTable =
        (ctx.kernel.processes[ctx.pid] ?: throw ProcessKilledException()).hostResources

    /**
     * 表にあと 1 つ入れられることを確かめる（カーネルロックの中。満杯なら [ResourceLimitException] → [guard] がトラップにする）。
     * 戻せない副作用（ファイルを作る・切り詰める）の前に呼ぶ
     */
    @Throws(ResourceLimitException::class)
    fun checkRoomLocked(ctx: ProcessContext) {
        if (tableLocked(ctx).size >= HostResourceTable.LIMIT) throw ResourceLimitException("ホストの resource の数が上限（${HostResourceTable.LIMIT}）")
    }

    /** [entry] を表に入れて own の値にする（カーネルロックの中。参照の持ち主は表になる） */
    fun ownLocked(ctx: ProcessContext, entry: P2Entry): CanonValue.Handle = CanonValue.Handle(tableLocked(ctx).add(entry))

    /** [entry] を表に入れて own の値にする */
    fun own(ctx: ProcessContext, entry: P2Entry): CanonValue.Handle = ctx.withKernelLock { ownLocked(ctx, entry) }

    /** own・borrow の値 [v] が指す実体（カーネルロックの中）。種類が違えば内部の誤り（トラップ） */
    inline fun <reified T : P2Entry> entryLocked(ctx: ProcessContext, v: CanonValue): T {
        val rep = (v as CanonValue.Handle).value
        val e = tableLocked(ctx)[rep] ?: trap("ホストの resource $rep がない")
        return e as? T ?: trap("ホストの resource $rep は ${T::class.simpleName} でない（${(e as? P2Entry)?.label}）")
    }

    /** [entryLocked] をロックを取って呼ぶ */
    inline fun <reified T : P2Entry> entry(ctx: ProcessContext, v: CanonValue): T = ctx.withKernelLock { entryLocked<T>(ctx, v) }

    /**
     * rep [rep] の resource を表から外し、持っている参照を返す（dtor・own の引数を受けたとき）。子の残る親はトラップ
     * （仕様は「親を先に drop したらトラップしてよい」。wasmtime と同じ。ADR 0267）。pollable なら親の子の数を減らす。
     * 子のハンドル（`ProcessObject`）なら、fd を閉じるときと同じく、外した後・参照を返す前に `WakeSink.processHandleClosed` で知らせる
     * （最後のハンドルなら子を付け替える。#685。`FdTable.releaseRemoved` と同じ順）
     */
    fun drop(ctx: ProcessContext, rep: Int) {
        ctx.withKernelLock {
            val process = ctx.kernel.processes[ctx.pid] ?: throw ProcessKilledException()
            val table = process.hostResources
            val e = table[rep] as? P2Entry ?: trap("ホストの resource $rep がない")
            if (e.children > 0) trap("${e.label} に子の pollable が残っている")
            table.remove(rep)
            if (e is PollableEntry && e.parent != 0) (table[e.parent] as? P2Entry)?.let { it.children-- }
            val id = e.objectId
            val obj = if (id != 0) ctx.kernel.objects[id] else null
            if (obj != null) {
                if (obj is ProcessObject) ctx.wakeSink.processHandleClosed(process, obj)
                ctx.kernel.objects.release(id, ctx.wakeSink)
            }
        }
    }

    /** 子の pollable [entry] を、親 [parent] の子として足す（カーネルロックの中） */
    fun childLocked(ctx: ProcessContext, parent: Int, entry: PollableEntry): CanonValue.Handle {
        val table = tableLocked(ctx)
        val p = table[parent] as? P2Entry ?: trap("ホストの resource $parent がない")
        val h = ownLocked(ctx, entry)
        p.children++
        return h
    }

    /** u64 の値を `Long` の範囲に飽和させる（2^63 以上は [Long.MAX_VALUE]） */
    fun u64(v: CanonValue): Long = (v as CanonValue.I64).value.let { if (it < 0) Long.MAX_VALUE else it }

    fun str(v: CanonValue): String = (v as CanonValue.Str).value

    /** [block] の [FsException] を、`result<_, error-code>` の err にする */
    inline fun fsResult(t: WasiP2Types, block: () -> CanonValue?): CanonValue = try {
        CanonValue.ok(block())
    } catch (e: FsException) {
        CanonValue.err(WasiP2Errors.code(t, e.errno))
    }
}
