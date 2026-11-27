package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import pro.ikili.kibicomputers.core.kernel.time.ManualTimerService
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 同梱の Lua が、RAM の上限の近くで「区画が変わる縮める realloc」をしても CRASHED にならない（#784）ことを確かめる。
 *
 * Zig の libc の malloc（BrkAllocator）の realloc は、縮めでも区画の大きさ（2 のべき乗）が変わると、新しい区画を取って写す。
 * 新しい区画への写しと頭は、アロケーターの包み（kibi_wasi.c）が数えないまま書くと、上限の近くでトラップになる。
 * Lua が os.sleep で待つ間に、テストの側で RAM の残りをちょうど決まった値にしてから、縮めを起こす。
 * os.sleep の時計は手で進める（#992。予約を済ませてから期限まで進めるので、実時間の 1 秒を待たない）
 */
class LuaShrinkReallocTest {
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

    private data class Squeezed(val state: ProcessState, val console: String, val refusals: Int)

    /**
     * RAM [ramBytes] のマシンで `lua -e <script>` を動かす。スクリプトが初めて os.sleep で待ったときに、RAM の残りがちょうど
     * [remaining] バイトになるように予約し、終わるまで待つ。終わったときの状態とコンソールと、予約してから終わるまでに RAM の勘定が
     * 確保を断った回数（kibi_wasi.c の counted_alloc が失敗した回数の目安）を返す。
     *
     * 時計は手で進める（#992）。マシンの時計とスケジューラのタイマーを同じ [ManualClock] で組にし（片方だけだと、Lua が読む時刻と
     * タイマーの期限が食い違う）、予約と断りの数を読んだ後に、os.sleep の期限ちょうどまで進めて起こす
     */
    private fun runSqueezed(script: String, ramBytes: Long, remaining: Long): Squeezed {
        val timers = ManualTimerService(ManualClock())
        return Harness(execRuntime = EndiveRuntime(), timers = timers).use { h -> runSqueezed(h, timers, script, ramBytes, remaining) }
    }

