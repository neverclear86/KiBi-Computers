package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import pro.ikili.kibicomputers.core.wasi.p2.P2Testing
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** テストの JVM の中で読み込んだ WASM を使い回す表（#991） */
class SharedProgramsTest {
    private val prompt: ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/prompt-wasi.wasm")) { "prompt-wasi.wasm がない" }.use { it.readBytes() }

    private val waits = WasiP1.WAIT_IMPORTS

    private fun load(
        runtime: EndiveRuntime = EndiveRuntime(),
        bytes: ByteArray = prompt,
        limits: ModuleLimits? = ModuleLimits.DEFAULT,
        metered: Boolean = true,
        waitImports: Set<String> = waits,
        host: ComponentHost = KernelDrivers.COMPONENT_HOST,
    ): Program = SharedPrograms.executableLoader.load(runtime, bytes, limits, metered, waitImports, host)

    /** [h] のマシンで `/bin/prompt` を exec し、そのプロセスの [Program] を返す */
    private fun execPrompt(h: Harness): Program {
        val m = h.machine(fuelPerTick = 10_000_000)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true))
        m.vfs.write(f, prompt, 0, prompt.size)
        val stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)
        val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
        return m.rt(pid).program
    }

    @Test
    fun `別々のHarnessで同じ実行ファイルをexecすると、同じProgramを使う`() = timeout(Duration.ofSeconds(120)) {
        val first = Harness(execRuntime = EndiveRuntime()).use { execPrompt(it) }
        val second = Harness(execRuntime = EndiveRuntime()).use { execPrompt(it) }
        assertSame(first, second)
    }

    @Test
    fun `shareProgramsがfalseなら、共有の表を使わずに読み込む`() = timeout(Duration.ofSeconds(120)) {
        val shared = Harness(execRuntime = EndiveRuntime()).use { execPrompt(it) }
        val own = Harness(execRuntime = EndiveRuntime(), sharePrograms = false).use { execPrompt(it) }
        assertNotSame(shared, own)
        assertEquals(shared.hash, own.hash)
    }

    @Test
    fun `スレッドのスタックが既定より小さいスケジューラは、共有の表を使わない`() = timeout(Duration.ofSeconds(120)) {
        val shared = Harness(execRuntime = EndiveRuntime()).use { execPrompt(it) }
        val small = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64)
            .threadStackBytes(SharedPrograms.DEFAULT_THREAD_STACK_BYTES / 2).build()
        val own = Harness(small, execRuntime = EndiveRuntime()).use { execPrompt(it) }
        assertNotSame(shared, own)
        assertEquals(shared.hash, own.hash)
        val large = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64)
            .threadStackBytes(SharedPrograms.DEFAULT_THREAD_STACK_BYTES * 2).build()
        assertSame(shared, Harness(large, execRuntime = EndiveRuntime()).use { execPrompt(it) }, "既定より大きいスタックなら共有する")
    }

    @Test
    fun `同じキーなら同じものを返し、キーの要素が違えば別に読み込む`() = timeout(Duration.ofSeconds(120)) {
        val base = load()
        assertSame(base, load())
        assertSame(base, load(runtime = EndiveRuntime()), "ランタイムのインスタンスが違っても、既定のものなら共有する")
        assertNotSame(base, load(metered = false))
        assertNotSame(base, load(waitImports = emptySet()))
    }

    @Test
    fun `既定でないランタイム・上限・ホストでは共有しない`() = timeout(Duration.ofSeconds(120)) {
        val interpreted = EndiveRuntime(compile = false)
        assertNotSame(load(runtime = interpreted), load(runtime = interpreted), "インタプリタだけのランタイム")
        val limits = ModuleLimits()
        assertNotSame(load(limits = limits), load(limits = limits), "ModuleLimits.DEFAULT でない上限")
        assertNotSame(load(limits = null), load(limits = null), "上限なし")
        assertNotSame(load(host = ComponentHost.NONE), load(host = ComponentHost.NONE), "許していないホスト")
    }

    @Test
    fun `失敗は覚えず、次は読み込み直す`() {
        val table = SharedProgramTable(maxEntries = 4, maxWeight = Long.MAX_VALUE)
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            table.getOrLoad<String>("k", { 1 }) {
                calls++
                throw IllegalStateException("1 回目は失敗")
            }
        }
        assertEquals("ok", table.getOrLoad<String>("k", { 1 }) { calls++; "ok" })
        assertEquals("ok", table.getOrLoad<String>("k", { 1 }) { calls++; "ng" })
        assertEquals(2, calls)
        assertEquals(1L, table.hits.get())
        assertEquals(2L, table.misses.get())
    }

    @Test
    fun `数と重さの上限を超えたら、使った順の古いものから外す`() {
        val table = SharedProgramTable(maxEntries = 2, maxWeight = 10)
        table.getOrLoad<String>("a", { 1 }) { "a" }
        table.getOrLoad<String>("b", { 1 }) { "b" }
        table.getOrLoad<String>("a", { 1 }) { "a2" } // a を使った（b が古くなる）
        table.getOrLoad<String>("c", { 1 }) { "c" } // 数の上限で b を外す
        assertEquals(2, table.size)
        assertEquals("a", table.getOrLoad<String>("a", { 1 }) { "a3" })
        assertEquals("b2", table.getOrLoad<String>("b", { 1 }) { "b2" }, "外したものは読み込み直す")
        // 重さの上限: 10 の重さのものを入れると、ほかを外して収める（入れたばかりのものは残す）
        table.getOrLoad<String>("heavy", { 10 }) { "heavy" }
        assertEquals(1, table.size)
        assertEquals(10L, table.totalWeight)
        table.getOrLoad<String>("huge", { 100 }) { "huge" }
        assertEquals(1, table.size, "上限より重い 1 件は残す")
        assertEquals("huge", table.getOrLoad<String>("huge", { 100 }) { "huge2" })
    }

    @Test
    fun `loadとloadMeteredは、同じバイト列・同じ引数なら同じProgramを返す`() = timeout(Duration.ofSeconds(120)) {
        assertSame(SharedPrograms.load(prompt), SharedPrograms.load(prompt))
        assertSame(SharedPrograms.loadMetered(prompt, waits), SharedPrograms.loadMetered(prompt, waits))
        assertNotSame(SharedPrograms.load(prompt), SharedPrograms.loadMetered(prompt, waits), "計測するかどうかで別")
        assertNotSame(SharedPrograms.loadMetered(prompt, waits), SharedPrograms.loadMetered(prompt, emptySet()), "待機の import が違えば別")
    }

    @Test
    fun `componentは、既定のランタイムなら同じものを返し、kindと計測の有無とホストが違えば別に読み込む`() = timeout(Duration.ofSeconds(120)) {
        val bytes = LinkTesting.wat("(component)")
        var loads = 0
        fun load(kind: String, runtime: EndiveRuntime, metered: Boolean = true, host: ComponentHost = ComponentHost.NONE): ComponentProgram =
            SharedPrograms.component(kind, runtime, bytes, host, metered) {
                loads++
                ComponentProgram.load(runtime, bytes, host, metered, policy = LinkTesting.ANY_IMPORT)
            }

        val runtime = EndiveRuntime()
        val base = load("a", runtime)
        assertSame(base, load("a", runtime))
        assertSame(base, load("a", EndiveRuntime()), "ランタイムのインスタンスが違っても、既定のものなら共有する")
        assertEquals(1, loads)
        assertNotSame(base, load("b", runtime), "kind が違う")
        assertNotSame(base, load("a", runtime, metered = false), "計測の有無が違う")
        assertNotSame(base, load("a", runtime, host = ComponentHost { null }), "ホストが違う")
        assertEquals(4, loads)
        val interpreted = EndiveRuntime(compile = false)
        assertNotSame(load("a", interpreted), load("a", interpreted), "インタプリタだけのランタイムは共有しない")
    }

    @Test
    fun `LinkTestingのloadは、状態を持たないホストで検証の差し込みがないときだけ共有する`() = timeout(Duration.ofSeconds(120)) {
        val text = "(component)"
        val shared = LinkTesting.load(text)
        assertSame(shared, LinkTesting.load(text))
        assertNotSame(shared, LinkTesting.load(text, metered = true))
        var validated = 0
        val first = LinkTesting.load(text, validate = { validated++ })
        val second = LinkTesting.load(text, validate = { validated++ })
        assertNotSame(first, second, "validate 付きは共有しない")
        assertNotSame(shared, first)
        assertEquals(2, validated, "validate は読み込みごとに呼ばれる")
        val host = ComponentHost { null }
        assertNotSame(LinkTesting.load(text, host), LinkTesting.load(text, host), "ホストが NONE でなければ共有しない")
        val interpreted = EndiveRuntime(compile = false)
        assertNotSame(LinkTesting.load(text, runtime = interpreted), LinkTesting.load(text, runtime = interpreted), "インタプリタだけのランタイムは共有しない")
    }

    @Test
    fun `P2Testingのloadは、同じバイト列・同じ計測の有無なら共有する`() = timeout(Duration.ofSeconds(120)) {
        val bytes = LinkTesting.wat("(component)")
        val metered = P2Testing.load(bytes)
        assertSame(metered, P2Testing.load(bytes))
        assertNotSame(metered, P2Testing.load(bytes, metered = false))
    }
}
