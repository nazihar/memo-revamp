package com.memo.common.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Object storage settings (ADR-0005). Storage is disabled when the endpoint or credentials are missing. The `files` module
 * (E05) takes over storage; until then the audit anchors (ADR-0019) and the health probe use these.
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

/**
 * Writes immutable records into the object-locked `records` bucket and reads them back. Never hands out URLs: storage is
 * reached only by the backend (AGENTS.md §2).
 */
@Component
class RecordStore(
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
                        .connectionTimeout(TIMEOUT)
                        .socketTimeout(TIMEOUT)
                        .build(),
                ).build()
        } else {
            null
        }

    val enabled: Boolean get() = client != null

    /** Stores [body] under [key] with object-lock retention until [retainUntil]. */
    fun putLocked(
        key: String,
        body: ByteArray,
        contentType: String,
        retainUntil: Instant,
        mode: RetentionMode,
    ) {
        val s3 = client ?: throw IllegalStateException("Object storage is not configured")
        s3.putObject(
            {
                it
                    .bucket(properties.recordsBucket)
                    .key(key)
                    .contentType(contentType)
                    .objectLockMode(ObjectLockMode.fromValue(mode.name))
                    .objectLockRetainUntilDate(retainUntil)
            },
            RequestBody.fromBytes(body),
        )
    }

    /** The current version of [key], or null if there is none. */
    fun get(key: String): ByteArray? {
        val s3 = client ?: throw IllegalStateException("Object storage is not configured")
        return try {
            s3.getObjectAsBytes { it.bucket(properties.recordsBucket).key(key) }.asByteArray()
        } catch (_: software.amazon.awssdk.services.s3.model.NoSuchKeyException) {
            null
        }
    }

    /** The first key under [prefix] in lexicographic order, or null. */
    fun firstKey(prefix: String): String? {
        val s3 = client ?: throw IllegalStateException("Object storage is not configured")
        return s3
            .listObjectsV2 { it.bucket(properties.recordsBucket).prefix(prefix).maxKeys(1) }
            .contents()
            .firstOrNull()
            ?.key()
    }

    override fun close() {
        client?.close()
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
