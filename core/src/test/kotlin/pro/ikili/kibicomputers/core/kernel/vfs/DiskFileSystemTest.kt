package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger

/** ホストのディレクトリのディスク（#52。ADR 0052） */
class DiskFileSystemTest {
    @TempDir
    lateinit var dir: Path

    private val opened = ArrayList<DiskFileSystem>()

    private fun disk(
        name: String = "disk",
        quota: DiskQuota = DiskQuota(),
        budget: HostFileBudget = HostFileBudget(8, 16, AtomicInteger()),
    ): DiskFileSystem = DiskFileSystem.open(dir.resolve(name), quota, budget = budget).also { opened += it }

    @AfterEach
    fun closeAll() {
        for (fs in opened) fs.retire()
    }

    private fun p(s: String) = VfsPath.parse(s)

    private fun errno(errno: Errno, block: () -> Unit) {
        val e = assertThrows<FsException> { block() }
        assertEquals(errno, e.errno, e.message)
    }

    private fun FileSystem.writeAll(path: String, text: String): Long {
        val node = try {
            stat(p(path)).node
        } catch (e: FsException) {
            createFile(p(path))
        }
        setSize(node, 0)
        val bytes = text.toByteArray()
        assertEquals(bytes.size, write(node, 0, bytes, 0, bytes.size))
        return node
    }

    private fun FileSystem.readAll(node: Long): String {
        val buf = ByteArray(statNode(node).size.toInt())
        val n = read(node, 0, buf, 0, buf.size)
        return String(buf, 0, n)
    }

    @Test
    fun `書いたものはすぐホストに見え、外で書いたものもすぐ見える`() {
        val fs = disk()
        fs.createDirectory(p("/a"))
        val node = fs.writeAll("/a/f.txt", "hello")
        assertEquals("hello", Files.readString(dir.resolve("disk/a/f.txt")))
        Files.writeString(dir.resolve("disk/a/f.txt"), "outside!")
        assertEquals("outside!", fs.readAll(node))
        Files.writeString(dir.resolve("disk/a/new.txt"), "x")
        assertEquals(listOf("f.txt", "new.txt"), fs.readDirectory(fs.stat(p("/a")).node).map { it.name })
        // 管理用のファイルを作らない
        Files.list(dir.resolve("disk")).use { s -> assertEquals(listOf("a"), s.map { it.fileName.toString() }.toList()) }
    }

    @Test
    fun `開いたままでも外での書き換えが見え、外で消すとSTALE`() {
        val fs = disk()
        val node = fs.writeAll("/f", "one")
        fs.retain(node, writable = true)
        assertEquals(1, fs.openHandles)
        Files.writeString(dir.resolve("disk/f"), "two")
        assertEquals("two", fs.readAll(node))
        fs.release(node)
        assertEquals(0, fs.openHandles)
        Files.delete(dir.resolve("disk/f"))
        errno(Errno.STALE) { fs.statNode(node) }
        errno(Errno.STALE) { fs.read(node, 0, ByteArray(1), 0, 1) }
    }

