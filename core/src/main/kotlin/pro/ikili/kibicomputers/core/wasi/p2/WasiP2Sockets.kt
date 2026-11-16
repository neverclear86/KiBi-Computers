package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody

/**
 * `wasi:sockets` の口（#267。ADR 0267）。リンクはできて、使うと `not-supported` になるものだけを出す（実物は #164。
 * ネットワークのモデルは #163）。
 *
 * - `instance-network` は `network` を返す
 * - ソケットを作る関数（`create-tcp-socket`・`create-udp-socket`）と名前解決（`resolve-addresses`）は `not-supported`
 * - ソケットのメソッドは、ソケットを作れないので呼ばれない（ハンドルを持てない）。呼ばれたら内部の誤りとしてトラップ
 */
internal object WasiP2Sockets {
    private const val PREFIX = "wasi:sockets/"

    fun functions(t: WasiP2Types): Map<String, Map<String, HostFunctionBody>> {
        val errorCode = t["wasi:sockets/network"].type<CanonType.Enum>("error-code")
        val notSupported = HostFunctionBody { _, _ -> CanonValue.err(errorCode.case("not-supported")) }
        val unreachable = HostFunctionBody { call, _ -> P2.trap("${call.functionName}: ソケットはまだない（#164）") }
        val special = mapOf(
            "wasi:sockets/instance-network" to mapOf(
                "instance-network" to HostFunctionBody { call, _ -> P2.own(P2.ctx(call), MarkerEntry("network")) },
            ),
            "wasi:sockets/tcp-create-socket" to mapOf("create-tcp-socket" to notSupported),
            "wasi:sockets/udp-create-socket" to mapOf("create-udp-socket" to notSupported),
            "wasi:sockets/ip-name-lookup" to mapOf("resolve-addresses" to notSupported),
        )
        val out = LinkedHashMap<String, Map<String, HostFunctionBody>>()
        for ((name, w) in t.all) {
            if (!name.startsWith(PREFIX)) continue
            val s = special[name] ?: emptyMap()
            out[name] = w.functions.keys.associateWith { s[it] ?: unreachable }
        }
        return out
    }
}
