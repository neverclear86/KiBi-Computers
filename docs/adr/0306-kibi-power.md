# 0306: 独自拡張 kibi:computer/power（停止・再起動）

- 状態: 採用
- Issue: #306（task）。前提: ADR 0046（プロセスの扱い。マシンの中では権限を分けない）、0049（起動シーケンスと停止・再起動）、0073（`kibi:*` の WIT と p1 の import の導き方）
- 後続: #454（OS の `shutdown`・`reboot` コマンド）、#264（Canonical ABI。ADR 0264）・#685・#266（p2・p3 の層）、#116（SDK の束縛）、#251（仮の値の確定）
- ABI の選択（A: `shutdown: func()` と `reboot: func() -> result<_, error-code>`、B: 受け付けたら戻らない、C: どのプロセスからでも呼べる、D: 断るときは `not-supported`）は、#306 のプランへのオーナーの回答で決めた。p2・p3 の形はエージェントに任された

## 決めたこと（要約）

- `kibi:computer@0.1.0` に `interface power` を置き、p1 向けの import を ADR 0073 の規則で導いて実装した
- p2・p3 も同じ interface を使う。待たない関数だけなので、`-poll`・`-async` の変種は作らない
- 止め方・再起動の順番・取り消しは ADR 0049 のまま（`Machine.stop()`・`Machine.reboot()` をそのまま使う）
- 公開 API（`pro.ikili.kibicomputers.api`）と保存のバイト形式は変えない。足した型（`KibiPower`）は Kotlin の `internal object`。`rebootRequested`・`rebootNotified` は実行時だけの値のまま

## WIT

`core/src/main/wit/kibi-computer/power.wit`:

```wit
interface power {
    use kibi:kernel/types@0.1.0.{error-code};
    shutdown: func();
    reboot: func() -> result<_, error-code>;
}
```

`kibi-computer/world.wit` の `world imports` に `import power;` を足した。

- `shutdown`: マシンを止める。必ず受け付け、呼んだプロセスもその場で止まるので戻らない
- `reboot`: マシンを再起動する。受け付けたら戻らない。戻るのは断ったときだけで、`not-supported`
- どちらも、どのプロセスからでも呼べる（init だけに絞らない。ADR 0046）

### 再起動を断る条件

`Machine.reboot()` が `false` を返すとき。

- 起動も復元も通らずに作ったマシン（`lastBootConfig` がない）
- スケジューラを閉じる途中（サーバーを閉じる途中）
- `CRASHED`。このときは呼んだプロセスも止まっているので、ゲストには返らない（戻らずに止まる）

## p1 の import

| モジュール | 名前 | core の型 |
| --- | --- | --- |
| `kibi:computer/power@0.1.0` | `shutdown` | `() -> ()` |
| `kibi:computer/power@0.1.0` | `reboot` | `(ret_ptr: i32) -> ()` |

- 書き先は `result<_, error-code>`（`{tag: u8 @0, err: u8 @1}`。2 バイト、align 1）。errno = 判別子 + 1
- 版は完全一致だけを受け付ける（ADR 0073 の Q5-A）。型違い・版違い・版なし・表にない名前は、読み込みで `NOEXEC`
- 待たない import にする（`WasiP1.WAIT_IMPORTS` に入れない。待機点への変換の対象でない。スナップショットの `waitImports` も変わらない）。`callGuest`（`cabi_realloc`）の中で呼んでもトラップにしない
- 燃料は、ほかの独自拡張と同じく呼び出しごとに `CALL_FUEL`（仮の値。確定は #251）
- C の宣言は `core/src/main/wasm/common/kibi_power.h`（`shutdown` には `noreturn` を付けた）

### 書き先を確かめる順番

書き先は、断ったときだけ書く。そのため:

- 範囲外の `ret_ptr` でも、受け付ければ再起動する（トラップにならない）
- 断ったときに書き先が範囲外なら、トラップになる

Canonical ABI も結果を返すときに書き先を確かめるので、この振る舞いはそれと同じ。p2・p3 に替えても変わらない（先に確かめる形は、Component Model の層 #264 とずれるので採らない）。`WasiP1PowerTest` で固定した。

## カーネル側

`ProcessContext` の形を変えた（内部）。

- `requestShutdown(): Nothing`: 必ず受け付け、`ProcessKilledException` を投げる
- `requestReboot(): Boolean`: 受け付けたら `ProcessKilledException` を投げる。断ったら `false`。断っても、そのプロセスが止められていれば（`CRASHED` など）例外を投げる

`ProcessRunner` の実装は、止められていないことを確かめてから `Machine.stop()`・`reboot()` を呼ぶまでを、1つのカーネルロックの区間にする。

- ホスト関数の包み（`hostFunction`）は、ロックの中で `rt.killed` を確かめた後、ロックを外して本体を呼ぶ。この隙間にプレイヤーの `stop()` が入ると、その後の `reboot()` が `rebootRequested` を立て直し、止めたはずのマシンが再起動してしまう。区間を1つにして、この競合をなくした
- ロックは再入できる（`ProcessRunner.lock` は `machine.lock` と同じ）。`Machine.stop()`・`reboot()` はロックの外で後処理をしないので、入れ子にしても意味は変わらない（KDoc に前提として書いた）
- 受け付けた後は、割り込みで止まる処理（待ち・`flushConsole` など）を挟まず、すぐ投げる（`killRuntimeLocked` は呼んだ自分のスレッドにも割り込みをかけるため）
- テスト用に `KernelTestHooks.beforePowerRequest`（実行時だけ、`internal`）を足した。確かめた後・求める前に `stop()` を挟む競合のテストに使う

init 自身が呼んでも、マシンは `STOPPING` なので「init exited」は出ない（ADR 0049）。

## データ形式

- 保存するものは足さない。スナップショットの `waitImports` も変えない
- p1 の書き先の配置は上のとおり

## 今の制限

- 再起動は `lastBootConfig` で行う。接続層は `onRebootRequested` を上書きしていない（その時点の部品で設定を作り直すのは接続層の仕事で、今は既定の実装のまま）
- 起動のたびに再起動を呼ぶ init は、プレイヤーの操作なしに起動ループが続く（これまでは init が終わると電源が切れた）。起動の処理（起動用のスレッド、マウント、init の読み込みとインスタンス化）はマシンの燃料で払われないので、燃料の上限の外でサーバーの CPU を使い続けられる。この Issue では抑止、tick ごとの処理、常駐スレッドを足さない。ADR 0049 に自動の抑止の取り決めはなく、ここで新しく決めたものでもない。抑止は #635 で扱う（最小の間隔の仮の値は #251）
  - 追記（#635）: 抑止を足した（ADR 0635）。短い間に繰り返すマシンだけ、止まった後、起動し直すまでを遅らせる（借り付きの指数の待ち）。`reboot` の戻り値・エラー・断る条件は変えていない（B・D のまま）
- コンソールに出す固定のメッセージは足さない。出すなら #454 のコマンドが出す（#454 の `shutdown`・`reboot` も出さない）
- OS のコマンドと Zig の包み（`os/lib/kibi/power.zig`）は #454 で足した（ADR 0103）
- ゲストが `shutdown` で止めると、ケースは OFF になり、マシンを外す（#640。ADR 0085 の追記）。止める直前にコンソールの出力を止めていた（#381）分や、画面に書ききれずに取っておいた分は、電源ボタンで切ったときと同じく捨てることがある
