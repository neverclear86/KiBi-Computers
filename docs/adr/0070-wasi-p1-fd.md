# 0070: WASI p1 の fd_*

- 状態: 採用
- Issue: #70（task）。前提: ADR 0051（VFS の中核）、0058（fd 表と標準入出力）、0261（p1 の層を直接実装する）
- 後続: #69（args・clock・proc_exit など）、#71（`path_*` と preopen の構成）、#72（`poll_oneoff`）、#59（パイプ）、#54（`/dev`）、#79（仕様書）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- p1 の層は `core.wasi.p1`（どれも `internal`）。公開 API・保存のバイト形式は足さない。p1 の関数の形は WASI p1 の witx のまま（独自の ABI は足さない）
- `WasiP1.register(ctx, builder, module, blocking)` がホスト関数を登録する。今あるのは `fd_*` の 21 個。まだない p1 の関数をモジュールが import していれば、`NOSYS` を返す仮の関数をつなぐ（リンクが通るように）
- `WasiP1Driver` は `_start` を `BLOCKING` で呼ぶだけのドライバー（`proc_exit` と終了コードは #69）
- fd 番号はカーネルの fd 番号そのもの（ADR 0261 の方針）。読み書きは `FdIo`、表の操作は `FdTable`、ファイルは `Vfs` を通す

## 関数ごとの振る舞い

| 関数 | 振る舞い |
| --- | --- |
| `fd_read`・`fd_write` | iovec をまとめて1回の読み書きにする（合わせて 1 MiB まで。超える分は一部だけの読み書き）。待つ fd では、読みは少しでも読めるまで、書きは全部書くまで待つ。待たない fd で何もできなければ `AGAIN`。読みの EOF は 0 バイト |
| `fd_pread`・`fd_pwrite` | 位置を変えない。ストリームは `SPIPE` |
| `fd_seek`・`fd_tell` | `Vfs.seek`。ストリームは `SPIPE`、ディレクトリは `ISDIR`、知らない whence は `INVAL` |
| `fd_close`・`fd_renumber` | `FdTable.close`・`renumber`。`renumber` は移す先が閉じていてもよい（範囲内なら） |
| `fd_fdstat_get` | 種類・`APPEND`/`NONBLOCK`・権利（下の表） |
| `fd_fdstat_set_flags` | `APPEND`（`OpenFile.append`）と `NONBLOCK`（fd のフラグ）を変える。`DSYNC`・`RSYNC`・`SYNC` は `NOTSUP`、知らないビットは `INVAL` |
| `fd_fdstat_set_rights` | fd を確かめて `NOTSUP`（p1 の権利は持たない） |
| `fd_filestat_get` | `Vfs.stat`。ストリームは種類と `nlink = 1` だけ |
| `fd_filestat_set_size` | `Vfs.setSize`。書けない fd は `BADF`、ストリームは `INVAL` |
| `fd_filestat_set_times` | `Vfs.setTimes`（`FileSystem.setTimes` を足した。既定は `NOTSUP`）。`*_NOW` はマシンの実時間。`ATIM` と `ATIM_NOW`（`MTIM` も）を一緒に使うと `INVAL` |
| `fd_advise` | 確かめるだけ（中身には効かない）。ストリームは `SPIPE` |
| `fd_allocate` | `offset + len` まで大きさを広げる（縮めない） |
| `fd_sync`・`fd_datasync` | `Vfs.sync`。ストリームは `INVAL` |
| `fd_readdir` | `.`・`..` の後に `Vfs.readDirectory`（名前の順）。cookie は並びの番号で、dirent の `d_next` は番号 + 1。入りきらない最後の dirent は途中で切る（`bufused == buf_len`。wasi-libc はバッファを広げて読み直す） |
| `fd_prestat_get`・`fd_prestat_dir_name` | ディレクトリの fd を preopen として答える（下の「preopen」） |

- ゲストのメモリの範囲は、読み書きの前にすべて確かめて `FAULT` にする（ストリームから読んだ後に書き込めずにデータを失うことがないように）
- エラーは `FsException` の `Errno`（値は p1 の errno と同じ）

