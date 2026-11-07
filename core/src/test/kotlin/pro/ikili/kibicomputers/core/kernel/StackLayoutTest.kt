package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import run.endive.wasm.Parser
import run.endive.wasm.types.ActiveDataSegment
import run.endive.wasm.types.MutabilityType
import run.endive.wasm.types.OpCode

/**
 * 同梱のプログラムが、スタックを線形メモリの頭に置いているか（#514）。
 *
 * スタックが静的データより下（アドレス 0 から）にあれば、あふれたスタックは 0 より下を指して範囲外の読み書きで止まり、
 * 黙って静的データを壊さない。`os/lib/kibi/stack.zig`（`kibi.stack.remaining`）もこの配置を前提にしている。
 * リンカーの既定が変わったら、ここで気づく
 */
class StackLayoutTest {
    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private fun imageFile(path: String): ByteArray {
        val stat = image.stat(VfsPath.parse(path))
        val bytes = ByteArray(stat.size.toInt())
        image.read(stat.node, 0, bytes, 0, bytes.size)
        return bytes
    }

    /** スタックの初めの位置（`__stack_pointer`。初めの可変な i32 の大域変数）が、どの静的データよりも下にあるか */
    private fun assertStackFirst(name: String, wasm: ByteArray) {
        val module = Parser.parse(wasm)
        val gs = module.globalSection()
        val sp = (0 until gs.globalCount()).map { gs.getGlobal(it) }
            .first { it.mutabilityType() == MutabilityType.Var && it.initInstructions().firstOrNull()?.opcode() == OpCode.I32_CONST }
            .initInstructions().first().operand(0)
        val offsets = module.dataSection().dataSegments().filterIsInstance<ActiveDataSegment>().map { s ->
            val ins = s.offsetInstructions().first()
            assertTrue(ins.opcode() == OpCode.I32_CONST, "$name: データの位置が定数でない")
            ins.operand(0)
        }
        assertTrue(sp > 0, "$name: スタックの初めの位置 $sp")
        for (o in offsets) assertTrue(o >= sp, "$name: 静的データ（$o）がスタックの上端（$sp）より下にある")
    }

    @Test
    fun `標準OSのプログラムはスタックを静的データの下に置く`() {
        var count = 0
        for (dir in listOf("/bin", "/sbin")) {
            val node = image.stat(VfsPath.parse(dir)).node
            for (e in image.readDirectory(node)) {
                if (e.type != FileType.REGULAR_FILE) continue
                assertStackFirst("$dir/${e.name}", imageFile("$dir/${e.name}"))
                count++
            }
        }
        assertTrue(count > 10, "プログラムが $count 個しかない")
    }

    @Test
    fun `zig ccでビルドしたluaもスタックを静的データの下に置く`() {
        val lua = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がリソースにない" }.use { it.readBytes() }
        assertStackFirst("lua.wasm", lua)
    }
}
