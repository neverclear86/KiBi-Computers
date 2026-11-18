package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.asyncify.Asyncify
import pro.ikili.kibicomputers.core.runtime.asyncify.AsyncifyUnsupported

/**
 * コンポーネント全体で、待機点への変換（#329 の `Asyncify`）の止まりうる import を決める（#757。ADR 0757）。
 *
 * core モジュールの import が止まりうるかは、ほかの core モジュールの解析の結果で決まる（アダプターで包んだ p2 のコマンドなら、
 * main の `fd_read` → アダプターの `fd_read` → shim の `call_indirect` → fixup が表に入れた lower の import → ホストの `blocking-read`）。
 * そこで、次の 2 つを、全体で答えが変わらなくなるまで繰り返す:
 * - 各モジュールの止まりうる import: 計画の行き先（[CoreRef]）が、待ちうるホストの関数（[HostWait.UNWIND] か [HostWait.BLOCK]）を
 *   lower したもの、またはほかのモジュールの止まりうる export
 * - 外から止まりうるとみなす関数の型: 全モジュールの止まりうる関数の型の和。import・export したテーブルへの `call_indirect` と
 *   `call_ref` に当てる（[Asyncify.Prepared.transform]）
 *
 * 同じ中身のモジュールを 2 回以上インスタンス化していれば、止まりうる import は使われ方の和にする（変換は 1 回）。
 *
 * 次のどれかなら変換しない（[Result.rejection]。コンポーネントは今までどおり `BLOCKING` で動く）:
 * - 巻き戻せない待ち（[HostWait.BLOCK]）のホストの関数を lower している
 * - 待ちが、ゲストからゲストへの呼び出し（lift した関数を lower した import）の下にある。間に canon の Kotlin の層があり、
 *   callee の線形メモリへの lower・Task・borrow の範囲が呼び出しの途中の状態になるので、巻き戻せない
 * - ゲストが実装した resource の dtor が止まりうる（`resource.drop` の Kotlin の層から呼ぶので、同じく巻き戻せない）
 * - どれかのモジュールを変換できない作り（[AsyncifyUnsupported]）
 */
internal object ComponentAsyncify {
    /**
     * 解析の結果。[rejection] が `null` なら、モジュールの番号ごとの止まりうる import [waitImports] と、外から止まりうるとみなす型
     * [externalTypes] で変換する
     */
    class Result(val waitImports: List<Set<String>>, val externalTypes: Set<Asyncify.Signature>, val rejection: String?)

    /** [planned] の core モジュール [modules]（計画の番号の順）を解析する。[checkpoint] は 1 周ごとに呼ぶ（時間の上限） */
    fun analyze(planned: ComponentPlanner.Planned, modules: List<Asyncify.Prepared>, checkpoint: () -> Unit = {}): Result {
        for (lower in planned.lowers) {
            val callee = lower.callee
            if (callee is FuncRef.Host && callee.host.wait == HostWait.BLOCK) {
                return reject(modules, "巻き戻せない待ちのホストの関数 ${callee.interfaceName}#${callee.name} を import している")
            }
        }
        val suspendExports = Array<Set<String>>(modules.size) { emptySet() }
        val waitImports = Array<Set<String>>(modules.size) { emptySet() }
        val types = LinkedHashSet<Asyncify.Signature>()

        fun exportWaits(ref: CoreRef.Export): Boolean = ref.name in suspendExports[planned.steps[ref.step].module]

        fun lowerWaits(lower: Int): Boolean = when (val callee = planned.lowers[lower].callee) {
            is FuncRef.Host -> callee.host.wait != HostWait.NONE
            is FuncRef.Lifted -> when (val core = planned.lifts[callee.lift].core) {
                is CoreRef.Export -> exportWaits(core)
                is CoreRef.Lowered -> lowerWaits(core.lower)
                is CoreRef.ResourceOp -> false
            }
        }

        var changed = true
        while (changed) {
            changed = false
            checkpoint()
            for (m in modules.indices) {
                val imports = LinkedHashSet<String>()
                for (step in planned.steps) {
                    if (step.module != m) continue
                    for ((imp, ref) in step.imports) {
                        val waits = when (ref) {
                            is CoreRef.Export -> exportWaits(ref)
                            is CoreRef.Lowered -> lowerWaits(ref.lower)
                            is CoreRef.ResourceOp -> false
                        }
                        if (waits) imports += "${imp.module}.${imp.name}"
                    }
                }
                val a = try {
                    modules[m].analyze(imports, types)
                } catch (e: AsyncifyUnsupported) {
                    return reject(modules, "core モジュール $m: ${e.message ?: "理由不明"}")
                }
                if (imports != waitImports[m] || a.suspendableExports != suspendExports[m]) changed = true
                waitImports[m] = imports
                suspendExports[m] = a.suspendableExports
                if (types.addAll(a.suspendTypes)) changed = true
            }
        }
        for ((i, lower) in planned.lowers.withIndex()) {
            if (lower.callee is FuncRef.Lifted && lowerWaits(i)) {
                return reject(modules, "待ちが、ゲストからゲストへの呼び出し（canon lower $i）の下にある")
            }
        }
        for (info in planned.resources) {
            val impl = info?.impl as? ResourceImpl.Guest ?: continue
            val dtor = impl.dtor
            val waits = when (dtor) {
                is CoreRef.Export -> exportWaits(dtor)
                is CoreRef.Lowered -> lowerWaits(dtor.lower)
                else -> false
            }
            if (waits) return reject(modules, "ゲストが実装した resource の dtor（$dtor）が止まりうる")
        }
        return Result(waitImports.toList(), types, null)
    }

    private fun reject(modules: List<Asyncify.Prepared>, reason: String) = Result(List(modules.size) { emptySet() }, emptySet(), reason)
}
