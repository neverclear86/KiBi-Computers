# 0131: コンピューターの持ち主と権限

- 状態: 採用
- Issue: #131（task）。オーナーの回答（#131 のコメント）: 保存の形は A（ケースの BE の NBT）、公開 API に出さない、権限のない人の破壊は止める
- 前提: ADR 0081（マシン ID と `owner.bin`）、0129（隣接デバイスの結線）、0202（コンフィグ）、#360（キーボードのセッション）
- 後続: #110（OS からの依頼とゲーム内の承認。ADR 0110）・#206（メンバーを足す・外す・持ち主を変えるコマンド）、#190（ロボット。同じ core の型を使う）、#205（プレイヤーごとの台数の上限）、
  #224（1.20.1 への移植）、#251（仮値の確定）

## 決めたこと

### 用語と置き場

- 「持ち主」はケースを置いたプレイヤー。ADR 0081 の「owner」（マシン ID を持つブロックの位置。`owner.bin`・`MachineOwner`・`OwnerEpoch`・
  `KeyboardBlockEntity.owner`・`DeviceRegistry.Entry.owner`）とは別物で、名前も `PlayerAccess`・`playerAccess`・`ownerPlayerId` にした
- 判定と保存の規則は core の `pro.ikili.kibicomputers.core.access`（`PlayerAccess`・`PlayerAccessRecord`・`AccessRules`）。すべて
  `@ApiStatus.Internal` で、`pro.ikili.kibicomputers.api` には出さない
- 接続層（1.21.1）は、NBT の木の変換（`PlayerAccessNbt`）と、MC のイベント・GUI・キーボードへの差し込み（`CaseAccess`・`CaseBlock`・
  `CaseMenu`・`KeyboardSessions`）だけ
- `owner.bin`・世代ファイル・`MachineIdIndex`・スナップショット・クライアントへの同期には、何も足さない

### 保存の形（ケースの BE の NBT）

```
PlayerAccess: {
  Version: 1,                                         // int
  Owner: { Id: [I; ...], Name: "Alex" },              // Id は putUUID（int[4]）
  Members: [ { Id: [I; ...], Name: "Steve" } ],       // 空なら省く
  Pending: [ { Name: "notch", Created: 1731...L } ]   // Name は小文字、Created はエポックのミリ秒。空なら省く
}
```

- 持ち主がいなければ、タグを書かない。タグのない NBT は持ち主なし（`PoweredOn` と同じ扱い）。古いデータと `/setblock` の NBT は変わらない
- UUID は `putUUID`。1.20.1 にも同じ API があるので、#224 で同じ木にできる
- 知らない版（`Version` が 1 でない）・読めない形（`Owner.Id` がない、タグが複合タグでない）は **locked**（fail-closed）:
  OP だけが操作・管理でき、WARN を BE ごとに1回出す。読んだ生のタグを BE が持ち続け、そのまま書き戻す（`MachineId` の生の値と同じ考え方）。
  黙って持ち主なし（誰でも使える）に落とさない
- 形の崩れたメンバー（`Id` がない）・保留（`Name`・`Created` がない）は読み飛ばす（権限が減る向きにだけ崩れる）。
  同じ UUID のメンバー・持ち主と同じ UUID のメンバー・同じ名前の保留は、最初のものだけ残す。上限を超えていても捨てない
- 保存の前に期限切れの保留を捨てる。`setChanged` は、保留の昇格と名前の更新で値が変わったときだけ呼ぶ（アクセスのたびにチャンクを汚さない）
- `/data merge`（OP）による `loadAdditional` は、そのまま受け入れる

### 判定

| 状態 | 操作（GUI・キーボード・破壊） | 管理（追加・削除・持ち主の変更） |
| --- | --- | --- |
| 持ち主なし | 誰でも | 誰でも |
| 持ち主あり | 持ち主・メンバー・OP | 持ち主・OP |
| locked（読めない） | OP だけ | OP だけ |

- 持ち主・メンバーの照合は UUID。名前で照らすのは保留だけで、`lowercase(Locale.ROOT)` で比べる
- OP は `hasPermissions(access.opPermissionLevel)`（既定 2）
- 管理の判定（`isManager`）と、#110・#206 が呼ぶ操作（`addPending`・`removeMember`・`transferOwner`）は core に作って単体テストした。
  #131 では、プレイヤーがメンバーを足す口はない
