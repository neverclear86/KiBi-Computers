# 0043: カーネル全体の設計

- 状態: 採用
- Issue: #43（task）。前提: ADR 0027（スレッド）、0028（燃料）、0029（プレイヤーの WASM）、0030（待機点での保存）、0260（Component Model）
- 後続: #44・#45・#46・#47・#58・#59・#63・#72・#81・#82・#120、Epic #259 の各層
- 番号は Issue 番号にそろえている
- 追記（#685）: p2 の `kibi:*` の pollable は、ハンドルのエントリに `WaitKey` を持たせず、親（stream か `kibi:*` の resource）が指すオブジェクトの `WaitKey` で待つ形にした（ADR 0685）

## 決めたこと（要約）

- カーネルの単位は「マシン（コンピューター1台）」。マシンは `KernelState`（プロセス表・オブジェクト表・燃料）を1つ持つ。1プロセス = 1 WASM インスタンス（Component Model では、手持ちを持つコアインスタンスは1つ）
- p1・p2・p3 で共有する土台は `KernelObject`（オブジェクトIDと参照カウント）、`HandleTable`（fd の表。Component Model の resource のハンドル表は、番号の振り方が違うので別の型にした。ADR 0265）、待ち（`WaitKey` / `WaitRegistry` / `WaitSet`）、`ByteStream` の4つ。ABI 層は `ProcessDriver` を実装して、その上に乗る
- プロセスの動き方は `BLOCKING`（ホスト関数の中で止まる）と `WAIT_POINT`（export から戻って待つ）の2種類。保存できるのは `WAIT_POINT` で待っているときだけ
- スレッドは WASM のスタックがあるあいだだけ持つ。ブロッキング型は専用スレッド（サーバー全体の上限あり）、待機点型は共有のワーカーで step を動かし、待機点と step の境目ではスレッドを持たない
- 燃料の正はマシンの `KernelState.fuel`。CPU を取るときに遅延補充し（tick ごとに配るのと同じ値の閉じた式）、`tick()` は台数によらない
- 停止は `Thread.interrupt` に頼る。割り込みはスレッドの登録をカーネルロックの中で確かめてから、同じロックの中で送る
- `core.kernel` で `public` にするのは接続層が使う `MachineScheduler`・`SchedulerConfig`・`PauseHandle`・`Machine`・`MachineConfig`・`MachineState`・`MachineHost` だけ（#44 で `MachineEnvironment`・`MachineClock`・`EntropySource`・`DiskResolver` を足した。ADR 0044）。`pro.ikili.kibicomputers.api` には何も足さない

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
| `BLOCKING` | プロセスごとに専用スレッド `KiBi-m<マシン>-p<pid>` 1本。上限はサーバー全体の `SchedulerConfig.maxBlockingProcesses` と 1 台の `MachineConfig.maxBlockingProcesses`（#687。超えると `spawn` が `ResourceLimitException`） | `ProcessContext.block`（スレッドを持ったまま） |
| `WAIT_POINT` | 1回の step（作って `start`、または `resume`）を共有のワーカーで動かす。step の途中の燃料待ち・`awaitHost` の間はワーカーを持つ | `StepResult.Wait`。待っている間はスレッドを持たない |

