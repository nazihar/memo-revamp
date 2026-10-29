package com.memo.identity.mfa

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserId
import com.memo.jooq.tables.references.IDENTITY_RECOVERY_CODE
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Recovery codes as Argon2id hashes, each usable once (ADR-0024). Inside a tenant transaction. */
@Component
class RecoveryCodeStore(
    private val tenantDsl: TenantDsl,
    private val encoder: PasswordEncoder,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** Replaces all codes of the user; returns the new ones for display (shown once). */
    fun replace(id: UserId): List<String> {
        val codes = RecoveryCodes.generate()
        val now = now()
        deleteAll(id)
        codes.forEach { code ->
            tenantDsl
                .current()
                .insertInto(IDENTITY_RECOVERY_CODE)
                .set(IDENTITY_RECOVERY_CODE.ID, ids.next())
                .set(IDENTITY_RECOVERY_CODE.TENANT_ID, tenantDsl.tenant().value)
                .set(IDENTITY_RECOVERY_CODE.USER_ID, id.value)
                .set(IDENTITY_RECOVERY_CODE.CODE_HASH, encoder.encode(code))
                .set(IDENTITY_RECOVERY_CODE.CREATED_AT, now)
                .execute()
        }
        return codes.map { RecoveryCodes.display(it) }
    }

    /** Uses an unused code matching [input]; guarded, so a code works once even under concurrent requests. */
    fun use(
        id: UserId,
        input: String,
    ): Boolean {
        val code = RecoveryCodes.normalise(input)
        val match =
            tenantDsl
                .current()
                .selectFrom(IDENTITY_RECOVERY_CODE)
                .where(IDENTITY_RECOVERY_CODE.USER_ID.eq(id.value))
                .and(IDENTITY_RECOVERY_CODE.USED_AT.isNull)
                .fetch()
                .firstOrNull { encoder.matches(code, it.codeHash) }
        val used =
            match != null &&
                tenantDsl
                    .current()
                    .update(IDENTITY_RECOVERY_CODE)
                    .set(IDENTITY_RECOVERY_CODE.USED_AT, now())
                    .where(IDENTITY_RECOVERY_CODE.ID.eq(match.id))
                    .and(IDENTITY_RECOVERY_CODE.USED_AT.isNull)
                    .execute() == 1
        if (used) audit.record("identity.user.recovery_code_used", "identity.user", id.toString())
        return used
    }

    fun deleteAll(id: UserId) {
        tenantDsl
            .current()
            .deleteFrom(IDENTITY_RECOVERY_CODE)
            .where(IDENTITY_RECOVERY_CODE.USER_ID.eq(id.value))
            .execute()
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
