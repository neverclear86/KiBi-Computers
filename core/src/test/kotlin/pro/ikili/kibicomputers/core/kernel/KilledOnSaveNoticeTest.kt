package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.FullSaveRule
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.MachineSnapshotSource
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 写しの中で殺したプロセスの、復元の後の知らせ（#86、ADR 0086）。
 *
 * 待機点型の init（`prompt`）の子に、保存できないブロッキング型の子を作って書き、新しいマシンに戻す。
 * 戻した表では子が KILLED・`SIGKILL` のゾンビで、親に `CHILD_EXITED` が数えてあり、コンソールには受け手（画面をつなぐ）の後に
 * 1行ずつ知らせが出る。init を殺した写しは冷えた起動の前に1行出す
 */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
class KilledOnSaveNoticeTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    private fun newStore(): MachineSaveStore = MachineSaveStore(Files.createDirectories(dir.resolve("saves")), MachineIdIndex(), "test")

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

    /** コンソールに配られた出力（tick の中で） */
    private class Console {
        private val sb = StringBuffer()

        val host = object : MachineHost {
            override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

            override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
                sb.append(String(bytes, Charsets.UTF_8))
            }
        }

        val text: String get() = sb.toString()
    }

    /** 知らせの記録。受け手の中で、その時点までにコンソールに配られた出力を取る */
    private class Finished(private val console: Console, private val onFinished: (Machine, RestoreOutcome) -> Unit = { _, _ -> }) {
        val outcomes: MutableList<Pair<RestoreOutcome, ColdBootCause?>> = Collections.synchronizedList(ArrayList())

        @Volatile
        var consoleInListener: String? = null

        val listener = RestoreListener { m, outcome, cause ->
            consoleInListener = console.text
            outcomes += outcome to cause
            onFinished(m, outcome)
        }
    }

    private fun capture(m: Machine): MachineSnapshotSource {
        var source: MachineSnapshotSource? = null
        eventually(10_000, "写しが取れない") {
            source = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT, null, FullSaveRule(1.0))
            source != null
        }
        return source!!
    }

    /** `prompt` を init として動かし、入力を待つところまで進める。init の pid を返す */
    private fun startPrompt(h: Harness, m: Machine): Int {
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/prompt-wasi.wasm")).use { it.readBytes() }
        val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        val (input, output) = m.lock.withLockValue {
            ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
        }
        val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
        val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
        m.lock.withLockValue { m.kernel.initPid = pid }
        eventually(60_000, "入力を待たない") {
            h.scheduler.tick()
            m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
        }
        return pid
    }

    /** [parent] の子に、門が開くまでホスト関数の中で待つブロッキング型のプロセスを作る（[name] があれば argv[0]） */
    private fun spawnBlocker(h: Harness, m: Machine, gate: Gate, parent: Int, name: String?): Int {
        val args = if (name == null) emptyList() else listOf(name)
        val pid = m.spawn(h.program(emptyModule()), gated(gate, kind = ProcessKind.BLOCKING), SpawnOptions(args = args), parent)
        h.tickUntilTrue("子が待たない") { m.stateOf(pid) == ProcessState.WAITING_EVENT }
        return pid
    }

    private class Saved(val identity: MachineIdentity, val init: Int, val children: List<Int>, val killed: List<Int>)

    /** init（`prompt`）と、[names] の数だけのブロッキング型の子を作って書き、閉じる */
    private fun saveWithBlockers(h: Harness, store: MachineSaveStore, names: List<String?>): Saved {
        val m = h.machine(fuelPerTick = 10_000_000)
        val init = startPrompt(h, m)
        val gate = Gate(m)
        val children = names.map { spawnBlocker(h, m, gate, init, it) }
        val written = store.write(store.assignNewId(owner)!!, owner, capture(m))
        val killed = store.readGeneration(written.identity.id, written.identity.generation).snapshot.killedPids
        // 元のマシンは殺さない（ADR 0081）
        for (pid in children) assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid), "元のマシンの子を殺した")
        gate.open()
        m.close()
        return Saved(written.identity, init, children, killed)
    }

    private fun restore(h: Harness, store: MachineSaveStore, identity: MachineIdentity, console: Console, finished: Finished): Machine {
        // 戻すマシンは、根のディスクを新しい空のメモリのファイルシステムとして開く
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
        val m = h.machine(fuelPerTick = 10_000_000, host = console.host)
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), finished.listener)))
        eventually(60_000, "知らせが来ない") {
            h.scheduler.tick()
            finished.outcomes.isNotEmpty()
        }
        // 受け手の後に並べた知らせを配る
        repeat(3) { h.scheduler.tick() }
        return m
    }

    private fun killedLines(text: String): List<String> = text.lines().map { it.trimEnd('\r') }.filter { "could not be saved" in it }

    @Test
    fun `写しの中で殺したブロッキング型の子は、戻すとKILLEDのゾンビで親に数えてあり、受け手の後にコンソールへ1行ずつ出る`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWithBlockers(h, store, listOf("blocker\u001b[2J", null))
            assertEquals(saved.children, saved.killed, "殺した pid を KILLED 節に書いていない")

            val console = Console()
            val finished = Finished(console)
            val m = restore(h, store, saved.identity, console, finished)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            assertTrue(killedLines(finished.consoleInListener.orEmpty()).isEmpty(), "受け手の中で、もう知らせを配っている")

            for (pid in saved.children) {
                assertEquals(ProcessState.KILLED, m.stateOf(pid))
                val status = m.exitStatusOf(pid)
                assertEquals(ExitReason.KILLED, status?.reason)
                assertEquals(KillSignal.KILL, status?.signal)
            }
            val pending = m.lock.withLockValue {
                (m.kernel.objects[m.kernel.processes[saved.init]!!.objectId] as ProcessObject).pendingChildExits
            }
            assertEquals(saved.children.size, pending, "親に子の終わりを数えていない")

            val (labelled, bare) = saved.children
            assertEquals(
                listOf(
                    "kibi: blocker?[2J (pid $labelled) was killed: could not be saved",
                    "kibi: pid $bare was killed: could not be saved",
                ),
                killedLines(console.text),
            )
            assertEquals(MachineState.RUNNING, m.state)

            // 殺したものはゾンビなので、次の保存の KILLED 節には入らず、もう一度戻しても重ねて出ない
            val again = store.write(store.assignNewId(owner)!!, owner, capture(m))
            assertEquals(emptyList<Int>(), store.readGeneration(again.identity.id, again.identity.generation).snapshot.killedPids)
            m.close()
            val console2 = Console()
            val m2 = restore(h, store, again.identity, console2, Finished(console2))
            assertTrue(killedLines(console2.text).isEmpty(), "2回目の復元でも知らせた: ${console2.text}")
            m2.close()
        }
    }

    @Test
    fun `一度に殺した数が上限を超えると、残りは数だけの1行にまとめる`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val count = Machine.MAX_KILLED_NOTICES + 3
            val saved = saveWithBlockers(h, store, List(count) { "b$it" })
            assertEquals(count, saved.killed.size)
            val console = Console()
            val m = restore(h, store, saved.identity, console, Finished(console))
            val lines = killedLines(console.text)
            assertEquals(Machine.MAX_KILLED_NOTICES + 1, lines.size, console.text)
            assertEquals("kibi: b0 (pid ${saved.children[0]}) was killed: could not be saved", lines.first())
            assertEquals("kibi: 3 more processes were killed: could not be saved", lines.last())
            m.close()
        }
    }

    @Test
    fun `initを写しの中で殺した写しは、冷えた起動の前に1行出し、取り消せば冷えた起動を始めず出さない`() = timeout(limit) {
        val store = newStore()
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val init = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.BLOCKING))
            h.tickUntilTrue { m.stateOf(init) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue { m.kernel.initPid = init }
            val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
            assertEquals(ColdBootReason.INIT_KILLED.code, store.readGeneration(identity.id, identity.generation).snapshot.coldBootCode)
            gate.open()
            m.close()

            // 受け手が冷えた起動を始める（ComputerCase と同じ）
            val console = Console()
            val finished = Finished(console) { machine, outcome -> if (outcome == RestoreOutcome.COLD_BOOT) machine.coldBootAfterRestore() }
            val m2 = restore(h, store, identity, console, finished)
            assertEquals(listOf(RestoreOutcome.COLD_BOOT to ColdBootCause.KILLED_SECTION), finished.outcomes.toList())
            eventually(10_000, "起動のメッセージが出ない") {
                h.scheduler.tick()
                console.text.substringAfter(Machine.INIT_KILLED_NOTICE, "").isNotBlank()
            }
            val text = console.text
            assertFalse(finished.consoleInListener.orEmpty().contains(Machine.INIT_KILLED_NOTICE), "受け手より前に配った")
            assertEquals(1, text.split(Machine.INIT_KILLED_NOTICE).size - 1, text)
            assertTrue(text.trimStart().startsWith(Machine.INIT_KILLED_NOTICE), "起動のメッセージより後に出た: $text")
            m2.close()

            // 冷えた起動を始めずに止めたら（取り消し）出さない。止めた後は冷えた起動待ちでなく、始めようとしても何も書かない。
            // 印（coldBootNotice）を捨てる `stopLocked` の1行そのものは、取り消した Machine が restore を二度と受け付けず
            // （coldBootPending も立たない）、外から観測できないので、ここでは確かめない
            val console3 = Console()
            val started = AtomicBoolean(true)
            val finished3 = Finished(console3) { machine, _ ->
                machine.stop()
                started.set(machine.coldBootAfterRestore())
            }
            val m3 = restore(h, store, identity, console3, finished3)
            assertFalse(started.get(), "取り消した後に冷えた起動を始めた")
            assertFalse(m3.isColdBootPending)
            repeat(5) { h.scheduler.tick() }
            assertFalse(console3.text.contains(Machine.INIT_KILLED_NOTICE), console3.text)
            m3.close()
        }
    }

    /** 起動の途中（[reboot] を求めていれば再起動の待ち）で、殺さずに写した冷えた起動の写しを書く。書いた世代の識別を返す */
    private fun saveWholeTransient(h: Harness, store: MachineSaveStore, reboot: Boolean): MachineIdentity {
        val fs = TestMemoryFileSystem()
        fs.createDirectory(VfsPath.parse("/sbin"))
        val bytes = "fake-init".toByteArray()
        h.runtime.define(bytes, FakeModule.builder().fuelMetered().export("_start", VOID) { _, _ -> LongArray(0) }.build())
        val node = fs.createFile(VfsPath.parse(BootConfig.INIT_PATH))
        fs.write(node, 0, bytes, 0, bytes.size)
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "d") BootVolume(fs, readOnly = false) else null }
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.releaseOnClose { release.countDown() } // 途中で落ちても止めたスレッドを放す（#916）
        h.scheduler.hooks.beforeInitExec = { _ ->
            reached.countDown()
            release.await()
        }
        val m = h.machine()
        assertTrue(m.boot(BootConfig.builder().addBootDisk("d").build()))
        reached.awaitOrFail()
        if (reboot) assertTrue(m.reboot())
        val source = requireNotNull(m.captureSnapshot(CapturePolicy.KILL_TRANSIENT, null, FullSaveRule(1.0))) { "写しが取れない" }
        val identity = store.write(store.assignNewId(owner)!!, owner, source).identity
        release.countDown()
        h.scheduler.hooks.beforeInitExec = null
        m.stop()
        assertTrue(m.awaitTermination(5_000))
        m.close()
        return identity
    }

    @Test
    fun `起動の途中・再起動の待ちの写しの冷えた起動では、initを殺した1行を出さない`() = timeout(limit) {
        for ((reboot, reason) in listOf(false to ColdBootReason.BOOTING, true to ColdBootReason.REBOOT_PENDING)) {
            val store = newStore()
            Harness().use { h ->
                val identity = saveWholeTransient(h, store, reboot)
                // 理由は非 null で、init を殺した写しと同じ KILLED 節に書く（ColdBootCause は KILLED_SECTION のまま）
                assertEquals(reason.code, store.readGeneration(identity.id, identity.generation).snapshot.coldBootCode, "理由が違う")

                val console = Console()
                val finished = Finished(console) { machine, outcome -> if (outcome == RestoreOutcome.COLD_BOOT) machine.coldBootAfterRestore() }
                val m = restore(h, store, identity, console, finished)
                assertEquals(listOf(RestoreOutcome.COLD_BOOT to ColdBootCause.KILLED_SECTION), finished.outcomes.toList(), "$reason")
                repeat(5) { h.scheduler.tick() }
                assertTrue(killedLines(console.text).isEmpty() && !console.text.contains(Machine.INIT_KILLED_NOTICE), "$reason: ${console.text}")
                m.close()
            }
        }
    }

    @Test
    fun `殺していない写しの冷えた起動では出さない`() = timeout(limit) {
        val store = newStore()
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            // 保存に対応した待機点型でも、テストの層は読む側が拒む（UNSUPPORTED_PROCESS。殺したものはない）
            val init = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(init) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue { m.kernel.initPid = init }
            val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
            gate.open()
            m.close()

            val console = Console()
            val finished = Finished(console) { machine, outcome -> if (outcome == RestoreOutcome.COLD_BOOT) machine.coldBootAfterRestore() }
            val m2 = restore(h, store, identity, console, finished)
            assertEquals(listOf(RestoreOutcome.COLD_BOOT to ColdBootCause.UNSUPPORTED_PROCESS), finished.outcomes.toList())
            repeat(5) { h.scheduler.tick() }
            assertTrue(killedLines(console.text).isEmpty() && !console.text.contains(Machine.INIT_KILLED_NOTICE), console.text)
            m2.close()
        }
    }
}
