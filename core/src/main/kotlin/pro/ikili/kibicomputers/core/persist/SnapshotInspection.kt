package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.persist.snapshot.KernelLimits
import pro.ikili.kibicomputers.core.persist.snapshot.KernelSectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader

/**
 * 保存データの1つのプロセス（[SnapshotInspection] が読んだもの。#552）。
 *
 * @property pid pid
 * @property args 引数（argv。最初の要素はプログラムの名前）
 * @property loadedHash `KERNEL` 節のプロセスのモジュールの識別子（読み込み方を当てた後のバイト列の SHA-256）
 * @property abiLayer `PROCESS_ABI` 節の層の ID（節がなければ `null`）
 * @property originalHash p1 の層のモジュール参照の、元のバイト列の SHA-256（モジュールの置き場のファイル名）。p1 の層でなければ `null`
 * @property abiLoadedHash p1 の層のモジュール参照の、後のハッシュ。p1 の層でなければ `null`
 */
@ApiStatus.Internal
public class InspectedProcess internal constructor(
    public val pid: Int,
    public val args: List<String>,
    loadedHash: ByteArray,
    public val abiLayer: Int?,
    originalHash: ByteArray?,
    abiLoadedHash: ByteArray?,
) {
    private val loaded = loadedHash.copyOf()
    private val original = originalHash?.copyOf()
    private val abiLoaded = abiLoadedHash?.copyOf()

    public val loadedHash: ByteArray get() = loaded.copyOf()
    public val originalHash: ByteArray? get() = original?.copyOf()
    public val abiLoadedHash: ByteArray? get() = abiLoaded?.copyOf()

    override fun toString(): String =
        "InspectedProcess(pid=$pid, args=$args, layer=$abiLayer, original=${original?.let(ModuleStore::hex)})"
}

/**
 * 保存データの中身を読んで確かめた結果（#552）。
 *
 * @property initPid `KERNEL` 節の init の pid
 * @property processes `KERNEL` 節のプロセス（pid の順）と、それぞれの `PROCESS_ABI` 節の層
 */
@ApiStatus.Internal
public class SnapshotContents internal constructor(
    public val initPid: Int,
    public val processes: List<InspectedProcess>,
)

/**
 * 保存データ（[MachineSnapshot]）の `KERNEL`・`PROCESS_ABI` 節を、戻すとき（#85）と同じ読み手で読んで確かめる。
 * 接続層の GameTest が、置き場がつながって冷えていない写しが書けたことを確かめるのに使う（#552。内部の codec は接続層から見えない）。
 */
@ApiStatus.Internal
public object SnapshotInspection {
    /**
     * [snapshot] の節を読む。`KERNEL` 節がちょうど1つあり `KernelSectionCodec.read` に通ること、`PROCESS_ABI` 節が
     * `KERNEL` 節にあるプロセスを1回ずつ指すこと、p1 の層が `P1SectionCodec.read` に通り後のハッシュが `KERNEL` 節と合うことを確かめる。
     * 冷えた起動の写しや、確かめられないものは [SnapshotFormatException]。[maxProcesses] はマシンのプロセスの数の上限
     */
    @JvmStatic
    @Throws(SnapshotFormatException::class)
    public fun inspect(snapshot: MachineSnapshot, maxProcesses: Int): SnapshotContents {
        if (snapshot.requiresColdBoot) throw SnapshotFormatException("冷えた起動の写し（理由 ${snapshot.coldBootCode}）")
        val kernelSections = snapshot.sections.filter { it.kind == SnapshotSection.KERNEL }
        if (kernelSections.size != 1) throw SnapshotFormatException("KERNEL 節が ${kernelSections.size} 個")
        val kernelSection = kernelSections.single()
        if (kernelSection.version != SnapshotSection.KERNEL_VERSION) {
            throw SnapshotFormatException("KERNEL 節の版 ${kernelSection.version}")
        }
        val kernel = KernelSectionCodec.read(kernelSection.payloadUnsafe(), KernelLimits(maxProcesses))

        // pid → (層, 元のハッシュ, 後のハッシュ)
        val abi = HashMap<Int, Triple<Int, ByteArray?, ModuleHash?>>()
        for (section in snapshot.sections) {
            if (section.kind != SnapshotSection.PROCESS_ABI) continue
            if (section.version != SnapshotSection.PROCESS_ABI_VERSION) {
                throw SnapshotFormatException("PROCESS_ABI 節の版 ${section.version}")
            }
            val input = SnapshotReader(section.payloadUnsafe())
            val pid = input.i32()
            val layer = input.u16()
            val layerVersion = input.u16()
            val process = kernel.processes[pid] ?: throw SnapshotFormatException("PROCESS_ABI 節の pid $pid が KERNEL 節にない")
            val entry = if (layer == SnapshotSection.ABI_LAYER_P1) {
                if (layerVersion != SnapshotSection.P1_LAYER_VERSION) throw SnapshotFormatException("p1 の層の版 $layerVersion")
                val decoded = P1SectionCodec.read(input.raw(input.remaining))
                if (decoded.loadedHash != process.moduleHash) {
                    throw SnapshotFormatException("pid $pid の後のハッシュが KERNEL 節と合わない")
                }
                Triple(layer, decoded.moduleSource.originalHash, decoded.loadedHash)
            } else {
                Triple(layer, null, null)
            }
            if (abi.put(pid, entry) != null) throw SnapshotFormatException("pid $pid の PROCESS_ABI 節が重なる")
        }

        val processes = kernel.processes.all().sortedBy { it.pid }.map { p ->
            val layer = abi[p.pid]
            InspectedProcess(p.pid, p.args, p.moduleHash.bytes(), layer?.first, layer?.second, layer?.third?.bytes())
        }
        return SnapshotContents(kernel.initPid, processes)
    }
}
