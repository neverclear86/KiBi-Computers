# 0081: 待機点保存のライフサイクル、マシンの ID と持ち主、世代のファイル

- 状態: 採用
- Issue: #81（task）。前提: ADR 0030（状態保存の PoC）、#339（燃料待ちの巻き戻し）、#358・#365（待機点の判定）、ADR 0124（内蔵ディスクの ID）
- 後続: #82（各節の中身）、#83・#84（差分保存・圧縮・書き込みのスレッド・掃除）、#85（復元）、#86（殺したプロセスの通知）、#88（MOD のバージョン違い）、#132（アンロード中の休止）、#251（仮の値の確定）

## 決めたこと（要約）

- **いつ取り出すか**: チャンクのアンロード（`ChunkEvent.Unload`）、サーバーの停止（`ServerStoppingEvent`）、MOD の自動保存（仮 6000 tick ごと。tick ごとの予算で回して書く）。`saveAdditional` の中では取り出さず、参照（`MachineId`・`SaveGeneration`・`OwnerEpoch`）を書くだけ
- **保存できる状態**（`SaveReadiness`）: `READY`（待機点）・`TRANSIENT`（`WAIT_POINT` 型の step の途中。待てば来る）・`UNSAVEABLE`（`BLOCKING` 型で待機点にいない）。マシンが起動中・再起動待ちなら全体を `TRANSIENT`
- **保存できないプロセスは写しの中でだけ殺す**（`KernelStateCopy.killInCopy`）。元のマシンは止めない。殺した pid と「冷えた起動が要る理由」を `KILLED` 節に書く
- **マシン1台だけを止める口** `Machine.quiesce()`（`QuiesceHandle`）。スケジューラが新しい step を割り当てなくなり、走っている step は待機点まで走る
- **マシンの ID** は UUID。最初の取り出しで振り、`<world>/kibicomputers/machines/<uuid>/owner.bin`（持ち主と epoch）を書く
- **世代のファイル** `<uuid>/<世代>.bin` を tmp → fsync → `ATOMIC_MOVE` で書く。チャンクの NBT が指す世代は `ChunkDataEvent.Save` で記録し（印）、印が進んだときだけ古い世代を消す
- 書き込みは #81 ではメインスレッドで同期して行う（#84 で別スレッドに移す）

## 保存できる状態と写し

- `Machine.saveReadiness()` はプロセスごとの判定をまとめる（1つでも `UNSAVEABLE` なら `UNSAVEABLE`、次に `TRANSIENT`）。プロセスのないマシンは `READY`
- `Machine.captureSnapshot(policy)` はカーネルロックの中で `KernelStateCopy` を作り、`MachineSnapshotSource` を返す
  - `SKIP_IF_TRANSIENT`（自動保存）: `TRANSIENT` があれば `null`（前の世代を残す）。`UNSAVEABLE` は写しの中で殺す
  - `KILL_TRANSIENT`（アンロード・停止）: `TRANSIENT` も写しの中で殺す
  - マシン全体が `TRANSIENT`（起動中・再起動待ち）で `KILL_TRANSIENT` のときは、必ず写しを作り、理由を `REBOOT_PENDING`（優先）か `BOOTING` にする。殺す手順は走らせない
- **写しは、殺す手順が書き換えるものを元と共有しない**: プロセス表・`Process`（fd 表・待ちのタイマー・Asyncify のスタック）・`ProcessObject`・オブジェクト表（参照の数）を作り直す。オブジェクトの実体は `KernelObject.copyForSnapshot()` が返せば写したもの、返さなければ元の実体を指すだけ（#81 で写せるのは `ProcessObject` だけ）
- **写しの中の後始末の規則**: `WakeSink` は何もしない `NoopWakeSink`。写していない実体の参照が 0 になっても `onLastClose`・`releaseReferences` を呼ばず、ID を `closedInCopy` に記録するだけ（ファイルのチャネル・パイプ・タイマー・デバイスの呼び出しなど、ホストの資源を閉じない）
  - **#82 への申し送り**: 種類ごとに `copyForSnapshot` を足し、`closedInCopy` を「写した実体の側で閉じた後の状態」に置き換える。すべての種類を写せるまで、`KERNEL` 節の中身は書かない
- 殺す手順の帳簿（終了状態・`pendingChildExits`・親なしのゾンビ・刈り取り）は `ExitBookkeeping` に分け、実行時の経路（`ProcessLifecycle`）と写しの経路（`killInCopy`）が同じものを通る
- init を写しの中で殺したら理由は `INIT_KILLED`

