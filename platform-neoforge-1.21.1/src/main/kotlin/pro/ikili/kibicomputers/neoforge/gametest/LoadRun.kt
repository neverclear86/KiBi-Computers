package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.locks.LockSupport

/** 負荷試験の段階。 */
internal enum class LoadPhase(val label: String) {
    /** 何も置いていない（並べる範囲のチャンクは読み込んである）。比べる基準 */
    EMPTY("何もない"),

    /** 並べたが、電源は入れていない */
    PLACED("並べただけ（電源なし）"),

    /** 全台が起動して、Lua のプロンプトを出すまで（初回のコンパイルを含む） */
    BOOT("起動"),

    /** 全台が入力を待っている */
    IDLE("全台が入力待ち"),

    /** 一部の台が無限ループで計算し続ける（残りは入力待ち） */
    COMPUTE("一部が計算し続ける"),

    /** 一部の台が画面に出力し続ける。誰も見ていない（計算していた台は電源を切る。残りは入力待ち） */
    OUTPUT("一部が出力し続ける"),

    /** [OUTPUT] の画面を、1人が全部見ている（画面の同期が送られる） */
    WATCHED("一部が出力し続け、1人が見る"),
}

/**
 * 負荷試験の設定。
 *
 * @property count 並べる台数
 * @property busy 計算・出力させる台数（それぞれ）
 * @property paced 実際のサーバーと同じく 1 tick を 50 ms にそろえるか。GameTest のサーバーは待たずに tick を進めるので、
 *   そろえないと計算している台に配る燃料（tick ごと）が実際より多くなる
 * @property warmupTicks 段階が変わってから、測りはじめるまでの tick 数
 * @property measureTicks 測る tick 数
 */
internal data class LoadSettings(
    val count: Int,
    val busy: Int,
    val paced: Boolean,
    val warmupTicks: Int,
    val measureTicks: Int,
    val bootTimeoutTicks: Int = if (paced) 150_000 else 15_000,
)

/** tick の時間の統計（ミリ秒）。 */
internal data class TickStats(val ticks: Int, val avg: Double, val p50: Double, val p95: Double, val max: Double) {
    override fun toString(): String = String.format(Locale.ROOT, "%d ticks, avg %.2f / p50 %.2f / p95 %.2f / max %.2f ms", ticks, avg, p50, p95, max)

    companion object {
        fun of(nanos: LongArray): TickStats {
            if (nanos.isEmpty()) return TickStats(0, 0.0, 0.0, 0.0, 0.0)
            val sorted = nanos.sortedArray()
            fun ms(n: Long) = n / 1_000_000.0
            fun pct(p: Double) = ms(sorted[((sorted.size - 1) * p).toInt()])
            return TickStats(sorted.size, ms(sorted.sum()) / sorted.size, pct(0.5), pct(0.95), ms(sorted.last()))
        }
    }
}

/** 1つの段階の結果。 */
internal data class PhaseResult(
    val phase: LoadPhase,
    /** 段階の終わりに動いていたマシンの数 */
    val running: Int,
    val tick: TickStats,
    /** 段階の終わりの、カーネルのスレッド（名前が `KiBi-` で始まるもの）の数 */
    val kernelThreads: Int,
    /** [kernelThreads] のうち、仕事がなくプールで次の仕事を待っているワーカー（しばらくすると終わる） */
    val idleWorkerThreads: Int,
    /** 測っている間の、カーネルのスレッドの最大（10 tick ごとに数えた中で） */
    val peakKernelThreads: Int,
    /** 段階の終わりの、JVM のスレッドの数 */
    val jvmThreads: Int,
    /** 測っている間の、JVM のスレッドの最大 */
    val peakJvmThreads: Int,
    /** 段階の終わりに GC したあとのヒープ使用量 */
    val heapUsedBytes: Long,
    /** 測っている間に、見ている人へ送った画面の同期のバイト数 */
    val sentBytes: Long,
    /** 段階の長さ（tick） */
    val durationTicks: Int,
    /** 段階の長さ（秒） */
    val wallSeconds: Double,
    /** 段階の終わりの、カーネルのスレッドの内訳（名前の数字を `#` にしたもの → 数） */
    val kernelThreadNames: Map<String, Int>,
)