- ワーカーのプールは `ThreadPoolExecutor`（コア 0、上限なし、`SynchronousQueue`、アイドル 30 秒）。上限を付けないのは、step の途中で燃料待ち・許可待ちのワーカーが仕事を塞いでデッドロックしないため
- スレッドはどれもプラットフォームスレッド・daemon・スタック 16 MiB（予約だけ。仮の値）
- 待機点型のプロセスが起こされたら、スレッドを持たない待ち手としてマシンの CPU の待ち行列に入る。順番が来たらワーカーへ渡す。ワーカーは許可を取り、燃料を確かめ、なければ許可と CPU を返して**スレッドを返し**、燃料待ちの列に入る
- ロックの順番は CPU → 許可 → カーネルロック。カーネルロックを持ったまま、待つ・WASM を呼ぶ・`MachineHost` を呼ぶ、のどれもしない（条件変数の `await` はロックを手放すので使う）。CPU の待ち行列・一時停止の数え・ディスパッチャーの列のロックは末端のロックで、カーネルロックの中から取ってよい
- CPU と許可は `ProcessRuntime.holdsCpu`・`holdsPermit` の印で、持っているものだけを1回だけ返す
- 許可を渡し終えた仕事をワーカーへ渡せなかったとき（スレッドを作れないなど）は、呼び手に投げず、許可を持ったまま取り残しの列に入れ、毎 tick と次にワーカーへ渡せたときに渡し直す（#870）。CPU を渡した先の待機点型の step（CPU の受け渡し・起こすとき・復元の取り込み）も、許可がその場で取れたときを含めて同じにする（#875。CPU はその step が持ち主のまま残り、動き出した step か、止めたときの `removeThreadless` が次へ渡す）。投げるのは spawn の直接の道（CPU が空いていて、その場で許可も取れたとき）だけで、取った許可を返してから投げ、spawn の巻き戻しが CPU を戻す（#629）
- ゲストからの入口のスタックの余裕（#828）: ゲストのどの深さからでも入る口（`ProcessContext.hostFunction` の包みと、燃料切れの処理）は、先頭で `StackHeadroom.ensure` を呼び、決まった量のスタックを使う再帰が通ることを確かめてから、カーネルロック・CPU・許可に触る。通らなければ何も触らずに `StackOverflowError` を投げ、プロセスだけが `call stack exhausted` で終わる。カーネルロックの中ではゲストを呼ばない（上の規則）ので、入口で確かめた分で、次にゲストを呼ぶまでのホストの区間がまかなえる。段数は、ホストの関数の包みと燃料切れの処理の入口が 32 段（JIT の後で約 6 KiB。1 回約 0.2 µs。前借り（#477）の間は燃料切れの処理が関数の入口・loop の先頭ごとに呼ばれるので、そこも 32 段だけ払う）。CPU・許可を返して取り直す道の手前は、どれももう 1 回 128 段（約 23 KiB。約 0.9 µs）で確かめる。置き場所は、燃料切れの処理がその道に入る直前（カーネルロックの中で、状態を変える前。通るたびに新しいスライスを受け取るので、スライスごと）と、ブロッキング型の止まって待つ口 `block`・`awaitHost`（`callOnMainThread` などを含む）の先頭（呼ぶたびに払う。`block` はすぐそろって待たないときも払う）。段数を縮めて確かめた結果（Windows・JDK 17）、ホストの関数の本体が `awaitHost` で CPU・許可を取り直す道は、包みが 2 段で落ち 8 段で通った。ところが CI（Linux・JDK 17）では、`awaitHost` の先頭に確かめがない（包みの 32 段だけの）とき、この道を本体にしたテストが止まった（何が残ったかは確かめられていない）。フレームの大きさは OS・JIT の段階で変わるので、ある環境で測った最小の段数の何倍という余裕は根拠にしない。取り直しの道の手前を 128 段にそろえ、足りなかったときは下の安全網で戻す。#858 で、ディスクの VFS の深い操作（道の解決はカーネルロックの中、I/O はロックの外でリースのロック）、端末の出力の流し込み（画面のデバイスのロック）、canon の lift・lower（realloc・post-return・lower した関数の lift し直し。巻き戻し・巻き直しの道は、`f` を呼ばない入口 `wind` の詰め物を境目の近くからずらして、境目の近くで確かめを通った回が巻き戻して最後まで終わることを確かめた。巻き戻し・巻き直しの最中に尽きた回は観測されていない。#903）、本物の p1 のモジュールの前借り・待ち点の道も確かめた。ディスクは、ロックの面だけでは足りない（本体の途中で尽きると、カーネルのロックも CPU も壊れずに、node の表・使用量・開いたままの枠がホストと食い違いうる）ので、本物のディスクの操作を、尽きる境目から先の幅を詰め物 1 段ずつ動かして、毎回の後にその食い違いを見る `DiskFileSystemStackExhaustionTest` を足した。入口の確かめを外した試験では、閉じる（`release`）の途中で尽きて枠が返らなかったので、ディスクの操作の入口（`io`・`release`）で、包みとは別に 128 段（`HOST_IO_FRAMES`）を、何も変える前に確かめる。fd の表から閉じる道は、`Vfs.releaseLater` が積んだ知らせを `Vfs.drainDeferred` が列から取り出してから `release` を呼び、`drainDeferred` は起動用のスレッドだけでなくファイルシステムに届く操作（`checkUnlocked`）からゲストのスレッドの深さでも走るので、`release` の確かめで投げると取り出した知らせが捨てられ、ホストのファイルと枠が返らない。そこで `drainDeferred` は、取り出す前に 144 段（`DEFERRED_RELEASE_FRAMES`。`release` の確かめまでの数段の分を足したもの）が残っているかを投げずに確かめ、足りなければ何も取り出さずに返す（残した知らせは、積んだときに頼んだ起動用のスレッドの仕事か、次の操作が行う）。深いスレッドが積んだ知らせを起動用のスレッドが行う道は、本物の WASI p1 の `path_open`・`fd_close` をスタックの底に近いところで繰り返す `P1DiskCloseStackExhaustionTest` が、Machine ごと通して確かめる。ディスクの入口の確かめの `StackOverflowError` を、呼び手が飲んではいけない（`Vfs.readDirectory` は、マウントの根の `stat` の失敗を node 0 にするが、受けるのは `Exception` だけで、`Error` は伝える）。`/tmp`（`TmpFileSystem`）の木と RAM の勘定の食い違いは、まだ確かめていない（#902）。どのテストも、尽きた位置（スタックトレース。区画の分け方は `SoePositionsTest` が確かめる）を、ロックを取る・放す途中（AQS・`Unsafe` の CAS を含む。この MOD のロックと、JDK のクラス経由の executor・列・NIO のロックの両方）か、確かめの中か、本体の中かに分けて数え、ロックの途中で尽きた回がないことと、毎回の後の状態（ロック・CPU・許可・コンソールの出力の予約）が戻ることを確かめる。今の段数では、JIT・冷えた道（Windows・JDK 17）とも、ロックの途中で尽きた回はなく（ディスクの入口の確かめを足した後の冷えた道・JDK 21・Linux・`-Xint` の測り直しは #903）、尽きた位置はほぼすべて確かめの中だった。段数を縮めると、8・8・32 段は p1 の待ち点の回でマシンのロックを取る途中（Windows）やディスクのリースのロックの途中（Linux。ディスクの入口の確かめを足す前）で尽きて落ち、16・16・64 段は（Windows・JIT で）ロックの途中の回がなく通った。今の段数（測った最小の 2 倍）のままにする（`StackHeadroom` の KDoc、`docs/load-test.md` の「#858 の結果」）。コンソールの出力の予約が、尽きたときに残る窓は #890。安全網として、step・専用スレッドの終わり（スタックが空いた浅いところ）で次を確かめる。(1) 入口より多くロック（マシンのロック・CPU・許可の中のロック）を持ったまま、または終わったプロセスが CPU を持ったままなら、ERROR を出し、マシンを不整合で CRASHED にする。マシンのロックは、このスレッドが持ったまま（再入して）CRASHED にしてから放し、CPU も CRASHED にした後で返す（放してから CRASHED にするまでの間に、tick・保存の写し・同じ台のほかのプロセスが、途中までしか変わっていない状態に入らないように。CPU・許可の中のロックは末端なので先に放す）。許可の中のロックだけなら放してログに出す。(2) 終わったプロセスのスレッドが、CPU・許可の待ち（`MachineCpu.acquire`・`PermitPool.acquire`）の途中で抜けて待ち手を残していたら、外してログに出す（残すと、後で返された CPU・許可が起こす相手のいない待ち手に渡り、台が止まる・サーバー全体の許可が減る。待ち手は、待っているスレッドごとに控えておく。渡された許可は次へ渡し、渡された CPU は (1) の持ち主のままとして扱う）。終わったプロセスが CPU を持ったままかは、同じ `rt` の次の step が別のワーカーで終わって（`finishLocked` の後）CPU を返す前の間を漏れと見誤らないよう、`rt.thread` が別のスレッドでないことをカーネルロックの中で確かめて決める。`finally` の中で取り戻そうとはしない（同じ深さなので、また溢れる）。JDK のロックの内部（AQS の待ち行列）の途中で尽きた壊れ方は、安全網では戻せないので、入口の確かめに頼る。3 つ目の入口として、メモリの勘定（`ProcessMemory.tryReserve`・`tryReserveBytes`）も先頭で 32 段を確かめる（#856）。ロックではないが、プロセス → マシン → サーバー全体の別々の CAS を重ね、ゲストの命令（初めて書いた区画・`memory.grow`・`table.grow`）から包みを通らずにどの深さからでも入るので、段の途中で尽きると勘定がずれる（ADR 0048）。返す側では確かめない（確かめで投げると、返すこと自体が飛ばされる）。この入口は、ホストの関数の本体からも入る（本体が新しい区画へ書く `memory.write*`・`commit`、`ctx.instantiate` が作るメモリ・テーブルの最初の分。どれもカーネルロックの外。コンポーネントの core インスタンスの固定費 `reserveCoreInstanceOverhead` はカーネルロックの中だが、プロセスの始まり（`load` → `loadComponent`）の浅いところからだけ来て、本体からは入らない）。そこでは、包みの確かめを通った後の本体の深さからもう 1 回 32 段を確かめるので、包みは通ったのに本体の途中（fd を開いた後・パイプから読んだ後など、カーネルの状態を変えた後）で `StackOverflowError` になる深さがある。何も数える前に投げるので勘定はずれず、プロセスは `call stack exhausted` で終わる（確かめがなければ、同じ深さで段の途中で尽きて勘定がずれる）。つまり、上の「入口で確かめた分で、次にゲストを呼ぶまでのホストの区間がまかなえる」は、本体が確保に入らず、ホストの I/O と表の付け替えが続かない区間についての記述で、確保に入る本体とディスクの操作は、入口ごとに自分で確かめる。カーネルのプロセスを通して、ゲストの命令と本体の `memory.write*`・`commit` から入る確保で、プロセスだけが終わり、終わりの後にマシン・サーバー全体の勘定が戻り、ほかの台が動き続けることを確かめた（`MemoryAccountStackExhaustionKernelTest`。確かめを外すと、マシンの勘定に数えた分が残って落ちる）。`ctx.instantiate` の道（失敗したときに作りかけの分を同じ深さで返す `catch` と、作り終えた後に `rt.instances` へつなぐまで）は、#884 で、入口（何も変える前）に 128 段（`INSTANTIATE_FRAMES`）の確かめを足した。返す側では確かめられず、同じ深さの `catch` はまた溢れるので、入口でまとめて払う。底に近い深さのすべてで 5 つの型を作らせる試験（`InstantiateStackExhaustionTest`・`InstantiateStackExhaustionKernelTest`）では、確かめを外してもずれは出なかった（返す `catch` は、数えた位置より浅いところで動くため。窓がないとは言えないので、JIT でフレームが変わる場合とロックの取り合いの道に備える）。`/tmp`（`TmpFileSystem` はマシンの `MemoryAccount` を直接呼ぶ）は、#883 で、変える操作の入口（モニターを取る前・何も変える前）に 32 段（`TMPFS_FRAMES`）の確かめを足した。勘定の 2 段に加えて自分の `usedBytes` と木を順に変えるので、確かめを外した試験（`TmpFileSystemStackExhaustionTest`。底に近い深さのすべてで 13 の型）では、`usedBytes` と木が食い違い（数えた塊が `size` の外に残る、作る前に数えた分が残る、消したのに返らない、置き換えた node が `nodes` から消えたまま木に残る）、`TMPFS` 節が読めなくなる回が出た。入れた後は食い違いは出ない（JDK 17・JIT に加えて、冷えた道・`-Xint`・JDK 21（Windows）でも、確かめが通らない深さで断られなかった回と食い違いは 0。`StackHeadroom` の KDoc）。`/tmp` は自分の分を確かめるので、ホストの関数の包みの 32 段が覆うのは、カーネルロックの区間と VFS が `/tmp` を呼ぶまでで足りる（`TmpFileSystemStackExhaustionKernelTest`）

