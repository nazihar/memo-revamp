package com.memo.files.internal

import com.memo.common.authz.SignedIn
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * File content (design §5, ADR-0029): streamed through the backend, never a presigned URL. Single ranges give 206,
 * several ranges the whole file, an unsatisfiable one 416. Content is served with its detected type, as an attachment
 * unless the purpose allows inline display of a safe type, sandboxed and never sniffed.
 */
@RestController
@Tag(name = "files")
internal class FileContentController(
    private val downloads: Downloads,
) {
    @GetMapping("/api/v1/files/{fileId}/content")
    @SignedIn
    @Operation(operationId = "getFileContent", summary = "Content of the current version (supports Range)")
    fun current(
        @PathVariable fileId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = serve(fileId, null, request, response)

    @GetMapping("/api/v1/files/{fileId}/versions/{versionId}/content")
    @SignedIn
    @Operation(operationId = "getFileVersionContent", summary = "Content of one version (supports Range)")
    fun version(
        @PathVariable fileId: UUID,
        @PathVariable versionId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = serve(fileId, versionId, request, response)

    private fun serve(
        fileId: UUID,
        versionId: UUID?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val rangeHeader = request.getHeader(HttpHeaders.RANGE)
        val askedStart = (ByteRanges.parse(rangeHeader, Long.MAX_VALUE) as? RangeRequest.Partial)?.range?.first
        val readable = downloads.open(fileId, versionId, request.getSession(false), askedStart)
        val size = readable.version.sizeBytes
        headers(response, readable)
        if (request.getHeader(HttpHeaders.IF_NONE_MATCH)?.split(',')?.any { it.trim() == readable.etag } == true) {
            response.status = HttpServletResponse.SC_NOT_MODIFIED
            return
        }
        val ifRange = request.getHeader(HttpHeaders.IF_RANGE)
        val range = if (ifRange != null && ifRange != readable.etag) RangeRequest.Whole else ByteRanges.parse(rangeHeader, size)
        val (first, length) =
            when (range) {
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
        if (request.method != "HEAD") downloads.stream(readable, first, length, response.outputStream)
    }

    private fun headers(
        response: HttpServletResponse,
        readable: Readable,
    ) {
        response.contentType = readable.version.mediaType
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, readable.disposition)
        response.setHeader(HttpHeaders.ETAG, readable.etag)
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
        response.setHeader("X-Content-Type-Options", "nosniff")
        // Set before Spring Security's header writer, which only adds headers that are not present.
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin")
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store")
    }
}
