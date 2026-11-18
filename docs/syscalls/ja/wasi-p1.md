---
title: WASI p1
description: wasi_snapshot_preview1 の関数ごとの対応と、標準との差、読み込みで断るもの、待ち方と保存。
---

KiBi Computers は WASI p1（モジュール名 `wasi_snapshot_preview1`）を、witx の定義どおりの関数の形で出しています。この節は、関数ごとの対応と、標準（と wasmtime などの一般的な実装）から振る舞いが違うところを書きます。

## 動かせるモジュール

- **コマンド形式だけ**を動かします。関数 `_start` を export していないモジュールは、読み込みで `NOEXEC` です。reactor 形式（イベントを受け取る export）の入口は定義していません。イベントは、キューの fd を `poll_oneoff` で待って受け取ります
- `_start` から戻ると終了コード 0、`proc_exit(n)` を呼ぶと終了コード `n`（32 ビットそのまま）で終わります
- p1 のモジュールではない Component Model のコンポーネントは、p1 の層では扱いません。`wasi:cli/run@0.2.x` を export するコマンドのコンポーネントは p2 の層で動き、それ以外は `NOEXEC` です（[p2・p3 の予定](./p2-p3.md)）
- 線形メモリを export してください。メモリのない呼び出しは `FAULT` か、トラップになります

## 関数ごとの対応

「対応」は実装していることを、`NOSYS` は import はできるが呼ぶと常に `NOSYS` を返すことを表します。

