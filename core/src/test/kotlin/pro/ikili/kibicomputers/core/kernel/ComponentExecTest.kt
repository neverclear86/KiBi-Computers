package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** コンポーネント（layer 1）を exec したときの振る舞い（#262。exec につなぐのは #687） */
class ComponentExecTest {
    private val limit = Duration.ofSeconds(60)

    @Test
    fun `コンポーネントをexecするとNOEXEC`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine()
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = WitTool.wat("(component (core module \$m) (core instance (instantiate \$m)))")
            val f = m.vfs.open(VfsPath.parse("/bin/comp"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
            val e = assertThrows<FsException> { m.exec("/bin/comp", KernelDrivers.DEFAULT) }
            assertEquals(Errno.NOEXEC, e.errno)
            assertTrue(e.message!!.contains("コンポーネント")) { e.message }
            // 読み込みの失敗は覚えられるので、もう一度でも同じ
            assertEquals(Errno.NOEXEC, assertThrows<FsException> { m.exec("/bin/comp", KernelDrivers.DEFAULT) }.errno)
        }
    }
}
