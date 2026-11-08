# 0065: デバイスモデル

- 状態: 採用
- Issue: #65（task）。前提: ADR 0043（カーネル）・0044（カーネルと接続層の境界）・0355（表示デバイス）、#271（呼び方「デバイス」）
- 後続: #66（列挙・呼び出しのカーネルの口の仕上げ、接続数の上限）、#67（接続・切断のイベント）、#77（WASM に出す）、#54（`/dev/device`）、
  #94（Lua の `device`）、#129（隣接の検出と登録表）、#153（`BlockCapability` と作者向けの説明）、#326（キー入力のイベント）、
  #81・#82（保存のバイトの形）、#356（サーバー全体の予算）、#224（1.20.1 への移植）
- 番号は Issue 番号にそろえている
- 人が決めたこと（Q1〜Q6、公開 API の追加分、WIT の名前）は Issue #65 のコメントの回答による

## 決めたこと（要約）

- デバイスは **address（UUID の文字列）と kind（`<名前空間>:<名前>`）** で表し、**名前付きのメソッドを、入れ子のない値の並びで呼ぶ**（動的な `invoke`）
- メソッドは **直接（プロセスのスレッド）か、メインスレッド（次の tick の中）** のどちらかで実行する。どちらにするかはデバイスがメソッドごとに選ぶ
- マシンごとの **`DeviceBus`**（`Machine.devices`）に、接続層がデバイスをつなぐ・外す。一覧は copy-on-write の表で、カーネルロックなしで読む
- 公開 API は `pro.ikili.kibicomputers.api.device`（`core`。MC 非依存）。他 MOD は `Device` を実装し、メソッドを builder の表で登録する（反射なし）
- WIT は `kibi:device@0.1.0`（`types`・`bus`）。この Issue では WIT を置くだけで、WASM に出すのは #77
- 画面・キーボード・ケースのビープを、組み込みのデバイス（`kibicomputers:screen`・`kibicomputers:keyboard`・`kibicomputers:speaker`）に移した

## 識別子と種類（Q1）

- **address**: 小文字・ハイフンありの UUID（36 文字）。デバイスを作ったときに決め、**持ち主（アイテムのデータ・ブロックエンティティの NBT）が保存する**。大文字は受けない（`DeviceAddress.parse`）
  - 画面・キーボードの BE は文字列タグ `DeviceAddress`、ケースの BE はケースに付いたスピーカーの address を `SpeakerAddress` に持つ。タグがない（古いワールド）・形が違うときは、最初に要ったときに新しく振って保存する
  - 1台のマシンの中で重なれば `DeviceBus.attach` が断る（`IllegalArgumentException`）。ブロックの複製などで重なったときの振り直しは #129
- **kind**: `<名前空間>:<名前>`。文字の規則は Minecraft の ResourceLocation と同じ（名前空間 `[a-z0-9._-]+`、名前 `[a-z0-9/._-]+`、`:` はちょうど1つ）で、合わせて 64 バイトまで。例: `kibicomputers:disk-drive`・`mymod:fusion-reactor/core`
- **メソッド名**: `[a-z][a-z0-9_-]*`、64 バイトまで

## 部品とデバイスの対応、乗るもの

拡張カード1枚・周辺機器のブロック1つ = デバイス1つ。CPU・RAM・起動部品はマシンの設定（`MachineConfig`）で、デバイスにしない。GPU はケースのティアの enum を増やさず、`kibicomputers:gpu` のデバイスにする。

| もの | kind（予定を含む） | 呼び方 | Issue |
| --- | --- | --- | --- |
| 画面 | `kibicomputers:screen` | `get-size`（直接）。中身は表示デバイス（ADR 0355）。コンソールの出力は最初につないだ画面に書く | #65・#138 |
| キーボード | `kibicomputers:keyboard` | メソッドなし。入力は TTY へ。イベントは #326 | #65・#326 |
| スピーカー（ケース） | `kibicomputers:speaker` | `beep(frequency, duration-ms) -> boolean`（直接。通知の列に積むだけ） | #65 |
| GPU | `kibicomputers:gpu` | 描画の命令（直接。表示デバイスのロックを持って書く）。速さが要れば型付きの層 | #137・#141 |
| ディスクドライブ | `kibicomputers:disk-drive` | 入っているメディアの情報（メインスレッド）。中身はディスクの FS（#56） | #56 |
| レッドストーン I/O | `kibicomputers:redstone-io` | 入力の読み・出力の設定（メインスレッド）。変化はイベント | #152 |
| ネットワークカード | `kibicomputers:network-card` | 送信（直接で列に積む）、受信はイベント | #162 |
| インターネットカード | `kibicomputers:internet-card` | 要求を積んで、終わりを Deferred で返す | #172 |
| ロボットの体 | `kibicomputers:robot-body` | 移動・インベントリ（メインスレッド。数 tick かかるものは Deferred） | #177 |
| 他 MOD のブロック | `<modid>:<名前>` | 作者が `Device` を実装する（NeoForge では `BlockCapability<Device, Direction>`。#153） | #153 |