<!-- check:p1-functions -->
| 関数 | 対応 | 備考 |
| --- | --- | --- |
| `args_get` | 対応 | |
| `args_sizes_get` | 対応 | |
| `environ_get` | 対応 | `PWD` を必ず含みます（[作業ディレクトリ](#作業ディレクトリ)） |
| `environ_sizes_get` | 対応 | 同上 |
| `clock_res_get` | 対応 | どの時計も 1 µs |
| `clock_time_get` | 対応 | [時計](#時計) |
| `fd_advise` | 対応 | 引数を確かめるだけで、中身には効きません。ストリームは `SPIPE` |
| `fd_allocate` | 対応 | `offset + len` まで広げます（縮めません） |
| `fd_close` | 対応 | |
| `fd_datasync` | 対応 | ストリームは `INVAL` |
| `fd_fdstat_get` | 対応 | [種類と権利](#種類と権利) |
| `fd_fdstat_set_flags` | 対応 | `APPEND` と `NONBLOCK` を変えられます。`DSYNC`・`RSYNC`・`SYNC` は `NOTSUP`、知らないビットは `INVAL` |
| `fd_fdstat_set_rights` | 対応 | fd を確かめてから常に `NOTSUP`（p1 の権利は持ちません） |
| `fd_filestat_get` | 対応 | ストリームは種類と `nlink = 1` だけ |
| `fd_filestat_set_size` | 対応 | 書けない fd は `BADF`、ストリームは `INVAL` |
| `fd_filestat_set_times` | 対応 | `*_NOW` はマシンの実時間。`ATIM` と `ATIM_NOW`（`MTIM` も）を一緒に指定すると `INVAL` |
| `fd_pread` | 対応 | 位置を変えません。ストリームは `SPIPE` |
| `fd_prestat_get` | 対応 | [preopen](#preopen) |
| `fd_prestat_dir_name` | 対応 | 同上 |
| `fd_pwrite` | 対応 | 位置を変えません。ストリームは `SPIPE` |
| `fd_read` | 対応 | 止まりうる。[読み書き](#読み書き) |
| `fd_readdir` | 対応 | [ディレクトリの読み出し](#ディレクトリの読み出し) |
| `fd_renumber` | 対応 | 移す先は閉じていてもかまいません（範囲内なら） |
| `fd_seek` | 対応 | ストリームは `SPIPE`、ディレクトリは `ISDIR`、知らない whence は `INVAL` |
| `fd_sync` | 対応 | ストリームは `INVAL` |
| `fd_tell` | 対応 | ストリームは `SPIPE` |
| `fd_write` | 対応 | 止まりうる。[読み書き](#読み書き) |
| `path_create_directory` | 対応 | 最後の名前が `.`・`..` なら `EXIST` |
| `path_filestat_get` | 対応 | |
| `path_filestat_set_times` | 対応 | `fd_filestat_set_times` と同じ規則。読み取り専用のマウントは `ROFS` |
| `path_link` | 対応 | 基点の fd を確かめてから常に `NOTSUP`（ハードリンクはありません） |
| `path_open` | 対応 | [パス](#パス) |
| `path_readlink` | 対応 | リンクはないので、パスがあれば `INVAL`、なければそのエラー |
| `path_remove_directory` | 対応 | 最後の名前が `.` なら `INVAL`、`..` なら `NOTEMPTY` |
| `path_rename` | 対応 | マウントをまたぐと `XDEV`、マウントの位置は `BUSY`。最後の名前が `.`・`..` なら `INVAL` |
| `path_symlink` | 対応 | 基点の fd を確かめてから常に `NOTSUP`（シンボリックリンクはありません） |
| `path_unlink_file` | 対応 | ディレクトリは `ISDIR` |
| `poll_oneoff` | 対応 | 止まりうる。[poll_oneoff](#poll_oneoff) |
| `proc_exit` | 対応 | モジュールの start 関数の中で呼ぶと、異常終了（crashed）になります |
| `proc_raise` | `NOSYS` | p1 でも非推奨のため実装しません |
| `random_get` | 対応 | マシンの環境の乱数の元で埋めます |
| `sched_yield` | 対応 | すぐに 0 を返します。CPU の割り当ては燃料で決まるので、譲る効果はありません |
| `sock_accept` | `NOSYS` | ソケットは未実装（[#76](https://github.com/neverclear86/KiBi-Computers/issues/76)） |
| `sock_recv` | `NOSYS` | 同上 |
| `sock_send` | `NOSYS` | 同上 |
| `sock_shutdown` | `NOSYS` | 同上 |
<!-- /check -->

## 読み込みで断るもの

プログラムを実行するとき（`kibi:process` の `spawn`、シェルからの起動など）、カーネルはモジュールの import と export を確かめ、次のどれかに当たれば実行せずに `NOEXEC` で断ります。

- `wasi_snapshot_preview1` の、上の表にない名前の関数
- 実装している p1 の関数の、witx と違う型の import
- 関数でない import（メモリ・テーブル・グローバル・タグ）
- `wasi_snapshot_preview1` と `kibi:` で始まるもの以外のモジュール名（`env` など）
- `kibi:*` の import の誤り（出していない関数、型の違い、版の違い）と、`cabi_realloc` が要る関数を import するのに `cabi_realloc` を export していないもの（[kibi:* の共通の規則](./kibi-abi.md)）
- 予約の名前: モジュール `kibi` の import（カーネルが燃料の計測と待機点への変換に使う空間）。古い名前 `kibi.memory_remaining`・`kibi.memory_commit` も含みます。export の `kibi_fuel`・`kibi_async_state` も予約です
- 待機点に変換できない作り（[待ち方と保存](#待ち方と保存)）
- 大きすぎるモジュール、関数やローカル変数などの数が多すぎるモジュール、読み込みに時間がかかりすぎるモジュール（上限は仮の値）

実行ファイルが普通のファイルでなければ `ACCES`、大きすぎれば `FBIG` です。先頭が `#!` のファイルは、その行のインタプリタで実行します（argv は `[インタプリタ, (#! 行の引数), スクリプトの絶対パス, 元の argv[1..]]`）。

## fd

### 標準入出力と preopen

- fd 0・1・2 は標準入力・標準出力・標準エラー出力です。何がつながるか（コンソール、パイプ、閉じたまま）は、プロセスを作った側が決めます
- 起動で最初に動くプロセス（init）には、fd 3 に `/` のディレクトリが1つだけ preopen として渡されます。マウント（ディスク、`/tmp`、`/dev`）はすべて `/` の下に見えるので、これ1つで全体を辿れます
- 子プロセスに fd 3 以降を渡すかは、作る側が決めます（`kibi:process` の `spawn` では、fd 3 以降は書いたものだけが渡ります）

### preopen

`fd_prestat_get` は、**ディレクトリを開いている fd を preopen として答えます**。名前は、開いたときの絶対パス（`/`、`/home` など）です。ディレクトリでない fd は `BADF` です。子に渡したディレクトリの fd は、そのまま子の preopen になります。wasi-libc は fd 3 から `BADF` が返るまでを preopen として読むので、preopen は fd 3 から詰めて並べてください。

### 種類と権利

| fd が指すもの | `fd_fdstat_get` の filetype | 権利 |
| --- | --- | --- |
| 普通のファイル | `REGULAR_FILE` | 位置・同期・属性の操作と、開き方に応じた `FD_READ`・`FD_WRITE` など |
| ディレクトリ | `DIRECTORY` | `path_*`・`fd_readdir` など。継承する権利はすべて |
| `/dev/null`・`/dev/zero`・`/dev/random`・`/dev/urandom` を `path_open` で開いたもの | `CHARACTER_DEVICE` | 普通のファイルと同じ（`SEEK`・`TELL` を持ちます） |
| コンソール | `CHARACTER_DEVICE` | `FD_READ`・`FD_WRITE`（開き方に応じて）、poll、filestat。`SEEK`・`TELL` は持ちません |
| パイプ、イベントキュー、タイマー、プロセスなどのハンドル | `UNKNOWN` | 同上 |

- 権利は fd の種類と、開いたときの読み書きの別から毎回作ります。実際に守らせるのは読み書きの別だけで、p1 の細かい権利（`fd_fdstat_set_rights`、`path_open` の `rights_inheriting`）では絞れません
- wasi-libc の `isatty` は、`CHARACTER_DEVICE` で、かつ `SEEK`・`TELL` の権利がない fd でだけ真になります。コンソールがそれにあたります。`/dev` のファイルは `CHARACTER_DEVICE` ですが `SEEK`・`TELL` を持つので、偽です

### 読み書き

- `fd_read`・`fd_write` は、iovec をまとめて1回の読み書きにします。1回に扱うのは合わせて 1 MiB までで、それを超える分は一部だけの読み書きになります
- 待つ fd では、読みは少しでも読めるまで、書きは書けるまで待ちます。ストリームへの書きは、少しでも書けたらその数を返すことがあります（一部だけの書き込み。wasi-libc は残りを書き直します）
- `NONBLOCK` の fd で何もできなければ `AGAIN` です。**`NONBLOCK` は fd ごとの属性**で、POSIX と違い、複製した fd とは共有しません
- 読みの終わり（EOF）は 0 バイトです
- ゲストのメモリの範囲は、読み書きの前にすべて確かめ、外れていれば何もせずに `FAULT` を返します

### ディレクトリの読み出し

- `fd_readdir` は `.`・`..` の後に、名前の順で項目を返します
- cookie は並びの番号で、dirent の `d_next` は番号 + 1 です。保存・復元をまたいでも同じ意味で続きを読めます（間に中身が変われば、POSIX の `readdir` と同じく飛んだり重なったりします）
- バッファに入りきらない最後の dirent は途中で切ります（`bufused == buf_len`）

## パス

- パスは `(ptr, len)` の UTF-8 です（NUL 終端なし）。壊れた UTF-8 は `ILSEQ`、長すぎれば（仮の値 4096 バイト）メモリを読まずに `NAMETOOLONG`
- 基点の fd の下だけを辿れます。**絶対パスと、基点の外へ出る `..` は `NOTCAPABLE`** です
- 基点の fd が開いていなければ `BADF`、ディレクトリでなければ `NOTDIR`
- リンクはありません。`lookupflags` の `SYMLINK_FOLLOW` は受け取って無視し、知らないビットは `INVAL` です
- 末尾の `/` は「ディレクトリでなければ `NOTDIR`」の意味です
- `path_open`: `oflags` の `CREAT`・`DIRECTORY`・`EXCL`・`TRUNC` を使います。`rights_base` は `FD_READ`（読む）と `FD_WRITE`（書く）だけを見ます。`fdflags` の `APPEND`・`NONBLOCK` を使い、`DSYNC`・`RSYNC`・`SYNC` は `NOTSUP`、知らないビットは `INVAL`。fd の表が満杯なら、開く前に `MFILE`（`TRUNC` で中身を消してから失敗することはありません）

## poll_oneoff

- 購読の数が 0 か、上限（仮の値 1024）を超えれば `INVAL`。`in`・`out`・`nevents` が範囲外なら、何も書かずに `FAULT`
- 知らないタグの購読があれば、呼び出し全体が `INVAL` です（wasmtime と同じ）
- そろった購読（エラーのものを含む）ごとに、購読の順に `event` を書きます。そろっていなければ、どれかがそろうまで待ちます。fd の `NONBLOCK` は見ません（POSIX の `poll` と同じ）
- 判定は何も取り出しません（キューのイベントもタイマーの回数も残ります）

### fd の購読

| fd が指すもの | `FD_READ` | `FD_WRITE` |
| --- | --- | --- |
| 開いていない・その向きの権利がない | `BADF` のイベント | 同左 |
| パイプ | 読めるバイトがあるか、書く側が閉じた。`nbytes` は読めるバイト数 | 空きがあるか、読む側が閉じた。`nbytes` は空き |
| コンソール | 読める入力がある | 書ける空きがある |
| ファイル・ディレクトリ | 常にそろう | 常にそろう |
| イベントキュー、タイマー、デバイスの呼び出し（`kibi:*` のハンドル） | 取り出せるものがある・終わった。`nbytes` は 1 | 書く権利がないので `BADF` |
| 子プロセス（`kibi:process` のハンドル） | 終わった。`nbytes` は 0 | 書く権利がないので `BADF` |
| それ以外で待てない種類 | `INVAL` のイベント | 同左 |

- `HANGUP`（`eventrwflags`）は、パイプの相手側が閉じたときだけ立てます

### 時計の購読

- `precision` は使いません。`flags` に `ABSTIME` 以外のビットがあるか、知らない時計の `id` なら、その購読は `INVAL` のイベントになります
- 期限は、呼び出しに入った時点で単調時計の時刻に直します。`REALTIME` の絶対時刻も入った時点で直すので、その後に実時間が飛んでも追いません
- `timeout` は符号なしで読み、足し算は飽和させます（とても大きな値はずっと待ちます）。期限が今以前ならすぐにそろいます（`timeout = 0` は待たずに確かめる）
- 時計で待つための内部のタイマーがマシンの上限に当たったときは、呼び出し全体が `AGAIN` です

## 時計

| id | 値 |
| --- | --- |
| `REALTIME` | マシンの環境の実時間（ナノ秒） |
| `MONOTONIC` | マシンの単調時計。マシンを作ったときが 0 |
| `PROCESS_CPUTIME`・`THREAD_CPUTIME` | そのプロセスが始まってからの単調時計の経過。**CPU を使った時間ではなく、待っていた時間も含みます**（2つは同じ値） |

- 分解能はどれも 1 µs です
- タイマー（`kibi:kernel/timers`）と `poll_oneoff` の時計は、`MONOTONIC` と同じ時計です
- 起動からの経過（tick）とゲーム内時間は `kibi:computer/clock` にあります。保存から戻ったときの時計の飛びは、イベント `restored` で分かります（[kibi:kernel](./kibi-kernel.md#イベントの種類)）

## 作業ディレクトリ

p1 には作業ディレクトリ（cwd）を読み書きする関数がありません。カーネルはプロセスごとに cwd を持ち、p1 のプログラムには環境変数 `PWD` で伝えます。

- `environ_get` が見せる環境変数には、`PWD=<カーネルの cwd>` が必ず1つ入ります（渡された環境に `PWD` があればその位置で値を置き換え、なければ最後に足します）。値は最初に `environ_*` を呼んだ時点の cwd で、その後は変わりません
- wasi-libc は起動時に `PWD` を読みません。相対パスをカーネルの cwd から辿らせるには、プログラムの起動時に `getenv("PWD")` へ `chdir` してください
- アダプター（`wasi_snapshot_preview1`）で p2 のコンポーネントに包んだ p1 のプログラムでも同じです。p2 の `get-environment` が同じ `PWD` を返します（値は起動時の cwd。[WASI p2・p3](./p2-p3.md)）
- libc の `chdir` はカーネルに伝わりません。子プロセスに引き継がせたいときは、`kibi:process` の `set-cwd` も呼んでください（[kibi:process](./kibi-process.md)）

## 待ち方と保存

止まりうる関数は、[止まりうる import](./kibi-abi.md#止まりうる-import) の表のものです。

- カーネルは読み込みのときに、モジュールを**待機点に変換**します（呼び出しの途中の状態を線形メモリの外へ退避できる形に書き換えます）。変換したプロセスは、止まりうる関数で待つ間、スレッドを持たず、マシンごとワールドに保存して、読み込んだ後にその呼び出しの続きから動けます。燃料が切れたときも、多くの場合は同じように止まります
- **変換できない作りのモジュールは、読み込みで `NOEXEC` です。** 止まりうる関数を呼びうる関数の中に、SIMD（v128）のローカル変数や命令、型付きの参照型（GC・関数参照）、止まりうる関数への末尾呼び出しなどがある場合です。エラーのメッセージに、どの関数のどの機能かが出ます。止まりうる関数を import しないモジュールは、この理由では断りません
- 例外: モジュールの start 関数の中（インスタンスを作っている途中）と、`cabi_realloc` の中では、退避ができません。start 関数の中で待つと、スレッドを持ったまま待ちます。**`cabi_realloc` の中で止まりうる関数を呼ぶと、トラップになります**（[kibi:* の共通の規則](./kibi-abi.md#cabi_realloc)）
- 保存から戻ると、待っていた呼び出しはやり直されます（`fd_read` は読む前から、`poll_oneoff` は同じ期限で）。`spawn` は子をまだ作っていなければ読み込みからやり直し、二重には作りません。`invoke` は、実行中だったメインスレッドのメソッドを `failed("interrupted")` で返します（[kibi:device](./kibi-device.md)）
- 保存の時期と、保存できなかったプロセスの扱いの詳しい規則は、ADR の [0081](https://github.com/neverclear86/KiBi-Computers/blob/main/docs/adr/0081-wait-point-save-lifecycle.md)・[0085](https://github.com/neverclear86/KiBi-Computers/blob/main/docs/adr/0085-restore-on-load.md) にあります

## 燃料

システムコールの呼び出しは、1回ごとに燃料を少し使います。読み書きしたバイト数、取り出したイベントの数、一覧の件数、ディスクのマウントなど、仕事の量に比例して加わる分もあります。量はすべて仮の値で、正式版の前に決めます（[#251](https://github.com/neverclear86/KiBi-Computers/issues/251)）。
