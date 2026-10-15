package com.memo.tenancy

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.TenancySettingRecord
import com.memo.jooq.tables.references.TENANCY_SETTING
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
) {
    fun get(key: String): TenantSetting? =
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
    }

    /** Guarded update (AGENTS.md §4): applies only if the stored version is [expectedVersion]. */
    fun update(
        key: String,
        value: String,
        expectedVersion: Long,
    ): TenantSetting =
        tenantDsl
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
            ?: throw ConcurrentModificationException("tenant setting '$key'", expectedVersion)

    fun delete(key: String): Boolean =
        tenantDsl
            .current()
            .deleteFrom(TENANCY_SETTING)
            .where(TENANCY_SETTING.KEY.eq(key))
            .execute() > 0

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun TenancySettingRecord.toSetting() = TenantSetting(key = key, value = value, version = version!!)

    private companion object {
        val KEY_PATTERN = Regex("^[a-z][a-z0-9.]{1,99}$")
    }
}
