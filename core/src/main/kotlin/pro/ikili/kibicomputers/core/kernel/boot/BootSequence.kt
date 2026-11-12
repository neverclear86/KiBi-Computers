package pro.ikili.kibicomputers.core.kernel.boot

import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.PendingExec
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath

/** 起動の途中で `stop()`・`reboot()` された（失敗の通知は出さない） */
internal class BootCancelledException : RuntimeException("起動を取り消した")

/**
 * 起動の手順（#49。ADR 0049）。[Machine.boot] が受け付けた後、起動用のスレッド（`KiBi-boot-*`。#350）で1回だけ動く。
 *
 * 1. 前回の残り（ゾンビ、pid、マウント）を片付ける
 * 2. 起動部品がなければ [BootFailure.NO_FIRMWARE]
 * 3. 候補のディスクを順に開き、[BootConfig.INIT_PATH] が普通のファイルとしてある最初のディスクを `/` にマウントする。
 *    続けて空の `/tmp`（メモリ上。#53）をマウントする（根の `/tmp` がディレクトリ以外ならマウントせず、コンソールに書いて続ける）。
 *    続けてデバイスファイルの `/dev`（`null`・`zero`・`tty`。#485）をマウントする（`/tmp` と同じく、できなければコンソールに書いて続ける）。
 *    根が読み取り専用なら、[BootConfig.homeDisk] を書き込める形で `/home` にマウントする（#461。開けない・読み取り専用・
 *    根の `/home` がディレクトリ以外なら、マウントせずにコンソールに書いて続ける）
 * 4. init を pid 1 で作る（親なし、argv は `[INIT_PATH]`、環境変数なし、cwd `/`、標準入出力はコンソール）。
 *    init の読み込み（コンパイル）の終わりは、スレッドを持たずに待つ（#358。終わったら起動用のスレッドで続ける）
 *
 * 途中で取り消されたら、マウントを外して何も知らせずに終える。
 */
internal class BootSequence(private val machine: Machine, private val config: BootConfig) {
    /** 今挿さっているディスクを開く口（開くたびに今の表を引く。#56） */
    private val opener: BootVolumeOpener
        get() = machine.openerForCurrentDisks()

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
            val (diskId, volume, claim) = findBootVolume() ?: return
            val mounted = try {
                machine.mountBootRoot(diskId, volume, claim)
            } catch (t: Throwable) {
                volume.fs.retire()
                throw t
            } finally {
                machine.releaseDiskClaim(claim)
            }
            if (!mounted) {
                // 取り消された・途中で抜かれた（抜かれたときは disksChanged が止めるので、ここでは知らせない。#56）
                volume.fs.retire()
                machine.endBoot(null, "")
                return
            }
            machine.mountTmp()?.let { errno -> machine.writeConsoleLine("kibi: /tmp not mounted (${errno.name})") }
            machine.mountDev()?.let { errno -> machine.writeConsoleLine("kibi: /dev not mounted (${errno.name})") }
            if (volume.readOnly) mountHome(diskId)
            mountOtherDisks()
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

    /** 選んだ根（識別子、開いたもの、段の予約） */
    private data class Root(val diskId: String, val volume: BootVolume, val claim: Machine.DiskClaim)

    /**
     * init のあるディスクを探す。見つからなければ失敗を知らせて `null`。候補は段の予約をしてから開く（#56。予約できない・今の表にない
     * 候補は開けないとして飛ばす）。選んだ候補の予約は持ったまま返す
     */
    private fun findBootVolume(): Root? {
        var opened = 0
        for (diskId in config.bootDisks) {
            if (machine.isBootCancelled) throw BootCancelledException()
            val claim = machine.claimPhaseDisk(diskId) ?: continue
            var keep = false
            try {
                val volume = try {
                    opener.open(diskId, machine.bootClaimCancelled(claim))
                } catch (e: Exception) {
                    null
                }
                // 取り消されて開かなかったときも、ここは `continue` でよい: 次の周の頭で取り消しを見て抜ける。最後の候補でも、
                // `endBoot` は取り消されていれば失敗を知らせない（#588）
                ?: continue
                opened++
                if (hasInit(volume)) {
                    keep = true
                    return Root(diskId, volume, claim)
                }
                // 選ばなかった候補は閉じる（ホストのディスクのリースを返す）
                volume.fs.retire()
            } finally {
                if (!keep) machine.releaseDiskClaim(claim)
            }
        }
        if (opened == 0) {
            machine.endBoot(BootFailure.NO_BOOT_DISK, if (config.bootDisks.isEmpty()) "起動ディスクの候補がない" else "どの候補も開けない: ${config.bootDisks}")
        } else {
            machine.endBoot(BootFailure.NO_INIT, "${BootConfig.INIT_PATH} のあるディスクがない（開けたディスク $opened 個）")
        }
        return null
    }

