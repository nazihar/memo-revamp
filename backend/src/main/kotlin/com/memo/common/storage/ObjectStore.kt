package com.memo.common.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Object storage settings (ADR-0005). Storage is disabled when the endpoint or credentials are missing; then every
 * operation fails with [StorageUnavailableException].
 */
@ConfigurationProperties("memo.storage")
data class StorageProperties(
    val endpoint: String = "",
    val region: String = "us-east-1",
    val accessKey: String = "",
    val secretKey: String = "",
    val recordsBucket: String = "records",
    val workBucket: String = "work",
) {
    /** Configured only when endpoint *and* credentials are present; a partial configuration is reported as disabled. */
    val enabled: Boolean get() = endpoint.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()

    override fun toString() = "StorageProperties(endpoint=$endpoint, region=$region, buckets=[$recordsBucket, $workBucket])"
}

/** Retention mode of a locked record: GOVERNANCE (an S3 admin may lift it) or COMPLIANCE (nobody may, until it expires). */
enum class RetentionMode { GOVERNANCE, COMPLIANCE }

/** The two buckets of ADR-0005: object-locked, versioned `records` and ordinary `work`. */
enum class Bucket { RECORDS, WORK }

/** Object-lock retention for an object in [Bucket.RECORDS]. */
data class Retention(
    val mode: RetentionMode,
    val until: Instant,
)

/** Object storage is not configured on this installation. */
class StorageUnavailableException : IllegalStateException("Object storage is not configured")

/**
 * The backend's only door to object storage (AGENTS.md §2): it never hands out URLs, and it reads ranges so that files
 * stream through the backend. Record objects are written with object-lock retention; the application's S3 identity can
 * set and extend retention but never shorten or bypass it (ADR-0005).
 */
