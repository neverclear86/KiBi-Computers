# 0724: Lua の term・colors・colours・keys・fs

- 状態: 採用
- Issue: #724（task）。前提: ADR 0094（Lua 向けの KiBi API）、0095（コルーチン）、0326（キーの押下・離しのイベント）、0427（`kibi:io/tty`）、0453（`list-mounts`）、0106（man）
- 方針: #94 のオーナーのコメント「Lua での書き方はできるだけ CC: Tweaked に合わせる」。合わせられないところは man に違いと理由を書く。CC のコードは写さず、CC の文書と名前の一覧で振る舞いだけを合わせて自分で書いた
- 後続: #798（キー待ちの間の TTY のエコーと入力の溜まり）、#237（CC 互換の上書き・`window` など）

## 決めたこと（要約）

- **新しい import・カーネルの口・保存形式・公開 API は足していない。** `lua.wasm` が新しく使い始めた import は、既にある `kibi:io/tty@0.1.0` の `is-tty`・`get-size` と、`kibi:fs/mounts@0.1.0` の `list-mounts` だけ（どれも待たない）。ファイルの操作は wasi-libc の stat・mkdir・rename・unlink・rmdir・`fd_readdir`
- C で書き、`lua.wasm` に組み込む（`kibi_lua_colors.c`・`kibi_lua_keys.c`・`kibi_lua_term.c`・`kibi_lua_fs.c`）。`kibi_linit.c` の `LIBS[]` で、グローバルと `package.loaded` に入れる
- **表の中身は初めて触ったときに詰める**（`kibi_lua_lazy_lib`）。起動時に作るのは、空のテーブルとメタテーブル（`__index`・`__pairs`・`__fill`）だけ。初めて触ると、C の静的な表から関数と値を詰めて、メタテーブルを外す。`pairs` も CC と同じに動く（`next` を直に呼ぶと、詰める前は空に見える）。関数は軽い C 関数（upvalue なし）なので、詰めても確保は表の分だけ
- 標準の `print`・`io`・`os.time` などは上書きしない（#237 の範囲）。`term`・`fs`・`keys` は、使っても読み込んでもイベントのキューを開かない

## keys とキーのイベント

- 番号は CC と同じ GLFW のキーコード。表は「番号・CC の名前・カーネルのキー名（ADR 0326）」の静的な配列（番号の順）で、`keys.getName` は二分探索、カーネルの名前から番号へは表をなめる（1 回のキーのイベントに 1 回）
- CC の別名 `keys["return"]`（= `enter`）と `scollLock`（綴り違い）も入れる。`getName` は別名を返さない（CC と同じ）
- **CC の表にないキー**（KiBi のキーボードが送るもの）: `escape`（256）・`world1`（161）・`world2`（162）・`rightSuper`（347）を足した。CC の表は、この 4 つを入れていない（CC のコンピューターの GUI では届かないため）。KiBi では届くので、捨てるより番号を付けた方が使える。名前は CC の命名（キャメルケース。`world1` は GLFW の名前）にそろえた
- CC にあって KiBi では届かない `f13`〜`f25` も表に入れた（CC のプログラムが名前を引いてもエラーにならないように）
- `push_kernel_event`（`kibi_lua_os.c`）で、`key-pressed`（source あり、値が text・integer・boolean）を `"key", 番号, 繰り返し`、`key-released`（text・integer）を `"key_up", 番号` に写す。**`unknown` と表にないキー名は捨てる**（`unknown` は別々のキーが同じ名前になり、押下と離しが対にならない。表にない名前は将来増えるもので、生の形を見せると約束していない形が見えてしまう。ADR 0326 の「知らない名前は無視」）
- 写すのは形だけを見る。source の種類がキーボードかは照らさない（`push_kernel_event` は arena を握った中で動くので、`open`・`info` の import を呼べない）。同じ名前と形のイベントを出すほかのデバイスも `key` に見える。man に書いた
- `key` には CC の形のまま値を足さない（キーボードの address・修飾キーは渡さない）。複数のキーボードを見分けられないのは CC と同じ
- `char`・`paste` は作らない。カーネルが文字の入力を出していない（ADR 0326「決めないこと」）。`key-pressed` と Shift から文字を作るのは、GLFW のコードが US 配列の物理的な位置なので JIS 配列では違う文字になり、IME も通らないのでしない。文字の入力をイベントにするのは ABI の判断なので、要るときに別の Issue で
- 写し替えたので、ADR 0326 の GameTest の汎用の形（`kp,q,0,false,true`）は CC の形（`kp,q,false,3`）に直した

## term

