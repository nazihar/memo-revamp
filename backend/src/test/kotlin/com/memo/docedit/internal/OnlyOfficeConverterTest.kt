package com.memo.docedit.internal

import com.memo.files.FileType
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ONLYOFFICE conversion client (ADR-0045) against a stand-in server: the file goes once in a multipart request with a
 * token that carries `operation: converter`; polls repeat the key without it; the PDF comes back as the body; error codes
 * map to final or retryable outcomes.
 */
class OnlyOfficeConverterTest {
    private val json = JsonMapper.builder().build()
    private val secret = "a".repeat(40)
    private val requests = CopyOnWriteArrayList<String>()
    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun converter(answers: List<Pair<String, ByteArray>>): OnlyOfficeConverter {
        val queue = answers.toMutableList()
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/converter/from-file") { exchange ->
                    requests += exchange.requestBody.readAllBytes().toString(Charsets.ISO_8859_1)
                    val (type, body) = queue.removeFirst()
                    exchange.responseHeaders.add("Content-Type", type)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        val properties = DocEditProperties(documentServerUrl = "http://127.0.0.1:${server!!.address.port}", jwtSecret = secret)
        return OnlyOfficeConverter(properties, json, Clock.systemUTC())
    }

    private val input = RenditionInput("budget.docx", FileType.DOCX, 5) { "hello".byteInputStream() }

    @Test
    fun `sends the file once with a converter token, polls without it, and writes the PDF`() {
        val pdf = "%PDF-1.7 test".toByteArray()
        val converter =
            converter(
                listOf(
                    "application/json" to """{"endConvert":false,"percent":40}""".toByteArray(),
                    "application/pdf" to pdf,
                ),
            )
        val out = ByteArrayOutputStream()
        assertThat(converter.toPdf(input, "r1-key", out)).isEqualTo(RenditionOutcome.Written())
        assertThat(out.toByteArray()).isEqualTo(pdf)

        assertThat(requests).hasSize(2)
        assertThat(requests[0])
            .contains("name=\"file\"")
            .contains("hello")
            .contains("name=\"params\"")
            .contains("\"key\":\"r1-key\"")
        assertThat(requests[1]).doesNotContain("name=\"file\"").contains("\"key\":\"r1-key\"")
        val token = Regex("name=\"token\"\r\n\r\n([^\r]+)").find(requests[0])!!.groupValues[1]
        val claims = Hs256(secret, json, Clock.systemUTC()).verify(token)
        assertThat(claims["operation"]).isEqualTo("converter")
        assertThat(claims["outputtype"]).isEqualTo("pdf")
        assertThat(claims).doesNotContainKey("url")
    }

    @Test
    fun `a size or timeout error is final, an unknown one is retried`() {
        assertThat(converter(listOf("application/json" to """{"error":-10}""".toByteArray())).toPdf(input, "k", ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Final("SIZE"))
        stop()
        assertThat(converter(listOf("application/json" to """{"error":-4}""".toByteArray())).toPdf(input, "k", ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Retry("CONVERTER_M4"))
        assertThat(OnlyOfficeConverter.outcomeOf(-2)).isEqualTo(RenditionOutcome.Final("TIMEOUT"))
        assertThat(OnlyOfficeConverter.outcomeOf(-8)).isEqualTo(RenditionOutcome.Final("TOKEN"))
    }

    @Test
    fun `an unreachable server is retried`() {
        val properties = DocEditProperties(documentServerUrl = "http://127.0.0.1:1", jwtSecret = secret)
        assertThat(OnlyOfficeConverter(properties, json, Clock.systemUTC()).toPdf(input, "k", ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Retry("UNREACHABLE"))
    }

    @Test
    fun `a type it cannot convert is not sent`() {
        val image = RenditionInput("photo.png", FileType.PNG, 1) { ByteArray(1).inputStream() }
        val properties = DocEditProperties(documentServerUrl = "http://127.0.0.1:1", jwtSecret = secret)
        assertThat(OnlyOfficeConverter(properties, json, Clock.systemUTC()).toPdf(image, "k", ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Final("UNSUPPORTED"))
    }
}
