# 0153: 他 MOD のブロックをデバイスにする能力（`KibiCapabilities.DEVICE`）

- 状態: 採用
- Issue: #153（task）。前提: ADR 0065（デバイスのモデル。公開 API の形は #65 で決まった）、0066（接続数の上限）、0067（接続・切断のイベント）、0129（隣接の検出と登録表）
- 後続: #156（インベントリ・エネルギー・液体の汎用対応）、#228（公式アドオン）、#726（共有できる印）、#17・#243（API の jar と公開先）、#224（1.20.1 への移植）
- 作者向けの説明: `docs/device-api.md`

## 決めたこと

### 公開 API（足したのは1つだけ）

- `pro.ikili.kibicomputers.neoforge.api.KibiCapabilities.DEVICE`（`BlockCapability<Device, @Nullable Direction>`、`@JvmField`。名前は `kibicomputers:device`）。
  作者は `RegisterCapabilitiesEvent` で `registerBlockEntity`・`registerBlock` する。MC 非依存の型（`Device`・`DeviceMethods` など）は #65 のまま変えない
- 文脈の `Direction` は、問い合わせたブロックの、ケースを向いた面（ケースから見た向きの反対）。`null` は「面を問わない」
- 作者向けの規則は KDoc と `docs/device-api.md` に書く（同じ実体をキャッシュして返す、`kind` を変えない、変わったら `invalidateCapabilities`、
  スレッド、例外、`ModList.isLoaded` のときだけ登録、`super` を呼ぶ）

### 見張り方（`CapabilityNeighbors`）

- ケースの結線は、電源が入っている間、隣の6面に `BlockCapabilityCache` を1つずつ持つ（強い参照を持たないと GC で消えて知らせが来ない）。
  知らせ（無効化）の中では `DeviceWiring.schedule` だけ（問い合わせもワールドへのアクセスもしない。NeoForge の決まり）
- つなぎ直し（tick の終わり）の前に、6面すべてで `getCapability()` を呼び直す。キャッシュが有効な間は provider を呼ばない。
  呼び直さないと、次の知らせが来ない（`BlockCapabilityCache` の決まり）。待っているだけの台は、tick ごとに何もしない（キャッシュ6個のメモリだけ）
- **問い合わせ直しの理由**: 知らせの中では問い合わせられず、知らせの後も、キャッシュが無効のままだと次の知らせが来ないため。tick ごとの巡回（ADR 0129 の案 B）はしない
- NeoForge が自動で無効化するのは、チャンクの読み込み・アンロード、ブロックエンティティの読み込み・アンロード・置く・壊す。
  **普通のブロック（ブロックエンティティなし）の置く・壊す・状態の変化は、作者が `invalidateCapabilities` を呼ぶ**（NeoForge の決まり）。
  KiBi はケースの `neighborChanged` から問い合わせ直す経路を足さない（足しても、キャッシュが有効なら古い値が返るだけで、作者が無効化しないと直らない）

### 同一性で比べる

- 面ごとに、前に見た実体（`lastSeen`）と `===` で比べる。同じなら何もしない（無効化されても、つなぎ直さない）。
  変わったら、`null` なら手放し（`ExternalDeviceSlots.release`）、別の実体なら載せ直す（新しい世代。前の実体の `onDetached` の後に、新しい実体の `onAttached`）
- 自分の結果が変わっていない結線は載せ直さないので、知らせ1回につき、つなぎ直しは見張っている結線の数ほどで止まる。
  毎回新しい実体を返す provider では、無効化のたびに外してつなぎ直す（作者向けの説明で、キャッシュして返すよう求める）
- address と種類で比べる案は採らなかった。メソッドの表を変えた新しい実体を返しても、古い表のままになる（作者に「新しい実体を返して無効化すれば表が替わる」と約束できない）

### 登録表への載せ方（`ExternalDeviceSlots`・`ExternalDeviceHolder`）

- 能力で見つけた位置は、どれも位置ごとのアダプター（`ExternalDeviceHolder`。`DeviceHolder` を実装する）として、内蔵と同じ登録表（ADR 0129）に載せる。
  つなぐ規則（候補の順、上限、address の振り直し、外れたらすぐ外す）は内蔵と同じ
