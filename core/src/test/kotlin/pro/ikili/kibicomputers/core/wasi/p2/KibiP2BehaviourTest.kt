package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.device.DeviceLimits
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.HostResourceTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentCall
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `kibi:*` の p2 の振る舞い（#685。ADR 0685）。コンポーネントのプロセスの中から、ホストの関数の本体を直接呼んで確かめる
 * （[WasiP2StreamTest] と同じ形）
 */
class KibiP2BehaviourTest {
    private val limit = Duration.ofSeconds(120)

    private val empty: Program by lazy { Program.component(P2Testing.loadWat("(component (core module \$m) (core instance (instantiate \$m)))")) }

    private val hello: ByteArray by lazy { P2Testing.wrap("hello-wasi") }

    private fun ProcessContext.call(iface: String, fn: String, vararg args: CanonValue): CanonValue? {
        val i = KibiP2Host.interfaces[iface] ?: WasiP2Host.interfaces.getValue(iface)
        val f = checkNotNull(i.functions[fn]) { "$iface#$fn がない" }
        return f.body.call(ComponentCall(component, object : KernelLinkEnv(this) {}, iface, fn), args.toList())
    }

    private fun ProcessContext.drop(v: CanonValue?) = P2.drop(this, (v as CanonValue.Handle).value)

    private fun ok(v: CanonValue?): CanonValue? {
        val c = v as CanonValue.Case
        assertEquals(0, c.index, "err: ${(c.payload as? CanonValue.Case)?.index?.let { KibiErrors.NAMES.getOrNull(it) } ?: c.payload}")
        return c.payload
    }

    private fun errName(v: CanonValue?): String {
        val c = v as CanonValue.Case
        assertEquals(1, c.index, "ok: $v")
        return KibiErrors.NAMES[(c.payload as CanonValue.Case).index]
    }

    private fun name(e: Errno): String = KibiErrors.NAMES[KibiErrors.code(e)]

    private fun hostSize(ctx: ProcessContext): Int = ctx.withKernelLock { P2.tableLocked(ctx).size }

    private fun machine(h: Harness): Machine {
        val m = h.machine(fuelPerTick = 10_000_000)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        return m
    }

    private fun write(m: Machine, path: String, bytes: ByteArray) {
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
    }

