export const meta = {
  name: 'issue-to-pr',
  description: 'Issue を worktree で実装して PR を作り、CI とレビューエージェントで確認・修正してマージする。人の確認が要るものは質問して止まる',
  whenToUse: 'KiBi Computers の Issue を自動で進めたいとき。args は Issue 番号・番号の配列・{ issues, window, merge }。省略するとエージェントが選ぶ。merge: false でマージしない。',
  phases: [
    { title: 'Select', detail: '対象の Issue と依存・area・プランの要否を調べる' },
    { title: 'Plan', detail: 'ABI・保存形式・公開 API に関わる Issue だけ、プランを書いてレビューする' },
    { title: 'Implement', detail: 'worktree で実装し、build と両バージョンの GameTest が通るまで直して PR を作る' },
    { title: 'CI', detail: 'PR の CI を待つ。落ちたら直す' },
    { title: 'Review', detail: '正しさ・ルール違反のレビュー、公開 API の Java 相互運用レビュー、指摘の検証' },
    { title: 'Fix', detail: '確かな指摘を直して CI を待ち直す' },
    { title: 'Merge', detail: '1件ずつ main を取り込み、確認してからマージする（merge: false なら飛ばす）' },
    { title: 'Report', detail: 'PR に結果をまとめ、人の確認が要れば質問して needs-human を付ける。worktree を片付ける' },
  ],
}

// 開発フロー（#242）。
// 選択 → (プラン) → 実装ループ → CI → レビュー → 修正 → マージ → 報告。最終確認の段階は置かない。
// 人の確認が要るものは Issue / PR にコメントで質問して needs-human ラベルを付け、返り値の questions にも入れる
// （起動したセッションがチャットでユーザーに聞く）。
// モデル: 判断の重い段階は Opus、定型の段階は Sonnet / Haiku。
const REPO = 'neverclear86/KiBi-Computers'
const MAX_CI_FIXES = 2
const MAX_REVIEW_ROUNDS = 2

const OPTS = Array.isArray(args) || typeof args === 'number' || args == null
  ? { issues: args == null ? [] : [].concat(args) }
  : args
const WINDOW = OPTS.window ?? 3
const MERGE = OPTS.merge !== false

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
          blockedReason: { type: ['string', 'null'], description: '着手できない理由（未決の decision、needs-human、human-task、担当者あり、進行中の PR など）。着手できるなら null' },
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

const IMPLEMENT_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['pr_opened', 'blocked'] },
    pr: { type: ['integer', 'null'] },
    branch: { type: ['string', 'null'] },
    worktree: { type: ['string', 'null'], description: 'worktree の絶対パス' },
    publicApiChanged: { type: 'boolean' },
    summary: { type: 'string' },
  },
  required: ['status', 'pr', 'branch', 'worktree', 'publicApiChanged', 'summary'],
}

const CI_SCHEMA = {
  type: 'object',
  properties: {
    passed: { type: 'boolean' },
    failingChecks: { type: 'array', items: { type: 'string' } },
    failureLog: { type: 'string', description: '失敗の原因が分かるログの抜粋（通ったら空）' },
  },
  required: ['passed', 'failingChecks', 'failureLog'],
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

const FIX_SCHEMA = {
  type: 'object',
  properties: {
    pushed: { type: 'boolean' },
    summary: { type: 'string' },
  },
  required: ['pushed', 'summary'],
}

const MERGE_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['merged', 'needs_human'] },
    reason: { type: ['string', 'null'] },
  },
  required: ['status', 'reason'],
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
- blockedReason: 未決の \`decision\` Issue に左右される、担当者がいる、進行中の PR がある（\`gh pr list --search "<番号> in:body"\`）、\`decision\` か \`epic\` のラベルが付いている、\`needs-human\` ラベルが付いている（人の回答待ち）、\`human-task\` ラベルが付いている（人が行う作業）、のいずれかならその理由。なければ null
- needsPlan: システムコールの ABI、セーブデータの保存形式、他 MOD 向けの公開 API（\`pro.ikili.kibicomputers.api\`）のどれかを決める・変える Issue なら true
コードやリポジトリは変更しない。`,
    { label: 'select', phase: 'Select', schema: SELECT_SCHEMA, ...M.select },
  )
}

