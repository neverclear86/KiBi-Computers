package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.input.KeyEvents
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.TerminalKeyEncoder
import pro.ikili.kibicomputers.core.kernel.Machine

/**
 * キーボードのデバイス（`kibicomputers:keyboard`。#65、ADR 0065）。キーボードの入力（[KeyboardEvent]）を、端末のバイト列にして
 * （[TerminalKeyEncoder]）つないだマシンのコンソールの TTY に流す（ADR 0065）。
 *
 * メソッドはない（[DeviceMethods.empty]）。キーの押下・離し（[KeyboardEvent.KeyDown]・[KeyboardEvent.KeyUp]）は、TTY に流すのと別に、
 * イベント `key-pressed`・`key-released`（[KeyEvents]。source は自分の address）としても配る（#326、ADR 0326）。
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

    // つないでいる間のつながり（キーのイベントを送る口。#326）
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
     * 貼り付けと文字の入力は、文字の境目で分けて入れてよい入力として渡す（TTY に入りきらなければ待たせる。#609）。
     *
     * キーの押下・離しは、端末のバイト列が空になるもの（修飾キーだけ、英字の押下、離しなど）も含めて、イベントとして配る
     * （#326）。イベントは返り値に数えない。文字の入力と貼り付けは今はイベントにしない。
     *
     * @return TTY が受け付けたバイト数（待ちの列に積んだ分を含む）。マシンがない・送るものがない入力なら 0
     */
    public fun input(event: KeyboardEvent): Int {
        val m = machine ?: return 0
        // キーのイベント（#326）。バイト列が空のキーでも出すので、早い return より前に置く。外れた後は postEvent が捨てる
        val ctx = context
        if (ctx != null) {
            when (event) {
                is KeyboardEvent.KeyDown -> ctx.postEvent(KeyEvents.PRESSED, *KeyEvents.pressedValues(event))
                is KeyboardEvent.KeyUp -> ctx.postEvent(KeyEvents.RELEASED, *KeyEvents.releasedValues(event))
                is KeyboardEvent.Text, is KeyboardEvent.Paste -> Unit
            }
        }
        val bytes = TerminalKeyEncoder.encode(event)
        if (bytes.isEmpty()) return 0
        val splittable = event is KeyboardEvent.Paste || event is KeyboardEvent.Text
        val accepted = m.offerConsoleInput(bytes, splittable = splittable)
        m.pumpConsole()
        return accepted
    }

    override fun toString(): String = "KeyboardDevice($machine)"
}