    /** [body] をコンポーネントのプロセスの中で動かす。標準出力は [stdout]（なければ閉じる） */
    private fun scenario(h: Harness, m: Machine, stdout: Int? = null, body: (ProcessContext) -> Unit) {
        val failure = AtomicReference<Throwable>()
        val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
            try {
                body(ctx)
            } catch (t: Throwable) {
                failure.set(t)
            }
            StepResult.Exit(0)
        }
        val out = stdout?.let { StdioSource.Object(it, HandleRights.WRITE) } ?: StdioSource.Closed
        val options = SpawnOptions(stdio = StdioSpec(StdioSource.Closed, out, StdioSource.Closed), fds = SpawnOptions.ROOT_PREOPEN)
        val pid = m.spawn(empty, driver, options, 0)
        eventually(60_000, "終わらない") {
            h.scheduler.tick()
            m.stateOf(pid).isTerminal
        }
        failure.get()?.let { throw it }
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
    }

    /** [p] がそろうまで wasi:io/poll.poll で待つ */
    private fun ProcessContext.await(p: CanonValue) {
        call("wasi:io/poll", "poll", CanonValue.Items(listOf(CanonValue.Handle((p as CanonValue.Handle).value))))
    }

    private fun spawnOptions(fds: List<CanonValue> = emptyList()): CanonValue = CanonValue.Fields(
        listOf(CanonValue.Items(emptyList()), CanonValue.NONE, CanonValue.NONE, CanonValue.Items(fds)),
    )

    private fun fdAssign(fd: Int, parent: Int?): CanonValue = CanonValue.Fields(
        listOf(CanonValue.I32(fd), if (parent == null) CanonValue.Case(1, null) else CanonValue.Case(0, CanonValue.I32(parent))),
    )

    private fun handleAssign(fd: Int, kind: Int, v: CanonValue): CanonValue = CanonValue.Fields(listOf(CanonValue.I32(fd), CanonValue.Case(kind, v)))

    @Test
    fun `イベントキューとタイマー・pollableで待ち、子が残る親のdropはトラップ`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            scenario(h, m) { ctx ->
                val base = hostSize(ctx)
                val q = ok(ctx.call("kibi:kernel/events", "[static]event-queue.open", CanonValue.Items(listOf(CanonValue.str("timer"))), CanonValue.NONE))!!
                val batch = ok(ctx.call("kibi:kernel/events", "[method]event-queue.take", q, CanonValue.I32(8))) as CanonValue.Fields
                assertEquals(0, (batch.fields[0] as CanonValue.Items).items.size)
                assertEquals(name(Errno.INVAL), errName(ctx.call("kibi:kernel/events", "[method]event-queue.take", q, CanonValue.I32(0))))
                val t = ok(
                    ctx.call(
                        "kibi:kernel/timers",
                        "[static]timer.create",
                        CanonValue.some(CanonValue.Fields(listOf(q, CanonValue.I64(7)))),
                    ),
                )!!
                ok(ctx.call("kibi:kernel/timers", "[method]timer.set-after", t, CanonValue.I64(1_000_000), CanonValue.I64(0)))
                val tp = ctx.call("kibi:kernel/timers-poll", "subscribe", t)!!
                val qp = ctx.call("kibi:kernel/events-poll", "subscribe", q)!!
                // 子の pollable が残る親は drop できない
                assertThrows<WasmTrapException> { ctx.drop(q) }
                ctx.await(qp)
                val got = ok(ctx.call("kibi:kernel/events", "[method]event-queue.take", q, CanonValue.I32(8))) as CanonValue.Fields
                val events = (got.fields[0] as CanonValue.Items).items
                assertEquals(1, events.size)
                assertEquals(CanonValue.str("timer"), (events[0] as CanonValue.Fields).fields[0])
                ok(ctx.call("kibi:kernel/timers", "[method]timer.read", t))
                // 種類の違うハンドルはトラップ
                assertThrows<WasmTrapException> { ctx.call("kibi:kernel/timers", "[method]timer.read", q) }
                ctx.drop(tp)
                ctx.drop(qp)
                ctx.drop(t)
                ctx.drop(q)
                assertEquals(base, hostSize(ctx))
            }
        }
    }

    @Test
    fun `pipe-streamsはwasiのstreamで読み書きでき、表が満杯ならmfile`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            scenario(h, m) { ctx ->
                val pair = ok(ctx.call("kibi:io/pipe-streams", "create")) as CanonValue.Fields
                val (input, output) = pair.fields
                ok(ctx.call("wasi:io/streams", "[method]output-stream.blocking-write-and-flush", output, CanonValue.Bytes("hi".toByteArray())))
                val read = ok(ctx.call("wasi:io/streams", "[method]input-stream.blocking-read", input, CanonValue.I64(16))) as CanonValue.Bytes
                assertEquals("hi", String(read.bytes))
                ctx.drop(input)
                ctx.drop(output)
                // 表を満杯にする（残り 1 つ）
                val fillers = ctx.withKernelLock {
                    val table = P2.tableLocked(ctx)
                    List(HostResourceTable.LIMIT - 1 - table.size) { table.add(MarkerEntry("filler")) }
                }
                assertEquals(name(Errno.MFILE), errName(ctx.call("kibi:io/pipe-streams", "create")))
                val q = ok(ctx.call("kibi:kernel/events", "[static]event-queue.open", CanonValue.Items(emptyList()), CanonValue.NONE))!!
                assertEquals(name(Errno.MFILE), errName(ctx.call("kibi:kernel/events", "[static]event-queue.open", CanonValue.Items(emptyList()), CanonValue.NONE)))
                assertEquals(name(Errno.MFILE), errName(ctx.call("kibi:kernel/timers", "[static]timer.create", CanonValue.NONE)))
                // マシンのオブジェクトは残さない（失敗した open が作りかけのキューを返す）
                ctx.drop(q)
                ctx.withKernelLock { for (r in fillers) P2.tableLocked(ctx).remove(r) }
            }
        }
    }

    @Test
    fun `spawnした子をpollableで待ってstatusを取り、handlesでパイプを標準出力に渡す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            write(m, "/bin/hello", hello)
            scenario(h, m) { ctx ->
                val base = hostSize(ctx)
                val p = ok(ctx.call("kibi:process/process", "[static]process.spawn", CanonValue.str("/bin/ok"), spawnOptions()))!!
                assertTrue((ctx.call("kibi:process/process", "[method]process.pid", p) as CanonValue.I32).value > 0)
                val pp = ctx.call("kibi:process/process-poll", "subscribe", p)!!
                ctx.await(pp)
                val status = ok(ctx.call("kibi:process/process", "[method]process.status", p)) as CanonValue.Case
                assertEquals(1, status.index)
                val exit = status.payload as CanonValue.Case
                assertEquals(0, exit.index)
                assertEquals(0, (exit.payload as CanonValue.I32).value)
                ctx.drop(pp)
                ctx.drop(p)

                // パイプの書く端を子の fd 1 にする
                val pipe = ok(ctx.call("kibi:io/pipe-streams", "create")) as CanonValue.Fields
                val (input, output) = pipe.fields
                val c = ok(
                    ctx.call(
                        "kibi:process/process-poll",
                        "spawn-with-handles",
                        CanonValue.str("/bin/hello"),
                        spawnOptions(),
                        CanonValue.Items(listOf(handleAssign(1, 1, output))),
                    ),
                )!!
                ctx.drop(output)
                val text = StringBuilder()
                while (true) {
                    val r = ctx.call("wasi:io/streams", "[method]input-stream.blocking-read", input, CanonValue.I64(256)) as CanonValue.Case
                    if (r.index != 0) break
                    text.append(String((r.payload as CanonValue.Bytes).bytes))
                }
                assertEquals("Hello from WASI\n", text.toString())
                ctx.drop(input)
                val cp = ctx.call("kibi:process/process-poll", "subscribe", c)!!
                ctx.await(cp)
                ctx.drop(cp)
                ctx.drop(c)
                assertEquals(base, hostSize(ctx))
            }
        }
    }

    @Test
    fun `spawnの誤りはerror-codeで返す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            scenario(h, m) { ctx ->
                val base = hostSize(ctx)
                val spawn = "[static]process.spawn"
                assertEquals(name(Errno.NOENT), errName(ctx.call("kibi:process/process", spawn, CanonValue.str("/bin/none"), spawnOptions())))
                // p2 の fds の parent は 0..2 だけ
                assertEquals(
                    name(Errno.INVAL),
                    errName(ctx.call("kibi:process/process", spawn, CanonValue.str("/bin/ok"), spawnOptions(listOf(fdAssign(3, 3))))),
                )
                val pipe = ok(ctx.call("kibi:io/pipe-streams", "create")) as CanonValue.Fields
                val (input, output) = pipe.fields
                val withHandles = "spawn-with-handles"
                // 同じ fd を fds と handles に書いた
                assertEquals(
                    name(Errno.INVAL),
                    errName(
                        ctx.call(
                            "kibi:process/process-poll", withHandles, CanonValue.str("/bin/ok"),
                            spawnOptions(listOf(fdAssign(1, null))), CanonValue.Items(listOf(handleAssign(1, 1, output))),
                        ),
                    ),
                )
                // handles の中で 2 回
                assertEquals(
                    name(Errno.INVAL),
                    errName(
                        ctx.call(
                            "kibi:process/process-poll", withHandles, CanonValue.str("/bin/ok"),
                            spawnOptions(), CanonValue.Items(listOf(handleAssign(4, 0, input), handleAssign(4, 1, output))),
                        ),
                    ),
                )
                // 閉じた stream（閉じた fd 0 の get-stdin）
                val stdin = ctx.call("wasi:cli/stdin", "get-stdin")!!
                assertEquals(
                    name(Errno.BADF),
                    errName(
                        ctx.call(
                            "kibi:process/process-poll", withHandles, CanonValue.str("/bin/ok"),
                            spawnOptions(), CanonValue.Items(listOf(handleAssign(0, 0, stdin))),
                        ),
                    ),
                )
                // 自分の位置を持つファイルの stream は渡せない
                val dirs = (ctx.call("wasi:filesystem/preopens", "get-directories") as CanonValue.Items).items
                val root = (dirs[0] as CanonValue.Fields).fields[0]
                val file = ok(
                    ctx.call(
                        "wasi:filesystem/types",
                        "[method]descriptor.open-at",
                        root,
                        CanonValue.Flags(0),
                        CanonValue.str("bin/ok"),
                        CanonValue.Flags(0),
                        CanonValue.Flags(1),
                    ),
                )!!
                val fileStream = ok(ctx.call("wasi:filesystem/types", "[method]descriptor.read-via-stream", file, CanonValue.I64(0)))!!
                assertEquals(
                    name(Errno.NOTSUP),
                    errName(
                        ctx.call(
                            "kibi:process/process-poll", withHandles, CanonValue.str("/bin/ok"),
                            spawnOptions(), CanonValue.Items(listOf(handleAssign(0, 0, fileStream))),
                        ),
                    ),
                )
                // descriptor なら渡せる
                val c = ok(
                    ctx.call(
                        "kibi:process/process-poll", withHandles, CanonValue.str("/bin/ok"),
                        spawnOptions(), CanonValue.Items(listOf(handleAssign(3, 2, file))),
                    ),
                )!!
                for (v in listOf(fileStream, file, root, stdin, input, output)) ctx.drop(v)
                val cp = ctx.call("kibi:process/process-poll", "subscribe", c)!!
                ctx.await(cp)
                ctx.drop(cp)
                ctx.drop(c)
                assertEquals(base, hostSize(ctx))
            }
        }
    }

    @Test
    fun `子のハンドルをdropすると、終わった子は刈り取り、動いている子は親なしにする`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            scenario(h, m) { ctx ->
                val p = ok(ctx.call("kibi:process/process", "[static]process.spawn", CanonValue.str("/bin/ok"), spawnOptions()))!!
                val pid = (ctx.call("kibi:process/process", "[method]process.pid", p) as CanonValue.I32).value
                val pp = ctx.call("kibi:process/process-poll", "subscribe", p)!!
                ctx.await(pp)
                ctx.drop(pp)
                assertTrue(ctx.withKernelLock { ctx.kernel.processes[pid] != null }, "ハンドルがある間は刈り取らない")
                ctx.drop(p)
                assertTrue(ctx.withKernelLock { ctx.kernel.processes[pid] == null }, "最後のハンドルを閉じたら刈り取る")

                val q = ok(ctx.call("kibi:process/process", "[static]process.spawn", CanonValue.str("/bin/ok"), spawnOptions()))!!
                val qpid = (ctx.call("kibi:process/process", "[method]process.pid", q) as CanonValue.I32).value
                ctx.drop(q)
                ctx.withKernelLock {
                    val child = ctx.kernel.processes[qpid]
                    assertTrue(child == null || child.parentPid == 0, "親なしにする（${child?.parentPid}）")
                }
            }
        }
    }

    @Test
    fun `プロセスの一覧・cwd・シグナル・マウント・時計・再起動`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            scenario(h, m) { ctx ->
                val list = (ctx.call("kibi:process/process", "list-processes") as CanonValue.Items).items
                assertTrue(list.any { ((it as CanonValue.Fields).fields[0] as CanonValue.I32).value == ctx.pid })
                assertEquals(ctx.pid, (ctx.call("kibi:process/process", "self-pid") as CanonValue.I32).value)
                ok(ctx.call("kibi:process/process", "set-cwd", CanonValue.str("/bin")))
                assertEquals(CanonValue.str("/bin"), ctx.call("kibi:process/process", "get-cwd"))
                assertEquals(name(Errno.NOENT), errName(ctx.call("kibi:process/process", "set-cwd", CanonValue.str("/none"))))
                assertEquals(name(Errno.NAMETOOLONG), errName(ctx.call("kibi:process/process", "set-cwd", CanonValue.str("a".repeat(4097)))))
                // kill は無視できない
                assertEquals(name(Errno.INVAL), errName(ctx.call("kibi:process/process", "set-signal-ignored", CanonValue.Case(3, null), CanonValue.Bool(true))))
                ok(ctx.call("kibi:process/process", "set-signal-ignored", CanonValue.Case(0, null), CanonValue.Bool(true)))
                assertEquals(name(Errno.SRCH), errName(ctx.call("kibi:process/process", "kill-pid", CanonValue.I32(0), CanonValue.Case(2, null))))
                val mounts = (ctx.call("kibi:fs/mounts", "list-mounts") as CanonValue.Items).items
                assertEquals(CanonValue.str("/"), (mounts[0] as CanonValue.Fields).fields[0])
                assertEquals(name(Errno.INVAL), errName(ctx.call("kibi:fs/mounts", "mount", CanonValue.str("x".repeat(4097)), CanonValue.str("/mnt"), CanonValue.Bool(true))))
                ctx.call("kibi:computer/clock", "uptime-ticks") as CanonValue.I64
                // 起動を通らずに作ったマシンは再起動を断る
                assertEquals(name(Errno.NOTSUP), errName(ctx.call("kibi:computer/power", "reboot")))
                assertTrue(ctx.call("kibi:io/tty", "is-tty", CanonValue.I32(0)) == CanonValue.Bool(false))
            }
        }
    }

    private class TestDevice(override val methods: DeviceMethods) : Device {
        override val kind: String = "test:device"
    }

    @Test
    fun `デバイスのinvoke・start・takeとpending-callの上限はfdと合わせて数える`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            val address = "00000000-0000-4000-8000-0000000000e1"
            m.devices.attach(
                address,
                TestDevice(
                    DeviceMethods.builder()
                        .direct("d") { _, _ -> arrayOf(DeviceValue.of(42L)) }
                        .mainThread("w") { _, _ -> arrayOf(DeviceValue.of("main")) }
                        .build(),
                ),
            )
            scenario(h, m) { ctx ->
                val base = hostSize(ctx)
                assertEquals(1, (ctx.call("kibi:device/bus", "list-devices") as CanonValue.Items).items.size)
                // 36 バイトでない・ない address は detached
                for (a in listOf("short", "00000000-0000-4000-8000-0000000000ff")) {
                    val r = ctx.call("kibi:device/bus", "[static]device.open", CanonValue.str(a)) as CanonValue.Case
                    assertEquals(1, r.index)
                    assertEquals(1, (r.payload as CanonValue.Case).index)
                }
                val d = ok(ctx.call("kibi:device/bus", "[static]device.open", CanonValue.str(address)))!!
                val info = ctx.call("kibi:device/bus", "[method]device.info", d) as CanonValue.Fields
                assertEquals(CanonValue.str("test:device"), info.fields[1])
                assertEquals(2, (ctx.call("kibi:device/bus", "[method]device.methods", d) as CanonValue.Items).items.size)
                val v = ok(ctx.call("kibi:device/bus", "[method]device.invoke", d, CanonValue.str("d"), CanonValue.Items(emptyList()))) as CanonValue.Items
                assertEquals(CanonValue.Case(1, CanonValue.I64(42)), v.items[0])
                val main = ok(ctx.call("kibi:device/bus", "[method]device.invoke", d, CanonValue.str("w"), CanonValue.Items(emptyList()))) as CanonValue.Items
                assertEquals(CanonValue.Case(4, CanonValue.str("main")), main.items[0])
                // 引数の誤りは kernel(error-code)
                val tooLong = ctx.call("kibi:device/bus", "[method]device.invoke", d, CanonValue.str("m".repeat(65)), CanonValue.Items(emptyList())) as CanonValue.Case
                assertEquals(CanonValue.Case(0, CanonValue.Case(KibiErrors.code(Errno.NAMETOOLONG), null)), tooLong.payload)

                // pending-call は fd 表（p1 の口）と p2 の表を合わせて 16 個まで
                val fd = ctx.openDevice(address)
                val half = DeviceLimits.MAX_PENDING_CALLS / 2
                val fdCalls = List(half) { ctx.startDeviceCall(fd, "w", emptyList()) }
                val calls = List(DeviceLimits.MAX_PENDING_CALLS - half) {
                    ok(ctx.call("kibi:device/bus", "[method]device.start", d, CanonValue.str("w"), CanonValue.Items(emptyList())))!!
                }
                val again = ctx.call("kibi:device/bus", "[method]device.start", d, CanonValue.str("w"), CanonValue.Items(emptyList())) as CanonValue.Case
                assertEquals(CanonValue.Case(0, CanonValue.Case(KibiErrors.code(Errno.AGAIN), null)), again.payload)
                val p = ctx.call("kibi:device/bus-poll", "subscribe", calls[0])!!
                ctx.await(p)
                val taken = ctx.call("kibi:device/bus", "[method]pending-call.take", calls[0]) as CanonValue.Case
                assertEquals(1, taken.index)
                ctx.drop(p)
                for (c in calls) ctx.drop(c)
                ctx.withKernelLock {
                    val table = pro.ikili.kibicomputers.core.kernel.fd.FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink)
                    for (c in fdCalls) table.close(c)
                    table.close(fd)
                }
                ctx.drop(d)
                assertEquals(base, hostSize(ctx))
            }
        }
    }
}
