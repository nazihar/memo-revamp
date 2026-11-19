package com.memo.meeting.internal.rsvp

import com.memo.common.authz.ForbiddenException
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AudienceRow
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.MeetingAccess
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingRelation
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.actorOrNull
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Reading replies (design E05 §3, §4), inside a tenant transaction: the actor's own "Will you attend?", the replies of a
 * meeting (every answer, note and how for its officers; the counts for the people who answer it), and the viewer's
 * answer on the meeting and "My meetings". Invisible meetings answer 404, visible but forbidden reads 403.
 */
@Component
class ReplyReads(
    private val guard: MeetingGuard,
    private val access: MeetingAccess,
    private val audience: AudienceStore,
    private val store: RsvpStore,
    private val alternates: Alternates,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val workingDays: WorkingDays,
    private val users: UserSummaries,
) {
    /** The actor's own "Will you attend?" for [meetingId]; 403 when they do not answer it (no seat, guest or stand-in). */
    fun mine(meetingId: UUID): MyRsvpResponse {
        val (meeting, _) = guard.requireVisible(meetingId)
        val actor = actorOrNull()
        val rows = actor?.let { audience.rowsOf(meeting.id, it) }.orEmpty()
        val capacity = RsvpRules.capacity(rows)
        if (actor == null || capacity == null) throw ForbiddenException(null)
        return mine(meeting, actor, capacity, rows, store.find(meeting.id, actor))
    }

    /** The replies of [meetingId]: every answer for the officers; the counts for the people who answer it. */
    fun list(meetingId: UUID): RepliesResponse {
        val (meeting, relation) = guard.requireVisible(meetingId)
        val officer = relation == MeetingRelation.OFFICER
        val answers = officer || actorOrNull()?.let { RsvpRules.capacity(audience.rowsOf(meeting.id, it)) } != null
        if (!answers) throw ForbiddenException(null)
        return list(meeting, officer)
    }

    /** Every reply of [meeting] as its officers see them, by person (the invitations panel); the caller checks access. */
    fun ofOfficers(meeting: MeetingRow): Map<UUID, ReplyResponse> = list(meeting, officer = true).people.orEmpty().associateBy { it.userId }

    /** The viewer's answer for each of [meetings] they answer ("My meetings", the meeting): one query each for rows and answers. */
    fun summaries(meetings: Collection<MeetingRow>): Map<UUID, MeetingRsvpResponse> {
        val user = actorOrNull()
        val rows = if (user == null || meetings.isEmpty()) emptyMap() else audience.rowsOf(meetings.map { it.id }, user)
        val answers = if (user == null) emptyMap() else store.mine(rows.keys, user)
        return meetings
            .mapNotNull { meeting ->
                RsvpRules.capacity(rows[meeting.id].orEmpty())?.let { capacity ->
                    val answer = answers[meeting.id]
                    meeting.id to
                        MeetingRsvpResponse(
                            capacity = capacity,
                            answer = answer?.answer,
                            alternateAttends = answer?.alternateAttends ?: false,
                            stale = answer?.let { stale(it, meeting) } ?: false,
                            canAnswer = meeting.status == MeetingStatus.SCHEDULED,
                        )
                }
            }.toMap()
    }

    /** [actor]'s "Will you attend?" on [meeting], from their audience [rows] and their answer [row]. */
    fun mine(
        meeting: MeetingRow,
        actor: UUID,
        capacity: ReplyCapacity,
        rows: List<AudienceRow>,
        row: RsvpRow?,
    ): MyRsvpResponse {
        val standingInFor = rows.firstOrNull { it.source == AudienceSource.ALTERNATE }?.forUserId
        val alternate = if (capacity == ReplyCapacity.MEMBER) alternates.alternateOf(meeting, actor) else null
        val recordedBy = row?.recordedBy?.takeIf { row.onBehalf }
        val names = users.names(listOfNotNull(standingInFor, alternate, recordedBy))
        return MyRsvpResponse(
            meetingId = meeting.id,
            capacity = capacity,
            canAnswer = meeting.status == MeetingStatus.SCHEDULED,
            offered = RsvpRules.offered(meeting.mode),
            replyBy = replyBy(meeting),
            answer = row?.answer,
            alternateAttends = row?.alternateAttends ?: false,
            note = row?.note,
            how = row?.how,
            recordedBy = recordedBy?.let { ReplyPersonResponse(it, names[it].orEmpty()) },
            answeredAt = row?.answeredAt,
            forStartsAt = row?.forStartsAt,
            forEndsAt = row?.forEndsAt,
            stale = row?.let { stale(it, meeting) } ?: false,
            version = row?.version,
            alternate = alternate?.let { ReplyPersonResponse(it, names[it].orEmpty()) },
            alternateStandingIn = alternates.standing(meeting.id).any { it.forUserId == actor },
            standingInFor = standingInFor?.let { ReplyPersonResponse(it, names[it].orEmpty()) },
        )
    }

    private fun list(
        meeting: MeetingRow,
        officer: Boolean,
    ): RepliesResponse {
        val rows = audience.audience(meeting.id).groupBy { it.userId }
        val repliers = rows.mapNotNull { (user, own) -> RsvpRules.capacity(own)?.let { user to (it to own) } }.toMap()
        val answers = store.ofMeeting(meeting.id)
        val members = repliers.filterValues { it.first == ReplyCapacity.MEMBER }.keys
        return RepliesResponse(
            meetingId = meeting.id,
            counts = counts(repliers.keys, answers, meeting),
            members = counts(members, answers, meeting),
            canRecord = officer && access.manages(meeting.forumId) && meeting.status == MeetingStatus.SCHEDULED,
            people = if (officer) people(meeting, rows.values.flatten(), repliers, answers) else null,
        )
    }

    /** Each person who answers, with their answer, note and how, and who stands in for whom. */
    private fun people(
        meeting: MeetingRow,
        rows: List<AudienceRow>,
        repliers: Map<UUID, Pair<ReplyCapacity, List<AudienceRow>>>,
        answers: Map<UUID, RsvpRow>,
    ): List<ReplyResponse> {
        val standIns = rows.filter { it.source == AudienceSource.ALTERNATE }.associateBy { checkNotNull(it.forUserId) }
        val names = users.names(repliers.keys + standIns.keys + answers.values.map { it.recordedBy })

        fun person(id: UUID?) = id?.let { ReplyPersonResponse(it, names[it].orEmpty()) }
        return repliers
            .map { (user, capacityAndRows) ->
                val (capacity, own) = capacityAndRows
                val answer = answers[user]
                ReplyResponse(
                    userId = user,
                    name = names[user].orEmpty(),
                    capacity = capacity,
                    standingInFor = person(own.firstOrNull { it.source == AudienceSource.ALTERNATE }?.forUserId),
                    alternate = person(standIns[user]?.userId),
                    answer = answer?.answer,
                    alternateAttends = answer?.alternateAttends ?: false,
                    note = answer?.note,
                    how = answer?.how,
                    howNote = answer?.howNote,
                    recordedBy = person(answer?.recordedBy?.takeIf { answer.onBehalf }),
                    answeredAt = answer?.answeredAt,
                    stale = answer?.let { stale(it, meeting) } ?: false,
                    version = answer?.version,
                )
            }.sortedWith(compareBy({ it.capacity.ordinal }, { it.name.lowercase() }, { it.userId }))
    }

    private fun counts(
        users: Collection<UUID>,
        answers: Map<UUID, RsvpRow>,
        meeting: MeetingRow,
    ): ReplyCountsResponse {
        val given = users.mapNotNull { answers[it] }
        val (stale, current) = given.partition { stale(it, meeting) }
        return ReplyCountsResponse(
            inPerson = current.count { it.answer == RsvpAnswer.IN_PERSON },
            remote = current.count { it.answer == RsvpAnswer.REMOTE },
            apologies = current.count { it.answer == RsvpAnswer.APOLOGIES },
            stale = stale.size,
            noAnswer = users.size - given.size,
            total = users.size,
        )
    }

    /** The committee type's reply deadline for [meeting] (design E05 §1.8); null when it has none. */
    private fun replyBy(meeting: MeetingRow): LocalDate? {
        val days =
            directory
                .forum(meeting.forumId)
                ?.let { policies.version(it.typeId, meeting.typeVersion) }
                ?.meetings
                ?.replyDeadlineWorkingDays
                ?.takeIf { it > 0 }
        return days?.let { workingDays.before(meeting.date, it) }
    }

    private fun stale(
        row: RsvpRow,
        meeting: MeetingRow,
    ): Boolean = RsvpRules.stale(row.forStartsAt, row.forEndsAt, meeting.startsAt, meeting.endsAt)
}
