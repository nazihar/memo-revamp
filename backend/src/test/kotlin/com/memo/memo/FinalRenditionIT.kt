package com.memo.memo

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.memo.internal.MemoAttachmentPurpose
import com.memo.memo.internal.rendition.MemoRenditionPurpose
import com.memo.memo.internal.rendition.MemoRenditionStore
import com.memo.memo.internal.rendition.MemoRenditions
import com.memo.memo.internal.rendition.PartKind
import com.memo.memo.internal.rendition.PartMode
import com.memo.memo.internal.rendition.PdfaValidator
import com.memo.memo.internal.rendition.RecordBuilder
import com.memo.memo.internal.rendition.RenditionState
import com.memo.memo.internal.rendition.RenditionTrigger
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.util.UUID

/**
 * The final record (ADR-0053; test plan E04 T4): the approval asks for version 1; the build makes a PDF/A-2u file from
 * the approved document, the stamps, the note sheet and the attachments, stores it as a locked record and audits its hash;
 * a document that no longer matches its stamp fails for good; the backfill gives older approved memos their record.
 * Builds run here, not on the scheduler (test contexts share its table).
 */
class FinalRenditionIT : MemoIntegrationTest() {
    @Autowired lateinit var renditions: MemoRenditions

    @Autowired lateinit var store: MemoRenditionStore

    @Autowired lateinit var builder: RecordBuilder

    @Autowired lateinit var validator: PdfaValidator

    @BeforeEach
    fun noScheduler() {
        renditions.jobs = false
    }

    @AfterEach
    fun scheduler() {
        renditions.jobs = true
    }

    private fun approved(vararg attachments: Pair<String, ByteArray>): Triple<String, Person, Person> {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session, name = "credit-line.docx")
        attachments.forEach { (name, bytes) ->
            assertThat(
                upload(alice.session, bytes, "/api/v1/files?purpose=${MemoAttachmentPurpose.KEY}&owner=$memo", name),
            ).hasStatus2xxSuccessful()
        }
        route(memo, alice, bob)
        post("/api/v1/memos/$memo/submit", alice.session, """{"remark":"For your approval."}""")
        val task = get(memo, bob.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", bob.session, """{"remark":"Approved."}""")).hasStatusOk()
        return Triple(memo, alice, bob)
    }

    private fun versions(memo: String) = transactions.inTenant(tenant.id) { store.of(UUID.fromString(memo)) }

    /** As the rendition job runs: the tenant bound, the job's system actor (the only one that stores records). */
    private fun <T> asJob(block: () -> T): T = TenantContext.call(tenant.id) { ActorContext.asSystem(MemoRenditionPurpose.JOB, block) }

    private fun build(memo: String) = asJob { builder.build(versions(memo).first().id) }

    @Test
    fun `the approval asks for version 1, and the build stores a valid PDF-A record with every part and audits its hash`() {
        val (memo, alice, _) = approved("annex.pdf" to Samples.pdf(), "data.zip" to Samples.zip("a.txt" to "x".toByteArray()))
        val requested = versions(memo).single()
        assertThat(requested.state).isEqualTo(RenditionState.PENDING)
        assertThat(requested.trigger).isEqualTo(RenditionTrigger.APPROVED)
        assertThat(get(memo, alice.session).json()["rendition"].asString()).isEqualTo("PENDING")

        build(memo)
        val record = versions(memo).single()
        assertThat(record.state).isEqualTo(RenditionState.READY)
        assertThat(record.compliant).isTrue()
        assertThat(record.sha256).hasSize(64)
        val parts = transactions.inTenant(tenant.id) { renditions.parts(record.id) }
        assertThat(parts.map { it.kind }).containsExactly(
            PartKind.COVER,
            PartKind.DOCUMENT,
            PartKind.APPROVALS,
            PartKind.NOTES,
            PartKind.ATTACHMENT,
            PartKind.PLACEHOLDER,
        )
        assertThat(parts.map { it.mode }).containsExactly(
            PartMode.GENERATED,
            PartMode.NORMALISED,
            PartMode.GENERATED,
            PartMode.GENERATED,
            PartMode.NORMALISED,
            PartMode.PLACEHOLDER,
        )
        Audit.assertAudited(tenant.id, "memo.rendition.requested", memo)
        Audit.assertAudited(tenant.id, "memo.rendition.created", memo)

        val view = read("/api/v1/memos/$memo/rendition", alice.session).json()
        assertThat(view["state"].asString()).isEqualTo("READY")
        val current = view["current"]
        assertThat(current["readable"].asBoolean()).isTrue()
        assertThat(current.absent("audit")).isTrue()
        val pdf = read("/api/v1/files/${current["fileId"].asString()}/versions/${current["versionId"].asString()}/content", alice.session)
        val file = Files.createTempFile("record", ".pdf")
        try {
            Files.write(file, pdf.response.contentAsByteArray)
            assertThat(validator.validate(file).compliant).isTrue()
        } finally {
            Files.deleteIfExists(file)
        }
        assertThat(get(memo, alice.session).json()["rendition"].asString()).isEqualTo("READY")
    }

    @Test
    fun `a document that no longer matches its stamp fails for good and stores nothing`() {
        val (memo, _, _) = approved()
        // As the schema owner (a test of tampering only): the stamp now names another document hash.
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c
                .prepareStatement(
                    "update memo_approval set content = jsonb_set(content, '{sha256}', to_jsonb(repeat('0', 64))) where memo_id = ?",
                ).use {
                    it.setObject(1, UUID.fromString(memo))
                    assertThat(it.executeUpdate()).isPositive()
                }
            c.commit()
        }
        build(memo)
        val record = versions(memo).single()
        assertThat(record.state).isEqualTo(RenditionState.FAILED)
        assertThat(record.error).isEqualTo("HASH_MISMATCH")
        assertThat(record.fileVersionId).isNull()
        Audit.assertAudited(tenant.id, "memo.rendition.failed", memo)
    }

    @Test
    fun `the backfill gives an approved memo without a record its version 1, once`() {
        val (memo, _, _) = approved()
        // As if approved before E04: no record rows.
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.prepareStatement("delete from memo_rendition where memo_id = ?").use {
                it.setObject(1, UUID.fromString(memo))
                it.executeUpdate()
            }
            c.commit()
        }
        assertThat(versions(memo)).isEmpty()
        asJob { transactions.inTenant(tenant.id) { renditions.backfill() } }
        asJob { transactions.inTenant(tenant.id) { renditions.backfill() } }
        assertThat(versions(memo).map { it.trigger }).containsExactly(RenditionTrigger.BACKFILL)
    }
}
