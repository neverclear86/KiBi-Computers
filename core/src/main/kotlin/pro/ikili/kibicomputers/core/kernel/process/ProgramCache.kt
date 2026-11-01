package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.CancellationException
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
 * - 待ち行列は持ち主（マシン）ごとに分け、持ち主を巡回して1つずつ取り出す（#363）。1台が大量に積んでも、別の台の読み込みは
 *   その後ろに並ばない。[executor] には読み込み1つにつき「次の1つを取り出して読み込む」仕事を1つ渡すので、[executor] 自体の
 *   待ち行列が FIFO でも、何を読み込むかは取り出すときに巡回で決まる
 * - 持ち主ごとに、読み込み中・待ち行列にある読み込みの数を [maxPendingPerOwner] までにする。超えたら [ProgramLoadBusyException]
 *   の失敗をすぐに返す（`Machine.exec` は `AGAIN` にする）。数えるのは自分が始めた読み込みだけで、ほかの台の読み込みに相乗りしたものは数えない
 * - [load] が返す future は呼び出しごとに別のもの。待つのをやめたら `cancel` する。待つ側がすべていなくなった読み込みは、
 *   始まる前なら待ち行列から外して読み込まない（始まっていれば最後まで読み込み、結果は共有する）
 * - 読み込みが [WasmLoadException] で失敗したら、そのキーを [failureTtlNanos] の間（最大 [maxFailures] 個）覚え、
 *   その間に頼まれたら読み込まずに同じ失敗を返す。それ以外の失敗（スレッドの停止など）は覚えない
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
    /** 持ち主ごとの、読み込み中・待ち行列にある読み込みの数の上限（1 以上） */
    private val maxPendingPerOwner: Int = DEFAULT_MAX_PENDING_PER_OWNER,
    /** 失敗を覚えておく時間（ナノ秒。0 なら覚えない） */
    private val failureTtlNanos: Long = DEFAULT_FAILURE_TTL_NANOS,
    /** 覚えておく失敗の数の上限（1 以上。あふれたら古いものから忘れる） */
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    /** 時計（ナノ秒。テストで差し替える） */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(maxEntries >= 1) { "maxEntries は 1 以上: $maxEntries" }
        require(maxWeight >= 1) { "maxWeight は 1 以上: $maxWeight" }
        require(maxPendingPerOwner >= 1) { "maxPendingPerOwner は 1 以上: $maxPendingPerOwner" }
        require(failureTtlNanos >= 0) { "failureTtlNanos は 0 以上: $failureTtlNanos" }
        require(maxFailures >= 1) { "maxFailures は 1 以上: $maxFailures" }
    }

    /** 共有のキー */
    private data class Key(val digest: String, val metered: Boolean, val waitImports: Set<String>)

    private class Retained(val program: Program, val weight: Long)

    private class Ref(val key: Key, program: Program, queue: ReferenceQueue<Program>) : WeakReference<Program>(program, queue)

    /** 読み込み1つ（待ち行列にあるか、読み込み中） */
    private class Job(val key: Key, val owner: Long, val bytes: ByteArray, val loader: (ByteArray) -> Program) {
        /** 待つ側に返した future（呼び出しごと） */
        val waiters = ArrayList<CompletableFuture<Program>>(1)

        /** 読み込みを始めた */
        var started = false
    }

    /** 覚えている失敗 */
    private class Failure(val cause: Throwable, val expiresAt: Long)

    private val lock = Any()

    /** 読み込み中・待ち行列にあるもの */
    private val loading = HashMap<Key, Job>()

    /** 持ち主ごとの、始まっていない読み込み（先頭から読み込む） */
    private val queues = HashMap<Long, ArrayDeque<Job>>()

    /** 始まっていない読み込みがある持ち主の、巡回の順（先頭が次） */
    private val ring = ArrayDeque<Long>()

    /** 持ち主ごとの、読み込み中・待ち行列にある読み込みの数 */
    private val pendingByOwner = HashMap<Long, Int>()

    /** 覚えている失敗（古い順） */
    private val failures = LinkedHashMap<Key, Failure>()

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
     * 持ち主 [owner]（マシンの番号）のために、[bytes] を読み込んだ [Program] を返す。共有しているものがあればそれを（完了した future で）、
     * なければ [executor] で [loader] を呼んで読み込む。[bytes] は呼び出しの後に書き換えない。
     *
     * 返す future は呼び出しごとに別のもの。待つのをやめるときは `cancel` する（ほかに待つ側がいなくて、まだ始まっていなければ読み込まない）。
     *
     * [loader] の例外は future の失敗になる。[executor] が受け付けない（閉じた後）ときは [IllegalStateException] の失敗。
     * [owner] の読み込みが [maxPendingPerOwner] 個あるときは [ProgramLoadBusyException] の失敗。最近 [WasmLoadException] で
     * 失敗したものは、読み込まずに同じ例外の失敗
     */
    fun load(owner: Long, bytes: ByteArray, metered: Boolean, waitImports: Set<String>, loader: (ByteArray) -> Program): CompletableFuture<Program> {
        val key = Key(sha256(bytes), metered, waitImports.toSet())
        val ticket = CompletableFuture<Program>()
        val job: Job
        var created = false
        synchronized(lock) {
            expungeLocked()
            retained[key]?.let { return CompletableFuture.completedFuture(it.program) }
            alive[key]?.get()?.let { program ->
                // 強い参照から外れた後も使われていた。もう一度持つ
                retainLocked(key, program, bytes.size.toLong())
                return CompletableFuture.completedFuture(program)
            }
            recentFailureLocked(key)?.let { return CompletableFuture.failedFuture(it) }
            val existing = loading[key]
            if (existing != null) {
                // 相乗りする（持ち主の数には数えない）
                existing.waiters += ticket
                job = existing
            } else {
                if (closed) return CompletableFuture.failedFuture(closedException(null))
                val pending = pendingByOwner[owner] ?: 0
                if (pending >= maxPendingPerOwner) {
                    return CompletableFuture.failedFuture(ProgramLoadBusyException("読み込みを待つ実行ファイルが多すぎる（上限 $maxPendingPerOwner）"))
                }
                job = Job(key, owner, bytes, loader)
                job.waiters += ticket
                loading[key] = job
                pendingByOwner[owner] = pending + 1
                val ownerQueue = queues.getOrPut(owner) { ArrayDeque() }
                if (ownerQueue.isEmpty()) ring.addLast(owner)
                ownerQueue.addLast(job)
                created = true
            }
        }
        ticket.whenComplete { _, e -> if (e is CancellationException) onCancelled(job, ticket) }
        if (!created) return ticket
        try {
            executor.execute(::runNext)
        } catch (e: RejectedExecutionException) {
            val waiters = synchronized(lock) {
                if (job.started || loading[key] !== job) return ticket
                dropLocked(job)
                job.waiters.toList()
            }
            val failure = closedException(e)
            for (waiter in waiters) waiter.completeExceptionally(failure)
        }
        return ticket
    }

    /** 待つ側が1つ減った。誰も待たず、まだ始まっていなければ、待ち行列から外す */
    private fun onCancelled(job: Job, ticket: CompletableFuture<Program>) {
        synchronized(lock) {
            job.waiters.remove(ticket)
            if (job.waiters.isEmpty() && !job.started && loading[job.key] === job) dropLocked(job)
        }
    }

    /** 始まっていない [job] を、待ち行列と数から外す */
    private fun dropLocked(job: Job) {
        loading.remove(job.key)
        releaseOwnerLocked(job.owner)
        val ownerQueue = queues[job.owner] ?: return
        ownerQueue.remove(job)
        if (ownerQueue.isEmpty()) {
            queues.remove(job.owner)
            ring.remove(job.owner)
        }
    }

    private fun releaseOwnerLocked(owner: Long) {
        val n = (pendingByOwner[owner] ?: 0) - 1
        if (n <= 0) pendingByOwner.remove(owner) else pendingByOwner[owner] = n
    }

    /** 巡回の順で、次の持ち主の先頭を取り出す。なければ `null` */
    private fun takeNextLocked(): Job? {
        val owner = ring.removeFirstOrNull() ?: return null
        val ownerQueue = queues.getValue(owner)
        val job = ownerQueue.removeFirst()
        if (ownerQueue.isEmpty()) queues.remove(owner) else ring.addLast(owner)
        job.started = true
        return job
    }

    /** コンパイル用のスレッドで、次の1つを読み込む（取り消されて無くなっていれば何もしない） */
    private fun runNext() {
        val job = synchronized(lock) { takeNextLocked() } ?: return
        val result = try {
            loads.incrementAndGet()
            Result.success(job.loader(job.bytes))
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val waiters = synchronized(lock) {
            // close() が先に外して失敗させていれば、結果は覚えない
            if (loading[job.key] === job) {
                loading.remove(job.key)
                releaseOwnerLocked(job.owner)
            }
            if (!closed) {
                result.fold(
                    { program ->
                        alive[job.key] = Ref(job.key, program, queue)
                        retainLocked(job.key, program, job.bytes.size.toLong())
                    },
                    { e -> if (e is WasmLoadException) rememberFailureLocked(job.key, e) },
                )
            }
            job.waiters.toList()
        }
        result.fold({ p -> waiters.forEach { it.complete(p) } }, { e -> waiters.forEach { it.completeExceptionally(e) } })
        // 読み込みの失敗が VM のエラーなら、このスレッドでも投げる（握りつぶさない）
        result.exceptionOrNull()?.let { if (it is VirtualMachineError && it !is StackOverflowError) throw it }
    }

    /** 覚えている [key] の失敗（期限内のもの）。期限が切れていれば忘れて `null` */
    private fun recentFailureLocked(key: Key): Throwable? {
        val failure = failures[key] ?: return null
        if (nanoTime() - failure.expiresAt < 0) return failure.cause
        failures.remove(key)
        return null
    }

    private fun rememberFailureLocked(key: Key, cause: Throwable) {
        if (failureTtlNanos == 0L) return
        failures.remove(key)
        failures[key] = Failure(cause, nanoTime() + failureTtlNanos)
        val it = failures.values.iterator()
        while (failures.size > maxFailures) {
            it.next()
            it.remove()
        }
    }

    /**
     * 閉じる（スケジューラの停止。#344）。読み込み中・待ち行列にあるものの future を [IllegalStateException] で失敗させ、
     * この後の [load] も同じ失敗にする（共有しているもので済むときは、それを返す）。読み込み中の [loader] は止めない
     * （終わっても結果は覚えない）。待ち行列にあったものは読み込まない。2回呼んでもよい
     */
    fun close() {
        val pending: List<CompletableFuture<Program>>
        synchronized(lock) {
            closed = true
            pending = loading.values.flatMap { it.waiters }
            loading.clear()
            queues.clear()
            ring.clear()
            pendingByOwner.clear()
        }
        // 後続の処理（anyOf など）はロックの外で動かす
        val failure = closedException(null)
        for (future in pending) future.completeExceptionally(failure)
    }

    /** 読み込み中・待ち行列にある数（テスト用） */
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
        /** 持ち主（マシン）ごとの、読み込み中・待ち行列にある読み込みの数の上限（仮: 8。#363） */
        const val DEFAULT_MAX_PENDING_PER_OWNER: Int = 8

        /** 失敗を覚えておく時間（仮: 30 秒。#363） */
        const val DEFAULT_FAILURE_TTL_NANOS: Long = 30_000_000_000L

        /** 覚えておく失敗の数の上限（仮: 256。#363） */
        const val DEFAULT_MAX_FAILURES: Int = 256

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

/** 持ち主（マシン）の読み込みが多すぎて、受け付けなかった（#363。`Machine.exec` は `AGAIN` にする） */
internal class ProgramLoadBusyException(message: String) : Exception(message)
