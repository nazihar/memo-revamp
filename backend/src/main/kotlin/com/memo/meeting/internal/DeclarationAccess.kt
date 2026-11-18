package com.memo.meeting.internal

import com.memo.common.authz.Clearances
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.org.OrgDirectory
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
 * (`seeTitle`, design E03 §1.9) for the audience; T2 folds it into `PaperAccess` with the recusal exclusion.
 */
@Component
class DeclarationAccess(
    private val items: ItemStore,
    private val sections: SectionStore,
    private val revisions: RevisionStore,
    private val audience: AudienceStore,
    private val store: DeclarationStore,
    private val directory: ForumDirectory,
    private val clearances: Clearances,
    private val org: OrgDirectory,
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
        return DeclarationAgenda(meeting, head?.number ?: 0, published, live.associateBy { it.id }, numbers)
    }

    /** [userId] as the title rule judges them in [meeting]: their audience rows and clearance. */
    fun viewer(
        meeting: MeetingRow,
        userId: UUID,
        officer: Boolean,
    ): Viewer =
        Viewer(
            userId = userId,
            rank = clearances.rankOf(listOf(userId))[userId] ?: 0,
            officer = officer,
            rows = audience.rowsOf(meeting.id, userId),
            today = org.today(),
        )

    /**
     * Whether [viewer] sees [item]'s title (E03 `seeTitle`): they see the item; or they are in the audience (a member in
     * office, a guest outside members-only sections, or the item's attendee) and the item does not hide its title.
     */
    fun seesTitle(
        viewer: Viewer,
        item: RevisionItem,
    ): Boolean {
        if (viewer.officer) return true
        val rank = { level: UUID -> clearances.level(level)?.rank ?: Int.MAX_VALUE }
        val inAudience =
            viewer.member(item.membersOnly) || (viewer.guest() && !item.membersOnly) || viewer.userId in item.attendees
        return AgendaVisibility.seesItem(viewer, item, rank) || (inAudience && !item.titleHidden)
    }

    /** Whether [userId] is recused from [item]: an effective declaration on it or on its parent (design E04 §1.6). */
    fun recused(
        userId: UUID,
        item: DeclarableItem,
    ): Boolean = store.recusedAmong(item.scope, listOf(userId)).isNotEmpty()

    /** The people among [userIds] recused from [item]. */
    fun recusedAmong(
        userIds: Collection<UUID>,
        item: DeclarableItem,
    ): Set<UUID> = store.recusedAmong(item.scope, userIds)

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
    ): UUID? {
        val seats = directory.membersOn(forumId)
        val candidates =
            listOfNotNull(
                seats.firstOrNull { it.role == ForumRole.CHAIR }?.userId,
                seats.firstOrNull { it.role == ForumRole.VICE_CHAIR }?.userId,
            ).filter { it != declarant }
        val recused = recusedAmong(candidates, item)
        return candidates.firstOrNull { it !in recused }
    }

    private companion object {
        val OFFICER_SEATS = setOf(ForumRole.SECRETARY, ForumRole.CHAIR, ForumRole.VICE_CHAIR)
    }
}
