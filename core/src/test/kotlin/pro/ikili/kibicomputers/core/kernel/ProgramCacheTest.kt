package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.process.ProgramLoadBusyException
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 読み込んだ実行ファイルの共有（#37） */
class ProgramCacheTest {
    private val runtime = EndiveRuntime()
    private val direct = Executor { it.run() }

    private fun bytes(n: Int): ByteArray = "program-$n".toByteArray()

    /** キーは [bytes] で分け、読み込むのは毎回同じ小さなモジュール（呼ぶたびに別の [Program] になる） */
    @Suppress("UNUSED_PARAMETER")
    private fun loader(b: ByteArray): Program = Program.load(runtime, TestModules.ADD)

    @Test
    fun `同じ中身と読み込み方なら1回だけ読み込んで同じものを返す`() {
        val cache = ProgramCache(4, Long.MAX_VALUE, direct)
        val a = cache.load(1, bytes(1), true, setOf("w.wait"), ::loader).get()
        val b = cache.load(1, bytes(1), true, setOf("w.wait"), ::loader).get()
        assertSame(a, b)
        assertEquals(1, cache.loads.get())
        // 読み込み方が違えば別に読み込む
        assertNotSame(a, cache.load(1, bytes(1), false, setOf("w.wait"), ::loader).get())
        assertNotSame(a, cache.load(1, bytes(1), true, emptySet(), ::loader).get())
        assertNotSame(a, cache.load(1, bytes(2), true, setOf("w.wait"), ::loader).get())
        assertEquals(4, cache.loads.get())
    }

