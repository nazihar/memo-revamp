package com.memo.files.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.storage.Bucket
import com.memo.common.storage.ObjectStore
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileRenditions
import com.memo.files.FileType
import com.memo.files.FileVersionCreated
import com.memo.files.RenditionConverter
import com.memo.files.RenditionDone
import com.memo.files.RenditionInfo
import com.memo.files.RenditionInput
import com.memo.files.RenditionKind
import com.memo.files.RenditionOutcome
import com.memo.files.RenditionState
import com.memo.files.TextExtractor
import com.memo.jooq.tables.references.FILES_RENDITION
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Semaphore

/** A rendition row as the job and the reads need it. */
internal data class RenditionRow(
    val versionId: UUID,
    val kind: RenditionKind,
    val state: RenditionState,
    val attempts: Int,
    val error: String?,
    val objectKey: String?,
    val sizeBytes: Long?,
    val ciphertextBytes: Long?,
    val pages: Int?,
    val truncated: Boolean,
    val createdAt: OffsetDateTime,
) {
    fun info() = RenditionInfo(kind, state, error, pages, sizeBytes, truncated)
}

/** The stored result of a rendition. */
internal data class RenditionResult(
    val objectKey: String,
    val sizeBytes: Long,
    val ciphertextBytes: Long,
    val sha256: ByteArray,
    val keysetKeyId: Long,
    val pages: Int?,
    val truncated: Boolean,
)

