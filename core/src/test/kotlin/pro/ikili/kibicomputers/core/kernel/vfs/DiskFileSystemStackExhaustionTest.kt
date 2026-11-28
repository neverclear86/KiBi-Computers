package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.SoePositions
import pro.ikili.kibicomputers.core.kernel.awaitAcquirable
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.stackHeadroomRounds
import pro.ikili.kibicomputers.core.kernel.stackHeadroomWait
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ディスク（[DiskFileSystem]）の操作の途中でスタックが尽きても、node の表・使用量・開いたままのファイルの数が、ホストと食い違わないことを確かめる
 * （#858）。
 *
 * `StackExhaustionLockTest` の `DISK_IO` は、ロックの面（マシンのロック・リースのロック・CPU・許可）だけを見る。ホストの関数の本体の中で
 * 尽きると、`rename` が「ホストの移動 → 表の付け替え」の間で、`create` が「ホストのファイルを作る → 使用量を数える」の間で止まりうる。
 * そこで、本物のディスクの 1 つの操作を、スタックの小さいスレッドで、尽きる位置を詰め物の 1 段ずつずらして動かし
 * （尽きる境目を二分探索で探し、そこから先の、操作が使う分の幅をすべて通る）、毎回の後に [DiskFileSystem.consistencyProblemsForTest] で
 * 表・使用量・開いたままのファイルの枠がホストと合うことと、リースのロックが取れることを確かめる。尽きたときも、尽きなかったときも見る
 */
class DiskFileSystemStackExhaustionTest {
    @TempDir
    lateinit var dir: Path

    private enum class Op {
        STAT_DEEP,
        CREATE_FILE,
        CREATE_DIRECTORY,
        WRITE,
        RETAIN,
        RELEASE,
        REMOVE_FILE,
        REMOVE_DIRECTORY,
        RENAME,
        RENAME_REPLACE,
        RENAME_DIRECTORY,
    }

    private val payload = "stack headroom #858".toByteArray()

    private fun p(s: String) = VfsPath.parse(s)

    /** 1 回分の試行の、始める前の状態（浅いスタックで作る）と、尽きる位置をずらして動かす操作 */
    private class Attempt(val run: () -> Unit, val cleanup: List<String>, val node: Long = 0)

    private fun FileSystem.makeFile(path: String, write: Boolean = true): Long {
        val node = createFile(p(path))
        if (write) write(node, 0, payload, 0, payload.size)
        return node
    }

    private fun prepare(fs: DiskFileSystem, op: Op, n: Int): Attempt = when (op) {
        Op.STAT_DEEP -> Attempt({ fs.stat(p(DEEP)) }, emptyList())
        Op.CREATE_FILE -> Attempt({ fs.createFile(p("/w/c$n")) }, listOf("/w/c$n"))
        Op.CREATE_DIRECTORY -> Attempt({ fs.createDirectory(p("/w/c$n")) }, listOf("/w/c$n"))
        Op.WRITE -> {
            val node = fs.makeFile("/w/f$n", write = false)
            Attempt({ fs.write(node, 0, payload, 0, payload.size) }, listOf("/w/f$n"), node)
        }
        Op.RETAIN -> {
            val node = fs.makeFile("/w/f$n")
            Attempt({ fs.retain(node, writable = true) }, listOf("/w/f$n"), node)
        }
        Op.RELEASE -> {
            val node = fs.makeFile("/w/f$n")
            fs.retain(node, writable = true)
            Attempt({ fs.release(node) }, listOf("/w/f$n"), node)
        }
        Op.REMOVE_FILE -> {
            fs.makeFile("/w/f$n")
            Attempt({ fs.removeFile(p("/w/f$n")) }, listOf("/w/f$n"))
        }
        Op.REMOVE_DIRECTORY -> {
            fs.createDirectory(p("/w/d$n"))
            Attempt({ fs.removeDirectory(p("/w/d$n")) }, listOf("/w/d$n"))
        }
        Op.RENAME -> {
            fs.makeFile("/w/f$n")
            Attempt({ fs.rename(p("/w/f$n"), p("/w/g$n"), false) }, listOf("/w/f$n", "/w/g$n"))
        }
        Op.RENAME_REPLACE -> {
            fs.makeFile("/w/f$n")
            fs.makeFile("/w/g$n")
            Attempt({ fs.rename(p("/w/f$n"), p("/w/g$n"), false) }, listOf("/w/f$n", "/w/g$n"))
        }
        Op.RENAME_DIRECTORY -> {
            fs.createDirectory(p("/w/d$n"))
            fs.makeFile("/w/d$n/x")
            fs.makeFile("/w/d$n/y")
            Attempt(
                { fs.rename(p("/w/d$n"), p("/w/e$n"), true) },
                listOf("/w/d$n/x", "/w/d$n/y", "/w/d$n", "/w/e$n/x", "/w/e$n/y", "/w/e$n"),
            )
        }
    }

