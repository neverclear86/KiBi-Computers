package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * MoonBit のモジュール（`moon.mod` のあるディレクトリ）を `moon build --target wasm`（線形メモリのバックエンド）でビルドし、
 * 実行ファイルのパッケージの `.wasm` を [outputDir] に写すタスク（#32）。
 *
 * MoonBit は初回に [MoonBitInstaller] が取得する。ソースのディレクトリを汚さないよう、一時ディレクトリに写してからビルドする。
 */
@CacheableTask
abstract class MoonBuildWasm @Inject constructor(
    private val execOperations: ExecOperations,
    private val fs: FileSystemOperations,
) : DefaultTask() {
    /** MoonBit の版。変わったらビルドし直す。 */
    @get:Input
    abstract val moonbitVersion: Property<String>

    /** ホスト用の MoonBit の配布物。OS ごとに違うので、キャッシュのキーには入れない。 */
    @get:Internal
    abstract val moonbitDistribution: Property<MoonBitDistribution>

    /** MoonBit を展開する場所（Gradle ホームの下）。 */
    @get:Internal
    abstract val toolchainsDir: DirectoryProperty

    /** MoonBit のモジュールのディレクトリ（`moon.mod` があるところ）。 */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleDir: DirectoryProperty

    /** 出力するパッケージ（モジュールからの相対パス → [outputDir] からの出力パス）。例: `cmd/echo` → `kibicomputers/wasm/echo-moonbit.wasm` */
    @get:Input
    abstract val packages: MapProperty<String, String>

    /** `moon build` に渡すほかの引数。 */
    @get:Input
    abstract val buildArgs: ListProperty<String>

    /**
     * 空でなければ、各パッケージの `.wasm` に、同梱の `bin/moon-wasm-opt` をこの引数で（`<入力> <引数> -o <出力>`）かけたものも出す。
     * 出力のパスは、[packages] の出力パスの `.wasm` の前に [wasmOptSuffix] を付けたもの。失敗してもビルドは止めない（警告だけ）
     */
    @get:Input
    abstract val wasmOptArgs: ListProperty<String>

    /** [wasmOptArgs] の出力のパスに付ける印（既定は `-opt`）。 */
    @get:Input
    abstract val wasmOptSuffix: Property<String>

    /** 出力ディレクトリ。リソースのディレクトリとしてそのまま使える。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val home = MoonBitInstaller.ensure(toolchainsDir.get().asFile, moonbitDistribution.get(), execOperations, logger)
        val work = temporaryDir.resolve("module")
        val targetDir = temporaryDir.resolve("target")
        work.deleteRecursively()
        targetDir.deleteRecursively()
        fs.copy {
            from(moduleDir)
            into(work)
            exclude("_build/**", "target/**", ".mooncakes/**")
        }
        execOperations.exec {
            executable = home.resolve("bin").resolve(moonbitDistribution.get().moonExecutable).absolutePath
            args("build", "--target", "wasm", "--release", "--target-dir", targetDir.absolutePath)
            args(buildArgs.get())
            workingDir = work
            environment(MoonBitInstaller.moonEnvironment(home))
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        val built = targetDir.walkTopDown().filter { it.isFile && it.extension == "wasm" }.toList()
        for ((pkg, outputPath) in packages.get()) {
            val name = pkg.substringAfterLast('/')
            // 出力は <target>/wasm/release/build/<パッケージ>/<名前>.wasm（版によって途中の段が変わっても探せるよう、末尾で照合する）
            val suffix = "/$pkg/$name.wasm"
            val matches = built.filter { it.invariantSeparatorsPath.endsWith(suffix) }
            val file = (matches.firstOrNull { "/release/" in it.invariantSeparatorsPath } ?: matches.firstOrNull())
                ?: throw GradleException("moon build の出力に $pkg の .wasm がありません（見つかったもの: ${built.map { it.relativeTo(targetDir).invariantSeparatorsPath }}）")
            val target = out.resolve(outputPath)
            target.parentFile.mkdirs()
            file.copyTo(target, overwrite = true)
            if (wasmOptArgs.get().isNotEmpty()) wasmOpt(home, file, out.resolve(outputPath.removeSuffix(".wasm") + wasmOptSuffix.get() + ".wasm"))
        }
    }

    private fun wasmOpt(home: java.io.File, input: java.io.File, output: java.io.File) {
        val exe = if (moonbitDistribution.get().moonExecutable.endsWith(".exe")) "moon-wasm-opt.exe" else "moon-wasm-opt"
        val result = execOperations.exec {
            executable = home.resolve("bin").resolve(exe).absolutePath
            args(input.absolutePath)
            args(wasmOptArgs.get())
            args("-o", output.absolutePath)
            environment(MoonBitInstaller.moonEnvironment(home))
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            output.delete()
            logger.warn("moon-wasm-opt が失敗した（終了コード ${result.exitValue}）: ${input.name}")
        }
    }
}
