package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.world.level.Level
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.device.ScreenDevice
import pro.ikili.kibicomputers.core.device.SpeakerDevice
import pro.ikili.kibicomputers.core.device.wiring.DeviceHolder
import pro.ikili.kibicomputers.core.device.wiring.MachineWiring
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import java.util.concurrent.atomic.AtomicLong

/**
 * ケースの隣のデバイスの結線（#129、ADR 0129）。ケース1つにつき1つ持ち、サーバーでだけ使う。判断は `core` の [MachineWiring]。
 *
 * - 電源が入ったら（[connect]）・保存から戻したら（[attach]）、ケースのスピーカー（[SpeakerDevice]）をケースの address でつなぎ
 *   （隣接の仕組みの外。電源が入っている間ずっと）、隣の画面・キーボード（下・上・北・南・西・東の順）と、つないだ画面の
 *   前のキーボード（[ScreenBlockEntity.relayKeys]）を、登録表（[DeviceWiring.registry]）から引いてつなぐ
 * - デバイスは1台のマシンにだけつながる（排他）。ほかのケースが持っているものはつながず、手放されたら tick の終わりにつなぎ直す
 * - 隣にデバイスが載った（置いた・チャンクを読み込んだ）・手放された・自分のデバイスが外れたら、tick の終わりにつなぎ直す
 *   （[DeviceWiring.schedule] → [rewire]）。つなぎ直すのは、ケースの今のマシンがこの結線のマシンで、止まっていない
 *   （[Machine.isRunningBootingOrRebooting]。再起動の途中も含む。#626）ときだけ
 * - マシンが自分で止まったら（CRASHED、ゲストが止めた、起動に失敗した）、ケースがマシンを外し、結線も外す（[disconnect]。#640・#641）
 *
 * スレッド: メインスレッドから呼ぶ（マシンの通知も、キーボードの入力もメインスレッドで届く）。
 *
 * @param current ケースの今のマシン（[rewire] で、この結線のマシンかを確かめる）。`null` なら確かめない（テスト用）
 */
internal class CaseWiring(private val current: (() -> Machine?)? = null) : MachineHost, MachineWiring.Host<GlobalPos> {
    private val core = MachineWiring(DeviceWiring.registry, this)

    private var neighbors: List<GlobalPos> = emptyList()
    private var speakerAddress: String? = null

    /** 今つないでいるマシン（テスト用） */
    val connectedMachine: Machine? get() = core.machine

    /** コンソールの出力先の画面（最初につないだ画面）。つないでいなければ null */
    val connectedScreen: ScreenBlockEntity?
        get() = core.machine?.devices?.primaryScreen?.let { core.holderOf(it) as? ScreenBlockEntity }

    /** [connectedScreen] のためにつないだ画面のデバイス。つないでいなければ null（テスト用） */
    val connectedScreenDevice: ScreenDevice? get() = core.machine?.devices?.primaryScreen?.takeIf { core.holderOf(it) != null }

    /** 最初につないだキーボード。つないでいなければ null */
    val connectedKeyboard: KeyboardBlockEntity? get() = core.connected.firstNotNullOfOrNull { it as? KeyboardBlockEntity }

    /** つないでいる画面・キーボード（つないだ順。テスト用） */
    val connected: List<DeviceHolder<GlobalPos>> get() = core.connected

    /** [holder] のためにつないだデバイス（テスト用） */
    fun deviceOf(holder: DeviceHolder<GlobalPos>): Device? = core.deviceOf(holder)

    /** [holder] をつないだときの登録の世代（テスト用）。つないでいなければ null */
    fun generationOf(holder: DeviceHolder<GlobalPos>): Long? = core.entryOf(holder)?.generation

    /**
     * 画面をマシンにつないだ回数（つなぎ直しも数える。つないですぐ外れても残る。テスト用）。
     * 「つながなかった」ことは、ある時点で `connectedScreen == null` と読むだけだと、つないですぐ外れたものを見逃すので、これで確かめる
     */
    var screenAttachCount: Int = 0
        private set

    /** スピーカーに届いたビープの数（音はまだ鳴らさない。テスト用） */
    val beeps: AtomicLong = AtomicLong()

