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

    /** 終了コードと標準出力・標準エラー出力。[outputs] は [run] で頼んだ、動かした後のファイルの中身（なければ入れない） */
    class Result(val exitCode: Int, val stdout: String, val stderr: String, val outputs: Map<String, ByteArray> = emptyMap())

    /** 仮想 FS の作業ディレクトリ（ゲストから見たパスも同じ）。[run] の引数のパスは `work/...` で書く */
    const val WORK: String = "work"

    /**
     * WAT のテキストを WASM のバイナリにする（`wasm-tools parse`。#425 のテストで、WAT でモジュールを書くために使う）。
     * 読めなければ [IllegalArgumentException]
     */
    fun wat(text: String): ByteArray {
        val r = run(listOf("parse", "$WORK/m.wat", "-o", "$WORK/m.wasm"), mapOf("m.wat" to text.toByteArray()), listOf("m.wasm"))
        require(r.exitCode == 0) { "WAT を読めない: ${r.stderr}" }
        return r.outputs.getValue("m.wasm")
    }

    /** `wasm-tools validate` で、[wasm] が正しい WASM か確かめる。正しくなければ、エラー出力を返す */
    fun validate(wasm: ByteArray, features: String = "all"): String? {
        val r = run(listOf("validate", "--features", features, "$WORK/m.wasm"), mapOf("m.wasm" to wasm))
        return if (r.exitCode == 0) null else r.stderr
    }

    /**
     * [files]（`work/` からの相対パス → 中身。`/` で区切る）を置き、`wasm-tools` を [args] で動かす。
     * [outputs]（`work/` からの相対パス）は、動かした後に読んで [Result.outputs] に入れる
     */
    fun run(args: List<String>, files: Map<String, ByteArray>, outputs: List<String> = emptyList()): Result =
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
            val read = outputs.mapNotNull { name -> dir.resolve(name).takeIf { Files.exists(it) }?.let { name to Files.readAllBytes(it) } }.toMap()
            Result(exit, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8), read)
        }
}
