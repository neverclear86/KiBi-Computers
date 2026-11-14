# 他 MOD のブロックをデバイスにする（NeoForge 1.21.1）

KiBi Computers のコンピューター（ケース）は、隣の6方向のブロックに NeoForge の能力 `KibiCapabilities.DEVICE` を問い合わせ、
返った `Device` をつなぐ（#153、ADR 0153）。この文書は作者向けの説明。決めたことの根拠は `docs/adr/0153-device-capability.md`。

- 1.20.1 / Forge 版はまだない（#224）
- API の jar と Maven の公開先は未定（#17・#243）。今は MOD の jar をコンパイル時の依存にする

## 1. デバイスを書く

`pro.ikili.kibicomputers.api.device.Device` を実装する。種類（`kind`）と、メソッドの表（`DeviceMethods`）を返す。

```java
public final class ReactorDevice implements Device {
    private final ReactorBlockEntity be;
    private final DeviceMethods methods;

    public ReactorDevice(ReactorBlockEntity be) {
        this.be = be;
        this.methods = DeviceMethods.builder()
            // メインスレッドで呼ぶ（ワールドに触ってよい）。外れた後のブロックエンティティに届くことがあるので、isRemoved を見る
            .mainThread("get-heat", (ctx, args) -> {
                if (be.isRemoved()) throw new DeviceException("removed");
                return new DeviceValue[] {DeviceValue.of(be.heat())};
            })
            // プロセスのスレッドでその場で呼ぶ（ワールドに触らない・ブロックしない・メインスレッドを待たない）
            .direct("get-name", (ctx, args) -> new DeviceValue[] {DeviceValue.of("reactor")})
            .build();
    }

    @Override public String getKind() { return "mymod:reactor"; }
    @Override public DeviceMethods getMethods() { return methods; }
}
```

- `kind` は `<名前空間>:<名前>`（小文字。64 バイトまで）。**版をまたいで変えない**（保存から戻したハンドルは address と種類で引き直す）
- 時間がかかる処理は、メインスレッドのメソッドで `ctx.defer()` して、後で `complete` する。どのメソッドも短く保つ（メインスレッドの呼び出しは、
  1台あたり 1 tick に件数の上限があり、サーバー全体の予算もある）
- `onAttached`・`onDetached` は、メインスレッドで、デバイスの表の書き込みのロックの中で呼ばれる。ブロックしたり重い処理をしたりしない

## 2. 能力を登録する

MOD のイベントバスの `RegisterCapabilitiesEvent` で登録する。KiBi Computers が入っていないときに `KibiCapabilities` を読むと
`NoClassDefFoundError` になるので、**KiBi に触るクラスは `ModList.get().isLoaded("kibicomputers")` のときだけ読み込む**。

```java
// MyMod のコンストラクター
if (ModList.get().isLoaded("kibicomputers")) KibiCompat.register(modEventBus);

// KibiCompat.java（KiBi が入っているときだけ読み込まれる）
public final class KibiCompat {
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterCapabilitiesEvent.class, event ->
            event.registerBlockEntity(KibiCapabilities.DEVICE, MyBlockEntities.REACTOR.get(), (be, side) -> be.kibiDevice()));
    }
}

// ReactorBlockEntity: 同じ実体をキャッシュして返す
private ReactorDevice device;
public ReactorDevice kibiDevice() {
    if (device == null) device = new ReactorDevice(this);
    return device;
}
```

- 文脈の `Direction` は、問い合わせたブロックの、ケースを向いた面。`null` は「面を問わない」。面で選ぶのは「出す・出さない」だけにする
- 問い合わせはサーバーでだけ行われる

## 3. 返すものの規則

- **同じブロックには、同じ実体をキャッシュして返す。** KiBi は実体の同一性（`==`）で比べる。同じ実体なら、能力が無効化されてもつなぎ直さない。
  問い合わせのたびに新しい実体を返すと、無効化のたびに外してつなぎ直す（ゲストには `device-detached`・`device-attached` が届く）
- **出すものが変わったら `level.invalidateCapabilities(pos)` を呼ぶ**（ブロックエンティティなら `invalidateCapabilities()`）。
  ブロックエンティティの置く・壊す・読み込み・アンロードは NeoForge が知らせる。普通のブロック（ブロックエンティティなし）の置く・壊す・状態の変化は、
  `onPlace`・`onRemove` などで作者が呼ぶ
- メソッドの表を変えたいときは、新しい実体を返して無効化する。KiBi は前の実体を外し（`onDetached`）、新しい実体をつなぐ（`onAttached`）。
  address と種類が同じなら、ゲストのハンドルはそのまま使える
- ブロックエンティティの `setRemoved`・`clearRemoved` を上書きするときは、`super` を呼ぶ（NeoForge の無効化がそこから出る）

## 4. つながりの規則

- **同じ実体がつながるマシンは1台だけ。** 隣のケースは、つないでいるケースが手放す（電源を切る・壊れる・アンロードする）までつながない
- `onAttached` は、前の `onDetached` の後にしか呼ばれない（交互に並ぶ）
- マルチブロックの各部から同じ実体を返してよい。1台のケースが複数の部分に触れても、つなぐのは1回（下・上・北・南・西・東の順で最初の部分から）
- address（小文字の UUID）は、つないだブロックのもの。ブロックエンティティがあれば、KiBi がそのデータ添付 `kibicomputers:device_address` に書く
  （ブロックエンティティの NBT の `neoforge:attachments` に入る）。ブロックエンティティがなければ、ディメンションと座標から決まる（保存しない）
- 複数のマシンでの共有は、今は約束も否定もしない（#726）
- 1台のマシンにつなげる数には上限がある（CPU で決まる）。満杯なら、空くまでつながない

## 5. 例外

- provider・`kind`・`methods`・`onAttached`・`onDetached` が投げた例外は、KiBi が受けてログに出す（サーバーは止めない）
- **つなぐときに例外を投げたデバイスは、そのケースでは、新しい実体を返して無効化する・ブロックを置き直す・電源を入れ直すまで試さない**
- provider が投げた面はデバイスなしとみなす。次に試すのは、ほかの理由のつなぎ直しか、電源の入れ直し
- 能力が消えた（壊した・無効化して `null` を返した）後、マシンから外れるまでの呼び出しは `failed("device unavailable")` になる
- メソッドの中で `DeviceException` を投げると、ゲストには `failed(message)` が届く。ほかの例外は `failed("internal error")`
