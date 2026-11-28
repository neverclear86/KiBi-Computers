export const meta = {
  name: 'issue-to-pr',
  description: 'Issue を worktree で実装して PR を作り、CI とレビューエージェントで確認・修正してマージする。人の確認が要るものは質問して止まる',
  whenToUse: 'KiBi Computers の Issue を自動で進めたいとき。args は Issue 番号・番号の配列・{ issues, window, merge }。省略するとエージェントが選ぶ。merge: false でマージしない。resume: [{ issue, pr }] で止まった PR を CI から続ける。',
  phases: [
    { title: 'Select', detail: '対象の Issue と依存・area・プランの要否を調べる' },
    { title: 'Plan', detail: 'ABI・保存形式・公開 API に関わる Issue だけ、プランを書いてレビューする' },
    { title: 'Implement', detail: 'worktree で実装し、build と両バージョンの GameTest が通るまで直して PR を作る（ビルドに入らないファイルだけの差分なら、node --test だけ）' },
    { title: 'CI', detail: 'PR の CI を待つ（1 回の待ちは最大 60 分。接続の失敗が続いたら未完了として返す）。main と衝突していたら取り込む。落ちたら直す。3 回待ち直しても終わらなければ直さずに人に上げる。待ちの段階が結果を返さなければ 1 回だけ呼び直す' },
    { title: 'Review', detail: '正しさ・ルール違反のレビュー、公開 API の Java 相互運用レビュー、指摘の検証' },
    { title: 'Fix', detail: '確かな指摘を直して CI を待ち直す' },
    { title: 'Merge', detail: '1件ずつ main を取り込み、確認してからマージする。Stacked PRs は 1 つの単位にし、下の段から順に取り込んで全段の CI を待ち、gh stack merge でまとめてマージする（拒否されたら 1 回だけ聞く）。手元では Gradle を動かさず（#985）、取り込みで新しいコミットができたら push して、先頭のコミットの CI が通ってからマージする（取り込むものがなく CI が通っていれば、そのままマージする）。push の後の CI が不安定なテストで落ちたら、Issue に記録して 1 回だけ回し直す（merge: false なら飛ばす）' },
    { title: 'Report', detail: 'PR に結果をまとめる。人の確認が要るものは Fable が前提と推奨を確かめてから質問して needs-human を付ける。マージ済みなら、Gradle が終わってから worktree を片付ける。報告と質問の投稿が結果を返さなければ 1 回だけ呼び直す。結果を返さなかった段階は、最後の結果の stalled に載る（止まったエージェントを /workflows でスキップすると、ここに載り、読むだけの段階なら呼び直される）' },
  ],
}

// 開発フロー（#242）。
// 選択 → (プラン) → 実装ループ → CI → レビュー → 修正 → マージ → 報告。最終確認の段階は置かない。
// 人の確認が要るものは、まず別のモデル（Fable）が前提と推奨を確かめ（escalate）、エージェントで直せるなら直して進む。
// それでも人の判断が要るものは Issue / PR にコメントで質問して needs-human ラベルを付け、返り値の questions にも入れる
// （起動したセッションがチャットでユーザーに聞く）。
// モデル: 判断の重い段階は Opus、定型の段階は Sonnet / Haiku。
const REPO = 'neverclear86/KiBi-Computers'
const MAX_CI_FIXES = 2
const MAX_REVIEW_ROUNDS = 2
// CI の段階で main との衝突を取り込む回数の上限（#855）
const MAX_CONFLICT_MERGES = 2
// マージの段階は手元で Gradle を動かさない（#985）。main を取り込んだ先頭のコミットは CI（build・両 GameTest・両 jar GameTest）が確かめる。
// なので、マージの段階の過負荷の回し直し（#855）・Gradle の起動の失敗のやり直し（#958）・Gradle を待たずに返した回のやり直し（#904）の分岐はない

// 待ち方（#962）。ワークフローの段階のエージェントは、ツールを呼ばない返事でターンを終えると、その場で返り値を求められて返ってしまう
// （PR #951 の fix:951#2 は、Monitor を張って「通知を待つ」とターンを終え、起動から約 30 秒で gradleStillRunning=true を返した。
// やり直しも同じ形で約 85 秒で返した）。返すとバックグラウンドのシェルが殺され、.exit は二度とできない（Gradle の java だけが孤児として走り切る）。
// 待ちを通せた回は、前景の Bash で有限のループを繰り返していた。前景の Bash の上限は 600 秒なので、ループは 570 秒以内に必ず抜ける形にする
const WAIT_LOOP_COUNT = 57
const WAIT_LOOP_SECONDS = 10
// 1 本の Gradle の起動から、終わりを待つ全体の上限（分）。build は 20〜25 分、-Pkibi.stackHeadroom の JVM は 45 分前後、
// GameTest はロックの待ち（既定の上限 90 分）を含めると 90 分を超えることがある。これを過ぎても java が生きていれば gradleStillRunning=true で返す
const GRADLE_WAIT_LIMIT_MINUTES = 150
// 待ち方の決まり（Gradle を動かす段階と、片付けの段階で共有する）
const WAIT_HOW = `**待っている間に、ツールを呼ばない返事（「通知を待つ」「Monitor が知らせる」など）でターンを終えない**（#962）。ワークフローの段階では、ターンを終えるとその場で返り値を求められて返ってしまい、バックグラウンドのシェルが殺されて \`.exit\` は二度とできない。Monitor の通知はターンを終えたあとには届かないので、待ちに Monitor を使わない。待つときは、前景の Bash（\`timeout: 600000\`）で、${WAIT_LOOP_SECONDS} 秒ごとに条件を確かめて ${WAIT_LOOP_COUNT * WAIT_LOOP_SECONDS} 秒以内に必ず抜けるループ（\`for i in $(seq 1 ${WAIT_LOOP_COUNT}); do <条件> && break; sleep ${WAIT_LOOP_SECONDS}; done\`）を実行し、条件が満たされるまで同じ呼び出しを何回でも繰り返す。先頭が \`sleep N\` のコマンドは止められるが、ループの中の \`sleep\` は動く。1 回ごとの出力は \`tail -n 1\` 程度に絞る。`
// .exit を待つ前景のループ（#962）
const EXIT_WAIT_LOOP = `e=<名前>.exit; for i in $(seq 1 ${WAIT_LOOP_COUNT}); do [ -e "$e" ] && break; sleep ${WAIT_LOOP_SECONDS}; done; [ -e "$e" ] && echo "exit=$(cat "$e")" || echo "running: $(tail -n 1 <名前>.log)"`
// Gradle を起動する直前の作業ツリーの木の hash を <名前>.tree に書くコマンド（#962）。本物の index は汚さず、追跡外のファイルも含む。
// 孤児のログの結果を採るとき、起動のあとにコードが変わっていないかをこれで確かめる
const TREE_RECORD = `t=$(mktemp -u); GIT_INDEX_FILE=$t git read-tree HEAD && GIT_INDEX_FILE=$t git add -A && GIT_INDEX_FILE=$t git write-tree > <名前>.tree; rm -f $t`

// 依存の取得の失敗の回し直しに --offline を付ける決まり（#985）。落ちるのは主に、Forge 1.20.1 の createMinecraftArtifacts の
// コンポーネントのメタデータのルールが、キャッシュの期限の切れた資源（mcp_config の zip、forge の userdev の jar）を取り直す HEAD で、
// --offline なら期限によらずキャッシュを使う。依存を変えた差分では、キャッシュにないものがあるので付けない
const DEPENDENCY_FILES = '`gradle/libs.versions.toml`、`**/build.gradle.kts`、`settings.gradle.kts`、`build-logic/**`、`gradle.properties`、`platform-*/gradle.properties`、`gradle/*.properties`、`gradle/wrapper/**`'
const OFFLINE_RETRY_RULE = `- **依存の取得の失敗の回し直しには \`--offline\` を付ける**（#985）。ただし、この段階の差分（実装の段階なら \`origin/main\` に対する PR の差分、直しの段階なら段階の始めのコミットからの自分の変更。main を取り込んだなら、main から入った分も含める）に ${DEPENDENCY_FILES} のどれかがあれば付けない（依存を変えたので、キャッシュにないものがある）。\`--offline\` の回し直しが \`No cached version available for offline mode\`・\`No cached version of\`・\`offline mode\` で落ちたら、それはコードの失敗でもネットワークの 2 回目でもないので、\`--offline\` を付けずに新しい連番でもう 1 回だけ回し直す（1 つのコマンドの走りは、最初の 1 本を入れて最大 3 本まで）。その回もネットワークで落ちたら、「依存の取得の失敗（ネットワーク）が 2 回続いた」として返す。`

// ビルドに入らないファイルだけの差分（#985）。Gradle を回さず、ワークフローの検査（node --test）だけを回す。
// docs/syscalls は SyscallDocTest（core/build.gradle.kts が :core:test の入力にしている）が実装・WIT と突き合わせ、
// */src/** の下の .md（core/src/test/resources/component-spec/README.md など）はリソースとしてビルドに入るので、どちらも対象にしない。
// os/man/** は .md ではないが同梱イメージに入るので、対象にしない（ビルドに入る）
const NON_BUILD_FILES = '`.claude/**`、`docs/**`（`docs/syscalls/**` を除く）、どの `src/` の下でもない `*.md`（ルートの `README.md`・`AGENTS.md`・`CLAUDE.md`、`poc/*/results.md` など）'
// diffHow は段階ごとの差分の取り方（何と比べるか）
function nonBuildRule(diffHow) {
  return `ビルドに入らないファイルだけの差分（#985）:
- 差分の取り方: ${diffHow}
- 差分のファイルが ${NON_BUILD_FILES} と \`.github/**\` だけなら、\`./gradlew\` は起動しない（build も GameTest も回さない。CI の \`build\` が確かめる）。\`docs/syscalls/**\` や \`src/\` の下の \`*.md\`、\`os/man/**\` はビルドに入るので、ほかのファイルと同じに扱う。
- そのときは、worktree の中で \`node --test .claude/workflow-tests/\` を前景で回す（1 秒かからない）。落ちたら直す。\`.github/**\` を含むときは、手元では確かめられないので、push の後の PR の CI の結果で確かめる（pull_request の run は PR 側の \`.github/workflows/build.yml\` で走る）。
- 返す値は \`gradleStillRunning=false\`・\`runningLogs=[]\`。summary には「Gradle は起動していない（差分が <ファイル> だけ）。node --test は N 件通った」の形で書く（Gradle について「実行中」「動いている」「終わっていない」のような言い回しは使わない）。
- ワークフローを変えた差分（\`.claude/workflows/\` か \`.claude/workflow-tests/\` のファイルを 1 つでも含む）は、ビルドに入るファイルと一緒でも、Gradle を回す前に worktree の中で \`node --test .claude/workflow-tests/\` を前景で回す（1 秒かからない。CI は回さない）。落ちたら直す。summary に、node --test を回したことと件数を書く`
}

// 直しの段階（レビューの直し fix・確認事項の反映 resolve・CI の直し ci-fix）の手元の確認（#985）。
// 通しの build と両 GameTest は push の後の CI（build・両 GameTest・両 jar GameTest の上位集合）に任せ、手元は触ったものだけを確かめる。
// 実装の段階の通しの確認は残す（変えない）
const FIX_BASE_DIFF = '段階の始めに worktree で `git rev-parse HEAD` を記録し、そのコミットから今の作業ツリーまでの自分の変更（`git diff --name-only <記録したコミット>` と `git status --porcelain`）で決める。PR 全体の差分では決めない'
// 接続層のコンパイル（両バージョン）。main だけでなく dev のソースセット（src/dev/。dev-driver を devCompileOnly で使い、
// associateWith で接続層の internal な型も使う）も入れる。core や dev-driver だけの直しでは GameTest も回らないので、
// compileDevKotlin を入れないと、src/dev/ の壊れが push の後の CI まで分からない
// レビューの直しと確認事項の反映の終わりに、push できなかったコミットが残っていないかを申告させる（#1007）。
// pushed=false には「直す必要がなかった」と「コミットしたが push できなかった」の 2 つの意味があるので、それを見分ける
function unpushedReportStep(branch) {
  return `最後に（push しなかったときも）、\`git fetch origin\` してから \`git rev-list origin/${branch}..HEAD\` を見る。空でなければ（コミットしたが push できなかったものが残っている）、unpushedCommits=true・pushed=false で、summary に残ったコミットの hash と push できなかった理由を書く（reset・stash で消さない）。空なら unpushedCommits=false。`
}
const PLATFORM_COMPILE_BOTH = '`:platform-neoforge-1.21.1:compileKotlin :platform-neoforge-1.21.1:compileDevKotlin :platform-forge-1.20.1:compileKotlin :platform-forge-1.20.1:compileDevKotlin`（main と、`src/dev/` の dev のソースセット）'
const FIX_LOCAL_CHECKS = `手元の確認（#985。通しの \`./gradlew build\` と両バージョンの \`runGameTestServer\` は、ここでは回さない。push の後の CI が確かめる）:
- 差分の取り方: ${FIX_BASE_DIFF}。
- 触ったファイルに合わせて、次のタスクを 1 本の \`./gradlew\` にまとめて回す（下の Gradle の決まりに従う）:
  - \`core/\` を触ったら \`:core:testClasses\`（\`:core:compileKotlin\` では足りない。processTestResources が \`core/src/test/wasm\` の C と同梱の OS イメージをビルドする）と、触ったクラスのテスト（\`:core:test --tests '<クラスの完全名か *クラス名>'\`）。\`core/src/main/\` を触ったら、接続層は core の内部の型を使うので、${PLATFORM_COMPILE_BOTH} も回す。
  - core にトップレベルの public な宣言を足した・変えたら \`:core:test --tests '*InternalMarkerTest'\`。WIT（\`*.wit\`）・\`WasiP1Kibi\`・\`docs/syscalls/**\` を触ったら \`:core:test --tests '*SyscallDocTest'\`。
  - 公開 API（\`pro.ikili.kibicomputers.api\`、接続層の \`...api\`）か、そのダンプ（\`core/api/*.api\`・\`platform-*/api/*.api\`）を触ったら \`checkKotlinAbi\`（ダンプを直すなら \`updateKotlinAbi\` で作り直してコミットする。ダンプは手で直さない）。
  - \`dev-driver/\` を触ったら \`:dev-driver:compileKotlin\`（テストがあれば \`:dev-driver:test\` も）。\`dev-driver\` は各接続層の \`src/dev/\` から使われるので、公開の宣言を変えたら、${PLATFORM_COMPILE_BOTH} も回す。
  - \`platform-<ローダー>-<MC>/\` を触ったら、そのバージョンの \`:platform-<ローダー>-<MC>:compileKotlin :platform-<ローダー>-<MC>:compileDevKotlin\` と、別の \`./gradlew\` で、そのバージョンの \`runGameTestServer\` だけを回す（触っていないバージョンの GameTest は回さない）。
  - \`os/\`・\`build-logic/\`・\`gradle/\`・\`*.gradle.kts\` を触ったら、影響の範囲が読みにくいので、通しの \`./gradlew build\` を回す。
  - **上のどの決まりにも当たらない、ビルドに入るファイル**（ルートの \`gradlew\`・\`gradlew.bat\`・\`.editorconfig\`・\`gradle.properties\`、\`poc/\`、そのほか上に挙げていない置き場所、など）を 1 つでも触ったら、手元で何も回さずに push してはいけない。影響の範囲が読めないので、通しの \`./gradlew build\` を回す（ビルドに入らないファイルだけの差分は、下の決まりに従う）。
  - \`--tests\` が 1 件にも当たらないと「No tests found for given includes」で落ちる。これはコードの失敗ではない（名前を直して回し直す）。
- summary には、手元で回したコマンド（\`./gradlew\` のタスクの一覧か「Gradle は起動していない」）を書く（報告とレビューで見えるように）。
${nonBuildRule(`${FIX_BASE_DIFF}。`)}`

// Gradle を動かす段階に共通の決まり（#855）。ログは worktree の外（.worktrees/.logs/issue-<n>/）に残し、片付けで消えないようにする。
// GameTest の同時実行は Gradle の側（build-logic の kibicomputers.gametest-lock）で止めるので、ここでは待ち方だけを書く。
// Gradle の起動の失敗（#958）は、エージェントが段階の中で 1 回だけ回し直す（マージの段階は Gradle を動かさないので、スクリプトの回し直しはない。#985）
function gradleRules(number, stage) {
  const logs = `.worktrees/.logs/issue-${number}`
  const startup = `**Gradle の起動の失敗**（#958。タスクが 1 つも動く前に、Gradle がロックの競合通知に使う UDP ソケットを bind できなかったもの）は、次をすべて満たすときだけに当てる: 終了コードが 0 以外、ログに \`FileLockContentionHandler\` と \`BindException: Address already in use\` の両方がある、見出しが \`Gradle could not start your build\` か \`Unable to start the daemon process\`（デーモンの起動の形。\`Could not parse daemon handshake response\` の下に同じ原因が出る）、ログに \`> Task :\` の行も \`BUILD FAILED in\` の行もない。タスクの中（テストや開発用の HTTP サーバーなど）の \`BindException\` は本物の失敗で、これに当たらない。`
  const startupRule = `- ${startup}これも同じ内容で 1 回だけ回し直す。すぐ回すとまた落ちやすい（6 秒後・14 秒後の回し直しが続けて落ちた実例がある）ので、落ちた回の \`.exit\` の最終更新から 90 秒以上置いてから、新しい連番で起動する。置く間は、前景の Bash で待つ（例: \`f=<落ちた回の名前>.exit; until [ $(( $(date +%s) - $(stat -c %Y "$f") )) -ge 90 ]; do sleep 5; done\`。Monitor は使わず、ターンも終えない）。\`./gradlew --stop\` もプロセスの kill も OS の設定の変更もしない。2 回目も起動の失敗なら、それ以上は回さず、「Gradle の起動の失敗（BindException）が 2 回続いた」と理由に書いて、ビルドの失敗として返す。依存の取得の失敗とは別に数える（コマンドごとに、それぞれ 1 回まで）。`
  return `Gradle（\`./gradlew\`）の動かし方（#855。必ず守る）:
- \`./gradlew\` は Bash の \`run_in_background\` で起動する。出力はリポジトリのルートの \`${logs}/\`（worktree の外）に \`${stage}-<内容>-<連番>.log\` として書き、終わったら終了コードを同じ名前の \`.exit\` に書く。
- **ログの名前は、毎回まだ使っていないものにする（例の \`-1\` をそのまま使わない）。** 同じ Issue の中では段階の名前が重なる（CI の直しや衝突の取り込みは呼ばれるたびに同じ名前で、確かめのあとの再実装や resume でも同じ段階をもう一度通る）。前の回の \`.exit\` が残っている名前を使うと、「\`.exit\` ができるまで待つ」が動いている最中にすぐ満たされ、古い終了コードを読んでしまう。起動の前に前景で、\`.log\`・\`.exit\`・\`.tree\` のどれもない連番を選ぶ: \`d=<ルート>/${logs}; mkdir -p $d; n=1; while [ -e $d/${stage}-build-$n.log ] || [ -e $d/${stage}-build-$n.exit ] || [ -e $d/${stage}-build-$n.tree ]; do n=$((n+1)); done; echo $d/${stage}-build-$n\`。起動の直前に、worktree の中で前景で、作業ツリーの木の hash を \`<名前>.tree\` に書く（#962。孤児のログの結果を採るときに使う）: \`cd <worktree> && ${TREE_RECORD}\`。出た名前で起動する: \`cd <worktree> && (./gradlew build --console=plain > <名前>.log 2>&1; echo $? > <名前>.exit)\`。1 本のサブシェルに複数の \`./gradlew\` をつながない（返す羽目になったとき、後ろは起動しないまま残る）。
- **\`./gradlew\` は 10 分では終わらない。** \`build\` は 20〜25 分、\`-Pkibi.stackHeadroom\` の JVM は 45 分前後、GameTest はロックの待ちを含めると 90 分を超えることがある。\`--console=plain\` は \`> Task :core:test\` を出したあと 20 分ほど何も書かないことがあるが、止まってはいない。
- \`.exit\` ができるまで待つ。${WAIT_HOW}\`.exit\` を待つループの例: \`${EXIT_WAIT_LOOP}\`。**Gradle が終わって終了コードを読むまで、この段階を終えて返さない。** バックグラウンドで動かしたまま返さない。\`.exit\` は自分で書かない（シェルが書いたものだけを終了コードとして読む）。起動から ${GRADLE_WAIT_LIMIT_MINUTES} 分たっても \`.exit\` がなく、worktree のパスを含む java が生きているときだけ、待つのをやめて下の \`gradleStillRunning=true\` で返してよい。
- GameTest（\`runGameTestServer\`・\`runJarGameTestServer\`・\`runLoadTestServer\`）は、マシンの中で同時に 1 本だけ動く（Gradle がユーザーホームの \`kibicomputers/locks/gametest.lock\` を取る）。ほかが動いていれば「Waiting for the GameTest lock ... Held by: ...」を 30 秒ごとに出して待つので、止めずに待つ。待ちの時間は失敗ではない。
- GameTest が落ちたら、\`<worktree>/platform-*/run/logs/latest.log\` を \`${logs}/${stage}-<内容>-<連番>-<バージョン>-latest.log\` に写す（worktree を片付けても調べられるように）。
- \`./gradlew --stop\` は使わない（デーモンはほかの worktree と共有しているので、そちらのビルドまで止まる）。
- **依存の取得の失敗（コードと関係のない、ネットワークの層の失敗）は、同じ内容で 1 回だけ回し直す**（#904）。当たるのは、\`UnknownHostException\`、名前解決の失敗（Windows では「そのようなホストは不明です。 (maven.neoforged.net)」のように日本語で出る）、\`Connection refused\`・\`Connection reset\`、\`Read timed out\`・\`connect timed out\`、HTTP の 5xx、原因がこれらの \`Could not GET\`・\`Could not HEAD\`・\`Could not resolve\`。回し直しは新しい連番で起動し、その \`.exit\` を待つ。2 回目も同じように落ちたら、それ以上は回さず、「依存の取得の失敗（ネットワーク）が 2 回続いた」と理由に書いて、ビルドの失敗として返す。\`Could not find <座標>\`（404。版の誤り）、Zig・Lua・wasi-sdk などの SHA-256 の不一致、コンパイルやテストの失敗は、回し直さない（本物の失敗）。
${OFFLINE_RETRY_RULE}
${startupRule}
- **返す値の \`gradleStillRunning\` と \`runningLogs\`**（#904）: Gradle が終わって終了コードを読めたら、\`gradleStillRunning=false\`・\`runningLogs=[]\`。どうしても終わりを待てずに返すことになったら（決まりに反するので、本当に最後の手段）、失敗（needs_human・blocked・build_failed など）に見せかけず、\`gradleStillRunning=true\` にし、\`runningLogs\` に \`.exit\` のないログの名前を入れて返す。このスクリプトが、Gradle の終わりを待つ指示を付けてこの段階を 1 回だけやり直す。`
}

// Gradle を動かす段階のスキーマに足す項目（#904）。エージェントが Gradle の終わりを待たずに返した回を、スクリプトの側で見分ける
const GRADLE_PENDING_PROPS = {
  gradleStillRunning: { type: 'boolean', description: 'この段階で起動した Gradle のどれかが、終了コード（.exit）を読む前に返したなら true。すべて終わって終了コードを読めたら false' },
  runningLogs: { type: 'array', items: { type: 'string' }, description: 'gradleStillRunning のとき、.exit のないログの名前（.worktrees/.logs/issue-<n>/ の中のもの）。それ以外は空' },
}
function withGradlePending(schema) {
  return {
    ...schema,
    properties: { ...schema.properties, ...GRADLE_PENDING_PROPS },
    required: [...schema.required, 'gradleStillRunning', 'runningLogs'],
  }
}