    @Test
    fun `開いたままのファイルを外で消す・置き換えることができる`() {
        // Windows でも、開いたままのファイルは削除の共有付きで開くので、外のエディタの保存（置き換え）と削除が通る
        val fs = disk()
        val node = fs.writeAll("/f", "one")
        fs.retain(node, writable = false)
        val tmp = dir.resolve("disk/f.tmp")
        Files.writeString(tmp, "replaced")
        Files.move(tmp, dir.resolve("disk/f"), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("replaced", fs.readAll(fs.stat(p("/f")).node))
        fs.release(node)
        val other = fs.writeAll("/g", "g")
        fs.retain(other, writable = true)
        Files.delete(dir.resolve("disk/g"))
        assertFalse(Files.exists(dir.resolve("disk/g")))
        fs.release(other)
    }

    @Test
    fun `ゲームの中で開いたままのファイルを消し、名前を変えられる`() {
        val fs = disk()
        val a = fs.writeAll("/a", "a")
        val b = fs.writeAll("/b", "b")
        fs.retain(a, writable = true)
        fs.retain(b, writable = true)
        fs.removeFile(p("/a"))
        assertFalse(Files.exists(dir.resolve("disk/a")))
        errno(Errno.STALE) { fs.statNode(a) }
        fs.rename(p("/b"), p("/c"))
        assertEquals(b, fs.stat(p("/c")).node, "名前を変えても node は変わらない")
        assertEquals("b", fs.readAll(b))
        assertEquals(p("/c"), fs.pathOf(b))
        fs.release(a)
        fs.release(b)
        assertEquals(0, fs.openHandles)
    }

    @Test
    fun `ディレクトリの名前を変えると下のnodeも付け替わる`() {
        val fs = disk()
        fs.createDirectory(p("/d"))
        val f = fs.writeAll("/d/f", "x")
        fs.rename(p("/d"), p("/e"))
        assertEquals(p("/e/f"), fs.pathOf(f))
        assertEquals(f, fs.stat(p("/e/f")).node)
        errno(Errno.NOENT) { fs.stat(p("/d/f")) }
        // 置き換え: 空のディレクトリへは置き換えられ、ファイルとディレクトリは置き換えられない
        fs.createDirectory(p("/empty"))
        fs.createDirectory(p("/full"))
        fs.writeAll("/full/x", "1")
        errno(Errno.NOTEMPTY) { fs.rename(p("/e"), p("/full")) }
        errno(Errno.ISDIR) { fs.rename(p("/full/x"), p("/empty")) }
        errno(Errno.NOTDIR) { fs.rename(p("/empty"), p("/full/x")) }
        errno(Errno.NOTDIR) { fs.rename(p("/full/x"), p("/y"), requireDirectory = true) }
        fs.rename(p("/e"), p("/empty"))
        assertEquals("x", fs.readAll(fs.stat(p("/empty/f")).node))
    }

    @Test
    fun `Windowsで使えない名前はどのOSでもINVALで、引くとNOENT`() {
        val fs = disk()
        for (name in listOf("CON", "nul", "Com1", "LPT9.txt", "aux .log", "a<b", "a:b", "a\"b", "a\\b", "a|b", "a?b", "a*b", "a\u0001", "end.", "end ")) {
            errno(Errno.INVAL) { fs.createFile(p("/").child(name)) }
            errno(Errno.INVAL) { fs.createDirectory(p("/").child(name)) }
            errno(Errno.NOENT) { fs.stat(p("/").child(name)) }
        }
        fs.writeAll("/ok", "1")
        errno(Errno.INVAL) { fs.rename(p("/ok"), p("/PRN")) }
        assertTrue(DiskNames.isValid("console"))
        assertTrue(DiskNames.isValid("COM10"))
    }

    @Test
    fun `大文字小文字だけ違う名前は同じ名前`() {
        val fs = disk()
        val node = fs.writeAll("/foo", "x")
        errno(Errno.EXIST) { fs.createFile(p("/FOO")) }
        errno(Errno.EXIST) { fs.createDirectory(p("/Foo")) }
        assertEquals(node, fs.stat(p("/FOO")).node)
        // 大文字小文字だけの名前の変更
        fs.rename(p("/foo"), p("/Foo"))
        assertEquals(listOf("Foo"), fs.readDirectory(DiskFileSystem.ROOT_NODE).map { it.name })
        Files.list(dir.resolve("disk")).use { s -> assertEquals(listOf("Foo"), s.map { it.fileName.toString() }.toList()) }
        assertEquals(node, fs.stat(p("/foo")).node)
    }

    @Test
    fun `大文字小文字だけ違う名前が外から2つ置かれたら、名前の順で先のものだけ見える`() {
        val d = Files.createDirectories(dir.resolve("disk"))
        Files.writeString(d.resolve("a"), "lower")
        val upper = d.resolve("A")
        assumeTrue(runCatching { Files.writeString(upper, "upper") }.isSuccess && Files.list(d).use { it.count() } == 2L, "大文字小文字を区別するホストだけ")
        val fs = disk()
        assertEquals(listOf("A"), fs.readDirectory(DiskFileSystem.ROOT_NODE).map { it.name })
    }

    @Test
    fun `外から置かれた使えない名前は一覧に出ない`() {
        val d = Files.createDirectories(dir.resolve("disk"))
        Files.writeString(d.resolve("ok.txt"), "1")
        assumeTrue(runCatching { Files.writeString(d.resolve("bad:name"), "1") }.isSuccess, "その名前を作れるホストだけ")
        val fs = disk()
        assertEquals(listOf("ok.txt"), fs.readDirectory(DiskFileSystem.ROOT_NODE).map { it.name })
    }

    @Test
    fun `シンボリックリンクは見せず、辿って外へ出られない`() {
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.writeString(outside.resolve("secret"), "s")
        val d = Files.createDirectories(dir.resolve("disk"))
        assumeTrue(runCatching { Files.createSymbolicLink(d.resolve("link"), outside) }.isSuccess, "シンボリックリンクを作れるホストだけ")
        val fs = disk()
        assertTrue(fs.readDirectory(DiskFileSystem.ROOT_NODE).isEmpty())
        errno(Errno.NOENT) { fs.stat(p("/link")) }
        errno(Errno.NOENT) { fs.stat(p("/link/secret")) }
    }

    @Test
    fun `容量は中身と項目ごとの分で数え、超えると短い書き込みとNOSPC`() {
        val fs = disk(quota = DiskQuota(maxBytes = 1000, maxEntries = 3))
        assertEquals(0, fs.usedBytes)
        val node = fs.createFile(p("/f"))
        assertEquals(DiskFileSystem.ENTRY_COST, fs.usedBytes)
        val big = ByteArray(2000) { 7 }
        val n = fs.write(node, 0, big, 0, big.size)
        assertEquals(1000 - DiskFileSystem.ENTRY_COST.toInt(), n, "入る分だけ書く")
        errno(Errno.NOSPC) { fs.write(node, n.toLong(), big, 0, 1) }
        errno(Errno.NOSPC) { fs.allocate(node, 5000) }
        errno(Errno.NOSPC) { fs.createFile(p("/g")) }
        // 縮めれば空く。上書き（伸ばさない書き込み）は数えない
        fs.setSize(node, 0)
        assertEquals(DiskFileSystem.ENTRY_COST, fs.usedBytes)
        fs.createDirectory(p("/d"))
        fs.createFile(p("/d/x"))
        errno(Errno.NOSPC) { fs.createFile(p("/d/y")) }
        assertEquals(3, fs.usedEntries)
        fs.removeFile(p("/d/x"))
        assertEquals(2, fs.usedEntries)
    }

    @Test
    fun `マウントのときに数え直し、外で上限を超えて置かれていても読める`() {
        val d = Files.createDirectories(dir.resolve("disk"))
        Files.createDirectories(d.resolve("sub"))
        Files.write(d.resolve("sub/big"), ByteArray(3000))
        val fs = disk(quota = DiskQuota(maxBytes = 1000, maxEntries = 10))
        assertEquals(3000 + 2 * DiskFileSystem.ENTRY_COST, fs.usedBytes)
        assertEquals(2, fs.usedEntries)
        val node = fs.stat(p("/sub/big")).node
        assertEquals(10, fs.read(node, 0, ByteArray(10), 0, 10))
        errno(Errno.NOSPC) { fs.write(node, 3000, ByteArray(1), 0, 1) }
        assertEquals(1, fs.write(node, 0, ByteArray(1), 0, 1), "伸ばさない上書きはできる")
    }

    @Test
    fun `追記とallocateは不可分で、allocateは縮めない`() {
        val fs = disk()
        val node = fs.writeAll("/f", "abc")
        assertEquals(Written(3, 2), fs.append(node, "de".toByteArray(), 0, 2))
        fs.allocate(node, 2)
        assertEquals(5, fs.statNode(node).size)
        fs.allocate(node, 8)
        assertEquals(8, fs.statNode(node).size)
        assertArrayEquals("abcde\u0000\u0000\u0000".toByteArray(), Files.readAllBytes(dir.resolve("disk/f")))
        assertEquals(8, fs.sizeHint(node))
    }

    @Test
    fun `同じディレクトリを開き直すと古いほうはIOになり、revokeの後もIO`() {
        val first = disk()
        first.writeAll("/f", "x")
        val second = disk()
        errno(Errno.IO) { first.stat(p("/f")) }
        assertEquals(1, second.stat(p("/f")).size)
        second.revoke()
        errno(Errno.IO) { second.stat(p("/f")) }
    }

    @Test
    fun `取り消された開き手は世代を進めず、今の持ち主を締め出さず、取った分を返す`() {
        // #588: 取り消しはリースのロックの中で見る
        val leases = HostDiskLeases.size
        val owner = disk()
        owner.writeAll("/f", "x")
        val budget = HostFileBudget(8, 16, AtomicInteger())
        assertThrows<DiskOpenCancelledException> { DiskFileSystem.open(dir.resolve("disk"), budget = budget, cancelled = { true }) }
        assertEquals(1, owner.stat(p("/f")).size, "今の持ち主は使えるまま")
        assertEquals(leases + 1, HostDiskLeases.size)
        owner.retire()
        assertEquals(leases, HostDiskLeases.size, "断った分の users を戻していない")
    }

    @Test
    fun `開いたままのファイルの数はマシンとサーバー全体の枠で数える`() {
        val total = AtomicInteger()
        val a = disk("a", budget = HostFileBudget(perMachine = 2, totalLimit = 3, total = total))
        val b = disk("b", budget = HostFileBudget(perMachine = 2, totalLimit = 3, total = total))
        val nodes = (1..3).map { a.writeAll("/f$it", "x") }
        a.retain(nodes[0], writable = false)
        a.retain(nodes[0], writable = true)
        assertEquals(1, a.openHandles, "同じ node は1つを分け合う")
        a.retain(nodes[1], writable = false)
        errno(Errno.MFILE) { a.retain(nodes[2], writable = false) }
        val bn = (1..2).map { b.writeAll("/g$it", "y") }
        b.retain(bn[0], writable = false)
        errno(Errno.NFILE) { b.retain(bn[1], writable = false) }
        assertEquals(3, total.get())
        a.release(nodes[0])
        assertEquals(2, a.openHandles, "参照が残っている間は閉じない")
        a.release(nodes[0])
        a.retire()
        assertEquals(1, total.get(), "外したら閉じる")
        b.release(bn[0])
        assertEquals(0, total.get())
    }

    @Test
    fun `開いたファイルの上限でopenが失敗したら、作成も切り詰めもしない`() {
        val budget = HostFileBudget(perMachine = 1, totalLimit = 16, total = AtomicInteger())
        val fs = disk(budget = budget)
        fs.writeAll("/keep", "hello")
        fs.writeAll("/held", "x")
        val vfs = Vfs()
        vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "disk")
        val held = vfs.open(p("/held"), OpenOptions(read = true))
        assertEquals(1, budget.inUse)
        // 作る open（#434）: 枠がなければ、ファイルを作らない
        errno(Errno.MFILE) { vfs.open(p("/new"), OpenOptions(read = true, write = true, create = true)) }
        assertFalse(Files.exists(dir.resolve("disk").resolve("new")), "上限で失敗した open がファイルを作った")
        // 切り詰める open: 枠がなければ、中身を変えない
        errno(Errno.MFILE) { vfs.open(p("/keep"), OpenOptions(write = true, truncate = true)) }
        assertEquals("hello", Files.readString(dir.resolve("disk").resolve("keep")))
        assertEquals(1, budget.inUse, "失敗した open が枠を残した")
        // 同じ node を分け合うなら、枠は要らない（予約した枠は返す）
        val again = vfs.open(p("/held"), OpenOptions(read = true, write = true, truncate = true))
        assertEquals(1, budget.inUse)
        assertEquals(0, vfs.size(again))
        vfs.close(again)
        vfs.close(held)
        assertEquals(0, budget.inUse)
        // 枠が空けば作れる。O_EXCL で EXIST になっても枠は残さない
        val created = vfs.open(p("/new"), OpenOptions(read = true, write = true, create = true))
        assertEquals(1, budget.inUse)
        vfs.close(created)
        errno(Errno.EXIST) { vfs.open(p("/new"), OpenOptions(write = true, create = true, exclusive = true)) }
        assertEquals(0, budget.inUse)
    }

