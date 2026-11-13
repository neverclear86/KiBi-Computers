package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 再起動を繰り返すマシンの、起動し直すまでの待ち（#635、ADR 0635）。tick は手で進める */
class RebootBackoffMachineTest {
    private val limit = Duration.ofSeconds(30)

    /** 1回目は待たず、2回目から 3・6・12 tick。1,000 tick 動くと1段戻る（借りの上限は 4,000） */
    private val backoff = RebootBackoff(freeReboots = 1, baseDelayTicks = 3, maxDelayTicks = 1000, decayTicks = 1000, capLevels = 4)

    private class Recorder : MachineHost {
        val reboots = AtomicInteger()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

        override fun onRebootRequested(machine: Machine) {
            reboots.incrementAndGet()
            super.onRebootRequested(machine)
        }
    }

    private class Env(val h: Harness, val m: Machine, val host: Recorder, val config: BootConfig) {
        val now: Long get() = h.scheduler.now

        fun tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
            h.scheduler.tick()
            Thread.sleep(1)
            condition()
        }

        fun tickUntilRunning() = tickUntil("起動し直さない") { m.state == MachineState.RUNNING && !m.isBooting }

        /** tick を進めずに、init が動いている（起動の途中でない）のを待つ */
        fun awaitRunning() = eventually(10_000, "起動しない") { m.state == MachineState.RUNNING && !m.isBooting }

        /**
         * [Machine.reboot] を求め、止まって、再起動の求めを通知に積む（か、待ちに入る）ところまで進める。tick は進めない。
         * 戻ったときは、借りと期限が決まっている（通知はまだ配っていない）
         */
        fun rebootAndAwaitPost() {
            val posted = CountDownLatch(1)
            h.scheduler.hooks.duringRebootPost = {
                h.scheduler.hooks.duringRebootPost = null
                posted.countDown()
            }
            assertTrue(m.reboot())
            assertTrue(posted.await(10, TimeUnit.SECONDS), "再起動の求めを積まない")
        }

        /** [Machine.reboot] を求め、待ちに入るまで進める。待ちの期限の tick を返す */
        fun rebootAndAwaitDeferral(): Long {
            rebootAndAwaitPost()
            val due = m.deferredRebootDueTick
            assertTrue(due != 0L, "待ちに入らない")
            return due
        }

        /**
         * 待たずに起動し直す [Machine.reboot]。止まって積んだ後、待ちに入らず（tick を進める前に確かめる）、次の tick 1回で起動し直す
         */
        fun rebootImmediately() {
            val before = host.reboots.get()
            rebootAndAwaitPost()
            assertEquals(0L, m.deferredRebootDueTick, "待たずに起動し直すはずが、待ちに入った")
            h.scheduler.tick()
            assertEquals(before + 1, host.reboots.get(), "次の tick で起動し直さない")
            tickUntilRunning()
        }

