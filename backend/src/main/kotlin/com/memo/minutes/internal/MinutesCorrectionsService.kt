package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingViewers
import com.memo.minutes.MinutesCorrectionRef
import com.memo.minutes.MinutesCorrections
import com.memo.notification.NotificationKind
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A correction to propose: on an item, or on the minutes in general; what is corrected (1 to 4,000) and why (1 to 500). */
data class MinutesCorrectionInput(
    val itemId: UUID? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val text: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
)

enum class CorrectionDecision { APPROVE, DECLINE }

/** The approver's answer to a proposed correction, with an optional note (0 to 1,000 characters). */
data class MinutesCorrectionDecisionRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decision: CorrectionDecision,
    val note: String? = null,
)

/**
 * A correction as its reader sees it (design E06 §1.6.5, flow AG4): the officers see every one, the other readers of the
 * approved minutes the approved ones; the text and reason of a correction on an item the viewer may not read in full are
 * withheld ([withheld]).
 */
data class MinutesCorrectionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    val itemId: UUID?,
    val itemNumber: String?,
    /** What is corrected; null when withheld. */
    val text: String?,
    /** Why; null when withheld. */
    val reason: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val withheld: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: CorrectionState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val proposedBy: MinutesPerson,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val proposedAt: Instant,
    val decidedBy: MinutesPerson?,
    val decidedAt: Instant?,
    /** The approver's note: the officers only. */
    val note: String?,
    /** The chair's authenticated approval (the stamps' `id`). */
    val approvalId: UUID?,
    /** The record version that carries it. */
    val recordVersion: Int?,
)

data class MinutesCorrectionsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val corrections: List<MinutesCorrectionResponse>,
    /** Who approves corrections now (the chair, or the vice-chair when the chair is recused); null when nobody is able. */
    val approver: MinutesPerson?,
    /** May propose a correction (a secretary or `forums.manage`; per item, not recused from it). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayPropose: Boolean,
    /** May approve or decline the proposed ones (the approver, with a fresh step-up). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mayDecide: Boolean,
)

/**
 * Corrections after approval (ADR-0066, design E06 §1.6.5, CGI §6.2; the owner's answer 9): approved minutes are never
 * edited. A secretary of the committee (or `forums.manage`) not recused from the item — from any minuted item for a
 * general correction — proposes one; the approver ([MinutesPeople.correctionApprover]: the chair in office, else the
 * vice-chair) approves or declines it once with a fresh step-up. An approval is an authenticated `minutes_approval` of kind
 * CORRECTION chained into the committee's chain over the same approved .docx, and asks for the next version of the record
 * ([MinutesRecords.request]), which keeps the approved document's pages as they were and adds the "Corrections" annex; the
 * earlier versions stay locked and readable. Audited `minutes.correction.proposed`, `.approved`, `.declined`; the approver
 * is told of a proposal. Published for E07 as [MinutesCorrections.since].
 */
