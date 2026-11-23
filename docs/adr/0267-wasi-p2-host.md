# 0267: WASI p2 のホストのインターフェース（`wasi:*@0.2.x`）

- 状態: 採用
- Issue: #267（Epic #259）。前提は ADR 0262（読み込みと import の確認）、ADR 0263（リンク）、ADR 0264（Canonical ABI）、ADR 0265（resource のハンドル表）、ADR 0043（カーネル。`BLOCKING`）、ADR 0332（p1 の cwd）
- 後続: #164（`wasi:sockets` の実物）、#757（スレッドを持たない待ち）、#268（p3）、#269（コンポーネントのプロセスの保存）、#687（コンポーネントの exec）、#685（`kibi:*` の p2）、#251（仮の値の確定）
- 番号は Issue 番号にそろえている
- 追記（#685）: `kibi:*` の p2 は `KibiP2Host`（ADR 0685）。`WitReader` は外で読んだ interface（`wasi:*`）を `use` で引けるようにし、関数の型をインターフェースの型を読み終えてから読むようにした

## 背景

#263 でリンクの口（`ComponentHost`）ができたが、`wasi:*` のホストの実物はなかった。ADR 0262・0263 は、`wasi:*` の版の受け付け方とホストの一覧を「システムコールの ABI に関わるので #267 で決める」として残していた。p2 のコマンド（`wasi:cli/command` の world のコンポーネント。wasmtime のアダプターで p1 のプログラムを包んだものを含む）を、カーネルの上で最後まで動かせるようにする。

方針（AGENTS.md）は、カーネルの土台（fd・イベント待ち・stream）を1つにし、p1・p2・p3 をその上の薄い層にすること。独自の形は足さず、標準の WASI 0.2 の WIT のとおりに出す。

## 決定

### 置き場所

`core` の内部（`pro.ikili.kibicomputers.core.wasi.p2`。すべて `internal`）。公開 API（`pro.ikili.kibicomputers.api`）は変えない。

| 型 | 役目 |
| --- | --- |
| `WasiP2Wit` | ホストの型の一次情報。WASI 0.2.12 の WIT を文字列で持つ（下記） |
| `WitReader` | 上の WIT を `CanonType`・`CanonFunction` に読む小さな読み手（この WIT が使う構文だけ） |
| `WasiP2Host` | `ComponentHost` の実物。WIT の関数ごとに本体（`WasiP2Io`・`WasiP2Cli`・`WasiP2Fs`・`WasiP2Sockets`）を付け、過不足をビルド時（初回の参照）に確かめる |
| `WasiP2Driver` | p2 のコマンドを動かす ABI 層のドライバー（待機点に変換できれば `WAIT_POINT`、できなければ `BLOCKING`。#757・ADR 0757）。export の `wasi:cli/run#run` を呼ぶ |
| `HostResourceTable` | プロセスごとのホストの resource の表（rep → 中身）。fd の表とは別 |

### ホストの型は WIT から作る

ホストの関数の型は、手で `CanonType` を組まず、WASI 0.2.12 の WIT（wasmtime 50.0.1 のアダプター `wasi_snapshot_preview1.command.wasm` に入っているもの。`wasm-tools component wit` で書き出した）を埋め込んで読む。

- 手で組むと、ゲストの型との形の照合（`CanonType.sameShape`）で 1 か所違うだけでリンクが通らず、見つけにくい。WIT のとおりに読めば、型は標準と一致する。
- `WasiP2HostTest` が、埋め込んだ WIT とアダプターが持つ WIT の一致を確かめる。アダプターを上げたら書き出し直す。
- 読み手は、この WIT が使う構文（`package`・`interface`・`world`（読み飛ばす）・`use`・`type`・`record`・`variant`・`enum`・`flags`・`resource`（method・static・constructor）・関数）だけを扱う。resource はインターフェースの頭でまとめて宣言してから読む（前方参照のため）。
- `wasi:cli/run` はゲストが export するものなので、ホストには出さない（ドライバーが型を使う）。

### 版の受け付け方

`wasi:*` の import は、版が `@0.2.<n>`（`n` は 0 以上の整数。pre-release・build を付けない）のものだけを受ける。

