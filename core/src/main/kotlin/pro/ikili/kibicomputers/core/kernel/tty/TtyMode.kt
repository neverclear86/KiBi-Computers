package pro.ikili.kibicomputers.core.kernel.tty

/**
 * TTY のモード（termios の必要な分だけ。ADR 0060）。ビットの組（[bits]）で持ち、ビットは個別に効く。
 *
 * `VMIN`・`VTIME`・特別な文字の変更・ジョブ制御（Ctrl+Z）は扱わない。
 */
internal data class TtyMode(val bits: Int) {
    init {
        require(bits and ALL.inv() == 0) { "知らないモードのビット: 0x${bits.toString(16)}" }
    }

    /** ICANON: 行単位の入力と行編集 */
    val canonical: Boolean get() = bits and CANONICAL != 0

    /** ECHO: 入力をエコーする（canonical のときの消去の表示を含む） */
    val echo: Boolean get() = bits and ECHO != 0

    /** ISIG: Ctrl+C・Ctrl+\ をシグナルにする */
    val signals: Boolean get() = bits and SIGNALS != 0

    /** ICRNL: 入力の CR を LF にする */
    val crToNl: Boolean get() = bits and CR_TO_NL != 0

    /** OPOST|ONLCR: 出力の LF を CR LF にする */
    val nlToCrNl: Boolean get() = bits and NL_TO_CRNL != 0

    /** ビット [bit] を [on] にしたモード */
    fun with(bit: Int, on: Boolean): TtyMode = TtyMode(if (on) bits or bit else bits and bit.inv())

    override fun toString(): String = "TtyMode(0x${bits.toString(16)})"

    companion object {
        const val CANONICAL: Int = 1
        const val ECHO: Int = 2
        const val SIGNALS: Int = 4
        const val CR_TO_NL: Int = 8
        const val NL_TO_CRNL: Int = 16

        /** 知っているビットすべて */
        const val ALL: Int = CANONICAL or ECHO or SIGNALS or CR_TO_NL or NL_TO_CRNL

        /** 全部オン（既定） */
        val COOKED: TtyMode = TtyMode(ALL)

        /** 全部オフ（`cfmakeraw` 相当） */
        val RAW: TtyMode = TtyMode(0)
    }
}

/** 画面の大きさ（文字セル）。どちらも 1 以上 */
internal data class TtySize(val columns: Int, val rows: Int) {
    init {
        require(columns >= 1 && rows >= 1) { "画面の大きさは 1 以上: ${columns}x$rows" }
    }
}

/** TTY が起こすシグナル */
internal enum class TtySignal {
    /** Ctrl+C */
    INTERRUPT,

    /** Ctrl+\ */
    QUIT,
}

/** [Tty.input] の結果。入力はいつも全部処理する（途中で止めない） */
internal data class TtyInputResult(
    /** 起きたシグナル（起きた順） */
    val signals: List<TtySignal>,
    /** あふれて捨てたバイト数 */
    val dropped: Int,
)
