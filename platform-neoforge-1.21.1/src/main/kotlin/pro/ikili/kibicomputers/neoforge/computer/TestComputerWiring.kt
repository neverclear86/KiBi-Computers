package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.InstalledParts
import pro.ikili.kibicomputers.core.computer.MachineConsole
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardListener
import pro.ikili.kibicomputers.core.kernel.BootConfig
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
 * - 起動ディスクは同梱イメージ（[TEST_BOOT_DISK]）に決め打ち。置き換え先: 標準OS #98、起動部品の設定 #99
 * - 電源を入れたときに、隣の画面とキーボードを決め打ちで探し（[findScreen]・[findKeyboard]）、
 *   コンソールの出力を画面に、キーボードの入力をコンソールに流す（[MachineConsole]）。動いている間に置いた・壊したものは見ない。
 *   キーボードがすでに別のケースにつながっていれば、つながない（[KeyboardBlockEntity.claim]）
 *   置き換え先: 隣接デバイスの検出と接続 #129、画面・キーボードのデバイス #65・#66
 *
 * スレッド: メインスレッドから呼ぶ（マシンの通知も、キーボードの入力もメインスレッドで届く）。
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

    /** 電源が入った（[machine] を作った）。隣の画面とキーボードにつなぎ、同梱イメージから起動する。 */
    fun connect(level: Level, casePos: BlockPos, machine: Machine) {
        disconnect()
        this.machine = machine
        val screen = findScreen(level, casePos)
        if (screen != null) {
            this.screen = screen
            console = MachineConsole(screen.text, screen.tier.maxColors).also {
                it.reset()
                it.machine = machine
            }
            // 1つのキーボードは、最初につないだケースだけが持つ（#360。正式な形は #129）
            keyboard = findKeyboard(level, casePos, screen.blockPos)?.takeIf { it.claim(this) }
        }
        machine.boot(BOOT_CONFIG)
    }

    /** 電源が切れた・ケースが消える。画面とキーボードから外す（画面の中身はそのまま残す）。 */
    fun disconnect() {
        keyboard?.release(this)
        keyboard = null
        screen = null
        console?.machine = null
        console = null
        machine = null
    }

    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

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
         * テスト用コンピューターの起動ディスク（同梱イメージ `testboot`）。`/sbin/init` は Lua の REPL（#315）。
         * 標準OS（#98）と起動部品（#99）ができたら置き換える
         */
        const val TEST_BOOT_DISK: String = BootConfig.IMAGE_DISK_PREFIX + "testboot"

        /** 仮の起動の設定: 起動部品あり、起動ディスクは同梱イメージ testboot */
        val BOOT_CONFIG: BootConfig = BootConfig.builder().firmwarePresent(true).addBootDisk(TEST_BOOT_DISK).build()

        /** 起動部品（#125）のスロットは、挿さっているとみなす */
        fun withBootPart(tier: CaseTier, parts: InstalledParts): InstalledParts =
            InstalledParts { slot -> tier.kindOf(slot) == PartKind.BOOT || parts.isInstalled(slot) }

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