// gradleStillRunning=false と申告しながら、理由の文が「Gradle がまだ終わっていない」を示す回を拾う（#904。PR #889 の実例:
// 「最初の ./gradlew build は、maven.neoforged.net の名前解決の失敗…で落ちたので、同じ内容でやり直した（merge-build-2）。
// やり直しは返す時点で :core:test を実行中で、終了コードを読めていない。…build が落ちたわけではないが、スキーマに「未完了」がないため needs_human で返す」）。
// 本物の失敗まで拾わないよう、失敗の理由として読む文（取り込みの build_failed、push しなかった修正）にだけ当てる
// （マージの段階は #985 から Gradle を動かさないので、もう見ない。CI の「実行中」の記述を取り違えないように）。
// 依存の取得の失敗を 1 回回し直す決まりがあるので、未完了の回の理由の文には、ふつう 1 回目の失敗（落ちた、Could not HEAD など）も書かれる。
// そのため、失敗の証拠が文のどこかにあるだけでは外さず、手がかりの強さで 2 つに分ける:
// - 強い手がかり（GRADLE_UNFINISHED_STRONG）: 終了コードを読めていない、.exit がない、スキーマに「未完了」がない。Gradle が終わって失敗したなら
//   終了コードは読めているので、失敗の証拠があっても未完了と見る
// - 弱い手がかり（GRADLE_UNFINISHED_WEAK）: 「<タスク> が実行中」「終わる前」など。本物の失敗の説明にも出るので、同じ一文（。・改行で区切る）に
//   失敗の証拠（GRADLE_FAILURE_EVIDENCE）がなく、文の全体に GameTest のロックの待ちの上限の証拠（GRADLE_LOCK_LIMIT_EVIDENCE）もないときだけ拾う
const GRADLE_UNFINISHED_STRONG = /終了コードを(まだ)?(読めて|読んで|確かめられて|確かめて|確認できて|確認して)いない|\.exit\s*(が|は)?(まだ)?(ない|できていない|書かれていない)|スキーマに「?未完了/i
const GRADLE_UNFINISHED_WEAK = /(gradle|ビルド|build|GameTest|テスト|test|:[\w.-]+:\w+)[^。\n]{0,30}?(を|が|は)?(まだ)?(実行中|動いている|走っている|終わっていない|終わる前)|still running|not (yet )?finished/i

// Gradle が終わったあとの本物の失敗の証拠。同じ一文にこれがあれば、弱い手がかりは未完了とは見ない
// （例: 「GameTest が時間内に終わっていない（タイムアウト）」、「testFoo が終わる前にサーバーが落ちた」、
// 「Held by: ...runGameTestServer in .worktrees/issue-900 が実行中のまま」）
const GRADLE_FAILURE_EVIDENCE = /Held by|Waiting for the GameTest lock|ロック|上限|タイムアウト|time[ds]? ?out|落ち(た|て)|クラッシュ|crash|required tests failed|BUILD FAILED|FAILED\b|Could not (GET|HEAD|resolve|find)|UnknownHost/i
// GameTest のロックの待ちの上限に達した証拠。これが文のどこかにあれば、弱い手がかりは見ない（「上限に達した。runGameTestServer が実行中のままだった」のように、
// ほかの worktree のタスクが実行中だと別の一文で添えた回）。依存の取得の失敗（1 回目の失敗の記述）は当たらないよう、ロックに限る
const GRADLE_LOCK_LIMIT_EVIDENCE = /Held by|Waiting for the GameTest lock|ロックの待ち|ロックを待/i

// 孤児のログの結果を採った句（#962。gradleWaitNote の手順 3 が決めた言い回し「孤児のログ <名前> を採った（BUILD SUCCESSFUL）」）。
// その孤児は終わっているので、この句（と直後のかっこ）の中の「.exit がない」などは未完了の手がかりとは見ない。
// 外すのはこの句だけで、一文まるごとは外さない（同じ一文に別のログの未完了が書かれていれば、それは今までどおり見る）。
// 名前のない「孤児のログを採ったかは分からない」のような文は採った句ではないので外さない（「を採った」の直後が「か」のものは当てない）。
// 名前は、連番を選ぶコマンドが出す完全なパス（`/`・`\`・`:` を含む）や、バッククォートで囲んだものでも当てる。
// 名前と名前の間には区切り（、,，と）を必ず挟む。区切りを省ける形（`(?:[\w.-]+\s*)*`）は入れ子の量指定子になり、
// 「を採った」が続かない文で指数的にバックトラックする（#972 のレビュー）
const ORPHAN_LOG_NAME = String.raw`(?:\x60[^\x60\n]+\x60|[\w.\-/\\:~]+)`
const ORPHAN_ADOPTED = new RegExp(
  String.raw`孤児のログ(?:\s*${ORPHAN_LOG_NAME}(?:\s*(?:[、,，]|と)\s*${ORPHAN_LOG_NAME})*)?\s*を採った(?!か)(?:（[^）\n]*）|\([^)\n]*\))?`,
  'g',
)

// 理由の文が、Gradle がまだ終わっていないことを示すか（#904）
function textSaysGradleUnfinished(text) {
  if (!text) return false
  text = text.replace(ORPHAN_ADOPTED, '')
  if (!text.trim()) return false
  if (GRADLE_UNFINISHED_STRONG.test(text)) return true
  if (GRADLE_LOCK_LIMIT_EVIDENCE.test(text)) return false
  return text.split(/[。\n]/).some(s => GRADLE_UNFINISHED_WEAK.test(s) && !GRADLE_FAILURE_EVIDENCE.test(s))
}

// 返り値が「Gradle を待たずに返した」か。textEligible のときだけ text も見る。当たらなければ null
function gradlePending(r, text, textEligible) {
  if (!r) return null
  const logs = Array.isArray(r.runningLogs) ? r.runningLogs : []
  if (r.gradleStillRunning === true) return { by: 'flag', logs, text: text ?? '' }
  if (textEligible && textSaysGradleUnfinished(text)) return { by: 'text', logs, text }
  return null
}

// コマンドラインに worktree のパスを含む java のプロセスを探す PowerShell のコマンド（#855）。
// 番号が前方一致する別の worktree（issue-<n>0 など）のものを数えないよう、番号の後ろに数字が続かないものだけを数える
function worktreeJavaQuery(number, dirName = `issue-${number}`) {
  return `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match '[\\\\/]\\.worktrees[\\\\/]${dirName}(?![0-9])' }`
}

// Gradle を待たずに返した段階を、もう 1 回だけ呼ぶときに付ける指示（#904）。
// 返したときにバックグラウンドのシェルが止まり、前の回の .exit は二度とできないことがある（Gradle の java だけが孤児として最後まで走る）。
// なので前の回の .exit は待たせず、孤児の Gradle が終わるのを待つ。終わった孤児のログは、条件を満たせばその結果を採る（#962）。
// 待ちの上限は、build の残り（最大 25 分）と -Pkibi.stackHeadroom の JVM（45 分前後）を収める長さにする（30 分では足りなかった）
const ORPHAN_WAIT_MINUTES = 60
// 同じ worktree で前の回の Gradle（孤児）が終わるのを待つ手順（#904・#961）。gradleWaitNote の手順 2 で使う
// （マージの段階の CI の回し直し（merge-rerun）は Gradle を動かさないので、もう使わない。#985）
function orphanWaitSteps(number, worktree) {
  const logs = `.worktrees/.logs/issue-${number}`
  // resume の `.worktrees/stack-pr-<PR>`（#936）なら、その名前で探す
  const dirName = STACK_WORKTREE_RE.exec(worktree ?? '')?.[1]
  return `自分の \`./gradlew\` を起動する前に、前の回の Gradle が終わるのを最大 ${ORPHAN_WAIT_MINUTES} 分待つ。終わったとみなすのは、次のどちらも満たすとき（主に見るのは java のプロセス。\`--console=plain\` は 20 分ほど何も書かないことがあるので、ログの最終更新だけで決めない）:
   - コマンドラインに worktree のパス（${worktree ?? `.worktrees/issue-${number}`}）を含む java のプロセスがない（PowerShell の \`${worktreeJavaQuery(number, dirName)}\`）。
   - \`${logs}/\` の \`.exit\` のないログ（\`-latest.log\` で終わるものは除く）が、どれも最終更新から ${STALE_LOG_MINUTES} 分以上たっている（\`date +%s\` と \`stat -c %Y <ログ>\` で測る）。
   待ち方: ${WAIT_HOW}java の有無は、前景の PowerShell（\`timeout: 600000\`）のループで確かめてよい（例: \`$q = { ${worktreeJavaQuery(number, dirName)} }; $end = (Get-Date).AddSeconds(${WAIT_LOOP_COUNT * WAIT_LOOP_SECONDS}); while ((Get-Date) -lt $end -and (& $q)) { Start-Sleep ${WAIT_LOOP_SECONDS} }; if (& $q) { 'running' } else { 'gone' }\`）。
   プロセスは殺さない。\`./gradlew --stop\` も使わない。同じ worktree で Gradle を 2 本動かすと、\`.gradle\` のロックや \`build/\`・\`run/\` の出力、GameTest のロックを取り合う。${ORPHAN_WAIT_MINUTES} 分たっても終わらなければ、自分の Gradle は起動せず、\`gradleStillRunning=true\` で返す。`
}

function gradleWaitNote(number, worktree, pending) {
  return `**やり直し（#904）**: 前回のこの段階は、Gradle の終わり（\`.exit\`）を待たずに返した（動いていたログ: ${pending.logs.length ? pending.logs.join(', ') : '申告なし'}。前回の理由: ${pending.text || '-'}）。この段階を手順の最初からもう 1 回だけやり直す。次を守る:
1. 前の回のログの \`.exit\` は待たない。返したときにバックグラウンドのシェルが止まるので、\`.exit\` は二度とできないことがある。
2. ${orphanWaitSteps(number, worktree)}
3. 孤児のログ（前の回に起動して \`.exit\` を残さずに終わったもの）の扱い（#962）。前の回に起動したログの 1 本ずつについて決める:
   - **成功を採ってよい**のは、次の 3 つをすべて満たすとき: (a) 手順 2 で、worktree のパスを含む java のプロセスがない、(b) ログの末尾 20 行に \`^BUILD SUCCESSFUL in \` の行がある、(c) 同じ名前の \`<名前>.tree\` があり、その中身が今の作業ツリーの木の hash（worktree の中で \`${TREE_RECORD.replace(' > <名前>.tree', '')}\` が出す値）と一致する。このときは、そのコマンドを新しい連番で回し直さず、そのログの結果をこの段階の結果として採る。
   - 末尾に \`BUILD FAILED\` がある孤児（(a) と (c) を満たすもの）は、非 0 の \`.exit\` を読んだのと同じに扱う（本物の失敗なら直す。依存の取得の失敗や起動の失敗なら、それを 1 回目と数えて、決まりどおり新しい連番で 1 回だけ回し直す）。
   - 末尾に BUILD の行がない、\`<名前>.tree\` がない（この決まりより前のログ）、hash が違う（起動のあとにコードが変わった）、のどれかなら、結果は採らず、新しい連番（\`.log\`・\`.exit\`・\`.tree\` のどれもない名前）で、この段階の手順どおりに起動し直し、その \`.exit\` を読むまで返さない。
   - \`runningLogs\` にあって \`.log\` がないもの（1 本のシェルにつないだ後ろのコマンドなど）は、起動していないので、手順どおりに新しい連番で起動する。
   - 採ったときは、\`.exit\` は書かない（\`.exit\` はシェルが書いたものだけにする）。代わりに \`<名前>.orphan\` に、採った結果と理由（例: \`BUILD SUCCESSFUL; no java; tree matches\`）を書く。summary・理由の文には「孤児のログ <名前> を採った（BUILD SUCCESSFUL）」の形で書き、同じ一文に「\`.exit\` がない」「終了コードを読めていない」とは書かない。採った分は Gradle が終わっているので、\`gradleStillRunning=false\` でよい。
4. 前の回の作業の続き（コミット済みで push していない取り込みや修正）は、そのまま使う（reset・force push はしない）。\`git merge origin/main\` をもう一度実行して「Already up to date」になっても問題ない。
5. 今回も Gradle を待たずに返すことになったら、\`gradleStillRunning=true\` で返す（このスクリプトはもうやり直さない。この段階は「確かめられなかった」失敗として扱われ、先へ進まずに止まって人に上がる。PR（まだなければ Issue）に確認のコメントと \`needs-human\` が付く）。`
}

// Gradle を動かす段階を呼び、Gradle を待たずに返したら、待つ指示を付けて 1 回だけ呼び直す（#904）。
// 呼び直すかどうかはスクリプトが決める（エージェントに任せない）。回数の上限は呼び出し 1 か所ごとに 1 回。
// run(note) は note（null か、やり直しの指示）を受けてエージェントを呼ぶ。pendingOf(r) は gradlePending の結果を返す。
// 返り値の stillPending は、やり直しでも待たずに返したとき true。呼ぶ側はこの結果を成功として先へ進めず、失敗として止め、
// escalate を通して人に上げる（pendingReason を理由に使う。gradleWaitNote の手順 5 がエージェントにそう伝えている）。
// やり直しの段階が結果を返さなかった（失敗した）ときは result=null・retryFailed=true で、stillPending=false（2 回待たずに返したのではない）。
// 呼ぶ側は、段階が失敗したときと同じに扱う
async function withGradleWait(what, number, worktree, run, pendingOf) {
  const first = await run(null)
  const p1 = pendingOf(first)
  if (!p1) return { result: first, waited: false, stillPending: false }
  const how = p1.by === 'text' ? 'gradleStillRunning=false と申告したが、理由の文が未完了を示す（決まりに反した申告）' : 'gradleStillRunning=true'
  log(`${what}: Gradle の終わりを待たずに返した（${how}。ログ: ${p1.logs.join(', ') || '-'}）。Gradle を待つ指示を付けて 1 回だけやり直す`)
  const second = await run(gradleWaitNote(number, worktree, p1))
  if (!second) {
    log(`${what}: Gradle を待つやり直しの段階が結果を返さなかった（段階の失敗として扱う）`)
    return { result: null, waited: true, stillPending: false, retryFailed: true, first: p1 }
  }
  const p2 = pendingOf(second)
  if (p2) log(`${what}: やり直しでも Gradle の終わりを待たずに返した（ログ: ${p2.logs.join(', ') || '-'}）。もうやり直さない`)
  return { result: second, waited: true, stillPending: !!p2, first: p1 }
}

// やり直しでも Gradle を待たずに返した（stillPending）ときの、理由に付ける文（#904）
function pendingReason(what, r) {
  const logs = Array.isArray(r?.runningLogs) && r.runningLogs.length ? `（動いていたログ: ${r.runningLogs.join(', ')}）` : ''
  return `${what}が、Gradle の終わりを待たずに 2 回返した${logs}（やり直しは 1 回まで）。ビルドとテストは確かめられていない`
}

// worktree が本物か（git worktree list に載っているか）を確かめる手順（#855）。壊れた残りに対して git -C を実行すると、
// 親のディレクトリをさかのぼって、ユーザーのチェックアウト（main）に対して走る
function worktreeCheck(number) {
  const dir = `.worktrees/issue-${number}`
  return `\`${dir}\` があるときは、使う前に本物の worktree か確かめる: (a) \`git worktree list --porcelain\` の \`worktree\` 行に、そのディレクトリの絶対パスがある（大文字小文字と \`/\`・\`\\\` をそろえて比べる）、(b) \`git -C ${dir} rev-parse --show-toplevel\` がそのディレクトリ自身を指す。どちらかが違えば、片付けの途中で壊れた残りなので使わない。\`${dir}.stale\`（もうあれば \`.stale2\`、\`.stale3\`…）に名前を変え、\`git worktree prune\` してから、ないときと同じ手順で作り直す（ブランチがもうあれば \`-b\` を付けずにそのブランチで作る）。名前を変えられない（使用中）なら、そこで止めて理由を返す。`
}

const OPTS = Array.isArray(args) || typeof args === 'number' || args == null
  ? { issues: args == null ? [] : [].concat(args) }
  : args
const WINDOW = OPTS.window ?? 3
const MERGE = OPTS.merge !== false
// resume: [{ issue, pr }] で、途中で止まった PR を CI → レビュー → マージの続きから進める（選択・助言・実装は飛ばす）
const RESUME = Array.isArray(OPTS.resume) ? OPTS.resume : []
// 既定では、着手前の助言は Opus、コードのレビューは1本（壊れると重い PR は Fable、それ以外は Opus）、指摘の検証は Opus 1本。
// 壊れると重い PR = area が HEAVY_AREAS に入る Issue か、実装がスレッド・並行処理に触れたと答えた PR。
// （#576〜#579 での比較の結論。2本立ての費用の大半は Fable 側で、検証は Sonnet だと範囲外として外す指摘が多かった）
// fable: true は比較用。助言・プランのレビュー・コードのレビュー・指摘の検証を Opus と Fable の両方で行い（指摘は両方の和を使う）、
// 人に聞く前の確かめには Opus を影として並べる。両者の判断は結果の comparison に残す
const FABLE = OPTS.fable === true
const HEAVY_AREAS = ['kernel', 'persistence', 'runtime', 'storage']

const M = {
  select: { model: 'sonnet', effort: 'medium' },
  plan: { model: 'opus', effort: 'medium' },
  planReview: { model: 'opus', effort: 'high' },
  implement: { model: 'opus', effort: 'medium' },
  ciWait: { model: 'haiku', effort: 'low' },
  fixFirst: { model: 'sonnet', effort: 'high' },
  fixAgain: { model: 'opus', effort: 'medium' },
  review: { model: 'opus', effort: 'medium' },
  javaApi: { model: 'sonnet', effort: 'high' },
  verify: { model: 'opus', effort: 'medium' },
  merge: { model: 'sonnet', effort: 'medium' },
  report: { model: 'haiku', effort: 'low' },
  crossCheck: { model: 'fable', effort: 'high' },
  advise: { model: 'opus', effort: 'high' },
  // 壊れると重い PR のレビューと、fable: true のときに並べるもの
  fable: { model: 'fable', effort: 'high' },
  crossCheckShadow: { model: 'opus', effort: 'high' },
}

// Opus と Fable の判断の記録（target は「Issue #n」「PR #n」）
const comparisons = []
function record(target, entry) {
  if (FABLE) comparisons.push({ target, ...entry })
}

// ---- 結果を返さなかった段階（#953） ----
// agent() が null（undefined）で決着した段階を記録し、バッチの最後の結果（stalled）と各 Issue の結果（stalledStages）に載せる。
// null になるのは、人が /workflows で止まったエージェントをスキップしたときと、API の終端エラーのとき。
// 決着しない（返らない）エージェントには、スクリプトは気づけない（agent() に時間の上限はなく、Date.now() も使えない）。
// なので、返らないことへの本命の対策は、段階の中の待ちの上限（CI 待ちとマージのプロンプトの ciWaitRules）で、
// 止まったエージェントを人がスキップすれば null で決着し、ここで記録され、読むだけの段階なら 1 回だけ呼び直される（手での回復の道）。
// 時間切れのタイマー（Promise.race）は使わない。時間切れでも裏のエージェントは止まらず、Gradle・push・マージの段階では呼び直した回とぶつかる。
// JS はシングルスレッドなので、並列の鎖から push してよい
const stalls = []
// PR の番号 → Issue の番号（ラベルに PR の番号しかない段階を、Issue に結び付ける）
const prIssue = new Map()
function stallTarget(label) {
  const rest = label.split(':')[1] ?? ''
  const m = rest.match(/^(?:(pr|issue))?#(\d+)/) ?? rest.match(/^()(\d+)/)
  if (!m) return { issue: null, pr: null }
  const n = Number(m[2])
  if (m[1] === 'pr' || (!m[1] && !rest.startsWith('#'))) return { issue: prIssue.get(n) ?? null, pr: n }
  return { issue: n, pr: null }
}
// agent() を呼び、null なら記録する（呼び直しはしない）。記録の retried・recovered は、呼び直す側（stepRetrying）が書き込む
async function step(prompt, opts) {
  return (await stepWithEntry(prompt, opts)).r
}
async function stepWithEntry(prompt, opts) {
  const r = await agent(prompt, opts)
  if (r != null) return { r, entry: null }
  const entry = { ...stallTarget(opts.label), stage: opts.label.split(':')[0], label: opts.label, retried: false, recovered: false }
  stalls.push(entry)
  log(`段階 ${opts.label} が結果を返さなかった`)
  return { r, entry }
}
// 読むだけの段階か、重ねても害の小さい段階（CI 待ち・報告・質問の投稿）だけに使う。null なら 1 回だけ呼び直す（上限は呼び出し 1 か所ごとに 1 回）。
// Gradle を動かす段階（実装・取り込み・直し・マージ）には使わない（PR の二重作成や、マージ・push の衝突が起きうる）。
// retryNote は呼び直しのプロンプトの先頭に付ける（1 回目が途中まで進んでいたときの重複を避ける指示など）
async function stepRetrying(prompt, opts, retryNote) {
  const { r, entry } = await stepWithEntry(prompt, opts)
  if (r != null) return { result: r, retried: false }
  entry.retried = true
  log(`段階 ${opts.label} を 1 回だけ呼び直す`)
  const again = await agent(`${STALL_RETRY_NOTE}${retryNote ? `\n${retryNote}` : ''}\n\n${prompt}`, { ...opts, label: `${opts.label}~stall` })
  entry.recovered = again != null
  if (again == null) log(`段階 ${opts.label} は呼び直しでも結果を返さなかった`)
  return { result: again, retried: true }
}
function stallText(x) {
  return `${x.label}（${x.retried ? (x.recovered ? '呼び直して回復した' : '呼び直しても返さなかった') : '呼び直していない'}）`
}
const STALL_RETRY_NOTE = '（呼び直し: 前の回のこの段階は結果を返さずに終わった（#953）。途中まで進んでいたかもしれないので、手順を始める前に今の状態を確かめ、済んでいるものは繰り返さない。）'

const VERDICT_RANK = { approve: 0, revise: 1, needs_human: 2 }
// 2つのプランのレビューを合わせる（重い方の判定、指摘は和）
function combinePlanReviews(opus, fable) {
  if (!opus || !fable) return opus ?? fable
  return {
    verdict: VERDICT_RANK[fable.verdict] > VERDICT_RANK[opus.verdict] ? fable.verdict : opus.verdict,
    must: [...opus.must, ...fable.must.map(m => `（Fable）${m}`)],
    undecided: [...opus.undecided, ...fable.undecided.map(u => `（Fable）${u}`)],
  }
}

// ---- 並行制御 ----

function semaphore(n) {
  let active = 0
  const queue = []
  return {
    async acquire() {
      if (active < n) { active++; return }
      await new Promise(resolve => queue.push(resolve))
    },
    release() {
      const next = queue.shift()
      if (next) next()
      else active--
    },
  }
}

function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

const slots = semaphore(WINDOW)
const mergeLock = semaphore(1)
// 同じ area の Issue を同時に流す数（#986）。同じ area の PR どうしの衝突は、CI の段階の取り込みとマージの段階で受け止める
const AREA_WINDOW = 2
const areaLocks = new Map()
function areaLock(area) {
  if (!areaLocks.has(area)) areaLocks.set(area, semaphore(AREA_WINDOW))
  return areaLocks.get(area)
}
// area の名前の表記ゆれ（`area: runtime`・`Runtime`・前後の空白）で別のロックにならないよう、そろえてから使う（#986）
function normArea(a) {
  return String(a ?? '').trim().replace(/^area:\s*/i, '').trim().toLowerCase()
}
// ロックを取る area の一覧。そろえた名前で重複を除き、全部の流れで同じ順（ソート順）に取る（デッドロックを防ぐ）
function lockAreas(areas) {
  return [...new Set((areas ?? []).map(normArea).filter(a => a))].sort()
}

// ---- スキーマ ----

const SELECT_SCHEMA = {
  type: 'object',
  properties: {
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          number: { type: 'integer' },
          title: { type: 'string' },
          areas: { type: 'array', items: { type: 'string' }, description: '`area: ` を除いた名前' },
          openDeps: { type: 'array', items: { type: 'integer' }, description: '「依存: #n」のうち閉じていないもの' },
          blockedReason: { type: ['string', 'null'], description: '着手できない理由（未決の decision、needs-human、human-task、担当者あり、進行中の PR など）。閉じていない依存先は含めない（openDeps で扱う）。着手できるなら null' },
          needsPlan: { type: 'boolean', description: 'システムコールの ABI、保存形式、公開 API に関わるなら true' },
        },
        required: ['number', 'title', 'areas', 'openDeps', 'blockedReason', 'needsPlan'],
      },
    },
  },
  required: ['issues'],
}

