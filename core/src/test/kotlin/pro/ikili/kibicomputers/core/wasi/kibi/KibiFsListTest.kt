package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.DiskListing
import pro.ikili.kibicomputers.core.kernel.DiskSnapshot
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MountListing
import pro.ikili.kibicomputers.core.kernel.ResolvedDisk
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.vfs.FsUsage
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** `kibi:fs/mounts` の `list-mounts`・`list-disks` の意味の層（[KibiFs]。#453、ADR 0453） */
class KibiFsListTest {
    @Test
    fun `種類はsourceから決め、知らないsourceはdiskに倒す`() {
        assertEquals(KibiFs.Kind.DISK, KibiFs.kindOf("disk:0123-abcd"))
        assertEquals(KibiFs.Kind.IMAGE, KibiFs.kindOf("disk:image:os"))
        assertEquals(KibiFs.Kind.TMP, KibiFs.kindOf("tmp"))
        assertEquals(KibiFs.Kind.DEV, KibiFs.kindOf("dev"))
        assertEquals(KibiFs.Kind.DISK, KibiFs.kindOf("test:root"))
        assertEquals(KibiFs.Kind.DISK, KibiFs.kindOf(""))
        // 判別子は WIT の enum fs-kind の順
        assertEquals(listOf("DISK", "IMAGE", "TMP", "DEV"), KibiFs.Kind.entries.map { it.name })
    }

    @Test
    fun `カーネルの写しをmount-infoとdisk-infoにする`() {
        val usage = FsUsage(100, 40, 60, 8, 2)
        assertEquals(
            KibiFs.MountInfo("/mnt/a", "disk:a", KibiFs.Kind.DISK, true, 3, usage),
            KibiFs.mountInfoOf(MountListing(3, "/mnt/a", "disk:a", true, usage)),
        )
        assertEquals(KibiFs.DiskInfo("disk:a", 1024, 16, null), KibiFs.diskInfoOf(DiskListing("a", 1024, 16, null)))
        assertEquals(KibiFs.DiskInfo("disk:b", 1024, 16, "/"), KibiFs.diskInfoOf(DiskListing("b", 1024, 16, "/")))
    }

    @Test
    fun `list-mountsとlist-disksは1件につきMOUNT_LIST_FUEL_PER_ENTRYの燃料を引く`() = timeout(Duration.ofSeconds(30)) {
        val module = FakeModule.builder().fuelMetered().memory(1).build()
        Harness().use { h ->
            // list-disks が0件だと件数 × 係数も 0 になり、引き忘れや係数の誤りを検出できないので、ディスクを3枚挿しておく
            val disks = DiskSnapshot.of(listOf("a", "b", "c").associateWith { ResolvedDisk(Path.of("unused-$it"), 1L shl 20, 64) })
            val m = h.machine(fuelPerTick = 1_000_000, environment = MachineEnvironment.builder().disks(disks).build())
            val root = TestMemoryFileSystem().also { it.createDirectory(VfsPath.parse("/a")) }
            m.vfs.mount(VfsPath.ROOT, root, readOnly = false, source = "test:root")
            m.vfs.mount(VfsPath.parse("/a"), TestMemoryFileSystem(), readOnly = true, source = "test:a")
            val error = AtomicReference<Throwable>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    val before = ctx.fuelHolder.fuel
                    val n = KibiFs.listMounts(ctx).size
                    assertEquals(n * KibiFs.MOUNT_LIST_FUEL_PER_ENTRY, before - ctx.fuelHolder.fuel, "件数 $n に比例して引く")
                    assertEquals(2, n)
                    val before2 = ctx.fuelHolder.fuel
                    val d = KibiFs.listDisks(ctx).size
                    assertEquals(3, d)
                    assertEquals(3 * KibiFs.MOUNT_LIST_FUEL_PER_ENTRY, before2 - ctx.fuelHolder.fuel, "ディスク $d 枚に比例して引く")
                } catch (t: Throwable) {
                    error.set(t)
                }
                StepResult.Exit(0)
            }
            val pid = m.spawn(h.program(module), driver)
            h.tickUntilTrue("終わらない: ${error.get()}") { m.stateOf(pid).isTerminal }
            error.get()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
        }
    }
}
