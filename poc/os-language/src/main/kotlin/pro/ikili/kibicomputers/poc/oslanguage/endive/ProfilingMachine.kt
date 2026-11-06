package run.endive.runtime

/**
 * 関数ごとの燃料の内訳を測るための、Endive のインタプリタ（#32 の追加調査）。
 *
 * [ExecutionListener] は命令しか渡さないので、今どの関数を実行しているかを呼び出しのスタックから読めるようにする。
 * [StackFrame.funcId] がパッケージの中だけで見えるため、このクラスは Endive と同じパッケージに置く（PoC だけ。MOD には入らない）。
 * ファイルは .gitignore の `run/` に当たらないよう、PoC のパッケージのディレクトリに置く。
 */
class ProfilingMachine(instance: Instance) : InterpreterMachine(instance) {
    /** 今実行している関数の番号（import を含めた番号）。呼び出しの外なら -1 */
    fun currentFunc(): Int = callStack.peek()?.funcId() ?: -1

    /** 呼び出しのスタックにある関数の番号（上から）を [out] に入れ、数を返す */
    fun stackFuncs(out: IntArray): Int {
        var n = 0
        for (frame in callStack) {
            if (n == out.size) break
            out[n++] = frame.funcId()
        }
        return n
    }
}
