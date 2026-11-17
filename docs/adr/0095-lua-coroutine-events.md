# 0095: Lua のコルーチンでイベントを待つ（pullEvent の yield と parallel）

- 状態: 採用
- Issue: #95（task）。前提: ADR 0094（Lua 向けの KiBi API）、0329（待機点への変換）、0088（保存の版が合わないときの扱い）
- 方針: オーナーのコメント（#95・#94）「Lua での書き方はできるだけ CC: Tweaked に合わせる」。CC 本体には依存しない（CC の Lua をコピーせず、振る舞いだけを合わせて自分で書く）

## 決めたこと（要約）

- コルーチンの中の `os.pullEventRaw(filter)` は、CC と同じく `filter`（nil でもよい）を yield し、resume で渡された値を**そのまま**返す。フィルターをかけるのは resume する側（`parallel` か、ユーザーのスケジューラー）
- `os.pullEvent` も同じく yield し、resume されたのが `terminate` なら `Terminated`（位置なし）のエラーにする
- コルーチンの中の `os.sleep` は、`startTimer` して `"timer"` を yield し、id が合う `timer` が来るまでくり返す（CC と同じ。そのコルーチンにとって、待つ間のほかのイベントは捨てる）
- メインスレッド（メインのチャンクと REPL）は今までどおり C でその場で待つ（ADR 0094）。`lua.c` は直さず、メインのチャンクをコルーチンで包まない
- `parallel.waitForAny`・`waitForAll` を足した（グローバルと `package.loaded.parallel`）
- **新しい import・カーネルの口・保存形式・公開 API は足していない。** FIFO・タイマーの表・コルーチンはどれも線形メモリの中にあり、スナップショットにそのまま入る

## メインスレッドかどうか

- `lua_pushthread`（メインなら 1）で分ける。`lua_isyieldable` だけでは分けない（メインスレッドは常に yield できないので、メインとコルーチンの中の yield できない文脈を見分けられない）
- コルーチンの中で yield できない文脈（`string.gsub`・`table.sort` のコールバック、`__gc` など）では、`pullEvent`・`pullEventRaw` は Lua の `attempt to yield across a C-call boundary` のエラーになる（CC と同じ）。その場で待つ形にはしない。共有の FIFO から、ほかのコルーチンが待っているイベントを奪うため
- **`os.sleep` だけは、yield できない文脈ではその場で待つ**（CC はエラーにする）。時計しか見ずイベントを取り出さないので、ほかのコルーチンのイベントを奪わない。man に違いとして書いた
- メインスレッドの `os.sleep` は、今までどおりイベントを捨てない（ADR 0094）。コルーチンの中では捨てる（CC と同じ）。どちらも man に書いた

## C で yield するときの形

- `lua_yieldk` の前に `lua_settop` で整え、残したい値は yield する値の下に置く（resume すると、yield した値が resume の引数に置き換わる）
- `sleep` は、タイマーの id（64 ビット）をスタックの底に置いて `"timer"` を yield し、続き（`sleep_k`）で id と resume の値を比べる。`lua_KContext` は wasm32 では 32 ビットなので id を入れない
- `pullEvent` の `terminate` の確かめは続き（`pull_event_k`）で行う。`pullEventRaw` は続きを持たない `lua_yield`（resume の値がそのまま戻り値になる）
- `terminate` で `sleep` が終わるときは、そのタイマーを表から外す（CC は外さないが、鳴らないタイマーを残さない方が軽い。外から見える違いはない）
- yield する道でもイベントのキューを開く（`pullEvent` を呼んだ時点でイベントを待つ意図がある。ADR 0094 の「読まないプロセスは開かない」と矛盾しない）。`parallel` が初めてメインで待つまでに来たイベントを失わないため

## terminate とフィルター

メインスレッドの `pullEvent`・`pullEventRaw` のフィルターも、`terminate` を素通りさせるようにした（CC のスケジューラーと `parallel` と同じ）。今は `terminate` を出すものはない（ADR 0094）ので、今の動きは変わらない。

## parallel

- 振る舞い: 引数はすべて関数（違えば `bad argument #n (function expected, got ...)`。動かす前に全部確かめる）。最初は引数なしで順に resume する。コルーチンごとに yield した値をフィルターとして覚え、イベントは、フィルターが nil か名前が合うか `terminate` のコルーチンにだけ渡す。エラーは `error(msg, 0)` で投げ直す（関数の中の位置のまま。テーブルのエラーもそのまま）。`waitForAny` は最初に終わった関数の番号を返し、`waitForAll` は何も返さない。引数がないときは、`waitForAny` は 0 を返し、`waitForAll` はすぐ戻る
- `waitForAny` の後に残ったコルーチンは `coroutine.close` しない（CC と同じ。GC に任せる）。残ったコルーチンの `sleep` のタイマーは、1回鳴れば表から消える
- 次のイベントは、`os.pullEventRaw` の C 関数（`kibi_lua_pull_raw`）を直接呼んで取る。メインスレッドならその場で待ち、コルーチンの中なら nil を yield する（入れ子の `parallel` と、ユーザーのスケジューラーの中でも動く）。ユーザーが上書きした `os.pullEventRaw`（CC でよくある `os.pullEvent = os.pullEventRaw` など）は通さない
- 本体（スケジューラーのループ）は Lua で書き、C の文字列として `lua.wasm` に入れる。初めて `waitForAny`・`waitForAll` を呼んだときに `luaL_loadbufferx(..., "t")` で読み、レジストリにしまう。起動時に作るのは C の関数 2 つのテーブルだけ（起動の RAM を増やさない。`WrittenMemoryTest`・`LuaMemoryLimitTest` の 192 KiB に収まる）。`coroutine.create`・`resume`・`status` と `table.pack`・`unpack` は、読むときに `package.loaded` から受け取る（ユーザーがグローバルを差し替えても動く）
- C の入口（`l_wait`）は本体を `lua_callk` で呼ぶ（中のコルーチンが yield をまたげるように）
- C だけで書く（`lua_resume` と続きの関数）形もあるが、入れ子のときの続きが込み入る。Lua で書くと CC との一致を確かめやすく、起動のコストも同じ

