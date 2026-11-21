package com.memo.meeting.internal.attendance

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.web.FieldProblem
import com.memo.common.web.PreconditionRequiredException
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceChanged
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSource
import com.memo.meeting.AttendanceState
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.ItemStore
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.RevisionStore
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.requireNoProblems
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpRules
import com.memo.meeting.internal.rsvp.RsvpStore
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** One event a secretary records for [userId] (design E05 §3): its time defaults to now, its item to the item in progress. */
data class AttendanceInput(
    val userId: UUID,
    val kind: AttendanceEventKind,
    val mode: AttendanceMode? = null,
    val at: Instant? = null,
    val itemId: UUID? = null,
    val note: String? = null,
    /** Required for a correction after the meeting was held. */
    val reason: String? = null,
)

/** An event written and the person's register row after it. */
data class Recorded(
    val event: AttendanceEventRow,
    val row: AttendanceRow,
)

/**
 * The attendance register (ADR-0064, design E05 §1.6, §1.7, §9), in the transaction of the change and under the meeting's
 * row lock (so events, the start and the end serialise):
 *  - opened at `start`: a row per person of the audience, apologies from the replies written as APOLOGIES (source RSVP);
 *  - events recorded by a secretary (or `forums.manage`), one at a time with the person's row version as If-Match, or as
 *    "everyone who said yes is here"; voided with a reason; corrections after the meeting with a reason;
 *  - the item in progress;
 *  - after each change of a meeting in progress, the live meeting-level quorum: a flip is logged, audited and (lost or
 *    restored) told to the chair and the secretaries in the app.
 * Every event re-derives the person's row from their events, which must stay a valid sequence (409 `WRONG_STATE`).
 */