    // 作る open で createFile が失敗したとき、予約した枠を返す経路（Vfs.openOnce の catch 2つ。#469）

    /** [inner] の createFile を [create] で差し替えた包み。ほかは [inner]（本物の枠の数え方）に委ねる */
    private fun failingCreate(inner: DiskFileSystem, create: (VfsPath) -> Long): FileSystem = object : FileSystem by inner {
        override fun createFile(path: VfsPath): Long = create(path)
    }

    private fun mounted(fs: FileSystem): Vfs = Vfs().also { it.mount(VfsPath.ROOT, fs, readOnly = false, source = "disk") }

    private val create = OpenOptions(read = true, write = true, create = true)

    @Test
    fun `ほかのスレッドが先に作ってEXISTになったら、枠を返して開き直す`() {
        val total = AtomicInteger()
        val budget = HostFileBudget(perMachine = 1, totalLimit = 16, total = total)
        val inner = disk(budget = budget)
        // 1回目の作成を「ほかのスレッド」が先に済ませたことにする（2回目が EXIST）
        val vfs = mounted(failingCreate(inner) { path -> inner.createFile(path); inner.createFile(path) })
        // マシンの枠は1つ。予約を返し忘れると、開き直す retain が MFILE になる
        val file = vfs.open(p("/new"), create)
        assertEquals(1, budget.inUse, "予約した枠が残った")
        assertEquals(1, total.get())
        vfs.close(file)
        assertEquals(0, budget.inUse)
        assertEquals(0, total.get())
    }

