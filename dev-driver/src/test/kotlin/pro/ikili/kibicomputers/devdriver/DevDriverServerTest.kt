package pro.ikili.kibicomputers.devdriver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DevDriverServerTest {
    @Test
    fun parsesRequestLineQueryAndBody() {
        val body = "{\"text\":\"あ\"}"
        // Content-Length はバイト数（「あ」は UTF-8 で3バイト）
        val length = body.toByteArray(StandardCharsets.UTF_8).size
        val raw = "POST /type?x=1&name=a%20b HTTP/1.1\r\nHost: localhost\r\nContent-Length: $length\r\n\r\n$body"
        val request = DevDriverServer.readRequest(ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))!!
        assertEquals("POST", request.method)
        assertEquals("/type", request.path)
        assertEquals(mapOf("x" to "1", "name" to "a b"), request.query)
        assertEquals(body, request.body)
    }

    @Test
    fun servesOverLoopbackAndWritesPortFile(@TempDir dir: Path) {
        val portFile = dir.resolve("dev-driver.port")
        val server = DevDriverServer(0, portFile) { req ->
            DevResponse.ok("{\"path\":${jsonString(req.path)},\"body\":${jsonString(req.body)}}")
        }
        val port = server.start()
        try {
            assertEquals(port.toString(), Files.readString(portFile))
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/echo"))
                    .POST(HttpRequest.BodyPublishers.ofString("こんにちは"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertEquals("{\"path\":\"/echo\",\"body\":\"こんにちは\"}", response.body())
        } finally {
            server.stop()
        }
    }

    @Test
    fun escapesJsonStrings() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", jsonString("a\"b\\c\n\u0001"))
    }
}