/** `files_rendition` (ADR-0045); every method runs inside a tenant transaction. */
@Component
internal class RenditionStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun insert(
        versionId: UUID,
        kind: RenditionKind,
        state: RenditionState,
        error: String? = null,
    ) {
        tenantDsl
            .current()
            .insertInto(FILES_RENDITION)
            .set(FILES_RENDITION.TENANT_ID, tenantDsl.tenant().value)
            .set(FILES_RENDITION.VERSION_ID, versionId)
            .set(FILES_RENDITION.KIND, kind.name)
            .set(FILES_RENDITION.STATE, state.name)
            .set(FILES_RENDITION.ERROR, error)
            .set(FILES_RENDITION.CREATED_AT, now())
            .set(FILES_RENDITION.FINISHED_AT, if (state == RenditionState.PENDING) null else now())
            .onConflictDoNothing()
            .execute()
    }

    /** Takes a pending rendition for one attempt: its attempt number, or null when it is no longer pending. */
    fun claim(
        versionId: UUID,
        kind: RenditionKind,
    ): Int? =
        tenantDsl
            .current()
            .update(FILES_RENDITION)
            .set(FILES_RENDITION.ATTEMPTS, FILES_RENDITION.ATTEMPTS.plus(1))
            .where(of(versionId, kind), FILES_RENDITION.STATE.eq(RenditionState.PENDING.name))
            .returning(FILES_RENDITION.ATTEMPTS)
            .fetchOne()
            ?.attempts

    fun done(
        versionId: UUID,
        kind: RenditionKind,
        result: RenditionResult,
    ) {
        tenantDsl
            .current()
            .update(FILES_RENDITION)
            .set(FILES_RENDITION.STATE, RenditionState.DONE.name)
            .set(FILES_RENDITION.ERROR, null as String?)
            .set(FILES_RENDITION.OBJECT_KEY, result.objectKey)
            .set(FILES_RENDITION.SIZE_BYTES, result.sizeBytes)
            .set(FILES_RENDITION.CIPHERTEXT_BYTES, result.ciphertextBytes)
            .set(FILES_RENDITION.SHA256, result.sha256)
            .set(FILES_RENDITION.KEYSET_KEY_ID, result.keysetKeyId)
            .set(FILES_RENDITION.PAGES, result.pages)
            .set(FILES_RENDITION.TRUNCATED, result.truncated)
            .set(FILES_RENDITION.FINISHED_AT, now())
            .where(of(versionId, kind), FILES_RENDITION.STATE.eq(RenditionState.PENDING.name))
            .execute()
    }

    fun end(
        versionId: UUID,
        kind: RenditionKind,
        state: RenditionState,
        error: String,
    ) {
        tenantDsl
            .current()
            .update(FILES_RENDITION)
            .set(FILES_RENDITION.STATE, state.name)
            .set(FILES_RENDITION.ERROR, error)
            .set(FILES_RENDITION.FINISHED_AT, now())
            .where(of(versionId, kind), FILES_RENDITION.STATE.eq(RenditionState.PENDING.name))
            .execute()
    }

    /** Records the last transient error while the rendition stays pending for its next attempt. */
    fun noteRetry(
        versionId: UUID,
        kind: RenditionKind,
        error: String,
    ) {
        tenantDsl
            .current()
            .update(FILES_RENDITION)
            .set(FILES_RENDITION.ERROR, error)
            .where(of(versionId, kind), FILES_RENDITION.STATE.eq(RenditionState.PENDING.name))
            .execute()
    }

    fun of(versionId: UUID): List<RenditionRow> =
        tenantDsl
            .current()
            .selectFrom(FILES_RENDITION)
            .where(FILES_RENDITION.VERSION_ID.eq(versionId))
            .orderBy(FILES_RENDITION.KIND)
            .fetch { it.toRow() }

    fun get(
        versionId: UUID,
        kind: RenditionKind,
    ): RenditionRow? =
        tenantDsl
            .current()
            .selectFrom(FILES_RENDITION)
            .where(of(versionId, kind))
            .fetchOne()
            ?.toRow()

    /** Pending renditions created before [before]: their job may have been lost. */
    fun stalled(
        before: OffsetDateTime,
        limit: Int,
    ): List<RenditionRow> =
        tenantDsl
            .current()
            .selectFrom(FILES_RENDITION)
            .where(FILES_RENDITION.STATE.eq(RenditionState.PENDING.name), FILES_RENDITION.CREATED_AT.lt(before))
            .orderBy(FILES_RENDITION.CREATED_AT)
            .limit(limit)
            .fetch { it.toRow() }

    private fun of(
        versionId: UUID,
        kind: RenditionKind,
    ) = FILES_RENDITION.VERSION_ID.eq(versionId).and(FILES_RENDITION.KIND.eq(kind.name))

    private fun com.memo.jooq.tables.records.FilesRenditionRecord.toRow() =
        RenditionRow(
            versionId = versionId,
            kind = RenditionKind.valueOf(kind),
            state = RenditionState.valueOf(state),
            attempts = attempts!!,
            error = error,
            objectKey = objectKey,
            sizeBytes = sizeBytes,
            ciphertextBytes = ciphertextBytes,
            pages = pages,
            truncated = truncated == true,
            createdAt = createdAt,
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

/** Task data of one rendition. */
data class RenditionTask(
    override val tenantId: UUID,
    val versionId: UUID,
    val kind: RenditionKind,
) : TenantScopedPayload

/** A transient failure: the scheduler retries the task with backoff. */
internal class RenditionRetry(
    code: String,
) : RuntimeException(code)

/**
 * Builds renditions (ADR-0045): decrypts the original as a stream for the port, encrypts the port's output into a spool,
 * checks a PDF's pages, stores it in `work` and records the result. Runs in a rendition task, as the system, in the
 * tenant's context and outside a transaction.
 */
@Component
internal class RenditionWorker(
    private val events: ApplicationEventPublisher,
    private val files: FileStore,
    private val store: RenditionStore,
    private val purposes: Purposes,
    private val objects: ObjectStore,
    private val crypto: FileCrypto,
    private val spool: Spool,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val properties: FilesProperties,
    private val converter: ObjectProvider<RenditionConverter>,
    private val extractor: ObjectProvider<TextExtractor>,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val slots = RenditionKind.entries.associateWith { Semaphore(properties.renditionConcurrency) }
    private val random = SecureRandom()
    private val outcomes = {
        kind: RenditionKind,
        outcome: String,
        ->
        meters.counter("memo.files.renditions", "kind", kind.name, "outcome", outcome)
    }

    fun run(
        versionId: UUID,
        kind: RenditionKind,
    ) {
        // A busy node is not an attempt: wait for a slot before claiming.
        val slot = slots.getValue(kind)
        if (!slot.tryAcquire(SLOT_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) throw RenditionRetry("BUSY")
        try {
            attempt(versionId, kind)
        } finally {
            slot.release()
        }
    }

    private fun attempt(
        versionId: UUID,
        kind: RenditionKind,
    ) {
        val tenant = TenantContext.current()
        val attempt = transactions.inTenant(tenant) { store.claim(versionId, kind) } ?: return
        val (version, stored, purpose) =
            transactions.readInTenant(tenant) {
                val version = files.version(versionId)
                val info = version?.let { files.info(it.fileId) }
                Triple(version, files.storedObject(versionId), info?.let { purposes[it.purpose] })
            }
        if (version == null || stored == null || purpose == null) {
            finish(tenant, versionId, kind, RenditionState.FAILED, "MISSING")
            return
        }
        val type = FileType.ofMediaType(version.mediaType)
        val input =
            RenditionInput(version.name, type ?: FileType.TEXT, version.sizeBytes) {
                val ciphertext =
                    objects.channel(
                        bucketOf(stored.bucket),
                        stored.objectKey,
                        stored.objectVersion,
                        stored.ciphertextBytes,
                        READ_CHUNK,
                    )
                Channels.newInputStream(crypto.decrypting(tenant, stored.fileId, stored.versionId, ciphertext))
            }
        build(tenant, versionId, kind, input, purpose.maxPdfPages, attempt)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun build(
        tenant: TenantId,
        versionId: UUID,
        kind: RenditionKind,
        input: RenditionInput,
        maxPages: Int,
        attempt: Int,
    ) {
        val spoolFile = spool.create()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            val outcome =
                FileChannel.open(spoolFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { file ->
                    Channels.newOutputStream(crypto.encryptingRendition(tenant, versionId, kind.name, file)).use { encrypted ->
                        val out =
                            object : FilterOutputStream(encrypted) {
                                override fun write(
                                    b: ByteArray,
                                    off: Int,
                                    len: Int,
                                ) {
                                    digest.update(b, off, len)
                                    written += len
                                    out.write(b, off, len)
                                }

                                override fun write(b: Int) {
                                    digest.update(b.toByte())
                                    written++
                                    out.write(b)
                                }
                            }
                        try {
                            convert(kind, input, renditionKey(tenant, versionId, kind), out)
                        } catch (e: RuntimeException) {
                            log.warn("Rendition {} of {} failed: {}", kind, versionId, e.javaClass.simpleName)
                            RenditionOutcome.Retry("ERROR")
                        }
                    }
                }
            when (outcome) {
                is RenditionOutcome.Final -> {
                    finish(tenant, versionId, kind, RenditionState.FAILED, outcome.code)
                }

                is RenditionOutcome.Retry -> {
                    retryOrFail(tenant, versionId, kind, outcome.code, attempt)
                }

                is RenditionOutcome.Written -> {
                    store(
                        tenant,
                        versionId,
                        kind,
                        spoolFile,
                        digest.digest(),
                        written,
                        outcome.truncated,
                        maxPages,
                    )
                }
            }
        } finally {
            spool.delete(spoolFile)
        }
    }

    private fun convert(
        kind: RenditionKind,
        input: RenditionInput,
        key: String,
        out: OutputStream,
    ): RenditionOutcome =
        when (kind) {
            RenditionKind.PDF -> {
                converter.ifAvailable?.toPdf(input, key, out) ?: RenditionOutcome.Final("NO_SERVICE")
            }

            RenditionKind.TEXT -> {
                extractor.ifAvailable?.text(input, properties.maxTextChars, out) ?: RenditionOutcome.Final("NO_SERVICE")
            }
        }

    private fun store(
        tenant: TenantId,
        versionId: UUID,
        kind: RenditionKind,
        spoolFile: java.nio.file.Path,
        sha256: ByteArray,
        sizeBytes: Long,
        truncated: Boolean,
        maxPages: Int,
    ) {
        val pages =
            if (kind == RenditionKind.PDF) {
                try {
                    FileChannel.open(spoolFile, StandardOpenOption.READ).use { file ->
                        PdfInspector.pages(crypto.decryptingRendition(tenant, versionId, kind.name, file), maxPages)
                    }
                } catch (e: UploadRejected) {
                    finish(
                        tenant,
                        versionId,
                        kind,
                        RenditionState.FAILED,
                        if (e.reason ==
                            RejectionReason.TOO_LARGE
                        ) {
                            "PAGES"
                        } else {
                            e.reason.name
                        },
                    )
                    return
                }
            } else {
                null
            }
        val key = "t/$tenant/r/${Uploads.base32(ByteArray(KEY_BYTES).also(random::nextBytes))}"
        objects.put(Bucket.WORK, key, spoolFile, CIPHERTEXT_TYPE, null)
        val result = RenditionResult(key, sizeBytes, Files.size(spoolFile), sha256, crypto.primaryKeyId(tenant), pages, truncated)
        transactions.inTenant(tenant) {
            store.done(versionId, kind, result)
            val file = files.version(versionId)?.let { files.info(it.fileId) }
            if (file != null) {
                events.publishEvent(RenditionDone(tenant.value, file.purpose, file.ownerId, file.id, versionId, kind))
            }
        }
        outcomes(kind, "done").increment()
    }

    private fun retryOrFail(
        tenant: TenantId,
        versionId: UUID,
        kind: RenditionKind,
        code: String,
        attempt: Int,
    ) {
        if (attempt >= MAX_ATTEMPTS) {
            finish(tenant, versionId, kind, RenditionState.FAILED, code)
            return
        }
        transactions.inTenant(tenant) { store.noteRetry(versionId, kind, code) }
        outcomes(kind, "retry").increment()
        throw RenditionRetry(code)
    }

    private fun finish(
        tenant: TenantId,
        versionId: UUID,
        kind: RenditionKind,
        state: RenditionState,
        code: String,
    ) {
        transactions.inTenant(tenant) {
            store.end(versionId, kind, state, code)
            if (state == RenditionState.FAILED) {
                audit.record(
                    "file.rendition.failed",
                    "file.version",
                    versionId.toString(),
                    after =
                        mapOf(
                            "kind" to kind.name,
                            "code" to code,
                        ),
                )
            }
        }
        outcomes(kind, state.name.lowercase()).increment()
    }

    /**
     * The conversion's name, one per tenant, version and kind. Not ONLYOFFICE's cache key: the adapter adds a fresh suffix to
     * it on every call, so a retry is never answered with an earlier attempt's kept failure (E06 T12).
     */
    private fun renditionKey(
        tenant: TenantId,
        versionId: UUID,
        kind: RenditionKind,
    ): String = "r${KEY_VERSION}-${tenant.value.toString().take(TENANT_PREFIX)}-$versionId-${kind.name.lowercase()}"

    companion object {
        const val MAX_ATTEMPTS = 4
        private const val KEY_VERSION = 1
        private const val TENANT_PREFIX = 8
        private const val KEY_BYTES = 16
        private const val READ_CHUNK = 2 * TenantKeysets.SEGMENT_BYTES
        private const val CIPHERTEXT_TYPE = "application/octet-stream"
        private val SLOT_WAIT: Duration = Duration.ofSeconds(30)
    }
}

/**
 * Records the renditions a new version needs, in the upload's transaction, and schedules their tasks (ADR-0045). A PDF
 * original needs no PDF and an image needs none; a type the service cannot handle, or no service, is not possible.
 */
@Component
internal class RenditionScheduler(
    private val store: RenditionStore,
    private val files: FileStore,
    private val purposes: Purposes,
    private val converter: ObjectProvider<RenditionConverter>,
    private val extractor: ObjectProvider<TextExtractor>,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("filesRenditionTask") private val task: OneTimeTask<RenditionTask>,
) {
    /** False only while a test runs the worker itself (test contexts share the scheduler table). */
    @Volatile internal var jobs: Boolean = true

    @EventListener
    fun onVersion(event: FileVersionCreated) {
        val kinds = purposes[event.purposeKey]?.renditions.orEmpty()
        if (kinds.isEmpty()) return
        val type = files.version(event.versionId)?.let { FileType.ofMediaType(it.mediaType) } ?: return
        kinds.forEach { kind ->
            val need = need(kind, type) ?: return@forEach
            when (need) {
                RenditionState.PENDING -> {
                    store.insert(event.versionId, kind, RenditionState.PENDING)
                    schedule(event.tenantId, event.versionId, kind)
                }

                else -> {
                    store.insert(event.versionId, kind, need, if (serviceFor(kind) == null) "NO_SERVICE" else "UNSUPPORTED")
                }
            }
        }
    }

    fun schedule(
        tenant: UUID,
        versionId: UUID,
        kind: RenditionKind,
    ) {
        if (!jobs) return
        scheduler.ifAvailable?.scheduleIfNotExists(
            task.schedulableInstance("$versionId:${kind.name}", RenditionTask(tenant, versionId, kind)),
        )
    }

    /** What [type] needs for [kind]: nothing (null), a build, or nothing possible. */
    private fun need(
        kind: RenditionKind,
        type: FileType,
    ): RenditionState? =
        when {
            kind == RenditionKind.PDF && (type == FileType.PDF || type in IMAGES) -> null
            kind == RenditionKind.PDF && converter.ifAvailable?.pdfTypes?.contains(type) == true -> RenditionState.PENDING
            kind == RenditionKind.TEXT && extractor.ifAvailable?.textTypes?.contains(type) == true -> RenditionState.PENDING
            else -> RenditionState.NOT_POSSIBLE
        }

    private fun serviceFor(kind: RenditionKind): Any? =
        when (kind) {
            RenditionKind.PDF -> converter.ifAvailable
            RenditionKind.TEXT -> extractor.ifAvailable
        }

    companion object {
        val IMAGES = setOf(FileType.PNG, FileType.JPEG, FileType.WEBP)
    }
}

/** Re-schedules renditions whose task was lost (a node stopped mid-way); the task instance id makes this idempotent. */
@Component
internal class RenditionRecovery(
    private val store: RenditionStore,
    private val scheduler: RenditionScheduler,
    private val transactions: TenantTransactions,
    private val clock: Clock,
) {
    fun recover(): Int {
        val tenant = TenantContext.current()
        val before = OffsetDateTime.now(clock).minus(STALLED_AFTER).withOffsetSameInstant(ZoneOffset.UTC)
        val stalled = transactions.readInTenant(tenant) { store.stalled(before, BATCH) }
        stalled.forEach { scheduler.schedule(tenant.value, it.versionId, it.kind) }
        return stalled.size
    }

    companion object {
        val STALLED_AFTER: Duration = Duration.ofMinutes(5)
        private const val BATCH = 200
    }
}

/** [FileRenditions] for owning modules. */
@Component
internal class FileRenditionsImpl(
    private val store: RenditionStore,
    private val objects: ObjectStore,
    private val crypto: FileCrypto,
    private val transactions: TenantTransactions,
    private val backlog: RenditionBacklog,
) : FileRenditions {
    override fun of(versionId: UUID): List<RenditionInfo> = store.of(versionId).map { it.info() }

    override fun pendingCount(
        purposeKey: String,
        atMost: Int,
    ): Int = backlog.pending(purposeKey, atMost)

    override fun pendingCount(versionIds: Collection<UUID>): Int = backlog.pending(versionIds)

    override fun states(versionIds: Collection<UUID>): Map<UUID, Map<RenditionKind, RenditionState>> = backlog.states(versionIds)

    override fun write(
        versionId: UUID,
        kind: RenditionKind,
        out: OutputStream,
    ): Boolean {
        val tenant = TenantContext.current()
        val row = transactions.readInTenant(tenant) { store.get(versionId, kind) }
        if (row?.state != RenditionState.DONE) return false
        open(tenant, row).use { it.copyTo(out) }
        return true
    }

    /** The plaintext of a done rendition as a stream, read from storage 1 MiB at a time (a whole text in a request or two). */
    fun open(
        tenant: TenantId,
        row: RenditionRow,
    ): java.io.InputStream {
        val ciphertext = objects.channel(Bucket.WORK, row.objectKey!!, null, row.ciphertextBytes!!, WHOLE_READ_CHUNK)
        return Channels.newInputStream(crypto.decryptingRendition(tenant, row.versionId, row.kind.name, ciphertext))
    }

    private companion object {
        const val WHOLE_READ_CHUNK = 16 * TenantKeysets.SEGMENT_BYTES
    }
}

/** The rendition tasks (ADR-0018, ADR-0045). */
@Configuration(proxyBeanMethods = false)
internal class RenditionJobs {
    @Bean
    fun filesRenditionTask(
        jobs: Jobs,
        worker: RenditionWorker,
    ): OneTimeTask<RenditionTask> =
        jobs.tenantTask(RENDITION, RenditionTask::class.java, maxRetries = RenditionWorker.MAX_ATTEMPTS) {
            worker.run(it.versionId, it.kind)
        }

    @Bean
    fun filesRenditionRecoverTask(
        jobs: Jobs,
        recovery: RenditionRecovery,
    ): OneTimeTask<FilesTenantTask> = jobs.tenantTask(RECOVER, FilesTenantTask::class.java) { recovery.recover() }

    @Bean
    fun filesRenditionRecoverSweep(
        jobs: Jobs,
        sweeper: FilesSweeper,
    ): RecurringTask<Void> =
        jobs.platformTask("files-rendition-sweep", Schedules.fixedDelay(RenditionRecovery.STALLED_AFTER)) {
            sweeper.fanOut(RECOVER)
        }

    companion object {
        const val RENDITION = "files-rendition"
        const val RECOVER = "files-rendition-recover"
    }
}
