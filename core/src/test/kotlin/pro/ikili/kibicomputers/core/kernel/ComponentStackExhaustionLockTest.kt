package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunction
import pro.ikili.kibicomputers.core.runtime.component.link.HostWait
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * コンポーネントの canon の lift・lower の中（realloc・post-return を含む）からロックを取る道で、スタックが尽きる位置をずらしても、
 * カーネルのロック・CPU・許可が壊れないことを確かめる（#858。#828 の `StackExhaustionLockTest` のコンポーネント版）。
 *
 * 実 Endive のコンポーネントの相互再帰で、1 段ごとに次を通す:
 * - 文字列を受けて文字列を返すホストの関数 `echo`（引数の lift、結果の lower の中の realloc。realloc は少し計算して燃料を使う）
 * - lift した関数の中身が lower したホストの関数そのもの（`CoreRef.Lowered`。`env.wrapHost` の包みを通らずにホストの関数の本体へ入る）
 * - post-return を持つ lift した関数（post-return も少し計算する）
 *
 * ホストの関数の本体は、どちらもカーネルロックを取る。スライスは小さく、同じ台に燃料を取り合うプロセスを置くので、realloc・post-return・
 * 再帰の中の燃料切れは、CPU・許可を返して取り直す道（`onFuelExhausted`）にも入る。尽きる位置は、ドライバーの詰め物の段数でずらす。
 *
 * 入口の `top`・`edge`（どちらも `body` を呼ぶ。深さは `top` が Int.MAX_VALUE、`edge` が 0）は、止まりうるホストの関数 `wait`（[HostWait.UNWIND]）を呼んでから、
 * 浅い `f` を繰り返し呼び、最後に深さ n の `f` を呼ぶ。
 * `wait` があるので、待機点への変換が `top` に燃料切れの待ち点（`kibi.async_fuel`）を作り（[ComponentProgram.fuelYield] が真）、
 * WAIT_POINT では待機点型に固有の道を通る:
 * - 深い `f` の中（ゲストからゲストへの呼び出しの下）の `kibi.fuel` は、`onFuelExhaustedPlain` で前借りする（#803）
 * - `top` の loop の先頭の `kibi.async_fuel` は、`onComponentFuelExhaustedUnwindable` → `LinkedComponent.requestUnwind` で巻き戻して燃料を待ち、
 *   巻き直して続ける（ドライバーは `WasiP2Driver` と同じく `StepResult.WaitFuel` を返す）
 *
 * 巻き戻しは `top`（外からの call の 1 段目）でしか起きず、深い `f` の下では起きない（待ちがゲストからゲストへの呼び出しの下にあると、
 * 変換を断る）。そこで、巻き戻しの道を境目の近くで動かすために、`f` を呼ばず `wait` と燃料を使う計算の loop だけの入口 `wind` を、
 * ドライバーの詰め物を尽きる境目の近くまで積んで呼ぶ回（[edgeSweep]）も回す。境目は二分探索で 1 段の精度で探し（その回も毎回確かめる）、
 * その後は境目の 16 段手前から [SPAN] 段先までを回数で等分した刻み（96 回で約 3.3 段、冷えた道の 24 回で約 13 段）でずらす。
 * `f` の連鎖（echo の包みの確かめ 32 段、realloc・post-return の中の `ensure(128)`）を呼ばないので、境目を決めるのは巻き戻し・巻き直しの道
 * そのものになり、入口の確かめが「ぎりぎり通る」深さで、その先の巻き戻し・巻き直しが収まるかを見る。巻き直した後の結果は、尽きなかった回でも
 * 期待値と合うことを確かめる。
 *
 * 確かめられたのは、「境目の近くで入口の確かめを通った回は、巻き戻して巻き直し、最後まで終わる（結果も合う）」まで。手元の結果
 * （JIT、Windows）では、尽きた回は確かめの中か詰め物の再帰そのもの（`wind` に入る前）・ランタイムの中で、巻き戻した後に尽きた回は、
 * 巻き直した後の入口の確かめの中（`PROBE`）だけだった。
 * 巻き戻し・巻き直しの最中に尽きた回は観測されていない（その位置に当てることは #903）
 *
 * 毎回の待ちは、`StackExhaustionLockTest` と同じ見分け（[awaitOrClassify]。遅れか停止か）と上限付きの読み（[peek]）を使う
 */
