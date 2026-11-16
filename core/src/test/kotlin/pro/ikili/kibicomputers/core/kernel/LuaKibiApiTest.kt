package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceException
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * Lua 向けの KiBi API（#94。ADR 0094）の peripheral と os の拡張を、実際の Endive と `lua.wasm`、カーネルで確かめる。
 * テスト用のデバイス（種類 `test:dev`）を [ADDRESS] につないで、Lua のスクリプトを動かす
 */
class LuaKibiApiTest {
    private val limit = Duration.ofSeconds(120)

    private companion object {
        const val ADDRESS = "00000000-0000-4000-8000-0000000000a1"
        const val OTHER = "00000000-0000-4000-8000-0000000000a2"
    }

    private val luaBytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がリソースにない" }.use { it.readBytes() }
    }

    /** テスト用のデバイス。直接のメソッドとメインスレッドのメソッドを持つ */
    private class TestDev(override val kind: String = "test:dev") : Device {
        val mainCalls = AtomicInteger()

        /** `big` を呼んだ回数 */
        val bigCalls = AtomicInteger()

        @Volatile
        var context: DeviceContext? = null

        /** テストの側が準備を終えたら `true`（スクリプトが `posted` で待つ） */
        @Volatile
        var posted = false

        override fun onAttached(context: DeviceContext) {
            this.context = context
        }

        override val methods: DeviceMethods = DeviceMethods.builder()
            .direct("echo") { _, args -> Array(args.size()) { args.get(it) } }
            .direct("types") { _, args -> Array(args.size()) { DeviceValue.of(args.get(it).type.name.lowercase()) } }
            .direct("get-size") { _, _ -> arrayOf(DeviceValue.of(80L), DeviceValue.of(25L)) }
            .direct("needs-int") { _, args -> arrayOf(DeviceValue.of(args.getLong(0))) }
            .direct("fail") { _, _ -> throw DeviceException("boom") }
            .direct("emit") { _, args ->
                context!!.postEvent("ping", DeviceValue.of(args.getLong(0)))
                emptyArray()
            }
            .direct("emit-as") { _, args ->
                context!!.postEvent(args.getText(0), *Array(args.size() - 1) { args.get(it + 1) })
                emptyArray()
            }
            .direct("posted") { _, _ -> arrayOf(DeviceValue.of(posted)) }
            .direct("big") { _, args ->
                bigCalls.incrementAndGet()
                arrayOf(DeviceValue.of("b".repeat(args.getLong(0).toInt())))
            }
            .mainThread("main-echo") { _, args ->
                mainCalls.incrementAndGet()
                Array(args.size()) { args.get(it) }
            }
            .build()
    }

    private inner class Run(
        script: String,
        val h: Harness = Harness(execRuntime = EndiveRuntime()),
        memoryBytes: Long? = null,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ) : AutoCloseable {
        val m: Machine = h.machine(fuelPerTick = 10_000_000, memoryBytes = memoryBytes, environment = environment)
        val dev = TestDev()
        private val output: Int
        private val text = StringBuilder()
        val pid: Int

        init {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            m.vfs.createDirectory(VfsPath.parse("/home"))
            write("/bin/lua", luaBytes)
            write("/home/t.lua", ("#!/bin/lua\n" + script).toByteArray(Charsets.UTF_8))
            assertTrue(m.devices.attach(ADDRESS, dev))
            output = m.lock.withLockValue { ByteStream(256 * 1024).also { m.kernel.objects.add(it) }.id }
            val out = StdioSource.Object(output, HandleRights.WRITE)
            pid = m.exec("/home/t.lua", KernelDrivers.DEFAULT, SpawnOptions(stdio = StdioSpec(StdioSource.Closed, out, out), fds = SpawnOptions.ROOT_PREOPEN))
        }

        private fun write(path: String, bytes: ByteArray) {
            val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
        }

        fun drain(): String = m.lock.withLockValue {
            val s = m.kernel.objects[output] as ByteStream
            val buf = ByteArray(s.available)
            if (buf.isNotEmpty()) s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes
            text.append(String(buf, Charsets.UTF_8))
            text.toString()
        }

        fun waitUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
            h.scheduler.tick()
            Thread.sleep(1)
            drain()
            condition()
        }

        fun waitFor(line: String) = waitUntil("「$line」が出ない: $text") { text.contains(line) }

        /**
         * Lua が待っている間に、マシンの RAM の残りがちょうど [remaining] バイトになるように、テストの側で予約する（#729）。
         * 予約したバイト数を返す（[release] で戻す）。待っている間は Lua が書かないので、起きた後の残りが決まる
         */
        fun squeeze(remaining: Long): Long {
            waitUntil("待たない: $text") { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            val account = m.memoryAccount
            val bytes = account.limitBytes - account.usedBytes - remaining
            assertTrue(bytes >= 0 && account.tryReserveBytes(bytes), "残りを $remaining にできない: ${account.usedBytes}")
            return bytes
        }

        fun release(bytes: Long) = m.memoryAccount.releaseBytes(bytes)

        fun finish(expected: ProcessState = ProcessState.EXITED): String {
            waitUntil("終わらない: $text") { m.stateOf(pid).isTerminal }
            val out = drain()
            assertEquals(expected, m.stateOf(pid), out)
            return out
        }

        override fun close() = h.close()
    }

    private fun run(script: String): String = Run(script).use { it.finish() }

    @Test
    fun `peripheralで列挙して種類とメソッドを読み、callで呼べる`() = timeout(limit) {
        val out = run(
            """
            local names = peripheral.getNames()
            print(#names, names[1])
            print(peripheral.isPresent("$ADDRESS"), peripheral.isPresent("$OTHER"), peripheral.isPresent("bogus"))
            print(peripheral.getType("$ADDRESS"), peripheral.getType("$OTHER"))
            print(peripheral.hasType("$ADDRESS", "test:dev"), peripheral.hasType("$ADDRESS", "dev"), peripheral.hasType("$ADDRESS", "x"), peripheral.hasType("$OTHER", "dev"))
            local ms = peripheral.getMethods("$ADDRESS")
            table.sort(ms)
            print(table.concat(ms, ","))
            print(peripheral.call("$ADDRESS", "echo", 1, "a", true))
            print(peripheral.call("$ADDRESS", "getSize"))
            print(peripheral.call("${ADDRESS.uppercase()}", "get-size"))
            print(peripheral.call("$ADDRESS", "main-echo", 7))
            print(package.loaded.peripheral == peripheral)
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "1\t$ADDRESS",
                "true\tfalse\tfalse",
                "test:dev\tnil",
                "true\ttrue\tfalse\tnil",
                "big,echo,emit,emit-as,fail,get-size,main-echo,needs-int,posted,types",
                "1\ta\ttrue",
                "80\t25",
                "80\t25",
                "7",
                "true",
            ).joinToString("\n", postfix = "\n"),
            out,
        )
    }

    @Test
    fun `wrapは別名付きの関数のテーブルを返し、findは種類で探す`() = timeout(limit) {
        val out = run(
            """
            local p = peripheral.wrap("$ADDRESS")
            print(p.getSize())
            print(p["get-size"]())
            print(p.mainEcho("x"))
            print(peripheral.getName(p), peripheral.getType(p), peripheral.hasType(p, "dev"))
            print(peripheral.wrap("$OTHER"))
            print(select("#", peripheral.find("dev")), select("#", peripheral.find("test:dev")), select("#", peripheral.find("nope")))
            print(select("#", peripheral.find("dev", function(name, w) return name ~= "$ADDRESS" end)))
            local f = peripheral.find("dev")
            print(peripheral.getName(f))
            print(tostring(p):match("^peripheral") ~= nil)
            """.trimIndent(),
        )
        assertEquals(
            listOf("80\t25", "80\t25", "x", "$ADDRESS\ttest:dev\ttrue", "nil", "1\t1\t0", "0", ADDRESS, "true").joinToString("\n", postfix = "\n"),
            out,
        )
    }

    @Test
    fun `値の写し方の境界`() = timeout(limit) {
        val out = run(
            """
            print(peripheral.call("$ADDRESS", "types", 1, 1.5, 10/2, -0.0, 0/0, math.huge, 2^63, true, "s", nil, peripheral.bytes("\xff")))
            print(peripheral.call("$ADDRESS", "needs-int", 10/2))
            local v = peripheral.call("$ADDRESS", "echo", peripheral.bytes("\xff\x00"))
            print(#v, v:byte(1), v:byte(2))
            print(math.type(peripheral.call("$ADDRESS", "echo", 3)), math.type(peripheral.call("$ADDRESS", "echo", 2.5)))
            print(select("#", peripheral.call("$ADDRESS", "echo", nil, 1, nil)))
            print(pcall(peripheral.call, "$ADDRESS", "echo", "\xff"))
            print(pcall(peripheral.call, "$ADDRESS", "echo", {}))
            print(pcall(peripheral.call, "$ADDRESS", "echo", table.unpack({}, 1, 33)))
            print(select("#", peripheral.call("$ADDRESS", "echo", table.unpack({}, 1, 32))))
            print(pcall(peripheral.call, "$ADDRESS", "echo", string.rep("a", 65537)))
            print(#peripheral.call("$ADDRESS", "echo", string.rep("a", 65536)))
            """.trimIndent(),
        )
        val lines = out.lines()
        assertEquals("integer\tfloat\tinteger\tfloat\tfloat\tfloat\tfloat\tboolean\ttext\tnone\tbytes", lines[0], out)
        assertEquals("5", lines[1], out)
        assertEquals("2\t255\t0", lines[2], out)
        assertEquals("integer\tfloat", lines[3], out)
        assertEquals("3", lines[4], out)
        assertTrue(lines[5].startsWith("false\t") && "bad argument #3" in lines[5] && "UTF-8" in lines[5], out)
        assertTrue(lines[6].startsWith("false\t") && "bad argument #3" in lines[6] && "table" in lines[6], out)
        assertTrue(lines[7].startsWith("false\t") && "too many arguments" in lines[7], out)
        assertEquals("32", lines[8], out)
        assertTrue(lines[9].startsWith("false\t") && "too large" in lines[9], out)
        assertEquals("65536", lines[10], out)
    }

    @Test
    fun `呼び出しの誤りはLuaのエラーになる`() = timeout(limit) {
        val out = run(
            """
            print(pcall(peripheral.call, "$OTHER", "echo"))
            print(pcall(peripheral.call, "$ADDRESS", "nope"))
            print(pcall(peripheral.call, "$ADDRESS", "needs-int", "x"))
            print(pcall(peripheral.call, "$ADDRESS", "fail"))
            print(pcall(peripheral.call, "$ADDRESS", string.rep("a", 65)))
            """.trimIndent(),
        )
        val lines = out.lines()
        assertTrue(lines[0].startsWith("false\t") && lines[0].endsWith("No such peripheral"), out)
        assertTrue(lines[1].startsWith("false\t") && lines[1].endsWith("No such method nope"), out)
        assertTrue(lines[2].startsWith("false\t") && lines[2] != "false\t", out)
        assertTrue(lines[3].startsWith("false\t") && lines[3].endsWith("boom"), out)
        assertTrue(lines[4].startsWith("false\t") && "too long" in lines[4], out)
    }

    @Test
    fun `wrapした後に同じaddressへ別の種類がつながったら呼べない`() = timeout(limit) {
        Run(
            """
            local p = peripheral.wrap("$ADDRESS")
            print("wrapped")
            io.stdout:flush()
            os.pullEvent("peripheral")
            print(pcall(p.echo, 1))
            print(peripheral.call("$ADDRESS", "echo", 2))
            """.trimIndent(),
        ).use { r ->
            r.waitFor("wrapped\n")
            r.m.devices.detach(ADDRESS)
            // キューは pullEvent が開いた後でなければ受け取れないので、待ちに入るまで少し進めてからつなぐ
            repeat(20) { r.h.scheduler.tick(); Thread.sleep(2) }
            r.m.devices.attach(ADDRESS, TestDev("test:other").let { other -> OtherKind(other) })
            val out = r.finish()
            val lines = out.lines()
            assertTrue(lines[1].startsWith("false\t") && lines[1].endsWith("No such peripheral"), out)
            assertEquals("2", lines[2], out)
        }
    }

    /** 種類だけ違う、echo を持つデバイス */
    private class OtherKind(private val inner: TestDev) : Device {
        override val kind: String = "test:other"
        override val methods: DeviceMethods = inner.methods
    }

    @Test
    fun `接続と切断とデバイスのイベントをpullEventで受ける`() = timeout(limit) {
        Run(
            """
            os.queueEvent("ready")
            print(os.pullEvent("ready"))
            io.stdout:flush()
            print(os.pullEvent("peripheral"))
            print(os.pullEvent("peripheral_detach"))
            peripheral.call("$ADDRESS", "emit", 5)
            print(os.pullEvent("ping"))
            """.trimIndent(),
        ).use { r ->
            r.waitFor("ready\n")
            repeat(20) { r.h.scheduler.tick(); Thread.sleep(2) }
            r.m.devices.attach(OTHER, TestDev())
            r.waitUntil("接続のイベントが届かない") { r.drain().contains("peripheral\t$OTHER\n") }
            r.m.devices.detach(OTHER)
            val out = r.finish()
            assertEquals("ready\nperipheral\t$OTHER\nperipheral_detach\t$OTHER\nping\t$ADDRESS\t5\n", out)
        }
    }

    @Test
    fun `カーネルのイベントの写し方とあふれて捨てた数`() = timeout(limit) {
        // キュー（64 件）に、コンソールの tty-resize、デバイスが出す同じ名前のイベント、source のあるイベントを入れ、
        // 残りを ping で埋めてあふれさせる。ping 以外を届いた順に書き、ping は数だけ書く
        Run(
            """
            os.queueEvent("ready")
            os.pullEvent("ready")
            print("open")
            io.stdout:flush()
            repeat os.sleep(0.05) until peripheral.call("$ADDRESS", "posted")
            peripheral.call("$ADDRESS", "emit-as", "tty-resize", 1, 2)
            peripheral.call("$ADDRESS", "emit-as", "device-attached", "x")
            peripheral.call("$ADDRESS", "emit-as", "multi", 1, "a", true)
            for i = 1, 70 do peripheral.call("$ADDRESS", "emit", i) end
            os.queueEvent("end")
            local pings = 0
            while true do
                local ev = table.pack(os.pullEvent())
                if ev[1] == "end" then break end
                if ev[1] == "ping" then
                    pings = pings + 1
                else
                    for i = 1, ev.n do ev[i] = tostring(ev[i]) end
                    print(table.concat(ev, "\t", 1, ev.n))
                end
            end
            print("pings", pings)
            """.trimIndent(),
        ).use { r ->
            r.waitFor("open\n")
            r.m.resizeConsole(100, 30)
            r.dev.posted = true
            val out = r.finish()
            assertEquals(
                listOf(
                    // コンソールの tty-resize は値なしの term_resize
                    "term_resize",
                    // デバイスが出した同じ名前のイベントは写さず、source（address）を値の前に入れる
                    "tty-resize\t$ADDRESS\t1\t2",
                    "device-attached\t$ADDRESS\tx",
                    "multi\t$ADDRESS\t1\ta\ttrue",
                    // 64 件のうち 4 件は上のもの、残り 60 件が ping。あふれた 10 件は events_dropped で知らせる
                    "events_dropped\t10",
                    "pings\t60",
                ).joinToString("\n", postfix = "\n"),
                out.removePrefix("open\n"),
            )
        }
    }

    @Test
    fun `os_epochとos_dayの既定のingameはworld-ticksから`() = timeout(limit) {
        val ticks = 24000L * 3 + 5
        val clock = object : MachineClock {
            override fun realtimeNanos(): Long = MachineClock.SYSTEM.realtimeNanos()
            override fun monotonicNanos(): Long = MachineClock.SYSTEM.monotonicNanos()
            override fun worldTicks(): Long = ticks
        }
        val env = MachineEnvironment.builder().clock(clock).build()
        val out = Run("print(os.epoch(), os.day(), os.epoch('ingame'), os.day('ingame'))", environment = env).use { it.finish() }
        assertEquals("${ticks * 3600}\t3\t${ticks * 3600}\t3\n", out)
    }

    @Test
    fun `タイマーとqueueEventの順とフィルター`() = timeout(limit) {
        val out = run(
            """
            local a = os.startTimer(0.2)
            local b = os.startTimer(0.1)
            local c = os.startTimer(0.15)
            os.cancelTimer(c)
            os.queueEvent("x", 1, {k = 2})
            os.queueEvent("y")
            local e, v, t = os.pullEvent()
            print(e, v, t.k)
            print(os.pullEvent("timer") == "timer")
            local _, id = os.pullEvent("timer")
            print(id == a)
            os.queueEvent("z")
            print(os.pullEventRaw())
            local t0 = os.epoch("utc")
            os.sleep(0)
            print(os.epoch("utc") - t0 >= 40)
            local d = os.startTimer(0)
            os.queueEvent("after")
            os.sleep(0.2)
            print(os.pullEvent())
            print(os.pullEvent())
            print(math.type(os.epoch()), math.type(os.day()), os.day("utc") > 19000, os.epoch("local") > 1600000000000)
            """.trimIndent(),
        )
        val lines = out.lines()
        assertEquals("x\t1\t2", lines[0], out)
        // y は "timer" のフィルターで捨てられ、b が先に鳴る
        assertEquals("true", lines[1], out)
        assertEquals("true", lines[2], out)
        assertEquals("z", lines[3], out)
        assertEquals("true", lines[4], out)
        // sleep はイベントを捨てない
        assertEquals("after", lines[5], out)
        assertTrue(lines[6].startsWith("timer\t"), out)
        assertEquals("integer\tinteger\ttrue\ttrue", lines[7], out)
    }

    @Test
    fun `pcallとgsubのコールバックの中で待て、コルーチンの中ではyieldする`() = timeout(limit) {
        val out = run(
            """
            print(pcall(function() os.sleep(0.05); return peripheral.call("$ADDRESS", "main-echo", "p") end))
            local co = coroutine.wrap(function()
                local _, v = os.pullEvent("c")
                coroutine.yield(v + peripheral.call("$ADDRESS", "main-echo", 1))
            end)
            print(co())
            print(co("c", 1))
            print((string.gsub("ab", ".", function(ch) os.sleep(0.05); return peripheral.call("$ADDRESS", "main-echo", ch:upper()) end)))
            """.trimIndent(),
        )
        assertEquals("true\tp\nc\n2\nAB\n", out)
    }

    @Test
    fun `コルーチンの中のpullEventRawはfilterをyieldし、resumeの値をそのまま返す`() = timeout(limit) {
        val out = run(
            """
            local co = coroutine.create(function(...)
                print("raw", select("#", os.pullEventRaw()))
                print("raw", os.pullEventRaw("x"))
                print("ev", select("#", os.pullEvent()))
                local t = table.pack(os.pullEvent("y"))
                print("ev", t.n, t[1], t[2], t[3], t[4])
                print("raw", os.pullEventRaw("z"))
                os.pullEvent()
                print("not reached")
            end)
            print(coroutine.resume(co))
            print(coroutine.resume(co))
            print(coroutine.resume(co, "anything", 1, 2))
            print(coroutine.resume(co))
            print(coroutine.resume(co, "y", nil, 3, nil))
            print(coroutine.resume(co, "terminate"))
            print(coroutine.resume(co, "terminate"))
            print(coroutine.status(co))
            -- yield できない文脈ではエラーになる（CC と同じ）
            local co2 = coroutine.create(function()
                return pcall(string.gsub, "a", ".", function() os.pullEvent() end)
            end)
            local ok, inner, msg = coroutine.resume(co2)
            print(ok, inner, msg:find("yield across") ~= nil)
            -- sleep は yield できない文脈ではその場で待ち、イベントを捨てない
            os.queueEvent("kept")
            local co3 = coroutine.create(function()
                return (string.gsub("a", ".", function() os.sleep(0.05); return "b" end))
            end)
            print(coroutine.resume(co3))
            print(os.pullEvent())
            """.trimIndent(),
        )
        assertEquals(
            "true\tnil\n" +
                "raw\t0\ntrue\tx\n" +
                "raw\tanything\t1\t2\ntrue\tnil\n" +
                "ev\t0\ntrue\ty\n" +
                "ev\t4\ty\tnil\t3\tnil\ntrue\tz\n" +
                "raw\tterminate\ntrue\tnil\n" +
                "false\tTerminated\n" +
                "dead\n" +
                "true\tfalse\ttrue\n" +
                "true\tb\n" +
                "kept\n",
            out,
        )
    }

    @Test
    fun `メインスレッドのpullEventのフィルターはterminateを素通りさせる`() = timeout(limit) {
        val out = run(
            """
            os.queueEvent("other")
            os.queueEvent("terminate")
            print(os.pullEventRaw("x"))
            os.queueEvent("terminate")
            print(pcall(os.pullEvent, "x"))
            """.trimIndent(),
        )
        assertEquals("terminate\nfalse\tTerminated\n", out)
    }

    @Test
    fun `コルーチンの中のsleepはtimerをyieldし、idが合うまで待つ`() = timeout(limit) {
        val out = run(
            """
            local co = coroutine.create(function() os.sleep(1); return "woke" end)
            local ok, f = coroutine.resume(co)
            print(ok, f)
            print(coroutine.resume(co, "timer", 12345))
            print(coroutine.resume(co, "other"))
            local _, id = os.pullEvent("timer")
            print(coroutine.resume(co, "timer", id))
            local co2 = coroutine.create(function() os.sleep(100) end)
            coroutine.resume(co2)
            print(coroutine.resume(co2, "terminate"))
            """.trimIndent(),
        )
        assertEquals("true\ttimer\ntrue\ttimer\ntrue\ttimer\ntrue\twoke\nfalse\tTerminated\n", out)
    }

    @Test
    fun `コルーチンの中のsleepはtimerのidがfloatで来ても合う`() = timeout(limit) {
        val out = run(
            """
            local co = coroutine.create(function() os.sleep(1); return "woke" end)
            coroutine.resume(co)
            local _, id = os.pullEvent("timer")
            print(math.type(id), math.type(id + 0.0))
            print(coroutine.resume(co, "timer", id + 0.0))
            """.trimIndent(),
        )
        assertEquals("integer\tfloat\ntrue\twoke\n", out)
    }

    @Test
    fun `parallelは関数を並べて動かし、イベントをフィルターごとに配る`() = timeout(limit) {
        val out = run(
            """
            -- 2つの sleep は並んで進む。続けて動かすなら1つ目が先に終わって "ba" になるので、
            -- 短いほうが先に終わる "ab" で確かめる（壁時計の上限は CI の遅れで不安定なので見ない）
            local t0 = os.epoch("utc")
            local order = {}
            parallel.waitForAll(
                function() os.sleep(0.4); order[#order + 1] = "b" end,
                function() os.sleep(0.05); order[#order + 1] = "a" end
            )
            local dt = os.epoch("utc") - t0
            print(table.concat(order), dt >= 350)
            -- waitForAny は最初に終わった関数の番号を返す
            print(parallel.waitForAny(
                function() os.pullEvent("never") end,
                function() os.sleep(0.05) end
            ))
            print(parallel.waitForAny(function() return 1 end, function() return 2 end))
            print(select("#", parallel.waitForAll(function() return 1 end)), parallel.waitForAny())
            -- フィルターと queueEvent: ほかのコルーチンへ届く
            local got = {}
            parallel.waitForAll(
                function()
                    local _, v = os.pullEvent("ping")
                    got[#got + 1] = "ping" .. v
                    os.queueEvent("pong", v + 1)
                end,
                function()
                    os.queueEvent("noise")
                    os.queueEvent("ping", 1)
                    local _, v = os.pullEvent("pong")
                    got[#got + 1] = "pong" .. v
                end
            )
            print(table.concat(got, ","))
            -- 入れ子
            local seen = {}
            parallel.waitForAll(
                function()
                    parallel.waitForAll(
                        function() local _, v = os.pullEvent("n"); seen[#seen + 1] = "in1:" .. v end,
                        function() os.sleep(0.05); seen[#seen + 1] = "in2" end
                    )
                    seen[#seen + 1] = "inner done"
                end,
                function() os.sleep(0.1); os.queueEvent("n", 7) end
            )
            print(table.concat(seen, ","))
            -- エラーは伝わる（位置は関数の中のまま）
            print(pcall(parallel.waitForAll, function() os.sleep(0.05) end, function() error("boom") end))
            print(pcall(parallel.waitForAny, function() error({code = 1}) end))
            print(select(2, pcall(parallel.waitForAny, function() end, 1)))
            -- terminate はフィルターに関係なく届き、pullEvent は Terminated になる
            print(pcall(parallel.waitForAll, function() os.pullEvent("never") end, function() os.queueEvent("terminate") end))
            -- 上書きした os.pullEventRaw は parallel の中の待ちに使われない
            local orig = os.pullEventRaw
            os.pullEventRaw = function() error("overridden") end
            parallel.waitForAll(function() os.sleep(0.05) end)
            os.pullEventRaw = orig
            print(package.loaded.parallel == parallel, "done")
            """.trimIndent(),
        )
        val lines = out.lines()
        assertEquals("ab\ttrue", lines[0], out)
        assertEquals("2", lines[1], out)
        assertEquals("1", lines[2], out)
        assertEquals("0\t0", lines[3], out)
        assertEquals("ping1,pong2", lines[4], out)
        assertEquals("in2,in1:7,inner done", lines[5], out)
        assertTrue(Regex("^false\t.*:\\d+: boom$").matches(lines[6]), out)
        assertTrue(lines[7].startsWith("false\ttable: "), out)
        assertTrue(lines[8].contains("bad argument #2") && lines[8].contains("function expected"), out)
        assertEquals("false\tTerminated", lines[9], out)
        assertEquals("true\tdone", lines[10], out)
    }

    @Test
    fun `os_rebootが断られたらfalseと理由を返す`() = timeout(limit) {
        // テストのマシンは起動を通らずに作ったので、reboot は not-supported で断られる（ADR 0306）
        val out = run("print(os.reboot())")
        assertEquals("false\tnot-supported\n", out)
    }

    @Test
    fun `os_shutdownで出力を書き出してからマシンが止まる`() = timeout(limit) {
        Run("io.write('bye'); os.shutdown(); print('not reached')").use { r ->
            r.waitUntil("止まらない") { r.m.state == MachineState.STOPPED }
            assertEquals("bye", r.drain())
        }
    }

    @Test
    fun `イベントを待たないスクリプトはキューを開かない`() = timeout(limit) {
        Run("peripheral.getNames(); os.startTimer(1); os.sleep(0.05); print('ok'); io.stdout:flush(); os.sleep(30)").use { r ->
            r.waitFor("ok\n")
            val queues = r.m.lock.withLockValue {
                var n = 0
                r.m.kernel.objects.forEachObject { if (it is pro.ikili.kibicomputers.core.kernel.event.EventQueue) n++ }
                n
            }
            assertEquals(0, queues)
        }
    }

    @Test
    fun `RAMの上限の近くではnot enough memoryのLuaのエラーになり、CRASHEDにならない`() = timeout(limit) {
        val script = """
            -- 埋める前に、使う関数と文字列を作っておく（埋めた後は、pcall の外で確保しない）
            local addr, echo, q = "$ADDRESS", "echo", "q"
            local function pull() os.queueEvent(q); return os.pullEvent() end
            local fill = function(t) for i = 1, 1e7 do t[i] = ('x'):rep(64) .. i end end
            local t = {}
            pcall(fill, t)
            local ok1, e1 = pcall(peripheral.call, addr, echo, 1)
            local ok2, e2 = pcall(peripheral.getNames)
            local ok3, e3 = pcall(pull)
            t = nil
            collectgarbage()
            print(tostring(ok1 or e1), tostring(ok2 or e2), tostring(ok3 or e3))
            print(pcall(peripheral.call, addr, echo, 1))
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            // RAM は解放しても減らないので、上限に達した後に続けられるかは穴の使い回し次第（#370。努力目標）。
            // ここでは、どの呼び出しもトラップ（CRASHED）にならず、Lua のエラーで返ることだけを確かめる
            val out = r.finish()
            val first = out.lines()[0].split("\t")
            assertEquals(3, first.size, out)
            assertTrue(first.all { it == "true" || it.endsWith("not enough memory") }, out)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [8, 16, 20])
    fun `RAMの上限の近くでイベントを読み続けても、Cのスタックが深くなる所でトラップにならない`(remainingKib: Long) = timeout(limit) {
        // #729 の再現。上限の近くで、表に足す・queueEvent・pullEventRaw をくり返す。
        // 直す前は、pullEventRaw の中で初めて深くなった C のスタック（上端から 8〜12 KiB）への書き込みがトラップになっていた
        val script = """
            local rep, q, p, pe, pc = string.rep, os.queueEvent, "p", os.pullEventRaw, pcall
            local t = {}
            print("ready")
            io.stdout:flush()
            os.sleep(2)
            for i = 1, 400 do
                pc(function() t[#t + 1] = rep("y", 16) end)
                pc(q, p)
                pc(pe)
            end
            t = nil
            collectgarbage()
            print("done")
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(remainingKib * 1024)
            // CRASHED にならず、Lua として終わる。関数を作る所など pcall の外の確保が断られたら、Lua のエラーで終わる
            val out = r.finish()
            r.release(held)
            assertTrue(out == "ready\ndone\n" || "not enough memory" in out, out)
        }
    }

    @Test
    fun `取り出した後のLuaの確保だけが失敗したら、写せなかったイベントをevents_droppedで知らせる`() = timeout(limit) {
        // 結果の場所（take の見積もり）は解放した穴に入り、取り出したイベントを Lua の値に写す確保だけが断られる形（#727・#729）。
        // 1回の pullEventRaw は、取れるイベントを 8 件ずつ全部 FIFO に移すので、4 KiB のイベントを 40 件出せば、どこかの件で必ず断られる
        val script = """
            local pull, sleep, pc = os.pullEventRaw, os.sleep, pcall
            os.queueEvent("x")
            pull("x")
            -- take の見積もりより大きい穴を作っておく（上限の近くで、結果の場所は新しく数えずに取れる）
            local s = ("z"):rep(48 * 1024)
            s = nil
            collectgarbage()
            print("ready")
            io.stdout:flush()
            sleep(2)
            local ok, err = pc(pull)
            io.write("failed\n")
            io.stdout:flush()
            sleep(2)
            os.queueEvent("end")
            local big, dropped = 0, 0
            while true do
                local ev, a = pull()
                if ev == "end" then break end
                if ev == "big" then big = big + 1 elseif ev == "events_dropped" then dropped = dropped + a end
            end
            print(ok, err, big, dropped)
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            r.waitFor("ready\n")
            val text = DeviceValue.of("e".repeat(4000))
            repeat(40) { assertTrue(r.dev.context!!.postEvent("big", text)) }
            val held = r.squeeze(24 * 1024)
            r.waitFor("failed\n")
            r.waitUntil("また待たない") { r.m.stateOf(r.pid) == ProcessState.WAITING_EVENT }
            r.release(held)
            val last = r.finish().lines().dropLast(1).last().split("\t")
            assertEquals(listOf("false", "not enough memory"), last.take(2), last.toString())
            val big = last[2].toInt()
            val dropped = last[3].toInt()
            // どの件で失敗したかに左右されない: 受け取った件数と、写せなかった件数の合計が、出した件数になる
            assertEquals(40, big + dropped, last.toString())
            assertTrue(dropped > 0, last.toString())
        }
    }

    @Test
    fun `呼び出しの結果を写す確保だけが失敗しても、fdを閉じて呼び続けられる`() = timeout(limit) {
        // 結果の場所は穴に入り、60 KiB の文字列を Lua に写す確保だけが断られる形を、fd の上限（256）より多く続ける（#727・#729）
        val script = """
            local call, pc, addr, big, echo = peripheral.call, pcall, "$ADDRESS", "big", "echo"
            local s = ("z"):rep(72 * 1024)
            s = nil
            collectgarbage()
            print("ready")
            io.stdout:flush()
            os.sleep(2)
            local refused = 0
            for i = 1, 300 do
                local ok, err = pc(call, addr, big, 60 * 1024)
                if not ok and err == "not enough memory" then refused = refused + 1 end
            end
            local ok, v = pc(call, addr, echo, 7)
            print(refused, ok, v)
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(40 * 1024)
            val out = r.finish()
            r.release(held)
            assertEquals("ready\n300\ttrue\t7\n", out)
            // 断られたのは結果を Lua に写すときで、どの呼び出しもデバイスまで届いている
            assertEquals(300, r.dev.bigCalls.get())
        }
    }

    /** 長い失敗の文と、上限いっぱいの数のメソッドを持つデバイス */
    private class Wordy : Device {
        override val kind = "test:wordy"
        override val methods: DeviceMethods = DeviceMethods.builder().also { b ->
            b.direct("long-fail") { _, _ -> throw DeviceException("e".repeat(5000)) }
            b.direct("long-bad") { _, _ -> throw DeviceException("あ".repeat(2000)) }
            for (i in 0 until 254) b.direct("m$i") { _, _ -> emptyArray() }
        }.build()
    }

    @Test
    fun `長い失敗の文は切り詰められ、メソッドが上限いっぱいでもgetMethodsとwrapが使える`() = timeout(limit) {
        Run(
            """
            local ok, msg = pcall(peripheral.call, "$OTHER", "long-fail")
            print(ok, #msg, msg == ("e"):rep(#msg))
            local ok2, msg2 = pcall(peripheral.call, "$OTHER", "long-bad")
            print(ok2, #msg2 <= 1024, msg2:sub(1, 3))
            print(#peripheral.getMethods("$OTHER"))
            local w = peripheral.wrap("$OTHER")
            print(w.m0 ~= nil, w.m253 ~= nil, peripheral.getType(w))
            """.trimIndent(),
        ).use { r ->
            assertTrue(r.m.devices.attach(OTHER, Wordy()))
            assertEquals("false\t1024\ttrue\nfalse\ttrue\tあ\n256\ntrue\ttrue\ttest:wordy\n", r.finish())
        }
    }
}
