package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.MachineClock
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.persist.snapshot.TmpfsSectionCodec
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * スタックの底の近くで `/tmp`（[TmpFileSystem]）を変えても、RAM の勘定（マシン → サーバー全体の別々の CAS）と [TmpFileSystem.usedBytes]・
 * 木がずれないことを確かめる（#883）。
 *
 * 形は `MemoryAccountStackExhaustionTest`（#856）と同じ。本番と同じく、サーバー全体の [MemoryAccount] → マシンの [MemoryAccount] →
 * [TmpFileSystem]（時計は本番と同じ深さの `MachineClock.realtimeNanos` のラムダ）を組み、256 KiB のスタックのスレッドで 1 段ずつ深くしながら、
 * **底に近い深さのすべて**で、変える操作を 1 回ずつ呼ぶ（尽きる位置を JIT の温まり方に頼らない）。組は深さごとに、浅いところで前もって
 * 作る（消す・置き換える・縮めるの元になるファイルと塊も）。最後に浅いところで、深さごとに次を確かめる。
 *
 * - 勘定がずれない: `fs.usedBytes`・マシン・サーバー全体の使用量が等しい
 * - 木が壊れない: [TmpFileSystem.consistencyProblemsForTest]（木から数え直した RAM が `usedBytes` と合う、塊が `size` の中、孤立した node がない）と、
 *   `TMPFS` 節に書いて読み直せること（[TmpfsSectionCodec.read] が断らない）
 * - 外す（[TmpFileSystem.onUnmounted]）と、3 つとも 0 に戻る
 * - 断られた（`StackOverflowError`）深さでは、木も勘定も始める前のまま
 * - 入口の確かめがあること: 操作の前後とも [StackHeadroom.TMPFS_FRAMES] 段の確かめが通らない深さでは、必ず断られる
 *
 * ホストの関数の本体から入る道（[hostBody]）は、包みの確かめ（[StackHeadroom.HOST_CALL_FRAMES]）が通った後、本体が深くなってから操作に入る。
 * カーネルのプロセス（`ctx.vfs` で `/tmp` を開く・書く・名前を変える・消す）を通した確かめは `TmpFileSystemStackExhaustionKernelTest`
 */
class TmpFileSystemStackExhaustionTest {
    private val stackBytes = 256L * 1024

    private enum class Op {
        /** 空のファイルに 1 バイト書く（新しい塊 1 つ） */
        WRITE,

        /** 空のファイルに 3 塊分を 1 回で書く（新しい塊 3 つ。塊を足した後・`size` を直す前の窓） */
        WRITE_MULTI,

        /** 塊の端の近くまであるファイルに追記する（`append` → 書く。新しい塊 1 つ） */
        APPEND,

        CREATE_FILE,
        CREATE_DIRECTORY,

        /** 塊のあるファイルを消す */
        REMOVE_FILE,

        REMOVE_DIRECTORY,

        /** 置き換えのない名前の変更（名前の長さが変わる） */
        RENAME,

        /** 塊のあるファイルで、塊のあるファイルを置き換える */
        RENAME_REPLACE_FILE,

        /** 空のディレクトリで、空のディレクトリを置き換える */
        RENAME_REPLACE_DIRECTORY,

        /** 2 塊のファイルを 1 塊の途中まで縮める（塊を返し、端の塊の後ろを 0 にする） */
        SHRINK,

        /** 空のファイルを伸ばす（`allocate` → 大きさを変える。穴なので RAM は増えない） */
        ALLOCATE,

        /** VFS の `open` の `O_TRUNC` で、塊のあるファイルを切り詰める */
        VFS_TRUNCATE,
    }

    /** 深さ 1 つ分の試験の組（浅いところで前もって作る） */
    private class Fixture(op: Op) {
        val server = MemoryAccount(Long.MAX_VALUE / 4)
        val machine = MemoryAccount(1L shl 40, server)
        val fs = TmpFileSystem(machine) { CLOCK.realtimeNanos() }
        val vfs: Vfs? = if (op == Op.VFS_TRUNCATE) Vfs().also { it.mount(VfsPath.ROOT, fs, false, "tmp") } else null
        val empty: Long
        val data: Long
        val nearEdge: Long

