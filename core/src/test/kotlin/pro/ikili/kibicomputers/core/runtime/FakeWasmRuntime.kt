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

    /** 読み込みのたびに、読み込む前に呼ぶ（読み込みを止めておくテスト用。#344） */
    @Volatile
    var beforeLoad: ((ByteArray) -> Unit)? = null

    /** [bytes] を読み込んだら [module] になるようにする。 */
    fun define(bytes: ByteArray, module: FakeModule): FakeWasmRuntime = apply { synchronized(definitions) { definitions[key(bytes)] = module } }

    override fun load(bytes: ByteArray): WasmModule {
        beforeLoad?.invoke(bytes)
        return synchronized(definitions) { definitions[key(bytes)] } ?: throw WasmLoadException("ダミーのランタイムに登録されていない WASM")
    }

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

    /**
     * 命令を [count] 個実行したことにして、燃料を減らす。計測しないモジュールでは燃料は減らさない。
     * Endive をまねて、スレッドが割り込まれていれば [WasmTrapKind.INTERRUPTED] のトラップを投げる。
     */
    fun step(count: Long = 1) = instance.step(count)

    /**
     * 挿入されたコードのまね（ADR 0028 のローカル）: 残りをローカル変数に写して命令を [count] 個実行し、最後にグローバルへ書き戻す。
     * 燃料切れの処理には、ローカルの残りを渡す（グローバルは古いまま）。[trapAtEnd] なら、書き戻さずにトラップする。
     */
    fun spinLocal(count: Long, trapAtEnd: Boolean = false) = instance.spinLocal(count, trapAtEnd)

    /** 燃料切れの処理を、グローバルに触れずに直接呼ぶ（残り [remaining]）。戻り値は新しい残り */
    fun callFuelHandler(remaining: Long): Long = instance.callFuelHandler(remaining)

    /** このインスタンス */
    val self: WasmInstance get() = instance

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
    override val isAsyncified: Boolean get() = false

    override val exports: List<WasmExportDescriptor> = functions.map { (name, f) -> WasmExportDescriptor(name, WasmExternKind.FUNCTION, f.first) } +
        listOfNotNull(memoryPages?.let { WasmExportDescriptor("memory", WasmExternKind.MEMORY, null) })

    override fun instantiate(config: WasmInstanceConfig): WasmInstance {
        for (imp in imports) {
            val host = config.hostImport(imp.module, imp.name) ?: throw WasmLinkException("import がない: $imp")
            if (host.type != imp.functionType) throw WasmLinkException("import の型が違う: $imp に $host")
        }
        memoryPages?.let { pages ->
            if (pages > minOf(FakeMemory.MAX_PAGES, config.maxMemoryPages)) throw WasmLinkException("ダミー: メモリの最初のページ数が上限を超える")
            if (!config.writtenMemory && pages > 0 && config.memoryBudget?.tryReserve(pages) == false) throw WasmLinkException("ダミー: メモリを確保できない")
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
    @Volatile
    private var fuelValue: Long = 0

    private val fakeMemory: FakeMemory? = module.memoryPages?.let { pages ->
        val max = minOf(FakeMemory.MAX_PAGES, config.maxMemoryPages)
        if (config.writtenMemory) {
            // #407: データは ROM として書き、書き換えた 4 KiB の区画だけを数える
            FakeMemory(pages, max, written = FakeWrittenCount(config.writtenMemoryBudget, config.onMemoryGrowRefused)).also { m ->
                module.data.forEach { (offset, bytes) -> m.write(offset, bytes) }
                m.startCounting()
            }
        } else {
            FakeMemory(pages, max, config.memoryBudget).also { m -> module.data.forEach { (offset, bytes) -> m.write(offset, bytes) } }
        }
    }

    override val memory: WasmMemory? get() = fakeMemory

    override var fuel: Long
        get() {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            return fuelValue
        }
        set(value) {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            fuelValue = value
        }

    override var asyncState: WasmAsyncState
        get() = throw IllegalStateException("待機点への変換をかけていないモジュール")
        set(_) = throw IllegalStateException("待機点への変換をかけていないモジュール")

    private fun checkInterrupt() {
        if (Thread.currentThread().isInterrupted) throw WasmTrapException(WasmTrapKind.INTERRUPTED, "ダミー: 割り込まれた")
    }

    fun step(count: Long) {
        checkInterrupt()
        if (!module.isFuelMetered) return
        fuelValue -= count
        if (fuelValue < 0) {
            val handler = config.fuelHandler ?: throw WasmTrapException(WasmTrapKind.FUEL_EXHAUSTED, "燃料が切れた")
            fuelValue = handler.onFuelExhausted(this, fuelValue)
        }
    }

    fun spinLocal(count: Long, trapAtEnd: Boolean) {
        var local = if (module.isFuelMetered) fuelValue else 0
        for (i in 0 until count) {
            checkInterrupt()
            if (!module.isFuelMetered) continue
            local -= 1
            if (local < 0) local = callFuelHandler(local)
        }
        if (trapAtEnd) throw WasmTrapException(WasmTrapKind.UNREACHABLE, "ダミー: 書き戻さずにトラップ")
        if (module.isFuelMetered) fuelValue = local
    }

    fun callFuelHandler(remaining: Long): Long {
        val handler = config.fuelHandler ?: throw WasmTrapException(WasmTrapKind.FUEL_EXHAUSTED, "燃料が切れた")
        return handler.onFuelExhausted(this, remaining)
    }

    fun callImport(moduleName: String, name: String, args: LongArray): LongArray {
        val host = config.hostImport(moduleName, name)
        require(host != null && module.imports.any { it.module == moduleName && it.name == name }) { "import していない: $moduleName.$name" }
        return host.function.call(this, args)
    }

    fun run(function: FakeFunction, args: LongArray): LongArray = function.run(FakeContext(this), args)

    private var closed = false

    override fun call(name: String, vararg args: Long): LongArray {
        check(!closed) { "閉じたインスタンス" }
        val (type, function) = requireNotNull(module.functions[name]) { "関数 $name は export されていない" }
        require(args.size == type.params.size) { "関数 $name の引数は ${type.params.size} 個（${args.size} 個渡された）" }
        return run(function, args)
    }

    override fun close() {
        if (closed) return
        closed = true
        fakeMemory?.releaseAll()
    }
}

/**
 * 書き換えた 4 KiB の区画を数える（#407。Endive の `WrittenPagesMemory` と同じ規則のダミー）。
 * 書き込みで数えられなければ [WasmTrapKind.OUT_OF_MEMORY] のトラップ。grow は増やした所の最初と最後の区画を数え、
 * 数えられなければ -1 にして [onGrowRefused] を呼ぶ
 */
internal class FakeWrittenCount(val budget: WasmTableBudget?, val onGrowRefused: Runnable?) {
    val written = java.util.BitSet()
    var count = 0
    var counting = false

    /** [chunk] を書き換えた区画として数える。数えられなければトラップ */
    fun touch(chunk: Int) {
        if (!counting || written[chunk]) return
        if (budget != null && !budget.tryReserveBytes(CHUNK.toLong())) throw WasmTrapException(WasmTrapKind.OUT_OF_MEMORY, "ダミー: RAM が足りない")
        written.set(chunk)
        count++
    }

    fun releaseAll() {
        if (count > 0) budget?.releaseBytes(count.toLong() * CHUNK)
        count = 0
        counting = false
    }

    companion object {
        const val CHUNK = 4096
    }
}

/**
 * ByteArray のメモリ。上限は [maxPages]（16 ページまで）。[budget] があれば、確保を数える（最初の分は作る側が数える）。
 * [written] があれば、ページ数ではなく書き換えた 4 KiB の区画を数える（#407）
 */
internal class FakeMemory(
    initialPages: Int,
    private val maxPages: Int = MAX_PAGES,
    private val budget: WasmMemoryBudget? = null,
    private val written: FakeWrittenCount? = null,
) : WasmMemory {
    private var bytes = ByteArray(initialPages * WasmMemory.PAGE_SIZE)

    /** ここまでに書いた分を ROM とし、以後の書き込みを数える */
    fun startCounting() {
        written?.counting = true
    }

    /** 書き換えた区画の数（#407） */
    val writtenChunks: Int get() = written?.count ?: -1

    private fun touch(start: Int, length: Int) {
        val w = written ?: return
        if (length <= 0) return
        for (c in (start / FakeWrittenCount.CHUNK)..((start + length - 1) / FakeWrittenCount.CHUNK)) w.touch(c)
    }

    /** [budget] から確保したページ数 */
    private var reserved = if (budget != null) initialPages else 0

    override val pages: Int get() = bytes.size / WasmMemory.PAGE_SIZE

    override fun grow(deltaPages: Int): Int {
        val old = pages
        if (deltaPages < 0 || old + deltaPages > maxPages) return -1
        if (deltaPages > 0 && written != null && written.counting) {
            val head = old * WasmMemory.PAGE_SIZE / FakeWrittenCount.CHUNK
            val tail = (old + deltaPages) * WasmMemory.PAGE_SIZE / FakeWrittenCount.CHUNK - 1
            val edges = if (head == tail) 1 else 2
            if (written.budget != null && !written.budget.tryReserveBytes(edges.toLong() * FakeWrittenCount.CHUNK)) {
                written.onGrowRefused?.run()
                return -1
            }
            written.written.set(head)
            written.written.set(tail)
            written.count += edges
        }
        if (deltaPages > 0 && budget != null) {
            if (!budget.tryReserve(deltaPages)) return -1
            reserved += deltaPages
        }
        bytes = bytes.copyOf((old + deltaPages) * WasmMemory.PAGE_SIZE)
        return old
    }

    /**
     * `kibi:memory` の `commit`（#408）。[written] があれば、範囲にかかる区画のうちまだ数えていないものを、
     * 残りが [keepBytes] 以上残るときだけまとめて数える（Endive の `WrittenPagesMemory.commitRange` と同じ規則）
     */
    override fun commit(address: Int, length: Int, keepBytes: Long): Boolean {
        val start = address.toUInt().toLong()
        val len = length.toUInt().toLong()
        if (start + len > bytes.size) throw WasmMemoryAccessException("メモリの範囲外: $start+$len")
        val w = written
        if (len == 0L || w == null || !w.counting) return true
        val first = (start / FakeWrittenCount.CHUNK).toInt()
        val last = ((start + len - 1) / FakeWrittenCount.CHUNK).toInt()
        val fresh = (first..last).count { !w.written[it] }
        if (fresh == 0) return true
        if (w.budget != null && !w.budget.tryReserveBytes(fresh.toLong() * FakeWrittenCount.CHUNK, keepBytes)) return false
        for (i in first..last) w.written.set(i)
        w.count += fresh
        return true
    }

    fun releaseAll() {
        if (reserved > 0) budget?.release(reserved)
        reserved = 0
        written?.releaseAll()
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
        val a = check(address, length)
        touch(a, length)
        System.arraycopy(src, srcOffset, bytes, a, length)
    }

    override fun readU8(address: Int): Int = bytes[check(address, 1)].toInt() and 0xff

    override fun writeU8(address: Int, value: Int) {
        val a = check(address, 1)
        touch(a, 1)
        bytes[a] = value.toByte()
    }

    override fun readI32(address: Int): Int {
        val a = check(address, 4)
        return (0 until 4).fold(0) { acc, i -> acc or ((bytes[a + i].toInt() and 0xff) shl (8 * i)) }
    }

    override fun writeI32(address: Int, value: Int) {
        val a = check(address, 4)
        touch(a, 4)
        for (i in 0 until 4) bytes[a + i] = (value ushr (8 * i)).toByte()
    }

    override fun readI64(address: Int): Long {
        val a = check(address, 8)
        return (0 until 8).fold(0L) { acc, i -> acc or ((bytes[a + i].toLong() and 0xff) shl (8 * i)) }
    }

    override fun writeI64(address: Int, value: Long) {
        val a = check(address, 8)
        touch(a, 8)
        for (i in 0 until 8) bytes[a + i] = (value ushr (8 * i)).toByte()
    }

    companion object {
        const val MAX_PAGES = 16
    }
}
