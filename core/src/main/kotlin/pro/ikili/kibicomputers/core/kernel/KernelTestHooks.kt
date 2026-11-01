package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener

/**
 * テスト用の内部のフック。競合の順番をテストで作るために、決まった場所で止められる。既定は何もしない。
 *
 * どれも `(マシン, pid)` を受け取る。[stopBeforeInterrupt] はカーネルロックの中、ほかはロックの外で呼ぶ。
 */
internal class KernelTestHooks {
    /** step（専用スレッド）の終わりの、登録を外す前（WASM は終わっている） */
    @Volatile
    var beforeUnregister: ((Machine, Int) -> Unit)? = null

    /** step（専用スレッド）の終わりの、登録を外して割り込みの印を消した後・プールへ返る前 */
    @Volatile
    var afterUnregister: ((Machine, Int) -> Unit)? = null

    /** 待機点型の step を始める前（起こされて CPU を受け取った後、`readyKeys` を求める前） */
    @Volatile
    var beforeStepStart: ((Machine, Int) -> Unit)? = null

    /** 待機点型の step で許可を取った後、2回目の `readyKeys` を求める前 */
    @Volatile
    var afterStepPermit: ((Machine, Int) -> Unit)? = null

    /** `stop()` で、登録を確かめた後・割り込みを送る前（カーネルロックの中） */
    @Volatile
    var stopBeforeInterrupt: ((Machine, Int) -> Unit)? = null

    /** 起動ディスクを開く口の差し替え（`null` なら既定の [BootVolumeOpener.DEFAULT]） */
    @Volatile
    var bootVolumes: BootVolumeOpener? = null

    /** 起動の手順で、ディスクを探し終えて init を作る前（ロックの外。ワーカーのスレッド） */
    @Volatile
    var beforeInitExec: ((Machine) -> Unit)? = null
}
