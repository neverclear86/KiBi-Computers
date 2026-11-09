package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.SnapshotCapableDriver
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType

/**
 * WASI p1（`wasi_snapshot_preview1`）の層（ADR 0261 の A）。カーネルの土台の上に、p1 のホスト関数を並べる。
 *
 * 今あるのは `fd_*`（#70）、`path_*`（#71。[WasiP1Path]）、args・environ・clock・random・proc_exit・sched_yield（#69。[WasiP1Basic]）と、
 * `poll_oneoff`（#72。[WasiP1Poll]）と、独自拡張の `kibi:*` のモジュール（#408・#73。[WasiP1Kibi]）。`sock_*` はまだない。`proc_raise` は p1 でも非推奨なので足さない（`NOSYS`）。
 * まだない関数をモジュールが import していれば、`NOSYS` を返す仮の関数をつなぐ（リンクが通るように）。
 */
internal object WasiP1 {
    private val I32 = WasmValueType.I32
    private val I64 = WasmValueType.I64

    private fun type(vararg params: WasmValueType): WasmFunctionType = WasmFunctionType.of(params.toList(), listOf(I32))

    /** プロセスごとの実装 */
    private class Impl(val fd: WasiP1Fd, val path: WasiP1Path, val basic: WasiP1Basic, val poll: WasiP1Poll)

    /** 関数1つ: 名前、型、本体（p1 の errno を返す） */
    private class Fn(val name: String, val type: WasmFunctionType, val body: (Impl, WasmMemory, LongArray) -> Int)

    /** `proc_exit(rval)` の型（戻り値がない） */
    private val PROC_EXIT_TYPE: WasmFunctionType = WasmFunctionType.of(listOf(I32), emptyList())

    private const val PROC_EXIT: String = "proc_exit"

    private fun Long.i(): Int = toInt()

