package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.util.CanonicalUuid
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalLong
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32C
import kotlin.concurrent.withLock

/** 根の名前の列挙を開く口（テストが、列挙の失敗を差し込むために差し替える。#585） */
internal var openRootEntries: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) }

/**
 * マシンの保存データの置き場（#81、ADR 0081）。根は `<world>/kibicomputers/machines`。
 *
 * - `<root>/<uuid>/<世代>.bin`（世代は10進）が保存データ（[MachineSnapshot]）、`<root>/<uuid>/owner.bin` が持ち主（[OwnerRecord]）。
 *   どちらも tmp に書いて fsync し、`ATOMIC_MOVE` で置き換える
 * - 持ち主の規則（回答 A。epoch 方式）は [claim]。書く予約の時点（[submit]）にも、覚えている `owner.bin` と照らす
 * - NBT が指す世代（チャンクの印）は、チャンクの保存（`ChunkDataEvent.Save`）から [markChunkGeneration] で受け取る。
 *   残すのは「今の印」「前の印（2つ）」「覚えている最新の世代」と、印より大きい世代、それらがディスクにないときの直下の世代、
 *   書き込み待ちの世代とその基準。それ以外は印が進んだときに消す
 *
 * `owner.bin` の中身・印・書いた最新の世代はメモリに覚え（このサーバーが書いた・読んだもの）、毎回ディスクは読まない。
 * 新しい ID の重なりは、開いたときに根の名前を 1 回読んだ集合で確かめ、振るときにディスクは見ない（#579）。
 * 印はメモリの上だけに持ち、保存しない（再起動の後は [claim] で読んだ世代から始まる）。
 *
 * 差分の世代（#83、ADR 0083）: 全体の世代を書けたら（完了を受け取ったら）、それを今の基準として覚える（メモリだけ。再起動の後の最初の保存は全体）。
 * 差分の世代は今の基準を指す（[deltaBaseFor] → 取り出し → [submit]。まとめたものが [captureAndSubmit]）。
 *
 * 書き込みのスレッド（#84、ADR 0084）: 世代の符号化（圧縮を含む）と書き込み、`owner.bin`、古い世代の掃除、
 * ディレクトリの削除は、`executor` の列に入れて順に走らせる。メインスレッドは予約（[submit]。世代を決め、持ち主を確かめる）と、
 * 完了の受け取り（[pollCompleted]）だけをする。[claim] もメインスレッドでは書かない（#585。ADR 0084 の規則 7a）: 引き取りの `owner.bin` は
 * その場で列に入れ、`owner.bin` がなかった ID と新しく振った ID の `owner.bin` は、最初の世代の書き込みの前に書く（[submit]）。
 * claim がディスクを読むのは、覚えておらず、先読み（`prefetchOwners`）でも分からない ID だけ。サーバーのメインスレッドは
 * [claimOrDefer] を使い、その読みもしない（#869）: 読みを先読みのスレッドか書き込みのスレッドに回して claim を待ちにし、
 * 読み終わった後の tick に [pollDeferredClaims] で確定する。
 *
 * スレッド: 列の仕事のほかは、メインスレッドだけで使う。[hasCapacity]・[pendingBytes]・[pendingCount] はどのスレッドから読んでもよい。
 *
 * @property root 置き場の根
 * @property index サーバーの ID の索引
 * @property modVersion 保存データに書く MOD のバージョン（戻すときは保存データのものと比べる。#88）
 * @param executor 保存の仕事を走らせる口（既定は呼んだスレッドでその場で走らせる [SaveExecutor.DIRECT]）
 * @param maxPendingBytes 書き込み待ちの写しの見積もりの合計の上限（[hasCapacity]）
 * @param claimWaitMillis [claim] がその UUID の仕事を待つ上限（`owner.bin` を読む前だけ。過ぎたら新しい UUID にする）
 * @param prefetchOwners 開いたときに、根にあった ID の `owner.bin` を別のスレッドで先読みするか（#585。サーバーの置き場は真。
 *   既定は偽で、テストは [startOwnerPrefetch] で走らせ方を決める）
 */
