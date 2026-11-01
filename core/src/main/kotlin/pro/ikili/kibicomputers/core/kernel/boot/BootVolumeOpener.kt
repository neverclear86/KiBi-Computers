package pro.ikili.kibicomputers.core.kernel.boot

import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem

/**
 * 起動ディスクとして開いたファイルシステム（実行時だけ）。
 *
 * @property bundled MOD に同梱したイメージ（#55）。中のプログラムは MOD が用意したものなので、
 *   燃料の計測（#38）がまだ入っていなくても init として動かす（#307 の仮の扱い。#38 で計測を入れたら外す）
 */
internal class BootVolume(val fs: FileSystem, val readOnly: Boolean, val bundled: Boolean = false)

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
         * ほかの識別子は、まだ開けない（ディスク FS は #52）。名前が正しくない・イメージが壊れているときも開けない
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
            fs?.let { BootVolume(it, readOnly = true, bundled = true) }
        }
    }
}
