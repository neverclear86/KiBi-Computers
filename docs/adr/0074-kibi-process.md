# 0074: 独自拡張 kibi:process（プロセスの生成・待機・強制終了と cwd）

- 状態: 採用
- Issue: #74（task）。前提: ADR 0045（プロセスの生成・引数・fd の渡し方）、0046（終了待ち・kill・親なしのゾンビ）、0073（`kibi:*` の WIT と p1 の import の導き方）、0329（待機点への変換）、0332（p1 の cwd）。#358（読み込みをスレッドなしで待つ `beginExec`/`finishExec`）
- 後続: #101（シェル）、#116（SDK の `posix_spawn`・`waitpid`・`kill`・`chdir` 風の束縛）、#262〜#268（p2・p3 の層）、#251（仮の値の確定）
- ABI の選択（Q1〜Q9）は、#74 のプランへのオーナーの回答（すべて A。p2/p3 の WIT は仮置き）で決めた

## 決めたこと（要約）

- `kibi:process@0.1.0` に、p1・p2・p3 で共通の `interface process`、p2 だけの `process-poll`、p3 だけの `process-async` を置く。p1 に出すのは `process` だけ
- 子は resource `process`（p1 ではハンドル = カーネルの fd 番号）。`status()` は待たない。待つのは `poll_oneoff` の `FD_READ`（Q1-A）。`wait-any` は出さない
- `spawn` は読み込み（コンパイル）が終わるまで待つ（止まりうる import。Q2-A）。読み込みの失敗は `spawn` の `no-exec`
- 終わり方は `variant exit-status { exited(u32), killed(option<signal>), crashed }`（カーネルの `ExitStatus` のまま。Q3-A）
- 子への fd は `fds: list<fd-assign>`。fd 0/1/2 は書かなければ引き継ぎ、fd 3 以降（preopen を含む）は書いたものだけ（Q4-A。ADR 0045）
- interface は1つ（生成・待機・シグナル・pid・cwd。Q5-A）。pid で操作するのは `kill-pid`・`self-pid`・`parent-pid`・`set-signal-ignored` だけで、プロセスの一覧は出さない（Q6-A）
- 公開 API（`pro.ikili.kibicomputers.api`）と保存のバイト形式は変えない。新しく持つ状態は、親の fd 表の項目が既存の `ProcessObject`（ADR 0046）を指すことだけ
- **p2/p3 は WIT とこの ADR だけで、実装しない。仮置き**: p2/p3 での形（interface・関数・型・依存する `wasi:*` の版）は、p2/p3 の層（#262〜#268）を作るときに直してよい（v0.1.0 までは版を変えずに直す。ADR 0073 の Q5-A）。p1 の ABI は対象外

## WIT

`core/src/main/wit/kibi-process/`:

- `process.wit`: `enum signal { interrupt, quit, terminate, kill }`、`variant exit-status`、`variant fd-source { parent(u32), closed }`、`record fd-assign { fd, source }`、`record spawn-options { args, env: option<list<tuple<string, string>>>, cwd: option<string>, fds }`、`resource process { spawn: static func, pid, status, kill }`、`kill-pid`・`self-pid`・`parent-pid`・`set-signal-ignored`・`get-cwd`・`set-cwd`。`wasi:*` を参照しない
- `process-poll.wit`（p2、仮置き）: `subscribe(borrow<process>) -> pollable`（刈り取らない）、`spawn-with-handles`（`handle-source` で stream・descriptor・process を渡す）。`wasi:io@0.2.3`・`wasi:filesystem@0.2.3`
- `process-async.wit`（p3、仮置き）: `spawn: async func`（`handle-source` に `stream<u8>`・`capture`）、`wait: async func(borrow<process>) -> exit-status`（親なら刈り取る）。`wasi:filesystem@0.3.0`
- `world.wit`: `imports`（`process` だけ。`wasi:*` をたどらない）、`imports-p2`（＋`process-poll`）、`imports-p3`（＋`process-async`）

### パッケージをまたぐ参照（`deps/` を置かない）

