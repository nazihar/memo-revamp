package com.memo.minutes

import com.memo.forum.MinutesApproval
import com.memo.minutes.internal.MinutesEventKind
import com.memo.minutes.internal.MinutesStage
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.workflow.WorkingDays
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * The circulation for comments (test plan E06 T6 `MinutesCirculationIT`; ADR-0066, design E06 §1.6.3, §9): the readers,
 * the deadline on the business calendar, "No comments" once and suggestions answered once, people asked for comments on
 * named items, a new deadline, the minutes taken back to the draft, the comment period ended early or by every reader's
 * answer (approval by circulation), and the sweep's steps (i)–(l), each once.
 */
class MinutesCirculationIT : MinutesFixture() {
    @Autowired lateinit var workingDays: WorkingDays

    @Test
    fun `the readers are told, comment once or suggest, and the secretary answers each suggestion once`() {
        val r = drafted("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        val m = r.meeting.id
        val row = circulated(m)
        // The deadline: the end of the fifth working day from today, on the tenant's calendar.
        val day = checkNotNull(transactions.readInTenant(tenant.id) { workingDays.after(workingDays.today(), 5) })
        assertThat(row.commentsUntil).isEqualTo(transactions.readInTenant(tenant.id) { workingDays.closeOf(day) })
        val readers = transactions.readInTenant(tenant.id) { minutesPeople.readers(row) }.keys
        assertThat(readers).containsExactlyInAnyOrder(secretary.id, chair.id, vice.id, director.id, colleague.id)
        val told = latest(director)
        assertThat(told["kind"].asString()).isEqualTo("MINUTES_CIRCULATED")
        assertThat(told["params"]["until"].asString()).isEqualTo(day.toString())
        assertThat(told.toString()).doesNotContain("Term loan")
        // Not a reader: the guest of the meeting, someone outside (404).
        listOf(guest, outsider).forEach { assertThat(read("/api/v1/meetings/$m/minutes", it.session)).hasStatus(HttpStatus.NOT_FOUND) }
        val page = read("/api/v1/meetings/$m/minutes", director.session).json()
        assertThat(page["myAction"].asString() to page["mayComment"].asBoolean()).isEqualTo("COMMENT" to true)

        // "No comments" once; a suggestion on an item, answered once.
        assertThat(comment(m, director, """{"kind":"NO_COMMENTS"}""")).hasStatus(HttpStatus.CREATED)
        assertThat(comment(m, director, """{"kind":"NO_COMMENTS"}""").response.contentAsString).contains("ALREADY_ANSWERED")
        assertThat(comment(m, colleague, """{"kind":"SUGGESTION","itemId":"${loan.item}"}""")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        val suggestion =
            comment(m, colleague, """{"kind":"SUGGESTION","itemId":"${loan.item}","text":"My question CANARY-CM is missing"}""")
        assertThat(suggestion).hasStatus(HttpStatus.CREATED)
        val id = suggestion.json()["id"].asString()
        val added = Audit.assertAudited(tenant.id, "minutes.comment.added", m.toString(), colleague.id.toString())
        assertThat(added.canonical).contains("textSha256").doesNotContain("CANARY-CM")
        assertThat(latest(secretary)["kind"].asString()).isEqualTo("MINUTES_COMMENT_ADDED")
        // Secretaries answer comments; they do not comment.
        assertThat(comment(m, secretary, """{"kind":"NO_COMMENTS"}""")).hasStatus(HttpStatus.FORBIDDEN)
        // Each sees their own; the officers all.
        assertThat(comments(m, director).map { it["kind"].asString() }).containsExactly("NO_COMMENTS")
        assertThat(comments(m, secretary)).hasSize(2)
        assertThat(comments(m, chair)).hasSize(2)
        val respond = { body: String -> post("/api/v1/meetings/$m/minutes/comments/$id/response", secretary.session, body) }
        assertThat(post("/api/v1/meetings/$m/minutes/comments/$id/response", director.session, """{"response":"ADOPTED"}"""))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(respond("""{"response":"ADOPTED","note":"Added to item 5"}""")).hasStatusOk()
        assertThat(respond("""{"response":"NOT_ADOPTED"}""").response.contentAsString).contains("ALREADY_ANSWERED")
        val mine = comments(m, colleague).single()
        assertThat(mine["response"].asString() to mine["responseNote"].asString()).isEqualTo("ADOPTED" to "Added to item 5")
        Audit.assertAudited(tenant.id, "minutes.comment.responded", m.toString(), secretary.id.toString())
        // At the next meeting the minutes are approved (AT_NEXT_MEETING): every answer does not end the circulation.
        assertThat(comment(m, chair, """{"kind":"NO_COMMENTS"}""")).hasStatus(HttpStatus.CREATED)
        assertThat(comment(m, vice, """{"kind":"NO_COMMENTS"}""")).hasStatus(HttpStatus.CREATED)
        assertThat(row(m).state).isEqualTo(MinutesStage.CIRCULATED)
        assertThat(comment(m, director, """{"kind":"SUGGESTION","itemId":"${renewal.item}","text":"A late thought on it"}"""))
            .hasStatus(HttpStatus.CREATED)
    }

    @Test
    fun `someone asked for comments reads and comments on the items named only, and the deadline moves`() {
        val r = drafted("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (loan, renewal) = r.items
        val m = r.meeting.id
        circulated(m)
        val body = """{"userIds":["${outsider.id}"],"itemIds":["${renewal.item}"]}"""
        assertThat(post("/api/v1/meetings/$m/minutes/comment-requests", director.session, body)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(post("/api/v1/meetings/$m/minutes/comment-requests", secretary.session, body)).hasStatusOk()
        Audit.assertAudited(tenant.id, "minutes.comments_requested", m.toString(), secretary.id.toString())
        assertThat(latest(outsider)["kind"].asString()).isEqualTo("MINUTES_COMMENTS_REQUESTED")
        assertThat(read("/api/v1/meetings/$m/minutes", outsider.session)).hasStatusOk()
        assertThat(comment(m, outsider, """{"kind":"SUGGESTION","itemId":"${loan.item}","text":"Not mine to read"}"""))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(comment(m, outsider, """{"kind":"SUGGESTION","itemId":"${renewal.item}","text":"The covenant is too loose"}"""))
            .hasStatus(HttpStatus.CREATED)
        // The deadline: tomorrow at the earliest.
        val today = transactions.readInTenant(tenant.id) { workingDays.today() }
        assertThat(step(m, "comments-until", secretary, """{"commentsUntil":"$today"}""", org.springframework.http.HttpMethod.PUT))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        val later = today.plusDays(9)
        assertThat(step(m, "comments-until", secretary, """{"commentsUntil":"$later"}""", org.springframework.http.HttpMethod.PUT))
            .hasStatusOk()
        assertThat(row(m).commentsUntil).isEqualTo(transactions.readInTenant(tenant.id) { workingDays.closeOf(later) })
        Audit.assertAudited(tenant.id, "minutes.deadline_changed", m.toString(), secretary.id.toString())
        // Taken back to the draft, with a reason; the comments stay.
        assertThat(step(m, "withdraw", secretary)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(step(m, "withdraw", secretary, """{"reason":"Decision on item 3 to be corrected"}""")).hasStatusOk()
        assertThat(row(m).state).isEqualTo(MinutesStage.DRAFT)
        assertThat(events(m).last().kind).isEqualTo(MinutesEventKind.WITHDRAWN)
        Audit.assertAudited(tenant.id, "minutes.withdrawn", m.toString(), secretary.id.toString())
        assertThat(comments(m, secretary)).hasSize(1)
        assertThat(read("/api/v1/meetings/$m/minutes", outsider.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `approved by circulation, ended early with a reason, or by every reader's answer, the chair told`() {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val early = drafted("Term loan, Delta Textiles")
        circulated(early.meeting.id)
        assertThat(step(early.meeting.id, "end-circulation", secretary)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(step(early.meeting.id, "end-circulation", secretary, """{"reason":"The credit line is needed now"}""")).hasStatusOk()
        val ended = row(early.meeting.id)
        assertThat(ended.state).isEqualTo(MinutesStage.AWAITING_CHAIR)
        assertThat(ended.awaitingSince).isNotNull()
        assertThat(Audit.assertAudited(tenant.id, "minutes.circulation_ended", early.meeting.id.toString()).canonical).contains("EARLY")
        assertThat(latest(chair)["kind"].asString()).isEqualTo("MINUTES_CONFIRMATION_REQUESTED")

        val all = drafted("Facility renewal, Acme")
        val m = all.meeting.id
        circulated(m)
        listOf(chair, vice, director).forEach { assertThat(comment(m, it, """{"kind":"NO_COMMENTS"}""")).hasStatus(HttpStatus.CREATED) }
        assertThat(row(m).state).isEqualTo(MinutesStage.CIRCULATED)
        assertThat(
            comment(m, colleague, """{"kind":"SUGGESTION","text":"Fine, but spell the borrower right"}"""),
        ).hasStatus(HttpStatus.CREATED)
        assertThat(row(m).state).describedAs("the last answer sends them to the chair").isEqualTo(MinutesStage.AWAITING_CHAIR)
        assertThat(events(m).last().let { it.kind to it.how }).isEqualTo(MinutesEventKind.CIRCULATION_ENDED to "ALL_ANSWERED")
        // The secretary still answers the suggestion while the chair approves.
        val id = comments(m, secretary).single { it["kind"].asString() == "SUGGESTION" }["id"].asString()
        assertThat(post("/api/v1/meetings/$m/minutes/comments/$id/response", secretary.session, """{"response":"ADOPTED"}""")).hasStatusOk()
    }

    @Test
    fun `the sweep, the deadline sends the minutes to the chair, and each reminder goes once`() {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        // (k) a draft not circulated ten working days after the meeting: the secretaries, once.
        val overdue = drafted("Working capital, Mill")
        val held = row(overdue.meeting.id).heldAt
        sweep(held.plus(Duration.ofDays(30)))
        sweep(held.plus(Duration.ofDays(31)))
        assertThat(kinds(secretary).count { it == "MINUTES_DRAFT_OVERDUE" }).isEqualTo(1)
        Audit.assertAudited(tenant.id, "minutes.reminded.draft_overdue", overdue.meeting.id.toString())

        val r = drafted("Term loan, Delta Textiles")
        val m = r.meeting.id
        val until = checkNotNull(circulated(m).commentsUntil)
        assertThat(comment(m, director, """{"kind":"NO_COMMENTS"}""")).hasStatus(HttpStatus.CREATED)
        // (j) 09:00 on the deadline's day: the readers who have not answered, once; not the secretary, not who answered.
        val morning = transactions.readInTenant(tenant.id) { workingDays.at(workingDays.dayOf(until), LocalTime.of(10, 0)) }
        sweep(morning)
        sweep(morning.plus(Duration.ofMinutes(30)))
        listOf(chair, vice, colleague).forEach { assertThat(kinds(it).count { k -> k == "MINUTES_COMMENTS_CLOSING" }).isEqualTo(1) }
        listOf(director, secretary).forEach { assertThat(kinds(it)).doesNotContain("MINUTES_COMMENTS_CLOSING") }
        Audit.assertAudited(tenant.id, "minutes.reminded.comments_closing", m.toString())
        // (i) the deadline passed: to the chair, once.
        sweep(until.plusSeconds(3600))
        sweep(until.plusSeconds(7200))
        assertThat(row(m).state).isEqualTo(MinutesStage.AWAITING_CHAIR)
        assertThat(events(m).count { it.kind == MinutesEventKind.DEADLINE_PASSED }).isEqualTo(1)
        // (l) three working days with the chair: the confirmer is reminded, once.
        val since = checkNotNull(row(m).awaitingSince)
        val before = kinds(chair).count { it == "MINUTES_CONFIRMATION_REQUESTED" }
        sweep(since.plus(Duration.ofDays(10)))
        sweep(since.plus(Duration.ofDays(11)))
        assertThat(kinds(chair).count { it == "MINUTES_CONFIRMATION_REQUESTED" }).isEqualTo(before + 1)
        Audit.assertAudited(tenant.id, "minutes.reminded.confirmation", m.toString())
    }

    private fun sweep(now: Instant) = asSystem { transactions.inTenant(tenant.id) { minutesSweep.sweep(now) } }

    private fun comment(
        meetingId: UUID,
        by: Person,
        body: String,
    ) = post("/api/v1/meetings/$meetingId/minutes/comments", by.session, body)

    private fun comments(
        meetingId: UUID,
        by: Person,
    ): List<JsonNode> =
        read("/api/v1/meetings/$meetingId/minutes/comments", by.session).json()["comments"].let { c ->
            (0 until c.size()).map { c[it] }
        }

    private fun latest(person: Person): JsonNode = read("/api/v1/me/notifications", person.session).json()["items"].first()

    private fun kinds(person: Person): List<String> =
        read("/api/v1/me/notifications?limit=100", person.session).json()["items"].let { c ->
            (0 until c.size()).map { c[it]["kind"].asString() }
        }
}
