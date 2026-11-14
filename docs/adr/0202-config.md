# 0202: コンフィグ基盤

- 状態: 採用
- Issue: #202（task）。親: #201。前提: #22（プリセットと個別の上書き）
- 後続: #203（プリセット）、#204（アップロードの制限）、#205（リソースの上限）、#251（既定値の確定）、#224（1.20.1 の使う側の移植）

## 背景

これまで、スケジューラ（ADR 0027・0029）、待機点保存（ADR 0081〜0085）、画面の同期（#352）、キーボードの入力（#142）の値は、コードの既定の値を固定で使っていた。#201 は「コンフィグで設定できるものは、基本的にすべて設定可能にする」。#203・#204・#205 は、項目を足すたびに両バージョンの接続層を直さずに済む土台を必要とする。

## 決めたこと

### 定義は core に1つ

- 項目の定義は `core` の `pro.ikili.kibicomputers.core.config` に置く（すべて `@ApiStatus.Internal`。公開 API には出さない）。
  - `ConfigEntry`（`IntEntry`・`LongEntry`・`DoubleEntry`・`BooleanEntry`）: 分類、区画、キー、既定、最小・最大、「自動」の番兵、いつ効くか、説明（英語・日本語）、lang のキー。
  - `KiBiConfig`: 項目の一覧（`ENTRIES`）と、値から core の設定（`SchedulerConfig`・`MachineSaveConfig`・`ScreenSyncTraffic`・`KeyboardInputBudget`）を組み立てる関数。
  - `ConfigValues`: 値の変わらない写し。`ConfigState`: 今の写しを `@Volatile` の参照1つで持つ。
- 接続層（`platform-*` の `config/KiBiConfigs`）は、`ENTRIES` を走査して各ローダーの spec（`ModConfigSpec`・`ForgeConfigSpec`）に写す汎用のコードだけにする。項目ごとのコードは書かない。項目を足すときは、`KiBiConfig` に宣言を1つ足し、1.21.1 の lang に名前と説明を足す（キーの書き漏れは GameTest が落とす）。

### 分類とファイル名

- 分類は SERVER・CLIENT・COMMON の3つ。NeoForge にしかない `STARTUP` は使わない（1.20.1 と食い違うため）。項目のない分類は登録しない（今は SERVER だけ）。
- ファイル名は両バージョンで同じ `kibicomputers-<server|client|common>.toml`、キーの木も同じにする。MOD パックを作る人が、バージョンをまたいで設定を流用できる。
- SERVER のファイルの置き場はローダーに従う。NeoForge 21.1 は `config/kibicomputers-server.toml`（ワールドの `serverconfig/` に同じ名前のファイルがあればそちらが優先）。Forge 1.20.1 は `<world>/serverconfig/kibicomputers-server.toml`（なければ `defaultconfigs/` から写す）。
- 今の区画: `scheduler`（スレッドとサーバー全体の上限）、`save`（待機点保存）、`screenSync`（画面の同期の送信量）、`keyboard`（キーボードの入力の量）。

### 値の流れと読み直し

- SERVER のファイルは、ローダーが `ServerLifecycleHooks.handleServerAboutToStart` の中で、`ServerAboutToStartEvent` より前に読む（両バージョンのソースで確かめた。GameTest でも確かめる）。`ModConfigEvent.Loading` で写しを作り、`ConfigState` に入れる。
- 1.21.1 の `ServerMachines.onServerAboutToStart` は、その写しから `SchedulerConfig` を作って `MachineManager` を作る。
- 読み直し（`ModConfigEvent.Reloading`）はファイルを見張るスレッドから来る。そこでは写しを作って参照を差し替えるだけで、マシンの状態に触らない。使う側はメインスレッドの tick の始めに参照を1回比べ、変わっていたときだけ当てる（tick ごとのコストは参照の比較1回。台数によらない）。
  - `save` の値: `ServerMachines` が `MachineSaveConfig` を作り直し、中身が変わったときだけ自動保存の列と書き込みの上限に当てる。
  - `screenSync` の値: 予算が変わったときだけ `ScreenSyncTraffic` を作り直す（プレイヤーごとの勘定は空に戻る）。
  - `keyboard` の値: 次に作るキーボードの予算から効く（作った予算は変えない）。
