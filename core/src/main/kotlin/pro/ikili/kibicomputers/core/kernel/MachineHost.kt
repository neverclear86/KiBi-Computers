package pro.ikili.kibicomputers.core.kernel

/**
 * カーネル → 接続層の口（#44）。[MachineScheduler.createMachine] に渡す。
 *
 * どの通知も [MachineScheduler.tick] の中で、メインスレッドから、マシンごとに起きた順に呼ばれる。
 * 呼ばれるとき、カーネルはロックも CPU も許可も持っていない。中から `stop()`・`close()`・`createMachine` を呼んでよい。
 * 中で投げた例外は捨てる（ほかのマシンと、同じマシンの後の通知には伝えない）。
 *
 * [onStateChanged] だけが必須。ほかは何もしない既定の実装を持つ（Java からはラムダでも、クラスでも実装できる）。
 */
public fun interface MachineHost {
    /**
     * マシンの状態が変わった。停止（[MachineState.STOPPED]・[MachineState.CRASHED]）もここで知らせる。
     *
     * @param cause [MachineState.CRASHED] になったときの原因。それ以外は `null`
     */
    public fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?)

    /**
     * 画面への出力（カーネルのコンソール。起動のログ、カーネルのメッセージ、画面のデバイスがないときの標準出力の行き先）。
     * 中身はバイト列（端末としての解釈は #60）。続けて積まれた出力は、1回にまとめて渡す。
     * 渡した配列はカーネルがもう使わないので、持っていてよい
     */
    public fun onConsoleOutput(machine: Machine, bytes: ByteArray) {}

    /**
     * ビープを鳴らす。
     *
     * @param frequencyHz 周波数（[Machine.MIN_BEEP_FREQUENCY_HZ]〜[Machine.MAX_BEEP_FREQUENCY_HZ] に丸めてある）
     * @param durationMillis 長さ（1〜[Machine.MAX_BEEP_DURATION_MILLIS] に丸めてある）
     */
    public fun onBeep(machine: Machine, frequencyHz: Int, durationMillis: Int) {}
}
