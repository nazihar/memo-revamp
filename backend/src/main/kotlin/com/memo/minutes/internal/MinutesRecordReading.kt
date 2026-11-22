package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.files.FileContents
import com.memo.files.Files
import com.memo.meeting.MeetingView
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** One version of the approved minutes' record as its reader sees it (design E06 §3 `GET …/minutes/records`). */
data class MinutesRecordResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Int,
    /** `PENDING`, `READY` or `FAILED`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: String,
    /** The authenticated approval it records (the stamps' `id`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approvalId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    val builtAt: Instant?,
    /** What the viewer reads of it: the record itself, or their copy; null while it is not made. */
    val file: MinutesRecordFile?,
    /** The items redacted in the viewer's copy, by number; empty when they read the record itself. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val redacted: List<MinutesRedactedItem>,
    /** Whether this is the record of reference (the newest version). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val current: Boolean,
)

/** The file the viewer reads: its SHA-256 (to check a download against) and veraPDF's verdict (PDF/A-2u). */
data class MinutesRecordFile(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sha256: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val compliant: Boolean,
    /** Whether it is the record itself, or a reader's copy. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val full: Boolean,
)

data class MinutesRedactedItem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemNumber: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: RedactionKind,
)

/** What a check of a record version found (`RECORD_CHANGED`, `CHAIN_BROKEN`, `DOCUMENT_*`, `FILE_*`, `NOT_READY`). */
data class MinutesRecordCheckResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val matches: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<MinutesCheckProblem>,
)

/** A reader of approved minutes and the redaction set of what they read. */
data class ApprovedReader(
    val view: MeetingView,
    val row: MinutesRow,
    val set: RedactionSet,
)

/** What the viewer streams: a file version, with what the open's audit says. */
data class RecordServe(
    val meetingId: UUID,
    val fileVersionId: UUID,
    val details: Map<String, Any?>,
)

/**
 * The approved minutes' record as its readers read it (ADR-0066, design E06 §1.9, §3): the committee's officers and
 * `forums.manage`, the readers of the minutes, and `forum.read` holders who see the meeting (404 for anyone else, and
 * before approval); each reads the record itself when they may read every item, else their own approved copy (a record
 * of its own). The versions list, the file to stream (the current version or one named), and the check: the approval's
 * hashes and chain ([MinutesConfirmation.check]), and the approved .docx and the viewer's file re-hashed from storage.
 */
