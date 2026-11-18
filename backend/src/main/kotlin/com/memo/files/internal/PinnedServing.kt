package com.memo.files.internal

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileServing
import com.memo.files.FileType
import com.memo.files.FileVersionInfo
import com.memo.files.OpenAudit
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.files.ServeOutcome
import com.memo.files.ServedContent
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** What a pinned open streams: the stored version, or its done PDF rendition. */
private sealed interface Pinned {
    val version: FileVersionInfo
    val size: Long
    val etag: String

    data class Original(
        val readable: Readable,
    ) : Pinned {
        override val version get() = readable.version
        override val size get() = readable.version.sizeBytes
        override val etag get() = readable.etag
    }

    data class Rendition(
        override val version: FileVersionInfo,
        val row: RenditionRow,
    ) : Pinned {
        override val size get() = checkNotNull(row.sizeBytes)
        override val etag get() = "\"${version.id}.pdf\""
    }
}

/**
 * [FileServing] (ADR-0061): a pinned version for a caller that decided access itself. The lookup and the open's audit in
 * one short transaction, then streaming outside it, with the headers and ranges of the files endpoints.
 */
@Component
internal class PinnedServing(
    private val store: FileStore,
    private val purposes: Purposes,
    private val renditions: RenditionStore,
    private val downloads: Downloads,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
) : FileServing {
    override fun serve(
        versionId: UUID,
        content: ServedContent,
        request: HttpServletRequest,
        response: HttpServletResponse,
        audit: OpenAudit,
    ): ServeOutcome {
        val pinned =
            transactions.inTenant(TenantContext.current()) { open(versionId, content, request, audit) }
                ?: return outcomeOf(versionId)
        headers(response, pinned, content)
        if (request.getHeader(HttpHeaders.IF_NONE_MATCH)?.split(',')?.any { it.trim() == pinned.etag } == true) {
            response.status = HttpServletResponse.SC_NOT_MODIFIED
        } else {
            write(pinned, request, response)
        }
        return ServeOutcome.SERVED
    }

    /** The whole content, or the one range asked for (206); 416 for a range outside it. */
    private fun write(
        pinned: Pinned,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val ifRange = request.getHeader(HttpHeaders.IF_RANGE)
        val stale = ifRange != null && ifRange != pinned.etag
        val range = if (stale) RangeRequest.Whole else ByteRanges.parse(request.getHeader(HttpHeaders.RANGE), pinned.size)
        if (range is RangeRequest.Unsatisfiable) {
            response.status = HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */${pinned.size}")
            return
        }
        val (first, length) =
            if (range is RangeRequest.Partial) {
                response.status = HttpServletResponse.SC_PARTIAL_CONTENT
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes ${range.range.first}-${range.range.last}/${pinned.size}")
                range.range.first to range.range.length
            } else {
                0L to pinned.size
            }
        response.setContentLengthLong(length)
        if (request.method == "HEAD") return
        when (pinned) {
            is Pinned.Original -> downloads.stream(pinned.readable, first, length, response.outputStream)
            is Pinned.Rendition -> downloads.streamRendition(pinned.row, first, length, response.outputStream)
        }
    }

    private fun headers(
        response: HttpServletResponse,
        pinned: Pinned,
        content: ServedContent,
    ) {
        response.contentType = if (pinned is Pinned.Rendition) PDF else pinned.version.mediaType
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition(pinned, content))
        response.setHeader(HttpHeaders.ETAG, pinned.etag)
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
        response.setHeader("X-Content-Type-Options", "nosniff")
        // Set before Spring Security's header writer, which only adds headers that are not present.
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin")
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store")
    }

    /** The version's content to stream, audited at the session's first open of it; null when there is none. */
    private fun open(
        versionId: UUID,
        content: ServedContent,
        request: HttpServletRequest,
        context: OpenAudit,
    ): Pinned? {
        val version = store.version(versionId)?.takeIf { it.purgedAt == null }
        val purpose = version?.let { store.info(it.fileId) }?.let { purposes[it.purpose] }
        if (version == null || purpose == null) return null
        val original = content == ServedContent.ORIGINAL || FileType.ofMediaType(version.mediaType) == FileType.PDF
        val pinned =
            if (original) {
                store.storedObject(version.id)?.let { Pinned.Original(Readable(version, purpose, it)) }
            } else {
                renditions
                    .get(version.id, RenditionKind.PDF)
                    ?.takeIf { it.state == RenditionState.DONE }
                    ?.let { Pinned.Rendition(version, it) }
            }
        val mark = UUID.nameUUIDFromBytes("pinned/${version.id}/${content.name}".toByteArray(Charsets.UTF_8))
        if (pinned != null && OpenMarks.firstOpen(request.getSession(false), mark, clock.millis())) {
            audit.record(
                context.action,
                context.objectType,
                context.objectId,
                after =
                    context.details +
                        mapOf(
                            "fileId" to version.fileId.toString(),
                            "fileVersionId" to version.id.toString(),
                            "purpose" to purpose.key,
                            "content" to content.name,
                        ),
            )
        }
        return pinned
    }

    /** Why nothing was served: no such version, or nothing ready for it. */
    private fun outcomeOf(versionId: UUID): ServeOutcome =
        transactions.readInTenant(TenantContext.current()) {
            if (store.version(versionId)?.takeIf { it.purgedAt == null } == null) ServeOutcome.MISSING else ServeOutcome.NOT_READY
        }

    /** The PDF to read in is shown inline; an original follows its purpose (an attachment unless a safe inline type). */
    private fun disposition(
        pinned: Pinned,
        content: ServedContent,
    ): String =
        when {
            pinned is Pinned.Original && content == ServedContent.ORIGINAL -> {
                pinned.readable.disposition
            }

            else -> {
                ContentDisposition
                    .inline()
                    .filename(pdfName(pinned.version.name), Charsets.UTF_8)
                    .build()
                    .toString()
            }
        }

    private fun pdfName(name: String): String = if (name.lowercase().endsWith(".pdf")) name else "${name.substringBeforeLast('.')}.pdf"

    private companion object {
        const val PDF = "application/pdf"
    }
}
