# 0264: Canonical ABI（lift / lower）

- 状態: 採用
- Issue: #264（Epic #259）。前提は ADR 0260（Component Model は `core` に自前で実装）、ADR 0262（コンポーネントの読み込みと検証）、ADR 0073（`kibi:*` の WIT と p1 の import。`cabi_realloc` の呼び方）、ADR 0306（書き先は書くときに確かめる）
- 後続: #263（リンク）、#687（exec）、#265（resource のハンドル表）、#266（非同期・stream・future）、#270（インスタンスをまたぐ燃料）、#616（`spawn` の検査の順）、#267（`wasi:io/poll`・`wasi:clocks` などの p2）、#685（`kibi:*` の p2 の interface と仮置きの WIT の確定）、#251（仮の値の確定）
- 番号は Issue 番号にそろえている

## 背景

Component Model の値（数値・char・string・list・record・tuple・variant・enum・option・result・flags・own・borrow）を、core の値と線形メモリの間で受け渡す部品が要る（Canonical ABI の lift と lower）。#262 で読み込みと検証はできたが、実行にはリンク（#263）が要る（exec につなぐのは #687）ので、#264 ではリンクから呼ぶ部品と、その単体テストまでにする。

## 決定

### 置き場所

`core` の内部（`pro.ikili.kibicomputers.core.runtime.component.canon`。すべて `internal`）。公開 API（`pro.ikili.kibicomputers.api`）は変えない。`DeviceValue` などの公開の型は Canonical ABI の値に合わせない。

| 型 | 役目 |
| --- | --- |
| `CanonType` | 値の型。添字を引いた後の形で、特殊化した型（tuple・enum・option・result）も残す。大きさ・align・平らにした形・深さを、作るときに 1 回だけ計算して持つ |
| `CanonValue` | 受け渡す値。`list<u8>` は `Bytes`（`ByteArray`）にして、要素ごとにオブジェクトを作らない |
| `CanonTypeSpace` | 1 つのコンポーネントの型の添字空間を `CanonType` と関数の計画に解決したもの |
| `CanonFunction` | 同期の関数 1 つの計画（平らにした形、引数・結果をポインタで渡すか）と、lift・lower の入口 |
| `CanonOptions` | canon の option（string-encoding・memory・realloc・post-return） |
| `CanonContext` | 1 回の呼び出しの文脈（メモリ、realloc、符号化、resource の口、予算）。呼び出しのたびに作る |
| `CanonAbi`・`CanonStrings` | 仕様の `load`・`store`・`lift_flat`・`lower_flat` と、文字列の手順 |

`CanonType`・`CanonFunction`・`CanonTypeSpace` は不変なので、コンパイル用のスレッドで作り、実行のスレッドで共有してよい。`ThreadLocal` や呼び出しをまたぐ可変のバッファは使わない。状態を持たないので、保存するもの（ADR 0082）はない。

### 仕様の版

component-model の `design/mvp/canonical-abi/definitions.py`（main、コミット `dd1fefc0c618382359feca6f2c758a2b3ef21c36`、2026-11-13）に従う。memory64 は無効なので（`ComponentValidator.FEATURES`）、ポインタは常に u32（`ptr_size` は 4）として扱う。

- `MAX_STRING_BYTE_LENGTH`・`MAX_LIST_BYTE_LENGTH` は `(1 << 28) - 1`。lift で、文字列は長さ → align → 範囲 → 符号化、list は長さ（要素の数 × 要素の大きさ）→ align → 範囲の順に確かめる。計算は `Long` で行う（u32 の最大の長さでもあふれない）。長さ 0 でも align を確かめる
- `latin1+utf16` の印は `1 << 31`
- `bool` は 0 以外を true、`u8`〜`s16` の平らにした値は上位のビットを捨てる、`char` は 0x110000 以上とサロゲートをトラップ、flags は宣言の外のビットを捨てる、判別子が範囲の外ならトラップ
- variant の平らにした形は `join`（i32 と f32 は i32、ほかの組み合わせは i64）。値は `long` のビット列で持ち、i32・f32 は上位を 0 にして書き、読む側は下位 32 ビットを取るので、`CoerceValueIter` の読み替えと lower の広げ方は、ビット列のまま置くことで済む。余った場所は 0 で埋め、lift では読み飛ばす
- 平らにした形の数え上げは 16 個（`MAX_FLAT_PARAMS`）を超えたところで打ち切る（フィールドが 10 万個の record でも、形を全部は作らない）

### ホストの実装からの使い方

