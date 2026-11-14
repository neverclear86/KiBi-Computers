package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.kernel.Machine

/**
 * マシン1台の、位置から引くデバイスの結線（#129、ADR 0129）。ケース1つにつき1つ持つ。
 *
 * - 候補は、ケースの隣（[Host.neighborKeys]。決まった順）と、そこでつないだデバイスの中継（[DeviceHolder.relayKeys]。
 *   画面の前のキーボードなど）。候補を順に [DeviceRegistry] から引き、空いているもの（[DeviceRegistry.Entry.owner] がいない）を
 *   つなぐ（[rewire]）。つないだ順に `DeviceBus` に入るので、最初につないだ画面がコンソールの出力先になる
 * - 満杯（`DeviceBus.size >= limit`）なら、デバイスを作らずに断る（[Host.onRefused]。#66）。断ったものは、自分のデバイスが外れたときに見直す
 * - 同じマシンに同じ address のデバイスがもうつながっていたら、後から来たほう（今つなごうとしているもの）の address を振り直す
 *   （[DeviceHolder.reassignDeviceAddress]。ブロックの複製など。ADR 0065・0066 の残り）
 * - 候補のキーを登録表で見張り、そこにデバイスが載った・手放されたときに、つなぎ直しを頼む（[Host.scheduleRewire]）。
 *   tick ごとに見回らない
 * - つないだデバイスの持ち主が登録表から外れたら（壊れた・アンロードした）、すぐにマシンから外す（`device-detached` は `DeviceBus` が出す。#67）
 * - 手放すとき（[disconnect]・候補から外れた）は、見張っているほかの結線に知らせる（空いたら次へ）
 * - 同じ実体（[DeviceHolder.exclusionKey]）の登録は、どれか1つを通してだけつなぐ（#153、ADR 0153。[DeviceRegistry.isFreeFor]）
 * - 持ち主やデバイス（他 MOD のコード）が投げた例外は受けて、つながずに手放す（[Host.onAttachFailed]・[Host.onDetachFailed]。
 *   JVM が続けられないもの（[rethrowIfFatal]）だけ投げ直す）。**つなぐときに投げた登録は、この結線では同じ登録（世代）の間は
 *   もう試さない**（[failed]）。新しい登録になる・候補から外れる・[disconnect] するまで。(結線, 登録) の組ごとに、投げて失敗するのは
 *   1回までなので、失敗 → 手放し → 知らせ → 再試行が2台の間で回り続けない。満杯で断ったものは覚えない（空いたら見直す。ADR 0066）
 * - catch・finally の中と、[Host] の知らせ（[Host.onRefused]・[Host.onAttachFailed]・[Host.onDetachFailed]）には、他 MOD の
 *   コードを呼ばずに済む値（登録・読み済みの address・例外）だけを渡す
 *
 * マシンが動いているかは、呼び手が [Machine.isRunningBootingOrRebooting] で1回だけ読んで決める（#625・#626）。
 *
 * スレッド: サーバーのメインスレッドからだけ使う。`DeviceBus` の attach・detach はカーネルロックを短く取るので、
 * カーネルロックやデバイスのロックを持って呼ばない（ADR 0067）。
 */
