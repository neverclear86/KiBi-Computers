package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.kernel.event.EventHub
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleTable
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ProcessTable
import pro.ikili.kibicomputers.core.kernel.time.ClockSnapshot
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.vfs.MountEntry
import pro.ikili.kibicomputers.core.kernel.vfs.MountTable
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.persist.KernelStateCopy
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException

/** `KERNEL` 節を読むときの上限（#82）。数はこれで先に確かめてから確保する */
internal class KernelLimits(
    /** プロセスの数の上限（`MachineConfig.maxProcesses`）。親のないゾンビの分の余りを足して確かめる */
    val maxProcesses: Int,
    /** ハンドル表の上限の上限 */
    val maxHandles: Int = DEFAULT_HANDLE_LIMIT,
)

/**
 * 読んだ `KERNEL` 節（#82）。#85 がマシンに戻す。参照（ハンドル・`wait`・`waitTimers`・`PipeEnd.streamId`・`objectId`・
 * `consoleTtyId`）はすべて表にあることを確かめてある
 */
internal class DecodedKernelState(
    val fuel: Long,
    val consoleTtyId: Int,
    val initPid: Int,
    val clocks: ClockSnapshot,
    val processes: ProcessTable,
    /** `wakeOnRestore` が立っているプロセスの pid */
    val wakeOnRestore: Set<Int>,
    val objects: ObjectTable,
    /** 開いたファイルのオブジェクトID → FS の中のパス（captureCopy で引けたものだけ） */
    val filePaths: Map<Int, VfsPath>,
    val events: EventHub,
    val mounts: MountTable,
)

/**
 * `KERNEL` 節（版 1）の中身（#82、ADR 0082）。並びの順に、先頭（燃料・コンソール・init・時計）→ プロセス表 → オブジェクト表 →
 * イベントの索引 → マウントの表。数値はビッグエンディアン、文字列は u32 のバイト数 + UTF-8。
 */
internal object KernelSectionCodec {
    /** `wait` が `null`（待っていない）の印 */
    private const val NO_WAIT = 0xFFFF

    /** 引数・環境変数の数の上限（読むとき） */
    private const val MAX_ARGS = 65_536

    /** `wait` の key の数の上限（読むとき） */
    private const val MAX_WAIT_KEYS = 0xFFFE

    /** 写し [copy] の `KERNEL` 節を書く（閉じたキューの ID を写しの索引から外してから書く） */
    fun write(copy: KernelStateCopy): ByteArray {
        val out = SnapshotWriter(4096)
        out.i64(copy.fuel)
        out.i32(copy.consoleTtyId)
        out.i32(copy.initPid)
        out.i64(copy.clocks.monotonicNanos)
        out.i64(copy.clocks.uptimeTicks)
        out.i64(copy.clocks.realtimeNanos)
        out.i64(copy.clocks.worldTicks)
        // プロセス表
        val table = copy.processes
        out.i32(table.nextPid)
        val orphans = table.orphanZombies.toList()
        out.count(orphans.size)
        for (pid in orphans) out.i32(pid)
        val processes = table.all().sortedBy { it.pid }
        out.count(processes.size)
        for (p in processes) writeProcess(out, p, copy.wakeOnRestore(p.pid))
        // オブジェクト表
        val objects = copy.objects
        out.i32(objects.nextObjectId)
        out.count(objects.size)
        objects.forEachEntrySorted { id, obj, refs ->
            out.i32(id)
            out.i32(refs)
            out.u16(KernelObjectCodec.kindOf(obj))
            val body = SnapshotWriter(64)
            KernelObjectCodec.write(body, obj, copy.filePaths[id])
            out.blob(body.toByteArray())
        }
        // イベントの索引（閉じたキューは外してから。ADR 0063 で任意とされた掃除を、するほうに決めた）
        copy.events.sweep(objects)
        val index = copy.events.index()
        writeIds(out, index.queues)
        writeIds(out, index.all)
        out.count(index.byType.size)
        for ((type, ids) in index.byType) {
            out.string(type)
            writeIds(out, ids)
        }
        // マウントの表
        out.i32(copy.mounts.nextMountId)
        out.count(copy.mounts.entries.size)
        for (m in copy.mounts.entries) {
            out.i32(m.id)
            out.string(m.point.toString())
            out.bool(m.readOnly)
            out.string(m.source)
        }
        return out.toByteArray()
    }

    private fun writeIds(out: SnapshotWriter, ids: List<Int>) {
        out.count(ids.size)
        for (id in ids) out.i32(id)
    }

