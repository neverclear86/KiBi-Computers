package pro.ikili.kibicomputers.core.runtime.component

/** WASM のバイナリの種類（ヘッダーの layer で見分ける。ADR 0261「モジュールの判別」） */
internal enum class WasmBinaryKind {
    /** コアモジュール（layer 0。WASI p1 として読む） */
    CORE_MODULE,

    /** Component Model のコンポーネント（layer 1。p2・p3 として読む） */
    COMPONENT,
}

/**
 * WASM のバイナリのヘッダー（プリアンブル）を見て、種類を見分ける（#262）。
 *
 * プリアンブルは `00 61 73 6d`（マジック）、version（2 バイト、リトル エンディアン）、layer（2 バイト）。
 * コアモジュールは version 1・layer 0、コンポーネントは layer 1。コンポーネントの version は、
 * Component Model がまだ固まっていないので版ごとに上がる（今は 0x0d）。先に layer を見て、そのあとで version を確かめる。
 */
internal object WasmBinaryHeader {
    /** プリアンブルのバイト数 */
    const val SIZE: Int = 8

    /** 対応するコンポーネントのバイナリの version（wasm-tools 1.256.0 が書き出す版） */
    const val COMPONENT_VERSION: Int = 0x0d

    private const val CORE_VERSION = 1

    /**
     * [bytes] の種類。WASM のマジックがない、または知らない layer なら `null`。
     * version は見ない（[componentVersion] で確かめる）
     */
    fun kindOf(bytes: ByteArray): WasmBinaryKind? = kindOf(bytes, 0, bytes.size)

    /** [bytes] の [offset] から [length] バイトの種類（[kindOf] と同じ） */
    fun kindOf(bytes: ByteArray, offset: Int, length: Int): WasmBinaryKind? {
        if (length < SIZE) return null
        if (bytes[offset].toInt() != 0x00 || bytes[offset + 1].toInt() != 0x61 ||
            bytes[offset + 2].toInt() != 0x73 || bytes[offset + 3].toInt() != 0x6d
        ) {
            return null
        }
        return when (u16(bytes, offset + 6)) {
            0 -> WasmBinaryKind.CORE_MODULE
            1 -> WasmBinaryKind.COMPONENT
            else -> null
        }
    }

    /** ヘッダーの version（2 バイト）。[kindOf] が `null` でないときだけ意味がある */
    fun version(bytes: ByteArray, offset: Int = 0): Int = u16(bytes, offset + 4)

    /** コアモジュールの version 1 か */
    fun isCoreVersion(bytes: ByteArray, offset: Int = 0): Boolean = version(bytes, offset) == CORE_VERSION

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
}
