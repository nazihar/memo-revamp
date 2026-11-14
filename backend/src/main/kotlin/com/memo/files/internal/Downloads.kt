package com.memo.files.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.storage.Bucket
import com.memo.common.storage.ObjectStore
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileVersionInfo
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.ContentDisposition
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** A version the actor may read, with what streaming it needs; resolved in one short transaction. */
internal data class Readable(
    val version: FileVersionInfo,
    val purpose: FilePurpose,
    val stored: StoredObject,
) {
    /** Strong validator: the version and its content. */
    val etag: String get() = "\"${version.id}.${version.sha256.take(ETAG_HASH_CHARS)}\""

    val disposition: String
        get() {
            val type = FileType.ofMediaType(version.mediaType)
            val inline = type != null && type in FileType.INLINE_SAFE && type in purpose.inlineTypes
            return (if (inline) ContentDisposition.inline() else ContentDisposition.attachment())
                .filename(version.name, Charsets.UTF_8)
                .build()
                .toString()
        }

    private companion object {
        const val ETAG_HASH_CHARS = 16
    }
}

/**
 * Downloads (ADR-0029): access and the open audit in a short transaction, then streaming outside any transaction,
 * through a seekable decrypting channel over ranged reads of the ciphertext.
 */
@Component
internal class Downloads(
    private val store: FileStore,
    private val purposes: Purposes,
    private val transactions: TenantTransactions,
    private val objects: ObjectStore,
    private val crypto: FileCrypto,
    private val audit: AuditLog,
    private val clock: Clock,
    private val renditions: RenditionStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The [kind] rendition of a version the actor may read (ADR-0045), when it is done; null otherwise. The read is audited
     * like the original, naming the rendition.
     */
    fun openRendition(
        fileId: UUID,
        versionId: UUID,
        kind: RenditionKind,
        session: HttpSession?,
    ): Pair<FileVersionInfo, RenditionRow>? =
        transactions.inTenant(TenantContext.current()) {
            val (version, purpose) = readableVersion(fileId, versionId)
            val row = renditions.get(versionId, kind)?.takeIf { it.state == RenditionState.DONE }
            if (row != null && purpose.auditReads && OpenMarks.firstOpen(session, markOf(versionId, kind), clock.millis())) {
                audit.record(
                    "file.opened",
                    "file.version",
                    versionId.toString(),
                    after = mapOf("fileId" to fileId.toString(), "purpose" to purpose.key, "rendition" to kind.name),
                )
            }
            row?.let { version to it }
        }

    /** The renditions of a version the actor may read. */
    fun renditionsOf(
        fileId: UUID,
        versionId: UUID,
    ): Pair<FileVersionInfo, List<RenditionRow>> =
        transactions.inTenant(TenantContext.current()) {
            val (version, _) = readableVersion(fileId, versionId)
            version to renditions.of(versionId)
        }

    private fun readableVersion(
        fileId: UUID,
        versionId: UUID,
    ): Pair<FileVersionInfo, FilePurpose> {
        val info = store.info(fileId)
        val purpose = info?.let { purposes[it.purpose] }
        val version = store.version(versionId)?.takeIf { it.fileId == fileId }
        if (info == null || purpose == null || version == null) throw FileNotFoundException()
        if (!purpose.mayRead(info)) throw ForbiddenException(null)
        return version to purpose
    }

    /** Writes [length] plaintext bytes of a done rendition from [first]; a failed authentication aborts the response. */
    fun streamRendition(
        row: RenditionRow,
        first: Long,
        length: Long,
        out: OutputStream,
    ) {
        val tenant = TenantContext.current()
        val ciphertext = objects.channel(Bucket.WORK, row.objectKey!!, null, row.ciphertextBytes!!, READ_CHUNK)
        val plaintext =
            try {
                crypto.decryptingRendition(tenant, row.versionId, row.kind.name, ciphertext)
            } catch (e: IOException) {
                ciphertext.close()
                throw FileIntegrityException(e)
            }
        plaintext.use { channel ->
            channel.position(first)
            val buffer = ByteBuffer.allocate(COPY_BUFFER)
            var remaining = length
            while (remaining > 0) {
                buffer.clear().limit(minOf(COPY_BUFFER.toLong(), remaining).toInt())
                val n = channel.read(buffer)
                if (n < 0) throw FileIntegrityException(IOException("rendition ended early"))
                if (!writeOrStop(out, buffer.array(), n)) return
                remaining -= n
            }
        }
    }

    /** A marker per version and kind, so opening the preview and the original are audited separately. */
    private fun markOf(
        versionId: UUID,
        kind: RenditionKind,
    ): UUID = UUID.nameUUIDFromBytes("$versionId/${kind.name}".toByteArray(Charsets.UTF_8))

    /**
     * The version of [fileId] ([versionId], or the current one) if the actor may read it; audits the open when the purpose
     * asks for it and [session] has not opened this version in the last [OPEN_WINDOW] (first range or whole download alike).
     */
    fun open(
        fileId: UUID,
        versionId: UUID?,
        session: HttpSession?,
        rangeStart: Long?,
    ): Readable =
        transactions.inTenant(TenantContext.current()) {
            val info = store.info(fileId)
            val purpose = info?.let { purposes[it.purpose] } ?: throw FileNotFoundException()
            if (!purpose.mayRead(info)) throw ForbiddenException(null)
            val version = (versionId ?: info.currentVersionId)?.let(store::version)?.takeIf { it.fileId == fileId }
            val stored = version?.let { store.storedObject(it.id) } ?: throw FileNotFoundException()
            if (purpose.auditReads && OpenMarks.firstOpen(session, version.id, clock.millis())) {
                audit.record(
                    "file.opened",
                    "file.version",
                    version.id.toString(),
                    after = mapOf("fileId" to fileId.toString(), "purpose" to purpose.key, "rangeStart" to (rangeStart ?: 0L)),
                )
            }
            Readable(version, purpose, stored)
        }

    /**
     * Writes [length] plaintext bytes from [first] to [out]. A segment that fails authentication aborts the response (the
     * client sees a broken transfer, never wrong bytes) and is audited; a client that goes away just ends the copy.
     */
    fun stream(
        readable: Readable,
        first: Long,
        length: Long,
        out: OutputStream,
    ) {
        val tenant = TenantContext.current()
        val stored = readable.stored
        val ciphertext =
            objects.channel(
                bucketOf(stored.bucket),
                stored.objectKey,
                stored.objectVersion,
                stored.ciphertextBytes,
                READ_CHUNK,
            )
        val plaintext =
            try {
                crypto.decrypting(tenant, stored.fileId, stored.versionId, ciphertext)
            } catch (e: IOException) {
                ciphertext.close()
                throw integrityFailure(tenant, readable, 0, e)
            }
        plaintext.use { copy(it, tenant, readable, first, length, out) }
    }

    private fun copy(
        channel: SeekableByteChannel,
        tenant: TenantId,
        readable: Readable,
        first: Long,
        length: Long,
        out: OutputStream,
    ) {
        channel.position(first)
        val buffer = ByteBuffer.allocate(COPY_BUFFER)
        var remaining = length
        while (remaining > 0) {
            buffer.clear().limit(minOf(COPY_BUFFER.toLong(), remaining).toInt())
            val n =
                try {
                    channel.read(buffer)
                } catch (e: IOException) {
                    throw integrityFailure(tenant, readable, channel.position(), e)
                }
            if (n < 0) throw integrityFailure(tenant, readable, channel.position(), IOException("ciphertext ended early"))
            if (!writeOrStop(out, buffer.array(), n)) return
            remaining -= n
        }
    }

    /** False when the client has gone away: nothing to report, the copy just ends. */
    private fun writeOrStop(
        out: OutputStream,
        bytes: ByteArray,
        n: Int,
    ): Boolean =
        try {
            out.write(bytes, 0, n)
            true
        } catch (e: IOException) {
            log.debug("Download ended by the client: {}", e.javaClass.simpleName)
            false
        }

    private fun integrityFailure(
        tenant: TenantId,
        readable: Readable,
        offset: Long,
        cause: IOException,
    ): FileIntegrityException {
        log.error("File version {} failed authentication at offset {}", readable.version.id, offset)
        transactions.inTenant(tenant) {
            audit.record("file.integrity.failed", "file.version", readable.version.id.toString(), after = mapOf("offset" to offset))
        }
        return FileIntegrityException(cause)
    }

    companion object {
        /** Ranged reads of two ciphertext segments: a 64 KiB plaintext range never needs more than one request. */
        private const val READ_CHUNK = 2 * TenantKeysets.SEGMENT_BYTES
        private const val COPY_BUFFER = 64 * 1024
        val OPEN_WINDOW: Duration = Duration.ofMinutes(30)
    }
}

