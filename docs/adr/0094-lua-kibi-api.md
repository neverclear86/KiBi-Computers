# 0094: Lua 向けの KiBi API（peripheral と os の拡張）

- 状態: 採用
- Issue: #94（task）。前提: ADR 0073（`kibi:*` の WIT と p1 の import）、0077（`kibi:device/bus`）、0072（`poll_oneoff`）、0329（待機点への変換）、0087（`restored`）、0067（接続・切断のイベント）、0306（電源）、0426（時計）、0106（man）
- 方針: オーナーのコメント（#94）「Lua での書き方はできるだけ CC: Tweaked に合わせる」。合わせられないところは man に違いと理由を書く。CC 本体には依存しない
- 後続: #95（コルーチンでイベントを待つ。`os.pullEventRaw` の置き換え）、#237（CC 互換ライブラリ。残る違いを埋める）、#251（仮の値の確定）

## 決めたこと（要約）

- ライブラリは CC の名前にする: グローバルの `peripheral`（`package.loaded.peripheral` にも入れる）と、標準の `os` に足す関数。Issue 本文の `device`・`event`・`computer` という名前は、方針のコメントより前に書かれたものなので使わない
- **新しい import・カーネルの口・保存形式・公開 API は足していない。** 使うのは既にある `kibi:device/bus@0.1.0`・`kibi:kernel/events@0.1.0`・`kibi:computer/clock@0.1.0`・`power@0.1.0`・`memory@0.1.0` と `poll_oneoff`・`clock_time_get` だけ
- `lua.wasm` はコマンド（`_start`）のまま（ADR 0073 の Q6-A）。待つのは `poll_oneoff` と `[method]device.invoke` だけで、待機点型の Lua は巻き戻ってスレッドを持たずに待つ
- C で書き、`lua.wasm` に組み込む（`core/src/main/wasm/lua/`）。Lua のソースは書き換えない。`linit.c` だけをビルドから外し、同じ形の `kibi_linit.c` に差し替える（`linit.c` はライブラリを足すときに差し替える前提のファイル）
- 起動のたびに Lua のコードを読まない（ライブラリは C の `luaL_newlib`）。イベントのキューは、初めて `os.pullEvent`/`pullEventRaw` を呼んだときに開く
- man は節 3（`peripheral(3)`・`os(3)`、英語と日本語）。`man` の既定の探す順を 1 → 8 → 3 → 5 → 7 にした（ADR 0106 に追記）
- 外部サイトは #220 がまだないので、man の Markdown をそのまま流用できる形にしておき、載せるのは別の Issue にした

## ファイル

| ファイル | 中身 |
| --- | --- |
| `kibi_linit.c` | `luaL_openlibs`。標準ライブラリを `linit.c` と同じ順に開き、`peripheral` を開いて、`os` に関数を足す |
| `kibi_lua_canon.c` | `cabi_realloc`、呼び出し1回分の確保の記録、RAM の見積もり、error-code の名前、UTF-8 の検査 |
| `kibi_lua_peripheral.c` | `peripheral` |
| `kibi_lua_os.c` | `os` の拡張 |
| `kibi_lua.h` | 上のファイルの間の宣言 |

## cabi_realloc と RAM

