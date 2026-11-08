package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.InstalledParts
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.device.KeyboardDevice
import pro.ikili.kibicomputers.core.device.ScreenDevice
import pro.ikili.kibicomputers.core.device.SpeakerDevice
import pro.ikili.kibicomputers.core.display.DisplayDevice
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardListener
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * テスト用コンピューターの仮の結線（#307）。ケース1つにつき1つ持ち、サーバーでだけ使う。
 *
 * - 起動部品は挿さっているとみなす（[withBootPart]）。置き換え先: 起動部品のアイテム #125、ファームウェア #99
 * - 起動ディスクの候補は、ディスクのスロットの順のディスク（#124）、最後に標準 OS の同梱イメージ（[OS_BOOT_DISK]。#461）
 *   （[bootConfig]）。同梱イメージから起動したら、最初のディスクを `/home` にマウントする（[BootConfig.homeDisk]）。
 *   置き換え先: OS 入りのフロッピーから HDD へのインストール #107・#145、起動部品の設定 #99
 * - 電源を入れたときに、隣の画面とキーボードを決め打ちで探し（[findScreen]・[findKeyboard]）、
 *   表示デバイス（[DisplayDevice]）を作って画面に映させ、画面とキーボードのデバイス（[ScreenDevice]・[KeyboardDevice]。#65）を
 *   それぞれのブロックの address でマシンにつなぐ（コンソールの出力は表示デバイスに、キーボードの入力はコンソールに流れる。
 *   プロセスの出力は、プロセスのスレッドが表示デバイスに書く。#355）。
 *   ケースに入っているスピーカー（[SpeakerDevice]）は、ケースの address で電源が入っている間ずっとつなぐ
 *   （音はまだ鳴らさず数えるだけ。[beeps]）。
 * - つないだ画面が外れたら、画面とキーボードだけを外す（#397）。マシンとスピーカーはつないだまま（#531）
 * - 画面をつないでいない間に、ケースの隣のブロックが変わったら（[CaseBlockEntity.neighborChanged]）、隣の画面と
 *   キーボードを探し直してつなぐ（[reconnectScreen]。#531）。置いた画面のデータ（address）を読み込んだ後につなぐよう、
 *   次の tick に回す。マシンが自分で止まっていれば（CRASHED、ゲストが止めた）つながない（#535）。
 *   つないだときの address を覚えておき、外すときはそれで外す。隣のブロックの更新で気付くので、画面の置いてある
 *   チャンクが読み込み直されたときなど、ケースの隣のブロックの更新が起きないときはつなぎ直さない。
 *   画面をつないでいる間に置いたキーボードも見ない
 *   キーボードがすでに別のケースにつながっていれば、つながない（[KeyboardBlockEntity.claim]）
 *   置き換え先: 隣接デバイスの検出と接続 #129、画面・キーボードのデバイス #65・#66
 *
 * スレッド: メインスレッドから呼ぶ（マシンの通知も、キーボードの入力もメインスレッドで届く）。
 * 表示デバイスの中身は、プロセスのスレッドも書くので、読むときはそのロックを持つ。
 */
internal class TestComputerWiring : MachineHost, KeyboardListener {
    private var screenDevice: ScreenDevice? = null
    private var keyboardDevice: KeyboardDevice? = null
    private var screen: ScreenBlockEntity? = null
    private var keyboard: KeyboardBlockEntity? = null

    // つないだときの画面・キーボードの address。外すときはこれで外す（つないだ後にブロックの address が変わっても外せるように。#535）
    private var screenAddress: String? = null
    private var keyboardAddress: String? = null
    private var machine: Machine? = null
    private var speakerAddress: String? = null

    /** つないだ画面。つないでいなければ null */
    val connectedScreen: ScreenBlockEntity? get() = screen

    /** つないだキーボード。つないでいなければ null */
    val connectedKeyboard: KeyboardBlockEntity? get() = keyboard

    /** 画面のブロックのためにマシンにつないだ画面のデバイス。つないでいなければ null（テスト用） */
    val connectedScreenDevice: ScreenDevice? get() = screenDevice

