package com.memo.forum.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.forum.SubmissionPurpose
import com.memo.forum.SubmissionStatus
import com.memo.jooq.tables.records.ForumSubmissionRecord
import com.memo.jooq.tables.references.FORUM_SUBMISSION
import com.memo.workflow.ForumOutcome
import org.jooq.Field
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One referral of a workflow item to a forum (ADR-0059). */
data class SubmissionRow(
    val id: UUID,
    val forumId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val instanceId: UUID,
    /** The step the item waits at; null when it passed through ("for information"). */
    val stepId: UUID?,
    val previousId: UUID?,
    val purpose: SubmissionPurpose,
    val resolutionSought: String?,
    val presenterId: UUID?,
    val status: SubmissionStatus,
    val targetDate: LocalDate?,
    val submittedBy: UUID,
    val submittedAt: Instant,
    val triagedBy: UUID?,
    val triagedAt: Instant?,
    val reason: String?,
    val outcome: ForumOutcome?,
    val decidedAt: Instant?,
    val remindedAt: Instant?,
    val version: Long,
    /** The meeting whose agenda it is placed on (design E03 §1.8), and that meeting's date. */
    val meetingId: UUID? = null,
    val meetingDate: LocalDate? = null,
) {
    val waits: Boolean get() = stepId != null
}

