package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * wasi-libc でビルドした p1 のテストのプログラムを、wasmtime のアダプターで p2 のコマンドのコンポーネントにし、
 * 実物のホスト（[WasiP2Host]）と [WasiP2Driver] でカーネルの上で最後まで動かす（#267）。p1 の層のテストと同じ期待を確かめる
 */
class WasiP2ProgramTest {
    private fun program(name: String): Program = Program.component(P2Testing.load(P2Testing.wrap(name)))

    private fun driver(p: Program): WasiP2Driver = WasiP2Driver.create(p.component!!)

    @Test
    fun `アダプターで包んだhello-wasiが実物のホストで最後まで動く`() = timeout(Duration.ofSeconds(120)) {
        Harness().use { h ->
            val p = program("hello-wasi")
            val r = P2Testing.run(h, p, driver(p))
            assertEquals("Hello from WASI\n", r.output)
            assertEquals(0, r.exitCode)
        }
    }

    @Test
    fun `アダプターで包んだpath-wasiのパスの操作がwasi-filesystemを通って動く`() = timeout(Duration.ofSeconds(120)) {
        Harness().use { h ->
            val p = program("path-wasi")
            val r = P2Testing.run(h, p, driver(p), args = listOf("path-wasi")) { m ->
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/data"))
                val f = m.vfs.open(VfsPath.parse("/data/a.txt"), OpenOptions(read = true, write = true, create = true))
                m.vfs.write(f, "abcde".toByteArray(), 0, 5)
                m.vfs.mount(VfsPath.parse("/ro"), TestMemoryFileSystem(), readOnly = true, source = "test:ro")
            }
            assertEquals("OK\n", r.output)
            assertEquals(0, r.exitCode)
        }
    }

    /**
     * 引数（argv[0] を含む）・環境変数（`PWD=<起動時の cwd>` を足す。#760。basic_wasi.c は件数を数えない）・単調時計・壁時計は p1 と同じに見える。`CLOCK_PROCESS_CPUTIME_ID` は、
     * アダプターが p2 の時計に写さない（p2 に CPU 時間の時計はない）ので、その行で止まる
     */
    @Test
    fun `アダプターで包んだbasic-wasiの引数・環境変数・時計がp1と同じに見える`() = timeout(Duration.ofSeconds(120)) {
        Harness().use { h ->
            val p = program("basic-wasi")
            val r = P2Testing.run(
                h,
                p,
                driver(p),
                args = listOf("basic-wasi", "alpha", "ベータ"),
                env = linkedMapOf("GREETING" to "hello", "EMPTY" to ""),
            )
            assertEquals("FAIL __wasi_clock_time_get(__WASI_CLOCKID_PROCESS_CPUTIME_ID, 1, &t) == 0 (line 48)\n", r.output)
            assertEquals(1, r.exitCode)
        }
    }
}
