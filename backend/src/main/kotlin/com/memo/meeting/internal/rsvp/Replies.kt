package com.memo.meeting.internal.rsvp

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.PreconditionRequiredException
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRef
import com.memo.identity.UserSummaries
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.requireNoProblems
import com.memo.notification.NotificationKind
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/** An answer: for oneself, or recorded for someone by a secretary, saying [how] they gave it. */
data class RsvpInput(
    val answer: RsvpAnswer,
    val alternateAttends: Boolean = false,
    val note: String? = null,
    val how: RsvpHow? = null,
    val howNote: String? = null,
)

/**
 * Replies to a meeting's invitation (ADR-0063, design E05 §1.4, §1.5, §3, §4), inside a tenant transaction:
 *  - a member, an alternate standing in or a guest answers for themselves while the meeting is scheduled; a secretary
 *    (or `forums.manage`) records an answer given by email, phone or in person, and the person is told;
 *  - apologies may bring the seat's alternate ([Alternates]); changing the answer releases them;
 *  - an answer given for other times is stale after a reschedule (only a new date or time asks again);
 *  - every answer is a guarded change of the current row (If-Match once one exists) with a history row, audited with the
 *    note's hash, never its text, under the meeting's row lock (a reply and a reschedule or the start serialise).
 * The reads are [ReplyReads]; [mine] and [list] are theirs. Invisible meetings answer 404, forbidden actions 403.
 */
