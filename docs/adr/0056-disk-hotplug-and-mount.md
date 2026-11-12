# 0056: ディスクの抜き差しと、マウント・アンマウントのシステムコール

- 状態: 採用
- Issue: #56（task）。前提: ADR 0049（起動の流れ）、0051（VFS の中核）、0052（ディスク FS とカーネルロックの外の I/O）、0073（`kibi:*` の WIT）、0085（読み込み時の復元）、0124（内蔵ディスク）
- ABI はオーナーの決定（Issue #56 のプラン改訂2・改訂3へのコメント）による。進め方はプラン改訂2・改訂3と「改訂3への補足」

## 決めたこと（要約）

- マシンは「今挿さっているディスクの表」（`Machine.inserted`。識別子 → 挿し込み `Insertion`）を持つ。最初の値は `MachineEnvironment.disks`（`DiskSnapshot`）、
  動いている間の変化は接続層が `ComputerCase.partsChanged(parts, disks)` → `Machine.disksChanged(snapshot)` で渡す。表は保存しない（実行時だけ）
- 起動のときに挿さっている、根（`/`）・`/home` 以外のディスクは `/mnt/<名前>` にマウントする（`BootSequence.mountOtherDisks`。イベントは出さない）
- 動いている間（`RUNNING` で、起動・復元の途中でない）に挿したディスクは、マシンに1本の自動マウントの仕事（起動用のスレッド `KiBi-boot-*`）が `/mnt/<名前>` にマウントし、
  `disk-inserted` を出す。抜いたディスクは、マウントを強制的に外し（指す開いたファイルは `IO`。ADR 0051）、`disk-removed` を出す
- 抜いたディスクが `/`・`/home` のマウントの元なら、または起動・復元の途中でその段が頼るディスクなら、`disksChanged` は「止めるべき」（`true`）と答え、
  `ComputerCase` がマシンを止めて `MISSING_PARTS`（`[STORAGE]` を含む）にする。それ以外のディスクを抜いても止めない
- 独自拡張 `kibi:fs@0.1.0` の interface `mounts` に `mount`・`unmount` を置き、p1 にも同じ形の import を出す

## ABI（`kibi:fs/mounts@0.1.0`）

```wit
mount: func(source: string, point: string, read-only: bool) -> result<_, error-code>;
unmount: func(point: string) -> result<_, error-code>;
```

p1: `mount(src_ptr, src_len, pt_ptr, pt_len, ro, ret_ptr) -> ()`、`unmount(pt_ptr, pt_len, ret_ptr) -> ()`。結果は `result<_, error-code>` を `ret_ptr` に書く（ADR 0073）。
どちらも待たない（`WAIT_IMPORTS` に入れない）。ディスクを開く I/O は、呼んだプロセスのスレッドでカーネルロックの外で行う（ADR 0052）。

| 状況 | error-code |
| --- | --- |
| `source` が `disk:<id>` でない・`disk:image:*`（同梱イメージ）・今挿さっていない | `no-entry` |
| そのディスクがもうマウントされている・ほかの持ち主が開いている途中（予約がある） | `busy` |
| `point` にもうマウントがある | `busy` |
| `point` がディレクトリ以外のもの・途中がディレクトリでない | `not-directory`（VFS の検査 `checkMountPoint` のとおり） |
| 呼んだときにマシンが `RUNNING` でない（止まっている途中・止まった） | `io`（予約を取らず、ディスクを開かない） |
| 開けない・開いている間にマシンが止まった・ディスクが抜けた | `io` |
| `unmount` の `point` が `/`・`/tmp`・`/dev` | `busy` |
| `unmount` の `point` の下にほかのマウントがある | `busy` |
| `unmount` の `point` がマウントの位置でない | `invalid` |
| 文字列が線形メモリの外 | `fault` |
| `source` が 4,096 バイトを超える・UTF-8 でない | `invalid` |
| `point` が `VfsPath.MAX_PATH_BYTES` を超える | `name-too-long` |

- `point` の相対パスは、呼んだプロセスのカーネルの作業ディレクトリから解決する
- `point` がなくてもエラーにしない。マウントは成功し、その位置と祖先は（根のディスクに作らずに）合成のディレクトリとして見える
- `read-only` が偽でも、ディスクが読み取り専用ならそのまま読み取り専用でマウントする
- 開いたファイルがあっても `unmount` できる（そのファイルの操作は以後 `IO`）

## 燃料（重い呼び出し）

