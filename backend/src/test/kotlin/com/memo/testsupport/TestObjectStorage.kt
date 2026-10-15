package com.memo.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

/**
 * One SeaweedFS per test JVM, started and bootstrapped with the same scripts as Compose (`deploy/seaweedfs/`, ADR-0005):
 * object-locked `records` bucket, plain `work` bucket, `admin` and least-privilege `app` identities with random keys.
 */
object TestObjectStorage {
    const val IMAGE = "chrislusf/seaweedfs:4.48"
    const val AWS_CLI_IMAGE = "amazon/aws-cli:2.37.13"

    val adminKey = randomSecret()
    val adminSecret = randomSecret()
    val appKey = randomSecret()
    val appSecret = randomSecret()

    private val network: Network by lazy { Network.newNetwork() }

    private val container: GenericContainer<*> by lazy {
        GenericContainer(IMAGE)
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
            .withExposedPorts(S3_PORT)
            .waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT).withStartupTimeout(Duration.ofMinutes(2)))
            .also {
                it.start()
                bootstrap()
            }
    }

    val endpoint: URI by lazy { URI.create("http://${container.host}:${container.getMappedPort(S3_PORT)}") }

    /** Runs the real, idempotent bucket bootstrap script with the admin identity (starts the container if needed). */
    fun runStorageInit() {
        container
        bootstrap()
    }

    private fun bootstrap() {
        GenericContainer(AWS_CLI_IMAGE)
            .withNetwork(network)
            .withEnv(
                mapOf(
                    "AWS_ACCESS_KEY_ID" to adminKey,
                    "AWS_SECRET_ACCESS_KEY" to adminSecret,
                    "AWS_DEFAULT_REGION" to "us-east-1",
                    "S3_ENDPOINT" to "http://seaweedfs:$S3_PORT",
                ),
            ).withCopyFileToContainer(
                MountableFile.forHostPath(repoRoot.resolve("deploy/seaweedfs/storage-init.sh")),
                "/init/storage-init.sh",
            ).withCreateContainerCmdModifier { it.withEntrypoint("/bin/sh", "/init/storage-init.sh") }
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
            .use { it.start() }
    }

    fun adminClient(): S3Client = client(adminKey, adminSecret)

    fun appClient(): S3Client = client(appKey, appSecret)

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

    private const val S3_PORT = 8333
}