/** Forum submissions (ADR-0059), inside a tenant transaction; status moves are guarded by the expected status and version. */
@Repository
class SubmissionStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun find(id: UUID): SubmissionRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM_SUBMISSION)
            .where(FORUM_SUBMISSION.ID.eq(id))
            .fetchOne { it.row() }

    /** The forum's submissions in [statuses], oldest first, at most [limit]. */
    fun queue(
        forumId: UUID,
        statuses: Collection<SubmissionStatus>,
        limit: Int,
    ): List<SubmissionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_SUBMISSION)
            .where(FORUM_SUBMISSION.FORUM_ID.eq(forumId), FORUM_SUBMISSION.STATUS.`in`(statuses.map { it.name }))
            .orderBy(FORUM_SUBMISSION.SUBMITTED_AT, FORUM_SUBMISSION.ID)
            .limit(limit)
            .fetch { it.row() }

    /** Every submission of a workflow subject, newest first. */
    fun ofSubject(
        subjectType: String,
        subjectId: UUID,
    ): List<SubmissionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_SUBMISSION)
            .where(FORUM_SUBMISSION.SUBJECT_TYPE.eq(subjectType), FORUM_SUBMISSION.SUBJECT_ID.eq(subjectId))
            .orderBy(FORUM_SUBMISSION.SUBMITTED_AT.desc(), FORUM_SUBMISSION.ID.desc())
            .fetch { it.row() }

    /** The open submissions of an instance (closed when it ends). */
    fun openOf(instanceId: UUID): List<SubmissionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_SUBMISSION)
            .where(FORUM_SUBMISSION.INSTANCE_ID.eq(instanceId))
            .fetch { it.row() }
            .filter { it.status.open }

    /** The latest earlier submission of the same subject to [forumId], if any (a resubmission after a return). */
    fun latest(
        forumId: UUID,
        subjectType: String,
        subjectId: UUID,
    ): SubmissionRow? = ofSubject(subjectType, subjectId).firstOrNull { it.forumId == forumId }

    fun insert(row: SubmissionRow): UUID {
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(FORUM_SUBMISSION)
            .set(FORUM_SUBMISSION.ID, id)
            .set(FORUM_SUBMISSION.TENANT_ID, tenantDsl.tenant().value)
            .set(FORUM_SUBMISSION.FORUM_ID, row.forumId)
            .set(FORUM_SUBMISSION.SUBJECT_TYPE, row.subjectType)
            .set(FORUM_SUBMISSION.SUBJECT_ID, row.subjectId)
            .set(FORUM_SUBMISSION.INSTANCE_ID, row.instanceId)
            .set(FORUM_SUBMISSION.STEP_ID, row.stepId)
            .set(FORUM_SUBMISSION.PREVIOUS_ID, row.previousId)
            .set(FORUM_SUBMISSION.PURPOSE, row.purpose.name)
            .set(FORUM_SUBMISSION.RESOLUTION_SOUGHT, row.resolutionSought)
            .set(FORUM_SUBMISSION.PRESENTER_ID, row.presenterId)
            .set(FORUM_SUBMISSION.STATUS, row.status.name)
            .set(FORUM_SUBMISSION.SUBMITTED_BY, row.submittedBy)
            .set(FORUM_SUBMISSION.SUBMITTED_AT, now())
            .execute()
        return id
    }

    /** Moves [row] to [to] with [fields] if it is still in its status at [version]; false when it was not. */
    fun move(
        row: SubmissionRow,
        to: SubmissionStatus,
        version: Long,
        fields: Map<out Field<*>, Any?> = emptyMap(),
    ): Boolean {
        val update =
            tenantDsl
                .current()
                .update(FORUM_SUBMISSION)
                .set(FORUM_SUBMISSION.STATUS, to.name)
                .set(FORUM_SUBMISSION.VERSION, FORUM_SUBMISSION.VERSION.plus(1))
        @Suppress("UNCHECKED_CAST")
        fields.forEach { (field, value) -> update.set(field as Field<Any?>, value) }
        val guard =
            FORUM_SUBMISSION.ID
                .eq(row.id)
                .and(FORUM_SUBMISSION.STATUS.eq(row.status.name))
                .and(FORUM_SUBMISSION.VERSION.eq(version))
        return update.where(guard).execute() == 1
    }

    /** Edits the cover sheet if the submission is still at [version]. */
    fun edit(
        row: SubmissionRow,
        version: Long,
    ): Boolean =
        tenantDsl
            .current()
            .update(FORUM_SUBMISSION)
            .set(FORUM_SUBMISSION.PURPOSE, row.purpose.name)
            .set(FORUM_SUBMISSION.RESOLUTION_SOUGHT, row.resolutionSought)
            .set(FORUM_SUBMISSION.PRESENTER_ID, row.presenterId)
            .set(FORUM_SUBMISSION.TARGET_DATE, row.targetDate)
            .set(FORUM_SUBMISSION.VERSION, FORUM_SUBMISSION.VERSION.plus(1))
            .where(FORUM_SUBMISSION.ID.eq(row.id), FORUM_SUBMISSION.VERSION.eq(version))
            .execute() == 1

    /** Queued submissions not yet chased, oldest first (the waiting reminder, design E02 §9). */
    fun unreminded(limit: Int): List<SubmissionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_SUBMISSION)
            .where(FORUM_SUBMISSION.STATUS.eq(SubmissionStatus.QUEUED.name), FORUM_SUBMISSION.REMINDED_AT.isNull)
            .orderBy(FORUM_SUBMISSION.SUBMITTED_AT, FORUM_SUBMISSION.ID)
            .limit(limit)
            .fetch { it.row() }

    /** Records that the waiting reminder for [id] went out; false when it already had (no version change: not an edit). */
    fun reminded(id: UUID): Boolean =
        tenantDsl
            .current()
            .update(FORUM_SUBMISSION)
            .set(FORUM_SUBMISSION.REMINDED_AT, now())
            .where(FORUM_SUBMISSION.ID.eq(id), FORUM_SUBMISSION.REMINDED_AT.isNull)
            .execute() == 1

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        /** The triage fields of a move by [actor] with [reason]. */
        fun triaged(
            actor: UUID,
            at: OffsetDateTime,
            reason: String?,
        ): Map<Field<*>, Any?> =
            mapOf(
                FORUM_SUBMISSION.TRIAGED_BY to actor,
                FORUM_SUBMISSION.TRIAGED_AT to at,
                FORUM_SUBMISSION.REASON to reason,
            )

        fun target(date: LocalDate?): Map<Field<*>, Any?> = mapOf(FORUM_SUBMISSION.TARGET_DATE to date)

        /** Off the agenda it was placed on (a deferral at the meeting, design E06 §1.4). */
        fun unplaced(): Map<Field<*>, Any?> = mapOf(FORUM_SUBMISSION.MEETING_ID to null, FORUM_SUBMISSION.MEETING_DATE to null)

        fun decided(
            outcome: ForumOutcome,
            actor: UUID,
            at: OffsetDateTime,
        ): Map<Field<*>, Any?> =
            mapOf(
                FORUM_SUBMISSION.OUTCOME to outcome.name,
                FORUM_SUBMISSION.DECIDED_BY to actor,
                FORUM_SUBMISSION.DECIDED_AT to at,
            )
    }
}

