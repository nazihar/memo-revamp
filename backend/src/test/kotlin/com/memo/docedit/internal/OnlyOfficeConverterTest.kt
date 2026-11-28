package com.memo.docedit.internal

import com.memo.files.FileType
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.testsupport.CachingDocumentServer
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
            .contains("\"key\":\"r1-key.")
        // The polls of one call repeat its key (the caller's key with this call's suffix).
        val key = Regex("\"key\":\"([^\"]+)\"")
        assertThat(requests[1]).doesNotContain("name=\"file\"")
        assertThat(key.find(requests[1])!!.groupValues[1]).isEqualTo(key.find(requests[0])!!.groupValues[1])
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
    fun `legacy Office files and RTF are converted under their own names, TIFF scans are not`() {
        // ADR-0055 and the E06 T1 spike: ONLYOFFICE converts doc, xls, ppt and rtf; it refuses TIFF (-7).
        val properties = DocEditProperties(documentServerUrl = "http://127.0.0.1:1", jwtSecret = secret)
        val types = OnlyOfficeConverter(properties, json, Clock.systemUTC()).pdfTypes
        assertThat(types).contains(FileType.DOC, FileType.XLS, FileType.PPT, FileType.RTF).doesNotContain(FileType.TIFF)

        val pdf = "%PDF-1.7 test".toByteArray()
        val legacy = RenditionInput("minutes.doc", FileType.DOC, 5) { "hello".byteInputStream() }
        assertThat(converter(listOf("application/pdf" to pdf)).toPdf(legacy, "r1-doc", ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Written())
        assertThat(requests.single()).contains("\"filetype\":\"doc\"")
        val tiff = RenditionInput("scan.tiff", FileType.TIFF, 1) { ByteArray(1).inputStream() }
        assertThat(OnlyOfficeConverter(properties, json, Clock.systemUTC()).toPdf(tiff, "k", ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Final("UNSUPPORTED"))
    }

    @Test
    fun `a type it cannot convert is not sent`() {
        val image = RenditionInput("photo.png", FileType.PNG, 1) { ByteArray(1).inputStream() }
        val properties = DocEditProperties(documentServerUrl = "http://127.0.0.1:1", jwtSecret = secret)
        assertThat(OnlyOfficeConverter(properties, json, Clock.systemUTC()).toPdf(image, "k", ByteArrayOutputStream()))
            .isEqualTo(RenditionOutcome.Final("UNSUPPORTED"))
    }
}

/**
 * The key the client sends (E06 T12): ONLYOFFICE keeps each conversion's answer by its key, failures included, so every
 * call is sent under the caller's key with a fresh suffix; the polls of one call repeat it.
 */
class OnlyOfficeConverterKeyTest {
    private val pdf = "%PDF-1.7 test".toByteArray()
    private val input = RenditionInput("minutes-copy.docx", FileType.DOCX, 5) { "hello".byteInputStream() }

    @Test
    fun `a call after a failure the document server kept is converted under a fresh key`() {
        CachingDocumentServer { _, _, _ -> pdf }.use { server ->
            val converter = server.converter()
            server.down = true
            assertThat(converter.toPdf(input, "minutes-copy-0193", ByteArrayOutputStream()))
                .isEqualTo(RenditionOutcome.Retry("CONVERTER_M3"))
            // The same caller key under ONLYOFFICE's cache would answer -3 again, at once.
            server.down = false
            val out = ByteArrayOutputStream()
            assertThat(converter.toPdf(input, "minutes-copy-0193", out)).isEqualTo(RenditionOutcome.Written())
            assertThat(out.toByteArray()).isEqualTo(pdf)
            assertThat(converter.toPdfA(input, "minutes-copy-0193", ByteArrayOutputStream())).isEqualTo(RenditionOutcome.Written())

            assertThat(server.keys).hasSize(3).doesNotHaveDuplicates().allSatisfy { assertThat(it).startsWith("minutes-copy-0193.") }
            assertThat(server.conversions).isEqualTo(2)
        }
    }

    @Test
    fun `the keys are ones ONLYOFFICE accepts, a caller's key outside its rules hashed`() {
        CachingDocumentServer { _, _, _ -> pdf }.use { server ->
            val converter = server.converter()
            val long = "minutes-record:" + "x".repeat(200)
            listOf("record-0193-1", "rendition:0193", long, "").forEach { converter.toPdf(input, it, ByteArrayOutputStream()) }
            assertThat(server.keys).hasSize(4).doesNotHaveDuplicates().allSatisfy {
                assertThat(it).hasSizeLessThanOrEqualTo(128).matches("[0-9A-Za-z._=-]+")
            }
            assertThat(server.keys[0]).startsWith("record-0193-1.")
            assertThat(server.keys[1]).doesNotContain(":")
            assertThat(server.keys[2]).doesNotContain("xxxxxxxx")
        }
        assertThat(OnlyOfficeConverter.serverKey("x".repeat(95), "n")).isEqualTo("x".repeat(95) + ".n")
        assertThat(OnlyOfficeConverter.serverKey("x".repeat(96), "0".repeat(40))).hasSize(41 + 1 + 32).startsWith("h")
    }
}