## 待機点と C のスタック

- yield の方式なら、メインスレッドが `poll_oneoff` で待つ時点では、すべてのコルーチンが yield 済み（C のフレームは longjmp で外れている）。巻き戻すのはメインの呼び出しの連なり（`parallel` の本体 → `kibi_lua_pull_raw` → `poll_oneoff`）だけで、退避するスタック（`AsyncStack.MAX_VALUES`）と C のスタック（256 KiB）は、コルーチンの数・入れ子の深さでほとんど増えない
- Lua の yield は `luaD_throw`（exnref の throw）で動く。足した C で待機点への変換に断られていないこと（`lua.wasm` が WAIT_POINT のまま）は、`LuaReplTest`・`StandardOsLuaTest`・`AsyncFuelTest`・`LuaParallelScaleTest` で確かめる

## 保存・復元

- 保存の形は変わらない。コルーチンの `lua_State` とスタック、`parallel` のフィルターの表は Lua のヒープ（線形メモリ）にある
- `lua.wasm` のバイト列が変わったので ModuleHash が変わる。古い `lua.wasm` で保存した Lua のプロセスは `MODULE_MISMATCH` で冷えた起動に落ちる（ADR 0088）。v0.1.0 の前なので互換は要らない

## CC との違い（man に書いたもの）

- メインのプログラムはコルーチンで動かない。トップレベルの `coroutine.yield()` はイベント待ちにならずエラーになる（`lua.c` を直さない。REPL の `fd_read` の待ちとも合わないため）
- メインスレッドの `os.sleep` はイベントを捨てない（ADR 0094）
- yield できない文脈の `os.sleep` はその場で待つ
- `peripheral.call` は yield しない。CC では、メインスレッドのメソッドは `task_complete` を待って yield し、ほかのコルーチンが動く。KiBi の `invoke` はプロセスごと待つ（最大で約 1 tick。その間に来たイベントはカーネルのキューに残る）。p1 の `[method]device.start` → `poll_oneoff` → `take`（ADR 0077）を使えば ABI を変えずに yield する形も作れるが、待つ fd の管理と復元のときの積み直しが絡むので、#95 には入れず #744 にした（→ ADR 0744 で yield するようにした。この違いはなくなった）

## 規模（1,000 台）

`LuaParallelScaleTest`（`-Pkibi.luaReplMachines=1000`）: 1,000 台が `parallel.waitForAll` の中で `os.sleep(1)`・`os.pullEvent` と、入れ子の `parallel.waitForAny` の `os.sleep(2)` のループで待つ。

- 増えたスレッドは 4（ワーカーの分。台数に比例しない。BLOCKING に落ちると台数分増える）
- 約 3 秒の間（1 台あたり約 4〜5 回起きる）の JVM 全体の CPU 時間は約 6.4 秒。比べるために、メインスレッドの `while true do os.sleep(1) end`（1 台あたり約 3 回起きる）を 1,000 台で測ると約 3.0 秒。起きる 1 回あたりの CPU 時間は、`parallel` を通しても大きくは変わらない
- `earliest_timer` は呼ぶたびにタイマーの表をなめる（O(n)）が、鳴ったタイマーは消えるので、CC の書き方（`sleep` のたびに `startTimer`）でも n は小さいまま

## テスト

- `LuaKibiApiTest`: コルーチンの中の `pullEventRaw` が filter を yield し、resume の値をそのまま返すこと（0 個、途中と末尾に nil、`n` 付き）。`pullEvent` の `terminate`。yield できない文脈でのエラーと、`sleep` がその場で待ってイベントを捨てないこと。コルーチンの中の `sleep` の続き（違う id・違う名前・`terminate`）。メインのフィルターが `terminate` を素通りさせること。`parallel`: 2 つの `sleep` が並んで進む（約 0.4 秒）、`waitForAny` の戻り値、引数なし、フィルターと `queueEvent` がほかのコルーチンに届く、入れ子、エラーの伝わり方、引数の誤り、`terminate`、上書きした `os.pullEventRaw` を通さないこと。今までの「コルーチンの中で待てる」テストは、yield する形に書き直した（ADR 0094 で「コルーチンの中の振る舞いはテストで固めない」としていた）
- `LuaKibiApiRestoreTest`: `parallel` の中で、タイマー待ちとイベント待ちのコルーチンがいる間に保存して戻すと、`restored` が両方のイベント待ちに届き、タイマー待ちも続く
- `LuaParallelScaleTest`: 上の規模
- `ManCommandTest`: `parallel(3)` があること
- GameTest（NeoForge 1.21.1 `StandardOsGameTests.luaParallelRunsCoroutinesTogether`）: 標準 OS のシェルから `lua -e` で、`sleep` とイベントで待ち合わせる 2 つの関数を `parallel.waitForAll` で動かす

## 範囲に入れなかったもの

- `peripheral.call` の yield 化（上の「CC との違い」）: #744（ADR 0744）
- グローバルの `sleep`、`os.time`・`os.clock` の CC 互換の上書き: #237
- メインのチャンクをコルーチンで動かすこと（`lua.c` を直すことになる）
