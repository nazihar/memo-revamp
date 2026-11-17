package com.memo.imports.internal

import com.memo.audit.AuditLog
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.FileUploadRejected
import com.memo.files.FileVersionCreated
import com.memo.files.Files
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.UUID

/** A path to register, with the size the client sees on disk. */
data class PathToRegister(
    val path: String?,
    val size: Long?,
)

/**
 * The intake of a batch's metadata and originals (ADR-0055, design E06 §5.1). Sources and originals arrive through the
 * files API (`imports.source`, `imports.intake`), each scanned, typed, encrypted and hashed by the pipeline; the
 * listeners bind them to the batch in the upload's transaction, and record a refusal with its reason. Registration of
 * paths and the intake list serve the browser's folder upload and its resume. Inside a tenant transaction.
 */
@Service
class ImportIntake(
    private val batches: ImportBatchStore,
    private val entries: ImportFileStore,
    private val staged: StagedVersionStore,
    private val files: Files,
    private val audit: AuditLog,
) {
    /**
     * Registers [paths] (1 to [MAX_PER_CALL]) as EXPECTED; paths the batch already holds keep their state. Answers each
     * path's entry in the order given. Refused as a whole for an invalid or repeated path, or past the batch's limits.
     */
    fun register(
        batchId: UUID,
        paths: List<PathToRegister>,
    ): List<IntakeEntry> {
        val normalised = checked(paths)
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        refuseIf(!batch.state.editable, "WRONG_STATE") { "Originals can be added only before the import starts." }
        val known = entries.byPaths(batchId, normalised.keys)
        val added = normalised.filterKeys { it !in known }
        val counts = entries.counts(batchId).values
        val files = counts.sumOf { it.files } + added.size
        val bytes = counts.sumOf { it.bytes } + added.values.sum()
        if (files > MAX_ORIGINALS || bytes > MAX_BATCH_BYTES) {
            throw ImportRejectedException(
                listOf(FieldProblem("files", "LIMIT_EXCEEDED", "A batch holds at most $MAX_ORIGINALS originals and 200 GiB.")),
            )
        }
        entries.register(batchId, added)
        if (added.isNotEmpty()) {
            audit.record(
                "import.originals.registered",
                ImportBatches.OBJECT_TYPE,
                batchId.toString(),
                after = mapOf("added" to added.size, "known" to known.size, "bytes" to added.values.sum()),
            )
        }
        val all = entries.byPaths(batchId, normalised.keys)
        return normalised.keys.map { all.getValue(it) }
    }

    fun page(
        batchId: UUID,
        states: Set<IntakeState>,
        after: Pair<IntakeState, String>?,
        limit: Int,
    ): List<IntakeEntry> {
        if (batches.find(batchId) == null) throw ImportBatchNotFoundException()
        return entries.page(batchId, states, after, limit)
    }

    /** Whether an original may be uploaded for [intakeId] now: its entry exists and its batch is OPEN or VALIDATED. */
    fun acceptsUpload(intakeId: UUID): Boolean {
        val entry = entries.find(intakeId) ?: return false
        return entry.state != IntakeState.PURGED && batches.find(entry.batchId)?.state?.editable == true
    }

    /** Whether a source may be uploaded for [batchId] now (OPEN or VALIDATED). */
    fun acceptsSource(batchId: UUID): Boolean = batches.find(batchId)?.state?.editable == true

    /**
     * Binds a stored source or original to its batch, in the upload's transaction. A batch that left OPEN and VALIDATED
     * meanwhile vetoes the upload (it rolls back). A replaced version is marked superseded, for purging.
     */
    @EventListener
    fun on(event: FileVersionCreated) {
        when (event.purposeKey) {
            ImportSourcePurpose.KEY -> source(event)
            ImportIntakePurpose.KEY -> original(event)
        }
    }

    /** The pipeline refused an original: its entry is REFUSED with the reason (and the scanner's signature). */
    @EventListener
    fun on(event: FileUploadRejected) {
        if (event.purposeKey != ImportIntakePurpose.KEY) return
        val entry = event.ownerId?.let(::uuidOrNull)?.let(entries::lock) ?: return
        entry.versionId?.let(staged::supersede)
        entries.refuse(entry.id, event.reasonCode, event.signatureName)
        // Only a refusal that replaces a received original changes what a dry run would read.
        if (entry.versionId != null) batches.reopen(entry.batchId)
    }

    private fun source(event: FileVersionCreated) {
        val owner = SourceOwner.parse(event.ownerId) ?: error("not a source owner")
        val batch = batches.lock(owner.batchId) ?: throw ImportBatchNotFoundException()
        check(batch.state.editable) { "batch ${batch.id} no longer takes sources" }
        val version = checkNotNull(files.version(event.versionId)) { "version ${event.versionId}" }
        // The table's earlier source, and the other kind's (a workbook replaces the CSV files and the reverse), are unused.
        batch.sources
            .filter { it.table == owner.table || it.table.kind != owner.table.kind }
            .forEach { staged.supersede(it.versionId) }
        check(batches.attach(batch.id, BatchSource(owner.table, event.fileId, event.versionId))) { "batch ${batch.id} changed state" }
        staged.add(batch.id, event.versionId, StagedKind.SOURCE)
        audit.record(
            "import.source.attached",
            ImportBatches.OBJECT_TYPE,
            batch.id.toString(),
            after =
                mapOf(
                    "table" to owner.table.name,
                    "fileId" to event.fileId.toString(),
                    "versionId" to event.versionId.toString(),
                    "sha256" to version.sha256,
                    "sizeBytes" to version.sizeBytes,
                ),
        )
    }

    private fun original(event: FileVersionCreated) {
        val entry = event.ownerId?.let(::uuidOrNull)?.let(entries::lock) ?: error("not an intake entry")
        val batch = batches.lock(entry.batchId) ?: throw ImportBatchNotFoundException()
        check(batch.state.editable) { "batch ${batch.id} no longer takes originals" }
        val version = checkNotNull(files.version(event.versionId)) { "version ${event.versionId}" }
        entry.versionId?.let(staged::supersede)
        entries.receive(entry.id, ReceivedVersion(event.fileId, event.versionId, version.sha256, version.mediaType, version.sizeBytes))
        staged.add(batch.id, event.versionId, StagedKind.ORIGINAL)
        batches.reopen(batch.id)
    }

    private fun checked(paths: List<PathToRegister>): LinkedHashMap<String, Long> {
        val problems = mutableListOf<FieldProblem>()
        if (paths.isEmpty() || paths.size > MAX_PER_CALL) {
            problems += FieldProblem("files", "COUNT", "Send 1 to $MAX_PER_CALL paths at a time.")
        }
        val normalised = LinkedHashMap<String, Long>()
        paths.take(MAX_PER_CALL).forEachIndexed { i, given ->
            val path = given.path?.let(PathRules::normalise)
            val size = given.size?.takeIf { it >= 0 }
            when {
                path == null -> problems += FieldProblem("files[$i].path", "PATH_INVALID", "Not a relative path inside the folder.")
                path in normalised -> problems += FieldProblem("files[$i].path", "PATH_DUPLICATE", "The same path is listed twice.")
                size == null -> problems += FieldProblem("files[$i].size", "SIZE_INVALID", "A size of 0 bytes or more.")
                else -> normalised[path] = size
            }
        }
        if (problems.isNotEmpty()) throw ImportRejectedException(problems.take(MAX_PROBLEMS))
        return normalised
    }

    companion object {
        const val MAX_PER_CALL = 1_000
        const val MAX_ORIGINALS = 200_000
        const val MAX_BATCH_BYTES = 200L shl 30
        private const val MAX_PROBLEMS = 100

        fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
    }
}

/** The owner of a source upload: `{batchId}:{table}` (design E06 §5.1). */
data class SourceOwner(
    val batchId: UUID,
    val table: SourceTable,
) {
    companion object {
        fun parse(owner: String?): SourceOwner? {
            val parts = owner?.split(':')?.takeIf { it.size == 2 } ?: return null
            val batch = ImportIntake.uuidOrNull(parts[0])
            val table = SourceTable.entries.firstOrNull { it.name == parts[1] }
            return if (batch != null && table != null) SourceOwner(batch, table) else null
        }
    }
}
