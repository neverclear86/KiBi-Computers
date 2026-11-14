---
title: kibi:computer
description: 残りの RAM、起動からの経過とゲーム内時間、電源（停止・再起動）。
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

- RAM は、プロセスが初めて書き込んだ 4 KiB の区画の数で数えます。線形メモリを大きく確保しただけでは減りません
- `remaining` は、プロセスの上限とマシンの RAM の残りのうち、小さい方（バイト）です
- `commit` は、`[ptr, ptr + len)` の区画を今のうちに数えます。数えた後の残りが `keep` 未満になるなら、何も数えずに `false` を返します。新しく数える区画がなければ `true`、範囲が線形メモリの外なら `false` です。大きなバッファを書く前に、足りるか確かめるのに使えます

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
