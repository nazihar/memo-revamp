package com.memo.memo.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.OrgTree
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import com.memo.org.OrgUnitDirectory
import com.memo.org.PositionRef
import com.memo.workflow.TaskItem
import com.memo.workflow.WorkflowTeamQueries
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * The units a person oversees for [Permission.INBOX_TEAM_READ] or [Permission.MEMO_DASHBOARD_READ] (design E05 §5, §6;
 * owner's answers 1 and 6): the subtree of the unit whose head position they hold, and the scopes of their grants of
 * the permission (all units for a tenant-wide grant). Empty for most people.
 */
@Component
class TeamScopes(
    private val policy: Policy,
    private val org: OrgDirectory,
    private val units: OrgUnitDirectory,
    private val tree: ObjectProvider<OrgTree>,
) {
    fun of(
        viewer: UUID,
        permission: Permission,
    ): Set<UUID> {
        val orgTree = tree.ifAvailable

        fun subtree(root: UUID) = orgTree?.subtree(root) ?: setOf(root)
        val headed =
            org
                .positionsOf(listOf(viewer))[viewer]
                ?.takeIf { it.head }
                ?.let { subtree(it.unitId) }
                .orEmpty()
        val grants = policy.grants().filter { it.permission == permission }
        val granted = if (grants.any { it.scopeUnit == null }) units.allUnits() else grants.mapNotNull { it.scopeUnit }.flatMap(::subtree)
        return headed + granted
    }

    /** The people seated in [scope] now, with their positions. */
    fun seated(scope: Set<UUID>): Map<UUID, PositionRef> = if (scope.isEmpty()) emptyMap() else units.seatedIn(scope)

    /** [unit] and its subtree, within [scope]; refused when [unit] is outside it. */
    fun narrowed(
        scope: Set<UUID>,
        unit: UUID?,
        permission: Permission,
    ): Set<UUID> =
        when {
            unit == null -> scope
            unit !in scope -> throw ForbiddenException(permission)
            else -> (tree.ifAvailable?.subtree(unit) ?: setOf(unit)).intersect(scope)
        }
}

/** An open task of someone in the team (flow I1). */
data class TeamTask(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val taskId: UUID,
    /** The memo; `restricted` (no number or subject) when the viewer may not read it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memo: InboxMemo,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val holder: Named,
    val unit: Named?,
    val stepName: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val assignedAt: Instant,
    val dueAt: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdue: Boolean,
    /** Working days since it was assigned, by the business calendar. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ageWorkingDays: Int,
)

data class TeamPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<TeamTask>,
    val nextCursor: String?,
    /** The units the viewer oversees, for the unit picker. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val units: List<Named>,
)

/**
 * The team view (design E05 §5, flow I): the open tasks of the people seated in the units the viewer oversees, by due
 * time (overdue first). A memo the viewer may not read shows as restricted, and can still be reassigned (bulk REASSIGN).
 * 403 for people who oversee no unit. Inside a tenant transaction.
 */
@Service
class MemoTeam(
    private val scopes: TeamScopes,
    private val team: WorkflowTeamQueries,
    private val lists: MemoListStore,
    private val readable: MemoReadable,
    private val users: UserSummaries,
    private val units: OrgUnitDirectory,
    private val org: OrgDirectory,
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun tasks(
        filter: TeamFilter,
        cursor: String?,
        limit: Int,
    ): TeamPage {
        val viewer = viewer()
        val scope = scopes.of(viewer, Permission.INBOX_TEAM_READ)
        if (scope.isEmpty()) throw ForbiddenException(Permission.INBOX_TEAM_READ)
        val seated = scopes.seated(scopes.narrowed(scope, filter.unit, Permission.INBOX_TEAM_READ))
        val people = filter.person?.let { person -> setOf(person).filter { it in seated } } ?: seated.keys
        val overdueAt = clock.instant().takeIf { filter.overdueOnly }
        val page = team.openTasksOf(people, cursor, limit, byDue = true, overdueAt = overdueAt)
        val unitNames = units.units(scope)
        return TeamPage(
            views(page.items, seated, viewer),
            page.nextCursor,
            unitNames.values.map { Named(it.id, it.name) }.sortedBy { it.name },
        )
    }

    private fun views(
        tasks: List<TaskItem>,
        seated: Map<UUID, PositionRef>,
        viewer: UUID,
    ): List<TeamTask> {
        val memoIds = tasks.map { it.subject.id }.toSet()
        val summaries = lists.summaries(memoIds)
        val visible = readableAmong(memoIds, viewer)
        val holders = users.of(tasks.mapNotNull { it.assigneeId }.toSet())
        val unitNames = units.units(seated.values.map { it.unitId }.toSet())
        val today = org.today()
        val now = clock.instant()
        return tasks.mapNotNull { task ->
            val holder = task.assigneeId ?: return@mapNotNull null
            val summary = summaries[task.subject.id]?.takeIf { task.subject.id in visible }
            val unit = seated[holder]?.unitId?.let { id -> unitNames[id]?.let { Named(id, it.name) } }
            TeamTask(
                taskId = task.taskId,
                memo = InboxMemo(task.subject.id, summary?.reference, summary?.title, summary == null),
                holder = Named(holder, holders[holder]?.displayName.orEmpty()),
                unit = unit,
                stepName = task.stepName,
                assignedAt = task.createdAt,
                dueAt = task.dueAt,
                overdue = task.dueAt?.isBefore(now) == true,
                ageWorkingDays = team.workingDays(LocalDate.ofInstant(task.createdAt, org.zone()), today),
            )
        }
    }

    /** Which of [ids] [viewer] may read (the E03 rule, ADR-0050). */
    private fun readableAmong(
        ids: Collection<UUID>,
        viewer: UUID,
    ): Set<UUID> =
        if (ids.isEmpty()) {
            emptySet()
        } else {
            tenantDsl
                .current()
                .select(MEMO.ID)
                .from(MEMO)
                .where(MEMO.ID.`in`(ids), readable.condition(viewer))
                .fetch(MEMO.ID)
                .filterNotNull()
                .toSet()
        }

    private fun viewer(): UUID =
        checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "the team view needs a signed-in user" }
}

/** What the team view lists: one unit of the scope (null: all of it), pending or only overdue tasks, one person. */
data class TeamFilter(
    val unit: UUID? = null,
    val overdueOnly: Boolean = false,
    val person: UUID? = null,
)