### `KILLED` 節の u8 を冷えた起動の理由にした理由

起動中・再起動待ちのマシンは init がないか終わっていて、「init を殺した」の印が立たない。印がないと、電源の切れたマシンと区別できない。
写しを書かずに前の世代を残す案は採らない（再起動の前の状態を蘇らせて再起動の意図に反するか、世代がなく電源が切れたものとして戻る）。
読むとき、知らない理由の値は「冷えた起動が要る」として扱う（`MachineSnapshot.requiresColdBoot`）。`KILLED` 節がない・読めないときも同じ。

**#85 への申し送り**: 理由が 0 以外なら状態を復元せず、電源は入っているものとして冷えた起動にする（起動の設定は接続層が渡し直す。ADR 0049）。

## ライフサイクル

| 場面 | 手順 |
| --- | --- |
| アンロード | `ChunkEvent.Unload`（`LevelChunk`・`ServerLevel`）で、そのチャンクの全ケースを止める → 待つ（tick ごとの予算 `unloadWaitBudgetMillis` 仮 50 ms）→ `KILL_TRANSIENT` で取り出す → 書く → `chunk.setUnsaved(true)` → 続く `ChunkMap.save` が新しい世代を NBT に書く → `onChunkUnloaded` で捨てる |
| 停止 | `ServerStoppingEvent`（`saveAllChunks` の前）で全台を止める → まとめて最大 `stopQuiesceMillis`（仮 500 ms）待つ → 全台を `KILL_TRANSIENT` で取り出して書く → `setChanged`。止めた台は `ServerStoppedEvent` まで止めたまま。後で来る `ChunkEvent.Unload` は「取り出し済み」として、NBT が古い世代を指すときだけ `setUnsaved(true)` を付ける |
| 自動保存 | 周期（仮 6000 tick）ごとに、電源の入っている台の列を作り、tick ごとの予算で回して書く（下） |
| プレイヤーの破壊・爆発 | `CaseBlock.playerWillDestroy`・`onBlockExploded` で `<uuid>/` を消す |
| そのほかの `setRemoved` | 取り出さずに捨てる。ディレクトリは消さない |

- **アンロードで `setChanged` が効かず、`setUnsaved(true)` を直接付ける理由**: MC 1.21.1 の `ChunkMap` は `setLoaded(false)` → `ChunkEvent.Unload` → `save(chunk)` → `level.unload` の順に走る。`Level.blockEntityChanged` は `hasChunkAt` を見るので、`setLoaded(false)` の後の `setChanged` はチャンクに届かない。`save` は `isUnsaved()` を見て保存するかを決めるので、イベントのチャンクに直接付ける
- **停止の取り出しを `ServerStoppingEvent` でする理由**: `saveAllChunks(true)` は `ChunkEvent.Unload` を通らずに保存し、チケットのあるチャンク（スポーンチャンク・`/forceload`・チャンクローダー）はアンロードされない。保存の前に全台を取り出して `setChanged` を付ければ、読み込み中の全チャンクの NBT が新しい世代を指す
- **`setRemoved` でディレクトリを消さない理由**: ブロックを運ぶ MOD の経路でも呼ばれ、運んだ先で同じ ID を引き取る（下の `TAKEN_OVER`）。ADR 0124 の内蔵ディスクと同じ考え。残ったディレクトリの掃除は #84

### 自動保存を回して書く理由と予算

fsync は中身の大きさによらず時間がかかる（開発機の D: ドライブで 1 回約 19 ms。SSD なら数 ms）。全台を数 tick に集めると、1,000 台で数秒止まる。
そこで `AutosaveRotation` が、tick ごとの書き込み（fsync）の回数の予算 `autosaveWritesPerTick` で回して書く。

