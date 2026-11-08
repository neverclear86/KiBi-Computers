package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 持ち主の規則（`MachineSaveStore.claim`。回答 A の epoch 方式）のテスト（#81、ADR 0081） */
class MachineClaimTest {
    @TempDir
    lateinit var dir: Path

    private val index = MachineIdIndex()
    private val store by lazy { MachineSaveStore(dir, index) }

    private fun at(x: Int) = MachineOwner("minecraft:overworld", x, 64, 0)

    /** ブロックエンティティ1つ（NBT の値と位置）。[load] で claim し、[unload] で索引から外す */
    private inner class Block(var id: String, var generation: Long, var epoch: Long, val pos: MachineOwner) {
        fun load(): ClaimResult {
            val claim = store.claim(id, generation, epoch, pos)
            claim.identity?.let {
                id = it.id.toString()
                generation = it.generation
                epoch = it.epoch
            }
            return claim.result
        }

        fun unload() = index.removeIfOwner(UUID.fromString(id), pos)

        /** このブロックの NBT を写した、別の位置のブロック */
        fun copyTo(x: Int) = Block(id, generation, epoch, at(x))
    }

    private fun original(): Block {
        val identity = store.assignNewId(at(0))!!
        index.removeIfOwner(identity.id, at(0))
        return Block(identity.id.toString(), 0, 0, at(0))
    }

    @Test
    fun `同時に読み込まれた重複は、後から来た方が新しいUUID`() {
        val a = original()
        assertEquals(ClaimResult.KEPT, a.load())
        val b = a.copyTo(5)
        assertEquals(ClaimResult.NEW_ID_DUPLICATE_LOADED, b.load())
        assertNotEquals(a.id, b.id)
        assertEquals(at(5), index.ownerOf(UUID.fromString(b.id)))
    }

    @Test
    fun `アンロードした後に同じUUIDを別の位置で読み込むと引き取る`() {
        val a = original()
        assertEquals(ClaimResult.KEPT, a.load())
        a.unload()
        val moved = a.copyTo(9)
        assertEquals(ClaimResult.TAKEN_OVER, moved.load())
        assertEquals(a.id, moved.id)
        assertEquals(1, moved.epoch)
        assertEquals(OwnerRecord(1, at(9)), OwnerRecord.read(store.directoryOf(UUID.fromString(a.id)).resolve(OwnerRecord.FILE_NAME)))
    }

    @Test
    fun `原本とコピーを交互に読み込むと、引き取った後は原本が冷えた起動になり、以後は安定する`() {
        val a = original()
        val copy = a.copyTo(3)
        assertEquals(ClaimResult.TAKEN_OVER, copy.load())
        copy.unload()
        assertEquals(ClaimResult.NEW_ID_STALE, a.load())
        a.unload()
        assertEquals(ClaimResult.KEPT, copy.load())
        copy.unload()
        assertEquals(ClaimResult.KEPT, a.load(), "新しい UUID の原本は自分が持ち主")
        assertNotEquals(a.id, copy.id)
    }

    @Test
    fun `コピー10個を順に読み込むと、最初の1つだけ引き取り、残りは冷えた起動`() {
        val a = original()
        val copies = (1..10).map { a.copyTo(it) }
        val results = copies.map { c -> c.load().also { c.unload() } }
        assertEquals(listOf(ClaimResult.TAKEN_OVER) + List(9) { ClaimResult.NEW_ID_STALE }, results)
        assertEquals(10, copies.map { it.id }.toSet().size)
    }

    @Test
    fun `引き取ったブロックをさらにコピーすると、最後に読み込んだ側が勝つ`() {
        val a = original()
        val b = a.copyTo(1)
        assertEquals(ClaimResult.TAKEN_OVER, b.load())
        b.unload()
        val c = b.copyTo(2)
        assertEquals(ClaimResult.TAKEN_OVER, c.load())
        assertEquals(2, c.epoch)
        c.unload()
        assertEquals(ClaimResult.NEW_ID_STALE, b.load())
    }

    @Test
    fun `AからBへ運び、Aへ運び戻しても状態が残る`() {
        val a = original()
        assertEquals(ClaimResult.KEPT, a.load())
        a.unload()
        val atB = a.copyTo(7)
        assertEquals(ClaimResult.TAKEN_OVER, atB.load())
        atB.unload()
        val back = atB.copyTo(0)
        assertEquals(ClaimResult.TAKEN_OVER, back.load())
        assertEquals(a.id, back.id)
        assertEquals(2, back.epoch)
    }

    @Test
    fun `引き取った後、チャンクの保存の前に落ちたとき（NBTのepochが古く持ち主が自分）はKEPTで直す`() {
        val a = original()
        val b = a.copyTo(4)
        assertEquals(ClaimResult.TAKEN_OVER, b.load())
        b.unload()
        // チャンクの NBT には引き取る前の epoch 0 が残っている
        val crashed = Block(b.id, 0, 0, at(4))
        assertEquals(ClaimResult.KEPT, crashed.load())
        assertEquals(1, crashed.epoch)
    }

    private fun corrupt(bytes: ByteArray): ClaimResult {
        val id = UUID.randomUUID()
        Files.createDirectories(dir.resolve(id.toString()))
        Files.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), bytes)
        val fresh = MachineSaveStore(dir, MachineIdIndex())
        val claim = fresh.claim(id.toString(), 0, 0, at(1))
        assertNotEquals(id, claim.identity!!.id)
        return claim.result
    }

    private fun ownerBytes(magic: String = "KIBO", version: Int = 1, epoch: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(magic.toByteArray())
            d.writeShort(version)
            d.writeLong(epoch)
            d.writeUTF("minecraft:overworld")
            d.writeInt(0)
            d.writeInt(64)
            d.writeInt(0)
        }
        return out.toByteArray()
    }

    @Test
    fun `owner_binが壊れている・magic違い・版違い・epochが範囲外なら例外を出さずNEW_ID_UNREADABLE`() {
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(byteArrayOf(1, 2, 3)))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(magic = "XXXX")))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(version = 2)))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(epoch = -1)))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(epoch = MachineIdentityLimits.MAX_COUNTER + 1)))
    }

    @Test
    fun `owner_binのepochが上限のとき引き取ると、負にならずNEW_ID_INVALID`() {
        val id = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(MachineIdentityLimits.MAX_COUNTER, at(0)))
        val claim = store.claim(id.toString(), 0, MachineIdentityLimits.MAX_COUNTER, at(1))
        assertEquals(ClaimResult.NEW_ID_INVALID, claim.result)
        assertEquals(0, claim.identity!!.epoch)
    }

    @Test
    fun `NBTのOwnerEpochがLong_MAX_VALUEならNEW_ID_INVALID`() {
        val a = original()
        a.epoch = Long.MAX_VALUE
        assertEquals(ClaimResult.NEW_ID_INVALID, a.load())
    }
}
