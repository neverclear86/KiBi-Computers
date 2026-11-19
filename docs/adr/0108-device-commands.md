# 0108: デバイス操作のコマンド（`lsdev`・`devcall`）

- 状態: 採用
- Issue: #108（task）。前提: ADR 0077（`kibi:device/bus` の p1 の import）、0067（接続・切断のイベント）、0087（復元のイベント）、0098（標準 OS のビルドと `kibi` ライブラリ）、0103（コマンドの共通の約束）、0094（Lua の `peripheral`）、0106（man）、#271（用語）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- `/bin` に 2 つ足した: `lsdev`（一覧・メソッド・接続と切断を見続ける）と `devcall`（メソッドを 1 回呼ぶ）
- 名前に「comp」を使わない。Issue 本文の `lscomp` は #271 より前の書き方で、周辺機器は「デバイス」と呼ぶ（AGENTS.md の用語）
- **新しい import・カーネルの口・保存形式・公開 API は足していない。** 使うのは既にある `kibi:device/bus@0.1.0` と `kibi:kernel/events@0.1.0`、`poll_oneoff` だけ
- 汎用のコマンドだけにした。まだないデバイス（レッドストーン I/O #154、GPU #137 など）専用のコマンドは作らない。`devcall` で将来のデバイスも呼べる。専用のものが要れば、それぞれの Issue で足す
- `kibi` ライブラリに `device`（`kibi:device/bus` の包み）と `events.waitQueue`（キュー 1 つを待つ）を足した

## `kibi.device`（`os/lib/kibi/device.zig`）

- 包むのは `list-devices`・`[static]device.open`・`info`・`methods`・`invoke`・`[resource-drop]device`。`start`・`take` は使うプログラムができたときに足す
- 配置は `kibi_device.h` と同じ（`comptime` で確かめる）。値の判別子は none=0 から（`Tag`）で、`events.Value`（integer=0 から）とは別の型にした
- 結果は `cabi_realloc` の領域（`canon.zig`）に置かれ、`canon.reset` まで読める。包みの中では戻さない。一覧を読みながらデバイスごとに呼ぶとき（`lsdev -l`）は、一覧を静的な表（256 台 × address 36 + 種類 64 バイト）に写してから、1 台ずつ `canon.reset` → `open` → `methods` とする（最悪 256 台 × 約 19.5 KiB を積み上げないため）
- 失敗は `Failure`（`kernel`・`detached`・`no_such_method`・`bad_arguments`・`failed`）。Zig のエラーは中身を持てないので、`Result(T)`（`ok` か `err`）で返す
- `Device` は `open` → 呼ぶ → `close`（`defer` で 1 回だけ）。不正なハンドルの drop はトラップするので、誤りの道で二重に閉じない
- デバイスの指定の小物: `hasType`（Lua の `peripheral.hasType` と同じ規則）・`lowerAddress`・`match`

## デバイスの指定（両方のコマンドで同じ）

- 英大文字は小文字にそろえる（カーネルは大文字の address を `detached` にする。Lua と同じ）
- address 全体と同じ（`exact`）、address の頭が同じ（`prefix`）、種類が当たる（`kind`。`screen` は `kibicomputers:screen` に当たる）
- `devcall`: `exact` があればそれ。なければ `prefix`・`kind` に当たるものが 1 台だけならそれ。0 台は `no such device`、2 台以上は `ambiguous device`（どちらも 1）。決めた address を写してから `canon.reset` して開く。間に外れたら `open` が `detached` を返し、`no such device`
- `lsdev`: 指定は絞り込み。どれかの指定に当たるものを全部書く。何にも当たらない指定は `no such device` を知らせて 1（当たったものは書く）

address の頭を受けるのは、36 文字を打つのが画面（ティア 1 は 50 桁）の上でつらいため。曖昧なら呼ばないので、取り違えは起きない。

## `lsdev`

