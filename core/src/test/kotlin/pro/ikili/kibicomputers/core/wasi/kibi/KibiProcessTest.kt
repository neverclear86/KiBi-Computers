package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException

/** `kibi:process` の、p1・p2・p3 で共通の変換（[KibiProcess]。#74） */
class KibiProcessTest {
    private fun request(
        args: List<String> = listOf("kp"),
        env: List<Pair<String, String>>? = null,
        cwd: String? = null,
        fds: List<KibiProcess.FdAssign> = emptyList(),
    ) = KibiProcess.Request("/bin/kp", args, env, cwd, fds)

    private fun errno(block: () -> Unit): Errno = assertThrows<FsException> { block() }.errno

    @Test
    fun `書かなければ標準入出力は引き継ぎ、envとcwdはnull`() {
        val o = KibiProcess.options(request())
        assertEquals(StdioSpec(StdioSource.Inherit, StdioSource.Inherit, StdioSource.Inherit), o.stdio)
        assertNull(o.env)
        assertNull(o.cwd)
        assertEquals(emptyMap<Int, StdioSource>(), o.fds)
        assertEquals(listOf("kp"), o.args)
    }

    @Test
    fun `fdsは0から2を標準入出力に、3以降をfdsに入れ、closedは割り当てない`() {
        val o = KibiProcess.options(
            request(
                fds = listOf(
                    KibiProcess.FdAssign(1, 5),
                    KibiProcess.FdAssign(2, null),
                    KibiProcess.FdAssign(7, 3),
                    KibiProcess.FdAssign(4, null),
                ),
            ),
        )
        assertEquals(StdioSpec(StdioSource.Inherit, StdioSource.ParentFd(5), StdioSource.Closed), o.stdio)
        assertEquals(mapOf(7 to StdioSource.ParentFd(3), 4 to StdioSource.Closed), o.fds)
    }

    @Test
    fun `envは並びを保ち、同じ名前は後のものを使う`() {
        val o = KibiProcess.options(request(env = listOf("B" to "1", "A" to "2", "B" to "3")))
        assertEquals(listOf("B" to "3", "A" to "2"), o.env!!.toList())
        assertEquals(emptyMap<String, String>(), KibiProcess.options(request(env = emptyList())).env)
    }

    @Test
    fun `fdsの重なり・範囲外、envの名前の誤り`() {
        assertEquals(Errno.INVAL, errno { KibiProcess.options(request(fds = listOf(KibiProcess.FdAssign(3, null), KibiProcess.FdAssign(3, 1)))) })
        assertEquals(Errno.BADF, errno { KibiProcess.options(request(fds = listOf(KibiProcess.FdAssign(1L shl 31, null)))) })
        assertEquals(Errno.BADF, errno { KibiProcess.options(request(fds = listOf(KibiProcess.FdAssign(1, 0xffff_ffffL)))) })
        assertEquals(Errno.INVAL, errno { KibiProcess.options(request(env = listOf("A=B" to "c"))) })
        assertEquals(Errno.INVAL, errno { KibiProcess.options(request(env = listOf("" to "c"))) })
    }

    @Test
    fun `signalの判別子とexit-statusの判別子`() {
        assertEquals(listOf(KillSignal.INTERRUPT, KillSignal.QUIT, KillSignal.TERMINATE, KillSignal.KILL), (0..3).map { KibiProcess.signalOf(it) })
        assertEquals(Errno.INVAL, errno { KibiProcess.signalOf(4) })
        assertEquals(Errno.INVAL, errno { KibiProcess.signalOf(-1) })
        assertEquals(0, KibiProcess.statusTag(ExitStatus(1, ExitReason.EXITED, 3, null)))
        assertEquals(1, KibiProcess.statusTag(ExitStatus(1, ExitReason.KILLED, 0, KillSignal.KILL)))
        assertEquals(2, KibiProcess.statusTag(ExitStatus(1, ExitReason.CRASHED, 0, null)))
    }

    @Test
    fun `killの結果は、送った・無視された・終わっていたなら成功`() {
        assertNull(KibiProcess.killError(KillResult.DELIVERED))
        assertNull(KibiProcess.killError(KillResult.IGNORED))
        assertNull(KibiProcess.killError(KillResult.ALREADY_EXITED))
        assertEquals(Errno.SRCH, KibiProcess.killError(KillResult.NOT_FOUND))
        assertEquals(Errno.PERM, KibiProcess.killError(KillResult.NOT_PERMITTED))
    }
}
