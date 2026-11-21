package com.memo.meeting.internal.quorum

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumSeat
import com.memo.forum.Quorum
import com.memo.forum.QuorumCounts
import com.memo.forum.QuorumResult
import com.memo.forum.QuorumRule
import com.memo.meeting.Recusals
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.RevisionStore
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpRow
import com.memo.meeting.internal.rsvp.RsvpRules
import com.memo.meeting.internal.rsvp.RsvpStore
import org.springframework.stereotype.Component
import java.util.UUID

/** One published item's forecast: its number, the result, and how many counting members are recused from it. */
data class ItemForecast(
    val itemId: UUID,
    val number: String?,
    val result: QuorumResult,
    /** Counting members in office who are recused from the item (E04's "N members have declared an interest"). */
    val declared: Int,
    /** The people recused from the item. */
    val recused: Set<UUID>,
)

/** The forecast of a meeting (design E05 §1.7): "if everyone who said yes attends", for the meeting and each item. */
data class Forecast(
    val result: QuorumResult,
    val items: List<ItemForecast>,
)

/**
 * The quorum forecast from the replies (ADR-0064, design E05 §1.7): the same [PresenceBuilder] and [Quorum.evaluate] as
 * the live quorum, with "present" = an answer IN_PERSON or REMOTE given for the meeting's current times (a stale answer
 * counts as no answer), and the alternates asked to attend (unless they sent apologies themselves), in the mode they
 * answered. Per item of the latest published revision, with E04's recusals (and a recused member's alternate). Inside a
 * tenant transaction; the caller decides access. E06's planning warning (E04 §1.8) is the same with every member present.
 */
@Component
class QuorumForecast(
    private val live: LiveQuorum,
    private val directory: ForumDirectory,
    private val rsvps: RsvpStore,
    private val audience: AudienceStore,
    private val recusals: Recusals,
    private val revisions: RevisionStore,
) {
    fun of(meeting: MeetingRow): Forecast {
        val rule = live.rule(meeting)
        val roster = directory.membersOn(meeting.forumId, meeting.date)
        val replies =
            rsvps
                .ofMeeting(meeting.id)
                .filterValues { !RsvpRules.stale(it.forStartsAt, it.forEndsAt, meeting.startsAt, meeting.endsAt) }
        val alternates = audience.audience(meeting.id).filter { it.source == AudienceSource.ALTERNATE && it.forMemberId != null }
        val standIns = alternates.associate { checkNotNull(it.forMemberId) to it.userId }
        val present = expected(replies, alternates.map { it.userId })
        val recusedByItem = recusals.ofMeeting(meeting.id)
        val items =
            revisions
                .heads(meeting.id)
                .maxByOrNull { it.number }
                ?.let { revisions.snapshot(it.id).items }
                .orEmpty()
                .filter { !it.removed && it.kind != ItemKind.BREAK }
                .map { item ->
                    val recused = recusedByItem[item.itemId].orEmpty()
                    val seats = PresenceBuilder.build(roster, standIns, present, item.itemId, recused)
                    ItemForecast(item.itemId, item.number, evaluate(rule, roster, seats), declared(rule, roster, recused), recused)
                }
        return Forecast(evaluate(rule, roster, PresenceBuilder.build(roster, standIns, present)), items)
    }

    private fun evaluate(
        rule: QuorumRule,
        roster: List<ForumSeat>,
        seats: List<SeatPresence>,
    ): QuorumResult = Quorum.evaluate(rule, PresenceBuilder.seats(roster), PresenceBuilder.attendance(seats))

    companion object {
        /**
         * Who is expected from the replies: everyone who said yes (online: remote), and each alternate asked to attend
         * unless they sent apologies themselves (in the mode they answered, in person without an answer).
         */
        fun expected(
            replies: Map<UUID, RsvpRow>,
            alternates: Collection<UUID>,
        ): Map<UUID, PersonPresence> {
            val yes =
                replies
                    .filterValues { it.answer != RsvpAnswer.APOLOGIES }
                    .mapValues { PersonPresence(remote = it.value.answer == RsvpAnswer.REMOTE) }
            val standing =
                alternates
                    .filter { replies[it]?.answer != RsvpAnswer.APOLOGIES }
                    .associateWith { PersonPresence(remote = replies[it]?.answer == RsvpAnswer.REMOTE) }
            return standing + yes
        }

        /** Counting members in office ([rule]'s) who are among [recused]. */
        fun declared(
            rule: QuorumRule,
            roster: List<ForumSeat>,
            recused: Set<UUID>,
        ): Int = roster.count { it.role.countable && (rule.counts == QuorumCounts.ALL || it.voting) && it.userId in recused }
    }
}