- `lsdev [-l] [device ...]`: 1 行に 1 台、`address 種類`（カーネルの一覧の順＝つないだ順）。`grep`・`cut -d' '` で扱える形にした（ティア 1 の 50 桁では折り返す）
- `-l`: 各デバイスの下に、メソッドを 1 行に 1 つ、2 文字字下げで書く。メインスレッドのメソッドには ` (main)` を付ける（結果が後の tick になるのが分かるように）
- `-w`: 接続と切断を見続ける（ADR 0067 の受け手。#108 で受けると ADR 0067・0077 が決めていたもの）
  1. キュー（種類 `device-attached`・`device-detached`・`restored`）を**先に**開いてから一覧を読む（逆だと間の接続を落とす）。種類で絞るので `restored` も入れる（ADR 0087）
  2. 今の一覧を、印なしで書く
  3. キューを `poll_oneoff` で待つ（`events.waitQueue`）。起きたら空になるまで 1 件ずつ取り出す
  4. source が空のものだけをカーネルのものとして扱う（デバイスが同じ名前で出したもの＝source が address は無視）。接続は、知らない address なら表に足して `+ address 種類`。切断は、知っている address なら表から除いて `- address 種類`
  5. `restored` を受けた・あふれて捨てた分（`dropped > 0`）があったときは、取り出し終えてから一覧を読み直し、表との差を `+`・`-` で書く。復元の後に重ねて届く `device-attached` は、知っている address なので書かない
  6. Ctrl+C で終わる（シグナルで殺される）
- 保存して戻すと、表（静的な領域）はプロセスのメモリごと戻る。デバイスの一覧は保存しないので、戻した後の読み直しで、なくなったデバイスが `-` で出る
- `-w` と `-l`・指定は一緒に使えない（2）

## `devcall`

- `devcall device method [arg ...]`。オプションはない（`--` だけ受ける）。負の数はメソッドの後ろに来るので、オプションと見なされない（`cli.Args` は最初の非オプションで止まる）
- 呼ぶのは `invoke`（結果まで待つ）。止まりうる import なので、`devcall` は待機点型に変換される（ADR 0329。テストで確かめた）
- 失敗しても自動でやり直さない。`failed("interrupted")`（保存の前後で実行中だったもの）も `kernel(again)`（待たない設定）もそのまま知らせて 1。メインスレッドのメソッドはもう走ったかもしれないため（ADR 0077）
- Ctrl+C で殺されたときは、fd 表が閉じて、まだ走っていない呼び出しは取り消される（#532 の既存の道）。走った後の効果は戻らない。man に書いた
- 定期的に呼び続ける機能（watch 的なもの）は作らない。メインスレッドのメソッドだと、台数に比例して tick の負荷になるため。変化を待つのは `lsdev -w` のキューだけ

### 引数の型

前置き（2 文字目が `:`）で決める。前置きがなければ形から推す。

| 書き方 | 型 |
| --- | --- |
| `s:文字列` | text（`s:none`・`s:42` のように、推すと別の型になるものを text にする） |
| `i:整数` | integer |
| `f:小数` | float（`std.fmt.parseFloat` が読むもの。`inf`・`nan` も） |
| `b:true`・`b:false` | boolean |
| `x:16進` | bytes（偶数桁。`x:` だけなら空） |
| `none` | none |
| `true`・`false` | boolean |
| `[+-]?[0-9]+` | integer。s64 の外は `integer out of range`（丸めない） |
| 数字と `.`・`e`・`E`・`+`・`-` だけで、数字と `.` か `e` を含み、読めるもの | float（`1.5`・`1e3`。`1.2.3` は読めないので text） |
| ほか | text（`minecraft:stone` は 2 文字目が `:` でないので text） |

- 形の誤りは `devcall: argument N: 説明` で 2（`invalid integer`・`integer out of range`・`invalid number`・`invalid boolean (true or false)`・`invalid hex bytes`・`invalid UTF-8`）。対象を引数の文字列でなく番号にしたのは、不正なバイト列を端末に送らないため
- text は厳しい UTF-8（`kibi.text.decode`）で確かめる。カーネルに任せると `kernel(invalid)` にしかならないため
- 上限は呼ぶ前に確かめ、1 で知らせる: 引数 33 個以上 `too many arguments (max 32)`、text・bytes の合計 64 KiB 超 `arguments too large`、メソッドの名前 64 バイト超 `method name too long`（値は `DeviceLimits` と同じ）。引数の領域（argv）は 64 KiB なので、`arguments too large` は argv からはほぼ届かない（守りとして残す）
- 引数の並びは静的な `[32]Value`（`Value` が i64 を含むので align 8。#430）。`x:` の bytes は静的な 64 KiB の領域に置く

