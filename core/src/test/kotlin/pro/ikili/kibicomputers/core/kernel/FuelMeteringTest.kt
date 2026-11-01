package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** `Machine.exec` が燃料の計測コードを挿入し、燃料切れで中断して次の tick で再開する（#38）。実際の Endive で動かす */
class FuelMeteringTest {
    private val limit = Duration.ofSeconds(60)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /**
     * 1 tick 分の燃料を配ってから、tick を進めずに燃料切れで止まるのを待つ（毎回 tick すると、遅い環境では止まった瞬間を見逃す）。
     * スレッドを持って待つ（`WAITING_FUEL`）か、変換したプロセスが巻き戻して、スレッドを持たずに燃料待ちの列にいる（#339）
     */
    private fun Harness.awaitFuelWait(machine: Machine, pid: Int, message: String) {
        scheduler.tick()
        eventually(30_000, message) {
            when (machine.stateOf(pid)) {
                ProcessState.WAITING_FUEL -> true
                ProcessState.RUNNABLE -> machine.lock.withLockValue {
                    val rt = machine.rt(pid)
                    rt.thread == null && rt.waitSet == null && rt.process.asyncStack?.isEmpty == false
                }
                else -> false
            }
        }
    }

    private fun Harness.ticks(n: Int) = repeat(n) {
        scheduler.tick()
        Thread.sleep(1)
    }

    /** ルートに `/bin` だけのファイルシステムをつなぎ、[path] に [bytes] を置く（マシンごとに1回） */
    private fun Machine.install(path: String, bytes: ByteArray) = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun Machine.isUnmetered(pid: Int): Boolean = lock.withLockValue { kernel.processes[pid]!!.unmetered }

    /** 標準出力をつなぐ ByteStream を作り、その id を返す */
    private fun Machine.outputStream(): Int = lock.withLockValue { ByteStream(64 * 1024).also { kernel.objects.add(it) }.id }

    private fun Machine.readOutput(id: Int): String = lock.withLockValue {
        val s = kernel.objects[id] as ByteStream
        val buf = ByteArray(s.available)
        s.read(buf, 0, buf.size, wakeSink) as? ReadResult.Bytes
        String(buf, Charsets.UTF_8)
    }

    @Test
    fun `計算は燃料切れで中断し、次のtickで再開して最後まで進む`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            m.install("/bin/fuel", resource("fuel.wasm"))
            val result = AtomicLong(-1)
            val drivers = ProcessDriverFactory {
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    result.set(ctx.instance.call("fib", 22).single())
                    StepResult.Exit(0)
                }
            }
            val startTick = h.scheduler.now
            val pid = m.exec("/bin/fuel", drivers)
            assertFalse(m.isUnmetered(pid), "exec したプログラムは燃料を計測する")
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(17711L, result.get())
            // fib(22) は数十万命令なので、1 tick 20,000 では何 tick にも分かれる
            assertTrue(h.scheduler.now - startTick > 5, "${h.scheduler.now - startTick} tick で終わった")
        }
    }

    @Test
    fun `終わらないループも燃料で区切られ、止められる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 10_000)
            m.install("/bin/fuel", resource("fuel.wasm"))
            val drivers = ProcessDriverFactory {
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    ctx.instance.call("forever")
                    StepResult.Exit(0)
                }
            }
            val pid = m.exec("/bin/fuel", drivers)
            h.awaitFuelWait(m, pid, "燃料切れで待たない")
            h.ticks(20)
            assertFalse(m.stateOf(pid).isTerminal)
            // 手持ちを使い切っては待つので、マシンの燃料は 1 tick の配分を超えない
            assertTrue(m.lock.withLockValue { m.kernel.fuel } <= 10_000)
            m.stop()
            assertTrue(m.awaitTermination(10_000), "止まらない")
        }
    }

    @Test
    fun `Luaのpcall（try_table）を通るループも燃料で区切られる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 50_000)
            m.install("/bin/lua", resource("lua.wasm"))
            val output = m.outputStream()
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            val script = "local n = 0 for i = 1, 3000 do if not pcall(error, 'x') then n = n + 1 end end print(n)"
            val startTick = h.scheduler.now
            val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", script), stdio = stdio))
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals("3000\n", m.readOutput(output))
            // 1 tick 50,000 では収まらないので、燃料切れで何度も中断して再開している
            assertTrue(h.scheduler.now - startTick > 5, "${h.scheduler.now - startTick} tick で終わった")

            // 終わらない pcall のループ
            val forever = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", "while true do pcall(error, 'x') end"), stdio = stdio))
            h.awaitFuelWait(m, forever, "燃料切れで待たない")
            h.ticks(10)
            assertFalse(m.stateOf(forever).isTerminal)
            m.stop()
            assertTrue(m.awaitTermination(10_000), "止まらない")
        }
    }

    @Test
    fun `計測の名前をすでに使っているWASMはNOEXEC`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine()
            m.install("/bin/fake", TestModules.FUEL)
            val e = assertThrows<FsException> { m.exec("/bin/fake", KernelDrivers.DEFAULT) }
            assertEquals(Errno.NOEXEC, e.errno)
        }
    }
}
