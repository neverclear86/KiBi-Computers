package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * コンソールの出力の予約（`Machine.consolePumpsReserved`。#419）が、スタックが尽きても残らないことを確かめる（#890）。
 *
 * 予約の持ち主はプロセスで、`pumpConsole` の例外でない道か、プロセスの終わり（`finishLocked`）の早い方で 1 回だけ戻る。
 * `StackExhaustionLockTest` の `CONSOLE` は尽きる位置を段数でずらすので、窓に落ちるかは環境に左右される。ここでは窓を決まった形で突く:
 * - 予約してから `pumpConsole` に入るまで（`flushConsole` の先頭のフック `beforeConsoleFlush`）で `StackOverflowError` を投げる
 * - `pumpConsole` の中で、表示デバイスのロックを取る途中（`ReentrantLock.lock`）で `StackOverflowError` を投げる
 *
 * どちらも、プロセスは `call stack exhausted` で終わり、予約は 0 に戻り、TTY に残った出力はメインスレッドが書き手のいないものとして配る
 * （予約が残ると、その台のコンソールの表示が止まる）
 */
internal class ConsolePumpReservationTest {
    private val limit = Duration.ofSeconds(60)

    private val payload = "reservation #890".toByteArray()

    /**
     * テスト用の書く先。[armed] に入れたスレッドが次に [lock] の `lock()` を呼ぶと、1 回だけ `StackOverflowError` を投げる
     * （ロックを取る途中で尽きたのと同じ。取る前に投げるので、ロックの状態は壊れない）。メインスレッドは `tryLock` で取るので投げない
     */
    private class TestSink : ConsoleSink {
        val armed = AtomicReference<Thread?>()
        val written = AtomicInteger()

        override val lock: ReentrantLock = object : ReentrantLock() {
            override fun lock() {
                if (armed.compareAndSet(Thread.currentThread(), null)) throw StackOverflowError("テストで投げた")
                super.lock()
            }
        }

        override fun write(bytes: ByteArray): Long {
            written.addAndGet(bytes.size)
            return bytes.size.toLong()
        }
    }

    /** ホストの関数 `env.h` の本体: TTY に [payload] を書いて予約し（[reserves] 回）、ロックの外で流す */
    private fun writeConsole(ctx: ProcessContext, tty: Tty, reserves: Int = 1, before: () -> Unit = {}) {
        ctx.withKernelLock {
            tty.write(payload, 0, payload.size, ctx.wakeSink)
            repeat(reserves) { ctx.reserveConsoleFlushLocked() }
        }
        before()
        ctx.flushConsole()
    }

    private fun module(): FakeModule = FakeModule.builder()
        .fuelMetered()
        .import("env", "h", VOID)
        .export("run", VOID) { ctx, _ ->
            ctx.callImport("env", "h")
            LongArray(0)
        }
        .build()

    private class Rig(val h: Harness, val m: Machine, val sink: TestSink, val tty: Tty)

