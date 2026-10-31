package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.HostCall
import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import java.util.concurrent.CompletableFuture

/** カーネルの包みを通すホスト関数。[ProcessContext.hostFunction] に渡す。 */
internal fun interface KernelHostFunction {
    fun call(ctx: ProcessContext, caller: WasmInstance, args: LongArray): LongArray
}

/**
 * ABI 層・システムコールが使う、プロセスごとの口。
 *
 * [kernel] と [withKernelLock] 以外は、プロセスのスレッド（ホスト関数・`ProcessDriver` の中）から呼ぶ。
 */
internal interface ProcessContext {
    /** このプロセスの pid */
    val pid: Int

    /** 最初に作ったインスタンス（p1 ではこれだけ）。`LOADING` の間（configure の中、start 関数の中）は呼べない（`IllegalStateException`） */
    val instance: WasmInstance

    /** 今、手持ちの燃料を持つインスタンス。ほかのインスタンスの `kibi_fuel` は 0。作っている途中の仮の holder は含まない */
    val fuelHolder: WasmInstance

    /**
     * ABI 層のホスト関数を包む。ABI 層はホスト関数をすべてこれで作る。包みは入口で停止の印を確かめ（[ProcessKilledException]）、
     * 渡された instance を照合する（作っている途中なら仮の holder にする／同じか確かめる。そうでなければ [fuelHolder] と同じか確かめる。
     * 違えばマシンを `CRASHED`）。その後 [f] を呼ぶ
     */
    fun hostFunction(f: KernelHostFunction): WasmHostFunction

    /**
     * ホスト関数の中で、手持ちを [target] へ移す（残りを読んで元を 0 にし、target に入れる）。
     * Canonical ABI の層が、コアインスタンスをまたぐたびに呼ぶ（#270）。燃料を計測しないプロセスでは holder だけ差し替える。
     * 作っている途中（`LOADING`）に呼ぶと `IllegalStateException`
     */
    fun switchFuelHolder(target: WasmInstance)

    /**
     * start・resume かホスト関数の中で、コアインスタンスを1つ作る。[configurer] でホスト import を登録させ
     * （`builder.fuel` を呼んでいたら `IllegalStateException`）、今の手持ちを initialFuel として渡して元を 0 にし、
     * start 関数の間は仮の holder の規則で動かし、作り終えたら残りを元の holder に戻す。
     * 作っている途中の例外は、CPU と許可を返さず、燃料だけ捨てて（元の holder は 0 のまま）呼び出し元へ投げる。
     * 作っている途中に入れ子で呼ぶと `IllegalStateException`
     */
    @Throws(WasmLinkException::class, WasmTrapException::class, ProcessKilledException::class)
    fun instantiate(module: WasmModule, configurer: InstanceConfigurer): WasmInstance

    /** カーネルの状態（カーネルロックの中で使う） */
    val kernel: KernelState

    /** マシンの VFS（カーネルロックの中で使う） */
    val vfs: Vfs

    /** 状態を変えるオブジェクトの操作に渡す、待ち手への知らせ口（カーネルロックの中で使う） */
    val wakeSink: WakeSink

    /** カーネルロックの中で [block] を実行する */
    fun <T> withKernelLock(block: () -> T): T

    /**
     * `BLOCKING` のとき（と、どちらの型でも作っている途中のとき）、[keys] のどれかがそろうまで止まる
     * （CPU と許可を返し、今の holder の手持ちを書き戻す）。最初からそろっていれば、CPU を持ったまますぐ返る。
     * 起こされても、そろった key が空なら待ち直す。戻り値は空でない
     */
    @Throws(ProcessKilledException::class)
    fun block(keys: List<WaitKey>): List<WaitKey>

    /** 接続層での処理の結果を待つ（CPU と許可を返し、手持ちの燃料を書き戻す）。future の例外は CPU を取り直してから投げる */
    @Throws(ProcessKilledException::class)
    fun <T> awaitHost(future: CompletableFuture<T>): T

    /** システムコールの費用を今の holder の手持ちから引く（ADR 0028）。燃料を計測しないプロセスでは何もしない */
    fun chargeFuel(amount: Long)

    // ---- 接続層との境界（#44） ----

    /** マシンが動く環境（時計、乱数、ディスク、保存先）。どのスレッドから使ってもよい */
    val environment: MachineEnvironment

    /**
     * [call] を次の `tick()` の中でメインスレッドから実行し、その結果を待つ（[awaitHost] と同じく、待つ間は CPU と許可を返す）。
     * ワールド・デバイスに触る処理はこれで行う。[call] の例外は、CPU を取り直してから投げる。
     * マシンを止めたときは [ProcessKilledException]（実行していない呼び出しは実行しない）
     */
    @Throws(ProcessKilledException::class)
    fun <T> callOnMainThread(call: HostCall<T>): T

    /** コンソール（画面）へ出力する（[MachineHost.onConsoleOutput]）。上限を超えて捨てた分があれば `false` */
    fun writeConsole(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean

    /** ビープを鳴らす（[MachineHost.onBeep]）。捨てたら `false` */
    fun beep(frequencyHz: Int, durationMillis: Int): Boolean
}

/** 作っている途中の記録（実行時だけ。[ProcessRuntime.loading]） */
internal class LoadingFrame(
    /** 仮の holder。最初にホスト関数・燃料切れの処理に渡された instance */
    var provisional: WasmInstance?,
    /** `ctx.instantiate` なら元の holder（0 にしてある）。プロセスの始まりなら `null` */
    val restoreTo: WasmInstance?,
)