@Component
class MinutesRecordReading(
    private val store: MinutesStore,
    private val reading: MinutesReading,
    private val people: MinutesPeople,
    private val records: MinutesRecordStore,
    private val copies: ApprovedCopies,
    private val confirmation: MinutesConfirmation,
    private val files: Files,
    private val contents: FileContents,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
) {
    /** The actor as a reader of [meetingId]'s approved minutes; 404 otherwise. Inside a transaction. */
    fun approvedReader(meetingId: UUID): ApprovedReader {
        val view = reading.actorView(meetingId)
        val row = store.find(meetingId)?.takeIf { it.state == MinutesStage.APPROVED } ?: throw MinutesNotFoundException("minutes")
        val reader = reading.readerOf(view, row)
        val byRole = view.manages || view.officer || view.reader
        if (reader == null && !byRole) throw MinutesNotFoundException("minutes")
        return ApprovedReader(view, row, reader?.let(reading::ownSet) ?: people.setOf(view))
    }

    /**
     * What the actor streams of [meetingId]'s approved minutes (inside a transaction): version [recordVersion] (the current
     * one when null) as the record or their copy; null (202) while their copy is being made, 409 `RECORD_PENDING`
     * while the record is not made.
     */
    fun serve(
        meetingId: UUID,
        recordVersion: Int?,
    ): RecordServe? {
        val reader = approvedReader(meetingId)
        return when (val lookup = copies.lookup(reader.row, reader.set, recordVersion)) {
            is RecordLookup.Ready -> {
                RecordServe(
                    meetingId,
                    lookup.fileVersionId,
                    mapOf(
                        "stage" to CopyStage.APPROVED.name,
                        "recordVersion" to lookup.record.recordVersion,
                        "setKey" to reader.set.key,
                        "full" to (lookup.copy == null),
                        "sourceVersionId" to lookup.record.docxVersionId.toString(),
                    ),
                )
            }

            RecordLookup.Building -> {
                null
            }

            is RecordLookup.Pending -> {
                throw RefusedException("RECORD_PENDING", "The approved minutes are being kept as the record; try again in a moment.")
            }
        }
    }

    /** The record's versions, the newest first, as the actor reads them (inside a transaction). */
    fun versions(meetingId: UUID): List<MinutesRecordResponse> {
        val reader = approvedReader(meetingId)
        val versions = records.of(meetingId)
        val numbers =
            reader.view.agenda.items
                .associate { it.id to it.number.orEmpty() }
        val redacted = reader.set.redacted.map { MinutesRedactedItem(numbers[it.itemId].orEmpty(), it.kind) }
        return versions.mapIndexed { i, r ->
            val ready = (copies.lookup(reader.row, reader.set, r.recordVersion) as? RecordLookup.Ready)
            MinutesRecordResponse(
                version = r.recordVersion,
                state = r.state.name,
                approvalId = r.approvalId,
                createdAt = r.createdAt,
                builtAt = r.builtAt,
                file = ready?.let { MinutesRecordFile(it.sha256, (it.copy?.compliant ?: r.compliant) == true, it.copy == null) },
                redacted = redacted,
                current = i == 0,
            )
        }
    }

    /**
     * Checks version [recordVersion] of [meetingId]'s record for the actor (outside a transaction: storage is read): the
     * approval's record hash, its place in the committee's chain and the .docx's recorded hash, then the approved .docx and
     * the file the actor reads re-hashed from storage against the hashes recorded when they were stored. Audited
     * `minutes.record.checked` (and the approval's check `minutes.approval.checked`).
     */
    fun check(
        meetingId: UUID,
        recordVersion: Int,
    ): MinutesRecordCheckResponse {
        val tenant = TenantContext.current()
        val (record, served) =
            transactions.inTenant(tenant) {
                val reader = approvedReader(meetingId)
                val record = records.find(meetingId, recordVersion) ?: throw MinutesNotFoundException("record")
                record to (copies.lookup(reader.row, reader.set, recordVersion) as? RecordLookup.Ready)
            }
        val docx = transactions.readInTenant(tenant) { files.version(record.docxVersionId) }
        val docxActual = docx?.let(::hashOf)
        val fileActual = served?.let { s -> transactions.readInTenant(tenant) { files.version(s.fileVersionId) }?.let(::hashOf) }
        return transactions.inTenant(tenant) {
            val approval = confirmation.check(meetingId, record.approvalId)
            val problems =
                buildList {
                    addAll(approval.problems)
                    when {
                        docxActual == null -> {
                            add(MinutesCheckProblem("DOCUMENT_MISSING", "The approved document cannot be read."))
                        }

                        docxActual != record.docxSha256 -> {
                            add(
                                MinutesCheckProblem("DOCUMENT_CHANGED", "The approved document has changed."),
                            )
                        }
                    }
                    when {
                        served == null -> {
                            add(MinutesCheckProblem("NOT_READY", "Your copy of the record is not made yet."))
                        }

                        fileActual == null -> {
                            add(MinutesCheckProblem("FILE_MISSING", "The record's file cannot be read."))
                        }

                        !fileActual.equals(served.sha256, ignoreCase = true) -> {
                            add(MinutesCheckProblem("FILE_CHANGED", "The record's file does not match its hash."))
                        }
                    }
                }.distinctBy { it.code }
            audit.record(
                "minutes.record.checked",
                MinutesDrafts.OBJECT_TYPE,
                meetingId.toString(),
                after =
                    mapOf(
                        "recordVersion" to recordVersion,
                        "full" to (served?.copy == null && served != null),
                        "problems" to problems.map { it.code },
                    ),
            )
            MinutesRecordCheckResponse(recordVersion, problems.isEmpty(), problems)
        }
    }

    /** Whether [meetingId]'s minutes are approved: their readers then read the record (inside a transaction). */
    fun approved(meetingId: UUID): Boolean = store.find(meetingId)?.state == MinutesStage.APPROVED

    /** SHA-256 of the stored plaintext, or null when it cannot be read. */
    private fun hashOf(version: com.memo.files.FileVersionInfo): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        return runCatching {
            DigestOutputStream(OutputStream.nullOutputStream(), digest).use { contents.write(version, it) }
            HexFormat.of().formatHex(digest.digest())
        }.getOrNull()
    }
}
