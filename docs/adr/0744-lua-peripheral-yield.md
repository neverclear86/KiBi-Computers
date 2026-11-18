# 0744: Lua の peripheral.call をコルーチンの中で yield させる

- 状態: 採用
- Issue: #744（task）。前提: ADR 0077（kibi:device/bus と p1 の import）、0094（Lua 向けの KiBi API）、0095（Lua のコルーチンでイベントを待つ）
- 方針: オーナーのコメント（#95・#94）「Lua での書き方はできるだけ CC: Tweaked に合わせる」。CC のコードは写さず、振る舞いだけを合わせる

## 決めたこと（要約）

- コルーチンの中（yield できるところ）の `peripheral.call` と `wrap` の関数は、メインスレッドのメソッドの結果を待つ間、CC と同じく `"task_complete"` を yield する。その間にほかのコルーチン（`parallel` など）が動く
- 使う import は、ADR 0077 の p1 の `[method]device.start`・`[method]pending-call.take`・`[resource-drop]pending-call` と `poll_oneoff`（`FD_READ`）だけ。**ABI・カーネル・保存形式・公開 API は変えない**。変えたのは `lua.wasm`（C）だけ
- メインスレッド（メインのチャンクと REPL）は今までどおり `invoke` でその場で待つ（ADR 0094）。メインかどうかは `lua_pushthread` で分ける（ADR 0095）

## 呼び出しの流れ（コルーチンの中）

1. 引数を写す（今までと同じ。上限の検査も同じ）
2. open →（`wrap` の関数なら）`info` で種類を照合 → `start` → device の fd をすぐ drop（pending-call は device の fd から独立している。`Queued(address, method, args)`）
3. すぐ1回 `take` する。直接のメソッドと、その場で失敗するもの（外れた・メソッドがない・種類が違う）は `start` の中で終わっているので、ここで値を返すかエラーにする（**yield しない**。CC も直接のメソッドは yield しない。`coroutine.wrap` でイテレーターを作るコードを壊さない）。メインスレッドのメソッドでも、`start` と直後の `take` の間にサーバーの tick が入ってメソッドが終わっていれば、ここで値を返す（yield しない。結果は同じで、待たずに済んだだけ。CC は常に yield する）。だから、メインスレッドのメソッドの呼び出しが必ず yield するとは約束しない。テストで yield を確かめるときは、Lua が動いている間は tick を進めない（`LuaKibiApiTest` の `finishTickingWhileWaiting`）
4. 終わっていなければ、task id（単調に増やす 64 ビット。タイマーの id とは別）を取り、`[task id]` をスタックの底に置いて `"task_complete"` を `lua_yieldk` で yield する
5. 続き（`call_k`）: 次のどれか
   - `"task_complete", id, true, 値...`（id は Lua の数値の等しさで比べる。float でも合う）: 値を返す
   - `"task_complete", id, false, 文`: 文に呼んだ Lua の行の位置（`luaL_where(L, 1)`）を付けてエラーにする（今の同期の呼び出しと同じ文になる）
   - `terminate`: `Terminated`（位置なし）のエラー。Lua の側は呼び出しを取り消さない（CC と同じ）。pending-call の fd は表に残るので、プログラムが続けば、メソッドは動き、結果は後で迷子の `task_complete` として届く。**プログラムが `Terminated` で終わる（ふつうの terminate の流れ）と、プロセスの終了で表の fd が閉じられる。カーネルは pending-call の最後の fd が閉じられると、まだ実行していないメインスレッドの呼び出しを列から外す（#532）ので、その tick でまだ走っていない呼び出しは実行されない**（`waitForAny` で残したコルーチンの呼び出しも、直後にプログラムがふつうに終われば同じ）。すでに実行した呼び出しは取り消されない。CC: Tweaked は積んだメインスレッドの呼び出しをプログラムが終わっても実行するので、利用者から見える違い（peripheral(3)・parallel(3) の man に書く）
   - ほか: 捨ててまた `"task_complete"` を yield する

## 待っている呼び出しの表

