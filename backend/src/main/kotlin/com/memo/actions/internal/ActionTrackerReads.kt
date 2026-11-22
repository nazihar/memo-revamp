package com.memo.actions.internal

import com.memo.actions.ClosedOnTime
import com.memo.actions.OverdueActions
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.ACTIONS_ITEM
import org.jooq.Condition
import org.jooq.Field
import org.jooq.SortField
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** How the tracker is sorted (design E07 §3): by due date (soonest first), by the latest update (newest first), by reference. */
enum class TrackerSort { DUE, UPDATED, REFERENCE }

/**
 * The tracker's filter (design E07 §3, flow AN), each part optional: [statuses] (any of them), the colour [rag], an
 * [owner] (a person, a position or whoever holds the owner's task), [overdue] (OPEN or IN_PROGRESS past the date), the due
 * date from [dueFrom] to [dueTo] (both included), the source [meetingId], [itemId] and [source], and [text] in the title,
 * the description or the reference.
 */
data class TrackerFilter(
    val statuses: Set<ActionStatus> = emptySet(),
    val rag: RagColour? = null,
    val owner: UUID? = null,
    val overdue: Boolean = false,
    val dueFrom: LocalDate? = null,
    val dueTo: LocalDate? = null,
    val meetingId: UUID? = null,
    val itemId: UUID? = null,
    val source: ActionSource? = null,
    val text: String? = null,
    val sort: TrackerSort = TrackerSort.DUE,
)

/** Where a page of the tracker ends: the last row's sort key and id. */
sealed interface TrackerKey {
    val id: UUID

    data class Due(
        val due: LocalDate,
        override val id: UUID,
    ) : TrackerKey

    data class Updated(
        val at: Instant,
        override val id: UUID,
    ) : TrackerKey

    data class Reference(
        val year: Int,
        val sequence: Int,
        override val id: UUID,
    ) : TrackerKey
}

/** The tracker's counts: one per colour, and the rows matching every filter. */
data class TrackerCounts(
    val matching: Int,
    val byColour: Map<RagColour, Int>,
    val all: Int,
)

/**
 * The committee's tracker and E08's statistics in SQL (design E07 §1.14, §3, §8; design E08 M2), each on the one rule
 * ([ActionAccess.seen], the action's own columns with one `EXISTS` for the recusal) and the red-amber-green rule's SQL
 * reading ([RagRule.field], [RagRule.shows]): the rows, their totals and the counts come from the same predicate, so a
 * list and its counts can never disagree and nothing is counted that the viewer may not see. The committee's rows are
 * read through `actions_item_forum_idx` (tenant, committee, status, due date).
 */
