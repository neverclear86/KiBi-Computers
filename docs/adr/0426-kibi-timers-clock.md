# 0426: 独自拡張 kibi:kernel/timers と kibi:computer/clock

- 状態: 採用
- Issue: #426（task）。前提: ADR 0063（イベントキュー）、0064（タイマーと時計）、0072（p1 の `poll_oneoff`）、0073（`kibi:*` の WIT と p1 の import の導き方）、0075（`kibi:io`。`wasi:io` の版）
- 後続: #439（接続層からゲーム内時間を渡す）、#264（Canonical ABI。ADR 0264）・#685・#266（p2・p3 の層）、#265（Component Model の resource のハンドル）、#267（対象の `wasi:*` の版）、#116（SDK の束縛）、#94・#95（Lua の `os.sleep`・`event`）、#251（仮の値の確定）
- ABI の選択（`set-after`・`set-at` の2関数、ナノ秒の `u64`、`interval = 0` で1回だけ、`create` の引数、2^63 以上の頭打ち、`uptime-ticks`・`world-ticks`、sleep の import を足さない、p2/p3 の待ち方は別 interface、p3 は `async func`）は、#426 のプランへのオーナーの回答（論点1〜7、A・B）で決めた。p2/p3 の WIT は仮置きで、細部はエージェントに任された

## 決めたこと（要約）

- `kibi:kernel@0.1.0` に `interface timers`（resource `timer`）、`kibi:computer@0.1.0` に `interface clock` を置き、p1 向けの import を ADR 0073 の規則で導いて実装した
- 現在時刻と単調時計は `wasi:clocks`（p1 の `clock_time_get`）のままで、`kibi:*` には入れない。sleep の独自の import も足さない
- p2・p3 の待ち方は、`wasi:io` に依存しないよう別の interface（`timers-poll`・`events-poll`・`timers-async`・`events-async`）に置く。**仮置き**: interface・関数・型・`wasi:*` の版は、p2/p3 の層（#685・#266）を作るときに直してよい（v0.1.0 までは版を変えずに直す。ADR 0073 の Q5-A）。WIT とこの ADR だけで、実装しない
- 公開 API（`pro.ikili.kibicomputers.api`）と保存のバイト形式は変えない。足した型（`KibiTimers`・`KibiClock`）はすべて Kotlin の `internal object`。タイマーは既存の `KernelState.objects` の `Timer`、時計は `ClockSnapshot`（ADR 0064）で、新しく持つ状態はない

## WIT（p1 で実装するもの）

`core/src/main/wit/kibi-kernel/timers.wit`・`kibi-computer/clock.wit`:

```wit
interface timers {
    use types.{error-code};
    use events.{event-queue};
    resource timer {
        create: static func(events: option<tuple<borrow<event-queue>, s64>>) -> result<timer, error-code>;
        set-after: func(duration: u64, interval: u64) -> result<_, error-code>;
        set-at: func(instant: u64, interval: u64) -> result<_, error-code>;
        cancel: func() -> result<_, error-code>;
        read: func() -> result<u64, error-code>;
    }
}

interface clock {
    uptime-ticks: func() -> u64;
    world-ticks: func() -> u64;
}
```

- 時間はナノ秒。時計は `wasi:clocks/monotonic-clock`（p1 の `clock_time_get(MONOTONIC)`。マシンの単調時計。ADR 0064）と同じ
- `create` は止まった状態で作る。`events` を渡すと鳴るたびにそのキューへイベントを入れ（下の「timer のイベントの値」）、そのときは `read` は 0 を返し、`FD_READ` にもならない
- `set-after` は今から `duration` 後（0 ならすぐ）、`set-at` は単調時計が `instant` になったら（過ぎていればすぐ）。`interval` が 0 なら1回だけ、0 でなければその間隔で繰り返す。設定し直すと回数は 0 に戻る
- `cancel` は止めて回数も 0 に戻す。`read` は前回からの回数を取り出して 0 に戻す（待たない。Linux の timerfd と同じ形）
- `uptime-ticks` は起動からの経過（tick。起動したときが 0。動いていた tick だけを数え、保存・復元をまたいで続く）、`world-ticks` はゲーム内時間（`Level.getGameTime()` 相当。ワールドがなければ 0。保存・復元で飛ぶ）。どちらも p1・p2・p3 で同じ形（待たない）なので、p2/p3 用の別 interface はない
- `world imports`（`kibi:kernel`）に `import timers;`、`world imports`（`kibi:computer`）に `import clock;` を足した

## p1 向けの import

