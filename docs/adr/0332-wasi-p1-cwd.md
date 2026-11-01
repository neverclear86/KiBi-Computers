# 0332: WASI p1 の cwd と wasi-libc の `PWD` の合わせ方

- 状態: 採用
- Issue: #332。前提: ADR 0045（`Process.cwd`・`env`）、0071（p1 のパスと preopen）
- 番号は Issue 番号にそろえている

## 背景

カーネルはプロセスごとに作業ディレクトリ（`Process.cwd`。VFS の絶対パス）を持つ（ADR 0045）。WASI p1 には cwd がなく、
カーネルの cwd を読む関数も変える関数もない。

使っている wasi-libc（Zig 0.17.0 同梱）を確かめると:

- cwd は `__wasilibc_cwd`（`getcwd.c` の静的変数。初期値 `"/"`）だけで持つ。`chdir` はゲストの中だけの処理（`fstatat` で確かめて書き換えるだけ）で、ホストには何も伝わらない
- 起動時に `PWD` を読まない
- 相対パスは `__wasilibc_cwd` を前に付けて絶対パスにし、いちばん長く一致する preopen を選ぶ
- 名前 `.` の preopen は接頭辞を落とすと `/` と同じになるので、cwd の代わりにならない

なので、カーネルの cwd をプログラムへ伝える経路は環境変数しかなく、プログラムの側が起動時にそれを読んで `chdir` する必要がある。

## 決定

### 伝え方: p1 の `environ_*` が `PWD=<カーネルの cwd>` を必ず含める

- `environ_sizes_get`・`environ_get` が見せる並びに、`PWD=<カーネルの cwd>` を1つだけ入れる（`WasiP1Basic.p1Environment`）
  - 呼び出し元が `PWD` を渡していれば、その位置のまま値を置き換える。なければ最後に足す（POSIX でもカーネルの cwd が正で、`PWD` はそれに合わせる慣習。ずれた値を見せない）
- 足すのは p1 の層が見せるビューだけ。`Process.env`（保存される。#82）とカーネルの状態は変えない。p2・p3 は `wasi:cli/environment` の `initial-cwd` で渡す（#267・#268）ので、p1 だけの都合として p1 の層に置く
- 値は、最初に `environ_*` を呼んだ時点の `Process.cwd`（正規化した絶対パス）。p1 の中から cwd を変える方法はないので、その後は変わらない
- 環境変数の大きさの上限（64 KiB、ADR 0045）は生成時に `Process.env` だけで数える。`PWD` の分（最大で `4 + 4096 + 1` バイト）は上限の外で、少し超えることがある
- ABI（関数の形）は witx のまま。新しい import は足さない

### 受け取り方: 起動時に `PWD` へ `chdir` する（`core/src/main/wasm/common/kibi_cwd.c`）

- `__attribute__((constructor))` の関数で、`getenv("PWD")` が `/` で始まれば `chdir` する。失敗したら何もしない（cwd は `/` のまま）。`errno` は元に戻す
- `lua.wasm` と、テスト用のプログラム `cwd-wasi` にリンクする。`io.open("相対パス")` などが cwd から辿るようになる
- 起動処理を持たないプログラム（素の wasi-sdk でビルドしたもの）は、今までどおり cwd が `/` になる。`getenv("PWD")` で自分で合わせられる
- **SDK への申し送り**: C（#116）は `kibi_cwd.c` と同じものを入れる。Rust（#114）・Zig（#115）も起動時に `PWD` へ `chdir` する同じ規約に従う

### p1 のプログラムの `chdir` はカーネルの cwd に反映しない

- wasi-libc の `chdir` はホストを呼ばないので、反映するにはカーネルの cwd を変える独自の import（ABI）が要る。ここでは足さない（必要になれば #73・#74）
- 子プロセスの cwd は、プロセス生成の import（#74）で cwd を明示して渡せば足りる（SDK が `getcwd()` を渡す）
- **#74 への申し送り**: `cwd` を省略したときは「親のカーネルの cwd」（ADR 0045 の既定）になる。p1 の親が `chdir` した後の値ではない
- `ProcessContext.changeDirectory` は今のまま残す（Kotlin で書くプログラムと、将来の import のため）

### preopen との関係（ADR 0071）

- 既定の preopen は `/` の1つだけなので、cwd がどこにあっても `chdir(PWD)` と相対パスは必ずどこかの preopen に当たる。cwd ごとに preopen を足さない
- 箱にするために `/` 以外の preopen だけを渡した子で、cwd がその外にあると `chdir` が失敗して `/` のままになり、相対パスは失敗する。見せていない場所には入れない、なのでそのままにする
- 起動後に cwd のディレクトリが消されたときも、`chdir` が失敗して `/` のまま動く

## 結果

- 公開 API・ABI・保存形式の変更はない。変えたのは `WasiP1Basic`（`internal`）の環境変数のビューと、C のプログラムの起動処理だけ
- テスト: `WasiP1BasicTest`（`PWD` の追加・置き換え、件数・大きさ）、`WasiP1CwdProgramTest`（`getcwd`、相対パスの `fopen`・`mkdir`、`chdir` 後の `getcwd`、カーネルの cwd が変わらないこと、cwd が消えたときに `/` で動くこと、Lua の `io.open`）
