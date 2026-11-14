# 0073: 独自拡張の WIT（kibi:* パッケージ）と p1 向けの import の導き方

- 状態: 採用
- Issue: #73（task）。前提: ADR 0043（カーネルの構成）、0063（イベントキュー）、0072（p1 の `poll_oneoff`）、0261（p1 の層を直接実装する）、0329（p1 の待機点への変換）、#257（WASI の対応範囲と独自拡張の定義方法）
- 後続: #426（タイマーと時計）、#427（TTY）、#74（プロセス）、#75（パイプと fd の複製）、#76（ソケット）、#77（デバイス。ADR 0077）、#306（電源）、#326（キーの押下）、#264（Canonical ABI。ADR 0264）・#685・#266（p2・p3 の層）、#116（SDK の束縛）、#251（仮の値の確定）
- 番号は Issue 番号にそろえている
- ABI の選択（Q1〜Q6、タイマーと時計の置き場所）は、#73 のプランへのオーナーの回答で決めた（すべて推奨どおり）

## 決めたこと（要約）

- 独自拡張は WIT の `kibi:<領域>` パッケージに分ける（Q1-A）。#73 で置いたのは `kibi:kernel@0.1.0`（`types`・`events`）と `kibi:computer@0.1.0`（`memory`。#408 の `kibi:memory` を移した）
- p1 向けの import は、WIT から Canonical ABI の core の形そのままに導く（Q2-B）。手で別の形を決めない
- resource のハンドルは、p1 ではカーネルの fd 番号（Q3-A）
- エラーは共通の `kibi:kernel/types.error-code`。p1 の errno = 判別子 + 1（Q4-A）
- 全パッケージ `@0.1.0`。ホストは版の完全一致だけを受け付ける（Q5-A）
- WASIX には揃えない。`lua.wasm` はコマンド形式のまま。p1 の reactor の入口は定義しない（Q6-A）
- 公開 API（`pro.ikili.kibicomputers.api`）と保存のバイト形式は変えない。足した型はすべて Kotlin の `internal`

## パッケージと置き場所

WIT は `core/src/main/wit/<ns>-<pkg>/` に置き、パッケージごとに `world imports`（その interface をすべて import する）を置く。

| パッケージ | interface | 中身 | Issue |
| --- | --- | --- | --- |
| `kibi:kernel` | `types` | 共通の型（`error-code`） | #73 |
| `kibi:kernel` | `events` | イベントキュー（resource `event-queue`） | #73 |
| `kibi:kernel` | `timers` | タイマー（イベントのキューにイベントを届ける、カーネルのイベントのしくみの一部。ADR 0426） | #426 |
| `kibi:kernel` | `timers-poll`・`events-poll`・`timers-async`・`events-async` | p2・p3 でタイマー・イベントキューを待つ（仮置き。ADR 0426） | #426 |
| `kibi:process` | `process` | プロセスの生成・待機・強制終了、カーネルの cwd、プロセスの一覧（p1・p2・p3 で共通。ADR 0074・0332・0452） | #74・#452 |
| `kibi:process` | `process-poll`・`process-async` | p2・p3 で子を待つ・ハンドルを渡して子を作る（仮置き。ADR 0074） | #74 |
| `kibi:io` | `fd`・`pipe` | fd の複製とパイプ（p1 だけ。ADR 0075） | #75 |
| `kibi:io` | `pipe-streams`・`pipe-async` | p2・p3 のパイプ（仮置き。ADR 0075） | #75 |
| `kibi:io` | `tty` | TTY（raw モード・大きさ・前のプロセス。p1・p2・p3 で共通。ADR 0427） | #427 |
| `kibi:device` | `types`・`bus`・`bus-poll`（p2）・`bus-async`（p3） | デバイスの列挙と操作（#65 で WIT を置いた。ADR 0065。p1 に `bus` を出した。ADR 0077。p2・p3 は仮置き） | #65・#77 |
| `kibi:computer` | `memory` | 残りの RAM（#408） | #73 で移した |
| `kibi:computer` | `clock` | 起動からの経過（uptime）とゲーム内時間（ADR 0426） | #426 |
| `kibi:computer` | `power` | 電源（停止・再起動。ADR 0306） | #306 |
| `kibi:fs` | `mounts` | ディスクのマウント・アンマウント（p1・p2・p3 で共通。ADR 0056）、マウントとディスクの一覧 `list-mounts`・`list-disks`（`cabi_realloc` が要る。ADR 0453） | #56・#453 |
| `kibi:network` | — | ソケット（`wasi:sockets` で足りなければ） | #76 |

