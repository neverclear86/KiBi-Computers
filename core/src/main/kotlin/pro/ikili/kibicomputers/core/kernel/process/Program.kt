package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
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

/** プロセスを作るときの指定 */
internal class SpawnOptions(
    /** 燃料を計測しないモジュールを動かす。テスト専用（同梱の WASM は #36・#92、プレイヤーの WASM は #29 で挿入済みになる） */
    val allowUnmetered: Boolean = false,
    /** 標準入出力（fd 0/1/2）の割り当て。既定は親から引き継ぐ（親がなければコンソール） */
    val stdio: StdioSpec = StdioSpec.INHERIT,
)
