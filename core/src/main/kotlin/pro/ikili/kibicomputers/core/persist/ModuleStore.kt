package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 待機点保存が指すモジュールの、元のバイト列の置き場（#82、ADR 0082）。中身のハッシュ（SHA-256）で引く（content-addressed）。
 *
 * 保存データはプロセスのモジュールを元のハッシュで指し、戻すとき（#85）にここから元のバイト列を取り出して、同じ読み込み方でやり直す。
 * ストアにないモジュールのプロセスは保存できない（写しの中で殺す）。
 *
 * 「あるか」（[contains]）はメモリの集合だけを引く。集合に入るのは (a) [put] で書けたもの、(b) [put] で既にあるファイルのハッシュを
 * 確かめられたもの、(c) [get] でハッシュを確かめて返せたもの。[FileModuleStore] はさらに、作るときに置き場の
 * 16進 64 文字のファイル名を集合の種にする（中身は [put] か [get] のときに確かめる。[put] は種だけのハッシュなら中身を確かめ、
 * 合わなければ書き直す。[get] は壊れていれば `null` を返し、戻す側は冷えた起動にする）。
 * ストアを通らずにモジュールを作る経路を足すときは [put] を呼ぶこと。
 *
 * スレッド: どのスレッドから呼んでもよい。[put]・[get] はファイルの I/O をするので、メインスレッドとカーネルロックの中では呼ばない
 * （読み込みのスレッドから呼ぶ）。[contains] はカーネルロックの中から呼んでよい（ファイルを触らない）。
 */
@ApiStatus.Internal
public interface ModuleStore {
    /**
     * 元のバイト列 [bytes]（SHA-256 が [sha256]）を置く。書けた、または既にあって中身を確かめられたら `true`（集合に入る）。
     * 書けなければ `false`（ログに出す）。[bytes] のハッシュが [sha256] と違えば [IllegalArgumentException]
     */
    public fun put(sha256: ByteArray, bytes: ByteArray): Boolean

    /** [sha256] のモジュールがあるか（メモリの集合だけを引く。ファイルを触らない） */
    public fun contains(sha256: ByteArray): Boolean

    /** [sha256] のモジュールの元のバイト列。ハッシュを確かめて返す（確かめられたら集合に入る）。ない・合わなければ `null` */
    @Throws(IOException::class)
    public fun get(sha256: ByteArray): ByteArray?

    public companion object {
        /** 置き場がない（何も書かず、すべて「ない」。プロセスは保存できない） */
        @JvmField
        public val NONE: ModuleStore = object : ModuleStore {
            override fun put(sha256: ByteArray, bytes: ByteArray): Boolean = false

            override fun contains(sha256: ByteArray): Boolean = false

            override fun get(sha256: ByteArray): ByteArray? = null

            override fun toString(): String = "ModuleStore.NONE"
        }

        /** [sha256] の16進小文字（ファイル名）。32 バイトでなければ [IllegalArgumentException] */
        @JvmStatic
        public fun hex(sha256: ByteArray): String {
            require(sha256.size == 32) { "SHA-256 は 32 バイト" }
            return sha256.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /** [bytes] の SHA-256 */
        @JvmStatic
        public fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/**
 * ワールドごとのモジュールの置き場（#82）。`<root>/<SHA-256 の16進小文字>.wasm` に元のバイト列をそのまま置く。
 * 書くときは一時ファイル → fsync → `ATOMIC_MOVE`。同じハッシュはメモリの集合で1回だけ書く。消すのは #84（世代の掃除と一緒に）。
 */
@ApiStatus.Internal
public class FileModuleStore(private val root: Path) : ModuleStore {
    /** 「ある」とするハッシュ（16進。書けた・確かめたものと、作るときの種） */
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 中身を確かめたハッシュ（16進。[put] はこれにあるときだけ書かずに戻る。種は入らない） */
    private val verified: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // 置き場にあるファイルの名前を集合の種にする（中身は get で確かめる）
        try {
            if (Files.isDirectory(root)) {
                Files.newDirectoryStream(root).use { dir ->
                    for (p in dir) {
                        val name = p.fileName.toString()
                        if (name.endsWith(SUFFIX)) {
                            val hex = name.removeSuffix(SUFFIX)
                            if (isHexName(hex) && Files.isRegularFile(p)) known += hex
                        }
                    }
                }
            }
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "モジュールの置き場 $root を読めない: $e")
        }
    }

    override fun put(sha256: ByteArray, bytes: ByteArray): Boolean {
        val hex = ModuleStore.hex(sha256)
        if (hex in verified) return true
        require(ModuleStore.sha256(bytes).contentEquals(sha256)) { "バイト列のハッシュが $hex でない" }
        return try {
            synchronized(lockFor(hex)) {
                if (hex in verified) return true
                val file = root.resolve(hex + SUFFIX)
                // 既にあって中身が合えば書かない（合わなければ書き直す）
                if (Files.isRegularFile(file) && ModuleStore.sha256(Files.readAllBytes(file)).contentEquals(sha256)) {
                    verified += hex
                    known += hex
                    return true
                }
                Files.createDirectories(root)
                val tmp = Files.createTempFile(root, "$hex.", ".tmp")
                try {
                    FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                        val buf = ByteBuffer.wrap(bytes)
                        while (buf.hasRemaining()) ch.write(buf)
                        ch.force(true)
                    }
                    try {
                        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(tmp)
                }
                verified += hex
                known += hex
                true
            }
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "モジュール $hex を置き場 $root に書けない（このモジュールのプロセスは保存できない）: $e")
            false
        }
    }

    override fun contains(sha256: ByteArray): Boolean = sha256.size == 32 && ModuleStore.hex(sha256) in known

    @Throws(IOException::class)
    override fun get(sha256: ByteArray): ByteArray? {
        val hex = ModuleStore.hex(sha256)
        val file = root.resolve(hex + SUFFIX)
        if (!Files.isRegularFile(file)) {
            known -= hex
            verified -= hex
            return null
        }
        val bytes = Files.readAllBytes(file)
        if (!ModuleStore.sha256(bytes).contentEquals(sha256)) {
            known -= hex
            verified -= hex
            return null
        }
        verified += hex
        known += hex
        return bytes
    }

    override fun toString(): String = "FileModuleStore($root)"

    private fun lockFor(hex: String): Any = locks.computeIfAbsent(hex) { Any() }

    private val locks = ConcurrentHashMap<String, Any>()

    private companion object {
        const val SUFFIX = ".wasm"
        val LOG: System.Logger = System.getLogger("kibicomputers.persist")

        fun isHexName(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
