package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
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

/** 同梱の Lua が、RAM の上限でトラップでなく `not enough memory` のエラーになる（#409）ことを、カーネルを通して確かめる */
class LuaMemoryLimitTest {
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

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val pid: Int)

    /** `lua.wasm` を `/bin/lua` に置き、RAM [ramBytes] のマシンで `lua -e <script>` を動かして、終わるまで待つ */
    private fun runLua(script: String, ramBytes: Long): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = ramBytes)
        run {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がない" }.use { it.readBytes() }
            val f = m.vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
        }
        val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", script)))
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
        // コンソールの行は、プロセスが終わるときに書く。書き出しを待つ
        h.scheduler.tick()
        Result(host.text(), state, exitCode, pid)
    }

    @ParameterizedTest
    @ValueSource(longs = [192, 512])
    fun `大きなテーブルを作るとnot enough memoryのエラーになり、pcallで受けられる`(ramKib: Long) = timeout(limit) {
        // エラーを受けた後は、新しい確保をせずに（作ったテーブルを捨てて GC してから）結果を書いて終わる
        val script = """
            local t = {}
            local ok, err = pcall(function() for i = 1, 1e7 do t[i] = ('x'):rep(64) .. i end end)
            t = nil
            collectgarbage()
            io.write('caught ', tostring(not ok), ' ', tostring(err), '\n')
            os.exit(ok and 3 or 0)
        """.trimIndent()
        val r = runLua(script, ramKib * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
        assertTrue("caught true not enough memory\n" in r.console, r.console)
    }

    @ParameterizedTest
    @ValueSource(longs = [192, 512])
    fun `受けなければLuaのエラーで終わり、メモリ不足が原因だったと表示する`(ramKib: Long) = timeout(limit) {
        val r = runLua("local t = {} for i = 1, 1e7 do t[i] = ('x'):rep(64) .. i end", ramKib * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(1, r.exitCode, r.console)
        assertTrue("not enough memory" in r.console, r.console)
        assertTrue("kibi: lua (pid ${r.pid}) exited with code 1 (kibi:memory commit was refused: out of memory, RAM limit $ramKib KiB)\n" in r.console, r.console)
    }

    @ParameterizedTest
    @MethodSource("largeNearLimit")
    fun `上限の近くで大きな確保をしてもトラップにならず、not enough memoryのエラーになる`(mib: Int, fill: Int) = timeout(limit) {
        // 1 KiB の文字列を fill 個持って残りを減らし（RAM 512 KiB では約 130 個で尽きる）、アドレス空間（64 MiB）に収まる
        // mib MiB の string.rep を pcall で受ける。確保は memory.grow を通り、端の区画と区画の表（#753。1 MiB なら 32 ページ分の 4 KiB）が
        // keep なしで数えられて、残りが一度に落ちる。fill を細かく振って、grow の後の残りが「MIN + 表」の少し上・下になる場合を含める。
        // その後も小さな確保と GC を続け、空きリストに書く free（kibi_wasi.c の free_node）でトラップにならないことも見る。
        // 残りが尽きると、pcall の外の確保も not enough memory になりうる（Lua のエラーで終わる。ADR 0094）。どちらでも CRASHED にはしない
        val script = """
            local t = {}
            pcall(function() for i = 1, $fill do t[i] = ('x'):rep(1000) .. i end end)
            local ok, err = pcall(function() return ('y'):rep($mib * 1024 * 1024) end)
            pcall(function() local u = {} for i = 1, 200 do u[i] = ('z'):rep(100) .. i end end)
            t = nil
            collectgarbage()
            io.write('caught ', tostring(not ok), ' ', tostring(err), '\n')
            os.exit(ok and 3 or 0)
        """.trimIndent()
        val r = runLua(script, 512 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        when (r.exitCode) {
            0 -> assertTrue("caught true not enough memory\n" in r.console, r.console)
            1 -> assertTrue("lua: not enough memory\n" in r.console, r.console)
            else -> throw AssertionError("終了コードが ${r.exitCode}: ${r.console}")
        }
    }

    companion object {
        /** 確保する MiB と、先に持つ 1 KiB の文字列の数の組。数は、grow の後の残りが線の前後を通るように細かく振る */
        @JvmStatic
        fun largeNearLimit(): List<Arguments> =
            listOf(1, 4, 16).flatMap { mib -> (80..110 step 2).map { fill -> Arguments.of(mib, fill) } }
    }
}
