package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.FsException

/**
 * 独自拡張 `kibi:fs/mounts`（#56。ADR 0056）の意味。ABI の層（p1 の import、後の p2/p3）に依らない部分。
 *
 * - `mount`: [mount]（その台に今挿さっている `disk:<識別子>` だけ。相対パスはカーネルの cwd から）
 * - `unmount`: [unmount]
 *
 * 文字列の上限・UTF-8 の検査は ABI の層が行う（`source` の誤りは `invalid`、`point` が長すぎれば `name-too-long`）。
 */
internal object KibiFs {
    /** `source` の長さの上限（バイト。`point` と同じ） */
    const val MAX_SOURCE_BYTES: Int = 4096

    @Throws(FsException::class)
    fun mount(ctx: ProcessContext, source: String, point: String, readOnly: Boolean) {
        ctx.mount(source, point, readOnly)
    }

    @Throws(FsException::class)
    fun unmount(ctx: ProcessContext, point: String) {
        ctx.unmount(point)
    }
}