| モジュール / 名前 | core の型 |
| --- | --- |
| `kibi:kernel/timers@0.1.0` / `[static]timer.create` | `(events_is_some: i32, events: i32, tag: i64, ret_ptr: i32) -> ()` |
| `kibi:kernel/timers@0.1.0` / `[method]timer.set-after` | `(self: i32, duration: i64, interval: i64, ret_ptr: i32) -> ()` |
| `kibi:kernel/timers@0.1.0` / `[method]timer.set-at` | `(self: i32, instant: i64, interval: i64, ret_ptr: i32) -> ()` |
| `kibi:kernel/timers@0.1.0` / `[method]timer.cancel` | `(self: i32, ret_ptr: i32) -> ()` |
| `kibi:kernel/timers@0.1.0` / `[method]timer.read` | `(self: i32, ret_ptr: i32) -> ()` |
| `kibi:kernel/timers@0.1.0` / `[resource-drop]timer` | `(self: i32) -> ()` |
| `kibi:computer/clock@0.1.0` / `uptime-ticks` | `() -> i64` |
| `kibi:computer/clock@0.1.0` / `world-ticks` | `() -> i64` |

- `timer` のハンドルはカーネルの fd 番号（ADR 0073 の Q3-A）。鳴った回数が 1 以上の間、`poll_oneoff` の `FD_READ` で待てる（`FdIo.pollLocked` の汎用の分岐。ADR 0072）。`fd_read` はイベントキューと同じく `INVAL`（ADR 0058・0064）、`fd_close` でも閉じられる
- `option<tuple<borrow<event-queue>, s64>>` は平らにすると `(判別子 i32, ハンドル i32, tag i64)`。判別子が 0 なら後ろの2値は読まない（Canonical ABI どおり）。0・1 以外は `invalid`
- `result<_, error-code>` も平らにすると 2 値なので retptr（MAX_FLAT_RESULTS = 1）
- どれも可変長の結果を持たないので `cabi_realloc` は要らない。どれも待たないので `WasiP1.WAIT_IMPORTS` に入れない（待機点型・Asyncify の巻き戻しの対象外）
- 燃料（仮の値。#251）: どれも呼び出しごとに `WasiP1Fd.CALL_FUEL`
- 読み込みの検査（型違い・表にない関数・版違いは `NOEXEC`）は、`WasiP1Kibi` の表に足したので、今の `WasiP1.checkImports` が行う。`timers-poll`・`events-poll`・`timers-async`・`events-async` は p1 の表に入れない（import すると「表にない」で `NOEXEC`）

### メモリ上の配置（Canonical ABI どおり）

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `result<timer, error-code>` | `{ tag: u8 @0, payload @4 }`（ok ならハンドル i32、err なら判別子 u8） | 8 / 4 |
| `result<_, error-code>` | `{ tag: u8 @0, err: u8 @1 }` | 2 / 1 |
| `result<u64, error-code>` | `{ tag: u8 @0, ok: u64 @8 \| err: u8 @8 }` | 16 / 8 |

`result<u64, …>` のペイロードは、場合の中で最大の align（u64 で 8）に揃うので @8 から始まる。使わない所（詰め物）は 0 にして書く。C の手書きの宣言は `core/src/main/wasm/common/kibi_timers.h`・`kibi_clock.h`（`_Static_assert` で上のオフセットを確かめる）。`result<_, error-code>` の構造体は `kibi_io.h` と共通（先に読んだ方が定義する）。

### 引数の写し方と検査

| 条件 | 返すもの |
| --- | --- |
| `events_is_some` が 0・1 でない | `invalid` |
| `events` がイベントキューでない・開いていない・読めない fd | `bad-descriptor` |
| キューのフィルターが `timer` を受け取らない | `invalid` |
| ハンドル表が満杯 | `mfile` |
| `self` がタイマーでない fd（`set-after`・`set-at`・`cancel`・`read`） | `bad-descriptor` |
| `interval` が 1..`MIN_INTERVAL_NANOS`-1（仮の値 50 ms。#251） | `invalid` |
| マシンの動いているタイマーが上限（仮の値 128。`ResourceLimitException`） | `nfile` |
| `[resource-drop]timer` の `self` がタイマーでない | トラップ（`[resource-drop]event-queue` と同じ） |
| retptr が線形メモリの外・align に揃っていない | トラップ（ADR 0073） |

- `u64` の引数（`duration`・`instant`・`interval`）は、2^63 以上（core の i64 で負）を `Long.MAX_VALUE` に頭打ちにする（論点5-A）。カーネルは期限を `saturatingAdd` で出すので溢れず、ゲストの入力で `IllegalArgumentException` は出ない。`Long.MAX_VALUE` の期限・間隔は、実際には鳴らない
- 結果の `u64`（`read`・`uptime-ticks`・`world-ticks`）は、カーネルの `Long`（どれも 0 以上）をそのまま書く。`world-ticks` は環境の `MachineClock.worldTicks()` が負なら 0 にする
- `tag` は `s64` のままカーネルの `Long` へ（イベントの値 `integer(tag)` で同じ値が戻る。#65 で `%s64` から改名した）
- 上限は `nfile`、ハンドル表は `mfile`、`self` の誤りは `bad-descriptor`、不正な drop はトラップ、という分け方は ADR 0073 の `events` にそろえた

