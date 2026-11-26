// issue-to-pr.js（保存済みの Workflow）の、Gradle を待たずに返した段階のやり直し（#904）、マージの段階の Gradle の起動の失敗のやり直し（#958）、
// マージの段階の push の後の CI の、不安定なテストの回し直し（#961）の検査。
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

// overrides: ラベルの種類（'merge' など。`:` より前）→ 呼ばれる順の応答（なければ既定）。関数なら呼んで使う
async function run(overrides = {}, args = { issues: [904] }) {
  const queues = Object.fromEntries(Object.entries(overrides).map(([k, v]) => [k, [...v]]))
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
    'cross-check': prompt => ({ premiseSound: true, needsHuman: true, findings: prompt, question: 'q', guidance: null }),
  }
  const agent = async (prompt, opts) => {
    const kind = opts.label.split(':')[0].replace(/~\d+$/, '')
    calls.push(kind)
    prompts.push({ kind, prompt })
    const q = queues[kind]
    const next = q && q.length ? q.shift() : (defaults[kind] ?? (() => undefined))(prompt)
    return typeof next === 'function' ? next() : next
  }
  const parallel = fns => Promise.all(fns.map(f => f()))
  const fn = new AsyncFunction('args', 'agent', 'parallel', 'log', 'phase', SOURCE)
  const result = await fn(args, agent, parallel, m => logs.push(m), () => {})
  const count = k => calls.filter(c => c === k).length
  const promptOf = k => prompts.find(p => p.kind === k)?.prompt
  return { result, calls, logs, count, promptOf, issue: result.results[0] }
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
