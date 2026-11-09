package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.DeviceHandle
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.HostCompletion
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException

/**
 * カーネルオブジェクトの種類の番号と中身（#82、ADR 0082。`KERNEL` 節のオブジェクト表のエントリ）。
 *
 * 種類の番号は列挙の順番でなく、ここで決めた表で写す。表にない種類を書こうとしたら [IllegalStateException]
 * （新しい種類を足したら、ここにも足す。`KernelObjectCodecTest` が全種類を回す）。
 */
internal object KernelObjectCodec {
    const val PROCESS: Int = 1
    const val PIPE_END: Int = 2
    const val BYTE_STREAM: Int = 3
    const val OPEN_FILE: Int = 4
    const val TIMER: Int = 5
    const val EVENT_QUEUE: Int = 6
    const val TTY: Int = 7
    const val HOST_COMPLETION: Int = 8
    const val DEVICE_HANDLE: Int = 9
    const val PENDING_DEVICE_CALL: Int = 10

    /** 読むときの、パイプのバッファの容量の上限（壊れたデータで大きな配列を確保しない） */
    const val MAX_STREAM_CAPACITY: Int = 1 shl 24

    /** 読むときの、文字列（パス・アドレスなど）のバイト数の上限 */
    const val MAX_STRING_BYTES: Int = 1 shl 20

    /** 読むときの、デバイスの値の並びの数の上限 */
    const val MAX_DEVICE_VALUES: Int = 65_535

    /** [obj] の種類の番号。表にない種類なら [IllegalStateException] */
    fun kindOf(obj: KernelObject): Int = when (obj) {
        is ProcessObject -> PROCESS
        is PipeEnd -> PIPE_END
        is ByteStream -> BYTE_STREAM
        is OpenFile -> OPEN_FILE
        is Timer -> TIMER
        is EventQueue -> EVENT_QUEUE
        is Tty -> TTY
        is HostCompletion -> HOST_COMPLETION
        is DeviceHandle -> DEVICE_HANDLE
        is PendingDeviceCall -> PENDING_DEVICE_CALL
        else -> throw IllegalStateException("保存の種類の番号がないカーネルオブジェクト: ${obj::class.qualifiedName}")
    }

    /**
     * [obj] の中身を書く（種類の番号と長さは書かない）。[filePath] は `OpenFile` の FS の中の今のパス（captureCopy で引いたもの。なければ `null`）
     */
    fun write(out: SnapshotWriter, obj: KernelObject, filePath: VfsPath? = null) {
        when (obj) {
            is ProcessObject -> {
                out.i32(obj.pid)
                val status = obj.status
                out.bool(status != null)
                if (status != null) {
                    out.u8(exitReasonCode(status.reason))
                    out.i32(status.code)
                    out.u8(signalCode(status.signal))
                }
                out.i32(obj.pendingChildExits)
            }
            is PipeEnd -> {
                out.i32(obj.streamId)
                out.bool(obj.reading)
            }
            is ByteStream -> {
                out.i32(obj.capacity)
                out.bool(obj.readClosed)
                out.bool(obj.writeClosed)
                out.blob(obj.availableBytes())
            }
            is OpenFile -> {
                out.i32(obj.mountId)
                out.i64(obj.node)
                out.u8(fileTypeCode(obj.type))
                out.string(obj.path.toString())
                out.bool(obj.readable)
                out.bool(obj.writable)
                out.bool(obj.append)
                out.i64(obj.position)
                out.bool(filePath != null)
                if (filePath != null) out.string(filePath.toString())
            }
            is Timer -> {
                // eventQueueId は 0 に直さない（sendsEvents が予定の入れ直しと、鳴ったときに read() するかを左右する）
                out.i32(obj.eventQueueId)
                out.i64(obj.eventTag)
                out.bool(obj.armed)
                out.i64(obj.deadlineNanos)
                out.i64(obj.intervalNanos)
                out.i64(obj.expirations)
                out.i64(obj.generation)
            }
            is EventQueue -> {
                val types = obj.filter.types.toList()
                out.u16(types.size)
                for (t in types) out.string(t)
                out.i32(obj.capacity)
                out.i64(obj.dropped)
                val events = obj.pendingEvents()
                out.count(events.size)
                for (e in events) writeEvent(out, e)
            }
            is Tty -> obj.writeSnapshot(out)
            is HostCompletion -> out.bool(obj.done)
            is DeviceHandle -> {
                out.string(obj.address)
                out.string(obj.kind)
            }
            is PendingDeviceCall -> when (val s = obj.snapshot()) {
                is PendingDeviceCall.Snapshot.Queued -> {
                    out.u8(1)
                    out.string(s.address)
                    out.string(s.method)
                    writeDeviceValues(out, s.args)
                }
                is PendingDeviceCall.Snapshot.Done -> {
                    out.u8(2)
                    CallResultCodec.write(out, s.result)
                }
            }
            else -> throw IllegalStateException("保存の種類の番号がないカーネルオブジェクト: ${obj::class.qualifiedName}")
        }
    }

