package pro.ikili.kibicomputers.core.kernel.sched

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * ディスパッチャー（`KiBi-dispatcher`。スケジューラに1本）。
 *
 * `tick()`（と一時停止の解除）から知らせを受け、燃料待ちのプロセスがいるマシンの列だけを取り出して、
 * 燃料待ち（step の途中で燃料切れの処理の中にいるスレッドと、境目で燃料を待つスレッドを持たないプロセス）を起こす・CPU の待ち行列に入れ直す。
 * メインスレッドは燃料待ちを起こさない（1,000 台が燃料待ちのとき、起こす処理がメインスレッドの tick に乗るため）。
 *
 * 列の先頭は見直すたびにずらす（公平さは #47）。
 *
 * @param M マシン
 * @param dispatch カーネルロックを取って、そのマシンの燃料待ちを起こし、（起こした数, まだ待ちが残っているか）を返す。列のロックの外で呼ぶ
 */
internal class Dispatcher<M : Any>(stackBytes: Long, private val dispatch: (M) -> Pair<Int, Boolean>) {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private var pending = ArrayList<M>()
    private var signaled = false
    private var closed = false
    private var rotation = 0

    /** 起こした数（数え口） */
    val woken: AtomicLong = AtomicLong()

    val thread: Thread = KernelThread("KiBi-dispatcher", stackBytes) { loop() }

    fun start() = thread.start()

    /** 燃料待ちのいるマシンの列に入れる。カーネルロックの中から呼んでよい（列のロックは末端） */
    fun add(machine: M) {
        lock.withLockValue { pending.add(machine) }
    }

    /** 列の長さ（テスト用） */
    val size: Int get() = lock.withLockValue { pending.size }

    /** 列を見直す */
    fun signal() {
        lock.withLockValue {
            signaled = true
            condition.signal()
        }
    }

    fun close() {
        lock.withLockValue {
            closed = true
            condition.signal()
        }
    }

    private fun loop() {
        while (true) {
            val batch: ArrayList<M>
            lock.lock()
            try {
                while (!signaled && !closed) condition.awaitUninterruptibly()
                if (closed) return
                signaled = false
                batch = pending
                pending = ArrayList()
            } finally {
                lock.unlock()
            }
            if (batch.isEmpty()) continue
            val keep = ArrayList<M>()
            val start = Math.floorMod(rotation++, batch.size)
            for (i in batch.indices) {
                val machine = batch[(start + i) % batch.size]
                val (count, remaining) = try {
                    dispatch(machine)
                } catch (t: Throwable) {
                    0 to false
                }
                woken.addAndGet(count.toLong())
                if (remaining) keep.add(machine)
            }
            if (keep.isNotEmpty()) {
                lock.withLockValue {
                    keep.addAll(pending)
                    pending = keep
                }
            }
        }
    }
}
