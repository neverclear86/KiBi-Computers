# 0326: キーの押下・離しのイベント

- 状態: 採用
- Issue: #326（task）。前提: ADR 0063（イベントキュー）、0065（デバイスのモデル）、0067（デバイスの接続・切断のイベント）、0073（`kibi:*` の WIT）、#142（キーボードの入力）、#360（入力のセッション）
- 後続: #724（Lua の `key`・`key_up` に写す）
- ABI（種類・source・値・修飾キー・打った人・文字の入力と貼り付けの扱い）と受け手への約束は、#326 へのオーナーの回答（「案で進める」。Q1〜Q5 と「回答がなければこう進めること」をすべて採用）で決めた

## 決めたこと

### イベントの形

キーボードのデバイス（`kibicomputers:keyboard`）は、キーの押下・離しのたびに、`DeviceContext.postEvent` で1件出す（同報。ADR 0063 の配り方のまま）。source は自動でキーボードの address になる。

| 種類 | source | 値 |
| --- | --- | --- |
| `key-pressed` | キーボードの address | `[text(キー名), integer(修飾キー), boolean(繰り返し)]` |
| `key-released` | キーボードの address | `[text(キー名), integer(修飾キー)]` |

- 押し続けたときの繰り返しは別の種類にせず、`key-pressed` の3つ目の値を `true` にする。繰り返しはクライアントのもの（GLFW の repeat）をそのまま流し、サーバーでは作らない
- 打った人（typist の UUID）は載せない。文字の入力（`KeyboardEvent.Text`）と貼り付け（`KeyboardEvent.Paste`）は、今はイベントにしない（TTY にだけ流す）。文書には「出さない」と約束せず、「今は出していない」と書く

### キー名

`Key` を小文字のケバブにしたもの。表は `KeyEvents.name` の網羅する `when`（`else` なし）に書き、`Key` が増えたらコンパイルエラーにする。`Key.name` を実行時に変換しない（enum の名前を変えると ABI が黙って変わるため）。`Key.ordinal` も使わない（通信の `KeyboardEventCodec` 専用。ADR 0065・#142）。

| 範囲 | 名前 |
| --- | --- |
| 英字・数字 | `a`〜`z`、`digit-0`〜`digit-9` |
| 記号 | `space` `apostrophe` `comma` `minus` `period` `slash` `semicolon` `equal` `left-bracket` `backslash` `right-bracket` `grave-accent` |
| 国別 | `world-1` `world-2` |
| 編集・移動 | `escape` `enter` `tab` `backspace` `insert` `delete` `right` `left` `down` `up` `page-up` `page-down` `home` `end` |
| ロックなど | `caps-lock` `scroll-lock` `num-lock` `print-screen` `pause` |
| ファンクション | `f1`〜`f12` |
| テンキー | `keypad-0`〜`keypad-9` `keypad-decimal` `keypad-divide` `keypad-multiply` `keypad-subtract` `keypad-add` `keypad-enter` `keypad-equal` |
| 修飾キー | `left-shift` `left-control` `left-alt` `left-super` `right-shift` `right-control` `right-alt` `right-super` `menu` |
| その他 | `unknown`（表にないキー） |

`KeyboardEventsTest` が、全キーの名前を文字列で書き並べた表と `Key.entries` が一対一であること、重ならないこと、64 バイト以内であることを確かめる。

### 修飾キー

`KeyModifiers` の値と同じビット（SHIFT `0x01`・CONTROL `0x02`・ALT `0x04`・SUPER `0x08`・CAPS_LOCK `0x10`・NUM_LOCK `0x20`）。CAPS_LOCK・NUM_LOCK は、位置は決めるが、付くとは約束しない。修飾キー自身（`left-shift` など）の押下・離しで、自分のビットが付くかどうかも約束しない（クライアントの GLFW の実装が OS ごとに違い、`KeyboardInputScreen.withHeldModifiers` は押した時点の自分を含めないため。デバイスは届いた値をそのまま出す）。出す前に `and KeyModifiers.ALL` で知らないビットを落とす（通信から来たものは `KeyboardEventCodec` が断るが、`KeyboardDevice.input` を直接呼ぶ経路にはその確かめがないため）。

### 出す場所と条件

- 出すのは `KeyboardDevice.input` の1か所。マシンにつないでいることを確かめた後、端末のバイト列（`TerminalKeyEncoder`）が空のときの早い return より前に置く。修飾キーなしの英字の押下、修飾キーだけの押下、Caps Lock、Ctrl+Alt（AltGr）、離しは、TTY には何も流れないが、イベントは出る
- F5・Enter・矢印などは、TTY のバイト列とイベントの両方に出る。TTY のバイト列とイベントの間の順番は約束しない
- `input` の返り値（TTY が受け付けたバイト数）の意味は変えない。イベントは返り値に数えない
- 外れた後は `postEvent` が `false` を返して捨てる。マシンにキューがなければ `EventHub.post` がすぐ戻る

### 受け手への約束

- 届かないキーがある。Shift+Esc、貼り付けの操作（Ctrl+V・Shift+Insert）の最後のキーはクライアントが受け持つ。表にないキーは `unknown`
- 押下と離しは対になるとは限らない（対になっていない離しが届くことがある）
  - 入力のセッション（#360）を閉じたとき（GUI を閉じた、離れた、ログアウトした、別のケースにつなぎ替えた）は、押したままのキーの離しを、修飾キー `0` で合成して、外れる前のマシンに届ける（`KeyboardSession.close`）
  - 1つのキーボードを2人が使うと、片方の GUI を閉じたときに、もう片方がまだ押しているキーの離しも届く（`KeyboardSession` ごとに押したままのキーを覚えているため）
  - 別々の GLFW のキーが同じ `unknown` に写るので、`unknown` の押下と離しは対にならない
