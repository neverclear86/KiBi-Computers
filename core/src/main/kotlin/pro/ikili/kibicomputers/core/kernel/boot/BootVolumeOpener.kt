package pro.ikili.kibicomputers.core.kernel.boot

import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.DiskResolver
import pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.DiskQuota
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.HostFileBudget
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem

/** 起動ディスクとして開いたファイルシステム（実行時だけ）。 */
internal class BootVolume(val fs: FileSystem, val readOnly: Boolean)

/**
 * ディスクの識別子から、起動に使うファイルシステムを開く口（#49）。ワーカーのスレッドから、カーネルロックを持たずに呼ぶ。
 */
internal fun interface BootVolumeOpener {
    /** [diskId] のディスクを開く。開けなければ `null` */
    fun open(diskId: String): BootVolume?

    companion object {
        /** どの識別子も開けない */
        val NONE: BootVolumeOpener = BootVolumeOpener { null }

        /**
         * 既定: [BootConfig.IMAGE_DISK_PREFIX] で始まる識別子を、同梱イメージ（[ImageFileSystem.bundled]）として開く。
         * ほかの識別子は開けない（[forDisks] を使う）。名前が正しくない・イメージが壊れているときも開けない
         */
        val DEFAULT: BootVolumeOpener = BootVolumeOpener { diskId ->
            if (!diskId.startsWith(BootConfig.IMAGE_DISK_PREFIX)) return@BootVolumeOpener null
            val name = diskId.substring(BootConfig.IMAGE_DISK_PREFIX.length)
            val fs = try {
                ImageFileSystem.bundled(name)
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: java.io.IOException) {
                null
            }
            fs?.let { BootVolume(it, readOnly = true) }
        }

        /**
         * マシンの既定: `image:` の識別子は [DEFAULT]、ほかは [disks] が返すホストのディレクトリを [DiskFileSystem] として開く
         * （なければ作る。#52）。開いたままのホストのファイルは [budget] で数える。[disks] が知らない・開けないディスクは `null`
         */
        fun forDisks(disks: DiskResolver, budget: HostFileBudget, quota: DiskQuota = DiskQuota()): BootVolumeOpener = BootVolumeOpener { diskId ->
            if (diskId.startsWith(BootConfig.IMAGE_DISK_PREFIX)) return@BootVolumeOpener DEFAULT.open(diskId)
            val dir = disks.resolve(diskId) ?: return@BootVolumeOpener null
            val fs = try {
                DiskFileSystem.open(dir, quota, readOnly = false, budget = budget)
            } catch (e: java.io.IOException) {
                null
            }
            fs?.let { BootVolume(it, readOnly = false) }
        }
    }
}
