package pro.ikili.kibicomputers.api.device

/**
 * コンピューターにつなぐデバイス（周辺機器。#65、ADR 0065）。他 MOD の作者が実装し、コンピューターにつなぐ。
 *
 * デバイスは種類（[kind]）とメソッドの表（[methods]）を持つ。プロセスはデバイスの address（つないだ持ち主が決める
 * 小文字の UUID）を指して、メソッドを名前で呼ぶ。引数と結果は [DeviceValue] の並び（入れ子なし）。
 *
 * ```java
 * public final class Reactor implements Device {
 *     private static final DeviceMethods METHODS = DeviceMethods.builder()
 *         .mainThread("get-heat", (ctx, args) -> new DeviceValue[] { DeviceValue.of(heat()) })
 *         .build();
 *
 *     public String getKind() { return "mymod:reactor"; }
 *     public DeviceMethods getMethods() { return METHODS; }
 * }
 * ```
 *
 * スレッド: [kind] と [methods] は、つないだときに1回だけ、つないだスレッドで読む。[onAttached]・[onDetached] は
 * つなぐ・外すスレッド（ふつうはメインスレッド）で呼ばれる。
 */
public interface Device {
    /**
     * デバイスの種類。`<名前空間>:<名前>`（Minecraft の ResourceLocation と同じ文字。名前空間は `[a-z0-9._-]`、
     * 名前は `[a-z0-9/._-]`、合わせて 64 バイトまで）。例: `mymod:reactor`。同じデバイスの間は変えない
     */
    public val kind: String

    /** メソッドの表。つないだときに1回だけ読み、外すまでそれを使う */
    public val methods: DeviceMethods

    /**
     * コンピューターにつないだ。[context] は外すまで使える（イベントを送るなど）。既定の実装は何もしない。
     * 中で投げた例外は、つなぐ側に伝わる
     */
    public fun onAttached(context: DeviceContext) {}

    /** コンピューターから外した。この後 [DeviceContext.postEvent] は `false` を返す。既定の実装は何もしない */
    public fun onDetached(context: DeviceContext) {}
}