async function plan(info) {
  let prev = null
  for (let round = 1; round <= 2; round++) {
    const p = await agent(
      `${REPO} の Issue #${info.number}「${info.title}」の実装プランを書き、Issue にコメントとして投稿する。コードは変更しない。
プランには、変更するファイル、公開する型とシグネチャ（Java から見た形を含む）、データ形式、テスト方針、そして「このプランで決めること」を明記する。
ABI・保存形式・公開 API について、\`decision\` Issue で決まっていない選択が必要なら、勝手に決めずに status=blocked とし、論点と選択肢を Issue にコメントする。それ以外の細かい設計はプランの中で決めてよい。${prev ? `\n前回のレビューで直すよう求められた点:\n${prev.must.map(m => `- ${m}`).join('\n')}` : ''}`,
      { label: `plan:#${info.number}#${round}`, phase: 'Plan', schema: PLAN_SCHEMA, ...M.plan },
    )
    if (!p || p.status !== 'planned') return { ok: false, reason: p?.reason ?? 'プランを作れなかった' }
    const r = await agent(
      `${REPO} の Issue #${info.number} のプランをレビューする。AGENTS.md のルール、関連する \`decision\` Issue の結論、既存のコードと突き合わせる。コードは変更しない。
- must: このままでは実装してはいけない問題
- undecided: システムコールの ABI・保存形式・公開 API について、\`decision\` Issue で決まっていない選択をプランが決めているもの（それ以外の細かい設計の選択は含めない）
undecided があれば verdict=needs_human。must だけなら revise。どちらもなければ approve。
プラン:
${p.plan}`,
      { label: `plan-review:#${info.number}#${round}`, phase: 'Plan', schema: PLAN_REVIEW_SCHEMA, ...M.planReview },
    )
    if (!r) return { ok: false, reason: 'プランのレビューに失敗した' }
    if (r.verdict === 'approve') return { ok: true, plan: p.plan }
    if (r.verdict === 'needs_human') {
      const question = `次の設計の選択は decision で決まっていないため、判断してほしい:\n${r.undecided.map(u => `- ${u}`).join('\n')}`
      await askHuman('issue', info.number, question)
      return { ok: false, reason: question }
    }
    prev = r
  }
  const question = `プランが2ラウンドで承認されなかった。残った問題:\n${prev.must.map(m => `- ${m}`).join('\n')}`
  await askHuman('issue', info.number, question)
  return { ok: false, reason: question }
}

// 人の確認が要るとき、Issue か PR にコメントで質問し、needs-human ラベルを付ける
function askHuman(kind, number, question) {
  const cmd = kind === 'pr' ? 'pr' : 'issue'
  return agent(
    `${REPO} の ${kind === 'pr' ? 'PR' : 'Issue'} #${number} に、次の内容を日本語のコメントで投稿する（\`gh ${cmd} comment ${number} --body-file <ファイル>\`）。見出しは「## 確認してほしいこと」。論点ごとに選択肢と推奨を短く添える。投稿したら \`gh ${cmd} edit ${number} --add-label needs-human\` を実行する。\n\n${question}`,
    { label: `ask:${cmd}#${number}`, phase: 'Report', ...M.report },
  )
}

function implement(info, approvedPlan) {
  return agent(
    `${REPO} の Issue #${info.number}「${info.title}」を実装して PR を作る。手順は \`.claude/skills/work-issue/SKILL.md\` の 1〜4 に従う。ただし作業場所は、ユーザーのチェックアウトとは別の worktree にする:

1. リポジトリのルートで \`git fetch origin\` し、\`git worktree add .worktrees/issue-${info.number} -b issue-${info.number}-<短い英語名> origin/main\` を実行する。
2. 以降の編集・ビルド・テスト・コミット・push はすべてその worktree の中で行う（絶対パスで操作する）。\`git stash\` は使わない。
3. 実装ループ: 実装 → \`./gradlew build\` → \`./gradlew :platform-neoforge-1.21.1:runGameTestServer :platform-forge-1.20.1:runGameTestServer\` を、すべて通るまで繰り返す。
4. PR を \`gh pr create\` で作る。本文の「概要」に、実装中に決めた細かい設計（設計メモ）を書く。マージはしない。
5. 仮素材（テクスチャ・モデル・GUI 画像）を追加したら、#250 の「仮素材の一覧」に追記する。

未決の \`decision\` に左右される論点や、AGENTS.md で人に聞くと決めた4つ（ABI・保存形式・公開 API・ゲームバランスの最終値）に当たったら、実装を止めて Issue に質問をコメントし（見出し「## 確認してほしいこと」、論点・選択肢・推奨）、\`gh issue edit ${info.number} --add-label needs-human\` を付けて status=blocked で終える。summary には質問の要点を書く。
publicApiChanged には、\`public\` な宣言（特に \`pro.ikili.kibicomputers.api\` 以下）を追加・変更したかを入れる。${approvedPlan ? `\n\n承認済みのプラン（これに従う。従えない点があれば PR に理由を書く）:\n${approvedPlan}` : ''}`,
    { label: `implement:#${info.number}`, phase: 'Implement', schema: IMPLEMENT_SCHEMA, ...M.implement },
  )
}

