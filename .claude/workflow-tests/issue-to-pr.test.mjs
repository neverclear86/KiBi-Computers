// issue-to-pr.js（保存済みの Workflow）の、Gradle を待たずに返した段階のやり直し（#904）、マージの段階の Gradle の起動の失敗のやり直し（#958）、
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
})

// PR #889 のマージの段階が実際に返した理由の文（切らずにそのまま）。1 回目の失敗の記述（「落ちたので」「落ちたわけではないが」）を含む
const PR889_REASON = 'マージは行っていない。main の取り込みは衝突なしで済んだ（コミット 65c5fb52。まだ push していない）。最初の `./gradlew build` は、maven.neoforged.net の名前解決の失敗（一時的なネットワークの不調）で落ちたので、同じ内容でやり直した（merge-build-2）。やり直しは返す時点で :core:test を実行中で、終了コードを読めていない。GameTest・CI・マージの段階には進んでいない。build が落ちたわけではないが、スキーマに「未完了」がないため needs_human で返す。この段階を最初からやり直してほしい。'

test('#889 の実際の文（申告は false、failedStep=build）で返ったマージは、待つやり直しで進む', async () => {
  const r = await run({ merge: [fail('build', PR889_REASON)], 'merge-wait': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge'), 1)
  assert.equal(r.count('merge-wait'), 1)
  assert.ok(r.logs.some(l => l.includes('決まりに反した申告')))
})

// ネットワークの失敗を回し直し、その回し直しを待たずに返した回は、理由の文に 1 回目の失敗の記述が入る。それでも未完了と見る
for (const [name, text] of [
  ['強い手がかり（終了コード）と、1 回目の Could not HEAD', '1 回目は Could not HEAD https://maven.neoforged.net/... で落ちた。回し直しの build-2 は終了コードを確かめていない'],
  ['強い手がかり（.exit がない）と、1 回目の UnknownHost', '1 回目は UnknownHostException で落ちたので回し直した。build-2 の .exit がまだない'],
  ['弱い手がかりだけで、1 回目の失敗は別の一文', '1 回目は Read timed out で落ちた。回し直しの :core:test がまだ実行中'],
]) {
  test(`回し直しを待たずに返した文は、1 回目の失敗の記述があっても待つやり直しをする: ${name}`, async () => {
    const r = await run({ merge: [fail('build', text)], 'merge-wait': [MERGED] })
    assert.equal(r.issue.status, 'merged')
    assert.equal(r.count('merge-wait'), 1)
  })
}

test('申告 true で返ったマージは、待つやり直しで進む', async () => {
  const r = await run({ merge: [fail('build', 'build 実行中', PENDING)], 'merge-wait': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-wait'), 1)
})

test('2 回とも申告 true なら、やり直しは 1 回で止まり、理由に「2 回返した」が入る', async () => {
  const r = await run({ merge: [fail('build', 'a', PENDING)], 'merge-wait': [fail('build', 'b', PENDING)] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-wait'), 1)
  assert.match(r.issue.mergeReason, /2 回返した/)
})

test('やり直しの段階が結果を返さなかったら、「2 回返した」とは書かず、段階の失敗として止まる', async () => {
  const r = await run({ merge: [fail('build', 'a', PENDING)], 'merge-wait': [null] })
  assert.notEqual(r.issue.status, 'merged')
  assert.doesNotMatch(r.issue.mergeReason, /2 回返した/)
  assert.match(r.issue.mergeReason, /やり直しの段階も失敗した/)
})

// マージの理由の文が、Gradle が終わったあとの本物の失敗を述べている回は、やり直さない
for (const [name, step, text, extra] of [
  ['ロックの待ちの上限（Held by に別の worktree のタスク名）', 'gametest', 'GameTest のロックの待ちの上限（90 分）に達した。Held by: :platform-neoforge-1.21.1:runGameTestServer in .worktrees/issue-900 が実行中のまま', {}],
  ['ロックの待ちの上限（failedStep=build）', 'build', 'GameTest のロックの待ちの上限に達した。Held by: runGameTestServer in .worktrees/issue-900 が実行中のまま', {}],
  ['GameTest のタイムアウト', 'gametest', 'GameTest が時間内に終わっていない（タイムアウト）', {}],
  ['サーバーが落ちた', 'gametest', 'testFoo が終わる前にサーバーが落ちた', {}],
  ['サーバーが落ちた（failedStep=build）', 'build', 'テストが終わる前にサーバーが落ちた', {}],
  ['GameTest のテストの失敗（文は「実行中」を含む）', 'gametest', 'GameTest が実行中に 2 件失敗した（required tests failed）', { gameTestFailedOnly: true }],
  ['failedStep=ci の「実行中」', 'ci', 'CI が実行中のまま 30 分たった', {}],
  ['コンパイルの失敗', 'build', ':core:compileKotlin が失敗した（型の不一致）', {}],
  ['ロックの待ちの上限と、別の一文の「実行中」', 'build', 'GameTest のロックの待ちの上限に達した。Held by: runGameTestServer in .worktrees/issue-900。そのタスクはまだ実行中だった', {}],
  ['ネットワークの失敗が 2 回続いた', 'build', '依存の取得の失敗（ネットワーク）が 2 回続いた。2 回目も Could not HEAD で落ちた', {}],
]) {
  test(`本物の失敗は待つやり直しをしない: ${name}`, async () => {
    const r = await run({ merge: [fail(step, text, extra)] })
    assert.equal(r.count('merge-wait'), 0)
    assert.notEqual(r.issue.status, 'merged')
    assert.doesNotMatch(r.issue.mergeReason ?? '', /2 回返した/)
  })
}

test('待つやり直しのあとで過負荷の GameTest が落ちたら、merge-retry で進む', async () => {
  const r = await run({
    merge: [fail('build', '終了コードを読めていない', {})],
    'merge-wait': [fail('gametest', 'GameTest が落ちた', { gameTestFailedOnly: true, maxTicksBehind: 5000 })],
    'merge-retry': [MERGED],
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-wait'), 1)
  assert.equal(r.count('merge-retry'), 1)
})

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

const CONFLICT = { ...OK_CI, passed: false, mergeable: 'CONFLICTING', noChecks: true }

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

// ---- Gradle の起動の失敗（BindException）のやり直し（#958） ----

const STARTUP = { gradleStartupFailed: true }
const OVERLOAD = { gameTestFailedOnly: true, maxTicksBehind: 5000 }
// PR #951 のマージの段階が実際に返した理由の文（切らずにそのまま）。gradleStartupFailed の項目がまだない頃のもの
const PR951_REASON = 'main は取り込み済み（衝突なし、既に最新）。build は 1 回目が Forge の maven への DNS 失敗で落ち、2 回目で通った。GameTest は Gradle の起動時に「java.net.BindException: Address already in use: bind」で起動できず、サーバーは動いていない（テストの失敗ではない）。回し直しはこの段階では行わない決まりで、自動の再実行も拒否されたため止めた。マージ、push、CI の待ちはしていない。'
const withoutFlag = m => { const c = { ...m }; delete c.gradleStartupFailed; return c }

for (const step of ['build', 'gametest']) {
  test(`起動の失敗（${step}）は merge-restart を 1 回呼んでマージまで進み、理由に残る`, async () => {
    const r = await run({ merge: [fail(step, 'Gradle could not start your build', STARTUP)], 'merge-restart': [MERGED] })
    assert.equal(r.issue.status, 'merged')
    assert.equal(r.count('merge-restart'), 1)
    assert.equal(r.count('merge-wait'), 0)
    assert.equal(r.count('merge-retry'), 0)
    assert.match(r.issue.mergeReason, /起動の失敗.*1 回だけやり直した/)
  })
}

test('起動の失敗が 2 回続いたら、やり直しは 1 回で止まり、人に上がる', async () => {
  const r = await run({ merge: [fail('gametest', 'a', STARTUP)], 'merge-restart': [fail('gametest', 'b', STARTUP)] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart'), 1)
  assert.equal(r.count('merge-retry'), 0)
  assert.match(r.issue.mergeReason, /2 回続いた/)
  assert.equal(r.count('cross-check'), 1)
  assert.equal(r.count('ask'), 1)
})

test('#951 の実際の文（項目なし、failedStep=gametest）は、待つやり直しに入らず、起動の失敗のやり直しに進む', async () => {
  const r = await run({ merge: [withoutFlag(fail('gametest', PR951_REASON))], 'merge-restart': [MERGED] })
  assert.equal(r.count('merge-wait'), 0)
  assert.equal(r.count('merge-restart'), 1)
  assert.equal(r.issue.status, 'merged')
})

for (const step of ['conflict', 'ci', 'merge']) {
  test(`gradleStartupFailed=true でも failedStep=${step} ならやり直さない`, async () => {
    const r = await run({ merge: [fail(step, 'x', STARTUP)] })
    assert.equal(r.count('merge-restart'), 0)
    assert.notEqual(r.issue.status, 'merged')
  })
}

test('項目がなく、理由の文も起動の失敗を述べない回は、やり直さない', async () => {
  const r = await run({ merge: [withoutFlag(fail('gametest', '2 required tests failed'))] })
  assert.equal(r.count('merge-restart'), 0)
  assert.notEqual(r.issue.status, 'merged')
})

test('項目がなく、failedStep=build の回も、文が BindException と Gradle の起動の失敗を述べるならやり直す', async () => {
  const r = await run({ merge: [withoutFlag(fail('build', PR951_REASON))], 'merge-restart': [MERGED] })
  assert.equal(r.count('merge-restart'), 1)
  assert.equal(r.issue.status, 'merged')
})

for (const step of ['build', 'gametest']) {
  test(`gradleStartupFailed=false と申告した回（${step}）は、文が BindException と Gradle の起動を述べてもやり直さない`, async () => {
    const r = await run({ merge: [fail(step, 'Gradle が起動した GameTest サーバーの中で BindException が出て落ちた', { gradleStartupFailed: false })] })
    assert.equal(r.count('merge-restart'), 0)
    assert.notEqual(r.issue.status, 'merged')
  })
}

test('テストの中の BindException（サーバーが起動してテストが落ちた）は、文に BindException があってもやり直さない', async () => {
  const r = await run({ merge: [fail('gametest', 'DevDriverServer の起動で BindException が出て 1 required tests failed', { gameTestFailedOnly: true })] })
  assert.equal(r.count('merge-restart'), 0)
  assert.notEqual(r.issue.status, 'merged')
})

test('過負荷の GameTest の失敗は、今までどおり merge-retry だけになる', async () => {
  const r = await run({ merge: [fail('gametest', 'x', OVERLOAD)], 'merge-retry': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart'), 0)
  assert.equal(r.count('merge-retry'), 1)
})

test('やり直しの結果が過負荷の GameTest の失敗なら、merge-retry を 1 回呼んで進む', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [fail('gametest', 'y', OVERLOAD)], 'merge-retry': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart'), 1)
  assert.equal(r.count('merge-retry'), 1)
  assert.match(r.issue.mergeReason, /起動の失敗/)
  assert.match(r.issue.mergeReason, /過負荷/)
})

test('過負荷の回し直しの中で起動の失敗が出たら、もうやり直さずに止まる', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [fail('gametest', 'y', OVERLOAD)], 'merge-retry': [fail('gametest', 'z', STARTUP)] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart'), 1)
  assert.equal(r.count('merge-retry'), 1)
})

test('merge-retry で起動の失敗が出ても、merge-restart は呼ばない', async () => {
  const r = await run({ merge: [fail('gametest', 'x', OVERLOAD)], 'merge-retry': [fail('gametest', 'z', STARTUP)] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart'), 0)
  assert.equal(r.count('merge-retry'), 1)
})

test('未完了（gradleStillRunning=true）の回は、gradleStartupFailed が付いていても merge-wait が先に呼ばれる', async () => {
  const r = await run({ merge: [fail('build', 'x', { ...STARTUP, ...PENDING })], 'merge-wait': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-wait'), 1)
  assert.equal(r.count('merge-restart'), 0)
})

test('やり直しの回が未完了なら merge-restart-wait で待ち、2 回とも未完了なら止まる', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [fail('build', 'y', PENDING)], 'merge-restart-wait': [fail('build', 'z', PENDING)] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart-wait'), 1)
  assert.match(r.issue.mergeReason, /2 回返した/)
})

test('起動の失敗のやり直しの段階が結果を返さなかったら、段階の失敗として止まる', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [null] })
  assert.notEqual(r.issue.status, 'merged')
  assert.match(r.issue.mergeReason, /やり直しの段階が失敗した/)
})

test('プロンプト: マージは起動の失敗を自分で回し直さず、ほかの段階は 90 秒置いて 1 回回し直す', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [MERGED] })
  const merge = r.promptOf('merge')
  assert.match(merge, /FileLockContentionHandler/)
  assert.match(merge, /自分で回し直さず/)
  assert.match(merge, /gradleStartupFailed=true/)
  assert.match(merge, /merge-build-\$n/)
  const restart = r.promptOf('merge-restart')
  assert.match(restart, /merge-restart-build-\$n/)
  assert.match(restart, /90 秒以上置く/)
  const impl = r.promptOf('implement')
  assert.match(impl, /Unable to start the daemon process/)
  assert.match(impl, /90 秒以上置いてから/)
  assert.match(impl, /2 回続いた/)
  assert.doesNotMatch(impl, /gradleStartupFailed=true/)
})

test('やり直しの回が未完了で、merge-restart-wait でマージできたら、理由に両方のやり直しが残る', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [fail('build', 'y', PENDING)], 'merge-restart-wait': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart'), 1)
  assert.equal(r.count('merge-restart-wait'), 1)
  assert.match(r.issue.mergeReason, /起動の失敗.*1 回だけやり直した/)
  assert.match(r.issue.mergeReason, /やり直しの段階が Gradle の終わりを待たずに返したので、それも 1 回だけやり直した/)
})

test('過負荷の回し直しが未完了で、merge-retry-wait でマージできたら、理由にそれが残る', async () => {
  const r = await run({ merge: [fail('gametest', 'x', OVERLOAD)], 'merge-retry': [fail('build', 'y', PENDING)], 'merge-retry-wait': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-retry'), 1)
  assert.equal(r.count('merge-retry-wait'), 1)
  assert.match(r.issue.mergeReason, /過負荷/)
  assert.match(r.issue.mergeReason, /回し直しの段階が Gradle の終わりを待たずに返したので、それも 1 回だけやり直した/)
})

test('待つやり直しを通らなかったやり直しの回は、理由に待つやり直しを書かない', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [MERGED] })
  assert.doesNotMatch(r.issue.mergeReason, /それも 1 回だけやり直した/)
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
  ['build', fail('build', ':core:test が落ちた')],
  ['conflict', fail('conflict', '字面で解けない')],
  ['merge', fail('merge', 'base branch policy')],
  ['過負荷でない gametest', fail('gametest', '1 required tests failed', { gameTestFailedOnly: true })],
]) {
  test(`failedStep=${name} なら merge-flaky を呼ばない`, async () => {
    const r = await run({ merge: [m] })
    assert.equal(r.count('merge-flaky'), 0)
    assert.notEqual(r.issue.status, 'merged')
  })
}

test('確かめが rerun で gradleStillRunning=true なら、merge-flaky-wait はせず、merge-rerun の指示に孤児の待ちが入る', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...FLAKY, ...PENDING }], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky-wait'), 0)
  assert.equal(r.count('merge-rerun'), 1)
  assert.match(r.promptOf('merge-rerun'), /確かめの段階が残した Gradle を待つ/)
  assert.match(r.promptOf('merge-rerun'), /Win32_Process/)
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

test('過負荷の回し直しのあとで CI が落ちたら、不安定なテストの回し直しに進み、理由に両方が残る', async () => {
  const r = await run({ merge: [fail('gametest', 'x', OVERLOAD)], 'merge-retry': [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-retry'), 1)
  assert.equal(r.count('merge-flaky'), 1)
  assert.equal(r.count('merge-rerun'), 1)
  assert.match(r.issue.mergeReason, /過負荷/)
  assert.match(r.issue.mergeReason, /不安定なテスト/)
})

test('起動の失敗のやり直しのあとで CI が落ちたら、不安定なテストの回し直しに進む', async () => {
  const r = await run({ merge: [fail('build', 'x', STARTUP)], 'merge-restart': [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-restart'), 1)
  assert.equal(r.count('merge-rerun'), 1)
})

test('merge-rerun が Gradle を待たずに返したら、merge-rerun-wait で進む', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [fail('build', 'y', PENDING)], 'merge-rerun-wait': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-rerun-wait'), 1)
  assert.match(r.issue.mergeReason, /回し直しのあとのマージの段階が Gradle の終わりを待たずに返したので、それも 1 回だけやり直した/)
})

test('merge-rerun が 2 回とも Gradle を待たずに返したら止まり、もう確かめに回さない', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [fail('ci', 'y', PENDING)], 'merge-rerun-wait': [fail('ci', 'z', PENDING)] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky'), 1)
  assert.match(r.issue.mergeReason, /2 回返した/)
})

test('merge-rerun の中の起動の失敗や過負荷は、もう回し直さない', async () => {
  for (const m of [fail('build', 'x', STARTUP), fail('gametest', 'x', OVERLOAD)]) {
    const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY], 'merge-rerun': [m] })
    assert.notEqual(r.issue.status, 'merged')
    assert.equal(r.count('merge-restart'), 0)
    assert.equal(r.count('merge-retry'), 0)
  }
})

test('マージの段階が failedStep=ci のまま 2 回 Gradle を待たずに返したら、確かめに回さない', async () => {
  const r = await run({ merge: [fail('ci', 'a', PENDING)], 'merge-wait': [fail('ci', 'b', PENDING)] })
  assert.notEqual(r.issue.status, 'merged')
  assert.equal(r.count('merge-flaky'), 0)
})

test('プロンプト: 確かめは CI の直しと同じ条件の文を使ってコードを直さず、merge-rerun は自分で回し直さず手順 2 を飛ばせる', async () => {
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
  assert.match(rerun, /merge-rerun-build-\$n/)
  assert.match(rerun, /自分では `gh run rerun` しない/)
  assert.match(rerun, /abc のままなら、手順 2（build と GameTest）は飛ばしてよい/)
  assert.doesNotMatch(rerun, /確かめの段階が残した Gradle を待つ/)
})

test('プロンプト: 確かめが headSha=null を返したら、merge-rerun は手順 2 を飛ばさずに全部回す', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...FLAKY, headSha: null }], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  const rerun = r.promptOf('merge-rerun')
  assert.match(rerun, /手順 2（build と GameTest）は、手順どおり全部回す/)
  assert.doesNotMatch(rerun, /は飛ばしてよい/)
})

test('プロンプト: 確かめが pending なら、merge-rerun は「回し直してはいない」と書き、先頭のコミットが同じときだけ手順 2 を飛ばす', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [FLAKY_PENDING], 'merge-rerun': [MERGED] })
  const rerun = r.promptOf('merge-rerun')
  assert.match(rerun, /CI がまだ終わっていなかったと判断した（run 123。回し直してはいない）/)
  assert.doesNotMatch(rerun, /落ちたジョブを回し直した/)
  assert.match(rerun, /先頭のコミット（`git rev-parse HEAD`）が abc のままなら、手順 2（build と GameTest）は飛ばしてよい/)
  assert.match(rerun, /先頭のコミットが変わった（main を取り込んだ）なら、手順 2 から全部回す/)
})

