package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.MachineClock
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.machine.MachineErrorListener
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.persist.RestoreListener

/**
 * マシンを作る処理・復元を始める前の用意が投げた台を、「表示は RUNNING、マシンなし」のまま残さないこと（#808）。
 * エラー（[ComputerError.UNAVAILABLE]）にし、電源の状態（[ComputerCase.poweredOn]）は変えず、例外は投げ直す
 */
class ComputerCaseLoadFailureTest {
    private val manager = MachineManager<String>(
        SchedulerConfig.builder().maxConcurrentMachines(1).build(),
        MachineErrorListener { _, _ -> },
    )
    private val config = MachineConfig.DEFAULT
    private val boot = BootConfig.builder().addBootDisk("none").build()

    @AfterEach
    fun tearDown() {
        manager.shutdown(10_000)
    }

    private class Parts(vararg slots: Int) : InstalledParts {
        val installed = slots.toMutableSet()

        override fun isInstalled(slot: Int): Boolean = slot in installed
    }

    private fun required(tier: CaseTier) =
        Parts(*PartKind.entries.filter { it.required }.map { tier.firstSlot(it) }.toIntArray())

    /** 単調時計を読むと [failure] を投げる環境（マシンのコンストラクターが時計を読むので、`MachineSlot.load` が投げる） */
    private fun throwingEnvironment(failure: () -> Throwable): MachineEnvironment =
        MachineEnvironment.builder().clock(
            object : MachineClock {
                override fun realtimeNanos(): Long = MachineClock.SYSTEM.realtimeNanos()

                override fun monotonicNanos(): Long = throw failure()

                override fun worldTicks(): Long = 0
            },
        ).build()

    /** 電源の変化の数を数えるケース */
    private class Counted {
        var changes = 0
        val case = ComputerCase<String>(CaseTier.BASIC, poweredOnChanged = { changes++ })
    }

    private fun ComputerCase<String>.startRestoreWith(
        environment: MachineEnvironment,
        onReady: RestoreReady = RestoreReady { error("呼ばない") },
        finished: RestoreListener = RestoreListener { _, _, _ -> error("呼ばない") },
    ): ComputerStatus = startRestore(manager, "a", required(tier), true, config, environment, boot, null, onReady, finished)

    @Test
    fun `電源を入れてマシンを作る処理が投げたらエラーにして投げ直し電源は入れない`() {
        val c = Counted()
        val thrown = assertThrows<IllegalArgumentException> {
            c.case.turnOn(manager, "a", required(c.case.tier), true, config, throwingEnvironment { IllegalArgumentException("壊れた時計") })
        }
        assertEquals("壊れた時計", thrown.message)
        assertEquals(ComputerStatus.ERROR, c.case.status)
        assertEquals(ComputerError.UNAVAILABLE, c.case.error)
        assertNull(c.case.machine)
        assertNull(manager["a"])
        assertFalse(c.case.poweredOn)
        assertEquals(0, c.changes)
    }

    @Test
    fun `復元でマシンを作る処理が投げたら順番待ちを抜けてエラーにし電源は残す`() {
        val c = Counted()
        c.case.loadPoweredOn(true)
        assertTrue(c.case.beginRestore())
        assertEquals(ComputerStatus.RUNNING, c.case.status)
        assertThrows<IllegalArgumentException> {
            c.case.startRestoreWith(throwingEnvironment { IllegalArgumentException("壊れた時計") })
        }
        assertEquals(ComputerStatus.ERROR, c.case.status)
        assertEquals(ComputerError.UNAVAILABLE, c.case.error)
        assertFalse(c.case.isRestorePending)
        assertNull(c.case.machine)
        assertTrue(c.case.poweredOn, "電源の状態は変えない（次の読み込みで復元し直す）")
        assertEquals(0, c.changes)

        // 順番待ちでないので、もう一度始めても何もしない
        assertEquals(ComputerStatus.ERROR, c.case.startRestoreWith(MachineEnvironment.DEFAULT))
        assertNull(c.case.machine)

        // 次の読み込み（FULL に戻った・読み込み直し）でまた順番待ちに入り、ふつうの環境なら動き出す
        assertTrue(c.case.beginRestore())
        assertEquals(ComputerStatus.RUNNING, c.case.status)
        val ready = ArrayList<Any>()
        c.case.startRestoreWith(MachineEnvironment.DEFAULT, onReady = RestoreReady { ready += it }, finished = RestoreListener { _, _, _ -> })
        assertEquals(ComputerStatus.RUNNING, c.case.status)
        val machine = c.case.machine!!
        assertEquals(listOf<Any>(machine), ready)
        assertSame(machine, manager["a"])
        assertTrue(c.case.poweredOn)
        assertEquals(0, c.changes, "もう真なので変わらない")
    }

