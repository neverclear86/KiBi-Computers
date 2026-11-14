package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.component.ComponentImportPolicy
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonFunction
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/** リンクのテストの道具（#263） */
internal object LinkTesting {
    /** 名前の形を見ない import の判定（テストの名前空間 `test:*` を通す） */
    val ANY_IMPORT: ComponentImportPolicy = ComponentImportPolicy { null }

    val runtime: WasmRuntime by lazy { EndiveRuntime() }

    fun wat(text: String): ByteArray = WitTool.wat(text)

    fun load(
        text: String,
        host: ComponentHost = ComponentHost.NONE,
        metered: Boolean = false,
        runtime: WasmRuntime = this.runtime,
        validate: ((ByteArray) -> Unit)? = null,
    ): ComponentProgram = ComponentProgram.load(runtime, wat(text), host, metered, policy = ANY_IMPORT, validate = validate)

    fun instantiate(program: ComponentProgram, env: LinkEnv = LinkEnv.NONE, fuel: Long = 10_000_000): LinkedComponent =
        LinkedComponent.instantiate(program.plan, env, if (program.metered) WasmSharedFuel(fuel) else null)

    fun fn(params: List<CanonType>, result: CanonType?): CanonFunction = CanonFunction(params, result)

    fun host(name: String, vararg functions: Pair<String, HostFunction>, resources: Map<String, Int> = emptyMap()): HostInterface =
        HostInterface(name, functions.toMap(), resources)

    fun u32(v: Int): CanonValue = CanonValue.I32(v)

    fun LinkedComponent.call(vararg path: String, args: List<CanonValue> = emptyList()): CanonValue? =
        call(requireNotNull(function(*path)) { "export ${path.joinToString("/")} がない" }, args)
}
