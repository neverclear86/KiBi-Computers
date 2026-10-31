# 0043: カーネル全体の設計

- 状態: 採用
- Issue: #43（task）。前提: ADR 0027（スレッド）、0028（燃料）、0029（プレイヤーの WASM）、0030（待機点での保存）、0260（Component Model）
- 後続: #44・#45・#46・#47・#58・#59・#63・#72・#81・#82・#120、Epic #259 の各層
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- カーネルの単位は「マシン（コンピューター1台）」。マシンは `KernelState`（プロセス表・オブジェクト表・燃料）を1つ持つ。1プロセス = 1 WASM インスタンス（Component Model では、手持ちを持つコアインスタンスは1つ）
- p1・p2・p3 で共有する土台は `KernelObject`（オブジェクトIDと参照カウント）、`HandleTable`（fd と resource ハンドルの共通の形）、待ち（`WaitKey` / `WaitRegistry` / `WaitSet`）、`ByteStream` の4つ。ABI 層は `ProcessDriver` を実装して、その上に乗る
- プロセスの動き方は `BLOCKING`（ホスト関数の中で止まる）と `WAIT_POINT`（export から戻って待つ）の2種類。保存できるのは `WAIT_POINT` で待っているときだけ
- スレッドは WASM のスタックがあるあいだだけ持つ。ブロッキング型は専用スレッド（サーバー全体の上限あり）、待機点型は共有のワーカーで step を動かし、待機点と step の境目ではスレッドを持たない
- 燃料の正はマシンの `KernelState.fuel`。CPU を取るときに遅延補充し（tick ごとに配るのと同じ値の閉じた式）、`tick()` は台数によらない
- 停止は `Thread.interrupt` に頼る。割り込みはスレッドの登録をカーネルロックの中で確かめてから、同じロックの中で送る
- `core.kernel` で `public` にするのは接続層が使う `MachineScheduler`・`SchedulerConfig`・`PauseHandle`・`Machine`・`MachineConfig`・`MachineState`・`MachineHost` だけ。`pro.ikili.kibicomputers.api` には何も足さない

## 構造

```
MachineScheduler（サーバーに1つ。接続層が作る）
 ├─ 公平なセマフォ（同時に WASM を実行するマシンの数の上限）
 ├─ tick(): tick の番号を進め、ディスパッチャーに知らせ、通知の溜まったマシンだけ MachineHost に配る
 ├─ ディスパッチャー（KiBi-dispatcher。1本）: 燃料待ちのいるマシンだけを見て、起こす
 ├─ ワーカー（KiBi-worker-N。共有のプール）: 待機点型の step を動かす
 ├─ pauseAll(): 保存の前に全マシンを止める
 └─ Machine（コンピューター1台 = 1カーネル）
     ├─ KernelState（保存の対象）: ProcessTable、ObjectTable、fuel
     ├─ CPU（MachineCpu。マシンの中で WASM を実行するのは同時に1プロセス。FIFO）
     └─ 実行時だけのもの: ProcessRuntime、WaitRegistry、燃料待ちの列、lastRefillTick、ロック、通知の列
```

コードは `core/src/main/kotlin/pro/ikili/kibicomputers/core/kernel/`。

## スレッドモデル（ADR 0027 を変えたところ）

ADR 0027 は「実行中のコンピューター1台に1スレッド」だった。複数プロセスと規模の目標（目安 1,000 台）のために、次のように変える。

| 型 | スレッド | 待つとき |
| --- | --- | --- |
| `BLOCKING` | プロセスごとに専用スレッド `KiBi-m<マシン>-p<pid>` 1本。上限 `SchedulerConfig.maxBlockingProcesses`（超えると `spawn` が `ResourceLimitException`） | `ProcessContext.block`（スレッドを持ったまま） |
| `WAIT_POINT` | 1回の step（作って `start`、または `resume`）を共有のワーカーで動かす。step の途中の燃料待ち・`awaitHost` の間はワーカーを持つ | `StepResult.Wait`。待っている間はスレッドを持たない |

- ワーカーのプールは `ThreadPoolExecutor`（コア 0、上限なし、`SynchronousQueue`、アイドル 30 秒）。上限を付けないのは、step の途中で燃料待ち・許可待ちのワーカーが仕事を塞いでデッドロックしないため
- スレッドはどれもプラットフォームスレッド・daemon・スタック 16 MiB（予約だけ。仮の値）
- 待機点型のプロセスが起こされたら、スレッドを持たない待ち手としてマシンの CPU の待ち行列に入る。順番が来たらワーカーへ渡す。ワーカーは許可を取り、燃料を確かめ、なければ許可と CPU を返して**スレッドを返し**、燃料待ちの列に入る
- ロックの順番は CPU → 許可 → カーネルロック。カーネルロックを持ったまま、待つ・WASM を呼ぶ・`MachineHost` を呼ぶ、のどれもしない（条件変数の `await` はロックを手放すので使う）。CPU の待ち行列・一時停止の数え・ディスパッチャーの列のロックは末端のロックで、カーネルロックの中から取ってよい
- CPU と許可は `ProcessRuntime.holdsCpu`・`holdsPermit` の印で、持っているものだけを1回だけ返す

