# 0087: 復元のイベント `restored`

- 状態: 採用
- Issue: #87（task）。前提: ADR 0063（イベントキュー）、0064（時計とタイマー）、0073（`kibi:*` の WIT と p1 の import）、0085（読み込みでの復元）、0098（標準 OS の作り方）
- 後続: #630（画面の中身の保存）、#439（ゲーム内時間）、#164・#174・#175（保存をまたいだ接続の扱い）、#88（MOD のバージョン違い）
- ABI（種類・source・値・出す条件）と、画面を OS が描き直すこと（保存形式は変えない）は、#87 へのオーナーの回答（Q1=A・Q2=A）で決めた

## 決めたこと

### イベントの形

成功した復元のたびに、カーネルが `kibi:kernel/events` のキューへ1件出す（同報。ADR 0063 の配り方のまま）。

```
event { kind: "restored", source: "", values: [integer(実時間の飛び ns), integer(ゲーム内時間の飛び tick)] }
```

- 値はこの順で、どちらも s64。中身は `KernelClocks.restore` の `TimeJump`（今の時計 − 保存した時点の時計）
- 飛びが 0 でも出す。負の値（ホストの時計を戻した、別のワールドに移した）と、あふれたときの飽和値（`Long.MIN_VALUE`・`Long.MAX_VALUE`。`saturatingSub`）もそのまま渡す
- ゲーム内時間は、ワールドの時計を渡すまで（#439）常に 0 で、飛びも 0
- 意味は「保存から戻った」だけ。画面が空であることは約束しない（今は表示のデバイスを保存しないので空のことが多いが、#630 で残るようになりうる）。端末の状態（代替画面・折り返しなど）が初期の状態に戻っていることもある
- 種類の名前の定数は `RestoreEvents.TYPE`（内部）。WIT の型は変えない（`events.wit` の doc コメントに書き足しただけ）

### 出す場所と時機

- `Machine.completeRestore` の成功の経路で、`setStateLocked(RUNNING)` の後、`postRestoreFinishedLocked(RESTORED)` の前に、同じカーネルロックの中で出す
  - 索引（`EventHub.restoreIndex`）とキュー（`ObjectTable.restoreFrom`）、待ちの登録（`registerRestoredWaitLocked`）を戻した後なので、戻したキューに届き、キューを待っているプロセスが起きる
  - 起きたプロセスは、この復元の `quiesce` が閉じる（RESTORED の受け手が返る）まで割り当てられない。受け手（`ComputerCase.startRestore` の `onReady`）が画面・キーボードをつないだ後で、OS が描き直す
  - 受け手より前に入れるので、受け手の中でつなぎ直したデバイスのイベントや `tty-resize` より先に並ぶ
- 出さないもの: 冷えた起動（`restoreFellBack`）、取り消し（CANCELLED）、表に入れる途中の失敗（CRASHED）。`restoreClocks` の中でも出さない（タイマーのテストが直接呼ぶ。出すのは呼び手）
- 画面の置き直し（#531 の `reconnectScreen`）や、復元の後から画面がつながったときには出ない。受け手の時点で画面が見つからなければ、描き直しの出力は失われる。どちらも #630 で扱う

### 受け手の決まり

- 冪等に受ける（描き直すだけにする）。取り出す前にもう一度保存・復元されると、2件届く。どちらも「前回の写しからの差」なので、足し合わせて矛盾しない
- 他 MOD のデバイスも `DeviceContext.postEvent` で同じ種類名のイベントを出せる（source はデバイスのアドレス）。カーネルのものは `source == ""` で見分ける
- キューがなければ捨てられ、あふれれば `dropped` に数えられる（ADR 0063 のまま）。`dropped > 0` も「何かを取りこぼした」合図として、描き直しの理由にしてよい

### 標準 OS の受け方

