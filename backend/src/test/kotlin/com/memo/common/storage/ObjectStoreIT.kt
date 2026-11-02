package com.memo.common.storage

import com.memo.testsupport.TestObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.services.s3.model.S3Exception
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random

/** ADR-0005, ADR-0029 against the pinned SeaweedFS: locked record objects, retention extension, ranged reads. */
class ObjectStoreIT {
    private val storage = TestObjectStorage
    private val store =
        ObjectStore(StorageProperties(endpoint = storage.endpoint.toString(), accessKey = storage.appKey, secretKey = storage.appSecret))

    @TempDir lateinit var dir: Path

    private fun aFile(size: Int): Pair<Path, ByteArray> {
        val bytes = Random(size).nextBytes(size)
        return Files.write(dir.resolve("f-${UUID.randomUUID()}"), bytes) to bytes
    }

    private fun aKey() = "test/${UUID.randomUUID()}"

    private fun inDays(days: Long) = Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `a locked record object cannot be deleted, and its retention cannot be shortened or bypassed`() {
        val key = aKey()
        val (file, bytes) = aFile(10_000)
        store.put(Bucket.RECORDS, key, file, "application/octet-stream", Retention(RetentionMode.GOVERNANCE, inDays(7)))
        val app = storage.appClient()
        val version = app.headObject { it.bucket("records").key(key) }.versionId()

        assertThatThrownBy { app.deleteObject { it.bucket("records").key(key).versionId(version) } }.isInstanceOf(S3Exception::class.java)
        assertThatThrownBy {
            app.deleteObject {
                it
                    .bucket("records")
                    .key(key)
                    .versionId(version)
                    .bypassGovernanceRetention(true)
            }
        }.isInstanceOf(S3Exception::class.java)
        assertThatThrownBy { store.extendRetention(key, Retention(RetentionMode.GOVERNANCE, inDays(1))) }
            .isInstanceOf(S3Exception::class.java)

        // An overwrite only adds a version; the locked one stays readable.
        val (other, _) = aFile(20)
        store.put(Bucket.RECORDS, key, other, "application/octet-stream", Retention(RetentionMode.GOVERNANCE, inDays(7)))
        assertThat(app.getObjectAsBytes { it.bucket("records").key(key).versionId(version) }.asByteArray()).isEqualTo(bytes)
    }

    @Test
    fun `a provisional lock is extended to its final date in the same mode, in GOVERNANCE and in COMPLIANCE`() {
        RetentionMode.entries.forEach { mode ->
            val key = aKey()
            val (file, _) = aFile(1_000)
            store.put(Bucket.RECORDS, key, file, "application/octet-stream", Retention(mode, inDays(7)))

            val final = inDays(3650)
            store.extendRetention(key, Retention(mode, final))

            val retention = storage.appClient().getObjectRetention { it.bucket("records").key(key) }.retention()
            assertThat(retention.modeAsString()).isEqualTo(mode.name)
            assertThat(retention.retainUntilDate()).isEqualTo(final)
        }
    }

    @Test
    fun `the application cannot turn a GOVERNANCE lock into COMPLIANCE, so the provisional lock already has the final mode`() {
        val key = aKey()
        val (file, _) = aFile(1_000)
        store.put(Bucket.RECORDS, key, file, "application/octet-stream", Retention(RetentionMode.GOVERNANCE, inDays(7)))

        assertThatThrownBy { store.extendRetention(key, Retention(RetentionMode.COMPLIANCE, inDays(3650))) }
            .isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `work objects are not locked and can be deleted`() {
        val key = aKey()
        val (file, _) = aFile(1_000)
        assertThatThrownBy { store.put(Bucket.WORK, key, file, "application/octet-stream", Retention(RetentionMode.GOVERNANCE, inDays(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        store.put(Bucket.WORK, key, file, "application/octet-stream", null)

        store.deleteAllVersions(Bucket.WORK, key)

        assertThatThrownBy { store.readRange(Bucket.WORK, key, 0, 10) }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `a record object whose provisional lock has expired can be removed with all its versions`() {
        val key = aKey()
        val (file, _) = aFile(1_000)
        store.put(Bucket.RECORDS, key, file, "application/octet-stream", Retention(RetentionMode.GOVERNANCE, Instant.now().plusSeconds(3)))

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until {
            store.deleteAllVersions(Bucket.RECORDS, key)
            storage
                .appClient()
                .listObjectVersions { it.bucket("records").prefix(key) }
                .versions()
                .isEmpty()
        }
    }

    @Test
    fun `the range channel returns exactly the requested bytes anywhere in the object`() {
        val key = aKey()
        val size = 300_000
        val (file, bytes) = aFile(size)
        store.put(Bucket.WORK, key, file, "application/octet-stream", null)
        val random = Random(7)

        store.channel(Bucket.WORK, key, size.toLong(), 64 * 1024).use { channel ->
            repeat(20) {
                val start = random.nextInt(size)
                val length = random.nextInt(1, 150_000)
                channel.position(start.toLong())
                val buffer = ByteBuffer.allocate(length)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                assertThat(buffer.array().copyOf(buffer.position())).isEqualTo(bytes.copyOfRange(start, minOf(size, start + length)))
            }
            channel.position(size.toLong())
            assertThat(channel.read(ByteBuffer.allocate(1))).isEqualTo(-1)
        }
    }

    @Test
    fun `without configuration every operation fails clearly`() {
        val disabled = ObjectStore(StorageProperties())

        assertThat(disabled.enabled).isFalse()
        assertThatThrownBy { disabled.readRange(Bucket.WORK, "k", 0, 1) }.isInstanceOf(StorageUnavailableException::class.java)
    }
}
