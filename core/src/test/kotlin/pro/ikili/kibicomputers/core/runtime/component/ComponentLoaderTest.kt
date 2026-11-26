package pro.ikili.kibicomputers.core.runtime.component

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
import pro.ikili.kibicomputers.core.runtime.validation.ValidationWatchdog
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
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
        // コンポーネントは Program.loadExecutable が見分けて ComponentLoader で読む（#687）。p1 の読み込みに来たら version で断る
        val e = assertThrows<WasmLoadException> { ModulePrescan.check(full, ModuleLimits.DEFAULT, null) }
        assertMessage(e, "version 1")
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
        // 割り込みで止まらない差し替えの検証は、見張りが時間切れにし、終わった後で断る
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
              (import "wasi:a/b@0.2.0" (instance ${'$'}a (type ${'$'}ta)))
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
              (import "wasi:a/b@0.2.0" (instance ${'$'}i (type ${'$'}rr (record $inner)) (export "r" (type (eq ${'$'}rr)))))
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

    /** WAT の一番外の `(component ...)` の中の、トップレベルの形（括弧の対応で切り出す。文字列とコメントは飛ばす） */
    private fun topLevelForms(text: String): List<String> {
        val forms = ArrayList<String>()
        var i = text.indexOf("(component") + "(component".length
        var depth = 0
        var start = -1
        var inString = false
        while (i < text.length) {
            val ch = text[i]
            when {
                inString -> if (ch == '\\') i++ else if (ch == '"') inString = false
                ch == '"' -> inString = true
                ch == ';' && text.startsWith(";;", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                ch == '(' && text.startsWith("(;", i) -> i = text.indexOf(";)", i) + 1
                ch == '(' -> {
                    if (depth == 0) start = i
                    depth++
                }
                ch == ')' -> {
                    if (depth == 0) break
                    depth--
                    if (depth == 0) forms += text.substring(start, i + 1)
                }
            }
            i++
        }
        return forms
    }

    /**
     * wac の plug と同じ形の合成。filesystem に近い規模のインターフェースを [k] 個 import して同じものを export する
     * コンポーネント（ツールチェーンで作る）を、前のインスタンスの export を次の引数にして [n] 回つなぐ
     */
    private fun composedComponent(k: Int, n: Int): ByteArray {
        val fields = (0 until 12).joinToString(", ") { "f$it: u64" }
        val cases = (0 until 37).joinToString(", ") { "e$it" }
        val funcs = (0 until 40).joinToString("\n") { "m$it: func(d: borrow<descriptor>, s: stat, o: option<list<stat>>) -> result<tuple<stat, u64>, error-code>;" }
        val interfaces = (0 until k).joinToString("\n") { j ->
            """
            interface i$j {
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
            """
        }
        val world = (0 until k).joinToString("\n") { "import i$it; export i$it;" }
        val wit = "package test:fs@0.2.0;\n$interfaces\nworld w {\n$world\n}"
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
        val printed = WitTool.run(listOf("print", "${WitTool.WORK}/c.wasm"), mapOf("c.wasm" to made.outputs.getValue("c.wasm")))
        assertEquals(0, printed.exitCode) { printed.stderr }
        val text = printed.stdout.trim()
        // 外側も同じ型で import する（部品の先頭の型・import・alias を写す。import は instance 0..k-1 になる）
        val prelude = topLevelForms(text).takeWhile { it.startsWith("(type") || it.startsWith("(import") || it.startsWith("(alias") }
        val out = StringBuilder("(component\n")
        prelude.forEach { out.append(it).append('\n') }
        out.append("(component ${'$'}c ").append(text.removePrefix("(component")).append('\n')
        for (x in 1..n) {
            val args = (0 until k).joinToString(" ") { j ->
                val from = if (x == 1) "$j" else "${'$'}e${x - 1}_$j"
                """(with "test:fs/i$j@0.2.0" (instance $from))"""
            }
            out.append("(instance ${'$'}x$x (instantiate ${'$'}c $args))\n")
            for (j in 0 until k) out.append("""(alias export ${'$'}x$x "test:fs/i$j@0.2.0" (instance ${'$'}e${x}_$j))""").append('\n')
        }
        return wat(out.append(")").toString())
    }

    @Test
    fun `wacで合成した形の見積もりの余裕`() {
        // 実物の確かめ（#666・#751。ADR 0262 の「検証の時間の見積もり」）。見積もりは引数ごとに部品のインターフェースの型の大きさ
        // （最大 3,620）を数えるので、引数の総数に比例して大きくなる。インスタンス化で作ったインスタンスの型は export だけで、
        // 写し直しは違う宣言ごとに数える（#751）。インターフェース 4 個 × 4 段は上限の 1/20 ほど
        val small = composedComponent(k = 4, n = 4)
        val e = estimateOf(small)
        assertTrue(e.cost * 4 < ComponentLimits.DEFAULT.maxValidationCost) { "${e.checkCount} × ${e.maxTypeWeight} = ${e.cost}" }
        assertDoesNotThrow { ComponentLoader.load(small, policy = ComponentImportPolicy { null }) }
        // 20 個 × 20 段（518 KB。#751 の前は見積もり 745 万で断っていた）も、既定の上限で通る（約 173 万。実際の検証は CPU 0.5 秒ほど）
        val large = composedComponent(k = 20, n = 20)
        val l = estimateOf(large)
        assertTrue(l.cost < ComponentLimits.DEFAULT.maxValidationCost) { "${l.checkCount} × ${l.maxTypeWeight} = ${l.cost}（写し直し ${l.remapCost}）" }
        // 写し直しは、展開した大きさ（段ごとに export のインターフェース 20 個 × 3,620）ではなく、違う宣言の大きさで数える
        assertTrue(l.remapCost * 5 < 20L * 20 * 3620) { "${l.remapCost}" }
        assertDoesNotThrow { ComponentLoader.load(large, policy = ComponentImportPolicy { null }) }
    }

    @Test
    fun `写し直しは同じ宣言を1回のインスタンス化の中で1回だけ数える`() {
        // フィールド 100 個の record を 100 個のフィールドに持つ record（展開すると約 1 万、宣言は 2 つで約 200）を export する
        val inner = (0 until 100).joinToString(" ") { """(field "f$it" u32)""" }
        val outer = (0 until 100).joinToString(" ") { """(field "g$it" ${'$'}r0)""" }
        val c = """(component ${'$'}c (type ${'$'}r0x (record $inner)) (export ${'$'}r0 "r0" (type ${'$'}r0x)) (type ${'$'}r1 (record $outer)) (export "r" (type ${'$'}r1)))"""
        val instances = (0 until 10).joinToString(" ") { "(instance (instantiate ${'$'}c))" }
        val e = estimateOf(wat("(component $c $instances)"))
        assertEquals(0L, e.checkCount)
        // 1 回あたり約 300（export した r0 の 101 と、r1 の宣言 101 とその中の r0 の 101、コンポーネントの宣言）。展開した大きさ（約 1 万）ではない
        assertTrue(e.remapCost in 10L * 202..10L * 400) { "${e.remapCost}" }
        assertEquals(e.cost, e.remapCost)

        // 別のインスタンス化で作ったインスタンスは、同じ宣言から来ても別の型（wasmparser が resource を置き換えて写し直す）なので、
        // 重なりを除かない。c を 2 回インスタンス化して両方を export する d を、10 回インスタンス化する
        val d = """(component ${'$'}d $c (instance ${'$'}x1 (instantiate ${'$'}c)) (instance ${'$'}x2 (instantiate ${'$'}c)) (export "a" (instance ${'$'}x1)) (export "b" (instance ${'$'}x2)))"""
        val outerInstances = (0 until 10).joinToString(" ") { "(instance (instantiate ${'$'}d))" }
        val twice = estimateOf(wat("(component $d $outerInstances)"))
        val perC = e.remapCost / 10
        assertTrue(twice.remapCost >= 2 * perC + 10 * 2 * perC) { "${twice.remapCost} / $perC" }
    }

    /** フィールド [fields] 個の record を [exports] 回 `(export "aN" (type (eq ..)))` するインスタンス型 `$it` */
    private fun eqInstanceType(fields: Int, exports: Int, resource: Boolean = false): String {
        val record = (0 until fields).joinToString(" ") { """(field "f$it" u32)""" }
        val eqs = (0 until exports).joinToString(" ") { """(export "a$it" (type (eq ${'$'}rr)))""" }
        val res = if (resource) """(export "res" (type (sub resource)))""" else ""
        return "(type ${'$'}it (instance (type ${'$'}rr (record $record)) $eqs $res))"
    }

    /** 入れ子のコンポーネントが、[eqInstanceType] を import する。その入れ子のコンポーネントを export するコンポーネントを [m] 回インスタンス化する */
    private fun nestedComponentExport(fields: Int, exports: Int, m: Int): ByteArray {
        val instances = (0 until m).joinToString(" ") { "(instance (instantiate ${'$'}c))" }
        return wat(
            """
            (component
              ${eqInstanceType(fields, exports)}
              (component ${'$'}c
                (alias outer 1 ${'$'}it (type ${'$'}it))
                (component ${'$'}n (alias outer 1 ${'$'}it (type ${'$'}it)) (import "i" (instance (type ${'$'}it))))
                (export "n" (component ${'$'}n))
              )
              $instances
            )
            """,
        )
    }

    /** [eqInstanceType] を import に持つコンポーネント型を import し、空のコンポーネントを渡して [m] 回インスタンス化する */
    private fun componentTypeImport(fields: Int, exports: Int, m: Int): ByteArray {
        val instances = (0 until m).joinToString(" ") { "(instance (instantiate ${'$'}c (with \"k\" (component ${'$'}empty))))" }
        return wat(
            """
            (component
              ${eqInstanceType(fields, exports)}
              (type ${'$'}ct (component (alias outer 1 ${'$'}it (type ${'$'}it)) (import "i" (instance (type ${'$'}it)))))
              (component ${'$'}c (alias outer 1 ${'$'}ct (type ${'$'}ct)) (import "k" (component (type ${'$'}ct))))
              (component ${'$'}empty)
              $instances
            )
            """,
        )
    }

    /** [eqInstanceType] を型として export するコンポーネントを（import はどこにもない）[m] 回インスタンス化する */
    private fun eqTypeExport(fields: Int, exports: Int, m: Int): ByteArray {
        val instances = (0 until m).joinToString(" ") { "(instance (instantiate ${'$'}c))" }
        return wat(
            """
            (component
              ${eqInstanceType(fields, exports)}
              (component ${'$'}c (alias outer 1 ${'$'}it (type ${'$'}it)) (export "t" (type ${'$'}it)))
              $instances
            )
            """,
        )
    }

    @Test
    fun `写し直しで検証が膨らむ形は今までどおり検証の前に断る`() {
        // どれも wasm-tools の検証に CPU で数秒かかる（#751 のレビューで実測。フィールド 900 個 × export 900 個を 60 回で 14 KB）
        // 入れ子のコンポーネントを export する（wasmparser は import も写し直す）
        val nested = nestedComponentExport(fields = 900, exports = 900, m = 60)
        assertTrue(nested.size < 100_000) { "${nested.size}" }
        val n = estimateOf(nested)
        assertTrue(n.remapCost >= 60L * 900 * 900 / 2) { "${n.remapCost}" }
        rejectedBeforeValidation(nested)
        // コンポーネント型を import する（引数ごとに、受ける側の import の型を写し直す。渡すのは空のコンポーネント）
        val componentType = componentTypeImport(fields = 900, exports = 900, m = 60)
        val ct = estimateOf(componentType)
        assertEquals(60L, ct.checkCount)
        assertTrue(ct.remapCost >= 60L * 900 * 900 / 2) { "${ct.remapCost}" }
        rejectedBeforeValidation(componentType)
        // 同じ型を何度も eq で export するインスタンス型（eq は export ごとに別の id。1 回のインスタンス化の中でも別々に写し直す）
        val eq = eqTypeExport(fields = 900, exports = 900, m = 60)
        val q = estimateOf(eq)
        assertTrue(q.remapCost >= 60L * 900 * 900 / 2) { "${q.remapCost}" }
        rejectedBeforeValidation(eq)
    }

    @Test
    fun `resource を定義する型は、import・exportの宣言のたびに写し直しを数える`() {
        // #751 のレビュー: wasmparser は resource を定義するインスタンス型を import するたび・型宣言の中で export するたびに、
        // その型の export を新しい Remapping で全部写し直す。インスタンス化がなくても、宣言の数に比例して検証が膨らむ
        // （フィールド 900 個 × export 900 個の型を 20 か所で宣言して CPU 約 3 秒）
        val n = 20
        val withResource = eqInstanceType(fields = 900, exports = 900, resource = true)
        // (1) 入れ子のコンポーネントが、それぞれ import する
        val nested = (0 until n).joinToString(" ") { "(component (alias outer 1 ${'$'}it (type ${'$'}t)) (import \"i\" (instance (type ${'$'}t))))" }
        // (2) コンポーネント型の中で import する
        val inComponentType = (0 until n).joinToString(" ") {
            "(type (component (alias outer 1 ${'$'}it (type ${'$'}t)) (import \"i\" (instance (type ${'$'}t)))))"
        }
        // (3) インスタンス型の中で export する
        val inInstanceType = (0 until n).joinToString(" ") {
            "(type (instance (alias outer 1 ${'$'}it (type ${'$'}t)) (export \"i\" (instance (type ${'$'}t)))))"
        }
        for (body in listOf(nested, inComponentType, inInstanceType)) {
            val bytes = wat("(component $withResource $body)")
            assertTrue(bytes.size < 100_000) { "${bytes.size}" }
            val e = estimateOf(bytes)
            assertTrue(e.remapCost >= n * 900L * 900 / 2) { "${e.remapCost}" }
            rejectedBeforeValidation(bytes)
        }
        // resource がなければ、wasmparser は宣言では写し直さない（見積もりは 0 のまま）
        val plain = eqInstanceType(fields = 900, exports = 900)
        val noResource = wat("(component $plain $nested)")
        assertEquals(0L, estimateOf(noResource).remapCost)
    }

    /** 名前 [names] 個の enum を [exports] 回 eq で export するインスタンス型 `$it`（[resource] なら resource も 1 つ export する） */
    private fun enumInstanceType(names: Int, exports: Int, resource: Boolean = false): String {
        val cases = (0 until names).joinToString(" ") { "\"e$it\"" }
        val eqs = (0 until exports).joinToString(" ") { """(export "a$it" (type (eq ${'$'}en)))""" }
        val res = if (resource) """(export "res" (type (sub resource)))""" else ""
        return "(type ${'$'}it (instance (type ${'$'}en (enum $cases)) $eqs $res))"
    }

    @Test
    fun `写し直しの大きさは、型の大きさの上限の100万で止めない`() {
        // #935 のレビュー: enum の名前の数は wasmparser の型の大きさに入らないので、wasmparser は断らないまま、写し直しの手間が
        // 100 万を超える。100 万で止めると、見積もりは上限の半分で止まり、検証が秒単位になる
        // （名前 3,000 個 × eq 3,000 回で、import 1 回が CPU 1.75 秒、インスタンス化 1 回が 1.6 秒）
        val n = 3_000
        // (1) resource を定義する型を 1 回 import するだけ（インスタンス化なし）
        val imported = wat("(component ${enumInstanceType(n, n, resource = true)} (import \"i\" (instance (type ${'$'}it))))")
        // (2) その型（resource なし）を export するコンポーネントを 1 回インスタンス化する
        val instantiated = wat(
            "(component ${enumInstanceType(n, n)} (component ${'$'}c (alias outer 1 ${'$'}it (type ${'$'}it)) " +
                "(export \"t\" (type ${'$'}it))) (instance (instantiate ${'$'}c)))",
        )
        for (bytes in listOf(imported, instantiated)) {
            assertTrue(bytes.size < 100_000) { "${bytes.size}" }
            val e = estimateOf(bytes)
            assertTrue(e.remapCost >= n.toLong() * n) { "${e.remapCost}" }
            rejectedBeforeValidation(bytes)
        }
        // 小さい enum なら今までどおり通る（展開した大きさを数えるだけ）
        val small = wat("(component ${enumInstanceType(100, 100, resource = true)} (import \"i\" (instance (type ${'$'}it))))")
        val s = estimateOf(small)
        assertTrue(s.remapCost in 100L * 100..2L * 101 * 101) { "${s.remapCost}" }
    }

    @Test
    fun `型の確認の大きさも、enumの名前を数えて100万で止めない`() {
        // #935 のレビュー: enum の名前の数は wasmparser の型の大きさに入らないが、型の確認は名前を1つずつ比べる（確認は覚えない）。
        // 名前 6,000 個の enum を 150 個ずつ 2 段の tuple にして、record で型を付けた export の確認をさせる（約 80 KB）。
        // 確認の大きさを 100 万で止めると、見積もりは 100 万のまま通り、検証が秒単位になる
        val names = (0 until 6_000).joinToString(" ") { "\"e$it\"" }
        fun record(p: String) =
            "(type ${'$'}en$p (enum $names)) " +
                "(type ${'$'}t1$p (tuple ${(0 until 150).joinToString(" ") { "${'$'}en$p" }})) " +
                "(type ${'$'}t2$p (tuple ${(0 until 150).joinToString(" ") { "${'$'}t1$p" }})) " +
                "(type ${'$'}b$p (record (field \"x\" ${'$'}t2$p)))"
        val bytes = wat("(component ${record("0")} ${record("1")} (export \"b\" (type ${'$'}b0) (type (eq ${'$'}b1))))")
        assertTrue(bytes.size < 200_000) { "${bytes.size}" }
        val e = estimateOf(bytes)
        assertEquals(1L, e.checkCount)
        assertTrue(e.cost > 150L * 150 * 6_000 / 2) { "${e.cost}" }
        // 型の大きさの最大は、今までどおり 100 万で止める
        assertTrue(e.maxTypeWeight <= 1_000_000L) { "${e.maxTypeWeight}" }
        rejectedBeforeValidation(bytes)
    }

    @Test
    fun `写して作った型が多く、同じ宣言の連なりを参照する形の見積もりは、入力の大きさの2乗の手間にならない`() {
        // #935 のレビュー: 写して作った型（export の `(type (eq ..))`）ごとに、参照先の宣言の連なりをたどり直すと、
        // 型の数 C × 連なりの長さ N の手間になり、N = C = 20,000（約 500 KB）で 30 秒以上かかった。
        // 見積もりは手数で抑え、上限を超えたら、すぐ断る
        val n = 20_000
        val chain = StringBuilder("(type \$r0 (record (field \"a\" u32))) ")
        for (k in 1 until n) chain.append("(type \$r$k (record (field \"a\" \$r${k - 1}))) ")
        val inner = StringBuilder("(alias outer 1 \$r${n - 1} (type \$x)) ")
        for (k in 0 until n) inner.append("(type \$c$k (record (field \"a\" \$x))) ")
        for (k in 0 until n) inner.append("(export \"e$k\" (type (eq \$c$k))) ")
        inner.append("(export \"res\" (type (sub resource)))")
        val bytes = wat("(component $chain (type \$it (instance $inner)) (import \"i\" (instance (type \$it))))")
        assertTrue(bytes.size < 1_000_000) { "${bytes.size}" }
        val root = ComponentParser.parse(bytes, ComponentLimits.DEFAULT, null)
        val e = assertThrows<WasmLoadException> { ComponentValidationCost.check(root, ComponentLimits.DEFAULT) }
        assertMessage(e, "検証が重すぎる")
        // 手数で断ったと分かる（見積もりの値は途中までのものなので出さない）。実時間は見ない（共有のランナーで揺れる。
        // 手数が上限で決定的に止まることが、この形が 2 乗にならない根拠）
        assertMessage(e, "手数")
    }

    @Test
    fun `写し直しの見積もりは上限をまたいでも、断る側に外れない`() {
        // 見積もりが上限を超えたら、check は写し直しの大きさをたどらずに展開した大きさに切り替える。切り替えても、
        // estimate（最後までたどる）が上限を超える形は、check でも断り、超えない形は通る
        val shapes = listOf(
            nestedComponentExport(fields = 30, exports = 30, m = 5),
            componentTypeImport(fields = 30, exports = 30, m = 5),
            eqTypeExport(fields = 30, exports = 30, m = 5),
        )
        for (bytes in shapes) {
            val root = ComponentParser.parse(bytes, ComponentLimits.DEFAULT, null)
            val full = ComponentValidationCost.estimate(root).cost
            assertTrue(full >= 5L * 30 * 30 / 2) { "$full" }
            assertDoesNotThrow { ComponentValidationCost.check(root, ComponentLimits(maxValidationCost = full)) }
            for (limit in listOf(full - 1, full / 2, full / 100, 1L)) {
                val e = assertThrows<WasmLoadException> { ComponentValidationCost.check(root, ComponentLimits(maxValidationCost = limit)) }
                assertMessage(e, "検証が重すぎる")
            }
        }
    }

    @Test
    fun `同じ型への参照が多い宣言の写し直しは、見積もりが上限に収まれば手数で断らない`() {
        // #935 のレビュー: 手数を、参照を積むときと取り出すときの両方で数えていたので、同じ型への参照が多い宣言
        // （`(list u32)` への参照 5,000 個の tuple）では、手数が写し直しの見積もりの約 2 倍になり、見積もり（約 125 万）が
        // 上限（200 万）に収まるのに断っていた。wasm-tools の検証は通り、CPU 0.3 秒ほど
        val refs = (0 until 5_000).joinToString(" ") { "${'$'}l" }
        val records = (0 until 50).joinToString(" ") { """(type ${'$'}r$it (record (field "a" ${'$'}xx)))""" }
        val eqs = (0 until 50).joinToString(" ") { """(export "e$it" (type (eq ${'$'}r$it)))""" }
        val types = (0 until 5).joinToString(" ") {
            """(type ${'$'}it$it (instance (alias outer 1 ${'$'}x (type ${'$'}xx)) $records $eqs (export "res" (type (sub resource)))))"""
        }
        val nested = (0 until 5).joinToString(" ") {
            """(component (alias outer 1 ${'$'}it$it (type ${'$'}t)) (import "i" (instance (type ${'$'}t))))"""
        }
        val bytes = wat("(component (type ${'$'}l (list u32)) (type ${'$'}x (tuple $refs)) $types $nested)")
        val root = ComponentParser.parse(bytes, ComponentLimits.DEFAULT, null)
        val e = ComponentValidationCost.estimate(root)
        assertTrue(e.cost in ComponentLimits.DEFAULT.maxValidationCost / 2..ComponentLimits.DEFAULT.maxValidationCost) { "${e.cost}" }
        // 手数は写し直しの見積もりを超えない（参照 1 本で 1 手）
        assertTrue(e.work <= e.remapCost) { "work ${e.work}, remap ${e.remapCost}" }
        assertDoesNotThrow { ComponentValidationCost.check(root, ComponentLimits.DEFAULT) }
        assertDoesNotThrow { ComponentLoader.load(bytes) }
    }

    @Test
    fun `core モジュールを渡すインスタンス化の確認は、それまでの型の大きさの最大で数える`() {
        // #935 のレビュー: core モジュールの引数（`(with "m" (core module $m))`）は添字空間をなぞらないので必ず引けず、
        // それまでに見た型の大きさの最大で数える。最大が core の型（モジュール型）のとき、100 万で止めない展開した大きさの最大
        // （値の型・インスタンス型などでしか更新していなかった）で数えて小さくなり、export 2,000 個のモジュール型を受ける引数
        // 100 個 × 10 回（約 40 KB）を通していた。main と同じく検証の前に断る
        val n = 2_000
        val funcs = (0 until n).joinToString(" ") { """(func (export "f$it"))""" }
        val decls = (0 until n).joinToString(" ") { """(export "f$it" (func))""" }
        val imports = (0 until 100).joinToString(" ") { """(import "i$it" (core module (type ${'$'}mt)))""" }
        val args = (0 until 100).joinToString(" ") { """(with "i$it" (core module ${'$'}m))""" }
        val instances = (0 until 10).joinToString(" ") { "(instance (instantiate ${'$'}c $args))" }
        val bytes = wat(
            """
            (component
              (core module ${'$'}m $funcs)
              (component ${'$'}c (core type ${'$'}mt (module $decls)) $imports)
              $instances
            )
            """,
        )
        val e = estimateOf(bytes)
        assertEquals(1_000L, e.checkCount)
        assertTrue(e.maxTypeWeight > n) { "${e.maxTypeWeight}" }
        // 確認ごとに、それまでの型の大きさの最大（モジュール型の大きさ）
        assertTrue(e.cost - e.remapCost >= 1_000L * e.maxTypeWeight) { "cost ${e.cost}, remap ${e.remapCost}, max ${e.maxTypeWeight}" }
        rejectedBeforeValidation(bytes)
    }

    /** フィールド [fields] 個の record を [exports] 回 export するコンポーネント `$c` */
    private fun recordExporter(fields: Int, exports: Int): String {
        val record = (0 until fields).joinToString(" ") { """(field "f$it" u32)""" }
        val ex = (0 until exports).joinToString(" ") { """(export "a$it" (type ${'$'}r))""" }
        return "(component ${'$'}c (type ${'$'}r (record $record)) $ex)"
    }

    /** `$src` のコンポーネントをインスタンス化して、そのインスタンスを export するコンポーネント `$d` を [m] 回インスタンス化する */
    private fun instantiateViaOuter(m: Int): String {
        val instances = (0 until m).joinToString(" ") { "(instance (instantiate ${'$'}d))" }
        return """
            (component ${'$'}d
              (alias outer 1 ${'$'}src (component ${'$'}cc))
              (instance ${'$'}x (instantiate ${'$'}cc))
              (export "x" (instance ${'$'}x))
            )
            $instances
        """
    }

    @Test
    fun `export し直したコンポーネントやaliasで引いたコンポーネントのインスタンスも、exportの写し直しを数える`() {
        // #751 のレビュー: export し直したコンポーネントや、インスタンスの export の alias で引いたコンポーネントを
        // インスタンス化したインスタンスの型が引けず、その export の写し直しを小さく数えていた（検証に 8 秒以上かかる形を通していた）
        val reexported = wat(
            """
            (component
              ${recordExporter(fields = 900, exports = 900)}
              (export ${'$'}src "c" (component ${'$'}c))
              ${instantiateViaOuter(m = 60)}
            )
            """,
        )
        val viaAlias = wat(
            """
            (component
              ${recordExporter(fields = 900, exports = 900)}
              (component ${'$'}w (alias outer 1 ${'$'}c (component ${'$'}cc)) (export "c" (component ${'$'}cc)))
              (instance ${'$'}wi (instantiate ${'$'}w))
              (alias export ${'$'}wi "c" (component ${'$'}src))
              ${instantiateViaOuter(m = 60)}
            )
            """,
        )
        // 比べる元: 同じコンポーネントを直接 outer alias する（前から断っていた）
        val direct = wat(
            """
            (component
              ${recordExporter(fields = 900, exports = 900)}
              (alias outer 0 ${'$'}c (component ${'$'}src))
              ${instantiateViaOuter(m = 60)}
            )
            """,
        )
        val d = estimateOf(direct)
        for (bytes in listOf(reexported, viaAlias)) {
            assertTrue(bytes.size < 100_000) { "${bytes.size}" }
            val e = estimateOf(bytes)
            assertTrue(e.remapCost >= 60L * 900 * 900 / 2) { "${e.remapCost}" }
            assertTrue(e.cost >= d.cost) { "${e.cost} < ${d.cost}" }
            rejectedBeforeValidation(bytes)
        }
        rejectedBeforeValidation(direct)
    }

    @Test
    fun `importしたコンポーネントのインスタンスの型はexportだけの大きさ`() {
        // 大きな import（フィールド 100 個の record）と小さな export を持つコンポーネント型を import し、インスタンス化したものを引数に渡す。
        // インスタンスの型は export だけ（型を引けずに、それまでの最大の大きさとはしない）
        val inner = (0 until 100).joinToString(" ") { """(field "f$it" u32)""" }
        val instances = (0 until 10).joinToString(" ") { "(instance (instantiate ${'$'}y (with \"j\" (instance ${'$'}x))))" }
        val bytes = wat(
            """
            (component
              (type ${'$'}ct (component (type ${'$'}rr (record $inner)) (import "i" (type (eq ${'$'}rr))) (export "e" (func))))
              (import "k" (component ${'$'}k (type ${'$'}ct)))
              (type ${'$'}big (record $inner))
              (component ${'$'}y (import "j" (instance)))
              (instance ${'$'}x (instantiate ${'$'}k (with "i" (type ${'$'}big))))
              $instances
            )
            """,
        )
        val e = estimateOf(bytes)
        assertEquals(11L, e.checkCount)
        // big を渡す 1 回（約 100）と、x を渡す 10 回（それぞれ数個）。x の型に k の import（約 100）を入れると 1,000 を超える
        assertTrue(e.cost - e.remapCost in 101L..300L) { "${e.cost} - ${e.remapCost}" }
    }

    @Test
    fun `型を付けたexportは付けた型で写し直しを数える`() {
        // #751 のレビュー: 実物（同じ record を 900 回 export したインスタンス）ではなく、付けた型（`(type (eq ..))` を 900 回 export する
        // インスタンス型。export ごとに別の型）を写し直す。実物で数えると、検証に 7 秒以上かかる形を通していた
        val record = (0 until 900).joinToString(" ") { """(field "f$it" u32)""" }
        val bag = (0 until 900).joinToString(" ") { """(export "a$it" (type ${'$'}r))""" }
        val instances = (0 until 60).joinToString(" ") { "(instance (instantiate ${'$'}d))" }
        val bytes = wat(
            """
            (component
              ${eqInstanceType(fields = 900, exports = 900)}
              (component ${'$'}d
                (alias outer 1 ${'$'}it (type ${'$'}t))
                (type ${'$'}r (record $record))
                (instance ${'$'}bag $bag)
                (export "x" (instance ${'$'}bag) (instance (type ${'$'}t)))
              )
              $instances
            )
            """,
        )
        assertTrue(bytes.size < 100_000) { "${bytes.size}" }
        val e = estimateOf(bytes)
        assertTrue(e.remapCost >= 60L * 900 * 900 / 2) { "${e.remapCost}" }
        rejectedBeforeValidation(bytes)
    }

    /**
     * t0 は export 1 個のインスタンス型、t(k+1) は t(k) のインスタンスを export する、を [depth] 段。
     * 一番下の t0 は、宣言を共有して 2 倍ずつ膨らむ record（[levels] 段。展開すると約 2^[levels] 倍、宣言は [levels] 個）を export する。
     * 最後の型を export するコンポーネントを 10 回インスタンス化する
     */
    private fun deepInstanceTypes(depth: Int, levels: Int): ByteArray {
        val types = StringBuilder()
        if (levels > 0) {
            types.append("(type ${'$'}r0 (record (field \"f\" u32)))\n")
            for (k in 1..levels) types.append("(type ${'$'}r$k (record (field \"a\" ${'$'}r${k - 1}) (field \"b\" ${'$'}r${k - 1})))\n")
            types.append("(type ${'$'}t0 (instance (alias outer 1 ${'$'}r$levels (type ${'$'}r)) (export \"x\" (type (eq ${'$'}r)))))\n")
        } else {
            types.append("(type ${'$'}t0 (instance (type ${'$'}r (record (field \"f\" u32))) (export \"x\" (type (eq ${'$'}r)))))\n")
        }
        for (k in 1..depth) {
            types.append("(type ${'$'}t$k (instance (alias outer 1 ${'$'}t${k - 1} (type ${'$'}p)) (export \"x\" (instance (type ${'$'}p)))))\n")
        }
        val instances = (0 until 10).joinToString(" ") { "(instance (instantiate ${'$'}c))" }
        return wat(
            """
            (component
              $types
              (component ${'$'}c (alias outer 1 ${'$'}t$depth (type ${'$'}t)) (export "t" (type ${'$'}t)))
              $instances
            )
            """,
        )
    }

    @Test
    fun `入れ子が深すぎる型は展開した大きさで数え、小さくならない`() {
        // 宣言を共有しない形（100 段。MAX_FLAT_DEPTH の 64 を超える）。写し直しの大きさは 1 回あたり 206（段ごとに宣言があり、
        // 打ち切りの先も 0 にはならない）。打ち切りが 0 を返すと 1 回あたり約 130 に下がる
        val root = ComponentParser.parse(deepInstanceTypes(depth = 100, levels = 0), ComponentLimits.DEFAULT, null)
        val e = ComponentValidationCost.estimate(root)
        assertEquals(10L * 206, e.remapCost)
        assertDoesNotThrow { ComponentValidationCost.check(root, ComponentLimits.DEFAULT) }
    }

    @Test
    fun `入れ子が深すぎると、たどった値ではなく展開した大きさに切り替える`() {
        // 宣言を共有して 2 倍ずつ膨らむ record（17 段。宣言は 18 個、展開すると約 26 万）を一番下に持つ
        // 浅い（10 段）うちは、たどった値（宣言の数ほど）。展開した大きさにはならない
        val shallow = ComponentValidationCost.estimate(ComponentParser.parse(deepInstanceTypes(depth = 10, levels = 17), ComponentLimits.DEFAULT, null))
        assertTrue(shallow.remapCost < 10L * 1_000) { "${shallow.remapCost}" }
        // 深い（100 段。64 を超えるところから展開した大きさ）と、1 回あたり展開した大きさ（約 26 万）以上になる
        val deepRoot = ComponentParser.parse(deepInstanceTypes(depth = 100, levels = 17), ComponentLimits.DEFAULT, null)
        val deep = ComponentValidationCost.estimate(deepRoot)
        assertTrue(deep.remapCost >= 10L * 200_000) { "${deep.remapCost}" }
        val rejected = assertThrows<WasmLoadException> { ComponentValidationCost.check(deepRoot, ComponentLimits.DEFAULT) }
        assertMessage(rejected, "検証が重すぎる")
    }

    @Test
    fun `インスタンス化で作ったインスタンスの型はexportだけの大きさ`() {
        // c は大きなインスタンス型を import し、何も export しない。そのインスタンスを引数に渡す確認は、import の大きさを数えない
        val inner = (0 until 100).joinToString(" ") { """(field "f$it" u32)""" }
        val instances = (0 until 10).joinToString(" ") { "(instance (instantiate ${'$'}y (with \"j\" (instance ${'$'}x))))" }
        val bytes = wat(
            """
            (component
              (type ${'$'}t (instance (type ${'$'}rr (record $inner)) (export "r" (type (eq ${'$'}rr)))))
              (import "wasi:a/b@0.2.0" (instance ${'$'}a (type ${'$'}t)))
              (component ${'$'}c (alias outer 1 ${'$'}t (type ${'$'}t)) (import "i" (instance (type ${'$'}t))))
              (component ${'$'}y (import "j" (instance)))
              (instance ${'$'}x (instantiate ${'$'}c (with "i" (instance ${'$'}a))))
              $instances
            )
            """,
        )
        val e = estimateOf(bytes)
        assertEquals(11L, e.checkCount)
        // a を渡す 1 回（約 100）と、x を渡す 10 回（それぞれ 1）。x の型に c の import（約 100）を入れると 1,000 を超える
        assertTrue(e.cost - e.remapCost in 101L..200L) { "${e.cost} - ${e.remapCost}" }
        assertDoesNotThrow { ComponentLoader.load(bytes, policy = ComponentImportPolicy { null }) }
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

    /** 見積もりを外し、時間の上限を [limitMillis] ms にした上限 */
    private fun timeLimited(limitMillis: Long) =
        ComponentLimits(moduleLimits = ModuleLimits(timeLimitNanos = limitMillis * 1_000_000L), maxValidationCost = Long.MAX_VALUE)

    /** 見積もりを外すと検証が秒単位で膨らむ形（部分型、record、関数型） */
    private val slowShapes: List<Pair<String, ByteArray>> by lazy {
        val fields = (0 until 4000).joinToString(" ") { """(field "f$it" u32)""" }
        val params = (0 until 1000).joinToString(" ") { """(param "p$it" u32)""" }
        listOf(
            "部分型" to subtypeComponent(n = 2000, k = 100, m = 100),
            "record" to typedArgComponent("(record $fields)", k = 100, m = 100, prelude = "", import = "(type (eq ${'$'}tb))", arg = "(type ${'$'}ta)"),
            "関数型" to typedArgComponent(
                "(func $params)", k = 100, m = 100,
                prelude = """(import "g" (func ${'$'}g (type ${'$'}ta)))""", import = "(func (type ${'$'}tb))", arg = "(func ${'$'}g)",
            ),
        )
    }

    @Test
    fun `見積もりをすり抜けて重い検証も時間の上限で止めて内容の失敗にする`() {
        ComponentValidator.warmUp()
        val limitMillis = 300L
        // 同じスレッドで続けて読む（時間切れの割り込みが遅れて届き、次の読み込みを止めないこと）
        val pool = Executors.newSingleThreadExecutor()
        try {
            for ((name, bytes) in slowShapes) {
                val result = pool.submit(
                    Callable {
                        val start = System.nanoTime()
                        val e = runCatching { ComponentLoader.load(bytes, timeLimited(limitMillis)) }.exceptionOrNull()
                        val took = System.nanoTime() - start
                        val flag = Thread.currentThread().isInterrupted
                        // すぐ後の、正しいコンポーネントの読み込みは通る
                        val next = runCatching { ComponentLoader.load(full) }.exceptionOrNull()
                        listOf(e, took, flag, next)
                    },
                ).get(60, TimeUnit.SECONDS)
                val e = result[0] as Throwable?
                assertTrue(e is WasmLoadException) { "$name: $e" }
                assertMessage(e as Exception, "時間の上限（$limitMillis ms）を超えた（コンポーネントの検証、")
                assertTrue((result[1] as Long) < 5_000_000_000L) { "$name: ${(result[1] as Long) / 1_000_000} ms" }
                assertTrue(!(result[2] as Boolean)) { "$name: 割り込みの印が残っている" }
                assertNull(result[3]) { "$name: 次の読み込みが失敗した: ${result[3]}" }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `coreのインスタンス化の引数が多い形も時間の上限で止める`() {
        // import 10,000 個の core モジュールを、export を並べた core インスタンスで 500 回インスタンス化する（約 440 KB）。
        // 見積もりには入れていない形（import ごとに確かめるので入力の大きさに比例し、wasm-tools の core インスタンスの上限
        // 1,000 個までで CPU 約 3.5 秒に収まる）。見張りで止まることを確かめる
        val exports = (0 until 10_000).joinToString(" ") { """(func (export "f$it"))""" }
        val imports = (0 until 10_000).joinToString(" ") { """(import "env" "f$it" (func))""" }
        val inline = (0 until 10_000).joinToString(" ") { """(export "f$it" (func ${'$'}i "f$it"))""" }
        val instances = (0 until 500).joinToString(" ") { """(core instance (instantiate ${'$'}m (with "env" (instance ${'$'}x))))""" }
        val bytes = wat(
            """
            (component
              (core module ${'$'}e $exports)
              (core instance ${'$'}i (instantiate ${'$'}e))
              (core instance ${'$'}x $inline)
              (core module ${'$'}m $imports)
              $instances
            )
            """,
        )
        assertEquals(0L, estimateOf(bytes).cost)
        ComponentValidator.warmUp()
        val start = System.nanoTime()
        val e = assertThrows<WasmLoadException> { ComponentLoader.load(bytes, timeLimited(200)) }
        assertMessage(e, "時間の上限（200 ms）を超えた")
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
        assertTrue(!Thread.currentThread().isInterrupted)
    }

    @Test
    fun `ComponentProgramの読み込みでも検証を同じ上限で止める`() {
        ComponentValidator.warmUp()
        val bytes = slowShapes.first().second
        val start = System.nanoTime()
        val e = assertThrows<WasmLoadException> {
            ComponentProgram.load(LinkTesting.runtime, bytes, ComponentHost.NONE, limits = timeLimited(300))
        }
        assertMessage(e, "コンポーネントの検証、")
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
        assertTrue(!Thread.currentThread().isInterrupted)
    }

    @Test
    fun `時間の上限で止めた検証は、ほかの理由の割り込みと区別する`() {
        ComponentValidator.warmUp()
        // 見張りが時間切れにする前に割り込まれたら、今どおり INTERRUPTED（印も立てたまま）
        val slow = slowShapes.first().second
        var failure: Throwable? = null
        var flag = false
        val t = Thread {
            failure = runCatching { ComponentLoader.load(slow, timeLimited(60_000)) }.exceptionOrNull()
            flag = Thread.currentThread().isInterrupted
        }
        t.start()
        Thread.sleep(300)
        t.interrupt()
        t.join(10_000)
        assertTrue(!t.isAlive)
        val e = failure
        assertTrue(e is WasmTrapException && e.kind == WasmTrapKind.INTERRUPTED) { "$e" }
        assertTrue(flag)
    }

    /** 手で動かすタイマー（予定を貯めるだけ） */
    private class ManualTimer : ValidationWatchdog.Timer {
        val tasks = ArrayList<Pair<Long, Runnable>>()

        override fun schedule(delayNanos: Long, task: Runnable): java.util.concurrent.Future<*> {
            tasks += delayNanos to task
            return java.util.concurrent.CompletableFuture<Unit>()
        }
    }

    @Test
    fun `見張りは終わった後に発火しても割り込まない`() {
        val timer = ManualTimer()
        val watchdog = ValidationWatchdog(timer)
        // 上限が負: 最初の発火で時間切れになる
        val w = watchdog.watch(LoadDeadline(-1))
        assertEquals(1, timer.tasks.size)
        assertTrue(timer.tasks[0].first >= ValidationWatchdog.MIN_DELAY_NANOS)
        assertTrue(!w.finish())
        timer.tasks[0].second.run()
        assertTrue(!Thread.currentThread().isInterrupted)
        assertTrue(!w.timedOut)

        // 終わる前に発火すれば、割り込んで、finish が印を下ろす
        val w2 = watchdog.watch(LoadDeadline(-1))
        timer.tasks.last().second.run()
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(w2.finish())
        assertTrue(!Thread.currentThread().isInterrupted)
        // 終わった後の発火は何もしない
        timer.tasks.last().second.run()
        assertTrue(!Thread.currentThread().isInterrupted)
    }

    @Test
    fun `見張りは上限に届くまで予定を入れ直す`() {
        val timer = ManualTimer()
        val w = ValidationWatchdog(timer).watch(LoadDeadline(Long.MAX_VALUE / 2))
        timer.tasks.last().second.run()
        assertEquals(2, timer.tasks.size)
        assertTrue(!Thread.currentThread().isInterrupted)
        assertTrue(!w.finish())
    }

    @Test
    fun `見張りがloadの検証の後に発火しても割り込まない`() {
        val timer = ManualTimer()
        ComponentValidator.warmUp()
        ComponentLoader.load(full, watchdog = ValidationWatchdog(timer))
        assertTrue(timer.tasks.isNotEmpty())
        for ((_, task) in timer.tasks.toList()) task.run()
        assertTrue(!Thread.currentThread().isInterrupted)
    }

    /** 時間切れの発火の後に [fail] で終わる検証を [ComponentLoader.load] に渡して、出てきた失敗を返す */
    private fun loadFailingAfterTimeout(fail: () -> Nothing): Throwable? {
        val timer = ManualTimer()
        var now = 0L
        // 上限も見張りも、差し替えの時計だけで測る（進めるのは validate の中だけ）。本物の時計で測ると、
        // full の初回の評価や load の先頭の検証器の初期化（初回は 1 秒ほど）が上限に入り、テストの順番しだいで
        // validate に届く前にパーサー側の確認で断られる
        val watchdog = ValidationWatchdog(timer, cpuReader = { now }, wallClock = { now })
        val deadline = LoadDeadline(1_000_000_000L, cpuClock = { now })
        val e = runCatching {
            ComponentLoader.load(
                full,
                deadline = deadline,
                watchdog = watchdog,
                validate = {
                    now += 2_000_000_000L
                    timer.tasks.last().second.run()
                    assertTrue(Thread.currentThread().isInterrupted)
                    fail()
                },
            )
        }.exceptionOrNull()
        // どの失敗でも、時間切れで立てた割り込みの印は下ろす
        assertTrue(!Thread.currentThread().isInterrupted)
        return e
    }

    @Test
    fun `時間切れと重なった検証の失敗は時間の上限の失敗にする`() {
        val cause = IllegalStateException("途中で止まった")
        val e = loadFailingAfterTimeout { throw cause }
        assertTrue(e is WasmLoadException) { "$e" }
        assertMessage(e as Exception, "時間の上限")
        assertSame(cause, e.cause)
    }

    @Test
    fun `時間切れと重なってもVMのエラーは内容の失敗に包まずそのまま投げる`() {
        val oom = OutOfMemoryError("検証の途中")
        assertSame(oom, loadFailingAfterTimeout { throw oom })
        val soe = StackOverflowError()
        assertSame(soe, loadFailingAfterTimeout { throw soe })
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
