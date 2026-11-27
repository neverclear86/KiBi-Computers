// issue-to-pr.js（保存済みの Workflow）の、Gradle を待たずに返した段階のやり直し（#904）、マージの段階が手元で Gradle を動かさないこと（#985）、
// マージの段階の push の後の CI の、不安定なテストの回し直し（#961）、結果を返さなかった段階の呼び直しと記録（#953）の検査。
// 偽の agent でスクリプト全体を流し、どの段階が何回呼ばれたか、最後の結果がどうなるかを見る。
// 実行: node --test .claude/workflow-tests/
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const SOURCE = readFileSync(join(here, '..', 'workflows', 'issue-to-pr.js'), 'utf8').replace('export const meta =', 'const meta =')
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor

const PR = 906
const IMPL = {
  status: 'pr_opened', pr: PR, branch: 'issue-904-x', worktree: 'D:/w/issue-904', publicApiChanged: false,
  touchesConcurrency: false, summary: 'done', openQuestions: [], gradleStillRunning: false, runningLogs: [],
}
const OK_CI = { passed: true, failingChecks: [], failureLog: '', mergeable: 'MERGEABLE', noChecks: false }
const MERGED = {
  status: 'merged', reason: null, failedStep: null, gameTestFailedOnly: false, gradleStartupFailed: false, gameTestSeconds: 100, maxTicksBehind: 0,
  logPath: null, gradleStillRunning: false, runningLogs: [],
}
const fail = (failedStep, reason, extra = {}) => ({ ...MERGED, status: 'needs_human', failedStep, reason, ...extra })
const PENDING = { gradleStillRunning: true, runningLogs: ['merge-build-2.log'] }

// スタックでない PR の判定の結果（#936）
const NO_STACK = { baseRef: 'main', stackNumber: null, members: [] }

// CI の段階で main と衝突していた回と、取り込みの段階（mergeMain）の結果（#855）
const CONFLICT = { ...OK_CI, passed: false, mergeable: 'CONFLICTING', noChecks: true }
const CONFLICT_OK = { outcome: 'merged', pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }
const buildFailed = summary => ({ outcome: 'build_failed', pushed: false, summary, gradleStillRunning: false, runningLogs: [] })
// 理由の文で Gradle の未完了を見分ける判定（#904）を、取り込みの段階で流す（build_failed の summary を見る）。
// 未完了と見れば conflict-wait（retry）に進み、取り込んでマージまで進む
const viaConflict = (text, retry = [CONFLICT_OK]) => run({ ci: [CONFLICT, OK_CI], conflict: [buildFailed(text)], 'conflict-wait': retry })

// overrides: ラベルの種類（'merge' など。`:` より前）→ 呼ばれる順の応答（なければ既定）。関数なら呼んで使う。
// 配列でなく関数を渡したら、毎回 (prompt, label) で呼んで応答にする（複数の Issue を流すときに、プロンプトで答えを分ける）
async function run(overrides = {}, args = { issues: [904] }) {
  const queues = Object.fromEntries(Object.entries(overrides).filter(([, v]) => Array.isArray(v)).map(([k, v]) => [k, [...v]]))
  const responders = Object.fromEntries(Object.entries(overrides).filter(([, v]) => typeof v === 'function'))
  const calls = []
  const prompts = []
  const logs = []
  const defaults = {
    select: () => ({ issues: [{ number: 904, title: 't', areas: ['tooling'], openDeps: [], blockedReason: null, needsPlan: false }] }),
    advise: () => 'advice',
    implement: () => IMPL,
    ci: () => OK_CI,
    review: () => ({ findings: [] }),
    verify: () => ({ confirmed: [], notes: '' }),
    merge: () => MERGED,
    cleanup: () => ({ removed: true, note: '' }),
    // 報告と質問の投稿は文を返す（null なら 1 回だけ呼び直すので、既定で何か返す。#953）
    report: () => 'ok',
    ask: () => 'ok',
    'cross-check': prompt => ({ premiseSound: true, needsHuman: true, findings: prompt, question: 'q', guidance: null }),
    // スタック（#936）。既定はスタックでない PR
    'stack-detect': () => NO_STACK,
    'stack-check': () => NO_STACK,
    'stack-sync': () => ({ outcome: 'merged', pushed: true, headSha: 'abc', worktree: 'D:/w', summary: 'ok' }),
    'stack-merge': () => ({ ...MERGED, stackMergeOutcome: 'merged' }),
    'stack-note': () => 'ok',
  }
  const agent = async (prompt, opts) => {
    const kind = opts.label.split(':')[0].replace(/~\d+$/, '')
    calls.push(kind)
    prompts.push({ kind, prompt, opts })
    const q = queues[kind]
    const next = responders[kind] ? responders[kind](prompt, opts.label) : q && q.length ? q.shift() : (defaults[kind] ?? (() => undefined))(prompt)
    return typeof next === 'function' ? next() : next
  }
  const parallel = fns => Promise.all(fns.map(f => f()))
  const fn = new AsyncFunction('args', 'agent', 'parallel', 'log', 'phase', SOURCE)
  const result = await fn(args, agent, parallel, m => logs.push(m), () => {})
  const count = k => calls.filter(c => c === k).length
  const promptOf = k => prompts.find(p => p.kind === k)?.prompt
  const promptsOf = k => prompts.filter(p => p.kind === k)
  const schemaOf = k => prompts.find(p => p.kind === k)?.opts.schema
  return { result, calls, logs, count, promptOf, promptsOf, prompts, schemaOf, issue: result.results[0] }
}

test('既定の流れはマージまで進み、やり直しは呼ばれない', async () => {
  const r = await run()
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-wait'), 0)
  assert.equal(r.count('merge'), 1)
})

// 理由の文で Gradle の未完了を見分ける判定（#904）は、マージの段階が Gradle を動かさなくなった（#985）ので、
// 取り込みの段階（outcome=build_failed の summary）で検査する
// PR #889 のマージの段階が実際に返した理由の文（切らずにそのまま）。1 回目の失敗の記述（「落ちたので」「落ちたわけではないが」）を含む
const PR889_REASON = 'マージは行っていない。main の取り込みは衝突なしで済んだ（コミット 65c5fb52。まだ push していない）。最初の `./gradlew build` は、maven.neoforged.net の名前解決の失敗（一時的なネットワークの不調）で落ちたので、同じ内容でやり直した（merge-build-2）。やり直しは返す時点で :core:test を実行中で、終了コードを読めていない。GameTest・CI・マージの段階には進んでいない。build が落ちたわけではないが、スキーマに「未完了」がないため needs_human で返す。この段階を最初からやり直してほしい。'

test('#889 の実際の文（申告は false、build_failed）で返った取り込みは、待つやり直しで進む', async () => {
  const r = await viaConflict(PR889_REASON)
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('conflict'), 1)
  assert.equal(r.count('conflict-wait'), 1)
  assert.ok(r.logs.some(l => l.includes('決まりに反した申告')))
})

// ネットワークの失敗を回し直し、その回し直しを待たずに返した回は、理由の文に 1 回目の失敗の記述が入る。それでも未完了と見る
for (const [name, text] of [
  ['強い手がかり（終了コード）と、1 回目の Could not HEAD', '1 回目は Could not HEAD https://maven.neoforged.net/... で落ちた。回し直しの build-2 は終了コードを確かめていない'],
  ['強い手がかり（.exit がない）と、1 回目の UnknownHost', '1 回目は UnknownHostException で落ちたので回し直した。build-2 の .exit がまだない'],
  ['弱い手がかりだけで、1 回目の失敗は別の一文', '1 回目は Read timed out で落ちた。回し直しの :core:test がまだ実行中'],
]) {
  test(`回し直しを待たずに返した文は、1 回目の失敗の記述があっても待つやり直しをする: ${name}`, async () => {
    const r = await viaConflict(text)
    assert.equal(r.issue.status, 'merged')
    assert.equal(r.count('conflict-wait'), 1)
  })
}

// 理由の文が、Gradle が終わったあとの本物の失敗を述べている回は、やり直さない
for (const [name, text] of [
  ['ロックの待ちの上限（Held by に別の worktree のタスク名）', 'GameTest のロックの待ちの上限（90 分）に達した。Held by: :platform-neoforge-1.21.1:runGameTestServer in .worktrees/issue-900 が実行中のまま'],
  ['GameTest のタイムアウト', 'GameTest が時間内に終わっていない（タイムアウト）'],
  ['サーバーが落ちた', 'テストが終わる前にサーバーが落ちた'],
  ['GameTest のテストの失敗（文は「実行中」を含む）', 'GameTest が実行中に 2 件失敗した（required tests failed）'],
  ['コンパイルの失敗', ':core:compileKotlin が失敗した（型の不一致）'],
  ['ロックの待ちの上限と、別の一文の「実行中」', 'GameTest のロックの待ちの上限に達した。Held by: runGameTestServer in .worktrees/issue-900。そのタスクはまだ実行中だった'],
  ['ネットワークの失敗が 2 回続いた', '依存の取得の失敗（ネットワーク）が 2 回続いた。2 回目も Could not HEAD で落ちた'],
]) {
  test(`本物の失敗は待つやり直しをしない: ${name}`, async () => {
    const r = await viaConflict(text)
    assert.equal(r.count('conflict-wait'), 0)
    assert.notEqual(r.issue.status, 'merged')
    assert.doesNotMatch(JSON.stringify(r.result), /2 回返した/)
  })
}