    @Test
    fun `同時に頼まれても1回だけ読み込んで待ち合わせる`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(4, Long.MAX_VALUE, pool)
            val gate = CountDownLatch(1)
            val slow = { b: ByteArray -> gate.await(); loader(b) }
            val futures = (0 until 8).map { cache.load(1, bytes(1), true, emptySet(), slow) }
            assertTrue(futures.none { it.isDone })
            gate.countDown()
            val programs = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(programs.all { it === programs[0] })
            assertEquals(1, cache.loads.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `WASMとして読めなかったものはしばらく覚えて読み込まずに失敗を返す`() {
        var now = 0L
        val cache = ProgramCache(4, Long.MAX_VALUE, direct, failureTtlNanos = 1_000, nanoTime = { now })
        var fail = true
        val flaky = { b: ByteArray -> if (fail) throw WasmLoadException("だめ") else loader(b) }
        val e = assertThrows<ExecutionException> { cache.load(1, bytes(1), true, emptySet(), flaky).get() }
        assertTrue(e.cause is WasmLoadException)
        fail = false
        // 期限の間は、別の台から頼まれても読み込まない
        val again = assertThrows<ExecutionException> { cache.load(2, bytes(1), true, emptySet(), flaky).get() }
        assertSame(e.cause, again.cause)
        assertEquals(1, cache.loads.get())
        // 読み込み方が違えば別のもの
        cache.load(1, bytes(1), false, emptySet(), flaky).get()
        assertEquals(2, cache.loads.get())
        // 期限が過ぎたら読み込み直す
        now = 1_000
        cache.load(1, bytes(1), true, emptySet(), flaky).get()
        assertEquals(3, cache.loads.get())
    }

    @Test
    fun `WASMの失敗でないものは覚えない`() {
        val cache = ProgramCache(4, Long.MAX_VALUE, direct)
        var fail = true
        val flaky = { b: ByteArray -> if (fail) throw IllegalStateException("一時的") else loader(b) }
        assertThrows<ExecutionException> { cache.load(1, bytes(1), true, emptySet(), flaky).get() }
        fail = false
        cache.load(1, bytes(1), true, emptySet(), flaky).get()
        assertEquals(2, cache.loads.get())
    }

    @Test
    fun `1台が大量に積んでも別の台の読み込みが先に進む`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(100, Long.MAX_VALUE, pool, maxPendingPerOwner = 100)
            val gate = CountDownLatch(1)
            val order = Collections.synchronizedList(ArrayList<Int>())
            val tracking = { n: Int -> { b: ByteArray -> gate.await(); order += n; loader(b) } }
            // 台 1 が 20 個積む（最初の 1 つは読み込み始めて止まる）
            val heavy = (0 until 20).map { cache.load(1, bytes(it), true, emptySet(), tracking(it)) }
            // 後から台 2・台 3 が 1 つずつ
            val light2 = cache.load(2, bytes(100), true, emptySet(), tracking(100))
            val light3 = cache.load(3, bytes(200), true, emptySet(), tracking(200))
            gate.countDown()
            light2.get(10, TimeUnit.SECONDS)
            light3.get(10, TimeUnit.SECONDS)
            heavy.forEach { it.get(10, TimeUnit.SECONDS) }
            // 台 1 の読み込み中のもの（0）の次は、台 1 の残りより先に台 2・台 3
            assertEquals(0, order[0])
            assertEquals(setOf(100, 200), order.subList(1, 4).filter { it >= 100 }.toSet(), order.toString())
            assertTrue(order.indexOf(200) <= 3, order.toString())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `台ごとの読み込みの数が上限に達したら受け付けない`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(100, Long.MAX_VALUE, pool, maxPendingPerOwner = 2)
            val gate = CountDownLatch(1)
            val slow = { b: ByteArray -> gate.await(); loader(b) }
            val a = cache.load(1, bytes(1), true, emptySet(), slow)
            val b = cache.load(1, bytes(2), true, emptySet(), slow)
            val e = assertThrows<ExecutionException> { cache.load(1, bytes(3), true, emptySet(), slow).get() }
            assertTrue(e.cause is ProgramLoadBusyException)
            // 別の台は受け付ける。台 1 の読み込みに相乗りするのは数えない
            val other = cache.load(2, bytes(3), true, emptySet(), slow)
            val shared = cache.load(1, bytes(3), true, emptySet(), slow)
            gate.countDown()
            listOf(a, b, other, shared).forEach { it.get(10, TimeUnit.SECONDS) }
            assertSame(other.get(), shared.get())
            // 終われば、また受け付ける
            cache.load(1, bytes(4), true, emptySet(), slow).get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `待つ側がいなくなった読み込みは始まる前なら取り消す`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(100, Long.MAX_VALUE, pool, maxPendingPerOwner = 2)
            val gate = CountDownLatch(1)
            val started = Collections.synchronizedList(ArrayList<Int>())
            val tracking = { n: Int -> { b: ByteArray -> started += n; gate.await(); loader(b) } }
            val running = cache.load(1, bytes(1), true, emptySet(), tracking(1))
            eventually { started.isNotEmpty() }
            // 2 つが待つ。片方がやめても、もう片方が待つ間は取り消さない
            val queuedA = cache.load(1, bytes(2), true, emptySet(), tracking(2))
            val queuedB = cache.load(2, bytes(2), true, emptySet(), tracking(2))
            val lone = cache.load(2, bytes(3), true, emptySet(), tracking(3))
            queuedA.cancel(false)
            lone.cancel(false)
            assertEquals(2, cache.loadingCount)
            // 始まっていない読み込みを取り消せば、台の数からも外れる
            queuedB.cancel(false)
            assertEquals(1, cache.loadingCount)
            cache.load(1, bytes(4), true, emptySet(), tracking(4))
            // 読み込み中のものは、待つ側がやめても最後まで読み込んで共有する
            running.cancel(false)
            gate.countDown()
            eventually { cache.loadingCount == 0 }
            assertEquals(listOf(1, 4), started.toList())
            assertEquals(2, cache.loads.get())
            assertEquals(2, cache.retainedCount)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `上限を超えたら古いものから外すが、使われているものは共有し続ける`() {
        val cache = ProgramCache(2, Long.MAX_VALUE, direct)
        val first = cache.load(1, bytes(1), true, emptySet(), ::loader).get()
        cache.load(1, bytes(2), true, emptySet(), ::loader).get()
        cache.load(1, bytes(3), true, emptySet(), ::loader).get()
        assertEquals(2, cache.retainedCount)
        // 1 は強い参照から外れたが、ここで使っているので同じものが返る（読み込み直さない）
        assertSame(first, cache.load(1, bytes(1), true, emptySet(), ::loader).get())
        assertEquals(3, cache.loads.get())
        assertEquals(2, cache.retainedCount)
    }

    @Test
    fun `重さの合計の上限を超えたら古いものから外す`() {
        val size = bytes(1).size.toLong()
        val cache = ProgramCache(100, size * 2, direct)
        (1..5).forEach { cache.load(1, bytes(it), true, emptySet(), ::loader).get() }
        assertEquals(2, cache.retainedCount)
        // 1 つで上限を超えるものも、最新の 1 つは持つ
        val tiny = ProgramCache(100, 1, direct)
        tiny.load(1, bytes(1), true, emptySet(), ::loader).get()
        assertEquals(1, tiny.retainedCount)
    }

    @Test
    fun `どこからも使われなくなったものは回収される`() {
        val cache = ProgramCache(1, Long.MAX_VALUE, direct)
        cache.load(1, bytes(1), true, emptySet(), ::loader).get()
        cache.releaseAll()
        // GC で弱い参照が消えれば、次は読み込み直す
        eventually(10_000, "回収されない") {
            System.gc()
            cache.load(1, bytes(1), true, emptySet(), ::loader).get()
            cache.releaseAll()
            cache.loads.get() > 1
        }
    }

    @Test
    fun `読み込むスレッドが止まっていたら失敗する`() {
        val pool = Executors.newSingleThreadExecutor().also { it.shutdown() }
        val cache = ProgramCache(1, Long.MAX_VALUE, pool)
        val e = assertThrows<ExecutionException> { cache.load(1, bytes(1), true, emptySet(), ::loader).get() }
        assertTrue(e.cause is IllegalStateException)
    }
}
