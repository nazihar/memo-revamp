package com.memo.imports.internal.report

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.Files
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportRecordPurpose
import com.memo.imports.internal.dryrun.DryRunLimits
import com.memo.imports.internal.dryrun.Finding
import com.memo.imports.internal.dryrun.FindingStore
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemSheet
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.memo.ImportedMemoRecord
import com.memo.memo.LegacyMemos
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** What the verification pass counted (design E06 §5.4), kept on the batch and in `import.batch.verified`. */
data class VerificationCounts(
    val memos: Int = 0,
    val originals: Int = 0,
    val mismatches: Int = 0,
    val problems: Int = 0,
) {
    operator fun plus(other: VerificationCounts) =
        VerificationCounts(memos + other.memos, originals + other.originals, mismatches + other.mismatches, problems + other.problems)
}

/** A stored original whose content no longer has its intake SHA-256 (`memo.legacy.mismatch`). */
private data class Mismatch(
    val memoId: UUID,
    val fileId: UUID,
    val versionId: UUID?,
    val expected: String?,
    val actual: String?,
)

/** What one row's verification found. */
private data class RowCheck(
    val findings: List<Finding>,
    val mismatches: List<Mismatch>,
    val originals: Int,
)

/**
 * The `imports-verify` job (ADR-0055, design E06 §5.4): once a batch is VERIFYING, every IMPORTED row's memo is read again
 * through `LegacyMemos` (it exists, its legacy facts are the staged row's: batch, row, row key and fingerprint) and every
 * original the import stored for it is checked: bound to that memo with the SHA-256 it had at intake, still the version
 * promoted, and its content, read back and re-hashed, still that SHA-256. What differs is a problem of stage VERIFY
 * (`VERIFY_MEMO_MISSING`, `VERIFY_FACTS_DIFFER`, `VERIFY_ORIGINAL_UNBOUND`, `FIXITY_MISMATCH`); a changed content is also
 * audited `memo.legacy.mismatch` and logged as an error for the alert. The originals' locks are lengthened to the type's
 * years again (idempotent, T7). Then `import.batch.verified` with the counts, and the report is scheduled. A retry starts
 * again from the beginning. Runs as `System("legacy-import")` with the tenant bound, outside a transaction.
 */
