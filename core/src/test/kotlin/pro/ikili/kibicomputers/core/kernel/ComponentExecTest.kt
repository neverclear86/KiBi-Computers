package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Driver
import pro.ikili.kibicomputers.core.wasi.p2.P2Testing
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * コンポーネント（layer 1）を `exec` で動かす（#687）。読み込みは `ProgramCache` で全マシンが共有し（計画は不変）、
 * `KernelDrivers.DEFAULT` が p2 のコマンドとして `WasiP2Driver`（`BLOCKING`）で動かす
 */
class ComponentExecTest {
    private val limit = Duration.ofSeconds(120)

    /** wasmtime のアダプターで包んだ hello-wasi（p2 のコマンド） */
    private val hello: ByteArray by lazy { P2Testing.wrap("hello-wasi") }

    private fun write(m: Machine, path: String, bytes: ByteArray) {
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
    }

    private fun machine(h: Harness): Machine {
        val m = h.machine(fuelPerTick = 10_000_000)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        return m
    }

    /** [path] を exec して、標準出力を集めながら終わるまで待つ。標準出力と終了コード */
    private fun execAndWait(h: Harness, m: Machine, path: String, drivers: ProcessDriverFactory = KernelDrivers.DEFAULT): Pair<String, Int?> {
        val out = P2Testing.outputStream(m)
        val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(out, HandleRights.WRITE), StdioSource.Object(out, HandleRights.WRITE))
        val pid = m.exec(path, drivers, SpawnOptions(stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN))
        val text = StringBuilder()
        eventually(60_000, "終わらない") {
            h.scheduler.tick()
            text.append(P2Testing.drain(m, out))
            m.stateOf(pid).isTerminal
        }
        text.append(P2Testing.drain(m, out))
        return text.toString() to m.lock.withLockValue { m.kernel.processes[pid]?.exitCode }
    }

    @Test
    fun `p2のコマンドのコンポーネントをexecで動かす`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/hello", hello)
            val (output, code) = execAndWait(h, m, "/bin/hello")
            assertEquals("Hello from WASI\n", output)
            assertEquals(0, code)
            assertEquals(1, h.scheduler.programs.loads.get())
        }
    }

    @Test
    fun `自己診断の最小のp2のコマンドはexecで0で終わる`() = timeout(limit) {
        assertTrue(RuntimeSelfCheck.loadsComponentCommand())
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
            assertEquals("" to 0, execAndWait(h, m, "/bin/ok"))
        }
    }

    @Test
    fun `同じコンポーネントは別のマシン・別のドライバーの集合からexecしても1回だけ読み込む`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val a = machine(h)
            val b = machine(h)
            write(a, "/bin/hello", hello)
            write(b, "/bin/hello", hello)
            assertEquals("Hello from WASI\n", execAndWait(h, a, "/bin/hello").first)
            // 止まりうる import の違うドライバーの集合（p1 なら別のキーになる）でも、コンポーネントは同じキー
            val other = object : ProcessDriverFactory {
                override val waitImports: Set<String> get() = setOf("x.wait")

                override fun create(program: Program): ProcessDriver = KernelDrivers.DEFAULT.create(program)
            }
            assertEquals("Hello from WASI\n", execAndWait(h, b, "/bin/hello", other).first)
            assertEquals(1, h.scheduler.programs.loads.get())
        }
    }

    @Test
    fun `runをexportしないコンポーネントはNOEXEC、読み込みは共有したまま`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/comp", WitTool.wat("(component (core module \$m) (core instance (instantiate \$m)))"))
            val e = assertThrows<FsException> { m.exec("/bin/comp", KernelDrivers.DEFAULT) }
            assertEquals(Errno.NOEXEC, e.errno)
            assertTrue(e.message!!.contains("wasi:cli/run")) { e.message }
            // 読み込みには成功しているので、2 回目は読み込み直さずにドライバーが断る
            assertEquals(Errno.NOEXEC, assertThrows<FsException> { m.exec("/bin/comp", KernelDrivers.DEFAULT) }.errno)
            assertEquals(1, h.scheduler.programs.loads.get())
        }
    }

    @Test
    fun `リンクできないコンポーネントはNOEXECで、失敗を覚えて読み込み直さない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            // p3（wasi:*@0.3.x）は #268 まで読み込みで断る。ホストにない import も同じ
            write(m, "/bin/p3", WitTool.wat("(component (import \"wasi:cli/environment@0.3.0\" (instance)))"))
            write(m, "/bin/unknown", WitTool.wat("(component (import \"example:none/iface@1.0.0\" (instance (export \"f\" (func)))))"))
            for (path in listOf("/bin/p3", "/bin/unknown")) {
                val before = h.scheduler.programs.loads.get()
                assertEquals(Errno.NOEXEC, assertThrows<FsException> { m.exec(path, KernelDrivers.DEFAULT) }.errno)
                assertEquals(Errno.NOEXEC, assertThrows<FsException> { m.exec(path, KernelDrivers.DEFAULT) }.errno)
                assertEquals(before + 1, h.scheduler.programs.loads.get(), path)
            }
        }
    }

    /** [interrupt] が立っている間、読み込みを割り込まれたものとして断るランタイム（コンパイル用のスレッドの割り込みの代わり） */
    private class InterruptingRuntime(private val inner: WasmRuntime = EndiveRuntime()) : WasmRuntime {
        @Volatile
        var interrupt = true
        override val name: String get() = "interrupting-" + inner.name

        override fun load(bytes: ByteArray): WasmModule {
            if (interrupt) throw WasmTrapException(WasmTrapKind.INTERRUPTED, "テストの割り込み")
            return inner.load(bytes)
        }
    }

    @Test
    fun `読み込みが割り込まれたらAGAINで、失敗として覚えない`() = timeout(limit) {
        val runtime = InterruptingRuntime()
        Harness(execRuntime = runtime).use { h ->
            val m = machine(h)
            write(m, "/bin/hello", hello)
            val e = assertThrows<FsException> { m.exec("/bin/hello", KernelDrivers.DEFAULT) }
            assertEquals(Errno.AGAIN, e.errno)
            // マシンは壊れていない。割り込みは覚えないので、次は読み込み直して動く
            runtime.interrupt = false
            assertEquals("Hello from WASI\n", execAndWait(h, m, "/bin/hello").first)
            assertEquals(2, h.scheduler.programs.loads.get())
        }
    }

    /** 読み込ませた core モジュールのバイト数を記録するランタイム（燃料の計測コードを挿入した後の大きさを知るため） */
    private class CountingRuntime(private val inner: WasmRuntime = EndiveRuntime()) : WasmRuntime {
        val sizes = ArrayList<Int>()
        override val name: String get() = "counting-" + inner.name

        override fun load(bytes: ByteArray): WasmModule {
            sizes += bytes.size
            return inner.load(bytes)
        }
    }

    /** [dataBytes] バイトのデータを持つ core モジュールを、[count] 回（同じ中身で）定義して、インスタンス化するコンポーネントの WAT */
    private fun modulesText(count: Int, dataBytes: Int): String {
        val data = "a".repeat(dataBytes)
        val modules = (1..count).joinToString("\n") { "(core module (memory 1) (data (i32.const 0) \"$data\") (func (export \"f\")))" }
        val instances = (0 until count).joinToString("\n") { "(core instance (instantiate $it))" }
        return "(component\n$modules\n$instances)"
    }

    private fun items(p: ComponentProgram): Long = p.plan.steps.size.toLong() + p.plan.lifts.size + p.plan.lowers.size + p.plan.resources.size

    private fun loadCounting(text: String): Pair<ComponentProgram, CountingRuntime> {
        val runtime = CountingRuntime()
        return ComponentProgram.load(runtime, WitTool.wat(text), ComponentHost.NONE, metered = true) to runtime
    }

    @Test
    fun `コンポーネントの重さはランタイムに渡したバイト数の合計と、モジュールの数と、計画の項目の数から見積もる`() {
        val (p, runtime) = loadCounting(modulesText(2, 1000))
        // 同じ中身は 1 つ。重さは「渡したバイト数の合計 + モジュール数 × 固定 + 項目数 × 項目の固定」ちょうど
        assertEquals(1, p.moduleCount)
        assertEquals(1, runtime.sizes.size)
        val expected = runtime.sizes.sumOf { it.toLong() } + p.moduleCount * ComponentProgram.WEIGHT_PER_MODULE + items(p) * ComponentProgram.WEIGHT_PER_PLAN_ITEM
        assertEquals(expected, p.cacheWeight)
        assertEquals(p.cacheWeight, Program.component(p).cacheWeight)
        assertTrue(p.cacheWeight >= 1000 + ComponentProgram.WEIGHT_PER_MODULE, "${p.cacheWeight}")
    }

    @Test
    fun `コンポーネントの重さは中のモジュールの大きさに比例し、同じ中身は1つに数える`() {
        val small = loadCounting(modulesText(1, 100)).first
        val big = loadCounting(modulesText(1, 100_000)).first
        // 大きさの差がそのまま重さの差になる（計画の項目の数は同じ）
        assertEquals(items(small), items(big))
        assertTrue(big.cacheWeight - small.cacheWeight >= 99_000, "${small.cacheWeight} ${big.cacheWeight}")
        // 同じ中身の定義を 3 つ並べても、モジュールは 1 つで、重さはモジュールの分が増えない（計画の項目の分だけ）
        val (triple, runtime) = loadCounting(modulesText(3, 100))
        assertEquals(1, triple.moduleCount)
        assertEquals(1, runtime.sizes.size)
        assertEquals(small.cacheWeight + (items(triple) - items(small)) * ComponentProgram.WEIGHT_PER_PLAN_ITEM, triple.cacheWeight)
        // 中身が違えば別のモジュールで、モジュールの数の分の固定の重さがかかる
        val different = ComponentProgram.load(
            EndiveRuntime(),
            WitTool.wat("(component (core module (func (export \"a\"))) (core module (func (export \"b\"))) (core instance (instantiate 0)) (core instance (instantiate 1)))"),
            ComponentHost.NONE,
            metered = true,
        )
        assertEquals(2, different.moduleCount)
        assertTrue(different.cacheWeight >= 2 * ComponentProgram.WEIGHT_PER_MODULE)
    }

    @Test
    fun `ProgramCacheはコンポーネントの重さで古いものを外す`() {
        val heavy = loadCounting(modulesText(1, 200_000)).first
        val light = loadCounting(modulesText(1, 100)).first
        val direct = Executor { it.run() }
        // 上限は重いもの 2 つ分の少し手前。重いものは 1 つしか持てない
        val cache = ProgramCache(100, heavy.cacheWeight * 2 - 1, direct)
        fun loadHeavy(n: Int) = cache.load(1, "heavy-$n".toByteArray(), true, emptySet()) { Program.component(heavy) }.get()
        loadHeavy(1)
        loadHeavy(2)
        assertEquals(1, cache.retainedCount)
        // 軽いものは何個でも収まる（バイト数は小さくても、固定の分だけ重さがある）
        val cache2 = ProgramCache(100, light.cacheWeight * 3, direct)
        (1..5).forEach { n -> cache2.load(1, "light-$n".toByteArray(), true, emptySet()) { Program.component(light) }.get() }
        assertEquals(3, cache2.retainedCount)
    }

    private val kibiProcess: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-process-wasi.wasm")) { "kibi-process-wasi.wasm がない" }.readBytes()
    }

    /** p1 の親（kibi_process.c。`/bin/parent`）を動かす。親は `/bin/kp` を spawn する（ここではコンポーネントを置く） */
    private fun runParent(h: Harness, m: Machine, vararg args: String): String {
        val output = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
        val program = Program.loadMetered(EndiveRuntime(), kibiProcess, null, WasiP1.WAIT_IMPORTS)
        val out = StdioSource.Object(output, HandleRights.WRITE)
        val pid = m.spawn(program, WasiP1Driver(program.module), SpawnOptions(args = listOf("kp") + args, stdio = StdioSpec(StdioSource.Closed, out, out)))
        val text = StringBuilder()
        fun drain() = m.lock.withLockValue {
            val s = m.kernel.objects[output] as ByteStream
            val buf = ByteArray(s.available)
            if (buf.isNotEmpty()) s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes
            text.append(String(buf, Charsets.UTF_8))
        }
        eventually(60_000, "終わらない") {
            h.scheduler.tick()
            drain()
            m.stateOf(pid).isTerminal
        }
        drain()
        return text.toString()
    }

    @Test
    fun `p1のプロセスがkibi-processのspawnでコンポーネントの子を作れる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/kp", hello)
            val out = runParent(h, m, "run")
            // 子は標準出力を親から受け継ぐ
            assertTrue("Hello from WASI\n" in out, out)
            assertTrue("status1 exited 0\n" in out, out)
        }
    }

    @Test
    fun `ブロッキング型の上限に達していたらコンポーネントの子のspawnはNFILE`() = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(0).build()
        Harness(schedulerConfig = config, execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            write(m, "/bin/kp", hello)
            // 親は待機点型なので数に入らない。子のコンポーネントは BLOCKING なので上限で断られる
            val out = runParent(h, m, "run")
            assertTrue("spawn ${-Errno.NFILE.code}\n" in out, out)
            assertEquals(ProcessKind.BLOCKING, KernelDrivers.DEFAULT.create(Program.component(P2Testing.load(hello))).kind)
        }
    }
}
