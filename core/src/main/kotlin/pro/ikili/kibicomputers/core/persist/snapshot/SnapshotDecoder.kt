package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.kernel.process.ModuleSource
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.StoredSnapshot

/**
 * 復号した保存データ（#85）。[SnapshotDecoder.decode] が返す。`SnapshotInspection.inspect` と復元（`MachineRestore`）が同じものを使う
 *
 * @property kernel `KERNEL` 節
 * @property p1 pid → p1 の層（差分の世代なら基準と合成済みで、`changedOnly` でない）
 * @property layers pid → `PROCESS_ABI` 節の層の ID
 * @property tmpfs マウントの ID → `TMPFS` 節
 */
internal class DecodedSnapshot(
    val kernel: DecodedKernelState,
    val p1: Map<Int, DecodedP1Process>,
    val layers: Map<Int, Int>,
    val tmpfs: Map<Int, DecodedTmpfs>,
)

/**
 * 保存データの読み手（#85）。`SnapshotInspection.inspect` の本体を「復号した結果を返す」形に切り出したもの。
 * 戻すときと確かめるときで読み手を1つにする（ADR 0085）
 */
internal object SnapshotDecoder {
    /**
     * [snapshot] を読み、確かめる（確かめることは `SnapshotInspection.inspect` の KDoc）。冷えた起動の写し・確かめられないものは
     * [SnapshotFormatException]。差分の世代なら [base]（基準）が要る
     */
    @Throws(SnapshotFormatException::class)
    fun decode(snapshot: MachineSnapshot, maxProcesses: Int, maxMemoryPages: Int, base: StoredSnapshot?): DecodedSnapshot {
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
        val p1 = HashMap<Int, DecodedP1Process>()
        val layers = HashMap<Int, Int>()
        for (section in snapshot.sections) {
            if (section.kind != SnapshotSection.PROCESS_ABI) continue
            val (pid, layer, decoded) = readAbiSection(section, budget)
            val process = kernel.processes[pid] ?: throw SnapshotFormatException("PROCESS_ABI 節の pid $pid が KERNEL 節にない")
            if (layers.put(pid, layer) != null) throw SnapshotFormatException("pid $pid の PROCESS_ABI 節が重なる")
            if (decoded == null) continue
            if (decoded.loadedHash != process.moduleHash) {
                throw SnapshotFormatException("pid $pid の後のハッシュが KERNEL 節と合わない")
            }
            val instance = decoded.instance
            val full = if (instance != null && instance.changedOnly) {
                val bl = baseLayers ?: throw SnapshotFormatException("pid $pid が差分の形なのに DELTA_BASE 節がない")
                val baseProcess = bl[pid] ?: throw SnapshotFormatException("pid $pid が基準にない")
                val baseInstance = baseProcess.instance ?: throw SnapshotFormatException("pid $pid の基準にインスタンスがない")
                if (baseInstance.changedOnly) throw SnapshotFormatException("pid $pid の基準が差分の形")
                if (!baseProcess.moduleSource.originalHash.contentEquals(decoded.moduleSource.originalHash) ||
                    baseProcess.loadedHash != decoded.loadedHash
                ) {
                    throw SnapshotFormatException("pid $pid のモジュールが基準と違う")
                }
                val merged = InstanceStateMerge.merge(baseInstance, instance, budget.limitChunks)
                mergedBudget.take(merged.writtenChunkCount, merged.memoryPages)
                DecodedP1Process(decoded.moduleSource, decoded.loadedHash, decoded.entry, merged, decoded.asyncStack, decoded.asyncWait)
            } else {
                if (instance != null) mergedBudget.take(instance.writtenChunkCount, maxOf(instance.memoryPages, 0))
                decoded
            }
            p1[pid] = full
        }
        for (p in kernel.processes.all()) {
            if (!p.state.isTerminal && p.pid !in layers) {
                throw SnapshotFormatException("終わっていない pid ${p.pid} に PROCESS_ABI 節がない")
            }
        }
        val tmpfs = HashMap<Int, DecodedTmpfs>()
        for (section in snapshot.sections) {
            if (section.kind != SnapshotSection.TMPFS) continue
            if (section.version != SnapshotSection.TMPFS_VERSION) throw SnapshotFormatException("TMPFS 節の版 ${section.version}")
            val decoded = TmpfsSectionCodec.read(section.payloadUnsafe())
            if (tmpfs.put(decoded.mountId, decoded) != null) throw SnapshotFormatException("マウント ${decoded.mountId} の TMPFS 節が重なる")
        }
        return DecodedSnapshot(kernel, p1, layers, tmpfs)
    }

    /**
     * 復号する前に、`PROCESS_ABI` 節の p1 の層が参照するモジュールを、節の順に読む（#577。重複は除かない）。
     * 各節の先頭（pid・層・モジュールの参照）だけを読み、インスタンスの状態は読まない（区画を展開しない）。
     * 節の数が [maxProcesses] を超えるものは [SnapshotFormatException]（[decode] も必ず断る形）。
     * ここで断れるものは [decode] も断るので、先に呼んでも冷えた起動の理由は変わらない
     */
    @Throws(SnapshotFormatException::class)
    fun moduleSources(snapshot: MachineSnapshot, maxProcesses: Int): List<ModuleSource> {
        if (snapshot.requiresColdBoot) throw SnapshotFormatException("冷えた起動の写し（理由 ${snapshot.coldBootCode}）")
        val result = ArrayList<ModuleSource>()
        var count = 0
        for (section in snapshot.sections) {
            if (section.kind != SnapshotSection.PROCESS_ABI) continue
            if (++count > maxProcesses) throw SnapshotFormatException("PROCESS_ABI 節が $maxProcesses 個より多い")
            if (section.version != SnapshotSection.PROCESS_ABI_VERSION) {
                throw SnapshotFormatException("PROCESS_ABI 節の版 ${section.version}")
            }
            val input = SnapshotReader(section.payloadUnsafe())
            input.i32()
            val layer = input.u16()
            val layerVersion = input.u16()
            if (layer == SnapshotSection.ABI_LAYER_P1) result += P1SectionCodec.readModuleSource(input.raw(input.remaining), layerVersion)
        }
        return result
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
