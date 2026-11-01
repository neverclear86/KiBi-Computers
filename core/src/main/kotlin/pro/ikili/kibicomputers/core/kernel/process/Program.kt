package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.asyncify.AsyncifyUnsupported
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

/**
 * 読み込んだモジュールと、その識別子の組。
 *
 * [fuelYield] は、燃料切れも待ち点にして読み込んだか（#339。[loadMetered] の段1）。[fuelYieldRejection] は、段1を断られて
 * 入力待ちだけの待ち点にした（段2）ときの、段1を断った理由（ログと調べもの用）
 */
internal class Program private constructor(
    val hash: ModuleHash,
    val module: WasmModule,
    val fuelYield: Boolean = false,
    val fuelYieldRejection: String? = null,
) {
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
            deadline?.check("パース・検証・コンパイル")
            return Program(ModuleHash.of(copy), module)
        }

        /**
         * [bytes] に燃料の計測コードを挿入してから（`FuelInjector`、#38）[runtime] に読み込ませる。識別子は挿入した後のバイト列から作る。
         * すでに計測の名前を使っている WASM などは [WasmLoadException] で断る。重いので、メインスレッドでは呼ばない。
         *
         * [limits] があれば、挿入する前に構造の上限を確かめ、変換・挿入・パース・検証の全体で時間の上限を守る（#40）
         *
         * [waitImports] が空でなければ、挿入の後に待機点への変換（`Asyncify`、#329）をかける。順番は
         * 「構造の上限の確認 → 燃料の計測コードの挿入 → 変換 → パースと検証」（理由は [Instrumentation]。#341）。変換できない作り（v128 のローカル、末尾呼び出しなど）は、
         * 変換せずに（`BLOCKING` のプロセスとして）読み込むことはせず、[WasmLoadException] で断る（#364・#367）。
         * そのときの原因（`cause`）は [AsyncifyUnsupported] で、どの命令・どの機能で断ったかは [unsuspendableReason] で取り出せる。
         * 変換は2段で試す（#339。[Instrumentation]）: 燃料切れも待ち点にする（段1。[fuelYield]）→ 断られたら入力待ちだけの待ち点（段2）→
         * それも断られたら上のとおり断る（理由は段2のもの）。
         * 予約した名前（`kibi.async_push` など）を使っている WASM も [WasmLoadException] で断る
         */
        @Throws(WasmLoadException::class)
        fun loadMetered(runtime: WasmRuntime, bytes: ByteArray, limits: ModuleLimits? = null, waitImports: Set<String> = emptySet()): Program {
            val deadline = limits?.let { LoadDeadline(it.timeLimitNanos) }
            if (limits != null) ModulePrescan.check(bytes, limits, deadline)
            val applied = try {
                Instrumentation.applyDetailed(bytes, waitImports) { stage -> deadline?.check(stage) }
            } catch (e: AsyncifyUnsupported) {
                throw WasmLoadException("待機点に変換できない: ${e.message ?: "理由不明"}", e)
            }
            val injected = applied.bytes
            val module = runtime.load(injected)
            deadline?.check("パース・検証・コンパイル")
            return Program(ModuleHash.of(injected), module, applied.fuelYield, applied.fuelYieldRejection)
        }

        /** [e] が、待機点に変換できない作りのために [loadMetered] が断ったものなら、その理由（どの命令・どの機能か）。違えば `null` */
        fun unsuspendableReason(e: WasmLoadException): String? = (e.cause as? AsyncifyUnsupported)?.let { it.message ?: "理由不明" }
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
    /**
     * 待機点への変換（#329）で止まりうるとみなす import（`"<module>.<name>"`）。空なら変換しない。
     * カーネルは ABI の名前を直接知らないまま、どの import で止まるかをここから受け取る（`Machine.exec` が `Program.loadMetered` に渡す）
     */
    val waitImports: Set<String> get() = emptySet()

    /** [program] を動かすドライバーを作る。動かせない形なら `FsException(NOEXEC)` を投げる */
    @Throws(FsException::class)
    fun create(program: Program): ProcessDriver
}
