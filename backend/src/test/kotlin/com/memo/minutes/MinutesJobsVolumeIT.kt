package com.memo.minutes

import com.memo.docedit.internal.DocEditProperties
import com.memo.docedit.internal.OnlyOfficeConverter
import com.memo.files.FileType
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.forum.MinutesApproval
import com.memo.meeting.AttendanceMode
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.LateDecision
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.TimesInput
import com.memo.minutes.internal.DraftTask
import com.memo.minutes.internal.MinutesRecordStore
import com.memo.minutes.internal.MinutesRecords
import com.memo.minutes.internal.RecordState
import com.memo.minutes.internal.ReleaseTrigger
import com.memo.testsupport.SqlRecorder
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * E06's jobs at the sizes of design E06 §8 (test plan T9, weekly `load` with `MEETING_VOLUME`, skipped without it): a
 * meeting of 60 escalated memos, one decision each (a director recused from one), each item with 10,000 characters of
 * notes so the minutes run to about 200 pages; then, each timed once as db-scheduler runs it:
 *  - `minutes-release` at the meeting's end: 60 numbers in agenda order and 60 memos resumed (< 5 s);
 *  - `minutes-draft`: the whole draft from the template (< 20 s);
 *  - `minutes-copies` once circulated: one copy per reader's set (< 10 s a copy);
 *  - `minutes-record` once confirmed: the PDF/A record with its cover, the annex and veraPDF (< 90 s), then the approved
 *    copies.
 * The application's document converter in tests is the fake document server ([com.memo.testsupport.FakeRenditionServices]),
 * so the jobs' times leave out ONLYOFFICE's conversion; with `MEMO_SPIKE_DOCSERVER` set (a local ONLYOFFICE 10.0.0.1), the
 * approved .docx is also converted by it to PDF (a copy's conversion) and PDF/A (the record's), and those times printed and
 * held to the same budgets. Budgets have 2x headroom for shared CI runners.
 */
@EnabledIfEnvironmentVariable(named = "MEETING_VOLUME", matches = "[1-9][0-9]*")
class MinutesJobsVolumeIT : MinutesFixture() {
    @Autowired lateinit var records: MinutesRecords

    @Autowired lateinit var recordStore: MinutesRecordStore

    @Autowired lateinit var sql: SqlRecorder

