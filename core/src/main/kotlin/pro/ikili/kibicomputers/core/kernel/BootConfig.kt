package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus

/**
 * 起動の設定（接続層 → カーネル。#49）。[Machine.boot] に渡す。[builder] で作る。
 *
 * どのディスクがつながっているかはワールドの情報なので、接続層がメインスレッドで集めて渡す。
 * 起動部品（EEPROM）の中身も、接続層がメインスレッドでアイテムから読んで渡す（#99、ADR 0099）。
 *
 * - 標準のファームウェア: 起動部品にプログラムがない（[firmwareProgram] が `null`）。カーネルの標準の起動（候補のディスクから
 *   `/sbin/init` を探す。ADR 0049）で起動する。[preferredBootDisk] があれば、その候補を先に試す
 * - プログラムだけの起動（ドローンなど）: [firmwareProgram] を `/sbin/init` に置いた読み取り専用のファイルシステムを `/` にして、
 *   それを init として動かす。ディスクは使わない
 *
 * ```java
 * BootConfig config = BootConfig.builder().firmwarePresent(true).addBootDisk(diskId).build();
 * machine.boot(config);
 * ```
 */
@ApiStatus.Internal
public class BootConfig private constructor(builder: Builder) {
    /** 起動部品が挿さっているか */
    public val isFirmwarePresent: Boolean = builder.firmwarePresent

    /** 起動ディスクの候補（ディスクの識別子。前ほど優先。[DiskResolver] と同じ識別子）。変更できない写し */
    public val bootDisks: List<String> = java.util.Collections.unmodifiableList(ArrayList(builder.bootDisks))

    /**
     * 起動部品のデータの「優先する起動ディスク」（#99。OC の boot address に当たる）。[bootDisks] にあれば、それを先に試す
     * （[bootOrder]）。[bootDisks] にない識別子は無視する（挿さっていないディスクは開けないので）。`null` なら [bootDisks] の順のまま
     */
    public val preferredBootDisk: String? = builder.preferredBootDisk

    /** 起動部品のプログラム（守るための写し。変えないので、カーネルはこれをそのまま使う）。`null` なら標準のファームウェア */
    internal val programBytes: ByteArray? = builder.firmwareProgram?.copyOf()

    /** 起動部品のプログラムだけで起動するか（[firmwareProgram] がある。#99） */
    public val isProgramOnly: Boolean get() = programBytes != null

    /** 起動部品のプログラムの写し（呼ぶたびに新しい配列）。`null` なら標準のファームウェア */
    public fun firmwareProgram(): ByteArray? = programBytes?.copyOf()

    /** 候補を試す順（[preferredBootDisk] が [bootDisks] にあれば先頭に移し、残りは [bootDisks] の順） */
    public val bootOrder: List<String>
        get() {
            val preferred = preferredBootDisk ?: return bootDisks
            if (preferred !in bootDisks) return bootDisks
            return java.util.Collections.unmodifiableList(listOf(preferred) + bootDisks.filter { it != preferred })
        }

    /**
     * 根が読み取り専用（同梱イメージなど）で起動したときに、書き込める形で `/home` にマウントするディスクの識別子（#461）。
     * `null` ならマウントしない。根にしたディスクと同じ識別子・読み取り専用でしか開けないディスクもマウントしない。
     * 正式な入手方法（#107・#145）がそろうまでの仮の起動経路のためのもの
     */
    public val homeDisk: String? = builder.homeDisk

    override fun toString(): String =
        "BootConfig(firmware=$isFirmwarePresent, disks=$bootDisks, preferred=$preferredBootDisk, home=$homeDisk, program=${programBytes?.size})"

    /** [BootConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal var firmwarePresent: Boolean = true
        internal val bootDisks = ArrayList<String>()
        internal var homeDisk: String? = null
        internal var preferredBootDisk: String? = null
        internal var firmwareProgram: ByteArray? = null

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

        /** 優先する起動ディスク（[BootConfig.preferredBootDisk]。既定 `null`） */
        public fun preferredBootDisk(diskId: String?): Builder = apply { preferredBootDisk = diskId }

        /**
         * 起動部品のプログラム（[BootConfig.firmwareProgram]。既定 `null` ＝ 標準のファームウェア）。渡した配列は [build] で写す。
         * 中身が空・WASM でないものも受け付ける（起動のときに init を動かせないとして失敗する）
         *
         * @throws IllegalArgumentException [MAX_FIRMWARE_PROGRAM_BYTES] より大きい
         */
        @Throws(IllegalArgumentException::class)
        public fun firmwareProgram(code: ByteArray?): Builder = apply {
            require(code == null || code.size <= MAX_FIRMWARE_PROGRAM_BYTES) {
                "起動部品のプログラムが大きすぎる（${code?.size} バイト。上限 $MAX_FIRMWARE_PROGRAM_BYTES）"
            }
            firmwareProgram = code
        }

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

        /** 起動部品のプログラムの大きさの上限（バイト。仮の値。確定は #251） */
        public const val MAX_FIRMWARE_PROGRAM_BYTES: Int = 64 * 1024

        /** 設定を組み立て始める。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
