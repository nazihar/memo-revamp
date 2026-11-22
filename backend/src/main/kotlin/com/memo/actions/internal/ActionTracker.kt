package com.memo.actions.internal

import com.memo.actions.ActionStats
import com.memo.actions.ClosedOnTime
import com.memo.actions.OverdueActions
import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.web.FieldProblem
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * Opaque cursors of the tracker: the sort and the last row's key, Base64url. A cursor that is not one, or of another
 * sort, is 422 `cursor INVALID`.
 */
object TrackerCursor {
    fun encode(key: TrackerKey): String {
        val text =
            when (key) {
                is TrackerKey.Due -> "${TrackerSort.DUE}|${key.due}|${key.id}"
                is TrackerKey.Updated -> "${TrackerSort.UPDATED}|${key.at}|${key.id}"
                is TrackerKey.Reference -> "${TrackerSort.REFERENCE}|${key.year}.${key.sequence}|${key.id}"
            }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    }

    fun decode(
        cursor: String,
        sort: TrackerSort,
    ): TrackerKey =
        runCatching {
            val parts = String(Base64.getUrlDecoder().decode(cursor)).split('|')
            require(parts.size == PARTS && parts[0] == sort.name)
            val id = UUID.fromString(parts[2])
            when (sort) {
                TrackerSort.DUE -> {
                    TrackerKey.Due(LocalDate.parse(parts[1]), id)
                }

                TrackerSort.UPDATED -> {
                    TrackerKey.Updated(Instant.parse(parts[1]), id)
                }

                TrackerSort.REFERENCE -> {
                    parts[1].split('.').let { (year, sequence) -> TrackerKey.Reference(year.toInt(), sequence.toInt(), id) }
                }
            }
        }.getOrElse { throw ActionRejectedException(listOf(FieldProblem("cursor", "INVALID", "Not a cursor of this list."))) }

    private const val PARTS = 3
}

/** The rows of an export, as the viewer may see them, and whether the row cap cut them. */
data class TrackerExport(
    val committee: String,
    val rows: List<ActionSummary>,
    val truncated: Boolean,
)

/**
 * The committee's action tracker (design E07 §3, §4, flow AN): its issued actions and direct drafts as the viewer may see
 * them ([ActionAccess.seen]), filtered, sorted and paged by keyset, with totals per colour from the same predicate. Who
 * has a tracker: the committee's officers, its members in office, `forum.read` holders, and anyone involved in one of its
 * issued actions (an owner outside the committee sees their own rows); everyone else gets 404. The export (secretaries,
 * the chair, `forums.manage` and `forum.read`; 403 to the others who have a tracker) holds the filtered rows, at most
 * [MAX_EXPORT], and is audited with the filter and the count.
 */
