---
title: kibi:* の共通の規則
description: 独自拡張 kibi:* の WIT から WASI p1 向けの import を導く規則、ハンドル、エラー、cabi_realloc、止まりうる import。
---

独自拡張は WIT の `kibi:<領域>` パッケージで定義しています。WASI p1 のプログラムには、その関数を **Component Model の Canonical ABI で平らにした core の関数**として、そのまま import に出しています。wit-bindgen がゲスト向けに出す import と同じ形なので、手で書いた宣言でも、生成した束縛でも呼べます。

| パッケージ | interface（p1 で使えるもの） | ページ |
| --- | --- | --- |
| `kibi:kernel@0.1.0` | `types`・`events`・`timers` | [kibi:kernel](./kibi-kernel.md) |
| `kibi:process@0.1.0` | `process` | [kibi:process](./kibi-process.md) |
| `kibi:io@0.1.0` | `fd`・`pipe`・`tty` | [kibi:io](./kibi-io.md) |
| `kibi:device@0.1.0` | `types`・`bus` | [kibi:device](./kibi-device.md) |
| `kibi:computer@0.1.0` | `memory`・`clock`・`power`・`access` | [kibi:computer](./kibi-computer.md) |
| `kibi:fs@0.1.0` | `mounts` | [kibi:fs](./kibi-fs.md) |

名前が `-poll`・`-async`・`-streams` で終わる interface は p2・p3 向けで、p1 では import できません。`-poll`・`-streams` は p2 で使えます（#685）。`-async` は p3 向けの仮置きです（[p2・p3 の予定](./p2-p3.md)）。現在時刻と単調時計は WASI の `clock_time_get` を使い、`kibi:*` には入れていません。

