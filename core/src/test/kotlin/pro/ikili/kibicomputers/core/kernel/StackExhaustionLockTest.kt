package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.runtime.FakeContext
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.display.DisplayDevice
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.FakeInstance
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ゲストの深い再帰でスタックが尽きる位置が、カーネルのロック（マシンのロック・CPU・許可）の途中に落ちても、
 * ロック・CPU・許可が壊れないことを確かめる（#828）。
 *
 * スタックを 256 KiB に縮めたカーネルのスレッドで、1 段ごとにホストの関数（`ctx.hostFunction` の包み）か燃料切れの処理を呼ぶ再帰を、
 * 尽きる位置をずらしながら多数回動かす。毎回の後に、ほかのスレッド（テストのスレッド）からマシンのロックが取れること、
 * 同じ台と別の台のプロセスが進み続けることを確かめ、最後に止めて CPU と許可が元に戻ったことを確かめる。
 * 同じワーカー・同じ種類の専用スレッドで何度もスタックを使い切らせる（2 回目以降に JVM ごと落ちないことも見る）
 */
class StackExhaustionLockTest {
    private val limit = stackHeadroomTestLimit()

    /** 1 段ごとの呼び方 */
    private enum class Pattern {
        HOST_EVERY_FRAME,
        FUEL_EVERY_FRAME,
        MIXED,

        /**
         * 再帰を `callGuest` の中で動かし、1 段ごとに止まりうる関数の中の燃料切れ（`onFuelExhaustedUnwindable`）とホストの関数を交互に呼ぶ。
         * ホストの関数の中から呼んだゲストの中は巻き戻せないので、`onFuelExhaustedUnwindable` → `onFuelExhausted`（スレッドを持ったまま待つ）の道を通る
         */
        UNWINDABLE_FUEL_IN_GUEST_CALL,

        /**
         * ホストの関数の再入: 1 段ごとにホストの関数 `g` を呼び、`g` の本体が `callGuest` の中でゲスト役の次の段を呼ぶ
         * （ホスト → ゲスト → ホスト …と入れ子で深くなる）。3 段に 1 回、燃料切れの処理も呼ぶ
         */
        NESTED_HOST,
    }

    /**
     * ホストの関数の本体がすること。包みの確かめ（[StackHeadroom.HOST_CALL_FRAMES]）が足りるかは、本体の奥でロックを取る道
     * （CPU・許可を返して取り直す、条件変数で待つ、カーネルロックの中で VFS を引く）が、確かめた余裕の中に収まるかで決まる
     */
    private enum class Body {
        /** 何もしない（包みのロックの区間と燃料切れの処理だけを確かめる） */
        EMPTY,

        /** 完了済みの future を待つ（`awaitHost`。`releasePermitAndCpu` → `acquireCpu` → CPU・許可の取り直し。ブロッキング型だけ） */
        AWAIT_HOST,

        /**
         * まだ読めないストリームを `block` で待つ（`releasePermitAndCpu` → 条件変数の待ち → `acquireCpu` の `precheck`。ブロッキング型だけ）。
         * テストのスレッドが、待ちに入ったのを見て 1 バイト書いて起こし、本体は読んでから戻る
         */
        BLOCK,

        /**
         * カーネルロックの中で VFS を引く（`withKernelLock` → `Vfs.stat`）。根に何もマウントしていないので、合成の stat を返すだけで
         * ファイルシステムには届かない（ADR 0052 により、ファイルシステムに届く操作はロックの外で呼ぶ。届く道は [DISK_IO]）
         */
        KERNEL_VFS,

        /**
         * ディスク（[DiskFileSystem]）の深いところまで入る（#858）。カーネルロックの中で fd・パスの解決に当たる区間を通った後、
         * ロックの外で、深いディレクトリの下のファイルを `open(create)` → write → close → `rename`、マウントをまたぐ `rename`（`XDEV`）、
         * 別のディスクの `stat` をする。ディスクの操作は、リースの I/O のロック（`HostDiskLeases.Lease.lock`）を持ったままホストの NIO を呼ぶ。
         * テストのスレッドがそのロックを取り合い、待ち行列ができた状態の取得・解放（AQS の遅い道）も通す
         */
        DISK_IO,

        /**
         * コンソールの TTY に書いて、表示デバイスに流す（#858。`flushConsole` → `Machine.pumpConsole` → 表示デバイスのロック →
         * カーネルロック → `AnsiTerminal.write`）。テストのスレッドが表示デバイスのロックを取り合う（メインスレッドの描画のまね）
         */
        CONSOLE,
    }

    /** ホストの関数の本体が使うもの（1 回の sweep で共有する） */
    private class Rig(
        val stream: ByteStream?,
        val disk: DiskRig? = null,
        val tty: Tty? = null,
    )

