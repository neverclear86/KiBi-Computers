package pro.ikili.kibicomputers.core.machine

import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineScheduler
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** マシンを手放す理由（[MachineManager.release]）。 */
public enum class MachineReleaseReason {
    /** ブロックが壊された・置き換えられた（もう戻らない） */
    REMOVED,

    /**
     * チャンクがアンロードされた（読み込まれたら、また [MachineManager.attach] される）。
     * 今は [REMOVED] と同じく止める。読み込まれていない間の休止は #132 で足す
     */
    UNLOADED,
}

/**
 * サーバー側のマシン管理（#120）で起きた例外の受け口。接続層はログに書く。
 *
 * [MachineManager.tick] の中など、メインスレッドから呼ばれる。中で投げた例外は捨てる。
 */
public fun interface MachineErrorListener {
    /**
     * @param message 何が起きたか（どのマシンかを含む）
     * @param error 原因
     */
    public fun onError(message: String, error: Throwable)

    public companion object {
        /** 何もしない */
        @JvmField
        public val IGNORE: MachineErrorListener = MachineErrorListener { _, _ -> }
    }
}

/**
 * サーバー全体のマシンの一覧（#120）。ブロックエンティティ（接続層）とカーネル（[MachineScheduler]）の間に立ち、
 * マシンの生成・tick・破棄を受け持つ。サーバーに1つ。接続層がサーバーの起動で作り、停止で [shutdown] する。
 *
 * - マシンは**キー**（[K]。接続層では、ディメンションとブロックの座標など）で引く。1つのキーに1台
 * - [attach] で作り、[release] で止めて一覧から外す。ブロックエンティティの読み込み・破壊・チャンクのアンロードに合わせて呼ぶ
 *   （[MachineSlot] がその手順をまとめている）
 * - [tick] は毎 tick メインスレッドから呼ぶ。中の例外はサーバーに伝えず、[MachineErrorListener] に知らせる
 * - [MachineHost] は包んで渡す。接続層の例外を [MachineErrorListener] に知らせ（カーネルはそのまま捨てるため）、
 *   [release] の後の通知は届けない。マシンが [MachineState.CRASHED] になったときも知らせる
 *
 * スレッド: メインスレッドから呼ぶ（[shutdown] を除き、待たない）。
 *
 * ```java
 * MachineManager<BlockKey> manager = new MachineManager<>(SchedulerConfig.DEFAULT, (message, error) -> LOGGER.error(message, error));
 * Machine machine = manager.attach(key, host, MachineConfig.DEFAULT);
 * manager.tick();
 * manager.release(key, MachineReleaseReason.REMOVED);
 * ```
 *
 * @param K マシンのキー。`equals`・`hashCode` を持ち、変わらない値にする
 */
