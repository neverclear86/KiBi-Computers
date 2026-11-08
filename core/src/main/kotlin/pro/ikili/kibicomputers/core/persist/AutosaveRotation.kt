package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus

/**
 * 自動保存で、台を止めて書く相手（接続層が実装する。#81）。
 *
 * @param T 接続層の台の参照（ブロックエンティティなど）
 */
@ApiStatus.Internal
public interface AutosaveTarget<T> {
    /** [machine] を止める。止められない（マシンがない・電源が切れた）なら `null`（その台は今回の周期では終わり） */
    public fun quiesce(machine: T): QuiesceHandle?

    /** 止めるときの書き込みの回数の見積もり。ID があれば 1、ID を振る（`owner.bin` を書く）台は 2 */
    public fun plannedWrites(machine: T): Int

    /**
     * [handle] が [SaveReadiness.READY] なら取り出して書き、実際の書き込みの回数（失敗したときは試した回数）を返す。
     * [SaveReadiness.READY] でなければ [NOT_READY]。書いた・失敗した台は、この後 [AutosaveRotation] が `close` する
     */
    public fun writeIfReady(machine: T, handle: QuiesceHandle): Int

    public companion object {
        /** [writeIfReady] の「READY でない」 */
        public const val NOT_READY: Int = -1
    }
}

/**
 * 自動保存を回して書く列（#81、ADR 0081）。MC 非依存。メインスレッドだけで使う。
 *
 * 全台を一度に止めず、tick ごとの書き込み（fsync）の回数の予算 [MachineSaveConfig.autosaveWritesPerTick] で回す。毎 [tick]:
 *
 * 1. **書く**: 止めている台を止めた順に見て、[SaveReadiness.READY] のものを予算が尽きるまで書く。予算の足りない READY の台は
 *    止めたまま次の tick に回す（飛ばさない）。後ろの台が先に書けるよう追い越すのは、前の台が READY でないときだけ
 * 2. **飛ばす**: 止めてから [MachineSaveConfig.autosaveQuiesceTicks] tick 経っても READY にならない台は `close` して飛ばし、
 *    次の周期の先頭に回す
 * 3. **止める**: 止めている台の書き込みの回数の合計が「予算 × autosaveQuiesceTicks」を超えないように、列から次の台を止める
 *
 * その tick の残りの予算が 1 以上なら次の台を書き始め、超えた分は次の tick の予算から引く（1 tick の書き込みは最大で予算 + 1）。
 *
 * @param T 接続層の台の参照。`equals`・`hashCode` で同じ台を見分ける
 */
@ApiStatus.Internal
public class AutosaveRotation<T : Any>(private val config: MachineSaveConfig) {
    private class Quiesced(val handle: QuiesceHandle, val planned: Int) {
        var age: Int = 0
    }

    /** まだ止めていない台（今の周期） */
    private val pending = ArrayDeque<T>()

    /** 止めている台（止めた順） */
    private val quiesced = LinkedHashMap<T, Quiesced>()

    /** 次の周期の先頭に回す台（飛ばした・周期の中で書けなかった） */
    private val carry = LinkedHashSet<T>()

    /** 前の tick で予算を超えた分 */
    private var debt = 0

    /** 最後の [tick] で書いた回数（数え口） */
    public var lastTickWrites: Int = 0
        private set

    /** 今の周期で書いた台の数（数え口） */
    public var writtenInCycle: Int = 0
        private set

    /** 今の周期で飛ばした台の数（数え口） */
    public var skippedInCycle: Int = 0
        private set

    /** 今の周期の tick の数（[startCycle] から、[isCycleDone] になるまで。数え口） */
    public var cycleTicks: Int = 0
        private set

    /** 止めている台の書き込みの回数の合計（テスト用） */
    public val quiescedWrites: Int get() = quiesced.values.sumOf { it.planned }

    /** 止めている台の数 */
    public val quiescedCount: Int get() = quiesced.size

    /** 今の周期の列を書き終えた・飛ばし終えた */
    public val isCycleDone: Boolean get() = pending.isEmpty() && quiesced.isEmpty()

    /**
     * 新しい周期を始める。[machines] を列にする（前の周期で書けなかった・飛ばした台を先頭に）。
     * 前の周期がまだ終わっていなければ、止めている台を `close` し、残りの台を次の周期の先頭に回してから始める
     */
    public fun startCycle(machines: List<T>) {
        for ((machine, q) in quiesced) {
            q.handle.close()
            carry += machine
        }
        quiesced.clear()
        carry += pending
        pending.clear()
        val present = LinkedHashSet(machines)
        for (machine in carry) if (machine in present) pending += machine
        carry.clear()
        for (machine in present) if (machine !in pending) pending += machine
        writtenInCycle = 0
        skippedInCycle = 0
        cycleTicks = 0
    }

    /** 1 tick 分すすめる（書く → 飛ばす → 止める の順） */
    public fun tick(target: AutosaveTarget<T>) {
        lastTickWrites = 0
        if (isCycleDone) return
        cycleTicks++
        for (q in quiesced.values) q.age++
        var budget = config.autosaveWritesPerTick - debt
        debt = 0
        // 1. 書く
        val iterator = quiesced.entries.iterator()
        while (iterator.hasNext()) {
            val (machine, q) = iterator.next()
            if (q.handle.readiness != SaveReadiness.READY) continue
            // READY の台は追い越さない（予算が尽きたら、後ろの台も次の tick）
            if (budget <= 0) break
            val writes = target.writeIfReady(machine, q.handle)
            if (writes == AutosaveTarget.NOT_READY) continue
            budget -= writes
            lastTickWrites += writes
            writtenInCycle++
            q.handle.close()
            iterator.remove()
        }
        if (budget < 0) debt = -budget
        // 2. 飛ばす
        val skip = quiesced.entries.iterator()
        while (skip.hasNext()) {
            val (machine, q) = skip.next()
            if (q.age < config.autosaveQuiesceTicks || q.handle.readiness == SaveReadiness.READY) continue
            q.handle.close()
            skip.remove()
            carry += machine
            skippedInCycle++
        }
        // 3. 止める
        val cap = config.autosaveWritesPerTick.toLong() * config.autosaveQuiesceTicks
        var held = quiescedWrites.toLong()
        while (pending.isNotEmpty()) {
            val machine = pending.first()
            val planned = target.plannedWrites(machine).coerceAtLeast(1)
            if (held + planned > cap && quiesced.isNotEmpty()) break
            pending.removeFirst()
            val handle = target.quiesce(machine) ?: continue
            quiesced[machine] = Quiesced(handle, planned)
            held += planned
        }
    }

    /**
     * [machine] を列から外す（アンロード・`REMOVED`）。止めていれば、その [QuiesceHandle] を返す（`close` せずに、アンロードの手順に渡す）
     */
    public fun remove(machine: T): QuiesceHandle? {
        pending.remove(machine)
        carry.remove(machine)
        return quiesced.remove(machine)?.handle
    }

    /** すべての台を `close` して列を空にする（サーバーの停止） */
    public fun clear() {
        for (q in quiesced.values) q.handle.close()
        quiesced.clear()
        pending.clear()
        carry.clear()
        debt = 0
    }

    override fun toString(): String = "AutosaveRotation(pending=${pending.size}, quiesced=${quiesced.size}, carry=${carry.size})"
}