    private val FUNCTIONS: List<Fn> = listOf(
        Fn("args_get", type(I32, I32)) { f, m, a -> f.basic.argsGet(m, a[0].i(), a[1].i()) },
        Fn("args_sizes_get", type(I32, I32)) { f, m, a -> f.basic.argsSizesGet(m, a[0].i(), a[1].i()) },
        Fn("environ_get", type(I32, I32)) { f, m, a -> f.basic.environGet(m, a[0].i(), a[1].i()) },
        Fn("environ_sizes_get", type(I32, I32)) { f, m, a -> f.basic.environSizesGet(m, a[0].i(), a[1].i()) },
        Fn("clock_res_get", type(I32, I32)) { f, m, a -> f.basic.clockResGet(m, a[0].i(), a[1].i()) },
        Fn("clock_time_get", type(I32, I64, I32)) { f, m, a -> f.basic.clockTimeGet(m, a[0].i(), a[2].i()) },
        Fn("random_get", type(I32, I32)) { f, m, a -> f.basic.randomGet(m, a[0].i(), a[1].i()) },
        Fn("sched_yield", type()) { f, _, _ -> f.basic.schedYield() },
        Fn("poll_oneoff", type(I32, I32, I32, I32)) { f, m, a -> f.poll.pollOneoff(m, a[0].i(), a[1].i(), a[2].i(), a[3].i()) },
        Fn("fd_advise", type(I32, I64, I64, I32)) { f, _, a -> f.fd.advise(a[0].i(), a[1], a[2], a[3].i()) },
        Fn("fd_allocate", type(I32, I64, I64)) { f, _, a -> f.fd.allocate(a[0].i(), a[1], a[2]) },
        Fn("fd_close", type(I32)) { f, _, a -> f.fd.close(a[0].i()) },
        Fn("fd_datasync", type(I32)) { f, _, a -> f.fd.sync(a[0].i()) },
        Fn("fd_fdstat_get", type(I32, I32)) { f, m, a -> f.fd.fdstatGet(m, a[0].i(), a[1].i()) },
        Fn("fd_fdstat_set_flags", type(I32, I32)) { f, _, a -> f.fd.fdstatSetFlags(a[0].i(), a[1].i() and 0xffff) },
        Fn("fd_fdstat_set_rights", type(I32, I64, I64)) { f, _, a -> f.fd.fdstatSetRights(a[0].i()) },
        Fn("fd_filestat_get", type(I32, I32)) { f, m, a -> f.fd.filestatGet(m, a[0].i(), a[1].i()) },
        Fn("fd_filestat_set_size", type(I32, I64)) { f, _, a -> f.fd.filestatSetSize(a[0].i(), a[1]) },
        Fn("fd_filestat_set_times", type(I32, I64, I64, I32)) { f, _, a -> f.fd.filestatSetTimes(a[0].i(), a[1], a[2], a[3].i() and 0xffff) },
        Fn("fd_pread", type(I32, I32, I32, I64, I32)) { f, m, a -> f.fd.pread(m, a[0].i(), a[1].i(), a[2].i(), a[3], a[4].i()) },
        Fn("fd_prestat_get", type(I32, I32)) { f, m, a -> f.fd.prestatGet(m, a[0].i(), a[1].i()) },
        Fn("fd_prestat_dir_name", type(I32, I32, I32)) { f, m, a -> f.fd.prestatDirName(m, a[0].i(), a[1].i(), a[2].i()) },
        Fn("fd_pwrite", type(I32, I32, I32, I64, I32)) { f, m, a -> f.fd.pwrite(m, a[0].i(), a[1].i(), a[2].i(), a[3], a[4].i()) },
        Fn("fd_read", type(I32, I32, I32, I32)) { f, m, a -> f.fd.read(m, a[0].i(), a[1].i(), a[2].i(), a[3].i()) },
        Fn("fd_readdir", type(I32, I32, I32, I64, I32)) { f, m, a -> f.fd.readdir(m, a[0].i(), a[1].i(), a[2].i(), a[3], a[4].i()) },
        Fn("fd_renumber", type(I32, I32)) { f, _, a -> f.fd.renumber(a[0].i(), a[1].i()) },
        Fn("fd_seek", type(I32, I64, I32, I32)) { f, m, a -> f.fd.seek(m, a[0].i(), a[1], a[2].i() and 0xff, a[3].i()) },
        Fn("fd_sync", type(I32)) { f, _, a -> f.fd.sync(a[0].i()) },
        Fn("fd_tell", type(I32, I32)) { f, m, a -> f.fd.tell(m, a[0].i(), a[1].i()) },
        Fn("fd_write", type(I32, I32, I32, I32)) { f, m, a -> f.fd.write(m, a[0].i(), a[1].i(), a[2].i(), a[3].i()) },
        Fn("path_create_directory", type(I32, I32, I32)) { f, m, a -> f.path.createDirectory(m, a[0].i(), a[1].i(), a[2].i()) },
        Fn("path_filestat_get", type(I32, I32, I32, I32, I32)) { f, m, a ->
            f.path.filestatGet(m, a[0].i(), a[1].i(), a[2].i(), a[3].i(), a[4].i())
        },
        Fn("path_filestat_set_times", type(I32, I32, I32, I32, I64, I64, I32)) { f, m, a ->
            f.path.filestatSetTimes(m, a[0].i(), a[1].i(), a[2].i(), a[3].i(), a[4], a[5], a[6].i() and 0xffff)
        },
        Fn("path_link", type(I32, I32, I32, I32, I32, I32, I32)) { f, m, a ->
            f.path.link(m, a[0].i(), a[1].i(), a[2].i(), a[3].i(), a[4].i(), a[5].i(), a[6].i())
        },
        Fn("path_open", type(I32, I32, I32, I32, I32, I64, I64, I32, I32)) { f, m, a ->
            f.path.open(m, a[0].i(), a[1].i(), a[2].i(), a[3].i(), a[4].i() and 0xffff, a[5], a[6], a[7].i() and 0xffff, a[8].i())
        },
        Fn("path_readlink", type(I32, I32, I32, I32, I32, I32)) { f, m, a ->
            f.path.readlink(m, a[0].i(), a[1].i(), a[2].i(), a[3].i(), a[4].i(), a[5].i())
        },
        Fn("path_remove_directory", type(I32, I32, I32)) { f, m, a -> f.path.removeDirectory(m, a[0].i(), a[1].i(), a[2].i()) },
        Fn("path_rename", type(I32, I32, I32, I32, I32, I32)) { f, m, a ->
            f.path.rename(m, a[0].i(), a[1].i(), a[2].i(), a[3].i(), a[4].i(), a[5].i())
        },
        Fn("path_symlink", type(I32, I32, I32, I32, I32)) { f, m, a -> f.path.symlink(m, a[0].i(), a[1].i(), a[2].i(), a[3].i(), a[4].i()) },
        Fn("path_unlink_file", type(I32, I32, I32)) { f, m, a -> f.path.unlinkFile(m, a[0].i(), a[1].i(), a[2].i()) },
    )

