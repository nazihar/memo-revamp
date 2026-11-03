package com.memo.files.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.storage.Bucket
import com.memo.common.storage.ObjectStore
import com.memo.common.storage.Retention
import com.memo.common.storage.RetentionMode
import com.memo.common.storage.StorageUnavailableException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.PreconditionRequiredException
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileVersionCreated
import com.memo.files.StorageClass
import com.memo.system.Idempotency
import com.memo.tenancy.TenantSettings
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** One upload as the API received it: a new file ([purposeKey]) or a new version of [fileId]. */
internal data class UploadRequest(
    val purposeKey: String?,
    val ownerId: String?,
    val fileId: UUID?,
    val expectedFileVersion: Long?,
    val declaredName: String?,
    val declaredLength: Long?,
    val idempotencyKey: String,
    val body: InputStream,
)

/** The upload body ended before it was complete (the client went away): 400, nothing stored. */
internal class UploadIncompleteException(
    cause: IOException,
) : RuntimeException("upload incomplete", cause)

/**
 * The upload pipeline of ADR-0029: check, then one pass over the body (SHA-256, clamd, encryption into a ciphertext
 * spool), inspection by decrypting the spool, storage, then the business transaction. Nothing rejected reaches object
 * storage; no transaction is open while the body is read or the object is written.
 */
