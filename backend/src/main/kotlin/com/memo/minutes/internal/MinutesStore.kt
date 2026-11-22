package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.forum.MinutesPolicy
import com.memo.jooq.tables.records.MinutesRecord
import com.memo.jooq.tables.references.MINUTES
import com.memo.jooq.tables.references.MINUTES_EVENT
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** What the draft job was asked to do last (design E06 §1.6.1): the whole draft, or one item filled again or restored. */
enum class DraftWork { ALL, ITEM }

/** Where that request stands: running, or given up with a code. Done requests leave no state. */
enum class WorkState { PENDING, FAILED }

/** What happened to the minutes (design E06 §1.5), as `minutes_event` keeps it. */
enum class MinutesEventKind {
    GENERATED,
    ITEM_REGENERATED,
    ITEM_RESTORED,
    RETURNED_TO_DRAFT,
    SENT_FOR_REVIEW,
    AGREED,
    RETURNED,
    CIRCULATED,
    COMMENTS_REQUESTED,
    WITHDRAWN,
    DEADLINE_PASSED,
    DEADLINE_CHANGED,
    CIRCULATION_ENDED,
    LINKED_TO_MEETING,
    APPROVED_AT_MEETING,
    CONFIRMED,
}

/** Who reviews or confirms the minutes, as what (design E06 §1.6.2, §1.6.4). */
enum class MinutesRole { CHAIR, VICE_CHAIR }

/** A meeting's minutes (design E06 §1.5), as the module reads and writes them. */
data class MinutesRow(
    val meetingId: UUID,
    val forumId: UUID,
    /** The committee type's minutes policy as pinned on the meeting when it was held. */
    val policy: MinutesPolicy,
    val state: MinutesStage,
    /** The draft (purpose `minutes.draft`), once its first version is stored. */
    val fileId: UUID?,
    val workSeq: Int,
    val workKind: DraftWork?,
    val workItemId: UUID?,
    val workState: WorkState?,
    val workError: String?,
    val workRequestedBy: UUID?,
    val workRequestedAt: Instant?,
    /** The draft's version the structure check last read, with what it found. */
    val checkedVersionId: UUID?,
    val structureOk: Boolean?,
    val structureProblems: List<StructureProblem>,
    val trackedChanges: Int,
    val comments: Int,
    val checkedAt: Instant?,
    val heldAt: Instant,
    val createdAt: Instant,
    val version: Long,
    /** The chair or vice-chair asked to review the draft (from CHAIR_REVIEW on). */
    val reviewerId: UUID? = null,
    val reviewerRole: MinutesRole? = null,
    /** The comment deadline, once circulated (the end of a working day). */
    val commentsUntil: Instant? = null,
    val circulatedAt: Instant? = null,
    /** The next meeting's "Minutes of the previous meeting" item they are linked to. */
    val linkedMeetingId: UUID? = null,
    val linkedItemId: UUID? = null,
    /** AT_NEXT_MEETING: the meeting and the decision that approved them. */
    val approvingMeetingId: UUID? = null,
    val approvingDecisionId: UUID? = null,
    /** When they went to the chair for confirmation. */
    val awaitingSince: Instant? = null,
    val confirmedApprovalId: UUID? = null,
    val approvedAt: Instant? = null,
    /** The copy builds asked for (the `minutes-copies` job's instance id). */
    val copiesSeq: Int = 0,
)

/** One line of the minutes' history; [note] is free text, never audited. */
data class MinutesEventRow(
    val id: UUID,
    val meetingId: UUID,
    val kind: MinutesEventKind,
    val itemId: UUID?,
    val actor: UUID?,
    val fileVersionId: UUID?,
    val sha256: String?,
    val at: Instant,
    val note: String? = null,
    /** A secretary who recorded the step for [actor] (the chair's agreement given outside the app). */
    val onBehalfBy: UUID? = null,
    /** How it was given, when recorded on someone's behalf (EMAIL, PHONE, IN_PERSON, OTHER). */
    val how: String? = null,
)

/**
 * The minutes and their history (design E06 §1.5, §6), inside a tenant transaction. The row changes only by a guarded
 * update on its version (the trigger repeats the rules: the meeting, policy and file never change once set); the history
 * is append-only. Lock order (design E06 §1.4 as built): after the decision, submission and meeting rows.
 */
