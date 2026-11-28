package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
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
import kotlin.concurrent.withLock
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * カーネルのプロセスの中で、スタックの底の近くからホストの関数の本体が `/tmp` を変えても、RAM の勘定（マシン・サーバー全体）と
 * `/tmp` の木がずれず、プロセスだけが終わることを確かめる（#883）。形は [MemoryAccountStackExhaustionKernelTest]（#856）と同じ。
 *
 * スタックを 256 KiB に縮めたカーネルのスレッドで本物のマシンとスケジューラを動かし、`machine.mountTmp()` で `/tmp` をつなぐ。
 * 犠牲のプロセスは本物の WASM（Endive）で、ドライバーが 1 段ずつ深くしながら、底に近い深さのすべてで export を呼ぶ。
 * export はホストの関数（`ctx.hostFunction` の包み）を呼び、本体が `ctx.vfs` で `/tmp` を開く（作る・切り詰める）・書く・名前を変える・消す。
 * 最後はスタックが尽きて、プロセスが `call stack exhausted` で終わる。
 *
 * 毎回の後に、マシンが CRASHED にならないこと、`/tmp` の木と `usedBytes` が食い違わないこと（[TmpFileSystem.consistencyProblemsForTest]）、
 * マシン・サーバー全体の勘定から `/tmp` の分を引いた値が始める前と同じこと（動いている間は `/tmp` の中身が勘定に残るのが正しいので、
 * 0 とは比べない）、同じ台と別の台のプロセスが進み続けることを確かめる。最後に止めると `/tmp` が外れ、マシンの勘定が 0、
 * サーバー全体が始める前の値に戻る
 */
class TmpFileSystemStackExhaustionKernelTest {
    private val limit = Duration.ofSeconds(180)

    /** 底に近い深さで、ホストの関数の本体が `/tmp` にすること */
    private enum class Op {
        /** 作る・切り詰めて開き、3 塊分を書いて閉じる */
        OPEN_TRUNCATE_WRITE,

        /** 名前を行き来させる（置き換えのない名前の変更） */
        RENAME,

        /** ほかのファイルを置き換える名前の変更（置き換えた先の塊を返す）。次の回のために、置き換えられた側を作り直す */
        RENAME_REPLACE,

        /** 作って書き、消す */
        CREATE_REMOVE,
    }

    private class Round(val op: Op) {
        val completed = AtomicInteger()
        val refused = AtomicInteger()

        @Volatile
        var unexpected: Throwable? = null
        var next = 0
    }

    private val i32ToVoid = WasmFunctionType(listOf(WasmValueType.I32), emptyList())

