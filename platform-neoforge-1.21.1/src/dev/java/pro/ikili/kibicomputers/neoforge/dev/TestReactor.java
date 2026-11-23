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

    /**
     * {@link #BURN} が1回に回す時間の上限（マイクロ秒）。これより長い指定は切り詰める（1件で tick を止めすぎないように）
     */
    public static final long MAX_BURN_MICROS = 10_000;

    /**
     * メインスレッドのメソッドの名前。引数のマイクロ秒だけメインスレッドで回して待ち（{@link System#nanoTime()}。{@code Thread.sleep} は
     * Windows で粒度が 1〜15 ms と予算の桁なので使わない）、回した時間（マイクロ秒）を返す。負荷試験で、メインスレッドの予算（#356）を
     * 超えるほど重い呼び出しを作るためのもの。上限は {@link #MAX_BURN_MICROS}
     */
    public static final String BURN = "burn";

    /** メインスレッドのメソッド {@code mainMethod} と {@link #BURN}、直接のメソッド {@code ping} を持つ */
    public TestReactor(String kind, String mainMethod) {
        this.kind = kind;
        this.methods = DeviceMethods.builder()
            .mainThread(mainMethod, (ctx, args) -> new DeviceValue[] {DeviceValue.of(42L)})
            .mainThread(BURN, (ctx, args) -> new DeviceValue[] {DeviceValue.of(burn(args.getLong(0)))})
            .direct("ping", (ctx, args) -> new DeviceValue[] {DeviceValue.of("pong")})
            .build();
    }

    /** {@code micros} マイクロ秒（0 から {@link #MAX_BURN_MICROS} に切り詰める）だけ回して待ち、回した時間（マイクロ秒）を返す */
    static long burn(long micros) {
        long nanos = Math.max(0, Math.min(micros, MAX_BURN_MICROS)) * 1_000L;
        long start = System.nanoTime();
        long elapsed;
        do {
            elapsed = System.nanoTime() - start;
        } while (elapsed < nanos);
        return elapsed / 1_000L;
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
