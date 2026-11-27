package pro.ikili.kibicomputers.core.runtime.asyncify

import pro.ikili.kibicomputers.core.runtime.WasmAsync
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.fuel.FuelSites
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import run.endive.wasm.types.AnnotatedInstruction
import run.endive.wasm.types.ExternalType
import run.endive.wasm.types.FunctionImport
import run.endive.wasm.types.GlobalImport
import run.endive.wasm.types.OpCode
import run.endive.wasm.types.TableImport
import run.endive.wasm.types.TagImport
import run.endive.wasm.types.ValType
import java.io.ByteArrayOutputStream

/**
 * 変換できない作りのモジュール（#329）。`Program.loadMetered` は、これを原因にした `WasmLoadException` で読み込みを断る
 * （`exec` は `NOEXEC`。#367）。
 *
 * [message] は断った理由（どの関数の、どの命令・どの機能か。`exec` のエラーメッセージに入る）。
 * 止まりうる関数がないモジュールは、止まりうる関数の中の作りでは断らない（#384）。グローバル・要素セグメントの定数式の
 * `v128.const` と GC の提案の命令は、定数式としてそのまま写し、これでは断らない（#851）。ただし、止まりうる関数があるモジュールは、
 * 型付きの参照型（`(ref i31)` など）のグローバルや型を持てば、定数式の命令とは別に断る（型を追えない。#384）。
 * struct・array の型の定義（rec グループの中のものも）は、それだけでは断らない（止まりうる関数があっても。#891）。
 * 止まりうる関数の中の、止まりうる `call_ref`（外の型に当たるものも。到達できないところのものも）と、待ち点をまたいだ関数の参照を渡す
 * `call_ref`・`return_call_ref`・結果が funcref でない型付きの `select` は断る
 * （参照は待ち点をまたいで退避できず、一時ローカルに移すと型が funcref になる。#923）。
 */
internal class AsyncifyUnsupported(message: String) : RuntimeException(message)

/** 変換のテスト用の切り替え（#338）。本番の読み込み（`Instrumentation`）は、いつも [NONE] */
internal enum class AsyncifyDebug {
    /** 生きているローカルだけを退避する（本番の形） */
    NONE,

    /** 今までの形: すべてのローカルを退避する（生存解析と出力を比べるため） */
    SAVE_ALL,

    /** 生きているローカルだけを退避し、巻き直しの入口で、戻さないローカル（参照型を除く）にごみを入れる（解析の漏れを見つけるため） */
    POISON_UNSAVED,
}

/**
 * 読み込み時にかける、待機点への変換（asyncify 相当。#329、方式は ADR 0316、PoC は `poc/threadless-wait/`）。
 *
 * 止まりうる import（[transform] の `waitImports`。p1 では `fd_read`・`fd_write`・`poll_oneoff`）の中でホストが状態を
 * [WasmAsyncState.UNWINDING][pro.ikili.kibicomputers.core.runtime.WasmAsyncState.UNWINDING] にして戻ると、呼んだ関数は
 * ローカル変数と「どの呼び出しで止まったか」をホストへ退避して戻り、それを export の入口まで繰り返す。
 * WASM のスタックが空になる（＝待機点になる）ので、スレッドを返せる。
 * 再開するときは、状態を `REWINDING` にして同じ export を呼ぶ。各関数は入口でローカル変数を戻し、止まった呼び出しまで、
 * ほかのコードを飛ばして進む。止まりうる import に着いたら、ホストが状態を `NORMAL` に戻して、その呼び出しをやり直す。
 *
 * binaryen はコードを平らにして（Flatten）からこれを行う。ここでは平らにせず、次の形にする:
 * - 止まりうる呼び出し（止まりうる import・止まりうる関数・`call_indirect`）と、それを含む構造（block・loop・if・try_table）を
 *   「待ち点」とする。待ち点の直前で、オペランドスタックに積まれている値をすべて一時ローカルに移し（スタックを空にする）、直後に戻す
 * - 待ち点の間の命令の並び（ラン）は `if (state == NORMAL)` で包む。巻き直し中は飛ばされる。ランの中の分岐は、包んだ分だけ深さを足す
 * - 呼び出しは `if (state == NORMAL || k == K)` で包む（K はその関数の中の呼び出しの番号）
 * - `if` の条件は一時ローカルに入れておく。巻き直し中は戻したローカルで同じ枝に入る
 * - ブロックの最後のランは `if (result T…) … else <ダミーの値> end` にする（巻き直し中に結果の型をそろえるため）
 * - 引数を持つ待ち点の構造（multi-value。#425）は、引数もほかの値と同じく一時ローカルに移しておき、構造の直前に積み直す。
 *   構造の中では、入口で引数を専用の一時ローカルに移す（巻き直しの間も。巻き直しでは止まった呼び出しより前の構造も通るので、
 *   ほかと共有するローカルに書くと、生きている値を壊す）。loop の先頭への分岐で渡された引数も、同じ入口で移る。
 *   不変条件（#447）: 止まった呼び出しを含む構造なら、巻き直しで構造の直前に積み直す値は、その構造に外から最後に入ったときの引数で、
 *   loop の2回目以降の繰り返しで止まったときは、今の繰り返しの値と違う（先頭への分岐は、構造の外の一時ローカルを書き直さない）。
 *   止まった呼び出しより前にあって、巻き直しで通るだけの構造では、引数の一時ローカルは構造の後で解放される（再利用されることもある）ので、
 *   積み直す値は引数ですらない別の値になりうる。どちらでも正しいのは、次の2つによる:
 *   (a) 専用の引数ローカルを読むのは、最初の待ち点より前のラン（NORMAL のときだけ動く）と、待ち点のない枝の最後のランだけ。
 *   今の繰り返しの値は、最初の待ち点の直前に一時ローカルへ移って退避・復元され、その後は専用の引数ローカルを読まない。
 *   (b) 巻き直しの間（REWINDING のまま）に読まれるのは、中身が空の最後のラン（後ろに待ち点もコードもない。包みのない `local.get`）だけで、
 *   そこでは専用の引数ローカル（中身が空の枝）のほかに、共有の一時ローカル（keep・呼び出しの結果）と結果の専用ローカルも読まれ、
 *   構造の結果の専用ローカルへ送られる。その先がまた中身が空の最後のランなら、外側の構造へさらに送られる（例: 引数付きの `if` の枝が、
 *   待ち点の構造で終わる入れ子）。この連鎖の行き先は、NORMAL のときだけ動くランの prefix か、関数の `$body` の結果（NORMAL でしか届かない）
 *   だけなので、古い値が使われることはない。
 *   巻き直しの後の専用の引数ローカルの中身に頼る変換（読む場所を足す、退避を省いて直接使う、など）を入れるときは、これを崩さないこと
 * - 結果が2つ以上のブロック型（`$body` と最後のランの `if`）は、型セクションに `[] -> [T…]` の型を足して、その番号で書く。
 *   足す型は `async_push`・`async_pop` の型の後に並ぶ（同じ型は1つにまとめる）。そのため、コードセクションを先に書き換えてから型セクションを書く
 * - `call_indirect` は、型が同じ止まりうる関数があるときだけ止まりうる、と見る
 *
 * 足すもの（名前は [WasmAsync]。カーネルと変換したコードの間の取り決めで、プログラムには見せない）:
 * - import の関数 `kibi.async_push`（`(i64) -> ()`）・`kibi.async_pop`（`() -> (i64)`）。退避先はホスト（カーネル）で、
 *   線形メモリには書かない（モジュールのメモリの配置を変えない）。import の関数の最後に足すので、定義済みの関数の番号が2つずれる
 * - 可変の i32 のグローバル（状態。0 = NORMAL、1 = UNWINDING、2 = REWINDING）。`kibi_async_state` という名前で export する
 *
 * 1つの関数が巻き戻しで積む値の並び: 退避するローカル（番号の小さい順。i32・f32・f64 は 64 ビットに広げる。参照型は null だけで、
 * null でなければ trap）、最後に「どの呼び出しで止まったか」の番号。内側の関数から順に積み、巻き直しは外側から順に取り出す。
 * 退避するローカルは関数ごとに決まった集合（#338）: 元のローカルのうち、どれかの待ち点の後で読まれうるもの（生存解析）と、
 * 巻き直しで読む一時ローカル（待ち点の前に移したオペランドスタック、待ち点の構造の引数、やり直す呼び出しに要る引数）。
 * 退避しないローカルは、巻き直しの後は呼ばれたときの値（引数）か 0 のまま。巻き直しの道（止まった呼び出しより前の待ち点の構造を通る）で
 * それを読んでも、ランと呼び出しを飛ばすので害はない（`if` の条件は退避した一時ローカルから読む）。
 *
 * 燃料切れの待ち点化（#339。[transform] の `yieldImport`）: 止まりうる関数の中の `yieldImport`（燃料切れの `kibi.fuel`）の呼び出しも
 * 待ち点にし、呼び先を [WasmAsync.FUEL]（`kibi.async_fuel`）に書き換える。`yieldImport` は止まりうる関数の判定には使わない
 * （燃料の確かめはすべての関数にあるので、使うと全関数が変換されてしまう）。止まりうる関数でない関数の呼び出しはそのまま残す。
 * [WasmAsync.FUEL] の import は `async_pop` の後に足すので、そのとき定義済みの関数の番号は3つずれる。
 *
 * 変換が足すコード（退避・復元・状態の確かめ）は、実行時には燃料を数えない（#341 と同じ。#339 で数える形を試したが、
 * かえって燃料 1 あたりの CPU 時間が増えたのでやめた。ADR 0329 の「燃料切れの待ち点化」）。代わりに、[transform] の `fuelSites`
 * （挿入した課金の位置）があれば、止まりうる関数のセグメントの課金の定数を `FuelSites.weight` 倍にして見込む（#389。
 * ADR 0329 の「#389: 変換が足すコードの見込み」）。課金は `if (state == NORMAL)` のランの中のままなので、巻き直しの間は引かない。
 * 止まりうる関数でない関数（番号の付け替えだけで写す）の課金は変えない。
 *
 * 名前のカスタムセクションは捨てる（燃料の計測コードの挿入と同じ）。
 *
 * 止まりうる関数がないモジュール（止まりうる import を使わない）も、関数の本体は書き換えずに（番号の付け替えだけで）同じ形にする
 * （#384）。足すもの（import 2つと状態のグローバル）はそろうので、カーネルは待機点型（`WAIT_POINT`）として動かせ、専用のスレッドを使わない。
 * 止まりうる関数の中でしか使わない型の情報（型付きの参照型など）で断ることはしない。
 *
 * ない型・import・グローバル・export のセクションは、空のセクションとして扱う（足すものを入れる新しいセクションを作る）。
 * import は関数・メモリ・テーブル・グローバル・タグのどれでもよい。グローバル・テーブル・タグの番号は、import したものが先に並ぶ。
 */
internal object Asyncify {
    /**
     * 変換の結果。
     *
     * @property bytes 変換後の WASM
     * @property functions 定義済みの関数の数
     * @property instrumented 変換した（止まりうる）関数の数
     * @property sites 止まりうる呼び出しの数
     * @property codeBefore コードセクションの大きさ（変換前）
     * @property codeAfter 同じく変換後
     * @property maxLocals 変換した関数の、退避するローカル変数の数の最大
     * @property yieldSites 待ち点にした燃料切れの呼び出しの数（`yieldImport` がないときは 0。[sites] に含む）
     * @property savedLocals 変換した関数の、退避するローカル変数の数の和（#338。生きているものだけ）
     * @property allLocals 同じく、変換した関数のローカル変数（足した一時ローカルを含み、呼び出しの番号を除く）の数の和（すべてを退避したときの [savedLocals]）
     */
    class Result(
        val bytes: ByteArray,
        val functions: Int,
        val instrumented: Int,
        val sites: Int,
        val codeBefore: Int,
        val codeAfter: Int,
        val maxLocals: Int,
        val yieldSites: Int,
        val savedLocals: Int,
        val allLocals: Int,
    )

