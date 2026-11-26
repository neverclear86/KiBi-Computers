package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import pro.ikili.kibicomputers.core.kernel.MachineClock
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import pro.ikili.kibicomputers.core.kernel.SoePositions
import pro.ikili.kibicomputers.core.kernel.stackHeadroomRounds
import pro.ikili.kibicomputers.core.persist.snapshot.TmpfsSectionCodec
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `/tmp`（[TmpFileSystem]）の木を変える操作の途中でスタックが尽きても、木と RAM の勘定が食い違わないことを、木の道全般で確かめる（#902）。
 *
 * `TmpFileSystemStackExhaustionTest`（#883）は、底に近い深さのすべてで、型を 1 回ずつ呼ぶ（#883 の 13 の型は根の直下の ASCII の名前だけ。
 * #902 で、入れ子・別のディレクトリ・日本語の名前・エラーの枝の 9 の型を足したので、今は 22 の型）。
 * こちらは `DiskFileSystemStackExhaustionTest`（#858）と同じ形で、1 つの `/tmp` に、回ごとに入れ子のディレクトリ（`/w/d<n>/...`）を組み、
 * スタックの小さい新しいスレッドで、詰め物の段数を変えて 1 つの操作を動かす（JIT をかけてから尽きる境目を探し、そこから先の幅を詰め物の
 * 1 段ずつ。境目は途中で探し直す）。型は、入れ子の作成・削除、いくつもの塊を持つファイルの削除、別のディレクトリへの名前の変更、
 * 中身のあるディレクトリの名前の変更、別のディレクトリにあるファイル・空のディレクトリの置き換え、何もしない名前の変更、
 * 名前が UTF-8 で何バイトにもなる（日本語の）名前（`String.toByteArray` と `TreeMap` の比較が、ASCII と別の道を通る。親のディレクトリには
 * ASCII と日本語の名前を混ぜておく）、エラーの枝（`INVAL`・`NOTEMPTY`・`ISDIR`・`NOTDIR`・`EXIST`・`NOENT`）。
 *
 * 組み方は本番と同じ（サーバー全体の [MemoryAccount] → マシンの [MemoryAccount] → [TmpFileSystem]。時計は `MachineClock.realtimeNanos` のラムダ）。
 * 毎回の後（尽きたときも、終わりまで動いたときも）に次を見る。
 *
 * - [TmpFileSystem.consistencyProblemsForTest]（根から辿った node と `nodes` の一致、塊が `size` の中、木から数え直した RAM と `usedBytes` の一致）
 * - `TMPFS` 節に書いて読み直せること（[TmpfsSectionCodec.read] が断らない）
 * - 勘定 3 つ（`fs.usedBytes`・マシン・サーバー全体）が等しいこと
 * - 尽きた回と、エラーで断られた回は、木（大きさ・時刻・塊の番号を含む）と `usedBytes` が始める前のまま
 *
 * その後に回の分を片付け、片付けた後も同じことと、`usedBytes` が回の前（共有の部分だけ）に戻ることを見る。最後に外して 3 つとも 0 に戻ること、
 * 尽きた回・終わりまで動いた回の両方があること、1 段ずつの掃引（境目を探す途中の大きな詰め物は除く）で、尽きた位置が操作より浅いところ
 * （詰め物の再帰・テストのコード）まで届いたこと（幅が操作の深さを覆う）と、最初の 1 段ずつの区間が境目をまたいだこと（境目の直後、
 * 操作のいちばん深い所が尽きる範囲を 1 段ずつ動かした）を確かめる。
 * ロックの途中で尽きたかは見ない。`TmpFileSystem` が使うのは `synchronized` と `MemoryAccount` の CAS だけで、`SoePositions` が
 * ロックに数える `java.util.concurrent.locks.` のフレームがこの道には出ない（見ても常に 0 で、確かめにならない）。モニターは例外でも放され、
 * 尽きた後の木と勘定は毎回の後の確かめが見る
 *
 * 入口の確かめ（`StackHeadroom.TMPFS_FRAMES`）がある今は、尽きる位置はほぼすべて確かめの中か、操作に入る前に落ちる。試験が窓を見つけられることは、
 * 手元で確かめを外して確かめた（日本語の名前の作成・削除と、入れ子のいくつもの塊の書き込みで食い違った。`StackHeadroom` の KDoc・ADR 0053）。
 * JIT が操作を畳むと、尽きうる位置は残った呼び出し（`TreeMap.put`・`String.encodeUTF8` など）だけになるので、窓を突く力は
 * 底に近い深さのすべてで呼ぶ `TmpFileSystemStackExhaustionTest` より弱い。こちらは、型の数（木の道全般）と、温めた後のフレームでの境目を受け持つ
 */
