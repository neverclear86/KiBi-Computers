package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * スタックの底の近くで、メモリとテーブルを持つモジュールからインスタンスを作っても（`ctx.instantiate` の道）、作りかけの分の勘定が
 * ずれないことを確かめる（#884）。
 *
 * 本番のカーネルのプロセスと同じく、サーバー全体の [MemoryAccount] → マシンの [MemoryAccount] → [ProcessMemory] を組み、
 * `writtenMemoryLimit`・`tableLimit` でその [ProcessMemory] に数える設定（`ProcessRunner.applyKernelLimits` と同じ）で、本物の Endive の
 * モジュールの `instantiate` を呼ぶ。256 KiB のスタックのスレッドで 1 段ずつ深くしながら、**底に近い深さのすべて**で 1 回ずつ呼ぶ
 * （[MemoryAccountStackExhaustionTest] と同じ形）。`ctx.instantiate` の入口の確かめ（[StackHeadroom.INSTANTIATE_FRAMES]）は、
 * 同じ段数の [lacks] で真似る。カーネルのプロセスを通した確かめは [InstantiateStackExhaustionKernelTest]。
 *
 * 作るのに失敗すると、Endive の `catch`（`EndiveWasmModule.instantiate`）が、作りかけのメモリ・テーブルの分を、失敗したのと同じ深さで
 * プロセス・マシン・サーバー全体に返す。返す側では確かめられないので、その途中でまた尽きると返らない。毎回の後に、浅いところで、
 * 作れたインスタンスを閉じ（`finishLocked` と同じ）、3 段とも 0 に戻ることを見る。
 */
class InstantiateStackExhaustionTest {
    private val stackBytes = 256L * 1024

    /** 作らせるモジュールの型 */
    internal enum class Kind {
        /** メモリ 1 ページとテーブル 2 要素。作り終えて返す（作り終えた後の `attach` まで） */
        SUCCESS,

        /** 要素セグメントがテーブルの外（メモリとテーブルを数えた後に、要素の初期化で失敗する） */
        ELEM_OUT_OF_BOUNDS,

        /** テーブルの最初の要素数が上限を超える（メモリだけを数えた後に `MemoryLimitFailure`） */
        TABLE_TOO_LARGE,

        /** start 関数がゼロの区画に書いてから `unreachable`（start が数えた区画も、`catch` で返す） */
        START_TRAP_AFTER_WRITE,

        /** start 関数が無限に再帰する（start の中の `StackOverflowError` → `catch`） */
        START_RECURSION,
    }

    internal companion object {
        const val NOT_RUN = 0

        /** 作り終えた（インスタンスが返った） */
        const val CREATED = 1

        /** スタックの不足以外の理由で、作るのに失敗した（型の期待どおり） */
        const val FAILED = 2

        /** スタックが足りずに断られた（入口の確かめか、作る途中の `StackOverflowError`・`call stack exhausted`） */
        const val EXHAUSTED = 3

        /** 底に近い深さを選ぶための確かめの段数（Endive の `build` の分を含めて、窓に底を含める） */
        const val WINDOW_FRAMES = 512

        /**
         * 入口の確かめを真似る段数。既定は [StackHeadroom.INSTANTIATE_FRAMES]。手元で確かめを外した（0）・縮めた結果を測るときだけ、
         * `kibi.test.instantiateGateFrames` で変える（ふつうの build・CI では渡さない）
         */
        val GATE_FRAMES: Int = Integer.getInteger("kibi.test.instantiateGateFrames", StackHeadroom.INSTANTIATE_FRAMES)

        /** 窓に入る深さの数の上限 */
        const val POOL_SIZE = 8000

        /** テーブルの上限（[Kind.TABLE_TOO_LARGE] は最初の要素数をこれより大きくする） */
        const val TABLE_LIMIT = 8

        /** [kind] のモジュール（燃料の計測なし） */
        fun moduleBytes(kind: Kind): ByteArray = WasmBinary().run {
            memory(1, exportName = null)
            when (kind) {
                Kind.SUCCESS -> table(2)
                Kind.ELEM_OUT_OF_BOUNDS -> {
                    table(1)
                    elem(4, listOf(func(emptyList(), emptyList(), code())))
                }
                Kind.TABLE_TOO_LARGE -> table(TABLE_LIMIT + 1)
                Kind.START_TRAP_AFTER_WRITE -> {
                    table(1)
                    // i32.store8 [0] = 1; unreachable
                    start(func(emptyList(), emptyList(), code(0x41, 0, 0x41, 1, 0x3a, 0, 0, 0x00)))
                }
                Kind.START_RECURSION -> {
                    table(1)
                    // f() = f()（関数 0 を呼ぶ。import がないので、自分）
                    start(func(emptyList(), emptyList(), code(0x10, 0)))
                }
            }
            build()
        }

        fun isStackExhaustion(t: Throwable): Boolean =
            t is StackOverflowError || (t is WasmTrapException && t.kind == WasmTrapKind.STACK_OVERFLOW)

        /** [frames] 段の確かめが通らなければ `true`（何も触らない） */
        fun lacks(frames: Int): Boolean = try {
            StackHeadroom.ensure(frames)
            false
        } catch (_: StackOverflowError) {
            true
        }
    }

    /** 深さ 1 つ分の試験の組（底に近いところで作らないよう、前もって用意する） */
    private class Fixture {
        val server = MemoryAccount(Long.MAX_VALUE / 4)
        val machine = MemoryAccount(1L shl 40, server)
        val process: ProcessMemory = machine.process(Int.MAX_VALUE / 2)
        val config: WasmInstanceConfig = WasmInstanceConfig.builder()
            .writtenMemoryLimit(16, process)
            .tableLimit(TABLE_LIMIT, process)
            .build()
        var status = NOT_RUN
        var created: WasmInstance? = null
    }

