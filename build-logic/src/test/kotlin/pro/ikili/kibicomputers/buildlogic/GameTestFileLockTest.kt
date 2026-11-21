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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GameTestFileLockTest {
    @TempDir
    lateinit var dir: Path

    private fun newLock() = GameTestFileLock(dir.resolve("locks/gametest.lock"), dir.resolve("locks/gametest.owner"))

    @Test
    fun `takes a free lock at once and writes the owner`() {
        val lock = newLock()
        val waited = lock.acquire("task A", Duration.ofSeconds(5), {})
        assertTrue(lock.isHeld)
        assertTrue(waited < Duration.ofSeconds(2))
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
        val releaser = thread {
            Thread.sleep(300)
            first.release()
        }
        val second = newLock()
        val waited = second.acquire("task B", Duration.ofSeconds(10), {}, Duration.ofMillis(50))
        releaser.join()
        assertTrue(second.isHeld)
        assertTrue(waited >= Duration.ofMillis(200), waited.toString())
        assertEquals("task B", second.readOwner())
        second.release()
    }

    @Test
    fun `handing over keeps the lock, rewrites the owner and restarts the held time`() {
        val lock = newLock()
        lock.acquire("task A", Duration.ofSeconds(5), {})
        Thread.sleep(300)
        val heldByA = lock.handOver("task B")
        assertTrue(heldByA >= Duration.ofMillis(300), heldByA.toString())
        assertTrue(lock.isHeld)
        assertEquals("task B", lock.readOwner())
        // ほかからは取れないまま
        assertFalse(newLock().tryAcquire())
        val heldByB = lock.release()!!
        assertTrue(heldByB < heldByA, "$heldByB should be counted from the handover")
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

/** 別のプロセスでロックを取り、殺されるまで持ち続ける（[GameTestFileLockTest] から起動する）。 */
object HoldLockMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = GameTestFileLock(Path.of(args[0]), Path.of(args[1]))
        lock.acquire("child", Duration.ofSeconds(30), {})
        Files.writeString(Path.of(args[2]), "ok")
        Thread.sleep(Long.MAX_VALUE)
    }
}
