package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.memo.RecordPdfFailed
import com.memo.memo.RecordPdfRetry
import com.memo.templating.DocxControls
import com.memo.templating.DocxCopies
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.nio.file.Files as Paths

/** What a reader of approved minutes reads (design E06 §3 `…/minutes/record`): a stored file, or nothing yet. */
sealed interface RecordLookup {
    /** The record itself (an empty set) or the reader's approved copy: [fileVersionId] to stream. */
    data class Ready(
        val record: RecordRow,
        val fileVersionId: UUID,
        val sha256: String,
        val copy: CopyRow?,
    ) : RecordLookup

    /** The reader's copy is being made: 202, try again. */
    data object Building : RecordLookup

    /** The record version is not made (yet, or it failed): 409 `RECORD_PENDING`. */
    data class Pending(
        val record: RecordRow?,
    ) : RecordLookup
}

/**
 * The readers' copies of the approved minutes (ADR-0066): for each distinct non-empty redaction set among the readers, a
 * PDF/A record of its own made from the approved .docx with the set's items replaced ([CopyBlocks.redacted], checked for
 * leaks), its cover naming the redacted items, the annex as the reader may read it and the approval stamp on every page
 * (where the draft's copies have "DRAFT"); the PDF's own text is checked for leaks again; stored as the record's job in
 * record storage (`minutes.approved-copy`, locked) and audited. Built by the `minutes-copies` job once the record is made,
 * and for a new reader's set when they first ask. A reader with an empty set reads the record itself.
 */
