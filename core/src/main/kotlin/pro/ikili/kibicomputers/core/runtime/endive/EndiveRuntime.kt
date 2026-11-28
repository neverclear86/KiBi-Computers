package pro.ikili.kibicomputers.core.runtime.endive

import org.jetbrains.annotations.ApiStatus
import org.objectweb.asm.MethodTooLargeException
import pro.ikili.kibicomputers.core.runtime.InterpreterReason
import pro.ikili.kibicomputers.core.runtime.InterpreterUse
import pro.ikili.kibicomputers.core.runtime.MemoryChunkCounts
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
import pro.ikili.kibicomputers.core.runtime.WasmAsync
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmException
import pro.ikili.kibicomputers.core.runtime.WasmExportDescriptor
import pro.ikili.kibicomputers.core.runtime.WasmExtern
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmFuel
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmImportDescriptor
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmModuleShape
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.WasmStateException
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.fuel.GcCosts
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline
import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.compiler.MethodPrefixer
import run.endive.runtime.ByteBufferMemory
import run.endive.runtime.CompiledModule
import run.endive.runtime.ExportFunction
import run.endive.runtime.GlobalFactory
import run.endive.runtime.GlobalInstance
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportFunction
import run.endive.runtime.ImportGlobal
import run.endive.runtime.ImportMemory
import run.endive.runtime.ImportTable
import run.endive.runtime.ImportTag
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.TableInstance
import run.endive.runtime.TrapException
import run.endive.runtime.WasmInterruptedException
import run.endive.wasm.InvalidException
import run.endive.wasm.MalformedException
import run.endive.wasm.Parser
import run.endive.wasm.UninstantiableException
import run.endive.wasm.UnlinkableException
import run.endive.wasm.WasmEngineException
import run.endive.wasm.types.ActiveDataSegment
import run.endive.wasm.types.ActiveElement
import run.endive.wasm.types.ExternalType
import run.endive.wasm.types.FunctionImport
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.GlobalImport
import run.endive.wasm.types.Instruction
import run.endive.wasm.types.OpCode
import run.endive.wasm.types.PassiveElement
import run.endive.wasm.types.MemoryLimits
import run.endive.wasm.types.MutabilityType
import run.endive.wasm.types.StorageType
import run.endive.wasm.types.Table
import run.endive.wasm.types.TableLimits
import run.endive.wasm.types.TypeSection
import run.endive.wasm.types.ValType
import run.endive.runtime.Memory as EndiveMemory
import run.endive.runtime.WasmException as EndiveWasmException
import run.endive.wasm.WasmModule as EndiveModule

/**
 * Endive による [WasmRuntime]。
 *
 * [load] でパース・検証の後に、ランタイムコンパイラで JVM のバイトコードにコンパイルする（#29 の決定 B、#37）。
 * JVM のメソッドの上限を超える関数だけは、Endive がその関数をインタプリタで動かす（[InterpreterFallback.SILENT]。
 * Endive の WARN は標準エラーに直に出すので使わず、`kibicomputers.wasm` に自分で出して間引く。[CompileFallbackLog]、#680）。
 * Endive のコンパイラは SIMD（v128）に対応していないので、SIMD を使う関数（[simdFunctions]）もその関数だけインタプリタで動かし、
 * ほかの関数はコンパイルする（#424）。そのときも、上限を超える関数は失敗の例外から読み取って、その関数だけインタプリタに回す（#446）。
 * コンパイルの後は、関数の本体を除いたモジュール（[CompiledModule.wasmModule]）だけを持ち、パースした命令の列は捨てる
 * （挿入後の lua.wasm で、ヒープが約 18 MB → 約 0.08 MB）。同じ WASM の読み込みの共有は、呼び出し側（カーネルの `ProgramCache`）が受け持つ。
 *
 * メモリの上限（#39）は、Endive のメモリを作るときに上限を縮め（[WasmInstanceConfig.maxMemoryPages]）、
 * [WasmInstanceConfig.memoryBudget] があれば `grow` を数える包み（[BudgetedMemory]）をかぶせて守る。
 * 共有しないメモリは、書き込んだページだけ JVM のメモリを確保する（[LazyMemory]、#334）。
 * 上限と RAM は宣言したページ数（最初のページ数と `grow` の合計）で数え、確保したページ数では数えない。
 * [WasmInstanceConfig.writtenMemory] なら、共有しないメモリは初めて書き込んだ 4 KiB の区画の数で数える（[WrittenPagesMemory]、#407）。
 *
 * テーブル（#361）も同じように、作るときに要素数の上限を縮め（[WasmInstanceConfig.maxTableElements]）、
 * [WasmInstanceConfig.tableBudget] があれば、要素数 × 要素1つのバイト数を数える（[BudgetedTable]）。
 * GC のオブジェクト（struct・array）も、作る前に [WasmInstanceConfig.tableBudget] に数える（#939。[GcAllocations]）。
 */