- 結果の場所は Lua と同じアロケーターの包み（`kibi_lua_realloc`。書く前に `kibi:computer/memory` の commit で数える）で取り、頭で前の確保につないで記録する。結果を Lua の値に写し終えたら `kibi_lua_arena_reset` でまとめて戻す。import を呼ぶ前にも戻す（写す途中の Lua のエラーで戻し損ねた分を回収するため）
- 確保できなければ線形メモリの外（`0xFFFFFFF8`。標準 OS の `canon.zig` と同じ）を返し、ホストにトラップさせる。0 は返さない（アドレス 0 から書かれてメモリを壊す）
- `cabi_realloc` の中では Lua の API・エラー・止まりうる import を呼ばない
- import の前に、結果の最大の大きさが入るかを見る（`kibi_lua_reserve`）。RAM の残りが十分ならそのまま進む。少なければ、その大きさの場所を先に取り（解放した穴に入れば新しく数えない）、`cabi_realloc` はそこから切り出す。取れなければ `not enough memory` の Lua のエラーにする（pcall で受けられる。トラップにしない）。RAM は解放しても減らないので、残りだけを見ると、一度上限に達した後は穴があっても断り続けるため
- 見積もり（仮の値）: `invoke` は値 32 個＋文字列 64 KiB＋失敗の文 1 KiB＋揃えの余り 1 KiB、`list-devices` は 256 台 × 116 バイト、`methods` は 256 個 × 76 バイト＋余り 1 KiB（`info` と合わせて取る）、`open`・`info` は 512 バイト、`take` は 8 件 × 約 4.8 KiB
- **見積もりはホスト側の上限が裏付ける**（超えるとトラップになるため）。`call-error` の `bad-arguments`・`failed` の message は、ホストが 1,024 バイト（UTF-8。文字の途中では切らない）に切り詰める（`DeviceLimits.MAX_ERROR_MESSAGE_BYTES`。デバイスの作者が自由に書ける文なので）。1つのデバイスのメソッドは 256 個までで、超えるデバイスは `DeviceBus.attach` が `IllegalArgumentException` で断る（`DeviceLimits.MAX_METHODS`。名前は 64 バイトまでなので 1 個 76 バイトで見積もれる）。どちらも仮の値（確定は #251）

## peripheral

| 関数 | 中身 |
| --- | --- |
| `getNames()` | `list-devices` の address の並び |
| `isPresent(name)` | `open` できるか |
| `getType(name)` | kind。なければ nil |
| `hasType(name, type)` | kind が `type` と同じか、`:` の後ろが `type` と同じか（`type` に `:` がないとき）。なければ nil |
| `getMethods(name)` | メソッドの名前（ケバブケースのまま）。なければ nil |
| `call(name, method, ...)` | open → invoke → drop |
| `wrap(name)` | メソッドごとの関数を入れたテーブル。なければ nil |
| `find(type [, filter])` | kind が合う（`hasType` と同じ）ものを wrap して複数の値で返す |
| `getName(wrapped)` | wrap したテーブルの address |
| `bytes(s)` | bytes として渡す文字列を包む（KiBi だけ。CC にはない） |

- 名前は address。36 バイトなら大文字を小文字に直してから開く（カーネルは大文字を受けない）
- **fd を持ち続けない。** `call` も `wrap` の関数も、呼ぶたびに open → invoke → drop する。import の結果（`info`・`methods`・`invoke`）を読んだらすぐ drop してから Lua の値に写す（写す途中の Lua の確保でメモリ不足の longjmp が起きても、fd が漏れない。漏れると 256 個で `mfile` になり peripheral 全体が使えなくなる）。fd はプロセスあたり 256、デバイスは最大 256 なので、全部の fd を持つと `mfile` に当たる。`__gc` で閉じる形にすると、二重の drop（トラップ）と GC の遅れによる fd の枯渇を気にすることになる
- `wrap` は作ったときの kind を覚え、呼ぶたびに `info` で確かめる。違えば（同じ address に別の種類がつながったら）`No such peripheral`（fd を持つ場合に kind が変わると届かないのと同じ振る舞い）
- `wrap` のテーブルのメタテーブル: `__name = "peripheral"`・`name`・`type`。`getType`・`hasType`・`getMethods`・`getName` は wrap したテーブルも受ける（CC と同じ）
- メソッドの別名: ケバブケース（`get-size`）は Lua の識別子にならないので、`wrap` のテーブルにキャメルケース（`getSize`）も入れる。「`-`＋英小文字」を大文字にし、`-` の後が英小文字でないもの（`a-1`・`a--b`・末尾の `-`）には付けない。本来の名前は大文字を含まない（`[a-z][a-z0-9_-]*`）ので、ぶつからない。`call` は大文字を「`-`＋小文字」に直してから呼ぶ（両方の形を受ける）