class TmpFileSystemTreeStackExhaustionTest {
    private enum class Op {
        /** 入れ子のディレクトリ（ASCII と日本語の名前が混ざる）にファイルを作る */
        CREATE_FILE_NESTED,

        /** 入れ子のディレクトリにディレクトリを作る */
        CREATE_DIRECTORY_NESTED,

        /** 日本語の名前のファイルを作る */
        CREATE_FILE_UTF16,

        /** 日本語の名前のディレクトリを作る */
        CREATE_DIRECTORY_UTF16,

        /** 入れ子の空のファイルに 3 塊分を 1 回で書く */
        WRITE_MULTI_NESTED,

        /** 入れ子の、3 塊のファイルを消す */
        REMOVE_FILE_CHUNKS,

        /** 日本語の名前の、塊のあるファイルを消す */
        REMOVE_FILE_UTF16,

        /** 入れ子の空のディレクトリを消す */
        REMOVE_DIRECTORY_NESTED,

        /** 日本語の名前の空のディレクトリを消す */
        REMOVE_DIRECTORY_UTF16,

        /** 入れ子のディレクトリの中で名前を変える（長さが変わる） */
        RENAME_SAME_DIR,

        /** 塊のあるファイルを、別のディレクトリへ移す */
        RENAME_ACROSS,

        /** 中身（塊のあるファイル 2 つと空のディレクトリ）のあるディレクトリを、別のディレクトリへ移す */
        RENAME_DIRECTORY_WITH_CONTENTS,

        /** 塊のあるファイルで、別のディレクトリにある塊のあるファイルを置き換える */
        RENAME_REPLACE_FILE_ACROSS,

        /** 中身のあるディレクトリで、別のディレクトリにある空のディレクトリを置き換える */
        RENAME_REPLACE_EMPTY_DIRECTORY_ACROSS,

        /** ASCII の名前から日本語の名前へ、別のディレクトリへ移す */
        RENAME_UTF16_ACROSS,

        /** 日本語の名前のファイルで、日本語の名前のファイルを置き換える */
        RENAME_REPLACE_UTF16,

        /** 同じ名前への名前の変更（何もしない） */
        RENAME_NOOP,

        /** ディレクトリを自分の下へ移す（`INVAL`） */
        RENAME_INTO_SELF,

        /** 中身のあるディレクトリを置き換える（`NOTEMPTY`） */
        RENAME_ONTO_NONEMPTY_DIRECTORY,

        /** ファイルでディレクトリを置き換える（`ISDIR`） */
        RENAME_FILE_ONTO_DIRECTORY,

        /** ディレクトリでファイルを置き換える（`NOTDIR`） */
        RENAME_DIRECTORY_ONTO_FILE,

        /** ディレクトリであることを求めて、ファイルの名前を変える（`NOTDIR`） */
        RENAME_REQUIRE_DIRECTORY,

        /** ないものの名前を変える（`NOENT`） */
        RENAME_MISSING,

        /** 既にある名前で作る（`EXIST`） */
        CREATE_EXISTING,

        /** ファイルの下に作る（`NOTDIR`） */
        CREATE_UNDER_FILE,

        /** ないファイルを消す（`NOENT`） */
        REMOVE_MISSING,

        /** ディレクトリを `removeFile` で消す（`ISDIR`） */
        REMOVE_FILE_ON_DIRECTORY,

        /** ファイルを `removeDirectory` で消す（`NOTDIR`） */
        REMOVE_DIRECTORY_ON_FILE,

        /** 中身のあるディレクトリを消す（`NOTEMPTY`） */
        REMOVE_NONEMPTY_DIRECTORY,
    }

    /** 本番と同じ順に組んだ `/tmp` */
    private class Rig {
        val server = MemoryAccount(Long.MAX_VALUE / 4)
        val machine = MemoryAccount(1L shl 40, server)
        val fs = TmpFileSystem(machine) { CLOCK.realtimeNanos() }
    }

