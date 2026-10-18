package com.memo.system.health.probes

import com.memo.common.storage.StorageProperties
import com.memo.system.health.ComponentStatus
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.randomSecret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * SeaweedFS + the real bucket bootstrap script (ADR-0005), exactly as Compose runs them.
 * Proves: object lock on `records`, least privilege for the app identity (threat model E2), probe UP.
 */
class ObjectStorageIT {
    private val storage = TestObjectStorage

    @Test
    fun `records bucket has object lock enabled and bootstrap is idempotent`() {
        storage.runStorageInit() // second run: must succeed without changes
        storage.adminClient().use { s3 ->
            val config = s3.getObjectLockConfiguration { it.bucket("records") }.objectLockConfiguration()
            assertThat(config.objectLockEnabled()).isEqualTo(ObjectLockEnabled.ENABLED)
        }
    }

    @Test
    fun `app identity cannot change object lock or administer buckets`() {
        storage.appClient().use { s3 ->
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
        val good = StorageProperties(endpoint = storage.endpoint.toString(), accessKey = storage.appKey, secretKey = storage.appSecret)
        ObjectStorageProbe(good).use { assertThat(it.check()).isEqualTo(ComponentStatus.UP) }

        val bad = good.copy(secretKey = "wrong-${randomSecret()}")
        ObjectStorageProbe(bad).use { assertThat(it.check()).isEqualTo(ComponentStatus.DOWN) }
    }

    @Test
    fun `storage properties never print the secret`() {
        val props = StorageProperties(endpoint = storage.endpoint.toString(), accessKey = storage.appKey, secretKey = storage.appSecret)
        assertThat(props.toString()).doesNotContain(storage.appSecret).doesNotContain(storage.appKey)
    }
}
