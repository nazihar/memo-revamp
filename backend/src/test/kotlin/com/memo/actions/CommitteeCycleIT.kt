package com.memo.actions

import com.memo.minutes.ReleaseFixture
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
 */
class CommitteeCycleIT : ReleaseFixture() {
    @Test
    fun `memo, item, resolution, action and follow-up memo link both ways`() {
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

        // Every step audited, in one chain that verifies.
        listOf("memo.created", "forum.submission.placed", "minutes.decision.recorded", "minutes.resolution.numbered").forEach { kind ->
            assertThat(Audit.entries(tenant.id).map { it.action }).describedAs(kind).contains(kind)
        }
        listOf("actions.action.issued", "actions.memo.raised", "actions.done", "actions.closed").forEach {
            Audit.assertAudited(tenant.id, it, actionId)
        }
        Audit.assertAudited(tenant.id, "memo.created", followUp)
        Audit.assertChainValid(tenant.id)
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