- リポジトリの `core/src/main/wit/` には `deps/` のディレクトリも symlink も置かない（Windows で `core.symlinks` が無効だと symlink が壊れ、コピーはずれる）
- `use kibi:<pkg>/<iface>@<版>.{...}` と同じパッケージの `use <iface>.{...}` の参照先（パッケージ・版・interface・型の名前）は、`KibiWitTableTest` がテキストで確かめる。`wasi:*` の参照は WIT を置かないので、形（`@<semver>`）だけを確かめる
- 解析・解決は `KibiWitParseTest` がテストの時に `deps/` を組み立てて行う（ほかの `kibi:*` のパッケージと `wit-stubs/`。`wasi:filesystem` の最小のスタブを足した）
- WIT のツール（`wasm-tools component embed`・wit-bindgen）で読むときは、使う側が `deps/` を組み立てる（SDK #116 のビルドで行う）

## p1 向けの import

モジュールは `kibi:process/process@0.1.0`（ADR 0073 の規則で導いたもの）。

| 名前 | core の型 | 備考 |
| --- | --- | --- |
| `[static]process.spawn` | `(path_ptr, path_len, args_ptr, args_len, env_is_some, env_ptr, env_len, cwd_is_some, cwd_ptr, cwd_len, fds_ptr, fds_len, ret_ptr: i32) -> ()` | 止まりうる import（`WasiP1.WAIT_IMPORTS`） |
| `[method]process.pid` | `(self: i32) -> i32` | 不正なハンドルはトラップ |
| `[method]process.status` | `(self, ret_ptr: i32) -> ()` | |
| `[method]process.kill` | `(self, sig, ret_ptr: i32) -> ()` | |
| `[resource-drop]process` | `(self: i32) -> ()` | `fd_close` と同じ閉じ方。不正なハンドルはトラップ |
| `kill-pid` | `(pid, sig, ret_ptr: i32) -> ()` | |
| `self-pid` / `parent-pid` | `() -> i32` | |
| `set-signal-ignored` | `(sig, ignored, ret_ptr: i32) -> ()` | |
| `get-cwd` | `(ret_ptr: i32) -> ()` | `cabi_realloc` が要る（なければ読み込みで `NOEXEC`） |
| `set-cwd` | `(path_ptr, path_len, ret_ptr: i32) -> ()` | |

`kibi:process/process-poll@0.1.0`・`kibi:process/process-async@0.1.0` を p1 で import すると `NOEXEC`。

### 線形メモリ上の配置（Canonical ABI どおり）

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `list<string>` の要素 | `{ ptr @0, len @4 }` | 8 / 4 |
| `tuple<string, string>`（env の要素） | `{ name.ptr @0, name.len @4, value.ptr @8, value.len @12 }` | 16 / 4 |
| `fd-assign` | `{ fd: u32 @0, source.tag: u8 @4, source.parent: u32 @8 }` | 12 / 4 |
| `result<process, error-code>` | `{ tag: u8 @0, payload @4 }` | 8 / 4 |
| `result<option<exit-status>, error-code>` | `{ tag @0, ok: { opt_tag @4, status_tag @8, exited: u32 @12 \| killed: { sig_is_some @12, sig @13 } } \| err: u8 @4 }` | 16 / 4 |
| `result<_, error-code>` | `{ tag: u8 @0, err: u8 @1 }` | 2 / 1 |
| `string`（`get-cwd` の結果） | `{ ptr @0, len @4 }` | 8 / 4 |

C の手書きの宣言は `core/src/main/wasm/common/kibi_process.h`（`_Static_assert` で上の配置を確かめる）。

## 振る舞い（p1）

### spawn

- 子は `Machine.exec` と同じく VFS の実行ファイルから作る（ドライバーは `KernelDrivers.DEFAULT`。子の ABI は実行ファイルの形で決まり、親の ABI と関係ない）。子の `ProcessObject` を親の fd 表に `READ` の権利で入れて、その fd を返す
- fd 表の空きは読み込みの前と、子を表に足すのと同じカーネルロックの中の両方で確かめる（満杯なら子を作らずに `mfile`）。fd は子が動き出す前に入る
- 引数の検査（ゲストの入力で例外を出さない）: 文字列が UTF-8 でなければ `invalid`。`args` 4096 個・`env` 4096 組・`fds` 256 個まで（仮の値。確定は #251）を要素を読む前に確かめる（`args`・`env` は `too-big`、`fds` は `invalid`）。文字列の合計は、中身を読む前に `ProcessArgs.MAX_BYTES` から引いて超えたら `too-big`。`path`・`cwd` は 4096 バイトまで（`name-too-long`）。範囲外は `fault`。`args`・`env`・`fds` の配列の ptr が align 4 に揃っていなければ、長さ 0 でもその list のほかの検査より先にトラップ（ADR 0073 の「エラー」。#430。`env` は `option` が none なら読まないので確かめない）。検査は引数の順（`path` → `args` → `env` → `cwd` → `fds`）なので、先の引数の error-code（`path` が長すぎれば `name-too-long`、`args` が 4096 個を超えれば `too-big`、`option` の判別子が不正なら `invalid`）は、あとの list の align のトラップより先に返り、待たない設定の `again` は引数を読む前に返る。Component Model では全引数の lift がホストの検査より前なので、これらの組み合わせはトラップになる（既知の差。#264 の層で、#616 で揃える）。`option`・`fd-source` の判別子の誤りと、`fds` の fd の重複は `invalid`
- カーネルのエラー（`no-entry`・`access`・`no-exec`・`bad-descriptor`・`not-directory` など）はそのまま写す。プロセスの数の上限は `nfile`
- `cabi_realloc` の中で呼んだらトラップ