- キーボードが外れるとき（ブロックが壊れた、チャンクのアンロード、つなぎ替え）も、押したままのキーの離しは外れる前のマシンに届き、その後に `device-detached` が届く（最初のプランの「ブロックが壊れたときも、外す前に離しが届く」のとおり）
  - チャンクのアンロード: NeoForge の `LevelChunk.clearAllBlockEntities` は、全ブロックエンティティの `onChunkUnloaded` を呼んでから `setRemoved` を呼ぶ。`onChunkUnloaded` → `unregister` → `MachineWiring.disconnectOne` → `onDisconnected` → `KeyboardSessions.closeFor` と同期で進み、離しを配ってから外す
  - ブロックが壊れた（運ばれた）: `KeyboardBlockEntity.setRemoved` が、`super.setRemoved` で `isRemoved` を立てる前に `KeyboardSessions.closeFor` を呼ぶ（`KeyboardSessions.close` は取り除かれたキーボードには配らないため）。その後の `unregister` で `device-detached` が出る
  - 受け手に「`device-detached` を受けたら全部離したとみなす」という約束は足さない（オーナーの回答に含まれていないため）
- `restored` を受けたとき、`dropped > 0` のときも、全キーを離したものとみなす
- 知らないキー名・修飾キーのビットは無視する（増えることがある）
- 他のデバイスも同じ種類名を出せるので、受け手は source の address の種類が `kibicomputers:keyboard` かを `kibi:device` の一覧で照らして見分ける（source だけではキーボードかどうかは分からない）

### 標準 OS と Lua

- 標準 OS の `sh`・`nano`・`pager` は、`restored` だけを受けるキュー（`openRestored`）しか開かないので、キーのイベントでは起きず、溜め込みもしない
- Lua は、`os.pullEvent` を呼んだときだけ全種類を受けるキューを開く。今は汎用の写し方で `"key-pressed", address, キー名, 修飾キー, 繰り返し` の順に見える。CC の `key`・`key_up` に写すのは #724 で、この名前のままの形は Lua の約束にしない
- 追記（#724）: Lua では CC の `"key", 番号（GLFW のキーコード）, 繰り返し`・`"key_up", 番号` に写すようにした（ADR 0724）。`unknown` と表にないキー名は捨て、汎用の形では届かない。キーボードの address と修飾キーは渡さない

## 決めないこと

- 押したままのキーの状態をカーネル・デバイス・マシンに持つこと、tick ごとに離しを合成すること（持たない。1,000 台の規模で、待っているだけの台に負荷をかけないため）
- Lua の `key`・`key_up`・`char` と `keys` の表: #724
- 文字の入力・貼り付けのイベント、打った人を渡すこと: 要るときに別の Issue で ABI として決める

## コスト

- tick ごとの処理・タイマー・状態を足さない。かかるのは打った分だけの `KernelEvent` の確保とカーネルロックの短い取得で、量は既存の `KeyboardInputBudget`（プレイヤーとキーボードの組ごと）とキューの上限で抑えられている
- 1件の footprint は、押下がおよそ 359 + キー名のバイト数、離しがおよそ 328 + キー名のバイト数。マシン全体の上限（256 KiB）で約 700 件。越えたら既存の `dropped` で済む
- `input` が呼ばれるのはサーバーのメインスレッドだけ。`Machine.postEvent` はカーネルロックを短く取るだけで、同じ `input` の中の `offerConsoleInput`・`pumpConsole` とはまとめない（ロックの順の新しい危険を作らない）
- 保存形式・WIT の型は変えない（`KernelEvent` は汎用の型で保存される。`events.wit` は doc コメントだけ足した）

## 確かめ方

- `core` の `KeyboardEventsTest`: バイト列が空の入力（Q・LEFT_SHIFT・CAPS_LOCK・Ctrl+Alt+A・離し）で返り値 0 のままイベントが出ること、F5（返り値 5）・Enter（返り値 1）でもイベントが出ること、文字の入力と貼り付けでは出ないこと、種類で絞ったキューにだけ入ること、外した後は出ないこと、キューがなくても例外にならないこと、知らないビットを落とすこと、セッションを閉じて合成した離しが修飾キー 0 で届くこと、キー名の表
- GameTest（NeoForge 1.21.1 `StandardOsGameTests.luaReceivesKeyEvents`）: `lua -e` で `os.pullEvent('key-pressed')` を待っている間に `q` を押すと、`kp,q,0,false,true`（キー名・修飾キー・繰り返し・source がキーボードの address と一致するか）が画面に出る。1.20.1 にはまだキーボードがないので、移植は #224 の配下
- GameTest（NeoForge 1.21.1 `StandardOsGameTests.luaReceivesSyntheticKeyReleases`・`luaReceivesReleasesBeforeDetachWhenKeyboardIsBroken`）: 押したままのキーのある GUI を閉じると `key-released` が届くこと、チャンクのアンロードとブロックを壊したときに、`key-released` が `device-detached` の前に届くこと
