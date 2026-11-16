package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.Pipes
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentCall
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * p2 の stream・pollable・exit の境界（#267。ADR 0267）。カーネルの中のコンポーネントのプロセスから、ホストの関数の本体を直接呼んで確かめる
 * （rep の扱いはゲストのハンドル表を通さない。表を通す形は [WasiP2ProgramTest] と multi-version のテストが見る）
 */
class WasiP2StreamTest {
    private val limit = Duration.ofSeconds(60)

    /** 中身のないコンポーネント（ホストの関数を直接呼ぶための入れ物） */
    private val empty: Program by lazy { Program.component(P2Testing.loadWat("(component (core module \$m) (core instance (instantiate \$m)))")) }

    /** ホストの関数 [iface]#[fn] を [args] で呼ぶ */
    private fun ProcessContext.p2(iface: String, fn: String, vararg args: CanonValue): CanonValue? {
        val f = checkNotNull(WasiP2Host.interfaces.getValue(iface).functions[fn]) { "$iface#$fn がない" }
        val env = object : KernelLinkEnv(this) {}
        return f.body.call(ComponentCall(component, env, iface, fn), args.toList())
    }

    private fun ProcessContext.drop(v: CanonValue) = P2.drop(this, (v as CanonValue.Handle).value)

    private val streams = "wasi:io/streams"

    private fun ok(v: CanonValue?): CanonValue? {
        val c = v as CanonValue.Case
        assertEquals(0, c.index, "err: $v")
        return c.payload
    }

    private fun isClosed(v: CanonValue?): Boolean {
        val c = v as CanonValue.Case
        return c.index == 1 && (c.payload as CanonValue.Case).index == 1
    }