    /** 1 回分の試行。[run] を尽きる位置をずらして動かす。[expected] はエラーで断られる型の errno（通る型は `null`） */
    private class Attempt(val run: () -> Unit, val expected: Errno?)

    private fun p(s: String) = VfsPath.parse(s)

    private fun TmpFileSystem.file(path: String, bytes: Int = 0): Long {
        val node = createFile(p(path))
        if (bytes > 0) write(node, 0, PAYLOAD, 0, bytes)
        return node
    }

    private fun TmpFileSystem.dir(vararg paths: String) = paths.forEach { createDirectory(p(it)) }

    /** 回 [n] の組を `/w/d<n>` の下に浅いところで作り、深いところで動かす操作を返す（パスも浅いところで解いておく） */
    private fun prepare(fs: TmpFileSystem, op: Op, n: Int): Attempt {
        val b = "/w/d$n"
        // 回ごとの親。ASCII と日本語の名前を混ぜ、`TreeMap` の比較が両方の道を通るようにする
        fs.dir(b, "$b/a", "$b/b", "$b/ディレクトリ")
        fs.file("$b/a/zz")
        fs.file("$b/a/日本語の名前")
        fun run(body: () -> Unit) = Attempt(body, null)
        fun fails(errno: Errno, body: () -> Unit) = Attempt(body, errno)
        return when (op) {
            Op.CREATE_FILE_NESTED -> p("$b/a/new").let { run { fs.createFile(it) } }
            Op.CREATE_DIRECTORY_NESTED -> p("$b/a/new").let { run { fs.createDirectory(it) } }
            Op.CREATE_FILE_UTF16 -> p("$b/a/新しいファイル").let { run { fs.createFile(it) } }
            Op.CREATE_DIRECTORY_UTF16 -> p("$b/ディレクトリ/新しいディレクトリ").let { run { fs.createDirectory(it) } }
            Op.WRITE_MULTI_NESTED -> fs.file("$b/a/data").let { node -> run { fs.write(node, 0, PAYLOAD, 0, PAYLOAD.size) } }
            Op.REMOVE_FILE_CHUNKS -> {
                fs.file("$b/a/data", PAYLOAD.size)
                p("$b/a/data").let { run { fs.removeFile(it) } }
            }
            Op.REMOVE_FILE_UTF16 -> {
                fs.file("$b/ディレクトリ/消すファイル", 10)
                p("$b/ディレクトリ/消すファイル").let { run { fs.removeFile(it) } }
            }
            Op.REMOVE_DIRECTORY_NESTED -> {
                fs.dir("$b/a/s", "$b/a/s/t")
                p("$b/a/s/t").let { run { fs.removeDirectory(it) } }
            }
            Op.REMOVE_DIRECTORY_UTF16 -> {
                fs.dir("$b/ディレクトリ/消すディレクトリ")
                p("$b/ディレクトリ/消すディレクトリ").let { run { fs.removeDirectory(it) } }
            }
            Op.RENAME_SAME_DIR -> {
                fs.file("$b/a/f", 10)
                rename(fs, "$b/a/f", "$b/a/renamed-to-a-longer-name")
            }
            Op.RENAME_ACROSS -> {
                fs.file("$b/a/f", 10)
                rename(fs, "$b/a/f", "$b/b/g")
            }
            Op.RENAME_DIRECTORY_WITH_CONTENTS -> {
                fs.dir("$b/a/s", "$b/a/s/empty")
                fs.file("$b/a/s/x", 10)
                fs.file("$b/a/s/y", TmpFileSystem.CHUNK_BYTES + 1)
                rename(fs, "$b/a/s", "$b/b/moved", requireDirectory = true)
            }
            Op.RENAME_REPLACE_FILE_ACROSS -> {
                fs.file("$b/a/f", 10)
                fs.file("$b/b/g", 2 * TmpFileSystem.CHUNK_BYTES)
                rename(fs, "$b/a/f", "$b/b/g")
            }
            Op.RENAME_REPLACE_EMPTY_DIRECTORY_ACROSS -> {
                fs.dir("$b/a/s", "$b/b/t")
                fs.file("$b/a/s/x", 10)
                rename(fs, "$b/a/s", "$b/b/t", requireDirectory = true)
            }
            Op.RENAME_UTF16_ACROSS -> {
                fs.file("$b/a/f", 10)
                rename(fs, "$b/a/f", "$b/ディレクトリ/日本語のファイル")
            }
            Op.RENAME_REPLACE_UTF16 -> {
                fs.file("$b/ディレクトリ/元のファイル", 10)
                fs.file("$b/a/置き換えられるファイル", 10)
                rename(fs, "$b/ディレクトリ/元のファイル", "$b/a/置き換えられるファイル")
            }
            Op.RENAME_NOOP -> {
                fs.file("$b/a/f", 10)
                rename(fs, "$b/a/f", "$b/a/f")
            }
            Op.RENAME_INTO_SELF -> rename(fs, "$b/a", "$b/a/sub", errno = Errno.INVAL)
            Op.RENAME_ONTO_NONEMPTY_DIRECTORY -> rename(fs, "$b/b", "$b/a", errno = Errno.NOTEMPTY)
            Op.RENAME_FILE_ONTO_DIRECTORY -> rename(fs, "$b/a/zz", "$b/b", errno = Errno.ISDIR)
            Op.RENAME_DIRECTORY_ONTO_FILE -> rename(fs, "$b/b", "$b/a/zz", errno = Errno.NOTDIR)
            Op.RENAME_REQUIRE_DIRECTORY -> rename(fs, "$b/a/zz", "$b/a/yy", requireDirectory = true, errno = Errno.NOTDIR)
            Op.RENAME_MISSING -> rename(fs, "$b/a/missing", "$b/b/missing", errno = Errno.NOENT)
            Op.CREATE_EXISTING -> p("$b/a/日本語の名前").let { fails(Errno.EXIST) { fs.createFile(it) } }
            Op.CREATE_UNDER_FILE -> p("$b/a/zz/new").let { fails(Errno.NOTDIR) { fs.createDirectory(it) } }
            Op.REMOVE_MISSING -> p("$b/a/missing").let { fails(Errno.NOENT) { fs.removeFile(it) } }
            Op.REMOVE_FILE_ON_DIRECTORY -> p("$b/b").let { fails(Errno.ISDIR) { fs.removeFile(it) } }
            Op.REMOVE_DIRECTORY_ON_FILE -> p("$b/a/zz").let { fails(Errno.NOTDIR) { fs.removeDirectory(it) } }
            Op.REMOVE_NONEMPTY_DIRECTORY -> p("$b/a").let { fails(Errno.NOTEMPTY) { fs.removeDirectory(it) } }
        }
    }

