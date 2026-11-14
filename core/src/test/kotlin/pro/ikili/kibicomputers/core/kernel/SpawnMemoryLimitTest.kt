package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 全プロセスのメモリ合計の管理（#48。ADR 0048）: インスタンスを作る間に RAM の上限（プロセス・マシン・サーバー全体）で
 * 確保を断られたプロセスは CRASHED になり、コンソールにメモリ不足だったと出る。断った分はすべての段に返る
 */
class SpawnMemoryLimitTest {
    private val limit = Duration.ofSeconds(60)
    private val page = WasmMemory.PAGE_SIZE.toLong()

    private class ConsoleRecorder : MachineHost {
        val console = StringBuffer()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            console.append(String(bytes, Charsets.UTF_8).replace("\r\n", "\n"))
        }
    }

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /** 最初のテーブルに [tableBytes] バイトを使い、動いたら 0 で終わるモジュール */
    private fun tableModule(tableBytes: Long): FakeModule = FakeModule.builder()
        .fuelMetered()
        .memory(1)
        .tableBytes(tableBytes)
        .export("run", VOID) { ctx, _ ->
            ctx.step(1)
            LongArray(0)
        }
        .build()

    private fun Machine.spawnNamed(program: Program, driver: ProcessDriver, name: String): Int =
        spawn(program, driver, SpawnOptions(args = listOf(name)))

    /** [pid] が CRASHED で終わり、[line] がコンソールに出るまで回す */
    private fun Harness.awaitCrash(m: Machine, host: ConsoleRecorder, pid: Int, line: String) {
        tickUntil("$line が出ない: ${host.console}") { host.console.contains("$line\n") }
        assertState(ProcessState.CRASHED, m, pid)
    }

    private fun harness(totalBytes: Long? = null, moduleLimits: ModuleLimits? = null): Harness = Harness(
        SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).apply { totalBytes?.let { totalMemoryBytes(it) } }.build(),
        moduleLimits = moduleLimits,
    )

    @Test
    fun `マシンのRAMで最初のテーブルを確保できないと、子はCRASHEDになりメモリ不足と出る`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = page)
            val pid = m.spawnNamed(h.program(tableModule(page + 1)), runDriver(), "/bin/big")
            h.awaitCrash(m, host, pid, "kibi: /bin/big (pid $pid) crashed: cannot instantiate module (out of memory, RAM limit 64 KiB)")
            assertTrue(m.rt(pid).lastError is WasmLinkException)
            assertEquals(0L, m.memoryAccount.usedBytes, "断った分は RAM に残らない")
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes, "サーバー全体にも残らない")
            // RAM に収まる子は、そのまま作れる
            val ok = m.spawnNamed(h.program(tableModule(page)), runDriver(), "/bin/ok")
            h.tickUntil("収まる子が終わらない") { m.stateOf(ok).isTerminal }
            assertState(ProcessState.EXITED, m, ok)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `プロセスの上限で断られたときは、プロセスの上限を書く`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 16 * page, maxProcessMemoryBytes = 2 * page)
            val pid = m.spawnNamed(h.program(tableModule(3 * page)), runDriver(), "big")
            h.awaitCrash(m, host, pid, "kibi: big (pid $pid) crashed: cannot instantiate module (out of memory, RAM limit 128 KiB)")
            assertEquals(0L, m.memoryAccount.usedBytes)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `サーバー全体の上限で断られたときは、RAMの上限ではなくサーバー全体の上限と書く`() = timeout(limit) {
        harness(totalBytes = 2 * page).use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 16 * page)
            val pid = m.spawnNamed(h.program(tableModule(3 * page)), runDriver(), "big")
            h.awaitCrash(m, host, pid, "kibi: big (pid $pid) crashed: cannot instantiate module (out of memory, server memory limit reached)")
            assertEquals(0L, m.memoryAccount.usedBytes)
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `サーバー全体の上限で書き込みがトラップしたときも、サーバー全体の上限と書く`() = timeout(limit) {
        harness(totalBytes = page).use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 16 * page)
            val pid = m.spawnNamed(
                h.program(FakeModule.builder().fuelMetered().memory(2).build()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    // サーバー全体の残りは 16 区画。17 区画めでトラップ
                    for (i in 0 until 17) memory.writeU8(i * 4096, 1)
                    StepResult.Exit(0)
                },
                "w",
            )
            h.awaitCrash(m, host, pid, "kibi: w (pid $pid) crashed: out of memory (server memory limit reached)")
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `tmpでRAMを埋めた後は、子を作れずメモリ不足と出る`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = page)
            // プロセスがなくなるとマシンが止まって /tmp も返るので、待ち続けるプロセスを置いておく
            val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
            val started = CountDownLatch(1)
            m.spawnNamed(
                h.program(FakeModule.builder().fuelMetered().build()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    started.countDown()
                    ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
                    StepResult.Exit(0)
                },
                "holder",
            )
            h.tickUntil("待つプロセスが始まらない") { started.count == 0L }
            assertEquals(null, m.mountTmp())
            val file = m.vfs.open(VfsPath.parse("/tmp/x"), OpenOptions(read = false, write = true, create = true))
            val bytes = 15 * 4096
            assertEquals(bytes, m.vfs.write(file, ByteArray(bytes), 0, bytes))
            val filled = m.memoryAccount.usedBytes
            assertTrue(filled >= bytes)
            // 残りより 1 バイト多いテーブル
            val pid = m.spawnNamed(h.program(tableModule(page - filled + 1)), runDriver(), "child")
            h.awaitCrash(m, host, pid, "kibi: child (pid $pid) crashed: cannot instantiate module (out of memory, RAM limit 64 KiB)")
            assertEquals(filled, m.memoryAccount.usedBytes, "子の分は残らず、/tmp の分だけが残る")
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `RAMと関係のない作れない理由には、メモリ不足と書かない`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = page)
            val missing = FakeModule.builder().fuelMetered().import("env", "nothing", VOID).export("run", VOID) { _, _ -> LongArray(0) }.build()
            val pid = m.spawnNamed(h.program(missing), runDriver(), "nolink")
            h.awaitCrash(m, host, pid, "kibi: nolink (pid $pid) crashed: cannot instantiate module")
            assertFalse(host.console.contains("out of memory"), host.console.toString())
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `実行中に作るインスタンスがRAMで断られて異常終了すると、メモリ不足と出る`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 2 * page)
            val second = h.program(tableModule(2 * page))
            val missing = h.program(FakeModule.builder().fuelMetered().import("env", "nothing", VOID).build())
            val caught = AtomicReference<Throwable?>()
            val pid = m.spawnNamed(
                h.program(FakeModule.builder().fuelMetered().memory(1).tableBytes(page).build()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    // RAM と関係なく作れないときは、そのまま捕まえて続けられる
                    try {
                        ctx.instantiate(missing.module) { }
                    } catch (e: WasmLinkException) {
                        caught.set(e)
                    }
                    ctx.instantiate(second.module) { }
                    StepResult.Exit(0)
                },
                "two",
            )
            h.awaitCrash(m, host, pid, "kibi: two (pid $pid) crashed: cannot instantiate module (out of memory, RAM limit 128 KiB)")
            assertTrue(caught.get() is WasmLinkException)
            assertEquals(0L, m.memoryAccount.usedBytes)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `実行中に作るインスタンスがRAMと関係なく作れないときは、前に断られていてもメモリ不足と書かない`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 2 * page)
            val big = h.program(tableModule(4 * page))
            val missing = h.program(FakeModule.builder().fuelMetered().import("env", "nothing", VOID).build())
            val pid = m.spawnNamed(
                h.program(FakeModule.builder().fuelMetered().memory(1).build()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    // 先に RAM で断られても、捕まえて続ける
                    try {
                        ctx.instantiate(big.module) { }
                    } catch (_: WasmLinkException) {
                    }
                    ctx.instantiate(missing.module) { }
                    StepResult.Exit(0)
                },
                "unrelated",
            )
            h.awaitCrash(m, host, pid, "kibi: unrelated (pid $pid) crashed: cannot instantiate module")
            assertFalse(host.console.contains("out of memory"), host.console.toString())
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `memory_growをサーバー全体の上限で断られた後に0以外で終わると、サーバー全体の上限と書く`() = timeout(limit) {
        harness(totalBytes = page).use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 16 * page)
            val results = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val pid = m.spawnNamed(
                h.program(FakeModule.builder().fuelMetered().memory(1).build()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    // サーバー全体の残りは 16 区画。15 区画を書いてから 1 ページ増やすと、端の 2 区画を数えられない
                    for (i in 0 until 15) memory.writeU8(i * 4096, 1)
                    results += memory.grow(1)
                    StepResult.Exit(1)
                },
                "g",
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(listOf(-1), results.toList(), "端の区画を数えられないので grow は -1")
            h.tickUntil("コンソールに出ない: ${host.console}") { host.console.contains("kibi: g (pid $pid) exited") }
            assertTrue(
                host.console.contains("kibi: g (pid $pid) exited with code 1 (memory.grow was refused: out of memory, server memory limit reached)\n"),
                host.console.toString(),
            )
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    /**
     * grow と commit を違う段で断ったとき（#48）: 注記の上限は、プロセス全体で最後に断った段ではなく、それぞれを断った段で書く。
     * [growFirst] なら grow（サーバー全体）→ commit（プロセスの上限）、そうでなければ逆の順に断らせる
     */
    private fun growAndCommitRefusedAtDifferentStages(growFirst: Boolean) = timeout(limit) {
        harness(totalBytes = page).use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 16 * page)
            val results = java.util.Collections.synchronizedList(mutableListOf<Any>())
            val pid = m.spawnNamed(
                h.program(FakeModule.builder().fuelMetered().memory(1).build()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    // サーバー全体の残りは 16 区画。15 区画を書いてから 1 ページ増やすと、端の 2 区画をサーバー全体で数えられない
                    for (i in 0 until 15) memory.writeU8(i * 4096, 1)
                    // 残りをプロセスの上限より多く残せと言う commit は、プロセスの上限で断られる（サーバー全体には keepBytes をかけない）
                    val commit = { ctx.commitMemory(memory, 15 * 4096, 1, 1L shl 40) }
                    if (growFirst) {
                        results += memory.grow(1)
                        results += commit()
                    } else {
                        results += commit()
                        results += memory.grow(1)
                    }
                    StepResult.Exit(1)
                },
                "gc",
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(if (growFirst) listOf<Any>(-1, false) else listOf<Any>(false, -1), results.toList())
            h.tickUntil("コンソールに出ない: ${host.console}") { host.console.contains("kibi: gc (pid $pid) exited") }
            assertTrue(
                host.console.contains(
                    "kibi: gc (pid $pid) exited with code 1 (memory.grow was refused: out of memory, server memory limit reached; " +
                        "kibi:memory commit was refused: out of memory, RAM limit 1 MiB)\n",
                ),
                host.console.toString(),
            )
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `growをサーバー全体で断られた後にcommitをプロセスの上限で断られると、それぞれを断った上限を書く`() =
        growAndCommitRefusedAtDifferentStages(growFirst = true)

    @Test
    fun `commitをプロセスの上限で断られた後にgrowをサーバー全体で断られても、それぞれを断った上限を書く`() =
        growAndCommitRefusedAtDifferentStages(growFirst = false)

    /** 作れない core インスタンス 1 つのコンポーネント: 最初のテーブルが 64 KiB に収まらない（`funcref` の要素は 12 バイト） */
    private val tooBigComponent = """
        (component
          (core module ${'$'}a
            (table 10000 funcref)
            (func (export "run")))
          (core instance ${'$'}ia (instantiate ${'$'}a)))
    """

    /**
     * 先に作る core インスタンス a の start 関数が 15 区画を書いて 1 ページ増やし、断られても続ける（`memory.grow` は -1）。
     * 後に作る b は、線形メモリの外に書く data セグメントを持ち、RAM と関係なく作れない
     */
    private val unrelatedLinkFailureComponent = """
        (component
          (core module ${'$'}a
            (memory (export "mem") 1)
            (func ${'$'}start
              (local ${'$'}i i32)
              (loop ${'$'}l
                (i32.store8 (i32.mul (local.get ${'$'}i) (i32.const 4096)) (i32.const 1))
                (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
                (br_if ${'$'}l (i32.lt_u (local.get ${'$'}i) (i32.const 15))))
              (drop (memory.grow (i32.const 1))))
            (start ${'$'}start))
          (core module ${'$'}b
            (memory 1)
            (data (i32.const 65536) "x"))
          (core instance ${'$'}ia (instantiate ${'$'}a))
          (core instance ${'$'}ib (instantiate ${'$'}b)))
    """

    private fun Machine.spawnComponent(text: String, name: String): Int =
        spawn(
            Program.component(LinkTesting.load(text, metered = false)),
            TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) },
            SpawnOptions(args = listOf(name), allowUnmetered = true),
        )

    @Test
    fun `コンポーネントのプロセスがRAMで作れないときは、メモリ不足と出てRAMが戻る`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = page)
            val pid = m.spawnComponent(tooBigComponent, "comp")
            h.awaitCrash(m, host, pid, "kibi: comp (pid $pid) crashed: cannot instantiate module (out of memory, RAM limit 64 KiB)")
            assertTrue(m.rt(pid).lastError is WasmLinkException)
            assertEquals(0L, m.memoryAccount.usedBytes, "作りかけの分も RAM に残らない")
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `コンポーネントのプロセスがサーバー全体の上限で作れないときは、サーバー全体の上限と書く`() = timeout(limit) {
        harness(totalBytes = page).use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = 16 * page)
            val pid = m.spawnComponent(tooBigComponent, "comp")
            h.awaitCrash(m, host, pid, "kibi: comp (pid $pid) crashed: cannot instantiate module (out of memory, server memory limit reached)")
            assertEquals(0L, m.memoryAccount.usedBytes)
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `コンポーネントの先のcoreインスタンスが確保を断られても、後のインスタンスがRAMと関係なく作れないときはメモリ不足と書かない`() = timeout(limit) {
        harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = page)
            val pid = m.spawnComponent(unrelatedLinkFailureComponent, "comp")
            h.tickUntil("異常終了が出ない: ${host.console}") { host.console.contains("kibi: comp (pid $pid) crashed: ") }
            assertState(ProcessState.CRASHED, m, pid)
            assertTrue(m.rt(pid).lastError is WasmLinkException, "${m.rt(pid).lastError}")
            // a の start 関数が断られたことを確かめる（断っていないと、この試験は何も確かめていない）
            assertTrue(m.rt(pid).memory.refusedReservation, "先のインスタンスが確保を断られていない")
            // 先に断った grow の注記は付くが、作れなかった理由をメモリ不足とは書かない
            assertFalse(host.console.contains("cannot instantiate module (out of memory"), host.console.toString())
            assertTrue(host.console.contains("cannot instantiate module (memory.grow was refused: out of memory, RAM limit 64 KiB)"), host.console.toString())
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `本物のランタイムでも、最初のテーブルがRAMに収まらない子はメモリ不足と出て、RAMが戻る`() = timeout(limit) {
        harness(moduleLimits = ModuleLimits(maxTableElements = 100_000)).use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host, memoryBytes = page)
            // funcref の要素1つは 12 バイト。10,000 要素は 64 KiB に収まらない
            val wasm = WasmBinary().run {
                table(10_000)
                build()
            }
            val started = CountDownLatch(1)
            val pid = m.spawn(
                Program.load(EndiveRuntime(), wasm),
                TestDriver(ProcessKind.BLOCKING) {
                    started.countDown()
                    StepResult.Exit(0)
                },
                SpawnOptions(args = listOf("table"), allowUnmetered = true),
            )
            h.awaitCrash(m, host, pid, "kibi: table (pid $pid) crashed: cannot instantiate module (out of memory, RAM limit 64 KiB)")
            assertEquals(1L, started.count, "インスタンスを作れないので、本体は動かない")
            assertEquals(0L, m.memoryAccount.usedBytes, "作りかけの分も RAM に残らない")
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
            assertFalse(m.rt(pid).lastError is WasmTrapException)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }
}