## 燃料（ADR 0028 の載せ方）

- **正は `KernelState.fuel`。** インスタンスのグローバル `kibi_fuel` は、そのプロセスが CPU を持っている間だけ使う手持ち。CPU を持っていないときは常に 0（CPU を持っている間の `KernelState.fuel` は、その tick の残り。追記 #47、ADR 0047）。手持ちを持つのは `ProcessRuntime.fuelHolder` 1つ（作っている途中は仮の holder）
- **遅延補充**: 規則は「tick ごとに `fuel = min(fuel, 0) + fuelPerTick`」。メインスレッドで配らず、`lastRefillTick` から進んだ k tick 分を閉じた式でまとめて当てはめる（`FuelAccount.refilled`）。`fuel ≥ 0` なら `fuelPerTick`、負なら `fuel + k·fuelPerTick`（0 以上になるまで）、それ以後は `fuelPerTick`
- `refill(now)` を呼ぶのは、マシンの燃料を読み書きする口のすべて: CPU を取る、CPU を返す2つの口、例外で抜けたとき（`holdsCpu` のときだけ）。マシンの燃料を読み書きするのは CPU を持つプロセスだけで、CPU を持っている間の `fuel` はその tick の残り（0 以上。#47 より前は 0）
- **CPU を取る**: CPU → 許可 → カーネルロックの中で補充し、停止の印・一時停止・`fuel > 0` を確かめて `grant = min(fuel, fuelSlice)`、`fuel -= grant`（1回に渡すのは最大でスライス。追記 #47: スライスを使い切ったときの譲り方は ADR 0047）
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
- 追記（#270）: コンポーネントは、core インスタンスがみな燃料のグローバルを共有する形にした（ADR 0263）ので、手持ちを移す口は要らなくなった。`switchFuelHolder` は消した（本番の呼び出し元はなかった）。作っている途中の入れ子の `instantiate` は、今後も許さない（コンポーネントの core インスタンスは計画だけが作り、`ctx.instantiate` はコンポーネントのプロセスでは使えない。p1 にも本番の呼び出し元がない）

