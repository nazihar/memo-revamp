package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.references.MEMO_DISTRIBUTION_LIST
import com.memo.jooq.tables.references.MEMO_DISTRIBUTION_MEMBER
import com.memo.org.OrgDirectory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A member of a distribution list: a person, or a position (its holder when a copy is sent). */
enum class MemberKind { USER, POSITION }

data class ListMember(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MemberKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
)

data class DistributionList(
    val id: UUID,
    val name: String,
    val active: Boolean,
    val version: Long,
    val members: List<ListMember>,
)

/** `memo_distribution_list` and its members (V14); inside a tenant transaction. */
@Repository
class DistributionListStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
) {
    fun all(activeOnly: Boolean): List<DistributionList> {
        val l = MEMO_DISTRIBUTION_LIST
        val rows =
            tenantDsl
                .current()
                .selectFrom(l)
                .where(
                    if (activeOnly) {
                        l.ACTIVE.isTrue
                    } else {
                        org.jooq.impl.DSL
                            .trueCondition()
                    },
                ).orderBy(l.NAME)
                .fetch()
        val members = membersOf(rows.map { it.id })
        return rows.map { DistributionList(it.id, it.name, it.active == true, it.version ?: 0, members[it.id].orEmpty()) }
    }

    fun get(id: UUID): DistributionList? {
        val row =
            tenantDsl
                .current()
                .selectFrom(MEMO_DISTRIBUTION_LIST)
                .where(MEMO_DISTRIBUTION_LIST.ID.eq(id))
                .fetchOne() ?: return null
        return DistributionList(row.id, row.name, row.active == true, row.version ?: 0, members(id))
    }

    fun members(listId: UUID): List<ListMember> = membersOf(listOf(listId))[listId].orEmpty()

    fun activeIds(ids: Collection<UUID>): Set<UUID> =
        if (ids.isEmpty()) {
            emptySet()
        } else {
            tenantDsl
                .current()
                .select(MEMO_DISTRIBUTION_LIST.ID)
                .from(MEMO_DISTRIBUTION_LIST)
                .where(MEMO_DISTRIBUTION_LIST.ID.`in`(ids), MEMO_DISTRIBUTION_LIST.ACTIVE.isTrue)
                .fetchSet(MEMO_DISTRIBUTION_LIST.ID)
                .filterNotNull()
                .toSet()
        }

    fun names(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .select(MEMO_DISTRIBUTION_LIST.ID, MEMO_DISTRIBUTION_LIST.NAME)
                .from(MEMO_DISTRIBUTION_LIST)
                .where(MEMO_DISTRIBUTION_LIST.ID.`in`(ids))
                .fetchMap(MEMO_DISTRIBUTION_LIST.ID, MEMO_DISTRIBUTION_LIST.NAME)
                .mapNotNull { (k, v) -> if (k != null && v != null) k to v else null }
                .toMap()
        }

    /** A new list; a name taken by another active list is refused (409 `NAME_TAKEN`). */
    fun create(
        name: String,
        members: List<ListMember>,
        by: UUID,
        at: OffsetDateTime,
    ): UUID {
        val id = ids.next()
        unique {
            tenantDsl
                .current()
                .insertInto(MEMO_DISTRIBUTION_LIST)
                .set(MEMO_DISTRIBUTION_LIST.ID, id)
                .set(MEMO_DISTRIBUTION_LIST.TENANT_ID, tenantDsl.tenant().value)
                .set(MEMO_DISTRIBUTION_LIST.NAME, name)
                .set(MEMO_DISTRIBUTION_LIST.CREATED_BY, by)
                .set(MEMO_DISTRIBUTION_LIST.CREATED_AT, at)
                .execute()
        }
        setMembers(id, members)
        return id
    }

    /** Renames and re-members the list at [version]; false when it changed since. */
    fun replace(
        id: UUID,
        version: Long,
        name: String,
        members: List<ListMember>,
    ): Boolean {
        val changed =
            unique {
                tenantDsl
                    .current()
                    .update(MEMO_DISTRIBUTION_LIST)
                    .set(MEMO_DISTRIBUTION_LIST.NAME, name)
                    .set(MEMO_DISTRIBUTION_LIST.VERSION, MEMO_DISTRIBUTION_LIST.VERSION.plus(1))
                    .where(
                        MEMO_DISTRIBUTION_LIST.ID.eq(id),
                        MEMO_DISTRIBUTION_LIST.VERSION.eq(version),
                        MEMO_DISTRIBUTION_LIST.ACTIVE.isTrue,
                    ).execute() == 1
            }
        if (changed) setMembers(id, members)
        return changed
    }

    fun deactivate(
        id: UUID,
        version: Long,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_DISTRIBUTION_LIST)
            .set(MEMO_DISTRIBUTION_LIST.ACTIVE, false)
            .set(MEMO_DISTRIBUTION_LIST.VERSION, MEMO_DISTRIBUTION_LIST.VERSION.plus(1))
            .where(MEMO_DISTRIBUTION_LIST.ID.eq(id), MEMO_DISTRIBUTION_LIST.VERSION.eq(version), MEMO_DISTRIBUTION_LIST.ACTIVE.isTrue)
            .execute() == 1

    private fun setMembers(
        id: UUID,
        members: List<ListMember>,
    ) {
        val dsl = tenantDsl.current()
        dsl.deleteFrom(MEMO_DISTRIBUTION_MEMBER).where(MEMO_DISTRIBUTION_MEMBER.LIST_ID.eq(id)).execute()
        if (members.isEmpty()) return
        val insert =
            dsl.insertInto(
                MEMO_DISTRIBUTION_MEMBER,
                MEMO_DISTRIBUTION_MEMBER.TENANT_ID,
                MEMO_DISTRIBUTION_MEMBER.LIST_ID,
                MEMO_DISTRIBUTION_MEMBER.KIND,
                MEMO_DISTRIBUTION_MEMBER.MEMBER_ID,
            )
        members.distinct().fold(insert) { q, m -> q.values(tenantDsl.tenant().value, id, m.kind.name, m.id) }.execute()
    }

    private fun membersOf(listIds: Collection<UUID>): Map<UUID, List<ListMember>> =
        if (listIds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(MEMO_DISTRIBUTION_MEMBER)
                .where(MEMO_DISTRIBUTION_MEMBER.LIST_ID.`in`(listIds))
                .orderBy(MEMO_DISTRIBUTION_MEMBER.KIND, MEMO_DISTRIBUTION_MEMBER.MEMBER_ID)
                .fetch()
                .groupBy({ it.listId }, { ListMember(MemberKind.valueOf(it.kind), it.memberId) })
        }

    private fun <T> unique(write: () -> T): T =
        try {
            write()
        } catch (_: IntegrityConstraintViolationException) {
            throw RefusedException("NAME_TAKEN", "Another active list has this name.")
        } catch (_: DuplicateKeyException) {
            throw RefusedException("NAME_TAKEN", "Another active list has this name.")
        }
}

