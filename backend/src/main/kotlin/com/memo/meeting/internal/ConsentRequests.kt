package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.id.IdGenerator
import com.memo.common.web.refuseIf
import com.memo.forum.AgendaSectionKind
import com.memo.forum.ForumDirectory
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingStatus
import com.memo.meeting.PaperAccess
import com.memo.meeting.PinnedPaper
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * "Ask to discuss" (design E03 §1.5): a member or guest who sees a consent item of the latest revision asks for it to be
 * lifted for discussion, or withdraws the request, while the meeting is scheduled. No reason and no approval: the item
 * shows "Lifted for discussion at {names}'s request" to everyone who sees it, and the secretaries and the chair are told.
 * The meeting's row is locked first (design E03 §1.8), so a request never lands after the meeting has started.
 */
@Service
class ConsentRequests(
    private val guard: MeetingGuard,
    private val meetings: MeetingStore,
    private val viewers: PaperViewers,
    private val papers: PaperAccess,
    private val revisions: RevisionStore,
    private val requests: ConsentRequestStore,
    private val agendas: MemberAgendas,
    private val notifier: MeetingNotifier,
    private val directory: ForumDirectory,
    private val audit: AuditLog,
    private val ids: IdGenerator,
) {
    /** The actor asks to discuss consent item [itemId] of [meetingId]. */
    fun ask(
        meetingId: UUID,
        itemId: UUID,
    ) {
        val (meeting, revision, scope) = open(meetingId, itemId, asking = true)
        val user = checkNotNull(actorOrNull())
        val id = ids.next()
        val asked = requests.insert(ConsentRequestRow(id, meeting.id, itemId, user, revision, requests.now()))
        refuseIf(!asked, "ALREADY_ASKED") { "You have already asked to discuss this item." }
        audit.record(
            "meeting.discussion.requested",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after = mapOf("requestId" to id.toString(), "itemId" to itemId.toString(), "revision" to revision),
        )
        directory.forum(meeting.forumId)?.let { notifier.discussionRequested(meeting, it, scope) }
    }

    /** The actor withdraws their request to discuss [itemId] of [meetingId]. */
    fun withdraw(
        meetingId: UUID,
        itemId: UUID,
    ) {
        val (meeting, revision) = open(meetingId, itemId, asking = false)
        val withdrawn = requests.withdraw(itemId, checkNotNull(actorOrNull()))
        refuseIf(withdrawn == null, "NO_REQUEST") { "You have not asked to discuss this item." }
        audit.record(
            "meeting.discussion.withdrawn",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            before = mapOf("requestId" to withdrawn.toString(), "itemId" to itemId.toString(), "revision" to revision),
        )
    }

    /**
     * The meeting, locked, its latest revision's number and the item with its parent, if the actor may ask about [itemId]
     * now: 404 when they do not see the meeting or the item in full (a recused person does not); 403 when they see it but
     * are not a member or guest (a secretary moves the item themselves); 409 `WRONG_STATE` unless the meeting is scheduled
     * and, [asking], the item sits in a consent section.
     */
    private fun open(
        meetingId: UUID,
        itemId: UUID,
        asking: Boolean,
    ): Triple<MeetingRow, Int, List<UUID>> {
        guard.requireVisible(meetingId)
        val meeting = checkNotNull(meetings.lock(meetingId))
        val head = revisions.heads(meeting.id).lastOrNull()
        val viewer = checkNotNull(viewers.actor(meeting))
        val item =
            head
                ?.takeIf { papers.revision(viewer, it.id).lines[itemId] == LineAccess.FULL }
                ?.let { revisions.snapshot(it.id).body() }
                ?.let { body -> body.items.firstOrNull { it.itemId == itemId }?.let { it to body } }
        if (head == null || item == null) throw MeetingNotFoundException("item")
        if (!agendas.asks(meeting, viewer)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        val (found, body) = item
        val consent = body.sections.any { it.sectionId == found.sectionId && it.kind == AgendaSectionKind.CONSENT }
        refuseIf(meeting.status != MeetingStatus.SCHEDULED, "WRONG_STATE") { "Requests close when the meeting starts." }
        refuseIf(asking && !consent, "WRONG_STATE") { "Only consent items are lifted for discussion." }
        return Triple(meeting, head.number, listOfNotNull(found.itemId, found.parentId))
    }
}

/**
 * Opening a paper (ADR-0061, design E03 §3): the paper rule on the revision asked for (the latest by default), originals
 * for the forum's secretaries and `forums.manage` only, and the person's first open recorded for the "Opened" tick. The
 * content is then streamed by the files module's `FileServing`, which audits the open (`meeting.paper.opened`).
 */
@Service
class PaperOpens(
    private val guard: MeetingGuard,
    private val access: MeetingAccess,
    private val viewers: PaperViewers,
    private val papers: PaperAccess,
    private val states: ReaderStateStore,
) {
    /** The pinned paper the actor may open; 404 when they may not read it, 403 for an original they may not download. */
    fun open(
        meetingId: UUID,
        paperId: UUID,
        revision: Int?,
        original: Boolean,
    ): PinnedPaper {
        val (meeting, _) = guard.requireVisible(meetingId)
        val viewer = checkNotNull(viewers.actor(meeting))
        val paper = papers.paper(viewer, meeting.id, paperId, revision) ?: throw MeetingNotFoundException("paper")
        if (original && !access.manages(meeting.forumId)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        return paper
    }

    /** Records the actor's first open of [paperId]. */
    fun opened(paperId: UUID) {
        actorOrNull()?.let { states.opened(paperId, it) }
    }
}