### 種類と権利（`fd_fdstat_get`）

| fd | filetype | rights_base | rights_inheriting |
| --- | --- | --- | --- |
| 普通のファイル | `REGULAR_FILE` | seek・tell・sync・advise・filestat_get など＋読む fd なら `FD_READ`、書く fd なら `FD_WRITE`・`ALLOCATE`・`FILESTAT_SET_SIZE` | 0 |
| ディレクトリ | `DIRECTORY` | `path_*`・`readdir`・filestat など | すべて |
| コンソールのストリーム | `CHARACTER_DEVICE` | `FD_READ`/`FD_WRITE`（fd の権利で）・poll・filestat_get。`SEEK`・`TELL` は持たない | 0 |
| ほかのストリーム（パイプなど） | `UNKNOWN` | 同上 | 0 |

- 権利は fd ごとに持たず、種類と fd の読み書きの権利（`HandleRights`）から毎回作る。p1 の権利は p2 で無くなったので、守らせるのはカーネルの `HandleRights` だけ
- ディレクトリの `rights_inheriting` をすべてにするのは、wasi-libc の `openat` がこれで開くファイルの権利を絞るため
- コンソールを `CHARACTER_DEVICE` にし、ストリームに `SEEK`・`TELL` を付けないので、wasi-libc の `isatty` はコンソールだけで真になる

### 待つか

- `BLOCKING` のプロセス（`WasiP1Driver`）で、fd に `NONBLOCK` がなければ、ストリームは `ctx.block` で待つ
- `NONBLOCK` は `HandleEntry.flags` に p1 の `fdflags` のビット（4）で持つ（flags の意味は ABI 層が決める。ADR 0058）。POSIX と違い、fd ごと（dup した相手と共有しない）
- `WAIT_POINT` のプロセスは待たずに `AGAIN`（p1 の reactor をどう待たせるかは #63・#72）

### preopen

- `fd_prestat_get` は、**ディレクトリの `OpenFile` を指す fd を preopen として答える**。名前は開いたときの VFS の絶対パス（`/`、`/home` など）。ほかの fd は `BADF`
- フラグで印を付ける形にしなかったのは、子へ fd を渡す `SpawnOptions.fds` でディレクトリを渡せば、そのまま子の preopen になるため（保存する状態も増えない）。wasi-libc は fd 3 から `BADF` が返るまでを preopen として読むので、preopen は fd 3 から詰めて並べる
- どのディレクトリをどの fd に並べるか（preopen の構成）は #71 で決める

### 燃料（仮の値。確定は #251）

- 呼び出しごとに 100、読み書きしたバイト 64 ごとに 1（`WasiP1Fd.CALL_FUEL`・`BYTES_PER_FUEL`）

## 保存（#81・#82 への申し送り）

- 増えた状態は `HandleEntry.flags` の `NONBLOCK` のビットと、`OpenFile.append` が変えられるようになったことだけ。どちらも番号・真偽値
- `fd_readdir` の cookie は並びの番号なので、保存をまたいでも、そのままの意味で続きを読める（間に中身が変われば飛ぶ・重なることがある。POSIX の `readdir` と同じ）

## 確かめたこと

- `WasiP1FdTest`: ダミーのモジュールから import を呼び、メモリの中身で確かめる（`NONBLOCK`・`AGAIN`、範囲外の `FAULT`、待つ書き込み、preopen、readdir と cookie、権利、`NOSYS`）
- `WasiP1FdProgramTest`: wasi-libc でビルドした `core/src/test/wasm/fd_wasi.c` を Endive とカーネルの上で動かす（標準入力で待つ、`read`/`write`/`lseek`/`pread`/`pwrite`/`ftruncate`/`fsync`/`fcntl(O_APPEND)`/`fstat`/`fdopendir`+`readdir`、`__wasi_fd_*` の直接の呼び出し）

## 決めないこと

- `poll_oneoff`: #72。args・clock・`proc_exit`: #69（`path_*`・preopen の構成・`path_open` の `DSYNC` などは #71 で決めた。ADR 0071）
- デバイスファイル（`/dev/null` など）の種類と読み書き: #54。パイプ: #59