    @Test
    fun `O_EXCLでほかのスレッドが先に作ってEXISTになったら、枠を返して失敗する`() {
        val total = AtomicInteger()
        val budget = HostFileBudget(perMachine = 1, totalLimit = 16, total = total)
        val inner = disk(budget = budget)
        val vfs = mounted(failingCreate(inner) { path -> inner.createFile(path); inner.createFile(path) })
        errno(Errno.EXIST) { vfs.open(p("/new"), OpenOptions(read = true, write = true, create = true, exclusive = true)) }
        assertEquals(0, budget.inUse, "失敗した open が枠を残した")
        assertEquals(0, total.get())
        // 枠が残っていないので、次の open は開ける
        val file = vfs.open(p("/new"), OpenOptions(read = true))
        assertEquals(1, budget.inUse)
        vfs.close(file)
        assertEquals(0, budget.inUse)
    }

    @Test
    fun `作るときにNOSPCになったら、枠を返してファイルを作らない`() {
        val total = AtomicInteger()
        val budget = HostFileBudget(perMachine = 1, totalLimit = 16, total = total)
        val inner = disk(quota = DiskQuota(maxEntries = 1), budget = budget)
        inner.writeAll("/full", "x")
        val vfs = mounted(inner)
        errno(Errno.NOSPC) { vfs.open(p("/new"), create) }
        assertFalse(Files.exists(dir.resolve("disk").resolve("new")))
        assertEquals(0, budget.inUse, "失敗した open が枠を残した")
        assertEquals(0, total.get())
        // 枠が残っていないので、あるファイルは開ける
        val file = vfs.open(p("/full"), OpenOptions(read = true))
        assertEquals(1, budget.inUse)
        vfs.close(file)
        assertEquals(0, budget.inUse)
    }