- 共通ライブラリに `kibi.events`（`os/lib/kibi/events.zig`）を置く。`open`・`take`・`[resource-drop]` の包みと、`cabi_realloc` の export
  - `cabi_realloc` は 8 KiB の静的な領域（.bss）から切り出すだけ。`take` の直前に先頭へ戻し、1回に1件だけ取り出す（1件の最悪は約 5 KiB）。足りなければ線形メモリの外（`0xFFFFFFF8`）を返してトラップさせる（0 を返すとアドレス 0 から上書きする）。止まりうる import は呼ばない
  - 配置（Canonical ABI）は `kibi_events.h` の `_Static_assert` と同じ値を `comptime` で確かめる
  - Zig は WASM の実行ファイルで `_start` のほかを export しないので、`kibi.events` を使うプログラム（`sh`・`nano`）だけを `--export=cabi_realloc` でビルドする（`core/build.gradle.kts` の `osEventsPrograms`）。`-rdynamic` は使わない。可変グローバル `__stack_pointer` まで全プログラムに足してしまい、events を使わないプログラムの export とバイナリが変わるため。events を使うプログラムを足したら、`osEventsPrograms` にも足す（足し忘れると読み込みで NOEXEC になる）
- 待ち方: `waitInputOrQueue` で、標準入力とキューを `poll_oneoff` の FD_READ 2つで待つ。時計の購読は付けない（待っているだけの台を定期的に起こさない）。キューの側が起きたら `take` で空にし（`dropped` も消費する）、空回りしない
- 描き直す条件: `kind == "restored"` かつ `source == ""`、または `dropped > 0`
- キューを開けない（マシンのキューが上限の `NFILE`、ハンドル表が満杯の `MFILE`）・待ち・取り出しに失敗したら、キューを閉じて、標準入力だけを待つ従来の動きに戻す
- `sh`（ADR 0102）と `nano`（ADR 0105）の受け方は、それぞれの ADR の追記に書いた

## 決めないこと

- 「接続が切れた」専用の種類。保存をまたいだ接続オブジェクトの扱い（閉じた状態で戻すか、など）は、接続を作る各 Issue（#164・#174・#175）で決める。そのときは `restored` を受けて繋ぎ直せる
- 表示のデバイスの状態の保存（#630）
- 画面の置き直しで知らせるか（#630）
- SDK（#116）・Lua の `event`（#94・#95）での見せ方

## コスト

- 復元1回につき post は1回。手間はマシンのキューの数（最大 64）に比例するだけで、キューがなければすぐ戻る。tick ごとの処理もスレッドも増えない
- 描き直しの出力は、復元の均し（`restoresPerTick`）に従って分散する
- 標準 OS: 待つ口が `fd_read` から `poll_oneoff`（fd 2つ）になるだけで、待っている間は起きない。`cabi_realloc` の領域は触るまで RAM に数えない（#407）
- 保存形式は変えない。イベントは既存の `EventQueue` に入り、そのまま保存される

## 確かめ方

- `core` の `RestoreEventTest`: 全部を受けるキューと `restored` だけを受けるキューに1件ずつ届き、ほかの種類のキューには届かない。値（正・0・負・飽和）。受け手の中は止めたまま（`restored` を `poll` で待っていたプロセスは、受け手の中では起きている=RUNNABLE が1歩も進まず、受け手が返ってから取り出す）。取り出さずに保存・復元すると2件。表に入れる途中の失敗では出さない
- `core` の `StandardOsRestoreTest`（待機点型に変換した標準 OS）: `sh` が入力中の行のまま戻ると、キーを打たずにプロンプトと行を描き直し、続きの入力が効き、待っている間に出力しない。`nano` が `?1049h`・`?7l` から全体を描き直す。キューを開けなくても `sh` は動く。イメージの全プログラムの export を調べ、`sh`・`nano` だけが `cabi_realloc` を足し（`memory`・`_start`・`cabi_realloc`）、ほかは `memory`・`_start` のままであることを確かめる
- 1.21.1 の GameTest `MachineRestoreGameTests.restoredShellRedrawsThePrompt`: 画面も置き直して戻すと、キーを打つ前に `$ echo pending` が出て、Enter で動く
