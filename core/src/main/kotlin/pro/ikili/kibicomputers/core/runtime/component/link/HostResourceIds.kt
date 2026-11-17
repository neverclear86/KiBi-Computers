package pro.ikili.kibicomputers.core.runtime.component.link

/**
 * ホストの resource の識別子（[HostResource.id]）の振り分け（#267。ADR 0267）。識別子はホスト全体で一意にする
 * （同じ番号なら、別の resource でも同じ型として照合される）ので、名前空間ごとに範囲を分けてここで決める
 */
internal object HostResourceIds {
    /** `wasi:*`（WIT に出てくる順に振る。`WasiP2Host`） */
    const val WASI_FIRST: Int = 0x1000

    /** `wasi:*` の範囲の終わり（含まない） */
    const val WASI_END: Int = 0x2000

    /** `kibi:*`（WIT に出てくる順に振る。`KibiP2Host`。#685） */
    const val KIBI_FIRST: Int = 0x2000

    /** `kibi:*` の範囲の終わり（含まない） */
    const val KIBI_END: Int = 0x3000
}
