package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.util.concurrent.atomic.AtomicLong

/**
 * 読み込んだ WASM（[Program]・[ComponentProgram]）を、テストの JVM の中で使い回す表（#991）。
 *
 * 読み込み（燃料の計測コードの挿入 → 待機点への変換 → パース・検証 → コンパイル）は `lua.wasm` で 1 回 1〜3 秒かかるので、
 * テストごとに新しいスケジューラ（[Harness]）を作ると、同じものを何百回も読み込み直してしまう。[Program] は不変で、本番でも
 * マシンの間で共有している（`ProgramCache`）ので、共有そのものは本番と同じ形になる。
 *
 * - 共有するのは、結果が入力だけで決まるときだけ: ランタイムが引数なしで作った [EndiveRuntime]（[EndiveRuntime.defaultConfiguration]。
 *   包んだもの・インタプリタだけのもの・時計を差し替えたものは共有しない）、上限が [ModuleLimits.DEFAULT] そのもの（`ModuleLimits` は
 *   同一性で比べるので、縮めた上限で断らせるテストが覚えた成功で検査を飛ばさないように）、ホストが状態を持たない単一のもの。
 * - [Harness] のスケジューラは、スレッドのスタック（`SchedulerConfig.threadStackBytes`）が既定より小さくないときだけ表を使う
 *   （[shareable]）。コンパイルはスケジューラのコンパイル用のスレッド（そのスタックの大きさ）で動き、`EndiveRuntime.load` は
 *   コンパイル中の `StackOverflowError` などを失敗にせず、インタプリタに落とした [Program] を成功として返す。小さいスタックで
 *   落ちた結果を覚えると、同じ JVM の後続のテストが黙ってインタプリタの [Program] を受け取るので、結果が入力だけで決まらない。
 * - キーのハッシュは、渡されたバイト列から計算する（置き場で期待していたハッシュは使わない。中身だけを差し替えて照合の失敗を
 *   確かめるテストがある）。
 * - 覚えるのは成功だけ。例外（`Error` も）は覚えずにそのまま投げる（失敗を覚えるのは今までどおり `ProgramCache` の役目）。
 * - 読み込みは表のロックの外で行い、読み込み中のものを待ち合わせない（ほかのスケジューラの読み込みが割り込まれたときに、
 *   巻き添えで失敗しないように）。同時に外れたら二重に読み込み、先に入った方を返す。
 * - 上限は、使った順で外す強い参照の LRU で、件数と重さ（`cacheWeight`）の両方で切る（`SoftReference` だと当たるかどうかが GC 次第になり、
 *   テストが決定的でなくなる）。
 *
 * 読み込みの副作用（回数・時間・読み込み時のログ）に頼るテストは、`Harness(sharePrograms = false)` で切る。
 */
internal class SharedProgramTable(private val maxEntries: Int, private val maxWeight: Long) {
    private class Entry(val value: Any, val weight: Long)

    private val entries = LinkedHashMap<Any, Entry>(16, 0.75f, true)
    private var weight = 0L

    /** 表から返した回数 */
    val hits: AtomicLong = AtomicLong()

    /** 表になく、読み込んだ回数（共有しない条件で直に読み込んだものは数えない） */
    val misses: AtomicLong = AtomicLong()

    /** 今覚えている数 */
    val size: Int get() = synchronized(entries) { entries.size }

    /** 今覚えている重さの合計 */
    val totalWeight: Long get() = synchronized(entries) { weight }

    /** [key] のものを返す。なければロックの外で [load] で読み込み、覚えてから返す */
    fun <V : Any> getOrLoad(key: Any, weightOf: (V) -> Long, load: () -> V): V {
        synchronized(entries) {
            entries[key]?.let {
                hits.incrementAndGet()
                @Suppress("UNCHECKED_CAST")
                return it.value as V
            }
        }
        misses.incrementAndGet()
        val loaded = load()
        synchronized(entries) {
            entries[key]?.let {
                // 同時に外れた別の読み込みが先に入れた。そちらを返す（どのスケジューラにも同じインスタンスを渡す）
                @Suppress("UNCHECKED_CAST")
                return it.value as V
            }
            val w = weightOf(loaded).coerceAtLeast(0)
            entries[key] = Entry(loaded, w)
            weight += w
            // 使った順の古いものから外す（入れたばかりのものは外さない）
            val it = entries.values.iterator()
            while ((entries.size > maxEntries || weight > maxWeight) && entries.size > 1) {
                weight -= it.next().weight
                it.remove()
            }
        }
        return loaded
    }

