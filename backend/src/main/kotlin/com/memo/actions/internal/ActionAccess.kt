package com.memo.actions.internal

import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.Cursors
import com.memo.files.Files
import com.memo.forum.ActionsPolicy
import com.memo.forum.DueChange
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.references.ACTIONS_CONTRIBUTOR
import com.memo.jooq.tables.references.ACTIONS_ITEM
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.ItemColumns
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.meeting.PaperAccess
import com.memo.meeting.PaperExclusions
import com.memo.meeting.Recusals
import com.memo.org.OrgDirectory
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Who sees which action (design E07 §1.14, ADR-0061, ADR-0062): the one rule, and the only reader of the action tables for
 * the people actions are shown to (`ActionAccessArchTest`). Written once as a jOOQ condition on the action's own row (its
 * copied item columns) with one `EXISTS` for the recusal, so lists and their counts are set-based and agree:
 *
 * ```
 * involved(v, a)  := v = a.holder ∨ v holds a.ownerPosition ∨ v = a.ownerUser ∨ v ∈ a.contributors ∨ v holds or may claim a's open task
 * seeAction(v, a) := a.status ≠ DRAFT ∧ involved(v, a)
 *                  ∨ cleared(v, a.classification) ∧ ¬recused(v, a.item) ∧
 *                    (a.status = DRAFT ⇒ secretaryOrManager(v, a.forum)) ∧
 *                    (a.item = null ⇒ officer(v, a.forum) ∨ reader(v) ∨ memberToday(v, a.forum)) ∧
 *                    (a.item ≠ null ⇒ PaperAccess.itemCondition(viewerToday(v, a.forum), a.itemColumns)
 *                                     ∨ reader(v) ∧ unrestricted ∧ ¬titleHidden ∧ ¬membersOnly ∧ cleared(v, item level))
 * ```
 * A person recused from the item neither sees nor counts its actions unless they are involved; then they see the action,
 * the source label with the item's number, and the item's title only as the title rule allows. Invisible actions are 404.
 */