public class MachineManager<K : Any> internal constructor(
    /** マシンを動かすスケジューラ（保存の前の [MachineScheduler.pauseAll] などに使う） */
    public val scheduler: MachineScheduler,
    private val errors: MachineErrorListener,
    private val tickAction: () -> Unit,
) : AutoCloseable {
    /**
     * [config] の設定でスケジューラを作り、それを使う管理を作る。
     *
     * @param errors 例外の受け口（既定は何もしない）
     */
    @JvmOverloads
    public constructor(
        config: SchedulerConfig = SchedulerConfig.DEFAULT,
        errors: MachineErrorListener = MachineErrorListener.IGNORE,
    ) : this(MachineScheduler(config), errors)

    internal constructor(scheduler: MachineScheduler, errors: MachineErrorListener) :
        this(scheduler, errors, scheduler::tick)

    private val entries = ConcurrentHashMap<K, Entry<K>>()
    private val tickCounter = AtomicLong()

    @Volatile
    private var closed = false

    /** 一覧にあるマシンの数 */
    public val size: Int get() = entries.size

    /** [tick] を呼んだ回数 */
    public val ticks: Long get() = tickCounter.get()

    /** [close]・[shutdown] の後なら `true` */
    public val isClosed: Boolean get() = closed

    /** [key] のマシン。なければ `null` */
    public operator fun get(key: K): Machine? = entries[key]?.machine

    /** 一覧にあるキー（呼んだときの写し） */
    public fun keys(): Set<K> = HashSet(entries.keys)

    /**
     * [key] のマシンを作って一覧に入れる（まだ何も動かない。起動は、返したマシンの [Machine.boot]）。
     * [key] にもうマシンがあれば、それを止めて（[MachineReleaseReason.REMOVED]）置き換える。
     *
     * @param host カーネルからの通知を受ける口。包んで渡す（クラスの説明）
     * @throws IllegalStateException [close] の後
     */
    @JvmOverloads
    public fun attach(
        key: K,
        host: MachineHost,
        config: MachineConfig,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ): Machine {
        check(!closed) { "マシンの管理は閉じている" }
        val managed = ManagedHost(key, host, errors)
        val machine = scheduler.createMachine(managed, config, environment)
        val previous = entries.put(key, Entry(machine, managed))
        previous?.let { stopEntry(it) }
        return machine
    }

    /**
     * [key] のマシンを止めて、一覧から外す。待たずに返す（プロセスのスレッドは後で終わる）。
     * 外した後のマシンの通知は、[attach] に渡した [MachineHost] に届かない。
     *
     * @return 外したら `true`。[key] にマシンがない・閉じた後なら `false`
     */
    public fun release(key: K, reason: MachineReleaseReason): Boolean {
        val entry = entries.remove(key) ?: return false
        stopFor(entry, reason)
        return true
    }

    /**
     * [key] のマシンが [machine] のときだけ、[release] する。古いブロックエンティティが、
     * 同じ場所に新しく作られたマシンを止めないために使う。
     *
     * @return 外したら `true`
     */
    public fun release(key: K, machine: Machine, reason: MachineReleaseReason): Boolean {
        val entry = entries[key] ?: return false
        if (entry.machine !== machine || !entries.remove(key, entry)) return false
        stopFor(entry, reason)
        return true
    }

    /**
     * メインスレッドから毎 tick 呼ぶ。[MachineScheduler.tick] を呼ぶ（通知とホスト呼び出しを配る）。
     * 中で例外が出ても投げず、[MachineErrorListener] に知らせる（JVM の致命的なエラーは除く）。
     */
    public fun tick() {
        tickCounter.incrementAndGet()
        if (closed) return
        try {
            tickAction()
        } catch (t: Throwable) {
            if (isFatal(t)) throw t
            report(errors, "マシンの tick で例外が起きた", t)
        }
    }

    /** 全マシンを止め、一覧を空にし、スケジューラを閉じる。待たずに返す。2回呼んでもよい */
    override fun close() {
        if (closed) return
        closed = true
        for (key in entries.keys.toList()) {
            entries.remove(key)?.let { stopEntry(it) }
        }
        scheduler.close()
    }

    /**
     * [close] して、プロセスのスレッドなどがすべて終わるまで待つ。終わったら `true`。
     *
     * @throws IllegalStateException カーネルのスレッドから呼んだ
     */
    @Throws(InterruptedException::class)
    public fun shutdown(timeoutMillis: Long): Boolean {
        close()
        return scheduler.awaitTermination(timeoutMillis)
    }

    private fun stopFor(entry: Entry<K>, reason: MachineReleaseReason) {
        when (reason) {
            // 今はどちらも止める。アンロードの間の休止（止めずに残す）は #132 で足す
            MachineReleaseReason.REMOVED, MachineReleaseReason.UNLOADED -> stopEntry(entry)
        }
    }

    private fun stopEntry(entry: Entry<K>) {
        entry.host.released = true
        try {
            entry.machine.close()
        } catch (t: Throwable) {
            if (isFatal(t)) throw t
            report(errors, "マシン ${entry.host.key} を止められなかった", t)
        }
    }

    override fun toString(): String = "MachineManager(${entries.size} machines${if (closed) ", closed" else ""})"

    private class Entry<K : Any>(val machine: Machine, val host: ManagedHost<K>)
}

/** [MachineManager] が [MachineScheduler] に渡す [MachineHost]。接続層の例外を知らせ、外した後の通知を捨てる */
internal class ManagedHost<K : Any>(
    val key: K,
    private val delegate: MachineHost,
    private val errors: MachineErrorListener,
) : MachineHost {
    /** 一覧から外した（これより後の通知は届けない） */
    @Volatile
    var released: Boolean = false

    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
        if (state == MachineState.CRASHED) {
            report(errors, "マシン $key が止まった（カーネルの不整合）", cause ?: IllegalStateException("原因なし"))
        }
        if (released) return
        guarded("onStateChanged") { delegate.onStateChanged(machine, state, cause) }
    }

    override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
        if (released) return
        guarded("onConsoleOutput") { delegate.onConsoleOutput(machine, bytes) }
    }

    override fun onBeep(machine: Machine, frequencyHz: Int, durationMillis: Int) {
        if (released) return
        guarded("onBeep") { delegate.onBeep(machine, frequencyHz, durationMillis) }
    }

    override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
        if (released) return
        guarded("onBootFailed") { delegate.onBootFailed(machine, failure, detail) }
    }

    override fun onRebootRequested(machine: Machine) {
        // 外した後は起動し直さない
        if (released) return
        guarded("onRebootRequested") { delegate.onRebootRequested(machine) }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (isFatal(t)) throw t
            report(errors, "マシン $key の $what で例外が起きた", t)
        }
    }
}

/** JVM が続けられないエラー（スタックの溢れは、そのマシンだけの問題なので含めない） */
internal fun isFatal(t: Throwable): Boolean = t is VirtualMachineError && t !is StackOverflowError

/** [errors] に知らせる。[errors] の例外は捨てる */
internal fun report(errors: MachineErrorListener, message: String, error: Throwable) {
    try {
        errors.onError(message, error)
    } catch (t: Throwable) {
        if (isFatal(t)) throw t
    }
}
