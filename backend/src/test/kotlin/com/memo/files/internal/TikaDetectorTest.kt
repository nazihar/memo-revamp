package com.memo.files.internal

import com.memo.files.FileType
import com.memo.testsupport.repoRoot
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * The OLE2 subtype through the Tika server (ADR-0055, test plan E06 T2 `TikaSubtypeTest`) against a stand-in server:
 * the content goes in a `PUT /detect`; Word, Excel and PowerPoint are allowed; anything else is refused by name; a
 * server that is missing, unreachable or failing refuses the upload as unavailable (fail closed, 503).
 */
class TikaDetectorTest {
    private var server: HttpServer? = null
    private var seenMethod: String? = null
    private var seenBody: String? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun detector(
        status: Int,
        answer: String = "",
    ): TikaDetector {
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/detect") { exchange ->
                    seenMethod = exchange.requestMethod
                    seenBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val bytes = answer.toByteArray()
                    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        return TikaDetector(FilesProperties(tikaUrl = "http://127.0.0.1:${server!!.address.port}/"))
    }

    private fun rejection(block: () -> Any): UploadRejected = catchThrowableOfType(UploadRejected::class.java) { block() }

    @ParameterizedTest
    @CsvSource(
        "application/msword, DOC",
        "application/vnd.ms-excel, XLS",
        "application/vnd.ms-powerpoint, PPT",
        "'APPLICATION/MSWORD; charset=x', DOC",
    )
    fun `Word, Excel and PowerPoint are named from the server's answer`(
        answer: String,
        type: FileType,
    ) {
        assertThat(detector(200, "$answer\n").ole2Type { "content".byteInputStream() }).isEqualTo(type)
        assertThat(seenMethod).isEqualTo("PUT")
        assertThat(seenBody).isEqualTo("content")
    }

    @Test
    fun `an Outlook message or any other container is not allowed, and an encrypted OOXML file is encrypted`() {
        val msg = rejection { detector(200, "application/vnd.ms-outlook").ole2Type { "m".byteInputStream() } }
        assertThat(msg.reason).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
        assertThat(msg.detail["detectedType"]).isEqualTo("application/vnd.ms-outlook")
        stop()
        assertThat(rejection { detector(200, "application/x-tika-msoffice").ole2Type { "m".byteInputStream() } }.reason)
            .isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
        stop()
        assertThat(rejection { detector(200, "application/x-tika-ooxml-protected").ole2Type { "m".byteInputStream() } }.reason)
            .isEqualTo(RejectionReason.ENCRYPTED)
        // Only the OLE2 documents come from the server: RTF and TIFF are named by the in-process detector, never here.
        assertThat(rejection { TikaDetector.typeOf("application/rtf") }.reason).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
    }

    @Test
    fun `an unconfigured, unreachable or failing server fails closed as unavailable`() {
        val missing = rejection { TikaDetector(FilesProperties(tikaUrl = "")).ole2Type { "m".byteInputStream() } }
        assertThat(missing.reason).isEqualTo(RejectionReason.SCAN_UNAVAILABLE)
        assertThat(rejection { TikaDetector(FilesProperties(tikaUrl = "http://127.0.0.1:1")).ole2Type { "m".byteInputStream() } }.reason)
            .isEqualTo(RejectionReason.SCAN_UNAVAILABLE)
        assertThat(rejection { detector(503).ole2Type { "m".byteInputStream() } }.reason).isEqualTo(RejectionReason.SCAN_UNAVAILABLE)
        stop()
        assertThat(rejection { detector(429).ole2Type { "m".byteInputStream() } }.reason).isEqualTo(RejectionReason.SCAN_UNAVAILABLE)
        stop()
        assertThat(rejection { detector(422).ole2Type { "m".byteInputStream() } }.reason).isEqualTo(RejectionReason.MALFORMED)
    }

    @Test
    fun `every Tika server config serves detection beside text`() {
        // T1 spike: Tika 4.2 answers PUT /detect only when the config lists the endpoint.
        val config = JsonMapper.builder().build().readTree(Files.readString(repoRoot.resolve("deploy/tika/tika-config.json")))
        val endpoints = config["server"]["endpoints"]
        assertThat((0 until endpoints.size()).map { endpoints[it].asString() }).contains("tika", "detect")
    }
}
