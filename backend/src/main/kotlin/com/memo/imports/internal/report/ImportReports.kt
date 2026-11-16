package com.memo.imports.internal.report

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileRenditions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportRecordPurpose
import com.memo.memo.ReportPages
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import kotlin.io.path.inputStream
import java.nio.file.Files as NioFiles

/** A part of the report, or a source kept beside it, as stored (purpose `imports.record`, owner the batch). */
data class ReportPart(
    /** SUMMARY, DETAIL, MANIFEST, or SOURCE_ and the table (WORKBOOK, MEMOS, FILES, APPROVALS). */
    val kind: String,
    val fileId: UUID,
    val versionId: UUID,
    val name: String,
    /** Lower-case hex. */
    val sha256: String,
    val size: Long,
)

/** The report as the batch keeps it (`imports_batch.report`): never a subject or a remark. */
data class StoredReport(
    val balanced: Boolean,
    val complete: Boolean,
    val valid: Boolean,
    val failing: List<String>,
    val totals: ReportTotals,
    val parts: List<ReportPart>,
)

/**
 * The `imports-report` job (ADR-0055, design E06 §5.4, §8): once the batch is verified, it waits (looking again every
 * [ImportEndLimits.REPORT_WAIT]) until no rendition of an imported original is pending, or [ImportEndLimits.REPORT_MAX_WAIT]
 * after the import finished, whichever is first. Then it keeps each source used as a record (`copyFrom`, the chain of
 * custody), builds the summary PDF, the workbook and the manifest, stores them as records of the batch (`imports.record`),
 * and moves the batch to COMPLETED with the report, audited `import.batch.completed` with each part's SHA-256 and the
 * balance. Stored once: a part's idempotency key carries its content's hash, so a retry stores nothing twice. Runs as
 * `System("legacy-import")` with the tenant bound, outside a transaction.
 */
