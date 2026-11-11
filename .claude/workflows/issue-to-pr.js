export const meta = {
  name: 'issue-to-pr',
  description: 'Issue を worktree で実装して PR を作り、CI とレビューエージェントで確認・修正してマージする。人の確認が要るものは質問して止まる',
  whenToUse: 'KiBi Computers の Issue を自動で進めたいとき。args は Issue 番号・番号の配列・{ issues, window, merge }。省略するとエージェントが選ぶ。merge: false でマージしない。resume: [{ issue, pr }] で止まった PR を CI から続ける。',
  phases: [
    { title: 'Select', detail: '対象の Issue と依存・area・プランの要否を調べる' },
    { title: 'Plan', detail: 'ABI・保存形式・公開 API に関わる Issue だけ、プランを書いてレビューする' },
    { title: 'Implement', detail: 'worktree で実装し、build と両バージョンの GameTest が通るまで直して PR を作る' },
    { title: 'CI', detail: 'PR の CI を待つ。落ちたら直す' },
    { title: 'Review', detail: '正しさ・ルール違反のレビュー、公開 API の Java 相互運用レビュー、指摘の検証' },
    { title: 'Fix', detail: '確かな指摘を直して CI を待ち直す' },
    { title: 'Merge', detail: '1件ずつ main を取り込み、確認してからマージする（merge: false なら飛ばす）' },
    { title: 'Report', detail: 'PR に結果をまとめる。人の確認が要るものは Fable が前提と推奨を確かめてから質問して needs-human を付ける。worktree を片付ける' },
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

const IMPLEMENT_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['pr_opened', 'blocked'] },
    pr: { type: ['integer', 'null'] },
    branch: { type: ['string', 'null'] },
    worktree: { type: ['string', 'null'], description: 'worktree の絶対パス' },
    publicApiChanged: { type: 'boolean' },
    touchesConcurrency: { type: 'boolean' },
    summary: { type: 'string' },
  },
  required: ['status', 'pr', 'branch', 'worktree', 'publicApiChanged', 'touchesConcurrency', 'summary'],
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

function implement(info, approvedPlan, guidance, advice) {
  return agent(
    `${REPO} の Issue #${info.number}「${info.title}」を実装して PR を作る。手順は \`.claude/skills/work-issue/SKILL.md\` の 1〜4 に従う。ただし作業場所は、ユーザーのチェックアウトとは別の worktree にする:

1. リポジトリのルートで \`git fetch origin\` し、\`git worktree add .worktrees/issue-${info.number} -b issue-${info.number}-<短い英語名> origin/main\` を実行する（\`.worktrees/issue-${info.number}\` がもうあれば、それを続けて使う）。
2. 以降の編集・ビルド・テスト・コミット・push はすべてその worktree の中で行う（絶対パスで操作する）。\`git stash\` は使わない。
3. 実装ループ: 実装 → \`./gradlew build\` → \`./gradlew :platform-neoforge-1.21.1:runGameTestServer :platform-forge-1.20.1:runGameTestServer\` を、すべて通るまで繰り返す。
4. PR を \`gh pr create\` で作る（1つの Issue を複数の PR に分けるときは、AGENTS.md の手順で Stacked PRs にする）。本文の「概要」に、実装中に決めた細かい設計（設計メモ）を書く。マージはしない。
5. 仮素材（テクスチャ・モデル・GUI 画像）を追加したら、#250 の「仮素材の一覧」に追記する。

未決の \`decision\` に左右される論点や、AGENTS.md で人に聞くと決めた4つ（ABI・保存形式・公開 API・ゲームバランスの最終値）に当たったら、実装を止めて status=blocked で終える。summary に論点・選択肢・推奨を書く（Issue には質問をコメントしない。このスクリプトが確かめてから聞く）。
publicApiChanged には、\`public\` な宣言（特に \`pro.ikili.kibicomputers.api\` 以下）を追加・変更したかを入れる。touchesConcurrency には、スレッド・ロック・非同期の完了待ちなど、並行して動く処理の振る舞いを変えたかを入れる。${approvedPlan ? `\n\n承認済みのプラン（これに従う。従えない点があれば PR に理由を書く）:\n${approvedPlan}` : ''}${guidance ? `\n\n前に止まった論点について、人に聞く前の確かめで示された進め方（これに従う）:\n${guidance}` : ''}${advice ? `\n\n着手前の設計の助言（別のモデル。鵜呑みにせず、コードと突き合わせて使う）:\n${advice}` : ''}`,
    { label: `implement:#${info.number}`, phase: 'Implement', schema: IMPLEMENT_SCHEMA, ...M.implement },
  )
}

