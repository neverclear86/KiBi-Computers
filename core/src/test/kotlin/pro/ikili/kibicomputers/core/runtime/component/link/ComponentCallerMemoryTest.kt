package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.fn
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.host
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.instantiate
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.load
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.u32

/**
 * ホストの関数に渡す [ComponentCall.callerMemory]（#777。ADR 0777）: canon lower した関数を core インスタンスが直接呼んだときだけ、
 * その core インスタンスが定義した 0 番の線形メモリ。同じ lower を別の core インスタンスが呼べば、そのインスタンスのメモリ。
 * メモリのないインスタンス・lift(lower) の道（core の呼び手がない）では `null`
 */
class ComponentCallerMemoryTest {
    /** 呼び手のメモリのページ数（なければ 0xffff_ffff）を返すホスト */
    private val probeHost = ComponentHost.of(
        host(
            "test:host/probe",
            "pages" to HostFunction(fn(emptyList(), CanonType.U32)) { call, _ -> u32(call.callerMemory?.pages ?: -1) },
        ),
    )

    private val component = """
        (component
          (import "test:host/probe" (instance ${'$'}h (export "pages" (func (result u32)))))
          (alias export ${'$'}h "pages" (func ${'$'}pages))
          (core func ${'$'}pages_lower (canon lower (func ${'$'}pages)))
          (core instance ${'$'}hi (export "pages" (func ${'$'}pages_lower)))
          (core module ${'$'}three
            (import "host" "pages" (func ${'$'}p (result i32)))
            (memory 3)
            (func (export "run") (result i32) (call ${'$'}p)))
          (core module ${'$'}five
            (import "host" "pages" (func ${'$'}p (result i32)))
            (memory (export "memory") 5)
            (func (export "run") (result i32) (call ${'$'}p)))
          (core module ${'$'}none
            (import "host" "pages" (func ${'$'}p (result i32)))
            (func (export "run") (result i32) (call ${'$'}p)))
          (core instance ${'$'}a (instantiate ${'$'}three (with "host" (instance ${'$'}hi))))
          (core instance ${'$'}b (instantiate ${'$'}five (with "host" (instance ${'$'}hi))))
          (core instance ${'$'}c (instantiate ${'$'}none (with "host" (instance ${'$'}hi))))
          (func (export "three") (result u32) (canon lift (core func ${'$'}a "run")))
          (func (export "five") (result u32) (canon lift (core func ${'$'}b "run")))
          (func (export "none") (result u32) (canon lift (core func ${'$'}c "run")))
          ;; lower した関数をそのまま lift する（core の呼び手がない）
          (func (export "direct") (result u32) (canon lift (core func ${'$'}pages_lower))))
    """

    @Test
    fun `callerMemoryは直接呼んだcoreインスタンスのメモリで、メモリのないインスタンスとliftした道ではnull`() {
        instantiate(load(component, probeHost)).use { c ->
            // export していないメモリも、定義したものなら渡す
            assertEquals(u32(3), c.call("three"))
            assertEquals(u32(5), c.call("five"))
            assertEquals(u32(-1), c.call("none"))
            assertEquals(u32(-1), c.call("direct"))
            // 何度呼んでも同じ（前の呼び出しのメモリが残らない）
            assertEquals(u32(3), c.call("three"))
        }
    }

    @Test
    fun `exportし直したホストの関数を外から呼んだときはnull`() {
        // ホストの関数を、lift せずに export し直したもの（外からの call がホストの関数を直接呼ぶ）
        val reexport = """
            (component
              (import "test:host/probe" (instance ${'$'}h (export "pages" (func (result u32)))))
              (alias export ${'$'}h "pages" (func ${'$'}pages))
              (export "pages" (func ${'$'}pages)))
        """
        instantiate(load(reexport, probeHost)).use { c ->
            assertEquals(CanonValue.I32(-1), c.call("pages"))
        }
    }
}