### 値の写し方

| Lua → デバイス | |
| --- | --- |
| nil（と、引数の途中の nil） | none |
| boolean | boolean |
| 整数 | integer |
| 浮動小数 | float。ただし整数の値で int64 に入るもの（`10/2`）は integer（`getLong` のメソッドが受けられるように。`getDouble` は integer も受ける）。-0.0・NaN・±inf は float |
| 文字列 | text。正しい UTF-8（過長・サロゲート・U+10FFFF 超えなし）でなければ `bad argument #n (string is not valid UTF-8 ...)` |
| `peripheral.bytes(s)` | bytes |
| ほか（テーブル・関数など） | `bad argument #n (cannot pass a table to a device)` |

デバイス → Lua: none → nil、integer → 整数、float → 浮動小数、boolean、text と bytes → 文字列。

上限は先に Lua 側で確かめる（分かりやすいエラーにするため。値は `DeviceLimits` と同じ）: 引数 33 個以上 `too many arguments (max 32)`、文字列の合計 64 KiB 超 `arguments too large`、メソッドの名前 64 バイト超 `method name too long`。

### エラー（Lua の `error`。位置は呼んだ Lua の行）

| call-error | 文 |
| --- | --- |
| `detached` | `No such peripheral` |
| `no-such-method` | `No such method <名前>` |
| `bad-arguments(m)`・`failed(m)` | `m`（保存の後に戻した実行中の呼び出しは `interrupted`） |
| `kernel(e)` | `device call failed: <error-code の名前>` |

## os の拡張

足したもの: `pullEvent`・`pullEventRaw`・`queueEvent`・`startTimer`・`cancelTimer`・`sleep`・`epoch`・`day`・`shutdown`・`reboot`。

**標準の `os.time`・`os.clock`・`os.date`・`os.exit` は上書きしない。** CC の `os.time()`（ゲーム内の時刻）・`os.clock()`（起動からの経過）は標準の Lua と意味が違い、上書きすると標準の Lua のスクリプトが壊れる。CC と同じにする上書きは #237 に回す。

### イベント

- キュー（種類を絞らない、件数は既定の 64）は、初めて `pullEvent`/`pullEventRaw` を呼んだときに開き、閉じない。起動時に開かないのは、読まないプロセスが同報のイベントを溜め込み、マシン全体の 256 KiB とキューの 64 個を食うのを避けるため（REPL を 1,000 台動かしてもキューを作らない）。開く前のイベントは受け取れない（man に書いた）
- Lua の中に FIFO を持つ（レジストリのテーブル）。`pullEventRaw(filter)` の流れ: FIFO から取り出す（filter に合わないものは捨てる。CC と同じ）→ 空なら、期限の来たタイマーと、待たずに取れるカーネルのイベント（`take` を 8 件ずつ、取れなくなるまで）を FIFO に移す → それでも空なら `poll_oneoff`（キューの fd の `FD_READ` と、いちばん早いタイマーの期限）で待つ。busy loop にならない
- `pullEvent` は `terminate` で `Terminated` のエラー（CC と同じ。今は `terminate` を出すものはない）
- `queueEvent(name, ...)`: その時点で届いているもの（期限の来たタイマー、開いていればキューのイベント）を先に FIFO に移してから、後ろに積む（CC と同じ順）。値はどの Lua の値でもよい。ほかのプロセスには届かない（ゲストがイベントを出す import はない）
- `take` の `dropped` は黙って捨てず、`events_dropped`（捨てた数）のイベントにする
- 取り出したイベントを FIFO に移す途中で Lua の確保がメモリ不足になったら、移せなかった数を C の変数（`events_lost`）に数え、次に確保できたときに `events_dropped` にする（取り出し済みのイベントはカーネルのキューに戻せないので、せめて「失った」と知らせる）。FIFO の末尾は積み終えてから進める（確保に失敗しても穴ができない）。タイマーも、イベントを積んでから表から外す（積む途中で失敗しても次に鳴る）

