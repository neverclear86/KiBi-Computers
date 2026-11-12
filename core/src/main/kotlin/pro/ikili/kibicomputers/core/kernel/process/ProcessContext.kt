package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.DeviceCallException
import pro.ikili.kibicomputers.core.device.DeviceInfo
import pro.ikili.kibicomputers.core.kernel.HostCall
import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.event.EventBatch
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
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
     * ブロッキング型だけ（と、どちらの型でも作っている途中のとき）: このプロセスを親にして、VFS の実行ファイル [path]
     * （相対パスは作業ディレクトリから）からプロセスを作る（`Machine.exec`）。WASM の読み込み（コンパイル）を待つ間は、CPU と許可を返して止まる。
     * 待機点型は [beginExec]・[finishExec] で、スレッドを持たずに待つ（#358）。カーネルロックを持たずに呼ぶ。戻り値は子の pid。
     * [callGuest] の中では [WasmTrapException]（[block] と同じ。#430）
     */
    @Throws(FsException::class, ProcessKilledException::class, WasmTrapException::class)
    fun exec(path: String, drivers: ProcessDriverFactory, options: SpawnOptions = SpawnOptions()): Int

    /**
     * [exec] の前半（#358）: 実行ファイルを読み、読み込み（コンパイル）を始めて、待たずに返す。待機点型は戻り値の `key` を
     * `StepResult.Wait` に入れて待ち（スレッドを持たない）、そろったら [finishExec] で子を作る。やめるなら [closeHostWait] に `wait` を渡す
     * （ほかに待つ側がなく、始まっていなければ読み込まない）。ファイルの失敗（`NOENT`・`ACCES`・`FBIG`・`LOOP` など）はここで投げ、
     * 読み込みの失敗（`NOEXEC`・`AGAIN`）は [finishExec] で投げる。カーネルロックを持たずに呼ぶ
     */
    @Throws(FsException::class, ProcessKilledException::class)
    fun beginExec(path: String, drivers: ProcessDriverFactory, options: SpawnOptions = SpawnOptions()): ExecWait

    /**
     * [beginExec] の後半: 読み込みが終わった [wait] から子を作り、その pid を返す（待ちのオブジェクトも閉じる）。
     * 終わっていなければ [IllegalStateException]。失敗は [exec] と同じ。カーネルロックを持たずに呼ぶ
     */
    @Throws(FsException::class, ProcessKilledException::class)
    fun finishExec(wait: ExecWait): Int

    /**
     * ブロッキング型だけ（と、どちらの型でも作っている途中のとき）: [exec] と同じだが、子の `ProcessObject` を指す fd（読む権利だけ）を
     * 自分の fd 表に入れ、子の pid と一緒に返す（`kibi:process` の `[static]process.spawn`。#74、ADR 0074）。
     * fd は子を表に足すのと同じカーネルロックの中で入れるので、子は fd が入る前に動き出さない。
     * fd 表が満杯なら、読み込みの前と子を作る前に確かめて `MFILE`（子を作ってから断らない）。カーネルロックを持たずに呼ぶ。
     * [callGuest] の中では [WasmTrapException]（[block] と同じ。#430）
     */
    @Throws(FsException::class, ProcessKilledException::class, WasmTrapException::class)
    fun execWithHandle(path: String, drivers: ProcessDriverFactory, options: SpawnOptions = SpawnOptions()): ProcessHandle

    /**
     * [finishExec] と同じだが、子のハンドルを自分の fd 表に入れて返す（[execWithHandle] の待機点型の後半。#74）。
     * fd 表が満杯なら、子を作らずに `MFILE`。カーネルロックを持たずに呼ぶ
     */
    @Throws(FsException::class, ProcessKilledException::class)
    fun finishExecWithHandle(wait: ExecWait): ProcessHandle

    /** 自分の fd 表に、子のハンドルを入れる空きがあるか確かめる（なければ `MFILE`。#74）。カーネルロックを持たずに呼ぶ */
    @Throws(FsException::class)
    fun checkHandleRoom()

    /**
     * 子のハンドル [fd] の終わり方（待たない。終わっていなければ `null`。#74）。終わっていて、自分がその子の親なら刈り取る
     * （刈り取った後も同じ値を返す）。pid が使い回された別のプロセスは刈り取らない。
     * [fd] が開いていない・子のハンドルでなければ `FsException(BADF)`
     */
    @Throws(FsException::class)
    fun processHandleStatus(fd: Int): ExitStatus?

    /**
     * 子のハンドル [fd] のプロセスに [signal] を送る（待たない。#74）。刈り取られた・pid が使い回されたなら送らずに
     * [KillResult.ALREADY_EXITED]。ほかは [kill] と同じ。[fd] が開いていない・子のハンドルでなければ `FsException(BADF)`
     */
    @Throws(FsException::class, ProcessKilledException::class)
    fun killProcessHandle(fd: Int, signal: KillSignal): KillResult

    /** 子のハンドル [fd] のプロセスの pid（#74）。[fd] が開いていない・子のハンドルでなければ `FsException(BADF)` */
    @Throws(FsException::class)
    fun processHandlePid(fd: Int): Int

    /** このプロセスの子の pid（ゾンビを含む。割り当ての順） */
    fun children(): List<Int>

    // ---- 終了待ち・シグナル（#46、ADR 0046。WASM に見せる形は #74） ----

    /**
     * 終わった子を1つ刈り取る。待たない（WNOHANG 相当）。[ChildSelector.Any] は pid の割り当ての順でいちばん古い、終わった子。
     * 刈り取った子は表から消える。待つ子がいない・自分の子でなければ [ReapResult.NoChild]、終わっていなければ [ReapResult.Running]
     */
    fun tryReap(selector: ChildSelector): ReapResult

    /**
     * [selector] の子の終わりを待つための key（[ChildSelector.Pid] → その子の `EXITED`、[ChildSelector.Any] → 自分の `CHILD_EXITED`）。
     * 待つ子がいなければ空。待機点型は `StepResult.Wait` で、ブロッキング型は [block] で待ち、起きたら [tryReap] する
     */
    fun childExitKeys(selector: ChildSelector): List<WaitKey>

    /**
     * ブロッキング型だけ: 終わった子を刈り取るまで待つ（[tryReap] が `Running` なら [block] で待ち直す）。
     * [ReapResult.Running] は返さない。[callGuest] の中で待つ（まだ走っている子を待つ）と [WasmTrapException]（[block] と同じ。#430）
     */
    @Throws(ProcessKilledException::class, WasmTrapException::class)
    fun waitChild(selector: ChildSelector): ReapResult

    /**
     * [pid] に [signal] を送る。待たずに返す（止まったことは終了待ちで知る）。マシンの中のどのプロセスにも送れるが、init には送れない
     * （[KillResult.NOT_PERMITTED]）。自分に送って止まるときは [ProcessKilledException] を投げる
     */
    @Throws(ProcessKilledException::class)
    fun kill(pid: Int, signal: KillSignal): KillResult

    /** 自分が [signal] を無視するか決める。子には引き継がない。[KillSignal.KILL] は [IllegalArgumentException] */
    fun setSignalIgnored(signal: KillSignal, ignored: Boolean)

    // ---- イベント（#63、ADR 0063。WASM に見せる形は #72・#73・#266） ----

    /**
     * イベントキューを作って自分のハンドル表に入れ、ハンドル番号を返す（読む権利だけ）。[filter] の種類だけを受け取る（空ならすべて）。
     * [capacity] が 1..`MAX_CAPACITY` の外なら `FsException(INVAL)`、マシンのキューの数が上限なら [ResourceLimitException]
     * （errno への写し方は ABI 層。#73）、ハンドル表が満杯なら `FsException(MFILE)`
     */
    @Throws(FsException::class, ResourceLimitException::class)
    fun openEventQueue(filter: EventFilter = EventFilter.ALL, capacity: Int = EventLimits.DEFAULT_CAPACITY): Int

    /**
     * イベントキュー [handle] から、待たずに最大 [max] 個取り出す（なければ空の結果）。
     * [handle] が開いていない・イベントキューでない・読む権利がなければ `FsException(BADF)`、[max] が 1 未満なら `FsException(INVAL)`
     */
    @Throws(FsException::class)
    fun takeEvents(handle: Int, max: Int = Int.MAX_VALUE): EventBatch

    /** イベントキュー [handle] の `READABLE` の [WaitKey]。待機点型は `StepResult.Wait` に入れて待つ。エラーは [takeEvents] と同じ */
    @Throws(FsException::class)
    fun eventWaitKey(handle: Int): WaitKey

    /**
     * ブロッキング型だけ: イベントキュー [handle] にイベントが1つ以上あるか、あふれて捨てた分があるまで [block] で待ってから取り出す。
     * 返す結果は空でない。[callGuest] の中で待つ（キューが空）と [WasmTrapException]（[block] と同じ。#430）
     */
    @Throws(FsException::class, ProcessKilledException::class, WasmTrapException::class)
    fun awaitEvents(handle: Int, max: Int = Int.MAX_VALUE): EventBatch

    // ---- 時計・タイマー（#64、ADR 0064。WASM に見せる形は #72・#73・#264・#266） ----

    /** マシンの単調時計（ナノ秒。マシンを作ったときが 0。保存・復元をまたいでも続く。WASI の `MONOTONIC`） */
    fun monotonicNanos(): Long

    /** 起動からの経過（tick。起動したときが 0。マシンが動いていた tick だけを数える） */
    fun uptimeTicks(): Long

    /**
     * タイマーを作って自分のハンドル表に入れ、ハンドル番号を返す（読む権利だけ。止まった状態で作る）。
     * [eventQueue] がイベントキューのハンドルなら、鳴るたびにそのキューへ `timer` のイベント（値は `Integer(tag)`・`Integer(回数)`）を入れる
     * （そのときは [readTimer] は 0 を返し、`READABLE` にもならない）。
     * [eventQueue] がイベントキューでない・読めないなら `FsException(BADF)`、キューが `timer` を受け取らないなら `FsException(INVAL)`、
     * ハンドル表が満杯なら `FsException(MFILE)`
     */
    @Throws(FsException::class)
    fun createTimer(eventQueue: Int? = null, tag: Long = 0): Int

    /**
     * タイマー [handle] を設定する。[absolute] なら [time] は単調時計の期限、そうでなければ今からの時間（ナノ秒。0 以下ならすぐ鳴る）。
     * [intervalNanos] が正なら、その後は間隔ごとに繰り返す。設定し直すと、数えた回数は 0 に戻る。
     * [handle] がタイマーでなければ `FsException(BADF)`、[intervalNanos] が負か `MIN_INTERVAL_NANOS` 未満の正なら `FsException(INVAL)`、
     * マシンの動いているタイマーが上限なら [ResourceLimitException]
     */
    @Throws(FsException::class, ResourceLimitException::class)
    fun setTimer(handle: Int, time: Long, intervalNanos: Long = 0, absolute: Boolean = false)

    /** タイマー [handle] を止める（数えた回数も 0 に戻す）。エラーは [setTimer] と同じ */
    @Throws(FsException::class)
    fun cancelTimer(handle: Int)

    /** タイマー [handle] が前回から鳴った回数を取り出し、0 に戻す。待たない。エラーは [setTimer] と同じ */
    @Throws(FsException::class)
    fun readTimer(handle: Int): Long

    /** タイマー [handle] の `READABLE`（鳴った回数が 1 以上）の [WaitKey]。待機点型は `StepResult.Wait` に入れて待つ */
    @Throws(FsException::class)
    fun timerWaitKey(handle: Int): WaitKey

    /**
     * ハンドル表を使わない一時的なタイマーを作り、単調時計の期限 [deadlineNanos] に設定して、その `READABLE` の key を返す
     * （p1 の `poll_oneoff` の時計、[sleep]。#72）。呼んだ側が必ず [closeWaitTimer] で閉じる。
     * マシンの動いているタイマーが上限なら [ResourceLimitException]（そのときは何も残さない）
     */
    @Throws(ResourceLimitException::class)
    fun openWaitTimer(deadlineNanos: Long): WaitKey

    /**
     * [openWaitTimer] のタイマー [key] を期限 [deadlineNanos] に設定し直す（鳴った回数は 0 に戻る）。
     * 鳴った後で予定が外れていて、マシンの動いているタイマーが上限なら [ResourceLimitException]。
     * [key] が一時的なタイマーでなければ [IllegalArgumentException]
     */
    @Throws(ResourceLimitException::class)
    fun resetWaitTimer(key: WaitKey, deadlineNanos: Long)

    /** [openWaitTimer] のタイマー [key] を閉じる（予定も取り消す）。もう閉じていれば何もしない */
    fun closeWaitTimer(key: WaitKey)

    /**
     * ブロッキング型だけ: [nanos] の間、[block] で止まる（0 以下ならすぐ返る）。ハンドル表を使わない一時的なタイマー（[openWaitTimer]）で待つ。
     * マシンの動いているタイマーが上限なら [ResourceLimitException]。[callGuest] の中で待つ（[nanos] が正）と [WasmTrapException]（[block] と同じ。#430）
     */
    @Throws(ProcessKilledException::class, ResourceLimitException::class, WasmTrapException::class)
    fun sleep(nanos: Long)

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

    /** マシンの VFS（ファイルシステムに届く操作はカーネルロックの外で使う。[withKernelLock] の中から呼ぶと `IllegalStateException`。#52） */
    val vfs: Vfs

    /** 状態を変えるオブジェクトの操作に渡す、待ち手への知らせ口（カーネルロックの中で使う） */
    val wakeSink: WakeSink

    /** カーネルロックの中で [block] を実行する */
    fun <T> withKernelLock(block: () -> T): T

    /**
     * `BLOCKING` のとき（と、どちらの型でも作っている途中のとき）、[keys] のどれかがそろうまで止まる
     * （CPU と許可を返し、今の holder の手持ちを書き戻す）。最初からそろっていれば、CPU を持ったまますぐ返る。
     * 起こされても、そろった key が空なら待ち直す。戻り値は空でない。
     * [callGuest] の中（[inGuestCall]）では止まれないので、[WasmTrapException] を投げる（ホスト関数の途中は巻き戻せない。ADR 0073。#430）
     */
    @Throws(ProcessKilledException::class, WasmTrapException::class)
    fun block(keys: List<WaitKey>): List<WaitKey>

    /**
     * ブロッキング型だけ（と、どちらの型でも作っている途中のとき）: 接続層での処理の結果を待つ（CPU と許可を返し、手持ちの燃料を書き戻す）。
     * future の例外は CPU を取り直してから投げる。待機点型は [watchHost] で、スレッドを持たずに待つ（#358）。
     * [callGuest] の中では [WasmTrapException]（[block] と同じ。#430）
     */
    @Throws(ProcessKilledException::class, WasmTrapException::class)
    fun <T> awaitHost(future: CompletableFuture<T>): T

    /**
     * 接続層での処理 [future] の終わりを待つための、終わると `READABLE` になるオブジェクトを作る（#358。待たずに返す）。
     * 待機点型は戻り値の `key` を `StepResult.Wait` に入れて待ち（スレッドを持たない）、そろったら [takeHostResult] で結果を受け取る。
     * ブロッキング型は [block] で待ってもよい。止められていれば [ProcessKilledException]
     */
    @Throws(ProcessKilledException::class)
    fun <T> watchHost(future: CompletableFuture<T>): HostWait<T>

    /**
     * 終わった [wait] の結果を受け取り、待ちのオブジェクトを閉じる（#358）。処理の例外はそのまま投げる
     * （出した呼び出しが止められて取り消されたなら [ProcessKilledException]）。
     * 終わっていなければ [IllegalStateException]、このプロセスの開いている待ちでなければ [IllegalArgumentException]
     */
    @Throws(ProcessKilledException::class)
    fun <T> takeHostResult(wait: HostWait<T>): T

    /**
     * [wait] を、結果を受け取らずに閉じる（#358。待つのをやめる）。まだ実行していないホスト呼び出しは実行しない
     * （実行し始めたものは最後まで実行し、結果は捨てる）。読み込みは、ほかに待つ側がなく始まっていなければ読み込まない。
     * もう閉じていれば何もしない
     */
    fun closeHostWait(wait: HostWait<*>)

    // ---- RAM（#408。WASM に見せる形は `kibi:computer/memory`） ----

    /** 残りの RAM（バイト）。プロセスの上限とマシンの RAM のそれぞれの残りのうち、小さい方。どのスレッドから呼んでもよい */
    fun memoryRemaining(): Long

    /**
     * [memory] の [address] から [length] バイト（符号なし 32 ビット）を、今のうちに RAM として数える（[WasmMemory.commit]）。
     * 数えた後の残りが [keepBytes]（符号なし 64 ビット）未満になるなら、何も数えずに `false`（RAM の上限で断ったとして覚え、
     * この後に異常終了したら、メモリ不足が原因だったと表示する）。範囲外なら [WasmMemoryAccessException]（断ったとは覚えない）
     */
    @Throws(WasmMemoryAccessException::class)
    fun commitMemory(memory: WasmMemory, address: Int, length: Int, keepBytes: Long): Boolean

    /** システムコールの費用を今の holder の手持ちから引く（ADR 0028）。燃料を計測しないプロセスでは何もしない */
    fun chargeFuel(amount: Long)

    /**
     * ホスト関数の中からゲストの export（`cabi_realloc` など。#73）を呼ぶときに、[block] を包む。包んでいる間は [inGuestCall] が立ち、
     * 燃料が切れても巻き戻さず（待機点型でも）、スレッドを持ったまま燃料を待つ。入れ子にしてよい。プロセスのスレッドから呼ぶ。
     * 包んでいる間は、止まって待つ口が [WasmTrapException] を投げる: [block]・[exec]・[execWithHandle]・[awaitHost]・[callOnMainThread]、
     * メインスレッドのメソッドを待つ [invokeDevice]、[block] を通って待つ [waitChild]・[awaitEvents]・[sleep]（この3つは待たずに返せるときは返す）。止まれないのは、ホスト関数の途中は巻き戻せず、ブロッキング型でも呼び出し元の途中で止まることになるため（ADR 0073。#430）。
     * ABI の層は、止まりうる import をこれらに届く前に入口で弾く（それでも届いた分の守り）。待たない口（[submitHostCall]・[watchHost] など）は呼んでよい
     */
    fun <T> callGuest(block: () -> T): T

    /** [callGuest] の中か（ホスト関数の中からゲストを呼んでいる途中か）。プロセスのスレッドから読む */
    val inGuestCall: Boolean

    /**
     * コンソールの TTY に書いたとき、そのカーネルロックの中で呼ぶ（#355・#419）。この後に [flushConsole] で書くと予約し、
     * それまでメインスレッドが TTY の出力を「書き手がいない」として取り出さないようにする。呼んだら、ロックを放した後に必ず
     * [flushConsole] を1回呼ぶ
     */
    fun reserveConsoleFlushLocked() {}

    /**
     * コンソールの TTY に書いた後に呼ぶ（#355）。たまった出力を、このスレッドで表示デバイスに書き、
     * 端末の処理の重さ（触ったセルの数）を燃料で払う。表示デバイスがない・今は書けないときは何もしない（メインスレッドが配る）。
     * [reserveConsoleFlushLocked] の予約を消費する。カーネルロックを持って呼ばない
     */
    fun flushConsole() {}

    /**
     * 今の巻き戻しが燃料切れのもの（#339。止まりうる関数の中で燃料が切れて巻き戻した）なら `true` を返し、印を消す。
     * 待機点型のドライバーが、`UNWINDING` のまま入口から戻ったところで呼ぶ（`true` なら `StepResult.WaitFuel` を返す）
     */
    fun takeFuelUnwind(): Boolean

    // ---- 接続層との境界（#44） ----

    /** マシンが動く環境（時計、乱数、ディスク、保存先）。どのスレッドから使ってもよい */
    val environment: MachineEnvironment

    /**
     * ブロッキング型だけ（と、どちらの型でも作っている途中のとき）: [call] を次の `tick()` の中でメインスレッドから実行し、その結果を待つ
     * （[awaitHost] と同じく、待つ間は CPU と許可を返す）。待機点型は [submitHostCall] で、スレッドを持たずに待つ（#358）。
     * ワールド・デバイスに触る処理はこれで行う。[call] の例外は、CPU を取り直してから投げる。
     * マシンを止めたとき・このプロセスを止めたとき（[kill]、Ctrl+C）は [ProcessKilledException]（実行していない呼び出しは実行しない）。
     * 取り消すのはまだ列にあるものだけで、メインスレッドがもう実行し始めた呼び出しは最後まで実行する
     * （途中で止めるとワールドが半端な状態になるため。結果は捨てる）。
     * [callGuest] の中では [WasmTrapException]（[block] と同じ。#430）
     */
    @Throws(ProcessKilledException::class, WasmTrapException::class)
    fun <T> callOnMainThread(call: HostCall<T>): T

    /**
     * [call] を次の `tick()` の中でメインスレッドから実行するよう出し、待たずに返す（#358。[callOnMainThread] のスレッドを持たない形）。
     * 待機点型は戻り値の `key` を `StepResult.Wait` に入れて待ち、そろったら [takeHostResult] で結果を受け取る。
     * 取り消しは [callOnMainThread] と同じ（止められたら、まだ列にあるものは実行しない。[closeHostWait] でも取り消す）。
     * 止められていれば [ProcessKilledException]。
     * 1つずつ待たずに続けて出してもよい（出した順に実行する）
     */
    @Throws(ProcessKilledException::class)
    fun <T> submitHostCall(call: HostCall<T>): HostWait<T>

    /** コンソール（画面）へ出力する（画面のデバイスがあればそこへ、なければ [MachineHost.onConsoleOutput]）。上限を超えて捨てた分があれば `false` */
    fun writeConsole(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean

    // ---- プロセスの一覧（#452、ADR 0452） ----

    /**
     * マシンの全プロセス（自分・ゾンビ・親なしのゾンビを含む）の写し（WIT の `list-processes`）。並びは表の順（pid の昇順とは限らない）。
     * 待たない。どのスレッドから呼んでもよい（`cabi_realloc` の中からも）。カーネルロックを短く取り、写してから放す
     */
    fun listProcesses(): List<ProcessListing>

    // ---- デバイス（#65、ADR 0065。WASM に見せる形は #77） ----

    /** つないでいるデバイスの一覧（WIT の `list-devices`）。待たない。どのスレッドから呼んでもよい */
    fun listDevices(): List<DeviceInfo>

    /**
     * デバイス [address] を開き、`device` resource の fd を返す（読む権利だけ。WIT の `[static]device.open`、`/dev/device/<address>` #54）。
     * つないでいなければ [DeviceCallException]（`detached`）、ハンドル表が満杯なら `FsException(MFILE)`
     */
    @Throws(DeviceCallException::class, FsException::class)
    fun openDevice(address: String): Int

    /**
     * デバイス [address] のメソッド [method] を [args] で呼び、結果まで待つ（WIT の `invoke`）。燃料を払う（ADR 0065）。
     * 直接のメソッドはこのスレッドで実行する。メインスレッドのメソッドを待てるのは、ブロッキング型（と作っている途中）だけ
     * （待機点型は [startDeviceCall] で積んで、fd を待つ）。失敗は [DeviceCallException]。カーネルロックを持たずに呼ぶ。
     * メインスレッドのメソッドを待つ呼び出しは、[callGuest] の中では [WasmTrapException]（[block] と同じ。#430。直接のメソッドは待たないので呼べる）
     */
    @Throws(DeviceCallException::class, ProcessKilledException::class, WasmTrapException::class)
    fun invokeDevice(address: String, method: String, args: List<DeviceValue>): List<DeviceValue>

    /**
     * [invokeDevice] と同じ呼び出しを待たずに積み、`pending-call` の fd（読む権利だけ。終わると `READABLE`）を返す（WIT の `start`）。
     * 出した順に実行する。燃料を払う。1プロセス [pro.ikili.kibicomputers.core.device.DeviceLimits.MAX_PENDING_CALLS] 個までで、
     * 超えたら `FsException(AGAIN)`。ハンドル表が満杯なら `FsException(MFILE)`。カーネルロックを持たずに呼ぶ
     */
    @Throws(FsException::class)
    fun startDeviceCall(address: String, method: String, args: List<DeviceValue>): Int

    /**
     * `pending-call` の fd [fd] の結果（WIT の `take`）。終わっていなければ `null`（待たない）。結果の分の燃料を払う。fd は閉じない。
     * `pending-call` でなければ `FsException(BADF)`
     */
    @Throws(FsException::class)
    fun takeDeviceCall(fd: Int): CallResult?

    /**
     * マシンを止める（`Machine.stop`。#49・#306。ADR 0306）。必ず受け付け、このプロセスも止まるので戻らない
     * （[ProcessKilledException] を投げる）。このプロセスが既に止められていても同じ例外を投げる（そのときは `stop` を呼ばない）。
     * どのプロセスからでも呼べる（ADR 0046。マシンの中では権限を分けない）。カーネルロックを持たずに呼ぶ。
     * `kibi:computer/power` の `shutdown`（[pro.ikili.kibicomputers.core.wasi.kibi.KibiPower]）
     */
    @Throws(ProcessKilledException::class)
    fun requestShutdown(): Nothing

    /**
     * マシンを再起動する（`Machine.reboot`。#49・#306。ADR 0306）。受け付けたら、このプロセスも止まるので戻らない
     * （[ProcessKilledException] を投げる）。断ったら `false`（起動も復元も通らずに作ったマシン、スケジューラを閉じる途中）。
     * このプロセスが既に止められている（呼ぶ前、または断った時点で）なら、`false` を返さずに例外を投げる。
     * どのプロセスからでも呼べる。カーネルロックを持たずに呼ぶ。`kibi:computer/power` の `reboot`
     */
    @Throws(ProcessKilledException::class)
    fun requestReboot(): Boolean
}

/** 子のハンドル（[ProcessContext.execWithHandle]）: 子の [pid] と、その `ProcessObject` を指す自分の [fd]（#74） */
internal data class ProcessHandle(val pid: Int, val fd: Int)

/** 作っている途中の記録（実行時だけ。[ProcessRuntime.loading]） */
internal class LoadingFrame(
    /** 仮の holder。最初にホスト関数・燃料切れの処理に渡された instance */
    var provisional: WasmInstance?,
    /** `ctx.instantiate` なら元の holder（0 にしてある）。プロセスの始まりなら `null` */
    val restoreTo: WasmInstance?,
)
