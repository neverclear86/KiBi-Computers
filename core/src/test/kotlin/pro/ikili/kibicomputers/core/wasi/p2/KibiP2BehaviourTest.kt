package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Timeout
import pro.ikili.kibicomputers.core.access.AccessPort
import pro.ikili.kibicomputers.core.access.AccessRequest
import pro.ikili.kibicomputers.core.access.AccessRequestLimiter
import pro.ikili.kibicomputers.core.access.AccessRequestRules
import pro.ikili.kibicomputers.core.access.AccessRules
import pro.ikili.kibicomputers.core.access.AccessView
import pro.ikili.kibicomputers.core.access.PlayerAccess
import pro.ikili.kibicomputers.core.access.PlayerRef
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.HostResourceTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tty.Tty
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
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `kibi:*` の p2 の振る舞い（#685。ADR 0685）。コンポーネントのプロセスの中から、ホストの関数の本体を直接呼んで確かめる
 * （[WasiP2StreamTest] と同じ形）
 */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
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
                // p2 の fds の parent は 0..2 だけ（process.wit の fd-source.parent のとおり bad-descriptor）
                assertEquals(
                    name(Errno.BADF),
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

    @Test
    fun `accessは口がなければnot-supportedで、あればaccess-infoを返し、依頼を受け付ける`() = timeout(limit) {
        Harness().use { h ->
            scenario(h, machine(h)) { ctx ->
                assertEquals(name(Errno.NOTSUP), errName(ctx.call("kibi:computer/access", "get-access")))
                assertEquals(name(Errno.NOTSUP), errName(ctx.call("kibi:computer/access", "request-add", CanonValue.str("Alex"))))
            }
            val alex = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Alex")
            val delivered = java.util.concurrent.CopyOnWriteArrayList<AccessRequest>()
            val port = object : AccessPort {
                override val limiter = AccessRequestLimiter()

                override fun view() = AccessView(PlayerAccess.ownedBy(alex).addPending("Bob", System.currentTimeMillis(), AccessRules.DEFAULT).access, AccessRules.DEFAULT)

                override fun requestRules() = AccessRequestRules(60_000, 1)

                override fun deliver(request: AccessRequest) {
                    delivered += request
                }
            }
            val m = h.machine(fuelPerTick = 10_000_000, environment = MachineEnvironment.builder().access(port).build())
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            scenario(h, m) { ctx ->
                val info = ok(ctx.call("kibi:computer/access", "get-access")) as CanonValue.Fields
                assertEquals(CanonValue.Case(1, null), info.fields[0])
                assertEquals(CanonValue.some(CanonValue.Fields(listOf(CanonValue.str("Alex"), CanonValue.str(alex.id.toString())))), info.fields[1])
                assertEquals(CanonValue.Items(emptyList()), info.fields[2])
                assertEquals(CanonValue.Items(listOf(CanonValue.str("bob"))), info.fields[3])
                assertEquals(name(Errno.INVAL), errName(ctx.call("kibi:computer/access", "request-add", CanonValue.str("x".repeat(65)))))
                assertEquals(name(Errno.INVAL), errName(ctx.call("kibi:computer/access", "request-remove", CanonValue.str("1-2-3-4-5"))))
                ok(ctx.call("kibi:computer/access", "request-transfer", CanonValue.str("Steve")))
                ok(ctx.call("kibi:computer/access", "request-transfer", CanonValue.str("steve")))
                assertEquals(name(Errno.AGAIN), errName(ctx.call("kibi:computer/access", "request-add", CanonValue.str("Carl"))))
            }
            repeat(3) { h.scheduler.tick() }
            assertEquals(listOf("Steve"), delivered.map { it.argument })
        }
    }

    @Test
    fun `マシン全体のキューの上限はトラップでなくnfileのerrで返る`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            scenario(h, m) { ctx ->
                val base = hostSize(ctx)
                val queues = ArrayList<CanonValue>()
                var failure: String? = null
                // マシンのキューの上限（EventLimits.MAX_QUEUES）まで開く。超えたら nfile の err（トラップにならない）
                for (i in 0..EventLimits.MAX_QUEUES + 1) {
                    val r = ctx.call("kibi:kernel/events", "[static]event-queue.open", CanonValue.Items(emptyList()), CanonValue.NONE) as CanonValue.Case
                    if (r.index == 0) {
                        queues += r.payload!!
                    } else {
                        failure = errName(r)
                        break
                    }
                }
                assertEquals(name(Errno.NFILE), failure)
                assertTrue(queues.isNotEmpty() && queues.size <= EventLimits.MAX_QUEUES, "${queues.size}")
                for (q in queues) ctx.drop(q)
                assertEquals(base, hostSize(ctx))
            }
        }
    }

    @Test
    fun `subscribeは表が満杯だとトラップになり、親に子の数を残さない`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            scenario(h, m) { ctx ->
                val q = ok(ctx.call("kibi:kernel/events", "[static]event-queue.open", CanonValue.Items(emptyList()), CanonValue.NONE))!!
                val t = ok(ctx.call("kibi:kernel/timers", "[static]timer.create", CanonValue.NONE))!!
                val fillers = ctx.withKernelLock {
                    val table = P2.tableLocked(ctx)
                    List(HostResourceTable.LIMIT - table.size) { table.add(MarkerEntry("filler")) }
                }
                // 戻り値が pollable だけでエラーを返せないので、満杯なら ResourceLimitException が P2.guard でトラップになる
                assertThrows<WasmTrapException> { ctx.call("kibi:kernel/events-poll", "subscribe", q) }
                assertThrows<WasmTrapException> { ctx.call("kibi:kernel/timers-poll", "subscribe", t) }
                ctx.withKernelLock { for (r in fillers) P2.tableLocked(ctx).remove(r) }
                // 子が残っていない（残っていれば drop がトラップになる）
                ctx.drop(q)
                ctx.drop(t)
            }
        }
    }

    @Test
    fun `タイマーのpollableは、キューなしならそろい、キューありならそろわない`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            scenario(h, m) { ctx ->
                val q = ok(ctx.call("kibi:kernel/events", "[static]event-queue.open", CanonValue.Items(listOf(CanonValue.str("timer"))), CanonValue.NONE))!!
                val plain = ok(ctx.call("kibi:kernel/timers", "[static]timer.create", CanonValue.NONE))!!
                val queued = ok(ctx.call("kibi:kernel/timers", "[static]timer.create", CanonValue.some(CanonValue.Fields(listOf(q, CanonValue.I64(7))))))!!
                val pp = ctx.call("kibi:kernel/timers-poll", "subscribe", plain)!!
                val pq = ctx.call("kibi:kernel/timers-poll", "subscribe", queued)!!
                val qp = ctx.call("kibi:kernel/events-poll", "subscribe", q)!!
                fun ready(p: CanonValue) = (ctx.call("wasi:io/poll", "[method]pollable.ready", p) as CanonValue.Bool).value
                assertFalse(ready(pp), "動かす前はそろわない")
                ok(ctx.call("kibi:kernel/timers", "[method]timer.set-after", plain, CanonValue.I64(1_000_000), CanonValue.I64(0)))
                ok(ctx.call("kibi:kernel/timers", "[method]timer.set-after", queued, CanonValue.I64(1_000_000), CanonValue.I64(0)))
                ctx.await(pp)
                assertTrue(ready(pp))
                // キューにイベントが届いた（キューを渡したタイマーが鳴った）後も、そのタイマーの pollable はそろわない
                ctx.await(qp)
                assertFalse(ready(pq))
                for (p in listOf(pp, pq, qp)) ctx.drop(p)
                for (v in listOf(plain, queued, q)) ctx.drop(v)
            }
        }
    }

    /** 子 [p]（p2 のハンドル）のカーネルの ProcessObject の id */
    private fun childObject(ctx: ProcessContext, p: CanonValue): Int =
        ctx.withKernelLock { (P2.tableLocked(ctx)[(p as CanonValue.Handle).value] as KernelEntry).objectId }

    /** 子の終わりを待つ */
    private fun ProcessContext.waitChild(p: CanonValue) {
        val pp = call("kibi:process/process-poll", "subscribe", p)!!
        await(pp)
        drop(pp)
    }

    @Test
    fun `fdとp2のハンドルで同じ子を持つとき、片方を閉じても子は付け替えられず、最後の1つで刈り取る`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            scenario(h, m) { ctx ->
                fun fdTable() = FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink)
                fun pidOf(p: CanonValue) = (ctx.call("kibi:process/process", "[method]process.pid", p) as CanonValue.I32).value
                fun exists(pid: Int) = ctx.withKernelLock { ctx.kernel.processes[pid] != null }

                // p2 のハンドルを先に閉じる: fd のハンドルが残るので、子は残る（刈り取らない）
                val a = ok(ctx.call("kibi:process/process", "[static]process.spawn", CanonValue.str("/bin/ok"), spawnOptions()))!!
                val aPid = pidOf(a)
                ctx.waitChild(a)
                val aFd = ctx.withKernelLock {
                    val id = childObject(ctx, a)
                    ctx.kernel.objects.retain(id)
                    fdTable().install(id)
                }
                ctx.drop(a)
                assertTrue(exists(aPid), "fd のハンドルが残るのに刈り取った")
                ctx.withKernelLock { fdTable().close(aFd) }
                assertFalse(exists(aPid), "最後のハンドル（fd）を閉じたら刈り取る")

                // fd のハンドルを先に閉じる: p2 のハンドルが残るので、子は残る
                val b = ok(ctx.call("kibi:process/process", "[static]process.spawn", CanonValue.str("/bin/ok"), spawnOptions()))!!
                val bPid = pidOf(b)
                ctx.waitChild(b)
                val bFd = ctx.withKernelLock {
                    val id = childObject(ctx, b)
                    ctx.kernel.objects.retain(id)
                    fdTable().install(id)
                }
                ctx.withKernelLock { fdTable().close(bFd) }
                assertTrue(exists(bPid), "p2 のハンドルが残るのに刈り取った")
                ctx.drop(b)
                assertFalse(exists(bPid), "最後のハンドル（p2）を閉じたら刈り取る")
            }
        }
    }

    @Test
    fun `写しの中で、processのハンドルを持つコンポーネントの親を殺しても、元のマシンは変わらない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            val ready = CountDownLatch(1)
            val release = CountDownLatch(1)
            val childObj = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    val p = ok(ctx.call("kibi:process/process", "[static]process.spawn", CanonValue.str("/bin/ok"), spawnOptions()))!!
                    ctx.waitChild(p)
                    childObj.set(childObject(ctx, p))
                    ready.countDown()
                    release.await()
                    ctx.drop(p)
                } catch (t: Throwable) {
                    failure.set(t)
                }
                StepResult.Exit(0)
            }
            val options = SpawnOptions(stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed), fds = SpawnOptions.ROOT_PREOPEN)
            h.releaseOnClose { release.countDown() }
            val pid = m.spawn(empty, driver, options, 0)
            eventually(60_000, "子を作らない") {
                h.scheduler.tick()
                ready.count == 0L
            }
            val obj = childObj.get()
            val before = m.lock.withLockValue { m.kernel.objects.refCount(obj) }
            assertTrue(before >= 2, "子自身と親のハンドルが参照する（$before）")

            val copy = m.captureCopy(CapturePolicy.KILL_TRANSIENT)!!
            assertTrue(pid in copy.killedPids, "コンポーネントのプロセスは写しの中で殺す")
            val killed = copy.processes[pid]!!
            assertEquals(ProcessState.KILLED, killed.state)
            assertEquals(0, killed.hostResources.size, "写しの表は閉じる")
            assertTrue(copy.objects.refCount(obj) < before, "写しの中で参照を返す（${copy.objects.refCount(obj)} < $before）")

            // 元は変わらない: 表も参照の数もそのまま、プロセスも生きている
            m.lock.withLockValue {
                assertEquals(before, m.kernel.objects.refCount(obj))
                assertTrue(m.kernel.processes[pid]!!.hostResources.size >= 1)
                assertFalse(m.kernel.processes[pid]!!.state.isTerminal)
            }
            release.countDown()
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            failure.get()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            m.stop()
        }
    }

    @Test
    fun `pipe-streamsで持てるパイプの端はfdの上限と同じ数までで、超えたらmfile`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            scenario(h, m) { ctx ->
                val base = hostSize(ctx)
                val ends = ArrayList<CanonValue>()
                var failure: String? = null
                for (i in 0..DEFAULT_HANDLE_LIMIT) {
                    val r = ctx.call("kibi:io/pipe-streams", "create") as CanonValue.Case
                    if (r.index != 0) {
                        failure = errName(r)
                        break
                    }
                    val (input, output) = (r.payload as CanonValue.Fields).fields
                    // 書いてから書く端を drop しても、読む端は数える（中身を持ったまま残るので）
                    ok(ctx.call("wasi:io/streams", "[method]output-stream.blocking-write-and-flush", output, CanonValue.Bytes("x".toByteArray())))
                    ctx.drop(output)
                    ends += input
                    // 読む端だけなら 1 本につき 1 つ。上限まで作れる
                }
                assertEquals(name(Errno.MFILE), failure)
                // 読む端は上限 - 1 個（次の create には 2 つ分の空きが要る）
                assertEquals(DEFAULT_HANDLE_LIMIT - 1, ends.size)
                // 表（4096）にはまだ空きがある
                assertTrue(HostResourceTable.LIMIT - hostSize(ctx) >= 2)
                ctx.drop(ends.removeAt(0))
                ctx.drop(ends.removeAt(0))
                val pair = ok(ctx.call("kibi:io/pipe-streams", "create")) as CanonValue.Fields
                ends += pair.fields
                for (e in ends) ctx.drop(e)
                assertEquals(base, hostSize(ctx))
            }
        }
    }

    @Test
    fun `spawn-with-handlesでdescriptorとprocessを渡すと子のfdに権利つきで入り、process-killで子を止められる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            write(m, "/bin/prompt", P2Testing.wrap("prompt-wasi"))
            scenario(h, m) { ctx ->
                val base = hostSize(ctx)
                fun pidOf(p: CanonValue) = (ctx.call("kibi:process/process", "[method]process.pid", p) as CanonValue.I32).value
                fun exists(pid: Int) = ctx.withKernelLock { ctx.kernel.processes[pid] != null }

                val done = ok(ctx.call("kibi:process/process", "[static]process.spawn", CanonValue.str("/bin/ok"), spawnOptions()))!!
                val donePid = pidOf(done)
                ctx.waitChild(done)
                val doneObj = childObject(ctx, done)

                val dirs = (ctx.call("wasi:filesystem/preopens", "get-directories") as CanonValue.Items).items
                val root = (dirs[0] as CanonValue.Fields).fields[0]
                fun openAt(path: String, openFlags: Int, flags: Int) = ok(
                    ctx.call(
                        "wasi:filesystem/types", "[method]descriptor.open-at", root,
                        CanonValue.Flags(0), CanonValue.str(path), CanonValue.Flags(openFlags), CanonValue.Flags(flags),
                    ),
                )!!
                fun entryOf(v: CanonValue) = ctx.withKernelLock { P2.tableLocked(ctx)[(v as CanonValue.Handle).value] as DescriptorEntry }
                // flags の read → READ、read・write → READ|WRITE、どちらもない（mutate-directory だけ）→ 開いたときの権利
                val readOnly = openAt("bin/ok", 0, 1)
                val readWrite = openAt("bin/ok", 0, 3)
                val mutateOnly = openAt("bin", 2, 32)
                val mutateHandle = entryOf(mutateOnly).handle

                val pipe = ok(ctx.call("kibi:io/pipe-streams", "create")) as CanonValue.Fields
                val (input, output) = pipe.fields
                val c = ok(
                    ctx.call(
                        "kibi:process/process-poll", "spawn-with-handles", CanonValue.str("/bin/prompt"), spawnOptions(),
                        CanonValue.Items(
                            listOf(
                                handleAssign(0, 0, input),
                                handleAssign(4, 2, readOnly),
                                handleAssign(5, 2, readWrite),
                                handleAssign(6, 2, mutateOnly),
                                handleAssign(7, 3, done),
                            ),
                        ),
                    ),
                )!!
                val cPid = pidOf(c)
                // 子は標準入力（パイプ。書く端はこちらが持つ）を待っているので、まだ生きている
                ctx.withKernelLock {
                    val child = checkNotNull(ctx.kernel.processes[cPid])
                    fun at(fd: Int) = checkNotNull(child.handles[fd]) { "子の fd $fd がない" }
                    assertEquals(entryOf(readOnly).handle.objectId, at(4).objectId)
                    assertEquals(HandleRights.READ, at(4).rights)
                    assertEquals(HandleRights.READ or HandleRights.WRITE, at(5).rights)
                    assertEquals(mutateHandle.objectId, at(6).objectId)
                    assertEquals(mutateHandle.rights, at(6).rights)
                    assertEquals(doneObj, at(7).objectId)
                    assertTrue(ctx.kernel.objects[at(7).objectId] is ProcessObject)
                }
                // 刈り取りは親のハンドルだけで決まる（p1 と同じ。ADR 0074）: 子に渡していても、親の最後のハンドルを drop したら刈り取る
                ctx.drop(done)
                assertFalse(exists(donePid), "親の最後のハンドルを drop しても刈り取らない")

                // enum の signal: kill（3）で止める
                ok(ctx.call("kibi:process/process", "[method]process.kill", c, CanonValue.Case(3, null)))
                ctx.waitChild(c)
                val status = ok(ctx.call("kibi:process/process", "[method]process.status", c)) as CanonValue.Case
                assertEquals(1, status.index)
                val exit = status.payload as CanonValue.Case
                assertEquals(1, exit.index, "シグナルで終わる: $exit")
                assertEquals(CanonValue.some(CanonValue.Case(3, null)), exit.payload)

                for (v in listOf(c, input, output, readOnly, readWrite, mutateOnly, root)) ctx.drop(v)
                assertEquals(base, hostSize(ctx))
            }
        }
    }

    @Test
    fun `タイマーのset-at・cancel、TTYのモード・大きさ・前面、unmount・list-disks`() = timeout(limit) {
        Harness().use { h ->
            val m = machine(h)
            m.vfs.createDirectory(VfsPath.parse("/mnt"))
            m.vfs.mount(VfsPath.parse("/mnt"), TestMemoryFileSystem(), readOnly = false, source = "test:mnt")
            val tty = m.lock.withLockValue { m.kernel.objects.add(Tty(64, 64)) }
            scenario(h, m, stdout = tty) { ctx ->
                val base = hostSize(ctx)
                // set-at: 過ぎた時刻なら、すぐ鳴る
                val t = ok(ctx.call("kibi:kernel/timers", "[static]timer.create", CanonValue.NONE))!!
                val tp = ctx.call("kibi:kernel/timers-poll", "subscribe", t)!!
                ok(ctx.call("kibi:kernel/timers", "[method]timer.set-at", t, CanonValue.I64(0), CanonValue.I64(0)))
                ctx.await(tp)
                assertTrue((ok(ctx.call("kibi:kernel/timers", "[method]timer.read", t)) as CanonValue.I64).value >= 1)
                // cancel: 止めたタイマーは鳴らない
                val u = ok(ctx.call("kibi:kernel/timers", "[static]timer.create", CanonValue.NONE))!!
                ok(ctx.call("kibi:kernel/timers", "[method]timer.set-after", u, CanonValue.I64(1_000_000_000_000_000), CanonValue.I64(0)))
                ok(ctx.call("kibi:kernel/timers", "[method]timer.cancel", u))
                val up = ctx.call("kibi:kernel/timers-poll", "subscribe", u)!!
                assertEquals(CanonValue.Bool(false), ctx.call("wasi:io/poll", "[method]pollable.ready", up))
                assertEquals(CanonValue.I64(0), ok(ctx.call("kibi:kernel/timers", "[method]timer.read", u)))
                for (v in listOf(tp, up, t, u)) ctx.drop(v)

                // TTY（fd 1）
                assertEquals(CanonValue.Bool(true), ctx.call("kibi:io/tty", "is-tty", CanonValue.I32(1)))
                val mode = ok(ctx.call("kibi:io/tty", "get-mode", CanonValue.I32(1))) as CanonValue.Flags
                ok(ctx.call("kibi:io/tty", "set-mode", CanonValue.I32(1), CanonValue.Flags(0)))
                assertEquals(CanonValue.Flags(0), ok(ctx.call("kibi:io/tty", "get-mode", CanonValue.I32(1))))
                ok(ctx.call("kibi:io/tty", "set-mode", CanonValue.I32(1), mode))
                assertEquals(mode, ok(ctx.call("kibi:io/tty", "get-mode", CanonValue.I32(1))))
                assertEquals(3, (ok(ctx.call("kibi:io/tty", "get-size", CanonValue.I32(1))) as CanonValue.Fields).fields.size)
                ok(ctx.call("kibi:io/tty", "set-foreground", CanonValue.I32(1), CanonValue.I32(ctx.pid)))
                assertEquals(CanonValue.I32(ctx.pid), ok(ctx.call("kibi:io/tty", "get-foreground", CanonValue.I32(1))))
                // TTY でない fd は no-tty、開いていない fd も no-tty（p1 と同じ）
                assertEquals(1, (ctx.call("kibi:io/tty", "get-mode", CanonValue.I32(3)) as CanonValue.Case).index)
                // 0..2 でない fd でも、TTY なら受ける
                val fd = 5
                ctx.withKernelLock { FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink).installAt(fd, HandleEntry(tty)) }
                assertEquals(CanonValue.Bool(true), ctx.call("kibi:io/tty", "is-tty", CanonValue.I32(fd)))
                assertEquals(CanonValue.I32(ctx.pid), ok(ctx.call("kibi:io/tty", "get-foreground", CanonValue.I32(fd))))
                ctx.withKernelLock { FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink).close(fd) }

                // マウント
                fun points() = (ctx.call("kibi:fs/mounts", "list-mounts") as CanonValue.Items).items.map { (it as CanonValue.Fields).fields[0] }
                assertTrue(CanonValue.str("/mnt") in points())
                ok(ctx.call("kibi:fs/mounts", "unmount", CanonValue.str("/mnt")))
                assertFalse(CanonValue.str("/mnt") in points())
                assertEquals(1, (ctx.call("kibi:fs/mounts", "unmount", CanonValue.str("/mnt")) as CanonValue.Case).index)
                assertEquals(name(Errno.NAMETOOLONG), errName(ctx.call("kibi:fs/mounts", "unmount", CanonValue.str("a".repeat(4097)))))
                assertEquals(0, (ctx.call("kibi:fs/mounts", "list-disks") as CanonValue.Items).items.size)
                assertEquals(base, hostSize(ctx))
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
