package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import pro.ikili.kibicomputers.core.kernel.Machine

/** p2 の層のテストの部品 */
internal object P2Testing {
    fun resource(path: String): ByteArray = requireNotNull(P2Testing::class.java.getResourceAsStream(path)) { "$path がない" }.readBytes()

    val adapter: ByteArray by lazy { resource("/kibicomputers/wasi-adapter/wasi_snapshot_preview1.command.wasm") }

    private val wrapped = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** テストの p1 のプログラム [name]（`/kibicomputers/wasm/<name>.wasm`）を、アダプターで p2 のコマンドのコンポーネントにする */
    fun wrap(name: String): ByteArray = wrapped.getOrPut(name) {
        val r = WitTool.run(
            listOf("component", "new", "work/m.wasm", "--adapt", "wasi_snapshot_preview1=work/a.wasm", "-o", "work/c.wasm"),
            mapOf("m.wasm" to resource("/kibicomputers/wasm/$name.wasm"), "a.wasm" to adapter),
            listOf("c.wasm"),
        )
        assertEquals(0, r.exitCode, r.stderr)
        r.outputs.getValue("c.wasm")
    }

    /** 実物のホスト・既定の判定で読み込む */
    fun load(bytes: ByteArray, metered: Boolean = true): ComponentProgram =
        ComponentProgram.load(LinkTesting.runtime, bytes, WasiP2Host, metered = metered)

    /** WAT のコンポーネントを、実物のホスト・既定の判定で読み込む */
    fun loadWat(text: String): ComponentProgram = load(WitTool.wat(text))

    /** 出力を受ける stream を作り、そのオブジェクトID を返す */
    fun outputStream(m: Machine): Int = ByteStream(256 * 1024).also { m.lock.withLockValue { m.kernel.objects.add(it) } }.id

    /** stream [id] にたまった出力 */
    fun drain(m: Machine, id: Int): String = m.lock.withLockValue {
        val s = m.kernel.objects[id] as ByteStream
        val buf = ByteArray(s.available)
        s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes
        String(buf, Charsets.UTF_8)
    }

    class Run(val pid: Int, val output: String, val exitCode: Int?)

    /**
     * [program] を [driver] で、標準出力を stream にして動かし、終わるまで待つ。[prepare] はマシンを作った後に呼ぶ（VFS の用意など）。
     * [cwd] はプロセスの cwd（null なら既定）、[stdin] は標準入力
     */
    fun run(
        h: Harness,
        program: Program,
        driver: ProcessDriver,
        args: List<String> = listOf("prog"),
        env: Map<String, String>? = null,
        cwd: String? = null,
        stdin: StdioSource = StdioSource.Closed,
        fuelPerTick: Long = 5_000_000,
        prepare: (Machine) -> Unit = {},
    ): Run {
        val m = h.machine(fuelPerTick = fuelPerTick)
        prepare(m)
        val out = outputStream(m)
        val options = SpawnOptions(
            args = args,
            env = env,
            cwd = cwd,
            stdio = StdioSpec(stdin, StdioSource.Object(out, HandleRights.WRITE), StdioSource.Object(out, HandleRights.WRITE)),
            fds = SpawnOptions.ROOT_PREOPEN,
        )
        val pid = m.spawn(program, driver, options, 0)
        val text = StringBuilder()
        eventually(60_000, "終わらない") {
            h.scheduler.tick()
            text.append(drain(m, out))
            m.stateOf(pid).isTerminal
        }
        text.append(drain(m, out))
        val code = m.lock.withLockValue { m.kernel.processes[pid]?.exitCode }
        return Run(pid, text.toString(), code)
    }
}
