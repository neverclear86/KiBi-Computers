# 0262: コンポーネントのバイナリの読み込みと検証

- 状態: 採用
- Issue: #262（Epic #259）。前提は ADR 0260（Component Model は `core` に自前で実装）、ADR 0261（layer で p1 とコンポーネントを見分ける）、#40（読み込むモジュールの検証）
- 番号は Issue 番号にそろえている

## 背景

#260 で、コンポーネントは `core` の自前のパーサーで読み、検証は Endive の `run.endive:wasm-tools`（wasm-tools を WASM にしたもの）の `validate` を使うと決めた。jar が約 5.4 MiB 増えるので、自前の検証に置き換えるかは #262 で大きさを見て決めることになっていた。あわせて、#40 の検証（大きさ・構造の上限、時間の上限、許可しない import の拒否）をコンポーネントにも広げる。

## 決定

### 置き場所と流れ

`core` の内部（`pro.ikili.kibicomputers.core.runtime.component`。すべて `internal`。公開 API には出さない）に置く。`ComponentLoader.load` が次の順で確かめ、失敗はすべて `WasmLoadException` にする。各段の後で時間の上限（`LoadDeadline`。読み込むスレッドの CPU 時間で 8 秒。`ModuleLimits.timeLimitNanos`）を確かめる。

1. 大きさの上限（16 MiB。`ModuleLimits.maxBytes`、実行ファイルの上限と同じ）
2. ヘッダー: layer を先に見て、layer 1 でなければ断る（layer 0 は core モジュール。ADR 0261）。そのあと version が `0x0d` と完全に一致するかを見て、違えば「対応していないコンポーネントの版」で断る
3. 自前のパーサー（`ComponentParser`）で、構造の上限（`ComponentLimits`）を確かめながら読む
4. 中の各 core モジュールに、p1 と同じ構造の確認（`ModulePrescan`、`ModuleLimits`）を当てる
5. wasm-tools で検証する（`ComponentValidator`）。core モジュールの関数の本体は除いた写し（`ValidationImage`）を渡す
6. トップレベルの import の形（`ComponentImportPolicy`）と、core モジュールが予約したモジュール名 `kibi` を import していないこと
7. 入れ子を展開した後の、core モジュールのインスタンスの数（`ComponentExpansion`）

結果（`LoadedComponent`）は、元のバイト列、その SHA-256、宣言の順の木（`ParsedComponent`）、すべての core モジュールの範囲、展開後のインスタンスの数を持つ。

### パーサー

- Component Model のバイナリ形式（version `0x0d`）を、wasm-tools 1.256.0 の wasmparser と同じ読み方で読む。対象は core module・core instance・core type・component・instance・alias・type・canon・import・export・custom
- セクションは宣言の順のまま持ち、並べ替えたりまとめたりしない。添字空間（core func〜instance の 13 種類）の番号は出てくる順に振られ、export も新しい番号を足す（#263 のリンクと #269 の保存データが番号で指すため、決定的にする）。各コンポーネントの添字空間の大きさも数えて持つ
- 型・import・export・alias・canon・インスタンスはデコードする。core モジュールは写さず、元のバイト列の中の範囲（オフセットと長さ）で持つ（キャッシュで元のバイト列と二重に持たないため。#263 で Endive に渡すときに写す）
- 型の正しさ（添字の範囲、型の一致、部分型）は確かめない（wasm-tools の検証に任せる）
- 値（`cm-values`）・start・オプション付きの名前（`cm-implements`）・スレッドの canon（`cm-threading`）・GC の core の型は、符号の時点で断る

### 構造の上限（仮の値。確定は #251）

数はバイナリ全体（入れ子を含む）の合計。どれも「上限以下」と「残りのバイト数以下」を確かめてから確保する。ただし、種類ごとの上限を掛け合わせると、1 バイトの要素ごとにオブジェクトができるので、16 MiB の入力でも木が数百 MiB になる（実測: bool 10 万個の tuple を 160 個並べた 16 MB で約 305 MiB。名前が空の引数 5 万個の関数型を 160 個で約 519 MiB）。そこで、ベクターを読む前に、その要素の数を足して総数の上限（500,000）を確かめる。木のメモリは 1 要素あたり 20〜100 バイトほどなので、最悪でも数十 MiB に収まる。名前は UTF-8 として正しいかも確かめる。

| 上限 | 値 |
| --- | --- |
| core モジュールの数 | 1,000 |
| 入れ子のコンポーネントの数 | 1,000 |
| 入れ子の深さ | 16 |
| 型（core の型を含む）の数 | 100,000 |
| import の数 / export の数 | 10,000 / 10,000 |
| alias の数 / canon の数 | 100,000 / 100,000 |
| インスタンス（コンポーネントと core）の宣言の数 | 10,000 |
| 1 つのベクターの要素の数 | 100,000 |
| デコードする要素の総数（ベクターの要素の合計。バイナリ全体） | 500,000 |
| 名前のバイト数 | 4,096 |
| 型の入れ子の深さ | 32 |
| 展開した後の core モジュールのインスタンスの数 | 1,000 |
| 展開を数える手間（見る宣言の数） | 1,000,000 |

