package com.memo.minutes

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.memo.internal.rendition.MemoRenditionPurpose
import com.memo.memo.internal.rendition.MemoRenditionStore
import com.memo.memo.internal.rendition.MemoRenditions
import com.memo.memo.internal.rendition.RecordBuilder
import com.memo.memo.internal.rendition.RenditionState
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The whole path from the memo to the resolution and back (ADR-0058, ADR-0065; test plan E06 T4, "ForumEscalationIT
 * extended"): a credit memo is reviewed and escalated to the committee, placed on its meeting's agenda and decided there;
 * when the meeting is marked as held the decision takes its number, the memo resumes and its final approver approves it,
 * and its final record carries the resolution in the approvals and on the note sheet.
 */
class ForumEscalationIT : ReleaseFixture() {
    @Autowired lateinit var renditions: MemoRenditions

    @Autowired lateinit var renditionStore: MemoRenditionStore

    @Autowired lateinit var builder: RecordBuilder

    @BeforeEach
    fun recordsByHand() {
        renditions.jobs = false
    }

    @AfterEach
    fun recordJobsBack() {
        renditions.jobs = true
    }

    @Test
    fun `an escalated memo resumes with its resolution number, and its final record carries it`() {
        val r = running("Term loan, Delta Textiles")
        val credit = r.items.single()
        // Waiting at the committee: nobody has a task on the memo.
        assertThat(get(credit.memo.toString(), colleague.session).json().absent("myTask")).isTrue()
        decide(r, credit.item, APPROVED_WITH_CONDITIONS)
        held(r)
        val year =
            r.meeting.startsAt
                .atZone(r.meeting.zone)
                .year
        val number = "BRC/$year/001"
        assertThat(numberOf(credit.item)).isEqualTo(number)

        // The memo resumed at its final approval, who approves it.
        val task = get(credit.memo.toString(), colleague.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", colleague.session, """{"remark":"Approved per the committee."}""")).hasStatusOk()
        assertThat(get(credit.memo.toString(), initiator.session).json()["status"].asString()).isEqualTo("APPROVED")

        // Its final record: the committee's decision among the approvals, and its remark on the note sheet.
        val version = transactions.inTenant(tenant.id) { renditionStore.of(credit.memo) }.single()
        TenantContext.call(tenant.id) { ActorContext.asSystem(MemoRenditionPurpose.JOB) { builder.build(version.id) } }
        val record = transactions.inTenant(tenant.id) { renditionStore.of(credit.memo) }.single()
        assertThat(record.state).isEqualTo(RenditionState.READY)
        val text = text(credit.memo, record.fileId, record.fileVersionId)
        val flat = text.replace(Regex("\\s+"), " ")
        assertThat(flat).contains(
            "Board Risk Committee — Approved with conditions — Resolution $number",
            "The committee's decision (remark)",
            "Resolution $number: It was RESOLVED",
        )
    }

    /** The record's text, as its reader downloads it. */
    private fun text(
        memo: UUID,
        fileId: UUID?,
        versionId: UUID?,
    ): String {
        val pdf = read("/api/v1/files/$fileId/versions/$versionId/content", initiator.session)
        assertThat(pdf).hasStatusOk()
        return Loader.loadPDF(pdf.response.contentAsByteArray).use { PDFTextStripper().getText(it) }.also {
            assertThat(it).describedAs("the record of $memo").isNotBlank()
        }
    }
}
