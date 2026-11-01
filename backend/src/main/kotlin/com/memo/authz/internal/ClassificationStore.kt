package com.memo.authz.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.AUTHZ_CLASSIFICATION_LEVEL
import com.memo.jooq.tables.references.AUTHZ_CLEARANCE
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ClassificationLevel(
    val id: UUID,
    val rank: Int,
    val name: String,
    val description: String?,
    val version: Long,
)

/** Classification levels and clearances (ADR-0027). Every method runs inside a tenant transaction. */
@Repository
class ClassificationStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Seeds the default levels into a tenant that has none (idempotent, safe on several nodes at once). */
    fun ensureSeeded() {
        if (tenantDsl.current().fetchExists(AUTHZ_CLASSIFICATION_LEVEL)) return
        DEFAULT_LEVELS.forEachIndexed { rank, name -> insert(rank, name, null) }
    }

    fun list(): List<ClassificationLevel> =
        tenantDsl
            .current()
            .selectFrom(AUTHZ_CLASSIFICATION_LEVEL)
            .orderBy(AUTHZ_CLASSIFICATION_LEVEL.RANK)
            .fetch { ClassificationLevel(it.id, it.rank, it.name, it.description, it.version!!) }

    fun byRank(rank: Int): ClassificationLevel? = list().firstOrNull { it.rank == rank }

    fun insert(
        rank: Int,
        name: String,
        description: String?,
    ): UUID {
        val id = ids.next()
        val now = now()
        tenantDsl
            .current()
            .insertInto(AUTHZ_CLASSIFICATION_LEVEL)
            .set(AUTHZ_CLASSIFICATION_LEVEL.ID, id)
            .set(AUTHZ_CLASSIFICATION_LEVEL.TENANT_ID, tenantDsl.tenant().value)
            .set(AUTHZ_CLASSIFICATION_LEVEL.RANK, rank)
            .set(AUTHZ_CLASSIFICATION_LEVEL.NAME, name)
            .set(AUTHZ_CLASSIFICATION_LEVEL.DESCRIPTION, description)
            .set(AUTHZ_CLASSIFICATION_LEVEL.CREATED_AT, now)
            .set(AUTHZ_CLASSIFICATION_LEVEL.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
        return id
    }

    /** Guarded rename on [expectedVersion]. */
    fun rename(
        id: UUID,
        expectedVersion: Long,
        name: String,
        description: String?,
    ): Boolean =
        tenantDsl
            .current()
            .update(AUTHZ_CLASSIFICATION_LEVEL)
            .set(AUTHZ_CLASSIFICATION_LEVEL.NAME, name)
            .set(AUTHZ_CLASSIFICATION_LEVEL.DESCRIPTION, description)
            .set(AUTHZ_CLASSIFICATION_LEVEL.VERSION, AUTHZ_CLASSIFICATION_LEVEL.VERSION.plus(1))
            .set(AUTHZ_CLASSIFICATION_LEVEL.UPDATED_AT, now())
            .where(AUTHZ_CLASSIFICATION_LEVEL.ID.eq(id), AUTHZ_CLASSIFICATION_LEVEL.VERSION.eq(expectedVersion))
            .execute() == 1

    /** Gives the levels ranks 0..n-1 in [ordered] order, through temporary ranks so the unique key never collides. */
    fun rerank(ordered: List<UUID>) {
        val dsl = tenantDsl.current()
        ordered.forEachIndexed { rank, id ->
            dsl
                .update(AUTHZ_CLASSIFICATION_LEVEL)
                .set(AUTHZ_CLASSIFICATION_LEVEL.RANK, TEMPORARY_OFFSET + rank)
                .where(AUTHZ_CLASSIFICATION_LEVEL.ID.eq(id))
                .execute()
        }
        ordered.forEachIndexed { rank, id ->
            dsl
                .update(AUTHZ_CLASSIFICATION_LEVEL)
                .set(AUTHZ_CLASSIFICATION_LEVEL.RANK, rank)
                .set(AUTHZ_CLASSIFICATION_LEVEL.VERSION, AUTHZ_CLASSIFICATION_LEVEL.VERSION.plus(1))
                .set(AUTHZ_CLASSIFICATION_LEVEL.UPDATED_AT, now())
                .where(AUTHZ_CLASSIFICATION_LEVEL.ID.eq(id))
                .execute()
        }
    }

    fun delete(id: UUID): Boolean =
        tenantDsl
            .current()
            .deleteFrom(AUTHZ_CLASSIFICATION_LEVEL)
            .where(AUTHZ_CLASSIFICATION_LEVEL.ID.eq(id))
            .execute() == 1

    /** Users cleared to [level] (a level in use cannot be removed). */
    fun clearedTo(level: UUID): Int = tenantDsl.current().fetchCount(AUTHZ_CLEARANCE, AUTHZ_CLEARANCE.LEVEL_ID.eq(level))

    /** The user's clearance level id, or null for the lowest level. */
    fun clearanceOf(user: UUID): UUID? =
        tenantDsl
            .current()
            .select(AUTHZ_CLEARANCE.LEVEL_ID)
            .from(AUTHZ_CLEARANCE)
            .where(AUTHZ_CLEARANCE.USER_ID.eq(user))
            .fetchOne(AUTHZ_CLEARANCE.LEVEL_ID)

    fun setClearance(
        user: UUID,
        level: UUID,
    ) {
        val now = now()
        tenantDsl
            .current()
            .insertInto(AUTHZ_CLEARANCE)
            .set(AUTHZ_CLEARANCE.TENANT_ID, tenantDsl.tenant().value)
            .set(AUTHZ_CLEARANCE.USER_ID, user)
            .set(AUTHZ_CLEARANCE.LEVEL_ID, level)
            .set(AUTHZ_CLEARANCE.UPDATED_AT, now)
            .onConflict(AUTHZ_CLEARANCE.TENANT_ID, AUTHZ_CLEARANCE.USER_ID)
            .doUpdate()
            .set(AUTHZ_CLEARANCE.LEVEL_ID, level)
            .set(AUTHZ_CLEARANCE.VERSION, AUTHZ_CLEARANCE.VERSION.plus(1))
            .set(AUTHZ_CLEARANCE.UPDATED_AT, now)
            .execute()
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        /** Seeded per tenant (overview E04); tenant admins rename, add or reorder them. */
        val DEFAULT_LEVELS = listOf("Public", "Internal", "Confidential", "Strictly Confidential")
        private const val TEMPORARY_OFFSET = 500
    }
}
