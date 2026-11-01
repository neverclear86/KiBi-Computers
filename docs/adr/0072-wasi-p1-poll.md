# 0072: WASI p1 の poll_oneoff とイベント待ちの統合

- 状態: 採用
- Issue: #72（task）。前提: ADR 0043（カーネルの構成）、0063（イベントキュー）、0064（タイマーと時計）、0070（p1 の `fd_*`）、0261（p1 の層を直接実装する）
- 後続: #73（p1 のプログラムがイベントキュー・タイマーの fd を開く import）、#329（`WAIT_POINT` の p1 を `poll_oneoff` で待たせる）、#264・#266（p2・p3 の待ち）、#81・#82（保存）、#251（仮の値の確定）、#332（cwd と `PWD`）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- `poll_oneoff` は `core.wasi.p1.WasiP1Poll`（`internal`）。関数の形と `subscription`・`event` の配置は WASI p1 の witx のまま（独自の ABI は足さない）。公開 API・保存のバイト形式・`kibi:*` の import は足さない。`platform-*` は変えない
- fd の購読（`FD_READ`・`FD_WRITE`）は `(objectId, READABLE / WRITABLE)` の `WaitKey` で待つ。イベントキュー（#63）とタイマー（#64）の fd も `FD_READ` で待てる。これが「イベント待ちとの統合」
- fd の判定はカーネルの `FdIo.pollLocked`（`FdReadiness` を返す）に置き、p2 の `pollable`・p3 の waitable-set でも使えるようにする
- 時計の購読は、1回の呼び出しにつき一時的なタイマー1つ（`ProcessContext.openWaitTimer`・`resetWaitTimer`・`closeWaitTimer`）で、いちばん早い期限に合わせて待つ。そろったかは単調時計で判定する。`ctx.sleep` もこの口で書き直した
- `BLOCKING` のプロセスは `ctx.block` で待ち、起きたら判定し直す。`WAIT_POINT` のプロセスは、そろっていなければ `AGAIN`（`fd_read` と同じ）

## 構造体（wasm32、リトルエンディアン）

```
subscription（48 バイト、align 8）
  @0  userdata  u64
  @8  tag       u8   （0 = clock、1 = fd_read、2 = fd_write）
  clock のとき:    @16 id u32、@24 timeout u64、@32 precision u64、@40 flags u16
  fd_read/write:  @16 fd u32

event（32 バイト、align 8）
  @0  userdata  u64
  @8  error     u16（errno）
  @10 type      u8（eventtype）
  @16 nbytes    u64（fd_readwrite。時計では 0）
  @24 flags     u16（eventrwflags。HANGUP = 1）
```

## 振る舞い

1. `nsubscriptions` が 0 か `MAX_SUBSCRIPTIONS`（仮 1024）を超えれば `INVAL`。`in`・`out`・`nevents` が範囲外なら、何も書かずに `FAULT`
2. 購読を全部読んでから書く（`in` と `out` が重なっていても壊れない）。知らないタグがあれば呼び出し全体を `INVAL`（wasmtime と同じ）
3. 時計の購読は、入った時点で単調時計の期限にする（下の表）
4. カーネルロックを1回取って全部を判定し、そろった購読（エラーを含む）ごとに `event` を購読の順に書く
5. そろっていなければ、`BLOCKING` は fd の key と一時的なタイマーの key で `ctx.block` し、4 からやり直す。`WAIT_POINT` は `AGAIN`
6. 一時的なタイマーは待つときにだけ作り、返るとき（そろった、`ProcessKilledException`）に必ず閉じる。マシンの動いているタイマーが上限（`ResourceLimitException`）なら、呼び出し全体を `AGAIN`

### fd の購読（`FdIo.pollLocked`）

| fd が指すもの | `FD_READ` | `FD_WRITE` |
| --- | --- | --- |
| 開いていない・fd にその権利（`HandleRights`）がない | `BADF` のイベント | 同左（イベントキュー・タイマーはここ） |
| パイプ（`ByteStream`） | 読めるバイトがあるか書く側が閉じた。`nbytes` = 読めるバイト、閉じていれば `HANGUP` | 空きがあるか読む側が閉じた。`nbytes` = 空き（閉じていれば 0）、閉じていれば `HANGUP` |
| コンソール（`Tty`） | 読める入力がある。`nbytes` = 読めるバイト | 2 バイト以上空いている。`nbytes` = 空き |
| ファイル（`OpenFile`） | 常にそろう。`nbytes` = `max(0, 大きさ − 位置)` | 常にそろう。`nbytes` = 0 |
| ディレクトリ（`OpenFile`） | 常にそろう。`nbytes` = 0 | 同左 |
| イベントキュー・タイマーなど、`READABLE` を待てる種類 | `isReady`（キューはイベントか捨てた知らせがある、タイマーは鳴った回数 ≥ 1）。`nbytes` = 1（wasmtime と同じく、バイトでないものは 1） | 書く権利がないので `BADF` |
| その条件を待てない種類 | `INVAL` のイベント | 同左 |