現在時刻と単調時計は `wasi:clocks`（p1 の `clock_time_get`）を使い、`kibi:*` には入れない。

### WIT の書き方（#75 で追記）

- 識別子（interface・関数・型の名前、引数名、フィールド名、case 名）が WIT のキーワード（`from`・`use`・`type`・`stream`・`s64` など。wit-parser の字句解析のもの）に当たらない名前を選ぶ（`kibi:*` の識別子すべて）。`%` で逃がすのは、改名すると意味が変わる・WASI の同じ概念と名前が食い違うときだけで、その理由を ADR に書く。variant の case 名は payload の型名ではなく「値の種類」の言葉（`integer`・`text` など）にする（#65 で強めた。`KibiWitTableTest` が `%` の識別子を落とす。許す一覧は空）
- 新しい WIT は `KibiWitParseTest` が wasm-tools（`wasm-tools component wit`。Endive の `run.endive:wasm-tools`。テストだけの依存）で解析・解決する。`src/main/wit/` のパッケージのディレクトリをすべて対象にするので、パッケージを足せば自動で対象になる。ほかの `kibi:*` のパッケージと、テストのリソースの `wit-stubs/`（`wasi:*` の最小のスタブ）を `deps/` に置いて解決する
- パッケージをまたぐ `use`（例: `kibi:process` から `kibi:kernel/types`）のために、`src/main/wit/` に `deps/` も symlink も置かない。参照先は `KibiWitTableTest` がテキストで確かめる（#74 で追記。ADR 0074）
- `KibiWitTableTest` は、予約語の識別子をテキストで拾う検査（速く分かりやすく失敗させるための補助）も持つ

## p1 向けの import の導き方（Q2-B・Q3-A）

WIT の関数を、Component Model の Canonical ABI で平らにした core の関数として、そのまま import にする。wit-bindgen がゲスト向けに出す import と同じ形なので、後で `wasm-tools component embed` で WIT を入れればそのままコンポーネントの import になる（ADR 0261 の測定 6）。

- モジュール名: `<ns>:<pkg>/<interface>@<版>`（例: `kibi:kernel/events@0.1.0`）
- 関数名: wit-bindgen と同じ。普通の関数はその名前、resource は `[constructor]<r>`・`[method]<r>.<f>`・`[static]<r>.<f>`・`[resource-drop]<r>`
- 引数と結果: Canonical ABI の平らにした形。結果が 1 つの値に収まらなければ、最後の引数に書き先（retptr）を渡す
- 可変長の結果（`list`・`string`）は、ゲストが export する `cabi_realloc(old_ptr, old_size, align, new_size) -> ptr` でホストが場所を取る。`cabi_realloc` が要る関数を import するのに export がなければ、読み込みで `NOEXEC`
- resource のハンドルはカーネルの fd 番号。`[resource-drop]` は `fd_close` と同じく閉じる（不正なハンドルならトラップ。Component Model と同じ）。`poll_oneoff` で待て、`SpawnOptions.fds` で子に渡せる
- モジュール `kibi` はカーネルの予約（燃料の `fuel`、待機点への変換の `async_*`）で、ゲストは import できない。#408 の旧名 `kibi.memory_remaining`・`kibi.memory_commit` も読み込みで `NOEXEC`
- `kibi:` で始まるモジュールは、出している関数を、型が一致するときだけ許す。版が違えば（`@0.2.0`、版なし）`NOEXEC`
- 燃料（仮の値。確定は #251）: 呼び出しごとに `WasiP1Fd.CALL_FUEL`。関数ごとに足す分は各 interface で決める（`take` は渡したイベントの数）

## エラー（Q4-A）

`kibi:kernel/types` の `enum error-code` は、p1 の errno から `success` を除いた 76 個を同じ順に並べた。名前は p1 の名前を読める形（kebab-case）にしたもの（`too-big`・`access`・…・`bad-descriptor`・…・`invalid`・…・`mfile`・…・`nfile`・…・`not-capable`）。

