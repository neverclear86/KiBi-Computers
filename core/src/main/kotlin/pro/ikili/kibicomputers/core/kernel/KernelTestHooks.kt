package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener

/**
 * テスト用の内部のフック。競合の順番をテストで作るために、決まった場所で止められる。既定は何もしない。
 *
 * 多くは `(マシン, pid)` を受け取る（[beforeInitExec] などはマシンだけ、[atSpawnStage] は段（[SpawnStage]）だけ）。
 * [stopBeforeInterrupt]・[duringRestoreInstall]・[duringInitSpawn]・[duringSpawn]・[atSpawnStage]・[duringRebootPost] は
 * カーネルロックの中、ほかはロックの外で呼ぶ（それぞれの KDoc を見る）。ロックの中のフックで、カーネルロックを要る別のスレッドを待つとデッドロックする。
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

    /** 待機点型の step で、燃料切れで `_start` まで巻き戻した後・燃料待ちの列に入れる前（#396） */
    @Volatile
    var beforeFuelUnwound: ((Machine, Int) -> Unit)? = null

    /** プロセスがコンソールの TTY に書いた後・自分のスレッドで表示デバイスに書く前（#419。カーネルロックの外） */
    @Volatile
    var beforeConsoleFlush: ((Machine, Int) -> Unit)? = null

    /**
     * プロセスが電源の求め（`requestShutdown`・`requestReboot`。#306）を出す前（カーネルロックの外。プロセスのスレッド）。
     * ホスト関数の包みが止められていないことを確かめた後なので、ここで `stop()` を挟むと、確かめてから求めるまでの競合を作れる
     */
    @Volatile
    var beforePowerRequest: ((Machine, Int) -> Unit)? = null

    /** `stop()` で、登録を確かめた後・割り込みを送る前（カーネルロックの中） */
    @Volatile
    var stopBeforeInterrupt: ((Machine, Int) -> Unit)? = null

    /** 起動ディスクを開く口の差し替え（`null` なら既定の [BootVolumeOpener.DEFAULT]） */
    @Volatile
    var bootVolumes: BootVolumeOpener? = null

    /** 起動の手順で、ディスクを探し終えて init を作る前（ロックの外。ワーカーのスレッド） */
    @Volatile
    var beforeInitExec: ((Machine) -> Unit)? = null

    /** 復元（#85）の前段を終えて、後段（カーネルロックの中）に入る前（ロックの外。起動用のスレッド） */
    @Volatile
    var beforeRestoreInstall: ((Machine) -> Unit)? = null

    /** 復元の後段の途中（カーネルロックの中。表を入れた後、実行時の部分を作る前）。例外を投げると後段の失敗になる */
    @Volatile
    var duringRestoreInstall: ((Machine) -> Unit)? = null

    /**
     * 起動の init を作る区間の途中（カーネルロックの中。表に足して実行時の部分を作った後、`RUNNING` にする前。#625）。
     * ロックを取らずに読む口（[Machine.isRunningOrBooting]）が、この間も正しく見えるかを確かめるのに使う。
     * 例外を投げると、足した分が巻き戻される（#629）
     */
    @Volatile
    var duringInitSpawn: ((Machine) -> Unit)? = null

    /**
     * すべての spawn の途中（カーネルロックの中。表・オブジェクト・fd・実行時の部分・専用スレッドを確保した後、スレッドを動かす・
     * 待ち行列に積む前。init なら [duringInitSpawn] の後）。例外を投げると、足した分が巻き戻される（#629）
     */
    @Volatile
    var duringSpawn: ((Machine, Int) -> Unit)? = null

    /**
     * spawn が表に足す各段を終えるたびに呼ぶ（カーネルロックの中。段は [SpawnStage]）。例外を投げると、そこまでに足した分が巻き戻される（#629）。
     * 途中の段（fd が途中まで入った、親のハンドルを保持しただけ、など）での失敗を再現するのに使う
     */
    @Volatile
    var atSpawnStage: ((SpawnStage) -> Unit)? = null

    /**
     * 再起動の求めを通知の列に積む区間の途中（カーネルロックの中。通知の印を立てた後、求めの印を下ろす前。#626）。
     * ロックを取らずに読む口（[Machine.isRunningBootingOrRebooting]）が、この間も正しく見えるかを確かめるのに使う
     */
    @Volatile
    var duringRebootPost: ((Machine) -> Unit)? = null
}

/** spawn が表に足す段（[KernelTestHooks.atSpawnStage]）。段を終えた直後に呼ぶ */
internal enum class SpawnStage {
    /** 表（プロセスの表）に足した。オブジェクトはまだない */
    TABLE,

    /** プロセスのオブジェクトを足した（init なら initPid も） */
    OBJECT,

    /** 子の fd を1つ割り当てた（割り当てるたびに呼ぶ） */
    FD,

    /** 親に返すハンドルの参照を足した。fd にはまだ入れていない */
    HANDLE_RETAINED,

    /** 親に返すハンドルを親の fd 表に入れた */
    HANDLE,

    /** 実行時の部分を作った。まだ表（runtimes）に足していない */
    RUNTIME_INIT,

    /** 実行時の部分を表（runtimes）に足した */
    RUNTIME,
}
