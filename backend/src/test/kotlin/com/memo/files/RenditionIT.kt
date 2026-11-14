package com.memo.files

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.FileStore
import com.memo.files.internal.RenditionRecovery
import com.memo.files.internal.RenditionRetry
import com.memo.files.internal.RenditionScheduler
import com.memo.files.internal.RenditionStore
import com.memo.files.internal.RenditionWorker
import com.memo.files.internal.UploadRequest
import com.memo.files.internal.Uploads
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.FakeRenditionServices
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestRenderedPurpose
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.onHost
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.UUID

/**
 * Renditions (ADR-0045; test plan E02 T2) with scripted stand-ins for the document server and the extractor: recorded at
 * upload, built by the worker, stored encrypted and read through the original's rules, retried for transient errors up
 * to a limit, failed for good otherwise, not possible for types no service handles, and found again when a job is lost.
 * The worker runs here by hand, as its task would: test contexts share the scheduler table, and a context without
 * object storage would pick up the tasks otherwise, so scheduling is off while this class runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenditionIT : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var uploads: Uploads

    @Autowired internal lateinit var store: FileStore

    @Autowired internal lateinit var renditions: RenditionStore

    @Autowired internal lateinit var recovery: RenditionRecovery

    @Autowired internal lateinit var worker: RenditionWorker

    @Autowired internal lateinit var scheduling: RenditionScheduler

    @BeforeAll
    fun noJobs() {
        scheduling.jobs = false
    }

    @AfterAll
    fun jobsAgain() {
        scheduling.jobs = true
    }

    @Autowired lateinit var fakes: FakeRenditionServices

    @Autowired lateinit var fileRenditions: FileRenditions

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "rend", mfaRequired = false) }
    private val other: TestTenant by lazy { registry.aTestTenant(transactions, settings, "rend2", mfaRequired = false) }

    @Test
    fun `a document gets a PDF and its text, read encrypted through the original's rules and audited`() {
        val (fileId, versionId) = upload(Samples.docx("<doc>budget</doc>"), "budget.docx")
        val pdf = settled(versionId, RenditionKind.PDF)
        assertThat(pdf.state).isEqualTo(RenditionState.DONE)
        assertThat(pdf.pages).isEqualTo(1)
        assertThat(settled(versionId, RenditionKind.TEXT).state).isEqualTo(RenditionState.DONE)

        // Stored encrypted under its own key in `work`, never as plaintext.
        val key = transactions.readInTenant(tenant.id) { renditions.get(versionId, RenditionKind.PDF)!!.objectKey!! }
        assertThat(key).startsWith("t/${tenant.id}/r/")

        val session = aSession(tenant)
        val states =
            mvc
                .get()
                .with(onHost(tenant.slug))
                .uri("/api/v1/files/$fileId/versions/$versionId/renditions")
                .cookie(session)
                .exchange()
        assertThat(states).hasStatusOk()
        assertThat(states).bodyJson().extractingPath("$.preview").isEqualTo("PDF")

        val content =
            mvc
                .get()
                .with(onHost(tenant.slug))
                .uri("/api/v1/files/$fileId/versions/$versionId/renditions/pdf")
                .cookie(session)
                .exchange()
        assertThat(content).hasStatusOk()
        assertThat(content).hasContentType("application/pdf")
        assertThat(content.response.contentAsByteArray).isEqualTo(FakeRenditionServices.PDF)
        assertThat(content.response.getHeader("Content-Security-Policy")).contains("sandbox")
        Audit.assertAudited(tenant.id, "file.opened", versionId.toString())

        // A range, for page-by-page loading.
        val range =
            mvc
                .get()
                .with(onHost(tenant.slug))
                .uri(
                    "/api/v1/files/$fileId/versions/$versionId/renditions/pdf",
                ).cookie(session)
                .header("Range", "bytes=0-9")
                .exchange()
        assertThat(range).hasStatus(HttpStatus.PARTIAL_CONTENT)
        assertThat(range.response.contentAsByteArray).isEqualTo(FakeRenditionServices.PDF.copyOf(10))

        // The text, for owning modules.
        val text = java.io.ByteArrayOutputStream()
        TenantContext.call(tenant.id) { assertThat(fileRenditions.write(versionId, RenditionKind.TEXT, text)).isTrue() }
        assertThat(text.toString()).isEqualTo("Text of budget.docx")
    }

    @Test
    fun `a final error fails at once and is audited, and a transient one is retried`() {
        val (_, failed) = upload(Samples.docx("<doc>a</doc>"), "final-size.docx")
        val pdf = settled(failed, RenditionKind.PDF)
        assertThat(pdf.state).isEqualTo(RenditionState.FAILED)
        assertThat(pdf.error).isEqualTo("SIZE")
        assertThat(fakes.calls("final-size.docx", "pdf")).isEqualTo(1)
        Audit.assertAudited(tenant.id, "file.rendition.failed", failed.toString())

        val (_, retried) = upload(Samples.docx("<doc>b</doc>"), "retry-once-b.docx")
        assertThat(settled(retried, RenditionKind.PDF).state).isEqualTo(RenditionState.DONE)
        assertThat(attempts(retried, RenditionKind.PDF)).isEqualTo(2)
    }

    @Test
    fun `a rendition that keeps failing stops at the attempt limit`() {
        val (_, versionId) = upload(Samples.docx("<doc>c</doc>"), "always-retry-c.docx")
        val pdf = settled(versionId, RenditionKind.PDF)
        assertThat(pdf.state).isEqualTo(RenditionState.FAILED)
        assertThat(pdf.error).isEqualTo("UNREACHABLE")
        assertThat(attempts(versionId, RenditionKind.PDF)).isEqualTo(RenditionWorker.MAX_ATTEMPTS)
    }

    @Test
    fun `an image is previewed as itself, and no service handles its text`() {
        val (fileId, versionId) = upload(Samples.png(), "photo.png")
        val rows = transactions.readInTenant(tenant.id) { renditions.of(versionId) }
        assertThat(rows.map { it.kind }).containsExactly(RenditionKind.TEXT)
        assertThat(rows.single().state).isEqualTo(RenditionState.NOT_POSSIBLE)
        assertThat(rows.single().error).isEqualTo("UNSUPPORTED")
        val states =
            mvc
                .get()
                .with(onHost(tenant.slug))
                .uri("/api/v1/files/$fileId/versions/$versionId/renditions")
                .cookie(aSession(tenant))
                .exchange()
        assertThat(states).bodyJson().extractingPath("$.preview").isEqualTo("IMAGE")
    }

    @Test
    fun `a PDF original is its own preview`() {
        val (fileId, versionId) = upload(Samples.pdf(), "scan.pdf")
        val content =
            mvc
                .get()
                .with(onHost(tenant.slug))
                .uri("/api/v1/files/$fileId/versions/$versionId/renditions/pdf")
                .cookie(aSession(tenant))
                .exchange()
        assertThat(content).hasStatusOk()
        assertThat(content).hasContentType("application/pdf")
    }

    @Test
    fun `a lost job is picked up again by the recovery task`() {
        val (_, versionId) = upload(Samples.docx("<doc>d</doc>"), "lost-d.docx")
        settled(versionId, RenditionKind.PDF)
        // As if the job had been lost before it ran: pending again, created long ago, and no scheduled instance.
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .execute(
                    "update files_rendition set state = 'PENDING', object_key = null, size_bytes = null, ciphertext_bytes = null, " +
                        "sha256 = null, keyset_key_id = null, pages = null, finished_at = null, " +
                        "created_at = timestamptz '2000-01-01 00:00Z' " +
                        "where version_id = '$versionId' and kind = 'PDF'",
                )
        }
        val rescheduled = ActorContext.asSystem("test") { TenantContext.call(tenant.id) { recovery.recover() } }
        assertThat(rescheduled).isGreaterThanOrEqualTo(1)
        assertThat(settled(versionId, RenditionKind.PDF).state).isEqualTo(RenditionState.DONE)
    }

    @Test
    fun `another tenant cannot see or read a rendition`() {
        val (fileId, versionId) = upload(Samples.docx("<doc>e</doc>"), "private-e.docx")
        settled(versionId, RenditionKind.PDF)
        val stranger = aSession(other)
        assertThat(
            mvc
                .get()
                .with(onHost(other.slug))
                .uri("/api/v1/files/$fileId/versions/$versionId/renditions")
                .cookie(stranger)
                .exchange(),
        ).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(
            mvc
                .get()
                .with(onHost(other.slug))
                .uri("/api/v1/files/$fileId/versions/$versionId/renditions/pdf")
                .cookie(stranger)
                .exchange(),
        ).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(transactions.readInTenant(other.id) { renditions.of(versionId) }).isEmpty()
    }

    private fun upload(
        bytes: ByteArray,
        name: String,
    ): Pair<UUID, UUID> {
        val uploader = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val versionId =
            ActorContext.call(ActorScope(Actor.User(uploader.id.value), null)) {
                TenantContext.call(tenant.id) {
                    uploads.upload(
                        UploadRequest(
                            TestRenderedPurpose.KEY,
                            null,
                            null,
                            null,
                            name,
                            bytes.size.toLong(),
                            UUID.randomUUID().toString(),
                            bytes.inputStream(),
                        ),
                    )
                }
            }
        return transactions.readInTenant(tenant.id) { store.version(versionId)!!.fileId } to versionId
    }

    /** Runs the worker as the task would (a retry is another run) until the rendition has left PENDING. */
    private fun settled(
        versionId: UUID,
        kind: RenditionKind,
    ): RenditionInfo {
        repeat(RenditionWorker.MAX_ATTEMPTS + 1) {
            if (info(versionId, kind)?.state != RenditionState.PENDING) return info(versionId, kind)!!
            try {
                ActorContext.asSystem("files-rendition") { TenantContext.call(tenant.id) { worker.run(versionId, kind) } }
            } catch (_: RenditionRetry) {
                // The scheduler would run it again after a backoff.
            }
        }
        return info(versionId, kind)!!
    }

    private fun info(
        versionId: UUID,
        kind: RenditionKind,
    ): RenditionInfo? = transactions.readInTenant(tenant.id) { renditions.get(versionId, kind)?.info() }

    private fun attempts(
        versionId: UUID,
        kind: RenditionKind,
    ): Int = transactions.readInTenant(tenant.id) { renditions.get(versionId, kind)?.attempts ?: 0 }

    private fun aSession(of: TestTenant): Cookie {
        val password = aPassword()
        return mvc.signIn(of, accounts.anActiveUser(transactions, of, password).email, password)
    }
}
