package pro.ikili.kibicomputers.core.kernel

/**
 * 接続層の例外 [t] の投げた場所（#674、ADR 0044）。[kind]（種類）・例外のクラス・スタックの上から [frames] 個のフレームで決める。
 * ただし、JDK と Kotlin の標準ライブラリのフレーム（`ArrayList.get` が `Objects.checkIndex` を通って投げるなど）は数えず、
 * それより下の呼んだ側のフレームを使う（標準ライブラリの中だけで決めると、別々の呼び出し元からの例外が同じ場所になる）。
 * スタックがすべて標準ライブラリなら、上から使う。
 *
 * スタックトレースを場所ごとに最初の1回だけ出すために、キーとして使う（`MachineScheduler.claimHostFailureTrace`、`RestoreQueue`）
 */
internal fun hostFailureSite(kind: Any, t: Throwable, frames: Int): List<Any> {
    val stack = t.stackTrace
    val site = ArrayList<Any>(frames + 2)
    site += kind
    site += t.javaClass
    for (frame in stack) {
        if (site.size - 2 >= frames) break
        if (!isLibraryFrame(frame)) site += frame
    }
    if (site.size == 2) {
        for (i in 0 until minOf(frames, stack.size)) site += stack[i]
    }
    return site
}

/** JDK か Kotlin の標準ライブラリのフレーム（[hostFailureSite] の場所に数えない） */
private fun isLibraryFrame(frame: StackTraceElement): Boolean {
    val name = frame.className
    return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") ||
        name.startsWith("sun.") || name.startsWith("com.sun.") || name.startsWith("kotlin.")
}
