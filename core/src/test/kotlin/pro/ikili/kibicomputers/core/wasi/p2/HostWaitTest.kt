package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.component.link.HostInterface
import pro.ikili.kibicomputers.core.runtime.component.link.HostWait

/**
 * 実物のホスト（`wasi:*`・`kibi:*`）の関数の待ち方（#757。ADR 0757）。待ち方が食い違うと、待機点型のコンポーネントが
 * 黙って壊れるか、CRASHED になるので、待ちうる関数の一覧をここで固定する
 */
class HostWaitTest {
    private fun waits(interfaces: Map<String, HostInterface>, wait: HostWait): Map<String, Set<String>> =
        interfaces.mapValues { (_, i) -> i.functions.filterValues { it.wait == wait }.keys }.filterValues { it.isNotEmpty() }

    @Test
    fun `wasiの巻き戻して待つ関数は、pollとstreamsの待ちうる関数だけ`() {
        assertEquals(
            mapOf(
                WasiP2Io.POLL to setOf("[method]pollable.block", "poll"),
                WasiP2Io.STREAMS to setOf(
                    "[method]input-stream.blocking-read",
                    "[method]input-stream.blocking-skip",
                    "[method]output-stream.write",
                    "[method]output-stream.blocking-write-and-flush",
                    "[method]output-stream.write-zeroes",
                    "[method]output-stream.blocking-write-zeroes-and-flush",
                    "[method]output-stream.splice",
                    "[method]output-stream.blocking-splice",
                ),
            ),
            waits(WasiP2Host.interfaces, HostWait.UNWIND),
        )
        assertEquals(emptyMap<String, Set<String>>(), waits(WasiP2Host.interfaces, HostWait.BLOCK))
    }

    @Test
    fun `kibiの巻き戻せない待ちは、デバイスの呼び出しと子の読み込み`() {
        assertEquals(
            mapOf(
                KibiP2Device.BUS to setOf("[method]device.invoke"),
                KibiP2Process.PROCESS to setOf("[static]process.spawn"),
                KibiP2Process.PROCESS_POLL to setOf("spawn-with-handles"),
            ),
            waits(KibiP2Host.interfaces, HostWait.BLOCK),
        )
        assertEquals(emptyMap<String, Set<String>>(), waits(KibiP2Host.interfaces, HostWait.UNWIND))
    }
}
