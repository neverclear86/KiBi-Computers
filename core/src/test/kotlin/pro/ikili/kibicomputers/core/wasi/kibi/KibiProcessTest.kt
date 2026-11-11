package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessListing
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.wasi.kibi.KibiProcess.ListState
import pro.ikili.kibicomputers.core.wasi.p1.P1Canonical

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

    // ---- list-processes（#452、ADR 0452） ----

    @Test
    fun `カーネルの10状態をprocess-stateの4つに写す`() {
        val expected = mapOf(
            ProcessState.CREATED to ListState.STARTING,
            ProcessState.LOADING to ListState.STARTING,
            ProcessState.RUNNABLE to ListState.RUNNING,
            ProcessState.RUNNING to ListState.RUNNING,
            ProcessState.WAITING_FUEL to ListState.RUNNING,
            ProcessState.WAITING_EVENT to ListState.WAITING,
            ProcessState.WAITING_HOST to ListState.WAITING,
            ProcessState.EXITED to ListState.FINISHED,
            ProcessState.CRASHED to ListState.FINISHED,
            ProcessState.KILLED to ListState.FINISHED,
        )
        assertEquals(ProcessState.entries.toSet(), expected.keys)
        for (state in ProcessState.entries) assertEquals(expected[state], KibiProcess.stateOf(state), "$state")
        // 判別子の順（starting 0・running 1・waiting 2・finished 3）
        assertEquals(listOf(ListState.STARTING, ListState.RUNNING, ListState.WAITING, ListState.FINISHED), ListState.entries)
    }

    @Test
    fun `一覧はpidの昇順に並べ、終わったものだけが終わり方を持つ`() {
        val killedNone = ExitStatus(7, ExitReason.KILLED, 0, null)
        val exited = ExitStatus(Int.MAX_VALUE, ExitReason.EXITED, -1, null)
        // 表は割り当ての順（pid が Int.MAX_VALUE から 1 に戻った後）
        val listed = listOf(
            ProcessListing(Int.MAX_VALUE, 0, ProcessState.EXITED, exited, listOf("a")),
            ProcessListing(7, 1, ProcessState.KILLED, killedNone, emptyList()),
            ProcessListing(1, 0, ProcessState.RUNNING, null, listOf("init")),
            ProcessListing(3, 1, ProcessState.WAITING_EVENT, null, listOf("sh", "-c")),
        )
        assertEquals(
            listOf(
                KibiProcess.Entry(1, 0, ListState.RUNNING, null, listOf("init")),
                KibiProcess.Entry(3, 1, ListState.WAITING, null, listOf("sh", "-c")),
                KibiProcess.Entry(7, 1, ListState.FINISHED, killedNone, emptyList()),
                KibiProcess.Entry(Int.MAX_VALUE, 0, ListState.FINISHED, exited, listOf("a")),
            ),
            KibiProcess.entries(listed),
        )
        assertEquals(emptyList<KibiProcess.Entry>(), KibiProcess.entries(emptyList()))
    }

    private fun truncate(vararg args: String) = KibiProcess.truncateArgs(args.toList())

    private fun bytesOf(args: List<String>) = args.sumOf { P1Canonical.utf8(it).size }

    private val emoji = String(Character.toChars(0x1F600))

    @Test
    fun `argsは16個まで`() {
        assertEquals(emptyList<String>(), truncate())
        val many = (0 until 20).map { "$it" }
        assertEquals(many.take(16), KibiProcess.truncateArgs(many))
        assertEquals(List(16) { "" }, KibiProcess.truncateArgs(List(20) { "" }))
    }

    @Test
    fun `argsは合計255バイトまで、収まる要素は丸ごと入れ、空文字列は残りが0でも入る`() {
        val a255 = "a".repeat(255)
        assertEquals(listOf(a255), truncate(a255))
        assertEquals(listOf(a255, "", ""), truncate(a255, "", ""))
        // 残りが 0 で、切ると空になる要素は入れずに止める（後の空文字列も入れない）
        assertEquals(listOf(a255), truncate(a255, "b", ""))
        assertEquals(listOf("a".repeat(250), "bbbbb"), truncate("a".repeat(250), "bbbbbbb", "c"))
        assertEquals(listOf(a255), truncate("a".repeat(300)))
    }

    @Test
    fun `収まらない要素はUTF-8の文字の境目で切る`() {
        // 2 バイト
        assertEquals(listOf("a".repeat(253), "é"), truncate("a".repeat(253), "é"))
        assertEquals(listOf("a".repeat(254)), truncate("a".repeat(254), "é", "x"))
        // 3 バイト（255 = 3 × 85）
        assertEquals(listOf("あ".repeat(85)), truncate("あ".repeat(100)))
        assertEquals(listOf("a", "あ".repeat(84)), truncate("a", "あ".repeat(100)))
        // 4 バイト（サロゲートペアを割らない）
        assertEquals(listOf(emoji.repeat(63)), truncate(emoji.repeat(70)))
        assertEquals(listOf("a".repeat(253)), truncate("a".repeat(253), emoji))
        assertEquals(listOf("a".repeat(254)), truncate("a".repeat(254) + emoji + "b"))
        assertEquals(listOf("a".repeat(255)), truncate("a".repeat(255) + emoji))
    }

    @Test
    fun `対になっていないサロゲートはU+FFFDの3バイトと数える`() {
        val high = 0xD800.toChar().toString()
        val low = 0xDC00.toChar().toString()
        val replacement = 0xFFFD.toChar().toString()
        assertEquals(listOf(replacement + "x"), truncate(high + "x"))
        assertEquals(listOf("a".repeat(252), replacement), truncate("a".repeat(252), low))
        assertEquals(listOf("a".repeat(253)), truncate("a".repeat(253), low))
    }

    @Test
    fun `切った結果は上限に収まり、p1のUTF-8にすると元の先頭と同じバイト列`() {
        val high = 0xD800.toChar().toString()
        val inputs = listOf(
            listOf("x".repeat(100), "あ".repeat(100), emoji.repeat(100)),
            List(30) { "é".repeat(it) },
            listOf(high.repeat(200)),
            listOf("a".repeat(10), high + emoji + "b".repeat(300)),
        )
        for (args in inputs) {
            val out = KibiProcess.truncateArgs(args)
            assertTrue(out.size <= KibiProcess.MAX_LIST_ARGS)
            assertTrue(bytesOf(out) <= KibiProcess.MAX_LIST_ARG_BYTES, "${bytesOf(out)}")
            // 最後の要素以外は元のまま（UTF-8 にした値として）
            for ((i, s) in out.dropLast(1).withIndex()) assertArrayEquals(P1Canonical.utf8(args[i]), P1Canonical.utf8(s))
            // 最後の要素は元の UTF-8 の先頭
            if (out.isNotEmpty()) {
                val orig = P1Canonical.utf8(args[out.lastIndex])
                val cut = P1Canonical.utf8(out.last())
                assertArrayEquals(orig.copyOf(cut.size), cut)
            }
        }
    }
}
