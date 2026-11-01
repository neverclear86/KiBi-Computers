package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * WASI p1 の path_*（#71）。ダミーのモジュールから import を直接呼び、VFS の中身で確かめる。
 *
 * fd の用意: 3 は preopen の `/`、4 は preopen の `/data`、5 は `/data/a.txt`（普通のファイル）。
 * VFS: `/` はメモリの FS（`/data/a.txt` = "hello"、`/data/sub/`）、`/ro` は読み取り専用（`/ro/f`）、`/mnt` は別の FS
 */
class WasiP1PathTest {
    private val limit = Duration.ofSeconds(30)

    private val allFunctions = arrayOf(
        "path_open", "path_create_directory", "path_remove_directory", "path_unlink_file", "path_rename",
        "path_filestat_get", "path_filestat_set_times", "path_readlink", "path_symlink", "path_link",
        "fd_read", "fd_write", "fd_close", "fd_fdstat_get", "fd_prestat_get", "fd_prestat_dir_name",
    )

    private fun forwarding(): FakeModule {
        val b = FakeModule.builder().fuelMetered().memory(1)
        for (name in allFunctions) {
            val type = WasiP1.typeOf(name)!!
            b.import(P1.MODULE, name, type)
            b.export(name, type) { c, args -> c.callImport(P1.MODULE, name, *args) }
        }
        return b.build()
    }

    private fun interface Call {
        operator fun invoke(name: String, vararg args: Long): Int
    }

    /** ゲストのメモリに文字列を置き、`(ptr, len)` を返す */
    private class Strings(val mem: WasmMemory) {
        private var next = 8192

        fun put(text: String): LongArray = put(text.toByteArray(Charsets.UTF_8))

        fun put(bytes: ByteArray): LongArray {
            val at = next
            mem.write(at, bytes)
            next += bytes.size + 8
            return longArrayOf(at.toLong(), bytes.size.toLong())
        }
    }

    /** p1 の関数を、パスの文字列つきで呼ぶ道具 */
    private class P(val call: Call, val mem: WasmMemory) {
        val s = Strings(mem)

        /** `path_open`。成功すれば新しい fd、失敗すれば `-errno` */
        fun open(dir: Int, path: String, oflags: Int = 0, rights: Long = P1.RIGHT_FD_READ, fdflags: Int = 0, lookup: Int = 0): Int {
            val (p, l) = s.put(path)
            val e = call("path_open", dir.toLong(), lookup.toLong(), p, l, oflags.toLong(), rights, 0, fdflags.toLong(), OUT.toLong())
            return if (e == 0) mem.readI32(OUT) else -e
        }

        fun one(name: String, dir: Int, path: String): Int {
            val (p, l) = s.put(path)
            return call(name, dir.toLong(), p, l)
        }

        fun mkdir(dir: Int, path: String) = one("path_create_directory", dir, path)

        fun rmdir(dir: Int, path: String) = one("path_remove_directory", dir, path)

        fun unlink(dir: Int, path: String) = one("path_unlink_file", dir, path)

        fun rename(fromDir: Int, from: String, toDir: Int, to: String): Int {
            val (p1, l1) = s.put(from)
            val (p2, l2) = s.put(to)
            return call("path_rename", fromDir.toLong(), p1, l1, toDir.toLong(), p2, l2)
        }

        /** `path_filestat_get`。成功すれば [STAT] に filestat がある */
        fun stat(dir: Int, path: String, lookup: Int = P1.LOOKUPFLAGS_SYMLINK_FOLLOW): Int {
            val (p, l) = s.put(path)
            return call("path_filestat_get", dir.toLong(), lookup.toLong(), p, l, STAT.toLong())
        }

        val statType: Int get() = mem.readU8(STAT + 16)
        val statSize: Long get() = mem.readI64(STAT + 32)
        val statAtim: Long get() = mem.readI64(STAT + 40)
        val statMtim: Long get() = mem.readI64(STAT + 48)

        fun read(fd: Int, n: Int): String {
            mem.writeI32(IOV, BUF)
            mem.writeI32(IOV + 4, n)
            assertEquals(0, call("fd_read", fd.toLong(), IOV.toLong(), 1, OUT.toLong()))
            return String(mem.read(BUF, mem.readI32(OUT)), Charsets.UTF_8)
        }

