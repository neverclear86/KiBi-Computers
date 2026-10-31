package pro.ikili.kibicomputers.core.kernel.fd

/** 標準入出力の1つ（fd 0/1/2 のどれか）に何を割り当てるか */
internal sealed interface StdioSource {
    /** 親の同じ番号の fd を引き継ぐ。親がなければ [Console]。親のその fd が閉じていれば [Closed] */
    data object Inherit : StdioSource

    /** 親の fd [fd] を引き継ぐ（パイプの片端を子の標準入力にするなど）。開いていなければ `BADF` */
    data class ParentFd(val fd: Int) : StdioSource

    /** オブジェクト [objectId] を [rights] で割り当てる（参照を1つ増やす。呼び出し側の参照はそのまま） */
    data class Object(val objectId: Int, val rights: Int) : StdioSource

    /** マシンのコンソール（入力は fd 0、出力は fd 1・2。TTY ができたら #60 で置き換える） */
    data object Console : StdioSource

    /** 割り当てない（閉じたまま） */
    data object Closed : StdioSource
}

/** プロセスを作るときの、標準入出力（fd 0/1/2）の割り当て */
internal data class StdioSpec(
    val stdin: StdioSource = StdioSource.Inherit,
    val stdout: StdioSource = StdioSource.Inherit,
    val stderr: StdioSource = StdioSource.Inherit,
) {
    /** fd [fd]（0・1・2）の割り当て */
    fun source(fd: Int): StdioSource = when (fd) {
        STDIN -> stdin
        STDOUT -> stdout
        STDERR -> stderr
        else -> throw IllegalArgumentException("標準入出力の fd ではない: $fd")
    }

    companion object {
        /** 3つとも親から引き継ぐ（既定） */
        val INHERIT: StdioSpec = StdioSpec()

        /** 3つともコンソール */
        val CONSOLE: StdioSpec = StdioSpec(StdioSource.Console, StdioSource.Console, StdioSource.Console)

        /** 3つとも閉じたまま */
        val CLOSED: StdioSpec = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)
    }
}