    @Test
    fun `作るときにIOになったら、枠を返して失敗する`() {
        val total = AtomicInteger()
        val budget = HostFileBudget(perMachine = 1, totalLimit = 16, total = total)
        val inner = disk(budget = budget)
        val vfs = mounted(failingCreate(inner) { fsError(Errno.IO, "壊れた") })
        errno(Errno.IO) { vfs.open(p("/new"), create) }
        assertEquals(0, budget.inUse, "失敗した open が枠を残した")
        assertEquals(0, total.get())
    }

    @Test
    fun `作るときにFsException以外の例外が出ても、枠を返して投げ直す`() {
        val total = AtomicInteger()
        val budget = HostFileBudget(perMachine = 1, totalLimit = 16, total = total)
        val inner = disk(budget = budget)
        val vfs = mounted(failingCreate(inner) { error("想定外") })
        val e = assertThrows<IllegalStateException> { vfs.open(p("/new"), create) }
        assertEquals("想定外", e.message)
        assertEquals(0, budget.inUse, "失敗した open が枠を残した")
        assertEquals(0, total.get())
    }

    @Test
    fun `最後の試行までEXISTが続いたら、どの試行の枠も返してEXISTで失敗する`() {
        val total = AtomicInteger()
        val budget = HostFileBudget(perMachine = 1, totalLimit = 16, total = total)
        val inner = disk(budget = budget)
        val target = p("/new")
        var reserves = 0
        var cancels = 0
        // 見るときはいつもなく、作るときはいつもある（ほかのスレッドが作っては消し続ける）
        val fs = object : FileSystem by inner {
            override fun stat(path: VfsPath): FileStat = if (path == target) fsError(Errno.NOENT) else inner.stat(path)
            override fun createFile(path: VfsPath): Long = fsError(Errno.EXIST)
            override fun reserveOpen() {
                reserves++
                inner.reserveOpen()
            }
            override fun cancelOpen() {
                cancels++
                inner.cancelOpen()
            }
        }
        val vfs = mounted(fs)
        errno(Errno.EXIST) { vfs.open(target, create) }
        assertEquals(Vfs.MAX_RETRIES, reserves)
        assertEquals(Vfs.MAX_RETRIES, cancels)
        assertEquals(0, budget.inUse, "失敗した open が枠を残した")
        assertEquals(0, total.get())
    }

