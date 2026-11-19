# 0616: Component Model の層の `spawn` の検査の順と、p1 との差

- 状態: 採用
- Issue: #616（task）。前提: ADR 0264（Canonical ABI。canon lower は全引数を lift してからホストを呼ぶ）、0685（`kibi:*` の p2）、0073（p1 の「エラー」）、0074（p1 の `spawn`）、0077（p1 の `invoke`）、0757（待機点の巻き直し）

## 決めたこと（要約）

- **Component Model の層（p2。今後の p3 も）は、全引数を lift してから、ホストの検査に入る。** これは canon lower（`CanonFunction`。ADR 0264）の形そのもので、`spawn`・`spawn-with-handles`・`invoke` のホストの本体（`KibiP2Process`・`KibiP2Device`）は lift 済みの値（`CanonValue`）だけを受け取る。#616 の1つ目の項目は、作り直しではなく、この順をテストで固定した。
- **p1 の層は今の順のまま（揃えない）。** p1 は引数を順に lift しながらホストの検査をする（ADR 0073・0074）。これは p1 が意図して Component Model と違う点の1つで（ADR 0264 の「p1 の `P1Canonical` は置き換えない」）、「既知の差（いずれ揃える）」ではなく「p1 の順」として固定する。`WasiP1KibiProcessTest` の `misaligned-order`・`nowait-misaligned` の期待値は変えない。
- **p2 の `spawn-with-handles` の `handles` の個数（`invalid`）を、`fds` の後に確かめるようにした。** 今までは引数の検査の最初（`path` より前）だった。p2 の細かい選択なので、ADR 0685 の委任（「p2/p3 の形について細かい選択が出ても、エージェントが決めて ADR に残してよい（p1 の ABI は対象外）」）の範囲でエージェントが決めた。
- ABI（p1）・保存形式・公開 API・ゲームバランスの値は変えない。

## p1 を揃えない理由

Issue は「p1 の層も揃えるか（全引数の align を先に確かめるか）を決める」としていた。揃えないことにした。

- p1 を揃えるのは、p1 のシステムコールの ABI（観測できる error-code とトラップの境目）を変えること。ADR 0264 は p1 の振る舞いを変えない方針で、ADR 0685 の委任も p1 の ABI を対象外にしている。
- 「全 list の align を先に確かめる」形にしても、Component Model とは揃わない。p1 は次の点で意図して違うので、align だけ動かしても差は残り、ABI だけが変わる。
  - 範囲外のポインタは `fault`、UTF-8 の誤りと判別子の誤りは `invalid`（Component Model ではトラップ）
  - 個数の上限は要素を読む前に確かめる（`args` が 4097 個なら、要素を読まずに `too-big`。Component Model は全要素を lift する）
  - 待たない設定（`P1Wait.NONE`）の `again` は引数を読む前に返る（p2 には待たない設定がない。`WasiP2Io.waitOf` は巻き戻すか、`execWithHandle` で待つ）
- 利用者向けの文書（`docs/syscalls/*/kibi-abi.md`）は、もとから「どの誤りが先に出るかに頼らないで」と書いている。p1 と Component Model の層で、どれが先に出るかは違ってよい。

## Component Model の層の順

1. canon lower が全引数を lift する（引数の順。`path` → `options`（`args` → `env` → `cwd` → `fds`）→ `handles`）。次はここでトラップになり、ホストの本体には入らない。
   - list の ptr が要素の align に揃っていない（長さ 0 でも。`load_list_from_range`）
   - list・string が線形メモリの外
   - string が UTF-8 でない
   - `option`・`variant`・`enum` の判別子が範囲の外（`env`・`cwd` の `option`、`fd-source`、`handle-source`）
   - 不正な borrow のハンドル（`handles` の中の stream・descriptor・process）
   - 1 回の呼び出しの lift の予算（`CanonLimits`。1 回 16 MiB・値 100 万個。ADR 0264）を超える list。`args` が予算の中で 4096 個を超えるだけなら、lift した後に `too-big` で返る
   - `env` が none なら、`env` の list は lift しない（core の引数として ptr を渡しても確かめない。Component Model の決まり）
