package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.forum.MinutesApproval
import com.memo.forum.OutcomeKind
import com.memo.forum.StandingKind
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.AgendaPublished
import com.memo.meeting.ItemLink
import com.memo.meeting.ItemLinks
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingRef
import com.memo.meeting.MeetingStatus
import com.memo.meeting.Meetings
import com.memo.notification.NotificationKind
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The minutes and the committee's next meeting (design E06 §1.6.3, ADR-0066), inside the caller's tenant transaction:
 *  - **linking:** when a meeting's agenda is published with a "Minutes of the previous meeting" item (PREVIOUS_MINUTES),
 *    the committee's earlier minutes that are circulated and not approved (AT_NEXT_MEETING), or circulated, with the chair
 *    or approved and not noted yet (BY_CIRCULATION), are linked to it; minutes circulated after that agenda is published
 *    are linked at once. AT_NEXT_MEETING minutes linked to a meeting held without approving them link to the next one;
 *  - **the item's links** ([ItemLinks]): "Minutes of BRC 3/2026" with their stage, for whoever sees the item in full;
 *  - **the approval at the meeting:** a decision APPROVED or APPROVED_WITH_CONDITIONS on that item names the AT_NEXT_MEETING
 *    minutes it approves (`approves_minutes_ids`) and moves them to the chair; DEFERRED leaves them circulated; a correction
 *    that no longer approves them sends minutes still waiting for the chair back to the draft.
 * Lock order: the minutes rows after the deciding meeting's rows, each in meeting id order.
 */
