package pro.ikili.kibicomputers.core.runtime;

import org.junit.jupiter.api.Test;
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ランタイムの抽象（#34）が Java から自然に使えることを確かめる。 */
class WasmRuntimeJavaTest {
    @Test
    void loadInstantiateAndCall() {
        WasmRuntime runtime = new EndiveRuntime();
        assertEquals(EndiveRuntime.NAME, runtime.getName());
        WasmModule module = runtime.load(TestModules.INSTANCE.getHOST());
        WasmFunctionType type = WasmFunctionType.of(List.of(WasmValueType.I32), List.of(WasmValueType.I32));
        WasmInstanceConfig config = WasmInstanceConfig.builder()
            .hostFunction("env", "twice", type, (instance, args) -> new long[] {args[0] * 2})
            .fuel(0, (instance, remaining) -> remaining + 100)
            .build();
        try (WasmInstance instance = module.instantiate(config)) {
            assertEquals(21L, instance.call("run", 10)[0]);
            assertNull(instance.getMemory());
            assertEquals(false, module.isFuelMetered());
        }
    }

    @Test
    void exceptionsAndConstants() {
        WasmTrapException trap = new WasmTrapException(WasmTrapKind.UNREACHABLE, "x");
        assertEquals(WasmTrapKind.UNREACHABLE, trap.getKind());
        assertThrows(WasmLoadException.class, () -> new EndiveRuntime().load(new byte[] {1, 2, 3}));
        assertEquals("kibi", WasmFuel.IMPORT_MODULE);
        assertEquals("kibi_fuel", WasmFuel.EXPORT_GLOBAL);
        assertEquals(65536, WasmMemory.PAGE_SIZE);
        assertEquals(0, WasmInstanceConfig.EMPTY.getHostImports().size());
        WasmHostImport host = new WasmHostImport("env", "f", WasmFunctionType.EMPTY, (instance, args) -> new long[0]);
        assertEquals("env", host.getModule());
    }
}