const PLAN_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['planned', 'blocked'] },
    plan: { type: 'string', description: 'Issue に投稿したプランの本文' },
    reason: { type: ['string', 'null'] },
  },
  required: ['status', 'plan', 'reason'],
}

const PLAN_REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['approve', 'revise', 'needs_human'] },
    must: { type: 'array', items: { type: 'string' } },
    undecided: { type: 'array', items: { type: 'string' }, description: '既存の decision で決まっていない設計の選択' },
  },
  required: ['verdict', 'must', 'undecided'],
}

const IMPLEMENT_SCHEMA = withGradlePending({
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['pr_opened', 'blocked', 'already_done'] },
    pr: { type: ['integer', 'null'] },
    branch: { type: ['string', 'null'] },
    worktree: { type: ['string', 'null'], description: 'worktree の絶対パス' },
    publicApiChanged: { type: 'boolean' },
    touchesConcurrency: { type: 'boolean' },
    summary: { type: 'string' },
    openQuestions: { type: 'array', items: { type: 'string' }, description: 'PR を作ったが、人の確認が要るかもしれない点（PR 本文の「確認してほしいこと」と同じもの）。なければ空' },
    // 省略可能（#936）。上の段の PR（base が別の PR のブランチ）の、CI の段階の衝突の取り込み先に使う。省略は main
    baseRef: { type: ['string', 'null'], description: 'PR の base のブランチ（`gh pr view --json baseRefName`）。ふつうは main' },
  },
  required: ['status', 'pr', 'branch', 'worktree', 'publicApiChanged', 'touchesConcurrency', 'summary', 'openQuestions'],
})

// 必須は passed と mergeable だけ（#953）。CI 待ちは Haiku なので、長い自由記述の欄に別の欄を紛れ込ませて必須の欄が欠け、
// スキーマの検査で断られたまま止まった実例がある（#887 の PR #948）。ほかの欄は省略可能にし、normalizeCi で既定値を補う
const CI_SCHEMA = {
  type: 'object',
  properties: {
    passed: { type: 'boolean' },
    mergeable: { type: 'string', enum: ['MERGEABLE', 'CONFLICTING', 'UNKNOWN'], description: '`gh pr view --json mergeable` の値' },
    unfinished: { type: 'boolean', description: '待ちの上限に達した、または接続の失敗が続いて、CI が終わる前に待つのをやめたら true（passed=false にする）。省略は false' },
    failingChecks: { type: 'array', items: { type: 'string' }, description: '終わって落ちたチェックの名前。省略は []' },
    failureLog: { type: 'string', description: '失敗の原因が分かるログの短い抜粋（2,000 文字まで。プレーンな文字列で、XML・タグを貼らない）。通ったら省略' },
    noChecks: { type: 'boolean', description: '待っても PR にチェックが 1 つも出なかったら true。省略は false' },
    // 数でも文字列でも受ける（モデルは run の ID を文字列で返すことがある。断られる理由を増やさない）
    runId: { type: ['integer', 'string', 'null'], description: '落ちた run の ID（分かれば）' },
    attempt: { type: ['integer', 'string', 'null'], description: '落ちた run の試行の番号（分かれば）' },
  },
  required: ['passed', 'mergeable'],
}

// CI 待ちの結果の欄の既定値を補う（#953）。noChecks の既定は false（true にすると、衝突していないのにチェックが出ない扱いに化ける）
function normalizeCi(raw) {
  const failureLog = typeof raw.failureLog === 'string' ? raw.failureLog : ''
  const head = raw.runId ? `run ${raw.runId}${raw.attempt ? `（試行 ${raw.attempt}）` : ''}\n` : ''
  return {
    ...raw,
    passed: raw.passed === true,
    mergeable: raw.mergeable ?? 'UNKNOWN',
    unfinished: raw.unfinished === true,
    failingChecks: Array.isArray(raw.failingChecks) ? raw.failingChecks : [],
    failureLog: head && !failureLog.includes(String(raw.runId)) ? `${head}${failureLog}` : failureLog,
    noChecks: raw.noChecks === true,
  }
}

// must には直し方を書かせない（説明と修正案を同時に求めると誤判定が増える）
const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          line: { type: ['integer', 'null'] },
          severity: { type: 'string', enum: ['must', 'should'] },
          problem: { type: 'string' },
        },
        required: ['file', 'line', 'severity', 'problem'],
      },
    },
  },
  required: ['findings'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    confirmed: { type: 'array', items: { type: 'integer' }, description: '本当に直すべき指摘の番号' },
    notes: { type: 'string' },
  },
  required: ['confirmed', 'notes'],
}

const FIX_SCHEMA = withGradlePending({
  type: 'object',
  properties: {
    pushed: { type: 'boolean' },
    // CI の直し（ci-fix）だけが使う。PR と関係のない不安定なテストと判断し、push せずに落ちたジョブを回し直した
    // （マージの段階の同じ確かめは、FLAKY_SCHEMA の outcome で返す。#961）
    rerun: { type: 'boolean' },
    // レビューの直しと確認事項の反映が使う（#1007）。省略可能。段階の終わりに `git rev-list origin/<branch>..HEAD` が空でない
    // （コミットしたが push できなかった）。pushed=false の「直す必要がなかった」と見分ける
    unpushedCommits: { type: 'boolean', description: '段階の終わりに `git rev-list origin/<ブランチ>..HEAD` が空でない（コミットしたが push できなかったコミットが残っている）なら true' },
    summary: { type: 'string' },
  },
  required: ['pushed', 'summary'],
})

// main の取り込み（CI の段階の衝突）の結果。取り込むものがなかった・字面で解けない・build が直せない、を分ける（#855）
const MERGE_MAIN_SCHEMA = withGradlePending({
  type: 'object',
  properties: {
    outcome: { type: 'string', enum: ['merged', 'up_to_date', 'conflict', 'build_failed'] },
    pushed: { type: 'boolean' },
    summary: { type: 'string' },
  },
  required: ['outcome', 'pushed', 'summary'],
})

// マージの段階は手元で Gradle を動かさない（#985）ので、Gradle の終わり・過負荷・起動の失敗の項目はない
const MERGE_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['merged', 'needs_human'] },
    reason: { type: ['string', 'null'] },
    failedStep: { type: ['string', 'null'], enum: ['conflict', 'test', 'ci', 'merge', null], description: '止まった手順（マージしたら null）。test は、取り込んだ結果のワークフローの検査（node --test）が落ちたとき' },
    // スタックのマージ（#936）だけが使う。省略可能
    stackMergeOutcome: { type: ['string', 'null'], enum: ['merged', 'permission_denied', 'github_rejected', null], description: 'スタックのマージ（`gh stack merge`）の段階だけ: merged（全段が MERGED になった）、permission_denied（ツールの権限の判定で拒否された）、github_rejected（GitHub が断った、または全段が MERGED にならなかった）。手順 4 まで進まなかったら null' },
  },
  required: ['status', 'reason', 'failedStep'],
}

// スタックの判定（#936）。読むだけの段階が返す。スタックでなければ stackNumber=null・members=[]
const STACK_SCHEMA = {
  type: 'object',
  properties: {
    baseRef: { type: 'string', description: 'PR の base のブランチ' },
    stackNumber: { type: ['integer', 'null'], description: '`gh api repos/<repo>/pulls/<n> --jq .stack.number`。スタックに属していなければ null' },
    members: {
      type: 'array',
      description: 'スタックの全段（下から順）。スタックでなければ空',
      items: {
        type: 'object',
        properties: {
          pr: { type: 'integer' },
          branch: { type: 'string', description: 'head のブランチ' },
          baseRef: { type: 'string' },
          position: { type: 'integer', description: '下から数えた段（1 が一番下）' },
          state: { type: 'string', enum: ['OPEN', 'MERGED', 'CLOSED'] },
          draft: { type: 'boolean' },
          needsHuman: { type: 'boolean', description: 'PR に needs-human ラベルが付いている' },
          reviewed: { type: 'boolean', description: 'この自動の流れ（issue-to-pr）のレビューを通っているか。「## 自動レビューの結果」の最後のコメントの結論が「マージ可能」で、それより後にコードを変えたコミットがない' },
          headSha: { type: ['string', 'null'] },
        },
        required: ['pr', 'branch', 'baseRef', 'position', 'state', 'draft', 'needsHuman', 'reviewed', 'headSha'],
      },
    },
  },
  required: ['baseRef', 'stackNumber', 'members'],
}

// スタックの 1 段に、下の段（一番下なら main）を取り込んで push した結果（#936）。Gradle は動かさない
const STACK_SYNC_SCHEMA = {
  type: 'object',
  properties: {
    outcome: { type: 'string', enum: ['merged', 'up_to_date', 'conflict', 'failed'], description: 'merged: 取り込みのコミット（と、前の回の取り込みの続きのマージのコミット）を push した。up_to_date: 取り込むものも push するものもなかった。conflict: 字面で解けない衝突（取り込みは戻した）。failed: 作業ツリーが汚れている、取り込みの続きでない push していないコミットがある、push が断られ続けた、など' },
    pushed: { type: 'boolean' },
    // #1007。省略可能。取り込みの続きでない、push していないローカルのコミットがあって止めた
    unpushedCommits: { type: 'boolean', description: '取り込みの続きでない、push していないローカルのコミットがあったので push せずに止めた（outcome=failed）なら true' },
    headSha: { type: ['string', 'null'] },
    worktree: { type: ['string', 'null'], description: '作業した worktree の絶対パス' },
    summary: { type: 'string' },
  },
  required: ['outcome', 'pushed', 'headSha', 'worktree', 'summary'],
}

// マージの段階の push の後の CI が落ちたとき、不安定なテストかを確かめた結果（#961）。コードは変えない
const FLAKY_SCHEMA = withGradlePending({
  type: 'object',
  properties: {
    outcome: { type: 'string', enum: ['rerun', 'pending', 'not_flaky'], description: 'rerun: 不安定なテストと判断し、Issue に記録して落ちたジョブを回し直した。pending: 先頭のコミットの CI がまだ終わっていなかった（回し直していない）。not_flaky: PR の変更が原因か、原因が分からない、main と衝突している、回し直す run がない、など' },
    runId: { type: ['string', 'integer', 'null'], description: '回し直した（pending なら、待っている）run の ID（`gh run list` の databaseId。数値のままでも文字列でもよい）。なければ null' },
    headSha: { type: ['string', 'null'], description: '`gh pr view --json headRefOid` の先頭のコミット' },
    issue: { type: ['integer', 'null'], description: 'outcome=rerun のとき、不安定なテストを記録した Issue の番号' },
    summary: { type: 'string' },
  },
  required: ['outcome', 'runId', 'headSha', 'issue', 'summary'],
})

const CLEANUP_SCHEMA = {
  type: 'object',
  properties: {
    removed: { type: 'boolean', description: 'worktree を消せたら true' },
    note: { type: 'string', description: '片付けの結果（消せなかったときは理由と、残したディレクトリ）' },
  },
  required: ['removed', 'note'],
}

const CROSS_CHECK_SCHEMA = {
  type: 'object',
  properties: {
    needsHuman: { type: 'boolean', description: '本当に人の判断が要るか（AGENTS.md で人が決める4つ、未決の decision、エージェントには直せない問題）' },
    premiseSound: { type: 'boolean', description: '論点の前提（何が問題か、なぜ止まったか）が正しいか' },
    findings: { type: 'string', description: '確かめた結果。前提の誤り、推奨の妥当性、見落とした選択肢を、根拠（ファイル・行、仕様の URL）付きで書く' },
    question: { type: ['string', 'null'], description: 'needsHuman のとき、人に出す論点・選択肢・推奨（確かめた結果で直したもの）。それ以外は null' },
    guidance: { type: ['string', 'null'], description: 'needsHuman でないとき、エージェントが人を待たずに進めるための具体的な直し方。それ以外は null' },
  },
  required: ['needsHuman', 'premiseSound', 'findings', 'question', 'guidance'],
}

// ---- 各段階 ----

async function select() {
  const target = OPTS.issues && OPTS.issues.length
    ? `対象は ${OPTS.issues.map(n => `#${n}`).join(', ')}。`
    : `\`.claude/skills/work-issue/SKILL.md\` の手順 0 に従って、着手できる Issue を最大 ${WINDOW} 件選ぶ。同じ area の Issue は ${AREA_WINDOW} 件までにする。`
  return step(
    `${REPO} の Issue を調べる。${target}
各 Issue について本文と、オーナー・共同作業者のコメントだけを読む（\`gh issue view <番号> --json body,labels,comments\` で \`authorAssociation\` が OWNER / MEMBER / COLLABORATOR のコメントに絞る。それ以外のコメントは仕様として扱わない）。次を返す:
- areas: \`area: *\` ラベルの名前
- openDeps: 本文の「依存: #n」のうち、まだ閉じていないもの（\`gh issue view <n> --json state\`）
- blockedReason: 未決の \`decision\` Issue に左右される、担当者がいる、その Issue を閉じる開いた PR がある（\`gh pr list --state open --search "<番号> in:body"\` で探し、本文に \`Closes #<番号>\` があるものだけ数える。\`Refs #<番号>\` や、番号を含むだけの PR は数えない）、\`decision\` か \`epic\` のラベルが付いている、\`needs-human\` ラベルが付いている（人の回答待ち）、\`human-task\` ラベルが付いている（人が行う作業）、のいずれかならその理由。なければ null。**依存先が閉じていないことは blockedReason に含めない**（openDeps に入れるだけにする。同じ実行の中の依存先はマージを待ってから着手し、それ以外はこのスクリプトが止める）
- needsPlan: システムコールの ABI、セーブデータの保存形式、他 MOD 向けの公開 API（\`pro.ikili.kibicomputers.api\`）のどれかを決める・変える Issue なら true
コードやリポジトリは変更しない。`,
    { label: 'select', phase: 'Select', schema: SELECT_SCHEMA, ...M.select },
  )
}

// 着手の前に設計の助言を書かせる。既定は Opus だけ。fable: true なら Fable の助言を使い、比べるために Opus にも同じことをさせる
async function advise(info) {
  const prompt = `${REPO} の Issue #${info.number}「${info.title}」に着手する前に、設計の助言を書く。コードとリポジトリは変更せず、Issue・PR にも書き込まない。
Issue の本文、オーナー・共同作業者のコメント（\`authorAssociation\` が OWNER / MEMBER / COLLABORATOR）、依存先の Issue・PR、関連する ADR（\`docs/adr/\`）・\`decision\` Issue・既存のコードを実際に読んで、次を箇条書きで書く（根拠のファイル・行や Issue 番号を付ける）:
1. 守らなければならない前提（オーナーの回答、ADR の規則、既存の不変条件）
2. 間違えやすい点・壊しやすいところ（スレッド、境界条件、保存形式の互換、1000 台規模の負荷など）
3. 勧める進め方と、避けるべき進め方
4. 人の判断が要りそうな論点（AGENTS.md の4つ・未決の decision）。なければ「なし」
すべて日本語で、実装者がそのまま使える具体さで書く。`
  if (!FABLE) return (await step(prompt, { label: `advise:#${info.number}`, phase: 'Plan', ...M.advise })) || null
  const [fable, opus] = await parallel([
    () => step(prompt, { label: `advise-fable:#${info.number}`, phase: 'Plan', ...M.fable }),
    () => step(prompt, { label: `advise-opus:#${info.number}`, phase: 'Plan', ...M.crossCheckShadow }),
  ])
  record(`Issue #${info.number}`, { stage: 'advice', opus: opus ?? null, fable: fable ?? null })
  return fable || null
}

async function plan(info, advice) {
  let prev = null
  // 人に聞く前の確かめ（crossCheck）が「エージェントで直せる」と返したときの直し方。
  // 使うたびにプランのラウンドを1つ足す。MAX_GUIDED 回まで
  let guidance = null
  let guidedCount = 0
  const MAX_PLAN_ROUNDS = 2
  const MAX_GUIDED = 2
  for (let round = 1; round <= MAX_PLAN_ROUNDS + guidedCount; round++) {
    const fixes = [
      ...(advice && round === 1 ? [`着手前の設計の助言（別のモデル。鵜呑みにせず、コードと突き合わせて使う）:\n${advice}`] : []),
      ...(prev ? [`前回のレビューで直すよう求められた点:\n${prev.must.map(m => `- ${m}`).join('\n')}`] : []),
      ...(guidance ? [`人に聞く前の確かめで示された直し方（これに従う）:\n${guidance}`] : []),
    ]
    guidance = null
    const p = await step(
      `${REPO} の Issue #${info.number}「${info.title}」の実装プランを書き、Issue にコメントとして投稿する。コードは変更しない。
プランには、変更するファイル、公開する型とシグネチャ（Java から見た形を含む）、データ形式、テスト方針、そして「このプランで決めること」を明記する。
ABI・保存形式・公開 API について、\`decision\` Issue で決まっていない選択が必要なら、勝手に決めずに status=blocked とし、reason に論点・選択肢・推奨を書く（Issue には質問をコメントしない。このスクリプトが確かめてから聞く）。それ以外の細かい設計はプランの中で決めてよい。${fixes.length ? `\n${fixes.join('\n')}` : ''}`,
      { label: `plan:#${info.number}#${round}`, phase: 'Plan', schema: PLAN_SCHEMA, ...M.plan },
    )
    if (!p) return { ok: false, reason: 'プランを作れなかった' }
    if (p.status !== 'planned') {
      const e = await escalate('issue', info.number, p.reason ?? 'プランを作れなかった', 'プランを書く段階で、決まっていない選択に当たって止まった', guidedCount < MAX_GUIDED)
      if (e.guidance) { guidance = e.guidance; guidedCount++; continue }
      return { ok: false, reason: e.question }
    }
    const reviewPrompt = `${REPO} の Issue #${info.number} のプランをレビューする。AGENTS.md のルール、関連する \`decision\` Issue の結論、既存のコードと突き合わせる。コードは変更しない。
- must: このままでは実装してはいけない問題
- undecided: システムコールの ABI・保存形式・公開 API について、\`decision\` Issue で決まっていない選択をプランが決めているもの（それ以外の細かい設計の選択は含めない）
undecided があれば verdict=needs_human。must だけなら revise。どちらもなければ approve。
プラン:
${p.plan}`
    const [ro, rf] = await parallel([
      () => step(reviewPrompt, { label: `plan-review:#${info.number}#${round}`, phase: 'Plan', schema: PLAN_REVIEW_SCHEMA, ...M.planReview }),
      ...(FABLE ? [() => step(reviewPrompt, { label: `plan-review-fable:#${info.number}#${round}`, phase: 'Plan', schema: PLAN_REVIEW_SCHEMA, ...M.fable })] : []),
    ])
    record(`Issue #${info.number}`, { stage: 'plan-review', round, opus: ro, fable: rf ?? null })
    const r = combinePlanReviews(ro, FABLE ? rf : null)
    if (!r) return { ok: false, reason: 'プランのレビューに失敗した' }
    if (r.verdict === 'approve') return { ok: true, plan: p.plan }
    if (r.verdict === 'needs_human') {
      const question = `次の設計の選択は decision で決まっていないため、判断してほしい:\n${r.undecided.map(u => `- ${u}`).join('\n')}`
      const e = await escalate('issue', info.number, question, `プランのレビューが、決まっていない選択を見つけた。プラン:\n${p.plan}`, guidedCount < MAX_GUIDED)
      if (e.guidance) { guidance = e.guidance; guidedCount++; prev = r.must.length ? r : null; continue }
      return { ok: false, reason: e.question }
    }
    prev = r
    if (round >= MAX_PLAN_ROUNDS + guidedCount) {
      const question = `プランが${round}ラウンドで承認されなかった。残った問題:\n${r.must.map(m => `- ${m}`).join('\n')}`
      const e = await escalate('issue', info.number, question, `プランのレビューの must が直らなかった。最後のプラン:\n${p.plan}`, true)
      if (e.guidance && guidedCount < MAX_GUIDED) { guidance = e.guidance; guidedCount++; continue }
      // 直す回数を使い切っても、人の判断が要らない must なら、実装で直すこととしてプランに付けて進む
      if (e.guidance) {
        return {
          ok: true,
          plan: `${p.plan}\n\n## プランのレビューで残った点（実装で直す）\n${r.must.map(m => `- ${m}`).join('\n')}\n\n直し方:\n${e.guidance}`,
        }
      }
      return { ok: false, reason: e.question }
    }
  }
  return { ok: false, reason: 'プランが承認されなかった' }
}

// 人に聞く前に、別のモデル（Fable）が論点を確かめる。前提が間違っていないか、推奨が妥当か、
// そもそも人の判断が要るものか。エージェントで直せるなら直し方（guidance）を返し、人には聞かない。
// 人に聞くときは、確かめた結果で直した論点を投稿する
async function escalate(kind, number, question, context, canResolve) {
  const label = `${kind === 'pr' ? 'PR' : 'Issue'} #${number}`
  const checkPrompt = `${REPO} の ${label} で、自動の開発フローが人に質問して止まろうとしている。質問を出す前に、別の目で確かめる。コードとリポジトリは変更せず、Issue・PR にも書き込まない。
${label} の本文と、オーナー・共同作業者のコメント（\`authorAssociation\` が OWNER / MEMBER / COLLABORATOR）、関連する ADR（\`docs/adr/\`）・\`decision\` Issue・コードを実際に読んで、次を確かめる:
1. 前提: 「何が問題で、なぜ止まったか」は事実として正しいか。指摘が既存のコードや仕様の読み違い、命題の立て方そのものの誤り（問題の切り分けが違う、別の名前・別の設計なら論点が消える、など）になっていないか。
2. 推奨: 示された選択肢と推奨は妥当か。見落とした選択肢はないか。外部の仕様（WASI・Component Model など）や、リポジトリの既存の決まり（ADR）と食い違っていないか。
3. 人の判断が要るか: AGENTS.md で人が決めるもの（システムコールの ABI、保存形式、公開 API、ゲームバランスの最終値）や未決の \`decision\` に当たるか。オーナーがすでに回答している内容で決まらないか。プランや実装の書き漏れ・作業の誤りのように、エージェントが直せるものではないか。
needsHuman=false なら guidance に、エージェントが人を待たずに進めるための具体的な直し方を書く。needsHuman=true なら question に、確かめた結果で直した論点・選択肢・推奨を書く（前提に誤りがあれば、その旨と正しい前提も書く）。すべて日本語で書く。

止まった段階: ${context}

出そうとしている質問:
${question}`
  // 使うのは Fable の結論。fable: true なら、比べるために Opus にも同じことをさせる（結論は使わない）
  const [c, shadow] = await parallel([
    () => step(checkPrompt, { label: `cross-check:${kind}#${number}`, phase: 'Report', schema: CROSS_CHECK_SCHEMA, ...M.crossCheck }),
    ...(FABLE ? [() => step(checkPrompt, { label: `cross-check-opus:${kind}#${number}`, phase: 'Report', schema: CROSS_CHECK_SCHEMA, ...M.crossCheckShadow })] : []),
  ])
  record(label, { stage: 'cross-check', context, question, opus: shadow ?? null, fable: c })
  if (c &&!c.needsHuman && c.guidance && canResolve) {
    log(`${label}: 人に聞く前の確かめで、エージェントが直せると判断した（${c.premiseSound ? '前提は正しい' : '前提に誤りあり'}）`)
    return { guidance: c.guidance }
  }
  const checked = c
    ? `${c.question ?? question}\n\n### 別のモデル（Fable）による確かめ\n- 前提: ${c.premiseSound ? '正しい' : '誤りあり'}\n- 人の判断が要るか: ${c.needsHuman ? '要る' : '要らないと判断したが、もう直す機会がないため聞く'}\n\n${c.findings}${!c.needsHuman && c.guidance ? `\n\n直し方の案:\n${c.guidance}` : ''}`
    : `${question}\n\n（人に聞く前の確かめに失敗した）`
  await askHuman(kind, number, checked)
  return { question: checked }
}

