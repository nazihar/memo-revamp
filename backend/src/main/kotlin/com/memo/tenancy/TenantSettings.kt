package com.memo.tenancy

import com.memo.audit.AuditLog
import com.memo.common.cache.CacheInvalidation
import com.memo.common.cache.TenantCache
import com.memo.common.cache.TenantCaches
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.TenancySettingRecord
import com.memo.jooq.tables.references.TENANCY_SETTING
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional

data class TenantSetting(
    val key: String,
    val value: String,
    val version: Long,
)

/**
 * Per-tenant key/value configuration (the reference tenant table of Phase 0). Every method must run inside
 * `TenantTransactions.inTenant { }`; outside it, [TenantDsl.current] fails closed.
 */
@Service
class TenantSettings(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
    private val audit: AuditLog,
    private val invalidation: CacheInvalidation,
    caches: TenantCaches,
) {
    private val cache: TenantCache<String, Optional<TenantSetting>> = caches.register(CACHE)

    /** Cached read (ADR-0020); a transaction that changed settings reads its own writes. */
    fun get(key: String): TenantSetting? = cache.get(key) { Optional.ofNullable(load(key)) }.orElse(null)

    private fun load(key: String): TenantSetting? =
        tenantDsl
            .current()
            .selectFrom(TENANCY_SETTING)
            .where(TENANCY_SETTING.KEY.eq(key))
            .fetchOne()
            ?.toSetting()

    fun list(): List<TenantSetting> =
        tenantDsl
            .current()
            .selectFrom(TENANCY_SETTING)
            .orderBy(TENANCY_SETTING.KEY)
            .fetch()
            .map { it.toSetting() }

    /** Insert or overwrite (no version check); bumps the version. */
    fun put(
        key: String,
        value: String,
    ): TenantSetting {
        require(KEY_PATTERN.matches(key)) { "invalid setting key" }
        val before = load(key)?.value
        val now = now()
        return tenantDsl
            .current()
            .insertInto(TENANCY_SETTING)
            .set(TENANCY_SETTING.ID, ids.next())
            .set(TENANCY_SETTING.TENANT_ID, tenantDsl.tenant().value)
            .set(TENANCY_SETTING.KEY, key)
            .set(TENANCY_SETTING.VALUE, value)
            .set(TENANCY_SETTING.CREATED_AT, now)
            .set(TENANCY_SETTING.UPDATED_AT, now)
            .onConflict(TENANCY_SETTING.TENANT_ID, TENANCY_SETTING.KEY)
            .doUpdate()
            .set(TENANCY_SETTING.VALUE, value)
            .set(TENANCY_SETTING.VERSION, TENANCY_SETTING.VERSION.plus(1))
            .set(TENANCY_SETTING.UPDATED_AT, now)
            .returning()
            .fetchSingle()
            .toSetting()
            .also { changed(key, before, value) }
    }

    /** Guarded update (AGENTS.md §4): applies only if the stored version is [expectedVersion]. */
    fun update(
        key: String,
        value: String,
        expectedVersion: Long,
    ): TenantSetting {
        val before = load(key)?.value
        return tenantDsl
            .current()
            .update(TENANCY_SETTING)
            .set(TENANCY_SETTING.VALUE, value)
            .set(TENANCY_SETTING.VERSION, TENANCY_SETTING.VERSION.plus(1))
            .set(TENANCY_SETTING.UPDATED_AT, now())
            .where(TENANCY_SETTING.KEY.eq(key))
            .and(TENANCY_SETTING.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()
            ?.toSetting()
            ?.also { changed(key, before, value) }
            ?: throw ConcurrentModificationException("tenant setting '$key'", expectedVersion)
    }

    fun delete(key: String): Boolean {
        val before = load(key)?.value
        return tenantDsl
            .current()
            .deleteFrom(TENANCY_SETTING)
            .where(TENANCY_SETTING.KEY.eq(key))
            .execute()
            .let { deleted -> (deleted > 0).also { if (it) changed(key, before, null) } }
    }

    /** Audit entry in this transaction (settings are non-secret by contract) and the outbox event for listeners. */
    private fun changed(
        key: String,
        before: String?,
        after: String?,
    ) {
        val action =
            when {
                before == null -> "tenant.setting.created"
                after == null -> "tenant.setting.deleted"
                else -> "tenant.setting.changed"
            }
        audit.record(action, "tenant.setting", key, before?.let { mapOf("value" to it) }, after?.let { mapOf("value" to it) })
        invalidation.invalidate(CACHE, tenantDsl.tenant(), key)
        events.publishEvent(TenantSettingChanged(tenantDsl.tenant().value, key))
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun TenancySettingRecord.toSetting() = TenantSetting(key = key, value = value, version = version!!)

    companion object {
        const val CACHE = "tenancy.settings"
        private val KEY_PATTERN = Regex("^[a-z][a-z0-9.]{1,99}$")
    }
}
