# 0453: マウントとディスクの一覧と容量（kibi:fs の list-mounts・list-disks）

- 状態: 採用
- Issue: #453（task。`df`・`mount` のためのシステムコール）。前提: ADR 0073（`kibi:*` の WIT と p1 の import の導き方）、0056（ディスクの抜き差しと `mount`）、0052（ディスクのリース）、0452（一覧の作り方の型）
- 後続: #454（`df`・`mount` コマンド）、#251（仮の値の確定）、#262〜#268（p2・p3 の層）
- ABI（Q1〜Q6）は、#453 のプランへのオーナーの回答（すべて推奨どおり）で決めた

## 決めたこと（要約）

- 既存の `kibi:fs/mounts@0.1.0` の `interface mounts` に2本を足す（版は変えない。`-poll`・`-async` の版は置かない）
  - `list-mounts: func() -> list<mount-info>`（マウントを足した順）
  - `list-disks: func() -> list<disk-info>`（スロットの順＝接続層が渡す順）
  - どちらも `result` で包まず、待たない
- `enum fs-kind { disk, image, tmp, dev }`
- `record fs-usage { total-bytes: u64, used-bytes: u64, available-bytes: u64, total-entries: option<u32>, used-entries: u32 }`（`none` は上限なし。項目はどれも根を除く）
- `record mount-info { point: string, source: string, kind: fs-kind, read-only: bool, id: u32, usage: option<fs-usage> }`
- `record disk-info { source: string, capacity-bytes: u64, max-entries: u32, mount-point: option<string> }`
- パスを受ける statvfs 相当の関数は足さない（Q2）。`df <パス>` は `stat` の `dev` が `mount-info.id` と等しいものを引く
- 公開 API（`pro.ikili.kibicomputers.api`）と保存形式（`KernelState`・`MountEntry`・KERNEL 節）は変えない。新しい型はすべて `internal`

## 数の意味

| 種類 | total-bytes | used-bytes | available-bytes | total-entries | used-entries |
| --- | --- | --- | --- | --- | --- |
| `disk` | `DiskQuota.maxBytes` | `usedBytes`（項目ごとの `ENTRY_COST` 256 バイトを含む） | `max(0, 全体 − 使用)` | `some(maxEntries)` | `usedEntries` |
| `tmp` | マシンの RAM（`MemoryAccount.limitBytes`） | tmpfs の `usedBytes`（`NODE_COST`・名前・4 KiB の塊を含む） | `max(0, 上限 − RAM の使用)` | `none` | `nodeCount − 1` |
| `image` | 中身の合計 | 全体と同じ | 0 | `some(entryCount − 1)` | `entryCount − 1` |
| `dev` | 0 | 0 | 0 | `some(0)` | 0 |

- 使用量は管理の分を含むので、`du` の合計とは合わない（`NOSPC` の判定と同じ数え方）
- `tmp` の空きは目安。RAM はプロセスの線形メモリとも分け合い、親（サーバー全体）の勘定で断られることもあるので、書けることは保証しない
- 外で書き足されたディスクは使用量が容量を超えることがある。空きは 0 で止める
- ディスクの `usedBytes` と `usedEntries` は別々の `@Volatile` なので、書いている途中なら一瞬ずれる（`lease.lock` を取ってまでそろえない）
- `dev` の `total-entries` は `some(0)`（`none` は「上限なし」の意味で `tmp` だけが使う）
- 容量は `FileSystem.usage(): FsUsage?`（既定 `null`）で FS ごとに返す。呼び手に `is DiskFileSystem` の分岐を散らさない。`ImageFileSystem.totalBytes` は作るときに1回だけ数える（同梱イメージは全台で共有する）

## 各欄の決め方