    private fun writeProcess(out: SnapshotWriter, p: Process, wakeOnRestore: Boolean) {
        out.i32(p.pid)
        out.i32(p.parentPid)
        out.i32(p.objectId)
        out.u8(kindCode(p.kind))
        out.u8(stateCode(p.state))
        out.bool(p.unmetered)
        out.raw(p.moduleHash.bytes())
        out.count(p.args.size)
        for (a in p.args) out.string(a)
        out.count(p.env.size)
        for ((k, v) in p.env) {
            out.string(k)
            out.string(v)
        }
        out.string(p.cwd.toString())
        val exit = p.exitCode
        out.bool(exit != null)
        if (exit != null) out.i32(exit)
        out.u8(KernelObjectCodec.signalCode(p.termSignal))
        var ignored = 0
        for (s in p.ignoredSignals) ignored = ignored or (1 shl KernelObjectCodec.signalCode(s))
        out.i32(ignored)
        val wait = p.wait
        if (wait == null) {
            out.u16(NO_WAIT)
        } else {
            out.u16(wait.size)
            for (k in wait) {
                out.i32(k.objectId)
                out.u8(conditionCode(k.condition))
            }
        }
        out.bool(wakeOnRestore)
        val timers = p.waitTimers.toIntArray()
        out.u16(timers.size)
        for (t in timers) out.i32(t)
        val handles = p.handles
        out.count(handles.limit)
        val entries = handles.entries()
        out.u16(entries.size)
        for ((h, e) in entries) {
            out.i32(h)
            out.i32(e.objectId)
            out.i64(e.flags)
            out.i32(e.rights)
        }
    }