    @Test
    fun `割り込まれたスレッドの操作はIOになり、ほかのスレッドは同じファイルを使い続けられる`() {
        val fs = disk()
        val node = fs.writeAll("/f", "hello")
        fs.retain(node, writable = true)
        var interrupted: FsException? = null
        val t = Thread {
            Thread.currentThread().interrupt()
            interrupted = runCatching { fs.read(node, 0, ByteArray(5), 0, 5) }.exceptionOrNull() as? FsException
        }
        t.start()
        t.join()
        assertEquals(Errno.IO, interrupted?.errno)
        assertEquals("hello", fs.readAll(node), "開き直して読める")
        fs.release(node)
    }

    @Test
    fun `時刻はホストの属性で、変えられる`() {
        val fs = disk()
        val node = fs.writeAll("/f", "x")
        fs.setTimes(node, 2_000_000_000L * 1_000_000_000L / 1000, 1_500_000_000_000_000_000L)
        val st = fs.statNode(node)
        assertEquals(1_500_000_000_000_000_000L / 1000 * 1000, st.modifiedNanos / 1000 * 1000)
        assertEquals(st.modifiedNanos, st.changedNanos)
        assertNull(fs.pathOf(12345))
    }

    @Test
    fun `pathHintはロックを取らずにFSの中のパスを返し、名前を変えると追う`() {
        val fs = disk()
        fs.createDirectory(p("/a"))
        val node = fs.writeAll("/a/f.txt", "hello")
        assertEquals(p("/a/f.txt"), fs.pathHint(node))
        assertEquals(VfsPath.ROOT, fs.pathHint(fs.stat(VfsPath.ROOT).node))
        fs.rename(p("/a"), p("/b"), false)
        assertEquals(p("/b/f.txt"), fs.pathHint(fs.stat(p("/b/f.txt")).node))
        fs.removeFile(p("/b/f.txt"))
        assertNull(fs.pathHint(12345))
    }
    @Test
    fun `保存した開いたファイルのnodeを登録すると読め、外で消すとSTALE（#85）`() {
        Files.createDirectories(dir.resolve("disk/a"))
        Files.writeString(dir.resolve("disk/a/f.txt"), "saved")
        val fs = disk()
        // I/O をしない登録。番号は保存したもの
        assertTrue(fs.adoptNode(42, p("/a/f.txt"), FileType.REGULAR_FILE))
        assertTrue(fs.adoptNode(42, p("/a/f.txt"), FileType.REGULAR_FILE), "同じものは何度でも")
        assertFalse(fs.adoptNode(42, p("/a/g.txt"), FileType.REGULAR_FILE), "同じ番号の別のパスは断る")
        assertFalse(fs.adoptNode(43, p("/a/f.txt"), FileType.REGULAR_FILE), "同じパスの別の番号は断る")
        assertTrue(fs.adoptNode(1, VfsPath.ROOT, FileType.DIRECTORY))
        assertEquals("saved", fs.readAll(42))
        assertEquals(p("/a/f.txt"), fs.pathHint(42))
        // 後で作るファイルは、登録した番号と重ならない
        val created = fs.writeAll("/a/new.txt", "x")
        assertTrue(created > 42)
        // 外で消したら STALE
        assertTrue(fs.adoptNode(50, p("/a/gone.txt"), FileType.REGULAR_FILE))
        errno(Errno.STALE) { fs.read(50, 0, ByteArray(1), 0, 1) }
    }

    // ---- #433: 名前の引き方と、大文字小文字の端のケース ----

    private fun hostNames(sub: String = ""): List<String> =
        Files.list(dir.resolve("disk").resolve(sub)).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `Windowsの特殊な名前と8点3形式の短い名前の形は、どのOSでも使えない`() {
        for (name in listOf("CONIN$", "conout$", "Clock$.txt", "conin$ .log", "ABCDEF~1", "ab12~1.txt", "~1", "A~12.TXT", "abcdef~9.c")) {
            assertFalse(DiskNames.isValid(name), name)
        }
        for (name in listOf("conin", "CONIN$$", "foo~bar", "abcdefghi~1", "a~1.html", "a~1.b.c", "a~", "~", "a~x1", ".kibi-rename-1", "x~1y")) {
            assertTrue(DiskNames.isValid(name), name)
        }
        val fs = disk()
        for (name in listOf("CONIN$", "CLOCK$.txt", "PROGRA~1", "abcdef~1.txt")) {
            errno(Errno.INVAL) { fs.createFile(p("/").child(name)) }
            errno(Errno.NOENT) { fs.stat(p("/").child(name)) }
        }
        fs.writeAll("/ok", "1")
        errno(Errno.INVAL) { fs.rename(p("/ok"), p("/ok~1")) }
        assertEquals(listOf("ok"), hostNames())
    }

