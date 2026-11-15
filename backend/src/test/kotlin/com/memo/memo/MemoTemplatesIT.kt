package com.memo.memo

import com.memo.common.review.ReviewState
import com.memo.common.web.RefusedException
import com.memo.identity.MfaSeeding
import com.memo.memo.internal.FieldRules
import com.memo.memo.internal.FieldType
import com.memo.memo.internal.MemoField
import com.memo.memo.internal.MemoTemplatePurpose
import com.memo.memo.internal.MemoTemplateStore
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypes
import com.memo.templating.FillFailure
import com.memo.testsupport.Audit
import com.memo.testsupport.Docx
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.TestTemplateFiller
import com.memo.testsupport.aPassword
import com.memo.testsupport.adminSession
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Templates on memo types (ADR-0048; test plan E03 T2): upload, check with problems and suggestions, preview, the
 * maker-checker rules, publish, and a memo whose document v1 is the filled template; a failed fill leaves the memo
 * without a document and offers FILL_FROM_TEMPLATE.
 */
class MemoTemplatesIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var templates: MemoTemplateStore

    private val amount =
        MemoField(key = "amount", label = "Facility amount", type = FieldType.AMOUNT, rules = FieldRules(currencies = listOf("BDT")))

    /** A credit facility type, draft version 1 with an amount field. */
    private fun creditDraft(): Pair<UUID, UUID> {
        val (type, _) = asUser(admin) { types.createDraft("Credit ${UUID.randomUUID().toString().take(6)}", "C${(100..999).random()}") }
        val draft = asUser(admin) { lifecycle.list(type.id) }.single { it.state == ReviewState.DRAFT }
        val changed =
            asUser(admin) { lifecycle.replace(type.id, draft.number, draft.revision, draft.settings.copy(fields = listOf(amount))) }
        return type.id to changed.id
    }

    private fun base(typeId: UUID) = "/api/v1/admin/memo-types/$typeId/versions/1/template"

    private fun uploadTemplate(
        session: Cookie,
        versionId: UUID,
        docx: ByteArray,
    ) = upload(session, docx, "/api/v1/files?purpose=${MemoTemplatePurpose.KEY}&owner=$versionId", "letterhead.docx")

    private fun submit(typeId: UUID): RefusedException? {
        val draft = asUser(admin) { lifecycle.get(typeId, 1) }
        return catchThrowableOfType(RefusedException::class.java) { asUser(admin) { lifecycle.submit(typeId, 1, draft.revision) } }
    }

    @Test
    fun `a template is checked, previewed and published, and a memo of the type starts from it`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val (typeId, versionId) = creditDraft()

        val empty = read(base(typeId), session).json()
        assertThat(empty.absent("template")).isTrue()
        assertThat((0 until empty["available"].size()).map { empty["available"][it]["tag"].asString() })
            .contains("{{memo.number}}", "{{meta.amount}}", "{{?meta.amount}}…{{/meta.amount}}", "{{approvals}}", "[decidedAt]")

        // A typo is a problem with a suggestion, and the version cannot be previewed or submitted.
        assertThat(uploadTemplate(session, versionId, Docx.letterhead(listOf("Ref {{memo.numbr}}")))).hasStatus(HttpStatus.CREATED)
        val typo = read(base(typeId), session).json()["template"]
        assertThat(typo["problems"][0]["code"].asString()).isEqualTo("UNKNOWN_TAG")
        assertThat(typo["problems"][0]["suggestion"].asString()).isEqualTo("{{memo.number}}")
        assertThat(typo["previewed"].asBoolean()).isFalse()
        assertThat(post("${base(typeId)}/preview", session).json()["reason"].asString()).isEqualTo("TEMPLATE_PROBLEMS")
        assertThat(submit(typeId)?.reason).isEqualTo("TEMPLATE_NOT_PREVIEWED")

        // The corrected template previews; then the version can be submitted and published.
        assertThat(uploadTemplate(session, versionId, Docx.letterhead())).hasStatus(HttpStatus.CREATED)
        Audit.assertAudited(tenant.id, "memo.type.template_uploaded", typeId.toString())
        assertThat(read(base(typeId), session).json()["template"]["problems"].size()).isZero()
        assertThat(submit(typeId)?.reason).isEqualTo("TEMPLATE_NOT_PREVIEWED")
        val preview = post("${base(typeId)}/preview", session)
        assertThat(preview).hasStatusOk()
        assertThat(read("/api/v1/files/${preview.json()["fileId"].asString()}", session)).hasStatusOk()
        assertThat(read(base(typeId), session).json()["template"]["previewed"].asBoolean()).isTrue()
        assertThat(submit(typeId)).isNull()
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { lifecycle.approve(typeId, 1, null) }
        val template = asUser(admin) { templates.of(versionId)!! }

        // A memo of the type: document v1 is the filled template, and the memo records the template version.
        val alice = person()
        val created =
            create(
                alice.session,
                """{"subject":"Working capital, Rahman Textiles","typeId":"$typeId",""" +
                    """"metadata":{"amount":{"value":"25000000","currency":"BDT"}}}""",
            )
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val memo = created.json()
        assertThat(memo.absent("document")).isFalse()
        assertThat(
            (0 until memo["allowedActions"].size()).map { memo["allowedActions"][it].asString() },
        ).doesNotContain("FILL_FROM_TEMPLATE")
        val fileId = memo["document"]["fileId"].asString()
        val text = Docx.text(read("/api/v1/files/$fileId/content", alice.session).response.contentAsByteArray)
        assertThat(text).contains(
            "Subject: Working capital, Rahman Textiles",
            "Facility amount: BDT 25,000,000.00",
            "Ref: (number on submit)",
            "Approver\tDecision\tDate",
        )
        val memoId = UUID.fromString(memo["id"].asString())
        assertThat(asUser(admin) { templates.filledFrom(memoId) }).isEqualTo(template.fileVersionId)
        Audit.assertAudited(tenant.id, "memo.document.filled", memoId.toString())

        // Filling again is refused: the memo has its document.
        assertThat(post("/api/v1/memos/$memoId/document/from-template", alice.session).json()["reason"].asString()).isEqualTo("WRONG_STATE")

        // A new draft of the type starts with the published template, already previewed.
        val next = asUser(admin) { lifecycle.newDraft(typeId, null) }
        val copied = asUser(admin) { templates.of(next.id)!! }
        assertThat(copied.fileVersionId).isEqualTo(template.fileVersionId)
        assertThat(copied.previewed).isTrue()
    }

    @Test
    fun `a fill that fails leaves the memo without a document, and the initiator can fill it again`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val (typeId, versionId) = creditDraft()
        uploadTemplate(session, versionId, Docx.letterhead())
        post("${base(typeId)}/preview", session)
        assertThat(submit(typeId)).isNull()
        asUser(accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)) { lifecycle.approve(typeId, 1, null) }

        val alice = person()
        TestTemplateFiller.failNext(FillFailure.TIMEOUT)
        val created = create(alice.session, """{"subject":"Retry","typeId":"$typeId"}""")
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val memo = created.json()
        assertThat(memo.absent("document")).isTrue()
        assertThat((0 until memo["allowedActions"].size()).map { memo["allowedActions"][it].asString() }).contains("FILL_FROM_TEMPLATE")

        val bob = person()
        assertThat(post("/api/v1/memos/${memo["id"].asString()}/document/from-template", bob.session)).hasStatus(HttpStatus.NOT_FOUND)
        val filled = post("/api/v1/memos/${memo["id"].asString()}/document/from-template", alice.session)
        assertThat(filled).hasStatusOk()
        assertThat(filled.json().absent("document")).isFalse()
        // A type without a template has nothing to fill from.
        val general = aDraft(alice)
        assertThat(
            post("/api/v1/memos/$general/document/from-template", alice.session).json()["reason"].asString(),
        ).isEqualTo("NO_TEMPLATE")
    }

    @Test
    fun `only a type manager previews or removes a template, reviewers may read it, and a removal needs the current revision`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val (typeId, versionId) = creditDraft()
        uploadTemplate(session, versionId, Docx.letterhead())
        val alice = person()
        assertThat(read(base(typeId), alice.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(post("${base(typeId)}/preview", alice.session)).hasStatus(HttpStatus.FORBIDDEN)

        val revision = asUser(admin) { lifecycle.get(typeId, 1) }.revision
        val stale =
            mvc.sendJson(HttpMethod.DELETE, tenant, base(typeId), session, "{}", mapOf("If-Match" to "\"${revision - 1}\""))
        assertThat(stale).hasStatus(HttpStatus.PRECONDITION_FAILED)
        val removed = mvc.sendJson(HttpMethod.DELETE, tenant, base(typeId), session, "{}", mapOf("If-Match" to "\"$revision\""))
        assertThat(removed).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(read(base(typeId), session).json().absent("template")).isTrue()
        Audit.assertAudited(tenant.id, "memo.type.template_removed", typeId.toString())
        // Without a template, the version is submitted as before.
        assertThat(submit(typeId)).isNull()
    }
}
