package com.memo.minutes

import com.memo.forum.ForumRole
import com.memo.forum.MemoResumes
import com.memo.forum.MinutesApproval
import com.memo.memo.internal.rendition.PdfaValidator
import com.memo.memo.internal.rendition.RecordFixity
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.ExtractMaker
import com.memo.minutes.internal.MinutesExtractPurpose
import com.memo.minutes.internal.MinutesExtractStore
import com.memo.minutes.internal.RecordState
import com.memo.minutes.internal.VoteMode
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.ageSessionFactors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Instant
import java.util.UUID

/**
 * Certified resolution extracts (test plan E06 T8 `ResolutionExtractsIT`; design E06 §1.6.6, flow AC3): a secretary of the
 * committee not recused from the item issues one with a fresh step-up once the memo has moved on (refused to members, the
 * chair, someone outside, a recused secretary, on a stale step-up, before the release and for a void number); the
 * `minutes-extract` job keeps it as a locked PDF/A saying whether the minutes are approved yet and showing only the
 * resolution (no vote, names or discussion); its readers (the secretary, `forum.read`, the memo's reader) open it, each
 * open audited, members do not; the weekly fixity run verifies it. The jobs are run by hand.
 */
class ResolutionExtractsIT : MinutesFixture() {
    @Autowired lateinit var extracts: ExtractMaker

    @Autowired lateinit var extractStore: MinutesExtractStore

    @Autowired lateinit var validator: PdfaValidator

    @Autowired lateinit var fixity: RecordFixity

