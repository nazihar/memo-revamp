package com.memo.meeting.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.jooq.tables.references.MEETING_CHAIR_COMMENT
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** A comment of the chair on the draft agenda, for its secretaries (design E03 §3). */
data class ChairComment(
    val id: UUID,
    val meetingId: UUID,
    val text: String,
    val by: UUID?,
    val at: Instant,
)

/** The chair's comments, inside a tenant transaction; written once, never changed. */
@Repository
class ChairCommentStore(
    private val tenantDsl: TenantDsl,
) {
    fun insert(comment: ChairComment) {
        tenantDsl
            .current()
            .insertInto(MEETING_CHAIR_COMMENT)
            .set(MEETING_CHAIR_COMMENT.ID, comment.id)
            .set(MEETING_CHAIR_COMMENT.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_CHAIR_COMMENT.MEETING_ID, comment.meetingId)
            .set(MEETING_CHAIR_COMMENT.TEXT, comment.text)
            .set(MEETING_CHAIR_COMMENT.CREATED_BY, comment.by)
            .set(MEETING_CHAIR_COMMENT.CREATED_AT, MeetingStore.utc(comment.at))
            .execute()
    }

    /** The comments on [meetingId]'s agenda, the oldest first. */
    fun of(meetingId: UUID): List<ChairComment> =
        tenantDsl
            .current()
            .selectFrom(MEETING_CHAIR_COMMENT)
            .where(MEETING_CHAIR_COMMENT.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_CHAIR_COMMENT.CREATED_AT, MEETING_CHAIR_COMMENT.ID)
            .fetch { ChairComment(it.id, it.meetingId, it.text, it.createdBy, it.createdAt.toInstant()) }
}

/** The chair's answer on a late item or paper (design E03 §1.4); [onBehalf]: a secretary records it, saying how in [note]. */
data class LateDecision(
    val agree: Boolean,
    val onBehalf: Boolean = false,
    val note: String? = null,
)

/**
 * The chair's part in the agenda (design E03 §1.4, flow Q): agreeing to its first publication, commenting on the draft
 * to the secretaries, and agreeing or declining late items and papers. The chair in office answers in the app (the
 * vice-chair when there is no chair); a secretary may record the chair's answer on their behalf, saying how it was given
 * ("by email on 4 Dec"). Every answer carries the agenda's version, so the chair answers on what they read.
 */