@Service
class ImportReports(
    private val batches: ImportBatchStore,
    private val transitions: ImportBatchTransitions,
    private val facts: ReportFactsBuilder,
    private val queries: ReportQueries,
    private val pages: ReportPages,
    private val workbook: ReportWorkbook,
    private val manifest: ReportManifest,
    private val renditions: FileRenditions,
    private val uploads: FileUploads,
    private val files: Files,
    private val audit: AuditLog,
    private val jobs: ImportJobs,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** One look at [batchId]'s report: the delay before the next, or null when it is stored (or there is nothing to do). */
    fun step(batchId: UUID) =
        ActorContext.asSystem(ImportRecordPurpose.ACTOR) {
            val batch = read { batches.find(batchId) }
            when {
                batch == null || batch.state != BatchState.VERIFYING -> {
                    null
                }

                batch.end.verifiedAt == null -> {
                    jobs.verify(batchId)
                    null
                }

                waiting(batch) -> {
                    ImportEndLimits.REPORT_WAIT
                }

                else -> {
                    build(batch)
                    null
                }
            }
        }

    /** Renditions of the imported originals are still pending, and the cap after the import finished is not reached. */
    private fun waiting(batch: ImportBatch): Boolean {
        val cap = (batch.run.finishedAt ?: batch.end.verifiedAt ?: Instant.now(clock)).plus(ImportEndLimits.REPORT_MAX_WAIT)
        if (!Instant.now(clock).isBefore(cap)) return false
        val versions = read { queries.importedVersions(batch.id) }
        return versions.chunked(STATES_CHUNK).any { chunk -> read { renditions.pendingCount(chunk) } > 0 }
    }

    private fun build(batch: ImportBatch) {
        val sources = sources(batch)
        val versions = read { queries.importedVersions(batch.id) }
        val states =
            versions.chunked(STATES_CHUNK).fold(emptyMap<UUID, Map<RenditionKind, RenditionState>>()) { all, chunk ->
                all + read { renditions.states(chunk) }
            }
        val built = read { facts.of(batch, versions, states, sources) }
        val spool = NioFiles.createTempDirectory("import-report-")
        try {
            val summary = spool.resolve(SUMMARY_NAME)
            val pdf = ByteArrayOutputStream().also { pages.render(ReportSummary.document(batch.settings.name, built), it) }
            NioFiles.write(summary, pdf.toByteArray())
            val detail = spool.resolve(DETAIL_NAME)
            workbook.write(batch, built, states, detail)
            val list = spool.resolve(MANIFEST_NAME)
            val lines = manifest.write(batch, list)
            val parts =
                listOfNotNull(
                    store(batch, "SUMMARY", summary, SUMMARY_NAME),
                    store(batch, "DETAIL", detail, DETAIL_NAME),
                    if (lines > 0) store(batch, "MANIFEST", list, MANIFEST_NAME) else null,
                )
            complete(batch, built, parts + sources)
        } finally {
            spool.toFile().deleteRecursively()
        }
    }

    /** Each source the dry run read, kept as a record of the batch (the chain of custody, design E06 §5.4). */
    private fun sources(batch: ImportBatch): List<ReportPart> =
        batch.sources.map { source ->
            val version = checkNotNull(read { files.version(source.versionId) }) { "source version ${source.versionId}" }
            val stored =
                uploads.copyFrom(
                    source.versionId,
                    ImportRecordPurpose.KEY,
                    batch.id.toString(),
                    "imp-${batch.id}-source-${source.table.name}",
                    version.sha256,
                )
            part("SOURCE_${source.table.name}", stored)
        }

    /** Stores [file] as a record of [batch]; the key names the part and its content, so a retry finds the same version. */
    private fun store(
        batch: ImportBatch,
        kind: String,
        file: Path,
        name: String,
    ): ReportPart {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        val hash = HexFormat.of().formatHex(digest.digest())
        val key = "imp-${batch.id}-${kind.lowercase()}-${hash.take(KEY_HASH)}"
        return part(kind, uploads.uploadFile(ImportRecordPurpose.KEY, batch.id.toString(), name, file, key))
    }

    private fun part(
        kind: String,
        versionId: UUID,
    ): ReportPart {
        val version = checkNotNull(read { files.version(versionId) }) { "report version $versionId" }
        return ReportPart(kind, version.fileId, version.id, version.name, version.sha256, version.sizeBytes)
    }

    /** VERIFYING → COMPLETED with the report, audited with each part's hash and the balance. */
    private fun complete(
        batch: ImportBatch,
        built: ReportFacts,
        parts: List<ReportPart>,
    ) {
        val b = built.balance
        val report = StoredReport(b.balanced, b.complete, b.valid, b.failing, built.totals, parts)
        transactions.inTenant(TenantContext.current()) {
            if (transitions.complete(batch.id, json.writeValueAsString(report))) {
                audit.record(
                    "import.batch.completed",
                    ImportBatches.OBJECT_TYPE,
                    batch.id.toString(),
                    before = mapOf("state" to BatchState.VERIFYING.name),
                    after =
                        mapOf(
                            "state" to BatchState.COMPLETED.name,
                            "balanced" to b.balanced,
                            "failing" to b.failing,
                            "complete" to b.complete,
                            "valid" to b.valid,
                            "rows" to rows(built.totals.rows),
                            "parts" to
                                parts.map {
                                    mapOf(
                                        "kind" to it.kind,
                                        "versionId" to it.versionId.toString(),
                                        "sha256" to it.sha256,
                                    )
                                },
                        ),
                )
            }
        }
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    companion object {
        /** The rows line for the audit entry: counts only. */
        fun rows(r: RowTotals): Map<String, Int> =
            mapOf(
                "read" to r.read,
                "imported" to r.imported,
                "skipped" to r.skipped,
                "rejected" to r.rejected,
                "failed" to r.failed,
                "notStarted" to r.notStarted,
            )

        const val SUMMARY_NAME = "report-summary.pdf"
        const val DETAIL_NAME = "report-detail.xlsx"
        const val MANIFEST_NAME = "manifest-sha256.txt"
        private const val STATES_CHUNK = 5_000
        private const val KEY_HASH = 16
    }
}