## 燃料（ADR 0028 の載せ方）

- **正は `KernelState.fuel`。** インスタンスのグローバル `kibi_fuel` は、そのプロセスが CPU を持っている間だけ使う手持ち。CPU を持っていないときは常に 0。手持ちを持つのは `ProcessRuntime.fuelHolder` 1つ（作っている途中は仮の holder）
- **遅延補充**: 規則は「tick ごとに `fuel = min(fuel, 0) + fuelPerTick`」。メインスレッドで配らず、`lastRefillTick` から進んだ k tick 分を閉じた式でまとめて当てはめる（`FuelAccount.refilled`）。`fuel ≥ 0` なら `fuelPerTick`、負なら `fuel + k·fuelPerTick`（0 以上になるまで）、それ以後は `fuelPerTick`
- `refill(now)` を呼ぶのは、マシンの燃料を読み書きする口のすべて: CPU を取る、CPU を返す2つの口、例外で抜けたとき（`holdsCpu` のときだけ）。マシンの燃料を読み書きするのは CPU を持つプロセスだけで、CPU を持っている間の `fuel` は 0
- **CPU を取る**: CPU → 許可 → カーネルロックの中で補充し、停止の印・一時停止・`fuel > 0` を確かめて `grant = fuel`、`fuel = 0`（1回に渡すのは最大 `fuelPerTick`）
- **CPU を返す**（`block`・`awaitHost`・`Wait`・`Exit`）: 今の holder のグローバル r を読んで 0 にし、補充してから、手持ちを渡してから tick が進んでいなければ r、進んでいれば `min(r, 0)` を足す（結果は tick ごとに配るのと同じ `fuelPerTick + min(r, 0)`）
- **燃料切れの処理**（`WasmFuelHandler`）ではグローバルを読まない・書かない。書き戻す残りは引数の `remaining`、新しい手持ちは戻り値
- **例外で抜けたとき**（トラップ、`ProcessKilledException`、ABI 層の例外）は、グローバルを読まず、マシンの燃料に何も書き戻さない（手持ちは使い切ったとみなす）。トラップは書き戻しを通らずに抜けるので、グローバルが古い正の値のことがあり、足すと燃料の上限を回避できるため。CPU を持たないプロセス（`block`・`awaitHost`・燃料切れの処理で待っている間に止められたもの）は、`fuel` にも `lastRefillTick` にも触らない
- **同じ tick の中の受け渡し**: CPU を返したときにマシンの燃料が正なら、そのマシンの燃料待ちを（ディスパッチャーを待たずに）起こす。返した残りを、同じ tick の中で次のプロセスが使える
- **システムコールの費用**: `ctx.chargeFuel(n)` は今の holder の手持ちから引く
- **待機点での保存**: 待機点では、どのインスタンスの `kibi_fuel` も 0。#82 は `kibi_fuel` を特別扱いせず書き出してよい。復元のときに燃料として読むのは `KernelState.fuel` だけ（`lastRefillTick` は今の tick にする）

### インスタンスを作るとき

- 内部の `Machine.spawn(program, driver, options, parentPid)` は `Process` を `CREATED` で表に足すだけで、インスタンスは作らない
- プロセスのスレッドで、CPU と許可を取ってから `LOADING` にし、ロックの外で `ProcessDriver.configure(ctx, builder)` を呼ぶ。ABI 層は `ctx.hostFunction(f)` で包んだホスト関数を登録するだけ。カーネルが `builder.fuel(grant, 燃料切れの処理)` を設定してから `instantiate` する（ABI 層が `builder.fuel` を呼んでいたら `IllegalStateException` で `CRASHED`）
- 作っている途中は、ホスト関数・燃料切れの処理に渡される `instance` を**仮の holder** として受け入れる（最初の1つを仮の holder にし、以後は同じか確かめる）。作っている途中でなければ `fuelHolder` と同じか確かめる。違えばマシンを `CRASHED`。作り終えたら、返されたインスタンスが仮の holder と同じか確かめる
- 2つ目以降のコアインスタンスは `ctx.instantiate(module, InstanceConfigurer)`。今の手持ちを `initialFuel` として渡して元を 0 にし、作り終えたら残りを元の holder へ戻す。作っている途中の例外は、CPU と許可を返さず、燃料だけを捨てて（元の holder は 0 のまま）呼び出し元へ投げる。作っている途中の入れ子の `instantiate` と `switchFuelHolder` は許さない（#270 で見直す）
- インスタンスをまたぐ呼び出しでは `ctx.switchFuelHolder(target)` で手持ちを移す

