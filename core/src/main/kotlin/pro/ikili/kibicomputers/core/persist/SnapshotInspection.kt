package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotDecoder
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
        // 戻すとき（#85。MachineRestore）と同じ読み手
        val decoded = SnapshotDecoder.decode(snapshot, maxProcesses, maxMemoryPages, base)
        val processes = decoded.kernel.processes.all().sortedBy { it.pid }.map { p ->
            val layer = decoded.layers[p.pid]
            val p1 = decoded.p1[p.pid]
            InspectedProcess(p.pid, p.args, p.moduleHash.bytes(), layer, p1?.moduleSource?.originalHash, p1?.loadedHash?.bytes())
        }
        return SnapshotContents(decoded.kernel.initPid, processes)
    }
}