## timer のイベントの値

`event { kind: "timer", source: "", values: [ integer(tag), integer(回数) ] }`（ADR 0073 の `event-value` のまま。新しい型は足さない。#65 で `%s64` から改名した）。

- 回数は 1 以上で、遅れて鳴った繰り返しのタイマーは、過ぎた回をまとめた数（ADR 0064）
- キューが満杯なら捨てて `dropped` に数え、キューが閉じていれば捨てる
- どのタイマーかは `tag` で見分ける。`source` は使わない（fd 番号は子に渡すと・`dup` すると変わるため）

## sleep

独自の import は足さない（論点7-A）。p1 は `poll_oneoff` の時計の購読（#72。wasi-libc の `nanosleep`・`clock_nanosleep`・`usleep` はこれで動く）、p2 は `wasi:clocks/monotonic-clock` の `subscribe-*`、p3 は `wait-for`・`wait-until` を使う。タイマーの fd と並べて待ちたいときは、`poll_oneoff` に時計の購読とタイマーの `FD_READ` を並べる。

## WIT（p2/p3。仮置き。実装しない）

```wit
interface timers-poll {        // p2
    use timers.{timer};
    use wasi:io/poll@0.2.3.{pollable};
    subscribe: func(t: borrow<timer>) -> pollable;
}

interface events-poll {        // p2
    use events.{event-queue};
    use wasi:io/poll@0.2.3.{pollable};
    subscribe: func(q: borrow<event-queue>) -> pollable;
}

interface timers-async {       // p3（wasi:* に依存しない）
    use types.{error-code};
    use timers.{timer};
    wait: async func(t: borrow<timer>) -> result<u64, error-code>;
}

interface events-async {       // p3（wasi:* に依存しない）
    use types.{error-code};
    use events.{event-queue, event-batch};
    take: async func(q: borrow<event-queue>, max: u32) -> result<event-batch, error-code>;
}

world imports { import types; import events; import timers; }   // p1（wasi:* を参照しない）
world imports-p2 { include imports; import timers-poll; import events-poll; }
world imports-p3 { include imports; import timers-async; import events-async; }
```

- `timers-poll.subscribe` の `pollable` は回数が 1 以上で ready。キューを渡して作ったタイマーは ready にならない（p1 で `FD_READ` にならないのと同じ。キューの側で待つ）
- `events-poll.subscribe` の `pollable` は、イベントが届く（か、あふれて捨てた分がある）と ready（p1 の `FD_READ` と同じ条件）
- `timers-async.wait` は回数が 1 以上になるまで待って取り出す（`read` を待つ形にしたもの）。キューを渡して作ったタイマーでは、待たずに `invalid`（ずっと鳴らないため）
- `events-async.take` は 1 つ以上届くまで待ってから、`event-queue.take` と同じに取り出す
- `timers`・`events` 自体は p1・p2・p3 共通なので、`imports-p2`・`imports-p3` は `include imports;` に待ち方の interface を足したものにした（ADR 0075 の `kibi:io` と同じ名前の付け方）
- 同梱の wasm-tools は `async func` を解析できた（`KibiWitParseTest`）ので、p3 の2関数は `async func` のまま置く

### `wasi:io` の版を 0.2.3 にした理由

プランの第2版では 0.2.0 だったが、その後に main に入った #75（ADR 0075）の `kibi:io/pipe-streams` が `wasi:io/streams@0.2.3` を使っている。`pipe-streams` の stream の `subscribe()` が返す `pollable` は `wasi:io/poll@0.2.3` の型なので、`timers-poll` が 0.2.0 の `pollable` を返すと、1つのゲストの中で型が別になり、同じ `poll()` に並べて渡せない。オーナーの回答（p2/p3 の形と `wasi:*` の版は仮置きで、細かい選択はエージェントが決めてよい）の範囲として、リポジトリの中で版を1つにそろえた。#267 で対象の版が決まったら、`pipe-streams` と一緒に直す。`KibiWitTableTest` が、`*-poll` の `wasi:io` の版が `pipe-streams` と同じであることを確かめる。

### p2/p3 への写し方（仮置き）

