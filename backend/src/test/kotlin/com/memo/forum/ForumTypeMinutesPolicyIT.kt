package com.memo.forum

import com.memo.common.numbering.NumberReset
import com.memo.common.review.ReviewState
import com.memo.forum.internal.ForumRejectedException
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.MinutesTemplatePurpose
import com.memo.identity.MfaSeeding
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.Docx
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.adminSession
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * A committee type's minutes policy (design E06 §1.8; test plan T2 `ForumTypeMinutesPolicyIT`): the presets (boards and
 * board committees at the next meeting, management committees by circulation), null read as the defaults, the checks
 * with their fields, the maker-checker path with its audit and the policy pinned per version; the minutes template
 * uploaded for a draft, checked with suggestions, previewed with the sample meeting, and refused at submit while it has
 * problems; the resolution number's live example.
 */
class ForumTypeMinutesPolicyIT : MemoIntegrationTest() {
    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var lifecycle: ForumTypeLifecycle

    @Autowired lateinit var policies: ForumPolicies

    @Autowired lateinit var mfaSeeding: MfaSeeding

    private fun type(
        code: String,
        preset: ForumTypePreset = ForumTypePreset.BOARD_COMMITTEE,
    ): UUID = asUser(admin) { types.create(code, "$code committee", preset) }.type.id

