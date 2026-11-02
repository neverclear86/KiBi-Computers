package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmValueType

/** RAM の数え方（#370 の PoC。テスト・計測でだけ切り替える。既定は今の方式） */
internal enum class RamAccounting {
    /** 今の方式: 線形メモリの大きさ（宣言したページ数。64 KiB 単位）で数える（#39） */
    DECLARED_PAGES,

    /**
     * 書き換えた 4 KiB の数で数える（#370 の PoC）。データセグメント（ROM）と触っていない所は数えず、
     * `memory.grow` は RAM を数えない（アドレス空間の上限だけを見る）。上限を超える書き込みはトラップ。
     * PoC 専用の仮の import（[RamPoc]）を許す
     */
    WRITTEN_4K,

    /**
     * 最初の大きさ（スタック・データ・最初のヒープ）は書き換えた 4 KiB で数え、`memory.grow` で増やした分はページ数（64 KiB）で数える
     * （#370 の PoC の比較の案）。grow が断られるので、アロケーターは包みなしで NULL を返せる
     */
    WRITTEN_4K_GROWN_PAGES,
}

/**
 * PoC 専用の、残りの RAM を問い合わせる仮の import（#370）。**正式な ABI ではない**（名前・型・p2/p3 での形は人が決める）。
 * [RamAccounting.WRITTEN_4K] のときだけ、カーネルが登録し、[pro.ikili.kibicomputers.core.kernel.process.KernelDrivers.RAM_POC] が許す。
 */
internal object RamPoc {
    const val MODULE: String = "kibi_poc"

    /** `ram_remaining() -> i64`: プロセスがあと書き換えられるバイト数（プロセスの上限とマシンの RAM のうち小さい方の残り） */
    const val REMAINING: String = "ram_remaining"

    val REMAINING_TYPE: WasmFunctionType = WasmFunctionType.of(emptyList(), listOf(WasmValueType.I64))

    /**
     * `ram_commit(ptr: i32, len: i32, keep: i64) -> i32`: [ptr] から [len] バイトの 4 KiB を、今のうちに書き換えた分として数える。
     * 数えた後の残りが `keep` バイト以上なら数えて 1、そうでなければ何もせず 0（アロケーターは確保を取り消して NULL を返せる）。
     * 新しく数える所がなければ（もう書いた所だけなら）、残りによらず 1。
     * `keep` は、アロケーターを通らない書き込み（C のスタックなど）のために残しておく分
     */
    const val COMMIT: String = "ram_commit"

    val COMMIT_TYPE: WasmFunctionType =
        WasmFunctionType.of(listOf(WasmValueType.I32, WasmValueType.I32, WasmValueType.I64), listOf(WasmValueType.I32))

    /** 許す import の名前と型 */
    val IMPORTS: Map<String, WasmFunctionType> = mapOf(REMAINING to REMAINING_TYPE, COMMIT to COMMIT_TYPE)
}
