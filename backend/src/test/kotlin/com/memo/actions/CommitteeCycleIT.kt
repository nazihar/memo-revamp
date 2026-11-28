package com.memo.actions

import com.memo.minutes.ReleaseFixture
import com.memo.org.internal.OrgCalendar
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The exit criterion's chain, walked both ways (test plan E07 T12 `CommitteeCycleIT`; Phase 4 overview, "memo → item →
 * resolution → action → follow-up memo"): a credit memo escalated to the Board Risk Committee is placed on a meeting and
 * approved with a condition, and takes its resolution number when the meeting is marked as held. The secretary makes the
 * condition an action for a member, who raises a follow-up memo from it and marks it done; the secretary verifies and
 * closes it. Then, through the API only, as the people who may read each step: forward from the source memo to its
 * submission, the item, the decision and its number, the action raised from its condition and the follow-up memo; and
 * back from the follow-up memo to the action, its source decision and item, and the item's memo, which is the source
 * memo. Every step's audit entry is there, and the tenant's chain verifies.
 *
 * E08 T8 extends it to the phase's exit check: the memo's note sheet carries the number and links the resolution; the
 * resolution leads to its decision, meeting and memo and back; the decision register finds the row by the resolution's
 * number and by the memo's, with every link of the chain on it; analytics counts the held meeting, the decision and the
 * closed action; each step's audit entry names who took it (the release job for the number), and the tenant's whole
 * chain verifies from its first entry to its head.
 */
class CommitteeCycleIT : ReleaseFixture() {
    /** The chain as built: the meeting, the placed memo, the resolution, the action and the follow-up memo. */
    private data class Chain(
        val r: Running,
        val placed: Placed,
        val number: String,
        val decisionId: UUID,
        val actionId: String,
        val reference: String,
        val followUp: String,
    )

    @Test
    fun `memo, item, resolution, action and follow-up memo link both ways`() {
        val chain = build()
        forward(chain)
        back(chain)
        // E08 T8: the memo moved on with the number, the resolution both ways, the register by both numbers, analytics.
        memoMovedOn(chain)
        resolutionBothWays(chain)
        register(chain)
        analytics()
        audited(chain)
    }

    /**
     * Every step of the cycle audited, each by the person (or the job) who took it, and the tenant's whole chain verified
     * from its first entry to its head (E08 T8).
     */
    private fun audited(chain: Chain) {
        val meeting =
            chain.r.meeting.id
                .toString()
        val by = { kind: String, actor: Person ->
            val entries = Audit.entries(tenant.id).filter { it.action == kind }
            assertThat(entries).describedAs(kind).isNotEmpty()
            assertThat(entries.map { it.actorId }).describedAs("$kind by").contains(actor.id.toString())
        }
        // The memo and its escalation (E02).
        Audit.assertAudited(tenant.id, "memo.created", chain.placed.memo.toString(), initiator.id.toString())
        by("forum.submission.placed", secretary)
        // The meeting and its agenda (E03, E05).
        listOf("meeting.created", "meeting.scheduled", "meeting.agenda.published", "meeting.started", "meeting.ended")
            .forEach { by(it, secretary) }
        by("meeting.late.agreed", chair)
        by("meeting.attendance.recorded", secretary)
        // The decision, its number and the memo moving on (E06).
        by("minutes.decision.recorded", secretary)
        Audit.assertAudited(tenant.id, "minutes.resolution.numbered", meeting, "minutes-release")
        Audit.assertAudited(tenant.id, "minutes.decision.released", meeting, secretary.id.toString())
        // The action, the follow-up memo, done and verified (E07).
        Audit.assertAudited(tenant.id, "actions.action.issued", chain.actionId, secretary.id.toString())
        Audit.assertAudited(tenant.id, "actions.memo.raised", chain.actionId, colleague.id.toString())
        Audit.assertAudited(tenant.id, "memo.created", chain.followUp, colleague.id.toString())
        Audit.assertAudited(tenant.id, "actions.done", chain.actionId, colleague.id.toString())
        Audit.assertAudited(tenant.id, "actions.closed", chain.actionId, secretary.id.toString())
        // The register's searches (E08).
        by("search.decisions.performed", secretary)
        Audit.assertChainValid(tenant.id)
    }