@Repository
class MinutesStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun find(meetingId: UUID): MinutesRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.MEETING_ID.eq(meetingId))
            .fetchOne { it.row() }

    /** [meetingId]'s minutes, locked to the end of the transaction; null when there are none. */
    fun lock(meetingId: UUID): MinutesRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.MEETING_ID.eq(meetingId))
            .forUpdate()
            .fetchOne { it.row() }

    /** Creates [row]; false when the meeting has minutes already. */
    fun insert(row: MinutesRow): Boolean {
        val m = MINUTES
        return tenantDsl
            .current()
            .insertInto(m)
            .set(m.TENANT_ID, tenantDsl.tenant().value)
            .set(m.MEETING_ID, row.meetingId)
            .set(m.FORUM_ID, row.forumId)
            .set(m.POLICY, JSONB.jsonb(json.writeValueAsString(row.policy)))
            .set(m.STATE, row.state.name)
            .set(m.HELD_AT, row.heldAt.atOffset(ZoneOffset.UTC))
            .set(m.CREATED_AT, row.createdAt.atOffset(ZoneOffset.UTC))
            .set(m.VERSION, 0L)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** Writes [after] over the minutes as seen at [before]'s version; false when they moved on in between. */
    fun update(
        before: MinutesRow,
        after: MinutesRow,
    ): Boolean {
        val m = MINUTES
        return tenantDsl
            .current()
            .update(m)
            .set(m.STATE, after.state.name)
            .set(m.FILE_ID, after.fileId)
            .set(m.WORK_SEQ, after.workSeq)
            .set(m.WORK_KIND, after.workKind?.name)
            .set(m.WORK_ITEM_ID, after.workItemId)
            .set(m.WORK_STATE, after.workState?.name)
            .set(m.WORK_ERROR, after.workError)
            .set(m.WORK_REQUESTED_BY, after.workRequestedBy)
            .set(m.WORK_REQUESTED_AT, after.workRequestedAt?.atOffset(ZoneOffset.UTC))
            .set(m.CHECKED_VERSION_ID, after.checkedVersionId)
            .set(m.STRUCTURE_OK, after.structureOk)
            .set(m.STRUCTURE_PROBLEMS, JSONB.jsonb(json.writeValueAsString(after.structureProblems)))
            .set(m.TRACKED_CHANGES, after.trackedChanges)
            .set(m.COMMENTS, after.comments)
            .set(m.CHECKED_AT, after.checkedAt?.atOffset(ZoneOffset.UTC))
            .set(m.REVIEWER_ID, after.reviewerId)
            .set(m.REVIEWER_ROLE, after.reviewerRole?.name)
            .set(m.COMMENTS_UNTIL, after.commentsUntil?.atOffset(ZoneOffset.UTC))
            .set(m.CIRCULATED_AT, after.circulatedAt?.atOffset(ZoneOffset.UTC))
            .set(m.LINKED_MEETING_ID, after.linkedMeetingId)
            .set(m.LINKED_ITEM_ID, after.linkedItemId)
            .set(m.APPROVING_MEETING_ID, after.approvingMeetingId)
            .set(m.APPROVING_DECISION_ID, after.approvingDecisionId)
            .set(m.AWAITING_SINCE, after.awaitingSince?.atOffset(ZoneOffset.UTC))
            .set(m.CONFIRMED_APPROVAL_ID, after.confirmedApprovalId)
            .set(m.APPROVED_AT, after.approvedAt?.atOffset(ZoneOffset.UTC))
            .set(m.COPIES_SEQ, after.copiesSeq)
            .set(m.VERSION, before.version + 1)
            .where(m.MEETING_ID.eq(before.meetingId), m.VERSION.eq(before.version))
            .execute() == 1
    }

    fun insertEvent(event: MinutesEventRow) {
        val e = MINUTES_EVENT
        tenantDsl
            .current()
            .insertInto(e)
            .set(e.ID, event.id)
            .set(e.TENANT_ID, tenantDsl.tenant().value)
            .set(e.MEETING_ID, event.meetingId)
            .set(e.KIND, event.kind.name)
            .set(e.ITEM_ID, event.itemId)
            .set(e.ACTOR, event.actor)
            .set(e.NOTE, event.note)
            .set(e.ON_BEHALF_BY, event.onBehalfBy)
            .set(e.HOW, event.how)
            .set(e.FILE_VERSION_ID, event.fileVersionId)
            .set(e.SHA256, event.sha256)
            .set(e.AT, event.at.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** [meetingId]'s history, oldest first. */
    fun events(meetingId: UUID): List<MinutesEventRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_EVENT)
            .where(MINUTES_EVENT.MEETING_ID.eq(meetingId))
            .orderBy(MINUTES_EVENT.AT, MINUTES_EVENT.ID)
            .fetch {
                MinutesEventRow(
                    it.id,
                    it.meetingId,
                    MinutesEventKind.valueOf(it.kind),
                    it.itemId,
                    it.actor,
                    it.fileVersionId,
                    it.sha256,
                    it.at.toInstant(),
                    it.note,
                    it.onBehalfBy,
                    it.how,
                )
            }

    /** [r] as the module reads it (for [MinutesQueries]). */
    fun rowOf(r: MinutesRecord): MinutesRow = r.row()

    private fun MinutesRecord.row() =
        MinutesRow(
            meetingId = meetingId,
            forumId = forumId,
            policy = json.readValue(policy.data(), MinutesPolicy::class.java),
            state = MinutesStage.valueOf(state ?: MinutesStage.DRAFT.name),
            fileId = fileId,
            workSeq = workSeq ?: 0,
            workKind = workKind?.let(DraftWork::valueOf),
            workItemId = workItemId,
            workState = workState?.let(WorkState::valueOf),
            workError = workError,
            workRequestedBy = workRequestedBy,
            workRequestedAt = workRequestedAt?.toInstant(),
            checkedVersionId = checkedVersionId,
            structureOk = structureOk,
            structureProblems = structureProblems?.let { json.readValue(it.data(), PROBLEMS) }.orEmpty(),
            trackedChanges = trackedChanges ?: 0,
            comments = comments ?: 0,
            checkedAt = checkedAt?.toInstant(),
            heldAt = heldAt.toInstant(),
            createdAt = createdAt.toInstant(),
            version = version ?: 0,
            reviewerId = reviewerId,
            reviewerRole = reviewerRole?.let(MinutesRole::valueOf),
            commentsUntil = commentsUntil?.toInstant(),
            circulatedAt = circulatedAt?.toInstant(),
            linkedMeetingId = linkedMeetingId,
            linkedItemId = linkedItemId,
            approvingMeetingId = approvingMeetingId,
            approvingDecisionId = approvingDecisionId,
            awaitingSince = awaitingSince?.toInstant(),
            confirmedApprovalId = confirmedApprovalId,
            approvedAt = approvedAt?.toInstant(),
            copiesSeq = copiesSeq ?: 0,
        )

    private companion object {
        val PROBLEMS = object : TypeReference<List<StructureProblem>>() {}
    }
}