- 端末（コンソールの TTY。docs/terminal.md）への ANSI のエスケープで描く。画面のデバイスにメソッド（色数・カーソルの位置）は足さない（ゲストに見える約束で、GPU の #137・#141 の範囲）
- **論理カーソル**: 位置・色・パレット・カーソルの表示は C の静的変数に持つ。端末に位置を問い合わせない（DSR の応答は TTY の入力に混ざり、cooked＋echo ではエコーされる）。位置は画面の外（x < 1、x > 幅、y も）も持つ（CC と同じ）。最初の `setCursorPos` の前は、端末の位置が分からないので、`write` は CUP を出さずに今の位置に書き、`getCursorPos` は 1, 1 から数えた見積もりになる
- **`write`・`blit` は毎回 CUP と SGR を書く**: 画面に入る文字の手前で 1 回 CUP を出し、続く文字は端末のカーソルに任せる。SGR は呼び出しの頭で毎回書く（約 40 バイト）。こうすると、保存から戻した後に端末の SGR が初めの状態になっていても（ADR 0087）、ユーザーが `io.write` でエスケープを書いても、次の出力で直る。「汚れた」の印で省く形より単純で、壊れ方がない
- 保存から戻した（`restored` を受けた）ときは、`push_kernel_event` で印を立てるだけにし（arena の中では書かない）、次の出力でカーソルを隠していれば `CSI ?25l` を書き直す
- **右端で折り返さない**（CC と同じ）。画面に入る分だけ切り出し、右端の DECAWM は切り替えない（`CSI ?7l` は途中で殺されると残るため）。端にかかる全角は、入る側の 1 セルを空白にする
- **`setCursorPos`・`setTextColour`・`setBackgroundColour`・`setCursorBlink` は、その場でエスケープを書く**。CC でよく書く `term.setCursorPos(1, 5); print(...)`、`term.setTextColour(colors.red); print(...)` が CC と同じに見える
- `print`・`io.write` は `term` を通らない（上書きしない）。`print` の後の `getCursorPos` は変わらない。man の違いに書いた
- 出力は 1 回の呼び出しを `luaL_Buffer` にまとめ、stdout（`print` と同じバッファ）に `fwrite` してすぐ `fflush` する。fd 1 に直に書くと、バッファにある `print` の出力と順番が入れ替わるため
- **文字**: UTF-8 で読み、C0・DEL・C1 の制御文字と不正なバイト列（過長・サロゲート・U+10FFFF 超え・途中で切れたもの）は U+FFFD にする（エスケープを注入させない。ADR 0102 の sh と同じ方針）。幅は `os/lib/kibi/text.zig` と同じ表（全角は core の `CharWidth` と同じ）で数え、幅 0 の文字は直前の文字を描いたときだけ付ける。表が同じことは `LuaTermFsKeysTest` がソースを読んで確かめる
- **色とパレット**: CC の 16 色のビット（いちばん上のビットを見る。0 以下と 2^16 以上は `Colour out of range`）→ 番号 → Lua の中のパレットの RGB → `SGR 38;2;r;g;b;48;2;r;g;b`。端末がティアの色数に丸める。端末はパレットを変える OSC 4 を読み捨てるので、`setPaletteColour` は後に描く分にだけ効く（もう描いたセルは変わらない。CC と違う）。今の文字色・背景色のパレットを変えたときは、その場で SGR を書き直す
- `isColour` はいつも true（色数を返す口がない。単色のティアでも端末が読める色に丸める）
- `getSize` は `kibi:io/tty` の `get-size`。fd 1 → 2 → 0 の順に `is-tty` で探し、見つけた fd を覚える。どれも TTY でなければ 51 x 19（CC のコンピューターの画面）。大きさは呼ぶたびに読む（待たない import。キャッシュすると、キューを開いていないプログラムには `term_resize` が届かず、古いままになる）
- **終わるときの後始末**: 色かカーソルの表示を変えたら、`atexit` で `SGR 0` と `CSI ?25h` を書く（`sh` は SGR を戻さず、`CSI K` は今の背景色で消すため。ADR 0102・0061）。シグナルで殺されたときは走らない。man に書いた
- 初めはカーソルを表示したまま（端末の実際の状態に合わせる。CC は `setCursorBlink(true)` まで隠す）
- **`term.redirect`**: 今の出力先をレジストリに持つ（この端末なら nil）。`term.*` は、出力先がこの端末なら C の実装を直に呼び、そうでなければ出力先のテーブルの同じ名前の関数に引数を渡す（`lua_callk` で、出力先の関数の中の yield も通す）。ない関数は `Redirect object is missing method <名前>.`。`term` の表そのものを出力先にするのは断る（CC と同じ文）。`term.native()` の表は初めて呼ばれたときに作る。`window` は #237、モニターへの redirect は GPU（#137・#141）がないので作れない

