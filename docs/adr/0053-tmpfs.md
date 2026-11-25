# 0053: `/tmp`（メモリ上のファイルシステム）

- 状態: 採用
- Issue: #53（task）。前提: ADR 0051（VFS の中核）、#39（RAM の上限）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- コードは `core.kernel.vfs.TmpFileSystem`（`internal`）。公開 API には何も足さない
- 起動のたびに空の `/tmp` を作り、根のディスクの後に `/tmp` にマウントする（`source = "tmp"`）。根のディスクに `/tmp` がなくてもよい（マウントの位置はディレクトリとして見える。ADR 0051）。根の `/tmp` がディレクトリ以外のものなら、マウントせずに `kibi: /tmp not mounted (NOTDIR)` をコンソールに書いて起動を続ける
- 中身は**コンピューターの RAM（`MemoryAccount`）から確保**し、プロセスの線形メモリと同じ枠で数える。RAM が足りなければ `NOSPC`（書けた分があれば短い書き込み）。逆に `/tmp` で RAM が埋まっていれば、`memory.grow` やプロセスの作成が失敗する
- 次の起動で外すとき（`Vfs.unmount` → `FileSystem.onUnmounted`）に中身を捨てて RAM を返す

## RAM の数え方

`MemoryAccount` を、ページ数ではなくバイト数で数える形にした（上限は RAM のページ数 × 64 KiB のまま）。線形メモリは `tryReserve(pages)`（ページ × 64 KiB）、`/tmp` は `tryReserveBytes(bytes)`。どちらも同じ CAS のカウンターで、カーネルロックは要らない。`usedPages` は線形メモリの分だけを数える。

`/tmp` が RAM に数えるもの（どれも仮の値。確定は #251）:

| もの | 数える量 |
| --- | --- |
| ファイル・ディレクトリ1つ | 256 バイト（`NODE_COST`）＋名前の UTF-8 のバイト数。根は数えない |
| 普通のファイルの中身 | 4 KiB（`CHUNK_BYTES`）の塊ごと。書いたことのない塊（穴）は数えない |

- 中身は塊の `TreeMap`（塊の番号 → 4 KiB の配列）。大きなファイルに書き足しても配列を作り直さない。伸ばしただけの所は疎で、読むと 0
- 縮めると、残らない塊をすぐ返す。端の塊は残し、切った後ろを 0 にする（後で伸ばしたときに前の中身が見えないように）
- 名前の変更は新しい名前の分を先に確保し、足りなければ何も変えない（`NOSPC`）。置き換えたファイルの分は返す

## node と、消したファイル

- node は 1（根）から順に割り当て、使い回さない。名前を変えても変わらない
- 消したファイルは、すぐに中身を返す。開いたままでも、その後の操作は `STALE`。FS には開く・閉じるが伝わらない（ADR 0051）ので、最後に閉じる時まで中身を残すには参照の数を VFS から渡す仕組みが要る。RAM を確実に返すほうを選んだ
- POSIX の規則（`rename` の置き換え、`ISDIR`・`NOTDIR`・`NOTEMPTY`、根は `BUSY`）は `TestMemoryFileSystem` と同じ

## スタックの余裕（#883）

- 変える操作（`createFile`・`createDirectory`・`removeFile`・`removeDirectory`・`rename`・`write`・`append`・`setSize`・`allocate`）は、入口で、モニターを取る前・何も変える前に `StackHeadroom.ensure(TMPFS_FRAMES)`（32 段）を 1 回呼ぶ。足りなければ何も変えずに `StackOverflowError` を投げ、プロセスだけが `call stack exhausted` で終わる（ADR 0043）
- 理由: ゲストのホストの関数の本体（VFS の操作）の深さから呼ばれ、中で RAM の勘定（マシン → サーバー全体の別々の CAS）・`usedBytes`・木を順に変える。途中で尽きると、勘定と `usedBytes` がずれる（外すときに返す量が合わず、マシン・サーバー全体に残る）か、木が半端になる（`size` の外の塊・根から辿れない node は `TMPFS` 節が読めず、冷えた起動になる）
- 返す側（`releaseRam`・`onUnmounted`）では確かめない（投げると返すことが飛ばされる）。同じ深さで `catch` して戻さない（また溢れる）。数える順を入れ替えない（逆向きのずれになるだけ）
- `write` は塊ごとでなく入口の 1 回（塊ごとだと、塊を足した後・`size` を直す前の窓が残り、費用も塊の数だけかかる）。`append`・`allocate` は中身（`writeLocked`・`setSizeLocked`）を呼び、2 回払わない
- 読むだけの操作と `setTimes` は確かめない（勘定にも木の形にも触らない）。`onUnmounted` は浅いところ（`unmountTmpLocked`・`MachineRestore.discard`）からだけ呼ばれ、ゲストの `unmount` は `/tmp` を `BUSY` で断る
- `restore`（起動用のスレッドの浅いところ）は、数えた後に木を組む途中で失敗したら、同じ深さで数えた分を返して投げ直す（fs を呼び手に返せず、`onUnmounted` でも返らないため）
- 確かめは `TmpFileSystemStackExhaustionTest`（底に近い深さのすべて）と `TmpFileSystemStackExhaustionKernelTest`（カーネルのプロセスを通す）。JDK 17・JIT に加えて、`-Pkibi.stackHeadroom=true` の冷えた道（`probe` だけ JIT、`/tmp` の中身はインタプリタ）・`-Xint`・JDK 21（どれも Windows）でも、確かめが通らない深さで断られなかった回と食い違いは 0 だった（`StackHeadroom` の KDoc）。費用は 1 回約 0.2 µs（1 バイトの `write` が約 0.04 µs → 約 0.25 µs）。待っているだけの台には費用がない

## 時刻

- 時刻はマシンの実時間（`MachineClock.realtimeNanos`）
- 作ったときに 3 つとも今。書き込み・大きさの変更で mtime・ctime、ディレクトリは中身が変わると mtime・ctime。`setTimes` は指定のものと ctime
- 読むたびには atime を変えない（`noatime` 相当。読むたびの時計の呼び出しを省く）

## 決めないこと

- 保存（#82）: 今は実行時だけ。マウントの表には `source = "tmp"` が残るので、復元のときに `TmpFileSystem` を作り直して `Vfs.attach` でつなぐ。中身を保存するか（電源を切ったら消える RAM として扱うか、待ちの点での保存 #30 と一緒に残すか）と、そのバイト形式は #82 で決める。node は使い回さないので、保存すれば開いたファイルは同じファイルを指せる
  - 追記（#82）: 待機点保存と一緒に `TMPFS` 節に保存する（電源を入れたままのマシンでは残る）。形式は ADR 0082
- 停止（`stop()`）の後も、次の起動まで `/tmp` は残る（RAM の分も）。根のディスクのマウントと同じ扱い
  - 追記: #362 で「最後のプロセスが終わったら外して RAM を返す」に変わった
- `/tmp` だけの容量の上限（Linux の `tmpfs` の `size=`）: 置かない。RAM 全体が上限
- 消費電力（RF）は数えない（後で決める）
