package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.HostCall
import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
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

    /** 親の pid（親がなければ 0） */
    val parentPid: Int

    /** 引数（argv。最初の要素はプログラムの名前。作った後は変わらない。#45） */
    val args: List<String>

    /** 環境変数（名前 → 値。並びを保つ。作った後は変わらない。#45） */
    val env: Map<String, String>

    /** 作業ディレクトリ（VFS の絶対パス。#45）。どのスレッドから読んでもよい */
    val cwd: VfsPath

    /**
     * 作業ディレクトリを [path] に変える（`chdir`）。相対パスは今の作業ディレクトリから辿る。
     * なければ `NOENT`、ディレクトリでなければ `NOTDIR`。カーネルロックを持たずに呼ぶ
     */
    @Throws(FsException::class)
    fun changeDirectory(path: String)

    /**
     * このプロセスを親にして、読み込み済みの [program] からプロセスを作る（`Machine.spawn`。[SpawnOptions] の既定は親から引き継ぐ）。
     * カーネルロックを持たずに呼ぶ。戻り値は子の pid
     */
    @Throws(FsException::class)
    fun spawn(program: Program, driver: ProcessDriver, options: SpawnOptions = SpawnOptions()): Int

    /**
     * このプロセスを親にして、VFS の実行ファイル [path]（相対パスは作業ディレクトリから）からプロセスを作る（`Machine.exec`）。
     * WASM の読み込みに時間がかかる（その間 CPU を持ったまま）。カーネルロックを持たずに呼ぶ。戻り値は子の pid
     */
    @Throws(FsException::class)
    fun exec(path: String, drivers: ProcessDriverFactory, options: SpawnOptions = SpawnOptions()): Int

    /** このプロセスの子の pid（ゾンビを含む。割り当ての順） */
    fun children(): List<Int>

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

    /**
     * マシンを止める（`Machine.stop`。#49）。このプロセスも止められるので、この後のホスト関数・待ちは [ProcessKilledException] になる。
     * WASM から呼ぶシステムコールの形は #306
     */
    fun requestShutdown()

    /** マシンを再起動する（`Machine.reboot`。#49）。受け付けたら `true`（一度も起動していなければ `false`）。システムコールの形は #306 */
    fun requestReboot(): Boolean
}

/** 作っている途中の記録（実行時だけ。[ProcessRuntime.loading]） */
internal class LoadingFrame(
    /** 仮の holder。最初にホスト関数・燃料切れの処理に渡された instance */
    var provisional: WasmInstance?,
    /** `ctx.instantiate` なら元の holder（0 にしてある）。プロセスの始まりなら `null` */
    val restoreTo: WasmInstance?,
)
