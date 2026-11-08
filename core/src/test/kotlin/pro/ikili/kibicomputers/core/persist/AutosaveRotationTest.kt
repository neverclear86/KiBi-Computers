package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 自動保存を回して書く列（`AutosaveRotation`）のテスト（#81、ADR 0081）。予算 16、`autosaveQuiesceTicks` 4 */
class AutosaveRotationTest {
    private val config = MachineSaveConfig(autosaveWritesPerTick = 16, autosaveQuiesceTicks = 4)

    /** 偽の台: 止めてから [readyAfter] tick 経つと READY になり、書くと [writes] 回を返す */
    private class FakeMachine(val name: Int, val writes: Int = 1, val readyAfter: Int = 0, val failing: Boolean = false) {
        var quiescedAt = -1
        var writtenAt = -1
        var writeCalls = 0
        var closedAt = -1
        override fun toString() = "M$name"
    }

    private class Target : AutosaveTarget<FakeMachine> {
        var now = 0
        val writesPerTick = ArrayList<Int>()
        val open = HashSet<FakeMachine>()

        override fun quiesce(machine: FakeMachine): QuiesceHandle {
            machine.quiescedAt = now
            open += machine
            return object : QuiesceHandle {
                override val readiness: SaveReadiness
                    get() = if (now - machine.quiescedAt >= machine.readyAfter) SaveReadiness.READY else SaveReadiness.TRANSIENT

                override fun close() {
                    if (open.remove(machine)) machine.closedAt = now
                }
            }
        }

        override fun plannedWrites(machine: FakeMachine): Int = machine.writes

        override fun writeIfReady(machine: FakeMachine, handle: QuiesceHandle): Int {
            if (handle.readiness != SaveReadiness.READY) return AutosaveTarget.NOT_READY
            machine.writeCalls++
            machine.writtenAt = now
            return machine.writes
        }
    }

    /** 周期を一巡するまで回し、かかった tick 数を返す。毎 tick、止めている台の回数の合計が上限以下かも確かめる */
    private fun runCycle(rotation: AutosaveRotation<FakeMachine>, target: Target, machines: List<FakeMachine>, maxTicks: Int = 10_000): Int {
        rotation.startCycle(machines)
        var ticks = 0
        while (!rotation.isCycleDone) {
            target.now++
            ticks++
            rotation.tick(target)
            target.writesPerTick += rotation.lastTickWrites
            assertTrue(rotation.quiescedWrites <= 64, "止めている台の回数の合計 ${rotation.quiescedWrites}")
            assertTrue(ticks < maxTicks, "一巡しない")
        }
        return ticks
    }

    @Test
    fun `2回目から相当 - 1000台・回数1は64 tickで一巡し、飛ばす台はない`() {
        val rotation = AutosaveRotation<FakeMachine>(config)
        val target = Target()
        val machines = List(1000) { FakeMachine(it) }
        val ticks = runCycle(rotation, target, machines)
        assertEquals((1000 + 15) / 16 + 1, ticks)
        assertEquals(0, rotation.skippedInCycle)
        assertEquals(1000, rotation.writtenInCycle)
        assertTrue(machines.all { it.writeCalls == 1 })
        assertTrue(machines.all { it.closedAt - it.quiescedAt <= 4 }, "止めてすぐ READY の台は 4 tick 以内に書く")
        assertTrue(target.writesPerTick.all { it <= 16 })
    }

    @Test
    fun `IDを振る初回相当 - 1000台・回数2は126 tickで一巡し、予算待ちのREADYの台を飛ばさない`() {
        val rotation = AutosaveRotation<FakeMachine>(config)
        val target = Target()
        val machines = List(1000) { FakeMachine(it, writes = 2) }
        val ticks = runCycle(rotation, target, machines)
        assertEquals((2000 + 15) / 16 + 1, ticks)
        assertEquals(0, rotation.skippedInCycle)
        assertTrue(machines.all { it.writeCalls == 1 })
        assertTrue(machines.all { it.closedAt - it.quiescedAt <= 4 })
    }

