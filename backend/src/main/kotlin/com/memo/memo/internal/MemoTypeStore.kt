package com.memo.memo.internal

import com.fasterxml.jackson.annotation.JsonIgnore
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MemoTypeRecord
import com.memo.jooq.tables.references.MEMO_TYPE
import com.memo.workflow.Workflows
import io.swagger.v3.oas.annotations.media.Schema
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Who chooses a memo's approvers (design E03 §12): the initiator, the definition alone, or the initiator within the definition. */
enum class RoutePolicy {
    /** The definition takes the initiator's route (and may add steps around it). */
    INITIATOR_DEFINED,

    /** The definition names every step; memos of the type have no route. */
    FIXED,

    /** The definition has fixed steps and takes the initiator's route too; the route builder is shown. */
    BOTH,

    ;

    /** Whether the definition has to have a `route` step. */
    val routed: Boolean get() = this != FIXED
}

/**
 * Who may be on a memo type's routes (design E04 §4.7): named people, the holders of positions, and the holders of roles.
 * Empty: anyone.
 */
data class AllowedApprovers(
    val people: List<UUID> = emptyList(),
    val positions: List<UUID> = emptyList(),
    val roles: List<UUID> = emptyList(),
) {
    @get:JsonIgnore
    val anyone: Boolean get() = people.isEmpty() && positions.isEmpty() && roles.isEmpty()
}

/** A memo type (design E01 §2, E03 §4.7): its workflow definition and who chooses the approvers. */
data class MemoType(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** Built-in types only (`general`). */
    val key: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** Null: the tenant's lowest classification level. */
    val defaultClassificationId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val routePolicy: RoutePolicy,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val definitionKey: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepUpOnApprove: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val active: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    /** Who may be on its routes (design E04 §4.7); empty lists: anyone. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val allowedApprovers: AllowedApprovers = AllowedApprovers(),
    /** Whether a route must satisfy the DoA at submit (design E04 §4.7). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val doaCheck: Boolean = false,
    /** For administrators: the names of the allowed people and the titles of the allowed positions, by id. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val allowedNames: Map<UUID, String> = emptyMap(),
)

/** The fields an administrator edits. */
data class MemoTypeFields(
    val name: String,
    val defaultClassificationId: UUID?,
    val stepUpOnApprove: Boolean,
    val active: Boolean,
    val definitionKey: String = Workflows.SEQUENTIAL_APPROVAL,
    val routePolicy: RoutePolicy = RoutePolicy.INITIATOR_DEFINED,
    val allowedApprovers: AllowedApprovers = AllowedApprovers(),
    val doaCheck: Boolean = false,
)

/** Memo types (design E01 §2). Every method runs inside a tenant transaction. */
@Repository
class MemoTypeStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val json: JsonMapper,
) {
    /** Every type, by name. */
    fun all(): List<MemoType> =
        tenantDsl
            .current()
            .selectFrom(MEMO_TYPE)
            .orderBy(DSL.lower(MEMO_TYPE.NAME), MEMO_TYPE.ID)
            .fetch { it.toType() }

    fun find(id: UUID): MemoType? =
        tenantDsl
            .current()
            .selectFrom(MEMO_TYPE)
            .where(MEMO_TYPE.ID.eq(id))
            .fetchOne()
            ?.toType()

    /** Whether a type other than [except] is called [name] (names are unique, ignoring case). */
    fun nameTaken(
        name: String,
        except: UUID? = null,
    ): Boolean =
        tenantDsl.current().fetchExists(
            MEMO_TYPE,
            DSL.lower(MEMO_TYPE.NAME).eq(name.lowercase()).and(except?.let { MEMO_TYPE.ID.ne(it) } ?: DSL.noCondition()),
        )

    fun insert(
        fields: MemoTypeFields,
        key: String? = null,
    ): UUID {
        val id = ids.next()
        val now = now()
        tenantDsl
            .current()
            .insertInto(MEMO_TYPE)
            .set(MEMO_TYPE.ID, id)
            .set(MEMO_TYPE.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_TYPE.KEY, key)
            .set(MEMO_TYPE.NAME, fields.name)
            .set(MEMO_TYPE.DEFAULT_CLASSIFICATION_ID, fields.defaultClassificationId)
            .set(MEMO_TYPE.STEP_UP_ON_APPROVE, fields.stepUpOnApprove)
            .set(MEMO_TYPE.ACTIVE, fields.active)
            .set(MEMO_TYPE.DEFINITION_KEY, fields.definitionKey)
            .set(MEMO_TYPE.ROUTE_POLICY, fields.routePolicy.name)
            .set(MEMO_TYPE.ALLOWED_APPROVERS, JSONB.valueOf(json.writeValueAsString(fields.allowedApprovers)))
            .set(MEMO_TYPE.DOA_CHECK, fields.doaCheck)
            .set(MEMO_TYPE.CREATED_AT, now)
            .set(MEMO_TYPE.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
        return id
    }

    /** Guarded on [expectedVersion]; false when the row changed meanwhile. */
    fun update(
        id: UUID,
        expectedVersion: Long,
        fields: MemoTypeFields,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_TYPE)
            .set(MEMO_TYPE.NAME, fields.name)
            .set(MEMO_TYPE.DEFAULT_CLASSIFICATION_ID, fields.defaultClassificationId)
            .set(MEMO_TYPE.STEP_UP_ON_APPROVE, fields.stepUpOnApprove)
            .set(MEMO_TYPE.ACTIVE, fields.active)
            .set(MEMO_TYPE.DEFINITION_KEY, fields.definitionKey)
            .set(MEMO_TYPE.ROUTE_POLICY, fields.routePolicy.name)
            .set(MEMO_TYPE.ALLOWED_APPROVERS, JSONB.valueOf(json.writeValueAsString(fields.allowedApprovers)))
            .set(MEMO_TYPE.DOA_CHECK, fields.doaCheck)
            .set(MEMO_TYPE.VERSION, MEMO_TYPE.VERSION.plus(1))
            .set(MEMO_TYPE.UPDATED_AT, now())
            .where(MEMO_TYPE.ID.eq(id), MEMO_TYPE.VERSION.eq(expectedVersion))
            .execute() == 1

    /** Creates the built-in "General memo" type unless the tenant has it (idempotent, safe on several nodes at once). */
    fun ensureGeneral() {
        if (tenantDsl.current().fetchExists(MEMO_TYPE, MEMO_TYPE.KEY.eq(GENERAL))) return
        // A tenant type may already use the name; the built-in one then gets a distinct name.
        val name = if (nameTaken(GENERAL_NAME)) "$GENERAL_NAME (built-in)" else GENERAL_NAME
        insert(MemoTypeFields(name, null, stepUpOnApprove = true, active = true), GENERAL)
    }

    private fun MemoTypeRecord.toType() =
        MemoType(
            id = id,
            key = key,
            name = name,
            defaultClassificationId = defaultClassificationId,
            routePolicy = RoutePolicy.valueOf(routePolicy!!),
            definitionKey = definitionKey!!,
            stepUpOnApprove = stepUpOnApprove!!,
            active = active!!,
            version = version!!,
            allowedApprovers = json.readValue(allowedApprovers!!.data(), AllowedApprovers::class.java),
            doaCheck = doaCheck == true,
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val GENERAL = "general"
        const val GENERAL_NAME = "General memo"
    }
}