    /** 止まりうる関数の名前（ストリームの読み書きと `poll_oneoff`。#329） */
    private val WAIT_NAMES: Set<String> = setOf("fd_read", "fd_write", "poll_oneoff")

    /**
     * 待機点への変換（#329）で止まりうるとみなす import（`"<module>.<name>"`）。`fd_write` は、パイプがいっぱいのときに `AGAIN` を返すと
     * wasi-libc がエラーとして扱いデータを失うので入れる。`sched_yield` は待ちではないので入れない。
     * `kibi:process` の `[static]process.spawn`（読み込みを待つ。#74）も入れる
     */
    val WAIT_IMPORTS: Set<String> =
        WAIT_NAMES.map { "${P1.MODULE}.$it" }.toSet() + WasiP1KibiProcess.waitNames.map { "${WasiP1KibiProcess.MODULE}.$it" }

    /** 実装している関数の名前 */
    val implemented: Set<String> = FUNCTIONS.map { it.name }.toSet() + PROC_EXIT

    /**
     * WASI p1 の関数の名前（`wasi_snapshot_preview1` の仕様にあるもの全部）。
     * まだ実装していないものも import してよい（`NOSYS` を返す仮の関数をつなぐ）。
     */
    val specFunctions: Set<String> = setOf(
        "args_get", "args_sizes_get", "environ_get", "environ_sizes_get", "clock_res_get", "clock_time_get",
        "fd_advise", "fd_allocate", "fd_close", "fd_datasync", "fd_fdstat_get", "fd_fdstat_set_flags", "fd_fdstat_set_rights",
        "fd_filestat_get", "fd_filestat_set_size", "fd_filestat_set_times", "fd_pread", "fd_prestat_get", "fd_prestat_dir_name",
        "fd_pwrite", "fd_read", "fd_readdir", "fd_renumber", "fd_seek", "fd_sync", "fd_tell", "fd_write",
        "path_create_directory", "path_filestat_get", "path_filestat_set_times", "path_link", "path_open", "path_readlink",
        "path_remove_directory", "path_rename", "path_symlink", "path_unlink_file",
        "poll_oneoff", "proc_exit", "proc_raise", "sched_yield", "random_get",
        "sock_accept", "sock_recv", "sock_send", "sock_shutdown",
    )