@Service
class Replies(
    private val guard: MeetingGuard,
    private val meetings: MeetingStore,
    private val audience: AudienceStore,
    private val store: RsvpStore,
    private val reads: ReplyReads,
    private val alternates: Alternates,
    private val notifier: MeetingNotifier,
    private val directory: ForumDirectory,
    private val workingDays: WorkingDays,
    private val users: UserSummaries,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun mine(meetingId: UUID): MyRsvpResponse = reads.mine(meetingId)

    fun list(meetingId: UUID): RepliesResponse = reads.list(meetingId)

    /** The actor answers [meetingId] for themselves; [version] (If-Match) is required once they have an answer. */
    fun answer(
        meetingId: UUID,
        version: Long?,
        input: RsvpInput,
    ): MyRsvpResponse {
        val (seen, _) = guard.requireVisible(meetingId)
        val meeting = meetings.lock(seen.id) ?: throw MeetingNotFoundException()
        val actor = actorOrNull()
        val capacity = actor?.let { RsvpRules.capacity(audience.rowsOf(meeting.id, it)) }
        if (actor == null || capacity == null) throw ForbiddenException(null)
        RsvpRules.window(meeting.status)
        val row = apply(meeting, actor, actor, capacity, version, input.copy(how = RsvpHow.IN_APP, howNote = null))
        return reads.mine(meeting, actor, capacity, audience.rowsOf(meeting.id, actor), row)
    }

    /**
     * A secretary (or `forums.manage`) records [userId]'s answer given outside the app; the person must answer the
     * meeting, and not be the recorder (422 `NOT_INVITABLE`), and is told. [version] (If-Match) is required once they have
     * an answer.
     */
    fun record(
        meetingId: UUID,
        userId: UUID,
        version: Long?,
        input: RsvpInput,
    ): ReplyResponse {
        val seen = guard.requireManaged(meetingId)
        val actor = actorOrNull() ?: throw ForbiddenException(Permission.FORUMS_MANAGE)
        val meeting = meetings.lock(seen.id) ?: throw MeetingNotFoundException()
        RsvpRules.window(meeting.status)
        val capacity = RsvpRules.capacity(audience.rowsOf(meeting.id, userId))
        requireNoProblems(
            listOfNotNull(
                FieldProblem("userId", "NOT_INVITABLE", "Answer for yourself on the meeting page.").takeIf { userId == actor },
                FieldProblem("userId", "NOT_INVITABLE", "This person is not invited to the meeting.").takeIf { capacity == null },
                RsvpRules.how(input.how),
            ).take(1),
        )
        apply(meeting, userId, actor, checkNotNull(capacity), version, input)
        return reads.ofOfficers(meeting).getValue(userId)
    }

    /** Writes [target]'s answer from [input] by [actor] (themselves or a secretary), with its alternate, history, audit and notices. */
    @Suppress("LongParameterList")
    private fun apply(
        meeting: MeetingRow,
        target: UUID,
        actor: UUID,
        capacity: ReplyCapacity,
        version: Long?,
        input: RsvpInput,
    ): RsvpRow {
        val onBehalf = target != actor
        val (note, howNote) = validate(meeting, capacity, input, onBehalf)
        val current = store.find(meeting.id, target)
        if (current != null && version == null) throw PreconditionRequiredException()
        if (version != current?.version) throw ConcurrentModificationException("rsvp $target", version ?: -1)
        val forum = checkNotNull(directory.forum(meeting.forumId)) { "forum ${meeting.forumId}" }
        // The alternate first: a refusal leaves the answer as it was.
        if (input.alternateAttends) {
            alternates.ask(meeting, forum, target)
        } else if (capacity == ReplyCapacity.MEMBER) {
            alternates.release(meeting, forum, target, ReleaseReason.ANSWER)
        }
        val row =
            RsvpRow(
                meetingId = meeting.id,
                userId = target,
                answer = input.answer,
                alternateAttends = input.alternateAttends,
                note = note,
                forStartsAt = meeting.startsAt,
                forEndsAt = meeting.endsAt,
                recordedBy = actor,
                how = if (onBehalf) checkNotNull(input.how) else RsvpHow.IN_APP,
                howNote = howNote,
                answeredAt = clock.instant(),
                version = (current?.version ?: -1) + 1,
            )
        write(current, row)
        audit(meeting, current, row, onBehalf)
        tell(meeting, forum, current, row, onBehalf)
        return row
    }

    /** The answer's checks (422) and refusals (409); the cleaned note and how-note. */
    private fun validate(
        meeting: MeetingRow,
        capacity: ReplyCapacity,
        input: RsvpInput,
        onBehalf: Boolean,
    ): Pair<String?, String?> {
        val (note, noteProblem) = RsvpRules.optional("note", input.note, RsvpRules.MAX_NOTE)
        val (howNote, howNoteProblem) = RsvpRules.optional("howNote", input.howNote.takeIf { onBehalf }, RsvpRules.MAX_HOW_NOTE)
        requireNoProblems(
            listOfNotNull(
                RsvpRules.answer(input.answer, meeting.mode),
                RsvpRules.alternate(input.answer, input.alternateAttends),
                noteProblem,
                howNoteProblem,
            ),
        )
        refuseIf(input.alternateAttends && capacity != ReplyCapacity.MEMBER, "ALTERNATE_NONE") {
            "Only a member's seat has an alternate who may attend in their place."
        }
        return note to howNote
    }

    /** The current row and its history row; 412 when someone else answered in between. */
    private fun write(
        current: RsvpRow?,
        row: RsvpRow,
    ) {
        val written = if (current == null) store.insert(row) else store.update(row, current.version)
        if (!written) throw ConcurrentModificationException("rsvp ${row.userId}", current?.version ?: -1)
        store.event(
            RsvpEventRow(
                id = ids.next(),
                meetingId = row.meetingId,
                userId = row.userId,
                answer = row.answer,
                alternateAttends = row.alternateAttends,
                noteSha256 = row.note?.let(RsvpRules::sha256),
                forStartsAt = row.forStartsAt,
                forEndsAt = row.forEndsAt,
                how = row.how,
                recordedBy = row.recordedBy,
                at = row.answeredAt,
            ),
        )
    }

    private fun audit(
        meeting: MeetingRow,
        before: RsvpRow?,
        after: RsvpRow,
        onBehalf: Boolean,
    ) {
        audit.record(
            "meeting.rsvp.answered",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            before =
                before?.let {
                    mapOf(
                        "userId" to it.userId.toString(),
                        "answer" to it.answer.name,
                        "alternateAttends" to it.alternateAttends,
                        "stale" to RsvpRules.stale(it.forStartsAt, it.forEndsAt, meeting.startsAt, meeting.endsAt),
                    )
                },
            after =
                mapOf(
                    "userId" to after.userId.toString(),
                    "answer" to after.answer.name,
                    "alternateAttends" to after.alternateAttends,
                    "how" to after.how.name,
                    "onBehalfBy" to after.recordedBy.takeIf { onBehalf }?.toString(),
                    "noteSha256" to after.note?.let(RsvpRules::sha256),
                    "howNoteSha256" to after.howNote?.let(RsvpRules::sha256),
                ),
        )
    }

    /**
     * The person is told of an answer recorded for them (in the app and by email); the secretaries, in the app, of
     * apologies and of an answer changed within two working days of the meeting (design E05 §1.4: less noise).
     */
    private fun tell(
        meeting: MeetingRow,
        forum: ForumRef,
        before: RsvpRow?,
        after: RsvpRow,
        onBehalf: Boolean,
    ) {
        val answer = mapOf("answer" to after.answer.name)
        if (onBehalf) {
            notifier.person(after.userId, NotificationKind.MEETING_RSVP_RECORDED, meeting, forum, answer + ("how" to after.how.name))
        }
        val apologies =
            after.answer == RsvpAnswer.APOLOGIES &&
                (before?.answer != RsvpAnswer.APOLOGIES || before.alternateAttends != after.alternateAttends)
        val lateChange = before != null && before.answer != after.answer && late(meeting)
        if (apologies || lateChange) {
            val details = answer + ("member" to users.of(after.userId)?.displayName.orEmpty())
            (notifier.secretaries(forum.id) - after.userId).forEach {
                notifier.person(it, NotificationKind.MEETING_RSVP_CHANGED, meeting, forum, details)
            }
        }
    }

    /** Today, in the meeting's zone, is within two working days of its date. */
    private fun late(meeting: MeetingRow): Boolean {
        val from = workingDays.before(meeting.date, LATE_WORKING_DAYS)
        return from != null &&
            !clock
                .instant()
                .atZone(meeting.zone)
                .toLocalDate()
                .isBefore(from)
    }

    private companion object {
        /** Answers changed this close to the meeting are told to the secretaries (design E05 §1.4). */
        const val LATE_WORKING_DAYS = 2
    }
}
