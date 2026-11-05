package pro.ikili.kibicomputers.core.wasi.kibi

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
 * Endive が WASM にした wasm-tools を、仮想 FS の上で動かす（#75。PoC #261 の `WasmTools` を、テストに要る分だけ移したもの）。
 * テストのソースセットだけで使う（MOD には入れない）。
 */
internal object WitTool {
    private val module by lazy { WasmToolsModule.load() }
    private val quiet = object : SystemLogger() {
        override fun isLoggable(level: Logger.Level): Boolean = false
    }

    /** 終了コードと標準出力・標準エラー出力 */
    class Result(val exitCode: Int, val stdout: String, val stderr: String)

    /** 仮想 FS の作業ディレクトリ（ゲストから見たパスも同じ）。[run] の引数のパスは `work/...` で書く */
    const val WORK: String = "work"

    /** [files]（`work/` からの相対パス → 中身。`/` で区切る）を置き、`wasm-tools` を [args] で動かす */
    fun run(args: List<String>, files: Map<String, ByteArray>): Result =
        ZeroFs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build()).use { fs ->
            val dir = fs.getPath(WORK)
            Files.createDirectory(dir)
            for ((name, bytes) in files) {
                val path = dir.resolve(name)
                Files.createDirectories(path.parent)
                Files.write(path, bytes)
            }
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val options = WasiOptions.builder()
                .withStdin(ByteArrayInputStream(ByteArray(0)), false)
                .withStdout(stdout, false)
                .withStderr(stderr, false)
                .withDirectory(dir.toString(), dir)
                .withArguments(listOf("wasm-tools") + args)
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
            Result(exit, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        }
}
