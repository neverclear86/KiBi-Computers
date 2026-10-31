package pro.ikili.kibicomputers.poc.waitpointsave

import run.endive.runtime.Memory
import run.endive.wasm.types.MutabilityType
import run.endive.wasm.types.PassiveDataSegment
import run.endive.wasm.types.PassiveElement
import run.endive.wasm.types.ValType
import run.endive.wasm.types.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** 保存した可変のグローバル。 */
data class SavedGlobal(val index: Int, val valType: Int, val low: Long, val high: Long)

/** 保存したテーブル（関数の番号。null は -1）。 */
class SavedTable(val index: Int, val refs: IntArray)

/** 保存データを別のモジュールに戻そうとした。 */
class ModuleMismatchException(message: String) : IllegalArgumentException(message)

/**
 * 待機点で取り出したプロセスの状態。
 *
 * 保存するのは、線形メモリ・可変のグローバル・テーブル・drop 済みのセグメント・カーネルの状態。
 * WASM のスタック（値スタック・呼び出しのフレーム）は、待機点では空なので保存しない。
 * 例外のタグ（`__c_longjmp`）は状態を持たず、待機点では投げている途中の例外もないので保存しない。
 *
 * バイト列の形（[toBytes]）はサイズを測るための PoC の中だけのもので、本番の保存形式（#81・#82・#88）ではない。
 */
