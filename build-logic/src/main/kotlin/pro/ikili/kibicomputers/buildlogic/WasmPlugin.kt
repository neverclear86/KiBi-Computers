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
            outputPath.convention("$resourceDir/$name.wasm")
            outputDir.convention(project.layout.buildDirectory.dir("generated/wasm/$name"))
            action.execute(this)
        }
        project.extensions.getByType(SourceSetContainer::class.java).named(sourceSet) {
            resources.srcDir(task)
        }
        return task
    }
}