// CI の結果を待つ。passed が偽なのに落ちたチェックの名前がない結果は、まだ走っている間に返したものとみなして待ち直す
async function waitCi(pr, label) {
  let ci = null
  for (let attempt = 0; attempt < 3; attempt++) {
    ci = await agent(
      `${REPO} の PR #${pr} の CI が終わるまで待ち、結果を返す。\`gh pr checks ${pr} --watch --interval 30\` を使う（10分を超えそうならもう一度実行する）。pending・queued・in_progress のチェックが残っている間は返さない。終わったら \`gh pr checks ${pr} --json name,state,bucket\` で全部のチェックの結果を確かめ、passed は「すべて pass か skipping」のときだけ true にする。失敗したら failingChecks に落ちたチェックの名前を入れ、\`gh run view <run-id> --log-failed\` の末尾から原因が分かる部分を抜き出す。コードは変更しない。`,
      { label: attempt ? `${label}~${attempt}` : label, phase: 'CI', schema: CI_SCHEMA, ...M.ciWait },
    )
    if (!ci || ci.passed || ci.failingChecks.length) return ci
    log(`PR #${pr}: CI の結果に落ちたチェックの名前がない。待ち直す（${attempt + 1}/3）`)
  }
  return ci
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
  const findings = (await parallel(reviewers)).filter(Boolean).flatMap(r => r.findings.map(f => ({ ...f, by: r.by })))
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
  if (!vo && !vf) return findings.filter(f => f.severity === 'must')
  return findings.filter((f, i) => byOpus.has(i) || byFable.has(i))
}

async function mergePr(info, impl) {
  await mergeLock.acquire()
  try {
    const m = await agent(
      `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）をマージする。他の PR のマージとは直列に行っている。
1. worktree で \`git fetch origin\` し、\`git merge origin/main\` で main を取り込む（rebase と force push はしない）。衝突が字面の解消で済まない（ロジックの判断が要る）なら、マージせず status=needs_human で理由を返す。
2. 取り込んだ結果で \`./gradlew build\` と両バージョンの \`runGameTestServer\` を通す。通らなければ status=needs_human。
3. 取り込みでコミットが増えたら push する。push したかどうかによらず、\`gh pr checks ${impl.pr} --watch --interval 30\` で、先頭のコミットの CI がすべて終わって通るのを待つ（pending が残っている間はマージしない）。
4. \`gh pr merge ${impl.pr} --squash --delete-branch\` でマージする。「base branch policy prohibits the merge」で断られたら、\`gh pr view ${impl.pr} --json mergeStateStatus,statusCheckRollup\` で理由を確かめ、CI の待ちが理由なら 3 からもう1回やり直す。2回目も断られたら status=needs_human で理由を返す。`,
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

    const tag = `#${info.number}`
    let ci = await untilGreen(impl, tag)
    const rounds = []
    for (let round = 1; ci && ci.passed && round <= MAX_REVIEW_ROUNDS; round++) {
      const confirmed = await review(info, impl, round)
      // 検証で残った指摘は must・should を問わず直す（should をマージ前に止めるため）
      const toFix = confirmed
      rounds.push({ round, confirmed })
      if (!toFix.length) break
      log(`PR #${impl.pr}: 指摘 ${toFix.length} 件を修正（${round}/${MAX_REVIEW_ROUNDS}）`)
      const fixed = await agent(
        `${REPO} の PR #${impl.pr}（ブランチ ${impl.branch}、worktree ${impl.worktree}）へのレビュー指摘を直す。worktree の中で修正し、\`./gradlew build\` と両バージョンの \`runGameTestServer\` を通してから、コミットして push する。
Issue にし忘れた別の問題は、コードを直す代わりに新しい Issue にして適切な Epic のサブ Issue にし（AGENTS.md の手順）、PR 本文に番号を書く。
${toFix.map((f, i) => `${i + 1}. [${f.severity}] ${f.file}:${f.line ?? '-'} ${f.problem}`).join('\n')}`,
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
2. リポジトリのルートで \`git fetch origin\` し、\`.worktrees/issue-${issue}\` がなければ \`git worktree add .worktrees/issue-${issue} <headRefName>\`（ローカルのブランチがなければ \`origin/<headRefName>\` から作る）。あれば \`git -C .worktrees/issue-${issue} pull --ff-only\` で先頭に合わせる。
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
