package pro.ikili.kibicomputers.core.kernel.vfs

import pro.ikili.kibicomputers.core.device.DeviceBus
import pro.ikili.kibicomputers.core.device.DeviceInfo
import pro.ikili.kibicomputers.core.kernel.EntropySource
import java.util.UUID

/**
 * `/dev/device` に見せる、つないでいるデバイスの表（[DevFileSystem] が読む。#54）。読むだけで待たない（ロックなしの表を読む）。
 * カーネルロックの中から呼んでよい
 */
internal interface DeviceDirectory {
    /** つないでいるデバイスの一覧（割り当てない） */
    fun list(): List<DeviceInfo>

    /** [address] でつないでいるデバイスの種類。なければ `null`（形の違う名前も `null`。例外を投げない） */
    fun kindOf(address: String): String?

    companion object {
        /** デバイスなし（テスト用） */
        val NONE: DeviceDirectory = object : DeviceDirectory {
            override fun list(): List<DeviceInfo> = emptyList()

            override fun kindOf(address: String): String? = null
        }

        /** マシンのデバイスの表 [bus] を読む */
        fun of(bus: DeviceBus): DeviceDirectory = object : DeviceDirectory {
            override fun list(): List<DeviceInfo> = bus.list()

            override fun kindOf(address: String): String? = bus.find(address)?.kind
        }
    }
}

/**
 * デバイスファイルを出すファイルシステム（devfs 相当。#485・#54、ADR 0054。`/dev` にマウントする）。
 * 状態を持たない（RAM にも数えない。保存するものもない）。中身は名前の順に次のとおり。
 *
 * - `device/`: つないでいるデバイスのディレクトリ。中身は address の名前のキャラクターデバイス（[devices] を読むたびに引く）。
 *   開くと、fd はこのファイルではなく、そのデバイスのハンドル（`DeviceHandle`）を指す（[special]。差し替えは `FdTable.openFile`）
 * - `null`: 読むと終わり（0 バイト）、書くと全部を捨てる
 * - `random`・`urandom`: 読むと乱数（[entropy]。`random_get` と同じ元。待たない）、書くと全部を捨てる
 * - `tty`: そのプロセスの制御端末。開くと、fd は端末の `Tty` を指す（[special]）。ここを通して読み書きしたら `NXIO`
 * - `zero`: 読むと 0 のバイトで埋まる、書くと全部を捨てる
 *
 * node は固定の番号（保存した開いたファイルが指すので変えない・使い回さない）。デバイスの node は address から決まる値
 * （[deviceNode]。ビット 62 を立てて固定の番号と重ならないようにする。表を持たない）。
 *
 * どれも大きさは 0。`O_TRUNC`（[setSize]）・時刻の変更（[setTimes]）は受け付けて何もしない（Linux の `/dev/null` と同じく、
 * `> /dev/null` や `touch /dev/null` が通るように）。ファイルを作る・消す・名前を変えるのは `ACCES`（中身は決まっている）。
 * どの操作も I/O をしないので、どのスレッドから同時に呼んでもよく、排他も要らない（乱数の元は自分で排他する）。
 */