@Service
class ImportVerification(
    private val batches: ImportBatchStore,
    private val transitions: ImportBatchTransitions,
    private val reads: VerifyReads,
    private val legacy: LegacyMemos,
    private val files: Files,
    private val contents: FileContents,
    private val findings: FindingStore,
    private val audit: AuditLog,
    private val jobs: ImportJobs,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Verifies [batchId] once it is VERIFYING, then schedules its report; does nothing in any other state. */
    fun verify(batchId: UUID) =
        ActorContext.asSystem(ImportRecordPurpose.ACTOR) {
            val tenant = TenantContext.current()
            val batch = transactions.readInTenant(tenant) { batches.find(batchId) }
            if (batch?.state == BatchState.VERIFYING) {
                if (batch.end.verifiedAt == null) pass(batch)
                jobs.report(batchId)
            }
        }

    private fun pass(batch: ImportBatch) {
        val tenant = TenantContext.current()
        transactions.inTenant(tenant) { reads.clear(batch.id) }
        var counts = VerificationCounts()
        var after = 0
        do {
            val page = transactions.readInTenant(tenant) { reads.importedAfter(batch.id, after, ImportEndLimits.PAGE) }
            counts += page(batch, page)
            after = page.lastOrNull()?.rowNumber ?: after
        } while (page.size == ImportEndLimits.PAGE)
        transactions.inTenant(tenant) {
            if (transitions.verified(batch.id, json.writeValueAsString(counts))) {
                audit.record(
                    "import.batch.verified",
                    ImportBatches.OBJECT_TYPE,
                    batch.id.toString(),
                    after = mapOf("memos" to counts.memos, "originals" to counts.originals, "mismatches" to counts.mismatches),
                )
            }
        }
    }

    /** One page of rows: read, checked outside a transaction (content is re-hashed), recorded, and the locks lengthened. */
    private fun page(
        batch: ImportBatch,
        rows: List<ImportedRow>,
    ): VerificationCounts {
        if (rows.isEmpty()) return VerificationCounts()
        val tenant = TenantContext.current()
        val (records, originals, current) =
            transactions.readInTenant(tenant) {
                val originals = reads.originals(rows.map { it.rowId }).groupBy { it.rowId }
                val stored = originals.values.flatten().mapNotNull { it.storedFileId }
                Triple(legacy.imported(rows.map { it.memoId }), originals, files.currentVersions(stored).mapValues { it.value.id })
            }
        val checks = rows.associateWith { row -> check(batch, row, records[row.memoId], originals[row.rowId].orEmpty(), current) }
        transactions.inTenant(tenant) {
            checks.forEach { (row, check) ->
                findings.add(batch.id, row.rowId, check.findings, ProblemStage.VERIFY)
                check.mismatches.forEach(::audited)
            }
        }
        rows.forEach { legacy.retainOriginals(it.memoId) }
        return VerificationCounts(
            memos = rows.size,
            originals = checks.values.sumOf { it.originals },
            mismatches = checks.values.sumOf { it.mismatches.size },
            problems = checks.values.sumOf { it.findings.size },
        )
    }

    private fun check(
        batch: ImportBatch,
        row: ImportedRow,
        record: ImportedMemoRecord?,
        originals: List<RowOriginal>,
        current: Map<UUID, UUID>,
    ): RowCheck {
        if (record == null) return RowCheck(listOf(memoFinding(row, "VERIFY_MEMO_MISSING", emptyMap())), emptyList(), 0)
        val differing =
            listOfNotNull(
                "batch".takeIf { record.batchId != batch.id },
                "row".takeIf { record.rowNumber != row.rowNumber },
                "source".takeIf { record.sourceCode != batch.settings.sourceCode },
                "number".takeIf { record.legacyNumberKey != row.legacyNumberKey },
                "fingerprint".takeIf { record.fingerprint != row.fingerprint },
                "originals".takeIf { record.originals.size != originals.size },
            )
        val facts = if (differing.isEmpty()) emptyList() else listOf(memoFinding(row, "VERIFY_FACTS_DIFFER", mapOf("fields" to differing)))
        val files = originals.map { original(row, record, it, current) }
        return RowCheck(facts + files.mapNotNull { it.first }, files.mapNotNull { it.second }, originals.size)
    }

    /** One original: bound to the memo with its intake hash, still the promoted version, and its content unchanged. */
    private fun original(
        row: ImportedRow,
        record: ImportedMemoRecord,
        original: RowOriginal,
        current: Map<UUID, UUID>,
    ): Pair<Finding?, Mismatch?> {
        val bound = record.originals.firstOrNull { it.fileId == original.storedFileId }
        val version = original.storedFileId?.let(current::get)?.takeIf { it == original.storedVersionId }
        val actual = if (bound != null && version != null) hashOf(version) else null
        val expected = original.intakeSha256
        return when {
            bound == null -> {
                fileFinding(original, UNBOUND, mapOf("reason" to "NOT_BOUND")) to null
            }

            version == null -> {
                fileFinding(original, UNBOUND, mapOf("reason" to "VERSION")) to null
            }

            expected != null && bound.sha256 == expected && actual == expected -> {
                null to null
            }

            else -> {
                log.error("Original {} of imported memo {} does not match its intake hash", bound.fileId, row.memoId)
                fileFinding(original, "FIXITY_MISMATCH", emptyMap()) to Mismatch(row.memoId, bound.fileId, version, expected, actual)
            }
        }
    }

    private fun audited(mismatch: Mismatch) =
        audit.record(
            "memo.legacy.mismatch",
            MEMO_OBJECT,
            mismatch.memoId.toString(),
            after =
                mapOf(
                    "fileId" to mismatch.fileId.toString(),
                    "versionId" to mismatch.versionId?.toString(),
                    "expected" to mismatch.expected,
                    "actual" to mismatch.actual,
                ),
        )

    /** SHA-256 of the stored plaintext of [versionId], or null when it cannot be read at all. */
    @Suppress("TooGenericExceptionCaught") // whatever stops the read, the content is not what was imported
    private fun hashOf(versionId: UUID): String? {
        val version = transactions.readInTenant(TenantContext.current()) { files.version(versionId) } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            DigestOutputStream(OutputStream.nullOutputStream(), digest).use { contents.write(version, it) }
            HexFormat.of().formatHex(digest.digest())
        } catch (e: RuntimeException) {
            log.warn("Original version {} could not be read back", versionId, e)
            null
        }
    }

    private companion object {
        /** Audit entries about a memo name it like the memo module does. */
        const val MEMO_OBJECT = "memo"
        const val UNBOUND = "VERIFY_ORIGINAL_UNBOUND"

        fun memoFinding(
            row: ImportedRow,
            code: String,
            params: Map<String, Any>,
        ) = Finding(ProblemLevel.ERROR, code, ProblemSheet.MEMOS, row.rowNumber, "legacy_number", params)

        fun fileFinding(
            original: RowOriginal,
            code: String,
            params: Map<String, Any>,
        ) = Finding(
            ProblemLevel.ERROR,
            code,
            ProblemSheet.FILES,
            original.rowNumber,
            "path",
            params + listOfNotNull(original.path?.let { "path" to it.take(DryRunLimits.MAX_PARAM) }),
        )
    }
}