    private fun parse(wasm: ByteArray): WasmModule = try {
        Parser.parse(wasm)
    } catch (e: RuntimeException) {
        throw WasmLoadException("WASM として読めない: ${e.message}", e)
    } catch (e: StackOverflowError) {
        throw WasmLoadException("WASM を読み込めない: 入れ子が深すぎる", e)
    }

    /**
     * [wasm] を変換する。[waitImports] は止まりうる import（`"<module>.<name>"`）。重いので（lua.wasm で約 100 ms）、メインスレッドでは呼ばない。
     *
     * [checkpoint] は関数の本体を1つ書き換えるごとに呼ぶ（検証の時間の上限。#40）。投げた [WasmLoadException] はそのまま外に出る。
     *
     * [yieldImport]（`"<module>.<name>"`）があれば、止まりうる関数の中のその呼び出しも待ち点にし、[WasmAsync.FUEL] の呼び出しに書き換える（#339）。
     * その import の型は `(i64) -> (i64)` でなければならない。モジュールがその import を持たなければ、何もしない。
     *
     * [originalFunc] は、断る理由に書く関数の番号を、利用者の元のモジュールの番号に戻す（#398）。[wasm] が前の書き換え
     * （燃料の計測コードの挿入）で import を足した後のものなら、その番号の対応を渡す（`FuelInjector.Result.originalFunc`）。
     *
     * [fuelSites] は、[wasm] に挿入した燃料の課金の位置（#389。`FuelInjector.Result.sites`）。あれば、止まりうる関数の
     * セグメントの課金を `FuelSites.weight` 倍にして、変換が足したコードの分を見込む。`null` なら課金は変えない（#389 の前と同じ出力）。
     *
     * @throws AsyncifyUnsupported 止まりうる関数を変換できない作り（その関数の中の SIMD（v128）のローカル変数や命令、
     *   型付きの参照型（GC・関数参照）、止まりうる関数への末尾呼び出し、止まりうる `call_ref`（#923）など）。メッセージに、どの関数の、どの機能かを書く。`Program.loadMetered` は、これで読み込みを断る（#367）。
     *   止まりうる関数がないモジュールは、止まりうる関数の中の作りでは断らない（#384）。定数式の `v128.const`（グローバル・要素セグメント）は断らない（#851）
     * @throws WasmLoadException WASM として読めない（定数式に使えない命令を含む。グローバル・能動な要素セグメントのものは Endive の検証が、
     *   パッシブ・宣言の要素セグメントのものは定数式の写しが断る。#851）、予約した名前（[WasmAsync]）をすでに使っている、時間の上限を超えた。読み込み自体を断る
     */
    @Throws(WasmLoadException::class)
    fun transform(
        wasm: ByteArray,
        waitImports: Set<String>,
        yieldImport: String? = null,
        originalFunc: (Int) -> Int = { it },
        debug: AsyncifyDebug = AsyncifyDebug.NONE,
        fuelSites: FuelSites? = null,
        checkpoint: () -> Unit = {},
    ): Result = prepare(wasm, checkpoint, fuelSites = fuelSites).transform(waitImports, emptySet(), yieldImport, originalFunc, debug, checkpoint)

    /**
     * 関数の型（引数と結果の値の型の並び。値の型は WASM のバイナリの型の番号）。コンポーネントの core モジュールをまたいで、
     * 止まりうる関数の型を集めるのに使う（#757。[Prepared.analyze]）
     */
    data class Signature(val params: List<Int>, val results: List<Int>)

    /**
     * [Prepared.analyze] の結果（#757）。
     *
     * @property suspendableExports 止まりうる関数の export の名前（import を export し直したものも含む）
     * @property suspendTypes 止まりうる関数（止まりうる import を含む）の型
     */
    class Analysis(val suspendableExports: Set<String>, val suspendTypes: Set<Signature>)

    /**
     * 読んだモジュール（#757）。同じモジュールに、止まりうる import を変えて何度も解析（[analyze]）してから、最後に 1 回変換（[transform]）する。
     * コンポーネントの core モジュールは、止まりうる import がほかの core モジュールの解析の結果で決まるので、全体で答えが変わらなくなるまで
     * 解析を繰り返す（`ComponentAsyncify`）
     */
    class Prepared internal constructor(
        private val wasm: ByteArray,
        private val module: WasmModule,
        private val numbering: (Int) -> Int = { it },
        /** 挿入した燃料の計測コードの位置（#389。[Asyncify.transform] の `fuelSites`） */
        private val fuelSites: FuelSites? = null,
    ) {
        /**
         * 止まりうる関数を求めるだけで、書き換えない。[externalSuspendTypes] は、モジュールの外から止まりうるとみなす関数の型（[transform]）
         *
         * @throws AsyncifyUnsupported 解析できない作り
         */
        fun analyze(waitImports: Set<String>, externalSuspendTypes: Set<Signature>): Analysis =
            guard { Transformer(wasm, module, waitImports, null, numbering, {}, externalSuspendTypes).analysis() }

        /**
         * 変換する（[Asyncify.transform] と同じ）。[externalSuspendTypes] は、モジュールの外から止まりうるとみなす関数の型（#757）。
         * import・export したテーブルへの `call_indirect` と `call_ref` は、型がこのどれかでも止まりうるとみなす
         * （テーブルの中身や関数の参照は、ほかのモジュールが入れられるため）。止まりうる関数の中の、そうして止まりうるとみなした
         * `call_ref` は断る（#923）。空なら今までと同じ出力になる
         *
         * @throws AsyncifyUnsupported 止まりうる関数を変換できない作り（[Asyncify.transform]）
         * @throws WasmLoadException 時間の上限を超えた
         */
        @Throws(WasmLoadException::class)
        fun transform(
            waitImports: Set<String>,
            externalSuspendTypes: Set<Signature> = emptySet(),
            yieldImport: String? = null,
            originalFunc: (Int) -> Int = numbering,
            debug: AsyncifyDebug = AsyncifyDebug.NONE,
            checkpoint: () -> Unit = {},
        ): Result = guard {
            // 位置は挿入の結果から渡されたもの。合わなければ内部の誤り。ほかの変換の誤りと同じく、変換できない（[AsyncifyUnsupported]）として断る
            // （燃料の数を壊さないように、黙って続けない。p1 は段1 → 段2 と試して読み込みを断り、コンポーネントは待機点に変換せずに動く）
            fuelSites?.let { check(it.charges.size == module.codeSection().functionBodyCount()) { "燃料の計測コードの位置が、関数の数と合わない" } }
            Transformer(wasm, module, waitImports, yieldImport, originalFunc, checkpoint, externalSuspendTypes, debug, fuelSites).run()
        }

        private inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: WasmLoadException) {
            throw e
        } catch (e: AsyncifyUnsupported) {
            throw e
        } catch (e: RuntimeException) {
            throw AsyncifyUnsupported("変換できない: $e").also { it.initCause(e) }
        } catch (e: StackOverflowError) {
            throw AsyncifyUnsupported("変換できない: 入れ子が深すぎる").also { it.initCause(e) }
        }
    }

    /**
     * [wasm] を読み、予約した名前を使っていないか確かめる（#757。解析と変換は [Prepared] で）。[checkpoint] は読んだ後に 1 回呼ぶ
     *
     * [originalFunc] は、断る理由に書く関数の番号を元のモジュールの番号に戻す（[transform] と同じ。#398）。[Prepared] の解析と変換の既定になる。
     * [fuelSites] は、挿入した燃料の計測コードの位置（[transform] と同じ。#389）
     *
     * @throws WasmLoadException WASM として読めない、予約した名前（[WasmAsync]）をすでに使っている、時間の上限を超えた
     */
    @Throws(WasmLoadException::class)
    fun prepare(wasm: ByteArray, checkpoint: () -> Unit = {}, originalFunc: (Int) -> Int = { it }, fuelSites: FuelSites? = null): Prepared {
        val module = parse(wasm)
        checkpoint()
        checkReserved(module)
        return Prepared(wasm, module, originalFunc, fuelSites)
    }

    /** 予約した名前をすでに使っていたら断る（プレイヤーが退避した値や状態を自分で書き換えられてしまう） */
    private fun checkReserved(module: WasmModule) {
        val imports = module.importSection()
        for (i in 0 until imports.importCount()) {
            val imp = imports.getImport(i)
            if (imp.module() == WasmAsync.IMPORT_MODULE && (imp.name() == WasmAsync.PUSH || imp.name() == WasmAsync.POP || imp.name() == WasmAsync.FUEL)) {
                throw WasmLoadException("${imp.module()}.${imp.name()} は予約された import")
            }
        }
        val exports = module.exportSection()
        for (i in 0 until exports.exportCount()) {
            if (exports.getExport(i).name() == WasmAsync.STATE_EXPORT) throw WasmLoadException("${WasmAsync.STATE_EXPORT} は予約された export")
        }
    }
}

private const val UNWINDING = 1
private const val REWINDING = 2

/**
 * 生存解析（#338）の大きさの上限: 解析の頂点の数（基本ブロックと、`try_table` の catch 先の節）× ローカル変数の 64 個の組の数。
 * 超えた関数は、すべてのローカルを退避する（今までの形）。1 つのビット集合の配列が 8 MiB（3 本で 24 MiB）になる大きさ。
 * lua.wasm のいちばん大きい関数でも、この 1% 未満。
 *
 * ビット集合のほかに作る配列（頂点ごとの後続など）は、関数の命令の数とバイト数に比例する大きさ（命令ごとに、囲む catch 先の数に比例する配列は作らない）。
 * 基本ブロックの数が上限を超えると決まった時点で、後続の配列を作る前にやめる。組み立てのループでも [Transformer.checkpoint] を呼ぶ（時間の上限。#40）
 */
private const val LIVENESS_LIMIT_WORDS = 1L shl 20

/** [AsyncifyDebug.POISON_UNSAVED] で、戻さないローカルに入れる値 */
private const val POISON = 0x5EADBEEF5EADBEEFL

private const val I32 = 0x7F
private const val I64 = 0x7E
private const val F32 = 0x7D
private const val F64 = 0x7C
private const val V128 = 0x7B
private const val BOT = -1

/** funcref（略記の `(ref null func)` も同じ番号になる。[code]） */
private const val FUNCREF = 0x70

/**
 * 型の追跡だけで使う印: `ref.func` が積んだ、型付きの関数の参照（`(ref $t)`。#923）。値の型としては [FUNCREF] と同じに扱い、
 * 一時ローカルに移すと [FUNCREF] になる（`FunctionRewriter` の `alloc`。宣言する型は今までと同じ）。
 * 待ち点をまたいで型を失った（[FUNCREF] になった）参照を `call_ref`・`return_call_ref`・結果が funcref でない型付きの `select` に渡すと、
 * 出力が検証に通らないので断る
 */
private const val REF_FUNC = 0x170

private fun isRef(t: Int) = t != I32 && t != I64 && t != F32 && t != F64 && t != V128 && t != BOT

private fun code(v: ValType): Int {
    val op = v.opcode()
    // Endive は funcref などの略記を (ref null <抽象ヒープ型>) で持つ。抽象ヒープ型の番号は「略記のバイト - 0x80」
    if (op == 0x63 && v.typeIdx() < 0) return v.typeIdx() + 0x80
    if (op == 0x63 || op == 0x64) throw AsyncifyUnsupported("型付きの参照型（GC・関数参照の提案の (ref <型>)）には対応していない")
    return op
}