function waitCi(pr, label) {
  return agent(
    `${REPO} の PR #${pr} の CI が終わるまで待ち、結果を返す。\`gh pr checks ${pr} --watch --interval 30\` を使う（10分を超えそうならもう一度実行する）。失敗したら \`gh run view <run-id> --log-failed\` の末尾から原因が分かる部分を抜き出す。コードは変更しない。`,
    { label, phase: 'CI', schema: CI_SCHEMA, ...M.ciWait },
  )
}

// CI が通るまで待つ。落ちたら最大 MAX_CI_FIXES 回直す（1回目は Sonnet、2回目は Opus）。
async function untilGreen(impl, tag) {
  let ci = await waitCi(impl.pr, `ci:${tag}`)
  for (let i = 1; ci && !ci.passed && i <= MAX_CI_FIXES; i++) {
    log(`PR #${impl.pr}: CI 失敗（${ci.failingChecks.join(', ')}）。修正 ${i}/${MAX_CI_FIXES}`)
    const fixed = await agent(
      `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）の CI が失敗した。worktree の中で原因を直し、\`./gradlew build\` と両バージョンの \`runGameTestServer\` を通してから、コミットして push する。
失敗したチェック: ${ci.failingChecks.join(', ')}
ログ抜粋:
${ci.failureLog}`,
      { label: `ci-fix:${tag}#${i}`, phase: 'CI', schema: FIX_SCHEMA, ...(i === 1 ? M.fixFirst : M.fixAgain) },
    )
    if (!fixed || !fixed.pushed) break
    ci = await waitCi(impl.pr, `ci:${tag}#${i}`)
  }
  return ci
}

async function review(info, impl, round) {
  const base = `${REPO} の PR #${impl.pr}（Issue #${info.number}）をレビューする。差分は \`gh pr diff ${impl.pr}\`、コードは worktree ${impl.worktree} で読める。コードは変更しない。CI（build と両バージョンの GameTest）は通っているので、その再現はしない。
must は「このままマージすると壊れる・ルールに反する」と確信できるものだけ。推測や好みは should にする。直し方は書かない。`
  const reviewers = [
    () => agent(
      `${base}
観点: 正しさ（バグ、境界条件、スレッド安全性、エラー処理）、AGENTS.md のルール違反（core に Minecraft を持ち込む、Kotlin 2.2 より新しい API、片方のバージョンだけ壊れる変更）、Issue の要件の取りこぼし、足りないテスト。`,
      { label: `review:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, ...M.review },
    ),
  ]
  if (impl.publicApiChanged) {
    reviewers.push(() => agent(
      `${base}
公開 API（public 宣言、特に pro.ikili.kibicomputers.api 以下）が Java から自然に使えるかを確認する。`,
      { label: `java-api:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, agentType: 'java-api-reviewer', ...M.javaApi },
    ))
  }
  const findings = (await parallel(reviewers)).filter(Boolean).flatMap(r => r.findings)
  if (!findings.length) return []

  // 指摘は別のエージェントが懐疑的に検証し、本当に直すべきものだけ残す
  const listed = findings.map((f, i) => `${i}. [${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join('\n')
  const verdict = await agent(
    `${REPO} の PR #${impl.pr} へのレビュー指摘を検証する。コード（worktree ${impl.worktree}）を実際に読み、各指摘が本当に問題か確かめる。疑わしいもの、好みの問題、PR の範囲外のものは外す。直すべきものの番号だけを返す。コードは変更しない。
${listed}`,
    { label: `verify:${impl.pr}#${round}`, phase: 'Review', schema: VERDICT_SCHEMA, ...M.verify },
  )
  if (!verdict) return findings.filter(f => f.severity === 'must')
  return verdict.confirmed.filter(i => findings[i]).map(i => findings[i])
}

async function mergePr(info, impl) {
  await mergeLock.acquire()
  try {
    const m = await agent(
      `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）をマージする。他の PR のマージとは直列に行っている。
1. worktree で \`git fetch origin\` し、\`git merge origin/main\` で main を取り込む（rebase と force push はしない）。衝突が字面の解消で済まない（ロジックの判断が要る）なら、マージせず status=needs_human で理由を返す。
2. 取り込んだ結果で \`./gradlew build\` と両バージョンの \`runGameTestServer\` を通す。通らなければ status=needs_human。
3. 取り込みでコミットが増えたら push し、\`gh pr checks ${impl.pr} --watch --interval 30\` で CI が通るのを待つ。
4. \`gh pr merge ${impl.pr} --squash --delete-branch\` でマージする。`,
      { label: `merge:${impl.pr}`, phase: 'Merge', schema: MERGE_SCHEMA, ...M.merge },
    )
    return m ?? { status: 'needs_human', reason: 'マージの段階が失敗した' }
  } finally {
    mergeLock.release()
  }
}

