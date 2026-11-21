package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

class GameTestLockCoordinatorTest {
    @TempDir
    lateinit var dir: Path

    private val messages = mutableListOf<String>()

    private fun newFileLock() = GameTestFileLock(dir.resolve("locks/gametest.lock"), dir.resolve("locks/gametest.owner"))

    private fun coordinator(
        lock: GameTestFileLock = newFileLock(),
        enabled: Boolean = true,
        timeout: Duration = Duration.ofSeconds(30),
    ) = GameTestLockCoordinator(lock, enabled, timeout, { messages += it }, Duration.ofMillis(20), Duration.ofSeconds(30), 1234)

    @Test
    fun `the finish notice of the holder releases the lock`() {
        val lock = newFileLock()
        val c = coordinator(lock)
        c.acquire(":a:runGameTestServer", "/root")
        assertTrue(lock.isHeld)
        assertEquals(":a:runGameTestServer", c.currentHolder)
        assertTrue(lock.readOwner().startsWith(":a:runGameTestServer in /root (Gradle pid 1234"), lock.readOwner())

        c.onTaskFinished(":a:runGameTestServer")
        assertFalse(lock.isHeld)
        assertNull(c.currentHolder)
        assertTrue(
            messages.any { it.startsWith("GameTest lock released by :a:runGameTestServer after holding it for") },
            messages.toString(),
        )
        // 別のインスタンスが取れる
        val other = newFileLock()
        assertTrue(other.tryAcquire())
        other.release()
    }

    @Test
    fun `a finish notice of an unrelated task does not release the lock`() {
        val lock = newFileLock()
        val c = coordinator(lock)
        c.acquire(":a:runGameTestServer", "/root")
        c.onTaskFinished(":a:build")
        assertTrue(lock.isHeld)
        assertEquals(":a:runGameTestServer", c.currentHolder)
        c.close()
    }

    @Test
    fun `the next task takes over before the finish notice, and the late notice of the previous task is ignored`() {
        val lock = newFileLock()
        val c = coordinator(lock)
        c.acquire(":a:runGameTestServer", "/root")
        // 前のタスクの終わりの通知より先に、次のタスクが acquire を呼ぶ
        c.acquire(":b:runGameTestServer", "/root")
        assertTrue(lock.isHeld)
        assertEquals(":b:runGameTestServer", c.currentHolder)
        assertTrue(lock.readOwner().startsWith(":b:runGameTestServer in /root"), lock.readOwner())
        assertTrue(
            messages.any { it.startsWith("GameTest lock released by :a:runGameTestServer after holding it for") },
            messages.toString(),
        )
        assertTrue(
            messages.any { it.contains("acquired by :b:runGameTestServer") && it.contains("taken over") },
            messages.toString(),
        )
        // ほかからは取れないまま
        assertFalse(newFileLock().tryAcquire())

        // 遅れて届いた前のタスクの通知では外さない
        c.onTaskFinished(":a:runGameTestServer")
        assertTrue(lock.isHeld)
        assertEquals(":b:runGameTestServer", c.currentHolder)
        assertFalse(newFileLock().tryAcquire())

        // 今の持ち主の通知で外れる
        c.onTaskFinished(":b:runGameTestServer")
        assertFalse(lock.isHeld)
        assertNull(c.currentHolder)
    }

    @Test
    fun `a task that finishes before the next acquire releases the lock, and the next one takes it fresh`() {
        val lock = newFileLock()
        val c = coordinator(lock)
        c.acquire(":a:runGameTestServer", "/root")
        c.onTaskFinished(":a:runGameTestServer")
        c.acquire(":b:runGameTestServer", "/root")
        assertTrue(lock.isHeld)
        assertEquals(":b:runGameTestServer", c.currentHolder)
        assertTrue(messages.none { it.contains("taken over") }, messages.toString())
        c.close()
    }

    @Test
    fun `close releases a lock that is still held`() {
        val lock = newFileLock()
        val c = coordinator(lock)
        c.acquire(":a:runGameTestServer", "/root")
        c.close()
        assertFalse(lock.isHeld)
        assertNull(c.currentHolder)
        // 2 回閉じても何も起きない
        c.close()
    }

    @Test
    fun `a disabled lock never waits or takes the lock`() {
        // ほかが持っていても待たない
        val other = newFileLock()
        assertTrue(other.tryAcquire())
        val lock = newFileLock()
        val c = coordinator(lock, enabled = false)
        c.acquire(":a:runGameTestServer", "/root")
        assertFalse(lock.isHeld)
        assertNull(c.currentHolder)
        assertTrue(messages.single().contains("disabled"), messages.toString())
        c.onTaskFinished(":a:runGameTestServer")
        c.close()
        // 持ち主のロックには触れない
        assertTrue(other.isHeld)
        other.release()
    }

    @Test
    fun `waiting past the timeout fails the build with the owner in the message`() {
        val other = newFileLock()
        other.acquire("someone else", Duration.ofSeconds(5), {})
        val c = coordinator(timeout = Duration.ofMillis(200))
        val e = assertThrows<GradleException> { c.acquire(":a:runGameTestServer", "/root") }
        assertTrue(e.message!!.contains("Held by: someone else"), e.message)
        assertTrue(e.message!!.contains("-PgameTestLockTimeoutMinutes"), e.message)
        assertNull(c.currentHolder)
        // 取れなかったので、ここから外さない
        c.onTaskFinished(":a:runGameTestServer")
        c.close()
        assertTrue(other.isHeld)
        other.release()
    }
}
