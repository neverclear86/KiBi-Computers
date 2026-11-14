package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceCompletion
import pro.ikili.kibicomputers.api.device.DeviceException
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 独自拡張 `kibi:device/bus`（#77）の p1 の import を、kibi_device.c（実際の Endive）とカーネルで確かめる。
 * テスト用のデバイス（種類 `test:dev`）を [ADDRESS] につなぎ、待ち方（巻き戻し・ブロッキング・待たない）ごとに動かす
 */
class WasiP1KibiDeviceTest {
    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-device-wasi.wasm")) { "kibi-device-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    /** 待ち方: 待機点型（巻き戻し）・ブロッキング型・待たない（`again`） */
    enum class Mode { UNWIND, BLOCK, NONE }

    private val asyncProgram: Program by lazy { Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS) }
    private val plainProgram: Program by lazy { Program.load(EndiveRuntime(), bytes) }

    /** 待たない設定（[P1Wait.NONE]）で p1 を登録するドライバー */
    private class NoWaitDriver(private val program: Program) : ProcessDriver {
        override val kind: ProcessKind = ProcessKind.BLOCKING

        override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
            WasiP1.register(ctx, builder, program.module, blocking = false)
        }

        override fun start(ctx: ProcessContext): StepResult = try {
            ctx.instance.call(WasiP1Driver.START)
            StepResult.Exit(0)
        } catch (e: WasiP1Exit) {
            StepResult.Exit(e.code)
        }

        override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult = error("resume しない")
    }

    /** テスト用のデバイス。メソッドごとの呼ばれた回数と、後で返すと決めた呼び出しを記録する */
    private inner class TestDev(private val m: Machine) : Device {
        override val kind: String = "test:dev"
        val calls: MutableMap<String, AtomicInteger> = Collections.synchronizedMap(HashMap())
        val deferred: MutableList<DeviceCompletion> = Collections.synchronizedList(ArrayList())
        val other = OtherDev()

        private fun count(name: String) = calls.getOrPut(name) { AtomicInteger() }.incrementAndGet()

        fun calls(name: String): Int = calls[name]?.get() ?: 0

        override val methods: DeviceMethods = DeviceMethods.builder()
            .direct("echo") { _, args ->
                count("echo")
                Array(args.size()) { args.get(it) }
            }
            .direct("count") { _, args -> arrayOf(DeviceValue.of(args.size().toLong())) }
            .direct("needs-int") { _, args -> arrayOf(DeviceValue.of(args.getLong(0))) }
            .direct("fail") { _, _ -> throw DeviceException("boom") }
            .direct("surrogate") { _, _ -> arrayOf(DeviceValue.of("a\uD800b")) }
            .direct("swap") { _, _ ->
                m.devices.detach(ADDRESS)
                m.devices.attach(ADDRESS, other)
                emptyArray()
            }
            .direct("unplug") { _, _ ->
                m.devices.detach(ADDRESS)
                emptyArray()
            }
            .mainThread("main-echo") { _, args ->
                count("main-echo")
                Array(args.size()) { args.get(it) }
            }
            .mainThread("defer") { ctx, _ ->
                count("defer")
                deferred += ctx.defer()
                emptyArray()
            }
            .build()
    }

    /** 同じ address に後からつなぐデバイス（メソッドはすべて直接。既定は種類も違う） */
    private class OtherDev(override val kind: String = "test:other") : Device {
        val calls = AtomicInteger()
        val mainEchoCalls = AtomicInteger()
        override val methods: DeviceMethods = DeviceMethods.builder()
            .direct("echo") { _, _ ->
                calls.incrementAndGet()
                emptyArray()
            }
            .direct("main-echo") { _, _ ->
                calls.incrementAndGet()
                mainEchoCalls.incrementAndGet()
                emptyArray()
            }
            .build()
    }

    private inner class Run(
        val h: Harness,
        val m: Machine,
        mode: Mode,
        args: List<String>,
        beforeSpawn: Run.() -> Unit = {},
        moduleBytes: ByteArray? = null,
    ) {
        private val output: Int
        private val text = StringBuilder()
        val dev = TestDev(m)
        val pid: Int
        val driver: WasiP1Driver?

        init {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/dev"))
            assertEquals(null, m.mountDev())
            assertTrue(m.devices.attach(ADDRESS, dev))
            output = m.lock.withLockValue { m.kernel.objects.add(ByteStream(256 * 1024)) }
            val program = when {
                moduleBytes != null && mode == Mode.UNWIND -> Program.loadMetered(EndiveRuntime(), moduleBytes, null, WasiP1.WAIT_IMPORTS)
                moduleBytes != null -> Program.load(EndiveRuntime(), moduleBytes)
                mode == Mode.UNWIND -> asyncProgram
                else -> plainProgram
            }
            val out = StdioSource.Object(output, HandleRights.WRITE)
            val options = SpawnOptions(
                allowUnmetered = !program.module.isFuelMetered,
                args = listOf("kd") + args,
                stdio = StdioSpec(StdioSource.Closed, out, out),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            beforeSpawn()
            driver = if (mode == Mode.NONE) null else WasiP1Driver(program.module)
            pid = m.spawn(program, driver ?: NoWaitDriver(program), options)
        }

        fun drain(): String = m.lock.withLockValue {
            val s = m.kernel.objects[output] as ByteStream
            val buf = ByteArray(s.available)
            if (buf.isNotEmpty()) s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes
            text.append(String(buf, Charsets.UTF_8))
            text.toString()
        }

        fun waitUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
            h.scheduler.tick()
            drain()
            condition()
        }

        fun state(): ProcessState = m.stateOf(pid)

        fun finish(expected: ProcessState = ProcessState.EXITED): String {
            waitUntil("終わらない: $text") { state().isTerminal }
            val out = drain()
            assertEquals(expected, state(), out)
            if (expected == ProcessState.EXITED) assertEquals(0, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }, out)
            return out
        }

        /** fd 表にある `pending-call` の数 */
        fun pendingCalls(): Int = m.lock.withLockValue {
            m.kernel.processes[pid]!!.handles.entries().values.count { m.kernel.objects[it.objectId] is PendingDeviceCall }
        }
    }

    private fun harness(mode: Mode) = Harness(execRuntime = EndiveRuntime(), asyncify = mode == Mode.UNWIND)

    private fun run(
        mode: Mode,
        vararg args: String,
        beforeSpawn: Run.() -> Unit = {},
        moduleBytes: ByteArray? = null,
        block: Run.() -> Unit,
    ) = timeout(Duration.ofSeconds(120)) {
        harness(mode).use { h -> Run(h, h.machine(fuelPerTick = 10_000_000), mode, args.toList(), beforeSpawn, moduleBytes).block() }
    }

    private fun assertLine(line: String, out: String) = assertTrue("$line\n" in out, out)

    private fun kernel(e: Errno): String = "err kernel ${e.code}"

    @Test
    fun `list-devicesはつないでいるデバイスの一覧を返す`() = run(Mode.UNWIND, "list") {
        val out = finish()
        assertLine("count 1", out)
        assertLine("dev $ADDRESS test:dev", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `open・info・methodsと、直接のメソッドのinvokeは値をそのまま往復し、失敗をcall-errorで返す`(mode: Mode) = run(mode, "basic", ADDRESS) {
        val out = finish()
        assertLine("info $ADDRESS test:dev", out)
        assertTrue(Regex("methods 9 echo:0 count:0 needs-int:0 fail:0 surrogate:0 swap:0 unplug:0 main-echo:1 defer:1\n").containsMatchIn(out), out)
        assertLine("echo ok 7 none i:-5 f:1.5 b:1 t:6869 y:3 b:0", out)
        assertLine("empty ok 0", out)
        // 対になっていないサロゲートは U+FFFD（ef bf bd）にして書く
        assertLine("surrogate ok 1 t:61efbfbd62", out)
        assertLine("fail err failed boom", out)
        assertTrue(Regex("bad err bad-arguments .+\n").containsMatchIn(out), out)
        assertLine("nsm err no-such-method", out)
        assertLine("dropped", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `メインスレッドのメソッドのinvokeは、待機点型とブロッキング型では結果まで待ち、待たない設定ではagain`(mode: Mode) = run(mode, "main", ADDRESS) {
        val out = finish()
        if (mode == Mode.NONE) {
            assertLine("main ${kernel(Errno.AGAIN)}", out)
            assertEquals(0, dev.calls("main-echo"), "待たない設定では積まない")
        } else {
            assertLine("main ok 1 i:7", out)
            assertEquals(1, dev.calls("main-echo"))
        }
        assertLine("direct ok 1 i:7", out)
    }

    @Test
    fun `待機点型はメインスレッドのメソッドを巻き戻して待ち、pending-callをfd表に持つ`() = run(Mode.UNWIND, "once", ADDRESS, "defer") {
        assertEquals(ProcessKind.WAIT_POINT, driver!!.kind)
        waitUntil("defer で待たない") { dev.deferred.isNotEmpty() && state() == ProcessState.WAITING_EVENT }
        assertTrue(driver.kibiDevice!!.hasPendingInvokeForTest)
        assertEquals(1, pendingCalls(), "待つ間は pending-call を1つ持つ")
        assertTrue(m.isAtWaitPoint, "待っている間は保存できる待機点")
        dev.deferred.single().complete(DeviceValue.of(5L))
        val out = finish()
        assertLine("once ok 1 i:5", out)
        assertEquals(1, dev.calls("defer"))
        assertFalse(driver.kibiDevice!!.hasPendingInvokeForTest)
    }

    @Test
    fun `待っている呼び出しを忘れても、巻き直しで起こしたkeyから引き、メソッドは1回だけ走る`() = run(Mode.UNWIND, "once", ADDRESS, "defer") {
        waitUntil("defer で待たない") { dev.deferred.isNotEmpty() && state() == ProcessState.WAITING_EVENT }
        driver!!.kibiDevice!!.forgetPendingInvokeForTest()
        dev.deferred.single().complete(DeviceValue.of(8L))
        val out = finish()
        assertLine("once ok 1 i:8", out)
        assertEquals(1, dev.calls("defer"))
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `invokeで待っているプロセスをkillすると、呼び出しは取り消される`(mode: Mode) = run(mode, "once", ADDRESS, "defer") {
        waitUntil("defer で待たない") { dev.deferred.isNotEmpty() }
        m.lock.withLockValue { m.killLocked(pid, KillSignal.KILL) }
        waitUntil("止まらない") { state().isTerminal }
        assertEquals(ProcessState.KILLED, state())
        assertTrue(dev.deferred.single().isCancelled, "結果が要らなくなったら取り消す")
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `startした呼び出しはpollで待ちtakeで受け取り、takeは何度でも同じ結果`(mode: Mode) = run(mode, "start", ADDRESS) {
        val out = finish()
        // 終わる前の take は none（tick が先に回ればもう終わっている）
        assertTrue(Regex("take0 (none|ok 1 i:9)\n").containsMatchIn(out), out)
        assertLine("poll 1 1", out)
        assertLine("take1 ok 1 i:9", out)
        assertLine("take2 ok 1 i:9", out)
        assertLine("take-direct ok 1 i:9", out)
        assertLine("done", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `同じaddressに種類の違うデバイスがつながると、ハンドルの呼び出しはdetachedで、infoは元のまま`(mode: Mode) = run(mode, "swap", ADDRESS) {
        val out = finish()
        assertLine("swap ok 0", out)
        assertLine("after err detached", out)
        assertLine("take err detached", out)
        assertLine("methods 0", out)
        assertLine("info $ADDRESS test:dev", out)
        assertEquals(0, dev.other.calls.get(), "種類の違うデバイスのメソッドは呼ばない")
    }

    @Test
    fun `外れた後のinvokeはdetached、methodsは空、openはdetached`() = run(Mode.UNWIND, "unplug", ADDRESS) {
        val out = finish()
        assertLine("unplug ok 0", out)
        assertLine("after err detached", out)
        assertLine("methods 0", out)
        assertLine("open err detached", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `引数とハンドルの誤りはcall-errorのkernelで返し、上限ちょうどは通る`(mode: Mode) = run(mode, "errors", ADDRESS) {
        val out = finish()
        assertLine("name64 err no-such-method", out)
        assertLine("name65 ${kernel(Errno.NAMETOOLONG)}", out)
        assertLine("name-huge ${kernel(Errno.NAMETOOLONG)}", out)
        assertLine("name-utf8 ${kernel(Errno.INVAL)}", out)
        assertLine("name-fault ${kernel(Errno.FAULT)}", out)
        assertLine("args32 ok 32", out)
        assertLine("args33 ${kernel(Errno.TOOBIG)}", out)
        assertLine("payload64k ok 1 i:2", out)
        assertLine("payload-over ${kernel(Errno.TOOBIG)}", out)
        assertLine("tag ${kernel(Errno.INVAL)}", out)
        assertLine("text-utf8 ${kernel(Errno.INVAL)}", out)
        assertLine("text-fault ${kernel(Errno.FAULT)}", out)
        assertLine("args-fault ${kernel(Errno.FAULT)}", out)
        assertLine("order ${kernel(Errno.NAMETOOLONG)}", out)
        assertLine("invoke-badf ${kernel(Errno.BADF)}", out)
        assertLine("invoke-badf-order ${kernel(Errno.NAMETOOLONG)}", out)
        assertLine("start-badf ${kernel(Errno.BADF)}", out)
        assertLine("take-badf ${kernel(Errno.BADF)}", out)
        assertLine("take-device ${kernel(Errno.BADF)}", out)
        assertLine("start-name ${kernel(Errno.NAMETOOLONG)}", out)
        assertLine("open35 detached", out)
        assertLine("open-huge detached", out)
        assertLine("open-upper detached", out)
        assertLine("open-none detached", out)
        assertLine("open-fault ${kernel(Errno.FAULT)}", out)
    }

    /** トラップで止まり、[message] を含む [WasmTrapException] が最後のエラー */
    private fun Run.assertTrapped(before: String, message: String) {
        val out = finish(ProcessState.CRASHED)
        assertLine(before, out)
        assertFalse("returned" in out, out)
        val e = m.rt(pid).lastError
        assertTrue(e is WasmTrapException && message in e.message!!, "$e")
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `揃っていないargsのptrは長さ0でもトラップ`(mode: Mode) = run(mode, "misaligned", ADDRESS) {
        assertTrapped("invoking", "list の場所が align 8 に揃っていない")
    }

    /** `cabi_realloc` の中の `invoke` は、待ち方・メソッドの種類に依らずトラップ（ADR 0073） */
    @ParameterizedTest
    @ValueSource(strings = ["UNWIND:echo", "UNWIND:main-echo", "BLOCK:echo", "BLOCK:main-echo", "NONE:echo", "NONE:main-echo"])
    fun `cabi_reallocの中のinvokeはトラップ`(spec: String) {
        val (mode, method) = spec.split(':')
        run(Mode.valueOf(mode), "realloc-invoke", ADDRESS, method) {
            val out = finish(ProcessState.CRASHED)
            assertFalse("returned" in out, out)
            val e = m.rt(pid).lastError
            assertTrue(e is WasmTrapException && "cabi_realloc の中で呼んだ" in e.message!!, "$e")
            assertEquals(0, dev.calls(method), "メソッドは呼ばない")
        }
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `list-devices・info・methods・start・takeはcabi_reallocの中からも呼べる`(mode: Mode) = run(mode, "realloc-ok", ADDRESS) {
        assertLine("inner-done 1", finish())
    }

    @Test
    fun `型の違うハンドルのdropはトラップ`() = run(Mode.UNWIND, "drop-type", ADDRESS) {
        assertTrapped("dropping", "[resource-drop]pending-call: 不正なハンドル")
    }

    @Test
    fun `不正なハンドルのinfoはトラップ`() = run(Mode.UNWIND, "info-bad") {
        assertTrapped("calling", "[method]device.info: 不正なハンドル")
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `startを16個積んだままでもinvokeでき、17個目はagain、invokeの後にfdは残らない`(mode: Mode) = run(mode, "limit", ADDRESS) {
        val out = finish()
        assertLine("start17 ${kernel(Errno.AGAIN)}", out)
        assertLine("main ok 1 i:3", out)
        assertLine("main2 ok 1 i:3", out)
        val probe = Regex("probe (\\d+)\n").find(out)!!.groupValues[1]
        assertLine("probe2 $probe", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `fd表が満杯でも直接のメソッドはinvokeでき、openとstartと待機点型のメインスレッドのメソッドはmfile`(mode: Mode) = run(mode, "mfile", ADDRESS) {
        val out = finish()
        assertLine("filled 1", out)
        // 満杯になるまで回した open の最後の失敗（open は fd を1つ使う）
        assertLine("open ${kernel(Errno.MFILE)}", out)
        assertLine("direct ok 1 i:1", out)
        // ブロッキング型は fd を使わずに待つ
        if (mode == Mode.UNWIND) assertLine("main ${kernel(Errno.MFILE)}", out) else assertLine("main ok 1 i:1", out)
        // start は直接のメソッドでもメインスレッドのメソッドでも pending-call の fd が要る（どの待ち方でも）
        assertLine("start-direct ${kernel(Errno.MFILE)}", out)
        assertLine("start-main ${kernel(Errno.MFILE)}", out)
        // main の invoke（ブロッキング型だけ走る）の1回だけで、満杯で断った start はメソッドを走らせない
        assertEquals(if (mode == Mode.UNWIND) 0 else 1, dev.calls("main-echo"))
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `devのdeviceをpath_openで開いたfdもdeviceとして使える`(mode: Mode) = run(mode, "devfs", ADDRESS) {
        val out = finish()
        assertLine("devfs ok 1 i:4", out)
        assertLine("info $ADDRESS test:dev", out)
    }

    @Test
    fun `invokeはデバイスを1回だけ引き、引いた後に付け替えても引き直さない`() {
        var swapped = false
        val replacement = OtherDev("test:dev")
        run(Mode.UNWIND, "main", ADDRESS, beforeSpawn = {
            h.scheduler.hooks.afterDeviceInvokeResolve = { machine, _ ->
                // 最初の invoke（main-echo）で引いた後に、同じ address を、同じ種類で全部直接のメソッドのデバイスに付け替える
                if (!swapped) {
                    swapped = true
                    machine.devices.detach(ADDRESS)
                    machine.devices.attach(ADDRESS, replacement)
                }
            }
        }) {
            val out = finish()
            // 引いたのは元の test:dev（メインスレッドのメソッド）。それが外れたので detached になり、引き直せば見つかる
            // 付け替えた先（同じ種類で、main-echo は直接）のメソッドは呼ばない
            assertLine("main err detached", out)
            assertEquals(0, dev.calls("main-echo"))
            assertEquals(0, replacement.mainEchoCalls.get())
        }
    }

    /**
     * モジュールの start 関数（インスタンスを作っている途中。`LOADING`）の中の `invoke` は、巻き戻せない（canUnwind が false）ので、
     * 待機点型のモジュールでもブロッキングの道（`ctx.invokeDevice`）で結果まで待つ。fd（`pending-call`）も作らない
     */
    @Test
    fun `モジュールのstart関数の中のinvokeは、待機点型でも巻き戻さずにブロッキングで結果まで待つ`() {
        val module = startFunctionModule()
        run(Mode.UNWIND, moduleBytes = module) {
            val out = finish()
            // _start が invoke の結果の判別子（0 = ok）を終了コードにする。finish は終了コード 0 を確かめている
            assertEquals(1, dev.calls("main-echo"), "メインスレッドのメソッドが1回走った: $out")
            assertEquals(0, pendingCalls())
            assertFalse(driver!!.kibiDevice!!.hasPendingInvokeForTest, "巻き戻していない")
        }
    }

    /**
     * start 関数が `open(ADDRESS)` → `invoke(handle, "main-echo", [])` を呼び、`_start` がその結果の判別子を `proc_exit` の終了コードにするモジュール。
     * `cabi_realloc` は固定の場所を返す（結果は読まない）
     */
    private fun startFunctionModule(): ByteArray {
        val i32 = WasmBinary.I32
        fun c(n: Int) = WasmBinary.code(0x41, WasmBinary.sleb(n.toLong()))
        val bus = WasiP1KibiDevice.MODULE
        val bin = WasmBinary()
        val open = bin.importFunc(bus, "[static]device.open", listOf(i32, i32, i32), emptyList())
        val invoke = bin.importFunc(bus, "[method]device.invoke", List(6) { i32 }, emptyList())
        val exit = bin.importFunc("wasi_snapshot_preview1", "proc_exit", listOf(i32), emptyList())
        bin.memory(1)
        bin.data(0x100, ADDRESS.toByteArray())
        bin.data(0x140, "main-echo".toByteArray())
        val realloc = bin.func(List(4) { i32 }, listOf(i32), c(0x1000))
        val startFn = bin.func(
            emptyList(),
            emptyList(),
            WasmBinary.code(
                c(0x100), c(36), c(0x200), 0x10, open,
                // self = open の結果の ok（オフセット 4）
                c(0x200), 0x28, 0x02, 0x04,
                c(0x140), c(9), c(0x300), c(0), c(0x400), 0x10, invoke,
            ),
        )
        val entry = bin.func(emptyList(), emptyList(), WasmBinary.code(c(0x400), 0x2d, 0x00, 0x00, 0x10, exit))
        bin.exportFunc("cabi_realloc", realloc)
        bin.exportFunc("_start", entry)
        bin.start(startFn)
        return bin.build()
    }

    private companion object {
        const val ADDRESS = "00000000-0000-4000-8000-0000000000a7"
    }
}
