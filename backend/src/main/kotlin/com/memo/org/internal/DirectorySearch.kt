package com.memo.org.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.references.ORG_POSITION
import com.memo.jooq.tables.references.ORG_UNIT
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.impl.DSL
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID

@Schema(name = "DirectoryPosition")
data class DirectoryPositionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unitId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unitName: String,
    @field:Schema(description = "Holder today; absent when vacant") val holder: PersonResponse? = null,
)

@Schema(name = "DirectoryUser")
data class DirectoryUserResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: PersonResponse,
    @field:Schema(description = "Primary position today") val position: DirectoryPositionSummary? = null,
)

@Schema(name = "DirectoryPositionSummary")
data class DirectoryPositionSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unitName: String,
)

@Schema(name = "DirectorySearchResult")
data class DirectorySearchResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val users: List<DirectoryUserResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val positions: List<DirectoryPositionResponse>,
)

/**
 * The people picker (design E04 §6, research R14): active and invited users and open positions whose name or title has
 * a word starting with the query, with today's holder and position. At most [MAX_RESULTS] of each. Inside a tenant
 * transaction.
 */
@Component
class DirectorySearch(
    private val tenantDsl: TenantDsl,
    private val users: UserSummaries,
    private val assignments: AssignmentStore,
    private val positions: PositionStore,
    private val calendar: OrgCalendar,
) {
    fun search(
        query: String,
        kinds: Set<String>,
        limit: Int,
    ): DirectorySearchResponse {
        val term = normalise(query)
        val size = limit.coerceIn(1, MAX_RESULTS)
        val today = calendar.today()
        val foundUsers = if ("user" in kinds) users.search(term, size) else emptyList()
        val foundPositions = if ("position" in kinds) positionsMatching(term, size, today) else emptyList()

        val userAssignments = assignments.heldBy(foundUsers.map { it.id }, today)
        val holders = assignments.holdersOn(foundPositions.map { it.first }, today)
        val people = users.of(holders.values.map { it.userId })
        val positionsOfUsers = positions.byIds(userAssignments.values.map { it.positionId })
        val unitNames = unitNames((positionsOfUsers.values.map { it.unitId }).toSet())

        return DirectorySearchResponse(
            foundUsers.map { u ->
                val held = userAssignments[u.id]?.let { positionsOfUsers[it.positionId] }
                DirectoryUserResponse(
                    PersonResponse(u.id, u.displayName, u.email),
                    held?.let { DirectoryPositionSummary(it.id, it.title, unitNames[it.unitId].orEmpty()) },
                )
            },
            foundPositions.map { (id, title, unit) ->
                val holder = holders[id]?.let { people[it.userId] }
                DirectoryPositionResponse(
                    id,
                    title,
                    unit.first,
                    unit.second,
                    holder?.let { PersonResponse(it.id, it.displayName, it.email) },
                )
            },
        )
    }

    private fun positionsMatching(
        term: String,
        limit: Int,
        today: LocalDate,
    ): List<Triple<UUID, String, Pair<UUID, String>>> {
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val title = DSL.lower(ORG_POSITION.TITLE)
        return tenantDsl
            .current()
            .select(ORG_POSITION.ID, ORG_POSITION.TITLE, ORG_UNIT.ID, ORG_UNIT.NAME)
            .from(ORG_POSITION)
            .join(ORG_UNIT)
            .on(ORG_UNIT.TENANT_ID.eq(ORG_POSITION.TENANT_ID), ORG_UNIT.ID.eq(ORG_POSITION.UNIT_ID))
            .where(validOn(ORG_POSITION.VALID_FROM, ORG_POSITION.VALID_TO, today))
            .and(title.like("$escaped%", '\\').or(title.like("% $escaped%", '\\')))
            .orderBy(title, ORG_POSITION.ID)
            .limit(limit)
            .fetch { Triple(it.value1()!!, it.value2()!!, it.value3()!! to it.value4()!!) }
    }

    private fun unitNames(ids: Set<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .select(ORG_UNIT.ID, ORG_UNIT.NAME)
                .from(ORG_UNIT)
                .where(ORG_UNIT.ID.`in`(ids))
                .fetchMap({ it.value1()!! }, { it.value2()!! })
        }

    companion object {
        const val MAX_RESULTS = 20
        private const val TATWEEL = "ـ"

        /** Lower case, Unicode NFC, Arabic tatweel removed, inner spaces collapsed. */
        fun normalise(value: String): String =
            Normalizer
                .normalize(value, Normalizer.Form.NFC)
                .replace(TATWEEL, "")
                .trim()
                .replace(Regex("\\s+"), " ")
                .lowercase()
    }
}

/** People picker endpoint (design E04 §6): `directory.read`, 60 searches a minute per user. */
@RestController
@Tag(name = "directory")
class DirectoryController(
    private val search: DirectorySearch,
    private val limiter: RateLimiter,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/directory/search")
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(operationId = "searchDirectory", summary = "Find people and positions by the start of a word in their name or title")
    fun search(
        @Parameter(description = "At least 2 characters") @RequestParam q: String,
        @Parameter(description = "user, position, or both (default)") @RequestParam(required = false) kind: List<String>?,
        @RequestParam(required = false, defaultValue = "10") limit: Int,
    ): ResponseEntity<DirectorySearchResponse> {
        val user = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId ?: throw ForbiddenException(null)
        limiter.consume(RateLimit.DIRECTORY_SEARCH_PER_USER, "${TenantContext.current()}:$user")
        require(DirectorySearch.normalise(q).length >= MIN_QUERY) { "Type at least $MIN_QUERY characters." }
        val kinds = (kind ?: listOf("user", "position")).toSet()
        require(kinds.all { it == "user" || it == "position" }) { "kind is user or position." }
        val body = transactions.readInTenant(TenantContext.current()) { search.search(q, kinds, limit) }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    private companion object {
        const val MIN_QUERY = 2
    }
}
