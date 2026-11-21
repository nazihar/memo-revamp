package com.memo.forum

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.refuseIf
import com.memo.forum.internal.ForumNotFoundException
import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.SubmissionPlacementStore
import com.memo.forum.internal.SubmissionRow
import com.memo.forum.internal.SubmissionStore
import com.memo.forum.internal.SubmissionSupport
import com.memo.forum.internal.SubmissionTriage
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** Why a paper went to a committee (ADR-0059); an agenda item takes it as its purpose. */
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

/** A submission as the meeting module places it (design E03 §1.8): its subject and cover sheet, and its placement. */
data class SubmissionRef(
    val id: UUID,
    val forumId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val purpose: SubmissionPurpose,
    val resolutionSought: String?,
    val presenterId: UUID?,
    val status: SubmissionStatus,
    val targetDate: LocalDate?,
    val meetingId: UUID?,
    val meetingDate: LocalDate?,
    val version: Long,
    /** Its memo waits at the forum step for the decision; false for one sent "for information" (it passed through). */
    val waits: Boolean = true,
)

/**
 * Submissions on agendas (ADR-0060, design E03 §1.8, §2): the forum records which meeting a submission is placed on, so
 * the memo's forum panel shows it without knowing meetings; the meeting module decides who may place and where. A
 * submission is on at most one agenda. Inside a tenant transaction; audited as `forum.submission.placed` and `.unplaced`.
 */
@Component
class SubmissionPlacements(
    private val store: SubmissionStore,
    private val placementStore: SubmissionPlacementStore,
    private val triage: SubmissionTriage,
    private val support: SubmissionSupport,
    private val audit: AuditLog,
) {
    fun find(id: UUID): SubmissionRef? = store.find(id)?.ref()

    /**
     * Locks the rows of [submissionIds] for the transaction, in id order. The lock order of placements (design E03 §1.8,
     * as built in T3): a change that may place or unplace a submission takes its row first, then the meeting's and the
     * agenda's, as a triage does (the submission, then the agenda through [ForumSubmissionChanged]); never the reverse.
     */
    fun lock(submissionIds: Collection<UUID>) = placementStore.lock(submissionIds)

    /** Locks the rows of the submissions placed on [meetingId], as [lock] does (cancelling or rescheduling the meeting). */
    fun lockOn(meetingId: UUID) = placementStore.lockOn(meetingId)

    /**
     * Places [submissionId] of [forumId] on [meetingId], held on [meetingDate]. A QUEUED submission is accepted first (by
     * the actor, audited as `forum.submission.accepted`); anything else but ACCEPTED is refused (`SUBMISSION_STATE`), and
     * so is one already placed (`ONE_PLACEMENT`). Unknown, or of another forum: 404.
     */
    fun place(
        forumId: UUID,
        submissionId: UUID,
        meetingId: UUID,
        meetingDate: LocalDate,
    ): SubmissionRef {
        val found = store.find(submissionId)?.takeIf { it.forumId == forumId } ?: throw ForumNotFoundException("submission")
        refuseIf(found.meetingId != null, "ONE_PLACEMENT") { "This submission is already on an agenda." }
        val row = if (found.status == SubmissionStatus.QUEUED) triage.accept(forumId, found.id, found.version, null) else found
        refuseIf(row.status != SubmissionStatus.ACCEPTED, "SUBMISSION_STATE") {
            "Only an accepted submission can be placed on an agenda; this one is ${row.status.name.lowercase()}."
        }
        if (!placementStore.place(row.id, row.version, meetingId, meetingDate)) {
            throw ConcurrentModificationException("submission ${row.id}", row.version)
        }
        val after = checkNotNull(store.find(row.id))
        audit.record("forum.submission.placed", ForumSubmissions.OBJECT_TYPE, row.id.toString(), after = placement(after))
        support.changed(after)
        return after.ref()
    }

    /** Clears [submissionId]'s placement (its item left the agenda): back in the tray if it is still open. Idempotent. */
    fun unplace(submissionId: UUID): SubmissionRef? {
        val before = store.find(submissionId) ?: return null
        if (placementStore.unplace(submissionId)) {
            audit.record("forum.submission.unplaced", ForumSubmissions.OBJECT_TYPE, submissionId.toString(), before = placement(before))
            support.changed(before)
        }
        return store.find(submissionId)?.ref()
    }

    /**
     * The tray, "Ready for an agenda" (design E03 §1.7): [forumId]'s accepted submissions not placed on an agenda, those
     * meant for a meeting on or before [on] first (by their target date), then the rest by age.
     */
    fun ready(
        forumId: UUID,
        on: LocalDate?,
    ): List<SubmissionRef> = trayOrder(placementStore.ready(forumId, MAX_TRAY), on).map { it.ref() }

    /**
     * The meeting [meetingId] moved to [date]: its placements follow, so the memo's forum panel shows the new date. A
     * derived field: the meeting's own `meeting.rescheduled` audit records the change.
     */
    fun redate(
        meetingId: UUID,
        date: LocalDate,
    ) {
        placementStore.redate(meetingId, date)
    }

    /** The ids of [forumId]'s open submissions placed on an agenda. */
    fun placed(forumId: UUID): Set<UUID> = placementStore.placed(forumId).map { it.id }.toSet()

    private fun placement(row: SubmissionRow): Map<String, Any?> =
        mapOf(
            "forumId" to row.forumId.toString(),
            "status" to row.status.name,
            "meetingId" to row.meetingId?.toString(),
            "meetingDate" to row.meetingDate?.toString(),
        )
}

/** The most entries the tray lists. */
private const val MAX_TRAY = 500

/** The tray's order (design E03 §1.7): due on or before [on] first, by target date; then by when they were submitted. */
internal fun trayOrder(
    rows: List<SubmissionRow>,
    on: LocalDate?,
): List<SubmissionRow> {
    fun due(row: SubmissionRow): Boolean {
        val target = row.targetDate
        return target != null && (on == null || !target.isAfter(on))
    }
    return rows.sortedWith(
        compareBy<SubmissionRow>({ if (due(it)) 0 else 1 }, { if (due(it)) it.targetDate else null }, { it.submittedAt }, { it.id }),
    )
}

/** The submission as other modules see it. */
internal fun SubmissionRow.ref() =
    SubmissionRef(
        id = id,
        forumId = forumId,
        subjectType = subjectType,
        subjectId = subjectId,
        purpose = purpose,
        resolutionSought = resolutionSought,
        presenterId = presenterId,
        status = status,
        targetDate = targetDate,
        meetingId = meetingId,
        meetingDate = meetingDate,
        version = version,
        waits = waits,
    )
