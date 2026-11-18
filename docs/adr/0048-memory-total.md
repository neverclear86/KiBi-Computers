# 0048: 全プロセスのメモリ合計の管理

- 状態: 採用
- Issue: #48（task）。前提: #39（RAM の上限）、#45（プロセスの生成。ADR 0045）、#362（サーバー全体の勘定）、#370・#407（RAM の数え方）、#408（`kibi:memory`）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- RAM の勘定は 3 段（プロセス → マシン → サーバー全体）。どれも実行時だけのもので、保存しない（復元のときは数え直す。ADR 0082・0085）
- 上限を超えたときの振る舞いは、確保の種類ごとに下の表のとおり。**プロセスの生成が RAM で失敗したときは、子を `CRASHED` にし、コンソールにメモリ不足と出す**（spawn の戻り値は変えない）
- どの段で断ったかを記録し、サーバー全体で断ったときは、マシンの RAM の上限ではなく `server memory limit reached` と書く
- ABI（`kibi:memory` の `remaining`・`commit`、spawn の戻り値）、保存形式、公開 API は変えない

## 3 段の勘定

| 段 | 持ち主 | 上限 | 数えるもの |
| --- | --- | --- | --- |
| プロセス | `ProcessMemory`（プロセスのすべてのインスタンスで共有） | `MachineConfig.maxProcessMemoryBytes`（既定は RAM と同じ） | 線形メモリ（初めて書き込んだ 4 KiB の区画。#407）、テーブルの要素（#361） |
| マシン（RAM） | `Machine.memoryAccount`（`MemoryAccount`） | `MachineConfig.memoryBytes`（RAM のパーツの合計。仮の値。#251） | マシンのすべてのプロセスの分と、`/tmp` の中身（ADR 0053） |
| サーバー全体 | `MachineScheduler.memoryAccount`（`MemoryAccount`） | `SchedulerConfig.totalMemoryBytes`（既定はヒープの最大値の半分。仮。#251） | すべてのマシンの分 |

- 確保は「プロセス → マシン → サーバー」の順に、それぞれ 1 回の CAS で数える。上の段で断られたら、下の段の分を戻す。返すときは全段から引く。カーネルロックは使わない（`memory.grow` と初めての書き込みは、プロセスのスレッドからロックを持たずに来る）
- 合計はプロセスを走査して求めない（カウンターを持つだけ）。tick ごとの集計もしない。1,000 台規模でも、待っているだけの台には負荷を足さない
- 解放しても減らさない（最高値）。プロセスが終わったときにまとめて返す（#370）。OS や init も同じ枠で数える

## 上限を超えたとき

| 確保 | 断ったときの振る舞い | コンソール |
| --- | --- | --- |
| 線形メモリへの初めての書き込み | トラップ（`OUT_OF_MEMORY`） | `crashed: out of memory (RAM limit 128 KiB)` |
| `memory.grow` | -1（増やした範囲の端の区画を数えられないとき） | その後に異常終了したら `(memory.grow was refused: out of memory, RAM limit 128 KiB)` を添える |
| `kibi:memory` の `commit` | false | 同上（`kibi:memory commit was refused`） |
| `table.grow` | -1 | 添えない（下の「決めないこと」） |
| `/tmp` への書き込み | `NOSPC`（書けた分があれば短い書き込み） | なし |
| プロセスの生成（最初のインスタンス。テーブルの最初の要素など） | 子が `CRASHED` | `crashed: cannot instantiate module (out of memory, RAM limit 128 KiB)` |
| 実行中に作る 2 つ目以降のインスタンス（`ProcessContext.instantiate`） | `WasmLinkException`（ABI 層が扱える） | そのまま異常終了したら、生成と同じ文 |

- `RAM limit` に書くのは、プロセスが数えられる上限（プロセスの上限とマシンの RAM のうち小さい方）
- **サーバー全体の勘定で断ったとき**は、`RAM limit ...` の代わりに `server memory limit reached` と書く（例: `out of memory (server memory limit reached)`、`cannot instantiate module (out of memory, server memory limit reached)`）。マシンの RAM には余裕があるので、RAM の上限を書くと誤解を招く
- どの段で断ったかは、`ProcessMemory.lastRefusal`（最後に断った段）に記録する。マシンの勘定はプロセスで共有するので、記録はプロセスの側に置く。断ったときだけ書くので、確保の速い経路には何も足さない
  - トラップとインスタンスの生成の失敗は、断った直後に同じスレッドで読むので、`lastRefusal` で決める
  - `memory.grow`・`commit` の注記は、異常終了したときに後から書くので、`lastRefusal` を読むと後の別の確保（注記を付けない `table.grow` など）で上書きされた段になる。そこで、grow・commit を断った直後に、そのときの `lastRefusal` を `ProcessRuntime.memoryGrowRefusal`・`memoryCommitRefusal` に写し、注記はそれぞれの段で書く
  - grow と commit を違う段で断ったときは、別々に書く（例: `memory.grow was refused: out of memory, server memory limit reached; kibi:memory commit was refused: out of memory, RAM limit 128 KiB`）。同じ段なら、これまでどおり 1 つにまとめる
- `kibi:memory` の `remaining` は、プロセスの上限とマシンの RAM のうち小さい方の残りで、サーバー全体は入れない（#408 のまま）。`commit` の `keep` もサーバー全体にはかけない

## プロセスの生成が RAM で失敗したとき