@Component
class ObjectStore(
    private val properties: StorageProperties,
) : AutoCloseable {
    private val client: S3Client? =
        if (properties.enabled) {
            S3Client
                .builder()
                .endpointOverride(URI.create(properties.endpoint))
                .region(Region.of(properties.region))
                .forcePathStyle(true)
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey)),
                ).httpClient(
                    UrlConnectionHttpClient
                        .builder()
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .socketTimeout(SOCKET_TIMEOUT)
                        .build(),
                )
                // We send Content-MD5 ourselves (object-lock PUTs require it); no SDK checksum headers on top, which
                // SeaweedFS rejects alongside it.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build()
        } else {
            null
        }

    val enabled: Boolean get() = client != null

    private val s3: S3Client get() = client ?: throw StorageUnavailableException()

    private val buckets = mapOf(Bucket.RECORDS to properties.recordsBucket, Bucket.WORK to properties.workBucket)

    private fun name(bucket: Bucket): String = buckets.getValue(bucket)

    /** Stores [body] under [key] in `records` with object-lock retention until [retainUntil]. */
    fun putLocked(
        key: String,
        body: ByteArray,
        contentType: String,
        retainUntil: Instant,
        mode: RetentionMode,
    ) {
        s3.putObject(
            request(Bucket.RECORDS, key, contentType, md5(body.inputStream()), Retention(mode, retainUntil)),
            RequestBody.fromBytes(body),
        )
    }

    /**
     * Streams [file] to [key] in [bucket]. Objects in [Bucket.RECORDS] need a [retention]; objects in [Bucket.WORK] must not
     * have one. Reads the file twice: once for the transfer checksum that object-lock PUTs require, once to send it.
     */
    fun put(
        bucket: Bucket,
        key: String,
        file: Path,
        contentType: String,
        retention: Retention?,
    ) {
        require((bucket == Bucket.RECORDS) == (retention != null)) { "records objects are locked, work objects are not" }
        val md5 = Files.newInputStream(file).use(::md5)
        s3.putObject(request(bucket, key, contentType, md5, retention), RequestBody.fromFile(file))
    }

    /** Sets the retention of the current version of [key] in `records`; storage refuses to shorten it. */
    fun extendRetention(
        key: String,
        retention: Retention,
    ) {
        s3.putObjectRetention {
            it
                .bucket(properties.recordsBucket)
                .key(key)
                .retention { r -> r.mode(ObjectLockRetentionMode.fromValue(retention.mode.name)).retainUntilDate(retention.until) }
        }
    }

    /** Reads up to [length] bytes of [key] starting at [offset]; fewer only at the end of the object. */
    fun readRange(
        bucket: Bucket,
        key: String,
        offset: Long,
        length: Int,
    ): ByteArray {
        require(offset >= 0 && length > 0)
        return s3
            .getObject { it.bucket(name(bucket)).key(key).range("bytes=$offset-${offset + length - 1}") }
            .use { it.readAllBytes() }
    }

    /** A read-only channel over [key] of known [size], fetching [chunk]-byte ranges on demand. */
    fun channel(
        bucket: Bucket,
        key: String,
        size: Long,
        chunk: Int,
    ): SeekableByteChannel = RangeReadChannel(this, bucket, key, size, chunk)

    /**
     * Deletes every version of [key]. In `work` that removes the object; in `records` storage refuses versions whose
     * retention has not expired, so this only clears objects whose (provisional) lock is over.
     */
    fun deleteAllVersions(
        bucket: Bucket,
        key: String,
    ) {
        val bucketName = name(bucket)
        if (bucket == Bucket.WORK) {
            s3.deleteObject { it.bucket(bucketName).key(key) }
            return
        }
        s3
            .listObjectVersions { it.bucket(bucketName).prefix(key) }
            .let { listing ->
                listing.versions().map { it.key() to it.versionId() } +
                    listing.deleteMarkers().map { it.key() to it.versionId() }
            }.filter { (k, _) -> k == key }
            .forEach { (k, version) -> s3.deleteObject { it.bucket(bucketName).key(k).versionId(version) } }
    }

    /** The current version of [key] in `records`, or null if there is none. */
    fun get(key: String): ByteArray? =
        try {
            s3.getObjectAsBytes { it.bucket(properties.recordsBucket).key(key) }.asByteArray()
        } catch (_: NoSuchKeyException) {
            null
        }

    /** The first key under [prefix] in `records` in lexicographic order, or null. */
    fun firstKey(prefix: String): String? =
        s3
            .listObjectsV2 { it.bucket(properties.recordsBucket).prefix(prefix).maxKeys(1) }
            .contents()
            .firstOrNull()
            ?.key()

    override fun close() {
        client?.close()
    }

    private fun request(
        bucket: Bucket,
        key: String,
        contentType: String,
        md5: String,
        retention: Retention?,
    ): PutObjectRequest =
        PutObjectRequest
            .builder()
            .bucket(name(bucket))
            .key(key)
            .contentType(contentType)
            .contentMD5(md5)
            .apply {
                if (retention != null) {
                    objectLockMode(ObjectLockMode.fromValue(retention.mode.name))
                    objectLockRetainUntilDate(retention.until)
                }
            }.build()

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        // Inactivity on the socket, not the duration of a whole transfer.
        val SOCKET_TIMEOUT: Duration = Duration.ofSeconds(60)

        /**
         * Transfer checksum required on object-lock PUTs, not a security control: integrity rests on authenticated
         * encryption (ADR-0028) and the SHA-256 audit chain (ADR-0019). SeaweedFS rejects the SDK's default checksum here.
         */
        fun md5(input: InputStream): String {
            // nosemgrep: kotlin.lang.security.use-of-md5.use-of-md5
            val digest = MessageDigest.getInstance("MD5")
            DigestInputStream(input, digest).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
            return Base64.getEncoder().encodeToString(digest.digest())
        }
    }
}

/**
 * A read-only [SeekableByteChannel] over one object of known size. Reads are served from a buffer that is refilled with
 * one ranged GET of [chunk] bytes at the current position, so sequential reads cost one request per chunk.
 */
internal class RangeReadChannel(
    private val store: ObjectStore,
    private val bucket: Bucket,
    private val key: String,
    private val size: Long,
    private val chunk: Int,
) : SeekableByteChannel {
    private var position = 0L
    private var open = true
    private var bufferStart = 0L
    private var buffer = ByteArray(0)

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (position < size && (position < bufferStart || position >= bufferStart + buffer.size)) {
            bufferStart = position
            buffer = store.readRange(bucket, key, position, minOf(chunk.toLong(), size - position).toInt())
        }
        val from = position - bufferStart
        if (position >= size || from >= buffer.size) return -1
        val count = minOf(dst.remaining(), buffer.size - from.toInt())
        dst.put(buffer, from.toInt(), count)
        position += count
        return count
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0)
        position = newPosition
        return this
    }

    override fun size(): Long = size

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }
}
