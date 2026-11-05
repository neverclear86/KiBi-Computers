package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.FakeContext
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.p1.P1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Kibi
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * init（`/sbin/init`、pid 1）のメモリ不足の表示（#414）。init 以外のプロセスの表示は `WrittenMemoryTest`・`MemoryLimitTest`・
 * `KibiMemoryProgramTest` が確かめる。ここでは、起動の手順（#49）で動かした init が RAM の上限で止まったとき、
 * `kibi: init ...` の行にメモリ不足の理由・注記が付くことを、ダミーのランタイム（同じ規則で数える）で確かめる
 */
class InitMemoryMessageTest {
    private val limit = Duration.ofSeconds(60)
    private val page = WasmMemory.PAGE_SIZE.toLong()
    private val chunk = 4096

    private class ConsoleRecorder : MachineHost {
        val console = StringBuffer()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            console.append(String(bytes, Charsets.UTF_8).replace("\r\n", "\n"))
        }
    }

    /** `/sbin/init` に [init] を置いたディスク `d` だけがある */
    private class InitDisk(init: ByteArray) : BootVolumeOpener {
        private val fs = TestMemoryFileSystem().also { fs ->
            fs.createDirectory(VfsPath.parse("/sbin"))
            val node = fs.createFile(VfsPath.parse(BootConfig.INIT_PATH))
            fs.write(node, 0, init, 0, init.size)
        }

        override fun open(diskId: String): BootVolume? = if (diskId == "d") BootVolume(fs, readOnly = false) else null
    }

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val results: List<Long>)

    private val procExitType = WasmFunctionType.of(listOf(WasmValueType.I32), emptyList())

    /**
     * RAM [ramBytes]・[pages] ページの線形メモリの init を、`_start` の本体 [body] で起動し、マシンが止まるまで待つ。
     * [body] は返した値を結果に積める（grow・commit の戻り値を確かめる）
     */
    private fun bootInit(ramBytes: Long, pages: Int = 1, body: (FakeContext, MutableList<Long>) -> Unit): Result = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleRecorder()
            val results = Collections.synchronizedList(mutableListOf<Long>())
            val module = FakeModule.builder()
                .fuelMetered()
                .memory(pages)
                .import(P1.MODULE, "proc_exit", procExitType)
                .import(WasiP1Kibi.MODULE, "memory_commit", WasiP1Kibi.typeOf("memory_commit")!!)
                .export("_start", VOID) { ctx, _ ->
                    ctx.step(1)
                    body(ctx, results)
                    LongArray(0)
                }
                .build()
            val bytes = "fake-oom-init".toByteArray().also { h.runtime.define(it, module) }
            h.scheduler.hooks.bootVolumes = InitDisk(bytes)
            val m = h.machine(host = host, memoryBytes = ramBytes)
            assertTrue(m.boot(BootConfig.builder().bootDisks(listOf("d")).build()))
            eventually(30_000, "init が終わらない: ${host.console}") {
                h.scheduler.tick()
                Thread.sleep(1)
                host.console.contains("kibi: init ") && m.state == MachineState.STOPPED
            }
            assertTrue(m.awaitTermination(5_000))
            val (state, exitCode) = m.lock.withLockValue { m.kernel.processes[1]!!.let { it.state to it.exitCode } }
            assertEquals(0L, m.memoryAccount.usedBytes, "RAM が戻らない")
            Result(host.console.toString(), state, exitCode, results.toList())
        }
    }

    /** [first] 番目から [count] 個の 4 KiB の区画に、1 バイトずつ書く */
    private fun WasmMemory.touch(first: Int, count: Int) {
        for (i in first until first + count) writeU8(i * chunk, 1)
    }

    private fun FakeContext.exit(code: Int) {
        callImport(P1.MODULE, "proc_exit", code.toLong())
    }

    @Test
    fun `initがRAMを超えて書いてトラップすると、メモリ不足と表示する`() {
        // RAM 64 KiB（16 区画）、線形メモリ 2 ページ。17 区画めを書いたところでトラップ
        val r = bootInit(page, pages = 2) { ctx, _ -> ctx.memory.touch(0, 17) }
        assertEquals(ProcessState.CRASHED, r.state, r.console)
        assertEquals("kibi: booting from disk d\nkibi: init crashed: out of memory (RAM limit 64 KiB)\n", r.console)
    }

    @Test
    fun `initがgrowを断られた後に0以外で終わると、メモリ不足が原因だったと表示する`() {
        // 15 区画を書いてから 1 ページ増やす（増やした所の端の 2 区画は数えられない）
        val r = bootInit(page) { ctx, results ->
            ctx.memory.touch(0, 15)
            results += ctx.memory.grow(1).toLong()
            ctx.exit(1)
        }
        assertEquals(listOf(-1L), r.results, "端の区画を数えられないので grow は -1")
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(1, r.exitCode)
        assertTrue(
            r.console.endsWith("kibi: init exited with code 1 (memory.grow was refused: out of memory, RAM limit 64 KiB)\n"),
            r.console,
        )
    }

    @Test
    fun `initがcommitを断られた後に0以外で終わると、メモリ不足が原因だったと表示する`() {
        // 残りは 1 区画。まだ数えていない最後の区画を、その後に 1 区画残して数えようとする
        val r = bootInit(page) { ctx, results ->
            ctx.memory.touch(0, 15)
            results += ctx.callImport(WasiP1Kibi.MODULE, "memory_commit", 15L * chunk, chunk.toLong(), chunk.toLong())[0]
            ctx.exit(2)
        }
        assertEquals(listOf(0L), r.results, "commit は断られる")
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(2, r.exitCode)
        assertTrue(
            r.console.endsWith("kibi: init exited with code 2 (kibi:memory commit was refused: out of memory, RAM limit 64 KiB)\n"),
            r.console,
        )
    }

    @Test
    fun `initがgrowとcommitの両方を断られた後に0以外で終わると、両方を表示する`() {
        val r = bootInit(page) { ctx, results ->
            ctx.memory.touch(0, 15)
            results += ctx.memory.grow(1).toLong()
            results += ctx.callImport(WasiP1Kibi.MODULE, "memory_commit", 15L * chunk, chunk.toLong(), chunk.toLong())[0]
            ctx.exit(3)
        }
        assertEquals(listOf(-1L, 0L), r.results)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertTrue(
            r.console.endsWith("kibi: init exited with code 3 (memory.grow and kibi:memory commit were refused: out of memory, RAM limit 64 KiB)\n"),
            r.console,
        )
    }

    @Test
    fun `initがgrowを断られた後にトラップで落ちると、理由にメモリ不足を添える`() {
        val r = bootInit(page) { ctx, _ ->
            ctx.memory.touch(0, 15)
            ctx.memory.grow(1)
            ctx.trap(WasmTrapKind.UNREACHABLE)
        }
        assertEquals(ProcessState.CRASHED, r.state, r.console)
        assertTrue(
            r.console.endsWith("kibi: init crashed: unreachable executed (memory.grow was refused: out of memory, RAM limit 64 KiB)\n"),
            r.console,
        )
    }

    @Test
    fun `initがgrowを断られても0で終われば、注記を付けない`() {
        val r = bootInit(page) { ctx, _ ->
            ctx.memory.touch(0, 15)
            ctx.memory.grow(1)
            ctx.exit(0)
        }
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertTrue(r.console.endsWith("kibi: init exited with code 0\n"), r.console)
        assertFalse("refused" in r.console, r.console)
    }
}