    /** [Body.DISK_IO] の置き場。`/a` と `/b` に別々のディスクをマウントし、`/a` の下に深いディレクトリを作る */
    private class DiskRig(val a: DiskFileSystem, val b: DiskFileSystem, val deep: VfsPath) {
        /**
         * 回ごとに別の名前にする。スタックが open と close の間で尽きると、開いたホストのファイルが（ファイルシステムを外すまで）残り、
         * Windows では開いたままのファイルの名前を変えられない（本番では fd に付くので、プロセスの終わりに閉じる）
         */
        fun file(round: Int): VfsPath = deep.child("t$round.txt")

        fun renamed(round: Int): VfsPath = deep.child("u$round.txt")

        fun across(round: Int): VfsPath = VfsPath.parse("/b/u$round.txt")

        val other: VfsPath = VfsPath.parse("/b")
    }

    /** 1 回分の深い再帰で、ホストの関数の本体が使うもの */
    private class Round(val rig: Rig, val index: Int) {
        val context = AtomicReference<ProcessContext>()

        /** ホストの関数の本体を呼んだ回数 */
        val calls = AtomicInteger()
    }

    private val payload = "\u001b[31mstack\u001b[0m headroom #858 \u001b[2K\r\n".toByteArray()

    private fun runBody(ctx: ProcessContext, body: Body, round: Round) {
        when (body) {
            Body.EMPTY -> Unit
            Body.AWAIT_HOST -> ctx.awaitHost(CompletableFuture.completedFuture(Unit))
            Body.BLOCK -> {
                val stream = round.rig.stream!!
                ctx.block(listOf(WaitKey(stream.id, WaitCondition.READABLE)))
                ctx.withKernelLock { stream.read(ByteArray(1), 0, 1, ctx.wakeSink) }
            }
            Body.KERNEL_VFS -> ctx.withKernelLock { ctx.vfs.stat(VfsPath.parse("/")) }
            Body.DISK_IO -> {
                val disk = round.rig.disk!!
                // 1 回ごとの深いパスの stat（パスの解決）と、16 回に 1 回の open・write・rename（ホストの I/O は遅いので間引く）
                if (round.calls.getAndIncrement() % 16 != 0) {
                    ctx.vfs.stat(disk.deep)
                    return
                }
                // 本番の path_open・path_rename と同じく、ロックの中は fd・パスの解決だけ（WasiP1Path）
                ctx.withKernelLock { ctx.kernel.processes[ctx.pid] }
                val vfs = ctx.vfs
                val n = round.index
                val f = vfs.open(disk.file(n), OpenOptions(read = true, write = true, create = true, truncate = true))
                vfs.write(f, payload, 0, payload.size)
                vfs.close(f)
                vfs.rename(disk.file(n), disk.renamed(n))
                try {
                    vfs.rename(disk.renamed(n), disk.across(n))
                } catch (e: FsException) {
                    check(e.errno == Errno.XDEV) { "マウントをまたぐ名前の変更が XDEV でない: ${e.errno}" }
                }
                vfs.stat(disk.other)
            }
            Body.CONSOLE -> {
                val tty = round.rig.tty!!
                ctx.withKernelLock {
                    tty.write(payload, 0, payload.size, ctx.wakeSink)
                    ctx.reserveConsoleFlushLocked()
                }
                ctx.flushConsole()
            }
        }
    }

    /** 再帰で尽きた位置（SOE の一番上のフレーム）の内訳 */
    private class Tally {
        val crashed = AtomicInteger()
        val inLocks = AtomicInteger()
        val inProbe = AtomicInteger()

        /** ホストの I/O（`java.nio`・`sun.nio`・`java.io`）の中 */
        val inHostIo = AtomicInteger()

        /** 端末（`AnsiTerminal`・`TextBuffer`）・TTY の中 */
        val inTerminal = AtomicInteger()

        /** VFS・ディスクの中 */
        val inVfs = AtomicInteger()

        /** 一番上のフレームのクラスごとの回数（表に書くため） */
        val tops = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        fun describe(): String =
            "CRASHED ${crashed.get()} 回、尽きた位置がロックの中 ${inLocks.get()} 回、入口の確かめ ${inProbe.get()} 回、" +
                "ホストの I/O ${inHostIo.get()} 回、端末 ${inTerminal.get()} 回、VFS ${inVfs.get()} 回、" +
                "一番上: ${tops.entries.sortedByDescending { it.value.get() }.take(8).joinToString { "${it.key}=${it.value.get()}" }}"
    }

    /** テストのスレッドで、[locks] を取っては放し続ける（待ち行列を作り、プロセスの側の取得・解放を AQS の遅い道に入れる） */
    private fun contender(locks: List<ReentrantLock>, stop: AtomicBoolean): Thread = Thread {
        while (!stop.get()) {
            for (l in locks) {
                l.lock()
                try {
                    repeat(50) { Thread.onSpinWait() }
                } finally {
                    l.unlock()
                }
            }
            // ロックは公平でないので、間を空けないとプロセスの側が取れずに飢える
            java.util.concurrent.locks.LockSupport.parkNanos(20_000)
        }
    }.apply {
        isDaemon = true
        start()
    }