@ApiStatus.Internal
public class EndiveRuntime internal constructor(
    /** `false` ならコンパイルせずインタプリタで動かす（テストで、インタプリタとの結果を比べるため） */
    private val compile: Boolean,
    /** コンパイルのやり直しの時間を測る時計（ナノ秒）。テストで差し替える。既定は、呼んだスレッドの CPU 時間（[defaultCompileClock]） */
    private val clock: () -> Long = defaultCompileClock(),
) : WasmRuntime {
    /** ランタイムコンパイラで動かすランタイムを作る */
    public constructor() : this(true) {
        defaultConfiguration = true
    }

    /**
     * 引数なしのコンストラクターで作った（コンパイルし、時計も既定）。テストで、読み込んだ結果をランタイムのインスタンスをまたいで
     * 使い回してよいかの判定に使う（#991。インタプリタだけのものや時計を差し替えたものとは共有しない）
     */
    internal var defaultConfiguration: Boolean = false
        private set

    override val name: String get() = NAME

    @Throws(WasmLoadException::class)
    override fun load(bytes: ByteArray): WasmModule = load(bytes, Long.MAX_VALUE)

    /**
     * [timeLeftNanos] は、呼んだ側の読み込みの時間の上限の残り。[MAX_COMPILE_RETRIES] 回を超えるコンパイルのやり直しを、
     * その半分（から、このモジュールのパースと検証に使った時間を引いたもの）と [COMPILE_RETRY_BUDGET_NANOS] の小さい方に収める（[retryBudget]。#446）
     */
    @Throws(WasmLoadException::class)
    override fun load(bytes: ByteArray, timeLeftNanos: Long): WasmModule {
        val start = clock()
        val interpreted = parseModule(bytes)
        if (!compile) return interpreted
        val budget = retryBudget(timeLeftNanos, clock() - start)
        val compiled = compileModule(interpreted.module, budget, moduleLabel(bytes))
        // コンパイルできなければ、インタプリタで動かす（パースと検証は通っているので、動かせる WASM を断らない）
        val module = compiled.module ?: return interpreted.also { it.interpreterUse = compiled.use }
        // パースした命令の列（module）はここで手放す。持つのは関数の本体を除いたモジュール（インタプリタで動かす関数の本体は残る）と
        // コンパイル結果だけ
        return EndiveWasmModule(module.wasmModule(), module, compiled.interpreted).also { it.interpreterUse = compiled.use }
    }

    /** パースだけして、import と export を返す（#757。コンパイルしない） */
    @Throws(WasmLoadException::class)
    override fun describe(bytes: ByteArray): WasmModuleShape {
        val m = parseModule(bytes)
        return object : WasmModuleShape {
            override val imports = m.imports
            override val exports = m.exports
        }
    }

    /** パースして、インタプリタで動かすモジュールにする */
    @Throws(WasmLoadException::class)
    private fun parseModule(bytes: ByteArray): EndiveWasmModule {
        val module = try {
            Parser.parse(bytes)
        } catch (e: MalformedException) {
            throw WasmLoadException("WASM として読めない: ${e.message}", e)
        } catch (e: InvalidException) {
            throw WasmLoadException("WASM の検証に通らない: ${e.message}", e)
        } catch (e: WasmEngineException) {
            throw WasmLoadException("WASM を読み込めない: ${e.message}", e)
        } catch (e: RuntimeException) {
            // パーサーが壊れた入力で想定外の例外を出すことがある
            throw WasmLoadException("WASM を読み込めない: $e", e)
        } catch (e: StackOverflowError) {
            throw WasmLoadException("WASM を読み込めない: 入れ子が深すぎる", e)
        }
        return try {
            // Endive の検証が確かめない定数式の型を確かめる（パッシブ・宣言の要素は #893、グローバル・アクティブの要素・表・データは #942）。
            // 正しくない WASM を「対応していない作り」と報告しないよう、#706 の判定より先に見る
            invalidConstExpr(module)?.let { throw WasmLoadException("WASM の検証に通らない: $it") }
            // Endive が違う関数を置いてしまう形は、動かす前に断る（#706。正しくない結果になるので、対応しない作りとして扱う）
            foreignFuncRefInitializer(module)?.let {
                throw WasmLoadException("対応していない作り: $it（Endive が別の関数を置いてしまう。#706）")
            }
            EndiveWasmModule(module, null)
        } catch (e: WasmLoadException) {
            throw e
        } catch (e: RuntimeException) {
            // Endive の検証は export の番号の範囲などを確かめないことがある（#40 で見つけた）。ここで分かったものも断る
            throw WasmLoadException("WASM の検証に通らない: $e", e)
        }
    }

    /**
     * コンパイルの結果。[interpreted] は、インタプリタで動かす関数の番号（import を含めた番号）。自分で決めて渡した関数か、
     * Endive が自動で回したとき（[InterpreterFallback.SILENT]）はコンパイル後のモジュールに本体が残った関数（[retainedFunctions]。
     * 自動で回した関数と、そこから末尾呼び出しで届く関数なので、本当の集合より広いことがある）。
     * [module] が `null` なら、モジュール全体をインタプリタで動かす。[use] は、インタプリタで動くコードを持つことの数え口（#931。
     * モジュールに持たせる。全部コンパイルできたら `null`）
     */
    private class Compilation(val module: CompiledModule?, val interpreted: Set<Int>, val use: InterpreterUse?) {
        companion object {
            /** モジュール全体をインタプリタで動かす（[reason] で） */
            fun whole(reason: InterpreterReason): Compilation = Compilation(null, emptySet(), InterpreterUse.wholeModule(reason))
        }
    }

    /**
     * [module] をコンパイルする。できなければ、モジュール全体をインタプリタで動かす結果（[Compilation.module] が `null`。ログに出す）。
     *
     * SIMD を使う関数は、初めからインタプリタに回す（[simdFunctions]。#424）。関数の型（引数・戻り値）に v128 があると、
     * 関数の呼び出しの橋渡しをコンパイルできないので、モジュール全体をインタプリタで動かす。
     *
     * インタプリタに回す関数がなければ、JVM のメソッドの上限を超える関数は、Endive がその関数だけインタプリタに回す
     * （[InterpreterFallback.SILENT]。#680）。回す関数を決めて渡すと、Endive の制約でこの自動の切り替えを使えない（[InterpreterFallback.FAIL]）ので、
     * 失敗の例外から大きすぎる関数を読み取って足し、やり直す（[compileWithFallback]。#446）。
     * [budgetNanos] は、[MAX_COMPILE_RETRIES] 回を超えるやり直しに掛ける時間の上限（[retryBudget]）。
     *
     * ただし、例外処理（タグ）を使うモジュールでは、関数ごとにインタプリタとコンパイルしたコードを混ぜない
     * （混ぜると、Lua のコルーチンの戻り値が欠けるなど、結果が変わった。#341）。1つでもコンパイルできない関数があれば、
     * モジュール全体をインタプリタで動かす。SIMD を使う関数があるときも同じ。
     *
     * 1回目に [InterpreterFallback.SILENT] で Endive が自動で回した関数は、Endive がコンパイルの結果の公開の口に出さないので、
     * コンパイル後のモジュールに本体が残った関数（[retainedFunctions]）から読む（#680）。
     *
     * [label] は、ログでモジュールを見分ける名前（[moduleLabel]。出すときだけ作る）。
     */
    private fun compileModule(module: EndiveModule, budgetNanos: Long, label: () -> String): Compilation {
        val mixable = module.tagSection().map { it.tagCount() == 0 }.orElse(true) && module.importSection().count(ExternalType.TAG) == 0
        val simd = simdFunctions(module)
        val describe = { functions: Set<Int> -> describeFunctions(functions, functionNames(module)) }
        if (simd == null) {
            FALLBACK_LOG.report(InterpreterReason.V128_SIGNATURE) {
                "${label()} の型（関数の型、struct のフィールド、array の要素）に v128 があってコンパイルできないので、モジュール全体をインタプリタで動かす"
            }
            return Compilation.whole(InterpreterReason.V128_SIGNATURE)
        }
        if (simd.isNotEmpty() && !mixable) {
            FALLBACK_LOG.report(InterpreterReason.SIMD_WITH_EXCEPTIONS) {
                "例外処理を使う ${label()} の関数 ${describe(simd)} が SIMD を使っていてコンパイルできないので、モジュール全体をインタプリタで動かす"
            }
            return Compilation.whole(InterpreterReason.SIMD_WITH_EXCEPTIONS)
        }
        val imported = module.importSection().count(ExternalType.FUNCTION)
        val defined = imported until imported + module.codeSection().functionBodies().size
        val result = compileWithFallback(simd, mixable, defined, { largeFunctions(module) }, clock, budgetNanos, label, describe) { interpreted ->
            // メソッドの名前から関数の番号を読むので、名前の付け方を固定する（既定。func_<番号>）
            val builder = MachineFactoryCompiler.builder(module).withMethodPrefixer(MethodPrefixer.defaultPrefixer())
            when {
                !mixable -> builder.withInterpreterFallback(InterpreterFallback.FAIL)
                // WARN は Endive が標準エラーに直に出す（`latest.log` の外か、1回ごとの `[STDERR]` の行になる）ので使わない（#680）
                interpreted.isEmpty() -> builder.withInterpreterFallback(InterpreterFallback.SILENT)
                // 決めた関数をインタプリタに回すときは、ほかの関数の自動の切り替えは使えない（Endive の制約）
                else -> builder.withInterpreterFallback(InterpreterFallback.FAIL).withInterpretedFunctions(interpreted)
            }
            builder.compileModule()
        } ?: return Compilation.whole(InterpreterReason.WHOLE_MODULE)
        if (result.interpreted.isEmpty() && mixable) {
            // Endive が自動で回した関数（SILENT）。ここで自分の言葉で出す
            val auto = retainedFunctions(result.value.wasmModule())
            if (auto.isNotEmpty()) {
                FALLBACK_LOG.report(InterpreterReason.TOO_LARGE_FUNCTIONS) {
                    "${label()} の関数 ${describe(auto)} をインタプリタで動かす（JVM のメソッドの上限を超える関数と、そこから末尾呼び出しで届く関数）"
                }
            }
            return Compilation(result.value, auto, InterpreterUse.functions(InterpreterReason.TOO_LARGE_FUNCTIONS, auto.size))
        }
        return Compilation(result.value, result.interpreted, result.reason?.let { InterpreterUse.functions(it, result.interpreted.size) })
    }

    public companion object {
        /** ランタイムの名前 */
        public const val NAME: String = "endive"

        private val LOG: System.Logger = System.getLogger("kibicomputers.wasm")

        /** インタプリタに回したことのログ。JVM 全体で1つ（#680。[CompileFallbackLog]） */
        internal val FALLBACK_LOG: CompileFallbackLog = CompileFallbackLog(LOG)

        /**
         * コンパイルした後のモジュール（[CompiledModule.wasmModule]）に本体が残っている関数の番号（import を含めた番号。#680）。
         * Endive は、インタプリタで動かす関数と、そこから末尾呼び出しで届く関数の本体だけを残す（`WasmModule.stripped`）。
         * なので、インタプリタに回した関数の集合と同じか、それより広い（間接の末尾呼び出しがあれば、定義した関数の全部になる）
         */
        internal fun retainedFunctions(stripped: EndiveModule): Set<Int> {
            val imported = stripped.importSection().count(ExternalType.FUNCTION)
            val bodies = stripped.codeSection().functionBodies()
            val out = LinkedHashSet<Int>()
            for (i in bodies.indices) if (bodies[i] != null) out += i + imported
            return out
        }

        /**
         * ログの1行に並べる関数の番号の数の上限。プレイヤーが作るモジュールは関数を 100,000 まで持てるので、行の大きさを抑える（#680）。
         * インタプリタに回したことのログ（[FALLBACK_LOG]）で関数を並べる箇所は、すべて [describeFunctions] を通す
         */
        internal const val MAX_LOGGED_FUNCTIONS: Int = 16

        /** ログに添える関数の名前（name セクションのもの）の長さの上限。名前はプレイヤーが決めるので、1つの長さも抑える（#680） */
        internal const val MAX_LOGGED_NAME_CHARS: Int = 48

        /**
         * ログに出す関数の番号の並び。[MAX_LOGGED_FUNCTIONS] を超えた分は個数だけにする。[nameOf] が名前を返す関数には、
         * `番号(名前)` の形で名前を添える（[MAX_LOGGED_NAME_CHARS] で切り、制御文字は `?` にする）
         */
        internal fun describeFunctions(functions: Set<Int>, nameOf: (Int) -> String? = { null }): String {
            fun one(id: Int): String {
                val name = nameOf(id)?.takeIf { it.isNotEmpty() } ?: return id.toString()
                val shown = name.take(MAX_LOGGED_NAME_CHARS).map { if (it.isISOControl()) '?' else it }.joinToString("")
                return "$id($shown${if (name.length > MAX_LOGGED_NAME_CHARS) "…" else ""})"
            }
            val head = functions.asSequence().take(MAX_LOGGED_FUNCTIONS).joinToString(", ", transform = ::one)
            return if (functions.size <= MAX_LOGGED_FUNCTIONS) "[$head]" else "[$head, … ほか ${functions.size - MAX_LOGGED_FUNCTIONS} 個]"
        }

        /** [module] の name セクションから関数の名前を引く（import を含めた番号）。name セクションがなければ、いつも `null` */
        internal fun functionNames(module: EndiveModule): (Int) -> String? {
            val names = module.nameSection() ?: return { null }
            return { id -> runCatching { names.nameOfFunction(id) }.getOrNull() }
        }

        /**
         * ログに出すモジュールの名前。ランタイムに渡したバイト列（燃料の計測コードの挿入や待機点への変換の後）の SHA-256 の先頭 16 桁（#680）。
         * `ProgramCache` のキー（変換の前のバイト列）とは別の値だが、同じプログラムなら同じになる。呼んだときに計算する
         */
        internal fun moduleLabel(bytes: ByteArray): () -> String = {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            "WASM（SHA-256 ${java.util.HexFormat.of().formatHex(digest, 0, 8)}…）"
        }

        /**
         * コンパイルに失敗し、失敗の例外から大きすぎる関数を特定できないとき、命令の数が多い関数をインタプリタに回してやり直す回数
         * （#446 の前からの値）。特定できた分も含めたやり直しの合計のうち、この回数までは時間を見ずに許す（[compileWithFallback]）
         */
        internal const val MAX_COMPILE_RETRIES: Int = 3

        /**
         * 失敗の例外から特定した大きすぎる関数をインタプリタに回して、やり直す回数の上限（#446）。
         * 1回の失敗で1つしか分からないので、大きすぎる関数が k 個なら、モジュール全体を k+1 回コンパイルする。
         * コンパイルは割り込めず、全台で共有するコンパイル用のスレッドを使う（ADR 0750）ので、悪い入力でも回数を抑える。仮の値（#251）
         */
        internal const val MAX_IDENTIFIED_RETRIES: Int = 32

        /**
         * [MAX_COMPILE_RETRIES] 回を超えるやり直しの前に見る、コンパイルに使う時間（ナノ秒）の上限（#446）。それまでの合計に、1回にかかった最大の時間を
         * 足して超えるなら、やり直さずにモジュール全体をインタプリタで動かす。読み込みの上限（CPU 時間で 8 秒。`LoadDeadline`）で
         * 断られないよう、その半分にする。呼んだ側が上限の残りを渡したときは、その半分の方が小さければそちらを使う（[retryBudget]。
         * 構造の確認・燃料の挿入・待機点への変換に時間を使った後や、コンポーネントの2つ目以降の core モジュール）。[MAX_COMPILE_RETRIES] 回までのやり直しには掛けない（#446 の前にコンパイルできたモジュールを、
         * 時間のせいで全体インタプリタに落とさないため。そこまでは、#446 の前と同じ最大 4 回のコンパイル）。仮の値（#251）
         */
        internal const val COMPILE_RETRY_BUDGET_NANOS: Long = 4_000_000_000L

        /**
         * やり直しの時間を測る既定の時計。呼んだスレッドの CPU 時間（測れない JVM では経過時間）。
         * 混んだサーバーや GC の停止で待たされただけで、同じモジュールが全体インタプリタに落ちたり落ちなかったりしないよう
         * （結果は `ProgramCache` で共有されて残る）、検証の時間の上限（`LoadDeadline`）と同じく CPU 時間で測る。
         */
        internal fun defaultCompileClock(): () -> Long = LoadDeadline.cpuClock() ?: System::nanoTime

        /**
         * [MAX_COMPILE_RETRIES] 回を超えるやり直しに掛ける時間の上限（ナノ秒）。呼んだ側の時間の上限の残り [timeLeftNanos] の半分から、
         * このモジュールのパースと検証に使った時間 [used] を引いたものと、[COMPILE_RETRY_BUDGET_NANOS] の小さい方（負なら 0）。
         * 半分にするのは、1回の最大で見積もった次のコンパイルの時間が外れても、呼んだ側の上限で断られないよう余らせるため
         */
        internal fun retryBudget(timeLeftNanos: Long, used: Long): Long =
            minOf(COMPILE_RETRY_BUDGET_NANOS, timeLeftNanos / 2 - used.coerceAtLeast(0)).coerceAtLeast(0)

        /**
         * [compile] でコンパイルし、失敗したら関数をインタプリタに回してやり直す。結果か、諦めたら `null`（ログに出す）。
         *
         * [compile] には、インタプリタで動かす関数の番号を渡す（初めは [simd]）。失敗したら:
         * - [mixable] でなければ、やり直さない（例外処理を使うモジュール。#341）。
         * - 失敗の例外から大きすぎる関数（[tooLargeFunction]）を特定でき、まだ回していなければ、それを足す
         *   （特定できた分。[MAX_IDENTIFIED_RETRIES] 回まで）。
         * - 特定できなければ（上限の近くで ASM が上限超えを見分けられずに別の例外で落ちることがある。待機点への変換の後に
         *   燃料の計測コードを挿入した lua.wasm の `luaV_execute`。#37）、[largeFunctions] の大きい順に、まだ回していないものを足す
         *   （特定できない分。[MAX_COMPILE_RETRIES] 回まで。#446 の前と同じ）。
         *
         * 特定できた分と特定できない分を合わせたやり直しが [MAX_COMPILE_RETRIES] 回に達するまでは、時間を見ずにやり直す
         * （#446 の前と同じ。コンパイルは最大 4 回）。それを超えるやり直しは、それまでのコンパイルの時間の合計（特定できない失敗の分も含む）に
         * 1回の最大を足して [budgetNanos]（既定は [COMPILE_RETRY_BUDGET_NANOS]。[retryBudget]）を超えないときだけ行う。特定できた分と特定できない分は別々に数える
         * （特定できない分の枠は、特定できた分で減らない）。
         *
         * [defined] は定義した関数の番号の範囲（import を含めた番号）。[clock] はナノ秒の時刻（このスレッドで呼ぶ。既定は [defaultCompileClock]。
         * テストで差し替える）。[label] と [describe] は、ログのモジュールの名前と関数の並び（[compileModule] が渡す）。
         *
         * ログの理由は、SIMD を使う関数だけを回したなら [InterpreterReason.SIMD_FUNCTIONS]、やり直しで関数を足したなら
         * [InterpreterReason.TOO_LARGE_FUNCTIONS]（SIMD のモジュールが先に読まれても、大きすぎる関数の最初の1回は WARNING になる）。
         */
        internal fun <T : Any> compileWithFallback(
            simd: Set<Int>,
            mixable: Boolean,
            defined: IntRange,
            largeFunctions: () -> List<Int>,
            clock: () -> Long = defaultCompileClock(),
            budgetNanos: Long = COMPILE_RETRY_BUDGET_NANOS,
            label: () -> String = { "WASM" },
            describe: (Set<Int>) -> String = { describeFunctions(it) },
            compile: (Set<Int>) -> T,
        ): Fallback<T>? {
            val interpreted = LinkedHashSet(simd)
            var candidates: List<Int>? = null
            var identified = 0
            var unidentified = 0
            var spent = 0L
            var longest = 0L
            while (true) {
                val start = clock()
                val failure = try {
                    val value = compile(interpreted.toSet())
                    var reason: InterpreterReason? = null
                    if (interpreted.isNotEmpty()) {
                        if (identified + unidentified > 0) {
                            reason = InterpreterReason.TOO_LARGE_FUNCTIONS
                            FALLBACK_LOG.report(InterpreterReason.TOO_LARGE_FUNCTIONS) {
                                "${label()} の関数 ${describe(interpreted)} をインタプリタで動かす（SIMD を使う関数と、コンパイルできない関数。" +
                                    "やり直し: 特定した関数で $identified 回、特定できずに $unidentified 回）"
                            }
                        } else {
                            reason = InterpreterReason.SIMD_FUNCTIONS
                            FALLBACK_LOG.report(InterpreterReason.SIMD_FUNCTIONS) {
                                "${label()} の関数 ${describe(interpreted)} をインタプリタで動かす（SIMD を使う関数）"
                            }
                        }
                    }
                    return Fallback(value, interpreted.toSet(), reason)
                } catch (e: RuntimeException) {
                    e
                } catch (e: StackOverflowError) {
                    e
                } catch (e: LinkageError) {
                    // 生成したクラスを JVM が受け付けない（VerifyError など）
                    e
                }
                val took = clock() - start
                spent += took
                longest = maxOf(longest, took)
                if (!mixable) return giveUp(failure, "")
                // #446 の前から許していた回数までは、時間を見ない
                val tooSlow = identified + unidentified >= MAX_COMPILE_RETRIES && spent + longest > budgetNanos
                val id = tooLargeFunction(failure, defined)?.takeIf { it !in interpreted }
                if (id != null) {
                    if (identified >= MAX_IDENTIFIED_RETRIES) return giveUp(failure, "（大きすぎる関数が多すぎる）")
                    if (tooSlow) return giveUp(failure, "（やり直すと時間がかかりすぎる）")
                    identified++
                    interpreted += id
                } else {
                    val next = (candidates ?: largeFunctions().also { candidates = it }).firstOrNull { it !in interpreted }
                    if (unidentified >= MAX_COMPILE_RETRIES || next == null) return giveUp(failure, "")
                    if (tooSlow) return giveUp(failure, "（やり直すと時間がかかりすぎる）")
                    unidentified++
                    interpreted += next
                }
            }
        }

        private fun giveUp(failure: Throwable, reason: String): Nothing? {
            FALLBACK_LOG.report(InterpreterReason.WHOLE_MODULE) { "WASM をコンパイルできないので、モジュール全体をインタプリタで動かす$reason: $failure" }
            return null
        }

        /**
         * [compileWithFallback] の結果。[interpreted] は、インタプリタで動かすと決めて渡した関数の番号、[reason] はログに出した理由
         * （[interpreted] が空なら `null`。#931）
         */
        internal class Fallback<T : Any>(val value: T, val interpreted: Set<Int>, val reason: InterpreterReason? = null)

        /** Endive が関数の本体をコンパイルしたメソッドの名前（既定の [MethodPrefixer]。`func_<番号>`） */
        private val FUNCTION_METHOD = Regex("^" + Regex.escape(MethodPrefixer.DEFAULT_PREFIX) + "_(\\d+)$")

        /**
         * [failure] が、JVM のメソッドの上限を超えた関数によるものなら、その関数の番号（import を含めた番号。[defined] の中のもの）。
         * 原因の連鎖に ASM の [MethodTooLargeException] があり、そのメソッドが関数の本体（`func_<番号>`）のときだけ読む
         * （`call_<番号>` などの橋渡しや `call_indirect_<型の番号>` は関数の本体ではない）。例外のメッセージは読まない。
         *
         * core が ASM のクラスを直接参照するのはここだけで、参照は失敗の例外を調べるときに初めて解決される（実行時の ASM は、MC ではローダーのもの）。
         * 解決できなければ特定できなかったものとして扱う。実行環境で解決できることは [pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck.readsTooLargeFunction]
         * （GameTest）で確かめる。
         */
        internal fun tooLargeFunction(failure: Throwable, defined: IntRange): Int? = try {
            readTooLargeFunction(failure, defined)
        } catch (e: LinkageError) {
            null
        }

        internal fun readTooLargeFunction(failure: Throwable, defined: IntRange): Int? {
            var e: Throwable? = failure
            var depth = 0
            while (e != null && depth++ < 16) {
                if (e is MethodTooLargeException) {
                    val name = e.methodName ?: return null
                    val id = FUNCTION_METHOD.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                    return id.takeIf { it in defined }
                }
                e = e.cause?.takeIf { it !== e }
            }
            return null
        }

        /** やり直しでインタプリタに回す候補にする、関数の命令の数の下限（これより小さい関数は JVM のメソッドの上限に届かない） */
        private const val LARGE_FUNCTION_INSTRUCTIONS: Int = 4096

        /**
         * SIMD（v128）を使う関数の番号（import を含めた番号）。関数の型（引数・戻り値）か、struct のフィールド・array の要素
         * （GC の提案）に v128 があるなら `null`（#424、#891）。
         *
         * SIMD の命令（`0xFD` の接頭辞）、v128 の局所変数、v128 のグローバルの読み書きのどれかを含む関数を選ぶ。
         * v128 の値は、これらと、型に v128 がある関数の呼び出し・ブロック、v128 のフィールド・要素の読み出し（`struct.get`・`array.get`）
         * からしか生まれない。後の 2 つは型で断るので、残りの関数は v128 を扱わない。
         */
        internal fun simdFunctions(module: EndiveModule): Set<Int>? {
            val types = module.typeSection()
            for (i in 0 until types.subTypeCount()) {
                // 型の番号は rec グループを平らにした番号。struct・array の型は funcType() が null（#891）
                val comp = types.getSubType(i).compType()
                val func = comp.funcType()
                val v128 = when {
                    func != null -> func.params().any(::isV128) || func.returns().any(::isV128)
                    comp.structType() != null -> comp.structType().fieldTypes().any { isV128(it.storageType().valType()) }
                    comp.arrayType() != null -> isV128(comp.arrayType().fieldType().storageType().valType())
                    else -> false
                }
                if (v128) return null
            }
            val imports = module.importSection()
            val v128Globals = HashSet<Int>()
            var globalIndex = 0
            for (i in 0 until imports.importCount()) {
                val imp = imports.getImport(i)
                if (imp is GlobalImport) {
                    if (isV128(imp.type())) v128Globals += globalIndex
                    globalIndex++
                }
            }
            for (global in module.globalSection().globals()) {
                if (isV128(global.valueType())) v128Globals += globalIndex
                globalIndex++
            }
            val imported = imports.count(ExternalType.FUNCTION)
            val bodies = module.codeSection().functionBodies()
            val result = LinkedHashSet<Int>()
            for (i in bodies.indices) {
                val body = bodies[i]
                val usesSimd = body.localTypes().any(::isV128) || body.instructions().any { ins ->
                    val op = ins.opcode()
                    op.opcode() ushr 8 == SIMD_PREFIX ||
                        ((op == OpCode.GLOBAL_GET || op == OpCode.GLOBAL_SET) && ins.operand(0).toInt() in v128Globals)
                }
                if (usesSimd) result += i + imported
            }
            return result
        }

        private fun isV128(type: ValType?): Boolean = type == ValType.V128

        /**
         * 定数式で GC のオブジェクト（struct・array）を作る命令（`struct.new` など）を初期値に持つグローバル・要素セグメントがあれば、
         * その名前（「グローバル 3」「要素セグメント 0」）。なければ `null`（#851）。`ref.i31` などは、オブジェクトを作らないので数えない
         */
        internal fun gcObjectInitializer(module: EndiveModule): String? {
            module.globalSection().globals().forEachIndexed { i, g ->
                if (g.initInstructions().any(::isGcAllocation)) return "グローバル $i"
            }
            val elements = module.elementSection()
            for (i in 0 until elements.elementCount()) {
                if (elements.getElement(i).initializers().any { init -> init.any(::isGcAllocation) }) return "要素セグメント $i"
            }
            return null
        }

        /**
         * Endive が、ほかのインスタンスの関数の参照を、自分の関数の番号として置いてしまう定数式があれば、その場所と理由。なければ `null`（#706）。
         * 場所の番号は [gcObjectInitializer] と同じく、セクションの中の番号（グローバルは import を数えない）
         *
         * import したグローバルの funcref の値は、渡した側のインスタンスでの関数の番号になっている。Endive 1.2.0 は、アクティブの要素では
         * その持ち主のインスタンスを引く（`ConstantEvaluators.computeConstantInstance`）が、次の形では引かずに自分の番号として置く
         * （別の関数が呼ばれるか、型が合わずにトラップする）。読み込みで断る:
         * - パッシブの要素（`table.init` で置く。`OpcodeImpl.TABLE_INIT`）の式が、import したグローバルを `global.get` する
         * - 関数の参照の型の、定義したグローバルの初期値が、import したグローバルを `global.get` する（`Instance.initialize` が持ち主を
         *   自分にする。そのグローバルを要素の式で使うと、アクティブ・パッシブのどちらも違う関数になる）
         * - 定数式の `struct.new`・`array.new`・`array.new_fixed` が、番号で持つ参照の型の import したグローバルを `global.get` して、
         *   フィールド・要素に入れる（Endive 1.2.0 の `ConstantEvaluators.computeConstant` は、funcref のフィールドを渡した側での番号のまま
         *   int で入れる。`struct.get` → `call_ref`・`table.set` で、受け取った側の同じ番号の関数になる）。グローバルの初期値、どの要素セグメントの式、
         *   表の初期値の式（例: `(table 1 (ref null $S) (struct.new $S (global.get $fr)))`。`Instance` のコンストラクターが同じ
         *   `computeConstant` で作って `setObjRef` で置く）を見る
         *
         * 表の初期値の式が import したグローバルを直に `global.get` する形（`(table 8 funcref (global.get $fr))`）は、アクティブの要素と同じく
         * `computeConstantInstance` で持ち主を引いて置くので正しく、断らない。
         * 最初の 2 つは、JVM で番号（int）として持つ参照の型（funcref・関数の型の参照。`isObjectRef` でないもの）だけ。externref・
         * GC の参照そのもの（struct・array の参照）は参照ごと置くので正しい。宣言の要素は `table.init` で使えないので見ない。
         * 関数の本体で値として運ぶ形（`global.get` → `table.set`・`call_ref`、import した表からの `table.get`）も同じく間違うが、
         * 定数式ではないので、ここでは見ない（#932）。Endive が直ったら、この判定を外す（#932）
         */
        internal fun foreignFuncRefInitializer(module: EndiveModule): String? {
            val imports = module.importSection()
            val importedGlobalTypes = ArrayList<ValType>()
            for (i in 0 until imports.importCount()) {
                val imp = imports.getImport(i)
                if (imp is GlobalImport) importedGlobalTypes += imp.type()
            }
            if (importedGlobalTypes.isEmpty()) return null
            val types = module.typeSection()
            fun heldAsIndex(type: ValType) = type.isReference && !ValType.isObjectRef(type.id(), types)
            fun readsImportedGlobal(init: List<Instruction>) =
                init.any { it.opcode() == OpCode.GLOBAL_GET && it.operand(0) < importedGlobalTypes.size }
            // GC のオブジェクトを作る式が、番号で持つ参照の型の import したグローバルを指す形（中のフィールド・要素に、番号のまま入る）
            fun putsImportedFuncRefInObject(init: List<Instruction>) =
                init.any(::takesOperandsIntoObject) &&
                    init.any { it.opcode() == OpCode.GLOBAL_GET && it.operand(0) < importedGlobalTypes.size && heldAsIndex(importedGlobalTypes[it.operand(0).toInt()]) }
            module.globalSection().globals().forEachIndexed { i, g ->
                if (heldAsIndex(g.valueType()) && readsImportedGlobal(g.initInstructions())) {
                    return "グローバル $i の初期値が、import したグローバルの関数の参照を指す"
                }
                if (putsImportedFuncRefInObject(g.initInstructions())) {
                    return "グローバル $i の初期値が、GC のオブジェクトの中に import したグローバルの関数の参照を入れる"
                }
            }
            val elements = module.elementSection()
            for (i in 0 until elements.elementCount()) {
                val el = elements.getElement(i)
                if (el is PassiveElement && heldAsIndex(el.type()) && el.initializers().any(::readsImportedGlobal)) {
                    return "要素セグメント $i（パッシブ）の式が、import したグローバルの関数の参照を指す"
                }
                if (el.initializers().any(::putsImportedFuncRefInObject)) {
                    return "要素セグメント $i の式が、GC のオブジェクトの中に import したグローバルの関数の参照を入れる"
                }
            }
            val tables = module.tableSection()
            for (i in 0 until tables.tableCount()) {
                val init: List<Instruction>? = tables.getTable(i).initialize()
                if (init != null && putsImportedFuncRefInObject(init)) {
                    return "表 $i の初期値の式が、GC のオブジェクトの中に import したグローバルの関数の参照を入れる"
                }
            }
            return null
        }

        /**
         * 定数式のうち、正しくないものがあれば、その場所と理由。なければ `null`（#893・#942）。
         * 場所の番号は [gcObjectInitializer] と同じく、セクションの中の番号（グローバルは import を数えない）
         *
         * Endive 1.2.0 の検証（`Validator.validateConstantExpression`）は、式の結果の型しか確かめず、命令が取る値の型を確かめない
         * （`(global i32 (i32.add (i64.const 1) (i64.const 2)))`、`struct.new` のフィールドの型が違う値など）。さらに、パッシブ（flag 1・5）・
         * 宣言（flag 3・7）の要素の式は、定数式としても確かめない（`drop` なども通る）。どちらも、型の合わない値が `struct.get`・`table.init`・
         * `call_indirect` などに届き、正しくない値が使われるか、JVM の例外（`ArrayIndexOutOfBoundsException` など）で止まる。
         * ここでは、次の場所の式を同じ規則（[constExprProblem]）で確かめる:
         * - グローバルの初期値: 期待する型はグローバルの型。`global.get` は、import したものと自分より前のグローバルだけ
         * - 表の初期値: 期待する型は表の要素の型。`global.get` は import したグローバルだけ（表のセクションはグローバルのセクションより前にあり、
         *   仕様でも定義したグローバルは見えない。Endive は全グローバルで通すが、インスタンスを作るとき、表の初期値をグローバルの初期化より前に
         *   評価するので、定義したグローバルは読めない）
         * - 要素セグメント・データセグメントのオフセット（アクティブ）: i32（Endive と同じ）。全グローバル
         * - 要素セグメントの項目（アクティブ・パッシブ・宣言）: 期待する型は要素の型（アクティブで要素の型が表の型に合うことは、Endive が確かめる）。
         *   全グローバル
         *
         * 式の先頭の `ref.func` の範囲（flag 1・3 の番号の並びを含む）は、Endive の検証（`Validator` のコンストラクターの `declaredFunctions`）が
         * もう断るので、ここに届くのは先頭でない `ref.func` だけ。
         * 型が合うかは [valTypeMatches]（Endive の `ValType.matches` の、`none` を eq・struct・array・i31 の部分型として扱わない穴をふさいだもの）で決める。
         * 判定は読み込みのときに 1 回だけ（関数の型とグローバルの一覧は 1 回だけ作る）。コストは「命令が取る値の数（式の長さの和に比例）×
         * 型の照合 1 回のコスト」で、式の長さだけには比例しない: 型の番号の違う型どうしの照合（Endive の `ValType.matches` →
         * `heapTypeSubtype`）は、上位の型を 1 段ずつたどり、各段で rec グループの全部の型を構造で比べ（`TypeSection.canonicallyEquivalent`）、
         * グループの外の型への参照は結果を覚えずに比べ直すので、型セクションの形しだいで大きくなる。同じ照合は Endive 自身の検証
         * （関数の本体と、定数式の結果の型）も 1 命令・1 式ごとに呼ぶので、ここで新しい最悪の場合を足すわけではない（回数が定数式の取る値の分だけ増える）。
         * Endive が直ったら、この判定を外す
         */
        internal fun invalidConstExpr(module: EndiveModule): String? {
            val imports = module.importSection()
            val funcTypes = ArrayList<Int>()
            val globals = ArrayList<Pair<ValType, Boolean>>() // 型と、可変か
            for (i in 0 until imports.importCount()) {
                when (val imp = imports.getImport(i)) {
                    is FunctionImport -> funcTypes += imp.typeIndex()
                    is GlobalImport -> globals += imp.type() to (imp.mutabilityType() != MutabilityType.Const)
                    else -> {}
                }
            }
            val importedGlobals = globals.size
            val functions = module.functionSection()
            for (i in 0 until functions.functionCount()) funcTypes += functions.getFunctionType(i)
            val defined = module.globalSection().globals()
            for (g in defined) globals += g.valueType() to (g.mutabilityType() != MutabilityType.Const)
            val types = module.typeSection()
            // 一覧は 1 回だけ作り、場所ごとに読めるグローバルの数（上限）だけを変える
            fun check(expr: List<Instruction>, expected: ValType, what: String, limit: Int, scope: String): String? =
                constExprProblem(expr, expected, what, funcTypes, globals, limit, scope, types)
            defined.forEachIndexed { i, g ->
                check(g.initInstructions(), g.valueType(), "グローバルの型", importedGlobals + i, "import したものか前のグローバルだけ")
                    ?.let { return "グローバル $i の初期値: $it" }
            }
            val tables = module.tableSection()
            for (i in 0 until tables.tableCount()) {
                val t = tables.getTable(i)
                val init: List<Instruction> = t.initialize() ?: continue
                check(init, t.elementType(), "表の要素の型", importedGlobals, "import したものだけ")?.let { return "表 $i の初期値: $it" }
            }
            val elements = module.elementSection()
            for (i in 0 until elements.elementCount()) {
                val el = elements.getElement(i)
                val kind = when (el) {
                    is ActiveElement -> "アクティブ"
                    is PassiveElement -> "パッシブ"
                    else -> "宣言"
                }
                if (el is ActiveElement) {
                    check(el.offset(), ValType.I32, "オフセットの型", globals.size, "")
                        ?.let { return "要素セグメント $i（$kind）のオフセット: $it" }
                }
                el.initializers().forEachIndexed { j, init ->
                    check(init, el.type(), "要素の型", globals.size, "")?.let { return "要素セグメント $i（$kind）の項目 $j: $it" }
                }
            }
            val data = module.dataSection()
            for (i in 0 until data.dataSegmentCount()) {
                val ds = data.getDataSegment(i) as? ActiveDataSegment ?: continue
                check(ds.offsetInstructions(), ValType.I32, "オフセットの型", globals.size, "")
                    ?.let { return "データセグメント $i のオフセット: $it" }
            }
            return null
        }

        /** [constExprProblem] の途中で、正しくない理由が分かったときの中断 */
        private class ConstExprProblem(message: String) : RuntimeException(message)

        /**
         * [expr] が [expected] の型の正しい定数式でなければ、その理由（[invalidConstExpr]）。[what] は [expected] の呼び名（文面に使う）。
         * `global.get` で読めるのは、[globals] の先頭の [globalLimit] 個だけ（[scope] はその範囲の説明。全部読めるときは使わない）
         */
        private fun constExprProblem(
            expr: List<Instruction>,
            expected: ValType,
            what: String,
            funcTypes: List<Int>,
            globals: List<Pair<ValType, Boolean>>,
            globalLimit: Int,
            scope: String,
            types: TypeSection,
        ): String? {
            val stack = ArrayList<ValType>()
            fun fail(reason: String): Nothing = throw ConstExprProblem(reason)
            fun ref(opcode: Int, typeIdx: Int): ValType = ValType.builder().withOpcode(opcode).withTypeIdx(typeIdx).build()
            fun subType(idx: Long) = if (idx in 0 until types.subTypeCount()) types.getSubType(idx.toInt()) else null
            // 命令 [op] が値を 1 つ取る。型が [want] に合わなければ断る（値の型の検証は Endive にない。#893）。取った値の型を返す
            fun pop(op: OpCode, want: ValType): ValType {
                if (stack.isEmpty()) fail("スタックの値が足りない")
                val actual = stack.removeAt(stack.size - 1)
                if (!valTypeMatches(actual, want, types)) fail("命令 $op が取る値の型（$actual）が期待する型（$want）に合わない")
                return actual
            }
            // struct のフィールド・array の要素の型を、スタックの値の型にしたもの（i8・i16 は i32）
            fun unpacked(storage: StorageType): ValType = if (storage.packedType() != null) ValType.I32 else storage.valType()
            // `*.new_default` で既定値が作れる型（数・v128・null を持てる参照）か
            fun defaultable(storage: StorageType): Boolean =
                storage.packedType() != null || !storage.valType().isReference || storage.valType().isNullable
            try {
                for (ins in expr) {
                    val op = ins.opcode()
                    val push: ValType = when (op) {
                        OpCode.END -> continue
                        OpCode.I32_CONST -> ValType.I32
                        OpCode.I64_CONST -> ValType.I64
                        OpCode.F32_CONST -> ValType.F32
                        OpCode.F64_CONST -> ValType.F64
                        OpCode.V128_CONST -> ValType.V128
                        OpCode.I32_ADD, OpCode.I32_SUB, OpCode.I32_MUL -> {
                            pop(op, ValType.I32)
                            pop(op, ValType.I32)
                            ValType.I32
                        }
                        OpCode.I64_ADD, OpCode.I64_SUB, OpCode.I64_MUL -> {
                            pop(op, ValType.I64)
                            pop(op, ValType.I64)
                            ValType.I64
                        }
                        OpCode.REF_NULL -> {
                            // ヒープ型は、型の番号（0 以上。範囲の中）か、抽象ヒープ型（負。funcref・externref など）。
                            // 範囲の外の番号を型にすると、ValType の解決が InvalidException を投げて、場所のない文面になる
                            val idx = ins.operand(0)
                            val known = if (idx >= 0) {
                                idx < types.subTypeCount()
                            } else {
                                ValType.TypeIdxCode.entries.any { it != ValType.TypeIdxCode.BOT && it.code().toLong() == idx }
                            }
                            if (!known) fail("ヒープ型 $idx がない")
                            ref(ValType.ID.RefNull, idx.toInt())
                        }
                        OpCode.REF_FUNC -> {
                            // 番号は u32 まで。Int にすると 2^31 以上が負になるので Long で比べる
                            val idx = ins.operand(0)
                            if (idx < 0 || idx >= funcTypes.size) fail("関数 $idx がない")
                            ref(ValType.ID.Ref, funcTypes[idx.toInt()])
                        }
                        OpCode.GLOBAL_GET -> {
                            val idx = ins.operand(0)
                            if (idx < 0 || idx >= globals.size) fail("グローバルがない")
                            // 番号は global.get の番号（import を数える）。場所の番号（セクションの中の番号。import を数えない）と区別して書く
                            if (idx >= globalLimit) fail("global.get が読むグローバル $idx（import を数えた番号）はここでは読めない（$scope）")
                            val (type, mutable) = globals[idx.toInt()]
                            if (mutable) fail("可変のグローバルは定数式で読めない")
                            type
                        }
                        OpCode.REF_I31 -> {
                            pop(op, ValType.I32)
                            ref(ValType.ID.Ref, ValType.TypeIdxCode.I31.code())
                        }
                        OpCode.STRUCT_NEW, OpCode.STRUCT_NEW_DEFAULT -> {
                            val st = subType(ins.operand(0))?.compType()?.structType() ?: fail("型 ${ins.operand(0)} が struct でない")
                            if (op == OpCode.STRUCT_NEW) {
                                // 値は、最後のフィールドのものが上にある
                                for (k in st.fieldTypes().indices.reversed()) pop(op, unpacked(st.fieldTypes()[k].storageType()))
                            } else if (!st.fieldTypes().all { defaultable(it.storageType()) }) {
                                fail("型 ${ins.operand(0)} に、null を持てない参照のフィールドがあるので、既定値で作れない")
                            }
                            ref(ValType.ID.Ref, ins.operand(0).toInt())
                        }
                        OpCode.ARRAY_NEW, OpCode.ARRAY_NEW_DEFAULT, OpCode.ARRAY_NEW_FIXED -> {
                            val arr = subType(ins.operand(0))?.compType()?.arrayType() ?: fail("型 ${ins.operand(0)} が array でない")
                            val elem = arr.fieldType().storageType()
                            when (op) {
                                OpCode.ARRAY_NEW -> {
                                    pop(op, ValType.I32) // 長さ（上）
                                    pop(op, unpacked(elem))
                                }
                                OpCode.ARRAY_NEW_DEFAULT -> {
                                    if (!defaultable(elem)) fail("型 ${ins.operand(0)} の要素が null を持てない参照なので、既定値で作れない")
                                    pop(op, ValType.I32)
                                }
                                else -> {
                                    // 個数は u32。スタックより多ければ、取る前に断る
                                    val n = ins.operand(1)
                                    if (n > stack.size) fail("スタックの値が足りない")
                                    repeat(n.toInt()) { pop(op, unpacked(elem)) }
                                }
                            }
                            ref(ValType.ID.Ref, ins.operand(0).toInt())
                        }
                        // 結果が null を持てるかは、取った値に従う（仕様。null を持てない値からは、null を持てない値になる）
                        OpCode.ANY_CONVERT_EXTERN, OpCode.EXTERN_CONVERT_ANY -> {
                            val toAny = op == OpCode.ANY_CONVERT_EXTERN
                            val actual = pop(op, if (toAny) ValType.ExternRef else ValType.AnyRef)
                            val heap = if (toAny) ValType.TypeIdxCode.ANY else ValType.TypeIdxCode.EXTERN
                            ref(if (actual.isNullable) ValType.ID.RefNull else ValType.ID.Ref, heap.code())
                        }
                        else -> fail("命令 $op は定数式に使えない")
                    }
                    stack += push
                }
                if (stack.size != 1) fail("定数式の値が ${stack.size} 個（1 個でない）")
                val actual = stack[0]
                if (!valTypeMatches(actual, expected, types)) fail("値の型（$actual）が$what（$expected）に合わない")
            } catch (e: ConstExprProblem) {
                return e.message
            }
            return null
        }

        /**
         * [actual] が [want] の部分型か（[constExprProblem] が使う）。Endive 1.2.0 の `ValType.matches` の、null の底の型 `none` の穴（eq・struct・array・i31 の部分型として扱わない）をふさいだもの。
         *
         * Endive の `heapTypeSubtype` は、`none` を any と番号の struct・array の型の部分型としか扱わず、eq・struct・array・i31 の部分型として扱わない
         * （仕様では `none` は any の階層の全部の型の部分型）。そのままでは、`(struct.new $X (ref.null none))`（`$X` のフィールドが eqref など）のような
         * 正しい WASM を断ってしまう（`ref.null none` は Binaryen が any の階層の null に出す標準の形）。`nofunc`・`noextern`・`noexn` は Endive が
         * 正しく扱うので、そのまま任せる。null を持てるかは、`ValType.matches` と同じく、[actual] が null を持てるなら [want] も持てるときだけ通す。
         * Endive が直ったら、`ValType.matches` に戻す（#1013）
         */
        internal fun valTypeMatches(actual: ValType, want: ValType, types: TypeSection): Boolean {
            if (actual.isReference && want.isReference && actual.typeIdx() == ValType.TypeIdxCode.NONE.code()) {
                if (actual.isNullable && !want.isNullable) return false
                val heap = want.typeIdx()
                return when (heap) {
                    ValType.TypeIdxCode.NONE.code(), ValType.TypeIdxCode.ANY.code(), ValType.TypeIdxCode.EQ.code(),
                    ValType.TypeIdxCode.I31.code(), ValType.TypeIdxCode.STRUCT.code(), ValType.TypeIdxCode.ARRAY.code(),
                    -> true
                    else -> heap >= 0 && heap < types.subTypeCount() && types.getSubType(heap).compType().let {
                        it.structType() != null || it.arrayType() != null
                    }
                }
            }
            return ValType.matches(actual, want, types)
        }

        /** 値をスタックから取って、GC のオブジェクト（struct・array）の中に入れる命令（`*.new_default`・`array.new_elem`・`array.new_data` は値を取らない） */
        private fun takesOperandsIntoObject(ins: Instruction): Boolean = when (ins.opcode()) {
            OpCode.STRUCT_NEW, OpCode.ARRAY_NEW, OpCode.ARRAY_NEW_FIXED -> true
            else -> false
        }

        private fun isGcAllocation(ins: Instruction): Boolean = when (ins.opcode()) {
            OpCode.STRUCT_NEW, OpCode.STRUCT_NEW_DEFAULT, OpCode.ARRAY_NEW, OpCode.ARRAY_NEW_DEFAULT, OpCode.ARRAY_NEW_FIXED -> true
            else -> false
        }

        /** SIMD の命令の接頭辞 */
        private const val SIMD_PREFIX: Int = 0xFD

        /** 命令の数が [LARGE_FUNCTION_INSTRUCTIONS] 以上の関数の番号（import を含めた番号）。大きい順 */
        private fun largeFunctions(module: EndiveModule): List<Int> {
            val imported = module.importSection().count(ExternalType.FUNCTION)
            val bodies = module.codeSection().functionBodies()
            return bodies.indices
                .map { it to bodies[it].instructions().size }
                .filter { it.second >= LARGE_FUNCTION_INSTRUCTIONS }
                .sortedByDescending { it.second }
                .map { it.first + imported }
        }
    }
}

