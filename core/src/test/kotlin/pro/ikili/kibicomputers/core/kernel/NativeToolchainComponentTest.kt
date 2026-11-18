package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import pro.ikili.kibicomputers.core.wasi.p2.P2Testing
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ネイティブのツールチェーンで作ったコンポーネントを `exec` で動かす（#775）。
 *
 * wasi-sdk（`gradle/wasi-sdk.properties`）の clang で `hello_wasi.c` などを `--target=wasm32-wasip2`・`wasm32-wasip3` でビルドしたもの
 * （core の build の `wasiSdkModule`）。アダプターで包んだ p1 のプログラム（`ComponentExecTest`）と違い、wasi-libc が p2・p3 の import を
 * 直接呼び、LLVM の既定の機能（bulk-memory、reference-types など）を有効にした core モジュールを `wasm-component-ld` が包む
 */
class NativeToolchainComponentTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm-native/$name.wasm")) { "$name.wasm がない" }.readBytes()

    private val helloP2: ByteArray by lazy { resource("hello-wasi-p2") }
    private val exit3P2: ByteArray by lazy { resource("exit3-p2") }
    private val helloP3: ByteArray by lazy { resource("hello-wasi-p3") }

    /** `wasm-tools component wit` で読んだ、コンポーネントの world の import の行 */
    private fun importsOf(component: ByteArray): List<String> {
        val r = WitTool.run(listOf("component", "wit", "${WitTool.WORK}/c.wasm"), mapOf("c.wasm" to component))
        assertEquals(0, r.exitCode, r.stderr)
        return r.stdout.lines().map { it.trim() }.filter { it.startsWith("import ") }
    }

    private fun write(m: Machine, path: String, bytes: ByteArray) {
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
    }

    private fun machine(h: Harness): Machine {
        val m = h.machine(fuelPerTick = 10_000_000)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        return m
    }

    /** [path] を exec して、標準出力を集めながら終わるまで待つ。標準出力と終了コード */
    private fun execAndWait(h: Harness, m: Machine, path: String): Pair<String, Int?> {
        val out = P2Testing.outputStream(m)
        val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(out, HandleRights.WRITE), StdioSource.Object(out, HandleRights.WRITE))
        val pid = m.exec(path, KernelDrivers.DEFAULT, SpawnOptions(stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN))
        val text = StringBuilder()
        eventually(60_000, "終わらない") {
            h.scheduler.tick()
            text.append(P2Testing.drain(m, out))
            m.stateOf(pid).isTerminal
        }
        text.append(P2Testing.drain(m, out))
        return text.toString() to m.lock.withLockValue { m.kernel.processes[pid]?.exitCode }
    }

    @Test
    fun `wasm32-wasip2でビルドしたものはアダプターを通さないp2のコマンドのコンポーネント`() {
        assertTrue(Program.isComponent(helloP2))
        val imports = importsOf(helloP2)
        // wasi-libc が直接 import する p2 のインターフェース（アダプター由来の wasi_snapshot_preview1 の core モジュールは持たない）
        assertTrue(imports.isNotEmpty() && imports.all { it.startsWith("import wasi:") && it.contains("@0.2.") }, "$imports")
        assertTrue(imports.any { it.startsWith("import wasi:cli/stdout@") }, "$imports")
        println("wasi-sdk の wasm32-wasip2 の import（#775）: $imports")
    }

    @Test
    fun `wasm32-wasip2でビルドしたコマンドをexecで動かす`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/hello", helloP2)
            val start = System.nanoTime()
            val (output, code) = execAndWait(h, m, "/bin/hello")
            println("wasi-sdk の hello（${helloP2.size} バイト）の読み込みから終了まで: ${(System.nanoTime() - start) / 1_000_000} ms")
            assertEquals("Hello from WASI\n", output)
            assertEquals(0, code)
            // 2 回目は読み込み直さない
            assertEquals("Hello from WASI\n" to 0, execAndWait(h, m, "/bin/hello"))
            assertEquals(1, h.scheduler.programs.loads.get())
        }
    }

    @Test
    fun `0と1以外の終了コードはp2のexitのerrで1になる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/exit3", exit3P2)
            // wasi-sdk 34 の wasi-libc は、main の戻り値が 0 でなければ wasi:cli/exit の exit(err) を呼ぶ。p2 の exit は ok・err だけ
            // なので、err はカーネルの終了コード 1 になる（WasiP2Cli）
            assertEquals("exit 3\n" to 1, execAndWait(h, m, "/bin/exit3"))
        }
    }

    @Test
    fun `wasm32-wasip3でビルドしたものは読み込みで断ってNOEXEC、失敗を覚えて読み込み直さない`() = timeout(limit) {
        assertTrue(Program.isComponent(helloP3))
        // 断る場所（版の判定・検証器・リンカー）は問わず、読み込みの失敗（WasmLoadException）になる。p3 の層は #268
        val e = assertThrows<WasmLoadException> {
            Program.loadExecutable(EndiveRuntime(), helloP3, null, true, emptySet(), KernelDrivers.COMPONENT_HOST)
        }
        println("wasi-sdk の wasm32-wasip3 を断った理由（#775）: ${e.message}")
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/p3", helloP3)
            assertEquals(Errno.NOEXEC, assertThrows<FsException> { m.exec("/bin/p3", KernelDrivers.DEFAULT) }.errno)
            assertEquals(Errno.NOEXEC, assertThrows<FsException> { m.exec("/bin/p3", KernelDrivers.DEFAULT) }.errno)
            assertEquals(1, h.scheduler.programs.loads.get())
        }
    }
}