## 呼び出し

### 2種類と Deferred

- **直接（`DeviceMethods.Builder.direct`）**: 呼んだプロセスのスレッドで、その場で実行する。カーネルロックは持たない。作者がスレッドの安全を受け持つ（デバイス自身のロックで守る）。`DeviceCallContext.consumeFuel` で重さを燃料で払える
- **メインスレッド（`mainThread`。既定の推奨）**: `Machine.submitHostCall`（ADR 0044 のホスト呼び出し）で列に積み、`tick()` の中で実行する。1 tick に `maxHostCallsPerTick` まで。ワールドに触ってよい
- **Deferred**: メインスレッドのメソッドの中で `DeviceCallContext.defer()` を呼ぶと、結果を後の tick で `DeviceCompletion` から返せる（どのスレッドからでも。2回目以降は無視）。終わるまでホスト呼び出しの件数は使わない。マシンを止めると `isCancelled()` が true になり、待っている呼び出しは `interrupted` で終わる

### 待つ・待たない（Q3）

- `invoke`: 結果まで待つ。メインスレッドのメソッドは、プロセスが CPU を返して待つ（ADR 0043 の `awaitHost`）
- `start` → `pending-call`: 待たずに積む。`pending-call` はカーネルのオブジェクトで、p1 では fd（終わると読める。`poll_oneoff` で待てる）。1プロセス 16 個まで（仮の値）。超えたら `kernel(again)`

### 値（Q2）

入れ子のない値の6種（`DeviceValue` / WIT の `value`）: `none`・`integer(s64)`・`float(f64)`・`boolean(bool)`・`text(string)`・`bytes(list<u8>)`。引数と結果はその並び。

- 型付きの WIT interface（`kibi:gpu` など）は、速さが要ると測れた組み込みのデバイスだけ、後で `invoke` の上の薄い層として足す。足しても `invoke` は残す
- 上限（仮の値。確定は #251）: 値は 32 個、text・bytes の合計は引数と結果それぞれ 64 KiB、メソッド名・イベント名は 64 バイト。燃料は1回 `CALL_FUEL = 1000` + 引数と結果のバイト数
- `DeviceValue.getType()` の `Type` は増えうるので、`switch` には `default` を書く

### 失敗（WIT の `call-error`）

| 失敗 | 何が起きたか |
| --- | --- |
| `kernel(error-code)` | 上限（`too-big`・`name-too-long`・`again`）、ハンドル表の満杯など |
| `detached` | デバイスが外れた（開いたハンドルは残り、呼ぶたびに `detached`） |
| `no-such-method` | そのメソッドがない |
| `bad-arguments(msg)` | `DeviceArguments.get*` の型が違う（`argument #N: integer expected, got text` の形） |
| `failed(msg)` | 作者が `DeviceException` を投げた・`DeviceCompletion.fail`。ほかの例外は `failed("internal error")` にしてログに出す（マシンは止めない）。中断は `failed("interrupted")` |

## イベント

`DeviceContext.postEvent(kind, values...)` で、マシンのイベントキュー（ADR 0063）に `source` = address のイベントを入れる。値は同じ種類の `EventValue` に写し、`none` は載せない。上限を超えた・外れた後は `false` を返して捨てる。接続・切断のイベントは #67。

## WIT（`kibi:device@0.1.0`）

置き場は `core/src/main/wit/kibi-device/`（`types.wit`・`bus.wit`・`world.wit`。`world imports`）。