### 燃料を計測しないモジュール

既定では `spawn` が断る（`IllegalArgumentException`）。テスト専用の `SpawnOptions.allowUnmetered` のときだけ動かす。燃料を使わず、一時停止できない（計算中の `pauseAll` は必ず `TimeoutException`）。止めるのは割り込みだけ。

## 待ち

- 待ちの単位は `WaitKey(objectId, condition)`（保存できる値）。`WaitCondition` は今は `READABLE`・`WRITABLE`
- オブジェクトの側は `canWait`・`isReady` を答え、状態を変える操作は引数の `WakeSink` に自分の `WaitKey` を知らせるだけ（`WakeSink` をフィールドに持たない）
- 実行時の側は `WaitRegistry`（`WaitKey` → `Waiter`）。登録・解除・`wake` はカーネルロックの中。`Waiter.wake()` は印を立てて起こすだけ（印が立っている間は何もしない）
- `WaitSet` の登録は、待ちが終わると決まるまで外さない。**起こされたのに、そろった key が空なら待ち直す**（印を下ろす）。カーネルは `resume` にも `block` の戻り値にも空の並びを渡さない。待機点型は `resume` を呼ばず、許可も燃料も使わずに `WAITING_EVENT` に戻る
- 閉じられたオブジェクト（参照カウント 0）・見つからないオブジェクトの key は「そろった」扱い。待ちは参照カウントを持たない
- 復元のときは `WaitRegistry` を空から作り、`Process.wait` から `WaitSet` を作り直す
- Component Model: p2 の `pollable` はハンドルのエントリに `WaitKey` を持たせず、親（stream か `kibi:*` の resource）が指すオブジェクトの `WaitKey` を `poll` のたびに引いて待つ（#685 で、最初の「エントリに `WaitKey` を1つ持たせる」から変えた。ADR 0685）。p3 の waitable-set は `WaitKey` の並びを持つ `KernelObject` にする（#266）