    @Test
    fun `presets approve at the next meeting or by circulation, and null reads as the defaults`() {
        val board = asUser(admin) { types.create("PB", "Board", ForumTypePreset.BOARD) }.open!!.settings.minutes
        val committee = asUser(admin) { types.create("PBC", "Board committee", ForumTypePreset.BOARD_COMMITTEE) }.open!!.settings.minutes
        val management = asUser(admin) { types.create("PMC", "Management", ForumTypePreset.MANAGEMENT_COMMITTEE) }.open!!.settings.minutes
        assertThat(board).isEqualTo(MinutesPolicy())
        assertThat(committee).isEqualTo(MinutesPolicy())
        assertThat(management).isEqualTo(MinutesPolicy(approval = MinutesApproval.BY_CIRCULATION))
        assertThat(listOf(board, committee, management)).allSatisfy {
            assertThat(it.memoResumes).isEqualTo(MemoResumes.AT_MEETING_END)
            assertThat(it.resolutionNumbering).isEqualTo(ResolutionNumbering())
            assertThat(it.templateFileId).isNull()
        }

        val old = type("OLD")
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("update forum_type_version set minutes = null where forum_type_id = ?").use {
                it.setObject(1, old)
                it.executeUpdate()
            }
        }
        assertThat(asUser(admin) { lifecycle.get(old, 1) }.settings.minutes).isEqualTo(MinutesPolicy())
    }

    @Test
    fun `the policy's checks name their fields`() {
        val id = type("CHK")
        val draft = asUser(admin) { lifecycle.get(id, 1) }

        fun save(policy: MinutesPolicy) = problems { lifecycle.replace(id, 1, draft.revision, draft.settings.copy(minutes = policy)) }

        assertThat(save(MinutesPolicy(approval = MinutesApproval.BY_CIRCULATION, circulationWorkingDays = 0)))
            .isEqualTo("minutes/circulationWorkingDays:RANGE")
        assertThat(save(MinutesPolicy(draftWorkingDays = 31))).isEqualTo("minutes/draftWorkingDays:RANGE")
        assertThat(save(MinutesPolicy(resolutionNumbering = ResolutionNumbering(reset = NumberReset.FINANCIAL_YEAR))))
            .isEqualTo("minutes/resolutionNumbering/reset:FINANCIAL_YEAR_MISSING")
        val noCode = ResolutionNumbering(parts = ResolutionNumbering.DEFAULT_PARTS.drop(2))
        assertThat(save(MinutesPolicy(resolutionNumbering = noCode))).isEqualTo("minutes/resolutionNumbering/parts:COMMITTEE_CODE_MISSING")
        assertThat(save(MinutesPolicy(templateFileId = UUID.randomUUID()))).isEqualTo("minutes/templateFileId:unknown")
    }

    @Test
    fun `a change is reviewed by a second administrator, audited, and pinned for meetings`() {
        val id = type("BRC")
        val draft = asUser(admin) { lifecycle.get(id, 1) }
        val numbering =
            ResolutionNumbering(
                parts =
                    listOf(
                        ResolutionNumberPart(ResolutionPartKind.COMMITTEE_CODE),
                        ResolutionNumberPart(ResolutionPartKind.TEXT, "/"),
                        ResolutionNumberPart(ResolutionPartKind.MEETING_NUMBER),
                        ResolutionNumberPart(ResolutionPartKind.TEXT, "/"),
                        ResolutionNumberPart(ResolutionPartKind.YEAR, digits = 2),
                        ResolutionNumberPart(ResolutionPartKind.TEXT, "-"),
                        ResolutionNumberPart(ResolutionPartKind.SEQUENCE, digits = 2),
                    ),
            )
        val policy =
            MinutesPolicy(
                approval = MinutesApproval.BY_CIRCULATION,
                circulationWorkingDays = 7,
                draftWorkingDays = 15,
                memoResumes = MemoResumes.ON_MINUTES_APPROVAL,
                resolutionNumbering = numbering,
            )
        val saved = asUser(admin) { lifecycle.replace(id, 1, draft.revision, draft.settings.copy(minutes = policy)) }
        val row = Audit.assertAudited(tenant.id, "forum.type.version.saved", id.toString(), admin.id.value.toString())
        assertThat(
            row.canonical,
        ).contains("BY_CIRCULATION", "ON_MINUTES_APPROVAL", "{COMMITTEE_CODE}/{MEETING_NUMBER}/{YEAR:2}-{SEQUENCE:2}")
        asUser(admin) { lifecycle.submit(id, 1, saved.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        assertThat(asUser(checker) { lifecycle.approve(id, 1, null) }.state).isEqualTo(ReviewState.PUBLISHED)

        assertThat(asUser(admin) { policies.version(id, 1) }?.minutes).isEqualTo(policy)
        val next = asUser(admin) { lifecycle.newDraft(id) }
        assertThat(next.settings.minutes).isEqualTo(policy)
        asUser(admin) { lifecycle.replace(id, 2, next.revision, next.settings.copy(minutes = MinutesPolicy())) }
        assertThat(asUser(admin) { policies.version(id, 1) }?.minutes).describedAs("version 1 is unchanged").isEqualTo(policy)
        assertThat(asUser(admin) { policies.published(id) }?.minutes).isEqualTo(policy)
        assertThat(ResolutionFormats.render(policy.resolutionNumbering, "BRC", 3, java.time.LocalDate.of(2026, 11, 19), 14))
            .isEqualTo("BRC/3/26-14")
    }

    @Test
    fun `an uploaded template becomes the draft's, is checked with suggestions and previewed, and blocks the submit while wrong`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val id = type("TPL")
        val base = "/api/v1/admin/forum-types/$id/versions/1"

        val builtIn = read("$base/minutes-template", session)
        assertThat(builtIn).hasStatusOk()
        val owner = builtIn.json()["owner"].asString()
        assertThat(builtIn.json().absent("template")).isTrue()
        assertThat(builtIn.json()["problems"].size()).isZero()
        val available = builtIn.json()["available"].let { a -> (0 until a.size()).map { a[it]["tag"].asString() } }
        assertThat(available).contains("{{meeting.label}}", "{{present}}", "[name]", "{{?items}}…{{/items}}", "{{item.notes}}")

        // A misspelt tag: uploaded, made the draft's template, listed with its suggestion; the submit is refused.
        val before = asUser(admin) { lifecycle.get(id, 1) }
        assertThat(upload(session, typo(), owner)).hasStatus(HttpStatus.CREATED)
        val after = asUser(admin) { lifecycle.get(id, 1) }
        val fileId = checkNotNull(after.settings.minutes.templateFileId)
        assertThat(after.revision).isGreaterThan(before.revision)
        Audit.assertAudited(tenant.id, "forum.type.minutes_template_uploaded", id.toString())
        val checked = read("$base/minutes-template", session).json()
        assertThat(checked["template"]["fileId"].asString()).isEqualTo(fileId.toString())
        assertThat(checked["problems"][0]["code"].asString()).isEqualTo("UNKNOWN_TAG")
        assertThat(checked["problems"][0]["tag"].asString()).isEqualTo("{{item.discusion}}")
        assertThat(checked["problems"][0]["suggestion"].asString()).isEqualTo("{{item.notes}}")
        assertThat(post("$base/minutes-template/preview", session).json()["reason"].asString()).isEqualTo("TEMPLATE_PROBLEMS")
        assertThat(submitWith(session, base, after.revision).errors()).containsExactly("minutes/templateFileId:TEMPLATE_PROBLEMS")

        // A corrected template (a new version of the same file) previews, and the draft can be submitted.
        val fixed =
            upload(session, good(), "/api/v1/files/$fileId/versions", headers = mapOf("If-Match" to "\"1\""))
        assertThat(fixed).hasStatus(HttpStatus.CREATED)
        assertThat(read("$base/minutes-template", session).json()["problems"].size()).isZero()
        val preview = post("$base/minutes-template/preview", session)
        assertThat(preview).hasStatusOk()
        assertThat(read("/api/v1/files/${preview.json()["fileId"].asString()}", session)).hasStatusOk()
        val current = asUser(admin) { lifecycle.get(id, 1) }
        assertThat(submitWith(session, base, current.revision)).hasStatusOk()

        // Out of draft, no upload is accepted for the version, and another type cannot borrow the file.
        assertThat(upload(session, good(), owner).response.status).isIn(403, 404, 409)
        val other = type("OTH")
        val otherDraft = asUser(admin) { lifecycle.get(other, 1) }
        assertThat(
            problems {
                lifecycle.replace(other, 1, otherDraft.revision, otherDraft.settings.copy(minutes = MinutesPolicy(templateFileId = fileId)))
            },
        ).isEqualTo("minutes/templateFileId:unknown")
    }

    @Test
    fun `the resolution number's live example`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val example = post("/api/v1/admin/forum-types/resolution-numbering/preview", session, """{"numbering":{},"committeeCode":"BRC"}""")
        assertThat(example).hasStatusOk()
        val samples = example.json()["samples"]
        assertThat(samples[0].asString()).matches("BRC/20\\d\\d/014")
        assertThat(samples[1].asString()).matches("BRC/20\\d\\d/001")
        val wrong =
            post("/api/v1/admin/forum-types/resolution-numbering/preview", session, """{"numbering":{"parts":[]},"committeeCode":"b r"}""")
        val problems =
            wrong.json()["problems"].let { p ->
                (0 until p.size()).map { "${p[it]["field"].asString()}:${p[it]["code"].asString()}" }
            }
        assertThat(problems).contains("numbering/parts:COUNT", "numbering/parts:COMMITTEE_CODE_MISSING", "committeeCode:FORMAT")
    }

    private fun submitWith(
        session: Cookie,
        base: String,
        revision: Long,
    ) = mvc.sendJson(org.springframework.http.HttpMethod.POST, tenant, "$base/submit", session, "{}", mapOf("If-Match" to "\"$revision\""))

    private fun upload(
        session: Cookie,
        docx: ByteArray,
        owner: String,
    ) = upload(session, docx, "/api/v1/files?purpose=${MinutesTemplatePurpose.KEY}&owner=$owner", "minutes.docx")

    private fun typo(): ByteArray =
        Docx.paragraphs(
            listOf("Minutes of {{meeting.label}}"),
            listOf("{{?items}}"),
            listOf("{{item.number}}. {{item.title}}"),
            listOf("Key points: {{item.discusion}}"),
            listOf("{{/items}}"),
        )

    private fun good(): ByteArray =
        Docx.paragraphs(
            listOf("Minutes of {{meeting.label}}"),
            listOf("{{?items}}"),
            listOf("{{item.number}}. {{item.title}}"),
            listOf("Key points: {{item.notes}}"),
            listOf("{{/items}}"),
        )

    private fun problems(block: () -> Any): String {
        val rejected = runCatching { asUser(admin, block) }.exceptionOrNull() as? ForumRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }
}
