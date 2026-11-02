package com.memo.files.internal

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.storage.RetentionMode
import com.memo.common.tenant.TenantDsl
import com.memo.files.FileInfo
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import com.memo.jooq.tables.records.FilesFileRecord
import com.memo.jooq.tables.records.FilesVersionRecord
import com.memo.jooq.tables.references.FILES_FILE
import com.memo.jooq.tables.references.FILES_PENDING_UPLOAD
import com.memo.jooq.tables.references.FILES_VERSION
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** Lock state of a stored version (design §2): NONE for ordinary objects; PROVISIONAL → FINAL for records. */
internal enum class LockState { NONE, PROVISIONAL, FINAL }

/** Everything known about a version when it is recorded. */
internal data class NewVersion(
    val id: UUID,
    val fileId: UUID,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: ByteArray,
    val bucket: String,
    val objectKey: String,
    val ciphertextBytes: Long,
    val keysetKeyId: Long,
    val scanEngine: String,
    val pdfPages: Int?,
    val lockState: LockState,
    val retainUntil: Instant?,
    val retentionMode: RetentionMode?,
    val uploadedBy: UUID?,
    val uploadedAt: Instant,
)

/** Where a stored version's ciphertext is. */
internal data class StoredObject(
    val fileId: UUID,
    val versionId: UUID,
    val bucket: String,
    val objectKey: String,
    val ciphertextBytes: Long,
)

/** Tables of the files module; every method runs inside a tenant transaction (RLS). */
@Component
internal class FileStore(
    private val tenantDsl: TenantDsl,
) : Files {
    private val dsl get() = tenantDsl.current()

    override fun info(fileId: UUID): FileInfo? =
        dsl
            .selectFrom(FILES_FILE)
            .where(FILES_FILE.ID.eq(fileId))
            .fetchOne()
            ?.toInfo()

    override fun version(versionId: UUID): FileVersionInfo? =
        dsl
            .selectFrom(FILES_VERSION)
            .where(FILES_VERSION.ID.eq(versionId))
            .fetchOne()
            ?.toInfo()

    override fun versions(
        fileId: UUID,
        limit: Int,
        before: Int?,
    ): List<FileVersionInfo> =
        dsl
            .selectFrom(FILES_VERSION)
            .where(FILES_VERSION.FILE_ID.eq(fileId))
            .apply { if (before != null) and(FILES_VERSION.NUMBER.lt(before)) }
            .orderBy(FILES_VERSION.NUMBER.desc())
            .limit(limit)
            .fetch()
            .map { it.toInfo() }

    fun storedObject(versionId: UUID): StoredObject? =
        dsl
            .select(FILES_VERSION.FILE_ID, FILES_VERSION.ID, FILES_VERSION.BUCKET, FILES_VERSION.OBJECT_KEY, FILES_VERSION.CIPHERTEXT_BYTES)
            .from(FILES_VERSION)
            .where(FILES_VERSION.ID.eq(versionId))
            .fetchOne { StoredObject(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!) }

    fun insertFile(
        id: UUID,
        purpose: String,
        ownerId: String?,
        createdBy: UUID?,
        now: Instant,
    ) {
        dsl
            .insertInto(FILES_FILE)
            .set(FILES_FILE.ID, id)
            .set(FILES_FILE.TENANT_ID, tenantDsl.tenant().value)
            .set(FILES_FILE.PURPOSE, purpose)
            .set(FILES_FILE.OWNER_ID, ownerId)
            .set(FILES_FILE.CREATED_BY, createdBy)
            .set(FILES_FILE.CREATED_AT, now.utc())
            .execute()
    }

    /**
     * Adds [version] as the next number of its file and makes it current, if the file is still at [expectedVersion]
     * (guarded update, AGENTS.md §4). Locks the file row, so concurrent uploads to one file serialise; numbers stay gapless.
     */
    fun appendVersion(
        version: NewVersion,
        expectedVersion: Long,
    ): Int {
        val file =
            dsl
                .selectFrom(FILES_FILE)
                .where(FILES_FILE.ID.eq(version.fileId))
                .forUpdate()
                .fetchOne()
                ?: throw FileNotFoundException()
        if (file.version != expectedVersion) throw ConcurrentModificationException("file", expectedVersion)
        val number = file.versionCount!! + 1
        dsl
            .insertInto(FILES_VERSION)
            .set(FILES_VERSION.ID, version.id)
            .set(FILES_VERSION.TENANT_ID, tenantDsl.tenant().value)
            .set(FILES_VERSION.FILE_ID, version.fileId)
            .set(FILES_VERSION.NUMBER, number)
            .set(FILES_VERSION.ORIGINAL_NAME, version.name)
            .set(FILES_VERSION.MEDIA_TYPE, version.mediaType)
            .set(FILES_VERSION.SIZE_BYTES, version.sizeBytes)
            .set(FILES_VERSION.SHA256, version.sha256)
            .set(FILES_VERSION.BUCKET, version.bucket)
            .set(FILES_VERSION.OBJECT_KEY, version.objectKey)
            .set(FILES_VERSION.CIPHERTEXT_BYTES, version.ciphertextBytes)
            .set(FILES_VERSION.KEYSET_KEY_ID, version.keysetKeyId)
            .set(FILES_VERSION.SCAN_ENGINE, version.scanEngine)
            .set(FILES_VERSION.PDF_PAGES, version.pdfPages)
            .set(FILES_VERSION.LOCK_STATE, version.lockState.name)
            .set(FILES_VERSION.RETAIN_UNTIL, version.retainUntil?.utc())
            .set(FILES_VERSION.RETENTION_MODE, version.retentionMode?.name)
            .set(FILES_VERSION.UPLOADED_BY, version.uploadedBy)
            .set(FILES_VERSION.UPLOADED_AT, version.uploadedAt.utc())
            .execute()
        dsl
            .update(FILES_FILE)
            .set(FILES_FILE.CURRENT_VERSION_ID, version.id)
            .set(FILES_FILE.VERSION_COUNT, number)
            .set(FILES_FILE.VERSION, FILES_FILE.VERSION.plus(1))
            .where(FILES_FILE.ID.eq(version.fileId))
            .execute()
        return number
    }

    /** PROVISIONAL → FINAL; false if the version was not provisional (already final, or not a record). */
    fun markFinal(versionId: UUID): Boolean =
        dsl
            .update(FILES_VERSION)
            .set(FILES_VERSION.LOCK_STATE, LockState.FINAL.name)
            .where(FILES_VERSION.ID.eq(versionId))
            .and(FILES_VERSION.LOCK_STATE.eq(LockState.PROVISIONAL.name))
            .execute() == 1

    /** Record versions still provisional and uploaded before [before]: (version id, object key, retain until, mode). */
    fun provisional(
        before: Instant,
        limit: Int,
    ): List<ProvisionalLock> =
        dsl
            .select(FILES_VERSION.ID, FILES_VERSION.OBJECT_KEY, FILES_VERSION.RETAIN_UNTIL, FILES_VERSION.RETENTION_MODE)
            .from(FILES_VERSION)
            .where(FILES_VERSION.LOCK_STATE.eq(LockState.PROVISIONAL.name))
            .and(FILES_VERSION.UPLOADED_AT.lt(before.utc()))
            .orderBy(FILES_VERSION.UPLOADED_AT)
            .limit(limit)
            .fetch { ProvisionalLock(it.value1()!!, it.value2()!!, it.value3()!!.toInstant(), RetentionMode.valueOf(it.value4()!!)) }

    private fun FilesFileRecord.toInfo() =
        FileInfo(
            id = id,
            purpose = purpose,
            ownerId = ownerId,
            currentVersionId = currentVersionId,
            versionCount = versionCount!!,
            createdBy = createdBy,
            createdAt = createdAt.toInstant(),
            version = version!!,
        )

    private fun FilesVersionRecord.toInfo() =
        FileVersionInfo(
            id = id,
            fileId = fileId,
            number = number,
            name = originalName,
            mediaType = mediaType,
            sizeBytes = sizeBytes,
            sha256 = HexFormat.of().formatHex(sha256),
            pdfPages = pdfPages,
            uploadedBy = uploadedBy,
            uploadedAt = uploadedAt.toInstant(),
            retainUntil = retainUntil?.toInstant(),
        )
}