| 項目 | p1 | p2 | p3 |
| --- | --- | --- | --- |
| `timer`・`event-queue` のハンドル | カーネルの fd 番号 | コンポーネントの resource のハンドル（#265。中身はカーネルの同じ `Timer`・キュー） | p2 と同じ |
| `create`・`set-*`・`cancel`・`read`・`open`・`take`、`clock` の2関数 | 上の import | 同じ WIT の関数（同期） | 同じ（同期） |
| タイマーを待つ | `poll_oneoff` の `FD_READ` | `timers-poll.subscribe` の `pollable` | `timers-async.wait` |
| キューを待つ | `poll_oneoff` の `FD_READ` | `events-poll.subscribe` の `pollable` | `events-async.take` |
| sleep | `poll_oneoff` の時計 | `wasi:clocks` の `subscribe-*` | `wasi:clocks` の `wait-*` |
| `stream` | 使わない | 使わない | 使わない（論点B-B1） |

## 実装

- WIT: `core/src/main/wit/kibi-kernel/{timers,timers-poll,timers-async,events-poll,events-async,world}.wit`、`kibi-computer/{clock,world}.wit`。`events.wit` の KDoc の待ち方の記述を、`events-poll`・`events-async` への参照に直した
- `core.wasi.kibi.KibiTimers`・`KibiClock`（意味の部分。ABI の層に依らない。カーネルの `ctx.createTimer`・`setTimer`・`cancelTimer`・`readTimer`・`uptimeTicks`、環境の `MachineClock.worldTicks` を写す）
- `core.wasi.p1.P1Canonical`: `liftOptionDisc`・`u64Arg`・`lowerU64Result`（`U64_RESULT_SIZE`・`U64_RESULT_ALIGN`）を足した
- `core.wasi.p1.WasiP1Kibi`: `TIMERS`・`CLOCK` と上の 8 関数を表に足した
- C: `core/src/main/wasm/common/kibi_timers.h`・`kibi_clock.h`（手書き。wit-bindgen で作る束縛は SDK #116）
- `WasiP1.kt`・`ProcessContext.kt`・カーネルの `time` パッケージ・`platform-*` は変えていない
- 接続層はまだゲーム内時間を渡していない（`MachineClock.SYSTEM` のまま）ので、ゲームの中の `world-ticks` は今は 0。渡すのは #439

## テスト

- `KibiTimersTest`（手で進める時計とタイマー）: キューなし・ありの作成、`set-after`・`set-at`（0・過去を含む）、繰り返しと `cancel`、`interval` の境界、頭打ちの最大値で溢れない、上限で `nfile`、キューへのイベントの値（`Integer(tag)`・`Integer(回数)`。#65 で `I64` から改名した）、キューでない fd・`timer` を受け取らないキュー、タイマーでないハンドル、`drop` で予定も消える
- `KibiClockTest`: `uptime-ticks` が tick とともに進む、`world-ticks` が環境の `worldTicks` を返す（負は 0、ワールドがなければ 0）
- `P1CanonicalTest`: `lowerU64Result` の配置と詰め物、範囲外・align 違いのトラップ、`liftOptionDisc`、`u64Arg` の頭打ち
- `WasiP1ImportCheckTest`: 8 関数の型が合えば `cabi_realloc` なしで通り、型違い・版違い・表にない名前・p2/p3 の interface は `NOEXEC`
- `KibiWitTableTest`: 関数の正規表現を `async func` に対応させた。`timers`・`clock` の関数がすべて p1 にある、p2/p3 の interface が WIT にあって p1 に出していない、`kibi:kernel` の p1・p3 の部分と `clock` が `wasi:*` を参照しない、`*-poll` の `wasi:` の `use` は `wasi:io/poll@0.2.3.{pollable}` だけで、`pipe-streams` と同じ版
- `KibiWitParseTest`: 新しい WIT を含めて wasm-tools で解析できる（`wit-stubs/wasi-io/poll.wit` を足した）
- `WasiP1TimersTest`（`kibi_timers.c` を Endive とカーネルで。待機点型・ブロッキング型の両方）: `set-after` を `poll_oneoff` で待って `read`、待機点型は待つ間スレッドを持たない、繰り返しと `cancel`、`set-at` と過ぎた時刻、キューへのイベント、不正な入力の error-code、判別子 0 なら後ろの値を読まない、`[resource-drop]`・`fd_close` の後は `bad-descriptor`、不正な `[resource-drop]timer` がトラップ、`uptime-ticks`・`world-ticks`

## 決めないこと

- p2/p3 の層の実装（`pollable`・async、resource のハンドル）: #685（p2）・#265（resource）・#266（p3）。上の p2/p3 の WIT は仮置き
- 対象の `wasi:*` の版: #267
- 接続層からゲーム内時間を渡すこと: #439
- Lua の `os.sleep`・`event` との対応: #94・#95
- 最小の間隔・動いているタイマーの上限・燃料の確定: #251
