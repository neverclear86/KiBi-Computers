package pro.ikili.kibicomputers.core.runtime.component.canon

import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind

/** canon の `string-encoding`（[code] はバイナリの値。option がなければ [UTF8]） */
internal enum class CanonStringEncoding(val code: Int) {
    UTF8(0),
    UTF16(1),

    /** `latin1+utf16`（長さの最上位ビット [CanonLimits.UTF16_TAG] が立っていれば UTF-16、なければ latin1） */
    LATIN1_UTF16(2),
    ;

    companion object {
        fun of(code: Int): CanonStringEncoding? = entries.firstOrNull { it.code == code }
    }
}

/**
 * ゲストの `realloc`（canon の `realloc` option。`(old_ptr, old_size, align, new_size) -> ptr`）。値はどれも u32 のビット列。
 *
 * ゲストの呼び出しなので、呼ぶ側（#263 のリンカー）が `ctx.callGuest { }` の中で呼び、燃料はゲストの分として数える
 * （ADR 0073 の「cabi_realloc の呼び出し」）。realloc が別の core インスタンスにあるなら燃料の holder を切り替え
 * （`ctx.switchFuelHolder`。#270）、呼んでいる間はそのインスタンスの `may_leave` を下ろす（ADR 0264）。
 * 返した場所の align と範囲は [CanonContext] が確かめる
 */
internal fun interface CanonRealloc {
    fun realloc(oldPtr: Int, oldSize: Int, align: Int, newSize: Int): Int
}

/**
 * resource のハンドルの lift・lower。lift はゲストのハンドル（u32 のビット列）を受けて rep を返し、lower は rep を受けて
 * ゲストのハンドルを返す（borrow を、その resource を実装したインスタンスに渡すときは rep のまま。仕様の `pass_rep_not_handle`）。
 * 誤り（表にない、型が違う、貸している own を渡した）は [WasmTrapException] にする。
 *
 * 実装は、リンカー（`LinkedComponent`）がコンポーネントのインスタンスごとに持つ（ハンドル表と borrow の範囲。#265・ADR 0265）
 */
internal interface CanonResources {
    @Throws(WasmTrapException::class)
    fun liftOwn(type: CanonType.Own, handle: Int): Int

    @Throws(WasmTrapException::class)
    fun liftBorrow(type: CanonType.Borrow, handle: Int): Int

    @Throws(WasmTrapException::class)
    fun lowerOwn(type: CanonType.Own, value: Int): Int

    @Throws(WasmTrapException::class)
    fun lowerBorrow(type: CanonType.Borrow, value: Int): Int

    companion object {
        /** resource を扱えない文脈（どれもトラップ。単体のテストなど、リンカーを通さないとき） */
        val UNSUPPORTED: CanonResources = object : CanonResources {
            override fun liftOwn(type: CanonType.Own, handle: Int): Int = unsupported()
            override fun liftBorrow(type: CanonType.Borrow, handle: Int): Int = unsupported()
            override fun lowerOwn(type: CanonType.Own, value: Int): Int = unsupported()
            override fun lowerBorrow(type: CanonType.Borrow, value: Int): Int = unsupported()
            private fun unsupported(): Nothing = CanonTraps.trap("この文脈では resource のハンドルを扱えない")
        }
    }
}

/** Canonical ABI の上限（仕様の値と、仮の値。仮の値の確定は #251） */
internal object CanonLimits {
    /** 1 つの `string` のバイト数の上限（仕様の `MAX_STRING_BYTE_LENGTH`） */
    const val MAX_STRING_BYTE_LENGTH: Long = (1L shl 28) - 1

    /** 1 つの `list` のバイト数の上限（仕様の `MAX_LIST_BYTE_LENGTH`） */
    const val MAX_LIST_BYTE_LENGTH: Long = (1L shl 28) - 1

    /** `latin1+utf16` の UTF-16 の印（wasm32 の `utf16_tag`） */
    const val UTF16_TAG: Long = 1L shl 31

    /** `realloc` に渡せる大きさの上限（u32） */
    const val REALLOC_I32_MAX: Long = 0xffff_ffffL

    /** 値の型の入れ子の深さの上限（仮の値）。lift・lower の再帰の深さがこれで決まる */
    const val MAX_TYPE_DEPTH: Int = 100

    /** 1 回の呼び出しで写すバイト数の上限の既定（仮の値） */
    const val DEFAULT_MAX_BYTES: Long = 16L shl 20

    /** 1 回の呼び出しで作る・写す値の数の上限の既定（仮の値） */
    const val DEFAULT_MAX_VALUES: Long = 1_000_000

    /** ハンドル表 1 つの番号の上限（仕様の `Table.MAX_LENGTH`） */
    const val MAX_TABLE_LENGTH: Int = (1 shl 28) - 1

    /**
     * 1 つのプロセス（コンポーネントの全インスタンスの合計）で同時に持てるハンドルの数の上限（仮の値。確定は #251）。
     * 仕様の上限（[MAX_TABLE_LENGTH]）より早くトラップにする（ADR 0265）
     */
    const val MAX_HANDLES: Int = 4_096
}

