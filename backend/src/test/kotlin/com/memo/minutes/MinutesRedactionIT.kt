package com.memo.minutes

import com.memo.minutes.internal.CopyBlocks
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.DraftStamp
import com.memo.minutes.internal.MinutesCopyStore
import com.memo.minutes.internal.VoteMode
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus

/**
 * The readers' copies (test plan E06 T6 `MinutesRedactionIT`; ADR-0066, ADR-0062): canaries in the recused item's notes and
 * dissent reason are in the full copy's PDF text layer and in nobody else's; `director@`'s copy shows item 5's heading,
 * their declaration and the decision; someone asked about one item reads that item only; the guest and someone outside
 * read nothing; a secretary previews a reader's copy. The copies job is run by hand; the fake document server's
 * conversion writes the document's text into the PDF, as ONLYOFFICE does (`MinutesCopiesOnlyOfficeIT` on the real one).
 */
class MinutesRedactionIT : MinutesFixture() {
    @Autowired lateinit var copyStore: MinutesCopyStore

    @Test
    fun `each reader's copy holds what they may read, and the recused director's none of the item's discussion`() {
        val r =
            drafted(
                "Term loan, Delta Textiles",
                "Facility renewal, Acme",
                before = { declare(director, it.meeting, it.items.first().item) },
                decisions = ::decideWithCanaries,
            )
        val m = r.meeting.id
        // The draft holds everything (the record will).
        assertThat(
            com.memo.testsupport.Docx
                .text(current(m)),
        ).contains(CANARY_NOTES, CANARY_DISSENT)
        circulated(m)
        // Before the copies are built, a reader is asked to come back.
        val early = copyOf(m, director)
        assertThat(early).hasStatus(HttpStatus.ACCEPTED)
        assertThat(early.response.getHeader("Retry-After")).isNotBlank()
        buildCopies(m)
        val built =
            transactions.readInTenant(
                tenant.id,
            ) { copyStore.ofVersion(m, com.memo.minutes.internal.CopyStage.DRAFT, sourceVersion(m)) }
        assertThat(built.map { it.state.name }.distinct()).containsExactly("DONE")
        assertThat(built.map { it.redacted.size }.sorted()).describedAs("the full copy and the recused director's").containsExactly(0, 1)
        Audit.assertAudited(tenant.id, "minutes.copy.built", m.toString())

        val full = pdf(m, colleague)
        assertThat(full).contains(CANARY_NOTES, CANARY_DISSENT, RENEWAL_NOTES, DraftStamp.WORD)
        val recused = pdf(m, director)
        assertThat(recused).doesNotContain(CANARY_NOTES, CANARY_DISSENT)
        assertThat(recused).contains(
            "Term loan, Delta Textiles",
            CopyBlocks.RECUSED,
            "APPROVED WITH CONDITIONS",
            "Security perfected before disbursement.",
            RENEWAL_NOTES,
            DraftStamp.WORD,
        )
        val opened = Audit.assertAudited(tenant.id, "minutes.copy.opened", m.toString(), director.id.toString())
        assertThat(opened.canonical).contains("setKey")
        // The chair and the secretaries read the full copy.
        assertThat(pdf(m, chair)).contains(CANARY_NOTES)
        // The guest and someone outside: nothing.
        listOf(guest, outsider, reader).forEach { assertThat(copyOf(m, it)).hasStatus(HttpStatus.NOT_FOUND) }

        askedAndPreviewed(m, r)
        // "See the copy for…": the secretaries' page lists the copies and who reads each.
        val copies = read("/api/v1/meetings/$m/minutes", secretary.session).json()["copies"]
        val theirs = (0 until copies.size()).map { copies[it] }.single { c -> c["readers"].toString().contains(director.id.toString()) }
        assertThat(theirs["redacted"][0]["itemId"].asString() to theirs["redacted"][0]["kind"].asString())
            .isEqualTo(
                r.items
                    .first()
                    .item
                    .toString() to "RECUSED",
            )
    }

    @Test
    fun `a copy is refused when a redacted item's recorded text appears outside its block`() {
        val r =
            drafted(
                "Term loan, Delta Textiles",
                before = { declare(director, it.meeting, it.items.single().item) },
                decisions = { run ->
                    decide(run, run.items.single().item, APPROVED)
                    notesOn(run, run.items.single().item, CANARY_NOTES)
                },
            )
        val m = r.meeting.id
        // The secretary pastes the discussion outside the item's block (between blocks).
        save(
            m,
            com.memo.testsupport.Docx
                .editText(current(m), "word/document.xml") { it.replace("Business of the meeting", CANARY_NOTES) },
        )
        circulated(m)
        buildCopies(m)
        val copies =
            transactions.readInTenant(
                tenant.id,
            ) { copyStore.ofVersion(m, com.memo.minutes.internal.CopyStage.DRAFT, sourceVersion(m)) }
        assertThat(copies.single { it.redacted.isNotEmpty() }.let { it.state.name to it.error }).isEqualTo("FAILED" to "LEAK")
        assertThat(copyOf(m, director)).hasStatus(HttpStatus.ACCEPTED)
        Audit.assertAudited(tenant.id, "minutes.copy.failed", m.toString())
    }

