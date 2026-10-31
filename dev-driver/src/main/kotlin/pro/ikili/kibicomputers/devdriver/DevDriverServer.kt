package pro.ikili.kibicomputers.devdriver

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 1件のリクエスト。 */
class DevRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val body: String,
)

/** 1件のレスポンス。本文は JSON の文字列。 */
class DevResponse(val status: Int, val json: String) {
    companion object {
        fun ok(json: String): DevResponse = DevResponse(200, json)
        fun error(status: Int, message: String): DevResponse = DevResponse(status, "{\"error\":${jsonString(message)}}")
    }
}

fun interface DevHandler {
    fun handle(request: DevRequest): DevResponse
}

/**
 * localhost だけで待ち受ける、最小限の HTTP サーバー。
 *
 * JDK の `com.sun.net.httpserver` はモジュールの都合で MOD から使えないことがあるため、
 * ソケットで直接 HTTP/1.1 を話す（1リクエストごとに接続を閉じる）。
 * ポートに 0 を渡すと空いているポートを使い、実際のポートを [portFile] に書く。
 */
class DevDriverServer(
    private val port: Int,
    private val portFile: Path?,
    private val handler: DevHandler,
) {
    private var socket: ServerSocket? = null

    /** 待ち受けを始め、実際のポート番号を返す。 */
    fun start(): Int {
        // IPv6 が優先される環境でも 127.0.0.1 で届くよう、IPv4 のループバックに固定する
        val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        socket = server
        val actual = server.localPort
        portFile?.let {
            Files.createDirectories(it.toAbsolutePath().parent)
            Files.writeString(it, actual.toString())
            // 正常終了したときはポートのファイルを消す（強制終了では残るので、呼ぶ側は接続失敗も考える）
            Runtime.getRuntime().addShutdownHook(Thread { Files.deleteIfExists(it) })
        }
        val thread = Thread({ acceptLoop(server) }, "KiBi-DevDriver")
        thread.isDaemon = true
        thread.start()
        return actual
    }

    fun stop() {
        socket?.close()
        socket = null
        portFile?.let { Files.deleteIfExists(it) }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (server.isClosed) return
                continue
            }
            // 時間のかかる操作（ワールドの読み込み中など）で、ほかのリクエストを止めないよう接続ごとにスレッドを分ける
            val worker = Thread({ client.use { serve(it) } }, "KiBi-DevDriver-Request")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun serve(client: Socket) {
        val response = try {
            val request = readRequest(BufferedInputStream(client.getInputStream()))
            if (request == null) {
                DevResponse.error(400, "bad request")
            } else {
                handler.handle(request)
            }
        } catch (e: Exception) {
            DevResponse.error(500, "${e.javaClass.simpleName}: ${e.message}")
        }
        val body = response.json.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "Error"
    }

    companion object {
        /** リクエストを読む。テストから使えるように公開している。 */
        fun readRequest(input: InputStream): DevRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toInt()
                }
            }
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
            return DevRequest(parts[0].uppercase(), path, query, String(body, 0, read, StandardCharsets.UTF_8))
        }

        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8)
                if (b == '\n'.code) break
                if (b != '\r'.code) buffer.write(b)
            }
            return buffer.toString(StandardCharsets.UTF_8)
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate {
                val eq = it.indexOf('=')
                val key = if (eq >= 0) it.substring(0, eq) else it
                val value = if (eq >= 0) it.substring(eq + 1) else ""
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
    }
}

/** 文字列を JSON の文字列リテラルにする。 */
fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}
