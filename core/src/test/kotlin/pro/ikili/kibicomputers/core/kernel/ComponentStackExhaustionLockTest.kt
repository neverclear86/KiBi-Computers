package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunction
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * 再帰の中の燃料切れは、CPU・許可を返して取り直す道（`onFuelExhausted`）にも入る。尽きる位置は、ドライバーの詰め物の段数でずらす
 */
class ComponentStackExhaustionLockTest {
    private val d = "$"

    /** ホストの関数を呼んだ回数（どちらも呼ばれていることを確かめる） */
    private val echoCalls = AtomicInteger()
    private val pidCalls = AtomicInteger()

    private val host = ComponentHost.of(
        LinkTesting.host(
            "test:host/k",
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
            (export "pid" (func (result u32)))
            (export "echo" (func (param "s" string) (result string)))))
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
          (core module ${d}M1
            (type ${d}ft (func (param i32) (result i32)))
            (type ${d}vt (func (result i32)))
            (import "host" "echo" (func ${d}echo (param i32 i32 i32)))
            (import "env" "mem" (memory 1))
            (data (i32.const 64) "stack-headroom-858")
            (table (export "tbl") 2 2 funcref)
            (func (export "f") (param i32) (result i32)
              (call ${d}echo (i32.const 64) (i32.const 18) (i32.const 32))
              (drop (call_indirect (type ${d}vt) (i32.const 1)))
              (if (result i32) (i32.eqz (local.get 0))
                (then (i32.const 100))
                (else (i32.add
                        (call_indirect (type ${d}ft) (i32.sub (local.get 0) (i32.const 1)) (i32.const 0))
                        (i32.load offset=4 (i32.const 32)))))))
          (core instance ${d}env_exports (export "mem" (memory ${d}memory)))
          (core instance ${d}host_exports (export "echo" (func ${d}echo_l)))
          (core instance ${d}m1 (instantiate ${d}M1 (with "host" (instance ${d}host_exports)) (with "env" (instance ${d}env_exports))))
          (func ${d}f (export "run") (param "n" u32) (result u32) (canon lift (core func ${d}m1 "f")))
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
    private fun padThen(pad: Int, body: () -> Int): Int = if (pad <= 0) body() else padThen(pad - 1, body) + 1

    private fun driver(kind: ProcessKind, depth: Int, pad: Int, result: AtomicInteger): TestDriver = TestDriver(kind) { ctx ->
        result.set(padThen(pad) { (ctx.component!!.call("run", args = listOf(CanonValue.I32(depth))) as CanonValue.I32).value })
        StepResult.Exit(0)
    }