中の core モジュールには、それぞれ `ModuleLimits`（関数の数、本体の大きさ、ローカル変数など）を当てる。

### 展開後のインスタンスの数

A が B を2回、B が C を2回…とインスタンス化する作りを重ねると、宣言は少ないまま実際のインスタンスが 2^深さ に増える。`ComponentExpansion` はインスタンス化をなぞり（どのコンポーネント・インスタンスがどこに渡るかだけを追い、関数や値は追わない）、`core instance (instantiate ...)` の数を数える。コンポーネントは、インスタンス化の引数・export・alias（outer を含む）で渡されるので、それも追う。ホストから来るもの（トップレベルの import）は中身のないものとして扱う。検証に通った後で行う（型があるので、自分自身をインスタンス化し続ける作りはない）が、深さ（64）と手間にも上限を置く。

### 検証（wasm-tools）

- Endive の `Validate` は使わず、`WasmToolsModule` と Endive の `WasiPreview1` で `wasm-tools validate --features=<明示> -` を動かす薄い包みを書いた。`Validate` は呼ぶたびにログを出し、ゲストのメモリの上限と機能を外から決められないため
- 入力は標準入力だけで渡し、ディレクトリは渡さない（ゲストからホストのファイルは見えない）。ログは出さない。乱数は固定する（結果を決定的にする）。標準出力・標準エラー出力は先頭 4 KiB だけ取っておく
- 有効にする機能（`ComponentValidator.FEATURES`）: `-all,wasm2,tail-call,exceptions,extended-const,call-indirect-overlong,bulk-memory-opt,component-model,cm-async`。`all` や wasm-tools の既定は使わない（GC・スレッド・memory64 や、値の import・start、Component Model のスレッドまで通ってしまうため）。stackful の非同期（`cm-async-stackful`）は #266 で決めるまで断る。`cm-error-context` も今は入れない
- ゲストの線形メモリの上限は 2,048 ページ（128 MiB。仮の値）
- 失敗は `WasmLoadException` にし、メッセージに標準エラー出力の先頭（500 文字まで）を入れる。wasm-tools は検証に失敗すると、DWARF の読み直しに失敗したログ（`[<時刻> WARN ] ...`）を先に出すことがあるので、環境変数 `RUST_LOG=off` でログを止め、念のためログの行（`ComponentValidator.reason`）も除く。時刻が入ると同じバイナリでもメッセージが変わり、先頭の 500 文字をログが使ってしまう
- **検証器そのものの失敗（`LinkageError`、wasm-tools のモジュールの読み込みの失敗など）も `WasmLoadException` に包むが、メッセージに「内部の失敗」と書き、ログ（ERROR）にも出す。** 包まないと、読み込みの呼び出し元（`Machine.loadedProgram`）が想定していない例外が上がるため。`OutOfMemoryError` などの `VirtualMachineError` は包まない
- ゲストのトラップ（メモリの上限で確保に失敗して abort したときなど）は、内容の失敗として扱う

### 関数の本体を除いた写しで検証する

wasm-tools の検証の時間は、ほとんどが core モジュールの関数の本体の検証で、大きさにほぼ比例した（下の測定）。関数の本体は、p1 と同じく Endive が読み込むとき（#263 の「パース → コンパイル」。#29 の一本道）に検証するので、wasm-tools には、関数の本体を `unreachable` だけにし、core モジュールのカスタム セクションを除いた写しを渡す。関数の数と型、import・export、テーブル・メモリ・グローバル・要素・データは元のままなので、コンポーネントとしての検証の結果は変わらない。関数の本体の誤りは、#262 の時点では見つからない（#263 で Endive が断る）。

見逃すのは関数の本体だけではない。写しが落とす・読まない、core モジュールの次の部分の誤りも、wasm-tools には届かない（変異ファジングで、`load` が通るのに元のバイナリは wasm-tools が断るものを調べた。19,500 個のうち `load` が通った 5,445 個で出たもの）:

- core モジュールのカスタム セクションの壊れ（「malformed UTF-8 encoding」「unexpected end-of-file」。写しが落とす）
- コードセクションの本体の後ろの余り（「trailing bytes at end of section」。`ModulePrescan` が見ず、写しが捨てる）

どれも実行されるバイトではないので、#262 の時点での害はない。ただし `LoadedComponent` を「wasm-tools の検証に通ったもの」として使う側（#263）が当てにできるのは、コンポーネントの層（型・import・export・canon・alias・インスタンス化）までで、core モジュールの中身は、元のバイト列を Endive が読んで初めて確かめられる。コンポーネントの層での食い違いと、`WasmLoadException` 以外の例外は、ファジングで 1 件も出なかった。

### 初期化とスレッド