    /**
     * [module] の import が、p1 のプロセスに許すものだけか確かめる（#40）。だめなら `NOEXEC` の [FsException]。
     *
     * 許すのは `wasi_snapshot_preview1` の、仕様にある名前の関数だけ。実装している関数は型も一致しなければならない
     * （インスタンスを作るときのリンクの失敗を、読み込みのときに分かるようにする）。
     * 独自拡張（`kibi:` で始まるモジュール。[WasiP1Kibi]。#73）は、出している関数だけを、型が一致するときに許す。版は完全一致だけ
     * （`@0.2.0` などは断る）。`cabi_realloc` が要る関数を import するなら、モジュールが `cabi_realloc: (i32, i32, i32, i32) -> i32` を
     * export していなければ断る。モジュール `kibi` は燃料・待機点への変換の予約の空間なので、ゲストの import は断る
     * （#408 の旧名 `kibi.memory_*` も。今は [WasiP1Kibi.MEMORY]）。
     * メモリ・テーブル・グローバル・タグの import や、ほかのモジュール名（`env` など）は断る。
     */
    @Throws(FsException::class)
    fun checkImports(module: WasmModule) {
        var needsRealloc: String? = null
        for (imp in module.imports) {
            val name = "${imp.module}.${imp.name}"
            if (imp.module.startsWith(WasiP1Kibi.PREFIX)) {
                if (imp.kind != WasmExternKind.FUNCTION) fsError(Errno.NOEXEC, "関数でない import は許可していない: $name（${imp.kind}）")
                if (imp.module !in WasiP1Kibi.modules) {
                    val base = imp.module.substringBefore('@')
                    if (WasiP1Kibi.modules.any { it.substringBefore('@') == base }) {
                        fsError(Errno.NOEXEC, "独自拡張の版が違う: ${imp.module}（出しているのは ${WasiP1Kibi.modules.filter { it.startsWith("$base@") }}）")
                    }
                    fsError(Errno.NOEXEC, "許可していない import: $name")
                }
                val expected = WasiP1Kibi.typeOf(imp.module, imp.name) ?: fsError(Errno.NOEXEC, "許可していない import: $name")
                if (imp.functionType != expected) fsError(Errno.NOEXEC, "import の型が違う: $name は $expected（モジュールは ${imp.functionType}）")
                if (WasiP1Kibi.needsRealloc(imp.module, imp.name)) needsRealloc = name
                continue
            }
            if (imp.module == KIBI_RESERVED && imp.name in OLD_KIBI_NAMES) {
                fsError(Errno.NOEXEC, "旧名の import: $name（#73 で ${WasiP1Kibi.MEMORY} に改名した）")
            }
            if (imp.module != P1.MODULE) fsError(Errno.NOEXEC, "許可していない import: $name")
            if (imp.kind != WasmExternKind.FUNCTION) fsError(Errno.NOEXEC, "関数でない import は許可していない: $name（${imp.kind}）")
            if (imp.name !in specFunctions) fsError(Errno.NOEXEC, "WASI p1 にない関数の import: $name")
            val expected = typeOf(imp.name) ?: continue
            if (imp.functionType != expected) fsError(Errno.NOEXEC, "import の型が違う: $name は $expected（モジュールは ${imp.functionType}）")
        }
        if (needsRealloc != null) {
            val ok = module.exports.any {
                it.name == WasiP1Kibi.REALLOC && it.kind == WasmExternKind.FUNCTION && it.functionType == WasiP1Kibi.REALLOC_TYPE
            }
            if (!ok) fsError(Errno.NOEXEC, "$needsRealloc を import するのに ${WasiP1Kibi.REALLOC}: ${WasiP1Kibi.REALLOC_TYPE} を export していない")
        }
    }

    /** カーネルの予約のモジュール名（燃料・待機点への変換。ゲストは import できない） */
    private const val KIBI_RESERVED: String = "kibi"

    /** #408 の旧名（#73 で [WasiP1Kibi.MEMORY] に改名） */
    private val OLD_KIBI_NAMES: Set<String> = setOf("memory_remaining", "memory_commit")

    /** 実装している関数 [name] の型（なければ `null`） */
    fun typeOf(name: String): WasmFunctionType? =
        if (name == PROC_EXIT) PROC_EXIT_TYPE else FUNCTIONS.firstOrNull { it.name == name }?.type

    /**
     * p1 のホスト関数を [builder] に登録する（`ProcessDriver.configure` の中から）。
     * [module] を渡すと、それが import する p1 の関数のうち、まだ実装していないものに `NOSYS` を返す仮の関数をつなぐ。
     * [blocking] は、ストリームの読み書きと `poll_oneoff` で待つか（`BLOCKING` のプロセス。`false` なら待たずに `AGAIN`）
     */
    fun register(ctx: ProcessContext, builder: WasmInstanceConfig.Builder, module: WasmModule?, blocking: Boolean) {
        register(ctx, builder, module, if (blocking) P1Wait.BLOCK else P1Wait.NONE)
    }