    /** 根が読み取り専用のとき、[BootConfig.homeDisk] を `/home` にマウントする。できなければコンソールに書いて続ける */
    private fun mountHome(rootDiskId: String) {
        val homeDisk = config.homeDisk ?: return
        if (homeDisk == rootDiskId) return
        if (machine.isBootCancelled) throw BootCancelledException()
        // 段の予約をしてから開く（予約できない・今の表にないなら開けない。#56）
        val claim = machine.claimPhaseDisk(homeDisk)
        val reason = try {
            val volume = if (claim == null) {
                null
            } else {
                try {
                    opener.open(homeDisk, machine.bootClaimCancelled(claim))
                } catch (e: Exception) {
                    null
                }
            }
            if (volume == null && machine.isBootCancelled) throw BootCancelledException()
            when {
                volume == null -> "cannot open"
                volume.readOnly -> {
                    volume.fs.retire()
                    "read-only"
                }
                else -> machine.mountHome(homeDisk, volume, checkNotNull(claim))?.name ?: return
            }
        } finally {
            if (claim != null) machine.releaseDiskClaim(claim)
        }
        machine.writeConsoleLine("kibi: /home not mounted ($reason)")
    }

    /**
     * 根と `/home` のほかに挿さっているディスクを、`/mnt/<識別子の先頭8文字>` にマウントする（#56、ADR 0056）。段の予約をしてから開く。
     * 開けない・位置が使えないものは飛ばす（コンソールにもイベントにも出さない）。取り消されたら [BootCancelledException]
     */
    private fun mountOtherDisks() {
        for (diskId in machine.bootMountCandidates()) {
            if (machine.isBootCancelled) throw BootCancelledException()
            val claim = machine.claimPhaseDisk(diskId) ?: continue
            machine.mountBootDisk(claim)
        }
    }

    /** まだマウントしていないファイルシステムを、ほかのスレッドと分け合わずに見る */
    private fun hasInit(volume: BootVolume): Boolean = try {
        volume.fs.stat(INIT).type == FileType.REGULAR_FILE
    } catch (e: FsException) {
        false
    }

    /**
     * init の読み込み（コンパイル）を始め、終わりはスレッドを持たずに待つ（#358）。読み込みが終わったら、起動用のスレッドで
     * [finishInit] が init を作る。待つ間に起動が取り消されたら、何も知らせずに起動を終える
     */
    private fun startInit() {
        val options = SpawnOptions(
            stdio = StdioSpec.CONSOLE,
            args = listOf(BootConfig.INIT_PATH),
            env = emptyMap(),
            cwd = "/",
            fds = SpawnOptions.ROOT_PREOPEN,
        )
        val pending = guarded { machine.beginExec(BootConfig.INIT_PATH, KernelDrivers.DEFAULT, options, parentPid = 0) } ?: return
        machine.whenInitLoaded(pending) { finishInit(pending) }
    }

    /** 読み込みが終わった init を pid 1 で作る（起動用のスレッドで） */
    private fun finishInit(pending: PendingExec) {
        try {
            guarded { machine.finishExec(pending, asInit = true) }
        } catch (e: BootCancelledException) {
            machine.endBoot(null, "")
        } catch (t: Throwable) {
            machine.endBoot(BootFailure.INIT_START_FAILED, "internal error", "起動の途中で例外: $t")
            if (t is VirtualMachineError && t !is StackOverflowError) throw t
        }
    }

    /** [block] を実行する。init を作れない失敗は、知らせて起動を終え `null` を返す */
    private fun <T : Any> guarded(block: () -> T): T? = try {
        block()
    } catch (e: FsException) {
        when (e.errno) {
            Errno.NOENT -> machine.endBoot(BootFailure.NO_INIT, e.message ?: "NOENT")
            Errno.ACCES, Errno.FBIG, Errno.NOEXEC -> machine.endBoot(BootFailure.INIT_NOT_EXECUTABLE, e.errno.name, e.message)
            else -> machine.endBoot(BootFailure.INIT_START_FAILED, e.errno.name, e.message)
        }
        null
    } catch (e: ResourceLimitException) {
        machine.endBoot(BootFailure.INIT_START_FAILED, "resource limit", e.message)
        null
    } catch (e: IllegalStateException) {
        machine.endBoot(BootFailure.INIT_START_FAILED, "machine not ready", e.message)
        null
    } catch (e: IllegalArgumentException) {
        machine.endBoot(BootFailure.INIT_START_FAILED, "invalid", e.message)
        null
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