- wasm-tools のモジュールは初めて使うときに1回だけ読む。初回の重い初期化（クラスの読み込み・初期化）は、時間の上限を測り始める前に `ComponentValidator.warmUp`（空のコンポーネントを1回検証する）で済ませる。初回の遅さで時間切れになり、失敗として覚えられないようにするため
- サーバーの起動時（`RuntimeSelfCheck`）には暖めない。コンポーネントを読まないサーバーの起動を遅くしないため
- 検証もパースも状態を持たず、どのスレッドから同時に呼んでもよい（呼び出しごとに WASI とインスタンスを作って閉じる）。重いので、コンパイル用のスレッド（#29・#37）から呼ぶ。メインスレッドと tick からは呼ばない

### import の確認（`ComponentImportPolicy`）

形だけを見る。インスタンスの型がホストの型に合うか（部分型か）の照合は、リンク（#263）の仕事。

- トップレベルの import の種類はインスタンスだけ（core モジュール・関数・値・型・コンポーネントは断る）
- 名前はインターフェースの名前（`<名前空間>:<パッケージ>/<インターフェース>[@<版>]`）。名前空間は `wasi` と `kibi` だけ
- `kibi:*` は版 `@0.1.0` の完全一致だけ（ADR 0073 の Q5-A）
- `wasi:*` の版の受け付け方（`@0.2.x` の minor の違いを semver の互換として受けるか）は、システムコールの ABI に関わるので、ここでは決めない。判定は差し替えられる形（`fun interface`）にし、ホストのインターフェースの一覧とあわせて #267・#268 で決める
- core モジュールが、カーネルが予約したモジュール名 `kibi`（燃料 `kibi.fuel` と待機点 `kibi.async_*`。#270 でリンカーが足す）を自分で import していたら、ここで断る（燃料の計測をすり抜けたり、カーネルの状態を壊したりできるため。早く断るほうが理由が分かりやすい）

### wasm-tools を使い続ける（自前の検証にしない）

jar は約 5.5 MiB 増える（`wasm-tools-1.2.0.jar` 5,635,996 バイト、`wasi-1.2.0.jar` 59,028 バイト）。自前の検証（部分型・resource・Canonical ABI の型の検査。wasmparser の component の検証は数万行）を書く手間と、それを Component Model の変化に合わせて保つ手間に比べて小さいので、wasm-tools を使い続ける。Endive の `wasi` は、wasm-tools を動かすためだけに使う（プレイヤーのプログラムは今どおり自前の p1 で動かす。ADR 0261）。wasm-tools の推移的な依存の `zerofs`（仮想 FS）は validate で使わないので、同梱しない。

### 実行（exec）にはまだつながない

`Machine.beginExec` の読み込み（`Program.load*`・`ProgramCache`）は変えない。コンポーネントを exec したときは、`ModulePrescan` がヘッダーで見分けて、重い検証をせずにすぐ「Component Model のコンポーネントは、まだ実行できない（#263）」で断る（`NOEXEC`）。`ProgramCache`・`Program`・保存データ（`ModuleSource`・スナップショットの節）は、#263・#269 で扱う。

## 測定

Windows・Java 17（Gradle のテスト。温まっていない JVM）。

| 対象 | 時間 |
| --- | --- |
| 初回の暖め（`warmUp`。新しい JVM の最初の呼び出し。2回目は 0 ms） | 約 1.06 秒 |
| 空のコンポーネントの `load`（暖めた後の1回目 / 2回目以降） | 約 37 ms / 9 ms |
| lua.wasm（316 KiB）を core モジュールとして持つもの（本体ごと検証 / 本体を除く） | 約 120 ms / 約 20 ms |
| 15.4 MiB（関数 10 万個、データ 5 MiB）（本体ごと検証 / 本体を除く） | CPU 約 4.8〜5.0 秒 / 約 0.63〜0.82 秒 |
| 同じものを 4 スレッドで同時に（本体ごと / 本体を除く） | 4 本とも時間の上限（8 秒）を超えた / 4 本で約 1.0 秒 |

ヒープの増え方（15.4 MiB のもの、本体を除く）: 1 本で約 52 MiB、4 本同時で約 144 MiB。同時に動く検証はコンパイル用のスレッドの数までなので、その本数に比例する。

## 測っていないこと

- 実際のツールチェーンのコンポーネント（Rust の `wasm32-wasip2`・`wasm32-wasip3`）。テストは wasm-tools で WAT から作ったものだけ。ツールチェーンのものは #263 か #267 で用意する
- Component Model の仕様のテスト（component-model の `test/` の wast）の取り込み（#263 のリンクと合わせて行う）
- Java 21（NeoForge）での測定と、製品の環境（jarJar で同梱した jar）で検証器のクラスが読めること。MOD の jar に `wasm-tools`・`wasi` が同梱されていることだけ確かめた（開発環境の GameTest は `additionalRuntimeClasspath` から読むので、jarJar の読み込みは確かめられない）。検証器はまだ実行（exec）からは呼ばれない（#263 でつなぐ）。開発環境の実行時クラスパスの配線は、GameTest `componentValidatorWorksOnServer`（両バージョン。`RuntimeSelfCheck.validatesComponent`）で確かめる
