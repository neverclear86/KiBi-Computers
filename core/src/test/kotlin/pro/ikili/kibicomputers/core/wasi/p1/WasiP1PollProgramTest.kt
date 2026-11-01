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
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** wasi-libc でビルドした poll_wasi.c（実際の Endive）を、p1 の層とカーネルの上で動かす（#72） */
class WasiP1PollProgramTest {
    private val program: Program by lazy {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/poll-wasi.wasm")?.use { it.readBytes() }
        assertNotNull(bytes, "poll-wasi.wasm がリソースにない")
        Program.load(EndiveRuntime(), bytes!!)
    }

    /** 同じプログラムに待機点への変換（#329）をかけたもの。`WAIT_POINT` で動き、巻き戻して待つ */
    private val asyncProgram: Program by lazy {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/poll-wasi.wasm")!!.use { it.readBytes() }
        Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS)
    }

    @Test
    fun `wasi-libcのpollとnanosleepとclock_nanosleepがpoll_oneoffを通って動く`() = run(program)

    @Test
    fun `待機点への変換をかけても、pollとnanosleepとclock_nanosleepが巻き戻して待って動く`() {
        assertEquals(ProcessKind.WAIT_POINT, WasiP1Driver(asyncProgram.module).kind)
        run(asyncProgram)
    }

    private fun run(program: Program) = timeout(Duration.ofSeconds(60)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val (input, output) = m.lock.withLockValue {
                ByteStream(64).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            }
            val options = SpawnOptions(
                allowUnmetered = !program.module.isFuelMetered,
                args = listOf("poll-wasi"),
                stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
            )
            val pid = m.spawn(program, WasiP1Driver(program.module), options)

            // 標準入力が空なので、poll で待つ
            eventually(20_000, "poll で待たない") {
                h.scheduler.tick()
                m.stateOf(pid) == ProcessState.WAITING_EVENT || m.stateOf(pid).isTerminal
            }
            m.lock.withLockValue {
                (m.kernel.objects[input] as ByteStream).write("ping\n".toByteArray(), 0, 5, m.wakeSink)
                Unit
            }
            eventually(20_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }

            val text = m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                (s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes)
                String(buf, Charsets.UTF_8)
            }
            assertEquals("OK\n", text, "プログラムの出力")
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            var timers = 0
            m.lock.withLockValue { m.kernel.objects.forEachObject { if (it is Timer) timers++ } }
            assertEquals(0, timers, "一時的なタイマーは閉じる")
        }
    }
}