### spawn の待ち方

`ExecWait`（読み込みの `CompletableFuture` などを持つ）は ABI 層の**実行時だけの状態**にし、保存される `Process.asyncWait`（`AsyncWait`）には入れない。

- 持ち場所: プロセスのインスタンスごとの `WasiP1KibiProcess.pendingSpawn`。`KernelState` の下には何も足さない
- `P1Wait.UNWIND`（待機点型）で巻き戻せるとき: 引数を読んで確かめ → fd の空きを確かめ → `beginExec` → 読み込みが終わっていなければ `pendingSpawn` に入れて巻き戻す → 巻き直しで着いたら引数を読み直さずにそれを使い、`finishExecWithHandle` で子を作る。読み込みが始めから終わっていれば（共有の読み込みに当たった）、巻き戻さずに子を作る
  - 巻き直しで着いたのに `pendingSpawn` がない（保存からの復元・インスタンスの作り直し）なら、`beginExec` からやり直す。子はまだ作っていないので、二重には作らない
  - 巻き直しかは `WasiP1Suspend.rewound`（実行時だけ）で見分ける
- それ以外（`P1Wait.BLOCK`、巻き戻せないとき — インスタンスを作っている途中）: `execWithHandle`（`ctx.exec` と同じく、読み込みの間は CPU と許可を返す）
- `P1Wait.NONE`: 待たずに `again`
- 待っている間に止められたとき（kill・マシンの停止）: 既存の `ProcessRunner.finishLocked` の後始末（`HostCompletion` を閉じ、読み込みを取り消す）に任せる。`finishExec` を呼ばないので子は作られない

### status・待ち方

- `status` は待たない。`ProcessObject.status` を写す
- 刈り取るのは、終わり方があり、表の `obj.pid` のプロセスの `objectId == obj.id`（pid が使い回された別のプロセスではない）で、その `parentPid` が呼んだプロセスのときだけ（親だけが刈り取る。`fds` で渡されたほかの持ち主は刈り取らない）
- 刈り取った後も、ハンドルが `ProcessObject` の参照を持つので同じ値を返し続ける
- 待つのは `poll_oneoff` の `FD_READ`。`FdIo.pollLocked` が `ProcessObject` の fd を、終わっていれば `Ready(0)`、まだなら `WaitKey(obj.id, EXITED)`（`ProcessLifecycle.onFinishedLocked` が起こす key と同じ）に写す。`FD_WRITE` は権利がないので `bad-descriptor`。`fd_read` は `invalid`

### kill・kill-pid・set-signal-ignored

- `DELIVERED`・`IGNORED`・`ALREADY_EXITED` は ok、`NOT_FOUND` は `no-such-process`、`NOT_PERMITTED`（init）は `not-permitted`
- ハンドルの `kill` は、表の `obj.pid` のプロセスの `objectId == obj.id` のときだけ送る。一致しなければ送らずに ok
- 自分に送って止まるなら、そのまま止まる
- signal の判別子が 0..3 の外なら `invalid`。`set-signal-ignored(kill)` は `invalid`

### ハンドルを閉じたとき

