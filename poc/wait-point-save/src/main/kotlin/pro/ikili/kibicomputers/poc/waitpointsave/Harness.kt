package pro.ikili.kibicomputers.poc.waitpointsave

import java.security.MessageDigest

/** シナリオを動かした結果。 */
class RunResult(val process: Process, val stdout: String, val memorySha256: String, val saves: Int)

/** プロセスを作って、最初の待機点まで動かす（`_initialize`、lua_host ならスクリプトの読み込み）。 */
fun boot(program: WasmProgram, mode: Mode, script: String? = null): Process {
    val p = Process.start(program, mode)
    p.initialize()
    if (script != null) p.load(script)
    return p
}

/** 待機点で保存し、新しいインスタンスに戻す。[viaBytes] ならバイト列に変換して読み直してから戻す。 */
fun saveAndRestore(process: Process, mode: Mode, viaBytes: Boolean = false): Process {
    var snap = Snapshot.capture(process)
    if (viaBytes) snap = Snapshot.fromBytes(snap.toBytes())
    process.instance.close()
    return snap.restore(process.program, mode)
}

/**
 * [events] を順に渡す。[restoreMode] があれば、各イベントを渡す前に保存し、その方式の新しいインスタンスに戻す。
 * 最後に、標準出力とメモリのハッシュを返す（保存しなかった実行と比べるため）。
 */
fun runEvents(start: Process, events: List<ByteArray>, restoreMode: ((Int) -> Mode)? = null, viaBytes: Boolean = false): RunResult {
    var p = start
    var saves = 0
    events.forEachIndexed { i, e ->
        if (restoreMode != null) {
            p = saveAndRestore(p, restoreMode(i), viaBytes)
            saves++
        }
        p.deliver(e)
    }
    return RunResult(p, p.kernel.stdoutText(), memorySha256(p), saves)
}

fun memorySha256(p: Process): String {
    val mem = p.instance.memory()
    val bytes = mem.readBytes(0, mem.pages() * run.endive.runtime.Memory.PAGE_SIZE)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

fun String.events(): List<ByteArray> = split(' ').map { it.toByteArray() }