@Service
class AgendaChair(
    private val guard: AgendaGuard,
    private val meetingGuard: MeetingGuard,
    private val access: MeetingAccess,
    private val agendas: AgendaStore,
    private val items: ItemStore,
    private val papers: PaperStore,
    private val comments: ChairCommentStore,
    private val writes: AgendaWrites,
    private val notifier: MeetingNotifier,
    private val directory: ForumDirectory,
    private val ids: IdGenerator,
) {
    /** The chair agrees the agenda's first publication, or a secretary records that they did ([onBehalf]). */
    fun agree(
        meetingId: UUID,
        version: Long,
        onBehalf: Boolean,
        note: String?,
    ) {
        val edit = guard.answer(meetingId, version, onBehalf)
        refuseIf(edit.agenda.currentRevision > 0, "WRONG_STATE") { "The agenda is published: the chair agrees its first publication." }
        refuseIf(edit.agenda.chairAgreement != null, "ALREADY_AGREED") { "The chair has already agreed this agenda." }
        val text = note(note, onBehalf)
        val chair = if (onBehalf) notifier.chairOf(edit.meeting.forumId) else actorOrNull()
        refuseIf(chair == null, "NO_CHAIR") { "The committee has no chair or vice-chair in office to agree the agenda." }
        val agreement = ChairAgreement(chair, agendas.now().toInstant(), actorOrNull().takeIf { onBehalf }, text)
        agendas.agree(edit.meeting.id, agreement)
        writes.record(
            "meeting.agenda.chair_agreed",
            edit.meeting.id,
            after =
                mapOf(
                    "by" to chair?.toString(),
                    "onBehalfBy" to agreement.onBehalfBy?.toString(),
                    "note" to text,
                    "version" to version,
                ),
        )
        guard.done(edit.meeting.id)
    }

    /** The chair comments on the draft: the secretaries are told; nothing changes on the agenda. */
    fun comment(
        meetingId: UUID,
        text: String?,
    ): UUID {
        val (meeting, _) = meetingGuard.requireVisible(meetingId)
        if (!access.chairs(meeting.forumId)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        refuseIf(agendas.find(meeting.id) == null, "NO_AGENDA") { "The agenda has not been started yet." }
        AgendaRules.requireChange(meeting.status, tabled = true)
        val (clean, problem) = AgendaRules.title("text", text, MAX_COMMENT)
        requireNoProblems(listOfNotNull(problem))
        val comment = ChairComment(ids.next(), meeting.id, clean, actorOrNull(), agendas.now().toInstant())
        comments.insert(comment)
        writes.record(
            "meeting.agenda.chair_commented",
            meeting.id,
            after = mapOf("commentId" to comment.id.toString(), "length" to clean.length),
        )
        directory.forum(meeting.forumId)?.let { notifier.chairCommented(meeting, it) }
        return comment.id
    }

    /**
     * The chair's answer on an item waiting for them: agreed, it is published in the next revision as a late paper;
     * declined, it leaves the agenda (`CHAIR_DECLINED`) and a submission goes back to the tray. The submission's row is
     * locked before the agenda's (design E03 §1.8).
     */
    fun decideItem(
        meetingId: UUID,
        itemId: UUID,
        version: Long,
        decision: LateDecision,
    ) {
        val submission = items.find(itemId)?.submissionId
        val edit = guard.answer(meetingId, version, decision.onBehalf, listOfNotNull(submission))
        val item = edit.item(itemId)
        refuseIf(item.late != Lateness.AWAITING_CHAIR, "WRONG_STATE") { "This item is not waiting for the chair." }
        val audited = decided(decision, mapOf("itemId" to item.id.toString(), "submissionId" to item.submissionId?.toString()))
        if (decision.agree) {
            items.update(item.copy(late = Lateness.CHAIR_AGREED))
            writes.record("meeting.late.agreed", edit.meeting.id, after = audited)
        } else {
            writes.removeItem(edit, item, RemovalReason.CHAIR_DECLINED)
            writes.record("meeting.late.declined", edit.meeting.id, after = audited)
        }
        guard.done(edit.meeting.id)
    }

    /** The chair's answer on a late paper: agreed, it is published in the next revision; declined, it leaves its item. */
    fun decidePaper(
        meetingId: UUID,
        paperId: UUID,
        version: Long,
        decision: LateDecision,
    ) {
        val edit = guard.answer(meetingId, version, decision.onBehalf)
        val paper = edit.paper(paperId)
        refuseIf(paper.late != Lateness.AWAITING_CHAIR, "WRONG_STATE") { "This paper is not waiting for the chair." }
        val audited = decided(decision, mapOf("paperId" to paper.id.toString(), "itemId" to paper.itemId.toString()))
        if (decision.agree) {
            papers.update(paper.copy(late = Lateness.CHAIR_AGREED))
            writes.record("meeting.late.agreed", edit.meeting.id, after = audited)
        } else {
            writes.removePaper(edit, paper)
            writes.record("meeting.late.declined", edit.meeting.id, after = audited)
        }
        guard.done(edit.meeting.id)
    }

    private fun decided(
        decision: LateDecision,
        what: Map<String, Any?>,
    ): Map<String, Any?> = what + mapOf("onBehalf" to decision.onBehalf, "note" to note(decision.note, decision.onBehalf))

    /** How the chair's answer was given: required when recorded on their behalf; at most [MAX_NOTE] characters. */
    private fun note(
        note: String?,
        onBehalf: Boolean,
    ): String? {
        val (text, problem) = AgendaRules.optional("note", note, MAX_NOTE)
        requireNoProblems(
            listOfNotNull(
                problem,
                FieldProblem("note", "NOTE_REQUIRED", "Say how the chair gave their answer, for example \"by email on 4 Dec\".")
                    .takeIf { onBehalf && text == null },
            ),
        )
        return text
    }

    companion object {
        const val MAX_COMMENT = 2000
        const val MAX_NOTE = 500
    }
}
