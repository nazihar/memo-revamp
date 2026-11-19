package com.memo.meeting.internal

import com.memo.common.web.FieldProblem
import com.memo.files.FileRenditions
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.forum.ForumRole
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingStatus
import com.memo.meeting.PaperAccess
import com.memo.meeting.PaperViewer
import com.memo.meeting.Visibility
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The agenda as its audience reads it (ADR-0061, design E03 §1.9, §3, flow P): a published revision (the latest, or an
 * earlier one by number) through the paper rule, "What changed since you last looked", the revision list, the last
 * revision seen, and the officers' preview of the draft as one person of the audience would see it.
 */
@Service
class MemberAgendas(
    private val guard: MeetingGuard,
    private val agendaGuard: AgendaGuard,
    private val access: MeetingAccess,
    private val viewers: PaperViewers,
    private val papers: PaperAccess,
    private val revisions: RevisionStore,
    private val states: ReaderStateStore,
    private val requests: ConsentRequestStore,
    private val publisher: AgendaPublisher,
    private val renditions: FileRenditions,
    private val users: UserSummaries,
) {
    /** Revision [number] of [meetingId]'s agenda (the latest when null) as the actor sees it; 404 when invisible. */
    fun agenda(
        meetingId: UUID,
        number: Int?,
    ): MemberAgendaResponse {
        val (meeting, _) = guard.requireVisible(meetingId)
        val viewer = checkNotNull(viewers.actor(meeting))
        val heads = revisions.heads(meeting.id)
        val asked = number ?: heads.lastOrNull()?.number ?: 0
        val head = heads.firstOrNull { it.number == asked }
        if (number != null && head == null) throw MeetingNotFoundException("revision")
        val lastSeen = states.states(viewer.userId, listOf(meeting.id))[meeting.id]?.lastSeen
        val latest = heads.lastOrNull()?.number ?: 0
        if (head == null) return empty(meeting, lastSeen)
        val snapshot = revisions.snapshot(head.id)
        val visibility = papers.revision(viewer, head.id)
        val mayAsk = head.number == latest && meeting.status == MeetingStatus.SCHEDULED && asks(meeting, viewer)
        val whatChanged =
            if (head.number == latest && lastSeen != null && lastSeen < latest) {
                changes(viewer, heads.filter { it.number > lastSeen }, lastSeen)
            } else {
                null
            }
        return MemberAgendaResponse(
            meetingId = meeting.id,
            status = meeting.status,
            startsAt = meeting.startsAt,
            endsAt = meeting.endsAt,
            timeZone = meeting.zone.id,
            revision = head.number,
            latestRevision = latest,
            publishedAt = head.publishedAt,
            note = head.note,
            preview = false,
            lastSeenRevision = lastSeen,
            sections = MemberAgendaRender.sections(input(meeting, snapshot, visibility, viewer, mayAsk)) { reads(viewer, it) },
            whatChanged = whatChanged,
        )
    }

    /** The draft of [meetingId] as [userId] (in its audience) would see it if published now: for its officers. */
    fun preview(
        meetingId: UUID,
        userId: UUID,
    ): MemberAgendaResponse {
        val meeting = agendaGuard.requireReader(meetingId)
        val inAudience = viewers.of(meeting, userId)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("as", "NOT_IN_AUDIENCE", "Choose someone the meeting is for.")
                    .takeIf { !inAudience.inAudience && !inAudience.officer },
            ),
        )
        // As if published now: a draft meeting is read as a scheduled one.
        val draft = meeting.status == MeetingStatus.DRAFT
        val viewer = inAudience.copy(open = inAudience.open || draft, guestsRead = inAudience.guestsRead || draft)
        val publication = publisher.preview(meeting.id).second
        val snapshot = publication?.snapshot ?: Snapshot(emptyList(), emptyList(), emptyList())
        val visibility = papers.draft(viewer, meeting.id)
        return MemberAgendaResponse(
            meetingId = meeting.id,
            status = meeting.status,
            startsAt = meeting.startsAt,
            endsAt = meeting.endsAt,
            timeZone = meeting.zone.id,
            revision = publication?.revision ?: 1,
            latestRevision = publication?.previous?.number ?: 0,
            publishedAt = null,
            note = null,
            preview = true,
            lastSeenRevision = null,
            sections = MemberAgendaRender.sections(input(meeting, snapshot, visibility, viewer, false)) { reads(viewer, it) },
            whatChanged = null,
        )
    }

    /** The published revisions of [meetingId], the first first: officers also see who published and whether people were told. */
    fun revisions(meetingId: UUID): List<PublishedRevisionResponse> {
        val (meeting, relation) = guard.requireVisible(meetingId)
        val heads = revisions.heads(meeting.id)
        val officer = relation == MeetingRelation.OFFICER
        val names = if (officer) users.names(heads.mapNotNull { it.publishedBy }) else emptyMap()
        return heads.map { h ->
            PublishedRevisionResponse(
                number = h.number,
                publishedAt = h.publishedAt,
                note = h.note.takeIf { relation != MeetingRelation.READER },
                publishedBy = h.publishedBy?.takeIf { officer }?.let { PersonResponse(it, names[it].orEmpty()) },
                notified = h.notified.takeIf { officer },
            )
        }
    }

    /** Records that the actor has seen revision [number] of [meetingId] ("What changed" starts after it). */
    fun seen(
        meetingId: UUID,
        number: Int,
    ) {
        val (meeting, _) = guard.requireVisible(meetingId)
        val latest = revisions.heads(meeting.id).lastOrNull()?.number ?: 0
        requireNoProblems(
            listOfNotNull(FieldProblem("revision", "unknown", "No such published revision.").takeIf { number < 1 || number > latest }),
        )
        states.seen(meeting.id, checkNotNull(actorOrNull()), number)
    }

    /**
     * For "My meetings": the actor's latest revision, last revision seen and how many lines changed since then that they
     * see, per meeting of [meetings] with a published agenda.
     */
    fun counts(meetings: List<MeetingRow>): Map<UUID, Triple<Int, Int?, Int?>> {
        val user = actorOrNull() ?: return emptyMap()
        val read = states.states(user, meetings.map { it.id })
        // The meetings with a revision newer than the one last seen: counted in a few queries for them all (design E03 §8).
        val behind = meetings.filter { m -> read[m.id]?.let { s -> s.lastSeen?.let { seen -> seen < s.currentRevision } } == true }
        val people = viewers.actors(behind)
        val heads = revisions.heads(behind.map { it.id })
        val newer = behind.associate { m -> m.id to heads[m.id].orEmpty().filter { it.number > (read.getValue(m.id).lastSeen ?: 0) } }
        // The rule takes the viewer as constants: one pair of queries per kind of viewer, not per meeting.
        val visible =
            behind
                .groupBy { people.getValue(it.id) }
                .flatMap { (viewer, group) ->
                    papers.revisions(viewer, group.flatMap { newer.getValue(it.id) }.map { it.id }, changedOnly = true).toList()
                }.toMap()
        val snapshots = revisions.snapshots(visible.keys, changedOnly = true)
        val counted =
            newer.mapValues { (_, revised) ->
                revised.sumOf { MemberAgendaRender.changes(snapshots.getValue(it.id), visible.getValue(it.id)).size }
            }
        return meetings
            .mapNotNull { m -> read[m.id]?.let { s -> m.id to Triple(s.currentRevision, s.lastSeen, counted[m.id]) } }
            .toMap()
    }

    /** "What changed" across [heads] (the revisions after [since]), limited to what [viewer] sees in each. */
    private fun changes(
        viewer: PaperViewer,
        heads: List<RevisionHead>,
        since: Int,
    ): WhatChangedResponse {
        // Only the flagged rows, what the list shows, for every revision at once (design E03 §8).
        val ids = heads.map { it.id }
        val snapshots = revisions.snapshots(ids, changedOnly = true)
        val visible = papers.revisions(viewer, ids, changedOnly = true)
        val revisions =
            heads.map { head ->
                RevisionChangesResponse(
                    number = head.number,
                    publishedAt = head.publishedAt,
                    note = head.note,
                    items = MemberAgendaRender.changes(snapshots.getValue(head.id), visible.getValue(head.id)),
                )
            }
        return WhatChangedResponse(since, revisions, revisions.sumOf { it.items.size })
    }

    /** Whether [viewer] may ask to discuss consent items: a member or guest of the meeting, not one of its secretaries. */
    fun asks(
        meeting: MeetingRow,
        viewer: PaperViewer,
    ): Boolean = (viewer.member || viewer.guest) && access.role(meeting.forumId) != ForumRole.SECRETARY

    private fun input(
        meeting: MeetingRow,
        snapshot: Snapshot,
        visibility: Visibility,
        viewer: PaperViewer,
        mayAsk: Boolean,
    ): MemberAgendaInput {
        val shown = snapshot.body().papers.filter { it.paperId in visibility.papers }
        val pdf = renditions.states(shown.map { it.fileVersionId })
        val open = requests.open(meeting.id).groupBy { it.itemId }
        val people =
            snapshot.items.flatMap { item -> item.presenters.mapNotNull { it.userId } } + open.values.flatten().map { it.userId }
        return MemberAgendaInput(
            meeting = meeting,
            snapshot = snapshot,
            visibility = visibility,
            names = users.names(people.toSet()),
            ready =
                shown
                    .filter { it.pages != null || pdf[it.fileVersionId]?.get(RenditionKind.PDF) == RenditionState.DONE }
                    .map { it.paperId }
                    .toSet(),
            opened = states.openedOf(viewer.userId, shown.map { it.paperId }),
            requests = open,
            viewerId = viewer.userId,
            mayAsk = mayAsk,
            inCamera = viewer.officer || (viewer.member && viewer.membersOnly),
        )
    }

    /** Whether [viewer] sees the heading of a section with no line they see: not a members-only one for guests. */
    private fun reads(
        viewer: PaperViewer,
        membersOnly: Boolean,
    ): Boolean = viewer.officer || (viewer.member && viewer.membersOnly) || (!membersOnly && viewer.inAudience)

    private fun empty(
        meeting: MeetingRow,
        lastSeen: Int?,
    ) = MemberAgendaResponse(
        meetingId = meeting.id,
        status = meeting.status,
        startsAt = meeting.startsAt,
        endsAt = meeting.endsAt,
        timeZone = meeting.zone.id,
        revision = null,
        latestRevision = 0,
        publishedAt = null,
        note = null,
        preview = false,
        lastSeenRevision = lastSeen,
        sections = emptyList(),
        whatChanged = null,
    )
}
