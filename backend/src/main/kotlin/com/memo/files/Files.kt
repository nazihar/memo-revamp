package com.memo.files

import com.memo.common.events.TenantScopedEvent
import com.memo.common.ratelimit.RateLimit
import java.nio.channels.SeekableByteChannel
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

    /**
     * The types allowed for an upload now, inside its tenant transaction: [allowedTypes], or fewer when the tenant narrows
     * them by a setting (Phase 3 E02, memo attachments). Never more than [allowedTypes].
     */
    fun allowedTypesNow(): Set<FileType> = allowedTypes

    /** Largest upload in bytes; the tenant setting and the platform ceiling can only lower it. */
    val maxBytes: Long

    val maxPdfPages: Int get() = DEFAULT_MAX_PDF_PAGES

    val storage: StorageClass

    /** Record purposes: years to retain each version; null uses the tenant setting `files.records.retention.years`. */
    val retentionYears: Int? get() = null

    /**
     * Years to retain a version uploaded for [ownerId], inside the upload's tenant transaction: [retentionYears] unless
     * the owner decides (a memo's final record follows its memo type, ADR-0053).
     */
    fun retentionYears(ownerId: String?): Int? = retentionYears

    /** Whether opening a version is audited (`file.opened`); false only for non-sensitive content such as a logo. */
    val auditReads: Boolean

    /** Types served `inline`; everything else is an attachment. Only [FileType.INLINE_SAFE] types are honoured. */
    val inlineTypes: Set<FileType> get() = emptySet()

    /** Derived content built for each new version (ADR-0045); none by default. */
    val renditions: Set<RenditionKind> get() = emptySet()

    /**
     * Whether the purpose accepts the [FileType.LEGACY] formats (ADR-0055): old Office documents typed by the Tika server,
     * RTF and TIFF. Only the purposes in [LEGACY_FORMAT_PURPOSES] may set it, and a purpose that allows a legacy type
     * must; checked at start-up.
     */
    val acceptsLegacyFormats: Boolean get() = false

    /**
     * Whether versions may be purged ([FilePurges]): the object deleted, the row and its hash kept (ADR-0055, staged
     * copies of an import). ORDINARY purposes without renditions only; checked at start-up.
     */
    val purgeable: Boolean get() = false

    /** The per-user limit on uploads (ADR-0025): [RateLimit.UPLOAD_PER_USER], or the import intake's own. */
    val uploadRateLimit: RateLimit get() = RateLimit.UPLOAD_PER_USER

    /** Whether the current actor may upload a file (or a new version) for [ownerId]. Called inside a tenant transaction. */
    fun mayUpload(ownerId: String?): Boolean

    /** Whether the current actor may read [file]. Called inside a tenant transaction. */
    fun mayRead(file: FileInfo): Boolean

    /**
     * Checks an upload's content beyond its type, once the type is known (for example a template's parts, ADR-0048):
     * throws [ContentRefused] to refuse it. [open] gives a fresh channel over the plaintext each time. Runs on the
     * inspection pool under its time limit, with no transaction and no tenant bound: read nothing but the content.
     */
    fun inspectContent(
        type: FileType,
        open: () -> SeekableByteChannel,
    ) = Unit

    companion object {
        const val DEFAULT_MAX_PDF_PAGES = 5_000

        /** The only purposes that may accept legacy formats: an import's staged originals and the imported ones (ADR-0055). */
        val LEGACY_FORMAT_PURPOSES: Set<String> = setOf("imports.intake", "memo.legacy")

        /** The per-user limits a purpose may choose for its uploads. */
        val UPLOAD_RATE_LIMITS: Set<RateLimit> = setOf(RateLimit.UPLOAD_PER_USER, RateLimit.IMPORT_INTAKE_PER_USER)
    }
}

/** One thing a purpose refuses in an upload's content: a stable [rule] code and where it is (a part name, a location). */
data class ContentRefusal(
    val rule: String,
    val where: String,
)

/** Thrown by [FilePurpose.inspectContent]: the upload is refused with `CONTENT_REFUSED` and these [refusals]. */
class ContentRefused(
    val refusals: List<ContentRefusal>,
) : RuntimeException("content refused: ${refusals.map { it.rule }.distinct()}")

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
    /** When its content was purged ([FilePurges]); the row and hash stay, the content is gone. */
    val purgedAt: Instant? = null,
)

/**
 * A new version was stored, published synchronously in the business transaction: the owning module may record its
 * pointer (for example the tenant logo) or veto the upload by throwing, which rolls everything back.
 */
data class FileVersionCreated(
    override val tenantId: UUID,
    val purposeKey: String,
    val ownerId: String?,
    val fileId: UUID,
    val versionId: UUID,
) : TenantScopedEvent

/**
 * The pipeline refused an upload (audited as `file.upload.rejected`), published synchronously in the transaction that
 * audits it, so the owning module can record why (for example an import's original, design E06 §5.1). Not published
 * when the scanner or the type detector was unavailable: that refuses nothing, the client tries again.
 */
data class FileUploadRejected(
    override val tenantId: UUID,
    val purposeKey: String,
    val ownerId: String?,
    /** The existing file of a new version; null for a new file. */
    val fileId: UUID?,
    val idempotencyKey: String,
    /** The stable reason code, as in the Problem Details: MALWARE, TYPE_NOT_ALLOWED, ENCRYPTED, HASH_MISMATCH, ... */
    val reasonCode: String,
    /** The scanner's signature for MALWARE, ENCRYPTED and LIMIT_EXCEEDED found by clamd; null otherwise. */
    val signatureName: String?,
) : TenantScopedEvent