## 一時停止と停止

- `pauseAll(timeoutMillis)` は一時停止の印を立て、実行中（燃料を受け取って動いている）のプロセスがなくなるまで待つ。プロセスは CPU を取るところで止まる。止まるまでの上限は手持ち（最大でスライス `MachineConfig.fuelSlice`。追記 #47、ADR 0047。#47 より前は `fuelPerTick`）を使い切るまで。時間切れなら一時停止を解いてから `TimeoutException`
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

#44 で足したもの（環境、コンソール出力・ビープ、メインスレッドでのホスト呼び出し、デバイスの変化の知らせ）は ADR 0044 に書いた。

- 接続層 → カーネル: `tick()`（毎 tick）、`createMachine`、`Machine.stop()`・`close()`、`pauseAll()`、`awaitTermination`
- カーネル → 接続層: `MachineHost.onStateChanged`。`tick()` の中でメインスレッドから、ロック・CPU・許可を持たずに呼ぶ。状態の変化はマシンごとの通知の列に積み、通知のあるマシンだけを `tick()` が配る

## 規模（目安 1,000 台）

| コスト | 抑え方 | 1,000 台のときの見込み |
| --- | --- | --- |
| OS スレッドの数 | 待機点型は step の途中だけ。ブロッキング型は1プロセス1本で、上限はサーバー全体と1台の `maxBlockingProcesses`。ほかにディスパッチャー1本 | 待っているだけの待機点型の台は 0 本 |
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
- マシン（`Machine.isAtWaitPoint`）は、全プロセスが待機点にいて、さらに起動の途中でも再起動の待ちの途中でもないときだけ待機点（#365、ADR 0049）