    private fun rename(fs: TmpFileSystem, from: String, to: String, requireDirectory: Boolean = false, errno: Errno? = null): Attempt {
        val f = p(from)
        val t = p(to)
        return Attempt({ fs.rename(f, t, requireDirectory) }, errno)
    }

    /** [path] の下をすべて消す（尽きた後の `/tmp` が、ふつうに使えることも見る） */
    private fun removeTree(fs: TmpFileSystem, path: VfsPath) {
        val stat = fs.stat(path)
        if (stat.type == FileType.DIRECTORY) {
            for (entry in fs.readDirectory(stat.node)) removeTree(fs, path.child(entry.name))
            fs.removeDirectory(path)
        } else {
            fs.removeFile(path)
        }
    }

    /** 食い違いの説明の一覧（なければ空） */
    private fun problems(rig: Rig): List<String> {
        val fs = rig.fs
        val problems = ArrayList(fs.consistencyProblemsForTest())
        if (fs.usedBytes != rig.machine.usedBytes || fs.usedBytes != rig.server.usedBytes) {
            problems += "勘定がずれた（/tmp ${fs.usedBytes}、マシン ${rig.machine.usedBytes}、サーバー ${rig.server.usedBytes}）"
        }
        try {
            TmpfsSectionCodec.read(TmpfsSectionCodec.write(1, fs.snapshot()!!))
        } catch (e: Exception) {
            problems += "TMPFS 節を読み直せない（$e）"
        }
        return problems
    }

    private fun padThen(pad: Int, body: () -> Unit): Int {
        if (pad <= 0) {
            body()
            return 0
        }
        return padThen(pad - 1, body) + 1
    }