- `kibi_lua_peripheral.c` の C の静的配列（`.bss`）。要素は pending-call の fd・task id・メソッドの名前（64 バイト。後から返る `no-such-method` の文に使う。復元の後の積み直しは address だけで引き直すので、メソッドがないと分かるのが後になることがある）。数はカーネルの上限 `DeviceLimits.MAX_PENDING_CALLS` と同じ 16
- 線形メモリにあるので、スナップショットにそのまま入る。fd 番号は保存をまたいで変わらない（`queue_fd` を static に持つのと同じ）。起動時には何も作らない（起動の RAM は増えない）
- **終わっていない呼び出しを表に入れるのは `take` の直後で、そこから yield までは Lua の確保（longjmp しうる）を伴う API を呼ばない。** yield する `"task_complete"` の文字列は、`lua_pushliteral`（内部化の確保や GC の 1 歩で longjmp しうる）で積むと、表に入れた後にメモリ不足になって「エラーが返ったのにメソッドは動いた」と持ち主のいない `task_complete` が残るので、device を開く前（`call_device`）に積んでおき、表に入れた後は `lua_pushvalue`・`lua_pushinteger`・`lua_copy`・`lua_settop`（確保も GC もしない）だけで yield する。 その場で終わった呼び出しは表に入れず、pending-call を閉じてから値に写す（`invoke` と同じ。写す途中のメモリ不足や入れ子の回収で、持ち主のいない `task_complete` や二重の drop が起きない）。写す間は `kibi_lua_arena_hold` で囲む（`lua_pcall` の外で `release` する）。囲んでいる間の os の `collect` は、タイマーだけ移し、arena を使うもの（`take_kernel_events` と `kibi_lua_tasks_collect`）をしない。`__gc` から `os.queueEvent` などで入れ子の回収が走って、arena（写している結果の場所）を戻して取り直したり、表を詰めたりするのを止める（#755 のレビュー）。`take_kernel_events` がイベントを写す間も同じく囲む。囲んでいる間の `__gc` からメインスレッドで `os.pullEvent` などで待つと、取り出せるものがないので `cannot wait for events while a result is being copied` のエラーにする
- **import の結果（arena にある）を受け取ってから arena を戻すまでの間に Lua の確保があるところは、すべて `lua_pcall` の中で写し、`kibi_lua_arena_hold` で囲む**（#756・#759）。`kibi_lua_peripheral.c` の `copy_held` が共通の形（関数と lightuserdata 2 つを積む → hold → `lua_pcall` → release → arena を戻す → 失敗なら投げ直す）で、`invoke` の結果、`call-error` の文、`list-devices`・`methods`・`info` の結果、`take` の結果（その場で終わったもの・回収）を写すところはすべてここを通す。import の結果を受け取ってから `copy_held` までは、Lua の確保を伴う API を呼ばない（`lua_pushcfunction`（軽い C 関数）と `lua_pushlightuserdata` は確保しない。スタックの余裕は import の前に `luaL_checkstack` で確かめる）。`call-error` の文は、写して arena を戻してから `luaL_where` で位置を付ける（`luaL_where` の確保で走る `__gc` に、写す前の文を壊させない）
- **囲んでいる間に呼ばれた peripheral の関数（`__gc` からの入れ子の呼び出し）は、`cannot call a peripheral while a result is being copied` のエラーにする**（#756。位置なし。`os.pullEvent` のエラーと同じ形）。arena を戻す前・fd を開く前（`open_device`・`push_device_list` の先頭）で止めるので、fd も漏れない。入れ子を許す（hold の間は arena を戻さない）案は採らない: 入れ子の `kibi_lua_reserve` が RAM の少ないときに先取りの場所を取り直して外側の結果を壊すうえ、入れ子の結果が外側の release まで溜まり、RAM は解放しても減らない（#370）ので上限まで食いつぶしうる。写す間だけ GC を止める（`LUA_GCSTOP`）案も採らない: 利用者の `collectgarbage("stop")` の状態との兼ね合いと、`__gc` の中では `lua_gc` が効かないことで、hold と別の仕組みが並ぶ。`getName`・`bytes` と、wrap したテーブルを渡した `getType`・`hasType` は arena に触らないので、囲んでいる間も使える
- **表から消す（pending-call を drop する）のは、結果を Lua の値に写し終えてから。** 写す途中でメモリ不足になっても、`take` は何度でも同じ結果を返す（ADR 0077）ので、次の回収でやり直せる（ADR 0094 の「読んだらすぐ drop」は、記録の残らない同期の呼び出しの話）。消すときは後ろを詰めて、積んだ順を保つ
- `__gc` では閉じない（ADR 0094 が退けた理由と同じ）
- pending-call の fd は、その場で終わった・回収して写し終えた・プロセスが終わった（カーネルが閉じる）のどれかで閉じる

## 回収と待ち

