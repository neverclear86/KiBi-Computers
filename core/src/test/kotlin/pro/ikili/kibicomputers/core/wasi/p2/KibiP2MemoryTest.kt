package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.CoreRef
import pro.ikili.kibicomputers.core.runtime.component.link.FuncRef
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `kibi:computer/memory` の p2（#777。ADR 0777）: wasm-tools で WIT の world から作ったコンポーネントが、canon lower した `commit` を
 * 自分の core から直接呼び、ptr はその core インスタンスの線形メモリの番地になること。意味（区画を数える・keep・範囲外・RAM の上限で断ったときの表示）は p1 と同じ
 */
class KibiP2MemoryTest {
    private val limit = Duration.ofSeconds(180)
    private val kib = 1024L
    private val s = "\$"

    private val imports = """
        (import "kibi:computer/memory@0.1.0" "remaining" (func ${s}remaining (result i64)))
        (import "kibi:computer/memory@0.1.0" "commit" (func ${s}commit (param i32 i32 i64) (result i32)))
    """.trimIndent()

    /** 意味を確かめる `run: func() -> u32`（0 なら成功。0 でなければ失敗した段の番号）。線形メモリは 4 ページ（256 KiB） */
    private val semantics: ByteArray by lazy {
        KibiP2WitTool.component(
            """
            import kibi:computer/memory@0.1.0;
            export run: func() -> u32;
            """.trimIndent(),
            """
            (module
              $imports
              (memory (export "memory") 4)
              (func (export "run") (result i32)
                (local ${s}r0 i64) (local ${s}r1 i64)
                (local.set ${s}r0 (call ${s}remaining))
                ;; 書いていない 2 区画（64 KiB から 8 KiB）を数える
                (if (i32.ne (call ${s}commit (i32.const 65536) (i32.const 8192) (i64.const 0)) (i32.const 1)) (then (return (i32.const 1))))
                (local.set ${s}r1 (call ${s}remaining))
                (if (i64.ne (i64.sub (local.get ${s}r0) (local.get ${s}r1)) (i64.const 8192)) (then (return (i32.const 2))))
                ;; 数え済みの範囲は、keep が u64 の最大でも true
                (if (i32.ne (call ${s}commit (i32.const 65536) (i32.const 8192) (i64.const -1)) (i32.const 1)) (then (return (i32.const 3))))
                ;; 新しい区画があり、keep（2^63 以上）が残らないなら false で、何も数えない
                (if (i32.ne (call ${s}commit (i32.const 131072) (i32.const 4096) (i64.const -1)) (i32.const 0)) (then (return (i32.const 4))))
                (if (i32.ne (call ${s}commit (i32.const 131072) (i32.const 4096) (i64.const 0x8000000000000000)) (i32.const 0)) (then (return (i32.const 5))))
                (if (i64.ne (call ${s}remaining) (local.get ${s}r1)) (then (return (i32.const 6))))
                ;; 線形メモリの外（端をまたぐ・符号なしで溢れる）は false
                (if (i32.ne (call ${s}commit (i32.const 262140) (i32.const 8) (i64.const 0)) (i32.const 0)) (then (return (i32.const 7))))
                (if (i32.ne (call ${s}commit (i32.const -16) (i32.const 32) (i64.const 0)) (i32.const 0)) (then (return (i32.const 8))))
                ;; 長さ 0 は大きさちょうどまで true
                (if (i32.ne (call ${s}commit (i32.const 262144) (i32.const 0) (i64.const 0)) (i32.const 1)) (then (return (i32.const 9))))
                (if (i64.ne (call ${s}remaining) (local.get ${s}r1)) (then (return (i32.const 10))))
                (i32.const 0)))
            """.trimIndent(),
        )
    }

    /** RAM より大きな範囲（512 KiB）を commit し、断られたら [onRefused]（`return` の値か `unreachable`）。数えられたら ok で返る */
    private fun refuseCommand(onRefused: String): ByteArray = KibiP2WitTool.component(
        """
        import kibi:computer/memory@0.1.0;
        export wasi:cli/run@0.2.12;
        """.trimIndent(),
        """
        (module
          $imports
          (memory (export "memory") 8)
          (func (export "wasi:cli/run@0.2.12#run") (result i32)
            (if (call ${s}commit (i32.const 0) (i32.const 524288) (i64.const 0)) (then (return (i32.const 0))))
            $onRefused))
        """.trimIndent(),
    )

    private fun load(bytes: ByteArray): ComponentProgram = ComponentProgram.load(LinkTesting.runtime, bytes, KernelComponentHost)

