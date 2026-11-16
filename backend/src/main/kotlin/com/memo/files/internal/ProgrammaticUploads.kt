package com.memo.files.internal

import com.memo.common.storage.ObjectStore
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.UploadRefusedException
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** [FileUploads] over the same pipeline as `POST /files`. */
@Component
internal class ProgrammaticUploads(
    private val uploads: Uploads,
    private val clamd: ClamdClient,
    private val store: FileStore,
    private val transactions: TenantTransactions,
    private val objects: ObjectStore,
    private val crypto: FileCrypto,
) : FileUploads {
    override fun ready(): Boolean = clamd.enabled && runCatching { clamd.version() }.isSuccess

    override fun upload(
        purposeKey: String,
        ownerId: String?,
        name: String,
        bytes: ByteArray,
        idempotencyKey: String,
    ): UUID = uploads.upload(UploadRequest(purposeKey, ownerId, null, null, name, bytes.size.toLong(), idempotencyKey, bytes.inputStream()))

    override fun uploadFile(
        purposeKey: String,
        ownerId: String?,
        name: String,
        file: Path,
        idempotencyKey: String,
    ): UUID =
        Files.newInputStream(file).use {
            uploads.upload(UploadRequest(purposeKey, ownerId, null, null, name, Files.size(file), idempotencyKey, it))
        }

    override fun uploadVersionFile(
        fileId: UUID,
        expectedFileVersion: Long,
        name: String,
        file: Path,
        idempotencyKey: String,
    ): UUID =
        Files.newInputStream(file).use {
            uploads.upload(UploadRequest(null, null, fileId, expectedFileVersion, name, Files.size(file), idempotencyKey, it))
        }

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

    override fun copyFrom(
        versionId: UUID,
        purposeKey: String,
        ownerId: String?,
        idempotencyKey: String,
        expectedSha256: String,
    ): UUID {
        val tenant = TenantContext.current()
        val (version, stored) =
            transactions.readInTenant(tenant) {
                val version = requireNotNull(store.version(versionId)) { "unknown file version" }
                require(version.purgedAt == null) { "file version purged" }
                version to requireNotNull(store.storedObject(versionId))
            }
        return try {
            CopySource(tenant, stored, objects, crypto).use { source ->
                uploads.upload(
                    UploadRequest(
                        purposeKey = purposeKey,
                        ownerId = ownerId,
                        fileId = null,
                        expectedFileVersion = null,
                        declaredName = version.name,
                        declaredLength = version.sizeBytes,
                        idempotencyKey = idempotencyKey,
                        body = source,
                        expectedSha256 = expectedSha256.lowercase(),
                        rateLimited = false,
                    ),
                )
            }
        } catch (e: UploadRejected) {
            throw UploadRefusedException(e.reason.name, e)
        }
    }
}