test('実装: 1 回目が未完了なら implement-wait で進む', async () => {
  const r = await run({ implement: [{ ...IMPL, ...PENDING }], 'implement-wait': [IMPL] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('implement-wait'), 1)
})

test('実装: 2 回とも未完了なら、CI に進まず、PR を残して人に上がる', async () => {
  const r = await run({ implement: [{ ...IMPL, ...PENDING }], 'implement-wait': [{ ...IMPL, ...PENDING }] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.issue.pr, PR)
  assert.equal(r.count('ci'), 0)
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.count('ask'), 1)
  assert.ok(r.issue.question)
  assert.match(r.issue.reason, /2 回返した/)
})

test('実装: PR を作る前に 2 回とも未完了なら、Issue で人に上がる', async () => {
  const noPr = { ...IMPL, status: 'failed', pr: null, ...PENDING }
  const r = await run({ implement: [noPr], 'implement-wait': [noPr] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.issue.pr, null)
  assert.equal(r.count('ask'), 1)
})

test('実装: blocked なら、gradleStillRunning=true でも待つやり直しをせずに人の判断へ回る', async () => {
  const r = await run({ implement: [{ ...IMPL, status: 'blocked', pr: null, summary: 'decision が未決', ...PENDING }] })
  assert.equal(r.count('implement-wait'), 0)
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.issue.status, 'needs_human')
})

test('実装: やり直しが結果を返さなかったら failed で、「2 回返した」とは書かない', async () => {
  const r = await run({ implement: [{ ...IMPL, ...PENDING }], 'implement-wait': [null] })
  assert.equal(r.issue.status, 'failed')
  assert.doesNotMatch(r.issue.reason, /2 回返した/)
})

test('取り込み: 未完了ならやり直しで進む', async () => {
  const r = await run({
    ci: [CONFLICT, OK_CI],
    conflict: [{ outcome: 'build_failed', pushed: false, summary: 'build が実行中', ...PENDING }],
    'conflict-wait': [{ outcome: 'merged', pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('conflict-wait'), 1)
})

test('取り込み: 2 回とも未完了なら、push せず build_failed 扱いで止まる（成功扱いにしない）', async () => {
  const r = await run({
    ci: [CONFLICT],
    conflict: [{ outcome: 'merged', pushed: true, summary: 'x', ...PENDING }],
    'conflict-wait': [{ outcome: 'merged', pushed: true, summary: 'y', ...PENDING }],
  })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('conflict-wait'), 1)
  assert.equal(r.count('merge'), 0)
})

test('取り込み: やり直しが結果を返さなかったら、「2 回返した」とは書かない', async () => {
  const r = await run({
    ci: [CONFLICT],
    conflict: [{ outcome: 'build_failed', pushed: false, summary: 'build が実行中', ...PENDING }],
    'conflict-wait': [null],
  })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('merge'), 0)
  assert.doesNotMatch(JSON.stringify(r.result), /2 回返した/)
})

const FAILED_CI = { passed: false, failingChecks: ['build'], failureLog: 'e', mergeable: 'MERGEABLE', noChecks: false }

test('CI の直し: 未完了ならやり直しで進む', async () => {
  const r = await run({
    ci: [FAILED_CI, OK_CI],
    'ci-fix': [{ pushed: true, summary: 'x', ...PENDING }],
    'ci-fix-wait': [{ pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('ci-fix-wait'), 1)
})

test('CI の直し: 2 回とも未完了なら、直ったことにせず止まる（再待ちもマージもしない）', async () => {
  const r = await run({
    ci: [FAILED_CI],
    'ci-fix': [{ pushed: true, summary: 'x', ...PENDING }],
    'ci-fix-wait': [{ pushed: true, summary: 'y', ...PENDING }],
  })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('ci'), 1)
  assert.equal(r.count('merge'), 0)
  assert.match(r.issue.question, /2 回返した/)
})

test('CI の直し: 不安定なテストで回し直した（rerun=true）なら、gradleStillRunning=true が付いても待つやり直しはしない', async () => {
  const r = await run({
    ci: [FAILED_CI, OK_CI],
    'ci-fix': [{ pushed: false, rerun: true, summary: 'flaky #1', ...PENDING }],
    'ci-fix-wait': [{ pushed: false, rerun: true, summary: 'flaky #2', gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(r.count('ci-fix'), 1)
  assert.equal(r.count('ci-fix-wait'), 0)
  assert.equal(r.count('ci'), 2)
  assert.equal(r.issue.status, 'merged')
})

test('CI の直し: push した直しの gradleStillRunning=true は、rerun が偽なら待つやり直しになる', async () => {
  const r = await run({
    ci: [FAILED_CI, OK_CI],
    'ci-fix': [{ pushed: true, rerun: false, summary: 'x', ...PENDING }],
    'ci-fix-wait': [{ pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(r.count('ci-fix-wait'), 1)
})

const FINDING = { file: 'a.kt', line: 1, severity: 'should', problem: 'p' }

test('レビューの直し: 未完了ならやり直しで進む', async () => {
  const r = await run({
    review: [{ findings: [FINDING] }],
    verify: [{ confirmed: [0], notes: '' }],
    fix: [{ pushed: true, summary: 'x', ...PENDING }],
    'fix-wait': [{ pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('fix-wait'), 1)
})

test('レビューの直し: 2 回とも未完了なら、マージせずに止まる', async () => {
  const r = await run({
    review: [{ findings: [FINDING] }],
    verify: [{ confirmed: [0], notes: '' }],
    fix: [{ pushed: true, summary: 'x', ...PENDING }],
    'fix-wait': [{ pushed: true, summary: 'y', ...PENDING }],
  })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('merge'), 0)
  assert.match(r.issue.question, /確かめられなかった/)
})

test('確認事項の反映: 2 回とも未完了なら、先へ進まず人に上がる', async () => {
  const r = await run({
    implement: [{ ...IMPL, openQuestions: ['q'] }],
    'cross-check': [{ premiseSound: true, needsHuman: false, findings: 'f', question: null, guidance: 'g' }],
    resolve: [{ pushed: true, summary: 'x', ...PENDING }],
    'resolve-wait': [{ pushed: true, summary: 'y', ...PENDING }],
  })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.issue.pr, PR)
  assert.equal(r.count('ci'), 0)
  assert.equal(r.count('ask'), 1)
  assert.match(r.issue.reason, /2 回返した/)
})

test('確認事項の反映: push しなくても、申告が false なら未完了とは見ない', async () => {
  const r = await run({
    implement: [{ ...IMPL, openQuestions: ['q'] }],
    'cross-check': [{ premiseSound: true, needsHuman: false, findings: 'f', question: null, guidance: 'g' }],
    resolve: [{ pushed: false, summary: '変更なし。build は実行中のものはない', gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(r.count('resolve-wait'), 0)
  assert.equal(r.issue.status, 'merged')
})

// ---- マージの段階は手元で Gradle を動かさない（#985） ----
// 前は、マージの段階の Gradle の起動の失敗（#958）・過負荷の GameTest（#855）・Gradle を待たずに返した回（#904）を、
// スクリプトが 1 回だけやり直していた。手元の Gradle がなくなったので、どの形の結果でもやり直さずに止まる

const STARTUP = { gradleStartupFailed: true }
const OVERLOAD = { gameTestFailedOnly: true, maxTicksBehind: 5000, gameTestSeconds: 4000 }
// PR #951 のマージの段階が実際に返した理由の文（切らずにそのまま）
const PR951_REASON = 'main は取り込み済み（衝突なし、既に最新）。build は 1 回目が Forge の maven への DNS 失敗で落ち、2 回目で通った。GameTest は Gradle の起動時に「java.net.BindException: Address already in use: bind」で起動できず、サーバーは動いていない（テストの失敗ではない）。回し直しはこの段階では行わない決まりで、自動の再実行も拒否されたため止めた。マージ、push、CI の待ちはしていない。'
const RETRY_KINDS = ['merge-wait', 'merge-retry', 'merge-restart', 'merge-retry-wait', 'merge-restart-wait', 'merge-rerun-wait']

for (const [name, m] of [
  ['起動の失敗（build）', fail('build', 'Gradle could not start your build', STARTUP)],
  ['起動の失敗（gametest、#951 の文）', fail('gametest', PR951_REASON)],
  ['過負荷の GameTest', fail('gametest', 'GameTest が落ちた', OVERLOAD)],
  ['Gradle の未完了の申告', fail('build', 'build 実行中', PENDING)],
  ['Gradle の未完了の文（#889）', fail('build', PR889_REASON)],
]) {
  test(`マージの段階の結果が ${name} の形でも、やり直しは 1 回も呼ばず、人に上がる（#985）`, async () => {
    const r = await run({ merge: [m] })
    for (const k of RETRY_KINDS) assert.equal(r.count(k), 0, k)
    assert.equal(r.count('merge'), 1)
    assert.equal(r.count('merge-flaky'), 0)
    // マージしていない（レビューまでは通っているので、結果の status は ready のまま。人には聞く）
    assert.notEqual(r.issue.status, 'merged')
    assert.equal(r.count('cross-check'), 1)
    assert.equal(r.count('ask'), 1)
  })
}

test('マージの段階の CI の待ちの記述（「GameTest が実行中」）は、Gradle の未完了と取り違えず、merge-flaky に回る（#985）', async () => {
  const r = await run({ merge: [fail('ci', 'GameTest (NeoForge 1.21.1) が実行中のまま待ちの上限に達した')], 'merge-flaky': [{ outcome: 'pending', runId: '123', headSha: 'abc', issue: null, summary: 'まだ終わっていない', gradleStillRunning: false, runningLogs: [] }], 'merge-rerun': [MERGED] })
  for (const k of RETRY_KINDS) assert.equal(r.count(k), 0, k)
  assert.equal(r.count('merge-flaky'), 1)
  assert.equal(r.issue.status, 'merged')
})

test('取り込みが Already up to date で CI が通っていれば、マージの段階 1 回でマージまで進む（#985）', async () => {
  const r = await run({ merge: [{ ...MERGED, reason: 'Already up to date。HEAD は headRefOid と同じで push していない。先頭のコミットの CI は通っていた' }] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge'), 1)
  for (const k of RETRY_KINDS) assert.equal(r.count(k), 0, k)
  const p = r.promptOf('merge')
  assert.match(p, /`git rev-parse HEAD` と `gh pr view 906 --json headRefOid` を比べる/)
  assert.match(p, /Already up to date で push もせず、先頭のコミットの CI がもう通っていれば、待たずにそのまま手順 4/)
})

test('マージの段階が結果を返さなければ、呼び直さずに人に上がる', async () => {
  const r = await run({ merge: [null] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('ask'), 1)
  assert.equal(r.count('merge'), 1)
  assert.match(r.issue.mergeReason, /マージの段階が失敗した/)
})

// ---- マージの段階の push の後の CI の、不安定なテストの回し直し（#961） ----

const CI_FAIL = fail('ci', '先頭のコミットの CI が落ちた（sendableButUnsentChunkIsNotWatched）')
const FLAKY = { outcome: 'rerun', runId: '123', headSha: 'abc', issue: 934, summary: '#934 に記録して回し直した', gradleStillRunning: false, runningLogs: [] }
const NOT_FLAKY = { ...FLAKY, outcome: 'not_flaky', runId: null, issue: null, summary: 'PR の変更が原因' }

test('push の後の CI が不安定なテストで落ちたら、merge-flaky で回し直し、merge-rerun でマージまで進む', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge'), 1)
  assert.equal(r.count('merge-flaky'), 1)
  assert.equal(r.count('merge-rerun'), 1)
  assert.equal(r.count('merge-flaky-wait'), 0)
  assert.match(r.issue.mergeReason, /不安定なテスト（#934）/)
  assert.match(r.issue.mergeReason, /run 123/)
  assert.equal(r.count('cross-check'), 0)
})

test('回し直しのあとも CI が落ちたら、もう回し直さずに止まって人に上がる', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [fail('ci', '2 回目も落ちた')] })
  assert.notEqual(r.issue.status, 'merged')
  assert.ok(r.issue.question)
  assert.equal(r.count('merge-flaky'), 1)
  assert.equal(r.count('merge-rerun'), 1)
  assert.match(r.issue.mergeReason, /回し直しのあとの理由: 2 回目も落ちた/)
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.count('ask'), 1)
})

for (const [name, f] of [['not_flaky', NOT_FLAKY], ['結果なし', null]]) {
  test(`確かめが ${name} なら、merge-rerun を呼ばずに止まる`, async () => {
    const r = await run({ merge: [CI_FAIL], 'merge-flaky': [f] })
    assert.notEqual(r.issue.status, 'merged')
    assert.equal(r.count('merge-flaky'), 1)
    assert.equal(r.count('merge-rerun'), 0)
    assert.match(r.issue.mergeReason, /回し直していない/)
  })
}

test('CI がまだ終わっていなかった（pending）なら、merge-rerun で待ち直す', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...FLAKY, outcome: 'pending', issue: null }], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-rerun'), 1)
  assert.match(r.issue.mergeReason, /まだ終わっていなかった/)
  assert.doesNotMatch(r.promptOf('merge-rerun'), /不安定なテスト（Issue/)
})

const FLAKY_PENDING = { ...FLAKY, outcome: 'pending', issue: null }

test('待ったあと（pending → merge-rerun）に CI が不安定なテストで落ちたら、確かめ直して回し直し、もう 1 回呼び直す', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY_PENDING, FLAKY], 'merge-rerun': [fail('ci', '待ったあとに落ちた'), MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky'), 2)
  assert.equal(r.count('merge-rerun'), 2)
  assert.match(r.issue.mergeReason, /不安定なテスト（#934）/)
})

test('待ったあとの確かめが not_flaky なら、回し直さずに止まる', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY_PENDING, NOT_FLAKY], 'merge-rerun': [fail('ci', '待ったあとに落ちた')] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky'), 2)
  assert.equal(r.count('merge-rerun'), 1)
})

test('待ったあとの確かめがまた pending なら、もう待たずに止まる', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY_PENDING, FLAKY_PENDING], 'merge-rerun': [fail('ci', '待ったあとに落ちた')] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky'), 2)
  assert.equal(r.count('merge-rerun'), 1)
})

test('待ったあとに 2 回目の回し直しまで落ちたら、それ以上は確かめない', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY_PENDING, FLAKY, FLAKY], 'merge-rerun': [fail('ci', 'a'), fail('ci', 'b')] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky'), 2)
  assert.equal(r.count('merge-rerun'), 2)
})

for (const [name, m] of [
  ['test', fail('test', 'node --test が落ちた')],
  ['conflict', fail('conflict', '字面で解けない')],
  ['merge', fail('merge', 'base branch policy')],
]) {
  test(`failedStep=${name} なら merge-flaky を呼ばない`, async () => {
    const r = await run({ merge: [m] })
    assert.equal(r.count('merge-flaky'), 0)
    assert.notEqual(r.issue.status, 'merged')
  })
}

test('確かめが rerun で gradleStillRunning=true なら、merge-flaky-wait はせず、merge-rerun は Gradle を待たずに CI を待つ（#985）', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...FLAKY, ...PENDING }], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky-wait'), 0)
  assert.equal(r.count('merge-rerun'), 1)
  // merge-rerun は Gradle を起動しないので、孤児の Gradle を待つ手順は要らない（残った Gradle は片付けの待ちが受け止める）
  assert.doesNotMatch(r.promptOf('merge-rerun'), /確かめの段階が残した Gradle を待つ/)
})

test('確かめが not_flaky で gradleStillRunning=true なら、merge-flaky-wait で待ってやり直す', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...NOT_FLAKY, ...PENDING }], 'merge-flaky-wait': [FLAKY], 'merge-rerun': [MERGED] })
  assert.equal(r.count('merge-flaky-wait'), 1)
  assert.equal(r.issue.status, 'merged')
})

