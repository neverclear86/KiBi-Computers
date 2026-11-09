package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.runtime.WasmInstance
import java.util.UUID

/** 置き場の全体の世代を指す（#83）。ID の振り直しの後は世代が 1 から始まるので、UUID と世代の組で照らす */
internal data class BaseRef(val id: UUID, val generation: Long)

/**
 * プロセスの線形メモリの基準（#83、ADR 0083）。カーネルロックの中で読み書きする。
 * 基準は [instance] と組で持ち、インスタンスが作り直されたら（今のインスタンスが違えば）基準はないものとして扱う
 */
internal sealed interface MemoryBase {
    /** 基準なし（差分の世代でも全体の形で書く） */
    object None : MemoryBase

    /** 全体保存の取り出し [token] で、区画を読み取り専用に戻した。置き場が書けたら [Committed] にする */
    class Pending(val token: Long, val instance: WasmInstance) : MemoryBase

    /** 置き場の全体の世代 [ref] を基準にしている（書ける区画 = [ref] から変わった区画） */
    class Committed(val ref: BaseRef, val instance: WasmInstance) : MemoryBase

    companion object {
        /** [base] が、今のインスタンス [current] で [ref] を基準にしているか */
        fun matches(base: MemoryBase, ref: BaseRef, current: WasmInstance?): Boolean =
            base is Committed && current != null && base.instance === current && base.ref == ref
    }
}
