package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.common.web.refuseIf
import com.memo.files.Files
import com.memo.forum.MinutesApproval
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.notification.NotificationKind
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/**
 * The comments on circulated minutes (ADR-0066, design E06 §1.6.3), inside a tenant transaction: people asked for comments
 * on named items, a reader's "No comments" or suggestion, the secretary's answer, and who reads which comments. Free text
 * is never audited, only its SHA-256. Under BY_CIRCULATION the last required reader's answer sends the minutes to the chair.
 */
@Service
class MinutesComments(
    private val steps: MinutesSteps,
    private val review: MinutesReview,
    private val store: MinutesStore,
    private val comments: MinutesCommentStore,
    private val people: MinutesPeople,
    private val viewers: MeetingViewers,
    private val copies: MinutesCopies,
    private val reading: MinutesReading,
    private val notifier: MinutesNotifier,
    private val ids: IdGenerator,
    private val audit: AuditLog,
) {
    /**
     * Asks [userIds] (1 to 20 people of the tenant) for comments on [itemIds] (minuted items) while circulated: their copy
     * shows those items as a member would see them, every other item hidden. 422 for an unknown item, or a person who could
     * not read any of the items (not cleared for them, or recused).
     */
    fun askForComments(
        meetingId: UUID,
        userIds: List<UUID>,
        itemIds: List<UUID>,
    ): MinutesRow {
        val view = steps.manager(meetingId)
        val row = store.lock(meetingId) ?: throw MinutesNotFoundException("minutes")
        MinutesStateMachine.next(row.state, MinutesTransition.COMMENT, row.policy.approval)
        val minuted = MinutesTemplateData.minuted(view.agenda.items).map { it.id }.toSet()
        val asked = userIds.distinct()
        val items = itemIds.distinct()
        val unreadable =
            asked.filter { user ->
                val seen = viewers.ofUser(meetingId, user, asMember = true)
                seen == null || items.none { seen.seesItem(it) }
            }
        requireNoProblems(
            listOfNotNull(
                FieldProblem(
                    "userIds",
                    "RANGE",
                    "Ask 1 to ${MinutesSteps.MAX_ASKED} people at a time.",
                ).takeIf { asked.isEmpty() || asked.size > MinutesSteps.MAX_ASKED },
                FieldProblem("itemIds", "RANGE", "Name at least one item.").takeIf { items.isEmpty() },
                FieldProblem("itemIds", "unknown", "Name items of these minutes.").takeIf { items.any { it !in minuted } },
                FieldProblem("userIds", "NOT_CLEARED", "These people could not read the items named.").takeIf { unreadable.isNotEmpty() },
            ),
        )
        val now = steps.now()
        asked.forEach { comments.insertRequest(CommentRequestRow(ids.next(), meetingId, it, items, view.userId, now)) }
        steps.event(row, MinutesEventKind.COMMENTS_REQUESTED)
        audit.record(
            "minutes.comments_requested",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            after = mapOf("userIds" to asked.map { it.toString() }, "itemIds" to items.map { it.toString() }),
        )
        val saved = checkNotNull(store.find(meetingId))
        copies.request(saved)
        notifier.notify(asked, NotificationKind.MINUTES_COMMENTS_REQUESTED, meetingId, until = row.commentsUntil)
        return saved
    }

    /**
     * A reader's comment while circulated: "No comments" once (409 `ALREADY_ANSWERED`), or a suggestion of 1 to 2,000
     * characters on an item they see in full or on the minutes in general. Told to the secretaries and the chair (not
     * those recused from the item). BY_CIRCULATION: the last required reader's answer sends the minutes to the chair.
     */
    fun comment(
        meetingId: UUID,
        kind: CommentKind,
        itemId: UUID?,
        text: String?,
    ): CommentRow {
        val view = reading.actorView(meetingId)
        val row = store.lock(meetingId)
        val readers = row?.let(people::readers).orEmpty()
        val reader = readers[view.userId]
        if (row == null || reader == null) throw MinutesNotFoundException("minutes")
        if (view.manages && reader == ReaderKind.OFFICER && people.secretaries(row.forumId).contains(view.userId)) {
            throw ForbiddenException(null, "SECRETARY", "Secretaries answer comments; they do not comment.")
        }
        MinutesStateMachine.next(row.state, MinutesTransition.COMMENT, row.policy.approval)
        val clean = text?.trim()?.ifEmpty { null }
        val set = people.setOf(row, view.userId, reader)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("text", "TEXT_REQUIRED", "Write your suggestion.").takeIf { kind == CommentKind.SUGGESTION && clean == null },
                FieldProblem("text", "LENGTH", "At most ${MinutesSteps.MAX_COMMENT} characters.").takeIf {
                    (clean?.length ?: 0) >
                        MinutesSteps.MAX_COMMENT
                },
                FieldProblem("itemId", "unknown", "Choose an item you can read, or the minutes in general.")
                    .takeIf { itemId != null && (kind != CommentKind.SUGGESTION || !readsInFull(view, set, itemId)) },
            ),
        )
        val comment =
            CommentRow(
                id = ids.next(),
                meetingId = meetingId,
                userId = view.userId,
                itemId = itemId.takeIf { kind == CommentKind.SUGGESTION },
                kind = kind,
                text = clean.takeIf { kind == CommentKind.SUGGESTION },
                at = steps.now(),
            )
        refuseIf(!comments.insert(comment), "ALREADY_ANSWERED") { "You have already told the secretary you have no comments." }
        audit.record(
            "minutes.comment.added",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            after =
                mapOf(
                    "commentId" to comment.id.toString(),
                    "kind" to kind.name,
                    "itemId" to comment.itemId?.toString(),
                    "textSha256" to comment.text?.let(MinutesSteps::sha256),
                ),
        )
        val recusedFromItem = itemId?.let { recusedFrom(meetingId, it) }.orEmpty()
        val told = people.secretaries(row.forumId) + listOfNotNull(people.reviewer(row)?.userId) - recusedFromItem
        notifier.notify(told, NotificationKind.MINUTES_COMMENT_ADDED, meetingId, details = mapOf("kind" to kind.name))
        if (row.policy.approval == MinutesApproval.BY_CIRCULATION && allAnswered(row, readers)) {
            review.toChair(row, MinutesReview.CloseTrigger.ALL_ANSWERED)
        }
        return comment
    }

    /** A secretary's answer to a suggestion, once (409 `ALREADY_ANSWERED`), while circulated or with the chair. */
    fun respond(
        meetingId: UUID,
        commentId: UUID,
        response: CommentResponse,
        note: String?,
    ): CommentRow {
        val view = steps.manager(meetingId)
        val row = store.lock(meetingId) ?: throw MinutesNotFoundException("minutes")
        val comment = comments.find(commentId)?.takeIf { it.meetingId == meetingId && it.kind == CommentKind.SUGGESTION }
        if (comment == null || (comment.itemId != null && view.userId in recusedFrom(meetingId, comment.itemId))) {
            throw MinutesNotFoundException("comment")
        }
        MinutesStateMachine.next(row.state, MinutesTransition.RESPOND, row.policy.approval)
        val clean = note?.trim()?.ifEmpty { null }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("note", "LENGTH", "At most ${MinutesSteps.MAX_NOTE} characters.").takeIf {
                    (clean?.length ?: 0) >
                        MinutesSteps.MAX_NOTE
                },
            ),
        )
        refuseIf(!comments.respond(commentId, response, clean, view.userId, steps.now()), "ALREADY_ANSWERED") {
            "This suggestion has an answer already."
        }
        audit.record(
            "minutes.comment.responded",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            after =
                mapOf(
                    "commentId" to commentId.toString(),
                    "response" to response.name,
                    "noteSha256" to clean?.let(MinutesSteps::sha256),
                ),
        )
        return checkNotNull(comments.find(commentId))
    }

    /** The comments the actor reads: the officers all (but on items they are recused from); anyone else their own. */
    fun commentsFor(meetingId: UUID): Pair<List<CommentRow>, Boolean> {
        val view = reading.actorView(meetingId)
        val row = store.find(meetingId) ?: throw MinutesNotFoundException("minutes")
        val officer = view.manages || view.userId == people.reviewer(row)?.userId || view.userId == people.confirmer(row)?.userId
        if (!officer && people.readers(row)[view.userId] == null) throw MinutesNotFoundException("minutes")
        val all = if (officer) comments.of(meetingId) else comments.of(meetingId, view.userId)
        val recused = recusedItems(meetingId, view.userId)
        return all.filter { it.userId == view.userId || it.itemId == null || it.itemId !in recused } to officer
    }

    /** Whether every reader who must answer (members, alternates, the chair and vice-chairs; not secretaries or people asked) has. */
    fun allAnswered(
        row: MinutesRow,
        readers: Map<UUID, ReaderKind> = people.readers(row),
    ): Boolean {
        val secretaries = people.secretaries(row.forumId)
        val required = readers.filter { it.value != ReaderKind.ASKED && it.key !in secretaries }.keys
        val answered = comments.answered(listOf(row.meetingId))[row.meetingId].orEmpty()
        return required.isNotEmpty() && answered.containsAll(required)
    }

    private fun readsInFull(
        view: MeetingView,
        set: RedactionSet,
        itemId: UUID,
    ): Boolean = MinutesTemplateData.minuted(view.agenda.items).any { it.id == itemId } && set.redacted.none { it.itemId == itemId }

    private fun recusedFrom(
        meetingId: UUID,
        itemId: UUID,
    ): Set<UUID> = people.recusedOf(meetingId)[itemId].orEmpty()

    private fun recusedItems(
        meetingId: UUID,
        userId: UUID,
    ): Set<UUID> = people.recusedOf(meetingId).filterValues { userId in it }.keys
}
