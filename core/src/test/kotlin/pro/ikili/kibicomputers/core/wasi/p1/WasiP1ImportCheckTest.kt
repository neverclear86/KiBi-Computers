package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.uleb
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmImportDescriptor
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** p1 のプロセスに許す import（#40） */
class WasiP1ImportCheckTest {
    private val runtime = EndiveRuntime()

    /** `_start` を export し、[imports]（モジュール名, 名前, 引数, 戻り値）を import するコマンド */
    private fun command(vararg imports: Triple<String, String, Pair<List<Int>, List<Int>>>): ByteArray = WasmBinary().run {
        for ((m, n, t) in imports) importFunc(m, n, t.first, t.second)
        exportFunc(WasiP1Driver.START, func(emptyList(), emptyList(), code()))
        build()
    }

    private fun load(wasm: ByteArray): WasmModule = runtime.load(wasm)

    private fun assertNoexec(module: WasmModule, fragment: String) {
        val e = assertThrows<FsException> { WasiP1.checkImports(module) }
        assertEquals(Errno.NOEXEC, e.errno)
        assertTrue(e.message!!.contains(fragment), e.message)
    }

    private val fdWrite = Triple(P1.MODULE, "fd_write", listOf(I32, I32, I32, I32) to listOf(I32))

    @Test
    fun `p1の関数だけをimportするモジュールは通る`() {
        // sock_accept はまだ実装していない（NOSYS の仮の関数をつなぐ）が、仕様にある名前なので通す
        val sockAccept = Triple(P1.MODULE, "sock_accept", listOf(I32, I32, I32) to listOf(I32))
        val pollOneoff = Triple(P1.MODULE, "poll_oneoff", listOf(I32, I32, I32, I32) to listOf(I32))
        val pathOpen = Triple(P1.MODULE, "path_open", listOf(I32, I32, I32, I32, I32, 0x7e, 0x7e, I32, I32) to listOf(I32))
        assertDoesNotThrow { WasiP1.checkImports(load(command(fdWrite, pollOneoff, pathOpen, sockAccept))) }
        assertDoesNotThrow { WasiP1.checkImports(load(command())) }
    }

    @Test
    fun `ほかのモジュール名のimportは断る`() {
        assertNoexec(load(command(fdWrite, Triple("env", "system", listOf(I32) to listOf(I32)))), "env.system")
    }

    @Test
    fun `仕様にないp1の関数は断る`() {
        assertNoexec(load(command(Triple(P1.MODULE, "open_backdoor", emptyList<Int>() to emptyList()))), "open_backdoor")
    }

    @Test
    fun `型の違う実装済みの関数は断る`() {
        assertNoexec(load(command(Triple(P1.MODULE, "fd_write", listOf(I32) to listOf(I32)))), "型が違う")
        // poll_oneoff は #72 で実装したので、型も確かめる
        assertNoexec(load(command(Triple(P1.MODULE, "poll_oneoff", listOf(I32, I32, I32) to listOf(I32)))), "poll_oneoff")
    }

    @Test
    fun `kibi_computer_memoryの関数は、型が一致するときだけ通る`() {
        val remaining = Triple(WasiP1Kibi.MEMORY, "remaining", emptyList<Int>() to listOf(0x7e))
        val commit = Triple(WasiP1Kibi.MEMORY, "commit", listOf(I32, I32, 0x7e) to listOf(I32))
        assertDoesNotThrow { WasiP1.checkImports(load(command(fdWrite, remaining, commit))) }
        assertNoexec(load(command(Triple(WasiP1Kibi.MEMORY, "remaining", emptyList<Int>() to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.MEMORY, "commit", listOf(I32, I32, I32) to listOf(I32)))), "型が違う")
    }

    @Test
    fun `kibiの知らない名前と予約名は断る`() {
        assertNoexec(load(command(Triple(WasiP1Kibi.MEMORY, "free", listOf(I32) to listOf(I32)))), "${WasiP1Kibi.MEMORY}.free")
        assertNoexec(load(command(Triple("kibi", "memory_free", listOf(I32) to listOf(I32)))), "kibi.memory_free")
        assertNoexec(load(command(Triple("kibi", "async_yield", emptyList<Int>() to emptyList()))), "kibi.async_yield")
        assertNoexec(load(command(Triple("kibi:kernel/unknown@0.1.0", "f", emptyList<Int>() to emptyList()))), "許可していない")
    }