    private fun runSqueezed(h: Harness, timers: ManualTimerService, script: String, ramBytes: Long, remaining: Long): Squeezed {
        val host = ConsoleHost()
        val env = MachineEnvironment.builder().clock(timers.clock).build()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = ramBytes, environment = env)
        run {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がない" }.use { it.readBytes() }
            val f = m.vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
        }
        val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", script)))
        // os.sleep(1) の待ち（予定が入り、待機点に着いた）を見る。ここでは時計を進めない
        eventually(60_000, "待たない: ${m.stateOf(pid)} ${host.text()}") {
            h.scheduler.tick()
            Thread.sleep(1)
            m.stateOf(pid).isTerminal || (m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint && timers.pending > 0)
        }
        val rt = requireNotNull(m.runtime(pid)) { "プロセスの状態がない" }
        val account = m.memoryAccount
        val held = account.limitBytes - account.usedBytes - remaining
        assertTrue(held >= 0 && account.tryReserveBytes(held), "残りを $remaining にできない: ${account.usedBytes} ${m.stateOf(pid)} ${host.text()}")
        val refusalsBefore = m.lock.withLockValue { rt.memory.refusalCount }
        // 予約の後に、os.sleep の期限ちょうどまで時計を進めて起こす（m.lock の外から。期限の仕事が中でロックを取る）
        timers.advanceToNextDue()
        eventually(60_000, "終わらない: ${host.text()}") {
            h.scheduler.tick()
            Thread.sleep(1)
            m.stateOf(pid).isTerminal
        }
        val state = m.stateOf(pid)
        val refusals = m.lock.withLockValue { rt.memory.refusalCount } - refusalsBefore
        account.releaseBytes(held)
        eventually(60_000, "RAM が戻らない: ${account.usedBytes}") {
            h.scheduler.tick()
            account.usedBytes == 0L
        }
        h.scheduler.tick()
        return Squeezed(state, host.text(), refusals)
    }

    @Test
    fun `包みが写した区画の計算は、実際のmallocの区画とreallocがその場で済むかに合う`() = timeout(limit) {
        // kibi_brk.h の kibi_brk_slot_bytes を、Zig の libc の malloc に当てる（brk_slots_wasi.c）。Zig を上げて合わなくなったら落ちる
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 8 * 1024 * kib)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/brk-slots-wasi.wasm")) { "brk-slots-wasi.wasm がない" }.use { it.readBytes() }
            val f = m.vfs.open(VfsPath.parse("/bin/brk"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
            val pid = m.exec("/bin/brk", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("brk")))
            eventually(60_000, "終わらない: ${host.text()}") {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
            val state = m.stateOf(pid)
            h.scheduler.tick()
            assertEquals(ProcessState.EXITED, state, host.text())
            assertEquals(0, exitCode, host.text())
        }
    }

    @ParameterizedTest(name = "{0} 残り {1} KiB")
    @MethodSource("cases")
    fun `上限の近くで区画が変わる縮めをしてもトラップにならない`(kind: String, remainingKib: Long) = timeout(limit) {
        val (state, console) = runSqueezed(scripts.getValue(kind), 1024 * kib, remainingKib * kib)
        // 縮めが通っても、新しい区画を数えられずに元の所のまま返っても、Lua として最後まで動く。
        // 残りが少ないと、縮めの前後の別の確保（ハッシュの部分、文字列）が not enough memory になりうる（pcall の外なら Lua のエラーで終わる）
        assertEquals(ProcessState.EXITED, state, console)
        assertTrue("done\n" in console || "lua: not enough memory\n" in console, console)
    }

    @ParameterizedTest(name = "{0} 残り {1} KiB")
    @MethodSource("retreatCases")
    fun `新しい区画を数えられない縮めは、NULLではなく元のブロックを返して続けられる`(kind: String, remainingKib: Long) = timeout(limit) {
        // 縮めを pcall で包まない。kibi_wasi.c の「if (result == NULL) return ptr;」が NULL を返すようになると、
        // luaH_resize の配列の縮め（luaM_saferealloc_）が not enough memory を投げ、pcall の外なので "done" が出ずに終わる
        val r = runSqueezed(strictArrayScript(kind), 1024 * kib, remainingKib * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals("ready\ndone\n", r.console)
        // 残りが縮めの新しい区画（kind の半分の配列）と keep に足りないので、縮めの counted_alloc が断られて、退路を通った。
        // 通らなかったなら（区画を数え済みの穴に置けた、など）この試験は何も確かめていない
        assertTrue(r.refusals >= 1, "縮めで確保を断っていない（退路を通っていない）: ${r.refusals}")
    }

    private val scripts: Map<String, String> = mapOf(
        // 配列の部分を n から n/2 に縮める（rehash。luaH_resize の配列の縮め）。n = 1024 は 32 KiB → 16 KiB の小さい区画、
        // 4096 は 2 ページ → 1 ページ、16384 は 8 ページ → 4 ページの大きな区画に移る
        "array1024" to arrayScript(1024),
        "array4096" to arrayScript(4096),
        "array16384" to arrayScript(16384),
        // 深い再帰で伸ばした Lua のスタックを、GC が縮める（luaD_shrinkstack）
        "stack" to """
            local function f(n) if n > 0 then return 1 + f(n - 1) end return 0 end
            f(3000)
            print("ready")
            io.stdout:flush()
            os.sleep(1)
            pcall(collectgarbage)
            pcall(collectgarbage)
            io.stdout:write("done\n")
        """.trimIndent(),
        // パーサーが、倍々で伸ばした定数とコードのベクターを、関数を閉じるときに縮める（luaM_shrinkvector）
        "parser" to """
            local parts = {}
            for i = 1, 3000 do parts[i] = i .. ".5" end
            local src = "return {" .. table.concat(parts, ",") .. "}"
            parts = nil
            collectgarbage()
            print("ready")
            io.stdout:flush()
            os.sleep(1)
            pcall(load, src)
            src = nil
            pcall(collectgarbage)
            io.stdout:write("done\n")
        """.trimIndent(),
    )

    /**
     * 配列の部分がちょうど [n] の表を、表の構成子（OP_NEWTABLE が大きさを先に決める）で作る。倍々で伸ばすと、縮めた先の区画が
     * 伸ばす途中で解放した区画（もう数えてある）の使い回しになり、新しい区画への書き込みが起きない
     */
    private fun arrayScript(n: Int): String = """
        local t = load("return {" .. ("0,"):rep($n) .. "}")()
        local shrink = function() t.x = 1 end
        collectgarbage()
        print("ready")
        io.stdout:flush()
        os.sleep(1)
        for i = ${n / 2 + 1}, $n do t[i] = nil end
        pcall(shrink)
        t = nil
        pcall(collectgarbage)
        io.stdout:write("done\n")
    """.trimIndent()

    /** [arrayScript] と同じ縮めを、pcall なしでやる（大きさは kind の末尾の数字） */
    private fun strictArrayScript(kind: String): String {
        val n = kind.removePrefix("array").toInt()
        return """
            local t = load("return {" .. ("0,"):rep($n) .. "}")()
            collectgarbage()
            print("ready")
            io.stdout:flush()
            os.sleep(1)
            for i = ${n / 2 + 1}, $n do t[i] = nil end
            t.x = 1
            t = nil
            collectgarbage()
            io.stdout:write("done\n")
        """.trimIndent()
    }

    companion object {
        /**
         * 縮めの新しい区画（4096 は 1 ページ = 64 KiB）が、残り（keep の 16 KiB を含めて）に収まらない組。
         * 小さい確保（ハッシュの 1 ノードなど）は通り、縮めだけが断られる残りを選ぶ。
         * 残りは 40・48 KiB（64 KiB だと縮めが通る穴があり、退路を壊しても通ってしまうことを、退路を壊して確かめた）。
         * array16384 は入れない: 縮め先の 4 ページの区画が、構成子を読み込む間に解放された数え済みの穴で足りてしまい、断られない（退路を通らない）
         */
        @JvmStatic
        fun retreatCases(): List<Arguments> =
            listOf("array4096").flatMap { kind -> listOf(40L, 48L).map { Arguments.of(kind, it) } }

        /** 縮めの種類と、縮める直前の RAM の残り（KiB）。残りは、スタックを数える線（32 KiB）と MIN（8 KiB）の前後を通るように振る */
        @JvmStatic
        fun cases(): List<Arguments> =
            listOf("array1024", "array4096", "array16384", "stack", "parser").flatMap { kind ->
                listOf(8L, 12, 16, 20, 24, 28, 32, 40, 48, 64).map { Arguments.of(kind, it) }
            }
    }
}