イベントの名前の写し方:

| カーネル（種類・source） | Lua |
| --- | --- |
| `device-attached`・`""` | `peripheral`, address |
| `device-detached`・`""` | `peripheral_detach`, address |
| `tty-resize`・`"console"` | `term_resize`（値なし。CC と同じ） |
| `restored`・`""` | `restored`, 実時間の飛び（ns）, ゲーム内時間の飛び（tick） |
| ほか | 種類のまま, source（空でなければ）, 値… |

- 写すのは、種類と source の両方が決まった形のものだけ。デバイスが同じ種類の名前のイベントを出しても（ADR 0067 は拒まない）、source が address なので写さずにそのまま渡す
- 一律に「source があれば第 1 引数」とすると、`tty-resize` の source `console` が `term_resize` の引数に入って CC と合わないので、表で写す

### タイマー

- カーネルのタイマーは作らない。Lua の中に id → 期限（単調時計の絶対時刻）の表を持ち、いちばん早い期限を `poll_oneoff` の時計（`MONOTONIC`・`ABSTIME`）で待つ。巻き直しで期限が延びず、保存・復元をまたいでも単調時計は続く（ADR 0064・0329）。`startTimer` を呼ぶたびに fd を作らないので、CC の書き方（ループのたびに `startTimer` して `cancel` しない）でも fd が漏れない
- 期限の来たものは、期限の早い順（同じなら id の小さい順）に `timer` のイベントにする
- 最短は 1 tick（50 ms。仮の値）。`sleep(0)`・`startTimer(0)` で `poll_oneoff(0)` を連打しないため（CC も 1 tick に丸める）
- `sleep(seconds)`: 時計だけを待ち、イベントは取り出さない（**CC と違う**: CC の `sleep` は待つ間のイベントを捨てる）。待つ間に来たイベントはキューに残る（キューの上限を超えたものは `events_dropped` になる）
- マシン全体の動いているタイマーが上限のとき、`poll_oneoff` は `AGAIN` を返す（ADR 0072）。そのときは `cannot wait for events: again` の Lua のエラーにする（滅多に起きない。待たずにやり直すと busy loop になるため）

### 時刻と電源

- `epoch([kind])`: ミリ秒の整数。`ingame`（既定）は `world-ticks` × 3600（1 tick = 3.6 秒）、`utc` は `clock_time_get(REALTIME)`、`local` は utc と同じ（タイムゾーンがない）
- `day([kind])`: `ingame` は `world-ticks` ÷ 24000、`utc`・`local` は 1970-01-01 からの日数
- **CC と値が違う**（man の違いに書いた）: CC の `ingame` は、ワールドの時刻（`Level.getDayTime()`。`/time set` で変わる）に 6000 tick を足して、日を 1 から数える（`os.day` = (dayTime + 6000) ÷ 24000 + 1、`os.epoch` = (dayTime + 30000) × 3600 ms）。CC の `utc` の日数も 1 から（1970-01-01 が 1）。KiBi は `world-ticks`（ゲーム内時間。進む一方）を 0 から数える。時間を測る用途には、`/time set` で飛ばない後者が向くため。CC に値を合わせる上書きは #237 に回す
- グローバルの `sleep`（CC の bios が定義する）は足さない。標準のグローバルは変えない方針（`os.sleep` はある）。#237 で足す
- `shutdown()`: `fflush(NULL)` してから `kibi:computer/power.shutdown`（戻らない）
- `reboot()`: `fflush(NULL)` してから `reboot`。断られたら（`not-supported`）`false, "not-supported"` を返す

### #95 との境目

`pullEventRaw` は今はその場でカーネルを待ち、コルーチンの中でも yield しない。#95 で、CC と同じく `coroutine.yield` に基づくスケジューラーに置き換える前提（`os.pullEventRaw` を Lua で包み直せば差し替えられる）。コルーチンの中での振る舞いはテストで固めない（`pcall`・コルーチン・`string.gsub` のコールバックの中で待てることだけを確かめる）。