`mount` はホスト側で毎回ディスクを開く（`DiskFileSystem.open` → 木をたどって使用量を数える `recount`、リースの取得）。この仕事は CPU の許可を持ったまま同期に行うので、
ふつうの呼び出し（`WasiP1Fd.CALL_FUEL` = 100）と同じ燃料では、`unmount` → `mount` を繰り返すゲストが、少ない燃料でワーカーとホストの I/O を使い続けられる（多数台で TPS を落とさない、という規模の目標に反する）。
そこで `mount` は、呼び出しごとの燃料に加えて次を払う（`ProcessRunner.Context.mount`。計測しないプロセスは払わない）。

- 固定分 `MachineLimits.MOUNT_FUEL` = 50,000（先に払う。失敗しても払う）
- 開いたディスクの項目（ファイル・ディレクトリ）1つあたり `MOUNT_FUEL_PER_ENTRY` = 100（開いた後に払う。木をたどった量に比例させる。`Machine.mountDisk` が項目の数を返す）

払うのは手持ちの燃料から引く借り（燃料が負になり、次の補充で返す）なので、ゲストは払い終えるまで進めない。仮の値で、確定は #251。
自動マウント（起動用のスレッド）にはこの燃料は掛けない。挿す・抜くはプレイヤーの操作で回数が限られ、ゲストが繰り返せないため。

## `/tmp` の下へのマウント

`mount` は位置を選ばないので、ゲストは `/tmp` の下（例: `/tmp/x`）にもマウントできる。止まるとき（`checkAllDoneLocked` → `unmountTmpLocked`）は `/tmp` を外して RAM を返すが、
強制でない `MountTable.remove` は「下にマウントがある」で `BUSY` を投げ、`stop()`・`close()`・`reboot()` の呼び手に例外が漏れて、状態が `STOPPING` のまま残る。
そのため `unmountTmpLocked` は、`/tmp` の下のマウントを深いほうから強制的に外してから `/tmp` を外す（`unmountForcedLocked`。ファイルシステムには `onUnmounted` で知らせるだけで、I/O はしない）。

## 自動マウントの名前とイベント

- 名前は識別子の先頭8文字の小文字（`/mnt/abcdef12`）。位置が埋まっていれば、10 文字・11 文字…と延ばす（`-` で終わる長さは飛ばす）。試すのは最大 32 回（`Machine.autoMountPoints`）
- `/mnt` が使えない（普通のファイル・作れない）ときや、開けないときは、マウントせずに位置の空の `disk-inserted` を1回出す
- イベント: `disk-inserted`・`disk-removed`。送り主（source）は `""`、値は `[識別子, 位置]`（マウントしていなければ位置は `""`）
- 自動マウントの仕事はマシンに1本（`AtomicBoolean`）。開けないディスクで回り続けない（1つのディスクは1回だけ試す。`Insertion.handled`）

## 予約（claim）と不変条件

同じディスクを2つの持ち主（起動の手順・復元の前段・自動マウント・システムコール）が同時に開かないよう、開く前にカーネルロックの中で予約（`DiskClaim`）を取る。
予約は「識別子・挿し込み・実行の世代（`runGeneration`）」の組で、次のどれかで取り消される（開き手の `cancelled` が真になる。ロックを取らずに `@Volatile` だけを読む）。

- 実行の終わり（`stopLocked`・init が最後に自分で終わって `checkAllDoneLocked` が `STOPPED` にしたとき）と段の始め（起動・冷えた起動・復元）で世代を進める（`endRunLocked`）
- そのディスクが抜けた（`Insertion.cancelled`）

不変条件:

- (a) 1つのディスクの予約は1つまで。予約があるディスクは `mount` で `busy`
- (b) マウントは、カーネルロックの中で「予約が生きている（世代と挿し込みが今のもの）」と「`RUNNING` で起動・復元の途中でない」（`canMountClaimLocked`。位置の空きは見ない）を確かめた区間でだけ足す。
  位置の空きは別に確かめ、埋まっていれば自動マウントは次の名前へ、システムコールは `busy`
- (c) 開いたがマウントしなかったファイルシステムは、必ず閉じる（`retire`）
- (d) 予約は、マウントした・やめたどちらでも同じ区間か `finally` で外す。段の予約は段の終わりに外す
- (e) 抜いたディスクのマウントは、抜いた区間（`disksChangedLocked`）で外す。止まったマシン（`RUNNING` でない）では外さず、次の起動が古いマウントを外す
- `RUNNING` の1つの挿し込みにつき、`disk-inserted` は1回まで（`Insertion.handled`。根・`/home`・起動のときにマウントしたものは出さない）
- `mount` のシステムコールは挿し込みを `handled` にしない。自動マウントの仕事が予約に阻まれて飛ばした挿し込みを `mount` が成功させたときは、`mount` の後始末が自動マウントの仕事を積み直し、
  仕事はマウント済みを見つけて（マウントの位置で）`disk-inserted` を出し、`handled` にする。このため、その後にゲストが `unmount` しても、同じ挿し込みは自動でマウントし直されない
  （同じ実行の中だけ。`handled` は保存しないので、保存・アンロードをまたぐと変わる。「知っている穴」を見る）
