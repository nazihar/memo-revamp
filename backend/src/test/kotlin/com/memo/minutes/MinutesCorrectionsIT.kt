package com.memo.minutes

import com.memo.forum.MinutesApproval
import com.memo.memo.internal.rendition.PdfaValidator
import com.memo.minutes.internal.CopyBlocks
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.MinutesApprovalStore
import com.memo.minutes.internal.MinutesEventKind
import com.memo.minutes.internal.MinutesRecordStore
import com.memo.minutes.internal.MinutesRecords
import com.memo.minutes.internal.RecordState
import com.memo.minutes.internal.VoteMode
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.ageSessionFactors
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Corrections after approval (test plan E06 T8 `MinutesCorrectionsIT`; ADR-0066, design E06 §1.6.5, CGI §6.2): approved
 * minutes are never edited. A secretary proposes a correction (refused before approval, to members and the chair, and to
 * someone outside); the chair approves it with a fresh step-up (never the vice-chair while the chair is able, never on a
 * stale step-up), which is an authenticated approval chained into the committee's chain and record version 2: the approved
 * document's pages unchanged, a "Corrections" annex; version 1 stays readable throughout, and the recused director's copy
 * of version 2 does not show the correction on their item. A declined correction is decided once. E07's
 * `MinutesCorrections.since` lists the approved one. The jobs are run by hand.
 */
class MinutesCorrectionsIT : MinutesFixture() {
    @Autowired lateinit var records: MinutesRecords

    @Autowired lateinit var recordStore: MinutesRecordStore

    @Autowired lateinit var approvalStore: MinutesApprovalStore

    @Autowired lateinit var validator: PdfaValidator

    @Autowired lateinit var correctionsApi: MinutesCorrections

