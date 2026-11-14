---
title: kibi:kernel
description: 共通の型（error-code）、イベントキュー、タイマーと、カーネルが出すイベントの種類。
---

`kibi:kernel@0.1.0` は、ほかのパッケージが共通に使う型と、カーネルのイベントのしくみ（イベントキューとタイマー）です。

## types

```wit
// kibi-kernel/types.wit
package kibi:kernel@0.1.0;
interface types {
    enum error-code {
        too-big,
        access,
        addr-in-use,
        addr-not-avail,
        af-not-supported,
        again,
        already,
        bad-descriptor,
        bad-message,
        busy,
        canceled,
        child,
        conn-aborted,
        conn-refused,
        conn-reset,
        deadlock,
        dest-addr-required,
        domain,
        quota,
        exist,
        fault,
        file-too-large,
        host-unreachable,
        identifier-removed,
        illegal-byte-sequence,
        in-progress,
        interrupted,
        invalid,
        io,
        is-connected,
        is-directory,
        loop,
        mfile,
        too-many-links,
        message-size,
        multihop,
        name-too-long,
        net-down,
        net-reset,
        net-unreachable,
        nfile,
        no-buffers,
        no-device,
        no-entry,
        no-exec,
        no-lock,
        no-link,
        no-memory,
        no-message,
        no-protocol-option,
        no-space,
        no-sys,
        not-connected,
        not-directory,
        not-empty,
        not-recoverable,
        not-socket,
        not-supported,
        no-tty,
        no-such-device,
        overflow,
        owner-dead,
        not-permitted,
        pipe,
        protocol,
        protocol-not-supported,
        protocol-type,
        range,
        read-only,
        invalid-seek,
        no-such-process,
        stale,
        timed-out,
        text-file-busy,
        cross-device,
        not-capable,
    }
}
```

