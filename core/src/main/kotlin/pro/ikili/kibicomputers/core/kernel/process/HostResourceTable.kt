package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * Component Model のホストの resource（p2 の stream・descriptor・pollable など。#267。ADR 0267）の実体 1 つ。
 * 中身は ABI 層（`wasi:*`）が決める。カーネルが見るのは、持っているカーネルのオブジェクトの参照 [objectId] だけ。
 *
 * 保存の対象（[Process] の一部）なので、数値と明示的なデータだけを持つ（ラムダ・スレッド・ロックを持たない。ADR 0260）
 */
internal abstract class HostResourceEntry {
    /** 持っているカーネルのオブジェクトの参照（なければ 0）。エントリを外すとき・表を閉じるときに 1 つ返す */
    abstract val objectId: Int

    /** 保存の写し（状態を写した新しいインスタンス。変わらないエントリは `this` を返してよい） */
    abstract fun copyForSnapshot(): HostResourceEntry
}

/**
 * プロセスのホストの resource の表（rep → [HostResourceEntry]。#267。ADR 0267）。ゲストのハンドル表（`ResourceTable`）が持つのは
 * 「ハンドルの番号 → rep」の写しだけで、rep が指す実体はここに置く（ADR 0265）。fd 表（[Process.handles]）とは別にする
 * （ADR 0075・0265。p2 のハンドルは fd の番号を使わない）。
 *
 * カーネルロックの中で使う。プロセスの終わり（`ProcessRunner.finishLocked`）と、保存の写しの中で殺すとき
 * （`KernelStateCopy.killInCopy`）に [closeAll] で必ず閉じる。保存のバイト形式には入れない（コンポーネントのプロセスは #269 まで
 * 保存できず、写しの中で必ず殺される）。#269 では、rep とエントリを数値のまま書けば戻せる
 */
internal class HostResourceTable {
    private var entries: HashMap<Int, HostResourceEntry>? = null
    private var nextRep = 1

    /** `wasi:cli/environment.initial-cwd` が返す、起動したときの作業ディレクトリ（ABI 層が初めて使うときに入れる） */
    var initialCwd: VfsPath? = null

    /** 入っているエントリの数 */
    val size: Int get() = entries?.size ?: 0

    /** [entry] を入れて rep を返す（参照の持ち主は表になる）。[LIMIT] 個あれば [ResourceLimitException]（[entry] の参照は呼び出し側に残る） */
    @Throws(ResourceLimitException::class)
    fun add(entry: HostResourceEntry): Int {
        val map = entries ?: HashMap<Int, HostResourceEntry>().also { entries = it }
        if (map.size >= LIMIT) throw ResourceLimitException("ホストの resource の数が上限（$LIMIT）")
        var rep = nextRep
        while (rep <= 0 || map.containsKey(rep)) rep = if (rep == Int.MAX_VALUE || rep <= 0) 1 else rep + 1
        nextRep = if (rep == Int.MAX_VALUE) 1 else rep + 1
        map[rep] = entry
        return rep
    }

    operator fun get(rep: Int): HostResourceEntry? = entries?.get(rep)

    /** [rep] のエントリを外して返す（参照は返さない。返すのは呼び出し側）。なければ `null` */
    fun remove(rep: Int): HostResourceEntry? = entries?.remove(rep)

    /** 入っているエントリ（順は決めない） */
    fun forEach(action: (rep: Int, entry: HostResourceEntry) -> Unit) {
        entries?.forEach { (k, v) -> action(k, v) }
    }

    /** すべてのエントリを外し、持っている参照を返す（プロセスの終わり。オブジェクトが既にない参照は飛ばす） */
    fun closeAll(objects: ObjectTable, sink: WakeSink) {
        val map = entries ?: return
        entries = null
        for (e in map.values) {
            val id = e.objectId
            if (id != 0 && objects[id] != null) objects.release(id, sink)
        }
    }

    /** 保存の写し */
    fun copyForSnapshot(): HostResourceTable {
        val copy = HostResourceTable()
        copy.nextRep = nextRep
        copy.initialCwd = initialCwd
        entries?.let { m -> copy.entries = HashMap<Int, HostResourceEntry>(m.size * 2).also { c -> m.forEach { (k, v) -> c[k] = v.copyForSnapshot() } } }
        return copy
    }

    companion object {
        /** 1 プロセスのホストの resource の上限（仮の値。確定は #251。`CanonLimits.MAX_HANDLES` より小さくする） */
        const val LIMIT: Int = 4096
    }
}
