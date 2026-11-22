package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
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
 * - 待ち行列は公平の組（[FairShareGroup]。ケースのプレイヤーの持ち主。#738）→ 持ち主（マシン）の二段に分け、まず組を巡回し、
 *   組の中で持ち主（マシン）を巡回して1つずつ取り出す（#363・#738）。1台・1人が大量に積んでも、別の台・別の人の読み込みは
 *   その後ろに並ばない。[executor] には読み込み1つにつき「次の1つを取り出して読み込む」仕事を1つ渡すので、[executor] 自体の
 *   待ち行列が FIFO でも、何を読み込むかは取り出すときに巡回で決まる
 * - 巡回は件数だけでなく時間でも公平にする（#750、ADR 0750）。組の間と、組ごとの持ち主（マシン）の間で、それぞれ
 *   コンパイル用のスレッドを使った経過時間を仮想時間（[Ledger]）で数え、ほかより多く使った分を「借り」とする。
 *   取り出すときは、待っているものの借りの最小を基準から引き（待っているものが全員借りていれば、その分を帳消しにする）、
 *   読み込み中でないもののうち、巡回の順で最初の「借り + 前の読み込みにかかった時間（終わる見込み）が、その最小 +
 *   [debtThresholdNanos] 以下」の組・持ち主を選ぶ（飛ばしたものの順番は変えない。まだ使っていないものは 0）。
 *   全員が読み込み中なら、読み込み中の経過時間も足して最も少ないものを選ぶ（スレッドを遊ばせない。
 *   [runNext] 1つにつき1件を取り出すことも変えない）。軽い読み込みしかないときは、件数の巡回と同じ順になる。
 *   借りは取り出した時点の持ち主と組に付け、読み込みが成功しても失敗しても付ける。相乗りした台には付けない。
 *   借りは、待っているものが全員借りているときと、待ち行列に何もない間（経過時間の [idleDecayDivisor] 分の 1 ずつ）だけ減る。
 *   前の読み込みの時間（見込み）は、読み込むたびに「その経過時間」と「前の見込み − その経過時間」の大きい方にするので、
 *   重い読み込みの間にすぐ終わる読み込みをはさんでも、見込みはほとんど減らない。待ち行列に何もない間は、借りに続けて同じ割合で減る。
 *   保存しない
 * - 持ち主（マシン）ごとに、読み込み中・待ち行列にある読み込みの数を [maxPendingPerOwner] までにする。組ごとには [maxPendingPerGroup]
 *   までにする（組の上限は、[load] で `limitGroup` を立てたものだけを断る。init・復元の読み込みは断らない。#738）。
 *   超えたら [ProgramLoadBusyException] の失敗をすぐに返す（`Machine.exec` は `AGAIN` にする）。
 *   数えるのは自分が始めた読み込みだけで、ほかの台の読み込みに相乗りしたものは数えない（組の数も同じ）
 * - 読み込みの持ち主が待つのをやめたら（その持ち主の future がすべて取り消されたら）、持ち主の数から外す（#383）。
 *   始まる前で、相乗りした台が残っていれば、残った台のうち最も早く頼んだ台を新しい持ち主にし、その台の列の末尾に移して
 *   その台の数に入れる（その台の数が一時的に上限を超えることもある）。組も、その台が頼んだときの組に移す。
 *   始まっていれば持ち主のない読み込みとして最後まで読み込む
 * - [load] が返す future は呼び出しごとに別のもの。待つのをやめたら `cancel` する。待つ側がすべていなくなった読み込みは、
 *   始まる前なら待ち行列から外して読み込まない（始まっていれば最後まで読み込み、結果は共有する）
 * - 読み込みが [WasmLoadException] で失敗したら、そのキーを [failureTtlNanos] の間（最大 [maxFailures] 個）覚え、
 *   その間に頼まれたら読み込まずに同じ失敗を返す。それ以外の失敗（スレッドの停止、コンポーネントの検証の途中の割り込み
 *   `WasmTrapException` など）は覚えない
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
    /**
     * 強い参照で持つものの重さ（[Program.cacheWeight]。core モジュールは読み込む前のバイト数、コンポーネントは中の core モジュールの
     * 数と計画の大きさを足した見積もり）の合計の上限（1 以上）。1 つでこれを超えるものも、最新の 1 つなら持つ
     */
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
    /** 公平の組ごとの、読み込み中・待ち行列にある読み込みの数の上限（1 以上。`Int.MAX_VALUE` で上限なし。#738） */
    private val maxPendingPerGroup: Int = Int.MAX_VALUE,
    /**
     * 借り（ほかより多く使った経過時間。ナノ秒）が、待っているものの最小よりこれだけ多い組・持ち主は、ほかに当てはまるものがあれば
     * 後に回す（0 以上。`Long.MAX_VALUE` なら件数の巡回だけ。#750）
     */
    private val debtThresholdNanos: Long = DEFAULT_DEBT_THRESHOLD_NANOS,
    /** 待ち行列に何もない組・持ち主の借りが減る速さ（経過時間の何分の 1 ずつ減るか。1 以上。#750） */
    private val idleDecayDivisor: Long = DEFAULT_IDLE_DECAY_DIVISOR,
) {
    init {
        require(maxEntries >= 1) { "maxEntries は 1 以上: $maxEntries" }
        require(maxWeight >= 1) { "maxWeight は 1 以上: $maxWeight" }
        require(maxPendingPerOwner >= 1) { "maxPendingPerOwner は 1 以上: $maxPendingPerOwner" }
        require(failureTtlNanos >= 0) { "failureTtlNanos は 0 以上: $failureTtlNanos" }
        require(maxFailures >= 1) { "maxFailures は 1 以上: $maxFailures" }
        require(maxPendingPerGroup >= 1) { "maxPendingPerGroup は 1 以上: $maxPendingPerGroup" }
        require(debtThresholdNanos >= 0) { "debtThresholdNanos は 0 以上: $debtThresholdNanos" }
        require(idleDecayDivisor >= 1) { "idleDecayDivisor は 1 以上: $idleDecayDivisor" }
    }

    /** 共有のキー */
    private data class Key(val digest: String, val metered: Boolean, val waitImports: Set<String>)

    private class Retained(val program: Program, val weight: Long)

    private class Ref(val key: Key, program: Program, queue: ReferenceQueue<Program>) : WeakReference<Program>(program, queue)

    /** 読み込み1つ（待ち行列にあるか、読み込み中） */
    private class Job(val key: Key, owner: Long, group: FairShareGroup, val bytes: ByteArray, val loader: (ByteArray) -> Program) {
        /**
         * 数に入れている持ち主（始まっていなければ、この持ち主の列にある）。持ち主が待つのをやめて、始まった後なら `null`
         * （どの持ち主の数にも入れない。#383）
         */
        var owner: Long? = owner

        /** 数に入れている公平の組（[owner] が `null` でないときだけ意味がある。持ち主が頼んだときの組。#738） */
        var group: FairShareGroup = group

        /** 待つ側に返した future（呼び出しごと。頼んだ順）と、頼んだ持ち主 */
        val waiters = ArrayList<Waiter>(1)

        /** 読み込みを始めた */
        var started = false

        /** 借りを付ける持ち主（取り出した時点の [owner]。#750） */
        var chargedOwner = 0L

        /** 借りを付ける組（取り出した時点の [group]。#750） */
        var chargedGroup: FairShareGroup = group

        /** 読み込みを始めた時刻（[nanoTime]。#750） */
        var startedAt = 0L
    }

    /**
     * 組か持ち主1つの、コンパイル用のスレッドを使った時間（#750）。借りは [vtime] − [Ledger.floor]（0 未満は 0）。
     * 読み込み中のものの時間は、終わってから [vtime] に足す
     */
    private class Usage(var vtime: Long, var at: Long) {
        /**
         * 次の読み込みにかかる時間の見込み。読み込みが終わるたびに、その経過時間と前の見込みの大きい方にする。
         * 読み込みでは減らず、頼まずにいた間にだけ減る（[forgiveIdleLocked]）。読み込みで減らせると、重い読み込みの間に
         * すぐ終わる読み込みや中くらいの読み込み（8 秒の後に 4・2・1 秒）をはさんで、軽い台が待っていない空き時間に見込みを崩せる
         */
        var lastCost = 0L

        /** 読み込み中の数 */
        var running = 0

        /** 読み込み中のものの、始めた時刻の合計（あふれても、時刻の差は正しく出る） */
        var startSum = 0L
    }

    /**
     * 使った時間の勘定1つ（組の間で1つ、組ごとに持ち主の間で1つ。#750）。[floor] は仮想時間の基準で、進むだけ。
     * 記録のないものは借りが 0
     */
    private class Ledger<K> {
        var floor = 0L
        val usage = HashMap<K, Usage>()
    }

    /** 待つ側1つ（頼んだ持ち主と、頼んだときの公平の組） */
    private class Waiter(val ticket: CompletableFuture<Program>, val owner: Long, val group: FairShareGroup)

    /** 組と持ち主（マシン）の組み合わせ1つの列の鍵（持ち主の組が変わっても、前の組の列と混ぜない） */
    private data class Slot(val group: FairShareGroup, val owner: Long)

    /** 覚えている失敗 */
    private class Failure(val cause: Throwable, val expiresAt: Long)

    private val lock = Any()

    /** 読み込み中・待ち行列にあるもの */
    private val loading = HashMap<Key, Job>()

    /** 組と持ち主ごとの、始まっていない読み込み（先頭から読み込む） */
    private val queues = HashMap<Slot, ArrayDeque<Job>>()

    /** 組ごとの、始まっていない読み込みがある持ち主の巡回の順（先頭が次） */
    private val ownerRings = HashMap<FairShareGroup, ArrayDeque<Long>>()

    /** 始まっていない読み込みがある組の、巡回の順（先頭が次） */
    private val ring = ArrayDeque<FairShareGroup>()

    /** 持ち主ごとの、読み込み中・待ち行列にある読み込みの数 */
    private val pendingByOwner = HashMap<Long, Int>()

    /** 組ごとの、読み込み中・待ち行列にある読み込みの数（0 になった組は消す。#738） */
    private val pendingByGroup = HashMap<FairShareGroup, Int>()

    /** 組の間の、使った時間の勘定（#750。借りがなく、待ち行列にも読み込み中にもないものは消す） */
    private val groupLedger = Ledger<FairShareGroup>()

    /** 組ごとの、持ち主（マシン）の間の使った時間の勘定（#750。同じく消し、空になった勘定も消す） */
    private val ownerLedgers = HashMap<FairShareGroup, Ledger<Long>>()

    /** 借りを付けた回数（[USAGE_SWEEP_INTERVAL] 回ごとに、減りきったものを消す） */
    private var charges = 0

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
    fun load(owner: Long, bytes: ByteArray, metered: Boolean, waitImports: Set<String>, loader: (ByteArray) -> Program): CompletableFuture<Program> =
        load(owner, FairShareGroup.NONE, true, bytes, metered, waitImports, loader)

    /**
     * 公平の組 [group] の持ち主 [owner] のために読み込む（#738）。ほかは 5 引数の [load] と同じ。
     * [limitGroup] なら、[group] の読み込みが [maxPendingPerGroup] 個あるときに [ProgramLoadBusyException] の失敗にする。
     * 立てないもの（init・復元の読み込み）は、組の数には入れるが、組の上限では断らない
     */
    fun load(
        owner: Long,
        group: FairShareGroup,
        limitGroup: Boolean,
        bytes: ByteArray,
        metered: Boolean,
        waitImports: Set<String>,
        loader: (ByteArray) -> Program,
    ): CompletableFuture<Program> {
        val key = Key(sha256(bytes), metered, waitImports.toSet())
        val ticket = CompletableFuture<Program>()
        val job: Job
        var created = false
        synchronized(lock) {
            expungeLocked()
            retained[key]?.let { return CompletableFuture.completedFuture(it.program) }
            alive[key]?.get()?.let { program ->
                // 強い参照から外れた後も使われていた。もう一度持つ
                retainLocked(key, program)
                return CompletableFuture.completedFuture(program)
            }
            recentFailureLocked(key)?.let { return CompletableFuture.failedFuture(it) }
            val existing = loading[key]
            if (existing != null) {
                // 相乗りする（持ち主の数には数えない）
                existing.waiters += Waiter(ticket, owner, group)
                job = existing
            } else {
                if (closed) return CompletableFuture.failedFuture(closedException(null))
                val pending = pendingByOwner[owner] ?: 0
                if (pending >= maxPendingPerOwner) {
                    return CompletableFuture.failedFuture(ProgramLoadBusyException("読み込みを待つ実行ファイルが多すぎる（上限 $maxPendingPerOwner）"))
                }
                val groupPending = pendingByGroup[group] ?: 0
                if (limitGroup && groupPending >= maxPendingPerGroup) {
                    return CompletableFuture.failedFuture(
                        ProgramLoadBusyException("この持ち主の読み込みを待つ実行ファイルが多すぎる（上限 $maxPendingPerGroup）"),
                    )
                }
                job = Job(key, owner, group, bytes, loader)
                job.waiters += Waiter(ticket, owner, group)
                loading[key] = job
                pendingByOwner[owner] = pending + 1
                pendingByGroup[group] = groupPending + 1
                enqueueLocked(job)
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
                job.waiters.map { it.ticket }
            }
            val failure = closedException(e)
            for (waiter in waiters) waiter.completeExceptionally(failure)
        }
        return ticket
    }

    /**
     * 待つ側が1つ減った。誰も待たず、まだ始まっていなければ、待ち行列から外す。
     * 持ち主が待たなくなったら、持ち主の数から外し、始まっていなければ残った台に移す（#383）
     */
    private fun onCancelled(job: Job, ticket: CompletableFuture<Program>) {
        synchronized(lock) {
            job.waiters.removeAll { it.ticket === ticket }
            // 終わった・close() で外れたものは、もう数に入っていない
            if (loading[job.key] !== job) return
            if (job.waiters.isEmpty() && !job.started) {
                dropLocked(job)
                return
            }
            val owner = job.owner ?: return
            if (job.waiters.any { it.owner == owner }) return
            releaseOwnerLocked(owner, job.group)
            if (job.started) {
                job.owner = null
            } else {
                dequeueLocked(job)
                // 残った台のうち、最も早く頼んだ台に移す（組も、その台が頼んだときの組に移す。#738）
                val next = job.waiters.first()
                job.owner = next.owner
                job.group = next.group
                pendingByOwner[next.owner] = (pendingByOwner[next.owner] ?: 0) + 1
                pendingByGroup[next.group] = (pendingByGroup[next.group] ?: 0) + 1
                enqueueLocked(job)
            }
        }
    }

    /** 始まっていない [job] を、待ち行列と数から外す */
    private fun dropLocked(job: Job) {
        loading.remove(job.key)
        val owner = job.owner ?: return
        releaseOwnerLocked(owner, job.group)
        dequeueLocked(job)
        job.owner = null
    }

    /** 始まっていない [job] を、持ち主（[Job.owner]）と組（[Job.group]）の列の末尾に入れる（列が空なら巡回にも入れる） */
    private fun enqueueLocked(job: Job) {
        val owner = checkNotNull(job.owner)
        val slot = Slot(job.group, owner)
        val ownerQueue = queues.getOrPut(slot) { ArrayDeque() }
        if (ownerQueue.isEmpty()) {
            val owners = ownerRings.getOrPut(job.group) { ArrayDeque() }
            if (owners.isEmpty()) {
                ring.addLast(job.group)
                forgiveIdleLocked(groupLedger, job.group)
            }
            owners.addLast(owner)
            ownerLedgers[job.group]?.let { forgiveIdleLocked(it, owner) }
        }
        ownerQueue.addLast(job)
    }

    /**
     * 待ち行列に入っていなかった [key] が入る。読み込み中のものもなければ、前に借りを付けてから（前に減らしてから）の経過時間の
     * [idleDecayDivisor] 分の 1 だけ、借りを減らし、余った分で前の読み込みの時間（見込み）も減らす（#750。続けて頼み直す台は減らない）
     */
    private fun <K> forgiveIdleLocked(ledger: Ledger<K>, key: K) {
        val u = ledger.usage[key] ?: return
        if (u.running != 0) return
        val now = nanoTime()
        val decay = (now - u.at).coerceAtLeast(0) / idleDecayDivisor
        val lag = lagOf(ledger, u)
        u.vtime = ledger.floor + (lag - decay).coerceAtLeast(0)
        if (decay > lag) u.lastCost = (u.lastCost - (decay - lag)).coerceAtLeast(0)
        u.at = now
    }

    /** 始まっていない [job] を、持ち主と組の列から外す（列が空になれば巡回からも外す） */
    private fun dequeueLocked(job: Job) {
        val owner = job.owner ?: return
        val slot = Slot(job.group, owner)
        val ownerQueue = queues[slot] ?: return
        ownerQueue.remove(job)
        if (ownerQueue.isNotEmpty()) return
        queues.remove(slot)
        val owners = ownerRings[job.group] ?: return
        owners.remove(owner)
        if (owners.isEmpty()) {
            ownerRings.remove(job.group)
            ring.remove(job.group)
        }
    }

    private fun releaseOwnerLocked(owner: Long, group: FairShareGroup) {
        val n = (pendingByOwner[owner] ?: 0) - 1
        if (n <= 0) pendingByOwner.remove(owner) else pendingByOwner[owner] = n
        val g = (pendingByGroup[group] ?: 0) - 1
        if (g <= 0) pendingByGroup.remove(group) else pendingByGroup[group] = g
    }

    /**
     * 巡回の順で、次の組の、次の持ち主の先頭を取り出す（借りの多いものは後に回す。#750）。どこかの列にあれば必ず1つ返す。
     * なければ `null`
     */
    private fun takeNextLocked(): Job? {
        if (ring.isEmpty()) return null
        val now = nanoTime()
        val group = ring.removeAt(pickLocked(ring, groupLedger, now))
        val owners = ownerRings.getValue(group)
        val ownerLedger = ownerLedgers.getOrPut(group) { Ledger() }
        val owner = owners.removeAt(pickLocked(owners, ownerLedger, now))
        val slot = Slot(group, owner)
        val ownerQueue = queues.getValue(slot)
        val job = ownerQueue.removeFirst()
        if (ownerQueue.isEmpty()) queues.remove(slot) else owners.addLast(owner)
        if (owners.isEmpty()) ownerRings.remove(group) else ring.addLast(group)
        job.started = true
        job.chargedOwner = owner
        job.chargedGroup = group
        job.startedAt = now
        beginUsageLocked(ownerLedger, owner, now)
        beginUsageLocked(groupLedger, group, now)
        return job
    }

    /**
     * 巡回の順 [order]（空でない）から選ぶものの位置（#750）。
     *
     * 1. 待っているもの全員に借りがあれば、その最小だけ [Ledger.floor] を進める（全員から同じだけ帳消しにする）
     * 2. 読み込み中でないもののうち、先頭から見て、終わる見込み（借り + 前の読み込みの時間。記録がなければ 0）が
     *    「その最小 + [debtThresholdNanos]」以下の最初のもの
     * 3. 全員が読み込み中なら、終わる見込みに読み込み中の経過時間を足したものが最も少ないもの（同じなら前のもの。スレッドを遊ばせない）
     *
     * 先頭に記録がなければ（借り 0 で読み込み中でもない）すぐ選ぶので、軽い読み込みだけのときは先頭を見るだけで済む
     */
    private fun <K> pickLocked(order: ArrayDeque<K>, ledger: Ledger<K>, now: Long): Int {
        val usage = ledger.usage
        if (usage[order[0]] == null) return 0
        var minLag = Long.MAX_VALUE
        for (key in order) {
            val lag = usage[key]?.let { lagOf(ledger, it) } ?: 0
            if (lag < minLag) minLag = lag
        }
        if (minLag > 0) ledger.floor += minLag
        // 読み込み中でないものの、借りと前の読み込みの時間の和（終わる見込みの仮想時刻）の最小
        var minFinish = Long.MAX_VALUE
        for (key in order) {
            val u = usage[key]
            if (u == null) {
                minFinish = 0
                break
            }
            if (u.running == 0) minFinish = minOf(minFinish, finishOf(ledger, u))
        }
        if (minFinish == Long.MAX_VALUE) {
            // 全員が読み込み中（スレッドが 2 本以上）。読み込み中の経過時間を足して最も少ないもの
            var best = 0
            var bestTotal = Long.MAX_VALUE
            for (i in order.indices) {
                val u = usage.getValue(order[i])
                val total = finishOf(ledger, u) + (u.running * now - u.startSum)
                if (total < bestTotal) {
                    best = i
                    bestTotal = total
                }
            }
            return best
        }
        val limit = if (debtThresholdNanos > Long.MAX_VALUE - minFinish) Long.MAX_VALUE else minFinish + debtThresholdNanos
        for (i in order.indices) {
            val u = usage[order[i]] ?: return i
            if (u.running == 0 && finishOf(ledger, u) <= limit) return i
        }
        error("選べるものがない")
    }

    /** [u] の借り（0 以上。読み込み中のものの経過時間は入れない） */
    private fun lagOf(ledger: Ledger<*>, u: Usage): Long = (u.vtime - ledger.floor).coerceAtLeast(0)

    /** [u] の次の読み込みが終わる見込みの仮想時刻（基準から。借り + 前の読み込みにかかった時間） */
    private fun finishOf(ledger: Ledger<*>, u: Usage): Long = lagOf(ledger, u) + u.lastCost

    private fun <K> beginUsageLocked(ledger: Ledger<K>, key: K, now: Long) {
        val u = ledger.usage.getOrPut(key) { Usage(ledger.floor, now) }
        u.running++
        u.startSum += now
    }

    /** 読み込みが終わった [job] の経過時間を、取り出した時点の持ち主と組の借りに足す（成功でも失敗でも。#750） */
    private fun chargeLocked(job: Job, now: Long) {
        ownerLedgers[job.chargedGroup]?.let { endUsageLocked(it, job.chargedOwner, job.startedAt, now) }
        endUsageLocked(groupLedger, job.chargedGroup, job.startedAt, now)
        if (++charges % USAGE_SWEEP_INTERVAL == 0) sweepUsageLocked(now)
    }

    private fun <K> endUsageLocked(ledger: Ledger<K>, key: K, startedAt: Long, now: Long) {
        val u = ledger.usage[key] ?: return
        val elapsed = (now - startedAt).coerceAtLeast(0)
        u.vtime = ledger.floor + lagOf(ledger, u) + elapsed
        u.lastCost = maxOf(elapsed, u.lastCost)
        u.at = now
        u.running--
        u.startSum -= startedAt
    }

    /**
     * 待ち行列にも読み込み中にもなく、待たずにいた分を引けば借りも前の読み込みの時間の見込みもなくなる（[forgiveIdleLocked] で
     * 記録がないのと同じになる）ものを消す。空になった持ち主の勘定も消す（台が消えても表に残らない。#750）
     */
    private fun sweepUsageLocked(now: Long) {
        fun <K> Ledger<K>.sweep(queued: (K) -> Boolean) {
            usage.entries.removeAll { (key, u) ->
                u.running == 0 && !queued(key) && finishOf(this, u) - (now - u.at) / idleDecayDivisor <= 0
            }
        }
        groupLedger.sweep { ownerRings.containsKey(it) }
        val iter = ownerLedgers.entries.iterator()
        while (iter.hasNext()) {
            val (group, ledger) = iter.next()
            ledger.sweep { queues.containsKey(Slot(group, it)) }
            if (ledger.usage.isEmpty()) iter.remove()
        }
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
            // 借りは成功でも失敗でも（VM のエラーでも）付ける。close() の後は表を消したので付けない（#750）
            if (!closed) chargeLocked(job, nanoTime())
            // close() が先に外して失敗させていれば、結果は覚えない
            if (loading[job.key] === job) {
                loading.remove(job.key)
                job.owner?.let { releaseOwnerLocked(it, job.group) }
                job.owner = null
            }
            if (!closed) {
                result.fold(
                    { program ->
                        alive[job.key] = Ref(job.key, program, queue)
                        retainLocked(job.key, program)
                    },
                    { e -> if (e is WasmLoadException) rememberFailureLocked(job.key, e) },
                )
            }
            job.waiters.map { it.ticket }
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
            pending = loading.values.flatMap { job -> job.waiters.map { it.ticket } }
            loading.clear()
            queues.clear()
            ownerRings.clear()
            ring.clear()
            pendingByOwner.clear()
            pendingByGroup.clear()
            groupLedger.usage.clear()
            ownerLedgers.clear()
        }
        // 後続の処理（anyOf など）はロックの外で動かす
        val failure = closedException(null)
        for (future in pending) future.completeExceptionally(failure)
    }

    /** 読み込み中・待ち行列にある数（テスト用） */
    val loadingCount: Int get() = synchronized(lock) { loading.size }

    /** [owner] の数に入っている、読み込み中・待ち行列にある読み込みの数（テスト用） */
    fun pendingOf(owner: Long): Int = synchronized(lock) { pendingByOwner[owner] ?: 0 }

    /** 組 [group] の数に入っている、読み込み中・待ち行列にある読み込みの数（テスト用。#738） */
    fun pendingOfGroup(group: FairShareGroup): Int = synchronized(lock) { pendingByGroup[group] ?: 0 }

    /**
     * 組 [group] の持ち主 [owner] の借り（ナノ秒。読み込み中のものの経過時間と、待ち行列に入るときに減らす分は入れない。
     * テスト用。#750）
     */
    fun debtOf(owner: Long, group: FairShareGroup = FairShareGroup.NONE): Long = synchronized(lock) {
        val ledger = ownerLedgers[group] ?: return 0
        ledger.usage[owner]?.let { lagOf(ledger, it) } ?: 0
    }

    /** 組 [group] の借り（ナノ秒。テスト用。#750） */
    fun debtOfGroup(group: FairShareGroup): Long = synchronized(lock) { groupLedger.usage[group]?.let { lagOf(groupLedger, it) } ?: 0 }

    /** 持ち主の間の勘定の数（組ごとに 1 つ。空になった勘定は後始末で消える。テスト用。#750） */
    val ownerLedgerCount: Int get() = synchronized(lock) { ownerLedgers.size }

    /** 使った時間を覚えている持ち主と組の数（テスト用。#750） */
    val usageCount: Int get() = synchronized(lock) { groupLedger.usage.size + ownerLedgers.values.sumOf { it.usage.size } }

    /** 読み込み中の持ち主の読み込みの数（組の勘定の読み込み中の数の合計。テスト用。#750） */
    val runningCount: Int get() = synchronized(lock) { groupLedger.usage.values.sumOf { it.running } }

    /** 数を記録している組の数（テスト用。#738。0 になった組は消える） */
    val pendingGroupCount: Int get() = synchronized(lock) { pendingByGroup.size }

    private fun closedException(cause: Throwable?) = IllegalStateException("WASM を読み込むスレッドが止まっている", cause)

    /** 強い参照で持つ。重さは [Program.cacheWeight]（#687）。あふれたら古いものから外す（最新の 1 つは残す） */
    private fun retainLocked(key: Key, program: Program) {
        val weight = program.cacheWeight
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

        /**
         * 借りが待っているものの最小よりこれだけ多い組・持ち主を後に回す（仮: 2 秒。挿入後の lua.wasm の最初の読み込み 0.3〜0.9 秒より
         * 十分大きくし、普通の読み込みでは件数の巡回と同じ順にする。確定は #251。#750）
         */
        const val DEFAULT_DEBT_THRESHOLD_NANOS: Long = 2_000_000_000L

        /**
         * 待ち行列に何もない組・持ち主の借りが減る速さ（仮: 経過時間の 1/8。8 秒の読み込みの後、64 秒頼まずにいれば借りが、
         * 128 秒で前の読み込みの時間の見込みも消える。確定は #251。#750）
         */
        const val DEFAULT_IDLE_DECAY_DIVISOR: Long = 8

        /** 減りきった借りを消す間隔（借りを付けた回数） */
        private const val USAGE_SWEEP_INTERVAL: Int = 64

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
