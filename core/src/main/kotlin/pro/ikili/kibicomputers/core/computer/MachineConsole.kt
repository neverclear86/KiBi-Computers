package pro.ikili.kibicomputers.core.computer

import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.display.terminal.TerminalListener
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.TerminalKeyEncoder
import pro.ikili.kibicomputers.core.kernel.Machine

/**
 * マシンのコンソールを、文字画面とキーボードにつなぐ（#307 の仮の結線）。
 *
 * - コンソールの出力（[pro.ikili.kibicomputers.core.kernel.MachineHost.onConsoleOutput]）を [write] に渡すと、
 *   ANSI エスケープシーケンスを解釈して（#61）[screen] に書く
 * - キーボードの入力（[KeyboardEvent]）を [keyboardInput] に渡すと、端末のバイト列にして（[TerminalKeyEncoder]）
 *   マシンのコンソールの TTY に流す
 * - 端末からの応答（カーソル位置の問い合わせへの答えなど）も、TTY に流す
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

    /**
     * 入力と端末の応答を流す先のマシン。`null` なら入力を捨てる。
     * 入れると、マシンのコンソールの大きさを [screen] の大きさに合わせる。
     */
    public var machine: Machine? = null
        set(value) {
            field = value
            value?.resizeConsole(screen.width, screen.height)
        }

    /** コンソールの出力 [bytes]（TTY を通った UTF-8 のバイト列）を、端末として解釈して [screen] に書く。 */
    public fun write(bytes: ByteArray) {
        terminal.write(bytes)
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
        terminal.reset()
    }

    override fun toString(): String = "MachineConsole(${screen.width}x${screen.height}, $machine)"
}