    @Test
    fun `旧名のkibi_memoryの関数は断る`() {
        assertNoexec(load(command(Triple("kibi", "memory_remaining", emptyList<Int>() to listOf(0x7e)))), "旧名")
        assertNoexec(load(command(Triple("kibi", "memory_commit", listOf(I32, I32, 0x7e) to listOf(I32)))), "旧名")
    }

    @Test
    fun `独自拡張の版が違えば断る`() {
        assertNoexec(load(command(Triple("kibi:computer/memory@0.2.0", "remaining", emptyList<Int>() to listOf(0x7e)))), "版が違う")
        assertNoexec(load(command(Triple("kibi:computer/memory", "remaining", emptyList<Int>() to listOf(0x7e)))), "版が違う")
        assertNoexec(load(command(Triple("kibi:kernel/events@0.1.1", "[resource-drop]event-queue", listOf(I32) to emptyList()))), "版が違う")
    }

    private val open = Triple(WasiP1Kibi.EVENTS, "[static]event-queue.open", listOf(I32, I32, I32, I32, I32) to emptyList<Int>())
    private val take = Triple(WasiP1Kibi.EVENTS, "[method]event-queue.take", listOf(I32, I32, I32) to emptyList<Int>())
    private val drop = Triple(WasiP1Kibi.EVENTS, "[resource-drop]event-queue", listOf(I32) to emptyList<Int>())

    /** [command] に、型が [params] → [results] の `cabi_realloc` の export を足したもの */
    private fun commandWithRealloc(params: List<Int>, results: List<Int>, vararg imports: Triple<String, String, Pair<List<Int>, List<Int>>>): ByteArray =
        WasmBinary().run {
            for ((m, n, t) in imports) importFunc(m, n, t.first, t.second)
            exportFunc(WasiP1Driver.START, func(emptyList(), emptyList(), code()))
            val body = if (results.isEmpty()) code() else code(0x41, 0)
            exportFunc(WasiP1Kibi.REALLOC, func(params, results, body))
            build()
        }

    @Test
    fun `kibi_kernel_eventsはcabi_reallocがあれば通り、takeはcabi_reallocがなければ断る`() {
        assertDoesNotThrow { WasiP1.checkImports(load(commandWithRealloc(listOf(I32, I32, I32, I32), listOf(I32), open, take, drop))) }
        // open と drop は cabi_realloc を使わない
        assertDoesNotThrow { WasiP1.checkImports(load(command(open, drop))) }
        assertNoexec(load(command(open, take)), WasiP1Kibi.REALLOC)
        // 型の違う cabi_realloc
        assertNoexec(load(commandWithRealloc(listOf(I32, I32, I32), listOf(I32), take)), WasiP1Kibi.REALLOC)
        assertNoexec(load(commandWithRealloc(listOf(I32, I32, I32, I32), emptyList(), take)), WasiP1Kibi.REALLOC)
        // 型の違う import
        assertNoexec(load(command(Triple(WasiP1Kibi.EVENTS, "[method]event-queue.take", listOf(I32, I32) to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.EVENTS, "[method]event-queue.peek", listOf(I32) to emptyList()))), "許可していない")
    }

