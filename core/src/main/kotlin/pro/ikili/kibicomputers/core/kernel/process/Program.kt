package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
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
        /** [bytes] をそのまま [runtime] に読み込ませ、同じ bytes からハッシュを作る（読み込んだものと識別子がずれない） */
        @Throws(WasmLoadException::class)
        fun load(runtime: WasmRuntime, bytes: ByteArray): Program {
            val copy = bytes.copyOf()
            return Program(ModuleHash.of(copy), runtime.load(copy))
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
    /** 燃料を計測しないモジュールを動かす。テスト専用（同梱の WASM は #36・#92、プレイヤーの WASM は #29 で挿入済みになる） */
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
     * [StdioSource.Inherit] は親の同じ番号（親がない・閉じていれば割り当てない）、[StdioSource.Console] はコンソールの出力。
     * fd が 3 未満・上限以上なら `BADF`
     */
    val fds: Map<Int, StdioSource> = emptyMap(),
)

/** 実行ファイルの [Program] から、そのプロセスの [ProcessDriver] を作る（ABI 層。p1 か Component Model かを見分ける）。 */
internal fun interface ProcessDriverFactory {
    /** [program] を動かすドライバーを作る。動かせない形なら `FsException(NOEXEC)` を投げる */
    @Throws(FsException::class)
    fun create(program: Program): ProcessDriver
}
