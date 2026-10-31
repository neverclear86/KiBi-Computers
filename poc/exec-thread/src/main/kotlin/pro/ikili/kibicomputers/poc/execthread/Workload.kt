package pro.ikili.kibicomputers.poc.execthread

import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.wasm.Parser
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType

/** WASM から呼ばれるホストの関数。コンピューターのスレッドで呼ばれる。 */
interface Host {
    /** 燃料を [units] 使う。燃料が尽きたら、次の tick まで戻らない */
    fun fuel(units: Int)

    /** ワールドの [key] に [delta] を足して、足した後の値を返す。ワールドはメインスレッドでしか触れない */
    fun worldAdd(key: Int, delta: Int): Int
}

/**
 * 負荷の WASM（`src/main/wasm/work.c`）。
 *
 * 実行方式はランタイムコンパイラ。比べたいのはスレッドの方式なので、どの方式でも同じでよい（#26 で、AOT と速さは同じ）。
 * コンパイルは1回だけ行い、全コンピューターで共有する。
 */
object Workload {
    private val compiled by lazy {
        val bytes = requireNotNull(Workload::class.java.getResourceAsStream("/kibicomputers/wasm/work.wasm")) { "work.wasm がない" }
            .use { it.readBytes() }
        MachineFactoryCompiler.builder(Parser.parse(bytes))
            .withInterpreterFallback(InterpreterFallback.FAIL)
            .compileModule()
    }

    /** [host] につないだインスタンスを作る。 */
    fun instantiate(host: Host): Instance {
        val fuel = HostFunction("env", "fuel", FunctionType.of(listOf(ValType.I32), listOf())) { _, args ->
            host.fuel(args[0].toInt())
            null
        }
        val worldAdd = HostFunction("env", "world_add", FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32))) { _, args ->
            longArrayOf(host.worldAdd(args[0].toInt(), args[1].toInt()).toLong())
        }
        return Instance.builder(compiled)
            .withImportValues(ImportValues.builder().addFunction(fuel, worldAdd).build())
            .build()
    }

    /** 燃料の数えない [Host]（較正と、スタックの深さを調べるのに使う） */
    object FreeHost : Host {
        override fun fuel(units: Int) {}

        override fun worldAdd(key: Int, delta: Int): Int = 0
    }

    /** 燃料 1 単位を使う計算にかかる時間（ナノ秒）を、このスレッドで測る。 */
    fun nanosPerUnit(): Double {
        val instance = instantiate(FreeHost)
        val burn = instance.export("burn")
        repeat(20) { burn.apply(2000) }
        val samples = (1..15).map {
            val start = System.nanoTime()
            burn.apply(2000)
            (System.nanoTime() - start) / 2000.0
        }.sorted()
        return samples[samples.size / 2]
    }
}

/** コンピューターが動かすプログラム（`work.c` の関数）。 */
enum class Program(val export: String) {
    /** 終わらない計算 */
    CPU("run_forever"),

    /** 燃料 1 単位ごとにワールドを 1 回操作する。終わらない */
    WORLD("world_forever"),
    ;

    fun run(instance: Instance) {
        when (this) {
            CPU -> instance.export(export).apply()
            WORLD -> instance.export(export).apply(1)
        }
    }
}
