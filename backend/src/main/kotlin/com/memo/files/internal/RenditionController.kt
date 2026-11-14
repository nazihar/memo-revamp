package com.memo.files.internal

import com.memo.common.authz.SignedIn
import com.memo.files.FileType
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** One rendition of a version as the API shows it (ADR-0045). */
data class RenditionView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: RenditionKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: RenditionState,
    /** Why it failed or is not possible, for example TIMEOUT, SIZE, PAGES, UNSUPPORTED, NO_SERVICE. */
    val error: String?,
    val pages: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val truncated: Boolean,
)

/** What a version offers for preview: how to show it, and its renditions. */
data class VersionRenditions(
    /** `PDF` (the original or its rendition, at the pdf endpoint), `IMAGE` (the original), or `NONE`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val preview: PreviewKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val renditions: List<RenditionView>,
)

enum class PreviewKind { PDF, IMAGE, PENDING, NONE }

/**
 * Renditions of a version (ADR-0045): their states, and the PDF to preview, streamed like content (ranges, sandboxed,
 * never sniffed). The original's purpose decides who may read them; reading a rendition is audited as an open.
 */
@RestController
@Tag(name = "files")
internal class RenditionController(
    private val downloads: Downloads,
) {
    @GetMapping("/api/v1/files/{fileId}/versions/{versionId}/renditions")
    @SignedIn
    @Operation(operationId = "getFileVersionRenditions", summary = "How a version can be previewed, and its renditions")
    fun renditions(
        @PathVariable fileId: UUID,
        @PathVariable versionId: UUID,
    ): VersionRenditions {
        val (version, rows) = downloads.renditionsOf(fileId, versionId)
        val type = FileType.ofMediaType(version.mediaType)
        val pdf = rows.firstOrNull { it.kind == RenditionKind.PDF }
        val preview =
            when {
                type == FileType.PDF || pdf?.state == RenditionState.DONE -> PreviewKind.PDF
                type in RenditionScheduler.IMAGES -> PreviewKind.IMAGE
                pdf?.state == RenditionState.PENDING -> PreviewKind.PENDING
                else -> PreviewKind.NONE
            }
        return VersionRenditions(preview, rows.map { RenditionView(it.kind, it.state, it.error, it.pages, it.truncated) })
    }

    @GetMapping("/api/v1/files/{fileId}/versions/{versionId}/renditions/pdf")
    @SignedIn
    @Operation(operationId = "getFileVersionPdf", summary = "The PDF to preview a version: the original if it is a PDF, else its rendition")
    fun pdf(
        @PathVariable fileId: UUID,
        @PathVariable versionId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val (version, row) =
            downloads.openRendition(fileId, versionId, RenditionKind.PDF, request.getSession(false)) ?: run {
                // A PDF original is its own preview; anything else has none yet.
                val (original, _) = downloads.renditionsOf(fileId, versionId)
                if (FileType.ofMediaType(original.mediaType) != FileType.PDF) throw FileNotFoundException()
                val readable = downloads.open(fileId, versionId, request.getSession(false), null)
                headers(response, readable.etag)
                response.setContentLengthLong(readable.version.sizeBytes)
                if (request.method != "HEAD") downloads.stream(readable, 0, readable.version.sizeBytes, response.outputStream)
                return
            }
        val size = row.sizeBytes!!
        headers(response, "\"${version.id}.pdf\"")
        val (first, length) =
            when (val range = ByteRanges.parse(request.getHeader(HttpHeaders.RANGE), size)) {
                is RangeRequest.Unsatisfiable -> {
                    response.status = HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */$size")
                    return
                }

                is RangeRequest.Partial -> {
                    response.status = HttpServletResponse.SC_PARTIAL_CONTENT
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes ${range.range.first}-${range.range.last}/$size")
                    range.range.first to range.range.length
                }

                is RangeRequest.Whole -> {
                    0L to size
                }
            }
        response.setContentLengthLong(length)
        if (request.method != "HEAD") downloads.streamRendition(row, first, length, response.outputStream)
    }

    private fun headers(
        response: HttpServletResponse,
        etag: String,
    ) {
        response.contentType = "application/pdf"
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
        response.setHeader(HttpHeaders.ETAG, etag)
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin")
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store")
    }
}
