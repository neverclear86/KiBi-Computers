package pro.ikili.kibicomputers.neoforge.dev;

import pro.ikili.kibicomputers.api.device.Device;
import pro.ikili.kibicomputers.api.device.DeviceContext;
import pro.ikili.kibicomputers.api.device.DeviceMethods;
import pro.ikili.kibicomputers.api.device.DeviceValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 他 MOD 役の試験用デバイス（#153）。つないだ・外した順を {@link #log} に残す。{@link #attachFailures} 回まで
 * {@code onAttached} で投げる（負なら毎回）。
 */
public final class TestReactor implements Device {
    /** 種類 */
    public static final String KIND = "kibitest:reactor";

    private final String kind;
    private final DeviceMethods methods;

    /** つないだ・外した順（"attached"・"detached"） */
    public final List<String> log = Collections.synchronizedList(new ArrayList<>());

    /** onAttached で投げる回数（負なら毎回） */
    public volatile int attachFailures;

    /** onAttached が呼ばれた回数 */
    public volatile int attachCalls;

    public TestReactor(String kind) {
        this(kind, "get-heat");
    }

    /** メインスレッドのメソッド {@code mainMethod} と、直接のメソッド {@code ping} を持つ */
    public TestReactor(String kind, String mainMethod) {
        this.kind = kind;
        this.methods = DeviceMethods.builder()
            .mainThread(mainMethod, (ctx, args) -> new DeviceValue[] {DeviceValue.of(42L)})
            .direct("ping", (ctx, args) -> new DeviceValue[] {DeviceValue.of("pong")})
            .build();
    }

    @Override
    public String getKind() {
        return kind;
    }

    @Override
    public DeviceMethods getMethods() {
        return methods;
    }

    @Override
    public void onAttached(DeviceContext context) {
        attachCalls++;
        int failures = attachFailures;
        if (failures != 0) {
            if (failures > 0) attachFailures = failures - 1;
            throw new IllegalStateException("test reactor refused to attach");
        }
        log.add("attached");
    }

    @Override
    public void onDetached(DeviceContext context) {
        log.add("detached");
    }
}
