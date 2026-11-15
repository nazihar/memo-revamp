package com.memo.memo.internal.rendition

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.Files
import com.memo.memo.internal.Memos
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

/** Task data of a tenant's fixity run. */
data class RecordFixityTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/** What a fixity run found. */
data class FixityResult(
    val checked: Int,
    val mismatched: Int,
)

/**
 * The weekly fixity check of final records (ADR-0053, design E04 §6; threat model T1): every made version is read back
 * through the files service, decrypted and re-hashed, and compared with the hash recorded when it was made. A mismatch is
 * audited on its own (a security incident, see the runbook); each run is audited with its counts. Outside a transaction
 * except for the reads and the audit; pages of [PAGE] so a large tenant never holds one long transaction.
 */
@Component
class RecordFixity(
    private val queries: MemoRenditionQueries,
    private val files: Files,
    private val contents: FileContents,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(): FixityResult {
        val tenant = TenantContext.current()
        var after: UUID? = null
        var checked = 0
        var mismatched = 0
        do {
            val page = transactions.readInTenant(tenant) { queries.ready(after, PAGE) }
            page.forEach { record ->
                checked++
                val actual = hashOf(record.fileVersionId)
                if (actual == null || !actual.equals(record.sha256, ignoreCase = true)) {
                    mismatched++
                    log.error("Final record {} of memo {} does not match its recorded hash", record.version, record.memoId)
                    transactions.inTenant(tenant) {
                        audit.record(
                            "memo.rendition.mismatch",
                            Memos.OBJECT_TYPE,
                            record.memoId.toString(),
                            after = mapOf("version" to record.version, "expected" to record.sha256, "actual" to actual),
                        )
                    }
                }
            }
            after = page.lastOrNull()?.id
        } while (page.size == PAGE)
        transactions.inTenant(tenant) {
            audit.record(
                "memo.rendition.verified",
                Memos.OBJECT_TYPE,
                null,
                after = mapOf("checked" to checked, "mismatched" to mismatched),
            )
        }
        return FixityResult(checked, mismatched)
    }

    /** SHA-256 of the stored plaintext, or null when it cannot be read at all. */
    private fun hashOf(versionId: UUID): String? {
        val version = transactions.readInTenant(TenantContext.current()) { files.version(versionId) } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        return runCatching {
            DigestOutputStream(OutputStream.nullOutputStream(), digest).use { contents.write(version, it) }
            HexFormat.of().formatHex(digest.digest())
        }.getOrNull()
    }

    companion object {
        const val PAGE = 100
    }
}

/** The record lifecycle tasks (ADR-0018, design E04 §11): the lock extension at closure and the weekly fixity check. */
@Configuration(proxyBeanMethods = false)
class RecordLifecycleJobs {
    @Bean
    fun memoRenditionRetentionTask(
        jobs: Jobs,
        lifecycle: RecordLifecycle,
    ): OneTimeTask<RecordRetentionTask> =
        jobs.tenantTask("memo-rendition-retention", RecordRetentionTask::class.java) {
            lifecycle.extendRetention(it.memoId)
        }

    @Bean
    fun memoRenditionFixityTask(
        jobs: Jobs,
        fixity: RecordFixity,
    ): OneTimeTask<RecordFixityTask> = jobs.tenantTask(FIXITY, RecordFixityTask::class.java) { fixity.verify() }

    @Bean
    fun memoRenditionFixitySweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        @Qualifier("memoRenditionFixityTask") task: ObjectProvider<OneTimeTask<RecordFixityTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("memo-rendition-fixity-sweep", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val fixity = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(fixity.schedulableInstance(tenant.toString(), RecordFixityTask(tenant.value)))
            }
        }

    private companion object {
        const val FIXITY = "memo-rendition-fixity"
        val EVERY: Duration = Duration.ofDays(7)
    }
}