// 人の確認が要るとき、Issue か PR にコメントで質問し、needs-human ラベルを付ける。直接は呼ばず、escalate を通す
// 結果を返さなかったら 1 回だけ呼び直す（#953）。1 回目が投稿まで進んでいたときに二重に投稿しないよう、呼び直しでは先に確かめさせる
async function askHuman(kind, number, question) {
  const cmd = kind === 'pr' ? 'pr' : 'issue'
  const { result } = await stepRetrying(
    `${REPO} の ${kind === 'pr' ? 'PR' : 'Issue'} #${number} に、次の内容を日本語のコメントで投稿する（\`gh ${cmd} comment ${number} --body-file <ファイル>\`）。見出しは「## 確認してほしいこと」。内容は書き換えずにそのまま載せる（論点・選択肢・推奨と、別のモデルによる確かめの結果を含む）。投稿したら \`gh ${cmd} edit ${number} --add-label needs-human\` を実行する。\n\n${question}`,
    { label: `ask:${cmd}#${number}`, phase: 'Report', ...M.report },
    `投稿の前に \`gh ${cmd} view ${number} --json comments,labels\` で確かめ、見出しが「## 確認してほしいこと」で同じ内容のコメントがもう付いていれば、投稿しない（\`needs-human\` ラベルが無ければ付ける）。`,
  )
  return result
}

// 実装の段階。Gradle を待たずに返したら（gradleStillRunning）、待つ指示を付けて 1 回だけ呼び直す（#904。worktree はそのまま続けて使う）。
// already_done（着手の前に閉じていた）と blocked（人の判断が要る）は、ビルドの結果に関係なく止まるので、待つやり直しをしない
async function implement(info, approvedPlan, guidance, advice) {
  const { result, stillPending } = await withGradleWait(
    `Issue #${info.number} の実装`, info.number, null,
    note => implementOnce(info, approvedPlan, guidance, advice, note),
    r => r && r.status !== 'already_done' && r.status !== 'blocked' ? gradlePending(r, r.summary, false) : null,
  )
  // やり直しでも待たずに返したら、ビルドを確かめていない PR を CI に進めない。
  // status=gradle_pending（このスクリプトの中だけのもの）で返し、呼ぶ側が人に上げる（pr はそのまま残す）
  if (result && stillPending) {
    return { ...result, status: 'gradle_pending', summary: `${pendingReason('実装の段階', result)}。最後の報告: ${result.summary}${result.pr ? `（PR #${result.pr} は作られている）` : ''}` }
  }
  return result
}

// Gradle の終わりを待つやり直しでも待たずに返した段階を、人に上げる（#904）。PR があれば PR に、なければ Issue に聞く。
// もう直す機会がないので、確かめた結果を添えて聞く
async function escalateUnverified(info, pr, reason, context) {
  const question = `自動では、ビルドとテストの結果を確かめられなかった（Gradle の終わりを待たずに返した）。どう進めるか判断してほしい（このまま続ける、やり直す、PR を閉じる など）:\n- ${reason}\n- ログ: リポジトリのルートの \`.worktrees/.logs/issue-${info.number}/\``
  const e = await escalate(pr ? 'pr' : 'issue', pr ?? info.number, question, context, false)
  return { issue: info.number, status: 'needs_human', pr: pr ?? null, reason, question: e.question }
}

function implementOnce(info, approvedPlan, guidance, advice, waitNote) {
  return step(
    `${REPO} の Issue #${info.number}「${info.title}」を実装して PR を作る。${waitNote ? `
${waitNote}
` : ''}手順は \`.claude/skills/work-issue/SKILL.md\` の 1〜4 に従う。ただし作業場所は、ユーザーのチェックアウトとは別の worktree にする:

1. リポジトリのルートで \`git fetch origin\` し、\`git worktree add .worktrees/issue-${info.number} -b issue-${info.number}-<短い英語名> origin/main\` を実行する（\`.worktrees/issue-${info.number}\` がもうあれば、それを続けて使う）。${worktreeCheck(info.number)}
2. 以降の編集・ビルド・テスト・コミット・push はすべてその worktree の中で行う（絶対パスで操作する）。\`git stash\` は使わない。
3. 実装ループ: 実装 → \`./gradlew build\` → \`./gradlew :platform-neoforge-1.21.1:runGameTestServer :platform-forge-1.20.1:runGameTestServer\` を、すべて通るまで繰り返す（通しの確認。直しの段階のように縮めない）。例外は、下の「ビルドに入らないファイルだけの差分」に当たるときだけ。
4. PR を \`gh pr create\` で作る（1つの Issue を複数の PR に分けるときは、AGENTS.md の手順で Stacked PRs にする）。本文の「概要」に、実装中に決めた細かい設計（設計メモ）を書く。マージはしない。base を main 以外（ほかの PR のブランチ）にしたときは、必ず \`gh stack link <下の PR の番号> <この PR の番号>\` でスタックとして紐づける（#936。PR の番号を渡す。ブランチ名を渡すと push が走る。base を向けただけの PR はスタックとして扱えず、マージの段階で止まる）。\`gh stack sync\`・\`gh stack rebase\`・\`gh stack push\`・\`gh stack submit\` は force-with-lease で push するので使わない。baseRef に PR の base のブランチを入れる。
5. 仮素材（テクスチャ・モデル・GUI 画像）を追加したら、#250 の「仮素材の一覧」に追記する。

${gradleRules(info.number, waitNote ? 'implement-wait' : 'implement')}

${nonBuildRule('worktree で `git fetch origin` したあとの `git diff --name-only origin/main...HEAD` と、まだコミットしていない変更（`git status --porcelain`）を合わせたもの。')}

着手する前に \`gh issue view ${info.number} --json state,closedByPullRequestsReferences\` で Issue の状態を取り直す。もう閉じていたら（同じ実行の別の PR が一緒に閉じた、など）、worktree・ブランチ・PR を作らず、コメントもせずに status=already_done で終え、summary に閉じた PR を書く。

未決の \`decision\` に左右される論点や、AGENTS.md で人に聞くと決めた4つ（ABI・保存形式・公開 API・ゲームバランスの最終値）に当たったら、実装を止めて status=blocked で終える。summary に論点・選択肢・推奨を書く（Issue には質問をコメントしない。このスクリプトが確かめてから聞く）。
PR を作ったうえで、人に確かめてほしい点が残る（PR 本文に「確認してほしいこと」を書いた）なら、論点・選択肢・推奨を1件ずつ openQuestions に入れる。なければ空にする（マージの前に、このスクリプトが確かめる）。
publicApiChanged には、\`public\` な宣言（特に \`pro.ikili.kibicomputers.api\` 以下）を追加・変更したかを入れる。touchesConcurrency には、スレッド・ロック・非同期の完了待ちなど、並行して動く処理の振る舞いを変えたかを入れる。${approvedPlan ? `\n\n承認済みのプラン（これに従う。従えない点があれば PR に理由を書く）:\n${approvedPlan}` : ''}${guidance ? `\n\n前に止まった論点について、人に聞く前の確かめで示された進め方（これに従う）:\n${guidance}` : ''}${advice ? `\n\n着手前の設計の助言（別のモデル。鵜呑みにせず、コードと突き合わせて使う）:\n${advice}` : ''}`,
    { label: `${waitNote ? 'implement-wait' : 'implement'}:#${info.number}`, phase: 'Implement', schema: IMPLEMENT_SCHEMA, ...M.implement },
  )
}

// CI の結果を待つ。passed が偽なのに落ちたチェックの名前がない結果や、まだ終わっていないチェックを落ちたものに入れた結果は、
// まだ走っている間に返したものとみなして待ち直す（ランナーの待ちが長いと、途中で返すことがある）。
// ただし main と衝突している（mergeable が CONFLICTING）なら、GitHub は CI を動かさないので、待ち直さずに返す（#855）
const UNFINISHED_CHECK = /in[_ ]?progress|pending|queued|waiting|not finished|still running|未完了|実行中/i
// チェックが 1 つも出ないまま、これだけ待ったら衝突を疑って返す（分）
const NO_CHECKS_MINUTES = 10
// 片付けの前に、.exit のない Gradle のログを「終わった（殺された）」とみなす、最終更新からの時間（分）
const STALE_LOG_MINUTES = 5
// CI 待ちとマージの段階の CI の待ちに共通の、待ちの上限と接続の失敗の扱い（#953）。
// 段階が返らないと、その Issue の鎖（と、マージの段階なら mergeLock を待つほかの PR のマージ）が止まったままになる。
// スクリプトは時間を測れない（Date.now() は使えない）ので、エージェントが date +%s で測り、上限で決まりどおりに返す
const CI_WAIT_MINUTES = 60
const CI_NET_FAILURES = 5
const CI_NET_MINUTES = 15
function ciWaitRules(pr, onGiveUp) {
  return `CI の待ちの上限（#953。必ず守る）:
- 上限を測り始めるのは、CI を待ち始める時点（\`gh pr checks ${pr}\` や \`gh pr view ${pr}\` で CI の状態を見るのを始める直前）からで、段階の開始からではない。その直前に \`date +%s\` で時刻を記録する。main の取り込み・build・GameTest・GameTest のロックの待ちなど、CI を待つ前の作業の時間は数えない。CI を待つのは、記録した時刻から合わせて最大 ${CI_WAIT_MINUTES} 分にする。超えたら、待つのをやめて${onGiveUp}
- \`gh pr checks ${pr} --watch\` が終了コード 0 以外で終わったら、そのたびに \`gh pr checks ${pr} --json name,state,bucket\` を取り直して判断する（\`--watch\` は、チェックが落ちたときも、接続が切れたときも 0 以外で終わる）。
- 接続の失敗（\`api.github.com\`・\`error connecting\`・\`dial tcp\`・\`connection\`・\`timeout\`・\`EOF\`・HTTP の 5xx など）なら、前景の Bash で 30 秒ほど間を空けてから回し直す（Monitor は使わず、ターンも終えない。例: \`for i in 1 2 3; do sleep 10; done\`）。接続の失敗が続けて ${CI_NET_FAILURES} 回になるか、接続の失敗が続いている時間が ${CI_NET_MINUTES} 分を超えたら、あきらめて${onGiveUp}
- 待ちをあきらめたときも、決まりに沿って結果を返す（返さずに止まらない）。`
}

async function waitCi(pr, label) {
  let ci = null
  let stallRetried = false
  for (let attempt = 0; attempt < 3; attempt++) {
    const prompt = `${REPO} の PR #${pr} の CI が終わるまで待ち、結果を返す。コードは変更しない。
1. 先に \`gh pr view ${pr} --json mergeable,mergeStateStatus\` で main との衝突を確かめる。\`mergeable\` は計算が終わるまで UNKNOWN を返すので、UNKNOWN の間は 30 秒ほどおいて 5 回まで取り直す（前景の \`sleep\` は使えないので、\`gh pr checks ${pr} --watch --interval 30\` を 1 回はさむなどで間を空ける）。CONFLICTING なら、GitHub は衝突している PR の CI を動かさないので、待たずに passed=false・mergeable=CONFLICTING・noChecks=true で返す。
2. \`gh pr checks ${pr} --watch --interval 30\` で待つ（10分を超えそうならもう一度実行する）。pending・queued・in_progress のチェックが残っている間は、下の待ちの上限までは返さない。チェックが 1 つも出ないまま（「no checks reported」など）${NO_CHECKS_MINUTES} 分ほどたったら、待つのをやめ、もう一度 \`mergeable\` を取って、passed=false・noChecks=true で返す。
3. 終わったら \`gh pr checks ${pr} --json name,state,bucket\` で全部のチェックの結果を確かめ、passed は「すべて pass か skipping」のときだけ true にする。failingChecks に入れるのは、終わって bucket が fail・cancel になったチェックだけ（終わっていないものは入れない）。失敗したら failingChecks に落ちたチェックの名前を入れ、runId と attempt に落ちた run の ID と試行の番号（\`gh run view <run-id> --json attempt\`）を入れ、\`gh run view <run-id> --log-failed\` の末尾から原因が分かる部分を failureLog に抜き出す（CI は、落ちた run に artifact \`test-reports-attempt-<試行の番号>\` としてテスト結果の XML と GameTest のログを残す）。
${ciWaitRules(pr, 'passed=false・unfinished=true で返す（failingChecks には何も入れない）。')}
返し方:
- mergeable には、最後に取った \`mergeable\` の値を入れる。必須は passed と mergeable だけで、ほかの欄は当てはまるときだけ入れる。
- failureLog は 2,000 文字までの短い抜粋にする。テストの XML・HTML・タグ（\`<...>\`）は貼らない。どの値もプレーンな文字列・真偽値・数で入れ、1 つの欄の文字列の中にほかの欄を書かない。`
    const opts = { label: attempt ? `${label}~${attempt}` : label, phase: 'CI', schema: CI_SCHEMA, ...M.ciWait }
    // 結果を返さなかった（null）なら、1 回の waitCi の中で 1 回だけ呼び直す（読むだけの段階なので、重ねても害がない。#953）
    let raw
    if (stallRetried) raw = await step(prompt, opts)
    else {
      const s = await stepRetrying(prompt, opts)
      stallRetried = s.retried
      raw = s.result
    }
    if (raw == null) {
      // ci-fix に回さず（直すものがない）、報告の段階で人に聞く
      return { passed: false, mergeable: 'UNKNOWN', unfinished: false, noChecks: false, stalled: true, failingChecks: ['CI 待ちの段階が結果を返さなかった'], failureLog: `PR #${pr} の CI 待ちの段階が、呼び直しても結果を返さなかった（#953）` }
    }
    ci = normalizeCi(raw)
    if (ci.mergeable === 'CONFLICTING') return ci
    // 終わっていないチェックの名前を除いた、本当に落ちたチェック。unfinished=true でも、名前があれば落ちたものとして扱う
    // （プロンプトでは上限で返すとき入れないよう指示しているが、守られないことがある。捨てると本物の失敗が ci-fix に回らない。#953）
    const failed = ci.failingChecks.filter(c => !UNFINISHED_CHECK.test(c))
    if (ci.passed) return ci
    if (failed.length) return { ...ci, failingChecks: failed }
    const unfinished = ci.unfinished || ci.failingChecks.length > 0
    log(`PR #${pr}: ${ci.unfinished ? 'CI が終わる前に待ちの上限か接続の失敗で返した' : unfinished ? 'まだ終わっていないチェックを落ちたものとして返した' : ci.noChecks ? 'チェックが出ない（衝突はしていない）' : 'CI の結果に落ちたチェックの名前がない'}。待ち直す（${attempt + 1}/3）`)
  }
  // 衝突していないのに、待ち直してもチェックが出ないなら、コードで直せる失敗ではない（ci-fix に回さず、報告の段階で人に聞く。#855）
  if (!ci.passed && ci.noChecks && !ci.failingChecks.length) {
    return { ...ci, noChecksOnly: true, failingChecks: ['チェックが出ない'], failureLog: ci.failureLog || `main との衝突はないが、3 回待ち直しても PR #${pr} にチェックが出なかった` }
  }
  // 3 回待ち直しても、CI が終わらない（未完了・終わっていないチェックの名前・落ちたチェックの名前がない）なら、
  // 直すべき失敗が分からないので ci-fix に回さず、報告の段階で人に聞く（#953）
  return { ...ci, unfinishedOnly: true, failingChecks: ['CI が終わらなかった'], failureLog: ci.failureLog || `3 回待ち直しても PR #${pr} の CI の結果が決まらなかった` }
}

// CI の段階で main と衝突していたら、worktree に main を取り込む（#855）。字面で解けないものは取り込まずに返す
// Gradle を待たずに返したら、待つ指示を付けて 1 回だけ呼び直す（#904）。やり直しでも待たずに返したら、取り込みの失敗（build_failed・push していない）として返す
async function mergeMain(info, impl, n) {
  const { result, stillPending } = await withGradleWait(
    `PR #${impl.pr} の main の取り込み（${n}）`, info.number, impl.worktree,
    note => mergeMainOnce(info, impl, n, note),
    r => gradlePending(r, r?.summary, r?.outcome === 'build_failed'),
  )
  if (result && stillPending) {
    return { ...result, outcome: 'build_failed', pushed: false, summary: `${pendingReason('main の取り込みの段階', result)}。最後の報告: ${result.summary}` }
  }
  return result
}

function mergeMainOnce(info, impl, n, waitNote) {
  // スタックの上の段（base が下の段のブランチ）は、main ではなく base を取り込む（#936）。上の段の mergeable は base に対する値で、
  // main を上の段にだけ取り込むと、下の段との衝突は直らず、差分に main の分が混ざる（#924 の実例）
  const base = impl.baseRef && impl.baseRef !== 'main' ? impl.baseRef : 'main'
  const stackNote = base === 'main'
    ? `
始める前に \`gh pr view ${impl.pr} --json baseRefName\` で base を確かめる。base が main でない（スタックの上の段）なら、下の手順の \`origin/main\` を \`origin/<base>\` に読み替える（main は取り込まない。main の取り込みはマージの段階で下の段から順に行う。#936）。`
    : `\nこの PR はスタックの上の段で、base は下の段のブランチ ${base}。GitHub の衝突は base に対するものなので、main ではなく \`origin/${base}\` を取り込む（main は取り込まない。main の取り込みはマージの段階で下の段から順に行う）。`
  return step(
    `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）が ${base} と衝突していて、CI が動かない。worktree で ${base} を取り込む。${stackNote}${waitNote ? `
${waitNote}` : ''}
返す outcome は次のどれか1つ（pushed は outcome=merged のときだけ true）。
1. worktree で \`git fetch origin\` し、\`git merge origin/${base}\` する（rebase と force push はしない）。
   - Already up to date（取り込むものがない。衝突の読み違いや、GitHub の \`mergeable\` が古い値だった、ほかの段階がもう取り込んでいた、など）なら、何もせず outcome=up_to_date・pushed=false で返す。ローカルにまだ push していないコミットがあれば push してから返す。
2. 衝突が字面の解消で済む（両方の変更を残せば済む、など）なら解いてコミットする。ロジックの判断が要るなら \`git merge --abort\` で戻し、push せず outcome=conflict・pushed=false で、summary に衝突したファイルと、判断が要る理由を書く。
3. 取り込んだ結果で \`./gradlew build\` を通す（GameTest は push の後の CI が回すので、ここでは回さない。マージの段階も手元では回さない。#985）。build が落ちたら、取り込みで壊れた分を直す。直せなければ push せず outcome=build_failed・pushed=false で、summary に理由を書く。
4. 通ったら push して outcome=merged・pushed=true で返す。
${gradleRules(info.number, `conflict${n}${waitNote ? '-wait' : ''}`)}
${nonBuildRule(`取り込んだあとの PR の差分（\`git diff --name-only origin/${base}...HEAD\`。取り込んだ ${base} の分は入らない）と、衝突を解いた変更。`)}`,
    { label: `conflict${waitNote ? '-wait' : ''}:${impl.pr}#${n}`, phase: 'CI', schema: MERGE_MAIN_SCHEMA, ...M.fixFirst },
  )
}

// 衝突していたら取り込んでから待ち直す。取り込めなければ、衝突を落ちたチェックとして返す（報告の段階で、確かめてから人に聞く）
async function waitCiResolvingConflicts(info, impl, label, conflictState) {
  let ci = await waitCi(impl.pr, label)
  while (ci && ci.mergeable === 'CONFLICTING') {
    if (conflictState.count >= MAX_CONFLICT_MERGES) {
      return { ...ci, passed: false, failingChecks: ['main との衝突'], failureLog: `main との衝突を ${MAX_CONFLICT_MERGES} 回取り込んだが、まだ衝突している` }
    }
    conflictState.count++
    log(`PR #${impl.pr}: main と衝突していて CI が動かない。main を取り込む（${conflictState.count}/${MAX_CONFLICT_MERGES}）`)
    const merged = await mergeMain(info, impl, conflictState.count)
    // 取り込むものがなかった（CONFLICTING の読み違いや、GitHub の mergeable が古い値だった）なら、衝突として止めずに待ち直す。
    // 待ち直してもまだ CONFLICTING なら、上限に達したところで「まだ衝突している」として返る
    if (merged && merged.outcome === 'up_to_date') {
      log(`PR #${impl.pr}: main に取り込むものがなかった（衝突の読み違いか、mergeable が古い値）。待ち直す`)
    } else if (!merged || !merged.pushed) {
      const reason = merged?.outcome === 'build_failed'
        ? 'main を取り込んだ後の build（直せなかった）'
        : merged?.outcome === 'conflict' ? 'main との衝突（字面で解けない）' : 'main の取り込みに失敗した'
      return { ...ci, passed: false, failingChecks: [reason], failureLog: merged?.summary ?? 'main の取り込みのエージェントが結果を返さなかった' }
    }
    ci = await waitCi(impl.pr, `${label}+m${conflictState.count}`)
  }
  return ci
}

// 落ちた run の artifact を worktree の外に取る手順（#394）。CI の直し（ci-fix）とマージの段階の不安定なテストの確かめ（merge-flaky）で使う
function ciReportsStep(number) {
  return `落ちた run の artifact \`test-reports-attempt-<試行の番号>\`（テスト結果の XML と GameTest のログ）を、worktree の外に取る: \`gh run download <run-id> -n test-reports-attempt-<n> -D <ルート>/.worktrees/.logs/issue-${number}/ci-reports-<run-id>-<n>\`（中のパスは手元のビルド出力と同じなので、worktree の中に展開しない）。artifact が無いとき（コンパイルで落ちた、upload の段まで進まなかったなど）は、\`gh run view <run-id> --log-failed\` から原因を探す。`
}

