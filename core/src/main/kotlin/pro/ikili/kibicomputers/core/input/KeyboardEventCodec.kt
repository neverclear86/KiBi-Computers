package pro.ikili.kibicomputers.core.input

import org.jetbrains.annotations.ApiStatus
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * クライアントからサーバーへ送る、キーボードの入力の並び（#142）のバイト列の形。
 *
 * 1つのパケットに、入力を前から順に並べる。各入力は種類の1バイトで始まる:
 *
 * | 種類 | 中身 |
 * | --- | --- |
 * | 0 [KeyboardEvent.KeyDown] | キーの番号（可変長）、修飾キー（1バイト）、繰り返しか（1バイト） |
 * | 1 [KeyboardEvent.KeyUp] | キーの番号（可変長）、修飾キー（1バイト） |
 * | 2 [KeyboardEvent.Text] | UTF-8 のバイト数（可変長）、UTF-8 |
 * | 3 [KeyboardEvent.Paste] | UTF-8 のバイト数（可変長）、UTF-8 |
 *
 * 可変長の整数は LEB128。サーバーは [decode] で読み、形が崩れたもの・上限を超えたものは丸ごと捨てる。
 * 通信の中だけで使う形で、セーブデータには書かない。
 */
@ApiStatus.Internal
public object KeyboardEventCodec {
    /** 1つのパケットのバイト数の上限。 */
    public const val MAX_PACKET_BYTES: Int = 16 * 1024

    /** 1つのパケットに入れる入力の数の上限。 */
    public const val MAX_EVENTS: Int = 1024

    /** 1つの文字列（[KeyboardEvent.Text]・[KeyboardEvent.Paste]）の UTF-8 のバイト数の上限。長いものは分けて送る。 */
    public const val MAX_TEXT_BYTES: Int = 8 * 1024

    private const val KEY_DOWN = 0
    private const val KEY_UP = 1
    private const val TEXT = 2
    private const val PASTE = 3

    /**
     * [events] をパケットのバイト列にする。上限に合わせて、長い文字列は文字の境目で分け、パケットも分ける。
     * 返す並びの順に送れば、届く入力の順は [events] と同じになる。
     */
    @JvmStatic
    public fun encode(events: List<KeyboardEvent>): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        val packet = ByteArrayOutputStream()
        val one = ByteArrayOutputStream()
        var count = 0
        fun flush() {
            if (count == 0) return
            packets += packet.toByteArray()
            packet.reset()
            count = 0
        }
        for (event in splitLongText(events)) {
            one.reset()
            writeEvent(one, event)
            if (count == MAX_EVENTS || packet.size() + one.size() > MAX_PACKET_BYTES) flush()
            one.writeTo(packet)
            count++
        }
        flush()
        return packets
    }

    /**
     * パケットのバイト列を入力の並びにする。
     *
     * @throws IllegalArgumentException 形が崩れている、上限を超えている、知らない種類やキーがある、文字列に許さない文字がある
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun decode(data: ByteArray): List<KeyboardEvent> {
        require(data.size <= MAX_PACKET_BYTES) { "packet too large: ${data.size}" }
        val reader = Reader(data)
        val events = ArrayList<KeyboardEvent>()
        while (reader.hasMore) {
            require(events.size < MAX_EVENTS) { "too many events" }
            events += when (val type = reader.byte()) {
                KEY_DOWN -> KeyboardEvent.KeyDown(reader.key(), reader.modifiers(), reader.bool())
                KEY_UP -> KeyboardEvent.KeyUp(reader.key(), reader.modifiers())
                TEXT -> {
                    val text = reader.text()
                    require(KeyboardText.isValidText(text)) { "control character in text" }
                    KeyboardEvent.Text(text)
                }
                PASTE -> {
                    val text = reader.text()
                    require(KeyboardText.isValidPaste(text)) { "control character in paste" }
                    KeyboardEvent.Paste(text)
                }
                else -> throw IllegalArgumentException("unknown event type $type")
            }
        }
        return events
    }

    private fun writeEvent(out: ByteArrayOutputStream, event: KeyboardEvent) {
        when (event) {
            is KeyboardEvent.KeyDown -> {
                out.write(KEY_DOWN)
                writeUint(out, event.key.ordinal)
                out.write(event.modifiers and KeyModifiers.ALL)
                out.write(if (event.repeat) 1 else 0)
            }
            is KeyboardEvent.KeyUp -> {
                out.write(KEY_UP)
                writeUint(out, event.key.ordinal)
                out.write(event.modifiers and KeyModifiers.ALL)
            }
            is KeyboardEvent.Text -> writeText(out, TEXT, event.text)
            is KeyboardEvent.Paste -> writeText(out, PASTE, event.text)
        }
    }

    private fun writeText(out: ByteArrayOutputStream, type: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        out.write(type)
        writeUint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeUint(out: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x and 0x7F.inv() != 0) {
            out.write((x and 0x7F) or 0x80)
            x = x ushr 7
        }
        out.write(x)
    }

    /** UTF-8 で [MAX_TEXT_BYTES] を超える文字列を、文字（コードポイント）の境目で分ける */
    private fun splitLongText(events: List<KeyboardEvent>): List<KeyboardEvent> {
        if (events.none { (it is KeyboardEvent.Text && utf8Length(it.text) > MAX_TEXT_BYTES) || (it is KeyboardEvent.Paste && utf8Length(it.text) > MAX_TEXT_BYTES) }) {
            return events
        }
        val out = ArrayList<KeyboardEvent>(events.size + 4)
        for (event in events) {
            when (event) {
                is KeyboardEvent.Text -> chunks(event.text).mapTo(out) { KeyboardEvent.Text(it) }
                is KeyboardEvent.Paste -> chunks(event.text).mapTo(out) { KeyboardEvent.Paste(it) }
                else -> out += event
            }
        }
        return out
    }

    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val b = utf8Length(cp)
            if (bytes + b > MAX_TEXT_BYTES) {
                out += text.substring(start, i)
                start = i
                bytes = 0
            }
            bytes += b
            i += n
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    internal fun utf8Length(text: String): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            total += utf8Length(cp)
            i += Character.charCount(cp)
        }
        return total
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0

        val hasMore: Boolean get() = pos < data.size

        fun byte(): Int {
            require(pos < data.size) { "truncated" }
            return data[pos++].toInt() and 0xFF
        }

        fun bool(): Boolean = when (byte()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("bad boolean")
        }

        fun uint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                require(shift < 32) { "varint too long" }
                val b = byte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            require(result >= 0) { "negative length" }
            return result
        }

        fun key(): Key = Key.fromOrdinal(uint()) ?: throw IllegalArgumentException("unknown key")

        fun modifiers(): Int {
            val m = byte()
            require(m and KeyModifiers.ALL.inv() == 0) { "unknown modifier bits" }
            return m
        }

        fun text(): String {
            val n = uint()
            require(n <= MAX_TEXT_BYTES) { "text too long: $n" }
            require(n <= data.size - pos) { "truncated" }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try {
                decoder.decode(ByteBuffer.wrap(data, pos, n)).toString()
            } catch (e: CharacterCodingException) {
                throw IllegalArgumentException("malformed UTF-8", e)
            }
            pos += n
            return text
        }
    }
}
