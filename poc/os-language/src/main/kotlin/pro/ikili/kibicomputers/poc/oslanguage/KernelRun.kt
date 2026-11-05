package pro.ikili.kibicomputers.poc.oslanguage

import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineScheduler
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.kernel.SchedulerInternals
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.romChunks
import pro.ikili.kibicomputers.core.runtime.endive.writtenChunks
import java.io.ByteArrayOutputStream
import kotlin.concurrent.withLock

/**
 * 本物のカーネル（スケジューラ・`Machine.exec`・待機点への変換・RAM の数え方）の上でプログラムを動かす（#32）。
 *
 * `Machine.exec` は、読み込むときに燃料の計測コードの挿入と待機点への変換をかける（#38・#329・#339）。
 * [asyncify] が `false` なら変換をかけず、ブロッキング型（`BLOCKING`。待つ間スレッドを持つ）で動かす（変換できないモジュールの比較用）。
 */
internal class KernelRun(asyncify: Boolean = true) : AutoCloseable {
    private val scheduler = MachineScheduler.create(
        SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).build(),
        SchedulerInternals(asyncifyExecutables = asyncify),
    )

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    /** 終わったプロセスの結果。[kind] は待機点型（`WAIT_POINT`）か、[fuelYield] は燃料切れも待ち点にできたか */
    class Exited(val console: String, val state: ProcessState, val exitCode: Int?, val kind: ProcessKind, val fuelYield: Boolean)

    /** 入力を待っているときの様子。RAM は書き換えた 4 KiB の区画（#407）、ROM はデータセグメントの区画（数えない） */
    class Waiting(
        val console: String,
        val kind: ProcessKind,
        val fuelYield: Boolean,
        val writtenChunks: Int,
        val romChunks: Int,
        val declaredPages: Int,
        val machineUsedBytes: Long,
        val afterInput: String,
    )

    private fun machine(host: MachineHost, program: ByteArray, files: Map<String, ByteArray>): Machine {
        val m = scheduler.createMachine(host, MachineConfig.builder().fuelPerTick(10_000_000).memoryBytes(16L * 1024 * 1024).build())
        m.lock.withLock {
            m.vfs.mount(VfsPath.ROOT, TmpFileSystem(m.memoryAccount) { 0L }, readOnly = false, source = "poc:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            m.vfs.createDirectory(VfsPath.parse("/d"))
            put(m, "/bin/prog", program)
            for ((name, data) in files) put(m, "/d/$name", data)
        }
        return m
    }

    private fun put(m: Machine, path: String, data: ByteArray) {
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, data, 0, data.size)
    }

    private fun tickUntil(timeoutMillis: Long, what: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("時間内に終わらない: ${what()}")
            scheduler.tick()
            Thread.sleep(1)
        }
    }

    private fun spawn(m: Machine, args: List<String>): Int =
        m.exec("/bin/prog", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prog") + args, fds = SpawnOptions.ROOT_PREOPEN))

    /** [program] を [args] で動かし、終わるまで待つ。ファイルは `/d/<名前>` に置く */
    fun run(program: ByteArray, args: List<String>, files: Map<String, ByteArray> = emptyMap()): Exited {
        val host = ConsoleHost()
        val m = machine(host, program, files)
        try {
            val pid = spawn(m, args)
            val rt = m.runtime(pid)!!
            tickUntil(60_000, { host.text() }) { m.processState(pid)!!.isTerminal }
            // 終わった後の出力が配られるまで回す
            repeat(5) {
                scheduler.tick()
                Thread.sleep(1)
            }
            val exitCode = m.lock.withLock { m.kernel.processes[pid]!!.exitCode }
            return Exited(host.text(), m.processState(pid)!!, exitCode, rt.driver.kind, rt.program.fuelYield)
        } finally {
            m.stop()
            m.awaitTermination(10_000)
        }
    }

    /** [program] を引数なしで動かし、`> ` を出して入力を待つまで進めてから測り、[input] を渡した後の出力も返す */
    fun runUntilInput(program: ByteArray, input: String): Waiting {
        val host = ConsoleHost()
        val m = machine(host, program, emptyMap())
        try {
            val pid = spawn(m, emptyList())
            val rt = m.runtime(pid)!!
            tickUntil(60_000, { host.text() }) { host.text().endsWith("> ") && m.processState(pid) == ProcessState.WAITING_EVENT }
            val memory = rt.instances.mapNotNull { it.memory }.single()
            val written = writtenChunks(memory)
            val rom = romChunks(memory)
            val used = m.lock.withLock { m.memoryAccount.usedBytes }
            val before = host.text()
            m.offerConsoleInput(input.toByteArray(Charsets.UTF_8))
            val prompts = before.split("> ").size
            tickUntil(60_000, { host.text() }) { host.text().split("> ").size > prompts && m.processState(pid) == ProcessState.WAITING_EVENT }
            return Waiting(before, rt.driver.kind, rt.program.fuelYield, written, rom, memory.pages, used, host.text().removePrefix(before))
        } finally {
            m.stop()
            m.awaitTermination(10_000)
        }
    }

    override fun close() {
        scheduler.close()
        scheduler.awaitTermination(10_000)
    }
}