    /** [paths] を、あるものだけ消す（尽きた後のディスクが、ふつうに使えることも見る） */
    private fun scrub(fs: DiskFileSystem, node: Long, paths: List<String>) {
        // 開いたままのものを閉じる（残っていれば、枠を返す）
        while (node != 0L && fs.openHandles > 0) fs.release(node)
        for (path in paths) {
            val stat = try {
                fs.stat(p(path))
            } catch (e: FsException) {
                if (e.errno == Errno.NOENT) continue
                throw e
            }
            if (stat.type == FileType.DIRECTORY) fs.removeDirectory(p(path)) else fs.removeFile(p(path))
        }
    }

    private fun padThen(pad: Int, body: () -> Unit): Int {
        if (pad <= 0) {
            body()
            return 0
        }
        return padThen(pad - 1, body) + 1
    }

    /** スタックの小さい新しいスレッドで、詰め物を [pad] 段積んでから [body] を動かす。尽きたら `StackOverflowError`。それ以外の例外はそのまま返す */
    private fun runOnSmallStack(pad: Int, body: () -> Unit): Throwable? {
        var result: Throwable? = null
        val t = Thread(null, {
            try {
                padThen(pad, body)
            } catch (e: Throwable) {
                result = e
            }
        }, "DiskFileSystemStackExhaustionTest", 192L * 1024)
        t.start()
        t.join(stackHeadroomWait(60_000))
        check(!t.isAlive) { "操作が終わらない（リースのロックが残ったか）: ${t.stackTrace.take(12).joinToString(" <- ")}" }
        return result
    }

    /** 食い違いがなく、リースのロックが取れること */
    private fun verify(fs: DiskFileSystem, what: String, soe: Throwable?) {
        fs.ioLockForTest.awaitAcquirable("リースのロックが取れない（$what）")
        val problems = fs.consistencyProblemsForTest()
        if (problems.isNotEmpty()) {
            fail("尽きた後にディスクの状態がホストと食い違った（$what）: $problems\nSOE: ${soe?.stackTrace?.take(14)?.joinToString(" <- ")}")
        }
    }

    /**
     * 詰め物の段数を変えて [attempt]（尽きた・深いところで行えなかったら `true`）を動かす。JIT をかけてから、尽きる境目を探し、
     * 境目の少し手前から先（詰め物が多く、残りのスタックが少ない側）を、操作が使う分の幅（[widthFrames] 段）だけ 1 段ずつ動かす。最後の境目を返す
     */
    private fun walkEdge(widthFrames: Int = WIDTH, attempt: (Int) -> Boolean): Int {
        // 操作を何度か動かして、JIT をかける（インタプリタと JIT のコードでは、フレームの大きさが違い、境目が動く）
        repeat(stackHeadroomRounds(WARMUP)) { attempt(0) }

        /** 尽きる最小の詰め物の段数を探す（倍々で尽きるところを見つけ、二分で縮める） */
        fun findEdge(): Int {
            var lo = 0
            var hi = 256
            while (!attempt(hi)) {
                lo = hi
                hi *= 2
                check(hi < (1 shl 20)) { "詰め物を増やしても尽きない" }
            }
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (attempt(mid)) hi = mid else lo = mid
            }
            return hi
        }

