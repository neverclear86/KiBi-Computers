package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.device.KeyboardDevice
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.display.terminal.TerminalColors
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.event.RestoreEvents
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.tty.TtyMode
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.Written
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmHostImport
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.p1.P1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Kibi
import java.io.ByteArrayOutputStream
import java.time.Duration
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * Lua の term・colors・colours・keys・fs（#724。ADR 0724）を、実際の Endive と標準 OS のイメージの `/bin/lua`、カーネルで確かめる。
 * term は、コンソールに出たバイト列を端末（[AnsiTerminal]）に流して、セルの文字と色を見る
 */
class LuaTermFsKeysTest {
    private val limit = Duration.ofSeconds(180)

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun bytes(): ByteArray = synchronized(out) { out.toByteArray() }

        fun text(): String = String(bytes(), Charsets.UTF_8).replace("\r\n", "\n")
    }

    private class Result(val bytes: ByteArray, val text: String, val state: ProcessState, val exitCode: Int?) {
        /** コンソールの出力を [columns] x [rows] の端末に流した画面 */
        fun screen(columns: Int, rows: Int): TextBuffer = TextBuffer(columns, rows).also { AnsiTerminal(it, 1 shl 24).write(bytes) }
    }

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private fun Machine.put(path: String, text: String) {
        val bytes = text.toByteArray()
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /**
     * イメージ `os` を `/` に読み取り専用で、メモリの FS を `/home`・`/tmp` に書ける形でマウントし、[script] を `lua` で動かす。
     * コンソールは [columns] x [rows]。[setup] はマウントの後・起動の前にマシンへ足すもの（マウントを増やすなど）。[stdio] は標準入出力の割り当て。[driver] はテストの側の操作（出力を見てキーを押すなど）で、終わるまで tick ごとに呼ぶ。
     * [after] は終わった後、マシンを閉じる前に呼ぶ（TTY の状態を見るなど）
     */
    private fun lua(
        script: String,
        columns: Int = 20,
        rows: Int = 5,
        setup: (Machine) -> Unit = {},
        stdio: StdioSpec = StdioSpec.INHERIT,
        driver: (Machine, String) -> Unit = { _, _ -> },
        after: (Machine) -> Unit = {},
    ): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
        m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
        m.vfs.mount(VfsPath.parse("/home"), TestMemoryFileSystem(), readOnly = false, source = "test:home")
        m.vfs.mount(VfsPath.parse("/tmp"), TestMemoryFileSystem(), readOnly = false, source = "test:tmp")
        setup(m)
        m.resizeConsole(columns, rows)
        m.put("/home/t.lua", script.trimIndent() + "\n")
        val pid = m.exec(
            "/bin/lua",
            KernelDrivers.DEFAULT,
            SpawnOptions(stdio = stdio, args = listOf("lua", "/home/t.lua"), env = mapOf("PATH" to "/bin", "HOME" to "/home"), cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN),
        )
        eventuallyDescribe(150_000, { "終わらない: ${host.text()}" }) {
            h.scheduler.tick()
            driver(m, host.text())
            Thread.sleep(1)
            m.stateOf(pid).isTerminal
        }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        repeat(5) { h.scheduler.tick() }
        after(m)
        Result(host.bytes(), host.text(), m.stateOf(pid), exitCode)
    }

    private fun Result.assertOk(): Result {
        assertEquals(ProcessState.EXITED, state, text)
        assertEquals(0, exitCode, text)
        return this
    }

    private fun TextBuffer.row(y: Int): String = buildString { for (x in 0 until width) appendCodePoint(getCodePoint(x, y)) }

    private fun TextBuffer.fg(x: Int, y: Int): Int = getForeground(x, y) and 0xFFFFFF

    private fun TextBuffer.bg(x: Int, y: Int): Int = getBackground(x, y) and 0xFFFFFF

    // ---- colors・colours ----

    @Test
    fun `colorsとcoloursはCCの値と関数を持つ`() = timeout(limit) {
        val r = lua(
            """
            print(colors.white, colors.orange, colors.lightBlue, colors.gray, colors.lightGray, colors.black)
            print(colours.grey, colours.lightGrey, colours.gray, colours.lightGray, colours.red)
            print(colors.combine(colors.white, colors.magenta, colors.lightBlue), colors.subtract(colors.lime, colors.orange, colors.white))
            print(colors.test(colors.combine(colors.white, colors.lightBlue), colors.lightBlue), colors.test(colors.white, colors.red))
            print(string.format('%x', colors.packRGB(0.7, 0.2, 0.6)), colors.toBlit(colors.red), colors.fromBlit('e'), colors.fromBlit('z'))
            local r, g, b = colors.unpackRGB(0xb23399)
            print(string.format('%.3f %.3f %.3f', r, g, b))
            local n = 0
            for k, v in pairs(colours) do n = n + 1 end
            print(n, package.loaded.colors == colors, pcall(colors.toBlit, 0))
            """,
        ).assertOk()
        assertEquals(
            """
            1	2	8	128	256	32768
            128	256	nil	nil	16384
            13	32
            true	false
            b23399	e	16384	nil
            0.698 0.200 0.600
            24	true	false	Colour out of range
            """.trimIndent() + "\n",
            r.text.replace(Regex("\u001b\\[[0-9;?]*[A-Za-z]"), ""),
        )
    }

    // ---- keys ----

    private val pressedLine = Regex("k [A-Za-z0-9]+ [0-9]+ (true|false)")

    @Test
    fun `keysはGLFWのキーコードで、getNameで名前に戻る`() = timeout(limit) {
        val r = lua(
            """
            print(keys.a, keys.enter, keys.leftShift, keys.zero, keys.f12, keys.numPadEnter, keys.grave, keys.equals, keys["return"], keys.scollLock)
            print(keys.getName(65), keys.getName(keys.enter), keys.getName(281), keys.getName(1000), keys.escape, keys.rightSuper)
            """,
        ).assertOk()
        assertEquals("65\t257\t340\t48\t301\t335\t96\t61\t257\t281\na\tenter\tscrollLock\tnil\t256\t347\n", r.text)
    }

    @Test
    fun `キーボードの押下と離しはkeyとkey_upになり、unknownは届かない`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        val sent = mutableListOf<String>()
        var index = 0
        val all = Key.entries.filter { it != Key.UNKNOWN }
        val r = lua(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            while true do
              local e, code, held = os.pullEvent()
              if e == 'key' then print('k ' .. keys.getName(code) .. ' ' .. code .. ' ' .. tostring(held))
              elseif e == 'key_up' then print('up:' .. keys.getName(code)) if code == keys.menu then break end
              elseif e == 'key-pressed' or e == 'key-released' then print('raw ' .. e) end
            end
            """,
            driver = { m, text ->
                if (sent.isEmpty() && text.contains("ready")) {
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000724", keyboard))
                    sent += "attached"
                }
                // キューの上限（64）を越えないように、前のキーが届いてから次を押す
                if (sent.isNotEmpty() && index <= all.size && pressedLine.findAll(text).count() == index) {
                    if (index < all.size) {
                        keyboard.input(KeyboardEvent.KeyDown(Key.UNKNOWN, 0, false))
                        keyboard.input(KeyboardEvent.KeyDown(all[index], 0, index % 2 == 1))
                    } else {
                        keyboard.input(KeyboardEvent.KeyUp(Key.UNKNOWN, 0))
                        keyboard.input(KeyboardEvent.KeyUp(Key.MENU, 0))
                    }
                    index++
                }
            },
        ).assertOk()
        // キー待ちの間の TTY はエコーしない（#798）が、待っていない間に届いたキーは後から出力に混ざりうるので、行の頭ではなく形で拾う
        val pressed = pressedLine.findAll(r.text).map { it.value.split(" ") }.toList()
        assertEquals(all.size, pressed.size, r.text)
        // 番号は重ならず、名前に戻る
        assertEquals(all.size, pressed.map { it[2] }.toSet().size, r.text)
        assertTrue(pressed.none { it[1] == "nil" }, r.text)
        val byKey = all.zip(pressed).associate { (k, p) -> k to (p[1] to p[2].toInt()) }
        assertEquals("a" to 65, byKey[Key.A])
        assertEquals("zero" to 48, byKey[Key.DIGIT_0])
        assertEquals("enter" to 257, byKey[Key.ENTER])
        assertEquals("escape" to 256, byKey[Key.ESCAPE])
        assertEquals("leftShift" to 340, byKey[Key.LEFT_SHIFT])
        assertEquals("leftCtrl" to 341, byKey[Key.LEFT_CONTROL])
        assertEquals("rightSuper" to 347, byKey[Key.RIGHT_SUPER])
        assertEquals("numPadEnter" to 335, byKey[Key.KEYPAD_ENTER])
        assertEquals("grave" to 96, byKey[Key.GRAVE_ACCENT])
        assertEquals("world1" to 161, byKey[Key.WORLD_1])
        // 繰り返しは 3 つ目の値
        assertEquals(listOf("false", "true"), pressed.take(2).map { it[3] })
        assertEquals(listOf("up:menu"), Regex("up:[A-Za-z0-9]+").findAll(r.text).map { it.value }.toList())
        assertFalse(r.text.contains("raw "), r.text)
    }

    // ---- キー待ちの間の TTY（#798） ----

    /** キー待ちの間のモード（signals | nl-to-crnl） */
    private val KEYS_MODE = TtyMode(0x14)

    private fun Machine.consoleMode(): TtyMode = lock.withLockValue { consoleTtyLocked().mode }

    /** コンソールの TTY に残っている入力（読める入力・編集中の行・待ちの列）のバイト数 */
    private fun Machine.consoleLeftover(): Int = lock.withLockValue {
        val t = consoleTtyLocked()
        t.inputAvailable + t.editingLength + t.pendingInputBytes
    }

    @Test
    fun `キー待ちの間に打ったキーはエコーされず、遅れて届いた文字も溜まらず、終わるとcookedに戻る`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        var step = 0
        var mode: TtyMode? = null
        var leftover = -1
        val r = lua(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            while true do
              local _, code = os.pullEvent('key')
              print('k' .. code)
              if code == keys.q then break end
            end
            """,
            columns = 40,
            rows = 10,
            driver = { m, text ->
                when {
                    step == 0 && text.contains("ready") && m.consoleMode() == KEYS_MODE -> {
                        assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000798", keyboard))
                        keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                        step = 1
                    }
                    // 英字の文字は押下の後から届く（クライアントは Text を後で送る）。Lua が key を受けた後に送る
                    step == 1 && text.contains("k65") -> {
                        keyboard.input(KeyboardEvent.Text("zz"))
                        keyboard.input(KeyboardEvent.KeyDown(Key.ENTER, 0, false))
                        keyboard.input(KeyboardEvent.KeyDown(Key.ESCAPE, 0, false))
                        keyboard.input(KeyboardEvent.KeyDown(Key.UP, 0, false))
                        step = 2
                    }
                    step == 2 && text.contains("k265") -> {
                        // 終わる前に届いた分は、終わるときに読み捨てる（押下の後に送ると、終わった後に届くことがある）
                        keyboard.input(KeyboardEvent.Text("yy"))
                        keyboard.input(KeyboardEvent.KeyDown(Key.Q, 0, false))
                        step = 3
                    }
                }
            },
            after = { m ->
                mode = m.consoleMode()
                leftover = m.consoleLeftover()
            },
        ).assertOk()
        assertEquals(3, step, r.text)
        assertTrue(r.text.contains("k65\nk257\nk256\nk265\nk81\n"), r.text)
        for (s in listOf("zz", "yy", "^[", "\u001b[A")) assertFalse(r.text.contains(s), "$s がエコーされた: ${r.text}")
        assertEquals(TtyMode.COOKED, mode)
        assertEquals(0, leftover, "TTY に入力が残っている")
    }

    // ---- 同じ TTY のほかの読み手（#838） ----

    private fun Machine.fd0Flags(pid: Int): Long? = lock.withLockValue { kernel.processes[pid]?.handles?.get(0)?.flags }

    private fun Long?.nonblock(): Boolean = this != null && this and P1.FDFLAGS_NONBLOCK.toLong() != 0L

    /**
     * [script] を `lua` で、[others] の各コマンドと一緒に動かす。どれも親のない exec に [StdioSpec.INHERIT] を渡し、コンソールの TTY を
     * fd 0 に持たせる。[beforeRun] は exec の後、最初の tick の前に呼ぶ。[driver] は Lua が終わるまで tick ごとに呼ぶ。
     * [after] は Lua が終わった後、ほかのプロセスを殺す前に呼ぶ
     */
    private fun luaWith(
        script: String,
        others: List<List<String>>,
        files: Map<String, String> = emptyMap(),
        fuelSlice: Long? = null,
        luaDrivers: ProcessDriverFactory = KernelDrivers.DEFAULT,
        waitMillis: Long = 150_000,
        beforeRun: (Machine, Int) -> Unit = { _, _ -> },
        driver: (Machine, Int, List<Int>, String) -> Unit,
        after: (Machine, List<Int>) -> Unit = { _, _ -> },
    ): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024, fuelSlice = fuelSlice)
        m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
        m.vfs.mount(VfsPath.parse("/home"), TestMemoryFileSystem(), readOnly = false, source = "test:home")
        m.resizeConsole(40, 10)
        m.put("/home/t.lua", script.trimIndent() + "\n")
        for ((path, text) in files) m.put(path, text.trimIndent() + "\n")
        val env = mapOf("PATH" to "/bin", "HOME" to "/home")
        fun spawn(args: List<String>, drivers: ProcessDriverFactory = KernelDrivers.DEFAULT): Int = m.exec(
            "/bin/${args[0]}",
            drivers,
            SpawnOptions(stdio = StdioSpec.INHERIT, args = args, env = env, cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN),
        )
        val otherPids = others.map { spawn(it) }
        val pid = spawn(listOf("lua", "/home/t.lua"), luaDrivers)
        beforeRun(m, pid)
        eventuallyDescribe(waitMillis, { "終わらない: ${host.text()}" }) {
            h.scheduler.tick()
            driver(m, pid, otherPids, host.text())
            Thread.sleep(1)
            m.stateOf(pid).isTerminal
        }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        repeat(5) { h.scheduler.tick() }
        after(m, otherPids)
        for (o in otherPids) m.lock.withLockValue { m.killLocked(o, KillSignal.KILL) }
        repeat(5) { h.scheduler.tick() }
        Result(host.bytes(), host.text(), m.stateOf(pid), exitCode)
    }

    /**
     * Lua のプロセスの import を包んで、読み捨て（`kibi_lua_tty.c` の `drain`）の中を見て、順を決めて作る（#838）。本番のコードは変えない。
     *
     * - `poll_oneoff`: [steal] なら、読み捨ての「読めるかを見る」呼び出し（`ready_now`。fd_read と待たない時計の2つの購読）が fd 0 を
     *   「読める」と返した直後に、コンソールの TTY の入力を空にする（ほかの読み手が、見てから読むまでの間に読み切った順）
     * - `kibi:io/tty` の `set-mode`: [refillAfterCooked] なら、cooked に戻した直後に、確定した1行を TTY に入れる。戻した後の2回目の
     *   読み捨て（戻したことで残った塊を捨てるもの）にも、読めると見てから取られる順を通すため
     * - `fd_read`: Lua の fd 0 に NONBLOCK が立っている間（読み捨ての中）に呼ばれたら、そのときのほかのプロセスの fd 0 を見る
     */
    private inner class DrainProbe(
        private val machine: () -> Machine,
        private val steal: Boolean,
        private val refillAfterCooked: Boolean = false,
    ) {
        /** 読み切ったときの TTY のモード（読めるものがあって取った回数だけ並ぶ） */
        val stolenModes: MutableList<TtyMode> = java.util.concurrent.CopyOnWriteArrayList()

        /** cooked に戻した後に1行を入れた回数 */
        val refills = java.util.concurrent.atomic.AtomicInteger()

        /** 読み捨ての中（Lua の fd 0 に NONBLOCK が立っている間）の fd_read の回数 */
        val drainReads = java.util.concurrent.atomic.AtomicInteger()

        /** 読み捨ての中で、ほかのプロセスの fd 0 に NONBLOCK が立っていたら true */
        @Volatile
        var othersNonblockInDrain = false

        val drivers: ProcessDriverFactory = object : ProcessDriverFactory {
            override val waitImports: Set<String> get() = KernelDrivers.DEFAULT.waitImports

            override fun create(program: Program): ProcessDriver {
                val inner = KernelDrivers.DEFAULT.create(program)
                return object : ProcessDriver by inner {
                    override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
                        inner.configure(ctx, builder)
                        wrap(builder, P1.MODULE, "poll_oneoff") { original, instance, args ->
                            val result = original.call(instance, args)
                            if (steal && result[0] == 0L && instance.asyncState == WasmAsyncState.NORMAL && readyCheckSawReadable(instance, args)) {
                                stealAll()
                            }
                            result
                        }
                        wrap(builder, WasiP1Kibi.IO_TTY, "set-mode") { original, instance, args ->
                            val result = original.call(instance, args)
                            if (refillAfterCooked && args[1].toInt() == TtyMode.COOKED.bits) {
                                machine().offerConsoleInput("s\n".toByteArray())
                                refills.incrementAndGet()
                            }
                            result
                        }
                        wrap(builder, P1.MODULE, "fd_read") { original, instance, args ->
                            val m = machine()
                            m.lock.withLockValue {
                                if (m.kernel.processes[ctx.pid]?.handles?.get(0)?.flags.nonblock()) {
                                    drainReads.incrementAndGet()
                                    val others = m.kernel.processes.all().filter { it.pid != ctx.pid && !it.state.isTerminal }
                                    if (others.any { it.handles[0]?.flags.nonblock() }) othersNonblockInDrain = true
                                }
                            }
                            original.call(instance, args)
                        }
                    }
                }
            }
        }

        private fun stealAll() {
            val m = machine()
            val (took, mode) = m.lock.withLockValue {
                val tty = m.consoleTtyLocked()
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val r = tty.read(buf, 0, buf.size, m.wakeSink)
                    if (r !is ReadResult.Bytes || r.count == 0) break
                    total += r.count
                }
                total to tty.mode
            }
            if (took > 0) stolenModes.add(mode)
        }

        private fun wrap(
            builder: WasmInstanceConfig.Builder,
            module: String,
            name: String,
            body: (WasmHostFunction, pro.ikili.kibicomputers.core.runtime.WasmInstance, LongArray) -> LongArray,
        ) {
            val index = builder.hostImports.indexOfFirst { it.module == module && it.name == name }
            check(index >= 0) { "$module.$name がない" }
            val original = builder.hostImports[index]
            builder.hostImports[index] = WasmHostImport(
                original.module,
                original.name,
                original.type,
                WasmHostFunction { instance, args -> body(original.function, instance, args) },
            )
        }
    }

    /** `poll_oneoff(in, out, nsubscriptions, nevents)` が、fd 0 の fd_read と時計（待たない）の2つの購読で、fd 0 を「読める」と返したか */
    private fun readyCheckSawReadable(instance: pro.ikili.kibicomputers.core.runtime.WasmInstance, args: LongArray): Boolean {
        val mem = instance.memory ?: return false
        val subs = args[0].toInt()
        val events = args[1].toInt()
        if (args[2].toInt() != 2) return false
        // 購読は 48 バイト。userdata u64 @0、種類 u8 @8（0 が時計、1 が fd_read）、fd_read は fd u32 @16、時計は時計の ID u32 @16・待ち時間 u64 @24
        if (mem.readU8(subs + 8) != 1 || mem.readI32(subs + 16) != 0) return false
        if (mem.readU8(subs + 48 + 8) != 0 || mem.readI64(subs + 48 + 24) != 0L) return false
        val count = mem.readI32(args[3].toInt())
        // 事象は 32 バイト。userdata u64 @0、エラー u16 @8
        for (i in 0 until count) {
            val at = events + i * 32
            if (mem.readI64(at) == 1L && (mem.readU8(at + 8) or (mem.readU8(at + 9) shl 8)) == 0) return true
        }
        return false
    }

    private val timerLine = Regex("t([0-9]+)\\n")

    @Test
    fun `同じTTYのほかの読み手が先に読み切っても、読み捨ては次の入力を待たず、ほかの読み手のfdはNONBLOCKにならない`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        var attached = false
        var bursts = 0
        var waitFor = 0
        var luaChecked = 0
        var readerNonblock = false
        var luaNonblockWhileWaiting = false
        var mode: TtyMode? = null
        var readerAlive = false
        lateinit var machine: Machine
        val probe = DrainProbe({ machine }, steal = false)
        // 読めることを見てから別の呼び出しで待つ fd_read をすると、その間にもう1つの読み手が読み切ったとき、次の入力まで止まる（#838）。
        // キーと文字を1回送るたびに、次の入力を送らずに、Lua がタイマーで2回起きるのを待つ（止まれば、そこで進まなくなる）。
        // 割り込みは、Lua が見てから読むまでの間（燃料を確かめるところ1つ分）で CPU を譲り、その間にもう1つの読み手が残りを読んだときに起きる。
        // スライスを小さくし、読み手は1バイトずつ読ませ、文字の長さを変えて、行き来を増やす。窓が狭く順は決められないので、
        // 止まる順は次のテスト（poll_oneoff の包みで決めて作る）に任せる。ここで確かめるのは、
        // ほかの読み手と一緒でも止まらないこと、ほかの読み手の fd 0 が NONBLOCK にならないこと、待つ間の Lua の fd 0 が元に戻っていること。
        // 読み捨ての最中（Lua が NONBLOCK を立てている間）のほかの読み手の fd 0 は、fd_read の包み（DrainProbe）で見る。
        // tick の境目では、立てた NONBLOCK はもう戻っているので、driver・after で見るだけでは、漏れて戻ったものを区別できない
        val r = luaWith(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            local n = 0
            os.startTimer(0.05)
            while n < 70 do
              local e = os.pullEvent()
              if e == 'timer' then
                n = n + 1
                print('t' .. n)
                os.startTimer(0.02)
              end
            end
            print('done')
            """,
            // バッファなしで1バイトずつ読む（Lua は待たないので、TTY のモードには触らない）
            others = listOf(listOf("lua", "/home/r.lua")),
            files = mapOf(
                "/home/r.lua" to """
                io.stdin:setvbuf('no')
                while io.read(1) do end
                """,
            ),
            fuelSlice = 200,
            luaDrivers = probe.drivers,
            beforeRun = { m, _ -> machine = m },
            driver = { m, pid, others, text ->
                if (others.any { m.fd0Flags(it).nonblock() }) readerNonblock = true
                // イベントを待っている間（読み捨ての外）は、Lua の fd 0 も元のフラグに戻っている
                m.lock.withLockValue {
                    val p = m.kernel.processes[pid]
                    if (p != null && p.state == ProcessState.WAITING_EVENT && m.consoleTtyLocked().mode == KEYS_MODE) {
                        if (p.handles[0]?.flags.nonblock()) luaNonblockWhileWaiting = true
                        luaChecked++
                    }
                }
                if (!attached && text.contains("ready") && m.consoleMode() == KEYS_MODE) {
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000838", keyboard))
                    attached = true
                }
                val timers = timerLine.findAll(text).lastOrNull()?.groupValues?.get(1)?.toInt() ?: 0
                if (attached && bursts < 30 && timers >= waitFor) {
                    keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                    keyboard.input(KeyboardEvent.Text("b".repeat(1 + bursts)))
                    bursts++
                    waitFor = timers + 2
                }
            },
            after = { m, others ->
                mode = m.consoleMode()
                if (others.any { m.fd0Flags(it).nonblock() }) readerNonblock = true
                readerAlive = others.all { !m.stateOf(it).isTerminal }
            },
        ).assertOk()
        assertTrue(r.text.contains("t70\n"), r.text)
        assertTrue(r.text.contains("done\n"), r.text)
        assertEquals(30, bursts, r.text)
        assertTrue(readerAlive, "読み手が先に終わった: ${r.text}")
        assertTrue(luaChecked > 0, "Lua が待っているところを見られなかった")
        assertFalse(luaNonblockWhileWaiting, "待っている間の Lua の fd 0 が NONBLOCK のまま")
        assertFalse(readerNonblock, "もう1つの読み手の fd 0 が NONBLOCK になった")
        assertFalse(probe.othersNonblockInDrain, "読み捨ての最中に、もう1つの読み手の fd 0 が NONBLOCK になった")
        assertEquals(TtyMode.COOKED, mode)
    }

    @Test
    fun `読み捨てで読めると見た後にほかの読み手が読み切っても、次の入力を待たない`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        lateinit var machine: Machine
        val probe = DrainProbe({ machine }, steal = true)
        var attached = false
        var bursts = 0
        var waitFor = 0
        // 見てから別の呼び出しで待つ fd_read だと、取られた後の fd_read が次の入力まで止まる。止まれば Lua は先に進まず、次のキーも送られない。
        // poll_oneoff が「読める」と返した直後に TTY を空にする（ほかの読み手が間に読み切った順）。直す前のコードはここで止まる
        val r = luaWith(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            local n = 0
            os.startTimer(0.05)
            while n < 20 do
              local e = os.pullEvent()
              if e == 'timer' then
                n = n + 1
                print('t' .. n)
                os.startTimer(0.02)
              end
            end
            print('done')
            """,
            others = emptyList(),
            luaDrivers = probe.drivers,
            waitMillis = 30_000,
            beforeRun = { m, _ -> machine = m },
            driver = { m, _, _, text ->
                if (!attached && text.contains("ready") && m.consoleMode() == KEYS_MODE) {
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000838", keyboard))
                    attached = true
                }
                val timers = timerLine.findAll(text).lastOrNull()?.groupValues?.get(1)?.toInt() ?: 0
                if (attached && bursts < 8 && timers >= waitFor) {
                    keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                    keyboard.input(KeyboardEvent.Text("b".repeat(1 + bursts)))
                    bursts++
                    waitFor = timers + 2
                }
            },
        ).assertOk()
        assertTrue(r.text.contains("done\n"), r.text)
        assertEquals(8, bursts, r.text)
        assertTrue(probe.stolenModes.isNotEmpty(), "読み切る順を作れなかった（poll_oneoff の包みが効いていない）")
        // ここで通るのは待つ前の読み捨て（キー待ちのモードのまま）だけ。戻すときの読み捨ては次の2つのテストで確かめる
        assertTrue(probe.stolenModes.all { it == KEYS_MODE }, "${probe.stolenModes}")
    }

    /**
     * 戻すときの読み捨て（`kibi_lua_tty_restore`）を、読めると見てから取られる順で通す（#838）。[script] は `pullEvent('key')` を1回待ち、
     * その後は待たずに戻す（終わる、または標準入力を読む）。キー待ちの間に文字を先に送るので、戻すときの1回目の読み捨て（キー待ちの
     * モードのまま）が読めると見る。cooked に戻した直後に1行を入れるので、2回目の読み捨て（cooked）も読めると見る。どちらも見た直後に
     * TTY を空にする。直す前のコード（待つ fd_read）は、空になった TTY を読んで次の入力まで止まる。
     * ほかに、`os.sleep` で寝ているだけの `lua` を同じ TTY で動かし、読み捨ての最中にその fd 0 が NONBLOCK にならないことを見る
     */
    private fun restoreAfterSteal(script: String, onCooked: ((Machine) -> Unit)? = null): Pair<Result, DrainProbe> {
        val keyboard = KeyboardDevice()
        lateinit var machine: Machine
        val probe = DrainProbe({ machine }, steal = true, refillAfterCooked = true)
        var step = 0
        val r = luaWith(
            script,
            others = listOf(listOf("lua", "/home/s.lua")),
            files = mapOf("/home/s.lua" to "os.sleep(600)"),
            luaDrivers = probe.drivers,
            waitMillis = 30_000,
            beforeRun = { m, _ -> machine = m },
            driver = { m, pid, _, text ->
                val waiting = m.lock.withLockValue { m.kernel.processes[pid]?.state == ProcessState.WAITING_EVENT }
                if (step == 0 && text.contains("ready") && m.consoleMode() == KEYS_MODE && waiting) {
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000838", keyboard))
                    // 文字を押下より先に送る（キーを受けた後は待たないので、戻すときの読み捨てまで TTY に残る）
                    keyboard.input(KeyboardEvent.Text("zz"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                    step = 1
                } else if (step == 1 && onCooked != null && probe.stolenModes.size >= 2 && m.consoleMode() == TtyMode.COOKED) {
                    onCooked(m)
                    step = 2
                }
            },
        ).assertOk()
        return r to probe
    }

    private fun assertRestoreStolen(r: Result, probe: DrainProbe) {
        // 1回目はキー待ちのモードのまま、2回目は cooked に戻した後。どちらも取られた後の fd_read が待たずに戻ったので、ここまで進んだ
        assertEquals(listOf(KEYS_MODE, TtyMode.COOKED), probe.stolenModes.toList(), r.text)
        assertEquals(1, probe.refills.get(), r.text)
        assertTrue(probe.drainReads.get() >= 2, "読み捨ての最中の fd_read を見られなかった: ${probe.drainReads.get()}")
        assertFalse(probe.othersNonblockInDrain, "読み捨ての最中に、寝ている lua の fd 0 が NONBLOCK になった")
        assertFalse(r.text.contains("zz"), r.text)
    }

    @Test
    fun `終わるときの戻しは、読めると見た後にほかの読み手が読み切っても待たない`() = timeout(limit) {
        val (r, probe) = restoreAfterSteal(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            os.pullEvent('key')
            print('got')
            """,
        )
        assertTrue(r.text.contains("got\n"), r.text)
        assertRestoreStolen(r, probe)
    }

    @Test
    fun `io_readの前の戻しは、読めると見た後にほかの読み手が読み切っても待たない`() = timeout(limit) {
        val (r, probe) = restoreAfterSteal(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            os.pullEvent('key')
            print('read')
            local line = io.read()
            print('got[' .. tostring(line) .. ']')
            """,
            // 戻した後に1行を打つ。cooked に戻した直後に入れた行は2回目の読み捨てが取ったので、io.read には混ざらない
            onCooked = { m -> m.offerConsoleInput("hello\n".toByteArray()) },
        )
        assertTrue(r.text.contains("got[hello]"), r.text)
        assertRestoreStolen(r, probe)
    }

    @Test
    fun `fd 0が元からNONBLOCKなら、読み捨ての後もNONBLOCKのまま`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        var step = 0
        var flagsWhileWaiting: Long? = null
        val r = luaWith(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            os.pullEvent('key')
            print('got')
            os.startTimer(0.3)
            os.pullEvent('timer')
            print('done')
            """,
            others = emptyList(),
            beforeRun = { m, pid ->
                // 親が NONBLOCK を立てた fd 0 を受け継いだ形
                m.lock.withLockValue {
                    val hs = m.kernel.processes[pid]!!.handles
                    val e = hs[0]!!
                    hs.insertAt(0, e.copy(flags = e.flags or P1.FDFLAGS_NONBLOCK.toLong()))
                }
            },
            driver = { m, pid, _, text ->
                if (step == 0 && text.contains("ready") && m.consoleMode() == KEYS_MODE) {
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000838", keyboard))
                    keyboard.input(KeyboardEvent.Text("zz"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                    step = 1
                } else if (step == 1 && text.contains("got\n")) {
                    // timer を待つ前の読み捨てで消える分
                    keyboard.input(KeyboardEvent.Text("yy"))
                    step = 2
                } else if (step == 2) {
                    m.lock.withLockValue {
                        val p = m.kernel.processes[pid]
                        if (p != null && p.state == ProcessState.WAITING_EVENT) flagsWhileWaiting = p.handles[0]?.flags
                    }
                }
            },
        ).assertOk()
        assertTrue(r.text.contains("done"), r.text)
        assertTrue(flagsWhileWaiting.nonblock(), "元の NONBLOCK が外れた: $flagsWhileWaiting")
        assertFalse(r.text.contains("zz") || r.text.contains("yy"), r.text)
    }

    @Test
    fun `キー待ちの後のio_readはエコーと行の編集が戻り、待つ間に打ったものは混ざらない`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        var step = 0
        val r = lua(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            os.pullEvent('key')
            print('read')
            local line = io.read()
            print('got[' .. tostring(line) .. ']')
            """,
            columns = 40,
            rows = 10,
            driver = { m, text ->
                if (step == 0 && text.contains("ready") && m.consoleMode() == KEYS_MODE) {
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000798", keyboard))
                    // 文字を先に送る（押下の後に送ると、Lua が io.read でモードを戻した後に届くことがある。ADR 0798 の「残ること」）
                    keyboard.input(KeyboardEvent.Text("zz"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                    step = 1
                } else if (step == 1 && text.contains("\nread\n") && m.consoleMode() == TtyMode.COOKED) {
                    // io.read がモードを戻した後に打つ
                    keyboard.input(KeyboardEvent.Text("helo"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.BACKSPACE, 0, false))
                    keyboard.input(KeyboardEvent.Text("lo"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.ENTER, 0, false))
                    step = 2
                }
            },
        ).assertOk()
        assertTrue(r.text.contains("got[hello]"), r.text)
        // 打ったものはエコーされる
        assertTrue(r.text.contains("hel"), r.text)
        assertFalse(r.text.contains("zz"), r.text)
    }

    @Test
    fun `キー待ちの後の引数なしのdofileも、モードが戻ってから標準入力を読む`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        var step = 0
        val r = lua(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            os.pullEvent('key')
            print('read')
            local v = dofile()
            print('got' .. tostring(v))
            """,
            columns = 40,
            rows = 10,
            driver = { m, text ->
                if (step == 0 && text.contains("ready") && m.consoleMode() == KEYS_MODE) {
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000798", keyboard))
                    keyboard.input(KeyboardEvent.Text("zz"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                    step = 1
                } else if (step == 1 && text.contains("\nread\n") && m.consoleMode() == TtyMode.COOKED) {
                    // dofile がモードを戻した後に打つ（canonical なので Ctrl+D で EOF）
                    m.offerConsoleInput("return 42\n\u0004".toByteArray())
                    step = 2
                }
            },
        ).assertOk()
        assertEquals(2, step, r.text)
        assertTrue(r.text.contains("got42"), r.text)
        // 打ったスクリプトはエコーされ、待つ間に打ったものは混ざらない
        assertTrue(r.text.contains("return 42"), r.text)
        assertFalse(r.text.contains("zz"), r.text)
    }

    @Test
    fun `キー待ちの後にerrorやos_exitで終わってもcookedに戻る`() = timeout(limit) {
        for (end in listOf("error('boom')", "os.exit(3)")) {
            val keyboard = KeyboardDevice()
            var sent = false
            var mode: TtyMode? = null
            var leftover = -1
            val r = lua(
                """
                os.queueEvent('ready')
                os.pullEvent('ready')
                print('ready')
                os.pullEvent('key')
                $end
                """,
                driver = { m, text ->
                    if (!sent && text.contains("ready") && m.consoleMode() == KEYS_MODE) {
                        assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000798", keyboard))
                        keyboard.input(KeyboardEvent.Text("bb"))
                        keyboard.input(KeyboardEvent.KeyDown(Key.B, 0, false))
                        sent = true
                    }
                },
                after = { m ->
                    mode = m.consoleMode()
                    leftover = m.consoleLeftover()
                },
            )
            assertEquals(ProcessState.EXITED, r.state, r.text)
            assertFalse(r.text.contains("bb"), r.text)
            assertEquals(TtyMode.COOKED, mode, end)
            assertEquals(0, leftover, end)
        }
    }

    @Test
    fun `parallelのコルーチンがkeyを待つときも、メインのfilterなしの待ちで切れ、エコーされない`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        var step = 0
        var mode: TtyMode? = null
        var leftover = -1
        val r = lua(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            parallel.waitForAny(function()
              while true do
                local _, code = os.pullEvent('key')
                print('k' .. code)
                if code == keys.q then return end
              end
            end)
            """,
            columns = 40,
            rows = 10,
            driver = { m, text ->
                when {
                    // 'ready' の待ちでは切らないので、KEYS_MODE になったのは parallel のメインの待ち
                    step == 0 && text.contains("ready") && m.consoleMode() == KEYS_MODE -> {
                        assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000798", keyboard))
                        keyboard.input(KeyboardEvent.KeyDown(Key.A, 0, false))
                        step = 1
                    }
                    step == 1 && text.contains("k65") -> {
                        keyboard.input(KeyboardEvent.Text("zz"))
                        keyboard.input(KeyboardEvent.KeyDown(Key.ENTER, 0, false))
                        keyboard.input(KeyboardEvent.KeyDown(Key.UP, 0, false))
                        step = 2
                    }
                    step == 2 && text.contains("k265") -> {
                        keyboard.input(KeyboardEvent.Text("yy"))
                        keyboard.input(KeyboardEvent.KeyDown(Key.Q, 0, false))
                        step = 3
                    }
                }
            },
            after = { m ->
                mode = m.consoleMode()
                leftover = m.consoleLeftover()
            },
        ).assertOk()
        assertEquals(3, step, r.text)
        assertTrue(r.text.contains("k65\nk257\nk265\nk81\n"), r.text)
        for (s in listOf("zz", "yy", "\u001b[A")) assertFalse(r.text.contains(s), "$s がエコーされた: ${r.text}")
        assertEquals(TtyMode.COOKED, mode)
        assertEquals(0, leftover, "TTY に入力が残っている")
    }

    @Test
    fun `pcallでキー待ちのエラーを受けた後や、timerとsleepだけを待った後も、次のio_readの前にモードが戻る`() = timeout(limit) {
        val keyboard = KeyboardDevice()
        var step = 0
        var modeAfterCatch: TtyMode? = null
        var modeWhileTimer: TtyMode? = null
        val r = lua(
            """
            os.queueEvent('ready')
            os.pullEvent('ready')
            print('ready')
            os.queueEvent('terminate')
            local ok, err = pcall(os.pullEvent, 'key')
            print('caught ' .. tostring(ok) .. ' ' .. tostring(err))
            os.startTimer(0.5)
            os.pullEvent('timer')
            print('slept1')
            os.sleep(0.3)
            print('read')
            local line = io.read()
            print('got[' .. tostring(line) .. ']')
            """,
            columns = 40,
            rows = 10,
            driver = { m, text ->
                if (step == 0 && text.contains("caught false Terminated")) {
                    // pull() の出口では戻さないので、エラーを受けた後も切ったまま（'timer' の待ちでも切ったまま）
                    modeAfterCatch = m.consoleMode()
                    assertTrue(m.devices.attach("00000000-0000-4000-8000-000000000798", keyboard))
                    // timer を待つ間に打ったもの（エコーされず、待ちの前か読む前に捨てる）
                    keyboard.input(KeyboardEvent.Text("zz"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.Z, 0, false))
                    step = 1
                } else if (step == 1 && text.contains("slept1")) {
                    // os.sleep の間に打ったもの（pull() を通らないので、読む前の読み捨てで消える）
                    modeWhileTimer = m.consoleMode()
                    keyboard.input(KeyboardEvent.Text("ww"))
                    step = 2
                } else if (step == 2 && text.contains("\nread\n") && m.consoleMode() == TtyMode.COOKED) {
                    keyboard.input(KeyboardEvent.Text("hello"))
                    keyboard.input(KeyboardEvent.KeyDown(Key.ENTER, 0, false))
                    step = 3
                }
            },
        ).assertOk()
        assertEquals(3, step, r.text)
        assertEquals(KEYS_MODE, modeAfterCatch, r.text)
        assertEquals(KEYS_MODE, modeWhileTimer, r.text)
        assertTrue(r.text.contains("got[hello]"), r.text)
        // io.read の前に戻ったので、打ったものはエコーされ、待つ間に打ったものは混ざらない
        assertTrue(r.text.contains("hello\n"), r.text)
        for (s in listOf("zz", "ww")) assertFalse(r.text.contains(s), "$s が混ざった: ${r.text}")
    }

    @Test
    fun `timerだけを待つときはモードを変えず、打ったものは先打ちとして残る`() = timeout(limit) {
        var sent = false
        var modeWhileWaiting: TtyMode? = null
        var leftover = -1
        lua(
            """
            os.startTimer(0.5)
            print('ready')
            os.pullEvent('timer')
            """,
            driver = { m, text ->
                if (!sent && text.contains("ready")) {
                    modeWhileWaiting = m.consoleMode()
                    m.offerConsoleInput("ab".toByteArray())
                    sent = true
                }
            },
            after = { m -> leftover = m.consoleLeftover() },
        ).assertOk()
        assertEquals(TtyMode.COOKED, modeWhileWaiting)
        assertEquals(2, leftover)
    }

    @Test
    fun `標準入力が端末でないとき（閉じた fd）は、キーを待ってもモードを変えず、打ったものも捨てない`() = timeout(limit) {
        var sent = false
        var modeWhileWaiting: TtyMode? = null
        var leftoverWhileWaiting = -1
        var mode: TtyMode? = null
        lua(
            """
            os.startTimer(0.5)
            print('ready')
            os.pullEvent()
            """,
            stdio = StdioSpec(stdin = StdioSource.Closed, stdout = StdioSource.Console, stderr = StdioSource.Console),
            driver = { m, text ->
                if (!sent && text.contains("ready")) {
                    modeWhileWaiting = m.consoleMode()
                    m.offerConsoleInput("ab".toByteArray())
                    sent = true
                } else if (sent && leftoverWhileWaiting < 0) {
                    leftoverWhileWaiting = m.consoleLeftover()
                }
            },
            after = { m -> mode = m.consoleMode() },
        ).assertOk()
        assertTrue(sent)
        assertEquals(TtyMode.COOKED, modeWhileWaiting)
        assertEquals(TtyMode.COOKED, mode)
        // 待っている間（タイマーが鳴るまで）、打ったものは TTY に残っている
        assertEquals(2, leftoverWhileWaiting)
    }

    @Test
    fun `元のモードがcookedでないときは、キーを待ってもモードを変えず、終わっても元のまま`() = timeout(limit) {
        // ほかのプログラムが変えたモード（echo を切った cooked）
        val custom = TtyMode(TtyMode.ALL and TtyMode.ECHO.inv())
        var sent = false
        var modeWhileWaiting: TtyMode? = null
        var mode: TtyMode? = null
        var leftoverWhileWaiting = -1
        lua(
            """
            os.startTimer(0.5)
            print('ready')
            os.pullEvent()
            """,
            setup = { m -> m.lock.withLockValue { m.consoleTtyLocked().setMode(custom, m.wakeSink) } },
            driver = { m, text ->
                if (!sent && text.contains("ready")) {
                    modeWhileWaiting = m.consoleMode()
                    m.offerConsoleInput("ab\n".toByteArray())
                    sent = true
                } else if (sent && leftoverWhileWaiting < 0) {
                    leftoverWhileWaiting = m.consoleLeftover()
                }
            },
            after = { m -> mode = m.consoleMode() },
        ).assertOk()
        assertTrue(sent)
        assertEquals(custom, modeWhileWaiting)
        assertEquals(custom, mode)
        // 打ったものは捨てられていない（canonical の行が確定して読める）
        assertEquals(3, leftoverWhileWaiting)
    }

    /**
     * 標準 OS の対話型 `sh` から `lua` でキーを待たせ、[stop] で止めた後に、次のコマンドが動いている間は端末が cooked に戻っている（`sh` が戻した）ことを確かめる
     * （シグナルで殺されると atexit が走らないので、戻すのは `sh` の仕事。ADR 0798 の「殺されたとき」）
     */
    private fun assertShRestoresAfterStop(stop: (Machine, Int) -> Unit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024)
            m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
            m.vfs.mount(VfsPath.parse("/home"), TestMemoryFileSystem(), readOnly = false, source = "test:home")
            m.vfs.mount(VfsPath.parse("/tmp"), TestMemoryFileSystem(), readOnly = false, source = "test:tmp")
            m.resizeConsole(40, 10)
            m.put("/home/t.lua", "print('ready')\nwhile true do os.pullEvent('key') end\n")
            val sh = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh"), env = mapOf("PATH" to "/bin", "HOME" to "/home"), cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN))

            fun luaPid() = m.lock.withLockValue { m.kernel.processes.all().firstOrNull { !it.state.isTerminal && it.args.firstOrNull() == "lua" }?.pid }
            fun step(describe: () -> String, done: () -> Boolean) = eventuallyDescribe(150_000, describe) {
                h.scheduler.tick()
                Thread.sleep(1)
                done()
            }

            step({ "プロンプトが出ない: ${host.text()}" }) { host.text().endsWith("$ ") }
            m.offerConsoleInput("lua /home/t.lua\n".toByteArray())
            step({ "キー待ちに入らない: ${host.text()} / ${m.consoleMode()}" }) { host.text().contains("ready") && luaPid() != null && m.consoleMode() == KEYS_MODE }
            val lua = luaPid()!!
            // 前景が lua になってから止める
            step({ "lua が前景にならない" }) { m.lock.withLockValue { m.consoleTtyLocked().foregroundPid } == lua }
            val seen = host.text().length
            stop(m, lua)
            step({ "プロンプトに戻らない: ${host.text()}" }) { host.text().length > seen && host.text().endsWith("$ ") }
            assertTrue(luaPid() == null, "lua が残っている")

            // 次のコマンドが動いている間、端末は cooked に戻っている（sh は行を編集する間だけモードを変える）
            fun sleepPid() = m.lock.withLockValue { m.kernel.processes.all().firstOrNull { !it.state.isTerminal && it.args.firstOrNull() == "sleep" }?.pid }
            m.offerConsoleInput("sleep 100\n".toByteArray())
            step({ "sleep が動かない: ${host.text()}" }) { sleepPid() != null }
            step({ "モードが戻らない: ${m.consoleMode()}" }) { m.consoleMode() == TtyMode.COOKED }
            val mark = host.text().length
            m.offerConsoleInput(byteArrayOf(0x03))
            step({ "プロンプトに戻らない: ${host.text()}" }) { host.text().length > mark && host.text().endsWith("$ ") }
            m.offerConsoleInput("exit\n".toByteArray())
            step({ "終わらない: ${host.text()}" }) { m.stateOf(sh).isTerminal }
        }
    }

    @Test
    fun `キー待ちの間にCtrl+Cで止めると、shが端末をcookedに戻す`() = timeout(limit) {
        assertShRestoresAfterStop { m, _ -> m.offerConsoleInput(byteArrayOf(0x03)) }
    }

    @Test
    fun `キー待ちの間にkillで止めると、shが端末をcookedに戻す`() = timeout(limit) {
        assertShRestoresAfterStop { m, lua -> m.lock.withLockValue { m.killLocked(lua, KillSignal.TERMINATE) } }
    }

    // ---- term ----

    @Test
    fun `termは位置と色を指定して書き、右端と画面の外は切る`() = timeout(limit) {
        val r = lua(
            """
            term.setBackgroundColor(colors.blue)
            term.clear()
            term.setCursorPos(3, 2)
            term.setTextColor(colors.red)
            term.write('hi')
            local x, y = term.getCursorPos()
            term.setCursorPos(18, 3)
            term.write('abcdef')
            local x2 = term.getCursorPos()
            term.setCursorPos(-1, 4)
            term.write('xyz')
            term.setCursorPos(1, 9)
            term.write('never')
            term.setCursorPos(1, 5)
            term.setTextColor(colors.white)
            term.setBackgroundColor(colors.black)
            term.write(x .. ',' .. y .. ',' .. x2)
            local w, h = term.getSize()
            term.write(' ' .. w .. 'x' .. h)
            """,
        ).assertOk()
        val s = r.screen(20, 5)
        assertEquals("  hi                ", s.row(1))
        assertEquals("                 abc", s.row(2))
        assertEquals("z                   ", s.row(3))
        assertEquals("5,2,24 20x5         ", s.row(4))
        // 既定のパレットのままの色は、端末の基本の 16 色（red は 1 番、blue は 4 番、white は 15 番、black は 0 番）
        assertEquals(TerminalColors.palette(1), s.fg(2, 1))
        assertEquals(TerminalColors.palette(4), s.bg(0, 0))
        assertEquals(TerminalColors.palette(4), s.bg(2, 1))
        assertEquals(TerminalColors.palette(15), s.fg(0, 4))
        assertEquals(TerminalColors.palette(0), s.bg(0, 4))
    }

    @Test
    fun `term_scrollは中身を上下にずらし、空いた行を背景色で埋め、行数を画面の高さで切る`() = timeout(limit) {
        val r = lua(
            """
            for i = 1, 5 do term.setCursorPos(1, i) term.write('line' .. i) end
            term.setCursorPos(7, 4)
            term.setBackgroundColor(colors.blue)
            term.scroll(2)
            term.scroll(-1)
            term.scroll(0)
            local x, y = term.getCursorPos()
            assert(x == 7 and y == 4, x .. ',' .. y)
            """,
        ).assertOk()
        // 2 行上げて [3,4,5,_,_]、1 行下げて [_,3,4,5,_]。0 行は何も書かない
        val s = r.screen(20, 5)
        assertEquals(" ".repeat(20), s.row(0))
        assertEquals("line3               ", s.row(1))
        assertEquals("line5               ", s.row(3))
        assertEquals(" ".repeat(20), s.row(4))
        assertEquals(TerminalColors.palette(4), s.bg(0, 0))
        assertEquals(TerminalColors.palette(4), s.bg(19, 4))
        assertEquals(TerminalColors.palette(0), s.bg(0, 1))
        assertTrue(r.text.contains("\u001b[2S") && r.text.contains("\u001b[1T"), r.text)
        assertFalse(r.text.contains("[0S") || r.text.contains("[0T"), r.text)

        // 画面の高さより多い行数は、高さで切る
        val big = lua("term.scroll(99) term.scroll(-99)").assertOk()
        assertTrue(big.text.contains("\u001b[5S") && big.text.contains("\u001b[5T"), big.text)
        assertFalse(big.text.contains("[99"), big.text)
    }

    @Test
    fun `term_clearLineはカーソルの行を背景色で消し、列を端に寄せ、画面の外の行では何も書かない`() = timeout(limit) {
        val r = lua(
            """
            for i = 1, 6 do term.setCursorPos(1, i) term.write(string.rep(tostring(i), 20)) end
            term.setBackgroundColor(colors.red)
            term.setCursorPos(5, 2)
            term.clearLine()
            term.setCursorPos(30, 3)
            term.clearLine()
            local x = term.getCursorPos()
            assert(x == 30, x)
            term.setCursorPos(-3, 4)
            term.clearLine()
            term.setCursorPos(1, 0)
            term.clearLine()
            term.setCursorPos(1, 7)
            term.clearLine()
            """,
            rows = 6,
        ).assertOk()
        val s = r.screen(20, 6)
        assertEquals("1".repeat(20), s.row(0))
        for (y in 1..3) {
            assertEquals(" ".repeat(20), s.row(y))
            assertEquals(TerminalColors.palette(1), s.bg(0, y))
            assertEquals(TerminalColors.palette(1), s.bg(19, y))
        }
        assertEquals("5".repeat(20), s.row(4))
        // 画面の下の外（7 行目）を 6 行目に寄せて消さない
        assertEquals("6".repeat(20), s.row(5))
        // 列は 1〜幅に寄せた CUP
        assertTrue(r.text.contains("\u001b[2;5H\u001b[2K"), r.text)
        assertTrue(r.text.contains("\u001b[3;20H\u001b[2K"), r.text)
        assertTrue(r.text.contains("\u001b[4;1H\u001b[2K"), r.text)
        assertFalse(r.text.contains("[0;1H") || r.text.contains("[7;1H"), r.text)
        assertEquals(3, Regex("\u001b\\[2K").findAll(r.text).count(), r.text)
    }

    @Test
    fun `termのwriteは制御文字を置き換え、全角は2セルで数え、端にかかる半分は空白にする`() = timeout(limit) {
        val r = lua(
            """
            term.setCursorPos(1, 1)
            term.write('a\27[2Jb\tc\128')
            term.setCursorPos(1, 2)
            term.write('あい')
            print()
            print((term.getCursorPos()))
            term.setCursorPos(1, 3)
            term.write(('#'):rep(20))
            term.setCursorPos(20, 3)
            term.write('う')
            term.setCursorPos(0, 4)
            term.write('えx')
            """,
            rows = 6,
        ).assertOk()
        val s = r.screen(20, 6)
        assertEquals("a�[2Jb�c�", s.row(0).trimEnd())
        assertEquals('あ'.code, s.getCodePoint(0, 1))
        assertEquals('い'.code, s.getCodePoint(2, 1))
        assertEquals("#".repeat(19) + " ", s.row(2))
        assertEquals(" x", s.row(3).trimEnd())
        assertTrue(r.text.contains("\n5\n"), r.text)
    }

    @Test
    fun `blitは文字ごとに色を付け、長さが違うとエラーにする`() = timeout(limit) {
        val r = lua(
            """
            term.setCursorPos(1, 1)
            term.blit('abc', 'e0b', 'f5f')
            print(pcall(term.blit, 'ab', 'e', 'ff'))
            print(term.getTextColor(), term.getBackgroundColor(), term.isColor(), term.isColour())
            """,
            rows = 12,
        ).assertOk()
        val s = r.screen(20, 12)
        assertEquals("abc", s.row(0).take(3))
        assertEquals(TerminalColors.palette(1), s.fg(0, 0))
        assertEquals(TerminalColors.palette(15), s.fg(1, 0))
        assertEquals(TerminalColors.palette(4), s.fg(2, 0))
        assertEquals(TerminalColors.palette(10), s.bg(1, 0))
        assertTrue(r.text.contains("false\tArguments must be the same length"), r.text)
        assertTrue(r.text.contains("1\t32768\ttrue\ttrue"), r.text)
    }

    @Test
    fun `setTextColorの後のprintはその色で、終わるとSGRとカーソルの表示が戻る`() = timeout(limit) {
        val r = lua(
            """
            term.setTextColor(colors.lime)
            term.setCursorBlink(false)
            term.setCursorPos(1, 1)
            print('green')
            term.setPaletteColor(colors.lime, 0x123456)
            print('dark')
            print(term.getCursorBlink(), term.getPaletteColor(colors.lime))
            print(term.nativePaletteColor(colors.lime))
            """,
            rows = 12,
        ).assertOk()
        val s = r.screen(20, 12)
        assertEquals("green", s.row(0).trimEnd())
        assertEquals(TerminalColors.palette(10), s.fg(0, 0))
        // パレットを変えた色は RGB で書く
        assertEquals(0x123456, s.fg(0, 1))
        assertTrue(r.text.contains("false\t0.0705"), r.text)
        val raw = String(r.bytes, Charsets.UTF_8)
        assertTrue(raw.endsWith("\u001b[0m\u001b[?25h"), raw)
    }

    @Test
    fun `term_redirectは出力先のテーブルに渡し、元に戻せる`() = timeout(limit) {
        val r = lua(
            """
            local log = {}
            local target = { write = function(s) log[#log + 1] = 'w:' .. s end, getSize = function() return 7, 3 end }
            local old = term.redirect(target)
            print(old == term.native(), term.current() == target)
            term.write('hey')
            print(term.getSize())
            print(pcall(term.clear))
            print(term.redirect(old) == target, term.current() == term.native())
            print(table.concat(log, ','), pcall(term.redirect, term))
            """,
        ).assertOk()
        assertEquals(
            """
            true	true
            7	3
            false	Redirect object is missing method clear.
            true	true
            w:hey	false	term is not a recommended redirect target, try term.current() instead
            """.trimIndent() + "\n",
            r.text,
        )
    }

    @Test
    fun `termを使わないプログラムは、エスケープを書かない`() = timeout(limit) {
        val r = lua("print(type(term.write), type(fs.list), keys.a)").assertOk()
        assertEquals("function\tfunction\t65\n", String(r.bytes, Charsets.UTF_8).replace("\r\n", "\n"))
    }

    @Test
    fun `getSizeはコンソールの大きさで、大きさが変わるとterm_resizeが届く`() = timeout(limit) {
        var resized = false
        val r = lua(
            """
            print(term.getSize())
            os.queueEvent('x')
            os.pullEvent('x')
            print('ready')
            os.pullEvent('term_resize')
            print(term.getSize())
            """,
            driver = { m, text ->
                if (!resized && text.contains("ready")) {
                    m.resizeConsole(30, 7)
                    resized = true
                }
            },
        ).assertOk()
        assertEquals("20\t5\nready\n30\t7\n", r.text)
    }

    /** 数字の範囲の表（`{0x1100, 0x115F}` や `.{ 0x1100, 0x115F }`）を読む */
    private fun ranges(source: String, start: String): List<Pair<Int, Int>> {
        val body = source.substring(source.indexOf(start)).substringBefore("};")
        return Regex("0x([0-9A-Fa-f]+),[ ]*0x([0-9A-Fa-f]+)").findAll(body).map { it.groupValues[1].toInt(16) to it.groupValues[2].toInt(16) }.toList()
    }

    @Test
    fun `termの幅の表は標準OSのtext_zigと同じ（全角はCharWidthと同じ）`() {
        val c = java.io.File("src/main/wasm/lua/kibi_lua_term.c").readText()
        val zig = java.io.File("../os/lib/kibi/text.zig").readText()
        val wideC = ranges(c, "WIDE[][2]")
        val zeroC = ranges(c, "ZERO[][2]")
        assertEquals(ranges(zig, "wide_ranges"), wideC)
        assertEquals(ranges(zig, "zero_ranges"), zeroC)
        assertTrue(wideC.isNotEmpty() && zeroC.isNotEmpty())
        for ((lo, hi) in wideC) {
            assertTrue(pro.ikili.kibicomputers.core.display.CharWidth.isWide(lo) && pro.ikili.kibicomputers.core.display.CharWidth.isWide(hi))
        }
    }

    // ---- fs ----

    @Test
    fun `fsのパスは根から数え、combine・getName・getDirはCCと同じ`() = timeout(limit) {
        val r = lua(
            """
            print(fs.combine('/rom/programs', '../apis', 'parallel.lua'), fs.combine('a', '..', '..', 'b'), fs.combine('a\\b/./c/'))
            print(fs.getName('rom/startup.lua'), fs.getName(''), fs.getDir('rom/startup.lua'), fs.getDir('a'), fs.getDir('/'))
            print(fs.exists('home/t.lua'), fs.exists('/home/t.lua'), fs.exists('t.lua'), fs.isDir('bin'), fs.isDir('bin/lua'))
            print(pcall(fs.exists, '../x'))
            """,
        ).assertOk()
        assertEquals(
            """
            rom/apis/parallel.lua	../b	a/b/c
            startup.lua	root	rom		..
            true	true	false	true	false
            false	/../x: Invalid Path
            """.trimIndent() + "\n",
            r.text.replace(Regex("[^\\n]*\\.lua:\\d+: "), ""),
        )
    }

    @Test
    fun `fsでファイルとディレクトリを作り、写し、動かし、消す`() = timeout(limit) {
        val r = lua(
            """
            fs.makeDir('/tmp/a/b/c')
            local f = fs.open('/tmp/a/b/c/x.txt', 'w')
            f.write('hello')
            f.writeLine(' world')
            f.close()
            print(pcall(f.write, 'x'))
            local g = fs.open('tmp/new/dir/y.bin', 'wb')
            g.write(65) g.write(66) g.close()
            print(table.concat(fs.list('/tmp'), ','), table.concat(fs.list('/tmp/new/dir'), ','))
            print(fs.getSize('/tmp/a/b/c/x.txt'), fs.getSize('/tmp/a'), fs.isDir('/tmp/a/b'))
            local h = fs.open('/tmp/a/b/c/x.txt', 'r')
            print(h.readLine(), h.readLine(), h.read())
            h.close()
            local b = fs.open('/tmp/new/dir/y.bin', 'rb')
            print(b.read(), b.read(1), b.read())
            b.close()
            fs.copy('/tmp/a', '/tmp/copy/a')
            print(fs.exists('/tmp/copy/a/b/c/x.txt'), pcall(fs.copy, '/tmp/a', '/tmp/copy/a'))
            print(pcall(fs.copy, '/tmp/a', '/tmp/a/b/inside'))
            fs.move('/tmp/copy', '/tmp/moved')
            print(fs.exists('/tmp/copy'), fs.exists('/tmp/moved/a/b/c/x.txt'))
            fs.delete('/tmp/moved')
            fs.delete('/tmp/never')
            print(fs.exists('/tmp/moved'), table.concat(fs.list('/tmp'), ','))
            print(fs.open('/tmp/none', 'r'))
            print(pcall(fs.open, '/tmp/x', 'q'))
            print(pcall(fs.list, '/tmp/none'))
            print(pcall(fs.list, '/tmp/a/b/c/x.txt'))
            print(pcall(fs.makeDir, '/tmp/a/b/c/x.txt'))
            local a = fs.attributes('/tmp/a/b/c/x.txt')
            print(a.size, a.isDir, a.isReadOnly, type(a.modified), type(a.created))
            local t = fs.open('/tmp/a/b/c/x.txt', 'a') t.write('!') t.close()
            print(fs.open('/tmp/a/b/c/x.txt', 'r').readAll())
            """,
        ).assertOk()
        val out = r.text.replace(Regex("[^\\n\\t]*\\.lua:\\d+: "), "")
        assertEquals(
            """
            false	attempt to use a closed file
            a,new	y.bin
            12	0	true
            hello world	nil	nil
            65	B	nil
            true	false	/tmp/copy/a: File exists
            false	Can't copy a directory inside itself
            false	true
            false	a,new
            nil	/tmp/none: No such file
            false	Unsupported mode
            false	/tmp/none: No such file
            false	/tmp/a/b/c/x.txt: Not a directory
            false	/tmp/a/b/c/x.txt: File exists
            12	false	false	number	number
            hello world
            !
            """.trimIndent() + "\n",
            out,
        )
    }

    /** 書いたバイトの合計が [limit] を超える書き込みを `NOSPC` にする FS（消しても空きは戻らないので、後の copy は最初のファイルで失敗する） */
    private class SmallFileSystem(private val limit: Int, private val inner: TestMemoryFileSystem = TestMemoryFileSystem()) : FileSystem by inner {
        private var used = 0

        private fun reserve(len: Int) {
            if (used + len > limit) fsError(Errno.NOSPC)
            used += len
        }

        override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int {
            reserve(len)
            return inner.write(node, position, src, off, len)
        }

        override fun append(node: Long, src: ByteArray, off: Int, len: Int): Written {
            reserve(len)
            return inner.append(node, src, off, len)
        }
    }

    @Test
    fun `fs_moveはマウントをまたぐと写してから消し、途中で失敗すると写した分を残さない`() = timeout(limit) {
        val r = lua(
            """
            local function put(p, s) local f = fs.open(p, 'w') f.write(s) f.close() end
            put('/tmp/m/a.txt', 'aaaaaa')
            put('/tmp/m/sub/b.txt', 'bbbbbb')
            fs.move('/tmp/m', '/home/out/m')
            print(fs.exists('/tmp/m'), fs.open('/home/out/m/a.txt', 'r').readAll(), fs.open('/home/out/m/sub/b.txt', 'r').readAll())
            print(pcall(fs.move, '/home/out/m', '/small/m'))
            print(fs.exists('/small/m'), fs.exists('/home/out/m/a.txt'), fs.exists('/home/out/m/sub/b.txt'))
            print(pcall(fs.copy, '/home/out/m', '/small/c'))
            print(fs.exists('/small/c'), #fs.list('/small'))
            """,
            setup = { m -> m.vfs.mount(VfsPath.parse("/small"), SmallFileSystem(limit = 10), readOnly = false, source = "test:small") },
        ).assertOk()
        val out = r.text.replace(Regex("[^\\n\\t]*\\.lua:\\d+: "), "")
        assertEquals(
            """
            false	aaaaaa	bbbbbb
            false	/small/m/sub/b.txt: Out of space
            false	true	true
            false	/small/c/a.txt: Out of space
            false	0
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `fsは読み取り専用の根を断り、容量とドライブをマウントから引く`() = timeout(limit) {
        val r = lua(
            """
            print(fs.isReadOnly('/'), fs.isReadOnly('bin/lua'), fs.isReadOnly('/tmp'), fs.isReadOnly('/tmp/none'))
            print(pcall(fs.delete, '/bin/lua'))
            print(pcall(fs.delete, '/'))
            print(fs.open('/bin/new', 'w'))
            print(fs.getDrive('/'), fs.getDrive('/bin'), fs.getDrive('/tmp/'), fs.getDrive('/none'))
            print(fs.isDriveRoot('/'), fs.isDriveRoot('/tmp'), fs.isDriveRoot('/bin'))
            print(fs.getFreeSpace('/'), fs.getCapacity('/'))
            print(type(fs.getFreeSpace('/tmp')))
            """,
        ).assertOk()
        val out = r.text.replace(Regex("[^\\n\\t]*\\.lua:\\d+: "), "")
        val lines = out.lines()
        assertEquals("true\ttrue\tfalse\tfalse", lines[0])
        assertEquals("false\t/bin/lua: Access denied", lines[1])
        assertEquals("false\t/: Access denied", lines[2])
        assertEquals("nil\t/bin/new: Access denied", lines[3])
        assertEquals("image\timage\tdisk\tnil", lines[4])
        assertEquals("true\ttrue\tfalse", lines[5])
        assertEquals("0\tnil", lines[6])
        assertTrue(lines[7] == "number" || lines[7] == "string", out)
    }

    @Test
    fun `fs_findはワイルドカードで1区間ずつ合わせ、completeは候補を返す`() = timeout(limit) {
        val r = lua(
            """
            fs.makeDir('/tmp/d/sub')
            for _, n in ipairs({'a.lua', 'b.lua', 'c.txt', 'sub/d.lua', '.hidden'}) do fs.open('/tmp/d/' .. n, 'w').close() end
            print(table.concat(fs.find('/tmp/d/*.lua'), ','))
            print(table.concat(fs.find('tmp/?/s*'), ','), #fs.find('/tmp/none/*'), table.concat(fs.find('/tmp/d/c.txt'), ','))
            print(table.concat(fs.complete('s', '/tmp/d'), ','))
            print(table.concat(fs.complete('', '/tmp/d', false, false), ','))
            print(table.concat(fs.complete('.', '/tmp/d', { include_hidden = true }), ','))
            print(table.concat(fs.complete('/tmp/d/a', 'ignored'), ','))
            """,
        ).assertOk()
        assertEquals(
            """
            tmp/d/a.lua,tmp/d/b.lua
            tmp/d/sub	0	tmp/d/c.txt
            ub/,ub
            sub/
            hidden
            .lua
            """.trimIndent() + "\n",
            r.text,
        )
    }

    @Test
    fun `fs_listをくり返してもfdが残らない`() = timeout(limit) {
        val r = lua(
            """
            fs.makeDir('/tmp/many')
            for i = 1, 3 do fs.open('/tmp/many/' .. i, 'w').close() end
            for i = 1, 600 do fs.list('/tmp/many') pcall(fs.list, '/tmp/none') end
            for i = 1, 300 do fs.copy('/tmp/many', '/tmp/c') fs.delete('/tmp/c') end
            print('ok', #fs.list('/tmp/many'))
            """,
        ).assertOk()
        assertEquals("ok\t3\n", r.text)
    }

    @Test
    fun `fsのdeleteは、マウントの位置とそれを含むディレクトリを、中身を消さずに断る`() = timeout(limit) {
        val r = lua(
            """
            fs.makeDir('/home/outer')
            fs.open('/tmp/keep', 'w').close()
            print(fs.isDriveRoot('/home/outer/inner'))
            print(pcall(fs.delete, '/home/outer'))
            print(pcall(fs.delete, '/home/outer/inner'))
            print(pcall(fs.delete, '/tmp'))
            print(fs.exists('/home/outer/inner/data'), fs.exists('/home/outer/inner/sub/deep'), fs.exists('/tmp/keep'))
            fs.delete('/home/outer/inner/data')
            print(fs.exists('/home/outer/inner/data'))
            """,
            setup = { m ->
                m.vfs.mount(VfsPath.parse("/home/outer/inner"), TestMemoryFileSystem(), readOnly = false, source = "test:inner")
                m.vfs.createDirectory(VfsPath.parse("/home/outer/inner/sub"))
                m.put("/home/outer/inner/data", "x")
                m.put("/home/outer/inner/sub/deep", "y")
            },
        ).assertOk()
        val out = r.text.replace(Regex("[^\\n\\t]*\\.lua:\\d+: "), "")
        assertEquals(
            """
            true
            false	/home/outer: Access denied
            false	/home/outer/inner: Access denied
            false	/tmp: Access denied
            true	true	true
            false
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `保存から戻した後の最初の描画で、カーソルを隠していればCSI 25lを書き直す`() = timeout(limit) {
        fun run(hide: Boolean): String {
            var sent = false
            val r = lua(
                """
                os.queueEvent('ready')
                os.pullEvent('ready')
                ${if (hide) "term.setCursorBlink(false)" else ""}
                print('ready')
                local e, jumpReal, jumpWorld = os.pullEvent('restored')
                print('got', e, jumpReal, jumpWorld)
                term.setCursorPos(1, 1)
                term.write('a')
                term.write('b')
                print('done')
                """,
                driver = { m, text ->
                    if (!sent && text.contains("ready")) {
                        sent = true
                        m.postEvent(KernelEvent(RestoreEvents.TYPE, RestoreEvents.SOURCE, listOf(EventValue.Integer(5), EventValue.Integer(7))))
                    }
                },
            ).assertOk()
            assertTrue(r.text.contains("got\trestored\t5\t7"), r.text)
            return String(r.bytes, Charsets.UTF_8)
        }
        val hidden = run(hide = true)
        // 隠したときの 1 回と、戻した後の最初の描画の 1 回だけ（2 回目の描画では書き直さない）
        assertEquals(2, Regex(Regex.escape("\u001b[?25l")).findAll(hidden).count(), hidden)
        val gotAt = hidden.indexOf("got")
        assertTrue(hidden.indexOf("\u001b[?25l", gotAt) > gotAt, hidden)
        // 隠していなければ、何も書き足さない
        assertFalse(run(hide = false).contains("\u001b[?25l"))
    }
}
