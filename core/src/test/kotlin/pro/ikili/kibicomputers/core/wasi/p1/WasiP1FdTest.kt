package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** WASI p1 の fd_*（#70）。ダミーのモジュールから import を直接呼び、メモリの中身で確かめる */
class WasiP1FdTest {
    private val limit = Duration.ofSeconds(30)

    /** p1 の関数 [names] を import し、同じ名前・型で export して、そのまま呼ぶモジュール */
    private fun forwarding(vararg names: String, extra: Map<String, WasmFunctionType> = emptyMap()): FakeModule {
        val b = FakeModule.builder().fuelMetered().memory(1)
        for (name in names) {
            val type = WasiP1.typeOf(name) ?: extra.getValue(name)
            b.import(P1.MODULE, name, type)
            b.export(name, type) { c, args -> c.callImport(P1.MODULE, name, *args) }
        }
        for ((name, type) in extra) {
            if (name in names) continue
            b.import(P1.MODULE, name, type)
            b.export(name, type) { c, args -> c.callImport(P1.MODULE, name, *args) }
        }
        return b.build()
    }

    /** p1 の層をつないだドライバー。[body] がメモリと呼び出しで確かめる */
    private fun driver(module: FakeModule, blocking: Boolean = true, body: (ProcessContext, WasmMemory, Call) -> Unit) =
        TestDriver(
            if (blocking) ProcessKind.BLOCKING else ProcessKind.WAIT_POINT,
            onConfigure = { ctx, b -> WasiP1.register(ctx, b, module, blocking) },
        ) { ctx ->
            body(ctx, ctx.instance.memory!!, Call { name, args -> ctx.instance.call(name, *args)[0].toInt() })
            StepResult.Exit(0)
        }

    private fun interface Call {
        operator fun invoke(name: String, vararg args: Long): Int
    }

    private fun runToEnd(h: Harness, m: Machine, module: FakeModule, options: SpawnOptions, blocking: Boolean = true, body: (ProcessContext, WasmMemory, Call) -> Unit) {
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val pid = m.spawn(h.program(module), driver(module, blocking) { c, mem, call ->
            try {
                body(c, mem, call)
            } catch (t: Throwable) {
                errors += t
            }
        }, options)
        eventually(10_000) {
            h.scheduler.tick()
            m.stateOf(pid).isTerminal
        }
        errors.firstOrNull()?.let { throw it }
        assertEquals(ProcessState.EXITED, m.stateOf(pid))
    }

    private fun Machine.stream(capacity: Int = 64): ByteStream = lock.withLockValue { ByteStream(capacity).also { kernel.objects.add(it) } }

    private fun iov(mem: WasmMemory, at: Int, vararg bufs: Pair<Int, Int>) {
        bufs.forEachIndexed { i, (addr, len) ->
            mem.writeI32(at + i * 8, addr)
            mem.writeI32(at + i * 8 + 4, len)
        }
    }

