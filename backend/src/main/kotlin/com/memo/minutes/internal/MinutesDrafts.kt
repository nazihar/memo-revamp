package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.web.refuseIf
import com.memo.docedit.DocumentEditing
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.MinutesPolicy
import com.memo.identity.UserSummaries
import com.memo.meeting.AgendaItemKind
import com.memo.meeting.MeetingAttendance
import com.memo.meeting.MeetingChange
import com.memo.meeting.MeetingChanged
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingRef
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.meeting.QuorumSnapshots
import com.memo.meeting.Recusals
import com.memo.minutes.MinutesItemActions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A meeting's minutes and the requests to draft them (ADR-0066, design E06 §1.5, §1.6.1), inside a tenant transaction.
 *  - When a meeting is marked as held, its minutes are created in DRAFT, in the ending transaction (after the meeting's
 *    row), with the committee type's minutes policy pinned on the meeting; a meeting held before T5 gets them at its
 *    first request.
 *  - A request (the whole draft, or one item filled again or restored) is the committee's secretaries' and `forums.manage`
 *    holders' who are recused from no minuted item (403, 403 `RECUSED`), on a held meeting (409 `WRONG_STATE`, `FROZEN`)
 *    whose minutes are a draft; one request at a time (409 `GENERATING`), never while someone has the draft open in the
 *    editor (409 `BEING_EDITED`: their save would be lost), and a whole draft over an existing one only with
 *    `replaceEdits` (409 `DRAFT_EXISTS`). The `minutes-draft` job does the work.
 */
