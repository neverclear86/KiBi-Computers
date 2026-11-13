package pro.ikili.kibicomputers.core.runtime.component.canon

import pro.ikili.kibicomputers.core.runtime.FakeMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/**
 * 呼ばれた順と引数を記録する realloc（[next] から順に取る）。縮めるときはその場で返し、広げるときは新しく取って中身を写す
 */
internal class RecordingRealloc(private val memory: WasmMemory, var next: Int = 0x1000) : CanonRealloc {
    data class Call(val old: Int, val oldSize: Int, val align: Int, val newSize: Int)

    val calls = ArrayList<Call>()

    /** 次に返す場所を差し替える（`null` なら普通に取る） */
    var override: Int? = null

    override fun realloc(oldPtr: Int, oldSize: Int, align: Int, newSize: Int): Int {
        calls += Call(oldPtr, oldSize, align, newSize)
        override?.let { return it }
        if (oldPtr != 0 && newSize <= oldSize) return oldPtr
        next = (next + align - 1) / align * align
        val p = next
        next += maxOf(newSize, 1)
        if (oldPtr != 0 && oldSize > 0) memory.write(p, memory.read(oldPtr, minOf(oldSize, newSize)))
        return p
    }
}

/** テスト用の文脈 */
internal fun canonContext(
    memory: WasmMemory = FakeMemory(1),
    encoding: CanonStringEncoding = CanonStringEncoding.UTF8,
    realloc: CanonRealloc? = RecordingRealloc(memory),
    resources: CanonResources = CanonResources.UNSUPPORTED,
    budget: CanonBudget = CanonBudget(),
): CanonContext = CanonContext(memory, encoding, realloc, resources, budget)
