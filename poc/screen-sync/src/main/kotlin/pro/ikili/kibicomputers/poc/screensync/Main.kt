package pro.ikili.kibicomputers.poc.screensync

import kotlin.random.Random

/** 1つの組み合わせ（シナリオ・解像度・方式）の測定結果。時間はすべて 1 tick あたりの平均。 */
data class Result(
    val scenario: Scenario,
    val width: Int,
    val height: Int,
    val method: Method,
    val payloadAvg: Double,
    val wireAvg: Double,
    val wireMax: Int,
    val drawMicros: Double,
    val encodeMicros: Double,
    val decodeMicros: Double,
    val extraMemory: Int,
    val consistent: Boolean,
)

fun newScreen(text: Boolean, w: Int, h: Int): Screen = if (text) TextScreen(w, h) else PixelScreen(w, h)

/**
 * サーバーとクライアントの画面を1組作り、[warmup] + [ticks] tick 回す。
 * 測るのは後半の [ticks] tick。最後にクライアントの画面がサーバーと同じかを確かめる。
 */
fun run(scenario: Scenario, w: Int, h: Int, method: Method, warmup: Int = 20, ticks: Int = 60): Result {
    val server = newScreen(scenario.text, w, h)
    val client = newScreen(scenario.text, w, h)
    val sender = method.sender(server)
    val receiver = method.receiver(client)
    val rnd = Random(31)
    var payload = 0L
    var wire = 0L
    var wireMax = 0
    var draw = 0L
    var enc = 0L
    var dec = 0L
    for (t in 0 until warmup + ticks) {
        val t0 = System.nanoTime()
        scenario.tick(server, t, rnd)
        val t1 = System.nanoTime()
        val packet = sender.encode()
        val t2 = System.nanoTime()
        if (packet != null) receiver.receive(packet)
        val t3 = System.nanoTime()
        if (t >= warmup) {
            draw += t1 - t0
            enc += t2 - t1
            dec += t3 - t2
            if (packet != null) {
                payload += packet.size
                val ws = Zip.wireSize(packet)
                wire += ws
                wireMax = maxOf(wireMax, ws)
            }
        }
    }
    return Result(
        scenario, w, h, method,
        payloadAvg = payload.toDouble() / ticks,
        wireAvg = wire.toDouble() / ticks,
        wireMax = wireMax,
        drawMicros = draw / 1000.0 / ticks,
        encodeMicros = enc / 1000.0 / ticks,
        decodeMicros = dec / 1000.0 / ticks,
        extraMemory = sender.extraMemory,
        consistent = server.sameAs(client),
    )
}

/** 測る解像度（仮の値。確定は #20）。最後のものは大型モニターを想定した大きさ。 */
val textSizes = listOf(50 to 16, 80 to 25, 160 to 50, 320 to 100)
val pixelSizes = listOf(160 to 100, 320 to 200, 640 to 400, 1280 to 800)

private fun fmtBytes(v: Double): String = when {
    v >= 1_000_000 -> "%.2f MB".format(v / 1_000_000)
    v >= 1_000 -> "%.1f KB".format(v / 1_000)
    else -> "%.0f B".format(v)
}

private fun fmtMicros(v: Double): String = if (v >= 1000) "%.1f ms".format(v / 1000) else "%.0f µs".format(v)

fun main(args: Array<String>) {
    val filter = args.firstOrNull()
    println("# 画面の同期方式の測定結果（#31）")
    println()
    println("- Java ${System.getProperty("java.version")} / ${System.getProperty("os.name")} / ${Runtime.getRuntime().availableProcessors()} スレッド")
    println("- 送信量は Minecraft のパケット圧縮（256 バイト以上を zlib）を通したあとの大きさ。KB/s は 20 tick/秒での値")
    println("- 描画・エンコード・デコードは 1 tick あたりの平均（ウォームアップ 20 tick の後の 60 tick）")
    println()
    for (scenario in Scenario.all) {
        if (filter != null && !scenario.name.contains(filter)) continue
        println("## ${scenario.name}（${if (scenario.text) "文字" else "ピクセル"}）")
        println()
        println("| 解像度 | 方式 | 送信/tick 平均 | 最大 | 送信/秒 | 圧縮前/tick | 描画 | エンコード（サーバー） | デコード（クライアント） | 追加メモリ |")
        println("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for ((w, h) in if (scenario.text) textSizes else pixelSizes) {
            for (method in Method.entries) {
                val r = run(scenario, w, h, method)
                check(r.consistent) { "${scenario.name} ${w}x$h ${method.label}: クライアントの画面が一致しない" }
                println(
                    "| ${w}x$h | ${method.label} | ${fmtBytes(r.wireAvg)} | ${fmtBytes(r.wireMax.toDouble())} | " +
                        "${fmtBytes(r.wireAvg * 20)} | ${fmtBytes(r.payloadAvg)} | ${fmtMicros(r.drawMicros)} | " +
                        "${fmtMicros(r.encodeMicros)} | ${fmtMicros(r.decodeMicros)} | ${fmtBytes(r.extraMemory.toDouble())} |",
                )
            }
        }
        println()
    }
}