ホストの実装（#263 のリンクで `kibi:*`・`wasi:*` の import に当てるもの）は、場合・フィールド・ビットの番号を手で数えずに、型の名前で値を作る・読む。`CanonType.Record` の `value("name" to v, …)`・`field(v, "name")`、`Variant`・`Enum` の `case("name", 中身)`・`caseName(v)`、`Flags` の `value("a", "b")`・`has(v, "a")`・`namesOf(v)`。option・result・文字列は `CanonValue.some`・`ok`・`err`・`str`。名前の表は初めて引いたときに作る（型は多く作るので、作るときには作らない）。名前がない・中身が型に合わないのはホストの誤りなので `IllegalArgumentException`。

値は汎用の `CanonValue` を経由する。型付きの codec（ホストの Kotlin の型へ直接 lift する形）は、使いやすさのためには要らず、負荷が重いと分かったとき（#267 で測る）にだけ足す。

### 浮動小数点数は決定的なプロファイル

lift（`canonicalize_nan`）も lower（`maybe_scramble_nan`）も、NaN は正規の NaN（`0x7fc00000`・`0x7ff8000000000000`）にする。仕様が許す「乱数の NaN」にすると、保存と復元の前後や台どうしで振る舞いが変わるため。

### 文字列

3 つの符号化（utf8・utf16・latin1+utf16）をすべて扱う（検証を通るものを実行時に断らない）。`realloc` の呼び方（回数・順・引数）はゲストから見えるので、仕様の `store_string_into_range` の 9 通りの手順をそのまま写した。メモリへの書き込みだけは、間にゲストの呼び出しがない区間でまとめて書く（結果のバイト列は同じ）。

- lift した文字列は、元の符号化と長さ（仕様の `String` の組）を持つ。コンポーネントどうしの受け渡し（#263）で、realloc の大きさが仕様どおりになる
- ホストが作る文字列（`CanonValue.Str.of`）は、UTF-8 で長さがそのバイト数のものとして扱う。対になっていないサロゲートは U+FFFD にする（`WasiUtf8.encode` と同じ）。したがって utf8 へは正確な大きさで 1 回、utf16 へは `(2, 2 × バイト数)` で取って短ければ縮める、latin1+utf16 へは `(2, バイト数)` で取り、2^8 以上の文字を見たら広げて、最後に縮める
- 符号化の誤り（UTF-8 の誤り、UTF-16 の対になっていないサロゲート）はトラップ（p1 の `invalid` とは違う）

### realloc

`CanonRealloc`（`fun interface`）で差し込む。`CanonContext` が、大きさ 0 でも呼び、返した場所の align（違えば `OTHER`）と範囲（外なら `MEMORY_OUT_OF_BOUNDS`）を、呼んだ後のメモリの大きさで確かめる（realloc の中で `memory.grow` が起きうるので、大きさは毎回読み直す）。

呼ぶ側（#263 のリンカー）の約束:

- ゲストの呼び出しなので `ctx.callGuest { }` の中で呼び、燃料はゲストの分として数える。燃料が切れたら巻き戻さずにスレッドを持ったまま待つ（ADR 0073）。lift・lower の途中は待機点にならない
- realloc・post-return が別の core インスタンスにあるなら、燃料の holder を切り替える（`ctx.switchFuelHolder`。#270）
- realloc と post-return を呼んでいる間は、そのコンポーネントのインスタンスの `may_leave` を下ろし、その間の canon lower（どの import でも）をトラップにする。p1（止まりうる import だけをトラップにする。ADR 0073）より厳しい。印は実行時だけのもので、保存しない

### 関数の呼び方

`CanonFunction` が `flatten_functype` の 2 つの向きを持つ。

- canon lower（ゲストが import を呼ぶ）: `liftParams` で引数をすべて lift してから、ホストの処理を呼ぶ（Component Model の層では、ホストの検査は全引数の lift の後。#616 がこの順で `spawn` を揃える）。結果は `lowerResult` で返す。結果をポインタで渡す形では、書き先（ゲストの最後の引数）の align と範囲を、ホストの処理が済んでから、書く直前（realloc を呼ぶ前）に確かめる（ADR 0306 と同じ）
- canon lift（ホストがゲストの export を呼ぶ）: `lowerParams` で引数を渡し（17 個以上なら realloc で場所を取って書く）、`liftResult` で結果を読む。結果は post-return を呼ぶ前にすべてホスト側へ写す（メモリを指すビューを返さない）

### 予算

仕様の検査は、1 つの list・string が線形メモリに収まるかだけで、要素がみな同じ大きな領域を指すと、小さな線形メモリでホストに大きな確保をさせられる（例: 1 KiB の文字列を 2 万個の要素が指す `list<string>` で 20 MB）。Component Model の層は全引数を lift してからホストの検査をするので、関数ごとの上限は lift の後にしか効かない。そこで、1 回の呼び出し（lift と lower の両方）に `CanonBudget` を持たせ、写すバイト数（list・string の中身）と値の数（値 1 つごと）の合計で抑え、超えたらトラップにする。list は要素を作る前に、要素の数とバイト数を引く。

