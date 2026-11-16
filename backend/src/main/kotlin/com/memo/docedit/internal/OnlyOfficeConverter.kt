package com.memo.docedit.internal

import com.memo.files.FileType
import com.memo.files.RenditionConverter
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * PDF renditions through ONLYOFFICE's conversion API (ADR-0045, research §1.1): `POST /converter/from-file`, async with a
 * key, the bytes sent by us over the internal network (the document server fetches nothing), the parameters in a token
 * with `operation: converter` (required since 10.0). Polls the same key until the PDF comes back or the deadline passes.
 * Present only when a document server is configured.
 */
@Component
@ConditionalOnExpression("!'\${memo.docedit.document-server-url:}'.isBlank()")
class OnlyOfficeConverter(
    private val properties: DocEditProperties,
    private val json: JsonMapper,
    private val clock: Clock,
) : RenditionConverter {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jwt by lazy { Hs256(properties.jwtSecret, json, clock) }
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    override val pdfTypes: Set<FileType> = FILE_TYPES.keys

    override fun toPdf(
        input: RenditionInput,
        key: String,
        out: OutputStream,
    ): RenditionOutcome = convert(input, key, out, "pdf")

    /** The conversion API's `pdfa` output (listed for docx, xlsx, pptx and the ODF types). */
    override fun toPdfA(
        input: RenditionInput,
        key: String,
        out: OutputStream,
    ): RenditionOutcome = convert(input, key, out, "pdfa")

    private fun convert(
        input: RenditionInput,
        key: String,
        out: OutputStream,
        outputType: String,
    ): RenditionOutcome {
        val fileType = fileTypeOf(input) ?: return RenditionOutcome.Final("UNSUPPORTED")
        val params =
            mapOf(
                "async" to true,
                "filetype" to fileType,
                "outputtype" to outputType,
                "key" to key,
                "title" to "document.$fileType",
            )
        val deadline = clock.instant().plus(DEADLINE)
        // The first request carries the file; the polls repeat the same key without it.
        var outcome = poll(params, input, out)
        while (outcome == null && !clock.instant().isAfter(deadline)) {
            Thread.sleep(POLL.toMillis())
            outcome = poll(params, null, out)
        }
        return outcome ?: RenditionOutcome.Retry("SLOW")
    }

    /** One request: its outcome, or null while the conversion is still running. */
    private fun poll(
        params: Map<String, Any?>,
        input: RenditionInput?,
        out: OutputStream,
    ): RenditionOutcome? =
        try {
            read(send(params, input), out)
        } catch (e: IOException) {
            log.warn("Conversion request failed: {}", e.javaClass.simpleName)
            RenditionOutcome.Retry("UNREACHABLE")
        }

    /** The PDF written (an outcome), a final or retryable error, or null while the conversion is still running. */
    private fun read(
        answer: HttpResponse<java.io.InputStream>,
        out: OutputStream,
    ): RenditionOutcome? {
        val status = answer.statusCode()
        val type = answer.headers().firstValue("Content-Type").orElse("")
        answer.body().use { body ->
            return when {
                status == HTTP_TOO_LARGE -> {
                    RenditionOutcome.Final("SIZE")
                }

                status != HTTP_OK -> {
                    RenditionOutcome.Retry("HTTP_$status")
                }

                type.startsWith("application/json") -> {
                    @Suppress("UNCHECKED_CAST")
                    val result = json.readValue(body, Map::class.java) as Map<String, Any?>
                    val error = (result["error"] as? Number)?.toInt()
                    when {
                        error != null -> outcomeOf(error)
                        result["endConvert"] == true -> RenditionOutcome.Retry("NO_RESULT")
                        else -> null
                    }
                }

                else -> {
                    body.copyTo(out)
                    RenditionOutcome.Written()
                }
            }
        }
    }

    private fun send(
        params: Map<String, Any?>,
        input: RenditionInput?,
    ): HttpResponse<java.io.InputStream> {
        val token = jwt.sign(params + ("operation" to "converter") + ("exp" to clock.instant().plus(TOKEN_LIFETIME).epochSecond))
        val boundary = "memo-${UUID.randomUUID()}"
        val parts = mutableListOf<HttpRequest.BodyPublisher>()

        fun text(
            name: String,
            value: String,
        ) {
            parts += HttpRequest.BodyPublishers.ofString("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        if (input != null) {
            parts +=
                HttpRequest.BodyPublishers.ofString(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"document\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n",
                )
            parts += HttpRequest.BodyPublishers.ofInputStream { input.open() }
            parts += HttpRequest.BodyPublishers.ofString("\r\n")
        }
        text("params", json.writeValueAsString(params))
        text("token", token)
        parts += HttpRequest.BodyPublishers.ofString("--$boundary--\r\n")
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.documentServerUrl.trimEnd('/') + "/converter/from-file"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.concat(*parts.toTypedArray()))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun fileTypeOf(input: RenditionInput): String? {
        val extension = input.name.substringAfterLast('.', "").lowercase()
        val types = FILE_TYPES[input.type] ?: return null
        return extension.takeIf { it in types } ?: types.first()
    }

    companion object {
        /** What the conversion API accepts, by our type, with the names it knows (research §1.1). */
        private val FILE_TYPES =
            mapOf(
                FileType.DOCX to listOf("docx"),
                FileType.XLSX to listOf("xlsx"),
                FileType.PPTX to listOf("pptx"),
                FileType.ODT to listOf("odt"),
                FileType.ODS to listOf("ods"),
                FileType.ODP to listOf("odp"),
                FileType.TEXT to listOf("txt", "csv"),
                // Legacy formats (ADR-0055, E06 T1 spike: about 1 s each). TIFF is refused (-7): scans are download-only.
                FileType.DOC to listOf("doc"),
                FileType.XLS to listOf("xls"),
                FileType.PPT to listOf("ppt"),
                FileType.RTF to listOf("rtf"),
            )

        private const val HTTP_OK = 200
        private const val HTTP_TOO_LARGE = 413
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
        private val TOKEN_LIFETIME: Duration = Duration.ofMinutes(5)
        private val POLL: Duration = Duration.ofSeconds(2)

        /** Above the document server's own 120 s conversion timeout, so its answer (-2) arrives first. */
        private val DEADLINE: Duration = Duration.ofSeconds(150)

        /** ONLYOFFICE's conversion error codes: those retrying cannot fix are final. */
        fun outcomeOf(error: Int): RenditionOutcome =
            FINAL_ERRORS[error]?.let { RenditionOutcome.Final(it) } ?: RenditionOutcome.Retry("CONVERTER_$error".replace("-", "M"))

        /** -2 timeout, -5 password, -7 input, -8 token, -9 output type, -10 size; the others (-1, -3, -4, -6) may pass. */
        private val FINAL_ERRORS =
            mapOf(-2 to "TIMEOUT", -5 to "ENCRYPTED", -7 to "INPUT", -8 to "TOKEN", -9 to "UNSUPPORTED", -10 to "SIZE")
    }
}
