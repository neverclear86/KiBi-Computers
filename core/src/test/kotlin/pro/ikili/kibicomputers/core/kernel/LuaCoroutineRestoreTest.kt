package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.FileModuleStore
import pro.ikili.kibicomputers.core.persist.FullSaveRule
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.WriteResult
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ブロッキングするスタイルの Lua（コルーチンの上で待つもの）を丸ごと保存して戻す（#89）。
 *
 * [LuaKibiApiRestoreTest] は KiBi API の待ち（`pullEvent`・タイマー・`parallel`・`peripheral.call`）ごとの保存・復元を見る。
 * ここでは、その上で動くユーザーのプログラムの状態が丸ごと続くことを見る:
 *
 * - ユーザーのコルーチン・上位値・メタテーブル・弱いテーブル・`__gc`・乱数の状態（線形メモリのヒープ）が、保存しなかった実行と同じ出力で続く
 * - yield しない待ち（yield できない文脈の `os.sleep`・`peripheral.call`、コルーチンの中の `io.read`）を、コルーチンの C のフレームごと巻き戻して続ける
 * - 燃料切れで巻き戻った計算の途中から続ける
 * - 同じプロセスを何回も保存して戻す（差分の世代から戻すものを含む）
 * - 保存した側のスケジューラと置き場を閉じ、開き直して戻す（サーバーの再起動のまね）。戻した直後にまた保存できる
 *
 * 出力は `restored`（ADR 0087）に左右されない書き方にして、保存しなかった実行と比べる（線形メモリは `restored` で必ず違うので比べない）。
 * 写しは、印が出て、プロセスが待機点で待つようになってから取る（印が出た直後は step の途中のことがある）
 */
class LuaCoroutineRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(180)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val address = "00000000-0000-4000-8000-0000000000c9"

    /** メインスレッドのメソッド `main-echo`（呼ばれた回数は [calls]）と、直接のメソッド `echo` を持つデバイス */
    private class Dev(private val calls: AtomicInteger) : Device {
        override val kind = "test:dev"
        override val methods: DeviceMethods = DeviceMethods.builder()
            .mainThread("main-echo") { _, args ->
                calls.incrementAndGet()
                Array(args.size()) { args.get(it) }
            }
            .direct("echo") { _, args -> Array(args.size()) { args.get(it) } }
            .build()
    }

    /** 動かしたプロセス。[input]・[output] は標準入力・標準出力のストリームの ID（写しに入るので、戻したマシンでも同じ） */
    private class Started(val pid: Int, val input: Int, val output: Int)

    private fun output(m: Machine, id: Int): String = m.lock.withLockValue {
        (m.kernel.objects[id] as? ByteStream)?.availableBytes()?.toString(Charsets.UTF_8) ?: ""
    }

    private fun send(m: Machine, id: Int, text: String) = m.lock.withLockValue {
        val b = text.toByteArray()
        (m.kernel.objects[id] as ByteStream).write(b, 0, b.size, m.wakeSink)
        Unit
    }

    /** 根のディスクとして [fs] を開く口を [h] に入れる（戻すたびに同じものを返す。ディスクのファイルは保存をまたいで残る） */
    private fun volumes(h: Harness, fs: TestMemoryFileSystem) {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(fs, readOnly = false) else null }
    }

    private val bootConfig: BootConfig = BootConfig.builder().addBootDisk("none").build()

    /** [fs] を根にした [m] で、`/bin/t`（[script]）を lua.wasm で動かす。[tmp] なら先に `/tmp` を付ける */
    private fun start(m: Machine, fs: TestMemoryFileSystem, script: String, calls: AtomicInteger, tmp: Boolean = false): Started {
        m.vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "disk:root")
        if (tmp) assertEquals(null, m.mountTmp())
        runCatching { m.vfs.createDirectory(VfsPath.parse("/bin")) }
        fun write(path: String, bytes: ByteArray) {
            val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
        }
        write("/bin/lua", requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")).use { it.readBytes() })
        write("/bin/t", ("#!/bin/lua\n" + script).toByteArray())
        assertTrue(m.devices.attach(address, Dev(calls)))
        val (inId, outId) = m.lock.withLockValue {
            val i = ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            val o = ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            i to o
        }
        val stdio = StdioSpec(
            StdioSource.Object(inId, HandleRights.READ),
            StdioSource.Object(outId, HandleRights.WRITE),
            StdioSource.Object(outId, HandleRights.WRITE),
        )
        val pid = m.exec("/bin/t", KernelDrivers.DEFAULT, SpawnOptions(stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN))
        m.lock.withLockValue { m.kernel.initPid = pid }
        return Started(pid, inId, outId)
    }

    /**
     * [condition] がそろうまで tick を回す（そろった後は回さない: 回すと先に進んでしまう）。
     * [holdQueuedHostCalls] なら、ホスト呼び出しが列にある間は、燃料が尽きて待つプロセスがいるときだけ tick を進める
     * （tick の中で列の呼び出しが実行されるので、保存するまで列に残す）
     */
    private fun tickUntil(h: Harness, m: Machine, s: Started, what: String, holdQueuedHostCalls: Boolean = false, condition: () -> Boolean) {
        eventuallyDescribe(60_000, { "$what: ${output(m, s.output)}" }) {
            if (condition()) return@eventuallyDescribe true
            if (!holdQueuedHostCalls || m.pendingHostCalls == 0 || m.fuelWaiterCount > 0) h.scheduler.tick()
            Thread.sleep(1)
            false
        }
    }

    /** [ready] が出て、プロセスがイベント（入力・時計・デバイス）を待つ待機点にいる */
    private fun waiting(m: Machine, s: Started, ready: String): Boolean =
        ready in output(m, s.output) && m.stateOf(s.pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint

    /**
     * 写しを取って書く（[current] がなければ新しい ID を振る）。[deltaBase] を渡すと、比を 1.0 にして差分を書く（全体に化けない）。
     * 待機点で取るので `null` にならない（燃料待ちのときは [captureRetrying] を使う）
     */
    private fun save(store: MachineSaveStore, m: Machine, current: MachineIdentity? = null, deltaBase: DeltaBase? = null): WriteResult {
        val snapshot = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT, deltaBase, FullSaveRule(1.0))
        assertNotNull(snapshot, "待っている間は保存できる")
        assertEquals(deltaBase, snapshot!!.deltaBase)
        return store.write(current ?: store.assignNewId(owner)!!, owner, snapshot)
    }

    /** [identity] の世代から新しいマシンに戻し、`RESTORED` の知らせが来るまで tick を回す。戻したマシンにデバイスをつなぎ直し、列の呼び出しを積み直す */
    private fun restore(h: Harness, store: MachineSaveStore, identity: MachineIdentity, calls: AtomicInteger, fuelPerTick: Long = FUEL, maxHostCallsPerTick: Int = 64): Machine {
        val r = h.machine(fuelPerTick = fuelPerTick, maxHostCallsPerTick = maxHostCallsPerTick)
        val outcomes = Collections.synchronizedList(ArrayList<RestoreOutcome>())
        val listener = RestoreListener { machine, outcome, _ ->
            if (outcome == RestoreOutcome.RESTORED) {
                machine.devices.attach(address, Dev(calls))
                machine.redispatchRestoredDeviceCalls()
            }
            outcomes += outcome
        }
        assertTrue(r.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig, listener)))
        eventually(60_000, "復元が終わらない") {
            h.scheduler.tick()
            Thread.sleep(1)
            outcomes.isNotEmpty()
        }
        assertEquals(listOf(RestoreOutcome.RESTORED), outcomes.toList())
        return r
    }

    /** [script] を保存せずに [END] まで動かした出力（比べる元） */
    private fun reference(h: Harness, script: String, input: List<String> = emptyList(), fuelPerTick: Long = FUEL, tmp: Boolean = false): String {
        val m = h.machine(fuelPerTick = fuelPerTick)
        val s = start(m, TestMemoryFileSystem(), script, AtomicInteger(), tmp)
        for (line in input) send(m, s.input, line)
        tickUntil(h, m, s, "保存しない実行が終わらない") { END in output(m, s.output) }
        return output(m, s.output).also { m.close() }
    }

    private fun store(name: String = "machines"): MachineSaveStore = MachineSaveStore(dir.resolve(name), MachineIdIndex(), "test")

    private fun harness(modules: ModuleStore = MemoryModuleStore()): Harness = Harness(execRuntime = EndiveRuntime(), moduleStore = modules)

    @Test
    fun `ユーザーのコルーチン・上位値・メタテーブル・弱いテーブル・__gc・乱数の状態が、保存しなかった実行と同じに続く`() = timeout(limit) {
        val script = """
            math.randomseed(42)
            local finalized = 0
            local keep = setmetatable({}, { __gc = function() finalized = finalized + 1 end })
            local strongKey = {}
            local weak = setmetatable({}, { __mode = 'k' })
            weak[strongKey] = 'kept'
            weak[{}] = 'dropped'
            local function make(name, n)
                local total = 0
                local obj = setmetatable({ name = name }, {
                    __index = function(_, k) return k .. '!' end,
                    __tostring = function(t) return 'O<' .. t.name .. '>' end,
                })
                return coroutine.create(function(...)
                    local args = { ... }
                    for i = 1, n do
                        total = total + i * math.random(1, 100)
                        local got = coroutine.yield(name, i, total, tostring(obj), obj.missing, #args)
                        args[#args + 1] = got
                    end
                    return name, 'done', total, table.concat(args, '+')
                end)
            end
            local cos = { make('a', 4), make('b', 3), make('c', 5) }
            local round = 0
            while true do
                local alive = false
                for _, co in ipairs(cos) do
                    if coroutine.status(co) == 'suspended' then
                        alive = true
                        print(coroutine.resume(co, 'r' .. round))
                    end
                end
                if not alive then break end
                round = round + 1
                if round == 2 then
                    print('rea' .. 'dy'); io.stdout:flush()
                    os.sleep(0.2)
                end
            end
            collectgarbage(); collectgarbage()
            local n = 0
            for _, v in pairs(weak) do n = n + 1 end
            print('weak', n, weak[strongKey], 'gc', finalized)
            keep = nil
            collectgarbage(); collectgarbage()
            print('gc', finalized, math.random(1, 1000000))
            print('E' .. 'ND')
        """.trimIndent() + "\n"
        harness().use { h ->
            val expected = reference(h, script)
            assertTrue(expected.contains("c\tdone\t"), expected)
            assertTrue(expected.contains("weak\t1\tkept\tgc\t0\n"), expected)

            val fs = TestMemoryFileSystem()
            volumes(h, fs)
            val store = store()
            val calls = AtomicInteger()
            val m = h.machine(fuelPerTick = FUEL)
            val s = start(m, fs, script, calls)
            tickUntil(h, m, s, "sleep で待たない") { waiting(m, s, "ready\n") }
            val written = save(store, m)
            m.close()
            val r = restore(h, store, written.identity, calls)
            tickUntil(h, r, s, "戻したプロセスが終わらない") { END in output(r, s.output) }
            assertEquals(expected, output(r, s.output))
            r.close()
        }
    }

    @Test
    fun `yieldできない文脈のsleepとperipheral_callを、parallelのコルーチンの中で待つ間に保存して戻すと、C のフレームごと続く`() = timeout(limit) {
        val script = """
            os.queueEvent('open'); os.pullEvent('open')
            parallel.waitForAll(
                function()
                    local t = { 5, 3, 9, 1, 7 }
                    local first = true
                    table.sort(t, function(x, y)
                        if first then
                            first = false
                            print('sor' .. 'ting'); io.stdout:flush()
                            os.sleep(0.5)
                        end
                        return x < y
                    end)
                    print('sorted', table.concat(t, ','))
                end,
                function()
                    local e = os.pullEvent('restored'); print('b', e)
                    local s = string.gsub('a-b', '%a', function(c)
                        print('in' .. 'gsub', c); io.stdout:flush()
                        return peripheral.call('$address', 'main-echo', c:upper())
                    end)
                    print('gsub', s)
                end
            )
            print('E' .. 'ND')
        """.trimIndent() + "\n"
        harness().use { h ->
            val fs = TestMemoryFileSystem()
            volumes(h, fs)
            val store = store()
            val calls = AtomicInteger()
            // 1 回目: table.sort の比較関数の中の os.sleep（その場で待つ）
            val m = h.machine(fuelPerTick = FUEL, maxHostCallsPerTick = 1)
            val s = start(m, fs, script, calls)
            tickUntil(h, m, s, "比較関数の中で待たない") { waiting(m, s, "sorting\n") }
            val first = save(store, m)
            m.close()
            // 2 回目: string.gsub のコールバックの中の peripheral.call（その場で待つ）。メソッドが列にある間に保存する
            val r = restore(h, store, first.identity, calls, maxHostCallsPerTick = 1)
            tickUntil(h, r, s, "コールバックの中で待たない", holdQueuedHostCalls = true) {
                "ingsub\ta\n" in output(r, s.output) && r.pendingHostCalls > 0 && r.isAtWaitPoint
            }
            assertEquals(0, calls.get(), "保存するときは、まだ実行していない")
            val second = save(store, r, first.identity)
            r.close()
            val r2 = restore(h, store, second.identity, calls)
            tickUntil(h, r2, s, "戻したプロセスが終わらない") { END in output(r2, s.output) }
            assertEquals(
                "sorting\nsorted\t1,3,5,7,9\nb\trestored\ningsub\ta\ningsub\tb\ngsub\tA-B\nEND\n",
                output(r2, s.output),
            )
            // 列にあった呼び出しは戻した後に 1 回だけ実行し、続きの 'b' の呼び出しと合わせて 2 回
            assertEquals(2, calls.get())
            r2.close()
        }
    }

    @Test
    fun `コルーチンの中とメインのio_readで待つ間に保存して戻すと、渡した行で続き、開いたディスクとtmpのファイルにも続けて書ける`() = timeout(limit) {
        val script = """
            local f = assert(io.open('/data.txt', 'w'))
            f:write('before;')
            local t = assert(io.open('/tmp/t.txt', 'w'))
            t:write('tmp1;'); t:flush()
            local read = coroutine.wrap(function(prompt)
                print(prompt); io.stdout:flush()
                local line = io.read()
                coroutine.yield(line)
                return 'unused'
            end)
            local line = read('rea' .. 'dy')
            f:write('after:' .. line .. ';'); f:close()
            t:write('tmp2:' .. line); t:close()
            print('co', line); io.stdout:flush()
            local l2 = io.read()
            print('main', l2)
            local g = assert(io.open('/data.txt')); print(g:read('a')); g:close()
            local u = assert(io.open('/tmp/t.txt')); print(u:read('a')); u:close()
            print('E' .. 'ND')
        """.trimIndent() + "\n"
        harness().use { h ->
            val fs = TestMemoryFileSystem()
            volumes(h, fs)
            val store = store()
            val calls = AtomicInteger()
            val m = h.machine(fuelPerTick = FUEL)
            val s = start(m, fs, script, calls, tmp = true)
            tickUntil(h, m, s, "コルーチンの中の io.read で待たない") { waiting(m, s, "ready\n") }
            val first = save(store, m)
            m.close()
            val r = restore(h, store, first.identity, calls)
            send(r, s.input, "hello\n")
            tickUntil(h, r, s, "メインの io.read で待たない") { waiting(r, s, "co\thello\n") }
            // メインのチャンクの io.read で待つ間に、もう一度保存して戻す
            val second = save(store, r, first.identity)
            r.close()
            val r2 = restore(h, store, second.identity, calls)
            send(r2, s.input, "world\n")
            tickUntil(h, r2, s, "戻したプロセスが終わらない") { END in output(r2, s.output) }
            assertEquals("ready\nco\thello\nmain\tworld\nbefore;after:hello;\ntmp1;tmp2:hello\nEND\n", output(r2, s.output))
            r2.close()
        }
    }

    @Test
    fun `燃料切れで巻き戻った計算の途中で保存して戻すと、保存しなかった実行と同じ結果になる`() = timeout(limit) {
        val script = """
            print('rea' .. 'dy'); io.stdout:flush()
            local acc, parts, words = 7, {}, {}
            for i = 1, 1500000 do
                acc = (acc * 31 + i) % 1000003
                if i % 15000 == 0 then
                    parts[#parts + 1] = tostring(acc)
                    words[#words + 1] = string.format('%05x', acc):rep(2)
                end
            end
            table.sort(parts)
            table.sort(words, function(a, b) return a > b end)
            local s = ''
            for k = 1, #words do s = s .. words[k]:sub(1, 2) end
            print('acc', acc, #parts, parts[1], parts[#parts])
            print('s', #s, s:sub(1, 30))
            print('E' .. 'ND')
        """.trimIndent() + "\n"
        harness().use { h ->
            val expected = reference(h, script, fuelPerTick = SMALL_FUEL)
            assertTrue(expected.startsWith("ready\nacc\t"), expected)

            val fs = TestMemoryFileSystem()
            volumes(h, fs)
            val store = store()
            val calls = AtomicInteger()
            val m = h.machine(fuelPerTick = SMALL_FUEL)
            val s = start(m, fs, script, calls)
            // 計算の途中で燃料が尽き、燃料を待つ（tick は回さない）。葉の関数の中で尽きたときは巻き戻らず写しが取れないので、tick を 1 回進めて取り直す
            var written: WriteResult? = null
            eventuallyDescribe(60_000, { "燃料待ちで保存できない: ${output(m, s.output)}" }) {
                assertFalse(END in output(m, s.output), "保存する前に計算が終わった: ${output(m, s.output)}")
                if ("ready\n" in output(m, s.output) && m.fuelWaiterCount > 0 && m.isAtWaitPoint) {
                    val snapshot = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)
                    if (snapshot != null) {
                        assertEquals(ProcessState.RUNNABLE, m.stateOf(s.pid), "燃料待ちのプロセスを保存する")
                        written = store.write(store.assignNewId(owner)!!, owner, snapshot)
                        return@eventuallyDescribe true
                    }
                }
                h.scheduler.tick()
                // tick の燃料でワーカーが動く間に次の tick を回すと、燃料待ちを見ないまま先へ進んでしまう（遅い CI で計算が終わっていた）。燃料を使い切って待つまで待つ
                val deadline = System.nanoTime() + 500_000_000L
                while (m.fuelWaiterCount == 0 && END !in output(m, s.output) && System.nanoTime() < deadline) Thread.sleep(1)
                false
            }
            assertEquals("ready\n", output(m, s.output), "計算の途中で保存する")
            m.close()
            val r = restore(h, store, written!!.identity, calls, fuelPerTick = SMALL_FUEL)
            // 燃料は借りのことがあるので、tick を回し続ける
            tickUntil(h, r, s, "戻したプロセスが終わらない") { END in output(r, s.output) }
            assertEquals(expected, output(r, s.output))
            r.close()
        }
    }

    @Test
    fun `同じプロセスを何回も保存して戻すと、差分の世代から戻したものを含めて、毎回続きから動く`() = timeout(limit) {
        val script = """
            local t, co = {}, nil
            co = coroutine.create(function()
                local sum = 0
                while true do
                    local line = coroutine.yield(sum)
                    sum = sum + #line
                    t[#t + 1] = line:upper()
                end
            end)
            coroutine.resume(co)
            for round = 1, 4 do
                print('wait' .. round); io.stdout:flush()
                local line = io.read()
                local _, sum = coroutine.resume(co, line)
                print('got', round, sum, table.concat(t, ','))
            end
            print('E' .. 'ND')
        """.trimIndent() + "\n"
        harness().use { h ->
            val fs = TestMemoryFileSystem()
            volumes(h, fs)
            val store = store()
            val calls = AtomicInteger()
            val m = h.machine(fuelPerTick = FUEL)
            val s = start(m, fs, script, calls)
            tickUntil(h, m, s, "1 行目を待たない") { waiting(m, s, "wait1\n") }
            // 1 回目: 全体
            val g1 = save(store, m)
            assertFalse(g1.delta)
            m.close()
            val m2 = restore(h, store, g1.identity, calls)
            send(m2, s.input, "ab\n")
            tickUntil(h, m2, s, "2 行目を待たない") { waiting(m2, s, "wait2\n") }
            // 戻した直後のプロセスは基準がないので、次は全体（これが次の差分の基準になる）
            val g2 = save(store, m2, g1.identity)
            assertFalse(g2.delta, "戻した直後の保存は全体")
            send(m2, s.input, "cde\n")
            tickUntil(h, m2, s, "3 行目を待たない") { waiting(m2, s, "wait3\n") }
            // 2 回目: 差分（「戻って動いた」だけでは、全体に化けていても通るので、差分を書いたことを確かめる）
            val base = store.deltaBaseFor(g2.identity, owner)
            assertNotNull(base, "全体を書いた後なのに基準がない")
            val g3 = save(store, m2, g2.identity, base)
            assertTrue(g3.delta, "差分を書いていない")
            m2.close()
            val m3 = restore(h, store, g3.identity, calls)
            send(m3, s.input, "f\n")
            tickUntil(h, m3, s, "4 行目を待たない") { waiting(m3, s, "wait4\n") }
            // 3 回目: 差分から戻したプロセスをもう一度保存して戻す
            val g4 = save(store, m3, g3.identity)
            m3.close()
            val m4 = restore(h, store, g4.identity, calls)
            send(m4, s.input, "ghij\n")
            tickUntil(h, m4, s, "戻したプロセスが終わらない") { END in output(m4, s.output) }
            assertEquals(
                "wait1\ngot\t1\t2\tAB\nwait2\ngot\t2\t5\tAB,CDE\nwait3\ngot\t3\t6\tAB,CDE,F\nwait4\ngot\t4\t10\tAB,CDE,F,GHIJ\nEND\n",
                output(m4, s.output),
            )
            m4.close()
        }
    }

    /** ワールドのフォルダーを開いたもの（本番と同じ作り。開き直すと、メモリ上の状態は新しくなる） */
    private inner class World : AutoCloseable {
        val store = MachineSaveStore(dir.resolve("world-machines"), MachineIdIndex(), "test")
        val modules: ModuleStore = FileModuleStore(dir.resolve("world-modules"))

        override fun close() = Unit
    }

    /** 書いた世代に [pid] の `PROCESS_ABI` 節がある（写しの中で殺されていない） */
    private fun hasProcessAbi(store: MachineSaveStore, identity: MachineIdentity, pid: Int): Boolean =
        store.readGeneration(identity.id, identity.generation).snapshot.sections.any {
            it.kind == SnapshotSection.PROCESS_ABI && SnapshotReader(it.payload).i32() == pid
        }

    @Test
    fun `保存した側のスケジューラと置き場を閉じて開き直して戻すと、parallelのコルーチンが続き、戻した直後にまた保存できる`() = timeout(limit) {
        val script = """
            os.queueEvent('open'); os.pullEvent('open')
            local log = {}
            parallel.waitForAll(
                function()
                    for i = 1, 3 do os.sleep(0.2); log[#log + 1] = 's' .. i end
                end,
                function()
                    print('rea' .. 'dy'); io.stdout:flush()
                    local e = os.pullEvent('restored'); log[#log + 1] = e
                    local line = io.read(); log[#log + 1] = line
                end
            )
            table.sort(log)
            print('log', table.concat(log, ','))
            print('E' .. 'ND')
        """.trimIndent() + "\n"
        val fs = TestMemoryFileSystem()
        val calls = AtomicInteger()
        lateinit var started: Started
        val identity = World().use { w ->
            harness(w.modules).use { h ->
                volumes(h, fs)
                val m = h.machine(fuelPerTick = FUEL)
                started = start(m, fs, script, calls)
                tickUntil(h, m, started, "待たない") { waiting(m, started, "ready\n") }
                save(w.store, m).identity.also { m.close() }
            }
        }
        val s = started
        World().use { w ->
            harness(w.modules).use { h ->
                volumes(h, fs)
                val r = restore(h, w.store, identity, calls)
                // 戻した直後（restored を受けて、また待つようになったところ）にまた写しが取れ、Lua は写しの中で殺されない
                // （置き場が、作るときに見つけたモジュールを知っている。#82）
                tickUntil(h, r, s, "戻した後に待たない") { waiting(r, s, "ready\n") }
                val again = save(w.store, r, identity)
                assertTrue(hasProcessAbi(w.store, again.identity, s.pid), "戻した直後の保存で Lua が殺された")
                send(r, s.input, "line\n")
                tickUntil(h, r, s, "戻したプロセスが終わらない") { END in output(r, s.output) }
                assertEquals("ready\nlog\tline,restored,s1,s2,s3\nEND\n", output(r, s.output))
                r.close()
            }
        }
    }

    private companion object {
        const val END = "END\n"
        const val FUEL = 10_000_000L

        /** 燃料切れを起こすための小さな 1 tick の燃料（lua.wasm の起動に何 tick もかからない程度） */
        const val SMALL_FUEL = 1_000_000L
    }
}