@Service
class MinutesDrafts(
    private val viewers: MeetingViewers,
    private val meetings: Meetings,
    private val items: MeetingItems,
    private val recusals: Recusals,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val store: MinutesStore,
    private val editing: DocumentEditing,
    private val jobs: MinutesJobs,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** [meeting]'s minutes, created in DRAFT when it is held and has none; null for a meeting not held. */
    fun ensure(meeting: MeetingRef): MinutesRow? =
        store.find(meeting.id) ?: unsaved(meeting)?.let { row ->
            if (store.insert(row)) created(meeting, row.policy)
            checkNotNull(store.find(meeting.id))
        }

    /** The minutes [meeting] would get now (the pinned policy, in DRAFT), not stored; null for a meeting not held. */
    fun unsaved(meeting: MeetingRef): MinutesRow? {
        val forum = directory.forum(meeting.forumId)?.takeIf { meeting.status == MeetingStatus.HELD } ?: return null
        val policy = policies.version(forum.typeId, meeting.typeVersion)?.minutes ?: MinutesPolicy()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return MinutesRow(
            meetingId = meeting.id,
            forumId = meeting.forumId,
            policy = policy,
            state = MinutesStage.DRAFT,
            fileId = null,
            workSeq = 0,
            workKind = null,
            workItemId = null,
            workState = null,
            workError = null,
            workRequestedBy = null,
            workRequestedAt = null,
            checkedVersionId = null,
            structureOk = null,
            structureProblems = emptyList(),
            trackedChanges = 0,
            comments = 0,
            checkedAt = null,
            heldAt = now,
            createdAt = now,
            version = 0,
        )
    }

    private fun created(
        meeting: MeetingRef,
        policy: MinutesPolicy,
    ) {
        audit.record(
            "minutes.created",
            OBJECT_TYPE,
            meeting.id.toString(),
            after =
                mapOf(
                    "forumId" to meeting.forumId.toString(),
                    "typeVersion" to meeting.typeVersion,
                    "approval" to policy.approval.name,
                    "memoResumes" to policy.memoResumes.name,
                    "templateFileId" to policy.templateFileId?.toString(),
                ),
        )
    }

    /** The meeting is marked as held: its minutes are created, in the ending transaction. */
    @EventListener
    fun onMeetingChanged(event: MeetingChanged) {
        if (event.change != MeetingChange.ENDED) return
        meetings.find(event.meetingId)?.let(::ensure)
    }

    /**
     * Asks for the whole draft ([itemId] null; over an existing draft only with [replaceEdits]) or for [itemId]'s block
     * filled again (restored when it is missing). Answers the minutes with the request PENDING.
     */
    fun request(
        meetingId: UUID,
        itemId: UUID?,
        replaceEdits: Boolean,
    ): MinutesRow {
        val view = keeper(meetingId)
        // The meeting's row before the minutes' (design E06 §1.4's lock order).
        val meeting = meetings.locked(meetingId) ?: throw MinutesNotFoundException("meeting")
        refuseIf(meeting.status == MeetingStatus.MINUTES_APPROVED || meeting.status == MeetingStatus.CLOSED, "FROZEN") {
            "The minutes have been approved."
        }
        refuseIf(meeting.status != MeetingStatus.HELD, "WRONG_STATE") { "The minutes are drafted once the meeting is marked as held." }
        ensure(meeting)
        val row = checkNotNull(store.lock(meetingId))
        refuseIf(row.state != MinutesStage.DRAFT, "WRONG_STATE") { "The minutes are out of the draft." }
        refuseIf(row.workState == WorkState.PENDING, "GENERATING") { "The draft is being made: wait for it to finish." }
        if (itemId == null) {
            refuseIf(row.fileId != null && !replaceEdits, "DRAFT_EXISTS") {
                "Making the whole draft again replaces every edit: confirm it to go on."
            }
        } else {
            if (MinutesTemplateData.minuted(view.agenda.items).none { it.id == itemId }) throw MinutesNotFoundException("item")
            refuseIf(row.fileId == null, "NO_DRAFT") { "Make the draft first." }
        }
        refuseIf(row.fileId != null && editing.beingEdited(checkNotNull(row.fileId)), "BEING_EDITED") {
            "Someone has the minutes open in the editor: their changes would be lost. Ask them to close it first."
        }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val after =
            row.copy(
                workSeq = row.workSeq + 1,
                workKind = if (itemId == null) DraftWork.ALL else DraftWork.ITEM,
                workItemId = itemId,
                workState = WorkState.PENDING,
                workError = null,
                workRequestedBy = view.userId,
                workRequestedAt = now,
            )
        check(store.update(row, after)) { "the locked minutes moved on" }
        audit.record(
            "minutes.draft.requested",
            OBJECT_TYPE,
            meetingId.toString(),
            after =
                mapOf(
                    "seq" to after.workSeq,
                    "kind" to after.workKind?.name,
                    "itemId" to itemId?.toString(),
                    "replaceEdits" to replaceEdits,
                ),
        )
        jobs.draft(meetingId, after.workSeq)
        return checkNotNull(store.find(meetingId))
    }

    /**
     * The actor drafting [meetingId]'s minutes: 404 for a meeting they do not see, 403 for anyone but a secretary or
     * `forums.manage`, 403 `RECUSED` for one recused from any minuted item (before the meeting's state is looked at, so
     * the leak suite's recusal columns hold on a scheduled meeting).
     */
    fun keeper(meetingId: UUID): MeetingView {
        val view = viewers.of(meetingId)
        if (!view.manages) throw ForbiddenException(null)
        if (recusedFromAny(meetingId, view.userId)) {
            throw ForbiddenException(
                null,
                "RECUSED",
                "You declared an interest in an item of this meeting: another secretary drafts its minutes.",
            )
        }
        return view
    }

    /** Whether [userId] is recused from any item of [meetingId] (the whole minutes rule, ADR-0066). */
    fun recusedFromAny(
        meetingId: UUID,
        userId: UUID,
    ): Boolean = recusals.ofMeeting(meetingId).values.any { userId in it }

    companion object {
        const val OBJECT_TYPE = "minutes"
    }
}

