package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * `owner.bin` の中身（#81、ADR 0081。回答 A の epoch 方式）: 持ち主の位置と epoch。
 *
 * 形: magic `KIBO`、版（u16 = [VERSION]）、epoch（i64、0 以上 [MachineIdentityLimits.MAX_COUNTER] 以下）、
 * 持ち主のディメンション（`writeUTF`）と座標（3つの i32）。
 */
@ApiStatus.Internal
public data class OwnerRecord(
    public val epoch: Long,
    public val owner: MachineOwner,
) {
    init {
        require(MachineIdentityLimits.isValidCounter(epoch)) { "epoch $epoch は範囲外" }
    }

    /** バイト列にする */
    public fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream(32 + owner.dimension.length)
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeShort(VERSION)
            out.writeLong(epoch)
            out.writeUTF(owner.dimension)
            out.writeInt(owner.x)
            out.writeInt(owner.y)
            out.writeInt(owner.z)
        }
        return bytes.toByteArray()
    }

    public companion object {
        /** 形の版 */
        public const val VERSION: Int = 1

        /** ファイルの名前 */
        public const val FILE_NAME: String = "owner.bin"

        /** 読むファイルの大きさの上限（壊れたファイルを読み込みすぎない） */
        private const val MAX_BYTES = 64 * 1024

        private val MAGIC = byteArrayOf('K'.code.toByte(), 'I'.code.toByte(), 'B'.code.toByte(), 'O'.code.toByte())

        /** [bytes] から読む。magic・版が違う・epoch が範囲外・途中で切れている・余りがあるなら [IOException] */
        @JvmStatic
        @Throws(IOException::class)
        public fun fromBytes(bytes: ByteArray): OwnerRecord {
            val input = DataInputStream(bytes.inputStream())
            val magic = ByteArray(4)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("owner.bin の magic が違う")
            val version = input.readUnsignedShort()
            if (version != VERSION) throw IOException("owner.bin の版 $version は読めない")
            val epoch = input.readLong()
            if (!MachineIdentityLimits.isValidCounter(epoch)) throw IOException("owner.bin の epoch $epoch は範囲外")
            val dimension = input.readUTF()
            val owner = MachineOwner(dimension, input.readInt(), input.readInt(), input.readInt())
            if (input.available() > 0) throw IOException("owner.bin の後ろに余りがある")
            return OwnerRecord(epoch, owner)
        }

        /** [file] を読む。なければ `null`。壊れていれば [IOException] */
        @JvmStatic
        @Throws(IOException::class)
        public fun read(file: Path): OwnerRecord? {
            if (!Files.exists(file)) return null
            val size = Files.size(file)
            if (size > MAX_BYTES) throw IOException("owner.bin が大きすぎる（$size バイト）")
            return fromBytes(Files.readAllBytes(file))
        }

        /** [record] を [file] に書く（tmp に書いて fsync し、rename で置き換える） */
        @JvmStatic
        @Throws(IOException::class)
        public fun write(file: Path, record: OwnerRecord) {
            writeAtomically(file, record.toBytes())
        }
    }
}

/**
 * [bytes] を `<file>.tmp` に書いて fsync し、`ATOMIC_MOVE` で [file] に置き換える（#81）。
 * 親のディレクトリがなければ作る。失敗したら tmp を消して [IOException]
 */
@Throws(IOException::class)
internal fun writeAtomically(file: Path, bytes: ByteArray) {
    val dir = file.parent
    if (dir != null) Files.createDirectories(dir)
    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
    try {
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("rename を不可分にできない: $file", e)
        }
    } catch (e: IOException) {
        try {
            Files.deleteIfExists(tmp)
        } catch (_: IOException) {
        }
        throw e
    }
}
