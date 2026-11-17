package com.memo.memo

import com.memo.memo.internal.AttachmentRules
import com.memo.memo.internal.MemoAttachmentPurpose
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypes
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Memo attachments (ADR-0046; test plan E02 T4): added by upload while the memo is editable, a stricter level only,
 * listed without details to readers not cleared for them and refused to them, the type's rules at submit, locked during
 * review, each round's binding kept, and the tenant's list of types.
 */
class MemoAttachmentsIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Test
    fun `an upload adds an attachment, listed with its title and type, and audited`() {
        val alice = person()
        val memo = aDraft(alice)
        val upload = attach(memo, alice, Samples.pdf(), "quotation.pdf")
        assertThat(upload).hasStatus(HttpStatus.CREATED)
        val list = attachments(memo, alice)
        assertThat(list.size()).isEqualTo(1)
        assertThat(list[0]["title"].asString()).isEqualTo("quotation.pdf")
        assertThat(list[0]["mediaType"].asString()).isEqualTo("application/pdf")
        assertThat(list[0]["restricted"].asBoolean()).isFalse()
        Audit.assertAudited(tenant.id, "memo.attachment.added", memo)
    }

    @Test
    fun `a level must be stricter than the memo's, and an approver not cleared for it sees no details and cannot open it`() {
        val alice = person(clearance = 1)
        val bob = person()
        val memo = aDraft(alice)
        attach(memo, alice, Samples.pdf(), "valuation.pdf")
        val attachment = attachments(memo, alice)[0]
        val id = attachment["id"].asString()

        val same = change(memo, alice, id, """{"classificationId":"${levels()[0].id}"}""", attachment["version"].asLong())
        assertThat(same.errors()).containsExactly("classificationId:NOT_STRICTER")
        val stricter = change(memo, alice, id, """{"classificationId":"${levels()[1].id}"}""", attachment["version"].asLong())
        assertThat(stricter).hasStatusOk()
        assertThat(stricter.json()["classification"]["id"].asString()).isEqualTo(levels()[1].id.toString())
        Audit.assertAudited(tenant.id, "memo.attachment.reclassified", memo)
        val renamed = change(memo, alice, id, """{"title":"Valuation 2026"}""", stricter.json()["version"].asLong())
        assertThat(renamed.json()["title"].asString()).isEqualTo("Valuation 2026")
        Audit.assertAudited(tenant.id, "memo.attachment.retitled", memo)

        submit(memo, alice, bob)
        val seen = attachments(memo, bob)[0]
        assertThat(seen["restricted"].asBoolean()).isTrue()
        assertThat(seen.absent("title")).isTrue()
        assertThat(seen.absent("fileId")).isTrue()
        val fileId = attachments(memo, alice)[0]["fileId"].asString()
        assertThat(read("/api/v1/files/$fileId/content", bob.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/files/$fileId/content", alice.session)).hasStatusOk()
    }

    @Test
    fun `the type's rules are checked at submit`() {
        val alice = person()
        val bob = person()
        val typeId = typeNeedingAttachments(2)
        val memo =
            create(alice.session, """{"subject":"Purchase","typeId":"$typeId","classificationId":"${levels()[0].id}"}""")
                .json()["id"]
                .asString()
        uploadDocument(memo, alice.session)
        route(memo, alice, bob)
        attach(memo, alice, Samples.pdf(), "one.pdf")
        assertThat(post("/api/v1/memos/$memo/submit", alice.session).errors()).containsExactly("attachments:ATTACHMENTS_REQUIRED")
        attach(memo, alice, Samples.png(), "two.png")
        assertThat(post("/api/v1/memos/$memo/submit", alice.session)).hasStatusOk()
    }

    @Test
    fun `attachments are locked during review, and each round keeps what it bound`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        attach(memo, alice, Samples.pdf(), "a.pdf")
        attach(memo, alice, Samples.png(), "b.png")
        submit(memo, alice, bob)

        val first = attachments(memo, alice)
        // The purpose refuses a new file once the memo is not editable.
        assertThat(attach(memo, alice, Samples.pdf(), "late.pdf")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(change(memo, alice, first[0]["id"].asString(), """{"title":"x"}""", first[0]["version"].asLong()))
            .hasStatus(HttpStatus.CONFLICT)
        assertThat(remove(memo, alice, first[1]["id"].asString())).hasStatus(HttpStatus.CONFLICT)

        val round1 = read("/api/v1/memos/$memo/rounds/1/attachments", alice.session).json()
        assertThat(round1.size()).isEqualTo(2)
        assertThat(round1[0]["versionNumber"].asInt()).isEqualTo(1)

        // Returned: one removed, the other gets version 2; the resubmission binds the new state.
        post("/api/v1/tasks/${task(memo, bob)}/return", bob.session, """{"remark":"Replace the photo"}""")
        assertThat(remove(memo, alice, first[1]["id"].asString())).hasStatus(HttpStatus.NO_CONTENT)
        val fileId = first[0]["fileId"].asString()
        assertThat(
            upload(alice.session, Samples.pdf(pages = 2), "/api/v1/files/$fileId/versions", "a.pdf", mapOf("If-Match" to "\"1\"")),
        ).hasStatus(HttpStatus.CREATED)
        assertThat(post("/api/v1/memos/$memo/resubmit", alice.session)).hasStatusOk()

        val round2 = read("/api/v1/memos/$memo/rounds/2/attachments", alice.session).json()
        assertThat(round2.size()).isEqualTo(1)
        assertThat(round2[0]["versionNumber"].asInt()).isEqualTo(2)
        val round1Again = read("/api/v1/memos/$memo/rounds/1/attachments", alice.session).json()
        assertThat(round1Again.size()).isEqualTo(2)
        assertThat(round1Again[0]["versionNumber"].asInt()).isEqualTo(1)
        Audit.assertAudited(tenant.id, "memo.attachment.removed", memo)
    }

    @Test
    fun `the tenant can narrow the types, and another initiator cannot attach`() {
        val alice = person()
        val mallory = person()
        val memo = aDraft(alice)
        transactions.inTenant(tenant.id) { settings.put(MemoAttachmentPurpose.TYPES_SETTING, "pdf") }
        assertThat(attach(memo, alice, Samples.docx(), "notes.docx").response.status).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value())
        assertThat(attach(memo, alice, Samples.pdf(), "ok.pdf")).hasStatus(HttpStatus.CREATED)
        assertThat(
            attach(memo, mallory, Samples.pdf(), "theirs.pdf").response.status,
        ).isIn(HttpStatus.FORBIDDEN.value(), HttpStatus.NOT_FOUND.value())
    }

    private fun attach(
        memo: String,
        who: Person,
        bytes: ByteArray,
        name: String,
    ) = upload(who.session, bytes, "/api/v1/files?purpose=${MemoAttachmentPurpose.KEY}&owner=$memo", name)

    private fun attachments(
        memo: String,
        who: Person,
    ) = read("/api/v1/memos/$memo/attachments", who.session).json()

    private fun change(
        memo: String,
        who: Person,
        id: String,
        body: String,
        version: Long,
    ) = mvc.sendJson(
        HttpMethod.PATCH,
        tenant,
        "/api/v1/memos/$memo/attachments/$id",
        who.session,
        body,
        mapOf("If-Match" to "\"$version\""),
    )

    private fun remove(
        memo: String,
        who: Person,
        id: String,
    ) = mvc.sendJson(HttpMethod.DELETE, tenant, "/api/v1/memos/$memo/attachments/$id", who.session, "{}", emptyMap())

    private fun submit(
        memo: String,
        initiator: Person,
        approver: Person,
    ) {
        uploadDocument(memo, initiator.session)
        route(memo, initiator, approver)
        assertThat(post("/api/v1/memos/$memo/submit", initiator.session)).hasStatusOk()
    }

    private fun task(
        memo: String,
        who: Person,
    ): String = get(memo, who.session).json()["myTask"]["id"].asString()

    /** A published type whose attachment rules need [min] attachments: drafted by the admin, approved by a second one. */
    private fun typeNeedingAttachments(min: Int): UUID {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val type = asUser(admin) { types.create(MemoTypeFields("Purchase ${UUID.randomUUID().toString().take(6)}", null, false, true)) }
        val published = asUser(admin) { types.published(type.id)!! }
        val draft =
            asUser(admin) {
                lifecycle.newDraft(type.id, published.settings.copy(attachmentRules = AttachmentRules(required = true, min = min)))
            }
        asUser(admin) { lifecycle.submit(type.id, draft.number, draft.revision) }
        asUser(checker) { lifecycle.approve(type.id, draft.number, null) }
        return type.id
    }
}