class ComponentStackExhaustionLockTest {
    private val d = "$"

    /** ホストの関数を呼んだ回数（どちらも呼ばれていることを確かめる） */
    private val echoCalls = AtomicInteger()
    private val pidCalls = AtomicInteger()
    private val waitCalls = AtomicInteger()

    private val host = ComponentHost.of(
        LinkTesting.host(
            "test:host/k",
            "wait" to HostFunction(LinkTesting.fn(emptyList(), null), HostWait.UNWIND) { _, _ ->
                waitCalls.incrementAndGet()
                null
            },
            "pid" to HostFunction(LinkTesting.fn(emptyList(), CanonType.U32)) { call, _ ->
                pidCalls.incrementAndGet()
                val ctx = (call.env as KernelLinkEnv).ctx
                CanonValue.I32(ctx.withKernelLock { ctx.pid })
            },
            "echo" to HostFunction(LinkTesting.fn(listOf(CanonType.Str), CanonType.Str)) { call, args ->
                echoCalls.incrementAndGet()
                val ctx = (call.env as KernelLinkEnv).ctx
                val s = (args[0] as CanonValue.Str).value
                ctx.withKernelLock { ctx.kernel.processes[ctx.pid] }
                CanonValue.Str.of("$s/${s.length}")
            },
        ),
    )

    /** realloc・post-return の中で回す数 */
    private fun spin(count: Int): String = """
        (local.set ${d}n (i32.const $count))
        (loop ${d}l
          (local.set ${d}x (i32.add (i32.mul (local.get ${d}x) (i32.const 31)) (local.get ${d}n)))
          (local.set ${d}n (i32.sub (local.get ${d}n) (i32.const 1)))
          (br_if ${d}l (local.get ${d}n)))
    """

