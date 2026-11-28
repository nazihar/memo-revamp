package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileType
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.meeting.Meetings
import com.memo.memo.RecordPdf
import com.memo.memo.RecordPdfFailed
import com.memo.memo.RecordPdfPart
import com.memo.memo.RecordPdfRequest
import com.memo.memo.RecordPdfRetry
import com.memo.memo.RecordPdfs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.nio.file.Files as Paths

/**
 * Makes the PDF/A of the approved minutes for one redaction set (ADR-0066, through the memo module's [RecordPdfs]): the
 * cover, the .docx (the record's, or a reader's redacted copy of it) converted to PDF/A by the document server, and the
 * resolutions annex, with the approval stamp at the foot of every page; validated by veraPDF.
 */
@Component
class MinutesRecordMaker(
    private val pdfs: RecordPdfs,
) {
    fun make(
        c: RecordContext,
        set: RedactionSet,
        docx: Path,
        out: Path,
        key: String,
    ): RecordPdf {
        val request =
            RecordPdfRequest(
                parts =
                    buildList {
                        add(RecordPdfPart.Pages("Cover", "C-", MinutesRecordPages.cover(c, set)))
                        add(RecordPdfPart.Document("The minutes", "M-", DOCUMENT_NAME, FileType.DOCX, docx))
                        add(RecordPdfPart.Pages("Resolutions", "R-", MinutesRecordPages.annex(c, set)))
                        // E06 T8: from version 2, the approved corrections after the approved document, never inside it.
                        if (c.corrections.isNotEmpty()) {
                            add(
                                RecordPdfPart.Pages("Corrections", "K-", MinutesCorrectionPages.corrections(c, set)),
                            )
                        }
                    },
                metadata = MinutesRecordPages.metadata(c),
                textItems = MinutesRecordPages.textItems(c),
                stamp = MinutesRecordPages.stamp(c, set),
                key = key,
            )
        return pdfs.build(request, out)
    }

    /** veraPDF's verdict and what went in otherwise than as text, as the record row and the copy keep them. */
    fun validation(pdf: RecordPdf): Map<String, Any?> =
        mapOf(
            "profile" to pdf.profile,
            "validator" to pdf.validator,
            "renderer" to pdf.renderer,
            "failedRules" to pdf.failedRules,
            "error" to pdf.validationError,
            "rasterisedPages" to pdf.rasterisedPages,
            "missingGlyphs" to pdf.missingGlyphs,
            "textMissing" to pdf.textMissing,
        )

    companion object {
        /** The name the converter is given (the record's, and a copy's: the same document, redacted). */
        const val DOCUMENT_NAME = "minutes-record.docx"
    }
}

/**
 * The approved minutes' record (ADR-0066, design E06 §1.5, §9): a version is asked for in the confirmation's transaction
 * ([request]); the `minutes-record` job ([build]) reads what it is made of in one transaction, makes the PDF/A of the
 * approved .docx with its cover and resolutions annex, validates it, stores it as the system through the files pipeline as
 * a `minutes.record` file (object-locked for the committee type's retention), marks it READY and audits its SHA-256, then
 * asks for the readers' approved copies. A record veraPDF rejects is still kept, marked not compliant. A converter outage
 * is retried; after the last try, or for a refusal, the version is FAILED with a code and the secretaries are told once
 * ([RecordFailures]); the minutes' sweep and a secretary ask for it again ([RecordRetries]).
 */
