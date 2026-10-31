package pro.ikili.kibicomputers.poc.endivebench.aotgen

import run.endive.build.time.compiler.Config
import run.endive.build.time.compiler.Generator
import run.endive.compiler.InterpreterFallback
import run.endive.redline.experimental.build.RedlineGenerator
import java.nio.file.Path

/**
 * ビルド時 AOT（Endive の build-time compiler）を Gradle から呼ぶための入口。
 * Maven プラグイン（`EndiveCompilerGenMojo`）と同じ順で生成する。
 *
 * 引数: `<wasm> <クラス名> <クラスの出力先> <ソースの出力先> <メタ wasm の出力先> [Redline のターゲット（カンマ区切り）]`
 */
fun main(args: Array<String>) {
    require(args.size >= 5) { "usage: AotGen <wasm> <name> <classes> <sources> <wasmDir> [redlineTargets]" }
    val redline = args.getOrNull(5)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    val config = Config.builder()
        .withWasmFile(Path.of(args[0]))
        .withName(args[1])
        .withTargetClassFolder(Path.of(args[2]))
        .withTargetSourceFolder(Path.of(args[3]))
        .withTargetWasmFolder(Path.of(args[4]))
        // JVM のメソッドの大きさの上限を超える関数はインタプリタで動かす。どれが該当したかは測定結果に出す
        .withInterpreterFallback(InterpreterFallback.WARN)
        .withRedlineTargets(redline)
        .build()

    val t0 = System.nanoTime()
    val generator = Generator(config)
    val interpreted = generator.generateResources()
    generator.generateMetaWasm(interpreted)
    generator.generateSources()
    val t1 = System.nanoTime()
    println("AOT ${args[1]}: bytecode ${(t1 - t0) / 1_000_000} ms, interpreted functions: ${interpreted.sorted()}")
    if (config.hasRedlineTargets()) {
        val redlineGenerator = RedlineGenerator(config)
        redlineGenerator.generateNativeCode()
        redlineGenerator.extendGeneratedSources()
        println("AOT ${args[1]}: redline $redline ${(System.nanoTime() - t1) / 1_000_000} ms")
    }
}
