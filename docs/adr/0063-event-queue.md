# 0063: イベントキューとイベント待ち

- 状態: 採用
- Issue: #63（task）。前提: ADR 0043（カーネル全体の設計）、0058（fd 表）
- 後続: #64（タイマー）、#65・#67（デバイスのイベント・接続と切断）、#72（p1 の `poll_oneoff`）、#73（`kibi:*` の WIT と p1 の import）、#81・#82（保存）、#94・#95（Lua の `event`）、#143（マウス）、#164（ネットワーク）、#266（p3 の waitable-set）、#316（コマンド型の p1 の待たせ方）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- コードは `core.kernel.event`。どれも `internal`。公開 API・ABI・保存のバイト形式は足さない
- **イベントキューはプロセスが開くカーネルオブジェクト**（`EventQueue`）。ハンドル表（fd 表）に読む権利だけで入り、`READABLE` を待てる。マシンに1つの共有キューにはしない（プロセスごとに受け取る種類を選べ、1つのプロセスが複数開いてもよい。#72 では fd として、#266 では waitable として使える）
- **配り方は同報**: `Machine.postEvent` は、種類の合うすべてのキューに同じ `KernelEvent`（変更できない値）を入れる。キューがなければ捨てる（マシン側には溜めない）
- **あふれたら新しいイベントを捨てて数える**（キューが満杯のとき、マシン全体の溜めている量が上限のとき）。数は次の取り出しで `EventBatch.dropped` として知らせ、0 に戻す。古いイベントを消すと、読み手が途中の状態を見失うため
- 待ち方は ADR 0043 の土台そのまま。待機点型（`WAIT_POINT`）は `ctx.eventWaitKey` を `StepResult.Wait` に入れ、ブロッキング型は `ctx.awaitEvents`（`ctx.block`）で待つ。ほかの `WaitKey`（`ByteStream`、タイマー）とまとめて待ってよい
- 燃料の費用（システムコールの課金）は付けない（#47・#251）

## 構造

```
KernelState（保存の対象）
 ├─ objects: ObjectTable
 │   └─ EventQueue（プロセスがハンドルで持つ）
 │       ├─ filter: EventFilter（受け取る種類。空ならすべて）
 │       ├─ capacity（1..MAX_CAPACITY）
 │       ├─ events（FIFO。最初に入れるときに作り、空になったら手放す）
 │       ├─ pendingBytes（溜めているイベントの footprint の合計）
 │       └─ dropped（あふれて捨てた数）
 └─ events: EventHub（種類 → キューのオブジェクトID の索引。番号と文字列だけ）

Machine.postEvent(event)  ← どのスレッドからも（カーネルロックを短く取る）
  → EventHub が索引から候補を引き、生きていて場所に合うキューだけに配る
  → 各キューの WaitKey(id, READABLE) を wakeSink に知らせる
  → 待っている WAIT_POINT のプロセスは CPU の待ち行列へ（ProcessWaiter）。BLOCKING は条件変数で起きる
```

### カーネルの中の口

| 口 | 振る舞い |
| --- | --- |
| `Machine.postEvent(event)` | 出す側（#64・#65・#67・#143・#164）が使う。待たない。配ったキューの数を返す（捨てた分は数えない）。一時停止中・止まったマシンでも受け付ける |
| `ctx.openEventQueue(filter, capacity)` | キューを作ってハンドル表に入れる。確かめる順は capacity（`INVAL`）→ マシンのキューの数（`ResourceLimitException`。errno への写し方は #73）→ ハンドル表（`MFILE`）。失敗したら何も残さない |
| `ctx.takeEvents(handle, max)` | 待たずに最大 `max` 個取り出す。キューでない・読めないハンドルは `BADF`、`max < 1` は `INVAL` |
| `ctx.eventWaitKey(handle)` | `WaitKey(objectId, READABLE)` |
| `ctx.awaitEvents(handle, max)` | ブロッキング型だけ。空でない結果（イベントか、捨てた知らせ）になるまで `ctx.block` で待つ |

- キューの fd への `fd_read` は `INVAL`（読み書きを持たない種類のオブジェクトと同じ。ADR 0058）、`fd_write` は書く権利がないので `BADF`
- 待機点型のドライバーの書き方:

```kotlin
override fun start(ctx: ProcessContext): StepResult {
    queue = ctx.openEventQueue(EventFilter(setOf("test")))
    return StepResult.Wait(listOf(ctx.eventWaitKey(queue)))
}
override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult {
    val batch = ctx.takeEvents(queue)
    // WASM の export を呼んで渡す（形は #73）。続けて待つなら Wait、終わるなら Exit
    return StepResult.Wait(listOf(ctx.eventWaitKey(queue)))
}
```

### イベントの中身

`KernelEvent(type, source, values)`。`type` は種類（名前は出す側の Issue が決める）、`source` は出したもの（デバイスの識別子など。なければ ""）。値は `EventValue` の `I64`・`F64`・`Bool`・`Text`・`Bytes`（保存できる値だけ。`Bytes` は作るとき・外へ渡すときに写す）。WASM・WIT への写し方は #73。

### 索引の掃除

`onLastClose` は `KernelState` に触れないので、キューが閉じても索引はすぐには直さない。どれも「`objects[id]` が、索引の場所に合う `EventQueue` でない ID を外す」だけ（件数は索引の ID の数に比例する）。

