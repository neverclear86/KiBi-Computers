package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject

/**
 * WASM をビルドして、ソースセットのリソースに組み込むプラグイン（`kibicomputers.wasm`、#9）。
 *
 * ```kotlin
 * plugins { id("kibicomputers.wasm") }
 *
 * kibiWasm {
 *     module("fib", sourceSet = "test") {
 *         sources.from("src/test/wasm/fib.c")
 *         target = "wasm32-freestanding"
 *         compilerArgs.addAll("-O2", "-nostdlib", "-Wl,--no-entry", "-Wl,--export=fib")
 *     }
 * }
 * ```
 *
 * 外部の C のソース（Lua など）は [WasmExtension.sourceArchive] で取得して `sources` に渡す。
 *
 * Zig のバージョンとハッシュはルートの `gradle/zig.properties` で固定し、
 * 取得した Zig は `<Gradle ホーム>/kibicomputers/zig/` に置く（CI はここをキャッシュする）。
 */
class WasmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("java")
        project.extensions.create("kibiWasm", WasmExtension::class.java, project)
    }
}

/** `kibiWasm { }` の中身。 */
abstract class WasmExtension @Inject constructor(private val project: Project) {
    private val distribution: ZigDistribution by lazy {
        ZigDistribution.load(project.rootDir.resolve("gradle/zig.properties"))
    }

    /**
     * WASM のモジュールを1つ登録する。
     *
     * タスク `compile<Name>Wasm` を作り、`<resourceDir>/<name>.wasm` に出力して、
     * [sourceSet] のリソースに加える（クラスパスから `/<resourceDir>/<name>.wasm` で読める）。
     * C のスタックは、指定がなければ [ZigCcWasm.DEFAULT_STACK_SIZE]（[ZigCcWasm.stackSize]）。
     */
    @JvmOverloads
    fun module(
        name: String,
        sourceSet: String = "main",
        resourceDir: String = "kibicomputers/wasm",
        action: Action<ZigCcWasm>,
    ): TaskProvider<ZigCcWasm> {
        val taskName = "compile" + name.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Wasm"
        val gradleHome = project.gradle.gradleUserHomeDir.resolve("kibicomputers")
        val task = project.tasks.register(taskName, ZigCcWasm::class.java) {
            group = "build"
            description = "Compiles the $name WASM module with zig cc."
            zigDistribution.set(project.provider { distribution })
            zigVersion.set(project.provider { distribution.version })
            toolchainsDir.set(gradleHome)
            zigCacheDir.set(gradleHome.resolve("zig-cache"))
            target.convention("wasm32-wasi")
            stackSize.convention(ZigCcWasm.DEFAULT_STACK_SIZE)
            outputPath.convention("$resourceDir/$name.wasm")
            outputDir.convention(project.layout.buildDirectory.dir("generated/wasm/$name"))
            action.execute(this)
        }
        project.extensions.getByType(SourceSetContainer::class.java).named(sourceSet) {
            resources.srcDir(task)
        }
        return task
    }

    /**
     * Zig のソース（`.zig`）の WASM のモジュールを1つ登録する（#32）。
     *
     * タスク `compile<Name>Wasm`（[ZigBuildExe]）を作り、`<resourceDir>/<name>.wasm` に出力して、[sourceSet] のリソースに加える。
     */
    @JvmOverloads
    fun zigModule(
        name: String,
        sourceSet: String = "main",
        resourceDir: String = "kibicomputers/wasm",
        action: Action<ZigBuildExe>,
    ): TaskProvider<ZigBuildExe> {
        val gradleHome = project.gradle.gradleUserHomeDir.resolve("kibicomputers")
        val task = project.tasks.register(taskName("compile", name, "Wasm"), ZigBuildExe::class.java) {
            group = "build"
            description = "Compiles the $name WASM module with zig build-exe."
            zigDistribution.set(project.provider { distribution })
            zigVersion.set(project.provider { distribution.version })
            toolchainsDir.set(gradleHome)
            zigCacheDir.set(gradleHome.resolve("zig-cache"))
            target.convention("wasm32-wasi")
            optimize.convention("ReleaseSmall")
            stackSize.convention(ZigCcWasm.DEFAULT_STACK_SIZE)
            outputPath.convention("$resourceDir/$name.wasm")
            outputDir.convention(project.layout.buildDirectory.dir("generated/wasm/$name"))
            action.execute(this)
        }
        project.extensions.getByType(SourceSetContainer::class.java).named(sourceSet) {
            resources.srcDir(task)
        }
        return task
    }