@Component
class PreviousMinutes(
    private val store: MinutesStore,
    private val queries: MinutesQueries,
    private val meetings: Meetings,
    private val items: MeetingItems,
    private val people: MinutesPeople,
    private val notifier: MinutesNotifier,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) : ItemLinks {
    override val standingKind = StandingKind.PREVIOUS_MINUTES

    override fun links(
        meetingId: UUID,
        itemId: UUID,
    ): List<ItemLink> =
        queries
            .linkedTo(meetingId)
            .filter { it.linkedItemId == itemId && it.state in SHOWN }
            .map { row ->
                val meeting = meetings.find(row.meetingId)
                ItemLink(
                    kind = LINK_KIND,
                    meetingId = row.meetingId,
                    label = "Minutes of ${meeting?.label ?: "the meeting of ${meeting?.date()}"}",
                    state = row.state.name,
                    at = row.approvedAt.takeIf { row.policy.approval == MinutesApproval.BY_CIRCULATION },
                    meetingLabel = meeting?.label,
                    meetingStartsAt = meeting?.startsAt,
                    meetingTimeZone = meeting?.timeZone?.id,
                )
            }

    /** A revision of [AgendaPublished]'s meeting was published: its "Minutes of the previous meeting" item takes the minutes waiting. */
    @EventListener
    fun onAgendaPublished(event: AgendaPublished) {
        val meeting = meetings.find(event.meetingId) ?: return
        val item = previousMinutesItem(meeting) ?: return
        linkable(meeting).forEach { link(it, meeting, item) }
    }

    /** [row] was just circulated (and is locked): linked to the committee's next meeting whose agenda has the item already. */
    fun linkOnCirculation(row: MinutesRow): MinutesRow {
        val held = meetings.find(row.meetingId) ?: return row
        val next =
            meetings
                .ofForum(row.forumId)
                .filter { it.id != held.id && !it.startsAt.isBefore(held.startsAt) && it.status in UPCOMING }
                .firstNotNullOfOrNull { m -> previousMinutesItem(m)?.let { m to it } }
        return next?.let { (meeting, item) -> link(row, meeting, item) } ?: row
    }

    /** The minutes [meeting]'s PREVIOUS_MINUTES item should carry: earlier minutes of the committee, by the rules above. */
    private fun linkable(meeting: MeetingRef): List<MinutesRow> =
        queries.ofForum(meeting.forumId, SHOWN).filter { row ->
            val held = meetings.find(row.meetingId)
            val earlier = held != null && held.id != meeting.id && !held.startsAt.isAfter(meeting.startsAt)
            val linkedElsewhere = row.linkedMeetingId?.takeIf { it != meeting.id }?.let(meetings::find)
            val free =
                when {
                    row.linkedMeetingId == null -> {
                        true
                    }

                    row.linkedMeetingId == meeting.id -> {
                        true
                    }

                    row.policy.approval == MinutesApproval.AT_NEXT_MEETING -> {
                        row.state == MinutesStage.CIRCULATED && linkedElsewhere?.status in PASSED
                    }

                    else -> {
                        false
                    }
                }
            val waiting = row.policy.approval == MinutesApproval.BY_CIRCULATION || row.state == MinutesStage.CIRCULATED
            earlier && free && waiting
        }

    /** Links [row] to [meeting]'s [item] (locked first); unchanged when it is linked there already. */
    private fun link(
        row: MinutesRow,
        meeting: MeetingRef,
        item: AgendaItemRef,
    ): MinutesRow {
        val locked = store.lock(row.meetingId)
        if (locked == null || (locked.linkedMeetingId == meeting.id && locked.linkedItemId == item.id)) return locked ?: row
        val after = locked.copy(linkedMeetingId = meeting.id, linkedItemId = item.id)
        check(store.update(locked, after)) { "the locked minutes moved on" }
        store.insertEvent(
            MinutesEventRow(ids.next(), row.meetingId, MinutesEventKind.LINKED_TO_MEETING, item.id, actorOrNull(), null, null, now()),
        )
        audit.record(
            "minutes.linked_to_meeting",
            MinutesDrafts.OBJECT_TYPE,
            row.meetingId.toString(),
            before = mapOf("linkedMeetingId" to locked.linkedMeetingId?.toString()),
            after = mapOf("linkedMeetingId" to meeting.id.toString(), "itemId" to item.id.toString()),
        )
        return checkNotNull(store.find(row.meetingId))
    }

    /**
     * The minutes a decision of [outcome] on [item] of [meetingId] approves: those linked to the item, circulated and
     * approved at the next meeting, when the item is a "Minutes of the previous meeting" one and the outcome approves.
     */
    fun approvedBy(
        meetingId: UUID,
        item: AgendaItemRef,
        outcome: OutcomeKind,
    ): List<UUID> {
        if (item.standingKind != StandingKind.PREVIOUS_MINUTES || outcome !in APPROVING) return emptyList()
        return queries
            .linkedTo(meetingId)
            .filter {
                it.linkedItemId == item.id && it.state == MinutesStage.CIRCULATED && it.policy.approval == MinutesApproval.AT_NEXT_MEETING
            }.map { it.meetingId }
            .sorted()
    }

    /**
     * After [recorded] was written: the minutes it approves go to the chair (each locked, in id order); on a correction, the
     * minutes it approved before and no longer does go back to the draft.
     */
    fun decided(recorded: CurrentDecision) {
        val approves = recorded.entry.approvesMinutesIds.toSet()
        approves.sorted().forEach { id ->
            val row = store.lock(id) ?: return@forEach
            if (row.state != MinutesStage.CIRCULATED || row.linkedMeetingId != recorded.decision.meetingId) return@forEach
            val next = MinutesStateMachine.next(row.state, MinutesTransition.APPROVED_AT_MEETING, row.policy.approval)
            val after =
                row.copy(
                    state = next,
                    approvingMeetingId = recorded.decision.meetingId,
                    approvingDecisionId = recorded.decision.id,
                    awaitingSince = now(),
                )
            check(store.update(row, after)) { "the locked minutes moved on" }
            store.insertEvent(
                MinutesEventRow(
                    ids.next(),
                    id,
                    MinutesEventKind.APPROVED_AT_MEETING,
                    recorded.decision.itemId,
                    actorOrNull(),
                    null,
                    null,
                    now(),
                ),
            )
            audit.record(
                "minutes.approved_at_meeting",
                MinutesDrafts.OBJECT_TYPE,
                id.toString(),
                before = mapOf("state" to row.state.name),
                after =
                    mapOf(
                        "state" to next.name,
                        "approvingMeetingId" to recorded.decision.meetingId.toString(),
                        "decisionId" to recorded.decision.id.toString(),
                        "outcome" to recorded.entry.outcomeKind.name,
                    ),
            )
            people.confirmer(after)?.let { notifier.notify(listOf(it.userId), NotificationKind.MINUTES_CONFIRMATION_REQUESTED, id) }
        }
        queries
            .approvedBy(
                recorded.decision.id,
            ).filter { it.meetingId !in approves }
            .sortedBy { it.meetingId }
            .forEach { undone(it, recorded) }
    }

    /** The approving decision was corrected: the minutes, still with the chair, go back to the draft. */
    private fun undone(
        found: MinutesRow,
        recorded: CurrentDecision,
    ) {
        val row = store.lock(found.meetingId)?.takeIf { it.state == MinutesStage.AWAITING_CHAIR } ?: return
        val next = MinutesStateMachine.next(row.state, MinutesTransition.APPROVAL_UNDONE, row.policy.approval)
        val after = row.copy(state = next, approvingMeetingId = null, approvingDecisionId = null, awaitingSince = null)
        check(store.update(row, after)) { "the locked minutes moved on" }
        store.insertEvent(
            MinutesEventRow(
                ids.next(),
                row.meetingId,
                MinutesEventKind.RETURNED_TO_DRAFT,
                recorded.decision.itemId,
                actorOrNull(),
                null,
                null,
                now(),
            ),
        )
        audit.record(
            "minutes.returned_to_draft",
            MinutesDrafts.OBJECT_TYPE,
            row.meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after = mapOf("state" to next.name, "itemId" to recorded.decision.itemId.toString(), "approvalUndone" to true),
        )
    }

    /** [meeting]'s "Minutes of the previous meeting" item in its latest published revision; null without one. */
    private fun previousMinutesItem(meeting: MeetingRef): AgendaItemRef? =
        items.agenda(meeting.id).items.firstOrNull { it.standingKind == StandingKind.PREVIOUS_MINUTES && it.published }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val LINK_KIND = "MINUTES"
        private val SHOWN = setOf(MinutesStage.CIRCULATED, MinutesStage.AWAITING_CHAIR, MinutesStage.APPROVED)
        private val APPROVING = setOf(OutcomeKind.APPROVED, OutcomeKind.APPROVED_WITH_CONDITIONS)
        private val UPCOMING = setOf(MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS)
        private val PASSED = setOf(MeetingStatus.HELD, MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED, MeetingStatus.CANCELLED)
    }
}
