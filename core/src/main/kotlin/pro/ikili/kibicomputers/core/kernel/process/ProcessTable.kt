package pro.ikili.kibicomputers.core.kernel.process

/**
 * pid → [Process] の表（保存の対象）。pid は 1 から割り当て、使い終わった番号はすぐには使い回さない。
 *
 * 終わったプロセス（ゾンビ）も、刈り取るまで表に残る（#46、ADR 0046）。上限 [limit] は、終わっていないプロセスと、
 * 親のいるゾンビの合計に掛ける（[limitedCount]）。親のないゾンビは誰も刈り取らないので、[orphanZombies] に終わった順に並べ、
 * 表が大きくなったらカーネルが古い順に刈り取る。
 */
internal class ProcessTable(val limit: Int) {
    private val table = LinkedHashMap<Int, Process>()

    /** 親のないゾンビの pid（終わった順。表から作り直せる） */
    private val orphans = LinkedHashSet<Int>()

    /** 次に割り当てる pid */
    var nextPid: Int = 1
        private set

    operator fun get(pid: Int): Process? = table[pid]

    operator fun contains(pid: Int): Boolean = table.containsKey(pid)

    /** 表にあるプロセスの数（ゾンビを含む） */
    val size: Int get() = table.size

    /** 表にあるすべてのプロセス（pid の割り当ての順） */
    fun all(): Collection<Process> = table.values

    /** 親が [pid] のプロセスの pid（割り当ての順。ゾンビを含む） */
    fun childrenOf(pid: Int): List<Int> = table.values.filter { it.parentPid == pid }.map { it.pid }

    /** 終わっていないプロセスの数 */
    fun liveCount(): Int = table.values.count { !it.state.isTerminal }

    /** 上限に数えるプロセスの数（終わっていないプロセス＋親のいるゾンビ） */
    fun limitedCount(): Int = table.values.count { !it.state.isTerminal || it.parentPid != 0 }

    /** 親のないゾンビの pid（終わった順） */
    val orphanZombies: Collection<Int> get() = orphans

    /** [pid]（親のないゾンビ）を、親のないゾンビの並びの最後に足す */
    fun addOrphanZombie(pid: Int) {
        orphans.add(pid)
    }

    /**
     * テスト専用。本体のコードから呼ばない。次に割り当てる pid を [pid] にする（pid の使い回しを作るため。#74）。
     * [pid] が使われていれば、[allocatePid] はそれを飛ばして次へ進む。範囲外（1 未満）なら [IllegalArgumentException]
     */
    internal fun setNextPidForTest(pid: Int) {
        require(pid in 1..Int.MAX_VALUE) { "pid は 1 以上（$pid）" }
        nextPid = pid
    }

    /** 空いている pid を割り当てる */
    fun allocatePid(): Int {
        while (true) {
            val pid = nextPid
            nextPid = if (nextPid == Int.MAX_VALUE) 1 else nextPid + 1
            if (pid !in table) return pid
        }
    }

    fun add(process: Process) {
        require(process.pid !in table) { "pid ${process.pid} は使われている" }
        table[process.pid] = process
    }

    /** 表から外す（刈り取り。`ProcessObject` の後片付けは呼び出し側） */
    fun remove(pid: Int): Process? {
        orphans.remove(pid)
        return table.remove(pid)
    }

    /** プロセスごとに写した別の表（保存の写し。`nextPid`・親のないゾンビの並びも写す。#81） */
    fun copyForSnapshot(): ProcessTable = ProcessTable(limit).also { copy ->
        for (process in table.values) copy.table[process.pid] = process.copyForSnapshot()
        copy.orphans.addAll(orphans)
        copy.nextPid = nextPid
    }

    /** 保存データから読んだ値を入れる（#82・#85。空の表に）。入れる前にすべて確かめ（[validateRestore]）、確かめに落ちたら表を変えない */
    fun restore(processes: Collection<Process>, orphans: Collection<Int>, nextPid: Int) {
        check(table.isEmpty()) { "空の表にだけ戻す" }
        validateRestore(processes, orphans, nextPid)
        for (p in processes) table[p.pid] = p
        this.orphans.addAll(orphans)
        this.nextPid = nextPid
    }

    companion object {
        /**
         * [restore] に渡せるか確かめる（#85。表は変えない）: pid が重ならない、親のないゾンビが表にあって終わっていて親が 0、次の pid が 1 以上。
         * 合わなければ [IllegalArgumentException]
         */
        fun validateRestore(processes: Collection<Process>, orphans: Collection<Int>, nextPid: Int) {
            val byPid = HashMap<Int, Process>()
            for (p in processes) require(byPid.put(p.pid, p) == null) { "pid ${p.pid} は使われている" }
            for (pid in orphans) {
                val p = byPid[pid]
                require(p != null && p.state.isTerminal && p.parentPid == 0) { "親のないゾンビでない pid $pid" }
            }
            require(nextPid >= 1) { "次の pid は 1 以上" }
        }
    }

    /** 終わったプロセス（ゾンビ）をすべて外し、pid を 1 から振り直す（起動のとき。#49）。終わっていないプロセスがあれば [IllegalStateException] */
    fun reset() {
        check(liveCount() == 0) { "終わっていないプロセスがある" }
        table.clear()
        orphans.clear()
        nextPid = 1
    }
}