    /** スタックの小さい新しいスレッドで、詰め物を [pad] 段積んでから [body] を動かす。投げたもの（なければ `null`）を返す */
    private fun runOnSmallStack(pad: Int, body: () -> Unit): Throwable? {
        var result: Throwable? = null
        val t = Thread(null, {
            try {
                padThen(pad, body)
            } catch (e: Throwable) {
                result = e
            }
        }, "TmpFileSystemTreeStackExhaustionTest", STACK_BYTES)
        t.start()
        t.join(60_000)
        check(!t.isAlive) { "操作が終わらない: ${t.stackTrace.take(12).joinToString(" <- ")}" }
        return result
    }

    /**
     * 詰め物の段数を変えて [attempt]（尽きたら `true`）を動かす（`DiskFileSystemStackExhaustionTest.walkEdge` と同じ形）。JIT をかけてから、
     * 尽きる境目を探し、境目の少し手前から先（詰め物が多く、残りのスタックが少ない側）を [WIDTH] 段だけ 1 段ずつ動かす（`attempt` の
     * `swept` は、この 1 段ずつの動かし方のとき `true`）。最初の区間が境目をまたがなかった（全部尽きた、または尽きなかった回が [MAX_LEAD] 回より多い）ときは、
     * 探し直した境目からその区間をやり直す（`DiskFileSystemStackExhaustionTest.walkEdge` にはこのやり直しがない）。やり直す回数を使い切っても、
     * 区間が境目をまたいではいれば（尽きた回も尽きなかった回もある）、そのまま進む（尽きなかった回が多いだけ。またがないときだけ失敗する）。
     * 区間ごとに、1 段ずつ動かして尽きた回を [commitSwept] に渡す（`true` は数える、`false` はやり直しで捨てた区間）。最後の境目を返す
     */
    private fun walkEdge(attempt: (pad: Int, swept: Boolean) -> Boolean, commitSwept: (keep: Boolean) -> Unit): Int {
        repeat(stackHeadroomRounds(WARMUP)) { attempt(0, false) }

        fun findEdge(): Int {
            var lo = 0
            var hi = 256
            while (!attempt(hi, false)) {
                lo = hi
                hi *= 2
                check(hi < (1 shl 20)) { "詰め物を増やしても尽きない" }
            }
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (attempt(mid, false)) hi = mid else lo = mid
            }
            return hi
        }