- os の `collect`（`fire_timers` → `take_kernel_events` → `kibi_lua_tasks_collect`）が、表の各要素を `take` し、終わったものを `"task_complete", id, true, 値...` か `"task_complete", id, false, 文`（位置なし）にして FIFO に積む。積むのは `lua_pcall` の中で、失敗したら arena を戻してエラーを投げ直す（`take_kernel_events` と同じ形）
- メインスレッドの `pull` の `poll_oneoff` は、キューと時計に加えて、表の fd を `FD_READ` で購読する（購読は最大 2＋16 個。`WasiP1Poll` の上限 1,024 より十分小さい）。これがないと、yield したコルーチンを起こすものがなくなる
- メインスレッドの `os.sleep` の待ちは、表の fd を購読しない（回収しないので、終わった呼び出しがあると busy loop になる）
- **表が空なら、回収も購読も何もしない**（`take` も `reserve` も呼ばない）。呼び出しのない台に費用を出さない
- `os.queueEvent` も同じ `collect` を通る（その時点で届いているものの後ろに積む順を保つ。結果を写している途中の `__gc` から呼んだときは、カーネルのイベントと終わった呼び出しを移さずに積む）

## その場で待つところ（CC と違う）

- **yield できない文脈**（コルーチンの中の `string.gsub`・`table.sort` のコールバック、`__gc` など）: CC はエラーにするが、`invoke` でその場で待つ。`invoke` はイベントを取り出さない（キューに残る）ので、ほかのコルーチンのイベントを奪わない。`os.sleep` と同じ扱い（ADR 0095）
- **表がいっぱい（16 個）**: まず回収して空きを作り、それでもいっぱいなら `invoke` でその場で待つ（`invoke` の待ちはカーネルの上限に数えない。ADR 0077）。`start` が `kernel(again)` を返したときも同じ。エラーにはしない（`parallel` で 17 個並べただけで壊れないように）。正しさは保たれ、そのコルーチンの間だけほかが止まる
- `waitForAny` で残したコルーチン・GC されたコルーチンの呼び出しも、回収されるまで枠を使う。回収はメインスレッドがイベントを待つたびと、枠が尽きたときに行う

## wrap の直接のメソッド

`start`＋`take`＋drop は、fd と pending-call を作る（ADR 0077 は直接の `invoke` の道を軽く保つと決めている）。`wrap` はもう `methods` を読んでいるので、メソッドごとに「メインスレッドのメソッドか」を上位値に持たせ、**直接のメソッドは今の `invoke` で呼ぶ**。印が古くなっても（同じ種類の別のデバイスがつながっても）どちらの道でも正しく動く（直接のものを `start` で呼べばすぐ `take` で返る。メインのものを `invoke` で呼べば yield せずに待つだけ）。印を持たない `peripheral.call(name, method)` は、コルーチンの中では常に `start` → `take` で呼ぶ。

## RAM の約束（ADR 0094）

- import の前に `reserve`（`reserve_or_close`・`kibi_lua_check_memory`）を呼ぶ。コルーチンの道は、`start` の**前に** `take` の分（`INVOKE_RESULT_BYTES`。`start` のエラーの文もこの中に入る）まで確かめる。足りなければ、メソッドを動かす前に `not enough memory`（`invoke` と同じ。「エラーが返ったのにメソッドは実行された」を作らない）。`start` の後は arena を戻さず、取った場所を `take` に使う（間に Lua の確保はない）。回収（`kibi_lua_tasks_collect`）は、要素ごとに `INVOKE_RESULT_BYTES` を確かめ、足りなければ要素を表に残して `not enough memory`（次の回収で `take` し直す。メインスレッドの `pull` は毎回エラーになる）
- 写し終えたら `arena_reset` する

## 保存・復元

保存の形は変わらない（ADR 0077 の pending-call の形と、線形メモリの表のまま）。

- 保存したとき実行中の呼び出しは `Done(failed("interrupted"))` で戻る。回収されて `"task_complete", id, false, "interrupted"` になり、待っていたコルーチンでは `<位置>: interrupted` のエラーになる。メソッドは1回だけ走る
- `Queued` のものは、戻すと `redispatchRestoredDeviceCalls` が積み直す。`Done` はそのまま戻る
- `lua.wasm` の import が増え、ModuleHash が変わるので、古い `lua.wasm` で保存した Lua のプロセスは `MODULE_MISMATCH` で冷えた起動に落ちる（ADR 0088。v0.1.0 の前なので互換は要らない）

## 待機点

`lua.wasm` は待機点型（WAIT_POINT）のまま。止まりうる import は `poll_oneoff` と `invoke` だけで、足した `start`・`take`・`[resource-drop]pending-call` は止まらない。yield したコルーチンの C のフレームは外れているので、巻き戻すスタックは増えない（ADR 0095）。

## 規模（1,000 台）

`LuaParallelScaleTest`（`-Pkibi.luaReplMachines=1000`）に、2 つのコルーチンがメインスレッドのメソッドの呼び出し（1 回と 2 回）と `os.sleep(1)` をくり返す場合を足した。

