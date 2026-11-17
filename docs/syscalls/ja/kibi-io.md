---
title: kibi:io
description: fd の複製（dup・dup2）、パイプ、TTY のモード・大きさ・前のプロセス。
---

`kibi:io@0.1.0` は、WASI p1 にない fd の操作です。`fd` と `pipe` は p1 だけのもので、p2 では `pipe-streams`、p3 では別の interface（仮置き）を使います。`tty` は p1・p2・p3 で同じ形にする予定です。

fd の引数は、そのプロセスのカーネルの fd 番号（p1 の fd と同じ番号）です。

## fd

```wit
// kibi-io/fd.wit
package kibi:io@0.1.0;
interface fd {
    use kibi:kernel/types@0.1.0.{error-code};
    dup: func(fd: u32) -> result<u32, error-code>;
    dup-to: func(fd: u32, to: u32) -> result<_, error-code>;
}
```

- `dup` は、いちばん小さい空き番号に複製します（POSIX の `dup`）。`dup-to` は `to` に複製し、`to` が開いていれば先に閉じます（POSIX の `dup2`）。`fd` と `to` が同じなら、開いているか確かめるだけです
- 複製した fd は同じものを指し、ファイルの位置やストリームの中身を共有します。権利と fd のフラグ（`NONBLOCK` など）は写します
- **POSIX との違い**: `NONBLOCK` は fd ごとの属性なので、複製の後はそれぞれ別に変えられます（共有しません）
- `fd` が開いていない、`to` が範囲外なら `bad-descriptor` で、このとき `to` は閉じません。表が満杯なら `mfile`
- `to` に何が開いていても（イベントキュー、子のハンドルなども）、ただの fd として閉じます

## pipe

```wit
// kibi-io/pipe.wit
package kibi:io@0.1.0;
interface pipe {
    use kibi:kernel/types@0.1.0.{error-code};
    record pipe-ends {
        read: u32,
        write: u32,
    }
    create: func() -> result<pipe-ends, error-code>;
}
```

- 読む端は `fd_read`・`poll_oneoff` の `FD_READ`、書く端は `fd_write`・`FD_WRITE` で使います。向きの違う使い方は `bad-descriptor` です
- 書く端をすべて閉じると、読む側は残りを読んだ後に EOF（0 バイト）になります。読む端をすべて閉じると、書く側は `pipe` です
- バッファの大きさは既定（仮の値 4096 バイト）です。両端は小さい空き番号から順に入り、空き番号が2つなければ何も作らずに `mfile` です
- 両端の fd のフラグは 0（待つ）です。待たない読み書きは `fd_fdstat_set_flags` の `NONBLOCK` で
- `fd_fdstat_get` の種類は、両端とも `UNKNOWN` です

## tty

```wit
// kibi-io/tty.wit
package kibi:io@0.1.0;
interface tty {
    use kibi:kernel/types@0.1.0.{error-code};
    flags mode {
        canonical,
        echo,
        signals,
        cr-to-nl,
        nl-to-crnl,
    }
    record size {
        columns: u32,
        rows: u32,
        generation: u64,
    }
    is-tty: func(fd: u32) -> bool;
    get-mode: func(fd: u32) -> result<mode, error-code>;
    set-mode: func(fd: u32, mode: mode) -> result<_, error-code>;
    get-size: func(fd: u32) -> result<size, error-code>;
    get-foreground: func(fd: u32) -> result<u32, error-code>;
    set-foreground: func(fd: u32, pid: u32) -> result<_, error-code>;
}
```

- `fd` が開いていなければ `bad-descriptor`（`is-tty` は `false`）、TTY でなければ `no-tty` です。fd の読み書きの権利は問いません（termios と同じ）
- `mode` のビットは個別に効きます。全部オン（0x1f）が既定の cooked、全部オフ（0）が raw（`cfmakeraw` 相当）です。`set-mode` は定義外の上位ビットを読みません
- `canonical` から外すと、編集中の行はそのまま読める入力になります
- `get-size` は大きさと世代を同じ瞬間の組で返します。画面の大きさが変わると、`tty-resize` のイベントが届きます（[kibi:kernel](./kibi-kernel.md#イベントの種類)）。取りこぼしは世代で分かります
- `set-foreground` の `pid` が 2^31 以上なら、fd より先に確かめて `invalid` です。生きているかは確かめません。どのプロセスからでも変えられます

## p1 向けの import

モジュール名: `kibi:io/fd@0.1.0`

<!-- check:imports kibi:io/fd@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `dup` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `dup-to` | `(fd: i32, to: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

モジュール名: `kibi:io/pipe@0.1.0`

<!-- check:imports kibi:io/pipe@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `create` | `(ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

モジュール名: `kibi:io/tty@0.1.0`

<!-- check:imports kibi:io/tty@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `is-tty` | `(fd: i32) -> i32` | — | — |
| `get-mode` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `set-mode` | `(fd: i32, mode: i32, ret_ptr: i32) -> ()` | — | — |
| `get-size` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `get-foreground` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `set-foreground` | `(fd: i32, pid: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

## メモリ上の配置

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `result<u32, error-code>` | 判別子 `u8 @0`、ok なら `u32 @4`、err なら `u8 @4` | 8 / 4 |
| `result<_, error-code>` | 判別子 `u8 @0`、err なら `u8 @1` | 2 / 1 |
| `result<pipe-ends, error-code>` | 判別子 `u8 @0`、ok なら `read: u32 @4`・`write: u32 @8`、err なら `u8 @4` | 12 / 4 |
| `result<mode, error-code>` | 判別子 `u8 @0`、ok ならビットの `u8 @1`、err なら `u8 @1` | 2 / 1 |
| `result<size, error-code>` | 判別子 `u8 @0`、ok なら `columns: u32 @8`・`rows: u32 @12`・`generation: u64 @16`、err なら `u8 @8` | 24 / 8 |

`mode` のビットは `canonical` = 1、`echo` = 2、`signals` = 4、`cr-to-nl` = 8、`nl-to-crnl` = 16 です。