    @Test
    fun `マシンを作る処理が IllegalStateException を投げても握らずにエラーにして投げ直す`() {
        val c = Counted()
        c.case.loadPoweredOn(true)
        c.case.beginRestore()
        val thrown = assertThrows<IllegalStateException> {
            c.case.startRestoreWith(throwingEnvironment { IllegalStateException("壊れた時計") })
        }
        assertEquals("壊れた時計", thrown.message)
        assertEquals(ComputerStatus.ERROR, c.case.status)
        assertEquals(ComputerError.UNAVAILABLE, c.case.error)
        assertFalse(c.case.isRestorePending)
        assertNull(c.case.machine)
        assertTrue(c.case.poweredOn)
        assertEquals(0, c.changes)

        // 電源ボタンでも同じ（電源は入れたまま変えない）
        val d = Counted()
        assertThrows<IllegalStateException> {
            d.case.pressPowerButton(manager, "b", required(d.case.tier), true, config, throwingEnvironment { IllegalStateException("壊れた時計") })
        }
        assertEquals(ComputerError.UNAVAILABLE, d.case.error)
        assertFalse(d.case.poweredOn)
    }

    @Test
    fun `閉じた管理には作らず投げずにエラーにする`() {
        val closed = MachineManager<String>(SchedulerConfig.builder().maxConcurrentMachines(1).build(), MachineErrorListener { _, _ -> })
        closed.shutdown(10_000)
        val c = Counted()
        assertEquals(ComputerStatus.ERROR, c.case.turnOn(closed, "a", required(c.case.tier), true, config))
        assertEquals(ComputerError.UNAVAILABLE, c.case.error)
        assertFalse(c.case.poweredOn)
    }

    @Test
    fun `StackOverflowError は致命的でない扱いでエラーにする`() {
        val c = Counted()
        c.case.loadPoweredOn(true)
        c.case.beginRestore()
        assertThrows<StackOverflowError> { c.case.startRestoreWith(throwingEnvironment { StackOverflowError() }) }
        assertEquals(ComputerStatus.ERROR, c.case.status)
        assertEquals(ComputerError.UNAVAILABLE, c.case.error)
        assertTrue(c.case.poweredOn)
    }

    @Test
    fun `JVM の致命的なエラーは状態を触らずにそのまま投げる`() {
        val c = Counted()
        c.case.loadPoweredOn(true)
        c.case.beginRestore()
        val error = OutOfMemoryError("試験")
        val thrown = assertThrows<OutOfMemoryError> { c.case.startRestoreWith(throwingEnvironment { error }) }
        assertSame(error, thrown)
        assertNull(c.case.error, "表示は書き換えない")
        assertFalse(c.case.isRestorePending)
        assertTrue(c.case.poweredOn)
        assertEquals(0, c.changes)
    }

    @Test
    fun `復元の前の用意が投げたら順番待ちを抜けてエラーにし電源は残す`() {
        val c = Counted()
        c.case.loadPoweredOn(true)
        c.case.beginRestore()
        assertTrue(c.case.abandonRestore())
        assertEquals(ComputerStatus.ERROR, c.case.status)
        assertEquals(ComputerError.UNAVAILABLE, c.case.error)
        assertFalse(c.case.isRestorePending)
        assertTrue(c.case.poweredOn)
        assertEquals(0, c.changes)
        // 2回目は何もしない
        assertFalse(c.case.abandonRestore())
        // セーブデータの読み直しは効く（順番待ちを抜けている）
        c.case.loadPoweredOn(false)
        assertFalse(c.case.poweredOn)
    }

    @Test
    fun `順番待ちでなければ復元をやめても表示を変えない`() {
        // OFF
        val off = ComputerCase<String>(CaseTier.BASIC)
        assertFalse(off.abandonRestore())
        assertEquals(ComputerStatus.OFF, off.status)
        // 部品が足りないエラー
        val missing = ComputerCase<String>(CaseTier.BASIC)
        missing.turnOn(manager, "m", Parts(), true, config)
        assertFalse(missing.abandonRestore())
        assertEquals(ComputerError.MISSING_PARTS, missing.error)
        assertFalse(missing.missingParts.isEmpty())
        // 動いている
        val c = Counted()
        c.case.turnOn(manager, "a", required(c.case.tier), true, config)
        val machine = c.case.machine!!
        assertFalse(c.case.abandonRestore())
        assertEquals(ComputerStatus.RUNNING, c.case.status)
        assertSame(machine, c.case.machine)
        assertTrue(c.case.poweredOn)
    }
}