    @Test
    fun `an extract says the minutes are not yet approved, then approved, shows only the resolution and is read by its readers`() {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val second = person()
        seat(second, ForumRole.SECRETARY)
        val r =
            drafted(
                "Term loan, Delta Textiles",
                "Facility renewal, Acme",
                before = { declare(second, it.meeting, it.items.first().item) },
                decisions = { run ->
                    decide(
                        run,
                        run.items.first().item,
                        APPROVED_WITH_CONDITIONS.copy(
                            voteMode = VoteMode.VOTE,
                            votesFor = 3,
                            votesAgainst = 1,
                            votesAbstain = 0,
                            dissent = listOf(Dissent(colleague.id, CANARY_DISSENT)),
                        ),
                    )
                    decide(run, run.items[1].item, APPROVED)
                    notesOn(run, run.items.first().item, CANARY_NOTES)
                },
            )
        val loan = r.items.first()
        val rid = checkNotNull(transactions.readInTenant(tenant.id) { decisionsApi.ofItem(loan.item) }?.resolutionId)

        // Who may issue: a secretary not recused, with a fresh step-up.
        assertThat(issue(rid, colleague)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(issue(rid, chair).json()["reason"].asString()).isEqualTo("NOT_SECRETARY")
        assertThat(issue(rid, outsider)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(issue(rid, second).json()["reason"].asString()).isEqualTo("RECUSED")
        ageSessionFactors(secretary.session)
        assertThat(issue(rid, secretary)).hasStatus(HttpStatus.UNAUTHORIZED)
        ageSessionFactors(secretary.session, issuedAt = Instant.now())

        val first = issued(rid)
        assertThat(first["minutesApproved"].asBoolean()).isFalse()
        val text = assertExtract(rid, UUID.fromString(first["id"].asString()))
        assertThat(text).contains("The minutes of this meeting have not yet been approved.")

        // An extract whose resolution is corrected before the job makes it is never printed: FAILED `CHANGED`.
        val stale = UUID.fromString(issued(rid)["id"].asString())
        decide(r, loan.item, APPROVED_WITH_CONDITIONS.copy(text = "It was RESOLVED that BDT 65 million be lent.", reason = "Amount"))
        asSystem { extracts.build(stale, last = true) }
        assertThat(transactions.readInTenant(tenant.id) { extractStore.find(stale) }?.error).isEqualTo("CHANGED")
        Audit.assertAudited(tenant.id, "minutes.extract.failed", r.meeting.id.toString())
        assertThat(content(rid, stale, secretary).json()["reason"].asString()).isEqualTo("EXTRACT_FAILED")

        // Once the chair approves the minutes, a new extract says so.
        circulated(r.meeting.id)
        assertThat(step(r.meeting.id, "end-circulation", secretary, """{"reason":"Needed for the drawdown"}""")).hasStatusOk()
        assertThat(step(r.meeting.id, "confirm", chair)).hasStatusOk()
        ageSessionFactors(secretary.session, issuedAt = Instant.now())
        val again = issued(rid)
        assertThat(again["minutesApproved"].asBoolean()).isTrue()
        val after = assertExtract(rid, UUID.fromString(again["id"].asString()))
        assertThat(after).contains("The minutes of this meeting were approved on").doesNotContain("not yet been approved")
        val listed = read("/api/v1/resolutions/$rid/extracts", secretary.session).json()
        assertThat(listed.size()).isEqualTo(3)
        assertThat(listed[0]["id"].asString()).isEqualTo(again["id"].asString())

        // The weekly fixity run checks the extracts.
        val run = asSystem { fixity.verify() }
        assertThat(run.sources.getValue("minutes.extract.verified").let { it.checked to it.mismatched }).isEqualTo(2 to 0)
        Audit.assertAudited(tenant.id, "minutes.extract.verified", null)
    }

    /** The extract made, locked and opened by its readers only; its text (whitespace collapsed). */
    private fun assertExtract(
        rid: UUID,
        id: UUID,
    ): String {
        assertThat(read("/api/v1/resolutions/$rid/extracts/$id/content", secretary.session)).hasStatus(HttpStatus.ACCEPTED)
        asSystem { extracts.build(id, last = true) }
        val row = checkNotNull(transactions.readInTenant(tenant.id) { extractStore.find(id) })
        assertThat(row.state to row.compliant).isEqualTo(RecordState.READY to true)
        Audit.assertAudited(tenant.id, "minutes.extract.issued", row.meetingId.toString())
        val stored = checkNotNull(transactions.readInTenant(tenant.id) { files.version(checkNotNull(row.fileVersionId)) })
        assertThat(checkNotNull(transactions.readInTenant(tenant.id) { files.info(stored.fileId) }).purpose)
            .isEqualTo(MinutesExtractPurpose.KEY)
        assertThat(stored.retainUntil).isAfter(Instant.now())

        val ofSecretary = content(rid, id, secretary)
        assertThat(ofSecretary).hasStatusOk()
        assertThat(ofSecretary.response.getHeader("Content-Disposition")).startsWith("attachment")
        val file =
            java.nio.file.Files.createTempFile("extract-", ".pdf").also {
                java.nio.file.Files
                    .write(it, ofSecretary.response.contentAsByteArray)
            }
        file.toFile().deleteOnExit()
        assertThat(validator.validate(file).compliant).isTrue()
        Audit.assertAudited(tenant.id, "minutes.extract.opened", row.meetingId.toString(), secretary.id.toString())
        val text = textOf(ofSecretary.response.contentAsByteArray).replace(WHITESPACE, " ")
        val number = checkNotNull(transactions.readInTenant(tenant.id) { resolutionsApi.find(rid) }).number
        assertThat(text).contains("Certified extract", number, "Security perfected before disbursement.", row.certificationHash.take(16))
        // Only the resolution: never the vote, the names or the discussion.
        assertThat(text).doesNotContain(CANARY_DISSENT, CANARY_NOTES, colleague.account.email)
        assertThat(text).doesNotContain("For 3")

        // Its readers: forum.read and the memo's reader (its reviewer); not a member who does not read the memo, nobody outside.
        assertThat(content(rid, id, reader)).hasStatusOk()
        assertThat(content(rid, id, reviewer)).hasStatusOk()
        assertThat(content(rid, id, director)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(content(rid, id, outsider)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/resolutions/$rid/extracts", director.session)).hasStatus(HttpStatus.FORBIDDEN)
        return text
    }

    @Test
    fun `no extract before the memo has moved on, nor of a void number`() {
        memosResume(MemoResumes.ON_MINUTES_APPROVAL)
        val r = drafted("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        val rid = checkNotNull(transactions.readInTenant(tenant.id) { decisionsApi.ofItem(loan.item) }?.resolutionId)
        val waiting = issue(rid, secretary)
        assertThat(waiting).hasStatus(HttpStatus.CONFLICT)
        assertThat(waiting.json()["reason"].asString()).isEqualTo("NOT_RELEASED")

        // The renewal corrected to "Noted" before its release: its number is void.
        val voided = checkNotNull(transactions.readInTenant(tenant.id) { decisionsApi.ofItem(renewal.item) }?.resolutionId)
        decide(r, renewal.item, NOTED.copy(reason = "Only noted on reflection"))
        val void = issue(voided, secretary)
        assertThat(void).hasStatus(HttpStatus.CONFLICT)
        assertThat(void.json()["reason"].asString()).isEqualTo("VOID")
        assertThat(transactions.readInTenant(tenant.id) { extractStore.of(rid) + extractStore.of(voided) }).isEmpty()
    }

    private fun issue(
        rid: UUID,
        by: Person,
    ): MvcTestResult = post("/api/v1/resolutions/$rid/extracts", by.session)

    /** The meeting [rid] was passed at, as the audit names it. */
    private fun meetingOf(rid: UUID): String =
        checkNotNull(transactions.readInTenant(tenant.id) { resolutionsApi.find(rid) }).meetingId.toString()

    private fun issued(rid: UUID) =
        issue(rid, secretary)
            .also {
                assertThat(it).describedAs(it.response.contentAsString).hasStatus(HttpStatus.ACCEPTED)
                assertThat(it.json()["state"].asString()).isEqualTo("PENDING")
                Audit.assertAudited(tenant.id, "minutes.extract.requested", meetingOf(rid), secretary.id.toString())
            }.json()

    private fun content(
        rid: UUID,
        id: UUID,
        by: Person,
    ) = read("/api/v1/resolutions/$rid/extracts/$id/content", by.session)

    private companion object {
        const val CANARY_NOTES = "CANARY-NT members challenged the borrower's sector outlook at length"
        const val CANARY_DISSENT = "CANARY-DS the sector outlook is too weak for this exposure"
        val WHITESPACE = Regex("""\s+""")
    }
}