- `kind` は FS の型からではなく source から決める（`KibiFs.kindOf`）。`disk:image:` → `image`、ほかの `disk:` → `disk`、`tmp` → `tmp`、`dev` → `dev`。知らない source は `disk` に倒す（ホスト関数で例外を投げるとゲストがトラップするため）。Machine が作る source はこの4種だけ
- `read-only` = マウントの `readOnly` ‖ FS の `readOnly`（`Vfs` の `Location.readOnly` と同じ）。FS がつながっていなければマウントの印だけ
- `id` = `MountEntry.id`（p1 の `filestat.dev` と同じ値）。マウントの祖先の合成ディレクトリは `dev` 0 で、どの `id` にも当たらない
- `usage` が `none` になるのは、FS がまだつながっていない（起動・復元の途中、`Vfs.attach` の前）、`usage()` が `null`（テスト用の FS など）、一覧を作る間に外れた、のどれか
- `disk-info` は挿し込みの実体（`ResolvedDisk`）から作り、ディスクは開かない（開くとリースの世代が進み、ほかの持ち主が `IO` になる。ADR 0052）。マウントしていないディスクの使用量は出さない（Q6）
- `mount-point` は `kernel.mounts.bySource("disk:" + 識別子)` の位置（`Machine.mountPointOfDisk` と同じ）。開いている途中のディスクは `none` だが、そのときの `mount` は `busy`
- 2つの呼び出しの間に抜き差しがあると、結果が食い違うことがある（害はない）

## ロックと順序

- `Machine.listMounts`: カーネルロックの中でマウントの表と FS の参照を写し（`listMountsLocked`。表の実体は外へ出さない）、ロックを放してから各 FS の `usage()` を読む（`usage()` はカーネルロックの中から呼んでよい5つには入らない。`TmpFileSystem.nodeCount` は自分のモニターを取る）。読んだ後にもう一度短くロックを取り、その間に外れた（ID の項目か FS の参照が変わった）マウントの `usage` を `none` にする
- `Machine.listDisks`: カーネルロックの中で挿し込みの表を写すだけ
- p1 の層は、一覧を作ってから `cabi_realloc` を呼ぶ（ロックは持たない）。`cabi_realloc` の中から `list-mounts` をもう一度呼んでもトラップ・デッドロックしない
- `disksChangedLocked` は、同じ組み合わせのまま並び（スロット）だけ変わったときも、挿し込みを使い回して表の並びを直す（イベントは出さず、外さない）。以前は集合だけを比べていて、`list-disks` の並びが古いまま残った

## p1 の配置（ADR 0073 の規則）

関数はどちらも `(ret_ptr: i32) -> ()`。`ret_ptr` に `(list_ptr, list_len)`（8 / 4）を書く。`needsRealloc = true`、`WAIT_IMPORTS` には入れない。

- `mount-info`（72 / 8）: `point` @0/@4、`source` @8/@12、`kind` u8 @16、`read-only` @17、`id` @20、`usage` の判別子 @24・中身 @32
- `fs-usage`（40 / 8）: `total` @0、`used` @8、`available` @16、`total-entries` の判別子 @24・値 @28、`used-entries` @32
- `disk-info`（32 / 8）: `source` @0/@4、`capacity-bytes` @8、`max-entries` @16、`mount-point` の判別子 @20・ptr @24・len @28
- `cabi_realloc` は、外側の配列（align 8）→ 要素ごとに欄の順（`point` → `source` / `source` → `mount-point`。`none` なら呼ばない）。0 件・0 バイトでも呼ぶ。詰め物と使わない payload は 0
- u32 を超える項目の数は u32 の最大で止める（今の上限では起きない）
- C の宣言は `core/src/main/wasm/common/kibi_fs.h`（`_Static_assert` で大きさとオフセットを固める）

## 最悪の量と燃料

- 呼ばれたときだけ計算する。tick ごとの処理・キャッシュ・スレッドは足さない
- ロックの中は O(マウントの数＋ディスクの数) の写しだけ（どちらも 10 件程度）。`usage()` は I/O なしの O(1)
- 燃料は呼び出しごとの `CALL_FUEL` に加えて、件数 × `KibiFs.MOUNT_LIST_FUEL_PER_ENTRY`（10。仮の値、確定は #251）。一覧を呼び続けるゲストを件数に比例して抑える

## #454 への申し送り（#454 で済んだ。`df`・`mount` は ADR 0103）

- `df`・`mount` は `cabi_realloc` を export する
- `du` と数が合わない（管理の分を含む）。`tmp` の空きは目安
- `df <パス>` は `stat().dev == id` で引く
- 関連して OPEN の #607（`recount` が見せる項目だけを数える）は使用量の数え方に触れるが、ABI の形は変えない
