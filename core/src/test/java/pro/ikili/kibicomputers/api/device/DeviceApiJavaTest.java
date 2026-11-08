package pro.ikili.kibicomputers.api.device;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** デバイスの公開 API（#65）が Java から自然に使えること（コンパイルできることが検査になる） */
class DeviceApiJavaTest {
    /** 他 MOD の作者が書くデバイス。onAttached・onDetached は書かずに済む */
    static final class Reactor implements Device {
        private long heat = 42;

        private final DeviceMethods methods = DeviceMethods.builder()
            .mainThread("get-heat", (ctx, args) -> new DeviceValue[] {DeviceValue.of(heat)})
            .direct("add", (ctx, args) -> {
                ctx.consumeFuel(10);
                return new DeviceValue[] {DeviceValue.of(args.getLong(0) + args.getLong(1))};
            })
            .direct("fail", (ctx, args) -> {
                throw new DeviceException("overheated");
            })
            .build();

        @Override
        public String getKind() {
            return "mymod:reactor";
        }

        @Override
        public DeviceMethods getMethods() {
            return methods;
        }
    }

    /** 種類を switch で読む（default を必ず書く） */
    static String describe(DeviceValue value) {
        switch (value.getType()) {
            case NONE:
                return "none";
            case INTEGER:
                return "integer " + value.asLong();
            case FLOAT:
                return "float " + value.asDouble();
            case BOOLEAN:
                return "boolean " + value.asBoolean();
            case TEXT:
                return "text " + value.asText();
            case BYTES:
                return "bytes " + value.asBytes().length;
            default:
                return "unknown";
        }
    }

    @Test
    void deviceCanBeWrittenInJava() {
        Reactor reactor = new Reactor();
        assertEquals("mymod:reactor", reactor.getKind());
        assertEquals(List.of("get-heat", "add", "fail"), reactor.getMethods().getNames());
        // 既定のメソッドは書かなくても呼べる
        DeviceContext context = new DeviceContext() {
            @Override
            public String getAddress() {
                return "00000000-0000-4000-8000-000000000001";
            }

            @Override
            public boolean postEvent(String kind, DeviceValue... values) {
                return true;
            }
        };
        reactor.onAttached(context);
        reactor.onDetached(context);
        assertTrue(context.postEvent("heat-changed", DeviceValue.of(1), DeviceValue.of("hot")));
    }

    @Test
    void valuesFromJava() {
        assertEquals("none", describe(DeviceValue.none()));
        assertEquals("integer 5", describe(DeviceValue.of(5)));
        assertEquals("float 1.5", describe(DeviceValue.of(1.5)));
        assertEquals("boolean true", describe(DeviceValue.of(true)));
        assertEquals("text hi", describe(DeviceValue.of("hi")));
        byte[] raw = "ab".getBytes(StandardCharsets.UTF_8);
        DeviceValue bytes = DeviceValue.of(raw);
        raw[0] = 'x';
        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), bytes.asBytes(), "作るときに写す");
        assertEquals(DeviceValue.Type.BYTES, bytes.getType());
        assertThrows(IllegalStateException.class, () -> DeviceValue.of(1).asText());
        assertEquals(DeviceValue.of(7), DeviceValue.of(7));
        assertSame(DeviceValue.none(), DeviceValue.none());
    }

    @Test
    void methodsAndBuilderFromJava() {
        DeviceMethods empty = DeviceMethods.empty();
        assertTrue(empty.getNames().isEmpty());
        DeviceMethods.Builder builder = DeviceMethods.builder();
        DeviceMethodHandler handler = (ctx, args) -> new DeviceValue[0];
        builder.direct("ok", handler);
        assertThrows(IllegalArgumentException.class, () -> builder.direct("ok", handler));
        assertThrows(IllegalArgumentException.class, () -> builder.mainThread("Bad", handler));
        assertNotNull(builder.build());
    }

    @Test
    void exceptionsAndCompletionFromJava() {
        DeviceException e = new DeviceException("broken");
        assertEquals("broken", e.getMessage());
        // 作者が自分の例外を DeviceException から作れる
        class MyException extends DeviceException {
            MyException() {
                super("mine");
            }
        }
        try {
            throw new MyException();
        } catch (DeviceException caught) {
            assertEquals("mine", caught.getMessage());
        }
        DeviceCompletion completion = new DeviceCompletion() {
            private boolean done;

            @Override
            public void complete(DeviceValue... values) {
                done = true;
            }

            @Override
            public void fail(String message) {
                done = true;
            }

            @Override
            public boolean isCancelled() {
                return done;
            }
        };
        assertFalse(completion.isCancelled());
        completion.complete(DeviceValue.of(1), DeviceValue.of("x"));
        assertTrue(completion.isCancelled());
    }

    @Test
    void callContextIsAnInterface() {
        DeviceCallContext ctx = new DeviceCallContext() {
            @Override
            public String getAddress() {
                return "a";
            }

            @Override
            public String getMethod() {
                return "m";
            }

            @Override
            public boolean isMainThread() {
                return false;
            }

            @Override
            public void consumeFuel(long amount) {
            }

            @Override
            public DeviceCompletion defer() {
                throw new IllegalStateException("direct");
            }
        };
        assertFalse(ctx.isMainThread());
        assertEquals("m", ctx.getMethod());
        assertThrows(IllegalStateException.class, ctx::defer);
    }
}
