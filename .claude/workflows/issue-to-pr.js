export const meta = {
  name: 'issue-to-pr',
  description: 'Issue を worktree で実装して PR を作り、CI とレビューエージェントで確認・修正してマージする。人の確認が要るものは質問して止まる',
  whenToUse: 'KiBi Computers の Issue を自動で進めたいとき。args は Issue 番号・番号の配列・{ issues, window, merge }。省略するとエージェントが選ぶ。merge: false でマージしない。resume: [{ issue, pr }] で止まった PR を CI から続ける。',
  phases: [
    { title: 'Select', detail: '対象の Issue と依存・area・プランの要否を調べる' },
    { title: 'Plan', detail: 'ABI・保存形式・公開 API に関わる Issue だけ、プランを書いてレビューする' },
    { title: 'Implement', detail: 'worktree で実装し、build と両バージョンの GameTest が通るまで直して PR を作る' },
    { title: 'CI', detail: 'PR の CI を待つ。main と衝突していたら取り込む。落ちたら直す' },
    { title: 'Review', detail: '正しさ・ルール違反のレビュー、公開 API の Java 相互運用レビュー、指摘の検証' },
    { title: 'Fix', detail: '確かな指摘を直して CI を待ち直す' },
    { title: 'Merge', detail: '1件ずつ main を取り込み、確認してからマージする。過負荷で GameTest だけが落ちたら 1 回だけ回し直す。Gradle を待たずに返した段階は、待つ指示を付けて 1 回だけやり直す（merge: false なら飛ばす）' },
    { title: 'Report', detail: 'PR に結果をまとめる。人の確認が要るものは Fable が前提と推奨を確かめてから質問して needs-human を付ける。マージ済みなら、Gradle が終わってから worktree を片付ける' },
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
// マージの段階で GameTest だけが落ちたとき、過負荷とみなして 1 回だけ回し直すしきい値（#855）。
// ふだんの起動でも、ワールドを読んだ直後に「Can't keep up!」が 1 回出ることがあるので、出たかどうかでなく大きさで決める。
// 手元の worktree の latest.log の最大値（約 40 件）は、ふだんは NeoForge 1.21.1 が 70〜110 tick、Forge 1.20.1 が 55〜141 tick（最大 189）で、
// 過負荷と分かる回は 350 tick 以上だった（11/20〜21 の過負荷では最大 32,800 tick 遅れ、ふだん 1.5〜2.5 分の NeoForge の GameTest が 1 時間を超えた）。
// ふだんの最大（189）と過負荷の最小（350）の間に置く。ふだんの値に近すぎると、少し重いだけの回の本物の失敗まで回し直しに入る
const OVERLOAD_TICKS_BEHIND = 300
const OVERLOAD_GAMETEST_SECONDS = 600

// Gradle を動かす段階に共通の決まり（#855）。ログは worktree の外（.worktrees/.logs/issue-<n>/）に残し、片付けで消えないようにする。
// GameTest の同時実行は Gradle の側（build-logic の kibicomputers.gametest-lock）で止めるので、ここでは待ち方だけを書く
function gradleRules(number, stage) {
  const logs = `.worktrees/.logs/issue-${number}`
  return `Gradle（\`./gradlew\`）の動かし方（#855。必ず守る）:
- \`./gradlew\` は Bash の \`run_in_background\` で起動する。出力はリポジトリのルートの \`${logs}/\`（worktree の外）に \`${stage}-<内容>-<連番>.log\` として書き、終わったら終了コードを同じ名前の \`.exit\` に書く。
- **ログの名前は、毎回まだ使っていないものにする（例の \`-1\` をそのまま使わない）。** 同じ Issue の中では段階の名前が重なる（CI の直しや衝突の取り込みは呼ばれるたびに同じ名前で、確かめのあとの再実装や resume でも同じ段階をもう一度通る）。前の回の \`.exit\` が残っている名前を使うと、「\`.exit\` ができるまで待つ」が動いている最中にすぐ満たされ、古い終了コードを読んでしまう。起動の前に前景で、\`.log\`・\`.exit\` のどちらもない連番を選ぶ: \`d=<ルート>/${logs}; mkdir -p $d; n=1; while [ -e $d/${stage}-build-$n.log ] || [ -e $d/${stage}-build-$n.exit ]; do n=$((n+1)); done; echo $d/${stage}-build-$n\`。出た名前で起動する: \`cd <worktree> && (./gradlew build --console=plain > <名前>.log 2>&1; echo $? > <名前>.exit)\`
- \`.exit\` ができるまで、Monitor などで待つ（前景の \`sleep\` は使えない）。**Gradle が終わって終了コードを読むまで、この段階を終えて返さない。** バックグラウンドで動かしたまま返さない。
- GameTest（\`runGameTestServer\`・\`runJarGameTestServer\`・\`runLoadTestServer\`）は、マシンの中で同時に 1 本だけ動く（Gradle がユーザーホームの \`kibicomputers/locks/gametest.lock\` を取る）。ほかが動いていれば「Waiting for the GameTest lock ... Held by: ...」を 30 秒ごとに出して待つので、止めずに待つ。待ちの時間は失敗ではない。
- GameTest が落ちたら、\`<worktree>/platform-*/run/logs/latest.log\` を \`${logs}/${stage}-<内容>-<連番>-<バージョン>-latest.log\` に写す（worktree を片付けても調べられるように）。
- \`./gradlew --stop\` は使わない（デーモンはほかの worktree と共有しているので、そちらのビルドまで止まる）。
- **依存の取得の失敗（コードと関係のない、ネットワークの層の失敗）は、同じ内容で 1 回だけ回し直す**（#904）。当たるのは、\`UnknownHostException\`、名前解決の失敗（Windows では「そのようなホストは不明です。 (maven.neoforged.net)」のように日本語で出る）、\`Connection refused\`・\`Connection reset\`、\`Read timed out\`・\`connect timed out\`、HTTP の 5xx、原因がこれらの \`Could not GET\`・\`Could not HEAD\`・\`Could not resolve\`。回し直しは新しい連番で起動し、その \`.exit\` を待つ。2 回目も同じように落ちたら、それ以上は回さず、「依存の取得の失敗（ネットワーク）が 2 回続いた」と理由に書いて、ビルドの失敗として返す。\`Could not find <座標>\`（404。版の誤り）、Zig・Lua・wasi-sdk などの SHA-256 の不一致、コンパイルやテストの失敗は、回し直さない（本物の失敗）。
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
// 「やり直しは返す時点で :core:test を実行中で、終了コードを読めていない」「スキーマに『未完了』がないため needs_human で返す」）。
// 本物の失敗まで拾わないよう、失敗の理由として読む文（マージの build の失敗、取り込みの build_failed、push しなかった修正）にだけ当て、
// さらに Gradle が終わったあとの失敗の証拠（GameTest のロックの待ちの上限、タイムアウト、サーバーが落ちた、など）を含む文は外す
const GRADLE_UNFINISHED_TEXT = /終了コードを(まだ)?(読めて|読んで|確かめられて|確かめて|確認できて|確認して)いない|\.exit\s*(が|は)?(まだ)?(ない|できていない|書かれていない)|(gradle|ビルド|build|GameTest|テスト|test|:[\w.-]+:\w+)[^。\n]{0,30}?(を|が|は)?(まだ)?(実行中|動いている|走っている|終わっていない|終わる前)|スキーマに「?未完了|still running|not (yet )?finished/i

// Gradle が終わったあとの本物の失敗の証拠。理由の文にこれがあれば、「実行中」「終わる前」などを含んでいても未完了とは見ない
// （例: GameTest のロックの待ちの上限に達した理由に「Held by: ...runGameTestServer in .worktrees/issue-900 が実行中のまま」と添えた回、
// 「GameTest が時間内に終わっていない（タイムアウト）」、「testFoo が終わる前にサーバーが落ちた」）
const GRADLE_FAILURE_EVIDENCE = /Held by|Waiting for the GameTest lock|ロック|上限|タイムアウト|time[ds]? ?out|落ち(た|て)|クラッシュ|crash|required tests failed|BUILD FAILED|FAILED\b|Could not (GET|HEAD|resolve|find)|UnknownHost/i

// 返り値が「Gradle を待たずに返した」か。textEligible のときだけ text も見る。当たらなければ null
function gradlePending(r, text, textEligible) {
  if (!r) return null
  const logs = Array.isArray(r.runningLogs) ? r.runningLogs : []
  if (r.gradleStillRunning === true) return { by: 'flag', logs, text: text ?? '' }
  if (textEligible && text && !GRADLE_FAILURE_EVIDENCE.test(text) && GRADLE_UNFINISHED_TEXT.test(text)) return { by: 'text', logs, text }
  return null
}

// コマンドラインに worktree のパスを含む java のプロセスを探す PowerShell のコマンド（#855）。
// 番号が前方一致する別の worktree（issue-<n>0 など）のものを数えないよう、番号の後ろに数字が続かないものだけを数える
function worktreeJavaQuery(number) {
  return `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match '[\\\\/]\\.worktrees[\\\\/]issue-${number}(?![0-9])' }`
}

// Gradle を待たずに返した段階を、もう 1 回だけ呼ぶときに付ける指示（#904）。
// 返したときにバックグラウンドのシェルが止まり、前の回の .exit は二度とできないことがある（Gradle の java だけが孤児として最後まで走る）。
// なので前の回の .exit は待たせず、孤児の Gradle が終わるのを待ってから、新しい連番で起動させる
const ORPHAN_WAIT_MINUTES = 30
function gradleWaitNote(number, worktree, pending) {
  const logs = `.worktrees/.logs/issue-${number}`
  return `**やり直し（#904）**: 前回のこの段階は、Gradle の終わり（\`.exit\`）を待たずに返した（動いていたログ: ${pending.logs.length ? pending.logs.join(', ') : '申告なし'}。前回の理由: ${pending.text || '-'}）。この段階を手順の最初からもう 1 回だけやり直す。次を守る:
1. 前の回のログの \`.exit\` は待たない。返したときにバックグラウンドのシェルが止まるので、\`.exit\` は二度とできないことがある。
2. 自分の \`./gradlew\` を起動する前に、前の回の Gradle が終わるのを最大 ${ORPHAN_WAIT_MINUTES} 分、Monitor などで待つ（前景の \`sleep\` は使えない）。終わったとみなすのは、次のどちらも満たすとき:
   - コマンドラインに worktree のパス（${worktree ?? `.worktrees/issue-${number}`}）を含む java のプロセスがない（PowerShell の \`${worktreeJavaQuery(number)}\`）。
   - \`${logs}/\` の \`.exit\` のないログ（\`-latest.log\` で終わるものは除く）が、どれも最終更新から ${STALE_LOG_MINUTES} 分以上たっている（\`date +%s\` と \`stat -c %Y <ログ>\` で測る）。
   プロセスは殺さない。\`./gradlew --stop\` も使わない。同じ worktree で Gradle を 2 本動かすと、\`.gradle\` のロックや \`build/\`・\`run/\` の出力、GameTest のロックを取り合う。${ORPHAN_WAIT_MINUTES} 分たっても終わらなければ、自分の Gradle は起動せず、\`gradleStillRunning=true\` で返す。
3. 前の回のログに結果（BUILD SUCCESSFUL など）が出ていても、それは参考にとどめる。新しい連番（\`.log\`・\`.exit\` のどちらもない名前）で、この段階の手順どおりに起動し直し、その \`.exit\` を読むまで返さない。
4. 前の回の作業の続き（コミット済みで push していない取り込みや修正）は、そのまま使う（reset・force push はしない）。\`git merge origin/main\` をもう一度実行して「Already up to date」になっても問題ない。
5. 今回も Gradle を待たずに返すことになったら、\`gradleStillRunning=true\` で返す（このスクリプトはもうやり直さない。この段階は「確かめられなかった」失敗として扱われ、先へ進まずに止まって人に上がる）。`
}

// Gradle を動かす段階を呼び、Gradle を待たずに返したら、待つ指示を付けて 1 回だけ呼び直す（#904）。
// 呼び直すかどうかはスクリプトが決める（エージェントに任せない）。回数の上限は呼び出し 1 か所ごとに 1 回。
// run(note) は note（null か、やり直しの指示）を受けてエージェントを呼ぶ。pendingOf(r) は gradlePending の結果を返す。
// 返り値の stillPending は、やり直しでも待たずに返したとき true。呼ぶ側はこの結果を成功として先へ進めず、失敗として止める（pendingReason を理由に使う）。
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
const areaLocks = new Map()
function areaLock(area) {
  if (!areaLocks.has(area)) areaLocks.set(area, semaphore(1))
  return areaLocks.get(area)
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
  },
  required: ['status', 'pr', 'branch', 'worktree', 'publicApiChanged', 'touchesConcurrency', 'summary', 'openQuestions'],
})