/** 負荷試験の結果。 */
internal class LoadReport(
    val settings: LoadSettings,
    /** ブロックを並べるのにかかった時間（1 tick の中） */
    val placeMillis: Double,
    /** 全台の電源を入れるのにかかった時間（1 tick の中） */
    val powerOnMillis: Double,
    /** 計算している台の電源を切るのにかかった時間（1 tick の中） */
    val powerOffMillis: Double,
    /** 並べる前に GC したあとのヒープ使用量 */
    val baselineHeapBytes: Long,
    val phases: List<PhaseResult>,
) {
    fun phase(phase: LoadPhase): PhaseResult = phases.first { it.phase == phase }

    fun toMarkdown(): String {
        val s = settings
        val mib = 1024.0 * 1024.0
        val placed = phases.firstOrNull { it.phase == LoadPhase.PLACED }?.heapUsedBytes ?: baselineHeapBytes
        val idle = phases.firstOrNull { it.phase == LoadPhase.IDLE }?.heapUsedBytes ?: placed
        fun perMachine(from: Long, to: Long) = (to - from).toDouble() / s.count / mib
        val sb = StringBuilder()
        sb.append(String.format(Locale.ROOT, "- 台数: %d（計算・出力はそれぞれ %d 台）、tick を 50 ms にそろえる: %s\n", s.count, s.busy, s.paced))
        sb.append(String.format(Locale.ROOT, "- 並べる: %.1f ms、電源を入れる: %.1f ms、計算中の台の電源を切る: %.1f ms（それぞれ 1 tick の中）\n", placeMillis, powerOnMillis, powerOffMillis))
        sb.append(
            String.format(
                Locale.ROOT,
                "- ヒープ（GC 後）: 並べる前 %.0f MiB、並べただけ %.0f MiB、全台が入力待ち %.0f MiB。1台あたり ブロック %.3f MiB + マシン %.3f MiB。上限 %.0f MiB\n",
                baselineHeapBytes / mib, placed / mib, idle / mib, perMachine(baselineHeapBytes, placed), perMachine(placed, idle),
                Runtime.getRuntime().maxMemory() / mib,
            ),
        )
        sb.append("\n| 段階 | 動いている台 | 長さ | MSPT 平均 | p50 | p95 | 最大 | KiBi スレッド（終わり / うち待機中のワーカー / 最大） | JVM スレッド（終わり / 最大） | ヒープ（GC 後） | 画面の同期 |\n")
        sb.append("| --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |\n")
        for (p in phases) {
            val sent = if (p.tick.ticks > 0) p.sentBytes.toDouble() / p.tick.ticks else 0.0
            sb.append(
                String.format(
                    Locale.ROOT, "| %s | %d | %d tick / %.1f s | %.2f | %.2f | %.2f | %.2f | %d / %d / %d | %d / %d | %.0f MiB | %.0f B/tick |\n",
                    p.phase.label, p.running, p.durationTicks, p.wallSeconds, p.tick.avg, p.tick.p50, p.tick.p95, p.tick.max,
                    p.kernelThreads, p.idleWorkerThreads, p.peakKernelThreads, p.jvmThreads, p.peakJvmThreads, p.heapUsedBytes / mib, sent,
                ),
            )
        }
        sb.append("\nカーネルのスレッドの内訳（段階の終わり）:\n")
        for (p in phases) sb.append("- ").append(p.phase.label).append(": ").append(p.kernelThreadNames).append('\n')
        return sb.toString()
    }
}

/**
 * 負荷試験を1回動かす（#345）。[start] で GameTest の毎 tick に段階を進め、終わったら [onDone] に結果を渡して成功にする。
 *
 * tick の時間は、毎 tick の呼び出しの間隔から、この試験の処理（並べる・起動したかを見る・数える）と、
 * tick をそろえるために待った時間を引いたもの。GameTest のサーバーは tick の間で待たないので、残りはサーバーの tick の処理になる。
 * ただし、並べる・電源を入れる・切るのにかかった時間は、別に [LoadReport] に書く。
 */
