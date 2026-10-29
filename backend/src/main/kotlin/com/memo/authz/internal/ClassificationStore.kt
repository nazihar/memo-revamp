package com.memo.authz.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.AUTHZ_CLASSIFICATION_LEVEL
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

/** Classification levels (ADR-0027). Every method runs inside a tenant transaction. */
@Repository
class ClassificationStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Seeds the default levels into a tenant that has none (idempotent, safe on several nodes at once). */
    fun ensureSeeded() {
        if (tenantDsl.current().fetchExists(AUTHZ_CLASSIFICATION_LEVEL)) return
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        DEFAULT_LEVELS.forEachIndexed { rank, name ->
            tenantDsl
                .current()
                .insertInto(AUTHZ_CLASSIFICATION_LEVEL)
                .set(AUTHZ_CLASSIFICATION_LEVEL.ID, ids.next())
                .set(AUTHZ_CLASSIFICATION_LEVEL.TENANT_ID, tenantDsl.tenant().value)
                .set(AUTHZ_CLASSIFICATION_LEVEL.RANK, rank)
                .set(AUTHZ_CLASSIFICATION_LEVEL.NAME, name)
                .set(AUTHZ_CLASSIFICATION_LEVEL.CREATED_AT, now)
                .set(AUTHZ_CLASSIFICATION_LEVEL.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute()
        }
    }

    fun list(): List<ClassificationLevel> =
        tenantDsl
            .current()
            .selectFrom(AUTHZ_CLASSIFICATION_LEVEL)
            .orderBy(AUTHZ_CLASSIFICATION_LEVEL.RANK)
            .fetch { ClassificationLevel(it.id, it.rank, it.name, it.description, it.version!!) }

    fun byRank(rank: Int): ClassificationLevel? = list().firstOrNull { it.rank == rank }

    companion object {
        /** Seeded per tenant (overview E04); tenant admins rename, add or reorder them (E04 T5). */
        val DEFAULT_LEVELS = listOf("Public", "Internal", "Confidential", "Strictly Confidential")
    }
}
