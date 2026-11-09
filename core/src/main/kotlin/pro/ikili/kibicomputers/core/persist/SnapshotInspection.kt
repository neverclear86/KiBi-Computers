package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.persist.snapshot.ChunkReadBudget
import pro.ikili.kibicomputers.core.persist.snapshot.DecodedP1Process
import pro.ikili.kibicomputers.core.persist.snapshot.InstanceStateMerge
import pro.ikili.kibicomputers.core.persist.snapshot.KernelLimits
import pro.ikili.kibicomputers.core.persist.snapshot.KernelSectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/**
 * 保存データの1つのプロセス（[SnapshotInspection] が読んだもの。#552）。
 *
 * @property pid pid
 * @property args 引数（argv。最初の要素はプログラムの名前）
 * @property loadedHash `KERNEL` 節のプロセスのモジュールの識別子（読み込み方を当てた後のバイト列の SHA-256）
 * @property abiLayer `PROCESS_ABI` 節の層の ID。終わったプロセス（ゾンビ）で節がなければ `null`（終わっていないプロセスは必ず節を持つ）
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
     * `KERNEL` 節にあるプロセスだけを指し、同じ pid を2回指さないこと、終わっていないプロセス（インスタンスを持つはずのもの）が
     * どれも `PROCESS_ABI` 節を持つこと、p1 の層が `P1SectionCodec.read` に通り後のハッシュが `KERNEL` 節と合うことを確かめる。
     * 終わったプロセス（ゾンビ）は ABI 層の写しを取らない（ADR 0082）ので、節がなくてよい。
     * 冷えた起動の写しや、確かめられないものは [SnapshotFormatException]。[maxProcesses] はマシンのプロセスの数の上限
     */
    @JvmStatic
    @Throws(SnapshotFormatException::class)
    public fun inspect(snapshot: MachineSnapshot, maxProcesses: Int): SnapshotContents = inspect(snapshot, maxProcesses, WasmMemory.MAX_PAGES, null)

    /**
     * [inspect] に加えて、差分の世代（#83、ADR 0083）を基準 [base]（[MachineSaveStore.readGeneration] で読んだもの）と合わせて確かめる。
     *
     * - 差分の世代なら [base] が要り、基準の UUID・世代・ファイルの長さ・CRC32C が `DELTA_BASE` 節と合い、基準が全体の世代で冷えていないこと
     * - 差分の形のプロセスは、基準に同じ pid の p1 の層があり、入口が 0 でなく、線形メモリが全体の形で、元のハッシュ・後のハッシュが同じこと。
     *   基準と合成できること（`InstanceStateMerge`）
     * - 区画の数は、1つのプロセスで `min(ページ数 × 16, maxMemoryPages × 16)`、保存データ全体でも `maxMemoryPages × 16` 以下（合成の後も）
     *
     * [maxMemoryPages] はマシンの RAM の上限（ページ数）
     */
    @JvmStatic
    @Throws(SnapshotFormatException::class)
    public fun inspect(snapshot: MachineSnapshot, maxProcesses: Int, maxMemoryPages: Int, base: StoredSnapshot?): SnapshotContents {
        if (snapshot.requiresColdBoot) throw SnapshotFormatException("冷えた起動の写し（理由 ${snapshot.coldBootCode}）")
        val deltaBase = snapshot.deltaBase
        // 差分なら、基準の p1 の層（pid → 中身）
        val baseLayers: Map<Int, DecodedP1Process>? = if (deltaBase != null) {
            if (base == null) throw SnapshotFormatException("差分の世代に基準が渡されていない")
            val stored = base.snapshot
            if (stored.machineId != snapshot.machineId || stored.machineId != deltaBase.id || stored.generation != deltaBase.generation ||
                base.fileLength != deltaBase.fileLength || base.crc32c != deltaBase.crc32c
            ) {
                throw SnapshotFormatException("基準 ${stored.machineId}/${stored.generation} が DELTA_BASE 節 $deltaBase と合わない")
            }
            if (stored.deltaBase != null) throw SnapshotFormatException("基準が差分の世代")
            if (stored.requiresColdBoot) throw SnapshotFormatException("基準が冷えた起動の写し")
            p1Layers(stored, ChunkReadBudget.forPages(maxMemoryPages))
        } else {
            null
        }
        val kernelSections = snapshot.sections.filter { it.kind == SnapshotSection.KERNEL }
        if (kernelSections.size != 1) throw SnapshotFormatException("KERNEL 節が ${kernelSections.size} 個")
        val kernelSection = kernelSections.single()
        if (kernelSection.version != SnapshotSection.KERNEL_VERSION) {
            throw SnapshotFormatException("KERNEL 節の版 ${kernelSection.version}")
        }
        val kernel = KernelSectionCodec.read(kernelSection.payloadUnsafe(), KernelLimits(maxProcesses))

        val budget = ChunkReadBudget.forPages(maxMemoryPages)
        val mergedBudget = ChunkReadBudget.forPages(maxMemoryPages)
        // pid → (層, 元のハッシュ, 後のハッシュ)
        val abi = HashMap<Int, Triple<Int, ByteArray?, ModuleHash?>>()
        for (section in snapshot.sections) {
            if (section.kind != SnapshotSection.PROCESS_ABI) continue
            val (pid, layer, decoded) = readAbiSection(section, budget)
            val process = kernel.processes[pid] ?: throw SnapshotFormatException("PROCESS_ABI 節の pid $pid が KERNEL 節にない")
            val entry = if (decoded != null) {
                if (decoded.loadedHash != process.moduleHash) {
                    throw SnapshotFormatException("pid $pid の後のハッシュが KERNEL 節と合わない")
                }
                val instance = decoded.instance
                if (instance != null && instance.changedOnly) {
                    val layers = baseLayers ?: throw SnapshotFormatException("pid $pid が差分の形なのに DELTA_BASE 節がない")
                    val baseProcess = layers[pid] ?: throw SnapshotFormatException("pid $pid が基準にない")
                    val baseInstance = baseProcess.instance ?: throw SnapshotFormatException("pid $pid の基準にインスタンスがない")
                    if (baseInstance.changedOnly) throw SnapshotFormatException("pid $pid の基準が差分の形")
                    if (!baseProcess.moduleSource.originalHash.contentEquals(decoded.moduleSource.originalHash) ||
                        baseProcess.loadedHash != decoded.loadedHash
                    ) {
                        throw SnapshotFormatException("pid $pid のモジュールが基準と違う")
                    }
                    val merged = InstanceStateMerge.merge(baseInstance, instance, budget.limitChunks)
                    mergedBudget.take(merged.writtenChunkCount, merged.memoryPages)
                } else if (instance != null) {
                    mergedBudget.take(instance.writtenChunkCount, maxOf(instance.memoryPages, 0))
                }
                Triple(layer, decoded.moduleSource.originalHash, decoded.loadedHash)
            } else {
                Triple(layer, null, null)
            }
            if (abi.put(pid, entry) != null) throw SnapshotFormatException("pid $pid の PROCESS_ABI 節が重なる")
        }

        val processes = kernel.processes.all().sortedBy { it.pid }.map { p ->
            val layer = abi[p.pid]
            if (layer == null && !p.state.isTerminal) {
                throw SnapshotFormatException("終わっていない pid ${p.pid} に PROCESS_ABI 節がない")
            }
            InspectedProcess(p.pid, p.args, p.moduleHash.bytes(), layer?.first, layer?.second, layer?.third?.bytes())
        }
        return SnapshotContents(kernel.initPid, processes)
    }

    /** `PROCESS_ABI` 節を読む（pid・層・p1 の層なら中身） */
    private fun readAbiSection(section: SnapshotSection, budget: ChunkReadBudget): Triple<Int, Int, DecodedP1Process?> {
        if (section.version != SnapshotSection.PROCESS_ABI_VERSION) {
            throw SnapshotFormatException("PROCESS_ABI 節の版 ${section.version}")
        }
        val input = SnapshotReader(section.payloadUnsafe())
        val pid = input.i32()
        val layer = input.u16()
        val layerVersion = input.u16()
        val decoded = if (layer == SnapshotSection.ABI_LAYER_P1) {
            P1SectionCodec.read(input.raw(input.remaining), layerVersion, budget)
        } else {
            null
        }
        return Triple(pid, layer, decoded)
    }

    /** 基準の p1 の層（pid → 中身） */
    private fun p1Layers(snapshot: MachineSnapshot, budget: ChunkReadBudget): Map<Int, DecodedP1Process> {
        val result = HashMap<Int, DecodedP1Process>()
        for (section in snapshot.sections) {
            if (section.kind != SnapshotSection.PROCESS_ABI) continue
            val (pid, _, decoded) = readAbiSection(section, budget)
            if (decoded != null && result.put(pid, decoded) != null) throw SnapshotFormatException("基準の pid $pid の PROCESS_ABI 節が重なる")
        }
        return result
    }
}