    /** 読んだ `OpenFile` と、添えてあった FS の中のパス */
    class ReadObject(val obj: KernelObject, val filePath: VfsPath? = null)

    /** 種類 [kind] の中身を読む（[input] は中身だけの範囲）。読み終わりを確かめる */
    @Throws(SnapshotFormatException::class)
    fun read(kind: Int, input: SnapshotReader): ReadObject {
        val result = try {
            readInner(kind, input)
        } catch (e: IllegalArgumentException) {
            throw SnapshotFormatException("種類 $kind のオブジェクトの値が不正: ${e.message}", e)
        }
        input.expectEnd()
        return result
    }

    private fun readInner(kind: Int, input: SnapshotReader): ReadObject = when (kind) {
        PROCESS -> {
            val obj = ProcessObject(input.i32())
            if (input.bool()) {
                val reason = exitReasonOf(input.u8())
                val code = input.i32()
                val signal = signalOf(input.u8())
                obj.status = ExitStatus(obj.pid, reason, code, signal)
            }
            obj.pendingChildExits = input.i32().also { if (it < 0) throw SnapshotFormatException("終わった子の数が負") }
            ReadObject(obj)
        }
        PIPE_END -> ReadObject(PipeEnd(input.i32(), input.bool()))
        BYTE_STREAM -> {
            val capacity = input.i32()
            if (capacity !in 1..MAX_STREAM_CAPACITY) throw SnapshotFormatException("バッファの容量が不正（$capacity）")
            val readClosed = input.bool()
            val writeClosed = input.bool()
            val bytes = input.blob(capacity)
            ReadObject(ByteStream.restore(capacity, readClosed, writeClosed, bytes))
        }
        OPEN_FILE -> {
            val mountId = input.i32()
            val node = input.i64()
            val type = fileTypeOf(input.u8())
            val path = parsePath(input.string(MAX_STRING_BYTES))
            val readable = input.bool()
            val writable = input.bool()
            val append = input.bool()
            val position = input.i64()
            if (position < 0) throw SnapshotFormatException("開いたファイルの位置が負")
            val fsPath = if (input.bool()) parsePath(input.string(MAX_STRING_BYTES)) else null
            ReadObject(OpenFile(mountId, node, type, path, readable, writable, append).also { it.position = position }, fsPath)
        }
        TIMER -> {
            // eventQueueId は宛先の番号で参照ではない（キューを閉じた後も残る。ADR 0064）。表にあるかは確かめない
            val timer = Timer(input.i32(), input.i64())
            timer.restoreState(input.bool(), input.i64(), input.i64(), input.i64(), input.i64())
            ReadObject(timer)
        }
        EVENT_QUEUE -> {
            val typeCount = input.u16()
            if (typeCount > EventLimits.MAX_FILTER_TYPES) throw SnapshotFormatException("フィルターの種類が多すぎる")
            val types = LinkedHashSet<String>()
            repeat(typeCount) { types += input.string(EventLimits.MAX_TYPE_BYTES) }
            val capacity = input.i32()
            if (capacity !in 1..EventLimits.MAX_CAPACITY) throw SnapshotFormatException("キューの件数が不正（$capacity）")
            val dropped = input.i64()
            val count = input.count(capacity, 8)
            val events = List(count) { readEvent(input) }
            ReadObject(EventQueue(EventFilter(types), capacity).also { it.restoreState(events, dropped) })
        }
        TTY -> ReadObject(Tty.readSnapshot(input))
        HOST_COMPLETION -> ReadObject(HostCompletion().also { it.done = input.bool() })
        DEVICE_HANDLE -> ReadObject(DeviceHandle(input.string(MAX_STRING_BYTES), input.string(MAX_STRING_BYTES)))
        PENDING_DEVICE_CALL -> when (val tag = input.u8()) {
            1 -> {
                val address = input.string(MAX_STRING_BYTES)
                val method = input.string(MAX_STRING_BYTES)
                val args = readDeviceValues(input)
                ReadObject(PendingDeviceCall.restore(PendingDeviceCall.Snapshot.Queued(address, method, args)))
            }
            2 -> ReadObject(PendingDeviceCall.restore(PendingDeviceCall.Snapshot.Done(CallResultCodec.read(input))))
            else -> throw SnapshotFormatException("呼び出しの状態のタグ $tag を知らない")
        }
        else -> throw SnapshotFormatException("オブジェクトの種類 $kind を知らない")
    }