WIT の元のファイル（説明のコメントつき）は、リポジトリの [`core/src/main/wit/`](https://github.com/neverclear86/KiBi-Computers/tree/main/core/src/main/wit) にあります。各ページの WIT は、そこからコメントを除いたものです。

## import の導き方

- **モジュール名**: `<名前空間>:<パッケージ>/<interface>@<版>`。例: `kibi:kernel/events@0.1.0`
- **関数名**: wit-bindgen と同じです。普通の関数はその名前、resource の関数は `[constructor]<resource>`・`[method]<resource>.<関数>`・`[static]<resource>.<関数>`、ハンドルを閉じるのは `[resource-drop]<resource>`
- **引数**: Canonical ABI の平らにした形です。`string`・`list<T>` は `(ptr, len)` の2つ、`option<T>` は判別子と値、`bool` は i32（0 以外が真）、`enum` は判別子の i32、`flags` はビットの i32、`u64`・`s64` は i64、それ以外の整数は i32
- **結果**: 1つの値に収まる結果（`u32`・`u64`・`bool` など）は戻り値で返します。収まらない結果（`result`・`option`・`record`・`string`・`list` など）は、**最後の引数に書き先のポインタ（retptr）を渡し**、ホストがそこに書きます。戻り値はありません
- **メモリ上の配置**: Canonical ABI のとおりです（リトルエンディアン、各型の align に揃え、record は欄の順、variant は判別子の後に最大の align に揃えた payload）。各ページに、主な型の大きさとオフセットを載せています
- 各ページの import の表の「core の型」は、引数の名前と型、戻り値の型です。`()` は戻り値なし

## ハンドル

- resource のハンドルは、**カーネルの fd 番号**です。p1 の fd と同じ番号空間なので、`fd_close` で閉じたり、`poll_oneoff` の `FD_READ` で待ったり、`kibi:process` の `spawn` で子に渡したりできます
- `[resource-drop]<resource>` は `fd_close` と同じく閉じます。そのハンドルが開いていない・違う種類なら、Component Model と同じくトラップです
- 結果に `error-code` を持たない関数（例: `[method]process.pid`）に不正なハンドルを渡すと、トラップです。`error-code` を返す関数は、`bad-descriptor` を返します
- fd の表が満杯なら `mfile`、マシン全体の上限（キューの数、動いているタイマーの数、プロセスの数など）に当たれば `nfile` です

## エラー

`kibi:kernel/types` の `enum error-code` は、WASI p1 の errno から `success` を除いた 76 個を、同じ順に並べたものです。**p1 の errno = 判別子 + 1** です。C では、判別子に 1 を足せば `errno` の値になります。

<!-- check:error-codes -->
| 判別子 | `error-code` | p1 の errno | p1 の名前 |
| --- | --- | --- | --- |
| 0 | `too-big` | 1 | `2BIG` |
| 1 | `access` | 2 | `ACCES` |
| 2 | `addr-in-use` | 3 | `ADDRINUSE` |
| 3 | `addr-not-avail` | 4 | `ADDRNOTAVAIL` |
| 4 | `af-not-supported` | 5 | `AFNOSUPPORT` |
| 5 | `again` | 6 | `AGAIN` |
| 6 | `already` | 7 | `ALREADY` |
| 7 | `bad-descriptor` | 8 | `BADF` |
| 8 | `bad-message` | 9 | `BADMSG` |
| 9 | `busy` | 10 | `BUSY` |
| 10 | `canceled` | 11 | `CANCELED` |
| 11 | `child` | 12 | `CHILD` |
| 12 | `conn-aborted` | 13 | `CONNABORTED` |
| 13 | `conn-refused` | 14 | `CONNREFUSED` |
| 14 | `conn-reset` | 15 | `CONNRESET` |
| 15 | `deadlock` | 16 | `DEADLK` |
| 16 | `dest-addr-required` | 17 | `DESTADDRREQ` |
| 17 | `domain` | 18 | `DOM` |
| 18 | `quota` | 19 | `DQUOT` |
| 19 | `exist` | 20 | `EXIST` |
| 20 | `fault` | 21 | `FAULT` |
| 21 | `file-too-large` | 22 | `FBIG` |
| 22 | `host-unreachable` | 23 | `HOSTUNREACH` |
| 23 | `identifier-removed` | 24 | `IDRM` |
| 24 | `illegal-byte-sequence` | 25 | `ILSEQ` |
| 25 | `in-progress` | 26 | `INPROGRESS` |
| 26 | `interrupted` | 27 | `INTR` |
| 27 | `invalid` | 28 | `INVAL` |
| 28 | `io` | 29 | `IO` |
| 29 | `is-connected` | 30 | `ISCONN` |
| 30 | `is-directory` | 31 | `ISDIR` |
| 31 | `loop` | 32 | `LOOP` |
| 32 | `mfile` | 33 | `MFILE` |
| 33 | `too-many-links` | 34 | `MLINK` |
| 34 | `message-size` | 35 | `MSGSIZE` |
| 35 | `multihop` | 36 | `MULTIHOP` |
| 36 | `name-too-long` | 37 | `NAMETOOLONG` |
| 37 | `net-down` | 38 | `NETDOWN` |
| 38 | `net-reset` | 39 | `NETRESET` |
| 39 | `net-unreachable` | 40 | `NETUNREACH` |
| 40 | `nfile` | 41 | `NFILE` |
| 41 | `no-buffers` | 42 | `NOBUFS` |
| 42 | `no-device` | 43 | `NODEV` |
| 43 | `no-entry` | 44 | `NOENT` |
| 44 | `no-exec` | 45 | `NOEXEC` |
| 45 | `no-lock` | 46 | `NOLCK` |
| 46 | `no-link` | 47 | `NOLINK` |
| 47 | `no-memory` | 48 | `NOMEM` |
| 48 | `no-message` | 49 | `NOMSG` |
| 49 | `no-protocol-option` | 50 | `NOPROTOOPT` |
| 50 | `no-space` | 51 | `NOSPC` |
| 51 | `no-sys` | 52 | `NOSYS` |
| 52 | `not-connected` | 53 | `NOTCONN` |
| 53 | `not-directory` | 54 | `NOTDIR` |
| 54 | `not-empty` | 55 | `NOTEMPTY` |
| 55 | `not-recoverable` | 56 | `NOTRECOVERABLE` |
| 56 | `not-socket` | 57 | `NOTSOCK` |
| 57 | `not-supported` | 58 | `NOTSUP` |
| 58 | `no-tty` | 59 | `NOTTY` |
| 59 | `no-such-device` | 60 | `NXIO` |
| 60 | `overflow` | 61 | `OVERFLOW` |
| 61 | `owner-dead` | 62 | `OWNERDEAD` |
| 62 | `not-permitted` | 63 | `PERM` |
| 63 | `pipe` | 64 | `PIPE` |
| 64 | `protocol` | 65 | `PROTO` |
| 65 | `protocol-not-supported` | 66 | `PROTONOSUPPORT` |
| 66 | `protocol-type` | 67 | `PROTOTYPE` |
| 67 | `range` | 68 | `RANGE` |
| 68 | `read-only` | 69 | `ROFS` |
| 69 | `invalid-seek` | 70 | `SPIPE` |
| 70 | `no-such-process` | 71 | `SRCH` |
| 71 | `stale` | 72 | `STALE` |
| 72 | `timed-out` | 73 | `TIMEDOUT` |
| 73 | `text-file-busy` | 74 | `TXTBSY` |
| 74 | `cross-device` | 75 | `XDEV` |
| 75 | `not-capable` | 76 | `NOTCAPABLE` |
<!-- /check -->

エラーとトラップの分け方:

| 誤り | 結果 |
| --- | --- |
| 引数の値の誤り（上限を超える長さ・個数、不正な UTF-8、不正な判別子） | `invalid`（関数によって `too-big`・`name-too-long`） |
| 引数のポインタが線形メモリの外 | `fault` |
| 引数の `list` の ptr が要素の align に揃っていない（長さ 0 でも） | トラップ |
| 書き先（retptr）が線形メモリの外・align に揃っていない | トラップ |
| `cabi_realloc` が返した場所が線形メモリの外・align に揃っていない | トラップ |
| 不正なハンドルの `[resource-drop]`、`error-code` を返さない関数の不正なハンドル | トラップ |

- 複数の誤りがあれば、確かめる順で最初に当たったものを返します。順は関数ごとに決まっていて、各ページの検査の表（または説明）にあります。ハンドル（先頭の `self`・`fd`）は、引数の順に確かめるとは限りません。ほかの引数の値を先に確かめて、ハンドルを後にするものがあります（`event-queue.take` の `max`、`device.invoke`・`start` の `method`・`args`、`tty.set-foreground` の `pid`、`process.kill` の `sig`）
- **Component Model の層（p2）との違い**: Component Model の層では、ホストが確かめる前に全引数を読み込む（lift する）ので、p1 で `error-code` が返る組み合わせのうち、後ろの引数の align・判別子・UTF-8 の誤りを含むものはトラップになります（たとえば長すぎる `path` と、揃っていない `args` の ptr）。また範囲外のポインタ、UTF-8 の誤り、判別子の誤りは、Component Model の層ではそれだけでトラップです。p1 はこの順のままにします（[#616](https://github.com/neverclear86/KiBi-Computers/issues/616)）。どの誤りが先に出るかに頼らないでください

## cabi_realloc

可変長の結果（`string`・`list` を含む結果）を返す関数は、ホストがゲストの関数 `cabi_realloc` を呼んで、結果を置く場所を取ります。

```c
// 型: (old_ptr: i32, old_size: i32, align: i32, new_size: i32) -> i32
void *cabi_realloc(void *old_ptr, size_t old_size, size_t align, size_t new_size);
```

- `cabi_realloc` が要る関数を1つでも import するモジュールは、この名前と型で `cabi_realloc` を export してください。なければ読み込みで `NOEXEC` です。どの関数が要るかは、各ページの表の「cabi_realloc」の列にあります
- ホストは `old_ptr = 0`・`old_size = 0` で呼びます。Canonical ABI のとおり、大きさ 0 の list でも呼びます。呼ぶ順は Component Model と同じです（外側の list の後に、要素の string・list を前から）
- 取った場所はゲストのものです（`malloc` で作ったなら、`free` してかまいません）
- 返した場所が線形メモリの外か、align に揃っていなければトラップです
- `cabi_realloc` の中で使った燃料は、プログラムの分として数えます
- **`cabi_realloc` の中では、止まりうる import（下の表）を呼べません。** 呼ぶとトラップです。ほかの import（`clock_time_get` や、待たない `kibi:*` の関数）は呼べます

## 止まりうる import

次の表の関数は、結果がそろうまでプロセスを待たせることがあります（[待ち方と保存](./wasi-p1.md#待ち方と保存)）。

<!-- check:wait-imports -->
| モジュール | 関数 | 待つもの |
| --- | --- | --- |
| `wasi_snapshot_preview1` | `fd_read` | 読める入力 |
| `wasi_snapshot_preview1` | `fd_write` | 書ける空き |
| `wasi_snapshot_preview1` | `poll_oneoff` | 購読のどれか |
| `kibi:process/process@0.1.0` | `[static]process.spawn` | 実行ファイルの読み込み（コンパイル） |
| `kibi:device/bus@0.1.0` | `[method]device.invoke` | デバイスのメソッドの結果 |
<!-- /check -->

ほかの `kibi:*` の関数は待ちません。`kibi:computer/power` の `shutdown`・`reboot` は、受け付けると呼んだプロセスもその場で止まるので、戻りません。

## 版

- すべてのパッケージは `@0.1.0` です。読み込みでは版の完全一致だけを受け付け、違う版や版なしの名前は `NOEXEC` です
- 正式版（v0.1.0）までは、版を上げずに互換を壊すことがあります

## WASIX との関係

WASIX の名前と形には合わせていません。WASI p1・p2・p3 と `kibi:*` の WIT が正です。
