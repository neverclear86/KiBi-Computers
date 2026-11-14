package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonLimits
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonTraps

/**
 * コンポーネントのインスタンス 1 つのハンドル表（#265。ADR 0265。仕様の `Table` と `ResourceHandle`）。
 *
 * 番号は 1 から振り（0 番は使わない）、空いた番号は最後に空いたものから使い回す（LIFO）。どちらもゲストから見える仕様
 * （仕様のテストが番号まで比べる）なので、p1 の fd 表（[pro.ikili.kibicomputers.core.kernel.obj.HandleTable]。
 * POSIX どおり 0 から振り、いちばん小さい空きを使う）とは別の型にしてある。
 *
 * エントリはプリミティブの配列で持つ（1 つごとにオブジェクトを作らない）。1 エントリは種類（1 バイト）・正規の resource の番号・
 * rep・lends（4 バイトずつ）・borrow の範囲（8 バイト）の約 21 バイト。
 * own か borrow かは種類のタグ（[OWN]・[BORROW]）だけで決め、borrow の範囲（task の番号）の値からは推しはからない。
 * 種類のタグは、#266 が Waitable・WaitableSet などを同じ番号の空間に足せるように置いてある。
 *
 * ラムダや Java の参照は持たない（数値だけ。ADR 0260 の「保存できる形」。保存の形は #269）。
 * 1 つのプロセスのスレッドからだけ使う（ロックは付けない）
 */
internal class ResourceTable {
    private var kinds = ByteArray(INITIAL)
    private var resources = IntArray(INITIAL)
    private var reps = IntArray(INITIAL)
    private var scopes = LongArray(INITIAL)
    private var lendCounts = IntArray(INITIAL)

    /** 次に新しく振る番号（0 番は使わないので 1 から） */
    private var length = 1
    private var free = IntArray(4)
    private var freeCount = 0

    /** 使っているエントリの数 */
    var size: Int = 0
        private set

    /** own のハンドルを足し、番号を返す */
    @Throws(WasmTrapException::class)
    fun addOwn(resource: Int, rep: Int): Int = add(OWN, resource, rep, 0)

    /** borrow のハンドルを足し、番号を返す。[scope] は borrow の範囲（その borrow を受けた task の番号） */
    @Throws(WasmTrapException::class)
    fun addBorrow(resource: Int, rep: Int, scope: Long): Int = add(BORROW, resource, rep, scope)

    private fun add(kind: Byte, resource: Int, rep: Int, scope: Long): Int {
        val i = if (freeCount > 0) {
            free[--freeCount]
        } else {
            if (length > CanonLimits.MAX_TABLE_LENGTH) CanonTraps.trap("ハンドル表が満杯")
            if (length == kinds.size) grow()
            length++
        }
        kinds[i] = kind
        resources[i] = resource
        reps[i] = rep
        scopes[i] = scope
        lendCounts[i] = 0
        size++
        return i
    }

    private fun grow() {
        val n = kinds.size * 2
        kinds = kinds.copyOf(n)
        resources = resources.copyOf(n)
        reps = reps.copyOf(n)
        scopes = scopes.copyOf(n)
        lendCounts = lendCounts.copyOf(n)
    }

    /** [i] が resource のハンドル（own か borrow）か確かめる。範囲外・空き・ほかの種類ならトラップ */
    @Throws(WasmTrapException::class)
    fun valid(i: Int): Int {
        if (i <= 0 || i >= length || kinds[i] == FREE) CanonTraps.trap("ハンドル表にない番号 ${Integer.toUnsignedString(i)}")
        if (kinds[i] != OWN && kinds[i] != BORROW) CanonTraps.trap("番号 $i は resource のハンドルでない")
        return i
    }

    /** [i] の正規の resource の番号 */
    @Throws(WasmTrapException::class)
    fun resource(i: Int): Int = resources[valid(i)]

    @Throws(WasmTrapException::class)
    fun rep(i: Int): Int = reps[valid(i)]

    /** [i] が own か（種類のタグで決める。borrow の範囲の値は見ない） */
    @Throws(WasmTrapException::class)
    fun isOwn(i: Int): Boolean = kinds[valid(i)] == OWN

    /** borrow の範囲（task の番号）。own に対して呼ぶのは内部の誤り */
    @Throws(WasmTrapException::class)
    fun scope(i: Int): Long {
        check(kinds[valid(i)] == BORROW) { "own のハンドル $i の範囲を引いた（内部の誤り）" }
        return scopes[i]
    }

    /** [i] を貸している数（仕様の `num_lends`） */
    @Throws(WasmTrapException::class)
    fun lends(i: Int): Int = lendCounts[valid(i)]

    /** [i] を貸している数を [delta] だけ変える */
    @Throws(WasmTrapException::class)
    fun lend(i: Int, delta: Int) {
        val n = lendCounts[valid(i)] + delta
        check(n >= 0) { "ハンドル $i を貸している数が負になった（内部の誤り）" }
        lendCounts[i] = n
    }

    /** [i] を外し、番号を空きに積む */
    @Throws(WasmTrapException::class)
    fun remove(i: Int) {
        kinds[valid(i)] = FREE
        if (freeCount == free.size) free = free.copyOf(free.size * 2)
        free[freeCount++] = i
        size--
    }

    companion object {
        const val FREE: Byte = 0
        const val OWN: Byte = 1
        const val BORROW: Byte = 2
        private const val INITIAL = 8
    }
}

/**
 * borrow の範囲（仕様の同期の Task）の番号を振る（#265。ADR 0265）。プロセスごとに 1 から振る `Long` の通し番号で、折り返さない
 * （次の値が `Long` を超えるならトラップ）。生きている task の番号はどれも違うので、borrow の drop で数を減らす相手の task を、
 * 番号で必ず正しく見つけられる
 */
internal class TaskIds(start: Long = 1) {
    /** 次に振る番号（保存するなら、これを書く。#269） */
    var next: Long = start
        private set

    @Throws(WasmTrapException::class)
    fun take(): Long {
        val v = next
        next = try {
            Math.addExact(v, 1L)
        } catch (_: ArithmeticException) {
            CanonTraps.trap("task の番号を使い切った")
        }
        return v
    }
}
