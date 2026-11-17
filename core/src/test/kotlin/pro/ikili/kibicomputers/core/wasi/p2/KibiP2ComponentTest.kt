package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.VOID
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.spawnRt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilInitStarted
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `kibi:*` の p2 を、実際のコンポーネント（WAT のコアモジュール + WIT の world から作る）から Canonical ABI の lift・lower を通して呼ぶ
 * テスト（#685。ADR 0685）。[KibiP2BehaviourTest] はホスト関数の本体を手で組んだ値で呼ぶので、WIT の型と値の形が合っているか
 * （`option<tuple<borrow<…>, s64>>`・variant の中の borrow・`list<handle-assign>`・own の返し・ゲストからの `resource.drop`）は、ここで確かめる
 */
class KibiP2ComponentTest {
    private val limit = Duration.ofSeconds(180)

    /** `cabi_realloc`（増えるだけの割り当て）と、1 ページのメモリ。ホストが返す list・string の置き場になる */
    private val realloc = """
        (memory (export "memory") 1)
        (global ${'$'}bump (mut i32) (i32.const 2048))
        (func (export "cabi_realloc") (param ${'$'}old i32) (param ${'$'}oldsize i32) (param ${'$'}align i32) (param ${'$'}new i32) (result i32)
          (local ${'$'}p i32)
          (local.set ${'$'}p (i32.and (i32.add (global.get ${'$'}bump) (i32.sub (local.get ${'$'}align) (i32.const 1))) (i32.sub (i32.const 0) (local.get ${'$'}align))))
          (global.set ${'$'}bump (i32.add (local.get ${'$'}p) (local.get ${'$'}new)))
          (local.get ${'$'}p))
    """.trimIndent()

    private fun load(bytes: ByteArray): Program =
        Program.component(ComponentProgram.load(LinkTesting.runtime, bytes, KernelComponentHost))

    private fun writeFile(m: Machine, path: String, bytes: ByteArray) {
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
    }

    private fun machine(h: Harness): Machine {
        val m = h.machine(fuelPerTick = 10_000_000)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        return m
    }

