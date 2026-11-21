package pro.ikili.kibicomputers.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class GameTestFileLockTest {
    @TempDir
    lateinit var dir: Path

    private fun newLock() = GameTestFileLock(dir.resolve("locks/gametest.lock"), dir.resolve("locks/gametest.owner"))

    @Test
    fun `takes a free lock at once and writes the owner`() {
        val lock = newLock()
        lock.acquire("task A", Duration.ofSeconds(5), {})
        assertTrue(lock.isHeld)
        assertEquals("task A", lock.readOwner())
        assertNotNull(lock.release())
        assertFalse(lock.isHeld)
        // 外したら持ち主のファイルも消す
        assertEquals("unknown", newLock().readOwner())
        // 2 回外しても何も起きない
        assertNull(lock.release())
    }

    @Test
    fun `a second holder in the same JVM waits and reports the owner`() {
        val first = newLock()
        first.acquire("task A", Duration.ofSeconds(5), {})
        val second = newLock()
        val messages = mutableListOf<String>()
        val e = assertThrows<IOException> {
            second.acquire("task B", Duration.ofMillis(300), { messages += it }, Duration.ofMillis(50), Duration.ofSeconds(30))
        }
        assertTrue(e.message!!.contains("Held by: task A"), e.message)
        assertTrue(messages.single().contains("Held by: task A"), messages.toString())
        assertFalse(second.isHeld)
        first.release()
    }

    @Test
    fun `a waiter takes the lock once the holder releases it`() {
        val first = newLock()
        first.acquire("task A", Duration.ofSeconds(5), {})
        // 待つ側が 1 回取れずに報告したのを見てから外す（時間には頼らない）
        val waiting = CountDownLatch(1)
        val releaser = thread {
            assertTrue(waiting.await(30, TimeUnit.SECONDS))
            first.release()
        }
        val second = newLock()
        val messages = CopyOnWriteArrayList<String>()
        second.acquire("task B", Duration.ofSeconds(60), { messages += it; waiting.countDown() }, Duration.ofMillis(50))
        releaser.join()
        assertTrue(second.isHeld)
        assertTrue(messages.first().contains("Held by: task A"), messages.toString())
        assertEquals("task B", second.readOwner())
        second.release()
    }

    @Test
    fun `handing over keeps the lock, rewrites the owner and restarts the held time`() {
        val clock = AtomicLong(1_000)
        val lock = GameTestFileLock(dir.resolve("locks/gametest.lock"), dir.resolve("locks/gametest.owner")) { clock.get() }
        lock.acquire("task A", Duration.ofSeconds(5), {})
        clock.addAndGet(Duration.ofMillis(300).toNanos())
        val heldByA = lock.handOver("task B")
        assertEquals(Duration.ofMillis(300), heldByA)
        assertTrue(lock.isHeld)
        assertEquals("task B", lock.readOwner())
        // ほかからは取れないまま
        assertFalse(newLock().tryAcquire())
        clock.addAndGet(Duration.ofMillis(50).toNanos())
        assertEquals(Duration.ofMillis(50), lock.release(), "the held time should be counted from the handover")
    }

    @Test
    fun `a lock held by another process blocks, and is freed when that process dies`() {
        val lockFile = dir.resolve("locks/gametest.lock")
        val ready = dir.resolve("ready")
        val java = ProcessHandle.current().info().command().orElseThrow()
        val child = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            HoldLockMain::class.java.name, lockFile.toString(), dir.resolve("locks/gametest.owner").toString(), ready.toString(),
        ).inheritIO().start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!Files.exists(ready)) {
                assertTrue(child.isAlive, "the child process exited before taking the lock")
                assertTrue(System.nanoTime() < deadline, "the child process did not take the lock")
                Thread.sleep(50)
            }
            val lock = newLock()
            assertFalse(lock.tryAcquire())
            assertEquals("child", lock.readOwner())
            // 異常終了（外す処理を通らない）でも OS が外す
            child.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
            lock.acquire("task B", Duration.ofSeconds(10), {}, Duration.ofMillis(50))
            assertTrue(lock.isHeld)
            lock.release()
        } finally {
            child.destroyForcibly()
        }
    }
}

/**
 * 別のプロセスでロックを取り、殺されるまで持ち続ける（[GameTestFileLockTest] から起動する）。
 * 親（テストのワーカー）が finally を通らずに終わっても残らないよう、親が終わったら自分も終わり、親がいなくても [MAX_LIFETIME] で終わる
 * （残ると、コマンドラインに worktree のパスを含む java のプロセスとして、worktree の片付けを止める）。
 */
object HoldLockMain {
    private val MAX_LIFETIME = Duration.ofMinutes(5)

    @JvmStatic
    fun main(args: Array<String>) {
        ProcessHandle.current().parent().ifPresent { parent -> parent.onExit().thenRun { Runtime.getRuntime().halt(0) } }
        val lock = GameTestFileLock(Path.of(args[0]), Path.of(args[1]))
        lock.acquire("child", Duration.ofSeconds(30), {})
        Files.writeString(Path.of(args[2]), "ok")
        Thread.sleep(MAX_LIFETIME.toMillis())
        Runtime.getRuntime().halt(0)
    }
}