- `scheduler` の値（スレッドの数・スタック・許可の数・キャッシュ・RAM の上限）は、スケジューラを作るときに決まる。spec では `worldRestart()` にし、読み直しでは当てない。読み直しで変わったら「次にサーバー（ワールド）を開いたときに効く」とログに出す。動いている 1,000 台を止めて作り直すのは重く、壊れやすいため。
  - NeoForge の `ConfigValue.get()` は `worldRestart` の項目を読み直しでも変えないので、接続層は `getRaw()` で読み、変化を見つける。
- 値が範囲の外・型の違いなら（spec が直すので普通は起きない）、その項目は既定にしてログに出す。
- GameTest・負荷試験が `ServerMachines.saveConfig` を差し替えるときは、コンフィグの値とは別の層（上書き）に入れる。上書きをコンフィグの値と同じ値に戻したら外れ、その後の読み直しが効く。

### 自動の既定と単位

- 機械によって変わる既定（`maxConcurrentMachines` = CPU のスレッド数の半分、`totalMemoryMiB` = JVM のヒープの最大値の半分）は、数をファイルに書かず、番兵 0 を「自動」とする。解釈は `KiBiConfig.schedulerConfig` で行う（`SchedulerConfig` の既定に任せる）。最初に起動した機械の数がファイルに残り、`defaultconfigs/` で配られるのを防ぐ。
- バイト数は、書きやすい単位の整数（`...MiB`・`...KiB`。キー名に単位を入れる）で受け、バイトの `Long` に直すのは `KiBiConfig` の組み立てだけにする（2 GiB を超えても桁があふれない）。
- 範囲は spec と core の `require` の両方にある。全項目の最小と最大で組み立てても例外にならないことを、core の単体テストで確かめる。

### 説明（日英）

- TOML のコメントは言語を切り替えられず、1.20.1 には設定画面もないので、項目のコメントに英語と日本語の両方を書く（UTF-8）。区画のコメントも同じ。
- 1.21.1 は NeoForge の組み込みの設定画面（`ConfigurationScreen`。MOD の一覧の「設定」）を登録する。名前と説明は lang（`kibicomputers.configuration.<区画>.<キー>` と `.tooltip`）の en_us・ja_jp。1.20.1 には lang がまだないので、画面も lang も #224 で移植するときに足す。

### プリセット（#203）への備え

- 値を決めるのは `ConfigValues.resolve` の1か所。今は「ファイルの値 ?: 既定」で、#203 ではここを「明示した上書き ?: プリセット ?: 既定」にする。「上書きしていない」の表し方（番兵か、キーがないことか）は #203 で決める。

## 保存との関係

- TOML はブロックエンティティの NBT や世代ファイルではないので、保存形式（AGENTS.md の2番目）には当たらない。保存形式は変えていない。
- ただし、値を下げると既存のワールドに効くものがある。今の項目では `totalMemoryMiB` を下げると、復元した台の `memory.grow` や新しいプロセスが断られる（#362 の上限と同じ振る舞いで、壊れはしない）。マシンごとの上限（`MachineConfig`・`PartTier` の値）をコンフィグにするとき（#205 など）は、下げると保存済みの台が冷えた起動になること（`MachineRestore` は今の設定で写しを復号する）を、その Issue で扱う。

## 今の範囲に入れなかったもの

- `const val` のマシンごとの上限（`Machine`・`DeviceLimits`・`Pipe`・`Tty`・`EventLimits`・`HostFiles` など）と `PartTier` の値、`RebootBackoff.DEFAULT`。`MachineConfig` を通す作り直しが要り、#205 と重なるので別の Issue にする。
- CLIENT の項目（描画の細かさなど）。仕組みは分類を問わないので、項目を足せば登録される。
- 1.20.1 の使う側（スケジューラなど）。1.20.1 の接続層はまだ雛形なので、spec の登録と写しを作るところまで（#224）。
