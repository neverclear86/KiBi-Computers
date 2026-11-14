# 0077: 独自拡張 kibi:device（デバイスの列挙と操作を WASM に出す）

- 状態: 採用
- Issue: #77（task）。前提: ADR 0065（デバイスのモデルと WIT `kibi:device@0.1.0`）、0066（接続数の上限とハンドルの呼び出し。デバイスは1回だけ引く）、0054（`/dev/device`）、0073（`kibi:*` の WIT と p1 の import の導き方）、0264（Canonical ABI）、0329（待機点への変換）
- 後続: #108（標準 OS のコマンドと Zig の束縛。ADR 0067 の接続・切断のイベントを受けるのもここ）、#94（Lua の `device`）、#116（SDK の束縛）、#685・#266（p2・p3 の層）、#251（仮の値の確定）

## 決めたこと（要約）

- p1 に出すのは `kibi:device/bus@0.1.0` だけ。WIT（`types`・`bus`）は #65 で決まった形のまま変えず、Canonical ABI の core の形をそのまま導く（ADR 0073）。関数のない `kibi:device/types@0.1.0` と、p2・p3 向けの `bus-poll`・`bus-async` を import すると `NOEXEC`
- resource（`device`・`pending-call`）のハンドルはカーネルの fd 番号。`/dev/device/<address>` を `path_open` で開いた fd も `device` として使える
- 止まりうる import は `[method]device.invoke` だけ（`WasiP1.WAIT_IMPORTS`）。メインスレッドのメソッドは結果まで待つ。待たない設定（`P1Wait.NONE`）では積む前に `kernel(again)`
- 待機点型の `invoke` は、待つ間の状態を呼んだプロセスの fd 表に入れた `PendingDeviceCall` に持つ。**保存形式は変えない**（#65 の Q6 のまま当てはまる）
- `invoke` の道でデバイスを引くのは1回だけ（ADR 0066）。「メインスレッドか」を確かめたデバイスで実行する
- 公開 API（`pro.ikili.kibicomputers.api`）は変えない。足したカーネルの口はすべて internal
- p2・p3 の WIT（`bus-poll`・`bus-async`）は仮置き。実装しない。p2・p3 の層（#685・#266）を作るときに直してよい（v0.1.0 までは版を変えずに直す。ADR 0073）

## p1 の import（`kibi:device/bus@0.1.0`）

| 関数 | core の型 | 止まりうる | 結果の書き先（大きさ / 揃え） |
| --- | --- | --- | --- |
| `list-devices` | `(ret) -> ()` | いいえ | `list<device-info>`（8 / 4） |
| `[static]device.open` | `(addr_ptr, addr_len, ret) -> ()` | いいえ | `result<device, call-error>`（16 / 4） |
| `[method]device.info` | `(self, ret) -> ()` | いいえ | `device-info`（16 / 4） |
| `[method]device.methods` | `(self, ret) -> ()` | いいえ | `list<method-info>`（8 / 4） |
| `[method]device.invoke` | `(self, m_ptr, m_len, a_ptr, a_len, ret) -> ()` | **はい** | `result<list<value>, call-error>`（16 / 4） |
| `[method]device.start` | `(self, m_ptr, m_len, a_ptr, a_len, ret) -> ()` | いいえ | `result<pending-call, call-error>`（16 / 4） |
| `[method]pending-call.take` | `(self, ret) -> ()` | いいえ | `option<result<list<value>, call-error>>`（20 / 4） |
| `[resource-drop]device` | `(self) -> ()` | いいえ | なし |
| `[resource-drop]pending-call` | `(self) -> ()` | いいえ | なし |

- drop 以外はすべて `cabi_realloc` が要る（`open`・`start` も `call-error` に string があるため）。export していなければ読み込みで `NOEXEC`
- C の宣言は `core/src/main/wasm/common/kibi_device.h`（`_Static_assert` で並びを確かめる）

### メモリの並び（Canonical ABI）

- `value`: 16 / 8。tag(u8) @0、payload @8（s64・f64 は @8、bool は u8 @8、string・list<u8> は ptr @8・len @12）。判別子は none=0・integer=1・float=2・boolean=3・text=4・bytes=5（`kibi:kernel/events` の `event-value` とは番号が違う。`P1Canonical` の定数も `DEVICE_VALUE_*` と名前を分けた）
- `device-info`: 16 / 4（address @0/@4、kind @8/@12）。`method-info`: 12 / 4（name @0/@4、main-thread u8 @8）
- `call-error`: 12 / 4。tag @0、payload @4（`kernel` は error-code の u8、`bad-arguments`・`failed` は string @4/@8）。判別子は kernel=0・detached=1・no-such-method=2・bad-arguments=3・failed=4
- `result<…, call-error>`: tag @0、ok・err は @4 から（16 / 4）。`option<result<…>>`: tag @0、中の result は @4（20 / 4）
- `cabi_realloc` を呼ぶ順は Component Model と同じ（外側の list → 要素の string・bytes を前から）

