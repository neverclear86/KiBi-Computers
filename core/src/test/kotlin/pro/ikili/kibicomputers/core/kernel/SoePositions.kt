package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 深い再帰でスタックが尽きた位置（`StackOverflowError` のスタックトレース）の内訳（#858。`StackHeadroom` のテストが使う）。
 *
 * 一番上のフレームだけを見ると、次の 2 つを見逃す（#889 のレビュー）:
 * - ロックの取得・解放の途中で尽きても、インタプリタ（冷えた道）では AQS の CAS（JDK 17 は `jdk.internal.misc.Unsafe.compareAndSetInt`）が
 *   本物のフレームになり、一番上は `Unsafe` になる。ロックの中に数えるには、`Unsafe`・`java.lang.invoke`・`java.util.concurrent.atomic` を飛ばして、
 *   ロックのフレームの下を見る
 * - 本体（VFS・端末・NIO）の中で尽きたとき、一番上は `String`・`Character`・`Arrays` などの JDK の葉になる。
 *   どの区画の中かは、一番上から下へ、最初のこの MOD のフレーム（と、その前に NIO のフレームがあったか）で決める
 *
 * 数えた位置のうち、[Position.LOCK] と [Position.JDK_LOCK]（[Position.isLock]）は、入口の確かめ（`StackHeadroom`）が足りなかったことを示す
 * （ロックを取る・放す途中で尽きた）ので、テストは 0 回であることを確かめる（[inLocks]。回ごとに落とすテストは [Position.isLock] を見る）。
 * JDK のクラス経由で取るロック（カーネルロックの中の `scheduleVfsDrain` → `bootExecutor.execute`、ワーカーへの引き渡しの列、NIO のチャンネル）も、
 * 途中で尽きると共有の executor・列の中のロックが放されずに残り、安全網では戻せない（ADR 0043）ので、この MOD のロックと同じく落とす
 * （#828 の `StackExhaustionLockTest` が、一番上が `java.util.concurrent.locks.` なら呼び手を問わず落としていたのと同じ強さ）。ほかは、確かめを通った後の本体の中で尽きた位置で、ふつうに起きる（本体の奥の深さは、入口の確かめの
 * 段数に収まらない）。本体の中で尽きても安全かは、位置でなく、毎回の後の状態（ロックが取れる・CPU・許可・予約が戻る）で確かめる
 */
internal class SoePositions {
    enum class Position(
        /** ロックを取る・放す途中（テストは 0 回であることを確かめる） */
        val isLock: Boolean = false,
    ) {
        /** 入口の確かめ（`StackHeadroom.probe`）の中。何も触る前なので安全 */
        PROBE,

        /** この MOD のロック（`ReentrantLock`）を取る・放す途中（AQS・`Unsafe` の CAS を含む）。確かめが足りなかった */
        LOCK(isLock = true),

        /**
         * JDK のクラス経由で取るロック（`ThreadPoolExecutor`・`LinkedBlockingQueue` などの共有の executor・列、NIO のチャンネル）の途中。
         * この MOD のロックではないが、放されずに残ると安全網で戻せないので、[LOCK] と同じく確かめが足りなかったものとして数える
         */
        JDK_LOCK(isLock = true),

        /** ホストの I/O（`java.nio`・`sun.nio`・`java.io`）の中 */
        HOST_IO,

        /** 端末（`AnsiTerminal`・`TextBuffer`）・TTY・表示デバイスの中 */
        TERMINAL,

        /** VFS・ディスクの中 */
        VFS,

        /** カーネルの中（上のどれでもないもの） */
        KERNEL,

        /** WASM のランタイム・WASI・ホストの関数の中 */
        RUNTIME,

        /** ゲスト役のテストのコード（詰め物の再帰・テストのラムダ。パッケージを問わない）・JDK だけ */
        OTHER,

        /** `StackOverflowError` でなく、Endive の `STACK_OVERFLOW` のトラップで終わった（尽きた位置は Java のスタックでない） */
        TRAP,
    }

    val crashed = AtomicInteger()

    private val positions = ConcurrentHashMap<String, AtomicInteger>()

    /** 一番上のフレームのクラスごとの回数 */
    private val tops = ConcurrentHashMap<String, AtomicInteger>()

    private val lockCount = AtomicInteger()

    /** [Position.isLock] の位置（[Position.LOCK]・[Position.JDK_LOCK]）の回数 */
    val inLocks: Int get() = lockCount.get()

    /** [rt] の終わり方の `StackOverflowError`（原因をたどる）。なければ `null` */
    fun soe(rt: ProcessRuntime): StackOverflowError? {
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        return t as StackOverflowError?
    }

