package com.memo.system

import com.memo.common.tenant.TenantDsl
import com.memo.common.web.IdempotencyKeyReusedException
import com.memo.jooq.tables.references.SYSTEM_IDEMPOTENCY_KEY
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `Idempotency-Key` support for creating requests (AGENTS.md §4). Call [once] inside the business transaction: the key
 * row is inserted first, so a concurrent repeat waits for the first commit and then reads its result. If the work
 * fails, the transaction rolls back the key too and the client may retry with the same key.
 */
@Component
class Idempotency(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /**
     * Runs [work] once for ([operation], [key]) and returns its result; a repeat returns the stored result. A repeat
     * with a different [request] body throws [IdempotencyKeyReusedException] (422).
     */
    fun once(
        operation: String,
        key: String,
        request: String,
        work: () -> String,
    ): String {
        require(KEY.matches(key)) { "invalid Idempotency-Key" }
        val hash = MessageDigest.getInstance("SHA-256").digest(request.toByteArray(Charsets.UTF_8))
        val dsl = tenantDsl.current()
        val claimed =
            dsl
                .insertInto(SYSTEM_IDEMPOTENCY_KEY)
                .set(SYSTEM_IDEMPOTENCY_KEY.TENANT_ID, tenantDsl.tenant().value)
                .set(SYSTEM_IDEMPOTENCY_KEY.OPERATION, operation)
                .set(SYSTEM_IDEMPOTENCY_KEY.KEY, key)
                .set(SYSTEM_IDEMPOTENCY_KEY.REQUEST_HASH, hash)
                .set(SYSTEM_IDEMPOTENCY_KEY.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute() == 1
        if (claimed) {
            val result = work()
            dsl
                .update(SYSTEM_IDEMPOTENCY_KEY)
                .set(SYSTEM_IDEMPOTENCY_KEY.RESULT, result)
                .where(SYSTEM_IDEMPOTENCY_KEY.OPERATION.eq(operation))
                .and(SYSTEM_IDEMPOTENCY_KEY.KEY.eq(key))
                .execute()
            return result
        }
        val stored =
            dsl
                .selectFrom(SYSTEM_IDEMPOTENCY_KEY)
                .where(SYSTEM_IDEMPOTENCY_KEY.OPERATION.eq(operation))
                .and(SYSTEM_IDEMPOTENCY_KEY.KEY.eq(key))
                .fetchSingle()
        if (!MessageDigest.isEqual(stored.requestHash, hash) || stored.result == null) throw IdempotencyKeyReusedException()
        return stored.result!!
    }

    private companion object {
        val KEY = Regex("^[A-Za-z0-9_.:-]{1,200}$")
    }
}
