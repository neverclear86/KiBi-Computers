package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceCompletion
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
import java.util.concurrent.ConcurrentLinkedQueue
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

        /** main-mark が動いた回数 */
        val marks = AtomicInteger()

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
            .direct("fail-with") { _, args -> throw DeviceException(args.getText(0)) }
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
            .mainThread("main-mark") { _, _ ->
                marks.incrementAndGet()
                emptyArray()
            }
            .mainThread("main-fail") { _, _ -> throw DeviceException("main boom") }
            .mainThread("main-gate") { ctx, args ->
                gates.add(ctx.defer() to args.getLong(0))
                emptyArray()
            }
            .build()

        /** main-gate が後で返すと決めた呼び出しと、その引数。テストの側が [release] で返す */
        val gates = ConcurrentLinkedQueue<Pair<DeviceCompletion, Long>>()

        /** main-gate の呼び出しを、受けた順にすべて返す（値は引数そのまま） */
        fun release() {
            while (true) {
                val (c, v) = gates.poll() ?: return
                c.complete(DeviceValue.of(v))
            }
        }
    }

    private inner class Run(
        script: String,
        val h: Harness = Harness(execRuntime = EndiveRuntime()),
        memoryBytes: Long? = null,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
        maxHostCallsPerTick: Int = 64,
        /**
         * true なら、[waitUntil]・[finish] はプロセスが待っている（イベント・接続層・燃料）ときだけ tick を進める。
         * Lua が動いている間に tick が入ると、start と直後の take の間でメインスレッドのメソッドが終わり、コルーチンが
         * task_complete を yield せずに値を返す（CI の遅い実行で、waitForAny の戻り値が変わる）。
         * `maxHostCallsPerTick = 1` と組にする（[finishTickingWhileWaiting] と同じ）。`Machine.runHostCalls` は tick の途中で
         * 積まれた呼び出しも同じ tick の中で実行するので、上限が 2 以上だと、待っている間に始めた tick の中で Lua が起きて
         * 積んだ start が、直後の take より前に実行されうる
         */
        private val tickOnlyWhileWaiting: Boolean = false,
        /** マシンのコンソール出力（プロセスが終わるときの注記など）を受けたいときに渡す */
        host: MachineHost? = null,
    ) : AutoCloseable {
        init {
            require(!tickOnlyWhileWaiting || maxHostCallsPerTick == 1) { "tickOnlyWhileWaiting は maxHostCallsPerTick = 1 と組にする" }
        }

        val m: Machine = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = memoryBytes, environment = environment, maxHostCallsPerTick = maxHostCallsPerTick)
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

        private fun shouldTick(): Boolean {
            if (!tickOnlyWhileWaiting) return true
            val state = m.stateOf(pid)
            return state == ProcessState.WAITING_EVENT || state == ProcessState.WAITING_HOST || state == ProcessState.WAITING_FUEL ||
                m.fuelWaiterCount > 0
        }

        fun waitUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
            if (shouldTick()) h.scheduler.tick()
            Thread.sleep(1)
            drain()
            condition()
        }

        /** このプロセスが持つ device・pending-call の fd の数 */
        fun openCallFds(): Int = m.lock.withLockValue {
            m.kernel.processes[pid]!!.handles.entries().values.count {
                val o = m.kernel.objects[it.objectId]
                o is pro.ikili.kibicomputers.core.device.PendingDeviceCall || o is pro.ikili.kibicomputers.core.device.DeviceHandle
            }
        }

        fun waitFor(line: String) = waitUntil("「$line」が出ない: $text（${m.stateOf(pid)}）") { text.contains(line) }

        /**
         * [finish] と同じだが、ホスト呼び出しが列にある間は、燃料が尽きて待つプロセスがいるときだけ tick を進める
         * （tick の中で列の呼び出しが実行されるので、Lua の速さと tick の間隔によらず、終わるまで列に残す）
         */
        fun finishHoldingHostCalls(expected: ProcessState = ProcessState.EXITED): String {
            eventually(60_000, "終わらない: $text") {
                if (m.pendingHostCalls == 0 || m.fuelWaiterCount > 0) h.scheduler.tick()
                Thread.sleep(1)
                drain()
                m.stateOf(pid).isTerminal
            }
            val out = drain()
            assertEquals(expected, m.stateOf(pid), out)
            return out
        }

        /**
         * [finish] と同じだが、プロセスが待っている（イベント・接続層・燃料）ときだけ tick を進める（1 tick に実行する呼び出しは
         * `maxHostCallsPerTick = 1` で 1 件にする）。start と直後の take の間に tick が入ってメソッドが終わり、コルーチンが
         * task_complete を yield せずに値を返すことがないようにする（Lua が動いている間は tick を進めないので、メインスレッドの
         * メソッドは必ず yield する）
         */
        fun finishTickingWhileWaiting(expected: ProcessState = ProcessState.EXITED): String {
            eventually(60_000, "終わらない: $text") {
                val state = m.stateOf(pid)
                val waiting = state == ProcessState.WAITING_EVENT || state == ProcessState.WAITING_HOST || state == ProcessState.WAITING_FUEL
                if (waiting || m.fuelWaiterCount > 0) h.scheduler.tick()
                Thread.sleep(1)
                drain()
                m.stateOf(pid).isTerminal
            }
            val out = drain()
            assertEquals(expected, m.stateOf(pid), out)
            return out
        }

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

    /** [run] と同じだが、メインスレッドのメソッドが start の直後に終わらないように tick を進める（[Run.finishTickingWhileWaiting]） */
    private fun runYielding(script: String): String = Run(script, maxHostCallsPerTick = 1).use { it.finishTickingWhileWaiting() }

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
                "big,echo,emit,emit-as,fail,fail-with,get-size,main-echo,main-fail,main-gate,main-mark,needs-int,posted,types",
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
        val out = runYielding(
            """
            print(pcall(function() os.sleep(0.05); return peripheral.call("$ADDRESS", "main-echo", "p") end))
            local co = coroutine.wrap(function()
                local _, v = os.pullEvent("c")
                coroutine.yield(v + peripheral.call("$ADDRESS", "main-echo", 1))
            end)
            print(co())
            -- メインスレッドのメソッドは task_complete を yield する（#744。CC と同じ）
            print(co("c", 1))
            local ev = table.pack(os.pullEvent("task_complete"))
            print(co(table.unpack(ev, 1, ev.n)))
            print((string.gsub("ab", ".", function(ch) os.sleep(0.05); return peripheral.call("$ADDRESS", "main-echo", ch:upper()) end)))
            """.trimIndent(),
        )
        assertEquals("true\tp\nc\ntask_complete\n2\nAB\n", out)
    }

    @Test
    fun `parallelの中のメインスレッドのメソッドは結果を待つ間にほかのコルーチンを動かす`() = timeout(limit) {
        Run(
            """
            local p = peripheral.wrap("$ADDRESS")
            parallel.waitForAll(
                function() print("call", p.mainGate(5), peripheral.call("$ADDRESS", "main-gate", 6)) end,
                function() os.sleep(0.05); print("slept"); io.stdout:flush() end
            )
            print("all")
            """.trimIndent(),
        ).use { r ->
            // 1つ目の呼び出しを返さないうちに、もう一方のコルーチンの sleep が進む（yield しなければここで止まる）
            r.waitUntil("sleep が進まない") { r.drain().contains("slept\n") && r.dev.gates.size == 1 }
            r.dev.release()
            r.waitUntil("2つ目の呼び出しが来ない") { r.dev.gates.size == 1 }
            r.dev.release()
            assertEquals("slept\ncall\t5\t6\nall\n", r.finish())
        }
    }

    @Test
    fun `コルーチンの中の呼び出しはtask_completeをyieldし、直接のメソッドとその場の失敗とyieldできない文脈では待たない`() = timeout(limit) {
        val out = runYielding(
            """
            local p = peripheral.wrap("$ADDRESS")
            -- 直接のメソッド（wrap は invoke、call は start してすぐ take）は yield しない
            local co = coroutine.create(function() return peripheral.call("$ADDRESS", "echo", 2), p.echo(1, "a") end)
            print(coroutine.resume(co))
            -- メインスレッドのメソッドは task_complete を yield し、id の合わないものと別のイベントは捨てる
            local co2 = coroutine.create(function() return p.mainEcho(7, nil, "z") end)
            print(coroutine.resume(co2))
            print(coroutine.resume(co2, "task_complete", -1, true, "wrong"))
            print(coroutine.resume(co2, "other"))
            local ev = table.pack(os.pullEvent("task_complete"))
            print(math.type(ev[2]), ev[3], ev.n)
            print(coroutine.resume(co2, ev[1], ev[2] + 0.0, table.unpack(ev, 3, ev.n)))
            print(coroutine.status(co2))
            -- 失敗は task_complete の false と文。続きでは呼んだ行の位置が付く
            local co3 = coroutine.create(function() return peripheral.call("$ADDRESS", "main-fail") end)
            coroutine.resume(co3)
            local e3 = table.pack(os.pullEvent("task_complete"))
            print(e3[3], e3[4], e3.n)
            print(coroutine.resume(co3, table.unpack(e3, 1, e3.n)))
            -- terminate は Terminated（位置なし）。呼び出しは取り消さず、後で task_complete が届く
            local co4 = coroutine.create(function() return peripheral.call("$ADDRESS", "main-echo", 4) end)
            coroutine.resume(co4)
            print(coroutine.resume(co4, "terminate"))
            print(select(3, os.pullEvent("task_complete")))
            -- その場の失敗は yield せずにエラー
            print(coroutine.resume(coroutine.create(function() return peripheral.call("$ADDRESS", "nope") end)))
            print(coroutine.resume(coroutine.create(function() return peripheral.call("$OTHER", "x") end)))
            -- yield できない文脈（gsub のコールバック）では、その場で待つ
            print(coroutine.wrap(function()
                return (string.gsub("ab", ".", function(ch) return peripheral.call("$ADDRESS", "main-echo", ch:upper()) end))
            end)())
            """.trimIndent(),
        )
        val lines = out.lines()
        assertEquals("true\t2\t1\ta", lines[0], out)
        assertEquals("true\ttask_complete", lines[1], out)
        assertEquals("true\ttask_complete", lines[2], out)
        assertEquals("true\ttask_complete", lines[3], out)
        assertEquals("integer\ttrue\t6", lines[4], out)
        assertEquals("true\t7\tnil\tz", lines[5], out)
        assertEquals("dead", lines[6], out)
        assertEquals("false\tmain boom\t4", lines[7], out)
        assertTrue(Regex("^false\t.*:\\d+: main boom$").matches(lines[8]), out)
        assertEquals("false\tTerminated", lines[9], out)
        assertEquals("true\t4", lines[10], out)
        assertTrue(Regex("^false\t.*:\\d+: No such method nope$").matches(lines[11]), out)
        assertTrue(Regex("^false\t.*:\\d+: No such peripheral$").matches(lines[12]), out)
        assertEquals("AB", lines[13], out)
    }

    @Test
    fun `上限を超えて並べてもその場で待って動き、fdを漏らさず、捨てたコルーチンの呼び出しも後で閉じる`() = timeout(limit) {
        Run(
            """
            -- 20 個のコルーチンが 15 回ずつ（300 回。fd の上限 256 を超える）。同時に 16 個を超えた分は invoke でその場で待つ
            local fns, sums = {}, {}
            for i = 1, 20 do
                fns[i] = function()
                    local s = 0
                    for j = 1, 15 do s = s + peripheral.call("$ADDRESS", "main-echo", j) end
                    sums[i] = s
                end
            end
            parallel.waitForAll(table.unpack(fns))
            local sum = 0
            for i = 1, 20 do sum = sum + sums[i] end
            print("sum", sum)
            -- waitForAny で残したコルーチンの呼び出しは、表に残り、後の待ちで回収されて閉じる
            print(parallel.waitForAny(function() peripheral.call("$ADDRESS", "main-echo", 1) end, function() end))
            print("waiting"); io.stdout:flush()
            os.pullEvent("finish")
            print("finished")
            """.trimIndent(),
            maxHostCallsPerTick = 1,
            tickOnlyWhileWaiting = true,
        ).use { r ->
            fun open(): Int = r.m.lock.withLockValue {
                r.m.kernel.processes[r.pid]!!.handles.entries().values.count {
                    val o = r.m.kernel.objects[it.objectId]
                    o is pro.ikili.kibicomputers.core.device.PendingDeviceCall || o is pro.ikili.kibicomputers.core.device.DeviceHandle
                }
            }
            r.waitFor("waiting")
            r.waitUntil("残した呼び出しが閉じない: ${open()}") { open() == 0 }
            r.dev.context!!.postEvent("finish")
            assertEquals("sum\t2400\n2\nwaiting\nfinished\n", r.finish())
            assertEquals(301, r.dev.mainCalls.get())
        }
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

    /**
     * 結果の場所（約 66 KiB）が入る解放済みの穴を、先に潰しておく Lua のコード。4000 バイトの文字列を持つ要素を、Lua の使用量が [kb] KB になるまで積み、
     * 新しい 12 個（約 48 KB）だけ解放する。空きは 1 つながりの 48 KB の穴になり、take の結果の場所（約 39 KiB）には足りるが、
     * 回収の結果の場所（約 66 KiB）には足りない（それまでの呼び出しが残した大きな穴は、この積みで使われる）。
     * これは穴の形を整えるだけで、残りは決めない。残りは、この後 Lua が os.sleep で待つ間に、[Run.squeeze] でバイト単位に合わせる
     * （埋めながら試す形は、残りが 4 KiB 単位で減り、スレッドの動き方で結果が変わるので使わない。ADR 0094）。
     * 一度絞ると、解放しても大きな場所は取れなくなる（RAM は解放しても減らない。#370）ので、元に戻す関数は用意しない
     */
    private val shapeHole = """
        local keep = {}
        local function shapeHole(kb)
            while collectgarbage("count") < kb do keep.head = {keep.head, string.rep("x", 4000)} end
            for i = 1, 12 do keep.head = keep.head[1] end
            collectgarbage()
        end
    """.trimIndent()

    @Test
    fun `RAMが足りないコルーチンの呼び出しは、メソッドを動かさずにnot enough memoryになり、fdを漏らさない`() = timeout(limit) {
        // 残りは 76 KiB: 結果の場所（約 66 KiB）を取る kibi_lua_reserve は断られ（kibi_lua_realloc は KIBI_LUA_KEEP の 16 KiB を残す）、
        // 残りが KEEP + STACK_GUARD（32 KiB）より上なので、C のスタックは数えず、Lua 自身の小さな確保は通る（ADR 0094 の #729）。
        // 残りは、Lua が os.sleep で待つ間に、テストの側でバイト単位に合わせる（埋めながら試す形は使わない）
        Run(
            """
            local addr = "$ADDRESS"
            local cos, res = {}, {}
            for i = 1, 5 do
                cos[i] = coroutine.create(function() return peripheral.call(addr, "main-echo", i) end)
                res[i] = false
            end
            print("ready"); io.stdout:flush()
            os.sleep(2)
            for i = 1, 5 do
                local ok, e = coroutine.resume(cos[i])
                res[i] = ok and "started" or e
            end
            print(table.concat(res, ","))
            os.sleep(0.3)
            print("done"); io.stdout:flush()
            os.sleep(2)
            """.trimIndent(),
            memoryBytes = 512 * 1024L,
        ).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(76 * 1024)
            r.waitFor("done\n")
            // 断られた呼び出しは積まれていない（積んでから断ると、fd も表に残る）
            assertEquals(0, r.openCallFds())
            // 断られたメソッドは動いていない（積まれていれば、待つ間に動く）
            assertEquals(0, r.dev.mainCalls.get())
            val out = r.finish()
            r.release(held)
            assertEquals("ready\nnot enough memory,not enough memory,not enough memory,not enough memory,not enough memory\ndone\n", out)
        }
    }

    @Test
    fun `RAMが足りなくて回収できないときは、Luaのエラーになって要素を表に残し、トラップにならない`() = timeout(limit) {
        // 残りは 76 KiB（上のテストと同じ理由で、結果の場所の reserve だけが断られる。イベントを取り出す分の約 39 KiB は取れる）。
        // 2つの呼び出しが終わった後に絞り、回収（pullEvent・queueEvent）を通す
        Run(
            """
            $shapeHole
            local addr = "$ADDRESS"
            local cos = {}
            for i = 1, 2 do cos[i] = coroutine.create(function() return peripheral.call(addr, "main-gate", 6 + i) end) end
            local function pull() return os.pullEvent("task_complete") end
            os.queueEvent("x"); os.pullEvent("x")
            for i = 1, 2 do coroutine.resume(cos[i]) end
            print("started"); io.stdout:flush()
            repeat os.sleep(0.05) until peripheral.call(addr, "posted")
            shapeHole(200)
            print("ready"); io.stdout:flush()
            os.sleep(2)
            -- 回収の reserve が断られる（os.queueEvent も同じ回収を通る）。何度でも Lua のエラーになる
            local ok1, e1 = pcall(pull)
            local ok2, e2 = pcall(pull)
            local ok3, e3 = pcall(os.queueEvent, "y")
            print(tostring(ok1 or e1), tostring(ok2 or e2), tostring(ok3 or e3))
            os.sleep(0.3)
            print("done"); io.stdout:flush()
            os.sleep(2)
            """.trimIndent(),
            memoryBytes = 512 * 1024L,
        ).use { r ->
            r.waitFor("started\n")
            r.waitUntil("2つの呼び出しが来ない") { r.dev.gates.size == 2 }
            r.dev.release()
            r.dev.posted = true
            r.waitFor("ready\n")
            val held = r.squeeze(76 * 1024)
            r.waitFor("done\n")
            // 終わった結果は写せるまで表に残る（写し終えていないものを閉じない）
            assertEquals(2, r.openCallFds())
            val out = r.finish()
            r.release(held)
            assertEquals("started\nready\nnot enough memory\tnot enough memory\tnot enough memory\ndone\n", out)
        }
    }

    @Test
    fun `startがagainを返したら、コルーチンの中でもinvokeでその場で待つ`() = timeout(limit) {
        Run(
            """
            local p = peripheral.wrap("$ADDRESS")
            local co = coroutine.create(function() return peripheral.call("$ADDRESS", "main-echo", 5), p.mainEcho(6) end)
            print(coroutine.resume(co))
            print(coroutine.status(co))
            """.trimIndent(),
        ).use { r ->
            // 表は空のまま、カーネルの上限（1 プロセス 16 個）に当てる
            r.m.lock.withLockValue {
                repeat(16) { r.m.openPendingDeviceCallLocked(r.pid, pro.ikili.kibicomputers.core.device.PendingDeviceCall.State.Running) }
            }
            assertEquals("true\t5\t6\ndead\n", r.finish())
            assertEquals(2, r.dev.mainCalls.get())
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [28, 31, 40])
    fun `RAMの上限の近くでイベントを読み続けても、Cのスタックが深くなる所でトラップにならない`(remainingKib: Long) = timeout(limit) {
        // #729 の再現。上限の近くで、表に足す・queueEvent・pullEventRaw をくり返す。
        // 直す前は、pullEventRaw の中で初めて深くなった C のスタック（上端から 8〜12 KiB）への書き込みがトラップになっていた。
        // 関数は pcall の外で先に作る（ループの本体で作ると、確保が断られたときに、pullEventRaw の入れ子を一度も通らずに終わる）。
        // ループが最後まで回ったことと、pullEventRaw が1回でも成功した（入れ子を通った）ことを確かめる。
        // スタックの計数は、まだ数えていない区画を最大 16 KiB 数え、その後に MIN の 8 KiB を残す。続けられると言えるのは、
        // 残りがその線（24 KiB）に、包みを通らない書き込みの分（起きた後に初めて書く静的な変数やスタックの区画。少なくとも 1 区画の
        // 4 KiB）を足した値より上のときだけなので、28 KiB 以上の値を使う（#802。24 KiB ちょうどは余裕が 0 で、lua.wasm の
        // 静的な変数の配置が 16 バイトずれただけで、確保がすべて断られて Lua のエラーで終わった）。線の上下の帯は、次の 2 つのテストで確かめる。
        // この形は、直す前の kibi_wasi.c・kibi_lua_canon.c で回しても CRASHED にならないことがあり、
        // 直したことの決め手ではない。上限の近くの経路を通して CRASHED にならないことを確かめる
        val script = """
            local rep, q, p, pe, pc = string.rep, os.queueEvent, "p", os.pullEventRaw, pcall
            local t = {}
            local grow = function() t[#t + 1] = rep("y", 16) end
            local pulled, called = 0, 0
            print("ready")
            io.stdout:flush()
            os.sleep(2)
            for i = 1, 400 do
                pc(grow)
                pc(q, p)
                local ok = pc(pe)
                called = called + 1
                if ok then pulled = pulled + 1 end
            end
            -- 上限の近くでは数値の文字列化も断られるので、結果は確保しない文字列（定数）で出す
            io.stdout:write(called == 400 and "loop-ok\n" or "loop-short\n")
            io.stdout:write(pulled > 0 and "pulled\n" or "not-pulled\n")
            t = nil
            collectgarbage()
            print("done")
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(remainingKib * 1024)
            // CRASHED にならず、Lua として終わる。ループの回数と、pullEventRaw が1回でも通ったかを見る
            val out = r.finish()
            r.release(held)
            assertEquals("ready\nloop-ok\npulled\ndone\n", out)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [22, 24, 26])
    fun `RAMの残りがスタックを数える線の上下でも、イベントを読み続けてトラップにならない`(remainingKib: Long) = timeout(limit) {
        // 前のテストと同じループを、スタックの計数の線（数える 16 KiB ＋ MIN の 8 KiB = 24 KiB）の前後で回す（#729・#802）。
        // この帯では、スタックを数えられるか、数えた後に起きる包みの外の書き込み（静的な変数の区画など）で残りが MIN を割るかが、
        // lua.wasm の配置やスタックの深さで変わる。割れば確保はすべて断られ、pcall の外の確保（CallInfo など）も
        // not enough memory になって、Lua のエラーで終わりうる。どちらでも CRASHED にならない（EXITED で終わる）ことだけを確かめる
        val script = """
            local rep, q, p, pe, pc = string.rep, os.queueEvent, "p", os.pullEventRaw, pcall
            local t = {}
            local grow = function() t[#t + 1] = rep("y", 16) end
            print("ready")
            io.stdout:flush()
            os.sleep(2)
            for i = 1, 400 do
                pc(grow)
                pc(q, p)
                pc(pe)
            end
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(remainingKib * 1024)
            // finish は EXITED でなければ落ちる（Lua のエラーで終わっても EXITED）
            try {
                r.finish()
            } finally {
                r.release(held)
            }
            Unit
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [2, 4, 7])
    fun `RAMの残りがMINを割った状態で確保が断られたら、commitを呼ばない道でも、メモリ不足だった注記が出る`(remainingKib: Long) = timeout(limit) {
        // kibi_wasi.c の counted_alloc の、確保の前の MIN の線（remaining < KIBI_LUA_MIN）で断る道（#802）。この道は commit を呼ばずに NULL を返すので、
        // note_refusal がなければ、プロセスが終わったときの「kibi:memory commit was refused」の注記（断られた commit の数で決まる）が出ない。
        // 絞る前に C のスタックを深くまで書いておき（string.gsub のコールバックの入れ子）、絞った後のスタックの計数が新しく区画を数えないようにする。
        // そうすると、計数は何も数えずに通り、残りが MIN 未満のまま MIN の線に着く。pcall の外で確保して、Lua のエラー（not enough memory）で終わらせる
        val script = """
            local gsub = string.gsub
            local function deep(n) if n > 0 then gsub("x", "x", function() deep(n - 1) end) end end
            deep(40)
            print("ready")
            io.stdout:flush()
            os.sleep(2)
            local t = {}
            for i = 1, 100 do t[i] = ("y"):rep(16) end
            print("unreachable")
        """.trimIndent()
        val console = StringBuilder()
        val host = object : MachineHost {
            override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

            override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
                synchronized(console) { console.append(String(bytes, Charsets.UTF_8)) }
            }
        }
        Run(script, memoryBytes = 512 * 1024L, host = host).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(remainingKib * 1024)
            val out = r.finish()
            r.release(held)
            // コンソールの行は、プロセスが終わるときに書く。書き出しを待つ
            r.h.scheduler.tick()
            val text = synchronized(console) { console.toString().replace("\r\n", "\n") }
            assertTrue("unreachable" !in out, out)
            assertEquals(1, r.m.lock.withLockValue { r.m.kernel.processes[r.pid]!!.exitCode }, "$out / $text")
            assertTrue("kibi:memory commit was refused: out of memory, RAM limit 512 KiB)\n" in text, "$out / $text")
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [8, 12, 16, 20])
    fun `RAMの残りがスタックを数えられないほど少なくても、イベントを読み続けてトラップにならず、穴の使い回しも断られる`(remainingKib: Long) = timeout(limit) {
        // #729 の元の形（512 KiB を埋め続ける）が必ず通る、低い残りの帯で、CRASHED にならない（Lua として終わる）ことを確かめる。
        // 残りが約 22 KiB 以下だと、スタックを数える commit（後に MIN の 8 KiB を残す）が断られるので、kibi_lua_realloc は
        // 解放した穴に収まる確保も含めてすべて NULL を返す（ADR 0094。スタックを守れないまま続けると、深くなったスタックがトラップになるため）。
        // 起きてすぐの、穴（先に作った 48 KiB）に収まる小さな確保が断られること（スタックを数えない形なら、この帯でも使い回せる）と、
        // ループが確保を断られながら最後まで回ることを見る。
        // この形は、直す前の kibi_wasi.c・kibi_lua_canon.c でも CRASHED にはならなかった（この帯でトラップを決まった形で再現できてはいない）
        val script = """
            local rep, q, p, pe, pc = string.rep, os.queueEvent, "p", os.pullEventRaw, pcall
            local t = {}
            local grow = function() t[#t + 1] = rep("y", 16) end
            local called = 0
            local s = ("z"):rep(48 * 1024)
            s = nil
            collectgarbage()
            print("ready")
            io.stdout:flush()
            os.sleep(2)
            local hole = pc(rep, "h", 1024)
            for i = 1, 400 do
                pc(grow)
                pc(q, p)
                pc(pe)
                called = called + 1
            end
            -- 上限の近くでは数値の文字列化も断られるので、結果は確保しない文字列（定数）で出す
            io.stdout:write(called == 400 and "loop-ok\n" or "loop-short\n")
            io.stdout:write(hole and "hole-reused\n" or "hole-refused\n")
            io.stdout:flush()
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(remainingKib * 1024)
            val out = r.finish()
            r.release(held)
            assertEquals("ready\nloop-ok\nhole-refused\n", out)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [30, 40, 44, 52, 60])
    fun `RAMの残りがスタックを数える線（32 KiB）の近くで、深いCのスタックから小さな結果のimportを呼んでもトラップにならない`(remainingKib: Long) = timeout(limit) {
        // #729。結果が小さい import（peripheral.getType = open と info。見積もり 512 バイト）は、kibi_lua_reserve の速い道（何も取らない）を通る。
        // その先の cabi_realloc の確保で、初めての C のスタックの計数（最大 16 KiB）が走ると、残りが KEEP を割って OUT_OF_MEMORY → トラップになる。
        // これを起こすには、import の直前まで Lua の確保が起きず（起きれば、そこで先にスタックを数える）、
        // そのスタックが、まだ触っていない深さまで伸びていること。
        // 先に Lua だけの再帰で CallInfo と Lua のスタックを伸ばしておき（C のスタックは使わない）、残りを絞ってから、string.gsub のコールバックの入れ子（C のスタックを使う）で深くする。
        // 速い道の余裕に計数の分を含めるので、断られる（"not enough memory"）か成功するかのどちらかで、CRASHED にならない。
        // 深くするだけで残りが減る（触った区画も数える）ので、計数が走る残りの幅に当たる値をいくつか並べる（旧い余裕 24 KiB で必ずトラップになることまでは再現できていない。この経路を通して CRASHED にならないことを確かめる）
        val script = """
            local gt, pc, addr = peripheral.getType, pcall, "$ADDRESS"
            local function warm(n) if n == 0 then return 0 end return 1 + warm(n - 1) end
            warm(400)
            local gsub = string.gsub
            local function deep(n)
                if n == 0 then return gt(addr) end
                local r
                gsub("x", "x", function() r = deep(n - 1) end)
                return r
            end
            print("ready")
            io.stdout:flush()
            os.sleep(2)
            for i = 1, 3 do pc(deep, 20) end
            print("done")
        """.trimIndent()
        Run(script, memoryBytes = 512 * 1024L).use { r ->
            r.waitFor("ready\n")
            val held = r.squeeze(remainingKib * 1024)
            val out = r.finish()
            r.release(held)
            assertEquals("ready\ndone\n", out)
        }
    }

    @Test
    fun `terminateでプログラムが終わると、まだ実行していないメインスレッドの呼び出しは実行されない`() = timeout(limit) {
        // 1 tick に 1 件しか実行せず、列に積んだ後は燃料が尽きたときしか tick を進めないので、先に積んだ 3 件の後ろにある main-mark は、
        // プログラムが終わるまでに実行されない
        Run(
            """
            local function start(method) local co = coroutine.create(function() return peripheral.call("$ADDRESS", method, 1) end); coroutine.resume(co); return co end
            start("main-echo"); start("main-echo"); start("main-echo")
            local co = start("main-mark")
            print(coroutine.resume(co, "terminate"))
            """.trimIndent(),
            maxHostCallsPerTick = 1,
        ).use { r ->
            assertEquals("false\tTerminated\n", r.finishHoldingHostCalls())
            // 終わったプロセスの fd が閉じられ、列にあった呼び出しは外される
            repeat(20) {
                r.h.scheduler.tick()
                Thread.sleep(5)
            }
            assertEquals(0, r.dev.marks.get())
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [20, 24, 28, 32])
    fun `取り出した後のLuaの確保だけが失敗したら、写せなかったイベントをevents_droppedで知らせる`(remainingKib: Long) = timeout(limit) {
        // 結果の場所（take の見積もり）は解放した穴に入り、取り出したイベントを Lua の値に写す確保だけが断られる形（#727・#729）。
        // 1回の pullEventRaw は、取れるイベントを 8 件ずつ全部 FIFO に移すので、4 KiB のイベントを 40 件出せば、どこかの件で必ず断られる。
        // 残りの値で結果を決めないよう、絞る前に次の 2 つをしておく（#802）:
        // - C のスタックを、起きた後の pullEventRaw より深くまで書いておく（string.gsub のコールバックの入れ子）。上限の近くの
        //   スタックの計数（kibi_wasi.c の count_stack）が新しく数える区画をなくし、結果がヒープの穴とイベントの写しだけで決まる
        //   （スタックの数え方は、ほかの上限の近くのテストが受け持つ）
        // - 起きた後と同じ形（フィルターなし）の pullEventRaw を一度通しておく。その道が初めて書く静的な変数（tty のキーの状態など）の
        //   区画を先に数えさせる。どの変数がまだ書いていない 4 KiB の区画に入るかは lua.wasm のリンクの配置で決まり、
        //   関係のないコードの変更で、起きた後の残りが 4 KiB 変わっていた
        // 残り 20 KiB では、スタックの計数で区画を数えると（16 KiB と MIN の 8 KiB が要る）見積もりから断られるので、
        // 上の前提が崩れれば [false, not enough memory, 40, 0] で落ちる
        val script = """
            local pull, sleep, pc, gsub = os.pullEventRaw, os.sleep, pcall, string.gsub
            local function deep(n) if n > 0 then gsub("x", "x", function() deep(n - 1) end) end end
            deep(40)
            os.queueEvent("x")
            pull()
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
            val held = r.squeeze(remainingKib * 1024)
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
    fun `コルーチンの中の結果を写す間に__gcがos_queueEventを呼んでも、結果は壊れず、積んだイベントも届く`() = timeout(limit) {
        // GC を止めずに回し続け、__gc のたびに次の __gc を持つごみを作る。結果の文字列（長い文字列は 1 つずつ確保する）を写す間に
        // __gc が走り、os.queueEvent が回収（take_kernel_events・kibi_lua_tasks_collect）を入れ子で呼ぼうとする。
        // キューは parallel が開くので、止めなければ take_kernel_events が写している結果の場所（arena）を戻して取り直す（#755）。
        // __gc は結果と同じくらいの大きさの文字列を作り、戻された場所を使い回させる（止めないと、写した値が "Z" などで壊れる）。
        // __gc の連鎖は 3,000 回で止める（GC の分の時間を抑える）
        val out = Run(
            """
            local hits, junk = 0, {}
            local mt = {}
            mt.__gc = function() hits = hits + 1; os.queueEvent("g"); for k = 1, 8 do junk[k] = string.rep("Z", 1500 + k) end; if hits < 3000 then setmetatable({}, mt) end end
            for i = 1, 16 do setmetatable({}, mt) end
            local big = {}
            for i = 1, 32 do big[i] = string.rep(string.char(65 + i % 26), 1500) .. i end
            local bad, g = 0, 0
            collectgarbage("incremental", 0, 1000)
            parallel.waitForAll(function()
                for round = 1, 10 do
                    local a = { peripheral.call("$ADDRESS", "echo", table.unpack(big)) }
                    local b = { peripheral.call("$ADDRESS", "main-echo", table.unpack(big)) }
                    for i = 1, 32 do
                        if a[i] ~= big[i] then bad = bad + 1 end
                        if b[i] ~= big[i] then bad = bad + 1 end
                    end
                end
                collectgarbage("incremental", 200, 100)
                os.queueEvent("end")
            end, function()
                while os.pullEvent() ~= "end" do g = g + 1 end
            end)
            print(bad, hits > 0, g > 0)
            """.trimIndent(),
        ).use { it.finish() }
        assertEquals("0\ttrue\ttrue\n", out)
    }

    @Test
    fun `カーネルのイベントを写す間に__gcからpullEventRawを呼ぶと、待たずにエラーになり、イベントは壊れずに届く`() = timeout(limit) {
        // メインスレッドの take_kernel_events が、取り出したイベント（arena にある結果）を Lua の値に写す間は、kibi_lua_arena_hold で入れ子の回収を止める（#755）。
        // その間に __gc が os.pullEventRaw を呼ぶと、回収（collect）はタイマーだけ移し、待たずに
        // "cannot wait for events while a result is being copied" のエラーにする（待つと、取り出せるものがなく止まる）。
        // 止めていなければ、入れ子の取り出しが写している結果の場所（arena）を戻して取り直し、写した値が壊れる。
        // イベントは、デバイスの emit-as で、480 文字の文字列 8 個を持つもの（1 つのイベントの大きさの上限は 4 KiB）を出す。写す間の確保で GC が走り、__gc が入る。
        // __gc が写す前の位置で pullEventRaw を呼ぶと、そのイベントを自分で受け取る（check に通す）ので、どちらで受け取っても、壊れていないことと件数を見る。
        // テストの側が pad を出し続けて、入れ子の pullEventRaw が待ち続けないようにする
        val out = Run(
            """
            local addr, call, pull, pc = "$ADDRESS", peripheral.call, os.pullEventRaw, pcall
            local parts = {}
            for i = 1, 8 do parts[i] = string.rep(string.char(65 + i % 26), 480) .. i end
            local bigs, bad, held, armed, hits = 0, 0, 0, false, 0
            local function check(name, ...)
                if name ~= "big" then return end
                -- デバイスのイベントは、最初の値がデバイスの address
                local a = { ... }
                bigs = bigs + 1
                if #a ~= 9 or a[1] ~= addr then bad = bad + 1; return end
                for i = 1, 8 do if a[i + 1] ~= parts[i] then bad = bad + 1; return end end
            end
            local mt = {}
            mt.__gc = function()
                hits = hits + 1
                if armed then
                    local r = table.pack(pc(pull))
                    if r[1] then check(table.unpack(r, 2, r.n))
                    elseif tostring(r[2]):find("while a result is being copied", 1, true) then held = held + 1 end
                end
                if hits < 3000 then setmetatable({}, mt) end
            end
            -- イベントのキューを先に開く（開く前に来たイベントは届かない）
            os.queueEvent("x"); os.pullEventRaw()
            for i = 1, 16 do setmetatable({}, mt) end
            collectgarbage("incremental", 0, 1000)
            armed = true
            for round = 1, 20 do
                call(addr, "emit-as", "big", table.unpack(parts))
                while bigs < round do check(pull()) end
            end
            armed = false
            collectgarbage("incremental", 200, 100)
            print(bad, bigs, held > 0)
            """.trimIndent(),
        ).use { r ->
            r.waitUntil("終わらない") {
                r.dev.context?.postEvent("pad")
                r.m.stateOf(r.pid).isTerminal
            }
            r.finish()
        }
        assertEquals("0\t20\ttrue\n", out)
    }

    /**
     * 写す間の __gc から呼ぶ、入れ子の peripheral の関数（#756）。call（直接のメソッド echo に [nsize] + k 文字の文字列を渡す）と、
     * open_device を通る isPresent・wrap・getMethods、push_device_list を通る getNames・find を順に呼び、
     * 成功なら値が合うか、失敗なら「写している途中」のエラーかを数える（nbad・refused）。どの関数が拒否されたかは byOp に数える。
     * __gc のたびに次の __gc を持つごみを作り、連鎖は 3,000 回で止める。__gc は結果と同じくらいの大きさの文字列も作り、戻された場所を使い回させる
     */
    private fun nestedCallPrelude(nsize: Int) = """
        local addr, call, pc = "$ADDRESS", peripheral.call, pcall
        local hits, junk, refused, nbad = 0, {}, 0, 0
        local byOp = {}
        local ops = {
            function(k)
                local s = string.rep("Z", $nsize + k)
                return s, call(addr, "echo", s)
            end,
            function() local n = peripheral.getNames(); return #n == 1 and n[1] == addr end,
            function() local r = { peripheral.find("test:dev") }; return #r == 1 and type(r[1]) == "table" end,
            function() return peripheral.isPresent(addr) end,
            function() return type(peripheral.wrap(addr)) == "table" end,
            function() return #peripheral.getMethods(addr) == 14 end,
        }
        local function nested(k)
            local op = k % #ops + 1
            local ok, a, b = pc(ops[op], k)
            if ok then
                if op == 1 then
                    if a ~= b then nbad = nbad + 1 end
                elseif a ~= true then
                    nbad = nbad + 1
                end
            elseif tostring(a):find("cannot call a peripheral while a result is being copied", 1, true) then
                refused = refused + 1
                byOp[op] = (byOp[op] or 0) + 1
            else
                nbad = nbad + 1
            end
        end
        local mt = {}
        mt.__gc = function()
            hits = hits + 1
            for k = 1, 8 do junk[k] = string.rep("Y", $nsize + k) end
            nested(hits)
            if hits < 3000 then setmetatable({}, mt) end
        end
        for i = 1, 16 do setmetatable({}, mt) end
    """.trimIndent()

    @Test
    fun `メインスレッドの結果を写す間に__gcがperipheral_callを呼ぶと、エラーになり、結果は壊れない`() = timeout(limit) {
        // その場で待つ呼び出し（invoke_now）と getNames・getMethods・getType が arena にある結果を写す間は kibi_lua_arena_hold で囲み、
        // 入れ子の peripheral の関数はエラーにする（#756）。止めなければ、入れ子の open_device が arena を戻して取り直し、写している結果が壊れる
        val out = Run(
            nestedCallPrelude(1500) + "\n" +
                """
                local big = {}
                for i = 1, 32 do big[i] = string.rep(string.char(65 + i % 26), 1500) .. i end
                local bad = 0
                collectgarbage("incremental", 0, 1000)
                for round = 1, 20 do
                    local a = { call(addr, "echo", table.unpack(big)) }
                    for i = 1, 32 do if a[i] ~= big[i] then bad = bad + 1 end end
                    local names = peripheral.getNames()
                    if #names ~= 1 or names[1] ~= addr then bad = bad + 1 end
                    local ms = peripheral.getMethods(addr)
                    if #ms ~= 14 then bad = bad + 1 end
                    if peripheral.getType(addr) ~= "test:dev" then bad = bad + 1 end
                end
                collectgarbage("incremental", 200, 100)
                print(bad, nbad, refused > 0)
                """.trimIndent(),
        ).use { it.finish() }
        assertEquals("0\t0\ttrue\n", out)
    }

    @Test
    fun `コルーチンの中の結果を写す間に__gcがperipheral_callを呼ぶと、エラーになり、結果は壊れない`() = timeout(limit) {
        // コルーチンの中の echo（その場で終わる。return_taken）と main-echo（回収。kibi_lua_tasks_collect か return_taken）の結果を写す間の
        // __gc から、入れ子の peripheral.call を呼ぶ（#756）。どちらの道を通ったかは tick 次第なので見ない
        val out = Run(
            nestedCallPrelude(1500) + "\n" +
                """
                local big = {}
                for i = 1, 32 do big[i] = string.rep(string.char(65 + i % 26), 1500) .. i end
                local bad = 0
                collectgarbage("incremental", 0, 1000)
                parallel.waitForAll(function()
                    for round = 1, 10 do
                        local a = { call(addr, "echo", table.unpack(big)) }
                        local b = { call(addr, "main-echo", table.unpack(big)) }
                        for i = 1, 32 do
                            if a[i] ~= big[i] then bad = bad + 1 end
                            if b[i] ~= big[i] then bad = bad + 1 end
                        end
                    end
                end, function()
                    for round = 1, 10 do
                        local ms = peripheral.getMethods(addr)
                        if #ms ~= 14 then bad = bad + 1 end
                        os.sleep(0)
                    end
                end)
                collectgarbage("incremental", 200, 100)
                print(bad, nbad, refused > 0)
                """.trimIndent(),
        ).use { it.finish() }
        assertEquals("0\t0\ttrue\n", out)
    }

    @Test
    fun `失敗の文を写す前に__gcがperipheral_callを呼んでも、文は壊れない`() = timeout(limit) {
        // call-error の文（arena にある）に位置を付ける luaL_where の確保で GC が走ると、前は文を写す前に __gc の入れ子の呼び出しが
        // arena を戻していた（#756）。今は文を写して arena を戻してから位置を付ける。文は 1,000 バイト（ホストの上限は 1,024）
        val out = Run(
            nestedCallPrelude(1000) + "\n" +
                """
                local msg = string.rep("E", 1000)
                local bad = 0
                collectgarbage("incremental", 0, 1000)
                for round = 1, 300 do
                    local ok, e = pc(call, addr, "fail-with", msg)
                    if ok or type(e) ~= "string" or e:sub(-1000) ~= msg then bad = bad + 1 end
                end
                parallel.waitForAll(function()
                    for round = 1, 100 do
                        local ok, e = pc(call, addr, "fail-with", msg)
                        if ok or type(e) ~= "string" or e:sub(-1000) ~= msg then bad = bad + 1 end
                    end
                end)
                collectgarbage("incremental", 200, 100)
                print(bad, nbad, hits > 0)
                """.trimIndent(),
        ).use { it.finish() }
        assertEquals("0\t0\ttrue\n", out)
    }

    @Test
    fun `カーネルのイベントを写す間に__gcがperipheral_callを呼ぶと、エラーになり、イベントは壊れない`() = timeout(limit) {
        // take_kernel_events がイベントを写す間（kibi_lua_arena_hold の中）の __gc から、入れ子の peripheral.call を呼ぶ（#756）。
        // 止めなければ、入れ子の open_device が arena を戻し、写しているイベントの場所を解放する
        val out = Run(
            nestedCallPrelude(480) + "\n" +
                """
                local pull = os.pullEventRaw
                local parts = {}
                for i = 1, 8 do parts[i] = string.rep(string.char(65 + i % 26), 480) .. i end
                local bigs, bad = 0, 0
                local function check(name, ...)
                    if name ~= "big" then return end
                    local a = { ... }
                    bigs = bigs + 1
                    if #a ~= 9 or a[1] ~= addr then bad = bad + 1; return end
                    for i = 1, 8 do if a[i + 1] ~= parts[i] then bad = bad + 1; return end end
                end
                -- イベントのキューを先に開く（開く前に来たイベントは届かない）
                os.queueEvent("x"); pull()
                collectgarbage("incremental", 0, 1000)
                for round = 1, 20 do
                    call(addr, "emit-as", "big", table.unpack(parts))
                    while bigs < round do check(pull()) end
                end
                collectgarbage("incremental", 200, 100)
                print(bad, bigs, nbad, refused > 0)
                """.trimIndent(),
        ).use { it.finish() }
        assertEquals("0\t20\t0\ttrue\n", out)
    }

    @Test
    fun `メインスレッドの結果を写す間に__gcがos_queueEventを呼んでも、結果は壊れない`() = timeout(limit) {
        // キューを開いた後、その場で待つ呼び出しと getNames・getMethods の結果を写す間の __gc から os.queueEvent を呼ぶ（#759）。
        // 写す間は kibi_lua_arena_hold で囲むので、os の回収（collect）は arena を使うもの（take_kernel_events）をしない。
        // 止めなければ、入れ子の take_kernel_events が arena を戻して取り直し、写している結果が壊れる
        val out = Run(
            """
            local addr, call = "$ADDRESS", peripheral.call
            local hits, junk = 0, {}
            local mt = {}
            mt.__gc = function()
                hits = hits + 1
                os.queueEvent("g")
                for k = 1, 8 do junk[k] = string.rep("Z", 1500 + k) end
                if hits < 3000 then setmetatable({}, mt) end
            end
            -- イベントのキューを先に開く（開いていないと、回収はカーネルのイベントを取り出さない）
            os.queueEvent("x"); os.pullEventRaw()
            for i = 1, 16 do setmetatable({}, mt) end
            local big = {}
            for i = 1, 32 do big[i] = string.rep(string.char(65 + i % 26), 1500) .. i end
            local bad = 0
            collectgarbage("incremental", 0, 1000)
            for round = 1, 20 do
                local a = { call(addr, "echo", table.unpack(big)) }
                for i = 1, 32 do if a[i] ~= big[i] then bad = bad + 1 end end
                local names = peripheral.getNames()
                if #names ~= 1 or names[1] ~= addr then bad = bad + 1 end
                local ms = peripheral.getMethods(addr)
                if #ms ~= 14 then bad = bad + 1 end
            end
            collectgarbage("incremental", 200, 100)
            print(bad, hits > 0)
            """.trimIndent(),
        ).use { it.finish() }
        assertEquals("0\ttrue\n", out)
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