    /** The memo moved on: its note sheet's committee entry carries the number and links to the resolution. */
    private fun memoMovedOn(chain: Chain) {
        val entry =
            ok("/api/v1/memos/${chain.placed.memo}/notes", initiator)["entries"].list().single {
                it["actionKind"]?.asString() == "FORUM_DECIDED"
            }
        assertThat(entry["committeeOutcome"].asString()).isEqualTo("APPROVED_WITH_CONDITIONS")
        assertThat(entry["resolution"].asString()).isEqualTo(chain.number)
        assertThat(entry["resolutionId"].asString()).isEqualTo(resolutionId(chain).toString())
    }

    /** The resolution forward to its decision, meeting and memo, and back from the decision to the resolution. */
    private fun resolutionBothWays(chain: Chain) {
        val resolution = ok("/api/v1/resolutions/${resolutionId(chain)}", secretary)
        assertThat(resolution["number"].asString()).isEqualTo(chain.number)
        assertThat(resolution["decisionId"].asString()).isEqualTo(chain.decisionId.toString())
        assertThat(resolution["meetingId"].asString()).isEqualTo(
            chain.r.meeting.id
                .toString(),
        )
        assertThat(resolution["conditions"].list().map { it.asString() }).containsExactly("Security perfected before disbursement.")
        assertThat(transactions.readInTenant(tenant.id) { resolutionsApi.ofMemo(chain.placed.memo) }.map { it.number })
            .containsExactly(chain.number)
    }

    /**
     * The register (E08 T1–T2) finds the resolution by its number and by its memo's number, and its row links the meeting,
     * the item, the decision, the resolution, the submission and the memo, which leads back to the meeting.
     */
    private fun register(chain: Chain) {
        val row = search(chain.number).single()
        assertThat(row["level"].asString()).isEqualTo("FULL")
        assertThat(row["decisionId"].asString()).isEqualTo(chain.decisionId.toString())
        assertThat(row["resolutionNumber"].asString()).isEqualTo(chain.number)
        assertThat(row["resolutionId"].asString()).isEqualTo(resolutionId(chain).toString())
        assertThat(row["meetingId"].asString()).isEqualTo(
            chain.r.meeting.id
                .toString(),
        )
        assertThat(row["itemId"].asString()).isEqualTo(chain.placed.item.toString())
        assertThat(row["submissionId"].asString()).isEqualTo(chain.placed.submission.toString())
        assertThat(row["memoId"].asString()).isEqualTo(chain.placed.memo.toString())
        assertThat(row["outcomeKind"].asString()).isEqualTo("APPROVED_WITH_CONDITIONS")
        val memoNumber = row["memoNumber"]?.takeUnless { it.isNull }?.asString()
        if (memoNumber != null) {
            assertThat(search(memoNumber).map { it["decisionId"].asString() }).containsExactly(chain.decisionId.toString())
        }
        val submission = ok("/api/v1/memos/${row["memoId"].asString()}/forum-submissions", initiator).list().single()
        assertThat(submission["meetingId"].asString()).isEqualTo(row["meetingId"].asString())
    }

    /** Analytics (E08 T4) counts the cycle: the held meeting's attendance, the decision and the action closed. */
    private fun analytics() {
        // The committee meets in Dhaka: the tenant keeps its dates there, as a real tenant does (see AnalyticsFixture).
        transactions.inTenant(tenant.id) { settings.put(OrgCalendar.TIME_ZONE, "Asia/Dhaka") }
        val summary =
            ok("/api/v1/analytics/committees?forumIds=$brc", secretary)["committees"].list().single {
                it["forumId"].asString() == brc.toString()
            }
        assertThat(summary["attendance"]["heldMeetings"].asInt()).isOne()
        assertThat(summary["timeToDecision"]["decisions"].asInt()).isOne()
        assertThat(summary["deferrals"]["decisions"].asInt()).isOne()
        assertThat(summary["actions"]["closed"].asInt()).isOne()
    }

