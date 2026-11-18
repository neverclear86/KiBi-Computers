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
 * C のソースを wasi-sdk の clang で、ネイティブのツールチェーンのコンポーネント（`wasm32-wasip2`・`wasm32-wasip3`）にビルドするタスク（#775）。
 *
 * clang は `wasm-ld` でリンクしたあと、同じ bin の `wasm-component-ld` でコンポーネントにする（アダプターで包んだ p1 のプログラムではなく、
 * wasi-libc が p2・p3 の import を直接呼ぶ）。wasi-sdk は初回に [WasiSdkInstaller] が取得する。
 * 出力は [outputDir] の下の [outputPath] で、[WasmExtension.wasiSdkModule] で登録すると、その出力ディレクトリがソースセットのリソースになる。
 */
@CacheableTask
abstract class WasiSdkCcWasm @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    /** wasi-sdk の版。変わったらビルドし直す。 */
    @get:Input
    abstract val wasiSdkVersion: Property<String>

    /** ホスト用の wasi-sdk の配布物。OS ごとに違うので、キャッシュのキーには入れない。 */
    @get:Internal
    abstract val wasiSdkDistribution: Property<WasiSdkDistribution>

    /** wasi-sdk を展開する場所（Gradle ホームの下。手元と CI で共有する）。 */
    @get:Internal
    abstract val toolchainsDir: DirectoryProperty

    /** コンパイルする C のソース。 */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** `--target` に渡すターゲット（`wasm32-wasip2`・`wasm32-wasip3`）。 */
    @get:Input
    abstract val target: Property<String>

    /** clang に渡すほかの引数（`-O2` など）。 */
    @get:Input
    abstract val compilerArgs: ListProperty<String>

    /** [outputDir] からの出力ファイルの相対パス（例: `kibicomputers/wasm-native/hello-wasi-p2.wasm`）。 */
    @get:Input
    abstract val outputPath: Property<String>

    /** 出力ディレクトリ。リソースのディレクトリとしてそのまま使える。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val sdk = WasiSdkInstaller.ensure(toolchainsDir.get().asFile, wasiSdkDistribution.get(), logger)
        val dist = wasiSdkDistribution.get()
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val output = out.resolve(outputPath.get())
        output.parentFile.mkdirs()

        execOperations.exec {
            executable = sdk.resolve(dist.clangPath).absolutePath
            args("--target=${target.get()}", "--sysroot=${sdk.resolve("share/wasi-sysroot").absolutePath}")
            args(compilerArgs.get())
            args("-o", output.absolutePath)
            args(sources.files.sorted().map { it.absolutePath })
        }
    }
}
