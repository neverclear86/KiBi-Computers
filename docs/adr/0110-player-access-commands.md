# 0110: 持ち主・権限の管理コマンド（OS からの依頼とゲーム内の承認）

- 状態: 採用
- Issue: #110（task）。プランを #110 に載せ、レビュー（3回）を経て採用した
- 前提: ADR 0131（ケースの持ち主と権限。`PlayerAccess`・`AccessRules`）、0046（マシンの中では権限を分けない）、0073（`kibi:*` の WIT と p1 の import の導き方）、0264（Canonical ABI）、0685（`kibi:*` の p2）、0103（基本コマンドの約束）、#545（チャンクの降格）
- 後続: #206（OS の外からのコマンド）、#224（1.20.1 への移植）、#116（SDK の束縛）、#251（仮の値の確定）

## 決めたこと

### 流れ

1. OS のプログラム（`access` コマンド）が `kibi:computer/access` の `request-add`・`request-remove`・`request-transfer` を呼ぶ。待たない
2. ワーカーで、引数の形を確かめ（`AccessRequest.parse`）、ケースごとの枠（`AccessRequestLimiter.tryReserve`）を取る。受け付けたら、`ProcessContext.postHostCallback` でメインスレッドに届けを積み、ok を返す
3. メインスレッドで、接続層（`CaseAccessPort.deliver` → `AccessRequests.deliver`）が今の `playerAccess` で依頼を振り分け、サーバー全体の表（`AccessRequestBook`、連番の ID）に載せ、通知する
4. 持ち主・OP（unowned の transfer は相手本人）が、チャットのボタンか `/kibicomputers access approve|deny <ID>` で決める。`/kibicomputers access list` で、自分が決められる依頼を見る
5. 結果は `get-access` で見る

プログラムはケースの持ち主・権限を直接は変えられない（マシンの中では権限を分けないので、OS の中のだれでも依頼は出せる。決めるのはゲーム内のプレイヤー）。

### ABI（`kibi:computer@0.1.0` の `interface access`）

- `get-access: func() -> result<access-info, error-code>`、`request-add: func(name: string)`、`request-remove: func(name-or-id: string)`、`request-transfer: func(name: string)`（どれも `-> result<_, error-code>`）
- `access-info` は `state`（`unowned`・`owned`・`locked`）、`owner: option<player>`、`members: list<player>`、`invites: list<string>`。`player` は名前と UUID の文字列（小文字の正規形）。期限の切れた保留は返さない（BE の値は変えない）。承認待ちの依頼は返さない
- 同期のエラーは `invalid`（形・64 バイト超・UTF-8 でない）、`not-supported`（口がない）、`again`（承認待ちが上限）だけ。文字列の範囲が外ならトラップ
- p1: モジュール `kibi:computer/access@0.1.0`。`get-access` は `cabi_realloc` が要る。p2・p3 も同じ interface（待たないので `-poll`・`-async` は作らない）
- `get-access` の燃料は、`(1 + メンバー数 + 保留数) × KibiFs.MOUNT_LIST_FUEL_PER_ENTRY`（仮の値）
- 書き先（retptr）は、依頼を受け付けて届けを積んだ後に確かめる。外なら依頼は届いたうえでトラップになる（`reboot` と同じく、効果を先に決める）

### 依頼の形と枠

- 名前は `[A-Za-z0-9_]{1,16}`。`remove` はほかに UUID の正規の形（36 文字。大文字も受け、小文字にそろえる）
- 同じ依頼（種類と引数。名前は大文字小文字を区別しない）は1つにまとめ（MERGED。ok を返し、もう一度は届けない）、1台の承認待ちが上限なら `again`
- 枠はケースの BE ごとに1つの `CaseAccessPort`（その `limiter`）が持ち、マシンを作り直しても続く。BE の読み込み直しで新しくなる（保存しない）
- 拒否では枠を返さない（期限まで占める。拒否した依頼の繰り返しを抑える）。承認（反映した・変わらなかった）と、届けで捨てたときに返す

### 届け（メインスレッド）

| 状態 | add | remove | transfer |
| --- | --- | --- | --- |
| owned | 表に載せ、オンラインの持ち主に通知 | 同左（名前なら、その名前のオンラインのプレイヤーを `target` に持つ） | 相手をオンラインのプレイヤーから引いて UUID を固定。いなければ捨てる。表に載せ、持ち主に通知 |
| unowned | 捨てる | 捨てる | 相手を固定し、表に載せ、相手本人に通知。いなければ捨てる |
| locked | 捨てる | 捨てる | 相手を固定し、表に載せる（通知しない。OP が決める） |

