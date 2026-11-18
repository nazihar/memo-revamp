package com.memo.meeting

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING_ITEM
import com.memo.jooq.tables.references.MEETING_ITEM_ATTENDEE
import com.memo.jooq.tables.references.MEETING_PAPER
import com.memo.jooq.tables.references.MEETING_REVISION
import com.memo.jooq.tables.references.MEETING_REVISION_ITEM
import com.memo.jooq.tables.references.MEETING_REVISION_PAPER
import com.memo.jooq.tables.references.MEETING_SECTION
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The E04 hook in the paper rule (ADR-0061, ADR-0062): who is kept from an item, applied to everyone, officers included.
 * Each method answers a jOOQ condition, true when [userId] is excluded from the item whose id is [item] and whose parent
 * is [parent] (an exclusion on an item covers its sub-items). The columns are the draft's (`meeting_item`) or a
 * revision's (`meeting_revision_item`), so one `NOT EXISTS` serves both. E04's declarations implement it.
 */
interface PaperExclusions {
    /** The person may not see the item beyond its line (number, time and, unless hidden, title). */
    fun excluded(
        userId: UUID,
        item: Field<UUID?>,
        parent: Field<UUID?>,
    ): Condition

    /** The person may not read the item's papers. */
    fun excludedFromPapers(
        userId: UUID,
        item: Field<UUID?>,
        parent: Field<UUID?>,
    ): Condition
}

/** E03: nobody is excluded. E04 replaces it with the declarations' rule and keeps it for tests. */
@Component
class NoExclusions : PaperExclusions {
    override fun excluded(
        userId: UUID,
        item: Field<UUID?>,
        parent: Field<UUID?>,
    ): Condition = DSL.falseCondition()

    override fun excludedFromPapers(
        userId: UUID,
        item: Field<UUID?>,
        parent: Field<UUID?>,
    ): Condition = DSL.falseCondition()
}

/**
 * A person as the paper rule sees them on one meeting (design E03 §1.9), on today's date: facts about the person and
 * the meeting, read once, that the rule's conditions take as constants.
 */
data class PaperViewer(
    val userId: UUID,
    /** A secretary or the chair of the forum in office today (the vice-chair when there is none), or `forums.manage`. */
    val officer: Boolean,
    /** The classification levels the person is cleared for. */
    val cleared: Set<UUID>,
    /** A roster member of the meeting still in office today. */
    val member: Boolean,
    /** A member who also reads members-only sections (the chair, vice-chair, members and secretaries). */
    val membersOnly: Boolean,
    val guest: Boolean,
    /** An attendee of some item (who sees that item, and the lines of the agenda). */
    val attendee: Boolean,
    /** A `forum.read` holder cleared for the meeting: unrestricted, unhidden titles only. */
    val reader: Boolean,
    /** The meeting is scheduled, in progress, held or later (not a draft, not cancelled): its revisions can be read. */
    val open: Boolean,
    /** The minutes are not approved yet: guests and item attendees still read. */
    val guestsRead: Boolean,
) {
    /** In the meeting's audience today: the lines of its agenda. */
    val inAudience: Boolean get() = member || ((guest || attendee) && guestsRead)
}

/** The columns of an item the rule reads: a draft's or a revision's (both carry the item id and its parent's). */
class ItemColumns(
    val item: Field<UUID?>,
    val parent: Field<UUID?>,
    val classification: Field<UUID?>,
    val restrictedTo: Field<Array<UUID?>?>,
    val titleHidden: Field<Boolean?>,
    /** The item's section is members only (in camera). */
    val membersOnly: Field<Boolean?>,
    /** The person attends this item. */
    val attendee: (UUID) -> Condition,
)

/** The columns of a paper the rule reads. */
class PaperColumns(
    val classification: Field<UUID?>,
    val restrictedTo: Field<Array<UUID?>?>,
)

/** How much of an item's line a person sees (design E03 §1.9). */
enum class LineAccess {
    /** The item: its details and the papers they may read. */
    FULL,

    /** "{n}. {title} — papers restricted". */
    TITLE,

    /** "{n}. Restricted item": its title is hidden. */
    HIDDEN,
}

/** What a person sees of one revision (or of the draft): each line they see, and the papers they may read. */
data class Visibility(
    val lines: Map<UUID, LineAccess>,
    val papers: Set<UUID>,
)

/** A paper of a revision that a person may read, pinned to the file version the revision carried. */
data class PinnedPaper(
    val paperId: UUID,
    val itemId: UUID,
    val revision: Int,
    val fileId: UUID,
    val fileVersionId: UUID,
    val title: String,
)

