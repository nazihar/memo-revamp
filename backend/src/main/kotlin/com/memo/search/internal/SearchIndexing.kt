package com.memo.search.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileRenditions
import com.memo.files.RenditionDone
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.memo.MemoChanged
import com.memo.memo.MemoFileChanged
import com.memo.memo.MemoNotePosted
import com.memo.memo.MemoSearchEvent
import com.memo.memo.MemoSearchSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.UUID

/** Task data of indexing one file version's text. */
data class SearchChunkTask(
    override val tenantId: UUID,
    val fileId: UUID,
    val versionId: UUID,
) : TenantScopedPayload

/** Task data of a tenant's search backfill. */
data class SearchBackfillTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/**
 * Keeps the search index with its sources (ADR-0054, design E05 §3, §11): memo and note rows in the transaction of the
 * change (the memo module publishes just before commit), a file's chunks by a job when its text is ready, and a backfill
 * for what existed before.
 */
@Service
class SearchIndexing(
    private val index: SearchIndex,
    private val source: MemoSearchSource,
    private val renditions: FileRenditions,
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("searchChunkTask") private val chunkTask: ObjectProvider<OneTimeTask<SearchChunkTask>>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** False only while a test runs the chunk job itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    @EventListener
    fun onMemo(event: MemoSearchEvent) {
        when (event) {
            is MemoChanged -> index.putMemo(event.memoId)
            is MemoNotePosted -> index.putNote(event.noteId)
            is MemoFileChanged -> index.refreshFile(event.fileId)
        }
    }

    /** A memo file's text is ready: its chunks are written by a job (reading and splitting run outside the transaction). */
    @EventListener
    fun onRendition(event: RenditionDone) {
        if (event.kind == RenditionKind.TEXT && source.file(event.fileId) != null) schedule(event.fileId, event.versionId)
    }

    /**
     * Indexes the text of [versionId] as [fileId]'s chunks, if it is still the file's current version; drops the file's
     * chunks when it is no longer a memo's file. Outside a transaction, in the tenant's context.
     */
    fun chunk(
        fileId: UUID,
        versionId: UUID,
    ) {
        val tenant = TenantContext.current()
        val file = transactions.readInTenant(tenant) { source.file(fileId) }
        when {
            file == null -> {
                transactions.inTenant(tenant) { index.dropFile(fileId) }
            }

            file.currentVersionId != versionId -> {
                log.debug("search: {} is no longer the current version of its file", versionId)
            }

            else -> {
                val text = text(versionId)
                var chunker = SearchChunker()
                repeat(SPLIT_ATTEMPTS) { attempt ->
                    val chunks = chunker.split(text)
                    val written =
                        try {
                            transactions.inTenant(tenant) { index.putChunks(file, versionId, chunks) }
                            true
                        } catch (e: DataAccessException) {
                            if (attempt == SPLIT_ATTEMPTS - 1 || !tooLong(e)) throw e
                            log.info("search: a chunk of {} was too long for a vector; splitting further", versionId)
                            false
                        }
                    if (written) return
                    chunker = chunker.smaller()
                }
            }
        }
    }

    /**
     * Indexes up to [limit] memos that have no search row yet: details, posted notes, and a chunk job for each file whose
     * text is ready. Inside a tenant transaction; returns how many memos it indexed.
     */
    fun backfill(limit: Int = BACKFILL_BATCH): Int {
        val memos = index.unindexed(limit)
        memos.forEach { memoId ->
            index.putMemo(memoId)
            source.notes(memoId).forEach { index.putNote(it.noteId) }
            source.files(memoId).forEach { file ->
                file.currentVersionId?.takeIf(::textReady)?.let { schedule(file.fileId, it) }
            }
        }
        return memos.size
    }

    private fun schedule(
        fileId: UUID,
        versionId: UUID,
    ) {
        val client = scheduler.ifAvailable.takeIf { jobs }
        val task = chunkTask.ifAvailable
        if (client != null && task != null) {
            client.scheduleIfNotExists(
                task.schedulableInstance("$fileId:$versionId", SearchChunkTask(tenantDsl.tenant().value, fileId, versionId)),
            )
        }
    }

    private fun text(versionId: UUID): String =
        ByteArrayOutputStream().also { renditions.write(versionId, RenditionKind.TEXT, it) }.toString(Charsets.UTF_8)

    private fun textReady(versionId: UUID): Boolean =
        renditions.of(versionId).any { it.kind == RenditionKind.TEXT && it.state == RenditionState.DONE }

    private fun tooLong(e: DataAccessException): Boolean =
        generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("too long for tsvector") == true }

    companion object {
        const val BACKFILL_BATCH = 200
        private const val SPLIT_ATTEMPTS = 4
    }
}

/** The search jobs (ADR-0018, design E05 §11): chunks per file version, and the backfill per tenant until it is done. */
@Configuration(proxyBeanMethods = false)
class SearchJobs {
    @Bean
    fun searchChunkTask(
        jobs: Jobs,
        indexing: ObjectProvider<SearchIndexing>,
    ): OneTimeTask<SearchChunkTask> =
        jobs.tenantTask(CHUNKS, SearchChunkTask::class.java) { indexing.getObject().chunk(it.fileId, it.versionId) }

    @Bean
    fun searchBackfillTask(
        jobs: Jobs,
        indexing: ObjectProvider<SearchIndexing>,
        transactions: TenantTransactions,
    ): OneTimeTask<SearchBackfillTask> =
        jobs.tenantTask(BACKFILL, SearchBackfillTask::class.java) {
            // Batches in their own transactions, until none is left or the run has done its share.
            var batches = 0
            while (batches < BATCHES_PER_RUN &&
                transactions.inTenant(TenantContext.current()) { indexing.getObject().backfill() } == SearchIndexing.BACKFILL_BATCH
            ) {
                batches++
            }
        }

    /** On unless `memo.search.backfill-sweep` is false (integration tests: they create memos without search rows by SQL). */
    @Bean
    @ConditionalOnProperty("memo.search.backfill-sweep", havingValue = "true", matchIfMissing = true)
    fun searchBackfillSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        @Qualifier("searchBackfillTask") task: ObjectProvider<OneTimeTask<SearchBackfillTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("search-backfill-sweep", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val backfill = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(backfill.schedulableInstance(tenant.toString(), SearchBackfillTask(tenant.value)))
            }
        }

    private companion object {
        const val CHUNKS = "search-chunks"
        const val BACKFILL = "search-backfill"
        const val BATCHES_PER_RUN = 50
        val EVERY: Duration = Duration.ofHours(1)
    }
}
