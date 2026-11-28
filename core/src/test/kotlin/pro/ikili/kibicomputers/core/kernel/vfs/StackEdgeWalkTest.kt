package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [walkStackEdge] の規則（最初の区間のやり直し・捨てた区間の `commitSwept(false)`・採った最初の区間だけ `done += crashed`・
 * やり直しを使い切ったときの扱い）を、決まった入力で確かめる（#952）。
 *
 * 本物のスタックは使わない。`attempt` は、境目（`edgeOf` が返す詰め物の段数）以上なら尽きたことにする模型で、JIT の進み方に左右されない。
 * 2 つのスタック尽きのテスト（`DiskFileSystemStackExhaustionTest`・`TmpFileSystemTreeStackExhaustionTest`）では、やり直しの道が
 * JIT の進み方しだいでしか通らないので、ここで毎回通す
 */
class StackEdgeWalkTest {
    /**
     * 模型の 1 回分。[decide] は `(pad, swept, それまでの 1 段ずつの回数)` を受け取り、尽きたなら `true`。
     * 1 段ずつの回の詰め物は区間ごとに置き、[walkStackEdge] が採った（`commitSwept(true)`）区間だけを [kept] に移す
     */
    private class Model(val decide: (pad: Int, swept: Boolean, sweptBefore: Int) -> Boolean) {
        var sweptCalls = 0
        val pending = ArrayList<Int>()
        val kept = ArrayList<Int>()
        val commits = ArrayList<Boolean>()

        fun attempt(pad: Int, swept: Boolean): Boolean {
            val crashed = decide(pad, swept, sweptCalls)
            if (swept) {
                sweptCalls++
                pending += pad
            }
            return crashed
        }

        fun commit(keep: Boolean) {
            commits += keep
            if (keep) kept += pending
            pending.clear()
        }
    }

    private fun Model.walk(
        width: Int = WIDTH,
        refindEvery: Int = EDGE_REFIND_EVERY,
        edgeRetries: Int = EDGE_RETRIES,
        maxLead: Int = EDGE_MAX_LEAD,
    ): StackEdgeWalk = walkStackEdge(width, 4, ::attempt, ::commit, refindEvery, edgeRetries, maxLead)

    /**
     * 採った区間の詰め物が、本当の境目 [edge] から [upTo] の手前まで、1 段も抜けずに通っている。幅 [WIDTH] は、最初の区間が境目の 8 段手前から始まるので、
     * ふつうは境目から `width - 8` 段先まで
     */
    private fun assertCovers(model: Model, edge: Int, upTo: Int = edge + WIDTH - 8) {
        val seen = model.kept.toSet()
        val missing = (edge until upTo).filter { it !in seen }
        assertTrue(missing.isEmpty(), "境目の直後を掃いていない段がある: ${missing.take(10)}（${missing.size} 段）")
    }

    @Test
    fun `境目が動かないなら、やり直さずに境目から幅の全部を掃く`() {
        val model = Model { pad, _, _ -> pad >= 1000 }
        val walk = model.walk()

        assertEquals(0, walk.retries)
        assertEquals(1000, walk.edge)
        // 最初の区間は境目の 8 段手前から始まり、120 回のうち 112 回が境目から先
        assertEquals(EDGE_REFIND_EVERY, walk.firstBatch)
        assertEquals(EDGE_REFIND_EVERY - 8, walk.firstCrashed)
        assertEquals(listOf(true, true, true), model.commits)
        assertCovers(model, 1000)
        // 最初の区間は、境目から先の段（112 回）だけ進める。区間の全部（120）を進めると、次の区間との間の 8 段を飛ばす
        assertEquals(992 + EDGE_REFIND_EVERY - 8, model.kept[EDGE_REFIND_EVERY], "2 つ目の区間は、最初の区間の終わりと 8 段重なる")
    }

    @Test
    fun `最初の境目が古くて低いと、その区間を捨てて、探し直した境目からやり直す`() {
        // 最初の境目（慣らしの後に 1 回だけ探したもの）は 500。それ以降は本当の境目の 1000
        val model = Model { pad, swept, n -> pad >= if (!swept && n == 0) 500 else 1000 }
        val walk = model.walk()

        assertEquals(1, walk.retries)
        assertEquals(listOf(false, true, true, true), model.commits)
        assertEquals(EDGE_REFIND_EVERY - 8, walk.firstCrashed)
        assertCovers(model, 1000)
        // 捨てた区間の詰め物（492 から）は、採った分に入らない
        assertFalse(model.kept.any { it < 992 }, "捨てた区間の詰め物が採った分に混ざった: ${model.kept.filter { it < 992 }.take(5)}")
    }