@Repository
class ActionTrackerReads(
    private val tenantDsl: TenantDsl,
    private val access: ActionAccess,
) {
    /** One page of [forumId]'s tracker for [viewer] under [filter], after [after], at most [limit] rows, with its colour. */
    fun page(
        viewer: ActionViewer,
        forumId: UUID,
        filter: TrackerFilter,
        today: LocalDate,
        after: TrackerKey?,
        limit: Int,
    ): List<Pair<ActionRow, RagWord>> {
        val a = ACTIONS_ITEM
        val day = day(today)
        return tenantDsl
            .current()
            .select(a.asterisk(), RagRule.field(day).`as`(RAG))
            .from(a)
            .where(base(viewer, forumId, filter), colour(filter, day), after?.let(::after) ?: DSL.noCondition())
            .orderBy(order(filter.sort))
            .limit(limit)
            .fetch { it.into(a).actionRow() to RagWord.valueOf(it.get(RAG, String::class.java)) }
    }

    /**
     * The totals of [forumId]'s tracker for [viewer]: every filter but the colour ([TrackerFilter.rag], `overdue`), so the
     * key's counts stay as the viewer moves between colours, each from [RagRule.shows]; and the rows under every filter.
     */
    fun counts(
        viewer: ActionViewer,
        forumId: UUID,
        filter: TrackerFilter,
        today: LocalDate,
    ): TrackerCounts {
        val a = ACTIONS_ITEM
        val day = day(today)
        val colours = RagColour.entries
        val fields =
            listOf<Field<Int>>(DSL.count().filterWhere(colour(filter, day)), DSL.count()) +
                colours.map { DSL.count().filterWhere(RagRule.shows(it, day)) }
        val record =
            tenantDsl
                .current()
                .select(fields)
                .from(a)
                .where(base(viewer, forumId, filter))
                .fetchSingle()
        return TrackerCounts(
            matching = record.get(0, Int::class.java),
            byColour = colours.withIndex().associate { (i, colour) -> colour to record.get(i + 2, Int::class.java) },
            all = record.get(1, Int::class.java),
        )
    }

    /** Whether [viewer] is involved in an issued action of [forumId] (an owner outside the committee has a tracker). */
    fun involvedIn(
        viewer: ActionViewer,
        forumId: UUID,
    ): Boolean {
        val a = ACTIONS_ITEM
        return tenantDsl.current().fetchExists(
            a,
            a.FORUM_ID
                .eq(forumId)
                .and(a.STATUS.ne(ActionStatus.DRAFT.name))
                .and(access.involved(viewer)),
        )
    }

    /** E08's overdue actions of [forumIds] for [viewer] [today], by committee and owner (one query). */
    fun overdue(
        viewer: ActionViewer,
        forumIds: Set<UUID>,
        today: LocalDate,
    ): List<OverdueActions> {
        if (forumIds.isEmpty()) return emptyList()
        val a = ACTIONS_ITEM
        val day = day(today)
        val late = DSL.localDateDiff(day, a.DUE_DATE)
        return tenantDsl
            .current()
            .select(
                a.FORUM_ID,
                a.OWNER_USER_ID,
                a.OWNER_POSITION_ID,
                a.HOLDER_USER_ID,
                DSL.count(),
                DSL.count().filterWhere(late.le(DSL.inline(FIRST_BAND))),
                DSL.count().filterWhere(late.between(DSL.inline(FIRST_BAND + 1), DSL.inline(SECOND_BAND))),
                DSL.count().filterWhere(late.gt(DSL.inline(SECOND_BAND))),
                DSL.count().filterWhere(a.DUE_REVISIONS.gt(0)),
            ).from(a)
            .where(
                DSL.or(forumIds.map { access.seen(viewer, it) }),
                a.STATUS.`in`(ActionStatus.OPEN.name, ActionStatus.IN_PROGRESS.name),
                a.DUE_DATE.lt(day),
            ).groupBy(a.FORUM_ID, a.OWNER_USER_ID, a.OWNER_POSITION_ID, a.HOLDER_USER_ID)
            .fetch {
                OverdueActions(
                    forumId = checkNotNull(it.value1()),
                    ownerUserId = it.value2(),
                    ownerPositionId = it.value3(),
                    holderUserId = it.value4(),
                    count = it.value5(),
                    days1To30 = it.value6(),
                    days31To90 = it.value7(),
                    over90 = it.value8(),
                    revised = it.value9(),
                )
            }
    }

    /** E08's actions of [forumIds] closed from [from] to [to] (days in [zone]) for [viewer], by committee (one query). */
    fun closedOnTime(
        viewer: ActionViewer,
        forumIds: Set<UUID>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<ClosedOnTime> {
        if (forumIds.isEmpty()) return emptyList()
        val a = ACTIONS_ITEM
        val doneOn = DSL.field("({0} at time zone {1})::date", LocalDate::class.java, a.DONE_AT, DSL.inline(zone.id))
        val start = from.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC)
        val end =
            to
                .plusDays(1)
                .atStartOfDay(zone)
                .toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC)
        return tenantDsl
            .current()
            .select(
                a.FORUM_ID,
                DSL.count(),
                DSL.count().filterWhere(doneOn.le(a.ORIGINAL_DUE_DATE)),
                DSL.count().filterWhere(doneOn.le(a.DUE_DATE)),
            ).from(a)
            .where(
                DSL.or(forumIds.map { access.seen(viewer, it) }),
                a.STATUS.eq(ActionStatus.CLOSED.name),
                a.CLOSED_AT.ge(start),
                a.CLOSED_AT.lt(end),
            ).groupBy(a.FORUM_ID)
            .fetch { ClosedOnTime(checkNotNull(it.value1()), it.value2(), it.value3(), it.value4()) }
    }

    /**
     * Every filter but the colour, on the one rule: the committee's issued actions and its direct drafts (a meeting's
     * drafts are on the meeting's page; a discarded draft is listed nowhere).
     */
    private fun base(
        viewer: ActionViewer,
        forumId: UUID,
        filter: TrackerFilter,
    ): Condition {
        val a = ACTIONS_ITEM
        val parts =
            listOfNotNull(
                access.seen(viewer, forumId),
                a.REFERENCE.isNotNull.or(a.STATUS.eq(ActionStatus.DRAFT.name).and(a.SOURCE.eq(ActionSource.DIRECT.name))),
                filter.statuses.takeIf { it.isNotEmpty() }?.let { s -> a.STATUS.`in`(s.map { it.name }) },
                filter.owner?.let { DSL.or(a.OWNER_USER_ID.eq(it), a.OWNER_POSITION_ID.eq(it), a.HOLDER_USER_ID.eq(it)) },
                filter.dueFrom?.let { a.DUE_DATE.ge(it) },
                filter.dueTo?.let { a.DUE_DATE.le(it) },
                filter.meetingId?.let { a.MEETING_ID.eq(it) },
                filter.itemId?.let { a.ITEM_ID.eq(it) },
                filter.source?.let { a.SOURCE.eq(it.name) },
                filter.text?.let(::text),
            )
        return DSL.and(parts)
    }

    /** The colour filters: [TrackerFilter.rag] by the rule, and `overdue` (OPEN or IN_PROGRESS past the date, design §1.4). */
    private fun colour(
        filter: TrackerFilter,
        day: Field<LocalDate?>,
    ): Condition {
        val a = ACTIONS_ITEM
        return DSL.and(
            listOfNotNull(
                filter.rag?.let { RagRule.shows(it, day) },
                if (filter.overdue) {
                    a.STATUS.`in`(ActionStatus.OPEN.name, ActionStatus.IN_PROGRESS.name).and(a.DUE_DATE.lt(day))
                } else {
                    null
                },
            ),
        )
    }

    /**
     * The words of [query] in the title or the description (`websearch_to_tsquery`, with English stemming and, for every
     * other language, as written), or the reference containing it. Evaluated within the committee's rows (design §8: no
     * full-text index at this size).
     */
    private fun text(query: String): Condition {
        val a = ACTIONS_ITEM
        val document = DSL.concat(a.TITLE, DSL.inline(" "), DSL.coalesce(a.DESCRIPTION, DSL.inline("")))
        val words = { config: String ->
            DSL.condition(
                "to_tsvector({0}::regconfig, {1}) @@ websearch_to_tsquery({0}::regconfig, {2})",
                DSL.inline(config),
                document,
                DSL.value(query),
            )
        }
        return DSL.or(words(ENGLISH), words(SIMPLE), a.REFERENCE.containsIgnoreCase(query))
    }

    private fun order(sort: TrackerSort): List<SortField<*>> {
        val a = ACTIONS_ITEM
        return when (sort) {
            TrackerSort.DUE -> listOf(due().asc(), a.ID.asc())
            TrackerSort.UPDATED -> listOf(updated().desc(), a.ID.desc())
            TrackerSort.REFERENCE -> listOf(year().asc(), sequence().asc(), a.ID.asc())
        }
    }

    private fun after(key: TrackerKey): Condition {
        val a = ACTIONS_ITEM
        return when (key) {
            is TrackerKey.Due -> DSL.row(due(), a.ID).gt(key.due, key.id)
            is TrackerKey.Updated -> DSL.row(updated(), a.ID).lt(key.at.atOffset(ZoneOffset.UTC), key.id)
            is TrackerKey.Reference -> DSL.row(year(), sequence(), a.ID).gt(key.year, key.sequence, key.id)
        }
    }

    companion object {
        /** A draft without a due date sorts after every dated action. */
        val NO_DUE: LocalDate = LocalDate.of(9999, 12, 31)

        /** A draft has no reference: it sorts after every numbered action. */
        const val NO_YEAR = 9999
        const val NO_SEQUENCE = Int.MAX_VALUE
        private const val RAG = "rag"
        private const val ENGLISH = "english"
        private const val SIMPLE = "simple"
        private const val FIRST_BAND = 30
        private const val SECOND_BAND = 90

        private fun day(today: LocalDate): Field<LocalDate?> = DSL.value(today)

        private fun due(): Field<LocalDate?> = DSL.coalesce(ACTIONS_ITEM.DUE_DATE, DSL.inline(NO_DUE))

        private fun updated() = DSL.coalesce(ACTIONS_ITEM.LAST_UPDATE_AT, ACTIONS_ITEM.CREATED_AT)

        private fun year(): Field<Int?> = DSL.coalesce(ACTIONS_ITEM.NUMBER_YEAR, DSL.inline(NO_YEAR))

        private fun sequence(): Field<Int?> = DSL.coalesce(ACTIONS_ITEM.SEQUENCE, DSL.inline(NO_SEQUENCE))

        /** Where [row] stands in [sort]: the next page starts after it. */
        fun keyOf(
            row: ActionRow,
            sort: TrackerSort,
        ): TrackerKey =
            when (sort) {
                TrackerSort.DUE -> TrackerKey.Due(row.dueDate ?: NO_DUE, row.id)
                TrackerSort.UPDATED -> TrackerKey.Updated(row.lastUpdateAt ?: row.createdAt, row.id)
                TrackerSort.REFERENCE -> TrackerKey.Reference(row.numberYear ?: NO_YEAR, row.sequence ?: NO_SEQUENCE, row.id)
            }
    }
}