    /** [payload] を読み、参照を確かめる。壊れていれば [SnapshotFormatException]（大きな配列を確保する前に数を確かめる） */
    @Throws(SnapshotFormatException::class)
    fun read(payload: ByteArray, limits: KernelLimits): DecodedKernelState {
        val input = SnapshotReader(payload)
        val fuel = input.i64()
        val consoleTtyId = input.i32()
        val initPid = input.i32()
        val clocks = try {
            ClockSnapshot(input.i64(), input.i64(), input.i64(), input.i64())
        } catch (e: IllegalArgumentException) {
            throw SnapshotFormatException("時計の値が不正", e)
        }
        // プロセス表
        val maxProcesses = limits.maxProcesses * 2 + 64
        val nextPid = input.i32()
        if (nextPid < 1) throw SnapshotFormatException("次の pid が不正（$nextPid）")
        val orphanCount = input.count(maxProcesses, 4)
        val orphans = List(orphanCount) { input.i32() }
        val processCount = input.count(maxProcesses, 16)
        val processes = LinkedHashMap<Int, Process>()
        val wake = HashSet<Int>()
        repeat(processCount) {
            val (p, w) = readProcess(input, limits)
            if (processes.put(p.pid, p) != null) throw SnapshotFormatException("pid ${p.pid} が重なる")
            if (w) wake += p.pid
        }
        // オブジェクト表
        val nextObjectId = input.i32()
        val objectCount = input.count(Int.MAX_VALUE, 14)
        val objects = HashMap<Int, Pair<KernelObject, Int>>()
        val paths = HashMap<Int, VfsPath>()
        repeat(objectCount) {
            val id = input.i32()
            val refs = input.i32()
            if (id <= 0) throw SnapshotFormatException("オブジェクトの ID が不正（$id）")
            if (refs < 1) throw SnapshotFormatException("オブジェクト $id の参照の数が $refs")
            val kind = input.u16()
            val body = input.sub(input.count(Int.MAX_VALUE, 1))
            val read = KernelObjectCodec.read(kind, body)
            if (objects.put(id, read.obj to refs) != null) throw SnapshotFormatException("オブジェクトの ID $id が重なる")
            read.filePath?.let { paths[id] = it }
        }
        // イベントの索引
        val queues = readIds(input)
        val all = readIds(input)
        val typeCount = input.count(EventLimits.MAX_QUEUES * EventLimits.MAX_FILTER_TYPES, 8)
        val byType = List(typeCount) { input.string(EventLimits.MAX_TYPE_BYTES) to readIds(input) }
        // マウントの表
        val nextMountId = input.i32()
        val mountCount = input.count(1024, 14)
        val mounts = List(mountCount) {
            val id = input.i32()
            val point = parsePath(input.string(KernelObjectCodec.MAX_STRING_BYTES))
            MountEntry(id, point, input.bool(), input.string(KernelObjectCodec.MAX_STRING_BYTES))
        }
        input.expectEnd()

        // ---- 参照の検査 ----
        fun obj(id: Int): KernelObject? = objects[id]?.first
        if (consoleTtyId != 0 && obj(consoleTtyId) !is Tty) throw SnapshotFormatException("コンソールの TTY $consoleTtyId が表にない")
        if (initPid != 0 && initPid !in processes) throw SnapshotFormatException("init の pid $initPid が表にない")
        for (pid in orphans) {
            val p = processes[pid] ?: throw SnapshotFormatException("親のないゾンビ $pid が表にない")
            if (!p.state.isTerminal || p.parentPid != 0) throw SnapshotFormatException("pid $pid は親のないゾンビでない")
        }
        if (orphans.toSet().size != orphans.size) throw SnapshotFormatException("親のないゾンビが重なる")
        for (p in processes.values) {
            val po = obj(p.objectId) as? ProcessObject ?: throw SnapshotFormatException("pid ${p.pid} の ProcessObject ${p.objectId} が表にない")
            if (po.pid != p.pid) throw SnapshotFormatException("ProcessObject ${p.objectId} の pid が合わない")
            for ((h, e) in p.handles.entries()) {
                if (obj(e.objectId) == null) throw SnapshotFormatException("pid ${p.pid} の fd $h のオブジェクト ${e.objectId} が表にない")
            }
            p.wait?.let { wait ->
                if (p.state.isTerminal) throw SnapshotFormatException("終わったプロセス ${p.pid} が待っている")
                for (k in wait) if (obj(k.objectId) == null) throw SnapshotFormatException("pid ${p.pid} の待ちのオブジェクト ${k.objectId} が表にない")
            }
            p.waitTimers.forEach { id -> if (obj(id) !is Timer) throw SnapshotFormatException("pid ${p.pid} の一時的なタイマー $id が表にない") }
        }
        for ((id, pair) in objects) {
            val o = pair.first
            if (o is PipeEnd && obj(o.streamId) !is ByteStream) throw SnapshotFormatException("パイプの端 $id の中身 ${o.streamId} が表にない")
            // Timer.eventQueueId は宛先の番号で参照ではない（キューを閉じた後も残る）ので確かめない
        }
        for (id in queues + all + byType.flatMap { it.second }) {
            if (obj(id) !is EventQueue) throw SnapshotFormatException("イベントの索引の $id がキューでない")
        }
        if (mounts.map { it.id }.toSet().size != mounts.size) throw SnapshotFormatException("マウントの ID が重なる")

        val table = ProcessTable(limits.maxProcesses)
        val objectTable = ObjectTable()
        val events = EventHub()
        val mountTable = MountTable()
        try {
            table.restore(processes.values, orphans, nextPid)
            objectTable.restoreFrom(nextObjectId, objects)
            events.restoreIndex(EventHub.Index(queues, all, byType))
            mountTable.restore(mounts, nextMountId)
        } catch (e: IllegalArgumentException) {
            throw SnapshotFormatException("KERNEL 節の値が不正: ${e.message}", e)
        }
        return DecodedKernelState(fuel, consoleTtyId, initPid, clocks, table, wake, objectTable, paths, events, mountTable)
    }

    private fun readIds(input: SnapshotReader): List<Int> {
        val n = input.count(EventLimits.MAX_QUEUES * 4, 4)
        return List(n) { input.i32() }
    }

