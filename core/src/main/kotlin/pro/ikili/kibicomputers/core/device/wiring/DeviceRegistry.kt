package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus

/**
 * 位置のキー [K]（接続層では、ディメンションと座標）から、デバイスの持ち主（[DeviceHolder]）を引く登録表（#129、ADR 0129）。
 *
 * - 持ち主は、読み込まれたら [register]、壊れた・アンロードしたら [unregister] する。登録のたびに新しい世代（[Entry.generation]）を
 *   振るので、壊して同じ座標に置き直したものは別の登録になる。世代は実行時だけの値で、保存しない
 * - 1つのデバイスがつながるマシンは1台だけ（[Entry.owner]。#360・#129 の排他）。持ち主の結線が手放すまで、ほかの結線はつながない
 * - 結線は、つなぎたい候補のキーを見張る（[MachineWiring] が出し入れする）。キーにデバイスが載った・手放されたときだけ、見張っている
 *   結線に知らせる（[MachineWiring.requestRewire]）。tick ごとの巡回はしない
 * - 外れた登録の持ち主の結線には、すぐに知らせる（[MachineWiring] がマシンから外す。#67 の `device-detached` が出る）
 *
 * 引くのは `HashMap` を1回だけで、割り当てない。スレッド: サーバーのメインスレッドからだけ使う。
 */
@ApiStatus.Internal
public class DeviceRegistry<K : Any> {
    /** 登録1つ。[key] の位置に、[generation] 番目に載った [holder] */
    public class Entry<K : Any> internal constructor(
        /** 位置のキー */
        public val key: K,
        /** デバイスの持ち主 */
        public val holder: DeviceHolder<K>,
        /** 登録の世代（登録のたびに増える。実行時だけ） */
        public val generation: Long,
    ) {
        /** このデバイスをつないでいる結線。いなければ `null` */
        public var owner: MachineWiring<K>? = null
            internal set

        /** まだ登録表に載っている（[unregister] で `false`） */
        public var registered: Boolean = true
            internal set

        override fun toString(): String = "Entry($key#$generation, owner=${owner != null})"
    }

    private val entries = HashMap<K, Entry<K>>()
    private val watchers = HashMap<K, ArrayList<MachineWiring<K>>>()
    private var lastGeneration = 0L

    /** 載っている持ち主の数 */
    public val size: Int get() = entries.size

    /** 見張られているキーの数（テスト用） */
    public val watchedKeys: Int get() = watchers.size

    /** [key] の位置の登録。なければ `null` */
    public operator fun get(key: K): Entry<K>? = entries[key]

    /**
     * [holder] を [key] の位置に載せ、[key] を見張っている結線に知らせる（すぐにはつながない。結線が次の機会に引き直す）。
     * 同じ位置に別の持ち主が載っていたら、それを先に外す。同じ持ち主がもう載っていれば、その登録を返す
     */
    public fun register(key: K, holder: DeviceHolder<K>): Entry<K> {
        entries[key]?.let {
            if (it.holder === holder) return it
            remove(it)
        }
        val entry = Entry(key, holder, ++lastGeneration)
        entries[key] = entry
        notifyWatchers(key, except = null)
        return entry
    }

    /** [entry] を外す（今の登録でなければ何もしない）。つないでいた結線は、すぐにマシンから外す */
    public fun unregister(entry: Entry<K>) {
        if (entries[entry.key] !== entry) return
        remove(entry)
    }

    /** すべて忘れる（サーバーが止まったとき。結線には知らせない） */
    public fun clear() {
        for (e in entries.values) {
            e.registered = false
            e.owner = null
        }
        entries.clear()
        watchers.clear()
    }

    private fun remove(entry: Entry<K>) {
        entries.remove(entry.key)
        entry.registered = false
        val owner = entry.owner ?: return
        entry.owner = null
        owner.onEntryRemoved(entry)
    }

    internal fun watch(key: K, wiring: MachineWiring<K>) {
        val list = watchers.getOrPut(key) { ArrayList(1) }
        if (list.none { it === wiring }) list.add(wiring)
    }

    internal fun unwatch(key: K, wiring: MachineWiring<K>) {
        val list = watchers[key] ?: return
        list.removeIf { it === wiring }
        if (list.isEmpty()) watchers.remove(key)
    }

    /** [by] が [entry] を手放した。まだ載っていれば、見張っているほかの結線に知らせる（空いたら次へ） */
    internal fun released(entry: Entry<K>, by: MachineWiring<K>) {
        if (entries[entry.key] === entry) notifyWatchers(entry.key, except = by)
    }

    private fun notifyWatchers(key: K, except: MachineWiring<K>?) {
        val list = watchers[key] ?: return
        // requestRewire は頼むだけで、見張りの表を変えない
        for (w in list) if (w !== except) w.requestRewire()
    }
}