    /** スピーカーに届いたビープの数（音はまだ鳴らさない。テスト用） */
    val beeps: AtomicLong = AtomicLong()

    /** 今つないでいるマシンの起動の失敗（[onBootFailed]）。失敗していなければ null（テスト用） */
    var bootFailure: BootFailure? = null
        private set

    // 画面が外れたら、出力を止めている最中でも、すぐに外して取っておいた出力を捨てる（#397）
    private val onScreenRemoved = Runnable { detachScreen() }

    /**
     * 電源が入った（[machine] を作った）。ケースのスピーカーを [speakerAddress] で、隣の画面とキーボードをそれぞれの address でつなぎ、
     * [boot] の候補から起動する。
     */
    fun connect(level: Level, casePos: BlockPos, machine: Machine, speakerAddress: String, boot: BootConfig = BOOT_CONFIG) {
        disconnect()
        this.machine = machine
        bootFailure = null
        this.speakerAddress = speakerAddress
        machine.devices.attach(speakerAddress, SpeakerDevice { _, _ -> beeps.incrementAndGet() })
        attachScreen(level, casePos, machine)
        machine.boot(boot)
    }

    /**
     * [current]（ケースの今のマシン）がこの結線のマシンで、止まっておらず（[MachineState.RUNNING] か、起動の途中（[Machine.isBooting]））、
     * 画面をつないでいないなら `true`。
     * そのときだけ画面をつなぎ直す（[reconnectScreen]。#531・#535）。軽いので、ケースの隣のブロックが変わるたびに呼んでよい
     */
    fun canReconnectScreen(current: Machine?): Boolean {
        val m = machine ?: return false
        return current === m && (m.state == MachineState.RUNNING || m.isBooting) && screen == null
    }

    /**
     * 動いているマシンに画面をつないでいなければ（[canReconnectScreen]）、隣の画面とキーボードを探してつなぐ（#531）。
     * そうでなければ何もしない。マシンが自分で止まった（CRASHED、ゲストが止めた）ときは、電源が入ったままでもつながない（#535）。
     * 置いたブロックのデータ（address など）を読み込んだ後に呼ぶ（[CaseBlockEntity.neighborChanged] は次の tick に回す）
     */
    fun reconnectScreen(level: Level, casePos: BlockPos, current: Machine?) {
        if (!canReconnectScreen(current)) return
        attachScreen(level, casePos, machine ?: return)
    }

    private fun attachScreen(level: Level, casePos: BlockPos, machine: Machine) {
        val screen = findScreen(level, casePos) ?: return
        this.screen = screen
        screen.addRemovalListener(onScreenRemoved)
        // 表示デバイスはマシンの側（この結線）が持ち、画面はそれを映す（#355）。つなぐたびに新しく作る
        val display = DisplayDevice.forTier(screen.tier)
        screen.mirror(display)
        val address = screen.deviceAddress
        screenAddress = address
        screenDevice = ScreenDevice(display).also { machine.devices.attach(address, it) }
        // 1つのキーボードは、最初につないだケースだけが持つ（#360。正式な形は #129）
        val keyboard = findKeyboard(level, casePos, screen.blockPos)?.takeIf { it.claim(this) }
        if (keyboard != null) {
            this.keyboard = keyboard
            val keyAddress = keyboard.deviceAddress
            keyboardAddress = keyAddress
            keyboardDevice = KeyboardDevice().also { machine.devices.attach(keyAddress, it) }
        }
    }

    /** 電源が切れた・ケースが消える。マシンからすべてのデバイス（画面・キーボード・スピーカー）を外す（[detachScreen]） */
    fun disconnect() {
        val m = machine
        detachScreen()
        speakerAddress?.let { m?.devices?.detach(it) }
        speakerAddress = null
        machine = null
    }