    private fun readProcess(input: SnapshotReader, limits: KernelLimits): Pair<Process, Boolean> {
        val pid = input.i32()
        val parentPid = input.i32()
        val objectId = input.i32()
        if (pid < 1 || parentPid < 0) throw SnapshotFormatException("pid が不正（$pid, 親 $parentPid）")
        val kind = kindOf(input.u8())
        val state = stateOf(input.u8())
        val unmetered = input.bool()
        val hash = ModuleHash.fromBytes(input.raw(ModuleHash.SIZE))
        val argCount = input.count(MAX_ARGS, 4)
        val args = List(argCount) { input.string() }
        val envCount = input.count(MAX_ARGS, 8)
        val env = LinkedHashMap<String, String>()
        repeat(envCount) { env[input.string()] = input.string() }
        val cwd = parsePath(input.string(KernelObjectCodec.MAX_STRING_BYTES))
        val exitCode = if (input.bool()) input.i32() else null
        val termSignal = KernelObjectCodec.signalOf(input.u8())
        val ignoredBits = input.i32()
        val p = Process(pid, parentPid, kind, hash, unmetered, args, env, cwd)
        p.objectId = objectId
        p.state = state
        p.exitCode = exitCode
        p.termSignal = termSignal
        for (s in KillSignal.entries) {
            if (ignoredBits and (1 shl KernelObjectCodec.signalCode(s)) != 0) {
                if (!s.ignorable) throw SnapshotFormatException("無視できないシグナル $s")
                p.ignoredSignals += s
            }
        }
        val waitCount = input.u16()
        p.wait = if (waitCount == NO_WAIT) {
            null
        } else {
            if (waitCount > MAX_WAIT_KEYS || waitCount * 5L > input.remaining) throw SnapshotFormatException("待ちの数が不正")
            List(waitCount) { WaitKey(input.i32(), conditionOf(input.u8())) }
        }
        val wake = input.bool()
        val timerCount = input.u16()
        if (timerCount * 4L > input.remaining) throw SnapshotFormatException("一時的なタイマーの数が不正")
        repeat(timerCount) { p.waitTimers.add(input.i32()) }
        val handleLimit = input.count(limits.maxHandles)
        if (handleLimit < 1) throw SnapshotFormatException("ハンドル表の上限が 0")
        val handleCount = input.u16()
        if (handleCount > handleLimit || handleCount * 20L > input.remaining) throw SnapshotFormatException("ハンドルの数が不正")
        val handles = HandleTable<HandleEntry>(handleLimit)
        repeat(handleCount) {
            val h = input.i32()
            if (h !in 0 until handleLimit || handles[h] != null) throw SnapshotFormatException("ハンドル $h が不正か重なる")
            handles.insertAt(h, HandleEntry(input.i32(), input.i64(), input.i32()))
        }
        p.restoreHandles(handles)
        return p to wake
    }

    private fun parsePath(text: String): VfsPath {
        if (!text.startsWith("/")) throw SnapshotFormatException("絶対パスでない: $text")
        return try {
            VfsPath.parse(text)
        } catch (e: RuntimeException) {
            throw SnapshotFormatException("パスとして読めない: $text", e)
        }
    }

    // ---- 列挙の番号（列挙の順番ではなく、ここで決めた表） ----

    fun kindCode(kind: ProcessKind): Int = when (kind) {
        ProcessKind.BLOCKING -> 1
        ProcessKind.WAIT_POINT -> 2
    }

    fun kindOf(code: Int): ProcessKind = when (code) {
        1 -> ProcessKind.BLOCKING
        2 -> ProcessKind.WAIT_POINT
        else -> throw SnapshotFormatException("プロセスの動き方 $code を知らない")
    }

    fun stateCode(state: ProcessState): Int = when (state) {
        ProcessState.CREATED -> 1
        ProcessState.LOADING -> 2
        ProcessState.RUNNABLE -> 3
        ProcessState.RUNNING -> 4
        ProcessState.WAITING_FUEL -> 5
        ProcessState.WAITING_EVENT -> 6
        ProcessState.WAITING_HOST -> 7
        ProcessState.EXITED -> 8
        ProcessState.CRASHED -> 9
        ProcessState.KILLED -> 10
    }

    fun stateOf(code: Int): ProcessState = when (code) {
        1 -> ProcessState.CREATED
        2 -> ProcessState.LOADING
        3 -> ProcessState.RUNNABLE
        4 -> ProcessState.RUNNING
        5 -> ProcessState.WAITING_FUEL
        6 -> ProcessState.WAITING_EVENT
        7 -> ProcessState.WAITING_HOST
        8 -> ProcessState.EXITED
        9 -> ProcessState.CRASHED
        10 -> ProcessState.KILLED
        else -> throw SnapshotFormatException("プロセスの状態 $code を知らない")
    }

    fun conditionCode(c: WaitCondition): Int = when (c) {
        WaitCondition.READABLE -> 1
        WaitCondition.WRITABLE -> 2
        WaitCondition.EXITED -> 3
        WaitCondition.CHILD_EXITED -> 4
    }

    fun conditionOf(code: Int): WaitCondition = when (code) {
        1 -> WaitCondition.READABLE
        2 -> WaitCondition.WRITABLE
        3 -> WaitCondition.EXITED
        4 -> WaitCondition.CHILD_EXITED
        else -> throw SnapshotFormatException("待ちの条件 $code を知らない")
    }
}
