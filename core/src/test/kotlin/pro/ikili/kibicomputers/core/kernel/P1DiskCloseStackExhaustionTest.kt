package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.FakeContext
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.wasi.p1.P1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * 本物の WASI p1 の `path_open`・`fd_close` を、スタックが尽きる深さで動かした後に、ディスクの開いたままの枠が返ることを確かめる（#858）。
 *
 * `fd_close` は、fd の表で最後の参照を外し（`OpenFile.close` → `WakeSink.fileClosed` → `Vfs.releaseLater`）、閉じる知らせを積む。
 * 知らせは `Machine.scheduleVfsDrain` が起動用のスレッドに頼んで行う。深いゲストのスレッドは、`path_open`（`Vfs.open` →
 * `checkUnlocked` → `Vfs.drainDeferred`）の中でも知らせを行おうとするが、スタックの余裕が足りなければ、取り出さずに返す。
 * 残した知らせを行うのは起動用のスレッドの仕事（`vfsDrainScheduled` の CAS と組み合わせた道）で、どちらも浅いところに戻らないと
 * 枠は返らない。`DiskFileSystemStackExhaustionTest` は `Vfs()`（`onDeferred` なし）で `drainDeferred` を直接呼ぶだけなので、
 * 「深いスレッドが積んだ知らせを、起動用のスレッドが行う」道は、ここで見る。
 *
 * ゲスト役は、深い再帰の底に近いところから、1 段ごとに「前の段の fd を `fd_close` し、`path_open` で新しく開く」ことを繰り返す
 * （閉じた後に開く入口の確かめで尽きる回が、積んだ知らせを深いところに残す）。尽きた後（プロセスの終わりが残った fd を閉じる分を含む）に、
 * 知らせが残らず、開いたままの枠が 0 に戻り、ディスクの状態がホストと合い、ロックが取れることを確かめる。
 * `FakeModule` は待機点への変換ができないので、BLOCKING だけ（燃料切れの道は `P1StackExhaustionLockTest` が見る）
 */
class P1DiskCloseStackExhaustionTest {
    @TempDir
    lateinit var dir: Path

    /** 1 回分の深い再帰の数え */
    private class Round {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
    }

    private val limit = maxOf(java.time.Duration.ofSeconds(300), stackHeadroomTestLimit())

    private fun nearStackEnd(): Boolean = !StackHeadroom.hasHeadroom(NEAR_END_FRAMES)

    /** 尽きる位置をずらすための詰め物（[pad] 段だけ積んでから [body] を呼ぶ） */
    private fun padThen(pad: Int, body: () -> Long): Long = if (pad <= 0) body() else padThen(pad - 1, body) + 1

    private fun recurse(ctx: FakeContext, n: Int, prev: Int, round: Round): Long {
        var fd = -1
        if (nearStackEnd()) {
            // 前の段の fd を閉じ（知らせが積まれる）、新しく開く（`Vfs.open` の入口の確かめで、ここが尽きることがある）
            if (prev >= 0) {
                ctx.callImport(P1.MODULE, "fd_close", prev.toLong())
                round.closes.incrementAndGet()
            }
            val errno = ctx.callImport(
                P1.MODULE,
                "path_open",
                3,
                0,
                PATH_PTR.toLong(),
                PATH.size.toLong(),
                P1.OFLAGS_CREAT.toLong(),
                P1.RIGHT_FD_READ or P1.RIGHT_FD_WRITE,
                0,
                0,
                OUT_PTR.toLong(),
            )[0]
            if (errno == 0L) {
                fd = ctx.memory.readI32(OUT_PTR)
                round.opens.incrementAndGet()
            }
        }
        return recurse(ctx, n + 1, fd, round) + n
    }

    private fun victim(pad: Int, round: Round): FakeModule {
        val b = FakeModule.builder().fuelMetered().memory(1).data(PATH_PTR, PATH)
        for (name in listOf("path_open", "fd_close")) {
            b.import(P1.MODULE, name, WasiP1.typeOf(name)!!)
        }
        return b.export("run", VOID) { ctx, _ ->
            padThen(pad) { recurse(ctx, 0, -1, round) }
            LongArray(0)
        }.build()
    }