/** Objects written to storage whose business transaction has not committed yet (design §12). In a tenant transaction. */
@Component
internal class PendingUploads(
    private val tenantDsl: TenantDsl,
) {
    private val dsl get() = tenantDsl.current()

    fun insert(
        id: UUID,
        bucket: String,
        objectKey: String,
        now: Instant,
        provisionalUntil: Instant?,
    ) {
        dsl
            .insertInto(FILES_PENDING_UPLOAD)
            .set(FILES_PENDING_UPLOAD.ID, id)
            .set(FILES_PENDING_UPLOAD.TENANT_ID, tenantDsl.tenant().value)
            .set(FILES_PENDING_UPLOAD.BUCKET, bucket)
            .set(FILES_PENDING_UPLOAD.OBJECT_KEY, objectKey)
            .set(FILES_PENDING_UPLOAD.CREATED_AT, now.utc())
            .set(FILES_PENDING_UPLOAD.PROVISIONAL_UNTIL, provisionalUntil?.utc())
            .execute()
    }

    fun delete(id: UUID) {
        dsl.deleteFrom(FILES_PENDING_UPLOAD).where(FILES_PENDING_UPLOAD.ID.eq(id)).execute()
    }

    /** Pending uploads created before [before]: their transaction never committed (or is long gone). */
    fun stale(
        before: Instant,
        limit: Int,
    ): List<PendingUpload> =
        dsl
            .selectFrom(FILES_PENDING_UPLOAD)
            .where(FILES_PENDING_UPLOAD.CREATED_AT.lt(before.utc()))
            .orderBy(FILES_PENDING_UPLOAD.CREATED_AT)
            .limit(limit)
            .fetch { PendingUpload(it.id, it.bucket, it.objectKey, it.provisionalUntil?.toInstant()) }

    /** Whether a committed version uses this object (then a pending row is only a leftover of an idempotent replay). */
    fun isReferenced(
        bucket: String,
        objectKey: String,
    ): Boolean = dsl.fetchExists(FILES_VERSION, FILES_VERSION.BUCKET.eq(bucket).and(FILES_VERSION.OBJECT_KEY.eq(objectKey)))
}

internal fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal data class ProvisionalLock(
    val versionId: UUID,
    val objectKey: String,
    val retainUntil: Instant,
    val mode: RetentionMode,
)

internal data class PendingUpload(
    val id: UUID,
    val bucket: String,
    val objectKey: String,
    val provisionalUntil: Instant?,
)

/** An unknown file or version id (also another tenant's: RLS hides it): 404. */
internal class FileNotFoundException : RuntimeException("file not found")