- どの `0.2.<n>` も同じ `HostInterface`（同じ関数・同じ resource の識別子）に写す。ゲストの宣言はホストの部分型であればよい（ADR 0263）ので、基準より古い版（関数が少ない）も新しい版（ある関数だけを使う）も通る。基準にない関数を宣言すれば、リンクの時点で `WasmLoadException`。
- 同じ resource の識別子に写すので、版の違う import（例: `wasi:io/poll@0.2.3` と `@0.2.12`）の `pollable` は 1 つの正規の型になり、片方の版で作ったものをもう片方の版の関数に渡せる。
- 版なし・`0.2.0-rc-*` などの pre-release・`0.3.x`（p3 は #268）・それ以外の major / minor は、読み込みの時点（`ComponentImportPolicy`）で断る。
- 理由: WASI 0.2 は minor 内で後方互換を約束し、wasmtime も `0.2.x` を semver の互換として 1 つの実装に写す。実際のツールチェーンの出力は版がそろわない（wasmtime 50 のアダプターは `@0.2.12` の中に `wasi:filesystem/preopens@0.2.6` を混ぜ、`kibi:*` の WIT は `@0.2.3` を参照する）ので、完全一致にすると実物が動かない。
- 基準の版は `0.2.12`（`WasiP2Wit.BASE_VERSION`）。基準を上げるときは、WIT を書き出し直すだけで、受け付け方は変わらない。

#### `@unstable` のもの

WIT の `@unstable` のうち、出すのは `wasi:cli/exit` の `exit-with-code` だけ（アダプターが使い、終了コードを返すのに要る）。`wasi:clocks/timezone` などそのほかの `@unstable` は、埋め込んだ WIT に含めない（import すればリンクの失敗）。

### コマンドの規則（export の `run`）

`WasiP2Driver` は、export の `wasi:cli/run@0.2.<n>` のインスタンスの `run`（`func() -> result`）を 1 つだけ持つコンポーネントを動かす。ない・2 つ以上・型が違うときは `NOEXEC`。

- `run` が `ok` なら終了コード 0、`err` なら 1。`exit(result)` は `ok` で 0・`err` で 1、`exit-with-code(c)` は `c`。
- コンポーネントの `exec` は #687 でつないだ（済み）。`KernelDrivers.DEFAULT` が、コンポーネントの `Program` にこのドライバーを選ぶ（ADR 0263 の追記）。

### カーネルへの写し方

#### resource の中身

- resource の rep は `HostResourceTable` の番号。中身（`P2Entry`）は stream・descriptor・directory-entry-stream・pollable・error と、中身のない印（`network` など）。中身はカーネルのオブジェクト（`HandleEntry` を通したファイル・パイプの端・TTY）を参照で持つ。
- 表はプロセスにあり、カーネルロックの中で触る。プロセスの終わり（`finishLocked`）と、写しの中で殺すとき（`killInCopy`）に、fd の表と同じく全部閉じてオブジェクトの参照を返す。上限は 1 プロセスあたり 4096 個（`HostResourceTable.LIMIT`。仮。#251）で、超えたらトラップ。超えるときは、作りかけの資源の参照（stream・descriptor・directory-entry-stream が持つ参照、開いたファイル）を返してからトラップする。`open-at` は、戻せない副作用（作る・切り詰める）の前に表の空きを確かめる（p1 の `path_open` が開く前に `MFILE` を確かめるのと同じ）。
- 子として数えるのは pollable だけ（`subscribe` で作ったもの。親は stream）。子の pollable が残る stream の `drop` はトラップ（WIT の約束。子を先に捨てる）。descriptor から作った stream（`read-via-stream` など）は、ファイルの参照を自分で持つので子に数えない。descriptor は、stream が残っていても `drop` できる（`read-directory` の stream も、ディレクトリの参照を自分で持つ）。
- pollable は待つ相手（期限か、親の stream）だけを持つ。待つときだけ、カーネルの一時的なタイマーを開き、待ち終わったら閉じる（pollable の数だけタイマーを持ち続けない）。
- マシンの動いているタイマーが上限（`TimerLimits.MAX_ARMED_TIMERS`）で、待つための一時的なタイマーを動かせないときは、`poll`・`pollable.block` はトラップにせず、期限の来ていない時計の pollable をそろったものとして早めに返す（`block` はそのまま返る）。p1 の `poll_oneoff` は同じ状況を一時的な不足（`AGAIN`）として返すが、p2 の `poll`・`block` にはエラーを返す道がない。ほかのプロセスがタイマーを使い切っただけで、時計を待つプロセス（アダプターで包んだ p1 の `sleep`・`poll` を含む）を落とさないため、早く起きたもの（時計を読めば分かる）として扱う。