## fs

- **パスはいつも根から数える**（CC と同じ。cwd は見ない）。`\` は `/`、`.` は飛ばし、`..` は 1 つ上。根より上に出る `..` は `fs.combine` では先頭に残り（`fs.combine("a", "..", "..", "b")` は `../b`）、ファイルの操作では `/<パス>: Invalid Path`。`getName("")` は `root`、`getDir("")` は `..`（CC と同じ）。cwd から数える標準の `io.open` とは数え方が違うことを man に書いた。根から数えるので、wasi-libc にはいつも絶対のパスで渡り、cwd とつなぐ確保（`make_absolute`）をしない
- CC がパスから除く文字（`"`・`:`・`<`・`>`・`|` など）は残す（KiBi の VFS は受けるため）
- **エラー**: CC と同じく、ほとんどの関数は `/<パス>: <文>` の Lua のエラー、`fs.open` だけは `nil, 文`。errno から文への表: `ENOENT` → `No such file`、`ENOTDIR` → `Not a directory`、`EEXIST` → `File exists`、`EACCES`・`EPERM`・`EROFS` → `Access denied`、`EISDIR` → `Is a directory`、`ENOTEMPTY` → `Directory not empty`、`ENOSPC`・`EDQUOT` → `Out of space`、`EMFILE`・`ENFILE` → `Too many files already open`、`ENAMETOOLONG` → `Path too long`、ほかは `strerror`
- **包みの外の malloc を避ける**: `fs.list` は `opendir`（中で libc の malloc を使い、RAM を数える包みを通らない。#729・#753 と同じ種類の危険）を使わず、`open(O_DIRECTORY)` と `__wasi_fd_readdir` を直に使う。受ける領域（4 KiB）は Lua の userdata。確保は fd を開く前に済ませ、読む部分は `lua_pcall` で包んで、エラーでも fd を閉じる
- `fs.list` は名前の順に並べる（標準の `table.sort`。`package.loaded.table` から取り、ユーザーが差し替えたグローバルは使わない）
- **再帰**（`delete`・`copy`・`move` のマウントをまたぐとき）: ディレクトリを一覧にして fd を閉じてから中を見るので、同時に持つ fd は 1〜2 個。深さは 128 まで（C のスタック 256 KiB と Lua のスタックを食いすぎない。超えたら `Too many nested directories`）。ファイルの写しは 4 KiB の userdata を先に取ってから 2 つの fd を開き、開いている間は Lua の確保をしない
- `fs.delete` はないパスなら何もしない。根（`/`）は `Access denied`。途中で失敗すると、そこまで消した分は残る（読み取り専用の同梱イメージ（ADR 0461）の中では最初の `unlink` で失敗する）
- `fs.copy`・`fs.move` は、行き先があれば `File exists`、自分の中へは `Can't copy a directory inside itself`・`Can't move a directory inside itself`（CC と同じ文）。行き先の親は作る。`move` は `rename` で、`EXDEV`（マウントをまたぐ）なら写してから消す
- `fs.makeDir` は親も作り、もうあるディレクトリはそのまま。途中にファイルがあれば `File exists`
- **容量・読み取り専用・ドライブ**: `list-mounts` で、パスがいちばん長く合うマウントの点を引く（ないパスでも引ける。`stat` の `st_dev` と照らす `df` の形より単純）。import の前に `kibi_lua_arena_reset` と `kibi_lua_check_memory(L, 見積もり)` を呼び、結果を読む間は Lua の確保をせず C の変数に写してから `arena_reset` する。見積もりは 32 個 ×（72 ＋ 文字列 2 つ × 256）＋ 1 KiB（仮の値。マウントの数に上限はないが、RAM に余裕があるときは見積もりを使わない）。arena を握っている中（`__gc` から）では呼ばずにエラー
  - `getDrive` はマウントの種類（`disk`・`image`・`tmp`・`dev`）。CC の `hdd`・`rom`・`disk` とは違う（KiBi の種類の方が、何がつながっているかを表すため）
  - `getFreeSpace` は `usage.available-bytes`。読み取り専用なら 0（CC の rom と同じ）、量を持たないマウントは `"unlimited"`。`tmp` の空きは目安（ADR 0453）
  - `getCapacity` は `usage.total-bytes`。読み取り専用か量を持たないマウントは nil