        /** 期限の前の tick を1つずつ進め、その間は起動し直さないことを確かめる */
        fun tickUpTo(due: Long, reboots: Int) {
            while (now + 1 < due) {
                h.scheduler.tick()
                assertEquals(reboots, host.reboots.get(), "期限の前（tick $now、期限 $due）に起動し直した")
            }
        }
    }

    private fun env(block: Env.() -> Unit) = timeout(limit) {
        Harness(rebootBackoff = backoff).use { h ->
            val host = Recorder()
            val init = "fake-init".toByteArray()
            h.runtime.define(init, foreverInit())
            val disk = TestMemoryFileSystem()
            disk.createDirectory(VfsPath.parse("/sbin"))
            val node = disk.createFile(VfsPath.parse(BootConfig.INIT_PATH))
            disk.write(node, 0, init, 0, init.size)
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "d") BootVolume(disk, readOnly = false) else null }
            val m = h.machine(host = host)
            val config = BootConfig.builder().bootDisks(listOf("d")).build()
            val e = Env(h, m, host, config)
            assertTrue(m.boot(config))
            e.tickUntilRunning()
            e.block()
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    /** init の `_start` が回った回数。init がスレッドを持って動いているかを見る */
    private val initLoops = java.util.concurrent.atomic.AtomicInteger()

    private fun foreverInit(): FakeModule = FakeModule.builder()
        .fuelMetered()
        .export("_start", VOID) { ctx, _ ->
            while (true) {
                ctx.step(1)
                initLoops.incrementAndGet()
                Thread.sleep(1)
            }
            @Suppress("UNREACHABLE_CODE")
            LongArray(0)
        }
        .build()

    @Test
    fun `繰り返す再起動は期限のtickまで遅らせ、期限のtickで1回だけ起動し直す`() = env {
        rebootImmediately()
        assertEquals(1, host.reboots.get())

        val posted = now
        val due = rebootAndAwaitDeferral()
        assertTrue(due >= posted + 3, "2回目は 3 tick 待つ: posted=$posted due=$due")
        // 待つ間: プロセスもスレッドもなく STOPPED だが、再起動の途中
        assertTrue(m.awaitTermination(5_000))
        assertEquals(MachineState.STOPPED, m.state)
        assertTrue(m.isRebootPending)
        assertTrue(m.isRunningBootingOrRebooting)
        assertFalse(m.isAtWaitPoint)
        assertEquals(SaveReadiness.TRANSIENT, m.saveReadiness())
        // 待ちは tick でしか解けない（メインスレッドで保存を待つ呼び手が、待っても無駄。#635）
        assertFalse(m.isSettlingForSave)
        assertEquals(1, h.scheduler.pendingTickWakes)

        tickUpTo(due, reboots = 1)
        h.scheduler.tick()
        assertEquals(due, now)
        assertEquals(2, host.reboots.get(), "期限の tick で起動し直す")
        assertEquals(0L, m.deferredRebootDueTick)
        assertEquals(0, h.scheduler.pendingTickWakes)
        tickUntilRunning()
        repeat(20) { h.scheduler.tick() }
        assertEquals(2, host.reboots.get(), "1回だけ")

        // 3回目はもっと待つ
        val due3 = rebootAndAwaitDeferral() - now
        assertTrue(due3 >= 6, "3回目は 6 tick: $due3")
    }

    @Test
    fun `起動の途中と、rebootを受け付けて止まり切る前は、保存を待つ価値がある`() = env {
        // アンロード・停止の待ち（#635）が、tick を進めずに解ける TRANSIENT を待たなくならないことを確かめる
        m.stop()
        assertTrue(m.awaitTermination(5_000))
        // 起動の途中（init を作る前）: 起動のスレッドが進めば解ける
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.scheduler.hooks.beforeInitExec = { _ ->
            h.scheduler.hooks.beforeInitExec = null
            reached.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(m.boot(config))
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS), "起動の途中で止まらない")
            assertTrue(m.isBooting)
            assertEquals(SaveReadiness.TRANSIENT, m.saveReadiness())
            assertTrue(m.isSettlingForSave, "起動の途中は待てば解ける")
        } finally {
            release.countDown()
        }
        awaitRunning()
        // 燃料は tick でしか届かず、起動した直後の init はスレッドを持たずに燃料待ちのことがある。
        // 1 tick 進めて、init がスレッドを持って回っているのを確かめてから reboot する（持たない init は止める時に登録を外す所を通らない）
        val loops = initLoops.get()
        h.scheduler.tick()
        eventually(10_000, "init が回らない") { initLoops.get() >= loops + 3 }

        // reboot を受け付けて、init のスレッドが止まり切る前（求めの印だけ）: プロセスのスレッドが進めば解ける
        val atGate = CountDownLatch(1)
        val gate = CountDownLatch(1)
        h.scheduler.hooks.beforeUnregister = { machine, pid ->
            if (machine === m && pid == 1 && atGate.count > 0) {
                atGate.countDown()
                gate.awaitKeepingInterrupt()
            }
        }
        try {
            assertTrue(m.reboot())
            assertTrue(atGate.await(5, TimeUnit.SECONDS), "init のスレッドが登録を外す前に来ない")
            assertTrue(m.isRebootPending)
            assertEquals(SaveReadiness.TRANSIENT, m.saveReadiness())
            assertTrue(m.isSettlingForSave, "止まり切る前は待てば解ける")
        } finally {
            h.scheduler.hooks.beforeUnregister = null
            gate.countDown()
        }
        // 止まり切って通知を積んだ後（待たない回）は、tick でしか解けない。tick を進めないので通知は配られない
        eventually(10_000, "通知を積まない") { !m.isSettlingForSave }
        assertTrue(m.isRebootPending)
        assertEquals(0L, m.deferredRebootDueTick, "1回目は待たない")
        assertEquals(SaveReadiness.TRANSIENT, m.saveReadiness())
        assertEquals(0, host.reboots.get())
        h.scheduler.tick()
        assertEquals(1, host.reboots.get())
        tickUntilRunning()
    }

    @Test
    fun `待つ間にstopすると再起動せず、借りも返す`() = env {
        rebootImmediately()
        val due = rebootAndAwaitDeferral()
        assertTrue(m.rebootDebt > 0)
        m.stop()
        assertFalse(m.isRebootPending)
        assertEquals(0L, m.deferredRebootDueTick)
        assertEquals(0L, m.rebootDebt)
        assertEquals(SaveReadiness.READY, m.saveReadiness())
        assertFalse(m.isSettlingForSave)
        while (now < due + 5) h.scheduler.tick()
        assertEquals(1, host.reboots.get(), "取り消した再起動はしない")
        assertEquals(MachineState.STOPPED, m.state)
        assertEquals(0, h.scheduler.pendingTickWakes, "期限が来た項目は捨てる")

        // 借りを返したので、次の起動からの再起動は待たない
        assertTrue(m.boot(config))
        tickUntilRunning()
        rebootImmediately()
    }

    @Test
    fun `待つ間にbootすると受け付け、古い期限では起動し直さない`() = env {
        rebootImmediately()
        val due = rebootAndAwaitDeferral()
        assertTrue(m.boot(config), "待つ間は STOPPED なので boot を受け付ける")
        assertFalse(m.isRebootPending)
        tickUntilRunning()
        // 借りは残っているので、すぐの再起動はさらに待つ。古い期限ではなく、新しい期限で起動し直す
        val due2 = rebootAndAwaitDeferral()
        assertTrue(due2 > due, "新しい期限のほうが後: $due → $due2")
        tickUpTo(due2, reboots = 1)
        h.scheduler.tick()
        assertEquals(2, host.reboots.get())
    }

    @Test
    fun `待つ間に重ねてrebootしても、期限も借りも列の項目も起動し直す回数も変わらない`() = env {
        rebootImmediately()
        val due = rebootAndAwaitDeferral()
        val debt = m.rebootDebt
        assertTrue(m.reboot(), "重ねた求めも受け付ける")
        assertEquals(due, m.deferredRebootDueTick, "今の期限のまま")
        assertEquals(debt, m.rebootDebt, "借りを増やさない")
        assertEquals(1, h.scheduler.pendingTickWakes, "列に足さない")
        assertTrue(m.isRebootPending)
        assertEquals(SaveReadiness.TRANSIENT, m.saveReadiness())

        tickUpTo(due, reboots = 1)
        h.scheduler.tick()
        assertEquals(2, host.reboots.get(), "期限の tick で1回だけ起動し直す")
        assertEquals(0, h.scheduler.pendingTickWakes)
        tickUntilRunning()
        repeat(20) { h.scheduler.tick() }
        assertEquals(2, host.reboots.get(), "重ねた分は起動し直さない")
    }

    @Test
    fun `通知を積んだ後、配る前に重ねてrebootしても、借りも起動し直す回数も変わらない`() = env {
        rebootAndAwaitPost()
        assertEquals(0L, m.deferredRebootDueTick, "1回目は待たない")
        val debt = m.rebootDebt
        assertEquals(backoff.decayTicks, debt)
        assertTrue(m.reboot(), "重ねた求めも受け付ける")
        assertEquals(debt, m.rebootDebt, "借りを増やさない")
        assertEquals(0L, m.deferredRebootDueTick, "待ちに入らない")
        assertTrue(m.isRebootPending)

        h.scheduler.tick()
        assertEquals(1, host.reboots.get(), "最初の通知で1回だけ起動し直す")
        tickUntilRunning()
        repeat(20) { h.scheduler.tick() }
        assertEquals(1, host.reboots.get(), "重ねた分は起動し直さない")
        assertEquals(debt, m.rebootDebt)
    }

    @Test
    fun `前の起動で積んだ古い通知が残っていても、新しい待ちを飛ばさない`() = env {
        // 1回目（待たない）の通知を積んだ後、配る前に boot する（積んだ印は boot が下ろす）。tick を進めないので、古い通知は列に残る
        rebootAndAwaitPost()
        assertEquals(0L, m.deferredRebootDueTick)
        assertTrue(m.boot(config), "通知を積んだ後は STOPPED なので boot を受け付ける")
        awaitRunning()
        // 2回目は待つ。待ちに入った後で、古い通知を配る tick が来る
        val due = rebootAndAwaitDeferral()
        assertTrue(now + 1 < due)
        h.scheduler.tick()
        assertEquals(0, host.reboots.get(), "古い通知で、待ちを飛ばして起動し直した")
        assertTrue(m.isRebootPending)
        tickUpTo(due, reboots = 0)
        h.scheduler.tick()
        assertEquals(1, host.reboots.get(), "期限の tick で起動し直す")
    }

    @Test
    fun `動いたtick数だけ借りが減る`() = env {
        rebootImmediately()
        assertEquals(backoff.decayTicks, m.rebootDebt)
        repeat(300) { h.scheduler.tick() }
        val ran = now - m.initStartedTick
        assertTrue(ran in 300 until backoff.decayTicks, "init が動いた tick 数: $ran")
        rebootAndAwaitPost()
        assertEquals(2 * backoff.decayTicks - ran, m.rebootDebt, "借りは、動いた分だけ減ってから、1回分増える")
    }

    @Test
    fun `長く動いてから再起動すると、借りが減って待たない`() = env {
        rebootImmediately()
        val due = rebootAndAwaitDeferral()
        assertTrue(m.rebootDebt > backoff.decayTicks)
        tickUpTo(due, reboots = 1)
        h.scheduler.tick()
        tickUntilRunning()
        // 2段分（2,000 tick）を超えて動けば、借りが 1 段分に戻る（待たない。rebootImmediately が待ちに入らないことを確かめる）
        repeat(2_100) { h.scheduler.tick() }
        rebootImmediately()
        assertEquals(backoff.decayTicks, m.rebootDebt)
    }

    @Test
    fun `起動の途中のrebootは動いた時間を0と数える`() = env {
        rebootImmediately()
        rebootAndAwaitDeferral()
        m.rebootDebt = 2 * backoff.decayTicks
        // 待つ間に boot し、init を作る前で止める。その間に tick が進んでも、init は動いていない
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.scheduler.hooks.beforeInitExec = { _ ->
            h.scheduler.hooks.beforeInitExec = null
            reached.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(m.boot(config))
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        repeat(500) { h.scheduler.tick() }
        assertTrue(m.isBooting)
        assertEquals(-1L, m.initStartedTick)
        val posted = CountDownLatch(1)
        h.scheduler.hooks.duringRebootPost = {
            h.scheduler.hooks.duringRebootPost = null
            posted.countDown()
        }
        assertTrue(m.reboot())
        release.countDown()
        assertTrue(posted.await(10, TimeUnit.SECONDS), "再起動の求めを積まない")
        assertEquals(3 * backoff.decayTicks, m.rebootDebt, "動いた時間は 0: 借りは減らず、1回分増える")
        assertTrue(m.deferredRebootDueTick != 0L, "3 段目なので待つ")
    }

    @Test
    fun `少しずつ動いてから再起動を繰り返しても、いずれ待たされる`() = env {
        // 閾値で回数を 0 に戻す形なら、閾値のすぐ上（ここでは decay の半分）で回す init は待たずに済んでしまう
        var deferred = false
        for (i in 0 until 10) {
            repeat(500) { h.scheduler.tick() }
            val before = host.reboots.get()
            assertTrue(m.reboot())
            // 待ちに入ったかを tick の前に見る（期限を過ぎて見落とさないため）
            eventually(10_000, "起動し直さず、待ちにも入らない") {
                if (m.deferredRebootDueTick != 0L) {
                    true
                } else {
                    h.scheduler.tick()
                    host.reboots.get() > before
                }
            }
            if (m.deferredRebootDueTick != 0L) {
                deferred = true
                break
            }
            tickUntilRunning()
        }
        assertTrue(deferred, "待ちに入らない")
    }
}