const CI_SCHEMA = {
  type: 'object',
  properties: {
    passed: { type: 'boolean' },
    failingChecks: { type: 'array', items: { type: 'string' } },
    failureLog: { type: 'string', description: '失敗の原因が分かるログの抜粋（通ったら空）' },
    mergeable: { type: 'string', enum: ['MERGEABLE', 'CONFLICTING', 'UNKNOWN'], description: '`gh pr view --json mergeable` の値' },
    noChecks: { type: 'boolean', description: '待っても PR にチェックが 1 つも出なかったら true' },
  },
  required: ['passed', 'failingChecks', 'failureLog', 'mergeable', 'noChecks'],
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

const MERGE_SCHEMA = withGradlePending({
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['merged', 'needs_human'] },
    reason: { type: ['string', 'null'] },
    failedStep: { type: ['string', 'null'], enum: ['conflict', 'build', 'gametest', 'ci', 'merge', null], description: '止まった手順（マージしたら null）' },
    gameTestFailedOnly: { type: 'boolean', description: 'build は通り、GameTest のサーバーが起動してテストが落ちた（「N required tests failed」）ときだけ true。サーバーの起動前の失敗（コンパイル、依存の取得、ワールドを消せない、ロックの待ちの上限）は false' },
    gameTestSeconds: { type: ['integer', 'null'], description: 'Gradle の出力の「GameTest lock released by ... after holding it for N s」のうち、最大の N（ロックの待ちは含まない）。なければ null' },
    maxTicksBehind: { type: ['integer', 'null'], description: '両バージョンの latest.log の「Can\'t keep up! ... Running Nms or N ticks behind」の N（tick）の最大。出ていなければ 0、ログがなければ null' },
    logPath: { type: ['string', 'null'], description: 'worktree の外に残したログの置き場所' },
  },
  required: ['status', 'reason', 'failedStep', 'gameTestFailedOnly', 'gameTestSeconds', 'maxTicksBehind', 'logPath'],
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
    : `\`.claude/skills/work-issue/SKILL.md\` の手順 0 に従って、着手できる Issue を最大 ${WINDOW} 件選ぶ。同じ area の Issue は1件までにする。`
  return agent(
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
  if (!FABLE) return (await agent(prompt, { label: `advise:#${info.number}`, phase: 'Plan', ...M.advise })) || null
  const [fable, opus] = await parallel([
    () => agent(prompt, { label: `advise-fable:#${info.number}`, phase: 'Plan', ...M.fable }),
    () => agent(prompt, { label: `advise-opus:#${info.number}`, phase: 'Plan', ...M.crossCheckShadow }),
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
    const p = await agent(
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
      () => agent(reviewPrompt, { label: `plan-review:#${info.number}#${round}`, phase: 'Plan', schema: PLAN_REVIEW_SCHEMA, ...M.planReview }),
      ...(FABLE ? [() => agent(reviewPrompt, { label: `plan-review-fable:#${info.number}#${round}`, phase: 'Plan', schema: PLAN_REVIEW_SCHEMA, ...M.fable })] : []),
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
    () => agent(checkPrompt, { label: `cross-check:${kind}#${number}`, phase: 'Report', schema: CROSS_CHECK_SCHEMA, ...M.crossCheck }),
    ...(FABLE ? [() => agent(checkPrompt, { label: `cross-check-opus:${kind}#${number}`, phase: 'Report', schema: CROSS_CHECK_SCHEMA, ...M.crossCheckShadow })] : []),
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
function askHuman(kind, number, question) {
  const cmd = kind === 'pr' ? 'pr' : 'issue'
  return agent(
    `${REPO} の ${kind === 'pr' ? 'PR' : 'Issue'} #${number} に、次の内容を日本語のコメントで投稿する（\`gh ${cmd} comment ${number} --body-file <ファイル>\`）。見出しは「## 確認してほしいこと」。内容は書き換えずにそのまま載せる（論点・選択肢・推奨と、別のモデルによる確かめの結果を含む）。投稿したら \`gh ${cmd} edit ${number} --add-label needs-human\` を実行する。\n\n${question}`,
    { label: `ask:${cmd}#${number}`, phase: 'Report', ...M.report },
  )
}

// 実装の段階。Gradle を待たずに返したら（gradleStillRunning）、待つ指示を付けて 1 回だけ呼び直す（#904。worktree はそのまま続けて使う）
async function implement(info, approvedPlan, guidance, advice) {
  const { result, stillPending } = await withGradleWait(
    `Issue #${info.number} の実装`, info.number, null,
    note => implementOnce(info, approvedPlan, guidance, advice, note),
    r => r && r.status !== 'already_done' ? gradlePending(r, r.summary, false) : null,
  )
  // やり直しでも待たずに返したら、ビルドを確かめていない PR を CI に進めず、失敗として止める
  if (result && stillPending) {
    return { ...result, status: 'failed', summary: `${pendingReason('実装の段階', result)}。最後の報告: ${result.summary}${result.pr ? `（PR #${result.pr} は作られている）` : ''}` }
  }
  return result
}

function implementOnce(info, approvedPlan, guidance, advice, waitNote) {
  return agent(
    `${REPO} の Issue #${info.number}「${info.title}」を実装して PR を作る。${waitNote ? `
${waitNote}
` : ''}手順は \`.claude/skills/work-issue/SKILL.md\` の 1〜4 に従う。ただし作業場所は、ユーザーのチェックアウトとは別の worktree にする:

1. リポジトリのルートで \`git fetch origin\` し、\`git worktree add .worktrees/issue-${info.number} -b issue-${info.number}-<短い英語名> origin/main\` を実行する（\`.worktrees/issue-${info.number}\` がもうあれば、それを続けて使う）。${worktreeCheck(info.number)}
2. 以降の編集・ビルド・テスト・コミット・push はすべてその worktree の中で行う（絶対パスで操作する）。\`git stash\` は使わない。
3. 実装ループ: 実装 → \`./gradlew build\` → \`./gradlew :platform-neoforge-1.21.1:runGameTestServer :platform-forge-1.20.1:runGameTestServer\` を、すべて通るまで繰り返す。
4. PR を \`gh pr create\` で作る（1つの Issue を複数の PR に分けるときは、AGENTS.md の手順で Stacked PRs にする）。本文の「概要」に、実装中に決めた細かい設計（設計メモ）を書く。マージはしない。
5. 仮素材（テクスチャ・モデル・GUI 画像）を追加したら、#250 の「仮素材の一覧」に追記する。

${gradleRules(info.number, waitNote ? 'implement-wait' : 'implement')}

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
async function waitCi(pr, label) {
  let ci = null
  for (let attempt = 0; attempt < 3; attempt++) {
    ci = await agent(
      `${REPO} の PR #${pr} の CI が終わるまで待ち、結果を返す。コードは変更しない。
1. 先に \`gh pr view ${pr} --json mergeable,mergeStateStatus\` で main との衝突を確かめる。\`mergeable\` は計算が終わるまで UNKNOWN を返すので、UNKNOWN の間は 30 秒ほどおいて 5 回まで取り直す（前景の \`sleep\` は使えないので、\`gh pr checks ${pr} --watch --interval 30\` を 1 回はさむなどで間を空ける）。CONFLICTING なら、GitHub は衝突している PR の CI を動かさないので、待たずに passed=false・failingChecks=[]・mergeable=CONFLICTING・noChecks=true で返す。
2. \`gh pr checks ${pr} --watch --interval 30\` で待つ（10分を超えそうならもう一度実行する）。pending・queued・in_progress のチェックが残っている間は返さない。チェックが 1 つも出ないまま（「no checks reported」など）${NO_CHECKS_MINUTES} 分ほどたったら、待つのをやめ、もう一度 \`mergeable\` を取って、passed=false・failingChecks=[]・noChecks=true で返す。
3. 終わったら \`gh pr checks ${pr} --json name,state,bucket\` で全部のチェックの結果を確かめ、passed は「すべて pass か skipping」のときだけ true にする。failingChecks に入れるのは、終わって bucket が fail・cancel になったチェックだけ（終わっていないものは入れず、終わるまで待つ）。失敗したら failingChecks に落ちたチェックの名前を入れ、\`gh run view <run-id> --log-failed\` の末尾から原因が分かる部分を抜き出す。
mergeable には、最後に取った \`mergeable\` の値を入れる。`,
      { label: attempt ? `${label}~${attempt}` : label, phase: 'CI', schema: CI_SCHEMA, ...M.ciWait },
    )
    if (ci && ci.mergeable === 'CONFLICTING') return ci
    const unfinished = !!ci && ci.failingChecks.some(c => UNFINISHED_CHECK.test(c))
    if (!ci || ci.passed || (ci.failingChecks.length && !unfinished)) return ci
    log(`PR #${pr}: ${unfinished ? 'まだ終わっていないチェックを落ちたものとして返した' : ci.noChecks ? 'チェックが出ない（衝突はしていない）' : 'CI の結果に落ちたチェックの名前がない'}。待ち直す（${attempt + 1}/3）`)
  }
  // 衝突していないのに、待ち直してもチェックが出ないなら、コードで直せる失敗ではない（ci-fix に回さず、報告の段階で人に聞く。#855）
  if (ci && !ci.passed && ci.noChecks && !ci.failingChecks.length && ci.mergeable !== 'CONFLICTING') {
    return { ...ci, noChecksOnly: true, failingChecks: ['チェックが出ない'], failureLog: ci.failureLog || `main との衝突はないが、3 回待ち直しても PR #${pr} にチェックが出なかった` }
  }
  return ci
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
  return agent(
    `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）が main と衝突していて、CI が動かない。worktree で main を取り込む。${waitNote ? `
${waitNote}` : ''}
返す outcome は次のどれか1つ（pushed は outcome=merged のときだけ true）。
1. worktree で \`git fetch origin\` し、\`git merge origin/main\` する（rebase と force push はしない）。
   - Already up to date（取り込むものがない。衝突の読み違いや、GitHub の \`mergeable\` が古い値だった、ほかの段階がもう取り込んでいた、など）なら、何もせず outcome=up_to_date・pushed=false で返す。ローカルにまだ push していないコミットがあれば push してから返す。
2. 衝突が字面の解消で済む（両方の変更を残せば済む、など）なら解いてコミットする。ロジックの判断が要るなら \`git merge --abort\` で戻し、push せず outcome=conflict・pushed=false で、summary に衝突したファイルと、判断が要る理由を書く。
3. 取り込んだ結果で \`./gradlew build\` を通す（GameTest はマージの段階でもう一度回るので、ここでは回さない）。build が落ちたら、取り込みで壊れた分を直す。直せなければ push せず outcome=build_failed・pushed=false で、summary に理由を書く。
4. 通ったら push して outcome=merged・pushed=true で返す。
${gradleRules(info.number, `conflict${n}${waitNote ? '-wait' : ''}`)}`,
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

// CI が通るまで待つ。落ちたら最大 MAX_CI_FIXES 回直す（1回目は Sonnet、2回目は Opus）。
// main との衝突は直しの回数に数えず、先に取り込む（衝突のまま ci-fix に回すと、名前のない失敗を直そうとする）
async function untilGreen(info, impl, tag) {
  const conflictState = { count: 0 }
  let ci = await waitCiResolvingConflicts(info, impl, `ci:${tag}`, conflictState)
  for (let i = 1; ci && !ci.passed && ci.mergeable !== 'CONFLICTING' && !ci.noChecksOnly && i <= MAX_CI_FIXES; i++) {
    log(`PR #${impl.pr}: CI 失敗（${ci.failingChecks.join(', ')}）。修正 ${i}/${MAX_CI_FIXES}`)
    const failing = ci.failingChecks.join(', ')
    const failureLog = ci.failureLog
    const { result: fixed, stillPending } = await withGradleWait(
      `PR #${impl.pr} の CI の修正（${i}）`, info.number, impl.worktree,
      note => agent(
        `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）の CI が失敗した。worktree の中で原因を直し、\`./gradlew build\` と両バージョンの \`runGameTestServer\` を通してから、コミットして push する。${note ? `\n${note}` : ''}
${gradleRules(info.number, `ci-fix${i}${note ? '-wait' : ''}`)}
失敗したチェック: ${failing}
ログ抜粋:
${failureLog}`,
        { label: `ci-fix${note ? '-wait' : ''}:${tag}#${i}`, phase: 'CI', schema: FIX_SCHEMA, ...(i === 1 ? M.fixFirst : M.fixAgain) },
      ),
      r => gradlePending(r, r?.summary, r?.pushed === false),
    )
    if (fixed && stillPending) {
      // 直しのビルドを確かめていないので、直ったことにして先へ進めない。落ちたまま、理由を添えて返す
      ci = { ...ci, failingChecks: [...ci.failingChecks, pendingReason(`CI の修正（${i}）の段階`, fixed)] }
      break
    }
    if (!fixed || !fixed.pushed) break
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
  const heavy = info.areas.some(a => HEAVY_AREAS.includes(a)) || impl.touchesConcurrency === true
  const reviewers = FABLE || !heavy
    ? [() => agent(correctness, { label: `review:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, ...M.review })
      .then(r => r && { by: 'opus', findings: r.findings })]
    : []
  if (FABLE || heavy) {
    reviewers.push(() => agent(correctness, { label: `review-fable:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, ...M.fable })
      .then(r => r && { by: 'fable', findings: r.findings }))
  }
  if (impl.publicApiChanged) {
    reviewers.push(() => agent(
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
    const retry = await agent(correctness, { label: `review-retry:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, ...M.review })
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
    () => agent(verifyPrompt, { label: `verify:${impl.pr}#${round}`, phase: 'Review', schema: VERDICT_SCHEMA, ...M.verify }),
    ...(FABLE ? [() => agent(verifyPrompt, { label: `verify-fable:${impl.pr}#${round}`, phase: 'Review', schema: VERDICT_SCHEMA, ...M.fable })] : []),
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

// マージの段階で GameTest だけが落ち、過負荷の兆候があったか（#855）。回し直すかどうかはスクリプトが決める（エージェントに任せない）
function overloaded(m) {
  return (m.maxTicksBehind ?? 0) >= OVERLOAD_TICKS_BEHIND || (m.gameTestSeconds ?? 0) >= OVERLOAD_GAMETEST_SECONDS
}

// retryNote は過負荷の回し直し（#855）、waitNote は Gradle を待たずに返した回のやり直し（#904）の指示。
// ログの名前（段階の名前）は merge・merge-retry に、やり直しなら -wait を付けて見分ける
function mergePrompt(info, impl, retryNote, waitNote) {
  const stage = `${retryNote ? 'merge-retry' : 'merge'}${waitNote ? '-wait' : ''}`
  return `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）をマージする。他の PR のマージとは直列に行っている。${retryNote ? `\n${retryNote}` : ''}${waitNote ? `\n${waitNote}` : ''}
1. worktree で \`git fetch origin\` し、\`git merge origin/main\` で main を取り込む（rebase と force push はしない）。衝突が字面の解消で済まない（ロジックの判断が要る）なら、マージせず status=needs_human・failedStep=conflict で理由を返す。
2. 取り込んだ結果で \`./gradlew build\` を通す。通らなければ status=needs_human・failedStep=build。通ったら、両バージョンの \`runGameTestServer\` を通す（\`./gradlew :platform-neoforge-1.21.1:runGameTestServer :platform-forge-1.20.1:runGameTestServer --continue\`。片方が落ちても、もう片方も回す）。落ちたら status=needs_human・failedStep=gametest で返す。**GameTest は自分で回し直さない**（回し直すかどうかは、返した数字を見てこのスクリプトが決める）。
3. 取り込みでコミットが増えたら push する。push したかどうかによらず、\`gh pr checks ${impl.pr} --watch --interval 30\` で、先頭のコミットの CI がすべて終わって通るのを待つ（pending が残っている間はマージしない）。通らなければ status=needs_human・failedStep=ci。
4. \`gh pr merge ${impl.pr} --squash --delete-branch\` でマージする。「base branch policy prohibits the merge」で断られたら、\`gh pr view ${impl.pr} --json mergeStateStatus,statusCheckRollup\` で理由を確かめ、CI の待ちが理由なら 3 からもう1回やり直す。2回目も断られたら status=needs_human・failedStep=merge で理由を返す。マージできたら status=merged・failedStep=null。

${gradleRules(info.number, stage)}

返す数字（GameTest まで進んだときは、成功・失敗によらず埋める）:
- gameTestFailedOnly: build が通り、GameTest のサーバーが起動してテストが落ちた（「N required tests failed」）ときだけ true。サーバーの起動前に落ちたもの（コンパイル、依存の取得、ワールドを消せない、GameTest のロックの待ちの上限）は false。
- gameTestSeconds: Gradle の出力にある「GameTest lock released by ... after holding it for N s」の N の最大（ロックを待った時間は入らない）。
- maxTicksBehind: \`platform-neoforge-1.21.1/run/logs/latest.log\` と \`platform-forge-1.20.1/run/logs/latest.log\` の「Can't keep up! Is the server overloaded? Running Nms or N ticks behind」の tick の最大（\`grep -h "Can't keep up" ...\`）。出ていなければ 0。
- logPath: ログを残した \`.worktrees/.logs/issue-${info.number}/\` の絶対パス。`
}

async function mergePr(info, impl) {
  await mergeLock.acquire()
  // 回し直す間も mergeLock を持ったままにする（main を動かさないため。ほかのマージは待つ）
  try {
    // Gradle を待たずに返した回（#904）は、過負荷の判定より先に見る（終わっていない GameTest の数字には意味がない）
    const firstRun = await mergeOnce(info, impl, null, 'merge')
    const first = firstRun.result
    if (!first) return { status: 'needs_human', reason: firstRun.retryFailed ? 'マージの段階が Gradle の終わりを待たずに返し、待つやり直しの段階も失敗した' : 'マージの段階が失敗した' }
    if (firstRun.stillPending) return pendingMergeResult(first)
    const waitPrefix = firstRun.waited ? 'マージの段階が Gradle の終わりを待たずに返したので、1 回だけやり直した。' : ''
    const retry = first.status === 'needs_human' && first.failedStep === 'gametest' && first.gameTestFailedOnly && overloaded(first)
    if (!retry) return waitPrefix ? { ...first, retried: true, reason: `${waitPrefix}${first.status === 'merged' ? '' : `やり直しの理由: ${first.reason ?? '-'}`}` } : first
    const numbers = `GameTest ${first.gameTestSeconds ?? '-'} 秒、最大 ${first.maxTicksBehind ?? '-'} tick 遅れ`
    log(`PR #${impl.pr}: マージの段階で GameTest だけが落ち、過負荷の兆候がある（${numbers}）。1 回だけ回し直す`)
    const note = `前回のマージの段階では、GameTest だけが過負荷の兆候とともに落ちた（${numbers}。理由: ${first.reason ?? '-'}）。過負荷による揺れの疑いがあるので、手順 1 からもう 1 回だけやり直す（GameTest はロックが空くのを待ってから回る）。**今回も落ちたら、回し直さずに status=needs_human で返す。**`
    const secondRun = await mergeOnce(info, impl, note, 'merge-retry')
    const second = secondRun.result
    const prefix = `${waitPrefix}過負荷の兆候（${numbers}）で GameTest が落ちたので、1 回だけ回し直した。1 回目の理由: ${first.reason ?? '-'}`
    if (!second) return { ...first, reason: `${prefix}。回し直しの段階が失敗した${secondRun.retryFailed ? '（Gradle の終わりを待たずに返し、待つやり直しの段階も失敗した）' : ''}` }
    if (secondRun.stillPending) return pendingMergeResult(second, prefix)
    return { ...second, retried: true, reason: second.status === 'merged' ? prefix : `${prefix}。回し直しの理由: ${second.reason ?? '-'}` }
  } finally {
    mergeLock.release()
  }
}

// マージの段階を 1 回呼ぶ。Gradle を待たずに返したら、待つ指示を付けて 1 回だけ呼び直す（#904）。
// 理由の文も見るのは、マージせずに build で止まったと返した回（failedStep が build か null）だけ。
// conflict・ci・merge は Gradle の終わりと関係せず、gametest は「ロックの待ちの上限」「タイムアウト」「サーバーが落ちた」など、
// Gradle が終わったあとの本物の失敗が「実行中」「終わる前」といった文を含みうるので見ない（gametest の未完了は申告で見分ける）
function mergeOnce(info, impl, retryNote, stage) {
  return withGradleWait(
    `PR #${impl.pr} の${stage === 'merge' ? 'マージ' : 'マージの回し直し'}`, info.number, impl.worktree,
    note => agent(mergePrompt(info, impl, retryNote, note), { label: `${stage}${note ? '-wait' : ''}:${impl.pr}`, phase: 'Merge', schema: MERGE_SCHEMA, ...M.merge }),
    r => r && r.status !== 'merged' ? gradlePending(r, r.reason, r.failedStep == null || r.failedStep === 'build') : null,
  )
}

// やり直しでも Gradle を待たずに返した（#904）。今までどおり人に上げる（マージはしていない）
function pendingMergeResult(r, prefix) {
  return {
    ...r,
    status: 'needs_human',
    failedStep: r.failedStep ?? 'build',
    reason: `${prefix ? `${prefix}。` : ''}${pendingReason('マージの段階', r)}。最後の理由: ${r.reason ?? '-'}`,
  }
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
function cleanupWorktree(info, impl) {
  const logs = `.worktrees/.logs/issue-${info.number}`
  return agent(
    `${REPO} の PR #${impl.pr} はマージ済み。worktree ${impl.worktree} を片付ける。リポジトリのルートで作業する。
1. その worktree の Gradle と GameTest のサーバーが終わっているのを確かめる。最大 15 分、Monitor などで待つ（前景の \`sleep\` は使えない）:
   - \`${logs}/\` の \`.log\` のうち、\`-latest.log\` で終わらないものすべてに、同じ名前の \`.exit\` がある（Gradle が終わった）。
   - コマンドラインに worktree のパス（\`.worktrees/issue-${info.number}\`）を含む java のプロセスがない。番号が前方一致する別の worktree（例: issue-${info.number}0 や issue-${info.number}4）のものを数えないよう、番号の後ろに数字が続かないものだけを数える（PowerShell の \`${worktreeJavaQuery(info.number)}\`。\`/\`・\`\\\` のどちらの書き方でも当たる）。
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
  await agent(
    `${REPO} の PR #${r.impl.pr} に、自動レビューの結果を日本語のコメントで投稿する（\`gh pr comment ${r.impl.pr} --body-file <ファイル>\`）。内容:
- CI: ${ciState}
- レビューの回数: ${r.rounds.length}
- 最後のレビューで残った指摘（直せなかったもの）: ${remaining.length ? remaining.map(f => `[${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join(' / ') : 'なし'}
- 最後のレビューの指摘で、直して push したがレビューし直していないもの: ${fixedUnreviewed.length ? fixedUnreviewed.map(f => `[${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join(' / ') : 'なし'}
- 結論: ${ready ? 'マージ可能' : '人の確認が必要'}
- マージ: ${mergeState}
- worktree: ${cleanupState}
- ログ: リポジトリのルートの \`.worktrees/.logs/issue-${info.number}/\`
worktree は消さない（片付けはこのスクリプトが別に行う）。`,
    { label: `report:${r.impl.pr}`, phase: 'Report', ...M.report },
  )
  if (merged || (!MERGE && ready)) return null

  const reasons = []
  if (!r.ci || !r.ci.passed) reasons.push(`CI が通らない: ${ciState}`)
  for (const f of remaining.filter(f => f.severity === 'must')) {
    reasons.push(`レビューの指摘が直らなかった: ${f.file}:${f.line ?? '-'} ${f.problem}`)
  }
  for (const f of fixedUnreviewed.filter(f => f.severity === 'must')) {
    reasons.push(`最後のラウンドで直して push したが、レビューし直していない must の指摘: ${f.file}:${f.line ?? '-'} ${f.problem}`)
  }
  if (r.unverified) reasons.push(`直しを確かめられなかった: ${r.unverified}`)
  if (r.merge && r.merge.status !== 'merged') reasons.push(`マージできなかった: ${r.merge.reason}`)
  const question = `自動ではマージできなかった。どう進めるか判断してほしい（直し方の指示、このままマージ、PR を閉じる など）:\n${reasons.map(x => `- ${x}`).join('\n')}`
  // この段階ではもう直す機会がないので、確かめた結果を添えて聞く
  const e = await escalate('pr', r.impl.pr, question, `PR の CI・レビュー・マージの段階（ブランチ ${r.impl.branch}）`, false)
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

  const areas = [...info.areas].sort()
  await slots.acquire()
  for (const a of areas) await areaLock(a).acquire()
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
    if (!impl || impl.status !== 'pr_opened' || !impl.pr) {
      return { issue: info.number, status: 'failed', reason: impl?.summary ?? '実装の段階が失敗した', question: null }
    }

    // 実装のエージェントが PR に残した確認事項は、マージの前に確かめる。人の判断が要るならマージせずに止まる
    if (impl.openQuestions?.length) {
      const listed = impl.openQuestions.map(x => `- ${x}`).join('\n')
      const q = `実装のエージェントが PR に確認事項を残した:\n${listed}`
      const e = await escalate('pr', impl.pr, q, `実装の段階で PR を作ったあと（ブランチ ${impl.branch}）`, true)
      if (!e.guidance) return { issue: info.number, status: 'needs_human', pr: impl.pr, reason: e.question, question: e.question }
      // push しないのが正しい回もある（コードを変える必要がない）ので、理由の文は見ずに申告だけで見分ける（#904）
      const { result: applied, stillPending: appliedPending } = await withGradleWait(
        `PR #${impl.pr} の確認事項の反映`, info.number, impl.worktree,
        note => agent(
          `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）に、実装のエージェントが確認事項を残した。人に聞く前の確かめ（別のモデル）が、人を待たずに進められると判断し、次の進め方を示した。これに従う。${note ? `\n${note}` : ''}
コードを変える必要があれば worktree の中で直し、\`./gradlew build\` と両バージョンの \`runGameTestServer\` を通してからコミットして push する。変える必要がなければ push しない。
${gradleRules(info.number, note ? 'resolve-wait' : 'resolve')}
どちらの場合も、PR 本文の「確認してほしいこと」を、確かめた結果と結論（決めたこととその根拠）に書き換える（\`gh pr edit ${impl.pr} --body-file <ファイル>\`）。

確認事項:
${listed}

進め方:
${e.guidance}`,
          { label: `${note ? 'resolve-wait' : 'resolve'}:${impl.pr}`, phase: 'Fix', schema: FIX_SCHEMA, ...M.fixFirst },
        ),
        r => gradlePending(r, r?.summary, false),
      )
      if (!applied) return { issue: info.number, status: 'failed', pr: impl.pr, reason: '確認事項の反映に失敗した', question: null }
      if (appliedPending) return { issue: info.number, status: 'failed', pr: impl.pr, reason: `${pendingReason('確認事項の反映の段階', applied)}。最後の報告: ${applied.summary}`, question: null }
    }

    const tag = `#${info.number}`
    let ci = await untilGreen(info, impl, tag)
    const rounds = []
    let unverified = null
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
        note => agent(
          `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）へのレビュー指摘を直す。worktree の中で修正し、\`./gradlew build\` と両バージョンの \`runGameTestServer\` を通してから、コミットして push する。${note ? `\n${note}` : ''}
Issue にし忘れた別の問題は、コードを直す代わりに新しい Issue にして適切な Epic のサブ Issue にし（AGENTS.md の手順）、PR 本文に番号を書く。
${gradleRules(info.number, `fix${round}${note ? '-wait' : ''}`)}
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
      if (!fixed || !fixed.pushed) break
      entry.fixed = true
      ci = await untilGreen(info, impl, `${tag}-r${round}`)
    }

    // マージの条件は変えない: 最後のラウンドの must は、直して push していてもレビューし直していないので止める
    const { all: lastConfirmed, remaining, fixedUnreviewed } = lastFindings(rounds)
    const ready = ci && ci.passed && !unverified && !lastConfirmed.some(f => f.severity === 'must')
    const merge = MERGE && ready ? await mergePr(info, impl) : null

    const result = { impl, ci, rounds, merge }
    const question = await report(info, { ...result, ready, unverified })
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
    for (const a of areas.reverse()) areaLock(a).release()
    slots.release()
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
  },
  required: ['ok', 'title', 'areas', 'branch', 'worktree', 'publicApiChanged', 'touchesConcurrency', 'summary'],
}

// 止まった PR の続きのために、worktree を用意して実装の結果と同じ形にする。
// needs-human のラベルは触らない（外すのは人の指示を受けた起動側）
async function prepareResume({ issue, pr }) {
  const r = await agent(
    `${REPO} の PR #${pr}（Issue #${issue}）を、途中で止まったところから自動の流れで続けるために用意する。
1. \`gh pr view ${pr} --json state,headRefName,body,labels\` と \`gh issue view ${issue} --json title,labels\` を読む。PR が OPEN でないか、PR か Issue に \`needs-human\` ラベルが付いていれば ok=false にして、理由を summary に書く。
2. リポジトリのルートで \`git fetch origin\` し、\`.worktrees/issue-${issue}\` がなければ \`git worktree add .worktrees/issue-${issue} <headRefName>\`（ローカルのブランチがなければ \`origin/<headRefName>\` から作る）。あれば、${worktreeCheck(issue)} 本物の worktree なら \`git -C .worktrees/issue-${issue} pull --ff-only\` で先頭に合わせる。
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
      publicApiChanged: r.publicApiChanged, touchesConcurrency: r.touchesConcurrency, summary: r.summary,
      // 止まった PR の確認事項は、人が回答して needs-human を外してから続けるので、ここでは確かめ直さない
      openQuestions: [],
    },
  }
}

phase('Select')
const selected = RESUME.length
  ? { issues: (await parallel(RESUME.map(x => () => prepareResume(x)))).filter(Boolean) }
  : await select()
const infos = (selected?.issues ?? []).filter(i => {
  if (i.blockedReason) log(`#${i.number} は着手しない: ${i.blockedReason}`)
  return !i.blockedReason
})
if (!infos.length) {
  log('着手できる Issue がない')
  return { results: [], skipped: selected?.issues ?? [] }
}
for (const i of infos) done.set(i.number, deferred())

const results = await parallel(infos.map(info => async () => {
  let r
  try {
    r = await runIssue(info)
  } catch (e) {
    r = { issue: info.number, status: 'failed', reason: String(e) }
  }
  done.get(info.number).resolve(r)
  return r
}))

const finished = results.filter(Boolean)
return {
  results: finished,
  // 起動したセッションは、これをチャットでユーザーに聞く
  questions: finished.filter(r => r.question).map(r => ({ issue: r.issue, pr: r.pr ?? null, question: r.question })),
  skipped: (selected?.issues ?? []).filter(i => i.blockedReason),
  ...(FABLE ? { comparison: comparisons } : {}),
}
