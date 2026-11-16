package com.memo.imports.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.ratelimit.RateLimit
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.StorageClass
import com.memo.imports.internal.sheet.SheetLimits
import org.springframework.stereotype.Component

/**
 * A batch's metadata (ADR-0055, design E06 §10): the workbook, or a CSV file per table, staged in the `work` bucket
 * (ordinary, purgeable). The owner is `{batchId}:{table}`; holders of `memo.import` upload while the batch is OPEN or
 * VALIDATED, and read them.
 */
@Component
class ImportSourcePurpose(
    private val policy: Policy,
    private val intake: ImportIntake,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.XLSX, FileType.TEXT)
    override val maxBytes = SheetLimits.MAX_BYTES
    override val storage = StorageClass.ORDINARY
    override val auditReads = true
    override val purgeable = true

    override fun mayUpload(ownerId: String?): Boolean {
        val owner = SourceOwner.parse(ownerId) ?: return false
        return policy.allows(Permission.MEMO_IMPORT) && intake.acceptsSource(owner.batchId)
    }

    override fun mayRead(file: FileInfo): Boolean = policy.allows(Permission.MEMO_IMPORT)

    companion object {
        const val KEY = "imports.source"
    }
}

/**
 * A batch's originals as received (ADR-0055, design E06 §5.1, §10): staged in the `work` bucket (ordinary, purgeable),
 * the legacy formats accepted (OLE2 documents typed by the Tika server, RTF and TIFF), 100 MiB each, with their own upload
 * rate ([RateLimit.IMPORT_INTAKE_PER_USER]). The owner is the intake entry's id; holders of `memo.import` upload while the
 * entry is not purged and its batch is OPEN or VALIDATED, and read them.
 */
@Component
class ImportIntakePurpose(
    private val policy: Policy,
    private val intake: ImportIntake,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = ORIGINAL_TYPES
    override val maxBytes = MAX_BYTES
    override val storage = StorageClass.ORDINARY
    override val auditReads = true
    override val acceptsLegacyFormats = true
    override val purgeable = true
    override val uploadRateLimit = RateLimit.IMPORT_INTAKE_PER_USER

    override fun mayUpload(ownerId: String?): Boolean {
        val entry = ownerId?.let(ImportIntake::uuidOrNull) ?: return false
        return policy.allows(Permission.MEMO_IMPORT) && intake.acceptsUpload(entry)
    }

    override fun mayRead(file: FileInfo): Boolean = policy.allows(Permission.MEMO_IMPORT)

    companion object {
        const val KEY = "imports.intake"
        const val MAX_BYTES = 100L shl 20

        /** As the imported originals' purpose (`memo.legacy`): what is received is what may be imported. */
        val ORIGINAL_TYPES =
            setOf(
                FileType.PDF,
                FileType.PNG,
                FileType.JPEG,
                FileType.WEBP,
                FileType.TIFF,
                FileType.TEXT,
                FileType.ZIP,
                FileType.DOCX,
                FileType.XLSX,
                FileType.PPTX,
                FileType.ODT,
                FileType.ODS,
                FileType.ODP,
                FileType.DOC,
                FileType.XLS,
                FileType.PPT,
                FileType.RTF,
            )
    }
}

/**
 * The import's records (ADR-0055, design E06 §8, §10): the reconciliation report's parts and the sources used, owned by
 * the batch. Stored only by the import's jobs; read by holders of `memo.import` or `audit.read` cleared to the highest
 * level the batch writes.
 */
@Component
class ImportRecordPurpose(
    private val policy: Policy,
    private val batches: ImportBatchStore,
) : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PDF, FileType.XLSX, FileType.TEXT)
    override val maxBytes = 1L shl 30
    override val storage = StorageClass.RECORD
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean = (ActorContext.current().actor as? Actor.System)?.name == ACTOR

    override fun mayRead(file: FileInfo): Boolean {
        val batch = file.ownerId?.let(ImportIntake::uuidOrNull)?.let(batches::find) ?: return false
        val holder = policy.allows(Permission.MEMO_IMPORT) || policy.allows(Permission.AUDIT_READ)
        return holder && policy.clearanceRank() >= (batch.maxRank ?: 0)
    }

    companion object {
        const val KEY = "imports.record"

        /** The import's jobs act as this system actor (design E06 §12). */
        const val ACTOR = "legacy-import"
    }
}