### 結果の書き方

1 行に 1 値。引数の書き方に戻すと同じ型になる形にした（text を除く）。

| 型 | 書き方 |
| --- | --- |
| none | `none` |
| integer | 10 進 |
| float | 読み直すと同じ値になる最短の形（`std.fmt.float.render`）。絶対値が 1e-5 以上 1e16 未満と 0 は小数の形、ほかは指数の形（`1e20`）。`.`・`e` を含まなければ `.0` を足す（`2.0`・`-0.0`）。`inf`・`-inf`・`nan` はそのまま |
| boolean | `true`・`false` |
| text | 制御文字（改行・ESC を含む）を `?` にする（`cli.Writer.printable`。デバイスの作者が自由に書く文なので、端末のエスケープを送らない） |
| bytes | `x:` と小文字の 16 進 |

### 失敗の知らせ

`devcall: 対象: 説明`。対象と説明の制御文字は `?` にする（説明はデバイスの作者が書く文でありうる）。文言は Lua（ADR 0094）とそろえた。

| `call-error` | 対象 | 説明 |
| --- | --- | --- |
| `detached` | デバイスの指定 | `no such device` |
| `no-such-method` | メソッド | `no such method` |
| `bad-arguments`・`failed` | メソッド | 文そのまま |
| `kernel(e)` | メソッド | errno の説明（`kibi.errno.describe`） |

## 大きさ

- `lsdev` は 8,485 バイト、`devcall` は 42,510 バイト（`ReleaseSmall`・`-fstrip`）。`devcall` の大半は小数の読み書き（`std.fmt.parseFloat`・`std.fmt.float.render`。Ryu と Eisel-Lemire の表）
- `std` は `fmt` の小数の関数だけを使う（`std.Io`・アロケーターは使わない。ADR 0098）。手で書くと、最短で往復する書き方・正しく丸める読み方を自前で持つことになり、誤りやすいため。大きさは同梱イメージ（展開後 約 1 MB）の数 % で、触るまで RAM に数えない
- 静的な領域: `lsdev` は表 2 つ（約 51 KiB。`-w` の読み直しでだけ 2 つ目を触る）、`devcall` は引数の並び 512 バイトと bytes の 64 KiB（`x:` を使ったときだけ触る）。.bss なので触るまで RAM に数えない（#407）

## 規模（1,000 台）

- tick ごとの処理は足さない。`lsdev`・`devcall` は呼ばれたときだけ動く
- `lsdev -w` は `poll_oneoff` でキューだけを待つ（時計では起きない）。待機点型なので、待つ間スレッドを持たない
- `sh`・`init` は接続のイベントを受けない（常駐の受け手を足さない）

## テスト

- `DeviceCommandsTest`（core）: `devcall` の 6 種の値の往復、推す型、小数の書き方、デバイスの指定（種類・大文字・address の頭・曖昧・ない）、各 `call-error` の知らせ、制御文字を含む失敗の文と結果、上限（32/33 個、メソッド名 64/65 バイト）、s64 の境界、形の誤り、使い方の誤り、メインスレッドのメソッドを待機点型で 1 回だけ呼ぶこと。`lsdev` の一覧・`-l`・絞り込み・ない指定。`lsdev -w` の接続・切断と、デバイスが出した同じ名前のイベントを無視すること
- `StandardOsRestoreTest`: `lsdev -w` で待つ間に保存して戻すと、一覧を読み直して、なくなったデバイスを `-` で書き、つなぎ直したデバイスは重ねて書かず、続きの接続も書く。`cabi_realloc` の export の検査に 2 つを足した
- `BasicCommandsTest`: イメージに入ること。`ManCommandTest`: 日英のページがあること
- GameTest（NeoForge 1.21.1 `StandardOsGameTests.shellListsAndCallsAdjacentDevices`）: シェルから `lsdev` でケースのスピーカーと隣の画面・キーボードを列挙し、`devcall` で画面の `get-size` を呼ぶ

確かめていないこと: 不正な UTF-8 の引数（テストの道具が argv に UTF-8 しか渡せない）と `arguments too large`（argv の領域で先に断られる）。
