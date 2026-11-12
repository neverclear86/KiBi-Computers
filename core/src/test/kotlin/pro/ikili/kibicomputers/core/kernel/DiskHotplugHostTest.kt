package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.HostDiskLeases
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ディスクの挿し抜きとマウントを、本番と同じ経路で確かめる（#56、ADR 0056）。[DiskHotplugTest] は開き方をテストのフック
 * （[KernelTestHooks.bootVolumes]）で差し替えるので通らない、次の経路を通す:
 *
 * - [Machine.openerForCurrentDisks] の `BootVolumeOpener.forDisks(DiskResolver { inserted[it]?.disk }, hostFiles)` と、
 *   本物の `DiskFileSystem`（ホストのディレクトリ・リース・開き手の取り消し）
 * - 自動マウントの仕事（`reconcileMounts`）が、フックがないときだけ行う「今の表にないディスクのマウントを外す」後始末
 *
 * init は kibi-power-wasi の `hold`（止められるまで眠る）。根のディスクもホストのディレクトリ
 */
class DiskHotplugHostTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun diskDir(id: String): Path = dir.resolve("disks").resolve(id)

    private fun snapshot(vararg ids: String): DiskSnapshot =
        DiskSnapshot.of(ids.associateWith { ResolvedDisk(diskDir(it), 1L shl 20, 64) })

    /** 根のディスクのディレクトリに init（hold）を置く */
    private fun writeRoot() {
        val root = diskDir("root")
        Files.createDirectories(root.resolve("sbin"))
        Files.write(root.resolve("sbin/init"), resource("kibi-power-wasi.wasm"))
        Files.write(root.resolve("kpw-mode"), "hold".toByteArray())
    }

    /** [ids] を挿したマシンを、本番の開き方（フックなし）で根 `root` から起動して、動くまで待つ */
    private fun boot(h: Harness, vararg ids: String): Machine {
        writeRoot()
        val m = h.machine(fuelPerTick = 10_000_000, environment = MachineEnvironment.builder().disks(snapshot(*ids)).build())
        assertTrue(m.boot(BootConfig.builder().addBootDisk("root").build()))
        eventually(60_000, "起動しない") {
            h.scheduler.tick()
            m.state == MachineState.RUNNING && !m.isBooting
        }
        return m
    }

    private fun Machine.mountAt(point: String): String? = lock.withLockValue { kernel.mounts.at(VfsPath.parse(point))?.source }

    private fun Machine.subscribeAll(): EventQueue = lock.withLockValue {
        kernel.events.ensureCapacity(kernel.objects)
        val q = EventQueue(EventFilter.ALL, 64)
        kernel.objects.add(q)
        kernel.events.subscribe(q, kernel.objects)
        q
    }

    private fun Machine.diskEvents(q: EventQueue): List<Triple<String, String, String>> = lock.withLockValue {
        q.pendingEvents().filter { it.type.startsWith("disk-") }.map {
            Triple(it.type, (it.values[0] as EventValue.Text).value, (it.values[1] as EventValue.Text).value)
        }
    }

    private fun users(id: String): Int = HostDiskLeases.usersOf(diskDir(id).toRealPath())

    private fun writeText(m: Machine, path: String, text: String) {
        val bytes = text.toByteArray()
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = false, write = true, create = true, truncate = true))
        try {
            m.vfs.write(f, bytes, 0, bytes.size)
        } finally {
            m.vfs.close(f)
        }
    }

    private fun readText(m: Machine, path: String): String {
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true))
        try {
            val buf = ByteArray(256)
            val n = m.vfs.read(f, buf, 0, buf.size)
            return String(buf, 0, n)
        } finally {
            m.vfs.close(f)
        }
    }

    @Test
    fun `起動のときに挿さっているディスクは本物のディレクトリをマウントし、書き込みがホストのファイルに出る`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = boot(h, "root", "d1")
            assertEquals("disk:root", m.mountAt("/"))
            assertEquals("disk:d1", m.mountAt("/mnt/d1"))
            assertEquals(1, users("d1"), "リースを1つ持つ")
            writeText(m, "/mnt/d1/a", "hello")
            assertEquals("hello", Files.readString(diskDir("d1").resolve("a")))
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    @Test
    fun `動いている間に挿したディスクをマウントしてdisk-insertedを出し、抜くと外してリースを返し、挿し直すと付け直す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = boot(h, "root")
            val q = m.subscribeAll()
            Files.createDirectories(diskDir("d2"))
            Files.writeString(diskDir("d2").resolve("hello"), "hi")
            // 挿す: 自動マウントの仕事（フックなし）が、本物の DiskFileSystem を開いてマウントする
            assertFalse(m.disksChanged(snapshot("root", "d2")))
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/d2") == "disk:d2" }
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", "d2", "/mnt/d2")), m.diskEvents(q))
            assertEquals("hi", readText(m, "/mnt/d2/hello"))
            writeText(m, "/mnt/d2/out", "written")
            assertEquals("written", Files.readString(diskDir("d2").resolve("out")))
            assertEquals(1, users("d2"))
            // 抜く: 外して disk-removed を出し、ファイルシステムを閉じてリースを返す
            assertFalse(m.disksChanged(snapshot("root")))
            assertNull(m.mountAt("/mnt/d2"))
            assertEquals(MachineState.RUNNING, m.state)
            eventually(10_000, "リースを返さない") {
                h.scheduler.tick()
                users("d2") == 0
            }
            assertEquals(listOf("disk-inserted", "disk-removed"), m.diskEvents(q).map { it.first })
            // 挿し直す: 新しい挿し込みとして、新しい DiskFileSystem でマウントし直す
            assertFalse(m.disksChanged(snapshot("root", "d2")))
            eventually(10_000, "付け直さない") { m.mountAt("/mnt/d2") == "disk:d2" }
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).count { it.first == "disk-inserted" } == 2 }
            assertEquals("written", readText(m, "/mnt/d2/out"))
            assertEquals(1, users("d2"))
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    @Test
    fun `今の表にないディスクのマウントは、自動マウントの仕事が外してdisk-removedを出す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = boot(h, "root")
            val q = m.subscribeAll()
            // 抜けたのに外れていないマウント（抜いた区間で外せなかったもの）を作る
            m.lock.withLockValue {
                m.vfs.mountLocked(VfsPath.parse("/ghost"), TestMemoryFileSystem(), readOnly = false, source = "disk:ghost")
            }
            assertEquals("disk:ghost", m.mountAt("/ghost"))
            // 別のディスクを挿して仕事を動かす（フックがないので、後始末も走る）
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            eventually(10_000, "後始末しない") { m.mountAt("/ghost") == null }
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/d1") == "disk:d1" }
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).any { it.first == "disk-inserted" } }
            assertEquals(
                setOf(Triple("disk-removed", "ghost", "/ghost"), Triple("disk-inserted", "d1", "/mnt/d1")),
                m.diskEvents(q).toSet(),
            )
            m.close()
        }
    }

    @Test
    fun `mountは本物のディレクトリを開いてマウントし、unmountでリースを返し、挿さっていないディスクと位置のあるマウントは断る`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = boot(h, "root", "d1")
            fun errnoOf(block: () -> Unit): Errno = assertThrows<FsException> { block() }.errno
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            eventually(10_000, "リースを返さない") {
                h.scheduler.tick()
                users("d1") == 0
            }
            val entries = m.mountDisk("disk:d1", VfsPath.parse("/x"), readOnly = false)
            assertEquals(0, entries, "空のディスクの項目は 0")
            writeText(m, "/x/a", "via mount")
            assertEquals("via mount", Files.readString(diskDir("d1").resolve("a")))
            assertEquals(Errno.BUSY, errnoOf { m.mountDisk("disk:d1", VfsPath.parse("/y"), false) }, "マウント中")
            assertEquals(Errno.NOENT, errnoOf { m.mountDisk("disk:d9", VfsPath.parse("/y"), false) }, "挿さっていない")
            // 読み取り専用でマウントし直す
            m.unmountAt(VfsPath.parse("/x"))
            // 項目は木をたどった数（ファイル a 1つ）
            assertEquals(1, m.mountDisk("disk:d1", VfsPath.parse("/y"), readOnly = true))
            assertEquals(Errno.ROFS, errnoOf { writeText(m, "/y/b", "no") })
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }
}
