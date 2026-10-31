package pro.ikili.kibicomputers.core.kernel.wait

import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import java.util.concurrent.locks.Condition

/**
 * 待ち手（実行時だけ。保存しない）。
 *
 * [wake] は「起こされた」の印を立て、印が立っていなかったときだけ [onWake] を呼ぶ。
 * 印が立っている間の [wake] は何もしない（CPU の待ち行列に二重に入らない）。どれもカーネルロックの中で呼ぶ。
 */
internal abstract class Waiter {
    /** 起こされた印 */
    var woken: Boolean = false
        private set

    /** 印を立てて起こす。ブロックしない */
    fun wake() {
        if (woken) return
        woken = true
        onWake()
    }

    /** 待ち直し: 印を下ろす（登録はそのまま） */
    fun rearm() {
        woken = false
    }

    /** 起こす処理（スレッドを起こす、CPU の待ち行列に入れる）。ブロックしない */
    protected abstract fun onWake()
}

/** 止まっているスレッドを起こす待ち手（ブロッキング型の `block` 用）。[condition] はカーネルロックの条件変数。 */
internal class ThreadWaiter(val condition: Condition) : Waiter() {
    override fun onWake() = condition.signalAll()
}

/** スレッドを持たない待機点型のプロセスを、CPU の待ち行列に入れる待ち手（`StepResult.Wait` 用）。 */
internal class ProcessWaiter(private val schedule: () -> Unit) : Waiter() {
    override fun onWake() = schedule()
}

/**
 * [WaitKey] → 登録された [Waiter] の表（マシンの実行時の部分に1つ）。[WakeSink] を実装する。
 *
 * 表は最初に使うときに作る。どの操作もカーネルロックの中で行う。
 */
internal class WaitRegistry : WakeSink {
    private var table: HashMap<Int, HashMap<WaitCondition, LinkedHashSet<Waiter>>>? = null

    /** 登録の数（テスト用） */
    val size: Int get() = table?.values?.sumOf { byCondition -> byCondition.values.sumOf { it.size } } ?: 0

    fun register(key: WaitKey, waiter: Waiter) {
        val t = table ?: HashMap<Int, HashMap<WaitCondition, LinkedHashSet<Waiter>>>().also { table = it }
        t.getOrPut(key.objectId) { HashMap() }.getOrPut(key.condition) { LinkedHashSet() }.add(waiter)
    }

    fun unregister(key: WaitKey, waiter: Waiter) {
        val t = table ?: return
        val byCondition = t[key.objectId] ?: return
        val set = byCondition[key.condition] ?: return
        set.remove(waiter)
        if (set.isEmpty()) byCondition.remove(key.condition)
        if (byCondition.isEmpty()) t.remove(key.objectId)
    }

    override fun wake(key: WaitKey) {
        val set = table?.get(key.objectId)?.get(key.condition) ?: return
        set.toList().forEach(Waiter::wake)
    }

    override fun wakeObject(objectId: Int) {
        val byCondition = table?.get(objectId) ?: return
        byCondition.values.flatMap { it.toList() }.forEach(Waiter::wake)
    }
}

/**
 * 1回の待ちのための実行時だけのもの。カーネルロックの中で [register] で作る。
 *
 * 登録は、待ちが終わると決まる（そろった key が空でないのを、決める時点のカーネルロックの中で確かめた）まで外さない。
 * 起こされただけでは外さない。
 */
internal class WaitSet private constructor(val keys: List<WaitKey>, val waiter: Waiter) {
    private var registered = true

    /**
     * そろっている key（オブジェクトが見つからない key を含む）。カーネルロックの中で呼ぶ。
     * 起こされた後でも空のことがある（そのときは呼び出し側が [rearm] して待ち直す）。
     */
    fun readyKeys(objects: ObjectTable): List<WaitKey> = keys.filter { key ->
        val obj = objects[key.objectId]
        obj == null || obj.isReady(key.condition)
    }

    /** 待ち直し: [Waiter] の印を下ろす（登録は残したまま）。カーネルロックの中で呼ぶ */
    fun rearm() = waiter.rearm()

    /** 登録を外す。2回呼んでもよい。カーネルロックの中で呼ぶ */
    fun unregister(registry: WaitRegistry) {
        if (!registered) return
        registered = false
        keys.forEach { registry.unregister(it, waiter) }
    }

    companion object {
        /**
         * カーネルロックの中で作り、[registry] に [waiter] を登録する。
         *
         * [objects] は今は使わない（オブジェクトが見つからない key も登録し、[readyKeys] で「そろった」として返す）。
         * 待てない条件をエラーにするのは ABI 層（[pro.ikili.kibicomputers.core.kernel.obj.KernelObject.canWait]）。
         */
        @Suppress("UNUSED_PARAMETER")
        fun register(registry: WaitRegistry, objects: ObjectTable, keys: List<WaitKey>, waiter: Waiter): WaitSet {
            require(keys.isNotEmpty()) { "待つ条件がない" }
            val distinct = keys.distinct()
            distinct.forEach { registry.register(it, waiter) }
            return WaitSet(distinct, waiter)
        }
    }
}