- 回数で数える: `identity` のある台は 1、ID を振る（`owner.bin` を書く）台は 2。止める上限も同じ回数で数える（予算 × `autosaveQuiesceTicks`）
- 毎 tick「書く → 飛ばす → 止める」の順。書くのは止めた順で、`READY` の台を予算が尽きるまで。予算待ちの `READY` の台は飛ばさない（止めている間は step が割り当てられないので `READY` のまま）。前の台が `READY` でないときだけ追い越す
- `autosaveQuiesceTicks`（仮 4）は `READY` になるまでの待ちにだけ掛ける。過ぎたら飛ばし（前の世代を残す）、次の周期の先頭に回す
- 予算を超えた分（回数 2 の台で残りが 1 のとき、書く前の確かめで振り直したとき）は次の tick の予算から引く。1 tick の書き込みは最大で予算 + 1
- 台数で数えない理由: ID を振る初回に予算待ちが `autosaveQuiesceTicks` を超え、飛ばすか止める時間が延びるかのどちらかになるため
- **仮の値は 2 回**（プランの仮 16 から下げた）。開発機で測ると、予算 16 では 100 台の一巡の間の tick が平均 375 ms になった（fsync 約 19 ms × 16 回）。2 回なら約 40 ms で 20 TPS に収まる。1,000 台の一巡は、ID を振る初回で約 1,000 tick、2回目から約 500 tick で、周期 6000 tick より短い。確定は #251、書き込みのスレッド（#84）ができたら上げられる
- **アンロードは延ばせない**: 1 tick の書き込みは「その tick にアンロードされるチャンクの台数 ×（1 か 2）」。`ChunkMap.processUnloads` は 1 tick に最低 200 チャンクを処理するので、最悪は 1 tick に全台。#81 ではこれを受け入れて負荷試験で測り（`docs/load-test.md` の「まとめてアンロード」）、#84 で解く（メインスレッドは写しを作るだけにする）

## マシンの ID と持ち主（epoch 方式）

- BE の NBT に `MachineId`（UUID の正規の小文字。`CanonicalUuid` で検査）・`SaveGeneration`・`OwnerEpoch`（0 以上 2^62 以下）。外れた値は「ID なし」
- BE は「NBT から読んだ生の値」と「確定した ID」（`identity`）を分けて持ち、`saveAdditional` は確定した ID があればそれを、なければ生の値を書き戻す
- `owner.bin`: magic `KIBO`、版 u16 = 1、epoch（i64）、持ち主のディメンション（`writeUTF`）と座標（3×i32）
- **索引**（`MachineIdIndex`。UUID → 持ち主）: `claim` に通った・ID を振った、**読み込み済みの BE** を入れ、`onChunkUnloaded`・`setRemoved` で外す。マシンが動いているかは問わない（#81 では復元しないので、`onLoad` の時点で動いているマシンはほぼない。複製の検出に要るのは「同じ UUID の BE が2つ読み込まれていること」）
- `claim`（サーバーの `onLoad`、まだ ID のない読み込み済みの BE の `loadAdditional`）の判定順:
  1. 値の検査に通らない → 新しい UUID（`NEW_ID_INVALID`）
  2. 索引に同じ UUID の別の持ち主 → 新しい UUID（`NEW_ID_DUPLICATE_LOADED`）
  3. `owner.bin` がない → 自分を持ち主にする（`KEPT`）
  4. 持ち主が自分 → `KEPT`（NBT の epoch が古ければ直す）
  5. 持ち主が別で、NBT の epoch が記録以上 → epoch + 1 で引き取る（`TAKEN_OVER`）
  6. 持ち主が別で、NBT の epoch が記録より古い → 古い写し。新しい UUID（`NEW_ID_STALE`）
  - `owner.bin` が読めない・壊れている → 新しい UUID（`NEW_ID_UNREADABLE`）。例外はチャンクの読み込みの外に出さない
- **書く前の確かめ**: `MachineSaveStore.write` は、覚えている `owner.bin` の持ち主と epoch が BE と合うかを確かめ、合わなければ新しい UUID に振り直して書く（`WriteResult.reassigned`）
- **持ち主の欄1つでは足りず、一覧でなく epoch にした理由**（回答 A）: ブロックを運ぶ・`/clone` で写すと、同じ UUID を持つ NBT が複数できる。持ち主の座標だけだと「運んだ先の正しい引き取り」と「古い写しの読み込み」を区別できない。持ち主の一覧（運んだ履歴）は増え続け、どれが最新かも分からない。epoch は引き取るたびに 1 上がり、運んだ BE 自身が最新の epoch を持つので、運び戻しは何度でも引き取れ、引き取りの前に写された NBT は古い写しとして新しい UUID になる（取り違えは各写しにつき最大1回）
- **確定した ID は `loadAdditional` で変えない理由**: `/data merge` で `MachineId` を書き換えると `claim` を飛ばして他のマシンのディレクトリを指せるため。変化は debug でログに残して無視し、次の `saveAdditional` が確定した値で上書きする。取り出し（ID を振る）が `onLoad` より先に来たとき（置いた直後の tick）も、`onLoad` の `claim` は確定した ID を変えない

## 世代のファイルとチャンクの印