/** メモリ・テーブルの上限のせいで作れない（#39・#361）。Endive の中を通す間だけ使い、[WasmLinkException] にして投げ直す。 */
private class MemoryLimitFailure(message: String) : RuntimeException(message, null, false, false)

/**
 * `grow` を [budget] で数える Endive のメモリ（#39）。ほかの操作は [inner] にそのまま渡す。
 *
 * [reserved] は [budget] から確保したページ数。インスタンスを閉じたときに [releaseAll] で返す。
 */
private class BudgetedMemory(
    val inner: EndiveMemory,
    private val budget: WasmMemoryBudget,
    private var reserved: Int,
) : EndiveMemory by inner, ReleasableMemory {
    @Synchronized
    override fun grow(size: Int): Int {
        // 0 は今の大きさを返すだけ。負（符号なしで 2^31 以上）は Endive が -1 を返す
        if (size <= 0 || reserved < 0) return inner.grow(size)
        val current = inner.pages()
        if (current.toLong() + size > inner.maximumPages()) return -1
        if (!budget.tryReserve(size)) return -1
        val previous = try {
            inner.grow(size)
        } catch (t: Throwable) {
            budget.release(size)
            throw t
        }
        if (previous < 0) {
            budget.release(size)
            return -1
        }
        reserved += size
        return previous
    }

    /** 確保した分をすべて返す。以後 `grow` は数えない（閉じたインスタンスのメモリ）。2回呼んでもよい */
    @Synchronized
    override fun releaseAll() {
        if (reserved > 0) budget.release(reserved)
        reserved = -1
    }
}

