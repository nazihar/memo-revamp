package com.memo.files

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.Downloads
import com.memo.files.internal.FileStore
import com.memo.files.internal.UploadRequest
import com.memo.files.internal.Uploads
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.TestDocumentPurpose
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Files budgets (design §11) with 2x headroom for shared CI runners, as in the org performance test; measured values are
 * printed for the log. Service level: HTTP adds the same constant to every endpoint and is covered by Gatling.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilesPerformanceIT : FilesIntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var uploads: Uploads

    @Autowired internal lateinit var downloads: Downloads

    @Autowired internal lateinit var store: FileStore

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "perf") }

    private val uploader: UserAccount by lazy { accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true) }

    /** A zip with [megabytes] MiB of random bytes stored as is (so nothing compresses and every segment is real). */
    private fun aZip(megabytes: Int): ByteArray {
        val payload = Random(megabytes).nextBytes(megabytes shl 20)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(
                ZipEntry("data.bin").apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    crc = CRC32().apply { update(payload) }.value
                },
            )
            zip.write(payload)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun <T> asUploader(block: () -> T): T =
        ActorContext.call(ActorScope(Actor.User(uploader.id.value), null)) { TenantContext.call(tenant.id, block) }

    private fun upload(bytes: ByteArray): UUID =
        asUploader {
            uploads.upload(
                UploadRequest(
                    TestDocumentPurpose.KEY,
                    null,
                    null,
                    null,
                    "data.zip",
                    bytes.size.toLong(),
                    UUID.randomUUID().toString(),
                    bytes.inputStream(),
                ),
            )
        }

    /** p95 in milliseconds over [runs] after [warmup] runs. */
    private fun p95(
        warmup: Int = 2,
        runs: Int = 20,
        block: () -> Unit,
    ): Double {
        repeat(warmup) { block() }
        val samples =
            (1..runs)
                .map {
                    val started = System.nanoTime()
                    block()
                    (System.nanoTime() - started) / 1_000_000.0
                }.sorted()
        return samples[(runs * 95 / 100).coerceAtMost(runs - 1)]
    }

    @Test
    fun `uploads, metadata, first bytes and ranges meet their budgets`() {
        val tenMiB = aZip(10)
        val uploadMs = p95(warmup = 1, runs = 4) { upload(tenMiB) }

        val versionId = upload(aZip(20))
        val fileId = transactions.readInTenant(tenant.id) { store.version(versionId)!!.fileId }
        val metadataMs = p95 { transactions.readInTenant(tenant.id) { store.info(fileId)!!.also { store.versions(fileId, 50) } } }
        val firstByteMs =
            p95 {
                asUploader {
                    val readable = downloads.open(fileId, null, null, null)
                    downloads.stream(readable, 0, 1, ByteArrayOutputStream())
                }
            }
        val middle = 10L shl 20
        val rangeMs =
            p95 {
                asUploader {
                    val out = ByteArrayOutputStream()
                    downloads.stream(downloads.open(fileId, null, null, middle), middle, 64 * 1024, out)
                    assertThat(out.size()).isEqualTo(64 * 1024)
                }
            }

        println("files p95 ms: upload 10 MiB=$uploadMs metadata=$metadataMs firstByte=$firstByteMs range64KiB=$rangeMs")
        assertThat(uploadMs).describedAs("10 MiB upload").isLessThan(2 * 1_500.0)
        assertThat(metadataMs).describedAs("metadata").isLessThan(2 * 100.0)
        assertThat(firstByteMs).describedAs("first byte").isLessThan(2 * 200.0)
        assertThat(rangeMs).describedAs("64 KiB range").isLessThan(2 * 150.0)
    }
}
