package pro.ikili.kibicomputers.core.runtime

/**
 * WASM のランタイム（#34）。カーネルは特定のランタイムではなく、この抽象を通して WASM を動かす。
 *
 * 実装は Endive（[pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime]）と、テスト用のダミーがある。
 * 将来ネイティブのランタイムを足すときも、この形に合わせる。
 *
 * スレッド: [load] とモジュールの [WasmModule.instantiate] は、どのスレッドから呼んでもよい。
 * 1つの [WasmInstance] は、同時に1つのスレッドからだけ使う。
 */
public interface WasmRuntime {
    /** ログに出す名前（例: `endive`） */
    public val name: String

    /**
     * WASM のバイト列を読み込む（パースと検証。実装によってはコンパイルも）。
     *
     * 重い処理なので、メインスレッドでは呼ばない（#29）。
     */
    @Throws(WasmLoadException::class)
    public fun load(bytes: ByteArray): WasmModule
}

/** 読み込んだモジュール。何度でもインスタンスを作れる。 */
public interface WasmModule {
    /** モジュールが求める import（宣言の順） */
    public val imports: List<WasmImportDescriptor>

    /** モジュールが出す export（宣言の順） */
    public val exports: List<WasmExportDescriptor>

    /**
     * 燃料を計測するモジュールなら `true`（#28）。
     *
     * 計測のコードを挿入したモジュールは、[WasmFuel.IMPORT_MODULE].[WasmFuel.IMPORT_NAME] を import し、
     * [WasmFuel.EXPORT_GLOBAL] を export する。この2つはインスタンスを作るときにランタイムがつなぐので、
     * [imports] と [exports] には出さない。
     */
    public val isFuelMetered: Boolean

    /**
     * 待機点への変換（asyncify。#329）をかけたモジュールなら `true`。
     *
     * 変換したモジュールは、[WasmAsync.IMPORT_MODULE] の [WasmAsync.PUSH]・[WasmAsync.POP] を import し、
     * [WasmAsync.STATE_EXPORT] を export する。この3つはインスタンスを作るときにランタイムがつなぐので、
     * [imports] と [exports] には出さない。インスタンスを作るときは [WasmInstanceConfig.asyncStack] が要る。
     */
    public val isAsyncified: Boolean

    /**
     * インスタンスを作る。モジュールに start 関数があれば、燃料を設定した後に実行する。
     *
     * @throws WasmLinkException import が足りない、型が合わない、またはデータ・要素のセグメントが範囲に収まらない
     * @throws WasmLoadException モジュールが検証に通らない（実装によっては [WasmRuntime.load] でなくここで分かる）
     * @throws WasmTrapException start 関数がトラップした
     */
    @Throws(WasmLinkException::class, WasmLoadException::class, WasmTrapException::class)
    public fun instantiate(config: WasmInstanceConfig): WasmInstance
}

/** モジュールのインスタンス。線形メモリ・グローバル・テーブルなど、実行の状態を持つ。 */
public interface WasmInstance : AutoCloseable {
    /** 元のモジュール */
    public val module: WasmModule

    /** 線形メモリ（0 番）。メモリを持たないモジュールでは `null` */
    public val memory: WasmMemory?

    /**
     * 燃料の残り。負なら行き過ぎた分（次の配分から引く。#28）。
     *
     * ホストの関数の中から読み書きしてよい（システムコールの費用を課すため）。
     *
     * @throws IllegalStateException 燃料を計測しないモジュール（[WasmModule.isFuelMetered] が `false`）
     */
    public var fuel: Long

    /**
     * 待機点への変換の状態（#329）。ホストの関数の中から読み書きしてよい。
     *
     * 止まりうる import の中で [WasmAsyncState.UNWINDING] にして戻ると、変換したコードが WASM のスタックを
     * [WasmInstanceConfig.asyncStack] に退避して export の呼び出しから戻る。[WasmAsyncState.REWINDING] にして同じ export を
     * 呼び直すと、退避した値を戻しながら止まった import まで進む（そこで呼ばれた側が [WasmAsyncState.NORMAL] に戻す）。
     *
     * @throws IllegalStateException 変換していないモジュール（[WasmModule.isAsyncified] が `false`）
     */
    public var asyncState: WasmAsyncState

    /**
     * export された関数 [name] を呼ぶ。引数と戻り値の表し方は [WasmValueType] を見る。
     *
     * ホストの関数（[WasmHostFunction]）や燃料切れの処理（[WasmFuelHandler]）が投げた例外は、包まずにそのまま外に出る。
     *
     * @throws IllegalArgumentException その名前の関数が export されていない
     * @throws IllegalStateException インスタンスを閉じた後に呼んだ
     * @throws WasmTrapException 実行がトラップした
     */
    @Throws(WasmTrapException::class)
    public fun call(name: String, vararg args: Long): LongArray