## 申し送り

- **#38**: カーネルは次の2つに頼る。(1) `kibi.fuel` 以外のホスト関数を呼ぶ前、export から戻る前、start 関数から戻る前に、ローカルの残りがグローバルへ書き戻され、ホスト関数から戻った後に読み直されること。(2) `kibi.fuel` の戻り値が新しい残りとして使われること。次の2つには頼らない: (3) `kibi.fuel` の呼び出しの前後でグローバルが同期されていること、(4) トラップで抜けたときにグローバルが同期されていること
- **#270**: 手持ちを持つのは実行しているコアインスタンス1つだけ。Canonical ABI の層は、インスタンスをまたぐ呼び出しのたびに `switchFuelHolder` を呼ぶ。またぐ前後でグローバルが同期されている前提（上の (1)）に頼る。作っている途中の入れ子の `instantiate`・`switchFuelHolder` を許すかは #270 で決める
  - 追記（#270）: 手持ちを移す形は採らず、core インスタンスで 1 つのグローバルを共有する（ADR 0263）。`switchFuelHolder` は消し、作っている途中の入れ子の `instantiate` は許さないままにした。共有のグローバルも上の (1)（ホスト関数・CALL 系の前後の書き戻しと読み直し）に頼る。例外で抜けたときにグローバルを読まないのも同じ
- **#63・#72**: 常駐するプロセスは待機点型がよい（上の「規模」）

## 決めないこと

- システムコールの ABI（#63・#72・#73・#79、Component Model の層は #264・#266）
  - 追記（#73）: 独自拡張の WIT と p1 向けの import の導き方は ADR 0073
- 保存のバイト形式・インスタンスの状態の取り出しと書き戻しの口（#81・#82・#88）
- 他 MOD 向けの公開 API（#65）
- `MachineHost` に足すもの（#44・#120）、プロセスの生成と終了待ち・1プロセスの kill（#45・#46）、fd の意味（#58）、イベントキュー（#63）、init と起動（#49）、メモリの合計（#48。→ ADR 0048）
- 公平な燃料配分と優先度（#47・#205。追記: マシンの中の配り方は ADR 0047。優先度とサーバー全体の配分は残り）、性能値・上限の最終値（#251）

仮の値: 同時に実行するマシンは CPU のスレッド数の半分（最低 1）、スタック 16 MiB、プロセスの上限 64、ブロッキング型のプロセス 2,048、ワーカーのアイドル 30 秒、`ByteStream` の最初のバッファ 256 バイト、`fuelPerTick` 1,000,000。