    @Test
    fun `a correction approved by the chair with a step-up is record version 2 with its annex, and version 1 stays readable`() {
        val started = Instant.now()
        val m = approvedMinutes()
        asSystem { records.build(m, 1, last = true) }
        val v1 = pdf(m, 1, colleague)

        val proposed = proposeAndRefuse(m)
        val id = UUID.fromString(proposed["id"].asString())
        Audit.assertAudited(tenant.id, "minutes.correction.proposed", m.toString(), secretary.id.toString())
        assertThat(latest(chair)["kind"].asString()).isEqualTo("MINUTES_CORRECTION_PROPOSED")

        // Who decides: the chair only (the vice-chair 403, someone outside 404), with a fresh step-up.
        assertThat(decide(m, id, vice, "APPROVE")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(decide(m, id, outsider, "APPROVE")).hasStatus(HttpStatus.NOT_FOUND)
        ageSessionFactors(chair.session)
        assertThat(decide(m, id, chair, "APPROVE")).hasStatus(HttpStatus.UNAUTHORIZED)
        ageSessionFactors(chair.session, issuedAt = Instant.now())
        val approved = decide(m, id, chair, "APPROVE")
        assertThat(approved).hasStatusOk()
        assertThat(approved.json()["state"].asString() to approved.json()["recordVersion"].asInt()).isEqualTo("APPROVED" to 2)
        Audit.assertAudited(tenant.id, "minutes.correction.approved", m.toString(), chair.id.toString())
        assertThat(events(m).map { it.kind }).contains(MinutesEventKind.CORRECTION_APPROVED)
        assertThat(decide(m, id, chair, "DECLINE").json()["reason"].asString()).isEqualTo("DECIDED")
        assertChain(m)

        // While version 2 is made, version 1 is what everyone reads.
        assertThat(transactions.readInTenant(tenant.id) { recordStore.find(m, 2) }?.state).isEqualTo(RecordState.PENDING)
        assertThat(read("/api/v1/meetings/$m/minutes/record", colleague.session)).hasStatusOk()

        asSystem { records.build(m, 2, last = true) }
        val v2record = checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 2) })
        assertThat(v2record.state to v2record.compliant).isEqualTo(RecordState.READY to true)
        Audit.assertAudited(tenant.id, "minutes.record.superseded", m.toString())
        assertVersions(m, v1)
        assertDirectorsCopy(m)
        assertDeclined(m)

        // E07's report: the approved correction, once.
        val since = transactions.readInTenant(tenant.id) { correctionsApi.since(brc, started) }
        assertThat(since.map { it.id to it.recordVersion }).containsExactly(id to 2)
        assertThat(since.single().itemId).isEqualTo(loan)
        assertThat(transactions.readInTenant(tenant.id) { correctionsApi.since(brc, Instant.now().plusSeconds(60)) }).isEmpty()
        assertThat(read("/api/v1/meetings/$m/minutes", secretary.session).json()["correctedAt"].isNull).isFalse()
    }

    /** Refusals before anything is written, then the secretary's proposal on the loan (201, PROPOSED). */
    private fun proposeAndRefuse(m: UUID): JsonNode {
        val body = """{"itemId":"$loan","text":"$CORRECTION","reason":"The amount was misstated"}"""
        assertThat(propose(m, colleague, body)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(propose(m, chair, body)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(propose(m, outsider, body)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(propose(m, secretary, """{"itemId":"$loan","text":"x","reason":"  "}""")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(propose(m, secretary, """{"itemId":"${UUID.randomUUID()}","text":"x","reason":"y"}""")).hasStatus(HttpStatus.NOT_FOUND)
        val proposed = propose(m, secretary, body)
        assertThat(proposed).hasStatus(HttpStatus.CREATED)
        assertThat(proposed.json()["state"].asString()).isEqualTo("PROPOSED")
        return proposed.json()
    }

    /** The correction's approval follows the confirmation in the committee's chain, and its check matches. */
    private fun assertChain(m: UUID) {
        val chain = transactions.readInTenant(tenant.id) { approvalStore.ofMeeting(m) }
        assertThat(chain.map { it.kind }).containsExactly("CONFIRMATION", "CORRECTION")
        assertThat(chain[1].previousHash).isEqualTo(chain[0].recordHash)
        assertThat(chain[1].docxSha256).isEqualTo(chain[0].docxSha256)
        assertThat(chain[1].authentication["stepUpRequired"].asBoolean()).isTrue()
        val check = post("/api/v1/meetings/$m/minutes/approvals/${chain[1].id}/check", colleague.session)
        assertThat(check.json()["matches"].asBoolean()).describedAs(check.response.contentAsString).isTrue()
    }

    /** Version 2: the minutes' pages as in version 1, then the corrections annex; version 1 still served when asked for. */
    private fun assertVersions(
        m: UUID,
        v1: Pdf,
    ) {
        val v2 = pdf(m, null, colleague)
        assertThat(validator.validate(v2.file).compliant).isTrue()
        assertThat(v2.byLabel("M-")).describedAs("the approved document's pages").isEqualTo(v1.byLabel("M-"))
        assertThat(v2.byLabel("K-").joinToString(" ")).contains("Corrections", "Correction 1", CORRECTION, "Reason: The amount")
        assertThat(v1.text).doesNotContain(CORRECTION)
        assertThat(pdf(m, 1, colleague).sha256).isEqualTo(v1.sha256)
        val versions = read("/api/v1/meetings/$m/minutes/records", colleague.session).json()
        assertThat((0 until versions.size()).map { versions[it]["version"].asInt() to versions[it]["current"].asBoolean() })
            .containsExactly(2 to true, 1 to false)
        val check = post("/api/v1/meetings/$m/minutes/records/2/check", colleague.session).json()
        assertThat(check["matches"].asBoolean()).describedAs(check.toString()).isTrue()
    }

    /** The recused director's copy of version 2: the correction on their item is named, not shown. */
    private fun assertDirectorsCopy(m: UUID) {
        assertThat(read("/api/v1/meetings/$m/minutes/record", director.session).response.status).isIn(200, 202)
        buildCopies(m)
        val theirs = pdf(m, 2, director)
        assertThat(theirs.text).contains("This copy", CopyBlocks.RECUSED, "A correction to item")
        assertThat(theirs.text).doesNotContain(CORRECTION, CANARY_NOTES, CANARY_DISSENT)
        val listed = read("/api/v1/meetings/$m/minutes/corrections", director.session).json()["corrections"]
        assertThat(listed.size()).isOne()
        assertThat(listed[0]["withheld"].asBoolean() to listed[0].has("text")).isEqualTo(true to false)
    }

    /** A general correction declined by the chair: decided once; members never see it, the secretaries do. */
    private fun assertDeclined(m: UUID) {
        val general = propose(m, secretary, """{"text":"The meeting closed at 13:10.","reason":"Time"}""").json()
        val gid = UUID.fromString(general["id"].asString())
        val declined = decide(m, gid, chair, "DECLINE", ",\"note\":\"The time is right\"")
        assertThat(declined.json()["state"].asString()).isEqualTo("DECLINED")
        Audit.assertAudited(tenant.id, "minutes.correction.declined", m.toString(), chair.id.toString())
        assertThat(decide(m, gid, chair, "APPROVE")).hasStatus(HttpStatus.CONFLICT)
        val ofMember = read("/api/v1/meetings/$m/minutes/corrections", colleague.session).json()
        assertThat(ofMember["corrections"].size()).isOne()
        assertThat(ofMember["corrections"][0]["text"].asString()).isEqualTo(CORRECTION)
        assertThat(ofMember["mayPropose"].asBoolean() to ofMember["mayDecide"].asBoolean()).isEqualTo(false to false)
        val ofSecretary = read("/api/v1/meetings/$m/minutes/corrections", secretary.session).json()
        assertThat(ofSecretary["corrections"].size()).isEqualTo(2)
        assertThat(read("/api/v1/meetings/$m/minutes/corrections", outsider.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/meetings/$m/minutes/corrections", chair.session).json()["mayDecide"].asBoolean()).isTrue()
    }

    @Test
    fun `a correction is proposed only once the minutes are approved`() {
        val r = drafted("Facility renewal, Acme")
        val answer = propose(r.meeting.id, secretary, """{"text":"x","reason":"y"}""")
        assertThat(answer).hasStatus(HttpStatus.CONFLICT)
        assertThat(answer.json()["reason"].asString()).isEqualTo("WRONG_STATE")
    }

    private fun propose(
        m: UUID,
        by: Person,
        body: String,
    ) = post("/api/v1/meetings/$m/minutes/corrections", by.session, body)

    private fun decide(
        m: UUID,
        id: UUID,
        by: Person,
        decision: String,
        extra: String = "",
    ) = post("/api/v1/meetings/$m/minutes/corrections/$id/decide", by.session, """{"decision":"$decision"$extra}""")

    private fun latest(person: Person): JsonNode = read("/api/v1/me/notifications", person.session).json()["items"].first()

    /** A record (or copy) as [person] reads it: its text per page, by page label, and its file and hash. */
    private inner class Pdf(
        val text: String,
        val pages: List<Pair<String, String>>,
        val file: java.nio.file.Path,
        val sha256: String,
    ) {
        /** The text of the pages whose label starts with [prefix], one entry per page. */
        fun byLabel(prefix: String): List<String> = pages.filter { it.first.startsWith(prefix) }.map { it.second }
    }

    private fun pdf(
        m: UUID,
        version: Int?,
        person: Person,
    ): Pdf {
        val answer = read("/api/v1/meetings/$m/minutes/record" + (version?.let { "?version=$it" } ?: ""), person.session)
        assertThat(answer).hasStatusOk()
        val bytes = answer.response.contentAsByteArray
        val file =
            java.nio.file.Files
                .createTempFile("minutes-correction-", ".pdf")
                .also {
                    java.nio.file.Files
                        .write(it, bytes)
                }
        file.toFile().deleteOnExit()
        val pages =
            Loader.loadPDF(bytes).use { doc ->
                val labels = doc.documentCatalog.pageLabels?.labelsByPageIndices ?: arrayOfNulls(doc.numberOfPages)
                (0 until doc.numberOfPages).map { i ->
                    val stripper =
                        PDFTextStripper().apply {
                            startPage = i + 1
                            endPage = i + 1
                        }
                    (labels[i] ?: "") to stripper.getText(doc).replace(WHITESPACE, " ").trim()
                }
            }
        val sha =
            java.util.HexFormat
                .of()
                .formatHex(
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(bytes),
                )
        return Pdf(textOf(bytes).replace(WHITESPACE, " "), pages, file, sha)
    }

    /** BRC minutes with the recused director's item carrying canaries, approved by circulation and confirmed by the chair. */
    private fun approvedMinutes(): UUID {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val r =
            drafted(
                "Term loan, Delta Textiles",
                "Facility renewal, Acme",
                before = { declare(director, it.meeting, it.items.first().item) },
                decisions = { run ->
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
                },
            )
        loan = r.items.first().item
        val m = r.meeting.id
        circulated(m)
        assertThat(step(m, "end-circulation", secretary, """{"reason":"Needed for the drawdown"}""")).hasStatusOk()
        assertThat(step(m, "confirm", chair)).hasStatusOk()
        return m
    }

    private var loan: UUID = UUID.randomUUID()

    private companion object {
        const val CORRECTION = "CANARY-CR the facility is BDT 60 million, not BDT 6 million"
        const val CANARY_NOTES = "CANARY-NT members challenged the borrower's sector outlook at length"
        const val CANARY_DISSENT = "CANARY-DS the sector outlook is too weak for this exposure"
        val WHITESPACE = Regex("""\s+""")
    }
}