    @Test
    fun `path_openとfd_closeを尽きる深さで動かしても、閉じる知らせは残らず、開いたままの枠は返る`() = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        val tally = SoePositions()
        val fs = DiskFileSystem.open(Files.createDirectories(dir.resolve("disk")).toRealPath())
        val deepNotices = AtomicInteger()
        val notices = AtomicInteger()
        var opens = 0
        var closes = 0
        try {
            Harness(config).use { h ->
                HeadroomTicker(h).use { ticker ->
                    val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
                    m.vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "test:disk")
                    // init として動き続けるプロセス（犠牲のプロセスが落ちても、マシンが止まらないようにする）
                    val spin = AtomicInteger()
                    m.spawnTest(h.program(spinForever(spin)), runDriver(ProcessKind.BLOCKING))
                    awaitOrClassify(ticker, "spin が動く", { describeMachineState(h, m) }) { spin.get() > 0 }
                    // 積んだ知らせの数と、積んだときにスタックの余裕が `drainDeferred` の確かめ（深いスレッドでは取り出せない）に足りなかった数
                    val schedule = m.vfs.onDeferred
                    m.vfs.onDeferred = {
                        notices.incrementAndGet()
                        if (!StackHeadroom.hasHeadroom(StackHeadroom.DEFERRED_RELEASE_FRAMES)) deepNotices.incrementAndGet()
                        schedule?.invoke()
                    }
                    for (i in 0 until stackHeadroomRounds(ROUNDS)) {
                        val pad = (i * 7) % 97
                        val round = Round()
                        val module = victim(pad, round)
                        val driver = runDriver(ProcessKind.BLOCKING) { ctx, b -> WasiP1.register(ctx, b, module, true) }
                        val options = SpawnOptions(stdio = StdioSpec.CLOSED, fds = mapOf(3 to StdioSource.Directory("/")))
                        val pid = m.spawn(h.program(module), driver, options)
                        val rt = m.rt(pid)
                        val where = "$i 回目、pad $pad"
                        val describe = { "$where: ${describeMachineState(h, m)}、SOE: ${soeOf(tally, rt)?.stackTrace?.take(8)?.joinToString(" <- ")}" }
                        awaitOrClassify(ticker, "深い再帰が終わる（$where）", describe) { m.stateOf(pid).isTerminal || m.state == MachineState.CRASHED }
                        assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$where）: ${rt.lastError}")
                        assertEquals(ProcessState.CRASHED, m.stateOf(pid), "${rt.lastError}")
                        assertEquals("call stack exhausted", crashReason(rt.lastError!!), "${rt.lastError}")
                        val position = tally.record(rt)
                        if (position.isLock) fail("スタックがロックを取る・放す途中で尽きた（$where）: ${soeOf(tally, rt)?.stackTrace?.take(16)?.joinToString(" <- ")}")
                        assertTrue(round.opens.get() > 0, "path_open が 1 度も通らなかった（$where）")
                        opens += round.opens.get()
                        closes += round.closes.get()
                        // 起動用のスレッドが、積んだ知らせを行う（深いスレッドが残した分、プロセスの終わりが残った fd を閉じた分を含む）。
                        // 知らせが列に残り続ける、または枠が返らないなら、深いところで取り出して捨てたか、頼みが落ちている。
                        // どちらもテストのスレッドの外で進むので、固定の待ちでなく、遅れと停止を見分ける待ちを通す。
                        // 枠の数（`openHandles`）はリースのロックを上限なしで取るので、上限付きで読む（[peek]。ロックが残っても止まらない）
                        val handles = { peek(fs.ioLockForTest) { fs.openHandles } }
                        awaitOrClassify(
                            ticker,
                            "閉じる知らせが行われ、枠が返る（$where）",
                            { "知らせ ${m.vfs.hasDeferred}、開いたままの枠 ${handles()}、${describe()}" },
                        ) { !m.vfs.hasDeferred && handles() == 0 }
                        // ロックの待ちも、負荷の下の遅れと、持ったまま残った停止を見分ける（#905）
                        awaitLockAcquirable(ticker, "マシンのロックが取れる（$where）", m.lock, describe)
                        awaitLockAcquirable(ticker, "リースのロックが取れる（$where）", fs.ioLockForTest) { "${fs.ioLockForTest}、${describe()}" }
                        assertEquals(emptyList<String>(), fs.consistencyProblemsForTest(), "ディスクの状態がホストと食い違った（$where）")
                    }
                    println(
                        "#858 p1 path_open・fd_close: ${tally.describe()}、開いた $opens 回、閉じた $closes 回、" +
                            "知らせ ${notices.get()} 回（深いスレッドが積んだ ${deepNotices.get()} 回）",
                    )
                    assertTrue(closes > 0, "fd_close が 1 度も通らなかった")
                    assertTrue(deepNotices.get() > 0, "深いスレッドが閉じる知らせを積んだ回が 1 度もない（深いところの道を通っていない）")
                    m.stop()
                    val stopping = { describeMachineState(h, m) }
                    awaitOrClassify(ticker, "マシンが止まる", stopping) { m.state == MachineState.STOPPED }
                    awaitOrClassify(ticker, "CPU が返る", stopping) { cpuIsIdle(m) }
                    awaitOrClassify(ticker, "許可が戻る", stopping) { permitsAreBack(h, config.maxConcurrentMachines) }
                    awaitLockReleased(ticker, "マシンのロックが放される", m.lock) { "${m.lock}、${stopping()}" }
                }
            }
        } finally {
            fs.retire()
        }
        assertEquals(0, tally.inLocks, "スタックがロックを取る・放す途中で尽きた")
    }

    private fun soeOf(tally: SoePositions, rt: pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime): StackOverflowError? = tally.soe(rt)

    private companion object {
        /** 底に近いと見なす、残りのスタックの段数（`HOST_IO_FRAMES` の 3 倍。`DEFERRED_RELEASE_FRAMES` の確かめが通らない深さを含む） */
        const val NEAR_END_FRAMES = StackHeadroom.HOST_IO_FRAMES * 3

        const val ROUNDS = 40

        /** ゲストのメモリの中の、相対パス `a` の場所と、`path_open` が fd を書く場所 */
        const val PATH_PTR = 1024
        const val OUT_PTR = 2048
        val PATH = "a".toByteArray()
    }
}