### 燃料を計測しないモジュール

既定では `spawn` が断る（`IllegalArgumentException`）。テスト専用の `SpawnOptions.allowUnmetered` のときだけ動かす。燃料を使わず、一時停止できない（計算中の `pauseAll` は必ず `TimeoutException`）。止めるのは割り込みだけ。

## 待ち

- 待ちの単位は `WaitKey(objectId, condition)`（保存できる値）。`WaitCondition` は今は `READABLE`・`WRITABLE`
- オブジェクトの側は `canWait`・`isReady` を答え、状態を変える操作は引数の `WakeSink` に自分の `WaitKey` を知らせるだけ（`WakeSink` をフィールドに持たない）
- 実行時の側は `WaitRegistry`（`WaitKey` → `Waiter`）。登録・解除・`wake` はカーネルロックの中。`Waiter.wake()` は印を立てて起こすだけ（印が立っている間は何もしない）
- `WaitSet` の登録は、待ちが終わると決まるまで外さない。**起こされたのに、そろった key が空なら待ち直す**（印を下ろす）。カーネルは `resume` にも `block` の戻り値にも空の並びを渡さない。待機点型は `resume` を呼ばず、許可も燃料も使わずに `WAITING_EVENT` に戻る
- 閉じられたオブジェクト（参照カウント 0）・見つからないオブジェクトの key は「そろった」扱い。待ちは参照カウントを持たない
- 復元のときは `WaitRegistry` を空から作り、`Process.wait` から `WaitSet` を作り直す
- Component Model: p2 の `pollable` はハンドルのエントリに `WaitKey` を1つ持たせ、p3 の waitable-set は `WaitKey` の並びを持つ `KernelObject` にする（#264・#266）

## 一時停止と停止

- `pauseAll(timeoutMillis)` は一時停止の印を立て、実行中（燃料を受け取って動いている）のプロセスがなくなるまで待つ。プロセスは CPU を取るところで止まる。止まるまでの上限は手持ち（最大 `fuelPerTick`）を使い切るまで。時間切れなら一時停止を解いてから `TimeoutException`
- `Machine.stop()` は待たずに返す（どのスレッドから呼んでもよい）。状態を `STOPPING` にし、各プロセスに停止の印を立てる。スレッドを持たないプロセスはその場で `KILLED` にし、スレッドが登録されているプロセスにだけ `Thread.interrupt()` を送る
- 計算中の WASM は Endive の中断（`WasmInterruptedException` → `WasmTrapKind.INTERRUPTED`）で止める。カーネルの待ちはすべて割り込みを受けられる形
- 停止の印があるときの例外は `KILLED`、ないときは `CRASHED`。カーネルの不整合はマシンを `CRASHED` にする
- 待つのは別の口 `awaitTermination`（カーネルのスレッドからは `IllegalStateException`）

### 割り込みとスレッドの登録の順番

1. スレッドは、プロセスを動かし始めるときに、カーネルロックの中で `ProcessRuntime.thread` に自分を登録する。停止の印が立っていれば、WASM を動かさずに `KILLED` にして終える
2. `stop()` は、カーネルロックの中で登録を確かめたときだけ、同じロックの中で割り込みを送る
3. スレッドは step（専用スレッド）の終わりに、`finally` の中で、カーネルロック（`lock()`。割り込みを受けない形）の中で登録を外し、続けて同じロックの中で `Thread.interrupted()` で印を消す。それからワーカーならプールへ返る

送るのも外すのも同じロックの中なので、割り込みはそのプロセスの step の中でしか効かず、別のマシンの step に持ち越されない（`ThreadPoolExecutor` が仕事の始めに印を消す処理には頼らない）。WASM が終わった後・登録を外す前に届いた割り込みは、登録を外す同じロックの中で停止の印を見て `KILLED` にする。

## MC 側との境界（#44 の前提）

- 接続層 → カーネル: `tick()`（毎 tick）、`createMachine`、`Machine.stop()`・`close()`、`pauseAll()`、`awaitTermination`
- カーネル → 接続層: `MachineHost.onStateChanged`。`tick()` の中でメインスレッドから、ロック・CPU・許可を持たずに呼ぶ。状態の変化はマシンごとの通知の列に積み、通知のあるマシンだけを `tick()` が配る

