package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import pro.ikili.kibicomputers.core.runtime.validation.ModulePrescan
import java.security.MessageDigest

/**
 * インスタンスを作ったモジュールの識別子。[WasmRuntime.load] に渡したバイト列（挿入があれば挿入の後）の SHA-256（32 バイト）。
 *
 * 保存する可変のグローバル・テーブルは挿入の後のモジュールに依存するので、復元のときにこれで照合する（#30 の結論 4）。
 */
internal class ModuleHash private constructor(private val digest: ByteArray) {
    /** 32 バイトのハッシュの写し */
    fun bytes(): ByteArray = digest.copyOf()

    override fun equals(other: Any?): Boolean = other is ModuleHash && digest.contentEquals(other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun of(loadedBytes: ByteArray): ModuleHash = ModuleHash(MessageDigest.getInstance("SHA-256").digest(loadedBytes))
    }
}

/** 読み込んだモジュールと、その識別子の組。 */
internal class Program private constructor(val hash: ModuleHash, val module: WasmModule) {
    companion object {
        /**
         * [bytes] をそのまま [runtime] に読み込ませ、同じ bytes からハッシュを作る（読み込んだものと識別子がずれない）。
         *
         * [limits] があれば、読み込む前に構造の上限を確かめ、時間の上限を守る（#40。[ModulePrescan]・[LoadDeadline]）
         */
        @Throws(WasmLoadException::class)
        fun load(runtime: WasmRuntime, bytes: ByteArray, limits: ModuleLimits? = null): Program {
            val copy = bytes.copyOf()
            val deadline = limits?.let { LoadDeadline(it.timeLimitNanos) }
            if (limits != null) ModulePrescan.check(copy, limits, deadline)
            val module = runtime.load(copy)
            deadline?.check("パースと検証")
            return Program(ModuleHash.of(copy), module)
        }

        /**
         * [bytes] に燃料の計測コードを挿入してから（[FuelInjector]、#38）[runtime] に読み込ませる。識別子は挿入した後のバイト列から作る。
         * すでに計測の名前を使っている WASM などは [WasmLoadException] で断る。重いので、メインスレッドでは呼ばない。
         *
         * [limits] があれば、挿入する前に構造の上限を確かめ、挿入・パース・検証の全体で時間の上限を守る（#40）
         */
        @Throws(WasmLoadException::class)
        fun loadMetered(runtime: WasmRuntime, bytes: ByteArray, limits: ModuleLimits? = null): Program {
            val deadline = limits?.let { LoadDeadline(it.timeLimitNanos) }
            if (limits != null) ModulePrescan.check(bytes, limits, deadline)
            val injected = FuelInjector.inject(bytes) { deadline?.check("燃料の計測コードの挿入") }.bytes
            val module = runtime.load(injected)
            deadline?.check("パースと検証")
            return Program(ModuleHash.of(injected), module)
        }
    }
}

/**
 * プロセスを作るときの指定（#45）。
 *
 * 引数・環境変数・作業ディレクトリ・fd は、プロセスを作るときに1回だけ決める（fork はない）。
 * 引数・環境変数の検査と上限は [ProcessArgs]。
 */
internal data class SpawnOptions(
    /** 燃料を計測しないモジュールを動かす。テスト専用（`Machine.exec` は読み込むときに計測コードを挿入する。#38） */
    val allowUnmetered: Boolean = false,
    /** 標準入出力（fd 0/1/2）の割り当て。既定は親から引き継ぐ（親がなければコンソール） */
    val stdio: StdioSpec = StdioSpec.INHERIT,
    /**
     * 引数（argv）。最初の要素はプログラムの名前（argv[0]）。`Machine.exec` で空なら、渡したパスの文字列1つにする。
     * `Machine.spawn` はそのまま使う（空のままでもよい）
     */
    val args: List<String> = emptyList(),
    /** 環境変数（名前 → 値。並びを保つ）。`null` なら親から引き継ぐ（親がなければ空） */
    val env: Map<String, String>? = null,
    /** 作業ディレクトリ。`null` なら親と同じ（親がなければ `/`）。相対パスは親の作業ディレクトリから辿る。ディレクトリでなければ失敗 */
    val cwd: String? = null,
    /**
     * fd 3 以降に割り当てるもの（fd → 割り当て）。標準入出力と同じ [StdioSource] で指定する。
     * [StdioSource.Inherit] は親の同じ番号（親がない・閉じていれば割り当てない）、[StdioSource.Console] はコンソールの出力、
     * [StdioSource.Directory] はディレクトリ（WASI の preopen。[ROOT_PREOPEN]）。
     * fd が 3 未満・上限以上なら `BADF`
     */
    val fds: Map<Int, StdioSource> = emptyMap(),
) {
    companion object {
        /** fd 3 に根 `/` を preopen として置く（WASI p1 のプログラムがパスで辿る起点。#71）。起動の手順が init に渡す */
        val ROOT_PREOPEN: Map<Int, StdioSource> = mapOf(3 to StdioSource.Directory("/"))
    }
}

/** 実行ファイルの [Program] から、そのプロセスの [ProcessDriver] を作る（ABI 層。p1 か Component Model かを見分ける）。 */
internal fun interface ProcessDriverFactory {
    /** [program] を動かすドライバーを作る。動かせない形なら `FsException(NOEXEC)` を投げる */
    @Throws(FsException::class)
    fun create(program: Program): ProcessDriver
}
