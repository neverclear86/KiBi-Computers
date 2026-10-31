package pro.ikili.kibicomputers.poc.waitpointsave

/**
 * 測定とテストのシナリオ（lua_host に読ませる Lua のスクリプト）。
 *
 * `kibi.wait()` は C 関数から `lua_yield` するので、届くのはそのコルーチンを resume した直近の相手までになる。
 * そこで、入れ子のコルーチンが待つときは、メインチャンク（と `coroutine.wrap` の呼び出し元）が
 * `kibi.WAIT` を受け取って、自分から `kibi.wait()` で待ちを出し直す（#30 の回答の選択肢 A）。
 * ホストに見えるのは、常にメインチャンクの待ちだけになる。
 */
object Scripts {
    /** シナリオ 2: 待つだけ。"block" が来たらホスト関数の中で待つ（ブロッキング型の例） */
    val IDLE: String = """
        local n = 0
        while true do
          local e = kibi.wait()
          n = n + 1
          if e == "block" then
            print("block ->", kibi.block(41))
          else
            print("event " .. n .. ": " .. e)
          end
        end
    """.trimIndent()

    /** シナリオ 3: [megabytes] MB 相当の数（乱数）をテーブルに持って待つ（配列の要素は 16 バイト） */
    fun data(megabytes: Int): String = """
        local t = {}
        for i = 1, ${megabytes * 1024 * 1024 / 16} do t[i] = math.random() end
        print("filled " .. #t)
        while true do
          local e = kibi.wait()
          local i = 1 + (tonumber(e) or 0) % #t
          print(e, #t, t[i])
          t[i] = -t[i]
        end
    """.trimIndent()

    /**
     * シナリオ 4: [n] 個の子のコルーチンが、それぞれ `kibi.wait()` の中で止まっている。
     * イベント "<番号>:<値>" はその子に、"all:<値>" は全部の子に渡す。
     */
    fun coroutines(n: Int): String = """
        local WAIT = kibi.WAIT
        local N = $n
        local children = {}
        for i = 1, N do
          local co = coroutine.create(function()
            local acc, count = i, 0
            while true do
              local e = kibi.wait()   -- 子の中の待ち（スケジューラーに kibi.WAIT が返る）
              count = count + 1
              acc = (acc * 31 + tonumber(e)) % 1000000007
              print(("co %d acc=%d n=%d"):format(i, acc, count))
            end
          end)
          local ok, r = coroutine.resume(co)
          assert(ok and r == WAIT, "child did not wait")
          children[i] = co
        end
        print("spawned " .. N)
        while true do
          local e = kibi.wait()       -- 子の待ちを、メインチャンクから出し直す
          local target, value = e:match("^(%w+):(.*)$")
          local from, to = 1, N
          if target ~= "all" then from = tonumber(target); to = from end
          for i = from, to do
            local ok, r = coroutine.resume(children[i], value)
            if not ok then error(r, 0) end
            assert(r == WAIT, "child did not wait again")
          end
        end
    """.trimIndent()

    /**
     * シナリオ 5: `pcall` の中、`coroutine.wrap` の入れ子の中で `kibi.wait()` する。
     * "boom" を渡すと、`pcall` の中でエラーになる（復元後の longjmp も確かめる）。
     */
    val NESTED: String = """
        local WAIT = kibi.WAIT
        -- 入れ子の中の kibi.wait() で kibi.WAIT が返ってきたら、外へ待ちを出し直して、届いたイベントを中へ渡す
        local function relay(f, ...)
          local r = table.pack(f(...))
          while r[1] == WAIT do
            r = table.pack(f(kibi.wait()))
          end
          return table.unpack(r, 1, r.n)
        end

        local inner = coroutine.wrap(function(prefix)
          local results = {}
          for round = 1, 3 do
            local ok, v = pcall(function()
              local name = prefix .. round
              local e = kibi.wait()
              if e == "boom" then error("boom at " .. name, 0) end
              return name .. "=" .. e
            end)
            results[#results + 1] = (ok and "ok " or "err ") .. tostring(v)
            print("inner", round, results[#results])
          end
          return table.concat(results, ";"), #results
        end)

        local outer = coroutine.wrap(function()
          local r, count = relay(inner, "p")
          print("inner returned", r, count)
          local ok, v = pcall(function()
            local x = kibi.wait()
            return "outer pcall " .. x
          end)
          print("outer", ok, v)
          return "outer done"
        end)

        print("main got", relay(outer))
        while true do
          local e = kibi.wait()
          print("main", e)
        end
    """.trimIndent()

    /** [NESTED] に渡すイベントと、最後に出るはずの出力 */
    val NESTED_EVENTS: List<String> = listOf("a", "boom", "c", "x", "after")
    val NESTED_OUTPUT: String = """
        inner	1	ok p1=a
        inner	2	err boom at p2
        inner	3	ok p3=c
        inner returned	ok p1=a;err boom at p2;ok p3=c	3
        outer	true	outer pcall x
        main got	outer done
        main	after
    """.trimIndent() + "\n"
}

/** waiter のイベントの種類（`waiter.c` と同じ値） */
object WaiterEvents {
    const val PUSH = 0
    const val ALLOC = 1
    const val REPORT = 2
    const val BLOCK = 3
}
