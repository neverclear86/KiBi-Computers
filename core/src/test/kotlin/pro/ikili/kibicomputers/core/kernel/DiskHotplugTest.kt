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
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Driver
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
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
    private class Disks(val root: FileSystem) : BootVolumeOpener {
        val fs = ConcurrentHashMap<String, CountingDisk>()
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        val gates = ConcurrentHashMap<String, Gate>()

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
            if (diskId == "root") return BootVolume(root, readOnly = false)
            if (diskId.startsWith("bad")) return null
            return BootVolume(disk(diskId), readOnly = false)
        }

        fun opened(id: String): Int = opens[id]?.get() ?: 0
    }

    private class Booted(val h: Harness, val m: Machine, val disks: Disks)

    /** [ids] を挿したマシンを root から起動して、動くまで待つ */
    private fun boot(h: Harness, vararg ids: String, disks: Disks = Disks(rootFs()), wait: Boolean = true): Booted {
        h.scheduler.hooks.bootVolumes = disks
        val m = h.machine(fuelPerTick = 10_000_000, environment = MachineEnvironment.builder().disks(snapshot(*ids)).build())
        assertTrue(m.boot(BootConfig.builder().addBootDisk("root").build()))
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
    private fun runKibiFs(h: Harness, m: Machine, vararg args: String): String {
        val program = Program.load(EndiveRuntime(), resource("kibi-fs-wasi.wasm"))
        val output = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
        val options = SpawnOptions(
            allowUnmetered = true,
            fds = SpawnOptions.ROOT_PREOPEN,
            args = listOf("kibi-fs") + args,
            stdio = StdioSpec(StdioSource.Console, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Console),
        )
        val pid = m.spawn(program, WasiP1Driver(program.module), options)
        eventually(60_000, "終わらない") {
            h.scheduler.tick()
            m.stateOf(pid).isTerminal
        }
        return m.lock.withLockValue {
            val s = m.kernel.objects[output] as ByteStream
            val buf = ByteArray(s.available)
            if (buf.isNotEmpty()) s.read(buf, 0, buf.size, m.wakeSink)
            String(buf, Charsets.UTF_8)
        }
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
}
