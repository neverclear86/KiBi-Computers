package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.InstantiateStackExhaustionTest.Companion.TABLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.InstantiateStackExhaustionTest.Companion.isStackExhaustion
import pro.ikili.kibicomputers.core.kernel.InstantiateStackExhaustionTest.Companion.lacks
import pro.ikili.kibicomputers.core.kernel.InstantiateStackExhaustionTest.Companion.moduleBytes
import pro.ikili.kibicomputers.core.kernel.InstantiateStackExhaustionTest.Kind
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * カーネルのプロセスの中で、スタックの底の近くから `ctx.instantiate` を呼んでも、作りかけのメモリ・テーブルの勘定がずれず、
 * プロセスだけが終わることを確かめる（#884）。[MemoryAccountStackExhaustionKernelTest]（#856）と同じく、スタックを 256 KiB に縮めた
 * カーネルのスレッドで、本物のマシンとスケジューラ・Endive を動かす。
 *
 * 犠牲のプロセスのドライバーが 1 段ずつ深くしながら、底に近い深さのすべてで 1 回ずつ、メモリとテーブルを持つモジュール
 * （[InstantiateStackExhaustionTest.Kind] の型）から `ctx.instantiate` でインスタンスを作る。呼び方は 2 つ: ドライバーの本体から直接
 * （[Via.DRIVER]）と、WASM の export が呼ぶホストの関数（`ctx.hostFunction` の包み）の本体から（[Via.HOST]）。
 * 最後はスタックが尽きてプロセスが `call stack exhausted` で終わる。
 *
 * 毎回の後に、プロセスの終わり（`finishLocked`。作れた子のインスタンスも閉じる）でマシンとサーバー全体の勘定が始める前の値に戻ること、
 * マシンが CRASHED にならないこと、マシンのロックが取れること、同じ台と別の台のプロセスが進み続けることを確かめ、最後に止めて CPU と許可が
 * 戻ったことを確かめる。尽きる位置が入口の確かめを外しても窓に当たるとは限らない（#945）ので、窓の検知は [InstantiateStackExhaustionTest] が受け持つ
 */
class InstantiateStackExhaustionKernelTest {
    private val limit = maxOf(Duration.ofSeconds(240), stackHeadroomTestLimit())

    /** `ctx.instantiate` の呼び方 */
    private enum class Via {
        /** ドライバーの本体（Kotlin の再帰の底）から直接 */
        DRIVER,

        /** export → ホストの関数の本体から */
        HOST,
    }

    private class Round(val via: Via, val kind: Kind, val child: WasmModule) {
        val created = AtomicInteger()
        val failed = AtomicInteger()
        val refused = AtomicInteger()

        @Volatile
        var unexpected: Throwable? = null
        var next = 0
    }

    private val voidToVoid = WasmFunctionType(emptyList(), emptyList())

    /** 犠牲のプロセスの WASM（燃料の計測なし）。`host()` がホストの関数 `env.i` を呼ぶ */
    private fun victim(): Program {
        val wasm = WasmBinary().run {
            val host = importFunc("env", "i", emptyList(), emptyList())
            memory(1)
            table(1)
            exportFunc("host", func(emptyList(), emptyList(), code(0x10, host)))
            build()
        }
        return Program.load(EndiveRuntime(), wasm)
    }

    /** 子を 1 回作る。スタックの不足は投げ直し、型の期待どおりの失敗は数えて飲む */
    private fun instantiateChild(ctx: ProcessContext, round: Round) {
        try {
            ctx.instantiate(round.child) { }
            round.created.incrementAndGet()
        } catch (t: Throwable) {
            if (isStackExhaustion(t)) throw t
            round.failed.incrementAndGet()
        }
    }

    /** 1 段ずつ深くし、底に近づいたら深さごとに子を 1 回作る。最後はスタックが尽きて、プロセスが終わる */
    private fun descend(round: Round, call: () -> Unit, n: Int): Int {
        if (lacks(WINDOW_FRAMES)) {
            round.next++
            try {
                call()
            } catch (t: Throwable) {
                if (isStackExhaustion(t)) round.refused.incrementAndGet() else round.unexpected = t
            }
        }
        return descend(round, call, n + 1) + 1
    }

