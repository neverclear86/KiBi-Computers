export const meta = {
  name: 'issue-to-pr',
  description: 'Issue を実装して PR を作り、CI が通ったらレビューエージェントで確認・修正する',
  whenToUse: 'KiBi Computers の Issue を自動で進めたいとき。args に Issue 番号（数値か配列）。省略するとエージェントが1件選ぶ。',
  phases: [
    { title: 'Implement', detail: 'worktree で実装・テストし PR を作る' },
    { title: 'CI', detail: 'PR の CI を待つ。落ちたら直す' },
    { title: 'Review', detail: 'コードレビューと Java 相互運用レビュー、指摘の検証' },
    { title: 'Fix', detail: '確かな指摘を直して CI を待ち直す' },
    { title: 'Report', detail: 'PR に結果をまとめる' },
  ],
}

// 開発フロー（#242）: 実装 → PR → CI → レビュー → 修正 → 報告。マージは人が行う（自動マージは未決）。
const REPO = 'neverclear86/KiBi-Computers'
const MAX_CI_FIXES = 2
const MAX_REVIEW_ROUNDS = 2

const issues = Array.isArray(args) ? args : (args ? [args] : [null])

const IMPLEMENT_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['pr_opened', 'blocked', 'nothing_to_do'] },
    issue: { type: ['integer', 'null'] },
    pr: { type: ['integer', 'null'] },
    branch: { type: ['string', 'null'] },
    worktree: { type: ['string', 'null'], description: 'worktree の絶対パス' },
    summary: { type: 'string' },
  },
  required: ['status', 'issue', 'pr', 'branch', 'worktree', 'summary'],
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
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          problem: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['file', 'line', 'severity', 'problem', 'fix'],
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

function implementPrompt(issue) {
  const target = issue == null
    ? 'まず `.claude/skills/work-issue/SKILL.md` の手順 0 に従って、着手できる Issue を1つ選ぶ。なければ status=nothing_to_do で終える。'
    : `対象は Issue #${issue}。`
  return `KiBi Computers（${REPO}）の Issue を1つ実装し、PR を作る。${target}

手順は \`.claude/skills/work-issue/SKILL.md\` に従う。ただし作業場所は次のとおり、ユーザーのチェックアウトとは別の worktree にする:

1. リポジトリのルートで \`git fetch origin\` し、\`git worktree add .worktrees/issue-<番号> -b issue-<番号>-<短い英語名> origin/main\` を実行する。
2. 以降の編集・ビルド・テスト・コミット・push はすべてその worktree の中で行う（絶対パスで操作する）。
3. \`./gradlew build\` と、関係するバージョンの \`runGameTestServer\` が通ってから PR を作る。
4. PR は \`gh pr create\` で作り、マージはしない。

仕様が読み取れない、または未決の \`decision\` に左右されるときは、実装せずに Issue に論点をコメントし、status=blocked で終える。
返り値の worktree には worktree の絶対パスを入れる。`
}

async function waitCi(pr, label) {
  return agent(
    `${REPO} の PR #${pr} の CI が終わるまで待ち、結果を返す。\`gh pr checks ${pr} --watch --interval 30\` を使う（10分を超えそうならもう一度実行する）。失敗したら \`gh run view <run-id> --log-failed\` の末尾から原因が分かる部分を抜き出す。コードは変更しない。`,
    { label, phase: 'CI', schema: CI_SCHEMA, effort: 'low' },
  )
}

async function fixCi(impl, ci, label) {
  return agent(
    `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）の CI が失敗した。worktree の中で原因を直し、ローカルで \`./gradlew build\` と関係する \`runGameTestServer\` を通してから、コミットして push する。
失敗したチェック: ${ci.failingChecks.join(', ')}
ログ抜粋:
${ci.failureLog}`,
    { label, phase: 'CI', schema: FIX_SCHEMA },
  )
}

// CI が通るまで待つ。落ちたら最大 MAX_CI_FIXES 回直す。
async function untilGreen(impl, tag) {
  let ci = await waitCi(impl.pr, `ci:${tag}`)
  for (let i = 1; ci && !ci.passed && i <= MAX_CI_FIXES; i++) {
    log(`PR #${impl.pr}: CI 失敗（${ci.failingChecks.join(', ')}）。修正 ${i}/${MAX_CI_FIXES}`)
    const fixed = await fixCi(impl, ci, `ci-fix:${tag}#${i}`)
    if (!fixed || !fixed.pushed) break
    ci = await waitCi(impl.pr, `ci:${tag}#${i}`)
  }
  return ci
}

