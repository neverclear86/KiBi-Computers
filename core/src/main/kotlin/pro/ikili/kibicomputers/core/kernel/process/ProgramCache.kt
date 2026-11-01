package pro.ikili.kibicomputers.core.kernel.process

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 読み込んだ実行ファイル（[Program]）を、WASM の中身のハッシュで共有する（#37。ADR 0029 の B）。サーバーに1つ（スケジューラが持つ）。
 *
 * 読み込み（待機点への変換・燃料の計測コードの挿入・パース・検証・コンパイル）は重く（挿入後の lua.wasm で 0.3〜0.9 秒、
 * コンパイル結果は Metaspace を約 1.3 MB 使う）、同じ WASM をマシンごとに読み込むとヒープも 1 台あたり十数 MB 使う。
 * そこで、同じ中身・同じ読み込み方なら1回だけ読み込み、結果を全マシンで使い回す。
 *
 * - キー: 読み込む前のバイト列の SHA-256、燃料の計測コードを挿入するか、待機点への変換をかける import の集合
 *   （構造の上限とランタイムはスケジューラごとに決まっているので、キーに入れない）
 * - 読み込みは [executor]（コンパイル用のスレッド）で行う。同じキーを同時に頼まれたら、1回だけ読み込んで待ち合わせる
 * - 失敗した読み込みは覚えない（次に頼まれたら読み込み直す）
 * - [close] の後は読み込まない。読み込み中・待ち行列にあったものの future も、その場で失敗させる（#344。待つ側を止めないため）
 * - 保持: 最近使った [maxEntries] 個（かつ重さの合計が [maxWeight] まで）を強い参照で持つ。あふれたら、最も長く使っていないものから
 *   強い参照を外す。外したものも、どこかのプロセスがまだ使っていれば（弱い参照が生きていれば）同じものを返す。
 *   使っているマシンがなくなれば GC で回収され、Metaspace も戻る。動いている WASM の数そのものは制限しない
 *
 * スレッド: どのスレッドから呼んでもよい。
 */
