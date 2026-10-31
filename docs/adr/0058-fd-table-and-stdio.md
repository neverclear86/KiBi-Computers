# 0058: ファイルディスクリプタ表と標準入出力

- 状態: 採用
- Issue: #58（task）。前提: ADR 0043（カーネル全体の設計）、0044（MC 層との境界）、0051（VFS の中核）
- 後続: #45（プロセスの生成）、#59（パイプ）、#60（TTY）、#72・#79（WASI p1 の写し方）、#265（resource ハンドル）、#81・#82（保存）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- コードは `core.kernel.fd`。どれも `internal`。公開 API・ABI・保存のバイト形式は足さない
- fd 表は `Process.handles`（`HandleTable<HandleEntry>`）そのもの。操作は `FdTable` を通す（p1・p2・p3 のどの ABI 層も同じ）
- fd はオブジェクト表の参照を1つ持つ。複製は同じオブジェクトを指す（`OpenFile` の位置、ストリームの中身を共有する）
- `HandleEntry` に `rights`（`READ`・`WRITE`）を足した。カーネルが読み書きのときに確かめる。`flags` の意味は ABI 層が決める
- 標準入出力（0/1/2）は、プロセスを作るときに `SpawnOptions.stdio`（`StdioSpec`）で割り当てる。既定は親から引き継ぎ、親がなければコンソール
- コンソールは、マシンに1組の `ByteStream`（入力・出力）。出力は tick ごとに `MachineHost.onConsoleOutput` へ配り、いっぱいなら書き手が待つ（ADR 0044 の「流量の制御は ByteStream で」）

## fd 表の操作（`FdTable`）

| 操作 | 振る舞い |
| --- | --- |
| `open(obj)` / `install(id)` | いちばん小さい空き番号に入れる。満杯なら `MFILE`（`open` は表に足さない。`install` は参照を呼び出し側に残す） |
| `dup(fd)` | いちばん小さい空き番号に複製。権利とフラグも写す。参照を1つ増やす |
| `dup2(from, to)` | `to` が開いていれば閉じてから複製。同じ番号なら確かめるだけ。番号を増やさないので上限でもできる |
| `renumber(from, to)` | WASI p1 の `fd_renumber`。`to` を閉じてから移し、`from` は閉じる |
| `close(fd)` / `closeAll()` | 参照を返す。最後の参照なら `onLastClose` |

- 開いていない fd・範囲外（負、上限以上）は `BADF`。失敗したときは表を変えない
- エラーは VFS と同じ `FsException(Errno)`（`Errno` は WASI p1 の値）
- fd の上限はハンドル表の上限（`DEFAULT_HANDLE_LIMIT` = 256。仮の値。確定は #251）
- **プロセスが終わったら（EXITED・CRASHED・KILLED のどれでも）、すべての fd を閉じる。** ゾンビは fd を持たない（パイプの片端を持ったまま終わった子が、相手を待たせ続けないため）
- fd 3 以降の preopen（WASI p1）の並びは決めない（#72・#79）

## 読み書き（`FdIo`）

- fd の指すオブジェクトの種類で振り分ける: `ByteStream` と `OpenFile`。ほかの種類は `INVAL`
- 結果は `IoResult`（`Bytes`・`WouldBlock(key)`・`Eof`・`Error(errno)`）。ABI 層が自分の値に直す
- 待たない版（`read`・`write`）と、`BLOCKING` 用の待つ版（`readBlocking`・`writeBlocking`。`ctx.block` で待つ）。`WAIT_POINT` の ABI 層は `WouldBlock` の key で `StepResult.Wait` を返す
- 書く版は一部だけ書けることがある（全部書くまで繰り返すかは ABI 層が決める）
- fd の権利がなければ `BADF`。`OpenFile` は開いたときの権利も VFS が確かめる。閉じたストリームへの書き込みは `PIPE`。バッファの範囲外は `FAULT`
- 燃料の課金はしない（ABI 層が `ctx.chargeFuel` で行う）

## 標準入出力の割り当て（`StdioSpec`）

`StdioSource` は fd ごとに次のどれか。

| 値 | 割り当て |
| --- | --- |
| `Inherit`（既定） | 親の同じ番号の fd。親がなければコンソール。親のその fd が閉じていれば閉じたまま |
| `ParentFd(fd)` | 親の fd `fd`（開いていなければ `BADF`。プロセスは作らない） |
| `Object(id, rights)` | オブジェクト `id`（パイプの片端など。#59） |
| `Console` | コンソール（入力は `READ` で fd 0、出力は `WRITE` で fd 1・2） |
| `Closed` | 割り当てない |

- 引き継ぐのは 0/1/2 だけ（fork はない）。fd 3 以降は `SpawnOptions.fds` で明示して渡す（ADR 0045）
- 割り当ては `Machine.spawn` の中で、ほかの確認の前に解決する。失敗したら何も変えない

## コンソール

- `KernelState.consoleInputId`・`consoleOutputId`（最初に使うときに作る。カーネルが参照を1つずつ持ち、閉じない）
- 出力（16 KiB。仮の値）: 書くと `Machine.wakeSink` が印を付け、次の `tick()` で、たまった分を取り出して `onConsoleOutput` に渡す（書き手を起こす）。いっぱいなら書き手は `WouldBlock` で待つ。1 tick に配る量はバッファの大きさまで
- 入力（4 KiB。仮の値）: `Machine.offerConsoleInput`（internal）で足す。接続層からキー入力を渡す口と行編集は #60
- たまった出力・入力はカーネルの状態なので、保存の対象になる
- `Machine.wakeSink` は `WaitRegistry` を包み、コンソールの出力の READABLE を見る。カーネルの中でオブジェクトの状態を変えるときは、これを渡す
- TTY（#60）ができたら、`Console` の中身を TTY のオブジェクトに置き換える

## 保存（#81・#82 への申し送り）

- `HandleEntry`（オブジェクトID・フラグ・権利）と、コンソールの2つの ID は、番号だけでできている（`KernelStateIsolationTest`）
- 復元のときは、`Machine.wakeSink` が ID を見るだけなので、作り直すものはない

## 決めないこと

- WASI p1 の `fd_*` への写し方、preopen の並び、`fdflags`・rights の写し方: #72・#79
- Component Model の resource ハンドル表: #265
- パイプ: #59。TTY と接続層からの入力: #60
- 子に 0/1/2 以外の fd を渡す方法、引数・環境変数: #45（ADR 0045 で決めた）
- 仮の値（fd の上限 256、コンソールの入力 4 KiB・出力 16 KiB）の確定: #251