#### stdio・preopen・引数・環境変数

- `get-stdin`・`get-stdout`・`get-stderr` は fd 0・1・2 のオブジェクトを参照する stream（位置を持たない共有の stream）。`terminal-stdin` などは、その fd が TTY のときだけ `some`。
- `preopens.get-directories` は p1 と同じく fd 3（`/`）を 1 つだけ、名前 `"/"` で返す（descriptor の flags は read と mutate-directory）。
- `get-arguments` は argv[0] を含む引数そのまま（p1 と同じ）。`get-environment` は環境変数から `PWD` を除いたもの、`initial-cwd` は開始時の cwd（ADR 0332 で p1 は `PWD` で渡すが、p2 には専用の関数がある）。親が `PWD` を環境変数に入れて起動しても、ゲストには見せない（古い値が `initial-cwd` とずれないように）。アダプターで包んだ p1 のプログラムは `PWD` を受け取らないので、`kibi_cwd.c` による cwd の同期は働かない（p2 のコマンドは `initial-cwd` で cwd を知る）。
  - 追記（#760）: `PWD` を除く規則をやめ、`get-environment` に `PWD=<initial-cwd>` を入れる。規則は p1 の `WasiP1Basic.p1Environment` と同じ（親が渡した `PWD` は同じ位置で値を置き換え、なければ最後に足す。`Process.env` は変えず、`PWD` の分は環境変数の大きさの上限に数えない）。値は起動時に固定した `initial-cwd`（`HostResourceTable.initialCwd`）で、`kibi:process` の `set-cwd` の後も変わらない。理由: wasmtime のアダプターは `initial-cwd` を import せず、wasi-libc と Rust の std も `initial-cwd` を読まないので、`PWD` で渡さないとアダプターで包んだ p1 のプログラムが cwd を知る方法がない。値を固定するので `initial-cwd` とずれず、アダプターが `environ_sizes_get` と `environ_get` で `get-environment` を 2 回呼んでも大きさと中身が合う。これで、包んだ p1 のプログラムでも `kibi_cwd.c` の同期が働く。

#### descriptor の種類（#865）

- `descriptor.get-type` と `descriptor.stat` の `type` は、fd の規則（`kernel/fd` の `descriptorFileType`。p1 の `fd_fdstat_get`・`fd_filestat_get` と同じ関数）で決める: ファイル（`OpenFile`。`/dev/null` などを含む）は VFS の種類、`Tty`（`/dev/tty`）は `character-device`、`/dev/device/<address>` の `DeviceHandle` とそれ以外は `unknown`（ADR 0054「fd とパスで種類が違う」）
- パスで見る `stat-at` と `read-directory` の項目は VFS の種類のまま（`/dev/device/<address>` は `character-device`。`ls -l` 向け）
- 理由: p1 とそろえる。wasmtime のアダプターの `fd_fdstat_get` は filetype を `get-type` から写すので、`character-device` を返していたときは、同じ p1 のプログラムが p1 のままなら `UNKNOWN`、アダプターで包むと `CHARACTER_DEVICE` に見えていた。なお、wasi-libc の p2 の `isatty` は種類を見ない（ファイルの fd はいつも偽）ので、ネイティブの p2 の `isatty` はこの変更の前から偽だった
- p3（#268）も同じ関数を使う

#### ファイル以外の descriptor の metadata-hash（#885）