    /**
     * 親の `f`（`n` が 0 なら 100。そうでなければ `echo` と、子の `pid2`（lower した `pid` を lift し直したもの）を呼んでから、
     * 子の `g`（post-return あり）を `n - 1` で呼ぶ）と、子の `g`（親の `f` を呼ぶだけ）の相互再帰
     */
    private val text = """
        (component
          (import "test:host/k" (instance ${d}h
            (export "wait" (func))
            (export "pid" (func (result u32)))
            (export "echo" (func (param "s" string) (result string)))))
          (alias export ${d}h "wait" (func ${d}wait))
          (alias export ${d}h "pid" (func ${d}pid))
          (alias export ${d}h "echo" (func ${d}echo))
          (core module ${d}Mem
            (memory (export "mem") 1)
            (global ${d}next (mut i32) (i32.const 4096))
            (func (export "realloc") (param i32 i32 i32 i32) (result i32)
              (local ${d}n i32) (local ${d}x i32) (local ${d}p i32)
              ${spin(40)}
              ;; 前から順に切り出し、終わりに近づいたら先頭へ戻る（前の段の値はもう読み終えている）
              (local.set ${d}p (i32.and (i32.add (global.get ${d}next) (i32.const 7)) (i32.const -8)))
              (if (i32.gt_u (i32.add (local.get ${d}p) (local.get 3)) (i32.const 60000)) (then (local.set ${d}p (i32.const 4096))))
              (global.set ${d}next (i32.add (local.get ${d}p) (local.get 3)))
              (local.get ${d}p)))
          (core instance ${d}m (instantiate ${d}Mem))
          (alias core export ${d}m "mem" (core memory ${d}memory))
          (alias core export ${d}m "realloc" (core func ${d}realloc))
          (core func ${d}echo_l (canon lower (func ${d}echo) (memory ${d}memory) (realloc ${d}realloc)))
          (core func ${d}wait_l (canon lower (func ${d}wait)))
          (core module ${d}M1
            (type ${d}ft (func (param i32) (result i32)))
            (type ${d}vt (func (result i32)))
            (import "host" "echo" (func ${d}echo (param i32 i32 i32)))
            (import "host" "wait" (func ${d}wait))
            (import "env" "mem" (memory 1))
            (data (i32.const 64) "stack-headroom-858")
            (table (export "tbl") 2 2 funcref)
            (func ${d}fn (export "f") (param i32) (result i32)
              (call ${d}echo (i32.const 64) (i32.const 18) (i32.const 32))
              (drop (call_indirect (type ${d}vt) (i32.const 1)))
              (if (result i32) (i32.eqz (local.get 0))
                (then (i32.const 100))
                (else (i32.add
                        (call_indirect (type ${d}ft) (i32.sub (local.get 0) (i32.const 1)) (i32.const 0))
                        (i32.load offset=4 (i32.const 32))))))
            ;; 外からの入口。止まりうるホストの関数 wait を呼ぶので、body・top・edge の燃料の確かめは kibi.async_fuel になる（f は止まりうる関数に
            ;; ならない。止まりうる関数の型 (i32) -> i64・() -> i64 は、f・g の call_indirect の型 (i32) -> i32・() -> i32 と違う）。
            ;; 浅い f を繰り返してから、深さ n の f を呼ぶ
            (func ${d}body (param i32) (result i64)
              (local ${d}i i32) (local ${d}c i32) (local ${d}acc i64)
              (call ${d}wait)
              (local.set ${d}i (i32.const 150))
              (loop ${d}l
                ;; top 自身の loop でも燃料を使う（燃料切れが top の loop の先頭で起き、巻き戻しの道に入る）
                (local.set ${d}c (i32.const 200))
                (loop ${d}cl
                  (local.set ${d}acc (i64.add (local.get ${d}acc) (i64.extend_i32_u (local.get ${d}c))))
                  (local.set ${d}c (i32.sub (local.get ${d}c) (i32.const 1)))
                  (br_if ${d}cl (local.get ${d}c)))
                (local.set ${d}acc (i64.add (local.get ${d}acc) (i64.extend_i32_u (call ${d}fn (i32.const 3)))))
                (local.set ${d}i (i32.sub (local.get ${d}i) (i32.const 1)))
                (br_if ${d}l (local.get ${d}i)))
              (i64.add (local.get ${d}acc) (i64.extend_i32_u (call ${d}fn (local.get 0)))))
            ;; f を呼ばず、wait と燃料を使う計算の loop だけの入口（巻き戻し・巻き直しの道だけが、境目を決める）
            (func ${d}spinBody (result i64)
              (local ${d}i i32) (local ${d}c i32) (local ${d}acc i64)
              (call ${d}wait)
              (local.set ${d}i (i32.const 150))
              (loop ${d}l
                (local.set ${d}c (i32.const 200))
                (loop ${d}cl
                  (local.set ${d}acc (i64.add (local.get ${d}acc) (i64.extend_i32_u (local.get ${d}c))))
                  (local.set ${d}c (i32.sub (local.get ${d}c) (i32.const 1)))
                  (br_if ${d}cl (local.get ${d}c)))
                (local.set ${d}i (i32.sub (local.get ${d}i) (i32.const 1)))
                (br_if ${d}l (local.get ${d}i)))
              (local.get ${d}acc))
            ;; 巻き直しで呼べるのは引数のない関数だけ（LinkedComponent.beginRewind）。深さで 2 つに分ける
            (func (export "top") (result i64) (call ${d}body (i32.const 2147483647)))
            (func (export "edge") (result i64) (call ${d}body (i32.const 0)))
            (func (export "wind") (result i64) (call ${d}spinBody)))
          (core instance ${d}env_exports (export "mem" (memory ${d}memory)))
          (core instance ${d}host_exports (export "echo" (func ${d}echo_l)) (export "wait" (func ${d}wait_l)))
          (core instance ${d}m1 (instantiate ${d}M1 (with "host" (instance ${d}host_exports)) (with "env" (instance ${d}env_exports))))
          (func ${d}f (export "run") (param "n" u32) (result u32) (canon lift (core func ${d}m1 "f")))
          (func (export "top") (result u64) (canon lift (core func ${d}m1 "top")))
          (func (export "edge") (result u64) (canon lift (core func ${d}m1 "edge")))
          (func (export "wind") (result u64) (canon lift (core func ${d}m1 "wind")))
          (component ${d}B
            (import "pid" (func ${d}pid (result u32)))
            (import "f" (func ${d}f (param "n" u32) (result u32)))
            (core func ${d}pid_l (canon lower (func ${d}pid)))
            (core func ${d}fl (canon lower (func ${d}f)))
            (core module ${d}MB
              (import "" "f" (func ${d}f (param i32) (result i32)))
              (func (export "g") (param i32) (result i32) (call ${d}f (local.get 0)))
              (func (export "post") (param i32)
                (local ${d}n i32) (local ${d}x i32)
                ${spin(40)}))
            (core instance ${d}mb (instantiate ${d}MB (with "" (instance (export "f" (func ${d}fl))))))
            (func (export "g") (param "n" u32) (result u32) (canon lift (core func ${d}mb "g") (post-return (func ${d}mb "post"))))
            (func (export "pid2") (result u32) (canon lift (core func ${d}pid_l))))
          (instance ${d}b (instantiate ${d}B (with "pid" (func ${d}pid)) (with "f" (func ${d}f))))
          (core func ${d}gl (canon lower (func ${d}b "g")))
          (core func ${d}pl (canon lower (func ${d}b "pid2")))
          (core module ${d}M2
            (import "" "tbl" (table 2 2 funcref))
            (import "" "g" (func ${d}g (param i32) (result i32)))
            (import "" "p" (func ${d}p (result i32)))
            (elem (i32.const 0) func ${d}g ${d}p))
          (core instance ${d}m2 (instantiate ${d}M2 (with "" (instance
            (export "tbl" (table ${d}m1 "tbl"))
            (export "g" (func ${d}gl))
            (export "p" (func ${d}pl)))))))
    """

