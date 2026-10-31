package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** wasi-libc でビルドした basic_wasi.c（実際の Endive）を、p1 の層とカーネルの上で動かす（#69） */
class WasiP1BasicProgramTest {
    private val program: Program by lazy {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/basic-wasi.wasm")?.use { it.readBytes() }
        assertNotNull(bytes, "basic-wasi.wasm がリソースにない")
        Program.load(EndiveRuntime(), bytes!!)
    }

    @Test
    fun `wasi-libcの引数・環境変数・時計・乱数が動き、exitの値が終了コードになる`() = timeout(Duration.ofSeconds(60)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val output = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
            val options = SpawnOptions(
                allowUnmetered = true,
                args = listOf("basic-wasi", "alpha", "ベータ"),
                env = linkedMapOf("GREETING" to "hello", "EMPTY" to ""),
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
            )
            val pid = m.spawn(program, WasiP1Driver(program.module), options)
            eventually(20_000, "終わらない") { m.stateOf(pid).isTerminal }

            val text = m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                (s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes)
                String(buf, Charsets.UTF_8)
            }
            assertEquals("OK\n", text, "プログラムの出力")
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(42, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode })
        }
    }
}
