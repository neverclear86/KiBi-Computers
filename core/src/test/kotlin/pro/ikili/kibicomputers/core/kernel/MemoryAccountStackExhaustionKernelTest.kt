package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * カーネルのプロセスの中で、スタックの底の近くからメモリの勘定に入っても、勘定がずれず、プロセスだけが終わることを確かめる（#856）。
 * [StackExhaustionLockTest]（#828）と同じく、スタックを 256 KiB に縮めたカーネルのスレッドで、本物のマシンとスケジューラを動かす。
 *
 * 犠牲のプロセスは本物の WASM（Endive。[WrittenPagesMemory][pro.ikili.kibicomputers.core.runtime.endive.WrittenPagesMemory] と
 * テーブルの `BudgetedTable`）で、ドライバーが 1 段ずつ深くしながら、底に近い深さのすべてで 1 回ずつ WASM の export を呼び、
 * 確保に入る命令を動かす（ROM の区画への書き込みは中身の写し、`table.grow` は Endive の `grow` と失敗したときの `releaseBytes` まで通る）。
 * ホストの関数の本体から入る道は、export がホストの関数（`ctx.hostFunction` の包み）を呼び、本体が `memory.write*`・`commit`
 * （`commitRange`）で新しい区画に書く。最後はスタックが尽きてプロセスが `call stack exhausted` で終わる。
 *
 * 毎回の後に、プロセスの終わり（`finishLocked` の `releaseAll`）でマシンとサーバー全体の勘定が始める前の値に戻ること、
 * マシンが CRASHED にならないこと、同じ台と別の台のプロセスが進み続けることを確かめ、最後に止めて CPU と許可が戻ったことを確かめる
 */
class MemoryAccountStackExhaustionKernelTest {
    private val limit = Duration.ofSeconds(180)

    /** 底に近い深さで呼ぶ確保 */
    private enum class Op(val export: String) {
        /** ゼロの区画に初めて書く（`i32.store8`。区画の確保だけ） */
        STORE_ZERO("store"),

        /** ROM の区画（データセグメントで書いた所）に初めて書く（`i32.store8`。区画の確保と中身の写し） */
        STORE_ROM("store"),

        /** `memory.grow 1`（端の区画と区画の表を 1 回で数える） */
        MEMORY_GROW("grow"),

        /** `table.grow 1`（`BudgetedTable.counted` → Endive の `grow`） */
        TABLE_GROW("tgrow"),

        /** ホストの関数の本体が `writeU8` で新しい区画に書く（包みを通った後の本体から `commit`） */
        HOST_WRITE("host"),

        /** ホストの関数の本体が `commit` で新しい区画を数える（`kibi:computer/memory` の `commit` → `commitRange`） */
        HOST_COMMIT("host"),
    }

    private class Round(val op: Op) {
        val completed = AtomicInteger()
        val refused = AtomicInteger()

        @Volatile
        var unexpected: Throwable? = null
        var next = 0
    }

    private val i32ToVoid = WasmFunctionType(listOf(WasmValueType.I32), emptyList())

    /** 犠牲のプロセスの WASM（燃料の計測なし）。ROM は区画 [0, ROM_CHUNKS) の先頭 1 バイトずつ、ゼロの区画はその後ろ */
    private fun victim(): Program {
        val wasm = WasmBinary().run {
            val host = importFunc("env", "w", listOf(I32), emptyList())
            memory(MEMORY_PAGES)
            table(1)
            for (i in 0 until ROM_CHUNKS) data(i * CHUNK, byteArrayOf(1))
            // store(addr): addr に 1 を書く
            exportFunc("store", func(listOf(I32), emptyList(), code(0x20, 0, 0x41, 1, 0x3a, 0, 0)))
            // grow(addr): memory.grow 1 を捨てる
            exportFunc("grow", func(listOf(I32), emptyList(), code(0x41, 1, 0x40, 0, 0x1a)))
            // tgrow(addr): table.grow 0 (ref.null func, 1) を捨てる
            exportFunc("tgrow", func(listOf(I32), emptyList(), code(0xd0, 0x70, 0x41, 1, 0xfc, 0x0f, 0, 0x1a)))
            // host(addr): ホストの関数 env.w(addr) を呼ぶ
            exportFunc("host", func(listOf(I32), emptyList(), code(0x20, 0, 0x10, host)))
            build()
        }
        return Program.load(EndiveRuntime(), wasm)
    }

    /** [frames] 段の確かめが通らなければ `true`（何も触らない） */
    private fun lacks(frames: Int): Boolean = try {
        StackHeadroom.ensure(frames)
        false
    } catch (_: StackOverflowError) {
        true
    }

    private fun isStackExhaustion(t: Throwable): Boolean =
        t is StackOverflowError || (t is WasmTrapException && t.kind == WasmTrapKind.STACK_OVERFLOW)

    /** 1 段ずつ深くし、底に近づいたら深さごとに確保を 1 回呼ぶ。最後はスタックが尽きて、プロセスが終わる */
    private fun descend(round: Round, call: (Op, Int) -> Unit, n: Int): Int {
        if (lacks(WINDOW_FRAMES)) {
            val i = round.next++
            val address = when (round.op) {
                Op.STORE_ROM -> (i % ROM_CHUNKS) * CHUNK
                else -> (ROM_CHUNKS + i % ZERO_CHUNKS) * CHUNK
            }
            try {
                call(round.op, address)
                round.completed.incrementAndGet()
            } catch (t: Throwable) {
                if (isStackExhaustion(t)) round.refused.incrementAndGet() else round.unexpected = t
            }
        }
        return descend(round, call, n + 1) + 1
    }

