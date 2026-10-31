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
 * コンポーネントのバイナリの中のコアモジュール（セクション 1）を [transform] で書き換える。
 * 入れ子のコンポーネント（セクション 4）の中も書き換える。
 */
fun rewriteCoreModules(component: ByteArray, transform: (ByteArray) -> ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(component, 0, 8) // magic + version + layer
    var pos = 8
    while (pos < component.size) {
        val id = component[pos++].toInt() and 0xFF
        var size = 0L
        var shift = 0
        while (true) {
            val b = component[pos++].toInt() and 0xFF
            size = size or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) break
        }
        val payload = component.copyOfRange(pos, pos + size.toInt())
        pos += size.toInt()
        val replaced = when (id) {
            1 -> transform(payload)
            4 -> rewriteCoreModules(payload, transform)
            else -> payload
        }
        out.write(id)
        var v = replaced.size.toLong()
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
        out.write(replaced)
    }
    return out.toByteArray()
}

/** Endive が WASM にした wasm-tools の `parse` で、コンポーネントの WAT をバイナリにする */
object Wat {
    private val module = WasmToolsModule.load()
    private val quiet = object : SystemLogger() {
        override fun isLoggable(level: Logger.Level): Boolean = false
    }

    fun toWasm(wat: String): ByteArray =
        ZeroFs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build()).use { fs ->
            val dir = fs.getPath("work")
            Files.createDirectory(dir)
            val input = dir.resolve("in.wat")
            Files.writeString(input, wat)
            val output = dir.resolve("out.wasm")
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val options = WasiOptions.builder()
                .withStdin(ByteArrayInputStream(ByteArray(0)), false)
                .withStdout(stdout, false)
                .withStderr(stderr, false)
                .withDirectory(dir.toString(), dir)
                .withArguments(listOf("wasm-tools", "parse", input.toString(), "-o", output.toString()))
                .build()
            WasiPreview1.builder().withLogger(quiet).withOptions(options).build().use { wasi ->
                try {
                    Instance.builder(module)
                        .withMachineFactory(WasmToolsModule::create)
                        .withMemoryFactory(::ByteArrayMemory)
                        .withImportValues(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
                        .build()
                } catch (e: WasiExitException) {
                    if (e.exitCode() != 0) error("wasm-tools parse: " + stderr.toString(Charsets.UTF_8))
                }
            }
            Files.readAllBytes(output)
        }
}

/**
 * WASI p3 の土台になる canon を1つずつ使う、最小のコンポーネント。どれも `run: func() -> u32` を export し、42 を返す。
 * 同期の lift（比較の基準）、非同期の lift（コールバック方式）と `task.return`、`stream.new`、`waitable-set.new`。
 */
object AsyncProbes {
    val all: List<Pair<String, String>> = listOf(
        "sync lift（基準）" to """
            (component
              (core module ${'$'}m (func (export "run") (result i32) (i32.const 42)))
              (core instance ${'$'}i (instantiate ${'$'}m))
              (func (export "run") (result u32) (canon lift (core func ${'$'}i "run"))))
        """.trimIndent(),
        "async lift + task.return" to """
            (component
              (core func ${'$'}ret (canon task.return (result u32)))
              (core module ${'$'}m
                (import "" "task.return" (func ${'$'}ret (param i32)))
                (func (export "run") (result i32) (call ${'$'}ret (i32.const 42)) (i32.const 0))
                (func (export "cb") (param i32 i32 i32) (result i32) unreachable))
              (core instance ${'$'}i (instantiate ${'$'}m (with "" (instance (export "task.return" (func ${'$'}ret))))))
              (func (export "run") (result u32)
                (canon lift (core func ${'$'}i "run") async (callback (func ${'$'}i "cb")))))
        """.trimIndent(),
        "stream.new" to """
            (component
              (type ${'$'}s (stream u8))
              (core func ${'$'}new (canon stream.new ${'$'}s))
              (core module ${'$'}m
                (import "" "stream.new" (func ${'$'}new (result i64)))
                (func (export "run") (result i32) (drop (call ${'$'}new)) (i32.const 42)))
              (core instance ${'$'}i (instantiate ${'$'}m (with "" (instance (export "stream.new" (func ${'$'}new))))))
              (func (export "run") (result u32) (canon lift (core func ${'$'}i "run"))))
        """.trimIndent(),
        "waitable-set.new" to """
            (component
              (core func ${'$'}new (canon waitable-set.new))
              (core module ${'$'}m
                (import "" "waitable-set.new" (func ${'$'}new (result i32)))
                (func (export "run") (result i32) (drop (call ${'$'}new)) (i32.const 42)))
              (core instance ${'$'}i (instantiate ${'$'}m (with "" (instance (export "waitable-set.new" (func ${'$'}new))))))
              (func (export "run") (result u32) (canon lift (core func ${'$'}i "run"))))
        """.trimIndent(),
    )
}
