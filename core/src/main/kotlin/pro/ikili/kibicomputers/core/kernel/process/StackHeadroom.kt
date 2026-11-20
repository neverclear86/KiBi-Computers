package pro.ikili.kibicomputers.core.kernel.process

/**
 * ゲストからカーネルに入る口で、スタックの余裕を先に確かめる（#828）。
 *
 * ゲストの深い再帰（コンポーネントの相互再帰など）では、スタックはどの深さでも尽きうる。尽きる位置が、カーネルのロック
 * （マシンのロック・CPU・許可）を取る・放す途中や、`holdsCpu`・`holdsPermit` の印と実際の持ち物を合わせる途中だと、
 * ロックが残る・CPU や許可が返らない・待ち手が起こされない、のどれかになり、そのマシン（許可ならサーバー全体）が止まる。
 * `finally` で取り戻そうとしても、同じ深さなのでまた溢れる。
 *
 * そこで、ゲストからカーネルに入る 2 つの口（`ProcessContext.hostFunction` の包みと、燃料切れの処理）の先頭で、決まった量の
 * スタックを使う再帰（[probe]）を 1 回だけ動かす。そこで `StackOverflowError` が出たら、ロックも CPU も許可も触らずに、
 * その `StackOverflowError` を投げ直す（ゲストの再帰で尽きたのと同じく、プロセスだけが `call stack exhausted` で終わる）。
 * 通れば、次にゲストを呼ぶまでのホストの区間（ADR 0043 の 48 行目により、カーネルロックの中でゲストを呼ばないので、深さに上限がある）は、
 * 確かめた分の余裕の中で動く。
 *
 * JVM は、メソッドに入るたびに、その先のシャドーゾーン（ネイティブのコードと JVM の中の処理のための数十 KiB）が残っているかを確かめる。
 * [probe] が通れば、ロックを取る時点で「シャドーゾーン + 確かめた分」が残っている。確かめた分は、ホストの区間でカーネルのロックを
 * 持つところまでのフレーム（Java のコードの分）に当てる。
 *
 * 1 段で使うバイト数は、フレームの大きさ（インタプリタか JIT か）で変わる。段数は、256 KiB のスタックで尽きる位置を 1 段ずつずらして
 * 確かめた結果（`StackExhaustionLockTest`。確かめがないと JDK のロックの中で尽き、燃料切れの処理では CPU が返らなくなった）に
 * 余裕を持たせて決めた。JIT の後で、ホストの関数の包みは約 6 KiB、燃料切れの処理は約 23 KiB を使い、
 * 1 回あたり約 0.2 µs・約 0.9 µs かかる（#828 の PR の設計メモ）。値はバランスの値でないので、コンフィグにしない
 */
internal object StackHeadroom {
    /**
     * ホストの関数の包みで確かめる段数。包みの道は熱い（コンポーネントの境界・p2 の import のたびに通る）ので、
     * ロックの区間とホストの関数の本体（VFS・canon の lift・lower）に足りるだけにする
     */
    const val HOST_CALL_FRAMES: Int = 32

    /**
     * 燃料切れの処理で確かめる段数。中で CPU・許可・カーネルロックを取り直し、条件変数で待つ（深い）。
     * スライスごとにしか通らないので大きめにする
     */
    const val FUEL_HANDLER_FRAMES: Int = 128

    /** [frames] 段分のスタックが残っているかを確かめる。足りなければ、何も触らずに `StackOverflowError` を投げる */
    fun ensure(frames: Int) {
        // 戻り値を使い、JIT が呼び出しごと消さないようにする（HotSpot は再帰をたたまないが、念のため）
        if (probe(frames, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16) == Long.MIN_VALUE) sink = 1
    }

    @Volatile
    private var sink: Int = 0

    /**
     * 1 段ごとに引数を呼び出しの後まで生かし、フレームに積ませる再帰（末尾呼び出しにしない）。
     * JIT が 1 段だけ内側に取り込んでも、段数に比例してスタックを使う
     */
    private fun probe(
        n: Int, a: Long, b: Long, c: Long, d: Long, e: Long, f: Long, g: Long, h: Long,
        i: Long, j: Long, k: Long, l: Long, m: Long, o: Long, p: Long, q: Long,
    ): Long {
        if (n <= 0) return a xor q
        val r = probe(n - 1, b + 1, c, d, e, f, g, h, i, j, k, l, m, o, p, q, a)
        return r + a - b + (c xor d) + (e or f) - (g and h) + (i xor j) - (k or l) + (m and o) - (p xor q)
    }
}