## 引数とハンドルの誤り

- 確かめる順は引数の順: `method` → `args` → ハンドル。Component Model（全引数を lift してから呼ぶ）とは、どの誤りが先に出るかが違うことがある（既知。#616）
- `method` が 64 バイトを超えたら読まずに `kernel(name-too-long)`。`args` が 32 個を超えたら要素を読まずに `kernel(too-big)`。text・bytes の長さの合計が 64 KiB を超えたら中身を読む前に `kernel(too-big)`（上限は `DeviceLimits` の定数をそのまま使い、`DeviceDispatcher.start` と揃える）
- `args` の ptr は align 8。揃っていなければ、長さ 0 でもほかの検査より先にトラップ（#430）
- `value` の判別子が 0..5 の外、UTF-8 の誤りは `kernel(invalid)`。範囲外のメモリは `kernel(fault)`。bool は 0 でなければ true
- `open` の address が 36 バイトでなければ読まずに `detached`。大文字など、見つからないものも `detached`。fd 表が満杯なら `kernel(mfile)`。開いた fd の権利は READ だけ（ADR 0054）
- ハンドルの誤り: 結果に error のない `info`・`methods` と `[resource-drop]*` は、不正なハンドル・型の違うハンドルでトラップ。`invoke`・`start`・`take` は `kernel(bad-descriptor)`（前例: ADR 0074、`kibi:kernel/events` の drop）
- 結果の書き先（`ret`）と `cabi_realloc` が返した場所の誤りはトラップ（ADR 0073）
- 返す文字列は UTF-8。対になっていないサロゲートは U+FFFD にし、場所を取る長さも実際に書くバイト列で数える（`DeviceLimits.utf8Length` は使わない）

## `invoke` の流れ

1. 入口で、`cabi_realloc` の中（`inGuestCall`）ならトラップ。巻き直しの処理と燃料より前、メソッドの種類も見ずに弾く（ADR 0073）
2. `ProcessContext.beginDeviceInvoke` がデバイスを1回だけ引く
   - 直接のメソッド: その場で実行して結果を返す（`pending-call` も fd も作らない。何度も呼ぶ道を軽く保つ）
   - メインスレッドのメソッド: `callGuest` の中なら、`mayWait` に依らず積む前にトラップ（入口で弾き忘れたときのカーネル側の守り。ADR 0073）。`P1Wait.NONE` なら積む前に `kernel(again)`。そうでなければ `PendingDeviceCall(Queued)` を呼んだプロセスの fd 表に入れ（読む権利だけ）、ロックを放してから、引いた `AttachedDevice` をそのまま `Machine.dispatchPendingDeviceCall(pid, obj, device)` に渡す（引き直さない）
3. 待機点型（`UNWIND`）は、その fd と key（`READABLE`）を実行時だけの欄に持って巻き戻す。巻き直して着いたら、結果があれば受け取って fd を閉じ、なければ同じ key でもう一度巻き戻す。ブロッキング型（`BLOCK`）は今までの `ProcessContext.invokeDevice` で待つ
4. 結果は `ctx.callGuest { }` の中で書く

### デバイスを1回だけ引く

`Machine.dispatchPendingDeviceCall` を2つに分けた（どちらも internal）。

- `dispatchPendingDeviceCall(pid, obj, expectedKind)`: ロックの中で `Queued` を確かめて address を読み、引いてから後者に渡す。`startDeviceCall` と、保存から戻した積み直し（`redispatchRestoredDeviceCalls`）が使う（振る舞いは変えない）
- `dispatchPendingDeviceCall(pid, obj, device: AttachedDevice?)`: 呼んだ側が引いたデバイスで回す（`null` = 外れている・種類が違う）。ここでは引き直さない

確かめた後に同じ address のデバイスが付け替わっても、確かめた方で実行する（付け替わった方には届かない）。

### 待つ間の状態と保存（形式は変えない）

- 待つ間の状態は fd 表の `PendingDeviceCall` そのもの。保存は #65 の Q6 のまま（`Queued` はそのまま、実行中は `Done(failed("interrupted"))`、`Done` はそのまま写る）。復元後の `Queued` は `redispatchRestoredDeviceCalls` が今までどおり積み直す
- 巻き直しで前の呼び出しを見つける: 実行時の欄があればそれを使う。なければ（保存からの復元・インスタンスの作り直し）、`WasiP1Driver.resume` に渡された起こした key を `WasiP1Suspend.onResume` で受け取り（`resumedReady`）、そのうち自分の fd 表の `PendingDeviceCall` を指すもの（`ProcessContext.pendingDeviceCallFdOf`）を前の呼び出しとする。燃料で巻き戻した場合は `invoke` の中で巻き直しにならないので、ここで取り違えない
- 前の呼び出しを見つけられないとき（起きないはず）は、やり直さずに `failed("interrupted")` を返す（メソッドを二重に実行しない）
- 待つ間に kill されたら、`ProcessRunner.finishLocked` が fd 表を閉じ、`pending-call` の最後の fd が閉じて取り消される（#532 の既存の道）
- 待つ間は fd を1つ使う（満杯なら `kernel(mfile)`）。`start` の上限（1プロセス 16 個。`MAX_PENDING_CALLS`）には数えない（`openPendingDeviceCallLocked(countTowardLimit = false)`）。invoke は1プロセスに同時に1つしかないので、`start` を 16 個積んだままでも invoke できる
- 待機点型で待っている間は保存できる（TRANSIENT にならない）