    @Test
    fun `the release at 60 decisions, the draft, the copies and the record run within their budgets`() {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val r = runningMany((1..DECISIONS).map { "Credit proposal $it" })
        val m = r.meeting.id
        declare(director, r.meeting, r.items.first().item)
        r.items.forEachIndexed { i, placed ->
            val conditions = i % CONDITIONS_EVERY == 0
            decide(r, placed.item, if (conditions) APPROVED_WITH_CONDITIONS else APPROVED)
        }
        r.items.forEachIndexed { i, placed -> notesOn(r, placed.item, notes(i + 1)) }
        endMeeting(r.meeting)

        var pass: com.memo.minutes.internal.ReleasePass? = null
        var statements = 0
        val release = timed { statements = sql.record { pass = job(m, ReleaseTrigger.HELD) }.second.size }
        println("MinutesJobsVolumeIT minutes-release ran $statements statements (${statements / DECISIONS} a decision)")
        assertThat(checkNotNull(pass).numbered).hasSize(DECISIONS)
        assertThat(checkNotNull(pass).released).hasSize(DECISIONS)
        assertThat(checkNotNull(pass).failed).isEmpty()
        report("minutes-release (60 decisions: numbered, memos resumed)", release, RELEASE_BUDGET_MS)

        asUser(secretary.account) { drafts.request(m, null, false) }
        val draft = timed { asSystem { drafting.run(DraftTask(tenant.id.value, m, row(m).workSeq)) } }
        checkNotNull(asSystem { checks.check(m) })
        assertThat(row(m).structureOk).isTrue()
        report("minutes-draft (60 items)", draft, DRAFT_BUDGET_MS)

        circulated(m)
        val copies = timed { buildCopies(m) }
        val built = doneCopies(m, "DRAFT")
        assertThat(built).isGreaterThanOrEqualTo(2)
        report("minutes-copies (draft, $built sets; per copy)", copies / built, COPY_BUDGET_MS)

        assertThat(step(m, "end-circulation", secretary, """{"reason":"Needed for the drawdowns"}""")).hasStatusOk()
        assertThat(step(m, "confirm", chair)).hasStatusOk()
        val record = timed { asSystem { records.build(m, 1, last = true) } }
        val made = checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 1) })
        assertThat(made.state).isEqualTo(RecordState.READY)
        println("MinutesJobsVolumeIT the record: ${made.pages} pages, ${made.sizeBytes} bytes, compliant ${made.compliant}")
        report("minutes-record (${made.pages} pages, PDF/A with veraPDF)", record, RECORD_BUDGET_MS)
        val approvedCopies = timed { buildCopies(m) }
        val approved = doneCopies(m, "APPROVED")
        assertThat(approved).isPositive()
        report("minutes-copies (approved, $approved sets; per copy)", approvedCopies / approved, COPY_BUDGET_MS + RECORD_COPY_EXTRA_MS)

        onlyOffice(current(m))
        assertThat(missed).describedAs("budgets missed").isEmpty()
    }

    /**
     * [running] for [subjects], each memo raised by one of several initiators (an initiator uploads at most 30 documents a
     * minute, the rate limit of uploads): escalated, placed in that order, published, and the meeting started with the
     * chair, the vice-chair and both directors present.
     */
    private fun runningMany(subjects: List<String>): Running {
        val placed =
            subjects.mapIndexed { i, s ->
                if (i % MEMOS_PER_INITIATOR == 0) initiator = person()
                escalated(s).let { (memo, submission) -> Triple(s, memo, submission) }
            }
        val zone = ZoneId.of("Asia/Dhaka")
        val start = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1)
        val meeting =
            asUser(secretary.account) {
                val created =
                    lifecycle.create(
                        brc,
                        MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                        TimesInput(start, start.plusHours(3), zone.id),
                    )
                starts.start(created.id, AgendaOrigin.TEMPLATE, null)
                created
            }
        val items = placed.map { (s, memo, submission) -> Placed(s, memo, submission, place(meeting, submission)) }
        asUser(secretary.account) { lifecycle.schedule(meeting.id, reads.get(meeting.id).version) }
        items.forEach { asUser(chair.account) { chairing.decideItem(meeting.id, it.item, version(meeting), LateDecision(true)) } }
        publish(meeting)
        startMeeting(meeting)
        asUser(secretary.account) {
            attendance.arrivals(meeting.id, listOf(chair.id, vice.id, director.id, colleague.id), AttendanceMode.IN_PERSON, null)
        }
        return Running(meeting, items)
    }

    /**
     * With a local ONLYOFFICE (`MEMO_SPIKE_DOCSERVER`, `MEMO_SPIKE_JWT`): the approved .docx converted to PDF (what a copy
     * costs the document server) and to PDF/A (the record's), each timed; otherwise said so.
     */
    private fun onlyOffice(docx: ByteArray) {
        val server = System.getenv("MEMO_SPIKE_DOCSERVER").orEmpty()
        if (server.isBlank()) {
            println("MinutesJobsVolumeIT ONLYOFFICE: not set (MEMO_SPIKE_DOCSERVER); the jobs above ran on the fake converter")
            return
        }
        val converter =
            OnlyOfficeConverter(
                DocEditProperties(documentServerUrl = server, jwtSecret = System.getenv("MEMO_SPIKE_JWT")),
                json,
                Clock.systemUTC(),
            )
        listOf(false, true).forEach { pdfa ->
            val out = ByteArrayOutputStream()
            val input = RenditionInput("minutes.docx", FileType.DOCX, docx.size.toLong()) { ByteArrayInputStream(docx) }
            val ms =
                timed {
                    val key = "minutes-volume-${UUID.randomUUID()}"
                    val outcome = if (pdfa) converter.toPdfA(input, key, out) else converter.toPdf(input, key, out)
                    assertThat(outcome).isEqualTo(RenditionOutcome.Written())
                }
            val pages = Loader.loadPDF(out.toByteArray()).use { it.numberOfPages }
            val what = if (pdfa) "ONLYOFFICE to PDF/A (the record's conversion)" else "ONLYOFFICE to PDF (a copy's conversion)"
            report("$what, $pages pages", ms, if (pdfa) RECORD_BUDGET_MS else COPY_BUDGET_MS)
        }
    }

    private fun doneCopies(
        meetingId: UUID,
        stage: String,
    ): Int =
        transactions.readInTenant(tenant.id) {
            (
                tenantDsl.current().fetchValue(
                    "select count(*) from minutes_copy where meeting_id = ? and stage = ? and state = 'DONE'",
                    meetingId,
                    stage,
                ) as Number
            ).toInt()
        }

    /** Item [n]'s notes: 10,000 characters, about three pages of the minutes. */
    private fun notes(n: Int): String =
        "Item $n. "
            .plus(NOTE_LINE.repeat(NOTE_CHARS / NOTE_LINE.length))
            .take(NOTE_CHARS)

    private fun timed(call: () -> Unit): Long {
        val start = System.nanoTime()
        call()
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    private fun report(
        name: String,
        ms: Long,
        budget: Long,
    ) {
        println("MinutesJobsVolumeIT $name: $ms ms (budget $budget ms, CI limit ${budget * 2} ms)")
        if (ms >= budget * 2) missed += "$name: $ms ms, CI limit ${budget * 2} ms"
    }

    /** The budgets missed: every job is timed and printed first, then the test fails naming them all. */
    private val missed = mutableListOf<String>()

    private companion object {
        const val DECISIONS = 60
        const val MEMOS_PER_INITIATOR = 20
        const val CONDITIONS_EVERY = 5
        const val NOTE_CHARS = 10_000
        const val NOTE_LINE = "Members discussed the borrower's cash flows, the security offered and the sector outlook. "
        const val NANOS_PER_MILLI = 1_000_000L

        // Design E06 §8. An approved copy is a record of its own (cover, annex, PDF/A, veraPDF): the copy's budget plus the
        // validation's share of the record's.
        const val RELEASE_BUDGET_MS = 5_000L
        const val DRAFT_BUDGET_MS = 20_000L
        const val COPY_BUDGET_MS = 10_000L
        const val RECORD_BUDGET_MS = 90_000L
        const val RECORD_COPY_EXTRA_MS = 20_000L
    }
}
