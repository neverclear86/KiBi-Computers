package pro.ikili.kibicomputers.core.runtime

import pro.ikili.kibicomputers.core.runtime.asyncify.Asyncify
import pro.ikili.kibicomputers.core.runtime.asyncify.AsyncifyUnsupported
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector

/**
 * 読み込むときにかける書き換え（燃料の計測コードの挿入 #38 と、待機点への変換 #329）の順番を1か所で決める。
 *
 * 順番は「燃料の計測コードの挿入 → 待機点への変換」（#341）。逆にすると、変換で足した細かい `if` の分だけ燃料のセグメントが増え、
 * lua.wasm の `luaV_execute` が JVM のメソッドの上限（64 KB）を超えて、ランタイムコンパイラでコンパイルできなかった
 * （WASM の命令の数が約 29,100 → この順で約 21,400。生成したメソッドは約 50 KB）。この順には、次の性質もある:
 * - 燃料を引くコードは、変換で包んだ `if (state == NORMAL)` の中に入るので、巻き直しの間は引かない（同じ命令を二度数えない）。
 *   変換が足したコード（退避・復元・状態の確かめ）は燃料を数えない。数えるのは元のプログラムの命令だけ
 * - 燃料の残りを写したローカル変数も、ほかのローカルと同じく退避・復元される。巻き直しで戻る値は古いが、使う前に
 *   （呼び出しから戻った直後に）グローバルから読み直すので、古い値で数えることはない
 * - 燃料切れの import（`kibi.fuel`）は、変換のときには普通の import として見える。止まりうる関数の中のものは待ち点にする（#339。下記）
 *
 * 燃料切れの待ち点化（#339。ADR 0329 の「燃料切れの待ち点化」）: 変換は、次の2段で試す。
 * 1. 止まりうる関数の中の `kibi.fuel` も待ち点にする（`kibi.async_fuel` に書き換える）
 * 2. 1 を変換できない作りで断られたら（燃料の確かめで loop が待ち点になり、引数を持つブロックなどに当たったとき）、
 *    同じ挿入の結果を、燃料切れを待ち点にせずに変換する（入力待ちだけが待ち点。#329 と同じ）
 * 2 も断られたら、2 の理由で断る
 */
internal object Instrumentation {
    /**
     * [applyDetailed] の結果。
     *
     * @property bytes 書き換えた後の WASM
     * @property fuelYield 燃料切れも待ち点にした（段1）
     * @property fuelYieldRejection 段1を断られて段2で変換したときの、段1を断った理由。それ以外は `null`
     */
    class Applied(val bytes: ByteArray, val fuelYield: Boolean, val fuelYieldRejection: String?)

    /**
     * [wasm] に燃料の計測コードを挿入し、[waitImports] が空でなければ待機点への変換をかける。重いので、メインスレッドでは呼ばない。
     *
     * [checkpoint] は書き換えの途中で呼ぶ（検証の時間の上限。#40）。引数はどの段階か（エラーメッセージに使う）
     *
     * @throws AsyncifyUnsupported 変換できない作り（段2で断られた理由）
     * @throws WasmLoadException WASM として読めない、予約した名前を使っている、時間の上限を超えた
     */
    @Throws(WasmLoadException::class)
    fun applyDetailed(wasm: ByteArray, waitImports: Set<String>, checkpoint: (stage: String) -> Unit = {}): Applied {
        val metered = FuelInjector.inject(wasm) { checkpoint(STAGE_FUEL) }
        if (waitImports.isEmpty()) return Applied(metered.bytes, false, null)
        return try {
            val r = Asyncify.transform(metered.bytes, waitImports, YIELD_IMPORT, metered::originalFunc) { checkpoint(STAGE_ASYNCIFY) }
            Applied(r.bytes, true, null)
        } catch (e: AsyncifyUnsupported) {
            val r = Asyncify.transform(metered.bytes, waitImports, null, metered::originalFunc) { checkpoint(STAGE_ASYNCIFY) }
            Applied(r.bytes, false, e.message ?: "理由不明")
        }
    }

    /** [applyDetailed] の書き換えた後の WASM だけを返す */
    @Throws(WasmLoadException::class)
    fun apply(wasm: ByteArray, waitImports: Set<String>, checkpoint: (stage: String) -> Unit = {}): ByteArray =
        applyDetailed(wasm, waitImports, checkpoint).bytes

    /** 段1で待ち点にする燃料切れの import */
    private const val YIELD_IMPORT: String = "${WasmFuel.IMPORT_MODULE}.${WasmFuel.IMPORT_NAME}"

    /** [apply] の [checkpoint] に渡す段階の名前: 燃料の計測コードの挿入 */
    const val STAGE_FUEL: String = "燃料の計測コードの挿入"

    /** 同じく、待機点への変換 */
    const val STAGE_ASYNCIFY: String = "待機点への変換"
}