    /** 尽きる位置をずらすための詰め物（[pad] 段だけ積んでから [body] を呼ぶ） */
    private fun padThen(pad: Int, body: () -> Long): Long = if (pad <= 0) body() else padThen(pad - 1, body) + 1

    private fun recurse(ctx: FakeContext, pattern: Pattern, n: Int): Long {
        when (pattern) {
            Pattern.HOST_EVERY_FRAME -> ctx.callImport("env", "h")
            Pattern.FUEL_EVERY_FRAME -> ctx.callFuelHandler(0)
            Pattern.MIXED -> if (n % 3 == 0) ctx.callFuelHandler(0) else ctx.callImport("env", "h")
            Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL -> if (n % 2 == 0) ctx.callUnwindableFuelHandler(0) else ctx.callImport("env", "h")
            Pattern.NESTED_HOST -> {
                if (n % 3 == 0) ctx.callFuelHandler(0)
                // 戻らない（`g` の本体が、callGuest の中で次の段を呼び続ける）
                ctx.callImport("env", "g", n.toLong())
            }
        }
        return recurse(ctx, pattern, n + 1) + n
    }

    private val i64ToVoid = WasmFunctionType(listOf(WasmValueType.I64), emptyList())

    private fun victim(pattern: Pattern, pad: Int, round: Round): FakeModule = FakeModule.builder()
        .fuelMetered()
        .import("env", "h", VOID)
        .import("env", "g", i64ToVoid)
        .export("run", VOID) { ctx, _ ->
            if (pattern == Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL) {
                round.context.get().callGuest { padThen(pad) { recurse(ctx, pattern, 0) } }
            } else {
                padThen(pad) { recurse(ctx, pattern, 0) }
            }
            LongArray(0)
        }
        .build()

    private val basePatterns = listOf(Pattern.HOST_EVERY_FRAME, Pattern.FUEL_EVERY_FRAME, Pattern.MIXED)

