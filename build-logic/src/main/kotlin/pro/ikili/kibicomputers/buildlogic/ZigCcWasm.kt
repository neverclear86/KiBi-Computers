package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * C のソースを `zig cc` で WASM にビルドするタスク。
 *
 * Zig は初回に [ZigInstaller] が取得する。出力は [outputDir] の下の [outputPath] で、
 * [WasmExtension.module] で登録すると、その出力ディレクトリがソースセットのリソースになる。
 */
@CacheableTask
abstract class ZigCcWasm @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    /** Zig のバージョン。変わったらビルドし直す。 */
    @get:Input
    abstract val zigVersion: Property<String>

    /** ホスト用の Zig の配布物。OS ごとに違うので、キャッシュのキーには入れない。 */
    @get:Internal
    abstract val zigDistribution: Property<ZigDistribution>

    /** Zig を展開する場所（Gradle ホームの下。手元と CI で共有する）。 */
    @get:Internal
    abstract val toolchainsDir: DirectoryProperty

    /** Zig のコンパイル結果のキャッシュ（`ZIG_GLOBAL_CACHE_DIR`）。 */
    @get:Internal
    abstract val zigCacheDir: DirectoryProperty

    /** コンパイルする C のソース。 */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** `-I` で渡すヘッダーのディレクトリ。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    /** `-target` に渡すターゲット。既定は `wasm32-wasi`（WASI p1）。 */
    @get:Input
    abstract val target: Property<String>

    /** `zig cc` に渡すほかの引数（`-O2`、`-Wl,--export=...` など）。 */
    @get:Input
    abstract val compilerArgs: ListProperty<String>

    /** [outputDir] からの出力ファイルの相対パス（例: `kibicomputers/wasm/fib.wasm`）。 */
    @get:Input
    abstract val outputPath: Property<String>

    /** 出力ディレクトリ。リソースのディレクトリとしてそのまま使える。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val zig = ZigInstaller.ensure(toolchainsDir.get().asFile, zigDistribution.get(), logger)
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val output = out.resolve(outputPath.get())
        output.parentFile.mkdirs()

        val localCache = temporaryDir.resolve("zig-local-cache")
        execOperations.exec {
            executable = zig.absolutePath
            args("cc", "-target", target.get())
            args(compilerArgs.get())
            includeDirs.forEach { args("-I", it.absolutePath) }
            args("-o", output.absolutePath)
            args(sources.files.sorted().map { it.absolutePath })
            environment("ZIG_GLOBAL_CACHE_DIR", zigCacheDir.get().asFile.absolutePath)
            environment("ZIG_LOCAL_CACHE_DIR", localCache.absolutePath)
        }
    }
}
