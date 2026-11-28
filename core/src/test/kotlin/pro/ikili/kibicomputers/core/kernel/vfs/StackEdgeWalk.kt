package pro.ikili.kibicomputers.core.kernel.vfs

import pro.ikili.kibicomputers.core.kernel.stackHeadroomRounds

/** [walkStackEdge] の結果（最後の行に出す） */
internal class StackEdgeWalk(
    /** 最後に探した境目 */
    val edge: Int,
    /** 採った最初の 1 段ずつの区間で、境目をまたいだ（`attempt` が `true` を返した）回 */
    val firstCrashed: Int,
    /** 採った最初の区間の長さ */
    val firstBatch: Int,
    /** 最初の区間を探し直してやり直した回数 */
    val retries: Int,
) {
    override fun toString(): String = "境目 $edge、最初の区間で境目の向こうだった $firstCrashed / $firstBatch 回（やり直し $retries 回）"
}

/**
 * 詰め物の段数を変えて [attempt] を動かし、尽きる境目から先を [width] 段だけ 1 段ずつ掃く（#858・#902・#952）。
 * `DiskFileSystemStackExhaustionTest` と `TmpFileSystemTreeStackExhaustionTest` が同じ規則を使う（#952 までは 2 つのクラスに別々に書いてあり、
 * Disk の側だけ下のやり直しがなかった）。
 *
 * [attempt] は `(pad, swept)` を受け取り、境目の向こう（尽きた、または深いところで行えなかった）なら `true` を返す。`swept` は 1 段ずつの掃引の回で
 * `true`（慣らしと境目を探す回は `false`）。
 *
 * 1. 慣らしとして、詰め物 0 で [warmup] 回（`stackHeadroomRounds` で縮める）動かして JIT をかける。
 * 2. 尽きる最小の詰め物の段数（境目）を、倍々と二分で探す。
 * 3. 境目の 8 段手前から、[refindEvery] 回ずつの区間で 1 段ずつ動かし、区間ごとに境目を探し直す（JIT が進むと境目が動く）。
 * 4. 最初の区間が境目をまたがなかった（全部が向こう、または 1 回も向こうでない）か、手前の回（`false`）が [maxLead] 回より多いときは、
 *    探し直した境目からその区間をやり直す。慣らしは詰め物 0 だけなので、最初の境目は、詰め物の再帰が温まる前の古い（低い）値になることがあり、
 *    そのまま進むと境目の直後を 1 段ずつ掃かない。やり直しを [edgeRetries] 回使い切ったら、またいでいる限り進む（混んだマシンでは、またいだ区間でも
 *    手前の回が [maxLead] を超えることがある）。またがなければ `check` で落とす。
 * 5. 採った最初の区間は、境目から先に動かした段（向こうだった回の数）だけ進める（区間の全部を進めると、次の区間との間の段を飛ばす）。
 *    2 つ目からの区間は区間の全部を進める。境目から先を [width] 段動かすまで続ける。
 *
 * 区間ごとに [commitSwept] を呼ぶ（`true` は採った区間、`false` はやり直しで捨てた区間）。1 段ずつの回だけを数えたい呼び手は、
 * 区間の途中の分を置いておき、ここで数えるか捨てるかを決める。幅は回数の割合（`kibi.stackHeadroom.roundsScale`）で縮めない（#903）
 */
internal fun walkStackEdge(
    width: Int,
    warmup: Int,
    attempt: (pad: Int, swept: Boolean) -> Boolean,
    commitSwept: (keep: Boolean) -> Unit = {},
    refindEvery: Int = EDGE_REFIND_EVERY,
    edgeRetries: Int = EDGE_RETRIES,
    maxLead: Int = EDGE_MAX_LEAD,
): StackEdgeWalk {
    // 操作を何度か動かして、JIT をかける（インタプリタと JIT のコードでは、フレームの大きさが違い、境目が動く）
    repeat(stackHeadroomRounds(warmup)) { attempt(0, false) }

    /** 尽きる最小の詰め物の段数を探す（倍々で尽きるところを見つけ、二分で縮める） */
    fun findEdge(): Int {
        var lo = 0
        var hi = 256
        while (!attempt(hi, false)) {
            lo = hi
            hi *= 2
            check(hi < (1 shl 20)) { "詰め物を増やしても尽きない" }
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (attempt(mid, false)) hi = mid else lo = mid
        }
        return hi
    }

    var edge = findEdge()
    var done = 0
    var retries = 0
    var firstCrashed = 0
    var firstBatch = 0
    while (done < width) {
        val batch = minOf(width - done, refindEvery)
        var crashed = 0
        for (k in 0 until batch) if (attempt(maxOf(0, edge - 8 + done + k), true)) crashed++
        edge = findEdge()
        val straddled = crashed in 1 until batch
        if (done == 0 && (!straddled || batch - crashed > maxLead) && retries < edgeRetries) {
            retries++
            commitSwept(false)
            continue
        }
        commitSwept(true)
        if (done == 0) {
            check(straddled) { "最初の 1 段ずつの区間が、境目をまたがない（$edgeRetries 回探し直しても。境目の向こう $crashed / $batch 回）" }
            firstCrashed = crashed
            firstBatch = batch
            // 区間の初めの（batch − crashed）回は境目の手前で、境目から先は crashed 段だけ。次の区間は、探し直した境目が本当の境目と
            // 同じなら、前の区間の終わりと 8 段重なる
            done += crashed
        } else {
            done += batch
        }
    }
    return StackEdgeWalk(edge, firstCrashed, firstBatch, retries)
}

/** 境目を探し直す間隔（回） */
internal const val EDGE_REFIND_EVERY = 120

/** 最初の 1 段ずつの区間が境目をまたがなかったときに、探し直してやり直す回数の上限 */
internal const val EDGE_RETRIES = 3

/**
 * 最初の 1 段ずつの区間で、手前の回（境目の手前）の、やり直さずに済む上限。境目が合っていれば区間は境目の 8 段手前から始まるので 8 回前後だが、
 * ふつうに出る幅は 2〜40 回で、混んだマシンや JDK 21 ではもっと大きい（境目は 1 段の精度では決まらず、JIT の段階で動く。#902 の
 * `TmpFileSystemTreeStackExhaustionTest` で測った）。区間の長さ [EDGE_REFIND_EVERY]（120）に対し、境目の直後を 20 段以上は掃いている 100 まで許す
 */
internal const val EDGE_MAX_LEAD = 100
