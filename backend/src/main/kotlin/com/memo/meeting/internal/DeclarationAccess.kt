package com.memo.meeting.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.meeting.LineAccess
import com.memo.meeting.PaperAccess
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * An item a declaration may be on (design E04 §1.1): its live row in the draft (officers), its row in the latest published
 * revision (everyone else), or both. A break is never one.
 */
data class DeclarableItem(
    val id: UUID,
    val parentId: UUID?,
    val kind: ItemKind,
    val title: String,
    val titleHidden: Boolean,
    /** As published, else as it would be published from the draft; null for an item waiting for the chair. */
    val number: String?,
    /** Its row in the latest revision; null when it is not published (yet, or any more). */
    val published: RevisionItem?,
) {
    /** The item and, for a sub-item, its parent: a declaration on either recuses (design E04 §1.6). */
    val scope: List<UUID> get() = listOfNotNull(id, parentId)
}

/** A meeting's items as declarations see them: the latest revision's body and the live draft. */
data class DeclarationAgenda(
    val meeting: MeetingRow,
    /** The latest published revision; 0 before the first. */
    val latest: Int,
    /** The latest revision's id; null before the first. */
    val latestId: UUID?,
    val published: Map<UUID, RevisionItem>,
    val draft: Map<UUID, ItemRow>,
    val draftNumbers: Map<UUID, String>,
) {
    /** The item [id], as an officer may declare on it ([officer]: also draft items), or as anyone else may; null otherwise. */
    fun item(
        id: UUID,
        officer: Boolean,
    ): DeclarableItem? {
        val row = published[id]
        val live = draft[id]
        return when {
            row != null && row.kind != ItemKind.BREAK -> {
                DeclarableItem(row.itemId, row.parentId, row.kind, row.title, row.titleHidden, row.number, row)
            }

            officer && live != null && live.kind != ItemKind.BREAK -> {
                DeclarableItem(live.id, live.parentId, live.kind, live.title, live.titleHidden, draftNumbers[live.id], null)
            }

            else -> {
                null
            }
        }
    }
}

/**
 * Who may declare on what, and who rules (ADR-0062, design E04 §1.1–§1.3). The item part is the E03 title rule
 * (`seeTitle`, design E03 §1.9) as `PaperAccess` judges it for someone in the audience.
 */
@Component
class DeclarationAccess(
    private val items: ItemStore,
    private val sections: SectionStore,
    private val revisions: RevisionStore,
    private val audience: AudienceStore,
    private val recusals: RecusalStore,
    private val directory: ForumDirectory,
    private val viewers: PaperViewers,
    private val paperAccess: PaperAccess,
) {
    /** [meeting]'s items: the latest revision's body and the live draft. */
    fun agenda(meeting: MeetingRow): DeclarationAgenda {
        val head = revisions.heads(meeting.id).lastOrNull()
        val published =
            head
                ?.let { revisions.snapshot(it.id).body().items }
                .orEmpty()
                .associateBy { it.itemId }
        val live = items.live(meeting.id)
        val numbers = AgendaRules.numbers(sections.live(meeting.id), live)
        return DeclarationAgenda(meeting, head?.number ?: 0, head?.id, published, live.associateBy { it.id }, numbers)
    }

    /**
     * The items of [agenda]'s latest revision whose title [userId] sees (E03 `seeTitle` through `PaperAccess`: the item
     * in full, or its line with the title shown), as someone in the audience; `forum.read` alone does not count here.
     */
    fun titles(
        agenda: DeclarationAgenda,
        userId: UUID,
    ): Set<UUID> {
        val revision = agenda.latestId ?: return emptySet()
        val viewer = viewers.of(agenda.meeting, userId)
        return paperAccess
            .revision(viewer, revision)
            .lines
            .filterValues { it != LineAccess.HIDDEN }
            .keys
    }

    /** Whether [userId] is recused from [item]: an effective declaration on it or on its parent (design E04 §1.6). */
    fun recused(
        userId: UUID,
        item: DeclarableItem,
    ): Boolean = recusals.recusedAmong(item.scope, listOf(userId)).isNotEmpty()

    /** The people among [userIds] recused from [item]. */
    fun recusedAmong(
        userIds: Collection<UUID>,
        item: DeclarableItem,
    ): Set<UUID> = recusals.recusedAmong(item.scope, userIds)

    /**
     * The forum's officers in office who are told of declarations: its secretaries and its chair, or the vice-chair when
     * there is no chair (design E03 §4).
     */
    fun officers(forumId: UUID): Set<UUID> {
        val seats = directory.membersOn(forumId)
        val chair = seats.any { it.role == ForumRole.CHAIR }
        return seats
            .filter { it.role == ForumRole.SECRETARY || it.role == ForumRole.CHAIR || (it.role == ForumRole.VICE_CHAIR && !chair) }
            .map { it.userId }
            .toSet()
    }

    /** Whether [userId] is in [meeting]'s audience or holds an officer's seat (secretary, chair, vice-chair) of its forum. */
    fun inAudienceOrOfficer(
        meeting: MeetingRow,
        userId: UUID,
    ): Boolean =
        audience.rowsOf(meeting.id, userId).isNotEmpty() ||
            directory.membersOn(meeting.forumId).any { it.userId == userId && it.role in OFFICER_SEATS }

    /**
     * Who rules on [declarant]'s declaration on [item] (design E04 §1.2): the chair in office, or the vice-chair when the
     * chair is the declarant, is recused from the item, or there is none. Null when nobody can.
     */
    fun ruler(
        forumId: UUID,
        item: DeclarableItem,
        declarant: UUID,
    ): UUID? = decider(forumId, item.scope, declarant)

    /**
     * Who decides for the item whose [scope] (the item and its parent) is given, a late item or paper on it, or a ruling
     * (design E04 §1.2, §1.7): the chair in office, or the vice-chair when the chair is [except] (the declarant), is
     * recused from the item, or there is none. Null when nobody can.
     */
    fun decider(
        forumId: UUID,
        scope: Collection<UUID>,
        except: UUID? = null,
    ): UUID? {
        val seats = directory.membersOn(forumId)
        val candidates =
            listOfNotNull(
                seats.firstOrNull { it.role == ForumRole.CHAIR }?.userId,
                seats.firstOrNull { it.role == ForumRole.VICE_CHAIR }?.userId,
            ).filter { it != except }
        val recused = recusals.recusedAmong(scope, candidates)
        return candidates.firstOrNull { it !in recused }
    }

    private companion object {
        val OFFICER_SEATS = setOf(ForumRole.SECRETARY, ForumRole.CHAIR, ForumRole.VICE_CHAIR)
    }
}