- `ExternalDeviceSlots` は、位置ごとに見張っている結線を数えるだけ。最初の結線が来たら載せ、最後の結線が去ったら外す（外し忘れで登録表が増え続けないように）
- **つなげるのは、その位置を `offer` した結線だけ**（`DeviceHolder.isOfferedTo`。内蔵は既定で `true`）。アダプターは位置ごとに1つで、
  どの結線も隣の6位置を候補として引くので、これがないと、面ごとの出し分け（上面にだけ出すなど）が守られない。出していない面の隣のケースが、
  持ち主が断った・投げた・まだ rewire していない間にアダプターを拾えてしまう。`MachineWiring` は、出していない持ち主を飛ばし（`visit`。見張りは続ける）、
  つないだ後で出さなくなった（`release` した）持ち主は、次のつなぎ直しで外す（`prune`。手放すので、出している結線に知らせる）。
  `offer` の watcher には `MachineWiring.host` を渡す（`ExternalDeviceHolder.offeredTo` が見張りの表をそのまま共有する）
- 内蔵（画面・キーボード）が載っている位置には載せない（内蔵を優先する）。アダプターの後に内蔵が置き換えたら、手放しても内蔵の登録は外さない
- アダプターは、つなぐたびにデバイスを `GuardedDevice` で包む（`kind`・`methods` を1回だけ読む）。能力が消えた・替わった（`retire`）後、
  マシンから外れるまでの間の呼び出しは `failed("device unavailable")` にする（外すのは tick の終わりなので、その間にメインスレッドの呼び出しが届きうる）

### 排他は実体の単位

- **他 MOD のデバイスも、内蔵と同じく1台のマシンにだけつなぐ。** 共有の口と「共有できる」印は足さない（印を足すかは #726）。
  根拠: 内蔵と同じ規則にそろえると、作者にとって `onAttached`・`onDetached` が交互に並ぶことを約束でき、他人の機械を隣のケースから乗っ取れない。
  後で印を足しても、既定（排他）を変えないので互換を壊さない
- 単位は位置ではなく実体。マルチブロックの各部が同じ実体を返すと、位置ごとの排他では、隣り合う2台のケースが同じ実体を別の位置からつなぎ、
  `onAttached` が2回続けて呼ばれる。`DeviceHolder.exclusionKey`（内部。既定 `null`）に、アダプターは能力が返した生の `Device` を返す
- `DeviceRegistry` は `IdentityHashMap<実体, Group>` を持つ。`Group` は同じ実体の登録（キー）と、今つないでいる登録 `ownerEntry` を持つ。
  `register` で `exclusionKey` を1回読んで足し、`remove`・`clear` で抜く（組が空になったら、実体のキーで直接消す。表をなめない。多数台の一斉アンロードで O(N²) にならないように）。`Group` は実行時だけで保存しない
- `Entry.owner` と `Group.ownerEntry` は一緒に立てて（`claim`）、一緒に下ろす（`drop`）。下ろすのは、つなげなかったとき（`tryConnect` の `finally`）、
  外したとき（`disconnectOne`）、登録が外れたとき（`remove`）。下ろしたら、同じ実体のすべてのキーを見張っている結線に知らせる（下ろした本人を除く）
- 候補を引くとき、`Group.ownerEntry` がほかの登録なら飛ばす（持ち主が自分でも飛ばす。1台のケースが同じ実体の2つの位置に触れても、1回だけつなぐ）
- address はつないだ位置のもの。1台のケースが同じ実体の複数の位置に触れるときは、隣の決まった順（下・上・北・南・西・東）で最初の位置
- **採らなかった案 (b)**: 結線ごとに「同じ実体を別の位置で待っている」表（待ちの位置）と、位置 → 実体の表を持つ形。位置の表と持ち主の二重の状態になり、
  満杯で断った・投げた・内蔵に置き換わったときに、二つがずれる経路が増える。持ち主（`ownerEntry`）だけで判定すれば、状態は1つで済む

### 例外の受け方

- 他 MOD のコード（provider、`kind`・`methods`、`onAttached`・`onDetached`、ブロックエンティティのデータ添付の読み書き）が投げたものは `Throwable` で受け、
  JVM が続けられないもの（`isFatal`。`VirtualMachineError` から `StackOverflowError` を除いたもの）だけ投げ直す（`rethrowIfFatal`）
- 受けるのは5か所
  1. `MachineWiring.tryConnect`（address の読み書き、`GuardedDevice` を作る、`DeviceBus.attach` の中の `onAttached`）。つながずに手放す
  2. `MachineWiring.disconnectOne`（`onDisconnected`・`onDetached`）。外すのと手放すのは済ませ、`disconnect` の残りも外し続ける（`Host.onDetachFailed`）
  3. `GuardedDevice` を作るところ（中身は 1.）
  4. 面ごと: `getCapability()` から、比べ、`ExternalDeviceSlots` の出し入れまで。投げた面はデバイスなし（載せていれば手放す）とし、ログは面ごとに1回。
     **provider が投げた面のキャッシュは無効のまま残る**（NeoForge は無効なキャッシュに知らせない）。次に試すのは、ほかの理由のつなぎ直しか、電源の入れ直し
  5. `DeviceWiring.flush` のケースごと（ここまで届くのは KiBi の誤り。数える: `guardedFailures`）
