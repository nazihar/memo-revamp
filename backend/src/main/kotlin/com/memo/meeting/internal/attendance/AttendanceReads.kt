package com.memo.meeting.internal.attendance

import com.memo.common.authz.ForbiddenException
import com.memo.identity.UserSummaries
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceState
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.MeetingAccess
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingRelation
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.quorum.LiveQuorum
import com.memo.meeting.internal.quorum.QuorumAt
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Reading the register and the live quorum (design E05 §3, §4), inside a tenant transaction:
 *  - the register: every person and event for the officers (secretaries, the chair, `forums.manage`); the viewer's own row
 *    and the counts for the audience (members, alternates, guests, item attendees); 403 for `forum.read` alone;
 *  - the meeting-level quorum: the seats for officers, the numbers for members and alternates;
 *  - the quorum's changes: officers only.
 * Invisible meetings answer 404.
 */
@Component
class AttendanceReads(
    private val guard: MeetingGuard,
    private val access: MeetingAccess,
    private val audience: AudienceStore,
    private val store: AttendanceStore,
    private val log: MeetingProgressStore,
    private val live: LiveQuorum,
    private val users: UserSummaries,
) {
    fun register(meetingId: UUID): AttendanceResponse {
        val (meeting, relation) = guard.requireVisible(meetingId)
        if (relation == MeetingRelation.READER) throw ForbiddenException(null)
        val officer = relation == MeetingRelation.OFFICER
        val events = store.events(meeting.id)
        val now = live.evaluate(meeting, events = events)
        val people = people(meeting, events, now)
        val actor = actorOrNull()
        val names = users.names(events.mapNotNull { it.recordedBy })
        val voided = events.mapNotNull { it.voidsEventId }.toSet()
        return AttendanceResponse(
            meetingId = meeting.id,
            status = meeting.status,
            canRecord = officer && access.manages(meeting.forumId) && meeting.status in OPEN,
            correcting = meeting.status == MeetingStatus.HELD,
            itemInProgress = log.progress(meeting.id).lastOrNull()?.let { ItemProgressResponse(it.itemId, it.startedAt) },
            counts = counts(people),
            mine = people.firstOrNull { it.userId == actor },
            people = if (officer) people else null,
            events = if (officer) events.map { it.response(it.id in voided, names) } else null,
            quorum = quorumView(now, officer, relation),
        )
    }

    /** The meeting-level quorum at [at] (null: now): officers with the seats, members and alternates the numbers. */
    fun quorum(
        meetingId: UUID,
        at: Instant?,
    ): LiveQuorumResponse {
        val (meeting, relation) = guard.requireVisible(meetingId)
        val officer = relation == MeetingRelation.OFFICER
        return quorumView(live.evaluate(meeting, at), officer, relation) ?: throw ForbiddenException(null)
    }

    /** The meeting's quorum reached, lost and restored, oldest first: officers only. */
    fun changes(meetingId: UUID): List<QuorumChangeResponse> {
        val (meeting, relation) = guard.requireVisible(meetingId)
        if (relation != MeetingRelation.OFFICER) throw ForbiddenException(null)
        return log.changes(meeting.id).map {
            QuorumChangeResponse(it.id, it.kind, it.at, it.itemId, it.needed, it.present, it.unmet, it.causeEventId)
        }
    }

    /** What a write answers: [recorded]'s event, its person's row and the live quorum (officers' view). */
    fun recorded(
        meetingId: UUID,
        recorded: Recorded,
    ): RecordedAttendanceResponse {
        val (meeting, relation) = guard.requireVisible(meetingId)
        val events = store.events(meeting.id)
        val now = live.evaluate(meeting, events = events)
        val person = people(meeting, events, now).single { it.userId == recorded.row.userId }
        val names = users.names(listOfNotNull(recorded.event.recordedBy))
        return RecordedAttendanceResponse(
            event = recorded.event.response(false, names),
            person = person,
            quorum = checkNotNull(quorumView(now, true, relation)),
        )
    }

    /** Everyone on the register, and the audience not on it yet (EXPECTED, no version), by capacity and name. */
    private fun people(
        meeting: MeetingRow,
        events: List<AttendanceEventRow>,
        now: QuorumAt,
    ): List<AttendancePersonResponse> {
        val rows = store.rows(meeting.id).associateBy { it.userId }
        val pending =
            audience
                .audience(meeting.id)
                .groupBy { it.userId }
                .filterKeys { it !in rows }
                .mapNotNull { (user, own) ->
                    AttendanceRules.capacity(own)?.let { capacity ->
                        val stand =
                            own.firstOrNull { it.source == AudienceSource.ALTERNATE }.takeIf {
                                capacity ==
                                    AttendanceCapacity.ALTERNATE
                            }
                        AttendanceRow(meeting.id, user, capacity, stand?.forMemberId, stand?.forUserId, Standing(), Instant.EPOCH, -1)
                    }
                }
        val all = rows.values + pending
        val names = users.names(all.map { it.userId } + all.mapNotNull { it.forUserId })
        val roles = now.roster.groupBy { it.userId }.mapValues { (_, seats) -> seats.minOf { it.role } }
        val counted = now.seats.map { it.userId }.toSet()
        val threshold = AttendanceRules.lateAfter(meeting, log.progress(meeting.id))
        val byPerson = events.groupBy { it.userId }
        return all
            .map { row ->
                val standing = AttendanceRules.replay(byPerson[row.userId].orEmpty())
                AttendancePersonResponse(
                    userId = row.userId,
                    name = names[row.userId].orEmpty(),
                    capacity = row.capacity,
                    role = roles[row.userId],
                    standingInFor = row.forUserId?.let { AttendancePersonRef(it, names[it].orEmpty()) },
                    state = standing.state,
                    mode = standing.mode,
                    steppedOutOf = standing.steppedOutOf,
                    firstArrivedAt = standing.firstArrivedAt,
                    lastLeftAt = standing.lastLeftAt,
                    arrivedDuringItem = standing.arrivedDuringItem,
                    leftDuringItem = standing.leftDuringItem,
                    joinedLate = AttendanceRules.joinedLate(standing, threshold),
                    leftEarly = standing.state == AttendanceState.LEFT,
                    counted = row.userId in counted,
                    version = row.version.takeIf { it >= 0 },
                )
            }.sortedWith(compareBy({ it.capacity.ordinal }, { it.name.lowercase() }, { it.userId }))
    }

    private fun counts(people: List<AttendancePersonResponse>): AttendanceCountsResponse {
        fun count(state: AttendanceState) = people.count { it.state == state }
        val present = people.filter { it.state == AttendanceState.PRESENT }
        return AttendanceCountsResponse(
            inPerson = present.count { it.mode == AttendanceMode.IN_PERSON },
            remote = present.count { it.mode == AttendanceMode.REMOTE },
            left = count(AttendanceState.LEFT),
            apologies = count(AttendanceState.APOLOGIES),
            absent = count(AttendanceState.ABSENT),
            expected = count(AttendanceState.EXPECTED),
            total = people.size,
        )
    }

    /** The quorum as [relation] sees it: seats for officers, numbers for members and alternates, nothing for others. */
    private fun quorumView(
        quorum: QuorumAt,
        officer: Boolean,
        relation: MeetingRelation,
    ): LiveQuorumResponse? {
        if (!officer && relation != MeetingRelation.MEMBER && relation != MeetingRelation.ALTERNATE) return null
        val result = quorum.result
        val seats =
            if (officer) {
                val filled = quorum.seats.associateBy { it.memberId }
                val names = users.names(quorum.roster.map { it.userId } + quorum.seats.map { it.userId })
                quorum.roster.map { seat ->
                    val by = filled[seat.memberId]
                    QuorumSeatResponse(
                        memberId = seat.memberId,
                        holder = AttendancePersonRef(seat.userId, names[seat.userId].orEmpty()),
                        role = seat.role,
                        voting = seat.voting,
                        filledBy = by?.let { AttendancePersonRef(it.userId, names[it.userId].orEmpty()) },
                        byAlternate = by?.byAlternate ?: false,
                        remote = by?.remote ?: false,
                    )
                }
            } else {
                null
            }
        return LiveQuorumResponse(quorum.at, result.quorate, result.basis, result.needed, result.present, result.unmet, seats)
    }

    private fun AttendanceEventRow.response(
        voided: Boolean,
        names: Map<UUID, String>,
    ) = AttendanceEventResponse(
        id = id,
        userId = userId,
        kind = kind,
        mode = mode,
        at = at,
        itemId = itemId,
        voidsEventId = voidsEventId,
        reason = reason,
        note = note,
        source = source,
        correction = correction,
        voided = voided && kind != AttendanceEventKind.VOIDED,
        recordedBy = recordedBy?.let { AttendancePersonRef(it, names[it].orEmpty()) },
        recordedAt = recordedAt,
    )

    private companion object {
        /** The statuses in which a secretary records on the register (apologies only while scheduled; corrections once held). */
        val OPEN = setOf(MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS, MeetingStatus.HELD)
    }
}