    private class Run(val module: WasmModule, val gateFrames: Int, val pool: Array<Fixture>) {
        var next = 0

        /** 入口の確かめを通った後、作る途中で尽きた回 */
        var exhaustedInside = 0
    }

    /** 1 段ずつ深くする。底に近づいたら、深さごとにインスタンスを 1 回作る */
    private fun descend(run: Run, n: Int): Int {
        if (run.next < run.pool.size && lacks(WINDOW_FRAMES)) instantiate(run, run.pool[run.next++])
        return descend(run, n + 1) + 1
    }

    /** `ctx.instantiate` の入口の確かめを真似てから、作る */
    private fun instantiate(run: Run, f: Fixture) {
        if (run.gateFrames > 0 && lacks(run.gateFrames)) {
            f.status = EXHAUSTED
            return
        }
        f.status = try {
            f.created = run.module.instantiate(f.config)
            CREATED
        } catch (t: Throwable) {
            if (isStackExhaustion(t)) {
                run.exhaustedInside++
                EXHAUSTED
            } else {
                FAILED
            }
        }
    }

    private class Result(val used: List<Fixture>, val drifts: List<String>, val exhaustedInside: Int) {
        fun count(status: Int): Int = used.count { it.status == status }
    }

    private fun sweep(kind: Kind, gateFrames: Int = GATE_FRAMES): Result = timeout(maxOf(Duration.ofSeconds(180), stackHeadroomTestLimit())) {
        val module = EndiveRuntime().load(moduleBytes(kind))
        // 浅いところで、底で初めて触るクラス（Endive のインスタンス・例外、この MOD の失敗の翻訳）を読み込んでおく
        // （クラスの初期化の途中で尽きると、そのクラスは以後使えなくなり、試験と関係なく壊れる）
        for (k in Kind.entries) {
            val warm = Fixture()
            try {
                EndiveRuntime().load(moduleBytes(k)).instantiate(warm.config).close()
            } catch (t: Throwable) {
                isStackExhaustion(t)
                t.toString()
            }
        }
        isStackExhaustion(WasmTrapException(WasmTrapKind.STACK_OVERFLOW, "warm-up"))
        val pool = Array(POOL_SIZE) { Fixture() }
        val run = Run(module, gateFrames, pool)
        val failure = AtomicReference<Throwable>()
        val thread = Thread(null, {
            try {
                descend(run, 0)
            } catch (t: StackOverflowError) {
                // 底まで来た（ここは確かめの対象でない）
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "instantiate-sweep", stackBytes)
        thread.start()
        thread.join()
        failure.get()?.let { throw AssertionError("試験の再帰が StackOverflowError 以外で終わった", it) }
        val used = pool.take(run.next)
        val drifts = ArrayList<String>()
        for ((i, f) in used.withIndex()) {
            // プロセスの終わり（finishLocked）と同じく、作れたインスタンスを閉じて、数えた分を返す
            f.created?.close()
            if (f.process.usedBytes != 0L || f.machine.usedBytes != 0L || f.server.usedBytes != 0L) {
                drifts += "$i 番目（${statusName(f.status)}）: プロセス ${f.process.usedBytes}、マシン ${f.machine.usedBytes}、サーバー ${f.server.usedBytes}"
            }
        }
        val result = Result(used, drifts, run.exhaustedInside)
        println(
            "#884 $kind（入口 $gateFrames 段）: 深さ ${used.size}、作れた ${result.count(CREATED)}、失敗 ${result.count(FAILED)}、" +
                "尽きた ${result.count(EXHAUSTED)}（うち入口を通った後 ${run.exhaustedInside}）、ずれた ${drifts.size}",
        )
        drifts.take(10).forEach { println("#884 $kind: $it") }
        result
    }

    private fun statusName(status: Int): String = when (status) {
        CREATED -> "作れた"
        FAILED -> "失敗"
        EXHAUSTED -> "尽きた"
        else -> "呼んでいない"
    }

    private fun check(kind: Kind) {
        val r = sweep(kind)
        assertEquals(emptyList<String>(), r.drifts, "勘定がずれた")
        // 試験が底をまたいでいること（どちらかが 0 だと、確かめられていない）
        assertTrue(r.count(EXHAUSTED) > 0, "スタックが尽きた深さがない（試験の窓が底まで届かない）")
        if (kind != Kind.START_RECURSION) {
            val expected = if (kind == Kind.SUCCESS) CREATED else FAILED
            assertTrue(r.count(expected) > 0, "底の近くで最後まで進んだ深さがない（試験の窓が底から遠い）")
            assertEquals(0, r.count(if (kind == Kind.SUCCESS) FAILED else CREATED), "型の期待と違う結果があった")
        }
    }

    @Test
    fun `底に近い深さのすべてで作り終えても、作ったインスタンスを閉じれば勘定は戻る`() = check(Kind.SUCCESS)

    @Test
    fun `底に近い深さのすべてで、メモリとテーブルを数えた後に要素の初期化で失敗しても、勘定はずれない`() = check(Kind.ELEM_OUT_OF_BOUNDS)

    @Test
    fun `底に近い深さのすべてで、メモリを数えた後にテーブルを断られても、勘定はずれない`() = check(Kind.TABLE_TOO_LARGE)

    @Test
    fun `底に近い深さのすべてで、start関数が区画に書いてからトラップしても、勘定はずれない`() = check(Kind.START_TRAP_AFTER_WRITE)

    @Test
    fun `底に近い深さのすべてで、start関数の中でスタックが尽きても、勘定はずれない`() = check(Kind.START_RECURSION)
}
