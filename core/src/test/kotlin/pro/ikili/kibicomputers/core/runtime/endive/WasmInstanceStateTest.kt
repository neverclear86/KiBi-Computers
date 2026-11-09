package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.persist.snapshot.InstanceStateCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotWriter
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I64
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.sleb
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmStateException
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmValueType

/** インスタンスの状態の取り出しと、状態からの作り直し（#82、ADR 0082 の 7.） */
class WasmInstanceStateTest {
    /** 上限 [limitBytes] のバイト数の勘定 */
    private class Budget(val limitBytes: Long = Long.MAX_VALUE) : WasmTableBudget {
        var used = 0L

        override fun tryReserveBytes(bytes: Long): Boolean = tryReserveBytes(bytes, 0)

        override fun tryReserveBytes(bytes: Long, keepBytes: Long): Boolean {
            if (used + bytes > limitBytes) return false
            used += bytes
            return true
        }

        override fun releaseBytes(bytes: Long) {
            used -= bytes
        }
    }

    private val passiveBytes = "PASSIVE".toByteArray()

    /**
     * 可変のグローバル（export しない）、関数ポインターのテーブル、ROM（能動なデータ）、パッシブなデータを持つモジュール。
     * export: inc・get・store(addr, v)・load(addr)・callAt(i)・dropP・initP・grow
     */
    private fun module(): ByteArray {
        val b = WasmBinary()
        b.memory(1)
        b.table(2)
        val g = b.mutableI64Global(0)
        val f10 = b.func(emptyList(), listOf(I64), code(0x42, sleb(10)))
        val f20 = b.func(emptyList(), listOf(I64), code(0x42, sleb(20)))
        val sig = b.typeIndex(emptyList(), listOf(I64))
        b.exportFunc("inc", b.func(emptyList(), emptyList(), code(0x23, g, 0x42, 1, 0x7c, 0x24, g)))
        b.exportFunc("get", b.func(emptyList(), listOf(I64), code(0x23, g)))
        b.exportFunc("store", b.func(listOf(I32, I64), emptyList(), code(0x20, 0, 0x20, 1, 0x37, 0x03, 0x00)))
        b.exportFunc("load", b.func(listOf(I32), listOf(I64), code(0x20, 0, 0x29, 0x03, 0x00)))
        b.exportFunc("callAt", b.func(listOf(I32), listOf(I64), code(0x20, 0, 0x11, sig, 0x00)))
        b.data(0, "ROMDATA!".toByteArray())
        val passive = b.passiveData(passiveBytes)
        b.exportFunc("dropP", b.func(emptyList(), emptyList(), code(0xFC, 9, passive)))
        b.exportFunc("initP", b.func(emptyList(), emptyList(), code(0x41, sleb(100), 0x41, 0, 0x41, passiveBytes.size, 0xFC, 8, passive, 0x00)))
        b.exportFunc("grow", b.func(emptyList(), emptyList(), code(0x41, 1, 0x40, 0x00, 0x1a)))
        b.elem(0, listOf(f10, f20))
        return b.build()
    }

    private fun config(budget: Budget): WasmInstanceConfig =
        WasmInstanceConfig.builder().writtenMemoryLimit(16, budget).build()

    private fun WasmInstance.i64(name: String, vararg args: Long): Long = call(name, *args)[0]