internal class ProgramCache(
    /** 強い参照で持つ数の上限（1 以上） */
    private val maxEntries: Int,
    /** 強い参照で持つものの重さ（読み込む前のバイト数）の合計の上限（1 以上）。1 つでこれを超えるものも、最新の 1 つなら持つ */
    private val maxWeight: Long,
    /** 読み込みを行う Executor */
    private val executor: Executor,
) {
    init {
        require(maxEntries >= 1) { "maxEntries は 1 以上: $maxEntries" }
        require(maxWeight >= 1) { "maxWeight は 1 以上: $maxWeight" }
    }

    /** 共有のキー */
    private data class Key(val digest: String, val metered: Boolean, val waitImports: Set<String>)

    private class Retained(val program: Program, val weight: Long)

    private class Ref(val key: Key, program: Program, queue: ReferenceQueue<Program>) : WeakReference<Program>(program, queue)

    private val lock = Any()

    /** 読み込み中のもの */
    private val loading = HashMap<Key, CompletableFuture<Program>>()

    /** 強い参照で持つもの（使った順。先頭が最も古い） */
    private val retained = LinkedHashMap<Key, Retained>(16, 0.75f, true)
    private var retainedWeight = 0L

    /** 読み込んだもの全部（弱い参照。回収されたら [queue] から消す） */
    private val alive = HashMap<Key, Ref>()
    private val queue = ReferenceQueue<Program>()

    /** [close] した */
    private var closed = false

    /** 実際に読み込んだ回数（数え口。テスト用） */
    val loads: AtomicLong = AtomicLong()

    /** 強い参照で持っている数（テスト用） */
    val retainedCount: Int get() = synchronized(lock) { retained.size }

    /**
     * [bytes] を読み込んだ [Program] を返す。共有しているものがあればそれを（完了した future で）、
     * なければ [executor] で [loader] を呼んで読み込む。[bytes] は呼び出しの後に書き換えない。
     *
     * [loader] の例外は future の失敗になる。[executor] が受け付けない（閉じた後）ときは [IllegalStateException] の失敗。
     */
    fun load(bytes: ByteArray, metered: Boolean, waitImports: Set<String>, loader: (ByteArray) -> Program): CompletableFuture<Program> {
        val key = Key(sha256(bytes), metered, waitImports.toSet())
        val future: CompletableFuture<Program>
        synchronized(lock) {
            expungeLocked()
            retained[key]?.let { return CompletableFuture.completedFuture(it.program) }
            alive[key]?.get()?.let { program ->
                // 強い参照から外れた後も使われていた。もう一度持つ
                retainLocked(key, program, bytes.size.toLong())
                return CompletableFuture.completedFuture(program)
            }
            loading[key]?.let { return it }
            if (closed) return CompletableFuture.failedFuture(closedException(null))
            future = CompletableFuture()
            loading[key] = future
        }
        val task = Runnable {
            val result = try {
                loads.incrementAndGet()
                Result.success(loader(bytes))
            } catch (t: Throwable) {
                Result.failure(t)
            }
            synchronized(lock) {
                // close() が先に外して失敗させていれば、結果は覚えない
                if (loading[key] === future) loading.remove(key)
                if (!closed) result.getOrNull()?.let { program ->
                    alive[key] = Ref(key, program, queue)
                    retainLocked(key, program, bytes.size.toLong())
                }
            }
            result.fold({ future.complete(it) }, { future.completeExceptionally(it) })
            // 読み込みの失敗が VM のエラーなら、このスレッドでも投げる（握りつぶさない）
            result.exceptionOrNull()?.let { if (it is VirtualMachineError && it !is StackOverflowError) throw it }
        }
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            synchronized(lock) { if (loading[key] === future) loading.remove(key) }
            future.completeExceptionally(closedException(e))
        }
        return future
    }

    /**
     * 閉じる（スケジューラの停止。#344）。読み込み中・待ち行列にあるものの future を [IllegalStateException] で失敗させ、
     * この後の [load] も同じ失敗にする（共有しているもので済むときは、それを返す）。読み込み中の [loader] は止めない
     * （終わっても結果は覚えない）。2回呼んでもよい
     */
    fun close() {
        val pending: List<CompletableFuture<Program>>
        synchronized(lock) {
            closed = true
            pending = loading.values.toList()
            loading.clear()
        }
        // 後続の処理（anyOf など）はロックの外で動かす
        for (future in pending) future.completeExceptionally(closedException(null))
    }

    /** 読み込み中の数（テスト用） */
    val loadingCount: Int get() = synchronized(lock) { loading.size }

    private fun closedException(cause: Throwable?) = IllegalStateException("WASM を読み込むスレッドが止まっている", cause)

    /** 強い参照で持つ。あふれたら古いものから外す（最新の 1 つは残す） */
    private fun retainLocked(key: Key, program: Program, weight: Long) {
        retained.remove(key)?.let { retainedWeight -= it.weight }
        retained[key] = Retained(program, weight)
        retainedWeight += weight
        val it = retained.entries.iterator()
        while (retained.size > 1 && (retained.size > maxEntries || retainedWeight > maxWeight)) {
            val eldest = it.next()
            retainedWeight -= eldest.value.weight
            it.remove()
        }
    }

    /** 回収された弱い参照を消す */
    private fun expungeLocked() {
        while (true) {
            val ref = queue.poll() as? Ref ?: return
            if (alive[ref.key] === ref) alive.remove(ref.key)
        }
    }

    /** 強い参照をすべて外す（テスト用） */
    fun releaseAll() {
        synchronized(lock) {
            retained.clear()
            retainedWeight = 0
        }
    }

    companion object {
        /** 仕事のないコンパイル用のスレッドが終わるまでの時間 */
        private const val COMPILER_IDLE_SECONDS: Long = 30

        /**
         * コンパイル用のスレッドのプール（[threads] 本まで。優先度を下げる）。仕事がなければスレッドは終わる。
         * スレッドのスタックは [stackBytes]（パースとコンパイルは再帰するので、カーネルのスレッドと同じ大きさにする）
         */
        fun compilerExecutor(threads: Int, stackBytes: Long): ThreadPoolExecutor {
            val counter = AtomicInteger()
            return ThreadPoolExecutor(threads, threads, COMPILER_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
                Thread(null, r, "KiBi-wasm-compiler-${counter.incrementAndGet()}", stackBytes).apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }.apply { allowCoreThreadTimeOut(true) }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
