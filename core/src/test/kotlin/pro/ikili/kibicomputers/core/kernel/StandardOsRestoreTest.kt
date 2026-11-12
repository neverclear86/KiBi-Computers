package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.display.CharWidth
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
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
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 標準 OS の `sh`・`nano` が、保存から戻ったとき（`restored` イベント。#87、ADR 0087）に、キーを待たずに画面を描き直すことを確かめる。
 * 戻したマシンの表示は新しい端末（[ScreenHost]）で、保存前の画面は残っていない
 */
class StandardOsRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(180)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    private class ScreenHost(columns: Int = 80, rows: Int = 25) : MachineHost {
        private val out = ByteArrayOutputStream()
        val terminal = AnsiTerminal(TextBuffer(columns, rows), 256)

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

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

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private fun imageFile(path: String): ByteArray {
        val stat = image.stat(VfsPath.parse(path))
        val bytes = ByteArray(stat.size.toInt())
        image.read(stat.node, 0, bytes, 0, bytes.size)
        return bytes
    }

    private fun Machine.put(path: String, bytes: ByteArray) {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

    /** 入力を待つところまで進め、出力が止まるまで待つ */
    private fun settle(h: Harness, m: Machine, host: ScreenHost, pid: Int) {
        var last = -1
        var still = 0
        eventuallyDescribe(60_000, { "入力を待たない: ${m.stateOf(pid)}\n${host.screen()}" }) {
            h.scheduler.tick()
            Thread.sleep(1)
            val state = m.stateOf(pid)
            val idle = state.isTerminal || (state == ProcessState.WAITING_EVENT && m.isAtWaitPoint)
            val now = host.bytes()
            still = if (idle && now == last) still + 1 else 0
            last = now
            still >= 5
        }
    }

    /** [program] を動かし、[setup] の後に入力を待ったところで書いてマシンを閉じる */
    private fun saveRunning(
        h: Harness,
        store: MachineSaveStore,
        program: String,
        args: List<String> = emptyList(),
        setup: (Machine, ScreenHost, Int) -> Unit,
    ): Pair<MachineIdentity, Int> {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
        val host = ScreenHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        for (d in listOf("/bin", "/tmp", "/home")) m.vfs.createDirectory(VfsPath.parse(d))
        m.put("/bin/$program", imageFile("/bin/$program"))
        val pid = m.exec(
            "/bin/$program",
            KernelDrivers.DEFAULT,
            SpawnOptions(args = listOf(program) + args, env = mapOf("PATH" to "/bin", "HOME" to "/home", "PWD" to "/home"), cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN),
        )
        m.lock.withLockValue { m.kernel.initPid = pid }
        settle(h, m, host, pid)
        setup(m, host, pid)
        settle(h, m, host, pid)
        val identity = store.assignNewId(owner)!!
        val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
        m.close()
        return written to pid
    }

    /**
     * 新しい表示のマシンに戻し、受け手が返るまで回す。[probe] は受け手の中（復元の quiesce が閉じる前）で、プロセス [pid] の
     * 状態を見る（少し待ってから。受け手の中でプロセスが動くなら、その間に書いて待ちに戻る）
     */
    private fun restore(
        h: Harness,
        store: MachineSaveStore,
        identity: MachineIdentity,
        pid: Int? = null,
        probe: ((ProcessState) -> Unit)? = null,
    ): Pair<Machine, ScreenHost> {
        val host = ScreenHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
        val outcomes = Collections.synchronizedList(ArrayList<RestoreOutcome>())
        val listener = RestoreListener { machine, outcome, _ ->
            if (pid != null && probe != null) {
                Thread.sleep(300)
                probe(machine.stateOf(pid))
            }
            outcomes += outcome
        }
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), listener)))
        eventually(60_000, "復元が終わらない") {
            h.scheduler.tick()
            outcomes.isNotEmpty()
        }
        assertEquals(listOf(RestoreOutcome.RESTORED), outcomes.toList())
        return m to host
    }

    @Test
    fun `shは戻るとプロンプトと入力中の行を描き直し、続きの入力も効く`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val (saved, pid) = saveRunning(h, store, "sh") { m, host, _ ->
                assertTrue(host.text().endsWith("$ "), host.text())
                m.offerConsoleInput("echo hi".toByteArray())
            }
            var inListener: ProcessState? = null
            val (m, host) = restore(h, store, saved, pid) { inListener = it }
            // 受け手の中では、sh は起こされている（restored が入った）が、まだ1歩も進んでいない（進めば描いて待ちに戻る）
            assertEquals(ProcessState.RUNNABLE, inListener, "受け手の中の sh の状態")
            assertEquals("", host.text(), "受け手の中では、まだ何も描いていない")
            eventuallyDescribe(60_000, { "描き直さない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.row(0) == "$ echo hi"
            }
            settle(h, m, host, pid)
            assertEquals("$ echo hi", host.row(0))
            assertEquals(7 + 2, host.terminal.cursorX, "カーソルは入力中の行の後ろ")
            // 待っている間に空回りしない（キューを空にしてから、入力とキューを待つ）
            val bytes = host.bytes()
            repeat(20) {
                h.scheduler.tick()
                Thread.sleep(1)
            }
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))
            assertTrue(m.isAtWaitPoint)
            assertEquals(bytes, host.bytes())
            // 続きを打つ（戻す前に打った分と続けて1行になる）
            m.offerConsoleInput("!\n".toByteArray())
            eventuallyDescribe(60_000, { "答えない: ${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.row(1) == "hi!" && host.row(2) == "$"
            }
            m.close()
        }
    }

    @Test
    fun `nanoは戻ると代替画面に入り直して全体を描き直す`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val (saved, pid) = saveRunning(h, store, "nano", listOf("note.txt")) { m, host, _ ->
                assertTrue(host.terminal.alternateScreen)
                m.offerConsoleInput("hello".toByteArray())
            }
            val (m, host) = restore(h, store, saved)
            eventuallyDescribe(60_000, { "描き直さない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.row(1) == "hello"
            }
            settle(h, m, host, pid)
            assertTrue(host.text().startsWith("\u001b[?1049h\u001b[?7l"), host.text())
            assertTrue(host.terminal.alternateScreen)
            assertTrue(host.row(0).contains("note.txt"), host.screen())
            assertTrue(host.row(23).startsWith("^G Help"), host.screen())
            assertEquals(5 to 1, host.terminal.cursorX to host.terminal.cursorY)
            // 続きの入力も効く
            m.offerConsoleInput(" world".toByteArray())
            eventuallyDescribe(60_000, { "打てない: ${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.row(1) == "hello world"
            }
            m.close()
        }
    }

    @Test
    fun `キューを開けなくてもshは入力だけを待って動く`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ScreenHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (d in listOf("/bin", "/home")) m.vfs.createDirectory(VfsPath.parse(d))
            for (name in listOf("sh", "echo")) m.put("/bin/$name", imageFile("/bin/$name"))
            val pid = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh"), env = mapOf("PATH" to "/bin", "HOME" to "/home"), cwd = "/", fds = SpawnOptions.ROOT_PREOPEN))
            // マシンのキューを上限まで使い切る（sh の open は NFILE で失敗する）
            m.lock.withLockValue { repeat(EventLimits.MAX_QUEUES) { m.openEventQueueLocked(pid, EventFilter.ALL, 1) } }
            eventuallyDescribe(60_000, { "プロンプトが出ない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.text().endsWith("$ ")
            }
            m.offerConsoleInput("echo ok\n".toByteArray())
            eventuallyDescribe(60_000, { "答えない: ${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.row(1) == "ok" && host.row(2) == "$"
            }
            m.close()
        }
    }

    @Test
    fun `eventsを使うプログラムだけがcabi_reallocをexportし、ほかの export は増えない`() {
        val runtime = EndiveRuntime()
        val eventsProgram = setOf("sh", "nano")
        var checked = 0
        for (dir in listOf("/bin", "/sbin")) {
            val entries = image.readDirectory(image.stat(VfsPath.parse(dir)).node)
            for (entry in entries) {
                val module = runtime.load(imageFile("$dir/${entry.name}"))
                val exports = module.exports.map { it.name }.toSet()
                val events = module.imports.filter { it.module == "kibi:kernel/events@0.1.0" }.map { it.name }.toSet()
                if (entry.name in eventsProgram && dir == "/bin") {
                    assertEquals(
                        setOf("[static]event-queue.open", "[method]event-queue.take", "[resource-drop]event-queue"),
                        events,
                        entry.name,
                    )
                    assertEquals(setOf("memory", "_start", "cabi_realloc"), exports, "${entry.name} の export")
                } else {
                    // events を使わないプログラムには何も足さない（`-rdynamic` は `__stack_pointer` まで足してしまう）
                    assertEquals(emptySet<String>(), events, "${entry.name} が events を import している")
                    assertEquals(setOf("memory", "_start"), exports, "$dir/${entry.name} の export")
                }
                checked++
            }
        }
        assertTrue(checked > 2, "イメージのプログラムを見ていない")
    }
}