    @Test
    fun `最初の境目が古くて高いと、区間の全部が向こうになるので、捨ててやり直す`() {
        val model = Model { pad, swept, n -> pad >= if (!swept && n == 0) 2000 else 1000 }
        val walk = model.walk()

        assertEquals(1, walk.retries)
        assertEquals(listOf(false, true, true, true), model.commits)
        assertCovers(model, 1000)
        assertFalse(model.kept.any { it > 1800 && it < 1990 }, "捨てた区間の詰め物（1992 から）が採った分に混ざった")
    }

    @Test
    fun `境目をまたいでも、手前の回が maxLead より多ければやり直す`() {
        // 最初の境目は 1000 だが、その後の本当の境目は 1050。最初の区間（992 から）は 58 回が手前
        val model = Model { pad, swept, n -> pad >= if (!swept && n == 0) 1000 else 1050 }
        val walk = model.walk(maxLead = 20)

        assertEquals(1, walk.retries)
        assertEquals(listOf(false, true, true, true), model.commits)
        assertEquals(EDGE_REFIND_EVERY - 8, walk.firstCrashed)
        assertCovers(model, 1050)
    }

    @Test
    fun `やり直しを使い切っても、またいでいれば maxLead を超えたまま進む`() {
        // 境目を探すときは 1000、1 段ずつの掃引では 1050 に見える。探し直しても 1000 のままなので、手前の回はいつも 58 回
        val model = Model { pad, swept, _ -> pad >= if (swept) 1050 else 1000 }
        val walk = model.walk(maxLead = 20, edgeRetries = 2)

        assertEquals(2, walk.retries)
        assertEquals(listOf(false, false, true, true, true), model.commits)
        assertEquals(EDGE_REFIND_EVERY - 58, walk.firstCrashed)
        assertEquals(EDGE_REFIND_EVERY, walk.firstBatch)
        // 手前の回が多い分、探した境目（1000）の 8 段手前から数えた幅の終わりまでしか掃かない。本当の境目（1050）の直後は途切れずに通る
        assertCovers(model, 1050, upTo = 1000 - 8 + WIDTH)
    }

    @Test
    fun `やり直しを使い切ってもまたがなければ、落とす`() {
        // 境目を探すときは 1000 で尽きるが、1 段ずつの掃引では 1 回も尽きない
        val model = Model { pad, swept, _ -> !swept && pad >= 1000 }
        val e = assertThrows(IllegalStateException::class.java) { model.walk(edgeRetries = 2) }

        assertTrue(e.message!!.contains("境目をまたがない"), e.message)
        assertEquals(listOf(false, false, true), model.commits, "やり直しの 2 回は捨て、使い切った最後の区間は採ってから落とす")
    }

    @Test
    fun `やり直しの既定の回数は EDGE_RETRIES で、全部が向こうでも使い切ったら落とす`() {
        val model = Model { pad, swept, _ -> swept || pad >= 1000 }
        assertThrows(IllegalStateException::class.java) { model.walk() }

        assertEquals(List(EDGE_RETRIES) { false } + true, model.commits)
    }

    @Test
    fun `幅が区間より短くても、最初の区間は境目から先の段だけ進めるので、続きの区間で幅を使い切る`() {
        val model = Model { pad, _, _ -> pad >= 1000 }
        val walk = model.walk(width = 50)

        assertEquals(0, walk.retries)
        assertEquals(50, walk.firstBatch)
        assertEquals(42, walk.firstCrashed)
        // 最初の区間は境目から先の 42 段だけ進めるので、残りの 8 段のために区間がもう 1 つ要る
        assertEquals(listOf(true, true), model.commits)
        assertCovers(model, 1000, upTo = 1000 - 8 + 50)
    }

    @Test
    fun `詰め物を増やしても尽きないなら、落とす`() {
        val model = Model { _, _, _ -> false }

        assertThrows(IllegalStateException::class.java) { model.walk() }
    }

    private companion object {
        const val WIDTH = 300
    }
}
