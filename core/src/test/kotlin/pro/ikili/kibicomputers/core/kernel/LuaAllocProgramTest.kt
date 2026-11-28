package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * Lua のアロケーターの包み（kibi_wasi.c の kibi_lua_realloc）を、Lua を通さずに直接呼んで確かめる（#802。lua_alloc_wasi.c）。
 *
 * Lua から確かめると、どの道を通るかが lua.wasm の静的な変数の配置や、確保の起きる所、緊急の GC のやり直し
 * （断られた確保を GC の後にもう一度呼び、そちらは確保の前の MIN の線で断られる）で変わるため、プログラムが残りを決まった値にしてから 1 回だけ呼ぶ
 */
class LuaAllocProgramTest {
    private val limit = Duration.ofSeconds(90)
    private val kib = 1024L

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    @Test
    fun `memory_growの後の見直しで残りがMINを割って断るときも、メモリ不足だった注記が出る`() = timeout(limit) {
        // 確保の前の線（MIN）とスタックの計数は何も断らずに通り、grow が区画の表と両端（16 KiB）を keep なしで数えた後、
        // 見直しで残りが MIN を割って確保を取り消す道。この道は commit を呼ばないので、note_refusal がなければ注記が出ない
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 512 * kib)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua-alloc-wasi.wasm")) { "lua-alloc-wasi.wasm がない" }.use { it.readBytes() }
            val f = m.vfs.open(VfsPath.parse("/bin/la"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
            val pid = m.exec("/bin/la", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("la", "grow-refuse")))
            eventually(60_000, "終わらない: ${host.text()}") {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
            val state = m.stateOf(pid)
            // コンソールの行は、プロセスが終わるときに書く。書き出しを待つ
            h.scheduler.tick()
            val text = host.text()
            assertEquals(ProcessState.EXITED, state, text)
            assertEquals(1, exitCode, text)
            val line = text.lines().firstOrNull { it.startsWith("before ") } ?: throw AssertionError("結果の行がない: $text")
            val (before, grew, isNull, after) = line.split(' ').mapNotNull { it.toLongOrNull() }
            // 確保の前は MIN 以上で、64 ページの grow が起き、その後の残りが MIN 未満になって断られた
            assertTrue(before in 20 * kib until 24 * kib, text)
            assertEquals(64L, grew, text)
            assertEquals(1L, isNull, text)
            assertTrue(after < 8 * kib, text)
            assertTrue("kibi: la (pid $pid) exited with code 1 (kibi:memory commit was refused: out of memory, RAM limit 512 KiB)\n" in text, text)
        }
    }
}
