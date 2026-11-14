package pro.ikili.kibicomputers.neoforge.computer

import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.InstalledParts
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.kernel.BootConfig

/**
 * 仮の起動まわり（#307 の仮の結線から、#129 で切り出した）。
 *
 * - 起動部品は挿さっているとみなす（[withBootPart]）。置き換え先: 起動部品のアイテム #125、ファームウェア #99
 * - 起動ディスクの候補は、ディスクのスロットの順のディスク（#124）、最後に同梱イメージ（[bootConfig]）。
 *   ゲームの既定は標準 OS（[OS_BOOT_DISK]。#461）。同梱イメージから起動したら、最初のディスクを `/home` にマウントする
 *   （[BootConfig.homeDisk]）。置き換え先: OS 入りのフロッピーから HDD へのインストール #107・#145、起動部品の設定 #99
 * - GameTest と負荷試験は、標準 OS とは別の試験用の起動イメージ（[TEST_BOOT_DISK]）を、ケースごとに明示して使う
 *   （[CaseBlockEntity.fallbackBootDisk]）
 */
internal object ProvisionalBoot {
    /**
     * 仮の起動経路の最後の起動ディスク（標準 OS の同梱イメージ `os`。#461）。
     * 正式な入手方法（OS 入りのフロッピーから HDD へのインストール。#107・#145）がそろったら置き換える
     */
    const val OS_BOOT_DISK: String = BootConfig.IMAGE_DISK_PREFIX + "os"

    /**
     * 試験用の起動ディスク（同梱イメージ `testboot`）。`/sbin/init` は Lua の REPL（#315）。
     * ゲームでは使わない（GameTest・負荷試験が [CaseBlockEntity.fallbackBootDisk] で差し替えて使う）
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
}
