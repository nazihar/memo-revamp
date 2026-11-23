package com.memo.minutes

import com.memo.forum.MemoResumes
import com.memo.forum.MinutesApproval
import com.memo.meeting.AttendanceMode
import com.memo.meeting.MeetingStatus
import com.memo.minutes.internal.MinutesApprovalStore
import com.memo.minutes.internal.MinutesEventKind
import com.memo.minutes.internal.MinutesRole
import com.memo.minutes.internal.MinutesStage
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.ageSessionFactors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * The approval (test plan E06 T6 `MinutesApprovalIT`; ADR-0066, ADR-0051, design E06 §1.6.3, §1.6.4): at the next meeting
 * through its "Minutes of the previous meeting" item (linked when its agenda is published; DEFERRED keeps them circulated,
 * APPROVED sends them to the chair); by circulation; the chair's confirmation with a fresh step-up, the authenticated
 * approval chained per committee, the meeting MINUTES_APPROVED with attendance, declarations and decisions frozen, and the
 * decisions sent to their memos on approval when the committee type says so.
 */
class MinutesApprovalIT : MinutesFixture() {
    @Autowired lateinit var approvals: MinutesApprovalStore

    @Test
    fun `at the next meeting, linked to its item, deferred then approved there, confirmed by the chair with a step-up`() {
        memosResume(MemoResumes.ON_MINUTES_APPROVAL)
        val a = drafted("Term loan, Delta Textiles")
        val m = a.meeting.id
        assertThat(decision(a.items.single().item).decision.releasedAt).describedAs("not at the meeting's end").isNull()
        circulated(m)

        // The next meeting's agenda is published: the minutes are linked to its "Minutes of the previous meeting" item.
        val b = running()
        val item = previousMinutesItem(b)
        val linked = row(m)
        assertThat(linked.linkedMeetingId to linked.linkedItemId).isEqualTo(b.meeting.id to item)
        assertThat(events(m).map { it.kind }).contains(MinutesEventKind.LINKED_TO_MEETING)
        Audit.assertAudited(tenant.id, "minutes.linked_to_meeting", m.toString())
        val link = linksOf(b.meeting.id, item, director).single()
        assertThat(link["kind"].asString() to link["meetingId"].asString()).isEqualTo("MINUTES" to m.toString())
        assertThat(link["label"].asString()).startsWith("Minutes of ")
        // The meeting's own fields, for the client's words in the reader's language (E06 T11).
        val earlier = read("/api/v1/meetings/$m", director.session).json()
        assertThat(listOf("meetingLabel", "meetingStartsAt", "meetingTimeZone").map { link[it]?.asString() })
            .containsExactly(earlier["label"]?.asString(), earlier["startsAt"].asString(), earlier["timeZone"].asString())
        // Its readers read the minutes from there.
        assertThat(read("/api/v1/meetings/$m/minutes", director.session)).hasStatusOk()

        // Deferred: still circulated; then approved (a correction): to the chair.
        decide(b, item, DEFER)
        assertThat(row(m).state).isEqualTo(MinutesStage.CIRCULATED)
        decide(b, item, APPROVE_MINUTES.copy(reason = "Approved once item 5 was amended"))
        val awaiting = row(m)
        assertThat(awaiting.state).isEqualTo(MinutesStage.AWAITING_CHAIR)
        assertThat(awaiting.approvingMeetingId to awaiting.approvingDecisionId).isEqualTo(b.meeting.id to decision(item).decision.id)
        assertThat(decision(item).entry.approvesMinutesIds).containsExactly(m)
        Audit.assertAudited(tenant.id, "minutes.approved_at_meeting", m.toString(), secretary.id.toString())
        assertThat(latest(chair)["kind"].asString()).isEqualTo("MINUTES_CONFIRMATION_REQUESTED")

        // Who confirms: the chair only, never the vice-chair while the chair is able; 404 for someone outside.
        assertThat(step(m, "confirm", outsider)).hasStatus(HttpStatus.NOT_FOUND)
        val byVice = step(m, "confirm", vice)
        assertThat(byVice).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(byVice.response.contentAsString).contains("NOT_CONFIRMER")
        // A stale second factor: the step-up problem, nothing approved.
        ageSessionFactors(chair.session)
        assertThat(step(m, "confirm", chair)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(row(m).state).isEqualTo(MinutesStage.AWAITING_CHAIR)
        ageSessionFactors(chair.session, issuedAt = Instant.now())
        val confirmed = step(m, "confirm", chair)
        assertThat(confirmed).hasStatusOk()
        assertThat(confirmed.json()["state"].asString()).isEqualTo("APPROVED")

        val stamp = assertApproval(m)
        // The meeting is MINUTES_APPROVED: its decisions final, attendance and declarations frozen.
        assertThat(asUser(secretary.account) { reads.get(m) }.status).isEqualTo(MeetingStatus.MINUTES_APPROVED)
        Audit.assertAudited(tenant.id, "meeting.minutes_approved", m.toString(), chair.id.toString())
        val loan = a.items.single().item
        assertThat(decision(loan).decision.finalAt).isNotNull()
        assertThat(refusal { decide(a, loan, APPROVED.copy(reason = "Too late")) }).isEqualTo("FROZEN")
        assertThat(refusal { declare(colleague, a.meeting, loan) }).isEqualTo("FROZEN")
        assertThat(
            refusal {
                asUser(secretary.account) { attendance.arrivals(m, listOf(colleague.id), AttendanceMode.IN_PERSON, null) }
            },
        ).isIn("FROZEN", "WRONG_STATE")
        // ON_MINUTES_APPROVAL: the approval's job sends the decision to its memo.
        approved(m)
        assertThat(decision(loan).decision.releasedAt).isNotNull()

        assertStamps(m, stamp.id)
        // Confirming again: the minutes are approved.
        assertThat(step(m, "confirm", chair).response.contentAsString).contains("FROZEN")
    }

    @Test
    fun `by circulation, to the chair when the period ends, confirmed, the committee's approvals chained`() {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val first = drafted("Term loan, Delta Textiles")
        val second = drafted("Facility renewal, Acme")
        listOf(first, second).forEach { r ->
            val m = r.meeting.id
            circulated(m)
            // Not yet with the chair: 409.
            assertThat(step(m, "confirm", chair).response.contentAsString).contains("WRONG_STATE")
            assertThat(step(m, "end-circulation", secretary, """{"reason":"Needed for the drawdown"}""")).hasStatusOk()
            assertThat(step(m, "confirm", director)).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(step(m, "confirm", chair)).hasStatusOk()
        }
        val chain = transactions.readInTenant(tenant.id) { approvals.ofForum(brc) }
        assertThat(chain).hasSize(2)
        assertThat(chain[0].previousHash).isNull()
        assertThat(chain[1].previousHash).isEqualTo(chain[0].recordHash)
        assertThat(chain.map { it.meetingId }).containsExactly(first.meeting.id, second.meeting.id)
        // AT_MEETING_END (the default): the decisions went to their memos when the meeting was held, not again.
        assertThat(decision(second.items.single().item).decision.releasedAt).isNotNull()
    }

    @Test
    fun `the vice-chair confirms when the chair declared an interest in an item of the minutes`() {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val r = drafted("Term loan, Delta Textiles", before = { declare(chair, it.meeting, previousMinutesItem(it)) })
        val m = r.meeting.id
        circulated(m)
        assertThat(step(m, "end-circulation", secretary, """{"reason":"Needed now"}""")).hasStatusOk()
        assertThat(step(m, "confirm", chair)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(step(m, "confirm", vice)).hasStatusOk()
        val stamp = transactions.readInTenant(tenant.id) { approvals.ofMeeting(m) }.single()
        assertThat(stamp.actorId to stamp.role).isEqualTo(vice.id to MinutesRole.VICE_CHAIR)
    }

    /** The links of [item] on [meetingId]'s members' agenda as [person] reads it. */
    private fun linksOf(
        meetingId: UUID,
        item: UUID,
        person: Person,
    ): List<JsonNode> {
        val sections = read("/api/v1/meetings/$meetingId/agenda", person.session).json()["sections"]
        val items = (0 until sections.size()).flatMap { s -> sections[s]["items"].let { i -> (0 until i.size()).map { i[it] } } }
        val links = items.single { it["id"].asString() == item.toString() }["links"]
        return (0 until links.size()).map { links[it] }
    }

    private fun latest(person: Person): JsonNode = read("/api/v1/me/notifications", person.session).json()["items"].first()

    private fun kinds(person: Person): List<String> =
        read("/api/v1/me/notifications?limit=100", person.session).json()["items"].let { c ->
            (0 until c.size()).map { c[it]["kind"].asString() }
        }

    /** The approved minutes and their authenticated approval (ADR-0051). */
    private fun assertApproval(m: UUID): com.memo.minutes.internal.ApprovalRow {
        val approved = row(m)
        assertThat(approved.state to approved.approvedAt).matches { it.first == MinutesStage.APPROVED && it.second != null }
        val stamp = transactions.readInTenant(tenant.id) { approvals.ofMeeting(m) }.single()
        assertThat(stamp.id).isEqualTo(approved.confirmedApprovalId)
        assertThat(stamp.actorId to stamp.role).isEqualTo(chair.id to MinutesRole.CHAIR)
        val docx =
            checkNotNull(
                transactions.readInTenant(
                    tenant.id,
                ) { files.version(checkNotNull(files.info(checkNotNull(approved.fileId))?.currentVersionId)) },
            )
        assertThat(stamp.docxVersionId to stamp.docxSha256).isEqualTo(docx.id to docx.sha256)
        assertThat(stamp.authentication["stepUpRequired"].asBoolean()).isTrue()
        assertThat(stamp.authentication["sessionHash"].asString()).hasSize(64)
        assertThat(stamp.client["ip"].isNull).isFalse()
        val audited = Audit.assertAudited(tenant.id, "minutes.confirmed", m.toString(), chair.id.toString())
        assertThat(audited.canonical).contains(stamp.recordHash, docx.sha256)

        return stamp
    }

    /** The stamps, for the approved minutes' readers, and their check. */
    private fun assertStamps(
        m: UUID,
        stampId: UUID,
    ) {
        // The stamps, for the approved minutes' readers; the check recomputes them.
        val stamps = read("/api/v1/meetings/$m/minutes/approvals", director.session)
        assertThat(stamps).hasStatusOk()
        assertThat(stamps.json()[0]["factors"].toString()).contains("PASSWORD")
        assertThat(stamps.json()[0]["client"]?.isNull ?: true).describedAs("client fields are the auditors'").isTrue()
        assertThat(read("/api/v1/meetings/$m/minutes/approvals", outsider.session)).hasStatus(HttpStatus.NOT_FOUND)
        val check = post("/api/v1/meetings/$m/minutes/approvals/$stampId/check", director.session)
        assertThat(check).hasStatusOk()
        assertThat(check.json()["matches"].asBoolean()).isTrue()
        Audit.assertAudited(tenant.id, "minutes.approval.checked", m.toString(), director.id.toString())
        // Everyone who read the circulated minutes is told.
        assertThat(kinds(colleague)).contains("MINUTES_APPROVED")
    }
}