        init {
            empty = fs.createFile(F_EMPTY)
            data = fs.createFile(F_DATA)
            fs.write(data, 0, PAYLOAD, 0, if (op == Op.SHRINK) 2 * TmpFileSystem.CHUNK_BYTES else 10)
            nearEdge = if (op == Op.APPEND) fs.createFile(F_EDGE).also { fs.write(it, 0, PAYLOAD, 0, TmpFileSystem.CHUNK_BYTES - 4) } else 0
            if (op == Op.RENAME_REPLACE_FILE) fs.createFile(F_OTHER).also { fs.write(it, 0, PAYLOAD, 0, 10) }
            if (op == Op.REMOVE_DIRECTORY || op == Op.RENAME_REPLACE_DIRECTORY) fs.createDirectory(D_ONE)
            if (op == Op.RENAME_REPLACE_DIRECTORY) fs.createDirectory(D_TWO)
        }

        val initialUsed = fs.usedBytes
        val initialPrint = fingerprint(fs)
        var status = NOT_RUN
        var lackedBefore = false
        var lackedAfter = false

        /** 断られたときの `StackOverflowError`（尽きた位置は浅いところに戻ってから見る。底でスタックトレースを読むと、そのクラスの初期化で尽きる） */
        var soe: StackOverflowError? = null

        /** 断られたとき、尽きた位置が入口の確かめ（`StackHeadroom`）の中だったか */
        val inProbe: Boolean
            get() = soe?.stackTrace?.firstOrNull { it.className.startsWith(MOD_PACKAGE) }?.className == StackHeadroom::class.java.name
    }

    private class Run(val op: Op, val hostBody: Boolean, val pool: Array<Fixture>) {
        var next = 0
    }

    /** [frames] 段の確かめが通らなければ `true`（何も触らない） */
    private fun lacks(frames: Int): Boolean = try {
        StackHeadroom.ensure(frames)
        false
    } catch (_: StackOverflowError) {
        true
    }

    /** 1 段ずつ深くする。底に近づいたら（[WINDOW_FRAMES] 段の確かめが通らなくなったら）、深さごとに操作を 1 回呼ぶ */
    private fun descend(run: Run, n: Int): Int {
        if (run.next < run.pool.size && lacks(WINDOW_FRAMES)) {
            if (run.hostBody) {
                // ホストの関数の包み: 確かめが通らなければ、本体に入らずにプロセスが終わる（操作は呼ばれない）
                if (!lacks(StackHeadroom.HOST_CALL_FRAMES)) body(run, run.pool[run.next++], BODY_FRAMES)
            } else {
                operate(run, run.pool[run.next++])
            }
        }
        return descend(run, n + 1) + 1
    }

    /** 包みを通った後、本体が [k] 段深くなってから操作に入る */
    private fun body(run: Run, f: Fixture, k: Int): Int {
        if (k == 0) {
            operate(run, f)
            return 0
        }
        return body(run, f, k - 1) + 1
    }

    private fun operate(run: Run, f: Fixture) {
        f.lackedBefore = lacks(StackHeadroom.TMPFS_FRAMES)
        try {
            val fs = f.fs
            when (run.op) {
                Op.WRITE -> fs.write(f.empty, 0, PAYLOAD, 0, 1)
                Op.WRITE_MULTI -> fs.write(f.empty, 0, PAYLOAD, 0, 3 * TmpFileSystem.CHUNK_BYTES)
                Op.APPEND -> fs.append(f.nearEdge, PAYLOAD, 0, 16)
                Op.CREATE_FILE -> fs.createFile(F_NEW)
                Op.CREATE_DIRECTORY -> fs.createDirectory(F_NEW)
                Op.REMOVE_FILE -> fs.removeFile(F_DATA)
                Op.REMOVE_DIRECTORY -> fs.removeDirectory(D_ONE)
                Op.RENAME -> fs.rename(F_DATA, F_RENAMED, false)
                Op.RENAME_REPLACE_FILE -> fs.rename(F_DATA, F_OTHER, false)
                Op.RENAME_REPLACE_DIRECTORY -> fs.rename(D_ONE, D_TWO, true)
                Op.SHRINK -> fs.setSize(f.data, 100)
                Op.ALLOCATE -> fs.allocate(f.empty, 3L * TmpFileSystem.CHUNK_BYTES)
                Op.VFS_TRUNCATE -> f.vfs!!.open(F_DATA, TRUNCATE)
            }
            f.status = COMPLETED
        } catch (e: StackOverflowError) {
            f.status = REFUSED
            f.soe = e
        }
        f.lackedAfter = lacks(StackHeadroom.TMPFS_FRAMES)
    }

    private class Result(val used: List<Fixture>, val drifts: List<String>) {
        val completed = used.count { it.status == COMPLETED }
        val refused = used.count { it.status == REFUSED }
        val refusedInProbe = used.count { it.status == REFUSED && it.inProbe }

        /** 操作の前後で確かめが通らなかった（フレームが変わっていない）深さと、そのうち断られなかった深さの数 */
        val lacking = used.filter { it.lackedBefore && it.lackedAfter }
        val lackingButCompleted = lacking.count { it.status != REFUSED }
    }