/**
 * 1 回の呼び出し（lift と lower の両方）で写す量の予算（ADR 0264「予算」）。仕様の検査は 1 つの list・string が
 * 線形メモリに収まるかだけなので、要素がみな同じ大きな領域を指すと、小さな線形メモリでホストに大きな確保をさせられる。
 * それを、写したバイト数（list・string の中身）と値の数（値 1 つごと）の合計で抑え、超えたらトラップにする。
 * 呼び出しごとに作る（スレッドをまたいで共有しない）。使った量（[usedBytes]・[usedValues]）は、リンカーが燃料に換算してよい
 */
internal class CanonBudget(
    val maxBytes: Long = CanonLimits.DEFAULT_MAX_BYTES,
    val maxValues: Long = CanonLimits.DEFAULT_MAX_VALUES,
) {
    var usedBytes: Long = 0
        private set
    var usedValues: Long = 0
        private set

    /** [bytes] バイトと [values] 個の値を使う。超えるならトラップ（使った量は変えない） */
    @Throws(WasmTrapException::class)
    fun take(bytes: Long, values: Long) {
        if (bytes > maxBytes - usedBytes) CanonTraps.trap("1 回の呼び出しで写すバイト数が上限（$maxBytes）を超えた")
        if (values > maxValues - usedValues) CanonTraps.trap("1 回の呼び出しで写す値の数が上限（$maxValues）を超えた")
        usedBytes += bytes
        usedValues += values
    }

    /** 値を 1 つ使う */
    @Throws(WasmTrapException::class)
    fun value() {
        if (usedValues >= maxValues) CanonTraps.trap("1 回の呼び出しで写す値の数が上限（$maxValues）を超えた")
        usedValues++
    }
}

/**
 * 1 回の lift・lower の文脈（Canonical ABI の `LiftLowerContext` の、ABI に要る部分）。呼び出しのたびに作る。
 *
 * [memory] は canon の `memory` option の線形メモリ（ない関数なら `null`。要るのにないならトラップ。検証で通らない形）。
 * 大きさはその都度読む（[realloc] の中で `memory.grow` が起きうる）
 */
internal class CanonContext(
    private val memory: WasmMemory?,
    val encoding: CanonStringEncoding = CanonStringEncoding.UTF8,
    private val realloc: CanonRealloc? = null,
    val resources: CanonResources = CanonResources.UNSUPPORTED,
    val budget: CanonBudget = CanonBudget(),
) {
    /** 線形メモリ */
    val mem: WasmMemory get() = memory ?: CanonTraps.trap("線形メモリがない（canon の memory option）")

    /** 場所を取る（`realloc(0, 0, align, size)`。大きさ 0 でも呼ぶ） */
    @Throws(WasmTrapException::class)
    fun allocate(align: Int, size: Long): Long = reallocate(0, 0, align, size)

    /** 場所を取り直す。返した場所が [align] に揃っていない、または線形メモリの外ならトラップ */
    @Throws(WasmTrapException::class)
    fun reallocate(old: Long, oldSize: Long, align: Int, newSize: Long): Long {
        if (newSize > CanonLimits.REALLOC_I32_MAX || oldSize > CanonLimits.REALLOC_I32_MAX) {
            CanonTraps.trap("確保する大きさが u32 を超える（$newSize）")
        }
        val r = realloc ?: CanonTraps.trap("realloc がない（canon の realloc option）")
        val p = u32(r.realloc(old.toInt(), oldSize.toInt(), align, newSize.toInt()))
        if (p % align != 0L) CanonTraps.trap("realloc が align $align に揃っていない場所を返した（$p）")
        if (p + newSize > mem.byteSize) CanonTraps.outOfBounds("realloc が線形メモリの外を返した（$p + $newSize）")
        return p
    }

    /** [ptr] が [align] に揃っているか（違えばトラップ） */
    @Throws(WasmTrapException::class)
    fun checkAligned(ptr: Long, align: Int, what: String) {
        if (ptr % align != 0L) CanonTraps.trap("$what の場所が align $align に揃っていない（$ptr）")
    }

    /** [ptr] から [length] バイトが線形メモリの中か（外ならトラップ） */
    @Throws(WasmTrapException::class)
    fun checkInMemory(ptr: Long, length: Long, what: String) {
        if (ptr + length > mem.byteSize) CanonTraps.outOfBounds("$what の範囲が線形メモリの外（$ptr + $length）")
    }

    companion object {
        fun u32(v: Int): Long = v.toLong() and 0xffff_ffffL
    }
}

/** lift・lower のトラップ（ゲストの入力の誤りは、どれも [WasmTrapException] にする。ADR 0074） */
internal object CanonTraps {
    fun trap(message: String): Nothing = throw WasmTrapException(WasmTrapKind.OTHER, message)

    fun outOfBounds(message: String): Nothing = throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, message)
}
