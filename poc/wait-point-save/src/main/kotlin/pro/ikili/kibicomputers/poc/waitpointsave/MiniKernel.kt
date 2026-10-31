package pro.ikili.kibicomputers.poc.waitpointsave

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** プロセスの状態。 */
enum class ProcessState {
    /** まだ動かしていない */
    NEW,

    /** export から戻ってイベントを待っている（待機点） */
    WAITING,

    /** 最後まで動いた、または proc_exit した */
    EXITED,

    /** エラーで止まった */
    FAILED,
}

/** ホスト関数の中で待つときに呼ばれる（テストのフック）。[reason] は待っている関数の名前。 */
fun interface HostWaitHook {
    fun onHostWait(reason: String)
}

/**
 * PoC 用のカーネルの状態。
 *
 * 本番のカーネル（#63・#72 など）の代わりに、保存に含めるべきもの（イベントキュー、タイマー、仮想時間、乱数、
 * 標準入出力のバッファ、プロセスの状態）を持つ。WASI の状態をすべてここに持つので、線形メモリなどと合わせて保存できる。
 */
class MiniKernel(args: List<String> = emptyList()) {
    val args: MutableList<String> = args.toMutableList()

    /** 届いていて、まだプロセスに渡していないイベント */
    val events: ArrayDeque<ByteArray> = ArrayDeque()

    /** 仮想時間で発火するタイマー（期限、発火したら積むイベント）。期限の順に並べる */
    val timers: MutableList<Pair<Long, ByteArray>> = mutableListOf()

    /** 仮想時間（ナノ秒）。`clock_time_get` のたびに少し進む（決定的にするため） */
    var clockNanos: Long = 1_700_000_000_000_000_000L

    /** `random_get` の状態（splitmix64） */
    var rng: Long = 0x1234_5678_9abc_def0L

    /** 標準入力のうち、まだ読まれていないもの */
    var stdin: ByteArray = ByteArray(0)

    /** 標準入力が閉じられたか（これ以上入力がない） */
    var stdinClosed: Boolean = false
    val stdout: ByteArrayOutputStream = ByteArrayOutputStream()
    val stderr: ByteArrayOutputStream = ByteArrayOutputStream()
    var state: ProcessState = ProcessState.NEW
    var exitCode: Int = 0

    // ここから下は保存しない（待機点では決まった値になる）

    /** WASM の中にいるか（export の呼び出し中。ホスト関数の中で待っているときも含む）。待機点では false */
    var inWasm: Boolean = false

    /** ホスト関数の中で待った回数（測定・テスト用） */
    var hostWaits: Int = 0

    /** ホスト関数の中で待つときのフック */
    var hostWaitHook: HostWaitHook? = null

    fun post(event: ByteArray) {
        events.addLast(event)
    }

    fun post(event: String) = post(event.toByteArray())

    fun addTimer(deadline: Long, event: ByteArray) {
        timers += deadline to event
        timers.sortBy { it.first }
    }

    /** 仮想時間を進め、期限が来たタイマーをイベントキューに移す。 */
    fun advance(nanos: Long) {
        clockNanos += nanos
        while (timers.isNotEmpty() && timers[0].first <= clockNanos) post(timers.removeAt(0).second)
    }

    fun feedStdin(bytes: ByteArray) {
        stdin += bytes
    }

    fun nextRandom(): Long {
        rng += -0x61c8864680b583ebL
        var z = rng
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** ホスト関数の中で待つ。プロセスは WASM の中にいるので、この間は待機点ではない。 */
    fun blockingWait(reason: String) {
        check(inWasm) { "WASM の外でホスト関数が呼ばれた" }
        hostWaits++
        hostWaitHook?.onHostWait(reason)
    }

    fun stdoutText(): String = stdout.toString(Charsets.UTF_8)

    fun stderrText(): String = stderr.toString(Charsets.UTF_8)

    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(VERSION)
            out.writeInt(args.size)
            args.forEach(out::writeUTF)
            out.writeInt(events.size)
            events.forEach { out.writeBytes(it) }
            out.writeInt(timers.size)
            timers.forEach { (deadline, event) ->
                out.writeLong(deadline)
                out.writeBytes(event)
            }
            out.writeLong(clockNanos)
            out.writeLong(rng)
            out.writeBytes(stdin)
            out.writeBoolean(stdinClosed)
            out.writeBytes(stdout.toByteArray())
            out.writeBytes(stderr.toByteArray())
            out.writeByte(state.ordinal)
            out.writeInt(exitCode)
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val VERSION = 1

        private fun DataOutputStream.writeBytes(b: ByteArray) {
            writeInt(b.size)
            write(b)
        }

        private fun DataInputStream.readBytes(): ByteArray = ByteArray(readInt()).also(::readFully)

        fun fromBytes(bytes: ByteArray): MiniKernel = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readShort().toInt() == VERSION) { "カーネルの状態の版が違う" }
            val k = MiniKernel(List(input.readInt()) { input.readUTF() })
            repeat(input.readInt()) { k.events.addLast(input.readBytes()) }
            repeat(input.readInt()) { k.timers += input.readLong() to input.readBytes() }
            k.clockNanos = input.readLong()
            k.rng = input.readLong()
            k.stdin = input.readBytes()
            k.stdinClosed = input.readBoolean()
            k.stdout.write(input.readBytes())
            k.stderr.write(input.readBytes())
            k.state = ProcessState.entries[input.readByte().toInt()]
            k.exitCode = input.readInt()
            k
        }
    }
}