- catch・finally の中と `Host` の知らせ（`onRefused`・`onAttachFailed`・`onDetachFailed`）では、他 MOD のコードを呼ばない。
  ログには、登録のキー（位置）、読み済みの address、アダプターの `toString`（位置と実体のクラス名だけ）を使う。`Device.toString()`・`kind` は呼ばない
- **不変条件: (結線, 登録の世代) の組ごとに、投げて失敗するのは1回まで。** `MachineWiring` は、つなぐときに投げた登録をキーごとに1つ覚え（`failed`）、
  同じ登録の間はもう試さない（見張りは続ける）。忘れるのは、新しい登録になった（実体を替えて無効化した、置き直した、チャンクを読み込み直した）、
  `disconnect`（電源の入れ直し・マシンの付け替え）、キーが候補から外れたとき。同じ実体のままの無効化では忘れない（同一性で比べる規則とそろえる）。
  これがないと、いつも投げるデバイスに2台のケースが隣り合うと、「A が失敗して B に知らせる → B が失敗して A に知らせる」が毎 tick（`MAX_ROUNDS` 回ずつ）続く。
  満杯で断ったものは覚えない（自分のデバイスが外れたら見直す ADR 0066 の規則を保つ）。今の内蔵の `IllegalArgumentException` の経路も同じ仕組みで止まる
- 作者向けには「つなぐときに例外を投げたデバイスは、そのケースでは、新しい実体を返して無効化する・ブロックを置き直す・電源を入れ直すまで試さない」と書く

### address の置き場所

- ブロックエンティティがあるとき: NeoForge のデータ添付 `kibicomputers:device_address`（`AttachmentType<String>`、`Codec.STRING`）。
  最初につなぐときに振り、`setChanged()` する。他 MOD のブロックエンティティの NBT（`neoforge:attachments`）にタグが1つ増える（保存の形の小さな追加。AGENTS.md の例外の範囲）。
  形が正しくない値は捨てて振り直す。ピックブロックや複製で写った同じ address は、ADR 0129 の規則で後から来たほうを振り直す（添付を書き換える）
- ブロックエンティティがないとき: `UUID.nameUUIDFromBytes("<ディメンション>|x|y|z")` から決め、保存しない（同じ座標なら、いつも同じ address）。
  振り直せないので、重なったらつながない（投げて、その世代は試さない）
- 世代・`Group`・`lastSeen`・失敗の覚えは実行時だけで保存しない（ADR 0065 の Q6・ADR 0129 と同じ）

### 気をつけること

- `super` を呼ばないブロックエンティティ（`setRemoved`・`clearRemoved` を上書きして `super` を呼ばないもの。NeoForge はここで無効化する）は、
  無効化が出ないので、壊しても外れない・置いてもつながらない。作者向けの説明で `super` を呼ぶよう求める
- ADR 0129 の「再起動の後に持ち主が入れ替わりうる」危険は、他 MOD のデバイスにも同じように当てはまる（受け入れる。#131）
- 種類を版ごとに変えると、保存から戻したハンドルは `detached` になる（作者向けの説明に「`kind` は変えない」と書く）

## スレッド・負荷

- すべてサーバーのメインスレッド。`onAttached`・`onDetached` は `DeviceBus` の書き込みのロックの中（ADR 0067 のロックの順のまま）
- 待っているだけの台は tick ごとに何もしない。他 MOD のデバイスの有無で変わるのは、ケースごとのキャッシュ6個と、隣にあるアダプターの登録だけ
- 負荷試験は `-PloadTestDevices=<n>` で、n 台に1台の割合でケースの隣に他 MOD 役のデバイス（樽）を置いて測る（`docs/load-test.md`）

## テスト

- core: `ExternalDeviceWiringTest`（実体の排他、満杯・投げた・内蔵の置き換え、連鎖が止まること、ログに渡す値）、`ExternalDeviceSlotsTest`、`GuardedDeviceTest`
- GameTest（dev ソースセット。`kibicomputers.dev.testDevices` で他 MOD 役を登録する。jar には入らない）: `ExternalDeviceGameTests`
  （樽とロードストーン。つながる・外れる、address の保存、排他と引き継ぎ、実体の入れ替え、同じ実体の無効化、毎回新しい実体、provider が投げる、いつも投げるデバイスに2台、1回目だけ投げる）
