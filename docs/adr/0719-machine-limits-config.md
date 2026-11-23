# 0719: マシンごとの上限と部品の性能値をコンフィグにする

- 状態: 採用
- Issue: #719（task）。前提: ADR 0202（コンフィグ）、0066（接続数の上限）、0635（再起動の待ち）、0356（メインスレッドの予算）、0453（ディスクの使用量）
- 後続: #251（仮の値の確定）、#205（サーバー全体のリソースの上限）、#122・#123（CPU・RAM の正式なティア）

## 背景

#202 ではコンフィグの仕組みを作ったが、使う側に `const val` で埋め込まれたマシンごとの上限、`PartTier` の値、`RebootBackoff.DEFAULT` は範囲から外した（ADR 0202「今の範囲に入れなかったもの」）。

## 決めたこと

### どの定数をコンフィグにするか

定数を4つに分けた。コンフィグにしたのは (B)・(C) と、部品の性能値。

| 分類 | 定数 | 扱い |
| --- | --- | --- |
| (A) ゲストに見える形の上限 | `DeviceLimits` の全部（値の数・中身の大きさ・名前・メソッドの数・積んだ呼び出しの数）、`EventLimits` の `MAX_TYPE_BYTES`・`MAX_SOURCE_BYTES`・`MAX_VALUES`・`MAX_PAYLOAD_BYTES`・`MAX_FILTER_TYPES`・`MAX_CAPACITY`、`TimerLimits.MIN_INTERVAL_NANOS`、`Tty.MAX_LINE_BYTES` | `const` のまま。同梱 OS（`os/lib/kibi/device.zig`）・Lua（`kibi_lua_peripheral.c`）に同じ数が埋め込まれ、WIT の doc にも書いてある。変えると ABI の変更になる |
| (B) マシンごとの量の上限 | `maxProcesses`・`maxBlockingProcesses`・`maxHostCallsPerTick`（もともと `MachineConfig`）、`EventLimits.MAX_QUEUES`・`MAX_PENDING_BYTES`、`TimerLimits.MAX_ARMED_TIMERS`、`HOST_FILES_PER_MACHINE`、`CONSOLE_INPUT_BYTES`・`CONSOLE_OUTPUT_BYTES`・`MAX_PENDING_CONSOLE_BYTES` | `MachineConfig` のフィールドにし、`machine` 区画の項目にした。今の定数は既定として残す |
| (C) サーバー全体 | `HOST_FILES_TOTAL`、`mainThreadBudgetNanos`、`RebootBackoff` | `SchedulerConfig` に入れ、`scheduler`・`reboot` 区画の項目にした（次にワールドを開いたときに効く） |
| (D) 見積もり・費用 | `CORE_INSTANCE_OVERHEAD_BYTES`、`EventLimits.*_OVERHEAD_BYTES`、`CALL_FUEL`・`MOUNT_FUEL`・`CONSOLE_FUEL_PER_CELL` などの燃料の費用、`MAX_EXECUTABLE_BYTES` | コンフィグにしない。見積もりは JVM の上の実際の大きさを下回らないための値で、下げると RAM の勘定が甘くなる（サーバーを守る値で、管理者が調整する理由がない）。費用は #251 で確定する仮の値 |

入れなかったもの:

- `EventLimits.DEFAULT_CAPACITY`・`Pipes.DEFAULT_CAPACITY`: ゲストが大きさを指定しなかったときの既定で、上限ではない。台ごとに変える理由が薄いので入れなかった。
- `MachineLimits.DISK_CAPACITY_BYTES`・`DISK_MAX_ENTRIES`: ティアのないディスク（テスト用の口）の既定。ゲームのディスクはティアの値（`storage` 区画）を使う。
- #205 の範囲（燃料の配分、プレイヤーごとの台数など）。

### 部品の性能値

- `PartTier` は enum のまま、値は既定（`defaultSpec`）だけを持つ。ゲームで使う値は、コンフィグから組み立てた `MachineProfile`（`base: MachineConfig` とティアごとの `PartSpec`）から読む。`MachineSpecs.configOf`・`DiskStore.snapshot`・部品のツールチップはこれを受け取る。
- 区画は部品の種類ごと（`cpu`・`memory`・`storage`）、キーはティアを含む名前（`tier1FuelPerTick`・`creativeMaxDevices`・`tier2MiB` など）。CREATIVE も項目にした（検証用だが、サーバーの管理者が負荷試験などで上げ下げしたいことがあるため）。
- 正式なティアの CPU・RAM のアイテムはまだない（#122・#123）が、項目は全ティアに置いた。アイテムが入ったときにコンフィグの形を変えずに済む。
- 範囲: CPU の接続数は 3〜256（最低でも画面・キーボード・スピーカー。ADR 0066。256 は Lua の `list-devices` の結果の見積もり）。RAM は 1〜4096 MiB（合計は 4 GiB で頭打ち）。燃料は 100,000〜1,000,000,000（前借りの上限 50,000 の2倍以上）。ディスクの項目の数は 16〜65,536（電源を入れる・戻すときの数え直しとマウントの燃料が項目の数に比例するので、CREATIVE の値で頭打ち）。

