package pro.ikili.kibicomputers.neoforge.api

import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.capabilities.BlockCapability
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.KiBiComputers

/**
 * KiBi Computers が他 MOD に出す NeoForge の能力（#153、ADR 0153）。
 *
 * ## [DEVICE]: ブロックをコンピューターのデバイスにする
 *
 * コンピューターのケースは、隣の6方向のブロックにこの能力を問い合わせ、返った [Device] をつなぐ。作者は MOD のイベントバスの
 * `RegisterCapabilitiesEvent` で登録する。KiBi Computers が入っていないときにこのクラスを読むと `NoClassDefFoundError` になるので、
 * 登録は `ModList.get().isLoaded("kibicomputers")` のときだけ読み込むクラスで行う。
 *
 * ```java
 * // ModList.get().isLoaded("kibicomputers") のときだけ呼ぶ
 * static void register(RegisterCapabilitiesEvent event) {
 *     event.registerBlockEntity(KibiCapabilities.DEVICE, MY_BE.get(), (be, side) -> be.device());
 * }
 * ```
 *
 * 能力を問い合わせるのはサーバーでだけ。文脈の [Direction] は、問い合わせたブロックの、ケースを向いた面。`null` は「面を問わない」。
 *
 * ### 返すもの
 * - **同じブロックには同じ実体をキャッシュして返す**（面ごとに作り直さない。出す・出さないだけを面で選ぶ）。KiBi は実体の同一性で
 *   比べ、同じ実体なら能力が無効化されてもつなぎ直さない。毎回新しい実体を返すと、無効化のたびに外してつなぎ直す
 *   （ゲストには `device-detached`・`device-attached` が届く）
 * - [Device.kind] は変えない（保存から戻したとき、ハンドルは address と種類で引き直す）
 * - 出すものが変わったら（設定が変わった、普通のブロックを置いた・壊した）、`level.invalidateCapabilities(pos)` を呼ぶ。
 *   ブロックエンティティの置く・壊す・読み込み・アンロードは NeoForge が知らせる
 * - メソッドの表を変えるには、新しい実体を返して `invalidateCapabilities` を呼ぶ。KiBi は前の実体を外し、新しい実体をつなぐ。
 *   address と種類が同じなら、ゲストのハンドルはそのまま使える
 *
 * ### つながりの規則
 * - 同じ実体がつながるマシンは1台だけ。ほかのケースは、つないでいるケースが手放すまでつながない
 * - [Device.onAttached] は、前の [Device.onDetached] の後にしか呼ばれない（交互に並ぶ）
 * - 複数のブロック（マルチブロックの各部）から同じ実体を返してよい。address は、つないだブロックのもの（ブロックエンティティが
 *   あれば、そのデータ添付 `kibicomputers:device_address` に KiBi が書く。なければ座標から決める）。1台のケースが同じ実体の
 *   複数のブロックに触れるときは、下・上・北・南・西・東の順で最初のブロックからつなぐ
 * - 複数のマシンでの共有は、今は約束も否定もしない（将来、明示して選べるようにするかもしれない）
 * - つなぐときに例外を投げたデバイスは、そのケースでは、新しい実体を返して無効化する・ブロックを置き直す・電源を入れ直すまで試さない
 *
 * ### スレッドと例外
 * - [Device.onAttached]・[Device.onDetached] と、能力の provider は、サーバーのメインスレッドで呼ばれる（`onAttached`・`onDetached`
 *   は、デバイスの表の書き込みのロックの中。ブロックしたり重い処理をしたりしない）
 * - メインスレッドのメソッド（`DeviceMethods.Builder.mainThread`）は、外れた後のブロックエンティティに届くことがある。
 *   `isRemoved()` を確かめる。直接のメソッド（`direct`）はプロセスのスレッドで動くので、ワールドに触らず、ブロックせず、
 *   メインスレッドを待たない。時間がかかる処理は、メインスレッドのメソッドで `defer()` する。どのメソッドも短く保つ
 * - provider・`kind`・`methods`・`onAttached`・`onDetached` が投げた例外は KiBi が受けてログに出し、そのデバイスをつながない
 *   （サーバーは止めない）。能力が消えた後、マシンから外れるまでの呼び出しは `failed` になる
 * - ブロックエンティティの `setRemoved`・`clearRemoved` を上書きするときは、`super` を呼ぶ（NeoForge の能力の無効化が、そこから出る）
 */
public object KibiCapabilities {
    /** ブロックをコンピューターのデバイスにする能力（`kibicomputers:device`）。文脈は、ケースを向いた面（`null` は面を問わない） */
    @JvmField
    public val DEVICE: BlockCapability<Device, Direction?> =
        BlockCapability.createSided(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "device"), Device::class.java)
}