class Snapshot(
    val moduleSha256: ByteArray,
    val pages: Int,
    val memory: ByteArray,
    val globals: List<SavedGlobal>,
    val tables: List<SavedTable>,
    val droppedData: IntArray,
    val droppedElements: IntArray,
    val kernel: ByteArray,
    /** 復元に使えるか（[measureOnly] で取ったものは使えない） */
    val restorable: Boolean = true,
) {
    /** 生のバイト列（変種 A） */
    fun toBytes(): ByteArray = encode(skipZeroPages = false)

    /** 全部 0 のページを省いたバイト列（変種 C の圧縮前） */
    fun toBytesSkippingZeroPages(): ByteArray = encode(skipZeroPages = true)

    private fun encode(skipZeroPages: Boolean): ByteArray {
        check(restorable) { "測定用のスナップショットはバイト列にしない" }
        val bytes = ByteArrayOutputStream(memory.size + kernel.size + 4096)
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeShort(if (skipZeroPages) VERSION_SPARSE else VERSION)
            out.write(moduleSha256)
            out.writeInt(pages)
            if (skipZeroPages) {
                val nonZero = (0 until pages).filter { !isZeroPage(memory, it) }
                out.writeInt(nonZero.size)
                nonZero.forEach(out::writeInt)
                nonZero.forEach { out.write(memory, it * Memory.PAGE_SIZE, Memory.PAGE_SIZE) }
            } else {
                out.write(memory)
            }
            out.writeInt(globals.size)
            globals.forEach {
                out.writeInt(it.index)
                out.writeByte(it.valType)
                out.writeLong(it.low)
                out.writeLong(it.high)
            }
            out.writeInt(tables.size)
            tables.forEach { t ->
                out.writeInt(t.index)
                out.writeInt(t.refs.size)
                t.refs.forEach(out::writeInt)
            }
            out.writeInt(droppedData.size)
            droppedData.forEach(out::writeInt)
            out.writeInt(droppedElements.size)
            droppedElements.forEach(out::writeInt)
            out.writeInt(kernel.size)
            out.write(kernel)
        }
        return bytes.toByteArray()
    }

    /** このスナップショットからプロセスを作り直す。`_initialize` は呼ばない。 */
    fun restore(program: WasmProgram, mode: Mode): Process {
        check(restorable) { "測定用のスナップショットからは復元できない" }
        if (!moduleSha256.contentEquals(program.sha256)) throw ModuleMismatchException("別のモジュールの保存データ（$program に戻そうとした）")
        val process = Process.instantiate(program, mode, MiniKernel.fromBytes(kernel))
        apply(process)
        return process
    }

    /** 作ったばかりのインスタンスに、メモリ・グローバル・テーブル・drop 済みのセグメントを書き戻す。 */
    fun apply(process: Process) {
        val instance = process.instance
        val mem = instance.memory()
        val current = mem.pages()
        check(current <= pages) { "保存したときよりメモリが大きい（$current > $pages ページ）" }
        if (current < pages) check(mem.grow(pages - current) == current) { "メモリを $pages ページに広げられない" }
        mem.write(0, memory, 0, memory.size)
        for (g in globals) {
            val global = instance.global(g.index)
            check(global.mutabilityType == MutabilityType.Var && valTypeId(global.type) == g.valType) { "グローバル ${g.index} の型が違う" }
            global.valueLow = g.low
            global.valueHigh = g.high
        }
        for (t in tables) {
            val table = instance.table(t.index)
            if (table.size() < t.refs.size) table.grow(t.refs.size - table.size(), Value.REF_NULL_VALUE, instance)
            check(table.size() == t.refs.size) { "テーブル ${t.index} の大きさが違う" }
            t.refs.forEachIndexed { i, ref -> table.setRef(i, ref, if (ref == Value.REF_NULL_VALUE) null else instance) }
        }
        droppedData.forEach {
            instance.dropDataSegment(it)
            mem.drop(it)
        }
        droppedElements.forEach { instance.setElement(it, null) }
    }

    companion object {
        private val MAGIC = "KWPS".toByteArray()
        private const val VERSION = 1
        private const val VERSION_SPARSE = 2

        /**
         * 待機点でプロセスの状態を取り出す。
         *
         * @throws NotAtWaitPointException WASM の中にいる（export の呼び出し中、ホスト関数の中で待っている）
         */
        fun capture(process: Process): Snapshot {
            if (process.kernel.inWasm) throw NotAtWaitPointException("WASM の中にいるので保存できない（${process.program}）")
            return take(process, restorable = true)
        }

        /** 比べるための測定用。WASM の中でも取れるが、復元には使えない（WASM のスタックが入らない） */
        fun measureOnly(process: Process): Snapshot = take(process, restorable = false)

        private fun take(process: Process, restorable: Boolean): Snapshot {
            val instance = process.instance
            val module = process.program.module
            val mem = instance.memory()
            val pages = mem.pages()
            val memory = mem.readBytes(0, pages * Memory.PAGE_SIZE)
            val globals = (0 until instance.globalCount()).mapNotNull { i ->
                val g = instance.global(i)
                if (g.mutabilityType != MutabilityType.Var) return@mapNotNull null
                SavedGlobal(i, valTypeId(g.type), g.valueLow, g.valueHigh)
            }
            val tables = (0 until instance.tableCount()).map { i ->
                val t = instance.table(i)
                SavedTable(i, IntArray(t.size()) { t.ref(it) })
            }
            // drop 済み: パッシブなセグメントのうち、中身が空になっているもの
            val segments = module.dataSection().dataSegments()
            val droppedData = segments.indices.filter { segments[it] is PassiveDataSegment && segments[it].data().isNotEmpty() && instance.dataSegmentData(it).isEmpty() }
            val elements = module.elementSection().elements()
            val droppedElements = elements.indices.filter { elements[it] is PassiveElement && instance.element(it) == null }
            return Snapshot(
                process.program.sha256,
                pages,
                memory,
                globals,
                tables,
                droppedData.toIntArray(),
                droppedElements.toIntArray(),
                process.kernel.toBytes(),
                restorable,
            )
        }

        private fun valTypeId(type: ValType): Int = when (type) {
            ValType.I32 -> 0x7f
            ValType.I64 -> 0x7e
            ValType.F32 -> 0x7d
            ValType.F64 -> 0x7c
            ValType.V128 -> 0x7b
            else -> throw IllegalStateException("参照型のグローバルには対応していない: $type")
        }

        fun isZeroPage(memory: ByteArray, page: Int): Boolean {
            val start = page * Memory.PAGE_SIZE
            for (i in start until start + Memory.PAGE_SIZE) if (memory[i].toInt() != 0) return false
            return true
        }

        /** [toBytes]・[toBytesSkippingZeroPages] のバイト列から読み直す。 */
        fun fromBytes(bytes: ByteArray): Snapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(4).also(input::readFully)
            check(magic.contentEquals(MAGIC)) { "保存データではない" }
            val version = input.readShort().toInt()
            check(version == VERSION || version == VERSION_SPARSE) { "版が違う: $version" }
            val sha = ByteArray(32).also(input::readFully)
            val pages = input.readInt()
            val memory = ByteArray(pages * Memory.PAGE_SIZE)
            if (version == VERSION_SPARSE) {
                val idx = IntArray(input.readInt()) { input.readInt() }
                idx.forEach { input.readFully(memory, it * Memory.PAGE_SIZE, Memory.PAGE_SIZE) }
            } else {
                input.readFully(memory)
            }
            val globals = List(input.readInt()) { SavedGlobal(input.readInt(), input.readUnsignedByte(), input.readLong(), input.readLong()) }
            val tables = List(input.readInt()) {
                val index = input.readInt()
                SavedTable(index, IntArray(input.readInt()) { input.readInt() })
            }
            val droppedData = IntArray(input.readInt()) { input.readInt() }
            val droppedElements = IntArray(input.readInt()) { input.readInt() }
            val kernel = ByteArray(input.readInt()).also(input::readFully)
            Snapshot(sha, pages, memory, globals, tables, droppedData, droppedElements, kernel)
        }
    }
}
