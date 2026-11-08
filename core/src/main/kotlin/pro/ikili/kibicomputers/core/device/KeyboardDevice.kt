package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.TerminalKeyEncoder
import pro.ikili.kibicomputers.core.kernel.Machine

/**
 * キーボードのデバイス（`kibicomputers:keyboard`。#65、ADR 0065）。キーボードの入力（[KeyboardEvent]）を、端末のバイト列にして
 * （[TerminalKeyEncoder]）つないだマシンのコンソールの TTY に流す（ADR 0065）。
 *
 * メソッドはない（[DeviceMethods.empty]）。入力をイベントキューに届けるのは #326（[input] の中の1か所で配る）。
 *
 * スレッド: メインスレッドからつなぐ・外す・[input] を呼ぶ。
 */
@ApiStatus.Internal
public class KeyboardDevice : Device {
    override val kind: String get() = DeviceKind.KEYBOARD

    override val methods: DeviceMethods get() = DeviceMethods.empty()

    /** つないでいるマシン。つないでいなければ `null`（入力を捨てる） */
    public var machine: Machine? = null
        private set

    // つないでいる間のつながり（#326 がイベントを送る口）
    private var context: DeviceContext? = null

    override fun onAttached(context: DeviceContext) {
        this.context = context
        machine = (context as AttachedDevice).machine
    }

    override fun onDetached(context: DeviceContext) {
        if (this.context !== context) return
        this.context = null
        machine = null
    }

    /**
     * キーボードの入力 [event] を、マシンのコンソールに流す。キー入力のエコーは、入力の直後にここで画面に書く
     * （書くプロセスがいないので。打った量だけなので軽い。#355）。
     *
     * @return TTY が受け付けたバイト数。マシンがない・送るものがない入力なら 0
     */
    public fun input(event: KeyboardEvent): Int {
        val m = machine ?: return 0
        // #326: キー入力をイベントキューにも届けるときは、ここで context の postEvent に渡す（イベントの名前と値の形は #326 で決める）
        val bytes = TerminalKeyEncoder.encode(event)
        if (bytes.isEmpty()) return 0
        val accepted = m.offerConsoleInput(bytes)
        m.pumpConsole()
        return accepted
    }

    override fun toString(): String = "KeyboardDevice($machine)"
}