    private fun parsePath(text: String): VfsPath {
        if (!text.startsWith("/")) throw SnapshotFormatException("絶対パスでない: $text")
        val path = try {
            VfsPath.parse(text)
        } catch (e: RuntimeException) {
            throw SnapshotFormatException("パスとして読めない: $text", e)
        }
        if (path.toString() != text) throw SnapshotFormatException("正規化していないパス: $text")
        return path
    }

    // ---- イベント ----

    fun writeEvent(out: SnapshotWriter, event: KernelEvent) {
        out.string(event.type)
        out.string(event.source)
        out.count(event.values.size)
        for (v in event.values) {
            when (v) {
                is EventValue.Integer -> {
                    out.u8(VALUE_INTEGER)
                    out.i64(v.value)
                }
                is EventValue.Float -> {
                    out.u8(VALUE_FLOAT)
                    out.i64(java.lang.Double.doubleToRawLongBits(v.value))
                }
                is EventValue.Boolean -> {
                    out.u8(VALUE_BOOLEAN)
                    out.bool(v.value)
                }
                is EventValue.Text -> {
                    out.u8(VALUE_TEXT)
                    out.string(v.value)
                }
                is EventValue.Bytes -> {
                    out.u8(VALUE_BYTES)
                    out.blob(v.toByteArray())
                }
            }
        }
    }

    @Throws(SnapshotFormatException::class)
    fun readEvent(input: SnapshotReader): KernelEvent {
        val type = input.string(EventLimits.MAX_TYPE_BYTES)
        val source = input.string(EventLimits.MAX_SOURCE_BYTES)
        val count = input.count(EventLimits.MAX_VALUES, 1)
        val values = List(count) {
            when (val tag = input.u8()) {
                VALUE_INTEGER -> EventValue.Integer(input.i64())
                VALUE_FLOAT -> EventValue.Float(java.lang.Double.longBitsToDouble(input.i64()))
                VALUE_BOOLEAN -> EventValue.Boolean(input.bool())
                VALUE_TEXT -> EventValue.Text(input.string(EventLimits.MAX_PAYLOAD_BYTES))
                VALUE_BYTES -> EventValue.Bytes(input.blob(EventLimits.MAX_PAYLOAD_BYTES))
                else -> throw SnapshotFormatException("イベントの値のタグ $tag を知らない")
            }
        }
        return try {
            KernelEvent(type, source, values)
        } catch (e: IllegalArgumentException) {
            throw SnapshotFormatException("イベントが上限を超える: ${e.message}", e)
        }
    }

    // ---- デバイスの値（公開 API の型。今ある読み出しの口だけで写す） ----

    fun writeDeviceValues(out: SnapshotWriter, values: List<DeviceValue>) {
        out.count(values.size)
        for (v in values) writeDeviceValue(out, v)
    }

    fun writeDeviceValue(out: SnapshotWriter, v: DeviceValue) {
        when (v.type) {
            DeviceValue.Type.NONE -> out.u8(VALUE_NONE)
            DeviceValue.Type.INTEGER -> {
                out.u8(VALUE_INTEGER)
                out.i64(v.asLong())
            }
            DeviceValue.Type.FLOAT -> {
                out.u8(VALUE_FLOAT)
                out.i64(java.lang.Double.doubleToRawLongBits(v.asDouble()))
            }
            DeviceValue.Type.BOOLEAN -> {
                out.u8(VALUE_BOOLEAN)
                out.bool(v.asBoolean())
            }
            DeviceValue.Type.TEXT -> {
                out.u8(VALUE_TEXT)
                out.string(v.asText())
            }
            DeviceValue.Type.BYTES -> {
                out.u8(VALUE_BYTES)
                out.blob(v.asBytes())
            }
        }
    }

    @Throws(SnapshotFormatException::class)
    fun readDeviceValues(input: SnapshotReader): List<DeviceValue> {
        val count = input.count(MAX_DEVICE_VALUES, 1)
        return List(count) { readDeviceValue(input) }
    }