    private fun sweep(kind: ProcessKind, rounds: Int) = timeout(stackHeadroomTestLimit()) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        val tops = ConcurrentHashMap<String, AtomicInteger>()
        var inLocks = 0
        var inProbe = 0
        Harness(config).use { h ->
            // スライスを小さくし、同じ台で燃料を取り合わせる（realloc・post-return・再帰の中の燃料切れを、譲る道に入れる）
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 2_000)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 2_000)
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
            other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }

            // 浅い再帰は最後まで進む（WAT とホストの関数の確かめ）。echo は "stack-headroom-858/18"（21 文字）を返す
            val shallow = AtomicInteger(-1)
            val ok = m.spawnRt(program, driver(kind, 20, 0, shallow))
            h.tickUntilTrue("浅い再帰が終わらない") { ok.process.state.isTerminal || m.state == MachineState.CRASHED }
            assertEquals(ProcessState.EXITED, ok.process.state, "${ok.lastError}")
            assertEquals(100 + 20 * 21, shallow.get())
            assertTrue(echoCalls.get() > 0 && pidCalls.get() > 0)
            if (kind == ProcessKind.WAIT_POINT) assertTrue(program.component!!.waitPoints, "${program.component!!.waitPointRejection}")

            for (i in 0 until rounds) {
                val pad = (i * 7) % 97
                val rt = m.spawnRt(program, driver(kind, Int.MAX_VALUE, pad, AtomicInteger()))
                assertEquals(kind, rt.process.kind)
                eventuallyDescribe(stackHeadroomWait(20_000), { "深い再帰が終わらない（$i 回目、pad $pad）: ${state(h, m, rt)}" }) {
                    h.scheduler.tick()
                    rt.process.state.isTerminal || m.state == MachineState.CRASHED
                }
                assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$i 回目）: ${rt.lastError}")
                assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
                val e = rt.lastError
                assertTrue(e is StackOverflowError || (e is WasmTrapException && e.kind == WasmTrapKind.STACK_OVERFLOW), "$e")
                assertEquals("call stack exhausted", crashReason(e!!), "${rt.lastError}")
                soe(rt)?.stackTrace?.firstOrNull()?.let { top ->
                    tops.computeIfAbsent(top.className.substringAfterLast('.')) { AtomicInteger() }.incrementAndGet()
                    if (top.className.startsWith("java.util.concurrent.locks.")) inLocks++
                    if (top.className == StackHeadroom::class.java.name) inProbe++
                }
                assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$i 回目、pad $pad）: ${m.lock}")
                m.lock.unlock()
                val same = sameProgress.get()
                val otherBefore = otherProgress.get()
                eventuallyDescribe(stackHeadroomWait(10_000), { "同じ台のプロセスが止まった（$i 回目、pad $pad）: ${state(h, m, rt)}" }) {
                    h.scheduler.tick()
                    sameProgress.get() > same + 2
                }
                h.tickUntilTrue("別の台のプロセスが止まった（$i 回目、pad $pad）") { otherProgress.get() > otherBefore + 2 }
            }
            m.stop()
            other.stop()
            h.tickUntilTrue("止まらない") { m.state == MachineState.STOPPED && other.state == MachineState.STOPPED }
            eventually { m.cpu.currentOwner == null && m.cpu.waiting == 0 }
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") {
                h.scheduler.permits.availablePermits() == config.maxConcurrentMachines
            }
            assertEquals(0, h.scheduler.permits.waiting)
            assertTrue(!m.lock.isLocked && !m.lock.hasQueuedThreads(), "${m.lock}")
            println(
                "#858 component $kind: CRASHED $rounds 回、尽きた位置がロックの中 $inLocks 回、入口の確かめ $inProbe 回、" +
                    "一番上: ${tops.entries.sortedByDescending { it.value.get() }.take(8).joinToString { "${it.key}=${it.value.get()}" }}、" +
                    "echo ${echoCalls.get()} 回、pid ${pidCalls.get()} 回、譲った ${h.scheduler.fuelYields.get()} 回、" +
                    "スレッドを持って燃料を待った ${h.scheduler.fuelBlockingWaits.get()} 回、前借り ${h.scheduler.fuelOverdrafts.get()} 回",
            )
        }
        assertEquals(0, inLocks, "スタックが JDK のロックの中で尽きた")
    }

    private fun soe(rt: ProcessRuntime): StackOverflowError? {
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        return t as StackOverflowError?
    }

    private fun state(h: Harness, m: Machine, rt: ProcessRuntime): String =
        "CPU ${m.cpu.currentOwner?.pid} 待ち ${m.cpu.waiting}、許可 ${h.scheduler.permits.availablePermits()} 待ち ${h.scheduler.permits.waiting}、" +
            "lock ${m.lock}、SOE: ${soe(rt)?.stackTrace?.take(8)?.joinToString(" <- ")}"

    @Test
    fun `BLOCKINGのコンポーネントでcanonのliftとlowerの中からロックを取る深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.BLOCKING, rounds = 60)

    @Test
    fun `WAIT_POINTのコンポーネントでcanonのliftとlowerの中からロックを取る深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 60)
}
