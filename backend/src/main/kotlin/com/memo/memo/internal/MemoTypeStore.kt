package com.memo.memo.internal

import com.fasterxml.jackson.annotation.JsonIgnore
import com.memo.common.id.IdGenerator
import com.memo.common.review.ReviewState
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
    /** The short code used in memo numbers (ADR-0044). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    /** The published version (ADR-0043); null while the type has only a draft. */
    val publishedVersion: Int?,
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
    /** Null: derived from the name when the type is created, unchanged on an edit. */
    val code: String? = null,
) {
    fun settings(code: String): MemoTypeSettings =
        MemoTypeSettings(name, code, defaultClassificationId, definitionKey, routePolicy, allowedApprovers, doaCheck, stepUpOnApprove)
}

/**
 * Memo types (design E01 §2). Since Phase 3 E01 the settings live in versions (ADR-0043); the type row keeps the identity
 * and a copy of its published version's settings, which every reader uses. Every method runs inside a tenant transaction.
 */
@Repository
class MemoTypeStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val json: JsonMapper,
    private val versions: MemoTypeVersionStore,
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

    /** Whether a type other than [except] has [code]. */
    fun codeTaken(
        code: String,
        except: UUID? = null,
    ): Boolean =
        tenantDsl.current().fetchExists(
            MEMO_TYPE,
            MEMO_TYPE.CODE.eq(code).and(except?.let { MEMO_TYPE.ID.ne(it) } ?: DSL.noCondition()),
        )

    /** A code for a type called [name] that no other type has: its initials, numbered if needed (as the V6 backfill). */
    fun freeCode(name: String): String {
        val base = MemoTypeCodes.initials(name)
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { !codeTaken(it) }
    }

    /**
     * Inserts a type with [fields], published at once as version 1, created by [createdBy] (null: the system). Used for
     * the built-in type and legacy direct creation; reviewed creation starts from a draft instead (E01 T6).
     */
    fun insert(
        fields: MemoTypeFields,
        key: String? = null,
        createdBy: UUID? = null,
    ): UUID {
        val id = ids.next()
        val now = now()
        val code = fields.code ?: freeCode(fields.name)
        val inserted =
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
                .set(MEMO_TYPE.CODE, code)
                .set(MEMO_TYPE.PUBLISHED_VERSION, 1)
                .set(MEMO_TYPE.CREATED_AT, now)
                .set(MEMO_TYPE.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute()
        // Another node created the built-in type meanwhile (or the name is taken): no row, no version.
        if (inserted == 1) versions.insert(id, ReviewState.PUBLISHED, fields.settings(code), createdBy)
        return id
    }

    /**
     * Copies [fields] onto the type row, with its published version [publishedVersion]; guarded on [expectedVersion]
     * when given. False when the row changed meanwhile.
     */
    fun update(
        id: UUID,
        expectedVersion: Long?,
        fields: MemoTypeFields,
        publishedVersion: Int,
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
            .apply { fields.code?.let { set(MEMO_TYPE.CODE, it) } }
            .set(MEMO_TYPE.PUBLISHED_VERSION, publishedVersion)
            .set(MEMO_TYPE.VERSION, MEMO_TYPE.VERSION.plus(1))
            .set(MEMO_TYPE.UPDATED_AT, now())
            .where(MEMO_TYPE.ID.eq(id), expectedVersion?.let { MEMO_TYPE.VERSION.eq(it) } ?: DSL.noCondition())
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
            code = code ?: "",
            publishedVersion = publishedVersion,
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

/** Short memo-type codes (ADR-0044): upper-case letters and digits, at most ten characters. */
object MemoTypeCodes {
    val PATTERN = Regex("^[A-Z0-9][A-Z0-9-]{0,9}$")

    /** The initials of [name]'s words, at most four; "T" when it has no letters or digits. */
    fun initials(name: String): String =
        name
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { it.take(1) }
            .uppercase()
            .take(MAX_INITIALS)
            .ifEmpty { "T" }

    private const val MAX_INITIALS = 4
}