internal class DevFileSystem(
    /** `random`・`urandom` の乱数の元 */
    private val entropy: EntropySource = EntropySource.SECURE,
    /** `device/` に見せるデバイスの表 */
    private val devices: DeviceDirectory = DeviceDirectory.NONE,
) : FileSystem {
    /** 容量も使用量もすべて 0（中身は RAM にもディスクにも置かない。#453、ADR 0453）。項目の数の上限も 0（`none` は上限なしの意味なので使わない） */
    override fun usage(): FsUsage = USAGE

    override fun stat(path: VfsPath): FileStat {
        if (path.isRoot) return ROOT_STAT
        val first = byName(path.segments[0]) ?: fsError(Errno.NOENT)
        if (first == DEVICE_DIR_NODE && path.depth >= 2) {
            val address = path.segments[1]
            if (devices.kindOf(address) == null) fsError(Errno.NOENT)
            // `/dev/device/<address>/x` は途中がディレクトリでない
            if (path.depth > 2) fsError(Errno.NOTDIR)
            return deviceStat(deviceNode(address))
        }
        // `/dev/null/x` は途中がディレクトリでない
        if (path.depth != 1) fsError(Errno.NOTDIR)
        return statNode(first)
    }

    override fun statNode(node: Long): FileStat = when (node) {
        ROOT_NODE -> ROOT_STAT
        DEVICE_DIR_NODE -> DEVICE_DIR_STAT
        NULL_NODE, ZERO_NODE, TTY_NODE, RANDOM_NODE, URANDOM_NODE -> deviceStat(node)
        else -> if (isDeviceNode(node) && addressOf(node) != null) deviceStat(node) else fsError(Errno.STALE)
    }

    override fun createFile(path: VfsPath): Long = fsError(Errno.ACCES, "/dev には作れない")

    override fun createDirectory(path: VfsPath) {
        fsError(Errno.ACCES, "/dev には作れない")
    }

    override fun removeFile(path: VfsPath) {
        if (stat(path).type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        fsError(Errno.ACCES, "/dev のファイルは消せない")
    }

    override fun removeDirectory(path: VfsPath) {
        if (path.isRoot) fsError(Errno.BUSY)
        if (stat(path).type == FileType.DIRECTORY) fsError(Errno.ACCES, "/dev のディレクトリは消せない")
        fsError(Errno.NOTDIR)
    }

    override fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean) {
        stat(from)
        fsError(Errno.ACCES, "/dev のファイルの名前は変えられない")
    }

    override fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int = when (device(node)) {
        NULL_NODE -> 0
        ZERO_NODE -> {
            dst.fill(0, off, off + len)
            len
        }
        RANDOM_NODE, URANDOM_NODE -> {
            fillRandom(dst, off, len)
            len
        }
        else -> fsError(Errno.NXIO, "/dev/tty・/dev/device は開いた fd から使う")
    }

    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int = when (device(node)) {
        NULL_NODE, ZERO_NODE, RANDOM_NODE, URANDOM_NODE -> len
        else -> fsError(Errno.NXIO, "/dev/tty・/dev/device は開いた fd から使う")
    }

    override fun setSize(node: Long, size: Long) {
        device(node)
        if (size < 0) fsError(Errno.INVAL)
    }

    override fun append(node: Long, src: ByteArray, off: Int, len: Int): Written = Written(0, write(node, 0, src, off, len))

    override fun allocate(node: Long, size: Long) {
        device(node)
    }

    override fun sizeHint(node: Long): Long = 0

    override fun readDirectory(node: Long): List<DirEntry> = when (node) {
        ROOT_NODE -> ENTRIES
        DEVICE_DIR_NODE -> devices.list().map { DirEntry(it.address, FileType.CHARACTER_DEVICE, deviceNode(it.address)) }
        else -> {
            statNode(node)
            fsError(Errno.NOTDIR)
        }
    }

    override fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?) {
        if (isDeviceNode(node)) return
        statNode(node)
    }

    /**
     * 開いた fd の差し替え先（[SpecialFile]）。`tty` なら端末、デバイスの node なら今つないでいるデバイス（外れていれば [SpecialFile.Detached]）。
     * カーネルロックの中から呼ばれる（I/O をしない。デバイスの表はロックなしで読む）
     */
    override fun special(node: Long): SpecialFile? = when {
        node == TTY_NODE -> SpecialFile.Terminal
        isDeviceNode(node) -> {
            val address = addressOf(node)
            val kind = address?.let { devices.kindOf(it) }
            if (address == null || kind == null) SpecialFile.Detached else SpecialFile.Device(address, kind)
        }
        else -> null
    }

    /** デバイスの node（根・`device/` なら `ISDIR`、知らなければ `STALE`）。デバイスの node は、外れていても受け付ける（`O_TRUNC` を通すため） */
    private fun device(node: Long): Long = when (node) {
        NULL_NODE, ZERO_NODE, TTY_NODE, RANDOM_NODE, URANDOM_NODE -> node
        ROOT_NODE, DEVICE_DIR_NODE -> fsError(Errno.ISDIR)
        else -> if (isDeviceNode(node)) node else fsError(Errno.STALE)
    }

    /** [dst] の [off] から [len] バイトを乱数で埋める（[EntropySource.fill] は配列全体を埋めるので、部分なら一時配列を使う） */
    private fun fillRandom(dst: ByteArray, off: Int, len: Int) {
        if (off == 0 && len == dst.size) {
            entropy.fill(dst)
            return
        }
        var done = 0
        while (done < len) {
            val chunk = ByteArray(minOf(len - done, RANDOM_CHUNK))
            entropy.fill(chunk)
            chunk.copyInto(dst, off + done)
            done += chunk.size
        }
    }

    /** デバイスの node [node] の address（今つないでいるものから探す。なければ `null`） */
    private fun addressOf(node: Long): String? = devices.list().firstOrNull { deviceNode(it.address) == node }?.address

    private fun byName(name: String): Long? = ENTRIES.firstOrNull { it.name == name }?.node

    private fun deviceStat(node: Long): FileStat = FileStat(node, FileType.CHARACTER_DEVICE)

    companion object {
        /** [usage] の値（すべて 0） */
        private val USAGE = FsUsage(totalBytes = 0, usedBytes = 0, availableBytes = 0, totalEntries = 0, usedEntries = 0)

        /** 根の node */
        const val ROOT_NODE: Long = 1

        /** `/dev/null` の node */
        const val NULL_NODE: Long = 2

        /** `/dev/zero` の node */
        const val ZERO_NODE: Long = 3

        /** `/dev/tty` の node */
        const val TTY_NODE: Long = 4

        /** `/dev/random` の node（#54） */
        const val RANDOM_NODE: Long = 5

        /** `/dev/urandom` の node（#54） */
        const val URANDOM_NODE: Long = 6

        /** `/dev/device` の node（#54） */
        const val DEVICE_DIR_NODE: Long = 7

        /** デバイスの node の印（ビット 62）。固定の番号はこれより小さい */
        private const val DEVICE_NODE_BIT: Long = 1L shl 62

        /** 一時配列で乱数を埋める単位（`random_get` と同じ大きさ） */
        private const val RANDOM_CHUNK: Int = 64 * 1024

        private val ROOT_STAT = FileStat(ROOT_NODE, FileType.DIRECTORY)

        private val DEVICE_DIR_STAT = FileStat(DEVICE_DIR_NODE, FileType.DIRECTORY)

        /** 根の中身（名前の順） */
        private val ENTRIES = listOf(
            DirEntry("device", FileType.DIRECTORY, DEVICE_DIR_NODE),
            DirEntry("null", FileType.CHARACTER_DEVICE, NULL_NODE),
            DirEntry("random", FileType.CHARACTER_DEVICE, RANDOM_NODE),
            DirEntry("tty", FileType.CHARACTER_DEVICE, TTY_NODE),
            DirEntry("urandom", FileType.CHARACTER_DEVICE, URANDOM_NODE),
            DirEntry("zero", FileType.CHARACTER_DEVICE, ZERO_NODE),
        )

        /**
         * address のデバイスの node（UUID の上位と下位の 64 ビットの XOR の下位 62 ビットに、ビット 62 を立てる）。
         * 表を持たずに決まる。address はデバイスの表から来たもの（小文字の UUID）
         */
        fun deviceNode(address: String): Long {
            val uuid = UUID.fromString(address)
            return ((uuid.mostSignificantBits xor uuid.leastSignificantBits) and (DEVICE_NODE_BIT - 1)) or DEVICE_NODE_BIT
        }

        private fun isDeviceNode(node: Long): Boolean = node and DEVICE_NODE_BIT != 0L && node > 0
    }
}