    /** [body] をコンポーネントのプロセスの中で動かす。標準入力・出力はテストが持つパイプの端 */
    private fun scenario(h: Harness, m: Machine, stdin: Int, stdout: Int, body: (ProcessContext) -> Unit): Int {
        val failure = AtomicReference<Throwable>()
        val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
            try {
                body(ctx)
            } catch (e: WasiP2Exit) {
                throw e
            } catch (t: Throwable) {
                failure.set(t)
            }
            StepResult.Exit(0)
        }
        val options = SpawnOptions(
            stdio = StdioSpec(StdioSource.Object(stdin, HandleRights.READ), StdioSource.Object(stdout, HandleRights.WRITE), StdioSource.Closed),
            fds = SpawnOptions.ROOT_PREOPEN,
        )
        val pid = m.spawn(empty, driver, options, 0)
        eventually(30_000, "終わらない") {
            h.scheduler.tick()
            m.stateOf(pid).isTerminal
        }
        failure.get()?.let { throw it }
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
        return pid
    }

    private class PipePair(val readEnd: Int, val writeEnd: Int)

    private fun pipe(m: Machine, capacity: Int = 4096): PipePair = m.lock.withLockValue {
        val ids = Pipes.create(m.kernel.objects, capacity)
        PipePair(ids.read, ids.write)
    }

    @Test
    fun `check-writeの許可量・4096の上限・空のpoll・大きな長さはトラップか切り詰め`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val input = pipe(m)
            val output = pipe(m, capacity = 1024)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val out = ctx.p2("wasi:cli/stdout", "get-stdout")!!
                val allowed = (ok(ctx.p2(streams, "[method]output-stream.check-write", out)) as CanonValue.I64).value
                assertEquals(1024, allowed)
                assertThrows<WasmTrapException> { ctx.p2(streams, "[method]output-stream.write", out, CanonValue.Bytes(ByteArray(1025))) }
                ok(ctx.p2(streams, "[method]output-stream.write", out, CanonValue.Bytes("abc".toByteArray())))
                // 許可量は使った分だけ減る
                assertThrows<WasmTrapException> { ctx.p2(streams, "[method]output-stream.write", out, CanonValue.Bytes(ByteArray(1022))) }
                assertThrows<WasmTrapException> { ctx.p2(streams, "[method]output-stream.blocking-write-and-flush", out, CanonValue.Bytes(ByteArray(4097))) }
                assertThrows<WasmTrapException> { ctx.p2("wasi:io/poll", "poll", CanonValue.Items(emptyList())) }
                // 読めるものがない read は空、長さは上限で切る（先に確保しない）
                val inp = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                assertEquals(0, ((ok(ctx.p2(streams, "[method]input-stream.read", inp, CanonValue.I64(-1))) as CanonValue.Bytes).bytes.size))
                // 乱数は len どおり。上限を超えたらトラップ
                assertEquals(16, (ctx.p2("wasi:random/random", "get-random-bytes", CanonValue.I64(16)) as CanonValue.Bytes).bytes.size)
                assertThrows<WasmTrapException> { ctx.p2("wasi:random/random", "get-random-bytes", CanonValue.I64(-1)) }
                // 子の pollable が残る親の drop はトラップ。子を先に捨てれば捨てられる
                val p = ctx.p2(streams, "[method]input-stream.subscribe", inp)!!
                assertThrows<WasmTrapException> { ctx.drop(inp) }
                assertEquals(CanonValue.FALSE, ctx.p2("wasi:io/poll", "[method]pollable.ready", p))
                ctx.drop(p)
                ctx.drop(inp)
                ctx.drop(out)
                assertEquals(0, ctx.withKernelLock { P2.tableLocked(ctx).size })
            }
            m.stop()
        }
    }

    @Test
    fun `パイプのEOFはclosed、時計のpollableは期限でそろい、exit-with-codeは終了コード`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val input = pipe(m)
            val output = pipe(m)
            val reader = AtomicReference<String>()
            val pid = run {
                val failure = AtomicReference<Throwable>()
                val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                    try {
                        val inp = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                        // テストが書いて閉じるまで待って読む
                        val first = ok(ctx.p2(streams, "[method]input-stream.blocking-read", inp, CanonValue.I64(100))) as CanonValue.Bytes
                        reader.set(String(first.bytes))
                        assertTrue(isClosed(ctx.p2(streams, "[method]input-stream.blocking-read", inp, CanonValue.I64(100))))
                        // 時計: 期限のある pollable を poll で待つ。期限のないもの（遠い先）と並べて、そろった添字だけが返る
                        val far = ctx.p2("wasi:clocks/monotonic-clock", "subscribe-duration", CanonValue.I64(Long.MAX_VALUE))!!
                        val near = ctx.p2("wasi:clocks/monotonic-clock", "subscribe-duration", CanonValue.I64(5_000_000))!!
                        val r = ctx.p2("wasi:io/poll", "poll", CanonValue.Items(listOf(far, near))) as CanonValue.Items
                        assertEquals(listOf<CanonValue>(CanonValue.I32(1)), r.items)
                        assertEquals(CanonValue.TRUE, ctx.p2("wasi:io/poll", "[method]pollable.ready", near))
                        // 待った後に一時的なタイマーが残らない
                        assertTrue(ctx.withKernelLock { ctx.kernel.processes[ctx.pid]!!.waitTimers.isEmpty })
                        // 書く側: 読む側を閉じたら closed
                        val out = ctx.p2("wasi:cli/stdout", "get-stdout")!!
                        ok(ctx.p2(streams, "[method]output-stream.blocking-write-and-flush", out, CanonValue.Bytes("hi".toByteArray())))
                    } catch (t: Throwable) {
                        failure.set(t)
                        return@TestDriver StepResult.Exit(99)
                    }
                    ctx.p2("wasi:cli/exit", "exit-with-code", CanonValue.I32(7))
                    error("exit から戻った")
                }
                val options = SpawnOptions(
                    stdio = StdioSpec(StdioSource.Object(input.readEnd, HandleRights.READ), StdioSource.Object(output.writeEnd, HandleRights.WRITE), StdioSource.Closed),
                )
                val pid = m.spawn(empty, P2ExitDriver(driver), options, 0)
                eventually(10_000, "読みで待たない") {
                    h.scheduler.tick()
                    m.stateOf(pid).let { s -> check(!s.isTerminal) { "終わった: $s ${failure.get()} ${m.rt(pid).lastError?.stackTraceToString()?.take(1500)}" }; s == ProcessState.WAITING_EVENT }
                }
                m.lock.withLockValue {
                    val end = m.kernel.objects[input.writeEnd] as pro.ikili.kibicomputers.core.kernel.io.PipeEnd
                    (m.kernel.objects[end.streamId] as ByteStream).write("ping".toByteArray(), 0, 4, m.wakeSink)
                    m.kernel.objects.release(input.writeEnd, m.wakeSink)
                }
                eventually(30_000, "終わらない") {
                    h.scheduler.tick()
                    m.stateOf(pid).isTerminal
                }
                failure.get()?.let { throw it }
                pid
            }
            assertEquals("ping", reader.get())
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(7, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode })
            m.stop()
        }
    }

    /** テストのドライバーの中で投げた [WasiP2Exit] を、[WasiP2Driver] と同じく終了コードにする */
    private class P2ExitDriver(private val inner: TestDriver) : pro.ikili.kibicomputers.core.kernel.process.ProcessDriver by inner {
        override fun start(ctx: ProcessContext): StepResult = try {
            inner.start(ctx)
        } catch (e: WasiP2Exit) {
            StepResult.Exit(e.code)
        }
    }

    @Test
    fun `preopenとファイルのstreamとdescriptorの読み書き`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val f = m.vfs.open(VfsPath.parse("/a.txt"), OpenOptions(read = true, write = true, create = true))
            m.vfs.write(f, "abcde".toByteArray(), 0, 5)
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val dirs = ctx.p2("wasi:filesystem/preopens", "get-directories") as CanonValue.Items
                assertEquals(1, dirs.items.size)
                val (root, name) = (dirs.items[0] as CanonValue.Fields).fields
                assertEquals(CanonValue.str("/"), name)
                val t = WasiP2Host.wit.getValue("wasi:filesystem/types")
                val pf = t.type<CanonType.Flags>("path-flags").value()
                val of = t.type<CanonType.Flags>("open-flags").value()
                val df = t.type<CanonType.Flags>("descriptor-flags").value("read", "write")
                val ty = "wasi:filesystem/types"
                val file = ok(ctx.p2(ty, "[method]descriptor.open-at", root, pf, CanonValue.str("a.txt"), of, df))!!
                // 自分の位置で読む stream（位置 1 から）
                val s = ok(ctx.p2(ty, "[method]descriptor.read-via-stream", file, CanonValue.I64(1)))!!
                val got = ok(ctx.p2(streams, "[method]input-stream.read", s, CanonValue.I64(3))) as CanonValue.Bytes
                assertEquals("bcd", String(got.bytes))
                assertTrue(isClosed(ctx.p2(streams, "[method]input-stream.read", s, CanonValue.I64(0)).let {
                    ctx.p2(streams, "[method]input-stream.read", s, CanonValue.I64(10))
                    ctx.p2(streams, "[method]input-stream.read", s, CanonValue.I64(10))
                }))
                // descriptor.write・read（位置を渡す）
                assertEquals(CanonValue.I64(2), ok(ctx.p2(ty, "[method]descriptor.write", file, CanonValue.Bytes("XY".toByteArray()), CanonValue.I64(5))))
                val r = ok(ctx.p2(ty, "[method]descriptor.read", file, CanonValue.I64(100), CanonValue.I64(0))) as CanonValue.Fields
                assertEquals("abcdeXY", String((r.fields[0] as CanonValue.Bytes).bytes))
                // ないファイルは no-entry、外へ出るパスは not-permitted
                val err = { v: CanonValue? -> t.type<CanonType.Enum>("error-code").caseName((v as CanonValue.Case).also { assertEquals(1, it.index) }.payload!!) }
                assertEquals("no-entry", err(ctx.p2(ty, "[method]descriptor.open-at", root, pf, CanonValue.str("none"), of, df)))
                assertEquals("not-permitted", err(ctx.p2(ty, "[method]descriptor.stat-at", root, pf, CanonValue.str("../x"))))
                // ディレクトリの中身
                val ds = ok(ctx.p2(ty, "[method]descriptor.read-directory", root))!!
                val e1 = ok(ctx.p2(ty, "[method]directory-entry-stream.read-directory-entry", ds)) as CanonValue.Case
                assertEquals(1, e1.index)
                assertEquals(CanonValue.str("a.txt"), t.type<CanonType.Record>("directory-entry").field(e1.payload!!, "name"))
            }
            m.stop()
        }
    }

    @Test
    fun `写しの中で殺すと、p2のstreamとdescriptorの参照がオブジェクト表に残らない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val input = pipe(m)
            val output = pipe(m)
            val opened = AtomicReference<Int>()
            val failure0 = AtomicReference<Throwable>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    ctx.p2("wasi:cli/stdout", "get-stdout")
                    val dirs = ctx.p2("wasi:filesystem/preopens", "get-directories") as CanonValue.Items
                    val root = (dirs.items[0] as CanonValue.Fields).fields[0]
                    val t = WasiP2Host.wit.getValue("wasi:filesystem/types")
                    val file = ok(
                        ctx.p2(
                            "wasi:filesystem/types",
                            "[method]descriptor.open-at",
                            root,
                            t.type<CanonType.Flags>("path-flags").value(),
                            CanonValue.str("b.txt"),
                            t.type<CanonType.Flags>("open-flags").value("create"),
                            t.type<CanonType.Flags>("descriptor-flags").value("read", "write"),
                        ),
                    )!!
                    opened.set(ctx.withKernelLock { P2.entryLocked<DescriptorEntry>(ctx, file).objectId })
                    val inp = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                    // 入力を待ったまま（写しを取る間）
                    ctx.p2(streams, "[method]input-stream.blocking-read", inp, CanonValue.I64(1))
                } catch (t: Throwable) {
                    failure0.set(t)
                    throw t
                }
                StepResult.Exit(0)
            }
            val options = SpawnOptions(
                stdio = StdioSpec(StdioSource.Object(input.readEnd, HandleRights.READ), StdioSource.Object(output.writeEnd, HandleRights.WRITE), StdioSource.Closed),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            val pid = m.spawn(empty, driver, options, 0)
            eventually(10_000, "読みで待たない") {
                h.scheduler.tick()
                m.stateOf(pid).let { s -> check(!s.isTerminal) { "終わった: $s ${failure0.get()} ${m.rt(pid).lastError?.stackTraceToString()?.take(1500)}" }; s == ProcessState.WAITING_EVENT }
            }
            val fileId = opened.get()
            assertNotNull(fileId)
            val before = m.lock.withLockValue { m.kernel.processes[pid]!!.hostResources.size }
            assertTrue(before >= 4, "$before")
            val copy = m.captureCopy(CapturePolicy.KILL_TRANSIENT)!!
            assertTrue(pid in copy.killedPids)
            val killed = copy.processes[pid]!!
            assertEquals(0, killed.hostResources.size)
            assertNull(copy.objects[fileId], "写しの中で開いたファイルが残った")
            // パイプの端は、テストが持つ参照だけ（プロセスが持っていた fd と stream の分が返っている）
            assertEquals(1, copy.objects.refCount(input.readEnd))
            assertEquals(1, copy.objects.refCount(output.writeEnd))
            // 元は変わらない
            m.lock.withLockValue {
                assertEquals(before, m.kernel.processes[pid]!!.hostResources.size)
                assertNotNull(m.kernel.objects[fileId])
            }
            m.lock.withLockValue { m.killLocked(pid, pro.ikili.kibicomputers.core.kernel.process.KillSignal.KILL) }
            eventually(10_000, "止まらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            m.lock.withLockValue {
                assertNull(m.kernel.objects[fileId], "プロセスの終わりに開いたファイルが閉じない")
                assertEquals(1, m.kernel.objects.refCount(input.readEnd))
            }
            assertEquals(ProcessState.KILLED, m.stateOf(pid))
            m.stop()
        }
    }
}
