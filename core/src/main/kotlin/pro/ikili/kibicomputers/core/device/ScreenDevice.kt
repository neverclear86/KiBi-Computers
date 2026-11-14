package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.display.DisplayDevice
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.kernel.ConsoleHoldListener
import pro.ikili.kibicomputers.core.kernel.Machine

/**
 * 画面のデバイス（`kibicomputers:screen`。#65、ADR 0065・0355）。表示デバイス（[DisplayDevice]。#355）を中身に持ち、
 * つないだマシンのコンソールの出力を書く（ADR 0065）。
 *
 * - [DeviceBus] につなぐと（[Device.onAttached]。[machine] が入る）、コンソールの TTY に書いたプロセスが、自分のスレッドで
 *   [display] に書く（端末の処理は燃料で払う。#355）。マシンのコンソールの大きさを画面に合わせる
 * - それ以外のコンソールの出力（カーネルのメッセージ、キー入力のエコーなど）は、マシンが `tick()` の中で [write] に渡し
 *   （[DeviceBus.primaryScreen] のとき）、メインスレッドで ANSI エスケープシーケンスを解釈して（#61）[display] に書く
 * - 端末からの応答（カーソル位置の問い合わせへの答えなど）は、TTY に流す
 * - [write] で 1 tick に処理するセルの数に上限を置く（#357）。超えた分は取っておいて次の tick 以降に回し（マシンへの通知が予算（#356）で持ち越されれば、その分遅れる）、その間はマシンからの出力を止める
 *   （プロセスのスレッドからも書かない。TTY がいっぱいになると、書いたプロセスは空くまで待つ）。マシンをつないでいない間に [write] したものは、
 *   上限なしで全部処理する
 * - 取っておいた分があるときにマシンから外すと（[Device.onDetached]。電源を切ったときなど）、その tick の残りの上限まで書き、
 *   残りは捨てる（#381）。画面のブロックが外れたときは、外す前に [discardOutput] で、書かずに捨てる（出力を止めている最中でも。
 *   接続層が画面の取り外しで呼ぶ。#397）。別のマシンにつなぎ替えたときは、そのまま続きを書く
 *
 * メソッド: `get-size() -> integer, integer`（列・行。直接）。描画（GPU）は #137・#141。
 *
 * スレッド: メインスレッドからつなぐ・外す・呼ぶ。[display] には、そのロックを持って書く（ロックの順は「表示デバイス → カーネル」）。
 *
 * @property display 書き込む表示デバイス
 */
@ApiStatus.Internal
public class ScreenDevice(public val display: DisplayDevice) : Device {
    /** 文字画面（[DisplayDevice.text]）と色数 [maxColors] で、新しい表示デバイスを作る（テスト用） */
    public constructor(screen: TextBuffer, maxColors: Int) : this(DisplayDevice(screen, maxColors))

    override val kind: String get() = DeviceKind.SCREEN

    override val methods: DeviceMethods = DeviceMethods.builder()
        .direct("get-size") { _, _ -> arrayOf(DeviceValue.of(screen.width.toLong()), DeviceValue.of(screen.height.toLong())) }
        .build()

    override fun onAttached(context: DeviceContext) {
        machine = (context as AttachedDevice).machine
    }

    override fun onDetached(context: DeviceContext) {
        val m = (context as AttachedDevice).machine
        if (machine === m) machine = null
    }

    /** 書き込む文字画面（[DisplayDevice.text]） */
    public val screen: TextBuffer get() = display.text

    private val terminal get() = display.terminal

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

    private val holdListener = ConsoleHoldListener { locked { pump() } }

    /** 取っておいた出力のうち、書かずに捨てたバイト数（#381。マシンを外したとき・画面が外れたとき（#397）など。数え口） */
    public var droppedBytes: Long = 0
        private set

    /**
     * つないでいるマシン（端末の応答を流す先）。[DeviceBus] につなぐ・外すときに入れ替わる。`null` なら応答を捨てる。
     * 入れると、マシンのコンソールの大きさを [screen] の大きさに合わせる。
     * 取っておいた出力があるときに `null` にすると、外したマシンのその tick の残りの上限まで書き、残りは捨てる（#381）。
     * 別のマシンに替えたときは、取っておいた分を、替えた先のマシンの tick の上限で書き続ける
     */
    public var machine: Machine? = null
        internal set(value) = locked {
            val old = field
            if (old !== value) {
                release()
                if (old != null && old.consoleSink === display.consoleSink) old.consoleSink = null
            }
            if (value == null && old != null && backlogEnd > backlogStart) {
                // 外したマシンの tick の上限は守る。続きを書く tick がもうないので、残りは捨てる
                writeWithinBudget(old)
                discardOutput()
            }
            field = value
            display.replyTo = value
            if (value != null) {
                value.resizeConsole(screen.width, screen.height)
                // ここから、コンソールの TTY に書いたプロセスが自分のスレッドで書く（#355）
                value.consoleSink = display.consoleSink
            }
            if (backlogEnd > backlogStart) pump()
        }

    /**
     * コンソールの出力 [bytes]（TTY を通った UTF-8 のバイト列）を、端末として解釈して [screen] に書く。
     * この tick の上限を超えた分は取っておき、次の tick 以降に書く（書いた順は変えない。マシンへの通知が予算（#356）で持ち越されれば、その分遅れる）。
     */
    public fun write(bytes: ByteArray): Unit = locked {
        if (bytes.isEmpty()) return@locked
        append(bytes)
        pump()
        // 渡された配列は呼ぶ側が使い回すかもしれないので、残りを取っておくときは写す
        if (backlog === bytes) {
            backlog = bytes.copyOfRange(backlogStart, backlogEnd)
            backlogStart = 0
            backlogEnd = backlog.size
        }
    }

    /** 処理しきれずに取っておいた出力のバイト数（#357）。マシンをつないでいて 0 より大きい間は、マシンの出力を止めている */
    public val backlogBytes: Int get() = backlogEnd - backlogStart

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
    public fun discardOutput(): Unit = locked {
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

    /** 端末を最初の状態に戻し、画面を消す（電源を入れ直したときなど）。 */
    public fun reset(): Unit = locked {
        clearBacklog()
        release()
        terminal.reset()
    }

    // 表示デバイスのロックを持って [block] を実行する（ロックの順は「表示デバイス → カーネル」）
    private inline fun <T> locked(block: () -> T): T {
        val lock = display.lock
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override fun toString(): String = "ScreenDevice(${screen.width}x${screen.height}, $machine)"

    internal companion object {
        /**
         * 1 tick に処理するセルの数の上限の既定値（仮の値。確定は #251）。いちばん大きい画面（160x50）の全体を 8 回書き換えられる。
         * 普通の出力は1 tick で終わり、1行送りの連打のような出力でも、1台の1 tick の処理は画面 8 枚分で止まる
         */
        const val DEFAULT_CELLS_PER_TICK: Long = 160L * 50 * 8

        private val EMPTY = ByteArray(0)
    }
}
