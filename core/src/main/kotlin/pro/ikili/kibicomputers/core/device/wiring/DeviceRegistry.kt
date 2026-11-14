package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus
import java.util.IdentityHashMap

/**
 * 位置のキー [K]（接続層では、ディメンションと座標）から、デバイスの持ち主（[DeviceHolder]）を引く登録表（#129、ADR 0129）。
 *
 * - 持ち主は、読み込まれたら [register]、壊れた・アンロードしたら [unregister] する。登録のたびに新しい世代（[Entry.generation]）を
 *   振るので、壊して同じ座標に置き直したものは別の登録になる。世代は実行時だけの値で、保存しない
 * - 1つのデバイスがつながるマシンは1台だけ（[Entry.owner]。#360・#129 の排他）。持ち主の結線が手放すまで、ほかの結線はつながない
 * - 同じ実体（[DeviceHolder.exclusionKey]）の登録は、まとめて1つのデバイスとみなす（#153、ADR 0153）。どれか1つの登録を通して
 *   つないでいる間（[Group.ownerEntry]）は、同じ実体のほかの登録もつながない（他 MOD の1台が複数の位置で能力を出すとき）
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
        /** 同じ実体の登録の組（[DeviceHolder.exclusionKey] が `null` なら `null`） */
        internal val group: Group<K>?,
    ) {
        /** このデバイスをつないでいる結線。いなければ `null` */
        public var owner: MachineWiring<K>? = null
            internal set

        /** まだ登録表に載っている（[unregister] で `false`） */
        public var registered: Boolean = true
            internal set

        override fun toString(): String = "Entry($key#$generation, owner=${owner != null})"
    }

    /**
     * 同じ実体（[DeviceHolder.exclusionKey]）の登録の組（実行時だけ。保存しない）。[ownerEntry] は、組の中で今つないでいる登録
     * （[Entry.owner] と一緒に立てて、一緒に下ろす）
     */
    internal class Group<K : Any> {
        val entries = ArrayList<Entry<K>>(1)
        var ownerEntry: Entry<K>? = null
    }

    private val entries = HashMap<K, Entry<K>>()
    private val watchers = HashMap<K, ArrayList<MachineWiring<K>>>()

    // 実体（exclusionKey）→ 組。同一性で引く（他 MOD の equals を呼ばない）
    private val groups = IdentityHashMap<Any, Group<K>>()
    private var lastGeneration = 0L

    /** 載っている持ち主の数 */
    public val size: Int get() = entries.size

    /** 見張られているキーの数（テスト用） */
    public val watchedKeys: Int get() = watchers.size

    /** 実体の組の数（テスト用。外し忘れの確かめ） */
    public val groupCount: Int get() = groups.size

    /** [key] の位置の登録。なければ `null` */
    public operator fun get(key: K): Entry<K>? = entries[key]

    /**
     * [holder] を [key] の位置に載せ、[key] を見張っている結線に知らせる（すぐにはつながない。結線が次の機会に引き直す）。
     * 同じ位置に別の持ち主が載っていたら、それを先に外す。同じ持ち主がもう載っていれば、その登録を返す。
     * [DeviceHolder.exclusionKey] はここで1回だけ読む
     */
    public fun register(key: K, holder: DeviceHolder<K>): Entry<K> {
        entries[key]?.let {
            if (it.holder === holder) return it
            remove(it)
        }
        val group = holder.exclusionKey?.let { groups.getOrPut(it) { Group() } }
        val entry = Entry(key, holder, ++lastGeneration, group)
        group?.entries?.add(entry)
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
        groups.clear()
    }

    private fun remove(entry: Entry<K>) {
        entries.remove(entry.key)
        entry.registered = false
        val group = entry.group
        if (group != null) {
            group.entries.removeIf { it === entry }
            if (group.ownerEntry === entry) {
                group.ownerEntry = null
                // 同じ実体のほかの位置が空いた（見張っている結線に知らせる。持ち主の結線には下の onEntryRemoved が頼む）
                for (other in group.entries) notifyWatchers(other.key, except = entry.owner)
            }
            if (group.entries.isEmpty()) groups.values.removeIf { it === group }
        }
        val owner = entry.owner ?: return
        entry.owner = null
        owner.onEntryRemoved(entry)
    }

    /**
     * [entry] を通してつないでよければ `true`（[by] の結線から見て、ほかの結線が持っていない。同じ実体のほかの登録を
     * 通して、誰か（[by] を含む）がつないでいない）
     */
    internal fun isFreeFor(entry: Entry<K>, by: MachineWiring<K>): Boolean {
        val owner = entry.owner
        if (owner != null && owner !== by) return false
        val held = entry.group?.ownerEntry
        return held == null || held === entry
    }

    /** [by] が [entry] を通してつなぐ（[Entry.owner] と [Group.ownerEntry] を一緒に立てる） */
    internal fun claim(entry: Entry<K>, by: MachineWiring<K>) {
        entry.owner = by
        entry.group?.ownerEntry = entry
    }

    /**
     * [by] が [entry] を手放した（[Entry.owner] と [Group.ownerEntry] を一緒に下ろす）。[notify] なら、まだ載っていれば
     * [entry] のキーと、同じ実体のほかのキーを見張っているほかの結線に知らせる（空いたら次へ）
     */
    internal fun drop(entry: Entry<K>, by: MachineWiring<K>, notify: Boolean) {
        if (entry.owner === by) entry.owner = null
        val group = entry.group
        val heldGroup = group != null && group.ownerEntry === entry
        if (heldGroup) group!!.ownerEntry = null
        if (!notify) return
        if (entries[entry.key] === entry) notifyWatchers(entry.key, except = by)
        if (heldGroup) for (other in group!!.entries) if (other !== entry) notifyWatchers(other.key, except = by)
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

    private fun notifyWatchers(key: K, except: MachineWiring<K>?) {
        val list = watchers[key] ?: return
        // requestRewire は頼むだけで、見張りの表を変えない
        for (w in list) if (w !== except) w.requestRewire()
    }
}