    /** 今つないでいるマシンの起動の失敗（[onBootFailed]）。失敗していなければ null（テスト用） */
    var bootFailure: BootFailure? = null
        private set

    /** 満杯で断られたことをログに出した回数。同じ結線で続けて断られても1回だけ出す（電源を入れ直すと戻す。テスト用） */
    var limitLogCount: Int = 0
        private set

    /** 電源が入った（[machine] を作った）。デバイスをつなぎ（[attach]）、[boot] の候補から起動する */
    fun connect(level: Level, casePos: BlockPos, machine: Machine, speakerAddress: String, boot: BootConfig = ProvisionalBoot.BOOT_CONFIG) {
        attach(level, casePos, machine, speakerAddress)
        machine.boot(boot)
    }

    /**
     * [machine] にケースのスピーカーと、隣のデバイスをつなぐ（起動はしない）。保存から戻したマシン（#85）は、戻し終えてから
     * これでつなぐ（画面の大きさは、そこでコンソールに渡る）。前につないでいたものは外す
     */
    fun attach(level: Level, casePos: BlockPos, machine: Machine, speakerAddress: String) {
        disconnect()
        bootFailure = null
        limitLogCount = 0
        neighbors = Direction.entries.map { DeviceWiring.keyOf(level, casePos.relative(it)) }
        if (machine.devices.attach(speakerAddress, SpeakerDevice { _, _ -> beeps.incrementAndGet() })) {
            this.speakerAddress = speakerAddress
        } else {
            logRefused(machine, "speaker")
        }
        core.attach(machine)
    }

    /**
     * ケースの今のマシン（[current]）がこの結線のマシンで、止まっていない（[MachineState.RUNNING]、起動の途中、再起動の途中。
     * [Machine.isRunningBootingOrRebooting]）なら `true`。状態と印は別々に読まない（読む順番に意味がある。#625・#626）
     */
    fun wantsRewire(current: Machine?): Boolean {
        val m = core.machine ?: return false
        return current === m && m.isRunningBootingOrRebooting
    }

    /** 隣と中継の候補を引き直してつなぐ（[wantsRewire] のときだけ）。[DeviceWiring] が tick の終わりに呼ぶ */
    fun rewire() {
        val m = core.machine ?: return
        if (!wantsRewire(current?.invoke() ?: m)) return
        core.rewire()
    }

    /** 電源が切れた・ケースが消える・マシンが止まった。マシンからすべてのデバイス（画面・キーボード・スピーカー）を外し、手放す */
    fun disconnect() {
        val m = core.machine
        core.disconnect()
        speakerAddress?.let { m?.devices?.detach(it) }
        speakerAddress = null
        neighbors = emptyList()
    }

    private fun logRefused(machine: Machine, name: String) {
        if (limitLogCount > 0) return
        limitLogCount++
        LOGGER.info("Device limit ({}) reached; not attaching the {}", machine.devices.limit, name)
    }

    // ---- MachineWiring.Host ----

    override fun neighborKeys(): List<GlobalPos> = neighbors

    override fun scheduleRewire() = DeviceWiring.schedule(this)

    override fun onRefused(machine: Machine, holder: DeviceHolder<GlobalPos>) = logRefused(machine, nameOf(holder))

    override fun onAttachFailed(holder: DeviceHolder<GlobalPos>, error: IllegalArgumentException) {
        LOGGER.warn("Could not attach the {} ({})", nameOf(holder), holder.deviceAddress, error)
    }

    override fun onConnected(holder: DeviceHolder<GlobalPos>, device: Device) {
        if (holder is ScreenBlockEntity) screenAttachCount++
    }

    private fun nameOf(holder: DeviceHolder<GlobalPos>): String = when (holder) {
        is ScreenBlockEntity -> "screen"
        is KeyboardBlockEntity -> "keyboard"
        else -> holder.toString()
    }

    // ---- MachineHost ----

    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

    override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
        if (machine === core.machine) bootFailure = failure
    }

    // 画面のデバイスをつないでいないときのコンソールの出力。映す画面がないので捨てる
    override fun onConsoleOutput(machine: Machine, bytes: ByteArray) = Unit

    companion object {
        private val LOGGER = LogUtils.getLogger()
    }
}
