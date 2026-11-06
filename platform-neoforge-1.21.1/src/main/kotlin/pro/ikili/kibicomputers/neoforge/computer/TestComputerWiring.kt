package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.InstalledParts
import pro.ikili.kibicomputers.core.computer.MachineConsole
import pro.ikili.kibicomputers.core.computer.PartKind
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

/**
 * テスト用コンピューターの仮の結線（#307）。ケース1つにつき1つ持ち、サーバーでだけ使う。
 *
 * - 起動部品は挿さっているとみなす（[withBootPart]）。置き換え先: 起動部品のアイテム #125、ファームウェア #99
 * - 起動ディスクの候補は、ディスクのスロットの順のディスク（#124）、最後に標準 OS の同梱イメージ（[OS_BOOT_DISK]。#461）
 *   （[bootConfig]）。同梱イメージから起動したら、最初のディスクを `/home` にマウントする（[BootConfig.homeDisk]）。
 *   置き換え先: OS 入りのフロッピーから HDD へのインストール #107・#145、起動部品の設定 #99
 * - 電源を入れたときに、隣の画面とキーボードを決め打ちで探し（[findScreen]・[findKeyboard]）、
 *   表示デバイス（[DisplayDevice]）を作って画面に映させ、コンソールの出力を表示デバイスに、キーボードの入力をコンソールに流す
 *   （[MachineConsole]。プロセスの出力は、プロセスのスレッドが表示デバイスに書く。#355）。動いている間に置いたものは見ない。
 *   つないだ画面が外れたら、画面とキーボードから外す（#397）。
 *   キーボードがすでに別のケースにつながっていれば、つながない（[KeyboardBlockEntity.claim]）
 *   置き換え先: 隣接デバイスの検出と接続 #129、画面・キーボードのデバイス #65・#66
 *
 * スレッド: メインスレッドから呼ぶ（マシンの通知も、キーボードの入力もメインスレッドで届く）。
 * 表示デバイスの中身は、プロセスのスレッドも書くので、読むときはそのロックを持つ。
 */
internal class TestComputerWiring : MachineHost, KeyboardListener {
    private var console: MachineConsole? = null
    private var screen: ScreenBlockEntity? = null
    private var keyboard: KeyboardBlockEntity? = null
    private var machine: Machine? = null

    /** つないだ画面。つないでいなければ null */
    val connectedScreen: ScreenBlockEntity? get() = screen

    /** つないだキーボード。つないでいなければ null */
    val connectedKeyboard: KeyboardBlockEntity? get() = keyboard

    /** 画面につないだコンソール。つないでいなければ null（テスト用） */
    val connectedConsole: MachineConsole? get() = console

    /** 今つないでいるマシンの起動の失敗（[onBootFailed]）。失敗していなければ null（テスト用） */
    var bootFailure: BootFailure? = null
        private set

    // 画面が外れたら、出力を止めている最中でも、すぐに外して取っておいた出力を捨てる（#397）
    private val onScreenRemoved = Runnable { disconnect() }

    /** 電源が入った（[machine] を作った）。隣の画面とキーボードにつなぎ、[boot] の候補から起動する。 */
    fun connect(level: Level, casePos: BlockPos, machine: Machine, boot: BootConfig = BOOT_CONFIG) {
        disconnect()
        this.machine = machine
        bootFailure = null
        val screen = findScreen(level, casePos)
        if (screen != null) {
            this.screen = screen
            screen.addRemovalListener(onScreenRemoved)
            // 表示デバイスはマシンの側（この結線）が持ち、画面はそれを映す（#355）。電源を入れるたびに新しく作る
            val display = DisplayDevice.forTier(screen.tier)
            screen.mirror(display)
            console = MachineConsole(display).also { it.machine = machine }
            // 1つのキーボードは、最初につないだケースだけが持つ（#360。正式な形は #129）
            keyboard = findKeyboard(level, casePos, screen.blockPos)?.takeIf { it.claim(this) }
        }
        machine.boot(boot)
    }

    /**
     * 電源が切れた・ケースが消える・画面が外れた。画面とキーボードから外す（画面は最後の表示デバイスを映したままにし、中身を残す）。
     * 書ききれずに取っておいた出力は、1 tick の上限まで書いて残りを捨てる。画面が外れていれば書かずに捨てる（#381）。
     * 画面が外れたときは、画面からの知らせ（[ScreenBlockEntity.addRemovalListener]）でも呼ぶ。出力を止めている間は
     * [onConsoleOutput] が呼ばれないので、それだけでは気付けない（#397）
     */
    fun disconnect() {
        keyboard?.release(this)
        keyboard = null
        val s = screen
        if (s?.isRemoved == true) console?.discardOutput()
        s?.removeRemovalListener(onScreenRemoved)
        screen = null
        console?.machine = null
        console = null
        machine = null
    }

    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

    override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
        if (machine === this.machine) bootFailure = failure
    }

    override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
        if (machine !== this.machine) return
        val s = screen ?: return
        if (s.isRemoved) {
            disconnect()
            return
        }
        console?.write(bytes)
    }

    override fun onKeyboardEvent(typist: UUID, event: KeyboardEvent) {
        console?.keyboardInput(event)
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