- 閉じ方（`[resource-drop]process`・`fd_close`・`fd_renumber` や `dup-to` での上書き）で振る舞いを変えないよう、カーネルの `FdTable` の `close`・`dup2`・`renumber` に置く。`ProcessObject` を指す項目を外したら `WakeSink.processHandleClosed` → `ProcessLifecycle.onProcessHandleClosedLocked`
- 付け替えるのは、`objectId` が一致し、閉じたのが親で、親の fd 表にまだ `obj.id` を指す項目がない（最後のハンドル）ときだけ。子が終わっていれば刈り取り、動いていれば `parentPid = 0`（親なし）。親なしで終われば、ほかと同じく親のないゾンビになる（ADR 0046）
- プロセスの終わりの `FdTable.closeAll` では呼ばない。同じロックの中で `onFinishedLocked` がすべての子に同じことをする

### get-cwd・set-cwd

- カーネルの cwd（ADR 0332）。p1 の `PWD` は最初の値のまま。SDK の `chdir` は libc の `chdir` と `set-cwd` の両方を呼ぶ（#116）

### 燃料

- 呼び出しごとに `WasiP1Fd.CALL_FUEL`（仮の値。確定は #251）。読み込みの間は燃料を使わない

## p2/p3 への写し方（仮置き）

| `process` の項目 | p1 | p2 | p3 |
| --- | --- | --- | --- |
| resource `process` | ハンドル = カーネルの fd 番号 | Component Model の resource（ハンドル表は #265。中身は同じ `ProcessObject`） | 同じ |
| `spawn` | 止まりうる import | 同期の関数（待ち方は #264 の層の決まりに従う） | 使えるが、`process-async.spawn` を使う |
| 子の fd | `fds` の `parent(n)` | `fds` は `parent(0/1/2)` と `closed` だけ（Q8-A）。ほかは `spawn-with-handles` | 同じ。ほかは `process-async.spawn` |
| 待つ | fd を `poll_oneoff` の `FD_READ` | `process-poll.subscribe` の `pollable`（同じ `EXITED` の key。刈り取らない） | `process-async.wait`（Q7-A。複数の子は subtask を waitable-set で） |
| ハンドルを閉じる | fd を閉じる経路で付け替え | resource の drop も同じ付け替え | 同じ |
| `exit-status` | `proc_exit(u32)` → `exited(n)` | `wasi:cli/exit.exit(ok/err)` → `exited(0/1)`、`exit-with-code(n)` → `exited(n)` | 同じ |
| `get-cwd`・`set-cwd` | カーネルの cwd | 同じ。子の `initial-cwd` は作られたときのカーネルの cwd | 同じ |

- `handle-source` の stream は、その stream の下のカーネルの fd を子の fd に複製する。カーネルの fd を下に持たない stream は `not-supported`
- p3 の `input(stream<u8>)`・`capture` は、カーネルのパイプ（ADR 0059）を1本作り、p3 の層がつなぐ（#266）
- `wasi:*` の版（p2 は `@0.2.3`、p3 は `wasi:filesystem@0.3.0`）は仮（Q9-A）

## テスト

- `WasiP1KibiProcessTest`（`kibi_process.c` を Endive とカーネルで）: p1 の層を通した spawn の振る舞い。「振る舞い（p1）」の「spawn」の検査順（`path` → `args` → `env` → `cwd` → `fds`）の根拠になるもの（#617）
  - `misaligned`: args・env・fds の list の ptr が align に揃っていなければ、長さ 0 でもトラップ（`UNWIND`・`BLOCK`）
  - `misaligned-order`: 先の引数の誤り（`path` の `name-too-long`、`args` の個数の `too-big`、`env` の `option` の判別子の `invalid`）は、後の list（`args`・`env`・`fds`）の ptr が揃っていないトラップより先に error-code で返る。`fds` の `fd-source` の判別子の誤りは、このテストでは確かめない。`env` が none なら、揃っていない `env_ptr` でも確かめない
  - `nowait`・`nowait-misaligned`: `P1Wait.NONE` の `again` は、引数を読む前に返る
  - 検査順を Component Model に揃えるとき（#616）は、`misaligned-order`・`nowait-misaligned` の期待値も合わせて直す
- `GuestCallGuardTest`: `callGuest` の中の `exec`・`execWithHandle` がトラップになる（ADR 0073）

## 保存

- 新しい保存の形はない。fd 表の項目（`HandleEntry(objectId = ProcessObject の id, rights = READ)`）と、既存の `ProcessObject`・`Process.parentPid` だけ
- 読み込みを待っている途中の状態（`ExecWait`）は保存しない。復元の後に巻き直しで着いたら `beginExec` からやり直す
