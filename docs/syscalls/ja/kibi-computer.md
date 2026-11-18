---
title: kibi:computer
description: 残りの RAM、起動からの経過とゲーム内時間、電源（停止・再起動）、持ち主と権限（見る・変更の依頼）。
---

`kibi:computer@0.1.0` は、コンピューター（マシン）全体に関わる口です。どの関数も待ちません。

## memory

```wit
// kibi-computer/memory.wit
package kibi:computer@0.1.0;
interface memory {
    remaining: func() -> u64;
    commit: func(ptr: u32, len: u32, keep: u64) -> bool;
}
```

- RAM は、プロセスが初めて書き込んだ 4 KiB の区画の数で数えます。線形メモリを大きく確保しただけでは、区画は数えません。ただし、線形メモリの区画の表として、宣言した（または `memory.grow` で増やした）ページ数に比例して、1 ページあたり 128 バイト（仮の値）を数えます。大きく宣言しただけでも少しずつ減るので、RAM の小さいマシンでは、宣言が大きすぎるモジュールはメモリ不足でインスタンス化できません。書いた区画の数は、表の分を含みません
- `remaining` は、プロセスの上限とマシンの RAM の残りのうち、小さい方（バイト）です
- `commit` は、`[ptr, ptr + len)` の区画を今のうちに数えます。数えた後の残りが `keep` 未満になるなら、何も数えずに `false` を返します。新しく数える区画がなければ `true`、範囲が線形メモリの外なら `false` です。大きなバッファを書く前に、足りるか確かめるのに使えます
- p1 では `ptr` は呼んだモジュールの線形メモリ、p2 では `commit` を呼んだ core モジュールの線形メモリの番地です（[p2・p3](./p2-p3.md)）

## clock

```wit
// kibi-computer/clock.wit
package kibi:computer@0.1.0;
interface clock {
    uptime-ticks: func() -> u64;
    world-ticks: func() -> u64;
}
```