/**
 * Distribution lists (ADR-0050): named sets of people and positions that copy-to can name, kept by holders of
 * `memo.distribution.manage`. Everyone signed in may read them to pick recipients. A list is never deleted, only
 * switched off; changing it does not change copies already sent. Inside a tenant transaction.
 */
@Service
class DistributionLists(
    private val store: DistributionListStore,
    private val users: UserSummaries,
    private val directory: OrgDirectory,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun all(): List<DistributionList> = store.all(activeOnly = true)

    fun get(id: UUID): DistributionList = store.get(id)?.takeIf { it.active } ?: throw MemoNotFoundException("distribution list")

    fun create(
        name: String,
        members: List<ListMember>,
    ): DistributionList {
        check(name, members)
        val id = store.create(name.trim(), members, actor(), now())
        audit.record("distribution.list.created", OBJECT_TYPE, id.toString(), after = mapOf("members" to members.size))
        return get(id)
    }

    fun replace(
        id: UUID,
        version: Long,
        name: String,
        members: List<ListMember>,
    ): DistributionList {
        get(id)
        check(name, members)
        if (!store.replace(id, version, name.trim(), members)) throw ConcurrentModificationException("distribution list $id", version)
        audit.record("distribution.list.changed", OBJECT_TYPE, id.toString(), after = mapOf("members" to members.size))
        return get(id)
    }

    fun deactivate(
        id: UUID,
        version: Long,
    ) {
        get(id)
        if (!store.deactivate(id, version)) throw ConcurrentModificationException("distribution list $id", version)
        audit.record("distribution.list.deactivated", OBJECT_TYPE, id.toString())
    }

    private fun check(
        name: String,
        members: List<ListMember>,
    ) {
        val people = users.of(members.filter { it.kind == MemberKind.USER }.map { it.id })
        val positions = directory.positions(members.filter { it.kind == MemberKind.POSITION }.map { it.id })
        val problems =
            listOfNotNull(
                FieldProblem("name", "REQUIRED", "Name the list (at most $MAX_NAME characters).").takeIf {
                    name.trim().length !in
                        1..MAX_NAME
                },
                FieldProblem("members", "TOO_MANY", "At most $MAX_MEMBERS members.").takeIf { members.size > MAX_MEMBERS },
            ) +
                members.mapIndexedNotNull { i, m ->
                    val known = if (m.kind == MemberKind.USER) people[m.id]?.status == UserStatus.ACTIVE else m.id in positions
                    FieldProblem("members[$i].id", "UNKNOWN", "Choose an active person or a position.").takeIf { !known }
                }
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
    }

    private fun actor(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "lists are kept by signed-in users" }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val OBJECT_TYPE = "memo.distribution_list"
        const val MAX_NAME = 200
        const val MAX_MEMBERS = 500
    }
}