| 場合 | 増えたスレッド | tick の時間（平均・最大） | 約 3 秒の CPU 時間 |
| --- | --- | --- | --- |
| `parallel`（sleep・pullEvent。#95 と同じ） | 4 | 19 us・57 us | 7.4 秒 |
| `parallel` ＋ `peripheral.call`（呼び出し 11,436 回） | 4 | 773 us・3.6 ms | 13.8 秒 |

- 呼び出しを待つ台も `poll_oneoff` の中で巻き戻り、スレッドを持たない（BLOCKING に落ちると台数分増える）
- tick の時間の増えは、毎秒約 3,000 件のメインスレッドのメソッドを tick の中で動かす分。1 台が同時に積めるのは 16 個までで、tick あたりの件数はマシンごとの `maxHostCallsPerTick` とサーバー全体の時間の予算（ADR 0356）が抑える
- 待つだけの台には、tick ごとの処理を足していない（回収は Lua が起きたときだけ）

## テスト

- `LuaKibiApiTest`:
  - `parallel` の中で、返さないメインスレッドのメソッド（`defer`）を待つ間に、もう一方のコルーチンの `sleep` が進む。`wrap` の関数と `call` の両方
  - 自前のコルーチン: 直接のメソッドは yield しない。メインのメソッドは `task_complete` を yield し、id の合わないものとほかのイベントを捨て、float の id でも合う。途中の nil を含む値。失敗は `false` と文で届き、続きで呼んだ行の位置が付く。`terminate` は `Terminated` で、プログラムが続けば呼び出しは取り消されずに後で届く（プログラムが終わると、まだ実行していない呼び出しは実行されない）。その場の失敗（メソッドがない・外れた）は yield せずにエラー。yield できない文脈（gsub）はその場で待つ
  - 20 個のコルーチンが 15 回ずつ（300 回。fd の上限 256 を超える）呼んでも正しく動き（16 個を超えた分はその場で待つ）、`waitForAny` で残したコルーチンの呼び出しも後で回収されて、プロセスの device・pending-call の fd が 0 に戻る
  - 今までの「コルーチンの中でメインのメソッドを呼んでも yield しない」テストは、`task_complete` を yield する形に書き直した（CC と同じ）
  - RAM の上限の近く（コルーチンの道）: take の結果の場所が取れなければ `not enough memory` の Lua のエラーになり、メソッドは走らず、`CRASHED` にならず、fd が表に残らない。回収の `reserve` が失敗したときも Lua のエラーで、`CRASHED` にならず、終わった結果は表（fd）に残る。RAM は解放しても減らず、一度絞ると大きな場所は取れなくなる（#370）ので、「空いた後の回収で同じ結果を受け取る」ところは、このテストでは確かめられない（`take` が何度でも同じ結果を返すこと自体は ADR 0077 のテストが見る）
  - `start` が `kernel(again)` を返す（カーネルの上限 16 個に、C の表の外から当てる）と、コルーチンの中でも `invoke` でその場で待つ（`call` も `wrap` の関数も）
  - `terminate` でプログラムが終わると、まだ実行していない呼び出しは実行されない
- `LuaKibiApiRestoreTest`: `parallel` の中で `defer` の実行中に保存して戻すと、そのコルーチンは `interrupted` のエラーになり、メソッドは1回だけ走り、もう一方のコルーチン（`restored` を待つ）も続く。1 tick に 1 件しかメインスレッドの呼び出しを実行しない設定で、12 個のコルーチンが呼び出しを積んだ直後（`Queued` のまま列に残っている）に保存して戻すと、戻した後に積み直されて実行され、どのコルーチンも結果を受け取り、メソッドは 1 回ずつ（12 回）走る
- `LuaParallelScaleTest`: 上の規模
- `LuaWasmTest`: 素の Endive に足した import のスタブ
- GameTest（NeoForge 1.21.1 `StandardOsGameTests.luaPeripheralCallRunsInsideParallel`）: 標準 OS の `lua -e` で、`parallel` の中から隣の画面のメソッドを `peripheral.call`（start → take の道）と `wrap` の関数で 300 回ずつ呼びながら、もう一方で `os.sleep` する。ゲームの中のデバイスにはまだメインスレッドのメソッドがないので、yield する道は core のテストで確かめる

## 範囲に入れなかったもの

- p2 の `bus-poll`・新しい import・`MAX_PENDING_CALLS` の変更（ABI と仮の値に触れるため）
- メインのチャンクをコルーチンで動かすこと（ADR 0095）
