package com.memorevamp.system.health.probes

import com.memorevamp.system.health.ComponentStatus
import com.memorevamp.testsupport.randomSecret
import com.memorevamp.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.time.Duration

/**
 * SeaweedFS + the real bucket bootstrap script (ADR-0005), exactly as Compose runs them.
 * Proves: object lock on `records`, least privilege for the app identity (threat model E2), probe UP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectStorageIT {
    private val adminKey = randomSecret()
    private val adminSecret = randomSecret()
    private val appKey = randomSecret()
    private val appSecret = randomSecret()
    private val network = Network.newNetwork()

    private val seaweed: GenericContainer<*> =
        GenericContainer("chrislusf/seaweedfs:4.48")
            .withNetwork(network)
            .withNetworkAliases("seaweedfs")
            .withEnv(
                mapOf(
                    "S3_ADMIN_ACCESS_KEY" to adminKey,
                    "S3_ADMIN_SECRET_KEY" to adminSecret,
                    "S3_APP_ACCESS_KEY" to appKey,
                    "S3_APP_SECRET_KEY" to appSecret,
                ),
            ).withCopyFileToContainer(MountableFile.forHostPath(repoRoot.resolve("deploy/seaweedfs/start.sh")), "/seaweedfs/start.sh")
            .withCreateContainerCmdModifier { it.withEntrypoint("/bin/sh", "/seaweedfs/start.sh") }
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/healthz").forPort(8333).withStartupTimeout(Duration.ofMinutes(2)))

    private lateinit var endpoint: URI

    @BeforeAll
    fun start() {
        seaweed.start()
        endpoint = URI.create("http://${seaweed.host}:${seaweed.getMappedPort(8333)}")
        repeat(2) { runStorageInit() } // second run proves idempotency
    }

    @AfterAll
    fun stop() {
        seaweed.stop()
        network.close()
    }

    private fun runStorageInit() {
        GenericContainer("amazon/aws-cli:2.37.13")
            .withNetwork(network)
            .withEnv(
                mapOf(
                    "AWS_ACCESS_KEY_ID" to adminKey,
                    "AWS_SECRET_ACCESS_KEY" to adminSecret,
                    "AWS_DEFAULT_REGION" to "us-east-1",
                    "S3_ENDPOINT" to "http://seaweedfs:8333",
                ),
            ).withCopyFileToContainer(
                MountableFile.forHostPath(repoRoot.resolve("deploy/seaweedfs/storage-init.sh")),
                "/init/storage-init.sh",
            ).withCreateContainerCmdModifier { it.withEntrypoint("/bin/sh", "/init/storage-init.sh") }
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
            .use { it.start() }
    }

    private fun client(
        key: String,
        secret: String,
    ): S3Client =
        S3Client
            .builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
            .build()

    @Test
    fun `records bucket has object lock enabled`() {
        client(adminKey, adminSecret).use { s3 ->
            val config = s3.getObjectLockConfiguration { it.bucket("records") }.objectLockConfiguration()
            assertThat(config.objectLockEnabled()).isEqualTo(ObjectLockEnabled.ENABLED)
        }
    }

    @Test
    fun `app identity cannot change object lock or administer buckets`() {
        client(appKey, appSecret).use { s3 ->
            assertThatThrownBy {
                s3.putObjectLockConfiguration {
                    it.bucket("records").objectLockConfiguration { c -> c.objectLockEnabled(ObjectLockEnabled.ENABLED) }
                }
            }.isInstanceOf(S3Exception::class.java)
            assertThatThrownBy { s3.createBucket { it.bucket("evil") } }.isInstanceOf(S3Exception::class.java)
            assertThatThrownBy { s3.deleteBucket { it.bucket("work") } }.isInstanceOf(S3Exception::class.java)
        }
    }

    @Test
    fun `probe is UP with app credentials and DOWN with wrong ones`() {
        val good = StorageProperties(endpoint = endpoint.toString(), accessKey = appKey, secretKey = appSecret)
        ObjectStorageProbe(good).use { assertThat(it.check()).isEqualTo(ComponentStatus.UP) }

        val bad = good.copy(secretKey = "wrong-${randomSecret()}")
        ObjectStorageProbe(bad).use { assertThat(it.check()).isEqualTo(ComponentStatus.DOWN) }
    }

    @Test
    fun `storage properties never print the secret`() {
        val props = StorageProperties(endpoint = endpoint.toString(), accessKey = appKey, secretKey = appSecret)
        assertThat(props.toString()).doesNotContain(appSecret).doesNotContain(appKey)
    }
}
