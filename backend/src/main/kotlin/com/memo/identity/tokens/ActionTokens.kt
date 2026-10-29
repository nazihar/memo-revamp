package com.memo.identity.tokens

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserId
import com.memo.jooq.tables.references.IDENTITY_ACTION_TOKEN
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/** What a one-time link may do, and how long it lives (design E03 §6). The lifetimes below are the configuration. */
@Suppress("MagicNumber")
enum class TokenPurpose(
    val lifetime: Duration,
) {
    INVITATION(Duration.ofDays(7)),
    PASSWORD_RESET(Duration.ofMinutes(30)),
}

/**
 * One-time tokens for emailed links: 256 random bits, URL-safe; only their SHA-256 is stored. A token works once, for
 * one purpose, until it expires; issuing a new one for the same user and purpose retires the old ones. Runs inside a
 * tenant transaction (the link's host names the tenant).
 */
@Component
class ActionTokens(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    fun issue(
        user: UserId,
        purpose: TokenPurpose,
    ): String {
        retire(user, purpose)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also { random.nextBytes(it) })
        val now = now()
        tenantDsl
            .current()
            .insertInto(IDENTITY_ACTION_TOKEN)
            .set(IDENTITY_ACTION_TOKEN.ID, ids.next())
            .set(IDENTITY_ACTION_TOKEN.TENANT_ID, tenantDsl.tenant().value)
            .set(IDENTITY_ACTION_TOKEN.USER_ID, user.value)
            .set(IDENTITY_ACTION_TOKEN.PURPOSE, purpose.name)
            .set(IDENTITY_ACTION_TOKEN.TOKEN_HASH, hash(token))
            .set(IDENTITY_ACTION_TOKEN.EXPIRES_AT, now.plus(purpose.lifetime))
            .set(IDENTITY_ACTION_TOKEN.CREATED_AT, now)
            .execute()
        return token
    }

    /** The user a valid token belongs to, without using it up (to show an invitation). */
    fun peek(
        token: String,
        purpose: TokenPurpose,
    ): UserId? =
        tenantDsl
            .current()
            .select(IDENTITY_ACTION_TOKEN.USER_ID)
            .from(IDENTITY_ACTION_TOKEN)
            .where(valid(token, purpose))
            .fetchOne(IDENTITY_ACTION_TOKEN.USER_ID)
            ?.let { UserId(it) }

    /** Uses the token up and returns its user; null when it is unknown, used, expired or for another purpose. */
    fun consume(
        token: String,
        purpose: TokenPurpose,
    ): UserId? =
        tenantDsl
            .current()
            .update(IDENTITY_ACTION_TOKEN)
            .set(IDENTITY_ACTION_TOKEN.USED_AT, now())
            .where(valid(token, purpose))
            .returning(IDENTITY_ACTION_TOKEN.USER_ID)
            .fetchOne()
            ?.get(IDENTITY_ACTION_TOKEN.USER_ID)
            ?.let { UserId(it) }

    /** Retires every open token of [user] for [purpose] (a new link replaces the old one; deactivation ends them all). */
    fun retire(
        user: UserId,
        purpose: TokenPurpose,
    ) {
        tenantDsl
            .current()
            .update(IDENTITY_ACTION_TOKEN)
            .set(IDENTITY_ACTION_TOKEN.USED_AT, now())
            .where(IDENTITY_ACTION_TOKEN.USER_ID.eq(user.value))
            .and(IDENTITY_ACTION_TOKEN.PURPOSE.eq(purpose.name))
            .and(IDENTITY_ACTION_TOKEN.USED_AT.isNull)
            .execute()
    }

    private fun valid(
        token: String,
        purpose: TokenPurpose,
    ) = IDENTITY_ACTION_TOKEN.TOKEN_HASH
        .eq(hash(token))
        .and(IDENTITY_ACTION_TOKEN.PURPOSE.eq(purpose.name))
        .and(IDENTITY_ACTION_TOKEN.USED_AT.isNull)
        .and(IDENTITY_ACTION_TOKEN.EXPIRES_AT.gt(now()))

    private fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