    /** 深さ [i] の組の後の状態を確かめ、ずれの説明を [drifts] に足す。最後に外して 0 に戻ることも見る */
    private fun inspect(i: Int, f: Fixture, drifts: MutableList<String>) {
        val fs = f.fs
        val where = "$i 番目（${if (f.status == REFUSED) "断られた" else "通った"}）"
        if (fs.usedBytes != f.machine.usedBytes || fs.usedBytes != f.server.usedBytes) {
            drifts += "$where: 勘定がずれた（/tmp ${fs.usedBytes}、マシン ${f.machine.usedBytes}、サーバー ${f.server.usedBytes}）"
        }
        fs.consistencyProblemsForTest().forEach { drifts += "$where: $it" }
        try {
            TmpfsSectionCodec.read(TmpfsSectionCodec.write(1, fs.snapshot()!!))
        } catch (e: Exception) {
            drifts += "$where: TMPFS 節を読み直せない（$e）"
        }
        if (f.status == REFUSED && (fs.usedBytes != f.initialUsed || fingerprint(fs) != f.initialPrint)) {
            drifts += "$where: 断られたのに変わった（usedBytes ${f.initialUsed} → ${fs.usedBytes}）"
        }
        fs.onUnmounted()
        if (fs.usedBytes != 0L || f.machine.usedBytes != 0L || f.server.usedBytes != 0L) {
            drifts += "$where: 外した後に残った（/tmp ${fs.usedBytes}、マシン ${f.machine.usedBytes}、サーバー ${f.server.usedBytes}）"
        }
    }