private class Sig(val params: List<Int>, val results: List<Int>)

/** 関数の本体の木。葉は命令、枝は block・loop・if・try_table */
private sealed class Node

private class Leaf(val ins: AnnotatedInstruction) : Node()

private class Struct(
    val open: AnnotatedInstruction,
    val body: List<Node>,
    val els: List<Node>?,
) : Node()

private class TypeStack(initial: List<Int> = emptyList()) {
    val types = ArrayList(initial)
    var unreachable = false

    fun push(t: Int) {
        types += t
    }

    fun pop(): Int = if (types.isEmpty()) {
        if (!unreachable) throw AsyncifyUnsupported("命令の型を追えない（スタックが空なのに取り出した）")
        BOT
    } else {
        types.removeAt(types.size - 1)
    }

    fun pop(n: Int) = repeat(n) { pop() }

    fun kill() {
        types.clear()
        unreachable = true
    }
}

private class Transformer(
    private val wasm: ByteArray,
    private val module: WasmModule,
    private val asyncImports: Set<String>,
    yieldImport: String?,
    private val originalFunc: (Int) -> Int,
    private val checkpoint: () -> Unit,
    /** モジュールの外から止まりうるとみなす関数の型（#757。[Asyncify.Prepared.transform]） */
    private val externalSuspendTypes: Set<Asyncify.Signature> = emptySet(),
    private val debug: AsyncifyDebug = AsyncifyDebug.NONE,
    /** 挿入した燃料の計測コードの位置（#389）。`null` なら課金を変えない */
    private val fuelSites: FuelSites? = null,
) {
    private val importedFuncs: Int
    private val funcSigs = ArrayList<Sig>()
    /**
     * 型の番号（rec グループを平らにした番号）→ 関数の型。struct・array の型（GC の提案）は `null`（#891）。
     * 型そのものでは断らない（止まりうる関数がなければ、型は使わない）。関数の型として引いたときだけ断る（[typeSig]）
     */
    private val typeSigs = ArrayList<Sig?>()

    /** 関数の型として番号 [idx] の型を引く。関数の型でなければ断る（正しいモジュールでは起きない。検証が先に断る） */
    private fun typeSig(idx: Int): Sig =
        typeSigs.getOrNull(idx) ?: throw AsyncifyUnsupported(
            if (idx in typeSigs.indices) "型 $idx は関数の型でない（GC の提案の struct・array の型）" else "型 $idx がない",
        )
    private val globalTypes = ArrayList<Int>()
    private val tableTypes = ArrayList<Int>()
    private val tagSigs = ArrayList<Sig>()
    private val asyncFuncs = HashSet<Int>()

    /**
     * 型・グローバル・テーブルの型に、追えない型（型付きの参照型）があったときの理由。止まりうる関数があるときだけ断る（#384）。
     * 止まりうる関数がなければ型は使わないので、そのまま写す
     */
    private var untypedReason: String? = null

    private fun codeOrBot(v: ValType): Int = try {
        code(v)
    } catch (e: AsyncifyUnsupported) {
        if (untypedReason == null) untypedReason = e.message
        BOT
    }

    /** 止まりうる関数（元の番号） */
    private val suspendable = HashSet<Int>()

    private val pushFunc: Int
    private val popFunc: Int
    private val stateGlobal: Int

    /** 燃料切れの import（`yieldImport`）の番号。なければ -1 */
    private val yieldFunc: Int

    /** 足す [WasmAsync.FUEL] の import の番号（[yieldFunc] がなければ -1） */
    private val asyncFuelFunc: Int

    /** [yieldFunc] の型の番号（足す import に使う） */
    private val yieldType: Int

    /** 定義済みの関数の番号のずれ（足す import の数） */
    private val shift: Int

    /** テーブルの番号 → import か export したテーブルか（#757。中身をほかのモジュールが入れられる） */
    private val sharedTables = ArrayList<Boolean>()

    init {
        val types = module.typeSection()
        // 型の番号は rec グループを平らにした番号なので、rec グループの数（typeCount）ではなく、平らにした数まで読む（#891）
        for (i in 0 until types.subTypeCount()) {
            val t = types.getSubType(i).compType().funcType()
            typeSigs += t?.let { Sig(it.params().map(::codeOrBot), it.returns().map(::codeOrBot)) }
        }
        val imports = module.importSection()
        var nf = 0
        var yf = -1
        var yt = -1
        for (i in 0 until imports.importCount()) {
            val imp = imports.getImport(i)
            // 関数でない import は、番号の空間（グローバル・テーブル・タグ）に先に並ぶ。メモリは型の追跡に関わらない（#384）
            when (imp) {
                is GlobalImport -> globalTypes += codeOrBot(imp.type())
                is TableImport -> {
                    tableTypes += codeOrBot(imp.entryType())
                    sharedTables += true
                }
                is TagImport -> tagSigs += typeSig(imp.tagType().typeIdx())
            }
            if (imp !is FunctionImport) continue
            val full = "${imp.module()}.${imp.name()}"
            if (full in asyncImports) asyncFuncs += nf
            if (full == yieldImport) {
                val s = typeSig(imp.typeIndex())
                if (s.params != listOf(I64) || s.results != listOf(I64)) throw AsyncifyUnsupported("燃料切れの import $full の型が (i64) -> (i64) でない")
                yf = nf
                yt = imp.typeIndex()
            }
            funcSigs += typeSig(imp.typeIndex())
            nf++
        }
        importedFuncs = nf
        yieldFunc = if (yf in asyncFuncs) -1 else yf
        yieldType = yt
        val fs = module.functionSection()
        for (i in 0 until fs.functionCount()) funcSigs += typeSig(fs.getFunctionType(i))
        val gs = module.globalSection()
        for (i in 0 until gs.globalCount()) globalTypes += codeOrBot(gs.getGlobal(i).valueType())
        val ts = module.tableSection()
        for (i in 0 until ts.tableCount()) {
            tableTypes += codeOrBot(ts.getTable(i).elementType())
            sharedTables += false
        }
        val es = module.exportSection()
        for (i in 0 until es.exportCount()) {
            val e = es.getExport(i)
            val t = e.index()
            if (e.exportType() == ExternalType.TABLE && t < sharedTables.size) sharedTables[t] = true
        }
        module.tagSection().ifPresent { tags -> tags.types().forEach { tagSigs += typeSig(it.typeIdx()) } }
        pushFunc = importedFuncs
        popFunc = importedFuncs + 1
        asyncFuelFunc = if (yieldFunc >= 0) importedFuncs + 2 else -1
        shift = if (yieldFunc >= 0) 3 else 2
        stateGlobal = globalTypes.size
    }

    /** 止まりうる関数の型（引数と結果の並び）。間接呼び出しは、型が同じ関数しか呼べないので、この型のときだけ止まりうる */
    private val suspendTypes = HashSet<Pair<List<Int>, List<Int>>>()

    private fun indirectMaySuspend(ins: AnnotatedInstruction): Boolean {
        val sig = typeSig(ins.operand(0).toInt())
        if (sig.params to sig.results in suspendTypes) return true
        if (externalSuspendTypes.isEmpty() || Asyncify.Signature(sig.params, sig.results) !in externalSuspendTypes) return false
        // 外の型は、中身をほかのモジュールが入れられるもの（import・export したテーブル、関数の参照）にだけ当てる（#757）。
        // ほかのモジュールの関数の参照が入りうる形のモジュールでは、import も export もしていないテーブルにも入れられる
        // （[foreignFuncRefs]）ので、どのテーブルにも当てる
        return when (ins.opcode()) {
            OpCode.CALL_INDIRECT, OpCode.RETURN_CALL_INDIRECT -> foreignFuncRefs || sharedTables.getOrElse(ins.operand(1).toInt()) { true }
            else -> true
        }
    }

    /**
     * ほかのモジュールの関数の参照が、モジュールの中に入りうる形か（#757）。次のどれかなら `true`:
     * import・export したテーブルがある（`table.get`・`table.copy` で、import も export もしていないテーブルに移せる）、
     * funcref のグローバルを import・export している、import・export した関数の引数・結果に funcref がある（`table.set` で入れられる）、
     * funcref を運ぶタグがある（例外の値で渡せる）。型を追えない参照型（[BOT]）は funcref かもしれないので、同じく `true` にする。
     * 参照が入りうるかだけを見る（テーブルへの命令の流れは追わない）ので、実際には入らなくても `true` になることがある
     * （そのときは止まりうるとみなす関数が増えるだけで、変換は正しい）
     */
    private val foreignFuncRefs: Boolean by lazy {
        fun mayHoldFunc(t: Int) = t == FUNCREF || t == BOT
        fun sigMayHoldFunc(s: Sig) = s.params.any(::mayHoldFunc) || s.results.any(::mayHoldFunc)
        if (sharedTables.any { it }) return@lazy true
        if (tagSigs.any(::sigMayHoldFunc)) return@lazy true
        // import したグローバルは、グローバルの番号の先頭に並ぶ
        var importedGlobals = 0
        for (i in 0 until module.importSection().importCount()) {
            if (module.importSection().getImport(i) is GlobalImport) importedGlobals++
        }
        if ((0 until importedGlobals).any { mayHoldFunc(globalTypes[it]) }) return@lazy true
        // import した関数の型
        if ((0 until importedFuncs).any { sigMayHoldFunc(funcSigs[it]) }) return@lazy true
        val es = module.exportSection()
        (0 until es.exportCount()).map { es.getExport(it) }.any { e ->
            when (e.exportType()) {
                ExternalType.GLOBAL -> mayHoldFunc(globalTypes[e.index()])
                ExternalType.FUNCTION -> sigMayHoldFunc(funcSigs[e.index()])
                else -> false
            }
        }
    }

    /** 止まりうる関数を求め、export の名前と型を返す（書き換えない。#757） */
    fun analysis(): Asyncify.Analysis {
        analyze(bodies())
        val es = module.exportSection()
        val names = LinkedHashSet<String>()
        for (i in 0 until es.exportCount()) {
            val e = es.getExport(i)
            if (e.exportType() == ExternalType.FUNCTION && e.index() in suspendable) names += e.name()
        }
        val types = suspendable.mapTo(LinkedHashSet()) { Asyncify.Signature(funcSigs[it].params, funcSigs[it].results) }
        return Asyncify.Analysis(names, types)
    }

    private fun remap(idx: Long): Long = if (idx >= importedFuncs) idx + shift else idx

    private fun bodies(): List<List<AnnotatedInstruction>> =
        (0 until module.codeSection().functionBodyCount()).map { module.codeSection().getFunctionBody(it).instructions() }

    private fun analyze(bodies: List<List<AnnotatedInstruction>>) {
        if (asyncFuncs.isEmpty() && externalSuspendTypes.isEmpty()) return
        suspendable += asyncFuncs
        var changed = true
        while (changed) {
            changed = false
            for (f in suspendable) suspendTypes += funcSigs[f].params to funcSigs[f].results
            for ((i, body) in bodies.withIndex()) {
                val f = importedFuncs + i
                if (f in suspendable) continue
                val calls = body.any { ins ->
                    when (ins.opcode()) {
                        OpCode.CALL, OpCode.RETURN_CALL -> ins.operand(0).toInt() in suspendable
                        OpCode.CALL_INDIRECT, OpCode.CALL_REF, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF ->
                            indirectMaySuspend(ins)
                        else -> false
                    }
                }
                if (calls) {
                    suspendable += f
                    changed = true
                }
            }
        }
    }

    fun run(): Asyncify.Result {
        val bodies = bodies()
        analyze(bodies)
        // 型を追うのは、書き換える（定義済みの）止まりうる関数だけ。止まりうる import を持っていても、呼ぶ関数がなければ断らない
        if (suspendable.any { it >= importedFuncs }) untypedReason?.let { throw AsyncifyUnsupported("止まりうる関数があるので型を追う必要があるが、$it") }
        val sections = withRequiredSections(scanSections())
        // コードセクションを先に書き換える（multi-value のブロック型に使う型を、型セクションに足すため。#425）
        val code = sections.firstOrNull { it.id == 10 }?.let { rewriteCode(it, bodies) }
        val out = Sink()
        out.bytes(wasm, 0, 8)
        fun emit(id: Int, body: Sink) {
            out.u8(id)
            out.leb(body.size().toLong())
            out.append(body)
        }
        for (s in sections) {
            val r = Reader(wasm, s.start, s.end)

            // 足した空のセクション（[withRequiredSections]）は、要素の数が 0 で中身がない
            fun count(): Long = if (s.empty) 0 else r.leb()
            when (s.id) {
                0 -> if (Reader(wasm, s.start, s.end).name() != "name") emit(0, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
                1 -> {
                    val n = count()
                    val body = Sink()
                    body.leb(n + 2 + blockTypes.size)
                    body.bytes(wasm, r.pos, s.end - r.pos)
                    // (i64) -> () と () -> (i64)
                    body.u8(0x60); body.leb(1); body.u8(I64); body.leb(0)
                    body.u8(0x60); body.leb(0); body.leb(1); body.u8(I64)
                    // 変換で足した、multi-value のブロック型
                    for (t in blockTypes) {
                        body.u8(0x60)
                        body.leb(t.params.size)
                        t.params.forEach(body::u8)
                        body.leb(t.results.size)
                        t.results.forEach(body::u8)
                    }
                    emit(1, body)
                }
                2 -> {
                    val n = count()
                    val body = Sink()
                    body.leb(n + shift)
                    body.bytes(wasm, r.pos, s.end - r.pos)
                    body.name(WasmAsync.IMPORT_MODULE); body.name(WasmAsync.PUSH); body.u8(0); body.leb(newTypePush)
                    body.name(WasmAsync.IMPORT_MODULE); body.name(WasmAsync.POP); body.u8(0); body.leb(newTypePush + 1)
                    if (asyncFuelFunc >= 0) {
                        body.name(WasmAsync.IMPORT_MODULE); body.name(WasmAsync.FUEL); body.u8(0); body.leb(yieldType)
                    }
                    emit(2, body)
                }
                6 -> {
                    val n = count()
                    val body = Sink()
                    body.leb(n + 1)
                    repeat(n.toInt()) {
                        r.copyValType(body)
                        body.u8(r.u8())
                        r.copyConstExpr(body, ::remap)
                    }
                    body.u8(I32); body.u8(1); body.u8(0x41); body.sleb(0); body.u8(0x0B)
                    emit(6, body)
                }
                7 -> {
                    val n = count()
                    val body = Sink()
                    body.leb(n + 1)
                    repeat(n.toInt()) {
                        val ns = r.pos
                        r.name()
                        body.bytes(wasm, ns, r.pos - ns)
                        val kind = r.u8()
                        body.u8(kind)
                        val idx = r.leb()
                        body.leb(if (kind == 0) remap(idx) else idx)
                    }
                    body.name(WasmAsync.STATE_EXPORT); body.u8(3); body.leb(stateGlobal)
                    emit(7, body)
                }
                8 -> emit(8, Sink().also { it.leb(remap(r.leb())) })
                9 -> emit(9, rewriteElements(r))
                10 -> emit(10, code!!)
                else -> emit(s.id, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
            }
        }
        return Asyncify.Result(
            out.toByteArray(), bodies.size, instrumented, sites, codeBefore, codeAfter, maxLocals, yieldSites, savedTotal, localsTotal,
        )
    }

    // 変換の集計（[rewriteCode] が数える）
    private var savedTotal = 0
    private var localsTotal = 0
    private var codeBefore = 0
    private var codeAfter = 0
    private var instrumented = 0
    private var sites = 0
    private var maxLocals = 0
    private var yieldSites = 0

    /** 型セクションの、変換の前からある型の数。足す型の番号はこの後に続く */
    private val typeBase = module.typeSection().subTypeCount()

    /** 足す import（`async_push`・`async_pop`）の型の番号。`async_pop` は次の番号 */
    private val newTypePush = typeBase

    /** 変換で足す、複数の結果を持つブロック型（multi-value。#425）。`async_push`・`async_pop` の型の後に並ぶ */
    private val blockTypes = ArrayList<Sig>()
    private val blockTypeIndex = HashMap<Pair<List<Int>, List<Int>>, Int>()

    /** 引数が [params]・結果が [results] のブロック型の、型の番号（まだ足していなければ足す） */
    private fun blockTypeIdx(params: List<Int>, results: List<Int>): Int = blockTypeIndex.getOrPut(params to results) {
        blockTypes += Sig(params, results)
        typeBase + 2 + blockTypes.size - 1
    }

    /** コードセクションの中身を書き換える。止まりうる関数は [FunctionRewriter] で、それ以外は番号の付け替えだけ */
    private fun rewriteCode(s: Section, bodies: List<List<AnnotatedInstruction>>): Sink {
        val r = Reader(wasm, s.start, s.end)
        val n = r.leb().toInt()
        val body = Sink()
        body.leb(n)
        for (i in 0 until n) {
            val size = r.leb().toInt()
            val start = r.pos
            val end = start + size
            val f = importedFuncs + i
            checkpoint()
            val fn = Sink()
            if (f in suspendable) {
                val rw: FunctionRewriter
                val stats = try {
                    rw = FunctionRewriter(f, bodies[i], start, end)
                    rw.rewrite(fn)
                } catch (e: AsyncifyUnsupported) {
                    throw AsyncifyUnsupported("止まりうる関数 ${describe(f)} の中: ${e.message}").also { it.initCause(e) }
                }
                instrumented++
                sites += stats.first
                maxLocals = maxOf(maxLocals, stats.second)
                yieldSites += rw.yieldSites
            } else {
                copyPlainFunction(bodies[i], start, end, fn)
            }
            codeBefore += size
            codeAfter += fn.size()
            body.leb(fn.size())
            body.append(fn)
            r.pos = end
        }
        return body
    }

    /** エラーメッセージ用の関数の呼び方（元のモジュールの番号（[originalFunc]）と、あれば export の名前） */
    private fun describe(f: Int): String {
        val exports = module.exportSection()
        val names = (0 until exports.exportCount()).map { exports.getExport(it) }
            .filter { it.exportType() == ExternalType.FUNCTION && it.index() == f }
            .map { it.name() }
        val n = originalFunc(f)
        return if (names.isEmpty()) "#$n" else "#$n（export ${names.joinToString("、")}）"
    }

    /** [empty] は、元のモジュールになく、足した空のセクション（[start]・[end] は使わない） */
    private class Section(val id: Int, val start: Int, val end: Int, val empty: Boolean = false)

    /**
     * 足すものを入れる型・import・グローバル・export のセクション（1・2・6・7）がなければ、空のセクションとして正しい位置に足す（#384）。
     * セクションの並び順は 1, 2, 3, 4, 5, 13（タグ）, 6, 7, 8, 9, 12, 10, 11。カスタムセクション（0）はどこにあってもよい
     */
    private fun withRequiredSections(sections: List<Section>): List<Section> {
        val order = listOf(1, 2, 3, 4, 5, 13, 6, 7, 8, 9, 12, 10, 11)
        fun rank(id: Int) = order.indexOf(id).let { if (it < 0) order.size else it }
        val list = ArrayList(sections)
        for (id in listOf(1, 2, 6, 7)) {
            if (list.any { it.id == id }) continue
            val at = list.indexOfFirst { it.id != 0 && rank(it.id) > rank(id) }.let { if (it < 0) list.size else it }
            list.add(at, Section(id, 0, 0, empty = true))
        }
        return list
    }

    private fun scanSections(): List<Section> {
        val r = Reader(wasm, 8, wasm.size)
        val list = ArrayList<Section>()
        while (r.pos < wasm.size) {
            val id = r.u8()
            val size = r.leb().toInt()
            list += Section(id, r.pos, r.pos + size)
            r.pos += size
        }
        return list
    }

    private fun rewriteElements(r: Reader): Sink {
        val body = Sink()
        val n = r.leb()
        body.leb(n)
        repeat(n.toInt()) {
            val flag = r.leb().toInt()
            body.leb(flag.toLong())
            fun funcIdxVec() {
                val m = r.leb()
                body.leb(m)
                repeat(m.toInt()) { body.leb(remap(r.leb())) }
            }
            fun exprVec() {
                val m = r.leb()
                body.leb(m)
                repeat(m.toInt()) { r.copyConstExpr(body, ::remap) }
            }
            when (flag) {
                0 -> {
                    r.copyConstExpr(body, ::remap)
                    funcIdxVec()
                }
                1, 3 -> {
                    body.u8(r.u8())
                    funcIdxVec()
                }
                2 -> {
                    body.leb(r.leb())
                    r.copyConstExpr(body, ::remap)
                    body.u8(r.u8())
                    funcIdxVec()
                }
                4 -> {
                    r.copyConstExpr(body, ::remap)
                    exprVec()
                }
                5, 7 -> {
                    r.copyValType(body)
                    exprVec()
                }
                6 -> {
                    body.leb(r.leb())
                    r.copyConstExpr(body, ::remap)
                    r.copyValType(body)
                    exprVec()
                }
                else -> throw AsyncifyUnsupported("知らない要素セグメントの形式（$flag）")
            }
        }
        return body
    }

    private fun insEnd(list: List<AnnotatedInstruction>, i: Int, bodyEnd: Int) = if (i + 1 < list.size) list[i + 1].address() else bodyEnd

    /** 変換しない関数: 関数の番号だけ付け替えて写す */
    private fun copyPlainFunction(list: List<AnnotatedInstruction>, start: Int, end: Int, out: Sink) {
        val first = list.first().address()
        out.bytes(wasm, start, first - start)
        for (i in list.indices) {
            val ins = list[i]
            when (ins.opcode()) {
                OpCode.CALL, OpCode.RETURN_CALL, OpCode.REF_FUNC -> {
                    out.u8(ins.opcode().opcode())
                    out.leb(remap(ins.operand(0)))
                }
                else -> out.bytes(wasm, ins.address(), insEnd(list, i, end) - ins.address())
            }
        }
    }

    // ---- 止まりうる関数の書き換え ----

    private inner class FunctionRewriter(
        private val func: Int,
        private val list: List<AnnotatedInstruction>,
        private val bodyStart: Int,
        private val bodyEnd: Int,
    ) {
        private val sig = funcSigs[func]
        private val localTypes = ArrayList<Int>()
        private val origLocals: Int
        private val newLocals = ArrayList<Int>()
        private val free = HashMap<Int, ArrayDeque<Int>>()
        private val endOf = java.util.IdentityHashMap<AnnotatedInstruction, Int>()
        private var siteCount = 0
        private val kLocal: Int

        /** 待ち点にした燃料切れの呼び出しの数 */
        var yieldSites = 0
            private set

        /** 燃料の課金の `i64.const` の位置（#389。[fuelSites] がなければ空。差し替えない） */
        private val chargeAt = HashSet<Int>()

        /** 課金の `i64.const` を出した、$body の中の位置と元の命令の長さ・元の課金（[rewrite] が定数を差し替える） */
        private val holes = ArrayList<LongArray>()

        init {
            localTypes += sig.params
            module.codeSection().getFunctionBody(func - importedFuncs).localTypes().forEach { localTypes += code(it) }
            origLocals = localTypes.size
            for (i in list.indices) endOf[list[i]] = insEnd(list, i, bodyEnd)
            if (localTypes.any { it == V128 }) throw AsyncifyUnsupported("SIMD（v128）のローカル変数には対応していない")
            kLocal = newLocal(I32)
            fuelSites?.let { sites ->
                val starts = HashSet<Int>(list.size * 2)
                for (ins in list) starts += ins.address()
                for (c in sites.charges[func - importedFuncs]) {
                    val at = bodyStart + c
                    // 位置は挿入の結果から渡されたもの。ずれていたら内部の誤り（燃料の数を壊さないように、黙って続けない）
                    check(at in starts && wasm[at].toInt() == 0x42) { "燃料の課金の位置 $at が i64.const でない" }
                    chargeAt += at
                }
            }
        }

        /** [body] を [out] に写し、課金の定数を、見込んだ値（[FuelSites.weight]）に差し替える（#389） */
        private fun appendPatched(out: Sink, body: Sink) {
            val weight = fuelSites?.weight
            check(holes.size == chargeAt.size) { "燃料の課金を ${chargeAt.size} 個のうち ${holes.size} 個しか写していない" }
            if (holes.isEmpty() || weight == null) {
                out.append(body)
                return
            }
            val bytes = body.toByteArray()
            var at = 0
            for (h in holes) {
                val pos = h[0].toInt()
                out.bytes(bytes, at, pos - at)
                out.u8(0x42)
                out.sleb(weight.apply(h[2]))
                at = pos + h[1].toInt()
            }
            out.bytes(bytes, at, bytes.size - at)
        }

        private fun newLocal(t: Int): Int {
            if (t == V128) throw AsyncifyUnsupported("SIMD（v128）のローカル変数には対応していない")
            // 型を追えない参照（BOT）は、宣言できる型がない。型の番号の `ref.null $s` などの値を待ち点をまたいで持つときに届く（#891）
            if (t == BOT) throw AsyncifyUnsupported("型付きの参照型（GC・関数参照の提案の (ref <型>)）の値を持ったまま待つことには対応していない")
            newLocals += t
            localTypes += t
            return localTypes.size - 1
        }

        private fun alloc(type: Int): Int {
            // 型付きの関数の参照（[REF_FUNC]）は、funcref の一時ローカルに移す（#923。宣言する型は #923 の前と同じ）
            val t = if (type == REF_FUNC) FUNCREF else type
            return free[t]?.removeFirstOrNull() ?: newLocal(t)
        }

        /** 共有しない一時ローカル（構造の結果） */
        private val dedicated = HashSet<Int>()

        private fun release(idx: Int) {
            if (idx in dedicated) return
            free.getOrPut(localTypes[idx]) { ArrayDeque() }.addLast(idx)
        }

        /**
         * 巻き戻しで退避する一時ローカル（#338）。待ち点の直前でオペランドスタックを移した一時ローカル（`keep`）、
         * 待ち点の構造の引数（`if` の条件を含む。巻き直しで構造に入る直前に積み直す）、巻き直しで同じ呼び出しをやり直すのに要る
         * 呼び出しの引数（止まりうる import は最初からやり直す。`call_indirect` の表の番号は同じ関数を呼ぶのに要る。
         * 止まりうる `call_ref` は断る（#923）ので、`call_ref` の参照は退避しない）。
         * 定義済みの関数への直接の `call` の引数（呼ばれた側が自分の生きているローカルを戻す）と、燃料切れの呼び出しの引数（巻き直しでは使わない）は退避しない。
         * 一時ローカルは使い回すので、どこかの待ち点でこれに当たったものの和を取る
         */
        private val savedTemps = HashSet<Int>()

        /** (呼び出しの数, 退避するローカルの数) */
        fun rewrite(out: Sink): Pair<Int, Int> {
            rejectSuspendingCallRef()
            val (nodes, _) = buildTree(0)
            val body = Sink()
            emitSuspendBody(body, nodes, sig.results, 0)
            val live = if (debug == AsyncifyDebug.SAVE_ALL) null else liveAcrossWaitPoints()
            // 退避するローカル（番号の小さい順に積み、逆の順に取り出す。積むものと取り出すものは、いつもこの1つの並びから作る）
            val saved = localTypes.indices.filter { i ->
                when {
                    i == kLocal -> false
                    live == null -> true
                    i < origLocals -> live[i]
                    else -> i in savedTemps
                }
            }
            val isSaved = BooleanArray(localTypes.size).also { a -> saved.forEach { a[it] = true } }
            savedTotal += saved.size
            localsTotal += localTypes.size - 1

            // ローカル変数の宣言
            val r = Reader(wasm, bodyStart, list.first().address())
            val groups = r.leb()
            val runs = ArrayList<Pair<Int, Int>>()
            for (t in newLocals) if (runs.isNotEmpty() && runs.last().second == t) runs[runs.size - 1] = runs.last().first + 1 to t else runs += 1 to t
            out.leb(groups + runs.size)
            out.bytes(wasm, r.pos, list.first().address() - r.pos)
            for ((n, t) in runs) {
                out.leb(n)
                out.u8(t)
            }

            // 入口: 巻き直し中ならローカルを戻す
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x41); out.sleb(REWINDING.toLong()); out.u8(0x46)
            out.u8(0x04); out.u8(0x40)
            out.u8(0x10); out.leb(popFunc); out.u8(0xA7); out.u8(0x21); out.leb(kLocal)
            for (i in saved.reversed()) {
                val t = localTypes[i]
                if (isRef(t)) {
                    out.u8(0xD0); out.u8(t)
                } else {
                    out.u8(0x10); out.leb(popFunc)
                    when (t) {
                        I32 -> out.u8(0xA7)
                        F32 -> { out.u8(0xA7); out.u8(0xBE) }
                        F64 -> out.u8(0xBF)
                    }
                }
                out.u8(0x21); out.leb(i)
            }
            if (debug == AsyncifyDebug.POISON_UNSAVED) {
                // テスト用: 戻さないローカルにごみを入れる（生存の解析の漏れを、0 で初期化された値がたまたま合うことで見逃さないため）
                for (i in localTypes.indices) {
                    if (i == kLocal || isSaved[i]) continue
                    when (localTypes[i]) {
                        I32 -> { out.u8(0x41); out.sleb(POISON.toInt().toLong()) }
                        I64 -> { out.u8(0x42); out.sleb(POISON) }
                        F32 -> { out.u8(0x41); out.sleb(POISON.toInt().toLong()); out.u8(0xBE) }
                        F64 -> { out.u8(0x42); out.sleb(POISON); out.u8(0xBF) }
                        else -> continue
                    }
                    out.u8(0x21); out.leb(i)
                }
            }
            out.u8(0x0B)

            out.u8(0x02); out.u8(0x40) // $unwind
            out.u8(0x02); blockType(out, sig.results) // $body
            appendPatched(out, body)
            out.u8(0x0B)
            out.u8(0x0F) // return
            out.u8(0x0B)

            // 巻き戻し: ローカルと k を積んで、ダミーの値で戻る
            for (i in saved) {
                val t = localTypes[i]
                out.u8(0x20); out.leb(i)
                if (isRef(t)) {
                    out.u8(0xD1); out.u8(0x45); out.u8(0x04); out.u8(0x40); out.u8(0x00); out.u8(0x0B)
                    continue
                }
                when (t) {
                    I32 -> out.u8(0xAD)
                    F32 -> { out.u8(0xBC); out.u8(0xAD) }
                    F64 -> out.u8(0xBD)
                }
                out.u8(0x10); out.leb(pushFunc)
            }
            out.u8(0x20); out.leb(kLocal); out.u8(0xAD); out.u8(0x10); out.leb(pushFunc)
            dummies(out, sig.results)
            out.u8(0x0B)
            return siteCount to saved.size
        }

        /**
         * 元のローカル（引数と宣言したもの）のうち、どれかの待ち点（止まりうる呼び出し）の後で読まれうるもの（#338）。
         * 巻き直しの道（ランと呼び出しを飛ばして止まった呼び出しまで進む）は元のローカルを読まない（一時ローカルだけを読む）ので、
         * 戻す必要があるのは、止まった呼び出しから戻った後に元のプログラムが読むものだけ。
         *
         * 関数の本体の制御フロー（分岐・loop の後ろ向きの辺・`br_table`・`try_table` の catch 先への辺）で後ろ向きの生存解析をする。
         * catch 先への辺は、`try_table` の中の投げうる命令（呼び出しと `throw`・`throw_ref`）から、囲むすべての `try_table` の catch 先へ張る
         * （止まった呼び出しが、再開した後で投げうる）。囲むものすべてへ命令ごとに辺を張ると、確保が「命令の数 × 入れ子の深さ」になるので、
         * `try_table` ごとに「自分の catch 先と外側の節」を持つ節の頂点を 1 つ作り、命令からは内側の節 1 つへだけ辺を張る（頂点は命令の数に比例）。
         *
         * 解析が大きすぎる（頂点の数 × ローカルの数が [LIVENESS_LIMIT_WORDS] の 64 倍を超える）と `null`（すべてを退避する）。
         * 追えない分岐（GC の `br_on_*`）の命令があっても `null` にするが、それらは [emitSuspendBody] が先に断る（変換できない作りとして読み込みを断る）ので、
         * 実際にはここまで来ない。どちらに切り替えるかは入力だけで決まる（決定的。`Instrumentation.VERSION`）
         */
        private fun liveAcrossWaitPoints(): BooleanArray? {
            val n = list.size
            val nl = origLocals
            if (n == 0 || list[n - 1].opcode() != OpCode.END) return null
            val ops = Array(n) { list[it].opcode() }
            // 構造の開き → 対応する end。else → その if
            val match = IntArray(n) { -1 }
            val ifOfElse = IntArray(n) { -1 }
            val elseOfIf = IntArray(n) { -1 }
            run {
                val st = ArrayList<Int>()
                for (i in 0 until n) when (ops[i]) {
                    OpCode.BLOCK, OpCode.LOOP, OpCode.IF, OpCode.TRY_TABLE -> st += i
                    OpCode.ELSE -> {
                        ifOfElse[i] = st.last()
                        elseOfIf[st.last()] = i
                    }
                    OpCode.END -> if (st.isNotEmpty()) match[st.removeAt(st.size - 1)] = i
                    else -> Unit
                }
            }
            fun structural(op: OpCode) = op == OpCode.BLOCK || op == OpCode.LOOP || op == OpCode.IF || op == OpCode.TRY_TABLE ||
                op == OpCode.ELSE || op == OpCode.END

            fun mayThrow(op: OpCode) = op == OpCode.CALL || op == OpCode.CALL_INDIRECT || op == OpCode.CALL_REF ||
                op == OpCode.THROW || op == OpCode.THROW_REF

            val words = (nl + 63) ushr 6
            if (words == 0) return BooleanArray(0)

            // 基本ブロックに分け、それぞれの後続を求める。後続は、0 以上なら命令の番号（その命令のあるブロック）、負なら catch 先の節 `-(k + 1)`。
            // 投げうる命令から囲むすべての catch 先への辺は、命令ごとに配列を作らず、`try_table` ごとの節（自分の catch 先と、外側の節）を
            // 1 つ作って、そこを通して張る（確保する量を、命令の数に比例する大きさに抑える）
            val blockOf = IntArray(n)
            val starts = ArrayList<Int>()
            val succIns = ArrayList<IntArray>()
            val labels = ArrayList<Int>() // 開いている構造（内側が最後）
            val handlerStack = ArrayList<Int>() // 開いている構造ごとの、いちばん内側の囲む節（なければ -1）
            val handlerTargets = ArrayList<IntArray>() // 節ごとの、自分の catch 先（命令の番号）
            val handlerOuter = ArrayList<Int>() // 節ごとの、外側の節（なければ -1）
            var prevEnder = true
            val empty = IntArray(0)

            fun target(l: Long): Int {
                if (l >= labels.size) return n - 1
                val s = labels[labels.size - 1 - l.toInt()]
                return if (ops[s] == OpCode.LOOP) s else match[s]
            }
            for (i in 0 until n) {
                // 大きな関数でも、時間の上限（#40）が効くようにする
                if ((i and 0x3FF) == 0x3FF) checkpoint()
                val op = ops[i]
                if (prevEnder || structural(op)) {
                    if (starts.isNotEmpty() && succIns.size < starts.size) succIns += intArrayOf(i)
                    starts += i
                    // 上限を超えると決まったら、後続の配列などを作る前にやめる
                    if (starts.size.toLong() * words > LIVENESS_LIMIT_WORDS) return null
                }
                blockOf[i] = starts.size - 1
                val handler = if (handlerStack.isEmpty()) -1 else handlerStack.last()
                val end = endOf[list[i]]!!
                var succ: IntArray? = null
                when (op) {
                    OpCode.BLOCK, OpCode.LOOP, OpCode.TRY_TABLE, OpCode.IF -> {
                        var own = handler
                        if (op == OpCode.TRY_TABLE) {
                            val r = Reader(wasm, blockSig(list[i]).third, end)
                            val c = r.leb().toInt()
                            if (c > 0) {
                                val t = IntArray(c) {
                                    val kind = r.u8()
                                    if (kind == 0 || kind == 1) r.leb()
                                    target(r.leb())
                                }
                                handlerTargets += t
                                handlerOuter += handler
                                own = handlerTargets.size - 1
                            }
                        }
                        succ = if (op == OpCode.IF) {
                            intArrayOf(i + 1, if (elseOfIf[i] >= 0) elseOfIf[i] + 1 else match[i])
                        } else {
                            intArrayOf(i + 1)
                        }
                        labels += i
                        handlerStack += own
                    }
                    OpCode.ELSE -> succ = intArrayOf(match[ifOfElse[i]])
                    OpCode.END -> {
                        if (labels.isNotEmpty()) {
                            labels.removeAt(labels.size - 1)
                            handlerStack.removeAt(handlerStack.size - 1)
                        }
                        succ = if (i + 1 < n) intArrayOf(i + 1) else empty
                    }
                    OpCode.BR -> succ = intArrayOf(target(Reader(wasm, list[i].address() + 1, end).leb()))
                    OpCode.BR_IF -> succ = intArrayOf(i + 1, target(Reader(wasm, list[i].address() + 1, end).leb()))
                    OpCode.BR_TABLE -> {
                        val r = Reader(wasm, list[i].address() + 1, end)
                        val c = r.leb().toInt()
                        succ = IntArray(c + 1) { target(r.leb()) }
                    }
                    OpCode.RETURN, OpCode.UNREACHABLE, OpCode.RETURN_CALL, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF -> succ = empty
                    OpCode.THROW, OpCode.THROW_REF -> succ = if (handler >= 0) intArrayOf(-(handler + 1)) else empty
                    // 追えない分岐。ただし、これらは emitSuspendBody が先に断るので、ここには来ない（保険）
                    OpCode.BR_ON_NULL, OpCode.BR_ON_NON_NULL, OpCode.BR_ON_CAST, OpCode.BR_ON_CAST_FAIL -> return null
                    else -> if (handler >= 0 && mayThrow(op)) succ = intArrayOf(i + 1, -(handler + 1))
                }
                prevEnder = succ != null
                if (succ != null) succIns += succ
            }
            if (succIns.size < starts.size) succIns += empty
            val blocks = starts.size
            val nodes = blocks + handlerTargets.size
            if (nodes.toLong() * words > LIVENESS_LIMIT_WORDS) return null
            fun nodeOf(x: Int) = if (x >= 0) blockOf[x] else blocks - x - 1

            // 節の後続は、自分の catch 先と外側の節
            val succ = Array(nodes) { v ->
                val raw = if (v < blocks) {
                    succIns[v]
                } else {
                    val k = v - blocks
                    val outer = handlerOuter[k]
                    val t = handlerTargets[k]
                    if (outer >= 0) t + (-(outer + 1)) else t
                }
                distinctNodes(IntArray(raw.size) { nodeOf(raw[it]) })
            }
            fun blockEnd(b: Int) = if (b + 1 < blocks) starts[b + 1] else n

            // ブロックごとの gen（先に読む）と kill（先に書く）
            val gen = LongArray(nodes * words)
            val kill = LongArray(nodes * words)
            for (b in 0 until blocks) {
                val base = b * words
                for (i in blockEnd(b) - 1 downTo starts[b]) {
                    val x = localOperand(i, ops[i]) ?: continue
                    val w = base + (x ushr 6)
                    val bit = 1L shl (x and 63)
                    if (ops[i] == OpCode.LOCAL_GET) {
                        gen[w] = gen[w] or bit
                    } else {
                        gen[w] = gen[w] and bit.inv()
                        kill[w] = kill[w] or bit
                    }
                }
            }
            // 不動点まで（後ろのブロックから）
            val liveIn = LongArray(nodes * words)
            val tmp = LongArray(words)
            var changed = true
            while (changed) {
                checkpoint()
                changed = false
                for (b in nodes - 1 downTo 0) {
                    tmp.fill(0)
                    for (s in succ[b]) for (w in 0 until words) tmp[w] = tmp[w] or liveIn[s * words + w]
                    val base = b * words
                    for (w in 0 until words) {
                        val v = gen[base + w] or (tmp[w] and kill[base + w].inv())
                        if (v != liveIn[base + w]) {
                            liveIn[base + w] = v
                            changed = true
                        }
                    }
                }
            }
            // 待ち点の直後で生きているものの和
            val result = LongArray(words)
            for (b in 0 until blocks) {
                if ((starts[b] until blockEnd(b)).none { isWaitInstruction(ops[it], it) }) continue
                tmp.fill(0)
                for (s in succ[b]) for (w in 0 until words) tmp[w] = tmp[w] or liveIn[s * words + w]
                for (i in blockEnd(b) - 1 downTo starts[b]) {
                    if (isWaitInstruction(ops[i], i)) for (w in 0 until words) result[w] = result[w] or tmp[w]
                    val x = localOperand(i, ops[i]) ?: continue
                    val bit = 1L shl (x and 63)
                    val w = x ushr 6
                    tmp[w] = if (ops[i] == OpCode.LOCAL_GET) tmp[w] or bit else tmp[w] and bit.inv()
                }
            }
            return BooleanArray(nl) { result[it ushr 6] and (1L shl (it and 63)) != 0L }
        }

        /** [a] の重複を除く（順番は問わない。箱に入れずに、小さな配列のまま） */
        private fun distinctNodes(a: IntArray): IntArray {
            if (a.size < 2) return a
            a.sort()
            var m = 1
            for (j in 1 until a.size) if (a[j] != a[j - 1]) a[m++] = a[j]
            return if (m == a.size) a else a.copyOf(m)
        }

        /** `local.get`・`local.set`・`local.tee` の元のローカルの番号。それ以外は `null` */
        private fun localOperand(i: Int, op: OpCode): Int? = when (op) {
            OpCode.LOCAL_GET, OpCode.LOCAL_SET, OpCode.LOCAL_TEE -> list[i].operand(0).toInt().takeIf { it < origLocals }
            else -> null
        }

        /**
         * 止まりうる型の `call_ref` は、どこにあっても断る（#923）。到達できないところのものも、デバッグの切り替え（[AsyncifyDebug]）・
         * 引数やローカルの数にかかわらず断る（呼ばれる経路ごとに断るかどうかがばらつかないように、変換の最初に命令を全部見る）
         */
        private fun rejectSuspendingCallRef() {
            for (ins in list) {
                if (ins.opcode() == OpCode.CALL_REF && indirectMaySuspend(ins)) {
                    throw AsyncifyUnsupported("止まりうる call_ref（関数の参照の提案）には対応していない（参照は待ち点をまたいで退避できない）")
                }
            }
        }

        /** 待ち点になりうる呼び出し（[isSuspendCall]。末尾呼び出しは除く。到達できないところのものも含む） */
        private fun isWaitInstruction(op: OpCode, i: Int): Boolean =
            (op == OpCode.CALL || op == OpCode.CALL_INDIRECT || op == OpCode.CALL_REF) && isSuspendCall(list[i])

        private fun buildTree(from: Int): Pair<List<Node>, Int> {
            val nodes = ArrayList<Node>()
            var i = from
            while (true) {
                val ins = list[i]
                when (ins.opcode()) {
                    OpCode.END, OpCode.ELSE -> return nodes to i
                    OpCode.BLOCK, OpCode.LOOP, OpCode.IF, OpCode.TRY_TABLE -> {
                        val (body, stop) = buildTree(i + 1)
                        var els: List<Node>? = null
                        var j = stop
                        if (list[stop].opcode() == OpCode.ELSE) {
                            val (e, stop2) = buildTree(stop + 1)
                            els = e
                            j = stop2
                        }
                        nodes += Struct(ins, body, els)
                        i = j + 1
                    }
                    else -> {
                        nodes += Leaf(ins)
                        i++
                    }
                }
            }
        }

        private fun isYieldCall(ins: AnnotatedInstruction): Boolean = yieldFunc >= 0 && ins.opcode() == OpCode.CALL && ins.operand(0).toInt() == yieldFunc

        // --- 判定 ---

        private fun isSuspendCall(ins: AnnotatedInstruction): Boolean = when (ins.opcode()) {
            OpCode.CALL -> ins.operand(0).toInt() in suspendable || isYieldCall(ins)
            OpCode.CALL_INDIRECT -> indirectMaySuspend(ins)
            // 止まりうる型の call_ref は、変換の最初に断る（[rejectSuspendingCallRef]）ので、ここに届く call_ref は待ち点でない（#923）
            OpCode.CALL_REF -> false
            OpCode.RETURN_CALL -> if (ins.operand(0).toInt() in suspendable) throw AsyncifyUnsupported("止まりうる関数への末尾呼び出し（${ins.opcode()}。末尾呼び出しの提案）には対応していない") else false
            OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF ->
                if (indirectMaySuspend(ins)) throw AsyncifyUnsupported("止まりうる関数への末尾呼び出し（${ins.opcode()}。末尾呼び出しの提案）には対応していない") else false
            else -> false
        }

        /**
         * 巻き直しで、止まった呼び出しをやり直すときに引数が要るか。定義済みの関数への直接の `call` は、呼ばれた側が入口で
         * 自分の生きているローカルを戻す（引数のローカルも、要るものは退避している）ので要らない。燃料切れの呼び出し（[WasmAsync.FUEL]）は、
         * 巻き直しで着いたときに引数を見ない（`onFuelExhaustedUnwindable`）
         */
        private fun needsArgsOnRewind(ins: AnnotatedInstruction): Boolean = when {
            isYieldCall(ins) -> false
            ins.opcode() == OpCode.CALL -> ins.operand(0).toInt() < importedFuncs
            else -> true
        }

        private val suspendMemo = HashMap<Node, Boolean>()

        private fun hasSuspend(n: Node): Boolean = suspendMemo.getOrPut(n) {
            when (n) {
                is Leaf -> isSuspendCall(n.ins)
                is Struct -> n.body.any(::hasSuspend) || (n.els?.any(::hasSuspend) ?: false)
            }
        }

        // --- ブロックの型 ---

        /** (params, results, ブロック型のバイト列の終わり) */
        private fun blockSig(ins: AnnotatedInstruction): Triple<List<Int>, List<Int>, Int> {
            val r = Reader(wasm, ins.address() + 1, bodyEnd)
            val b = wasm[r.pos].toInt() and 0xFF
            return when {
                b == 0x40 -> {
                    r.pos++
                    Triple(emptyList(), emptyList(), r.pos)
                }
                b == 0x63 || b == 0x64 -> throw AsyncifyUnsupported("型付きの参照型（GC・関数参照の提案の (ref <型>)）には対応していない")
                b in 0x60..0x7F -> {
                    r.pos++
                    Triple(emptyList(), listOf(b), r.pos)
                }
                else -> {
                    val idx = r.sleb().toInt()
                    val s = typeSig(idx)
                    Triple(s.params, s.results, r.pos)
                }
            }
        }

        /** 引数のない、結果が [results] のブロック型。結果が2つ以上なら、足した型の番号で書く（multi-value。#425） */
        private fun blockType(out: Sink, results: List<Int>) {
            when (results.size) {
                0 -> out.u8(0x40)
                1 -> out.u8(results[0])
                else -> out.sleb(blockTypeIdx(emptyList(), results).toLong())
            }
        }

        private fun dummies(out: Sink, types: List<Int>) {
            for (t in types) when (t) {
                I32 -> { out.u8(0x41); out.u8(0) }
                I64 -> { out.u8(0x42); out.u8(0) }
                F32 -> { out.u8(0x43); repeat(4) { out.u8(0) } }
                F64 -> { out.u8(0x44); repeat(8) { out.u8(0) } }
                // ref.null 0x7B は正しくない。v128 を結果に持つものは、ローカル変数と同じく断る（ADR 0316。#851）
                V128 -> throw AsyncifyUnsupported("SIMD（v128）の値を結果に持つ関数・ブロックには対応していない")
                else -> { out.u8(0xD0); out.u8(t) }
            }
        }

        // --- 型の追跡 ---

        /** `call_ref`・`return_call_ref` が取り出した参照が [FUNCREF] なら断る（待ち点をまたいだ参照。#923） */
        private fun rejectFuncrefOperand(t: Int, op: String) {
            if (t == FUNCREF) throw AsyncifyUnsupported("関数の参照を持ったまま待ってから $op で呼ぶことには対応していない（待ち点をまたぐと参照の型が funcref になる）")
        }

        private fun effect(ins: AnnotatedInstruction, st: TypeStack) {
            val op = ins.opcode().opcode()
            when (op) {
                0x00, 0x0C, 0x0E, 0x0F, 0x12, 0x13 -> st.kill()
                0x15 -> {
                    rejectFuncrefOperand(st.pop(), "return_call_ref")
                    st.kill()
                }
                0x01 -> Unit
                0x08 -> {
                    st.pop(tagSigs[ins.operand(0).toInt()].params.size)
                    st.kill()
                }
                0x0A -> {
                    st.pop()
                    st.kill()
                }
                0x0D -> st.pop()
                0x10 -> call(st, funcSigs[ins.operand(0).toInt()])
                0x11 -> {
                    st.pop()
                    call(st, typeSig(ins.operand(0).toInt()))
                }
                0x14 -> {
                    // 型付きの参照は [REF_FUNC]（待ち点をまたいでいない ref.func）か [BOT]。funcref は、待ち点をまたいで
                    // funcref の一時ローカルに移した参照（正しいモジュールでは、ほかに call_ref に funcref は届かない）。出力が検証に通らないので断る（#923）
                    rejectFuncrefOperand(st.pop(), "call_ref")
                    call(st, typeSig(ins.operand(0).toInt()))
                }
                0x1A -> st.pop()
                0x1B -> {
                    st.pop()
                    val a = st.pop()
                    val b = st.pop()
                    st.push(if (a == BOT) b else a)
                }
                0x1C -> {
                    st.pop()
                    val a = st.pop()
                    val b = st.pop()
                    // 型付きの select の注釈は vec(valtype)（数は 1 なので 1 バイト）。(ref null <ヒープ型>)・(ref <ヒープ型>) は
                    // 先頭のバイト（0x63・0x64）の後にヒープ型の sleb が続く。ref.null（0xD0）と同じく、抽象ヒープ型の
                    // (ref null <抽象>) は略記のバイトにし、型の番号のものと null を許さないものは型を追えない参照（BOT）にする（#891）
                    val r = Reader(wasm, ins.address() + 2, endOf[ins]!!)
                    val t = r.u8()
                    val result = if (t == 0x63 || t == 0x64) {
                        val heapType = r.sleb()
                        if (t == 0x63 && heapType < 0) (heapType + 0x80).toInt() else BOT
                    } else {
                        t
                    }
                    // 待ち点をまたいで funcref の一時ローカルに移した参照は、結果が funcref でない select（型の番号の (ref null $t) など）に
                    // 渡すと検証に通らない出力になるので断る（#923。call_ref に届く前に型を失うので、ここで見つける）
                    if ((a == FUNCREF || b == FUNCREF) && result != FUNCREF) {
                        throw AsyncifyUnsupported("関数の参照を持ったまま待ってから型付きの select に渡すことには対応していない（待ち点をまたぐと参照の型が funcref になる）")
                    }
                    st.push(result)
                }
                0x20 -> st.push(localTypes[ins.operand(0).toInt()])
                0x21 -> st.pop()
                0x22 -> {
                    st.pop()
                    st.push(localTypes[ins.operand(0).toInt()])
                }
                0x23 -> st.push(globalTypes[ins.operand(0).toInt()])
                0x24 -> st.pop()
                0x25 -> {
                    st.pop()
                    st.push(tableTypes[ins.operand(0).toInt()])
                }
                0x26 -> st.pop(2)
                in 0x28..0x35 -> {
                    st.pop()
                    st.push(
                        when (op) {
                            0x28, in 0x2C..0x2F -> I32
                            0x2A -> F32
                            0x2B -> F64
                            else -> I64
                        },
                    )
                }
                in 0x36..0x3E -> st.pop(2)
                0x3F -> st.push(I32)
                0x40 -> {
                    st.pop()
                    st.push(I32)
                }
                0x41 -> st.push(I32)
                0x42 -> st.push(I64)
                0x43 -> st.push(F32)
                0x44 -> st.push(F64)
                0x45, 0x50 -> un(st, I32)
                in 0x46..0x4F, in 0x51..0x66 -> bin(st, I32)
                in 0x67..0x69 -> un(st, I32)
                in 0x6A..0x78 -> bin(st, I32)
                in 0x79..0x7B -> un(st, I64)
                in 0x7C..0x8A -> bin(st, I64)
                in 0x8B..0x91 -> un(st, F32)
                in 0x92..0x98 -> bin(st, F32)
                in 0x99..0x9F -> un(st, F64)
                in 0xA0..0xA6 -> bin(st, F64)
                in 0xA7..0xAB, 0xBC -> un(st, I32)
                in 0xAC..0xB1, 0xBD -> un(st, I64)
                in 0xB2..0xB6, 0xBE -> un(st, F32)
                in 0xB7..0xBB, 0xBF -> un(st, F64)
                0xC0, 0xC1 -> un(st, I32)
                in 0xC2..0xC4 -> un(st, I64)
                0xD0 -> {
                    // ref.null のヒープ型は sleb。抽象ヒープ型（func・struct・any など）は 1 バイトの負の数で、略記のバイトと同じ番号になる。
                    // 型の番号（0 以上。`ref.null $s`）は型を追えない参照（BOT）にして、値が待ち点をまたぐとき（一時ローカルに移すとき）に断る（#891）
                    val heapType = Reader(wasm, ins.address() + 1, endOf[ins]!!).sleb()
                    st.push(if (heapType >= 0) BOT else (heapType + 0x80).toInt())
                }
                0xD1 -> un(st, I32)
                0xD2 -> st.push(REF_FUNC)
                0xD3 -> bin(st, I32)
                0xD4 -> {
                    val t = st.pop()
                    st.push(t)
                }
                in 0xFC00..0xFC03 -> un(st, I32)
                in 0xFC04..0xFC07 -> un(st, I64)
                0xFC08, 0xFC0A, 0xFC0B, 0xFC0C, 0xFC0E, 0xFC11 -> st.pop(3)
                0xFC09, 0xFC0D -> Unit
                0xFC0F -> {
                    st.pop(2)
                    st.push(I32)
                }
                0xFC10 -> st.push(I32)
                else -> throw AsyncifyUnsupported("命令 ${ins.opcode()}（SIMD・アトミック・GC などの拡張）には対応していない")
            }
        }

        private fun call(st: TypeStack, s: Sig) {
            st.pop(s.params.size)
            s.results.forEach(st::push)
        }

        private fun un(st: TypeStack, r: Int) {
            st.pop()
            st.push(r)
        }

        private fun bin(st: TypeStack, r: Int) {
            st.pop(2)
            st.push(r)
        }

        private fun structEffect(n: Struct, st: TypeStack) {
            val (params, results, _) = blockSig(n.open)
            if (n.open.opcode() == OpCode.IF) st.pop()
            st.pop(params.size)
            results.forEach(st::push)
        }

        // --- 出力 ---

        /**
         * 待ち点を含むブロックの中身。入るとき、スタックにはブロックの引数 [params] だけがある。[m] は $body の中で開いている待ち点の構造の数。
         *
         * 引数は入口で専用の一時ローカルに移す（#425）。巻き直しの間も移す。止まった呼び出しを含む構造なら、ブロックの直前に、
         * 戻したローカルから「外から最後に入ったときの引数」を積んでいる（loop の2回目以降で止まったなら、今の繰り返しの値ではない）。
         * 止まった呼び出しより前の構造なら、移した値は使わない。loop の先頭への分岐で積まれた引数も、同じ入口で移す。
         *
         * 専用の引数ローカルを読むのは、最初の待ち点より前のラン（`emitRun` の prefix。NORMAL のときだけ動く）と、待ち点のない
         * 最後のランだけ（#447 の不変条件。クラスの KDoc を参照）。最初の待ち点の後の `pending` は退避する一時ローカルと結果の
         * 専用ローカルに替わるので、巻き直しで入口に書いた古い値は読まれない。中身が空の最後のラン（`!hasCode`）は包みのない
         * `local.get` で `pending`（専用の引数ローカル・共有の一時ローカル・結果の専用ローカル）を積み、REWINDING のままでも読まれて
         * 外側の構造の結果の専用ローカルへ送られる。古い値でも害がないのは、その連鎖の行き先が NORMAL のときだけ動くランの prefix か、
         * `$body` の結果（NORMAL でしか届かない）だけだから
         */
        private fun emitSuspendBody(out: Sink, nodes: List<Node>, results: List<Int>, m: Int, params: List<Int> = emptyList()) {
            // 専用のローカルに（巻き直しで、止まった呼び出しより前の構造を通るときにも書かれるので、ほかと共有しない）。
            // 巻き直しでは古い値で上書きする。読む場所と、古い値でも害がない理由は KDoc とクラスの KDoc を参照（#447）
            var pending: List<Int> = params.map { newLocal(it).also(dedicated::add) }
            for (p in pending.reversed()) {
                out.u8(0x21); out.leb(p)
            }
            var st = TypeStack(params)
            val run = ArrayList<Node>()
            for (node in nodes) {
                if (st.unreachable || !hasSuspend(node)) {
                    run += node
                    when (node) {
                        is Leaf -> effect(node.ins, st)
                        is Struct -> structEffect(node, st)
                    }
                    continue
                }
                val consumed: Int
                val callSig: Sig?
                when (node) {
                    is Leaf -> {
                        callSig = when (node.ins.opcode()) {
                            OpCode.CALL -> funcSigs[node.ins.operand(0).toInt()]
                            else -> typeSig(node.ins.operand(0).toInt())
                        }
                        consumed = callSig.params.size + if (node.ins.opcode() == OpCode.CALL) 0 else 1
                    }
                    is Struct -> {
                        callSig = null
                        // 引数を持つブロック（multi-value）は、引数も一時ローカルに移し、ブロックの直前に積み直す（#425）
                        val (params, _, _) = blockSig(node.open)
                        consumed = params.size + if (node.open.opcode() == OpCode.IF) 1 else 0
                    }
                }
                // 前のランを閉じる: スタックをすべて一時ローカルに移す
                val spill = st.types.map(::alloc)
                pending.forEach(::release)
                emitRun(out, pending, run, spill)
                run.clear()
                val keep = spill.subList(0, spill.size - consumed)
                val args = spill.subList(spill.size - consumed, spill.size)
                // 巻き直しで読む一時ローカル（[savedTemps]）
                savedTemps += keep
                if (node is Struct || needsArgsOnRewind((node as Leaf).ins)) savedTemps += args
                val resultTemps: List<Int>
                when (node) {
                    is Leaf -> {
                        resultTemps = emitCallSite(out, node.ins, args, callSig!!.results, m)
                    }
                    is Struct -> {
                        for (a in args) {
                            out.u8(0x20); out.leb(a)
                        }
                        val (params, res, btEnd) = blockSig(node.open)
                        // 見出し（try_table の catch のラベルは外側から数えるので、そのままでよい）
                        val headerEnd = if (node.open.opcode() == OpCode.TRY_TABLE) endOf[node.open]!! else btEnd
                        out.bytes(wasm, node.open.address(), headerEnd - node.open.address())
                        emitSuspendBody(out, node.body, res, m + 1, params)
                        if (node.els != null) {
                            out.u8(0x05)
                            emitSuspendBody(out, node.els, res, m + 1, params)
                        }
                        out.u8(0x0B)
                        args.forEach(::release)
                        // 結果は専用のローカルに（巻き直し中にも書かれるので、ほかと共有しない）
                        resultTemps = res.map { newLocal(it).also(dedicated::add) }
                        for (t in resultTemps.reversed()) {
                            out.u8(0x21); out.leb(t)
                        }
                    }
                }
                pending = keep + resultTemps
                st = TypeStack(pending.map { localTypes[it] })
            }
            // 最後のラン
            val hasCode = run.isNotEmpty()
            if (!hasCode) {
                for (p in pending) {
                    out.u8(0x20); out.leb(p)
                }
            } else {
                out.u8(0x23); out.leb(stateGlobal); out.u8(0x45)
                out.u8(0x04); blockType(out, results)
                for (p in pending) {
                    out.u8(0x20); out.leb(p)
                }
                for (n in run) emitPlain(out, n, 0)
                out.u8(0x05)
                dummies(out, results)
                out.u8(0x0B)
            }
            pending.forEach(::release)
        }

        /** ランを `if (state == NORMAL)` で包んで出す。[prefix] を積んでから始め、最後にスタックを [suffix] に移す */
        private fun emitRun(out: Sink, prefix: List<Int>, nodes: List<Node>, suffix: List<Int>) {
            if (prefix.isEmpty() && nodes.isEmpty() && suffix.isEmpty()) return
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x45)
            out.u8(0x04); out.u8(0x40)
            for (p in prefix) {
                out.u8(0x20); out.leb(p)
            }
            for (n in nodes) emitPlain(out, n, 0)
            for (s in suffix.reversed()) {
                out.u8(0x21); out.leb(s)
            }
            out.u8(0x0B)
        }

        private fun emitCallSite(out: Sink, ins: AnnotatedInstruction, args: List<Int>, results: List<Int>, m: Int): List<Int> {
            val k = siteCount++
            // if (state == NORMAL || k == K)
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x45)
            out.u8(0x20); out.leb(kLocal); out.u8(0x41); out.sleb(k.toLong()); out.u8(0x46)
            out.u8(0x72)
            out.u8(0x04); out.u8(0x40)
            for (a in args) {
                out.u8(0x20); out.leb(a)
            }
            if (isYieldCall(ins)) {
                // 止まりうる関数の中の燃料切れ: 巻き戻せる燃料切れの import を呼ぶ（#339）
                out.u8(0x10); out.leb(asyncFuelFunc)
                yieldSites++
            } else if (ins.opcode() == OpCode.CALL) {
                out.u8(0x10); out.leb(remap(ins.operand(0)))
            } else {
                out.bytes(wasm, ins.address(), endOf[ins]!! - ins.address())
            }
            args.forEach(::release)
            // 巻き戻し中なら k = K として $unwind へ
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x41); out.sleb(UNWINDING.toLong()); out.u8(0x46)
            out.u8(0x04); out.u8(0x40)
            out.u8(0x41); out.sleb(k.toLong()); out.u8(0x21); out.leb(kLocal)
            out.u8(0x0C); out.leb(m + 3)
            out.u8(0x0B)
            val temps = results.map(::alloc)
            for (t in temps.reversed()) {
                out.u8(0x21); out.leb(t)
            }
            out.u8(0x0B)
            return temps
        }

        /** 変えない命令を写す。[inner] はラン（`if` で包んだ）の中で開いている構造の数。それより外への分岐は 1 深くする */
        private fun emitPlain(out: Sink, node: Node, inner: Int) {
            fun label(l: Long) = if (l >= inner) l + 1 else l
            when (node) {
                is Leaf -> {
                    val ins = node.ins
                    val end = endOf[ins]!!
                    // 燃料の課金: 後で定数を差し替える（#389。課金はいつもランの中にあり、ここから出る）
                    if (ins.address() in chargeAt) holes += longArrayOf(out.size().toLong(), (end - ins.address()).toLong(), ins.operand(0))
                    when (ins.opcode()) {
                        OpCode.BR, OpCode.BR_IF -> {
                            out.u8(ins.opcode().opcode())
                            out.leb(label(Reader(wasm, ins.address() + 1, end).leb()))
                        }
                        OpCode.BR_TABLE -> {
                            val r = Reader(wasm, ins.address() + 1, end)
                            val n = r.leb()
                            out.u8(0x0E)
                            out.leb(n)
                            repeat(n.toInt() + 1) { out.leb(label(r.leb())) }
                        }
                        OpCode.CALL, OpCode.RETURN_CALL, OpCode.REF_FUNC -> {
                            out.u8(ins.opcode().opcode())
                            out.leb(remap(ins.operand(0)))
                        }
                        OpCode.BR_ON_NULL, OpCode.BR_ON_NON_NULL, OpCode.BR_ON_CAST, OpCode.BR_ON_CAST_FAIL ->
                            throw AsyncifyUnsupported("命令 ${ins.opcode()}（GC の提案）には対応していない")
                        else -> out.bytes(wasm, ins.address(), end - ins.address())
                    }
                }
                is Struct -> {
                    val (_, _, btEnd) = blockSig(node.open)
                    out.bytes(wasm, node.open.address(), btEnd - node.open.address())
                    if (node.open.opcode() == OpCode.TRY_TABLE) {
                        val r = Reader(wasm, btEnd, endOf[node.open]!!)
                        val n = r.leb()
                        out.leb(n)
                        repeat(n.toInt()) {
                            val kind = r.u8()
                            out.u8(kind)
                            if (kind == 0 || kind == 1) out.leb(r.leb())
                            out.leb(label(r.leb()))
                        }
                    }
                    for (c in node.body) emitPlain(out, c, inner + 1)
                    if (node.els != null) {
                        out.u8(0x05)
                        for (c in node.els) emitPlain(out, c, inner + 1)
                    }
                    out.u8(0x0B)
                }
            }
        }
    }
}