    @Test
    fun `NONBLOCKの空のストリームはAGAINで、fdstatにフラグが出る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val input = m.stream()
            val module = forwarding("fd_read", "fd_fdstat_get", "fd_fdstat_set_flags")
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Object(input.id, HandleRights.READ), StdioSource.Closed, StdioSource.Closed))
            runToEnd(h, m, module, opts) { _, mem, call ->
                assertEquals(0, call("fd_fdstat_set_flags", 0, P1.FDFLAGS_NONBLOCK.toLong()))
                iov(mem, 100, 200 to 16)
                assertEquals(Errno.AGAIN.code, call("fd_read", 0, 100, 1, 300))
                assertEquals(0, call("fd_fdstat_get", 0, 400))
                assertEquals(P1.FILETYPE_UNKNOWN, mem.readU8(400))
                assertEquals(P1.FDFLAGS_NONBLOCK, mem.readU8(402))
                val rights = mem.readI64(408)
                assertTrue(rights and P1.RIGHT_FD_READ != 0L)
                assertEquals(0L, rights and (P1.RIGHT_FD_WRITE or P1.RIGHT_FD_SEEK or P1.RIGHT_FD_TELL))
                assertEquals(Errno.INVAL.code, call("fd_fdstat_set_flags", 0, 0x100))
                assertEquals(Errno.NOTSUP.code, call("fd_fdstat_set_flags", 0, P1.FDFLAGS_SYNC.toLong()))
                assertEquals(Errno.BADF.code, call("fd_fdstat_get", 7, 400))
            }
        }
    }

    @Test
    fun `コンソールは端末として見え、読み書きでバイトの燃料がかかる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val module = forwarding("fd_fdstat_get", "fd_write")
            runToEnd(h, m, module, SpawnOptions(stdio = StdioSpec.CONSOLE)) { _, mem, call ->
                assertEquals(0, call("fd_fdstat_get", 1, 400))
                assertEquals(P1.FILETYPE_CHARACTER_DEVICE, mem.readU8(400))
                assertEquals(0L, mem.readI64(408) and (P1.RIGHT_FD_SEEK or P1.RIGHT_FD_TELL))
                mem.write(200, "hi\n".toByteArray())
                iov(mem, 100, 200 to 3)
                assertEquals(0, call("fd_write", 1, 100, 1, 300))
                assertEquals(3, mem.readI32(300))
            }
        }
    }

    @Test
    fun `範囲外のiovecはFAULTで、ストリームから読まない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val input = m.stream()
            m.lock.withLockValue {
                input.write("abc".toByteArray(), 0, 3, m.wakeSink)
                Unit
            }
            val module = forwarding("fd_read", "fd_write", "fd_seek")
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Object(input.id, HandleRights.READ), StdioSource.Closed, StdioSource.Closed))
            runToEnd(h, m, module, opts) { _, mem, call ->
                iov(mem, 100, 200 to 2, WasmMemory.PAGE_SIZE - 1 to 2)
                assertEquals(Errno.FAULT.code, call("fd_read", 0, 100, 2, 300))
                assertEquals(Errno.FAULT.code, call("fd_read", 0, WasmMemory.PAGE_SIZE - 4L, 1, 300))
                assertEquals(Errno.FAULT.code, call("fd_read", 0, 100, 1, WasmMemory.PAGE_SIZE - 2L))
                // 2 つの iovec に分けて読む
                iov(mem, 100, 200 to 2, 210 to 5)
                assertEquals(0, call("fd_read", 0, 100, 2, 300))
                assertEquals(3, mem.readI32(300))
                assertArrayEquals("ab".toByteArray(), mem.read(200, 2))
                assertEquals('c'.code, mem.readU8(210))
                assertEquals(Errno.SPIPE.code, call("fd_seek", 0, 0, P1.WHENCE_CUR.toLong(), 300))
                assertEquals(Errno.BADF.code, call("fd_write", 0, 100, 1, 300))
            }
        }
    }

    @Test
    fun `待つfdへの書き込みは、相手が読むのを待って全部書く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val out = m.stream(capacity = 4)
            val module = forwarding("fd_write")
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(out.id, HandleRights.WRITE), StdioSource.Closed))
            val received = java.io.ByteArrayOutputStream()
            val reader = Thread {
                val buf = ByteArray(16)
                while (received.size() < 10) {
                    val n = m.lock.withLockValue {
                        (out.read(buf, 0, buf.size, m.wakeSink) as? pro.ikili.kibicomputers.core.kernel.io.ReadResult.Bytes)?.count ?: 0
                    }
                    synchronized(received) { received.write(buf, 0, n) }
                    Thread.sleep(1)
                }
            }
            reader.isDaemon = true
            reader.start()
            runToEnd(h, m, module, opts) { _, mem, call ->
                mem.write(200, "0123456789".toByteArray())
                iov(mem, 100, 200 to 10)
                assertEquals(0, call("fd_write", 1, 100, 1, 300))
                assertEquals(10, mem.readI32(300))
            }
            reader.join(5_000)
            assertEquals("0123456789", synchronized(received) { received.toString(Charsets.UTF_8) })
        }
    }

    @Test
    fun `WAIT_POINTのプロセスではストリームを待たずにAGAIN`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val input = m.stream()
            val module = forwarding("fd_read")
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Object(input.id, HandleRights.READ), StdioSource.Closed, StdioSource.Closed))
            runToEnd(h, m, module, opts, blocking = false) { _, mem, call ->
                iov(mem, 100, 200 to 4)
                assertEquals(Errno.AGAIN.code, call("fd_read", 0, 100, 1, 300))
                // 長さ 0 は待たずに 0
                iov(mem, 100, 200 to 0)
                assertEquals(0, call("fd_read", 0, 100, 1, 300))
                assertEquals(0, mem.readI32(300))
            }
        }
    }

    @Test
    fun `ファイルとディレクトリのfdの属性・preopen・readdir`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val (dirId, fileId) = m.lock.withLockValue {
                val fs = TestMemoryFileSystem()
                m.vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/data"))
                m.vfs.createDirectory(VfsPath.parse("/data/sub"))
                val f = m.vfs.open(VfsPath.parse("/data/a.txt"), OpenOptions(read = true, write = true, create = true))
                m.vfs.write(f, "hello".toByteArray(), 0, 5)
                val d = m.vfs.open(VfsPath.parse("/data"), OpenOptions(read = true))
                m.kernel.objects.add(d) to m.kernel.objects.add(f)
            }
            val module = forwarding(
                "fd_fdstat_get", "fd_filestat_get", "fd_prestat_get", "fd_prestat_dir_name", "fd_readdir", "fd_seek", "fd_tell",
                "fd_filestat_set_size", "fd_filestat_set_times", "fd_allocate", "fd_fdstat_set_rights",
                extra = mapOf("sock_accept" to SOCK_ACCEPT),
            )
            val opts = SpawnOptions(
                stdio = StdioSpec.CLOSED,
                fds = mapOf(3 to StdioSource.Object(dirId, HandleRights.ALL), 4 to StdioSource.Object(fileId, HandleRights.READ)),
            )
            runToEnd(h, m, module, opts) { _, mem, call ->
                // preopen
                assertEquals(0, call("fd_prestat_get", 3, 100))
                assertEquals(P1.PREOPENTYPE_DIR, mem.readU8(100))
                assertEquals(5, mem.readI32(104))
                assertEquals(Errno.NAMETOOLONG.code, call("fd_prestat_dir_name", 3, 200, 4))
                assertEquals(0, call("fd_prestat_dir_name", 3, 200, 5))
                assertEquals("/data", String(mem.read(200, 5)))
                assertEquals(Errno.BADF.code, call("fd_prestat_get", 4, 100))
                assertEquals(Errno.BADF.code, call("fd_prestat_get", 5, 100))

                // ディレクトリの fdstat
                assertEquals(0, call("fd_fdstat_get", 3, 100))
                assertEquals(P1.FILETYPE_DIRECTORY, mem.readU8(100))
                assertEquals(P1.RIGHTS_ALL, mem.readI64(116))

                // 読むだけの fd のファイル
                assertEquals(0, call("fd_fdstat_get", 4, 100))
                assertEquals(P1.FILETYPE_REGULAR_FILE, mem.readU8(100))
                val rights = mem.readI64(108)
                assertTrue(rights and P1.RIGHT_FD_READ != 0L && rights and P1.RIGHT_FD_SEEK != 0L)
                assertEquals(0L, rights and P1.RIGHT_FD_WRITE)
                assertEquals(Errno.BADF.code, call("fd_filestat_set_size", 4, 0))
                assertEquals(Errno.BADF.code, call("fd_allocate", 4, 0, 100))
                assertEquals(Errno.NOTSUP.code, call("fd_fdstat_set_rights", 4, 0, 0))
                assertEquals(0, call("fd_filestat_get", 4, 100))
                assertEquals(P1.FILETYPE_REGULAR_FILE, mem.readU8(116))
                assertEquals(5L, mem.readI64(132))
                assertEquals(Errno.INVAL.code, call("fd_filestat_set_times", 4, 1, 2, (P1.FSTFLAGS_ATIM or P1.FSTFLAGS_ATIM_NOW).toLong()))
                assertEquals(0, call("fd_filestat_set_times", 4, 0, 0, P1.FSTFLAGS_MTIM_NOW.toLong()))
                assertEquals(0, call("fd_filestat_get", 4, 100))
                assertTrue(mem.readI64(148) > 0, "MTIM_NOW で時刻が入る")
                assertEquals(0, call("fd_seek", 4, -2, P1.WHENCE_END.toLong(), 300))
                assertEquals(3L, mem.readI64(300))
                assertEquals(0, call("fd_tell", 4, 300))
                assertEquals(3L, mem.readI64(300))
                assertEquals(Errno.INVAL.code, call("fd_seek", 4, 0, 9, 300))
                assertEquals(Errno.ISDIR.code, call("fd_seek", 3, 0, 0, 300))

                // readdir: . .. a.txt sub
                assertEquals(0, call("fd_readdir", 3, 1000, 4096, 0, 300))
                val names = parseDirents(mem, 1000, mem.readI32(300))
                assertEquals(listOf(".", "..", "a.txt", "sub"), names.map { it.first })
                assertEquals(listOf(1L, 2L, 3L, 4L), names.map { it.second })
                // cookie から続きを読む
                assertEquals(0, call("fd_readdir", 3, 1000, 4096, 2, 300))
                assertEquals(listOf("a.txt", "sub"), parseDirents(mem, 1000, mem.readI32(300)).map { it.first })
                assertEquals(0, call("fd_readdir", 3, 1000, 4096, 4, 300))
                assertEquals(0, mem.readI32(300))
                assertEquals(Errno.NOTDIR.code, call("fd_readdir", 4, 1000, 4096, 0, 300))

                // まだない関数は NOSYS
                assertEquals(Errno.NOSYS.code, call("sock_accept", 3, 0, 0))
            }
        }
    }

    private companion object {
        val I32 = WasmValueType.I32
        val SOCK_ACCEPT: WasmFunctionType = WasmFunctionType.of(listOf(I32, I32, I32), listOf(I32))
    }

    /** dirent の並びを（名前、d_next）にする。途中で切れたものは含めない */
    private fun parseDirents(mem: WasmMemory, at: Int, used: Int): List<Pair<String, Long>> {
        val result = ArrayList<Pair<String, Long>>()
        var p = 0
        while (p + P1.DIRENT_SIZE <= used) {
            val next = mem.readI64(at + p)
            val len = mem.readI32(at + p + 16)
            if (p + P1.DIRENT_SIZE + len > used) break
            result += String(mem.read(at + p + P1.DIRENT_SIZE, len)) to next
            p += P1.DIRENT_SIZE + len
        }
        assertNotNull(result)
        return result
    }
}
