package com.memo.forum.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
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

enum class SubmissionPurpose { DECISION, DISCUSSION, INFORMATION }

/** Where a submission stands (ADR-0059, design E02 §1). */
enum class SubmissionStatus(
    val open: Boolean,
) {
    QUEUED(open = true),
    ACCEPTED(open = true),
    DEFERRED(open = true),
    DECIDED(open = false),
    RETURNED(open = false),
    DECLINED(open = false),
    WITHDRAWN(open = false),
    REFERRED(open = false),
}

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
    )