test('確かめが runId を数値で返しても、そのまま merge-rerun に進む', async () => {
  const r = await run({ merge: [CI_FAIL], 'merge-flaky': [{ ...FLAKY, runId: 123456 }], 'merge-rerun': [MERGED] })
  assert.equal(r.issue.status, 'merged')
  assert.match(r.promptOf('merge-rerun'), /run 123456 の落ちたジョブを回し直した/)
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
const LOWER = { pr: 900, branch: 'issue-338-lower', baseRef: 'main', position: 1, state: 'OPEN', draft: false, needsHuman: false, headSha: 'l1' }
const TOP = { pr: PR, branch: IMPL.branch, baseRef: LOWER.branch, position: 2, state: 'OPEN', draft: false, needsHuman: false, headSha: 't1' }
const stackOf = (...members) => ({ baseRef: members.find(m => m.pr === PR)?.baseRef ?? 'main', stackNumber: 925, members })
const STACK2 = stackOf(LOWER, TOP)
const STACK_CMD = `gh stack merge ${PR} --yes --squash`
const TWO_ISSUES = [
  { number: 904, title: 'a', areas: ['a'], openDeps: [], blockedReason: null, needsPlan: false },
  { number: 905, title: 'b', areas: ['b'], openDeps: [], blockedReason: null, needsPlan: false },
]
const UPPER_IMPL = { ...IMPL, pr: 907, branch: 'issue-905-y', worktree: 'D:/w/issue-905', baseRef: 'issue-904-x' }

test('スタック: 上の段は、下の段から順に取り込み、一番上の木で 1 回だけ確かめて gh stack merge でマージする', async () => {
  const r = await run({ 'stack-detect': [STACK2], 'stack-check': [STACK2] })
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
  assert.match(m, /runGameTestServer/)
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
  assert.match(note.prompt, /needs-human/)
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

test('スタック: 確かめの過負荷の GameTest の失敗は、ふつうのマージと同じく 1 回だけ回し直す', async () => {
  const r = await run({
    'stack-detect': [STACK2],
    'stack-check': [STACK2],
    'stack-merge': [fail('gametest', 'GameTest が落ちた', { gameTestFailedOnly: true, maxTicksBehind: 5000 })],
    'stack-merge-retry': [{ ...MERGED, stackMergeOutcome: 'merged' }],
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('stack-merge-retry'), 1)
  assert.ok(r.promptOf('stack-merge-retry').includes(STACK_CMD))
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
