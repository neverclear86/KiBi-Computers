package pro.ikili.kibicomputers.core.runtime.component

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import pro.ikili.kibicomputers.core.runtime.validation.ModulePrescan
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** コンポーネントのバイナリの読み込みと検証（#262） */
class ComponentLoaderTest {
    private fun wat(text: String): ByteArray = WitTool.wat(text)

    private fun rejects(bytes: ByteArray, limits: ComponentLimits = ComponentLimits.DEFAULT): WasmLoadException =
        assertThrows<WasmLoadException> { ComponentLoader.load(bytes, limits) }

    private fun assertMessage(e: Exception, part: String) =
        assertTrue(e.message!!.contains(part)) { "「$part」を含まない: ${e.message}" }

    /** 型・import・core モジュール・resource・canon・入れ子・インスタンス化・export をひととおり持つもの */
    private val full by lazy {
        wat(
            """
            (component
              (import "wasi:cli/stdout@0.2.0" (instance ${'$'}out
                (export "get" (func (result u32)))
              ))
              (core module ${'$'}m
                (func (export "run") (result i32) i32.const 0)
                (memory (export "mem") 1)
              )
              (core instance ${'$'}i (instantiate ${'$'}m))
              (type ${'$'}rec (record (field "a" u32) (field "b" string)))
              (type ${'$'}v (variant (case "x" u8) (case "y")))
              (type ${'$'}r (resource (rep i32)))
              (func ${'$'}run (result u32) (canon lift (core func ${'$'}i "run")))
              (core func ${'$'}rn (canon resource.new ${'$'}r))
              (core func ${'$'}rd (canon resource.drop ${'$'}r))
              (core func ${'$'}rr (canon resource.rep ${'$'}r))
              (component ${'$'}inner
                (import "f" (func ${'$'}f (result u32)))
                (core module ${'$'}n)
                (core instance (instantiate ${'$'}n))
                (export "g" (func ${'$'}f))
              )
              (instance ${'$'}ii (instantiate ${'$'}inner (with "f" (func ${'$'}run))))
              (export "run" (func ${'$'}run))
              (export "inner" (instance ${'$'}ii))
            )
            """,
        )
    }

    /** p3 の非同期の canon（stream・waitable-set・task.return・async の lift と callback） */
    private val async by lazy {
        wat(
            """
            (component
              (core module ${'$'}libc (memory (export "mem") 1))
              (core instance ${'$'}libc (instantiate ${'$'}libc))
              (type ${'$'}s (stream u8))
              (type ${'$'}f (future string))
              (core func ${'$'}sn (canon stream.new ${'$'}s))
              (core func ${'$'}sr (canon stream.read ${'$'}s async (memory ${'$'}libc "mem")))
              (core func ${'$'}sd (canon stream.drop-readable ${'$'}s))
              (core func ${'$'}fn (canon future.new ${'$'}f))
              (core func ${'$'}wn (canon waitable-set.new))
              (core func ${'$'}ww (canon waitable-set.wait (memory ${'$'}libc "mem")))
              (core func ${'$'}wj (canon waitable.join))
              (core func ${'$'}tr (canon task.return (result u32)))
              (core func ${'$'}cg (canon context.get i32 0))
              (core func ${'$'}y (canon thread.yield))
              (core func ${'$'}bi (canon backpressure.inc))
              (core module ${'$'}m
                (import "" "sn" (func (result i64)))
                (func (export "f") (result i32) i32.const 0)
                (func (export "cb") (param i32 i32 i32) (result i32) i32.const 0)
              )
              (core instance ${'$'}i (instantiate ${'$'}m (with "" (instance (export "sn" (func ${'$'}sn))))))
              (func (export "f") async (result u32) (canon lift (core func ${'$'}i "f") async (callback (func ${'$'}i "cb"))))
            )
            """,
        )
    }

    @Test
    fun `最小のコンポーネントを読める`() {
        val c = ComponentLoader.load(wat("(component)"))
        assertTrue(c.root.sections.all { it is ComponentSection.Custom })
        assertEquals(0, c.coreModules.size)
        assertEquals(0, c.expandedCoreInstances)
        assertEquals(32, c.sha256.size)
    }

    @Test
    fun `型・import・export・coreモジュールを取り出す`() {
        val c = ComponentLoader.load(full)
        val root = c.root
        assertEquals(listOf("wasi:cli/stdout@0.2.0"), c.imports.map { it.name })
        assertTrue(c.imports.single().desc is ExternDesc.Instance)
        assertEquals(listOf("run", "inner"), c.exports.map { it.name })
        assertEquals(SortIdx(Sort.INSTANCE, 1), c.exports[1].target)

        // 入れ子の中のものも含めて、core モジュールは2つ。どちらも完全な core モジュール（version 1）
        assertEquals(2, c.coreModules.size)
        for (range in c.coreModules) {
            val module = c.coreModuleBytes(range)
            assertEquals(WasmBinaryKind.CORE_MODULE, WasmBinaryHeader.kindOf(module))
            assertTrue(WasmBinaryHeader.isCoreVersion(module))
            assertDoesNotThrow { ModulePrescan.check(module, ModuleLimits.DEFAULT, null) }
        }
        // 外の core instance 1 個と、1 回インスタンス化する inner の中の 1 個
        assertEquals(2, c.expandedCoreInstances)

        val types = root.sections.filterIsInstance<ComponentSection.Types>().flatMap { it.items }
        assertTrue(types.any { it is InstanceTypeDef })
        assertTrue(
            types.contains(
                DefValType.Record(
                    listOf(
                        LabeledValType("a", ValType.Primitive(PrimValType.U32)),
                        LabeledValType("b", ValType.Primitive(PrimValType.STRING)),
                    ),
                ),
            ),
        )
        assertTrue(types.contains(DefValType.Variant(listOf(VariantCase("x", ValType.Primitive(PrimValType.U8)), VariantCase("y", null)))))
        assertTrue(types.contains(ResourceType(CoreValType.I32, null)))
        val canons = root.sections.filterIsInstance<ComponentSection.Canons>().flatMap { it.items }
        assertTrue(canons.first() is Canon.Lift)
        assertEquals(listOf(Canon.ResourceNew(3), Canon.ResourceDrop(3), Canon.ResourceRep(3)), canons.drop(1))

        // 添字空間: func は lift の 1 個と export の 1 個、core func は alias の 1 個と resource の 3 個
        assertEquals(2, root.indexSpaceSize(Sort.FUNC))
        assertEquals(4, root.indexSpaceSize(Sort.CORE_FUNC))
        // instance は import・instantiate・export で 3 個（export も番号を足す）
        assertEquals(3, root.indexSpaceSize(Sort.INSTANCE))
        assertEquals(1, root.indexSpaceSize(Sort.COMPONENT))
        assertEquals(1, root.indexSpaceSize(Sort.CORE_MODULE))
        val inner = root.sections.filterIsInstance<ComponentSection.Nested>().single().component
        assertEquals(1, inner.depth)
        assertEquals(listOf("f"), inner.imports.map { it.name })
    }