    private fun sweep(kind: ProcessKind, rounds: Int) = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        Harness(config, moduleLimits = ModuleLimits(maxTableElements = TABLE_LIMIT)).use { h ->
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000, memoryBytes = MACHINE_BYTES, maxProcessMemoryBytes = MACHINE_BYTES)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
            other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }
            val permits = config.maxConcurrentMachines
            val program = victim()
            val runtime = EndiveRuntime()
            val children = Kind.entries.associateWith { runtime.load(moduleBytes(it)) }
            for (r in 0 until rounds) {
                for (via in Via.entries) {
                    for (k in Kind.entries) {
                        val machineBefore = m.memoryAccount.usedBytes
                        val serverBefore = h.scheduler.memoryAccount.usedBytes
                        val round = Round(via, k, children.getValue(k))
                        val driver = TestDriver(
                            kind,
                            onConfigure = { ctx, b ->
                                b.hostFunction(
                                    "env",
                                    "i",
                                    voidToVoid,
                                    ctx.hostFunction { c, _, _ ->
                                        instantiateChild(c, round)
                                        LongArray(0)
                                    },
                                )
                            },
                        ) { ctx ->
                            val instance = ctx.instance
                            // 浅いところで、底で初めて触るクラス（Endive のインスタンス・例外、失敗の翻訳）を読み込んでおく
                            // （クラスの初期化の途中で尽きると、そのクラスは以後使えなくなり、試験と関係なく壊れる）
                            for (c in children.values) {
                                try {
                                    ctx.instantiate(c) { }
                                } catch (t: Throwable) {
                                    if (!isStackExhaustion(t)) t.toString()
                                }
                            }
                            try {
                                instance.call("host")
                            } catch (t: Throwable) {
                                // START_RECURSION の子は、浅いところでも start の中で尽きる
                                if (!isStackExhaustion(t)) throw t
                            }
                            isStackExhaustion(WasmTrapException(WasmTrapKind.STACK_OVERFLOW, "warm-up"))
                            round.created.set(0)
                            round.failed.set(0)
                            val call: () -> Unit = when (via) {
                                Via.DRIVER -> ({ instantiateChild(ctx, round) })
                                Via.HOST -> ({ instance.call("host") })
                            }
                            descend(round, call, 0)
                            StepResult.Exit(0)
                        }
                        val rt = m.spawnRt(program, driver, SpawnOptions(allowUnmetered = true))
                        eventuallyDescribe(30_000, { "深い再帰が終わらない（$r 回目、$via $k）: ${rt.process.state}" }) {
                            h.scheduler.tick()
                            rt.process.state.isTerminal || m.state == MachineState.CRASHED
                        }
                        val label = "$via $k（$r 回目）"
                        // 終わりの片付け（finishLocked。作れた子を閉じて返す）はマシンのロックの中で、状態を CRASHED にしてから進むので、
                        // ほかのスレッドからマシンのロックを取って（取れることも確かめる）、片付けが終わってから勘定を読む
                        assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$label）: ${m.lock}")
                        val processAfter: Long
                        val machineAfter: Long
                        val serverAfter: Long
                        try {
                            processAfter = rt.memory.usedBytes
                            machineAfter = m.memoryAccount.usedBytes
                            serverAfter = h.scheduler.memoryAccount.usedBytes
                        } finally {
                            m.lock.unlock()
                        }
                        println(
                            "#884 $kind $label: 深さ ${round.next}、作れた ${round.created.get()}、失敗 ${round.failed.get()}、断られた ${round.refused.get()}、" +
                                "マシン $machineBefore → $machineAfter、サーバー $serverBefore → $serverAfter",
                        )
                        round.unexpected?.let { throw AssertionError("$label: スタックが尽きた以外の失敗", it) }
                        assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$label）: ${rt.lastError}")
                        assertEquals(ProcessState.CRASHED, rt.process.state, "$label: ${rt.lastError}")
                        assertEquals("call stack exhausted", crashReason(rt.lastError!!), "$label: ${rt.lastError}")
                        assertTrue(round.refused.get() > 0, "$label: スタック不足で断られた深さがない（試験の窓が底まで届かない）")
                        when (k) {
                            Kind.SUCCESS -> assertTrue(round.created.get() > 0, "$label: 底の近くで作れた深さがない（試験の窓が底から遠い）")
                            Kind.START_RECURSION -> {}
                            else -> assertTrue(round.failed.get() > 0, "$label: 底の近くで最後まで進んで失敗した深さがない（試験の窓が底から遠い）")
                        }
                        // プロセスの終わりで、作れた子も閉じて数えた分を返したので、マシン・サーバー全体の勘定は元に戻る
                        assertEquals(0L, processAfter, "$label: プロセスの勘定が残った")
                        assertEquals(machineBefore, machineAfter, "$label: マシンの勘定がずれた")
                        assertEquals(serverBefore, serverAfter, "$label: サーバー全体の勘定がずれた")
                        // 同じ台と別の台のプロセスが進み続ける
                        val same = sameProgress.get()
                        val otherBefore = otherProgress.get()
                        h.tickUntilTrue("同じ台のプロセスが止まった（$label）") { sameProgress.get() > same + 2 }
                        h.tickUntilTrue("別の台のプロセスが止まった（$label）") { otherProgress.get() > otherBefore + 2 }
                    }
                }
            }
            m.stop()
            other.stop()
            h.tickUntilTrue("止まらない") { m.state == MachineState.STOPPED && other.state == MachineState.STOPPED }
            eventually { m.cpu.currentOwner == null && m.cpu.waiting == 0 }
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") { h.scheduler.permits.availablePermits() == permits }
            assertEquals(0L, m.memoryAccount.usedBytes, "止めた後にマシンの勘定が残った")
        }
    }

    @Test
    fun `BLOCKINGでスタックの底の近くからctx_instantiateを呼んでも、勘定はずれずプロセスだけが終わる`() =
        sweep(ProcessKind.BLOCKING, rounds = 2)

    @Test
    fun `WAIT_POINTでスタックの底の近くからctx_instantiateを呼んでも、勘定はずれずプロセスだけが終わる`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 1)

    private companion object {
        const val MACHINE_BYTES = 512L * 1024 * 1024

        /** 底に近い深さを選ぶための確かめの段数（Endive の `build` とホストの関数の包みの分を含めて、窓に底を含める） */
        const val WINDOW_FRAMES = 640
    }
}