/** Stored content failed authentication: the response is aborted, never completed with wrong bytes. */
internal class FileIntegrityException(
    cause: IOException,
) : IOException("file content failed authentication", cause)

/**
 * Which versions a session opened recently, kept in the server-side session (Spring Session JDBC), so the marker holds
 * across nodes and the client cannot reset it. One string attribute: `versionId:epochMillis` entries, newest last.
 */
internal object OpenMarks {
    private const val ATTRIBUTE = "memo.files.opened"
    private const val MAX_ENTRIES = 50

    /** Whether this is the first open of [version] in [session] within the window; records it. No session: always. */
    fun firstOpen(
        session: HttpSession?,
        version: UUID,
        nowMillis: Long,
    ): Boolean {
        if (session == null) return true
        val since = nowMillis - Downloads.OPEN_WINDOW.toMillis()
        val marks =
            (session.getAttribute(ATTRIBUTE) as? String)
                .orEmpty()
                .split(',')
                .mapNotNull { entry -> entry.split(':').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } }
                .filter { it.second >= since }
        val first = marks.none { it.first == version.toString() }
        val kept = if (first) marks + (version.toString() to nowMillis) else marks
        session.setAttribute(ATTRIBUTE, kept.takeLast(MAX_ENTRIES).joinToString(",") { "${it.first}:${it.second}" })
        return first
    }
}