    private fun sweep(
        kind: ProcessKind,
        rounds: Int,
        body: Body = Body.EMPTY,
        patterns: List<Pattern> = basePatterns,
    ) = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        val h = Harness(config)
        val tally = Tally()
        val stopWaker = AtomicBoolean(false)
        var waker: Thread? = null
        var contenderThread: Thread? = null
        var disk: DiskRig? = null
        var tmp: java.nio.file.Path? = null
        try {
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
            other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }
            val permits = config.maxConcurrentMachines
            // Body.BLOCK: 犠牲のプロセスが block で待ちに入ったら、1 バイト書いて起こす
            val current = AtomicReference<Pair<ProcessRuntime, ByteStream>?>()
            if (body == Body.BLOCK) {
                waker = Thread {
                    while (!stopWaker.get()) {
                        val target = current.get()
                        if (target != null) {
                            val (rt, stream) = target
                            m.lock.withLockValue {
                                if (rt.process.state == ProcessState.WAITING_EVENT && stream.available == 0) {
                                    stream.write(ByteArray(1), 0, 1, m.wakeSink)
                                }
                            }
                        }
                        Thread.onSpinWait()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
            // Body.DISK_IO・CONSOLE: プロセスのスレッドが取る、マシンのロック・CPU・許可の外のロック（安全網の HeldLocks の対象外）。
            // 毎回の後に、テストのスレッドから取れることを確かめる
            val extraLocks = ArrayList<ReentrantLock>()
            var tty: Tty? = null
            if (body == Body.DISK_IO) {
                val dir = java.nio.file.Files.createTempDirectory("kibi-858-")
                tmp = dir
                val a = DiskFileSystem.open(java.nio.file.Files.createDirectories(dir.resolve("a")).toRealPath())
                val b = DiskFileSystem.open(java.nio.file.Files.createDirectories(dir.resolve("b")).toRealPath())
                for ((point, fs) in listOf("/a" to a, "/b" to b)) {
                    val p = VfsPath.parse(point)
                    m.vfs.checkMountPoint(p)
                    m.lock.withLockValue { m.vfs.mountLocked(p, fs, readOnly = false, source = "test:$point") }
                }
                // パスの解決を長くする（ディスクは根から 1 段ずつホストで確かめる）
                var deep = VfsPath.parse("/a")
                for (d in 0 until 12) {
                    deep = deep.child("d$d")
                    m.vfs.createDirectory(deep)
                }
                disk = DiskRig(a, b, deep)
                extraLocks += a.ioLockForTest
                extraLocks += b.ioLockForTest
            }
            if (body == Body.CONSOLE) {
                val display = DisplayDevice(TextBuffer(80, 25), 2)
                m.consoleSink = display.consoleSink
                tty = m.lock.withLockValue { m.consoleTtyLocked() }
                extraLocks += display.lock
            }
            if (extraLocks.isNotEmpty()) contenderThread = contender(extraLocks, stopWaker)
            for (i in 0 until stackHeadroomRounds(rounds)) {
                val pattern = patterns[i % patterns.size]
                // 尽きる位置を 1 段ずつずらす（詰め物の段数を変える）
                val pad = (i / patterns.size * 7) % 97
                val stream = if (body == Body.BLOCK) m.lock.withLockValue { ByteStream(1).also { m.kernel.objects.add(it) } } else null
                val round = Round(Rig(stream, disk, tty), i)
                val driver = runDriver(kind) { ctx, b ->
                    round.context.set(ctx)
                    b.host(ctx) { c, _, _ -> runBody(c, body, round).let { LongArray(0) } }
                    // NESTED_HOST: ゲスト役の次の段を、ホストの関数の中から callGuest で呼ぶ
                    b.hostFunction(
                        "env",
                        "g",
                        i64ToVoid,
                        ctx.hostFunction { c, caller, args ->
                            c.callGuest { recurse(FakeContext(caller as FakeInstance), pattern, args[0].toInt() + 1) }
                            LongArray(0)
                        },
                    )
                }
                val rt = m.spawnRt(h.program(victim(pattern, pad, round)), driver)
                if (stream != null) current.set(rt to stream)
                eventuallyDescribe(stackHeadroomWait(10_000), { "深い再帰が終わらない（$i 回目、$pattern、pad $pad）: ${state(h, m, rt)}" }) {
                    h.scheduler.tick()
                    rt.process.state.isTerminal || m.state == MachineState.CRASHED
                }
                current.set(null)
                assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$i 回目）: ${rt.lastError}")
                assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
                assertEquals("call stack exhausted", crashReason(rt.lastError!!), "${rt.lastError}")
                record(tally, rt)
                // ほかのスレッドから、マシンのロックが取れる（持ったまま死んだスレッドがいない）
                assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$i 回目、$pattern、pad $pad）: ${m.lock}")
                m.lock.unlock()
                // 表示デバイス・ディスクのリースのロックも残っていない（残るとメインスレッドの描画・同じディスクが止まる）
                for (l in extraLocks) {
                    assertTrue(l.tryLock(5, TimeUnit.SECONDS), "ほかのロックが取れない（$i 回目、$pattern、pad $pad）: $l")
                    l.unlock()
                }
                // 同じ台と別の台のプロセスが進み続ける（CPU・許可が渡り続ける）
                val same = sameProgress.get()
                val otherBefore = otherProgress.get()
                eventuallyDescribe(stackHeadroomWait(10_000), { "同じ台のプロセスが止まった（$i 回目、$pattern、pad $pad）: ${state(h, m, rt)}" }) {
                    h.scheduler.tick()
                    sameProgress.get() > same + 2
                }
                h.tickUntilTrue("別の台のプロセスが止まった（$i 回目、$pattern、pad $pad）") { otherProgress.get() > otherBefore + 2 }
            }
            m.stop()
            other.stop()
            h.tickUntilTrue("止まらない") { m.state == MachineState.STOPPED && other.state == MachineState.STOPPED }
            // CPU は誰も持っておらず、待ち手もいない。許可はすべて戻った
            eventually { m.cpu.currentOwner == null && m.cpu.waiting == 0 }
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") { h.scheduler.permits.availablePermits() == permits }
            assertEquals(0, h.scheduler.permits.waiting)
            assertTrue(!m.lock.isLocked && !m.lock.hasQueuedThreads(), "${m.lock}")
        } finally {
            stopWaker.set(true)
            waker?.join(5_000)
            contenderThread?.join(5_000)
            println("#858 $kind $body $patterns: ${tally.describe()}")
            h.close()
            disk?.let {
                it.a.retire()
                it.b.retire()
            }
            tmp?.let { dir -> dir.toFile().deleteRecursively() }
        }
        // 尽きた位置が JDK のロックの中だったことはない（入口の余裕の確かめが、ロックを取る前に止める）
        assertEquals(0, tally.inLocks.get(), "スタックが JDK のロックの中で尽きた")
    }

    private fun state(h: Harness, m: Machine, rt: ProcessRuntime): String {
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        val frames = t?.stackTrace?.take(8)?.joinToString(" <- ")
        return "CPU ${m.cpu.currentOwner?.pid} 待ち ${m.cpu.waiting}、許可 ${h.scheduler.permits.availablePermits()} 待ち ${h.scheduler.permits.waiting}、" +
            "lock ${m.lock}、SOE: $frames"
    }

    private fun record(tally: Tally, rt: ProcessRuntime) {
        tally.crashed.incrementAndGet()
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        val top = t?.stackTrace?.firstOrNull() ?: return
        val c = top.className
        tally.tops.computeIfAbsent(c.substringAfterLast('.')) { AtomicInteger() }.incrementAndGet()
        when {
            c.startsWith("java.util.concurrent.locks.") -> {
                tally.inLocks.incrementAndGet()
                println("#858 ロックの中で尽きた: ${t.stackTrace.take(16).joinToString(" <- ")}")
            }
            c == StackHeadroom::class.java.name -> tally.inProbe.incrementAndGet()
            c.startsWith("java.nio.") || c.startsWith("sun.nio.") || c.startsWith("java.io.") -> tally.inHostIo.incrementAndGet()
            c.startsWith("pro.ikili.kibicomputers.core.display.") || c.startsWith("pro.ikili.kibicomputers.core.kernel.tty.") ->
                tally.inTerminal.incrementAndGet()
            c.startsWith("pro.ikili.kibicomputers.core.kernel.vfs.") -> tally.inVfs.incrementAndGet()
        }
    }

    @Test
    fun `BLOCKINGで尽きる位置をずらして深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() = sweep(ProcessKind.BLOCKING, rounds = 120)

    @Test
    fun `WAIT_POINTで尽きる位置をずらして深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() = sweep(ProcessKind.WAIT_POINT, rounds = 120)

    @Test
    fun `BLOCKINGでホストの関数の本体がawaitHostでCPUと許可を取り直しても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.BLOCKING, rounds = 120, body = Body.AWAIT_HOST)

    @Test
    fun `BLOCKINGでホストの関数の本体がblockで待ってCPUと許可を取り直しても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.BLOCKING, rounds = 60, body = Body.BLOCK)