    @Test
    fun `回数1と2が混ざっても、1 tickの書き込みは予算+1以下、続くk tickの合計は16k+1以下`() {
        val rotation = AutosaveRotation<FakeMachine>(config)
        val target = Target()
        val machines = List(500) { FakeMachine(it, writes = if (it % 3 == 0) 2 else 1) }
        runCycle(rotation, target, machines)
        val w = target.writesPerTick
        assertTrue(w.all { it <= 17 }, "$w")
        for (start in w.indices) {
            var sum = 0
            for (k in 1..(w.size - start)) {
                sum += w[start + k - 1]
                assertTrue(sum <= 16 * k + 1, "tick $start から $k tick の合計 $sum")
            }
        }
        assertEquals(machines.sumOf { it.writes }, w.sum())
    }

    @Test
    fun `4 tick経ってもREADYにならない台は飛ばされ、次の周期の先頭に来る。3 tick目にREADYになった台は書かれる`() {
        val rotation = AutosaveRotation<FakeMachine>(config)
        val target = Target()
        val slow = FakeMachine(0, readyAfter = 3)
        val stuck = FakeMachine(1, readyAfter = 100)
        val others = List(5) { FakeMachine(it + 2) }
        runCycle(rotation, target, listOf(slow, stuck) + others)
        assertEquals(1, slow.writeCalls, "3 tick 目に READY になった台は書く")
        assertEquals(0, stuck.writeCalls)
        assertEquals(4, stuck.closedAt - stuck.quiescedAt, "4 tick 経って飛ばす")
        assertEquals(1, rotation.skippedInCycle)
        assertTrue(others.all { it.writeCalls == 1 }, "前の台が READY でなければ追い越す")

        // 次の周期の先頭に来る
        val order = ArrayList<FakeMachine>()
        val recording = object : AutosaveTarget<FakeMachine> by target {
            override fun quiesce(machine: FakeMachine): QuiesceHandle {
                order += machine
                return target.quiesce(machine)
            }
        }
        rotation.startCycle(listOf(slow) + others + listOf(stuck))
        target.now++
        rotation.tick(recording)
        assertEquals(stuck, order.first())
    }

    @Test
    fun `途中でremoveした台は書かず、止めていればハンドルを返す。失敗した台は書き直さない`() {
        val rotation = AutosaveRotation<FakeMachine>(config)
        val target = Target()
        val removedEarly = FakeMachine(0, readyAfter = 2)
        val removedPending = FakeMachine(1)
        val failing = FakeMachine(2)
        val failTarget = object : AutosaveTarget<FakeMachine> by target {
            override fun writeIfReady(machine: FakeMachine, handle: QuiesceHandle): Int {
                if (machine === failing) {
                    machine.writeCalls++
                    return 1
                }
                return target.writeIfReady(machine, handle)
            }
        }
        val pendingTail = List(100) { FakeMachine(it + 3) }
        rotation.startCycle(listOf(removedEarly, failing) + pendingTail + listOf(removedPending))
        target.now++
        rotation.tick(failTarget)
        val handle = rotation.remove(removedEarly)
        assertNotNull(handle, "止めていた台のハンドルを返す")
        assertTrue(removedEarly in target.open, "返したハンドルはまだ閉じない")
        handle!!.close()
        assertEquals(null, rotation.remove(removedPending), "止めていない台は null")
        while (!rotation.isCycleDone) {
            target.now++
            rotation.tick(failTarget)
        }
        assertEquals(0, removedEarly.writeCalls)
        assertEquals(0, removedPending.writeCalls)
        assertEquals(1, failing.writeCalls, "失敗の回数を返した台は、その周期では書き直さない")
        assertFalse(rotation.quiescedCount > 0)
    }
}