    private val program: Program by lazy { Program.component(LinkTesting.load(text, host, metered = true)) }

    /** 尽きる位置をずらすための詰め物 */
    private fun <T> padThen(pad: Int, body: () -> T): T {
        if (pad <= 0) return body()
        val r = padThen(pad - 1, body)
        // 戻り値を使い、末尾の呼び出しにしない
        if (pad < 0) sink = r
        return r
    }

    @Volatile
    private var sink: Any? = null

    /**
     * 入口の関数（[function]）を呼ぶドライバー。`WasiP2Driver` と同じく、燃料切れで巻き戻したら `StepResult.WaitFuel` を返し、
     * 再開では巻き直して同じ関数を呼び直す（`TestDriver` は、`WaitFuel` の後の空の `ready` を断るので使わない）
     */
    private class TopDriver(
        override val kind: ProcessKind,
        private val function: String,
        val result: AtomicLong,
        private val pads: (() -> CanonValue?) -> CanonValue?,
    ) : ProcessDriver {
        /** 燃料切れで巻き戻した回数（巻き直しも同じ回数。再開のたびに巻き直す） */
        val unwinds = AtomicInteger()

        override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) = Unit

        override fun start(ctx: ProcessContext): StepResult = run(ctx)

        override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult {
            check(ready.isEmpty()) { "ホストの関数は待たない。燃料の後の再開だけのはず: $ready" }
            ctx.component.beginRewind()
            return run(ctx)
        }