- p1 の errno = 判別子 + 1。カーネルの `Errno` がそのまま写り、C では errno に直しやすい（`KibiErrors`）
- マシン全体の上限（`ResourceLimitException`）は `nfile`、プロセスのハンドル表が満杯なら `mfile`
- ゲストの引数の誤り（上限・UTF-8）は `invalid`、範囲外のポインタは `fault` で返す（`result` を返す関数ではトラップにしない。ゲストが自分の誤りを受け取って続けられるように）。書き先（retptr）と `cabi_realloc` が返した場所の誤りは、書き先がないのでトラップ
- 引数の `list` の ptr が要素の align に揃っていないときもトラップ（#430）。Component Model の lift（`load_list_from_range`）でもトラップになるので、Component Model の層（#264）に替えたときに振る舞いを変えないため。長さ 0 でも、その list の長さ・範囲・中身の検査より先に確かめる。list を複数取る関数（`spawn`）は、引数の順（`path` → `args` → `env` → `cwd` → `fds`）に読むので、先に読む引数の error-code（`name-too-long`・`too-big`・`invalid`）は、あとの list の align のトラップより先に返る。待たない設定（`P1Wait.NONE`）の `again` は、引数を読む前に返る。Component Model の層は全引数を lift してからホストの検査に入るので、これらの組み合わせはトラップになる（今の p1 との既知の差。Component Model の層（#264）で、#616 で Component Model の順に揃える）。範囲外のほうは上のとおり `fault` のまま（Component Model ではトラップだが、Q4 で `fault` と決めた）。`string` の中身は align 1 なので確かめない

## 版（Q5-A）

全パッケージ `@0.1.0`。v0.1.0 のリリースまでは互換を壊しても版を変えない。ホストは版の完全一致だけを受け付ける（semver の互換の判定はしない）。

## WASIX と reactor（Q6-A）

WASIX の名前と形には揃えない（WASI p1・p2・p3 と `kibi:*` の WIT を正とする）。`lua.wasm` はコマンド形式（`_start`）のままで、p1 の reactor の入口（イベントを渡す export）は定義しない。p1 のプログラムは、キューの fd を `poll_oneoff` で待つ（ADR 0072・0329）。

## kibi:kernel/events

```wit
interface events {
    use types.{error-code};
    variant event-value { integer(s64), float(f64), boolean(bool), text(string), bytes(list<u8>) }
    record event { kind: string, source: string, values: list<event-value> }
    record event-batch { events: list<event>, dropped: u64 }
    resource event-queue {
        open: static func(kinds: list<string>, capacity: option<u32>) -> result<event-queue, error-code>;
        take: func(max: u32) -> result<event-batch, error-code>;
    }
}
```

- #65 で `event-value` の case 名を `%s64`・`%f64`・`%bool` から `integer`・`float`・`boolean` に改名した（case 名は Canonical ABI に出ないので、ABI・保存形式は変わらない）
- `kind` は `KernelEvent.type`、`source` はなければ ""、`dropped` は前回の取り出しからあふれて捨てた数（ADR 0063）
- `open`: `kinds` が空ならすべての種類。`capacity` を省くと既定（仮の値 64。確定は #251）
- `take`: 待たずに最大 `max` 個。`max` は符号なしで読み、0 だけが `invalid`。`Int.MAX_VALUE` で頭打ち（2^31 以上は「全部」）
- 待ち方: p1 はキューの fd を `poll_oneoff` の `FD_READ` で待つ。p2 の `subscribe() -> pollable`・p3 の async は `wasi:io` に依存するので、p2 は #685、p3 は #266 で別の interface として足す
- 燃料: 呼び出しごとに `CALL_FUEL`、`take` は加えて渡したイベントの数

### p1 向けの import

| モジュール / 名前 | core の型 |
| --- | --- |
| `kibi:kernel/events@0.1.0` / `[static]event-queue.open` | `(kinds_ptr, kinds_len, cap_is_some, cap, ret_ptr: i32) -> ()` |
| `kibi:kernel/events@0.1.0` / `[method]event-queue.take` | `(self, max, ret_ptr: i32) -> ()`（`cabi_realloc` が要る） |
| `kibi:kernel/events@0.1.0` / `[resource-drop]event-queue` | `(self: i32) -> ()` |
| `kibi:computer/memory@0.1.0` / `remaining` | `() -> i64` |
| `kibi:computer/memory@0.1.0` / `commit` | `(ptr, len: i32, keep: i64) -> i32` |

### メモリ上の配置（Canonical ABI どおり）

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `list<T>`・`string` | `{ ptr: i32 @0, len: i32 @4 }` | 8 / 4 |
| `result<event-queue, error-code>` | `{ tag: u8 @0, payload @4 }`。ok ならハンドル（i32）、err なら判別子（u8） | 8 / 4 |
| `result<event-batch, error-code>` | `{ tag: u8 @0, ok: { events.ptr @8, events.len @12, dropped: u64 @16 } \| err: u8 @8 }` | 24 / 8 |
| `event` | `{ kind @0, source @8, values @16 }` | 24 / 4 |
| `event-value` | `{ tag: u8 @0, payload @8 }`（s64・f64 は 8 バイト、bool は u8、text・bytes は (ptr, len)） | 16 / 8 |