    /**
     * p1 のホスト関数を [builder] に登録する。[wait] は、ストリームの読み書きと `poll_oneoff`・`kibi:process` の `spawn` の待ち方。
     * 戻り値の `suspend` は止まりうる import の入口と出口の処理（[P1Wait.UNWIND] のとき、ドライバーが巻き戻しを受け取るのに使う）
     */
    fun register(ctx: ProcessContext, builder: WasmInstanceConfig.Builder, module: WasmModule?, wait: P1Wait): WasiP1Registration {
        val basic = WasiP1Basic(ctx)
        val suspend = WasiP1Suspend(ctx)
        val impl = Impl(WasiP1Fd(ctx, wait, suspend), WasiP1Path(ctx), basic, WasiP1Poll(ctx, basic, wait, suspend))
        for (fn in FUNCTIONS) {
            val mayWait = fn.name in WAIT_NAMES
            val waits = wait == P1Wait.UNWIND && mayWait
            builder.hostFunction(
                P1.MODULE,
                fn.name,
                fn.type,
                ctx.hostFunction { c, caller, args ->
                    // ホスト関数の中から呼んだゲスト（cabi_realloc など。#73）の中では、待ち方に依らず止まりうる import を呼べない
                    // （巻き戻せず、スレッドを持ったまま待つことになるため）。巻き直しの状態にも燃料にも触れる前に止める
                    if (mayWait && c.inGuestCall) {
                        throw WasmTrapException(WasmTrapKind.OTHER, "止まりうる import ${fn.name} を cabi_realloc の中で呼んだ")
                    }
                    // 巻き直して着いたなら、ここで NORMAL に戻す（燃料を引くより前。巻き直しも普通の呼び出しとして数える）
                    if (waits) suspend.enter(caller)
                    c.chargeFuel(WasiP1Fd.CALL_FUEL)
                    val memory = caller.memory
                    val errno = if (memory == null) {
                        Errno.FAULT.code
                    } else {
                        try {
                            fn.body(impl, memory, args)
                        } catch (e: FsException) {
                            e.errno.code
                        } catch (e: WasmMemoryAccessException) {
                            Errno.FAULT.code
                        }
                    }
                    longArrayOf(errno.toLong())
                },
            )
        }
        // 独自拡張（kibi:* の p1 向けの import。#408・#73）
        val kibiProcess = WasiP1Kibi.register(ctx, builder, suspend, wait)
        // proc_exit: 戻らない。WasiP1Exit でゲストの呼び出しを抜ける（ドライバーが終了コードにする）
        builder.hostFunction(
            P1.MODULE,
            PROC_EXIT,
            PROC_EXIT_TYPE,
            ctx.hostFunction { c, _, args ->
                c.chargeFuel(WasiP1Fd.CALL_FUEL)
                throw WasiP1Exit(args[0].toInt())
            },
        )
        val registration = WasiP1Registration(suspend, kibiProcess)
        if (module == null) return registration
        for (imp in module.imports) {
            if (imp.module != P1.MODULE || imp.kind != WasmExternKind.FUNCTION || imp.name in implemented) continue
            val type = imp.functionType ?: continue
            builder.hostFunction(
                P1.MODULE,
                imp.name,
                type,
                ctx.hostFunction { _, _, _ ->
                    LongArray(type.results.size).also { if (it.isNotEmpty() && type.results[0] == I32) it[0] = Errno.NOSYS.code.toLong() }
                },
            )
        }
        return registration
    }
}

/** [WasiP1.register] の結果: 止まりうる import の入口と出口の処理と、`kibi:process` の import（#74） */
internal class WasiP1Registration(val suspend: WasiP1Suspend, val kibiProcess: WasiP1KibiProcess)