    private fun sweep(op: Op, hostBody: Boolean): Result = timeout(Duration.ofSeconds(TIMEOUT_SECONDS)) {
        val pool = Array(POOL_SIZE) { Fixture(op) }
        // 浅いところで、底で初めて触るクラス（操作の中の例外・VFS の道）を読み込んでおく（クラスの初期化の途中で尽きると、試験と関係なく壊れる）
        run {
            val warm = Fixture(op)
            operate(Run(op, false, emptyArray()), warm)
            inspect(0, warm, ArrayList())
        }
        val run = Run(op, hostBody, pool)
        val failure = AtomicReference<Throwable>()
        val thread = Thread(null, {
            try {
                descend(run, 0)
            } catch (_: StackOverflowError) {
                // 底まで来た（ここは確かめの対象でない）
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "tmpfs-sweep", stackBytes)
        thread.start()
        thread.join()
        failure.get()?.let { throw AssertionError("試験の再帰が StackOverflowError 以外で終わった", it) }
        val used = pool.take(run.next)
        val drifts = ArrayList<String>()
        for ((i, f) in used.withIndex()) inspect(i, f, drifts)
        val result = Result(used, drifts)
        println(
            "#883 $op${if (hostBody) "（ホストの関数の本体）" else ""}: 深さ ${used.size}、通った ${result.completed}、断られた ${result.refused}" +
                "（うち確かめの中 ${result.refusedInProbe}）、確かめが通らない深さ ${result.lacking.size}、うち断られなかった ${result.lackingButCompleted}、" +
                "ずれ ${drifts.size}",
        )
        drifts.take(10).forEach { println("#883 $op: $it") }
        result
    }

    private fun check(op: Op) {
        val r = sweep(op, hostBody = false)
        assertEquals(emptyList<String>(), r.drifts, "$op: 勘定・木がずれた")
        // 試験が底をまたいでいること（どちらかが 0 だと、確かめられていない）
        assertTrue(r.completed > 0, "$op: 操作が通った深さがない（試験の窓が底から遠い）")
        assertTrue(r.refused > 0, "$op: 操作が断られた深さがない（試験の窓が底まで届かない）")
        assertTrue(r.lacking.isNotEmpty(), "$op: 確かめが通らない深さがない（試験の窓が底まで届かない）")
        // 入口の確かめがあるなら、通らない深さでは必ず断られる（なければ、通って数える）
        assertEquals(0, r.lackingButCompleted, "$op: 確かめが通らない深さで、操作が通った（入口の確かめがない）")
    }

    @Test
    fun `底に近い深さのすべてで、新しい塊に書いても、勘定と木はずれない`() = check(Op.WRITE)

    @Test
    fun `底に近い深さのすべてで、いくつもの塊を1回で書いても、勘定と木はずれない`() = check(Op.WRITE_MULTI)

    @Test
    fun `底に近い深さのすべてで、追記しても、勘定と木はずれない`() = check(Op.APPEND)

    @Test
    fun `底に近い深さのすべてで、ファイルを作っても、勘定と木はずれない`() = check(Op.CREATE_FILE)

    @Test
    fun `底に近い深さのすべてで、ディレクトリを作っても、勘定と木はずれない`() = check(Op.CREATE_DIRECTORY)

    @Test
    fun `底に近い深さのすべてで、ファイルを消しても、勘定と木はずれない`() = check(Op.REMOVE_FILE)

    @Test
    fun `底に近い深さのすべてで、ディレクトリを消しても、勘定と木はずれない`() = check(Op.REMOVE_DIRECTORY)

    @Test
    fun `底に近い深さのすべてで、名前を変えても、勘定と木はずれない`() = check(Op.RENAME)

    @Test
    fun `底に近い深さのすべてで、ファイルを置き換えても、勘定と木はずれない`() = check(Op.RENAME_REPLACE_FILE)

    @Test
    fun `底に近い深さのすべてで、空のディレクトリを置き換えても、勘定と木はずれない`() = check(Op.RENAME_REPLACE_DIRECTORY)

    @Test
    fun `底に近い深さのすべてで、縮めても、勘定と木はずれない`() = check(Op.SHRINK)

    @Test
    fun `底に近い深さのすべてで、allocateしても、勘定と木はずれない`() = check(Op.ALLOCATE)

    @Test
    fun `底に近い深さのすべてで、VFSのopenで切り詰めても、勘定と木はずれない`() = check(Op.VFS_TRUNCATE)

    /**
     * ホストの関数の本体（包みの確かめを通った後）から `/tmp` を変える道のまね。包みは通ったのに本体の途中で断られる深さがあること
     * （ADR 0043）と、そこでも勘定と木がずれないことを確かめる
     */
    @Test
    fun `ホストの関数の本体から変える操作が、包みの確かめを通った後に断られても、勘定と木はずれない`() {
        for (op in listOf(Op.WRITE_MULTI, Op.CREATE_FILE, Op.RENAME_REPLACE_FILE, Op.VFS_TRUNCATE)) {
            val r = sweep(op, hostBody = true)
            assertEquals(emptyList<String>(), r.drifts, "$op: 勘定・木がずれた")
            assertTrue(r.completed > 0, "$op: 本体からの操作が通った深さがない")
            assertTrue(r.refused > 0, "$op: 包みを通った後に、本体からの操作が断られた深さがない（本体の途中で尽きる窓が確かめられていない）")
        }
    }

    private companion object {
        const val NOT_RUN = 0
        const val COMPLETED = 1
        const val REFUSED = 2

        const val MOD_PACKAGE = "pro.ikili.kibicomputers."

        /** 底に近い深さを選ぶための確かめの段数（操作の確かめ [StackHeadroom.TMPFS_FRAMES] の何倍も大きくして、窓に底を含める） */
        const val WINDOW_FRAMES = 256

        /** ホストの関数の本体が、包みの後に深くなる段数 */
        const val BODY_FRAMES = 150

        /** 窓に入る深さの数の上限 */
        const val POOL_SIZE = 6000

        val TIMEOUT_SECONDS: Long = System.getProperty("kibi.stackHeadroom.timeoutSeconds")?.toLongOrNull() ?: 120

        /** 本番の `/tmp` の時計（`environment.clock.realtimeNanos()`）と同じ深さ */
        val CLOCK: MachineClock = MachineClock.SYSTEM

        val PAYLOAD = ByteArray(3 * TmpFileSystem.CHUNK_BYTES) { (it % 251).toByte() }

        val F_EMPTY: VfsPath = VfsPath.parse("/empty")
        val F_DATA: VfsPath = VfsPath.parse("/data")
        val F_EDGE: VfsPath = VfsPath.parse("/edge")
        val F_OTHER: VfsPath = VfsPath.parse("/other")
        val F_NEW: VfsPath = VfsPath.parse("/new")
        val F_RENAMED: VfsPath = VfsPath.parse("/renamed-to-a-longer-name")
        val D_ONE: VfsPath = VfsPath.parse("/dir-one")
        val D_TWO: VfsPath = VfsPath.parse("/dir-two")

        val TRUNCATE = OpenOptions(read = false, write = true, truncate = true)

        /** 木の形と大きさ・時刻・塊の番号（中身は除く）。断られた操作が何も変えていないことを比べる */
        fun fingerprint(fs: TmpFileSystem): String = fs.snapshot()!!.nodes.joinToString("|") { n ->
            "${n.id},${n.type},${n.size},${n.modifiedNanos},${n.changedNanos},${n.children},${n.chunks?.map { it.first }}"
        }
    }
}
