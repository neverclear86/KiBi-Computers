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

    /**
     * [diskId] のディスクを、開き手（起動・復元の手順）の取り消しを見ながら開く（#588）。[cancelled] が真になっていたら、
     * ほかの持ち主を締め出さずに `null` を返す（ホストのディスクでは、世代を進める直前にリースのロックの中で見る。ADR 0052）。
     * 呼び手は `null` を受けたら、まず自分の取り消しを確かめる。既定は [cancelled] を見ずに [open] を呼ぶ（メモリ上・同梱イメージは
     * ほかの持ち主を締め出さない）
     */
    fun open(diskId: String, cancelled: () -> Boolean): BootVolume? = open(diskId)

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
         * （なければ作る。#52）。容量は [disks] が返す値（[pro.ikili.kibicomputers.core.kernel.ResolvedDisk]。#124）。
         * 開いたままのホストのファイルは [budget] で数える。[disks] が知らない・開けないディスクは `null`。
         * 取り消しを見る形（`open(diskId, cancelled)`）で呼ぶと、取り消された開き手は世代を進めない（#588）
         */
        fun forDisks(disks: DiskResolver, budget: HostFileBudget): BootVolumeOpener = object : BootVolumeOpener {
            override fun open(diskId: String): BootVolume? = open(diskId) { false }

            override fun open(diskId: String, cancelled: () -> Boolean): BootVolume? {
                if (diskId.startsWith(BootConfig.IMAGE_DISK_PREFIX)) return DEFAULT.open(diskId)
                val disk = disks.resolve(diskId) ?: return null
                val quota = DiskQuota(maxBytes = disk.capacityBytes, maxEntries = disk.maxEntries)
                val fs = try {
                    DiskFileSystem.open(disk.directory, quota, readOnly = false, budget = budget, cancelled = cancelled)
                } catch (e: java.io.IOException) {
                    // 取り消されていたとき（DiskOpenCancelledException）も null。呼び手が取り消しを確かめる
                    null
                }
                return fs?.let { BootVolume(it, readOnly = false) }
            }
        }
    }
}
