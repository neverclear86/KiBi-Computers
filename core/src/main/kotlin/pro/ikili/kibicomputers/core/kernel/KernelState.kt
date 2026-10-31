package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessTable

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

    /** マシンの燃料の残り（`fuelPerTick` 以下。負なら行き過ぎた分）。燃料の正 */
    var fuel: Long = 0

    // 時計・乱数の状態は #64・#69 で足す
}