    @Test
    fun `取り出した状態から作り直すと、グローバル・メモリ・テーブル・dropしたセグメントが戻り、ROMの区画は書かない`() {
        val rt = EndiveRuntime()
        val module = rt.load(module())
        val budget = Budget()
        val state = module.instantiate(config(budget)).use { inst ->
            // 何もしていなければ ROM の区画は書いた区画に入らない
            val fresh = inst.captureState()
            assertEquals(0, fresh.writtenChunkCount)
            assertEquals(1, fresh.memoryPages)

            repeat(3) { inst.call("inc") }
            inst.call("grow")
            inst.call("store", (WasmMemory.PAGE_SIZE + 8).toLong(), 77)
            inst.call("store", 4096L * 3 + 16, 5)
            inst.call("dropP")
            inst.captureState()
        }
        assertEquals(2, state.memoryPages)
        // grow で先に数えた端の区画（増やした所の最初と最後）も、数えた区画として書く（戻した後の数えた量を合わせる）
        assertArrayEquals(intArrayOf(3, WasmInstanceState.CHUNKS_PER_PAGE, 2 * WasmInstanceState.CHUNKS_PER_PAGE - 1), state.writtenChunkIndices)
        assertEquals(listOf(WasmInstanceState.GlobalValue(0, WasmValueType.I64, 3)), state.globals)
        assertEquals(1, state.tables.size)
        assertEquals(2, state.tables[0].size)
        assertTrue(1 in state.droppedDataSegments, "パッシブなセグメントは drop 済み")

        // 形の往復
        val out = SnapshotWriter(1024)
        InstanceStateCodec.write(out, state)
        val bytes = out.toByteArray()
        val input = SnapshotReader(bytes)
        val decoded = InstanceStateCodec.read(input)
        input.expectEnd()
        assertEquals(state.memoryPages, decoded.memoryPages)
        assertArrayEquals(state.writtenChunkIndices, decoded.writtenChunkIndices)
        assertEquals(state.globals, decoded.globals)
        assertEquals(state.tables, decoded.tables)
        assertArrayEquals(state.droppedDataSegments, decoded.droppedDataSegments)
        assertArrayEquals(state.droppedElementSegments, decoded.droppedElementSegments)

        val budget2 = Budget()
        module.instantiateForRestore(config(budget2), decoded).use { inst ->
            assertEquals(3, inst.i64("get"))
            assertEquals(77, inst.i64("load", (WasmMemory.PAGE_SIZE + 8).toLong()))
            assertEquals(5, inst.i64("load", 4096L * 3 + 16))
            assertEquals(2, inst.memory!!.pages)
            // ROM はそのまま
            assertArrayEquals("ROMDATA!".toByteArray(), inst.memory!!.read(0, 8))
            assertEquals(10, inst.i64("callAt", 0))
            assertEquals(20, inst.i64("callAt", 1))
            // drop したパッシブなセグメントは使えない（長さ 0 なので範囲外のトラップ）
            assertThrows<WasmTrapException> { inst.call("initP") }
            // 数えた区画の数は、書いた区画の数と同じ
            assertEquals(state.writtenChunkCount.toLong() * WasmInstanceState.CHUNK_BYTES, budget2.used)
        }
    }

    @Test
    fun `dropしていなければ、作り直したインスタンスでもパッシブなセグメントを使える`() {
        val module = EndiveRuntime().load(module())
        val state = module.instantiate(config(Budget())).use { it.captureState() }
        assertTrue(1 !in state.droppedDataSegments)
        module.instantiateForRestore(config(Budget()), state).use { inst ->
            inst.call("initP")
            assertArrayEquals(passiveBytes, inst.memory!!.read(100, passiveBytes.size))
        }
    }

    @Test
    fun `RAMの上限が足りなければWasmLinkException`() {
        val module = EndiveRuntime().load(module())
        val state = module.instantiate(config(Budget())).use { inst ->
            inst.call("store", 0, 1)
            inst.call("store", 8192, 1)
            inst.captureState()
        }
        assertEquals(2, state.writtenChunkCount)
        assertThrows<WasmLinkException> { module.instantiateForRestore(config(Budget(WasmInstanceState.CHUNK_BYTES.toLong())), state) }
    }

    @Test
    fun `start関数を持つモジュールは取り出せず、作り直せない`() {
        val b = WasmBinary()
        b.memory(1)
        val g = b.mutableI64Global(0)
        val start = b.func(emptyList(), emptyList(), code(0x23, g, 0x42, 1, 0x7c, 0x24, g))
        b.start(start)
        b.exportFunc("get", b.func(emptyList(), listOf(I64), code(0x23, g)))
        val module = EndiveRuntime().load(b.build())
        val state = module.instantiate(config(Budget())).use { inst ->
            assertEquals(1, inst.i64("get"))
            assertThrows<WasmStateException> { inst.captureState() }
            WasmInstanceState(1, IntArray(0), emptyList(), listOf(WasmInstanceState.GlobalValue(0, WasmValueType.I64, 1)), emptyList(), IntArray(0), IntArray(0))
        }
        assertThrows<WasmLinkException> { module.instantiateForRestore(config(Budget()), state) }
    }

    @Test
    fun `形が壊れていればSnapshotFormatException`() {
        val module = EndiveRuntime().load(module())
        val state = module.instantiate(config(Budget())).use { inst ->
            inst.call("store", 0, 1)
            inst.captureState()
        }
        val out = SnapshotWriter(1024)
        InstanceStateCodec.write(out, state)
        val bytes = out.toByteArray()
        // 途中で切れている
        assertThrows<pro.ikili.kibicomputers.core.persist.SnapshotFormatException> {
            InstanceStateCodec.read(SnapshotReader(bytes.copyOf(bytes.size - 3)))
        }
        // ページ数を超える区画の番号
        val bad = bytes.copyOf()
        // [bool][pages i32][count u32][index i32] → index を 1000 にする
        bad[9] = 0
        bad[10] = 0
        bad[11] = 0x03
        bad[12] = 0xE8.toByte()
        assertThrows<pro.ikili.kibicomputers.core.persist.SnapshotFormatException> { InstanceStateCodec.read(SnapshotReader(bad)) }
    }
}
