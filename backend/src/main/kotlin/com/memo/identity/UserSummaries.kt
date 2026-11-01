package com.memo.identity

import com.memo.common.events.TenantScopedEvent
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IDENTITY_USER
import org.springframework.stereotype.Component
import java.util.UUID

/** What other modules show about a user (org holders, people picker). */
data class UserSummary(
    val id: UUID,
    val displayName: String,
    val email: String,
    val status: UserStatus,
)

/** A user was deactivated (ids only, ADR-0017); published inside the deactivating transaction. */
data class UserDeactivated(
    override val tenantId: UUID,
    val userId: UUID,
) : TenantScopedEvent

/** Batch reads of user summaries for other modules, one query per call. Inside a tenant transaction. */
@Component
class UserSummaries(
    private val tenantDsl: TenantDsl,
) {
    fun of(ids: Collection<UUID>): Map<UUID, UserSummary> {
        if (ids.isEmpty()) return emptyMap()
        return tenantDsl
            .current()
            .select(IDENTITY_USER.ID, IDENTITY_USER.DISPLAY_NAME, IDENTITY_USER.EMAIL, IDENTITY_USER.STATUS)
            .from(IDENTITY_USER)
            .where(IDENTITY_USER.ID.`in`(ids))
            .fetch { UserSummary(it.value1()!!, it.value2()!!, it.value3()!!, UserStatus.valueOf(it.value4()!!)) }
            .associateBy { it.id }
    }

    fun of(id: UUID): UserSummary? = of(listOf(id))[id]
}