@Component
class ActionAccess(
    private val tenantDsl: TenantDsl,
    private val viewers: ActionViewers,
    private val paperAccess: PaperAccess,
    private val exclusions: PaperExclusions,
    private val store: ActionStore,
    private val lines: ActionLines,
    private val directory: ForumDirectory,
    private val users: UserSummaries,
    private val org: OrgDirectory,
    private val meetings: Meetings,
    private val meetingViewers: MeetingViewers,
    private val items: MeetingItems,
    private val recusals: Recusals,
    private val clearances: Clearances,
    private val policies: ForumPolicies,
    private val requests: ActionDueRequests,
    private val abilityRules: ActionAbilityRules,
    private val files: Files,
    private val memoViews: ActionMemoViews,
) {
    /** The action [actionId] as the actor may see it; 404 when they may not (never 403: its existence is not revealed). */
    fun action(actionId: UUID): ActionResponse {
        val viewer = viewers.actor()
        val forumId =
            tenantDsl
                .current()
                .select(ACTIONS_ITEM.FORUM_ID)
                .from(ACTIONS_ITEM)
                .where(ACTIONS_ITEM.ID.eq(actionId))
                .fetchOne()
                ?.value1()
                ?: throw ActionNotFoundException("action")
        val paper = viewer.paper(forumId)
        val a = ACTIONS_ITEM
        val record =
            tenantDsl
                .current()
                .select(
                    a.asterisk(),
                    DSL.field(paperAccess.itemCondition(paper, COLUMNS)).`as`("full"),
                    DSL.field(paperAccess.titleCondition(paper, COLUMNS)).`as`("titled"),
                    DSL.field(exclusions.excluded(viewer.userId, a.ITEM_ID, a.ITEM_PARENT_ID)).`as`("recused"),
                ).from(a)
                .where(a.ID.eq(actionId), seen(viewer, forumId))
                .fetchOne() ?: throw ActionNotFoundException("action")
        val row = record.into(a).actionRow()
        val full = record.get("full", Boolean::class.java) == true
        val titled = record.get("titled", Boolean::class.java) == true
        val recused = row.itemId != null && record.get("recused", Boolean::class.java) == true
        return response(viewer, row, full || titled, recused)
    }

    /**
     * [meetingId]'s actions as the actor may see them, oldest first: drafts for its secretaries and `forums.manage`, and an
     * owner outside the committee their own. 404 for a meeting the actor neither sees nor has an action of.
     */
    fun ofMeeting(meetingId: UUID): MeetingActionsResponse {
        val meeting = meetings.find(meetingId) ?: throw ActionNotFoundException("meeting")
        val viewer = viewers.actor()
        val a = ACTIONS_ITEM
        val rows =
            tenantDsl
                .current()
                .select(a.asterisk(), RagRule.field(org.today()).`as`(RAG))
                .from(a)
                .where(a.MEETING_ID.eq(meetingId).and(seen(viewer, meeting.forumId)))
                .orderBy(a.CREATED_AT, a.ID)
                .fetch { it.into(a).actionRow() to RagWord.valueOf(it.get(RAG, String::class.java)) }
        if (rows.isEmpty() && meetingViewers.find(meetingId) == null) throw ActionNotFoundException("meeting")
        val summaries = summaries(rows)
        return MeetingActionsResponse(meetingId, summaries, rows.count { it.first.status == ActionStatus.DRAFT })
    }

    /**
     * "My actions", MINE (design E07 §3): the issued actions the actor owns (as the person, or as the position's holder) or
     * whose owner's task they hold (a substitute), open or done awaiting verification, by due date; [forumId] narrows them
     * to a committee. The count comes from the same predicate.
     */
    fun mine(
        tab: MyActionsTab,
        forumId: UUID?,
        cursor: String?,
        limit: Int,
    ): MyActionsResponse {
        val viewer = viewers.actor()
        val a = ACTIONS_ITEM
        val mine =
            DSL.or(
                listOfNotNull(
                    a.HOLDER_USER_ID.eq(viewer.userId),
                    a.OWNER_USER_ID.eq(viewer.userId),
                    viewer.positions.takeIf { it.isNotEmpty() }?.let { a.OWNER_POSITION_ID.`in`(it) },
                ),
            )
        val condition =
            a.STATUS
                .`in`(OPEN.map { it.name })
                .and(mine)
                .and(forumId?.let { a.FORUM_ID.eq(it) } ?: DSL.noCondition())
        val size = limit.coerceIn(1, MAX_LIMIT)
        val after =
            cursor?.let(Cursors::decode)?.let { (at, id) ->
                DSL.row(a.DUE_DATE, a.ID).gt(DSL.row(DSL.inline(at.atOffset(ZoneOffset.UTC).toLocalDate()), DSL.inline(id)))
            } ?: DSL.noCondition()
        val rows =
            tenantDsl
                .current()
                .select(a.asterisk(), RagRule.field(org.today()).`as`(RAG))
                .from(a)
                .where(condition, after)
                .orderBy(a.DUE_DATE, a.ID)
                .limit(size + 1)
                .fetch { it.into(a).actionRow() to RagWord.valueOf(it.get(RAG, String::class.java)) }
        val page = rows.take(size)
        val next = if (rows.size > size) page.last().first.let { Cursors.encode(day(checkNotNull(it.dueDate)), it.id) } else null
        val count = tenantDsl.current().fetchCount(a, condition)
        return MyActionsResponse(tab, summaries(page), next, MyActionCounts(count))
    }

    /** Whether [viewer] sees the action [actionId] of [forumId] (the writing services' 404). */
    fun sees(
        viewer: ActionViewer,
        actionId: UUID,
        forumId: UUID,
    ): Boolean = tenantDsl.current().fetchExists(ACTIONS_ITEM, ACTIONS_ITEM.ID.eq(actionId).and(seen(viewer, forumId)))

    /** Whether [viewer] is recused from the source item of the action [actionId] (ADR-0062). */
    fun recused(
        viewer: ActionViewer,
        actionId: UUID,
    ): Boolean =
        tenantDsl.current().fetchExists(
            ACTIONS_ITEM,
            ACTIONS_ITEM.ID.eq(actionId).and(exclusions.excluded(viewer.userId, ACTIONS_ITEM.ITEM_ID, ACTIONS_ITEM.ITEM_PARENT_ID)),
        )

    /** The one rule for [viewer] on [forumId]'s actions, as a condition on `actions_item`. */
    fun seen(
        viewer: ActionViewer,
        forumId: UUID,
    ): Condition {
        val a = ACTIONS_ITEM
        val paper = viewer.paper(forumId)
        val cleared = if (viewer.cleared.isEmpty()) DSL.falseCondition() else a.CLASSIFICATION_ID.`in`(viewer.cleared)
        val notRecused = exclusions.excluded(viewer.userId, a.ITEM_ID, a.ITEM_PARENT_ID).not()
        val drafts = if (viewer.manages(forumId)) DSL.trueCondition() else a.STATUS.ne(ActionStatus.DRAFT.name)
        val withoutItem =
            a.ITEM_ID.isNull.and(
                if (viewer.officer(forumId) || viewer.reader ||
                    viewer.member(forumId)
                ) {
                    DSL.trueCondition()
                } else {
                    DSL.falseCondition()
                },
            )
        val reads =
            if (viewer.reader && viewer.cleared.isNotEmpty()) {
                a.ITEM_RESTRICTED_TO.isNull
                    .and(a.ITEM_TITLE_HIDDEN.isFalse)
                    .and(a.ITEM_MEMBERS_ONLY.isFalse)
                    .and(a.ITEM_CLASSIFICATION_ID.`in`(viewer.cleared))
            } else {
                DSL.falseCondition()
            }
        val withItem = a.ITEM_ID.isNotNull.and(DSL.or(paperAccess.itemCondition(paper, COLUMNS), reads))
        val byRule = cleared.and(notRecused).and(drafts).and(DSL.or(withoutItem, withItem))
        return a.FORUM_ID.eq(forumId).and(DSL.or(a.STATUS.ne(ActionStatus.DRAFT.name).and(involved(viewer)), byRule))
    }

    /** Whether [viewer] is involved in the action: its holder, owner (or the position's holder), contributor or task holder. */
    fun involved(viewer: ActionViewer): Condition {
        val a = ACTIONS_ITEM
        val c = ACTIONS_CONTRIBUTOR
        val contributes =
            DSL.exists(
                DSL
                    .selectOne()
                    .from(c)
                    .where(c.TENANT_ID.eq(a.TENANT_ID), c.ACTION_ID.eq(a.ID), c.USER_ID.eq(viewer.userId), c.REMOVED_AT.isNull),
            )
        return DSL.or(
            listOfNotNull(
                a.HOLDER_USER_ID.eq(viewer.userId),
                a.OWNER_USER_ID.eq(viewer.userId),
                viewer.positions.takeIf { it.isNotEmpty() }?.let { a.OWNER_POSITION_ID.`in`(it) },
                contributes,
                viewer.tasks().takeIf { it.isNotEmpty() }?.let { a.ID.`in`(it) },
            ),
        )
    }

    private fun response(
        viewer: ActionViewer,
        row: ActionRow,
        itemTitled: Boolean,
        recused: Boolean,
    ): ActionResponse {
        val contributors = lines.contributorsOf(row.id)
        val pending = requests.pending(row.id)
        val people = People.of(listOf(row), contributors + listOfNotNull(row.ragOverrideBy, pending?.requestedBy), users, org)
        val committee = directory.forum(row.forumId)?.name.orEmpty()
        val item = row.meetingId?.let { m -> row.itemId?.let { items.agenda(m).item(it) } }
        val manages = viewer.manages(row.forumId) && !recused
        val policy = policies.current(row.forumId)?.actions ?: ActionsPolicy()
        val today = org.today()
        val word = RagRule.word(row.ragFacts, today)
        val override =
            row.ragOverride?.takeIf { word.name.startsWith(OVERRIDE) }?.let {
                ActionRagOverride(it, row.ragOverrideReason.orEmpty(), row.ragOverrideBy?.let(people::person), row.ragOverrideUntil)
            }
        return ActionResponse(
            id = row.id,
            version = row.version,
            forumId = row.forumId,
            committee = committee,
            reference = row.reference,
            status = row.status,
            priority = row.priority,
            title = row.title,
            description = row.description,
            context = row.context,
            classificationId = row.classificationId,
            source = sourceOf(row, item, itemTitled),
            owner = people.owner(row),
            contributors = contributors.map(people::person),
            dueDate = row.dueDate,
            originalDueDate = row.originalDueDate,
            dueRevisions = row.dueRevisions,
            stepName = row.stepName,
            issuedAt = row.issuedAt,
            createdAt = row.createdAt,
            viewerRecused = recused,
            warnings = if (manages) warnings(row, contributors) else emptyList(),
            can =
                abilityRules.of(viewer, row, recused, contributors, policy.dueChange, pending, today).copy(
                    raiseMemo = memoViews.mayRaise(viewer, row, contributors),
                    linkMemo = memoViews.mayLink(viewer, row, contributors, manages),
                ),
            memos = memoViews.of(viewer, row, manages),
            rag = ActionRagResponse(word.colour, word, override),
            confidence = row.confidence,
            lastUpdateAt = row.lastUpdateAt,
            dueChange = policy.dueChange,
            pendingRequest =
                pending?.let {
                    DueRequestResponse(it.id, it.newDate, it.reason, people.person(it.requestedBy), it.requestedAt)
                },
            cancelKind = row.cancelKind,
            cancelReason = row.cancelReason,
            supersededBy = row.supersededBy,
            closedAt = row.closedAt,
            reopenCount = row.reopenCount,
        )
    }

    /** [actionId]'s timeline as the actor may see it, newest first; 404 when they may not see the action. */
    fun entries(actionId: UUID): ActionEntriesResponse {
        val viewer = viewers.actor()
        val forumId = store.find(actionId)?.forumId ?: throw ActionNotFoundException("action")
        notFoundUnless(sees(viewer, actionId, forumId), "action")
        val entries = lines.entries(actionId)
        val names = users.names(entries.flatMap { listOfNotNull(it.actorId, it.more.onBehalfOf) }.toSet())
        val versions = files.currentVersions(entries.flatMap { it.more.fileIds }.toSet())
        val person = { id: UUID -> ActionPerson(id, names[id].orEmpty()) }
        return ActionEntriesResponse(
            actionId,
            entries.map { e ->
                ActionEntryResponse(
                    seq = e.seq,
                    kind = e.kind,
                    actor = e.actorId?.let(person),
                    onBehalfOf = e.more.onBehalfOf?.let(person),
                    how = e.more.how,
                    text = e.more.text,
                    confidence = e.more.confidence,
                    files = e.more.fileIds.mapNotNull { id -> versions[id]?.let { EvidenceFile(id, it.name, it.mediaType, it.sizeBytes) } },
                    corrects = e.more.corrects,
                    // What people wrote is never in the details, only its hash, which the timeline does not show.
                    details = e.details.filterKeys { !it.endsWith("Sha256") }.filterValues { it != null },
                    at = e.at,
                )
            },
        )
    }

    /**
     * The secretary's warnings in words (flow AJ2): an owner recused from the item will see the action but not the item; a
     * person named who is not active or not cleared for the action's level cannot take it.
     */
    private fun warnings(
        row: ActionRow,
        contributors: List<UUID>,
    ): List<ActionWarning> {
        val named =
            listOfNotNull(row.owner?.userId, row.owner?.positionId?.let { org.holders(listOf(it))[it] }) + contributors
        if (named.isEmpty()) return emptyList()
        val recused = row.itemId?.let { recusals.recusedFrom(it) }.orEmpty()
        val rank = clearances.level(row.classificationId)?.rank ?: Int.MAX_VALUE
        val ranks = clearances.rankOf(named)
        val active = users.of(named).filterValues { it.status == com.memo.identity.UserStatus.ACTIVE }.keys
        return named.distinct().flatMap { user ->
            listOfNotNull(
                ActionWarning(OWNER_RECUSED, user).takeIf { user in recused },
                ActionWarning(NOT_CLEARED, user).takeIf { user !in active || (ranks[user] ?: 0) < rank },
            )
        }
    }

    /** Lists' lines, each with its red, amber or green from the SQL reading of the rule. */
    private fun summaries(found: List<Pair<ActionRow, RagWord>>): List<ActionSummary> {
        if (found.isEmpty()) return emptyList()
        val rows = found.map { it.first }
        val people = People.of(rows, emptyList(), users, org)
        val forums = directory.forums(rows.map { it.forumId }.toSet())
        return found.map { (row, word) ->
            ActionSummary(
                id = row.id,
                version = row.version,
                forumId = row.forumId,
                committee = forums[row.forumId]?.name.orEmpty(),
                reference = row.reference,
                status = row.status,
                priority = row.priority,
                title = row.title,
                sourceKind = row.source,
                sourceLabel = row.sourceLabel,
                meetingId = row.meetingId,
                itemId = row.itemId,
                decisionId = row.decisionId,
                conditionIndex = row.conditionIndex,
                owner = people.owner(row),
                dueDate = row.dueDate,
                originalDueDate = row.originalDueDate,
                dueRevisions = row.dueRevisions,
                stepName = row.stepName,
                rag = ActionRagResponse(word.colour, word),
            )
        }
    }

    /** The names of the people and positions a set of actions names, read in one query each. */
    private class People(
        private val names: Map<UUID, String>,
        private val positions: Map<UUID, String>,
    ) {
        fun person(id: UUID) = ActionPerson(id, names[id].orEmpty())

        fun owner(row: ActionRow): ActionOwnerResponse? =
            row.owner?.let { owner ->
                ActionOwnerResponse(
                    kind = owner.kind,
                    person = owner.userId?.let(::person),
                    position = owner.positionId?.let { ActionPosition(it, positions[it].orEmpty()) },
                    holder = row.holderUserId?.let(::person),
                )
            }

        companion object {
            fun of(
                rows: List<ActionRow>,
                more: List<UUID>,
                users: UserSummaries,
                org: OrgDirectory,
            ): People {
                val ids = rows.flatMap { listOfNotNull(it.owner?.userId, it.holderUserId) } + more
                val positionIds = rows.mapNotNull { it.owner?.positionId }.toSet()
                return People(
                    users.names(ids.toSet()),
                    if (positionIds.isEmpty()) emptyMap() else org.positions(positionIds).mapValues { it.value.title },
                )
            }
        }
    }

    companion object {
        const val OWNER_RECUSED = "OWNER_RECUSED"
        const val NOT_CLEARED = "NOT_CLEARED"
        private const val MAX_LIMIT = 100
        private const val RAG = "rag"
        private const val OVERRIDE = "OVERRIDE_"
        private val OPEN = listOf(ActionStatus.OPEN, ActionStatus.IN_PROGRESS, ActionStatus.DONE_PENDING)

        /** The action's copied item columns, as the paper rule reads an item's (no attendee reads an action). */
        val COLUMNS =
            ItemColumns(
                item = ACTIONS_ITEM.ITEM_ID,
                parent = ACTIONS_ITEM.ITEM_PARENT_ID,
                classification = ACTIONS_ITEM.ITEM_CLASSIFICATION_ID,
                restrictedTo = ACTIONS_ITEM.ITEM_RESTRICTED_TO,
                titleHidden = ACTIONS_ITEM.ITEM_TITLE_HIDDEN,
                membersOnly = ACTIONS_ITEM.ITEM_MEMBERS_ONLY,
                attendee = { DSL.falseCondition() },
            )

        private fun day(date: LocalDate) = date.atStartOfDay().toInstant(ZoneOffset.UTC)
    }
}

/** Where [row] comes from, with [item]'s number, and its title only when [titled] (the item rule). */
private fun sourceOf(
    row: ActionRow,
    item: AgendaItemRef?,
    titled: Boolean,
) = ActionSourceResponse(
    kind = row.source,
    label = row.sourceLabel,
    note = row.sourceNote,
    meetingId = row.meetingId,
    itemId = row.itemId,
    itemNumber = item?.number,
    itemTitle = item?.title?.takeIf { titled },
    decisionId = row.decisionId,
    conditionIndex = row.conditionIndex,
)