        // 幅は回数の割合（`kibi.stackHeadroom.roundsScale`。インタプリタで回す opt-in のタスクは 1/4）で縮めない（#903）。縮めると
        // 「境目から先を 1 段ずつ全部」でなくなり、`-Xint` では確かめ（`probe` もインタプリタの大きいフレーム）の幅より狭くなって、
        // [sweepVfs] の尽きる位置が確かめの外まで届かなかった。1 回は速い（インタプリタでも 1 本が数分）ので、減らすのは JIT をかける回数だけにする
        val width = widthFrames
        var edge = findEdge()
        var done = 0
        // 境目から先（詰め物が多く、残りのスタックが少ない側）を、操作が使う分の幅だけ 1 段ずつ。境目は、操作のいちばん深い所が尽きる
        // 最小の詰め物で、それより浅い所（ホストの移動の後の付け替えなど）は、詰め物がもっと多いところで尽きる。境目は動くので
        // （JIT が進む）、途中で探し直す
        while (done < width) {
            val batch = minOf(width - done, REFIND_EVERY)
            for (k in 0 until batch) attempt(maxOf(0, edge - 8 + done + k))
            done += batch
            edge = findEdge()
        }
        return edge
    }

    private fun sweep(op: Op) {
        val root = Files.createDirectories(dir.resolve("disk"))
        val fs = DiskFileSystem.open(root, budget = HostFileBudget(64, 128, AtomicInteger()))
        try {
            fs.createDirectory(p("/w"))
            var deep = p("/w")
            for (i in 0 until 8) {
                deep = deep.child("deep$i")
                fs.createDirectory(deep)
            }
            check(deep == p(DEEP)) { "深いパスの定義が合わない: $deep" }
            val counter = AtomicInteger()
            val tally = SoePositions()
            var completed = 0

            /** [pad] 段の詰め物で 1 回動かし、尽きたか（尽きなかったら終わりまで動いた）を返す。毎回、状態を確かめて片付ける */
            fun attempt(pad: Int): Boolean {
                val n = counter.incrementAndGet()
                val a = prepare(fs, op, n)
                val error = runOnSmallStack(pad, a.run)
                val soe = error as? StackOverflowError
                if (error != null && soe == null) throw AssertionError("尽きる以外の失敗（$op、pad $pad）", error)
                verify(fs, "$op、pad $pad、${if (soe != null) "尽きた" else "終わりまで動いた"}", soe)
                if (soe != null) tally.record(soe) else completed++
                scrub(fs, a.node, a.cleanup)
                verify(fs, "$op、pad $pad、片付けた後", soe)
                return soe != null
            }

            val edge = walkEdge(attempt = ::attempt)
            println("#858 disk $op: 境目 $edge、${counter.get()} 回動かし、尽きなかった（終わりまで動いた）$completed 回、尽きた位置: ${tally.describe()}")
            assertTrue(tally.crashed.get() > 0, "1 度も尽きなかった")
            // 入口の確かめ（`ensure(HOST_IO_FRAMES)`）は、リースのロックを取る前に止める。ロック・NIO のチャンネルの中のロックの途中では尽きない
            assertEquals(0, tally.inLocks, "スタックがロックを取る・放す途中で尽きた")
            assertTrue(completed > 0, "1 度も終わりまで動かなかった")
            assertEquals(emptyList<String>(), fs.consistencyProblemsForTest())
        } finally {
            fs.retire()
        }
    }

    @Test
    fun `深いディレクトリのstatの途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.STAT_DEEP)

    @Test
    fun `ファイルを作る途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.CREATE_FILE)

    @Test
    fun `ディレクトリを作る途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.CREATE_DIRECTORY)

    @Test
    fun `書く途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.WRITE)

    @Test
    fun `ファイルを開いたままにする途中で尽きても、開いたままの枠は食い違わない`() = sweep(Op.RETAIN)

    @Test
    fun `開いたままのファイルを閉じる途中で尽きても、開いたままの枠は食い違わない`() = sweep(Op.RELEASE)

    @Test
    fun `ファイルを消す途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.REMOVE_FILE)

    @Test
    fun `ディレクトリを消す途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.REMOVE_DIRECTORY)

    @Test
    fun `名前を変える途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.RENAME)

    @Test
    fun `名前を変えて置き換える途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.RENAME_REPLACE)

    @Test
    fun `ディレクトリの名前を変える途中で尽きても、ディスクの状態は食い違わない`() = sweep(Op.RENAME_DIRECTORY)

    /**
     * fd の表から閉じる道（`Vfs.releaseLater` → `Vfs.drainDeferred` → [DiskFileSystem.release]）を、深いところで行う回（#858）。
     * `drainDeferred` は起動用のスレッドだけでなく、ファイルシステムに届く操作（`checkUnlocked`）から、ゲストのスレッドのその深さでも呼ばれる。
     * 知らせを列から取り出した後に `release` の入口の確かめで尽きると、取り出した知らせが捨てられ、開いたままの枠とホストのファイルが返らない。
     * 毎回、深いところで `drainDeferred` を呼んだ後に、知らせが行われたか、列に残っているかのどちらかであることと、浅いところで行えば
     * 枠が返ることを確かめる
     */
    @Test
    fun `fdの表から閉じる知らせを深いところで行っても、開いたままの枠は返る`() {
        val root = Files.createDirectories(dir.resolve("disk"))
        val fs = DiskFileSystem.open(root, budget = HostFileBudget(64, 128, AtomicInteger()))
        try {
            val vfs = Vfs()
            vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "disk")
            fs.createDirectory(p("/w"))
            val tally = SoePositions()
            var counter = 0
            var drained = 0
            var left = 0

            /** 深いところで行えなかった（知らせが列に残った、または尽きた）なら `true` */
            fun attempt(pad: Int): Boolean {
                val n = ++counter
                val f = vfs.open(p("/w/f$n"), OpenOptions(read = true, write = true, create = true))
                assertEquals(1, fs.openHandles, "開いたファイルの枠を数えていない")
                vfs.releaseLater(f)
                val error = runOnSmallStack(pad) { vfs.drainDeferred() }
                val soe = error as? StackOverflowError
                if (error != null && soe == null) throw AssertionError("尽きる以外の失敗（pad $pad）", error)
                val queued = vfs.hasDeferred
                if (!queued && fs.openHandles != 0) {
                    fail("閉じる知らせを取り出したのに、開いたままの枠が返らない（pad $pad）\nSOE:${soe?.stackTrace?.take(14)?.joinToString(" <- ")}")
                }
                if (soe != null) tally.record(soe)
                if (queued) left++ else if (soe == null) drained++
                vfs.drainDeferred()
                assertTrue(!vfs.hasDeferred, "浅いところで行っても知らせが残った（pad $pad）")
                assertEquals(0, fs.openHandles, "浅いところで行っても枠が返らない（pad $pad）")
                verify(fs, "閉じる知らせ、pad $pad", soe)
                fs.removeFile(p("/w/f$n"))
                return queued || soe != null
            }

            val edge = walkEdge(attempt = ::attempt)
            println("#858 disk deferred release: 境目 $edge、$counter 回動かし、深いところで行えた $drained 回、列に残した $left 回、尽きた位置: ${tally.describe()}")
            assertEquals(0, tally.inLocks, "スタックがロックを取る・放す途中で尽きた")
            assertTrue(drained > 0, "1 度も深いところで行えなかった")
            assertTrue(left > 0, "1 度も列に残さなかった（境目を通っていない）")
            assertEquals(emptyList<String>(), fs.consistencyProblemsForTest())
        } finally {
            fs.retire()
        }
    }

    /** [Vfs] を通して開く・閉じる型（#901） */
    private enum class VfsOp {
        /** 浅いところで開いたファイルを、深いところで閉じる。尽きたら、浅いところで閉じ直す（やり直す呼び手の形） */
        CLOSE,

        /**
         * 同じファイルを浅いところで 2 つ開き（ディスクのハンドルを分け合い、参照は 2）、片方を深いところで閉じる。尽きたら浅いところで閉じ直す。
         * 閉じ終えた後に、もう 1 回閉じても何もしないことと、もう片方が開いたまま使えることを見る（投げた `close` を呼び直しても、
         * 閉じ終えた `close` を呼び直しても、分け合うハンドルの参照を二重に減らさない）
         */
        CLOSE_SHARED,

        /** 開いて書き、`finally` で閉じる（`Machine.readExecutable` と同じ本番の形）。閉じ直さない */
        OPEN_WRITE_CLOSE,
    }

    /**
     * [Vfs] を通して開く・閉じる操作の途中で尽きても、開いたままのホストのファイル（[DiskFileSystem.openHandles]）が残らないことを確かめる（#901）。
     * `Vfs.close` は fd の表に入れていない開いたファイルを閉じるので、閉じ損ねるとマウントを外すまでホストのファイルが開いたままになる。
     * 片付け（[scrub]）の前に、開いたままのファイルが 0 であることを見る（片付けは開いたままのものを強引に閉じるので、後では見えない）。
     *
     * 幅は [VFS_WIDTH] 段にする。境目はいちばん深い入口の確かめ（`release`・`open` の `StackHeadroom.ensure`）が尽きる所で、
     * 確かめの幅（100 段を超える `probe` のフレーム）は詰め物の 1 段よりずっと大きいので、[WIDTH] では確かめの中しか通らない。
     * 確かめより浅い所（`Vfs.close`・`Vfs.open`・`DiskFileSystem.release` 自身の入口）を通り越して、その上のテストのコード
     * （操作より浅い所）まで尽きる位置が届いたことを、終わりに確かめる（手元では、`VFS` の位置で尽きた回は 0〜70 回。JIT が
     * `Vfs.close` を呼び手に畳むと入口がなくなるので、`VFS` の回数は確かめない）
     */
    private fun sweepVfs(op: VfsOp) {
        val root = Files.createDirectories(dir.resolve("disk"))
        val fs = DiskFileSystem.open(root, budget = HostFileBudget(64, 128, AtomicInteger()))
        try {
            val vfs = Vfs()
            vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "disk")
            fs.createDirectory(p("/w"))
            val counter = AtomicInteger()
            val tally = SoePositions()
            var completed = 0
            val options = OpenOptions(read = true, write = true, create = true)

            fun attempt(pad: Int): Boolean {
                val n = counter.incrementAndGet()
                val path = p("/w/f$n")
                val error = when (op) {
                    VfsOp.CLOSE -> {
                        val f = vfs.open(path, options)
                        assertEquals(1, fs.openHandles, "開いたファイルの枠を数えていない")
                        runOnSmallStack(pad) { vfs.close(f) }.also { if (it is StackOverflowError) vfs.close(f) }
                    }
                    VfsOp.CLOSE_SHARED -> {
                        val a = vfs.open(path, options)
                        val b = vfs.open(path, options)
                        assertEquals(1, fs.openHandles, "同じファイルを開いたのに、ハンドルを分け合っていない")
                        val e = runOnSmallStack(pad) { vfs.close(a) }.also { if (it is StackOverflowError) vfs.close(a) }
                        assertEquals(1, fs.openHandles, "閉じ直すと、もう片方と分け合うハンドルまで閉じた（pad $pad）")
                        vfs.close(a)
                        assertEquals(1, fs.openHandles, "閉じ終えたものをもう 1 回閉じると、分け合うハンドルを閉じた（pad $pad）")
                        vfs.write(b, payload, 0, payload.size)
                        vfs.close(b)
                        e
                    }
                    VfsOp.OPEN_WRITE_CLOSE -> runOnSmallStack(pad) {
                        val f = vfs.open(path, options)
                        try {
                            vfs.write(f, payload, 0, payload.size)
                        } finally {
                            vfs.close(f)
                        }
                    }
                }
                val soe = error as? StackOverflowError
                if (error != null && soe == null) throw AssertionError("尽きる以外の失敗（$op、pad $pad）", error)
                val what = "$op、pad $pad、${if (soe != null) "尽きた" else "終わりまで動いた"}"
                if (fs.openHandles != 0) {
                    fail("開いたままのホストのファイルが残った（$what）: ${fs.openHandles}\nSOE: ${soe?.stackTrace?.take(14)?.joinToString(" <- ")}")
                }
                verify(fs, what, soe)
                if (soe != null) tally.record(soe) else completed++
                scrub(fs, 0, listOf("/w/f$n"))
                verify(fs, "$op、pad $pad、片付けた後", soe)
                return soe != null
            }

            val edge = walkEdge(VFS_WIDTH, ::attempt)
            println("#901 vfs $op: 境目 $edge、${counter.get()} 回動かし、尽きなかった（終わりまで動いた）$completed 回、尽きた位置: ${tally.describe()}")
            assertTrue(tally.crashed.get() > 0, "1 度も尽きなかった")
            // 入口の確かめより浅い所を通り越し、操作の外（その上のテストのコード）でも尽きた。その間にある尽きうる位置（`Vfs` の
            // 関数の入口）は、すべて幅に入っている（JIT が `Vfs.close` を呼び手に畳むと、その入口はなくなり VFS の回は 0 になりうる）
            assertTrue(tally.count(SoePositions.Position.OTHER) > 0, "操作より浅い所まで尽きる位置が届いていない（幅が足りない）: ${tally.describe()}")
            assertEquals(0, tally.inLocks, "スタックがロックを取る・放す途中で尽きた")
            assertTrue(completed > 0, "1 度も終わりまで動かなかった")
            assertEquals(emptyList<String>(), fs.consistencyProblemsForTest())
        } finally {
            fs.retire()
        }
    }

    @Test
    fun `Vfsで閉じる途中で尽きても、閉じ直せばホストのファイルは残らない`() = sweepVfs(VfsOp.CLOSE)

    @Test
    fun `Vfsで分け合うファイルを閉じる途中で尽きても、閉じ直しで分け合うハンドルを閉じない`() = sweepVfs(VfsOp.CLOSE_SHARED)

    @Test
    fun `Vfsで開いて書いてfinallyで閉じる途中で尽きても、ホストのファイルは残らない`() = sweepVfs(VfsOp.OPEN_WRITE_CLOSE)

    /** `StackHeadroom.VFS_OPEN_FRAMES` 段の確かめが通らなくなる最初の深さまで降りて、[body] を動かす（降りた段数を返す） */
    private fun descendToOpenGate(n: Int, body: () -> Unit): Int {
        if (!StackHeadroom.hasHeadroom(StackHeadroom.VFS_OPEN_FRAMES)) {
            body()
            return n
        }
        return descendToOpenGate(n + 1, body) + 1
    }

    /**
     * 開く側の入口の確かめ（`Vfs.open` の `ensureCloseHeadroom`。[StackHeadroom.VFS_OPEN_FRAMES]）が通らない深さで、ディスクのファイルを開くと、
     * **何も変えずに** `StackOverflowError` で断る（#901）。本番の呼び手（`readExecutable`・`path_open`・`open-at`）は閉じ直さないので、
     * 断る・通すの境目が `open` の入口で決まらないと、続く `close` の `release` の確かめが通らない深さで開いてしまい、ホストのファイルが残る。
     * 掃引（[sweepVfs]）は、窓が確かめの中に落ちるので、確かめを外しても落ちない。この試験は、確かめが通らない最初の深さで開き、
     * 断られたことと、何も残っていないこと（作ったファイル・切り詰め・開いたままの枠・予約した枠）、浅いところで開き直せることを見る。
     * 確かめを外す、`reserveOpen`・`createFile`・`opened` の後ろへ動かすと、落ちる
     */
    private fun refuseOpen(existing: Boolean, truncate: Boolean) {
        val root = Files.createDirectories(dir.resolve("disk"))
        val fs = DiskFileSystem.open(root, budget = HostFileBudget(64, 128, AtomicInteger()))
        try {
            val vfs = Vfs()
            vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "disk")
            fs.createDirectory(p("/w"))
            val options = OpenOptions(read = true, write = true, create = true, truncate = truncate)
            // 浅いところで、底で初めて触るクラスを読み込んでおく
            vfs.close(vfs.open(p("/w/warm"), options))
            fs.removeFile(p("/w/warm"))
            var refused = 0
            repeat(stackHeadroomRounds(ROUNDS)) { round ->
                val path = p("/w/f$round")
                if (existing) {
                    val node = fs.createFile(path)
                    fs.write(node, 0, payload, 0, payload.size)
                }
                var opened: OpenFile? = null
                val error = runOnSmallStack(0) {
                    descendToOpenGate(0) { opened = vfs.open(path, options) }
                }
                val what = "round $round（${if (existing) "ある" else "ない"}、truncate $truncate）"
                if (error != null && error !is StackOverflowError) throw AssertionError("尽きる以外の失敗（$what）", error)
                if (error is StackOverflowError) {
                    refused++
                    assertEquals(null, opened, "断ったのに開いたファイルを返した（$what）")
                } else {
                    // 通ったときは、閉じて、後始末が合うことを見る
                    opened?.let { vfs.close(it) }
                }
                assertEquals(0, fs.openHandles, "開いたままのホストのファイルが残った（$what）")
                assertEquals(emptyList<String>(), fs.consistencyProblemsForTest(), "断った後に枠・表がホストと食い違った（$what）")
                if (error is StackOverflowError) {
                    val stat = try {
                        fs.stat(path)
                    } catch (e: FsException) {
                        if (e.errno != Errno.NOENT) throw e
                        null
                    }
                    if (existing) {
                        assertEquals(payload.size.toLong(), stat?.size, "断ったのに切り詰めた・消えた（$what）")
                    } else {
                        assertEquals(null, stat, "断ったのにファイルを作った（$what）")
                    }
                }
                // 浅いところで開き直せる
                val again = vfs.open(path, options)
                assertEquals(1, fs.openHandles, "浅いところで開き直せない（$what）")
                vfs.close(again)
                assertEquals(0, fs.openHandles, "閉じても枠が返らない（$what）")
                fs.removeFile(path)
            }
            assertTrue(refused > 0, "1 度も入口の確かめで断られなかった（確かめが効いていない）")
            assertEquals(emptyList<String>(), fs.consistencyProblemsForTest())
        } finally {
            fs.retire()
        }
    }

    @Test
    fun `入口の確かめが通らない深さでディスクのファイルを作って開くと、何も残さずに断る`() = refuseOpen(existing = false, truncate = false)

    @Test
    fun `入口の確かめが通らない深さで既存のディスクのファイルを開くと、何も変えずに断る`() = refuseOpen(existing = true, truncate = false)

    @Test
    fun `入口の確かめが通らない深さで既存のディスクのファイルをtruncateで開くと、切り詰めずに断る`() = refuseOpen(existing = true, truncate = true)

    private companion object {
        /** 入口の確かめの試験を繰り返す回数（JIT でフレームの大きさが変わっても、毎回、確かめが通らない最初の深さで開く） */
        const val ROUNDS = 40

        const val DEEP = "/w/deep0/deep1/deep2/deep3/deep4/deep5/deep6/deep7"

        /** 境目を探す前に動かす回数 */
        const val WARMUP = 150

        /** 境目から、詰め物をずらして動かす幅（段数）。操作が使うスタックの幅より広くする */
        const val WIDTH = 480

        /** [sweepVfs] の幅（段数）。`release`・`open` の入口の確かめの幅より広くし、`Vfs` 自身のフレームとその上まで通る */
        const val VFS_WIDTH = 1800

        /** 境目を探し直す間隔（回） */
        const val REFIND_EVERY = 120
    }
}
