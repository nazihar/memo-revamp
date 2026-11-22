package com.memo.minutes

import com.memo.forum.MinutesApproval
import com.memo.memo.internal.rendition.PdfaValidator
import com.memo.memo.internal.rendition.RecordFixity
import com.memo.minutes.internal.ApprovedCopyPurpose
import com.memo.minutes.internal.CopyBlocks
import com.memo.minutes.internal.CopyStage
import com.memo.minutes.internal.CopyState
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.MinutesCopyStore
import com.memo.minutes.internal.MinutesRecordPurpose
import com.memo.minutes.internal.MinutesRecordStore
import com.memo.minutes.internal.MinutesRecords
import com.memo.minutes.internal.RecordState
import com.memo.minutes.internal.VoteMode
import com.memo.testsupport.Audit
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The record (test plan E06 T7 `MinutesRecordIT`; ADR-0066, ADR-0053): once the chair confirms, the `minutes-record` job
 * keeps the approved minutes as one PDF/A-2u file (cover, the .docx converted, the resolutions annex, the approval stamp on
 * every page) that veraPDF validates, stored object-locked for the committee type's retention with its SHA-256 audited;
 * the recused director's approved copy is a separate locked record naming their item as redacted and holding none of its
 * discussion; the weekly fixity run verifies both and names a record whose hash no longer matches; the check re-hashes.
 * The jobs are run by hand; the fake document server writes the document's text into the PDF/A.
 */
class MinutesRecordIT : MinutesFixture() {
    @Autowired lateinit var records: MinutesRecords

    @Autowired lateinit var recordStore: MinutesRecordStore

    @Autowired lateinit var copyStore: MinutesCopyStore

    @Autowired lateinit var fixity: RecordFixity

    @Autowired lateinit var validator: PdfaValidator

    @Test
    fun `the approved minutes are a validated, locked PDF-A record, the recused director's copy another, both verified`() {
        val m = approvedMinutes()
        // Before the record is made: 409 RECORD_PENDING for every reader.
        listOf("document", "record").forEach { path ->
            val pending = read("/api/v1/meetings/$m/minutes/$path", colleague.session)
            assertThat(pending).hasStatus(HttpStatus.CONFLICT)
            assertThat(pending.response.contentAsString).contains("RECORD_PENDING")
        }
        val requested = transactions.readInTenant(tenant.id) { recordStore.of(m) }.single()
        assertThat(requested.recordVersion to requested.state).isEqualTo(1 to RecordState.PENDING)
        Audit.assertAudited(tenant.id, "minutes.record.requested", m.toString(), chair.id.toString())

        val record = assertRecord(m)
        assertDirectorsCopy(m)

        // The versions as each reads them; someone outside reads nothing.
        val mine = read("/api/v1/meetings/$m/minutes/records", director.session).json()[0]
        assertThat(mine["file"]["full"].asBoolean()).isFalse()
        assertThat(mine["redacted"][0]["kind"].asString()).isEqualTo("RECUSED")
        val ours = read("/api/v1/meetings/$m/minutes/records", colleague.session).json()[0]
        assertThat(ours["file"]["sha256"].asString() to ours["file"]["full"].asBoolean()).isEqualTo(record.sha256 to true)
        listOf(outsider, guest).forEach { p ->
            assertThat(read("/api/v1/meetings/$m/minutes/record", p.session)).hasStatus(HttpStatus.NOT_FOUND)
            assertThat(read("/api/v1/meetings/$m/minutes/records", p.session)).hasStatus(HttpStatus.NOT_FOUND)
        }

        // The check re-hashes the approved document and the reader's file.
        val check = post("/api/v1/meetings/$m/minutes/records/1/check", colleague.session)
        assertThat(check).hasStatusOk()
        assertThat(check.json()["matches"].asBoolean()).describedAs(check.response.contentAsString).isTrue()
        Audit.assertAudited(tenant.id, "minutes.record.checked", m.toString(), colleague.id.toString())

        assertFixity(m, record)
    }