        private fun run(ctx: ProcessContext): StepResult {
            val component = ctx.component
            val r = pads { component.call(function) }
            if (component.unwound) {
                val suspend = (component.env as KernelLinkEnv).suspend
                check(ctx.takeFuelUnwind()) { "燃料切れでないのに巻き戻した" }
                check(!suspend.hasPendingKeys) { "燃料切れとホストの待ちの巻き戻しが重なった" }
                unwinds.incrementAndGet()
                return StepResult.WaitFuel
            }
            result.set((r as CanonValue.I64).value)
            return StepResult.Exit(0)
        }
    }

    private fun driver(kind: ProcessKind, function: String, pad: Int, result: AtomicLong = AtomicLong()): TopDriver =
        TopDriver(kind, function, result) { body -> padThen(pad, body) }

    /** `edge` の期待値: 150 回の（200 の和 20100 と、浅い `f(3)`）と、`f(0)`。`f(n)` は 100 + 21n（echo は 21 文字を返す） */
    private val expectedShallow = 150L * (20100 + 100 + 3 * 21) + 100

    /** `wind` の期待値: 200 の和 20100 を 150 回 */
    private val expectedWind = 150L * 20100

    /** 毎回の後の確かめに使うもの */
    private class Rig(
        val h: Harness,
        val m: Machine,
        val ticker: HeadroomTicker,
        val sameProgress: AtomicInteger,
        val otherProgress: AtomicInteger,
    )

    /** 止まったときの説明（上限付きで読む。[describeMachineState]）。[rt] があれば、その状態と尽きた位置も付ける */
    private fun describe(r: Rig, rt: ProcessRuntime?): String =
        describeMachineState(r.h, r.m) + "、tick: ${r.ticker.describe()}" +
            (rt?.let { "、犠牲 ${it.process.state}、SOE: ${soe(it)?.stackTrace?.take(8)?.joinToString(" <- ")}" } ?: "")

    /** 毎回の後の確かめ: マシンのロックが取れる。同じ台と別の台のプロセスが進み続ける（遅れか停止かを見分ける） */
    private fun checkAlive(r: Rig, label: String, rt: ProcessRuntime) {
        assertTrue(r.m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$label）: ${r.m.lock}")
        r.m.lock.unlock()
        awaitProgress(r.ticker, "同じ台のプロセス（$label）", r.sameProgress) { describe(r, rt) }
        awaitProgress(r.ticker, "別の台のプロセス（$label）", r.otherProgress) { describe(r, rt) }
    }

    /** [rt] が終わるまで tick する（遅れか停止かを見分ける） */
    private fun awaitEnd(r: Rig, rt: ProcessRuntime, label: String) =
        awaitOrClassify(r.ticker, "深い再帰が終わる（$label）", { describe(r, rt) }) {
            rt.process.state.isTerminal || r.m.state == MachineState.CRASHED
        }

    private fun assertCrashedBySoe(rt: ProcessRuntime, m: Machine, label: String) {
        assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$label）: ${rt.lastError}")
        assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
        val e = rt.lastError
        assertTrue(e is StackOverflowError || (e is WasmTrapException && e.kind == WasmTrapKind.STACK_OVERFLOW), "$e")
        assertEquals("call stack exhausted", crashReason(e!!), "${rt.lastError}")
    }

    /** [body] を、スライスの小さい 2 台（同じ台で燃料を取り合うプロセスが走っている）の上で動かし、終わりに CPU・許可・ロックがそろうことを確かめる */
    private fun onMachines(kind: ProcessKind, tally: SoePositions, body: (Rig) -> Unit) = timeout(stackHeadroomTestLimit()) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        Harness(config).use { h ->
            HeadroomTicker(h).use { ticker ->
                // スライスを小さくし、同じ台で燃料を取り合わせる（realloc・post-return・再帰の中の燃料切れを、譲る道に入れる）
                val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 2_000)
                val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 2_000)
                val sameProgress = AtomicInteger()
                val otherProgress = AtomicInteger()
                m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
                other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
                val rig = Rig(h, m, ticker, sameProgress, otherProgress)
                awaitOrClassify(ticker, "spin が動く", { describe(rig, null) }) { sameProgress.get() > 0 && otherProgress.get() > 0 }

                // 浅い再帰は最後まで進む（WAT とホストの関数の確かめ）。echo は "stack-headroom-858/18"（21 文字）を返す
                val shallow = AtomicLong(-1)
                val ok = m.spawnRt(program, driver(kind, "edge", 0, shallow))
                awaitOrClassify(ticker, "浅い再帰が終わる", { describe(rig, ok) }) { ok.process.state.isTerminal || m.state == MachineState.CRASHED }
                assertEquals(ProcessState.EXITED, ok.process.state, "${ok.lastError}")
                assertEquals(expectedShallow, shallow.get(), "巻き戻して巻き直しても、結果は同じ")
                assertTrue(echoCalls.get() > 0 && pidCalls.get() > 0 && waitCalls.get() > 0)
                if (kind == ProcessKind.WAIT_POINT) {
                    // 待機点への変換がかかり、`top` に燃料切れの待ち点ができた（待機点型に固有の燃料切れの道がある）
                    val c = program.component!!
                    assertTrue(c.waitPoints, "${c.waitPointRejection}")
                    assertTrue(c.fuelYield, "燃料切れの待ち点がない: ${c.fuelYieldRejection}")
                }
                val overdrafts0 = h.scheduler.fuelOverdrafts.get()
                val unwindCost0 = h.scheduler.fuelUnwindCost.get()

                body(rig)

                if (kind == ProcessKind.WAIT_POINT) {
                    // 本番の燃料切れの道を通った: 深い `f` の中の前借り、`top` の巻き戻し
                    assertTrue(h.scheduler.fuelUnwindCost.get() > unwindCost0, "top の燃料切れで巻き戻していない")
                    if (overdraftsExpected) assertTrue(h.scheduler.fuelOverdrafts.get() > overdrafts0, "前借りしていない")
                }
                println(
                    "#858 component $kind: ${tally.describe()}、echo ${echoCalls.get()} 回、pid ${pidCalls.get()} 回、wait ${waitCalls.get()} 回、" +
                        "譲った ${h.scheduler.fuelYields.get()} 回、スレッドを持って燃料を待った ${h.scheduler.fuelBlockingWaits.get()} 回、" +
                        "前借り ${h.scheduler.fuelOverdrafts.get()} 回、巻き戻しの費用 ${h.scheduler.fuelUnwindCost.get()}",
                )
                m.stop()
                other.stop()
                val stopping = { describe(rig, null) }
                awaitOrClassify(ticker, "マシンが止まる", stopping) { m.state == MachineState.STOPPED && other.state == MachineState.STOPPED }
                awaitOrClassify(ticker, "CPU が返る", stopping) { cpuIsIdle(m) }
                awaitOrClassify(ticker, "許可が戻る", stopping) { permitsAreBack(h, config.maxConcurrentMachines) }
                assertTrue(!m.lock.isLocked && !m.lock.hasQueuedThreads(), "${m.lock}")
            }
        }
    }

    /** 深い `f` の回で前借りが起きることを確かめるか（境目の回は浅いので確かめない） */
    private var overdraftsExpected = true

    private fun sweep(kind: ProcessKind, rounds: Int) {
        val tally = SoePositions()
        overdraftsExpected = true
        onMachines(kind, tally) { r ->
            for (i in 0 until stackHeadroomRounds(rounds)) {
                val pad = (i * 7) % 97
                val rt = r.m.spawnRt(program, driver(kind, "top", pad))
                assertEquals(kind, rt.process.kind)
                awaitEnd(r, rt, "$i 回目、pad $pad")
                assertCrashedBySoe(rt, r.m, "$i 回目")
                tally.record(rt)
                checkAlive(r, "$i 回目、pad $pad", rt)
            }
        }
        assertEquals(0, tally.inLocks, "スタックがロックを取る・放す途中で尽きた")
    }

    /**
     * 尽きる境目を `wind`（`f` を呼ばず、`wait` と燃料を使う loop だけ。巻き戻し・巻き直しの道を通る）で探し（倍々と二分探索）、その近くを、
     * 境目の 16 段手前から [SPAN] 段先までを回数で等分した刻みで動かす（1 段ずつではない。96 回で約 3.3 段、冷えた道の 24 回で約 13 段）。
     * 境目を探す回も含めて毎回、終わった回（EXITED）は結果が期待値と合うこと、尽きた回はプロセスだけが `call stack exhausted` で終わり、
     * 尽きた位置がロックの途中でないこと、ロック・同じ台・別の台が動き続けることを確かめる。尽きる位置は、入口の確かめ・詰め物の再帰・
     * `wind` の中（巻き戻し・巻き直し、ホストの関数・canon）のどこにもなりうるが、巻き戻し・巻き直しの最中に尽きた回は観測されていない
     * （クラスの KDoc）。
     *
     * `top`・`edge` を使わないのは、1 回の実行で一番深い道が `f` の連鎖の先（echo の包みの確かめ 32 段、realloc・post-return の中の
     * `ensure(128)`）になり、巻き戻しの道は、その分浅いところで動くので、境目の近くでも入口の確かめと同じ余裕が残るため
     */
    private fun edgeSweep(kind: ProcessKind, rounds: Int) {
        val tally = SoePositions()
        var exited = 0
        var exitedUnwound = 0
        var crashedUnwound = 0
        overdraftsExpected = false
        onMachines(kind, tally) { r ->
            /**
             * [pad] 段の詰め物で `wind` を 1 回動かし、尽きたかを返す。境目を探す回も含めて毎回、終わった回は結果が期待値と合うこと、
             * 尽きた回は `call stack exhausted` で終わり、尽きた位置がロックの途中でないこと、その後にマシンのロックが取れて同じ台・別の台が
             * 進み続けることを確かめる（境目を探す回が、いちばん境目に近い「ぎりぎり」の回になる）
             */
            fun run(pad: Int): Boolean {
                val d = driver(kind, "wind", pad)
                val rt = r.m.spawnRt(program, d)
                awaitEnd(r, rt, "pad $pad")
                val crashed = rt.process.state != ProcessState.EXITED
                if (!crashed) {
                    exited++
                    // 巻き直した後の結果も期待値と合う（スタックが残り少ない状態で巻き戻し・巻き直しをした結果）
                    assertEquals(expectedWind, d.result.get(), "境目の近くで終わった回の結果が違う（pad $pad、巻き戻し ${d.unwinds.get()} 回）")
                    if (d.unwinds.get() > 0) exitedUnwound++
                } else {
                    assertCrashedBySoe(rt, r.m, "pad $pad")
                    val unwound = d.unwinds.get() > 0
                    val position = tally.record(rt, if (unwound) "巻き戻した後" else "最初の通り")
                    if (unwound) crashedUnwound++
                    if (position.isLock) throw AssertionError("スタックがロックを取る・放す途中で尽きた（pad $pad）: ${describe(r, rt)}")
                }
                checkAlive(r, "pad $pad", rt)
                return crashed
            }
            // 詰め物の再帰を JIT のコンパイルに乗せてから探す（インタプリタと JIT のコードでは、1 段のフレームの大きさが違い、境目が数倍動く）
            val warmEnd = System.nanoTime() + 500_000_000L
            while (System.nanoTime() < warmEnd) padThen(2000) { 0 }

            /** 尽きる最小の詰め物の段数（境目）。倍々で尽きるところを見つけ、二分で縮める */
            fun findEdge(): Int {
                var lo = 0
                var hi = 256
                while (!run(hi)) {
                    lo = hi
                    hi *= 2
                    check(hi < (1 shl 20)) { "詰め物を増やしても尽きない" }
                }
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (run(mid)) hi = mid else lo = mid
                }
                return hi
            }

            // 境目の手前（終わる側）と、境目から先（詰め物が多く、残りのスタックが少ない側）を、詰め物の段数をずらして回す。
            // 境目は、1 回の実行でいちばん深い所が尽きる最小の詰め物で、それより浅い所（入口の確かめを通った後の、巻き戻し・巻き直しの
            // 中など）は、詰め物がもっと多いところで尽きる。回すうちに JIT でフレームが小さくなると境目が動くので、BATCH 回ごとに探し直す
            val count = stackHeadroomRounds(rounds)
            var edge = findEdge()
            for (i in 0 until count) {
                if (i > 0 && i % BATCH == 0) edge = findEdge()
                val offset = -16 + i * SPAN / count
                val pad = maxOf(0, edge + offset)
                run(pad)
            }
        }
        println("#858 component edge $kind: 境目を探す回を含めて、終わった $exited 回（巻き戻した $exitedUnwound 回）、尽きた回のうち巻き戻した後のもの $crashedUnwound 回")
        assertTrue(tally.crashed.get() > 0, "境目で 1 度も尽きなかった")
        assertTrue(exited > 0, "境目の手前で 1 度も終わらなかった")
        if (kind == ProcessKind.WAIT_POINT) {
            // 境目の近くまで積んだ状態で、実際に巻き戻して巻き直し、最後まで終わった回がある（巻き戻しの道が「ぎりぎり通る」深さで動いた）
            assertTrue(exitedUnwound > 0, "境目の近くで巻き戻して終わった回がない")
        }
        assertEquals(0, tally.inLocks, "スタックがロックを取る・放す途中で尽きた")
    }

    private fun soe(rt: ProcessRuntime): StackOverflowError? = SoePositions().soe(rt)

    private companion object {
        /** `edgeSweep` が境目を探し直す間隔（回） */
        const val BATCH = 16

        /** `edgeSweep` が、境目から先へ進む詰め物の段数（回数で等分する。1 段ずつではない。入口の確かめの 32 段分より広く取る） */
        const val SPAN = 320
    }

    @Test
    fun `BLOCKINGのコンポーネントでcanonのliftとlowerの中からロックを取る深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.BLOCKING, rounds = 60)

    @Test
    fun `WAIT_POINTのコンポーネントでcanonのliftとlowerの中からロックを取る深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 60)

    @Test
    fun `BLOCKINGのコンポーネントで尽きる境目をwindの詰め物でずらしても、ロック・CPU・許可は壊れない`() =
        edgeSweep(ProcessKind.BLOCKING, rounds = 96)

    @Test
    fun `WAIT_POINTのコンポーネントで尽きる境目の近くで燃料切れの巻き戻し・巻き直しをしても、ロック・CPU・許可は壊れず、結果は変わらない`() =
        edgeSweep(ProcessKind.WAIT_POINT, rounds = 96)
}
