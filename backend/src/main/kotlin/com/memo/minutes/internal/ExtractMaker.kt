package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.memo.RecordPdf
import com.memo.memo.RecordPdfFailed
import com.memo.memo.RecordPdfPart
import com.memo.memo.RecordPdfRequest
import com.memo.memo.RecordPdfRetry
import com.memo.memo.RecordPdfs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.nio.file.Files as Paths

/**
 * The `minutes-extract` job (design E06 §1.6.6, §9): makes a certified resolution extract as one PDF/A page through the
 * memo module's [RecordPdfs], stores it as the system as a `minutes.extract` record (object-locked), marks it READY and
 * audits its SHA-256; a resolution corrected since it was certified, or gone, fails it with a code.
 */
@Service
class ExtractMaker(
    private val store: MinutesExtractStore,
    private val extracts: ResolutionExtracts,
    private val certification: ExtractCertification,
    private val evidence: ApprovalEvidence,
    private val pdfs: RecordPdfs,
    private val maker: MinutesRecordMaker,
    private val files: Files,
    private val uploads: FileUploads,
    private val users: UserSummaries,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The `minutes-extract` job for [extractId] (outside a transaction): what it certifies read again and checked against the
     * certification (a decision corrected since: FAILED `CHANGED`), made as PDF/A, stored as the system, marked READY and
     * audited `minutes.extract.issued`; a converter outage is thrown back to the scheduler unless this is the [last] try.
     */
    fun build(
        extractId: UUID,
        last: Boolean,
    ) {
        val tenant = TenantContext.current()
        val (row, content, issuer) =
            transactions.readInTenant(tenant) {
                store.find(extractId)?.takeIf { it.state == RecordState.PENDING }?.let { r ->
                    Triple(r, extracts.content(r.resolutionId), users.names(setOf(r.issuedBy))[r.issuedBy].orEmpty())
                }
            } ?: return
        val problem =
            when {
                content == null -> "SOURCE_GONE"
                certification.certificationHash(row, certification.contentSha256(content)) != row.certificationHash -> "CHANGED"
                else -> null
            }
        if (problem != null) return failed(row, problem)
        checkNotNull(content)
        val spool = Paths.createTempDirectory("minutes-extract-")
        try {
            val out = spool.resolve("extract.pdf")
            val pdf = pdfs.build(request(row, content, issuer), out)
            val versionId = store(row, out)
            transactions.inTenant(tenant) { ready(row, versionId, pdf) }
        } catch (e: RecordPdfFailed) {
            failed(row, e.code)
        } catch (e: RecordPdfRetry) {
            if (last) failed(row, e.code) else throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("The extract {} was not made: {}", extractId, e.javaClass.simpleName)
            if (last) failed(row, "ERROR") else throw e
        } finally {
            spool.toFile().deleteRecursively()
        }
    }

    private fun request(
        row: ExtractRow,
        content: ExtractContent,
        issuer: String,
    ): RecordPdfRequest {
        val pages = ExtractPages.of(row, content, issuer, evidence.factors(row.certification["authentication"]))
        return RecordPdfRequest(
            parts = listOf(RecordPdfPart.Pages("Certified extract", "E-", pages)),
            metadata = ExtractPages.metadata(row, content),
            textItems = listOf(content.committee, content.number),
            stamp = ExtractPages.stamp(row, content, issuer),
            key = "minutes-extract-${row.id}",
        )
    }

    private fun store(
        row: ExtractRow,
        out: Path,
    ): UUID =
        ActorContext.asSystem(MinutesExtractPurpose.JOB) {
            uploads.uploadFile(MinutesExtractPurpose.KEY, row.meetingId.toString(), FILE_NAME, out, "minutes-extract:${row.id}")
        }

    private fun ready(
        row: ExtractRow,
        versionId: UUID,
        pdf: RecordPdf,
    ) {
        val locked = store.lock(row.id)?.takeIf { it.state == RecordState.PENDING } ?: return
        val stored = checkNotNull(files.version(versionId)) { "stored extract missing" }
        val after =
            locked.copy(
                state = RecordState.READY,
                fileId = stored.fileId,
                fileVersionId = stored.id,
                sha256 = stored.sha256.lowercase(),
                sizeBytes = stored.sizeBytes,
                pages = pdf.pages,
                compliant = pdf.compliant,
                validation = maker.validation(pdf),
                builtAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        check(store.update(locked, after)) { "the locked extract moved on" }
        audit.record(
            "minutes.extract.issued",
            DecisionEffects.OBJECT_TYPE,
            row.meetingId.toString(),
            after =
                mapOf(
                    "extractId" to row.id.toString(),
                    "resolutionId" to row.resolutionId.toString(),
                    "sha256" to after.sha256,
                    "pages" to pdf.pages,
                    "compliant" to pdf.compliant,
                    "certificationHash" to row.certificationHash,
                    "retainUntil" to stored.retainUntil?.toString(),
                ),
        )
    }

    private fun failed(
        row: ExtractRow,
        code: String,
    ) {
        transactions.inTenant(TenantContext.current()) {
            val locked = store.lock(row.id)?.takeIf { it.state == RecordState.PENDING } ?: return@inTenant
            check(store.update(locked, locked.copy(state = RecordState.FAILED, error = code.take(CODE_LENGTH)))) {
                "the locked extract moved on"
            }
            audit.record(
                "minutes.extract.failed",
                DecisionEffects.OBJECT_TYPE,
                row.meetingId.toString(),
                after = mapOf("extractId" to row.id.toString(), "error" to code.take(CODE_LENGTH)),
            )
        }
    }

    companion object {
        const val FILE_NAME = "resolution-extract.pdf"
        const val MAX_RETRIES = 5
        private const val CODE_LENGTH = 100
    }
}
