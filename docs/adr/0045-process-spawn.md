# 0045: プロセスの生成（引数・環境変数・標準入出力つき）

- 状態: 採用
- Issue: #45（task）。前提: ADR 0043（カーネル全体の設計）、0051（VFS の中核）、0058（fd 表と標準入出力）
- 後続: #46（終了待ち・親が終わったときの子）、#48（メモリの合計）、#49（init）、#63・#72・#73（ABI への写し方）、#81・#82（保存）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- どれも `core.kernel` の `internal`。公開 API・ABI・保存のバイト形式は足さない
- プロセスを作る口は2つ。読み込み済みの `Program` から作る `Machine.spawn`（今までどおり）と、VFS の実行ファイルから作る `Machine.exec`。プロセスの中からは `ProcessContext.spawn`・`exec`（親は自分）
- 引数・環境変数・作業ディレクトリ・渡す fd は、作るときに `SpawnOptions` で1回だけ決める（fork はない。posix_spawn に近い）
- `Process` に `args`（`List<String>`）・`env`（`Map<String, String>`。並びを保つ）・`cwd`（`VfsPath`）を足した。どれも番号・文字列だけなので、`KernelState` の下に置ける（`KernelStateIsolationTest`）
- 失敗のうち WASM のプログラムが起こしうるもの（引数・パス・fd）は `FsException(Errno)`。ABI 層が自分の値に直す

## `SpawnOptions`

| 項目 | 既定 | 振る舞い |
| --- | --- | --- |
| `args` | 空 | argv。最初の要素がプログラムの名前。`exec` で空なら、渡したパスの文字列1つ |
| `env` | `null` | `null` なら親の環境変数（親がなければ空）。指定すれば丸ごと置き換える |
| `cwd` | `null` | `null` なら親と同じ（親がなければ `/`。確かめない）。指定すれば、親の作業ディレクトリから辿り、ディレクトリか確かめる（`NOENT`・`NOTDIR`） |
| `stdio` | 引き継ぎ | fd 0/1/2（ADR 0058） |
| `fds` | 空 | fd 3 以降に渡すもの（fd → `StdioSource`）。下の「fd の引き継ぎ」 |

### 引数・環境変数の検査

- 引数・環境変数に NUL があれば `INVAL`。環境変数の名前が空、`=` を含めば `INVAL`
- 合計の大きさの上限は 64 KiB（仮の値。確定は #251）。数え方は WASI p1 の `args_sizes_get`・`environ_sizes_get` と同じ（UTF-8 のバイト数＋終わりの NUL。環境変数は `名前=値`）。超えたら `TOOBIG`（POSIX の `E2BIG`）
- カーネルは文字列で持つ。p1 の `args_get`・`environ_get`、p2 の `wasi:cli/environment` へ写すのは ABI 層（#72・#73）

## 実行ファイル（`Machine.exec`）

1. カーネルロックの中で、パスを解決して読む。相対パスは**親の**作業ディレクトリから辿る（子の `cwd` ではない）。`PATH` からは探さない（シェルの役目）
2. 普通のファイルでなければ `ACCES`（execve と同じ）、16 MiB（仮の値）を超えたら `FBIG`
3. ロックの外で `Program.load`（パース・検証）。読めなければ `NOEXEC`
4. `ProcessDriverFactory.create(program)` で ABI 層がドライバーを作る（p1 か Component Model かを見分けるのは ABI 層。動かせない形なら `NOEXEC` を投げる）
5. `spawn` で表に足す。ファイルを読んでから表に足すまでの間に親が終わっていれば、`spawn` が断る

- 読み込みに使うランタイムは `SchedulerInternals.runtime`（既定は `EndiveRuntime`。テストではダミー）
- 読み込みは重いので、メインスレッドからは呼ばない（#49 の init もプロセスのスレッドかワーカーで呼ぶ）。プロセスの中から呼ぶと、読み込みの間は CPU を持ったまま（燃料は使わない）
- 読み込んだモジュールの共有（同じハッシュの再利用）は、ランタイムコンパイラと合わせて #37 で考える

## fd の引き継ぎ

ADR 0058 で残した「0/1/2 以外の fd を子に渡す方法」は、`SpawnOptions.fds` で明示的に渡す形にした（Unix の「閉じずに残った fd を全部引き継ぐ」形にはしない。close-on-exec の考えが要らず、子が予期しない fd を持たない）。

- 値は標準入出力と同じ `StdioSource`。`Inherit` は親の同じ番号（親がない・閉じていれば渡さない。コンソールにはしない）、`ParentFd(n)` は親の fd n、`Object`・`Console`（出力）・`Closed` も使える
- fd が 3 未満・上限（256）以上なら `BADF`
- WASI p1 の preopen（fd 3 以降のディレクトリ）の並べ方は、これを使って #72・#79 で決める

## 親子関係

- `Process.parentPid`（今まであった）。親がなければ 0
- 親は終わっていないプロセスでなければならない（ゾンビを親にすると `IllegalArgumentException`）
- 子の一覧は `ProcessTable.childrenOf(pid)`（表を走査する。プロセスの上限が小さい（仮 64）ので、子の並びを別に持たない）。`Machine.children`・`ProcessContext.children`
- 親が終わったときの子の扱い（親なしへの付け替え）、終了待ち、刈り取りは ADR 0046（#46）

## 作業ディレクトリ

- `Process.cwd` は VFS の絶対パス。`ProcessContext.changeDirectory`（`chdir`）で変える。ディレクトリでなければ `NOTDIR`、なければ `NOENT`
- 作業ディレクトリのディレクトリが後で消えても追わない（POSIX と同じく、次に辿るときに `NOENT` になる）
- WASI p1 には cwd がない（preopen からの相対）。p1 の libc が使う `PWD` との合わせ方は #332（ADR 0332。p1 の `environ_*` が `PWD=<cwd>` を含める）

## 保存（#81・#82 への申し送り）

- `args`・`env`・`cwd` は文字列だけでできている。書き出す形は #82
- `ModuleHash` は `exec` でも、読んだファイルのバイト列そのもの（挿入があれば挿入の後。#38）

## 決めないこと

- ABI（`args_get`・`environ_get`・`proc_spawn` 相当の `kibi:*` のシステムコール）: #63・#72・#73
- 終了待ち・kill・親が終わったときの子: ADR 0046（#46）
- メモリの合計の上限で生成を断ること: #48
- 仮の値（引数と環境変数の合計 64 KiB、実行ファイル 16 MiB）の確定: #251
