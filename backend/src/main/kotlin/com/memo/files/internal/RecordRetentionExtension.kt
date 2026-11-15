package com.memo.files.internal

import com.memo.audit.AuditLog
import com.memo.common.storage.ObjectStore
import com.memo.common.storage.Retention
import com.memo.common.storage.RetentionMode
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.files.RecordRetentions
import com.memo.jooq.tables.references.FILES_VERSION
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** [RecordRetentions] over object storage and `files_version` (files/V3 lets `retain_until` move later only). */
@Component
internal class RecordRetentionExtension(
    private val objects: ObjectStore,
    private val tenantDsl: TenantDsl,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
) : RecordRetentions {
    override fun extend(
        versionId: UUID,
        until: Instant,
    ): Boolean {
        val tenant = TenantContext.current()
        val row =
            transactions.readInTenant(tenant) {
                tenantDsl
                    .current()
                    .select(
                        FILES_VERSION.OBJECT_KEY,
                        FILES_VERSION.OBJECT_VERSION,
                        FILES_VERSION.RETAIN_UNTIL,
                        FILES_VERSION.RETENTION_MODE,
                    ).from(FILES_VERSION)
                    .where(FILES_VERSION.ID.eq(versionId))
                    .fetchOne()
            }
        // A record version has all four; anything else is not one, and is left alone.
        val lock =
            row?.let { r ->
                val key = r.value1() ?: return@let null
                val version = r.value2() ?: return@let null
                val current = r.value3()?.toInstant() ?: return@let null
                val mode = r.value4()?.let(RetentionMode::valueOf) ?: return@let null
                Lock(key, version, current, mode)
            }
        if (lock == null || !until.isAfter(lock.current)) return false

        // Storage first: a lock that cannot be extended leaves the row as it is, and the caller tries again.
        objects.extendRetention(lock.key, lock.version, Retention(lock.mode, until))
        transactions.inTenant(tenant) {
            tenantDsl
                .current()
                .update(FILES_VERSION)
                .set(FILES_VERSION.RETAIN_UNTIL, until.atOffset(ZoneOffset.UTC))
                .where(FILES_VERSION.ID.eq(versionId), FILES_VERSION.RETAIN_UNTIL.lt(until.atOffset(ZoneOffset.UTC)))
                .execute()
            audit.record(
                "file.retention.extended",
                "file.version",
                versionId.toString(),
                before = mapOf("retainUntil" to lock.current.toString()),
                after = mapOf("retainUntil" to until.toString(), "mode" to lock.mode.name),
            )
        }
        return true
    }

    private data class Lock(
        val key: String,
        val version: String,
        val current: Instant,
        val mode: RetentionMode,
    )
}
