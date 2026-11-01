package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 引数なしの `lua` をコンソール（TTY）につないで、対話型の REPL として使う（#96）。実際の Endive で動かす */
class LuaReplTest {
    private val limit = Duration.ofSeconds(60)

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8) }
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Harness.tickUntil(message: String, condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun Machine.installLua() = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = resource("lua.wasm")
        val f = vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun Machine.readOutput(id: Int): String = lock.withLockValue {
        val s = kernel.objects[id] as ByteStream
        val buf = ByteArray(s.available)
        s.read(buf, 0, buf.size, wakeSink)
        String(buf, Charsets.UTF_8)
    }

    /** コンソールの出力が [expected] で終わるまで tick を進める（プロンプトが出るまで待つのに使う） */
    private fun Harness.awaitOutput(host: ConsoleHost, expected: String) =
        tickUntil("出力が「$expected」で終わらない") { host.text().endsWith(expected) }

    @Test
    fun `式の評価・複数行の入力・履歴の呼び戻しができ、Ctrl+Dで終わる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host)
            m.installLua()
            val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua")))
            h.awaitOutput(host, "\r\n> ")
            assertTrue(host.text().startsWith("Lua 5.4"), host.text())

            // 式はそのまま値が出る
            var mark = host.text().length
            m.offerConsoleInput("1+1\r".toByteArray())
            h.awaitOutput(host, "2\r\n> ")
            assertEquals("1+1\r\n2\r\n> ", host.text().substring(mark))

            // 終わっていない文は続きの行（>> ）を待つ
            mark = host.text().length
            m.offerConsoleInput("for i = 1, 2 do\r".toByteArray())
            h.awaitOutput(host, ">> ")
            m.offerConsoleInput("print(i * 10)\r".toByteArray())
            h.awaitOutput(host, ">> ")
            m.offerConsoleInput("end\r".toByteArray())
            h.awaitOutput(host, "20\r\n> ")
            assertEquals("for i = 1, 2 do\r\n>> print(i * 10)\r\n>> end\r\n10\r\n20\r\n> ", host.text().substring(mark))

            // 上の矢印で前の行（end、print、for、1+1 の順）を呼び戻す。4つ前が 1+1
            mark = host.text().length
            m.offerConsoleInput("\u001b[A\u001b[A\u001b[A\u001b[A\r".toByteArray())
            h.awaitOutput(host, "2\r\n> ")
            assertTrue(host.text().substring(mark).endsWith("1+1\r\n2\r\n> "), host.text().substring(mark))

            // エラーは表示して REPL を続ける
            m.offerConsoleInput("error('boom')\r".toByteArray())
            h.awaitOutput(host, "> ")
            h.tickUntil("エラーが出ない") { host.text().contains("boom") }

            // 空の行で Ctrl+D を押すと終わる
            m.offerConsoleInput(byteArrayOf(0x04))
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
        }
    }

    @Test
    fun `標準入力が端末でなければ、引数なしでも標準入力をスクリプトとして読む`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.installLua()
            val (input, output) = m.lock.withLockValue {
                val input = ByteStream(1024).also { m.kernel.objects.add(it) }
                val output = ByteStream(64 * 1024).also { m.kernel.objects.add(it) }
                val script = "print(6 * 7)\n".toByteArray()
                input.write(script, 0, script.size, m.wakeSink)
                input.closeWrite(m.wakeSink)
                input.id to output.id
            }
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua"), stdio = stdio))
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals("42\n", m.readOutput(output))
        }
    }

    /**
     * REPL に [inputs] を1つずつ渡し（それぞれ、プロンプトが出て入力を待つまで進めてから）、最後の入力で終わるまで動かす。
     * コンソールの出力・終了コード・動き方を返す。[asyncify] が `false` なら待機点への変換をかけずに `BLOCKING` で動かす
     */
    private fun runRepl(asyncify: Boolean, inputs: List<String>, check: (Harness, Machine, Int) -> Unit = { _, _, _ -> }): Triple<String, Int?, ProcessKind> =
        Harness(execRuntime = EndiveRuntime(), asyncify = asyncify).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host)
            m.installLua()
            val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua")))
            for ((i, input) in inputs.withIndex()) {
                h.tickUntil("入力を待たない（${i}番目の前）: ${host.text()}") { host.text().endsWith("> ") && m.stateOf(pid) == ProcessState.WAITING_EVENT }
                if (i == 0) check(h, m, pid)
                m.offerConsoleInput(input.toByteArray())
                if (i < inputs.lastIndex) {
                    val mark = host.text().length
                    h.tickUntil("入力を読まない") { host.text().length > mark && !(m.stateOf(pid) == ProcessState.WAITING_EVENT && host.text().endsWith(input.trimEnd('\r'))) }
                }
            }
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            val process = m.lock.withLockValue { m.kernel.processes[pid]!! }
            Triple(host.text(), process.exitCode, process.kind)
        }

    @Test
    fun `REPLは待機点型で動き、待っている間スレッドを持たず、出力がブロッキングと一致する`() = timeout(limit) {
        val inputs = listOf(
            "x = io.read()\rhello\r",
            "print(x, #x)\r",
            "print(pcall(error, 'e1'))\r",
            "error('boom')\r",
            "co = coroutine.wrap(function(a) local b = coroutine.yield(a + 1) return b * 2 end)\r",
            "print(co(1), co(5))\r",
            "local t = {} for i = 1, 50 do t[i] = i * i end print(table.concat(t, ',', 45))\r",
            "os.exit(3)\r",
        )
        val (async, asyncCode, asyncKind) = runRepl(true, inputs) { h, m, pid ->
            // 入力を待っている間: スレッドを持たず、専用スレッドもブロッキング型の枠も使わない。退避した値がある
            eventually(5_000, "スレッドが外れない") { m.rt(pid).thread == null }
            assertTrue(h.scheduler.dedicatedThreads.isEmpty())
            assertEquals(0, h.scheduler.blockingProcesses)
            assertTrue(m.lock.withLockValue { m.kernel.processes[pid]!!.asyncStack!!.size } > 0)
            assertTrue(m.isAtWaitPoint)
            assertTrue(m.rt(pid).program.module.isAsyncified)
        }
        val (blocking, blockingCode, blockingKind) = runRepl(false, inputs)
        assertEquals(ProcessKind.WAIT_POINT, asyncKind)
        assertEquals(ProcessKind.BLOCKING, blockingKind)
        assertEquals(3, asyncCode)
        assertEquals(blocking, async)
        assertEquals(blockingCode, asyncCode)
        assertTrue(async.contains("hello\t5"), async)
        assertTrue(async.contains("false\te1"), async)
        assertTrue(async.contains("boom"), async)
        assertTrue(async.contains("2\t10"), async)
        assertTrue(async.contains("2025,2116,2209,2304,2401,2500"), async)
    }
}