    /** The register's rows for [text] (a number in any spelling) as the secretary. */
    private fun search(text: String): List<JsonNode> {
        val result = send(HttpMethod.POST, "/api/v1/decision-register/search", secretary, """{"text":"$text"}""")
        assertThat(result).describedAs("search $text").hasStatusOk()
        return result.json()["rows"].list()
    }

    private fun resolutionId(chain: Chain): UUID =
        transactions.readInTenant(tenant.id) { resolutionsApi.ofDecision(chain.decisionId) }.single().id

    /** Memo → item → resolution (E02, E03, E06), then the action, its follow-up memo, done and verified (E07). */
    private fun build(): Chain {
        // The memo to the resolution (E02, E03, E06).
        val r = running("Term loan, Kushtia Agro Mills")
        val placed = r.items.single()
        decide(r, placed.item, APPROVED_WITH_CONDITIONS)
        held(r)
        val number = checkNotNull(numberOf(placed.item))
        val decisionId = decision(placed.item).decision.id

        // The action from the condition, for a member (E07 T3), issued.
        val due = LocalDate.now(ZoneOffset.UTC).plusDays(30)
        val raised =
            send(
                HttpMethod.POST,
                "/api/v1/meetings/${r.meeting.id}/actions",
                secretary,
                """{"source":"DECISION","decisionId":"$decisionId","conditionIndex":0,""" +
                    """"owner":{"kind":"PERSON","userId":"${colleague.id}"},"dueDate":"$due"}""",
            )
        assertThat(raised).hasStatus(HttpStatus.CREATED)
        val actionId = raised.json()["id"].asString()
        val issued = change(actionId, "issue", secretary)
        assertThat(issued).hasStatusOk()
        val reference = issued.json()["reference"].asString()
        assertThat(issued.json()["title"].asString()).isEqualTo("Security perfected before disbursement.")

        // The owner raises a follow-up memo (E07 T6) and marks the action done; the secretary verifies it (T5).
        val memoBody = """{"typeId":"${generalType(colleague.session)}","subject":"Kushtia Agro: security perfected"}"""
        val memo = send(HttpMethod.POST, "/api/v1/actions/$actionId/memos", colleague, memoBody)
        assertThat(memo).hasStatus(HttpStatus.CREATED)
        val followUp = memo.json()["memoId"].asString()
        val done = change(actionId, "done", colleague, """{"note":"Charge registered; memo raised."}""")
        assertThat(done).hasStatusOk()
        assertThat(done.json()["status"].asString()).isEqualTo("DONE_PENDING")
        val closed = change(actionId, "verify", secretary, """{"decision":"CLOSE","remark":"Seen."}""")
        assertThat(closed).hasStatusOk()
        assertThat(closed.json()["status"].asString()).isEqualTo("CLOSED")
        return Chain(r, placed, number, decisionId, actionId, reference, followUp)
    }