`error-code` の各値と p1 の errno の対応は、[kibi:* の共通の規則](./kibi-abi.md#エラー)の表にあります。`types` には関数がないので、import はありません。

## events

イベントキューは、カーネルやデバイスが出すイベントを、受け取る種類を決めて溜めておくものです。待たずに取り出し、待つときは p1 ではキューの fd を `poll_oneoff` の `FD_READ` で待ちます。イベントが1つ以上あるか、あふれて捨てた分があると、そろいます。

```wit
// kibi-kernel/events.wit
package kibi:kernel@0.1.0;
interface events {
    use types.{error-code};
    variant event-value {
        integer(s64),
        float(f64),
        boolean(bool),
        text(string),
        bytes(list<u8>),
    }
    record event {
        kind: string,
        source: string,
        values: list<event-value>,
    }
    record event-batch {
        events: list<event>,
        dropped: u64,
    }
    resource event-queue {
        open: static func(kinds: list<string>, capacity: option<u32>) -> result<event-queue, error-code>;
        take: func(max: u32) -> result<event-batch, error-code>;
    }
}
```

- `event.kind` は種類（1〜64 バイトの UTF-8）、`source` は出したもの（デバイスのアドレスなど。なければ `""`）、`values` は値（32 個まで）
- `event-queue.open`: `kinds` が空なら、すべての種類を受け取ります。`kinds` は 16 個まで、それぞれ 1〜64 バイトの UTF-8。`capacity` を省くと既定の容量（仮の値）で、指定するなら 1〜256
- `event-queue.take`: 待たずに、古い順に最大 `max` 個を取り出します（なければ空）。`max` は 1 以上で、容量より大きければ「全部」の意味です。`dropped` は、前回の取り出しから、キューがいっぱいで捨てた数です
- 同じイベントは、それを受け取るすべてのキューに届きます

### p1 向けの import

モジュール名: `kibi:kernel/events@0.1.0`

<!-- check:imports kibi:kernel/events@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `[static]event-queue.open` | `(kinds_ptr: i32, kinds_len: i32, cap_is_some: i32, cap: i32, ret_ptr: i32) -> ()` | — | — |
| `[method]event-queue.take` | `(self: i32, max: i32, ret_ptr: i32) -> ()` | 要る | — |
| `[resource-drop]event-queue` | `(self: i32) -> ()` | — | — |
<!-- /check -->

### メモリ上の配置

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `string`・`list<T>` | `ptr: i32 @0`、`len: i32 @4` | 8 / 4 |
| `result<event-queue, error-code>` | 判別子 `u8 @0`。ok ならハンドル `i32 @4`、err なら `error-code` の `u8 @4` | 8 / 4 |
| `result<event-batch, error-code>` | 判別子 `u8 @0`。ok なら `events.ptr @8`・`events.len @12`・`dropped: u64 @16`、err なら `u8 @8` | 24 / 8 |
| `event` | `kind @0`、`source @8`、`values @16`（それぞれ `ptr`・`len`） | 24 / 4 |
| `event-value` | 判別子 `u8 @0`、値 `@8`（`integer`・`float` は 8 バイト、`boolean` は `u8`、`text`・`bytes` は `ptr @8`・`len @12`） | 16 / 8 |

`event-value` の判別子は `integer` = 0、`float` = 1、`boolean` = 2、`text` = 3、`bytes` = 4 です。`result<event-batch, …>` の payload は、`dropped`（u64）の align に揃うので @8 から始まります。

### 引数の検査

上から順に確かめ、最初に当たったものを返します。

| 条件 | 結果 |
| --- | --- |
| `kinds_ptr` が 4 に揃っていない（長さ 0 でも） | トラップ |
| `kinds_len` が 16 を超える | `invalid` |
| `kinds` の配列が線形メモリの外 | `fault` |
| 要素の長さが 1〜64 の外 | `invalid` |
| 要素の中身が線形メモリの外 | `fault` |
| 要素が正しい UTF-8 でない | `invalid` |
| `cap_is_some` が 0・1 でない、`capacity` が 1〜256 の外 | `invalid` |
| マシンのキューの数が上限 | `nfile` |
| fd の表が満杯 | `mfile` |
| `take` の `max` が 0 | `invalid` |
| `take` の `self` がキューでない | `bad-descriptor` |

返す文字列は UTF-8 です。

## timers

タイマーは、指定した時刻や間隔で鳴るカーネルのオブジェクトです。時計は単調時計（p1 の `clock_time_get(MONOTONIC)`）で、時間の単位はナノ秒です。鳴った回数が 1 以上の間、タイマーの fd は `poll_oneoff` の `FD_READ` でそろいます。

```wit
// kibi-kernel/timers.wit
package kibi:kernel@0.1.0;
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
```

- `timer.create`: 止まった状態のタイマーを作ります。`events` にキューとタグを渡すと、鳴るたびにそのキューへ種類 `timer` のイベントを入れます（このとき `read` は 0 を返し、`FD_READ` でもそろいません）。省くとイベントは出しません
- `set-after`: 今から `duration` 後に鳴らします（0 ならすぐ）。`interval` が 0 でなければ、その後 `interval` ごとに繰り返します。設定し直すと回数は 0 に戻ります
- `set-at`: 単調時計が `instant` になったら鳴らします（過ぎていればすぐ）
- `cancel`: 止めて、回数を 0 に戻します
- `read`: 前回から鳴った回数を取り出して 0 に戻します。待ちません。遅れて鳴った繰り返しのタイマーは、過ぎた回をまとめて数えます
- 時間の引数の 2^63 以上の値は、2^63 − 1 として扱います（実際には鳴りません）
- 眠るだけなら、タイマーは要りません。`poll_oneoff` の時計の購読（wasi-libc の `nanosleep` など）を使ってください。タイマーの fd と時計の購読は、同じ `poll_oneoff` で並べて待てます

### p1 向けの import

モジュール名: `kibi:kernel/timers@0.1.0`

<!-- check:imports kibi:kernel/timers@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `[static]timer.create` | `(events_is_some: i32, events: i32, tag: i64, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.set-after` | `(self: i32, duration: i64, interval: i64, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.set-at` | `(self: i32, instant: i64, interval: i64, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.cancel` | `(self: i32, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.read` | `(self: i32, ret_ptr: i32) -> ()` | — | — |
| `[resource-drop]timer` | `(self: i32) -> ()` | — | — |
<!-- /check -->

`events_is_some` が 0 なら、`events`・`tag` は読みません。

### メモリ上の配置

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `result<timer, error-code>` | 判別子 `u8 @0`、ok ならハンドル `i32 @4`、err なら `u8 @4` | 8 / 4 |
| `result<_, error-code>` | 判別子 `u8 @0`、err なら `u8 @1` | 2 / 1 |
| `result<u64, error-code>` | 判別子 `u8 @0`、ok なら `u64 @8`、err なら `u8 @8` | 16 / 8 |

### エラー

| 条件 | 結果 |
| --- | --- |
| `events_is_some` が 0・1 でない | `invalid` |
| `events` がイベントキューでない・開いていない | `bad-descriptor` |
| そのキューが種類 `timer` を受け取らない | `invalid` |
| fd の表が満杯 | `mfile` |
| `self` がタイマーでない（`set-after`・`set-at`・`cancel`・`read`） | `bad-descriptor` |
| `interval` が 0 でなく、最小の間隔（仮の値 50 ms）より短い | `invalid` |
| マシンの動いているタイマーの数が上限 | `nfile` |

## イベントの種類

カーネルが出すイベントは、`source` が `""`（TTY のものは `"console"`）です。デバイスも同じ種類名のイベントを出せるので、カーネルのものは `source` で見分けてください。

| 種類 | `source` | `values` | いつ |
| --- | --- | --- | --- |
| `timer` | `""` | `[integer(tag), integer(回数)]` | キューを渡して作ったタイマーが鳴ったとき（[timers](#timers)） |
| `tty-resize` | `"console"` | `[integer(列), integer(行), integer(世代)]` | 画面の大きさが実際に変わったとき（[kibi:io](./kibi-io.md#tty)） |
| `device-attached` | `""` | `[text(アドレス), text(種類)]` | デバイスをつないだとき（[kibi:device](./kibi-device.md)） |
| `device-detached` | `""` | `[text(アドレス), text(種類)]` | デバイスを外したとき |
| `disk-inserted` | `""` | `[text(識別子), text(マウントの位置)]` | ディスクを挿したとき（[kibi:fs](./kibi-fs.md)） |
| `disk-removed` | `""` | `[text(識別子), text(マウントの位置)]` | ディスクを抜いたとき |
| `restored` | `""` | `[integer(実時間の飛び ns), integer(ゲーム内時間の飛び tick)]` | 保存から戻ったとき（飛びが 0・負でも出ます） |

- 同じアドレスの `device-attached` と `device-detached` は、起きた順に届きます。届いた時点で、`kibi:device` の一覧はもう新しくなっています。起動の前の接続は届かないので、起動したら一覧を読んでください
- 保存から戻ったときは、`restored` の後に、戻った先でつなぎ直したすべてのデバイスの `device-attached` が届きます（保存の前につないでいた分と重なることがあり、消えた分の `device-detached` は出ません）。`restored` を受けたときと、`dropped` が 0 でないときは、一覧を読み直してください
- `restored` は「保存から戻った」という意味だけです。画面の中身が残っているかは約束しません
- `restored` の2つ目の値（ゲーム内時間の飛び）は、今は常に 0 です。接続層がまだゲーム内時間をマシンに渡していないためです（[#439](https://github.com/neverclear86/KiBi-Computers/issues/439)。`world-ticks` と同じ）
- **デバイスは、上の表にない独自の種類のイベントも出せます**（`source` はそのデバイスのアドレス）。種類と値の意味は、そのデバイスの説明を見てください。この表は閉じた一覧ではありません