### 値の流れ

- `KiBiConfig.machineProfile(values)` は、コンフィグの写し（`ConfigValues`）の参照ごとに1つ組み立てて覚え、同じ参照には同じものを返す。台ごと・tick ごとには作らない（1,000 台規模）。読み直しは別スレッドから来るので、写しと組み立てたものの組を `@Volatile` の参照1つで差し替える。
- 接続層は電源を入れるとき・戻すとき（`CaseBlockEntity.machineConfig`）と、ディスクを挿すとき（`prepareDisks`）に呼ぶ。`machine`・`cpu`・`memory`・`storage` は `ConfigApply.RELOAD`: 次に電源を入れた・読み込み直した台から効き、動いている台の設定は変わらない。
- `MachineConfig` の写しの口は `toBuilder()` の1つにした（`withFuelSlice` もこれを使う）。全フィールドが写ることを単体テストで確かめる。
- 既定の値は core の定数を参照して作り、「既定のコンフィグから組み立てたもの = `MachineConfig.DEFAULT`・`PartTier.defaultSpec`・`RebootBackoff.DEFAULT`・`SchedulerConfig` の既定」を単体テストで確かめる。
- 1.20.1 は spec の登録まで（ADR 0202。項目は `ENTRIES` の走査で自動で入る）。使う側の移植は #224。

### 再起動の待ち（ADR 0635）の前提を組み立てで守る

上限の見積もり（1台の再起動は長い目で見て `decayTicks` に1回以下）は、`maxDelayTicks >= decayTicks` で、`capLevels` 段の待ちが `maxDelayTicks` に届くときだけ成り立つ。組み立て（`KiBiConfig.rebootBackoff`）で `maxDelayTicks` を `decayTicks` に、`capLevels` を「`freeReboots + 1 + (baseDelayTicks から倍々で maxDelayTicks に届く回数)`」に引き上げる（ADR 0202 の自動保存の周期と同じ形）。`baseDelayTicks` の最小は 1（0 だと待ちが伸びない）。テストの差し替え（`SchedulerInternals.rebootBackoff`）は残し、`null` ならコンフィグの値を使う。

### 値を下げたときの既存の台・ディスク

上限は保存しない（ADR 0066）。戻すときは今の設定で写しを復号する。

| 下げたもの | 振る舞い |
| --- | --- |
| RAM（`memory`）・プロセスの数（`machine.maxProcesses`）で、写しが収まらない | 冷えた起動。理由は新しい `ColdBootCause.OVER_LIMIT`（壊れているのと分けるため、復号の上限の検査で `SnapshotLimitException` を投げる）。全台が一度に当たりうるので、`MOD_VERSION` と同じく、ログは理由ごとに起動ごと1回の要約にする |
| プロセスの数（表に収まる程度） | 戻る（復号の上限は `maxProcesses * 2 + 64`）。新しいプロセスだけを断る |
| イベントキューの数・タイマーの数 | 上限を超えたまま戻る。新しく作る・動かすものだけを `ResourceLimitException` で断る |
| 溜めるイベントの量 | 戻る。新しい post は溜めずに落とす（今までどおり） |
| ホストのファイルの数 | 戻した開いたファイルは開いたまま数えない（使うたびに開いて閉じる）ので、戻すのは壊れない。新しく開くものが `MFILE`・`NFILE` |
| コンソールのバッファ | 戻した TTY は保存したときの大きさのまま。冷えた起動で新しい値になる |
| 接続数（`cpu.*MaxDevices`） | つなげなかったデバイスは `detached`（ADR 0066。`DeviceLimitRestoreTest`） |
| ディスクの容量・項目の数（`storage`） | 挿したとき・電源を入れたときに決まる。中身は消えない。読む・消す・縮める・伸ばさない上書きはでき、増やす書き込み・作成・伸ばすのは `NOSPC`（ADR 0453。空きは 0 で止まる） |

上げる方向は、復号の上限をコンフィグの最大以上にそろえて、上げた設定で保存した台を同じ設定で戻せるようにした（イベントキューの数の索引は `EventLimits.MAX_QUEUES_CEILING` = 1,024、TTY は `Tty.MAX_SNAPSHOT_CAPACITY` = 1 MiB）。

`ColdBootCause` は実行時の分類（統計・ログ）で、保存しない。保存形式・公開 API（`core.api`）は変えていない。足した型（`PartSpec`・`MachineProfile`）は内部（`@ApiStatus.Internal`）。