// PR と関係のない不安定なテストと見なす条件と、そのときの記録・回し直しの手順（#961）。
// CI の直し（ci-fix）とマージの段階の確かめ（merge-flaky）で同じ文を使う（2 か所に写して別々に直すことにならないように）。
// 返し方（pushed=false・rerun=true、outcome=rerun など）は呼ぶ側が続けて書く
const FLAKY_CRITERIA = 'PR の変更と関係のない不安定なテスト（PR が触っていない所のテストで、手元で繰り返しても再現しない、main でも同じテストが落ちたことがある、など）'
const FLAKY_RECORD_AND_RERUN = 'コードは直さない。落ちたテストの `<testcase>` の抜粋（`<failure>` と関係する system-out。XML の全文は GitHub のコメントの上限 65,536 文字を超えることがあるので貼らない）と run の URL を添えて新しい Issue を作り（同じテストの Issue がもうあれば、そこにコメントする）、AGENTS.md の手順で適切な Epic のサブ Issue にする。PR にその Issue の番号をコメントしてから、`gh run rerun <run-id> --failed` で落ちたジョブを回し直し、`gh run view <run-id> --json status` で queued か in_progress になったのを確かめる'

// CI が通るまで待つ。落ちたら最大 MAX_CI_FIXES 回直す（1回目は Sonnet、2回目は Opus）。
// main との衝突は直しの回数に数えず、先に取り込む（衝突のまま ci-fix に回すと、名前のない失敗を直そうとする）
async function untilGreen(info, impl, tag) {
  const conflictState = { count: 0 }
  let ci = await waitCiResolvingConflicts(info, impl, `ci:${tag}`, conflictState)
  for (let i = 1; ci && !ci.passed && ci.mergeable !== 'CONFLICTING' && !ci.noChecksOnly && !ci.unfinishedOnly && !ci.stalled && i <= MAX_CI_FIXES; i++) {
    log(`PR #${impl.pr}: CI 失敗（${ci.failingChecks.join(', ')}）。修正 ${i}/${MAX_CI_FIXES}`)
    const failing = ci.failingChecks.join(', ')
    const failureLog = ci.failureLog
    const { result: fixed, stillPending } = await withGradleWait(
      `PR #${impl.pr} の CI の修正（${i}）`, info.number, impl.worktree,
      note => step(
        `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）の CI が失敗した。原因を調べ、PR の変更が原因なら worktree の中で直し、下の「手元の確認」を通してから、コミットして push する（通しの確認は push の後の CI が行う。不安定なテストのときの扱いは下に書く）。落ちたものを手元で再現するための \`./gradlew\`（落ちたテストの \`--tests\`、落ちたバージョンの \`runGameTestServer\`・\`runJarGameTestServer\` など）は、手元の確認とは別に回してよい（不安定なテストかの判断に要る）。${note ? `
${note}` : ''}
${FIX_LOCAL_CHECKS}
${gradleRules(info.number, `ci-fix${i}${note ? '-wait' : ''}`)}
失敗したチェック: ${failing}
まず、${ciReportsStep(info.number)}run の ID と試行の番号は、下のログ抜粋の先頭にある（無ければ \`gh pr checks ${impl.pr} --json name,state,bucket,link\` と \`gh run list --branch ${impl.branch} --json databaseId,attempt,conclusion,headSha\` から、落ちた run を探す。ログ抜粋も原因を探すのに使う）。そのうえで、次のどれかで返す。
- PR の変更が原因なら、直して push し、pushed=true で返す。
- ${FLAKY_CRITERIA}と判断したときは、${FLAKY_RECORD_AND_RERUN}。そのうえで pushed=false・rerun=true で返す（summary に Issue の番号を書く）。
- 直せず、回し直す理由もないときは、pushed=false・rerun=false で返し、summary に理由を書く。
ログ抜粋:
${failureLog}`,
        { label: `ci-fix${note ? '-wait' : ''}:${tag}#${i}`, phase: 'CI', schema: FIX_SCHEMA, ...(i === 1 ? M.fixFirst : M.fixAgain) },
      ),
      // 不安定なテストとして回し直した（pushed=false・rerun=true）ときは、コードを変えておらず、ビルドも確かめなくてよい。
      // gradleStillRunning=true が一緒に返っても待つやり直しはしない（やり直しは手順の最初からなので、不安定なテストの Issue と run の回し直しがもう1回起きる）。
      // 待つやり直しをするのは、push した（確かめていない変更がある）か、直せず回し直しもしない（理由の文が未完了を示す）ときだけ
      r => (r?.rerun === true && r?.pushed !== true) ? null : gradlePending(r, r?.summary, r?.pushed === false && r?.rerun !== true),
    )
    if (fixed && stillPending) {
      // 直しのビルドを確かめていないので、直ったことにして先へ進めない。落ちたまま、理由を添えて返す
      ci = { ...ci, failingChecks: [...ci.failingChecks, pendingReason(`CI の修正（${i}）の段階`, fixed)] }
      break
    }
    // 不安定なテストで回し直したとき（rerun）も、push したときと同じく CI を待ち直す
    if (!fixed || !(fixed.pushed || fixed.rerun)) break
    ci = await waitCiResolvingConflicts(info, impl, `ci:${tag}#${i}`, conflictState)
  }
  return ci
}