// PR に結果をまとめる。自動でマージできなかったときは質問を返す
async function report(info, r) {
  const remaining = r.rounds.length ? r.rounds[r.rounds.length - 1].confirmed : []
  const ciState = !r.ci ? 'CI の確認に失敗' : r.ci.passed ? 'CI 通過' : `CI 失敗（${r.ci.failingChecks.join(', ')}）`
  const ready = r.ready
  const mergeState = r.merge
    ? (r.merge.status === 'merged' ? 'マージ済み' : `マージせず（${r.merge.reason}）`)
    : MERGE ? 'マージせず（確認が必要）' : 'マージは人が行う（merge: false）'
  await agent(
    `${REPO} の PR #${r.impl.pr} に、自動レビューの結果を日本語のコメントで投稿する（\`gh pr comment ${r.impl.pr} --body-file <ファイル>\`）。内容:
- CI: ${ciState}
- レビューの回数: ${r.rounds.length}
- 最後のレビューで残った指摘: ${remaining.length ? remaining.map(f => `[${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join(' / ') : 'なし'}
- 結論: ${ready ? 'マージ可能' : '人の確認が必要'}
- マージ: ${mergeState}
投稿したら、リポジトリのルートで \`git worktree remove --force ${r.impl.worktree}\` を実行して worktree を片付ける。`,
    { label: `report:${r.impl.pr}`, phase: 'Report', ...M.report },
  )
  if (r.merge?.status === 'merged' || (!MERGE && ready)) return null

  const reasons = []
  if (!r.ci || !r.ci.passed) reasons.push(`CI が通らない: ${ciState}`)
  for (const f of remaining.filter(f => f.severity === 'must')) {
    reasons.push(`レビューの指摘が直らなかった: ${f.file}:${f.line ?? '-'} ${f.problem}`)
  }
  if (r.merge && r.merge.status !== 'merged') reasons.push(`マージできなかった: ${r.merge.reason}`)
  const question = `自動ではマージできなかった。どう進めるか判断してほしい（直し方の指示、このままマージ、PR を閉じる など）:\n${reasons.map(x => `- ${x}`).join('\n')}`
  await askHuman('pr', r.impl.pr, question)
  return question
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
    let approvedPlan = null
    if (info.needsPlan) {
      const p = await plan(info)
      if (!p.ok) return { issue: info.number, status: 'needs_human', reason: p.reason, question: p.reason }
      approvedPlan = p.plan
    }

    const impl = await implement(info, approvedPlan)
    if (!impl || impl.status !== 'pr_opened' || !impl.pr) {
      const reason = impl?.summary ?? '実装の段階が失敗した'
      return { issue: info.number, status: impl ? 'needs_human' : 'failed', reason, question: impl ? reason : null }
    }

    const tag = `#${info.number}`
    let ci = await untilGreen(impl, tag)
    const rounds = []
    for (let round = 1; ci && ci.passed && round <= MAX_REVIEW_ROUNDS; round++) {
      const confirmed = await review(info, impl, round)
      const toFix = confirmed.filter(f => f.severity === 'must')
      rounds.push({ round, confirmed })
      if (!toFix.length) break
      log(`PR #${impl.pr}: must ${toFix.length} 件を修正（${round}/${MAX_REVIEW_ROUNDS}）`)
      const fixed = await agent(
        `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）へのレビュー指摘を直す。worktree の中で修正し、\`./gradlew build\` と両バージョンの \`runGameTestServer\` を通してから、コミットして push する。
${toFix.map((f, i) => `${i + 1}. ${f.file}:${f.line ?? '-'} ${f.problem}`).join('\n')}`,
        { label: `fix:${impl.pr}#${round}`, phase: 'Fix', schema: FIX_SCHEMA, ...(round === 1 ? M.fixFirst : M.fixAgain) },
      )
      if (!fixed || !fixed.pushed) break
      ci = await untilGreen(impl, `${tag}-r${round}`)
    }

    const remaining = rounds.length ? rounds[rounds.length - 1].confirmed : []
    const ready = ci && ci.passed && !remaining.some(f => f.severity === 'must')
    const merge = MERGE && ready ? await mergePr(info, impl) : null

    const result = { impl, ci, rounds, merge }
    const question = await report(info, { ...result, ready })
    return {
      issue: info.number,
      status: merge?.status === 'merged' ? 'merged' : ready ? 'ready' : 'needs_human',
      pr: impl.pr,
      summary: impl.summary,
      ciPassed: ci?.passed ?? null,
      reviewRounds: rounds.length,
      remainingFindings: remaining,
      mergeReason: merge?.reason ?? null,
      question,
    }
  } finally {
    for (const a of areas.reverse()) areaLock(a).release()
    slots.release()
  }
}

// ---- 実行 ----

phase('Select')
const selected = await select()
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
}
