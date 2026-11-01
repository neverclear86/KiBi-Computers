package pro.ikili.kibicomputers.core.machine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.runDriver
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.spinForever
import pro.ikili.kibicomputers.core.kernel.stateOf
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** サーバー側のマシン管理（#120）: 一覧・生成・破棄・tick・例外の扱い */
class MachineManagerTest {
    private val limit = Duration.ofSeconds(30)
    private val config = MachineConfig.builder().fuelPerTick(100).build()

    private class Errors : MachineErrorListener {
        val messages: MutableList<String> = Collections.synchronizedList(ArrayList())
        val errors: MutableList<Throwable> = Collections.synchronizedList(ArrayList())

        override fun onError(message: String, error: Throwable) {
            messages += message
            errors += error
        }
    }

    private class Recording : MachineHost {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            log += "state:$state"
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            log += "console:${String(bytes)}"
        }
    }

    private fun Harness.manager(errors: MachineErrorListener = MachineErrorListener.IGNORE) = MachineManager<String>(scheduler, errors)

    /** 終わらないプロセスを1つ動かし、その pid を返す */
    private fun Harness.spin(machine: Machine): Int = machine.spawnTest(program(spinForever()), runDriver())

    private fun Harness.tickUntil(manager: MachineManager<String>, condition: () -> Boolean) = eventually(10_000) {
        manager.tick()
        Thread.sleep(1)
        condition()
    }

    @Test
    fun `attachで作り、キーで引け、同じキーは置き換えて古いほうを止める`() = timeout(limit) {
        Harness().use { h ->
            val manager = h.manager()
            val a = manager.attach("a", Recording(), config)
            assertSame(a, manager["a"])
            assertNull(manager["b"])
            assertEquals(1, manager.size)
            val pid = h.spin(a)
            h.tickUntil(manager) { a.stateOf(pid) in RUNNING_STATES }
            val replaced = manager.attach("a", Recording(), config)
            assertNotSame(a, replaced)
            assertSame(replaced, manager["a"])
            assertEquals(setOf("a"), manager.keys())
            h.tickUntil(manager) { a.stateOf(pid) == ProcessState.KILLED }
        }
    }

    @Test
    fun `releaseで止めて一覧から外す。知らないキーと古いマシンは何もしない`() = timeout(limit) {
        Harness().use { h ->
            val manager = h.manager()
            for (reason in MachineReleaseReason.entries) {
                val m = manager.attach("k", Recording(), config)
                val pid = h.spin(m)
                h.tickUntil(manager) { m.stateOf(pid) in RUNNING_STATES }
                assertTrue(manager.release("k", reason))
                assertNull(manager["k"])
                h.tickUntil(manager) { m.stateOf(pid) == ProcessState.KILLED }
            }
            assertFalse(manager.release("k", MachineReleaseReason.REMOVED))
            val old = manager.attach("k", Recording(), config)
            val current = manager.attach("k", Recording(), config)
            assertFalse(manager.release("k", old, MachineReleaseReason.REMOVED))
            assertSame(current, manager["k"])
            assertTrue(manager.release("k", current, MachineReleaseReason.REMOVED))
            assertEquals(0, manager.size)
        }
    }

    @Test
    fun `外した後の通知は届かない`() = timeout(limit) {
        Harness().use { h ->
            val manager = h.manager()
            val kept = Recording()
            val gone = Recording()
            val keptMachine = manager.attach("kept", kept, config)
            val goneMachine = manager.attach("gone", gone, config)
            keptMachine.writeConsole("hi".toByteArray())
            goneMachine.writeConsole("hi".toByteArray())
            manager.release("gone", MachineReleaseReason.REMOVED)
            manager.tick()
            assertEquals(listOf("console:hi"), kept.log)
            assertEquals(emptyList<String>(), gone.log)
        }
    }

    @Test
    fun `起動の失敗と再起動の求めを転送し、外した後は捨てる`() = timeout(limit) {
        Harness().use { h ->
            val errors = Errors()
            val manager = h.manager(errors)
            val log = Collections.synchronizedList(ArrayList<String>())
            val host = object : MachineHost {
                override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

                override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
                    log += "failed:$failure"
                }

                override fun onRebootRequested(machine: Machine) {
                    log += "reboot"
                    throw IllegalStateException("reboot boom")
                }
            }
            val noFirmware = BootConfig.builder().firmwarePresent(false).build()
            val m = manager.attach("m", host, config)
            assertTrue(m.boot(noFirmware))
            h.tickUntil(manager) { log.contains("failed:NO_FIRMWARE") }
            assertTrue(m.reboot())
            h.tickUntil(manager) { log.contains("reboot") }
            assertEquals("reboot boom", errors.errors.single().message)

            // 外した後は、起動の失敗も再起動の求めも届けない（起動し直さない）
            val gone = manager.attach("gone", host, config)
            assertTrue(gone.boot(noFirmware))
            eventually { !gone.isBooting }
            manager.release("gone", MachineReleaseReason.REMOVED)
            repeat(20) {
                manager.tick()
                Thread.sleep(1)
            }
            assertEquals(listOf("failed:NO_FIRMWARE", "reboot"), log.toList())
        }
    }

    @Test
    fun `接続層の例外は知らせ、ほかのマシンには伝えない`() = timeout(limit) {
        Harness().use { h ->
            val errors = Errors()
            val manager = h.manager(errors)
            val ok = Recording()
            val bad = manager.attach(
                "bad",
                object : MachineHost {
                    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

                    override fun onConsoleOutput(machine: Machine, bytes: ByteArray) = throw IllegalStateException("boom")
                },
                config,
            )
            val good = manager.attach("ok", ok, config)
            bad.writeConsole("x".toByteArray())
            good.writeConsole("y".toByteArray())
            manager.tick()
            assertEquals(listOf("console:y"), ok.log)
            assertEquals(1, errors.errors.size)
            assertEquals("boom", errors.errors[0].message)
            assertTrue(errors.messages[0].contains("bad"), errors.messages[0])
        }
    }

    @Test
    fun `CRASHEDは知らせ、受け口の例外は捨てる`() {
        val errors = Errors()
        val log = ArrayList<MachineState>()
        val host = ManagedHost("k", { _, s, _ -> log += s }, errors)
        Harness().use { h ->
            val m = h.machine()
            val cause = RuntimeException("inconsistent")
            host.onStateChanged(m, MachineState.CRASHED, cause)
            assertSame(cause, errors.errors.single())
            assertEquals(listOf(MachineState.CRASHED), log)
            // 外した後も CRASHED は知らせる（接続層の受け口には届けない）
            host.released = true
            host.onStateChanged(m, MachineState.CRASHED, null)
            assertEquals(2, errors.errors.size)
            assertEquals(listOf(MachineState.CRASHED), log)
            val throwing = ManagedHost("k", { _, _, _ -> throw IllegalStateException() }, { _, _ -> throw IllegalStateException() })
            throwing.onStateChanged(m, MachineState.RUNNING, null)
        }
    }

    @Test
    fun `tickの例外はサーバーに伝えず知らせる`() = timeout(limit) {
        Harness().use { h ->
            val errors = Errors()
            var calls = 0
            val manager = MachineManager<String>(h.scheduler, errors) {
                calls++
                throw IllegalStateException("tick failed")
            }
            manager.tick()
            manager.tick()
            assertEquals(2, calls)
            assertEquals(2, manager.ticks)
            assertEquals(2, errors.errors.size)
            // JVM の致命的なエラーは投げる
            val fatal = MachineManager<String>(h.scheduler, errors) { throw OutOfMemoryError("test") }
            assertThrows<OutOfMemoryError> { fatal.tick() }
            Unit
        }
    }

    @Test
    fun `閉じると全マシンを止め、新しいマシンを断る`() = timeout(limit) {
        Harness().use { h ->
            val manager = h.manager()
            val m = manager.attach("a", Recording(), config)
            val pid = h.spin(m)
            h.tickUntil(manager) { m.stateOf(pid) in RUNNING_STATES }
            assertTrue(manager.shutdown(10_000))
            assertTrue(manager.isClosed)
            assertEquals(0, manager.size)
            assertEquals(ProcessState.KILLED, m.stateOf(pid))
            assertThrows<IllegalStateException> { manager.attach("b", Recording(), config) }
            assertFalse(manager.release("a", MachineReleaseReason.REMOVED))
            manager.tick()
            manager.close()
        }
    }

    @Test
    fun `MachineSlotはアンロードと破壊を区別し、自分のマシンだけ外す`() = timeout(limit) {
        Harness().use { h ->
            val manager = h.manager()
            val slot = MachineSlot<String>()
            assertNull(slot.machine)
            assertFalse(slot.removed())

            // アンロード: onChunkUnloaded の後の setRemoved は何もしない
            val m = slot.load(manager, "p", Recording(), config)!!
            assertSame(m, manager["p"])
            assertSame(m, slot.machine)
            assertTrue(slot.chunkUnloaded())
            assertFalse(slot.removed())
            assertNull(manager["p"])
            assertNull(slot.machine)

            // 破壊
            slot.load(manager, "p", Recording(), config)
            assertTrue(slot.removed())
            assertEquals(0, manager.size)

            // 同じ場所に新しいブロックエンティティが先にマシンを作った: 古いほうは新しいマシンを止めない
            val oldSlot = MachineSlot<String>()
            val newSlot = MachineSlot<String>()
            oldSlot.load(manager, "p", Recording(), config)
            val newer = newSlot.load(manager, "p", Recording(), config)
            assertFalse(oldSlot.removed())
            assertSame(newer, manager["p"])

            // 読み込み直すと、前のマシンを外してから作る
            val again = newSlot.load(manager, "q", Recording(), config)
            assertNull(manager["p"])
            assertSame(again, manager["q"])

            // 閉じた管理には作らない
            manager.close()
            assertNull(MachineSlot<String>().load(manager, "r", Recording(), config))
        }
    }
}

/** 動き始めた（燃料を待っているときも含む） */
private val RUNNING_STATES = setOf(ProcessState.RUNNING, ProcessState.WAITING_FUEL)