    /** インスタンスを捨てる。以後 [call] は [IllegalStateException] になる。2回呼んでもよい。 */
    override fun close()
}

/**
 * 線形メモリ。アドレスは WASM の i32 の値（符号なし 32 ビットとして扱う）。値はリトルエンディアン。
 *
 * 範囲の外を読み書きすると [WasmMemoryAccessException] を投げる。
 */
public interface WasmMemory {
    /** 今のページ数（1 ページ = [PAGE_SIZE] バイト） */
    public val pages: Int

    /** 今の大きさ（バイト） */
    public val byteSize: Long get() = pages.toLong() * PAGE_SIZE

    /**
     * [deltaPages] ページ増やし、増やす前のページ数を返す。増やせないとき（上限を超える、
     * [WasmMemoryBudget] が断る。#39）は -1。
     */
    public fun grow(deltaPages: Int): Int

    /** [address] から [length] バイトを [dst] の [dstOffset] に読む。 */
    @Throws(WasmMemoryAccessException::class)
    public fun read(address: Int, dst: ByteArray, dstOffset: Int, length: Int)

    /** [address] から [length] バイトを読んで返す。 */
    @Throws(WasmMemoryAccessException::class)
    public fun read(address: Int, length: Int): ByteArray = ByteArray(length).also { read(address, it, 0, length) }

    /** [src] の [srcOffset] から [length] バイトを [address] に書く。 */
    @Throws(WasmMemoryAccessException::class)
    public fun write(address: Int, src: ByteArray, srcOffset: Int, length: Int)

    /** [src] をすべて [address] に書く。 */
    @Throws(WasmMemoryAccessException::class)
    public fun write(address: Int, src: ByteArray): Unit = write(address, src, 0, src.size)

    /** 1 バイト読む（符号なし。0〜255）。 */
    @Throws(WasmMemoryAccessException::class)
    public fun readU8(address: Int): Int

    /** 1 バイト書く（下位 8 ビット）。 */
    @Throws(WasmMemoryAccessException::class)
    public fun writeU8(address: Int, value: Int)

    /** 32 ビットの整数を読む。 */
    @Throws(WasmMemoryAccessException::class)
    public fun readI32(address: Int): Int

    /** 32 ビットの整数を書く。 */
    @Throws(WasmMemoryAccessException::class)
    public fun writeI32(address: Int, value: Int)

    /** 64 ビットの整数を読む。 */
    @Throws(WasmMemoryAccessException::class)
    public fun readI64(address: Int): Long

    /** 64 ビットの整数を書く。 */
    @Throws(WasmMemoryAccessException::class)
    public fun writeI64(address: Int, value: Long)

    public companion object {
        /** 1 ページの大きさ（64 KiB） */
        public const val PAGE_SIZE: Int = 65536

        /** 32 ビットの線形メモリのページ数の上限（4 GiB） */
        public const val MAX_PAGES: Int = 65536
    }
}

/**
 * 線形メモリの確保を数える相手（#39）。[WasmInstanceConfig.Builder.memoryLimit] で渡す。
 *
 * ランタイムは、メモリを作るとき（最初のページ数）と増やすとき（`memory.grow`）に [tryReserve] を呼び、
 * インスタンスを閉じたとき（作るのに失敗したときも）に、確保した分をまとめて [release] で返す。
 * 複数のインスタンスで1つを共有して、合計を制限してよい（カーネルはプロセスごと・マシンごとに数える）。
 *
 * スレッド: どのスレッドから呼ばれてもよいように作る（別々のインスタンスが、別々のスレッドから同時に呼ぶ）。
 * WASM の実行の中から呼ばれるので、待たない・WASM を呼ばない。
 */
public interface WasmMemoryBudget {
    /**
     * [pages] ページ（1 以上）を確保してよければ、確保した分として数えて `true` を返す。
     * だめなら何も数えずに `false` を返す。
     */
    public fun tryReserve(pages: Int): Boolean

    /** [tryReserve] で確保した分のうち [pages] ページ（1 以上）を返す。 */
    public fun release(pages: Int)
}

/**
 * テーブルの確保を数える相手（#361）。[WasmInstanceConfig.Builder.tableLimit] で渡す。
 *
 * ランタイムは、テーブルを作るとき（最初の要素数）と増やすとき（`table.grow`）に、要素数 × 要素1つのバイト数を
 * [tryReserveBytes] で数え、インスタンスを閉じたとき（作るのに失敗したときも）に、まとめて [releaseBytes] で返す。
 * 増やすのに失敗したら数えない。スレッドの約束は [WasmMemoryBudget] と同じ。
 *
 * カーネルの外に出す必要がまだないので、`internal` にしてある（カーネルの [pro.ikili.kibicomputers.core.kernel.ProcessMemory] が実装する）。
 */
