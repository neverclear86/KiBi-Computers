package pro.ikili.kibicomputers.core.kernel.boot

import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath

/** 起動の途中で `stop()`・`reboot()` された（失敗の通知は出さない） */
internal class BootCancelledException : RuntimeException("起動を取り消した")

/**
 * 起動の手順（#49。ADR 0049）。[Machine.boot] が受け付けた後、ワーカーのスレッドで1回だけ動く。
 *
 * 1. 前回の残り（ゾンビ、pid、マウント）を片付ける
 * 2. 起動部品がなければ [BootFailure.NO_FIRMWARE]
 * 3. 候補のディスクを順に開き、[BootConfig.INIT_PATH] が普通のファイルとしてある最初のディスクを `/` にマウントする
 * 4. init を pid 1 で作る（親なし、argv は `[INIT_PATH]`、環境変数なし、cwd `/`、標準入出力はコンソール）
 *
 * 途中で取り消されたら、マウントを外して何も知らせずに終える。
 */
internal class BootSequence(private val machine: Machine, private val config: BootConfig) {
    private val opener: BootVolumeOpener get() = machine.scheduler.hooks.bootVolumes ?: BootVolumeOpener.NONE

    fun run() {
        try {
            if (!machine.beginBoot()) {
                machine.endBoot(null, "")
                return
            }
            if (!config.isFirmwarePresent) {
                machine.endBoot(BootFailure.NO_FIRMWARE, "起動部品がない")
                return
            }
            val (diskId, volume) = findBootVolume() ?: return
            if (!machine.mountBootRoot(diskId, volume)) {
                machine.endBoot(null, "")
                return
            }
            machine.writeConsoleLine("kibi: booting from disk $diskId")
            machine.scheduler.hooks.beforeInitExec?.invoke(machine)
            startInit()
        } catch (e: BootCancelledException) {
            machine.endBoot(null, "")
        } catch (t: Throwable) {
            machine.endBoot(BootFailure.INIT_START_FAILED, "internal error", "起動の途中で例外: $t")
            if (t is VirtualMachineError && t !is StackOverflowError) throw t
        }
    }

    /** init のあるディスクを探す。見つからなければ失敗を知らせて `null` */
    private fun findBootVolume(): Pair<String, BootVolume>? {
        var opened = 0
        for (diskId in config.bootDisks) {
            if (machine.isBootCancelled) throw BootCancelledException()
            val volume = try {
                opener.open(diskId)
            } catch (e: Exception) {
                null
            } ?: continue
            opened++
            if (hasInit(volume)) return diskId to volume
        }
        if (opened == 0) {
            machine.endBoot(BootFailure.NO_BOOT_DISK, if (config.bootDisks.isEmpty()) "起動ディスクの候補がない" else "どの候補も開けない: ${config.bootDisks}")
        } else {
            machine.endBoot(BootFailure.NO_INIT, "${BootConfig.INIT_PATH} のあるディスクがない（開けたディスク $opened 個）")
        }
        return null
    }

    /** まだマウントしていないファイルシステムを、ほかのスレッドと分け合わずに見る */
    private fun hasInit(volume: BootVolume): Boolean = try {
        volume.fs.stat(INIT).type == FileType.REGULAR_FILE
    } catch (e: FsException) {
        false
    }

    private fun startInit() {
        val options = SpawnOptions(
            stdio = StdioSpec.CONSOLE,
            args = listOf(BootConfig.INIT_PATH),
            env = emptyMap(),
            cwd = "/",
        )
        try {
            machine.exec(BootConfig.INIT_PATH, KernelDrivers.DEFAULT, options, parentPid = 0, asInit = true)
        } catch (e: FsException) {
            when (e.errno) {
                Errno.NOENT -> machine.endBoot(BootFailure.NO_INIT, e.message ?: "NOENT")
                Errno.ACCES, Errno.FBIG, Errno.NOEXEC -> machine.endBoot(BootFailure.INIT_NOT_EXECUTABLE, e.errno.name, e.message)
                else -> machine.endBoot(BootFailure.INIT_START_FAILED, e.errno.name, e.message)
            }
        } catch (e: ResourceLimitException) {
            machine.endBoot(BootFailure.INIT_START_FAILED, "resource limit", e.message)
        } catch (e: IllegalStateException) {
            machine.endBoot(BootFailure.INIT_START_FAILED, "machine not ready", e.message)
        } catch (e: IllegalArgumentException) {
            machine.endBoot(BootFailure.INIT_START_FAILED, "invalid", e.message)
        }
    }

    companion object {
        private val INIT: VfsPath = VfsPath.parse(BootConfig.INIT_PATH)

        /** 失敗の種類ごとの、コンソールに書く固定の英語の1行（[reason] は errno の名前など） */
        fun consoleMessage(failure: BootFailure, reason: String): String = when (failure) {
            BootFailure.NO_FIRMWARE -> "kibi: no boot firmware"
            BootFailure.NO_BOOT_DISK -> "kibi: no bootable disk"
            BootFailure.NO_INIT -> "kibi: ${BootConfig.INIT_PATH} not found"
            BootFailure.INIT_NOT_EXECUTABLE, BootFailure.INIT_START_FAILED -> "kibi: cannot start ${BootConfig.INIT_PATH}: $reason"
        }
    }
}
