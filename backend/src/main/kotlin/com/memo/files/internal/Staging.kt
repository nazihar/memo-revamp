package com.memo.files.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.storage.Bucket
import com.memo.common.storage.ObjectStore
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FilePurges
import com.memo.files.RenditionState
import com.memo.files.StorageClass
import com.memo.jooq.tables.references.FILES_FILE
import com.memo.jooq.tables.references.FILES_RENDITION
import com.memo.jooq.tables.references.FILES_VERSION
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.time.Clock
import java.util.UUID

/**
 * Purging of staged content (ADR-0055, design E06 §3.3): the version is marked purged and audited in one transaction,
 * then its object is deleted from `work`. A crash in between leaves a marked version whose object a retry deletes.
 */
@Component
internal class Purges(
    private val store: FileStore,
    private val purposes: Purposes,
    private val tenantDsl: TenantDsl,
    private val transactions: TenantTransactions,
    private val objects: ObjectStore,
    private val audit: AuditLog,
    private val clock: Clock,
) : FilePurges {
    override fun purge(versionId: UUID): Boolean {
        val tenant = TenantContext.current()
        val user = (ActorContext.current().actor as? Actor.User)?.userId
        val (stored, purged) = transactions.inTenant(tenant) { mark(versionId, user) }
        objects.deleteAllVersions(bucketOf(stored.bucket), stored.objectKey)
        return purged
    }

    private fun mark(
        versionId: UUID,
        user: UUID?,
    ): Pair<StoredObject, Boolean> {
        val version = requireNotNull(store.version(versionId)) { "unknown file version" }
        val info = requireNotNull(store.info(version.fileId)) { "unknown file" }
        val purpose = purposes[info.purpose]
        check(purpose != null && purpose.purgeable && purpose.storage == StorageClass.ORDINARY) {
            "file purpose '${info.purpose}' is not purgeable"
        }
        val stored = requireNotNull(store.storedObject(versionId))
        check(stored.bucket == Bucket.WORK.stored) { "only ordinary content is purged" }
        val purged =
            tenantDsl
                .current()
                .update(FILES_VERSION)
                .set(FILES_VERSION.PURGED_AT, clock.instant().utc())
                .set(FILES_VERSION.PURGED_BY, user)
                .where(FILES_VERSION.ID.eq(versionId), FILES_VERSION.PURGED_AT.isNull)
                .execute() == 1
        if (purged) {
            audit.record(
                "file.version.purged",
                "file.version",
                versionId.toString(),
                after =
                    mapOf(
                        "fileId" to info.id.toString(),
                        "purpose" to info.purpose,
                        "owner" to info.ownerId,
                        "sizeBytes" to version.sizeBytes,
                        "sha256" to version.sha256,
                    ),
            )
        }
        return stored to purged
    }
}

/** Counts of pending renditions (ADR-0045), for owners that wait for them or hold back (an import's throttle). */
@Component
internal class RenditionBacklog(
    private val tenantDsl: TenantDsl,
) {
    /** Pending renditions of files of [purposeKey], counted up to [atMost]: the count stops there, so it stays cheap. */
    fun pending(
        purposeKey: String,
        atMost: Int,
    ): Int {
        val dsl = tenantDsl.current()
        val pending =
            dsl
                .select(DSL.inline(1))
                .from(FILES_RENDITION)
                .join(FILES_VERSION)
                .on(FILES_VERSION.TENANT_ID.eq(FILES_RENDITION.TENANT_ID), FILES_VERSION.ID.eq(FILES_RENDITION.VERSION_ID))
                .join(FILES_FILE)
                .on(FILES_FILE.TENANT_ID.eq(FILES_VERSION.TENANT_ID), FILES_FILE.ID.eq(FILES_VERSION.FILE_ID))
                .where(FILES_RENDITION.STATE.eq(RenditionState.PENDING.name), FILES_FILE.PURPOSE.eq(purposeKey))
                .limit(atMost)
        return dsl.fetchCount(pending)
    }

    /** Pending renditions of [versionIds]. */
    fun pending(versionIds: Collection<UUID>): Int =
        if (versionIds.isEmpty()) {
            0
        } else {
            tenantDsl.current().fetchCount(
                FILES_RENDITION,
                FILES_RENDITION.STATE.eq(RenditionState.PENDING.name).and(FILES_RENDITION.VERSION_ID.`in`(versionIds)),
            )
        }
}

/**
 * The plaintext of a stored version as the body of a copy (FileUploads.copyFrom), opened on first read. Content that
 * fails authentication cannot be the content expected, so it refuses the copy as `HASH_MISMATCH` (audited by the
 * pipeline) rather than as an interrupted upload.
 */
internal class CopySource(
    private val tenant: TenantId,
    private val stored: StoredObject,
    private val objects: ObjectStore,
    private val crypto: FileCrypto,
) : InputStream() {
    private var input: InputStream? = null

    override fun read(): Int = guarded { it.read() }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = guarded { it.read(b, off, len) }

    override fun close() {
        input?.close()
    }

    private fun guarded(read: (InputStream) -> Int): Int =
        try {
            read(input ?: open().also { input = it })
        } catch (e: IOException) {
            reject(RejectionReason.HASH_MISMATCH, "source" to stored.versionId.toString(), "problem" to "integrity", cause = e)
        }

    private fun open(): InputStream {
        val ciphertext =
            objects.channel(bucketOf(stored.bucket), stored.objectKey, stored.objectVersion, stored.ciphertextBytes, READ_CHUNK)
        return try {
            Channels.newInputStream(crypto.decrypting(tenant, stored.fileId, stored.versionId, ciphertext))
        } catch (e: IOException) {
            ciphertext.close()
            throw e
        }
    }

    private companion object {
        const val READ_CHUNK = 16 * TenantKeysets.SEGMENT_BYTES
    }
}
