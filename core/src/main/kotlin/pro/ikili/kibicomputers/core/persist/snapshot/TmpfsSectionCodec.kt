package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException

/** 読んだ `TMPFS` 節（#82） */
internal class DecodedTmpfs(val mountId: Int, val snapshot: TmpFileSystem.TmpSnapshot)

/**
 * `TMPFS` 節（版 1）の中身（#82、ADR 0082）。マウントの ID、`nextNode`、node の並び（id・種類・大きさ・3つの時刻・
 * ディレクトリなら子の名前と id、普通のファイルなら塊の番号と 4,096 バイト）。`usedBytes` は書かず、戻すときに中身から数え直す。
 */
internal object TmpfsSectionCodec {
    /** 名前のバイト数の上限（読むとき） */
    private const val MAX_NAME_BYTES = 4096

    fun write(mountId: Int, snapshot: TmpFileSystem.TmpSnapshot): ByteArray {
        val out = SnapshotWriter(1024)
        out.i32(mountId)
        out.i64(snapshot.nextNode)
        out.count(snapshot.nodes.size)
        for (n in snapshot.nodes) {
            out.i64(n.id)
            out.u8(KernelObjectCodec.fileTypeCode(n.type))
            out.i64(n.size)
            out.i64(n.accessedNanos)
            out.i64(n.modifiedNanos)
            out.i64(n.changedNanos)
            when (n.type) {
                FileType.DIRECTORY -> {
                    val children = n.children.orEmpty()
                    out.count(children.size)
                    for ((name, id) in children) {
                        out.string(name)
                        out.i64(id)
                    }
                }
                else -> {
                    val chunks = n.chunks.orEmpty()
                    out.count(chunks.size)
                    for ((index, data) in chunks) {
                        out.i64(index)
                        out.raw(data)
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /** 読み、木の形（根から1回ずつ指される・塊が大きさの中）を確かめる。壊れていれば [SnapshotFormatException] */
    @Throws(SnapshotFormatException::class)
    fun read(payload: ByteArray): DecodedTmpfs {
        val input = SnapshotReader(payload)
        val mountId = input.i32()
        val nextNode = input.i64()
        val count = input.count(Int.MAX_VALUE, 45)
        val nodes = LinkedHashMap<Long, TmpFileSystem.TmpNode>()
        repeat(count) {
            val id = input.i64()
            val type = KernelObjectCodec.fileTypeOf(input.u8())
            val size = input.i64()
            val a = input.i64()
            val m = input.i64()
            val c = input.i64()
            val node = when (type) {
                FileType.DIRECTORY -> {
                    val n = input.count(Int.MAX_VALUE, 12)
                    val children = List(n) { input.string(MAX_NAME_BYTES) to input.i64() }
                    if (children.map { it.first }.toSet().size != children.size) throw SnapshotFormatException("/tmp の node $id の子の名前が重なる")
                    TmpFileSystem.TmpNode(id, type, size, a, m, c, children, null)
                }
                FileType.REGULAR_FILE -> {
                    if (size < 0) throw SnapshotFormatException("/tmp の node $id の大きさが負")
                    val n = input.count(Int.MAX_VALUE, 8 + TmpFileSystem.CHUNK_BYTES)
                    var last = -1L
                    val chunks = List(n) {
                        val index = input.i64()
                        if (index <= last || index.toDouble() * TmpFileSystem.CHUNK_BYTES >= size.toDouble()) {
                            throw SnapshotFormatException("/tmp の node $id の塊 $index が不正")
                        }
                        last = index
                        index to input.raw(TmpFileSystem.CHUNK_BYTES)
                    }
                    TmpFileSystem.TmpNode(id, type, size, a, m, c, null, chunks)
                }
                else -> throw SnapshotFormatException("/tmp の node $id の種類 $type は持てない")
            }
            if (nodes.put(id, node) != null) throw SnapshotFormatException("/tmp の node $id が重なる")
        }
        input.expectEnd()
        // 木の形
        val root = nodes[TmpFileSystem.ROOT_NODE]
        if (root == null || root.type != FileType.DIRECTORY) throw SnapshotFormatException("/tmp の根がない")
        val seen = HashSet<Long>()
        seen += TmpFileSystem.ROOT_NODE
        val stack = ArrayDeque<TmpFileSystem.TmpNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            for ((name, child) in n.children.orEmpty()) {
                if (name.isEmpty() || name == "." || name == ".." || '/' in name) throw SnapshotFormatException("/tmp の名前 $name が不正")
                val c = nodes[child] ?: throw SnapshotFormatException("/tmp の node $child がない")
                if (!seen.add(child)) throw SnapshotFormatException("/tmp の node $child が2回指される")
                stack.addLast(c)
            }
        }
        if (seen.size != nodes.size) throw SnapshotFormatException("/tmp に根から辿れない node がある")
        if (nodes.keys.any { it < TmpFileSystem.ROOT_NODE || it >= nextNode }) throw SnapshotFormatException("/tmp の node の番号が nextNode 以上")
        return DecodedTmpfs(mountId, TmpFileSystem.TmpSnapshot(nextNode, nodes.values.toList()))
    }
}