    /** 犠牲のプロセスの WASM（燃料の計測なし）。`host(n)` がホストの関数 `env.w(n)` を呼ぶ */
    private fun victim(): Program {
        val wasm = WasmBinary().run {
            val host = importFunc("env", "w", listOf(I32), emptyList())
            memory(1)
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

    /** 1 段ずつ深くし、底に近づいたら深さごとにホストの関数を 1 回呼ぶ。最後はスタックが尽きて、プロセスが終わる */
    private fun descend(round: Round, call: (Int) -> Unit, n: Int): Int {
        if (lacks(WINDOW_FRAMES)) {
            val i = round.next++
            try {
                call(i)
                round.completed.incrementAndGet()
            } catch (t: Throwable) {
                if (isStackExhaustion(t)) round.refused.incrementAndGet() else round.unexpected = t
            }
        }
        return descend(round, call, n + 1) + 1
    }

    /** ホストの関数の本体（`ctx.vfs` で `/tmp` を変える） */
    private fun body(machine: Machine, op: Op, i: Int) {
        val vfs = machine.vfs
        when (op) {
            Op.OPEN_TRUNCATE_WRITE -> {
                val f = vfs.open(FILE_A, OpenOptions(read = false, write = true, create = true, truncate = true))
                try {
                    vfs.write(f, PAYLOAD, 0, PAYLOAD.size)
                } finally {
                    vfs.close(f)
                }
            }
            // 前の回が途中で尽きると、どちらの名前にあるかは回の数で決まらないので、ない方からは移さない
            Op.RENAME -> try {
                vfs.rename(FILE_A, FILE_B)
            } catch (e: FsException) {
                if (e.errno != Errno.NOENT) throw e
                vfs.rename(FILE_B, FILE_A)
            }
            Op.RENAME_REPLACE -> {
                // A を B へ移して B を置き換え、A を作り直す（どこで尽きても、次の回は A があるか、ないかのどちらか）
                try {
                    vfs.rename(FILE_A, FILE_B)
                } catch (e: FsException) {
                    // 前の回で A を作り直す前に尽きた
                    if (e.errno != Errno.NOENT) throw e
                }
                writeFile(machine, FILE_A)
            }
            Op.CREATE_REMOVE -> {
                writeFile(machine, FILE_C)
                vfs.removeFile(FILE_C)
            }
        }
    }

    private fun writeFile(machine: Machine, path: VfsPath) {
        val f = machine.vfs.open(path, OpenOptions(read = false, write = true, create = true, truncate = true))
        try {
            machine.vfs.write(f, PAYLOAD, 0, TmpFileSystem.CHUNK_BYTES + 1)
        } finally {
            machine.vfs.close(f)
        }
    }

    private fun tmpOf(m: Machine): TmpFileSystem = m.lock.withLock {
        val entry = m.kernel.mounts.entries.single { it.source == MachineLimits.TMP_SOURCE }
        m.vfs.fileSystemLocked(entry.id) as TmpFileSystem
    }

    private fun sweep(kind: ProcessKind, rounds: Int) = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        Harness(config).use { h ->
            val serverAtStart = h.scheduler.memoryAccount.usedBytes
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000, memoryBytes = MACHINE_BYTES, maxProcessMemoryBytes = MACHINE_BYTES)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            assertNull(m.mountTmp())
            val tmp = tmpOf(m)
            // 名前の変更の元と、置き換えられる側を浅いところで作っておく
            writeFile(m, FILE_A)
            writeFile(m, FILE_B)
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
            other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }
            val permits = config.maxConcurrentMachines
            val program = victim()
            for (r in 0 until rounds) {
                for (op in Op.entries) {
                    // /tmp の中身の分を除いた勘定（プロセスの線形メモリなど）。終わった後に同じ値に戻る
                    val machineBefore = m.memoryAccount.usedBytes - tmp.usedBytes
                    val serverBefore = h.scheduler.memoryAccount.usedBytes - tmp.usedBytes
                    val round = Round(op)
                    val driver = TestDriver(
                        kind,
                        onConfigure = { ctx, b ->
                            b.hostFunction(
                                "env",
                                "w",
                                i32ToVoid,
                                ctx.hostFunction { _, _, args ->
                                    body(m, round.op, args[0].toInt())
                                    LongArray(0)
                                },
                            )
                        },
                    ) { ctx ->
                        val instance = ctx.instance
                        // 浅いところで 1 回ずつ呼び、底で初めて触るクラス（VFS・tmpfs・Endive の例外）を読み込んでおく
                        for (o in Op.entries) body(m, o, 0)
                        instance.call("host", 1L)
                        isStackExhaustion(WasmTrapException(WasmTrapKind.STACK_OVERFLOW, "warm-up"))
                        descend(round, { i -> instance.call("host", i.toLong()) }, 0)
                        StepResult.Exit(0)
                    }
                    val rt = m.spawnRt(program, driver, SpawnOptions(allowUnmetered = true))
                    eventuallyDescribe(20_000, { "深い再帰が終わらない（$r 回目、$op）: ${rt.process.state}" }) {
                        h.scheduler.tick()
                        rt.process.state.isTerminal || m.state == MachineState.CRASHED
                    }
                    // 終わりの片付け（finishLocked。インスタンスを閉じて線形メモリの区画の表などを返す）はマシンのロックの中で、
                    // 状態を CRASHED にしてから進む（#1031）。ほかのスレッドからマシンのロックを取って（取れることも確かめる）、
                    // 片付けが終わってから勘定を読む。ロックの中では数値を写すだけにする。
                    // ロックが取れなくても（スタックが尽きた後にロックが残る形）、その回の診断の行と、ほかの失敗の原因を先に出すため、
                    // 取れたかどうかの確かめは勘定を比べる直前に置く（取れなかったときの値はロックの外で読んだもの）
                    val locked = m.lock.tryLock(stackHeadroomWait(5_000), TimeUnit.MILLISECONDS)
                    val tmpAfter: Long
                    val processAfter: Long
                    val machineAfter: Long
                    val serverAfter: Long
                    try {
                        tmpAfter = tmp.usedBytes
                        processAfter = rt.memory.usedBytes
                        machineAfter = m.memoryAccount.usedBytes - tmpAfter
                        serverAfter = h.scheduler.memoryAccount.usedBytes - tmpAfter
                    } finally {
                        if (locked) m.lock.unlock()
                    }
                    // /tmp のモニターは、マシンのロックを放してから取る（プロセスは終わっていて、/tmp はもう誰も変えない）
                    val problems = tmp.consistencyProblemsForTest()
                    println(
                        "#883 $kind $op（$r 回目）: 深さ ${round.next}、通った ${round.completed.get()}、断られた ${round.refused.get()}、" +
                            "/tmp $tmpAfter、プロセス $processAfter、マシン（/tmp を除く）$machineBefore → $machineAfter、" +
                            "サーバー（/tmp を除く）$serverBefore → $serverAfter、食い違い $problems" +
                            if (locked) "" else "、マシンのロックが取れない（勘定はロックの外で読んだ）: ${m.lock}",
                    )
                    round.unexpected?.let { throw AssertionError("$op: スタックが尽きた以外の失敗", it) }
                    assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$r 回目、$op）: ${rt.lastError}")
                    assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
                    assertEquals("call stack exhausted", crashReason(rt.lastError!!), "${rt.lastError}")
                    assertTrue(round.completed.get() > 0, "$op: 底の近くで本体が通った深さがない（試験の窓が底から遠い）")
                    assertTrue(round.refused.get() > 0, "$op: スタック不足で断られた深さがない（試験の窓が底まで届かない）")
                    assertEquals(emptyList<String>(), problems, "$op: /tmp の木と usedBytes が食い違った")
                    // ほかのスレッドから、マシンのロックが取れる（取れないと、上の勘定は片付けの途中の値かもしれないので、比べる前に確かめる）
                    assertTrue(locked, "マシンのロックが取れない（$r 回目、$op）: ${m.lock}")
                    assertEquals(machineBefore, machineAfter, "$op: マシンの勘定が /tmp とずれた（プロセス $processAfter）")
                    assertEquals(serverBefore, serverAfter, "$op: サーバー全体の勘定が /tmp とずれた（プロセス $processAfter）")
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
            // 止めると /tmp が外れ、中身の分が返る
            assertEquals(0L, tmp.usedBytes, "止めた後に /tmp の usedBytes が残った")
            assertEquals(0L, m.memoryAccount.usedBytes, "止めた後にマシンの勘定が残った")
            assertEquals(serverAtStart, h.scheduler.memoryAccount.usedBytes, "止めた後にサーバー全体の勘定が戻らない")
        }
    }

    @Test
    fun `BLOCKINGでスタックの底の近くから tmp を変えても、勘定と木はずれずプロセスだけが終わる`() = sweep(ProcessKind.BLOCKING, rounds = 2)

    @Test
    fun `WAIT_POINTでスタックの底の近くから tmp を変えても、勘定と木はずれずプロセスだけが終わる`() = sweep(ProcessKind.WAIT_POINT, rounds = 1)

    private companion object {
        const val MACHINE_BYTES = 512L * 1024 * 1024

        /** 底に近い深さを選ぶための確かめの段数（Endive の呼び出しとホストの関数の包みの分を含めて、窓に底を含める） */
        const val WINDOW_FRAMES = 512

        val PAYLOAD = ByteArray(3 * TmpFileSystem.CHUNK_BYTES) { (it % 251).toByte() }

        val FILE_A: VfsPath = VfsPath.parse("/tmp/a")
        val FILE_B: VfsPath = VfsPath.parse("/tmp/b")
        val FILE_C: VfsPath = VfsPath.parse("/tmp/c")
    }
}