/** Where submissions are placed (design E03 §1.8): one agenda at a time, inside a tenant transaction. */
@Repository
class SubmissionPlacementStore(
    private val tenantDsl: TenantDsl,
) {
    /** Places [id] on [meetingId] if it is still ACCEPTED, unplaced and at [version]; false when it was not. */
    fun place(
        id: UUID,
        version: Long,
        meetingId: UUID,
        meetingDate: LocalDate,
    ): Boolean =
        tenantDsl
            .current()
            .update(FORUM_SUBMISSION)
            .set(FORUM_SUBMISSION.MEETING_ID, meetingId)
            .set(FORUM_SUBMISSION.MEETING_DATE, meetingDate)
            .set(FORUM_SUBMISSION.VERSION, FORUM_SUBMISSION.VERSION.plus(1))
            .where(
                FORUM_SUBMISSION.ID.eq(id),
                FORUM_SUBMISSION.VERSION.eq(version),
                FORUM_SUBMISSION.STATUS.eq(SubmissionStatus.ACCEPTED.name),
                FORUM_SUBMISSION.MEETING_ID.isNull,
            ).execute() == 1

    /** Clears [id]'s placement; false when it had none. */
    fun unplace(id: UUID): Boolean =
        tenantDsl
            .current()
            .update(FORUM_SUBMISSION)
            .setNull(FORUM_SUBMISSION.MEETING_ID)
            .setNull(FORUM_SUBMISSION.MEETING_DATE)
            .set(FORUM_SUBMISSION.VERSION, FORUM_SUBMISSION.VERSION.plus(1))
            .where(FORUM_SUBMISSION.ID.eq(id), FORUM_SUBMISSION.MEETING_ID.isNotNull)
            .execute() == 1

    /** [forumId]'s accepted submissions not placed on an agenda, oldest first, at most [limit]. */
    fun ready(
        forumId: UUID,
        limit: Int,
    ): List<SubmissionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_SUBMISSION)
            .where(
                FORUM_SUBMISSION.FORUM_ID.eq(forumId),
                FORUM_SUBMISSION.STATUS.eq(SubmissionStatus.ACCEPTED.name),
                FORUM_SUBMISSION.MEETING_ID.isNull,
            ).orderBy(FORUM_SUBMISSION.SUBMITTED_AT, FORUM_SUBMISSION.ID)
            .limit(limit)
            .fetch { it.row() }

    /** Moves the placements on [meetingId] to its new [date] (the meeting was rescheduled); the number of rows moved. */
    fun redate(
        meetingId: UUID,
        date: LocalDate,
    ): Int =
        tenantDsl
            .current()
            .update(FORUM_SUBMISSION)
            .set(FORUM_SUBMISSION.MEETING_DATE, date)
            .where(FORUM_SUBMISSION.MEETING_ID.eq(meetingId), FORUM_SUBMISSION.MEETING_DATE.ne(date))
            .execute()

    /**
     * Locks the rows of [ids] for this transaction (`SELECT … FOR UPDATE`), in id order. The lock order of placements
     * (design E03 §1.8): a submission's row before the meeting's and the agenda's.
     */
    fun lock(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        tenantDsl
            .current()
            .select(FORUM_SUBMISSION.ID)
            .from(FORUM_SUBMISSION)
            .where(FORUM_SUBMISSION.ID.`in`(ids))
            .orderBy(FORUM_SUBMISSION.ID)
            .forUpdate()
            .fetch()
    }

    /** Locks the rows of the submissions placed on [meetingId], in id order, as [lock] does. */
    fun lockOn(meetingId: UUID) {
        tenantDsl
            .current()
            .select(FORUM_SUBMISSION.ID)
            .from(FORUM_SUBMISSION)
            .where(FORUM_SUBMISSION.MEETING_ID.eq(meetingId))
            .orderBy(FORUM_SUBMISSION.ID)
            .forUpdate()
            .fetch()
    }

    /** The open submissions of [forumId] placed on an agenda. */
    fun placed(forumId: UUID): List<SubmissionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_SUBMISSION)
            .where(
                FORUM_SUBMISSION.FORUM_ID.eq(forumId),
                FORUM_SUBMISSION.STATUS.`in`(SubmissionStatus.entries.filter { it.open }.map { it.name }),
                FORUM_SUBMISSION.MEETING_ID.isNotNull,
            ).fetch { it.row() }
}

private fun ForumSubmissionRecord.row() =
    SubmissionRow(
        id = id,
        forumId = forumId,
        subjectType = subjectType,
        subjectId = subjectId,
        instanceId = instanceId,
        stepId = stepId,
        previousId = previousId,
        purpose = SubmissionPurpose.valueOf(purpose),
        resolutionSought = resolutionSought,
        presenterId = presenterId,
        status = SubmissionStatus.valueOf(status),
        targetDate = targetDate,
        submittedBy = submittedBy,
        submittedAt = submittedAt.toInstant(),
        triagedBy = triagedBy,
        triagedAt = triagedAt?.toInstant(),
        reason = reason,
        outcome = outcome?.let { ForumOutcome.valueOf(it) },
        decidedAt = decidedAt?.toInstant(),
        remindedAt = remindedAt?.toInstant(),
        version = version ?: 0,
        meetingId = meetingId,
        meetingDate = meetingDate,
    )