@Service
class MinutesCorrectionsService(
    private val store: MinutesStore,
    private val corrections: MinutesCorrectionStore,
    private val approvals: MinutesApprovalStore,
    private val confirmation: MinutesConfirmation,
    private val records: MinutesRecords,
    private val recordReading: MinutesRecordReading,
    private val people: MinutesPeople,
    private val viewers: MeetingViewers,
    private val evidence: ApprovalEvidence,
    private val notifier: MinutesNotifier,
    private val users: UserSummaries,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) : MinutesCorrections {
    /**
     * Proposes [input] on [meetingId]'s approved minutes (inside a transaction), in this order of refusals: 404 for a meeting
     * the actor does not see, 403 for anyone but a secretary or `forums.manage`, 403 `RECUSED` for one recused from the item
     * (or, for a general correction, from any item), before the minutes' stage is looked at; then 409 `WRONG_STATE` before
     * approval, 404 for an item not in the minutes, 422 for the lengths.
     */
    fun propose(
        meetingId: UUID,
        input: MinutesCorrectionInput,
    ): MinutesCorrectionResponse {
        val view = proposer(meetingId, input.itemId)
        val row = store.find(meetingId)
        refuseIf(row?.state != MinutesStage.APPROVED, "WRONG_STATE") {
            "Corrections are proposed once the minutes are approved; until then, edit the draft."
        }
        checkNotNull(row)
        val minuted = MinutesTemplateData.minuted(view.agenda.items).map { it.id }.toSet()
        if (input.itemId != null && input.itemId !in minuted) throw MinutesNotFoundException("item")
        val text = input.text.trim()
        val reason = input.reason.trim()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("text", "LENGTH", "1 to $MAX_TEXT characters.").takeIf { text.isEmpty() || text.length > MAX_TEXT },
                FieldProblem("reason", "REASON_REQUIRED", "Say why the minutes need correcting.").takeIf { reason.isEmpty() },
                FieldProblem("reason", "LENGTH", "At most $MAX_REASON characters.").takeIf { reason.length > MAX_REASON },
            ),
        )
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val correction =
            CorrectionRow(
                id = ids.next(),
                meetingId = meetingId,
                forumId = row.forumId,
                itemId = input.itemId,
                text = text,
                reason = reason,
                proposedBy = view.userId,
                proposedAt = now,
                state = CorrectionState.PROPOSED,
                decidedBy = null,
                decidedAt = null,
                decisionNote = null,
                approvalId = null,
                recordVersion = null,
                version = 0,
            )
        corrections.insert(correction)
        audit.record(
            "minutes.correction.proposed",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            after =
                mapOf(
                    "correctionId" to correction.id.toString(),
                    "itemId" to input.itemId?.toString(),
                    "textSha256" to ApprovalEvidence.sha256(text),
                    "reasonSha256" to ApprovalEvidence.sha256(reason),
                ),
        )
        people.correctionApprover(row)?.let { notifier.notify(listOf(it.userId), NotificationKind.MINUTES_CORRECTION_PROPOSED, meetingId) }
        return response(correction, officer = true, withheld = false, names = names(listOf(correction)), numbers = view.numbers())
    }

    /**
     * The actor proposing a correction on [itemId] (none: the minutes in general) of [meetingId]: a secretary or
     * `forums.manage` (403 otherwise; 404 for a meeting they do not see), not recused from the item or, for a general
     * correction, from any item (403 `RECUSED`).
     */
    private fun proposer(
        meetingId: UUID,
        itemId: UUID?,
    ): com.memo.meeting.MeetingView {
        val view = viewers.of(meetingId)
        if (!view.manages) throw ForbiddenException(null)
        val recused = people.recusedOf(meetingId)
        val isRecused = itemId?.let { view.userId in recused[it].orEmpty() } ?: recused.values.any { view.userId in it }
        if (isRecused) {
            throw ForbiddenException(null, "RECUSED", "You declared an interest: another secretary proposes this correction.")
        }
        return view
    }

    /**
     * Who may decide [correctionId] (read in its own transaction, before the step-up is asked for): 404 for minutes or a
     * correction the actor does not read, 403 `NOT_CONFIRMER` for anyone but the approver.
     */
    fun requireApprover(
        meetingId: UUID,
        correctionId: UUID,
    ) {
        val reader = recordReading.approvedReader(meetingId)
        corrections.find(correctionId)?.takeIf { it.meetingId == meetingId } ?: throw MinutesNotFoundException("correction")
        approverOf(reader.row, reader.view.userId)
    }

    /**
     * The approver approves or declines [correctionId] (inside a transaction, after the step-up): once (409 `DECIDED`). An
     * approval, in the lock order of the confirmation (the minutes, then the committee's chain): the authenticated approval
     * of kind CORRECTION over the approved .docx, the correction APPROVED with it and the record's next version asked for.
     */
    fun decide(
        meetingId: UUID,
        correctionId: UUID,
        decision: CorrectionDecision,
        note: String?,
    ): MinutesCorrectionResponse {
        val reader = recordReading.approvedReader(meetingId)
        val row = store.lock(meetingId)?.takeIf { it.state == MinutesStage.APPROVED } ?: throw MinutesNotFoundException("minutes")
        val approver = approverOf(row, reader.view.userId)
        val correction =
            corrections.lock(correctionId)?.takeIf { it.meetingId == meetingId } ?: throw MinutesNotFoundException("correction")
        refuseIf(correction.state != CorrectionState.PROPOSED, "DECIDED") { "This correction has been decided already." }
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("note", "LENGTH", "At most $MAX_NOTE characters.").takeIf { (cleanNote?.length ?: 0) > MAX_NOTE },
            ),
        )
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val decided =
            correction.copy(
                decidedBy = approver.userId,
                decidedAt = now,
                decisionNote = cleanNote,
            )
        val after =
            if (decision == CorrectionDecision.DECLINE) {
                decided.copy(state = CorrectionState.DECLINED).also { declined ->
                    check(corrections.decide(correction, declined)) { "the locked correction moved on" }
                    audit.record(
                        "minutes.correction.declined",
                        MinutesDrafts.OBJECT_TYPE,
                        meetingId.toString(),
                        after =
                            mapOf(
                                "correctionId" to correctionId.toString(),
                                "role" to approver.role.name,
                                "noteSha256" to cleanNote?.let(ApprovalEvidence::sha256),
                            ),
                    )
                }
            } else {
                approve(row, approver, correction, decided, now)
            }
        return response(after, officer = true, withheld = false, names = names(listOf(after)), numbers = reader.view.numbers())
    }

    private fun approve(
        row: MinutesRow,
        approver: MinutesOfficer,
        correction: CorrectionRow,
        decided: CorrectionRow,
        now: Instant,
    ): CorrectionRow {
        val confirmed =
            approvals.ofMeeting(row.meetingId).firstOrNull { it.id == row.confirmedApprovalId }
                ?: throw MinutesNotFoundException("approval")
        approvals.lockChain(row.forumId)
        val sha = sha256Of(correction)
        val approval =
            confirmation.approval(
                row,
                approver,
                confirmed.docxVersionId to confirmed.docxSha256,
                approvals.head(row.forumId),
                now,
                correction.id to sha,
            )
        approvals.insert(approval)
        val record = records.request(row, approval)
        val approved = decided.copy(state = CorrectionState.APPROVED, approvalId = approval.id, recordVersion = record.recordVersion)
        check(corrections.decide(correction, approved)) { "the locked correction moved on" }
        store.insertEvent(
            MinutesEventRow(
                ids.next(),
                row.meetingId,
                MinutesEventKind.CORRECTION_APPROVED,
                correction.itemId,
                approver.userId,
                confirmed.docxVersionId,
                confirmed.docxSha256,
                now,
            ),
        )
        audit.record(
            "minutes.correction.approved",
            MinutesDrafts.OBJECT_TYPE,
            row.meetingId.toString(),
            after =
                mapOf(
                    "correctionId" to correction.id.toString(),
                    "approvalId" to approval.id.toString(),
                    "role" to approver.role.name,
                    "recordHash" to approval.recordHash,
                    "previousHash" to approval.previousHash,
                    "correctionSha256" to sha,
                    "recordVersion" to record.recordVersion,
                ),
        )
        return approved
    }

    /** [row]'s correction approver, who must be [userId] (403 `NOT_CONFIRMER`). */
    private fun approverOf(
        row: MinutesRow,
        userId: UUID,
    ): MinutesOfficer {
        val approver = people.correctionApprover(row)
        if (approver?.userId != userId) {
            throw ForbiddenException(null, "NOT_CONFIRMER", "Only the chair approves corrections to these minutes.")
        }
        return approver
    }

    /**
     * [meetingId]'s corrections as the actor reads them (inside a transaction): the readers of the approved minutes (404
     * otherwise); the officers (secretaries, `forums.manage`, the approver) every one with the approver's notes, the other
     * readers the approved ones; a correction on an item the viewer may not read in full without its text or reason.
     */
    fun list(meetingId: UUID): MinutesCorrectionsResponse {
        val reader = recordReading.approvedReader(meetingId)
        val approver = people.correctionApprover(reader.row)
        val officer = reader.view.manages || approver?.userId == reader.view.userId
        val redacted =
            reader.set.redacted
                .map { it.itemId }
                .toSet()
        val rows = corrections.of(meetingId).filter { officer || it.state == CorrectionState.APPROVED }
        val names = names(rows) + users.names(setOfNotNull(approver?.userId))
        val numbers = reader.view.numbers()
        return MinutesCorrectionsResponse(
            corrections = rows.map { response(it, officer, it.itemId != null && it.itemId in redacted, names, numbers) },
            approver = approver?.let { MinutesPerson(it.userId, names[it.userId].orEmpty()) },
            mayPropose = reader.view.manages,
            mayDecide = approver?.userId == reader.view.userId,
        )
    }

    override fun since(
        forumId: UUID,
        since: Instant,
    ): List<MinutesCorrectionRef> =
        corrections.approvedSince(forumId, since).map { c ->
            MinutesCorrectionRef(c.id, c.meetingId, c.forumId, c.itemId, c.text, checkNotNull(c.decidedAt), checkNotNull(c.recordVersion))
        }

    private fun response(
        c: CorrectionRow,
        officer: Boolean,
        withheld: Boolean,
        names: Map<UUID, String>,
        numbers: Map<UUID, String?>,
    ) = MinutesCorrectionResponse(
        id = c.id,
        itemId = c.itemId,
        itemNumber = c.itemId?.let(numbers::get),
        text = c.text.takeIf { !withheld },
        reason = c.reason.takeIf { !withheld },
        withheld = withheld,
        state = c.state,
        proposedBy = MinutesPerson(c.proposedBy, names[c.proposedBy].orEmpty()),
        proposedAt = c.proposedAt,
        decidedBy = c.decidedBy?.let { MinutesPerson(it, names[it].orEmpty()) },
        decidedAt = c.decidedAt,
        note = c.decisionNote.takeIf { officer && !withheld },
        approvalId = c.approvalId,
        recordVersion = c.recordVersion,
    )

    private fun names(rows: List<CorrectionRow>): Map<UUID, String> =
        users.names(rows.flatMap { listOfNotNull(it.proposedBy, it.decidedBy) }.toSet())

    /** SHA-256 of what [c] says, as approved: its item, text and reason (in the approval's record hash). */
    private fun sha256Of(c: CorrectionRow): String =
        evidence.hash(mapOf("correctionId" to c.id.toString(), "itemId" to c.itemId?.toString(), "text" to c.text, "reason" to c.reason))

    private companion object {
        const val MAX_TEXT = 4000
        const val MAX_REASON = 500
        const val MAX_NOTE = 1000
    }
}

/** The agenda items' numbers by id, as [this] reads the agenda. */
private fun com.memo.meeting.MeetingView.numbers(): Map<UUID, String?> = agenda.items.associate { it.id to it.number }