test('確かめが 2 回とも Gradle を待たずに返したら、回し直さずに止まる', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...NOT_FLAKY, ...PENDING }], 'merge-flaky-wait': [{ ...NOT_FLAKY, ...PENDING }] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-rerun'), 0)
  assert.match(r.issue.mergeReason, /2 回返した/)
})

test('merge-rerun が Gradle の未完了・起動の失敗・過負荷の形で返っても、やり直しも確かめ直しもせずに止まる（#985）', async () => {
  for (const extra of [PENDING, { gradleStartupFailed: true }, { gameTestFailedOnly: true, maxTicksBehind: 5000 }]) {
    const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [fail('build', '終了コードを読めていない', extra)] })
    assert.notEqual(r.issue.status, 'merged')
    for (const k of ['merge-rerun-wait', 'merge-restart', 'merge-retry', 'merge-wait']) assert.equal(r.count(k), 0, k)
    assert.equal(r.count('merge-flaky'), 1)
  }
})

test('プロンプト: 確かめは CI の直しと同じ条件の文を使ってコードを直さず、merge-rerun は自分で回し直さない', async () => {
  const r = await run({ ci: [FAILED_CI, OK_CI], 'ci-fix': [{ pushed: false, rerun: true, summary: 'flaky' }], merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [MERGED] })
  const flaky = r.promptOf('merge-flaky')
  const ciFix = r.promptOf('ci-fix')
  const criteria = 'PR が触っていない所のテストで、手元で繰り返しても再現しない、main でも同じテストが落ちたことがある'
  assert.ok(flaky.includes(criteria))
  assert.ok(ciFix.includes(criteria))
  for (const p of [flaky, ciFix]) {
    assert.match(p, /gh run rerun <run-id> --failed/)
    assert.match(p, /test-reports-attempt-<n>/)
    assert.match(p, /サブ Issue/)
  }
  assert.match(flaky, /コードは直さず、コミットも push もしない/)
  assert.match(r.promptOf('merge'), /CI は自分で回し直さない/)
  assert.match(flaky, /merge-flaky-build-\$n/)
  assert.match(flaky, /headRefOid/)
  const rerun = r.promptOf('merge-rerun')
  assert.doesNotMatch(rerun, /merge-rerun-build-\$n/)
  assert.match(rerun, /自分では `gh run rerun` しない/)
  assert.match(rerun, /先頭のコミットは abc のまま/)
  assert.match(rerun, /落ちたジョブを回し直した/)
})

test('プロンプト: 確かめが pending なら、merge-rerun は「回し直してはいない」と書く', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY_PENDING], 'merge-rerun': [MERGED] })
  const rerun = r.promptOf('merge-rerun')
  assert.match(rerun, /CI がまだ終わっていなかったと判断した（run 123。回し直してはいない）/)
  assert.doesNotMatch(rerun, /落ちたジョブを回し直した/)
})

test('確かめが headSha=null を返しても、merge-rerun に進む', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...FLAKY, headSha: null }], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.match(r.promptOf('merge-rerun'), /先頭のコミットが変わっていない/)
})

test('確かめが runId を数値で返しても、そのまま merge-rerun に進む', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...FLAKY, runId: 123456 }], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.match(r.promptOf('merge-rerun'), /run 123456 の落ちたジョブを回し直した/)
})

// ---- 長い Gradle の待ち方と、孤児のログの結果の扱い（#962） ----

// Gradle を動かす全段階のプロンプトを集める（段階の種類 → プロンプト）
async function gradleStagePrompts() {
  const out = {}
  const take = (r, kinds) => { for (const k of kinds) { const p = r.promptOf(k); if (p) out[k] = p } }
  take(await run({ ci: [CONFLICT, OK_CI], conflict: [CONFLICT_OK] }), ['implement', 'conflict', 'merge', 'cleanup'])
  take(await run({ ci: [FAILED_CI, OK_CI], 'ci-fix': [{ pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }] }), ['ci-fix'])
  take(await run({ review: [{ findings: [FINDING] }], verify: [{ confirmed: [0], notes: '' }], fix: [{ pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }] }), ['fix'])
  take(await run({
    implement: [{ ...IMPL, openQuestions: ['q'] }],
    'cross-check': [{ premiseSound: true, needsHuman: false, findings: 'f', question: null, guidance: 'g' }],
    resolve: [{ pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }],
  }), ['resolve'])
  take(await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [MERGED] }), ['merge-flaky', 'merge-rerun'])
  return out
}

// Gradle を動かす段階（マージの段階は #985 から入らない）
const GRADLE_KINDS = ['implement', 'conflict', 'ci-fix', 'fix', 'resolve', 'merge-flaky']