internal interface WasmTableBudget {
    /** [bytes] バイト（1 以上）を確保してよければ、数えて `true`。だめなら何も数えずに `false` */
    fun tryReserveBytes(bytes: Long): Boolean

    /** [tryReserveBytes] で確保した分のうち [bytes] バイト（1 以上）を返す */
    fun releaseBytes(bytes: Long)
}

/**
 * ホストの関数（システムコールなど）。
 *
 * 投げた例外は、WASM の中では捕まえられず、[WasmInstance.call] からそのまま外に出る。
 * 実行を止める（終了、tick の待ちでの中断など）には例外を投げる。
 */
public fun interface WasmHostFunction {
    /**
     * @param instance 呼び出したインスタンス。メモリと燃料を読み書きできる
     * @param args 引数（型は import の宣言のとおり）
     * @return 戻り値（数と型は import の宣言のとおり）
     */
    public fun call(instance: WasmInstance, args: LongArray): LongArray
}

/** 燃料切れのときに呼ばれる処理（#28・#27）。 */
public fun interface WasmFuelHandler {
    /**
     * @param instance 燃料が切れたインスタンス
     * @param remaining 今の残り（負。行き過ぎた分）
     * @return 新しい残り。正でなければ、次に燃料を確かめるところでまた呼ばれる。実行を止めるには例外を投げる
     */
    public fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long
}

/** 燃料を計測するモジュールが使う名前（#28）。カーネルの内部のもので、プレイヤーには見せない。 */
public object WasmFuel {
    /** 燃料切れを知らせる import のモジュール名。型は `(i64 残り) -> (i64 新しい残り)` */
    public const val IMPORT_MODULE: String = "kibi"

    /** 燃料切れを知らせる import の名前 */
    public const val IMPORT_NAME: String = "fuel"

    /** 燃料の残りを入れる、可変の i64 のグローバルの export 名 */
    public const val EXPORT_GLOBAL: String = "kibi_fuel"

    /** 燃料切れを知らせる import の型 */
    @JvmField
    public val IMPORT_TYPE: WasmFunctionType = WasmFunctionType.of(listOf(WasmValueType.I64), listOf(WasmValueType.I64))
}

/** 待機点への変換をかけたモジュールの状態（#329）。状態のグローバルの値は、この並びの順に 0・1・2 */
public enum class WasmAsyncState {
    /** ふつうに実行している */
    NORMAL,

    /** 巻き戻し中（WASM のスタックを退避しながら、export の呼び出しから戻っている） */
    UNWINDING,

    /** 巻き直し中（退避した値を戻しながら、止まった呼び出しまで進んでいる） */
    REWINDING,
}

/**
 * 待機点への変換をかけたモジュールが、巻き戻しで退避する値の置き場所（#329）。[WasmInstanceConfig.Builder.asyncStack] で渡す。
 *
 * 値は後入れ先出し（巻き戻しは内側の関数から積み、巻き直しは外側の関数から取り出す）。
 *
 * スレッド: WASM の実行の中（インスタンスを使っているスレッド）から呼ばれるので、待たない・WASM を呼ばない。
 * 投げた例外は、[WasmInstance.call] からそのまま外に出る（実行を止める）。
 */
public interface WasmAsyncStack {
    /** 退避する値 [value] を1つ積む。上限を超えたら例外を投げる（実行を止める） */
    public fun push(value: Long)

    /** 最後に積んだ値を取り出す。空なら例外を投げる */
    public fun pop(): Long
}

/** 待機点への変換をかけたモジュールが使う名前（#329）。カーネルの内部のもので、プレイヤーには見せない。 */
public object WasmAsync {
    /** 退避の import のモジュール名（燃料の [WasmFuel.IMPORT_MODULE] と同じ予約の空間） */
    public const val IMPORT_MODULE: String = "kibi"

    /** 値を1つ積む import の名前。型は [PUSH_TYPE] */
    public const val PUSH: String = "async_push"

    /** 値を1つ取り出す import の名前。型は [POP_TYPE] */
    public const val POP: String = "async_pop"

    /** 状態（[WasmAsyncState] の順番）を入れる、可変の i32 のグローバルの export 名 */
    public const val STATE_EXPORT: String = "kibi_async_state"

    /** [PUSH] の型 `(i64) -> ()` */
    @JvmField
    public val PUSH_TYPE: WasmFunctionType = WasmFunctionType.of(listOf(WasmValueType.I64), emptyList())

    /** [POP] の型 `() -> (i64)` */
    @JvmField
    public val POP_TYPE: WasmFunctionType = WasmFunctionType.of(emptyList(), listOf(WasmValueType.I64))
}
