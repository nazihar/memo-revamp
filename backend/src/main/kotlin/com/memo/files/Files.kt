package com.memo.files

import java.time.Instant
import java.util.UUID

/** Where a purpose's files live (ADR-0029): object-locked records, or ordinary objects (for example a tenant logo). */
enum class StorageClass { RECORD, ORDINARY }

/**
 * What a kind of file is for, declared in code by the module that owns such files (ADR-0029). The files service checks
 * uploads and reads against it; no purpose, no upload. Register purposes as Spring beans; keys are unique.
 */
interface FilePurpose {
    /** Stable key, for example `tenant.logo`: lower case, dots and dashes. */
    val key: String

    val allowedTypes: Set<FileType>

    /** Largest upload in bytes; the tenant setting and the platform ceiling can only lower it. */
    val maxBytes: Long

    val maxPdfPages: Int get() = DEFAULT_MAX_PDF_PAGES

    val storage: StorageClass

    /** Record purposes: years to retain each version; null uses the tenant setting `files.records.retention-years`. */
    val retentionYears: Int? get() = null

    /** Whether opening a version is audited (`file.opened`); false only for non-sensitive content such as a logo. */
    val auditReads: Boolean

    /** Types served `inline`; everything else is an attachment. Only [FileType.INLINE_SAFE] types are honoured. */
    val inlineTypes: Set<FileType> get() = emptySet()

    /** Whether the current actor may upload a file (or a new version) for [ownerId]. Called inside a tenant transaction. */
    fun mayUpload(ownerId: String?): Boolean

    /** Whether the current actor may read [file]. Called inside a tenant transaction. */
    fun mayRead(file: FileInfo): Boolean

    companion object {
        const val DEFAULT_MAX_PDF_PAGES = 5_000
    }
}

data class FileInfo(
    val id: UUID,
    val purpose: String,
    val ownerId: String?,
    val currentVersionId: UUID?,
    val versionCount: Int,
    val createdBy: UUID?,
    val createdAt: Instant,
    val version: Long,
)

data class FileVersionInfo(
    val id: UUID,
    val fileId: UUID,
    val number: Int,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the plaintext. */
    val sha256: String,
    val pdfPages: Int?,
    val uploadedBy: UUID?,
    val uploadedAt: Instant,
    /** Record versions: end of the object-lock retention. */
    val retainUntil: Instant?,
)

/**
 * A new version was stored, published synchronously in the business transaction: the owning module may record its
 * pointer (for example the tenant logo) or veto the upload by throwing, which rolls everything back.
 */
data class FileVersionCreated(
    val tenantId: UUID,
    val purpose: String,
    val ownerId: String?,
    val fileId: UUID,
    val versionId: UUID,
)

/** Read access for owning modules. Inside a tenant transaction; no authorisation (the owner decides). */
interface Files {
    fun info(fileId: UUID): FileInfo?

    fun version(versionId: UUID): FileVersionInfo?

    /** Newest first, at most [limit], before version number [before] when given. */
    fun versions(
        fileId: UUID,
        limit: Int,
        before: Int? = null,
    ): List<FileVersionInfo>
}
