package pro.ikili.kibicomputers.core.computer

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.DiskSnapshot
import pro.ikili.kibicomputers.core.kernel.ResolvedDisk
import pro.ikili.kibicomputers.core.util.CanonicalUuid
import java.nio.file.Path
import java.util.UUID

/**
 * ワールドの内蔵ディスクの置き場（#124。ADR 0124）。ディスクの識別子の生成・検査と、識別子からディレクトリへの解決を受け持つ。
 *
 * - 識別子は UUID の小文字の文字列（ハイフン付き 36 文字。[newId]）。形の正しくない識別子（`..`・区切り文字・大文字など）は
 *   [isValidId] が `false` で、ディレクトリを指さない（アイテムのデータを書き換えられても、[root] の外を指させない）
 * - ディスク `<id>` の中身は `<root>/<id>/`。ディレクトリはここでは作らない（マシンが初めて開くときにカーネルが作る。#52）
 * - 使われなくなったディレクトリは消さない（アイテムが消えても残す。片付けはサーバーの管理者の手作業）
 *
 * スレッド: どのスレッドから呼んでもよい（状態を持たない）。
 *
 * @property root ディスクのディレクトリを置く根（接続層では `<world>/kibicomputers/disks`）
 */
@ApiStatus.Internal
public class DiskStore(public val root: Path) {
    /** 新しい識別子（UUID の小文字の文字列）。I/O はしない */
    public fun newId(): String = UUID.randomUUID().toString()

    /** 識別子 [id] のディスクのディレクトリ（[root] の直下）。形が正しくなければ `null`。ディレクトリは作らない */
    public fun directoryOf(id: String): Path? = if (isValidId(id)) root.resolve(id) else null

    /**
     * 挿さっているディスク（識別子 → ティア）から、変わらない [DiskSnapshot] を作る（電源を入れたとき・動いている間に挿し抜きしたときに、
     * メインスレッドで呼ぶ。#56）。挿した順を保つ。
     * 形の正しくない識別子は入れない。容量は [PartTier.diskBytes]・[PartTier.diskEntries]。
     * 作った後に [disks] を変えても、結果は変わらない（プロセスのスレッドから呼ばれるため、ワールドにもアイテムにも触らない）
     */
    public fun snapshot(disks: Map<String, PartTier>): DiskSnapshot {
        val resolved = LinkedHashMap<String, ResolvedDisk>()
        for ((id, tier) in disks) {
            val dir = directoryOf(id) ?: continue
            resolved[id] = ResolvedDisk(dir, tier.diskBytes, tier.diskEntries)
        }
        return DiskSnapshot.of(resolved)
    }

    override fun toString(): String = "DiskStore($root)"

    public companion object {
        /** [id] がディスクの識別子の形か（UUID の正規の小文字の形だけ。`..`・区切り文字・大文字・空は `false`） */
        @JvmStatic
        public fun isValidId(id: String): Boolean = CanonicalUuid.isValid(id)
    }
}
