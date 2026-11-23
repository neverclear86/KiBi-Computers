package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [SoePositions.classify] が、ロックの機構のフレームの下を見て、ロックの途中を [SoePositions.Position.LOCK] に数えることの確認（#858） */
class SoePositionsTest {
    private fun trace(vararg frames: String): Array<StackTraceElement> =
        frames.map { StackTraceElement(it.substringBeforeLast('.'), it.substringAfterLast('.'), null, -1) }.toTypedArray()

    private val machine = "pro.ikili.kibicomputers.core.kernel.Machine.withLockValue"

    @Test
    fun `一番上がThreadのcurrentThreadでも、ReentrantLockの下がこのMODならLOCKに数える`() {
        val t = trace(
            "java.lang.Thread.currentThread",
            "java.util.concurrent.locks.ReentrantLock\$Sync.tryRelease",
            "java.util.concurrent.locks.ReentrantLock.unlock",
            machine,
        )
        assertEquals(SoePositions.Position.LOCK, SoePositions.classify(t))
    }

    @Test
    fun `一番上がUnsafeのCASでも、ロックの下がこのMODならLOCKに数える`() {
        val t = trace(
            "jdk.internal.misc.Unsafe.compareAndSetInt",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer.compareAndSetState",
            "java.util.concurrent.locks.ReentrantLock\$Sync.initialTryLock",
            "java.util.concurrent.locks.ReentrantLock.lock",
            machine,
        )
        assertEquals(SoePositions.Position.LOCK, SoePositions.classify(t))
    }

    @Test
    fun `ロックの下がNIOならJDK_LOCKに数える`() {
        val t = trace(
            "java.lang.Thread.currentThread",
            "java.util.concurrent.locks.ReentrantLock.lock",
            "sun.nio.ch.FileChannelImpl.read",
            "pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem.read",
        )
        assertEquals(SoePositions.Position.JDK_LOCK, SoePositions.classify(t))
    }

    @Test
    fun `ロックの機構のないThreadのフレームは、本体の区画に数える`() {
        val t = trace("java.lang.Thread.currentThread", "pro.ikili.kibicomputers.core.kernel.vfs.Vfs.stat")
        assertEquals(SoePositions.Position.VFS, SoePositions.classify(t))
    }
}
