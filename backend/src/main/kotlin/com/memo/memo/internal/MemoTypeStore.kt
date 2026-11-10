package com.memo.memo.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MemoTypeRecord
import com.memo.jooq.tables.references.MEMO_TYPE
import io.swagger.v3.oas.annotations.media.Schema
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A memo type (design E01 §2). E01 routes every type through the initiator-defined sequential approval. */
data class MemoType(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** Built-in types only (`general`). */
    val key: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** Null: the tenant's lowest classification level. */
    val defaultClassificationId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val routePolicy: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val definitionKey: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stepUpOnApprove: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val active: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/** The fields an administrator edits. */
data class MemoTypeFields(
    val name: String,
    val defaultClassificationId: UUID?,
    val stepUpOnApprove: Boolean,
    val active: Boolean,
)

/** Memo types (design E01 §2). Every method runs inside a tenant transaction. */
@Repository
class MemoTypeStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
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
            routePolicy = routePolicy!!,
            definitionKey = definitionKey!!,
            stepUpOnApprove = stepUpOnApprove!!,
            active = active!!,
            version = version!!,
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val GENERAL = "general"
        const val GENERAL_NAME = "General memo"
    }
}
