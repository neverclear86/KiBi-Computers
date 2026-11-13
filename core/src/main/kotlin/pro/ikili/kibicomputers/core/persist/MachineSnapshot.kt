package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.persist.snapshot.DeltaBaseCodec
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.UUID

/**
 * 保存データの節（#81、ADR 0081）。「種類（u16）・節の版（u16）・長さ（u32）・中身」。
 *
 * 中身の形は種類ごとに決める（`KERNEL`・`PROCESS_ABI`・`TMPFS` の中身は #82、ADR 0082）。
 */
@ApiStatus.Internal
public class SnapshotSection private constructor(
    /** 種類（[KERNEL] など。0〜65535） */
    public val kind: Int,
    /** 節の版（0〜65535） */
    public val version: Int,
    private val bytes: ByteArray,
    @Suppress("UNUSED_PARAMETER") owned: Boolean,
) {
    /** 中身 [payload] を写して持つ節を作る */
    public constructor(kind: Int, version: Int, payload: ByteArray) : this(kind, version, payload.copyOf(), true)

    init {
        require(kind in 0..0xFFFF) { "節の種類は u16（$kind）" }
        require(version in 0..0xFFFF) { "節の版は u16（$version）" }
    }

    /** 中身（写し） */
    public val payload: ByteArray get() = bytes.copyOf()

    /** 中身の長さ */
    public val size: Int get() = bytes.size

    /** 中身を写さずに渡す（読む側が変えないこと。#82 の符号化・復号で配列を何度も写さないため） */
    internal fun payloadUnsafe(): ByteArray = bytes

    internal fun writeTo(out: DataOutput) {
        out.writeShort(kind)
        out.writeShort(version)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    override fun toString(): String = "SnapshotSection(kind=$kind, version=$version, ${bytes.size} bytes)"

    public companion object {
        /** カーネルの状態（pid・親子・状態・待ち・fd 表・オブジェクト表・時計。中身は #82） */
        public const val KERNEL: Int = 1

        /** プロセスごとの ABI 層の状態（先頭に pid（i32）・ABI 層の ID（u16）・層の版（u16）。中身は #82） */
        public const val PROCESS_ABI: Int = 2

        /** 写しの中で殺した pid の一覧（u32 の個数 + i32 の並び）と、冷えた起動が要る理由（u8） */
        public const val KILLED: Int = 3

        /** `/tmp` のマウントごとの中身（#82、ADR 0082。先頭にマウントの ID（i32）） */
        public const val TMPFS: Int = 4

        /**
         * 差分の世代が指す基準（#83、ADR 0083。基準の世代 i64・基準のファイルの長さ i64・CRC32C（i32））。
         * 差分の世代にだけ、外枠の直後（節の並びの先頭）に1つ書く。[MachineSnapshot.deltaBase] で渡し、[MachineSnapshot.sections] には入れない
         */
        public const val DELTA_BASE: Int = 5

        /** [DELTA_BASE] 節の版 */
        public const val DELTA_BASE_VERSION: Int = 1

        /** [DELTA_BASE] 節の中身の長さ */
        public const val DELTA_BASE_LENGTH: Int = 20

        /** [KILLED] 節の版 */
        public const val KILLED_VERSION: Int = 1

        /** [KERNEL] 節の版 */
        public const val KERNEL_VERSION: Int = 1

        /** [PROCESS_ABI] 節の版（外側の形。層の中身の版は層ごと） */
        public const val PROCESS_ABI_VERSION: Int = 1

        /** [TMPFS] 節の版 */
        public const val TMPFS_VERSION: Int = 1

        /** ABI 層の ID: テスト用（予約。本物の層には使わない。読む側は知らない層として拒む） */
        public const val ABI_LAYER_TEST: Int = 0

        /** ABI 層の ID: WASI p1 */
        public const val ABI_LAYER_P1: Int = 1

        /** p1 の層の中身の版（2 は #83。インスタンスの状態の線形メモリに形と圧縮を足した。読むときは 1 も読む） */
        public const val P1_LAYER_VERSION: Int = 2

        /** 渡した配列をそのまま持つ節を作る（呼び出し側はその後で配列を変えない） */
        @JvmSynthetic
        internal fun wrap(kind: Int, version: Int, payload: ByteArray): SnapshotSection = SnapshotSection(kind, version, payload, true)
    }
}

/**
 * マシン1台の保存データの外枠（#81、ADR 0081）。世代ファイル `<世代>.bin` の中身。
 *
 * 形: magic `KIBS`、形式の版（u16 = [FORMAT_VERSION]）、MOD のバージョン（`writeUTF`）、UUID（2つの i64）、世代（i64）、
 * 続いてファイルの終わりまで節（[SnapshotSection]）を並べる。数値はビッグエンディアン。可変長の整数は使わない。
 *
 * 読むとき、知らない節と、長さの合わない既知の節は読み飛ばす（[sections] には残る）。`KILLED` 節がない・読めないときは、
 * 冷えた起動が要るとして扱う（状態を蘇らせない側に倒す）。
 *
 * 差分の世代（#83、ADR 0083）は、外枠の直後に `DELTA_BASE` 節を1つ持つ（[deltaBase]）。先頭以外にある・2つ以上ある・
 * 版か長さが違う `DELTA_BASE` 節は壊れた保存データ（[SnapshotFormatException]）。
 *
 * @property machineId マシンの ID
 * @property generation 世代
 * @property modVersion 書いた MOD のバージョン
 * @property sections `KILLED` 以外の節（書く順）
 * @property killedPids 写しの中で殺した pid（#86 が使う）
 * @property coldBootCode 冷えた起動が要る理由の値（[ColdBootReason.code]。知らない値もそのまま持つ）
 * @property deltaBase 差分の世代なら、指す基準（UUID は [machineId] と同じ）。全体の世代なら `null`
 */
@ApiStatus.Internal
public class MachineSnapshot @JvmOverloads constructor(
    public val machineId: UUID,
    public val generation: Long,
    public val modVersion: String,
    sections: List<SnapshotSection> = emptyList(),
    killedPids: List<Int> = emptyList(),
    public val coldBootCode: Int = ColdBootReason.NONE.code,
    public val deltaBase: DeltaBase? = null,
) {
    init {
        require(coldBootCode in 0..0xFF) { "冷えた起動の理由は u8（$coldBootCode）" }
        require(sections.none { it.kind == SnapshotSection.KILLED }) { "KILLED 節は killedPids・coldBootCode で渡す" }
        require(sections.none { it.kind == SnapshotSection.DELTA_BASE }) { "DELTA_BASE 節は deltaBase で渡す" }
        require(deltaBase == null || deltaBase.id == machineId) { "差分の基準の UUID は外枠の UUID と同じ" }
    }

    public val sections: List<SnapshotSection> = sections.toList()

    public val killedPids: List<Int> = killedPids.toList()

    /** 冷えた起動が要る理由。知らない値なら `null`（[requiresColdBoot] は真） */
    public val coldBootReason: ColdBootReason? get() = ColdBootReason.fromCode(coldBootCode)

    /** 状態を復元せず、冷えた起動にする */
    public val requiresColdBoot: Boolean get() = ColdBootReason.requiresColdBoot(coldBootCode)

    /** 書き出す */
    @Throws(IOException::class)
    public fun writeTo(out: DataOutput) {
        out.write(MAGIC)
        out.writeShort(FORMAT_VERSION)
        out.writeUTF(modVersion)
        out.writeLong(machineId.mostSignificantBits)
        out.writeLong(machineId.leastSignificantBits)
        out.writeLong(generation)
        deltaBase?.let { DeltaBaseCodec.section(it).writeTo(out) }
        for (section in sections) section.writeTo(out)
        killedSection().writeTo(out)
    }

    private fun killedSection(): SnapshotSection {
        val bytes = ByteArrayOutputStream(4 + killedPids.size * 4 + 1)
        DataOutputStream(bytes).use { out ->
            out.writeInt(killedPids.size)
            for (pid in killedPids) out.writeInt(pid)
            out.writeByte(coldBootCode)
        }
        return SnapshotSection(SnapshotSection.KILLED, SnapshotSection.KILLED_VERSION, bytes.toByteArray())
    }

    override fun toString(): String =
        "MachineSnapshot($machineId, generation=$generation, $modVersion, ${sections.size} sections, killed=$killedPids, coldBoot=$coldBootCode" +
            (deltaBase?.let { ", deltaBase=${it.generation}" } ?: "") + ")"

    public companion object {
        /** 形式の版 */
        public const val FORMAT_VERSION: Int = 1

        /** 節1つの中身の長さの上限（読むとき。壊れたファイルで大きな配列を確保しない） */
        public const val MAX_SECTION_BYTES: Int = 1 shl 30

        private val MAGIC = byteArrayOf('K'.code.toByte(), 'I'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte())

        /**
         * 読む。magic・形式の版が違う・途中で切れている・節の長さが上限を超えるなら [IOException]。
         * [input] の終わりまで節を読む（節の始まりでちょうど終わっていれば、そこまで）。
         * 節の頭・中身が途中で切れている、`DELTA_BASE` 節が先頭以外にある・2つある・版か長さが違うなら [SnapshotFormatException]（#83）
         */
        @JvmStatic
        @Throws(IOException::class)
        public fun readFrom(input: DataInput): MachineSnapshot {
            val header = readHeader(input)
            val sections = ArrayList<SnapshotSection>()
            var killed: List<Int>? = null
            var coldBoot = -1
            var deltaBase: DeltaBase? = null
            var first = true
            while (true) {
                val high = try {
                    input.readUnsignedByte()
                } catch (_: EOFException) {
                    break
                }
                val (kind, sectionVersion, length) = truncatedAsFormat { readSectionHead(high, input) }
                if (length > MAX_SECTION_BYTES) throw IOException("節 $kind の長さ $length が上限を超える")
                val isFirst = first
                first = false
                if (kind == SnapshotSection.DELTA_BASE) {
                    if (!isFirst) throw SnapshotFormatException("DELTA_BASE 節が先頭にない")
                    deltaBase = readDeltaBasePayload(header.id, sectionVersion, length, input)
                    continue
                }
                val payload = ByteArray(length.toInt())
                truncatedAsFormat { input.readFully(payload) }
                if (kind == SnapshotSection.KILLED && sectionVersion == SnapshotSection.KILLED_VERSION) {
                    parseKilled(payload)?.let { (pids, code) ->
                        killed = pids
                        coldBoot = code
                    }
                    continue
                }
                // 知らない節・版の違う KILLED 節は、中身をそのまま持つ（読み飛ばす）
                if (kind != SnapshotSection.KILLED) sections += SnapshotSection.wrap(kind, sectionVersion, payload)
            }
            // KILLED 節がない・読めないなら、冷えた起動が要る側に倒す
            val code = if (coldBoot < 0) UNREADABLE_KILLED_CODE else coldBoot
            return MachineSnapshot(header.id, header.generation, header.modVersion, sections, killed.orEmpty(), code, deltaBase)
        }

        /**
         * 差分の世代の基準だけを読む（#83。残す世代を決めるときに、ファイル全体を読まずに基準を引く）。
         * 外枠と、最初の節の頭（種類・版・長さ）と、`DELTA_BASE` 節ならその中身 20 バイトだけを読む。
         * 最初の節が `DELTA_BASE` 節（版 1・長さ 20）ならその中身（UUID は外枠のもの）、ほかの節か節なしで終わっていれば `null`。
         * 途中で切れている・`DELTA_BASE` 節の版か長さが違うなら [SnapshotFormatException]、magic・形式の版が違うなら [IOException]。
         * 2つ目以降の節は見ない（2つ目以降に `DELTA_BASE` 節があるファイルは [readFrom] が拒むので、読める保存データにならない）
         */
        @JvmStatic
        @Throws(IOException::class)
        public fun readDeltaBase(input: DataInput): DeltaBase? {
            val header = readHeader(input)
            val high = try {
                input.readUnsignedByte()
            } catch (_: EOFException) {
                return null
            }
            val (kind, sectionVersion, length) = truncatedAsFormat { readSectionHead(high, input) }
            if (kind != SnapshotSection.DELTA_BASE) return null
            return readDeltaBasePayload(header.id, sectionVersion, length, input)
        }

        /**
         * 外枠の MOD のバージョンだけを読む（#88。戻すときに、版の違うファイルの中身を解釈する前に比べる。ファイル全体を読まない）。
         * magic・形式の版が違うなら [IOException]、外枠の途中で切れているなら [SnapshotFormatException]
         */
        @JvmStatic
        @Throws(IOException::class)
        public fun readModVersion(input: DataInput): String = readHeader(input).modVersion

        private class Header(val modVersion: String, val id: UUID, val generation: Long)

        private fun readHeader(input: DataInput): Header = truncatedAsFormat {
            val magic = ByteArray(4)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("保存データの magic が違う")
            val version = input.readUnsignedShort()
            if (version != FORMAT_VERSION) throw IOException("保存データの形式の版 $version は読めない")
            val modVersion = input.readUTF()
            val id = UUID(input.readLong(), input.readLong())
            val generation = input.readLong()
            Header(modVersion, id, generation)
        }

        /** 種類の上位バイト [high] を読んだ後の、節の頭の残り（種類・版・長さ） */
        private fun readSectionHead(high: Int, input: DataInput): Triple<Int, Int, Long> {
            val kind = (high shl 8) or input.readUnsignedByte()
            val sectionVersion = input.readUnsignedShort()
            val length = input.readInt().toLong() and 0xFFFF_FFFFL
            return Triple(kind, sectionVersion, length)
        }

        private fun readDeltaBasePayload(id: UUID, version: Int, length: Long, input: DataInput): DeltaBase {
            if (version != SnapshotSection.DELTA_BASE_VERSION || length != SnapshotSection.DELTA_BASE_LENGTH.toLong()) {
                throw SnapshotFormatException("DELTA_BASE 節の版 $version・長さ $length が違う")
            }
            val payload = ByteArray(SnapshotSection.DELTA_BASE_LENGTH)
            truncatedAsFormat { input.readFully(payload) }
            return DeltaBaseCodec.read(id, payload)
        }

        /** 途中で切れていたら（[EOFException]）、壊れた保存データにする */
        private inline fun <T> truncatedAsFormat(block: () -> T): T = try {
            block()
        } catch (e: EOFException) {
            throw SnapshotFormatException("保存データが途中で切れている", e)
        }

        /** KILLED 節が読めなかったときの理由（どの理由でもない値。冷えた起動が要る） */
        private const val UNREADABLE_KILLED_CODE = 0xFF

        /** 長さが合わなければ `null`（読み飛ばす） */
        private fun parseKilled(payload: ByteArray): Pair<List<Int>, Int>? {
            if (payload.size < 5) return null
            val input = DataInputStream(payload.inputStream())
            val count = input.readInt().toLong() and 0xFFFF_FFFFL
            if (payload.size.toLong() != 4 + count * 4 + 1) return null
            val pids = List(count.toInt()) { input.readInt() }
            return pids to input.readUnsignedByte()
        }
    }
}