`result<event-batch, …>` のペイロードは、場合の中で最大の align（`event-batch` の u64 で 8）に揃うので、@4 でなく @8 から始まる。C の手書きの宣言は `core/src/main/wasm/common/kibi_events.h`（`_Static_assert` で上のオフセットを確かめる）。

### 引数の検査

| 条件 | 返すもの |
| --- | --- |
| `kinds_ptr` が align 4 に揃っていない（長さ 0 でも。メモリは読まない。#430） | トラップ |
| `kinds_len > 16`（要素は読まない） | `invalid` |
| 要素の `len` が `1..64` の外（中身は読まない） | `invalid` |
| 要素の中身が正しい UTF-8 でない | `invalid` |
| `kinds` の配列・要素が線形メモリの外 | `fault` |
| `option` の判別子が 0・1 でない | `invalid` |
| `capacity` が `1..256` の外（符号なし） | `invalid` |
| `max == 0` | `invalid` |
| マシンのキューの数の上限 | `nfile` |
| プロセスのハンドル表が満杯 | `mfile` |
| `take` の `self` がキューでない fd | `bad-descriptor` |

- 確かめる順: `kinds_ptr` の align → `kinds_len` → 配列の範囲 → 各要素（`len` → 範囲 → UTF-8）→ `capacity`。最初に当たったものを返す
- 上限（16・64・256）は `EventLimits` の定数をそのまま使い、カーネルの `require` と同じ値を見る。ゲストの入力で `IllegalArgumentException` は出ない
- ホストが読むのは最大で 16 × 8 バイト（配列）+ 16 × 64 バイト（中身）。`kinds_len` を大きくして同じ領域を指させても、上限で先に止まる
- 返す文字列は UTF-8。`Text` の対になっていないサロゲートは U+FFFD にする

## cabi_realloc の呼び出し（ホスト → ゲスト）

- ホスト関数の中から `ctx.callGuest { instance.call("cabi_realloc", 0, 0, align, size) }` で呼ぶ。Canonical ABI どおり、大きさ 0 のリストでも呼ぶ。返したポインタが線形メモリの外、または align に揃っていなければトラップ
- 燃料はゲストの分として数える。`callGuest` の中（`ProcessRuntime.guestCallDepth > 0`。実行時だけの状態で保存しない）で燃料が切れたら、`WAIT_POINT` のプロセスでも巻き戻さず、スレッドを持ったまま燃料を待つ（ホスト関数の途中は巻き戻せないため）。普通の燃料切れでトラップにはしない
- `callGuest` の中で止まりうる p1 の import（`fd_read`・`fd_write`・`poll_oneoff`、`kibi:process` の `spawn`（ADR 0074））を呼んだら、待ち方（`BLOCK`・`UNWIND`・`NONE`）に依らずトラップにする。検査は `WasiP1` のホスト関数の入口（巻き直しの処理と燃料より前）に置く。ほかの import（`clock_time_get` など）は呼んでよい
- 止まりうるかの調べ（#430）: カーネルで止まって待つ口は `ProcessContext.block` と、`checkMayBlock` を通るもの（`exec`・`execWithHandle`・`awaitHost`・`callOnMainThread`、メインスレッドのメソッドの `invokeDevice`）、`block` を通って待つもの（`waitChild`・`awaitEvents`・`sleep`。待たずに返せるときは `block` に入らない）。今の p1 の関数でこれらに届くのは、上の4つ（`fd_read`・`fd_write` → `FdIo` → `block`、`poll_oneoff` → `block`、`spawn` → `execWithHandle`）だけで、どれも入口でトラップにしている。`fd_pread`・`fd_pwrite` は VFS を直接呼び、`sched_yield` はすぐに返り、ディスクの I/O はリースのロックの中で同期に行うので、届かない。`kibi:*` のほかの関数（events・timers・clock・memory・io・tty、`spawn` 以外の process）は待たない。燃料切れの待ちは `onFuelExhausted` を通り、これらの口を通らない
- 守り: `block` と `checkMayBlock` は、`callGuest` の中（`guestCallDepth > 0`）ならロックを取る前にトラップにする（`止まりうるホストの処理 … を cabi_realloc の中で呼んだ`）。ABI の層が入口で弾き忘れた import（`kibi:device` の `invoke` は入口で弾く。#77、ADR 0077）も、IllegalStateException（異常終了の理由が `internal error`）ではなくトラップで終わる。止まりうる import を足すときは、これまでどおり `WasiP1.WAIT_IMPORTS` と入口の検査にも入れる（巻き直しの処理と燃料より前に、待ち方に依らず弾くため）

