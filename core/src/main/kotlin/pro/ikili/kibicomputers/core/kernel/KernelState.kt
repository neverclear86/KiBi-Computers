package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessTable
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

    /** VFS のマウントの表（ファイルシステムそのものは実行時だけ。`Machine.vfs` が持つ。ADR 0051） */
    val mounts: MountTable = MountTable()

    /** マシンの燃料の残り（`fuelPerTick` 以下。負なら行き過ぎた分）。燃料の正 */
    var fuel: Long = 0

    /** コンソールの入力の `ByteStream` のオブジェクトID（まだ作っていなければ 0）。カーネルが参照を1つ持つ（#58） */
    var consoleInputId: Int = 0

    /** コンソールの出力の `ByteStream` のオブジェクトID（まだ作っていなければ 0）。tick ごとに接続層へ配る（#58） */
    var consoleOutputId: Int = 0

    // 時計・乱数の状態は #64・#69 で足す
}