    @Test
    fun `BLOCKINGで止まりうる関数の燃料切れの口とホストの関数の再入で深くなっても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.BLOCKING, rounds = 80, patterns = listOf(Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL, Pattern.NESTED_HOST))

    @Test
    fun `WAIT_POINTで止まりうる関数の燃料切れの口とホストの関数の再入で深くなっても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 80, patterns = listOf(Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL, Pattern.NESTED_HOST))

    @Test
    fun `WAIT_POINTでホストの関数の再入の本体がカーネルロックの中でVFSを引いても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 60, body = Body.KERNEL_VFS, patterns = listOf(Pattern.NESTED_HOST))

    @Test
    fun `BLOCKINGでホストの関数の本体がカーネルロックの中でVFSを引いても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.BLOCKING, rounds = 120, body = Body.KERNEL_VFS)

    @Test
    fun `WAIT_POINTでホストの関数の本体がカーネルロックの中でVFSを引いても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 120, body = Body.KERNEL_VFS)

    @Test
    fun `BLOCKINGでホストの関数の本体がディスクの深いところまで入っても、尽きる位置がロックの中にならず、リースのロックも残らない`() =
        sweep(ProcessKind.BLOCKING, rounds = 24, body = Body.DISK_IO)

    @Test
    fun `WAIT_POINTでホストの関数の本体がディスクの深いところまで入っても、尽きる位置がロックの中にならず、リースのロックも残らない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 24, body = Body.DISK_IO)

    @Test
    fun `BLOCKINGでホストの関数の本体がコンソールに書いて表示デバイスに流しても、表示デバイスのロックが残らない`() =
        sweep(ProcessKind.BLOCKING, rounds = 60, body = Body.CONSOLE)

    @Test
    fun `WAIT_POINTでホストの関数の本体がコンソールに書いて表示デバイスに流しても、表示デバイスのロックが残らない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 60, body = Body.CONSOLE)

    /**
     * 安全網: 入口の確かめが漏れてロックが残ったときのまね。ドライバーがマシンのロックを取ったまま返ると、step・専用スレッドの終わりで
     * 放され、マシンが不整合で CRASHED になる（サーバーの tick がそのマシンのロックで止まらない）。ほかの台は動き続ける
     */
    private fun leakedLockIsRecovered(kind: ProcessKind) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val other = h.machine(fuelPerTick = 1_000_000)
            val progress = AtomicInteger()
            other.spawnTest(h.program(spinForever(progress)), runDriver())
            val leaker = TestDriver(kind) { _ ->
                m.lock.lock()
                StepResult.Exit(0)
            }
            m.spawnTest(h.program(emptyModule()), leaker)
            h.tickUntilTrue("マシンが CRASHED にならない") { m.state == MachineState.CRASHED }
            assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが放されていない: ${m.lock}")
            m.lock.unlock()
            val before = progress.get()
            h.tickUntilTrue("ほかの台が止まった") { progress.get() > before + 2 }
            assertEquals(MachineState.RUNNING, other.state)
            other.stop()
        }
    }

    @Test
    fun `BLOCKINGの専用スレッドがマシンのロックを持ったまま終わると、放してマシンをCRASHEDにする`() = leakedLockIsRecovered(ProcessKind.BLOCKING)

    @Test
    fun `WAIT_POINTのstepがマシンのロックを持ったまま終わると、放してマシンをCRASHEDにする`() = leakedLockIsRecovered(ProcessKind.WAIT_POINT)

    // ---- 安全網の枝（recoverLeaks）----

    /** [owner]（`MachineCpu`・`PermitPool`）の中のロック。テストで、ロックを持ったまま step を終えるまねをするのに使う */
    private fun innerLock(owner: Any): ReentrantLock =
        owner.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(owner) as ReentrantLock

    /** [t]（step・専用スレッドを動かしていたスレッド）が、`runStep`・`runBlocking`（安全網を含む）を抜けるまで待つ */
    private fun awaitOutOfRunner(t: Thread) =
        eventuallyDescribe(10_000, { "安全網を通り終えない: ${t.stackTrace.take(6).joinToString(" <- ")}" }) {
            t.stackTrace.none { it.methodName == "runStep" || it.methodName == "runBlocking" }
        }

    /**
     * [leak] で step の中の何かを漏らして終えたとき、安全網が戻し、[crashed] ならマシンを CRASHED にする（そうでなければ動き続ける）ことを確かめる。
     * 終わりに、ロック・CPU・許可がそろっていて、同じ台に新しいプロセスを足して終えられ、ほかの台が動き続けることも見る
     */
    private fun safetyNet(kind: ProcessKind, crashed: Boolean, leak: (Machine, ProcessRuntime) -> Unit) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val permits = h.scheduler.permits.availablePermits()
            val m = h.machine(fuelPerTick = 1_000_000)
            val other = h.machine(fuelPerTick = 1_000_000)
            val progress = AtomicInteger()
            other.spawnTest(h.program(spinForever(progress)), runDriver())
            val rtFuture = CompletableFuture<ProcessRuntime>()
            val worker = AtomicReference<Thread>()
            val leaker = TestDriver(kind) { _ ->
                worker.set(Thread.currentThread())
                leak(m, rtFuture.get())
                StepResult.Exit(0)
            }
            rtFuture.complete(m.spawnRt(h.program(emptyModule()), leaker))
            h.tickUntilTrue("step が始まらない") { worker.get() != null }
            awaitOutOfRunner(worker.get())
            if (crashed) {
                assertEquals(MachineState.CRASHED, m.state)
            } else {
                assertNotEquals(MachineState.CRASHED, m.state)
            }
            assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが放されていない: ${m.lock}")
            m.lock.unlock()
            for (inner in listOf(innerLock(m.cpu), innerLock(h.scheduler.permits))) {
                assertTrue(inner.tryLock(5, TimeUnit.SECONDS), "中のロックが放されていない: $inner")
                inner.unlock()
            }
            assertEquals(null, m.cpu.currentOwner, "CPU が返っていない")
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") { h.scheduler.permits.availablePermits() == permits }
            if (!crashed) {
                // 動き続ける台は、新しいプロセスを終えられる
                val pid = m.spawnTest(h.program(emptyModule()), runDriver(kind))
                h.tickUntilTrue("続きのプロセスが終わらない") { m.stateOf(pid).isTerminal }
                assertNotEquals(MachineState.CRASHED, m.state)
            }
            val before = progress.get()
            h.tickUntilTrue("ほかの台が止まった") { progress.get() > before + 2 }
            assertEquals(MachineState.RUNNING, other.state)
            other.stop()
        }
    }

    /** 終わったプロセスが CPU を持ったまま step を終える（`exit` が CPU を返さなかったまね） */
    private val dropCpuMark: (Machine, ProcessRuntime) -> Unit = { _, rt -> rt.holdsCpu = false }

    @Test
    fun `BLOCKINGで終わったプロセスがCPUを持ったままなら、返してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.BLOCKING, crashed = true, leak = dropCpuMark)

    @Test
    fun `WAIT_POINTで終わったプロセスがCPUを持ったままなら、返してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = true, leak = dropCpuMark)

    @Test
    fun `BLOCKINGでCPUの中のロックを持ったまま終わると、放してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.BLOCKING, crashed = true) { m, _ -> innerLock(m.cpu).lock() }

    @Test
    fun `WAIT_POINTでCPUの中のロックを持ったまま終わると、放してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = true) { m, _ -> innerLock(m.cpu).lock() }

    @Test
    fun `BLOCKINGで許可の中のロックだけを持ったまま終わると、放すだけでマシンはCRASHEDにしない`() =
        safetyNet(ProcessKind.BLOCKING, crashed = false) { m, _ -> innerLock(m.scheduler.permits).lock() }

    @Test
    fun `WAIT_POINTで許可の中のロックだけを持ったまま終わると、放すだけでマシンはCRASHEDにしない`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, _ -> innerLock(m.scheduler.permits).lock() }

    /**
     * CPU の待ちの途中で抜けた（`MachineCpu.acquire` の `await` の中でスタックが尽きたなど）まね: まだ渡されていない待ち手を、step の
     * スレッドのものとして残す。step の中で残すと、プロセスが終わって CPU を返したときに、その待ち手に渡る（受け取る者のいない CPU）。
     * 安全網は、その CPU を次へ渡す。待ちの途中ではカーネルの状態を変えていないので、マシンは CRASHED にしない
     */
    @Test
    fun `BLOCKINGでCPUの待ち手を残して終わり、その待ち手にCPUが渡っても、次へ渡してマシンは動き続ける`() =
        safetyNet(ProcessKind.BLOCKING, crashed = false) { m, rt -> m.cpu.leaveWaiterForTest(rt, Thread.currentThread()) }

    @Test
    fun `WAIT_POINTでCPUの待ち手を残して終わり、その待ち手にCPUが渡っても、次へ渡してマシンは動き続ける`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, rt -> m.cpu.leaveWaiterForTest(rt, Thread.currentThread()) }

    /**
     * 渡されないまま残った CPU の待ち手（CPU を返し終えた後、安全網の前に残すまね。`afterUnregister`）。安全網が外さなければ、
     * 持ち主のいない CPU の待ち行列の先頭に残り、後から来たプロセスが CPU を受け取れない（続きのプロセスが終わらない）
     */
    private fun waitingCpuWaiter(kind: ProcessKind) =
        safetyNet(kind, crashed = false) { m, rt ->
            m.scheduler.hooks.afterUnregister = { mm, _ ->
                if (mm === m) {
                    m.scheduler.hooks.afterUnregister = null
                    m.cpu.leaveWaiterForTest(rt, Thread.currentThread())
                }
            }
        }

    @Test
    fun `BLOCKINGで渡されていないCPUの待ち手を残して終わると、外してマシンは動き続ける`() = waitingCpuWaiter(ProcessKind.BLOCKING)

    @Test
    fun `WAIT_POINTで渡されていないCPUの待ち手を残して終わると、外してマシンは動き続ける`() = waitingCpuWaiter(ProcessKind.WAIT_POINT)

    /** 許可の待ちの途中で抜けたまね（渡されていない待ち手）。外すので、後で返された許可が待ち手に渡って減ったままにならない */
    @Test
    fun `BLOCKINGで許可の待ち手を残して終わると、外して許可が減らない`() =
        safetyNet(ProcessKind.BLOCKING, crashed = false) { m, _ -> m.scheduler.permits.leaveWaiterForTest(Thread.currentThread(), granted = false) }

    @Test
    fun `WAIT_POINTで許可の待ち手を残して終わると、外して許可が減らない`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, _ -> m.scheduler.permits.leaveWaiterForTest(Thread.currentThread(), granted = false) }

    /** 許可を渡された後、`acquire` から戻る前に抜けたまね。受け取る者のいない許可を返す */
    @Test
    fun `WAIT_POINTで渡された許可の待ち手を残して終わると、許可を返す`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, _ -> m.scheduler.permits.leaveWaiterForTest(Thread.currentThread(), granted = true) }

    /**
     * 安全網は、マシンのロックを持ったまま（再入して）マシンを CRASHED にしてから放す。放してから CRASHED にするまでの間に、
     * ほかのスレッドがマシンのロックを取って、途中までしか変わっていないかもしれない状態に入らない（#828 のレビュー）。
     * ロックを持ったまま step を終え、テストのスレッドはそのロックを待つ。取れたときには、もう CRASHED になっている
     */
    private fun crashedBeforeLockIsReleased(kind: ProcessKind) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val stepDone = CountDownLatch(1)
            val leaker = TestDriver(kind) { _ ->
                m.lock.lock()
                stepDone.countDown()
                // テストのスレッドがロックを待ち始めるまで、持ったまま
                eventually(10_000, "テストのスレッドがロックを待たない") { m.lock.hasQueuedThreads() }
                StepResult.Exit(0)
            }
            m.spawnTest(h.program(emptyModule()), leaker)
            // step がロックを取ったら tick しない（テストのスレッドだけが、ロックを待つようにする）
            eventually(10_000, "step が始まらない") {
                stepDone.count == 0L || run {
                    h.scheduler.tick()
                    false
                }
            }
            assertTrue(m.lock.tryLock(10, TimeUnit.SECONDS), "マシンのロックが放されていない: ${m.lock}")
            val observed = try {
                m.state
            } finally {
                m.lock.unlock()
            }
            assertEquals(MachineState.CRASHED, observed, "ロックが取れたときに、まだ CRASHED になっていない")
        }
    }

    @Test
    fun `BLOCKINGでマシンのロックを持ったまま終わると、ほかのスレッドがロックを取る前にCRASHEDにする`() =
        crashedBeforeLockIsReleased(ProcessKind.BLOCKING)

    @Test
    fun `WAIT_POINTでマシンのロックを持ったまま終わると、ほかのスレッドがロックを取る前にCRASHEDにする`() =
        crashedBeforeLockIsReleased(ProcessKind.WAIT_POINT)

    /**
     * 誤検知しない: 同じプロセスの次の step が別のワーカーで終わった（`finishLocked` の後・CPU を返す前）間に、前の step のワーカーが
     * 安全網に着いても、マシンを CRASHED にしない（その間は、終わったプロセスが CPU の持ち主なのが正しい）。
     * 前の step のワーカーを登録を外した後で止め、次の step を終えさせて CPU を返す前で止め、そこで前のワーカーを進める。
     * 最後に次の step が CPU を返し、プロセスは EXITED で、CPU・許可がそろう
     */
    @Test
    fun `次のstepが終わってCPUを返す前に前のstepの安全網が走っても、CRASHEDにしない`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val permits = h.scheduler.permits.availablePermits()
            val m = h.machine(fuelPerTick = 1000)
            val stream = m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } }
            val driver = TestDriver(
                ProcessKind.WAIT_POINT,
                onResume = { _, _ -> StepResult.Exit(0) },
            ) { StepResult.Wait(listOf(WaitKey(stream.id, WaitCondition.READABLE))) }
            h.scheduler.tick()
            val firstWorker = AtomicReference<Thread>()
            val atFirst = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val atFinish = CountDownLatch(1)
            val releaseFinish = CountDownLatch(1)
            h.scheduler.hooks.afterUnregister = { mm, _ ->
                if (mm === m && firstWorker.compareAndSet(null, Thread.currentThread())) {
                    atFirst.countDown()
                    releaseFirst.await(20, TimeUnit.SECONDS)
                }
            }
            h.scheduler.hooks.afterFinish = { mm, _ ->
                if (mm === m) {
                    atFinish.countDown()
                    releaseFinish.await(20, TimeUnit.SECONDS)
                }
            }
            val rt = m.spawnRt(h.program(emptyModule()), driver)
            assertTrue(atFirst.await(10, TimeUnit.SECONDS), "最初の step が終わらない")
            // 起こすと、次の step が別のワーカーで始まり、終わって（EXITED）CPU を返す前で止まる
            m.lock.withLockValue { stream.write(ByteArray(1), 0, 1, m.wakeSink) }
            assertTrue(atFinish.await(10, TimeUnit.SECONDS), "次の step が終わらない")
            assertEquals(ProcessState.EXITED, rt.process.state)
            assertTrue(m.cpu.currentOwner === rt, "終わったプロセスが CPU の持ち主のはず")
            // 前の step のワーカーが安全網を通る
            releaseFirst.countDown()
            awaitOutOfRunner(firstWorker.get())
            assertNotEquals(MachineState.CRASHED, m.state, "次の step が動いている間を、漏れと見誤った")
            releaseFinish.countDown()
            eventually { m.cpu.currentOwner == null }
            eventually(message = "許可が戻らない") { h.scheduler.permits.availablePermits() == permits }
            assertNotEquals(MachineState.CRASHED, m.state)
            assertEquals(ProcessState.EXITED, rt.process.state)
            m.stop()
        }
    }
}

/**
 * スタックの余裕の確かめ（#828・#858）のテスト 1 本の時間の上限。ふつうは 120 秒。インタプリタで回す opt-in のタスク
 * （`-Pkibi.stackHeadroom=true` の `stackHeadroomColdTest` など）は、`kibi.stackHeadroom.timeoutSeconds` で延ばす
 */
internal fun stackHeadroomTestLimit(): Duration = Duration.ofSeconds(Integer.getInteger("kibi.stackHeadroom.timeoutSeconds", 120).toLong())

/** 1 回分の待ちの上限 [millis] を、[stackHeadroomTestLimit] が 120 秒より長いときは同じ割合で延ばす */
internal fun stackHeadroomWait(millis: Long): Long = millis * maxOf(1L, stackHeadroomTestLimit().seconds / 120)

/**
 * 1 本の回数 [rounds] を、`kibi.stackHeadroom.roundsScale`（既定 1）の割合で減らす（インタプリタで回す opt-in のタスク用。少なくとも 4 回）
 */
internal fun stackHeadroomRounds(rounds: Int): Int =
    maxOf(4, (rounds * (System.getProperty("kibi.stackHeadroom.roundsScale")?.toDouble() ?: 1.0)).toInt())