    @Test
    fun `past 50 different copies none is built, and the refusal is audited`() {
        val r = drafted("Term loan, Delta Textiles")
        val m = r.meeting.id
        circulated(m)
        val minuted =
            transactions
                .readInTenant(tenant.id) { meetingItems.agenda(m) }
                .items
                .filter { it.number != null }
                .map { it.id }
        // 51 people asked about 51 different sets of items: 51 copies besides the members'.
        (1..PAST_THE_LIMIT).forEach { mask ->
            val items = minuted.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val asked = person()
            val body = """{"userIds":["${asked.id}"],"itemIds":[${items.joinToString(",") { "\"$it\"" }}]}"""
            assertThat(post("/api/v1/meetings/$m/minutes/comment-requests", secretary.session, body)).hasStatusOk()
        }
        buildCopies(m)
        val copies =
            transactions.readInTenant(
                tenant.id,
            ) { copyStore.ofVersion(m, com.memo.minutes.internal.CopyStage.DRAFT, sourceVersion(m)) }
        assertThat(copies).isEmpty()
        Audit.assertAudited(tenant.id, "minutes.copies.refused", m.toString())
    }

    private fun sourceVersion(meetingId: java.util.UUID) =
        checkNotNull(transactions.readInTenant(tenant.id) { files.info(checkNotNull(row(meetingId).fileId))?.currentVersionId })

    /** [person]'s copy's text layer (200). */
    private fun pdf(
        meetingId: java.util.UUID,
        person: Person,
    ): String {
        val copy = copyOf(meetingId, person)
        assertThat(copy).hasStatusOk()
        assertThat(copy.response.contentType).isEqualTo("application/pdf")
        // The renderer wraps long lines: compare with the whitespace collapsed.
        return textOf(copy.response.contentAsByteArray).replace(WHITESPACE, " ")
    }

    /** The loan approved with conditions and a dissent, the renewal approved, both with notes; the loan's carry canaries. */
    private fun decideWithCanaries(run: Running) {
        val (loan, renewal) = run.items
        decide(
            run,
            loan.item,
            APPROVED_WITH_CONDITIONS.copy(
                voteMode = VoteMode.VOTE,
                votesFor = 2,
                votesAgainst = 1,
                votesAbstain = 0,
                dissent = listOf(Dissent(colleague.id, CANARY_DISSENT)),
            ),
        )
        decide(run, renewal.item, APPROVED)
        notesOn(run, loan.item, CANARY_NOTES)
        notesOn(run, renewal.item, RENEWAL_NOTES)
    }

    private companion object {
        const val CANARY_NOTES = "CANARY-NT members challenged the borrower's sector outlook at length"
        const val CANARY_DISSENT = "CANARY-DS the sector outlook is too weak for this exposure"
        const val RENEWAL_NOTES = "The renewal keeps the existing covenants unchanged"
        const val PAST_THE_LIMIT = 51
        val WHITESPACE = Regex("""\s+""")
    }

    /** Someone asked about the renewal only reads it alone; a secretary previews the director's copy. */
    private fun askedAndPreviewed(
        m: java.util.UUID,
        r: Running,
    ) {
        // Someone asked about the renewal only: every other item hidden, title and all.
        val renewal = r.items[1]
        val ask = """{"userIds":["${outsider.id}"],"itemIds":["${renewal.item}"]}"""
        assertThat(post("/api/v1/meetings/$m/minutes/comment-requests", secretary.session, ask)).hasStatusOk()
        assertThat(copyOf(m, outsider)).hasStatus(HttpStatus.ACCEPTED)
        buildCopies(m)
        val asked = pdf(m, outsider)
        assertThat(asked).contains(RENEWAL_NOTES, "restricted").doesNotContain(CANARY_NOTES, CANARY_DISSENT, "Term loan, Delta Textiles")

        // A secretary previews the director's copy; a member may not preview.
        val preview = read("/api/v1/meetings/$m/minutes/preview?as=${director.id}", secretary.session)
        assertThat(preview).hasStatusOk()
        assertThat(textOf(preview.response.contentAsByteArray)).doesNotContain(CANARY_NOTES).contains(CopyBlocks.RECUSED)
        assertThat(read("/api/v1/meetings/$m/minutes/preview?as=${colleague.id}", director.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/meetings/$m/minutes/preview?as=${guest.id}", secretary.session))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }
}
