package com.memo.identity

import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantDsl
import com.memo.identity.mfa.Totp
import com.memo.identity.mfa.mfaAad
import com.memo.jooq.tables.references.IDENTITY_MFA
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Demo and test seeding of TOTP (ADR-0024), without a code. Refused by its callers under the prod profile. */
@Component
class MfaSeeding(
    private val tenantDsl: TenantDsl,
    private val keys: DataKeys,
    private val clock: Clock,
) {
    /** Enrols the user with [base32Secret] unless that is already their secret. Inside a tenant transaction. */
    fun ensureTotp(
        id: UserId,
        base32Secret: String,
    ) {
        val secret = Totp.decodeBase32(base32Secret)
        require(secret.size >= Totp.SECRET_BYTES) { "the TOTP secret must be at least ${Totp.SECRET_BYTES} bytes" }
        val aad = mfaAad(tenantDsl.tenant(), id)
        val current =
            tenantDsl
                .current()
                .select(IDENTITY_MFA.SECRET_ENC)
                .from(IDENTITY_MFA)
                .where(IDENTITY_MFA.USER_ID.eq(id.value))
                .fetchOne(IDENTITY_MFA.SECRET_ENC)
        if (current == null || !keys.decrypt(current, aad).contentEquals(secret)) store(id, keys.encrypt(secret, aad))
    }

    private fun store(
        id: UserId,
        sealed: ByteArray,
    ) {
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        tenantDsl
            .current()
            .insertInto(IDENTITY_MFA)
            .set(IDENTITY_MFA.USER_ID, id.value)
            .set(IDENTITY_MFA.TENANT_ID, tenantDsl.tenant().value)
            .set(IDENTITY_MFA.SECRET_ENC, sealed)
            .set(IDENTITY_MFA.ENROLLED_AT, now)
            .set(IDENTITY_MFA.CREATED_AT, now)
            .set(IDENTITY_MFA.UPDATED_AT, now)
            .onConflict(IDENTITY_MFA.USER_ID)
            .doUpdate()
            .set(IDENTITY_MFA.SECRET_ENC, sealed)
            .setNull(IDENTITY_MFA.PENDING_SECRET_ENC)
            .set(IDENTITY_MFA.UPDATED_AT, now)
            .execute()
    }
}
