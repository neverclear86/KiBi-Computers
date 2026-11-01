package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun Machine.install(path: String, bytes: ByteArray) = lock.withLockValue {
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
