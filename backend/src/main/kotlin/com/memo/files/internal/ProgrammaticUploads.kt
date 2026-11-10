package com.memo.files.internal

import com.memo.files.FileUploads
import org.springframework.stereotype.Component
import java.util.UUID

/** [FileUploads] over the same pipeline as `POST /files`. */
@Component
internal class ProgrammaticUploads(
    private val uploads: Uploads,
    private val clamd: ClamdClient,
) : FileUploads {
    override fun ready(): Boolean = !clamd.enabled || runCatching { clamd.version() }.isSuccess

    override fun upload(
        purposeKey: String,
        ownerId: String?,
        name: String,
        bytes: ByteArray,
        idempotencyKey: String,
    ): UUID = uploads.upload(UploadRequest(purposeKey, ownerId, null, null, name, bytes.size.toLong(), idempotencyKey, bytes.inputStream()))

    override fun uploadVersion(
        fileId: UUID,
        expectedFileVersion: Long,
        name: String,
        bytes: ByteArray,
        idempotencyKey: String,
    ): UUID =
        uploads.upload(
            UploadRequest(null, null, fileId, expectedFileVersion, name, bytes.size.toLong(), idempotencyKey, bytes.inputStream()),
        )
}
