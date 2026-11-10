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

    /** 止めたシグナル（`stop()` で止めたなら `null`。最初のものを残す。カーネルロックの中で書く。#46） */
    var killSignal: KillSignal? = null

    /** 止まりうる関数でない関数の中で燃料が切れ、前借りして巻き戻せるところを探している（プロセスのスレッドだけが読み書きする。#477） */
    var fuelOverdraft: Boolean = false

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

    /**
     * 線形メモリの基準（#83。差分の世代で、変わった区画だけを書けるか）。カーネルロックの中で読み書きする。
     * インスタンスと組で持つので、インスタンスを作り直したら（exec・戻し）基準はないものとして扱われる
     */
    var memoryBase: MemoryBase = MemoryBase.None

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

    /**
     * 終わりを待っているホスト側の処理（#358。[HostCompletion] のオブジェクトID → 処理）。閉じていないものは、プロセスの終わりで閉じる。
     * カーネルロックの中で読み書きする
     */
    val hostWaits: HashMap<Int, PendingHostWait> = HashMap()

    /** step の途中に起こされた（スレッドが外れたら CPU の待ち行列に入れる） */
    var pendingSchedule: Boolean = false

    /**
     * 保存から戻した直後の起こし（#85、ADR 0085。保存データの `wakeOnRestore`）。立っている間の最初の step は、[waitSet] があれば
     * そろった key（空でもよい）で、なければ空の並びで `resume` する（揃っていなくても待ち直さない・燃料切れの巻き直しにしない）。
     * 燃料がなく step に入れなければ立てたまま残す。カーネルロックの中で読み書きする。実行時だけ（保存は `wakeOnRestore` に写す）
     */
    var restoreWake: Boolean = false

    /** 燃料待ち（・一時停止の解除待ち）から起こされた */
    var fuelReady: Boolean = false

    /** 燃料待ちの列に、スレッドを持たない待ち手として入っている */
    var fuelWaitThreadless: Boolean = false

    /**
     * 燃料切れで巻き戻すよう頼んだ（#339）。step の中でだけ立つ（ドライバーが [ProcessContext.takeFuelUnwind] で消す）。
     * プロセスのスレッドだけが読み書きする
     */
    var fuelUnwound: Boolean = false

    /** [fuelUnwound] を立てたときの燃料の残り（負。行き過ぎた分）。マシンの燃料に書き戻す */
    var fuelUnwindRemaining: Long = 0

    /** 燃料待ちからの巻き直しの途中（#339）。止まった燃料切れの呼び出しに着いたら消す。プロセスのスレッドだけが読み書きする */
    var rewindingForFuel: Boolean = false

    /**
     * ホスト関数の中からゲストの export を呼んでいる深さ（#73。[ProcessContext.callGuest]）。0 より大きい間の燃料切れは巻き戻さない。
     * 実行時だけ（保存しない）。プロセスのスレッドだけが読み書きする
     */
    var guestCallDepth: Int = 0

    /** 燃料待ちの条件変数（カーネルロックの） */
    val fuelCondition: Condition = machine.lock.newCondition()

    /** 最後に出た例外（テスト・ログ用） */
    @Volatile
    var lastError: Throwable? = null

    /** 例外で `CRASHED` になったときの理由（コンソールに出す短い英語。#41）。カーネルロックの中で書く */
    var crashReason: String? = null

    /**
     * `memory.grow` を RAM の上限で断った（#407）。この後にプロセスが異常終了したら、メモリ不足が原因だったと表示する。
     * プロセスのスレッドが、ロックを持たずに書く
     */
    @Volatile
    var memoryGrowRefused: Boolean = false

    /**
     * `kibi:computer/memory` の `commit` を RAM の上限で断った（#408。範囲外で断ったときは立てない）。[memoryGrowRefused] と同じく、
     * この後にプロセスが異常終了したら、メモリ不足が原因だったと表示する。プロセスのスレッドが、ロックを持たずに書く
     */
    @Volatile
    var memoryCommitRefused: Boolean = false

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
