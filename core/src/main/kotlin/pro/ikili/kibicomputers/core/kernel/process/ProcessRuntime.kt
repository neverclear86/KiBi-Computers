package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.ProcessMemory
import pro.ikili.kibicomputers.core.kernel.wait.WaitSet
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import java.util.concurrent.locks.Condition

/**
 * プロセスの実行時だけのもの（保存しない）。
 *
 * 特に書かないものはカーネルロックの中で読み書きする。`holdsCpu`・`holdsPermit` はプロセスのスレッドだけが読み書きする。
 */
internal class ProcessRuntime(
    val machine: Machine,
    val process: Process,
    val program: Program,
    val driver: ProcessDriver,
) {
    val pid: Int get() = process.pid

    /** 今このプロセスを動かしているスレッド（専用スレッド、または step を動かしているワーカー）。カーネルロックの中で書く */
    @Volatile
    var thread: Thread? = null

    /** ブロッキング型の専用スレッド（終わるのを待つため） */
    var dedicatedThread: Thread? = null

    /** 停止の印 */
    @Volatile
    var killed: Boolean = false

    /** マシンの CPU を持っている（プロセスのスレッドだけが読み書きする） */
    var holdsCpu: Boolean = false

    /** サーバー全体のセマフォの許可を持っている（プロセスのスレッドだけが読み書きする） */
    var holdsPermit: Boolean = false

    /** 一時停止の数え（スケジューラの「実行中」）に入っている */
    var active: Boolean = false

    /** WASM のスタックがある（step の途中、専用スレッドが動いている間）。待機点かの判定に使う */
    var stackActive: Boolean = false

    /** 線形メモリの使用量（#39。すべてのインスタンスの合計。インスタンスを閉じると減る） */
    val memory: ProcessMemory = machine.memoryAccount.process(machine.config.maxProcessMemoryPages)

    /** 作ったインスタンス（作った順） */
    val instances: MutableList<WasmInstance> = ArrayList(1)

    /** 最初に作ったインスタンス */
    var firstInstance: WasmInstance? = null

    /** 手持ちを持つインスタンス */
    var fuelHolder: WasmInstance? = null

    /** 作っている途中の記録 */
    var loading: LoadingFrame? = null

    /** 今の待ち */
    var waitSet: WaitSet? = null

    /** step の途中に起こされた（スレッドが外れたら CPU の待ち行列に入れる） */
    var pendingSchedule: Boolean = false

    /** 燃料待ち（・一時停止の解除待ち）から起こされた */
    var fuelReady: Boolean = false

    /** 燃料待ちの列に、スレッドを持たない待ち手として入っている */
    var fuelWaitThreadless: Boolean = false

    /** 燃料待ちの条件変数（カーネルロックの） */
    val fuelCondition: Condition = machine.lock.newCondition()

    /** 最後に出た例外（テスト・ログ用） */
    @Volatile
    var lastError: Throwable? = null

    /** このプロセスの燃料切れの処理（カーネルが作る） */
    lateinit var fuelHandler: WasmFuelHandler

    /** このプロセスの [ProcessContext] */
    lateinit var context: ProcessContext

    /** 今の holder（作っている途中なら仮の holder、そうでなければ [fuelHolder]） */
    val currentHolder: WasmInstance?
        get() {
            val frame = loading ?: return fuelHolder
            return frame.provisional
        }
}