        fun write(fd: Int, text: String): Int {
            mem.write(BUF, text.toByteArray())
            mem.writeI32(IOV, BUF)
            mem.writeI32(IOV + 4, text.length)
            return call("fd_write", fd.toLong(), IOV.toLong(), 1, OUT.toLong())
        }

        companion object {
            const val OUT = 100
            const val STAT = 200
            const val IOV = 300
            const val BUF = 400
        }
    }

    private fun Machine.setUpFiles(): Int = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/data"))
        vfs.createDirectory(VfsPath.parse("/data/sub"))
        val f = vfs.open(VfsPath.parse("/data/a.txt"), OpenOptions(read = true, write = true, create = true))
        vfs.write(f, "hello".toByteArray(), 0, 5)
        val ro = TestMemoryFileSystem()
        ro.createFile(VfsPath.parse("/f"))
        vfs.mount(VfsPath.parse("/ro"), ro, readOnly = true, source = "test:ro")
        vfs.mount(VfsPath.parse("/mnt"), TestMemoryFileSystem(), readOnly = false, source = "test:mnt")
        kernel.objects.add(f)
    }

    private fun run(body: (Machine, ProcessContext, P) -> Unit) = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val fileId = m.setUpFiles()
            val module = forwarding()
            val options = SpawnOptions(
                stdio = StdioSpec.CLOSED,
                fds = mapOf(
                    3 to StdioSource.Directory("/"),
                    4 to StdioSource.Directory("/data"),
                    5 to StdioSource.Object(fileId, HandleRights.ALL),
                ),
            )
            val errors = Collections.synchronizedList(ArrayList<Throwable>())
            val driver = TestDriver(ProcessKind.BLOCKING, onConfigure = { ctx, b -> WasiP1.register(ctx, b, module, true) }) { ctx ->
                try {
                    val mem = ctx.instance.memory!!
                    body(m, ctx, P(Call { name, args -> ctx.instance.call(name, *args)[0].toInt() }, mem))
                } catch (t: Throwable) {
                    errors += t
                }
                StepResult.Exit(0)
            }
            val pid = m.spawn(h.program(module), driver, options)
            eventually(10_000) {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            errors.firstOrNull()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
        }
    }

    private fun Machine.exists(path: String): Boolean = lock.withLockValue {
        try {
            vfs.stat(VfsPath.parse(path))
            true
        } catch (_: pro.ikili.kibicomputers.core.kernel.vfs.FsException) {
            false
        }
    }

    private fun Machine.content(path: String): String = lock.withLockValue {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true))
        val buf = ByteArray(256)
        val n = vfs.read(f, buf, 0, buf.size)
        String(buf, 0, n, Charsets.UTF_8)
    }

    @Test
    fun `preopenのディレクトリが見える`() = run { _, _, p ->
        assertEquals(0, p.call("fd_prestat_get", 3, P.OUT.toLong()))
        assertEquals(1, p.mem.readI32(P.OUT + 4))
        assertEquals(0, p.call("fd_prestat_get", 4, P.OUT.toLong()))
        assertEquals(5, p.mem.readI32(P.OUT + 4))
        assertEquals(0, p.call("fd_prestat_dir_name", 4, P.BUF.toLong(), 5))
        assertEquals("/data", String(p.mem.read(P.BUF, 5)))
    }

    @Test
    fun `path_openのoflagsと権利`() = run { m, _, p ->
        // 読む
        val r = p.open(3, "data/a.txt")
        assertTrue(r >= 0, "新しい fd: $r")
        assertEquals("hello", p.read(r, 16))
        assertEquals(Errno.BADF.code, p.write(r, "x"))
        // 基点からの相対パス、`.` と途中の `..`
        val r2 = p.open(4, "./sub/../a.txt")
        assertEquals("hello", p.read(r2, 16))

        // 作る・排他
        val w = p.open(3, "data/new.txt", oflags = P1.OFLAGS_CREAT or P1.OFLAGS_EXCL, rights = P1.RIGHT_FD_WRITE)
        assertTrue(w >= 0)
        assertEquals(0, p.write(w, "abc"))
        assertEquals("abc", m.content("/data/new.txt"))
        assertEquals(-Errno.EXIST.code, p.open(3, "data/new.txt", oflags = P1.OFLAGS_CREAT or P1.OFLAGS_EXCL, rights = P1.RIGHT_FD_WRITE))
        assertEquals(-Errno.NOENT.code, p.open(3, "data/none.txt"))

        // 切り詰め
        val t = p.open(4, "new.txt", oflags = P1.OFLAGS_TRUNC, rights = P1.RIGHT_FD_WRITE or P1.RIGHT_FD_READ)
        assertTrue(t >= 0)
        assertEquals("", m.content("/data/new.txt"))

        // 追記と NONBLOCK は fdstat に出る
        val a = p.open(4, "new.txt", rights = P1.RIGHT_FD_WRITE, fdflags = P1.FDFLAGS_APPEND or P1.FDFLAGS_NONBLOCK)
        assertEquals(0, p.call("fd_fdstat_get", a.toLong(), P.STAT.toLong()))
        assertEquals(P1.FDFLAGS_APPEND or P1.FDFLAGS_NONBLOCK, p.mem.readU8(P.STAT + 2))
        assertEquals(0, p.write(t, "12345"))
        assertEquals(0, p.write(a, "Z"))
        assertEquals("12345Z", m.content("/data/new.txt"))

        // ディレクトリ
        val d = p.open(3, "data", oflags = P1.OFLAGS_DIRECTORY)
        assertEquals(0, p.call("fd_fdstat_get", d.toLong(), P.STAT.toLong()))
        assertEquals(P1.FILETYPE_DIRECTORY, p.mem.readU8(P.STAT))
        assertTrue(p.open(d, "a.txt") >= 0, "開いたディレクトリも基点になる")
        assertEquals(-Errno.NOTDIR.code, p.open(3, "data/a.txt", oflags = P1.OFLAGS_DIRECTORY))
        assertEquals(-Errno.NOTDIR.code, p.open(3, "data/a.txt/"))
        assertEquals(-Errno.ISDIR.code, p.open(3, "data", rights = P1.RIGHT_FD_WRITE))
        assertEquals(-Errno.INVAL.code, p.open(3, "data/x", oflags = P1.OFLAGS_CREAT or P1.OFLAGS_DIRECTORY))

        // フラグの検査
        assertEquals(-Errno.NOTSUP.code, p.open(3, "data/a.txt", fdflags = P1.FDFLAGS_DSYNC))
        assertEquals(-Errno.NOTSUP.code, p.open(3, "data/a.txt", fdflags = P1.FDFLAGS_RSYNC))
        assertEquals(-Errno.NOTSUP.code, p.open(3, "data/a.txt", fdflags = P1.FDFLAGS_SYNC))
        assertEquals(-Errno.INVAL.code, p.open(3, "data/a.txt", fdflags = 0x20))
        assertEquals(-Errno.INVAL.code, p.open(3, "data/a.txt", oflags = 0x10))
        assertEquals(-Errno.INVAL.code, p.open(3, "data/a.txt", lookup = 2))
        assertTrue(p.open(3, "data/a.txt", lookup = P1.LOOKUPFLAGS_SYMLINK_FOLLOW) >= 0)

        // 読み取り専用のマウント
        assertEquals(-Errno.ROFS.code, p.open(3, "ro/f", rights = P1.RIGHT_FD_WRITE))
        assertEquals(-Errno.ROFS.code, p.open(3, "ro/g", oflags = P1.OFLAGS_CREAT, rights = P1.RIGHT_FD_WRITE))
        assertTrue(p.open(3, "ro/f") >= 0)
    }

    @Test
    fun `基点の外へは出られず、基点はディレクトリのfd`() = run { _, _, p ->
        assertEquals(-Errno.NOTCAPABLE.code, p.open(3, "/data/a.txt"))
        assertEquals(-Errno.NOTCAPABLE.code, p.open(4, "../data/a.txt"))
        assertEquals(-Errno.NOTCAPABLE.code, p.open(4, "sub/../../data/a.txt"))
        assertEquals(-Errno.NOTCAPABLE.code, p.open(3, ".."))
        assertEquals(Errno.NOTCAPABLE.code, p.mkdir(4, "../x"))
        assertEquals(-Errno.NOTDIR.code, p.open(5, "x"))
        assertEquals(-Errno.BADF.code, p.open(9, "x"))
        assertEquals(Errno.BADF.code, p.mkdir(9, "x"))
        assertEquals(-Errno.NOENT.code, p.open(3, ""))
        // 基点そのもの
        assertTrue(p.open(4, ".") >= 0)
    }

    @Test
    fun `mkdir・rmdir・unlink`() = run { m, _, p ->
        assertEquals(0, p.mkdir(4, "d1"))
        assertTrue(m.exists("/data/d1"))
        assertEquals(Errno.EXIST.code, p.mkdir(4, "d1"))
        assertEquals(0, p.mkdir(4, "d2/"))
        assertEquals(Errno.EXIST.code, p.mkdir(4, "."))
        assertEquals(Errno.EXIST.code, p.mkdir(4, "sub/.."))
        assertEquals(Errno.NOENT.code, p.mkdir(4, "none/x"))
        assertEquals(Errno.NOTDIR.code, p.mkdir(4, "a.txt/x"))
        assertEquals(Errno.ROFS.code, p.mkdir(3, "ro/x"))

        // rmdir
        assertEquals(0, p.mkdir(4, "d1/inner"))
        assertEquals(Errno.NOTEMPTY.code, p.rmdir(4, "d1"))
        assertEquals(Errno.INVAL.code, p.rmdir(4, "d1/inner/."))
        assertEquals(Errno.NOTEMPTY.code, p.rmdir(4, "d1/inner/.."))
        assertEquals(0, p.rmdir(4, "d1/inner"))
        assertEquals(0, p.rmdir(4, "d1/"))
        assertTrue(!m.exists("/data/d1"))
        assertEquals(Errno.NOTDIR.code, p.rmdir(4, "a.txt"))
        assertEquals(Errno.NOENT.code, p.rmdir(4, "d1"))
        assertEquals(Errno.BUSY.code, p.rmdir(3, "mnt"))

        // unlink
        assertEquals(Errno.ISDIR.code, p.unlink(4, "sub"))
        assertEquals(Errno.NOTDIR.code, p.unlink(4, "a.txt/"))
        assertEquals(Errno.ROFS.code, p.unlink(3, "ro/f"))
        assertEquals(0, p.unlink(4, "a.txt"))
        assertTrue(!m.exists("/data/a.txt"))
        assertEquals(Errno.NOENT.code, p.unlink(4, "a.txt"))
    }

    @Test
    fun `rename`() = run { m, _, p ->
        assertEquals(0, p.rename(4, "a.txt", 3, "data/b.txt"))
        assertTrue(!m.exists("/data/a.txt"))
        assertEquals("hello", m.content("/data/b.txt"))
        // 置き換え
        assertEquals(0, p.mkdir(4, "d"))
        val f = p.open(4, "c.txt", oflags = P1.OFLAGS_CREAT, rights = P1.RIGHT_FD_WRITE)
        assertEquals(0, p.write(f, "C"))
        assertEquals(0, p.rename(4, "c.txt", 4, "b.txt"))
        assertEquals("C", m.content("/data/b.txt"))
        assertEquals(0, p.rename(4, "d", 4, "e/"))
        assertTrue(m.exists("/data/e"))
        // 末尾の / は、元がディレクトリでなければ NOTDIR
        assertEquals(Errno.NOTDIR.code, p.rename(4, "b.txt/", 4, "x"))
        assertEquals(Errno.NOTDIR.code, p.rename(4, "b.txt", 4, "x/"))
        // . と ..
        assertEquals(Errno.INVAL.code, p.rename(4, "e/.", 4, "x"))
        assertEquals(Errno.INVAL.code, p.rename(4, "e", 4, "sub/.."))
        // マウント
        assertEquals(Errno.XDEV.code, p.rename(4, "b.txt", 3, "mnt/b.txt"))
        assertEquals(Errno.BUSY.code, p.rename(3, "mnt", 3, "mnt2"))
        assertEquals(Errno.ROFS.code, p.rename(3, "ro/f", 3, "ro/g"))
        assertEquals(Errno.NOENT.code, p.rename(4, "none", 4, "x"))
        assertEquals(Errno.NOTCAPABLE.code, p.rename(4, "b.txt", 4, "../b.txt"))
    }

    @Test
    fun `filestat_getとset_times`() = run { m, ctx, p ->
        assertEquals(0, p.stat(4, "a.txt"))
        assertEquals(P1.FILETYPE_REGULAR_FILE, p.statType)
        assertEquals(5L, p.statSize)
        assertEquals(0, p.stat(3, "data/"))
        assertEquals(P1.FILETYPE_DIRECTORY, p.statType)
        assertEquals(0, p.stat(3, "mnt"))
        assertEquals(P1.FILETYPE_DIRECTORY, p.statType)
        assertEquals(Errno.NOTDIR.code, p.stat(4, "a.txt/"))
        assertEquals(Errno.NOENT.code, p.stat(4, "none"))
        assertEquals(Errno.INVAL.code, p.stat(4, "a.txt", lookup = 4))

        fun setTimes(dir: Int, path: String, atim: Long, mtim: Long, flags: Int): Int {
            val (ptr, len) = p.s.put(path)
            return p.call("path_filestat_set_times", dir.toLong(), 0, ptr, len, atim, mtim, flags.toLong())
        }
        assertEquals(0, setTimes(4, "a.txt", 111, 222, P1.FSTFLAGS_ATIM or P1.FSTFLAGS_MTIM))
        assertEquals(0, p.stat(4, "a.txt"))
        assertEquals(111L, p.statAtim)
        assertEquals(222L, p.statMtim)
        assertEquals(0, setTimes(4, "a.txt", 0, 0, P1.FSTFLAGS_MTIM_NOW))
        assertEquals(0, p.stat(4, "a.txt"))
        assertEquals(111L, p.statAtim)
        assertTrue(p.statMtim > 222L, "MTIM_NOW で実時間が入る")
        assertEquals(Errno.INVAL.code, setTimes(4, "a.txt", 0, 0, P1.FSTFLAGS_ATIM or P1.FSTFLAGS_ATIM_NOW))
        assertEquals(Errno.INVAL.code, setTimes(4, "a.txt", 0, 0, 0x10))
        assertEquals(Errno.ROFS.code, setTimes(3, "ro/f", 1, 1, P1.FSTFLAGS_ATIM))
        assertEquals(Errno.NOENT.code, setTimes(4, "none", 1, 1, P1.FSTFLAGS_ATIM))
        assertEquals(Errno.NOTDIR.code, setTimes(4, "a.txt/", 1, 1, P1.FSTFLAGS_ATIM))
        // 何も変えないときは、あるか確かめるだけ（読み取り専用でもよい）
        assertEquals(0, setTimes(3, "ro/f", 0, 0, 0))
        assertEquals(FileType.REGULAR_FILE, m.lock.withLockValue { m.vfs.stat(VfsPath.parse("/data/a.txt")).type })
        assertTrue(ctx.pid > 0)
    }

    @Test
    fun `readlink・symlink・link`() = run { _, _, p ->
        fun readlink(dir: Int, path: String): Int {
            val (ptr, len) = p.s.put(path)
            return p.call("path_readlink", dir.toLong(), ptr, len, P.BUF.toLong(), 64, P.OUT.toLong())
        }
        assertEquals(Errno.INVAL.code, readlink(4, "a.txt"))
        assertEquals(Errno.INVAL.code, readlink(4, "sub"))
        assertEquals(Errno.NOENT.code, readlink(4, "none"))
        assertEquals(Errno.NOTCAPABLE.code, readlink(4, "/data"))
        val (pp, pl) = p.s.put("a.txt")
        assertEquals(Errno.FAULT.code, p.call("path_readlink", 4, pp, pl, WasmMemory.PAGE_SIZE - 4L, 64, P.OUT.toLong()))

        val (op, ol) = p.s.put("a.txt")
        val (np, nl) = p.s.put("link")
        assertEquals(Errno.NOTSUP.code, p.call("path_symlink", op, ol, 4, np, nl))
        assertEquals(Errno.BADF.code, p.call("path_symlink", op, ol, 9, np, nl))
        assertEquals(Errno.NOTDIR.code, p.call("path_symlink", op, ol, 5, np, nl))
        assertEquals(Errno.NOTSUP.code, p.call("path_link", 4, 0, op, ol, 4, np, nl))
        assertEquals(Errno.BADF.code, p.call("path_link", 4, 0, op, ol, 9, np, nl))
        assertEquals(Errno.INVAL.code, p.call("path_link", 4, 2, op, ol, 4, np, nl))
    }

    @Test
    fun `範囲外のポインタ・壊れたUTF-8・長すぎるパス`() = run { m, _, p ->
        val end = WasmMemory.PAGE_SIZE.toLong()
        assertEquals(Errno.FAULT.code, p.call("path_create_directory", 4, end - 2, 4))
        assertEquals(Errno.FAULT.code, p.call("path_create_directory", 4, -1, 4))
        // 出力の範囲外: 何も作らない
        val (np, nl) = p.s.put("made.txt")
        val e = p.call("path_open", 4, 0, np, nl, P1.OFLAGS_CREAT.toLong(), P1.RIGHT_FD_WRITE, 0, 0, end - 2)
        assertEquals(Errno.FAULT.code, e)
        assertTrue(!m.exists("/data/made.txt"))
        val (sp, sl) = p.s.put("a.txt")
        assertEquals(Errno.FAULT.code, p.call("path_filestat_get", 4, 0, sp, sl, end - 10))
        // 2 つ目のパスが範囲外の rename は、何も変えない
        assertEquals(Errno.FAULT.code, p.call("path_rename", 4, sp, sl, 4, end - 1, 8))
        assertTrue(m.exists("/data/a.txt"))

        val (bp, bl) = p.s.put(byteArrayOf('x'.code.toByte(), 0xff.toByte()))
        assertEquals(Errno.ILSEQ.code, p.call("path_create_directory", 4, bp, bl))
        val (cp, cl) = p.s.put(byteArrayOf(0xc3.toByte()))
        assertEquals(Errno.ILSEQ.code, p.call("path_create_directory", 4, cp, cl))
        // UTF-8 の名前は使える
        assertEquals(0, p.mkdir(4, "日本語"))
        assertTrue(m.exists("/data/日本語"))

        assertEquals(Errno.NAMETOOLONG.code, p.call("path_create_directory", 4, 0, VfsPath.MAX_PATH_BYTES + 1L))
        assertEquals(Errno.NAMETOOLONG.code, p.call("path_create_directory", 4, 0, -1))
        assertEquals(Errno.NAMETOOLONG.code, p.mkdir(4, "n".repeat(VfsPath.MAX_NAME_BYTES + 1)))
        assertEquals(Errno.INVAL.code, p.mkdir(4, "a\u0000b"))
    }

    @Test
    fun `fd表が満杯ならMFILEで、O_TRUNCしても中身が残る`() = run { m, _, p ->
        var opened = 0
        while (true) {
            val r = p.open(4, ".")
            if (r < 0) {
                assertEquals(-Errno.MFILE.code, r)
                break
            }
            opened++
            assertTrue(opened < 10_000)
        }
        val before = m.lock.withLockValue { m.kernel.objects.size }
        assertEquals(-Errno.MFILE.code, p.open(4, "a.txt", oflags = P1.OFLAGS_TRUNC, rights = P1.RIGHT_FD_WRITE))
        assertEquals("hello", m.content("/data/a.txt"))
        assertEquals(-Errno.MFILE.code, p.open(4, "new.txt", oflags = P1.OFLAGS_CREAT, rights = P1.RIGHT_FD_WRITE))
        assertTrue(!m.exists("/data/new.txt"))
        assertEquals(before, m.lock.withLockValue { m.kernel.objects.size })
        // 1つ閉じれば開ける
        assertEquals(0, p.call("fd_close", 7))
        assertEquals(7, p.open(4, "a.txt", oflags = P1.OFLAGS_TRUNC, rights = P1.RIGHT_FD_WRITE))
        assertEquals("", m.content("/data/a.txt"))
    }

    @Test
    fun `fdのファイルを閉じると、オブジェクトが表から消える`() = run { m, _, p ->
        val before = m.lock.withLockValue { m.kernel.objects.size }
        val fd = p.open(4, "a.txt")
        assertEquals(before + 1, m.lock.withLockValue { m.kernel.objects.size })
        assertEquals(0, p.call("fd_close", fd.toLong()))
        assertEquals(before, m.lock.withLockValue { m.kernel.objects.size })
        assertArrayEquals("hello".toByteArray(), m.content("/data/a.txt").toByteArray())
    }
}