internal class LoadRun(
    private val helper: GameTestHelper,
    private val settings: LoadSettings,
    private val onDone: (LoadReport) -> Unit,
) {
    private class Computer(val case: CaseBlockEntity, val screen: ScreenBlockEntity, val keyboard: KeyboardBlockEntity) {
        var ready = false
    }

    private val level = helper.level
    private val threads = ManagementFactory.getThreadMXBean()
    private val memory = ManagementFactory.getMemoryMXBean()
    private val columns = LoadGameTests.Layout.columns(settings.count)
    private val computers = ArrayList<Computer>(settings.count)
    private val forced = ArrayList<ChunkPos>()
    private val results = ArrayList<PhaseResult>()

    private val computeSet: List<Computer> get() = computers.subList(0, minOf(settings.busy, computers.size))
    private val outputSet: List<Computer> get() = computers.subList(minOf(settings.busy, computers.size), minOf(settings.busy * 2, computers.size))

    private var phase: LoadPhase? = null
    private var phaseTick = 0
    private var phaseStartNanos = 0L
    private var recording = false
    private var samples = LongArray(1024)
    private var sampleCount = 0
    private var peakKernelThreads = 0
    private var sentBytes = 0L
    private var countSent = false

    private var placeMillis = 0.0
    private var powerOnMillis = 0.0
    private var powerOffMillis = 0.0
    private var baselineHeap = 0L
    private var watcher: ServerPlayer? = null
    private var finished = false

    private var prevCallback = 0L
    private var prevHarness = 0L
    private var prevSleep = 0L

    fun start() {
        helper.onEachTick(::onTick)
    }

    private fun onTick() {
        if (finished) return
        val now = System.nanoTime()
        var work = 0L
        if (prevCallback != 0L) {
            work = (now - prevCallback - prevHarness - prevSleep).coerceAtLeast(0)
            if (recording) record(work)
        }
        prevCallback = now
        try {
            step()
        } catch (t: Throwable) {
            cleanUp()
            throw t
        }
        val end = System.nanoTime()
        prevHarness = end - now
        prevSleep = 0
        if (settings.paced && !finished) {
            val wait = TARGET_NANOS - work - prevHarness
            if (wait > 0) {
                LockSupport.parkNanos(wait)
                prevSleep = System.nanoTime() - end
            }
        }
    }

    private fun record(nanos: Long) {
        if (sampleCount == samples.size) samples = samples.copyOf(samples.size * 2)
        samples[sampleCount++] = nanos
    }

    private fun step() {
        val p = phase
        if (p == null) {
            forceChunks()
            baselineHeap = heapAfterGc()
            enter(sequence.first())
        } else {
            if (phaseTick == 0) setUp(p)
            if (p == LoadPhase.BOOT) boot() else steady(p)
        }
        phaseTick++
    }

    /** 段階の順番。計算・出力させる台がなければ、その段階は飛ばす */
    private val sequence: List<LoadPhase> = buildList {
        add(LoadPhase.EMPTY)
        add(LoadPhase.PLACED)
        add(LoadPhase.BOOT)
        add(LoadPhase.IDLE)
        if (settings.busy > 0) add(LoadPhase.COMPUTE)
        if (settings.busy > 0 && settings.count > settings.busy) {
            add(LoadPhase.OUTPUT)
            add(LoadPhase.WATCHED)
        }
    }

    private fun next(p: LoadPhase) {
        verify(p)
        finish()
        val i = sequence.indexOf(p) + 1
        if (i < sequence.size) enter(sequence[i]) else done()
    }

    private fun boot() {
        if (phaseTick % 10 != 0) return
        peakKernelThreads = maxOf(peakKernelThreads, kernelThreads())
        var waiting = 0
        for (c in computers) {
            if (!c.ready) c.ready = hasPrompt(c.screen.text)
            if (!c.ready) waiting++
        }
        if (waiting == 0) {
            next(LoadPhase.BOOT)
        } else if (phaseTick >= settings.bootTimeoutTicks) {
            val c = computers.first { !it.ready }
            fail("$waiting of ${settings.count} computers did not show the prompt within ${settings.bootTimeoutTicks} ticks: ${lines(c.screen.text)}")
        }
    }

    private fun steady(p: LoadPhase) {
        if (phaseTick == settings.warmupTicks) {
            recording = true
            countSent = true
            threads.resetPeakThreadCount()
            peakKernelThreads = 0
        }
        if (recording && phaseTick % 10 == 0) peakKernelThreads = maxOf(peakKernelThreads, kernelThreads())
        if (phaseTick == settings.warmupTicks + settings.measureTicks) next(p)
    }

    private fun setUp(p: LoadPhase) {
        when (p) {
            LoadPhase.EMPTY, LoadPhase.IDLE -> Unit
            LoadPhase.PLACED -> {
                val t0 = System.nanoTime()
                for (i in 0 until settings.count) LoadGameTests.Layout.placeComputer(helper, LoadGameTests.Layout.casePos(i, columns))
                placeMillis = (System.nanoTime() - t0) / 1e6
            }
            LoadPhase.BOOT -> {
                val t0 = System.nanoTime()
                for (i in 0 until settings.count) {
                    val pos = LoadGameTests.Layout.casePos(i, columns)
                    val case = helper.getBlockEntity<CaseBlockEntity>(pos)
                    if (case.pressPowerButton() != ComputerStatus.RUNNING) fail("computer #$i did not turn on: ${case.computer.status}")
                    computers += Computer(case, helper.getBlockEntity(pos.above()), helper.getBlockEntity(pos.south()))
                }
                powerOnMillis = (System.nanoTime() - t0) / 1e6
                for (c in computers) {
                    if (c.case.wiring.connectedScreen !== c.screen || c.case.wiring.connectedKeyboard !== c.keyboard) {
                        fail("computer at ${c.case.blockPos} is not wired to its own screen and keyboard")
                    }
                }
                // 起動は、電源を入れた次の tick から終わるまでを全部測る
                recording = true
            }
            LoadPhase.COMPUTE -> for (c in computeSet) type(c, "while true do end")
            LoadPhase.OUTPUT -> {
                val t0 = System.nanoTime()
                for (c in computeSet) c.case.pressPowerButton()
                powerOffMillis = (System.nanoTime() - t0) / 1e6
                for (c in outputSet) type(c, "local i = 0 while true do i = i + 1 print(i) end")
            }
            LoadPhase.WATCHED -> watch(outputSet)
        }
    }

    private fun verify(p: LoadPhase) {
        when (p) {
            LoadPhase.EMPTY, LoadPhase.PLACED, LoadPhase.BOOT -> Unit
            LoadPhase.IDLE -> {
                val notRunning = computers.count { it.case.computer.machine?.state != MachineState.RUNNING }
                if (notRunning > 0) fail("$notRunning computers stopped while waiting for input")
            }
            LoadPhase.COMPUTE -> for (c in computeSet) {
                if (c.case.computer.machine?.state != MachineState.RUNNING) fail("a computing machine stopped: ${lines(c.screen.text)}")
                val text = lines(c.screen.text)
                // 計算している間はプロンプトに戻らない
                if (text.last { it.isNotEmpty() } != "> while true do end") fail("the loop should keep running: $text")
            }
            LoadPhase.OUTPUT, LoadPhase.WATCHED -> for (c in outputSet) {
                if (c.case.computer.machine?.state != MachineState.RUNNING) fail("an output machine stopped: ${lines(c.screen.text)}")
                if (lines(c.screen.text).none { line -> line.isNotEmpty() && line.all { it.isDigit() } }) fail("output should be shown: ${lines(c.screen.text)}")
            }
        }
    }

    private fun enter(p: LoadPhase) {
        phase = p
        phaseTick = -1 // step() が 0 にする
        phaseStartNanos = System.nanoTime()
        recording = false
        countSent = false
        sampleCount = 0
        sentBytes = 0
        peakKernelThreads = 0
        threads.resetPeakThreadCount()
    }

    private fun finish() {
        val p = phase ?: return
        val wall = (System.nanoTime() - phaseStartNanos) / 1e9
        val stats = TickStats.of(samples.copyOf(sampleCount))
        val infos = threads.dumpAllThreads(false, false, STACK_DEPTH).filter { it.threadName.startsWith("KiBi-") }
        val names = infos.groupingBy { it.threadName.replace(DIGITS, "#") }.eachCount().toSortedMap()
        val kernel = infos.size
        val idleWorkers = infos.count { info -> info.stackTrace.any { it.className == ThreadPoolExecutor::class.java.name && it.methodName == "getTask" } }
        val result = PhaseResult(
            phase = p,
            running = computers.count { it.case.computer.machine?.state == MachineState.RUNNING },
            tick = stats,
            kernelThreads = kernel,
            idleWorkerThreads = idleWorkers,
            peakKernelThreads = maxOf(peakKernelThreads, kernel),
            jvmThreads = threads.threadCount,
            peakJvmThreads = threads.peakThreadCount,
            heapUsedBytes = heapAfterGc(),
            sentBytes = sentBytes,
            durationTicks = phaseTick + 1,
            wallSeconds = wall,
            kernelThreadNames = names,
        )
        results += result
    }

    private fun done() {
        val report = LoadReport(settings, placeMillis, powerOnMillis, powerOffMillis, baselineHeap, results.toList())
        cleanUp()
        onDone(report)
        helper.succeed()
    }

    private fun type(c: Computer, line: String) {
        val events = listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        for (packet in KeyboardEventCodec.encode(events)) {
            if (c.keyboard.receive(packet, level.gameTime) < 0) fail("the keyboard rejected the input")
        }
    }

    // 偽物のプレイヤーを並べた範囲の真ん中に置き、[screens] を見ていることにする。送ったものは数えるだけ
    private fun watch(screens: List<Computer>) {
        if (screens.isEmpty()) return
        ScreenSync.transport = ScreenSync.Transport { player, payload ->
            if (player === watcher) {
                if (countSent) sentBytes += payload.data.size
            } else {
                ScreenSync.NETWORK.send(player, payload)
            }
        }
        val player = helper.makeMockServerPlayerInLevel()
        val rows = (settings.count + columns - 1) / columns
        val center = helper.absoluteVec(
            BlockPos(columns * LoadGameTests.Layout.STEP_X / 2, 3, rows * LoadGameTests.Layout.STEP_Z / 2).center,
        )
        player.teleportTo(center.x, center.y, center.z)
        level.chunkSource.move(player)
        watcher = player
        for (c in screens) c.screen.watch(player)
    }

    private fun forceChunks() {
        val rows = (settings.count + columns - 1) / columns
        val a = helper.absolutePos(BlockPos(0, 0, 0))
        val b = helper.absolutePos(BlockPos(columns * LoadGameTests.Layout.STEP_X, 0, rows * LoadGameTests.Layout.STEP_Z))
        val already = level.forcedChunks
        for (cx in (minOf(a.x, b.x) shr 4)..(maxOf(a.x, b.x) shr 4)) {
            for (cz in (minOf(a.z, b.z) shr 4)..(maxOf(a.z, b.z) shr 4)) {
                val chunk = ChunkPos(cx, cz)
                if (!already.contains(chunk.toLong()) && level.setChunkForced(cx, cz, true)) forced += chunk
            }
        }
    }

    private fun cleanUp() {
        if (finished) return
        finished = true
        recording = false
        for (i in 0 until settings.count) {
            val pos = LoadGameTests.Layout.casePos(i, columns)
            helper.setBlock(pos, Blocks.AIR)
            helper.setBlock(pos.above(), Blocks.AIR)
            helper.setBlock(pos.south(), Blocks.AIR)
        }
        watcher?.let { level.server.playerList.remove(it) }
        watcher = null
        ScreenSync.transport = ScreenSync.NETWORK
        for (chunk in forced) level.setChunkForced(chunk.x, chunk.z, false)
        forced.clear()
    }

    private fun kernelThreads(): Int = threads.dumpAllThreads(false, false, 0).count { it.threadName.startsWith("KiBi-") }

    private fun heapAfterGc(): Long {
        System.gc()
        return memory.heapMemoryUsage.used
    }

    private fun fail(message: String): Nothing = throw GameTestAssertException(message)

    companion object {
        private val DIGITS = Regex("[0-9]+")

        /** プールで仕事を待っているワーカーを見分けるのに読むスタックの深さ */
        private const val STACK_DEPTH = 32

        private const val TARGET_NANOS = (LoadGameTests.TARGET_TICK_MILLIS * 1_000_000).toLong()

        /** 画面に Lua のプロンプト（`>` だけの行）がある */
        fun hasPrompt(text: TextBuffer): Boolean {
            for (y in 0 until text.height) {
                if (text.getCodePoint(0, y) != '>'.code) continue
                var blank = true
                for (x in 1 until text.width) {
                    if (text.getCodePoint(x, y) != ' '.code) {
                        blank = false
                        break
                    }
                }
                if (blank) return true
            }
            return false
        }

        fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
            val sb = StringBuilder()
            for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
            sb.toString().trimEnd()
        }
    }
}