    /**
     * マシンから画面とキーボードのデバイスを外し、画面とキーボードから外す（画面は最後の表示デバイスを映したままにし、中身を残す）。
     * マシンとスピーカーはそのまま（#531）。
     * 書ききれずに取っておいた出力は、1 tick の上限まで書いて残りを捨てる。画面が外れていれば書かずに捨てる（#381）。
     * 画面が外れたときは、画面からの知らせ（[ScreenBlockEntity.addRemovalListener]）で呼ぶ。出力を止めている間は
     * マシンからの通知がないので、それがないと気付けない（#397）
     */
    private fun detachScreen() {
        val m = machine
        keyboard?.release(this)
        keyboardAddress?.let { m?.devices?.detach(it) }
        keyboardAddress = null
        keyboard = null
        keyboardDevice = null
        val s = screen
        if (s?.isRemoved == true) screenDevice?.discardOutput()
        s?.removeRemovalListener(onScreenRemoved)
        screenAddress?.let { m?.devices?.detach(it) }
        screenAddress = null
        screen = null
        screenDevice = null
    }

    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

    override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
        if (machine === this.machine) bootFailure = failure
    }

    // 画面のデバイスをつないでいないときのコンソールの出力。映す画面がないので捨てる
    override fun onConsoleOutput(machine: Machine, bytes: ByteArray) = Unit

    override fun onKeyboardEvent(typist: UUID, event: KeyboardEvent) {
        keyboardDevice?.input(event)
    }

    companion object {
        /**
         * 仮の起動経路の最後の起動ディスク（標準 OS の同梱イメージ `os`。#461）。
         * 正式な入手方法（OS 入りのフロッピーから HDD へのインストール。#107・#145）がそろったら置き換える
         */
        const val OS_BOOT_DISK: String = BootConfig.IMAGE_DISK_PREFIX + "os"

        /**
         * GameTest 用の起動ディスク（同梱イメージ `testboot`）。`/sbin/init` は Lua の REPL（#315）。
         * ゲームでは使わない（[CaseBlockEntity.fallbackBootDisk] で差し替えて使う）
         */
        const val TEST_BOOT_DISK: String = BootConfig.IMAGE_DISK_PREFIX + "testboot"

        /** 仮の起動の設定: 起動部品あり、起動ディスクは同梱イメージ os */
        val BOOT_CONFIG: BootConfig = bootConfig(emptyList())

        /**
         * 仮の起動の設定: 起動部品あり、起動ディスクの候補は [disks]（ディスクのスロットの順）、最後に [fallback]。
         * [fallback]（読み取り専用の同梱イメージ）から起動したときは、[disks] の最初のディスクを `/home` にマウントする（#461）
         */
        fun bootConfig(disks: List<String>, fallback: String = OS_BOOT_DISK): BootConfig =
            BootConfig.builder().firmwarePresent(true).bootDisks(disks + fallback).homeDisk(disks.firstOrNull()).build()

        /** 起動部品（#125）のスロットは、挿さっているとみなす。ディスクの識別子は [parts] のまま */
        fun withBootPart(tier: CaseTier, parts: InstalledParts): InstalledParts = object : InstalledParts {
            override fun isInstalled(slot: Int): Boolean = tier.kindOf(slot) == PartKind.BOOT || parts.isInstalled(slot)

            override fun diskIdAt(slot: Int): String? = parts.diskIdAt(slot)
        }

        /** ケースの隣の画面。下・上・北・南・西・東の順に見て、最初のもの */
        fun findScreen(level: Level, casePos: BlockPos): ScreenBlockEntity? =
            Direction.entries.asSequence()
                .map { casePos.relative(it) }
                .firstNotNullOfOrNull { pos -> if (level.isLoaded(pos)) level.getBlockEntity(pos) as? ScreenBlockEntity else null }

        /** ケースの隣のキーボード（[findScreen] と同じ順）。なければ、画面につながったキーボード（[KeyboardInput.findKeyboard]） */
        fun findKeyboard(level: Level, casePos: BlockPos, screenPos: BlockPos): KeyboardBlockEntity? {
            Direction.entries.asSequence()
                .map { casePos.relative(it) }
                .firstNotNullOfOrNull { pos -> if (level.isLoaded(pos)) level.getBlockEntity(pos) as? KeyboardBlockEntity else null }
                ?.let { return it }
            val pos = KeyboardInput.findKeyboard(level, screenPos) ?: return null
            return level.getBlockEntity(pos) as? KeyboardBlockEntity
        }
    }
}
