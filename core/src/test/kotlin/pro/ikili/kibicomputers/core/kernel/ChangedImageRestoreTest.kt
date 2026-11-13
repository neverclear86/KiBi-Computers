package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pro.ikili.kibicomputers.core.display.CharWidth
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 同梱イメージ（`image:os`）の中身だけが変わったビルドで、動いている台を戻す場面（#670）。
 *
 * 本物の `/sbin/init` から標準 OS を起動し（根は同梱イメージ、`/home` は書き込めるディスク）、シェルが入力を待っているところで
 * 保存して閉じる。次に「イメージが同じ・`/bin/sh` だけ変えた」×「同じスケジューラ・新しいスケジューラ（JVM の起動し直しのまね。
 * 読み込みのキャッシュが冷えている）」の組み合わせで戻し、描き直し・続きの入力・`reboot` の後の起動を確かめる。
 * 置き場（モジュールのバイト列）と保存先、`/home` の中身は、保存と復元で共有する（ワールドのフォルダーと同じ）
 */
class ChangedImageRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(240)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    private class ScreenHost(columns: Int = 80, rows: Int = 25) : MachineHost {
        private val out = ByteArrayOutputStream()
        val terminal = AnsiTerminal(TextBuffer(columns, rows), 256)

        @Volatile
        var state: MachineState = MachineState.STOPPED

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            this.state = state
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(this) {
                out.write(bytes, 0, bytes.size)
                terminal.write(bytes)
            }
        }

        fun text(): String = synchronized(this) { out.toString(Charsets.UTF_8) }

        fun bytes(): Int = synchronized(this) { out.size() }

        fun row(y: Int): String = synchronized(this) {
            buildString {
                var x = 0
                val b = terminal.buffer
                while (x < b.width) {
                    val cp = b.getCodePoint(x, y)
                    appendCodePoint(cp)
                    x += if (CharWidth.isWide(cp)) 2 else 1
                }
            }.trimEnd()
        }

        fun screen(): String = synchronized(this) { (0 until terminal.buffer.height).joinToString("\n") { row(it) } }
    }

    private fun osZip(): ByteArray = checkNotNull(javaClass.getResourceAsStream("${ImageFileSystem.RESOURCE_DIR}/os.zip")) { "os.zip がリソースにない" }.use { it.readBytes() }

    /** os.zip の [paths] のファイルだけ、末尾にカスタムセクションを足して（動きは同じでハッシュが違う WASM）作り直す */
    private fun changedZip(original: ByteArray, paths: Set<String>): ByteArray {
        val out = ByteArrayOutputStream()
        var changed = 0
        ZipOutputStream(out).use { zip ->
            ZipInputStream(ByteArrayInputStream(original)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory && "/" + entry.name.trimStart('/') in paths) {
                        zip.write(SnapshotDecoderModuleSourcesTest.withCustomSection(bytes))
                        changed++
                    } else {
                        zip.write(bytes)
                    }
                    zip.closeEntry()
                }
            }
        }
        assertEquals(paths.size, changed, "差し替えるファイルがイメージにない")
        return out.toByteArray()
    }

    private fun harness(modules: MemoryModuleStore): Harness = Harness(execRuntime = EndiveRuntime(), moduleStore = modules)

    /** 根を [image]、`/home` を [home] として開く口を [h] に入れる */
    private fun volumes(h: Harness, image: () -> ImageFileSystem, home: TestMemoryFileSystem) {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id ->
            when (id) {
                OS -> BootVolume(image(), readOnly = true)
                HOME -> BootVolume(home, readOnly = false)
                else -> null
            }
        }
    }

    private val bootConfig: BootConfig = BootConfig.builder().bootDisks(listOf(OS)).homeDisk(HOME).build()

    /** init の子（コンソールのシェル）の pid */
    private fun shellPid(m: Machine): Int? = m.lock.withLockValue {
        m.kernel.processes.all().firstOrNull { it.pid != m.kernel.initPid && !it.state.isTerminal }?.pid
    }

    /** シェルがプロンプトを出して入力を待ち、出力が止まるまで回す。シェルの pid を返す */
    private fun settlePrompt(h: Harness, m: Machine, host: ScreenHost, what: String): Int {
        var last = -1
        var still = 0
        var pid: Int? = null
        eventuallyDescribe(90_000, { "$what: シェルが入力を待たない（${host.state}）\n${host.screen()}" }) {
            h.scheduler.tick()
            Thread.sleep(1)
            pid = shellPid(m)
            val p = pid
            val idle = p != null && m.processState(p) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
            val now = host.bytes()
            still = if (idle && now == last) still + 1 else 0
            last = now
            still >= 5
        }
        return checkNotNull(pid)
    }

    /**
     * 組み合わせ1つ。[changeImage] なら、戻すときの根を `/bin/sh` だけ変えたイメージにする。[freshScheduler] なら、
     * 保存と復元で別のスケジューラを使う（置き場・保存先・`/home` だけを共有する）
     */
    @ParameterizedTest(name = "イメージを変える={0}, 新しいスケジューラ={1}")
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `動いている台を戻すと描き直し、続きの入力とrebootが効く`(changeImage: Boolean, freshScheduler: Boolean) = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val home = TestMemoryFileSystem()
        val zip = osZip()
        val imageA = ImageFileSystem.fromZip(ByteArrayInputStream(zip))
        val imageB = if (changeImage) ImageFileSystem.fromZip(ByteArrayInputStream(changedZip(zip, setOf("/bin/sh")))) else imageA
        var current = imageA

        // 保存する側: 起動してシェルの入力待ちまで進め、確定しない入力を入れてから写す
        fun save(h: Harness): Triple<MachineIdentity, Int, ModuleHash> {
            val host = ScreenHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024)
            assertTrue(m.boot(bootConfig))
            val pid = settlePrompt(h, m, host, "起動")
            assertTrue(host.text().endsWith("$ "), host.screen())
            val hash = m.lock.withLockValue { m.kernel.processes[pid]!!.moduleHash }
            m.offerConsoleInput("echo pending".toByteArray())
            settlePrompt(h, m, host, "入力の後")
            val identity = store.write(store.assignNewId(owner)!!, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
            m.close()
            return Triple(identity, pid, hash)
        }

        // 戻す側
        fun restore(h: Harness, identity: MachineIdentity, pid: Int, oldShellHash: ModuleHash) {
            current = imageB
            val host = ScreenHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024)
            val outcomes = Collections.synchronizedList(ArrayList<RestoreOutcome>())
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig, RestoreListener { _, o, _ -> outcomes += o })))
            eventually(90_000, "復元が終わらない") {
                h.scheduler.tick()
                outcomes.isNotEmpty()
            }
            assertEquals(listOf(RestoreOutcome.RESTORED), outcomes.toList())
            // 戻したシェルは保存したときのモジュールで動く（ADR 0030 の規則 4、ADR 0088）
            assertEquals(oldShellHash, m.lock.withLockValue { m.kernel.processes[pid]?.moduleHash }, "戻したシェルのモジュール")
            eventuallyDescribe(60_000, { "描き直さない: ${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.row(0) == "$ echo pending"
            }
            assertEquals(pid, settlePrompt(h, m, host, "描き直しの後"))
            // 続きの入力が効く（echo はシェルの組み込み。書く先は /home のディスク）
            m.offerConsoleInput(" > /home/t\n".toByteArray())
            eventuallyDescribe(60_000, { "入力が効かない: ${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                runCatching { home.stat(VfsPath.parse("/t")).type == FileType.REGULAR_FILE }.getOrDefault(false)
            }
            settlePrompt(h, m, host, "書いた後")
            // reboot（今のイメージの /bin/reboot を起こす）で、今のイメージから上がり直す（pid は振り直すので、出力で見る）
            m.offerConsoleInput("reboot\n".toByteArray())
            eventuallyDescribe(90_000, { "上がり直さない（${host.state}）: ${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.row(2) == "kibi: booting from disk $OS" && host.row(3) == "$"
            }
            val newPid = settlePrompt(h, m, host, "reboot の後")
            val newHash = m.lock.withLockValue { m.kernel.processes[newPid]!!.moduleHash }
            if (changeImage) assertNotEquals(oldShellHash, newHash, "上がり直したシェルは新しいイメージのもの") else assertEquals(oldShellHash, newHash)
            m.close()
        }

        if (freshScheduler) {
            // 保存したスケジューラを閉じてから、新しいスケジューラで戻す（読み込みのキャッシュが冷えている）
            val (identity, pid, hash) = harness(modules).use { h ->
                volumes(h, { current }, home)
                save(h)
            }
            harness(modules).use { h ->
                volumes(h, { current }, home)
                restore(h, identity, pid, hash)
            }
        } else {
            harness(modules).use { h ->
                volumes(h, { current }, home)
                val (identity, pid, hash) = save(h)
                restore(h, identity, pid, hash)
            }
        }
    }

    private companion object {
        const val OS = "image:os"
        const val HOME = "home"
    }
}