/**
 * p1 のコマンド（`_start` を export するモジュール）を動かすドライバー。
 *
 * 読み込み時に待機点への変換（#329）をかけたモジュール（[WasmModule.isAsyncified]）は `WAIT_POINT` で動かす。
 * 止まりうる import の中で巻き戻すと `_start` から戻るので、状態が巻き戻し中なら `NORMAL` に戻して `StepResult.Wait` にする。
 * 止まりうる関数の中で燃料が切れて巻き戻したとき（#339。`ctx.takeFuelUnwind()`）は `StepResult.WaitFuel` にする。
 * 起こされたら、状態を巻き直し中にして `_start` を呼び直す。変換をかけずに読み込んだモジュール（テストの設定。変換できない作りは `Machine.exec` が断る）は `BLOCKING` で動かす。
 *
 * `_start` から（巻き戻しでなく）戻ったら終了コード 0。`proc_exit(rval)` が呼ばれたら、そこで抜けて終了コード `rval`
 * （wasi-libc は `main` が 0 以外を返したときと `exit()` で呼ぶ）。
 * `proc_exit` を `_start` の外（モジュールの start 関数の中）で呼ぶと、例外で抜けたものとして `CRASHED` になる。
 */
internal class WasiP1Driver(private val module: WasmModule) : SnapshotCapableDriver {
    override val kind: ProcessKind = if (module.isAsyncified) ProcessKind.WAIT_POINT else ProcessKind.BLOCKING

    override val abiLayer: Int get() = SnapshotSection.ABI_LAYER_P1

    override val abiLayerVersion: Int get() = SnapshotSection.P1_LAYER_VERSION

    /**
     * p1 の層の中身（#82、ADR 0082 の 5.。[P1SectionCodec]）。インスタンスが2つ以上あれば `null`（保存できない）。
     * インスタンスの状態を取り出せない作り（[pro.ikili.kibicomputers.core.runtime.WasmStateException]）は、そのまま投げる
     */
    override fun captureAbi(rt: ProcessRuntime): ByteArray? {
        if (rt.instances.size > 1) return null
        val instance = rt.instances.firstOrNull()?.captureState()
        val process = rt.process
        val stack = process.asyncStack?.toLongArray() ?: LongArray(0)
        if (instance == null && stack.isNotEmpty()) return null
        return P1SectionCodec.write(rt.program.moduleSource, rt.program.hash, instance, stack, process.asyncWait)
    }

    /** 止まりうる import の入口と出口の処理（[configure] で作る） */
    private var suspend: WasiP1Suspend? = null

    /** `kibi:process` の import（[configure] で作る。テストが巻き戻しの途中の状態を見るのに使う） */
    @Volatile
    internal var kibiProcess: WasiP1KibiProcess? = null
        private set

    override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
        val r = WasiP1.register(ctx, builder, module, if (module.isAsyncified) P1Wait.UNWIND else P1Wait.BLOCK)
        suspend = r.suspend
        kibiProcess = r.kibiProcess
    }

    override fun start(ctx: ProcessContext): StepResult = run(ctx)

    override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult {
        check(kind == ProcessKind.WAIT_POINT) { "BLOCKING のドライバーは resume しない" }
        ctx.instance.asyncState = WasmAsyncState.REWINDING
        return run(ctx)
    }

    private fun run(ctx: ProcessContext): StepResult {
        val instance = ctx.instance
        try {
            instance.call(START)
        } catch (e: WasiP1Exit) {
            return StepResult.Exit(e.code)
        }
        if (!module.isAsyncified) return StepResult.Exit(0)
        return when (instance.asyncState) {
            WasmAsyncState.NORMAL -> StepResult.Exit(0)
            WasmAsyncState.UNWINDING -> {
                instance.asyncState = WasmAsyncState.NORMAL
                val s = checkNotNull(suspend)
                if (ctx.takeFuelUnwind()) {
                    check(!s.hasPendingKeys) { "燃料切れと入力待ちの巻き戻しが重なった" }
                    StepResult.WaitFuel
                } else {
                    StepResult.Wait(s.takePendingKeys())
                }
            }
            // 止まった呼び出しに着かずに戻った: 巻き直しが食い違った
            WasmAsyncState.REWINDING -> throw IllegalStateException("巻き直しが止まった呼び出しに着かずに _start から戻った")
        }
    }

    companion object {
        /** コマンドの入口 */
        const val START: String = "_start"
    }
}
