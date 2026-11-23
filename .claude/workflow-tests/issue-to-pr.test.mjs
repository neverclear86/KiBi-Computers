// issue-to-pr.js（保存済みの Workflow）の、Gradle を待たずに返した段階のやり直し（#904）の検査。
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
  status: 'merged', reason: null, failedStep: null, gameTestFailedOnly: false, gameTestSeconds: 100, maxTicksBehind: 0,
  logPath: null, gradleStillRunning: false, runningLogs: [],
}
const fail = (failedStep, reason, extra = {}) => ({ ...MERGED, status: 'needs_human', failedStep, reason, ...extra })
const PENDING = { gradleStillRunning: true, runningLogs: ['merge-build-2.log'] }

// overrides: ラベルの種類（'merge' など。`:` より前）→ 呼ばれる順の応答（なければ既定）。関数なら呼んで使う
async function run(overrides = {}, args = { issues: [904] }) {
  const queues = Object.fromEntries(Object.entries(overrides).map(([k, v]) => [k, [...v]]))
  const calls = []
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
    'cross-check': prompt => ({ premiseSound: true, needsHuman: true, findings: prompt, question: 'q', guidance: null }),
  }
  const agent = async (prompt, opts) => {
    const kind = opts.label.split(':')[0].replace(/~\d+$/, '')
    calls.push(kind)
    const q = queues[kind]
    const next = q && q.length ? q.shift() : (defaults[kind] ?? (() => undefined))(prompt)
    return typeof next === 'function' ? next() : next
  }
  const parallel = fns => Promise.all(fns.map(f => f()))
  const fn = new AsyncFunction('args', 'agent', 'parallel', 'log', 'phase', SOURCE)
  const result = await fn(args, agent, parallel, m => logs.push(m), () => {})
  const count = k => calls.filter(c => c === k).length
  return { result, calls, logs, count, issue: result.results[0] }
}

test('既定の流れはマージまで進み、やり直しは呼ばれない', async () => {
  const r = await run()
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge-wait'), 0)
})

test('#889 の文（申告は false、failedStep=build）で返ったマージは、待つやり直しで進む', async () => {
  const r = await run({
    merge: [fail('build', 'やり直しは返す時点で :core:test を実行中で、終了コードを読めていない。スキーマに未完了がないため needs_human で返す')],
    'merge-wait': [MERGED],
  })
  assert.equal(r.issue.status, 'merged')
  assert.equal(r.count('merge'), 1)
  assert.equal(r.count('merge-wait'), 1)
  assert.ok(r.logs.some(l => l.includes('決まりに反した申告')))
})

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

test('実装: 2 回とも未完了なら、CI に進まず failed になる', async () => {
  const r = await run({ implement: [{ ...IMPL, ...PENDING }], 'implement-wait': [{ ...IMPL, ...PENDING }] })
  assert.equal(r.issue.status, 'failed')
  assert.equal(r.count('ci'), 0)
  assert.match(r.issue.reason, /2 回返した/)
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

test('確認事項の反映: 2 回とも未完了なら、先へ進まず failed になる', async () => {
  const r = await run({
    implement: [{ ...IMPL, openQuestions: ['q'] }],
    'cross-check': [{ premiseSound: true, needsHuman: false, findings: 'f', question: null, guidance: 'g' }],
    resolve: [{ pushed: true, summary: 'x', ...PENDING }],
    'resolve-wait': [{ pushed: true, summary: 'y', ...PENDING }],
  })
  assert.equal(r.issue.status, 'failed')
  assert.equal(r.count('ci'), 0)
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