取らなかった案:

- A1（実行時だけに持つ。`spawn` と同じ）: 復元の後に巻き直してやり直すので、メインスレッドのメソッド（ロボットの移動など）が二重に走り、実行中のものが Q6 の `interrupted` にならない
- A2（`Process` に objectId を足し、`KERNEL` 節の版を上げる）: 世代ファイルの中身を変える（#85 で人に聞くもの）。fd 表に持てば同じ性質が得られるので要らない

### 読み込みのキャッシュ

`WAIT_IMPORTS`（`ModuleSource.waitImports`）に `kibi:device/bus@0.1.0.[method]device.invoke` が加わるので、読み込みのキャッシュの key が変わる。これまで `kibi:device` を import するプログラムは `NOEXEC` で読めなかったので、古い保存との互換は崩れない。

## ほかの関数

- `list-devices`・`info`・`methods`: 待たない。`cabi_realloc` の中からも呼べる。一覧はロックの中で写し、放してから `cabi_realloc` を呼ぶ。`list-devices` は接続を変えたときだけ作り直す一覧を写す。`info` は外れていても元の値を返し、`methods` は外れている・種類が違うなら空
- `start`: 止まりうる import に入れない（直接のメソッドはその場で実行するが、待たない）。16 個を超えたら `kernel(again)`
- `take`: `ProcessContext.takeDeviceCall` を写す。fd は閉じない。何度でも同じ結果
- `[resource-drop]device`・`[resource-drop]pending-call`: その型の fd を閉じる（終わる前の `pending-call` は取り消す）

## 燃料（仮の値。確定は #251）

- 各関数 `WasiP1Fd.CALL_FUEL`
- `list-devices`・`methods` は件数 × `KibiDevice.DEVICE_LIST_FUEL_PER_ENTRY`（10。`list-processes` の前例）を加える
- `invoke`・`start`・`take` は、カーネルが `DeviceLimits` の分（呼び出しの分と結果のバイト数）を払う

## p2・p3 の WIT（仮置き。実装しない）

`core/src/main/wit/kibi-device/`:

- `bus-poll.wit`（p2）: `subscribe: func(call: borrow<pending-call>) -> pollable`（結果が出ると ready。p1 の `FD_READ` と同じ条件）。`wasi:io/poll@0.2.3`
- `bus-async.wit`（p3。`wasi:*` に依存しない）: `invoke: async func(...)`（メインスレッドのメソッドも待てる）と、`start` で積んだ呼び出しを待つ `wait: async func(call: borrow<pending-call>) -> result<list<value>, call-error>`（p3 には pollable がないため。`take` と同じく閉じない）
- `world.wit`: `imports-p2`（`include imports` ＋ `bus-poll`）・`imports-p3`（`include imports` ＋ `bus-async`）

## 規模（1,000 台）

- tick ごとの処理は足さない。列挙は作り置きの一覧を写すだけ
- 直接のメソッドの `invoke` は、割り当て以外のカーネルの道を通らない（fd も `pending-call` も作らない）
- メインスレッドのメソッドを待つ台は、待機点型ならスレッドを持たずに待つ

## テスト

- `WasiP1KibiDeviceTest`: 直接 / メインスレッド × `UNWIND` / `BLOCK` / `NONE`、`start` → `poll_oneoff` → `take`、列挙と `/dev/device` の fd、外れた後・種類の違うデバイス、境界の値、`cabi_realloc` の中の `invoke` のトラップ、ハンドルの誤り、kill での取り消し、実行時の欄を忘れた巻き直し、デバイスを1回だけ引くこと（テスト用のフック `KernelTestHooks.afterDeviceInvokeResolve` で、引いた後に付け替える）
- `WasiP1KibiDeviceRestoreTest`: 待つ間に保存して戻す（`Queued`・実行中・`Done`）。メソッドは保存の前後を通して1回だけ走り、実行中のものは `failed("interrupted")`
- `WasiP1ImportCheckTest`・`KibiWitTableTest`・`KibiWitParseTest`: import の型・`cabi_realloc` の要否・p2・p3 の interface を断ること、WIT との突き合わせ
- `GuestCallGuardTest`: `callGuest` の中からメインスレッドのメソッドを `beginDeviceInvoke` で呼ぶとトラップ（待機点型・ブロッキング型、`mayWait` が `false` でも）、直接のメソッドは呼べる
