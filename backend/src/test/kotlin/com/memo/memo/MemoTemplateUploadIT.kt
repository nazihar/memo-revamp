package com.memo.memo

import com.memo.common.review.ReviewState
import com.memo.identity.MfaSeeding
import com.memo.memo.internal.MemoTemplatePurpose
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypes
import com.memo.testsupport.Audit
import com.memo.testsupport.Docx
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.adminSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Memo templates as files (ADR-0048; test plan E03 T1): uploaded for a draft type version by a type manager, refused
 * with the rules a package breaks (and audited), refused for a published version and for other people.
 */
class MemoTemplateUploadIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var mfaSeeding: MfaSeeding

    /** A new type's draft version 1, made by the base class's admin. */
    private fun draftVersion(): UUID {
        val (type, _) = asUser(admin) { types.createDraft("Credit ${UUID.randomUUID().toString().take(6)}", "C${(100..999).random()}") }
        return asUser(admin) { lifecycle.list(type.id) }.single { it.state == ReviewState.DRAFT }.id
    }

    private fun uri(owner: Any) = "/api/v1/files?purpose=${MemoTemplatePurpose.KEY}&owner=$owner"

    @Test
    fun `a type manager uploads a clean template for a draft version`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val created = upload(session, Docx.letterhead(), uri(draftVersion()), "letterhead.docx")
        assertThat(created).hasStatus(HttpStatus.CREATED)
    }

    @Test
    fun `a template with macros or an external image is refused with its rules, and the refusal is audited`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val version = draftVersion()
        val macros =
            Docx.withRelationship(
                Docx.edit(Docx.letterhead(), mapOf("word/vbaProject.bin" to byteArrayOf(1, 2, 3))),
                "http://schemas.microsoft.com/office/2006/relationships/vbaProject",
                "vbaProject.bin",
            )
        val refused = upload(session, macros, uri(version), "letterhead.docx")
        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        val body = refused.json()
        assertThat(body["reason"].asString()).isEqualTo("CONTENT_REFUSED")
        assertThat((0 until body["refusals"].size()).map { body["refusals"][it]["rule"].asString() }.toSet()).containsExactly("MACROS")
        val rejected = Audit.entries(tenant.id).last { it.action == "file.upload.rejected" }
        assertThat(rejected.canonical).contains("CONTENT_REFUSED", "MACROS")

        val image = Docx.withRelationship(Docx.letterhead(), "${Docx.REL}/image", "https://tracker.example/pixel.png", external = true)
        val second = upload(session, image, uri(version), "letterhead.docx").json()
        assertThat(second["refusals"][0]["rule"].asString()).isEqualTo("EXTERNAL_RELATIONSHIP")
    }

    @Test
    fun `a published version, an unknown owner and people without the permission cannot upload one`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val general = asUser(admin) { types.active() }.single()
        val published = asUser(admin) { lifecycle.list(general.id) }.single { it.state == ReviewState.PUBLISHED }.id
        assertThat(upload(session, Docx.letterhead(), uri(published), "letterhead.docx")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(upload(session, Docx.letterhead(), uri(UUID.randomUUID()), "letterhead.docx")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(upload(session, Docx.letterhead(), uri("not-a-uuid"), "letterhead.docx")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(upload(person().session, Docx.letterhead(), uri(draftVersion()), "letterhead.docx")).hasStatus(HttpStatus.FORBIDDEN)
    }
}