@ApiStatus.Internal
public class MachineWiring<K : Any>(
    private val registry: DeviceRegistry<K>,
    /** 接続層の口（候補の出し元、つなぎ直しの予約） */
    public val host: Host<K>,
) {
    /** 接続層の口。どれもメインスレッドで呼ぶ */
    public interface Host<K : Any> {
        /** ケースの隣の候補（決まった順。同じリストを返してよい） */
        public fun neighborKeys(): List<K>

        /** つなぎ直し（[rewire]）を後で（tick の終わりなどに）呼ぶよう頼む。何度呼んでもよい */
        public fun scheduleRewire()

        /** [holder] を、[machine] が満杯なので断った */
        public fun onRefused(machine: Machine, holder: DeviceHolder<K>) {}

        /**
         * [entry] をつなげなかった（address・種類の形の誤り、持ち主やデバイスが投げたなど）。[address] はそれまでに読めた address
         * （読む前に投げたら `null`）。他 MOD のコード（持ち主の address の読み書きなど）を呼ばない
         */
        public fun onAttachFailed(entry: DeviceRegistry.Entry<K>, address: String?, error: Throwable) {}

        /**
         * [entry] を外すときに、持ち主かデバイスが投げた（外すのは済んでいる）。[address] はつないだときの address。
         * 他 MOD のコードを呼ばない
         */
        public fun onDetachFailed(entry: DeviceRegistry.Entry<K>, address: String, error: Throwable) {}

        /** [holder] の [device] をつないだ */
        public fun onConnected(holder: DeviceHolder<K>, device: Device) {}
    }

    private class Connection<K : Any>(val entry: DeviceRegistry.Entry<K>, val address: String, val device: Device)

    private val connections = ArrayList<Connection<K>>(4)

    // 見張っているキー（候補）
    private var watched: Set<K> = emptySet()

    // キーごとの、つなぐときに投げた登録（同じ登録の間はもう試さない。見張るキーの数まで）
    private val failed = HashMap<K, DeviceRegistry.Entry<K>>()

    /** つなぐときに投げたので、同じ登録の間はもう試さない登録の数（テスト用） */
    public val failedCount: Int get() = failed.size

    /** つないでいるマシン。なければ `null` */
    public var machine: Machine? = null
        private set

    /** つないでいるデバイスの持ち主（つないだ順） */
    public val connected: List<DeviceHolder<K>> get() = connections.map { it.entry.holder }

    /** [holder] のためにつないだデバイス。つないでいなければ `null` */
    public fun deviceOf(holder: DeviceHolder<K>): Device? = connections.firstOrNull { it.entry.holder === holder }?.device

    /** [device] をつないだ持ち主。なければ `null` */
    public fun holderOf(device: Device): DeviceHolder<K>? = connections.firstOrNull { it.device === device }?.entry?.holder

    /** [holder] をつないだときの登録（世代の確かめ用）。つないでいなければ `null` */
    public fun entryOf(holder: DeviceHolder<K>): DeviceRegistry.Entry<K>? = connections.firstOrNull { it.entry.holder === holder }?.entry

    /** つないでいるデバイスの数（このマシンの結線のもの） */
    public val size: Int get() = connections.size

    /** [machine] を受け持ち、候補をつなぐ（前のマシンは外す）。起動はしない（保存から戻したマシンにも使う） */
    public fun attach(machine: Machine) {
        disconnect()
        this.machine = machine
        rewire()
    }

    /**
     * 候補を引き直してつなぐ。候補から外れたもの（中継していた画面が外れたキーボードなど）は外す。マシンがなければ何もしない。
     * マシンが動いているかは見ない（呼び手が確かめる）
     */
    public fun rewire() {
        val m = machine ?: return
        val neighbors = host.neighborKeys()
        // 1. 届かなくなったものを先に外す（空いた枠に、続けて候補をつなげるように）
        prune(neighbors)
        // 2. 隣、続けて中継の候補を順に引く
        val seen = LinkedHashSet<K>()
        for (key in neighbors) visit(m, key, relayed = false, seen)
        var i = 0
        while (i < connections.size) {
            val c = connections[i++]
            if (c.entry.key !in neighbors) continue
            for (key in c.entry.holder.relayKeys()) visit(m, key, relayed = true, seen)
        }
        // 3. 候補を見張る（変わったところだけ出し入れする）。候補から外れたキーの失敗は忘れる
        if (failed.isNotEmpty()) failed.keys.retainAll(seen)
        if (seen != watched) {
            for (k in watched) if (k !in seen) registry.unwatch(k, this)
            for (k in seen) if (k !in watched) registry.watch(k, this)
            watched = seen
        }
    }

    /** 隣にも、隣でつないだものの中継にもなくなったもの（中継していた画面が外れたキーボードなど）を外して手放す */
    private fun prune(neighbors: List<K>) {
        if (connections.isEmpty()) return
        val reach = HashSet<K>(neighbors)
        for (c in connections) if (c.entry.key in neighbors) reach.addAll(c.entry.holder.relayKeys())
        for (c in connections.toTypedArray()) {
            if (c.entry.key !in reach || registry[c.entry.key] !== c.entry) disconnectOne(c, removed = false)
        }
    }

    private fun visit(m: Machine, key: K, relayed: Boolean, seen: MutableSet<K>) {
        if (!seen.add(key)) return
        if (connections.any { it.entry.key == key }) return
        val entry = registry[key]
        if (entry == null) {
            failed.remove(key)
            return
        }
        val lastFailed = failed[key]
        if (lastFailed != null) {
            // 同じ登録で投げたものは、もう試さない（見張りは続ける）。登録が替わったら忘れて試す
            if (lastFailed === entry) return
            failed.remove(key)
        }
        val holder = entry.holder
        if (relayed && !holder.relayable) return
        if (!registry.isFreeFor(entry, this)) return
        tryConnect(m, entry)
    }

    private fun tryConnect(m: Machine, entry: DeviceRegistry.Entry<K>) {
        val holder = entry.holder
        val bus = m.devices
        // 満杯なら、デバイスを作らずに断る（重い準備を省く。#66）
        if (bus.size >= bus.limit) {
            host.onRefused(m, holder)
            return
        }
        registry.claim(entry, this)
        var attached = false
        // 読めた address（ログに渡す。catch の中で持ち主の address を読み直さない）
        var address: String? = null
        try {
            address = holder.deviceAddress
            if (bus.deviceAt(address) != null) {
                holder.reassignDeviceAddress()
                address = holder.deviceAddress
            }
            val device = holder.createDevice()
            attached = bus.attach(address, device)
            if (!attached) {
                host.onRefused(m, holder)
                return
            }
            // つなげてから、自分の状態を確定する（断られたら何も残さない。#66）
            connections += Connection(entry, address, device)
            holder.onConnected(device)
            host.onConnected(holder, device)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            // つなげなかったら、この登録の間はもう試さない（失敗 → 手放し → 知らせ → 再試行が回り続けないように）。
            // つないだ後の知らせ（持ち主の onConnected）が投げたときは、つないだままにする
            if (!attached) failed[entry.key] = entry
            host.onAttachFailed(entry, address, t)
        } finally {
            if (!attached) registry.drop(entry, this, notify = true)
        }
    }

    /** つないだものをすべて外して手放し、見張りもやめる（電源が切れた・ケースが消える・マシンが止まった）。もう一度呼んでもよい */
    public fun disconnect() {
        for (c in connections.asReversed().toTypedArray()) disconnectOne(c, removed = false)
        for (k in watched) registry.unwatch(k, this)
        watched = emptySet()
        failed.clear()
        machine = null
    }

    /** [requestRewire] を受けて、マシンがあればつなぎ直しを頼む（登録表から呼ぶ） */
    internal fun requestRewire() {
        if (machine != null) host.scheduleRewire()
    }

    /**
     * つないでいた [entry] が登録表から外れた。すぐにマシンから外し、それを通して中継していたもの（画面の前のキーボード）も外して手放す。
     * 空いた枠（断った候補）の見直しを頼む
     */
    internal fun onEntryRemoved(entry: DeviceRegistry.Entry<K>) {
        val c = connections.firstOrNull { it.entry === entry } ?: return
        disconnectOne(c, removed = true)
        prune(host.neighborKeys())
        host.scheduleRewire()
    }

    /**
     * [c] を外して手放す。持ち主の [DeviceHolder.onDisconnected] やデバイスの `onDetached`（他 MOD のコード）が投げても、
     * 外すのと手放すのは済ませ、知らせて続ける（[disconnect] の残りも外し続ける）
     */
    private fun disconnectOne(c: Connection<K>, removed: Boolean) {
        if (!connections.remove(c)) return
        var error: Throwable? = null
        try {
            c.entry.holder.onDisconnected(c.device, removed)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            error = t
        }
        try {
            machine?.devices?.detach(c.address)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            if (error == null) error = t else error.addSuppressed(t)
        } finally {
            registry.drop(c.entry, this, notify = !removed)
        }
        if (error != null) host.onDetachFailed(c.entry, c.address, error)
    }

    override fun toString(): String = "MachineWiring(${connections.size} devices, $machine)"
}
