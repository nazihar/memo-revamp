package com.memo.files

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.storage.Bucket
import com.memo.common.storage.ObjectStore
import com.memo.common.storage.Retention
import com.memo.common.storage.RetentionMode
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.FileStore
import com.memo.files.internal.FilesMaintenance
import com.memo.files.internal.LockState
import com.memo.files.internal.NewVersion
import com.memo.files.internal.PendingUploads
import com.memo.files.internal.RejectionReason
import com.memo.files.internal.UploadRejected
import com.memo.files.internal.UploadRequest
import com.memo.files.internal.Uploads
import com.memo.files.internal.Uploads.Companion.MAX_BYTES_SETTING
import com.memo.files.internal.Uploads.Companion.RETENTION_MODE_SETTING
import com.memo.files.internal.Uploads.Companion.RETENTION_YEARS_SETTING
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
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Retention finalising, leftover clean-up and the tenant's files settings (design §10, §12; test plan T7). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilesMaintenanceIT : FilesIntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var uploads: Uploads

    @Autowired internal lateinit var store: FileStore

    @Autowired internal lateinit var pending: PendingUploads

    @Autowired internal lateinit var maintenance: FilesMaintenance

    @Autowired lateinit var objects: ObjectStore

    @TempDir lateinit var dir: Path

    private lateinit var tenant: TestTenant

    @BeforeEach
    fun aFreshTenant() {
        tenant = registry.aTestTenant(transactions, settings, "maint")
    }

    private fun <T> asSystem(block: () -> T): T = ActorContext.asSystem("test") { TenantContext.call(tenant.id, block) }

    private fun upload(
        bytes: ByteArray,
        name: String,
        purpose: String = TestDocumentPurpose.KEY,
    ): UUID {
        val uploader = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        return ActorContext.call(ActorScope(Actor.User(uploader.id.value), null)) {
            TenantContext.call(tenant.id) {
                uploads.upload(
                    UploadRequest(purpose, null, null, null, name, bytes.size.toLong(), UUID.randomUUID().toString(), bytes.inputStream()),
                )
            }
        }
    }

    private fun anObjectKey() = "t/${tenant.id}/" + Uploads.base32(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })

    private fun aSpool(): Path = Files.write(dir.resolve("o-${UUID.randomUUID()}"), Samples.text)

    private fun storageNow(): Instant =
        TestObjectStorage
            .appClient()
            .listObjectsV2 { it.bucket("work").maxKeys(1) }
            .sdkHttpResponse()
            .firstMatchingHeader("Date")
            .map { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(it)) }
            .orElseThrow()

    private fun pendingCount() = AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from files_pending_upload") }

    /** A record version as an upload leaves it when extending its lock failed: provisional, ten minutes old. */
    private fun aProvisionalVersion(final: Instant): Pair<String, String> {
        val key = anObjectKey()
        val provisional = Retention(RetentionMode.GOVERNANCE, Instant.now().plus(7, ChronoUnit.DAYS))
        val objectVersion = objects.put(Bucket.RECORDS, key, aSpool(), "application/octet-stream", provisional)!!
        val fileId = UUID.randomUUID()
        val uploadedAt = Instant.now().minus(10, ChronoUnit.MINUTES)
        val version =
            NewVersion(
                UUID.randomUUID(),
                fileId,
                "a.txt",
                "text/plain",
                10,
                ByteArray(32),
                "records",
                key,
                objectVersion,
                100,
                1,
                "clamd",
                null,
                LockState.PROVISIONAL,
                final,
                RetentionMode.GOVERNANCE,
                null,
                uploadedAt,
            )
        asSystem {
            transactions.inTenant(tenant.id) {
                store.insertFile(fileId, TestDocumentPurpose.KEY, null, null, uploadedAt)
                store.appendVersion(version, 0)
            }
        }
        return key to objectVersion
    }

    private fun lockState(): String =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select lock_state from files_version").use { st ->
                st.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    @Test
    fun `a lock left provisional is extended to its final date by the job`() {
        val final = Instant.now().plus(3650, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val (key, objectVersion) = aProvisionalVersion(final)

        assertThat(asSystem { maintenance.finaliseProvisional() }).isEqualTo(1)

        val retention =
            TestObjectStorage
                .appClient()
                .getObjectRetention {
                    it
                        .bucket(
                            "records",
                        ).key(key)
                        .versionId(objectVersion)
                }.retention()
        assertThat(retention.retainUntilDate()).isEqualTo(final)
        assertThat(lockState()).isEqualTo("FINAL")
        assertThat(Audit.entries(tenant.id).map { it.action }).contains("file.retention.finalised")
        assertThat(asSystem { maintenance.finaliseProvisional() }).isZero()
    }

    @Test
    fun `leftovers of uploads that never committed are removed, records only once their lock has expired`() {
        val work = anObjectKey()
        objects.put(Bucket.WORK, work, aSpool(), "application/octet-stream", null)
        val record = anObjectKey()
        val lockedUntil = storageNow().plusSeconds(2)
        objects.put(Bucket.RECORDS, record, aSpool(), "application/octet-stream", Retention(RetentionMode.GOVERNANCE, lockedUntil))
        val longAgo = Instant.now().minus(2, ChronoUnit.HOURS)
        asSystem {
            transactions.inTenant(tenant.id) {
                pending.insert(UUID.randomUUID(), "work", work, longAgo, null)
                pending.insert(UUID.randomUUID(), "records", record, longAgo, lockedUntil)
                pending.insert(UUID.randomUUID(), "records", anObjectKey(), Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS))
            }
        }
        val committed = upload(Samples.png(), "kept.png", TestImagePurpose.KEY)

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until {
            asSystem { maintenance.sweepPending() }
            pendingCount() == 1L
        }

        val app = TestObjectStorage.appClient()
        assertThat(app.listObjectsV2 { it.bucket("work").prefix(work) }.contents()).isEmpty()
        assertThat(app.listObjectVersions { it.bucket("records").prefix(record) }.versions()).isEmpty()
        assertThat(Audit.entries(tenant.id).count { it.action == "file.upload.leftover.removed" }).isEqualTo(2)
        assertThat(transactions.readInTenant(tenant.id) { store.version(committed) }).isNotNull()
    }

    @Test
    fun `the tenant can lower the upload limit and set the retention of records`() {
        transactions.inTenant(tenant.id) {
            ActorContext.asSystem("test") {
                settings.put(MAX_BYTES_SETTING, "50")
                settings.put(RETENTION_YEARS_SETTING, "1")
                settings.put(RETENTION_MODE_SETTING, "COMPLIANCE")
            }
        }

        val tooBig = catchThrowableOfType(UploadRejected::class.java) { upload(Samples.png(), "a.png", TestImagePurpose.KEY) }
        assertThat(tooBig.reason).isEqualTo(RejectionReason.TOO_LARGE)
        assertThat(tooBig.detail["maxBytes"]).isEqualTo(50L)

        transactions.inTenant(tenant.id) { ActorContext.asSystem("test") { settings.put(MAX_BYTES_SETTING, "1000000") } }
        val versionId = upload(Samples.pdf(), "a.pdf")
        val version = transactions.readInTenant(tenant.id) { store.version(versionId)!! }
        assertThat(version.retainUntil).isBetween(Instant.now().plus(364, ChronoUnit.DAYS), Instant.now().plus(367, ChronoUnit.DAYS))
        val key =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select object_key from files_version where id = ?").use { st ->
                    st.setObject(1, version.id)
                    st.executeQuery().use {
                        it.next()
                        it.getString(1)
                    }
                }
            }
        assertThat(
            TestObjectStorage
                .appClient()
                .getObjectRetention {
                    it.bucket("records").key(key)
                }.retention()
                .modeAsString(),
        ).isEqualTo("COMPLIANCE")
    }
}