    /** 全部を忘れる */
    fun clear() {
        synchronized(entries) {
            entries.clear()
            weight = 0
        }
    }
}

/** JVM に 1 つの共有の表と、それを使う読み込みの口（#991。[SharedProgramTable]） */
internal object SharedPrograms {
    /** 覚える数の上限 */
    const val MAX_ENTRIES: Int = 64

    /** 覚える重さ（`cacheWeight` の合計）の上限 */
    const val MAX_WEIGHT: Long = 64L shl 20

    val table: SharedProgramTable = SharedProgramTable(MAX_ENTRIES, MAX_WEIGHT)

    private data class ExecKey(val sha256: String, val metered: Boolean, val waitImports: Set<String>)

    private data class MeteredKey(val sha256: String, val waitImports: Set<String>)

    private data class PlainKey(val sha256: String)

    private data class ComponentKey(val kind: String, val sha256: String, val metered: Boolean, val host: ComponentHost)

    private fun hex(bytes: ByteArray): String = ModuleStore.hex(ModuleStore.sha256(bytes))

    /** 読み込んだ結果を、ランタイムのインスタンスをまたいで使い回してよいランタイム */
    fun shareable(runtime: WasmRuntime): Boolean = (runtime as? EndiveRuntime)?.defaultConfiguration == true

    /** 既定のスレッドのスタックの大きさ（`SchedulerConfig` の既定） */
    val DEFAULT_THREAD_STACK_BYTES: Long = SchedulerConfig.builder().build().threadStackBytes

    /**
     * [config] のスケジューラで読み込んだ結果を表に入れてよいか: スレッドのスタックが既定より小さくないとき。
     * 小さいとコンパイルが `StackOverflowError` でインタプリタに落ちうる（落ちても読み込みは成功になる）ので、共有しない
     */
    fun shareable(config: SchedulerConfig): Boolean = config.threadStackBytes >= DEFAULT_THREAD_STACK_BYTES

    /** `Machine.exec` と復元の読み込み（[SchedulerInternals.programLoader]）。共有しない条件なら [Program.loadExecutable] をそのまま呼ぶ */
    val executableLoader: ProgramLoader = ProgramLoader { runtime, bytes, limits, metered, waitImports, host ->
        if (!shareable(runtime) || limits !== ModuleLimits.DEFAULT || host !== KernelDrivers.COMPONENT_HOST) {
            Program.loadExecutable(runtime, bytes, limits, metered, waitImports, host)
        } else {
            table.getOrLoad(ExecKey(hex(bytes), metered, waitImports.toSet()), Program::cacheWeight) {
                Program.loadExecutable(runtime, bytes, limits, metered, waitImports, host)
            }
        }
    }

    /** `Program.loadMetered(EndiveRuntime(), bytes, null, waitImports)` を覚える（テストクラスの `by lazy` の代わり） */
    fun loadMetered(bytes: ByteArray, waitImports: Set<String>): Program =
        table.getOrLoad(MeteredKey(hex(bytes), waitImports.toSet()), Program::cacheWeight) {
            Program.loadMetered(EndiveRuntime(), bytes, null, waitImports)
        }

    /** `Program.load(EndiveRuntime(), bytes)` を覚える（テストクラスの `by lazy` の代わり） */
    fun load(bytes: ByteArray): Program =
        table.getOrLoad(PlainKey(hex(bytes)), Program::cacheWeight) { Program.load(EndiveRuntime(), bytes) }

    /**
     * コンポーネントを [load] で読み込み、覚える。[kind] は読み込み方（判定の方針など、キーに入らない引数）の名前。
     * [host] は状態を持たない単一のものだけを渡す（状態を持つものに結び付いた計画を、別のテストに渡さないように）
     */
    fun component(kind: String, runtime: WasmRuntime, bytes: ByteArray, host: ComponentHost, metered: Boolean, load: () -> ComponentProgram): ComponentProgram =
        if (!shareable(runtime)) load() else table.getOrLoad(ComponentKey(kind, hex(bytes), metered, host), ComponentProgram::cacheWeight, load)
}
