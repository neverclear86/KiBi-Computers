package pro.ikili.kibicomputers.core.computer

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.display.terminal.TerminalListener
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.TerminalKeyEncoder
import pro.ikili.kibicomputers.core.kernel.ConsoleHoldListener
import pro.ikili.kibicomputers.core.kernel.Machine

/**
 * マシンのコンソールを、文字画面とキーボードにつなぐ（#307 の仮の結線）。
 *
 * - コンソールの出力（[pro.ikili.kibicomputers.core.kernel.MachineHost.onConsoleOutput]）を [write] に渡すと、
 *   ANSI エスケープシーケンスを解釈して（#61）[screen] に書く
 * - キーボードの入力（[KeyboardEvent]）を [keyboardInput] に渡すと、端末のバイト列にして（[TerminalKeyEncoder]）
 *   マシンのコンソールの TTY に流す
 * - 端末からの応答（カーソル位置の問い合わせへの答えなど）も、TTY に流す
 * - 1 tick に処理するセルの数に上限を置く（#357）。超えた分は取っておいて次の tick に回し、その間はマシンからの出力を止める
 *   （TTY がいっぱいになると、書いたプロセスは空くまで待つ）。マシンをつないでいない間に [write] したものは、上限なしで全部処理する
 * - 取っておいた分があるときにマシンを外すと（[machine] を `null` にする。電源を切ったときなど）、その tick の残りの上限まで書き、
 *   残りは捨てる（#381）。画面が外れたときは [discardOutput] で、書かずに捨てる。別のマシンに替えたときは、そのまま続きを書く
 *
 * 正式には、隣接デバイスの接続（#129）と、画面・キーボードのデバイス（#65・#66）で置き換える。
 *
 * スレッド: メインスレッドから呼ぶ（スレッドセーフではない）。
 *
 * ```java
 * MachineConsole console = new MachineConsole(screen.getText(), screen.getTier().maxColors);
 * console.setMachine(machine);
 * console.write(bytes);              // MachineHost.onConsoleOutput から
 * console.keyboardInput(event);      // KeyboardListener から
 * ```
 *
 * @property screen 書き込む文字画面
 * @param maxColors 画面の色数（2・16・256 など）
 */
@ApiStatus.Internal
public class MachineConsole(public val screen: TextBuffer, maxColors: Int) {
    private val terminal = AnsiTerminal(
        screen,
        maxColors,
        object : TerminalListener {
            override fun reply(bytes: ByteArray) {
                machine?.offerConsoleInput(bytes)
            }
        },
    )

    /** 1 tick に処理するセルの数の上限（#357。仮の値。確定は #251）。テストで変える */
    internal var cellsPerTick: Long = DEFAULT_CELLS_PER_TICK

    // 処理しきれずに取っておいた出力（backlog[backlogStart until backlogEnd]）
    private var backlog = EMPTY
    private var backlogStart = 0
    private var backlogEnd = 0

    // 今の tick（マシンのスケジューラーの tick）に、まだ処理してよいセルの数
    private var budgetTick = Long.MIN_VALUE
    private var budgetLeft = 0L

    // 出力を止めているマシン
    private var held: Machine? = null

    private val holdListener = ConsoleHoldListener { pump() }

    /** 取っておいた出力のうち、書かずに捨てたバイト数（#381。マシンを外したときなど。数え口） */
    internal var droppedBytes: Long = 0
        private set

    /**
     * 入力と端末の応答を流す先のマシン。`null` なら入力を捨てる。
     * 入れると、マシンのコンソールの大きさを [screen] の大きさに合わせる。
     * 取っておいた出力があるときに `null` にすると、外したマシンのその tick の残りの上限まで書き、残りは捨てる（#381）。
     * 別のマシンに替えたときは、取っておいた分を、替えた先のマシンの tick の上限で書き続ける
     */
    public var machine: Machine? = null
        set(value) {
            val old = field
            if (old !== value) release()
            if (value == null && old != null && backlogEnd > backlogStart) {
                // 外したマシンの tick の上限は守る。続きを書く tick がもうないので、残りは捨てる
                writeWithinBudget(old)
                discardOutput()
            }
            field = value
            value?.resizeConsole(screen.width, screen.height)
            if (backlogEnd > backlogStart) pump()
        }