## 実装

- WIT: `core/src/main/wit/kibi-kernel/{types,events,world}.wit`、`core/src/main/wit/kibi-computer/{memory,world}.wit`、`core/src/main/wit/kibi-io/`（#75。ADR 0075）、`kibi-kernel/timers*.wit`・`events-poll.wit`・`events-async.wit`・`kibi-computer/clock.wit`（#426。ADR 0426）
- `core.wasi.kibi.KibiErrors`（`error-code` の写し方）、`KibiEvents`（意味の部分。ABI の層に依らない）
- `core.wasi.p1.P1Canonical`（p1 に要る Canonical ABI の最小限の lift・lower。Component Model の層（#264）とは意図して違うので置き換えない。ADR 0264）、`WasiP1Kibi`（import の表）、`WasiP1.checkImports`（読み込みの検査）
- `ProcessContext.callGuest`・`inGuestCall`
- C: `core/src/main/wasm/common/kibi_memory.h`・`kibi_events.h`（手書き。wit-bindgen で作る束縛は SDK #116）

## テスト

- `KibiErrorsTest`: 判別子 + 1 = p1 の errno、`types.wit` の並びと `Errno` の並びが一致
- `KibiWitTableTest`: p1 向けの表のモジュール名・関数名が WIT から導いたものと一致。予約語の識別子がない（#75）
- `KibiWitParseTest`（#75）: `src/main/wit/` の全パッケージを wasm-tools で解析・解決できる。予約語の引数名・存在しない型の参照が失敗すること
- `KibiEventsTest`: 開く・取り出す・`dropped`・フィルター、`max`・`capacity`・`kinds` の境界、`nfile`・`bad-descriptor`
- `P1CanonicalTest`: 配置、`cabi_realloc` の呼び出しの順と align・size、上限を超えたときに中身を読まないこと、UTF-8・範囲外、書き先と `cabi_realloc` の誤りのトラップ
- `WasiP1KibiTest`（`kibi_events.c` を Endive とカーネルで）: `poll_oneoff` で待って取り出す（待機点型・ブロッキング型、`[resource-drop]` と `fd_close`）、不正な入力、`max` の境界、`cabi_realloc` の中の燃料切れ、`cabi_realloc` の中の止まりうる import が3つの待ち方のどれでもトラップ
- `GuestCallGuardTest`（#430・#617）: `callGuest` の中で止まって待つ口（`block`・`callOnMainThread`・`exec`・`execWithHandle`・`awaitHost`、メインスレッドのメソッドの `invokeDevice`、まだ待つ必要のある `waitChild`・`awaitEvents`・`sleep`）がトラップで CRASHED になる（ブロッキング型で確かめる。待機点型で確かめるのは `block`・`callOnMainThread`・メインスレッドのメソッドの `invokeDevice` の3つだけで、待機点型でも IllegalStateException にならない）。直接のメソッドの `invokeDevice`、0 以下の `sleep`、子のない `waitChild`（`NoChild`）、もう終わった子の `waitChild`（`callGuest` の外で `childExitKeys` を `block` で待ってから。刈り取って `Reaped`）、届いたイベントのある `awaitEvents` は、待たずに返る
- `WasiP1ImportCheckTest`: 型違い・表にない関数・版違い・`cabi_realloc` なし・旧名が `NOEXEC`

## 決めないこと

- タイマー・sleep・uptime・ゲーム内時間の関数の形と、`timer` のイベントの値の写し方: #426（この ADR の規則で WIT から導く）
  - 追記（#426）: ADR 0426 で決めた。`events` の p2・p3 での待ち方（`events-poll`・`events-async`。仮置き）も ADR 0426
- TTY のモード・大きさ・前のプロセスの見せ方、画面の大きさの変更のイベント: #427
  - 追記（#427）: ADR 0427 で決めた（`kibi:io/tty` と `tty-resize` のイベント）
- プロセス・ソケット・デバイス・電源・キーの押下: #74・#76・#77（ADR 0077）・#306・#326（パイプと fd の複製は #75 で決めた。ADR 0075）
  - 追記（#306）: 電源は ADR 0306 で決めた（`kibi:computer/power`）
- p2・p3 の層: Canonical ABI の一般の実装は #264（ADR 0264）、`kibi:*` の p2 の `pollable` は #685、async は #266
- 生成した束縛（wit-bindgen）: SDK #116
- 燃料とキューの既定の容量の確定: #251
- 異常終了の理由の文（`kibi:memory commit was refused …`）は読む人向けの名前なので、今回は変えない