- `bus.list-devices() -> list<device-info>`（`list` は WIT のキーワードなので、WASI の「動詞-目的語」に合わせた）
- `resource device`（`open`・`info`・`methods`・`invoke`・`start`）、`resource pending-call`（`take`）
- 値の case 名は `kibi:kernel/events` の `event-value` と同じ「値の種類」の言葉にした。`event-value` は #65 で `%s64`・`%f64`・`%bool` から `integer`・`float`・`boolean` に改名した（Kotlin の `EventValue.I64`・`F64`・`Bool` も `Integer`・`Float`・`Boolean` に。ABI・保存形式は変わらない）
- 判別子の番号は合わない: `value` は none=0・integer=1・float=2・boolean=3・text=4・bytes=5、`event-value` は integer=0・float=1・boolean=2・text=3・bytes=4。カーネルの中では番号でなく種類で写すので困らない
- `/dev/device/<address>` を開くと `device` resource と同じ fd になる（Q5。実装は #54）。Lua の `device.list()` などは #94

## 公開 API（Q4、`pro.ikili.kibicomputers.api.device`）

`Device`（`getKind`・`getMethods`・既定の `onAttached`・`onDetached`）、`DeviceMethods`（`builder()`・`empty()`・`getNames()`）、`DeviceMethodHandler`（`fun interface`。`@Throws(DeviceException)`）、`DeviceCallContext`、`DeviceCompletion`、`DeviceContext`、`DeviceValue`、`DeviceArguments`、`DeviceException`。

- メソッドの表はつないだときに1回だけ読む（変わらない表）
- Kotlin の関数型・`suspend`・value class は出さない。Java からの使い方は `DeviceApiJavaTest`
- MC 依存の部分（NeoForge の `BlockCapability`）は #153

## 保存（Q6）

カーネルの保存に入れるのは、開いたハンドル（`DeviceHandle.Snapshot(address, kind)`）と `pending-call` だけ。デバイスの中身は持ち主が保存する。

- `PendingDeviceCall.Snapshot` は `Queued(address, method, args)`（まだ実行していない）か `Done(result)`。実行中（メインスレッドに積んだ・Deferred）のものは、写しを取るときに `Done(failed("interrupted"))` にする
- 戻したときの `Queued` は、改めて振り分けに回す（`Machine.dispatchPendingDeviceCall`）
- バイトの形は #81・#82

## スレッド・ロック・負荷

- ロックの順は **デバイスのロック → カーネルロック**（ADR 0355 と同じ）。直接のメソッドはカーネルロックを持たずに呼ぶ
- `DeviceBus` の表は copy-on-write（つなぐ・外すときだけ作り直す）で、`list`・`find` はロックなしで読む。つなぐ・外すはどのスレッドからでもよく、そのたびに `generation` が上がる
- `core` のデバイスは tick を持たない。メインスレッドが動くのは、メインスレッドのメソッドの呼び出しがあるマシンと、ビープなどの通知があるマシンだけ。**tick ごとのコストは台数とデバイス数に比例しない**（サーバー全体の予算は #356）
- スピーカーのビープは通知の列（`HostNotification.Callback`）に積み、`tick()` の中でメインスレッドから受け手（`SpeakerListener`）に渡す。配っていない分は 8 個まで（ADR 0044 と同じ上限。`droppedBeeps`）

## 仮の結線を置き換える道筋

1. この Issue: `TestComputerWiring` が `ScreenDevice`・`KeyboardDevice`・`SpeakerDevice` を `DeviceBus` につなぐ（#65 で `MachineConsole` を `ScreenDevice` に、`MachineConsole.keyboardInput` を `KeyboardDevice` に、`MachineHost.onBeep` を `SpeakerDevice` に、`Machine.notifyDevicesChanged()` を `DeviceBus` に移した）。コンソールの出力先は `DeviceBus.primaryScreen`、画面がなければ `MachineHost.onConsoleOutput`。振る舞いは変えない。NeoForge のスピーカーの受け手は、まだ音を鳴らさない
2. #129: 隣接の検出と登録表に置き換え、`TestComputerWiring` を消す
3. #137 以降: カード1枚 = デバイス1つで足す

## テスト

- `DeviceAddressTest`・`DeviceBusTest`・`DeviceDispatcherTest`・`PendingDeviceCallTest`・`SpeakerDeviceTest`・`KeyboardDeviceTest`・`ScreenDevice*Test`・`DeviceValueTest`（`core`）
- `DeviceApiJavaTest`（Java から公開 API を使う）
- `KibiWitTableTest`: `kibi:device` の `package`・`world imports`、`%` の識別子がないこと、`value` と `event-value` の case 名がそろうこと
- GameTest `devicesAreAttachedWithSavedAddresses`: 電源を入れるとケース・画面・キーボードの address でつながり、address は読み直しても同じ
