package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
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
        val a = cache.load(bytes(1), true, setOf("w.wait"), ::loader).get()
        val b = cache.load(bytes(1), true, setOf("w.wait"), ::loader).get()
        assertSame(a, b)
        assertEquals(1, cache.loads.get())
        // 読み込み方が違えば別に読み込む
        assertNotSame(a, cache.load(bytes(1), false, setOf("w.wait"), ::loader).get())
        assertNotSame(a, cache.load(bytes(1), true, emptySet(), ::loader).get())
        assertNotSame(a, cache.load(bytes(2), true, setOf("w.wait"), ::loader).get())
        assertEquals(4, cache.loads.get())
    }

    @Test
    fun `同時に頼まれても1回だけ読み込んで待ち合わせる`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cache = ProgramCache(4, Long.MAX_VALUE, pool)
            val gate = CountDownLatch(1)
            val slow = { b: ByteArray -> gate.await(); loader(b) }
            val futures = (0 until 8).map { cache.load(bytes(1), true, emptySet(), slow) }
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
    fun `失敗した読み込みは覚えない`() {
        val cache = ProgramCache(4, Long.MAX_VALUE, direct)
        var fail = true
        val flaky = { b: ByteArray -> if (fail) throw WasmLoadException("だめ") else loader(b) }
        val e = assertThrows<ExecutionException> { cache.load(bytes(1), true, emptySet(), flaky).get() }
        assertTrue(e.cause is WasmLoadException)
        fail = false
        cache.load(bytes(1), true, emptySet(), flaky).get()
        assertEquals(2, cache.loads.get())
    }

    @Test
    fun `上限を超えたら古いものから外すが、使われているものは共有し続ける`() {
        val cache = ProgramCache(2, Long.MAX_VALUE, direct)
        val first = cache.load(bytes(1), true, emptySet(), ::loader).get()
        cache.load(bytes(2), true, emptySet(), ::loader).get()
        cache.load(bytes(3), true, emptySet(), ::loader).get()
        assertEquals(2, cache.retainedCount)
        // 1 は強い参照から外れたが、ここで使っているので同じものが返る（読み込み直さない）
        assertSame(first, cache.load(bytes(1), true, emptySet(), ::loader).get())
        assertEquals(3, cache.loads.get())
        assertEquals(2, cache.retainedCount)
    }

    @Test
    fun `重さの合計の上限を超えたら古いものから外す`() {
        val size = bytes(1).size.toLong()
        val cache = ProgramCache(100, size * 2, direct)
        (1..5).forEach { cache.load(bytes(it), true, emptySet(), ::loader).get() }
        assertEquals(2, cache.retainedCount)
        // 1 つで上限を超えるものも、最新の 1 つは持つ
        val tiny = ProgramCache(100, 1, direct)
        tiny.load(bytes(1), true, emptySet(), ::loader).get()
        assertEquals(1, tiny.retainedCount)
    }

    @Test
    fun `どこからも使われなくなったものは回収される`() {
        val cache = ProgramCache(1, Long.MAX_VALUE, direct)
        cache.load(bytes(1), true, emptySet(), ::loader).get()
        cache.releaseAll()
        // GC で弱い参照が消えれば、次は読み込み直す
        eventually(10_000, "回収されない") {
            System.gc()
            cache.load(bytes(1), true, emptySet(), ::loader).get()
            cache.releaseAll()
            cache.loads.get() > 1
        }
    }

    @Test
    fun `読み込むスレッドが止まっていたら失敗する`() {
        val pool = Executors.newSingleThreadExecutor().also { it.shutdown() }
        val cache = ProgramCache(1, Long.MAX_VALUE, pool)
        val e = assertThrows<ExecutionException> { cache.load(bytes(1), true, emptySet(), ::loader).get() }
        assertTrue(e.cause is IllegalStateException)
    }
}
