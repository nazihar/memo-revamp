package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.forum.ForumStatus
import com.memo.forum.SubmissionPlacements
import com.memo.meeting.MeetingChange
import com.memo.meeting.MeetingChanged
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.invite.MeetingInvitations
import com.memo.org.OrgDirectory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/** What a secretary sends to create a meeting or change its place and labels (design E03 §3). */
data class MeetingInput(
    val kind: MeetingKind,
    val place: MeetingPlace,
    /** Null: the forum's level. */
    val classificationId: UUID?,
)

/** New times for a meeting: local date-times in [zone] (null: the tenant's zone). */
data class TimesInput(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val zone: String?,
)

/**
 * Meetings of a forum (ADR-0060, design E03 §1.1): created as a DRAFT by its secretaries (or `forums.manage`), edited,
 * scheduled, rescheduled, started, ended and cancelled along the state machine ([MeetingAction]), each change guarded by
 * status and version and audited with before and after in the business transaction. Inside a tenant transaction.
 */
@Service
class MeetingLifecycle(
    private val store: MeetingStore,
    private val guard: MeetingGuard,
    private val audience: MeetingAudience,
    private val notifier: MeetingNotifier,
    private val placing: AgendaPlacing,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val org: OrgDirectory,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val placements: SubmissionPlacements,
    private val sweeps: MeetingSweepStore,
    private val events: ApplicationEventPublisher,
    private val invitations: MeetingInvitations,
) {
    fun create(
        forumId: UUID,
        input: MeetingInput,
        times: TimesInput,
    ): MeetingRow {
        val forum = guard.requireManagedForum(forumId)
        refuseIf(forum.status == ForumStatus.DISSOLVED, "FORUM_DISSOLVED") { "This committee is dissolved and holds no more meetings." }
        val typeVersion = policies.published(forum.typeId)?.version
        refuseIf(typeVersion == null, "TYPE_NOT_PUBLISHED") { "The committee's type has no published version." }
        val (place, placeProblems) = MeetingFields.place(input.place)
        val (resolved, timeProblems) = MeetingTimes.resolve(times.local(), org.zone())
        val level = input.classificationId ?: guard.forumLevel(forum, checkNotNull(typeVersion))
        requireNoProblems(placeProblems + timeProblems + listOfNotNull(guard.classification(level, forum, checkNotNull(typeVersion))))
        val at = checkNotNull(resolved)
        val row =
            MeetingRow(
                id = ids.next(),
                forumId = forum.id,
                typeVersion = typeVersion,
                kind = input.kind,
                title = place.title,
                startsAt = at.startsAt,
                endsAt = at.endsAt,
                zone = at.zone,
                mode = place.mode,
                location = place.location,
                joinUrl = place.joinUrl,
                joinDetails = place.joinDetails,
                classificationId = level,
                number = null,
                numberYear = null,
                label = null,
                status = MeetingStatus.DRAFT,
                statusReason = null,
                createdBy = actorOrNull(),
                createdAt = store.now().toInstant(),
                version = 0,
            )
        store.insert(row)
        val created = checkNotNull(store.find(row.id))
        audit.record("meeting.created", OBJECT_TYPE, row.id.toString(), after = audited(created))
        changed(created.id, MeetingChange.CREATED)
        audience.recompute(created)
        return created
    }

    /** Title, kind, place, mode, link and classification; times change through [reschedule]. */
    fun edit(
        id: UUID,
        version: Long,
        input: MeetingInput,
    ): MeetingRow {
        val before = guard.requireManaged(id)
        MeetingAction.EDIT.next(before.status)
        val forum = forum(before)
        val (place, problems) = MeetingFields.place(input.place)
        val level = input.classificationId ?: guard.forumLevel(forum, before.typeVersion)
        requireNoProblems(problems + listOfNotNull(guard.classification(level, forum, before.typeVersion)))
        val after =
            before.copy(
                kind = input.kind,
                title = place.title,
                mode = place.mode,
                location = place.location,
                joinUrl = place.joinUrl,
                joinDetails = place.joinDetails,
                classificationId = level,
            )
        return save("meeting.updated", before, after, version).also { invitations.changed(before, it) }
    }

    fun schedule(
        id: UUID,
        version: Long,
    ): MeetingRow {
        val before = guard.requireManaged(id)
        val status = MeetingAction.SCHEDULE.next(before.status)
        refuseIf(forum(before).status == ForumStatus.DISSOLVED, "FORUM_DISSOLVED") { "This committee is dissolved." }
        return save("meeting.scheduled", before, before.copy(status = status), version).also(invitations::scheduled)
    }

    /** New times (and zone); a scheduled meeting needs a reason, its audience follows the new date. */
    fun reschedule(
        id: UUID,
        version: Long,
        times: TimesInput,
        reason: String?,
    ): MeetingRow {
        val before = guard.requireManaged(id)
        MeetingAction.RESCHEDULE.next(before.status)
        val (resolved, problems) = MeetingTimes.resolve(times.local(), org.zone())
        val (text, reasonProblem) = MeetingFields.reason(reason)
        val needsReason = before.status != MeetingStatus.DRAFT
        requireNoProblems(problems + listOfNotNull(reasonProblem?.takeIf { needsReason || text != null }))
        val at = checkNotNull(resolved)
        val after = before.copy(startsAt = at.startsAt, endsAt = at.endsAt, zone = at.zone)
        // The placements follow the new date: their rows are locked before the meeting's (design E03 §1.8).
        placements.lockOn(before.id)
        val saved = save("meeting.rescheduled", before, after, version, mapOf("reason" to text))
        audience.recompute(saved)
        placing.redate(saved)
        invitations.changed(before, saved)
        // The sweep's reminders were for the old date: the new one reminds again (design E03 §9).
        sweeps.clear(saved.id)
        return saved
    }

    fun start(
        id: UUID,
        version: Long,
    ): MeetingRow {
        val before = guard.requireManaged(id)
        return save("meeting.started", before, before.copy(status = MeetingAction.START.next(before.status)), version)
    }

    fun end(
        id: UUID,
        version: Long,
    ): MeetingRow {
        val before = guard.requireManaged(id)
        return save("meeting.ended", before, before.copy(status = MeetingAction.END.next(before.status)), version)
    }

    /**
     * Discards a draft or calls off a scheduled meeting, with a reason: its agenda freezes and its submissions go back to the
     * tray (design E03 §1.1); a scheduled meeting's audience is told.
     */
    fun cancel(
        id: UUID,
        version: Long,
        reason: String?,
    ): MeetingRow {
        val before = guard.requireManaged(id)
        val status = MeetingAction.CANCEL.next(before.status)
        val (text, problem) = MeetingFields.reason(reason)
        requireNoProblems(listOfNotNull(problem))
        // Its submissions go back to the tray: their rows are locked before the meeting's and the agenda's (design E03 §1.8).
        placements.lockOn(before.id)
        val saved = save("meeting.cancelled", before, before.copy(status = status, statusReason = text), version)
        placing.released(saved)
        if (before.status == MeetingStatus.SCHEDULED) {
            // Those who hold the event are told by the reconciler, with the CANCEL file (design E05 §1.1).
            val invited = invitations.cancelled(saved)
            notifier.cancelled(saved, forum(saved), invited)
        }
        return saved
    }

    private fun save(
        action: String,
        before: MeetingRow,
        after: MeetingRow,
        version: Long,
        extra: Map<String, Any?> = emptyMap(),
    ): MeetingRow {
        if (!store.update(after, before.status, version)) throw ConcurrentModificationException("meeting ${before.id}", version)
        val saved = checkNotNull(store.find(before.id))
        audit.record(action, OBJECT_TYPE, before.id.toString(), before = audited(before), after = audited(saved) + extra)
        changed(saved.id, CHANGES.getValue(action))
        return saved
    }

    /** Publishes [MeetingChanged] for E04–E07, in the changing transaction. */
    private fun changed(
        id: UUID,
        change: MeetingChange,
    ) = events.publishEvent(MeetingChanged(TenantContext.current().value, id, change))

    private fun forum(meeting: MeetingRow): ForumRef = checkNotNull(directory.forum(meeting.forumId)) { "forum ${meeting.forumId}" }

    companion object {
        const val OBJECT_TYPE = "meeting"

        private val CHANGES =
            mapOf(
                "meeting.updated" to MeetingChange.UPDATED,
                "meeting.scheduled" to MeetingChange.SCHEDULED,
                "meeting.rescheduled" to MeetingChange.RESCHEDULED,
                "meeting.started" to MeetingChange.STARTED,
                "meeting.ended" to MeetingChange.ENDED,
                "meeting.cancelled" to MeetingChange.CANCELLED,
            )

        fun audited(meeting: MeetingRow): Map<String, Any?> =
            mapOf(
                "forumId" to meeting.forumId.toString(),
                "typeVersion" to meeting.typeVersion,
                "kind" to meeting.kind.name,
                "title" to meeting.title,
                "startsAt" to meeting.startsAt.toString(),
                "endsAt" to meeting.endsAt.toString(),
                "timeZone" to meeting.zone.id,
                "mode" to meeting.mode.name,
                "location" to meeting.location,
                "joinUrl" to meeting.joinUrl,
                "classificationId" to meeting.classificationId.toString(),
                "status" to meeting.status.name,
                "statusReason" to meeting.statusReason,
            )
    }
}

private fun TimesInput.local() = LocalTimes(startsAt, endsAt, zone?.trim()?.ifEmpty { null })