spawn（`kibi:process spawn`・`Machine.exec`）は、読み込み（コンパイル）が終わるまでしか待たない（ADR 0074）。インスタンスは子のスレッドが作るので、RAM の不足はその後で分かる。

- 子は `CRASHED` で終わり、親は終了待ち（`status`）で `crashed` を見る。spawn の戻り値（ok と pid）は変えない
- 判定: `WasmLinkException` で終わり、かつ、失敗した core インスタンスを作る前後で断った回数（`ProcessMemory.refusalCount`）が増えたこと。
  最初のインスタンス（`load`）、コンポーネントの各 core インスタンス（`loadComponent`。作り始めるたびに数え直す）、実行中に作る 2 つ目以降（`ProcessContext.instantiate`）のどれも同じ
  - 「それまでに一度でも断ったか」では見ない。コンポーネントは core インスタンスを順に作り、先に作ったものの start 関数が `memory.grow` などを断って続行した後に、別の理由で後のインスタンスが作れないことがあるため
- RAM と関係のない失敗（import がない、型が違う、start 関数のトラップ）には、メモリ不足と書かない。前に別の確保を断っていても、判定は作る前後の回数で見るので混ざらない
- 作りかけのインスタンスのメモリ・テーブルの分は、全段に返る（ランタイムが失敗のときもまとめて返す。#318）
- init（pid 1）でも同じ文が `kibi: init crashed: ...` に出る

### 採らなかったもの

- spawn を、子のインスタンスができるまで待たせる / 生成の前に残りを比べて spawn で `no-memory` を返す: ABI（ADR 0074 の spawn の返すエラー）と待機点の保存に関わる。事前の確認は競合で必ずすり抜けるので、子の `CRASHED` の経路は残る
- 宣言したページ数で事前に予約する: #370 の数え方（書いた区画だけ）に反し、小さい RAM で Lua が起動しなくなる
  - これは区画（4 KiB）を予約しないということで、線形メモリの区画の表（1 ページ 128 バイト。#753 の追記）を数えることとは別。表は区画の 1/512 なので、小さい RAM でも起動を妨げない

## 決めないこと

- `table.grow` を断った後の異常終了に、メモリ不足の注記を添えること（今は `memory.grow`・`commit` だけ）
- RAM・サーバー全体の上限の最終値: #251・#20・#123
- インスタンスをまたぐ燃料と上限の残り: #270
  - 追記（#270）: 線形メモリ・テーブルは、core インスタンスごとに同じ `ProcessMemory` に数える（#263 から）。#270 で、コンポーネントの core インスタンス 1 つあたりの固定費（`CORE_INSTANCE_OVERHEAD_BYTES`。仮に 4 KiB、確定は #251）を、作る前に 3 段に数えるようにした（断られたら作れず、メモリ不足と出る）。返すのはプロセスの終わりにまとめて。p1 の単体のモジュールには数えない。ホスト側の一時的な確保（lift した値）と、線形メモリのページの表は #753 に残す
  - 追記（#753）: 線形メモリの区画の表（`WrittenPagesMemory` が 4 KiB の区画ごとに持つ参照）を、1 ページあたり `TABLE_BYTES_PER_PAGE`（区画 16 個 × 参照 8 バイト = 128 バイト。テーブルの要素の見積もり #361 と同じく圧縮しない参照の前提。仮の値で、確定は #251）で 3 段に数える。p1 とコンポーネントで同じ規則。
  - 追記（#777）: p2 のコンポーネントの `kibi:computer/memory` の `commit` も、p1 と同じ `ProcessContext.commitMemory` を通り、同じ `ProcessMemory` に数える。区画を数えるメモリは `commit` を呼んだ core インスタンスの線形メモリ。RAM の上限で断ったら `memoryCommitRefusal` に残し、異常終了の理由に添える（p1 と同じ文。ADR 0777）
    - 作るとき: 最初のページ数の分を、作る前に数える。断られたら作れず（`WasmLinkException`）、メモリ不足（`cannot instantiate module (out of memory, …)`）と出る。
    - `memory.grow`: 増やしたページ数の分を、増やした所の端の区画と 1 回の CAS で一緒に数える（片方だけ数えた状態を作らない）。断られたら今までどおり -1 と `onGrowRefused`。カーネルロックは使わない（メモリのモニターの中で数え、閉じた後は数えない）。
    - 戻すとき（`restorePages`）: 増やしたページ数の分だけ数える（端の区画は写しから `restoreChunk` で数える）。生きている間の勘定と、戻した後の勘定は同じになる。断られたら戻せず `NO_MEMORY`。
    - 返すのは、区画の分と一緒にインスタンスを閉じるとき。区画の数（`writtenChunks`・`countedBytes`。保存・計測で使う）には混ぜない。
    - 数えないもの: 保存のために作る `parked` の表（保存の待機点で作り、そこでは断れない。保存できるのは p1 だけ）と、grow のときに古い表と新しい表が一瞬並ぶ分（一時的なもの）。テスト用のダミー（`FakeMemory`）は表を数えない。
  - 追記（#753）: lift・lower の一時的な値（ホストの JVM ヒープ）は、RAM の 3 段には数えない。この勘定は解放しても減らない最高値なので、大きな呼び出し 1 回（最大 16 MiB）がプロセスの終わりまで RAM を引き、今は通る小さい RAM の呼び出しがメモリ不足になるため。代わりに、ホストが同時に持つ量を 1 段分に抑える（ADR 0264「予算」の追記）
