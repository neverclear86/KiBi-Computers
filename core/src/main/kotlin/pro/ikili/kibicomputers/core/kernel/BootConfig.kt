package pro.ikili.kibicomputers.core.kernel

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
public class BootConfig private constructor(builder: Builder) {
    /** 起動部品が挿さっているか（中身は #99 で足す） */
    public val isFirmwarePresent: Boolean = builder.firmwarePresent

    /** 起動ディスクの候補（ディスクの識別子。前ほど優先。[DiskResolver] と同じ識別子）。変更できない写し */
    public val bootDisks: List<String> = java.util.Collections.unmodifiableList(ArrayList(builder.bootDisks))

    override fun toString(): String = "BootConfig(firmware=$isFirmwarePresent, disks=$bootDisks)"

    /** [BootConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal var firmwarePresent: Boolean = true
        internal val bootDisks = ArrayList<String>()

        /** 起動部品が挿さっているか（既定 `true`） */
        public fun firmwarePresent(value: Boolean): Builder = apply { firmwarePresent = value }

        /** 起動ディスクの候補を後ろに足す */
        public fun addBootDisk(diskId: String): Builder = apply { bootDisks.add(diskId) }

        /** 起動ディスクの候補を [value] で置き換える（前ほど優先） */
        public fun bootDisks(value: List<String>): Builder = apply {
            bootDisks.clear()
            bootDisks.addAll(value)
        }

        /** 設定を作る。 */
        public fun build(): BootConfig = BootConfig(this)
    }

    public companion object {
        /** 最初のプロセスとして動かす実行ファイルのパス */
        public const val INIT_PATH: String = "/sbin/init"

        /** 設定を組み立て始める。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