@Component
internal class Uploads(
    private val purposes: Purposes,
    private val store: FileStore,
    private val pending: PendingUploads,
    private val transactions: TenantTransactions,
    private val settings: TenantSettings,
    private val properties: FilesProperties,
    private val rateLimiter: RateLimiter,
    private val slots: UploadSlots,
    private val spool: Spool,
    private val crypto: FileCrypto,
    private val scanner: Scanner,
    private val inspector: ContentInspector,
    private val objects: ObjectStore,
    private val retentions: Retentions,
    private val idempotency: Idempotency,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /** Stores the upload and returns the id of the version that holds it (on an idempotent replay: the first one's). */
    fun upload(request: UploadRequest): UUID {
        val tenant = TenantContext.current()
        val user = (ActorContext.current().actor as? Actor.User)?.userId
        val plan = transactions.readInTenant(tenant) { plan(request) }
        val progress = Progress()
        try {
            rateLimiter.consume(RateLimit.UPLOAD_PER_USER, user?.toString() ?: "anonymous")
            if (request.declaredLength != null && request.declaredLength > plan.maxBytes) {
                reject(RejectionReason.TOO_LARGE, "maxBytes" to plan.maxBytes)
            }
            return slots.withSlot { process(tenant, user, request, plan, progress) }
        } catch (rejected: UploadRejected) {
            auditRejection(tenant, plan, request, progress, rejected)
            throw rejected
        }
    }

    private fun auditRejection(
        tenant: TenantId,
        plan: Plan,
        request: UploadRequest,
        progress: Progress,
        rejected: UploadRejected,
    ) = transactions.inTenant(tenant) {
        audit.record(
            "file.upload.rejected",
            "file.upload",
            null,
            after =
                rejected.detail +
                    mapOf(
                        "reason" to rejected.reason.name,
                        "purpose" to plan.purpose.key,
                        "owner" to plan.ownerId,
                        "fileId" to plan.fileId.toString().takeUnless { plan.isNew },
                        "declaredName" to request.declaredName?.let { FileNames.sanitise(it, "bin") },
                        "bytesRead" to progress.bytes,
                    ),
        )
    }

    private fun plan(request: UploadRequest): Plan {
        val existing = request.fileId?.let { store.info(it) ?: throw FileNotFoundException() }
        val ownerId = existing?.ownerId ?: request.ownerId
        val purpose = purposeOf(request, existing, ownerId)
        return Plan(
            purpose = purpose,
            fileId = existing?.id ?: ids.next(),
            isNew = existing == null,
            expectedVersion = if (existing == null) 0 else request.expectedFileVersion ?: throw PreconditionRequiredException(),
            ownerId = ownerId,
            maxBytes =
                listOfNotNull(
                    purpose.maxBytes,
                    properties.maxUploadBytes,
                    settings.get(MAX_BYTES_SETTING)?.value?.toLongOrNull(),
                ).min(),
            retention = if (purpose.storage == StorageClass.RECORD) recordRetention(purpose) else null,
        )
    }

    /** The purpose of the upload, if the actor may upload for [ownerId] under it. */
    private fun purposeOf(
        request: UploadRequest,
        existing: FileInfo?,
        ownerId: String?,
    ): FilePurpose {
        // A stored file whose purpose is no longer registered is treated as absent.
        val purpose =
            purposes[existing?.purpose ?: request.purposeKey.orEmpty()]
                ?: throw if (existing != null) FileNotFoundException() else UnknownPurposeException()
        if (!purpose.mayUpload(ownerId)) throw ForbiddenException(null)
        return purpose
    }

    private fun recordRetention(purpose: FilePurpose): RecordRetention {
        val mode = settings.get(RETENTION_MODE_SETTING)?.value?.let { value -> RetentionMode.entries.firstOrNull { it.name == value } }
        val years = purpose.retentionYears ?: settings.get(RETENTION_YEARS_SETTING)?.value?.toIntOrNull()
        return RecordRetention(mode ?: RetentionMode.GOVERNANCE, years ?: DEFAULT_RETENTION_YEARS)
    }

    private fun process(
        tenant: TenantId,
        user: UUID?,
        request: UploadRequest,
        plan: Plan,
        progress: Progress,
    ): UUID {
        // Creates the tenant's keyset on first use, in its own transaction, before anything is encrypted with it.
        val keyId = crypto.primaryKeyId(tenant)
        val versionId = ids.next()
        val spoolFile = spool.create()
        try {
            val received = receive(tenant, plan, versionId, request.body, spoolFile, progress)
            val inspected =
                inspector.inspect(
                    { crypto.decrypting(tenant, plan.fileId, versionId, FileChannel.open(spoolFile, StandardOpenOption.READ)) },
                    request.declaredName,
                    InspectionRules(plan.purpose.allowedTypes, plan.purpose.maxPdfPages),
                )
            val now = clock.instant()
            val bucket = if (plan.retention != null) Bucket.RECORDS else Bucket.WORK
            val objectKey = objectKey(tenant)
            val provisional = plan.retention?.provisional(now)
            val pendingId = ids.next()
            transactions.inTenant(tenant) { pending.insert(pendingId, bucket.stored, objectKey, now, provisional?.until) }
            val objectVersion = objects.put(bucket, objectKey, spoolFile, CIPHERTEXT_TYPE, provisional)

            val final = plan.retention?.final(now)
            val version =
                NewVersion(
                    id = versionId,
                    fileId = plan.fileId,
                    name = FileNames.sanitise(request.declaredName, inspected.type.extensions.first()),
                    mediaType = inspected.type.mediaType,
                    sizeBytes = received.size,
                    sha256 = received.sha256,
                    bucket = bucket.stored,
                    objectKey = objectKey,
                    objectVersion = objectVersion,
                    ciphertextBytes = Files.size(spoolFile),
                    keysetKeyId = keyId,
                    scanEngine = received.engine,
                    pdfPages = inspected.pdfPages,
                    lockState = if (final != null) LockState.PROVISIONAL else LockState.NONE,
                    retainUntil = final?.until,
                    retentionMode = final?.mode,
                    uploadedBy = user,
                    uploadedAt = now,
                )
            val digest = HexFormat.of().formatHex(received.sha256)
            val requestHash = listOf(plan.purpose.key, plan.ownerId, plan.fileId.takeUnless { plan.isNew }, digest).joinToString("|")
            val stored =
                transactions.inTenant(tenant) {
                    UUID.fromString(
                        idempotency.once(IDEMPOTENCY_OPERATION, request.idempotencyKey, requestHash) { commit(plan, version, pendingId) },
                    )
                }
            if (stored == versionId && final != null) retentions.finalise(tenant, versionId, objectKey, objectVersion!!, final)
            return stored
        } finally {
            spool.delete(spoolFile)
        }
    }

    /** Inside the business transaction: the version, the file pointer, the audit entry and the owner's event. */
    private fun commit(
        plan: Plan,
        version: NewVersion,
        pendingId: UUID,
    ): String {
        if (plan.isNew) store.insertFile(plan.fileId, plan.purpose.key, plan.ownerId, version.uploadedBy, version.uploadedAt)
        val number = store.appendVersion(version, plan.expectedVersion)
        pending.delete(pendingId)
        audit.record(
            "file.version.created",
            "file",
            plan.fileId.toString(),
            after =
                mapOf(
                    "versionId" to version.id.toString(),
                    "number" to number,
                    "purpose" to plan.purpose.key,
                    "owner" to plan.ownerId,
                    "mediaType" to version.mediaType,
                    "sizeBytes" to version.sizeBytes,
                    "sha256" to HexFormat.of().formatHex(version.sha256),
                    "bucket" to version.bucket,
                    "retainUntil" to version.retainUntil?.toString(),
                ),
        )
        events.publishEvent(FileVersionCreated(TenantContext.current().value, plan.purpose.key, plan.ownerId, plan.fileId, version.id))
        return version.id.toString()
    }

    /** One read of the body: every chunk to SHA-256, clamd and the encrypting spool. Fails closed on any scanner trouble. */
    private fun receive(
        tenant: TenantId,
        plan: Plan,
        versionId: UUID,
        body: InputStream,
        spoolFile: Path,
        progress: Progress,
    ): Received {
        val digest = MessageDigest.getInstance("SHA-256")
        val verdict =
            scanner.open().use { scan ->
                FileChannel.open(spoolFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { file ->
                    crypto.encrypting(tenant, plan.fileId, versionId, file).use { encrypted ->
                        pump(body, plan.maxBytes, progress) { buffer, n ->
                            digest.update(buffer, 0, n)
                            scan.write(buffer, 0, n)
                            encrypted.writeFully(ByteBuffer.wrap(buffer, 0, n))
                        }
                    }
                }
                scan.finish()
            }
        when (verdict) {
            is ScanVerdict.Clean -> Unit
            is ScanVerdict.Infected -> reject(RejectionReason.MALWARE, "signature" to verdict.signature)
            is ScanVerdict.Failed -> reject(RejectionReason.SCAN_UNAVAILABLE, "scanner" to verdict.reason)
        }
        return Received(progress.bytes, digest.digest(), scanner.engine())
    }

    /** Reads [body] to the end in chunks, refusing it as soon as it exceeds [maxBytes]. */
    private fun pump(
        body: InputStream,
        maxBytes: Long,
        progress: Progress,
        sink: (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(CHUNK)
        var n = read(body, buffer)
        while (n >= 0) {
            progress.bytes += n
            if (progress.bytes > maxBytes) reject(RejectionReason.TOO_LARGE, "maxBytes" to maxBytes)
            sink(buffer, n)
            n = read(body, buffer)
        }
    }

    private fun read(
        body: InputStream,
        buffer: ByteArray,
    ): Int =
        try {
            body.read(buffer)
        } catch (e: IOException) {
            throw UploadIncompleteException(e)
        }

    private fun objectKey(tenant: TenantId): String {
        val bytes = ByteArray(OBJECT_KEY_BYTES).also { random.nextBytes(it) }
        return "t/$tenant/${base32(bytes)}"
    }

    private class Progress {
        var bytes = 0L
    }

    private data class Plan(
        val purpose: FilePurpose,
        val fileId: UUID,
        val isNew: Boolean,
        val expectedVersion: Long,
        val ownerId: String?,
        val maxBytes: Long,
        val retention: RecordRetention?,
    )

    private data class RecordRetention(
        val mode: RetentionMode,
        val years: Int,
    ) {
        /** Until the business transaction is through: a lock that expires if it never is. */
        fun provisional(now: Instant) = Retention(mode, now.plus(PROVISIONAL_LOCK).toLockPrecision())

        fun final(now: Instant) =
            Retention(
                mode,
                now
                    .atZone(ZoneOffset.UTC)
                    .plusYears(years.toLong())
                    .toInstant()
                    .toLockPrecision(),
            )
    }

    private class Received(
        val size: Long,
        val sha256: ByteArray,
        val engine: String,
    )

    companion object {
        const val MAX_BYTES_SETTING = "files.upload.max.bytes"
        const val RETENTION_MODE_SETTING = "files.records.retention.mode"
        const val RETENTION_YEARS_SETTING = "files.records.retention.years"
        const val DEFAULT_RETENTION_YEARS = 10
        private const val IDEMPOTENCY_OPERATION = "files.upload"
        private const val CIPHERTEXT_TYPE = "application/octet-stream"
        private const val CHUNK = 64 * 1024
        private const val OBJECT_KEY_BYTES = 16

        /** Long enough to outlive any outage of the finaliser job; short enough that an orphan does not linger. */
        val PROVISIONAL_LOCK: Duration = Duration.ofDays(7)

        private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
        private const val BASE32_BITS = 5
        private const val BASE32_MASK = 0x1F
        private const val BYTE_MASK = 0xFF

        /** RFC 4648 base32, lower case, no padding: 16 bytes give 26 characters. */
        internal fun base32(bytes: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl Byte.SIZE_BITS) or (b.toInt() and BYTE_MASK)
                bits += Byte.SIZE_BITS
                while (bits >= BASE32_BITS) {
                    out.append(BASE32[(buffer shr (bits - BASE32_BITS)) and BASE32_MASK])
                    bits -= BASE32_BITS
                }
            }
            if (bits > 0) out.append(BASE32[(buffer shl (BASE32_BITS - bits)) and BASE32_MASK])
            return out.toString()
        }
    }
}