@Service
class ActionTracker(
    private val viewers: ActionViewers,
    private val reads: ActionTrackerReads,
    private val access: ActionAccess,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val org: OrgDirectory,
    private val audit: AuditLog,
) {
    fun page(
        forumId: UUID,
        filter: TrackerFilter,
        cursor: String?,
        limit: Int,
    ): ForumActionsResponse {
        val viewer = viewers.actor()
        val forum = standing(viewer, forumId)
        val checked = checked(filter)
        val after = cursor?.let { TrackerCursor.decode(it, checked.sort) }
        val today = org.today()
        val size = limit.coerceIn(1, MAX_LIMIT)
        val rows = reads.page(viewer, forumId, checked, today, after, size + 1)
        val shown = rows.take(size)
        val next = if (rows.size > size) TrackerCursor.encode(ActionTrackerReads.keyOf(shown.last().first, checked.sort)) else null
        val counts = reads.counts(viewer, forumId, checked, today)
        val policy = policies.current(forumId)?.actions ?: ActionsPolicy()
        val colour = { c: RagColour -> counts.byColour[c] ?: 0 }
        return ForumActionsResponse(
            forumId = forumId,
            committee = forum.name,
            items = access.summaries(shown),
            nextCursor = next,
            matching = counts.matching,
            totals =
                ActionTotals(
                    red = colour(RagColour.RED),
                    amber = colour(RagColour.AMBER),
                    green = colour(RagColour.GREEN),
                    toVerify = colour(RagColour.BLUE),
                    closed = colour(RagColour.GREY),
                    drafts = colour(RagColour.NONE),
                    all = counts.all,
                ),
            key = ActionRagKey(policy.amberWorkingDays, policy.staleWorkingDays.takeIf { it != ActionsPolicy.OFF }),
            can = TrackerAbilities(raise = viewer.manages(forumId), export = exports(viewer, forumId)),
        )
    }

    /**
     * The actor, when they may export [forumId]'s tracker under [filter]: 404 without a tracker, 403 without the right to
     * export, 422 for a filter that is refused.
     */
    fun exporter(
        forumId: UUID,
        filter: TrackerFilter,
    ): UUID {
        val viewer = viewers.actor()
        standing(viewer, forumId)
        forbidUnless(exports(viewer, forumId), Permission.FORUM_READ)
        checked(filter)
        return viewer.userId
    }

    /** The export's rows: the tracker under [filter], every page, at most [MAX_EXPORT]; audited with the filter and the count. */
    fun export(
        forumId: UUID,
        filter: TrackerFilter,
    ): TrackerExport {
        val viewer = viewers.actor()
        val forum = standing(viewer, forumId)
        forbidUnless(exports(viewer, forumId), Permission.FORUM_READ)
        val checked = checked(filter)
        val today = org.today()
        // Pages of the same keyset as the screen, up to one row past the cap (which tells that the cap cut the rows).
        val found = mutableListOf<Pair<ActionRow, RagWord>>()
        var after: TrackerKey? = null
        var wanted = minOf(EXPORT_PAGE, MAX_EXPORT + 1)
        while (wanted > 0) {
            val page = reads.page(viewer, forumId, checked, today, after, wanted)
            found += page
            after = page.lastOrNull()?.let { ActionTrackerReads.keyOf(it.first, checked.sort) }
            wanted = if (page.size < wanted) 0 else minOf(EXPORT_PAGE, MAX_EXPORT + 1 - found.size)
        }
        val truncated = found.size > MAX_EXPORT
        if (truncated) found.removeAt(found.lastIndex)
        audit.record(
            "actions.tracker.exported",
            OBJECT_TYPE,
            forumId.toString(),
            after =
                mapOf(
                    "statuses" to checked.statuses.map { it.name }.sorted(),
                    "rag" to checked.rag?.name,
                    "owner" to checked.owner?.toString(),
                    "overdue" to checked.overdue,
                    "dueFrom" to checked.dueFrom?.toString(),
                    "dueTo" to checked.dueTo?.toString(),
                    "meetingId" to checked.meetingId?.toString(),
                    "itemId" to checked.itemId?.toString(),
                    "source" to checked.source?.name,
                    // What the viewer searched for is free text: its hash only (design E07 §5).
                    "textSha256" to checked.text?.let(ActionRules::sha256),
                    "sort" to checked.sort.name,
                    "count" to found.size,
                    "truncated" to truncated,
                ),
        )
        return TrackerExport(forum.name, access.summaries(found), truncated)
    }

    /** The committee, when [viewer] has its tracker; 404 otherwise (an unknown committee too). */
    private fun standing(
        viewer: ActionViewer,
        forumId: UUID,
    ): ForumRef {
        val forum = directory.forum(forumId) ?: throw ActionNotFoundException("forum")
        val member = viewer.officer(forumId) || viewer.member(forumId) || viewer.reader
        notFoundUnless(member || reads.involvedIn(viewer, forumId), "forum")
        return forum
    }

    private fun exports(
        viewer: ActionViewer,
        forumId: UUID,
    ): Boolean = viewer.officer(forumId) || viewer.reader

    /** [filter] with its text trimmed (blank is none) and checked: 422 `text LENGTH`, `dueTo RANGE`. */
    private fun checked(filter: TrackerFilter): TrackerFilter {
        val text = filter.text?.trim()?.takeIf { it.isNotEmpty() }
        val from = filter.dueFrom
        val to = filter.dueTo
        val backwards = from != null && to != null && to.isBefore(from)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("text", "LENGTH", "At most $MAX_TEXT characters.").takeIf { (text?.length ?: 0) > MAX_TEXT },
                FieldProblem("dueTo", "RANGE", "On or after dueFrom.").takeIf { backwards },
            ),
        )
        return filter.copy(text = text)
    }

    companion object {
        const val MAX_LIMIT = 100
        const val MAX_TEXT = 200
        const val MAX_EXPORT = 5_000
        const val EXPORT_PAGE = 1_000
        const val OBJECT_TYPE = "actions_tracker"
    }
}

/**
 * E08's counts of actions (design E08 §2.2, M2) for the signed-in viewer, each on the one rule in one query
 * ([ActionTrackerReads.overdue], [ActionTrackerReads.closedOnTime]); nothing for the system.
 */
@Service
class ActionStatistics(
    private val viewers: ActionViewers,
    private val reads: ActionTrackerReads,
    private val org: OrgDirectory,
) : ActionStats {
    override fun overdue(
        forumIds: Set<UUID>,
        today: LocalDate,
    ): List<OverdueActions> = if (actorOrNull() == null) emptyList() else reads.overdue(viewers.actor(), forumIds, today)

    override fun closedOnTime(
        forumIds: Set<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): List<ClosedOnTime> = if (actorOrNull() == null) emptyList() else reads.closedOnTime(viewers.actor(), forumIds, from, to, org.zone())
}