    @Test
    fun `宣言の順と添字は決定的`() {
        val a = ComponentParser.parse(full, ComponentLimits.DEFAULT, null)
        val b = ComponentParser.parse(full.copyOf(), ComponentLimits.DEFAULT, null)
        assertEquals(a.sections, b.sections)
        for (sort in Sort.entries) assertEquals(a.indexSpaceSize(sort), b.indexSpaceSize(sort))
        // セクションはバイナリの順のまま（並べ替えない）
        val offsets = a.sections.map { it.range.offset }
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun `非同期のcanonを読める`() {
        val c = ComponentLoader.load(async)
        val canons = c.root.sections.filterIsInstance<ComponentSection.Canons>().flatMap { it.items }
        val ops = canons.filterIsInstance<Canon.Builtin>().map { it.op }
        assertEquals(
            listOf(
                CanonBuiltin.STREAM_NEW, CanonBuiltin.STREAM_READ, CanonBuiltin.STREAM_DROP_READABLE, CanonBuiltin.FUTURE_NEW,
                CanonBuiltin.WAITABLE_SET_NEW, CanonBuiltin.WAITABLE_SET_WAIT, CanonBuiltin.WAITABLE_JOIN, CanonBuiltin.TASK_RETURN,
                CanonBuiltin.CONTEXT_GET, CanonBuiltin.THREAD_YIELD, CanonBuiltin.BACKPRESSURE_INC,
            ),
            ops,
        )
        val read = canons.filterIsInstance<Canon.Builtin>().first { it.op == CanonBuiltin.STREAM_READ }
        assertTrue(read.options.contains(CanonOption.Async))
        val ret = canons.filterIsInstance<Canon.Builtin>().first { it.op == CanonBuiltin.TASK_RETURN }
        assertEquals(ValType.Primitive(PrimValType.U32), ret.result)
        val lift = canons.filterIsInstance<Canon.Lift>().single()
        assertTrue(lift.options.contains(CanonOption.Async))
        assertTrue(lift.options.any { it is CanonOption.Callback })
        val funcType = c.root.sections.filterIsInstance<ComponentSection.Types>().flatMap { it.items }.filterIsInstance<FuncType>().single()
        assertTrue(funcType.async)
    }

    @Test
    fun `ヘッダーで見分ける`() {
        assertEquals(WasmBinaryKind.CORE_MODULE, WasmBinaryHeader.kindOf(TestModules.ADD))
        assertEquals(WasmBinaryKind.COMPONENT, WasmBinaryHeader.kindOf(full))
        assertNull(WasmBinaryHeader.kindOf(byteArrayOf(1, 2, 3)))
        assertMessage(rejects(TestModules.ADD), "core モジュール")
        assertMessage(rejects(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)), "ヘッダーがない")
        // 知らない版
        val newer = full.copyOf().also { it[4] = 0x0e }
        assertMessage(rejects(newer), "対応していないコンポーネントの版")
        // 知らない layer
        val layer2 = full.copyOf().also { it[6] = 0x02 }
        assertMessage(rejects(layer2), "ヘッダーがない")
    }

    @Test
    fun `p1の読み込みはコンポーネントをすぐ断る`() {
        val e = assertThrows<WasmLoadException> { ModulePrescan.check(full, ModuleLimits.DEFAULT, null) }
        assertMessage(e, "#263")
    }