    @Throws(SnapshotFormatException::class)
    fun readDeviceValue(input: SnapshotReader): DeviceValue = when (val tag = input.u8()) {
        VALUE_NONE -> DeviceValue.none()
        VALUE_INTEGER -> DeviceValue.of(input.i64())
        VALUE_FLOAT -> DeviceValue.of(java.lang.Double.longBitsToDouble(input.i64()))
        VALUE_BOOLEAN -> DeviceValue.of(input.bool())
        VALUE_TEXT -> DeviceValue.of(input.string())
        VALUE_BYTES -> DeviceValue.of(input.blob())
        else -> throw SnapshotFormatException("デバイスの値のタグ $tag を知らない")
    }

    private const val VALUE_NONE = 0
    private const val VALUE_INTEGER = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_BOOLEAN = 3
    private const val VALUE_TEXT = 4
    private const val VALUE_BYTES = 5

    // ---- 列挙の番号（列挙の順番ではなく、ここで決めた表） ----

    fun exitReasonCode(reason: ExitReason): Int = when (reason) {
        ExitReason.EXITED -> 1
        ExitReason.KILLED -> 2
        ExitReason.CRASHED -> 3
    }

    fun exitReasonOf(code: Int): ExitReason = when (code) {
        1 -> ExitReason.EXITED
        2 -> ExitReason.KILLED
        3 -> ExitReason.CRASHED
        else -> throw SnapshotFormatException("終わり方 $code を知らない")
    }

    /** シグナルの番号（POSIX の番号。0 = なし） */
    fun signalCode(signal: KillSignal?): Int = when (signal) {
        null -> 0
        KillSignal.INTERRUPT -> 2
        KillSignal.QUIT -> 3
        KillSignal.KILL -> 9
        KillSignal.TERMINATE -> 15
    }

    fun signalOf(code: Int): KillSignal? = when (code) {
        0 -> null
        2 -> KillSignal.INTERRUPT
        3 -> KillSignal.QUIT
        9 -> KillSignal.KILL
        15 -> KillSignal.TERMINATE
        else -> throw SnapshotFormatException("シグナル $code を知らない")
    }

    fun fileTypeCode(type: FileType): Int = when (type) {
        FileType.UNKNOWN -> 0
        FileType.CHARACTER_DEVICE -> 1
        FileType.DIRECTORY -> 2
        FileType.REGULAR_FILE -> 3
    }

    fun fileTypeOf(code: Int): FileType = when (code) {
        0 -> FileType.UNKNOWN
        1 -> FileType.CHARACTER_DEVICE
        2 -> FileType.DIRECTORY
        3 -> FileType.REGULAR_FILE
        else -> throw SnapshotFormatException("ファイルの種類 $code を知らない")
    }
}

/** `PendingDeviceCall` の `Done` の結果（#82、ADR 0082） */
internal object CallResultCodec {
    private const val VALUES = 1
    private const val ERROR = 2

    fun write(out: SnapshotWriter, result: CallResult) {
        when (result) {
            is CallResult.Values -> {
                out.u8(VALUES)
                KernelObjectCodec.writeDeviceValues(out, result.values)
            }
            is CallResult.Error -> {
                out.u8(ERROR)
                when (val e = result.error) {
                    is CallError.Kernel -> {
                        out.u8(1)
                        out.u16(e.errno.code)
                    }
                    CallError.Detached -> out.u8(2)
                    CallError.NoSuchMethod -> out.u8(3)
                    is CallError.BadArguments -> {
                        out.u8(4)
                        out.string(e.message)
                    }
                    // INTERRUPTED・INTERNAL_ERROR も文字列のまま（WIT の failed(message) として呼び出し側に見える）
                    is CallError.Failed -> {
                        out.u8(5)
                        out.string(e.message)
                    }
                }
            }
        }
    }

    @Throws(SnapshotFormatException::class)
    fun read(input: SnapshotReader): CallResult = when (val tag = input.u8()) {
        VALUES -> CallResult.Values(KernelObjectCodec.readDeviceValues(input))
        ERROR -> CallResult.Error(
            when (val e = input.u8()) {
                1 -> {
                    val code = input.u16()
                    CallError.Kernel(Errno.ofCode(code) ?: throw SnapshotFormatException("errno $code を知らない"))
                }
                2 -> CallError.Detached
                3 -> CallError.NoSuchMethod
                4 -> CallError.BadArguments(input.string())
                5 -> CallError.Failed(input.string())
                else -> throw SnapshotFormatException("呼び出しのエラーのタグ $e を知らない")
            },
        )
        else -> throw SnapshotFormatException("呼び出しの結果のタグ $tag を知らない")
    }
}
