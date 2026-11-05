package pro.ikili.kibicomputers.core.kernel.io

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * パイプの片端（#59）。読む端と書く端で別のオブジェクトにし、どちらも中身の [ByteStream]（[streamId]）の参照を1つ持つ。
 *
 * 片端の最後の参照が閉じたら（fd をすべて閉じた、持っていたプロセスが終わった）、中身の [ByteStream] のその側を閉じる
 * （書く端なら `closeWrite` で読む側は残りを読んだ後に `Eof`、読む端なら `closeRead` で書く側は `PIPE`）。
 * 中身の参照も返すので、両端が閉じたら中身も表から消える。
 *
 * 待ちの [pro.ikili.kibicomputers.core.kernel.wait.WaitKey] は中身の ID で作る（[ByteStream] が自分の ID で知らせるため）。
 * 状態は中身の ID と向きだけで、どちらも書き出せる値。
 */
internal class PipeEnd(
    /** 中身の [ByteStream] のオブジェクトID */
    val streamId: Int,
    /** 読む端なら `true`、書く端なら `false` */
    val reading: Boolean,
) : KernelObject() {
    /** 待ちは中身の ID で受ける（この端の ID では待たない） */
    override fun canWait(condition: WaitCondition): Boolean = false

    override fun releaseReferences(objects: ObjectTable, sink: WakeSink) {
        val stream = objects[streamId] as? ByteStream ?: return
        if (reading) stream.closeRead(sink) else stream.closeWrite(sink)
        objects.release(streamId, sink)
    }
}

/** パイプの作成（#59） */
internal object Pipes {
    /** パイプのバッファの既定の大きさ（バイト。仮の値。確定は #251） */
    const val DEFAULT_CAPACITY: Int = 4096

    /**
     * 容量 [capacity] のパイプを [objects] に作り、読む端と書く端のオブジェクトIDを返す。
     * 両端とも参照カウントは 1 で、参照は呼び出し側が持つ（fd に入れる、または `release` する）
     */
    fun create(objects: ObjectTable, capacity: Int = DEFAULT_CAPACITY): PipeIds {
        val stream = ByteStream(capacity)
        val streamId = objects.add(stream)
        objects.retain(streamId) // 両端が1つずつ持つ
        val read = objects.add(PipeEnd(streamId, reading = true))
        val write = objects.add(PipeEnd(streamId, reading = false))
        return PipeIds(read, write)
    }
}

/** [Pipes.create] で作ったパイプの両端のオブジェクトID */
internal data class PipeIds(val read: Int, val write: Int)