/** インスタンスを閉じたとき（作るのに失敗したときも）に、数えた分を返すメモリ */
internal interface ReleasableMemory {
    /** 数えた分をすべて返す。2回呼んでもよい */
    fun releaseAll()
}

/** 書き込んだ区画で数えるメモリ（#407）を、閉じたときに返す相手として包む */
private class WrittenRelease(val memory: WrittenPagesMemory) : ReleasableMemory {
    override fun releaseAll() = memory.releaseAll()
}

/**
 * 要素数の上限を縮め、確保を [budget] で数える Endive のテーブル（#361）。
 *
 * Endive のテーブルは `table.grow` のたびに配列を丸ごと確保し直すので、増やす前に上限と [budget] を確かめ、
 * 断られたら Endive に渡さずに -1 を返す（失敗した grow は数えない。#335 と同じ）。
 * [reserved] は [budget] から確保したバイト数。インスタンスを閉じたときに [releaseAll] で返す。
 */
internal class BudgetedTable(
    table: Table,
    initValue: Int,
    private val budget: WasmTableBudget?,
    private val elementBytes: Long,
    private var reserved: Long,
) : TableInstance(table, initValue) {
    override fun grow(size: Int, value: Int, instance: Instance?): Int = counted(size) { super.grow(size, value, instance) }

    override fun growWithRef(size: Int, refValue: Any?, instance: Instance?): Int = counted(size) { super.growWithRef(size, refValue, instance) }

    @Synchronized
    private fun counted(size: Int, grow: () -> Int): Int {
        // 0 は今の大きさを返すだけ。負（符号なしで 2^31 以上）は Endive が -1 を返す
        if (size <= 0) return grow()
        if (size().toLong() + size > limits().max()) return -1
        val bytes = size * elementBytes
        val counting = budget != null && reserved >= 0
        if (counting && !budget!!.tryReserveBytes(bytes)) return -1
        val previous = try {
            grow()
        } catch (t: Throwable) {
            if (counting) budget!!.releaseBytes(bytes)
            throw t
        }
        if (!counting) return previous
        if (previous < 0) {
            budget!!.releaseBytes(bytes)
            return -1
        }
        reserved += bytes
        return previous
    }

    /** 確保した分をすべて返す。以後 `grow` は数えない（閉じたインスタンスのテーブル）。2回呼んでもよい */
    @Synchronized
    fun releaseAll() {
        if (reserved > 0) budget?.releaseBytes(reserved)
        reserved = -1
    }
}