### テストで確かめていないこと

RAM の上限の近くで、import の結果を読んだ後の Lua の確保だけが失敗する経路（fd を閉じること、取り出したイベントを数えること）は、決まった形では再現できず、テストにしていない。残りが 4 KiB 単位で減ることに頼って、埋めながら試す形は、上限の近くの別のトラップ（Issue にした）に当たって安定しなかった。コードの順（fd を先に閉じる、積み終えてから進める）で保っている。

## 範囲に入れなかったもの

- `term`・`fs`・`colors`・`keys`（#326 が要る）・`parallel`（#95）: 別の Issue（Epic #90 の下、「依存: #94」）
- `os.getComputerID`・`getComputerLabel`・`setComputerLabel`: 返す import がない。足すならシステムコールの ABI（ラベルの保存は保存形式）の判断になる
- ほかのプロセスに届く `queueEvent`: ゲストがイベントを出す import がない（ABI の判断）
- `key`・`char` のイベント: #326 がまだ
- 外部サイトのページ: #220（構築）の後。別の Issue にした

## 規模（1,000 台）

- tick ごとの処理は足さない。イベントを待たないプログラム（REPL など）はキューを開かない
- 待つ台は `poll_oneoff` か `invoke` の中で巻き戻り、スレッドを持たない
- 起動時に足すのは C のテーブル作り（`peripheral` の表と、`os` の表が広がる分。約 1.5 KiB）だけ。FIFO・タイマーの表と `bytes` のメタテーブルは、初めて使うときに作る
- この 1.5 KiB で、起動の RAM が 128 KiB（WASM の 2 ページ。上限は 64 KiB 単位）に収まらなくなった（アロケーターが残す 16 KiB に届かない）。
  起動の目安のテスト（`WrittenMemoryTest`・`LuaMemoryLimitTest`）は 192 KiB にした。ゲームの RAM は MiB 単位（`PartTier`。既定 32 MiB）なので、実際の影響はない

## テスト

- `LuaKibiApiTest`（実際の Endive・`lua.wasm`・カーネル）: peripheral の各関数（直接とメインスレッドのメソッド、大文字の address、別名）、`wrap`・`find`・`getName`、wrap の後の kind の付け替え、値の写し方の境界（整数の値の float、-0.0・NaN・inf・2^63、不正な UTF-8、bytes、途中の nil、32/33 個、64 KiB）、エラーの写し方、接続・切断・デバイスのイベント、タイマーと `queueEvent` の順・フィルター・`sleep` がイベントを捨てないこと、`pcall`・コルーチン・`gsub` の中で待つこと、`reboot` の断り、`shutdown`、キューを遅れて開くこと、RAM の上限の近くで `not enough memory` になり CRASHED にならないこと
- `LuaKibiApiRestoreTest`: `pullEvent` で待つ間に保存して戻すと `restored` を受ける。`startTimer`・`sleep` で待つ間に保存して戻すと、同じ期限で続く（Lua の中のタイマーの表・FIFO と、C の静的変数が戻した後もそろっている）。`peripheral.call` の実行中に保存して戻すと `interrupted` のエラーで、メソッドは1回だけ走る
- `LuaWasmTest`: 素の Endive で動かすため、足した import のスタブを足した
- `ManCommandTest`: man3 のページがあること
- `StandardOsRestoreTest`: `cabi_realloc` を export するプログラムに `/bin/lua` が加わった
- `TestWasiP1`（`EndiveProgramTest` など）と PoC（`poc/fuel-metering`・`player-exec`・`wait-point-save`・`endive-bench`）: 足した import のスタブ
- GameTest（NeoForge 1.21.1 `StandardOsGameTests.luaListsAndCallsAdjacentDevices`）: 標準 OS のシェルから `lua -e` で、ケースのスピーカーと隣の画面・キーボードを列挙し、画面の `getSize` を呼ぶ
