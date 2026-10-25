package com.memo.identity.mfa

import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserId
import com.memo.jooq.tables.references.IDENTITY_MFA
import org.springframework.stereotype.Component

/** TOTP enrolment state (ADR-0024); enrolment and verification follow in T5. */
@Component
class MfaEnrolments(
    private val tenantDsl: TenantDsl,
) {
    fun isEnrolled(id: UserId): Boolean =
        tenantDsl.current().fetchExists(IDENTITY_MFA, IDENTITY_MFA.USER_ID.eq(id.value).and(IDENTITY_MFA.CONFIRMED.isTrue))
}
