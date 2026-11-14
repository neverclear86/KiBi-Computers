package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.capabilities.BlockCapabilityCache
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.device.DeviceAddress
import pro.ikili.kibicomputers.core.device.wiring.ExternalDeviceHolder
import pro.ikili.kibicomputers.core.device.wiring.ExternalDeviceSlots
import pro.ikili.kibicomputers.core.device.wiring.rethrowIfFatal
import pro.ikili.kibicomputers.neoforge.api.KibiCapabilities
import java.util.UUID

/**
 * ケースの隣の6面の、他 MOD のデバイスの能力（[KibiCapabilities.DEVICE]）の見張り（#153、ADR 0153）。ケースの結線（[CaseWiring]）が
 * 1つ持ち、電源が入っている間だけ使う。
 *
 * - 面ごとに [BlockCapabilityCache] を持つ（強い参照を持たないと GC で消え、知らせが来なくなる）。知らせ（無効化）の中では、
 *   つなぎ直しを頼む（[onInvalidated]）だけで、問い合わせない
 * - [refresh]（つなぎ直しの前）で6面すべての `getCapability()` を呼ぶ（キャッシュが有効な間は provider を呼ばない。
 *   呼び直さないと次の知らせが来ない）。面ごとに前に見た実体（[lastSeen]）と同一性（`===`）で比べ、変わったときだけ
 *   登録表への出し入れ（[ExternalDeviceSlots]）をする。同じ実体なら、無効化されてもつなぎ直さない
 * - provider（他 MOD のコード）が投げた面は、デバイスなしとみなす（載せていれば手放す）。ログは面ごとに1回。
 *   投げた面のキャッシュは無効のまま残るので、次に試すのは、ほかの理由のつなぎ直しか、電源の入れ直し
 *
 * スレッド: サーバーのメインスレッドからだけ使う。
 */
// watcher は、ケースの結線（MachineWiring.host）。登録表のアダプターは、出した結線だけがつなげる
internal class CapabilityNeighbors(private val watcher: Any, private val onInvalidated: Runnable) {
    private val caches = arrayOfNulls<BlockCapabilityCache<Device, Direction?>>(FACES.size)
    private val keys = arrayOfNulls<GlobalPos>(FACES.size)
    private val lastSeen = arrayOfNulls<Device>(FACES.size)
    private val logged = BooleanArray(FACES.size)

    // 見張りの代。止めたら上げて、古いキャッシュの知らせを止める（isValid が false を返したキャッシュは NeoForge が捨てる）
    private var epoch = 0
    private var active = false
    private var level: ServerLevel? = null

    // アダプターを作る（その位置にブロックエンティティがあればデータ添付、なければ座標）。refresh の try の中で呼ばれる
    private val factory = ExternalDeviceSlots.Factory<GlobalPos> { key, device ->
        val be = level?.getBlockEntity(key.pos())
        ExternalDeviceHolder(key, device, if (be != null) AttachmentAddress(be) else CoordinateAddress(key))
    }

    /** provider が投げた回数（テスト用） */
    var failures: Int = 0
        private set

    /** [face] の面で今見ている他 MOD のデバイス（テスト用） */
    fun seen(face: Direction): Device? = lastSeen[face.ordinal]

    /** [casePos] の隣の6面を見張り始める（前の見張りはやめる）。サーバーのレベルでなければ何もしない */
    fun start(level: Level, casePos: BlockPos) {
        stop()
        val server = level as? ServerLevel ?: return
        active = true
        this.level = server
        val mine = ++epoch
        for (face in FACES) {
            val pos = casePos.relative(face)
            keys[face.ordinal] = DeviceWiring.keyOf(level, pos)
            caches[face.ordinal] = BlockCapabilityCache.create(
                KibiCapabilities.DEVICE,
                server,
                pos,
                face.opposite,
                { active && epoch == mine },
                onInvalidated,
            )
        }
    }

    /** 6面を問い合わせ直し、変わった面だけ登録表に出し入れする（つなぎ直しの前に呼ぶ） */
    fun refresh() {
        if (!active) return
        for (i in FACES.indices) refreshFace(i)
    }

    private fun refreshFace(i: Int) {
        val cache = caches[i] ?: return
        val key = keys[i] ?: return
        try {
            val now = cache.getCapability()
            val before = lastSeen[i]
            if (now === before) return
            lastSeen[i] = now
            if (now == null) {
                DeviceWiring.externalSlots.release(key, watcher)
            } else {
                DeviceWiring.externalSlots.offer(key, watcher, now, factory)
            }
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            failures++
            if (lastSeen[i] != null) {
                lastSeen[i] = null
                DeviceWiring.externalSlots.release(key, watcher)
            }
            if (!logged[i]) {
                logged[i] = true
                LOGGER.warn("The device capability at {} threw; treating it as no device", key, t)
            }
        }
    }

    /** 見張りをやめ、載せていたアダプターを手放す（電源が切れた・マシンが止まった） */
    fun stop() {
        active = false
        level = null
        epoch++
        for (i in FACES.indices) {
            val key = keys[i]
            if (lastSeen[i] != null && key != null) DeviceWiring.externalSlots.release(key, watcher)
            lastSeen[i] = null
            caches[i] = null
            keys[i] = null
            logged[i] = false
        }
    }

    /** ブロックエンティティのデータ添付に置く address（[DeviceAttachments.DEVICE_ADDRESS]） */
    private class AttachmentAddress(private val be: BlockEntity) : ExternalDeviceHolder.AddressStore {
        override fun read(): String = be.getExistingDataOrNull(DeviceAttachments.DEVICE_ADDRESS)?.takeIf(DeviceAddress::isValid) ?: assign()

        override fun reassign() {
            assign()
        }

        private fun assign(): String = DeviceAddress.random().also {
            be.setData(DeviceAttachments.DEVICE_ADDRESS, it)
            be.setChanged()
        }
    }

    /** ブロックエンティティがないときの、座標から決めた address（保存しない。振り直せないので、重なったらつながない） */
    private class CoordinateAddress(private val key: GlobalPos) : ExternalDeviceHolder.AddressStore {
        private val address = coordinateAddress(key)

        override fun read(): String = address

        override fun reassign() = throw IllegalStateException("the device at $key has no block entity to store a new address")
    }

    companion object {
        private val LOGGER = LogUtils.getLogger()

        /** 見る順（下・上・北・南・西・東。ADR 0129） */
        private val FACES = Direction.entries.toTypedArray()

        /** 位置 [key] の、ブロックエンティティがないデバイスの address（ディメンションと座標から決まる） */
        fun coordinateAddress(key: GlobalPos): String {
            val p = key.pos()
            return UUID.nameUUIDFromBytes("${key.dimension().location()}|${p.x}|${p.y}|${p.z}".toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