/**
 * The one paper rule (ADR-0061, design E03 §1.9), written once as jOOQ conditions and used by every read path: the
 * members' agenda and "What changed", paper opens, the revision notifications, the `meeting.paper` purpose and the
 * preview. With the revision store, the only reader of the revision tables (`PaperAccessArchTest`).
 *
 * ```
 * seeItem  := cleared(item) ∧ ¬excluded ∧ (officer ∨ (open ∧ audience ∧ (unrestricted ∨ named)))
 *   audience := member in office (members-only sections for members only) ∨ guest (not members-only) ∨ attendee of it
 *               (guests and attendees until the minutes are approved)
 * seeLine  := seeItem ∨ (in the audience ∧ (not members-only ∨ a member)) ∨ (reader ∧ unhidden ∧ unrestricted)
 * seeTitle := seeItem ∨ (seeLine ∧ unhidden)
 * seePaper := seeItem ∧ cleared(paper) ∧ ¬excludedFromPapers ∧ (unrestricted ∨ named ∨ officer)
 * ```
 */
@Component
class PaperAccess(
    private val tenantDsl: TenantDsl,
    private val exclusions: PaperExclusions,
) {
    /** True for an item [viewer] sees in full. */
    fun itemCondition(
        viewer: PaperViewer,
        item: ItemColumns,
    ): Condition {
        val audience =
            DSL.or(
                when {
                    !viewer.member -> DSL.falseCondition()
                    viewer.membersOnly -> DSL.trueCondition()
                    else -> item.membersOnly.isFalse
                },
                if (viewer.guest && viewer.guestsRead) item.membersOnly.isFalse else DSL.falseCondition(),
                if (viewer.guestsRead) item.attendee(viewer.userId) else DSL.falseCondition(),
            )
        val allowed =
            when {
                viewer.officer -> DSL.trueCondition()
                viewer.open -> audience.and(named(viewer.userId, item.restrictedTo))
                else -> DSL.falseCondition()
            }
        return cleared(viewer, item.classification)
            .and(exclusions.excluded(viewer.userId, item.item, item.parent).not())
            .and(allowed)
    }

    /** True for an item whose line [viewer] sees: the item itself, or its number and (unless hidden) its title. */
    fun lineCondition(
        viewer: PaperViewer,
        item: ItemColumns,
    ): Condition =
        DSL.or(
            itemCondition(viewer, item),
            when {
                !viewer.inAudience -> DSL.falseCondition()
                viewer.member && viewer.membersOnly -> DSL.trueCondition()
                else -> item.membersOnly.isFalse
            },
            if (viewer.reader) item.titleHidden.isFalse.and(item.restrictedTo.isNull) else DSL.falseCondition(),
        )

    /** True for a paper of an item [viewer] may read. */
    fun paperCondition(
        viewer: PaperViewer,
        item: ItemColumns,
        paper: PaperColumns,
    ): Condition =
        itemCondition(viewer, item)
            .and(cleared(viewer, paper.classification))
            .and(exclusions.excludedFromPapers(viewer.userId, item.item, item.parent).not())
            .and(if (viewer.officer) DSL.trueCondition() else named(viewer.userId, paper.restrictedTo))

    /** What [viewer] sees of revision [revisionId]: its removed rows ("What changed") are judged as they were published. */
    fun revision(
        viewer: PaperViewer,
        revisionId: UUID,
    ): Visibility {
        val dsl = tenantDsl.current()
        val i = MEETING_REVISION_ITEM
        val p = MEETING_REVISION_PAPER
        val lines =
            dsl
                .select(i.ITEM_ID, DSL.field(itemCondition(viewer, REVISION_ITEM)), DSL.field(i.TITLE_HIDDEN.isFalse))
                .from(i)
                .where(i.REVISION_ID.eq(revisionId), lineCondition(viewer, REVISION_ITEM))
                .fetch { checkNotNull(it.value1()) to access(it.value2(), it.value3()) }
                .toMap()
        val papers =
            dsl
                .select(p.PAPER_ID)
                .from(p)
                .join(i)
                .on(i.TENANT_ID.eq(p.TENANT_ID), i.REVISION_ID.eq(p.REVISION_ID), i.ITEM_ID.eq(p.ITEM_ID))
                .where(p.REVISION_ID.eq(revisionId), paperCondition(viewer, REVISION_ITEM, REVISION_PAPER))
                .fetchSet(p.PAPER_ID)
                .filterNotNull()
                .toSet()
        return Visibility(lines, papers)
    }

    /** What [viewer] would see of [meetingId]'s draft if it were published now (the preview "as a member"). */
    fun draft(
        viewer: PaperViewer,
        meetingId: UUID,
    ): Visibility {
        val dsl = tenantDsl.current()
        val i = MEETING_ITEM
        val s = MEETING_SECTION
        val p = MEETING_PAPER
        val lines =
            dsl
                .select(i.ID, DSL.field(itemCondition(viewer, DRAFT_ITEM)), DSL.field(i.TITLE_HIDDEN.isFalse))
                .from(i)
                .join(s)
                .on(s.TENANT_ID.eq(i.TENANT_ID), s.ID.eq(i.SECTION_ID))
                .where(i.MEETING_ID.eq(meetingId), i.REMOVED_AT.isNull, lineCondition(viewer, DRAFT_ITEM))
                .fetch { checkNotNull(it.value1()) to access(it.value2(), it.value3()) }
                .toMap()
        val papers =
            dsl
                .select(p.ID)
                .from(p)
                .join(i)
                .on(i.TENANT_ID.eq(p.TENANT_ID), i.ID.eq(p.ITEM_ID))
                .join(s)
                .on(s.TENANT_ID.eq(i.TENANT_ID), s.ID.eq(i.SECTION_ID))
                .where(p.MEETING_ID.eq(meetingId), p.REMOVED_AT.isNull, paperCondition(viewer, DRAFT_ITEM, DRAFT_PAPER))
                .fetchSet(p.ID)
                .filterNotNull()
                .toSet()
        return Visibility(lines, papers)
    }

    /**
     * Paper [paperId] of revision [revision] of [meetingId] (the latest when null), pinned to its version, if [viewer]
     * may read it there; null otherwise (404). One indexed query.
     */
    fun paper(
        viewer: PaperViewer,
        meetingId: UUID,
        paperId: UUID,
        revision: Int?,
    ): PinnedPaper? {
        val dsl = tenantDsl.current()
        val r = MEETING_REVISION
        val i = MEETING_REVISION_ITEM
        val p = MEETING_REVISION_PAPER
        val number =
            revision?.let { DSL.inline(it) }
                ?: DSL.field(dsl.select(DSL.max(r.NUMBER)).from(r).where(r.MEETING_ID.eq(meetingId)))
        return dsl
            .select(p.PAPER_ID, p.ITEM_ID, r.NUMBER, p.FILE_ID, p.FILE_VERSION_ID, p.TITLE)
            .from(p)
            .join(r)
            .on(r.TENANT_ID.eq(p.TENANT_ID), r.ID.eq(p.REVISION_ID))
            .join(i)
            .on(i.TENANT_ID.eq(p.TENANT_ID), i.REVISION_ID.eq(p.REVISION_ID), i.ITEM_ID.eq(p.ITEM_ID))
            .where(
                r.MEETING_ID.eq(meetingId),
                r.NUMBER.eq(number),
                p.PAPER_ID.eq(paperId),
                p.REMOVED.isFalse,
                paperCondition(viewer, REVISION_ITEM, REVISION_PAPER),
            ).fetchOne { PinnedPaper(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!, it.value6()!!) }
    }

    /**
     * Whether revision [revisionId] changed something [viewer] sees (design E03 §9): a flag on an item they see in full, or
     * on a paper they may read (a removed row judged as it was published before). For the revision's notifications.
     */
    fun changed(
        viewer: PaperViewer,
        revisionId: UUID,
    ): Boolean {
        val dsl = tenantDsl.current()
        val i = MEETING_REVISION_ITEM
        val p = MEETING_REVISION_PAPER
        val flagged = DSL.or(ITEM_CHANGES.map { DSL.inline(it).eq(DSL.any(i.CHANGES)) })
        val item = dsl.selectOne().from(i).where(i.REVISION_ID.eq(revisionId), flagged, itemCondition(viewer, REVISION_ITEM))
        val paper =
            dsl
                .selectOne()
                .from(p)
                .join(i)
                .on(i.TENANT_ID.eq(p.TENANT_ID), i.REVISION_ID.eq(p.REVISION_ID), i.ITEM_ID.eq(p.ITEM_ID))
                .where(
                    p.REVISION_ID.eq(revisionId),
                    DSL.cardinality(p.CHANGES).gt(0),
                    paperCondition(viewer, REVISION_ITEM, REVISION_PAPER),
                )
        return dsl.fetchExists(item) || dsl.fetchExists(paper)
    }

    /**
     * Whether [viewer] may read file [fileId] of meeting [meetingId] through the generic files endpoints (the
     * `meeting.paper` purpose's `mayRead`): officers only, and only when every paper carrying the file, in the draft and
     * in every revision, passes the rule for them. Members read pinned versions through the meeting's own endpoint: the
     * generic endpoints serve any version of a file and cannot tell which one a revision carried.
     */
    fun readsFile(
        viewer: PaperViewer,
        meetingId: UUID,
        fileId: UUID,
    ): Boolean {
        if (!viewer.officer) return false
        val dsl = tenantDsl.current()
        val i = MEETING_ITEM
        val s = MEETING_SECTION
        val p = MEETING_PAPER
        val draft =
            dsl
                .selectOne()
                .from(p)
                .join(i)
                .on(i.TENANT_ID.eq(p.TENANT_ID), i.ID.eq(p.ITEM_ID))
                .join(s)
                .on(s.TENANT_ID.eq(i.TENANT_ID), s.ID.eq(i.SECTION_ID))
                .where(p.MEETING_ID.eq(meetingId), p.FILE_ID.eq(fileId), paperCondition(viewer, DRAFT_ITEM, DRAFT_PAPER).not())
        val ri = MEETING_REVISION_ITEM
        val rp = MEETING_REVISION_PAPER
        val published =
            dsl
                .selectOne()
                .from(rp)
                .join(ri)
                .on(ri.TENANT_ID.eq(rp.TENANT_ID), ri.REVISION_ID.eq(rp.REVISION_ID), ri.ITEM_ID.eq(rp.ITEM_ID))
                .join(MEETING_REVISION)
                .on(MEETING_REVISION.TENANT_ID.eq(rp.TENANT_ID), MEETING_REVISION.ID.eq(rp.REVISION_ID))
                .where(
                    MEETING_REVISION.MEETING_ID.eq(meetingId),
                    rp.FILE_ID.eq(fileId),
                    paperCondition(viewer, REVISION_ITEM, REVISION_PAPER).not(),
                )
        return !dsl.fetchExists(draft) && !dsl.fetchExists(published)
    }

    private fun cleared(
        viewer: PaperViewer,
        level: Field<UUID?>,
    ): Condition = if (viewer.cleared.isEmpty()) DSL.falseCondition() else level.`in`(viewer.cleared)

    private companion object {
        /** Item flags judged on the item itself; paper flags are judged on each paper the person may read. */
        val ITEM_CHANGES = listOf("ADDED", "REMOVED", "MOVED", "RETITLED", "TIME_CHANGED", "LATE", "TABLED")

        /** The revision tables' columns. */
        val REVISION_ITEM =
            ItemColumns(
                item = MEETING_REVISION_ITEM.ITEM_ID,
                parent = MEETING_REVISION_ITEM.PARENT_ITEM_ID,
                classification = MEETING_REVISION_ITEM.CLASSIFICATION_ID,
                restrictedTo = MEETING_REVISION_ITEM.RESTRICTED_TO,
                titleHidden = MEETING_REVISION_ITEM.TITLE_HIDDEN,
                membersOnly = MEETING_REVISION_ITEM.MEMBERS_ONLY,
                attendee = { user -> DSL.inline(user).eq(DSL.any(MEETING_REVISION_ITEM.ATTENDEES)) },
            )
        val REVISION_PAPER = PaperColumns(MEETING_REVISION_PAPER.CLASSIFICATION_ID, MEETING_REVISION_PAPER.RESTRICTED_TO)

        /** The draft's columns: the item joined to its section. */
        val DRAFT_ITEM =
            ItemColumns(
                item = MEETING_ITEM.ID,
                parent = MEETING_ITEM.PARENT_ID,
                classification = MEETING_ITEM.CLASSIFICATION_ID,
                restrictedTo = MEETING_ITEM.RESTRICTED_TO,
                titleHidden = MEETING_ITEM.TITLE_HIDDEN,
                membersOnly = MEETING_SECTION.MEMBERS_ONLY,
                attendee = { user ->
                    DSL.exists(
                        DSL
                            .selectOne()
                            .from(MEETING_ITEM_ATTENDEE)
                            .where(
                                MEETING_ITEM_ATTENDEE.TENANT_ID.eq(MEETING_ITEM.TENANT_ID),
                                MEETING_ITEM_ATTENDEE.ITEM_ID.eq(MEETING_ITEM.ID),
                                MEETING_ITEM_ATTENDEE.USER_ID.eq(user),
                                MEETING_ITEM_ATTENDEE.REMOVED_AT.isNull,
                            ),
                    )
                },
            )
        val DRAFT_PAPER = PaperColumns(MEETING_PAPER.CLASSIFICATION_ID, MEETING_PAPER.RESTRICTED_TO)

        /** Unrestricted, or the restriction names [user]. */
        fun named(
            user: UUID,
            restrictedTo: Field<Array<UUID?>?>,
        ): Condition = restrictedTo.isNull.or(DSL.inline(user).eq(DSL.any(restrictedTo)))

        fun access(
            full: Boolean?,
            titled: Boolean?,
        ): LineAccess =
            when {
                full == true -> LineAccess.FULL
                titled == true -> LineAccess.TITLE
                else -> LineAccess.HIDDEN
            }
    }
}
