package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.HostDiskLeases
import pro.ikili.kibicomputers.core.kernel.vfs.HostFileBudget
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.MachineSnapshotSource
import pro.ikili.kibicomputers.core.persist.QuiesceHandle
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.snapshot.DecodedKernelState
import pro.ikili.kibicomputers.core.persist.snapshot.DecodedP1Process
import pro.ikili.kibicomputers.core.persist.snapshot.KernelLimits
import pro.ikili.kibicomputers.core.persist.snapshot.KernelSectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 復元（#85、ADR 0085）の主な分岐（#578、#590）: 差分の世代から戻す、RUNNABLE のプロセス（燃料待ち・イベントで起きたが燃料がない・
 * 戻した直後の起こし）、待ちが空で `wakeOnRestore` が立つ形、`/tmp` と開いたファイル（消えていたら STALE）、表に入れた後の後段の失敗、
 * 冷えた起動の理由ごとの1つずつ、起こしを使う前の保存の `wakeOnRestore`。
 *
 * 保存した状態の形に頼るテスト（差分・RUNNABLE・起こし・空の待ち・`/tmp` と開いたファイル・KILLED_SECTION・UNSUPPORTED_PROCESS）は、
 * 戻す前に書いた世代そのもの（状態・待ち・差分か）を確かめてから戻す（「戻って動いた」だけでは、思った形で保存していなくても通るため）。
 * [savePrompt] を使うもの（MODULE_MISMATCH の 2 件・MOUNT）は、落ちる原因がモジュール・ディスクの側にあるので、世代の中身は確かめない
 */
class MachineRestoreBranchesTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    /** 置き場の根（ディスクのディレクトリとは分ける。置き場の根に UUID でないディレクトリを置かない） */
    private fun newStore(): MachineSaveStore = MachineSaveStore(Files.createDirectories(dir.resolve("saves")), MachineIdIndex(), "test")

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

    /** 終わりの知らせの記録（受け手の中の確かめは [errors] に取る。受け手の例外はマシンが捨てる） */
    private class Finished {
        val outcomes: MutableList<Pair<RestoreOutcome, ColdBootCause?>> = Collections.synchronizedList(ArrayList())

        @Volatile
        var onFinished: ((Machine, RestoreOutcome) -> Unit)? = null

        val errors: MutableList<Throwable> = Collections.synchronizedList(ArrayList())

        val listener = RestoreListener { m, outcome, cause ->
            try {
                onFinished?.invoke(m, outcome)
            } catch (t: Throwable) {
                errors += t
            }
            outcomes += outcome to cause
        }

        fun assertNoErrors() {
            errors.firstOrNull()?.let { throw AssertionError("受け手の中の確かめが落ちた", it) }
        }
    }

    /** 動かしたプロセスと、標準入出力のオブジェクトの ID（[output] は ByteStream のときだけ。0 ならない） */
    private class Started(val m: Machine, val pid: Int, val input: Int, val output: Int)

    /**
     * [m] の根に [root] を `disk:root` でマウントし、[name] のモジュールを [path] に置いて、init として動かす。[stdout] を渡せばそれを
     * 標準出力にする（渡さなければ新しい ByteStream）。標準入力は新しい ByteStream（[withInput] が偽なら閉じる）
     */
    private fun start(
        m: Machine,
        root: FileSystem,
        name: String,
        path: String,
        args: List<String>,
        stdout: ((Machine) -> Int)? = null,
        withInput: Boolean = true,
        beforeExec: (Machine) -> Unit = {},
    ): Started {
        m.vfs.mount(VfsPath.ROOT, root, readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = resource(name)
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        beforeExec(m)
        val stdoutId = stdout?.invoke(m)
        val (input, output) = m.lock.withLockValue {
            val i = if (withInput) ByteStream(4096).also { m.kernel.objects.add(it) }.id else 0
            val o = if (stdoutId == null) ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id else 0
            i to o
        }
        val stdio = StdioSpec(
            if (input != 0) StdioSource.Object(input, HandleRights.READ) else StdioSource.Closed,
            StdioSource.Object(stdoutId ?: output, HandleRights.WRITE),
            StdioSource.Closed,
        )
        val pid = m.exec(path, KernelDrivers.DEFAULT, SpawnOptions(args = args, stdio = stdio))
        // exec だけでは init にならない（initPid が 0 だと、戻すときに NO_LIVE_INIT になる）
        m.lock.withLockValue { m.kernel.initPid = pid }
        return Started(m, pid, input, output)
    }

    /** `prompt` を init として動かし、最初の入力を待つところまで進める */
    private fun startPrompt(h: Harness, m: Machine, root: FileSystem, stdout: ((Machine) -> Int)? = null, beforeExec: (Machine) -> Unit = {}): Started {
        val s = start(m, root, "prompt-wasi.wasm", "/bin/prompt", listOf("prompt"), stdout, beforeExec = beforeExec)
        awaitWaiting(h, s, "入力を待たない")
        return s
    }

    private fun awaitWaiting(h: Harness, s: Started, message: String) = eventually(60_000, message) {
        h.scheduler.tick()
        s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT && s.m.isAtWaitPoint
    }

    private fun output(m: Machine, id: Int): String = m.lock.withLockValue {
        (m.kernel.objects[id] as ByteStream).availableBytes().toString(Charsets.UTF_8)
    }

    private fun send(m: Machine, id: Int, text: String) = m.lock.withLockValue {
        val b = text.toByteArray()
        (m.kernel.objects[id] as ByteStream).write(b, 0, b.size, m.wakeSink)
        Unit
    }

    /** 待機点の写しを取る（step の途中の一瞬は `null` を返しうるので、取れるまで繰り返す） */
    private fun capture(m: Machine, deltaBase: pro.ikili.kibicomputers.core.persist.DeltaBase? = null): MachineSnapshotSource {
        var source: MachineSnapshotSource? = null
        eventually(10_000, "写しが取れない") {
            source = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT, deltaBase, 1.0)
            source != null
        }
        return source!!
    }

    /** [captureCopy][Machine.captureCopy] の写し（取れるまで繰り返す） */
    private fun captureCopy(m: Machine) = run {
        var copy: pro.ikili.kibicomputers.core.persist.KernelStateCopy? = null
        eventually(10_000, "写しが取れない") {
            copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)
            copy != null
        }
        copy!!
    }

    private fun stored(store: MachineSaveStore, identity: MachineIdentity): MachineSnapshot =
        store.readGeneration(identity.id, identity.generation).snapshot

    /** 書いた世代の `KERNEL` 節 */
    private fun kernelOf(snapshot: MachineSnapshot): DecodedKernelState =
        KernelSectionCodec.read(snapshot.sections.single { it.kind == SnapshotSection.KERNEL }.payload, KernelLimits(64))

    /** 書いた世代の [pid] の p1 の層 */
    private fun p1Of(snapshot: MachineSnapshot, pid: Int): DecodedP1Process {
        for (section in snapshot.sections) {
            if (section.kind != SnapshotSection.PROCESS_ABI) continue
            val r = SnapshotReader(section.payload)
            if (r.i32() != pid) continue
            assertEquals(SnapshotSection.ABI_LAYER_P1, r.u16())
            r.u16()
            return P1SectionCodec.read(r.raw(r.remaining))
        }
        fail("pid $pid の PROCESS_ABI 節がない")
    }

    /** [identity] の世代から新しいマシンに戻し、知らせが来るまで tick を回す */
    private fun restore(h: Harness, store: MachineSaveStore, identity: MachineIdentity, finished: Finished = Finished(), fuelPerTick: Long = 10_000_000): Machine {
        val m = h.machine(fuelPerTick = fuelPerTick)
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), finished.listener)))
        eventually(60_000, "知らせが来ない") {
            h.scheduler.tick()
            finished.outcomes.isNotEmpty()
        }
        finished.assertNoErrors()
        return m
    }

    /** 冷えた起動になり、表が空のまま */
    private fun assertColdBoot(m: Machine, finished: Finished, cause: ColdBootCause) {
        assertEquals(listOf(RestoreOutcome.COLD_BOOT to cause), finished.outcomes.toList())
        assertEquals(0, m.lock.withLockValue { m.kernel.processes.size }, "表は空のまま")
        assertTrue(m.lock.withLockValue { m.kernel.mounts.entries.isEmpty() }, "マウントは空のまま")
    }

    /** 戻すマシンが開く根のディスクを、新しい空のメモリのファイルシステムにする */
    private fun openFreshRoot(h: Harness) {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
    }

    private fun expectedSpin(rounds: Int, inner: Long): String {
        var x = 1L
        val sb = StringBuilder()
        repeat(rounds) { r ->
            repeat(inner.toInt()) { x = (x * 1103515245L + 12345L + (x ushr 7)) and 0xFFFF_FFFFL }
            sb.append("$r $x\n")
        }
        return sb.toString()
    }

    // ---- 差分の世代 ----

    @Test
    fun `差分の世代から戻すと、基準と合わせた差分の時点から続き、基準が消えていればUNREADABLE`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            openFreshRoot(h)
            val m = h.machine(fuelPerTick = 10_000_000)
            val s = startPrompt(h, m, TestMemoryFileSystem())
            // 世代 1: 全体（置き場が完了を受け取ったときに、次の差分の基準になる）
            val full = store.write(store.assignNewId(owner)!!, owner, capture(m))
            assertFalse(full.delta)
            // 1 行進めて、次のプロンプトで待つ
            send(m, s.input, "hello\n")
            eventually(60_000, "次のプロンプトで待たない") {
                h.scheduler.tick()
                output(m, s.output).split("> ").size - 1 >= 2 && m.stateOf(s.pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
            }
            assertTrue(output(m, s.output).contains("1 hello "))
            // 世代 2: 差分（比を 1.0 にして、全体に化けないようにする）
            val base = store.deltaBaseFor(full.identity, owner)
            assertNotNull(base, "全体を書いた後なのに基準がない")

            assertEquals(full.identity.generation, base!!.generation)
            val source = capture(m, base)
            assertEquals(base, source.deltaBase)
            val delta = store.write(full.identity, owner, source)
            assertTrue(delta.delta, "差分を書いていない: $delta")
            val written = stored(store, delta.identity)
            assertEquals(base, written.deltaBase)
            assertTrue(p1Of(written, s.pid).instance!!.changedOnly, "p1 の層が変わった区画だけになっていない")
            m.close()

            // 差分の世代から戻す: 行番号が 2 から続く（差分の時点の状態）
            val finished2 = Finished()
            val m2 = restore(h, store, delta.identity, finished2)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished2.outcomes.toList())
            assertEquals(MachineState.RUNNING, m2.state)
            assertEquals(ProcessState.WAITING_EVENT, m2.stateOf(s.pid))
            send(m2, s.input, "world\n")
            eventually(60_000, "戻したプロセスが答えない: ${output(m2, s.output)}") {
                h.scheduler.tick()
                output(m2, s.output).contains("2 world ")
            }
            m2.close()

            // 基準の世代のファイルが消えていたら、差分の世代は読めない（ADR 0084 の規則 9）
            Files.delete(store.generationFile(full.identity.id, full.identity.generation))
            val finished = Finished()
            val m3 = restore(h, store, delta.identity, finished)
            assertColdBoot(m3, finished, ColdBootCause.UNREADABLE)
            m3.close()
        }
    }

    // ---- RUNNABLE のプロセス ----

    @Test
    fun `燃料待ちのRUNNABLEのプロセスを戻すと、退避した値から続けて最後まで動く`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            openFreshRoot(h)
            val m = h.machine(fuelPerTick = 20_000)
            val s = start(m, TestMemoryFileSystem(), "fuel-spin-wasi.wasm", "/bin/spin", listOf("spin", "main", "3", "100000"), withInput = false)
            // 1 ラウンド目の出力が出るまで回す（何も出さないうちに保存すると、頭から動かし直しても通ってしまう。#590）
            eventually(60_000, "1 ラウンド目を出さない") {
                h.scheduler.tick()
                output(m, s.output).isNotEmpty()
            }
            // 燃料切れで巻き戻し、スレッドを持たずに燃料を待つ（tick は回さない: 回すと続きを動かしてしまう）
            eventually(30_000, "燃料切れで巻き戻さない") {
                m.lock.withLockValue {
                    val p = m.kernel.processes[s.pid]!!
                    val rt = m.rt(s.pid)
                    p.state == ProcessState.RUNNABLE && rt.thread == null && !rt.stackActive && rt.waitSet == null && p.wait == null &&
                        p.asyncStack?.isEmpty == false
                }
            }
            val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
            val written = stored(store, identity)
            val process = kernelOf(written).processes[s.pid]!!
            assertEquals(ProcessState.RUNNABLE, process.state)
            assertNull(process.wait, "燃料待ちは待ちがない")
            assertFalse(s.pid in kernelOf(written).wakeOnRestore)
            assertTrue(p1Of(written, s.pid).asyncStack.isNotEmpty(), "退避した値がない")
            // 書いた世代の出力は、途中までの分（空でなく、全文の真の先頭部分）。wasi-libc の stdout は tty でなければ 2 行目からためることが
            // あるので、何行出たかは決め打ちしない
            val all = expectedSpin(3, 100_000)
            val savedOutput = (kernelOf(written).objects[s.output] as ByteStream).availableBytes().toString(Charsets.UTF_8)
            assertTrue(savedOutput.isNotEmpty(), "書いた世代の出力が空")
            assertTrue(savedOutput.length < all.length && all.startsWith(savedOutput), "書いた世代の出力が途中までの分でない: $savedOutput")
            m.close()

            val finished = Finished()
            finished.onFinished = { machine, _ ->
                machine.lock.withLockValue {
                    assertEquals(ProcessState.RUNNABLE, machine.kernel.processes[s.pid]!!.state)
                    assertFalse(machine.runtime(s.pid)!!.restoreWake, "燃料待ちは起こしを立てない")
                }
            }
            val m2 = restore(h, store, identity, finished, fuelPerTick = 20_000)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            // 燃料は借りのことがあるので、tick を回し続ける
            eventually(60_000, "最後まで動かない") {
                h.scheduler.tick()
                m2.stateOf(s.pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m2.stateOf(s.pid), "${m2.rt(s.pid).lastError}")
            // 出力のストリームも写しに入っているので、保存の前の分と合わせて全文になる（頭から動かし直すと、1 行目が 2 回出る）
            assertEquals(all, output(m2, s.output))
            m2.close()
        }
    }

    @Test
    fun `イベントで起きたが燃料がないRUNNABLEは、起こしを立てずに待ちを登録し直し、そろった入力で続く`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            openFreshRoot(h)
            val m = h.machine(fuelPerTick = 10_000_000)
            val s = startPrompt(h, m, TestMemoryFileSystem())
            // 止めてから入力を入れる: 起こされても燃料（step）をもらえず、RUNNABLE のまま待機点にいる
            val hold = m.quiesce()
            send(m, s.input, "hello\n")
            eventually(10_000, "RUNNABLE にならない") {
                h.scheduler.tick()
                m.stateOf(s.pid) == ProcessState.RUNNABLE && m.isAtWaitPoint
            }
            val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
            val kernel = kernelOf(stored(store, identity))
            val process = kernel.processes[s.pid]!!
            assertEquals(ProcessState.RUNNABLE, process.state)
            assertTrue(process.wait?.isNotEmpty() == true, "待ちが残っていない: ${process.wait}")
            assertFalse(s.pid in kernel.wakeOnRestore, "起こしを使っていない印は立たない")
            hold.close()
            m.close()

            val finished = Finished()
            finished.onFinished = { machine, _ ->
                machine.lock.withLockValue {
                    val rt = machine.runtime(s.pid)!!
                    assertEquals(ProcessState.RUNNABLE, rt.process.state)
                    assertFalse(rt.restoreWake, "空でない待ちで wakeOnRestore がなければ起こしを立てない")
                    assertNotNull(rt.waitSet, "待ちを登録し直していない")
                }
            }
            val m2 = restore(h, store, identity, finished)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            // 追加の入力なしで、保存の前に入れた行に答える
            eventually(60_000, "戻したプロセスが答えない: ${output(m2, s.output)}") {
                h.scheduler.tick()
                output(m2, s.output).contains("1 hello ")
            }
            awaitWaiting(h, Started(m2, s.pid, s.input, s.output), "次の入力を待たない")
            m2.close()
        }
    }

    @Test
    fun `起こしを使う前に保存し直すとwakeOnRestoreが立ち、戻したRUNNABLEも起こしてから入力を待ち直す`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            openFreshRoot(h)
            val m = h.machine(fuelPerTick = 10_000_000)
            val s = startPrompt(h, m, TestMemoryFileSystem())
            // 世代 1: WAITING_EVENT に wakeOnRestore を立てて書く（本物の取り出しと同じ markWakeOnRestore を使う）
            val copy = captureCopy(m)
            copy.markWakeOnRestore(s.pid)
            val first = store.write(store.assignNewId(owner)!!, owner, copy.toSource()).identity
            val k1 = kernelOf(stored(store, first))
            assertEquals(ProcessState.WAITING_EVENT, k1.processes[s.pid]!!.state)
            assertTrue(s.pid in k1.wakeOnRestore)
            m.close()

            // 2 台目: 戻した直後に自分でも止め、起こしを使わせないまま保存し直す
            var hold: QuiesceHandle? = null
            val finished2 = Finished()
            finished2.onFinished = { machine, _ ->
                hold = machine.quiesce()
                machine.lock.withLockValue { assertTrue(machine.runtime(s.pid)!!.restoreWake, "wakeOnRestore で起こしを立てていない") }
            }
            val m2 = restore(h, store, first, finished2)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished2.outcomes.toList())
            assertTrue(m2.isQuiesced, "自分の quiesce で止まったまま")
            val copy2 = captureCopy(m2)
            assertTrue(copy2.wakeOnRestore(s.pid), "起こしを使う前の写しに wakeOnRestore が立たない")
            m2.lock.withLockValue { assertTrue(m2.runtime(s.pid)!!.restoreWake) }
            val second = store.write(first, owner, copy2.toSource()).identity
            val k2 = kernelOf(stored(store, second))
            // 起こしの予定で RUNNABLE にした（scheduleThreadlessLocked）が、燃料をもらえずに止まっている
            assertEquals(ProcessState.RUNNABLE, k2.processes[s.pid]!!.state)
            assertTrue(k2.processes[s.pid]!!.wait?.isNotEmpty() == true)
            assertTrue(s.pid in k2.wakeOnRestore, "KERNEL 節を往復しても wakeOnRestore が残る")

            // 3 台目: RUNNABLE + wakeOnRestore から戻す
            val finished3 = Finished()
            finished3.onFinished = { machine, _ ->
                machine.lock.withLockValue {
                    val rt = machine.runtime(s.pid)!!
                    assertEquals(ProcessState.RUNNABLE, rt.process.state)
                    assertTrue(rt.restoreWake, "RUNNABLE の wakeOnRestore で起こしを立てていない")
                }
            }
            val m3 = restore(h, store, second, finished3)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished3.outcomes.toList())

            // 2 台目: 止めるのをやめると、起こしを使ってそろいを見ずに続け、入力がないので待ち直す
            hold!!.close()
            for ((machine, line) in listOf(m2 to "x", m3 to "y")) {
                eventually(60_000, "起こしを使って待ち直さない") {
                    h.scheduler.tick()
                    machine.lock.withLockValue { !machine.runtime(s.pid)!!.restoreWake } &&
                        machine.stateOf(s.pid) == ProcessState.WAITING_EVENT && machine.isAtWaitPoint
                }
                assertFalse(captureCopy(machine).wakeOnRestore(s.pid), "起こしを使った後の写しに wakeOnRestore が残る")
                assertFalse(output(machine, s.output).contains("1 "), "入力なしで答えた")
                send(machine, s.input, "$line\n")
                eventually(60_000, "起こしの後の入力に答えない: ${output(machine, s.output)}") {
                    h.scheduler.tick()
                    output(machine, s.output).contains("1 $line ")
                }
            }
            m2.close()
            m3.close()
        }
    }

    /**
     * 写しの [pid] の待ちを空の並びにし、`wakeOnRestore` を立てて書く（#590）。ホスト側の処理の待ちだけを待っていたプロセスで、
     * `dropHostWaitsInCopy` が key を外した結果と同じ形を手で作る（状態は変えない。`null` ではなく空の並び）。
     * 書いた世代が思った形（[state]・空の待ち・`wakeOnRestore`）かを確かめてから返す
     */
    private fun writeEmptyWait(store: MachineSaveStore, m: Machine, pid: Int, state: ProcessState): MachineIdentity {
        val copy = captureCopy(m)
        assertEquals(state, copy.processes[pid]!!.state)
        copy.processes[pid]!!.wait = emptyList()
        copy.markWakeOnRestore(pid)
        val identity = store.write(store.assignNewId(owner)!!, owner, copy.toSource()).identity
        val kernel = kernelOf(stored(store, identity))
        val process = kernel.processes[pid]!!
        assertEquals(state, process.state)
        assertNotNull(process.wait, "空の待ちが NO_WAIT（null）になった")
        assertTrue(process.wait!!.isEmpty(), "待ちが空でない: ${process.wait}")
        assertTrue(pid in kernel.wakeOnRestore)
        return identity
    }

    /** 戻した直後（知らせの中）: 空の待ちは登録せず（waitSet がない）、起こしを立てて RUNNABLE にしている */
    private fun assertEmptyWaitRestored(machine: Machine, pid: Int) = machine.lock.withLockValue {
        val rt = machine.runtime(pid)!!
        // WAITING_EVENT でも、起こしの予定（scheduleThreadlessLocked）で RUNNABLE になっている
        assertEquals(ProcessState.RUNNABLE, rt.process.state)
        assertTrue(rt.restoreWake, "空の待ちで起こしを立てていない")
        assertNull(rt.waitSet, "空の待ちを登録した")
        assertTrue(rt.process.wait?.isEmpty() == true, "待ちが空の並びでない: ${rt.process.wait}")
    }

    @Test
    fun `待ちが空でwakeOnRestoreが立ったWAITING_EVENTを戻すと、登録せずに起こし、入力がないので待ち直す`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            openFreshRoot(h)
            val m = h.machine(fuelPerTick = 10_000_000)
            val s = startPrompt(h, m, TestMemoryFileSystem())
            val identity = writeEmptyWait(store, m, s.pid, ProcessState.WAITING_EVENT)
            m.close()

            val finished = Finished()
            finished.onFinished = { machine, _ -> assertEmptyWaitRestored(machine, s.pid) }
            val m2 = restore(h, store, identity, finished)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            // 起こし（waitSet のないまま resume）を使い、入力がないので空でない待ちで待ち直す
            eventually(60_000, "起こしを使って待ち直さない") {
                h.scheduler.tick()
                m2.lock.withLockValue { !m2.runtime(s.pid)!!.restoreWake } && m2.stateOf(s.pid) == ProcessState.WAITING_EVENT && m2.isAtWaitPoint
            }
            assertFalse(output(m2, s.output).contains("1 "), "入力なしで答えた")
            val copy = captureCopy(m2)
            assertFalse(copy.wakeOnRestore(s.pid), "起こしを使った後の写しに wakeOnRestore が残る")
            assertTrue(copy.processes[s.pid]!!.wait?.isNotEmpty() == true, "待ち直した後の待ちが空")
            send(m2, s.input, "x\n")
            eventually(60_000, "起こしの後の入力に答えない: ${output(m2, s.output)}") {
                h.scheduler.tick()
                output(m2, s.output).contains("1 x ")
            }
            m2.close()
        }
    }

    @Test
    fun `待ちが空でwakeOnRestoreが立ったRUNNABLEを戻すと、登録せずに燃料をもらってから起こし、入っていた入力に答える`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            openFreshRoot(h)
            val m = h.machine(fuelPerTick = 10_000_000)
            val s = startPrompt(h, m, TestMemoryFileSystem())
            // (b1) と同じく、止めてから入力を入れて RUNNABLE（待機点）にする
            val hold = m.quiesce()
            send(m, s.input, "hello\n")
            eventually(10_000, "RUNNABLE にならない") {
                h.scheduler.tick()
                m.stateOf(s.pid) == ProcessState.RUNNABLE && m.isAtWaitPoint
            }
            val identity = writeEmptyWait(store, m, s.pid, ProcessState.RUNNABLE)
            hold.close()
            m.close()

            val finished = Finished()
            finished.onFinished = { machine, _ -> assertEmptyWaitRestored(machine, s.pid) }
            val m2 = restore(h, store, identity, finished)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            // 追加の入力なしで、保存の前に入れた行に答える（起こしで、そろいを見ずに resume する）
            eventually(60_000, "戻したプロセスが答えない: ${output(m2, s.output)}") {
                h.scheduler.tick()
                output(m2, s.output).contains("1 hello ")
            }
            awaitWaiting(h, Started(m2, s.pid, s.input, s.output), "次の入力を待たない")
            assertFalse(m2.lock.withLockValue { m2.runtime(s.pid)!!.restoreWake })
            m2.close()
        }
    }

    // ---- /tmp と開いたファイル ----

    /** `/tmp/x` に書いた模様と、標準出力にしたディスクのファイル `/out.txt` のオブジェクト（[root] は保存したマシンの根のディスク） */
    private class SavedFiles(
        val identity: MachineIdentity,
        val started: Started,
        val outId: Int,
        val pattern: ByteArray,
        val usedBytes: Long,
        val root: DiskFileSystem,
    )

    /**
     * 根を本物の [DiskFileSystem]（[disk]）にし、`/tmp` に模様を書き、標準出力をディスクのファイル `/out.txt` にして `prompt` を動かし、
     * 入力を待ったところで書いて閉じる。戻すマシンは、同じディレクトリを新しく開く（リースは開くだけで移る）
     */
    private fun saveWithFiles(h: Harness, store: MachineSaveStore, disk: Path): SavedFiles {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(DiskFileSystem.open(disk), readOnly = false) else null }
        val m = h.machine(fuelPerTick = 10_000_000)
        val pattern = ByteArray(10_000) { (it % 251 + 1).toByte() }
        var outId = 0
        val root = DiskFileSystem.open(disk)
        val s = startPrompt(
            h,
            m,
            root,
            stdout = { machine ->
                val out = machine.vfs.open(VfsPath.parse("/out.txt"), OpenOptions(read = false, write = true, create = true, truncate = true))
                machine.lock.withLockValue { machine.kernel.objects.add(out) }.also { outId = it }
            },
        ) { machine ->
            machine.vfs.createDirectory(VfsPath.parse("/tmp"))
            assertNull(machine.mountTmp())
            val t = machine.vfs.open(VfsPath.parse("/tmp/x"), OpenOptions(read = false, write = true, create = true))
            assertEquals(pattern.size, machine.vfs.write(t, pattern, 0, pattern.size))
        }
        // 本番と同じく、開いたファイルにはプロセスの標準出力からだけ届く形にする（テストが足した参照は返す）
        m.lock.withLockValue { m.kernel.objects.release(outId, m.wakeSink) }
        assertTrue(m.lock.withLockValue { m.kernel.objects[outId] is OpenFile }, "標準出力の開いたファイルが表にない")
        eventually(10_000, "プロンプトをファイルに書かない") { String(Files.readAllBytes(disk.resolve("out.txt"))) == "> " }
        val used = m.memoryAccount.usedBytes
        val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
        val written = stored(store, identity)
        assertEquals(1, written.sections.count { it.kind == SnapshotSection.TMPFS }, "TMPFS 節がない")
        assertEquals(VfsPath.parse("/out.txt"), kernelOf(written).filePaths[outId], "開いたファイルのパスを書いていない")
        m.close()
        return SavedFiles(identity, s, outId, pattern, used, root)
    }

    @Test
    fun `tmpと開いたファイルを持つ状態を戻すと、tmpの中身とRAMの勘定が戻り、ファイルの続きに書く`() = timeout(limit) {
        val store = newStore()
        val disk = dir.resolve("disk")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWithFiles(h, store, disk)
            val finished = Finished()
            val m = restore(h, store, saved.identity, finished)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            // /tmp の中身と、その RAM の勘定（インスタンスの分と合わせて、保存した時と同じ）
            val t = m.vfs.open(VfsPath.parse("/tmp/x"), OpenOptions(read = true))
            val buf = ByteArray(saved.pattern.size + 1)
            assertEquals(saved.pattern.size, m.vfs.read(t, buf, 0, buf.size))
            assertArrayEquals(saved.pattern, buf.copyOf(saved.pattern.size))
            assertEquals(saved.usedBytes, m.memoryAccount.usedBytes, "RAM の勘定が保存した時と違う")
            // 開いたファイル（adoptNode で登録した node）の、保存した位置の続きに答えを書く
            send(m, saved.started.input, "hello\n")
            val file = disk.resolve("out.txt")
            eventually(60_000, "ファイルに答えを書かない") {
                h.scheduler.tick()
                String(Files.readAllBytes(file)).split("> ").size - 1 >= 2
            }
            val text = String(Files.readAllBytes(file))
            assertTrue(text.startsWith("> 1 hello "), "保存した位置から続けていない: $text")
            assertEquals(MachineState.RUNNING, m.state)
            m.close()
        }
    }

    @Test
    fun `開いたファイルのパスが消えていても戻せ、その操作はSTALEになる`() = timeout(limit) {
        val store = newStore()
        val disk = dir.resolve("disk")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWithFiles(h, store, disk)
            // ホストのファイルを閉じるのは起動用スレッドの後始末（非同期）なので、閉じ終わるのを待ってから消す
            eventually(10_000, "保存したマシンのディスクがファイルを閉じない") { saved.root.openHandles == 0 }
            Files.delete(disk.resolve("out.txt"))
            val finished = Finished()
            val m = restore(h, store, saved.identity, finished)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList(), "パスが消えていても冷えた起動にしない")
            val file = m.lock.withLockValue { m.kernel.objects[saved.outId] as OpenFile }
            val e = assertThrows<FsException> { m.vfs.write(file, "x".toByteArray(), 0, 1) }
            assertEquals(Errno.STALE, e.errno)
            assertFalse(Files.exists(disk.resolve("out.txt")), "消えたファイルを作り直した")
            assertEquals(MachineState.RUNNING, m.state)
            m.close()
        }
    }

    @Test
    fun `表に入れた後に後段が失敗しても、閉じたものに重ねて閉じるだけで、RAMとディスクのリースは1回だけ返る`() = timeout(limit) {
        // #590: 1 つ目のループ（runtimes に入れてインスタンスを渡す）を通った後で落とす。stopLocked が閉じた後に discard が重ねて閉じる
        val store = newStore()
        val disk = dir.resolve("disk")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            // 戻すマシンの根も、本物の DiskFileSystem（saveWithFiles が bootVolumes を差し替える。包むと adoptNode が効かない）
            val saved = saveWithFiles(h, store, disk)
            val pid = saved.started.pid
            val real = disk.toRealPath()
            // 保存したマシンの後始末（起動用スレッドでリースを返す）を待つ
            eventually(10_000, "保存したマシンのリースが返らない") {
                h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty() && HostDiskLeases.usersOf(real) == 0
            }
            // テストが別に 1 人分持つ（持ち主が 1 人だけだと、二重に返しても computeIfPresent が何もしないので usersOf に出ない）
            val holder = DiskFileSystem.open(disk)
            assertEquals(1, HostDiskLeases.usersOf(real))
            val base = h.scheduler.memoryAccount.usedBytes
            var during = -1L
            // 終わっていない状態のうち、後段が戻せないもの（WAITING_HOST）に書き換える。終わった状態にすると stopLocked が飛ばし、
            // 閉じたものに重ねる経路を通らない。表の数は state から数えるので、書き換えても表は壊れない
            h.scheduler.hooks.duringRestoreInstall = { m ->
                during = m.memoryAccount.usedBytes
                m.kernel.processes[pid]!!.state = ProcessState.WAITING_HOST
            }
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), finished.listener)))
            eventually(60_000, "CRASHED にならない") {
                h.scheduler.tick()
                m.state == MachineState.CRASHED
            }
            h.scheduler.hooks.duringRestoreInstall = null
            // state は stopLocked の途中（setStateLocked(CRASHED) の直後）で見えるので、ここで見ると復元の quiesce を閉じる前かもしれない。
            // stopLocked・discard・vfs の後始末はどれも起動用スレッドで走るので、そこが空になるのを待ってから見る
            val drained = { h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty() }
            eventually(10_000, "起動用スレッドの後始末が終わらない") { drained() }
            // 後始末が全部終わった後に tick して、知らせが積まれていないことを見る（積む退行があっても、先の tick に間に合わないと見えないため）
            repeat(3) { h.scheduler.tick() }
            eventually(10_000, "tick の後で起動用スレッドが空にならない") { drained() }
            repeat(3) { h.scheduler.tick() }
            assertTrue(finished.outcomes.isEmpty(), "後段の失敗で知らせを送った: ${finished.outcomes}")
            assertEquals(1, h.events.count { it.first == m.id && it.second == MachineState.CRASHED })
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced)
            // 1 つ目のループで runtimes に入れてインスタンスを渡し、stopLocked が止めて閉じた
            val rt = m.runtime(pid) ?: fail("1 つ目のループで runtimes に入れていない")
            assertEquals(ProcessState.KILLED, m.lock.withLockValue { rt.process.state })
            assertTrue(m.lock.withLockValue { rt.instances.isEmpty() }, "stopLocked がインスタンスを閉じていない")
            assertTrue(during >= 10_000, "戻す途中で RAM を数えていない（$during）")
            // インスタンスの RAM は 1 回だけ返る。インスタンスはプロセスの勘定（rt.memory）を通して返すので、二重に返すとプロセスの勘定が
            // 負になった時点で check が投げ、マシンとサーバー全体の勘定には届かない（例外は closeQuietly が捨てる）。そのためプロセスの勘定で見る。
            // ProcessMemory は数を引いてから check するので、二重に返した分は値に負として残る
            val processBytes = m.lock.withLockValue { rt.memory.usedBytes }
            val processPages = m.lock.withLockValue { rt.memory.usedPages }
            assertEquals(0L, processBytes, "プロセスの RAM の勘定が 0 にならない（負なら WasmInstance.close が二重に返した）")
            assertEquals(0, processPages, "プロセスの線形メモリのページ数が 0 にならない（負なら WasmInstance.close が二重に返した）")
            // /tmp の RAM はマシンの勘定から直に返すので、二重に返すとマシンの勘定が負になる（ここで見える）。
            // サーバー全体の勘定は「返していない」だけを見る（二重に返すと、親に届く前にマシンの勘定の check が投げる）
            assertEquals(0L, m.memoryAccount.usedBytes, "マシンの RAM の勘定が 0 にならない（負なら /tmp を二重に返した）")
            assertEquals(base, h.scheduler.memoryAccount.usedBytes, "サーバー全体の RAM の勘定が元に戻らない")
            // ディスクのリースも 1 回だけ返る（テストの holder の分だけが残る）
            assertEquals(1, HostDiskLeases.usersOf(real), "ディスクのリースを二重に返した、または返していない")
            holder.retire()
            assertEquals(0, HostDiskLeases.usersOf(real))
            m.close()
        }
    }

    @Test
    fun `取り消された古い前段が確かめた後にディスクを開いても、新しく戻したマシンのディスクを締め出さない`() = timeout(limit) {
        // #588: A の前段は openMounts で取り消しを確かめた直後（開く前）に止まる。その間に A を閉じ、B が同じ世代を戻してから A を放す
        val store = newStore()
        val disk = dir.resolve("disk")
        // 取り消された前段を冷えた起動に流すと、復元の WARNING（「戻せないので冷えた起動にする」）が残る。restoreFellBack は取り消しなら何もしないので、これだけが差になる
        val warnings = Collections.synchronizedList(ArrayList<String>())
        val restoreLog = java.util.logging.Logger.getLogger("kibicomputers.restore")
        val logHandler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                if (record.level == java.util.logging.Level.WARNING) warnings.add(record.message)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        restoreLog.addHandler(logHandler)
        try {
            Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
                val saved = saveWithFiles(h, store, disk)
                val real = BootVolumeOpener.forDisks({ id -> if (id == "root") ResolvedDisk(disk, 1L shl 30, 4096) else null }, HostFileBudget())
                val aEntered = CountDownLatch(1)
                val aRelease = CountDownLatch(1)
                val aDone = CountDownLatch(1)
                val aOpened = AtomicReference<BootVolume?>()
                val calls = AtomicInteger()
                h.scheduler.hooks.bootVolumes = object : BootVolumeOpener {
                    override fun open(diskId: String): BootVolume? = open(diskId) { false }

                    override fun open(diskId: String, cancelled: () -> Boolean): BootVolume? {
                        if (calls.getAndIncrement() != 0) return real.open(diskId, cancelled)
                        aEntered.countDown()
                        aRelease.await()
                        try {
                            return real.open(diskId, cancelled).also { aOpened.set(it) }
                        } finally {
                            aDone.countDown()
                        }
                    }
                }
                val aFinished = Finished()
                val a = h.machine(fuelPerTick = 10_000_000)
                assertTrue(a.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), aFinished.listener)))
                eventually(60_000, "A の前段がディスクを開きに来ない") {
                    h.scheduler.tick()
                    aEntered.count == 0L
                }
                a.close()
                assertTrue(a.isRestoreCancelled)
                val finished = Finished()
                val b = restore(h, store, saved.identity, finished)
                try {
                    assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
                    aRelease.countDown()
                    assertTrue(aDone.await(10, TimeUnit.SECONDS))
                    assertNull(aOpened.get(), "取り消された前段はディスクを開かない")
                    // aDone は opener の中で下りるので、A の前段の段が終わる（起動用のスレッドが空く）まで待ってから見る
                    eventually(10_000, "A の前段の段が終わらない") { h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty() }
                    h.scheduler.tick()
                    assertTrue(aFinished.outcomes.none { it.first == RestoreOutcome.COLD_BOOT }, "取り消された前段を冷えた起動に流した: ${aFinished.outcomes}")
                    assertTrue(warnings.none { it.contains("冷えた起動にする") }, "取り消された前段を冷えた起動の経路に流した: $warnings")
                    // B の開いたファイル（ディスクの /out.txt）に、今も書ける（直す前は、A が世代を進めて IO になった）
                    send(b, saved.started.input, "hello\n")
                    val file = disk.resolve("out.txt")
                    eventually(60_000, "ファイルに答えを書かない") {
                        h.scheduler.tick()
                        String(Files.readAllBytes(file)).split("> ").size - 1 >= 2
                    }
                    assertTrue(String(Files.readAllBytes(file)).startsWith("> 1 hello "))
                    assertEquals(MachineState.RUNNING, b.state)
                } finally {
                    aRelease.countDown()
                    b.close()
                }
            }
        } finally {
            restoreLog.removeHandler(logHandler)
        }
    }

    // ---- 冷えた起動の理由 ----

    @Test
    fun `initを写しの中で殺した世代はKILLED_SECTIONで冷えた起動`() = timeout(limit) {
        val store = newStore()
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val driver = gated(gate, kind = ProcessKind.WAIT_POINT)
            driver.abiPayload = null
            val init = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(init) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue { m.kernel.initPid = init }
            val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
            val written = stored(store, identity)
            assertTrue(written.requiresColdBoot)
            assertEquals(ColdBootReason.INIT_KILLED.code, written.coldBootCode)
            gate.open()
            m.close()

            val finished = Finished()
            val m2 = restore(h, store, identity, finished)
            assertColdBoot(m2, finished, ColdBootCause.KILLED_SECTION)
            m2.close()
        }
    }

    @Test
    fun `p1でないABI層のプロセスはUNSUPPORTED_PROCESSで冷えた起動`() = timeout(limit) {
        val store = newStore()
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val init = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(init) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue { m.kernel.initPid = init }
            val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
            val written = stored(store, identity)
            assertFalse(written.requiresColdBoot)
            val abi = SnapshotReader(written.sections.single { it.kind == SnapshotSection.PROCESS_ABI }.payload)
            assertEquals(init, abi.i32())
            assertEquals(SnapshotSection.ABI_LAYER_TEST, abi.u16())
            gate.open()
            m.close()

            val finished = Finished()
            val m2 = restore(h, store, identity, finished)
            assertColdBoot(m2, finished, ColdBootCause.UNSUPPORTED_PROCESS)
            m2.close()
        }
    }

    /** [swap] の元のハッシュに、別のバイト列を返す置き場（ハッシュの合わないモジュールを作る） */
    private class SwappingModuleStore(private val inner: MemoryModuleStore = MemoryModuleStore()) : ModuleStore by inner {
        @Volatile
        var swap: Pair<String, ByteArray>? = null

        override fun get(sha256: ByteArray): ByteArray? {
            val s = swap
            return if (s != null && ModuleStore.hex(sha256) == s.first) s.second.copyOf() else inner.get(sha256)
        }
    }

    /** `prompt` が入力を待ったところを書いて閉じる（根は `disk:root`。[extra] で根の後にマウントを足せる） */
    private fun savePrompt(h: Harness, store: MachineSaveStore, extra: (Machine) -> Unit = {}): MachineIdentity {
        val m = h.machine(fuelPerTick = 10_000_000)
        startPrompt(h, m, TestMemoryFileSystem(), beforeExec = extra)
        val identity = store.write(store.assignNewId(owner)!!, owner, capture(m)).identity
        m.close()
        return identity
    }

    @Test
    fun `読み込み直したモジュールのハッシュが合わなければMODULE_MISMATCH`() = timeout(limit) {
        val store = newStore()
        val modules = SwappingModuleStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = modules).use { h ->
            openFreshRoot(h)
            val identity = savePrompt(h, store)
            modules.swap = ModuleStore.hex(ModuleStore.sha256(resource("prompt-wasi.wasm"))) to resource("fuel-spin-wasi.wasm")
            val finished = Finished()
            val m = restore(h, store, identity, finished)
            assertColdBoot(m, finished, ColdBootCause.MODULE_MISMATCH)
            m.close()
        }
    }

    @Test
    fun `置き場のモジュールを読み込めなければMODULE_MISMATCH`() = timeout(limit) {
        val store = newStore()
        val modules = MemoryModuleStore()
        val identity = Harness(execRuntime = EndiveRuntime(), moduleStore = modules).use { h -> savePrompt(h, store) }
        // 戻す側のサーバーは、読み込みの上限が厳しい（置き場のバイト列は同じ）
        Harness(execRuntime = EndiveRuntime(), moduleStore = modules, moduleLimits = ModuleLimits(maxBytes = 1)).use { h ->
            openFreshRoot(h)
            val finished = Finished()
            val m = restore(h, store, identity, finished)
            assertColdBoot(m, finished, ColdBootCause.MODULE_MISMATCH)
            m.close()
        }
    }

    /** 閉じた（[FileSystem.retire]）回数を数えるディスク */
    private class CountingDisk(private val inner: TestMemoryFileSystem = TestMemoryFileSystem()) : FileSystem by inner {
        val retired = AtomicInteger()

        override fun retire() {
            retired.incrementAndGet()
            inner.retire()
        }
    }

    @Test
    fun `根のディスクを開けなければMOUNTで冷えた起動にし、予約を残さない`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val identity = savePrompt(h, store)
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { _ -> null }
            val finished = Finished()
            val m = restore(h, store, identity, finished)
            assertColdBoot(m, finished, ColdBootCause.MOUNT)
            assertEquals(0, m.diskClaimCount, "予約は残らない")
            m.close()
        }
    }

    @Test
    fun `根でないディスクを開けなければ、そのマウントだけ落として戻し、disk-removedを出す`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val identity = savePrompt(h, store) { m ->
                m.vfs.createDirectory(VfsPath.parse("/data"))
                m.vfs.mount(VfsPath.parse("/data"), TestMemoryFileSystem(), readOnly = false, source = "disk:data")
            }
            // 根は開けるが、2つ目のディスク data は開けない（抜かれた）
            val root = CountingDisk()
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(root, readOnly = false) else null }
            val events = Collections.synchronizedList(ArrayList<Pair<String, List<EventValue>>>())
            var queue: EventQueue? = null
            h.scheduler.hooks.duringRestoreInstall = { m ->
                m.kernel.events.ensureCapacity(m.kernel.objects)
                val q = EventQueue(EventFilter.ALL, 16)
                m.kernel.objects.add(q)
                m.kernel.events.subscribe(q, m.kernel.objects)
                queue = q
            }
            val finished = Finished()
            val m = restore(h, store, identity, finished)
            h.scheduler.hooks.duringRestoreInstall = null
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            m.lock.withLockValue { queue!!.pendingEvents().filter { it.type.startsWith("disk-") }.forEach { events += it.type to it.values } }
            assertEquals(listOf("disk-removed" to listOf<EventValue>(EventValue.Text("data"), EventValue.Text("/data"))), events.toList())
            assertEquals(null, m.lock.withLockValue { m.kernel.mounts.at(VfsPath.parse("/data")) })
            assertEquals("disk:root", m.lock.withLockValue { m.kernel.mounts.at(VfsPath.ROOT)?.source })
            assertEquals(0, m.diskClaimCount, "予約は残らない")
            assertEquals(0, root.retired.get(), "戻した根は閉じない")
            m.close()
        }
    }

    // ---- ディスクの挿し抜きと復元（#56、ADR 0056） ----

    /** 挿さっているディスクの表（ホストのパスは使わない。開き方はテストのフック） */
    private fun insertedDisks(vararg ids: String): MachineEnvironment =
        MachineEnvironment.builder().disks(DiskSnapshot.of(ids.associateWith { ResolvedDisk(Path.of("unused", it), 1L shl 20, 64) })).build()

    /** 戻す途中（表に入れる区間）に、全部を受けるキューを足す。戻したマシンの [queue] に溜まる */
    private class QueueProbe {
        @Volatile
        var queue: EventQueue? = null

        fun install(h: Harness) {
            h.scheduler.hooks.duringRestoreInstall = { m ->
                m.kernel.events.ensureCapacity(m.kernel.objects)
                val q = EventQueue(EventFilter.ALL, 32)
                m.kernel.objects.add(q)
                m.kernel.events.subscribe(q, m.kernel.objects)
                queue = q
            }
        }

        fun diskEvents(m: Machine): List<Triple<String, String, String>> = m.lock.withLockValue {
            queue!!.pendingEvents().filter { it.type.startsWith("disk-") }.map {
                Triple(it.type, (it.values[0] as EventValue.Text).value, (it.values[1] as EventValue.Text).value)
            }
        }
    }

    private fun mountSource(m: Machine, point: String): String? = m.lock.withLockValue { m.kernel.mounts.at(VfsPath.parse(point))?.source }

    @Test
    fun `復元の後に、保存した表にない挿さっているディスクを自動でマウントしてdisk-insertedを出す`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val identity = savePrompt(h, store)
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root" || id == "extra") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
            val probe = QueueProbe().also { it.install(h) }
            val m = h.machine(fuelPerTick = 10_000_000, environment = insertedDisks("root", "extra"))
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), finished.listener)))
            eventually(60_000, "知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            h.scheduler.hooks.duringRestoreInstall = null
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            // 戻した根は予約の持ち主がカーネルに移ったので、自動マウントの対象外。表にない extra だけがマウントされる
            eventually(10_000, "extra をマウントしない") {
                h.scheduler.tick()
                mountSource(m, "/mnt/extra") == "disk:extra"
            }
            eventually(10_000, "disk-inserted が来ない") { probe.diskEvents(m).isNotEmpty() }
            assertEquals(listOf(Triple("disk-inserted", "extra", "/mnt/extra")), probe.diskEvents(m))
            assertEquals("disk:root", mountSource(m, "/"))
            assertNull(mountSource(m, "/mnt/root"), "戻した根を /mnt に重ねてマウントしない")
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    @Test
    fun `前段がopenMountsの後に失敗して冷えた起動になったときは、落としたマウントのdisk-removedを出さない`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val identity = savePrompt(h, store) { m ->
                m.vfs.createDirectory(VfsPath.parse("/data"))
                m.vfs.mount(VfsPath.parse("/data"), TestMemoryFileSystem(), readOnly = false, source = "disk:data")
                // 知らないマウントは、data の後（表の順）で冷えた起動（MOUNT）にする
                m.vfs.createDirectory(VfsPath.parse("/weird"))
                m.vfs.mount(VfsPath.parse("/weird"), TestMemoryFileSystem(), readOnly = false, source = "weird")
            }
            // data は開けない（落とす）。その後の weird で前段が失敗する
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
            val probe = QueueProbe().also { it.install(h) }
            val finished = Finished()
            val m = restore(h, store, identity, finished)
            h.scheduler.hooks.duringRestoreInstall = null
            assertColdBoot(m, finished, ColdBootCause.MOUNT)
            assertNull(probe.queue, "後段に入っていないので、戻したキューはない（落としたマウントのイベントはどこにも届かない）")
            assertEquals(0, m.diskClaimCount, "予約は残らない")
            m.close()
        }
    }

    @Test
    fun `RESTOREDを配る受け手の中でrebootしても、戻す前のdisk-removedは次のinitへ漏れず、起動し直せる`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val identity = savePrompt(h, store) { m ->
                m.vfs.createDirectory(VfsPath.parse("/data"))
                m.vfs.mount(VfsPath.parse("/data"), TestMemoryFileSystem(), readOnly = false, source = "disk:data")
            }
            // 起動ディスク none は prompt を init に持つ。data は開けない（落として disk-removed を出す）
            val bootDisk = TestMemoryFileSystem().also { fs ->
                fs.createDirectory(VfsPath.parse("/sbin"))
                val bytes = resource("prompt-wasi.wasm")
                fs.write(fs.createFile(VfsPath.parse(BootConfig.INIT_PATH)), 0, bytes, 0, bytes.size)
            }
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id ->
                when (id) {
                    "root" -> BootVolume(TestMemoryFileSystem(), readOnly = false)
                    "none" -> BootVolume(bootDisk, readOnly = true)
                    else -> null
                }
            }
            val m = h.machine(fuelPerTick = 10_000_000, environment = insertedDisks("root", "none"))
            val finished = Finished()
            // 受け手の中で再起動を求める（RESTORED の配達の途中。戻した表は再起動が片付ける）
            finished.onFinished = { machine, outcome -> if (outcome == RestoreOutcome.RESTORED) assertTrue(machine.reboot()) }
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), finished.listener)))
            eventually(60_000, "知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            // 起動し直した init が入力を待つ
            eventually(60_000, "起動し直さない") {
                h.scheduler.tick()
                m.state == MachineState.RUNNING && !m.isBooting && !m.isRebootPending &&
                    m.lock.withLockValue { m.kernel.processes[m.kernel.initPid]?.state == ProcessState.WAITING_EVENT }
            }
            // 起動し直した後に作ったキューには、戻す前のディスクのイベントは届かない
            val q = m.lock.withLockValue {
                m.kernel.events.ensureCapacity(m.kernel.objects)
                EventQueue(EventFilter.ALL, 32).also {
                    m.kernel.objects.add(it)
                    m.kernel.events.subscribe(it, m.kernel.objects)
                }
            }
            repeat(10) {
                h.scheduler.tick()
                Thread.sleep(5)
            }
            assertEquals(emptyList<String>(), m.lock.withLockValue { q.pendingEvents().filter { it.type.startsWith("disk-") }.map { it.type } })
            assertEquals(null, mountSource(m, "/data"), "戻す前のマウントは残らない")
            assertEquals(0, m.diskClaimCount)
            m.close()
        }
    }

    /** 開くのを [gated] の識別子ごとに止められる開き手（根は常に開ける）。[entered] は止まったときに数える */
    private class GatedOpener(private val gated: Set<String>) : BootVolumeOpener {
        val entered = ConcurrentHashMap<String, CountDownLatch>().also { map -> gated.forEach { map[it] = CountDownLatch(1) } }
        val release = CountDownLatch(1)
        val opens = ConcurrentHashMap<String, AtomicInteger>()

        override fun open(diskId: String): BootVolume? = open(diskId) { false }

        override fun open(diskId: String, cancelled: () -> Boolean): BootVolume? {
            opens.computeIfAbsent(diskId) { AtomicInteger() }.incrementAndGet()
            if (diskId in gated) {
                entered.getValue(diskId).countDown()
                release.await(15, TimeUnit.SECONDS)
            }
            return BootVolume(TestMemoryFileSystem(), readOnly = false)
        }
    }

    private fun saveWithData(h: Harness, store: MachineSaveStore): MachineIdentity = savePrompt(h, store) { m ->
        m.vfs.createDirectory(VfsPath.parse("/data"))
        m.vfs.mount(VfsPath.parse("/data"), TestMemoryFileSystem(), readOnly = false, source = "disk:data")
    }

    @Test
    fun `復元の途中で予約の後に頼るディスクを抜くと止めるべきと答え、止めると取り消しになって予約が残らない`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val identity = saveWithData(h, store)
            val opener = GatedOpener(setOf("data"))
            h.scheduler.hooks.bootVolumes = opener
            val m = h.machine(fuelPerTick = 10_000_000, environment = insertedDisks("root", "data"))
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), finished.listener)))
            // data の予約を取って開いている途中
            assertTrue(opener.entered.getValue("data").await(60, TimeUnit.SECONDS))
            assertTrue(m.diskClaimCount >= 1, "data の予約を取っている")
            assertTrue(m.isRestoring)
            assertTrue(m.disksChanged(DiskSnapshot.of(mapOf("root" to ResolvedDisk(Path.of("unused", "root"), 1L shl 20, 64)))), "復元が頼るディスクが抜けたら止める")
            m.stop()
            assertFalse(m.isRestoring, "stop で復元の quiesce を閉じる")
            opener.release.countDown()
            eventually(10_000, "取り消しの知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(listOf(RestoreOutcome.CANCELLED), finished.outcomes.map { it.first })
            eventually(10_000, "予約が残る") { m.diskClaimCount == 0 }
            assertEquals(MachineState.STOPPED, m.state)
            assertEquals(0, m.lock.withLockValue { m.kernel.processes.size })
            m.close()
        }
    }

    @Test
    fun `復元の途中で予約の前に頼るディスクを抜いても止めるべきと答え、そのディスクは開かない`() = timeout(limit) {
        val store = newStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val identity = saveWithData(h, store)
            // 根を開いている途中（data はまだ予約していない）で data を抜く
            val opener = GatedOpener(setOf("root"))
            h.scheduler.hooks.bootVolumes = opener
            val m = h.machine(fuelPerTick = 10_000_000, environment = insertedDisks("root", "data"))
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), finished.listener)))
            assertTrue(opener.entered.getValue("root").await(60, TimeUnit.SECONDS))
            assertTrue(m.disksChanged(DiskSnapshot.of(mapOf("root" to ResolvedDisk(Path.of("unused", "root"), 1L shl 20, 64)))), "復元が頼るディスクが抜けたら止める")
            m.stop()
            opener.release.countDown()
            eventually(10_000, "取り消しの知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(listOf(RestoreOutcome.CANCELLED), finished.outcomes.map { it.first })
            assertEquals(0, opener.opens["data"]?.get() ?: 0, "抜けたディスクは開かない")
            eventually(10_000, "予約が残る") { m.diskClaimCount == 0 }
            assertEquals(MachineState.STOPPED, m.state)
            m.close()
        }
    }
}