- `subscribe`: 上限を確かめる前に掃除する。キューの数は生きているキューだけを数える（一度も post されない種類のキューを開いて閉じるのを繰り返しても、上限が埋まらない）
- `post`: 候補を引いたときに外す
- `Machine.beginBoot`（再起動を含む）: 掃除する
- 場所に合うとは、すべてを受け取る索引ならフィルターが空、種類の索引ならフィルターがその種類を含むこと。`ObjectTable` の ID が一周して使い回されたとき、古い種類の下に残った ID から配らない・二重に配らないため
- プロセスが終わるときの掃除には頼らない（fd は `SpawnOptions.fds` で子へ渡せるので、開いたプロセスが終わってもキューが生きていることがある）

### メモリの数え方

マシン全体の量は、`post` のときに生きているキューの `pendingBytes` を足して求める（キューは 64 個まで）。別の合計を持たないので、閉じたキューの分が残って上限を食うことがなく、保存する値も増えない。同報で共有していても、キューごとに数える（安全側）。

`KernelEvent.footprint` は JVM の上での大きさを下回らないように数える（#63 のプランのレビューで、値のオブジェクトのコストが抜けていた指摘への対応）:

```
footprint = EVENT_OVERHEAD_BYTES(96)                 KernelEvent 自体、値の並び、キューの中の場所
          + OBJECT_OVERHEAD_BYTES(40) + type のバイト数
          + (source が空でなければ) 40 + source のバイト数
          + 値ごとに EVENT_VALUE_OVERHEAD_BYTES(32)   値のオブジェクトと並びの中の場所
          + Text・Bytes ごとに 40 + 中身のバイト数    String・ByteArray の本体
```

文字列のバイト数は UTF-8 で数える（JVM の内部表現（Latin-1 か UTF-16）の大きさを下回らない）。

## 規模

- 待っているだけのキューに tick ごとの処理はない。`post` は索引の候補とキューの数（≤ 64）に比例するだけで、台数によらない。待っている待機点型のプロセスはスレッドを持たない（`ScaleTest` で 1,000 台を確かめる）
- メモリ（仮の値）: 1台あたり、溜めているイベントの見積もりは `MAX_PENDING_BYTES` = 256 KiB まで（キューの数・件数によらない）。見積もりが実際の大きさを下回らないので、実際の量もほぼこれまで。いちばん小さいイベント（種類 1 バイト、値なし）は 137 バイトに数えるので、1台で溜められるのは約 1,900 件まで。値を 32 個持つ数値のイベントは 1,161 バイトで、約 225 件まで。1,000 台がすべて上限まで溜めた最悪でも約 256 MiB、待っているだけで溜めていない台はほぼ 0（空のキューは数十バイト、索引はキューの数に比例）

仮の値（確定は #251）: キューの件数 既定 64・最大 256、マシンあたりのキュー 64 個、マシン全体で溜める量 256 KiB、イベントの固定の見積もり 96 バイト・値 32 バイト・文字列とバイト列のオブジェクト 40 バイト、種類の名前 64 バイト、出したもの 128 バイト、値 32 個、文字列とバイト列の中身の合計 4 KiB、フィルターの種類 16 個。

## データ形式

保存のバイト形式は決めない（#81・#82）。メモリ上の持ち方だけを決める。

- `EventQueue`: フィルター（文字列の集合）、`capacity`、イベントの並び、`pendingBytes`、`dropped`。`footprint` と `pendingBytes` は中身から求め直せるので、保存しなくてもよい
- `EventHub`: 索引に入れた ID の集合、すべてを受け取るキューの ID の集合、種類 → ID の集合、上限。実行時だけのものを参照しない（`KernelStateIsolationTest`）
- 待っているプロセスは、これまでどおり `Process.wait` に `WaitKey` の並びを持つ。復元では `WaitRegistry` を作り直すだけでよい
- 索引に閉じたキューの ID が残っていてもよい（`subscribe`・`post`・起動で外れる）。#82 は書き出す前に `sweep` してもしなくてもよい

## 決めないこと

- WASM から見える ABI。渡す先は論点ごとに1つ:
  - イベントの `kibi:*` の interface（WIT）と p1 向けの import（キューを開く・取り出す、値の写し方）、reactor 型の `lua.wasm` へイベントを渡す export の形、p1 の reactor の待たせ方: **#73**
  - p1 の `poll_oneoff` でキューの fd を待つ: #72。p3 の waitable-set: #266
- ADR 0030・0043・0070 は、これらを「#63（・#72）で決める」としている。#63 はカーネルの中の口だけを作り、ABI は上の先（reactor の export とイベントの渡し方は #73）で決める。古い ADR は決めた時点の記録として書き換えない
- `lua.wasm` を reactor にするか（ADR 0030 の推奨）: #73 で export の形と合わせて決める。Lua の `event` ライブラリ: #94・#95。コマンド型の p1 の待たせ方: #316
- イベントの種類の名前と中身（タイマー、デバイス、ネットワーク、入力、画面の大きさ）: #64・#65・#67・#143・#164・ADR 0060
- 保存のバイト形式: #81・#82
- 他 MOD 向けの公開 API: #65
- 燃料の費用: #47・#251
