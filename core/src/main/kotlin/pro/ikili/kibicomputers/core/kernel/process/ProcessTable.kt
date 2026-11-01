package pro.ikili.kibicomputers.core.kernel.process

/**
 * pid → [Process] の表（保存の対象）。pid は 1 から割り当て、使い終わった番号はすぐには使い回さない。
 *
 * 終わったプロセス（ゾンビ）も、刈り取る（#46）まで表に残る。上限 [limit] は終わっていないプロセスの数に掛ける。
 */
internal class ProcessTable(val limit: Int) {
    private val table = LinkedHashMap<Int, Process>()

    /** 次に割り当てる pid */
    var nextPid: Int = 1
        private set

    operator fun get(pid: Int): Process? = table[pid]

    operator fun contains(pid: Int): Boolean = table.containsKey(pid)

    /** 表にあるすべてのプロセス（pid の割り当ての順） */
    fun all(): Collection<Process> = table.values

    /** 親が [pid] のプロセスの pid（割り当ての順。ゾンビを含む） */
    fun childrenOf(pid: Int): List<Int> = table.values.filter { it.parentPid == pid }.map { it.pid }

    /** 終わっていないプロセスの数 */
    fun liveCount(): Int = table.values.count { !it.state.isTerminal }

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

    /** 刈り取る（#46） */
    fun remove(pid: Int): Process? = table.remove(pid)

    /** 終わったプロセス（ゾンビ）をすべて外し、pid を 1 から振り直す（起動のとき。#49）。終わっていないプロセスがあれば [IllegalStateException] */
    fun reset() {
        check(liveCount() == 0) { "終わっていないプロセスがある" }
        table.clear()
        nextPid = 1
    }
}
