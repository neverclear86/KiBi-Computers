package pro.ikili.kibicomputers.core.runtime;

import org.junit.jupiter.api.Test;
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void selfCheckCompilesBundledLua() throws WasmLoadException {
        assertTrue(RuntimeSelfCheck.compilesBundledLua());
        assertTrue(RuntimeSelfCheck.isWorking());
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

    @Test
    void memoryLimit() {
        int[] used = {0};
        WasmMemoryBudget budget = new WasmMemoryBudget() {
            @Override
            public boolean tryReserve(int pages) {
                if (used[0] + pages > 3) return false;
                used[0] += pages;
                return true;
            }

            @Override
            public void release(int pages) {
                used[0] -= pages;
            }
        };
        WasmInstanceConfig config = WasmInstanceConfig.builder().memoryLimit(WasmMemory.MAX_PAGES, budget).build();
        assertEquals(WasmMemory.MAX_PAGES, config.getMaxMemoryPages());
        WasmModule module = new EndiveRuntime().load(TestModules.INSTANCE.getGROW());
        try (WasmInstance instance = module.instantiate(config)) {
            assertEquals(2, used[0]);
            assertEquals(-1, instance.getMemory().grow(2));
            assertEquals(2, instance.getMemory().grow(1));
        }
        assertEquals(0, used[0]);
    }

    @Test
    void asyncifiedModule() {
        WasmModule module = new EndiveRuntime().load(TestModules.INSTANCE.getASYNC_WAIT());
        assertTrue(module.isAsyncified());
        assertFalse(new EndiveRuntime().load(TestModules.INSTANCE.getHOST()).isAsyncified());
        Deque<Long> saved = new ArrayDeque<>();
        WasmAsyncStack stack = new WasmAsyncStack() {
            @Override
            public void push(long value) {
                saved.push(value);
            }

            @Override
            public long pop() {
                return saved.pop();
            }
        };
        WasmFunctionType waitType = WasmFunctionType.of(List.of(), List.of(WasmValueType.I32));
        WasmInstanceConfig config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", waitType, (instance, args) -> {
                if (instance.getAsyncState() == WasmAsyncState.REWINDING) {
                    instance.setAsyncState(WasmAsyncState.NORMAL);
                    return new long[] {41};
                }
                instance.setAsyncState(WasmAsyncState.UNWINDING);
                return new long[] {0};
            })
            .asyncStack(stack)
            .build();
        assertEquals(stack, config.getAsyncStack());
        try (WasmInstance instance = module.instantiate(config)) {
            instance.call("run");
            assertEquals(WasmAsyncState.UNWINDING, instance.getAsyncState());
            assertFalse(saved.isEmpty());
            instance.setAsyncState(WasmAsyncState.REWINDING);
            assertEquals(42L, instance.call("run")[0]);
            assertEquals(WasmAsyncState.NORMAL, instance.getAsyncState());
        }
        assertEquals("kibi", WasmAsync.IMPORT_MODULE);
        assertEquals("async_push", WasmAsync.PUSH);
        assertEquals("async_pop", WasmAsync.POP);
        assertEquals("kibi_async_state", WasmAsync.STATE_EXPORT);
        assertEquals(List.of(WasmValueType.I64), WasmAsync.PUSH_TYPE.getParams());
        assertEquals(List.of(WasmValueType.I64), WasmAsync.POP_TYPE.getResults());
        assertEquals(2, WasmAsyncState.valueOf("REWINDING").ordinal());
    }
}