    @Test
    fun `kibi_ioのp1の関数は型が一致するときだけ通り、版違い・表にない関数・p2とp3のinterfaceは断る`() {
        val dup = Triple(WasiP1Kibi.IO_FD, "dup", listOf(I32, I32) to emptyList<Int>())
        val dupTo = Triple(WasiP1Kibi.IO_FD, "dup-to", listOf(I32, I32, I32) to emptyList<Int>())
        val create = Triple(WasiP1Kibi.IO_PIPE, "create", listOf(I32) to emptyList<Int>())
        // cabi_realloc は要らない
        assertDoesNotThrow { WasiP1.checkImports(load(command(dup, dupTo, create))) }
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_FD, "dup", listOf(I32) to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_FD, "dup-to", listOf(I32, I32) to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_PIPE, "create", emptyList<Int>() to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple("kibi:io/fd@0.2.0", "dup", listOf(I32, I32) to emptyList()))), "版が違う")
        assertNoexec(load(command(Triple("kibi:io/fd", "dup", listOf(I32, I32) to emptyList()))), "版が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_FD, "dup3", listOf(I32, I32, I32, I32) to emptyList()))), "許可していない")
        assertNoexec(load(command(Triple("kibi:io/pipe-streams@0.1.0", "create", listOf(I32) to emptyList()))), "許可していない")
        assertNoexec(load(command(Triple("kibi:io/pipe-async@0.1.0", "create", listOf(I32, I32) to emptyList()))), "許可していない")
    }

    @Test
    fun `kibi_processは型が一致するときだけ通り、get-cwdとlist-processesはcabi_reallocが要り、版違い・p2とp3のinterfaceは断る`() {
        val p = WasiP1Kibi.PROCESS
        val spawn = Triple(p, "[static]process.spawn", List(13) { I32 } to emptyList<Int>())
        val pid = Triple(p, "[method]process.pid", listOf(I32) to listOf(I32))
        val status = Triple(p, "[method]process.status", listOf(I32, I32) to emptyList<Int>())
        val kill = Triple(p, "[method]process.kill", listOf(I32, I32, I32) to emptyList<Int>())
        val drop = Triple(p, "[resource-drop]process", listOf(I32) to emptyList<Int>())
        val killPid = Triple(p, "kill-pid", listOf(I32, I32, I32) to emptyList<Int>())
        val selfPid = Triple(p, "self-pid", emptyList<Int>() to listOf(I32))
        val parentPid = Triple(p, "parent-pid", emptyList<Int>() to listOf(I32))
        val ignored = Triple(p, "set-signal-ignored", listOf(I32, I32, I32) to emptyList<Int>())
        val getCwd = Triple(p, "get-cwd", listOf(I32) to emptyList<Int>())
        val setCwd = Triple(p, "set-cwd", listOf(I32, I32, I32) to emptyList<Int>())
        val listProcesses = Triple(p, "list-processes", listOf(I32) to emptyList<Int>())
        // get-cwd・list-processes 以外は cabi_realloc が要らない
        assertDoesNotThrow { WasiP1.checkImports(load(command(spawn, pid, status, kill, drop, killPid, selfPid, parentPid, ignored, setCwd))) }
        assertDoesNotThrow { WasiP1.checkImports(load(commandWithRealloc(listOf(I32, I32, I32, I32), listOf(I32), getCwd))) }
        assertNoexec(load(command(getCwd)), WasiP1Kibi.REALLOC)
        // list-processes も cabi_realloc が要る（#452）。型の違う cabi_realloc・import も断る
        assertDoesNotThrow { WasiP1.checkImports(load(commandWithRealloc(listOf(I32, I32, I32, I32), listOf(I32), getCwd, listProcesses))) }
        assertNoexec(load(command(listProcesses)), WasiP1Kibi.REALLOC)
        assertNoexec(load(commandWithRealloc(listOf(I32, I32, I32), listOf(I32), listProcesses)), WasiP1Kibi.REALLOC)
        assertNoexec(load(command(Triple(p, "list-processes", emptyList<Int>() to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple(p, "[static]process.spawn", List(12) { I32 } to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(p, "[method]process.status", listOf(I32) to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple("kibi:process/process@0.2.0", "self-pid", emptyList<Int>() to listOf(I32)))), "版が違う")
        assertNoexec(load(command(Triple(p, "fork", emptyList<Int>() to listOf(I32)))), "許可していない")
        assertNoexec(load(command(Triple("kibi:process/process-poll@0.1.0", "subscribe", listOf(I32) to listOf(I32)))), "許可していない")
        assertNoexec(load(command(Triple("kibi:process/process-async@0.1.0", "wait", listOf(I32, I32) to emptyList()))), "許可していない")
    }

    @Test
    fun `kibi_io_ttyは型が一致するときだけ通り、版違い・表にない関数は断る`() {
        val isTty = Triple(WasiP1Kibi.IO_TTY, "is-tty", listOf(I32) to listOf(I32))
        val getMode = Triple(WasiP1Kibi.IO_TTY, "get-mode", listOf(I32, I32) to emptyList<Int>())
        val setMode = Triple(WasiP1Kibi.IO_TTY, "set-mode", listOf(I32, I32, I32) to emptyList<Int>())
        val getSize = Triple(WasiP1Kibi.IO_TTY, "get-size", listOf(I32, I32) to emptyList<Int>())
        val getFg = Triple(WasiP1Kibi.IO_TTY, "get-foreground", listOf(I32, I32) to emptyList<Int>())
        val setFg = Triple(WasiP1Kibi.IO_TTY, "set-foreground", listOf(I32, I32, I32) to emptyList<Int>())
        val all = listOf(isTty, getMode, setMode, getSize, getFg, setFg)
        // cabi_realloc は要らない
        assertDoesNotThrow { WasiP1.checkImports(load(command(*all.toTypedArray()))) }
        for (fn in all) assertEquals(false, WasiP1Kibi.needsRealloc(fn.first, fn.second), fn.second)
        // 型が違う
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "is-tty", listOf(I32, I32) to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "get-mode", listOf(I32) to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "set-mode", listOf(I32, I32) to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "get-size", listOf(I32, I32, I32) to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "get-foreground", listOf(I32) to listOf(I32)))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "set-foreground", listOf(I32, I32) to emptyList()))), "型が違う")
        // 版が違う
        assertNoexec(load(command(Triple("kibi:io/tty@0.2.0", "is-tty", listOf(I32) to listOf(I32)))), "版が違う")
        assertNoexec(load(command(Triple("kibi:io/tty", "is-tty", listOf(I32) to listOf(I32)))), "版が違う")
        // 表にない関数
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "subscribe", listOf(I32) to listOf(I32)))), "許可していない")
        assertNoexec(load(command(Triple(WasiP1Kibi.IO_TTY, "isatty", listOf(I32) to listOf(I32)))), "許可していない")
    }

    @Test
    fun `kibi_kernel_timersとkibi_computer_clockは型が一致するときだけ通り、版違い・表にない関数・p2とp3のinterfaceは断る`() {
        val I64 = 0x7e
        val create = Triple(WasiP1Kibi.TIMERS, "[static]timer.create", listOf(I32, I32, I64, I32) to emptyList<Int>())
        val setAfter = Triple(WasiP1Kibi.TIMERS, "[method]timer.set-after", listOf(I32, I64, I64, I32) to emptyList<Int>())
        val setAt = Triple(WasiP1Kibi.TIMERS, "[method]timer.set-at", listOf(I32, I64, I64, I32) to emptyList<Int>())
        val cancel = Triple(WasiP1Kibi.TIMERS, "[method]timer.cancel", listOf(I32, I32) to emptyList<Int>())
        val read = Triple(WasiP1Kibi.TIMERS, "[method]timer.read", listOf(I32, I32) to emptyList<Int>())
        val drop = Triple(WasiP1Kibi.TIMERS, "[resource-drop]timer", listOf(I32) to emptyList<Int>())
        val uptime = Triple(WasiP1Kibi.CLOCK, "uptime-ticks", emptyList<Int>() to listOf(I64))
        val world = Triple(WasiP1Kibi.CLOCK, "world-ticks", emptyList<Int>() to listOf(I64))
        // cabi_realloc は要らない
        assertDoesNotThrow { WasiP1.checkImports(load(command(create, setAfter, setAt, cancel, read, drop, uptime, world))) }
        for (fn in listOf(create, setAfter, setAt, cancel, read, drop, uptime, world)) {
            assertEquals(false, WasiP1Kibi.needsRealloc(fn.first, fn.second), fn.second)
        }
        // 型が違う
        assertNoexec(load(command(Triple(WasiP1Kibi.TIMERS, "[static]timer.create", listOf(I32, I32, I32, I32) to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.TIMERS, "[method]timer.set-after", listOf(I32, I32, I32, I32) to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.TIMERS, "[method]timer.read", listOf(I32) to listOf(I64)))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.CLOCK, "uptime-ticks", emptyList<Int>() to listOf(I32)))), "型が違う")
        // 版が違う
        assertNoexec(load(command(Triple("kibi:kernel/timers@0.2.0", "[resource-drop]timer", listOf(I32) to emptyList()))), "版が違う")
        assertNoexec(load(command(Triple("kibi:computer/clock@0.1.1", "world-ticks", emptyList<Int>() to listOf(I64)))), "版が違う")
        // 表にない関数・p2 と p3 の interface
        assertNoexec(load(command(Triple(WasiP1Kibi.TIMERS, "[method]timer.wait", listOf(I32, I32) to emptyList()))), "許可していない")
        assertNoexec(load(command(Triple(WasiP1Kibi.CLOCK, "now", emptyList<Int>() to listOf(I64)))), "許可していない")
        assertNoexec(load(command(Triple("kibi:kernel/timers-poll@0.1.0", "subscribe", listOf(I32) to listOf(I32)))), "許可していない")
        assertNoexec(load(command(Triple("kibi:kernel/events-poll@0.1.0", "subscribe", listOf(I32) to listOf(I32)))), "許可していない")
        assertNoexec(load(command(Triple("kibi:kernel/timers-async@0.1.0", "[async-lower]wait", listOf(I32, I32) to listOf(I32)))), "許可していない")
        assertNoexec(load(command(Triple("kibi:kernel/events-async@0.1.0", "take", listOf(I32, I32, I32) to emptyList()))), "許可していない")
    }

    @Test
    fun `kibi_computer_powerは型が一致するときだけ通り、版違い・版なし・表にない関数は断る`() {
        val shutdown = Triple(WasiP1Kibi.POWER, "shutdown", emptyList<Int>() to emptyList<Int>())
        val reboot = Triple(WasiP1Kibi.POWER, "reboot", listOf(I32) to emptyList<Int>())
        // cabi_realloc は要らない。待たない（待機点への変換の対象でない）
        assertDoesNotThrow { WasiP1.checkImports(load(command(shutdown, reboot))) }
        for (fn in listOf(shutdown, reboot)) {
            assertEquals(false, WasiP1Kibi.needsRealloc(fn.first, fn.second), fn.second)
            assertFalse("${fn.first}.${fn.second}" in WasiP1.WAIT_IMPORTS, fn.second)
        }
        // 型が違う
        assertNoexec(load(command(Triple(WasiP1Kibi.POWER, "shutdown", listOf(I32) to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.POWER, "reboot", emptyList<Int>() to emptyList()))), "型が違う")
        assertNoexec(load(command(Triple(WasiP1Kibi.POWER, "reboot", emptyList<Int>() to listOf(I32)))), "型が違う")
        // 版が違う・版がない
        assertNoexec(load(command(Triple("kibi:computer/power@0.2.0", "shutdown", emptyList<Int>() to emptyList()))), "版が違う")
        assertNoexec(load(command(Triple("kibi:computer/power", "shutdown", emptyList<Int>() to emptyList()))), "版が違う")
        // 表にない関数
        assertNoexec(load(command(Triple(WasiP1Kibi.POWER, "halt", emptyList<Int>() to emptyList()))), "許可していない")
        assertNoexec(load(command(Triple(WasiP1Kibi.POWER, "reboot-poll", listOf(I32) to emptyList()))), "許可していない")
    }

    @Test
    fun `関数でないimportは断る`() {
        // (import "wasi_snapshot_preview1" "memory" (memory 1)) と `_start`
        val name = P1.MODULE.toByteArray()
        val importSec = byteArrayOf(1) + uleb(name.size.toLong()) + name + uleb(6) + "memory".toByteArray() + byteArrayOf(0x02, 0x00, 0x01)
        val wasm = sections(
            1 to byteArrayOf(1, 0x60, 0, 0),
            2 to importSec,
            3 to byteArrayOf(1, 0),
            7 to (byteArrayOf(1) + uleb(6) + "_start".toByteArray() + byteArrayOf(0x00, 0x00)),
            10 to byteArrayOf(1, 2, 0, 0x0b),
        )
        val module = load(wasm)
        assertEquals(listOf(WasmImportDescriptor(P1.MODULE, "memory", WasmExternKind.MEMORY, null)), module.imports)
        assertNoexec(module, "関数でない")
    }

    private fun sections(vararg s: Pair<Int, ByteArray>): ByteArray {
        var out = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        for ((id, body) in s) out += byteArrayOf(id.toByte()) + uleb(body.size.toLong()) + body
        return out
    }

    private fun Machine.install(path: String, bytes: ByteArray) = run {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    @Test
    fun `Machine_execは許可していないimportを持つWASMをNOEXECにする`() = timeout(Duration.ofSeconds(60)) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine()
            m.install("/bin/evil", command(Triple("env", "system", listOf(I32) to listOf(I32))))
            val e = assertThrows<FsException> { m.exec("/bin/evil", KernelDrivers.DEFAULT) }
            assertEquals(Errno.NOEXEC, e.errno)
        }
    }

    @Test
    fun `Machine_execは構造の上限を超えるWASMをNOEXECにする`() = timeout(Duration.ofSeconds(60)) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine()
            // ローカル変数を 40 億個宣言する関数
            val body = byteArrayOf(1, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f, I32.toByte(), 0x0b)
            m.install("/bin/bomb", sections(1 to byteArrayOf(1, 0x60, 0, 0), 3 to byteArrayOf(1, 0), 10 to (byteArrayOf(1) + uleb(body.size.toLong()) + body)))
            val e = assertThrows<FsException> { m.exec("/bin/bomb", KernelDrivers.DEFAULT) }
            assertEquals(Errno.NOEXEC, e.errno)
        }
    }
}