async function review(impl, round) {
  const base = `${REPO} の PR #${impl.pr}（Issue #${impl.issue}）をレビューする。差分は \`gh pr diff ${impl.pr}\`、コードは worktree ${impl.worktree} で読める。コードは変更しない。確信の持てる問題だけを挙げる（好みの問題は low にする）。`
  const [general, javaApi] = await parallel([
    () => agent(
      `${base}
観点: 正しさ（バグ、境界条件、スレッド安全性）、AGENTS.md のルール違反（core に Minecraft を持ち込む、Kotlin 2.2 より新しい API、片方のバージョンだけ壊れる変更）、Issue の要件の取りこぼし、足りないテスト。`,
      { label: `review:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA },
    ),
    () => agent(
      `${base}
公開 API（public 宣言、特に pro.ikili.kibicomputers.api 以下）の Java 相互運用を確認する。公開 API の変更がなければ findings は空にする。`,
      { label: `java-api:${impl.pr}#${round}`, phase: 'Review', schema: FINDINGS_SCHEMA, agentType: 'java-api-reviewer' },
    ),
  ])
  const findings = [general, javaApi].filter(Boolean).flatMap(r => r.findings)
  if (!findings.length) return []

  // 指摘は懐疑的に検証し、本当に直すべきものだけ残す
  const listed = findings.map((f, i) => `${i}. [${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}（直し方: ${f.fix}）`).join('\n')
  const verdict = await agent(
    `${REPO} の PR #${impl.pr} へのレビュー指摘を検証する。コード（worktree ${impl.worktree}）を実際に読み、各指摘が本当に問題か確かめる。疑わしいもの、好みの問題、PR の範囲外のものは外す。直すべきものの番号だけを返す。
${listed}`,
    { label: `verify:${impl.pr}#${round}`, phase: 'Review', schema: VERDICT_SCHEMA },
  )
  if (!verdict) return findings.filter(f => f.severity === 'high')
  return verdict.confirmed.filter(i => findings[i]).map(i => findings[i])
}

const results = await pipeline(
  issues,

  // 実装して PR を作る
  (issue, _item, idx) => agent(implementPrompt(issue), {
    label: issue == null ? 'implement:pick' : `implement:#${issue}`,
    phase: 'Implement',
    schema: IMPLEMENT_SCHEMA,
  }),

  // CI → レビュー → 修正 を繰り返す
  async impl => {
    if (!impl || impl.status !== 'pr_opened' || !impl.pr) return { impl, ci: null, rounds: [] }
    const tag = `#${impl.issue}`
    let ci = await untilGreen(impl, tag)
    const rounds = []
    for (let round = 1; ci && ci.passed && round <= MAX_REVIEW_ROUNDS; round++) {
      const confirmed = await review(impl, round)
      const toFix = confirmed.filter(f => f.severity !== 'low')
      rounds.push({ round, confirmed })
      if (!toFix.length) break
      log(`PR #${impl.pr}: レビュー指摘 ${toFix.length} 件を修正（${round}/${MAX_REVIEW_ROUNDS}）`)
      const fixed = await agent(
        `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）へのレビュー指摘を直す。worktree の中で修正し、\`./gradlew build\` と関係する \`runGameTestServer\` を通してから、コミットして push する。
${toFix.map((f, i) => `${i + 1}. [${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}\n   直し方: ${f.fix}`).join('\n')}`,
        { label: `fix:${impl.pr}#${round}`, phase: 'Fix', schema: FIX_SCHEMA },
      )
      if (!fixed || !fixed.pushed) break
      ci = await untilGreen(impl, `${tag}-r${round}`)
    }
    return { impl, ci, rounds }
  },

  // PR に結果をまとめ、worktree を片付ける
  async ({ impl, ci, rounds }) => {
    if (!impl || impl.status !== 'pr_opened' || !impl.pr) return { impl, ci, rounds }
    const remaining = rounds.length ? rounds[rounds.length - 1].confirmed : []
    const state = !ci ? 'CI の確認に失敗' : ci.passed ? 'CI 通過' : `CI 失敗（${ci.failingChecks.join(', ')}）`
    await agent(
      `${REPO} の PR #${impl.pr} に、自動レビューの結果を日本語のコメントで投稿する（\`gh pr comment ${impl.pr} --body-file <ファイル>\`）。内容:
- CI: ${state}
- レビューの回数: ${rounds.length}
- 最後のレビューで残った指摘: ${remaining.length ? remaining.map(f => `[${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join(' / ') : 'なし'}
- 結論: ${ci && ci.passed && !remaining.some(f => f.severity !== 'low') ? 'マージ可能' : '人の確認が必要'}
投稿したら、リポジトリのルートで \`git worktree remove ${impl.worktree}\` を実行して worktree を片付ける（ブランチは残す）。`,
      { label: `report:${impl.pr}`, phase: 'Report', effort: 'low' },
    )
    return { impl, ci, rounds }
  },
)

return results.filter(Boolean).map(r => ({
  issue: r.impl?.issue ?? null,
  status: r.impl?.status ?? 'failed',
  pr: r.impl?.pr ?? null,
  summary: r.impl?.summary ?? '',
  ciPassed: r.ci?.passed ?? null,
  reviewRounds: r.rounds?.length ?? 0,
  remainingFindings: r.rounds?.length ? r.rounds[r.rounds.length - 1].confirmed : [],
}))
