package com.memo.files

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.ratelimit.RateLimitedException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.FileStore
import com.memo.files.internal.RejectionReason
import com.memo.files.internal.UploadRejected
import com.memo.files.internal.UploadRequest
import com.memo.files.internal.Uploads
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestDocumentPurpose
import com.memo.testsupport.TestImagePurpose
import com.memo.testsupport.TestMalwareScanner
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.TestTenant
import com.memo.testsupport.VetoingFileOwner
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The upload pipeline of ADR-0029 end to end against PostgreSQL, SeaweedFS and clamd (test plan T5). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadIT : FilesIntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var uploads: Uploads

    @Autowired internal lateinit var store: FileStore

    /** A fresh tenant per test: assertions count rows and objects of the whole tenant. */
    private lateinit var tenant: TestTenant

    @BeforeEach
    fun aFreshTenant() {
        tenant = registry.aTestTenant(transactions, settings, "files")
    }

    /** A fresh uploader per test: the upload rate limit is per user. */
    private fun anUploader(admin: Boolean = true): UserAccount =
        accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = admin)

    private fun upload(
        bytes: ByteArray,
        name: String?,
        uploader: UserAccount,
        purpose: String = TestDocumentPurpose.KEY,
        owner: String? = null,
        fileId: UUID? = null,
        expectedVersion: Long? = null,
        key: String = UUID.randomUUID().toString(),
    ): UUID =
        ActorContext.call(ActorScope(Actor.User(uploader.id.value), null)) {
            TenantContext.call(tenant.id) {
                uploads.upload(UploadRequest(purpose, owner, fileId, expectedVersion, name, bytes.size.toLong(), key, bytes.inputStream()))
            }
        }

    private fun rejection(block: () -> Any): UploadRejected = catchThrowableOfType(UploadRejected::class.java) { block() }

    private fun version(id: UUID) = transactions.readInTenant(tenant.id) { store.version(id)!! }

    private fun file(id: UUID) = transactions.readInTenant(tenant.id) { store.info(id)!! }

    private fun objectKeys(): List<String> =
        listOf("records", "work").flatMap { bucket ->
            TestObjectStorage
                .appClient()
                .listObjectsV2 { it.bucket(bucket).prefix("t/${tenant.id}/") }
                .contents()
                .map { "$bucket/${it.key()}" }
        }

    private fun count(sql: String) = AppRoleJdbc.inTransaction(tenant.id) { it.long(sql) }

    @Test
    fun `an accepted record is stored encrypted under a random key, locked, versioned and audited`() {
        val pdf = Samples.pdf(pages = 2)
        val versionId = upload(pdf, "Board pack Q3.pdf", anUploader())

        val version = version(versionId)
        assertThat(version.mediaType).isEqualTo("application/pdf")
        assertThat(version.name).isEqualTo("Board pack Q3.pdf")
        assertThat(version.sizeBytes).isEqualTo(pdf.size.toLong())
        assertThat(version.sha256).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
        assertThat(version.pdfPages).isEqualTo(2)
        assertThat(version.number).isEqualTo(1)
        assertThat(version.retainUntil).isNotNull()
        assertThat(file(version.fileId).currentVersionId).isEqualTo(versionId)

        val (bucket, key) =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select bucket, object_key, lock_state from files_version where id = ?").use { st ->
                    st.setObject(1, versionId)
                    st.executeQuery().use {
                        it.next()
                        assertThat(it.getString(3)).isEqualTo("FINAL")
                        it.getString(1) to it.getString(2)
                    }
                }
            }
        assertThat(bucket).isEqualTo("records")
        assertThat(key).matches("t/${tenant.id}/[a-z2-7]{26}")
        val stored = TestObjectStorage.appClient().getObjectAsBytes { it.bucket("records").key(key) }.asByteArray()
        assertThat(String(stored, Charsets.ISO_8859_1)).doesNotContain("%PDF")
        val retention = TestObjectStorage.appClient().getObjectRetention { it.bucket("records").key(key) }.retention()
        assertThat(retention.retainUntilDate()).isEqualTo(version.retainUntil)

        val actions = Audit.entries(tenant.id).map { it.action }
        assertThat(actions).contains("file.version.created", "file.retention.finalised", "files.keyset.created")
        assertThat(count("select count(*) from files_pending_upload")).isZero()
        assertThat(Files.list(spoolDir).use { it.count() }).isZero()
    }

    @Test
    fun `malware is refused, nothing reaches storage, and the rejection is audited with the signature`() {
        val before = objectKeys()

        val rejected = rejection { upload(TestMalwareScanner.eicar, "invoice.txt", anUploader()) }

        assertThat(rejected.reason).isEqualTo(RejectionReason.MALWARE)
        assertThat(objectKeys()).isEqualTo(before)
        val audit = Audit.entries(tenant.id).last { it.action == "file.upload.rejected" }
        assertThat(audit.canonical).contains("MALWARE", "Memo.Test.EICAR")
    }

    @Test
    fun `a file over the purpose's limit is refused while streaming, and by its declared length before`() {
        val big = Samples.png() + ByteArray(3 shl 20)

        assertThat(rejection { upload(big, "logo.png", anUploader(admin = false), purpose = TestImagePurpose.KEY) }.reason)
            .isEqualTo(RejectionReason.TOO_LARGE)
        val streamed =
            rejection {
                ActorContext.call(ActorScope(Actor.User(anUploader(admin = false).id.value), null)) {
                    TenantContext.call(tenant.id) {
                        uploads.upload(
                            UploadRequest(
                                TestImagePurpose.KEY,
                                null,
                                null,
                                null,
                                "logo.png",
                                null,
                                "k-${UUID.randomUUID()}",
                                big.inputStream(),
                            ),
                        )
                    }
                }
            }
        assertThat(streamed.reason).isEqualTo(RejectionReason.TOO_LARGE)
        assertThat(streamed.detail["maxBytes"]).isEqualTo(2L shl 20)
    }

    @Test
    fun `the same Idempotency-Key and content give the same version once`() {
        val uploader = anUploader()
        val key = "retry-${UUID.randomUUID()}"
        val png = Samples.png()

        val first = upload(png, "a.png", uploader, key = key)
        val second = upload(png, "a.png", uploader, key = key)

        assertThat(second).isEqualTo(first)
        assertThat(count("select count(*) from files_version where file_id = '${version(first).fileId}'")).isEqualTo(1)
    }

    @Test
    fun `concurrent new versions with the same If-Match leave one winner and gapless numbers`() {
        val uploader = anUploader()
        val fileId = version(upload(Samples.pdf(), "v1.pdf", uploader)).fileId
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes =
            try {
                List(2) { i ->
                    pool.submit<Result<UUID>> {
                        start.await()
                        runCatching { upload(Samples.pdf(pages = i + 2), "v2.pdf", uploader, fileId = fileId, expectedVersion = 1) }
                    }
                }.also { start.countDown() }.map { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(ConcurrentModificationException::class.java)
        val versions = transactions.readInTenant(tenant.id) { store.versions(fileId, 10) }
        assertThat(versions.map { it.number }).containsExactly(2, 1)
        assertThat(file(fileId).version).isEqualTo(2)
    }

    @Test
    fun `an owner's veto rolls the version back and leaves the object to the pending sweep`() {
        val filesBefore = count("select count(*) from files_file")

        assertThatThrownBy { upload(Samples.pdf(), "a.pdf", anUploader(), owner = VetoingFileOwner.VETO) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(count("select count(*) from files_file")).isEqualTo(filesBefore)
        assertThat(count("select count(*) from files_pending_upload")).isEqualTo(1)
    }

    @Test
    fun `an uploader without the purpose's permission is refused before anything is read`() {
        assertThatThrownBy { upload(Samples.pdf(), "a.pdf", anUploader(admin = false)) }
            .isInstanceOf(com.memo.common.authz.ForbiddenException::class.java)
    }

    @Test
    fun `the 31st upload in a minute is rate limited`() {
        val uploader = anUploader(admin = false)
        val tooBig = ByteArray(3 shl 20)
        repeat(30) {
            assertThat(
                rejection { upload(tooBig, "a.png", uploader, purpose = TestImagePurpose.KEY) }.reason,
            ).isEqualTo(RejectionReason.TOO_LARGE)
        }

        assertThatThrownBy {
            upload(
                tooBig,
                "a.png",
                uploader,
                purpose = TestImagePurpose.KEY,
            )
        }.isInstanceOf(RateLimitedException::class.java)
    }
}