/**
 * What a draft is filled from (design E06 §1.6.1), read in one tenant transaction: the meeting, its minuted items and
 * their details, attendance, declarations, decisions with their numbers and snapshots, notes, the actions (E07's port,
 * none without it) and every name. No access decision is made here: the draft job reads as the system for its requester.
 */
@Component
class MinutesDraftSource(
    private val meetings: Meetings,
    private val items: MeetingItems,
    private val attendance: MeetingAttendance,
    private val recusals: Recusals,
    private val snapshots: QuorumSnapshots,
    private val directory: ForumDirectory,
    private val reads: DecisionReads,
    private val resolutions: ResolutionStore,
    private val notes: NoteStore,
    private val users: UserSummaries,
    private val actions: ObjectProvider<MinutesItemActions>,
    private val store: MinutesStore,
) {
    fun read(meetingId: UUID): DraftSource? {
        val meeting = meetings.find(meetingId)
        val row = store.find(meetingId)
        val forum = meeting?.let { directory.forum(it.forumId) }
        if (meeting == null || row == null || forum == null) return null
        val agenda = items.agenda(meetingId)
        val register = attendance.of(meetingId)
        val roles = directory.membersOn(meeting.forumId, meeting.date()).associate { it.userId to it.role }
        val declarations = recusals.declarationsFor(meetingId)
        val decisions = reads.currentOf(meetingId).associateBy { it.decision.itemId }
        val numbers = resolutions.inForce(decisions.values.map { it.decision.id }).mapValues { it.value.number }
        val taken = snapshots.ofMeeting(meetingId).associateBy { it.id }
        val ratified = ratified(decisions.values.mapNotNull { it.entry.ratifiesDecisionId })
        val lines = actions.getIfAvailable { MinutesItemActions.NONE }.of(meetingId)
        val details = items.details(agenda)
        val named =
            buildSet {
                register?.people?.forEach {
                    add(it.userId)
                    it.forUserId?.let(::add)
                }
                addAll(declarations.map { it.userId })
                decisions.values.forEach { d ->
                    addAll(d.entry.dissent.map { it.userId })
                    addAll(d.entry.abstentions)
                    addAll(d.entry.tookNoPart)
                }
                details.values.forEach { it.presenters.mapNotNull { p -> p.userId }.forEach(::add) }
            }
        return DraftSource(
            meeting = meeting,
            committee = forum.name,
            heldAt = row.heldAt,
            items = agenda.items,
            details = details,
            attendance = register,
            roles = roles,
            declarations = declarations,
            decisions = decisions,
            resolutions = numbers,
            snapshots = taken,
            ratified = ratified,
            notes = notes.latestOf(meetingId).mapValues { it.value.text },
            actions = lines,
            names = users.names(named),
        )
    }

    /** "item 4 of BRC 2/2026" for each of [decisionIds]. */
    private fun ratified(decisionIds: List<UUID>): Map<UUID, String> =
        decisionIds
            .distinct()
            .mapNotNull { id ->
                val decision = reads.currentById(id) ?: return@mapNotNull null
                val meeting = meetings.find(decision.decision.meetingId) ?: return@mapNotNull null
                val number = items.agenda(meeting.id).item(decision.decision.itemId)?.number
                id to listOfNotNull(number?.let { "item $it" } ?: "an item", meeting.label?.let { "of $it" }).joinToString(" ")
            }.toMap()

    /** The submission items of [meetingId]'s agenda that have no decision (design E06 §1.1: the minutes cannot leave the draft). */
    fun undecided(meetingId: UUID): List<MinutedItem> {
        val decided = reads.currentOf(meetingId).map { it.decision.itemId }.toSet()
        return MinutesTemplateData
            .minuted(items.agenda(meetingId).items)
            .filter { it.kind == AgendaItemKind.SUBMISSION && it.id !in decided }
            .map { MinutedItem(it.id, checkNotNull(it.number)) }
    }
}
