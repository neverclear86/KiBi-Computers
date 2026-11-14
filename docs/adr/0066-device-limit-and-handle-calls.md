# 0066: 接続数の上限と、開いたデバイスからの呼び出し

- 状態: 採用
- Issue: #66（task）。前提: ADR 0065（デバイスモデル）、ADR 0073（`cabi_realloc` の中で待たない）
- 後続: #77（WASM に出す。ADR 0077 で済んだ）、#54（`/dev/device`。ADR 0054 で済んだ）、#94（Lua の `device`）、#129（隣接の検出と登録表。空いたときのつなぎ直し）、
  #67（接続・切断のイベント）、#122（CPU の性能）、#251（仮の値の確定）
- 番号は Issue 番号にそろえている

## 範囲

列挙（`DeviceBus.list`・`ProcessContext.listDevices`）と、呼び出しの同期・非同期・メインスレッドへの受け渡し（`DeviceDispatcher`、
`invokeDevice`・`startDeviceCall`・`takeDeviceCall`、取り消し）は #65（PR #529・#532・#536）で入った。この ADR は残りの3つを決める。

1. CPU で決まる接続数の上限
2. 開いたデバイス（fd、`DeviceHandle`）から `info`・`methods`・`invoke`・`start` を呼ぶカーネルの口（WIT の `device` resource のメソッド）
3. 列挙の割り当てをなくす

WIT（`kibi-device`）、公開 API（`pro.ikili.kibicomputers.api.device`）、保存形式は変えない。

## 接続数の上限

- `MachineConfig.maxDevices`（Builder は 1 以上）。`MachineSpecs.configOf` が CPU のスロットのうち最大の `PartTier.cpuMaxDevices` を入れる。CPU がなければ既定
- 数えるのは `DeviceBus` につないだもの全部（ケースのスピーカーも1つ）。種類で重みは付けない（単純で予測しやすい）
- 仮の値（確定は #122・#251 で、どちらにもこの値をコメントで残した。OpenComputers の CPU の刻みに近づけた）

  | | 接続数 |
  | --- | --- |
  | TIER_1 | 8 |
  | TIER_2 | 12 |
  | TIER_3 | 16 |
  | TIER_4 | 32 |
  | CREATIVE | 256 |
  | `MachineConfig.DEFAULT` | 16 |

  最低のティアでも、スピーカー・画面・キーボードの3つは入る
- 満杯なら `DeviceBus.attach` は `false` を返し、何もしない（表・`generation`・`primaryScreen` を変えず、`onAttached` も呼ばない）。
  例外は投げず、マシンも止めない。ゲストからは、つながっていないのと同じに見える（新しい ABI はない）。
  address の形の誤り・重複・種類の形の誤りは、今までどおり `IllegalArgumentException`（プログラムの誤り）
- 判定は `writeLock` の中で、数えてから入れるまでを同じロックで行う（並んだ `attach` でも越えない）。`onAttached` が投げたときの戻しで数も戻る
- `DeviceBus.limit`・`size` はロックなしで読める。接続層（#129）はこれを見て、満杯なら重い準備（表示デバイスを作るなど）を省ける
- 空いても core は自分でつなぎ直さない（tick ごとの巡回を足さない）。つなぎ直すのは接続層（今は `TestComputerWiring.reconnectScreen`、正式には #129）
  - 追記（#129）: 接続層の結線（`MachineWiring`）が、自分のデバイスが外れたときに断った候補を見直す（tick の終わりに1回。ADR 0129）
- 上限は電源を入れたとき・戻したときに決まる。CPU を抜くとマシンは止まるので、動いている間は変わらない。**保存しない**（`KernelState` に入れない）。
  戻したときの上限が保存したときより小さいと、つなげなかったデバイスへのハンドル・積んだ呼び出しは `detached` になる（ADR 0065 の決まりどおり）。
  保存 → 上限の小さいマシンへの復元 → `redispatchRestoredDeviceCalls` の経路は `DeviceLimitRestoreTest` で確かめている
- 仮の結線（`TestComputerWiring`）は、`attach` の結果を見てから自分の状態を確定する。断られたキーボード、`attach` が例外で抜けた（address の重複）キーボードは手放す（ほかのケースが使える）。
  断られたことはログに1回だけ出す。つなぐ順はスピーカー → 画面 → キーボード
  - 追記（#129）: 結線は `MachineWiring` になった。断られたもの・例外で抜けたものは手放す（ほかの結線に知らせる）。address の重複は例外にせず、後から来たほうを振り直す。つなぐ順はスピーカー → 隣（下・上・北・南・西・東）→ 画面の中継のキーボード（ADR 0129）

## 開いたデバイスからの呼び出し（`ProcessContext`）

| 口 | WIT | 外れている・種類が違う | device でない fd |
| --- | --- | --- | --- |
| `deviceInfo(fd)` | `info` | 開いたときの address と種類を返す | `BADF` |
| `deviceMethods(fd)` | `methods` | 空の一覧 | `BADF` |
| `invokeDevice(fd, …)` | `invoke` | `detached` | `BADF` |
| `startDeviceCall(fd, …)` | `start` | 積んだ呼び出しが `detached` で終わる | `BADF` |

- ハンドルは address と種類だけを持つ。デバイスは**毎回 address で引く**（つないだときの `AttachedDevice` に縛らない）。
  縛ると、戻したハンドルが後からつながったデバイスを使えない。外して同じ種類をつなぎ直せば、同じハンドルでまた呼べる
- 今その address につながっているデバイスの種類がハンドルの種類と違えば、外れているのと同じに扱う（ブロックの複製などで address が重なった場合。振り直しは #129）。
  address で呼ぶ口（`invokeDevice(address, …)` など）は今までどおり種類を見ない。戻した `Queued` の呼び出しは address しか保存していないので照合しない
- fd → `DeviceHandle` はカーネルロックの中で引き、ハンドラーはロックの外で呼ぶ（ロックの順はデバイス → カーネルのまま）
- メソッドの一覧は `MethodInfo(name, mainThread)`（内部の型）。メソッドの表は変わらないので、つないだときに1回だけ作る
- `listDevices`・`deviceInfo`・`deviceMethods` は待たず、燃料を取らず、どのスレッドからでも呼べる（`cabi_realloc` の中からも）。メインスレッドに回さない。
  WASM へ写す分の費用は #77 で決めた（ADR 0077。仮の値）。メインスレッドのメソッドを待つ `invoke` は、今までどおり `callGuest` の中では `WasmTrapException`（#430）
- デバイスは1回だけ引き、「メインスレッドのメソッドか」の確かめと実行に同じものを使う（`DeviceDispatcher.resolve` → `start(pid, device, …)`）。
  #65 の `invokeDevice` は2回引いていて、間に付け替えが起きると待機点型のプロセスが `checkMayBlock` で `IllegalStateException` になりえた

## 列挙の割り当て

- `DeviceBus.list()` は、表を作り直すとき（`writeLock` の中）に一緒に作った変わらない一覧を返す。表が変わるまで同じインスタンスで、呼ぶたびの割り当てはない
- 並びはつないだ順。戻したときやつなぎ直しで変わりうるので、ゲストは並びに頼れない
- `deviceInfo` は `DeviceHandle` が持つ変わらない値を返す

## 負荷

tick の処理は増やさない。メインスレッドの呼び出しの件数は `maxHostCallsPerTick` のまま。これに重ねて、サーバー全体の tick ごとの時間の予算がかかる（#356、ADR 0356）。
上限の判定は、つなぐときの `writeLock` の中で表の大きさを見るだけ。
