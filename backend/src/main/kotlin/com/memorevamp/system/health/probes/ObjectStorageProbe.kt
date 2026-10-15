package com.memorevamp.system.health.probes

import com.memorevamp.system.health.ComponentProbe
import com.memorevamp.system.health.ComponentStatus
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

/**
 * Object storage settings. Ownership moves to the `files` module in Phase 1a, which will expose its own health API.
 * Storage is disabled when [endpoint] is blank.
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
    val enabled: Boolean get() = endpoint.isNotBlank()

    override fun toString() = "StorageProperties(endpoint=$endpoint, region=$region, buckets=[$recordsBucket, $workBucket])"
}

@Component
class ObjectStorageProbe(
    private val properties: StorageProperties,
) : ComponentProbe,
    AutoCloseable {
    override val name = "objectStorage"

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

    override fun check(): ComponentStatus {
        val s3 = client ?: return ComponentStatus.DISABLED
        return try {
            listOf(properties.recordsBucket, properties.workBucket).forEach { bucket -> s3.headBucket { it.bucket(bucket) } }
            ComponentStatus.UP
        } catch (_: SdkException) {
            ComponentStatus.DOWN
        }
    }

    override fun close() {
        client?.close()
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}