/**
 * インスタンスが作った GC のオブジェクト（struct・array）の RAM の勘定（#939。ADR 0048 の「GC のオブジェクト」）。
 *
 * 計測コードが、オブジェクトを作る命令の前に `kibi.gc_alloc` でバイト数（[GcCosts] の見積もり）を渡し、ここで [budget] に数える。
 * 断られたら、Endive に作らせずにトラップで止める（失敗した確保は数えない。#335 と同じ）。インスタンスを作るときの定数式の分も、作る前にここで数える。
 *
 * 数えた分は、オブジェクトが要らなくなっても返さず、インスタンスを閉じたとき（作るのに失敗したときも）に [releaseAll] でまとめて返す
 * （ADR 0048 の「解放しても減らさない」と同じ。GC のオブジェクトは状態の保存をまたがないので、作り直したインスタンスは 0 から数え直す）。
 *
 * 確保のたびにプロセス → マシン → サーバー全体の 3 段の CAS を回さないよう、足りなくなったら [PREFETCH_BYTES] ずつ前借りし（断られても
 * 断った記録を残さない [WasmTableBudget.tryPrefetchBytes]）、前借りできなければ要る分だけを数える。前借りの残りはホストの側に持ち、
 * WASM のグローバルには置かない（状態の保存が可変のグローバルを写すため）。
 *
 * [budget] が `null`（テスト）なら数えないが、[UNBUDGETED_MAX_BYTES] 以上の 1 回の確保は断る（Endive に負の長さを渡さない）。
 * `close()` と確保は別のスレッドから重なりうる（kill など）ので、どちらも同期し、閉じた後の確保は断る（数えると二度と返らない）
 */
private class GcAllocations(private val budget: WasmTableBudget?) {
    /** [budget] から数えたバイト数（前借りの残りを含む）。閉じたら -1 */
    private var reserved = 0L

    /** 前借りの残り */
    private var credit = 0L

    /** [bytes] バイトを数えられれば `true`。断られたら、閉じた後なら、または負なら何も数えずに `false` */
    @Synchronized
    fun allocate(bytes: Long): Boolean {
        if (bytes < 0 || reserved < 0) return false
        if (budget == null) return bytes < UNBUDGETED_MAX_BYTES
        if (bytes <= credit) {
            credit -= bytes
            return true
        }
        val need = bytes - credit
        if (need < PREFETCH_BYTES && budget.tryPrefetchBytes(PREFETCH_BYTES)) {
            reserved += PREFETCH_BYTES
            credit = PREFETCH_BYTES - need
            return true
        }
        if (!budget.tryReserveBytes(need)) return false
        reserved += need
        credit = 0
        return true
    }

    /** 数えた分をすべて返す。以後の確保は断る。2回呼んでもよい */
    @Synchronized
    fun releaseAll() {
        if (reserved > 0) budget?.releaseBytes(reserved)
        reserved = -1
        credit = 0
    }

    companion object {
        /** 前借りの単位（64 KiB） */
        const val PREFETCH_BYTES: Long = 64L * 1024

        /** 数えないとき（テスト）に断る 1 回の確保の大きさ（16 GiB。長さ 2^31 以上の配列は必ずこれを超える） */
        const val UNBUDGETED_MAX_BYTES: Long = 1L shl 34
    }
}

/**
 * テーブルの要素1つが使う JVM のメモリ（バイト。#361）の見積もり。Endive は要素ごとに `int`（4）と
 * インスタンスへの参照（圧縮しない参照で 8）を持ち、GC の参照のテーブルはさらに値の参照（8）を持つ。
 * i31ref を入れられるテーブル（anyref・eqref・i31ref・externref）は、要素ごとに `WasmI31Ref` 1 つ分（[GcCosts.I31_BYTES]。#939）を足す。
 */
private fun tableElementBytes(table: Table): Long =
    (if (table.elementType().isObjectRef()) 20L else 12L) + if (GcCosts.mayHoldI31(table.elementType())) GcCosts.I31_BYTES else 0L

/**
 * [config] の上限で Endive のテーブルを作る（#361）。数えるテーブルは [created] に足す。
 *
 * Endive の [TableInstance] は、`grow` のたびにモジュールの [Table] の要素数を書き換える。同じモジュールから作る
 * ほかのインスタンスに響かないように、インスタンスごとに [Table] を作り直し、そのときに上限を縮める。
 * 最初の要素数が上限を超える、または確保を断られたら [MemoryLimitFailure] を投げる。
 */
internal fun createTable(table: Table, initValue: Int, config: WasmInstanceConfig, created: MutableList<BudgetedTable>): TableInstance {
    val limits = table.limits()
    val initial = limits.min()
    val max = minOf(limits.max(), config.maxTableElements.toLong())
    if (initial > max) {
        throw MemoryLimitFailure("テーブルの最初の要素数 $initial が上限 $max を超える")
    }
    val own = Table(table.elementType(), TableLimits(initial, max, limits.shared()), table.initialize())
    val budget = config.tableBudget
    val elementBytes = tableElementBytes(table)
    val initialBytes = initial * elementBytes
    if (budget != null && initialBytes > 0 && !budget.tryReserveBytes(initialBytes)) {
        throw MemoryLimitFailure("テーブルの最初の $initial 要素を確保できない（RAM が足りない）")
    }
    val reserved = if (budget != null) initialBytes else 0L
    val made = try {
        BudgetedTable(own, initValue, budget, elementBytes, reserved)
    } catch (t: Throwable) {
        if (budget != null && initialBytes > 0) budget.releaseBytes(initialBytes)
        throw t
    }
    // 登録で落ちても、数えた分を返す（#946）
    try {
        created += made
    } catch (t: Throwable) {
        made.releaseAll()
        throw t
    }
    return made
}

/**
 * [config] の上限で Endive のメモリを作る（#39）。数えるメモリは [created] に足す。
 *
 * 最初のページ数が上限を超える、または確保を断られたら [MemoryLimitFailure] を投げる。
 */
internal fun createMemory(limits: MemoryLimits, config: WasmInstanceConfig, created: MutableList<ReleasableMemory>): EndiveMemory {
    val initial = limits.initialPages()
    val max = minOf(limits.maximumPages(), config.maxMemoryPages)
    if (initial > max) {
        throw MemoryLimitFailure("メモリの最初のページ数 $initial が上限 $max を超える")
    }
    if (config.writtenMemory) {
        // #407: ページ数は数えず、初めて書き込んだ 4 KiB の区画を数える。共有メモリは書き込みを数えられないので作らない
        if (limits.shared()) throw MemoryLimitFailure("共有メモリは使えない")
        // 区画の表の最初の分は作るときに数える（#753）。断られたら作らない（何も数えていない）
        val memory = try {
            WrittenPagesMemory(MemoryLimits(initial, max, false), config.writtenMemoryBudget, onGrowRefused = config.onMemoryGrowRefused)
        } catch (e: PageTableRefused) {
            throw MemoryLimitFailure(e.message!!)
        }
        // 作った後の登録（包みの確保・リストの伸び）で落ちても、数えた分を返す（#946。登録できないと instantiate の catch が返せない）
        try {
            created += WrittenRelease(memory)
        } catch (t: Throwable) {
            memory.releaseAll()
            throw t
        }
        return memory
    }
    val budget = config.memoryBudget
    if (budget == null) return newMemory(MemoryLimits(initial, max, limits.shared()))
    if (initial > 0 && !budget.tryReserve(initial)) {
        throw MemoryLimitFailure("メモリの最初の $initial ページを確保できない（RAM が足りない）")
    }
    val inner = try {
        newMemory(MemoryLimits(initial, max, limits.shared()))
    } catch (t: Throwable) {
        if (initial > 0) budget.release(initial)
        throw t
    }
    val memory = try {
        BudgetedMemory(inner, budget, initial)
    } catch (t: Throwable) {
        if (initial > 0) budget.release(initial)
        throw t
    }
    try {
        created += memory
    } catch (t: Throwable) {
        memory.releaseAll()
        throw t
    }
    return memory
}

/**
 * Endive のメモリを作る。共有しないメモリは、書き込んだページだけ確保する [LazyMemory]（#334）。
 * 共有メモリは Endive の [ByteBufferMemory]（最初のページをすべて確保する）。
 */
private fun newMemory(limits: MemoryLimits): EndiveMemory = if (limits.shared()) ByteBufferMemory(limits) else LazyMemory(limits)

/**
 * [memory] のうち、JVM のメモリを確保したページ数（#334。テストと計測用）。
 * 書き込んだページだけ確保するメモリでなければ、宣言したページ数を返す。
 */
internal fun committedPages(memory: WasmMemory): Int {
    val inner = (memory as? EndiveWasmMemory)?.raw ?: return memory.pages
    val lazy = (inner as? BudgetedMemory)?.inner ?: inner
    return (lazy as? LazyMemory)?.committedPages ?: memory.pages
}

/** [memory] のうち、書き換えた（RAM として数えた）4 KiB の区画の数（#407。テスト用）。区画で数えるメモリでなければ `-1` */
internal fun writtenChunks(memory: WasmMemory): Int =
    ((memory as? EndiveWasmMemory)?.raw as? WrittenPagesMemory)?.writtenChunks ?: -1

/** [memory] のうち、データセグメントで書いた（数えない）ROM の区画の数（#407。テスト用）。区画で数えるメモリでなければ `-1` */
internal fun romChunks(memory: WasmMemory): Int =
    ((memory as? EndiveWasmMemory)?.raw as? WrittenPagesMemory)?.romChunks ?: -1

/** [module] をコンパイルしたコードで動かすなら `true`（#37。テスト用） */
internal fun isCompiled(module: WasmModule): Boolean = (module as? EndiveWasmModule)?.isCompiled ?: false