    private fun rig(block: (Rig) -> Unit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 50_000_000)
            val sink = TestSink()
            m.consoleSink = sink
            val tty = m.lock.withLockValue { m.consoleTtyLocked() }
            // init は回り続けるもの（テストのプロセスが init だと、尽きて終わったときに台ごと止まる）
            val init = m.spawnRt(h.program(spinForever()), runDriver())
            eventually(10_000, "init が動かない") {
                h.scheduler.tick()
                init.process.state != ProcessState.CREATED
            }
            block(Rig(h, m, sink, tty))
            m.stop()
            eventually(10_000, "マシンが止まらない") {
                h.scheduler.tick()
                m.state == MachineState.STOPPED
            }
        }
    }

    private fun Rig.spawn(kind: ProcessKind, body: (ProcessContext) -> Unit): ProcessRuntime {
        val driver = runDriver(kind) { ctx, b -> b.host(ctx) { c, _, _ -> body(c).let { LongArray(0) } } }
        return m.spawnRt(h.program(module()), driver)
    }

    private fun Rig.awaitEnd(rt: ProcessRuntime) = eventually(10_000, "プロセスが終わらない: ${rt.process.state}") {
        h.scheduler.tick()
        rt.process.state.isTerminal
    }

    private fun Rig.outputAvailable(): Int = m.lock.withLockValue { tty.outputAvailable }

    /** 尽きて終わった後に、予約が 0 に戻り、TTY に残った出力をメインスレッドが配る（表示が止まらない） */
    private fun Rig.assertCrashedAndDrained(rt: ProcessRuntime) {
        awaitEnd(rt)
        assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
        assertEquals("call stack exhausted", crashReason(rt.lastError!!), "${rt.lastError}")
        assertEquals(0, m.consolePumpsReservedForTest, "コンソールの出力の予約が残った")
        eventually(10_000, "TTY に残った出力をメインスレッドが配らない（${outputAvailable()} バイト）") {
            h.scheduler.tick()
            outputAvailable() == 0
        }
        assertEquals(0, sink.written.get(), "プロセスのスレッドが書いた（窓を突けていない）")
        assertEquals(MachineState.RUNNING, m.state)
    }

    @ParameterizedTest
    @EnumSource(ProcessKind::class)
    fun `予約してからpumpConsoleに入るまでにスタックが尽きても、予約は残らない`(kind: ProcessKind) = timeout(limit) {
        rig { r ->
            r.h.scheduler.hooks.beforeConsoleFlush = { _, _ -> throw StackOverflowError("テストで投げた") }
            try {
                val rt = r.spawn(kind) { ctx -> writeConsole(ctx, r.tty) }
                r.assertCrashedAndDrained(rt)
            } finally {
                r.h.scheduler.hooks.beforeConsoleFlush = null
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProcessKind::class)
    fun `pumpConsoleの中で表示デバイスのロックを取る途中でスタックが尽きても、予約は残らない`(kind: ProcessKind) = timeout(limit) {
        rig { r ->
            val rt = r.spawn(kind) { ctx -> writeConsole(ctx, r.tty) { r.sink.armed.set(Thread.currentThread()) } }
            r.assertCrashedAndDrained(rt)
            assertEquals(null, r.sink.armed.get(), "表示デバイスのロックを取りに行かなかった")
            // 表示デバイスのロックは壊れていない（取る前に投げた）
            assertTrue(r.sink.lock.tryLock(5, TimeUnit.SECONDS))
            r.sink.lock.unlock()
        }
    }

    @ParameterizedTest
    @EnumSource(ProcessKind::class)
    fun `ふつうに書いた後と、続けて予約した後も、予約は0に戻る`(kind: ProcessKind) = timeout(limit) {
        rig { r ->
            // 1 回のロックの区間で 2 回予約しても、1 つのプロセスが数えるのは 1 つまで（flushConsole 1 回で戻る）
            val rt = r.spawn(kind) { ctx ->
                writeConsole(ctx, r.tty)
                writeConsole(ctx, r.tty, reserves = 2)
            }
            r.awaitEnd(rt)
            assertEquals(ProcessState.EXITED, rt.process.state, "${rt.lastError}")
            assertEquals(0, r.m.consolePumpsReservedForTest)
            // 書き手のスレッドが自分で書いた
            assertEquals(payload.size * 2, r.sink.written.get())
            assertEquals(0, r.outputAvailable())
        }
    }

    @Test
    fun `予約したまま止まっている書き手を止めても、予約は1回だけ戻る`() = timeout(limit) {
        rig { r ->
            val atGate = CountDownLatch(1)
            val gate = CountDownLatch(1)
            r.h.scheduler.hooks.beforeConsoleFlush = { _, _ ->
                atGate.countDown()
                gate.awaitKeepingInterrupt()
            }
            try {
                val rt = r.spawn(ProcessKind.BLOCKING) { ctx -> writeConsole(ctx, r.tty) }
                eventually(10_000, "書き手が門に着かない") {
                    r.h.scheduler.tick()
                    atGate.count == 0L
                }
                assertEquals(1, r.m.consolePumpsReservedForTest)
                // 書き手が予約している間は、メインスレッドは TTY の出力を取り出さない（#419）
                repeat(5) { r.h.scheduler.tick() }
                assertEquals(payload.size, r.outputAvailable())
                // 止める（スレッドのあるプロセスには割り込むだけ。終わりの片付けは、門を開けた後に書き手のスレッドが行う）
                r.m.stop()
                assertEquals(1, r.m.consolePumpsReservedForTest)
                r.h.scheduler.hooks.beforeConsoleFlush = null
                gate.countDown()
                eventually(10_000, "書き手が終わらない") {
                    r.h.scheduler.tick()
                    rt.process.state.isTerminal
                }
                // pumpConsole と終わりの片付けの両方を通っても、負にならない
                assertEquals(0, r.m.consolePumpsReservedForTest)
            } finally {
                r.h.scheduler.hooks.beforeConsoleFlush = null
                gate.countDown()
            }
        }
    }
}

