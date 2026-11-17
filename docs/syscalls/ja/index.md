---
title: システムコール仕様
description: KiBi Computers のプログラムが使える WASI と独自拡張（kibi:*）の、対応範囲と呼び出しの形。
---

KiBi Computers のコンピューターでは、プログラムは WebAssembly（WASM）のモジュールとして動きます。プログラムが OS の機能を使う口（システムコール）は、次の2つです。

- **WASI**: 標準の口。ファイル、標準入出力、時計、乱数、待ち合わせ（`poll_oneoff`）など
- **独自拡張 `kibi:*`**: WASI にない機能。イベント、タイマー、プロセス、パイプ、TTY、デバイス、電源、マウントなど。WIT（WebAssembly Interface Types）のパッケージとして定義し、WASI p1 のプログラムには同じ関数を import として出しています

この仕様書は、プログラムを書く人（C・Zig・Rust などで WASM を作る人、言語の実行環境や SDK を作る人）に向けたものです。

## 今の対応状況

| 口 | 状況 |
| --- | --- |
| WASI p1（`wasi_snapshot_preview1`） | **対応**。コマンド形式（`_start` を export するモジュール）だけを動かします。関数ごとの対応と標準との差は [WASI p1](./wasi-p1.md) |
| `kibi:*` の p1 向けの import | **対応**。共通の規則は [kibi:* の共通の規則](./kibi-abi.md)、パッケージごとのページは下の表 |
| WASI p2・Component Model | **一部対応**。`wasi:cli/run@0.2.x` を export するコマンドのコンポーネントを exec で動かせます。`kibi:*` の p2 向けの interface（`imports-p2` の world）も import できます。状態は保存されません（[p2・p3](./p2-p3.md)） |
| WASI p3（非同期） | **未実装**（[#266](https://github.com/neverclear86/KiBi-Computers/issues/266)・[#268](https://github.com/neverclear86/KiBi-Computers/issues/268)） |
| `kibi:*` の p2 向けの interface（`events-poll`・`timers-poll`・`process-poll`・`pipe-streams`・`bus-poll` と、共通のもの） | **対応**（#685）。コンポーネントから import できます（[p2・p3 の予定](./p2-p3.md)） |
| `kibi:*` の p3 向けの interface（`*-async`） | **仮置き**。WIT はありますが、形は p3 の層を作るときに変わります（[p2・p3 の予定](./p2-p3.md)） |
| ソケット（`sock_*`・`wasi:sockets`） | **未実装**。p1 の `sock_*` は `NOSYS` を返します（[#76](https://github.com/neverclear86/KiBi-Computers/issues/76)） |

p2・p3 と Component Model は、カーネルの同じ土台（fd、イベント待ち、ストリーム）の上の薄い層です。p2 のコマンドは動き、p3 などの残りは後で足す予定です。p2・p3 でも、`kibi:*` の同じ WIT を使います。

## ページ

| ページ | 中身 |
| --- | --- |
| [WASI p1](./wasi-p1.md) | p1 の関数ごとの対応、標準との差、読み込みで断るもの、待ち方と保存 |
| [kibi:* の共通の規則](./kibi-abi.md) | p1 向けの import の導き方、ハンドル、エラー（`error-code` の表）、`cabi_realloc`、止まりうる import |
| [kibi:kernel](./kibi-kernel.md) | 共通の型、イベントキュー、タイマー、イベントの種類 |
| [kibi:process](./kibi-process.md) | プロセスの生成・待機・シグナル、作業ディレクトリ、プロセスの一覧 |
| [kibi:io](./kibi-io.md) | fd の複製、パイプ、TTY |
| [kibi:device](./kibi-device.md) | デバイスの一覧と呼び出し |
| [kibi:computer](./kibi-computer.md) | 残りの RAM、起動からの経過とゲーム内時間、電源 |
| [kibi:fs](./kibi-fs.md) | ディスクのマウント、マウントとディスクの一覧 |
| [p2・p3 の予定](./p2-p3.md) | p2・p3 と Component Model の今の状況と予定、`kibi:*` の p2 の interface と仮置きの p3 の interface |

## 版と互換性

- `kibi:*` のパッケージは、すべて `@0.1.0` です。読み込みでは版の完全一致だけを受け付けます（`@0.2.0` や版なしの import は `NOEXEC`）
- **正式版（v0.1.0）までは、互換を保ちません。** 版を上げないまま、WIT・import の形・エラーの値を変えることがあります。互換を保つのは正式版からです
- この仕様書に「仮の値」と書いた数（上限、既定の容量、燃料など）は、正式版の前に見直します（[#251](https://github.com/neverclear86/KiBi-Computers/issues/251)）。仮の値に頼らないプログラムにしてください

## 用語

| 用語 | 意味 |
| --- | --- |
| デバイス | コンピューターにつながる周辺機器（画面、ディスクドライブ、レッドストーン I/O、他 MOD のブロックなど） |
| コンポーネント | WASM の Component Model のコンポーネント。周辺機器のことではありません |
| fd | カーネルのファイル記述子の番号。p1 の fd と同じ番号で、`kibi:*` の resource のハンドルにも使います |
| マシン | 1台のコンピューター。プロセス、ファイル、デバイスはマシンごとに分かれています |
| 待機点 | プロセスが止まりうる呼び出しで待っている状態。この状態のプロセスはスレッドを持たず、ワールドに保存できます（[待ち方と保存](./wasi-p1.md#待ち方と保存)） |
| 燃料 | プロセスが使える計算の量。WASM の命令とシステムコールの呼び出しで減り、なくなると次の割り当てまで待ちます。量は仮の値です |