test('プロンプト: Gradle を動かす全段階に、ターンを終えない待ち方（前景のループ）があり、Monitor で待てとは書かない', async () => {
  const prompts = await gradleStagePrompts()
  const kinds = [...GRADLE_KINDS, 'cleanup']
  for (const k of kinds) {
    const p = prompts[k]
    assert.ok(p, k)
    assert.match(p, /ツールを呼ばない返事[^。]*でターンを終えない/, k)
    assert.match(p, /timeout: 600000/, k)
    assert.match(p, /seq 1 57/, k)
    assert.match(p, /待ちに Monitor を使わない/, k)
    assert.doesNotMatch(p, /Monitor (など)?で待つ/, k)
    assert.doesNotMatch(p, /前景の `sleep` は使えない/, k)
  }
  for (const k of GRADLE_KINDS) {
    const p = prompts[k]
    assert.match(p, /20〜25 分/, k)
    assert.match(p, /\.exit` は自分で書かない/, k)
    assert.match(p, /<名前>\.tree/, k)
    assert.match(p, /GIT_INDEX_FILE=\$t git write-tree/, k)
    assert.match(p, /起動から 150 分/, k)
    // 起動の失敗は、どの段階でもエージェントが 90 秒置いて 1 回だけ回し直す（スクリプトの回し直しはもうない。#985）
    assert.match(p, /90 秒以上置いてから/, k)
    assert.doesNotMatch(p, /gradleStartupFailed=true で返す/, k)
  }
})

test('プロンプト: マージの段階（ふつう・CI の回し直しのあと）は、手元で Gradle を動かさず、node --test だけを条件つきで回す（#985）', async () => {
  const prompts = await gradleStagePrompts()
  for (const k of ['merge', 'merge-rerun']) {
    const p = prompts[k]
    assert.doesNotMatch(p, /\.\/gradlew (build|:)/, k)
    assert.doesNotMatch(p, /runGameTestServer/, k)
    assert.doesNotMatch(p, /gameTestFailedOnly|gradleStartupFailed|maxTicksBehind|gameTestSeconds/, k)
    assert.doesNotMatch(p, /\.worktrees\/\.logs\/issue-904\/merge/, k)
    assert.match(p, /`\.\/gradlew` は起動しない/, k)
    assert.match(p, /node --test \.claude\/workflow-tests\//, k)
    assert.match(p, /headRefOid/, k)
    assert.match(p, /pass か skipping/, k)
    assert.match(p, /Already up to date で push もせず、先頭のコミットの CI がもう通っていれば、待たずにそのまま手順 4/, k)
  }
  assert.match(prompts['merge-rerun'], /自分では `gh run rerun` しない/)
  assert.doesNotMatch(prompts['merge-rerun'], /確かめの段階が残した Gradle を待つ|飛ばしてよい/)
  // スキーマにも Gradle の項目がない
  const r = await run()
  const props = Object.keys(r.schemaOf('merge').properties)
  for (const k of ['gradleStillRunning', 'runningLogs', 'gameTestFailedOnly', 'gradleStartupFailed', 'gameTestSeconds', 'maxTicksBehind', 'logPath']) assert.ok(!props.includes(k), k)
})

test('プロンプト: 実装の段階は通しの build と両 GameTest を残し、直しの段階は触ったものだけを手元で確かめる（#985）', async () => {
  const prompts = await gradleStagePrompts()
  assert.match(prompts.implement, /`\.\/gradlew build` → `\.\/gradlew :platform-neoforge-1\.21\.1:runGameTestServer :platform-forge-1\.20\.1:runGameTestServer`/)
  assert.doesNotMatch(prompts.implement, /手元の確認（#985/)
  for (const k of ['fix', 'resolve', 'ci-fix']) {
    const p = prompts[k]
    assert.match(p, /手元の確認（#985。通しの `\.\/gradlew build` と両バージョンの `runGameTestServer` は、ここでは回さない/, k)
    assert.doesNotMatch(p, /`\.\/gradlew build` と両バージョンの `runGameTestServer` を通してから/, k)
    assert.match(p, /:core:testClasses/, k)
    assert.match(p, /:platform-neoforge-1\.21\.1:compileKotlin :platform-forge-1\.20\.1:compileKotlin/, k)
    assert.match(p, /\*InternalMarkerTest/, k)
    assert.match(p, /\*SyscallDocTest/, k)
    assert.match(p, /checkKotlinAbi/, k)
    assert.match(p, /そのバージョンの `runGameTestServer` だけ/, k)
    assert.match(p, /No tests found for given includes/, k)
    assert.match(p, /段階の始めに worktree で `git rev-parse HEAD` を記録し/, k)
    assert.match(p, /summary には、手元で回したコマンド/, k)
  }
  // CI の直しは、落ちたものを手元で再現する Gradle を許す（不安定なテストかの判断に要る）
  assert.match(prompts['ci-fix'], /落ちたものを手元で再現するための `\.\/gradlew`/)
  // 衝突の取り込みは build を残す（範囲外）。GameTest は回さない
  assert.match(prompts.conflict, /取り込んだ結果で `\.\/gradlew build` を通す/)
})

test('プロンプト: 直しの段階の手元の確認は、どの決まりにも当たらないビルド入力（dev-driver・ルートの gradlew・api のダンプ）を素通りさせない（#985）', async () => {
  const prompts = await gradleStagePrompts()
  for (const k of ['fix', 'resolve', 'ci-fix']) {
    const p = prompts[k]
    assert.match(p, /:dev-driver:compileKotlin/, k)
    assert.match(p, /core\/api\/\*\.api/, k)
    assert.match(p, /platform-\*\/api\/\*\.api/, k)
    assert.match(p, /上のどの決まりにも当たらない、ビルドに入るファイル/, k)
    assert.match(p, /gradlew/, k)
    assert.match(p, /\.editorconfig/, k)
    assert.match(p, /手元で何も回さずに push してはいけない/, k)
  }
})

test('プロンプト: ワークフローを変えた差分は、ビルドに入るファイルと一緒でも、実装・衝突の取り込み・直しの段階で node --test を回す（#985）', async () => {
  const prompts = await gradleStagePrompts()
  for (const k of ['implement', 'conflict', 'ci-fix', 'fix', 'resolve']) {
    const p = prompts[k]
    assert.match(p, /ワークフローを変えた差分（\`\.claude\/workflows\/\` か \`\.claude\/workflow-tests\/\` のファイルを 1 つでも含む）は、ビルドに入るファイルと一緒でも/, k)
    assert.match(p, /Gradle を回す前に worktree の中で \`node --test \.claude\/workflow-tests\/\` を前景で回す/, k)
  }
})

test('プロンプト: マージの段階の node --test は、Already up to date で新しいコミットができなくても、差分にワークフローがあれば回す（#985）', async () => {
  const prompts = await gradleStagePrompts()
  for (const k of ['merge', 'merge-rerun']) {
    assert.match(prompts[k], /取り込みの結果によらず（Already up to date で新しいコミットができなかったときも）/, k)
    assert.match(prompts[k], /git diff --name-only origin\/main\.\.\.HEAD/, k)
    assert.doesNotMatch(prompts[k], /取り込みで新しいコミットができ、/, k)
  }
})

test('プロンプト: 全段階に、ビルドに入らないファイルだけの差分の決まり（docs/syscalls と src の下の .md を除く）がある（#985）', async () => {
  const prompts = await gradleStagePrompts()
  for (const k of ['implement', 'conflict', 'ci-fix', 'fix', 'resolve']) {
    const p = prompts[k]
    assert.match(p, /ビルドに入らないファイルだけの差分（#985）/, k)
    assert.match(p, /`\.claude\/\*\*`、`docs\/\*\*`（`docs\/syscalls\/\*\*` を除く）、どの `src\/` の下でもない `\*\.md`/, k)
    assert.match(p, /`\.github\/\*\*` を含むときは、手元では確かめられないので、push の後の PR の CI の結果で確かめる/, k)
    assert.match(p, /`node --test \.claude\/workflow-tests\/` を前景で回す/, k)
    assert.match(p, /Gradle は起動していない（差分が <ファイル> だけ）/, k)
    assert.match(p, /差分の取り方: /, k)
  }
  assert.match(prompts.implement, /git diff --name-only origin\/main\.\.\.HEAD/)
  assert.match(prompts.conflict, /git diff --name-only origin\/main\.\.\.HEAD/)
  assert.match(prompts.fix, /そのコミットから今の作業ツリーまでの自分の変更/)
})

test('プロンプト: 依存の取得の失敗の回し直しには、依存を変えていなければ --offline を付け、キャッシュにないときは付けずに戻す（#985）', async () => {
  const prompts = await gradleStagePrompts()
  for (const k of GRADLE_KINDS) {
    const p = prompts[k]
    assert.match(p, /依存の取得の失敗の回し直しには `--offline` を付ける/, k)
    assert.match(p, /`gradle\/libs\.versions\.toml`、`\*\*\/build\.gradle\.kts`/, k)
    assert.match(p, /`build-logic\/\*\*`/, k)
    assert.match(p, /`gradle\/wrapper\/\*\*`/, k)
    assert.match(p, /No cached version available for offline mode/, k)
    assert.match(p, /最大 3 本まで/, k)
  }
})

test('ビルドに入らないファイルだけの直しの summary（Gradle は起動していない）では、待つやり直しに入らない（#985）', async () => {
  const summary = 'Gradle は起動していない（差分が .claude/workflows/issue-to-pr.js だけ）。node --test は 163 件通った'
  const fix = await run({
    review: [{ findings: [FINDING] }],
    verify: [{ confirmed: [0], notes: '' }],
    fix: [{ pushed: false, summary, gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(fix.count('fix-wait'), 0)
  const conflict = await viaConflict(summary)
  assert.equal(conflict.count('conflict-wait'), 0)
})

test('プロンプト: 待つやり直しは、孤児のログを採る 3 つの条件と、起動していないログの扱いと、60 分の待ちを書く', async () => {
  const r = await run({ ci: [CONFLICT, OK_CI], conflict: [{ ...buildFailed('a'), ...PENDING }], 'conflict-wait': [CONFLICT_OK] })
  const p = r.promptOf('conflict-wait')
  assert.match(p, /最大 60 分待つ/)
  assert.match(p, /Win32_Process/)
  assert.match(p, /\^BUILD SUCCESSFUL in /)
  assert.match(p, /<名前>\.tree` があり、その中身が今の作業ツリーの木の hash/)
  assert.match(p, /java のプロセスがない/)
  assert.match(p, /`runningLogs` にあって `\.log` がないもの/)
  assert.match(p, /<名前>\.orphan/)
  assert.match(p, /孤児のログ <名前> を採った（BUILD SUCCESSFUL）/)
  assert.doesNotMatch(p, /参考にとどめる/)
  assert.doesNotMatch(p, /Monitor (など)?で待つ/)
})

const ORPHAN_SUCCESS = '孤児のログ conflict1-build-3 を採った（BUILD SUCCESSFUL、.exit はない）'

test('孤児のログを採った文は、同じ一文に「.exit はない」があっても、待つやり直しに入らない', async () => {
  const conflict = await viaConflict(`${ORPHAN_SUCCESS}。:core:test が落ちた`)
  assert.equal(conflict.count('conflict-wait'), 0)
  const fix = await run({
    review: [{ findings: [FINDING] }],
    verify: [{ confirmed: [0], notes: '' }],
    fix: [{ pushed: false, summary: `${ORPHAN_SUCCESS}。直すものはなかった`, gradleStillRunning: false, runningLogs: [] }],
  })
  assert.equal(fix.count('fix-wait'), 0)
})

test('孤児のログを採った文と一緒でも、別の一文が未完了を示せば（#889 の文）、待つやり直しに入る', async () => {
  const r = await viaConflict(`${ORPHAN_SUCCESS}。${PR889_REASON}`)
  assert.equal(r.count('conflict-wait'), 1)
  assert.equal(r.issue.status, 'merged')
})

test('孤児のログを採った句と、別のログの未完了が同じ一文に入っていても、待つやり直しに入る（#962）', async () => {
  const mixed = '孤児のログ conflict1-build-3 を採ったが（BUILD SUCCESSFUL）、conflict1-build-4 は .exit がなく終了コードを読めていない'
  const r = await viaConflict(mixed)
  assert.equal(r.count('conflict-wait'), 1)
  assert.equal(r.issue.status, 'merged')
  const paren = await viaConflict(`${ORPHAN_SUCCESS}、conflict1-build-4 は終了コードを読めていない`)
  assert.equal(paren.count('conflict-wait'), 1)
})

test('「孤児のログを採ったかは分からない」のような文は、採った句ではないので、未完了の手がかりを外さない（#962）', async () => {
  const r = await viaConflict('孤児のログを採ったかは分からない。.exit がなく、終了コードを読めていない')
  assert.equal(r.count('conflict-wait'), 1)
  const same = await viaConflict('孤児のログを採ったかは分からず、.exit がない')
  assert.equal(same.count('conflict-wait'), 1)
})

test('孤児のログの名前が完全なパスやバッククォートで囲んだものでも、採った句として外す（#972 のレビュー）', async () => {
  const names = [
    'D:/Projects/KiBi-Computers/.worktrees/.logs/issue-962/conflict1-build-3',
    String.raw`D:\Projects\KiBi-Computers\.worktrees\.logs\issue-962\conflict1-build-3`,
    '`conflict1-build-3`',
    '`conflict1-build-3` と `ci-fix1-gametest-neoforge-1.21.1-2`',
    'conflict1-build-3、ci-fix1-gametest-2',
  ]
  for (const n of names) {
    const r = await viaConflict(`孤児のログ ${n} を採った（BUILD SUCCESSFUL、.exit はない）。:core:test が落ちた`)
    assert.equal(r.count('conflict-wait'), 0, n)
  }
})

test('「を採った」が続かない長い名前の文でも、すぐに判定が終わる（入れ子の量指定子のバックトラックがない。#972 のレビュー）', async () => {
  const long = 'fix2-gametest-neoforge-1.21.1-3'.repeat(20)
  const texts = [
    `孤児のログ ${long} は採らなかった。.exit がなく、終了コードを読めていない`,
    `孤児のログ ${'a、'.repeat(500)}b は採らなかった。.exit がない`,
    `孤児のログ ${'a '.repeat(500)}は採らなかった。.exit がない`,
  ]
  for (const t of texts) {
    const t0 = Date.now()
    const r = await viaConflict(t)
    assert.ok(Date.now() - t0 < 2000, `${Date.now() - t0} ms`)
    assert.equal(r.count('conflict-wait'), 1)
  }
})

// ---- 結果を返さなかった段階（#953） ----

test('CI 待ちが 1 回結果を返さなければ、1 回だけ呼び直して進み、stalled に回復として載る', async () => {
  const r = await run({ ci: [null, OK_CI] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('ci'), 2)
  assert.equal(r.result.stalled.length, 1)
  assert.deepEqual(
    { stage: r.result.stalled[0].stage, issue: r.result.stalled[0].issue, retried: r.result.stalled[0].retried, recovered: r.result.stalled[0].recovered },
    { stage: 'ci', issue: 904, retried: true, recovered: true },
  )
  assert.equal(r.issue.stalledStages.length, 1)
  assert.match(r.promptOf('report'), /結果を返さなかった段階.*ci:#904/)
})

test('CI 待ちが 2 回とも結果を返さなければ、ci-fix にもマージにも進まず人に上がり、stalled に未回復として載る', async () => {
  const r = await run({ ci: [null, null] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('ci'), 2)
  assert.equal(r.count('ci-fix'), 0)
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('ask'), 1)
  assert.match(r.issue.question, /結果を返さなかった/)
  assert.equal(r.result.stalled.length, 1)
  assert.equal(r.result.stalled[0].retried, true)
  assert.equal(r.result.stalled[0].recovered, false)
})

// #887（PR #948）の CI 待ちが返そうとした形。noChecks が failureLog の文字列に紛れ込んで欠けていた
const PR948_CI = { mergeable: 'MERGEABLE', passed: false, failingChecks: [], failureLog: 'まだ終わっていない（state IN_PROGRESS, bucket pending）。接続エラーで途中終了を繰り返し</failureLog>\n<parameter name="noChecks">false' }

test('必須でない欄を欠いた CI の結果でも例外にならず、待ち直して進む', async () => {
  const r = await run({ ci: [PR948_CI, { passed: true, mergeable: 'MERGEABLE' }] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('ci'), 2)
  assert.equal(r.count('ci-fix'), 0)
})

test('CI 待ちが 3 回とも未完了（unfinished）で返ったら、ci-fix に回さず人に上がる', async () => {
  const u = { passed: false, mergeable: 'MERGEABLE', unfinished: true }
  const r = await run({ ci: [u, u, u] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('ci'), 3)
  assert.equal(r.count('ci-fix'), 0)
  assert.match(r.issue.question, /CI が終わらなかった/)
})

test('unfinished=true でも落ちたチェックの名前があれば、待ち直さず ci-fix に回り、名前が残る', async () => {
  const u = { passed: false, mergeable: 'MERGEABLE', unfinished: true, failingChecks: ['build', 'gametest (in_progress)'], runId: 456, attempt: 2, failureLog: 'boom' }
  const r = await run({ ci: [u, OK_CI], 'ci-fix': [{ pushed: true, rerun: false, summary: 'fixed', gradleStillRunning: false, runningLogs: [] }] })
  assert.equal(r.count('ci-fix'), 1)
  assert.match(r.promptOf('ci-fix'), /build/)
  assert.doesNotMatch(r.promptOf('ci-fix'), /CI が終わらなかった|gametest \(in_progress\)/)
  assert.match(r.promptOf('ci-fix'), /run 456（試行 2）/)
  assert.equal(r.issue.status, 'merged')
})

test('unfinished=true で終わっていないチェックの名前だけなら、今までどおり待ち直す', async () => {
  const u = { passed: false, mergeable: 'MERGEABLE', unfinished: true, failingChecks: ['build (pending)'] }
  const r = await run({ ci: [u, u, u] })
  assert.equal(r.count('ci'), 3)
  assert.equal(r.count('ci-fix'), 0)
  assert.match(r.issue.question, /CI が終わらなかった/)
})

test('落ちたチェックの名前がない結果が 3 回続いても、ci-fix に回さない', async () => {
  const r = await run({ ci: [PR948_CI, PR948_CI, PR948_CI] })
  assert.equal(r.count('ci-fix'), 0)
  assert.equal(r.issue.status, 'needs_human')
})

test('未完了のあとに本物の失敗が返れば、今までどおり ci-fix に回る', async () => {
  const failed = { passed: false, mergeable: 'MERGEABLE', failingChecks: ['build'], runId: 123, attempt: 1, failureLog: 'boom' }
  const r = await run({ ci: [{ passed: false, mergeable: 'MERGEABLE', unfinished: true }, failed, OK_CI], 'ci-fix': [{ pushed: true, rerun: false, summary: 'fixed', gradleStillRunning: false, runningLogs: [] }] })
  assert.equal(r.count('ci-fix'), 1)
  assert.match(r.promptOf('ci-fix'), /run 123（試行 1）/)
  assert.equal(r.issue.status, 'merged')
})

test('報告の投稿が 1 回結果を返さなければ、重複を避ける指示を付けて 1 回だけ呼び直す', async () => {
  const r = await run({ report: [null, 'ok'] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('report'), 2)
  assert.equal(r.result.stalled[0].stage, 'report')
  assert.equal(r.result.stalled[0].recovered, true)
})

test('質問の投稿が 1 回結果を返さなければ、1 回だけ呼び直す', async () => {
  const r = await run({ ci: [null, null], ask: [null, 'ok'] })
  assert.equal(r.count('ask'), 2)
  assert.ok(r.result.stalled.some(x => x.stage === 'ask' && x.retried && x.recovered))
})

test('Gradle を動かす段階（マージ）が結果を返さなくても呼び直さず、stalled に呼び直していないとして載る', async () => {
  const r = await run({ merge: [null] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge'), 1)
  const s = r.result.stalled.find(x => x.stage === 'merge')
  assert.ok(s)
  assert.equal(s.retried, false)
  assert.equal(s.pr, PR)
  assert.equal(s.issue, 904)
})

test('プロンプト: CI 待ちとマージの段階に、待ちの上限と接続の失敗の扱いが入る', async () => {
  const r = await run()
  for (const k of ['ci', 'merge']) {
    assert.match(r.promptOf(k), /date \+%s/)
    assert.match(r.promptOf(k), /最大 60 分/)
    assert.match(r.promptOf(k), /続けて 5 回/)
  }
  assert.match(r.promptOf('ci'), /unfinished=true/)
})

test('プロンプト: 待ちの上限は CI を待ち始めた時点から測り、段階の開始からではない（マージの段階の build・GameTest は数えない）', async () => {
  const r = await run()
  for (const k of ['ci', 'merge']) {
    assert.match(r.promptOf(k), /CI を待ち始める時点/)
    assert.match(r.promptOf(k), /段階の開始からではない/)
    assert.match(r.promptOf(k), /GameTest のロックの待ちなど、CI を待つ前の作業の時間は数えない/)
    assert.doesNotMatch(r.promptOf(k), /この段階を始めたら、最初に/)
  }
})

test('CI 待ちのスキーマ: runId・attempt は数でも文字列でも null でも受ける（必須は passed と mergeable だけ）', async () => {
  const r = await run()
  const schema = r.schemaOf('ci')
  assert.deepEqual(schema.required, ['passed', 'mergeable'])
  for (const k of ['runId', 'attempt']) {
    for (const t of ['integer', 'string', 'null']) assert.ok(schema.properties[k].type.includes(t), `${k} は ${t} を受ける`)
  }
})

test('runId・attempt を文字列で返した CI 待ちの結果も、失敗の抜粋に run の ID と試行が入る', async () => {
  const failed = { passed: false, mergeable: 'MERGEABLE', failingChecks: ['build'], runId: '123', attempt: '2', failureLog: 'boom' }
  const r = await run({ ci: [failed, OK_CI], 'ci-fix': [{ pushed: true, rerun: false, summary: 'fixed', gradleStillRunning: false, runningLogs: [] }] })
  assert.match(r.promptOf('ci-fix'), /run 123（試行 2）/)
})

// ---- Stacked PRs（#936） ----
// 下の段 PR #900（Issue #338。この実行にない）、上の段 PR #906（この実行の Issue #904）の 2 段のスタック
const LOWER = { pr: 900, branch: 'issue-338-lower', baseRef: 'main', position: 1, state: 'OPEN', draft: false, needsHuman: false, reviewed: true, headSha: 'l1' }
const TOP = { pr: PR, branch: IMPL.branch, baseRef: LOWER.branch, position: 2, state: 'OPEN', draft: false, needsHuman: false, reviewed: true, headSha: 't1' }
const stackOf = (...members) => ({ baseRef: members.find(m => m.pr === PR)?.baseRef ?? 'main', stackNumber: 925, members })
const STACK2 = stackOf(LOWER, TOP)
const STACK_CMD = `gh stack merge ${PR} --yes --squash`
const TWO_ISSUES = [
  { number: 904, title: 'a', areas: ['a'], openDeps: [], blockedReason: null, needsPlan: false },
  { number: 905, title: 'b', areas: ['b'], openDeps: [], blockedReason: null, needsPlan: false },
]
const UPPER_IMPL = { ...IMPL, pr: 907, branch: 'issue-905-y', worktree: 'D:/w/issue-905', baseRef: 'issue-904-x' }

test('スタック: 上の段は、下の段から順に取り込み、手元で Gradle を動かさずに全段の CI を待って gh stack merge でマージする', async () => {
  const r = await run({
    'stack-detect': [STACK2],
    'stack-check': [STACK2],
    'stack-sync': prompt => ({ outcome: 'merged', pushed: true, headSha: 'abc', worktree: prompt.includes('PR #900') ? 'D:/KiBi/.worktrees/issue-338' : 'D:/w/issue-904', summary: 'ok' }),
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('stack-merge'), 1)
  const syncs = r.promptsOf('stack-sync')
  assert.deepEqual(syncs.map(p => p.opts.label), ['stack-sync:900', 'stack-sync:906'])
  assert.match(syncs[0].prompt, /`origin\/main` を取り込んで/)
  assert.match(syncs[1].prompt, /`origin\/issue-338-lower` を取り込んで/)
  // 取り込みは通常の push だけ。force を使うコマンドは使わないと書く
  for (const p of syncs) {
    assert.match(p.prompt, /force push はしない/)
    assert.match(p.prompt, /`gh stack sync`・`gh stack rebase`・`gh stack push`・`gh stack submit` は force-with-lease で push するので使わない/)
    assert.match(p.prompt, /Gradle は動かさない/)
  }
  const m = r.promptOf('stack-merge')
  assert.ok(m.includes(STACK_CMD))
  assert.match(m, /#900・#906/)
  assert.match(m, /`git merge origin\/issue-338-lower`/)
  assert.doesNotMatch(m, /`gh pr merge \d+/)
  // 手元の build と GameTest はやめた（#985）。push してから全段の先頭のコミットの CI を待つ
  assert.doesNotMatch(m, /\.\/gradlew (build|:)|runGameTestServer/)
  assert.match(m, /headRefOid/)
  assert.match(m, /pass か skipping/)
  // この実行にない下の段は、上の段の流れが片付けと報告をする
  assert.equal(r.count('cleanup'), 2)
  assert.ok(r.promptsOf('cleanup').some(p => p.prompt.includes('.worktrees/issue-338')))
  assert.ok(r.promptsOf('stack-note').some(p => p.opts.label === 'stack-note:900'))
  assert.equal(r.count('cross-check'), 0)
  assert.deepEqual(r.issue.stack, { number: 925, prs: [900, 906], position: 2 })
})

test('スタック: gh stack merge が権限の判定で拒否されたら、確かめのあとで 1 回だけ聞き、下の段にはその PR を指す案内を付ける', async () => {
  const denied = fail('merge', 'head t2。build と両 GameTest は通った。CI: #900 run 1 pass、#906 run 2 pass', { stackMergeOutcome: 'permission_denied' })
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-merge': [denied] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-merge'), 1)
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.count('ask'), 1)
  assert.equal(r.result.questions.length, 1)
  assert.equal(r.result.questions[0].pr, PR)
  const check = r.promptOf('cross-check')
  assert.match(check, /権限の判定で拒否された/)
  assert.match(check, /回避の手段/)
  assert.ok(check.includes(STACK_CMD))
  assert.match(check, /スタックごとマージしてよいか/)
  // 権限の拒否のあとで、ほかの口（gh pr merge・merge-async）を試す段階はない
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('merge-flaky'), 0)
  const note = r.promptsOf('stack-note').find(p => p.opts.label === 'stack-note:900')
  assert.ok(note)
  assert.match(note.prompt, /論点は PR #906 で扱う/)
  // 案内のためだけに needs-human を付けない（人が論点の PR のラベルだけを外して resume したとき、互いを指し合って止まるため）
  assert.doesNotMatch(note.prompt, /--add-label/)
  assert.match(note.prompt, /ラベルは付けない/)
  assert.match(note.prompt, /needs-human を付けていない/)
  assert.equal(r.issue.stackPointer, PR)
})

test('スタック: 下の段に needs-human があれば、取り込みも確かめもせず、新しく聞かずに下の段の論点を指す', async () => {
  const waiting = stackOf({ ...LOWER, needsHuman: true }, TOP)
  const r = await run({ 'stack-detect': [waiting], 'stack-check': [waiting] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.count('cross-check'), 0)
  assert.equal(r.count('ask'), 0)
  assert.equal(r.result.questions.length, 0)
  const note = r.promptsOf('stack-note').find(p => p.opts.label === `stack-note:${PR}`)
  assert.ok(note)
  assert.match(note.prompt, /論点は PR #900 で扱う/)
  assert.equal(r.issue.stackPointer, 900)
})

test('スタック: 下の段が MERGED（部分マージのあと）なら、取り込まずに 1 回だけ聞く', async () => {
  const partial = stackOf({ ...LOWER, state: 'MERGED' }, TOP)
  const r = await run({ 'stack-detect': [partial], 'stack-check': [partial] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('cross-check'), 1)
  assert.match(r.issue.mergeReason, /rebase（force push）が要る/)
})

test('スタック: マージの直前にスタックの形が変わっていたら、取り込まずに止まる', async () => {
  const third = { ...TOP, pr: 999, branch: 'issue-999-x', baseRef: TOP.branch, position: 3 }
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [stackOf(LOWER, TOP, third)] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.match(r.issue.mergeReason, /形が変わっていた/)
})

test('スタック: 取り込みが字面で解けない衝突なら、上の段に進まず止まる', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-sync': [{ outcome: 'conflict', pushed: false, headSha: null, worktree: null, summary: 'Foo.kt' }] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 1)
  assert.equal(r.count('stack-merge'), 0)
  assert.match(r.issue.mergeReason, /1 段目 PR #900 に main を取り込めなかった/)
})

test('スタック: 全段の CI が通らなければ、不安定なテストの回し直し（1 つの PR 用）には回さずに止まる', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-merge': [fail('ci', '#900 の build が落ちた')] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('merge-flaky'), 0)
  assert.equal(r.count('cross-check'), 1)
})

test('スタック: base が main でなく、スタックにも属さない PR はマージせずに聞く', async () => {
  const r = await run({ 'stack-detect': [{ baseRef: 'issue-338-lower', stackNumber: null, members: [] }] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.count('cross-check'), 1)
  assert.match(r.promptOf('cross-check'), /gh stack link/)
})

test('スタック: 下の段で、上の段がこの実行にないなら、この PR だけではマージせず、聞かない', async () => {
  const lower = { ...LOWER, pr: PR, branch: IMPL.branch }
  const upper = { ...TOP, pr: 950, branch: 'issue-950-up', baseRef: IMPL.branch }
  const r = await run({ 'stack-detect': [{ baseRef: 'main', stackNumber: 925, members: [lower, upper] }] })
  assert.equal(r.issue.status, 'ready')
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.count('cross-check'), 0)
  assert.equal(r.issue.question, null)
  assert.match(r.issue.mergeReason, /上の段 PR #950 と一緒に/)
})

test('スタック: ふつうのマージの手順は、gh pr merge の前にスタックになっていないかを確かめる', async () => {
  const r = await run()
  assert.equal(r.count('stack-detect'), 1)
  assert.match(r.promptOf('merge'), /\.stack/)
  assert.match(r.promptOf('merge'), /スタックの PR になっている/)
})

test('スタック: 実装の指示に、base を main 以外にしたら gh stack link で紐づけることが入る', async () => {
  const r = await run()
  assert.match(r.promptOf('implement'), /gh stack link <下の PR の番号> <この PR の番号>/)
})

test('スタック: CI の段階の衝突は、上の段では main ではなく base（下の段のブランチ）を取り込む', async () => {
  const r = await run({
    implement: [{ ...IMPL, baseRef: 'issue-338-lower' }],
    ci: [CONFLICT, OK_CI],
    conflict: [{ outcome: 'merged', pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }],
    'stack-detect': [STACK2],
    'stack-check': [STACK2],
  })
  const p = r.promptOf('conflict')
  assert.match(p, /`git merge origin\/issue-338-lower`/)
  assert.doesNotMatch(p, /`git merge origin\/main`/)
  assert.equal(r.issue.status, 'merged')
})

test('スタック: base が main の PR の衝突の取り込みは、今までどおり main を取り込む', async () => {
  const r = await run({
    ci: [CONFLICT, OK_CI],
    conflict: [{ outcome: 'merged', pushed: true, summary: 'ok', gradleStillRunning: false, runningLogs: [] }],
  })
  assert.match(r.promptOf('conflict'), /`git merge origin\/main`/)
})

// 同じ実行の中で、Issue #904（PR #906。下の段）と Issue #905（PR #907。上の段）がスタックになる
test('スタック: 同じ実行の 2 つの Issue が下と上の段なら、詰まらずに上の段の流れが 1 回でまとめてマージし、両方 merged になる', async () => {
  const low = { ...LOWER, pr: 906, branch: 'issue-904-x' }
  const up = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: 'issue-904-x' }
  const members = [low, up]
  const r = await run({
    select: [{ issues: TWO_ISSUES }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
    'stack-detect': prompt => ({ stackNumber: 925, members, baseRef: prompt.includes('PR #907') ? 'issue-904-x' : 'main' }),
    'stack-check': () => ({ stackNumber: 925, members, baseRef: 'issue-904-x' }),
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'merged')
  assert.equal(byIssue[905].status, 'merged')
  assert.equal(r.count('stack-merge'), 1)
  assert.equal(r.promptsOf('stack-merge')[0].opts.label, 'stack-merge:907')
  assert.equal(r.count('merge'), 0)
  assert.deepEqual(r.promptsOf('stack-sync').map(p => p.opts.label), ['stack-sync:906', 'stack-sync:907'])
  // 下の段の worktree は、この実行の実装の結果のものを使う
  assert.match(r.promptsOf('stack-sync')[0].prompt, /worktree D:\/w\/issue-904 で作業する/)
  // 片付けは、それぞれの流れが自分の worktree に 1 回ずつ
  assert.equal(r.count('cleanup'), 2)
  assert.equal(r.count('cross-check'), 0)
})

test('スタック: 同じ実行の下の段がマージしてよいところまで進まなければ、上の段は確かめずに止まり、新しく聞かない', async () => {
  const low = { ...LOWER, pr: 906, branch: 'issue-904-x' }
  const up = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: 'issue-904-x' }
  const r = await run({
    select: [{ issues: TWO_ISSUES }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
    // 下の段（PR #906）の CI だけが落ち続ける
    ci: prompt => prompt.includes('PR #906') ? { passed: false, mergeable: 'MERGEABLE', failingChecks: ['build'], failureLog: 'boom' } : OK_CI,
    'ci-fix': () => ({ pushed: false, rerun: false, summary: '直せない', gradleStillRunning: false, runningLogs: [] }),
    'stack-detect': () => ({ stackNumber: 925, baseRef: 'issue-904-x', members: [low, up] }),
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[905].status, 'needs_human')
  assert.equal(byIssue[905].stackPointer, 906)
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  // 人への質問は、下の段の自分の論点の 1 つだけ
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.result.questions.length, 1)
  assert.equal(r.result.questions[0].pr, 906)
  assert.ok(r.promptsOf('stack-note').some(p => p.opts.label === 'stack-note:907' && /論点は PR #906 で扱う/.test(p.prompt)))
})

test('スタック: 同じ実行の上の段が止まったら、下の段は上の段の論点を指し、新しく聞かない', async () => {
  const low = { ...LOWER, pr: 906, branch: 'issue-904-x' }
  const up = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: 'issue-904-x' }
  const members = [low, up]
  const denied = fail('merge', '拒否', { stackMergeOutcome: 'permission_denied' })
  const r = await run({
    select: [{ issues: TWO_ISSUES }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
    'stack-detect': () => ({ stackNumber: 925, members, baseRef: 'main' }),
    'stack-check': () => ({ stackNumber: 925, members, baseRef: 'issue-904-x' }),
    'stack-merge': [denied],
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'needs_human')
  assert.equal(byIssue[905].status, 'needs_human')
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.result.questions.length, 1)
  assert.equal(r.result.questions[0].pr, 907)
  assert.ok(r.promptsOf('stack-note').some(p => p.opts.label === 'stack-note:906' && /論点は PR #907 で扱う/.test(p.prompt)))
})

test('スタック: 同じ実行で上の段の Issue が下の段に依存していたら、互いに待たずに終わる', async () => {
  const low = { ...LOWER, pr: PR, branch: IMPL.branch }
  const up = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: IMPL.branch }
  const r = await run({
    select: [{ issues: [TWO_ISSUES[0], { ...TWO_ISSUES[1], openDeps: [904] }] }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
    'stack-detect': () => ({ stackNumber: 925, baseRef: 'main', members: [low, up] }),
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'ready')
  assert.equal(byIssue[905].status, 'blocked')
  // 「この実行にない」の分岐ではなく、依存の分岐で待たずに終わる（上の段の流れは、依存先の終わりを待っていて、PR をまだ登録していない）
  assert.match(byIssue[904].mergeReason, /「依存: #904」/)
  assert.doesNotMatch(byIssue[904].mergeReason, /この実行にない|と一緒に/)
  assert.ok(r.logs.some(l => l.includes('上の段の Issue #905 がこの Issue に依存している')))
})

test('スタック: 推移的な依存（上の段 → 別の Issue → 下の段）でも、下の段は上の段を待たない', async () => {
  const low = { ...LOWER, pr: PR, branch: IMPL.branch }
  const up = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: IMPL.branch }
  const mid = { number: 903, title: 'm', areas: ['c'], openDeps: [904], blockedReason: null, needsPlan: false }
  const r = await run({
    select: [{ issues: [TWO_ISSUES[0], mid, { ...TWO_ISSUES[1], openDeps: [903] }] }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
    'stack-detect': () => ({ stackNumber: 925, baseRef: 'main', members: [low, up] }),
  }, { issues: [904, 903, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'ready')
  assert.match(byIssue[904].mergeReason, /「依存: #904」/)
})

test('スタック: 下の段の Issue が一番上の段の Issue に依存していたら、上の段は待たずに止まる（互いに待たない）', async () => {
  const low = { ...LOWER, pr: PR, branch: IMPL.branch }
  const up = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: IMPL.branch }
  const r = await run({
    select: [{ issues: [{ ...TWO_ISSUES[0], openDeps: [905] }, TWO_ISSUES[1]] }],
    implement: prompt => prompt.includes('Issue #905') ? UPPER_IMPL : IMPL,
    'stack-detect': () => ({ stackNumber: 925, baseRef: 'main', members: [low, up] }),
    'stack-check': () => ({ stackNumber: 925, baseRef: 'main', members: [low, up] }),
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[905].status, 'needs_human')
  assert.match(byIssue[905].mergeReason, /「依存: #905」/)
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(byIssue[904].status, 'blocked')
})

// ---- 同じ実行の 2 段が、同じ area・window が埋まっているなどで、片方の流れが PR を登録する前に、もう片方がマージの段階に来る形 ----
const SAME_AREA = [
  { number: 904, title: 'a', areas: ['tooling'], openDeps: [], blockedReason: null, needsPlan: false },
  { number: 905, title: 'b', areas: ['tooling'], openDeps: [], blockedReason: null, needsPlan: false },
]
const lowStep = { ...LOWER, pr: 906, branch: 'issue-904-x' }
const upStep = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: 'issue-904-x' }
const SAME_AREA_BASE = {
  implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
  'stack-detect': () => ({ stackNumber: 925, baseRef: 'issue-904-x', members: [lowStep, upStep] }),
  'stack-check': () => ({ stackNumber: 925, baseRef: 'issue-904-x', members: [lowStep, upStep] }),
}
const LOWER_CI_FAILS = {
  ci: prompt => prompt.includes('PR #906') ? { passed: false, mergeable: 'MERGEABLE', failingChecks: ['build'], failureLog: 'boom' } : OK_CI,
  'ci-fix': () => ({ pushed: false, rerun: false, summary: '直せない', gradleStillRunning: false, runningLogs: [] }),
}

test('スタック: 上の段が先にロックを取り（同じ area・window: 1）、下の段が未登録でも、上の段は下の段の知らせを待ってから取り込む', async () => {
  const r = await run({ ...SAME_AREA_BASE, select: [{ issues: [SAME_AREA[1], SAME_AREA[0]] }] }, { issues: [905, 904], window: 1 })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'merged')
  assert.equal(byIssue[905].status, 'merged')
  assert.equal(r.count('stack-merge'), 1)
  // 下の段（PR #906）の CI が済んでから、取り込みが始まる
  const lastLowerCi = Math.max(...r.prompts.map((p, i) => p.kind === 'ci' && p.prompt.includes('PR #906') ? i : -1))
  const firstSync = r.prompts.findIndex(p => p.kind === 'stack-sync')
  assert.ok(lastLowerCi >= 0 && firstSync > lastLowerCi, `下の段の CI(${lastLowerCi}) の後に取り込み(${firstSync})`)
  assert.equal(r.count('cleanup'), 2)
  assert.equal(r.count('cross-check'), 0)
})

test('スタック: 上の段が先にロックを取り、下の段の CI が落ち続けるなら、上の段は取り込みも確かめもマージもしない', async () => {
  const r = await run({ ...SAME_AREA_BASE, ...LOWER_CI_FAILS, select: [{ issues: [SAME_AREA[1], SAME_AREA[0]] }] }, { issues: [905, 904], window: 1 })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[905].status, 'needs_human')
  assert.equal(byIssue[905].stackPointer, 906)
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.count('cross-check'), 1)
})

test('スタック: 下の段が先にロックを取り、上の段が未登録でも、下の段は上の段の結果を待ち、上の段がスタックごとマージして両方 merged になる', async () => {
  const r = await run({ ...SAME_AREA_BASE, select: [{ issues: SAME_AREA }] }, { issues: [904, 905], window: 1 })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'merged')
  assert.equal(byIssue[905].status, 'merged')
  assert.equal(r.count('stack-merge'), 1)
  assert.equal(r.promptsOf('stack-merge')[0].opts.label, 'stack-merge:907')
  assert.equal(r.count('merge'), 0)
  // どちらも自分の worktree を片付ける（下の段が「マージせず」で返して片付けない形にならない）
  assert.equal(r.count('cleanup'), 2)
  assert.doesNotMatch(byIssue[904].mergeReason, /この実行にない/)
})

test('スタック: resume の 2 段（同じ area・window: 1）も、PR を登録する前から、この実行の段として扱う', async () => {
  const resume = prompt => {
    const isLow = prompt.includes('PR #906')
    return { ok: true, title: 't', areas: ['tooling'], branch: isLow ? 'issue-904-x' : 'issue-905-y', worktree: isLow ? 'D:/w/issue-904' : 'D:/w/issue-905', publicApiChanged: false, touchesConcurrency: false, summary: 's', baseRef: isLow ? 'main' : 'issue-904-x' }
  }
  const r = await run({ ...SAME_AREA_BASE, ...LOWER_CI_FAILS, resume }, { resume: [{ issue: 905, pr: 907 }, { issue: 904, pr: 906 }], window: 1 })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[905].status, 'needs_human')
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
})

// ---- 下の段の論点の案内（needs-human を付けない） ----
test('スタック: 上の段が止まって質問したら、下の段には案内だけでラベルを付けず、人が上の段のラベルだけを外せば続けられる', async () => {
  const denied = fail('merge', '拒否', { stackMergeOutcome: 'permission_denied' })
  const first = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-merge': [denied] })
  assert.equal(first.issue.status, 'needs_human')
  const notes = first.promptsOf('stack-note')
  assert.ok(notes.length >= 1)
  for (const n of notes) assert.doesNotMatch(n.prompt, /add-label/, n.opts.label)
  // 続き: 上の段の needs-human だけが外れた状態（下の段には付いていない）。取り込み・確かめ・マージまで進む
  const second = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2] })
  assert.equal(second.issue.status, 'merged')
  assert.equal(second.count('stack-sync'), 2)
})

// ---- この実行にない下の段のレビュー（#936） ----
test('スタック: この実行にない下の段が、自動レビューを通った記録がなければ、取り込みも確かめもせず、一番上の段で 1 回だけ聞く', async () => {
  const unreviewed = stackOf({ ...LOWER, reviewed: false }, TOP)
  const r = await run({ 'stack-detect': [unreviewed], 'stack-check': [unreviewed] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.count('ask'), 1)
  assert.match(r.issue.mergeReason, /レビューなしで main に入る/)
  assert.match(r.promptOf('stack-detect'), /reviewed/)
  assert.match(r.promptOf('stack-detect'), /## 自動レビューの結果/)
})

test('スタック: レビューの記録（reviewed）はスキーマの必須で、この実行の流れがレビューする下の段には求めない', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2] })
  assert.ok(r.schemaOf('stack-detect').properties.members.items.required.includes('reviewed'))
  const low = { ...LOWER, pr: 906, branch: 'issue-904-x', reviewed: false }
  const up = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: 'issue-904-x', reviewed: false }
  const same = await run({
    select: [{ issues: TWO_ISSUES }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
    'stack-detect': () => ({ stackNumber: 925, baseRef: 'issue-904-x', members: [low, up] }),
    'stack-check': () => ({ stackNumber: 925, baseRef: 'issue-904-x', members: [low, up] }),
  }, { issues: [904, 905] })
  assert.equal(same.result.results.find(x => x.issue === 905).status, 'merged')
})

// ---- 作業場所と片付け（#936） ----
const SAME_ISSUE_UPPER = { ...IMPL, pr: 907, branch: 'issue-904-up', worktree: 'D:/KiBi/.worktrees/issue-904', baseRef: 'issue-904-low' }
const SAME_ISSUE_STACK = stackOf({ ...LOWER, pr: 900, branch: 'issue-904-low' }, { ...TOP, pr: 907, branch: 'issue-904-up', baseRef: 'issue-904-low' })
const syncAt = (lowerWorktree, upperWorktree = 'D:/w/issue-904') => prompt => ({
  outcome: 'merged', pushed: true, headSha: 'abc', worktree: prompt.includes('PR #900') ? lowerWorktree : upperWorktree, summary: 'ok',
})

test('スタック: 1 つの Issue を 2 つの PR に分けた形では、下の段の作業場所を上の段の worktree と同じにせず、PR の番号の場所にする', async () => {
  const r = await run({
    implement: [SAME_ISSUE_UPPER],
    'stack-detect': [SAME_ISSUE_STACK],
    'stack-check': [SAME_ISSUE_STACK],
    'stack-sync': syncAt('D:/KiBi/.worktrees/stack-pr-900', 'D:/KiBi/.worktrees/issue-904'),
  })
  assert.equal(r.issue.status, 'merged')
  const lower = r.promptsOf('stack-sync')[0].prompt
  assert.match(lower, /git worktree add \.worktrees\/stack-pr-900 issue-904-low/)
  assert.doesNotMatch(lower, /git worktree add \.worktrees\/issue-904 /)
  // 片付けは、取り込みが返した場所（stack-pr-900）。上の段の worktree（issue-904）は上の段の流れが 1 回だけ片付ける
  const cleanups = r.promptsOf('cleanup').map(p => p.prompt)
  assert.equal(cleanups.length, 2)
  assert.ok(cleanups.some(p => p.includes('worktree D:/KiBi/.worktrees/stack-pr-900 を片付ける') && p.includes('stack-pr-900(?![0-9])') && p.includes('.worktrees/.logs/stack-pr-900')))
  assert.equal(cleanups.filter(p => p.includes('worktree D:/KiBi/.worktrees/issue-904 を片付ける')).length, 1)
})

test('スタック: この実行にない下の段の取り込みが、この実行の流れの worktree を返したら、片付けない（その流れが片付ける）', async () => {
  const r = await run({
    implement: [SAME_ISSUE_UPPER],
    'stack-detect': [SAME_ISSUE_STACK],
    'stack-check': [SAME_ISSUE_STACK],
    'stack-sync': syncAt('D:\\KiBi\\.worktrees\\issue-904', 'D:/KiBi/.worktrees/issue-904'),
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('cleanup'), 1)
})

test('スタック: この実行にない下の段の worktree が .worktrees/ の標準の形でなければ、片付けずに報告に残す', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-sync': syncAt('D:/Users/me/work/KiBi') })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('cleanup'), 1)
  const note = r.promptsOf('stack-note').find(p => p.opts.label === 'stack-note:900')
  assert.match(note.prompt, /片付けなかった（D:\/Users\/me\/work\/KiBi/)
})

test('スタック: ブランチ名が issue-<番号>- でない下の段は、stack-pr-<PR> の場所で作業し、同じ場所を片付ける', async () => {
  const odd = stackOf({ ...LOWER, branch: 'feature-lower' }, { ...TOP, baseRef: 'feature-lower' })
  const r = await run({ 'stack-detect': [odd], 'stack-check': [odd], 'stack-sync': syncAt('D:/KiBi/.worktrees/stack-pr-900') })
  assert.equal(r.issue.status, 'merged')
  assert.match(r.promptsOf('stack-sync')[0].prompt, /git worktree add \.worktrees\/stack-pr-900 feature-lower/)
  assert.ok(r.promptsOf('cleanup').some(p => p.prompt.includes('worktree D:/KiBi/.worktrees/stack-pr-900 を片付ける')))
})

test('スタック: 下の段がこの実行にない別の Issue なら、その Issue の場所を使う', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2] })
  assert.match(r.promptsOf('stack-sync')[0].prompt, /git worktree add \.worktrees\/issue-338 issue-338-lower/)
})

test('スタック: どのプロンプトも、force を使う gh stack のコマンドや merge-async を実行させない', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2] })
  const banned = '`gh stack sync`・`gh stack rebase`・`gh stack push`・`gh stack submit` は force-with-lease で push するので使わない'
  for (const p of r.prompts) {
    const text = p.prompt.split(banned).join('')
    assert.doesNotMatch(text, /gh stack (sync|rebase|push|submit)\b/, p.opts.label)
    assert.doesNotMatch(text, /git push[^`]*--force/, p.opts.label)
  }
  // merge-async は「別の口では試さない」の文の中にだけ出る
  for (const p of r.promptsOf('stack-merge')) {
    for (const line of p.prompt.split('\n').filter(l => l.includes('merge-async'))) assert.match(line, /試さない/)
  }
})

// ---- レビュー指摘（PR #976）への直し ----
// 1. resume に同じ Issue の PR を 2 つ渡すと、どちらも流さない（同じ知らせを共有して、下の段を待たずにマージしうるため）
test('スタック: resume に同じ Issue の PR が 2 つあれば、どちらも流さず、理由を skipped に残す', { timeout: 5000 }, async () => {
  const r = await run({ 'stack-detect': [SAME_ISSUE_STACK], 'stack-check': [SAME_ISSUE_STACK] }, { resume: [{ issue: 904, pr: 907 }, { issue: 904, pr: 900 }] })
  assert.equal(r.count('resume'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('review'), 0)
  assert.deepEqual(r.result.results, [])
  assert.equal(r.result.skipped.length, 1)
  assert.equal(r.result.skipped[0].number, 904)
  assert.deepEqual(r.result.skipped[0].prs, [907, 900])
  assert.match(r.result.skipped[0].blockedReason, /1 つの Issue につき PR を 1 つだけ/)
})

test('スタック: resume の同じ Issue の重なりは、その Issue だけを外し、ほかの PR は流す', async () => {
  const resume = () => ({ ok: true, title: 't', areas: ['tooling'], branch: 'issue-905-y', worktree: 'D:/w/issue-905', publicApiChanged: false, touchesConcurrency: false, summary: 's', baseRef: 'main' })
  const r = await run({ resume }, { resume: [{ issue: 904, pr: 907 }, { issue: 904, pr: 900 }, { issue: 905, pr: 911 }] })
  assert.equal(r.count('resume'), 1)
  assert.match(r.promptOf('resume'), /PR #911/)
  assert.deepEqual(r.result.results.map(x => x.issue), [905])
  assert.deepEqual(r.result.skipped.map(x => x.number), [904])
})

// 2. resume の用意は、別のブランチを開いた `.worktrees/issue-<番号>` を使わない
test('スタック: resume の用意は、ブランチを開いた worktree を探し、別のブランチの issue-<番号> なら stack-pr-<PR> を使う', async () => {
  const resume = () => ({ ok: true, title: 't', areas: ['tooling'], branch: 'issue-904-low', worktree: 'D:/KiBi/.worktrees/stack-pr-900', publicApiChanged: false, touchesConcurrency: false, summary: 's', baseRef: 'main' })
  const r = await run({ resume, 'stack-detect': [SAME_ISSUE_STACK] }, { resume: [{ issue: 904, pr: 900 }] })
  const p = r.promptOf('resume')
  assert.match(p, /branch refs\/heads\/<headRefName>/)
  assert.match(p, /branch --show-current/)
  assert.match(p, /git worktree add \.worktrees\/stack-pr-900 <headRefName>/)
  assert.match(p, /ブランチも切り替えず/)
  // 1 つの Issue を分けたスタックの下の段だけを resume すると、上の段がこの実行にないので、その段だけではマージせず、質問もしない
  assert.equal(r.issue.status, 'ready')
  assert.equal(r.issue.question, null)
  assert.equal(r.count('stack-merge'), 0)
  assert.match(r.promptOf('report'), /結論: マージ可能/)
})

test('片付けと孤児の待ちは、worktree が stack-pr-<PR> なら、その名前で java を探す', async () => {
  const resume = () => ({ ok: true, title: 't', areas: ['tooling'], branch: 'issue-904-low', worktree: 'D:/KiBi/.worktrees/stack-pr-900', publicApiChanged: false, touchesConcurrency: false, summary: 's', baseRef: 'main' })
  const r = await run({ resume, ci: [CONFLICT, OK_CI], conflict: [{ ...buildFailed('a'), ...PENDING }], 'conflict-wait': [CONFLICT_OK] }, { resume: [{ issue: 904, pr: 900 }] })
  assert.equal(r.issue.status, 'merged')
  assert.ok(r.promptOf('cleanup').includes('stack-pr-900(?![0-9])'))
  assert.ok(r.promptOf('conflict-wait').includes('stack-pr-900(?![0-9])'))
})

// 3. レビューの記録のない下の段の質問は、回答で進める道だけを挙げ、その続きで進む
test('スタック: レビューの記録のない下の段の質問は、下の段だけの resume か人の gh stack merge を挙げ、下の段のレビューのあとの resume でマージする', async () => {
  const unreviewed = stackOf({ ...LOWER, reviewed: false }, TOP)
  const first = await run({ 'stack-detect': [unreviewed], 'stack-check': [unreviewed] })
  assert.equal(first.issue.status, 'needs_human')
  const reason = first.issue.mergeReason
  assert.match(reason, /1 つの Issue につき PR を 1 つだけ/)
  assert.ok(reason.includes(`人がそのまま \`${STACK_CMD}\` を実行する`))
  assert.match(reason, /一番上の段だけを resume しても、同じところで止まる/)
  assert.doesNotMatch(reason, /レビューなしでマージしてよいか判断してほしい/)
  // 続き 1: 下の段だけを resume する。上の段はこの実行にないので、その段だけではマージせずに「マージ可能」を残す
  const lowerRun = await run({
    resume: () => ({ ok: true, title: 't', areas: ['x'], branch: LOWER.branch, worktree: 'D:/KiBi/.worktrees/issue-338', publicApiChanged: false, touchesConcurrency: false, summary: 's', baseRef: 'main' }),
    'stack-detect': [{ ...unreviewed, baseRef: 'main' }],
  }, { resume: [{ issue: 338, pr: 900 }] })
  assert.equal(lowerRun.issue.status, 'ready')
  assert.equal(lowerRun.issue.question, null)
  assert.equal(lowerRun.count('stack-merge'), 0)
  assert.match(lowerRun.promptOf('report'), /結論: マージ可能/)
  // 続き 2: 一番上の段を resume する。下の段にレビューの記録があるので、スタックごとマージする
  const topRun = await run({
    resume: () => ({ ok: true, title: 't', areas: ['x'], branch: TOP.branch, worktree: 'D:/w/issue-904', publicApiChanged: false, touchesConcurrency: false, summary: 's', baseRef: LOWER.branch }),
    'stack-detect': [STACK2], 'stack-check': [STACK2],
  }, { resume: [{ issue: 904, pr: PR }] })
  assert.equal(topRun.issue.status, 'merged')
  assert.equal(topRun.count('stack-merge'), 1)
})

// 4. スタックの判定が結果を返さなければ、ふつうのマージ（main の取り込みから始まる）に進まない
test('スタック: 判定が呼び直しでも結果を返さなければ、ふつうのマージに進まず、取り込まずに止まる', async () => {
  const r = await run({ implement: [{ ...IMPL, baseRef: 'issue-338-lower' }], 'stack-detect': [undefined, undefined] })
  assert.equal(r.count('stack-detect'), 2)
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.notEqual(r.issue.status, 'merged')
  assert.match(r.issue.mergeReason, /スタック（Stacked PRs）に属するかを確かめられなかった/)
  assert.equal(r.count('cross-check'), 1)
})

test('スタック: 同じ実行の下の段の判定が返らなければ、上の段もマージせず、下の段の質問を指す', { timeout: 5000 }, async () => {
  const members = [lowStep, upStep]
  const r = await run({
    select: [{ issues: TWO_ISSUES }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : UPPER_IMPL,
    'stack-detect': prompt => prompt.includes('PR #906') ? undefined : { stackNumber: 925, baseRef: 'issue-904-x', members },
    'stack-check': () => ({ stackNumber: 925, baseRef: 'issue-904-x', members }),
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.count('stack-sync'), 0)
  assert.notEqual(byIssue[904].status, 'merged')
  assert.equal(byIssue[905].status, 'needs_human')
  assert.equal(byIssue[905].stackPointer, 906)
  assert.equal(r.result.questions.length, 1)
  assert.equal(r.result.questions[0].pr, 906)
})

// 5. 3 段のスタックで、中の段の Issue が一番下の段の Issue に依存しても、詰まらずに終わる
test('スタック: 3 段で中の段が下の段に依存していても、互いに待たずに終わり、上の段で 1 回だけ聞く', { timeout: 5000 }, async () => {
  const x = { ...LOWER, pr: 906, branch: 'issue-904-x' }
  const a = { ...LOWER, pr: 908, branch: 'issue-903-m', baseRef: 'issue-904-x', position: 2 }
  const b = { ...TOP, pr: 907, branch: 'issue-905-y', baseRef: 'issue-903-m', position: 3 }
  const st = { stackNumber: 925, baseRef: 'main', members: [x, a, b] }
  const mid = { number: 903, title: 'm', areas: ['c'], openDeps: [904], blockedReason: null, needsPlan: false }
  const r = await run({
    select: [{ issues: [TWO_ISSUES[0], mid, TWO_ISSUES[1]] }],
    implement: prompt => prompt.includes('Issue #904') ? IMPL : prompt.includes('Issue #905') ? UPPER_IMPL : { ...IMPL, pr: 908, branch: 'issue-903-m', worktree: 'D:/w/issue-903' },
    'stack-detect': () => st,
    'stack-check': () => st,
  }, { issues: [904, 903, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'ready')
  assert.match(byIssue[904].mergeReason, /「依存: #904」/)
  assert.equal(byIssue[903].status, 'blocked')
  assert.equal(byIssue[905].status, 'needs_human')
  assert.match(byIssue[905].mergeReason, /Issue #903 が「依存: #904」/)
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.equal(r.result.questions.length, 1)
  assert.equal(r.result.questions[0].pr, 907)
})

// 6. 相手の流れが質問を残さずに終わったら、指すだけにせず、自分で聞く
test('スタック: 同じ実行の上の段が質問なしで止まった（実行の外の依存で blocked）なら、下の段が自分の PR で聞く', { timeout: 5000 }, async () => {
  const r = await run({
    ...SAME_AREA_BASE,
    select: [{ issues: [TWO_ISSUES[0], { ...TWO_ISSUES[1], openDeps: [999] }] }],
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[905].status, 'blocked')
  assert.equal(byIssue[904].status, 'needs_human')
  assert.ok(byIssue[904].question)
  assert.equal(byIssue[904].stackPointer, 906)
  assert.equal(r.result.questions.length, 1)
  assert.equal(r.result.questions[0].pr, 906)
  assert.match(byIssue[904].mergeReason, /質問を残していない/)
  assert.ok(!r.promptsOf('stack-note').some(p => /論点は PR #907 で扱う/.test(p.prompt)))
  assert.equal(r.count('stack-merge'), 0)
})

test('スタック: 同じ実行の下の段が質問なしで終わった（実装の失敗）なら、上の段が自分の PR で聞く', { timeout: 5000 }, async () => {
  const r = await run({
    ...SAME_AREA_BASE,
    implement: prompt => prompt.includes('Issue #904') ? null : UPPER_IMPL,
    select: [{ issues: TWO_ISSUES }],
  }, { issues: [904, 905] })
  const byIssue = Object.fromEntries(r.result.results.map(x => [x.issue, x]))
  assert.equal(byIssue[904].status, 'failed')
  assert.equal(byIssue[905].status, 'needs_human')
  assert.ok(byIssue[905].question)
  assert.equal(r.result.questions.length, 1)
  assert.equal(r.result.questions[0].pr, 907)
  assert.match(byIssue[905].mergeReason, /質問を残していない/)
  assert.equal(r.count('stack-sync'), 0)
})

// 7. stackPlan の理由の文は、当たった形どおりに書く
test('スタック: 段の一覧が空なら、「スタックに属していない」とは書かずに止まる', async () => {
  const r = await run({ 'stack-detect': [{ baseRef: 'issue-338-lower', stackNumber: 925, members: [] }] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('merge'), 0)
  assert.match(r.issue.mergeReason, /スタック #925 に属しているが、.*段の一覧.*を読めなかった/)
  assert.doesNotMatch(r.issue.mergeReason, /スタックに属していない/)
})

test('スタック: 取り直したときにスタックから外れていた（base は main）なら、「base が main でない」とは書かない', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [{ baseRef: 'main', stackNumber: null, members: [] }] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 0)
  assert.match(r.issue.mergeReason, /GitHub のスタックに属していなかった（base は main）/)
  assert.doesNotMatch(r.issue.mergeReason, /main でない/)
})

// 8. テストのなかった分岐
test('スタック: GitHub が gh stack merge を断ったら、rebase せずに理由を添えて 1 回だけ聞く', async () => {
  const rejected = fail('merge', 'rebase が要る（Pull request is not mergeable）', { stackMergeOutcome: 'github_rejected' })
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-merge': [rejected] })
  assert.equal(r.issue.status, 'needs_human')
  assert.match(r.issue.mergeReason, /GitHub が `gh stack merge` を断った（rebase・force push はしていない）。rebase が要る/)
  assert.equal(r.count('stack-merge'), 1)
  assert.equal(r.count('merge'), 0)
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.count('ask'), 1)
  assert.equal(r.result.questions[0].pr, PR)
  assert.match(r.promptOf('cross-check'), /GitHub が `gh stack merge` を断った/)
})

test('スタック: 段が draft なら、取り込まずに止まって聞く', async () => {
  const drafted = stackOf({ ...LOWER, draft: true }, TOP)
  const r = await run({ 'stack-detect': [drafted], 'stack-check': [drafted] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.match(r.issue.mergeReason, /PR #900 が draft/)
  assert.equal(r.count('cross-check'), 1)
})

test('スタック: マージの直前の取り直し（stack-check）が呼び直しでも返らなければ、取り込まずに止まる', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [undefined, undefined] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-check'), 2)
  assert.equal(r.count('stack-sync'), 0)
  assert.equal(r.count('stack-merge'), 0)
  assert.match(r.issue.mergeReason, /取り直せなかった/)
})

test('スタック: 取り込みが failed なら、上の段に進まず止まる', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-sync': [{ outcome: 'failed', pushed: false, headSha: null, worktree: null, summary: '作業ツリーが汚れている: Foo.kt' }] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 1)
  assert.equal(r.count('stack-merge'), 0)
  assert.match(r.issue.mergeReason, /1 段目 PR #900 に main を取り込めなかった（作業ツリーが汚れている: Foo\.kt）/)
})

test('スタック: 2 段目の取り込みが結果を返さなければ、呼び直さずに止まり、1 段目は push 済みと書く', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2], 'stack-sync': [{ outcome: 'merged', pushed: true, headSha: 'a', worktree: 'D:/KiBi/.worktrees/issue-338', summary: 'ok' }, null] })
  assert.equal(r.issue.status, 'needs_human')
  assert.equal(r.count('stack-sync'), 2)
  assert.equal(r.count('stack-merge'), 0)
  assert.ok(r.issue.mergeReason.includes('2 段目 PR #906 に issue-338-lower を取り込めなかった（取り込みの段階が結果を返さなかった）（PR #900（取り込んで push） は取り込んで push 済み）'), r.issue.mergeReason)
})

test('スタック: 確かめが過負荷・起動の失敗・Gradle の未完了の形で返っても、やり直しは呼ばずに止まる（#985。手元で Gradle を動かさない）', async () => {
  for (const extra of [{ gameTestFailedOnly: true, maxTicksBehind: 5000 }, { gradleStartupFailed: true }, PENDING]) {
    const r = await run({
      'stack-detect': [STACK2], 'stack-check': [STACK2],
      'stack-merge': [fail('build', 'Gradle could not start your build。build 実行中。終了コードを読めていない', extra)],
    })
    assert.equal(r.issue.status, 'needs_human')
    for (const k of ['stack-merge-retry', 'stack-merge-restart', 'stack-merge-wait', 'merge-retry', 'merge-restart', 'merge-wait']) assert.equal(r.count(k), 0, k)
    assert.equal(r.count('stack-merge'), 1)
  }
})

test('スタック: merge: false なら、スタックの判定も知らせ待ちもせず、同じ実行の 2 段がどちらも詰まらずに終わる', { timeout: 5000 }, async () => {
  const single = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2] }, { issues: [904], merge: false })
  assert.equal(single.count('stack-detect'), 0)
  assert.equal(single.count('stack-check'), 0)
  assert.equal(single.count('stack-merge'), 0)
  assert.equal(single.issue.status, 'ready')
  const pair = await run({ ...SAME_AREA_BASE, select: [{ issues: SAME_AREA }] }, { issues: [904, 905], merge: false, window: 1 })
  assert.deepEqual(pair.result.results.map(x => x.status).sort(), ['ready', 'ready'])
  assert.equal(pair.count('stack-detect'), 0)
  assert.equal(pair.count('stack-sync'), 0)
  assert.equal(pair.count('stack-merge'), 0)
})

test('スタック: 3 段のスタックは、下の段から順に一つ下の段のブランチを取り込み、一番上の木は一つ下の段を取り込む', async () => {
  const mid = { ...LOWER, pr: 901, branch: 'issue-339-mid', baseRef: LOWER.branch, position: 2, headSha: 'm1' }
  const top = { ...TOP, baseRef: mid.branch, position: 3 }
  const st = stackOf(LOWER, mid, top)
  const r = await run({ 'stack-detect': [st], 'stack-check': [st] })
  assert.equal(r.issue.status, 'merged')
  const syncs = r.promptsOf('stack-sync')
  assert.deepEqual(syncs.map(p => p.opts.label), ['stack-sync:900', 'stack-sync:901', 'stack-sync:906'])
  assert.match(syncs[0].prompt, /1\/3 段目.*`origin\/main` を取り込んで/)
  assert.match(syncs[1].prompt, /2\/3 段目.*`origin\/issue-338-lower` を取り込んで/)
  assert.match(syncs[2].prompt, /3\/3 段目.*`origin\/issue-339-mid` を取り込んで/)
  const m = r.promptOf('stack-merge')
  assert.match(m, /`git merge origin\/issue-339-mid`/)
  assert.match(m, /#900・#901・#906/)
  assert.ok(r.promptsOf('stack-note').some(p => p.opts.label === 'stack-note:901'))
})