    private fun sweep(kind: ProcessKind, ops: List<Op>, rounds: Int) = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        Harness(config).use { h ->
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000, memoryBytes = MACHINE_BYTES, maxProcessMemoryBytes = MACHINE_BYTES)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
            other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }
            val permits = config.maxConcurrentMachines
            val program = victim()
            for (r in 0 until rounds) {
                for (op in ops) {
                    val machineBefore = m.memoryAccount.usedBytes
                    val serverBefore = h.scheduler.memoryAccount.usedBytes
                    val round = Round(op)
                    val driver = TestDriver(
                        kind,
                        onConfigure = { ctx, b ->
                            b.hostFunction(
                                "env",
                                "w",
                                i32ToVoid,
                                ctx.hostFunction { _, caller, args ->
                                    val address = args[0].toInt()
                                    when (round.op) {
                                        Op.HOST_COMMIT -> caller.memory!!.commit(address, CHUNK, 0)
                                        else -> caller.memory!!.writeU8(address, 1)
                                    }
                                    LongArray(0)
                                },
                            )
                        },
                    ) { ctx ->
                        val instance = ctx.instance
                        // 浅いところで、底で初めて触るクラス（Endive の命令・例外・トラップの種類）を読み込んでおく
                        // （クラスの初期化の途中で尽きると、そのクラスは以後使えなくなり、試験と関係なく壊れる）
                        for (o in Op.entries) instance.call(o.export, WARM_ADDRESS.toLong())
                        isStackExhaustion(WasmTrapException(WasmTrapKind.STACK_OVERFLOW, "warm-up"))
                        descend(round, { o, address -> instance.call(o.export, address.toLong()) }, 0)
                        StepResult.Exit(0)
                    }
                    val rt = m.spawnRt(program, driver, SpawnOptions(allowUnmetered = true))
                    eventuallyDescribe(20_000, { "深い再帰が終わらない（$r 回目、$op）: ${rt.process.state}" }) {
                        h.scheduler.tick()
                        rt.process.state.isTerminal || m.state == MachineState.CRASHED
                    }
                    // 終わりの片付け（finishLocked。インスタンスを閉じて数えた分を返す）はマシンのロックの中で、状態を CRASHED にしてから
                    // 進む（#1031）。ほかのスレッドからマシンのロックを取って（取れることも確かめる）、片付けが終わってから勘定を読む
                    assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$r 回目、$op）: ${m.lock}")
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
                        "#856 $kind $op（$r 回目）: 深さ ${round.next}、通った ${round.completed.get()}、断られた ${round.refused.get()}、" +
                            "プロセス $processAfter、マシン $machineBefore → $machineAfter、サーバー $serverBefore → $serverAfter",
                    )
                    round.unexpected?.let { throw AssertionError("$op: スタックが尽きた以外の失敗", it) }
                    assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$r 回目、$op）: ${rt.lastError}")
                    assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
                    assertEquals("call stack exhausted", crashReason(rt.lastError!!), "${rt.lastError}")
                    assertTrue(round.completed.get() > 0, "$op: 底の近くで確保が通った深さがない（試験の窓が底から遠い）")
                    assertTrue(round.refused.get() > 0, "$op: 確保がスタック不足で断られた深さがない（試験の窓が底まで届かない）")
                    // プロセスの終わりで数えた分をまとめて返したので、マシン・サーバー全体の勘定は元に戻る（ずれていない）
                    assertEquals(0L, processAfter, "$op: プロセスの勘定が残った")
                    assertEquals(machineBefore, machineAfter, "$op: マシンの勘定がずれた")
                    assertEquals(serverBefore, serverAfter, "$op: サーバー全体の勘定がずれた")
                    // 同じ台と別の台のプロセスが進み続ける
                    val same = sameProgress.get()
                    val otherBefore = otherProgress.get()
                    h.tickUntilTrue("同じ台のプロセスが止まった（$r 回目、$op）") { sameProgress.get() > same + 2 }
                    h.tickUntilTrue("別の台のプロセスが止まった（$r 回目、$op）") { otherProgress.get() > otherBefore + 2 }
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
    fun `BLOCKINGでスタックの底の近くから確保に入っても、勘定はずれずプロセスだけが終わる`() =
        sweep(ProcessKind.BLOCKING, Op.entries, rounds = 2)

    @Test
    fun `WAIT_POINTでスタックの底の近くから確保に入っても、勘定はずれずプロセスだけが終わる`() =
        sweep(ProcessKind.WAIT_POINT, Op.entries, rounds = 1)

    private companion object {
        const val CHUNK = 4096

        /** ROM の区画の数（データセグメント 1 つにつき 1 区画） */
        const val ROM_CHUNKS = 2048

        /** ゼロの区画の数 */
        const val ZERO_CHUNKS = 2048

        /** 浅いところで 1 回ずつ呼ぶ（クラスを読み込む）ための区画（ゼロの区画の後ろの 1 ページ） */
        const val WARM_ADDRESS = (ROM_CHUNKS + ZERO_CHUNKS) * CHUNK

        const val MEMORY_PAGES = (ROM_CHUNKS + ZERO_CHUNKS) * CHUNK / 65536 + 1

        const val MACHINE_BYTES = 512L * 1024 * 1024

        /** 底に近い深さを選ぶための確かめの段数（Endive の呼び出しの分を含めて、窓に底を含める） */
        const val WINDOW_FRAMES = 512
    }
}