## 規模（目安 1,000 台）

| コスト | 抑え方 | 1,000 台のときの見込み |
| --- | --- | --- |
| OS スレッドの数 | 待機点型は step の途中だけ。ブロッキング型は1プロセス1本で、上限 `maxBlockingProcesses`。ほかにディスパッチャー1本 | 待っているだけの待機点型の台は 0 本 |
| 待っているだけの台に tick ごとにかかる処理 | 0。`tick()` は tick の番号を進めるだけ。燃料は CPU を取るときに遅延補充する。通知は通知のあるマシンだけ | `tick()` は O(1)＋O(通知の数) |
| 燃料待ちを起こす処理 | ディスパッチャーが燃料待ちのいるマシンだけを見る（メインスレッドの外） | メインスレッドには乗らない |
| CPU の全体の上限 | 公平なセマフォ（同時に実行するマシンの数。仮に CPU のスレッド数の半分） | 計算に使うコアの数は台数によらず一定 |
| 1台あたりのメモリ（線形メモリ以外） | `HandleTable` は 8 から倍に、`ObjectTable`・`WaitRegistry` の表は最初に使うときに作る、`ByteStream` のバッファは最初の書き込みで 256 バイトから倍に広げ、空になったら手放す | 数 MiB |

常駐するプロセス（init、シェル、Lua の REPL）は、待機点型がよい（ブロッキング型は待っているだけでもスレッドを1本持つ）。#30 の推奨（`lua.wasm` を reactor にする）と同じ向き。ABI として決めるのは #63・#72・#73。

## データ形式

保存のバイト形式は決めない（#81・#82・#88）。メモリ上の持ち方だけを決める。

- 保存の対象は `KernelState` の下と、各プロセスの WASM インスタンスの中の状態（#30 の結論 2）。`KernelState` の下は番号・列挙・バイト列だけで、実行時だけのもの（`Waiter`、`WaitRegistry`、`WakeSink`、スレッド、`WasmInstance`、ロック、`MachineHost`）を参照しない（`KernelStateIsolationTest` で確かめる）
- オブジェクト同士・プロセスとオブジェクトの間は番号で指す
- `Process` は pid・親の pid・型・状態・終了コード・モジュールの識別子（`load` に渡したバイト列の SHA-256）・燃料を計測しないか・待っている `WaitKey` の並び・ハンドル表を持つ
- 待機点なのは、`WAIT_POINT` 型の `WAITING_EVENT`、`WAIT_POINT` 型で境目にいる `RUNNABLE`、`CREATED`、終わったプロセス

## 申し送り

- **#38**: カーネルは次の2つに頼る。(1) `kibi.fuel` 以外のホスト関数を呼ぶ前、export から戻る前、start 関数から戻る前に、ローカルの残りがグローバルへ書き戻され、ホスト関数から戻った後に読み直されること。(2) `kibi.fuel` の戻り値が新しい残りとして使われること。次の2つには頼らない: (3) `kibi.fuel` の呼び出しの前後でグローバルが同期されていること、(4) トラップで抜けたときにグローバルが同期されていること
- **#270**: 手持ちを持つのは実行しているコアインスタンス1つだけ。Canonical ABI の層は、インスタンスをまたぐ呼び出しのたびに `switchFuelHolder` を呼ぶ。またぐ前後でグローバルが同期されている前提（上の (1)）に頼る。作っている途中の入れ子の `instantiate`・`switchFuelHolder` を許すかは #270 で決める
- **#63・#72**: 常駐するプロセスは待機点型がよい（上の「規模」）

## 決めないこと

- システムコールの ABI（#63・#72・#73・#79、Component Model の層は #264・#266）
- 保存のバイト形式・インスタンスの状態の取り出しと書き戻しの口（#81・#82・#88）
- 他 MOD 向けの公開 API（#65）
- `MachineHost` に足すもの（#44・#120）、プロセスの生成と終了待ち・1プロセスの kill（#45・#46）、fd の意味（#58）、イベントキュー（#63）、init と起動（#49）、メモリの合計（#48）
- 公平な燃料配分と優先度（#47・#205）、性能値・上限の最終値（#251）

仮の値: 同時に実行するマシンは CPU のスレッド数の半分（最低 1）、スタック 16 MiB、プロセスの上限 64、ブロッキング型のプロセス 2,048、ワーカーのアイドル 30 秒、`ByteStream` の最初のバッファ 256 バイト、`fuelPerTick` 1,000,000。
