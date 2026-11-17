package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.HostResourceIds
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.ResourceImpl
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/** `kibi:*` の p2 のホスト（#685。ADR 0685）: WIT・出す interface・resource の番号・リンク */
class KibiP2HostTest {
    private fun load(bytes: ByteArray): ComponentProgram = ComponentProgram.load(LinkTesting.runtime, bytes, KernelComponentHost)

    private fun loadWat(text: String): ComponentProgram = load(WitTool.wat(text))

    @Test
    fun `ホストのWITはsrc_main_witと同じ`() {
        assertEquals(KibiP2WitTool.kibiText.trim(), KibiP2Wit.TEXT.trim(), "src/main/wit を直したら KibiP2Wit を書き出し直す")
    }

    @Test
    fun `出すのはimports-p2のinterfaceだけで、resourceの番号はkibiの範囲`() {
        val expected = setOf(
            "kibi:kernel/types", "kibi:kernel/events", "kibi:kernel/timers", "kibi:kernel/events-poll", "kibi:kernel/timers-poll",
            "kibi:process/process", "kibi:process/process-poll",
            "kibi:io/tty", "kibi:io/pipe-streams",
            "kibi:fs/mounts",
            "kibi:device/types", "kibi:device/bus", "kibi:device/bus-poll",
            "kibi:computer/clock", "kibi:computer/power", "kibi:computer/access",
        )
        assertEquals(expected, KibiP2Host.interfaces.keys)
        assertTrue(KibiP2Host.interfaces.values.all { it.fallback == null })
        val wasi = WasiP2Host.wit
        val own = setOf("event-queue", "timer", "process", "device", "pending-call")
        for ((key, w) in KibiP2Host.wit) {
            for ((name, id) in w.resources) {
                if (name in own) {
                    assertTrue(id >= HostResourceIds.KIBI_FIRST && id < HostResourceIds.KIBI_END, "$key $name $id")
                } else {
                    assertTrue(id >= HostResourceIds.WASI_FIRST && id < HostResourceIds.WASI_END, "$key $name $id")
                }
            }
        }
        // use した wasi:* の resource は wasi:* と同じ番号（wasi:io/poll.poll に並べて渡せる）
        val pollable = wasi.getValue("wasi:io/poll").resources.getValue("pollable")
        for (k in listOf("kibi:kernel/events-poll", "kibi:kernel/timers-poll", "kibi:process/process-poll", "kibi:device/bus-poll")) {
            assertEquals(pollable, KibiP2Host.wit.getValue(k).resources.getValue("pollable"), k)
        }
        val streams = wasi.getValue("wasi:io/streams").resources
        val pipe = KibiP2Host.wit.getValue("kibi:io/pipe-streams").resources
        assertEquals(streams.getValue("input-stream"), pipe.getValue("input-stream"))
        assertEquals(streams.getValue("output-stream"), pipe.getValue("output-stream"))
        assertEquals(
            wasi.getValue("wasi:filesystem/types").resources.getValue("descriptor"),
            KibiP2Host.wit.getValue("kibi:process/process-poll").resources.getValue("descriptor"),
        )
        // 同じ resource は interface をまたいで同じ番号
        assertEquals(
            KibiP2Host.wit.getValue("kibi:kernel/events").resources.getValue("event-queue"),
            KibiP2Host.wit.getValue("kibi:kernel/timers").resources.getValue("event-queue"),
        )
    }

    @Test
    fun `kibiは0点1点0の完全一致だけで、p1だけ・p3・memoryのinterfaceは出さない`() {
        assertNotNull(KernelComponentHost.interfaceOf("kibi:kernel/events@0.1.0"))
        assertSame(KibiP2Host.interfaceOf("kibi:io/tty@0.1.0"), KernelComponentHost.interfaceOf("kibi:io/tty@0.1.0"))
        assertSame(WasiP2Host.interfaceOf("wasi:io/poll@0.2.3"), KernelComponentHost.interfaceOf("wasi:io/poll@0.2.3"))
        for (bad in listOf(
            "kibi:kernel/events@0.1.1", "kibi:kernel/events", "kibi:kernel/events@0.2.0",
            "kibi:io/fd@0.1.0", "kibi:io/pipe@0.1.0", "kibi:computer/memory@0.1.0",
            "kibi:kernel/events-async@0.1.0", "kibi:process/process-async@0.1.0", "kibi:device/bus-async@0.1.0",
            "other:pkg/iface@0.1.0",
        )) {
            assertNull(KernelComponentHost.interfaceOf(bad), bad)
        }
        assertSame(KernelComponentHost, KernelDrivers.COMPONENT_HOST)
    }

    @Test
    fun `出さないkibiのimportは読み込みの失敗`() {
        for (name in listOf("kibi:computer/memory@0.1.0", "kibi:io/fd@0.1.0", "kibi:computer/clock@0.1.1")) {
            assertThrows<WasmLoadException>(name) {
                loadWat("""(component (import "$name" (instance (export "uptime-ticks" (func (result u64))))))""")
            }
        }
        // 出しているものは通る
        loadWat("""(component (import "kibi:computer/clock@0.1.0" (instance (export "uptime-ticks" (func (result u64))))))""")
    }

    @Test
    fun `p2のworldをすべてimportするダミーのコンポーネントがfallbackなしでリンクできる`() {
        val p = load(KibiP2WitTool.dummy)
        val hostResources = p.plan.resources.filterNotNull().filter { it.impl is ResourceImpl.Host }
        val byHost = hostResources.groupBy { (it.impl as ResourceImpl.Host).resource.id }
        // kibi の resource も、wasi から use した resource も、ホストの番号ごとに 1 つの正規の番号にまとまる
        assertTrue(byHost.values.all { infos -> infos.map { it.canonical }.toSet().size == 1 }, "$byHost")
        val ids = byHost.keys
        assertTrue(ids.any { it >= HostResourceIds.KIBI_FIRST }, "$ids")
        assertTrue(WasiP2Host.wit.getValue("wasi:io/poll").resources.getValue("pollable") in ids, "$ids")
    }
}
