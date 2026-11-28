package pro.ikili.kibicomputers.core.testing

import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

/**
 * テスト本体を包む `assertTimeoutPreemptively`（#980）。上限は [limit] と [BODY_LIMIT_FLOOR] の長いほう。
 *
 * #916 で中の待ちを期限なしにしたので、止まったときに落ちる時点は、この外側の上限になった。30 秒のような短い上限は、負荷の高い CI で
 * 正しく動いている本体を誤って落としうるので、下限を設けて延ばす。外さないのは、本体を別のスレッドで動かして先に切る性質
 * （割り込みに応じない止まり、例えば `ReentrantLock.lock()` やカーネルロックの取り合いで止まったテストのスレッドを、保険の
 * タイムアウト（SAME_THREAD の 10 分）を待たずに落とす）と、#927 のスレッドダンプ（`ThreadDumpOnTimeout` が原因の連鎖の
 * `ExecutionTimeoutException` を見る）を残すため。中では JUnit の `assertTimeoutPreemptively` をそのまま呼ぶ。
 *
 * 形（総称型の戻り値）は JUnit の Kotlin 版と同じにしてある。`import org.junit.jupiter.api.assertTimeoutPreemptively as timeout` を
 * この関数の import に置き換えても、`fun テスト() = timeout(limit) { ... }` の型の推論（テストのメソッドが void のままか）は変わらない。
 * 時間そのものを確かめる上限（直す前は何十秒もかかった計算が速く終わること、など）には使わない（JUnit のものを直に呼ぶ）
 */
internal fun <R> bodyTimeout(limit: Duration, executable: () -> R): R =
    assertTimeoutPreemptively(bodyLimit(limit), executable)

/** [bodyTimeout] が使う上限（[limit] と [BODY_LIMIT_FLOOR] の長いほう） */
internal fun bodyLimit(limit: Duration): Duration = maxOf(limit, BODY_LIMIT_FLOOR)

/**
 * [bodyTimeout] の上限の下限（#980）。
 *
 * 止まったテスト 1 本にかかる時間は、30 秒の上限より 90 秒延びる。止まるのは不具合のときだけで、CI の core-test の 1 組（ふだん 5〜8 分、
 * ジョブの上限 60 分）に数本の止まりが重なっても収まる長さにする
 */
internal val BODY_LIMIT_FLOOR: Duration = Duration.ofMinutes(2)
