# 0071: WASI p1 の path_* と preopen の構成

- 状態: 採用
- Issue: #71（task）。前提: ADR 0051（VFS の中核）、0058（fd 表と標準入出力）、0070（p1 の `fd_*`）、0261（p1 の層を直接実装する）
- 後続: #72（`poll_oneoff`、cwd と wasi-libc の `PWD`）、#74（子プロセスへの preopen の渡し方）、#54（`/dev`）、#267・#268（p2・p3 の `wasi:filesystem`）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- p1 の `path_*` 10 個は `core.wasi.p1.WasiP1Path`（`internal`）。関数の形は WASI p1 の witx のまま（独自の ABI は足さない）。公開 API・保存のバイト形式は足さない
- 辿り方: 基点の fd は「ディレクトリの `OpenFile`」。`VfsPath.resolveBeneath(開いたときのパス, 文字列)` で解き、`Vfs` のパスの操作を呼ぶ。絶対パスと、基点の外へ出る `..` は `NOTCAPABLE`
- **preopen は fd 3 の `/` の1つだけ。** 起動の手順（`BootSequence`）が init に `SpawnOptions.ROOT_PREOPEN` を渡す
- 燃料は呼び出しごとの `CALL_FUEL` だけ（細かい計算は足さない。確定は #251）

## 共通の規則

- パスの文字列はゲストのメモリの `(ptr, len)`。NUL 終端なし。UTF-8 として厳密に読み、壊れていれば `ILSEQ`。`len` が `VfsPath.MAX_PATH_BYTES` を超えれば、メモリを読まずに `NAMETOOLONG`
- 範囲外のポインタは、何かを変える前に `FAULT`（`fd_*` と同じ）
- 基点の fd が開いていなければ `BADF`、ディレクトリの `OpenFile` でなければ `NOTDIR`
- `lookupflags` の `SYMLINK_FOLLOW` は受け取って無視する（シンボリックリンクはない。ADR 0051）。知らないビットは `INVAL`
- 末尾の `/` は「ディレクトリでなければ `NOTDIR`」
- 呼び出しはすべてカーネルロックの中。失敗は `FsException` の errno

## 関数ごとの振る舞い

| 関数 | 振る舞い |
| --- | --- |
| `path_open` | `oflags` → `OpenOptions` の `create`・`directory`・`exclusive`・`truncate`。`rights_base` の `FD_READ` → 読む、`FD_WRITE` → 書く（ほかの権利と `rights_inheriting` は見ない。守らせるのは `HandleRights` だけ。ADR 0070 と同じ）。`fdflags`: `APPEND` → 追記、`NONBLOCK` → fd のフラグ、`DSYNC`・`RSYNC`・`SYNC` は `NOTSUP`（`fd_fdstat_set_flags` とそろえる）、知らないビットは `INVAL`。fd 表が満杯なら、開く前に `MFILE`（`O_TRUNC` で中身を消してから失敗しないように） |
| `path_create_directory` | `Vfs.createDirectory`。最後の名前が `.`・`..` なら `EXIST` |
| `path_remove_directory` | `Vfs.removeDirectory`。最後の名前が `.` なら `INVAL`、`..` なら `NOTEMPTY`（Linux と同じ） |
| `path_unlink_file` | `Vfs.removeFile`。ディレクトリは `ISDIR`。末尾の `/` があり、普通のファイルなら `NOTDIR` |
| `path_rename` | 2つの fd から解いて `Vfs.rename`（マウントをまたげば `XDEV`、マウントの位置は `BUSY`）。末尾の `/` があれば、元がディレクトリでなければ `NOTDIR`。最後の名前が `.`・`..` なら `INVAL`。置き換えの規則は `FileSystem.rename`（POSIX と同じ）に任せる |
| `path_filestat_get` | `Vfs.stat(path)` を `filestat` で書く（`fd_filestat_get` と同じ書き出し） |
| `path_filestat_set_times` | `fstflags` の解釈は `fd_filestat_set_times` と共有（`*_NOW` はマシンの実時間）。`Vfs.setTimes(path, …)` を足した（規則は fd 版と同じ。読み取り専用のマウントとマウントの祖先は `ROFS`。何も変えないなら、あるか確かめるだけ） |
| `path_readlink` | リンクはないので、パスがあれば `INVAL`（POSIX の `readlink` と同じ）、なければ stat のエラー |
| `path_symlink`・`path_link` | 基点の fd を確かめてから `NOTSUP`（ハードリンク・シンボリックリンクは扱わない。ADR 0051） |

## preopen の構成

- fd 3 に `/`（読む権利のディレクトリの `OpenFile`）を1つ置く。`fd_prestat_get` は ADR 0070 のとおり「ディレクトリの fd を preopen として答える」ので、名前は `/` になる
- 1つにする理由: マウント（ディスク・`/tmp`・`/dev`）はすべて `/` の下に見えるので、ほかを並べなくても全体を辿れる。wasi-libc は相対パスを自分の cwd（初めは `/`）から絶対パスにしてから、いちばん長く一致する preopen を選ぶので、`/` があれば相対パスも動く。マウントの位置ごとに並べると、抜き差し（#56）で並びが変わる
- 渡し方: `StdioSource.Directory(path)` を足し、`SpawnOptions.fds` で渡す。相対パスは親の作業ディレクトリ（親がなければ `/`）から辿る。`/` 以外を渡せば、そのディレクトリの下だけを見せる箱にできる。`SpawnOptions.fds` の既定は空のまま（子にどう渡すかは #74）
- `Machine.spawn` での順番:
  1. fd の割り当てを決める（`resolveFdsLocked`）。`Directory` は `Vfs.open(directory = true)` で `OpenFile` を作るだけで、オブジェクトの表には足さない（ない・ディレクトリでないなら、ここで `NOENT`・`NOTDIR`）。`OpenFile` はファイルシステムを参照せず、閉じる処理も要らないので、表に足す前に捨てても何も漏れない
  2. ブロッキング型の枠を予約する。ここで失敗しても、表には何も足していない
  3. pid・プロセス・fd 表を作る
  4. 割り当ての直前に `Directory` のオブジェクトを表に足し（参照 1）、fd に割り当てる（参照 2）。最後に `finally` で作ったときの参照を必ず返す。成功すれば fd の参照 1 つが残り、途中で失敗すれば 0 になって表から消える

## 決めないこと

- 子プロセスへの preopen の渡し方の既定: #74
- cwd と wasi-libc の `PWD` の合わせ方: #72（ADR 0045）
- p2・p3 の `wasi:filesystem` への写し方: #267・#268
- デバイスファイル（`/dev`）の開き方: #54