- 判定は何も取り出さない（キューのイベントもタイマーの回数も残る）
- `HANGUP` はパイプの相手側が閉じたときだけ立てる
- fd の `NONBLOCK` は見ない（POSIX の `poll` と同じ）

### 時計の購読

`precision` は使わない。`flags` に `ABSTIME` 以外のビットがあるか、知らない `id` なら、その購読は `INVAL` のイベント。

| `id` | 相対 | 絶対（`ABSTIME`） |
| --- | --- | --- |
| `REALTIME` | 単調時計の今 + `timeout` | 単調時計の今 + (`timeout` − 実時間の今)。入った時点で写し、その後に実時間が飛んでも追わない（ADR 0064） |
| `MONOTONIC` | 同上 | `timeout`（`clock_time_get` と同じ起点） |
| `PROCESS_CPUTIME`・`THREAD_CPUTIME` | 同上 | `WasiP1Basic` の起点 + `timeout`（`clock_time_get` と同じ） |

- `timeout` は u64 として読み、足し算は飽和させる（2^63 以上は `Long.MAX_VALUE` で、待ち続ける）
- 期限が今以前ならすぐそろう（`timeout` = 0 は「待たずに確かめる」）
- タイマーが鳴ったのに期限が来ていない（時計の復元など）ときは、まだの期限に設定し直して待つ

### 燃料（仮の値。確定は #251）

- 呼び出しごとに `CALL_FUEL`（100。ほかの p1 の関数と同じ）、判定のたびに購読1つにつき `SUBSCRIPTION_FUEL`（10）

## 規模

- 1回の呼び出しの費用は購読の数に比例し、タイマーは時計の購読がいくつあっても1つ。tick ごとの処理はない
- `BLOCKING` の p1 は待つ間スレッドを持つ（今の `fd_read` と同じ）。待っている台がスレッドを持たない形は #329 で作る

## 保存（#81・#82 への申し送り）

- 新しく保存する状態はない。一時的なタイマーは今の `Timer`（`ObjectTable` の中）で、`BLOCKING` の待ちはもともと保存できない（ADR 0043）
- #329 で `WAIT_POINT` の p1 が待つようになったら、待っている間の状態は「読み込んだ購読の並び」（`WasiP1Poll.Subscription` は番号と列挙だけ）と「一時的なタイマーのオブジェクトID」になる。#329 は `WasiP1Poll.evaluate` の `Outcome.Wait`（待つ key と期限）を使う

## 確かめたこと

- `WasiP1PollTest`: ダミーのモジュールから呼び、メモリの中身で確かめる（配置、入口と購読ごとのエラー、パイプと `HANGUP`、ファイル・ディレクトリ、イベントキューの fd が受け取る種類のイベントでだけ起き判定で減らないこと、捨てた知らせだけでそろうこと、タイマーの fd、時計の相対・絶対・`REALTIME` の写し・飽和・過去、早い期限だけがそろうこと、fd と時計の組み合わせ、止めたときと終わったときに一時的なタイマーが閉じること、タイマーの上限で `AGAIN`、`WAIT_POINT` の `AGAIN`、燃料）
- `WasiP1PollProgramTest`: wasi-libc でビルドした `core/src/test/wasm/poll_wasi.c` を Endive とカーネルの上で動かす（`poll`、`nanosleep`、`clock_nanosleep(TIMER_ABSTIME)`、`__wasi_poll_oneoff` の直接の呼び出し）
- `FdPollTest`: `FdIo.pollLocked` の表の各行。`TimerWaitTest`: 一時的なタイマーの口。`WasiP1ImportCheckTest`: `poll_oneoff` の型の確認

## 決めないこと

- p1 のプログラムがイベントキュー・タイマーの fd を開く import と、イベントの取り出し方: #73
- `WAIT_POINT` の p1 を `poll_oneoff` で待たせる形: #329
- p2 の `wasi:io/poll`・`wasi:clocks`: #264 配下、p3 の waitable-set: #266
- 保存のバイト形式: #81・#82。仮の値の確定: #251
- ADR 0045・0071 が #72 としていた「cwd と wasi-libc の `PWD` の合わせ方」は `poll_oneoff` の範囲の外なので、#332（#68 のサブ Issue）に渡す