- `<uuid>/<世代>.bin`（10進。`0` か先頭が 0 でない19桁まで）。次の世代は「BE の世代 + 1」と「このサーバーが書いた最新 + 1」の大きい方。2^62 を超えるなら新しい UUID に振り直す
- **NBT が指す世代を `ChunkDataEvent.Save` で決める理由**: `saveAdditional` は `/data get block`・`/clone`・ピックブロック・ストラクチャーの保存からも呼ばれ、呼び元を区別できない。チャンクの保存は遅れることがある。`ChunkDataEvent.Save` の `getData()` はチャンクのファイルに書く `CompoundTag` そのもの
- **印を持ち主の位置のタグだけで動かす理由**: `getBlockEntityNbtForSaving` は BE がなければ `pendingBlockEntities` の生のタグを返すので、worldgen のストラクチャーや読み込まれていない BE の、持ち主でない写しのタグもチャンクの NBT に入る。これで印が戻ると、NBT が指す世代を消してしまう。タグの座標とイベントのディメンションが覚えている `owner.bin` の持ち主と合うときだけ動かし、合わなければ `NOT_OWNER`・`UNKNOWN_ID`（debug でログ）。戻す向き（`MOVED_BACK`）・同じ値（`UNCHANGED`）ではファイルを消さないのも同じ理由
- **残す世代**: 印が進んだ（`ADVANCED`）ときだけ、「今の印」「前の印」「2つ前の印」「覚えている最新」と、印より大きい世代を残して、ほかを消す
  - **「印より小さいもののうち最大」で選ばない理由**: `ChunkMap.save`（ChunkMap.java 790〜796）は `ChunkDataEvent.Save` を投げてから `this.write`（IOWorker の非同期の書き込み）を呼び、メインスレッドが書き終わりを待つのは `flushWorker`（停止の `saveAllChunks(true)`）だけ。印が進んだ時点では、ディスクのチャンクはまだ前の印を指している。「印より小さい最大」では、戻す向きの後（g7 → g5 → g8 で g5 が消える）や、チャンクの保存の間に2回書いたとき（印 g7 で g8・g9 を書き、印が g9 に進むと g7 が消える）に、ディスクのチャンクが指す世代を消しうる
  - **前の印を2つ覚える案を採った**: IOWorker は同じチャンクの未処理の書き込みの `data` を差し替える（IOWorker.java 132〜133）ので、同じチャンクの保存が短時間に2回起きると、間の NBT は一度もディスクに載らず、ディスクは2つ前の印を指していることがある。ファイルが1つ増えるだけで形式は変わらない。前の印はメモリの上だけに持ち、保存しない（再起動後は `claim` で読んだ世代が印になる）

## 保存データの外枠

- magic `KIBS`、形式の版（u16 = 1）、MOD のバージョン（`writeUTF`）、UUID（2つの i64）、世代（i64）、続いてファイルの終わりまで節の並び
- 節は「種類（u16）・節の版（u16）・長さ（u32）・中身」。知らない節・版の違う節は読み飛ばす（中身は残す）。節の数は書かず、節の始まりでファイルが終わったら終わり
- 種類: `KERNEL`（1）・`PROCESS_ABI`（2）の中身は #82。`KILLED`（3、版 1）は u32 の個数 + i32 の pid の並び + u8 の理由（0 `NONE`、1 `INIT_KILLED`、2 `BOOTING`、3 `REBOOT_PENDING`）

## プランからの違い

- `MachineSnapshotSource.build` に MOD のバージョンの引数を足した（外枠に書くため。写しの側が MOD のバージョンを知らない）
- `MachineSnapshot.coldBootReason` は、知らない理由の値で `null` を返す（値そのものは `coldBootCode`）。判定は `requiresColdBoot`
- 外枠に節の数を書かず、ファイルの終わりまで節を読む（足すときに数を直さなくてよい）
- 写しそのもの（`captureCopy`）は `internal`。接続層には `captureSnapshot` だけを出す
- `autosaveWritesPerTick` の仮の値を 16 から 2 に下げた（上の「自動保存を回して書く理由と予算」）
- 前の印を2つ覚える案を採った（プランは実装に任せていた）

## 決めていないこと

- 各節の中身、プロセス以外のカーネルオブジェクトの写し（#82）
- 書き込みのスレッド、圧縮、差分、残ったディレクトリ（ID のない・印のない台）の掃除（#83・#84）
- 復元と冷えた起動へのフォールバック（#85）、殺したプロセスへの通知（#86）、MOD のバージョン違い（#88）
- 仮の値（周期・予算・待ちの上限）の確定（#251）
