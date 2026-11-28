package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.DiskSnapshot
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.ResolvedDisk
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.VOID
import pro.ikili.kibicomputers.core.kernel.awaitTerminationOrFail
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.spawnRt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilInitStarted
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.tty.TtyMode
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * `kibi:*` の p2 を、実際のコンポーネント（WAT のコアモジュール + WIT の world から作る）から Canonical ABI の lift・lower を通して呼ぶ
 * テスト（#685。ADR 0685）。[KibiP2BehaviourTest] はホスト関数の本体を手で組んだ値で呼ぶので、WIT の型と値の形が合っているか
 * （`option<tuple<borrow<…>, s64>>`・variant の中の borrow・`list<handle-assign>`・own の返し・ゲストからの `resource.drop`、
 * enum（`signal`）・flags（`mode`）・bool の引数と戻り値、デバイスの `list<value>` の引数と戻り値、`list-disks` の `list<disk-info>`）は、
 * ここで確かめる（#794）。往復だけでは lift と lower の対称な取り違えを見逃すので、ホスト側の状態や、受け取ったものと違う値と照らし合わせる
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

    private fun machine(h: Harness, environment: MachineEnvironment = MachineEnvironment.DEFAULT): Machine {
        val m = h.machine(fuelPerTick = 10_000_000, environment = environment)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        return m
    }

    /**
     * コンポーネント [bytes] の `run: func() -> u32` を、ブロッキング型のプロセスの中で呼ぶ。戻り値（0 なら成功。0 でなければ WAT の中で
     * 失敗した段の番号）を返す。[after] は呼んだ後に同じプロセスの中で確かめる（ホストの表が空に戻ったことなど）。
     * [stdout] はプロセスの fd 1 に置くカーネルのオブジェクト（なければ親なしの既定 [StdioSpec.INHERIT]）、[entry] は呼ぶ export の名前
     */
    private fun runGuest(
        h: Harness,
        m: Machine,
        bytes: ByteArray,
        stdout: Int? = null,
        entry: String = "run",
        after: (ProcessContext) -> Unit = {},
    ): Int {
        val result = AtomicReference<Any>()
        val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
            try {
                result.set((ctx.component!!.call(entry) as CanonValue.I32).value)
                after(ctx)
            } catch (t: Throwable) {
                result.set(t)
            }
            StepResult.Exit(0)
        }
        val stdio = stdout?.let { StdioSpec(StdioSource.Closed, StdioSource.Object(it, HandleRights.WRITE), StdioSource.Closed) } ?: StdioSpec.INHERIT
        val options = SpawnOptions(stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN)
        val pid = m.spawn(load(bytes), driver, options, 0)
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

    @Test
    fun `enum・flags・boolの引数をCanonical-ABIでliftし、exit-statusのkilledのsignalをlowerする`() = timeout(limit) {
        val s = "\$"
        val inval = KibiErrors.code(Errno.INVAL)
        val wat = """
            (module
              (import "kibi:io/tty@0.1.0" "is-tty" (func ${s}istty (param i32) (result i32)))
              (import "kibi:io/tty@0.1.0" "get-mode" (func ${s}getmode (param i32 i32)))
              (import "kibi:io/tty@0.1.0" "set-mode" (func ${s}setmode (param i32 i32 i32)))
              (import "kibi:io/pipe-streams@0.1.0" "create" (func ${s}pipe (param i32)))
              (import "kibi:process/process@0.1.0" "self-pid" (func ${s}self (result i32)))
              (import "kibi:process/process@0.1.0" "kill-pid" (func ${s}killpid (param i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "set-signal-ignored" (func ${s}ignore (param i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[method]process.kill" (func ${s}kill (param i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[method]process.status" (func ${s}status (param i32 i32)))
              (import "kibi:process/process@0.1.0" "[resource-drop]process" (func ${s}procdrop (param i32)))
              (import "kibi:process/process-poll@0.1.0" "spawn-with-handles" (func ${s}spawnh (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process-poll@0.1.0" "subscribe" (func ${s}psub (param i32) (result i32)))
              (import "wasi:io/poll@0.2.12" "poll" (func ${s}poll (param i32 i32 i32)))
              (import "wasi:io/poll@0.2.12" "[resource-drop]pollable" (func ${s}pdrop (param i32)))
              (import "wasi:io/streams@0.2.12" "[resource-drop]input-stream" (func ${s}idrop (param i32)))
              (import "wasi:io/streams@0.2.12" "[resource-drop]output-stream" (func ${s}odrop (param i32)))
              $realloc
              (data (i32.const 16) "/bin/prompt")
              (func (export "run") (result i32)
                (local ${s}in i32) (local ${s}out i32) (local ${s}c i32) (local ${s}p i32)
                ;; bool の戻り値: fd 1 は TTY、開いていない fd 9 は TTY でない
                (if (i32.ne (call ${s}istty (i32.const 1)) (i32.const 1)) (then (return (i32.const 1))))
                (if (call ${s}istty (i32.const 9)) (then (return (i32.const 2))))
                ;; flags の戻り値: result<mode, error-code>（判別子 +0、mode +1）。既定は全部オン（5 ビット）
                (call ${s}getmode (i32.const 1) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 3))))
                (if (i32.ne (i32.load8_u (i32.const 65)) (i32.const 31)) (then (return (i32.const 4))))
                ;; flags の引数: echo だけ（2）にして読み直す
                (call ${s}setmode (i32.const 1) (i32.const 2) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 5))))
                (call ${s}getmode (i32.const 1) (i32.const 64))
                (if (i32.ne (i32.load8_u (i32.const 65)) (i32.const 2)) (then (return (i32.const 6))))
                ;; 知らないビットは捨てる: echo と nl-to-crnl（0x12）に上位のビットを足して渡す。ホストの TTY は after で確かめる
                (call ${s}setmode (i32.const 1) (i32.const 0xffffff12) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 7))))
                (call ${s}getmode (i32.const 1) (i32.const 64))
                (if (i32.ne (i32.load8_u (i32.const 65)) (i32.const 0x12)) (then (return (i32.const 8))))

                ;; enum と bool の引数: quit を無視する（bool は 0 以外が true なので 2 を渡す）。interrupt は無視にしてから戻す
                (call ${s}ignore (i32.const 1) (i32.const 2) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 9))))
                (call ${s}ignore (i32.const 0) (i32.const 1) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 10))))
                (call ${s}ignore (i32.const 0) (i32.const 0) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 11))))
                ;; kill は無視できない: result<_, error-code> の err（判別子 +0 が 1、error-code +1 が invalid）
                (call ${s}ignore (i32.const 3) (i32.const 1) (i32.const 64))
                (if (i32.ne (i32.load8_u (i32.const 64)) (i32.const 1)) (then (return (i32.const 12))))
                (if (i32.ne (i32.load8_u (i32.const 65)) (i32.const $inval)) (then (return (i32.const 13))))
                ;; terminate を無視してから自分に terminate を送る。enum の lift が別の signal に取り違えると、ここで自分が止まる
                (call ${s}ignore (i32.const 2) (i32.const 1) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 14))))
                (call ${s}killpid (call ${s}self) (i32.const 2) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 15))))
                (call ${s}ignore (i32.const 2) (i32.const 0) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 16))))

                ;; 子（標準入力のパイプを待ち続ける）を terminate で止め、status の killed(some(terminate)) を読む
                (call ${s}pipe (i32.const 96))
                (if (i32.load8_u (i32.const 96)) (then (return (i32.const 17))))
                (local.set ${s}in (i32.load (i32.const 100)))
                (local.set ${s}out (i32.load (i32.const 104)))
                ;; handles = [{ fd: 0, source: input(borrow in) }]（fd +0、判別子 +4（input は 0）、ハンドル +8）
                (i32.store (i32.const 48) (i32.const 0))
                (i32.store8 (i32.const 52) (i32.const 0))
                (i32.store (i32.const 56) (local.get ${s}in))
                (call ${s}spawnh
                  (i32.const 16) (i32.const 11)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 48) (i32.const 1)
                  (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (return (i32.const 18))))
                (local.set ${s}c (i32.load (i32.const 132)))
                (call ${s}kill (local.get ${s}c) (i32.const 2) (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (return (i32.const 19))))
                (local.set ${s}p (call ${s}psub (local.get ${s}c)))
                (i32.store (i32.const 256) (local.get ${s}p))
                (call ${s}poll (i32.const 256) (i32.const 1) (i32.const 160))
                (if (i32.ne (i32.load (i32.const 164)) (i32.const 1)) (then (return (i32.const 20))))
                ;; result<option<exit-status>, error-code>: option の判別子 +4、exit-status の判別子 +8（killed は 1）、
                ;; option<signal> の判別子 +12、signal +13（terminate は 2）
                (call ${s}status (local.get ${s}c) (i32.const 192))
                (if (i32.load8_u (i32.const 192)) (then (return (i32.const 21))))
                (if (i32.ne (i32.load8_u (i32.const 196)) (i32.const 1)) (then (return (i32.const 22))))
                (if (i32.ne (i32.load8_u (i32.const 200)) (i32.const 1)) (then (return (i32.const 23))))
                (if (i32.ne (i32.load8_u (i32.const 204)) (i32.const 1)) (then (return (i32.const 24))))
                (if (i32.ne (i32.load8_u (i32.const 205)) (i32.const 2)) (then (return (i32.const 25))))
                (call ${s}pdrop (local.get ${s}p))
                (call ${s}procdrop (local.get ${s}c))
                (call ${s}idrop (local.get ${s}in))
                (call ${s}odrop (local.get ${s}out))
                (i32.const 0))
              ;; 範囲の外の signal（4）は lift の時点でトラップになる（p1 は invalid を返す。ADR 0685 の既知の違い）
              (func (export "bad-signal") (result i32)
                (call ${s}killpid (call ${s}self) (i32.const 4) (i32.const 64))
                (i32.const 0)))
        """.trimIndent()
        val bytes = KibiP2WitTool.component(
            """
            import kibi:io/tty@0.1.0;
            import kibi:io/pipe-streams@0.1.0;
            import kibi:process/process@0.1.0;
            import kibi:process/process-poll@0.1.0;
            import wasi:io/poll@0.2.12;
            import wasi:io/streams@0.2.12;
            export run: func() -> u32;
            export bad-signal: func() -> u32;
            """.trimIndent(),
            wat,
        )
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            writeFile(m, "/bin/prompt", P2Testing.wrap("prompt-wasi"))
            val tty = Tty(64, 64)
            val ttyId = m.lock.withLockValue { m.kernel.objects.add(tty) }
            val step = runGuest(h, m, bytes, stdout = ttyId) { ctx ->
                ctx.withKernelLock {
                    assertEquals(0, P2.tableLocked(ctx).size, "ホストの resource が残った")
                    // 往復だけでは対称な取り違え（ビットの順・判別子の順）を見逃すので、ホスト側の状態と照らし合わせる
                    assertEquals(TtyMode.ECHO or TtyMode.NL_TO_CRNL, tty.mode.bits)
                    assertEquals(setOf(KillSignal.QUIT), ctx.kernel.processes[ctx.pid]!!.ignoredSignals)
                }
                assertEquals(emptyList<Int>(), ctx.children(), "子が刈り取られていない")
            }
            assertEquals(0, step, "WAT の段 $step で失敗した")
            assertThrows<WasmTrapException> { runGuest(h, m, bytes, entry = "bad-signal") }
            m.stop()
        }
    }

    private class TestDevice(override val methods: DeviceMethods) : Device {
        override val kind: String = "test:device"
    }

    @Test
    fun `デバイスのinvokeでlist-valueの全caseをliftし、戻り値の全caseをlowerする`() = timeout(limit) {
        val s = "\$"
        val address = "00000000-0000-4000-8000-0000000000e1"
        val received = AtomicReference<List<DeviceValue>>()
        val wat = """
            (module
              (import "kibi:device/bus@0.1.0" "[static]device.open" (func ${s}open (param i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[method]device.invoke" (func ${s}invoke (param i32 i32 i32 i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[resource-drop]device" (func ${s}ddrop (param i32)))
              $realloc
              (data (i32.const 16) "$address")
              (data (i32.const 56) "xy")
              (data (i32.const 64) "\e3\81\82")
              (data (i32.const 72) "\00\80\ff")
              (func (export "run") (result i32)
                (local ${s}d i32) (local ${s}l i32) (local ${s}t i32)
                ;; result<device, call-error>（判別子 +0、ハンドル +4）
                (call ${s}open (i32.const 16) (i32.const 36) (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (return (i32.const 1))))
                (local.set ${s}d (i32.load (i32.const 132)))
                ;; 引数の list<value>（1 つ 16 バイト・align 8。判別子 +0、中身 +8）を 512 に 6 つ並べる
                (i32.store8 (i32.const 512) (i32.const 0))
                (i32.store8 (i32.const 528) (i32.const 1))
                (i64.store (i32.const 536) (i64.const -0x7ffffffffffffff0))
                (i32.store8 (i32.const 544) (i32.const 2))
                (f64.store (i32.const 552) (f64.const 1.5))
                ;; bool は 0 以外が true なので 2 を書く
                (i32.store8 (i32.const 560) (i32.const 3))
                (i32.store8 (i32.const 568) (i32.const 2))
                ;; text は非 ASCII（「あ」の 3 バイト）
                (i32.store8 (i32.const 576) (i32.const 4))
                (i32.store (i32.const 584) (i32.const 64))
                (i32.store (i32.const 588) (i32.const 3))
                ;; bytes は 0x80 以上を含む
                (i32.store8 (i32.const 592) (i32.const 5))
                (i32.store (i32.const 600) (i32.const 72))
                (i32.store (i32.const 604) (i32.const 3))
                ;; result<list<value>, call-error>（判別子 +0、list の ptr +4・len +8）
                (call ${s}invoke (local.get ${s}d) (i32.const 56) (i32.const 1) (i32.const 512) (i32.const 6) (i32.const 160))
                (if (i32.load8_u (i32.const 160)) (then (return (i32.const 2))))
                (if (i32.ne (i32.load (i32.const 168)) (i32.const 6)) (then (return (i32.const 3))))
                (local.set ${s}l (i32.load (i32.const 164)))
                (if (i32.and (local.get ${s}l) (i32.const 7)) (then (return (i32.const 4))))
                ;; none
                (if (i32.load8_u (local.get ${s}l)) (then (return (i32.const 5))))
                ;; integer
                (if (i32.ne (i32.load8_u offset=16 (local.get ${s}l)) (i32.const 1)) (then (return (i32.const 6))))
                (if (i64.ne (i64.load offset=24 (local.get ${s}l)) (i64.const -0x123456789abcdef0)) (then (return (i32.const 7))))
                ;; float
                (if (i32.ne (i32.load8_u offset=32 (local.get ${s}l)) (i32.const 2)) (then (return (i32.const 8))))
                (if (f64.ne (f64.load offset=40 (local.get ${s}l)) (f64.const -2.25)) (then (return (i32.const 9))))
                ;; boolean
                (if (i32.ne (i32.load8_u offset=48 (local.get ${s}l)) (i32.const 3)) (then (return (i32.const 10))))
                (if (i32.ne (i32.load8_u offset=56 (local.get ${s}l)) (i32.const 1)) (then (return (i32.const 11))))
                ;; text「いう」（6 バイト。E3 81 84 E3 81 86）
                (if (i32.ne (i32.load8_u offset=64 (local.get ${s}l)) (i32.const 4)) (then (return (i32.const 12))))
                (if (i32.ne (i32.load offset=76 (local.get ${s}l)) (i32.const 6)) (then (return (i32.const 13))))
                (local.set ${s}t (i32.load offset=72 (local.get ${s}l)))
                (if (i32.ne (i32.load8_u (local.get ${s}t)) (i32.const 0xe3)) (then (return (i32.const 14))))
                (if (i32.ne (i32.load8_u offset=2 (local.get ${s}t)) (i32.const 0x84)) (then (return (i32.const 15))))
                (if (i32.ne (i32.load8_u offset=5 (local.get ${s}t)) (i32.const 0x86)) (then (return (i32.const 16))))
                ;; bytes FE 01
                (if (i32.ne (i32.load8_u offset=80 (local.get ${s}l)) (i32.const 5)) (then (return (i32.const 17))))
                (if (i32.ne (i32.load offset=92 (local.get ${s}l)) (i32.const 2)) (then (return (i32.const 18))))
                (local.set ${s}t (i32.load offset=88 (local.get ${s}l)))
                (if (i32.ne (i32.load8_u (local.get ${s}t)) (i32.const 0xfe)) (then (return (i32.const 19))))
                (if (i32.ne (i32.load8_u offset=1 (local.get ${s}t)) (i32.const 0x01)) (then (return (i32.const 20))))
                ;; ないメソッド "y" は err の no-such-method（call-error の判別子 +4 が 2）
                (call ${s}invoke (local.get ${s}d) (i32.const 57) (i32.const 1) (i32.const 0) (i32.const 0) (i32.const 160))
                (if (i32.ne (i32.load8_u (i32.const 160)) (i32.const 1)) (then (return (i32.const 21))))
                (if (i32.ne (i32.load8_u (i32.const 164)) (i32.const 2)) (then (return (i32.const 22))))
                (call ${s}ddrop (local.get ${s}d))
                (i32.const 0)))
        """.trimIndent()
        val bytes = KibiP2WitTool.component(
            """
            import kibi:device/bus@0.1.0;
            export run: func() -> u32;
            """.trimIndent(),
            wat,
        )
        Harness().use { h ->
            val m = machine(h)
            m.devices.attach(
                address,
                TestDevice(
                    DeviceMethods.builder()
                        .direct("x") { _, args ->
                            received.set((0 until args.size()).map { args.get(it) })
                            // 受け取ったものとは別の値を返す（echo だと lift と lower の対称な取り違えを見逃す）
                            arrayOf(
                                DeviceValue.none(),
                                DeviceValue.of(-0x123456789abcdef0L),
                                DeviceValue.of(-2.25),
                                DeviceValue.of(true),
                                DeviceValue.of("いう"),
                                DeviceValue.of(byteArrayOf(0xfe.toByte(), 0x01)),
                            )
                        }
                        .build(),
                ),
            )
            val step = runGuest(h, m, bytes) { ctx ->
                assertEquals(0, ctx.withKernelLock { P2.tableLocked(ctx).size }, "ホストの resource が残った")
            }
            assertEquals(0, step, "WAT の段 $step で失敗した")
            val args = checkNotNull(received.get()) { "デバイスが呼ばれていない" }
            assertEquals(
                listOf(
                    DeviceValue.Type.NONE,
                    DeviceValue.Type.INTEGER,
                    DeviceValue.Type.FLOAT,
                    DeviceValue.Type.BOOLEAN,
                    DeviceValue.Type.TEXT,
                    DeviceValue.Type.BYTES,
                ),
                args.map { it.type },
            )
            assertEquals(-0x7ffffffffffffff0L, args[1].asLong())
            assertEquals(1.5, args[2].asDouble())
            assertEquals(true, args[3].asBoolean())
            assertEquals("あ", args[4].asText())
            assertArrayEquals(byteArrayOf(0x00, 0x80.toByte(), 0xff.toByte()), args[5].asBytes())
            m.stop()
        }
    }

    @Test
    fun `list-disksのdisk-info（u64・option-string）をCanonical-ABIでlowerする`(@TempDir dir: Path) = timeout(limit) {
        val s = "\$"
        val wat = """
            (module
              (import "kibi:fs/mounts@0.1.0" "list-disks" (func ${s}disks (param i32)))
              $realloc
              (func (export "run") (result i32)
                (local ${s}l i32) (local ${s}t i32)
                ;; list<disk-info>（ptr +0、len +4）。2 枚
                (call ${s}disks (i32.const 64))
                (if (i32.ne (i32.load (i32.const 68)) (i32.const 2)) (then (return (i32.const 1))))
                (local.set ${s}l (i32.load (i32.const 64)))
                (if (i32.and (local.get ${s}l) (i32.const 7)) (then (return (i32.const 2))))
                ;; disk-info は 32 バイト・align 8: source の ptr +0・len +4、capacity-bytes +8、max-entries +16、
                ;; mount-point の判別子 +20・文字列の ptr +24・len +28
                ;; 1 枚目: "disk:a"、2^20 バイト、64 項目、/mnt/x にマウント済み
                (if (i32.ne (i32.load offset=4 (local.get ${s}l)) (i32.const 6)) (then (return (i32.const 3))))
                (if (i32.ne (i32.load8_u offset=5 (i32.load (local.get ${s}l))) (i32.const 0x61)) (then (return (i32.const 4))))
                (if (i64.ne (i64.load offset=8 (local.get ${s}l)) (i64.const 0x100000)) (then (return (i32.const 5))))
                (if (i32.ne (i32.load offset=16 (local.get ${s}l)) (i32.const 64)) (then (return (i32.const 6))))
                (if (i32.ne (i32.load8_u offset=20 (local.get ${s}l)) (i32.const 1)) (then (return (i32.const 7))))
                (if (i32.ne (i32.load offset=28 (local.get ${s}l)) (i32.const 6)) (then (return (i32.const 8))))
                (local.set ${s}t (i32.load offset=24 (local.get ${s}l)))
                (if (i32.ne (i32.load8_u (local.get ${s}t)) (i32.const 0x2f)) (then (return (i32.const 9))))
                (if (i32.ne (i32.load8_u offset=5 (local.get ${s}t)) (i32.const 0x78)) (then (return (i32.const 10))))
                ;; 2 枚目: "disk:b"、2^32 + 5 バイト（上位の 32 ビットも読む）、100 項目、マウントしていない
                (if (i32.ne (i32.load offset=36 (local.get ${s}l)) (i32.const 6)) (then (return (i32.const 11))))
                (if (i32.ne (i32.load8_u offset=5 (i32.load offset=32 (local.get ${s}l))) (i32.const 0x62)) (then (return (i32.const 12))))
                (if (i64.ne (i64.load offset=40 (local.get ${s}l)) (i64.const 0x100000005)) (then (return (i32.const 13))))
                (if (i32.ne (i32.load offset=48 (local.get ${s}l)) (i32.const 100)) (then (return (i32.const 14))))
                (if (i32.load8_u offset=52 (local.get ${s}l)) (then (return (i32.const 15))))
                (i32.const 0)))
        """.trimIndent()
        val bytes = KibiP2WitTool.component(
            """
            import kibi:fs/mounts@0.1.0;
            export run: func() -> u32;
            """.trimIndent(),
            wat,
        )
        Harness().use { h ->
            val disks = DiskSnapshot.of(
                linkedMapOf(
                    "a" to ResolvedDisk(dir.resolve("a"), 1L shl 20, 64),
                    "b" to ResolvedDisk(dir.resolve("b"), (1L shl 32) + 5, 100),
                ),
            )
            val m = machine(h, MachineEnvironment.builder().disks(disks).build())
            // 0 件の一覧は lower の道（realloc・レコードの書き込み）を通らないので 2 枚挿し、1 枚は some の mount-point を持たせる。
            // mount-point はマウントの表の source から決まる（ディスクを開かずに済むよう、メモリの FS を "disk:a" としてマウントする）
            m.vfs.createDirectory(VfsPath.parse("/mnt"))
            m.vfs.createDirectory(VfsPath.parse("/mnt/x"))
            m.vfs.mount(VfsPath.parse("/mnt/x"), TestMemoryFileSystem(), readOnly = false, source = "disk:a")
            val step = runGuest(h, m, bytes) { ctx ->
                assertEquals(0, ctx.withKernelLock { P2.tableLocked(ctx).size }, "ホストの resource が残った")
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
            val rt = m.spawnRt(program, WasiP2Driver.create(program.component!!), SpawnOptions(), parentPid = 1)
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
            m.awaitTerminationOrFail()
            m.stop()
        }
    }
}