    @Test
    fun `壊れたバイナリは断る`() {
        // 途中で切ったもの（どこで切っても、例外は WasmLoadException だけ。セクションの境目で切ると正しいまま）
        var rejected = 0
        for (n in 9 until full.size) {
            val e = runCatching { ComponentLoader.load(full.copyOf(n)) }.exceptionOrNull() ?: continue
            assertTrue(e is WasmLoadException) { "$n: $e" }
            rejected++
        }
        assertTrue(rejected > full.size / 2)
        // 終わらない LEB128
        val leb = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x07, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01)
        assertMessage(rejects(leb), "長すぎる")
        // 型のセクションで、数がバイト数より多いもの
        val count = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x07, 0x05, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f, 0x00)
        assertMessage(rejects(count), "上限")
        // UTF-8 として正しくない名前（カスタム セクション）
        val utf8 = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x00, 0x02, 0x01, 0xff.toByte())
        assertMessage(rejects(utf8), "UTF-8")
        // 値のセクション（cm-values）は断る
        val value = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x0c, 0x01, 0x00)
        assertMessage(rejects(value), "cm-values")
    }

    @Test
    fun `構造の上限を超えるものは断る`() {
        assertMessage(rejects(full, ComponentLimits(moduleLimits = ModuleLimits(maxBytes = full.size - 1))), "大きすぎる")
        assertMessage(rejects(full, ComponentLimits(maxNestingDepth = 0)), "入れ子が深すぎる")
        assertMessage(rejects(full, ComponentLimits(maxImports = 1)), "importの数")
        assertMessage(rejects(full, ComponentLimits(maxCoreModules = 1)), "コアモジュールの数")
        assertMessage(rejects(full, ComponentLimits(maxNameBytes = 10)), "名前が長すぎる")
        val nestedType = wat("""(component (type (component (import "a" (instance (export "f" (func)))))))""")
        assertDoesNotThrow { ComponentParser.parse(nestedType, ComponentLimits(maxTypeDepth = 2), null) }
        assertMessage(rejects(nestedType, ComponentLimits(maxTypeDepth = 1)), "型の入れ子")
        assertMessage(rejects(full, ComponentLimits(maxTypes = 3)), "型の数")
        assertMessage(rejects(full, ComponentLimits(maxCanons = 3)), "canonの数")
        assertMessage(rejects(full, ComponentLimits(maxExports = 2)), "exportの数")
        assertMessage(rejects(full, ComponentLimits(maxVectorItems = 1)), "上限 1")
        assertMessage(rejects(full, ComponentLimits(maxAliases = 0)), "aliasの数")
        assertMessage(rejects(full, ComponentLimits(maxInstances = 1)), "インスタンスの数")
        assertMessage(rejects(full, ComponentLimits(maxComponents = 0)), "入れ子のコンポーネントの数")
        // 中の core モジュールには、p1 と同じ構造の上限（ModulePrescan）を当てる
        assertMessage(rejects(full, ComponentLimits(moduleLimits = ModuleLimits(maxFunctions = 0))), "関数の数")
        // 時間の上限
        // 時間の上限（CPU 時間の時計は粗いことがあるので、時計が進むまで回してから読む）
        val deadline = LoadDeadline(1)
        var spin = 0L
        while (deadline.elapsedNanos <= 1) spin++
        val e = assertThrows<WasmLoadException> { ComponentParser.parse(full, ComponentLimits.DEFAULT, deadline) }
        assertMessage(e, "時間の上限")
    }

    /** 1 回ずつインスタンス化する入れ子を [levels] 段重ねたもの */
    private fun chain(levels: Int): ByteArray {
        val sb = StringBuilder("(component \$root\n")
        sb.append("  (component \$c0 (core module \$m) (core instance (instantiate \$m)))\n")
        for (i in 1..levels) {
            sb.append("  (component \$c$i (alias outer \$root \$c${i - 1} (component \$p)) (instance (instantiate \$p)))\n")
        }
        sb.append("  (instance (instantiate \$c$levels))\n)")
        return wat(sb.toString())
    }

    @Test
    fun `インスタンス化の入れ子の深さに上限がある`() {
        assertEquals(61, ComponentLoader.load(chain(60)).expandedInstances)
        assertMessage(rejects(chain(70)), "インスタンス化の入れ子が深すぎる（上限 64）")
    }

    @Test
    fun `展開の確認にも時間の上限がある`() {
        val root = ComponentParser.parse(doubling(8), ComponentLimits.DEFAULT, null)
        assertEquals(256, ComponentExpansion.count(root, ComponentLimits.DEFAULT, null).coreInstances)
        val deadline = LoadDeadline(1)
        var spin = 0L
        while (deadline.elapsedNanos <= 1) spin++
        val e = assertThrows<WasmLoadException> { ComponentExpansion.count(root, ComponentLimits.DEFAULT, deadline) }
        assertMessage(e, "時間の上限")
        assertMessage(e, "コンポーネントの展開の確認")
    }

    /** [bytes] の一番外の core の型のセクションの、最初のモジュール型の宣言 */
    private fun moduleTypeDecls(bytes: ByteArray): List<CoreModuleDecl> {
        val root = ComponentParser.parse(bytes, ComponentLimits.DEFAULT, null)
        val types = root.sections.filterIsInstance<ComponentSection.CoreTypes>().single()
        return (types.items.single() as CoreDefType.Module).decls
    }

    @Test
    fun `64ビットの大きさを読む`() {
        val bytes = wat("""(component (core type (module (import "a" "m" (memory i64 1 0xffffffffffffffff)))))""")
        val memory = (moduleTypeDecls(bytes).single() as CoreModuleDecl.Import).desc as CoreExternDesc.Memory
        assertEquals(1L, memory.limits.min)
        assertEquals(-1L, memory.limits.max)
        // 10 バイト目に 64 ビットを超える分があるものは断る
        val max = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01)
        val at = (0..bytes.size - max.size).single { i -> max.indices.all { bytes[i + it] == max[it] } }
        val over = bytes.copyOf().also { it[at + 9] = 0x03 }
        assertMessage(assertThrows<WasmLoadException> { ComponentParser.parse(over, ComponentLimits.DEFAULT, null) }, "64 ビットを超える")
    }

    @Test
    fun `coreの参照の型の長い形を読む`() {
        val bytes = wat(
            """
            (component (core type (module
              (type (func))
              (import "a" "g" (global (ref null 0)))
              (import "a" "h" (global (ref 0)))
            )))
            """,
        )
        val globals = moduleTypeDecls(bytes).filterIsInstance<CoreModuleDecl.Import>().map { (it.desc as CoreExternDesc.Global).type }
        assertEquals(listOf(CoreValType(0x63, 0), CoreValType(0x64, 0)), globals)
    }

    private fun leb(v: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        do {
            var b = x and 0x7f
            x = x ushr 7
            if (x != 0) b = b or 0x80
            out.write(b)
        } while (x != 0)
        return out.toByteArray()
    }

    /** bool が [elements] 個の tuple を [tuples] 個並べた型のセクションだけのコンポーネント（検証には通らない） */
    private fun tuples(tuples: Int, elements: Int): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(leb(tuples))
        repeat(tuples) {
            body.write(0x6f)
            body.write(leb(elements))
            repeat(elements) { body.write(0x7f) }
        }
        val section = java.io.ByteArrayOutputStream()
        section.write(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x07))
        section.write(leb(body.size()))
        section.write(body.toByteArray())
        return section.toByteArray()
    }

    @Test
    fun `デコードする要素の総数に上限がある`() {
        // 1 つのベクターは上限に収まる小さな tuple でも、並べた合計で断る（掛け合わせた確保を抑える）
        val limits = ComponentLimits(maxDecodedItems = 2_500)
        assertDoesNotThrow { ComponentParser.parse(tuples(2, 1_000), limits, null) }
        val e = assertThrows<WasmLoadException> { ComponentParser.parse(tuples(3, 1_000), limits, null) }
        assertMessage(e, "デコードする要素の総数が上限 2500")
        // 既定の上限でも、1 つのベクターが上限に収まる tuple を重ねたものは、検証の前に断る
        assertMessage(
            assertThrows<WasmLoadException> { ComponentParser.parse(tuples(10, 99_000), ComponentLimits.DEFAULT, null) },
            "デコードする要素の総数",
        )
    }

    /** 2 回ずつインスタンス化する入れ子を [levels] 段重ねたもの（core のインスタンスは 2^levels 個） */
    private fun doubling(levels: Int): ByteArray {
        val sb = StringBuilder("(component \$root\n")
        sb.append("  (component \$c0 (core module \$m) (core instance (instantiate \$m)))\n")
        for (i in 1..levels) {
            sb.append("  (component \$c$i (alias outer \$root \$c${i - 1} (component \$p)) (instance (instantiate \$p)) (instance (instantiate \$p)))\n")
        }
        sb.append("  (instance (instantiate \$c$levels))\n)")
        return wat(sb.toString())
    }

    @Test
    fun `インスタンスが指数的に増えるものは断る`() {
        assertEquals(8, ComponentLoader.load(doubling(3)).expandedCoreInstances)
        // 宣言は 10 段分しかないが、展開すると 1024 個
        val bomb = doubling(10)
        assertTrue(bomb.size < 2000)
        // 既定の上限では、途中の段のコンポーネントのインスタンス（2,047 個）で先に断る
        assertMessage(rejects(bomb), "展開した後のコンポーネントのインスタンスの数")
        assertMessage(rejects(bomb, ComponentLimits(maxExpandedInstances = 10_000)), "展開した後のコアモジュールのインスタンスの数")
        assertMessage(rejects(doubling(4), ComponentLimits(maxExpandedCoreInstances = 15)), "上限 15")
    }

    /** 型のセクションを 1 つ持つコンポーネントを葉にして、2 回ずつインスタンス化する入れ子を [levels] 段重ねたもの（core のインスタンスはない） */
    private fun resourceDoubling(levels: Int): ByteArray {
        val sb = StringBuilder("(component \$root\n")
        sb.append("  (component \$c0 (type \$r (resource (rep i32))) (export \"r\" (type \$r)))\n")
        for (i in 1..levels) {
            sb.append("  (component \$c$i (alias outer \$root \$c${i - 1} (component \$p)) (instance (instantiate \$p)) (instance (instantiate \$p)))\n")
        }
        sb.append("  (instance (instantiate \$c$levels))\n)")
        return wat(sb.toString())
    }

    @Test
    fun `coreのインスタンスがなくてもコンポーネントのインスタンスが増えるものは断る`() {
        // 1 + 2 + 4 + 8 = 15 個
        val small = ComponentLoader.load(resourceDoubling(3))
        assertEquals(0, small.expandedCoreInstances)
        assertEquals(15, small.expandedInstances)
        // 1 KB ほどで、展開すると 2,047 個
        val bomb = resourceDoubling(10)
        assertTrue(bomb.size < 1_000)
        assertMessage(rejects(bomb), "展開した後のコンポーネントのインスタンスの数が上限 1000")
        assertMessage(rejects(resourceDoubling(3), ComponentLimits(maxExpandedInstances = 14)), "上限 14")
        // 型も 1 つずつ手間に数える（葉の resource の型 8 個 + ほかの宣言）
        assertMessage(rejects(resourceDoubling(3), ComponentLimits(maxExpansionSteps = 40)), "なぞる手間")
    }

    /** 要素が 0 個のセクション（id が [id]、3 バイト）を [n] 個並べたコンポーネント */
    private fun emptySections(n: Int, id: Int): ByteArray {
        val out = ByteArray(8 + 3 * n)
        byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00).copyInto(out)
        for (i in 0 until n) {
            out[8 + 3 * i] = id.toByte()
            out[8 + 3 * i + 1] = 1
            out[8 + 3 * i + 2] = 0
        }
        return out
    }

    @Test
    fun `空のセクションを並べたものはセクションの数で断る`() {
        val max = ComponentLimits.DEFAULT.maxSections
        // 上限ちょうどは、検証まで含めて時間の上限に収まる
        val ok = emptySections(max, 7)
        assertEquals(max, ComponentLoader.load(ok).root.sections.size)
        // 16 MiB 近くまで並べたもの（約 559 万個）は、パーサーがすぐ断る（検証まで行かない）
        val count = (ComponentLimits.DEFAULT.maxBytes - 8) / 3
        for (id in listOf(0, 2, 7)) {
            val big = emptySections(count, id)
            val clock = LoadDeadline(Long.MAX_VALUE)
            assertMessage(rejects(big), "セクションの数が上限 $max")
            assertTrue(clock.elapsedNanos < 2_000_000_000L) { "断るのに ${clock.elapsedNanos / 1_000_000} ms かかった" }
        }
        assertMessage(rejects(emptySections(11, 7), ComponentLimits(maxSections = 10)), "セクションの数が上限 10")
        // セクションもデコードする要素の総数に数える
        assertMessage(rejects(emptySections(11, 7), ComponentLimits(maxDecodedItems = 10)), "デコードする要素の総数")
    }

    /** データのセグメントに [dataBytes] バイトを持つ core モジュールを 1 つだけ持つコンポーネント */
    private fun bigDataComponent(dataBytes: Int): ByteArray {
        val module = java.io.ByteArrayOutputStream()
        module.write(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00))
        // メモリ 1 つ（最初 256 ページ = 16 MiB）
        module.write(byteArrayOf(0x05, 0x04, 0x01, 0x00, 0x80.toByte(), 0x02))
        val data = java.io.ByteArrayOutputStream()
        data.write(byteArrayOf(0x01, 0x00, 0x41, 0x00, 0x0b))
        data.write(leb(dataBytes))
        data.write(ByteArray(dataBytes) { (it * 31).toByte() })
        module.write(0x0b)
        module.write(leb(data.size()))
        data.writeTo(module)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x01))
        out.write(leb(module.size()))
        module.writeTo(out)
        return out.toByteArray()
    }

    @Test
    fun `16MiB近いコンポーネントも時間の上限の中で読める`() {
        val bytes = bigDataComponent(ComponentLimits.DEFAULT.maxBytes - 64)
        assertTrue(bytes.size <= ComponentLimits.DEFAULT.maxBytes)
        // 時間の上限（既定 8 秒）を超えたら load が断るので、通れば収まっている
        val c = ComponentLoader.load(bytes)
        assertEquals(1, c.coreModules.size)
    }

    @Test
    fun `wasm-toolsの検証をまたいだ時間も上限に数える`() {
        val limit = 500_000_000L
        val limits = ComponentLimits(moduleLimits = ModuleLimits(timeLimitNanos = limit))
        // 検証が終わった後の確認で断る（検証の間は止められないので、終わった後で確かめる）
        val e = assertThrows<WasmLoadException> {
            ComponentLoader.load(full, limits) { image ->
                ComponentValidator.validate(image)
                val spin = LoadDeadline(Long.MAX_VALUE)
                var n = 0L
                while (spin.elapsedNanos <= limit + 100_000_000L) n++
            }
        }
        assertMessage(e, "コンポーネントの検証、")
        // 差し替えずに同じ上限で読めば通る
        assertDoesNotThrow { ComponentLoader.load(full, limits) }
    }

    @Test
    fun `検証器のメモリの上限を超えたらWasmLoadException`() {
        // 最初の大きさより小さな上限は、内部の失敗
        assertMessage(assertThrows<WasmLoadException> { ComponentValidator.validate(full, maxMemoryPages = 1) }, "最初の大きさ")
        // 入力を読み込みきれない上限では、wasm-tools が確保に失敗して終わる（内容の失敗として、WasmLoadException になる）
        val big = bigDataComponent(8 * 1024 * 1024)
        val e = assertThrows<WasmLoadException> { ComponentValidator.validate(big, maxMemoryPages = VALIDATOR_SMALL_PAGES) }
        assertMessage(e, "out of memory")
        // 既定の上限なら通る
        assertDoesNotThrow { ComponentValidator.validate(big) }
    }

    @Test
    fun `引数とexportで渡したコンポーネントのインスタンス化も数える`() {
        val bytes = wat(
            """
            (component
              (component ${'$'}leaf (core module ${'$'}m) (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m)))
              (component ${'$'}twice
                (import "c" (component ${'$'}c))
                (instance (instantiate ${'$'}c))
                (instance (instantiate ${'$'}c))
                (instance ${'$'}x (instantiate ${'$'}c))
                (export "c2" (component ${'$'}c))
              )
              (instance ${'$'}t (instantiate ${'$'}twice (with "c" (component ${'$'}leaf))))
              (alias export ${'$'}t "c2" (component ${'$'}again))
              (instance (instantiate ${'$'}again))
            )
            """,
        )
        // twice の中で leaf を 3 回（6 個）、export して外で 1 回（2 個）
        assertEquals(8, ComponentLoader.load(bytes).expandedCoreInstances)
    }

    /**
     * export が [n] 個のインスタンス型 2 つ、それを outer alias して import する入れ子のコンポーネント（import [k] 個）、
     * それを [m] 回インスタンス化するもの。wasm-tools の検証が M × k × N に比例して重くなる形
     */
    private fun subtypeComponent(n: Int, k: Int, m: Int): ByteArray {
        val exports = (0 until n).joinToString(" ") { """(export "f$it" (func))""" }
        val imports = (0 until k).joinToString(" ") { """(import "i$it" (instance (type ${'$'}tb)))""" }
        val args = (0 until k).joinToString(" ") { """(with "i$it" (instance ${'$'}a))""" }
        val instances = (0 until m).joinToString(" ") { "(instance (instantiate ${'$'}c $args))" }
        return wat(
            """
            (component
              (type ${'$'}ta (instance $exports))
              (type ${'$'}tb (instance $exports))
              (import "wasi:a/b" (instance ${'$'}a (type ${'$'}ta)))
              (component ${'$'}c
                (alias outer 1 ${'$'}tb (type ${'$'}tb))
                $imports
              )
              $instances
            )
            """,
        )
    }

    @Test
    fun `インスタンス化の引数ごとの型の確認が膨らむ形は検証の前に断る`() {
        // N=2,000・k=100・M=100 は wasm-tools の検証に CPU で 20 秒以上かかる（約 98 KB）。検証に入る前に断る
        val bomb = subtypeComponent(n = 2000, k = 100, m = 100)
        assertTrue(bomb.size < 400_000) { "${bomb.size}" }
        var validated = false
        val e = assertThrows<WasmLoadException> {
            ComponentLoader.load(bomb, validate = { validated = true })
        }
        assertMessage(e, "検証が重すぎる")
        assertTrue(!validated)
        // 断るのは短い時間（パーサーと見積もりだけ）
        val start = System.nanoTime()
        rejects(bomb)
        assertTrue(System.nanoTime() - start < 2_000_000_000L)

        // 見積もりは、型の確認の回数（引数の総数）× 型の大きさ
        val small = subtypeComponent(n = 10, k = 3, m = 4)
        val estimate = ComponentValidationCost.estimate(ComponentParser.parse(small, ComponentLimits.DEFAULT, null))
        assertEquals(12L, estimate.checkCount)
        assertTrue(estimate.maxTypeWeight in 10L..30L) { "${estimate.maxTypeWeight}" }
        // 上限は差し替えられる。同じ形でも小さければ通る
        assertMessage(rejects(small, ComponentLimits(maxValidationCost = estimate.cost - 1)), "検証が重すぎる")
        assertEquals(0, ComponentLoader.load(small, ComponentLimits(maxValidationCost = estimate.cost)).expandedCoreInstances)
    }

    @Test
    fun `実際に使う形の見積もりは上限に十分収まる`() {
        // WASI のインターフェースを数十個 import し、1 回インスタンス化するコンポーネントの規模
        val c = subtypeComponent(n = 200, k = 60, m = 3)
        val e = ComponentValidationCost.estimate(ComponentParser.parse(c, ComponentLimits.DEFAULT, null))
        assertTrue(e.cost * 4 < ComponentLimits.DEFAULT.maxValidationCost) { "${e.cost}" }
        assertDoesNotThrow { ComponentLoader.load(c) }
    }

    private fun estimateOf(bytes: ByteArray) = ComponentValidationCost.estimate(ComponentParser.parse(bytes, ComponentLimits.DEFAULT, null))

    /** [bytes] は見積もりで断られ、検証まで行かない */
    private fun rejectedBeforeValidation(bytes: ByteArray) {
        var validated = false
        val e = assertThrows<WasmLoadException> { ComponentLoader.load(bytes, validate = { validated = true }) }
        assertMessage(e, "検証が重すぎる")
        assertTrue(!validated)
    }

    /**
     * [type] の型を 2 つ（`$ta`・`$tb`）定義し、`$tb` を [k] 個受ける入れ子のコンポーネントに、`$ta` 由来のものを渡して [m] 回
     * インスタンス化するもの。[import] は受ける側の import の記述、[arg] は渡す引数
     */
    private fun typedArgComponent(type: String, k: Int, m: Int, prelude: String, import: String, arg: String): ByteArray {
        val imports = (0 until k).joinToString(" ") { """(import "i$it" $import)""" }
        val args = (0 until k).joinToString(" ") { """(with "i$it" $arg)""" }
        val instances = (0 until m).joinToString(" ") { "(instance (instantiate ${'$'}c $args))" }
        return wat(
            """
            (component
              (type ${'$'}ta $type)
              (type ${'$'}tb $type)
              $prelude
              (component ${'$'}c
                (alias outer 1 ${'$'}tb (type ${'$'}tb))
                $imports
              )
              $instances
            )
            """,
        )
    }

    @Test
    fun `型の引数と関数の引数で大きな型を何度も確かめさせる形も検証の前に断る`() {
        // フィールド 4,000 個の record を (type (eq ..)) で 100 個 × 100 回受ける（wasm-tools で CPU 約 13 秒）
        val fields = (0 until 4000).joinToString(" ") { """(field "f$it" u32)""" }
        val record = typedArgComponent("(record $fields)", k = 100, m = 100, prelude = "", import = "(type (eq ${'$'}tb))", arg = "(type ${'$'}ta)")
        val r = estimateOf(record)
        assertEquals(10_000L, r.checkCount)
        assertTrue(r.maxTypeWeight >= 4000) { "${r.maxTypeWeight}" }
        rejectedBeforeValidation(record)

        // 引数 1,000 個の関数型を 100 個 × 100 回受ける（約 2 秒）
        val params = (0 until 1000).joinToString(" ") { """(param "p$it" u32)""" }
        val func = typedArgComponent(
            "(func $params)", k = 100, m = 100,
            prelude = """(import "g" (func ${'$'}g (type ${'$'}ta)))""", import = "(func (type ${'$'}tb))", arg = "(func ${'$'}g)",
        )
        val f = estimateOf(func)
        assertTrue(f.maxTypeWeight >= 1000) { "${f.maxTypeWeight}" }
        rejectedBeforeValidation(func)

        // 小さな形は通る（見積もりは引数 × 大きさ）
        val smallFields = (0 until 10).joinToString(" ") { """(field "f$it" u32)""" }
        val small = typedArgComponent("(record $smallFields)", k = 3, m = 2, prelude = "", import = "(type (eq ${'$'}tb))", arg = "(type ${'$'}ta)")
        val s = estimateOf(small)
        assertEquals(6L, s.checkCount)
        assertEquals(11L, s.maxTypeWeight)
    }

    @Test
    fun `入れ子のコンポーネントの import の型と、引数のないインスタンス化も見積もりに入る`() {
        val inner = (0 until 100).joinToString(" ") { """(field "f$it" u32)""" }
        // import に大きなインスタンス型を持つコンポーネントを、引数として渡す（import も逆向きに比べる）
        val asArg = wat(
            """
            (component
              (component ${'$'}x
                (import "i" (instance (type ${'$'}rr (record $inner)) (export "r" (type (eq ${'$'}rr))))))
              (component ${'$'}y (import "c" (component)))
              (instance (instantiate ${'$'}y (with "c" (component ${'$'}x))))
            )
            """,
        )
        val a = estimateOf(asArg)
        assertEquals(1L, a.checkCount)
        assertTrue(a.cost >= 101) { "${a.cost}" }
        // 引数のないインスタンス化も、そのたびに export の型を写し直すので数える（型の確認の回数には入れない）
        val instances = (0 until 10).joinToString(" ") { "(instance (instantiate ${'$'}c))" }
        val noArgs = wat("""(component (component ${'$'}c (type ${'$'}r (record $inner)) (export "r" (type ${'$'}r))) $instances)""")
        val n = estimateOf(noArgs)
        assertEquals(0L, n.checkCount)
        assertTrue(n.cost >= 10L * 101) { "${n.cost}" }
    }

    @Test
    fun `型の大きさは参照している型の中まで数える`() {
        // フィールド 100 個の record を、100 個のフィールドに持つ record（展開すると約 1 万）
        val inner = (0 until 100).joinToString(" ") { """(field "f$it" u32)""" }
        val outer = (0 until 100).joinToString(" ") { """(field "g$it" ${'$'}in)""" }
        val bytes = wat("""(component (type ${'$'}in (record $inner)) (type (record $outer)))""")
        assertEquals(1L + 100 * 101, estimateOf(bytes).maxTypeWeight)
        // インスタンス型の export の型の中まで数え、alias で引いた型も同じ大きさとして使う
        val aliased = wat(
            """
            (component
              (import "wasi:a/b" (instance ${'$'}i (type ${'$'}rr (record $inner)) (export "r" (type (eq ${'$'}rr)))))
              (alias export ${'$'}i "r" (type ${'$'}r))
              (type (list ${'$'}r))
            )
            """,
        )
        val w = estimateOf(aliased).maxTypeWeight
        // record は 101、インスタンス型はその export、list はその上に 1
        assertTrue(w in 102L..110L) { "$w" }
        // 型を付けた export も、型の確認として数える
        val ascribed = wat("""(component (import "f" (func ${'$'}f)) (export "g" (func ${'$'}f) (func)))""")
        assertEquals(1L, estimateOf(ascribed).checkCount)
    }

    @Test
    fun `ツールチェーンが作るコンポーネントの見積もりは上限に十分収まる`() {
        // WASI の filesystem に近い規模のインターフェース（record・variant・enum・flags・resource と関数 40 個）を
        // import と export に持つ world を、wasm-tools の component embed --dummy と component new でコンポーネントにする
        val fields = (0 until 12).joinToString(", ") { "f$it: u64" }
        val cases = (0 until 37).joinToString(", ") { "e$it" }
        val funcs = (0 until 40).joinToString("\n") { "    m$it: func(d: borrow<descriptor>, s: stat, o: option<list<stat>>) -> result<tuple<stat, u64>, error-code>;" }
        val wit = """
            package test:fs@0.2.0;
            interface types {
                record stat { $fields }
                enum error-code { $cases }
                flags open-flags { create, directory, exclusive, truncate }
                variant entry { file(stat), dir(list<stat>), link(string), other }
                resource descriptor {
                    open: func(f: open-flags) -> result<descriptor, error-code>;
                    read-dir: func() -> result<list<entry>, error-code>;
                }
            $funcs
            }
            world w {
                import types;
                export types;
            }
        """.trimIndent()
        val embed = WitTool.run(
            listOf("component", "embed", "--dummy", "-w", "w", "${WitTool.WORK}/w.wit", "-o", "${WitTool.WORK}/core.wasm"),
            mapOf("w.wit" to wit.toByteArray()),
            listOf("core.wasm"),
        )
        assertEquals(0, embed.exitCode) { embed.stderr }
        val made = WitTool.run(
            listOf("component", "new", "${WitTool.WORK}/core.wasm", "-o", "${WitTool.WORK}/c.wasm"),
            mapOf("core.wasm" to embed.outputs.getValue("core.wasm")),
            listOf("c.wasm"),
        )
        assertEquals(0, made.exitCode) { made.stderr }
        val c = made.outputs.getValue("c.wasm")
        val e = estimateOf(c)
        assertTrue(e.cost * 10 < ComponentLimits.DEFAULT.maxValidationCost) { "${e.checkCount} × ${e.maxTypeWeight} = ${e.cost}" }
        val root = ComponentParser.parse(c, ComponentLimits.DEFAULT, null)
        assertDoesNotThrow { ComponentValidator.validate(ValidationImage.of(c, root)) }
    }

    @Test
    fun `検証中にスレッドが割り込まれたらloadは内容の失敗にしない`() {
        ComponentValidator.warmUp()
        val bytes = full
        // 割り込まれていれば WasmTrapException（INTERRUPTED）で返し、印は立てたまま（WasmLoadException ではないので、ProgramCache は覚えない）
        Thread.currentThread().interrupt()
        try {
            val e = assertThrows<WasmTrapException> { ComponentLoader.load(bytes) }
            assertEquals(WasmTrapKind.INTERRUPTED, e.kind)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `割り込みで止まった検証はWasmTrapExceptionになる`() {
        ComponentValidator.warmUp()
        val slow = subtypeComponent(n = 2000, k = 100, m = 100)
        var failure: Throwable? = null
        var flag = false
        val t = Thread {
            failure = runCatching { ComponentValidator.validate(slow) }.exceptionOrNull()
            flag = Thread.currentThread().isInterrupted
        }
        t.start()
        Thread.sleep(500)
        val clock = System.nanoTime()
        t.interrupt()
        t.join(10_000)
        assertTrue(!t.isAlive) { "割り込んでも止まらない" }
        assertTrue(System.nanoTime() - clock < 5_000_000_000L)
        val e = failure
        assertTrue(e is WasmTrapException && e.kind == WasmTrapKind.INTERRUPTED) { "$e" }
        assertTrue(flag)
    }

    @Test
    fun `検証の失敗の位置は元のバイト列の位置にする`() {
        // core モジュールの関数の本体とカスタム セクション（写しでは落とす）の後ろに、型の誤り
        val bad = wat(
            """
            (component
              (core module ${'$'}m
                (func (export "f") (result i64) (local i32 i32 i32) i64.const 0 i64.const 1 i64.add)
                (@custom "pad" "0123456789012345678901234567890123456789")
              )
              (core instance ${'$'}i (instantiate ${'$'}m))
              (func (result u32) (canon lift (core func ${'$'}i "f")))
            )
            """,
        )
        val offset = Regex("""offset 0x([0-9a-f]+)""")
        fun offsetIn(message: String): Long = offset.find(message)!!.groupValues[1].toLong(16)
        val direct = assertThrows<WasmLoadException> { ComponentValidator.validate(bad) }.message!!
        val image = ValidationImage.of(bad, ComponentParser.parse(bad, ComponentLimits.DEFAULT, null))
        val raw = assertThrows<WasmLoadException> { ComponentValidator.validate(image.bytes) }.message!!
        // 写しの中の位置は元と違う
        assertTrue(offsetIn(raw) != offsetIn(direct)) { "$raw / $direct" }
        val loaded = rejects(bad).message!!
        assertEquals(offsetIn(direct), offsetIn(loaded)) { "$loaded / $direct" }
        assertEquals(offsetIn(direct), image.originalOffset(offsetIn(raw)))
    }

    @Test
    fun `instanceのexportに入れたコンポーネントも追って数える`() {
        val bytes = wat(
            """
            (component
              (component ${'$'}leaf (core module ${'$'}m) (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m)))
              (instance ${'$'}bag (export "c" (component ${'$'}leaf)))
              (alias export ${'$'}bag "c" (component ${'$'}c))
              (instance (instantiate ${'$'}c))
              (instance (instantiate ${'$'}c))
              (instance (instantiate ${'$'}c))
            )
            """,
        )
        val c = ComponentLoader.load(bytes)
        assertEquals(6, c.expandedCoreInstances)
        assertEquals(3, c.expandedInstances)
        // 数えた数が上限に当たる
        assertMessage(rejects(bytes, ComponentLimits(maxExpandedCoreInstances = 5)), "コアモジュールのインスタンスの数")
    }

    @Test
    fun `インスタンスの引数を入れ子のimportで受けて、そのexportのコンポーネントを数える`() {
        val bytes = wat(
            """
            (component
              (component ${'$'}leaf (core module ${'$'}m) (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m)))
              (instance ${'$'}bag (export "c" (component ${'$'}leaf)))
              (component ${'$'}user
                (import "i" (instance ${'$'}i (export "c" (component))))
                (alias export ${'$'}i "c" (component ${'$'}c))
                (instance (instantiate ${'$'}c))
                (instance (instantiate ${'$'}c))
                (instance (instantiate ${'$'}c))
              )
              (instance (instantiate ${'$'}user (with "i" (instance ${'$'}bag))))
            )
            """,
        )
        val c = ComponentLoader.load(bytes)
        // user の中で leaf を 3 回（6 個）。コンポーネントのインスタンスは bag を除いて user の 1 回と leaf の 3 回で 4 個
        assertEquals(6, c.expandedCoreInstances)
        assertEquals(4, c.expandedInstances)
        assertMessage(rejects(bytes, ComponentLimits(maxExpandedInstances = 3)), "コンポーネントのインスタンスの数")
    }

    @Test
    fun `exportが足す番号を使ってインスタンス化しても数える`() {
        // mid の添字空間: component 0 = import の c、1 = export の c2（番号を足す）、2 = 中の big（core のインスタンスが 5 個）。
        // 番号 1 を数え損なうと、big（5 個）を数えて合計が変わる
        val bytes = wat(
            """
            (component
              (component ${'$'}leaf (core module ${'$'}m) (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m)))
              (component ${'$'}mid
                (import "c" (component ${'$'}c))
                (export ${'$'}c2 "c2" (component ${'$'}c))
                (component ${'$'}big
                  (core module ${'$'}m)
                  (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m))
                  (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m))
                )
                (instance (instantiate ${'$'}c2))
              )
              (instance ${'$'}x (instantiate ${'$'}mid (with "c" (component ${'$'}leaf))))
              (alias export ${'$'}x "c2" (component ${'$'}again))
              (instance (instantiate ${'$'}again))
            )
            """,
        )
        // mid の中で c2 = leaf を 1 回（2 個）、外で export された c2 = leaf を 1 回（2 個）
        val c = ComponentLoader.load(bytes)
        assertEquals(4, c.expandedCoreInstances)
        assertEquals(3, c.expandedInstances)
    }

    @Test
    fun `入れ子のimportの後の番号でインスタンス化しても数える`() {
        // user の添字空間: component 0 = 自分の big（5 個）、1 = import の c。import が番号を足さないと、番号 1 は何も指さない
        val bytes = wat(
            """
            (component
              (component ${'$'}leaf (core module ${'$'}m) (core instance (instantiate ${'$'}m)))
              (component ${'$'}user
                (component ${'$'}big
                  (core module ${'$'}m)
                  (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m))
                  (core instance (instantiate ${'$'}m)) (core instance (instantiate ${'$'}m))
                )
                (import "c" (component ${'$'}c))
                (instance (instantiate ${'$'}c))
                (instance (instantiate ${'$'}c))
              )
              (instance (instantiate ${'$'}user (with "c" (component ${'$'}leaf))))
            )
            """,
        )
        assertEquals(2, ComponentLoader.load(bytes).expandedCoreInstances)
    }

    @Test
    fun `wasm-toolsの検証で型の誤りを断る`() {
        val bad = wat(
            """
            (component
              (core module ${'$'}m (func (export "f") (result i64) i64.const 0))
              (core instance ${'$'}i (instantiate ${'$'}m))
              (func (result u32) (canon lift (core func ${'$'}i "f")))
            )
            """,
        )
        assertMessage(rejects(bad), "検証に通らない")
    }

    @Test
    fun `有効にしていない機能は断る`() {
        // stackful の非同期（callback のない async の lift。#266 で決めるまで断る）
        val stackful = wat(
            """
            (component
              (core module ${'$'}m (func (export "f")))
              (core instance ${'$'}i (instantiate ${'$'}m))
              (func (export "f") async (canon lift (core func ${'$'}i "f") async))
            )
            """,
        )
        assertMessage(rejects(stackful), "検証に通らない")
        // core のスレッド（共有メモリ）
        val shared = wat("(component (core module (memory 1 1 shared)))")
        assertMessage(rejects(shared), "検証に通らない")
    }

    @Test
    fun `許可しないimportは断る`() {
        fun component(import: String) = wat("(component $import)")
        assertDoesNotThrow { ComponentLoader.load(component("""(import "wasi:cli/stdout@0.2.3" (instance))""")) }
        assertDoesNotThrow { ComponentLoader.load(component("""(import "kibi:kernel/events@0.1.0" (instance))""")) }
        assertMessage(rejects(component("""(import "kibi:kernel/events@0.2.0" (instance))""")), "@0.1.0")
        assertMessage(rejects(component("""(import "kibi:kernel/events" (instance))""")), "版なし")
        assertMessage(rejects(component("""(import "acme:foo/bar@1.0.0" (instance))""")), "名前空間 acme")
        assertMessage(rejects(component("""(import "plain" (instance))""")), "インターフェースの名前")
        assertMessage(rejects(component("""(import "wasi:cli/run@0.2.0" (func))""")), "インスタンスでない import（func）")
        assertMessage(rejects(component("""(import "wasi:cli/m@0.2.0" (core module))""")), "インスタンスでない import（core module）")
        assertMessage(rejects(component("""(import "wasi:cli/c@0.2.0" (component))""")), "インスタンスでない import（component）")
        // 判定は差し替えられる
        val all = ComponentImportPolicy { null }
        assertDoesNotThrow { ComponentLoader.load(component("""(import "plain" (func))"""), policy = all) }
    }

    @Test
    fun `予約したモジュール名をimportするcoreモジュールは断る`() {
        val bytes = wat("""(component (core module (import "kibi" "fuel" (func (param i64)))))""")
        assertMessage(rejects(bytes), "予約したモジュール名 kibi")
        assertDoesNotThrow { ComponentLoader.load(wat("""(component (core module (import "kibix" "fuel" (func (param i64)))))""")) }
    }

    /** [imports]（core モジュールの import の宣言）の後ろに、[last] を import する core モジュールを持つコンポーネント */
    private fun importModule(imports: String, last: String = "kibi"): ByteArray =
        wat("""(component (core module $imports (import "$last" "fuel" (func (param i64)))))""")

    private fun moduleNames(bytes: ByteArray): List<String> {
        val root = ComponentParser.parse(bytes, ComponentLimits.DEFAULT, null)
        return CoreModuleImports.moduleNames(bytes, root.allCoreModules().single())
    }

    @Test
    fun `予約したモジュール名は表やメモリのimportの後ろにあっても見つける`() {
        // import の記述の読み飛ばし（関数・テーブル・メモリ・グローバル・タグ）が 1 バイトでもずれると、後ろの import を見逃す
        val before = mapOf(
            "関数" to """(import "a" "f" (func (param i32)))""",
            "メモリ" to """(import "a" "m" (memory 1))""",
            "最大つきのメモリ" to """(import "a" "m2" (memory 300 60000))""",
            "テーブル" to """(import "a" "t" (table 1 funcref))""",
            "最大つきのテーブル" to """(import "a" "t2" (table 200 100000 externref))""",
            "不変のグローバル" to """(import "a" "g" (global i32))""",
            "可変のグローバル" to """(import "a" "g2" (global (mut f64)))""",
            "タグ" to """(import "a" "e" (tag (param i32)))""",
        )
        for ((label, import) in before) {
            val bytes = importModule(import)
            assertEquals(listOf("a", "kibi"), moduleNames(bytes), label)
            assertMessage(rejects(bytes), "予約したモジュール名 kibi")
            // 予約していない名前なら通る
            val ok = importModule(import, last = "kibix")
            assertEquals(listOf("a", "kibix"), moduleNames(ok), label)
            assertDoesNotThrow { ComponentLoader.load(ok) }
        }
        // すべてを並べた後ろでも見つける
        // （メモリは 1 つまで）
        val one = before.filterKeys { it != "最大つきのメモリ" }.values
        val all = importModule(one.joinToString(" "))
        assertEquals(List(one.size) { "a" } + "kibi", moduleNames(all))
        assertMessage(rejects(all), "予約したモジュール名 kibi")
        // 共有メモリ（スレッドは有効にしていないので検証には通らない）の後ろでも、読み飛ばしは合う
        assertEquals(listOf("a", "kibi"), moduleNames(importModule("""(import "a" "m" (memory 1 2 shared))""")))
        // 先頭にあっても、メモリの前にあっても同じ
        val first = wat("""(component (core module (import "kibi" "x" (memory 1)) (import "b" "t" (table 1 funcref))))""")
        assertEquals(listOf("kibi", "b"), moduleNames(first))
        assertMessage(rejects(first), "予約したモジュール名 kibi")
    }

    @Test
    fun `参照の型の長い形のimportも読み飛ばす`() {
        // `(ref null $t)` のテーブルとグローバル（0x63・0x64 の後ろに型の添字が付く）。wasm-tools の検証には通らない
        // （function-references は有効にしていない）ので、読み飛ばしだけを確かめる
        val bytes = wat(
            """
            (component (core module
              (type ${'$'}t (func))
              (import "a" "t" (table 1 (ref null ${'$'}t)))
              (import "a" "g" (global (ref null ${'$'}t)))
              (import "a" "h" (global (mut (ref extern))))
              (import "kibi" "fuel" (func (param i64)))
            ))
            """,
        )
        assertEquals(listOf("a", "a", "a", "kibi"), moduleNames(bytes))
    }

    @Test
    fun `インターフェースの名前を読む`() {
        assertEquals(InterfaceName("wasi", "cli", "stdout", "0.2.0"), InterfaceName.parse("wasi:cli/stdout@0.2.0"))
        assertEquals(InterfaceName("my-ns", "pkg2", "a-b", null), InterfaceName.parse("my-ns:pkg2/a-b"))
        assertEquals("0.3.0-rc-2026-02-09", InterfaceName.parse("wasi:io/streams@0.3.0-rc-2026-02-09")?.version)
        for (bad in listOf("wasi", "wasi:cli", ":cli/x", "wasi:/x", "wasi:cli/", "Wasi:cli/x", "wasi:cli/x@1", "wasi:cli/x/y", "wasi:cli/x@")) {
            assertNull(InterfaceName.parse(bad)) { bad }
        }
    }

    @Test
    fun `同時に検証しても結果が変わらない`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 16).map { i ->
                Callable {
                    if (i % 2 == 0) {
                        ComponentLoader.load(full).expandedCoreInstances.toString()
                    } else {
                        try {
                            ComponentLoader.load(async)
                            "ok"
                        } catch (e: WasmLoadException) {
                            "ng"
                        }
                    }
                }
            }
            val results = pool.invokeAll(tasks).map { it.get(60, TimeUnit.SECONDS) }
            for ((i, r) in results.withIndex()) assertEquals(if (i % 2 == 0) "2" else "ok", r)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `壊れたものは何度でも同じメッセージで断る`() {
        // 71 バイト目を 0 にすると、wasm-tools が検証に失敗する（DWARF の読み直しのログも出る）。load が成功する壊し方だと確かめにならない
        val bad = full.copyOf().also { it[70] = 0 }
        val first = rejects(bad).message!!
        assertMessage(rejects(bad), "検証に通らない")
        assertEquals(first, rejects(bad).message)
        // 時刻つきのログの行は、メッセージに入らない（秒をまたいでも変わらない）
        assertTrue(!first.contains("WARN") && !Regex("""\d{4}-\d{2}-\d{2}T""").containsMatchIn(first)) { first }
    }

    @Test
    fun `検証器の標準エラー出力から理由だけを取り出す`() {
        val log = "[2026-11-13T00:36:39Z WARN ] failed to parse DWARF information: function section is absent (at offset 0x5d)"
        assertEquals("unknown function 0", ComponentValidator.reason("$log\nerror: unknown function 0\n"))
        assertEquals("unknown function 0", ComponentValidator.reason("error: unknown function 0\n"))
        assertEquals("a\nb", ComponentValidator.reason("[2026-11-13T00:36:39Z INFO  ] x\nerror: a\nb"))
        assertEquals("", ComponentValidator.reason(log))
    }

    @Test
    fun `検証用の写しはcoreモジュールの中身を確かめない`() {
        val root = ComponentParser.parse(full, ComponentLimits.DEFAULT, null)
        val image = ValidationImage.of(full, root).bytes
        val parsed = ComponentParser.parse(image, ComponentLimits.DEFAULT, null)
        // コンポーネントのセクションは同じ（core モジュールとカスタム セクションの範囲だけが変わる）
        assertEquals(
            root.sections.filter { it !is ComponentSection.CoreModule && it !is ComponentSection.Nested }.map { it::class to (it as? ComponentSection.Imports)?.items },
            parsed.sections.filter { it !is ComponentSection.CoreModule && it !is ComponentSection.Nested }.map { it::class to (it as? ComponentSection.Imports)?.items },
        )
        assertEquals(root.allCoreModules().size, parsed.allCoreModules().size)
        assertDoesNotThrow { ComponentValidator.validate(image) }
        // core モジュールの中身（関数の本体、カスタム セクションの壊れ）は、ここでは見ない（Endive が読み込むときに確かめる。#263）
        val badBody = wat("(component (core module (func (result i32) i64.const 0)))")
        assertDoesNotThrow { ComponentLoader.load(badBody) }
        assertThrows<WasmLoadException> { ComponentValidator.validate(badBody) }
    }

    @Test
    fun `取り出したcoreモジュールは元のバイト列の中を指す`() {
        val c = ComponentLoader.load(full)
        val range = c.coreModules.first()
        assertArrayEquals(full.copyOfRange(range.offset, range.end), c.coreModuleBytes(range))
        assertTrue(c.bytes === full || c.bytes.contentEquals(full))
    }

    private companion object {
        /** wasm-tools の最初のメモリ（37 ページ）より大きく、8 MiB の入力は読み込みきれない上限（64 ページ = 4 MiB） */
        const val VALIDATOR_SMALL_PAGES = 64
    }
}