@Service
class MinutesRecords(
    private val store: MinutesStore,
    private val records: MinutesRecordStore,
    private val approvals: MinutesApprovalStore,
    private val corrections: MinutesCorrectionStore,
    private val source: MinutesDraftSource,
    private val meetings: Meetings,
    private val users: UserSummaries,
    private val maker: MinutesRecordMaker,
    private val files: Files,
    private val contents: FileContents,
    private val uploads: FileUploads,
    private val copies: ApprovedCopies,
    private val failures: RecordFailures,
    private val jobs: MinutesJobs,
    private val transactions: TenantTransactions,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Asks for the next version of [row]'s record, of [approval] (in the caller's transaction); audited. */
    fun request(
        row: MinutesRow,
        approval: ApprovalRow,
    ): RecordRow {
        val next = (records.of(row.meetingId).firstOrNull()?.recordVersion ?: 0) + 1
        val record =
            RecordRow(
                id = ids.next(),
                meetingId = row.meetingId,
                forumId = row.forumId,
                recordVersion = next,
                approvalId = approval.id,
                docxVersionId = approval.docxVersionId,
                docxSha256 = approval.docxSha256,
                state = RecordState.PENDING,
                error = null,
                fileId = null,
                fileVersionId = null,
                sha256 = null,
                sizeBytes = null,
                pages = null,
                compliant = null,
                validation = null,
                createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                builtAt = null,
                version = 0,
            )
        check(records.insert(record)) { "record version $next of ${row.meetingId} exists" }
        audit.record(
            "minutes.record.requested",
            MinutesDrafts.OBJECT_TYPE,
            row.meetingId.toString(),
            after = mapOf("recordVersion" to next, "approvalId" to approval.id.toString(), "docxSha256" to approval.docxSha256),
        )
        jobs.record(row.meetingId, next)
        return record
    }

    /**
     * What [record] is made of, inside a transaction; null when it cannot be made (the minutes or an approval gone): the
     * approval it records, the chair's confirmation, and (E06 T8) the corrections approved up to its version.
     */
    fun context(record: RecordRow): RecordContext? {
        val row = store.find(record.meetingId)?.takeIf { it.state == MinutesStage.APPROVED }
        val all = row?.let { approvals.ofMeeting(record.meetingId) }.orEmpty().associateBy { it.id }
        val approval = all[record.approvalId]
        val confirmation = row?.confirmedApprovalId?.let(all::get)
        // Both approvals there, then what the minutes are made of.
        val from = approval?.let { confirmation?.let { source.read(record.meetingId) } }
        if (row == null || from == null) return null
        checkNotNull(approval)
        checkNotNull(confirmation)
        val approving = row.approvingMeetingId?.let(meetings::find)?.label
        val applied =
            corrections.of(record.meetingId).filter {
                it.state == CorrectionState.APPROVED && (it.recordVersion ?: Int.MAX_VALUE) <= record.recordVersion
            }
        val names = users.names(setOf(confirmation.actorId) + applied.mapNotNull { it.decidedBy })
        val carried =
            applied.mapNotNull { c ->
                c.approvalId?.let(all::get)?.let { a -> RecordCorrection(c, a, names[a.actorId] ?: "The chair") }
            }
        val approver = names[confirmation.actorId] ?: "The chair"
        return RecordContext(from, row, record, approval, approver, approving, confirmation, carried)
    }

    /**
     * The `minutes-record` job for version [recordVersion] of [meetingId]'s record (outside a transaction): made, stored,
     * marked READY and audited; a retry asked for by the converter is thrown back to the scheduler unless this is the
     * [last] try.
     */
    fun build(
        meetingId: UUID,
        recordVersion: Int,
        last: Boolean,
    ) {
        val tenant = TenantContext.current()
        val (record, context) =
            transactions.readInTenant(tenant) {
                records.find(meetingId, recordVersion)?.takeIf { it.state == RecordState.PENDING }?.let { r -> r to context(r) }
            } ?: return
        if (context == null) return failed(record, "SOURCE_GONE")
        val spool = Paths.createTempDirectory("minutes-record-")
        try {
            val docx = spool.resolve("minutes.docx")
            val version = checkNotNull(transactions.readInTenant(tenant) { files.version(record.docxVersionId) }) { "docx missing" }
            Paths.newOutputStream(docx).use { contents.write(version, it) }
            val out = spool.resolve("record.pdf")
            val pdf = maker.make(context, RedactionSet.FULL, docx, out, "minutes-record-${record.id}")
            val versionId = store(record, out)
            transactions.inTenant(tenant) { ready(record, versionId, pdf) }
            if (!pdf.compliant) log.warn("The record {} of the minutes of {} is not valid PDF/A", recordVersion, meetingId)
        } catch (e: RecordPdfFailed) {
            failed(record, e.code)
        } catch (e: RecordPdfRetry) {
            if (last) failed(record, e.code) else throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("The record {} of the minutes of {} was not made: {}", recordVersion, meetingId, e.javaClass.simpleName)
            if (last) failed(record, "ERROR") else throw e
        } finally {
            spool.toFile().deleteRecursively()
        }
    }

    /** Version 1 is a new `minutes.record` file; later versions are new versions of it (the earlier ones stay locked). */
    private fun store(
        record: RecordRow,
        out: Path,
    ): UUID {
        val tenant = TenantContext.current()
        val earlier =
            transactions.readInTenant(tenant) {
                records
                    .of(record.meetingId)
                    .firstOrNull { it.fileId != null && it.id != record.id }
                    ?.fileId
                    ?.let(files::info)
            }
        val key = "minutes-record:${record.id}"
        return ActorContext.asSystem(MinutesRecordPurpose.JOB) {
            if (earlier == null) {
                uploads.uploadFile(MinutesRecordPurpose.KEY, record.meetingId.toString(), FILE_NAME, out, key)
            } else {
                uploads.uploadVersionFile(earlier.id, earlier.version, FILE_NAME, out, key)
            }
        }
    }

    private fun ready(
        record: RecordRow,
        versionId: UUID,
        pdf: RecordPdf,
    ) {
        val locked = records.lock(record.id)?.takeIf { it.state == RecordState.PENDING } ?: return
        val stored = checkNotNull(files.version(versionId)) { "stored record version missing" }
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
        check(records.update(locked, after)) { "the locked record moved on" }
        audit.record(
            "minutes.record.created",
            MinutesDrafts.OBJECT_TYPE,
            record.meetingId.toString(),
            after =
                mapOf(
                    "recordVersion" to record.recordVersion,
                    "approvalId" to record.approvalId.toString(),
                    "sha256" to after.sha256,
                    "sizeBytes" to after.sizeBytes,
                    "pages" to pdf.pages,
                    "compliant" to pdf.compliant,
                    "failedRules" to pdf.failedRules.size,
                    "retainUntil" to stored.retainUntil?.toString(),
                ),
        )
        // E06 T8: a corrected version becomes the record of reference; the one before stays locked and readable.
        records
            .of(record.meetingId)
            .filter { it.recordVersion < record.recordVersion && it.state == RecordState.READY }
            .maxByOrNull { it.recordVersion }
            ?.let { earlier ->
                audit.record(
                    "minutes.record.superseded",
                    MinutesDrafts.OBJECT_TYPE,
                    record.meetingId.toString(),
                    after = mapOf("recordVersion" to earlier.recordVersion, "by" to record.recordVersion, "sha256" to earlier.sha256),
                )
            }
        // The readers' approved copies follow the record.
        store.find(record.meetingId)?.let(copies::request)
    }

    private fun failed(
        record: RecordRow,
        code: String,
    ) {
        transactions.inTenant(TenantContext.current()) {
            val locked = records.lock(record.id)?.takeIf { it.state == RecordState.PENDING } ?: return@inTenant
            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            check(records.update(locked, locked.copy(state = RecordState.FAILED, error = code.take(CODE_LENGTH), failedAt = at))) {
                "the locked record moved on"
            }
            audit.record(
                "minutes.record.failed",
                MinutesDrafts.OBJECT_TYPE,
                record.meetingId.toString(),
                after =
                    mapOf("recordVersion" to record.recordVersion, "error" to code.take(CODE_LENGTH), "retries" to locked.retries),
            )
            failures.failed(record.meetingId, record.forumId, record.recordVersion)
        }
    }

    companion object {
        const val FILE_NAME = "minutes-record.pdf"
        const val MAX_RETRIES = 5
        private const val CODE_LENGTH = 100
    }
}