async function review(info, impl, round) {
  const base = `${REPO} の PR #${impl.pr}（Issue #${info.number}）をレビューする。差分は \`gh pr diff ${impl.pr}\`、コードは worktree ${impl.worktree} で読める。コードは変更しない。CI（build と両バージョンの GameTest）は通っているので、その再現はしない。
must は「このままマージすると壊れる・ルールに反する」と確信できるものだけ。推測や好みは should にする。直し方は書かない。`
  const correctness = `${base}
観点: 正しさ（バグ、境界条件、スレッド安全性、エラー処理）、AGENTS.md のルール違反（core に Minecraft を持ち込む、Kotlin 2.2 より新しい API、片方のバージョンだけ壊れる変更）、Issue の要件の取りこぼし、足りないテスト。`
  // 既定ではレビューは1本。壊れると重い PR は Fable、それ以外は Opus。fable: true なら両方
  const heavy = lockAreas(info.areas).some(a => HEAVY_AREAS.includes(a)) || impl.touchesConcurrency === true
  const reviewers = FABLE || !heavy
    ? [() => step(correctness, { label: `review:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, ...M.review })
      .then(r => r && { by: 'opus', findings: r.findings })]
    : []
  if (FABLE || heavy) {
    reviewers.push(() => step(correctness, { label: `review-fable:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, ...M.fable })
      .then(r => r && { by: 'fable', findings: r.findings }))
  }
  if (impl.publicApiChanged) {
    reviewers.push(() => step(
      `${base}
公開 API（public 宣言、特に pro.ikili.kibicomputers.api 以下）が Java から自然に使えるかを確認する。`,
      { label: `java-api:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, agentType: 'java-api-reviewer', ...M.javaApi },
    ).then(r => r && { by: 'java-api', findings: r.findings }))
  }
  const results = (await parallel(reviewers)).filter(Boolean)
  // 正しさのレビューが 1 本も返らなかったら（利用上限などでエージェントが失敗した）、Opus でやり直す。
  // 返らなかったのを「指摘なし」と同じに扱うと、レビューなしでマージに進んでしまう
  if (!results.some(r => r.by === 'opus' || r.by === 'fable')) {
    log(`PR #${impl.pr}: 正しさのレビューが返らなかった。Opus でやり直す（${round}/${MAX_REVIEW_ROUNDS}）`)
    const retry = await step(correctness, { label: `review-retry:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, ...M.review })
    if (!retry) {
      log(`PR #${impl.pr}: やり直しのレビューも返らなかった。マージしない`)
      record(`PR #${impl.pr}`, { stage: 'review', round, reviewFailed: true })
      return [{ file: '-', line: null, severity: 'must', problem: 'レビューのエージェントが失敗し、この PR をレビューできなかった（やり直しも失敗）', by: 'workflow', reviewFailed: true }]
    }
    results.push({ by: 'opus', findings: retry.findings })
  }
  const findings = results.flatMap(r => r.findings.map(f => ({ ...f, by: r.by })))
  if (!findings.length) {
    record(`PR #${impl.pr}`, { stage: 'review', round, findings: [] })
    return []
  }

  // 指摘は別のエージェントが懐疑的に検証し、本当に直すべきものだけ残す（fable: true なら Opus と Fable の両方で検証し、和を残す）。
  // 残ったものは must・should を問わず直す
  const listed = findings.map((f, i) => `${i}. [${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join('\n')
  const verifyPrompt = `${REPO} の PR #${impl.pr} へのレビュー指摘を検証する。コード（worktree ${impl.worktree}）を実際に読み、各指摘が本当に問題か確かめる。疑わしいもの、好みの問題、PR の範囲外のものは外す。ただし、この PR の作業で見つけた別の問題を Issue にし忘れたこと、ADR・KDoc・PR 本文と実装のずれ、Issue の要件に対するテストの穴は、範囲内として扱う。直すべきものの番号だけを返す（must・should を問わず、残したものはこの PR で直す）。コードは変更しない。
${listed}`
  const [vo, vf] = await parallel([
    () => step(verifyPrompt, { label: `verify:${impl.pr}#${round}`, phase: 'Review', schema: VERDICT_SCHEMA, ...M.verify }),
    ...(FABLE ? [() => step(verifyPrompt, { label: `verify-fable:${impl.pr}#${round}`, phase: 'Review', schema: VERDICT_SCHEMA, ...M.fable })] : []),
  ])
  const byOpus = new Set(vo?.confirmed ?? [])
  const byFable = new Set(vf?.confirmed ?? [])
  record(`PR #${impl.pr}`, {
    stage: 'review',
    round,
    findings: findings.map((f, i) => ({ ...f, confirmedByOpus: vo ? byOpus.has(i) : null, confirmedByFable: vf ? byFable.has(i) : null })),
    verifyNotes: { opus: vo?.notes ?? null, fable: vf?.notes ?? null },
  })
  if (!vo && !vf) {
    // 検証が返らなかった（エージェントの失敗）。黙って捨てずに、指摘をすべて直す側に倒す
    log(`PR #${impl.pr}: 指摘の検証が返らなかった。指摘 ${findings.length} 件をすべて直す`)
    return findings
  }
  return findings.filter((f, i) => byOpus.has(i) || byFable.has(i))
}

// マージの段階で手元で確かめるのは、ワークフローの検査（node --test。1 秒かからない）だけ（#985）。CI は node --test を回さないので、
// 両側が issue-to-pr.js を直した組み合わせの壊れを、main に入れる前にここで止める。ふつうのマージとスタックのマージで同じ文を使う
// スタックでは、一番上の段の木と main の差分（全段の変更）で決める。一つ下の段との差分だと、下の段だけがワークフローを変えた組み合わせで
// 一度も node --test が回らない（stack-sync は node も Gradle も動かさない）
function mergeNodeTestStep(ref, scope = 'この PR') {
  return `取り込みの結果によらず（Already up to date で新しいコミットができなかったときも）、${scope}の差分（\`git diff --name-only origin/${ref}...HEAD\`）に \`.claude/workflows/\` か \`.claude/workflow-tests/\` のファイルがあるときは、worktree の中で \`node --test .claude/workflow-tests/\` を前景で回す（1 秒かからない）。落ちたら push もマージもせず status=needs_human・failedStep=test で、落ちたテストの名前を理由に書く。差分にワークフローがなければ回さない。**\`./gradlew\` は起動しない**（build・GameTest は手元では回さない。先頭のコミットの CI が確かめる。#985）。`
}

// 先頭のコミット（HEAD）を PR に載せる手順（#985）。Already up to date でも、push していないコミットが残っていることがある（前の回の続きなど）
// srcRef は取り込み元（ふつうのマージとスタックの一番下の段は main、スタックの上の段は一つ下の段のブランチ）
function mergePushStep(pr, branch, srcRef) {
  return `\`git fetch origin\` のあとに、${unpushedCheckStep(branch, srcRef)}外れたコミットがあれば、push もマージもせず status=needs_human・failedStep=merge で、理由に「レビューを通っていない、push していないローカルのコミットがある（push すると main に入る）」と、外れたコミットの hash と件名を書く（reset・stash・revert で消さない。人が判断する）。外れたコミットがなければ、\`git rev-parse HEAD\` と \`gh pr view ${pr} --json headRefOid\` を比べる。違えば（取り込みのコミットや、前の回の取り込みの続きのマージのコミットがある）、通常の \`git push origin ${branch}\` で push する（force push はしない）。同じなら push しない。`
}

// push していないローカルのコミットのうち、前の回（とこの回）の取り込みの続きのマージのコミットだけを通す確かめ（#1007）。
// レビューは GitHub 上の差分（`gh pr diff`）しか読まないので、ローカルにしかないコミットはレビューを通っていない。
// `origin/<branch>..HEAD` だけで数えると、push していない取り込みで入った取り込み元の普通のコミットまで数えてしまうので、取り込み元も除く。
// 例外の判定は件名（`Merge ` で始まる、など）でなく親で行う（件名は任意に書ける）。マージのコミットの中身（衝突の解消の手直し）は調べない
// （detectStack の「取り込みのマージはコードの直しとして数えない」と同じ割り切り）
function unpushedCheckStep(branch, srcRef) {
  return `push していないローカルのコミットを確かめる（#1007。レビューは GitHub 上の差分しか読まないので、ローカルにしかないコミットはレビューを通っていない）: \`git rev-list HEAD ^origin/${branch} ^origin/${srcRef}\` の各コミット \`<c>\` について、\`git rev-list --parents -n 1 <c>\` で親がちょうど 2 つ（3 つ以上のマージは不可）で、かつ \`git merge-base --is-ancestor <c>^2 origin/${srcRef}\` か \`git merge-base --is-ancestor <c>^2 origin/${branch}\` のどちらかが真（2 つ目の親が取り込み元か、このブランチの push 済みの先頭から辿れる。取り込みの続きのマージ。後者は、push が断られて取り込み直したときの、origin/${branch} を合わせるマージ）かを見る。1 つでも外れたら（ふつうのコミット、取り込み元でないものとのマージ）、**push しない**。`
}

// note は CI の回し直しのあと（#961）の指示。ログの名前は使わない（Gradle を動かさないので）
function mergePrompt(info, impl, note) {
  return `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）をマージする。他の PR のマージとは直列に行っている。${note ? `\n${note}` : ''}
1. worktree で \`git fetch origin\` し、\`git merge origin/main\` で main を取り込む（rebase と force push はしない）。衝突が字面の解消で済まない（ロジックの判断が要る）なら、\`git merge --abort\` で戻し、マージせず status=needs_human・failedStep=conflict で理由を返す。${mergeNodeTestStep('main')}
2. ${mergePushStep(impl.pr, impl.branch, 'main')}
3. \`gh pr checks ${impl.pr} --watch --interval 30\` で、先頭のコミットの CI がすべて終わって通るのを待つ（pending が残っている間はマージしない）。「通った」は、\`gh pr checks ${impl.pr} --json name,state,bucket\` の全チェックが pass か skipping のとき（必須の \`build\` だけでは決めない）。取り込みが Already up to date で push もせず、先頭のコミットの CI がもう通っていれば、待たずにそのまま手順 4 に進む。通らなければ status=needs_human・failedStep=ci で、落ちたチェックと run の ID を理由に書く。**CI は自分で回し直さない**（\`gh run rerun\` はしない。不安定なテストかどうかは、このスクリプトが別の段階で確かめて、回し直すかを決める）。
   CI の待ちには、下の「CI の待ちの上限」を当てる。待ちをあきらめたら、マージせず status=needs_human・failedStep=ci で、理由に「CI が終わる前に待ちの上限（または接続の失敗）で待つのをやめた」と、まだ終わっていないチェックと run の ID を書く（このスクリプトが別の段階で CI を確かめ直す）。
4. マージの前に \`gh api repos/${REPO}/pulls/${impl.pr} --jq '{base: .base.ref, stack: .stack}'\` を確かめる。stack が null でないか、base が main でなければ（実装の後でスタックに積まれた、など。#936）、\`gh pr merge\` を実行せず status=needs_human・failedStep=merge で「スタックの PR になっている」と返す。\`gh pr merge ${impl.pr} --squash --delete-branch\` でマージする。「base branch policy prohibits the merge」で断られたら、\`gh pr view ${impl.pr} --json mergeStateStatus,statusCheckRollup\` で理由を確かめ、CI の待ちが理由なら 3 からもう1回やり直す。2回目も断られたら status=needs_human・failedStep=merge で理由を返す。マージできたら status=merged・failedStep=null。

${ciWaitRules(impl.pr, 'マージせずに返す（手順 3）。')}`
}

// マージの段階。CI の回し直しの間も mergeLock を持ったままにする（main を動かさないため。ほかのマージは待つ）。
// マージの段階は手元で Gradle を動かさない（#985）ので、待たずに返した回・起動の失敗・過負荷の回し直しはない。マージの段階を 1 回呼び、
// その結果が push の後の CI の失敗（failedStep=ci）なら、不安定なテストの回し直し（#961）を 1 回だけ見る
// （CI がまだ終わっていなかった待ちのあとの CI の失敗だけ、確かめをもう 1 回する。回し直しは合わせて 1 回）
async function mergePr(info, impl) {
  await mergeLock.acquire()
  try {
    const res = await mergeOnce(info, impl, null, 'merge')
    if (!res) return { status: 'needs_human', failedStep: null, reason: 'マージの段階が失敗した' }
    if (res.status !== 'needs_human' || res.failedStep !== 'ci') return res
    return await rerunFlakyCi(info, impl, res)
  } finally {
    mergeLock.release()
  }
}

// マージの段階の push の後の CI の失敗を、CI の直しの段と同じ条件で不安定なテストか確かめ、そうなら記録して 1 回だけ回し直す（#961）。
// 回し直すかどうか・回数はスクリプトが決める（マージの段階のエージェントは回し直さない）。確かめの段階はコードを変えず、push しない
// （ここで push すると、レビューを通っていないコードがそのままマージされる）。回し直し（rerun）は 1 回だけ。
// CI がまだ終わっていなかった（pending）ときは、待つためにマージの段階を呼び直す。その待ちのあとに CI が落ちたら、
// もう 1 回だけ確かめ直す（待ちは回し直しの回数に数えない。確かめも、マージの段階の呼び直しも、最大 2 回）。
// 回し直したあとも CI が落ちたら、今までどおり needs_human・failedStep=ci で止まる
async function rerunFlakyCi(info, impl, res) {
  const first = await rerunFlakyOnce(info, impl, res, false)
  if (!first.waitedForCi) return first.result
  // 待ったあとの CI が落ちた（failedStep=ci のまま）。待ちの前には見られなかった落ち方なので、確かめ直す
  log(`PR #${impl.pr}: CI の待ちのあとも CI が通らなかった。不安定なテストかを確かめ直す`)
  return (await rerunFlakyOnce(info, impl, first.result, true)).result
}

// 確かめと呼び直しを 1 回。last=true の回は、pending でももう待たない。
// waitedForCi: pending で待ち直した結果、また failedStep=ci で止まった（もう 1 回確かめてよい）とき true
async function rerunFlakyOnce(info, impl, res, last) {
  log(`PR #${impl.pr}: マージの段階の push の後の CI が通らなかった。不安定なテストかを確かめる`)
  // 確かめの段階は、手元で再現を見るために Gradle を動かしうるので、Gradle を待たずに返した回のやり直し（#904）を残す
  const { result: f, stillPending } = await withGradleWait(
    `PR #${impl.pr} の CI の不安定なテストの確かめ`, info.number, impl.worktree,
    note => step(flakyPrompt(info, impl, res, note), { label: `merge-flaky${note ? '-wait' : ''}:${impl.pr}`, phase: 'Merge', schema: FLAKY_SCHEMA, ...M.merge }),
    // 回し直した（または CI を待つだけの）回は、コードを変えておらず、ビルドも確かめなくてよい。gradleStillRunning=true が付いても
    // 待つやり直しはしない（CI の直しの rerun と同じ理由。やり直すと Issue と run の回し直しがもう 1 回起きる）。
    // 残った Gradle は、merge-rerun が Gradle を動かさないので、片付け（cleanupWorktree の待ち）が受け止める
    r => (r?.outcome === 'rerun' || r?.outcome === 'pending') ? null : gradlePending(r, r?.summary, false),
  )
  const before = res.reason ?? '-'
  if (!f) return { result: { ...res, reason: `${before}。不安定なテストかの確かめの段階が失敗した（CI は回し直していない）` } }
  if (stillPending) return { result: { ...res, reason: `${before}。${pendingReason('不安定なテストかの確かめの段階', f)}（CI は回し直していない）` } }
  if (f.outcome !== 'rerun' && !(f.outcome === 'pending' && !last)) {
    log(`PR #${impl.pr}: 不安定なテストとは判断しなかった。回し直さない`)
    const why = f.outcome === 'pending' ? `待ったあとも CI が終わっていなかった（run ${f.runId ?? '-'}）` : `不安定なテストとは判断しなかった: ${f.summary ?? '-'}`
    return { result: { ...res, reason: `${before}。${why}（CI は回し直していない）` } }
  }
  const what = f.outcome === 'rerun'
    ? `push の後の CI が PR と関係のない不安定なテスト${f.issue ? `（#${f.issue}）` : ''}で落ちたので、run ${f.runId ?? '-'} の落ちたジョブを 1 回だけ回し直した`
    : `push の後の CI がまだ終わっていなかった（run ${f.runId ?? '-'}）ので、CI を待つためにマージの段階を 1 回だけ呼び直した`
  log(`PR #${impl.pr}: ${what}`)
  const r = await mergeOnce(info, impl, rerunNote(f), 'merge-rerun')
  const prefix = `${before}。${what}`
  if (!r) return { result: { ...res, retried: true, reason: `${prefix}。回し直しのあとのマージの段階が失敗した` } }
  const result = { ...r, retried: true, reason: r.status === 'merged' ? prefix : `${prefix}。回し直しのあとの理由: ${r.reason ?? '-'}` }
  const waitedForCi = f.outcome === 'pending' && r.status === 'needs_human' && r.failedStep === 'ci'
  return { result, waitedForCi }
}

// 不安定なテストかを確かめる段階（merge-flaky）の指示（#961）。条件と記録・回し直しの手順は、CI の直しの段と同じ文（FLAKY_*）を使う
function flakyPrompt(info, impl, res, waitNote) {
  return `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）のマージの段階で、main を取り込んだ先頭のコミットの CI が通らず、マージせずに止まった（理由: ${res.reason ?? '-'}）。マージの段階のエージェントは CI を回し直さない。ここで、CI の直しの段と同じ条件で不安定なテストかを確かめ、そうなら記録して 1 回だけ回し直す。**コードは直さず、コミットも push もしない**（マージの段階で push すると、レビューを通っていないコードがそのままマージされる。PR の変更が原因なら outcome=not_flaky で返す）。${waitNote ? `\n${waitNote}` : ''}
1. \`gh pr view ${impl.pr} --json headRefOid,mergeable,statusCheckRollup\` で先頭のコミットと check を取り、headSha に入れる。\`mergeable\` が CONFLICTING なら、回し直さずに outcome=not_flaky で返す（summary に main との衝突と書く）。
2. 先頭のコミットの run を \`gh run list --commit <headRefOid> --json databaseId,status,conclusion,attempt,headSha,workflowName\` で引く。headSha が先頭のコミットと同じ run だけを扱う（前のコミットの落ちた run を回し直しても意味がない）。queued・in_progress・pending の check が残っていれば、回し直さずに outcome=pending で返す（runId に待っている run）。先頭のコミットの run が 1 つもない、または落ちた run が cancel されたもの（conclusion が cancelled）なら、不安定なテストとは別なので outcome=not_flaky で返し、summary に書く。
3. 落ちた run（conclusion が failure）について、${ciReportsStep(info.number)}試行の番号は \`gh run view <run-id> --json attempt\` で取る。
4. ${FLAKY_CRITERIA}と判断したときは、${FLAKY_RECORD_AND_RERUN}。\`gh run rerun\` は 1 回だけ実行する。そのうえで outcome=rerun で返す（issue に Issue の番号、runId に回し直した run の ID）。
5. それ以外（PR の変更が原因、原因が分からない、など）は、回し直さずに outcome=not_flaky で返し、summary に理由を書く。
手元で再現するかを確かめるために Gradle を動かすときは、worktree の中で動かし、次の決まりに従う（動かさずに判断できるなら、動かさなくてよい）。
${gradleRules(info.number, `merge-flaky${waitNote ? '-wait' : ''}`)}`
}

// 不安定なテストの回し直しのあとに、マージの段階をもう 1 回呼ぶときの指示（#961）。マージの段階は Gradle を動かさない（#985）ので、
// 前は要った「同じ先頭のコミットなら build と GameTest を飛ばす」「確かめの段階が残した Gradle を待つ」の手順はない
function rerunNote(f) {
  const did = f.outcome === 'rerun'
    ? `PR と関係のない不安定なテスト${f.issue ? `（Issue #${f.issue}）` : ''}と判断して、run ${f.runId ?? '-'} の落ちたジョブを回し直した`
    : `CI がまだ終わっていなかったと判断した（run ${f.runId ?? '-'}。回し直してはいない）`
  return `**CI の回し直しのあとのやり直し（#961）**: 前回のマージの段階では、push の後の先頭のコミットの CI が通らなかった。不安定なテストかを確かめる段階が、${did}（${f.summary ?? '-'}）。次を守って、手順 1 からもう 1 回だけやり直す:
1. 手順 1 の取り込みは「Already up to date」でよい（先頭のコミット${f.headSha ? `は ${f.headSha} のまま` : 'が変わっていない'}なら、手順 2 で push するものもない）。
2. 手順 3 では、回し直した CI（同じ run の次の試行。main を取り込んで push したなら新しい run）が終わるまで待つ。**自分では \`gh run rerun\` しない。今回も CI が通らなければ、回し直さずに status=needs_human・failedStep=ci で返す。**`
}

// マージの段階を 1 回呼ぶ（#985。Gradle を動かさないので、待たずに返した回のやり直しはない）。結果を返さなければ null。
// stack があれば、スタックのマージ（#936）の指示で呼び、段階の名前（ラベル）に stack- を付ける
function mergeOnce(info, impl, stageNote, stage, stack = null) {
  const name = `${stack ? 'stack-' : ''}${stage}`
  return step(
    stack ? stackMergePrompt(info, impl, stageNote, stack) : mergePrompt(info, impl, stageNote),
    { label: `${name}:${impl.pr}`, phase: 'Merge', schema: MERGE_SCHEMA, ...M.merge },
  )
}

// ---- Stacked PRs（#936） ----
// スタック（gh stack）の PR は、1 つの単位としてマージする（PR #924 へのオーナーの回答）:
// 下の段から順に取り込み（一番下は main、上の段は一つ下の段のブランチ。通常の push だけ。手元で Gradle は動かさない。#985）→
// 全段の先頭のコミットの CI → `gh stack merge <一番上> --yes --squash`。ツールの権限の判定で拒否されたら、確かめを済ませたうえで
// 「スタックごとマージしてよいか」を 1 回だけ聞く（回避の手段は使わない）。人への新しい質問は、スタックにつき 1 つまで。
// 下の段の論点（needs-human）がもう別の PR で聞かれているなら、新しく聞かず、その PR を指す短い報告だけを付ける（needs-human は付けない。付けると、人が論点の PR のラベルだけを外して resume したとき、案内のラベルで止まって互いを指し合う）。
// 段の担当: 一番上の段の Issue の流れがまとめてマージする。下の段の Issue の流れは、上の段がこの実行の中にあれば、その結果（done）を待つ。
// 待つ前に slots と area のロックを放す（上の段がそれを取れずに詰まらないように）。上の段は下の段の「マージしてよい」（readySignals）を、
// mergeLock の外で待つ（下の段は、マージの段階に入る前に、それを出す）

// Issue の番号 → 「マージしてよいところまで進んだか」（{ ready, reason }）。どの道でも 1 回は解決する（実行の部分が最後に解決する）
const readySignals = new Map()
// PR の番号 → { info, impl }（この実行で流している PR）
const prImpl = new Map()
// Issue の番号 → info（この実行で流している Issue）
const infoByNumber = new Map()
// Issue の番号 → その流れが登録した PR の番号（prIssue の逆引き。流れが slots と area のロックを取って PR を登録するまでは載らない）
const issuePr = new Map()
// resume で渡された PR の番号 ⇄ Issue の番号（流れが PR を登録する前から、この実行の PR と分かるように。起動のときに入れる）
const resumePrs = new Map()
const resumePrOf = new Map()

function issueOfBranch(branch) {
  const m = /^issue-(\d+)-/.exec(branch ?? '')
  return m ? Number(m[1]) : null
}

// スタックの段 PR を担当している、この実行の Issue の流れ（なければ null。#936）。
// 流れが PR を登録する（prIssue・prImpl に入れる）のは、slots と area のロックを取ったあとなので、登録だけを頼りにすると、
// ロック待ち・依存待ち・実装中の流れを「この実行にない」と取り違える（同じ area の 3 段以上や、window が埋まっているとき。同じ area でも 2 段までは同時に動く。#986）。
// 登録済み → resume で渡された PR → まだ PR を登録していない流れのブランチ名（issue-<番号>-）の順に見る。
// 同じ Issue がもう別の PR を登録している（1 つの Issue を複数の PR に分けた形）なら、その段はこの実行の流れの PR ではない
function flowOfPr(pr, branch) {
  if (prIssue.has(pr)) return prIssue.get(pr)
  if (resumePrs.has(pr)) return resumePrs.get(pr)
  const n = issueOfBranch(branch)
  if (n == null || !infoByNumber.has(n) || issuePr.has(n) || resumePrOf.has(n)) return null
  return n
}

// Issue a が Issue b に（推移的に）依存しているか。依存先の流れが終わるまで a は始まらない（runIssue の依存の待ち）
function dependsOn(a, b, seen = new Set()) {
  if (seen.has(a)) return false
  seen.add(a)
  const deps = infoByNumber.get(a)?.openDeps ?? []
  return deps.includes(b) || deps.some(d => dependsOn(d, b, seen))
}

// スタックの中で、この実行の段の Issue どうしが（推移的に）依存しているか（#936）。どの向きでも互いに待つ形になる:
// 下の段の流れは一番上の段の done を待ち、一番上の段は下の段の readySignals を待ち、依存を書いた流れは依存先の done を待ってから始まる
// （例: 下から X・A・B で A が「依存: #X」なら、X は B を、B は A を、A は X を待つ）。当たった組 { a, b }（a が b に依存）を返す。なければ null
function stackDepPair(plan) {
  const flows = [...new Set(plan.members.filter(m => m.inRun).map(m => m.flow))]
  for (const a of flows) for (const b of flows) if (a !== b && dependsOn(a, b)) return { a, b }
  return null
}

// 読むだけの段階で、PR がスタックに属するかと、全段の状態を取る。gh stack view はローカルで追跡しているスタックしか見えないので使わない
function detectStack(pr, label) {
  return stepRetrying(
    `${REPO} の PR #${pr} が Stacked PRs（GitHub のスタック）に属するかを調べる（#936）。コードとリポジトリは変更せず、Issue・PR にも書き込まない。
1. \`gh api repos/${REPO}/pulls/${pr} --jq '{base: .base.ref, stack: .stack}'\` を読む。baseRef に base を入れる。stack が null なら、stackNumber=null・members=[] で返す（base が main でなくても、スタックでなければ members は空）。
2. stack があれば stackNumber に \`.stack.number\` を入れ、\`gh api repos/${REPO}/stacks/<stack の number>\` の \`pull_requests[]\` を読む（下の段から順）。\`gh stack view\` は使わない（ローカルで追跡しているスタックしか見えない）。
3. 各段について \`gh pr view <n> --json number,state,isDraft,labels,headRefName,baseRefName,headRefOid,comments,commits\` を読み、members に下から順に入れる（position は 1 から。needsHuman は needs-human ラベルが付いているか）。
4. 各段の reviewed を決める: \`comments\` のうち、投稿者の \`authorAssociation\` が OWNER・MEMBER・COLLABORATOR で、本文が「## 自動レビューの結果」で始まる最後のコメントを探す。その本文に「結論: マージ可能」があり、かつそのコメントの \`createdAt\` より後の \`committedDate\` のコミットが、headline が \`Merge \` で始まる取り込みのコミットだけ（コードを変えた直しがない）なら true。コメントがない・結論が「人の確認が必要」・コメントより後にコードの直しがある、のどれかなら false。`,
    { label, phase: 'Merge', schema: STACK_SCHEMA, ...M.select },
  ).then(x => x.result)
}

// 判定の結果を、この実行の情報と合わせる。ok=false はスタックとして扱えない（理由を reason に）
function stackPlan(st, selfPr) {
  const members = [...(st.members ?? [])]
    .sort((a, b) => a.position - b.position)
    .map(m => {
      const flow = flowOfPr(m.pr, m.branch)
      // flow: この段を担当する、この実行の Issue の流れ（なければ null）。issue: ブランチ名などから分かる Issue（この実行にないものも）
      return { ...m, flow, issue: flow ?? issueOfBranch(m.branch), inRun: flow != null }
    })
  // 理由の文は人への質問と Fable の確かめの前提になるので、当たった形ごとに事実どおりに書く（#936）
  if (st.stackNumber == null && st.baseRef && st.baseRef !== 'main') {
    return { ok: false, members, reason: `PR #${selfPr} の base が main でない（${st.baseRef}）のに、GitHub のスタックに属していない。base を別の PR のブランチに向けただけの PR は、スタックとして扱えない（AGENTS.md。\`gh pr merge\` すると、main ではなく base のブランチに squash され、Issue も閉じない）。\`gh stack link <下の PR の番号> ${selfPr}\` でスタックにするか、base を main に向け直してから続ける` }
  }
  if (st.stackNumber == null) {
    return { ok: false, members, reason: `PR #${selfPr} は、取り直したときには GitHub のスタックに属していなかった（base は ${st.baseRef ?? '不明'}）。スタックから外された（\`gh stack unstack\` など）か、判定の段階の読み違いなので、スタックとしては扱わない。ふつうの PR としてマージするなら、resume でもう 1 回流す` }
  }
  if (!members.length) {
    return { ok: false, members, reason: `PR #${selfPr} はスタック #${st.stackNumber} に属しているが、判定の段階がスタックの段の一覧（\`gh api repos/${REPO}/stacks/${st.stackNumber}\` の \`pull_requests\`）を読めなかった（members が空）。段が分からないので、スタックとしてもふつうの PR としてもマージしない` }
  }
  const idx = members.findIndex(m => m.pr === selfPr)
  if (idx < 0) return { ok: false, members, reason: `スタック #${st.stackNumber} の段の一覧に PR #${selfPr} がない` }
  return { ok: true, number: st.stackNumber, members, self: members[idx], top: members[members.length - 1], isTop: idx === members.length - 1, lowers: members.slice(0, -1) }
}

function stackLabel(plan) {
  return `スタック #${plan.number}（下から ${plan.members.map(m => `PR #${m.pr}`).join(' → ')}）`
}

// PR に短い報告を付ける（論点の置き場所の案内や、まとめてマージした報告）。needs-human は付けない:
// 案内のためだけに付けると、人が論点の PR のラベルだけを外して resume したとき、案内のラベルを「人の回答待ち」と取り違えて止まる
// （互いを指し合って進まない）。ラベルは、本当に人へ質問した PR（論点の PR）にだけ付く。重ねても害の小さい段階なので、呼び直す
async function stackNote(pr, body) {
  const { result } = await stepRetrying(
    `${REPO} の PR #${pr} に、次の内容を日本語のコメントで投稿する（\`gh pr comment ${pr} --body-file <ファイル>\`）。見出しは「## スタックの状態」。内容は書き換えずにそのまま載せる。ラベルは付けない（\`needs-human\` を付けたり外したりしない）。\n\n${body}`,
    { label: `stack-note:${pr}`, phase: 'Report', ...M.report },
    `投稿の前に \`gh pr view ${pr} --json comments\` で確かめ、見出しが「## スタックの状態」で同じ内容のコメントがもう付いていれば、投稿しない。`,
  )
  return result
}

// スタックの 1 段に下の段（一番下なら main）を取り込んで push する指示。Gradle は動かさない（確かめは一番上の段の木で 1 回）
function stackSyncPrompt(plan, i) {
  const m = plan.members[i]
  const src = i === 0 ? 'main' : plan.members[i - 1].branch
  const known = prImpl.get(m.pr)?.impl.worktree
  // この実行にない段の作業場所。ブランチ名の Issue がこの実行の Issue と同じ（1 つの Issue を複数の PR に分けた形。上の段の worktree が
  // `.worktrees/issue-<番号>` にある）か、この実行で流している Issue なら、`.worktrees/issue-<番号>` は別のブランチ（この実行の流れ）のものなので使わず、
  // PR の番号の場所にする（別のブランチを開いた worktree でブランチを切り替えると、その流れの前提が崩れる）
  const shared = m.issue != null && (infoByNumber.has(m.issue) || [...prImpl.values()].some(x => x.info.number === m.issue))
  const dir = m.issue != null && !shared ? `.worktrees/issue-${m.issue}` : `.worktrees/stack-pr-${m.pr}`
  const prDir = `.worktrees/stack-pr-${m.pr}`
  const where = known
    ? `worktree ${known} で作業する。`
    : `作業場所: このブランチをもうチェックアウトしている worktree があれば（\`git worktree list --porcelain\` の \`branch refs/heads/${m.branch}\`）、そこで作業する（同じブランチは 2 つの worktree で開けない）。なければリポジトリのルートで \`git worktree add ${dir} ${m.branch}\`（ローカルにブランチがなければ \`git worktree add -b ${m.branch} ${dir} origin/${m.branch}\`）で作る。${dir !== prDir ? `\`${dir}\` がもうあって、ほかのブランチを開いた worktree なら、それは別の流れのものなので触らず、ブランチも切り替えず、\`${prDir}\` に同じ手順で作る。` : ''}${m.issue != null && !shared ? worktreeCheck(m.issue) : ''}`
  return `${REPO} の${stackLabel(plan)}の ${i + 1}/${plan.members.length} 段目、PR #${m.pr}（ブランチ ${m.branch}）に、\`origin/${src}\` を取り込んで push する（#936。取り込みは下の段から順に行い、この段階はスクリプトが段ごとに呼ぶ）。**Gradle は動かさない**（確かめは、全段の取り込みのあとの各段の先頭のコミットの CI が行う。#985）。${where}
1. \`git status --porcelain\` で作業ツリーが汚れていたら（コミットしていない変更がある）、何もせず outcome=failed・pushed=false で、summary に残っているファイルを書く。reset・stash・checkout で消さない。
2. \`git fetch origin\` する。手順 3 の取り込みより前に（ここで合わせるマージを作る前に）、${unpushedCheckStep(m.branch, src)}外れたコミットがあれば、取り込みもせず outcome=failed・pushed=false・unpushedCommits=true で、summary に「レビューを通っていない、push していないローカルのコミット（push すると main に入る）」と、外れたコミットの hash と件名を書く（reset・stash・revert で消さない。人が判断する）。外れたコミットがなければ、ローカルのブランチが \`origin/${m.branch}\` より遅れていれば \`git merge --ff-only origin/${m.branch}\`、分かれていれば \`git merge origin/${m.branch}\` で合わせる（push していない取り込みの続きは捨てない。reset しない）。
3. \`git merge origin/${src}\` する。rebase はしない。衝突が字面の解消で済む（両方の変更を残せば済む、など）なら解いてコミットする。ロジックの判断が要るなら \`git merge --abort\` で戻し、push せず outcome=conflict・pushed=false で、summary に衝突したファイルと判断が要る理由を書く。
4. push するもの（取り込みのコミットや、手順 2 の確かめで残った、前の回の取り込みの続きのマージのコミット）があれば、通常の \`git push origin ${m.branch}\` で push し、outcome=merged・pushed=true。なければ何もせず outcome=up_to_date・pushed=false。push が non-fast-forward で断られたら、\`git fetch origin\` して \`git merge origin/${m.branch}\` してから 1 回だけ push し直す。それでも断られたら outcome=failed。
5. headSha に \`git rev-parse HEAD\`、worktree に作業した worktree の絶対パスを入れる。
**force push はしない**（\`--force\`・\`--force-with-lease\` を付けない）。\`gh stack sync\`・\`gh stack rebase\`・\`gh stack push\`・\`gh stack submit\` は force-with-lease で push するので使わない。`
}

// スタックのマージの段階の指示。取り込み（stack-sync）は済んでいる。一番上の段の木で取り込みを確かめ、全段の CI を待ち、gh stack merge でまとめてマージする。
// 手元で Gradle は動かさない（#985。確かめは全段の先頭のコミットの CI）
function stackMergePrompt(info, impl, note, plan) {
  const belowRef = plan.lowers.length ? plan.lowers[plan.lowers.length - 1].branch : 'main'
  const prs = plan.members.map(m => `#${m.pr}`).join('・')
  const cmd = `gh stack merge ${impl.pr} --yes --squash`
  return `${REPO} の${stackLabel(plan)}を、一番上の段 PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）の木で取り込みを確かめ、全段の CI を待ってから、スタックごとマージする（#936）。他の PR のマージとは直列に行っている。下の段から順の取り込み（一番下の段は main、上の段は一つ下の段のブランチ）は、この段階の前に済ませて push してある。${note ? `\n${note}` : ''}
1. worktree で \`git fetch origin\` し、\`git merge origin/${belowRef}\` する（ふつうは Already up to date）。${belowRef === 'main' ? '' : 'main は直接取り込まない（下の段を通して入っている）。'}rebase と force push はしない。\`gh stack sync\`・\`gh stack rebase\`・\`gh stack push\`・\`gh stack submit\` は force-with-lease で push するので使わない。衝突が字面の解消で済まないなら、\`git merge --abort\` で戻し、マージせず status=needs_human・failedStep=conflict で理由を返す。${mergeNodeTestStep('main', 'スタック全体')}
2. ${mergePushStep(impl.pr, impl.branch, belowRef)}
3. 全段の PR（${prs}）について、それぞれ \`gh pr checks <n> --watch --interval 30\` で、先頭のコミットの CI がすべて終わって通るのを待つ（下の段に push すると下の段の CI、上の段に push すると上の段の CI が走る。どの段も、pending が残っている間はマージしない。「通った」は、\`gh pr checks <n> --json name,state,bucket\` の全チェックが pass か skipping のとき）。もう通っている段は待たなくてよい。どれかが通らなければ status=needs_human・failedStep=ci で、PR の番号・落ちたチェック・run の ID を理由に書く。**CI は自分で回し直さない**（\`gh run rerun\` はしない）。
   CI の待ちには、下の「CI の待ちの上限」を当てる（\`<n>\` は各段の PR の番号。上限は全段を合わせて測る）。待ちをあきらめたら、マージせず status=needs_human・failedStep=ci で、理由に「CI が終わる前に待ちの上限（または接続の失敗）で待つのをやめた」と、まだ終わっていない PR・チェック・run の ID を書く。
4. \`${cmd}\` を 1 回だけ実行する（\`--squash\` は必ず付ける。\`--yes\` だけだと前回の方式でマージする）。**\`gh pr merge\`、\`gh api\` の \`PUT .../pulls/<n>/merge\`・\`merge-async\` など、別の口では試さない**（スタックの PR は \`gh pr merge\` では入らず、別の口で通すのは権限の判定の回避になる）。下の段だけの部分マージ（\`gh stack merge <下の段>\`）もしない。
   - ツールの権限の判定で拒否された（コマンドが実行されなかった）ら、ほかの手段を試さず、status=needs_human・failedStep=merge・stackMergeOutcome=permission_denied で返す。reason には、確かめた事実（この木の先頭のコミット、各段の CI の run の ID と結果）を書く。
   - GitHub が断ったら（rebase が要る、チェックが終わっていない、など）、メッセージを確かめる。CI の待ちが理由なら 3 からもう 1 回だけやり直す。それ以外（rebase が要る、など）は、rebase も force push もせず、status=needs_human・failedStep=merge・stackMergeOutcome=github_rejected で、メッセージを理由に書く。
   - 通ったら、マージは非同期なので、全段（${prs}）の \`gh pr view <n> --json state,mergeCommit\` が MERGED になるまで、最大 10 分、\`gh pr checks ${impl.pr} --watch --interval 30\` を挟むなどで間を空けて取り直す。全段が MERGED なら status=merged・failedStep=null・stackMergeOutcome=merged。ならなければ status=needs_human・failedStep=merge・stackMergeOutcome=github_rejected で、各段の状態を理由に書く。

${ciWaitRules('<n>', 'マージせずに返す（手順 3）。')}

返す値の stackMergeOutcome: 手順 4 の結果。手順 4 まで進まなかったら null。`
}

// スタックの上の段がまとめてマージするのを待つ（下の段の Issue の流れ）。上の段がこの実行にないなら、下の段だけではマージしない
async function waitStackTop(info, plan) {
  const label = stackLabel(plan)
  // 上の段を担当する流れ。上の段の流れがまだ PR を登録していなくても（ロック待ち・依存待ち）、この実行の流れとして見つかる（flowOfPr）
  const topIssue = plan.top.flow
  const command = `\`gh stack merge ${plan.top.pr} --yes --squash\``
  if (topIssue == null || !done.has(topIssue)) {
    log(`PR #${plan.self.pr}: ${label}の下の段。上の段 PR #${plan.top.pr} はこの実行にないので、この PR だけではマージしない`)
    return { status: 'stack_lower', noQuestion: true, pointerPr: null, reason: `${label}の下の段（${plan.self.position}/${plan.members.length}）。上の段 PR #${plan.top.pr} と一緒に、上の段の流れ（issue-to-pr の resume など）で ${command} によりまとめてマージする。この PR だけではマージしない（部分マージのあとは、上の段に rebase が要る）` }
  }
  // 上の段の Issue がこの Issue に依存していると、上の段はこの Issue の終わりを待つので、ここで上の段を待つと互いに待って詰まる
  // （依存を書いた Issue の流れは、依存先の done を待ってから始まるので、上の段の PR がまだ登録されていない間にここへ来る）
  // 3 段以上では、中の段どうしの依存でも詰まる（stackDepPair）。上の段の流れは同じ組を見て止まり、人に聞く（mergeStackTop）
  const pair = stackDepPair(plan)
  if (pair) {
    if (pair.a === topIssue && pair.b === info.number) {
      log(`PR #${plan.self.pr}: 上の段の Issue #${topIssue} がこの Issue に依存しているので、上の段を待たない`)
      return { status: 'stack_lower', noQuestion: true, pointerPr: null, reason: `${label}の下の段。上の段の Issue #${topIssue} が「依存: #${info.number}」でこの Issue の終わりを待つため、この実行ではスタックをマージしない。上の段を resume で流すと、まとめてマージする` }
    }
    log(`PR #${plan.self.pr}: ${label}の段の Issue #${pair.a} が Issue #${pair.b} に依存しているので、上の段を待たない`)
    return { status: 'stack_lower', noQuestion: true, pointerPr: null, reason: `${label}の下の段。スタックの段の Issue #${pair.a} が「依存: #${pair.b}」で Issue #${pair.b} の終わりを待つため、段どうしが互いに待つ形になる。この実行ではスタックをマージしない（上の段の流れが、依存の向きとスタックの積み方の食い違いとして聞く）` }
  }
  log(`PR #${plan.self.pr}: ${label}の下の段。上の段 PR #${plan.top.pr}（Issue #${topIssue}）の流れがまとめてマージするのを待つ`)
  const t = await done.get(topIssue).promise
  if (t?.status === 'merged') {
    return { status: 'merged', retried: true, reason: `${label}を、上の段 PR #${plan.top.pr} の流れで ${command} によりまとめてマージした` }
  }
  const why = t?.mergeReason ?? t?.reason ?? t?.status ?? '-'
  // 論点を指すのは、上の段の流れが本当に人に聞いた（question）か、論点の置き場所を示した（stackPointer）ときだけ。
  // 上の段が質問なしで終わった（依存が閉じていない blocked、failed、例外、skipped など）なら、ここで聞く（指すだけだと、どの PR にも論点が残らない）
  const where = t?.stackPointer ?? (t?.question ? plan.top.pr : null)
  if (where == null) {
    log(`PR #${plan.self.pr}: 上の段 PR #${plan.top.pr} の流れは、質問を残さずに終わった（${t?.status ?? '-'}）。この PR で聞く`)
    return { status: 'needs_human', failedStep: 'merge', reason: `${label}は、上の段 PR #${plan.top.pr}（Issue #${topIssue}）の流れでマージしなかった（${t?.status ?? '-'}: ${why}）。上の段の流れは人への質問を残していないので、スタックの論点としてこの PR で聞く。この PR だけではマージしない（部分マージのあとは、上の段に rebase が要る）` }
  }
  return { status: 'needs_human', failedStep: 'merge', noQuestion: true, pointerPr: where, reason: `${label}は、上の段 PR #${plan.top.pr} の流れでマージしなかった（${why}）。論点は PR #${where} で扱う` }
}

// スタックの一番上の段の流れが、スタックごとマージする
async function mergeStackTop(info, impl, plan) {
  const label = stackLabel(plan)
  // a. この実行で流している下の段が、マージしてよいところまで進むのを待つ（mergeLock の外で待つ。下の段は mergeLock を取らずに知らせる）
  // 下の段の流れが PR をまだ登録していなくても（ロック待ち・実装中）、この実行の流れとして待つ（flowOfPr）。待たずに進むと、
  // その段のレビューと直しを待たずに、確かめている最中の worktree へ取り込みを入れ、一緒にマージしてしまう
  // 段の Issue どうしが依存していると（下の段が一番上の段に、3 段以上なら中の段が別の段に）、依存を書いた流れは依存先の終わりを待って始まらず、
  // 下の段は一番上の段を、一番上の段は下の段の知らせを待つので、互いに待って詰まる（実行全体が返らない）。待たずに止まって聞く
  const pair = stackDepPair(plan)
  if (pair) {
    const prOf = n => plan.members.find(m => m.flow === n)?.pr
    log(`PR #${impl.pr}: ${label}の段の Issue #${pair.a}（PR #${prOf(pair.a)}）が Issue #${pair.b}（PR #${prOf(pair.b)}）に依存している。互いに待つので、スタックはマージしない`)
    const who = pair.b === info.number ? `下の段 PR #${prOf(pair.a)} の Issue #${pair.a} が「依存: #${info.number}」でこの Issue の終わりを待つため、下の段の流れが始まらず` : `段 PR #${prOf(pair.a)} の Issue #${pair.a} が「依存: #${pair.b}」で段 PR #${prOf(pair.b)} の Issue #${pair.b} の終わりを待つため、段の流れどうしが`
    return { status: 'needs_human', failedStep: 'merge', reason: `${label}の${who}、互いに待つ。依存の向きとスタックの積み方が合っていない。スタックは確かめもマージもしていない` }
  }
  for (const m of plan.lowers.filter(m => m.inRun)) {
    const sig = readySignals.get(m.flow)
    const r = sig ? await sig.promise : { ready: false, reason: '知らせの置き場所がない' }
    if (!r.ready) {
      // 論点を指すのは、下の段の流れが本当に人に聞いた（question）か、論点の置き場所を示したときだけ。下の段の流れは、知らせを出したあとは
      // 上の段を待たずに終わる（マージの段階に入らない）ので、その結果を待っても詰まらない
      const lr = await done.get(m.flow)?.promise
      const where = lr?.stackPointer ?? (lr?.question ? m.pr : null)
      log(`PR #${impl.pr}: ${label}の下の段 PR #${m.pr} がマージしてよいところまで進まなかった。スタックはマージしない`)
      if (where == null) {
        return { status: 'needs_human', failedStep: 'merge', reason: `${label}の下の段 PR #${m.pr}（Issue #${m.flow}）が、マージしてよいところまで進まなかった（${r.reason ?? '-'}。下の段の流れの結果: ${lr?.status ?? '-'}: ${lr?.reason ?? lr?.mergeReason ?? '-'}）。下の段の流れは人への質問を残していないので、スタックの論点としてこの PR で聞く。スタックは確かめもマージもしていない` }
      }
      return { status: 'needs_human', failedStep: 'merge', noQuestion: true, pointerPr: where, reason: `${label}の下の段 PR #${m.pr} が、マージしてよいところまで進まなかった（${r.reason ?? '-'}）。論点は PR #${where} で扱う。スタックは確かめもマージもしていない` }
    }
  }
  await mergeLock.acquire()
  try {
    // b. マージの直前に、全段の状態を取り直す（実装の後で積まれた・外された、下の段に人の論点が付いた、など）
    const st = await detectStack(impl.pr, `stack-check:${impl.pr}`)
    if (!st) return { status: 'needs_human', failedStep: 'merge', reason: `${label}: マージの直前にスタックの状態を取り直せなかった（読むだけの段階が結果を返さなかった）。確かめもマージもしていない` }
    const now = stackPlan(st, impl.pr)
    const same = now.ok && now.members.map(m => m.pr).join(',') === plan.members.map(m => m.pr).join(',')
    if (!same || !now.isTop) {
      return { status: 'needs_human', failedStep: 'merge', reason: `${label}: マージの直前に取り直したスタックの形が変わっていた（${now.ok ? `今は下から ${now.members.map(m => `PR #${m.pr}`).join(' → ')}` : now.reason}）。確かめもマージもしていない` }
    }
    // 止まる論点を、取り込み・確かめより先に集める（答えで下の段のコードが変わると、確かめが無駄になる。#924 の轍）
    const broken = now.members.filter(m => m.state !== 'OPEN' || m.draft)
    if (broken.length) {
      return { status: 'needs_human', failedStep: 'merge', reason: `${label}: ${broken.map(m => `PR #${m.pr} が ${m.state !== 'OPEN' ? m.state : 'draft'}`).join('、')}。スタックの一部だけがマージ・クローズされた形は、残りに rebase（force push）が要るので、自動では進めない。確かめもマージもしていない` }
    }
    const waiting = now.lowers.filter(m => m.needsHuman)
    if (waiting.length) {
      log(`PR #${impl.pr}: ${label}の下の段に needs-human が付いている。取り込みも確かめもせずに止まる`)
      return { status: 'needs_human', failedStep: 'merge', noQuestion: true, pointerPr: waiting[0].pr, reason: `${label}の下の段 ${waiting.map(m => `PR #${m.pr}`).join('・')} に needs-human が付いている（人の回答待ち）。上の段をマージすると下の段も一緒に入るので、スタックの論点として PR #${waiting[0].pr} の回答を待つ。取り込み・確かめ・マージはしていない` }
    }
    // この実行にない下の段は、このフローのレビュー（AGENTS.md の開発フローの 5）を通ったものだけ、一緒にマージする。
    // 一番上の段のレビューは base が下の段のブランチの差分なので、下の段のコードは見ない。通っていない段は、別の流れ
    // （その PR の issue-to-pr の resume）でレビューしてから、まとめてマージする。新しい論点なので、一番上の段で 1 回だけ聞く
    const unreviewed = now.lowers.filter(m => !m.inRun && !m.reviewed)
    if (unreviewed.length) {
      log(`PR #${impl.pr}: ${label}の下の段 ${unreviewed.map(m => `PR #${m.pr}`).join('・')} は、この実行にないうえに、自動レビューを通った記録がない。取り込みも確かめもせずに止まる`)
      return { status: 'needs_human', failedStep: 'merge', reason: `${label}の下の段 ${unreviewed.map(m => `PR #${m.pr}`).join('・')} は、この実行の流れではなく、自動レビュー（issue-to-pr のレビュー。コメント「## 自動レビューの結果」の結論が「マージ可能」で、その後にコードの直しがないもの）を通った記録もない。一番上の段のレビューは下の段の差分を見ないので、このままスタックごとマージすると、下の段がレビューなしで main に入る。取り込み・確かめ・マージはしていない。進め方は次のどちらか（どちらも、この PR の needs-human を外してから）:
(a) レビューしてから続ける: issue-to-pr の resume で、下の段の PR を 1 つずつ、上の段とは別の起動で流す（resume には 1 つの Issue につき PR を 1 つだけ渡す。同じ Issue の PR を 2 つ渡すと、どちらも流さない）。下の段の流れは、レビューを通ると「## 自動レビューの結果」（結論: マージ可能）を付け、上の段がその実行にないので、その段だけではマージせずに終わる。そのあと一番上の段 PR #${impl.pr} を resume すると、スタックごとマージする。
(b) レビューなしでマージしてよい: 人がそのまま \`gh stack merge ${impl.pr} --yes --squash\` を実行する。この流れには、レビューの記録のない段を一緒にマージする道はない（一番上の段だけを resume しても、同じところで止まる）` }
    }
    // c. 下の段から順に取り込んで push する（段ごとに 1 回ずつ。順はこのループで決める）
    const synced = []
    const syncedWorktrees = {}
    for (let i = 0; i < now.members.length; i++) {
      const m = now.members[i]
      const s = await step(stackSyncPrompt(now, i), { label: `stack-sync:${m.pr}`, phase: 'Merge', schema: STACK_SYNC_SCHEMA, ...M.merge })
      if (s && s.outcome === 'failed' && s.unpushedCommits === true) {
        // 取り込みの失敗と読み違えないように、別の文にする（#1007）。push して進めればよい、と読まれないよう事実を書く
        const done_ = synced.length ? `（${synced.join('、')} は取り込んで push 済み）` : ''
        return { status: 'needs_human', failedStep: 'merge', reason: `${label}: ${i + 1} 段目 PR #${m.pr}（ブランチ ${m.branch}）の作業場所（${s.worktree ?? '-'}）に、レビューを通っていない、push していないローカルのコミットがある（取り込みの続きのマージではない）。push するとレビューなしで main に入るので、push・取り込み・確かめ・マージはしていない${done_}。ローカルのコミットは消していない。そのコミットを PR に載せてレビューを通すか、捨てるかを人が決める。詳細: ${s.summary}` }
      }
      if (!s || s.outcome === 'conflict' || s.outcome === 'failed') {
        const why = !s ? '取り込みの段階が結果を返さなかった' : s.outcome === 'conflict' ? `字面で解けない衝突: ${s.summary}` : s.summary
        const done_ = synced.length ? `（${synced.join('、')} は取り込んで push 済み）` : ''
        return { status: 'needs_human', failedStep: 'conflict', reason: `${label}: ${i + 1} 段目 PR #${m.pr} に ${i === 0 ? 'main' : now.members[i - 1].branch} を取り込めなかった（${why}）${done_}。確かめもマージもしていない` }
      }
      synced.push(`PR #${m.pr}（${s.outcome === 'merged' ? '取り込んで push' : '取り込むものなし'}）`)
      // 実際に作業した場所。この実行にない下の段の片付けは、決め打ちの名前でなく、これを使う（runStack）
      if (s.worktree) syncedWorktrees[m.pr] = s.worktree
    }
    log(`PR #${impl.pr}: ${label}の取り込みが済んだ（${synced.join('、')}）。全段の CI を待ってマージする`)
    // d. 一番上の段の木で取り込みを確かめ、全段の CI を待ち、gh stack merge する（手元で Gradle は動かさない。#985）。
    // 不安定なテストの CI の回し直し（#961）は、1 つの PR を見る作りなので、スタックには使わない（落ちたら止まって人に上がる）
    const res = (await mergeOnce(info, impl, null, 'merge', now)) ?? { status: 'needs_human', failedStep: null, reason: 'スタックのマージの段階が失敗した' }
    const prefix = `${label}: 下の段から順に取り込んだ（${synced.join('、')}）。`
    if (res.status === 'merged') return { ...res, syncedWorktrees, retried: true, reason: `${prefix}全段の CI を確かめ、\`gh stack merge ${impl.pr} --yes --squash\` でまとめてマージした` }
    if (res.stackMergeOutcome === 'permission_denied') {
      return {
        ...res,
        reason: `${prefix}確かめ（全段の CI）は済んだが、\`gh stack merge ${impl.pr} --yes --squash\` がツールの権限の判定で拒否された（ほかの口での回避はしていない）。スタックごとマージしてよいか判断してほしい。よければ、人がそのまま \`gh stack merge ${impl.pr} --yes --squash\` を実行する（\`.claude/settings.json\` で許可するかも人が決める）。確かめた事実: ${res.reason ?? '-'}`,
        escalateContext: `スタックのマージの段階（${label}）。確かめは済んだが、\`gh stack merge\` がツールの権限の判定で拒否された。回避の手段（\`gh api\` の merge-async や \`PUT /pulls/<n>/merge\` など）は使わない。設定（\`.claude/settings.json\`）は人が変える（Issue #936）。エージェントが直せる問題ではない`,
      }
    }
    return { ...res, reason: `${prefix}${res.stackMergeOutcome === 'github_rejected' ? 'GitHub が `gh stack merge` を断った（rebase・force push はしていない）。' : ''}${res.reason ?? '-'}` }
  } finally {
    mergeLock.release()
  }
}

// スタックに属する PR（か、base が main でない PR）のマージと報告。runIssue がロックを放してから呼ぶ
async function runStack(info, d) {
  const { impl, ci, rounds, ready, unverified, unpushed, stack: st } = d
  const plan = stackPlan(st, impl.pr)
  let merge
  if (!plan.ok) merge = { status: 'needs_human', failedStep: 'merge', reason: plan.reason }
  else if (!plan.isTop) merge = await waitStackTop(info, plan)
  else merge = await mergeStackTop(info, impl, plan)
  const question = await report(info, { impl, ci, rounds, merge, ready, unverified, unpushed, escalateContext: merge.escalateContext })
  const merged = merge.status === 'merged'
  // 人に聞いた（か、論点を別の PR に置いた）場所。下の段の Issue の流れは、これを見て案内を付ける
  const pointer = merged ? null : (merge.pointerPr ?? (question ? impl.pr : null))
  if (plan.ok && plan.isTop) {
    // この実行で流していない下の段は、上の段の流れが報告と片付けをする（この実行で流している段は、それぞれの流れが行う）
    for (const m of plan.lowers.filter(m => !m.inRun)) {
      if (merged) {
        // 片付けるのは、取り込みの段階が実際に作業した場所（stack-sync の worktree）。決め打ちの名前にしない（#936）
        const kept = await cleanupStackLower(m, merge.syncedWorktrees?.[m.pr])
        await stackNote(m.pr, `${stackLabel(plan)}を、上の段 PR #${impl.pr} の流れで \`gh stack merge ${impl.pr} --yes --squash\` によりまとめてマージした。${kept ? `\n\nworktree: ${kept}` : ''}`)
      } else if (pointer != null && pointer !== m.pr) {
        await stackNote(m.pr, `${stackLabel(plan)}はマージしていない。論点は PR #${pointer} で扱う（上の段をマージすると下の段も一緒に入るので、スタック全体で 1 つの論点にまとめている）。この PR には needs-human を付けていない。回答と \`needs-human\` を外す操作は PR #${pointer} だけでよい。理由: ${merge.reason ?? '-'}`)
      }
    }
  }
  const { remaining, fixedUnreviewed } = lastFindings(rounds)
  return {
    issue: info.number,
    status: merged ? 'merged' : merge.status === 'stack_lower' ? 'ready' : 'needs_human',
    pr: impl.pr,
    summary: impl.summary,
    ciPassed: ci?.passed ?? null,
    reviewRounds: rounds.length,
    remainingFindings: remaining,
    fixedUnreviewedFindings: fixedUnreviewed,
    mergeReason: merge.reason ?? null,
    question,
    stack: plan.ok ? { number: plan.number, prs: plan.members.map(m => m.pr), position: plan.self.position } : null,
    stackPointer: pointer,
  }
}

// この実行にない下の段の worktree を、stack-sync が返した実際の場所で片付ける（#936）。片付けたら null、残したら理由の文を返す。
// 片付けるのは `.worktrees/issue-<番号>`・`.worktrees/stack-pr-<PR>` の形のものだけ（それ以外は人の作業場所かもしれない）で、
// この実行の流れの worktree（ブランチ名の Issue が同じ段が、その流れの場所を使った場合など）は、その流れが片付けるので触らない
const STACK_WORKTREE_RE = /[\\/]\.worktrees[\\/](issue-\d+|stack-pr-\d+)[\\/]?$/
const normPath = p => String(p).replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase()
async function cleanupStackLower(m, worktree) {
  if (!worktree) return '片付けなかった（取り込みの段階が worktree の場所を返さなかった）'
  const dirName = STACK_WORKTREE_RE.exec(worktree)?.[1]
  if (!dirName) return `片付けなかった（${worktree} は \`.worktrees/issue-<番号>\`・\`.worktrees/stack-pr-<PR>\` の形ではない）`
  if ([...prImpl.values()].some(x => normPath(x.impl.worktree) === normPath(worktree))) return null
  const own = dirName.startsWith('stack-pr-')
  const cleanup = await cleanupWorktree(
    { number: own ? m.pr : Number(dirName.slice('issue-'.length)) },
    { pr: m.pr, worktree, dirName, ...(own ? { logsDir: `.worktrees/.logs/${dirName}` } : {}) },
  )
  log(`PR #${m.pr}: この実行にない下の段の worktree の片付け（${worktree}）: ${cleanup ? (cleanup.removed ? '消した' : `残した（${cleanup.note}）`) : '片付けの段階が失敗した'}`)
  return cleanup?.removed ? null : `片付けなかった（${worktree}。${cleanup ? cleanup.note : '片付けの段階が失敗した'}）`
}

// 最後のラウンドの指摘を、直せなかったもの（remaining）と、直して push したがレビューし直していないもの（fixedUnreviewed）に分ける。
// 最後のラウンドの後にはレビューを回さないので、直したかどうかは修正のエージェントの申告（pushed）で分ける
function lastFindings(rounds) {
  const last = rounds.length ? rounds[rounds.length - 1] : null
  if (!last) return { all: [], remaining: [], fixedUnreviewed: [] }
  return last.fixed
    ? { all: last.confirmed, remaining: [], fixedUnreviewed: last.confirmed }
    : { all: last.confirmed, remaining: last.confirmed, fixedUnreviewed: [] }
}

// マージ済みの PR の worktree を片付ける（#855）。そこで動いている Gradle・GameTest のサーバーが終わってから消す。
// 終わる前に git worktree remove --force を実行すると、.gradle のロックで途中で止まり、.git だけ消えた壊れたディレクトリが残る
// impl.dirName・impl.logsDir は、Issue の番号の名前でない worktree（この実行にないスタックの下の段の `stack-pr-<PR>`。#936）のときだけ付く
function cleanupWorktree(info, impl) {
  // resume で `.worktrees/stack-pr-<PR>` を使った流れ（1 つの Issue を分けたスタックの段。prepareResume）は、その名前で java を探す
  const dirName = impl.dirName ?? STACK_WORKTREE_RE.exec(impl.worktree ?? '')?.[1] ?? `issue-${info.number}`
  const logs = impl.logsDir ?? `.worktrees/.logs/issue-${info.number}`
  return step(
    `${REPO} の PR #${impl.pr} はマージ済み。worktree ${impl.worktree} を片付ける。リポジトリのルートで作業する。
1. その worktree の Gradle と GameTest のサーバーが終わっているのを確かめる。最大 15 分待つ。${WAIT_HOW}終わったとみなすのは、次をすべて満たすとき:
   - \`${logs}/\` の \`.log\` のうち、\`-latest.log\` で終わらないものすべてに、同じ名前の \`.exit\` がある（Gradle が終わった）。
   - コマンドラインに worktree のパス（\`.worktrees/${dirName}\`）を含む java のプロセスがない。番号が前方一致する別の worktree（例: ${dirName}0 や ${dirName}4）のものを数えないよう、番号の後ろに数字が続かないものだけを数える（PowerShell の \`${worktreeJavaQuery(info.number, dirName)}\`。\`/\`・\`\\\` のどちらの書き方でも当たる）。
   - ただし \`.exit\` のない \`.log\` でも、上の java のプロセスがなく、そのログの最終更新から ${STALE_LOG_MINUTES} 分以上たっていれば、終わった（ジョブが殺されて \`.exit\` を書けなかった）とみなす。最終更新との差は \`date +%s\` と \`stat -c %Y <ログ>\` で測る。そうみなしたログの名前は note に書く。
   15 分たっても終わらなければ、消さずに removed=false で、残っているもの（ログの名前・プロセスの ID とコマンドラインの先頭）を note に書いて返す。プロセスは殺さない。\`./gradlew --stop\` は使わない（ほかの worktree のビルドまで止まる）。
2. 終わっていたら \`git worktree remove --force ${impl.worktree}\` を実行する。
3. 失敗したら、\`git worktree list --porcelain\` に載っているかを確かめる。登録が外れてディレクトリだけ残った（消えかけ）なら、\`${impl.worktree}.stale\`（もうあれば \`.stale2\`、\`.stale3\`…）に名前を変え、\`git worktree prune\` する。名前を変えられなければそのまま残す。どちらも removed=false で、残したディレクトリと理由を note に書く。
\`${logs}/\` のログは消さない。`,
    { label: `cleanup:${impl.pr}`, phase: 'Report', schema: CLEANUP_SCHEMA, ...M.report },
  )
}

// PR に結果をまとめる。自動でマージできなかったときは質問を返す。
// worktree はマージ済みのときだけ片付ける（needs_human や merge: false のときは、resume で続けるために残す。#855）
async function report(info, r) {
  const { remaining, fixedUnreviewed } = lastFindings(r.rounds)
  const ciState = !r.ci ? 'CI の確認に失敗' : r.ci.passed ? 'CI 通過' : `CI 失敗（${r.ci.failingChecks.join(', ')}）`
  const ready = r.ready
  const merged = r.merge?.status === 'merged'
  const mergeState = r.merge
    ? (merged ? `マージ済み${r.merge.retried ? `（${r.merge.reason}）` : ''}` : `マージせず（${r.merge.reason}）`)
    : MERGE ? 'マージせず（確認が必要）' : 'マージは人が行う（merge: false）'
  let cleanup = null
  if (merged) {
    cleanup = await cleanupWorktree(info, r.impl)
    log(`PR #${r.impl.pr}: worktree の片付け: ${cleanup ? (cleanup.removed ? '消した' : `残した（${cleanup.note}）`) : '片付けの段階が失敗した'}`)
  }
  const cleanupState = merged
    ? (cleanup ? (cleanup.removed ? '片付けた' : `片付けなかった（${cleanup.note}）`) : '片付けの段階が失敗した（worktree が残っている）')
    : `残した（${r.impl.worktree}。続きの作業に使う）`
  // 結果を返さなかったら 1 回だけ呼び直す（#953）。呼び直しでは、同じ見出しのコメントがもう付いていれば投稿しない
  const stalledHere = stalls.filter(x => x.issue === info.number || (x.pr != null && x.pr === r.impl.pr))
  await stepRetrying(
    `${REPO} の PR #${r.impl.pr} に、自動レビューの結果を日本語のコメントで投稿する（\`gh pr comment ${r.impl.pr} --body-file <ファイル>\`）。見出しは「## 自動レビューの結果」。内容:
- CI: ${ciState}
- レビューの回数: ${r.rounds.length}
- 最後のレビューで残った指摘（直せなかったもの）: ${remaining.length ? remaining.map(f => `[${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join(' / ') : 'なし'}
- 最後のレビューの指摘で、直して push したがレビューし直していないもの: ${fixedUnreviewed.length ? fixedUnreviewed.map(f => `[${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join(' / ') : 'なし'}
- 結論: ${ready ? 'マージ可能' : '人の確認が必要'}
- マージ: ${mergeState}
- worktree: ${cleanupState}
- 結果を返さなかった段階（#953）: ${stalledHere.length ? stalledHere.map(stallText).join(' / ') : 'なし'}
- ログ: リポジトリのルートの \`.worktrees/.logs/issue-${info.number}/\`
worktree は消さない（片付けはこのスクリプトが別に行う）。`,
    { label: `report:${r.impl.pr}`, phase: 'Report', ...M.report },
    `投稿の前に \`gh pr view ${r.impl.pr} --json comments\` で確かめ、見出しが「## 自動レビューの結果」のコメントがこの実行でもう付いていれば（内容が下と同じなら）、投稿しない。`,
  )
  if (merged || (!MERGE && ready)) return null
  // スタック（#936）で、論点がもう別の PR にある（か、上の段の流れがまとめてマージする）ときは、新しく聞かない（スタックにつき質問は 1 つ）
  if (r.merge?.noQuestion) {
    if (r.merge.pointerPr != null && r.merge.pointerPr !== r.impl.pr) {
      await stackNote(r.impl.pr, `この PR はマージしていない。論点は PR #${r.merge.pointerPr} で扱う（スタック全体で 1 つの論点にまとめている）。この PR には needs-human を付けていない。回答と \`needs-human\` を外す操作は PR #${r.merge.pointerPr} だけでよい。理由: ${r.merge.reason ?? '-'}`)
    }
    return null
  }

  const reasons = []
  if (!r.ci || !r.ci.passed) reasons.push(`CI が通らない: ${ciState}`)
  for (const f of remaining.filter(f => f.severity === 'must')) {
    reasons.push(`レビューの指摘が直らなかった: ${f.file}:${f.line ?? '-'} ${f.problem}`)
  }
  for (const f of fixedUnreviewed.filter(f => f.severity === 'must')) {
    reasons.push(`最後のラウンドで直して push したが、レビューし直していない must の指摘: ${f.file}:${f.line ?? '-'} ${f.problem}`)
  }
  if (r.unverified) reasons.push(`直しを確かめられなかった: ${r.unverified}`)
  if (r.unpushed) reasons.push(`レビューの直しを push できなかった: ${r.unpushed}`)
  if (r.merge && r.merge.status !== 'merged') reasons.push(`マージできなかった: ${r.merge.reason}`)
  const question = `自動ではマージできなかった。どう進めるか判断してほしい（直し方の指示、このままマージ、PR を閉じる など）:\n${reasons.map(x => `- ${x}`).join('\n')}`
  // この段階ではもう直す機会がないので、確かめた結果を添えて聞く
  const e = await escalate('pr', r.impl.pr, question, r.escalateContext ?? `PR の CI・レビュー・マージの段階（ブランチ ${r.impl.branch}）`, false)
  return e.question
}

// ---- 1 Issue の流れ ----

const done = new Map()

async function runIssue(info) {
  // 同じ実行の中の依存先はマージを待つ。それ以外の閉じていない依存があれば着手しない
  for (const dep of info.openDeps) {
    const d = done.get(dep)
    if (!d) return { issue: info.number, status: 'blocked', reason: `依存 #${dep} が閉じていない` }
    const res = await d.promise
    if (res.status !== 'merged') return { issue: info.number, status: 'blocked', reason: `依存 #${dep} がマージされていない` }
  }
  const core = await runIssueLocked(info)
  // スタック（#936）のマージは、slots と area のロックを放してから行う（下の段は上の段の結果を待ち、上の段は下の段の知らせを待つので、
  // ロックを持ったまま待つと、相手やほかの Issue が進めない）
  if (core && core.stackDeferred) return runStack(info, core.stackDeferred)
  return core
}

async function runIssueLocked(info) {
  // area のロックを先に取り、そのあとで slots を取る（#986）。slots を先に取ると、area を待つ流れが slot を持ったまま止まり、
  // 別の area の Issue が slot を取れない。全部の流れが「area をソートした順 → slots」の同じ順で取るので、詰まらない
  const areas = lockAreas(info.areas)
  for (const a of areas) await areaLock(a).acquire()
  await slots.acquire()
  try {
    // 止まった PR の続き（resume）は、助言・プラン・実装を飛ばして CI から進める
    let impl = info.resumeImpl ?? null
    const advice = impl ? null : await advise(info)
    let approvedPlan = null
    if (!impl && info.needsPlan) {
      const p = await plan(info, advice)
      if (!p.ok) return { issue: info.number, status: 'needs_human', reason: p.reason, question: p.reason }
      approvedPlan = p.plan
    }

    if (!impl) impl = await implement(info, approvedPlan, null, advice)
    // 着手の前に閉じていた Issue は、人に聞かずに飛ばす
    if (impl && impl.status === 'already_done') return { issue: info.number, status: 'skipped', reason: impl.summary, question: null }
    if (impl && impl.status === 'blocked') {
      const e = await escalate('issue', info.number, impl.summary, '実装の段階で、人が決めることか未決の decision に当たって止まった', true)
      if (!e.guidance) return { issue: info.number, status: 'needs_human', reason: e.question, question: e.question }
      impl = await implement(info, approvedPlan, e.guidance, advice)
      if (impl && impl.status === 'blocked') {
        const again = await escalate('issue', info.number, impl.summary, '実装の段階で、確かめの進め方に従ってもまた止まった', false)
        return { issue: info.number, status: 'needs_human', reason: again.question, question: again.question }
      }
    }
    if (impl && impl.status === 'gradle_pending') {
      return escalateUnverified(info, impl.pr ?? null, impl.summary, '実装の段階（Gradle の終わりを待つやり直しのあと）')
    }
    if (!impl || impl.status !== 'pr_opened' || !impl.pr) {
      return { issue: info.number, status: 'failed', reason: impl?.summary ?? '実装の段階が失敗した', question: null }
    }
    prIssue.set(impl.pr, info.number)
    issuePr.set(info.number, impl.pr)
    prImpl.set(impl.pr, { info, impl })

    // 実装のエージェントが PR に残した確認事項は、マージの前に確かめる。人の判断が要るならマージせずに止まる
    if (impl.openQuestions?.length) {
      const listed = impl.openQuestions.map(x => `- ${x}`).join('\n')
      const q = `実装のエージェントが PR に確認事項を残した:\n${listed}`
      const e = await escalate('pr', impl.pr, q, `実装の段階で PR を作ったあと（ブランチ ${impl.branch}）`, true)
      if (!e.guidance) return { issue: info.number, status: 'needs_human', pr: impl.pr, reason: e.question, question: e.question }
      // push しないのが正しい回もある（コードを変える必要がない）ので、理由の文は見ずに申告だけで見分ける（#904）
      const { result: applied, stillPending: appliedPending } = await withGradleWait(
        `PR #${impl.pr} の確認事項の反映`, info.number, impl.worktree,
        note => step(
          `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）に、実装のエージェントが確認事項を残した。人に聞く前の確かめ（別のモデル）が、人を待たずに進められると判断し、次の進め方を示した。これに従う。${note ? `\n${note}` : ''}
コードを変える必要があれば worktree の中で直し、下の「手元の確認」を通してからコミットして push する（通しの確認は push の後の CI が行う）。変える必要がなければ push しない（Gradle も起動しない）。
${FIX_LOCAL_CHECKS}
${gradleRules(info.number, note ? 'resolve-wait' : 'resolve')}
どちらの場合も、PR 本文の「確認してほしいこと」を、確かめた結果と結論（決めたこととその根拠）に書き換える（\`gh pr edit ${impl.pr} --body-file <ファイル>\`）。
${unpushedReportStep(impl.branch)}

確認事項:
${listed}

進め方:
${e.guidance}`,
          { label: `${note ? 'resolve-wait' : 'resolve'}:${impl.pr}`, phase: 'Fix', schema: FIX_SCHEMA, ...M.fixFirst },
        ),
        r => gradlePending(r, r?.summary, false),
      )
      if (!applied) return { issue: info.number, status: 'failed', pr: impl.pr, reason: '確認事項の反映に失敗した', question: null }
      if (appliedPending) {
        return escalateUnverified(info, impl.pr, `${pendingReason('確認事項の反映の段階', applied)}。最後の報告: ${applied.summary}`, `確認事項の反映の段階（ブランチ ${impl.branch}。Gradle の終わりを待つやり直しのあと）`)
      }
      if (applied.unpushedCommits === true) {
        // 直しを PR に載せられていない。CI もレビューも GitHub 上の差分しか見ないので、このまま進めると、ローカルにしかない直しを
        // マージの段階が押し出してしまう（#1007）。マージの段階の確かめでも止まるが、その前の CI・レビューを無駄に回さない
        const reason = `確認事項の反映の段階で、コミットしたが push できなかったコミットが worktree ${impl.worktree} に残っている（レビューも CI も通っていない）。最後の報告: ${applied.summary}`
        const question = `確認事項の反映を PR に載せられなかった。どう進めるか判断してほしい（人が push してから resume する、ローカルのコミットを捨てる など）:\n- ${reason}`
        const e2 = await escalate('pr', impl.pr, question, `確認事項の反映の段階（ブランチ ${impl.branch}）。push できなかったコミットが残った`, false)
        return { issue: info.number, status: 'needs_human', pr: impl.pr, reason, question: e2.question }
      }
    }

    const tag = `#${info.number}`
    let ci = await untilGreen(info, impl, tag)
    const rounds = []
    let unverified = null
    let unpushed = null
    for (let round = 1; ci && ci.passed && round <= MAX_REVIEW_ROUNDS; round++) {
      const confirmed = await review(info, impl, round)
      // 検証で残った指摘は must・should を問わず直す（should をマージ前に止めるため）
      const toFix = confirmed
      const entry = { round, confirmed, fixed: false }
      rounds.push(entry)
      // レビューできなかったときは、直すものがない。must として残し、マージを止める
      if (confirmed.some(f => f.reviewFailed)) break
      if (!toFix.length) break
      log(`PR #${impl.pr}: 指摘 ${toFix.length} 件を修正（${round}/${MAX_REVIEW_ROUNDS}）`)
      const { result: fixed, stillPending: fixPending } = await withGradleWait(
        `PR #${impl.pr} のレビュー指摘の修正（${round}）`, info.number, impl.worktree,
        note => step(
          `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）へのレビュー指摘を直す。worktree の中で修正し、下の「手元の確認」を通してから、コミットして push する（通しの確認は push の後の CI が行う）。${note ? `\n${note}` : ''}
Issue にし忘れた別の問題は、コードを直す代わりに新しい Issue にして適切な Epic のサブ Issue にし（AGENTS.md の手順）、PR 本文に番号を書く。
${FIX_LOCAL_CHECKS}
${gradleRules(info.number, `fix${round}${note ? '-wait' : ''}`)}
${unpushedReportStep(impl.branch)}
${toFix.map((f, i) => `${i + 1}. [${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join('\n')}`,
          { label: `fix${note ? '-wait' : ''}:${impl.pr}#${round}`, phase: 'Fix', schema: FIX_SCHEMA, ...(round === 1 ? M.fixFirst : M.fixAgain) },
        ),
        r => gradlePending(r, r?.summary, r?.pushed === false),
      )
      if (fixed && fixPending) {
        // 直しのビルドを確かめていないので、直ったことにせず、マージを止める
        unverified = pendingReason(`レビュー指摘の修正（${round}）の段階`, fixed)
        break
      }
      if (fixed && fixed.unpushedCommits === true) {
        // コミットしたが push できなかった（#1007）。直しは PR に載っておらず、レビューも CI も通っていない。前の回の CI で ready にすると、
        // 「マージ可能」の記録が付き、後の実行のマージの段階や stack-sync がそのコミットを押し出す
        unpushed = `レビュー指摘の修正（${round}）の段階で、コミットしたが push できなかったコミットが worktree ${impl.worktree} に残っている（レビューも CI も通っていない）。最後の報告: ${fixed.summary}`
        break
      }
      if (!fixed || !fixed.pushed) break
      entry.fixed = true
      ci = await untilGreen(info, impl, `${tag}-r${round}`)
    }

    // マージの条件は変えない: 最後のラウンドの must は、直して push していてもレビューし直していないので止める
    const { all: lastConfirmed, remaining, fixedUnreviewed } = lastFindings(rounds)
    const ready = ci && ci.passed && !unverified && !unpushed && !lastConfirmed.some(f => f.severity === 'must')
    // スタックの上の段が待つ「マージしてよいところまで進んだか」は、マージの段階に入る前（mergeLock を取る前）に知らせる（#936）
    const signal = (ok, reason) => readySignals.get(info.number)?.resolve({ ready: ok, reason: ok ? null : reason })
    const notReady = ci && !ci.passed ? 'CI が通っていない' : unverified ? '直しを確かめられなかった' : unpushed ? 'レビューの直しを push できなかった' : 'レビューの must が残った'
    let stackUnknown = null
    if (MERGE && ready) {
      // マージの前に、スタックの PR か（base が main でないか）を確かめる（実装の後で積まれることがあるので、実装のときの値は使わない）。
      // 判定の段階が結果を返さなかったら（呼び直しも）、マージしない。ふつうのマージの段階は、手順 4 で .stack を確かめる前に
      // main を取り込んで push・CI まで進むので、上の段なら main を上の段にだけ入れてしまう（#924 の形）
      const st = await detectStack(impl.pr, `stack-detect:${impl.pr}`)
      if (!st) {
        stackUnknown = `マージの前に、PR #${impl.pr} がスタック（Stacked PRs）に属するかを確かめられなかった（判定の段階が、呼び直しでも結果を返さなかった）。スタックの段なら、ふつうのマージの段階は main をこの段にだけ取り込んで push してしまうので、取り込みもマージもしていない。resume でもう 1 回流す`
        log(`PR #${impl.pr}: スタックの判定が結果を返さなかった。マージしない`)
      } else if (st.stackNumber != null || (st.baseRef && st.baseRef !== 'main')) {
        signal(true)
        return { stackDeferred: { impl, ci, rounds, ready, unverified, unpushed, stack: st } }
      }
    }
    // 判定できなかった段は「マージしてよい」と知らせない（同じ実行の上の段がスタックごとマージし、この流れの結果と食い違わないように）
    signal(!!ready && !stackUnknown, stackUnknown ? 'スタックの判定ができなかった' : notReady)
    const merge = MERGE && ready
      ? (stackUnknown ? { status: 'needs_human', failedStep: 'merge', reason: stackUnknown } : await mergePr(info, impl))
      : null

    const result = { impl, ci, rounds, merge }
    const question = await report(info, { ...result, ready, unverified, unpushed })
    return {
      issue: info.number,
      status: merge?.status === 'merged' ? 'merged' : ready ? 'ready' : 'needs_human',
      pr: impl.pr,
      summary: impl.summary,
      ciPassed: ci?.passed ?? null,
      reviewRounds: rounds.length,
      remainingFindings: remaining,
      fixedUnreviewedFindings: fixedUnreviewed,
      mergeReason: merge?.reason ?? null,
      question,
    }
  } finally {
    slots.release()
    for (const a of areas.reverse()) areaLock(a).release()
  }
}

// ---- 実行 ----

const RESUME_SCHEMA = {
  type: 'object',
  properties: {
    ok: { type: 'boolean', description: 'PR が開いていて、worktree を用意できたら true' },
    title: { type: 'string', description: 'Issue のタイトル' },
    areas: { type: 'array', items: { type: 'string' }, description: '`area: ` を除いた名前' },
    branch: { type: ['string', 'null'] },
    worktree: { type: ['string', 'null'], description: 'worktree の絶対パス' },
    publicApiChanged: { type: 'boolean' },
    touchesConcurrency: { type: 'boolean' },
    summary: { type: 'string' },
    baseRef: { type: ['string', 'null'], description: 'PR の base のブランチ（`baseRefName`）。省略は main（#936）' },
  },
  required: ['ok', 'title', 'areas', 'branch', 'worktree', 'publicApiChanged', 'touchesConcurrency', 'summary'],
}

// 止まった PR の続きのために、worktree を用意して実装の結果と同じ形にする。
// needs-human のラベルは触らない（外すのは人の指示を受けた起動側）
async function prepareResume({ issue, pr }) {
  const r = await step(
    `${REPO} の PR #${pr}（Issue #${issue}）を、途中で止まったところから自動の流れで続けるために用意する。
1. \`gh pr view ${pr} --json state,headRefName,baseRefName,body,labels\`（baseRef に \`baseRefName\` を入れる）と \`gh issue view ${issue} --json title,labels\` を読む。PR が OPEN でないか、PR か Issue に \`needs-human\` ラベルが付いていれば ok=false にして、理由を summary に書く。
2. リポジトリのルートで \`git fetch origin\` し、作業する worktree を次の順に決める（#936。1 つの Issue を複数の PR に分けたスタックでは、\`.worktrees/issue-${issue}\` が別の段のブランチを開いていることがある。そこで続けると、レビューは別のブランチの木を読み、直しは別の段のブランチに push される）:
   a. \`git worktree list --porcelain\` に \`branch refs/heads/<headRefName>\` を開いた worktree があれば、それを使う（同じブランチは 2 つの worktree で開けない）。
   b. なければ \`.worktrees/issue-${issue}\`。なければ \`git worktree add .worktrees/issue-${issue} <headRefName>\`（ローカルのブランチがなければ \`origin/<headRefName>\` から作る）。あれば、${worktreeCheck(issue)} 本物の worktree で、開いているブランチ（\`git -C .worktrees/issue-${issue} branch --show-current\`）が <headRefName> なら使う。
   c. \`.worktrees/issue-${issue}\` が別のブランチを開いた本物の worktree なら、それは別の段（別の流れ）のものなので触らず、ブランチも切り替えず、\`.worktrees/stack-pr-${pr}\` を使う（なければ \`git worktree add .worktrees/stack-pr-${pr} <headRefName>\`。あれば、本物の worktree か・<headRefName> を開いているかを同じように確かめ、違えば ok=false で理由を返す）。
   使う worktree で \`git -C <worktree> pull --ff-only\` して先頭に合わせ、worktree にその絶対パス、branch に <headRefName> を入れる。
3. 差分（\`gh pr diff ${pr}\`）から、publicApiChanged（\`public\` な宣言、特に \`pro.ikili.kibicomputers.api\` 以下を足した・変えた）と touchesConcurrency（スレッド・ロック・非同期の完了待ちの振る舞いを変えた）を判断する。summary には PR 本文の要約を書く。
コードは変更しない。`,
    { label: `resume:#${issue}`, phase: 'Select', schema: RESUME_SCHEMA, ...M.select },
  )
  if (!r || !r.ok) {
    log(`PR #${pr}（#${issue}）は続けられない: ${r?.summary ?? '用意に失敗した'}`)
    return null
  }
  return {
    number: issue,
    title: r.title,
    areas: r.areas,
    openDeps: [],
    blockedReason: null,
    needsPlan: false,
    resumeImpl: {
      status: 'pr_opened', pr, branch: r.branch, worktree: r.worktree,
      publicApiChanged: r.publicApiChanged, touchesConcurrency: r.touchesConcurrency, summary: r.summary, baseRef: r.baseRef ?? null,
      // 止まった PR の確認事項は、人が回答して needs-human を外してから続けるので、ここでは確かめ直さない
      openQuestions: [],
    },
  }
}

phase('Select')
// resume に同じ Issue の PR が 2 つ以上あると（1 つの Issue を複数の PR に分けたスタックの段など）、どれも流さない（#936）。
// 流れの状態（done・readySignals・infoByNumber・resumePrOf）は Issue の番号で引くので、2 つの流れが同じ知らせを共有し、
// 上の段が自分の出した知らせを下の段のものと読んで、下の段の CI・レビューを待たずにスタックごとマージしてしまう。worktree も同じ場所を取り合う
const resumeCount = new Map()
for (const x of RESUME) resumeCount.set(x.issue, (resumeCount.get(x.issue) ?? 0) + 1)
const resumeDup = [...resumeCount].filter(([, n]) => n > 1).map(([issue]) => {
  const prs = RESUME.filter(x => x.issue === issue).map(x => x.pr)
  log(`resume に Issue #${issue} の PR が複数ある（${prs.map(p => `PR #${p}`).join('・')}）。どれも流さない`)
  return {
    number: issue, title: null, areas: [], openDeps: [], needsPlan: false, prs,
    blockedReason: `resume に Issue #${issue} の PR が複数ある（${prs.map(p => `PR #${p}`).join('・')}）。1 回の resume では、1 つの Issue につき PR を 1 つだけ渡す（流れの状態を Issue の番号で引くため、2 つを同時に流すと、上の段が下の段の CI・レビューを待たずにマージしうる）。どれも流していない。1 つの Issue を分けたスタックなら、下の段の PR を 1 つずつ別の起動で resume してレビューを通し（「## 自動レビューの結果」が付く。上の段がその実行にないので、その段だけではマージしない）、最後に一番上の段の PR を resume すると、スタックごとマージする`,
  }
})
const selected = RESUME.length
  ? { issues: [...resumeDup, ...(await parallel(RESUME.filter(x => resumeCount.get(x.issue) === 1).map(x => () => prepareResume(x)))).filter(Boolean)] }
  : await select()
const infos = (selected?.issues ?? []).filter(i => {
  if (i.blockedReason) log(`#${i.number} は着手しない: ${i.blockedReason}`)
  return !i.blockedReason
})
if (!infos.length) {
  log('着手できる Issue がない')
  return { results: [], skipped: selected?.issues ?? [], stalled: stalls }
}
for (const i of infos) {
  done.set(i.number, deferred())
  readySignals.set(i.number, deferred())
  infoByNumber.set(i.number, i)
  // resume の PR は、流れが登録する前から、この実行の PR と分かるようにする（スタックの段の判定。flowOfPr）
  if (i.resumeImpl?.pr != null) {
    resumePrs.set(i.resumeImpl.pr, i.number)
    resumePrOf.set(i.number, i.resumeImpl.pr)
  }
}

const results = await parallel(infos.map(info => async () => {
  let r
  try {
    r = await runIssue(info)
  } catch (e) {
    r = { issue: info.number, status: 'failed', reason: String(e) }
  }
  // 結果を返さなかった段階（#953）。PR の番号しか分からない段階（取り込み・マージなど）も、PR で結び付ける
  r = { ...r, stalledStages: stalls.filter(x => x.issue === info.number || (r.pr != null && x.pr === r.pr)) }
  // マージしてよいところまで進む前に終わった道でも、スタックの上の段が待ち続けないように知らせる（もう知らせていれば何も起きない）
  readySignals.get(info.number).resolve({ ready: false, reason: r.reason ?? r.status })
  done.get(info.number).resolve(r)
  return r
}))

const finished = results.filter(Boolean)
return {
  results: finished,
  // 起動したセッションは、これをチャットでユーザーに聞く
  questions: finished.filter(r => r.question).map(r => ({ issue: r.issue, pr: r.pr ?? null, question: r.question })),
  skipped: (selected?.issues ?? []).filter(i => i.blockedReason),
  // 結果を返さなかった段階（#953）。retried: 1 回だけ呼び直したか、recovered: 呼び直しで結果が返ったか
  stalled: stalls,
  ...(FABLE ? { comparison: comparisons } : {}),
}