/** Read access for owning modules. Inside a tenant transaction; no authorisation (the owner decides). */
interface Files {
    fun info(fileId: UUID): FileInfo?

    fun version(versionId: UUID): FileVersionInfo?

    /** The current version of each of [fileIds] that has one, by file id, in one query (lists of attachments). */
    fun currentVersions(fileIds: Collection<UUID>): Map<UUID, FileVersionInfo>

    /** Newest first, at most [limit], before version number [before] when given. */
    fun versions(
        fileId: UUID,
        limit: Int,
        before: Int? = null,
    ): List<FileVersionInfo>
}

/**
 * Content for owning modules that serve a file themselves, such as the tenant logo on the sign-in page (E07). No access
 * check: the owning module has decided who may see it. Call outside a transaction; tenant scope required.
 */
interface FileContents {
    /** The current version of [fileId] when the file has [purpose], else null. */
    fun current(
        fileId: UUID,
        purpose: String,
    ): FileVersionInfo?

    /** Writes the whole plaintext of [version] to [out]. */
    fun write(
        version: FileVersionInfo,
        out: java.io.OutputStream,
    )
}

/**
 * Stores files through the full upload pipeline (scan, inspection, encryption, purpose checks) for callers that are not
 * HTTP requests, such as seeders acting as a user (ADR-0029). Call outside a transaction, with the tenant and the acting
 * user bound; the purpose's `mayUpload` applies as for the API. Returns the new version's id.
 */
interface FileUploads {
    /** Whether uploads can be scanned now: a malware scanner is configured and answers (uploads fail closed without one). */
    fun ready(): Boolean

    /** A new file of [purposeKey] for [ownerId]. */
    fun upload(
        purposeKey: String,
        ownerId: String?,
        name: String,
        bytes: ByteArray,
        idempotencyKey: String,
    ): UUID

    /** A new file of [purposeKey] for [ownerId], streamed from [file] (large generated files, such as final records). */
    fun uploadFile(
        purposeKey: String,
        ownerId: String?,
        name: String,
        file: java.nio.file.Path,
        idempotencyKey: String,
    ): UUID

    /** A new version of [fileId] streamed from [file], guarded on its [expectedFileVersion]. */
    fun uploadVersionFile(
        fileId: UUID,
        expectedFileVersion: Long,
        name: String,
        file: java.nio.file.Path,
        idempotencyKey: String,
    ): UUID

    /** A new version of [fileId], guarded on its [expectedFileVersion]. */
    fun uploadVersion(
        fileId: UUID,
        expectedFileVersion: Long,
        name: String,
        bytes: ByteArray,
        idempotencyKey: String,
    ): UUID

    /**
     * A new file of [purposeKey] for [ownerId] holding the content of the stored version [versionId] (ADR-0055, an
     * import's promotion): its plaintext is streamed back through the whole pipeline (a new scan, detection and
     * inspection under the target purpose, encryption, storage), keeping its name. Refused with `HASH_MISMATCH` before
     * anything is stored when the content's SHA-256 is not [expectedSha256] (hex); idempotent on [idempotencyKey] like
     * any upload, and not counted against the actor's upload rate. The source is read without an access check (the
     * caller decides). The target purpose's `mayUpload` applies to the bound actor. Throws [UploadRefusedException] when
     * the pipeline refuses the content, and [IllegalArgumentException] for an unknown or purged source.
     */
    fun copyFrom(
        versionId: UUID,
        purposeKey: String,
        ownerId: String?,
        idempotencyKey: String,
        expectedSha256: String,
    ): UUID
}

/**
 * The pipeline refused a copy ([FileUploads.copyFrom]) for [reason], the stable code of `file.upload.rejected` (MALWARE,
 * TYPE_NOT_ALLOWED, ENCRYPTED, HASH_MISMATCH, ...). `SCAN_UNAVAILABLE` is worth retrying; every other reason is final
 * for that content.
 */
class UploadRefusedException(
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException(reason, cause)

/**
 * Purges stored content of purposes that declare it (ADR-0055, design E06 §3.3): the object is deleted from the `work`
 * bucket, the version's row, name and SHA-256 stay, downloads answer 410, and `file.version.purged` is audited. Record
 * versions are never purged. Call outside a transaction, with the tenant bound.
 */
interface FilePurges {
    /**
     * Purges [versionId]: true when this call purged it, false when it already was (its object is deleted again, so a
     * retry after a crash leaves nothing behind). Throws [IllegalArgumentException] for an unknown version, and
     * [IllegalStateException] when its purpose is not purgeable.
     */
    fun purge(versionId: UUID): Boolean
}

/**
 * Extends the lock of record versions (ADR-0029, ADR-0053): a memo's final record kept until its memo's closure plus the
 * type's retention. Later only; storage refuses anything shorter. Call outside a transaction, with the tenant bound.
 */
interface RecordRetentions {
    /** Retains [versionId] at least until [until]; false when it is not a record version or is already retained as long. */
    fun extend(
        versionId: UUID,
        until: Instant,
    ): Boolean
}
