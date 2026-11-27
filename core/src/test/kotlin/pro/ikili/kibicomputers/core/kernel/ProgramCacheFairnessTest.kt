package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.process.ProgramLoadBusyException
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 実行ファイルの読み込みの待ち行列を、公平の組（持ち主）→ マシンの二段で回し、組ごとの上限をかける（#738） */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
class ProgramCacheFairnessTest {
    private val runtime = EndiveRuntime()
    private val a = FairShareGroup.of(UUID(0, 1))
    private val b = FairShareGroup.of(UUID(0, 2))

    private fun bytes(n: Int): ByteArray = "program-$n".toByteArray()

    @Suppress("UNUSED_PARAMETER")
    private fun loader(b: ByteArray): Program = Program.load(runtime, TestModules.ADD)

    private fun ProgramCache.load(owner: Long, group: FairShareGroup, n: Int, loader: (ByteArray) -> Program, limitGroup: Boolean = true): CompletableFuture<Program> =
        load(owner, group, limitGroup, bytes(n), true, emptySet(), loader)

    @Test
    fun `1人が多くの台で積んでも、別の持ち主の読み込みは1件ごとに順番が回ってくる`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(100, Long.MAX_VALUE, pool, maxPendingPerOwner = 100)
            val gate = CountDownLatch(1)
            val order = Collections.synchronizedList(ArrayList<Int>())
            val started = CountDownLatch(1)
            val tracking = { n: Int -> { by: ByteArray -> started.countDown(); gate.await(); order += n; loader(by) } }
            // 持ち主 a の台 1 が読み込み始めて止まってから、a の台 2〜10 が 1 つずつ、後から b の台が 1 つ。
            // マシンの巡回だけなら b は最後になる
            val first = cache.load(1, a, 1, tracking(1))
            started.awaitOrFail()
            val heavy = (2..10).map { cache.load(it.toLong(), a, it, tracking(it)) }
            val light = cache.load(100, b, 100, tracking(100))
            gate.countDown()
            light.getOrFail()
            (heavy + first).forEach { it.getOrFail() }
            // 組の巡回で、a の 1 件（先に並んだ）の次に b、その後に a の残り（台の順）
            assertEquals(listOf(1, 2, 100, 3, 4, 5, 6, 7, 8, 9, 10), order.toList())

            assertEquals(0, cache.pendingGroupCount, "数が 0 の組は消える")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `組ごとの上限に達したら、上限を見る読み込みだけ断る`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(100, Long.MAX_VALUE, pool, maxPendingPerOwner = 8, maxPendingPerGroup = 2)
            val gate = CountDownLatch(1)
            val slow = { by: ByteArray -> gate.await(); loader(by) }
            val first = cache.load(1, a, 1, slow)
            val second = cache.load(2, a, 2, slow)
            assertEquals(2, cache.pendingOfGroup(a))
            // 同じ持ち主の別の台でも断る（ゲストの exec は AGAIN になる）
            val e = assertThrows<ExecutionException> { cache.load(3, a, 3, slow).get() }
            assertTrue(e.cause is ProgramLoadBusyException)
            // init・復元（上限を見ない）は断らず、組の数には入れる
            val boot = cache.load(3, a, 4, slow, limitGroup = false)
            assertEquals(3, cache.pendingOfGroup(a))
            // 別の持ち主は受け付ける。相乗りは数えない
            val other = cache.load(4, b, 5, slow)
            val shared = cache.load(3, a, 5, slow)
            assertEquals(1, cache.pendingOfGroup(b))
            assertEquals(3, cache.pendingOfGroup(a))
            gate.countDown()
            listOf(first, second, boot, other, shared).forEach { it.getOrFail() }
            assertEquals(0, cache.pendingOfGroup(a))
            assertEquals(0, cache.pendingGroupCount)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `持ち主の台が待つのをやめたら、相乗りした台の組に移す`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(100, Long.MAX_VALUE, pool, maxPendingPerOwner = 8)
            val gate = CountDownLatch(1)
            val started = Collections.synchronizedList(ArrayList<Int>())
            val tracking = { n: Int -> { by: ByteArray -> started += n; gate.await(); loader(by) } }
            val running = cache.load(9, b, 9, tracking(9))
            eventually { started.isNotEmpty() }
            val queuedA = cache.load(1, a, 1, tracking(1))
            val sharedB = cache.load(2, b, 1, tracking(1))
            assertEquals(1, cache.pendingOfGroup(a))
            assertEquals(1, cache.pendingOfGroup(b))
            queuedA.cancel(false)
            // 始まる前の読み込みは、相乗りした台 2（組 b）に移る
            assertEquals(0, cache.pendingOfGroup(a))
            assertEquals(2, cache.pendingOfGroup(b))
            assertEquals(1, cache.pendingOf(2))
            gate.countDown()
            running.getOrFail()
            sharedB.getOrFail()
            assertEquals(listOf(9, 1), started.toList())
            assertEquals(0, cache.pendingGroupCount)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `台の組が変わっても、前に積んだ読み込みは前の組に数え、前の組に返す`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(100, Long.MAX_VALUE, pool, maxPendingPerOwner = 8)
            val gate = CountDownLatch(1)
            val slow = { by: ByteArray -> gate.await(); loader(by) }
            val running = cache.load(1, a, 1, slow)
            val queuedOld = cache.load(1, a, 2, slow)
            // 持ち主が変わった（#110・#206）。同じ台の次の読み込みは新しい組
            val queuedNew = cache.load(1, b, 3, slow)
            assertEquals(2, cache.pendingOfGroup(a))
            assertEquals(1, cache.pendingOfGroup(b))
            assertEquals(3, cache.pendingOf(1))
            queuedOld.cancel(false)
            assertEquals(1, cache.pendingOfGroup(a))
            gate.countDown()
            running.getOrFail()
            queuedNew.getOrFail()
            assertEquals(0, cache.pendingOf(1))
            assertEquals(0, cache.pendingGroupCount)
            assertEquals(0, cache.loadingCount)
        } finally {
            pool.shutdownNow()
        }
    }
}
