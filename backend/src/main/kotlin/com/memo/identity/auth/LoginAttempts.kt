package com.memo.identity.auth

import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserId
import com.memo.jooq.tables.references.IDENTITY_USER
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Sign-in bookkeeping on the account row (ADR-0023). Runs inside the sign-in's tenant transaction. */
@Component
class LoginAttempts(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** A successful sign-in: stamps `last_login_at` and clears the failure counter. */
    fun succeeded(id: UserId) {
        tenantDsl
            .current()
            .update(IDENTITY_USER)
            .set(IDENTITY_USER.LAST_LOGIN_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .set(IDENTITY_USER.FAILED_LOGINS, 0)
            .where(IDENTITY_USER.ID.eq(id.value))
            .execute()
    }
}
