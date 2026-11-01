package pro.ikili.kibicomputers.core.kernel.boot

import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem

/** 起動ディスクとして開いたファイルシステム（実行時だけ） */
internal class BootVolume(val fs: FileSystem, val readOnly: Boolean)

/**
 * ディスクの識別子から、起動に使うファイルシステムを開く口（#49）。ワーカーのスレッドから、カーネルロックを持たずに呼ぶ。
 * ディスク FS（#52）・同梱イメージ（#55）ができたら、それを開く実装にする
 */
internal fun interface BootVolumeOpener {
    /** [diskId] のディスクを開く。開けなければ `null` */
    fun open(diskId: String): BootVolume?

    companion object {
        /** 今の既定: どの識別子も開けない（#52・#55 で実装する） */
        val NONE: BootVolumeOpener = BootVolumeOpener { null }
    }
}