    @Test
    fun `大文字小文字をそろえる比べ方はfoldと同じ`() {
        for ((a, b) in listOf("abc" to "ABC", "Straße" to "STRAßE", "ǆ" to "Ǆ", "a" to "b", "ab" to "abc", "ﬀ" to "FF")) {
            assertEquals(DiskNames.fold(a) == DiskNames.fold(b), DiskNames.sameName(a, b), "$a / $b")
        }
    }

    @Test
    fun `Windowsの8点3形式の別名では引けず、作れない`() {
        val d = Files.createDirectories(dir.resolve("disk"))
        Files.writeString(d.resolve("abcdefghij.txt"), "long")
        assumeTrue(Files.exists(d.resolve("ABCDEF~1.TXT")), "8.3 形式の短い名前があるホストだけ")
        val fs = disk()
        errno(Errno.NOENT) { fs.stat(p("/ABCDEF~1.TXT")) }
        errno(Errno.INVAL) { fs.createFile(p("/ABCDEF~1.TXT")) }
        fs.writeAll("/x", "x")
        errno(Errno.INVAL) { fs.rename(p("/x"), p("/abcdef~1.txt")) }
        assertEquals("long", Files.readString(d.resolve("abcdefghij.txt")))
    }

    @Test
    fun `大文字小文字だけ違う名前が2つあれば、引くときも一覧と同じ項目を選ぶ`() {
        val d = Files.createDirectories(dir.resolve("disk"))
        Files.createDirectory(d.resolve("a"))
        val upper = d.resolve("A")
        assumeTrue(runCatching { Files.writeString(upper, "upper") }.isSuccess && Files.list(d).use { it.count() } == 2L, "大文字小文字を区別するホストだけ")
        val fs = disk()
        val listed = fs.readDirectory(DiskFileSystem.ROOT_NODE).single()
        assertEquals("A", listed.name)
        assertEquals(FileType.REGULAR_FILE, listed.type)
        val st = fs.stat(p("/a"))
        assertEquals(FileType.REGULAR_FILE, st.type)
        assertEquals(listed.node, st.node)
        assertEquals(FileType.REGULAR_FILE, fs.statNode(st.node).type, "STALE にならない")
        errno(Errno.NOTDIR) { fs.stat(p("/a/x")) }
        assertEquals("upper", fs.readAll(st.node))
        assertEquals(p("/A"), fs.pathOf(st.node))
    }

    @Test
    fun `大文字小文字だけ違う名前の先がシンボリックリンクなら、見せるほうを選ぶ`() {
        val d = Files.createDirectories(dir.resolve("disk"))
        Files.writeString(d.resolve("a"), "lower")
        assumeTrue(
            runCatching { Files.createSymbolicLink(d.resolve("A"), d.resolve("a")) }.isSuccess && Files.list(d).use { it.count() } == 2L,
            "大文字小文字を区別し、リンクを作れるホストだけ",
        )
        val fs = disk()
        assertEquals(listOf("a"), fs.readDirectory(DiskFileSystem.ROOT_NODE).map { it.name })
        assertEquals("lower", fs.readAll(fs.stat(p("/A")).node))
    }

    @Test
    fun `大文字小文字だけ違う自分の下への名前の変更はINVALで、ホストに触らない`() {
        val fs = disk()
        fs.createDirectory(p("/d"))
        fs.createDirectory(p("/d/e"))
        errno(Errno.INVAL) { fs.rename(p("/d"), p("/D/x")) }
        errno(Errno.INVAL) { fs.rename(p("/d"), p("/D/E/x")) }
        errno(Errno.INVAL) { fs.rename(p("/d"), p("/D/E")) }
        assertEquals(listOf("d"), hostNames())
        assertEquals(listOf("e"), hostNames("d"))
        // 名前の頭だけが同じものは、下ではない
        fs.createDirectory(p("/dd"))
        fs.rename(p("/d"), p("/DD/x"))
        assertEquals(listOf("x"), hostNames("dd"))
    }

