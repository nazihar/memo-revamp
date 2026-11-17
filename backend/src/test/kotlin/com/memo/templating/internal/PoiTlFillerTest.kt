package com.memo.templating.internal

import com.memo.templating.FillFailure
import com.memo.templating.TemplateData
import com.memo.templating.TemplateFillException
import com.memo.testsupport.Docx
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/** ADR-0048, test plan E03 T1: fills put text where the tags were, values stay text, and every failure has a kind. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiTlFillerTest {
    private val filler = PoiTlFiller(Duration.ofSeconds(20))

    @AfterAll
    fun stop() = filler.destroy()

    private val values =
        mapOf(
            "memo.number" to "CR-2026-00042",
            "memo.date" to "15 Nov 2026",
            "memo.subject" to "Working capital facility",
            "initiator.name" to "Demo User",
            "initiator.position" to "Relationship Manager",
            "meta.amount" to "BDT 25,000,000.00",
        )
    private val approvals =
        listOf(
            mapOf("name" to "Demo Head of Credit", "position" to "Head of Credit", "decision" to "Approved", "decidedAt" to "15 Nov 2026"),
            mapOf("name" to "Demo Director", "position" to "Director", "decision" to "Approved", "decidedAt" to "16 Nov 2026"),
        )

    private fun fill(
        template: ByteArray = Docx.letterhead(),
        data: TemplateData = TemplateData(values, mapOf("approvals" to approvals)),
        with: PoiTlFiller = filler,
    ) = Docx.text(with.fill(template.inputStream(), data))

    private fun failure(
        template: ByteArray = Docx.letterhead(),
        data: TemplateData = TemplateData(values, mapOf("approvals" to approvals)),
        with: PoiTlFiller = filler,
    ): FillFailure = catchThrowableOfType(TemplateFillException::class.java) { with.fill(template.inputStream(), data) }.failure

    @Test
    fun `the letterhead is filled in the body, header, footer and the approval rows, split tags included`() {
        val text = fill()
        assertThat(text).contains(
            "Subject: Working capital facility",
            "From: Demo User, Relationship Manager",
            "Facility amount: BDT 25,000,000.00",
            "Demo Head of Credit, Head of Credit\tApproved\t15 Nov 2026",
            "Demo Director, Director\tApproved\t16 Nov 2026",
            "Ref: CR-2026-00042",
            "Dated 15 Nov 2026",
        )
        assertThat(text).doesNotContain("{{", "}}", "[name]", "[decision]")
    }

    @Test
    fun `a block shows only when its value is not blank, and no approvals leave just the header row`() {
        val text = fill(data = TemplateData(values + ("meta.amount" to " "), mapOf("approvals" to emptyList())))
        assertThat(text).doesNotContain("Facility amount", "[name]")
        assertThat(text).contains("Approver\tDecision\tDate")
    }

    @Test
    fun `values go in as text, so tags, markup and entities in a value are not interpreted`() {
        val hostile = values + ("memo.subject" to "{{memo.number}} <w:p/> &amp; ]]> [name]")
        val text = fill(data = TemplateData(hostile, mapOf("approvals" to approvals)))
        assertThat(text).contains("Subject: {{memo.number}} <w:p/> &amp; ]]> [name]")
    }

    @Test
    fun `a missing value, a refused tag kind, or a broken template fails with its kind`() {
        assertThat(failure(data = TemplateData(values - "memo.date", mapOf("approvals" to approvals)))).isEqualTo(FillFailure.MISSING_VALUE)
        val row = approvals[0] - "position"
        assertThat(failure(data = TemplateData(values, mapOf("approvals" to listOf(row))))).isEqualTo(FillFailure.MISSING_VALUE)
        assertThat(failure(Docx.paragraphs(listOf("Logo {{@memo.number}}")))).isEqualTo(FillFailure.MISSING_VALUE)
        assertThat(failure("not a document".toByteArray())).isEqualTo(FillFailure.INVALID_TEMPLATE)
        assertThat(failure(ByteArray(PoiTlFiller.MAX_TEMPLATE_BYTES + 1))).isEqualTo(FillFailure.INVALID_TEMPLATE)
    }

    @Test
    fun `a fill that takes too long times out`() {
        val impatient = PoiTlFiller(Duration.ofMillis(1))
        try {
            assertThat(failure(with = impatient)).isEqualTo(FillFailure.TIMEOUT)
        } finally {
            impatient.destroy()
        }
    }
}