@ApiStatus.Internal
public class MachineSaveStore @JvmOverloads constructor(
    public val root: Path,
    public val index: MachineIdIndex,
    public val modVersion: String = "unknown",
    private val executor: SaveExecutor = SaveExecutor.DIRECT,
    maxPendingBytes: Long = Long.MAX_VALUE,
    claimWaitMillis: Long = MachineSaveConfig.DEFAULT_WRITER_FLUSH_MILLIS,
    prefetchOwners: Boolean = false,
) {
    /** 書き込み待ちの写しの見積もりの合計の上限（バイト。GameTest が差し替える） */
    @Volatile
    public var maxPendingBytes: Long = maxPendingBytes

    /** [claim] が `owner.bin` を読む前に、その UUID の仕事を待つ上限（ミリ秒） */
    public var claimWaitMillis: Long = claimWaitMillis

    // ---- メインスレッドだけが触る ----

    /** 覚えている `owner.bin`（このサーバーが書いた・読んだもの。書く予定で、まだ書いていないもの（[unwrittenOwners]）も含む） */
    private val owners = HashMap<UUID, OwnerRecord>()

    /**
     * [owners] のうち、まだディスクに書いていない `owner.bin`（#585）。claim で `owner.bin` がなかった ID（KEPT）と、新しく振った ID。
     * 次の [submit] がその世代の前に書く（同じ仕事の中で、`owner.bin` → 世代の順）。引き取り・`delete`・ID を外したときに外す
     */
    private val unwrittenOwners = HashMap<UUID, OwnerRecord>()

    /** このサーバーで `delete` した ID（`owner.bin` は消える。claim はディスクを読まずに「ない」とする。#585）。claim し直したら外す */
    private val deletedIds = HashSet<UUID>()

    /** このサーバーで claim・`delete`・ID を振った ID（先読みの値を使わない。先読みは開いたときのディスクの写しのため。#585） */
    private val touched = HashSet<UUID>()

    /** [root] の名前を全部読めたか（偽なら [knownDirectories] が欠けているかもしれないので、claim は読む側に倒す。#585） */
    internal var scanComplete: Boolean = false

    /** チャンクの印（今の印と、前の印2つ） */
    private val marks = HashMap<UUID, Marks>()

    /** その UUID で書けた（完了を受け取った）最新の世代 */
    private val latest = HashMap<UUID, Long>()

    /** その UUID で予約した最大の世代（失敗しても戻さない。#84） */
    private val reserved = HashMap<UUID, Long>()

    /** その UUID の、予約して完了を受け取っていない世代 → 差分ならその基準の世代（全体なら `null`。#84） */
    private val inFlight = HashMap<UUID, HashMap<Long, Long?>>()

    /** その UUID の今の基準（次の差分が指す全体の世代。#83）。全体の世代の完了を受け取ったときに進む */
    private val bases = HashMap<UUID, DeltaBase>()

    /** その UUID の登録（claim・ID を振ったときに作り、`delete` で消す。古い登録の完了は表を進めない。#84） */
    private val registrations = HashMap<UUID, Registration>()

    /** 受け取ったが、まだ [pollCompleted] で返していない完了（[write] が自分の分だけ抜く） */
    private val ready = ArrayList<SaveCompletion>()

    /**
     * ディレクトリがあるかもしれない UUID（#579。新しい ID の重なりの確かめに使い、ディスクは見ない。ADR 0084 の規則 2）。
     *
     * 不変条件: 開いたときに [root] にあった名前（UUID として読めるもの。大文字も）∪ このストアがディレクトリを作りうる ID
     * （[newUuid] が返したもの・`owner.bin` を書こうとしたもの）を含む。サーバーが動いている間は外さない（`delete` や、
     * 新しく振った `owner.bin` が書けなかったときに消えても、多めに持つ側に倒す）。動いている間に外から置かれたディレクトリは見ない
     */
    private val knownDirectories = HashSet<UUID>()

    // ---- 書き込みのスレッドの持ち物 ----

    /**
     * UUID → 世代 → その世代が指す基準の世代（全体の世代なら `null`。#83）。ファイルの頭の写し（キャッシュ）で、
     * 忘れても次に要るときに [deltaBaseReader] で読み直すだけ。消せた世代・`delete` で忘れる
     */
    private val baseOf = ConcurrentHashMap<UUID, HashMap<Long, Long?>>()

    /** このサーバーで書いたディレクトリ（初めて書く前に、残った `*.tmp` を片付ける。#84 の規則 8） */
    private val initialized: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * 新しく振った `owner.bin` が書けなかった UUID（後続の仕事は書かずに失敗で返す。#84 の規則 7b）。外すのは、メインスレッドが
     * 「使えない」の失敗の完了を全部受け取り（[unreceivedUnusable] にない）、かつその UUID の仕事が残っていないとき
     * （#566。仕事が尽きた時点で外すと、受け取る前に入れた後続が書かれる）。`owner.bin` の書き込みが成功したときも外す
     */
    private val unusable: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * UUID → 積んだが、メインスレッドがまだ受け取っていない「使えない」の失敗の完了（[Done.unusable]）の数（#566・#585）。
     * 受け入れ直しで同じ ID の `owner.bin` の失敗が 2 回以上続いても、最後の失敗を受け取るまで [unusable] を外さないよう、集合でなく数で持つ。
     * [accept] は「数を減らす → 仕事が残っているか見る」、[finishTask] は「数を増やす → 完了を積む → 仕事の数を減らす → ここにあるか見る」
     * の順なので、どちらかが必ず最後の仕事・最後の受け取りの後に [unusable] から外す
     */
    private val unreceivedUnusable = ConcurrentHashMap<UUID, Int>()

    // ---- 両方が触る ----

    /** UUID → 列に入れて終わっていない仕事の数 */
    private val outstanding = ConcurrentHashMap<UUID, Int>()
    private val pendingBytesCounter = AtomicLong()
    private val pendingTasks = AtomicInteger()
    private val completed = ConcurrentLinkedQueue<Done>()
    private val waitLock = ReentrantLock()
    private val progress = waitLock.newCondition()

    /** 開いたときに [root] の名前を読んだ回数（数え口。テストが「振るときにディスクを見ない」を確かめる。#579） */
    internal var directoryScans: Int = 0
        private set

    /** 新しい ID の候補を作る口（テストが差し替える。既定は [UUID.randomUUID]。#579） */
    internal var uuidSource: () -> UUID = UUID::randomUUID

    // ---- owner.bin の先読み（#585） ----

    /** 先読みした `owner.bin`（[startOwnerPrefetch]） */
    private sealed class Prefetched {
        class Found(val record: OwnerRecord) : Prefetched()

        object Missing : Prefetched()

        class Unreadable(val reason: String) : Prefetched()
    }

    /**
     * 先読みの結果（#585・#869）。先読みのスレッドが 1 件読むたびに入れ（読み終えた ID は、その時点で claim が使える）、
     * メインスレッドが claim で使うときに抜く。ない ID は、まだ読んでいない（または先読みしない・読まない）
     */
    private val prefetched = ConcurrentHashMap<UUID, Prefetched>()

    /** 先読みのスレッドが終わった（[ownerPrefetchDone]） */
    @Volatile
    private var prefetchFinished: Boolean = false

    /** 先読みが読む ID（[startOwnerPrefetch] のときの [knownDirectories]。メインスレッドだけが触る。#869） */
    private var prefetchIds: Set<UUID> = emptySet()

    /** 先読みのスレッドに、順番より先に読んでほしい ID（待っている claim の ID。#869）。先読みのスレッドが毎回先に見る */
    private val prefetchUrgent = ConcurrentLinkedQueue<UUID>()

    /**
     * 先読みのスレッドが読まない ID（このサーバーで触った ID。[touched] の写しで、どのスレッドからも読める。#585）。
     * 触った ID は、書き込みのスレッドが `owner.bin` を置き換えうる。Windows では、開いているファイルへの置き換え（`ATOMIC_MOVE`）が
     * 失敗するので、触った ID は先読みで開かない（[awaitPrefetchRead]）
     */
    private val prefetchSkip: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 先読みのスレッドが今読んでいる ID（[prefetchReading]）と、[prefetchSkip] の確かめを守る錠（#585） */
    private val prefetchLock = ReentrantLock()
    private val prefetchIdle = prefetchLock.newCondition()

    /** 先読みのスレッドが今開いている `owner.bin` の ID（[prefetchLock] の中で読み書きする） */
    private var prefetchReading: UUID? = null

    /** 先読みの仕事を作ってから、その仕事が終わるまで真 */
    @Volatile
    private var prefetchRunning: Boolean = false

    /** 先読みの値で claim した回数（数え口。#585） */
    public var prefetchHits: Long = 0
        private set

    /** 先読みのスレッドが `owner.bin` を読む口（テストが、読みの途中で止めるために差し替える。#585） */
    internal var prefetchOwnerReader: (Path) -> OwnerRecord? = { OwnerRecord.read(it) }

    /** 先読みのスレッドが終わったか（数え口・テスト用） */
    public val ownerPrefetchDone: Boolean get() = prefetchFinished

    /**
     * 開いたときに [root] にあった ID（[knownDirectories] の今の中身）の `owner.bin` を読む仕事を作り、[runner] で走らせる（#585）。
     * 読むのは別のスレッドで、1 件読むたびに結果を [prefetched] に入れる（#869）。毎回、先に [prefetchUrgent]（待っている claim の ID）を見る。
     * このサーバーで触った ID（claim・`delete`）には使わず、読む前に触っていたら開かない（書き込みのスレッドの `owner.bin` の置き換えと
     * 重ねない。[awaitPrefetchRead]）
     */
    internal fun startOwnerPrefetch(runner: (Runnable) -> Unit) {
        val ids = knownDirectories.toList()
        prefetchIds = ids.toHashSet()
        prefetchRunning = true
        runner(
            Runnable {
                val start = System.nanoTime()
                val order = ids.iterator()
                val done = HashSet<UUID>(ids.size * 2)
                try {
                    while (true) {
                        val id = prefetchUrgent.poll() ?: (if (order.hasNext()) order.next() else break)
                        if (!done.add(id)) continue
                        val skip = prefetchLock.withLock {
                            (id in prefetchSkip).also { if (!it) prefetchReading = id }
                        }
                        if (skip) continue
                        try {
                            // 読みを止める（prefetchReading を外す）前に入れる。メインスレッドは、結果がなければ待つ側に回す
                            prefetched[id] = readOwnerOffMain(prefetchOwnerReader, id)
                        } finally {
                            prefetchLock.withLock {
                                prefetchReading = null
                                prefetchIdle.signalAll()
                            }
                        }
                    }
                } finally {
                    // 結果を全部入れてから下ろす（メインスレッドは、下りたのを見てから「先読みでは来ない」とする。[ensureOwnerRead]）
                    prefetchRunning = false
                    prefetchFinished = true
                }
                val millis = (System.nanoTime() - start) / 1_000_000.0
                LOG.log(System.Logger.Level.INFO, "Prefetched owner.bin of ${ids.size} machines in $root (${"%.1f".format(millis)} ms)")
            },
        )
    }

    /** メインスレッドの外で [id] の `owner.bin` を [reader] で読む（先読み・書き込みのスレッド。例外は [Prefetched.Unreadable] にする） */
    private fun readOwnerOffMain(reader: (Path) -> OwnerRecord?, id: UUID): Prefetched = try {
        reader(directoryOf(id).resolve(OwnerRecord.FILE_NAME))?.let { Prefetched.Found(it) } ?: Prefetched.Missing
    } catch (e: IOException) {
        Prefetched.Unreadable("${e.message}")
    } catch (e: RuntimeException) {
        Prefetched.Unreadable("$e")
    }

    /** [id] を触った（claim・`delete`・振った）。先読みの値を使わず、先読みのスレッドもその ID を開かない（#585） */
    private fun touch(id: UUID) {
        touched += id
        prefetchSkip += id
    }

    /**
     * 書き込みのスレッドで、[id] の `owner.bin` を置き換える・消す前に、先読みのスレッドがそのファイルを読み終えるのを待つ（#585）。
     * Windows では、開いているファイルへの `ATOMIC_MOVE`（`REPLACE_EXISTING`）が `AccessDeniedException` になるため。
     * メインスレッドは、その仕事を入れる前に [touch] で [prefetchSkip] に入れているので、ここを抜けた後に先読みが [id] を開くことはない。
     * 待つのは読みの 1 回分まで（1 件数 ms）で、先読みが終わった後は錠を取らない
     */
    private fun awaitPrefetchRead(id: UUID) {
        if (!prefetchRunning) return
        prefetchLock.withLock {
            while (prefetchReading == id) prefetchIdle.awaitUninterruptibly()
        }
    }

    /**
     * [root] の名前を一度だけ読み、[knownDirectories] に入れる（#579。サーバーの起動時）。名前だけを見て、エントリごとに stat しない
     * （ファイルでも、今までの `Files.exists` と同じく避ける）。`Files.isDirectory(root)` が偽のとき（根がない・根がファイル・
     * 親に入れないなどでディレクトリかどうか判定できない）は、warn を出さずに空の集合で始める（新しいワールドでは根がないのがふつう）。
     * warn が出るのは、列挙（`newDirectoryStream` 以降）に失敗したときだけで、読めた分だけで始める
     */
    private fun scanDirectories() {
        directoryScans++
        val start = System.nanoTime()
        try {
            if (!Files.isDirectory(root)) {
                // 根がないと確かに分かるときだけ、全部読めたとする（根がファイル・入れないなどは、claim が読む側に倒す）
                scanComplete = Files.notExists(root)
            } else {
                openRootEntries(root).use { entries ->
                    for (entry in entries) {
                        val name = entry.fileName?.toString() ?: continue
                        if (name.length != UUID_NAME_LENGTH) continue
                        try {
                            knownDirectories += UUID.fromString(name)
                        } catch (_: IllegalArgumentException) {
                            // UUID の名前でないものは読み飛ばす
                        }
                    }
                }
                scanComplete = true
            }
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "Failed to list machine directories in $root; continuing with ${knownDirectories.size} entries", e)
        } catch (e: RuntimeException) {
            // DirectoryIteratorException（反復中の IOException）・SecurityException など
            LOG.log(System.Logger.Level.WARNING, "Failed to list machine directories in $root; continuing with ${knownDirectories.size} entries", e)
        }
        val millis = (System.nanoTime() - start) / 1_000_000.0
        LOG.log(System.Logger.Level.INFO, "Found ${knownDirectories.size} machine directories in $root (${"%.1f".format(millis)} ms)")
    }

    /** 世代のファイルの頭から基準を読む（#83。テストが差し替える。既定は [MachineSnapshot.readDeltaBase]） */
    internal var deltaBaseReader: (Path) -> DeltaBase? = { path ->
        DataInputStream(BufferedInputStream(Files.newInputStream(path), 64)).use { MachineSnapshot.readDeltaBase(it) }
    }

    private class Marks(var current: Long? = null) {
        /** 印が最後に変わる直前の値（[previous2] はその1つ前）。`claim` 直後・ID の振り直し直後は空 */
        var previous: Long? = null
        var previous2: Long? = null

        fun moveTo(value: Long) {
            previous2 = previous
            previous = current
            current = value
        }
    }

    /** UUID の登録（中身は持たない。同じものかだけを見る） */
    private class Registration

    /**
     * 書き込みのスレッドで終わった世代の書き込み（[pollCompleted] でメインスレッドが受け取る）。[pending] が `null` なのは、claim の
     * `owner.bin` だけを書く仕事の失敗（#585。[ownerOnly] がその ID。外には返さず、ID を外すだけ）
     */
    private class Done(
        val pending: PendingSave?,
        val registration: Registration?,
        val self: MachineOwner,
        val snapshot: MachineSnapshotSource?,
        val result: WriteResult?,
        val error: IOException?,
        /** 書けた全体の世代（冷えた起動の写しでないもの）なら、それを指す基準 */
        val newBase: DeltaBase? = null,
        /** この UUID は `owner.bin` が書けず、使えない（規則 7b） */
        val unusable: Boolean = false,
        /** `owner.bin` だけを書く仕事の ID（[pending] が `null` のとき） */
        val ownerOnly: UUID? = null,
    )

    /** [id] のディレクトリ */
    public fun directoryOf(id: UUID): Path = root.resolve(id.toString())

    /** [id] の世代 [generation] のファイル */
    public fun generationFile(id: UUID, generation: Long): Path = directoryOf(id).resolve("$generation$GENERATION_SUFFIX")

    // ---- 持ち主の確かめ ----

    /**
     * 読み込まれたブロックエンティティ（位置 [self]）の NBT の値で、持ち主を確かめる（#81 の 8.）。例外を投げない。
     *
     * 判定の順: 値の検査 → 索引の重複 → `owner.bin` がない（自分を持ち主に）→ 持ち主が自分 → 引き取り（NBT の epoch が記録以上）→ 古い写し。
     * 新しい UUID にしたときも索引に入れる（[assignNewId]）。通った ID は索引に入れる（外すのは呼び出し側。アンロード・`setRemoved`）。
     *
     * メインスレッドでは `owner.bin` を書かない（#585。ADR 0084 の規則 7a）。引き取りの `owner.bin` は書き込みの列に入れ、`owner.bin` が
     * なかったとき・新しい UUID は最初の世代の前に書く。書けなければ、メインスレッドが完了を受け取ったときにその ID を外し（規則 7b）、
     * 次の [submit] が振り直す。`owner.bin` を読むのは、覚えておらず、このサーバーで消した ID でも、開いたときに根になかった ID でもなく、
     * 先読みの値も使えないときだけ。そのときその UUID の仕事が列に残っていれば、終わるのを待ってから読む（上限 [claimWaitMillis]。
     * 過ぎたら新しい UUID）。
     *
     * この口は、読みが要れば呼んだスレッドで読む（テスト・同期の置き場用）。サーバーのメインスレッドは [claimOrDefer] を使う（#869）
     */
    public fun claim(rawId: String?, rawGeneration: Long, rawEpoch: Long, self: MachineOwner): Claim {
        val start = System.nanoTime()
        try {
            return claimInner(rawId, rawGeneration, rawEpoch, self, defer = false)!!
        } finally {
            claimCount++
            claimNanos += System.nanoTime() - start
        }
    }

    // ---- 読みの要る claim の待ち（#869。ADR 0084 の規則 7a） ----

    /** 待っている claim（[claimOrDefer]） */
    private class DeferredEntry(
        val key: Any,
        val rawId: String,
        val rawGeneration: Long,
        val rawEpoch: Long,
        val self: MachineOwner,
        val since: Long,
    )

    /** ID → 待っている claim（来た順。メインスレッドだけが触る）。ID の並びも入れた順（確定の順を決まったものにする） */
    private val deferredById = LinkedHashMap<UUID, ArrayDeque<DeferredEntry>>()

    /** 待ちの鍵 → 待っている claim（[cancelDeferredClaim]。鍵は参照で比べる） */
    private val deferredByKey = java.util.IdentityHashMap<Any, Pair<UUID, DeferredEntry>>()

    /** 書き込みのスレッドに読みを頼んだ ID → 頼んだ番号（古い頼みの結果を捨てる。メインスレッドだけが触る） */
    private val requestedReads = HashMap<UUID, Long>()

    /** 先読みのスレッドに先に読んでほしいと頼んだ ID（同じ ID を何度も積まない。メインスレッドだけが触る） */
    private val urgentRequested = HashSet<UUID>()

    /** 書き込みのスレッドが読んで、まだ claim に使っていない `owner.bin`（メインスレッドだけが触る。その ID の待ちがなくなったら捨てる） */
    private val ownerReads = HashMap<UUID, Prefetched>()

    /** 書き込みのスレッドが読んだ `owner.bin`（メインスレッドが [pollDeferredClaims] で受け取る） */
    private class OwnerRead(val id: UUID, val seq: Long, val value: Prefetched)

    private val ownerReadResults = ConcurrentLinkedQueue<OwnerRead>()
    private var readSeq = 0L

    /** 書き込みのスレッドが claim のために `owner.bin` を読む口（テストが差し替える。#869） */
    internal var writerOwnerReader: (Path) -> OwnerRecord? = { OwnerRecord.read(it) }

    /** [claimOrDefer] が待ちにした回数（数え口。#869） */
    public var deferredClaims: Long = 0
        private set

    /** 今待っている claim の数（数え口。#869） */
    public val waitingClaims: Int get() = deferredByKey.size

    /** 待ちにしてから確定するまでの時間の最大（ナノ秒。数え口。#869） */
    public var maxDeferredClaimNanos: Long = 0
        private set

    /** 先読みのスレッドに、順番より先に読んでほしいと頼んだ回数（数え口。#869） */
    public var prefetchRequests: Long = 0
        private set

    /** claim のために、書き込みのスレッドに `owner.bin` の読みを頼んだ回数（数え口。#869） */
    public var writerOwnerReadRequests: Long = 0
        private set

    /**
     * [claim] と同じ判定をするが、メインスレッドで `owner.bin` を読まない（#869。ADR 0084 の規則 7a）。メインスレッドだけが呼ぶ。
     *
     * メモリで決まる（値の検査・索引の重複・覚えている `owner.bin`・消した ID・根になかった ID・先読みの値がある）なら、その場で結果を返す。
     * 読みが要るときは `null` を返して待ちにし、読みを先読みのスレッド（まだ読んでいなければ順番より先に）か、書き込みのスレッド
     * （その ID の仕事の後ろ。先読みで決まらない ID）に回す。結果は後の tick の [pollDeferredClaims] が [key] と一緒に返す。
     * 同じ ID に待ちがあれば、後ろに並ぶ（確定は ID ごとに来た順）。同じ [key] の待ちがあれば、取り消してから出し直す。
     * 待っている間は索引に入れず、`owner.bin` も書かない。待ちを取り消すときは [cancelDeferredClaim]
     *
     * @param key 待ちの鍵（呼び手のブロックエンティティなど。参照で比べる）
     * @return 決まった結果。待ちにしたら `null`
     */
    public fun claimOrDefer(rawId: String?, rawGeneration: Long, rawEpoch: Long, self: MachineOwner, key: Any): Claim? {
        val start = System.nanoTime()
        try {
            cancelDeferredClaim(key)
            val id = CanonicalUuid.parse(rawId)
            val waiting = if (id != null) deferredById[id] else null
            if (waiting == null) {
                val claim = claimInner(rawId, rawGeneration, rawEpoch, self, defer = true)
                if (claim != null) return claim
            }
            // ここに来るのは、ID が読めて、読みが要る（か、その ID に待ちがある）ときだけ
            val entry = DeferredEntry(key, rawId!!, rawGeneration, rawEpoch, self, start)
            deferredById.getOrPut(id!!) { ArrayDeque() }.addLast(entry)
            deferredByKey[key] = id to entry
            deferredClaims++
            ensureOwnerRead(id)
            // 起動の直後には台数だけ来うるので、文字列は DEBUG のときだけ作る
            LOG.log(System.Logger.Level.DEBUG, java.util.function.Supplier { "Deferring the claim of $id at $self until its owner.bin is read" })
            return null
        } finally {
            claimCount++
            claimNanos += System.nanoTime() - start
        }
    }

    /** [key] の待ちを取り消す（アンロード・`setRemoved`・NBT の値が変わったとき。#869）。待ちがなければ何もしない。メインスレッドだけが呼ぶ */
    public fun cancelDeferredClaim(key: Any) {
        val (id, entry) = deferredByKey.remove(key) ?: return
        val queue = deferredById[id] ?: return
        queue.remove(entry)
        if (queue.isEmpty()) forgetWaiting(id)
    }

    /**
     * テスト用（#869。GameTest が読みの要る claim を作る）: [id] の覚えている `owner.bin` を忘れ、開いたときに根にあった ID として扱う
     * （再起動の後と同じく、次の claim は `owner.bin` の読みを要する）。索引・登録・列の仕事には触らない
     */
    public fun forgetOwnerForTest(id: UUID) {
        owners.remove(id)
        unwrittenOwners.remove(id)
        deletedIds.remove(id)
        knownDirectories += id
    }

    /** [key] の claim が待っているか（テスト用） */
    public fun isClaimDeferred(key: Any): Boolean = deferredByKey.containsKey(key)

    /**
     * 待っている claim のうち、読みの結果がそろったものを確定して返す（#869。メインスレッドで毎 tick、[pollCompleted] の後に呼ぶ）。
     * 確定は ID ごとに来た順で、そのたびに [claim] と同じ判定（索引の重複・覚えている `owner.bin` を含む）をやり直す。
     * 返したものは待ちから外れている（索引にも入っている）ので、呼び手はその場で受け取る。待ちがなければ何もしない
     */
    public fun pollDeferredClaims(): List<DeferredClaim> {
        while (true) {
            val read = ownerReadResults.poll() ?: break
            if (requestedReads[read.id] != read.seq) continue
            requestedReads.remove(read.id)
            ownerReads[read.id] = read.value
        }
        if (deferredById.isEmpty()) return emptyList()
        val out = ArrayList<DeferredClaim>()
        val start = System.nanoTime()
        val ids = deferredById.entries.iterator()
        val left = ArrayList<UUID>()
        while (ids.hasNext()) {
            val (id, queue) = ids.next()
            // 判定の材料が何も変わっていなければ、判定をやり直さない（待ちの台数 × tick の、UUID の読み直しと索引の引きを避ける）
            if (!mayDecide(id)) {
                left += id
                continue
            }
            while (queue.isNotEmpty()) {
                val entry = queue.first()
                val claim = claimInner(entry.rawId, entry.rawGeneration, entry.rawEpoch, entry.self, defer = true) ?: break
                queue.removeFirst()
                deferredByKey.remove(entry.key)
                maxDeferredClaimNanos = maxOf(maxDeferredClaimNanos, System.nanoTime() - entry.since)
                out += DeferredClaim(entry.key, claim)
            }
            if (queue.isEmpty()) {
                ids.remove()
                forgetReads(id)
            } else {
                left += id
            }
        }
        for (id in left) ensureOwnerRead(id)
        if (out.isNotEmpty()) claimNanos += System.nanoTime() - start
        return out
    }

    /**
     * 待っている [id] の claim が、今の判定で決まりうるか（読みの結果が来た・先読みの値がある・索引に入った・覚えた・消した）。
     * 偽なら [claimInner] は必ず待ちを返すので、呼ばずに飛ばす
     */
    private fun mayDecide(id: UUID): Boolean =
        id in ownerReads || index.ownerOf(id) != null || owners.containsKey(id) || id in deletedIds ||
            (id !in touched && prefetched.containsKey(id))

    /** [id] の待ちがなくなった: 待ちの表と、頼んだ読み・読んだ結果を捨てる */
    private fun forgetWaiting(id: UUID) {
        deferredById.remove(id)
        forgetReads(id)
    }

    private fun forgetReads(id: UUID) {
        requestedReads.remove(id)
        ownerReads.remove(id)
        urgentRequested.remove(id)
    }

    /**
     * 待っている [id] の `owner.bin` の読みを手配する（#869）。先読みのスレッドがまだ読みうる（動いていて、開いたときの ID で、触っていない）
     * なら、順番より先に読んでもらう。そうでなければ（先読みが終わった・触った ID・根の列挙が欠けて先読みにない ID）、書き込みのスレッドの
     * その ID の列の後ろに読む仕事を入れる（前の仕事の後のディスクを読むので、仕事の終わりを待たなくてよい）。頼んであれば何もしない
     */
    private fun ensureOwnerRead(id: UUID) {
        if (id in requestedReads || id in ownerReads) return
        if (id !in touched && prefetchRunning && id in prefetchIds) {
            if (urgentRequested.add(id)) {
                prefetchUrgent.add(id)
                prefetchRequests++
            }
            return
        }
        val seq = ++readSeq
        requestedReads[id] = seq
        writerOwnerReadRequests++
        enqueue(id, 0, {
            ownerReadResults.add(OwnerRead(id, seq, readOwnerOffMain(writerOwnerReader, id)))
            null
        }, {
            ownerReadResults.add(OwnerRead(id, seq, Prefetched.Unreadable("the save writer is closed")))
            null
        }, blocksRestore = false)
    }

    /**
     * 次の [submit] が [id] の `owner.bin` も書く（claim が受け入れた ID・振った ID で、まだ書いていない。#585）。メインスレッドだけが呼ぶ。
     * 自動保存の書き込みの見積もり（2 回）に使う
     */
    public fun hasUnwrittenOwner(id: UUID): Boolean = id in unwrittenOwners

    /** [claim] を呼んだ回数（数え口。#585） */
    public var claimCount: Long = 0
        private set

    /** [claim] にかかった時間の合計（ナノ秒。`owner.bin` の読み（先読みがなかったとき）と、その UUID の仕事の待ちを含む。書きは含まない。数え口。#585） */
    public var claimNanos: Long = 0
        private set

    /** メインスレッドで `owner.bin` を読んだ回数（数え口。#585） */
    public var mainOwnerReads: Long = 0
        private set

    /** メインスレッドで `owner.bin` を読んだ時間の合計（ナノ秒。数え口。#585） */
    public var mainOwnerReadNanos: Long = 0
        private set

    private fun readOwner(id: UUID): OwnerRecord? {
        val start = System.nanoTime()
        try {
            return OwnerRecord.read(directoryOf(id).resolve(OwnerRecord.FILE_NAME))
        } finally {
            mainOwnerReads++
            mainOwnerReadNanos += System.nanoTime() - start
        }
    }

    /**
     * claim の判定。[defer] なら、読みが要るときにメインスレッドで読まず、書き込みのスレッドが読んだ結果（[ownerReads]）があれば使い、
     * なければ何も変えずに `null` を返す（#869）。[defer] が偽なら、呼んだスレッドで読み、`null` を返さない
     */
    private fun claimInner(rawId: String?, rawGeneration: Long, rawEpoch: Long, self: MachineOwner, defer: Boolean): Claim? {
        val id = CanonicalUuid.parse(rawId)
        if (id == null || !MachineIdentityLimits.isValidCounter(rawGeneration) || !MachineIdentityLimits.isValidCounter(rawEpoch)) {
            return fresh(self, ClaimResult.NEW_ID_INVALID, "invalid values (id=$rawId, generation=$rawGeneration, epoch=$rawEpoch)")
        }
        val indexed = index.ownerOf(id)
        if (indexed != null && indexed != self) return fresh(self, ClaimResult.NEW_ID_DUPLICATE_LOADED, "$id is already loaded at $indexed")
        val known = owners[id]
        val record: OwnerRecord? = when {
            known != null -> known
            // このサーバーで消した: 削除の仕事の後に書くので「ない」（FIFO）
            id in deletedIds -> null
            // 開いたときに根になく、このサーバーも作っていない（動いている間に外から置かれたディレクトリは見ない。ADR 0084）
            scanComplete && id !in knownDirectories -> null
            else -> when (val p = if (id in touched) null else prefetched.remove(id)) {
                is Prefetched.Found -> p.record.also { prefetchHits++ }
                Prefetched.Missing -> null.also { prefetchHits++ }
                is Prefetched.Unreadable -> {
                    touch(id)
                    return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: ${p.reason}")
                }
                null -> if (defer) {
                    // 書き込みのスレッドが読んだ結果（その ID の前の仕事の後のディスク）。なければ待ち（何も変えない）
                    when (val r = ownerReads.remove(id) ?: return null) {
                        is Prefetched.Found -> r.record
                        Prefetched.Missing -> null
                        is Prefetched.Unreadable -> {
                            touch(id)
                            return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: ${r.reason}")
                        }
                    }
                } else {
                    if (!awaitIdle(id)) return fresh(self, ClaimResult.NEW_ID_UNREADABLE, busy(id))
                    touch(id)
                    try {
                        readOwner(id)
                    } catch (e: IOException) {
                        return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: ${e.message}")
                    } catch (e: RuntimeException) {
                        return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: $e")
                    }
                }
            }
        }
        touch(id)
        val epoch: Long
        val result: ClaimResult
        when {
            record == null -> {
                // owner.bin は最初の世代の前に書く（#585）
                val next = OwnerRecord(rawEpoch, self)
                renewRegistrationIfOwnerPending(id)
                owners[id] = next
                unwrittenOwners[id] = next
                knownDirectories += id
                deletedIds -= id
                epoch = rawEpoch
                result = ClaimResult.KEPT
            }
            record.owner == self -> {
                // NBT の epoch が古いのは、引き取って owner.bin を書いた後、チャンクの保存の前に落ちたとき。記録の値に直す
                owners[id] = record
                // 前の持ち主の仕事が残っているなら、その owner.bin の書き込みが失敗しても自分の状態を外さないよう、登録を新しくして書き直す
                if (renewRegistrationIfOwnerPending(id) && id !in unwrittenOwners) enqueueOwnerWrite(id, record, self)
                epoch = record.epoch
                result = ClaimResult.KEPT
            }
            rawEpoch >= record.epoch -> {
                if (record.epoch >= MachineIdentityLimits.MAX_COUNTER) {
                    return fresh(self, ClaimResult.NEW_ID_INVALID, "epoch of $id cannot be advanced")
                }
                val next = OwnerRecord(record.epoch + 1, self)
                renewRegistrationIfOwnerPending(id)
                owners[id] = next
                unwrittenOwners.remove(id)
                knownDirectories += id
                epoch = next.epoch
                result = ClaimResult.TAKEN_OVER
                enqueueOwnerWrite(id, next, self)
            }
            else -> {
                owners[id] = record
                return fresh(self, ClaimResult.NEW_ID_STALE, "$id is a stale copy (epoch $rawEpoch < ${record.epoch}, owner ${record.owner})")
            }
        }
        index.put(id, self)
        registrations.getOrPut(id) { Registration() }
        // 印は、読み込んだ NBT（チャンクのファイルから来たもの）の世代から始める。前の印は空
        marks[id] = Marks(rawGeneration)
        return Claim(MachineIdentity(id, rawGeneration, epoch), result)
    }

    /**
     * [id] の `owner.bin` を書く仕事が列に残っている（まだ書いていない）か、書けなかった失敗がまだ受け取られていない（「使えない」の印が
     * 付いている）なら、登録を新しくして `true`。その仕事の失敗を後から受け取っても、引き取り直した登録の状態を外さないように（#585）。
     * `owner.bin` を書かない世代の仕事だけが残っているときは新しくしない（その完了は、今の登録の最新の世代・基準に進める）。
     * 印は、後の `owner.bin` の書き込みの成功で外れる。書き込みのスレッドは、印を付けてから [ownerWriteJobs] を減らすので、
     * ここでは [ownerWriteJobs] → 印の順に見る
     */
    private fun renewRegistrationIfOwnerPending(id: UUID): Boolean {
        if (!ownerWriteJobs.containsKey(id) && id !in unusable) return false
        registrations[id] = Registration()
        return true
    }

    /**
     * [id] の `owner.bin` を [record] で書く仕事を列に入れる（引き取り。#585）。その UUID の前の仕事の後に走る（FIFO）。
     * 書けなければ、受け取ったときにその ID を外す（規則 7b）。復元の待ち（[awaitRestorable]）には数えない
     */
    private fun enqueueOwnerWrite(id: UUID, record: OwnerRecord, self: MachineOwner) {
        val registration = registrations.getOrPut(id) { Registration() }
        enqueue(id, 0, {
            prepareDirectory(id)
            awaitPrefetchRead(id)
            try {
                OwnerRecord.write(directoryOf(id).resolve(OwnerRecord.FILE_NAME), record)
                // 書けたので、前の登録の失敗の印が残っていても外す（後に入れた世代は書ける）
                forgetUnusable(id)
                null
            } catch (e: IOException) {
                LOG.log(System.Logger.Level.WARNING, "Failed to write owner.bin of $id", e)
                unusable += id
                Done(null, registration, self, null, null, e, unusable = true, ownerOnly = id)
            }
        }, {
            LOG.log(System.Logger.Level.WARNING, "The save writer is closed; owner.bin of $id is not written")
            null
        }, blocksRestore = false, writesOwner = true)
    }

    private fun busy(id: UUID) = "writes of $id did not finish within $claimWaitMillis ms"

    /** claim でその UUID の仕事を待った時間の合計（ナノ秒。数え口。#84） */
    public var claimWaitNanos: Long = 0
        private set

    /** [id] の仕事が列に残っていれば、終わるまで待つ（上限 [claimWaitMillis]。`owner.bin` を読む前）。待ちきれなければ `false`（規則 7a） */
    private fun awaitIdle(id: UUID): Boolean {
        if (!outstanding.containsKey(id)) return true
        val start = System.nanoTime()
        return try {
            awaitWrites(id, claimWaitMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            claimWaitNanos += System.nanoTime() - start
        }
    }

    private fun fresh(self: MachineOwner, result: ClaimResult, reason: String): Claim {
        val identity = assignNewId(self)
        LOG.log(System.Logger.Level.WARNING, "Machine at $self gets a new id (${identity?.id ?: "none"}, $result): $reason")
        return Claim(identity, result)
    }

    /**
     * 新しい ID を振る（#81 の 7.。claim の振り直し）: 索引にもディレクトリにもない UUID を作り、索引に入れる。印なしから始める。
     * `owner.bin`（epoch 0、持ち主は [self]）はメインスレッドでは書かず、次の [submit] が最初の世代の前に書き込みのスレッドで書く（#585）。
     * 書けなければ、そのときに ID を外し、その次の [submit] が振り直す（規則 7b）。今は `null` を返さない
     */
    public fun assignNewId(self: MachineOwner): MachineIdentity? {
        val id = newUuid()
        val record = OwnerRecord(0, self)
        owners[id] = record
        unwrittenOwners[id] = record
        touch(id)
        register(id, self)
        return MachineIdentity(id, 0, 0)
    }

    /**
     * 索引・覚えている `owner.bin`・列・ディレクトリがあるかもしれない UUID（[knownDirectories]）のどれにもない UUID。
     * メモリだけで確かめ、ディスクは見ない（#579、ADR 0084 の規則 2）。返す ID は [knownDirectories] に足す
     */
    private fun newUuid(): UUID {
        var id: UUID
        do {
            id = uuidSource()
        } while (
            index.ownerOf(id) != null || owners.containsKey(id) || outstanding.containsKey(id) || id in knownDirectories ||
            deferredById.containsKey(id)
        )
        knownDirectories += id
        return id
    }

    /** 振った [id] を索引に入れ、新しい登録で始める */
    private fun register(id: UUID, self: MachineOwner) {
        index.put(id, self)
        registrations[id] = Registration()
        marks.remove(id)
        bases.remove(id)
        latest.remove(id)
        reserved.remove(id)
        inFlight.remove(id)
    }

    // ---- 書き込み ----

    /**
     * [current] の次の世代の書き込みを予約し、書き込みの列に入れる（メインスレッド。#84）。印は変えない。
     *
     * 予約の時点で、覚えている `owner.bin` の持ち主と epoch が [self]・[current] と合うかを確かめる。合わない（または覚えていない、
     * 次の世代が上限を超える）か、[current] が `null` なら、新しい UUID を振り（索引を付け替え、`owner.bin` は書き込みのスレッドで
     * 世代の前に書く）、そこに書く。合っていても、claim・[assignNewId] が `owner.bin` をまだ書いていなければ、同じく世代の前に書く（#585）。次の世代は「[current] の世代 + 1」と「予約した最大の世代 + 1」の大きい方。
     *
     * 差分の写し（[MachineSnapshotSource.deltaBase] が `null` でない。#83）は、[deltaBaseFor] と同じ確かめで得る基準と一致するときだけ入れる
     * （振り直しは起きない）。合わなければ何も入れずに [DeltaBaseUnavailableException]。
     *
     * 書けたかどうかは、後で [pollCompleted] が返す（予約1つにつき完了が必ず1つ）。[tag] はその完了の [SaveCompletion.pending] に入る
     *
     * @throws DeltaBaseUnavailableException 差分の写しの基準が今の基準と合わない
     */
    @Throws(DeltaBaseUnavailableException::class)
    public fun submit(current: MachineIdentity?, self: MachineOwner, snapshot: MachineSnapshotSource, tag: Any?): PendingSave {
        val wantedBase = snapshot.deltaBase
        if (wantedBase != null) {
            val candidate = current?.let { deltaBaseCandidate(it, self) }
            if (candidate != wantedBase) {
                throw DeltaBaseUnavailableException("the delta base $wantedBase of ${current?.id} is not the current base $candidate")
            }
        }
        val identity: MachineIdentity
        var ownerRecord: OwnerRecord? = null
        var reassigned = false
        if (current != null && matchesOwner(current, self)) {
            identity = current
            // claim・assignNewId で書かずにおいた owner.bin を、この世代の前に書く（#585）
            ownerRecord = unwrittenOwners.remove(current.id)
        } else {
            val id = newUuid()
            val record = OwnerRecord(0, self)
            if (current != null) {
                val old = owners[current.id]
                LOG.log(
                    System.Logger.Level.WARNING,
                    "Machine ${current.id} at $self does not match its owner.bin (${old?.owner}, epoch ${old?.epoch} vs ${current.epoch}) " +
                        "or ran out of generations; writing under a new id $id",
                )
                index.removeIfOwner(current.id, self)
                reassigned = true
            }
            owners[id] = record
            register(id, self)
            ownerRecord = record
            identity = MachineIdentity(id, 0, 0)
        }
        val id = identity.id
        val generation = nextGeneration(identity)
        reserved[id] = generation
        inFlight.getOrPut(id) { HashMap() }[generation] = wantedBase?.generation
        val estimated = snapshot.estimatedBytes.coerceAtLeast(0)
        val pending = PendingSave(
            identity.copy(generation = generation), reassigned, if (ownerRecord != null) 2 else 1, estimated, wantedBase, tag,
        )
        val registration = registrations.getOrPut(id) { Registration() }
        enqueue(
            id,
            estimated,
            { writeGeneration(pending, registration, self, snapshot, ownerRecord, if (reassigned) current?.id else null) },
            { Done(pending, registration, self, snapshot, null, IOException("the save writer is closed; generation $generation of $id is not written")) },
            writesOwner = ownerRecord != null,
        )
        return pending
    }

    /**
     * [deltaBaseFor] → [capturer] で取り出し → [submit] をまとめて行う（#83・#84）。[submit] が [DeltaBaseUnavailableException] を投げたら
     * （何も入れていない）、その場で全体を取り直して（`capturer.capture(null, retry = true)`）入れる。取り出せなければ `null`
     */
    public fun captureAndSubmit(current: MachineIdentity?, self: MachineOwner, capturer: SnapshotCapturer, tag: Any?): CaptureSubmitResult? {
        val base = current?.let { deltaBaseFor(it, self) }
        var start = System.nanoTime()
        val source = capturer.capture(base, false)
        var captureNanos = System.nanoTime() - start
        if (source == null) return null
        return try {
            CaptureSubmitResult(submit(current, self, source, tag), captureNanos, false)
        } catch (e: DeltaBaseUnavailableException) {
            LOG.log(System.Logger.Level.WARNING, "Delta base of ${current?.id} is unavailable; capturing a full snapshot again: ${e.message}")
            start = System.nanoTime()
            val full = capturer.capture(null, true)
            captureNanos += System.nanoTime() - start
            if (full == null) return null
            CaptureSubmitResult(submit(current, self, full, tag), captureNanos, true)
        }
    }

    /**
     * 同期の書き込み（[submit] して、列を流しきるまで待ち、自分の完了だけを受け取って返す。ほかの完了は次の [pollCompleted] に残す）。
     * テストと、スレッドを使わない置き場用
     *
     * @throws IOException 書けなかった（振り直しの `owner.bin` が書けなかったときも）
     * @throws DeltaBaseUnavailableException 差分の写しの基準が今の基準と合わない
     */
    @Throws(IOException::class)
    public fun write(current: MachineIdentity, self: MachineOwner, snapshot: MachineSnapshotSource): WriteResult {
        val pending = submit(current, self, snapshot, null)
        val finished = try {
            executor.flush(WRITE_WAIT_MILLIS) && awaitWrites(pending.identity.id, WRITE_WAIT_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while writing generation ${pending.identity.generation} of ${pending.identity.id}", e)
        }
        if (!finished) throw IOException("generation ${pending.identity.generation} of ${pending.identity.id} was not written in time")
        drainCompleted()
        val mine = ready.firstOrNull { it.pending === pending } ?: throw IOException("the completion of ${pending.identity} was not returned")
        ready.remove(mine)
        return mine.result ?: throw mine.error!!
    }

    /** 書き込みのスレッドの仕事: 世代のファイル（新しく振った UUID なら先に `owner.bin`）を書く。例外は捕まえて失敗の完了にする */
    private fun writeGeneration(
        pending: PendingSave,
        registration: Registration,
        self: MachineOwner,
        snapshot: MachineSnapshotSource,
        ownerRecord: OwnerRecord?,
        forget: UUID?,
    ): Done {
        // 振り直した元の UUID の頭の写しは忘れる（ファイルは残るが、要れば読み直せる）
        if (forget != null) baseOf.remove(forget)
        val id = pending.identity.id
        val generation = pending.identity.generation
        fun failed(error: IOException) = Done(pending, registration, self, snapshot, null, error, unusable = id in unusable)
        try {
            // owner.bin を自分で書く仕事は、前の登録の失敗の印があっても書く（書けたら印を外す。#585）
            if (ownerRecord == null && id in unusable) {
                return failed(IOException("owner.bin of $id could not be written; generation $generation is not written"))
            }
            prepareDirectory(id)
            var fileWriteNanos = 0L
            if (ownerRecord != null) {
                val start = System.nanoTime()
                awaitPrefetchRead(id)
                try {
                    OwnerRecord.write(directoryOf(id).resolve(OwnerRecord.FILE_NAME), ownerRecord)
                } catch (e: IOException) {
                    unusable += id
                    return failed(IOException("owner.bin of $id at $self cannot be written", e))
                }
                forgetUnusable(id)
                fileWriteNanos += System.nanoTime() - start
            }
            val encodeStart = System.nanoTime()
            val built = snapshot.build(id, generation, modVersion)
            check(built.deltaBase == pending.deltaBase) { "写しの基準と保存データの基準が違う" }
            val bytes = ByteArrayOutputStream(256)
            DataOutputStream(bytes).use { out -> built.writeTo(out) }
            val data = bytes.toByteArray()
            val encodeNanos = System.nanoTime() - encodeStart
            val tmpfsBytes = built.sections.sumOf { if (it.kind == SnapshotSection.TMPFS) it.size else 0 }
            val writeStart = System.nanoTime()
            writeAtomically(generationFile(id, generation), data)
            fileWriteNanos += System.nanoTime() - writeStart
            baseOf.getOrPut(id) { HashMap() }[generation] = built.deltaBase?.generation
            // 全体の世代（冷えた起動の写しでないもの）を、次の差分の基準にする（覚えるのは完了を受け取ったとき。規則 12）
            val newBase = if (built.deltaBase == null && !built.requiresColdBoot) {
                DeltaBase(id, generation, data.size.toLong(), crc32c(data))
            } else {
                null
            }
            val result = WriteResult(
                pending.identity, pending.reassigned, pending.writes, encodeNanos, fileWriteNanos, data.size, tmpfsBytes, built.deltaBase != null,
            )
            return Done(pending, registration, self, snapshot, result, null, newBase)
        } catch (e: IOException) {
            return failed(e)
        } catch (e: Throwable) {
            return failed(IOException("failed to write generation $generation of $id: $e", e))
        }
    }

    /** 書き込みのスレッドで、このサーバーで初めて書くディレクトリの残った `*.tmp` を片付ける（規則 8） */
    private fun prepareDirectory(id: UUID) {
        if (!initialized.add(id)) return
        try {
            deleteStaleTemps(directoryOf(id))
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "Failed to delete leftover temporary files of $id", e)
        }
    }

    /** [task] を列に入れる。閉じていれば [rejected] の完了で返す。終わったら（どちらでも）見積もりと数を戻し、待ち手を起こす */
    private fun enqueue(id: UUID, bytes: Long, task: () -> Done?, rejected: () -> Done?, blocksRestore: Boolean = true, writesOwner: Boolean = false) {
        if (writesOwner) ownerWriteJobs.merge(id, 1, Int::plus)
        if (blocksRestore) restoreBlocking.merge(id, 1, Int::plus)
        outstanding.merge(id, 1, Int::plus)
        pendingTasks.incrementAndGet()
        pendingBytesCounter.addAndGet(bytes)
        val accepted = try {
            executor.execute(id) {
                var done: Done? = null
                try {
                    done = task()
                } finally {
                    finishTask(id, bytes, done, blocksRestore, writesOwner)
                }
            }
        } catch (e: RuntimeException) {
            LOG.log(System.Logger.Level.WARNING, "Failed to queue a save task of $id", e)
            false
        }
        if (!accepted) finishTask(id, bytes, rejected(), blocksRestore, writesOwner)
    }

    private fun finishTask(id: UUID, bytes: Long, done: Done?, blocksRestore: Boolean, writesOwner: Boolean) {
        // 印を付けた後（仕事の中）に減らす。[renewRegistrationIfOwnerPending] が、仕事の数 → 印の順に見るため
        if (writesOwner) ownerWriteJobs.compute(id) { _, n -> if (n == null || n <= 1) null else n - 1 }
        // 「使えない」の失敗は、積む前に数える（受け取る側が減らす。#566）
        if (done?.unusable == true) unreceivedUnusable.merge(id, 1, Int::plus)
        // 完了を先に積む（その UUID を待っていた呼び出しが、起きたときに完了を受け取れるように）
        if (done != null) completed.add(done)
        if (blocksRestore) restoreBlocking.compute(id) { _, n -> if (n == null || n <= 1) null else n - 1 }
        pendingBytesCounter.addAndGet(-bytes)
        pendingTasks.decrementAndGet()
        val left = outstanding.compute(id) { _, n -> if (n == null || n <= 1) null else n - 1 }
        if (left == null && id in unusable && !unreceivedUnusable.containsKey(id)) forgetUnusable(id)
        waitLock.withLock { progress.signalAll() }
    }

    /** [id] の「使えない」の印を外す（#566。受け取っていない失敗の数は、受け取る側が減らすので触らない） */
    private fun forgetUnusable(id: UUID) {
        unusable.remove(id)
    }

    /** UUID → 列に入れて終わっていない、`owner.bin` を書く仕事（世代の仕事に付くものも含む）の数（#585） */
    private val ownerWriteJobs = ConcurrentHashMap<UUID, Int>()

    /** 列で `owner.bin` だけを書く仕事を除いた、[id] の仕事の数（[awaitRestorable]。#585） */
    private val restoreBlocking = ConcurrentHashMap<UUID, Int>()

    /** [id] が「使えない」の印を持つか（テスト用） */
    internal fun isUnusable(id: UUID): Boolean = id in unusable

    private fun nextGeneration(identity: MachineIdentity): Long =
        maxOf(identity.generation, reserved[identity.id] ?: -1L, latest[identity.id] ?: -1L) + 1

    /** 覚えている `owner.bin` の持ち主・epoch が合い、次の世代が上限以下（[submit] が振り直さずに書ける） */
    private fun matchesOwner(current: MachineIdentity, self: MachineOwner): Boolean {
        val record = owners[current.id]
        return record != null && record.owner == self && record.epoch == current.epoch &&
            nextGeneration(current) <= MachineIdentityLimits.MAX_COUNTER
    }

    /** [submit] が差分の世代を書ける基準（#83）。振り直さずに書けて、今の基準を覚えているときだけ */
    private fun deltaBaseCandidate(current: MachineIdentity, self: MachineOwner): DeltaBase? {
        if (!matchesOwner(current, self)) return null
        return bases[current.id]
    }

    /**
     * 次の差分の世代が指せる基準（#83）。`null` なら全体を書く（置き場が基準を知らない（初めての保存・再起動の後・振り直しの後・
     * 全体の世代の書き込みの完了をまだ受け取っていない）、持ち主・epoch が合わない、世代の上限）。同じ呼び出しの中で、置き場を変えずに
     * [submit] まで進めれば、[submit] の確かめと一致する
     */
    public fun deltaBaseFor(current: MachineIdentity, self: MachineOwner): DeltaBase? = deltaBaseCandidate(current, self)

    // ---- 完了の受け取り ----

    /**
     * 終わった書き込みを受け取る（メインスレッドで、毎 tick。#84）。失敗したもの・走らせなかったものも入る。
     * その予約を作った登録が今も同じときだけ、書けた最新の世代・今の基準を進め、[MachineSnapshotSource.committed] を呼ぶ。
     * 新しく振った `owner.bin` が書けなかった UUID は、覚えていること・索引から外す（規則 7b）
     */
    public fun pollCompleted(): List<SaveCompletion> {
        drainCompleted()
        if (ready.isEmpty()) return emptyList()
        val out = ArrayList(ready)
        ready.clear()
        return out
    }

    private fun drainCompleted() {
        while (true) {
            val done = completed.poll() ?: return
            accept(done)?.let { ready += it }
        }
    }

    private fun accept(done: Done): SaveCompletion? {
        val pending = done.pending
        val id = pending?.identity?.id ?: done.ownerOnly ?: return null
        val generation = pending?.identity?.generation ?: -1L
        if (pending != null) {
            inFlight[id]?.let {
                it.remove(generation)
                if (it.isEmpty()) inFlight.remove(id)
            }
        }
        if (done.unusable) {
            // 印を外すのは、その UUID の「使えない」の失敗を全部受け取り、かつ仕事が残っていないときだけ
            // （受け取る前に入れた後続も書かずに失敗で返す。受け入れ直しで失敗が 2 回続いても、最後の失敗を受け取るまで外さない。#566・#585）
            val left = unreceivedUnusable.compute(id) { _, n -> if (n == null || n <= 1) null else n - 1 }
            if (left == null && !outstanding.containsKey(id)) forgetUnusable(id)
        }
        if (done.registration != null && registrations[id] === done.registration) {
            val result = done.result
            if (done.unusable) {
                LOG.log(System.Logger.Level.WARNING, "Dropping the id $id because its owner.bin could not be written")
                owners.remove(id)
                unwrittenOwners.remove(id)
                index.removeIfOwner(id, done.self)
                registrations.remove(id)
                reserved.remove(id)
                inFlight.remove(id)
                marks.remove(id)
                latest.remove(id)
                bases.remove(id)
            } else if (result != null && pending != null) {
                latest[id] = maxOf(latest[id] ?: -1L, generation)
                done.newBase?.let { bases[id] = it }
                try {
                    done.snapshot?.committed(id, generation)
                } catch (e: RuntimeException) {
                    LOG.log(System.Logger.Level.WARNING, "Failed to commit generation $generation of $id", e)
                }
            }
        }
        if (pending == null) return null
        return SaveCompletion(pending, done.result, done.error)
    }

    /** 書き込みに回してよいか（書き込み待ちの見積もりの合計が上限未満。列が空なら必ず真。#84） */
    public val hasCapacity: Boolean get() = pendingBytesCounter.get() < maxPendingBytes

    /** 書き込み待ちの写しの見積もりの合計（バイト。書き込みのスレッドで、仕事が終わった時点で減る。数え口） */
    public val pendingBytes: Long get() = pendingBytesCounter.get()

    /** 列に入れて終わっていない仕事の数（数え口） */
    public val pendingCount: Int get() = pendingTasks.get()

    /** [hasCapacity] が真になるまで、最大 [timeoutMillis] ミリ秒待つ（アンロード・停止の取り出しの前。#84）。待ちきれなければ `false` */
    @Throws(InterruptedException::class)
    public fun awaitCapacity(timeoutMillis: Long): Boolean = awaitUntil(timeoutMillis) { hasCapacity }

    /** [id] の仕事がすべて終わるまで、最大 [timeoutMillis] ミリ秒待つ（claim が `owner.bin` を読む前など）。終われば `true` */
    @Throws(InterruptedException::class)
    public fun awaitWrites(id: UUID, timeoutMillis: Long): Boolean = awaitUntil(timeoutMillis) { !outstanding.containsKey(id) }

    /**
     * [id] の、`owner.bin` だけを書く仕事（claim の引き取り）を除いた仕事がすべて終わるまで、最大 [timeoutMillis] ミリ秒待つ
     * （#85 が世代のファイルを読む前。#585）。復元は世代のファイルだけを読むので、`owner.bin` の書き込みは待たない。終われば `true`
     */
    @Throws(InterruptedException::class)
    public fun awaitRestorable(id: UUID, timeoutMillis: Long): Boolean = awaitUntil(timeoutMillis) { !restoreBlocking.containsKey(id) }

    private inline fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        if (condition()) return true
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        waitLock.withLock {
            while (!condition()) {
                if (remaining <= 0) return false
                remaining = progress.awaitNanos(remaining)
            }
        }
        return true
    }

    /** 入れた仕事がすべて終わるまで待つ（停止の手順）。終われば `true`。完了は [pollCompleted] で受け取る */
    @Throws(InterruptedException::class)
    public fun flush(timeoutMillis: Long): Boolean = executor.flush(timeoutMillis)

    /** 書き込みの口を閉じる（入っている仕事は終える）。止まれば `true`。後から入れた予約は失敗の完了で返る */
    public fun close(timeoutMillis: Long): Boolean = executor.close(timeoutMillis)

    /** 予約した最大の世代（なければ空。#84） */
    public fun reservedGeneration(id: UUID): OptionalLong = reserved[id].toOptional()

    /**
     * 戻す世代（#84 の規則 9、回答 2）: [generation] のファイルがあればそれ。なければ、ディスクにある、それより小さい最大の世代。
     * それもなければ空（冷えた起動）。#85 が NBT の世代で呼ぶ（読む前に [awaitRestorable] で待つ）
     */
    public fun restorableGeneration(id: UUID, generation: Long): OptionalLong {
        val onDisk = generationsOnDisk(id)
        if (generation in onDisk) return OptionalLong.of(generation)
        return onDisk.lastOrNull { it < generation }.toOptional()
    }

    /**
     * [id] の世代 [generation] のファイルを読む（#83。差分の世代を戻すときは、これで基準を読んで確かめる）。
     * ファイルの長さと CRC32C も返す
     *
     * @throws IOException 読めない・壊れている
     */
    @Throws(IOException::class)
    public fun readGeneration(id: UUID, generation: Long): StoredSnapshot {
        val data = Files.readAllBytes(generationFile(id, generation))
        val snapshot = DataInputStream(data.inputStream()).use { MachineSnapshot.readFrom(it) }
        if (snapshot.machineId != id || snapshot.generation != generation) {
            throw SnapshotFormatException("世代のファイル $id/$generation の外枠が ${snapshot.machineId}/${snapshot.generation}")
        }
        return StoredSnapshot(snapshot, data.size.toLong(), crc32c(data))
    }

    /**
     * [id] の世代 [generation] のファイルの外枠から、書いた MOD のバージョンだけを読む（#88）。
     * ファイル全体の読み込み・CRC・節の解釈をせずに、戻す前に MOD のバージョンを比べるための口
     *
     * @throws IOException 読めない・外枠が壊れている・形式の版が違う
     */
    @Throws(IOException::class)
    public fun readGenerationModVersion(id: UUID, generation: Long): String =
        DataInputStream(BufferedInputStream(Files.newInputStream(generationFile(id, generation)), 256)).use { MachineSnapshot.readModVersion(it) }

    /**
     * [id] のディレクトリを消し、覚えていることも忘れる（プレイヤーの破壊・爆発のときだけ呼ぶ）。消すのは書き込みの列の仕事で、
     * それより前に入れた書き込みの後に走る（#84 の規則 7）。消せなければ warn
     */
    public fun delete(id: UUID) {
        owners.remove(id)
        unwrittenOwners.remove(id)
        deletedIds += id
        touch(id)
        marks.remove(id)
        latest.remove(id)
        reserved.remove(id)
        inFlight.remove(id)
        bases.remove(id)
        registrations.remove(id)
        enqueue(id, 0, {
            deleteDirectory(id)
            null
        }, {
            LOG.log(System.Logger.Level.WARNING, "The save writer is closed; the directory of $id is not deleted")
            null
        })
    }

    private fun deleteDirectory(id: UUID) {
        baseOf.remove(id)
        initialized.remove(id)
        awaitPrefetchRead(id)
        val dir = directoryOf(id)
        try {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (e: Exception) {
            LOG.log(System.Logger.Level.WARNING, "Failed to delete the directory of $id", e)
        }
    }

    // ---- 印と掃除 ----

    /**
     * チャンクの保存（`ChunkDataEvent.Save`）で、NBT に入った [id] の世代 [generation] を記録する（#81 の 9.）。
     *
     * [at]（タグの座標とディメンション）が覚えている `owner.bin` の持ち主と合うときだけ印を動かす。印が変わると、
     * 前の印 = 変わる前の印、今の印 = [generation] にする。印が進んだ（[ChunkMarkResult.ADVANCED]）ときだけ、
     * 残さない世代ファイルを消す仕事を書き込みの列に入れる（[pruneGenerations]）。戻す向き・同じ値では消さない
     */
    public fun markChunkGeneration(id: UUID, generation: Long, at: MachineOwner): ChunkMarkResult {
        val record = owners[id] ?: return ChunkMarkResult.UNKNOWN_ID
        if (record.owner != at) return ChunkMarkResult.NOT_OWNER
        val m = marks.getOrPut(id) { Marks() }
        val current = m.current
        return when {
            current == generation -> ChunkMarkResult.UNCHANGED
            current != null && generation < current -> {
                m.moveTo(generation)
                ChunkMarkResult.MOVED_BACK
            }
            else -> {
                m.moveTo(generation)
                schedulePrune(id, m)
                ChunkMarkResult.ADVANCED
            }
        }
    }

    /** keep の材料（印・完了した最新・書き込み待ちの世代と基準・今の基準）をメインスレッドで集め、掃除を列に入れる（#84） */
    private fun schedulePrune(id: UUID, m: Marks) {
        val current = m.current ?: return
        val k = setOfNotNull(current, m.previous, m.previous2, latest[id])
        val extra = HashSet<Long>()
        inFlight[id]?.forEach { (generation, base) ->
            extra += generation
            if (base != null) extra += base
        }
        bases[id]?.let { extra += it.generation }
        enqueue(id, 0, {
            pruneGenerations(id, current, k, extra)
            null
        }, { null })
    }

    /**
     * 残さない世代を消す（書き込みのスレッド。ADR 0081。#83 で差分の基準を、#84 で直下の世代と書き込み待ちを残すように広げた。ADR 0083・0084）:
     *
     * 1. 候補: K（今の印・前の印2つ・覚えている最新）に入る世代と、今の印より大きい世代。K の世代がディスクにないときは、
     *    それより小さいうちディスクにある最大の世代も候補にする（規則 10。読み込みで戻す世代）
     * 2. 候補の基準: [baseOf] に覚えていればそれ、なければファイルの頭（[deltaBaseReader]）から読んで覚える
     * 3. keep = 候補 ∪ 候補の差分が指す基準 ∪ [extra]（書き込み待ちの世代とその基準・今の基準。規則 11）
     * 4. keep に入らず、今の印より小さい世代を消す
     *
     * 2. で頭を読めなければ（[IOException]）、その回はこの UUID の世代を1つも消さない（基準を失わない側に倒す）。
     * 読めない世代は印が進めば K から外れ（最大 3 回）、候補でなくなるので掃除が再開する
     */
    private fun pruneGenerations(id: UUID, current: Long, k: Set<Long>, extra: Set<Long>) {
        val onDisk = generationsOnDisk(id)
        val present = onDisk.toHashSet()
        val candidates = LinkedHashSet<Long>()
        for (generation in onDisk) if (generation in k || generation > current) candidates += generation
        for (generation in k) {
            if (generation in present) continue
            onDisk.lastOrNull { it < generation }?.let { candidates += it }
        }
        val known = baseOf.getOrPut(id) { HashMap() }
        val keep = HashSet<Long>(extra)
        for (generation in candidates) {
            keep += generation
            val base = if (known.containsKey(generation)) {
                known[generation]
            } else {
                val read = try {
                    deltaBaseReader(generationFile(id, generation))
                } catch (e: IOException) {
                    LOG.log(
                        System.Logger.Level.WARNING,
                        "Cannot read the delta base of generation $generation of $id; keeping all generations this time: $e",
                    )
                    return
                }
                known[generation] = read?.generation
                read?.generation
            }
            if (base != null) keep += base
        }
        val gone = HashSet<Long>()
        for (generation in onDisk) {
            if (generation >= current || generation in keep) continue
            try {
                Files.deleteIfExists(generationFile(id, generation))
                gone += generation
            } catch (e: IOException) {
                LOG.log(System.Logger.Level.WARNING, "Failed to delete generation $generation of $id", e)
            }
        }
        // 消せた世代と、もうディスクにない世代の頭の写しを忘れる
        known.keys.removeAll { it in gone || it !in present }
    }

    /** [id] の世代の頭の写しを忘れる（テストが「覚えていない世代」を作る用。#83） */
    internal fun forgetBaseCache(id: UUID) {
        baseOf.remove(id)
    }

    /** 覚えている、[id] の世代の頭の写し（[baseOf] のキー。検査用。#83） */
    internal fun rememberedBaseGenerations(id: UUID): Set<Long> = baseOf[id]?.keys?.toSet().orEmpty()

    /** 今の基準（次の差分が指す全体の世代。なければ `null`。#83） */
    public fun currentBase(id: UUID): DeltaBase? = bases[id]

    /** ディスクにある [id] の世代ファイルの世代（小さい順。tmp は含めない） */
    public fun generationsOnDisk(id: UUID): List<Long> {
        val dir = directoryOf(id)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { paths ->
                val result = ArrayList<Long>()
                paths.forEach { path -> parseGeneration(path.fileName.toString())?.let(result::add) }
                result.sorted()
            }
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "Failed to list generations of $id", e)
            emptyList()
        }
    }

    /** 記録した今の印（なければ空） */
    public fun chunkGeneration(id: UUID): OptionalLong = marks[id]?.current.toOptional()

    /** 前の印（印が最後に変わる直前の値）。`claim`・引き取り・振り直しの直後は空 */
    public fun previousChunkGeneration(id: UUID): OptionalLong = marks[id]?.previous.toOptional()

    /** 残す前の印（[previousChunkGeneration] と、その1つ前。新しい順。なければ空の並び） */
    public fun previousChunkGenerations(id: UUID): List<Long> = marks[id]?.let { listOfNotNull(it.previous, it.previous2) }.orEmpty()

    /** その UUID で書けた（完了を受け取った）最新の世代（なければ空） */
    public fun latestGeneration(id: UUID): OptionalLong = latest[id].toOptional()

    /** 覚えている `owner.bin` の持ち主（ディスクは読まない） */
    public fun ownerOf(id: UUID): MachineOwner? = owners[id]?.owner

    /** 覚えている `owner.bin` の epoch（ディスクは読まない） */
    public fun epochOf(id: UUID): OptionalLong = owners[id]?.epoch.toOptional()

    // 初期化子は書いた順に走るので、ほかのプロパティ（先読みの表・錠・待ちの表など）をすべて作った後に、根を読み、先読みを始める
    // （先に始めると、先読みのスレッドが作る前の表に触り、後から走る初期化子が先読みの印を消す。#869）
    init {
        scanDirectories()
        if (prefetchOwners) {
            startOwnerPrefetch { task -> Thread(task, "KiBi-OwnerPrefetch").apply { isDaemon = true }.start() }
        }
    }

    override fun toString(): String = "MachineSaveStore($root)"

    private companion object {
        val LOG: System.Logger = System.getLogger("kibicomputers.persist")

        const val GENERATION_SUFFIX = ".bin"

        /** UUID の名前（`8-4-4-4-12`）の長さ */
        const val UUID_NAME_LENGTH = 36

        /** [write] が書き込みを待つ上限（実際には待ちきる） */
        const val WRITE_WAIT_MILLIS = 24L * 60 * 60 * 1000

        val GENERATION_NAME = Regex("(0|[1-9][0-9]{0,18})\\.bin")

        fun parseGeneration(name: String): Long? {
            if (!GENERATION_NAME.matches(name)) return null
            return name.removeSuffix(GENERATION_SUFFIX).toLongOrNull()?.takeIf { MachineIdentityLimits.isValidCounter(it) }
        }

        fun Long?.toOptional(): OptionalLong = if (this == null) OptionalLong.empty() else OptionalLong.of(this)

        fun crc32c(data: ByteArray): Int = CRC32C().also { it.update(data, 0, data.size) }.value.toInt()
    }
}
