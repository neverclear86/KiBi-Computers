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
 * 書くときは一時ファイル → fsync → `ATOMIC_MOVE`。同じハッシュはメモリの集合で1回だけ書く。
 *
 * 掃除（#564、ADR 0564）: 消してよいのは、作ったときに置き場にあったファイル（[sweepCandidates]）のうち、このセッションで [put]・[get] に
 * 来ておらず（[touched]）、どの世代のファイルからも参照されていないものだけ（[deleteIfUnused]）。動いていて、まだ保存していない
 * プロセスのモジュールはファイルに載っていないが、必ず [put]（起動・exec）か [get]（復元）を通るので [touched] で守る。
 * 置き場はサーバーの起動ごとに作るので、[touched] はそのサーバーのすべての使用を覆う。
 */
@ApiStatus.Internal
public class FileModuleStore(private val root: Path) : ModuleStore {
    /** 「ある」とするハッシュ（16進。書けた・確かめたものと、作るときの種） */
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 中身を確かめたハッシュ（16進。[put] はこれにあるときだけ書かずに戻る。種は入らない） */
    private val verified: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** このセッションで [put]・[get] に来たハッシュ（16進。掃除で消さない。#564） */
    private val touched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 掃除が消そうとしているハッシュ（16進。[put] は、これにあるとき速い道を使わない。#564） */
    private val deleting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 作ったときに置き場にあったファイルのハッシュ（16進。掃除の候補。#564。作った後は変えない） */
    private val seeds = ArrayList<String>()

    /** 作った時刻（ミリ秒。これより後に更新されたファイルは掃除しない。#564） */
    private val createdMillis = System.currentTimeMillis()

    init {
        // 置き場にあるファイルの名前を集合の種にする（中身は get で確かめる）
        try {
            if (Files.isDirectory(root)) {
                Files.newDirectoryStream(root).use { dir ->
                    for (p in dir) {
                        val name = p.fileName.toString()
                        if (name.endsWith(SUFFIX)) {
                            val hex = name.removeSuffix(SUFFIX)
                            if (isHexName(hex) && Files.isRegularFile(p)) {
                                known += hex
                                seeds += hex
                            }
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
        // 使ったことを先に記す（掃除は「消す印 → touched を見る」の順。こちらは「touched → 消す印 → verified」の順に見る。#564）
        touched += hex
        if (hex !in deleting && hex in verified) return true
        require(ModuleStore.sha256(bytes).contentEquals(sha256)) { "バイト列のハッシュが $hex でない" }
        return try {
            synchronized(lockFor(hex)) {
                // 掃除はこの錠の中でファイルを消すので、ここでは消えた後の状態を見る
                if (hex in verified && Files.isRegularFile(root.resolve(hex + SUFFIX))) return true
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
        touched += hex
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

    /** 掃除の候補（作ったときに置き場にあったファイルのハッシュ。16進。#564） */
    public fun sweepCandidates(): List<String> = seeds.toList()

    /** このセッションで [put]・[get] に来たか（#564。テスト・掃除の数え口） */
    public fun isTouched(hex: String): Boolean = hex in touched

    /**
     * どの世代のファイルからも参照されていないと分かったハッシュ [hex] のファイルを消す（#564。書き込みのスレッドから呼ぶ）。
     * このセッションで使った（[touched]）・作った後に更新された・ないなら消さない。消したらそのバイト数、消さなければ -1。
     *
     * 順: 消す印を立てる → [touched] を見る → ハッシュの錠の中で [touched] を見直し、集合から外してファイルを消す → 印を外す。
     * [put] は「[touched] に入れる → 印を見る → [verified] を見る」の順なので、どちらかが必ず相手を見る（[put] は錠の中の遅い道で書き直す）。
     * 集合（[known]）から外すのは [touched] を見た後なので、使っているプロセスの [contains] は偽にならない
     */
    public fun deleteIfUnused(hex: String): Long {
        if (!isHexName(hex)) return -1
        deleting += hex
        try {
            if (hex in touched) return -1
            synchronized(lockFor(hex)) {
                if (hex in touched) return -1
                val file = root.resolve(hex + SUFFIX)
                return try {
                    if (!Files.isRegularFile(file)) return -1
                    if (Files.getLastModifiedTime(file).toMillis() > createdMillis) return -1
                    val size = Files.size(file)
                    known -= hex
                    verified -= hex
                    Files.delete(file)
                    size
                } catch (e: IOException) {
                    LOG.log(System.Logger.Level.WARNING, "モジュール $hex を置き場 $root から消せない: $e")
                    -1
                }
            }
        } finally {
            deleting -= hex
        }
    }

    /**
     * 置き場に残った一時ファイル（`*.tmp`。[put] の途中で落ちたもの）のうち、作ったときより前のものを消す（#564。書き込みのスレッドから呼ぶ）。
     * 消した数
     */
    public fun deleteStaleTemps(): Int {
        var count = 0
        try {
            if (!Files.isDirectory(root)) return 0
            Files.newDirectoryStream(root, "*.tmp").use { stream ->
                for (path in stream) {
                    try {
                        if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() < createdMillis && Files.deleteIfExists(path)) count++
                    } catch (e: IOException) {
                        LOG.log(System.Logger.Level.WARNING, "置き場の一時ファイル $path を消せない: $e")
                    }
                }
            }
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "置き場 $root の一時ファイルを読めない: $e")
        } catch (e: RuntimeException) {
            // DirectoryIteratorException（反復中の IOException）・SecurityException など。結果が返らないと掃除が終わらない
            LOG.log(System.Logger.Level.WARNING, "置き場 $root の一時ファイルを読めない: $e")
        }
        return count
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