- `removeMember` は、UUID の文字列ならその UUID のメンバーを、名前ならその名前の保留だけを外す。名前ではメンバーを外さない
  （最後に見た名前は古くなりうるので、改名前に同じ名前だったメンバーを巻き込まない）。名前でメンバーを外す口（#110・#206）は、
  サーバーで今の名前から UUID を引いて渡す
- 画面の閲覧は制限しない

### 保留の追加

- 名前で持つ（オフラインのプレイヤーの UUID を引かないため）。保留に入れられる名前は `[A-Za-z0-9_]{1,16}`。持ち主・メンバーの最後の名前は検査しない
- アクセス（ケースの右クリック・キーボードのセッションを開く）のとき、名前の一致する期限内の保留があれば、メンバーに昇格して保留を消す。
  破壊と、GUI を開いている間の確かめはアクセスに数えない
- 期限: `now - created >= ttl` で期限切れ（ちょうども期限切れ）。時計が戻ったとき（`created > now`）は年齢 0 で、期限切れにしない
- 重複は断る（大文字・小文字を無視して同じ名前の保留だけ）。持ち主・メンバーの名前とは照らさない（最後に見た名前は古くなりうるので、
  UUID の照合が原則。名前だけで別のプレイヤーを締め出さない）。同じ名前の持ち主・メンバー本人が保留に入っても、アクセスでは持ち主・メンバーとして
  扱うので昇格せず、期限で消える。上限は追加のときだけ見る。
  メンバーの上限で昇格できないときは、保留を残す
- 時刻は呼び手が渡す（接続層は `System.currentTimeMillis()`）。core では時計を読まない
- `transferOwner` では元の持ち主をメンバーに残さない。locked の記録からも変えられる（OP が直す）

### 設置

- `CaseBlock.setPlacedBy` で、`PlayerAccess` を常に置き換える。置いた人が本物の `ServerPlayer`（`FakePlayer` でない）ならその人が持ち主、
  それ以外（FakePlayer・ディスペンサー・`/setblock`・構造物）は持ち主なし
- 常に置き換えるのは、クリエイティブのピックブロックで写したアイテムの `BLOCK_ENTITY_DATA` の他人の持ち主を引き継がないため
  （`BlockItem.place` は `BLOCK_ENTITY_DATA` を当てた後に `setPlacedBy` を呼ぶ）
- 構造物のテンプレートに入った持ち主（`/clone`・ストラクチャーブロックで写したもの）は残る。テンプレートを作れるのは OP なので受け入れる

### 止めるもの

- **GUI**: `CaseBlock.useWithoutItem` で、アクセス（昇格・名前の更新）→ 判定の順に処理し、拒んだら actionbar に
  `message.kibicomputers.access.denied`（「%s のコンピューターです」）を出す。`CaseMenu.stillValid`（副作用なし）と `clickMenuButton` も
  サーバーで確かめるので、メンバーから外れた人の GUI は次の操作・tick で閉じる
- **プレイヤーの破壊**（FakePlayer を含む）: `BlockEvent.BreakEvent` で、権限のない人だけ取り消す
  - BreakEvent は `playerWillDestroy`（ここで保存のディレクトリを消す）より前に呼ばれるので、取り消せば保存は残る。クリエイティブの
    即時の破壊もこの経路を通る
  - `onDestroyedByPlayer` では止めない（ディレクトリが消えた後）。`getDestroyProgress` だけでも止めない（クリエイティブで抜けられる）
  - 取り消すと、NeoForge の `CommonHooks.fireBlockBreak` が `ClientboundBlockUpdatePacket` を送り、クライアントのブロックを戻す
  - 他 MOD の破壊機（FakePlayer）も、メンバーでなければ止まる。#190 の「持ち主が壊せない場所はロボットも壊せない」と整合する