    /**
     * MoonBit のモジュールを `moon build --target wasm` でビルドするタスク `compile<Name>MoonBit`（[MoonBuildWasm]）を登録し、
     * その出力を [sourceSet] のリソースに加える（#32）。出力するパッケージは [MoonBuildWasm.packages] で指定する。
     *
     * MoonBit の版とハッシュはルートの `gradle/moonbit.properties` で固定し、取得したものは `<Gradle ホーム>/kibicomputers/moonbit/` に置く。
     * MoonBit は、Gradle のプロパティ `kibi.moonbit` が `true` のとき、または環境変数 `CI` が `true` のときだけビルドする
     * （#32 のオーナーの回答。手元の作業環境では MoonBit のツールチェーンを動かさない）。ビルドしないときはタスクを飛ばし、
     * リソースに MoonBit の `.wasm` は入らない。
     */
    fun moonbit(name: String, sourceSet: String = "main", action: Action<MoonBuildWasm>): TaskProvider<MoonBuildWasm> {
        val gradleHome = project.gradle.gradleUserHomeDir.resolve("kibicomputers")
        val enabled = moonbitEnabled
        val task = project.tasks.register(taskName("compile", name, "MoonBit"), MoonBuildWasm::class.java) {
            group = "build"
            description = "Compiles the $name MoonBit module to WASM with moon build."
            moonbitDistribution.set(project.provider { moonbitDist })
            moonbitVersion.set(project.provider { moonbitDist.version })
            toolchainsDir.set(gradleHome)
            outputDir.convention(project.layout.buildDirectory.dir("generated/moonbit/$name"))
            wasmOptSuffix.convention("-opt")
            onlyIf("kibi.moonbit か CI のときだけ MoonBit をビルドする") { enabled }
            action.execute(this)
        }
        project.extensions.getByType(SourceSetContainer::class.java).named(sourceSet) {
            resources.srcDir(task)
        }
        return task
    }

    /** MoonBit をビルドするか（[moonbit] を見る） */
    val moonbitEnabled: Boolean
        get() = project.providers.gradleProperty("kibi.moonbit").orNull?.toBoolean()
            ?: (project.providers.environmentVariable("CI").orNull?.toBoolean() ?: false)

    private val moonbitDist: MoonBitDistribution by lazy {
        MoonBitDistribution.load(project.rootDir.resolve("gradle/moonbit.properties"))
    }

    private fun taskName(prefix: String, name: String, suffix: String): String =
        prefix + name.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + suffix

    /**
     * C のソースのアーカイブを取得・展開するタスク `download<Name>Source` を登録する。
     *
     * 展開先は `build/sources/<name>/`。取得したアーカイブは `<Gradle ホーム>/kibicomputers/downloads/` に置く
     * （CI はここをキャッシュする）。[module] の `sources` に、このタスクの `outputDir` から渡す。
     */
    fun sourceArchive(name: String, action: Action<SourceArchive>): TaskProvider<SourceArchive> {
        val taskName = "download" + name.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Source"
        return project.tasks.register(taskName, SourceArchive::class.java) {
            group = "build"
            description = "Downloads and extracts the $name sources."
            downloadsDir.set(project.gradle.gradleUserHomeDir.resolve("kibicomputers/downloads"))
            outputDir.convention(project.layout.buildDirectory.dir("sources/$name"))
            action.execute(this)
        }
    }
}