@Service
class ApprovedCopies(
    private val store: MinutesStore,
    private val records: MinutesRecordStore,
    private val copyStore: MinutesCopyStore,
    private val people: MinutesPeople,
    @param:Lazy private val minutesRecords: MinutesRecords,
    private val maker: MinutesRecordMaker,
    private val files: Files,
    private val contents: FileContents,
    private val uploads: FileUploads,
    private val controls: DocxControls,
    private val packages: DocxCopies,
    private val jobs: MinutesJobs,
    private val transactions: TenantTransactions,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Asks for [row]'s approved copies to be built (in the caller's transaction). */
    fun request(row: MinutesRow) {
        if (row.state != MinutesStage.APPROVED) return
        val locked = checkNotNull(store.lock(row.meetingId))
        val after = locked.copy(copiesSeq = locked.copiesSeq + 1)
        check(store.update(locked, after)) { "the locked minutes moved on" }
        jobs.copies(row.meetingId, after.copiesSeq)
    }

    /**
     * What [set]'s reader reads of version [recordVersion] (the current one when null) of [row]'s record, inside a
     * transaction: the record for an empty set, else their built copy, else one asked for now.
     */
    fun lookup(
        row: MinutesRow,
        set: RedactionSet,
        recordVersion: Int? = null,
    ): RecordLookup {
        val versions = records.of(row.meetingId)
        val record = recordVersion?.let { n -> versions.firstOrNull { it.recordVersion == n } ?: throw MinutesNotFoundException("record") }
        // The current version is the newest made one (E06 T8: version 1 stays what everyone reads while version 2 is made).
        val chosen = record ?: versions.firstOrNull { it.state == RecordState.READY } ?: versions.firstOrNull()
        return when {
            chosen == null || chosen.state != RecordState.READY -> RecordLookup.Pending(chosen)
            set.redacted.isEmpty() -> RecordLookup.Ready(chosen, checkNotNull(chosen.fileVersionId), checkNotNull(chosen.sha256), null)
            else -> copyOf(row, chosen, set)
        }
    }

    /** [set]'s built copy of [record], or one asked for now. */
    private fun copyOf(
        row: MinutesRow,
        record: RecordRow,
        set: RedactionSet,
    ): RecordLookup {
        val copy = copyStore.ofRecord(row.meetingId, record.recordVersion).firstOrNull { it.setKey == set.key }
        if (copy == null && copyStore.insert(pending(record, set))) request(row)
        return if (copy?.state == CopyState.DONE) {
            RecordLookup.Ready(record, checkNotNull(copy.fileVersionId), checkNotNull(copy.sha256), copy)
        } else {
            RecordLookup.Building
        }
    }

    /**
     * The approved copies of [meetingId]'s current record (the `minutes-copies` job, outside a transaction): the readers'
     * non-empty sets recorded (past [MinutesCopies.MAX_COPIES] none is, audited), then each not built yet is built.
     */
    fun build(
        meetingId: UUID,
        last: Boolean,
    ) {
        val tenant = TenantContext.current()
        val plan = transactions.inTenant(tenant) { plan(meetingId) }?.takeIf { it.first.isNotEmpty() } ?: return
        val (open, context) = plan
        val docx = ByteArrayOutputStream()
        val version = checkNotNull(transactions.readInTenant(tenant) { files.version(context.record.docxVersionId) }) { "docx missing" }
        contents.write(version, docx)
        var retry: RuntimeException? = null
        open.forEach { copy ->
            try {
                buildOne(copy, context, docx.toByteArray())
            } catch (e: CopyFailed) {
                transactions.inTenant(tenant) { failed(copy, e.code, e.items) }
            } catch (e: RecordPdfFailed) {
                transactions.inTenant(tenant) { failed(copy, e.code, emptyList()) }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                // A converter outage (RecordPdfRetry) or anything unexpected: tried again, FAILED on the last try.
                log.warn("Approved copy {} of the minutes of {} not built: {}", copy.id, meetingId, e.javaClass.simpleName)
                val code = (e as? RecordPdfRetry)?.code ?: "ERROR"
                if (last) transactions.inTenant(tenant) { failed(copy, code, emptyList()) } else retry = e
            }
        }
        retry?.let { throw it }
    }

    /** The copies to build for the current READY record and what they are made of; null when there is nothing to do. */
    private fun plan(meetingId: UUID): Pair<List<CopyRow>, RecordContext>? {
        val row = store.find(meetingId)?.takeIf { it.state == MinutesStage.APPROVED }
        val record = row?.let { records.of(meetingId).firstOrNull()?.takeIf { it.state == RecordState.READY } }
        val context = record?.let(minutesRecords::context)
        if (row == null || record == null || context == null) return null
        val sets = sets(row).filter { it.redacted.isNotEmpty() }
        if (sets.size > MinutesCopies.MAX_COPIES) {
            log.warn("The approved minutes of {} need {} copies: none is built past {}", meetingId, sets.size, MinutesCopies.MAX_COPIES)
            audit.record("minutes.copies.refused", MinutesDrafts.OBJECT_TYPE, meetingId.toString(), after = mapOf("sets" to sets.size))
        } else {
            sets.forEach { copyStore.insert(pending(record, it)) }
        }
        return copyStore.ofRecord(meetingId, record.recordVersion).filter { it.state == CopyState.PENDING } to context
    }

    /** The distinct redaction sets of the approved minutes' readers today. */
    private fun sets(row: MinutesRow): Set<RedactionSet> = people.readers(row).map { (user, kind) -> people.setOf(row, user, kind) }.toSet()

    /** [copy] redacted, made into a PDF/A, its text checked, stored and marked built. */
    private fun buildOne(
        copy: CopyRow,
        context: RecordContext,
        docx: ByteArray,
    ) {
        val tenant = TenantContext.current()
        val set = RedactionSet(copy.redacted)
        val redacted = CopyBlocks.redacted(docx, copy.redacted, context.source, controls, packages)
        val spool = Paths.createTempDirectory("minutes-approved-copy-")
        try {
            val file = spool.resolve("copy.docx").also { Paths.write(it, redacted) }
            val out = spool.resolve("copy.pdf")
            val pdf = maker.make(context, set, file, out, "minutes-approved-copy-${copy.id}")
            // The PDF itself (cover, annex, converted text) names nothing of a redacted item either.
            CopyBlocks.requireNoLeak(context.source, set, text(out))
            val versionId =
                ActorContext.asSystem(MinutesRecordPurpose.JOB) {
                    uploads.uploadFile(
                        ApprovedCopyPurpose.KEY,
                        copy.meetingId.toString(),
                        FILE_NAME,
                        out,
                        "minutes-approved-copy:${copy.id}",
                    )
                }
            transactions.inTenant(tenant) { finish(copy, versionId, pdf) }
        } finally {
            spool.toFile().deleteRecursively()
        }
    }

    private fun text(pdf: Path): String =
        Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { PDFTextStripper().getText(it) }

    private fun finish(
        copy: CopyRow,
        versionId: UUID,
        pdf: com.memo.memo.RecordPdf,
    ) {
        val locked = copyStore.lock(copy.id)?.takeIf { it.state == CopyState.PENDING } ?: return
        val version = checkNotNull(files.version(versionId))
        val after =
            locked.copy(
                state = CopyState.DONE,
                fileId = version.fileId,
                fileVersionId = version.id,
                sha256 = version.sha256.lowercase(),
                builtAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                compliant = pdf.compliant,
                validation = maker.validation(pdf),
            )
        check(copyStore.update(locked, after)) { "the locked copy moved on" }
        audit.record(
            "minutes.copy.built",
            MinutesDrafts.OBJECT_TYPE,
            copy.meetingId.toString(),
            after =
                mapOf(
                    "copyId" to copy.id.toString(),
                    "stage" to copy.stage.name,
                    "recordVersion" to copy.recordVersion,
                    "setKey" to copy.setKey,
                    "redactedItems" to copy.redacted.size,
                    "sourceVersionId" to copy.sourceVersionId.toString(),
                    "sha256" to after.sha256,
                    "compliant" to pdf.compliant,
                ),
        )
    }

    private fun failed(
        copy: CopyRow,
        code: String,
        items: List<String>,
    ) {
        val locked = copyStore.lock(copy.id)?.takeIf { it.state == CopyState.PENDING } ?: return
        check(
            copyStore.update(locked, locked.copy(state = CopyState.FAILED, error = code.take(CODE_LENGTH))),
        ) { "the locked copy moved on" }
        audit.record(
            "minutes.copy.failed",
            MinutesDrafts.OBJECT_TYPE,
            copy.meetingId.toString(),
            after =
                mapOf(
                    "copyId" to copy.id.toString(),
                    "stage" to copy.stage.name,
                    "setKey" to copy.setKey,
                    "error" to code.take(CODE_LENGTH),
                    "items" to items,
                ),
        )
    }

    private fun pending(
        record: RecordRow,
        set: RedactionSet,
    ) = CopyRow(
        id = ids.next(),
        meetingId = record.meetingId,
        stage = CopyStage.APPROVED,
        redacted = set.redacted,
        setKey = set.key,
        sourceVersionId = record.docxVersionId,
        state = CopyState.PENDING,
        error = null,
        fileId = null,
        fileVersionId = null,
        sha256 = null,
        createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        builtAt = null,
        version = 0,
        recordVersion = record.recordVersion,
    )

    private companion object {
        const val FILE_NAME = "minutes-copy.pdf"
        const val CODE_LENGTH = 100
    }
}