@Service
class Attendance(
    private val guard: MeetingGuard,
    private val meetings: MeetingStore,
    private val checks: RegisterChecks,
    private val log: MeetingProgressStore,
    private val audience: AudienceStore,
    private val rsvps: RsvpStore,
    private val store: AttendanceStore,
    private val quorum: QuorumChanges,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val publisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * Opens [meeting]'s register as it starts (design E05 §9): a row for each person of its audience, and APOLOGIES (source
     * RSVP) for every current answer of apologies not already recorded. In the start's transaction.
     */
    fun open(meeting: MeetingRow) {
        val now = clock.instant()
        audience.audience(meeting.id).groupBy { it.userId }.forEach { (user, rows) ->
            val capacity = AttendanceRules.capacity(rows) ?: return@forEach
            val stand = rows.firstOrNull { it.source == AudienceSource.ALTERNATE }.takeIf { capacity == AttendanceCapacity.ALTERNATE }
            store.open(AttendanceRow(meeting.id, user, capacity, stand?.forMemberId, stand?.forUserId, Standing(), now, 0))
        }
        val apologies =
            rsvps
                .ofMeeting(meeting.id)
                .values
                .filter { it.answer == RsvpAnswer.APOLOGIES }
                .filterNot { RsvpRules.stale(it.forStartsAt, it.forEndsAt, meeting.startsAt, meeting.endsAt) }
        apologies.forEach { reply ->
            val row = store.row(meeting.id, reply.userId)?.takeIf { it.standing.state == AttendanceState.EXPECTED } ?: return@forEach
            append(meeting, row, event(meeting, row.userId, AttendanceEventKind.APOLOGIES, now, source = AttendanceSource.RSVP))
        }
        publisher.publishEvent(AttendanceChanged(TenantContext.current().value, meeting.id))
    }

    /** Records [input] for its person; [version] is their row's (If-Match), required once they have a row. */
    fun record(
        meetingId: UUID,
        version: Long?,
        input: AttendanceInput,
    ): Recorded {
        val meeting = locked(meetingId)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("kind", "VOID_SEPARATELY", "Void an event with its own action.")
                    .takeIf { input.kind == AttendanceEventKind.VOIDED },
            ),
        )
        val correction = AttendanceRules.window(input.kind, meeting.status)
        val now = clock.instant()
        val at = input.at ?: now
        val timed = AttendanceRules.timed(input.kind)
        val item = if (timed) input.itemId ?: checks.itemAt(meeting.id, at) else null
        val (note, noteProblem) = RsvpRules.optional("note", input.note, AttendanceRules.MAX_NOTE)
        val (reason, reasonProblem) = RsvpRules.optional("reason", input.reason, AttendanceRules.MAX_REASON)
        val withMode = input.kind == AttendanceEventKind.ARRIVED || input.kind == AttendanceEventKind.MODE_CHANGED
        val stepping = input.kind == AttendanceEventKind.STEPPED_OUT || input.kind == AttendanceEventKind.STEPPED_BACK
        requireNoProblems(
            listOfNotNull(
                FieldProblem("mode", "MODE_REQUIRED", "Say whether they attend in person or online.").takeIf {
                    withMode &&
                        input.mode == null
                },
                AttendanceRules.time(input.kind, at, meeting, now),
                checks.itemProblem(meeting, input.itemId.takeIf { timed }, required = stepping && item == null),
                noteProblem,
                reasonProblem,
                RegisterChecks.reasonRequired(correction && reason == null),
            ),
        )
        val (row, existed) = checks.rowFor(meeting, input.userId)
        checks.precondition(row, existed, version)
        if (input.kind == AttendanceEventKind.ARRIVED) checks.countedOnce(meeting, row)
        val event =
            event(
                meeting,
                row.userId,
                input.kind,
                at,
                mode = input.mode.takeIf { withMode },
                itemId = item,
                note = note,
                reason = reason,
                correction = correction,
            )
        val after = append(meeting, row, event)
        changed(meeting, event)
        return Recorded(event, after)
    }

    /** "Everyone who said yes is here" (design E05 §3): an ARRIVED for each of [userIds], in one transaction, while in progress. */
    fun arrivals(
        meetingId: UUID,
        userIds: List<UUID>,
        mode: AttendanceMode?,
        at: Instant?,
    ): List<Recorded> {
        val meeting = locked(meetingId)
        AttendanceRules.window(AttendanceEventKind.ARRIVED, meeting.status)
        refuseIf(meeting.status != MeetingStatus.IN_PROGRESS, "WRONG_STATE") { "After the meeting, correct arrivals one at a time." }
        val now = clock.instant()
        val time = at ?: now
        val people = userIds.distinct()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("userIds", "LENGTH", "Choose 1 to ${AttendanceRules.MAX_ARRIVALS} people.")
                    .takeIf { people.isEmpty() || people.size > AttendanceRules.MAX_ARRIVALS },
                FieldProblem("mode", "MODE_REQUIRED", "Say whether they attend in person or online.").takeIf { mode == null },
                AttendanceRules.time(AttendanceEventKind.ARRIVED, time, meeting, now),
            ),
        )
        val item = checks.itemAt(meeting.id, time)
        val recorded =
            people.map { user ->
                val (row, _) = checks.rowFor(meeting, user)
                checks.countedOnce(meeting, row)
                val event = event(meeting, user, AttendanceEventKind.ARRIVED, time, mode = mode, itemId = item)
                Recorded(event, append(meeting, row, event))
            }
        changed(meeting, recorded.last().event)
        return recorded
    }

    /** Voids event [eventId] with [reason]; [version] is its person's row (If-Match). */
    fun void(
        meetingId: UUID,
        eventId: UUID,
        version: Long?,
        reason: String?,
    ): Recorded {
        val meeting = locked(meetingId)
        val correction = AttendanceRules.window(AttendanceEventKind.VOIDED, meeting.status)
        val target = store.event(meeting.id, eventId) ?: throw MeetingNotFoundException("attendance event")
        val (text, problem) = RsvpRules.optional("reason", reason, AttendanceRules.MAX_REASON)
        requireNoProblems(listOfNotNull(problem, RegisterChecks.reasonRequired(text == null)))
        val row = checkNotNull(store.row(meeting.id, target.userId)) { "register row of ${target.userId}" }
        checks.precondition(row, existed = true, version = version)
        AttendanceRules.voidable(target, store.eventsOf(meeting.id, row.userId))
        val event =
            event(
                meeting,
                row.userId,
                AttendanceEventKind.VOIDED,
                clock.instant(),
                voids = target.id,
                reason = text,
                correction = correction,
            )
        val after = append(meeting, row, event)
        changed(meeting, event)
        return Recorded(event, after)
    }

    /** "Now: item n" (design E05 §1.6.1): the item must be on the latest published revision or tabled; while in progress. */
    fun progress(
        meetingId: UUID,
        itemId: UUID,
    ): ItemProgressRow {
        val meeting = locked(meetingId)
        AttendanceRules.window(AttendanceEventKind.ARRIVED, meeting.status)
        refuseIf(
            meeting.status != MeetingStatus.IN_PROGRESS,
            "WRONG_STATE",
        ) { "The item in progress is set while the meeting is in progress." }
        requireNoProblems(listOfNotNull(checks.itemProblem(meeting, itemId, required = false)))
        val row = ItemProgressRow(ids.next(), meeting.id, itemId, clock.instant(), actorOrNull())
        log.insert(row)
        audit.record(
            "meeting.item.progressed",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after = mapOf("progressId" to row.id.toString(), "itemId" to itemId.toString(), "at" to row.startedAt.toString()),
        )
        quorum.progressed(meeting, row)
        publisher.publishEvent(AttendanceChanged(TenantContext.current().value, meeting.id))
        return row
    }

    /** Event [eventId] recorded earlier (a repeated request), with its person's row now. */
    fun recordedAgain(
        meetingId: UUID,
        eventId: UUID,
    ): Recorded {
        val meeting = guard.requireManaged(meetingId)
        val event = store.event(meeting.id, eventId) ?: throw MeetingNotFoundException("attendance event")
        return Recorded(event, checkNotNull(store.row(meeting.id, event.userId)))
    }

    /** The meeting the actor runs, locked for this transaction (404, 403). */
    private fun locked(meetingId: UUID): MeetingRow {
        val seen = guard.requireManaged(meetingId)
        return meetings.lock(seen.id) ?: throw MeetingNotFoundException()
    }

    /** Writes [event] for [row]'s person, re-deriving their row (409 `WRONG_STATE` when the sequence breaks), and audits it. */
    private fun append(
        meeting: MeetingRow,
        row: AttendanceRow,
        event: AttendanceEventRow,
    ): AttendanceRow {
        val standing = AttendanceRules.replay(store.eventsOf(meeting.id, row.userId) + event)
        store.insert(event)
        if (!store.update(meeting.id, row.userId, standing, row.version)) {
            throw ConcurrentModificationException("attendance ${row.userId}", row.version)
        }
        val details =
            mapOf(
                "eventId" to event.id.toString(),
                "userId" to event.userId.toString(),
                "kind" to event.kind.name,
                "mode" to event.mode?.name,
                "at" to event.at.toString(),
                "itemId" to event.itemId?.toString(),
                "source" to event.source.name,
                "correction" to event.correction,
                "state" to standing.state.name,
                "reasonSha256" to event.reason?.let(RsvpRules::sha256),
                "noteSha256" to event.note?.let(RsvpRules::sha256),
            )
        if (event.kind == AttendanceEventKind.VOIDED) {
            audit.record(
                "meeting.attendance.voided",
                MeetingLifecycle.OBJECT_TYPE,
                meeting.id.toString(),
                after = details + ("voidsEventId" to event.voidsEventId.toString()),
            )
        } else {
            audit.record("meeting.attendance.recorded", MeetingLifecycle.OBJECT_TYPE, meeting.id.toString(), after = details)
        }
        return checkNotNull(store.row(meeting.id, row.userId))
    }

    /** After a change: the live quorum while in progress, and [AttendanceChanged] for E06. */
    private fun changed(
        meeting: MeetingRow,
        cause: AttendanceEventRow,
    ) {
        if (meeting.status == MeetingStatus.IN_PROGRESS) quorum.check(meeting, cause, checks.itemAt(meeting.id, clock.instant()))
        publisher.publishEvent(AttendanceChanged(TenantContext.current().value, meeting.id))
    }

    @Suppress("LongParameterList")
    private fun event(
        meeting: MeetingRow,
        userId: UUID,
        kind: AttendanceEventKind,
        at: Instant,
        mode: AttendanceMode? = null,
        itemId: UUID? = null,
        voids: UUID? = null,
        note: String? = null,
        reason: String? = null,
        correction: Boolean = false,
        source: AttendanceSource = AttendanceSource.RECORDED,
    ) = AttendanceEventRow(
        id = ids.next(),
        meetingId = meeting.id,
        userId = userId,
        kind = kind,
        mode = mode,
        at = at,
        itemId = itemId,
        voidsEventId = voids,
        reason = reason,
        note = note,
        source = source,
        correction = correction,
        recordedBy = actorOrNull(),
        recordedAt = clock.instant(),
    )
}
