package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 起動部品のプログラムだけで起動した台（#99、ADR 0099）の保存と復元: 根（`source` が `firmware`）を、復元の求めの設定の
 * プログラムから作り直す。今の設定にプログラムがなければ冷えた起動
 */
class FirmwareRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    private val program: ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/prompt-wasi.wasm")) { "prompt-wasi.wasm がリソースにない" }.use { it.readBytes() }

    private class Console : MachineHost {
        val text = StringBuffer()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            text.append(String(bytes, Charsets.UTF_8))
        }
    }

    private fun mounts(m: Machine): List<Pair<String, String>> = m.lock.withLockValue { m.kernel.mounts.entries.map { it.point.toString() to it.source } }

    /** プログラムだけで起動し、init（`prompt`）がコンソールの入力を待ったところで書いて閉じる */
    private fun saveWaiting(h: Harness, store: MachineSaveStore, config: BootConfig): MachineIdentity {
        val host = Console()
        val m = h.machine(fuelPerTick = 10_000_000, host = host)
        assertTrue(m.boot(config))
        eventually(60_000, "入力を待たない") {
            h.scheduler.tick()
            Thread.sleep(1)
            !m.isBooting && m.stateOf(1) == ProcessState.WAITING_EVENT && m.isAtWaitPoint && host.text.contains("> ")
        }
        assertEquals(listOf("/" to "firmware", "/tmp" to "tmp", "/dev" to "dev"), mounts(m))
        val written = store.write(store.assignNewId(owner)!!, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
        m.close()
        return written
    }

    private fun restore(h: Harness, saved: MachineIdentity, store: MachineSaveStore, config: BootConfig, host: MachineHost): Pair<Machine, Pair<RestoreOutcome, ColdBootCause?>> {
        val m = h.machine(fuelPerTick = 10_000_000, host = host)
        val outcomes = Collections.synchronizedList(ArrayList<Pair<RestoreOutcome, ColdBootCause?>>())
        val listener = RestoreListener { _, outcome, cause -> outcomes += outcome to cause }
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.id, saved.generation), config, listener)))
        eventually(60_000, "復元が終わらない") {
            h.scheduler.tick()
            outcomes.isNotEmpty()
        }
        return m to outcomes.single()
    }

    @Test
    fun `プログラムだけで起動した台を戻すと、根を今の設定のプログラムで作り直して続きの入力に答える`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val config = BootConfig.builder().firmwareProgram(program).build()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaiting(h, store, config)
            val host = Console()
            val (m, result) = restore(h, saved, store, config, host)
            assertEquals(RestoreOutcome.RESTORED to null, result)
            assertEquals(listOf("/" to "firmware", "/tmp" to "tmp", "/dev" to "dev"), mounts(m))
            m.offerConsoleInput("hello\n".toByteArray())
            eventually(60_000, "戻したプロセスが答えない") {
                h.scheduler.tick()
                Thread.sleep(1)
                host.text.contains("hello")
            }
            m.close()
        }
    }

    @Test
    fun `今の設定にプログラムがなければ冷えた起動`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaiting(h, store, BootConfig.builder().firmwareProgram(program).build())
            val (m, result) = restore(h, saved, store, BootConfig.builder().addBootDisk("none").build(), Console())
            assertEquals(RestoreOutcome.COLD_BOOT to ColdBootCause.MOUNT, result)
            m.close()
        }
    }
}