    /** The record made, stored and audited, then read in full by a reader who may read every item. */
    private fun assertRecord(m: UUID): com.memo.minutes.internal.RecordRow {
        asSystem { records.build(m, 1, last = true) }
        val record = checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 1) })
        assertThat(record.state to record.compliant).isEqualTo(RecordState.READY to true)
        assertThat(record.validation?.get("textMissing") as List<*>).isEmpty()
        val stored = checkNotNull(transactions.readInTenant(tenant.id) { files.version(checkNotNull(record.fileVersionId)) })
        assertThat(
            checkNotNull(transactions.readInTenant(tenant.id) { files.info(stored.fileId) }).purpose,
        ).isEqualTo(MinutesRecordPurpose.KEY)
        assertThat(stored.sha256).isEqualTo(record.sha256)
        // Object-locked for the committee type's ten years.
        assertThat(stored.retainUntil).isAfter(Instant.now().plus(YEARS_LOCKED * DAYS_A_YEAR, ChronoUnit.DAYS))
        val created = Audit.assertAudited(tenant.id, "minutes.record.created", m.toString())
        assertThat(created.canonical).contains(checkNotNull(record.sha256), "\"compliant\":true")

        // The record, for a reader who may read every item: cover, the minutes with their discussion, the annex, the stamp.
        val full = pdfOf(m, "record", colleague)
        assertThat(validator.validate(full.second).compliant).isTrue()
        assertThat(full.first).contains(
            "Approved minutes",
            "Board Risk Committee",
            CANARY_NOTES,
            CANARY_DISSENT,
            "Resolutions",
            "Approved minutes of",
            "confirmed by",
        )
        assertThat(full.first).doesNotContain("This copy", CopyBlocks.RECUSED)
        Audit.assertAudited(tenant.id, "minutes.copy.opened", m.toString(), colleague.id.toString())
        // The document endpoint reads the record now too.
        assertThat(read("/api/v1/meetings/$m/minutes/document", colleague.session)).hasStatusOk()
        return record
    }

    /** The recused director's approved copy: made on demand, a locked record of its own, none of their item's discussion. */
    private fun assertDirectorsCopy(m: UUID) {
        // The recused director: their copy is made on the record's heels (asked for by the record job) or on demand.
        assertThat(read("/api/v1/meetings/$m/minutes/record", director.session)).hasStatus(HttpStatus.ACCEPTED)
        buildCopies(m)
        val copy = transactions.readInTenant(tenant.id) { copyStore.ofRecord(m, 1) }.single()
        assertThat(copy.stage to copy.state).isEqualTo(CopyStage.APPROVED to CopyState.DONE)
        assertThat(copy.compliant).isTrue()
        assertThat(copy.redacted.map { it.itemId }).containsExactly(loan)
        val copyFile = checkNotNull(transactions.readInTenant(tenant.id) { files.version(checkNotNull(copy.fileVersionId)) })
        assertThat(
            checkNotNull(transactions.readInTenant(tenant.id) { files.info(copyFile.fileId) }).purpose,
        ).isEqualTo(ApprovedCopyPurpose.KEY)
        assertThat(copyFile.retainUntil).isNotNull()
        val theirs = pdfOf(m, "record", director)
        assertThat(validator.validate(theirs.second).compliant).isTrue()
        assertThat(theirs.first).doesNotContain(CANARY_NOTES, CANARY_DISSENT)
        val number =
            transactions
                .readInTenant(tenant.id) { meetingItems.agenda(m) }
                .items
                .single { it.id == loan }
                .number
        assertThat(
            theirs.first,
        ).contains("This copy", "Item $number", CopyBlocks.RECUSED, "Security perfected before disbursement.", RENEWAL_NOTES)
        assertThat(pdfOf(m, "document", director).first).doesNotContain(CANARY_NOTES).contains(CopyBlocks.RECUSED)
    }

    /** The weekly fixity run verifies the record and the copy, then names a record whose hash no longer matches. */
    private fun assertFixity(
        m: UUID,
        record: com.memo.minutes.internal.RecordRow,
    ) {
        val clean = asSystem { fixity.verify() }
        assertThat(clean.sources.getValue("minutes.record.verified").let { it.checked to it.mismatched }).isEqualTo(1 to 0)
        assertThat(clean.sources.getValue("minutes.copy.verified").let { it.checked to it.mismatched }).isEqualTo(1 to 0)
        Audit.assertAudited(tenant.id, "minutes.record.verified", null)
        tamper(record.id)
        val tampered = asSystem { fixity.verify() }
        assertThat(tampered.sources.getValue("minutes.record.verified").mismatched).isEqualTo(1)
        assertThat(tampered.mismatched).describedAs("the memos' records, apart").isZero()
        Audit.assertAudited(tenant.id, "minutes.record.mismatch", m.toString())
        val after = post("/api/v1/meetings/$m/minutes/records/1/check", colleague.session).json()
        assertThat(after["problems"].toString()).contains("FILE_CHANGED")
    }

    @Test
    fun `a record the converter cannot make is tried again, then failed with its code`() {
        val m = approvedMinutes()
        converterDown = true
        try {
            assertThat(runCatching { asSystem { records.build(m, 1, last = false) } }.isFailure).isTrue()
            assertThat(checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 1) }).state).isEqualTo(RecordState.PENDING)
            asSystem { records.build(m, 1, last = true) }
        } finally {
            converterDown = false
        }
        val failed = checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 1) })
        assertThat(failed.state to failed.error).isEqualTo(RecordState.FAILED to "CONVERTER_UNREACHABLE")
        Audit.assertAudited(tenant.id, "minutes.record.failed", m.toString())
    }

    /** BRC minutes with the recused director's item carrying canaries, approved by circulation and confirmed by the chair. */
    private fun approvedMinutes(): UUID {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val r =
            drafted(
                "Term loan, Delta Textiles",
                "Facility renewal, Acme",
                before = { declare(director, it.meeting, it.items.first().item) },
                decisions = ::decideWithCanaries,
            )
        loan = r.items.first().item
        val m = r.meeting.id
        circulated(m)
        assertThat(step(m, "end-circulation", secretary, """{"reason":"Needed for the drawdown"}""")).hasStatusOk()
        assertThat(step(m, "confirm", chair)).hasStatusOk()
        return m
    }

    private var loan: UUID = UUID.randomUUID()

    /** While true, the fake document server is "unreachable" for the record's document (see [FakeRenditionServices]). */
    private var converterDown: Boolean
        get() = com.memo.testsupport.FakeRenditionServices.minutesRecordDown
        set(value) {
            com.memo.testsupport.FakeRenditionServices.minutesRecordDown = value
        }

    /** The approved loan with a dissent and the renewal, with notes; the loan's notes and dissent reason are canaries. */
    private fun decideWithCanaries(run: Running) {
        val (loanItem, renewal) = run.items
        decide(
            run,
            loanItem.item,
            APPROVED_WITH_CONDITIONS.copy(
                voteMode = VoteMode.VOTE,
                votesFor = 2,
                votesAgainst = 1,
                votesAbstain = 0,
                dissent = listOf(Dissent(colleague.id, CANARY_DISSENT)),
            ),
        )
        decide(run, renewal.item, APPROVED)
        notesOn(run, loanItem.item, CANARY_NOTES)
        notesOn(run, renewal.item, RENEWAL_NOTES)
    }

    /** [person]'s file at `…/minutes/[path]` (200): its text layer (whitespace collapsed) and the file. */
    private fun pdfOf(
        meetingId: UUID,
        path: String,
        person: Person,
    ): Pair<String, java.nio.file.Path> {
        val answer = read("/api/v1/meetings/$meetingId/minutes/$path", person.session)
        assertThat(answer).hasStatusOk()
        assertThat(answer.response.contentType).isEqualTo("application/pdf")
        val bytes = answer.response.contentAsByteArray
        val file = Files.createTempFile("minutes-record-", ".pdf").also { Files.write(it, bytes) }
        file.toFile().deleteOnExit()
        return textOf(bytes).replace(WHITESPACE, " ") to file
    }

    /** The record's recorded hash no longer that of its stored file (as if its object had been altered). */
    private fun tamper(recordId: UUID) {
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.createStatement().use { it.execute("alter table minutes_record disable trigger minutes_record_guard") }
            c.prepareStatement("update minutes_record set sha256 = repeat('0', 64) where id = ?").use {
                it.setObject(1, recordId)
                it.executeUpdate()
            }
            c.createStatement().use { it.execute("alter table minutes_record enable trigger minutes_record_guard") }
            c.commit()
        }
    }

    private companion object {
        const val CANARY_NOTES = "CANARY-NT members challenged the borrower's sector outlook at length"
        const val CANARY_DISSENT = "CANARY-DS the sector outlook is too weak for this exposure"
        const val RENEWAL_NOTES = "The renewal keeps the existing covenants unchanged"
        const val YEARS_LOCKED = 9L
        const val DAYS_A_YEAR = 365L
        val WHITESPACE = Regex("""\s+""")
    }
}