    @Test
    fun `大文字小文字だけ違う項目を置き換える名前の変更`() {
        val fs = disk()
        val old = fs.writeAll("/Foo", "old")
        val src = fs.writeAll("/bar", "new!")
        val entries = fs.usedEntries
        val bytes = fs.usedBytes
        fs.rename(p("/bar"), p("/foo"))
        assertEquals(listOf("foo"), hostNames())
        assertEquals("new!", Files.readString(dir.resolve("disk/foo")))
        assertEquals(src, fs.stat(p("/FOO")).node)
        assertEquals(p("/foo"), fs.pathOf(src))
        errno(Errno.STALE) { fs.statNode(old) }
        assertEquals(entries - 1, fs.usedEntries)
        assertEquals(bytes - 3 - DiskFileSystem.ENTRY_COST, fs.usedBytes)
        // ディレクトリ: 空のディレクトリを置き換える
        fs.createDirectory(p("/Dst"))
        fs.createDirectory(p("/src"))
        val inner = fs.writeAll("/src/f", "f")
        fs.rename(p("/src"), p("/dst"))
        assertEquals(listOf("dst", "foo"), hostNames())
        assertEquals(listOf("f"), hostNames("dst"))
        assertEquals(p("/dst/f"), fs.pathOf(inner))
        assertEquals("f", fs.readAll(fs.stat(p("/DST/F")).node))
    }

    @Test
    fun `置き換えるホストのmoveが失敗したら、何も変わらない`() {
        val fs = disk()
        val old = fs.writeAll("/Foo", "old")
        val src = fs.writeAll("/bar", "new!")
        fs.createDirectory(p("/Dst"))
        fs.createDirectory(p("/src"))
        fs.writeAll("/src/f", "f")
        val entries = fs.usedEntries
        val bytes = fs.usedBytes
        val before = hostNames()
        // 置き換えの move（ファイル: 1手目。ディレクトリ: src を移す2手目）を失敗させる
        fs.beforeHostMove = { from, _ -> if (from.fileName.toString() in setOf("bar", "src")) throw java.io.IOException("boom") }
        errno(Errno.IO) { fs.rename(p("/bar"), p("/foo")) }
        errno(Errno.IO) { fs.rename(p("/src"), p("/dst")) }
        fs.beforeHostMove = null
        assertEquals(before, hostNames())
        assertEquals("old", Files.readString(dir.resolve("disk/Foo")))
        assertEquals(old, fs.stat(p("/foo")).node)
        assertEquals(src, fs.stat(p("/bar")).node)
        assertEquals(listOf("f"), hostNames("src"))
        assertTrue(hostNames("Dst").isEmpty())
        assertEquals(entries, fs.usedEntries)
        assertEquals(bytes, fs.usedBytes)
    }

    @Test
    fun `置き換えた後に名前をそろえられなければ、古い大文字小文字のまま成功する`() {
        val fs = disk()
        fs.writeAll("/Foo", "old")
        val src = fs.writeAll("/bar", "new!")
        // 一時的な名前から新しい名前（foo）への move を失敗させる
        fs.beforeHostMove = { _, to -> if (to.fileName.toString() == "foo") throw java.io.IOException("boom") }
        fs.rename(p("/bar"), p("/foo"))
        fs.beforeHostMove = null
        assertEquals(listOf("Foo"), hostNames(), "一時的な名前は残らない")
        assertEquals("new!", Files.readString(dir.resolve("disk/Foo")))
        assertEquals(src, fs.stat(p("/foo")).node)
        assertEquals(p("/Foo"), fs.pathOf(src))
    }

    @Test
    fun `大文字小文字だけの名前の変更が失敗したら、元の名前に戻す`() {
        val fs = disk()
        val node = fs.writeAll("/foo", "x")
        fs.beforeHostMove = { _, to -> if (to.fileName.toString() == "Foo") throw java.io.IOException("boom") }
        errno(Errno.IO) { fs.rename(p("/foo"), p("/Foo")) }
        fs.beforeHostMove = null
        assertEquals(listOf("foo"), hostNames())
        assertEquals(node, fs.stat(p("/FOO")).node)
        assertEquals(p("/foo"), fs.pathOf(node))
    }

    @Test
    fun `見せていないホストの項目への名前の変更は、それを消さずにEXIST`() {
        val outside = Files.createDirectories(dir.resolve("outside"))
        val d = Files.createDirectories(dir.resolve("disk"))
        assumeTrue(runCatching { Files.createSymbolicLink(d.resolve("link"), outside) }.isSuccess, "シンボリックリンクを作れるホストだけ")
        val fs = disk()
        val node = fs.writeAll("/f", "x")
        errno(Errno.EXIST) { fs.rename(p("/f"), p("/link")) }
        assertTrue(Files.isSymbolicLink(d.resolve("link")))
        assertEquals(node, fs.stat(p("/f")).node)
    }
}