- 名前から UUID を引くのは `playerList.getPlayerByName` だけ（`GameProfileCache` はメインスレッドで外に問い合わせうるので使わない）
- 表の上限は 4,096 件（仮の値）。超えたら捨てる。捨てたときは枠を返す。ログインのときの知らせは作らない（ADR 0131）

### 承認・拒否

- 権限は `AccessApproval.mayDecide` の1つの関数で決め、approve・deny・`list` の絞り込みのすべてで使う。unowned の transfer は相手本人だけ（OP でも代われない。他人に台数を押し付けないため）。それ以外は `PlayerAccess.isManager`（owned は持ち主か OP、locked は OP だけ）。判定は決めるときの `playerAccess` で行う
- 実行者は本物の `ServerPlayer` だけ（FakePlayer とコンソールは不可）。OP の判定は `CaseAccess.isOp`。コマンドの権限レベルは 0 にして中で確かめる
- 順は「`peek` → BE の確かめ → `mayDecide` → 通ったときだけ `take`」。権限のない approve・deny は表も枠も変えない
- 「依頼がない」「BE が無効」「降格中」「権限がない」は同じ文言にする（連番の ID を当てても、他人のケースの依頼の有無が分からないように）

| approve の結果 | 表 | 枠 |
| --- | --- | --- |
| APPLIED | 外す | 返す |
| NO_CHANGE（もう入っている・上限・見つからない など） | 外す | 返す |
| NOT_PERMITTED | 残す | 返さない |
| TARGET_OFFLINE（transfer の相手がいない） | 残す | 返さない |

### BE の確かめ（チャンクを読み込まない）

`AccessRequests.check` は次の順に確かめる。

1. 弱い参照が切れた・`isRemoved`・`level` が `ServerLevel` でない → 無効（表から外す。枠は BE と一緒に捨てられる）
2. `!level.isLoaded(pos)`（#545 の降格中）→ 一時的に扱えない。外さず、枠も残し、反映もしない（`setChanged` が届かないため）。`getBlockEntity` を呼ばない
3. `level.getBlockEntity(pos) !== be` → 無効（置き直した別のケースには当てない）

`Level.getBlockEntity` は FULL でないチャンクを同期に読み込むので、必ず `isLoaded` の後に呼ぶ。`list` は1件ごとに、参照 → `isRemoved` → `mayDecide` → `isLoaded` → `getBlockEntity` の順にし、権限のない依頼ではワールドに触らない。

### 期限・保存・スレッド

- 期限（既定 300 秒）と1台の上限（既定 4）はコンフィグの `access.requestTtlSeconds`・`access.maxRequestsPerComputer`（読み直しで効く。仮の値。確定は #251）。期限切れは、依頼・届け・承認・一覧のときに遅れて捨てる。tick ごとの見回りとスレッドは作らない
- 承認待ちの依頼は保存しない（サーバーを止めると消える。`ServerStoppedEvent` で表を捨てる）。保存の形は変えない
- スレッド: `AccessRequestLimiter` は `synchronized`（ワーカーとメインスレッドが触る）。`CaseBlockEntity.playerAccess` は `@Volatile`（`get-access` がワーカーから読む。書くのはメインスレッドだけ）。表・通知・反映はメインスレッドだけ

### `cabi_realloc` の順

`get-access` は Canonical ABI の項目の順（持ち主の名前 → 持ち主の ID → `members` の配列 → 各メンバーの名前・ID → `invites` の配列 → 各保留）に `cabi_realloc` を呼ぶ。プランでは members → invites → owner の順と書いていたが、ほかの関数（ADR 0264）とそろえ、項目の順に直した。

### OS

- `access`（表示）・`access add NAME`・`access remove NAME|UUID`・`access transfer NAME`（`os/bin/access.zig`、man あり）。ファイルの権限と紛らわしい `chown` などの名前は使わない
- ファイルの持ち主・権限は範囲外（ADR 0046。マシンの中では権限を分けない）

## 採らなかったもの

- プログラムが直接変える: マシンの中では権限を分けないので、OS の中のだれでも他人を持ち主にできてしまう
- 承認を待つ関数（結果を返すまで止まる）: 期限が分単位で、待ちのスレッド・保存の扱いが増える。ok は「受け付けた」にし、結果は `get-access` で見る
- 依頼の保存: 期限が短く、保存の形を変えるほどの値がない
- 降格中の依頼を `take` する: 権限のない人が ID を当てるだけで他人の依頼を消し、枠をふさげる
