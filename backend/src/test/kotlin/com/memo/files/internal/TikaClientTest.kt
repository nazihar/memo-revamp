package com.memo.files.internal

import com.memo.files.FileType
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

/**
 * The Tika client (ADR-0045) against a stand-in server: the bytes go in a PUT with their type, the text comes back cut at
 * the limit and marked, and the server's answers map to final or retryable outcomes.
 */
class TikaClientTest {
    private var server: HttpServer? = null
    private var seenType: String? = null
    private var seenBody: String? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun client(
        status: Int,
        body: String = "",
    ): TikaClient {
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/tika/text") { exchange ->
                    seenType = exchange.requestHeaders.getFirst("Content-Type")
                    seenBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        return TikaClient(FilesProperties(tikaUrl = "http://127.0.0.1:${server!!.address.port}"))
    }

    private val input = RenditionInput("budget.docx", FileType.DOCX, 5) { "bytes".byteInputStream() }

    @Test
    fun `sends the bytes with their type and writes the text`() {
        val out = ByteArrayOutputStream()
        assertThat(client(200, "Budget for 2027 — draft").text(input, 1000, out)).isEqualTo(RenditionOutcome.Written(false))
        assertThat(out.toString(Charsets.UTF_8)).isEqualTo("Budget for 2027 — draft")
        assertThat(seenType).isEqualTo(FileType.DOCX.mediaType)
        assertThat(seenBody).isEqualTo("bytes")
    }

    @Test
    fun `keeps at most the limit and says it cut`() {
        val out = ByteArrayOutputStream()
        assertThat(client(200, "x".repeat(20_000)).text(input, 10_000, out)).isEqualTo(RenditionOutcome.Written(true))
        assertThat(out.toString(Charsets.UTF_8)).hasSize(10_000)
        stop()
        val exact = ByteArrayOutputStream()
        assertThat(client(200, "y".repeat(10)).text(input, 10, exact)).isEqualTo(RenditionOutcome.Written(false))
    }

    @Test
    fun `malformed and too large are final, busy and crashed forks are retried`() {
        assertThat(client(422).text(input, 10, ByteArrayOutputStream())).isEqualTo(RenditionOutcome.Final("MALFORMED"))
        stop()
        assertThat(client(413).text(input, 10, ByteArrayOutputStream())).isEqualTo(RenditionOutcome.Final("SIZE"))
        stop()
        assertThat(client(429).text(input, 10, ByteArrayOutputStream())).isEqualTo(RenditionOutcome.Retry("HTTP_429"))
        stop()
        assertThat(client(503).text(input, 10, ByteArrayOutputStream())).isEqualTo(RenditionOutcome.Retry("HTTP_503"))
    }

    @Test
    fun `an unreachable server is retried`() {
        assertThat(TikaClient(FilesProperties(tikaUrl = "http://127.0.0.1:1")).text(input, 10, ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Retry("UNREACHABLE"))
    }
}
