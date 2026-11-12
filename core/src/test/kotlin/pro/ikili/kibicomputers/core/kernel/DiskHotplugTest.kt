package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.wasi.kibi.KibiFs
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Driver
import pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.DiskQuota
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.FsUsage
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ディスクの抜き差しと自動マウント・予約（#56、ADR 0056）。開き方はテストのフック（[KernelTestHooks.bootVolumes]）で差し替え、
 * 今挿さっているディスクの表は [MachineEnvironment.disks] と [Machine.disksChanged] で入れる。
 * init は kibi-power-wasi の `hold`（止められるまで眠る）
 */
class DiskHotplugTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    /** init（hold）を置いた根 */
    private fun rootFs(): TestMemoryFileSystem {
        val fs = TestMemoryFileSystem()
        fs.createDirectory(VfsPath.parse("/sbin"))
        for ((path, bytes) in listOf(BootConfig.INIT_PATH to resource("kibi-power-wasi.wasm"), "/kpw-mode" to "hold".toByteArray())) {
            val node = fs.createFile(VfsPath.parse(path))
            fs.write(node, 0, bytes, 0, bytes.size)
        }
        return fs
    }

    private fun snapshot(vararg ids: String): DiskSnapshot = DiskSnapshot.of(ids.associateWith { ResolvedDisk(dir.resolve(it), 1L shl 20, 64) })

    /** 閉じた回数を数えるディスク */
    private class CountingDisk(private val inner: TestMemoryFileSystem = TestMemoryFileSystem()) : FileSystem by inner {
        val retired = AtomicInteger()

        override fun retire() {
            retired.incrementAndGet()
        }
    }

    /** ディスクの識別子 → 中身。開くたびに同じ中身を返す。[gates] の識別子は、開く前に門が開くまで待つ（取り消しは見ない） */
    private class Disks(
        val root: FileSystem,
        val rootReadOnly: Boolean = false,
        /** [root] を返す識別子（同梱イメージの識別子 `image:<名前>` から起動する経路を試すときに足す） */
        val rootIds: Set<String> = setOf("root"),
    ) : BootVolumeOpener {
        val fs = ConcurrentHashMap<String, CountingDisk>()
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        val gates = ConcurrentHashMap<String, Gate>()

        /** 識別子 → 開くときに返すファイルシステム（本物の `DiskFileSystem` などに差し替える。#453） */
        val overrides = ConcurrentHashMap<String, FileSystem>()

        class Gate {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
        }

        fun disk(id: String): CountingDisk = fs.computeIfAbsent(id) { CountingDisk() }

        override fun open(diskId: String): BootVolume? = open(diskId) { false }

        override fun open(diskId: String, cancelled: () -> Boolean): BootVolume? {
            opens.computeIfAbsent(diskId) { AtomicInteger() }.incrementAndGet()
            gates[diskId]?.let { g ->
                g.entered.countDown()
                check(g.release.await(15, TimeUnit.SECONDS)) { "門が開かない" }
            }
            if (diskId in rootIds) return BootVolume(root, readOnly = rootReadOnly)
            if (diskId.startsWith("bad")) return null
            overrides[diskId]?.let { return BootVolume(it, readOnly = false) }
            return BootVolume(disk(diskId), readOnly = false)
        }

        fun opened(id: String): Int = opens[id]?.get() ?: 0
    }

    private class Booted(val h: Harness, val m: Machine, val disks: Disks)

    /** [ids] を挿したマシンを root から起動して、動くまで待つ */
    private fun boot(
        h: Harness,
        vararg ids: String,
        disks: Disks = Disks(rootFs()),
        wait: Boolean = true,
        fuel: Long = 10_000_000,
        config: BootConfig = BootConfig.builder().addBootDisk("root").build(),
    ): Booted {
        h.scheduler.hooks.bootVolumes = disks
        val m = h.machine(fuelPerTick = fuel, environment = MachineEnvironment.builder().disks(snapshot(*ids)).build())
        assertTrue(m.boot(config))
        if (wait) awaitRunning(h, m)
        return Booted(h, m, disks)
    }

    private fun awaitRunning(h: Harness, m: Machine) = eventually(60_000, "起動しない") {
        h.scheduler.tick()
        m.state == MachineState.RUNNING && !m.isBooting
    }

    private fun Machine.mountAt(point: String): String? = lock.withLockValue { kernel.mounts.at(VfsPath.parse(point))?.source }

    private fun Machine.mountPoints(): Map<String, String> = lock.withLockValue { kernel.mounts.entries.associate { it.point.toString() to it.source } }

    /** マシンのイベントを全部受けるキュー */
    private fun Machine.subscribeAll(): EventQueue = lock.withLockValue {
        kernel.events.ensureCapacity(kernel.objects)
        val q = EventQueue(EventFilter.ALL, 64)
        kernel.objects.add(q)
        kernel.events.subscribe(q, kernel.objects)
        q
    }

    /** キューにある、ディスクのイベント（種類, 識別子, 位置） */
    private fun Machine.diskEvents(q: EventQueue): List<Triple<String, String, String>> = lock.withLockValue {
        q.pendingEvents().filter { it.type.startsWith("disk-") }.map {
            Triple(it.type, (it.values[0] as EventValue.Text).value, (it.values[1] as EventValue.Text).value)
        }
    }

    @Test
    fun `自動マウントの名前は先頭8文字の小文字で、重なったらハイフンで終わらない長さに延ばす`() {
        val names = Machine.autoMountPoints("ABCDEF12-3456-7890-abcd-ef1234567890").map { it.toString() }
        assertEquals("/mnt/abcdef12", names[0])
        assertEquals("/mnt/abcdef12-3", names[1])
        assertEquals("/mnt/abcdef12-34", names[2])
        assertTrue(names.none { it.endsWith("-") })
        assertEquals(listOf("/mnt/d1"), Machine.autoMountPoints("d1").map { it.toString() })
        assertEquals(emptyList<VfsPath>(), Machine.autoMountPoints(".."))
    }

    @Test
    fun `起動のときに挿さっている根以外のディスクは、mntにマウントする`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1", "d2")
            assertEquals(mapOf("/" to "disk:root", "/mnt/d1" to "disk:d1", "/mnt/d2" to "disk:d2"), b.m.mountPoints().filterKeys { it == "/" || it.startsWith("/mnt") })
            assertEquals(setOf("root", "d1", "d2"), b.m.insertedDiskIds)
            assertEquals(0, b.m.diskClaimCount, "段の予約は起動の後に残らない")
            b.m.close()
        }
    }

    @Test
    fun `動いている間に挿すとmntにマウントしてdisk-insertedを出し、抜くと外してdisk-removedを出す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root")
            val m = b.m
            val q = m.subscribeAll()
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/d1") == "disk:d1" }
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", "d1", "/mnt/d1")), m.diskEvents(q))
            // 開いたファイルは、抜いた後に IO になる
            val f = m.vfs.open(VfsPath.parse("/mnt/d1/a"), OpenOptions(read = true, write = true, create = true))
            assertFalse(m.disksChanged(snapshot("root")))
            assertNull(m.mountAt("/mnt/d1"))
            assertEquals(Errno.IO, assertThrows<FsException> { m.vfs.write(f, byteArrayOf(1), 0, 1) }.errno)
            assertEquals(MachineState.RUNNING, m.state)
            assertEquals(listOf(Triple("disk-inserted", "d1", "/mnt/d1"), Triple("disk-removed", "d1", "/mnt/d1")), m.diskEvents(q))
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    @Test
    fun `マウントしていないディスクを抜いても、位置の空のdisk-removedを出す。根を抜くと止めるべきと答える`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            val q = m.subscribeAll()
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            assertFalse(m.disksChanged(snapshot("root")))
            assertEquals(listOf(Triple("disk-removed", "d1", "")), m.diskEvents(q))
            assertTrue(m.disksChanged(DiskSnapshot.EMPTY), "根が抜けたら止める")
            m.close()
        }
    }

    @Test
    fun `入れ子のマウントの親を抜いても例外を出さず、子は残る`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1", "d2")
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/d2"))
            m.mountDisk("disk:d2", VfsPath.parse("/mnt/d1/sub"), readOnly = false)
            assertFalse(m.disksChanged(snapshot("root", "d2")))
            assertNull(m.mountAt("/mnt/d1"))
            assertEquals("disk:d2", m.mountAt("/mnt/d1/sub"))
            assertEquals(MachineState.RUNNING, m.state)
            m.close()
        }
    }

    @Test
    fun `mountとunmountのシステムコールのエラー`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            fun errnoOf(block: () -> Unit): Errno = assertThrows<FsException> { block() }.errno
            val x = VfsPath.parse("/x")
            assertEquals(Errno.NOENT, errnoOf { m.mountDisk("disk:nope", x, false) }, "挿さっていない")
            assertEquals(Errno.NOENT, errnoOf { m.mountDisk("disk:image:os", x, false) }, "同梱イメージ")
            assertEquals(Errno.NOENT, errnoOf { m.mountDisk("tmp", x, false) }, "disk: でない")
            assertEquals(Errno.BUSY, errnoOf { m.mountDisk("disk:root", x, false) }, "根はマウント中")
            assertEquals(Errno.BUSY, errnoOf { m.mountDisk("disk:d1", x, false) }, "マウント中")
            assertEquals(Errno.BUSY, errnoOf { m.unmountAt(VfsPath.ROOT) })
            assertEquals(Errno.BUSY, errnoOf { m.unmountAt(MachineLimits.TMP_PATH) })
            assertEquals(Errno.BUSY, errnoOf { m.unmountAt(MachineLimits.DEV_PATH) })
            assertEquals(Errno.INVAL, errnoOf { m.unmountAt(x) }, "マウントの位置でない")
            // 外してマウントし直せる。位置にもうマウントがあれば busy で、予約は残らない
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            assertEquals(Errno.BUSY, errnoOf { m.mountDisk("disk:d1", MachineLimits.TMP_PATH, false) })
            assertEquals(0, m.diskClaimCount)
            m.mountDisk("disk:d1", x, readOnly = true)
            assertEquals("disk:d1", m.mountAt("/x"))
            assertTrue(m.lock.withLockValue { m.kernel.mounts.at(x)!!.readOnly })
            assertEquals(MachineState.RUNNING, m.state)
            m.close()
        }
    }

    @Test
    fun `自動マウントを開いている途中に同じディスクをmountするとbusyで、自動マウントしたものはIOにならない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root")
            val m = b.m
            val gate = Disks.Gate()
            b.disks.gates["d1"] = gate
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            assertEquals(Errno.BUSY, assertThrows<FsException> { m.mountDisk("disk:d1", VfsPath.parse("/x"), false) }.errno)
            gate.release.countDown()
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/d1") == "disk:d1" }
            assertEquals(1, b.disks.opened("d1"), "同じディスクを2回開かない")
            m.close()
        }
    }

    @Test
    fun `自動マウントを開いている途中に同じ位置へほかのディスクをマウントすると、名前を延ばしてマウントする`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val id = "abcdef12-0000-0000-0000-000000000001"
            val b = boot(h, "root", "other")
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/other"))
            val q = m.subscribeAll()
            val gate = Disks.Gate()
            b.disks.gates[id] = gate
            assertFalse(m.disksChanged(snapshot("root", "other", id)))
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            m.mountDisk("disk:other", VfsPath.parse("/mnt/abcdef12"), false)
            gate.release.countDown()
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/abcdef12-0") == "disk:$id" }
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).any { it.second == id } }
            assertEquals(listOf(Triple("disk-inserted", id, "/mnt/abcdef12-0")), m.diskEvents(q).filter { it.second == id })
            m.close()
        }
    }

    @Test
    fun `mntが普通のファイルなら、位置の空のdisk-insertedを1回出して開いたものを閉じない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val root = rootFs()
            root.createFile(VfsPath.parse("/mnt"))
            val b = boot(h, "root", disks = Disks(root))
            val m = b.m
            val q = m.subscribeAll()
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", "d1", "")), m.diskEvents(q))
            assertEquals(0, b.disks.opened("d1"), "位置が使えなければ開かない")
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    @Test
    fun `開けないディスクでは、自動マウントの仕事が回り続けない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root")
            val m = b.m
            val q = m.subscribeAll()
            val before = m.autoMountRuns.get()
            assertFalse(m.disksChanged(snapshot("root", "bad1")))
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", "bad1", "")), m.diskEvents(q))
            Thread.sleep(200)
            assertTrue(m.autoMountRuns.get() - before <= 2, "回り続ける: ${m.autoMountRuns.get() - before}")
            m.close()
        }
    }

    @Test
    fun `自動マウントを開いている途中にinitが終わってSTOPPEDになると、マウントせず閉じ、次の起動で根に選ばれる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root")
            val m = b.m
            val gate = Disks.Gate()
            b.disks.gates["root2"] = gate
            assertFalse(m.disksChanged(snapshot("root", "root2")))
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            // init が自分で終わる（stopLocked を通らずに STOPPED）
            m.lock.withLockValue { m.killRuntimeLocked(m.runtimes.getValue(m.kernel.initPid), KillSignal.KILL) }
            eventually(10_000, "止まらない") {
                h.scheduler.tick()
                m.state == MachineState.STOPPED
            }
            gate.release.countDown()
            eventually(10_000, "閉じない") { b.disks.disk("root2").retired.get() == 1 }
            assertNull(m.mountAt("/mnt/root2"))
            assertEquals(0, m.diskClaimCount, "予約は残らない")
            b.disks.gates.remove("root2")
            // root2 にも init を置いて、次の起動の根の候補にする
            val init = resource("kibi-power-wasi.wasm")
            val d = b.disks.disk("root2")
            d.createDirectory(VfsPath.parse("/sbin"))
            for ((path, bytes) in listOf(BootConfig.INIT_PATH to init, "/kpw-mode" to "hold".toByteArray())) {
                val node = d.createFile(VfsPath.parse(path))
                d.write(node, 0, bytes, 0, bytes.size)
            }
            eventually(10_000, "起動できない") { m.boot(BootConfig.builder().addBootDisk("root2").build()) }
            awaitRunning(h, m)
            assertEquals("disk:root2", m.mountAt("/"))
            m.close()
        }
    }

    @Test
    fun `システムコールのmountが開いている途中にマシンが止まるとIO`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            val gate = Disks.Gate()
            b.disks.gates["d1"] = gate
            val error = java.util.concurrent.CompletableFuture<Errno?>()
            val t = Thread {
                error.complete(
                    try {
                        m.mountDisk("disk:d1", VfsPath.parse("/x"), false)
                        null
                    } catch (e: FsException) {
                        e.errno
                    },
                )
            }
            t.start()
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            m.stop()
            gate.release.countDown()
            assertEquals(Errno.IO, error.get(10, TimeUnit.SECONDS))
            assertNull(m.mountAt("/x"))
            assertEquals(0, m.diskClaimCount)
            t.join()
            m.close()
        }
    }

    @Test
    fun `予約より前に抜いたディスクは、自動マウントで開かず、disk-insertedも出さない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d0")
            val m = b.m
            val q = m.subscribeAll()
            // d0 の自動マウントを止めておき、その間に d1 を挿して抜く（d1 は予約されないまま抜ける）
            m.unmountAt(VfsPath.parse("/mnt/d0"))
            val gate = Disks.Gate()
            b.disks.gates["d2"] = gate
            assertFalse(m.disksChanged(snapshot("root", "d0", "d2")))
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            assertFalse(m.disksChanged(snapshot("root", "d0", "d2", "d1")))
            assertFalse(m.disksChanged(snapshot("root", "d0", "d2")))
            gate.release.countDown()
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/d2") == "disk:d2" }
            Thread.sleep(100)
            assertEquals(0, b.disks.opened("d1"))
            assertEquals(listOf(Triple("disk-removed", "d1", "")), m.diskEvents(q).filter { it.second == "d1" })
            m.close()
        }
    }

    @Test
    fun `起動の途中に挿して根に選ばれたディスクを、initを作る前に抜くと止めるべきと答える`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val disks = Disks(TestMemoryFileSystem())
            val gate = Disks.Gate()
            disks.gates["root"] = gate
            val stopped = CountDownLatch(1)
            h.scheduler.hooks.beforeInitExec = { _ -> stopped.await(30, TimeUnit.SECONDS) }
            val b = boot(h, "x", disks = disks, wait = false)
            val m = b.m
            // 根の候補 root を開いている途中に root を挿しても、予約より後なので表にない（起動は root を飛ばす）
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            gate.release.countDown()
            eventually(10_000, "起動が終わらない") { !m.isBooting }
            stopped.countDown()
            m.close()
        }
        // 根を表に入れてから起動し、init を作る前に抜く
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val reached = CountDownLatch(1)
            val go = CountDownLatch(1)
            h.scheduler.hooks.beforeInitExec = { _ ->
                reached.countDown()
                go.await(30, TimeUnit.SECONDS)
            }
            val b = boot(h, "root", wait = false)
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            assertTrue(b.m.disksChanged(DiskSnapshot.EMPTY), "根が抜けたら止める")
            go.countDown()
            b.m.close()
        }
    }

    @Test
    fun `再起動の待ちとゲストが止めた後に根以外を抜いても止めず、イベントも出さない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1", "d2")
            val m = b.m
            m.stop()
            eventually(10_000, "止まらない") {
                h.scheduler.tick()
                m.state == MachineState.STOPPED
            }
            assertFalse(m.disksChanged(snapshot("root", "d2")))
            assertEquals("disk:d1", m.mountAt("/mnt/d1"), "止まったマシンでは外さない（次の起動が外す）")
            assertTrue(m.disksChanged(snapshot("d2")), "根が抜けたら止める")
            m.close()
        }
    }

    @Test
    fun `起動の後に外してmountし直せる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            m.mountDisk("disk:d1", VfsPath.parse("/mnt/d1"), false)
            assertEquals("disk:d1", m.mountAt("/mnt/d1"))
            assertNotNull(m.vfs.stat(VfsPath.parse("/mnt/d1")))
            m.close()
        }
    }

    /** kibi-fs-wasi（p1 の `kibi:fs/mounts`）を [args] で動かし、出力を返す */
    private fun runKibiFs(h: Harness, m: Machine, vararg args: String): String = runKibiFsCounting(h, m, *args).first

    /** [runKibiFs] と、終わるまでに回した tick の数 */
    private fun runKibiFsCounting(h: Harness, m: Machine, vararg args: String, metered: Boolean = false): Pair<String, Int> {
        val output = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
        val options = SpawnOptions(
            allowUnmetered = !metered,
            fds = SpawnOptions.ROOT_PREOPEN,
            args = listOf("kibi-fs") + args,
            stdio = StdioSpec(StdioSource.Console, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Console),
        )
        val pid = if (metered) {
            // 燃料を計測するモジュールとして、ディスクの /bin/kibi-fs を exec する
            m.exec("/bin/kibi-fs", KernelDrivers.DEFAULT, options)
        } else {
            val program = Program.load(EndiveRuntime(), resource("kibi-fs-wasi.wasm"))
            m.spawn(program, WasiP1Driver(program.module), options)
        }
        var ticks = 0
        if (metered) {
            // 燃料を待っているとき（CPU を持たず、マシンの燃料が尽きている）にだけ tick を進めて数える。
            // 時計で回すと、読み込み（コンパイル）が遅い環境ほど tick が増え、燃料の差が数に出なくなる。
            // 燃料の補充は 1 tick で最大 fuelPerTick なので、払った燃料の分だけは必ず tick を数える。
            // 燃料以外で止まって進まないとき（CPU 待ちが長いなど）に備えて、1 秒動かなければ tick を進める
            var lastTick = System.nanoTime()
            eventually(60_000, "終わらない") {
                val (state, starved) = m.lock.withLockValue {
                    val state = m.stateOf(pid)
                    val holdsCpu = state == ProcessState.RUNNING || state == ProcessState.LOADING
                    state to (!holdsCpu && m.fuelAccount.estimate(h.scheduler.now) <= 0)
                }
                if (state.isTerminal) return@eventually true
                val stalled = state != ProcessState.RUNNING && state != ProcessState.LOADING &&
                    System.nanoTime() - lastTick > 1_000_000_000L
                if (starved || stalled) {
                    h.scheduler.tick()
                    ticks++
                    lastTick = System.nanoTime()
                }
                false
            }
        } else {
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                ticks++
                m.stateOf(pid).isTerminal
            }
        }
        val text = m.lock.withLockValue {
            val s = m.kernel.objects[output] as ByteStream
            val buf = ByteArray(s.available)
            if (buf.isNotEmpty()) s.read(buf, 0, buf.size, m.wakeSink)
            String(buf, Charsets.UTF_8)
        }
        return text to ticks
    }

    @Test
    fun `p1のmountとunmountは結果をerror-codeで返し、相対パスは作業ディレクトリから解決する`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            val out = runKibiFs(
                h, m,
                "unmount", "mnt/d1",
                "mount", "disk:d1", "/x", "1",
                "write", "/x/a", "hi",
                "unmount", "/x",
                "mount", "disk:d1", "/x", "0",
                "write", "/x/a", "hi",
                "mount", "disk:nope", "/y", "0",
                "mount", "disk:root", "/y", "0",
                "unmount", "/",
                "unmount", "/y",
                "oob", "long", "badutf8",
            )
            val expected = listOf(
                "unmount 0",
                "mount 0",
                "write ${Errno.ROFS.code}",
                "unmount 0",
                "mount 0",
                "write 0",
                "mount ${Errno.NOENT.code}",
                "mount ${Errno.BUSY.code}",
                "unmount ${Errno.BUSY.code}",
                "unmount ${Errno.INVAL.code}",
                "oob ${Errno.FAULT.code}",
                "longpoint ${Errno.NAMETOOLONG.code}",
                "longsource ${Errno.INVAL.code}",
                "badutf8 ${Errno.INVAL.code}",
            )
            assertEquals(expected, out.lines().filter { it.isNotEmpty() })
            assertEquals("hi", String(b.disks.disk("d1").let { fs -> val n = fs.stat(VfsPath.parse("/a")).node; val buf = ByteArray(2); fs.read(n, 0, buf, 0, 2); buf }))
            m.close()
        }
    }

    /** 起動用のスレッドをすべて塞ぐ（[release] を数えると解ける）。自動マウントの仕事を、積んだままにしておくため */
    private fun blockBootThreads(h: Harness, release: CountDownLatch) {
        val executor = h.scheduler.bootExecutor
        val started = CountDownLatch(executor.maximumPoolSize)
        repeat(executor.maximumPoolSize) {
            executor.execute {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "起動用のスレッドを塞げない")
    }

    private fun awaitBootThreadsIdle(h: Harness) =
        eventually(10_000, "起動用のスレッドが空にならない") { h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty() }

    @Test
    fun `ゲストがtmpの下にマウントしたまま止めても例外が漏れず、STOPPEDになってtmpもマウントも外れる`() = timeout(limit) {
        for (viaKill in listOf(false, true)) {
            Harness(execRuntime = EndiveRuntime()).use { h ->
                val b = boot(h, "root", "d1")
                val m = b.m
                m.unmountAt(VfsPath.parse("/mnt/d1"))
                // /tmp に書いてから、その下にディスクをマウントする
                val f = m.vfs.open(VfsPath.parse("/tmp/f"), OpenOptions(read = true, write = true, create = true))
                m.vfs.write(f, ByteArray(4096), 0, 4096)
                assertTrue(m.memoryAccount.usedBytes > 0)
                m.mountDisk("disk:d1", VfsPath.parse("/tmp/x"), readOnly = false)
                assertEquals("disk:d1", m.mountAt("/tmp/x"))
                if (viaKill) {
                    // init が自分で終わる（checkAllDoneLocked が STOPPED にする）
                    m.lock.withLockValue { m.killRuntimeLocked(m.runtimes.getValue(m.kernel.initPid), KillSignal.KILL) }
                } else {
                    m.stop()
                }
                eventually(10_000, "止まらない") {
                    h.scheduler.tick()
                    m.state == MachineState.STOPPED
                }
                assertNull(m.mountAt("/tmp"), "viaKill=$viaKill")
                assertNull(m.mountAt("/tmp/x"), "viaKill=$viaKill")
                assertEquals(0L, m.memoryAccount.usedBytes, "/tmp の RAM を返す viaKill=$viaKill")
                assertEquals(0, m.diskClaimCount)
                // もう一度止めても例外を出さず、起動し直せる
                m.stop()
                eventually(10_000, "起動できない") { m.boot(BootConfig.builder().addBootDisk("root").build()) }
                awaitRunning(h, m)
                assertEquals("disk:d1", m.mountAt("/mnt/d1"))
                m.close()
            }
        }
    }

    @Test
    fun `自動マウントの仕事が飛ばした挿し込みをmountが成功させても、disk-insertedを1回出し、外しても自動で付け直さない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root")
            val m = b.m
            val q = m.subscribeAll()
            val hold = CountDownLatch(1)
            blockBootThreads(h, hold)
            val gate = Disks.Gate()
            b.disks.gates["d1"] = gate
            // 自動マウントの仕事は積まれたまま動けない。その間にプロセスが先に予約を取る
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            val error = java.util.concurrent.CompletableFuture<Errno?>()
            val t = Thread {
                error.complete(
                    try {
                        m.mountDisk("disk:d1", VfsPath.parse("/x"), false)
                        null
                    } catch (e: FsException) {
                        e.errno
                    },
                )
            }
            t.start()
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            // 仕事が動く: 予約があるので d1 を飛ばして終わる
            val runs = m.autoMountRuns.get()
            hold.countDown()
            eventually(10_000, "仕事が動かない") { m.autoMountRuns.get() > runs }
            awaitBootThreadsIdle(h)
            assertEquals(emptyList<Triple<String, String, String>>(), m.diskEvents(q), "マウントが終わるまで出さない")
            gate.release.countDown()
            assertEquals(null, error.get(10, TimeUnit.SECONDS))
            t.join()
            assertEquals("disk:d1", m.mountAt("/x"))
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", "d1", "/x")), m.diskEvents(q))
            assertEquals(1, b.disks.opened("d1"), "同じディスクを2回開かない")
            // 外しても、同じ挿し込みは自動マウントし直さない（別のディスクを挿して仕事が動いても）
            m.unmountAt(VfsPath.parse("/x"))
            assertFalse(m.disksChanged(snapshot("root", "d1", "d2")))
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/d2") == "disk:d2" }
            awaitBootThreadsIdle(h)
            assertNull(m.mountAt("/mnt/d1"))
            assertEquals(1, b.disks.opened("d1"))
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    @Test
    fun `予約の持ち主が入れ替わっても、古い持ち主は新しい予約を消さずに開いたものを閉じ、新しい持ち主だけがマウントする`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root")
            val m = b.m
            val q = m.subscribeAll()
            val gateA = Disks.Gate()
            b.disks.gates["d1"] = gateA
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            assertTrue(gateA.entered.await(10, TimeUnit.SECONDS))
            // A が開いている途中に抜き、挿し直す（B）
            assertFalse(m.disksChanged(snapshot("root")))
            val gateB = Disks.Gate()
            b.disks.gates["d1"] = gateB
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            assertTrue(gateB.entered.await(10, TimeUnit.SECONDS))
            assertEquals(1, m.diskClaimCount, "B の予約だけが残る")
            // A を進める: マウントせず、開いたものを閉じる。B の予約は残る
            gateA.release.countDown()
            eventually(10_000, "A の開いたものを閉じない") { b.disks.disk("d1").retired.get() == 1 }
            assertNull(m.mountAt("/mnt/d1"))
            assertEquals(1, m.diskClaimCount, "A の後始末が B の予約を消さない")
            // B を進める
            gateB.release.countDown()
            eventually(10_000, "B がマウントしない") { m.mountAt("/mnt/d1") == "disk:d1" }
            assertEquals(1, b.disks.disk("d1").retired.get(), "B の開いたものは閉じない")
            assertEquals(0, m.diskClaimCount)
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).any { it.first == "disk-inserted" } }
            assertEquals(listOf("disk-removed", "disk-inserted"), m.diskEvents(q).map { it.first })
            m.close()
        }
    }

    @Test
    fun `先頭8文字が同じ2枚のディスクは、名前を延ばして別々の位置にマウントする`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val a = "abcdef12-0000-0000-0000-000000000001"
            val c = "abcdef12-0000-0000-0000-000000000002"
            val d = "abcdef12-0000-0000-0000-000000000003"
            // 起動のときに挿さっている2枚
            val b = boot(h, "root", a, c)
            val m = b.m
            assertEquals(
                mapOf("/mnt/abcdef12" to "disk:$a", "/mnt/abcdef12-0" to "disk:$c"),
                m.mountPoints().filterKeys { it.startsWith("/mnt/") },
            )
            // 動いている間に挿した3枚目
            val q = m.subscribeAll()
            assertFalse(m.disksChanged(snapshot("root", a, c, d)))
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/abcdef12-00") == "disk:$d" }
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", d, "/mnt/abcdef12-00")), m.diskEvents(q))
            m.close()
        }
    }

    @Test
    fun `mountは固定の燃料を払うので、mountとunmountを繰り返すゲストは少ない燃料では進めない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // 1 tick の燃料を、mount 1回の固定分（MOUNT_FUEL = 50,000）の 2 倍にする
            val root = rootFs()
            root.createDirectory(VfsPath.parse("/bin"))
            val bytes = resource("kibi-fs-wasi.wasm")
            root.write(root.createFile(VfsPath.parse("/bin/kibi-fs")), 0, bytes, 0, bytes.size)
            val b = boot(h, "root", "d1", disks = Disks(root), fuel = 100_000)
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            val cycles = 20
            val mountArgs = (1..cycles).flatMap { listOf("mount", "disk:d1", "/x", "0", "unmount", "/x") }.toTypedArray()
            val (out, mountTicks) = runKibiFsCounting(h, m, *mountArgs, metered = true)
            assertEquals(cycles * 2, out.lines().count { it == "mount 0" || it == "unmount 0" })
            // 同じ数の呼び出しでも、mount でない unmount（失敗）なら燃料は 100 ずつ
            val cheapArgs = (1..cycles).flatMap { listOf("unmount", "/nowhere", "unmount", "/nowhere") }.toTypedArray()
            val (_, cheapTicks) = runKibiFsCounting(h, m, *cheapArgs, metered = true)
            // 20 回の mount は 1,000,000 以上の燃料 = 100,000 ずつの tick で 9 回以上は待つ
            assertTrue(mountTicks >= 10, "mount の燃料が払われていない: $mountTicks tick（unmount だけは $cheapTicks tick）")
            assertTrue(mountTicks > cheapTicks + 5, "mount: $mountTicks tick、unmount だけ: $cheapTicks tick")
            m.close()
        }
    }

    @Test
    fun `読み取り専用の根では、homeのディスクをhomeにだけマウントしてmntに重ねず、抜くと止めるべきと答える`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val config = BootConfig.builder().addBootDisk("root").homeDisk("h1").build()
            val b = boot(h, "root", "h1", "d1", disks = Disks(rootFs(), rootReadOnly = true), config = config)
            val m = b.m
            assertEquals("disk:h1", m.mountAt("/home"))
            assertEquals(
                mapOf("/mnt/d1" to "disk:d1"),
                m.mountPoints().filterKeys { it.startsWith("/mnt") },
                "/home にしたディスクを /mnt にも重ねてマウントしない",
            )
            assertEquals(1, b.disks.opened("h1"), "/home のディスクは1回だけ開く")
            // 別のディスクを挿して自動マウントの仕事が動いても、/home のディスクは handled なので /mnt に足さない
            assertFalse(m.disksChanged(snapshot("root", "h1", "d1", "d2")))
            eventually(10_000, "マウントしない") { m.mountAt("/mnt/d2") == "disk:d2" }
            awaitBootThreadsIdle(h)
            assertNull(m.mountAt("/mnt/h1"))
            assertEquals(1, b.disks.opened("h1"))
            // /home 以外を抜いても止めない。/home を抜くと止めるべき
            assertFalse(m.disksChanged(snapshot("root", "h1", "d1")))
            assertTrue(m.disksChanged(snapshot("root", "d1")), "/home のディスクが抜けたら止める")
            m.close()
        }
    }

    @Test
    fun `止まったマシンでmountを呼ぶと、予約を取らずディスクを開かずにIO`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            val before = b.disks.opened("d1")
            m.stop()
            eventually(10_000, "止まらない") {
                h.scheduler.tick()
                m.state == MachineState.STOPPED
            }
            assertEquals(Errno.IO, assertThrows<FsException> { m.mountDisk("disk:d1", VfsPath.parse("/x"), false) }.errno)
            // 止まった後の予約は新しい世代を持ち取り消されないので、開くと次の台のリースの世代を進めてしまう
            assertEquals(before, b.disks.opened("d1"), "止まったマシンはディスクを開かない")
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    @Test
    fun `起動の途中でmntのマウントより後に挿したディスクは、起動の後にmntにマウントしてdisk-insertedを出す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            var queue: EventQueue? = null
            val changed = java.util.concurrent.CompletableFuture<Boolean>()
            // mountOtherDisks の後、init を作る前に d1 を挿す（起動の途中なので、disksChanged は仕事を積まない）
            h.scheduler.hooks.beforeInitExec = { m ->
                queue = m.subscribeAll()
                changed.complete(m.disksChanged(snapshot("root", "d1")))
            }
            val b = boot(h, "root", wait = false)
            val m = b.m
            awaitRunning(h, m)
            assertEquals(false, changed.get(10, TimeUnit.SECONDS))
            eventually(10_000, "起動の後にマウントしない") { m.mountAt("/mnt/d1") == "disk:d1" }
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(queue!!).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", "d1", "/mnt/d1")), m.diskEvents(queue!!))
            assertEquals(1, b.disks.opened("d1"))
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    // ---- マウントとディスクの一覧（#453、ADR 0453） ----

    @Test
    fun `listMountsはマウントを足した順に、FSの容量を付けて返す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1", "d2")
            val m = b.m
            val mounts = m.listMounts()
            assertTrue(mounts.map { it.source }.containsAll(listOf("disk:root", "tmp", "dev", "disk:d1", "disk:d2")), mounts.toString())
            // テストのディスク（TestMemoryFileSystem）は容量を知らないので none、/tmp と /dev は some
            assertNull(mounts.first { it.source == "disk:d1" }.usage)
            assertNotNull(mounts.first { it.source == "tmp" }.usage)
            assertEquals(FsUsage(0, 0, 0, 0, 0), mounts.first { it.source == "dev" }.usage)
            assertFalse(mounts.first { it.source == "disk:d1" }.readOnly)
            m.close()
        }
    }

    @Test
    fun `listMountsの並びはunmountしてmountし直すと後ろへ回る`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1", "d2")
            val m = b.m
            // 期待値はカーネルの表（m.kernel.mounts）からは作らず、一覧どうしの前後で比べる
            val before = m.listMounts().map { it.source }
            assertTrue(before.indexOf("disk:d1") in 0 until before.indexOf("disk:d2"), before.toString())
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            assertEquals(before.filter { it != "disk:d1" }, m.listMounts().map { it.source })
            m.mountDisk("disk:d1", VfsPath.parse("/x"), false)
            val after = m.listMounts()
            assertEquals(before.filter { it != "disk:d1" } + "disk:d1", after.map { it.source })
            assertEquals("/x", after.last().point)
            m.close()
        }
    }

    @Test
    fun `listMountsのreadOnlyはマウントの指定とFS自体の読み取り専用のどちらでもtrueで、idはfilestatのdevと同じ`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // root は読み取り専用でマウントする（マウントの指定の側）
            val b = boot(h, "root", "d1", "d2", disks = Disks(rootFs(), rootReadOnly = true))
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/d2"))
            m.mountDisk("disk:d2", VfsPath.parse("/ro"), true)
            // FS 自体が読み取り専用（マウントの指定は読み書き）
            m.vfs.mount(VfsPath.parse("/rofs"), TestMemoryFileSystem(readOnly = true), readOnly = false, source = "test:rofs")
            m.vfs.mount(VfsPath.parse("/rwfs"), TestMemoryFileSystem(), readOnly = false, source = "test:rwfs")
            val mounts = m.listMounts()
            val readOnlyBySource = mounts.associate { it.source to it.readOnly }
            assertEquals(true, readOnlyBySource["disk:root"], "読み取り専用でマウントした root")
            assertEquals(true, readOnlyBySource["disk:d2"], "読み取り専用でマウントしたディスク")
            assertEquals(true, readOnlyBySource["test:rofs"], "FS 自体が読み取り専用")
            assertEquals(false, readOnlyBySource["disk:d1"])
            assertEquals(false, readOnlyBySource["test:rwfs"])
            // id は stat().device（p1 の filestat.dev）と同じ値
            for (e in mounts) {
                assertEquals(e.id.toLong(), m.vfs.stat(VfsPath.parse(e.point)).device, "${e.point} (${e.source})")
            }
            assertEquals(mounts.size, mounts.map { it.id }.toSet().size, "id は重ならない")
            m.close()
        }
    }

    @Test
    fun `listMountsはFSがつながっていない項目で落ちず、usageはnoneでreadOnlyはマウントの指定のまま`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            // 復元のとき、Vfs.attach の前の表（マウントの項目だけでファイルシステムがない）を作る
            val (ro, rw) = m.lock.withLockValue {
                m.kernel.mounts.add(VfsPath.parse("/ghost-ro"), true, "disk:ghost-ro") to
                    m.kernel.mounts.add(VfsPath.parse("/ghost-rw"), false, "disk:ghost-rw")
            }
            val mounts = m.listMounts().associateBy { it.id }
            assertNull(mounts.getValue(ro.id).usage)
            assertEquals(true, mounts.getValue(ro.id).readOnly)
            assertNull(mounts.getValue(rw.id).usage)
            assertEquals(false, mounts.getValue(rw.id).readOnly)
            assertEquals("disk:ghost-ro", mounts.getValue(ro.id).source)
            m.lock.withLockValue {
                m.kernel.mounts.remove(ro.id)
                m.kernel.mounts.remove(rw.id)
            }
            m.close()
        }
    }

    /** 容量を読むとき（[FileSystem.usage]）に [onUsage] を呼ぶファイルシステム */
    private class HookedUsage(private val inner: TestMemoryFileSystem = TestMemoryFileSystem(), val onUsage: () -> Unit) : FileSystem by inner {
        override fun usage(): FsUsage {
            onUsage()
            return FsUsage(10, 4, 6, 8, 2)
        }
    }

    @Test
    fun `listMountsは容量を読んでいる間に外れたマウントの項目を残し、usageだけnoneにする`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1")
            val m = b.m
            var unmountDuringUsage = false
            val fs = HookedUsage {
                // listMounts はカーネルロックを持たずに容量を読むので、ここで外せる
                if (unmountDuringUsage) m.unmountAt(VfsPath.parse("/hooked"))
            }
            m.vfs.mount(VfsPath.parse("/hooked"), fs, readOnly = false, source = "test:hooked")
            // 外れなければ容量が出る
            assertEquals(FsUsage(10, 4, 6, 8, 2), m.listMounts().first { it.source == "test:hooked" }.usage)
            unmountDuringUsage = true
            val mounts = m.listMounts()
            val hooked = mounts.firstOrNull { it.source == "test:hooked" }
            assertNotNull(hooked, "写した項目は残る: $mounts")
            assertNull(hooked!!.usage, "読んだ後に外れたマウントの容量は見せない")
            assertEquals("/hooked", hooked.point)
            // 外れた後の一覧には出ない
            assertTrue(m.listMounts().none { it.source == "test:hooked" })
            // ほかの項目の容量は影響を受けない
            assertNotNull(mounts.first { it.source == "tmp" }.usage)
            m.close()
        }
    }

    /** 同梱でない、小さなイメージ（`d/a.txt` に 5 バイト。項目は `/d` と `/d/a.txt` の2つ） */
    private fun tinyImage(): ImageFileSystem {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("d/a.txt"))
            z.write("hello".toByteArray())
            z.closeEntry()
        }
        return ImageFileSystem.fromZip(java.io.ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `listMountsは本物のDiskFileSystemとイメージの容量をそのまま返す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val real = DiskFileSystem.open(dir.resolve("real"), DiskQuota(maxBytes = 1L shl 20, maxEntries = 64))
            val disks = Disks(rootFs())
            disks.overrides["d1"] = real
            val b = boot(h, "root", "d1", disks = disks)
            val m = b.m
            m.vfs.mount(VfsPath.parse("/img"), tinyImage(), readOnly = true, source = "disk:image:tiny")
            val mounts = m.listMounts()
            val d1 = mounts.first { it.source == "disk:d1" }
            assertEquals(real.usage(), d1.usage)
            assertEquals(1L shl 20, d1.usage!!.totalBytes)
            assertEquals(64L, d1.usage!!.totalEntries)
            val img = mounts.first { it.source == "disk:image:tiny" }
            assertEquals(FsUsage(totalBytes = 5, usedBytes = 5, availableBytes = 0, totalEntries = 2, usedEntries = 2), img.usage)
            assertTrue(img.readOnly)
            m.close()
        }
    }

    @Test
    fun `listDisksはスロットの順で、マウントしていないディスクも位置なしで出し、その sourceでmountでき、抜くと消え、ディスクを開かない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1", "d2")
            val m = b.m
            val cap = 1L shl 20
            assertEquals(
                listOf(DiskListing("root", cap, 64, "/"), DiskListing("d1", cap, 64, "/mnt/d1"), DiskListing("d2", cap, 64, "/mnt/d2")),
                m.listDisks(),
            )
            val opens = b.disks.opened("d1")
            m.unmountAt(VfsPath.parse("/mnt/d1"))
            val d1 = m.listDisks().first { it.diskId == "d1" }
            assertNull(d1.mountPoint)
            assertEquals(opens, b.disks.opened("d1"), "一覧はディスクを開かない")
            m.mountDisk(MachineLimits.DISK_SOURCE_PREFIX + d1.diskId, VfsPath.parse("/x"), false)
            assertEquals("/x", m.listDisks().first { it.diskId == "d1" }.mountPoint)
            // スロットを入れ替えただけなら、並びだけが変わり、外さない・イベントも出さない
            val q = m.subscribeAll()
            assertFalse(m.disksChanged(snapshot("root", "d2", "d1")))
            assertEquals(listOf("root", "d2", "d1"), m.listDisks().map { it.diskId })
            assertEquals(emptyList<Triple<String, String, String>>(), m.diskEvents(q))
            assertEquals("disk:d1", m.mountAt("/x"))
            // 抜くと消える
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            assertEquals(listOf("root", "d1"), m.listDisks().map { it.diskId })
            assertTrue(m.listMounts().none { it.source == "disk:d2" })
            m.close()
        }
    }

    @Test
    fun `Machineが起動で作るsourceは4種だけで、種類はimage・tmp・dev・diskになる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // 同梱イメージ（image:<名前>）から起動する経路。開き方だけをフックで差し替え、source は Machine が識別子から作る
            val imageId = BootConfig.IMAGE_DISK_PREFIX + "test"
            val config = BootConfig.builder().addBootDisk(imageId).homeDisk("h1").build()
            val disks = Disks(rootFs(), rootReadOnly = true, rootIds = setOf(imageId))
            val b = boot(h, "h1", "d1", disks = disks, config = config)
            val m = b.m
            // 期待値は KibiFs.kindOf からは作らず、文字列と種類を書き下す（ADR 0453 の「Machine が作る source はこの4種だけ」）
            val expected = mapOf(
                "/" to ("disk:image:test" to KibiFs.Kind.IMAGE),
                "/tmp" to ("tmp" to KibiFs.Kind.TMP),
                "/dev" to ("dev" to KibiFs.Kind.DEV),
                "/home" to ("disk:h1" to KibiFs.Kind.DISK),
                "/mnt/d1" to ("disk:d1" to KibiFs.Kind.DISK),
            )
            val infos = m.listMounts().map(KibiFs::mountInfoOf)
            assertEquals(expected, infos.associate { it.point to (it.source to it.kind) })
            m.close()
        }
    }

    @Test
    fun `Machineが根のディスクから起動したときの根の種類はdisk`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = boot(h, "root").m
            val root = m.listMounts().map(KibiFs::mountInfoOf).single { it.point == "/" }
            assertEquals("disk:root" to KibiFs.Kind.DISK, root.source to root.kind)
            m.close()
        }
    }

    @Test
    fun `list-disksは自動マウントを飛ばしたディスクを位置なしで出し、そのsourceでmountできる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val root = rootFs()
            root.createFile(VfsPath.parse("/mnt"))
            val b = boot(h, "root", disks = Disks(root))
            val m = b.m
            val q = m.subscribeAll()
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            eventually(10_000, "disk-inserted が来ない") { m.diskEvents(q).isNotEmpty() }
            val cap = 1L shl 20
            assertEquals(listOf(DiskListing("root", cap, 64, "/"), DiskListing("d1", cap, 64, null)), m.listDisks())
            assertEquals(KibiFs.DiskInfo("disk:d1", cap, 64, null), KibiFs.diskInfoOf(m.listDisks()[1]))
            m.mountDisk("disk:d1", VfsPath.parse("/x"), false)
            assertEquals("/x", m.listDisks().first { it.diskId == "d1" }.mountPoint)
            m.close()
        }
    }

    @Test
    fun `list-disksは自動マウントで開いている途中のディスクを位置なしで出し、そのときのmountはbusy`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root")
            val m = b.m
            val gate = Disks.Gate()
            b.disks.gates["d1"] = gate
            assertFalse(m.disksChanged(snapshot("root", "d1")))
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            try {
                // 予約（DiskClaim）の間は、まだマウントの表にないので位置なし
                assertNull(m.listDisks().single { it.diskId == "d1" }.mountPoint)
                assertEquals(Errno.BUSY, assertThrows<FsException> { m.mountDisk("disk:d1", VfsPath.parse("/x"), false) }.errno)
            } finally {
                gate.release.countDown()
            }
            eventually(10_000, "マウントしない") { m.listDisks().single { it.diskId == "d1" }.mountPoint == "/mnt/d1" }
            m.close()
        }
    }

    @Test
    fun `p1のlist-mountsとlist-disksは一覧を書き、cabi_reallocの中から呼んでもトラップしない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val b = boot(h, "root", "d1", "d2")
            val m = b.m
            m.unmountAt(VfsPath.parse("/mnt/d2"))
            val mounts = m.listMounts()
            val out = runKibiFs(h, m, "mounts", "disks", "mounts-realloc").lines().filter { it.isNotEmpty() }
            val expectedMounts = mounts.map { e ->
                val kind = KibiFs.kindOf(e.source).ordinal
                val u = e.usage
                val usage = if (e.source == "tmp") null else if (u == null) "none" else "${u.totalBytes} ${u.usedBytes} ${u.availableBytes} ${u.totalEntries ?: "-"} ${u.usedEntries}"
                "m ${e.point} ${e.source} $kind ${if (e.readOnly) 1 else 0} ${e.id}" + (usage?.let { " $it" } ?: "")
            }
            val mountLines = out.filter { it.startsWith("m ") }
            assertEquals(expectedMounts.size, mountLines.size, out.toString())
            for ((line, exp) in mountLines.zip(expectedMounts)) {
                // /tmp は使用量がプロセスのメモリで動くので、数の前までを比べる
                if (exp.contains(" tmp ")) {
                    assertTrue(line.startsWith(exp + " "), "$line / $exp")
                    assertEquals(11, line.split(" ").size, line)
                    assertEquals("-", line.split(" ")[9], line)
                } else {
                    assertEquals(exp, line)
                }
            }
            assertTrue("mounts ${mounts.size}" in out)
            val cap = 1L shl 20
            assertEquals(
                listOf("d disk:root $cap 64 /", "d disk:d1 $cap 64 /mnt/d1", "d disk:d2 $cap 64 -", "disks 3"),
                out.filter { it.startsWith("d") },
            )
            assertEquals("mounts-realloc ${mounts.size} ${mounts.size}", out.last())
            m.close()
        }
    }

    @Test
    fun `p1のlist-mountsは本物のDiskFileSystemのsome(usage)とイメージの項目をkibi_fsの経路で書く`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val real = DiskFileSystem.open(dir.resolve("real"), DiskQuota(maxBytes = 1L shl 20, maxEntries = 64))
            val disks = Disks(rootFs())
            disks.overrides["d1"] = real
            val b = boot(h, "root", "d1", disks = disks)
            val m = b.m
            val img = m.vfs.mount(VfsPath.parse("/img"), tinyImage(), readOnly = true, source = "disk:image:tiny")
            val d1Id = m.listMounts().first { it.source == "disk:d1" }.id
            val lines = runKibiFs(h, m, "mounts").lines().filter { it.startsWith("m ") }
            // 期待値は実装の一覧からではなく、ファイルシステム自身の値と、イメージに入れたものの数から作る。種類は disk = 0、image = 1
            val u = real.usage()
            assertEquals(1L shl 20, u.totalBytes)
            assertEquals(
                "m /mnt/d1 disk:d1 0 0 $d1Id ${u.totalBytes} ${u.usedBytes} ${u.availableBytes} 64 ${u.usedEntries}",
                lines.first { it.startsWith("m /mnt/d1 ") },
            )
            assertEquals("m /img disk:image:tiny 1 1 ${img.id} 5 5 0 2 2", lines.first { it.startsWith("m /img ") })
            m.close()
        }
    }
}
