package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmValueType

/**
 * WASI p1（`wasi_snapshot_preview1`）の層（ADR 0261 の A）。カーネルの土台の上に、p1 のホスト関数を並べる。
 *
 * 今あるのは `fd_*`（#70）だけ。args・environ・clock・random・proc_exit などは #69、`path_*` は #71、`poll_oneoff` は #72 で足す。
 * まだない関数をモジュールが import していれば、`NOSYS` を返す仮の関数をつなぐ（リンクが通るように）。
 */
internal object WasiP1 {
    private val I32 = WasmValueType.I32
    private val I64 = WasmValueType.I64

    private fun type(vararg params: WasmValueType): WasmFunctionType = WasmFunctionType.of(params.toList(), listOf(I32))

    /** 関数1つ: 名前、型、本体（p1 の errno を返す） */
    private class Fn(val name: String, val type: WasmFunctionType, val body: (WasiP1Fd, WasmMemory, LongArray) -> Int)

    private fun Long.i(): Int = toInt()

    private val FUNCTIONS: List<Fn> = listOf(
        Fn("fd_advise", type(I32, I64, I64, I32)) { f, _, a -> f.advise(a[0].i(), a[1], a[2], a[3].i()) },
        Fn("fd_allocate", type(I32, I64, I64)) { f, _, a -> f.allocate(a[0].i(), a[1], a[2]) },
        Fn("fd_close", type(I32)) { f, _, a -> f.close(a[0].i()) },
        Fn("fd_datasync", type(I32)) { f, _, a -> f.sync(a[0].i()) },
        Fn("fd_fdstat_get", type(I32, I32)) { f, m, a -> f.fdstatGet(m, a[0].i(), a[1].i()) },
        Fn("fd_fdstat_set_flags", type(I32, I32)) { f, _, a -> f.fdstatSetFlags(a[0].i(), a[1].i() and 0xffff) },
        Fn("fd_fdstat_set_rights", type(I32, I64, I64)) { f, _, a -> f.fdstatSetRights(a[0].i()) },
        Fn("fd_filestat_get", type(I32, I32)) { f, m, a -> f.filestatGet(m, a[0].i(), a[1].i()) },
        Fn("fd_filestat_set_size", type(I32, I64)) { f, _, a -> f.filestatSetSize(a[0].i(), a[1]) },
        Fn("fd_filestat_set_times", type(I32, I64, I64, I32)) { f, _, a -> f.filestatSetTimes(a[0].i(), a[1], a[2], a[3].i() and 0xffff) },
        Fn("fd_pread", type(I32, I32, I32, I64, I32)) { f, m, a -> f.pread(m, a[0].i(), a[1].i(), a[2].i(), a[3], a[4].i()) },
        Fn("fd_prestat_get", type(I32, I32)) { f, m, a -> f.prestatGet(m, a[0].i(), a[1].i()) },
        Fn("fd_prestat_dir_name", type(I32, I32, I32)) { f, m, a -> f.prestatDirName(m, a[0].i(), a[1].i(), a[2].i()) },
        Fn("fd_pwrite", type(I32, I32, I32, I64, I32)) { f, m, a -> f.pwrite(m, a[0].i(), a[1].i(), a[2].i(), a[3], a[4].i()) },
        Fn("fd_read", type(I32, I32, I32, I32)) { f, m, a -> f.read(m, a[0].i(), a[1].i(), a[2].i(), a[3].i()) },
        Fn("fd_readdir", type(I32, I32, I32, I64, I32)) { f, m, a -> f.readdir(m, a[0].i(), a[1].i(), a[2].i(), a[3], a[4].i()) },
        Fn("fd_renumber", type(I32, I32)) { f, _, a -> f.renumber(a[0].i(), a[1].i()) },
        Fn("fd_seek", type(I32, I64, I32, I32)) { f, m, a -> f.seek(m, a[0].i(), a[1], a[2].i() and 0xff, a[3].i()) },
        Fn("fd_sync", type(I32)) { f, _, a -> f.sync(a[0].i()) },
        Fn("fd_tell", type(I32, I32)) { f, m, a -> f.tell(m, a[0].i(), a[1].i()) },
        Fn("fd_write", type(I32, I32, I32, I32)) { f, m, a -> f.write(m, a[0].i(), a[1].i(), a[2].i(), a[3].i()) },
    )

    /** 実装している関数の名前 */
    val implemented: Set<String> = FUNCTIONS.map { it.name }.toSet()

    /** 実装している関数 [name] の型（なければ `null`） */
    fun typeOf(name: String): WasmFunctionType? = FUNCTIONS.firstOrNull { it.name == name }?.type

    /**
     * p1 のホスト関数を [builder] に登録する（`ProcessDriver.configure` の中から）。
     * [module] を渡すと、それが import する p1 の関数のうち、まだ実装していないものに `NOSYS` を返す仮の関数をつなぐ。
     * [blocking] は、ストリームの読み書きで待つか（`BLOCKING` のプロセス）
     */
    fun register(ctx: ProcessContext, builder: WasmInstanceConfig.Builder, module: WasmModule?, blocking: Boolean) {
        val fd = WasiP1Fd(ctx, blocking)
        for (fn in FUNCTIONS) {
            builder.hostFunction(
                P1.MODULE,
                fn.name,
                fn.type,
                ctx.hostFunction { c, caller, args ->
                    c.chargeFuel(WasiP1Fd.CALL_FUEL)
                    val memory = caller.memory
                    val errno = if (memory == null) {
                        Errno.FAULT.code
                    } else {
                        try {
                            fn.body(fd, memory, args)
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
        if (module == null) return
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
    }
}

/**
 * p1 のコマンド（`_start` を export するモジュール）を `BLOCKING` で動かすドライバー。
 *
 * `_start` から戻ったら終了コード 0。`proc_exit` と終了コードの扱いは #69 で足す。
 */
internal class WasiP1Driver(private val module: WasmModule) : ProcessDriver {
    override val kind: ProcessKind get() = ProcessKind.BLOCKING

    override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
        WasiP1.register(ctx, builder, module, blocking = true)
    }

    override fun start(ctx: ProcessContext): StepResult {
        ctx.instance.call(START)
        return StepResult.Exit(0)
    }

    override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult = throw IllegalStateException("BLOCKING のドライバーは resume しない")

    companion object {
        /** コマンドの入口 */
        const val START: String = "_start"
    }
}
