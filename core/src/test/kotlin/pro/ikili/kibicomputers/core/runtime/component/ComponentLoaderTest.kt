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
        assertMessage(rejects(bomb), "展開した後のコアモジュールのインスタンスの数")
        assertMessage(rejects(doubling(4), ComponentLimits(maxExpandedCoreInstances = 15)), "上限 15")
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
        val bad = full.copyOf().also { it[it.size - 1] = 0x7f }
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
        // 壊れたものは何度でも同じように断る
        val first = runCatching { ComponentLoader.load(bad) }.exceptionOrNull()?.message
        val second = runCatching { ComponentLoader.load(bad) }.exceptionOrNull()?.message
        assertEquals(first, second)
    }

    @Test
    fun `検証用の写しは関数の本体だけを除く`() {
        val root = ComponentParser.parse(full, ComponentLimits.DEFAULT, null)
        val image = ValidationImage.of(full, root)
        val parsed = ComponentParser.parse(image, ComponentLimits.DEFAULT, null)
        // コンポーネントのセクションは同じ（core モジュールとカスタム セクションの範囲だけが変わる）
        assertEquals(
            root.sections.filter { it !is ComponentSection.CoreModule && it !is ComponentSection.Nested }.map { it::class to (it as? ComponentSection.Imports)?.items },
            parsed.sections.filter { it !is ComponentSection.CoreModule && it !is ComponentSection.Nested }.map { it::class to (it as? ComponentSection.Imports)?.items },
        )
        assertEquals(root.allCoreModules().size, parsed.allCoreModules().size)
        assertDoesNotThrow { ComponentValidator.validate(image) }
        // 関数の本体の誤りは、ここでは見ない（p1 と同じく、Endive が読み込むときに検証する。#263）
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
}