    /** [rt] が尽きた位置を数える。[tag] は、本体の種類など、位置を分けて数える名前 */
    fun record(rt: ProcessRuntime, tag: String = ""): Position = record(soe(rt), tag)

    /** [t]（尽きたときの `StackOverflowError`。なければ `STACK_OVERFLOW` のトラップ）の位置を数える */
    fun record(t: StackOverflowError?, tag: String = ""): Position {
        crashed.incrementAndGet()
        val position = if (t == null) Position.TRAP else classify(t.stackTrace)
        positions.computeIfAbsent(if (tag.isEmpty()) position.name else "$tag:${position.name}") { AtomicInteger() }.incrementAndGet()
        t?.stackTrace?.firstOrNull()?.let { top ->
            tops.computeIfAbsent(top.className.substringAfterLast('.')) { AtomicInteger() }.incrementAndGet()
        }
        if (position.isLock) {
            lockCount.incrementAndGet()
            println("#858 ロックの中で尽きた${if (tag.isEmpty()) "" else "（$tag）"}: ${t!!.stackTrace.take(16).joinToString(" <- ")}")
        }
        return position
    }

    fun describe(): String =
        "CRASHED ${crashed.get()} 回、位置: ${positions.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.get()}" }}、" +
            "一番上: ${tops.entries.sortedByDescending { it.value.get() }.take(8).joinToString { "${it.key}=${it.value.get()}" }}"

    companion object {
        private const val MOD = "pro.ikili.kibicomputers."

        /**
         * テストのコード（詰め物の再帰・テストのラムダ・テストの支え）。パッケージを問わず、本体の区画（VFS・カーネルなど）に数えない
         * （`DiskFileSystemStackExhaustionTest` は `core.kernel.vfs` にあるので、名前で見分ける）
         */
        private fun isTestCode(c: String): Boolean {
            val simple = c.substringAfterLast('.').substringBefore('$')
            // `StackHeadroomTestSupportKt` は `StackHeadroomTestSupport.kt` のトップレベルの関数
            return simple.endsWith("Test") || simple.startsWith("StackHeadroomTest") || simple == "SoePositions"
        }

        private fun isLockMachinery(c: String): Boolean =
            c.startsWith("java.util.concurrent.locks.") || c == "jdk.internal.misc.Unsafe" || c.startsWith("java.lang.invoke.") ||
                c.startsWith("java.util.concurrent.atomic.")

        /** スタックトレース [trace]（一番上が先頭）の、尽きた位置の区画 */
        fun classify(trace: Array<StackTraceElement>): Position {
            val top = trace.firstOrNull() ?: return Position.OTHER
            if (top.className == StackHeadroom::class.java.name) return Position.PROBE
            // ロックの機構（AQS・Unsafe・VarHandle・atomic）を一番上から飛ばし、その下の呼び手を見る。
            // 呼び手がこの MOD なら、この MOD のロックの途中（`Machine.withLockValue` など）。JDK の中（executor・列・NIO）なら、JDK の中のロック。
            // どちらも落とす（[Position.isLock]）
            var i = 0
            var sawLock = false
            while (i < trace.size && isLockMachinery(trace[i].className)) {
                if (trace[i].className.startsWith("java.util.concurrent.locks.")) sawLock = true
                i++
            }
            if (sawLock) {
                // 呼び手がこの MOD でなければ（NIO の中のロックなど）、JDK のロックの途中
                var j = i
                while (j < trace.size && !trace[j].className.startsWith(MOD)) {
                    val c = trace[j].className
                    if (c.startsWith("java.nio.") || c.startsWith("sun.nio.") || c.startsWith("java.io.")) return Position.JDK_LOCK
                    j++
                }
                return if (i < trace.size && trace[i].className.startsWith(MOD)) Position.LOCK else Position.JDK_LOCK
            }
            // 一番上から下へ、最初のこの MOD のフレーム（その前に NIO を通ったか）
            var hostIo = false
            for (j in i until trace.size) {
                val c = trace[j].className
                if (c.startsWith("java.nio.") || c.startsWith("sun.nio.") || c.startsWith("java.io.")) hostIo = true
                if (!c.startsWith(MOD)) continue
                return when {
                    hostIo -> Position.HOST_IO
                    c.startsWith("${MOD}core.display.") || c.startsWith("${MOD}core.kernel.tty.") -> Position.TERMINAL
                    isTestCode(c) -> Position.OTHER
                    c.startsWith("${MOD}core.kernel.vfs.") -> Position.VFS
                    c.startsWith("${MOD}core.kernel.") -> Position.KERNEL
                    c.startsWith("${MOD}core.runtime.") || c.startsWith("${MOD}core.wasi.") -> Position.RUNTIME
                    else -> Position.OTHER
                }
            }
            return if (hostIo) Position.HOST_IO else Position.OTHER
        }
    }
}