private fun WritableByteChannel.writeFully(chunk: ByteBuffer) {
    while (chunk.hasRemaining()) write(chunk)
}

/** Object lock keeps whole seconds: round up, so the lock never ends before the date the database records. */
private fun Instant.toLockPrecision(): Instant = truncatedTo(ChronoUnit.SECONDS).let { if (it == this) it else it.plusSeconds(1) }

/** The value stored in `files_version.bucket` (configured bucket names may differ). */
internal val Bucket.stored: String get() = name.lowercase()

internal fun bucketOf(stored: String): Bucket = Bucket.valueOf(stored.uppercase())

/** clamd behind the upload pipeline: fails closed when it is not configured or unreachable, caches the engine version. */
@Component
internal class Scanner(
    private val clamd: ClamdClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val version = AtomicReference<Pair<Instant, String>?>()

    fun open(): ClamdStream {
        if (!clamd.enabled) reject(RejectionReason.SCAN_UNAVAILABLE, "scanner" to "not configured")
        return try {
            clamd.open()
        } catch (e: IOException) {
            log.warn("Malware scanner unreachable: {}", e.javaClass.simpleName)
            reject(RejectionReason.SCAN_UNAVAILABLE, "scanner" to "unreachable", cause = e)
        }
    }

    /** Engine and signature version, refreshed every few minutes; the last known (or "clamd") when it cannot be read. */
    fun engine(): String {
        val cached = version.get()
        if (cached == null || !cached.first.isAfter(clock.instant())) {
            runCatching { clamd.version() }.getOrNull()?.let { version.set(clock.instant().plus(VERSION_TTL) to it.take(MAX_ENGINE)) }
        }
        return version.get()?.second ?: "clamd"
    }

    private companion object {
        val VERSION_TTL: Duration = Duration.ofMinutes(5)
        const val MAX_ENGINE = 200
    }
}

/** Moves a record version's lock from provisional to final, after the business transaction (and from the job). */
@Component
internal class Retentions(
    private val objects: ObjectStore,
    private val store: FileStore,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Best effort: on a storage failure the version stays PROVISIONAL and the finaliser job retries it. */
    fun finalise(
        tenant: TenantId,
        versionId: UUID,
        objectKey: String,
        objectVersion: String,
        retention: Retention,
    ): Boolean {
        val extended =
            try {
                objects.extendRetention(objectKey, objectVersion, retention)
                true
            } catch (e: SdkException) {
                log.warn("Retention of file version {} is still provisional: {}", versionId, e.javaClass.simpleName)
                false
            } catch (e: StorageUnavailableException) {
                log.warn("Retention of file version {} is still provisional: {}", versionId, e.message)
                false
            }
        if (!extended) return false
        transactions.inTenant(tenant) {
            if (store.markFinal(versionId)) {
                audit.record(
                    "file.retention.finalised",
                    "file.version",
                    versionId.toString(),
                    after = mapOf("retainUntil" to retention.until.toString(), "mode" to retention.mode.name),
                )
            }
        }
        return true
    }
}
