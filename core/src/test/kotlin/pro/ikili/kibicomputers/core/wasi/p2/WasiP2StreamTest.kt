package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.device.DeviceHandle
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.Pipes
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.HostResourceTable
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.time.TimerLimits
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
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

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
    private fun scenario(
        h: Harness,
        m: Machine,
        stdin: Int,
        stdout: Int,
        stdoutSource: StdioSource = StdioSource.Object(stdout, HandleRights.WRITE),
        args: List<String> = emptyList(),
        env: Map<String, String>? = null,
        cwd: String? = null,
        body: (ProcessContext) -> Unit,
    ): Int {
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
            stdio = StdioSpec(StdioSource.Object(stdin, HandleRights.READ), stdoutSource, StdioSource.Closed),
            args = args,
            env = env,
            cwd = cwd,
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

    // ---- 追加のテスト（レビューの指摘: 通っていなかった関数・約束の確認） ----

    private val ty = "wasi:filesystem/types"

    private val fsTypes get() = WasiP2Host.wit.getValue(ty)

    private fun errName(v: CanonValue?): String {
        val c = v as CanonValue.Case
        assertEquals(1, c.index, "ok だった: $v")
        return fsTypes.type<CanonType.Enum>("error-code").caseName(c.payload!!)
    }

    private fun root(ctx: ProcessContext): CanonValue =
        ((ctx.p2("wasi:filesystem/preopens", "get-directories") as CanonValue.Items).items[0] as CanonValue.Fields).fields[0]

    private fun openAt(
        ctx: ProcessContext,
        base: CanonValue,
        path: String,
        open: Array<String> = emptyArray(),
        flags: Array<String> = arrayOf("read"),
    ): CanonValue? = ctx.p2(
        ty,
        "[method]descriptor.open-at",
        base,
        fsTypes.type<CanonType.Flags>("path-flags").value(),
        CanonValue.str(path),
        fsTypes.type<CanonType.Flags>("open-flags").value(*open),
        fsTypes.type<CanonType.Flags>("descriptor-flags").value(*flags),
    )

    /** パイプ [p] の書く側の ByteStream に [data] を書く（テストが入力を用意する） */
    private fun feed(m: Machine, p: PipePair, data: ByteArray) {
        m.lock.withLockValue {
            val end = m.kernel.objects[p.writeEnd] as pro.ikili.kibicomputers.core.kernel.io.PipeEnd
            (m.kernel.objects[end.streamId] as ByteStream).write(data, 0, data.size, m.wakeSink)
        }
    }

    /** パイプ [p] の書く側にたまっているものを全部読む */
    private fun take(m: Machine, p: PipePair): ByteArray = m.lock.withLockValue {
        val end = m.kernel.objects[p.writeEnd] as pro.ikili.kibicomputers.core.kernel.io.PipeEnd
        val stream = m.kernel.objects[end.streamId] as ByteStream
        val buf = ByteArray(stream.available)
        stream.read(buf, 0, buf.size, m.wakeSink)
        buf
    }

    private fun envPair(k: String, v: String): CanonValue = CanonValue.Fields(listOf(CanonValue.str(k), CanonValue.str(v)))

    private fun ProcessContext.environment(): List<CanonValue> = (p2("wasi:cli/environment", "get-environment") as CanonValue.Items).items

    @Test
    fun `get-environmentは親のPWDを同じ位置でinitial-cwdに置き換え、引数はargv0を含む`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/home"))
            val input = pipe(m)
            val output = pipe(m)
            scenario(
                h,
                m,
                input.readEnd,
                output.writeEnd,
                args = listOf("prog", "ベータ"),
                env = linkedMapOf("A" to "1", "PWD" to "/stale", "B" to "2"),
                cwd = "/home",
            ) { ctx ->
                assertEquals(listOf(envPair("A", "1"), envPair("PWD", "/home"), envPair("B", "2")), ctx.environment())
                assertEquals(CanonValue.some(CanonValue.str("/home")), ctx.p2("wasi:cli/environment", "initial-cwd"))
                assertEquals(
                    listOf<CanonValue>(CanonValue.str("prog"), CanonValue.str("ベータ")),
                    (ctx.p2("wasi:cli/environment", "get-arguments") as CanonValue.Items).items,
                )
                // 環境変数そのもの（保存される Process.env）は変えない
                assertEquals(
                    mapOf("A" to "1", "PWD" to "/stale", "B" to "2"),
                    ctx.withKernelLock { ctx.kernel.processes[ctx.pid]!!.env.toMap() },
                )
            }
            m.stop()
        }
    }

    @Test
    fun `get-environmentはPWDがなければ最後に足す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/home"))
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd, env = linkedMapOf("A" to "1", "B" to "2"), cwd = "/home") { ctx ->
                assertEquals(listOf(envPair("A", "1"), envPair("B", "2"), envPair("PWD", "/home")), ctx.environment())
                assertEquals(mapOf("A" to "1", "B" to "2"), ctx.withKernelLock { ctx.kernel.processes[ctx.pid]!!.env.toMap() })
            }
            m.stop()
        }
    }

    @Test
    fun `get-environmentのPWDは起動時の値に固定され、カーネルのcwdを変えてもinitial-cwdと同じ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/home"))
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd, env = linkedMapOf("PWD" to "/stale"), cwd = "/home") { ctx ->
                // TestDriver は WasiP2Driver.start を通らないので、initial-cwd を先に呼んで値を決める
                assertEquals(CanonValue.some(CanonValue.str("/home")), ctx.p2("wasi:cli/environment", "initial-cwd"))
                ctx.changeDirectory("/")
                assertEquals("/", ctx.cwd.toString())
                // 2 回呼んでも同じ（アダプターは environ_sizes_get と environ_get で 1 回ずつ呼ぶ）
                assertEquals(listOf(envPair("PWD", "/home")), ctx.environment())
                assertEquals(listOf(envPair("PWD", "/home")), ctx.environment())
                assertEquals(CanonValue.some(CanonValue.str("/home")), ctx.p2("wasi:cli/environment", "initial-cwd"))
                assertEquals(mapOf("PWD" to "/stale"), ctx.withKernelLock { ctx.kernel.processes[ctx.pid]!!.env.toMap() })
            }
            m.stop()
        }
    }

    /** 本物の [WasiP2Driver.start]（`initial-cwd` を決める）の後に、[after] をプロセスの中で動かすドライバー */
    private class AfterStartDriver(private val inner: WasiP2Driver, private val after: (ProcessContext) -> Unit) :
        pro.ikili.kibicomputers.core.kernel.process.ProcessDriver by inner {
        override fun start(ctx: ProcessContext): StepResult = inner.start(ctx).also { after(ctx) }
    }

    @Test
    fun `実物のドライバーで起動すると、ゲストが初めてget-environmentを呼ぶ前にcwdを変えても、PWDは起動時の値`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/home"))
            val program = Program.component(
                P2Testing.loadWat(
                    """
                    (component
                      (core module ${'$'}m (memory (export "memory") 1) (func (export "run") (result i32) i32.const 0))
                      (core instance ${'$'}i (instantiate ${'$'}m))
                      (func ${'$'}run (result (result)) (canon lift (core func ${'$'}i "run") (memory ${'$'}i "memory")))
                      (instance ${'$'}inst (export "run" (func ${'$'}run)))
                      (export "wasi:cli/run@0.2.12" (instance ${'$'}inst))
                    )
                    """.trimIndent(),
                ),
            )
            val failure = AtomicReference<Throwable>()
            val driver = AfterStartDriver(WasiP2Driver.create(program.component!!)) { ctx ->
                try {
                    // start の後（ゲストはまだ get-environment を呼んでいない）に cwd を変える。PWD を決めたのは start の initialCwd
                    ctx.changeDirectory("/")
                    assertEquals("/", ctx.cwd.toString())
                    assertEquals(listOf(envPair("PWD", "/home")), ctx.environment())
                    assertEquals(CanonValue.some(CanonValue.str("/home")), ctx.p2("wasi:cli/environment", "initial-cwd"))
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            val options = SpawnOptions(env = linkedMapOf("PWD" to "/stale"), cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN)
            val pid = m.spawn(program, driver, options, 0)
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            failure.get()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            m.stop()
        }
    }

    @Test
    fun `get-terminalはTTYのときだけsome、wall-clockは実時間、wasi-socketsはnot-supported`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd, stdoutSource = StdioSource.Console) { ctx ->
                // stdin はパイプ、stdout はコンソール（TTY）、stderr は閉じている
                assertEquals(CanonValue.NONE, ctx.p2("wasi:cli/terminal-stdin", "get-terminal-stdin"))
                val tty = ctx.p2("wasi:cli/terminal-stdout", "get-terminal-stdout") as CanonValue.Case
                assertEquals(1, tty.index)
                ctx.drop(tty.payload!!)
                assertEquals(CanonValue.NONE, ctx.p2("wasi:cli/terminal-stderr", "get-terminal-stderr"))

                val datetime = WasiP2Host.wit.getValue("wasi:clocks/wall-clock").type<CanonType.Record>("datetime")
                val nanosOf = { v: CanonValue ->
                    (datetime.field(v, "seconds") as CanonValue.I64).value * 1_000_000_000L + (datetime.field(v, "nanoseconds") as CanonValue.I32).value
                }
                val before = ctx.environment.clock.realtimeNanos()
                val now = ctx.p2("wasi:clocks/wall-clock", "now")!!
                val after = ctx.environment.clock.realtimeNanos()
                assertTrue(nanosOf(now) in before..after, "$before <= ${nanosOf(now)} <= $after")
                assertEquals(
                    pro.ikili.kibicomputers.core.wasi.p1.WasiP1Basic.CLOCK_RESOLUTION_NANOS,
                    nanosOf(ctx.p2("wasi:clocks/wall-clock", "resolution")!!),
                )

                // ソケットは作れない（実物は #164）。ネットワークの口は返る
                val sockets = WasiP2Host.wit.getValue("wasi:sockets/network")
                val family = sockets.type<CanonType.Enum>("ip-address-family").case("ipv4")
                val notSupported = { v: CanonValue? ->
                    val c = v as CanonValue.Case
                    assertEquals(1, c.index)
                    assertEquals("not-supported", sockets.type<CanonType.Enum>("error-code").caseName(c.payload!!))
                }
                notSupported(ctx.p2("wasi:sockets/tcp-create-socket", "create-tcp-socket", family))
                notSupported(ctx.p2("wasi:sockets/udp-create-socket", "create-udp-socket", family))
                val network = ctx.p2("wasi:sockets/instance-network", "instance-network")!!
                notSupported(ctx.p2("wasi:sockets/ip-name-lookup", "resolve-addresses", network, CanonValue.str("example.com")))
                ctx.drop(network)
                assertEquals(0, ctx.withKernelLock { P2.tableLocked(ctx).size })
            }
            m.stop()
        }
    }

    @Test
    fun `ファイルへのappend・write-via-streamとwrite-zeroes、set-size・set-times・is-same-object・metadata-hash`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val f = m.vfs.open(VfsPath.parse("/a.txt"), OpenOptions(read = true, write = true, create = true))
            m.vfs.write(f, "abcde".toByteArray(), 0, 5)
            val g = m.vfs.open(VfsPath.parse("/b.txt"), OpenOptions(read = true, write = true, create = true))
            m.vfs.write(g, "z".toByteArray(), 0, 1)
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val root = root(ctx)
                val file = ok(openAt(ctx, root, "a.txt", flags = arrayOf("read", "write")))!!
                val read = {
                    (ok(ctx.p2(ty, "[method]descriptor.read", file, CanonValue.I64(100), CanonValue.I64(0))) as CanonValue.Fields).fields[0] as CanonValue.Bytes
                }
                val allowance = { s: CanonValue -> (ok(ctx.p2(streams, "[method]output-stream.check-write", s)) as CanonValue.I64).value }

                // append-via-stream: 終わりに足す
                val ap = ok(ctx.p2(ty, "[method]descriptor.append-via-stream", file))!!
                assertEquals(P2.FILE_WRITE_ALLOWANCE, allowance(ap))
                ok(ctx.p2(streams, "[method]output-stream.write", ap, CanonValue.Bytes("XY".toByteArray())))
                ok(ctx.p2(streams, "[method]output-stream.flush", ap))
                assertEquals("abcdeXY", String(read().bytes))
                ctx.drop(ap)

                // write-via-stream: 位置 1 から上書き
                val ws = ok(ctx.p2(ty, "[method]descriptor.write-via-stream", file, CanonValue.I64(1)))!!
                allowance(ws)
                ok(ctx.p2(streams, "[method]output-stream.write", ws, CanonValue.Bytes("ZZ".toByteArray())))
                assertEquals("aZZdeXY", String(read().bytes))
                ctx.drop(ws)

                // write-zeroes（許可量を超えるとトラップ）・blocking-write-zeroes-and-flush（4096 まで）
                val wz = ok(ctx.p2(ty, "[method]descriptor.write-via-stream", file, CanonValue.I64(0)))!!
                assertThrows<WasmTrapException> { ctx.p2(streams, "[method]output-stream.write-zeroes", wz, CanonValue.I64(2)) }
                allowance(wz)
                ok(ctx.p2(streams, "[method]output-stream.write-zeroes", wz, CanonValue.I64(2)))
                assertThrows<WasmTrapException> { ctx.p2(streams, "[method]output-stream.write-zeroes", wz, CanonValue.I64(P2.FILE_WRITE_ALLOWANCE)) }
                assertThrows<WasmTrapException> { ctx.p2(streams, "[method]output-stream.blocking-write-zeroes-and-flush", wz, CanonValue.I64(4097)) }
                ok(ctx.p2(streams, "[method]output-stream.blocking-write-zeroes-and-flush", wz, CanonValue.I64(1)))
                assertEquals("\u0000\u0000\u0000deXY", String(read().bytes))
                ctx.drop(wz)

                // set-size は切り詰める・伸ばす
                ok(ctx.p2(ty, "[method]descriptor.set-size", file, CanonValue.I64(2)))
                assertEquals(2, read().bytes.size)
                ok(ctx.p2(ty, "[method]descriptor.set-size", file, CanonValue.I64(4)))
                assertEquals(4, read().bytes.size)

                // set-times: 変更時刻だけを決めた値にする（no-change は変えない）
                val newTs = fsTypes.type<CanonType.Variant>("new-timestamp")
                val datetime = WasiP2Host.wit.getValue("wasi:clocks/wall-clock").type<CanonType.Record>("datetime")
                val at = datetime.value("seconds" to CanonValue.I64(1_000), "nanoseconds" to CanonValue.I32(5))
                ok(ctx.p2(ty, "[method]descriptor.set-times", file, newTs.case("no-change"), newTs.case("timestamp", at)))
                val stat = fsTypes.type<CanonType.Record>("descriptor-stat")
                val st = ok(ctx.p2(ty, "[method]descriptor.stat", file))!!
                assertEquals(CanonValue.some(at), stat.field(st, "data-modification-timestamp"))
                assertEquals(CanonValue.I64(4), stat.field(st, "size"))
                // 範囲外のナノ秒は invalid
                val bad = datetime.value("seconds" to CanonValue.I64(1), "nanoseconds" to CanonValue.I32(1_000_000_000))
                assertEquals("invalid", errName(ctx.p2(ty, "[method]descriptor.set-times", file, newTs.case("no-change"), newTs.case("timestamp", bad))))

                // is-same-object・metadata-hash
                val again = ok(openAt(ctx, root, "a.txt"))!!
                val other = ok(openAt(ctx, root, "b.txt"))!!
                assertEquals(CanonValue.TRUE, ctx.p2(ty, "[method]descriptor.is-same-object", file, again))
                assertEquals(CanonValue.TRUE, ctx.p2(ty, "[method]descriptor.is-same-object", file, file))
                assertEquals(CanonValue.FALSE, ctx.p2(ty, "[method]descriptor.is-same-object", file, other))
                assertEquals(CanonValue.FALSE, ctx.p2(ty, "[method]descriptor.is-same-object", file, root))
                val h1 = ok(ctx.p2(ty, "[method]descriptor.metadata-hash", file))
                assertEquals(h1, ok(ctx.p2(ty, "[method]descriptor.metadata-hash", again)))
                assertTrue(h1 != ok(ctx.p2(ty, "[method]descriptor.metadata-hash", other)))
                val pf = fsTypes.type<CanonType.Flags>("path-flags").value()
                assertEquals(h1, ok(ctx.p2(ty, "[method]descriptor.metadata-hash-at", root, pf, CanonValue.str("a.txt"))))
            }
            m.stop()
        }
    }

    @Test
    fun `デバイスのfdのstreamは、readとblocking-write-and-flushがnosysで、check-writeはinval（ADR 0054の保つ形。#695）`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val device = m.lock.withLockValue { DeviceHandle("123e4567-e89b-12d3-a456-426614174000", "kibi:screen").also { m.kernel.objects.add(it) } }
            scenario(h, m, device.id, device.id) { ctx ->
                // 失敗（last-operation-failed）のエラーの説明（errno の名前で始まる）を返す
                fun failure(v: CanonValue?): String {
                    val r = v as CanonValue.Case
                    assertEquals(1, r.index, "err のはず: $v")
                    val failed = r.payload as CanonValue.Case
                    assertEquals(0, failed.index, "last-operation-failed のはず: $v")
                    return (ctx.p2("wasi:io/error", "[method]error.to-debug-string", failed.payload!!) as CanonValue.Str).value
                }
                // 入力の read は FdIo.readHandle を通り、NOSYS
                val inp = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                assertTrue(failure(ctx.p2(streams, "[method]input-stream.read", inp, CanonValue.I64(8))).startsWith("nosys"))
                // 出力の check-write は FdIo.pollEntryLocked(WRITABLE) を通り、デバイスは INVAL（NOSYS ではない）
                val out = ctx.p2("wasi:cli/stdout", "get-stdout")!!
                assertTrue(failure(ctx.p2(streams, "[method]output-stream.check-write", out)).startsWith("inval"))
                // blocking-write-and-flush は check-write を通らず FdIo.writeHandle を通り、NOSYS
                val out2 = ctx.p2("wasi:cli/stdout", "get-stdout")!!
                val written = ctx.p2(streams, "[method]output-stream.blocking-write-and-flush", out2, CanonValue.Bytes("hi".toByteArray()))
                assertTrue(failure(written).startsWith("nosys"))
            }
            m.stop()
        }
    }

    private object TestDevice : Device {
        override val kind = "test:device"
        override val methods: DeviceMethods = DeviceMethods.builder().build()
    }

    @Test
    fun `パスで開いたdeviceのfdはget-typeとstatがunknownで、パスのstat-atとread-directoryはcharacter-device（ADR 0054。#865）`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/dev"))
            assertNull(m.mountDev())
            val address = "00000000-0000-4000-8000-000000000865"
            assertTrue(m.devices.attach(address, TestDevice))
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val kind = fsTypes.type<CanonType.Enum>("descriptor-type")
                val stat = fsTypes.type<CanonType.Record>("descriptor-stat")
                val typeOf = { d: CanonValue -> kind.caseName(ok(ctx.p2(ty, "[method]descriptor.get-type", d))!!) }
                val root = root(ctx)
                // device の fd は UNKNOWN（CHARACTER_DEVICE を返す fd は Tty だけ。p1 の fd_fdstat_get と同じ）
                val device = ok(openAt(ctx, root, "dev/device/$address", flags = arrayOf("read", "write")))!!
                assertEquals("unknown", typeOf(device))
                val st = ok(ctx.p2(ty, "[method]descriptor.stat", device))!!
                assertEquals("unknown", kind.caseName(stat.field(st, "type")))
                assertEquals(CanonValue.I64(1), stat.field(st, "link-count"))
                assertEquals(CanonValue.I64(0), stat.field(st, "size"))
                // パスの種類は CHARACTER_DEVICE のまま（ls -l 向け）
                val pf = fsTypes.type<CanonType.Flags>("path-flags").value()
                val pathStat = ok(ctx.p2(ty, "[method]descriptor.stat-at", root, pf, CanonValue.str("dev/device/$address")))!!
                assertEquals("character-device", kind.caseName(stat.field(pathStat, "type")))
                val dir = ok(openAt(ctx, root, "dev/device", flags = arrayOf("read")))!!
                val ds = ok(ctx.p2(ty, "[method]descriptor.read-directory", dir))!!
                val entry = ok(ctx.p2(ty, "[method]directory-entry-stream.read-directory-entry", ds)) as CanonValue.Case
                assertEquals(1, entry.index, "device の項目があるはず")
                val dirEntry = fsTypes.type<CanonType.Record>("directory-entry")
                assertEquals(CanonValue.str(address), dirEntry.field(entry.payload!!, "name"))
                assertEquals("character-device", kind.caseName(dirEntry.field(entry.payload!!, "type")))
                // ファイル（/dev/null は OpenFile）の fd は VFS の種類（CHARACTER_DEVICE）のまま
                val devNull = ok(openAt(ctx, root, "dev/null", flags = arrayOf("read", "write")))!!
                assertEquals("character-device", typeOf(devNull))
                assertEquals("character-device", kind.caseName(stat.field(ok(ctx.p2(ty, "[method]descriptor.stat", devNull))!!, "type")))
            }
            m.stop()
        }
    }

    @Test
    fun `deviceとttyのfdのmetadata-hashは(0,0)でokになり、is-same-objectが真なら等しい（ADR 0267。#885）`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/dev"))
            assertNull(m.mountDev())
            val address = "00000000-0000-4000-8000-000000000885"
            assertTrue(m.devices.attach(address, TestDevice))
            // /dev/tty を開くには console の Tty が要る
            m.lock.withLockValue { m.ensureConsoleLocked() }
            m.vfs.open(VfsPath.parse("/f.txt"), OpenOptions(read = true, write = true, create = true))
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val hashType = fsTypes.type<CanonType.Record>("metadata-hash-value")
                val zero = hashType.value("lower" to CanonValue.I64(0), "upper" to CanonValue.I64(0))
                val hashOf = { d: CanonValue -> ok(ctx.p2(ty, "[method]descriptor.metadata-hash", d))!! }
                val same = { x: CanonValue, y: CanonValue -> ctx.p2(ty, "[method]descriptor.is-same-object", x, y) }
                val root = root(ctx)
                val device = ok(openAt(ctx, root, "dev/device/$address", flags = arrayOf("read", "write")))!!
                val tty = ok(openAt(ctx, root, "dev/tty", flags = arrayOf("read", "write")))!!
                val tty2 = ok(openAt(ctx, root, "dev/tty", flags = arrayOf("read", "write")))!!
                val file = ok(openAt(ctx, root, "f.txt"))!!
                // ファイル以外の fd は (0, 0)（p1 の fd_filestat_get の ino=0・dev=0 と同じ）
                assertEquals(zero, hashOf(device))
                assertEquals(zero, hashOf(tty))
                // /dev/tty を2回開くと同じ Tty を指すので、is-same-object が真で hash も等しい
                assertEquals(CanonValue.TRUE, same(tty, tty2))
                assertEquals(hashOf(tty), hashOf(tty2))
                assertEquals(CanonValue.TRUE, same(device, device))
                // 種類の違うもの同士は偽（hash が等しくても同じものとは限らない）
                assertEquals(CanonValue.FALSE, same(device, tty))
                assertEquals(CanonValue.FALSE, same(device, file))
                assertEquals(CanonValue.FALSE, same(tty, file))
                // パスで引く hash は VFS の値のまま（/dev の node は 0 ではない。fd とパスで違う。ADR 0054）
                val pf = fsTypes.type<CanonType.Flags>("path-flags").value()
                val pathHash = ok(ctx.p2(ty, "[method]descriptor.metadata-hash-at", root, pf, CanonValue.str("dev/device/$address")))!!
                assertTrue((hashType.field(pathHash, "lower") as CanonValue.I64).value != 0L)
                // /dev/null（OpenFile）の fd は VFS の値で、パスの hash と同じ
                val devNull = ok(openAt(ctx, root, "dev/null", flags = arrayOf("read", "write")))!!
                val nullHash = hashOf(devNull)
                assertTrue(nullHash != zero)
                assertEquals(nullHash, ok(ctx.p2(ty, "[method]descriptor.metadata-hash-at", root, pf, CanonValue.str("dev/null"))))
                // ファイルの fd の hash も今のまま（0 ではない）
                assertTrue(hashOf(file) != zero)
            }
            m.stop()
        }
    }

    @Test
    fun `ストリームの失敗はlast-operation-failedで、errorからerror-codeと説明を読める`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val file = ok(openAt(ctx, root(ctx), "f.txt", open = arrayOf("create"), flags = arrayOf("read", "write")))!!
                // 位置が u64 の最大（Long の範囲に飽和）のところへ書くと、ファイルが大きすぎて失敗する
                val s = ok(ctx.p2(ty, "[method]descriptor.write-via-stream", file, CanonValue.I64(-1)))!!
                ok(ctx.p2(streams, "[method]output-stream.check-write", s))
                val r = ctx.p2(streams, "[method]output-stream.write", s, CanonValue.Bytes("abc".toByteArray())) as CanonValue.Case
                assertEquals(1, r.index)
                val failed = r.payload as CanonValue.Case
                assertEquals(0, failed.index, "last-operation-failed のはず")
                val error = failed.payload!!
                val code = ctx.p2(ty, "filesystem-error-code", error) as CanonValue.Case
                assertEquals(1, code.index)
                assertEquals("file-too-large", fsTypes.type<CanonType.Enum>("error-code").caseName(code.payload!!))
                val text = (ctx.p2("wasi:io/error", "[method]error.to-debug-string", error) as CanonValue.Str).value
                assertTrue(text.contains("fbig") && text.contains("file-too-large"), text)
                ctx.drop(error)
                ctx.drop(s)
            }
            m.stop()
        }
    }

    @Test
    fun `spliceは出力の許可量だけ運び、出力が一杯なら待たずに0、閉じていれば入力を読まずにclosed`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val input = pipe(m, capacity = 8192)
            val output = pipe(m, capacity = 1024)
            val data = ByteArray(4000) { (it % 251).toByte() }
            feed(m, input, data)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val inp = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                val out = ctx.p2("wasi:cli/stdout", "get-stdout")!!
                val splice = { len: Long -> (ok(ctx.p2(streams, "[method]output-stream.splice", out, inp, CanonValue.I64(len))) as CanonValue.I64).value }
                // 出力の空き（1024）だけ運ぶ。待たない
                assertEquals(1024, splice(4000))
                // 出力が一杯なら、入力を読まずに 0（スレッドを持ったまま待たない）
                assertEquals(0, splice(4000))
                assertEquals(0, splice(0))
                val rest = ok(ctx.p2(streams, "[method]input-stream.read", inp, CanonValue.I64(10_000))) as CanonValue.Bytes
                assertEquals(4000 - 1024, rest.bytes.size)
                assertTrue(rest.bytes.contentEquals(data.copyOfRange(1024, 4000)), "入力の続きが運ばれていない")
            }
            assertTrue(take(m, output).contentEquals(data.copyOf(1024)))
            m.stop()
        }
        // 出力の読み手が閉じていれば、入力を読む前に closed。入力は消費されない
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val input = pipe(m)
            val output = pipe(m)
            feed(m, input, "hello".toByteArray())
            m.lock.withLockValue { m.kernel.objects.release(output.readEnd, m.wakeSink) }
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val inp = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                val out = ctx.p2("wasi:cli/stdout", "get-stdout")!!
                assertTrue(isClosed(ctx.p2(streams, "[method]output-stream.splice", out, inp, CanonValue.I64(5))))
                assertTrue(isClosed(ctx.p2(streams, "[method]output-stream.blocking-splice", out, inp, CanonValue.I64(5))))
                val rest = ok(ctx.p2(streams, "[method]input-stream.read", inp, CanonValue.I64(100))) as CanonValue.Bytes
                assertEquals("hello", String(rest.bytes))
            }
            m.stop()
        }
    }

    @Test
    fun `blocking-spliceは出力に空きができるまで待ってから入力を読む`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val input = pipe(m, capacity = 1024)
            val output = pipe(m, capacity = 16)
            val data = ByteArray(40) { (it + 1).toByte() }
            feed(m, input, data)
            val results = java.util.concurrent.CopyOnWriteArrayList<Long>()
            val failure = AtomicReference<Throwable>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    val inp = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                    val out = ctx.p2("wasi:cli/stdout", "get-stdout")!!
                    // 1 回目は空き（16）だけ運ぶ。2 回目は出力が一杯なので、読み手が空けるまで待つ
                    repeat(2) {
                        results += (ok(ctx.p2(streams, "[method]output-stream.blocking-splice", out, inp, CanonValue.I64(40))) as CanonValue.I64).value
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
                StepResult.Exit(0)
            }
            val options = SpawnOptions(
                stdio = StdioSpec(StdioSource.Object(input.readEnd, HandleRights.READ), StdioSource.Object(output.writeEnd, HandleRights.WRITE), StdioSource.Closed),
            )
            val pid = m.spawn(empty, driver, options, 0)
            eventually(10_000, "待たない") {
                h.scheduler.tick()
                m.stateOf(pid).let { s ->
                    check(!s.isTerminal) { "終わった: $s ${failure.get()}" }
                    s == ProcessState.WAITING_EVENT
                }
            }
            assertEquals(listOf(16L), results.toList())
            val first = take(m, output)
            assertEquals(16, first.size)
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            failure.get()?.let { throw it }
            assertEquals(listOf(16L, 16L), results.toList())
            assertTrue((first + take(m, output)).contentEquals(data.copyOf(32)))
            m.stop()
        }
    }

    @Test
    fun `read-directoryのstreamは中身の写しを持たず、読むたびに名前の順の続きを返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (n in listOf("b", "d", "a")) m.vfs.open(VfsPath.parse("/$n"), OpenOptions(read = true, write = true, create = true))
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val root = root(ctx)
                val entry = fsTypes.type<CanonType.Record>("directory-entry")
                val next = { s: CanonValue ->
                    (ok(ctx.p2(ty, "[method]directory-entry-stream.read-directory-entry", s)) as CanonValue.Case).payload
                        ?.let { (entry.field(it, "name") as CanonValue.Str).value }
                }
                val s = ok(ctx.p2(ty, "[method]descriptor.read-directory", root))!!
                // 写しを持たない: 持つのはディレクトリの参照と、最後に返した名前だけ
                assertNull(ctx.withKernelLock { P2.entryLocked<DirectoryStreamEntry>(ctx, s) }.last)
                assertEquals("a", next(s))
                // 途中で足したものも、消したものも、名前の順の続きから読める
                ctx.vfs.removeFile(VfsPath.parse("/b"))
                ctx.vfs.open(VfsPath.parse("/c"), OpenOptions(read = true, write = true, create = true))
                assertEquals("c", next(s))
                assertEquals("d", next(s))
                assertNull(next(s))
                assertNull(next(s))
                // descriptor は stream が残っていても drop できる（stream は自分でディレクトリの参照を持つ）
                val dirId = ctx.withKernelLock { P2.entryLocked<DirectoryStreamEntry>(ctx, s).objectId }
                val before = ctx.withKernelLock { ctx.kernel.objects.refCount(dirId) }
                ctx.drop(root)
                assertEquals(before - 1, ctx.withKernelLock { ctx.kernel.objects.refCount(dirId) })
                assertNull(next(s))
                ctx.drop(s)
                // ファイルの descriptor は read-directory できない
                val file = ok(openAt(ctx, root(ctx), "a"))!!
                assertEquals("not-directory", errName(ctx.p2(ty, "[method]descriptor.read-directory", file)))
            }
            m.stop()
        }
    }

    @Test
    fun `mutate-directoryのないdescriptorでは、ディレクトリの中身を変える操作がread-onlyになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/sub"))
            m.vfs.open(VfsPath.parse("/sub/x"), OpenOptions(read = true, write = true, create = true))
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val root = root(ctx)
                val flagsType = fsTypes.type<CanonType.Flags>("descriptor-flags")
                val getFlags = { d: CanonValue -> (ok(ctx.p2(ty, "[method]descriptor.get-flags", d)) as CanonValue.Flags).bits }
                assertEquals(flagsType.value("read", "mutate-directory").bits, getFlags(root))
                // mutate-directory を持たないディレクトリの descriptor（今の preopen からは作れないので、表に直接置く）
                val sub = ok(openAt(ctx, root, "sub", open = arrayOf("directory")))!!
                val ro = ctx.withKernelLock {
                    val id = P2.entryLocked<DescriptorEntry>(ctx, sub).objectId
                    ctx.kernel.objects.retain(id)
                    P2.ownLocked(ctx, DescriptorEntry(HandleEntry(id, rights = HandleRights.READ), flagsType.value("read").bits))
                }
                assertEquals(flagsType.value("read").bits, getFlags(ro))
                val pf = fsTypes.type<CanonType.Flags>("path-flags").value()
                val newTs = fsTypes.type<CanonType.Variant>("new-timestamp")
                assertEquals("read-only", errName(ctx.p2(ty, "[method]descriptor.create-directory-at", ro, CanonValue.str("d"))))
                assertEquals("read-only", errName(ctx.p2(ty, "[method]descriptor.remove-directory-at", ro, CanonValue.str("d"))))
                assertEquals("read-only", errName(ctx.p2(ty, "[method]descriptor.unlink-file-at", ro, CanonValue.str("x"))))
                assertEquals("read-only", errName(ctx.p2(ty, "[method]descriptor.rename-at", ro, CanonValue.str("x"), ro, CanonValue.str("y"))))
                assertEquals("read-only", errName(ctx.p2(ty, "[method]descriptor.rename-at", root, CanonValue.str("sub/x"), ro, CanonValue.str("y"))))
                assertEquals("read-only", errName(ctx.p2(ty, "[method]descriptor.rename-at", ro, CanonValue.str("x"), root, CanonValue.str("y"))))
                assertEquals(
                    "read-only",
                    errName(ctx.p2(ty, "[method]descriptor.set-times-at", ro, pf, CanonValue.str("x"), newTs.case("now"), newTs.case("now"))),
                )
                assertEquals("read-only", errName(openAt(ctx, ro, "n", open = arrayOf("create"), flags = arrayOf("read"))))
                assertEquals("read-only", errName(openAt(ctx, ro, "x", open = arrayOf("truncate"), flags = arrayOf("read"))))
                assertEquals("read-only", errName(openAt(ctx, ro, "x", flags = arrayOf("read", "write"))))
                // 基点が持たない mutate-directory は、open-at で求めても得られない
                assertEquals("read-only", errName(openAt(ctx, ro, "x", flags = arrayOf("read", "mutate-directory"))))
                // 何も変えない操作は通る。持たない基点から開いたディレクトリも持たない
                val x = ok(openAt(ctx, ro, "x"))!!
                assertEquals(flagsType.value("read").bits, getFlags(x))
                ok(ctx.p2(ty, "[method]descriptor.stat-at", ro, pf, CanonValue.str("x")))
                assertEquals(flagsType.value("read").bits, getFlags(ok(openAt(ctx, ro, ".", open = arrayOf("directory")))!!))
                // read だけで開いたサブディレクトリも、基点（preopen）の mutate-directory を引き継ぐ（wasmtime と同じ）。ファイルは持たない
                assertEquals(flagsType.value("read", "mutate-directory").bits, getFlags(sub))
                assertEquals(flagsType.value("read").bits, getFlags(ok(openAt(ctx, root, "sub/x", flags = arrayOf("read")))!!))
                ok(ctx.p2(ty, "[method]descriptor.create-directory-at", sub, CanonValue.str("d")))
                ok(ctx.p2(ty, "[method]descriptor.remove-directory-at", sub, CanonValue.str("d")))
                ok(ctx.p2(ty, "[method]descriptor.rename-at", sub, CanonValue.str("x"), sub, CanonValue.str("y")))
                ok(ctx.p2(ty, "[method]descriptor.unlink-file-at", sub, CanonValue.str("y")))
                ok(openAt(ctx, sub, "n", open = arrayOf("create"), flags = arrayOf("read", "write")))
            }
            m.stop()
        }
    }

    @Test
    fun `ホストのresourceの表が満杯なら、作りかけの参照を返してトラップし、open-atはファイルを作る前に断る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val f = m.vfs.open(VfsPath.parse("/a.txt"), OpenOptions(read = true, write = true, create = true))
            m.vfs.write(f, "abcde".toByteArray(), 0, 5)
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val root = root(ctx)
                val file = ok(openAt(ctx, root, "a.txt"))!!
                val rootId = ctx.withKernelLock { P2.entryLocked<DescriptorEntry>(ctx, root).objectId }
                val fileId = ctx.withKernelLock { P2.entryLocked<DescriptorEntry>(ctx, file).objectId }
                val stdinId = ctx.withKernelLock { ctx.kernel.processes[ctx.pid]!!.handles[0]!!.objectId }
                // 表を満杯にする（中身のない印で埋める）
                ctx.withKernelLock {
                    val table = P2.tableLocked(ctx)
                    while (table.size < HostResourceTable.LIMIT) P2.ownLocked(ctx, MarkerEntry("filler"))
                }
                val refs = { ctx.withKernelLock { listOf(rootId, fileId, stdinId).map { ctx.kernel.objects.refCount(it) } } }
                val objects = { ctx.withKernelLock { ctx.kernel.objects.size } }
                val before = refs()
                val count = objects()
                val calls = listOf<() -> Unit>(
                    { ctx.p2("wasi:cli/stdin", "get-stdin") },
                    { ctx.p2("wasi:filesystem/preopens", "get-directories") },
                    { ctx.p2(ty, "[method]descriptor.read-via-stream", file, CanonValue.I64(0)) },
                    { ctx.p2(ty, "[method]descriptor.read-directory", root) },
                    { openAt(ctx, root, "a.txt") },
                    { openAt(ctx, root, "new.txt", open = arrayOf("create"), flags = arrayOf("read", "write")) },
                    { openAt(ctx, root, "a.txt", open = arrayOf("truncate"), flags = arrayOf("read", "write")) },
                )
                for (call in calls) {
                    assertThrows<WasmTrapException> { call() }
                    // 参照もオブジェクトも残らない
                    assertEquals(before, refs())
                    assertEquals(count, objects())
                }
                // open-at はファイルを作らず、切り詰めない
                val pf = fsTypes.type<CanonType.Flags>("path-flags").value()
                assertEquals("no-entry", errName(ctx.p2(ty, "[method]descriptor.stat-at", root, pf, CanonValue.str("new.txt"))))
                assertEquals(5L, ctx.vfs.stat(VfsPath.parse("/a.txt")).size)
            }
            m.stop()
        }
    }

    @Test
    fun `動いているタイマーが上限なら、時計のpollableのpollとblockはトラップせず早めに返る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val input = pipe(m)
            val output = pipe(m)
            scenario(h, m, input.readEnd, output.writeEnd) { ctx ->
                val ms = 1_000_000L
                repeat(TimerLimits.MAX_ARMED_TIMERS) { ctx.setTimer(ctx.createTimer(), 60_000 * ms) }
                val far = ctx.p2("wasi:clocks/monotonic-clock", "subscribe-duration", CanonValue.I64(30_000 * ms))!!
                val stdin = ctx.p2("wasi:cli/stdin", "get-stdin")!!
                val readable = ctx.p2(streams, "[method]input-stream.subscribe", stdin)!!
                val start = ctx.monotonicNanos()
                // そろっていない入力と並べても、時計の pollable の添字だけがそろったものとして返る
                val r = ctx.p2("wasi:io/poll", "poll", CanonValue.Items(listOf(readable, far))) as CanonValue.Items
                assertEquals(listOf<CanonValue>(CanonValue.I32(1)), r.items)
                ctx.p2("wasi:io/poll", "[method]pollable.block", far)
                assertTrue(ctx.monotonicNanos() - start < 10_000 * ms)
                assertEquals(CanonValue.FALSE, ctx.p2("wasi:io/poll", "[method]pollable.ready", far))
                assertTrue(ctx.withKernelLock { ctx.kernel.processes[ctx.pid]!!.waitTimers.isEmpty })
            }
            m.stop()
        }
    }
}
