package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.FUEL_OVERDRAFT_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.FUEL_OVERDRAFT_RECHECK
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelCosts
import pro.ikili.kibicomputers.core.wasi.p2.P2Testing
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * p2 のコマンド（コンポーネント）の、止まりうる関数の中の燃料切れも待ち点にする（#803。p1 の #339 に当たる。ADR 0757）。
 * 実際の Endive と `Machine.exec` で動かす。
 *
 * `fuel_spin_wasi.c`（`AsyncFuelTest` と同じ、計算し続ける CLI）を、アダプターで包んだ p2 のコマンド（`wrapped`）と、
 * wasi-sdk で wasi-libc が p2 の import を直接呼ぶ形にしたもの（`native`。`fuel-spin-p2`）の 2 つで使う。
 */
class AsyncFuelP2Test {
    private val limit = Duration.ofSeconds(180)

    private val wrapped: ByteArray by lazy { P2Testing.wrap("fuel-spin-wasi") }
    private val native: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm-native/fuel-spin-p2.wasm")) { "fuel-spin-p2.wasm がない" }.readBytes()
    }

    private fun bytes(nativeBuild: Boolean): ByteArray = if (nativeBuild) native else wrapped

    private fun Machine.install(bytes: ByteArray) {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val f = vfs.open(VfsPath.parse("/bin/spin"), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private inner class Spin(
        val h: Harness,
        val m: Machine,
        args: List<String>,
        bytes: ByteArray? = wrapped,
        outCapacity: Int = 64 * 1024,
    ) {
        val output: Int = m.lock.withLockValue { ByteStream(outCapacity).also { m.kernel.objects.add(it) }.id }
        val pid: Int
        private val out = StringBuilder()

        init {
            if (bytes != null) m.install(bytes)
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            pid = m.exec("/bin/spin", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("spin") + args, stdio = stdio))
        }

        val rt get() = m.rt(pid)
        val process get() = m.lock.withLockValue { m.kernel.processes[pid]!! }

        fun drain(): String {
            m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                s.read(buf, 0, buf.size, m.wakeSink)
                out.append(String(buf, Charsets.UTF_8))
            }
            return out.toString()
        }

        /** スレッドを持たずに燃料を待っている（燃料切れで巻き戻した） */
        fun waitingFuelThreadless(): Boolean = m.lock.withLockValue {
            val p = m.kernel.processes[pid]!!
            p.state == ProcessState.RUNNABLE && rt.thread == null && !rt.stackActive && rt.waitSet == null && p.wait == null &&
                p.asyncStack?.isEmpty == false
        }

        /**
         * 起動が済む（最初のラウンドの出力が出る）まで tick を進め、その時点の `fuelBlockingWaits` を返す。
         * 起動の途中の燃料切れは、巻き戻せないところ（realloc の中など）に当たるとスレッドを持って待つので、数えない
         */
        fun awaitStartup(h: Harness): Long {
            eventually(60_000, "起動が終わらない") {
                h.scheduler.tick()
                Thread.sleep(5)
                drain().isNotEmpty() || m.stateOf(pid).isTerminal
            }
            return h.scheduler.fuelBlockingWaits.get()
        }

        fun finish(): String {
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                drain()
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${rt.lastError}")
            return drain()
        }
    }

    private fun expected(rounds: Int, inner: Long): String {
        var x = 1L
        val sb = StringBuilder()
        repeat(rounds) { r ->
            repeat(inner.toInt()) { x = (x * 1103515245L + 12345L + (x ushr 7)) and 0xFFFF_FFFFL }
            sb.append("$r $x\n")
        }
        return sb.toString()
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = [false, true])
    fun `止まりうる関数の中の燃料切れは巻き戻し、燃料を待つ間スレッドを持たず待機点にある`(nativeBuild: Boolean) = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val s = Spin(h, m, listOf("main", "3", "100000"), bytes(nativeBuild))
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            val program = s.rt.program
            assertTrue(program.component!!.waitPoints, "${program.component!!.waitPointRejection}")
            assertTrue(program.fuelYield, "燃料切れを待ち点にしていない: ${program.fuelYieldRejection}")
            assertNull(program.fuelYieldRejection)
            // 起動（アダプターが引数を受け取るときにホストが呼ぶ realloc の中など）は巻き戻せないところで燃料を使い切ることがあり、
            // tick との前後でスレッドを持って待つことがある。起動が済んだ（最初のラウンドの出力が出た）後の待ちだけを数える
            val blocking0 = s.awaitStartup(h)
            eventually(30_000, "燃料切れで巻き戻さない") {
                h.scheduler.tick()
                Thread.sleep(20)
                s.waitingFuelThreadless() && m.isAtWaitPoint
            }
            assertTrue(h.scheduler.fuelUnwinds.get() > unwinds0)
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            m.lock.withLockValue {
                assertNull(s.process.asyncWait)
                // 巻き戻しの費用は、マシンの燃料の借りになる
                assertTrue(m.kernel.fuel <= -s.process.asyncStack!!.size * FuelCosts.ASYNC_VALUE, "借りがない: ${m.kernel.fuel}")
            }
            assertEquals(expected(3, 100_000), s.finish())
            assertTrue(m.lock.withLockValue { s.process.asyncStack!!.isEmpty })
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "スレッドを持ったまま燃料を待った")
        }
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = [false, true])
    fun `計算し続ける2つのプロセスは、巻き戻してスレッドを持たずに譲り合い、出力は正しい`(nativeBuild: Boolean) = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 200_000, fuelSlice = 20_000)
            val yields0 = h.scheduler.fuelYields.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val a = Spin(h, m, listOf("main", "4", "100000"), bytes(nativeBuild))
            val b = Spin(h, m, listOf("main", "4", "100000"), bytes = null)
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                a.drain()
                b.drain()
                m.stateOf(a.pid).isTerminal && m.stateOf(b.pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(a.pid), "${a.rt.lastError}")
            assertEquals(ProcessState.EXITED, m.stateOf(b.pid), "${b.rt.lastError}")
            assertEquals(expected(4, 100_000), a.drain())
            assertEquals(expected(4, 100_000), b.drain())
            assertTrue(h.scheduler.fuelYields.get() - yields0 > 0, "譲っていない")
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "スレッドを持ったまま待った")
        }
    }

    @Test
    fun `燃料の巻き直しの後に出力の書き込みで待っても、巻き直して着いた呼び出しと取り違えない`() = timeout(limit) {
        // 出力のパイプを小さくし、各ラウンドの printf（blocking-write-and-flush）が書き込みで待つようにする。
        // 燃料切れの巻き戻し・巻き直しと、書き込みの巻き戻し・巻き直しが交互に起きる
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 7_000)
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val s = Spin(h, m, listOf("main", "12", "20000"), outCapacity = 4)
            var sawWriteWait = false
            // 起動の途中の燃料切れは、巻き戻せないところ（realloc の中など）に当たるとスレッドを持って待つので、
            // 起動が済んだ（最初の出力が出た）後の待ちだけを数える
            var blocking0 = -1L
            eventually(120_000, "終わらない") {
                h.scheduler.tick()
                if (m.stateOf(s.pid) == ProcessState.WAITING_EVENT) sawWriteWait = true
                // 起動が済むまでは毎回読む。済んだ後は毎回は読まず、書き込みで待つ間を作る
                if (blocking0 < 0 || sawWriteWait || h.scheduler.fuelUnwinds.get() % 3 == 0L) {
                    if (s.drain().isNotEmpty() && blocking0 < 0) blocking0 = h.scheduler.fuelBlockingWaits.get()
                }
                m.stateOf(s.pid).isTerminal
            }
            assertTrue(blocking0 >= 0, "起動が済んだ時点を測っていない")
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals(expected(12, 20_000), s.drain())
            assertTrue(sawWriteWait, "書き込みで待っていない")
            assertTrue(h.scheduler.fuelUnwinds.get() > unwinds0, "燃料切れで巻き戻していない")
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "スレッドを持ったまま燃料を待った")
        }
    }

    @Test
    fun `葉の関数の中の燃料切れは前借りで先へ進み、止まりうる関数に戻ったところで巻き戻す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 7_000)
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            val s = Spin(h, m, listOf("leaf", "300", "500"))
            // 起動（アダプターの初期化など）は巻き戻せないところで燃料を使い切ることがあり、tick との前後でスレッドを持って待つことがある。
            // 起動が済んだ（最初のラウンドの出力が出た）後の待ちだけを数える
            eventually(60_000, "最初のラウンドが終わらない") {
                h.scheduler.tick()
                s.drain().isNotEmpty() || m.stateOf(s.pid).isTerminal
            }
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            assertEquals(expected(300, 500), s.finish())
            val overdrafts = h.scheduler.fuelOverdrafts.get() - overdrafts0
            assertTrue(overdrafts > 0, "前借りしていない")
            assertTrue(h.scheduler.fuelUnwinds.get() - unwinds0 >= overdrafts, "前借りの後に巻き戻していない")
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "スレッドを持ったまま燃料を待った")
            m.lock.withLockValue { assertTrue(m.kernel.fuel > -FUEL_OVERDRAFT_LIMIT * 2, "借りが大きすぎる: ${m.kernel.fuel}") }
        }
    }

    @Test
    fun `葉の関数の中で前借りの上限を超えて計算し続けたら、スレッドを持ったまま待つ`() = timeout(limit) {
        // p1 と同じく残る性質（ADR 0757 の「制約と残り」）
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val before = h.scheduler.fuelBlockingWaits.get()
            val s = Spin(h, m, listOf("leaf", "2", "100000"))
            // 起動（アダプターの初期化など）で最初の tick の燃料を使い切り、止まりうる関数で巻き戻すことがあるので、葉に入るまで tick を進める
            eventually(30_000, "燃料切れで待たない") {
                h.scheduler.tick()
                Thread.sleep(20)
                m.stateOf(s.pid) == ProcessState.WAITING_FUEL
            }
            assertTrue(h.scheduler.fuelBlockingWaits.get() > before)
            assertFalse(m.isAtWaitPoint)
            assertEquals(expected(2, 100_000), s.finish())
        }
    }

    /**
     * ゲストからゲストへの呼び出し（lift した関数を lower した import）の下で計算し続ける p2 のコマンド。`run` は `inner` の `spin` を
     * [calls] 回呼び、`spin` が 1 回に [n] 回まわる。`run` は（実行時は呼ばない）`pollable.block` を呼ぶので止まりうる関数になり、変換は
     * 待ち点型・燃料切れも待ち点（`fuelYield`）になる。`spin` は待たないので止まりうる関数でなく、燃料は `kibi.fuel`（前借りの道）で確かめる。
     * `spin` は外からの `run` の下の 2 段目なので、`LinkedComponent.canUnwind` が偽。`run` の loop の先頭（1 段目）では真
     */
    private fun guestToGuestSpin(calls: Int, n: Int): ByteArray {
        val d = "$"
        return LinkTesting.wat(
        """
        (component
          (import "wasi:io/poll@0.2.12" (instance ${d}poll
            (export "pollable" (type ${d}pollable (sub resource)))
            (export "[method]pollable.block" (func (param "self" (borrow ${d}pollable))))))
          (alias export ${d}poll "[method]pollable.block" (func ${d}block))
          (core func ${d}block_lower (canon lower (func ${d}block)))
          (core module ${d}inner
            (func (export "spin") (param ${d}n i32) (result i32)
              (local ${d}x i32)
              (loop ${d}l
                (local.set ${d}x (i32.add (i32.mul (local.get ${d}x) (i32.const 31)) (local.get ${d}n)))
                (local.set ${d}n (i32.sub (local.get ${d}n) (i32.const 1)))
                (br_if ${d}l (local.get ${d}n)))
              (i32.const 1)))
          (core module ${d}outer
            (import "inner" "spin" (func ${d}spin (param i32) (result i32)))
            (import "poll" "block" (func ${d}block (param i32)))
            (func (export "run") (result i32)
              (local ${d}k i32)
              (local.set ${d}k (i32.const $calls))
              (loop ${d}l
                ;; spin は常に 1 を返すので、block は実行時には呼ばれない（止まりうる関数にするためだけの呼び出し）
                (if (i32.eqz (call ${d}spin (i32.const $n))) (then (call ${d}block (i32.const 0))))
                (local.set ${d}k (i32.sub (local.get ${d}k) (i32.const 1)))
                (br_if ${d}l (local.get ${d}k)))
              (i32.const 0)))
          (core instance ${d}i (instantiate ${d}inner))
          (func ${d}spin_lifted (param "n" u32) (result u32) (canon lift (core func ${d}i "spin")))
          (core func ${d}spin_lower (canon lower (func ${d}spin_lifted)))
          (core instance ${d}spin_exports (export "spin" (func ${d}spin_lower)))
          (core instance ${d}poll_exports (export "block" (func ${d}block_lower)))
          (core instance ${d}o (instantiate ${d}outer (with "inner" (instance ${d}spin_exports)) (with "poll" (instance ${d}poll_exports))))
          (func ${d}run (result (result)) (canon lift (core func ${d}o "run")))
          (instance ${d}run_i (export "run" (func ${d}run)))
          (export "wasi:cli/run@0.2.12" (instance ${d}run_i)))
        """.trimIndent(),
        )
    }

    /**
     * ゲストからゲストへの呼び出しの下（コンポーネントが巻き戻せない。`LinkedComponent.canUnwind` が偽）の、止まりうる関数でない関数の
     * 燃料切れは、p1 の葉の関数と同じく前借りで先へ進め、callee から戻った後の外の止まりうる関数（1 段目）で巻き戻す（#803）。
     * スレッドを持って燃料を待たない
     */
    @Test
    fun `ゲストからゲストへの呼び出しの下の燃料切れは前借りで戻り、外の止まりうる関数で巻き戻す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 5_000)
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            // 1 回の spin は前借りの上限（FUEL_OVERDRAFT_LIMIT）より十分小さく、1 tick の燃料より大きい
            val s = Spin(h, m, emptyList(), guestToGuestSpin(calls = 60, n = 1_000))
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            val program = s.rt.program
            assertTrue(program.component!!.waitPoints, "${program.component!!.waitPointRejection}")
            assertTrue(program.fuelYield, "燃料切れを待ち点にしていない: ${program.fuelYieldRejection}")
            assertEquals("", s.finish())
            assertTrue(h.scheduler.fuelOverdrafts.get() > overdrafts0, "ゲストからゲストへの呼び出しの下で前借りしていない")
            assertTrue(h.scheduler.fuelUnwinds.get() > unwinds0, "callee から戻った後に巻き戻していない")
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "スレッドを持ったまま燃料を待った")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
        }
    }

    /**
     * ゲストからゲストへの呼び出しの下で、前借りの上限を超えて計算し続けたら（callee から戻らない）、p1 の葉の関数と同じくスレッドを持って待つ
     * （残る性質。ADR 0757 の「制約と残り」）
     */
    @Test
    fun `ゲストからゲストへの呼び出しの下で前借りの上限を超えて計算し続けたら、スレッドを持って待つ`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 5_000)
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val s = Spin(h, m, emptyList(), guestToGuestSpin(calls = 1, n = 200_000))
            assertTrue(s.rt.program.fuelYield, "燃料切れを待ち点にしていない: ${s.rt.program.fuelYieldRejection}")
            assertEquals("", s.finish())
            assertTrue(h.scheduler.fuelBlockingWaits.get() > blocking0, "前借りの上限を超えてもスレッドを持って待っていない")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
        }
    }

    /**
     * ホストの関数・canon が呼んだゲスト（realloc・post-return。`guestCallDepth > 0`）の中で計算し続ける p2 のコマンド。
     * `run` は `get-arguments`（`list<string>` を realloc で受ける）を呼び、受けた引数を確かめて返る。`run` の lift には post-return がある。
     * [reallocSpin]・[postSpin] は realloc（1 回の呼び出しごと）・post-return の中で回す数。[mayBlock] なら、回す関数（realloc か post-return）は
     * （実行時は呼ばない）`pollable.block` を呼ぶので止まりうる関数になり、燃料は `kibi.async_fuel` で確かめる。偽なら `kibi.fuel`（前借りの道）
     */
    private fun guestCallSpin(reallocSpin: Int, postSpin: Int, mayBlock: Boolean): ByteArray {
        val d = "$"
        fun spin(count: Int, blockIf: String): String {
            if (count <= 0) return ""
            val loop = """
                (local.set ${d}n (i32.const $count))
                (loop ${d}l
                  (local.set ${d}x (i32.add (i32.mul (local.get ${d}x) (i32.const 31)) (local.get ${d}n)))
                  (local.set ${d}n (i32.sub (local.get ${d}n) (i32.const 1)))
                  (br_if ${d}l (local.get ${d}n)))
            """
            // 実行時は呼ばない（引数が -1 になることはない）。止まりうる関数にするためだけの呼び出し
            return if (mayBlock) loop + "(if (i32.eq $blockIf (i32.const -1)) (then (call ${d}block (i32.const 0))))" else loop
        }
        return LinkTesting.wat(
        """
        (component
          (import "wasi:io/poll@0.2.12" (instance ${d}poll
            (export "pollable" (type ${d}pollable (sub resource)))
            (export "[method]pollable.block" (func (param "self" (borrow ${d}pollable))))))
          (import "wasi:cli/environment@0.2.12" (instance ${d}env
            (export "get-arguments" (func (result (list string))))))
          (alias export ${d}poll "[method]pollable.block" (func ${d}block))
          (core func ${d}block_lower (canon lower (func ${d}block)))
          (core module ${d}mem
            (import "poll" "block" (func ${d}block (param i32)))
            (memory (export "mem") 1)
            (global ${d}next (mut i32) (i32.const 1024))
            (func (export "realloc") (param i32 i32 i32 i32) (result i32)
              (local ${d}n i32) (local ${d}x i32) (local ${d}p i32)
              ${spin(reallocSpin, "(local.get 3)")}
              ;; 前から順に切り出す（解放しない）
              (local.set ${d}p (i32.and (i32.add (global.get ${d}next) (i32.sub (local.get 2) (i32.const 1)))
                (i32.sub (i32.const 0) (local.get 2))))
              (global.set ${d}next (i32.add (local.get ${d}p) (local.get 3)))
              (local.get ${d}p))
            (func (export "post") (param i32)
              (local ${d}n i32) (local ${d}x i32)
              ${spin(postSpin, "(local.get 0)")}))
          (core instance ${d}poll_exports (export "block" (func ${d}block_lower)))
          (core instance ${d}m (instantiate ${d}mem (with "poll" (instance ${d}poll_exports))))
          (alias core export ${d}m "mem" (core memory ${d}memory))
          (alias core export ${d}m "realloc" (core func ${d}realloc))
          (alias export ${d}env "get-arguments" (func ${d}args))
          (core func ${d}args_lower (canon lower (func ${d}args) (memory ${d}memory) (realloc ${d}realloc)))
          (core module ${d}main
            (import "cli" "get-arguments" (func ${d}args (param i32)))
            (import "poll" "block" (func ${d}block (param i32)))
            (import "env" "mem" (memory 1))
            (func (export "run") (result i32)
              (call ${d}args (i32.const 16))
              ;; 引数は argv[0]（"spin"）の 1 つだけ（realloc が返した場所に lower できている）
              (if (i32.ne (i32.load offset=4 (i32.const 16)) (i32.const 1)) (then unreachable))
              (if (i32.ne (i32.load offset=4 (i32.load (i32.const 16))) (i32.const 4)) (then unreachable))
              ;; 実行時は呼ばない。run を止まりうる関数にする（燃料切れの待ち点を作る）ための呼び出し
              (if (i32.eq (i32.load (i32.const 16)) (i32.const -1)) (then (call ${d}block (i32.const 0))))
              (i32.const 0)))
          (core instance ${d}env_exports (export "mem" (memory ${d}memory)))
          (core instance ${d}cli_exports (export "get-arguments" (func ${d}args_lower)))
          (core instance ${d}o (instantiate ${d}main
            (with "cli" (instance ${d}cli_exports)) (with "poll" (instance ${d}poll_exports)) (with "env" (instance ${d}env_exports))))
          (alias core export ${d}m "post" (core func ${d}post))
          (func ${d}run (result (result)) (canon lift (core func ${d}o "run") (post-return ${d}post)))
          (instance ${d}run_i (export "run" (func ${d}run)))
          (export "wasi:cli/run@0.2.12" (instance ${d}run_i)))
        """.trimIndent(),
        )
    }

    /**
     * ホストの関数・canon が呼んだゲスト（realloc・post-return。`guestCallDepth > 0`）の中の燃料切れは、`fuelYield` が真のコンポーネントでも
     * 前借りも巻き戻しもせず、スレッドを持って待つ（#803。巻き戻すと canon の Kotlin の層（lower・post-return の途中）を巻き戻してしまう）。
     * 止まりうる関数でない realloc・post-return は前借りの判定（`onFuelExhaustedPlain`）で、止まりうるものは
     * `onComponentFuelExhaustedUnwindable` の `!unwindable` で、`onFuelExhausted` に落ちる。p1 の `WasiP1KibiTest` の
     * 「cabi_realloc の中で燃料が切れても、巻き戻さずに燃料を待って続ける」に当たる
     */
    @ParameterizedTest(name = "{0}, mayBlock={1}")
    @CsvSource("realloc, false", "realloc, true", "post-return, false", "post-return, true")
    fun `ホストの関数が呼んだゲストの中の燃料切れは、巻き戻さず前借りもせず、スレッドを持って待って続ける`(place: String, mayBlock: Boolean) = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 5_000)
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            val bytes = if (place == "realloc") {
                guestCallSpin(reallocSpin = 20_000, postSpin = 0, mayBlock = mayBlock)
            } else {
                guestCallSpin(reallocSpin = 0, postSpin = 40_000, mayBlock = mayBlock)
            }
            val s = Spin(h, m, emptyList(), bytes)
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            val program = s.rt.program
            assertTrue(program.component!!.waitPoints, "${program.component!!.waitPointRejection}")
            assertTrue(program.fuelYield, "燃料切れを待ち点にしていない: ${program.fuelYieldRejection}")
            val rt = s.rt
            var waitedInGuestCall = false
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                Thread.sleep(2)
                m.lock.withLockValue {
                    if (s.process.state == ProcessState.WAITING_FUEL && rt.guestCallDepth > 0) waitedInGuestCall = true
                }
                m.stateOf(s.pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${rt.lastError}")
            assertEquals(0, m.lock.withLockValue { s.process.exitCode }, "run が失敗した")
            assertTrue(waitedInGuestCall, "$place の中で、スレッドを持って燃料を待っていない")
            assertTrue(h.scheduler.fuelBlockingWaits.get() > blocking0)
            assertEquals(overdrafts0, h.scheduler.fuelOverdrafts.get(), "ホストの関数が呼んだゲストの中で前借りした")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
        }
    }

    /**
     * 止まりうる関数が、ホストの関数の中からのゲストの呼び出し（`guestCallDepth > 0`）で燃料切れの確かめ（`kibi.async_fuel`）に当たると、
     * 巻き戻せないので `onFuelExhausted`（スレッドを持って待つ）に落ちる。前借りの印・速い道の控えが残っていると、戻った後の止まりうる関数でない
     * 関数の燃料切れが、古い控えで速い道を通る（#887）ので、待った後の手持ちを渡すときに印と控えを消す。
     * 呼ぶ前に印と控えを置いて（前借りの最中に入口が来た形）、戻ったときに消えていることを確かめる
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource("realloc", "post-return")
    fun `ホストの関数が呼んだゲストの中の止まりうる関数の燃料切れは、前借りの印と速い道の控えを残さない`(place: String) = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 5_000)
            val bytes = if (place == "realloc") {
                guestCallSpin(reallocSpin = 20_000, postSpin = 0, mayBlock = true)
            } else {
                guestCallSpin(reallocSpin = 0, postSpin = 40_000, mayBlock = true)
            }
            val s = Spin(h, m, emptyList(), bytes)
            val rt = s.rt
            class Stale(private val inner: WasmFuelHandler) : WasmFuelHandler {
                @Volatile var calls = 0

                @Volatile var left = 0

                override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long = inner.onFuelExhausted(instance, remaining)

                override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                    val inGuestCall = rt.guestCallDepth > 0
                    if (inGuestCall) {
                        rt.fuelOverdraft = true
                        rt.fuelOverdraftInstance = instance
                        rt.fuelOverdraftRecheckAt = -FUEL_OVERDRAFT_RECHECK
                    }
                    val out = inner.onFuelExhaustedUnwindable(instance, remaining)
                    if (inGuestCall) {
                        calls++
                        if (rt.fuelOverdraft || rt.fuelOverdraftInstance != null) left++
                    }
                    return out
                }
            }
            val stale = Stale(rt.fuelHandler)
            rt.fuelHandler = stale
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                Thread.sleep(2)
                m.stateOf(s.pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${rt.lastError}")
            assertTrue(stale.calls > 0, "$place の中の止まりうる関数の燃料切れを通っていない")
            assertEquals(0, stale.left, "$place の中の燃料切れの後も、前借りの印か速い道の控えが残っている")
        }
    }

    @Test
    fun `1回の燃料が巻き直しの費用より小さくても先へ進む`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Spin(h, h.machine(fuelPerTick = 300), listOf("main", "2", "3000"))
            assertEquals(expected(2, 3_000), s.finish())
        }
    }

    @Test
    fun `燃料を待っている間にkillすると、その場でKILLEDになり退避した値を残さない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val s = Spin(h, m, listOf("main", "1000", "100000"))
            h.scheduler.tick()
            eventually(30_000, "燃料切れで巻き戻さない") { s.waitingFuelThreadless() }
            val memory0 = m.lock.withLockValue { s.rt.memory.usedBytes }
            assertTrue(memory0 > 0)
            m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
            assertEquals(ProcessState.KILLED, m.stateOf(s.pid))
            assertTrue(m.lock.withLockValue { s.process.asyncStack!!.isEmpty })
            // 燃料待ちの列から外れ、tick を進めても動かない
            repeat(3) { h.scheduler.tick() }
            assertEquals(ProcessState.KILLED, m.stateOf(s.pid))
            assertEquals(0L, m.lock.withLockValue { s.rt.memory.usedBytes }, "RAM を返していない")
        }
    }

    @Test
    fun `燃料を待っている台の写しでは、プロセスを殺し続きを残さず、元は動き続ける`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val s = Spin(h, m, listOf("main", "3", "100000"))
            h.scheduler.tick()
            eventually(30_000, "燃料切れで巻き戻さない") { s.waitingFuelThreadless() }
            // コンポーネントは静的に保存できない（UNSAVEABLE）ので、燃料待ちで巻き戻していても写しの中で殺す
            val copy = m.captureCopy(CapturePolicy.KILL_TRANSIENT)!!
            assertTrue(s.pid in copy.killedPids, "写しの中で殺していない")
            copy.processes[s.pid]?.let { p ->
                assertEquals(ProcessState.KILLED, p.state)
                assertTrue(p.asyncStack?.isEmpty != false, "写しに退避した値が残った")
            }
            assertTrue(copy.abiCaptures[s.pid] == null, "殺したプロセスの ABI 層を写した")
            assertTrue(m.lock.withLockValue { s.process.asyncStack!!.size } > 0, "元の退避した値を消した")
            assertEquals(expected(3, 100_000), s.finish())
        }
    }

    @Test
    fun `多数の台で計算し続けても、燃料を待つ間スレッドを持たない`() = timeout(limit) {
        val permits = 4
        val config = SchedulerConfig.builder().maxConcurrentMachines(permits).maxBlockingProcesses(64).build()
        Harness(schedulerConfig = config, execRuntime = EndiveRuntime()).use { h ->
            val spins = ArrayList<Spin>()
            repeat(12) {
                val m = h.machine(fuelPerTick = 200_000)
                repeat(4) { j -> spins += Spin(h, m, listOf("main", "1000000", "20000"), bytes = if (j == 0) wrapped else null) }
            }
            // 起動の途中（引数を受け取るときにホストの関数が呼ぶ realloc の中など。巻き戻せない）で燃料が切れると、スレッドを持って待つ。
            // 全員が起動を終えて 1 ラウンド目を出すまで tick を進めてから測る
            eventually(120_000, "1 ラウンド目を出さない") {
                h.scheduler.tick()
                Thread.sleep(5)
                spins.all { it.drain().isNotEmpty() }
            }
            repeat(10) {
                h.scheduler.tick()
                Thread.sleep(5)
            }
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            var maxThreads = 0
            // 遅い CI では 1 tick に回せる台が少ないので、tick の回数を決め打ちせず、全員分より多く巻き戻すまで（最低 40 tick）測る
            val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
            var ticks = 0
            while (ticks < 40 || (h.scheduler.fuelUnwinds.get() - unwinds0 <= spins.size && System.nanoTime() < deadline)) {
                h.scheduler.tick()
                Thread.sleep(5)
                ticks++
                val threads = spins.count { s -> s.m.lock.withLockValue { s.rt.thread != null } }
                if (threads > maxThreads) maxThreads = threads
            }
            val unwinds = h.scheduler.fuelUnwinds.get() - unwinds0
            val blocking = h.scheduler.fuelBlockingWaits.get() - blocking0
            println("多数台の p2 の燃料切れ: $ticks tick で巻き戻し $unwinds 回、スレッドを持って待った $blocking 回、スレッドを持つプロセスの最大 $maxThreads（${spins.size} 本）")
            assertTrue(unwinds > spins.size, "燃料切れで巻き戻していない")
            assertEquals(0, blocking, "スレッドを持ったまま燃料を待った")
            // 動いている（許可を持つ）分だけがスレッドを持つ。許可を返してから登録を外すまでの間の重なりを見込んで 2 倍まで
            assertTrue(maxThreads <= permits * 2, "スレッドを持つプロセスが多すぎる: $maxThreads（${spins.size} 本）")
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            for (s in spins) s.m.lock.withLockValue { s.m.killLocked(s.pid, KillSignal.KILL) }
            // 接続層の負荷試験（#821）が読む口は、数え口と同じ値を返す。動いている間は別スレッドが数え口を増やすので、
            // 全部殺して、走っているステップが終わり、数え口が止まってから比べる（2 回の読みの間の増分で落ちない）
            eventually(30_000, "殺したのに数え口が止まらない") {
                val a = h.scheduler.fuelUnwinds.get()
                Thread.sleep(50)
                val b = h.scheduler.fuelUnwinds.get()
                a == b && h.scheduler.fuelUnwindCount == b && h.scheduler.fuelBlockingWaitCount == h.scheduler.fuelBlockingWaits.get() &&
                    h.scheduler.fuelOverdraftCount == h.scheduler.fuelOverdrafts.get()
            }
            // 台ごとの巻き戻しの数（接続層の負荷試験が、台ごとに起動が済んだかを見る）は、巻き戻した燃料切れ（燃料待ちの fuelUnwinds と、
            // 巻き戻して譲った分）を数える。fuelYields には、巻き戻せないところ（起動の途中の realloc の中など）でスレッドを持ったまま
            // 譲った分も入るので、合計は fuelUnwinds 以上、fuelUnwinds + fuelYields 以下になる（等しいとは限らない）
            val perMachine = spins.map { it.m }.distinct()
            assertTrue(perMachine.all { it.fuelUnwindCount > 0 }, "巻き戻していない台がある")
            val perMachineSum = perMachine.sumOf { it.fuelUnwindCount }
            val unwindTotal = h.scheduler.fuelUnwinds.get()
            val yieldTotal = h.scheduler.fuelYields.get()
            assertTrue(
                perMachineSum in unwindTotal..(unwindTotal + yieldTotal),
                "台ごとの巻き戻しの合計 $perMachineSum が、巻き戻し $unwindTotal と譲り $yieldTotal の範囲にない",
            )
        }
    }
}
