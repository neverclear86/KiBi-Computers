package pro.ikili.kibicomputers.poc.screensync

/**
 * 同期方式。サーバー側は tick の終わりに [Sender.encode] を1回呼び、返ったバイト列を
 * 見ている全員に同じものを送る。何も変わっていなければ null（送らない）。
 */
enum class Method(val label: String) {
    /** A: 変化した文字セル（ピクセル）の run だけ送る。 */
    CELL_DIFF("A セル差分"),

    /** B: 描画命令を送り、クライアントで再生する。 */
    COMMANDS("B 描画命令"),

    /** C: 画面の画像（バッファ）を前の画面と XOR して圧縮し、送る。 */
    IMAGE_DIFF("C 画像差分"),

    /**
     * B を基本にし、命令が画面の半分より大きくなる tick だけ画面全体（キーフレーム）を送る。
     * キーフレームはメインスレッドでは圧縮しない（Minecraft のパケット圧縮がネットワークのスレッドで行う）。
     */
    COMMANDS_WITH_KEYFRAME("B+K 描画命令＋キーフレーム"),
    ;

    /** 描画のときに命令を記録する必要があるか。 */
    val needsLog: Boolean get() = this == COMMANDS || this == COMMANDS_WITH_KEYFRAME

    fun sender(server: Screen): Sender = when (this) {
        CELL_DIFF -> CellDiffSender(server)
        COMMANDS -> CommandSender(server)
        IMAGE_DIFF -> ImageDiffSender(server)
        COMMANDS_WITH_KEYFRAME -> KeyframeCommandSender(server)
    }

    fun receiver(client: Screen): Receiver = when (this) {
        CELL_DIFF -> Receiver { client.applyDiff(Reader(it)) }
        COMMANDS -> Receiver { client.applyCommands(Reader(it)) }
        IMAGE_DIFF -> ImageDiffReceiver(client)
        COMMANDS_WITH_KEYFRAME -> Receiver {
            if (it[0].toInt() == 0) {
                client.applyCommands(Reader(it, 1))
            } else {
                client.loadSnapshot(it.copyOfRange(1, it.size))
            }
        }
    }
}

interface Sender {
    /** サーバーが余分に持つメモリ（バイト）。 */
    val extraMemory: Int
    fun encode(): ByteArray?
}

fun interface Receiver {
    fun receive(packet: ByteArray)
}

private class CellDiffSender(private val server: Screen) : Sender {
    private val prev = server.duplicate()
    private val out = Buf(4096)
    override val extraMemory: Int get() = server.rawSize

    override fun encode(): ByteArray? {
        out.clear()
        server.encodeDiff(prev, out)
        return if (out.size == 0) null else out.toByteArray()
    }
}

private class CommandSender(private val server: Screen) : Sender {
    init {
        server.log = Buf(4096)
    }
    override val extraMemory: Int get() = 0

    override fun encode(): ByteArray? {
        val log = server.log!!
        if (log.size == 0) return null
        val bytes = log.toByteArray()
        log.clear()
        return bytes
    }
}

private class KeyframeCommandSender(private val server: Screen) : Sender {
    init {
        server.log = Buf(4096)
    }
    private val frame = ByteArray(server.rawSize)
    override val extraMemory: Int get() = 0

    override fun encode(): ByteArray? {
        val log = server.log!!
        if (log.size == 0) return null
        val packet = if (log.size <= server.rawSize / 2) {
            Buf(log.size + 1).also { it.byte(0); it.put(log.bytes, 0, log.size) }.toByteArray()
        } else {
            // 命令が画面の半分を超えたら、画面全体を送ったほうが小さく、再生も速い
            ByteArray(server.rawSize + 1).also {
                it[0] = 1
                server.snapshot(frame)
                frame.copyInto(it, 1)
            }
        }
        log.clear()
        return packet
    }
}

private class ImageDiffSender(private val server: Screen) : Sender {
    private var prev = ByteArray(server.rawSize).also { server.snapshot(it) }
    private var cur = ByteArray(server.rawSize)
    private val xor = ByteArray(server.rawSize)
    override val extraMemory: Int get() = server.rawSize * 3

    override fun encode(): ByteArray? {
        server.snapshot(cur)
        var changed = false
        for (i in xor.indices) {
            val v = (cur[i].toInt() xor prev[i].toInt()).toByte()
            xor[i] = v
            if (v.toInt() != 0) changed = true
        }
        val t = prev; prev = cur; cur = t
        return if (changed) Zip.deflate(xor) else null
    }
}

private class ImageDiffReceiver(private val client: Screen) : Receiver {
    private val frame = ByteArray(client.rawSize).also { client.snapshot(it) }

    override fun receive(packet: ByteArray) {
        val x = Zip.inflate(packet, 0, packet.size, frame.size)
        for (i in frame.indices) frame[i] = (frame[i].toInt() xor x[i].toInt()).toByte()
        client.loadSnapshot(frame)
    }
}