    /**
     * コンポーネント [bytes] の `run: func() -> u32` を、ブロッキング型のプロセスの中で呼ぶ。戻り値（0 なら成功。0 でなければ WAT の中で
     * 失敗した段の番号）を返す。[after] は呼んだ後に同じプロセスの中で確かめる（ホストの表が空に戻ったことなど）
     */
    private fun runGuest(h: Harness, m: Machine, bytes: ByteArray, after: (pro.ikili.kibicomputers.core.kernel.process.ProcessContext) -> Unit = {}): Int {
        val result = AtomicReference<Any>()
        val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
            try {
                result.set((ctx.component!!.call("run") as CanonValue.I32).value)
                after(ctx)
            } catch (t: Throwable) {
                result.set(t)
            }
            StepResult.Exit(0)
        }
        val pid = m.spawn(load(bytes), driver, SpawnOptions(fds = SpawnOptions.ROOT_PREOPEN), 0)
        eventually(120_000, "終わらない") {
            h.scheduler.tick()
            m.stateOf(pid).isTerminal
        }
        (result.get() as? Throwable)?.let { throw it }
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
        return result.get() as Int
    }

    @Test
    fun `ゲストがイベントキュー・タイマー・pollableをCanonical-ABIで呼べる`() = timeout(limit) {
        val s = "\$"
        val wat = """
            (module
              (import "kibi:kernel/events@0.1.0" "[static]event-queue.open" (func ${s}qopen (param i32 i32 i32 i32 i32)))
              (import "kibi:kernel/events@0.1.0" "[method]event-queue.take" (func ${s}take (param i32 i32 i32)))
              (import "kibi:kernel/events@0.1.0" "[resource-drop]event-queue" (func ${s}qdrop (param i32)))
              (import "kibi:kernel/timers@0.1.0" "[static]timer.create" (func ${s}tcreate (param i32 i32 i64 i32)))
              (import "kibi:kernel/timers@0.1.0" "[method]timer.set-after" (func ${s}tset (param i32 i64 i64 i32)))
              (import "kibi:kernel/timers@0.1.0" "[method]timer.read" (func ${s}tread (param i32 i32)))
              (import "kibi:kernel/timers@0.1.0" "[resource-drop]timer" (func ${s}tdrop (param i32)))
              (import "kibi:kernel/events-poll@0.1.0" "subscribe" (func ${s}qsub (param i32) (result i32)))
              (import "kibi:kernel/timers-poll@0.1.0" "subscribe" (func ${s}tsub (param i32) (result i32)))
              (import "wasi:io/poll@0.2.12" "poll" (func ${s}poll (param i32 i32 i32)))
              (import "wasi:io/poll@0.2.12" "[method]pollable.ready" (func ${s}ready (param i32) (result i32)))
              (import "wasi:io/poll@0.2.12" "[resource-drop]pollable" (func ${s}pdrop (param i32)))
              $realloc
              (data (i32.const 16) "timer")
              (data (i32.const 32) "\10\00\00\00\05\00\00\00")
              (func (export "run") (result i32)
                (local ${s}q i32) (local ${s}ta i32) (local ${s}tb i32) (local ${s}pa i32) (local ${s}pb i32) (local ${s}pq i32)
                ;; キューを開く: kinds = ["timer"]、capacity = none。result<event-queue, error-code> は 64 に（判別子 +0、ハンドル +4）
                (call ${s}qopen (i32.const 32) (i32.const 1) (i32.const 0) (i32.const 0) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 1))))
                (local.set ${s}q (i32.load (i32.const 68)))
                ;; タイマー A（キューなし）・B（キューあり。option<tuple<borrow<event-queue>, s64>> = some((q, 7)))
                (call ${s}tcreate (i32.const 0) (i32.const 0) (i64.const 0) (i32.const 96))
                (if (i32.load8_u (i32.const 96)) (then (return (i32.const 2))))
                (local.set ${s}ta (i32.load (i32.const 100)))
                (call ${s}tcreate (i32.const 1) (local.get ${s}q) (i64.const 7) (i32.const 96))
                (if (i32.load8_u (i32.const 96)) (then (return (i32.const 3))))
                (local.set ${s}tb (i32.load (i32.const 100)))
                (local.set ${s}pa (call ${s}tsub (local.get ${s}ta)))
                (local.set ${s}pb (call ${s}tsub (local.get ${s}tb)))
                (local.set ${s}pq (call ${s}qsub (local.get ${s}q)))
                ;; 動かす前は、どれもそろっていない
                (if (call ${s}ready (local.get ${s}pa)) (then (return (i32.const 4))))
                (call ${s}tset (local.get ${s}ta) (i64.const 1000000) (i64.const 0) (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (return (i32.const 5))))
                (call ${s}tset (local.get ${s}tb) (i64.const 1000000) (i64.const 0) (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (return (i32.const 6))))
                ;; キューなしのタイマーの pollable はそろう（list<u32> は 160 に。ptr +0、len +4）
                (i32.store (i32.const 256) (local.get ${s}pa))
                (call ${s}poll (i32.const 256) (i32.const 1) (i32.const 160))
                (if (i32.ne (i32.load (i32.const 164)) (i32.const 1)) (then (return (i32.const 7))))
                (if (i32.eqz (call ${s}ready (local.get ${s}pa))) (then (return (i32.const 8))))
                ;; キューの pollable もそろう（B の "timer" のイベントが届く）
                (i32.store (i32.const 256) (local.get ${s}pq))
                (call ${s}poll (i32.const 256) (i32.const 1) (i32.const 160))
                (if (i32.ne (i32.load (i32.const 164)) (i32.const 1)) (then (return (i32.const 9))))
                ;; キューに送るタイマーの pollable は、鳴った後もそろわない
                (if (call ${s}ready (local.get ${s}pb)) (then (return (i32.const 10))))
                ;; take: result<event-batch, error-code>（events の ptr +8、len +12）。1 件、kind は "timer"（5 バイト）
                (call ${s}take (local.get ${s}q) (i32.const 8) (i32.const 192))
                (if (i32.load8_u (i32.const 192)) (then (return (i32.const 11))))
                (if (i32.ne (i32.load (i32.const 204)) (i32.const 1)) (then (return (i32.const 12))))
                (if (i32.ne (i32.load offset=4 (i32.load (i32.const 200))) (i32.const 5)) (then (return (i32.const 13))))
                ;; A の read: result<u64, error-code>（値は +8）。1 回以上
                (call ${s}tread (local.get ${s}ta) (i32.const 224))
                (if (i32.load8_u (i32.const 224)) (then (return (i32.const 14))))
                (if (i64.lt_u (i64.load (i32.const 232)) (i64.const 1)) (then (return (i32.const 15))))
                ;; 子の pollable・タイマー・キューの順に drop
                (call ${s}pdrop (local.get ${s}pa))
                (call ${s}pdrop (local.get ${s}pb))
                (call ${s}pdrop (local.get ${s}pq))
                (call ${s}tdrop (local.get ${s}ta))
                (call ${s}tdrop (local.get ${s}tb))
                (call ${s}qdrop (local.get ${s}q))
                (i32.const 0)))
        """.trimIndent()
        val bytes = KibiP2WitTool.component(
            """
            import kibi:kernel/events@0.1.0;
            import kibi:kernel/timers@0.1.0;
            import kibi:kernel/events-poll@0.1.0;
            import kibi:kernel/timers-poll@0.1.0;
            import wasi:io/poll@0.2.12;
            export run: func() -> u32;
            """.trimIndent(),
            wat,
        )
        Harness().use { h ->
            val m = machine(h)
            val step = runGuest(h, m, bytes) { ctx ->
                assertEquals(0, ctx.withKernelLock { P2.tableLocked(ctx).size }, "ホストの resource が残った")
            }
            assertEquals(0, step, "WAT の段 $step で失敗した")
            m.stop()
        }
    }

    @Test
    fun `ゲストがspawn-with-handlesでパイプを渡し、子の終わりを待ち、drop--resource-dropで子が刈り取られる`() = timeout(limit) {
        val s = "\$"
        val wat = """
            (module
              (import "kibi:io/pipe-streams@0.1.0" "create" (func ${s}pipe (param i32)))
              (import "kibi:process/process-poll@0.1.0" "spawn-with-handles" (func ${s}spawnh (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process-poll@0.1.0" "subscribe" (func ${s}psub (param i32) (result i32)))
              (import "kibi:process/process@0.1.0" "[static]process.spawn" (func ${s}spawn (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[method]process.status" (func ${s}status (param i32 i32)))
              (import "kibi:process/process@0.1.0" "[resource-drop]process" (func ${s}procdrop (param i32)))
              (import "wasi:io/poll@0.2.12" "poll" (func ${s}poll (param i32 i32 i32)))
              (import "wasi:io/poll@0.2.12" "[resource-drop]pollable" (func ${s}pdrop (param i32)))
              (import "wasi:io/streams@0.2.12" "[method]input-stream.blocking-read" (func ${s}read (param i32 i64 i32)))
              (import "wasi:io/streams@0.2.12" "[resource-drop]input-stream" (func ${s}idrop (param i32)))
              (import "wasi:io/streams@0.2.12" "[resource-drop]output-stream" (func ${s}odrop (param i32)))
              $realloc
              (data (i32.const 16) "/bin/hello")
              (data (i32.const 32) "/bin/ok")
              (func (export "run") (result i32)
                (local ${s}in i32) (local ${s}out i32) (local ${s}c i32) (local ${s}p i32) (local ${s}total i32)
                ;; パイプ: result<tuple<input-stream, output-stream>, error-code>（input +4、output +8）
                (call ${s}pipe (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 1))))
                (local.set ${s}in (i32.load (i32.const 68)))
                (local.set ${s}out (i32.load (i32.const 72)))
                ;; handles = [{ fd: 1, source: output(borrow out) }]（record handle-assign: fd +0、variant の判別子 +4、ハンドル +8。output は 1）
                (i32.store (i32.const 48) (i32.const 1))
                (i32.store8 (i32.const 52) (i32.const 1))
                (i32.store (i32.const 56) (local.get ${s}out))
                (call ${s}spawnh
                  (i32.const 16) (i32.const 10)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 48) (i32.const 1)
                  (i32.const 96))
                (if (i32.load8_u (i32.const 96)) (then (return (i32.const 2))))
                (local.set ${s}c (i32.load (i32.const 100)))
                ;; 書く端を捨てると、子が終わった後の読みは closed になる
                (call ${s}odrop (local.get ${s}out))
                (loop ${s}more
                  (call ${s}read (local.get ${s}in) (i64.const 256) (i32.const 128))
                  (if (i32.eqz (i32.load8_u (i32.const 128)))
                    (then
                      (local.set ${s}total (i32.add (local.get ${s}total) (i32.load (i32.const 136))))
                      (br ${s}more))))
                (if (i32.ne (local.get ${s}total) (i32.const 16)) (then (return (i32.const 3))))
                (call ${s}idrop (local.get ${s}in))
                ;; 子の終わりを pollable で待ち、status を読む:
                ;; result<option<exit-status>, error-code>（option の判別子 +4、exit-status の判別子 +8、終了コード +12）
                (local.set ${s}p (call ${s}psub (local.get ${s}c)))
                (i32.store (i32.const 256) (local.get ${s}p))
                (call ${s}poll (i32.const 256) (i32.const 1) (i32.const 160))
                (if (i32.ne (i32.load (i32.const 164)) (i32.const 1)) (then (return (i32.const 4))))
                (call ${s}status (local.get ${s}c) (i32.const 192))
                (if (i32.load8_u (i32.const 192)) (then (return (i32.const 5))))
                (if (i32.ne (i32.load8_u (i32.const 196)) (i32.const 1)) (then (return (i32.const 6))))
                (if (i32.load8_u (i32.const 200)) (then (return (i32.const 7))))
                (if (i32.load (i32.const 204)) (then (return (i32.const 8))))
                (call ${s}pdrop (local.get ${s}p))
                (call ${s}procdrop (local.get ${s}c))
                ;; 2 つ目の子は status を読まずに、ハンドルを drop して刈り取らせる（options はすべて空）
                (call ${s}spawn
                  (i32.const 32) (i32.const 7)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 96))
                (if (i32.load8_u (i32.const 96)) (then (return (i32.const 9))))
                (local.set ${s}c (i32.load (i32.const 100)))
                (local.set ${s}p (call ${s}psub (local.get ${s}c)))
                (i32.store (i32.const 256) (local.get ${s}p))
                (call ${s}poll (i32.const 256) (i32.const 1) (i32.const 160))
                (call ${s}pdrop (local.get ${s}p))
                (call ${s}procdrop (local.get ${s}c))
                (i32.const 0)))
        """.trimIndent()
        val bytes = KibiP2WitTool.component(
            """
            import kibi:io/pipe-streams@0.1.0;
            import kibi:process/process@0.1.0;
            import kibi:process/process-poll@0.1.0;
            import wasi:io/poll@0.2.12;
            import wasi:io/streams@0.2.12;
            export run: func() -> u32;
            """.trimIndent(),
            wat,
        )
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            writeFile(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            writeFile(m, "/bin/hello", P2Testing.wrap("hello-wasi"))
            val step = runGuest(h, m, bytes) { ctx ->
                assertEquals(0, ctx.withKernelLock { P2.tableLocked(ctx).size }, "ホストの resource が残った")
                // 1 つ目は status で、2 つ目は resource.drop（P2.drop → processHandleClosed）で刈り取られている
                assertEquals(emptyList<Int>(), ctx.children(), "子が刈り取られていない")
            }
            assertEquals(0, step, "WAT の段 $step で失敗した")
            m.stop()
        }
    }

    /** `shutdown` を呼んで戻らない `wasi:cli/run` のコマンド */
    private val shutdownCommand: ByteArray by lazy {
        KibiP2WitTool.component(
            """
            import kibi:computer/power@0.1.0;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            """
            (module
              (import "kibi:computer/power@0.1.0" "shutdown" (func ${'$'}shutdown))
              (memory (export "memory") 1)
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (call ${'$'}shutdown)
                ;; 戻ってはいけない（戻ったら err で終わる）
                (i32.const 1)))
            """.trimIndent(),
        )
    }

    /** `_start` が終わらない init */
    private fun foreverInit(): FakeModule = FakeModule.builder()
        .fuelMetered()
        .export("_start", VOID) { ctx, _ ->
            while (true) {
                ctx.step(1)
                Thread.sleep(1)
            }
            @Suppress("UNREACHABLE_CODE")
            LongArray(0)
        }
        .build()

    @Test
    fun `power-shutdownはProcessKilledExceptionをトラップに書き換えず、プロセスはKILLEDでマシンが止まる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val init = "fake-init".toByteArray()
            h.runtime.define(init, foreverInit())
            val disk = TestMemoryFileSystem()
            disk.createDirectory(VfsPath.parse("/sbin"))
            val node = disk.createFile(VfsPath.parse(BootConfig.INIT_PATH))
            disk.write(node, 0, init, 0, init.size)
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "d") BootVolume(disk, readOnly = false) else null }
            assertTrue(m.boot(BootConfig.builder().bootDisks(listOf("d")).build()))
            h.tickUntilInitStarted(m, limit.toMillis())

            val program = load(shutdownCommand)
            val rt = m.spawnRt(program, WasiP2Driver.create(program.component!!.plan), SpawnOptions(), parentPid = 1)
            eventually(limit.toMillis(), "止まらない") {
                h.scheduler.tick()
                m.state == MachineState.STOPPED
            }
            // 呼んだプロセスは CRASHED でなく KILLED。例外はトラップに書き換わらない
            eventually(30_000, "プロセスが終わらない") {
                h.scheduler.tick()
                rt.process.state.isTerminal
            }
            assertEquals(ProcessState.KILLED, rt.process.state, "${rt.lastError}")
            assertFalse(rt.lastError is WasmTrapException, "${rt.lastError}")
            assertTrue(m.awaitTermination(10_000))
            m.stop()
        }
    }
}
