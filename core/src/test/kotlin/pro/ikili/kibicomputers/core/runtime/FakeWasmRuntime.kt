package pro.ikili.kibicomputers.core.runtime

import java.util.Base64

/**
 * テスト用のダミーの [WasmRuntime]（#34）。WASM を実行せず、バイト列ごとに Kotlin で書いた振る舞いを返す。
 *
 * カーネルのテストで、WASM を用意せずにシステムコールや燃料・トラップの扱いを確かめるために使う。
 * [define] で登録していないバイト列は [WasmLoadException] になる。
 */
class FakeWasmRuntime : WasmRuntime {
    private val definitions = HashMap<String, FakeModule>()

    override val name: String get() = "fake"

    /** [bytes] を読み込んだら [module] になるようにする。 */
    fun define(bytes: ByteArray, module: FakeModule): FakeWasmRuntime = apply { definitions[key(bytes)] = module }

    override fun load(bytes: ByteArray): WasmModule = definitions[key(bytes)] ?: throw WasmLoadException("ダミーのランタイムに登録されていない WASM")

    private fun key(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/** ダミーの関数の本体。 */
fun interface FakeFunction {
    fun run(context: FakeContext, args: LongArray): LongArray
}

/** ダミーの関数が、import の呼び出し・燃料・メモリ・トラップを扱うための口。 */
class FakeContext internal constructor(private val instance: FakeInstance) {
    /** import した関数を呼ぶ（ホストの関数の例外はそのまま出る）。 */
    fun callImport(module: String, name: String, vararg args: Long): LongArray = instance.callImport(module, name, args)

    /** 命令を [count] 個実行したことにして、燃料を減らす。計測しないモジュールでは何もしない。 */
    fun step(count: Long = 1) = instance.step(count)

    /** WASM の中からメモリを読む。範囲外はトラップ。 */
    fun loadU8(address: Int): Int = try {
        instance.memory!!.readU8(address)
    } catch (e: WasmMemoryAccessException) {
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS)
    }

    val memory: WasmMemory get() = instance.memory!!

    fun trap(kind: WasmTrapKind): Nothing = throw WasmTrapException(kind, "ダミーのトラップ: $kind")
}

/** ダミーのモジュール。[builder] で作る。 */
class FakeModule private constructor(
    override val imports: List<WasmImportDescriptor>,
    internal val functions: Map<String, Pair<WasmFunctionType, FakeFunction>>,
    internal val memoryPages: Int?,
    internal val data: List<Pair<Int, ByteArray>>,
    override val isFuelMetered: Boolean,
    internal val start: FakeFunction?,
) : WasmModule {
    override val exports: List<WasmExportDescriptor> = functions.map { (name, f) -> WasmExportDescriptor(name, WasmExternKind.FUNCTION, f.first) } +
        listOfNotNull(memoryPages?.let { WasmExportDescriptor("memory", WasmExternKind.MEMORY, null) })

    override fun instantiate(config: WasmInstanceConfig): WasmInstance {
        for (imp in imports) {
            val host = config.hostImport(imp.module, imp.name) ?: throw WasmLinkException("import がない: $imp")
            if (host.type != imp.functionType) throw WasmLinkException("import の型が違う: $imp に $host")
        }
        val instance = FakeInstance(this, config)
        if (isFuelMetered) instance.fuel = config.initialFuel
        start?.let { instance.run(it, LongArray(0)) }
        return instance
    }

    class Builder {
        private val imports = mutableListOf<WasmImportDescriptor>()
        private val functions = LinkedHashMap<String, Pair<WasmFunctionType, FakeFunction>>()
        private var memoryPages: Int? = null
        private val data = mutableListOf<Pair<Int, ByteArray>>()
        private var fuelMetered = false
        private var start: FakeFunction? = null

        fun import(module: String, name: String, type: WasmFunctionType) = apply { imports += WasmImportDescriptor(module, name, WasmExternKind.FUNCTION, type) }

        fun export(name: String, type: WasmFunctionType, function: FakeFunction) = apply { functions[name] = type to function }

        fun memory(pages: Int) = apply { memoryPages = pages }

        fun data(offset: Int, bytes: ByteArray) = apply { data += offset to bytes }

        fun fuelMetered() = apply { fuelMetered = true }

        fun start(function: FakeFunction) = apply { start = function }

        fun build(): FakeModule = FakeModule(imports.toList(), functions.toMap(), memoryPages, data.toList(), fuelMetered, start)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

internal class FakeInstance(override val module: FakeModule, private val config: WasmInstanceConfig) : WasmInstance {
    private var fuelValue: Long = 0

    override val memory: WasmMemory? = module.memoryPages?.let { pages ->
        FakeMemory(pages).also { m -> module.data.forEach { (offset, bytes) -> m.write(offset, bytes) } }
    }

    override var fuel: Long
        get() {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            return fuelValue
        }
        set(value) {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            fuelValue = value
        }

    fun step(count: Long) {
        if (!module.isFuelMetered) return
        fuelValue -= count
        if (fuelValue < 0) {
            val handler = config.fuelHandler ?: throw WasmTrapException(WasmTrapKind.FUEL_EXHAUSTED, "燃料が切れた")
            fuelValue = handler.onFuelExhausted(this, fuelValue)
        }
    }

    fun callImport(moduleName: String, name: String, args: LongArray): LongArray {
        val host = config.hostImport(moduleName, name)
        require(host != null && module.imports.any { it.module == moduleName && it.name == name }) { "import していない: $moduleName.$name" }
        return host.function.call(this, args)
    }

    fun run(function: FakeFunction, args: LongArray): LongArray = function.run(FakeContext(this), args)

    override fun call(name: String, vararg args: Long): LongArray {
        val (type, function) = requireNotNull(module.functions[name]) { "関数 $name は export されていない" }
        require(args.size == type.params.size) { "関数 $name の引数は ${type.params.size} 個（${args.size} 個渡された）" }
        return run(function, args)
    }

    override fun close() {}
}

/** ByteArray のメモリ。上限は 16 ページ。 */
internal class FakeMemory(initialPages: Int) : WasmMemory {
    private var bytes = ByteArray(initialPages * WasmMemory.PAGE_SIZE)

    override val pages: Int get() = bytes.size / WasmMemory.PAGE_SIZE

    override fun grow(deltaPages: Int): Int {
        val old = pages
        if (deltaPages < 0 || old + deltaPages > MAX_PAGES) return -1
        bytes = bytes.copyOf((old + deltaPages) * WasmMemory.PAGE_SIZE)
        return old
    }

    private fun check(address: Int, length: Int): Int {
        val start = address.toUInt().toLong()
        if (length < 0 || start + length > bytes.size) throw WasmMemoryAccessException("メモリの範囲外: $start+$length")
        return start.toInt()
    }

    override fun read(address: Int, dst: ByteArray, dstOffset: Int, length: Int) {
        System.arraycopy(bytes, check(address, length), dst, dstOffset, length)
    }

    override fun write(address: Int, src: ByteArray, srcOffset: Int, length: Int) {
        System.arraycopy(src, srcOffset, bytes, check(address, length), length)
    }

    override fun readU8(address: Int): Int = bytes[check(address, 1)].toInt() and 0xff

    override fun writeU8(address: Int, value: Int) {
        bytes[check(address, 1)] = value.toByte()
    }

    override fun readI32(address: Int): Int {
        val a = check(address, 4)
        return (0 until 4).fold(0) { acc, i -> acc or ((bytes[a + i].toInt() and 0xff) shl (8 * i)) }
    }

    override fun writeI32(address: Int, value: Int) {
        val a = check(address, 4)
        for (i in 0 until 4) bytes[a + i] = (value ushr (8 * i)).toByte()
    }

    override fun readI64(address: Int): Long {
        val a = check(address, 8)
        return (0 until 8).fold(0L) { acc, i -> acc or ((bytes[a + i].toLong() and 0xff) shl (8 * i)) }
    }

    override fun writeI64(address: Int, value: Long) {
        val a = check(address, 8)
        for (i in 0 until 8) bytes[a + i] = (value ushr (8 * i)).toByte()
    }

    companion object {
        const val MAX_PAGES = 16
    }
}