data class DistributionListRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val members: List<ListMember> = emptyList(),
)

/** A member with its name: the person's, or the position's title and holder today. */
data class ListMemberView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MemberKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class DistributionListView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val members: List<ListMemberView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

@RestController
@RequestMapping("/api/v1/distribution-lists")
@Tag(name = "memo")
class DistributionListsController(
    private val lists: DistributionLists,
    private val users: UserSummaries,
    private val directory: OrgDirectory,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @SignedIn
    @Operation(operationId = "listDistributionLists", summary = "The active distribution lists, for picking copy recipients")
    fun all(): ResponseEntity<List<DistributionListView>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { lists.all().map(::view) })

    @GetMapping("/{id}")
    @SignedIn
    @Operation(operationId = "getDistributionList", summary = "One distribution list with its members")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<DistributionListView> {
        val list = transactions.readInTenant(TenantContext.current()) { view(lists.get(id)) }
        return ResponseEntity.ok().eTag(etagOf(list.version)).body(list)
    }

    @PostMapping
    @RequiresPermission(Permission.MEMO_DISTRIBUTION_MANAGE)
    @Operation(operationId = "createDistributionList", summary = "A new distribution list")
    fun create(
        @RequestBody body: DistributionListRequest,
    ): ResponseEntity<DistributionListView> {
        val list = transactions.inTenant(TenantContext.current()) { view(lists.create(body.name, body.members)) }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(list.version)).body(list)
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.MEMO_DISTRIBUTION_MANAGE)
    @Operation(operationId = "replaceDistributionList", summary = "Rename a list or change its members")
    fun replace(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: DistributionListRequest,
    ): ResponseEntity<DistributionListView> {
        val version = ifMatchVersion(ifMatch)
        val list = transactions.inTenant(TenantContext.current()) { view(lists.replace(id, version, body.name, body.members)) }
        return ResponseEntity.ok().eTag(etagOf(list.version)).body(list)
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.MEMO_DISTRIBUTION_MANAGE)
    @Operation(operationId = "deactivateDistributionList", summary = "Switch a list off (it is kept)")
    fun deactivate(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Unit> {
        val version = ifMatchVersion(ifMatch)
        transactions.inTenant(TenantContext.current()) { lists.deactivate(id, version) }
        return ResponseEntity.noContent().build()
    }

    private fun view(list: DistributionList): DistributionListView {
        val people = users.of(list.members.filter { it.kind == MemberKind.USER }.map { it.id })
        val positions = directory.positions(list.members.filter { it.kind == MemberKind.POSITION }.map { it.id })
        return DistributionListView(
            list.id,
            list.name,
            list.members.map {
                val name = if (it.kind == MemberKind.USER) people[it.id]?.displayName else positions[it.id]?.title
                ListMemberView(it.kind, it.id, name.orEmpty())
            },
            list.version,
        )
    }
}