2. ホストの本体が、lift した値を引数の順に確かめる。上限とコードは p1 と同じ。
   1. `path`: 4096 バイトを超えたら `name-too-long`
   2. `args`: 4096 個を超えたら `too-big`。文字列の合計（`ProcessArgs.MAX_BYTES`）を超えたら `too-big`
   3. `env`: 4096 組を超えたら `too-big`。合計は `args` と同じ予算から引く
   4. `cwd`: 4096 バイトを超えたら `name-too-long`
   5. `fds`: 256 個を超えたら `invalid`。前から順に、`parent(n)` の n が 0..2 の外なら `bad-descriptor`（p2 だけの規則。#74 の Q8-A）
   6. `KibiProcess.options`: fd・親の fd が 2^31 以上なら `bad-descriptor`、`fds` の fd の重複、NUL を含む文字列、env の名前の誤りは `invalid`（args・env の NUL と名前の検査はここなので、5 の `fds` より後）
   7. `handles`（`spawn-with-handles` だけ）: 256 個を超えたら `invalid`（このタスクで 6 の後に移した）。前から順に、fd の重複（`fds` と合わせて）は `invalid`、渡せないものは `bad-descriptor`・`not-supported`
   8. 置き場の空き（`mfile`。待機点型は読み込みの前に `checkHandleRoom`）
   9. カーネル（実行ファイルの `no-entry`・`noexec` など）
3. 待機点型で巻き戻したら、巻き直しで同じ引数を lift し直す（ADR 0757）が、着いた呼び出しは `pendingSpawn` を使い、ホストの検査をやり直さない（ADR 0800）。トラップは本体より前に起きるので、`pendingSpawn` は作られない。

## p1 との差（`spawn`）

| 入力 | p1 | Component Model の層 |
| --- | --- | --- |
| 長すぎる `path` と、揃っていない `args` の ptr | `name-too-long` | トラップ（align） |
| 4097 個の `args` と、揃っていない `fds` の ptr | `too-big` | トラップ（align） |
| 長すぎる `path` と、some で揃っていない `env` の ptr | `name-too-long` | トラップ（align） |
| 長すぎる `path` と、`cwd` の `option` の判別子 2 | `name-too-long` | トラップ（判別子） |
| 長すぎる `path` と、`fds` の `fd-source` の判別子の誤り | `name-too-long` | トラップ（判別子） |
| 長すぎる `path` と、UTF-8 でない `cwd` | `name-too-long` | トラップ（UTF-8） |
| `option`・`fd-source` の判別子の誤りだけ | `invalid` | トラップ |
| UTF-8 の誤りだけ | `invalid` | トラップ |
| 範囲外のポインタ | `fault` | トラップ |
| 待たない設定で、揃っていない list | `again`（引数を読む前） | （待たない設定がない） |
| `env` が none で、揃っていない `env_ptr` | 確かめない | lift しない（同じ） |

`device.invoke`・`start` も同じ形（ADR 0077）: p1 は `method` → `args` → ハンドルの順に確かめるので、長すぎる `method`（`kernel(name-too-long)`）は揃っていない `args` のトラップより先に返る。Component Model の層は `self`（borrow）・`method`・`args` を lift してから確かめるので、トラップになる（不正なハンドルも、p1 は `kernel(bad-descriptor)`、Component Model の層は lift でトラップ）。

## 確かめたこと

- `KibiP2LiftOrderTest`（WAT のコアモジュールと WIT の world から作るコンポーネント。ブロッキング型）: 上の表の上 6 行（長すぎる `path` や 4097 個の `args` と、後の引数の align・判別子・UTF-8 の誤りの組み合わせ）の Component Model の層の列。トラップはメッセージ（`list の場所が align 4 に揃っていない`・`判別子が範囲の外`・UTF-8）まで照合し、子を作っていないこと・ホストの表が元の大きさのことも確かめる。対照として、揃っていて `path` だけ長すぎれば `name-too-long`、`args` が 4097 個だけなら `too-big`、`env` が none なら揃っていない `env_ptr` でも `name-too-long`。`spawn-with-handles` の 257 個の `handles` は、長すぎる `path`（`name-too-long`）・4097 個の `args`（`too-big`）・長すぎる `cwd`（`name-too-long`）・`parent(3)` の `fds`（`bad-descriptor`）・範囲外の fd（`KibiProcess.options` の `bad-descriptor`）が先で、ほかに誤りがなければ `invalid`（手順 2 の 1・2・4・5・6 の後に 7 が来ることの固定）。`invoke` の長すぎる `method` と揃っていない `args` は、トラップ（align 8）
- このテストで確かめていないもの: 表の「判別子の誤りだけ」「UTF-8 の誤りだけ」「範囲外のポインタ」の行、`device.start`（本文で `invoke` と同じ形と書いたが、テストは `invoke` だけ）、手順 1 の `handle-source` の判別子・`handles` の中の不正な borrow のハンドル・lift の予算超えのトラップ。lift の側の決まり（ADR 0264）に従う動きで、このタスクでは固定していない
- `KibiP2WaitPointTest`: 待機点型でも、長すぎる `path` と揃っていない `args` はトラップになり、`pendingSpawn` も子も残らない
- `WasiP1KibiProcessTest`: p1 の順（`misaligned-order`・`nowait-misaligned`）は、期待値を変えずに通る