    /** Forward, as the people who may read each step. */
    private fun forward(chain: Chain) {
        val r = chain.r
        val placed = chain.placed
        val number = chain.number
        val decisionId = chain.decisionId
        val actionId = chain.actionId
        val reference = chain.reference
        val followUp = chain.followUp
        // Forward: the memo's committee panel → the meeting and its number → the item's decision → its actions → the memo.
        val submission = ok("/api/v1/memos/${placed.memo}/forum-submissions", initiator).list().single()
        assertThat(submission["id"].asString()).isEqualTo(placed.submission.toString())
        assertThat(submission["meetingId"].asString()).isEqualTo(r.meeting.id.toString())
        assertThat(submission["outcome"].asString()).isEqualTo("APPROVED_WITH_CONDITIONS")
        val item = itemOf(r.meeting.id, placed.item)
        assertThat(item["submissionId"].asString()).isEqualTo(placed.submission.toString())
        val decided =
            ok("/api/v1/meetings/${r.meeting.id}/decisions", secretary)["decisions"].list().single {
                it["itemId"].asString() ==
                    "${placed.item}"
            }
        assertThat(decided["id"].asString()).isEqualTo(decisionId.toString())
        assertThat(decided["resolutionNumber"].asString()).isEqualTo(number)
        val fromMeeting = ok("/api/v1/meetings/${r.meeting.id}/actions", secretary)
        assertThat(fromMeeting.toString()).contains(actionId, reference)
        val action = ok("/api/v1/actions/$actionId", secretary)
        assertThat(action["source"]["kind"].asString()).isEqualTo("DECISION")
        assertThat(action["source"]["decisionId"].asString()).isEqualTo(decisionId.toString())
        assertThat(action["source"]["conditionIndex"].asInt()).isZero()
        assertThat(action["source"]["label"].asString()).contains(number)
        assertThat(action["memos"].list().map { it["memoId"].asString() }).containsExactly(followUp)
    }

    /** Back, from the follow-up memo to the source memo. */
    private fun back(chain: Chain) {
        val r = chain.r
        val placed = chain.placed
        val actionId = chain.actionId
        val reference = chain.reference
        val followUp = chain.followUp
        // Back: the follow-up memo → "Raised from action" → the action's source → the item → the item's memo.
        val origin = ok("/api/v1/memos/$followUp", colleague)["followsUp"].list().single()
        assertThat(origin["reference"].asString()).isEqualTo(reference)
        assertThat(origin["recordId"].asString()).isEqualTo(actionId)
        val back = ok("/api/v1/actions/${origin["recordId"].asString()}", colleague)["source"]
        assertThat(back["meetingId"].asString()).isEqualTo(r.meeting.id.toString())
        assertThat(back["itemId"].asString()).isEqualTo(placed.item.toString())
        val sourceItem = itemOf(UUID.fromString(back["meetingId"].asString()), UUID.fromString(back["itemId"].asString()))
        assertThat(sourceItem["memoId"].asString()).isEqualTo(placed.memo.toString())
        assertThat(ok("/api/v1/memos/${sourceItem["memoId"].asString()}", initiator)["subject"].asString())
            .isEqualTo("Term loan, Kushtia Agro Mills")
    }

    private fun send(
        method: HttpMethod,
        uri: String,
        person: Person,
        body: String = "{}",
    ) = mvc.sendJson(method, tenant, uri, person.session, body, mapOf("Idempotency-Key" to UUID.randomUUID().toString()))

    /** POSTs [body] to the action [actionId]'s [what] as [person], at the version they read (If-Match). */
    private fun change(
        actionId: String,
        what: String,
        person: Person,
        body: String = "{}",
    ): org.springframework.test.web.servlet.assertj.MvcTestResult {
        val version = ok("/api/v1/actions/$actionId", person)["version"].asLong()
        val headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString(), "If-Match" to "\"$version\"")
        return mvc.sendJson(HttpMethod.POST, tenant, "/api/v1/actions/$actionId/$what", person.session, body, headers)
    }

    private fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }

    /** [path] read by [person], answered 200; its JSON. */
    private fun ok(
        path: String,
        person: Person,
    ): JsonNode {
        val result = read(path, person.session)
        assertThat(result).describedAs(path).hasStatusOk()
        return result.json()
    }

    /** The item [itemId] of [meetingId]'s agenda as its officers read it. */
    private fun itemOf(
        meetingId: UUID,
        itemId: UUID,
    ): JsonNode =
        ok("/api/v1/meetings/$meetingId/agenda/draft", secretary)["sections"]
            .list()
            .flatMap { it["items"].list() }
            .single { it["id"].asString() == itemId.toString() }
}
