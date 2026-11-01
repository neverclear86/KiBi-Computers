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
     * 中身はコンソールの TTY を通ったバイト列（#60）。改行はいつも CR LF になっている（TTY の出力も、カーネルのメッセージも）。
     * エスケープシーケンスなど、端末としての解釈は受け取る側が行う（#61）。続けて積まれた出力は、1回にまとめて渡す。
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

    /**
     * 起動（[Machine.boot]）に失敗した（#49）。マシンは [MachineState.STOPPED] のまま。
     * コンソールには、カーネルが固定の英語のメッセージをこの通知の前に書いている。翻訳した表示が要るときは、ここで出す。
     *
     * @param detail 失敗の詳しい理由（ログ向け。決まった形ではない）
     */
    public fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {}

    /**
     * 再起動（[Machine.reboot]）を求められ、プロセスがすべて止まった（#49）。
     * 既定の実装は、前回の設定（[Machine.lastBootConfig]）でもう一度 [Machine.boot] する。
     * 上書きすると、その時点のディスクで [BootConfig] を作り直して起動できる（起動しないこともできる）
     */
    public fun onRebootRequested(machine: Machine) {
        machine.lastBootConfig?.let { machine.boot(it) }
    }
}