- `fs.attributes` の `created`: WASI に作った時刻はないので、`ctim`（状態の変わった時刻）で代える
- **Lua で書いた部分**: `fs.open` のハンドル（標準の `io` のファイルを包む。fd の後始末は `io` の `__gc`・`__close` に任せるので、`io` が既に持つリスクから増えない）、`fs.find`（ワイルドカードを 1 区間ずつ Lua のパターンにして合わせる）、`fs.complete`（CC の文書の振る舞い）。ADR 0095 の型で Lua の文字列として入れ、初めて使ったときに `luaL_loadbufferx(..., "t")` で読む
- `fs.open` のモードは `r`・`w`・`a`・`r+`・`w+`（`b` でバイナリ）。`io.open` にはいつもバイナリで渡す（CC 1.109 から、テキストのモードも生のバイト）。書くモードでは親のディレクトリを作る。ディレクトリは開かない（`Is a directory`）。ほかのモードは `Unsupported mode` のエラー

## 規模（1,000 台）・RAM・待機点

- tick ごとの処理・タイマー・スレッドは足していない。`term`・`fs` は呼ばれたときだけ動く
- 待たない import しか足していないので、`lua.wasm` は待機点型（WAIT_POINT）のまま。`LuaReplTest`・`StandardOsLuaTest`・`AsyncFuelTest`・`LuaParallelScaleTest` が通る
- 起動の RAM: 足したのは 5 つの空のテーブルとメタテーブル（`package.loaded` とグローバルの項目を含めて 2 KiB 足らず）と、静的な表（キーの表・幅の表・パレット・`fs` の Lua のソース）。`WrittenMemoryTest`（REPL の書き換えは 48 区画以内、`lua` は 192 KiB の RAM で動く）と `LuaMemoryLimitTest` が通る

## 保存・復元

- 状態は C の静的変数と Lua のヒープ（どちらも線形メモリ）にあり、スナップショットにそのまま入るので、保存形式は変わらない
- `lua.wasm` のバイト列が変わったので ModuleHash が変わり、古い `lua.wasm` で保存した Lua のプロセスは `MODULE_MISMATCH` で冷えた起動に落ちる（ADR 0088）。v0.1.0 の前なので互換は要らない

## 範囲に入れなかったもの

- キー待ちの間の TTY のエコーと、打った入力が溜まって `sh` に読まれること: #798（Epic #90。依存: #724）。TTY のモードを自動で切り替えると、REPL の `fgets`・CR → LF・Ctrl+C のシグナル・殺されたときの戻し方に効き、`os(3)` の振る舞いの変更になるので分けた。#724 では `keys(3)`・`term(3)` に注意を書いた
- `char`・`paste` のイベント（上の keys の節）
- `window`・`paintutils`・`textutils`・グローバルの `write`・`read`・`sleep`: #237
- モニター（画面の周辺機器）への `term.redirect`: GPU（#137・#141）

## テスト

- `LuaTermFsKeysTest`（実際の Endive・標準 OS のイメージの `/bin/lua`・カーネル。term はコンソールの出力を `AnsiTerminal` に流してセルを見る）
  - colors・colours の値と関数、`pairs`
  - keys の代表の番号と別名、`getName`。`KeyboardDevice.input` に `Key.entries`（`unknown` を除く）をすべて流し、`key` の番号が重ならず名前に戻ること、繰り返しの値、`unknown` が届かないこと、`key_up`
  - term: `clear`・`setCursorPos`・`write` の切り出し（右端・x < 1・画面の外の行）、色、制御文字の置き換え、全角と端にかかる半分、`blit`（長さが違うときのエラー）、`setTextColour` の後の `print` の色、`setPaletteColour`、終わるときの SGR とカーソルの表示、`redirect`・`current`・`native`、`getSize` と `term_resize`、term を使わないプログラムはエスケープを書かないこと、幅の表が `text.zig`・`CharWidth` と同じこと
  - fs: `combine`・`getName`・`getDir`、根から数えること、`makeDir`（親ごと）・`open` の各モード・`copy`・`move`・`delete`（中身ごと）・エラーの文、読み取り専用の根、`getDrive`・`isDriveRoot`・`getFreeSpace`・`getCapacity`、`find`・`complete`、くり返しても fd が残らないこと
- `LuaWasmTest`・`TestWasiP1`・PoC（`endive-bench`・`fuel-metering`・`wait-point-save`）: 足した import のスタブ
- `ManCommandTest`: `term(3)`・`colors(3)`・`colours(3)`・`keys(3)`・`fs(3)` があること
- GameTest（NeoForge 1.21.1 `StandardOsGameTests.luaReceivesKeyEvents` など）: キーボードの `q` が `key`, `keys.q`, false で届く。離しの合成とキーボードを壊したときの順も、`key`・`key_up` の名前で確かめる