- `descriptor.metadata-hash` は `descriptor.stat` と同じ値（`WasiP2Fs.descriptorStat`）から作る（`lower` = node、`upper` = device。ファイルは今までどおり）。ファイル以外の fd（`Tty`・`DeviceHandle` など）は stat の node・device が 0 なので、hash は `(0, 0)` になる。`openFile` を通さないので `bad-descriptor` にはならない
- 理由: p1 にそろえる。p1 の `fd_filestat_get` は、ファイル以外の fd で `ino=0`・`dev=0` の filestat を返す。wasi-libc の p2 の `fstat` と wasmtime のアダプターの `fd_filestat_get` は先に `metadata-hash` を呼ぶので、`bad-descriptor` を返していたときは、ネイティブの p2 もアダプターで包んだ p1 も、device・`/dev/tty` の fd の `fstat` が EBADF で失敗していた。`(0, 0)` なら、ネイティブの p2 の `st_ino`・`st_dev`（`lower`・`upper` から取る）と、アダプターの `ino`（`lower` から取る。`dev` はアダプターがいつも 1 にする）が p1 と同じになる
- `is-same-object` との関係: 真なら hash が等しい（同じカーネルのオブジェクトか、同じ `(node, device)` の `OpenFile`。`/dev/tty` を何度開いても同じ `Tty` なので真で、hash も等しい）。逆（hash が等しいなら同じもの）は WIT も求めていない
- 既知の衝突: `(0, 0)` は、device の fd どうし、device の fd と `/dev/tty`、マウントの祖先のディレクトリ（ADR 0051。node 0・device 0）の間で重なる。p1 の `ino`・`dev` でも同じものが重なるので、新しい食い違いではない
- 作らない hash: カーネルのオブジェクトの ID から作ると、閉じると使い回され、保存と復元をまたぐと変わり、p1 ともずれる。`/dev` の node を p2 の層で計算すると「ABI 層は `/dev` を知らない」（ADR 0054）に反する
- パスで引く `metadata-hash-at` は VFS の値のまま（`/dev/device/<address>` は `/dev` の node とマウントの ID）。fd とパスで違うのは、種類と同じく ADR 0054「fd とパスで種類が違う」の扱い
- 状態は足さない（定数を返すだけ）ので、1,000 台規模の負荷と保存形式には響かない

#### エラーの写し方

カーネルの `Errno` を `wasi:filesystem/types` の `error-code` に写す（`WasiP2Errors.TABLE`）。主な対応: `NOTCAPABLE`・`PERM` → `not-permitted`、`NOSYS`・`NOTSUP`・`NOEXEC` → `unsupported`、`MFILE`・`NFILE`・`NOMEM` → `insufficient-memory`、表にないもの → `io`。stream の `last-operation-failed` の `error` は errno を持ち、`to-debug-string` と `filesystem-error-code` で読める。パイプの相手が閉じた（`PIPE`）・EOF は `closed`。

#### 時計・乱数

- `monotonic-clock` はプロセスの単調時計（`ctx.monotonicNanos`。p1 の `CLOCK_MONOTONIC` と同じ）、`wall-clock` はマシンの壁時計。`subscribe-duration` は飽和加算。
- `wasi:random/random`・`insecure-seed` はマシンのエントロピー源、`insecure` は `ThreadLocalRandom`。1 回の `get-*-random-bytes` は 1 MiB まで（`WasiP2Cli.MAX_RANDOM_BYTES`。仮。#251）で、超えたらトラップ。
- p2 には CPU 時間の時計がない。アダプターは p1 の `CLOCK_PROCESS_CPUTIME_ID` を断る（p1 のプログラムを包んだときの違い）。

#### stream の上限

- `read-directory` の stream は、中身の写しを持たない。開いたディレクトリの参照と、最後に返した名前（名前の順）だけを持ち、`read-directory-entry` のたびに VFS から読み直して、その名前より後の最初の 1 つを返す（p1 の `fd_readdir` と同じく読み直す。途中で足されたり消されたりしても、名前の順の続きから読める）。呼び出しごとの燃料は、1 回につき 1 と、読み直した項目 64 個ごとに 1（仮。#251）。1 プロセスが持てる stream の数は `HostResourceTable.LIMIT` で抑えられ、ホストのヒープに留まる量は stream の数に比例しない。
- `splice`・`blocking-splice` は WIT の定義どおり、出力の `check-write` → 許可量と `len` の小さい方で入力を `read` → 出力へ `write` の順。出力が閉じている・失敗しているときは、入力を読む前に報告する。許可量が 0 なら、`splice` は 0 を返し（入力を読まない）、`blocking-splice` は受けられるまで待つ。読んだ後の `write` の失敗は、読んだバイトが入力に戻らないまま報告する（WIT の定義と同じ）。
- `read` の長さは 64 KiB で切る（`P2.MAX_READ`。先に確保しない）。`check-write` が返した許可量を超える `write` はトラップ（WIT の約束）。`blocking-write-and-flush` は 4096 バイトまで（WIT の約束）。ファイルへの書き込みの許可量は 64 KiB（`P2.FILE_WRITE_ALLOWANCE`）。
- `poll` は、空のリストと、p1 の `poll_oneoff` の上限（`WasiP1Poll.MAX_SUBSCRIPTIONS`）を超える数でトラップ。
- 燃料は、ホストの関数 1 回あたり p1 と同じ `WasiP1Fd.CALL_FUEL`（仮。#251）。lift・lower の分は ADR 0264 のとおり別に引く。