- 仮の値（確定は #251）: 1 回の呼び出しで 16 MiB、値 100 万個
- 使った量（`usedBytes`・`usedValues`）は、リンカーが燃料に換算して引いてよい（換算の値は #263 で決める）

### 型の深さ

lift・lower は型の深さだけ再帰する。上限は `CanonLimits.MAX_TYPE_DEPTH = 100`（葉を 1 と数える）。同梱の wasm-tools（wasmparser）も型の入れ子を同じ 100 段までにしていて、それより深いものは検証で落ちる（測定: `option` を 99 段重ねたものは通り、100 段は「type nesting is too deep」）。したがって、検証を通ったコンポーネントで上限に当たることはなく、上限は守りとして置く。型は葉から順に作るので、大きさや形の計算は再帰しない。

### 型の解決の範囲

`CanonTypeSpace` が引くのは、このコンポーネントの型のセクション・型の import（`eq`・`sub resource`）・型の export・`alias outer`（外側の添字空間を渡したとき）まで。インスタンスの export の alias と、インスタンス型・コンポーネント型の中の型は、リンク（#263）で引く。

次のものは、解決のときには断らず「解決できない」として持ち、その型を求められたとき（canon の計画を作るとき）に `WasmLoadException` にする（使わない型のためにコンポーネント全体を断らない）:

- stream・future・async の関数（#266）、canon の `async`・`callback` option（#266）
- error-context、長さ固定の list、map、GC の option（今の検証の機能では通らないので、来たら断る）
- 引けない型（上の範囲の外）と、それを使う型

own・borrow の resource は、その resource を定義・import した型の添字を識別子にする。識別子はその添字空間の中でだけ意味を持つので、別の空間の型どうし（ゲストの型とホストが宣言した型など）を `CanonType.sameShape` で比べるときは、リンク（#263）が import の解決から作った resource の対応（`CanonResourceMatch`）を渡す。1 つの空間の中だけなら `CanonResourceMatch.SAME_SPACE`（番号が同じなら同じ）で比べる。比べる側に対応を渡させるため、既定の値は置かない。ハンドルの lift・lower は `CanonResources` に任せ、ハンドル表は #265 が作った（ADR 0265。`LinkedComponent` がインスタンスごとに持つ）。

### 失敗の扱い

ゲストの入力の誤りは、どれも `WasmTrapException`（範囲の外は `MEMORY_OUT_OF_BOUNDS`、ほかは `OTHER`）。範囲を確かめた後のメモリの読み書きで `WasmMemoryAccessException` が出たら、トラップにする。ホストの値が型に合わないのはホストの誤りなので `IllegalArgumentException`。呼ぶ側（リンカー）が `CanonFunction` に渡す core の引数・結果の数が型と違うのも同じ（`liftParams`・`lowerResult`・`liftResult` が確かめる）。読み込み・リンクの時点の誤りは `WasmLoadException`。

### p1 の `P1Canonical` は置き換えない

`P1Canonical` の KDoc と ADR 0073 は「#264 ができたら置き換える」としていたが、p1 は次の点で意図して Component Model と違う。置き換えると p1 のシステムコールの ABI の振る舞いが変わるので、#264 では置き換えない。

- 範囲外は `fault`、UTF-8 の誤りは `invalid` を返す（Component Model ではトラップ）
- 引数を順に lift しながらホストの検査をする（#616 の既知の差）
- 関数ごとの上限で、要素を読む前に止める
- NaN は生のビットのまま書く

置き換えるなら、p1 の振る舞いを変えずに既存のテストを無修正で通す形で、別の Issue（#616 の後）で行う。

## 測っていないこと

- 実際のゲスト（ツールチェーンが作ったコンポーネント）を動かす統合テストと、仕様の wast のテストは、リンクのできる #263 で行う。#264 のテストは、ByteArray のメモリと、呼んだ順と引数を記録する realloc で、配置・トラップの順・realloc の呼び方を固定したもの
- 予算の値と、燃料への換算は仮（#251）。1,000 台規模で頻繁に呼ばれる関数（`wasi:io/streams` の `write(list<u8>)` など）の割り当ては、`list<u8>` を `ByteArray` で写す形で抑えたが、汎用の値（`CanonValue`）を経由する分の負荷は #267 の実装で測る。重ければ、型付きの codec（ホストの値へ直接 lift する形）を足す