/** コンパイルした [module] のうち、インタプリタで動かす関数の番号（import を含めた番号。#424。テスト用） */
internal fun interpretedFunctions(module: WasmModule): Set<Int> = (module as? EndiveWasmModule)?.interpretedFunctions ?: emptySet()

/** テーブルの空の要素（Endive の `Value.REF_NULL_VALUE`） */
private const val REF_NULL: Int = -1

/** 戻り値のないホストの関数の戻り値 */
private val NO_VALUES = LongArray(0)

/** 状態のグローバルの値 → 状態 */
private val ASYNC_STATES: Array<WasmAsyncState> = WasmAsyncState.values()

/** ホストの関数が投げた例外を、Endive の中を通す間だけ包む。外に出るときに外す。 */
private class HostThrowable(val original: Throwable) : RuntimeException(null, original, false, false)

private fun valueType(type: ValType): WasmValueType = when (type) {
    ValType.I32 -> WasmValueType.I32
    ValType.I64 -> WasmValueType.I64
    ValType.F32 -> WasmValueType.F32
    ValType.F64 -> WasmValueType.F64
    else -> WasmValueType.UNSUPPORTED
}

private fun valType(type: WasmValueType): ValType = when (type) {
    WasmValueType.I32 -> ValType.I32
    WasmValueType.I64 -> ValType.I64
    WasmValueType.F32 -> ValType.F32
    WasmValueType.F64 -> ValType.F64
    WasmValueType.UNSUPPORTED -> throw IllegalArgumentException("ホストの関数に使えない型")
}

private fun functionType(type: FunctionType): WasmFunctionType = WasmFunctionType(type.params().map(::valueType), type.returns().map(::valueType))

private fun endiveType(type: WasmFunctionType): FunctionType = FunctionType.of(type.params.map(::valType), type.results.map(::valType))

private fun externKind(type: ExternalType): WasmExternKind = when (type) {
    ExternalType.FUNCTION -> WasmExternKind.FUNCTION
    ExternalType.TABLE -> WasmExternKind.TABLE
    ExternalType.MEMORY -> WasmExternKind.MEMORY
    ExternalType.GLOBAL -> WasmExternKind.GLOBAL
    ExternalType.TAG -> WasmExternKind.TAG
}

/** 包んだホストの例外があれば、それを返す。 */
private fun hostThrowable(e: Throwable): Throwable? {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth++ < 32) {
        if (cur is HostThrowable) return cur.original
        cur = cur.cause
    }
    return null
}

/** メモリの上限のせいで作れなかったなら、その例外を返す。 */
private fun memoryLimitFailure(e: Throwable): MemoryLimitFailure? {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth++ < 32) {
        if (cur is MemoryLimitFailure) return cur
        cur = cur.cause
    }
    return null
}

/** Endive の例外を、この抽象の例外に変えて投げる。ホストの例外はそのまま投げる。 */
private fun rethrow(e: Throwable): Nothing {
    hostThrowable(e)?.let { throw it }
    throw when (e) {
        is WasmException -> e
        // 書き込んだ区画を RAM に数えられなかった（#407）
        is RamExhausted -> outOfMemory(e)
        is StackOverflowError -> WasmTrapException(WasmTrapKind.STACK_OVERFLOW, "呼び出しが深すぎる", e)
        // Thread.interrupt で止められた（#43。カーネルの stop が使う）
        is WasmInterruptedException -> WasmTrapException(WasmTrapKind.INTERRUPTED, "実行しているスレッドが割り込まれた", e)
        is EndiveWasmException -> WasmTrapException(WasmTrapKind.UNCAUGHT_EXCEPTION, "捕まえられていない WASM の例外", e)
        is WasmEngineException -> WasmTrapException(trapKind(e.message.orEmpty()), e.message ?: e.toString(), e)
        else -> e
    }
}

/** RAM の上限を超えた書き込み（[RamExhausted]）のトラップ（#407） */
private fun outOfMemory(e: RamExhausted): WasmTrapException = WasmTrapException(WasmTrapKind.OUT_OF_MEMORY, e.message ?: "out of memory", e)

/** GC のオブジェクト（[bytes] バイト）を RAM に数えられなかったトラップ（#939） */
private fun gcOutOfMemory(bytes: Long): WasmTrapException =
    WasmTrapException(WasmTrapKind.OUT_OF_MEMORY, "out of memory: RAM の上限を超えて GC のオブジェクト（${bytes.toULong()} バイト）を作ろうとした")

/** `kibi.gc_alloc` の戻り値（使わない。#939） */
private val GC_ALLOC_RESULT = longArrayOf(0)

/** Endive のトラップのメッセージから種類を決める。詳しい扱いは #41。 */
internal fun trapKind(message: String): WasmTrapKind {
    val m = message.lowercase()
    return when {
        "unreachable" in m -> WasmTrapKind.UNREACHABLE
        "out of bounds memory" in m -> WasmTrapKind.MEMORY_OUT_OF_BOUNDS
        "out of bounds table" in m || "undefined element" in m -> WasmTrapKind.TABLE_OUT_OF_BOUNDS
        "divide by zero" in m -> WasmTrapKind.INTEGER_DIVIDE_BY_ZERO
        "integer overflow" in m -> WasmTrapKind.INTEGER_OVERFLOW
        "invalid conversion" in m -> WasmTrapKind.INVALID_CONVERSION
        "call stack exhausted" in m -> WasmTrapKind.STACK_OVERFLOW
        "indirect call" in m || "uninitialized element" in m -> WasmTrapKind.INDIRECT_CALL
        "null" in m -> WasmTrapKind.NULL_REFERENCE
        else -> WasmTrapKind.OTHER
    }
}

/**
 * Endive のモジュール。[compiled] があればコンパイルしたコードで、なければインタプリタで動かす。
 * [module] は、コンパイルしたなら関数の本体を除いたもの（[CompiledModule.wasmModule]）。
 */