/** バイト列の書き出し（core の `FuelInjector` と同じもの。core のものは internal なので写した。テストが [Reader] に渡すので internal） */
internal class Sink : ByteArrayOutputStream() {
    fun u8(b: Int) = write(b and 0xFF)

    fun bytes(src: ByteArray, off: Int, len: Int) = write(src, off, len)

    fun append(other: Sink) = other.writeTo(this)

    fun leb(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                u8(b)
                return
            }
            u8(b or 0x80)
        }
    }

    fun leb(value: Int) = leb(value.toLong())

    fun sleb(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v shr 7
            val done = (v == 0L && b and 0x40 == 0) || (v == -1L && b and 0x40 != 0)
            if (done) {
                u8(b)
                return
            }
            u8(b or 0x80)
        }
    }

    fun name(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        leb(b.size.toLong())
        write(b)
    }
}

/** バイト列の読み込み（定数式の写しをテストが直接呼ぶので internal。#851） */
internal class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
    fun u8(): Int {
        if (pos >= end) throw AsyncifyUnsupported("セクションの終わりを超えて読んだ")
        return buf[pos++].toInt() and 0xFF
    }

    fun leb(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun sleb(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) {
                if (shift < 64 && b and 0x40 != 0) result = result or (-1L shl shift)
                return result
            }
        }
    }

    fun skipLeb() {
        while (u8() and 0x80 != 0) Unit
    }

    fun name(): String {
        val len = leb().toInt()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun copyValType(out: Sink) {
        val start = pos
        val t = u8()
        if (t == 0x63 || t == 0x64) skipLeb()
        out.bytes(buf, start, pos - start)
    }

    /** 定数式を写す。`ref.func` の番号だけ付け替える */
    fun copyConstExpr(out: Sink, remap: (Long) -> Long) {
        while (true) {
            val start = pos
            when (val op = u8()) {
                0x0B -> {
                    out.u8(op)
                    return
                }
                0x41, 0x42, 0x23 -> {
                    skipLeb()
                    out.bytes(buf, start, pos - start)
                }
                0x43 -> {
                    pos += 4
                    out.bytes(buf, start, pos - start)
                }
                0x44 -> {
                    pos += 8
                    out.bytes(buf, start, pos - start)
                }
                0xD0 -> {
                    skipLeb()
                    out.bytes(buf, start, pos - start)
                }
                0xD2 -> {
                    out.u8(op)
                    out.leb(remap(leb()))
                }
                0x6A, 0x6B, 0x6C, 0x7C, 0x7D, 0x7E -> out.u8(op)
                // 受け付ける命令は `FuelInjector` の定数式と同じ（#851）。以下の「定数式に使えない」で断る分岐は、変換できない作りではなく、
                // WASM として正しくないものとして断る（原因なしの WasmLoadException）。`prepare` は最初に Endive の `Parser.parse`（検証あり）を通すが、
                // Endive 1.2.0 の検証はパッシブ・宣言の要素セグメント（flag 5・7）の初期値を確かめないので、その形ではここに届く
                // （型の合わないものは、変換の後の読み込みで EndiveRuntime.parseModule が断る。#893）
                0xFD -> { // v128.const
                    val sub = leb()
                    if (sub != 12L) throw WasmLoadException("定数式の命令 0xFD %d は定数式に使えない".format(sub))
                    if (end - pos < 16) throw WasmLoadException("セクションの終わりを超えて読んだ")
                    pos += 16
                    out.bytes(buf, start, pos - start)
                }
                0xFB -> { // GC の提案の定数式の命令
                    when (val sub = leb()) {
                        0L, 1L, 6L, 7L -> skipLeb() // struct.new・struct.new_default・array.new・array.new_default（型の番号）
                        8L -> { // array.new_fixed（型の番号と数）
                            skipLeb()
                            skipLeb()
                        }
                        0x1AL, 0x1BL, 0x1CL -> Unit // any.convert_extern・extern.convert_any・ref.i31
                        else -> throw WasmLoadException("定数式の命令 0xFB %d は定数式に使えない".format(sub))
                    }
                    out.bytes(buf, start, pos - start)
                }
                else -> throw WasmLoadException("定数式の命令 0x%02X は定数式に使えない".format(op))
            }
        }
    }
}