#### `mutate-directory`

descriptor の `mutate-directory` は `get-flags` で返すだけでなく、確かめる。ディレクトリの中身を変える関数（`create-directory-at`・`remove-directory-at`・`rename-at`（両方の基点）・`unlink-file-at`・`set-times-at`）と、`open-at` の作る・切り詰める・書く開き方は、基点の descriptor が `mutate-directory` を持たなければ `read-only`。`open-at` で開いたディレクトリの descriptor は、求めた flags に依らず基点の `mutate-directory` を引き継ぐ（基点が持たないのに求めたら `read-only`）。ファイルの descriptor は持たない。wasmtime のホストと同じく、変えられるかは preopen の権限で決め、開いたディレクトリごとには絞らない。wasmtime のアダプターの `path_open` は `mutate-directory` を求めないので、絞ると、アダプターで包んだ p1 のプログラムがサブディレクトリの fd を基点に変える操作（`unlinkat`・`openat(O_CREAT)` など）が失敗し、同じカーネルの p1 の層（`path_*` は基点の fd に変更の権利を求めない）と食い違うため。ルートの preopen は `read`・`mutate-directory` を持つので、今は `read-only` になる descriptor はない（確かめは、`mutate-directory` を持たない preopen を足すときのため）。

### `wasi:sockets`

関数はすべて出す（リンクを通すため。fallback は使わない）が、中身はまだない。`instance-network` は印の resource を返し、`create-tcp-socket`・`create-udp-socket`・`resolve-addresses` は `err(not-supported)`。それ以外（ソケットを作れないので、呼ばれることはない）はトラップ。実物は #164 で差し替える。wasmtime のアダプターは `wasi:sockets` を import しないので、p1 のプログラムを包んだものには影響しない。

### `write(list<u8>)` の負荷（ADR 0264 の宿題）

汎用の値（`CanonValue`）を経由しても、`list<u8>` は lift で 1 回 `ByteArray` に写すだけで、要素ごとのオブジェクトは作らない（`CanonAbi.loadList`）。4096 バイトの `blocking-write-and-flush` を、ホストの本体を通して 20,000 回呼んで測ると、1 回あたり約 3 µs、確保は約 16 KB（lift 相当の写し 4 KiB と、測る側の読み出し 4 KiB を含む。残りは stream の中の写し）。p1 の `fd_write` と同じ程度なので、型付きの codec は足さない。

### 保存

コンポーネントのプロセスは、今までどおり保存できないもの（`UNSAVEABLE`。ADR 0263）。保存のときは写しの中で殺し、ホストの resource の表も閉じて、オブジェクトの参照を残さない。表の中身（rep → 中身）を保存する形は #269 で決める（中身はカーネルのオブジェクトの参照と小さな値だけなので、fd の表と同じ形で書ける）。

追記（#760）: #269 でコンポーネントのプロセスを保存するときは、`HostResourceTable.initialCwd` も保存する。保存しないと、戻した後に今の cwd で決め直され、`get-environment` の `PWD` と `initial-cwd` が起動時の値から変わる。

## 制約と残り

- `WasiP2Driver` は、コンポーネントを待機点に変換できれば `WAIT_POINT` で動き、待っている間スレッドを持たない（#757。ADR 0757）。`kibi:device` の `invoke`、`kibi:process` の `spawn`・`spawn-with-handles` も #800 から巻き戻して待つ（`HostWait.UNWIND`。ADR 0800）ので、これらを import するものも `WAIT_POINT` で動く。ホストの関数に、巻き戻せない待ち（`BLOCK`）はもうない。`BLOCKING` に残るのは、ゲスト側の作りで待機点に変換できないもの（#801）。
- 仮の値（`CALL_FUEL`・`HostResourceTable.LIMIT`・`MAX_READ`・`FILE_WRITE_ALLOWANCE`・`MAX_RANDOM_BYTES`）の確定は #251。
- `symlink-at`・`link-at` は `unsupported`、`readlink-at` は `invalid`（VFS にリンクがない）。
- `wasi:clocks/timezone` は出さない（`@unstable`）。
