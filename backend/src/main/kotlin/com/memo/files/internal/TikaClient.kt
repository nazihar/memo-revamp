package com.memo.files.internal

import com.memo.files.FileType
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.files.TextExtractor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Text through an Apache Tika server (ADR-0045, research §3): `PUT /tika/text` with the decrypted bytes streamed over the
 * internal network. The server parses in forked JVMs with the limits of `deploy/tika/tika-config.json`; this client
 * keeps at most `maxChars` characters and says when it cut. Present only when `memo.files.tika-url` is set.
 */
@Component
@ConditionalOnExpression("!'\${memo.files.tika-url:}'.isBlank()")
internal class TikaClient(
    private val properties: FilesProperties,
) : TextExtractor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    override val textTypes: Set<FileType> =
        setOf(
            FileType.PDF,
            FileType.DOCX,
            FileType.XLSX,
            FileType.PPTX,
            FileType.ODT,
            FileType.ODS,
            FileType.ODP,
            FileType.TEXT,
        )

    override fun text(
        input: RenditionInput,
        maxChars: Long,
        out: OutputStream,
    ): RenditionOutcome {
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.tikaUrl.trimEnd('/') + "/tika/text"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/plain")
                .header("Content-Type", input.type.mediaType)
                .PUT(HttpRequest.BodyPublishers.ofInputStream { input.open() })
                .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                when (val status = response.statusCode()) {
                    HTTP_OK -> RenditionOutcome.Written(truncated = copy(InputStreamReader(body, Charsets.UTF_8), out, maxChars))

                    HTTP_TOO_LARGE -> RenditionOutcome.Final("SIZE")

                    HTTP_UNPROCESSABLE -> RenditionOutcome.Final("MALFORMED")

                    HTTP_UNSUPPORTED -> RenditionOutcome.Final("UNSUPPORTED")

                    // 429: every fork busy; 503: a fork crashed, ran out of memory or timed out; both may pass.
                    else -> RenditionOutcome.Retry("HTTP_$status")
                }
            }
        } catch (e: IOException) {
            log.warn("Text extraction request failed: {}", e.javaClass.simpleName)
            RenditionOutcome.Retry("UNREACHABLE")
        }
    }

    /** Copies at most [maxChars] characters as UTF-8; true when there was more. */
    private fun copy(
        reader: InputStreamReader,
        out: OutputStream,
        maxChars: Long,
    ): Boolean {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        val buffer = CharArray(BUFFER)
        var copied = 0L
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            val keep = minOf(n.toLong(), maxChars - copied).toInt()
            writer.write(buffer, 0, keep)
            copied += keep
            if (keep < n) {
                writer.flush()
                return true
            }
        }
        writer.flush()
        return false
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_TOO_LARGE = 413
        const val HTTP_UNSUPPORTED = 415
        const val HTTP_UNPROCESSABLE = 422
        const val BUFFER = 8192
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** Above the server's own 120 s task timeout, so its answer arrives first. */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(150)
    }
}
