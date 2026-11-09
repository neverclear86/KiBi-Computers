package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.kernel.process.AsyncStack
import pro.ikili.kibicomputers.core.kernel.process.AsyncWait
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.ModuleSource
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState

/** 読んだ p1 の層の中身（#82。#85 がプロセスに戻す） */
internal class DecodedP1Process(
    val moduleSource: ModuleSource,
    val loadedHash: ModuleHash,
    val entry: Int,
    val instance: WasmInstanceState?,
    val asyncStack: LongArray,
    val asyncWait: AsyncWait?,
)

/**
 * `PROCESS_ABI` 節の p1 の層（層 1、版 1。#82、ADR 0082 の 5.）の中身。
 *
 * モジュールの参照（元のハッシュ 32 バイト・後のハッシュ 32 バイト・計測したか・止まりうる import の集合・変換器の版・段1で変換できたか）、
 * 入口（u8。[ENTRY_NO_INSTANCE]・[ENTRY_START]・[ENTRY_REWIND]）、インスタンスの状態（入口が 0 でないとき。[InstanceStateCodec]）、
 * 退避した値（u32 + i64）、`AsyncWait`（bool + タイマーの key（bool + objectId・条件）+ monotonicBase + realtimeBase（bool + i64））
 */
internal object P1SectionCodec {
    /** インスタンスがまだない（`CREATED`） */
    const val ENTRY_NO_INSTANCE: Int = 0

    /** `_start` をまだ呼んでいない（退避した値がない） */
    const val ENTRY_START: Int = 1

    /** 巻き直して再開する（退避した値がある） */
    const val ENTRY_REWIND: Int = 2

    /** import の名前のバイト数の上限（読むとき） */
    private const val MAX_IMPORT_NAME = 4096

    fun write(
        source: ModuleSource,
        loadedHash: ModuleHash,
        instance: WasmInstanceState?,
        asyncStack: LongArray,
        asyncWait: AsyncWait?,
    ): ByteArray {
        val out = SnapshotWriter(256 + (instance?.writtenChunkCount ?: 0) * (4 + WasmInstanceState.CHUNK_BYTES))
        out.raw(source.originalHash)
        out.raw(loadedHash.bytes())
        out.bool(source.metered)
        out.count(source.waitImports.size)
        for (name in source.waitImports) out.string(name)
        out.i32(source.instrumentationVersion)
        out.bool(source.fuelYield)
        val entry = when {
            instance == null -> ENTRY_NO_INSTANCE
            asyncStack.isEmpty() -> ENTRY_START
            else -> ENTRY_REWIND
        }
        out.u8(entry)
        if (instance != null) InstanceStateCodec.write(out, instance)
        out.count(asyncStack.size)
        for (v in asyncStack) out.i64(v)
        out.bool(asyncWait != null)
        if (asyncWait != null) {
            val timer = asyncWait.timer
            out.bool(timer != null)
            if (timer != null) {
                out.i32(timer.objectId)
                out.u8(KernelSectionCodec.conditionCode(timer.condition))
            }
            out.i64(asyncWait.monotonicBase)
            val rb = asyncWait.realtimeBase
            out.bool(rb != null)
            if (rb != null) out.i64(rb)
        }
        return out.toByteArray()
    }

    @Throws(SnapshotFormatException::class)
    fun read(payload: ByteArray): DecodedP1Process {
        val input = SnapshotReader(payload)
        val original = input.raw(ModuleHash.SIZE)
        val loaded = ModuleHash.fromBytes(input.raw(ModuleHash.SIZE))
        val metered = input.bool()
        val importCount = input.count(Int.MAX_VALUE, 4)
        val imports = List(importCount) { input.string(MAX_IMPORT_NAME) }
        if (imports.toSet().size != imports.size || imports != imports.sorted()) {
            throw SnapshotFormatException("止まりうる import の並びが不正")
        }
        val version = input.i32()
        if (version < 0) throw SnapshotFormatException("変換器の版 $version が不正")
        val fuelYield = input.bool()
        val source = ModuleSource(original, metered, imports, version, fuelYield)
        val entry = input.u8()
        if (entry > ENTRY_REWIND) throw SnapshotFormatException("入口 $entry が不正")
        val instance = if (entry == ENTRY_NO_INSTANCE) null else InstanceStateCodec.read(input)
        val stackSize = input.count(AsyncStack.MAX_VALUES, 8)
        val stack = LongArray(stackSize) { input.i64() }
        if ((entry == ENTRY_REWIND) != stack.isNotEmpty()) {
            throw SnapshotFormatException("入口 $entry と退避した値の数 $stackSize が合わない")
        }
        val wait = if (input.bool()) {
            val timer = if (input.bool()) WaitKey(input.i32(), KernelSectionCodec.conditionOf(input.u8())) else null
            val monotonic = input.i64()
            val realtime = if (input.bool()) input.i64() else null
            AsyncWait(timer, monotonic, realtime)
        } else {
            null
        }
        input.expectEnd()
        return DecodedP1Process(source, loaded, entry, instance, stack, wait)
    }
}
