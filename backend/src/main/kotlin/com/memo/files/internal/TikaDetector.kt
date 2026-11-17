package com.memo.files.internal

import com.memo.files.FileType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Names the document inside an OLE2 container for purposes that accept legacy formats (ADR-0055): the in-process
 * detector sees only `application/x-tika-msoffice`, and nothing in the backend JVM parses OLE2. The isolated Tika server
 * answers `PUT /detect` (enabled in `deploy/tika/tika-config.json`; Tika 4.2 has no `/detect/stream`) from inside the
 * container. Word, Excel and PowerPoint are allowed; anything else, such as an Outlook message, is not. Fails closed: no
 * server configured, or one that cannot answer, refuses the upload as `SCAN_UNAVAILABLE` (503), never as accepted.
 */
@Component
internal class TikaDetector(
    private val properties: FilesProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    /** The type of the OLE2 document whose plaintext [open] streams; throws [UploadRejected] for anything else. */
    fun ole2Type(open: () -> InputStream): FileType {
        if (properties.tikaUrl.isBlank()) reject(RejectionReason.SCAN_UNAVAILABLE, "scanner" to "type detection not configured")
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.tikaUrl.trimEnd('/') + "/detect"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/plain")
                .header("Content-Type", FileType.OLE2.mediaType)
                .PUT(HttpRequest.BodyPublishers.ofInputStream { open() })
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: IOException) {
                log.warn("Type detection request failed: {}", e.javaClass.simpleName)
                reject(RejectionReason.SCAN_UNAVAILABLE, "scanner" to "type detection unreachable", cause = e)
            }
        val answer = response.body().use { body -> body.readNBytes(MAX_ANSWER).toString(Charsets.US_ASCII).trim() }
        return when (val status = response.statusCode()) {
            HTTP_OK -> typeOf(answer)
            HTTP_UNPROCESSABLE -> reject(RejectionReason.MALFORMED, "problem" to "type-detection")
            else -> reject(RejectionReason.SCAN_UNAVAILABLE, "scanner" to "type detection answered $status")
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_UNPROCESSABLE = 422
        private const val MAX_ANSWER = 200
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** Below the inspection's own limit (20 s), so a server that does not answer is "unavailable", not "too complex". */
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)

        /** Encrypted OOXML travels in an OLE2 container; it cannot be checked (ADR-0029). */
        private const val PROTECTED_OOXML = "application/x-tika-ooxml-protected"

        /** What the server's answer means: an allowed OLE2 document, or a refusal naming what it found. */
        internal fun typeOf(mediaType: String): FileType {
            val base = mediaType.substringBefore(';').trim().lowercase()
            if (base == PROTECTED_OOXML) reject(RejectionReason.ENCRYPTED, "detectedType" to base)
            return FileType.ofMediaType(base)?.takeIf { it in FileType.OLE2_DOCUMENTS }
                ?: reject(RejectionReason.TYPE_NOT_ALLOWED, "detectedType" to base.take(MAX_ANSWER))
        }
    }
}
