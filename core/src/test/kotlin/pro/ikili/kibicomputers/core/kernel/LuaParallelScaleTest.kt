package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.lang.management.ManagementFactory
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `parallel` の中で `os.sleep` と `os.pullEvent` のループで待つ Lua を多数台動かす（#95。ADR 0095）。
 * 待っている台はスレッドを持たない（待機点で巻き戻る）ことと、tick の時間を確かめる。
 *
 * 普段は少ない台数で測る。`-Pkibi.luaReplMachines=1000` を付けると、その台数で測る（LuaReplMemoryTest と同じ指定）。
 * 結果は標準出力に出す。
 */
class LuaParallelScaleTest {
    private val machines = System.getProperty("kibi.luaReplMachines")?.toInt() ?: 16

    private val script = """
        local n = 0
        parallel.waitForAll(
            function() while true do os.sleep(1); n = n + 1 end end,
            function() while true do os.pullEvent("never") end end,
            function() parallel.waitForAny(function() while true do os.sleep(2) end end, function() os.pullEvent("never") end) end
        )
    """.trimIndent()

    @Test
    fun `parallelで待っている台はスレッドを持たない`() = timeout(Duration.ofSeconds(120L + machines / 2)) {
        val lua = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がない" }.use { it.readBytes() }
        val threads = ManagementFactory.getThreadMXBean()
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // 1 台目を動かしてワーカーのスレッドを起こしてから数える
            val started = ArrayList<Pair<Machine, Int>>()
            fun start() {
                val m = h.machine(fuelPerTick = 10_000_000)
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val f = m.vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, lua, 0, lua.size)
                val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", script), fds = SpawnOptions.ROOT_PREOPEN))
                assertEquals(ProcessKind.WAIT_POINT, m.rt(pid).driver.kind)
                started += m to pid
            }
            fun allWaiting() = started.all { (m, pid) -> m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint }
            start()
            eventuallyDescribe(30_000, { "1 台目が待たない: ${started.map { (m, pid) -> "${m.stateOf(pid)} ${m.isAtWaitPoint}" }}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                allWaiting()
            }
            val baseThreads = threads.threadCount
            repeat(machines - 1) { start() }
            eventually(60_000L + machines * 200L, "待たない") {
                h.scheduler.tick()
                Thread.sleep(1)
                allWaiting()
            }
            // 50 ms ごとに tick を回し、約 3 秒（sleep(1)・sleep(2) で起きる分を含む）の tick の時間と、JVM 全体の CPU 時間を測る
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            val cpu0 = os?.processCpuTime ?: 0L
            val wall0 = System.nanoTime()
            val ticks = 60
            var total = 0L
            var max = 0L
            repeat(ticks) {
                val t0 = System.nanoTime()
                h.scheduler.tick()
                val dt = System.nanoTime() - t0
                total += dt
                if (dt > max) max = dt
                Thread.sleep(50)
            }
            val cpuMs = ((os?.processCpuTime ?: 0L) - cpu0) / 1_000_000
            val wallMs = (System.nanoTime() - wall0) / 1_000_000
            eventually(30_000, "待ちに戻らない") {
                h.scheduler.tick()
                Thread.sleep(1)
                allWaiting()
            }
            val extraThreads = threads.threadCount - baseThreads
            println(
                "Lua parallel x $machines: 増えたスレッド $extraThreads, tick の時間 平均 ${total / ticks / 1000} us・最大 ${max / 1000} us, " +
                    "CPU 時間 $cpuMs ms / 経過 $wallMs ms",
            )
            assertTrue(started.none { (m, pid) -> m.stateOf(pid).isTerminal }, "止まった台がある")
            // 待つ台ごとにスレッドを持たない（BLOCKING に落ちると台数分増える）
            assertTrue(extraThreads < 8, "スレッドが増えた: $extraThreads")

            started.forEach { (m, _) -> m.stop() }
            eventually(30_000) {
                h.scheduler.tick()
                started.all { (m, pid) -> m.stateOf(pid).isTerminal }
            }
        }
    }
}