private class EndiveWasmModule(
    val module: EndiveModule,
    private val compiled: CompiledModule?,
    /**
     * コンパイルしたとき、インタプリタで動かす関数の番号（import を含めた番号。テスト用）。Endive が自動で回した分（#680）は、
     * 本体が残った関数から読むので、そこから末尾呼び出しで届く関数も含みうる（[EndiveRuntime.retainedFunctions]）
     */
    val interpretedFunctions: Set<Int> = emptySet(),
) : WasmModule {
    /** コンパイルしたコードで動かすなら `true`（テスト用） */
    val isCompiled: Boolean get() = compiled != null

    /**
     * インタプリタで動くコードを持つこと（#931）。[EndiveRuntime.load] が返す前に1回だけ書く（その後は変わらない。モジュールを渡す側の
     * `ProgramCache` の完了が、ほかのスレッドへの公開になる）。コンパイルしない設定のランタイムでは `null`
     */
    override var interpreterUse: InterpreterUse? = null

    /** import の関数の型（関数の番号の順） */
    private val importFunctionTypes: List<FunctionType>

    private val rawImports: List<WasmImportDescriptor>
    private val rawExports: List<WasmExportDescriptor>

    override val isFuelMetered: Boolean

    override val sharesFuel: Boolean

    /** 燃料の残りを入れる、定義したグローバルの番号（import を含めた番号）。計測しない・共有する（import する）なら -1 */
    private val fuelGlobalIndex: Int

    override val isAsyncified: Boolean

    /** GC のオブジェクトを作る前に RAM に数える import（[WasmFuel.GC_ALLOC_NAME]。#939）を持つか */
    private val countsGcAllocations: Boolean

    /**
     * インスタンスを作るときに定数式（グローバルの初期値・アクティブの要素の式）が作る GC のオブジェクトのバイト数（#939。[GcCosts.estimate]）。
     * 作る前に数える。燃料を計測しないモジュール（テスト）は数えない（0）
     */
    private val instantiateGcBytes: Long

    override val imports: List<WasmImportDescriptor>
    override val exports: List<WasmExportDescriptor>

    init {
        val types = module.typeSection()
        val section = module.importSection()
        val funcTypes = mutableListOf<FunctionType>()
        val imps = mutableListOf<WasmImportDescriptor>()
        for (i in 0 until section.importCount()) {
            val imp = section.getImport(i)
            val type = (imp as? FunctionImport)?.let { types.getType(it.typeIndex()) }
            if (type != null) funcTypes += type
            imps += WasmImportDescriptor(imp.module(), imp.name(), externKind(imp.importType()), type?.let(::functionType))
        }
        importFunctionTypes = funcTypes
        rawImports = imps

        val exportSection = module.exportSection()
        rawExports = (0 until exportSection.exportCount()).map { i ->
            val exp = exportSection.getExport(i)
            val kind = externKind(exp.exportType())
            WasmExportDescriptor(exp.name(), kind, if (kind == WasmExternKind.FUNCTION) functionType(functionTypeOf(exp.index())) else null)
        }

        val fuelImport = rawImports.firstOrNull { it.module == WasmFuel.IMPORT_MODULE && it.name == WasmFuel.IMPORT_NAME }
        val fuelExport = rawExports.firstOrNull { it.name == WasmFuel.EXPORT_GLOBAL }
        if ((fuelImport == null) != (fuelExport == null)) {
            throw WasmLoadException("燃料の計測の import と export の片方しかない")
        }
        if (fuelImport != null && (fuelImport.functionType != WasmFuel.IMPORT_TYPE || fuelExport!!.kind != WasmExternKind.GLOBAL)) {
            throw WasmLoadException("燃料の計測の import・export の型が違う")
        }
        isFuelMetered = fuelImport != null
        // 共有の燃料（#263）: 残りのグローバルを import する。燃料を計測するモジュールだけが持てる
        val fuelGlobalImport = rawImports.firstOrNull { it.module == WasmFuel.IMPORT_MODULE && it.name == WasmFuel.IMPORT_GLOBAL }
        if (fuelGlobalImport != null) {
            if (!isFuelMetered) throw WasmLoadException("${WasmFuel.IMPORT_MODULE}.${WasmFuel.IMPORT_GLOBAL} は燃料を計測するモジュールだけが持てる")
            val global = section.getImport(rawImports.indexOf(fuelGlobalImport)) as? GlobalImport
            if (global == null || global.type() != ValType.I64 || global.mutabilityType() != MutabilityType.Var) {
                throw WasmLoadException("${WasmFuel.IMPORT_MODULE}.${WasmFuel.IMPORT_GLOBAL} の型が違う")
            }
        }
        sharesFuel = fuelGlobalImport != null
        // 定義した燃料のグローバルは、作るときに番号で見分けて最初の燃料を入れる（GlobalFactory は定義したものだけを数える）ので、
        // ほかのグローバルの import とは一緒にできない。共有するモジュールは燃料のグローバルを import するので、ほかの import もよい
        if (fuelExport != null && !sharesFuel && module.importSection().count(ExternalType.GLOBAL) > 0) {
            throw WasmLoadException("グローバルの import には対応していない")
        }
        fuelGlobalIndex = if (fuelExport != null && !sharesFuel) exportSection.let { es -> (0 until es.exportCount()).map(es::getExport).first { it.name() == WasmFuel.EXPORT_GLOBAL }.index() } else -1
        // 待機点への変換（#329）: import 2つと状態のグローバルの export は、そろっていて型が合うときだけ
        val pushImport = rawImports.firstOrNull { it.module == WasmAsync.IMPORT_MODULE && it.name == WasmAsync.PUSH }
        val popImport = rawImports.firstOrNull { it.module == WasmAsync.IMPORT_MODULE && it.name == WasmAsync.POP }
        val stateExport = rawExports.firstOrNull { it.name == WasmAsync.STATE_EXPORT }
        val asyncParts = listOfNotNull(pushImport, popImport, stateExport).size
        if (asyncParts != 0 && asyncParts != 3) {
            throw WasmLoadException("待機点への変換の import・export の一部しかない")
        }
        if (asyncParts == 3) {
            if (pushImport!!.functionType != WasmAsync.PUSH_TYPE || popImport!!.functionType != WasmAsync.POP_TYPE || stateExport!!.kind != WasmExternKind.GLOBAL) {
                throw WasmLoadException("待機点への変換の import・export の型が違う")
            }
        }
        isAsyncified = asyncParts == 3
        // 止まりうる関数の中の燃料切れ（#339）: 燃料を計測し、変換もしたモジュールだけが持てる
        val asyncFuelImport = rawImports.firstOrNull { it.module == WasmAsync.IMPORT_MODULE && it.name == WasmAsync.FUEL }
        if (asyncFuelImport != null) {
            if (!isAsyncified || !isFuelMetered) throw WasmLoadException("${WasmAsync.IMPORT_MODULE}.${WasmAsync.FUEL} は燃料を計測し、待機点に変換したモジュールだけが持てる")
            if (asyncFuelImport.functionType != WasmAsync.FUEL_TYPE) throw WasmLoadException("${WasmAsync.IMPORT_MODULE}.${WasmAsync.FUEL} の型が違う")
        }
        // GC のオブジェクトの確保（#939）: 燃料を計測するモジュールだけが持てる（計測コードの挿入が足す）
        val gcImport = rawImports.firstOrNull { it.module == WasmFuel.IMPORT_MODULE && it.name == WasmFuel.GC_ALLOC_NAME }
        if (gcImport != null) {
            if (!isFuelMetered) throw WasmLoadException("${WasmFuel.IMPORT_MODULE}.${WasmFuel.GC_ALLOC_NAME} は燃料を計測するモジュールだけが持てる")
            if (gcImport.functionType != WasmFuel.IMPORT_TYPE) throw WasmLoadException("${WasmFuel.IMPORT_MODULE}.${WasmFuel.GC_ALLOC_NAME} の型が違う")
        }
        countsGcAllocations = gcImport != null
        // 大きさを決められない定数式は、計測コードの挿入がもう断っている（ここでも同じ理由で断る）
        instantiateGcBytes = if (isFuelMetered) GcCosts.estimate(module).instantiateBytes else 0L
        val hidden = listOfNotNull(fuelImport, fuelExport, fuelGlobalImport, pushImport, popImport, stateExport, asyncFuelImport, gcImport)
        imports = rawImports.filter { imp -> hidden.none { it === imp } }
        exports = rawExports.filter { exp -> hidden.none { it === exp } }
    }

    private fun functionTypeOf(index: Int): FunctionType = if (index < importFunctionTypes.size) {
        importFunctionTypes[index]
    } else {
        module.functionSection().getFunctionType(index - importFunctionTypes.size, module.typeSection())
    }

    fun exportedFunctionType(name: String): WasmFunctionType? = exports.firstOrNull { it.name == name && it.kind == WasmExternKind.FUNCTION }?.functionType

    /** export [name] の種類（燃料などランタイムがつなぐものは除く）。なければ `null` */
    fun exportKind(name: String): WasmExternKind? = exports.firstOrNull { it.name == name }?.kind

    @Throws(WasmLinkException::class, WasmLoadException::class, WasmTrapException::class)
    override fun instantiate(config: WasmInstanceConfig): WasmInstance {
        val wrapper = EndiveWasmInstance(this, config)
        val values = ImportValues.builder()
        for (imp in rawImports) {
            if (sharesFuel && imp.module == WasmFuel.IMPORT_MODULE && imp.name == WasmFuel.IMPORT_GLOBAL) {
                val shared = config.sharedFuel ?: throw WasmLinkException("燃料を共有するモジュールに sharedFuel がない")
                values.addGlobal(ImportGlobal(imp.module, imp.name, sharedFuelGlobal(shared)))
                continue
            }
            val extern = config.externImport(imp.module, imp.name)
            if (extern != null) {
                addExternImport(values, imp, extern)
                continue
            }
            if (imp.kind != WasmExternKind.FUNCTION) {
                throw WasmLinkException("import がない: $imp")
            }
            val type = imp.functionType!!
            if (isFuelMetered && imp.module == WasmFuel.IMPORT_MODULE && imp.name == WasmFuel.IMPORT_NAME) {
                values.addFunction(hostFunction(imp.module, imp.name, type, wrapper) { args -> longArrayOf(wrapper.fuelExhausted(args[0])) })
                continue
            }
            if (countsGcAllocations && imp.module == WasmFuel.IMPORT_MODULE && imp.name == WasmFuel.GC_ALLOC_NAME) {
                values.addFunction(hostFunction(imp.module, imp.name, type, wrapper) { args -> wrapper.gcAllocate(args[0]); GC_ALLOC_RESULT })
                continue
            }
            if (isAsyncified && imp.module == WasmAsync.IMPORT_MODULE && imp.name == WasmAsync.FUEL) {
                values.addFunction(hostFunction(imp.module, imp.name, type, wrapper) { args -> longArrayOf(wrapper.fuelExhaustedUnwindable(args[0])) })
                continue
            }
            if (isAsyncified && imp.module == WasmAsync.IMPORT_MODULE && (imp.name == WasmAsync.PUSH || imp.name == WasmAsync.POP)) {
                val stack = config.asyncStack ?: throw WasmLinkException("待機点への変換をかけたモジュールに asyncStack がない")
                values.addFunction(
                    if (imp.name == WasmAsync.PUSH) {
                        hostFunction(imp.module, imp.name, type, wrapper) { args -> stack.push(args[0]); NO_VALUES }
                    } else {
                        hostFunction(imp.module, imp.name, type, wrapper) { longArrayOf(stack.pop()) }
                    },
                )
                continue
            }
            val host = config.hostImport(imp.module, imp.name) ?: throw WasmLinkException("import がない: $imp")
            if (host.type != type) {
                throw WasmLinkException("import の型が違う: $imp に $host")
            }
            values.addFunction(hostFunction(imp.module, imp.name, type, wrapper) { args -> host.function.call(wrapper, args) })
        }
        // 燃料のグローバルは、作るときに最初の燃料を入れる（start 関数は build の中で実行されるので、その前に要る）
        val fuelIndex = fuelGlobalIndex
        var globalIndex = 0
        val globals = GlobalFactory { low, high, type, mutability ->
            val value = if (globalIndex++ == fuelIndex) config.initialFuel else low
            GlobalInstance(value, high, type, mutability)
        }
        // GC のオブジェクト（#939）: 定数式が作る分を、Endive に作らせる前に数える（作るのに失敗したら、数えた分を返す）
        val gc = GcAllocations(config.tableBudget)
        wrapper.gcAllocations = gc
        if (instantiateGcBytes > 0 && !gc.allocate(instantiateGcBytes)) {
            gc.releaseAll()
            throw WasmLinkException("インスタンスを作れない: 初期値の GC のオブジェクトの ${instantiateGcBytes.toULong()} バイトを確保できない（RAM が足りない）")
        }
        // 数えるメモリ（作るのに失敗したら、確保した分を返す）
        val memories = ArrayList<ReleasableMemory>(1)
        wrapper.budgetedMemories = memories
        // 数えるテーブル（#361。同じく、失敗したら返す）
        val tables = ArrayList<BudgetedTable>(1)
        wrapper.budgetedTables = tables
        val instance = try {
            // withStart(false): export の `_start` は呼ばない（start セクションの関数は build の中で実行される）
            (if (compiled != null) Instance.builder(compiled) else Instance.builder(module))
                .withImportValues(values.build())
                .withGlobalFactory(globals)
                .withMemoryFactory { limits -> createMemory(limits, config, memories) }
                .withTableFactory { table, initValue -> createTable(table, initValue, config, tables) }
                .withStart(false)
                .build()
        } catch (e: Throwable) {
            memories.forEach { it.releaseAll() }
            tables.forEach { it.releaseAll() }
            gc.releaseAll()
            translateInstantiateFailure(e)
        }
        wrapper.attach(instance)
        return wrapper
    }

    /**
     * ほかのインスタンスの export [extern] を、import [imp] として [values] に足す（#263）。種類・関数の型が違えば [WasmLinkException]。
     * テーブル・メモリ・グローバル・タグの型は、Endive がインスタンスを作るときに確かめる（合わなければ [WasmLinkException]）
     */
    private fun addExternImport(values: ImportValues.Builder, imp: WasmImportDescriptor, extern: WasmExtern) {
        if (extern.kind != imp.kind) throw WasmLinkException("import の種類が違う: $imp に $extern")
        val owner = extern.instance as? EndiveWasmInstance ?: throw WasmLinkException("ほかのランタイムのインスタンスの export は渡せない: $extern")
        val raw = owner.rawInstance()
        val name = extern.exportName
        when (imp.kind) {
            WasmExternKind.FUNCTION -> {
                val actual = owner.module.exportedFunctionType(name) ?: throw WasmLinkException("export がない: $extern")
                if (actual != imp.functionType) throw WasmLinkException("import の型が違う: $imp に $actual")
                values.addFunction(ImportFunction.exportAsImport(imp.module, imp.name, raw, name))
            }
            WasmExternKind.MEMORY -> values.addMemory(ImportMemory(imp.module, imp.name, raw.exports().memory(name)))
            WasmExternKind.TABLE -> values.addTable(ImportTable(imp.module, imp.name, raw.exports().table(name)))
            WasmExternKind.GLOBAL -> values.addGlobal(ImportGlobal(imp.module, imp.name, raw.exports().global(name)))
            WasmExternKind.TAG -> values.addTag(ImportTag(imp.module, imp.name, raw.exports().tag(name)))
        }
    }

    /** [shared] に結び付けた Endive のグローバル（なければ作る） */
    private fun sharedFuelGlobal(shared: WasmSharedFuel): GlobalInstance = synchronized(shared) {
        (shared.binding as? GlobalInstance) ?: GlobalInstance(shared.initial, 0L, ValType.I64, MutabilityType.Var).also { shared.binding = it }
    }

    /**
     * 保存した状態からインスタンスを作り直す（#82、ADR 0082）。start セクションを持つモジュールは、start 関数を飛ばして作れない
     * （Endive は初期化の中で必ず動かす）ので [WasmLinkException]（取り出す側の [EndiveWasmInstance.captureState] も断る）。
     * ほかは普通に作り（能動データセグメント・要素セグメントの初期化）、その後で状態を上書きする
     */
    @Throws(WasmLinkException::class, WasmLoadException::class)
    override fun instantiateForRestore(config: WasmInstanceConfig, state: WasmInstanceState): WasmInstance {
        if (module.startSection().isPresent) throw WasmLinkException("start 関数を持つモジュールは、状態から作り直せない")
        val instance = try {
            instantiate(config) as EndiveWasmInstance
        } catch (e: WasmTrapException) {
            throw WasmLinkException("インスタンスを作れない: ${e.message}", e)
        }
        try {
            instance.restoreState(state)
        } catch (t: Throwable) {
            instance.close()
            if (t is WasmLinkException) throw t
            throw WasmLinkException("保存した状態を戻せない: ${t.message ?: t}", t)
        }
        return instance
    }

    /** インスタンスを作るときの例外を、この抽象の例外にして投げる。 */
    private fun translateInstantiateFailure(e: Throwable): Nothing {
        hostThrowable(e)?.let { throw it }
        memoryLimitFailure(e)?.let { throw WasmLinkException("インスタンスを作れない: ${it.message}", it) }
        when (e) {
            is UnlinkableException -> throw WasmLinkException("インスタンスを作れない: ${e.message}", e)
            is UninstantiableException -> {
                // start 関数のトラップは Endive が UninstantiableException に包むので、トラップとして投げ直す
                val cause = e.cause
                if (cause != null && (cause is TrapException || cause is WasmInterruptedException || cause is EndiveWasmException || cause is StackOverflowError)) rethrow(cause)
                throw WasmLinkException("インスタンスを作れない: ${e.message}", e)
            }
            is InvalidException -> throw WasmLoadException("WASM の検証に通らない: ${e.message}", e)
            else -> rethrow(e)
        }
    }

    private fun hostFunction(moduleName: String, name: String, type: WasmFunctionType, wrapper: EndiveWasmInstance, body: (LongArray) -> LongArray): HostFunction =
        HostFunction(moduleName, name, endiveType(type)) { raw, args ->
            // start 関数は build の中で実行されるので、そこから呼ばれたときに Endive のインスタンスを結び付ける
            wrapper.attachIfAbsent(raw)
            try {
                body(args)
            } catch (t: Throwable) {
                throw HostThrowable(t)
            }
        }
}

private class EndiveWasmInstance(override val module: EndiveWasmModule, private val config: WasmInstanceConfig) : WasmInstance {
    private var raw: Instance? = null
    private var memoryWrapper: WasmMemory? = null
    private var closed = false

    /** 数えるメモリ（閉じたときに確保した分を返す） */
    var budgetedMemories: List<ReleasableMemory> = emptyList()

    /** 数えるテーブル（#361。閉じたときに確保した分を返す） */
    var budgetedTables: List<BudgetedTable> = emptyList()

    /** GC のオブジェクトの勘定（#939。閉じたときに数えた分を返す） */
    var gcAllocations: GcAllocations? = null

    /** GC のオブジェクトを作る前に、[bytes] バイトを RAM に数える（`kibi.gc_alloc`。#939）。数えられなければ [WasmTrapKind.OUT_OF_MEMORY] のトラップ */
    fun gcAllocate(bytes: Long) {
        val gc = checkNotNull(gcAllocations) { "GC のオブジェクトの勘定がない" }
        if (!gc.allocate(bytes)) throw gcOutOfMemory(bytes)
    }

    /** export の関数（名前ごとに1回だけ引く） */
    private val exportFunctions = HashMap<String, ExportFunction>()

    /** export の線形メモリ（名前ごとに1回だけ包む。#263） */
    private val exportMemories = HashMap<String, WasmMemory>()

    /** Endive のインスタンス（#263。ほかのインスタンスの import に export を渡すため） */
    fun rawInstance(): Instance = instance()

    override fun extern(name: String): WasmExtern? = module.exportKind(name)?.let { WasmExtern(it, this, name) }

    override fun exportedMemory(name: String): WasmMemory? {
        if (module.exportKind(name) != WasmExternKind.MEMORY) return null
        return exportMemories.getOrPut(name) { EndiveWasmMemory(instance().exports().memory(name)) }
    }

    fun attach(instance: Instance) {
        raw = instance
        memoryWrapper = if (module.module.memorySection().map { it.memoryCount() > 0 }.orElse(false)) EndiveWasmMemory(instance.memory()) else null
    }

    fun attachIfAbsent(instance: Instance?) {
        if (raw == null && instance != null) attach(instance)
    }

    private fun instance(): Instance {
        check(!closed) { "閉じたインスタンス" }
        return checkNotNull(raw) { "インスタンスがまだできていない" }
    }

    override val memory: WasmMemory? get() = memoryWrapper

    override var fuel: Long
        get() {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            return instance().exports().global(WasmFuel.EXPORT_GLOBAL).getValue()
        }
        set(value) {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            instance().exports().global(WasmFuel.EXPORT_GLOBAL).setValue(value)
        }