- **爆発**: 持ち主のある（locked を含む）ケースは、誰が起こした爆発でも壊れない。持ち主のないケースは、これまでどおり壊れる
  - 根拠: 権限のない人の破壊を止める理由は「壊して部品とディスクを拾えば GUI の制限を回避できる」こと。爆発は誰が起こしたかを
    確かめられない（ディスペンサーやレッドストーンで点けた TNT は `getIndirectSourceEntity()` が null。クリーパーは誰のものでもない）ので、
    人では判定せず一律に止める。持ち主・メンバーが撤去したいときは、手で壊す
  - `CaseBlock.onExplosionHit` で止める。NeoForge 1.21.1 の `BlockBehaviour.onExplosionHit` はルートテーブルのドロップを作ってから
    `onBlockExploded` を呼ぶので、`onBlockExploded` だけで止めるとブロックが残るのにケースのアイテムが落ちる。`onBlockExploded` でも
    同じく守る（他 MOD が直接呼ぶ経路への備え）
  - **クライアントでは、どちらも常に何もしない**。`ServerLevel.explode` は `toBlow`（持ち主のあるケースの位置も入る）をそのまま
    `ClientboundExplodePacket` に入れ、クライアントの `handleExplosion` は `finalizeExplosion(true)` で各位置の `onExplosionHit` を呼ぶ。
    サーバーのブロックが変わらなければ更新のパケットは出ないので、クライアントで予測して消すと、チャンクを読み直すまで食い違う。
    クライアントは持ち主を知らないので、ケースでは爆発の予測をしない。持ち主のないケースは、サーバーの `setBlock(AIR)` の更新で消える
  - `ExplosionEvent.Detonate` で `toBlow` から外す方法は採らない。`Explosion.explode()` を通る爆発にしか効かず、他 MOD のイベントの順序にも
    左右される。サーバーの `onExplosionHit` の守りは、どちらの方法でも要る
  - 持ち主の有無をクライアントに同期する案も採らない（設置・持ち主の変更の直後の遅れで食い違い、送らない方針も崩れる）
  - 爆発耐性は変えない（ケースが後ろのブロックを守る盾にならないように）。光線は今までどおり通り抜け、ケースだけが残る
- **mob の破壊**（ウィザー・エンダードラゴンなど）: `LivingDestroyBlockEvent` で、持ち主のあるケースなら取り消す
- **キーボード**: デバイスには持ち主を持たせない。キーボードがケースにつながっていれば、そのケースの権限で確かめる
  - `KeyboardSessions.open` で確かめ、アクセスに数える（保留の昇格）。拒んだら知らせる
  - 入力のたびと tick ごと（`isValid`）にも、副作用なしで確かめ、だめなら閉じる。つながっていない間に開いたセッションが、後から
    持ち主のあるケースにつながった場合も閉じる。かかるのは開いているセッションの数だけで、台数に比例しない
  - つながっていないキーボードは誰でも開ける（入力の届く先がない）
  - ADR 0129 の「再起動の後にキーボードのつながる先が入れ替わる」危険は受け入れたまま。ただし、他人のケースにつながったときは
    「漏れ」ではなく「締め出し」になる

### 止めないもの

- `/setblock`・`/fill` など OP のコマンド（OP は素通りの規則と同じ）
- イベントを出さずに `level.destroyBlock`・`removeBlock` を呼ぶ他 MOD の処理。KiBi の側からは差し込めないので、保護 MOD と同じく
  その MOD の責任とする
- ピストン（BE のあるブロックを押さない）

### クライアントには送らない

- `getUpdateTag` は上書きしないので、`PlayerAccess`（メンバーの UUID）はクライアントに届かない
- クライアントは持ち主の有無で振る舞いを変えない。拒んだことは、サーバーから actionbar の文言で知らせる

### 仮値（コンフィグの区画 `access`。読み直しで効く。確定は #251）

| 項目 | 既定 | 範囲 |
| --- | --- | --- |
| `opPermissionLevel` | 2 | 1〜4 |
| `pendingInviteHours` | 24 | 1〜720 |
| `maxMembers` | 32 | 0〜256 |
| `maxPendingInvites` | 8 | 0〜64 |

### 負荷とスレッド

- tick ごとにケースや保留を見回らない。ワールド単位の索引やログインのときの走査も作らない。期限切れの保留は、アクセスと保存のときに捨てる
- 判定は UUID の比較と、上限のある表の走査と、`hasPermissions` だけ
- 読み書きはすべてサーバーのメインスレッド。`PlayerAccess` は変わらない値で、BE は参照を差し替える（変わったかは参照の比較で分かる）

## 採らなかったもの

- B（チーム）、C（マシンの索引）、D（信頼するプレイヤーの表）: v1 では作らない（オーナーの回答）
- 持ち主・権限を公開 API に出すこと（オーナーの回答）
- スケジューラの「持ち主 → マシン」の二段の公平と、持ち主ごとの上限: 別の Issue にした
