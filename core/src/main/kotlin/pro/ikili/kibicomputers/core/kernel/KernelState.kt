package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.event.EventHub
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessTable
import pro.ikili.kibicomputers.core.kernel.time.KernelClocks
import pro.ikili.kibicomputers.core.kernel.vfs.MountTable

/**
 * マシン（コンピューター1台）のカーネルの状態。保存の対象（書き出す形は #82）。
 *
 * この下は番号・列挙・バイト列だけで、実行時だけのもの（`Waiter`、スレッド、`WasmInstance`、ロック、`MachineHost`）を参照しない。
 * オブジェクト同士・プロセスとオブジェクトの間は、番号（pid、オブジェクトID、ハンドル番号）で指す。
 * マシンのカーネルロックの中でだけ読み書きする。
 */
internal class KernelState(maxProcesses: Int) {
    /** pid → プロセス（と次に割り当てる pid） */
    val processes: ProcessTable = ProcessTable(maxProcesses)

    /** オブジェクトID → カーネルオブジェクト（と次に割り当てる ID） */
    val objects: ObjectTable = ObjectTable()

    /** イベントの種類 → イベントキューのオブジェクトID の索引（#63、ADR 0063） */
    val events: EventHub = EventHub()

    /** VFS のマウントの表（ファイルシステムそのものは実行時だけ。`Machine.vfs` が持つ。ADR 0051） */
    val mounts: MountTable = MountTable()

    /** マシンの燃料の残り（`fuelPerTick` 以下。負なら行き過ぎた分）。燃料の正 */
    var fuel: Long = 0

    /**
     * コンソールの `Tty` のオブジェクトID（まだ作っていなければ 0）。カーネルが参照を1つ持ち、閉じない（#58・#60）。
     * 出力は tick ごとに接続層へ配る
     */
    var consoleTtyId: Int = 0

    /**
     * 今の起動の init の pid（0 = いない）。起動の手順が init を作ったときに入れ、init が終わったとき・次の起動の始めに 0 に戻す（#49・#365）。
     * 戻したマシンでも init を見分けられるよう、保存の対象に置く（init が終わったらマシンを止める、init にはシグナルを送れない）
     */
    var initPid: Int = 0

    /**
     * マシンの時計（単調時計・起動からの経過。#64、ADR 0064）。WASI の `MONOTONIC`（#69）はこの単調時計。
     * 中のずれは今の JVM の時計と tick の番号に対する値なので、保存（#81）は `KernelClocks.snapshot` を書き出す。
     * タイマーそのものはオブジェクト表の `Timer`。乱数は環境（`EntropySource`）から取るだけで、状態を持たない（#69）
     */
    val clocks: KernelClocks = KernelClocks()
}
