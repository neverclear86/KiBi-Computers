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
    private val exitNativeP2: ByteArray by lazy { resource("exit-native-p2") }
    private val helloP3: ByteArray by lazy { resource("hello-wasi-p3") }
    private val pathP2: ByteArray by lazy { resource("path-wasi-p2") }
    private val basicP2: ByteArray by lazy { resource("basic-native-p2") }

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
    private fun execAndWait(
        h: Harness,
        m: Machine,
        path: String,
        args: List<String> = emptyList(),
        env: Map<String, String>? = null,
    ): Pair<String, Int?> {
        val out = P2Testing.outputStream(m)
        val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(out, HandleRights.WRITE), StdioSource.Object(out, HandleRights.WRITE))
        val pid = m.exec(path, KernelDrivers.DEFAULT, SpawnOptions(args = args, env = env, stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN))
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
        // wasi-libc が直接 import する p2 のインターフェース。world の import は、アダプターで包んだものも `wasi:*@0.2.x` だけになるので、
        // これだけでは「アダプターを通さない」とは言えない（下の、アダプターの印の検査が分ける）
        assertTrue(imports.isNotEmpty() && imports.all { it.startsWith("import wasi:") && it.contains("@0.2.") }, "$imports")
        assertTrue(imports.any { it.startsWith("import wasi:cli/stdout@") }, "$imports")
        println("wasi-sdk の wasm32-wasip2 の import（#775）: $imports")
        // アダプターで包んだ p1 のプログラムは、中の core モジュールが p1 の `wasi_snapshot_preview1` を import し、アダプターの core モジュールが
        // それを受ける。ネイティブの成果物には、どちらの core モジュールもない。対照として、アダプターで包んだ同じソースには印が入っていることを先に確かめる
        // （wasi-sdk を上げて wasm-component-ld がアダプターを挟む形に戻ったら、ここで落ちる）
        assertTrue(hasP1AdapterMark(P2Testing.wrap("hello-wasi")), "対照（アダプターで包んだもの）に p1 の印がない。検査が効いていない")
        assertTrue(!hasP1AdapterMark(helloP2), "ネイティブの成果物に wasi_snapshot_preview1 がある（アダプターを挟んでいる）")
        assertTrue(!hasP1AdapterMark(pathP2) && !hasP1AdapterMark(basicP2) && !hasP1AdapterMark(exit3P2) && !hasP1AdapterMark(exitNativeP2))
    }

    /** コンポーネントの中のどこかに、p1 のアダプターの印（`wasi_snapshot_preview1` の名前。core モジュールの import・アダプターの export）があるか */
    private fun hasP1AdapterMark(component: ByteArray): Boolean =
        String(component, Charsets.ISO_8859_1).contains("wasi_snapshot_preview1")

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

    /**
     * wasi-libc がアダプターと違う呼び方（preopen を `wasi:filesystem/preopens` の `get-directories` で取る、descriptor の open-at・
     * read-via-stream・write-via-stream・read-directory など）をするパス操作を、p1（アダプター経由）と同じ `path_wasi.c` で確かめる
     */
    @Test
    fun `wasm32-wasip2でビルドしたpath-wasiがwasi-filesystemを直接通ってパスを操作する`() = timeout(limit) {
        assertTrue(importsOf(pathP2).any { it.startsWith("import wasi:filesystem/") }, "wasi:filesystem を import していない")
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            m.vfs.createDirectory(VfsPath.parse("/data"))
            write(m, "/data/a.txt", "abcde".toByteArray())
            m.vfs.mount(VfsPath.parse("/ro"), TestMemoryFileSystem(), readOnly = true, source = "test:ro")
            write(m, "/bin/path", pathP2)
            assertEquals("OK\n" to 0, execAndWait(h, m, "/bin/path", args = listOf("path-wasi")))
        }
    }

    /** args・environ・時計・乱数・sched_yield（`wasi:cli/environment`・`wasi:clocks`・`wasi:random`）を、ネイティブの wasi-libc の呼び方で確かめる */
    @Test
    fun `wasm32-wasip2でビルドしたものの引数・環境変数・時計・乱数が届く`() = timeout(limit) {
        val imports = importsOf(basicP2)
        for (name in listOf("wasi:cli/environment@", "wasi:clocks/wall-clock@", "wasi:random/random@")) {
            assertTrue(imports.any { it.startsWith("import $name") }, "$name を import していない: $imports")
        }
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/basic", basicP2)
            val env = linkedMapOf("GREETING" to "hello", "EMPTY" to "")
            assertEquals("OK\n" to 0, execAndWait(h, m, "/bin/basic", args = listOf("basic", "alpha", "ベータ"), env = env))
        }
    }

    @Test
    fun `mainが0と1以外を返すとrunのerrで終了コード1になる`() = timeout(limit) {
        // wasm32-wasip2 の wasi-libc の crt1 は、main の戻り値が 0 でなければ wasi:cli/exit を呼ばず、wasi:cli/run の run から err を
        // 返すだけ（exit を import すらしない）。終了コード 1 は WasiP2Driver の「run が err → 1」で決まる
        assertTrue(importsOf(exit3P2).none { it.startsWith("import wasi:cli/exit@") }, "exit3-p2 が wasi:cli/exit を import している")
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/exit3", exit3P2)
            assertEquals("exit 3\n" to 1, execAndWait(h, m, "/bin/exit3"))
        }
    }

    @Test
    fun `exitを呼ぶとwasi-libcがwasiのcliのexitを直接呼び終了コードになる`() = timeout(limit) {
        // main から戻らずに exit() を呼ぶと、wasi-libc は wasi:cli/exit を直接呼ぶ（WasiP2Exit を投げて WasiP2Driver が終了コードにする）。
        // ホストの写し方は WasiP2Cli（exit(ok) → 0、exit(err) → 1、exit-with-code(n) → n）。exit(3) が 3 になるのは exit-with-code を
        // 通ったときだけ（exit(err) なら 1）なので、exit-with-code の経路はここで通る。0・1 で exit・exit-with-code のどちらを呼ぶかは見分けない
        assertTrue(importsOf(exitNativeP2).any { it.startsWith("import wasi:cli/exit@") }, "exit-native-p2 が wasi:cli/exit を import していない")
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/exit", exitNativeP2)
            for ((code, expected) in EXIT_CASES) {
                assertEquals("exit($code)\n" to expected, execAndWait(h, m, "/bin/exit", args = listOf("exit", "$code")), "exit($code)")
            }
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

    private companion object {
        /** exit() に渡す値と、期待する終了コード */
        val EXIT_CASES: List<Pair<Int, Int>> = listOf(0 to 0, 1 to 1, 3 to 3)
    }
}