- システムコールの `mount` は、予約を取る区間で「`RUNNING` で起動・復元の途中でない」（`runningSteadyLocked`）を確かめる。ホスト関数の入口の確かめの後に止まったマシンが、
  新しい世代の（取り消されない）予約を取って開き、リースの世代を進めないため（(d)。段の予約は `bootCancelled`・`isRestoreCancelled` を足して同じことを防ぐ）

挿し込みのない予約（`insertion == null`）は、同梱イメージ（`image:*`）と、テストのフック（`KernelTestHooks.bootVolumes`）を差したときだけ許す。

## 復元（ADR 0085 との関係）

- 復元の前段（`MachineRestore.openMounts`）は、保存したマウントごとに予約を取って開く。開けなかったとき:
  - 根・`/home` なら、今までどおり冷えた起動（`MOUNT`）
  - それ以外なら、そのマウントだけを落として戻す（`droppedMountIds`）。そのマウントを指す開いたファイルは `IO`。戻した後に `disk-removed`（`[識別子, 位置]`）を出す
- 「改訂3への補足」1: 予約は `PreparedRestore.claims` に持ち、戻したとき（`completeRestore`）か、失敗した3か所（`completeRestore` が偽・前段の例外・`prepare` の例外）で外す
- 戻した後（`RESTORED`）に、挿さっているがマウントしていないディスクを自動マウントの仕事に回す（`reconcileMounts`）

## 「改訂3への補足」

1. 復元の予約の寿命は上のとおり（前段から `completeRestore` まで持ち、成功・失敗のどちらでも外す）
2. マウントしてよいかの確かめ（`canMountClaimLocked`）は、位置の空きと分ける。自動マウントは名前を延ばすので、位置が埋まっているのは「やめる」ではなく「次の名前」

## 振る舞いの変化

- 以前は電源を入れたときのディスク（`ComputerCase.bootDiskIds`）のどれかを抜くと止めていた。今は根・`/home`（と起動・復元の途中で頼るディスク）だけで止める。`bootDiskIds` は消した（`Machine.insertedDiskIds` が今の表）
- `MachineEnvironment.disks` の型を `DiskResolver` から `DiskSnapshot`（`@ApiStatus.Internal`）に変えた。公開 API（`pro.ikili.kibicomputers.api`）は変えていない
- 保存形式は変えていない（表・予約・イベントの印は保存しない）

## 知っている穴

- 開いているファイルは保存・復元する（#82）が、抜いたディスクを指すものは `IO` になるだけで、ゲストに知らせるのは `disk-removed` だけ
- `unmount` したまま保存・アンロードしたディスクは、復元の後にまた `/mnt/<名前>` にマウントされ、`disk-inserted` が出る。`Insertion.handled` は保存しない
  （表は実行時だけ。保存形式を変えないため）ので、戻した後は、挿さっているがマウントしていない新しい挿し込みとして扱う。ゲストは `disk-inserted` を見て外し直せる
- 標準 OS の `mount`・`umount` コマンドはまだない（#454 で足す）
- （#453 で解けた）以前は、挿さっているがマウントしていないディスク（`unmount` した後・自動マウントの位置が使えなかったもの）を、ゲストが `disk-inserted` を見逃すと知る手段がなかった。今は `list-disks`（ADR 0453）が、位置 `none` で返す。その `source` をそのまま `mount` に渡せる
- `list-mounts` は同梱イメージの根を `disk:image:<名前>`（種類 `image`）として返すが、その source を `mount` に渡すと `no-entry`（同梱イメージは挿し込みではないので、`list-disks` にも出ない）。一覧と `mount` が受け付ける source は対称でない
- 開いている途中（予約がある）のディスクは、`list-disks` では位置 `none` に見えるが、そのときに `mount` すると `busy`
- p2・p3 の world は仮置き（ほかの `kibi:*` と同じく、Component Model のホストの実装は #259 の配下）
- 同じ識別子のディスクを2台に挿したときは、ADR 0052 のリースのとおり後から開いた方が勝つ（#606 の場合も同じ）
