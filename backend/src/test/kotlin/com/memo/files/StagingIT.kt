package com.memo.files

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.FileStore
import com.memo.files.internal.RenditionScheduler
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
import com.memo.testsupport.LegacySamples
import com.memo.testsupport.Samples
import com.memo.testsupport.TestDocumentPurpose
import com.memo.testsupport.TestIntakePurpose
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.TestRenderedPurpose
import com.memo.testsupport.TestTenant
import com.memo.testsupport.UploadRejections
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.onHost
import com.memo.testsupport.signIn
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.sql.SQLException
import java.util.UUID

/**
 * Staged content for the legacy import (ADR-0055, test plan E06 T2 `CopyFromIT`, `PurgeIT`, `RenditionBacklogIT`):
 * promotion through the whole pipeline with an expected hash, purging of ordinary staged versions, and the count of
 * pending renditions an import throttles on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StagingIT : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var fileUploads: FileUploads

    @Autowired lateinit var purges: FilePurges

    @Autowired lateinit var fileRenditions: FileRenditions

    @Autowired internal lateinit var uploads: Uploads

    @Autowired internal lateinit var store: FileStore

    @Autowired internal lateinit var scheduling: RenditionScheduler

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "staging", mfaRequired = false) }

    private val admin: UserAccount by lazy { accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true) }

    private fun <T> asAdmin(block: () -> T): T =
        ActorContext.call(ActorScope(Actor.User(admin.id.value), null)) { TenantContext.call(tenant.id) { block() } }

    private fun stage(
        bytes: ByteArray,
        name: String,
        purpose: String = TestIntakePurpose.KEY,
    ): FileVersionInfo =
        asAdmin {
            val key = UUID.randomUUID().toString()
            val id = uploads.upload(UploadRequest(purpose, "intake-1", null, null, name, bytes.size.toLong(), key, bytes.inputStream()))
            transactions.readInTenant(tenant.id) { store.version(id)!! }
        }

    private fun version(id: UUID) = transactions.readInTenant(tenant.id) { store.version(id)!! }

    private fun stored(id: UUID): Pair<String, String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select bucket, object_key from files_version where id = ?").use { st ->
                st.setObject(1, id)
                st.executeQuery().use {
                    it.next()
                    it.getString(1) to it.getString(2)
                }
            }
        }

    private fun objectKeys(bucket: String): Set<String> =
        TestObjectStorage
            .appClient()
            .listObjectsV2 { it.bucket(bucket).prefix("t/${tenant.id}/") }
            .contents()
            .map { it.key() }
            .toSet()

    private fun copy(
        source: FileVersionInfo,
        sha256: String = source.sha256,
        key: String = "imp-${UUID.randomUUID()}",
        purpose: String = TestDocumentPurpose.KEY,
    ): UUID = asAdmin { fileUploads.copyFrom(source.id, purpose, "memo-1", key, sha256) }

    @Test
    fun `a copy streams the staged content through the pipeline into a record, once per key`() {
        val staged = stage(Samples.pdf(pages = 2), "Board pack.pdf")
        assertThat(stored(staged.id).first).isEqualTo("work")
        val key = "imp-${UUID.randomUUID()}"

        val copied = version(copy(staged, staged.sha256.uppercase(), key))

        assertThat(copied.fileId).isNotEqualTo(staged.fileId)
        assertThat(copied.sha256).isEqualTo(staged.sha256)
        assertThat(copied.name).isEqualTo("Board pack.pdf")
        assertThat(copied.pdfPages).isEqualTo(2)
        assertThat(copied.retainUntil).isNotNull()
        val (bucket, objectKey) = stored(copied.id)
        assertThat(bucket).isEqualTo("records")
        assertThat(objectKey).isNotEqualTo(stored(staged.id).second)
        assertThat(transactions.readInTenant(tenant.id) { store.info(copied.fileId)!! }.purpose).isEqualTo(TestDocumentPurpose.KEY)
        // The same key gives the same version: a retried promotion stores nothing twice.
        assertThat(copy(staged, key = key)).isEqualTo(copied.id)
        assertThat(Audit.entries(tenant.id).count { it.action == "file.version.created" && copied.id.toString() in it.canonical })
            .isEqualTo(1)
    }

    @Test
    fun `a copy is inspected again under its target purpose`() {
        val staged = stage(LegacySamples.xls(), "ledger.xls")

        val refused = catchThrowableOfType(UploadRefusedException::class.java) { copy(staged) }

        assertThat(refused.reason).isEqualTo("TYPE_NOT_ALLOWED")
    }

    @Test
    fun `a copy whose content is not the expected one stores nothing and is refused as a hash mismatch`() {
        val staged = stage(Samples.pdf(), "minutes.pdf")
        val key = "imp-${UUID.randomUUID()}"
        val records = objectKeys("records")
        val versions = AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from files_version") }
        val pending = AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from files_pending_upload") }

        val refused = catchThrowableOfType(UploadRefusedException::class.java) { copy(staged, "0".repeat(64), key) }

        assertThat(refused.reason).isEqualTo("HASH_MISMATCH")
        assertThat(objectKeys("records")).isEqualTo(records)
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from files_version") }).isEqualTo(versions)
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from files_pending_upload") }).isEqualTo(pending)
        val audit = Audit.entries(tenant.id).last { it.action == "file.upload.rejected" }
        assertThat(audit.canonical).contains("HASH_MISMATCH", staged.sha256)
        assertThat(UploadRejections.seen.single { it.idempotencyKey == key }.reasonCode).isEqualTo("HASH_MISMATCH")
    }

    @Test
    fun `a purge deletes the staged object, keeps the row and its hash, answers 410 and is audited once`() {
        val staged = stage(Samples.pdf(), "minutes.pdf")
        val objectKey = stored(staged.id).second
        assertThat(objectKeys("work")).contains(objectKey)

        assertThat(asAdmin { purges.purge(staged.id) }).isTrue()

        assertThat(objectKeys("work")).doesNotContain(objectKey)
        val purged = version(staged.id)
        assertThat(purged.sha256).isEqualTo(staged.sha256)
        assertThat(purged.sizeBytes).isEqualTo(staged.sizeBytes)
        assertThat(purged.purgedAt).isNotNull()
        val audit = Audit.entries(tenant.id).filter { it.action == "file.version.purged" && it.objectId == staged.id.toString() }
        assertThat(audit).hasSize(1)
        assertThat(audit.single().canonical).contains(staged.sha256, TestIntakePurpose.KEY)

        val password = aPassword()
        val session = mvc.signIn(tenant, accounts.anActiveUser(transactions, tenant, password).email, password)
        val download =
            mvc
                .get()
                .uri("/api/v1/files/${staged.fileId}/versions/${staged.id}/content")
                .with(onHost(tenant.slug))
                .cookie(session)
                .exchange()
        assertThat(download).hasStatus(HttpStatus.GONE)
        assertThat(download.response.contentAsString).contains("PURGED")

        // Again: nothing more to mark, nothing more audited; a copy of it is impossible.
        assertThat(asAdmin { purges.purge(staged.id) }).isFalse()
        assertThat(Audit.entries(tenant.id).count { it.action == "file.version.purged" && it.objectId == staged.id.toString() })
            .isEqualTo(1)
        assertThatThrownBy { copy(staged) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a record version is never purged, by the service or in the database`() {
        val record = stage(Samples.pdf(), "resolution.pdf", purpose = TestDocumentPurpose.KEY)

        assertThatThrownBy { asAdmin { purges.purge(record.id) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not purgeable")
        assertThat(version(record.id).purgedAt).isNull()
        assertThat(objectKeys("records")).contains(stored(record.id).second)
        assertThatThrownBy {
            AppRoleJdbc.inTransaction(tenant.id) {
                it.createStatement().executeUpdate("update files_version set purged_at = now() where id = '${record.id}'")
            }
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `pending renditions are counted by purpose, up to a bound, and by version`() {
        scheduling.jobs = false
        val (first, second) =
            try {
                stage(Samples.docx("<doc>one</doc>"), "one.docx", TestRenderedPurpose.KEY) to
                    stage(Samples.docx("<doc>two</doc>"), "two.docx", TestRenderedPurpose.KEY)
            } finally {
                scheduling.jobs = true
            }

        transactions.readInTenant(tenant.id) {
            // Each document waits for a PDF and its text.
            assertThat(fileRenditions.pendingCount(TestRenderedPurpose.KEY, 100)).isEqualTo(4)
            assertThat(fileRenditions.pendingCount(TestRenderedPurpose.KEY, 3)).isEqualTo(3)
            assertThat(fileRenditions.pendingCount(TestDocumentPurpose.KEY, 100)).isZero()
            assertThat(fileRenditions.pendingCount(listOf(first.id))).isEqualTo(2)
            assertThat(fileRenditions.pendingCount(listOf(first.id, second.id, UUID.randomUUID()))).isEqualTo(4)
            assertThat(fileRenditions.pendingCount(emptyList())).isZero()
        }
    }
}
