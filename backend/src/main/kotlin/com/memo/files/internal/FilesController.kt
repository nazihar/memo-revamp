package com.memo.files.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.files.FileInfo
import com.memo.files.FileVersionInfo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as BodyDoc

data class FileVersionView(
    val fileId: UUID,
    val versionId: UUID,
    val number: Int,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val pdfPages: Int?,
    val uploadedBy: UUID?,
    val uploadedAt: Instant,
    val retainUntil: Instant?,
)

data class FileView(
    val id: UUID,
    val purpose: String,
    val ownerId: String?,
    val versionCount: Int,
    val version: Long,
    /** Newest first. */
    val versions: List<FileVersionView>,
    /** Pass as `before` for older versions; null when there are none. */
    val nextBefore: Int?,
)

/**
 * Uploads and file metadata (design §6). Signed-in users only; the file's purpose decides who may upload or read
 * (ADR-0029). The body of an upload is the raw file; its name travels in `Content-Disposition`, never in the URL.
 */
@RestController
@Tag(name = "files")
internal class FilesController(
    private val uploads: Uploads,
    private val store: FileStore,
    private val purposes: Purposes,
    private val transactions: TenantTransactions,
) {
    @PostMapping("/api/v1/files", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @SignedIn
    @Operation(
        operationId = "uploadFile",
        summary = "Upload a new file for a purpose",
        requestBody =
            BodyDoc(
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
    )
    fun upload(
        @RequestParam purpose: String,
        @RequestParam(required = false) owner: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader(HttpHeaders.CONTENT_DISPOSITION, required = false) disposition: String?,
        request: HttpServletRequest,
    ): ResponseEntity<FileVersionView> = created(upload(request, key, disposition, purposeKey = purpose, ownerId = owner))

    @PostMapping("/api/v1/files/{fileId}/versions", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @SignedIn
    @Operation(
        operationId = "uploadFileVersion",
        summary = "Upload a new version of a file",
        requestBody =
            BodyDoc(
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
    )
    fun uploadVersion(
        @PathVariable fileId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader(HttpHeaders.CONTENT_DISPOSITION, required = false) disposition: String?,
        request: HttpServletRequest,
    ): ResponseEntity<FileVersionView> =
        created(upload(request, key, disposition, fileId = fileId, expectedVersion = ifMatchVersion(ifMatch)))

    @GetMapping("/api/v1/files/{fileId}")
    @SignedIn
    @Operation(operationId = "getFile", summary = "A file and its versions, newest first")
    fun file(
        @PathVariable fileId: UUID,
        @RequestParam(required = false) before: Int?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<FileView> {
        val size = limit.coerceIn(1, MAX_PAGE)
        val view =
            transactions.readInTenant(TenantContext.current()) {
                val info = readable(fileId)
                val versions = store.versions(fileId, size + 1, before)
                FileView(
                    id = info.id,
                    purpose = info.purpose,
                    ownerId = info.ownerId,
                    versionCount = info.versionCount,
                    version = info.version,
                    versions = versions.take(size).map { it.toView() },
                    nextBefore = if (versions.size > size) versions[size - 1].number else null,
                )
            }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    /** The file, if it exists and the actor may read it under its purpose (unknown purpose: as if absent). */
    private fun readable(fileId: UUID): FileInfo {
        val info = store.info(fileId)
        val purpose = info?.let { purposes[it.purpose] } ?: throw FileNotFoundException()
        if (!purpose.mayRead(info)) throw ForbiddenException(null)
        return info
    }

    private fun upload(
        request: HttpServletRequest,
        key: String,
        disposition: String?,
        purposeKey: String? = null,
        ownerId: String? = null,
        fileId: UUID? = null,
        expectedVersion: Long? = null,
    ): FileVersionView {
        val versionId =
            uploads.upload(
                UploadRequest(
                    purposeKey = purposeKey,
                    ownerId = ownerId,
                    fileId = fileId,
                    expectedFileVersion = expectedVersion,
                    declaredName = disposition?.let { runCatching { ContentDisposition.parse(it).filename }.getOrNull() },
                    declaredLength = request.contentLengthLong.takeIf { it >= 0 },
                    idempotencyKey = key,
                    body = request.inputStream,
                ),
            )
        return transactions.readInTenant(TenantContext.current()) { store.version(versionId)!!.toView() }
    }

    private fun created(view: FileVersionView): ResponseEntity<FileVersionView> =
        ResponseEntity.created(URI.create("/api/v1/files/${view.fileId}")).body(view)

    private fun FileVersionInfo.toView() =
        FileVersionView(
            fileId = fileId,
            versionId = id,
            number = number,
            name = name,
            mediaType = mediaType,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            pdfPages = pdfPages,
            uploadedBy = uploadedBy,
            uploadedAt = uploadedAt,
            retainUntil = retainUntil,
        )

    private companion object {
        const val MAX_PAGE = 100
    }
}