        // 幅は回数の割合（`kibi.stackHeadroom.roundsScale`。インタプリタで回す opt-in のタスクは 1/4）で縮めない（#903。`DiskFileSystemStackExhaustionTest.walkEdge` と
        // 同じ）。縮めると「境目から先を 1 段ずつ全部」でなくなり、`-Xint` では入口の確かめ（`probe` もインタプリタの大きいフレーム）の幅より狭くなって、
        // 尽きる位置が確かめの外（[SoePositions.Position.OTHER]）まで届かない。減らすのは JIT をかける回数（[WARMUP]）だけ
        val width = WIDTH
        var edge = findEdge()
        var done = 0
        // 最初の区間（境目の直後。操作のいちばん深い所が尽きる範囲）を、境目をまたいで掃いたか（尽きなかった回が少なく、尽きた回もある）。慣らしは詰め物 0 だけなので、
        // 最初の数回の境目は、詰め物の再帰が温まる前の古い値（低い）になることがある。そのときは区間の全部が尽きず、境目の直後を
        // 1 段ずつ動かさないまま先へ進むので、探し直した境目からやり直す
        var retries = 0
        while (done < width) {
            val batch = minOf(width - done, REFIND_EVERY)
            var crashed = 0
            for (k in 0 until batch) if (attempt(maxOf(0, edge - 8 + done + k), true)) crashed++
            edge = findEdge()
            val straddled = crashed in 1 until batch
            // 境目の手前（尽きない回）が [MAX_LEAD] 回より多いと、境目の直後を掃いた幅が足りない（区間の終わりの数段でだけ尽きた）。
            // やり直す回数を使い切ったら、境目をまたいではいる限り（[straddled]）進む（またいだ区間でも手前の回が [MAX_LEAD] を超えることは、マシンが混むとある）
            if (done == 0 && (!straddled || batch - crashed > MAX_LEAD) && retries < EDGE_RETRIES) {
                retries++
                commitSwept(false)
                continue
            }
            if (done == 0) {
                check(straddled) { "最初の 1 段ずつの区間が、境目をまたがない（$EDGE_RETRIES 回探し直しても。尽きた $crashed / $batch 回）" }
                sweptAcrossEdge = true
            }
            commitSwept(true)
            done += batch
        }
        return edge
    }

    /** 最初の 1 段ずつの区間が、尽きなかった回と尽きた回の両方を含んだ（境目の直後を掃いた）か。[walkEdge] が立てる */
    private var sweptAcrossEdge = false

    private fun sweep(op: Op) = timeout(Duration.ofSeconds(TIMEOUT_SECONDS)) {
        val rig = Rig()
        val fs = rig.fs
        // 回をまたいで残す共有の部分（ASCII と日本語の名前が混ざった親）
        fs.dir("/w", "/w/共有")
        fs.file("/w/aaa", 10)
        fs.file("/w/日本語", 10)
        val baseline = fs.usedBytes
        val tally = SoePositions()

        // 1 段ずつの掃引（境目から [WIDTH] 段）の分だけ。境目を探す途中の大きな詰め物は、境目を大きく越えて、いつも詰め物の中で尽きる。
        // 区間の途中の分は [pendingSwept] に置き、[walkEdge] が区間を採るか（`commitSwept(true)`）、やり直しで捨てるか（`false`）を決める
        // （捨てた区間の尽きた回は、境目が高すぎて全部が詰め物の中で尽きているので、数えると [SoePositions.Position.OTHER] の検査が空振りする）
        val swept = SoePositions()
        val pendingSwept = ArrayList<StackOverflowError>()
        var counter = 0
        var completed = 0
        var refused = 0

        /** [pad] 段の詰め物で 1 回動かし、尽きたかを返す。毎回、状態を確かめて片付ける */
        fun attempt(pad: Int, sweptStep: Boolean): Boolean {
            val n = ++counter
            val a = prepare(fs, op, n)
            val beforeUsed = fs.usedBytes
            val beforePrint = fingerprint(fs)
            val error = runOnSmallStack(pad, a.run)
            val soe = error as? StackOverflowError
            val what = "$op、pad $pad、${if (soe != null) "尽きた" else "終わりまで動いた"}"
            when {
                soe != null -> {}
                error is FsException && error.errno == a.expected -> refused++
                error != null -> throw AssertionError("尽きる以外の失敗（$what。期待した errno ${a.expected}）", error)
                a.expected != null -> fail("$what: ${a.expected} で断られるはずが通った")
                else -> completed++
            }
            val found = problems(rig).toMutableList()
            if ((soe != null || error != null || op == Op.RENAME_NOOP) && (fs.usedBytes != beforeUsed || fingerprint(fs) != beforePrint)) {
                found += "断られた（何もしない）のに変わった（usedBytes $beforeUsed → ${fs.usedBytes}）"
            }
            if (found.isNotEmpty()) fail("尽きた後に /tmp の木と勘定が食い違った（$what）: $found\nSOE: ${soe?.stackTrace?.take(14)?.joinToString(" <- ")}")
            if (soe != null) {
                tally.record(soe)
                if (sweptStep) pendingSwept += soe
            }
            removeTree(fs, p("/w/d$n"))
            val after = problems(rig)
            if (after.isNotEmpty() || fs.usedBytes != baseline) fail("片付けた後に食い違った（$what）: $after、usedBytes ${fs.usedBytes}（回の前 $baseline）")
            return soe != null
        }

        val edge = walkEdge(::attempt) { keep ->
            if (keep) pendingSwept.forEach { swept.record(it) }
            pendingSwept.clear()
        }
        println(
            "#902 tmpfs $op: 境目 $edge、$counter 回動かし、終わりまで動いた $completed 回、エラーで断られた $refused 回、尽きた位置: ${tally.describe()}、" +
                "1 段ずつの掃引: ${swept.describe()}",
        )
        assertTrue(tally.crashed.get() > 0, "1 度も尽きなかった")
        assertTrue(completed + refused > 0, "1 度も終わりまで動かなかった")
        // 深い側: 最初の 1 段ずつの区間が境目をまたいだ（操作のいちばん深い所が尽きる、境目の直後を 1 段ずつ動かした）
        assertTrue(sweptAcrossEdge, "1 段ずつの掃引が、境目の直後を通っていない")
        // 幅が操作の深さを覆う（1 段ずつの掃引で、尽きる位置が、操作より浅いテストのコード（詰め物）まで届いた）
        assertTrue(
            swept.count(SoePositions.Position.OTHER) > 0,
            "1 段ずつの掃引で、操作より浅い所まで尽きる位置が届いていない（幅が足りない）: ${swept.describe()}",
        )
        fs.onUnmounted()
        assertEquals(emptyList<String>(), fs.consistencyProblemsForTest())
        assertEquals(0L, fs.usedBytes, "外した後に /tmp の使用量が残った")
        assertEquals(0L, rig.machine.usedBytes, "外した後にマシンの勘定が残った")
        assertEquals(0L, rig.server.usedBytes, "外した後にサーバー全体の勘定が残った")
    }

    @Test
    fun `入れ子のディレクトリにファイルを作る途中で尽きても、木と勘定は食い違わない`() = sweep(Op.CREATE_FILE_NESTED)

    @Test
    fun `入れ子のディレクトリにディレクトリを作る途中で尽きても、木と勘定は食い違わない`() = sweep(Op.CREATE_DIRECTORY_NESTED)

    @Test
    fun `日本語の名前のファイルを作る途中で尽きても、木と勘定は食い違わない`() = sweep(Op.CREATE_FILE_UTF16)

    @Test
    fun `日本語の名前のディレクトリを作る途中で尽きても、木と勘定は食い違わない`() = sweep(Op.CREATE_DIRECTORY_UTF16)

    @Test
    fun `入れ子のファイルにいくつもの塊を書く途中で尽きても、木と勘定は食い違わない`() = sweep(Op.WRITE_MULTI_NESTED)

    @Test
    fun `いくつもの塊を持つ入れ子のファイルを消す途中で尽きても、木と勘定は食い違わない`() = sweep(Op.REMOVE_FILE_CHUNKS)

    @Test
    fun `日本語の名前のファイルを消す途中で尽きても、木と勘定は食い違わない`() = sweep(Op.REMOVE_FILE_UTF16)

    @Test
    fun `入れ子の空のディレクトリを消す途中で尽きても、木と勘定は食い違わない`() = sweep(Op.REMOVE_DIRECTORY_NESTED)

    @Test
    fun `日本語の名前のディレクトリを消す途中で尽きても、木と勘定は食い違わない`() = sweep(Op.REMOVE_DIRECTORY_UTF16)

    @Test
    fun `入れ子のディレクトリの中で名前を変える途中で尽きても、木と勘定は食い違わない`() = sweep(Op.RENAME_SAME_DIR)

    @Test
    fun `別のディレクトリへ移す途中で尽きても、木と勘定は食い違わない`() = sweep(Op.RENAME_ACROSS)

    @Test
    fun `中身のあるディレクトリを移す途中で尽きても、木と勘定は食い違わない`() = sweep(Op.RENAME_DIRECTORY_WITH_CONTENTS)

    @Test
    fun `別のディレクトリのファイルを置き換える途中で尽きても、木と勘定は食い違わない`() = sweep(Op.RENAME_REPLACE_FILE_ACROSS)

    @Test
    fun `別のディレクトリの空のディレクトリを置き換える途中で尽きても、木と勘定は食い違わない`() = sweep(Op.RENAME_REPLACE_EMPTY_DIRECTORY_ACROSS)

    @Test
    fun `日本語の名前へ別のディレクトリに移す途中で尽きても、木と勘定は食い違わない`() = sweep(Op.RENAME_UTF16_ACROSS)

    @Test
    fun `日本語の名前のファイルを置き換える途中で尽きても、木と勘定は食い違わない`() = sweep(Op.RENAME_REPLACE_UTF16)

    @Test
    fun `同じ名前への名前の変更の途中で尽きても、何も変わらない`() = sweep(Op.RENAME_NOOP)

    @Test
    fun `ディレクトリを自分の下へ移すINVALの途中で尽きても、何も変わらない`() = sweep(Op.RENAME_INTO_SELF)

    @Test
    fun `中身のあるディレクトリを置き換えるNOTEMPTYの途中で尽きても、何も変わらない`() = sweep(Op.RENAME_ONTO_NONEMPTY_DIRECTORY)

    @Test
    fun `ファイルでディレクトリを置き換えるISDIRの途中で尽きても、何も変わらない`() = sweep(Op.RENAME_FILE_ONTO_DIRECTORY)

    @Test
    fun `ディレクトリでファイルを置き換えるNOTDIRの途中で尽きても、何も変わらない`() = sweep(Op.RENAME_DIRECTORY_ONTO_FILE)

    @Test
    fun `ディレクトリを求めてファイルの名前を変えるNOTDIRの途中で尽きても、何も変わらない`() = sweep(Op.RENAME_REQUIRE_DIRECTORY)

    @Test
    fun `ないものの名前を変えるNOENTの途中で尽きても、何も変わらない`() = sweep(Op.RENAME_MISSING)

    @Test
    fun `既にある名前で作るEXISTの途中で尽きても、何も変わらない`() = sweep(Op.CREATE_EXISTING)

    @Test
    fun `ファイルの下に作るNOTDIRの途中で尽きても、何も変わらない`() = sweep(Op.CREATE_UNDER_FILE)

    @Test
    fun `ないファイルを消すNOENTの途中で尽きても、何も変わらない`() = sweep(Op.REMOVE_MISSING)

    @Test
    fun `ディレクトリをファイルとして消すISDIRの途中で尽きても、何も変わらない`() = sweep(Op.REMOVE_FILE_ON_DIRECTORY)

    @Test
    fun `ファイルをディレクトリとして消すNOTDIRの途中で尽きても、何も変わらない`() = sweep(Op.REMOVE_DIRECTORY_ON_FILE)

    @Test
    fun `中身のあるディレクトリを消すNOTEMPTYの途中で尽きても、何も変わらない`() = sweep(Op.REMOVE_NONEMPTY_DIRECTORY)

    private companion object {
        /** 1 回分のスレッドのスタック（`DiskFileSystemStackExhaustionTest` と同じ） */
        const val STACK_BYTES = 192L * 1024

        /** 境目を探す前に動かす回数（JIT をかける） */
        const val WARMUP = 150

        /**
         * 境目から、詰め物をずらして動かす幅（段数）。入口の確かめと操作が使うスタックの幅より広くする。回数の割合（`roundsScale`）では縮めない
         * （`-Xint` では確かめの `probe` のフレームが大きく、縮めると確かめの外まで届かない）
         */
        const val WIDTH = 600

        /** 境目を探し直す間隔（回） */
        const val REFIND_EVERY = 120

        /** 最初の 1 段ずつの区間が境目をまたがなかったときに、探し直してやり直す回数の上限 */
        const val EDGE_RETRIES = 3

        /**
         * 最初の 1 段ずつの区間で、尽きない回（境目の手前）の、やり直さずに済む上限。境目が合っていれば区間は境目の 8 段手前から始まるので 8 回前後だが、
         * ふつうに出る幅は 2〜40 回で、混んだマシンや JDK 21 ではもっと大きい（境目は 1 段の精度では決まらず、JIT の段階で動く）。区間の長さ
         * [REFIND_EVERY]（120）に対し、境目の直後を 20 段以上は掃いている 100 まで許す。これより多いと、探した境目が古い（低い）ので、探し直してやり直す
         * （[EDGE_RETRIES] 回やり直しても超えるなら、境目をまたいでいる限りそのまま進む）
         */
        const val MAX_LEAD = 100

        val TIMEOUT_SECONDS: Long = System.getProperty("kibi.stackHeadroom.timeoutSeconds")?.toLongOrNull() ?: 120

        /** 本番の `/tmp` の時計（`environment.clock.realtimeNanos()`）と同じ深さ */
        val CLOCK: MachineClock = MachineClock.SYSTEM

        val PAYLOAD = ByteArray(3 * TmpFileSystem.CHUNK_BYTES) { (it % 251).toByte() }

        /** 木の形と大きさ・時刻・塊の番号（中身は除く）。断られた操作が何も変えていないことを比べる */
        fun fingerprint(fs: TmpFileSystem): String = fs.snapshot()!!.nodes.joinToString("|") { n ->
            "${n.id},${n.type},${n.size},${n.modifiedNanos},${n.changedNanos},${n.children},${n.chunks?.map { it.first }}"
        }
    }
}
