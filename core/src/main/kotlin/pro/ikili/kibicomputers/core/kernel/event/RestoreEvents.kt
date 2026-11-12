package pro.ikili.kibicomputers.core.kernel.event

/**
 * 復元のイベント（#87、ADR 0087）。成功した復元のたびに、カーネルが1件出す（同報）。
 *
 * 形は `event { kind: "restored", source: "", values: [integer(実時間の飛び ns), integer(ゲーム内時間の飛び tick)] }`。
 * 意味は「保存から戻った」だけで、画面が空であることは約束しない。他 MOD のデバイスも同じ名前のイベントを出せる
 * （source がアドレス）ので、受け手は source が空かどうかで見分ける。
 */
internal object RestoreEvents {
    /** 種類の名前 */
    const val TYPE: String = "restored"

    /** 出したもの（カーネル） */
    const val SOURCE: String = ""
}
