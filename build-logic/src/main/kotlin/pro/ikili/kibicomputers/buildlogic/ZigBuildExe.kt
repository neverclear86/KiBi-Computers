package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Zig のソース（`.zig`）を `zig build-exe` で WASM にビルドするタスク（#32）。
 *
 * [ZigCcWasm] の Zig 版。Zig は同じく [ZigInstaller] が取得する。[root] がルートのファイルで、
 * そこから `@import` するファイルは [sources] に入れる（変わったらビルドし直すため）。
 */
@CacheableTask
abstract class ZigBuildExe @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    /** Zig のバージョン。変わったらビルドし直す。 */
    @get:Input
    abstract val zigVersion: Property<String>

    /** ホスト用の Zig の配布物。OS ごとに違うので、キャッシュのキーには入れない。 */
    @get:Internal
    abstract val zigDistribution: Property<ZigDistribution>

    /** Zig を展開する場所（Gradle ホームの下）。 */
    @get:Internal
    abstract val toolchainsDir: DirectoryProperty

    /** Zig のコンパイル結果のキャッシュ（`--global-cache-dir`）。 */
    @get:Internal
    abstract val zigCacheDir: DirectoryProperty

    /** ルートのソース（`pub fn main` を持つファイル）。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val root: RegularFileProperty

    /** [root] から `@import` するソース。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** `-target` に渡すターゲット。既定は `wasm32-wasi`（WASI p1）。 */
    @get:Input
    abstract val target: Property<String>

    /** 最適化（`-O`）。既定は `ReleaseSmall`。 */
    @get:Input
    abstract val optimize: Property<String>

    /** スタックの大きさ（バイト。`--stack`）。既定は [ZigCcWasm.DEFAULT_STACK_SIZE]。 */
    @get:Input
    abstract val stackSize: Property<Int>

    /** `zig build-exe` に渡すほかの引数。 */
    @get:Input
    abstract val compilerArgs: ListProperty<String>

    /** [outputDir] からの出力ファイルの相対パス（例: `kibicomputers/wasm/echo.wasm`）。 */
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
            args("build-exe", root.get().asFile.absolutePath)
            args("-target", target.get(), "-O", optimize.get(), "-fstrip")
            args("--stack", stackSize.get().toString())
            args(compilerArgs.get())
            args("--cache-dir", localCache.absolutePath, "--global-cache-dir", zigCacheDir.get().asFile.absolutePath)
            args("-femit-bin=${output.absolutePath}")
            // build-exe は作業ディレクトリに中間ファイル（.o）を書くことがあるので、一時ディレクトリで動かす
            workingDir = temporaryDir
        }
    }
}
