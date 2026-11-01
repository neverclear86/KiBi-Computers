package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.endive.committedPages
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * Lua の REPL が入力を待っているときのメモリ（#334）。
 *
 * 普段は少ない台数で測る。`-Pkibi.luaReplMachines=1000` を付けると、その台数で測る（時間がかかる。テストのヒープを 4 GiB にする）。
 * 結果は標準出力に出す。
 */
class LuaReplMemoryTest {
    private val machines = System.getProperty("kibi.luaReplMachines")?.toInt() ?: 16

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8) }
    }

    private fun usedHeap(): Long {
        val bean = ManagementFactory.getMemoryMXBean()
        repeat(3) {
            System.gc()
            Thread.sleep(100)
        }
        return bean.heapMemoryUsage.used
    }

    @Test
    fun `入力を待っているLuaのREPLは、宣言したメモリの一部だけをJVMのヒープに確保する`() = timeout(Duration.ofSeconds(60L + machines)) {
        val lua = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がない" }.use { it.readBytes() }
        val schedulerConfig = SchedulerConfig.builder().maxConcurrentMachines(8).maxBlockingProcesses(machines + 16).build()
        Harness(schedulerConfig, execRuntime = EndiveRuntime()).use { h ->
            // 読み込んだモジュール（命令の列）は全台で共有する。台ごとに読み込むと、モジュールが 1 台あたり約 16 MiB を使う
            // （読み込みの共有は #37 の範囲）。ここでは、インスタンスごとに増える分を測る
            val program = Program.loadMetered(EndiveRuntime(), lua, ModuleLimits.DEFAULT)
            val before = usedHeap()
            val started = (1..machines).map {
                val host = ConsoleHost()
                val m = h.machine(fuelPerTick = 10_000_000, host = host)
                m.lock.withLockValue { m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root") }
                Triple(m, host, m.spawn(program, KernelDrivers.DEFAULT.create(program), SpawnOptions(args = listOf("lua"))))
            }
            eventually(30_000L + machines * 200L, "プロンプトが出ない") {
                h.scheduler.tick()
                Thread.sleep(1)
                started.all { (_, host, _) -> host.text().endsWith("\r\n> ") }
            }
            assertTrue(started.all { (m, _, pid) -> m.stateOf(pid) == ProcessState.WAITING_EVENT })
            val after = usedHeap()

            var declared = 0L
            var committed = 0L
            for ((m, _, pid) in started) {
                val memory = m.rt(pid).instances.mapNotNull { it.memory }.single()
                declared += memory.pages
                committed += committedPages(memory)
            }
            val page = WasmMemory.PAGE_SIZE.toLong()
            val heapPerMachine = (after - before) / machines
            println(
                "Lua REPL x $machines: 宣言 ${declared * page / machines / 1024} KiB/台, " +
                    "確保 ${committed * page / machines / 1024} KiB/台, ヒープ増分 ${heapPerMachine / 1024} KiB/台（合計 ${(after - before) / 1024 / 1024} MiB）",
            )
            assertTrue(committed < declared, "確保 $committed / 宣言 $declared")
            // 以前（スタック 16 MiB を全部確保）は 1 台で 16 MiB を超えていた
            assertTrue(committed * page / machines < 4L * 1024 * 1024, "1 台あたりの確保が大きすぎる: ${committed * page / machines}")

            started.forEach { (m, _, _) -> m.stop() }
            eventually(30_000) {
                h.scheduler.tick()
                started.all { (m, _, pid) -> m.stateOf(pid).isTerminal }
            }
        }
    }
}
