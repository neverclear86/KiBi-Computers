package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus

/**
 * 起動の設定（接続層 → カーネル。#49）。[Machine.boot] に渡す。[builder] で作る。
 *
 * どのディスクがつながっているかはワールドの情報なので、接続層がメインスレッドで集めて渡す。
 * 起動部品の中身（ブートローダー・探す順の設定）は #99 で足す。
 *
 * ```java
 * BootConfig config = BootConfig.builder().firmwarePresent(true).addBootDisk(diskId).build();
 * machine.boot(config);
 * ```
 */
@ApiStatus.Internal
public class BootConfig private constructor(builder: Builder) {
    /** 起動部品が挿さっているか（中身は #99 で足す） */
    public val isFirmwarePresent: Boolean = builder.firmwarePresent

    /** 起動ディスクの候補（ディスクの識別子。前ほど優先。[DiskResolver] と同じ識別子）。変更できない写し */
    public val bootDisks: List<String> = java.util.Collections.unmodifiableList(ArrayList(builder.bootDisks))

    /**
     * 根が読み取り専用（同梱イメージなど）で起動したときに、書き込める形で `/home` にマウントするディスクの識別子（#461）。
     * `null` ならマウントしない。根にしたディスクと同じ識別子・読み取り専用でしか開けないディスクもマウントしない。
     * 正式な入手方法（#107・#145）がそろうまでの仮の起動経路のためのもの
     */
    public val homeDisk: String? = builder.homeDisk

    override fun toString(): String = "BootConfig(firmware=$isFirmwarePresent, disks=$bootDisks, home=$homeDisk)"

    /** [BootConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal var firmwarePresent: Boolean = true
        internal val bootDisks = ArrayList<String>()
        internal var homeDisk: String? = null

        /** 起動部品が挿さっているか（既定 `true`） */
        public fun firmwarePresent(value: Boolean): Builder = apply { firmwarePresent = value }

        /** 起動ディスクの候補を後ろに足す */
        public fun addBootDisk(diskId: String): Builder = apply { bootDisks.add(diskId) }

        /** 起動ディスクの候補を [value] で置き換える（前ほど優先） */
        public fun bootDisks(value: List<String>): Builder = apply {
            bootDisks.clear()
            bootDisks.addAll(value)
        }

        /** 根が読み取り専用のときに `/home` にマウントするディスク（[BootConfig.homeDisk]。既定 `null`） */
        public fun homeDisk(diskId: String?): Builder = apply { homeDisk = diskId }

        /** 設定を作る。 */
        public fun build(): BootConfig = BootConfig(this)
    }

    public companion object {
        /** 最初のプロセスとして動かす実行ファイルのパス */
        public const val INIT_PATH: String = "/sbin/init"

        /**
         * 同梱イメージ（MOD の jar に入れた読み取り専用のイメージ。#55）を表すディスクの識別子の頭。
         * `image:<名前>` は、リソース `/kibicomputers/images/<名前>.zip` のイメージを読み取り専用で開く
         * （例: `image:testboot`）。ほかの識別子は [DiskResolver] のディスク
         */
        public const val IMAGE_DISK_PREFIX: String = "image:"

        /** 設定を組み立て始める。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