    override var asyncState: WasmAsyncState
        get() {
            check(module.isAsyncified) { "待機点への変換をかけていないモジュール" }
            val v = instance().exports().global(WasmAsync.STATE_EXPORT).getValue().toInt()
            return ASYNC_STATES.getOrNull(v) ?: throw IllegalStateException("待機点への変換の状態が壊れている: $v")
        }
        set(value) {
            check(module.isAsyncified) { "待機点への変換をかけていないモジュール" }
            instance().exports().global(WasmAsync.STATE_EXPORT).setValue(value.ordinal.toLong())
        }

    fun fuelExhausted(remaining: Long): Long {
        val handler = config.fuelHandler ?: throw WasmTrapException(WasmTrapKind.FUEL_EXHAUSTED, "燃料が切れた")
        return handler.onFuelExhausted(this, remaining)
    }

    fun fuelExhaustedUnwindable(remaining: Long): Long {
        val handler = config.fuelHandler ?: throw WasmTrapException(WasmTrapKind.FUEL_EXHAUSTED, "燃料が切れた")
        return handler.onFuelExhaustedUnwindable(this, remaining)
    }

    @Throws(WasmTrapException::class)
    override fun call(name: String, vararg args: Long): LongArray {
        val type = requireNotNull(module.exportedFunctionType(name)) { "関数 $name は export されていない" }
        require(args.size == type.params.size) { "関数 $name の引数は ${type.params.size} 個（${args.size} 個渡された）" }
        check(!closed) { "閉じたインスタンス" }
        val function = exportFunctions.getOrPut(name) { instance().export(name) }
        val result = try {
            function.apply(*args)
        } catch (e: Throwable) {
            rethrow(e)
        }
        return result ?: LongArray(0)
    }

    @Throws(WasmStateException::class)
    override fun captureState(): WasmInstanceState = captureState(StateCaptureMode.FULL)

    /** 区画で数えるメモリ（[WrittenPagesMemory]）か、メモリがなければ追える（#83） */
    override val tracksChanges: Boolean
        get() = memoryWrapper.let { it == null || (it as EndiveWasmMemory).raw is WrittenPagesMemory }

    override fun memoryChunkCounts(): MemoryChunkCounts? {
        val mem = memoryWrapper ?: return MemoryChunkCounts(0, 0)
        val raw = (mem as EndiveWasmMemory).raw as? WrittenPagesMemory ?: return null
        val (written, changed) = raw.chunkCounts()
        return MemoryChunkCounts(written, changed)
    }

    @Throws(WasmStateException::class)
    override fun captureState(mode: StateCaptureMode): WasmInstanceState {
        val inst = instance()
        val mod = module.module
        if (mod.startSection().isPresent) throw WasmStateException("start 関数を持つモジュールは、状態から作り直せない")
        if (mode != StateCaptureMode.FULL && !tracksChanges) throw WasmStateException("このメモリは変わった区画を追えない（$mode）")
        // 可変のグローバル（モジュールが定義したもの。グローバルの import はランタイムが断る）。取り出せない型（可変の v128 など。#851）なら、
        // メモリの区画を写す前に断る（保存のたびに、捨てる区画を写さない）
        if (mod.importSection().count(ExternalType.GLOBAL) > 0) throw WasmStateException("グローバルの import を持つモジュール")
        val globals = ArrayList<WasmInstanceState.GlobalValue>()
        mod.globalSection().globals().forEachIndexed { i, g ->
            if (g.mutabilityType() != MutabilityType.Var) return@forEachIndexed
            val type = valueType(g.valueType())
            if (type == WasmValueType.UNSUPPORTED) throw WasmStateException("取り出せない型の可変のグローバル $i（${g.valueType()}）")
            globals += WasmInstanceState.GlobalValue(i, type, inst.global(i).getValueLow())
        }
        // GC のオブジェクト（struct・array）を初期値に持つグローバル・要素セグメントは、不変でも写せない。オブジェクトの中身（可変のフィールド）は
        // 写さないので、戻すと `struct.set`・`array.set` で書いた値が初期値に戻ってしまう（#851。製品の経路（コンパイルあり）でも届く。#891）
        EndiveRuntime.gcObjectInitializer(mod)?.let { throw WasmStateException("GC のオブジェクトを初期値に持つ$it") }
        // 線形メモリ（0 番）: 書き換えた区画だけ（CHANGED なら、最後の FULL_AND_TRACK から変わった区画だけ。#83）
        var pages = -1
        var indices = IntArray(0)
        var data: List<ByteArray> = emptyList()
        val mem = memoryWrapper
        // FULL_AND_TRACK は、ほかの部分を取り出せた後に区画を読み取り専用に戻す（取り出せない作りなら追跡を変えない）
        var parkAfter: WrittenPagesMemory? = null
        if (mem != null) {
            val raw = (mem as EndiveWasmMemory).raw
            pages = raw.pages()
            if (raw is WrittenPagesMemory) {
                val (i, d) = raw.writtenChunkSnapshot(changedOnly = mode == StateCaptureMode.CHANGED)
                indices = i
                data = d
                if (mode == StateCaptureMode.FULL_AND_TRACK) parkAfter = raw
            } else {
                // 区画で数えないメモリ（テストの設定）: 0 でない区画をすべて書き換えた区画として扱う
                val list = ArrayList<Int>()
                val bytes = ArrayList<ByteArray>()
                val count = pages * WasmInstanceState.CHUNKS_PER_PAGE
                for (c in 0 until count) {
                    val chunk = raw.readBytes(c * WasmInstanceState.CHUNK_BYTES, WasmInstanceState.CHUNK_BYTES)
                    if (chunk.any { it != 0.toByte() }) {
                        list += c
                        bytes += chunk
                    }
                }
                indices = list.toIntArray()
                data = bytes
            }
        }
        // テーブル（funcref だけ。ほかのインスタンスの関数は指せない）
        if (mod.importSection().count(ExternalType.TABLE) > 0) throw WasmStateException("テーブルの import を持つモジュール")
        val tables = ArrayList<WasmInstanceState.TableState>()
        for (t in 0 until mod.tableSection().tableCount()) {
            val table = inst.table(t)
            if (table.elementType().isObjectRef()) throw WasmStateException("取り出せない型のテーブル $t（${table.elementType()}）")
            val refs = IntArray(table.size()) { j ->
                val ref = table.ref(j)
                val owner = table.instance(j)
                if (ref != REF_NULL && owner != null && owner !== inst) throw WasmStateException("テーブル $t の要素 $j がほかのインスタンスの関数を指す")
                ref
            }
            tables += WasmInstanceState.TableState(t, refs)
        }
        // drop 済みのセグメント（Endive は drop したデータを空の並びに、要素を null にする）
        val segments = mod.dataSection().dataSegments()
        val droppedData = segments.indices.filter { i -> segments[i].bytes().isNotEmpty() && inst.dataSegmentData(i).isEmpty() }.toIntArray()
        val droppedElements = (0 until inst.elementCount()).filter { inst.element(it) == null }.toIntArray()
        parkAfter?.parkWritten()
        // メモリがなければ、差分で取っても全体と同じ（区画がない）。全体の形で返す（#562。差分の形はメモリのある状態だけ）
        val changedOnly = mode == StateCaptureMode.CHANGED && mem != null
        return WasmInstanceState(pages, indices, data, globals, tables, droppedData, droppedElements, changedOnly)
    }

    /** 作ったばかりのインスタンスに、保存した状態 [state] を上書きする（#82）。合わなければ [WasmLinkException] */
    fun restoreState(state: WasmInstanceState) {
        val inst = instance()
        val mod = module.module
        if (state.changedOnly) throw WasmLinkException("差分の状態は基準と合成してから戻す")
        // メモリ
        val mem = memoryWrapper
        if ((mem == null) != (state.memoryPages < 0)) throw WasmLinkException("保存した状態とメモリの有無が合わない")
        if (mem != null) {
            val raw = (mem as EndiveWasmMemory).raw
            if (raw is WrittenPagesMemory) {
                // 形が合わない（縮める・上限を超える）失敗と、区画の表の分を RAM に数えられなかった失敗を、文言で分ける（#753）
                if (state.memoryPages < raw.pages() || state.memoryPages > raw.maximumPages()) {
                    throw WasmLinkException("メモリを ${state.memoryPages} ページに戻せない（今 ${raw.pages()}、上限 ${raw.maximumPages()}）")
                }
                if (!raw.restorePages(state.memoryPages)) throw WasmLinkException("メモリを ${state.memoryPages} ページに戻せない（区画の表の分の RAM が足りない）")
                state.forEachChunk { index, bytes ->
                    if (!raw.restoreChunk(index, bytes)) throw WasmLinkException("メモリの区画 $index を戻せない（RAM が足りない）")
                }
            } else {
                val delta = state.memoryPages - raw.pages()
                if (delta < 0 || (delta > 0 && mem.grow(delta) < 0)) throw WasmLinkException("メモリを ${state.memoryPages} ページに戻せない")
                state.forEachChunk { index, bytes -> raw.write(index * WasmInstanceState.CHUNK_BYTES, bytes, 0, bytes.size) }
            }
        }
        // グローバル
        val defined = mod.globalSection().globals()
        for (g in state.globals) {
            val decl = defined.getOrNull(g.index) ?: throw WasmLinkException("グローバル ${g.index} がない")
            if (decl.mutabilityType() != MutabilityType.Var || valueType(decl.valueType()) != g.type) throw WasmLinkException("グローバル ${g.index} の型が合わない")
            inst.global(g.index).setValue(g.bits)
        }
        // テーブル
        val functions = inst.functionCount()
        for (t in state.tables) {
            if (t.index >= mod.tableSection().tableCount()) throw WasmLinkException("テーブル ${t.index} がない")
            val table = inst.table(t.index)
            val delta = t.size - table.size()
            if (delta < 0 || (delta > 0 && table.grow(delta, REF_NULL, inst) < 0)) throw WasmLinkException("テーブル ${t.index} を ${t.size} 要素に戻せない")
            for (j in 0 until t.size) {
                val ref = t.element(j)
                if (ref != REF_NULL && (ref < 0 || ref >= functions)) throw WasmLinkException("テーブル ${t.index} の要素 $j の関数 $ref がない")
                table.setRef(j, ref, if (ref == REF_NULL) null else inst)
            }
        }
        val dataCount = mod.dataSection().dataSegments().size
        for (i in state.droppedDataSegments) {
            if (i < 0 || i >= dataCount) throw WasmLinkException("データセグメント $i がない")
            inst.dropDataSegment(i)
        }
        for (i in state.droppedElementSegments) {
            if (i < 0 || i >= inst.elementCount()) throw WasmLinkException("要素セグメント $i がない")
            inst.setElement(i, null)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        exportFunctions.clear()
        exportMemories.clear()
        try {
            raw?.close()
        } finally {
            budgetedMemories.forEach { it.releaseAll() }
            budgetedTables.forEach { it.releaseAll() }
            gcAllocations?.releaseAll()
        }
    }
}

private class EndiveWasmMemory(private val memory: EndiveMemory) : WasmMemory {
    val raw: EndiveMemory get() = memory

    override val pages: Int get() = memory.pages()

    override fun grow(deltaPages: Int): Int = try {
        memory.grow(deltaPages)
    } catch (e: WasmEngineException) {
        -1
    }

    /** [address]（符号なし）から [length] バイトが範囲に収まるかを確かめ、Endive のアドレスを返す。 */
    private fun check(address: Int, length: Int): Int {
        if (length < 0) throw WasmMemoryAccessException("長さが負: $length")
        val start = address.toUInt().toLong()
        if (start + length > byteSize) {
            throw WasmMemoryAccessException("メモリの範囲外: ${start}〜${start + length}（大きさ $byteSize）")
        }
        return address
    }

    override fun read(address: Int, dst: ByteArray, dstOffset: Int, length: Int) {
        require(dstOffset >= 0 && dstOffset + length <= dst.size) { "dst の範囲外" }
        System.arraycopy(memory.readBytes(check(address, length), length), 0, dst, dstOffset, length)
    }

    override fun read(address: Int, length: Int): ByteArray = memory.readBytes(check(address, length), length)

    override fun write(address: Int, src: ByteArray, srcOffset: Int, length: Int) {
        require(srcOffset >= 0 && srcOffset + length <= src.size) { "src の範囲外" }
        hostWrite { memory.write(check(address, length), src, srcOffset, length) }
    }

    /**
     * ホストの書き込み。書き込んだ区画を RAM に数えられなければ（#407）、WASM の中の書き込みと同じく
     * [WasmTrapKind.OUT_OF_MEMORY] のトラップにする（ホストの関数から投げると、そのままプロセスを止める）
     */
    private inline fun hostWrite(block: () -> Unit) {
        try {
            block()
        } catch (e: RamExhausted) {
            throw outOfMemory(e)
        }
    }

    override fun commit(address: Int, length: Int, keepBytes: Long): Boolean {
        val end = address.toUInt().toLong() + length.toUInt().toLong()
        if (end > byteSize) throw WasmMemoryAccessException("メモリの範囲外: ${address.toUInt()}〜$end（大きさ $byteSize）")
        // 書き込んだ区画で数えるメモリ（#407）だけが、まだ数えていない所を持つ
        val written = memory as? WrittenPagesMemory ?: return true
        return written.commitRange(address, length, keepBytes)
    }

    override fun readU8(address: Int): Int = memory.read(check(address, 1)).toInt() and 0xff

    override fun writeU8(address: Int, value: Int) = hostWrite { memory.writeByte(check(address, 1), value.toByte()) }

    override fun readI32(address: Int): Int = memory.readInt(check(address, 4))

    override fun writeI32(address: Int, value: Int) = hostWrite { memory.writeI32(check(address, 4), value) }

    override fun readI64(address: Int): Long = memory.readLong(check(address, 8))

    override fun writeI64(address: Int, value: Long) = hostWrite { memory.writeLong(check(address, 8), value) }
}
