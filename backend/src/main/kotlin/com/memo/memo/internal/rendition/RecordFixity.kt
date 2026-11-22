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
import com.memo.memo.RecordFixitySource
import com.memo.memo.internal.Memos
import com.memo.memo.internal.legacy.LegacyMemoReader
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

/** What a fixity run found: final records, and the originals of imported memos (design E06 §11). */
data class FixityResult(
    val checked: Int,
    val mismatched: Int,
    val originalsChecked: Int = 0,
    val originalsMismatched: Int = 0,
    /** Other modules' records ([RecordFixitySource], for example the approved minutes' records and copies), by source. */
    val sources: Map<String, FixityCount> = emptyMap(),
)

/** What a fixity run found in one source. */
data class FixityCount(
    val checked: Int,
    val mismatched: Int,
)

/**
 * The weekly fixity check (ADR-0053, design E04 §6; threat model T1): every made version of a final record, and every
 * original of an imported memo (ADR-0055: the originals are its record, design E06 §11), is read back through the files
 * service, decrypted and re-hashed, and compared with the hash recorded when it was made or imported. A mismatch is
 * audited on its own (a security incident, see the runbook); each part of a run is audited with its counts. Outside a
 * transaction except for the reads and the audit; pages of [PAGE] so a large tenant never holds one long transaction.
 */
@Component
class RecordFixity(
    private val queries: MemoRenditionQueries,
    private val legacy: LegacyMemoReader,
    private val files: Files,
    private val contents: FileContents,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val others: ObjectProvider<RecordFixitySource>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(): FixityResult {
        val records = records()
        val originals = originals()
        val sources = others.orderedStream().toList().associate { it.verifiedAction to source(it) }
        return FixityResult(records.checked, records.mismatched, originals.checked, originals.mismatched, sources)
    }

    /** Another module's records ([RecordFixitySource]): each version against the hash recorded when it was stored. */
    private fun source(source: RecordFixitySource): FixityCount {
        val tenant = TenantContext.current()
        val result =
            paged({ after -> transactions.readInTenant(tenant) { source.page(after, PAGE) } }, { it.id }) { page ->
                page.count { record ->
                    val actual = hashOf(record.fileVersionId)
                    val mismatch = actual == null || !actual.equals(record.sha256, ignoreCase = true)
                    if (mismatch) {
                        log.error("Record {} ({}) does not match its recorded hash", record.id, source.mismatchAction)
                        transactions.inTenant(tenant) {
                            audit.record(
                                source.mismatchAction,
                                record.objectType,
                                record.objectId,
                                after = record.details + mapOf("expected" to record.sha256, "actual" to actual),
                            )
                        }
                    }
                    mismatch
                }
            }
        transactions.inTenant(tenant) {
            audit.record(
                source.verifiedAction,
                source.objectType,
                null,
                after = mapOf("checked" to result.checked, "mismatched" to result.mismatched),
            )
        }
        return FixityCount(result.checked, result.mismatched)
    }

    /** Final records: each made version against the hash recorded when it was made. */
    private fun records(): FixityResult {
        val tenant = TenantContext.current()
        val result =
            paged({ after -> transactions.readInTenant(tenant) { queries.ready(after, PAGE) } }, { it.id }) { page ->
                page.count { record ->
                    val actual = hashOf(record.fileVersionId)
                    val mismatch = actual == null || !actual.equals(record.sha256, ignoreCase = true)
                    if (mismatch) {
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
                    mismatch
                }
            }
        counted("memo.rendition.verified", result)
        return result
    }

    /** Imported originals: each file's stored version against the SHA-256 it was imported with. */
    private fun originals(): FixityResult {
        val tenant = TenantContext.current()
        val result =
            paged({ after -> transactions.readInTenant(tenant) { legacy.originalsAfter(after, PAGE) } }, { it.id }) { page ->
                val versions = transactions.readInTenant(tenant) { files.currentVersions(page.map { it.fileId }) }
                page.count { original ->
                    val version = versions[original.fileId]?.id
                    val actual = version?.let(::hashOf)
                    val mismatch = actual == null || !actual.equals(original.sha256, ignoreCase = true)
                    if (mismatch) {
                        log.error("Original {} of imported memo {} does not match its imported hash", original.fileId, original.memoId)
                        transactions.inTenant(tenant) {
                            audit.record(
                                "memo.legacy.mismatch",
                                Memos.OBJECT_TYPE,
                                original.memoId.toString(),
                                after =
                                    mapOf(
                                        "fileId" to original.fileId.toString(),
                                        "versionId" to version?.toString(),
                                        "expected" to original.sha256,
                                        "actual" to actual,
                                    ),
                            )
                        }
                    }
                    mismatch
                }
            }
        counted("memo.legacy.verified", result)
        return result
    }

    /** Runs [check] (which answers how many mismatched) over every page [load] gives after the last item's [id]. */
    private fun <T> paged(
        load: (UUID?) -> List<T>,
        id: (T) -> UUID,
        check: (List<T>) -> Int,
    ): FixityResult {
        var after: UUID? = null
        var checked = 0
        var mismatched = 0
        do {
            val page = load(after)
            checked += page.size
            mismatched += check(page)
            after = page.lastOrNull()?.let(id)
        } while (page.size == PAGE)
        return FixityResult(checked, mismatched)
    }

    private fun counted(
        action: String,
        result: FixityResult,
    ) {
        transactions.inTenant(TenantContext.current()) {
            audit.record(action, Memos.OBJECT_TYPE, null, after = mapOf("checked" to result.checked, "mismatched" to result.mismatched))
        }
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
