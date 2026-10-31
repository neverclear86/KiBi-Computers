package pro.ikili.kibicomputers.poc.cm

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import run.endive.log.Logger
import run.endive.log.SystemLogger
import run.endive.runtime.ByteArrayMemory
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.tools.wasm.WasmToolsModule
import run.endive.wasi.WasiExitException
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * Endive が WASM にした wasm-tools を、任意の引数で動かす（#261）。
 * 入力は仮想 FS の `work/` に置き、引数の中の `{name}` をそのパスに置き換える。`output` は動かしたあとに読む。
 */
object WasmTools {
    private val module = WasmToolsModule.load()
    private val quiet = object : SystemLogger() {
        override fun isLoggable(level: Logger.Level): Boolean = false
    }

    class Result(val exitCode: Int, val output: ByteArray?, val stdout: String, val stderr: String)

    fun run(args: List<String>, inputs: Map<String, ByteArray>, output: String? = null): Result =
        ZeroFs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build()).use { fs ->
            val dir = fs.getPath("work")
            Files.createDirectory(dir)
            for ((name, bytes) in inputs) Files.write(dir.resolve(name), bytes)
            fun expand(arg: String): String {
                var s = arg
                for (name in inputs.keys + listOfNotNull(output)) s = s.replace("{$name}", dir.resolve(name).toString())
                return s
            }
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val options = WasiOptions.builder()
                .withStdin(ByteArrayInputStream(ByteArray(0)), false)
                .withStdout(stdout, false)
                .withStderr(stderr, false)
                .withDirectory(dir.toString(), dir)
                .withArguments(listOf("wasm-tools") + args.map(::expand))
                .build()
            var exit = 0
            WasiPreview1.builder().withLogger(quiet).withOptions(options).build().use { wasi ->
                try {
                    Instance.builder(module)
                        .withMachineFactory(WasmToolsModule::create)
                        .withMemoryFactory(::ByteArrayMemory)
                        .withImportValues(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
                        .build()
                } catch (e: WasiExitException) {
                    exit = e.exitCode()
                }
            }
            val out = output?.let { dir.resolve(it) }?.takeIf { Files.exists(it) }?.let(Files::readAllBytes)
            Result(exit, out, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        }

    /** p1 のモジュールを、公式の `wasi_snapshot_preview1` アダプタでコンポーネントに包む */
    fun adapt(module: ByteArray, adapter: ByteArray): ByteArray {
        val r = run(
            listOf("component", "new", "{module.wasm}", "--adapt", "wasi_snapshot_preview1={adapter.wasm}", "-o", "{out.wasm}"),
            mapOf("module.wasm" to module, "adapter.wasm" to adapter),
            output = "out.wasm",
        )
        if (r.exitCode != 0 || r.output == null) throw AdaptException(r.stderr.ifBlank { r.stdout }.trim())
        return r.output
    }

    /** コアモジュールに、WIT の world（コンポーネントの型の情報）を埋め込む */
    fun embed(module: ByteArray, wit: String, world: String): ByteArray {
        val r = run(
            listOf("component", "embed", "{probe.wit}", "--world", world, "{module.wasm}", "-o", "{out.wasm}"),
            mapOf("module.wasm" to module, "probe.wit" to wit.toByteArray()),
            output = "out.wasm",
        )
        if (r.exitCode != 0 || r.output == null) throw AdaptException(r.stderr.ifBlank { r.stdout }.trim())
        return r.output
    }

    /** コアモジュールの WAT をバイナリにする */
    fun parseCore(wat: String): ByteArray {
        val r = run(listOf("parse", "{in.wat}", "-o", "{out.wasm}"), mapOf("in.wat" to wat.toByteArray()), output = "out.wasm")
        if (r.exitCode != 0 || r.output == null) error("wasm-tools parse: ${r.stderr}")
        return r.output
    }
}

class AdaptException(message: String) : RuntimeException(message)
