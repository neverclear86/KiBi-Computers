package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.device.KeyboardDevice
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

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
     * コンソールは [columns] x [rows]。[driver] はテストの側の操作（出力を見てキーを押すなど）で、終わるまで tick ごとに呼ぶ
     */
    private fun lua(
        script: String,
        columns: Int = 20,
        rows: Int = 5,
        driver: (Machine, String) -> Unit = { _, _ -> },
    ): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
        m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
        m.vfs.mount(VfsPath.parse("/home"), TestMemoryFileSystem(), readOnly = false, source = "test:home")
        m.vfs.mount(VfsPath.parse("/tmp"), TestMemoryFileSystem(), readOnly = false, source = "test:tmp")
        m.resizeConsole(columns, rows)
        m.put("/home/t.lua", script.trimIndent() + "\n")
        val pid = m.exec(
            "/bin/lua",
            KernelDrivers.DEFAULT,
            SpawnOptions(args = listOf("lua", "/home/t.lua"), env = mapOf("PATH" to "/bin", "HOME" to "/home"), cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN),
        )
        eventuallyDescribe(150_000, { "終わらない: ${host.text()}" }) {
            h.scheduler.tick()
            driver(m, host.text())
            Thread.sleep(1)
            m.stateOf(pid).isTerminal
        }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        repeat(5) { h.scheduler.tick() }
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
        // コンソールの TTY は打ったキー（Esc・Enter・Tab など）をエコーするので、行の頭ではなく形で拾う
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
        assertEquals(0xCC4C4C, s.fg(2, 1))
        assertEquals(0x3366CC, s.bg(0, 0))
        assertEquals(0x3366CC, s.bg(2, 1))
        assertEquals(0xF0F0F0, s.fg(0, 4))
        assertEquals(0x111111, s.bg(0, 4))
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
        assertEquals(0xCC4C4C, s.fg(0, 0))
        assertEquals(0xF0F0F0, s.fg(1, 0))
        assertEquals(0x3366CC, s.fg(2, 0))
        assertEquals(0x7FCC19, s.bg(1, 0))
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
        assertEquals(0x7FCC19, s.fg(0, 0))
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
}