    @Test
    fun `commitはmainモジュールに直接lowerされ、canonのmemoryを持たない`() {
        val plan = load(semantics).plan
        // wit-component が shim を挟まない（core モジュールは 1 つ）。挟むと呼び手が shim になり、ptr を解くメモリが変わる
        assertEquals(1, plan.modules.size)
        val lowered = plan.steps.single().imports.filter { it.first.module == "kibi:computer/memory@0.1.0" }
        assertEquals(setOf("remaining", "commit"), lowered.map { it.first.name }.toSet())
        for ((_, ref) in lowered) {
            val spec = plan.lowers[(ref as CoreRef.Lowered).lower]
            assertTrue(spec.callee is FuncRef.Host)
            assertEquals(null, spec.options.memory)
        }
    }

    @Test
    fun `remainingとcommitがp1と同じ意味で動く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val result = AtomicReference<Any>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    result.set((ctx.component!!.call("run") as CanonValue.I32).value)
                } catch (t: Throwable) {
                    result.set(t)
                }
                StepResult.Exit(0)
            }
            val pid = m.spawn(Program.component(load(semantics)), driver, SpawnOptions(), 0)
            eventually(120_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            (result.get() as? Throwable)?.let { throw it }
            assertEquals(0, result.get(), "WAT の段 ${result.get()} で失敗した")
            m.stop()
        }
    }

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val pid: Int, val kind: ProcessKind)

    /** p1 の `kibi-memory-wasi.wasm`（`kibi_memory.h` で `commit` を呼ぶ C）を、アダプターで p2 のコマンドにしたもの */
    private val adapted: ByteArray by lazy {
        val module = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-memory-wasi.wasm")).use { it.readBytes() }
        KibiP2WitTool.adapt("import kibi:computer/memory@0.1.0;", module)
    }

    /** [bytes] を RAM [ramBytes] のマシンで [args] で `exec` して、終わるまで待つ */
    private fun exec(bytes: ByteArray, vararg args: String, ramBytes: Long = 256 * kib): Result = Harness(
        SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).build(),
        execRuntime = EndiveRuntime(),
    ).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = ramBytes)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/km"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        val pid = m.exec("/bin/km", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("km") + args))
        val kind = m.rt(pid).driver.kind
        eventually(60_000, "終わらない: ${host.text()}") {
            h.scheduler.tick()
            Thread.sleep(1)
            m.stateOf(pid).isTerminal
        }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        val state = m.stateOf(pid)
        eventually(60_000, "RAM が戻らない: ${m.memoryAccount.usedBytes}") {
            h.scheduler.tick()
            m.memoryAccount.usedBytes == 0L
        }
        h.scheduler.tick()
        Result(host.text(), state, exitCode, pid, kind)
    }

    @Test
    fun `commitをRAMの上限で断られた後にerrで終わると、メモリ不足が原因だったと表示する`() = timeout(limit) {
        val r = exec(refuseCommand("(i32.const 1)"))
        assertEquals(ProcessKind.WAIT_POINT, r.kind, "memory の import は待機点型を妨げない")
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(1, r.exitCode, r.console)
        assertTrue("kibi: km (pid ${r.pid}) exited with code 1 (kibi:memory commit was refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }

    @Test
    fun `commitをRAMの上限で断られた後にトラップで落ちると、理由にメモリ不足を添える`() = timeout(limit) {
        val r = exec(refuseCommand("(unreachable)"))
        assertEquals(ProcessState.CRASHED, r.state, r.console)
        assertTrue("kibi: km (pid ${r.pid}) crashed: unreachable executed (kibi:memory commit was refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }

    @Test
    fun `アダプターで包んだp1のCのcommitは、mainモジュールのメモリの区画を数える`() = timeout(limit) {
        val plan = load(adapted).plan
        // アダプターは kibi:computer/memory を import しない。commit を呼ぶのは C の main モジュールだけ
        val callers = plan.steps.filter { step -> step.imports.any { it.first.module == "kibi:computer/memory@0.1.0" } }
        assertEquals(1, callers.size, "commit を import する core インスタンス")
        assertTrue(plan.modules[callers.single().module].exports.any { it.name == "memory" }, "commit を呼ぶのはメモリを定義した main モジュール")
        val r = exec(adapted, "commit", "96", ramBytes = 512 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
        val commit = r.console.lines().first { it.startsWith("commit") }.split(' ').mapNotNull { it.toLongOrNull() }
        assertEquals(1L, commit[0], r.console)
        assertTrue(commit[1] in (96 * kib - 2 * 4096)..(96 * kib + 4096), r.console)
        assertTrue("write decreased 0" in r.console, r.console)
    }

    @Test
    fun `アダプターで包んだp1のCがcommitを断られて0以外で終わると、メモリ不足を表示する`() = timeout(limit) {
        val r = exec(adapted, "refuse", "1")
        assertEquals(1, r.exitCode, r.console)
        assertTrue("kibi: km (pid ${r.pid}) exited with code 1 (kibi:memory commit was refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }
}