    /**
     * コンソールの出力 [bytes]（TTY を通った UTF-8 のバイト列）を、端末として解釈して [screen] に書く。
     * この tick の上限を超えた分は取っておき、次の tick から書く（書いた順は変えない）。
     */
    public fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        append(bytes)
        pump()
        // 渡された配列は呼ぶ側が使い回すかもしれないので、残りを取っておくときは写す
        if (backlog === bytes) {
            backlog = bytes.copyOfRange(backlogStart, backlogEnd)
            backlogStart = 0
            backlogEnd = backlog.size
        }
    }

    /** 処理しきれずに取っておいた出力のバイト数（#357） */
    internal val backlogBytes: Int get() = backlogEnd - backlogStart

    /** 最後に処理したマシンの tick で使ったセルの数（#357。テスト用） */
    internal val cellsUsedThisTick: Long get() = cellsPerTick - budgetLeft

    private fun append(bytes: ByteArray) {
        val pending = backlogEnd - backlogStart
        if (pending == 0) {
            backlog = bytes
            backlogStart = 0
            backlogEnd = bytes.size
            return
        }
        val joined = ByteArray(pending + bytes.size)
        backlog.copyInto(joined, 0, backlogStart, backlogEnd)
        bytes.copyInto(joined, pending)
        backlog = joined
        backlogStart = 0
        backlogEnd = joined.size
    }

    // 取っておいた出力を、この tick の上限まで書く。残ればマシンの出力を止め、なくなれば止めるのをやめる
    private fun pump() {
        val m = machine
        if (m == null) {
            terminal.write(backlog, backlogStart, backlogEnd - backlogStart)
            clearBacklog()
            return
        }
        writeWithinBudget(m)
        if (backlogEnd > backlogStart) {
            if (held !== m) {
                release()
                held = m
            }
            m.holdConsoleOutput(holdListener)
        } else {
            clearBacklog()
            release()
        }
    }

    // 取っておいた出力を、マシン m の今の tick の上限まで書く
    private fun writeWithinBudget(m: Machine) {
        val tick = m.scheduler.now
        if (tick != budgetTick) {
            budgetTick = tick
            budgetLeft = cellsPerTick
        }
        while (backlogEnd > backlogStart && budgetLeft > 0) {
            backlogStart += terminal.write(backlog, backlogStart, backlogEnd - backlogStart, budgetLeft)
            budgetLeft -= terminal.cost
        }
    }

    /**
     * 取っておいた出力（[write] で上限を超えた分）を、書かずに捨てる（#381）。画面が外れたときなど、もう見えない画面に書かないために使う。
     * マシンの出力を止めていたら、止めるのをやめる（マシンに取ってある分は、そのまま配られる）
     */
    public fun discardOutput() {
        droppedBytes += backlogEnd - backlogStart
        clearBacklog()
        release()
    }

    private fun clearBacklog() {
        backlog = EMPTY
        backlogStart = 0
        backlogEnd = 0
    }

    private fun release() {
        held?.releaseConsoleOutput()
        held = null
    }

    /**
     * キーボードの入力 [event] を、マシンのコンソールに流す。
     *
     * @return TTY が受け付けたバイト数。マシンがない・送るものがない入力なら 0
     */
    public fun keyboardInput(event: KeyboardEvent): Int {
        val m = machine ?: return 0
        val bytes = TerminalKeyEncoder.encode(event)
        if (bytes.isEmpty()) return 0
        return m.offerConsoleInput(bytes)
    }

    /** 端末を最初の状態に戻し、画面を消す（電源を入れ直したときなど）。 */
    public fun reset() {
        clearBacklog()
        release()
        terminal.reset()
    }

    override fun toString(): String = "MachineConsole(${screen.width}x${screen.height}, $machine)"

    internal companion object {
        /**
         * 1 tick に処理するセルの数の上限の既定値（仮の値。確定は #251）。いちばん大きい画面（160x50）の全体を 8 回書き換えられる。
         * 普通の出力は1 tick で終わり、1行送りの連打のような出力でも、1台の1 tick の処理は画面 8 枚分で止まる
         */
        const val DEFAULT_CELLS_PER_TICK: Long = 160L * 50 * 8

        private val EMPTY = ByteArray(0)
    }
}