- `uptime-ticks` は起動からの経過（tick。1 tick は 50 ms の想定）です。起動したときが 0 で、マシンが動いていた tick だけを数え、保存・復元をまたいで続きます
- `world-ticks` はゲーム内時間（ワールドの tick）です。ワールドがなければ 0 で、保存・復元のあいだは飛びます
- **今は未実装で、ゲームの中では常に 0 を返します。** 接続層がまだゲーム内時間をマシンに渡していないためです（[#439](https://github.com/neverclear86/KiBi-Computers/issues/439)）
- 現在時刻と単調時計（ナノ秒）は、WASI の `clock_time_get` を使ってください（[WASI p1](./wasi-p1.md#時計)）

## power

```wit
// kibi-computer/power.wit
package kibi:computer@0.1.0;
interface power {
    use kibi:kernel/types@0.1.0.{error-code};
    shutdown: func();
    reboot: func() -> result<_, error-code>;
}
```

- どのプロセスからでも呼べます（マシンの中では権限を分けていません）
- `shutdown` はマシンを止めます。必ず受け付け、呼んだプロセスもその場で止まるので戻りません
- `reboot` は、すべてのプロセスを止めてから、最後に起動したときと同じ設定で起動し直します。受け付けると戻りません。断ったときだけ戻り、`not-supported` を返します（起動も復元も通らずに作ったマシン、サーバーを閉じる途中）
- 短い間に再起動を繰り返すと、起動し直すまでの間が延びます（戻り値は変わりません）
- `reboot` の書き先は、断ったときだけ書きます。そのため、書き先が線形メモリの外でも、受け付ければ再起動します（トラップにはなりません）。断ったときに書き先が外なら、トラップです

## access

```wit
// kibi-computer/access.wit
package kibi:computer@0.1.0;
interface access {
    use kibi:kernel/types@0.1.0.{error-code};
    enum access-state {
        unowned,
        owned,
        locked,
    }
    record player {
        name: string,
        id: string,
    }
    record access-info {
        state: access-state,
        owner: option<player>,
        members: list<player>,
        invites: list<string>,
    }
    get-access: func() -> result<access-info, error-code>;
    request-add: func(name: string) -> result<_, error-code>;
    request-remove: func(name-or-id: string) -> result<_, error-code>;
    request-transfer: func(name: string) -> result<_, error-code>;
}
```

- コンピューター（ケース）の持ち主・メンバー・保留（[ADR 0131](../adr/0131-player-access.md)）を見て、変えるよう**依頼**します（#110、[ADR 0110](../adr/0110-player-access-commands.md)）。プログラムが直接変えることはできません
- `get-access` は今の状態を返します。`unowned`（持ち主なし）・`locked`（保存した記録が読めなかったケース）のとき、`owner` は none、`members`・`invites` は空です。`invites` は名前で足した保留（小文字）で、期限の切れたものは含みません。承認待ちの依頼は含みません
- `request-add`・`request-remove`・`request-transfer` は依頼を受け付けると ok を返します。ok は「反映した」ではありません。依頼はゲーム内で持ち主か OP（持ち主のいないケースの `transfer` は新しい持ち主になる本人、`locked` のケースは OP だけ）が `/kibicomputers access approve <ID>` で承認したときに反映し、結果は `get-access` で見ます。持ち主がオンラインなら、チャットに承認・拒否のボタンが出ます
- 承認待ちの依頼は、コンピューターごとに上限（既定 4、コンフィグ `access.maxRequestsPerComputer`）があり、期限（既定 300 秒、`access.requestTtlSeconds`）で消えます。同じ依頼（種類と引数が同じ。名前は大文字小文字を区別しない）は1つにまとめ、ok を返します。承認待ちの依頼は保存しません（サーバーを止めると消えます）
- `remove` の引数は、名前（保留とその名前で今オンラインのメンバー）か、UUID の正規の形（ハイフン付き 36 文字。大文字も可）です
- `transfer` の相手は、依頼がゲームに届いたときにオンラインのプレイヤーから名前で引き、その UUID に固定します。そのときオンラインでなければ、依頼は捨てられます（ok は「受け付けた」だけで、捨てられても分かりません）。承認のときは固定した UUID に渡すので、相手がその後ログアウトしていても反映します

| 状況 | 返すもの |
| --- | --- |
| 名前の形が違う（`[A-Za-z0-9_]{1,16}`）、`remove` で名前でも UUID でもない、64 バイトを超える、UTF-8 でない | `invalid` |
| このコンピューターに持ち主と権限がない（ゲームの外で作ったマシンなど） | `not-supported` |
| 承認待ちの依頼が上限 | `again` |

- `request-*` の文字列の範囲が線形メモリの外なら、`fault` を返します（トラップにはなりません）。`request-*` の書き先（retptr）は、依頼を受け付けた後に確かめます（書き先が外なら、依頼は届いたうえでトラップになります）。`get-access` は依頼を出さないので、書き先を最初に確かめます
- `get-access` は `cabi_realloc` の中からも呼べます。`cabi_realloc` は、Canonical ABI の項目の順（持ち主の名前 → 持ち主の ID → `members` の配列 → 各メンバーの名前・ID → `invites` の配列 → 各保留）に呼びます

## p1 向けの import

モジュール名: `kibi:computer/memory@0.1.0`

<!-- check:imports kibi:computer/memory@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `remaining` | `() -> i64` | — | — |
| `commit` | `(ptr: i32, len: i32, keep: i64) -> i32` | — | — |
<!-- /check -->

モジュール名: `kibi:computer/clock@0.1.0`

<!-- check:imports kibi:computer/clock@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `uptime-ticks` | `() -> i64` | — | — |
| `world-ticks` | `() -> i64` | — | — |
<!-- /check -->

モジュール名: `kibi:computer/power@0.1.0`

<!-- check:imports kibi:computer/power@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `shutdown` | `() -> ()` | — | — |
| `reboot` | `(ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

`reboot` の書き先は `result<_, error-code>`（判別子 `u8 @0`、err なら `u8 @1`。2 バイト、align 1）です。

モジュール名: `kibi:computer/access@0.1.0`

<!-- check:imports kibi:computer/access@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `get-access` | `(ret_ptr: i32) -> ()` | 要る | — |
| `request-add` | `(name_ptr: i32, name_len: i32, ret_ptr: i32) -> ()` | — | — |
| `request-remove` | `(arg_ptr: i32, arg_len: i32, ret_ptr: i32) -> ()` | — | — |
| `request-transfer` | `(name_ptr: i32, name_len: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

`request-*` の書き先は `reboot` と同じ `result<_, error-code>` です。`get-access` の書き先は次の形です（文字列は `ptr`・`len`、`list<T>` は `ptr`・`len`。詰め物と使わない値の場所は 0）。

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `result<access-info, error-code>` | 判別子 `u8 @0`。ok なら `access-info @4`、err なら `u8 @4` | 44 / 4 |
| `access-info` | `state: u8 @0`、`owner` の判別子 `u8 @4`・`player @8`、`members @24`・`@28`、`invites @32`・`@36` | 40 / 4 |
| `player` | `name @0`・`@4`、`id @8`・`@12` | 16 / 4 |

`access-state` の判別子は `unowned` = 0、`owned` = 1、`locked` = 2 です。
