package com.memo.meeting

import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.LocalDate
import java.util.UUID

/**
 * The officer probes of the minutes' circulation and the preview "as" (E06 T6), issuing an extract (E06 T8) and making a
 * failed record again (the fix after E08 T10) for [RecusalLeakIT]: the recused secretary calls each on the planted
 * (scheduled) meeting and is refused or finds nothing, naming nothing.
 */
internal fun RecusalLeakIT.minutesOfficerProbes(): Map<String, (RecusalLeakFixture.Planted) -> MvcTestResult> {
    val base = { p: RecusalLeakFixture.Planted -> "/api/v1/meetings/${p.meeting.id}/minutes" }
    val later = LocalDate.now().plusDays(LATER_DAYS)
    return mapOf(
        "POST /api/v1/meetings/{meetingId}/minutes/send-for-review" to
            { p -> send(HttpMethod.POST, "${base(p)}/send-for-review", secretary, "{}", versioned(0)) },
        "POST /api/v1/meetings/{meetingId}/minutes/circulate" to
            { p -> send(HttpMethod.POST, "${base(p)}/circulate", secretary, """{"reason":"No chair"}""", versioned(0)) },
        "PUT /api/v1/meetings/{meetingId}/minutes/comments-until" to
            { p -> send(HttpMethod.PUT, "${base(p)}/comments-until", secretary, """{"commentsUntil":"$later"}""", versioned(0)) },
        "POST /api/v1/meetings/{meetingId}/minutes/withdraw" to
            { p -> send(HttpMethod.POST, "${base(p)}/withdraw", secretary, """{"reason":"Wrong"}""", versioned(0)) },
        "POST /api/v1/meetings/{meetingId}/minutes/end-circulation" to
            { p -> send(HttpMethod.POST, "${base(p)}/end-circulation", secretary, """{"reason":"Now"}""", versioned(0)) },
        "POST /api/v1/meetings/{meetingId}/minutes/comment-requests" to { p ->
            send(HttpMethod.POST, "${base(p)}/comment-requests", secretary, """{"userIds":["${d1.id}"],"itemIds":["${p.item}"]}""")
        },
        "POST /api/v1/meetings/{meetingId}/minutes/comments/{commentId}/response" to
            { p -> send(HttpMethod.POST, "${base(p)}/comments/${UUID.randomUUID()}/response", secretary, """{"response":"ADOPTED"}""") },
        "GET /api/v1/meetings/{meetingId}/minutes/preview" to { p -> read("${base(p)}/preview?as=${d1.id}", secretary.session) },
        // E06 T8: issuing a certified extract, of a resolution that is not there: 404, naming nothing.
        "POST /api/v1/resolutions/{resolutionId}/extracts" to
            { _ -> send(HttpMethod.POST, "/api/v1/resolutions/${UUID.randomUUID()}/extracts", secretary, "{}") },
        // Making a failed record again (the fix after E08 T10): the recused secretary is refused (403 `RECUSED`), naming nothing.
        "POST /api/v1/meetings/{meetingId}/minutes/record/retry" to { p ->
            val answer = send(HttpMethod.POST, "${base(p)}/record/retry", secretary, "{}")
            assertThat(answer).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(answer.response.contentAsString).contains("RECUSED")
            answer
        },
    )
}

private const val LATER_DAYS = 40L

/** The minutes page (E06 T5, first), the review, the confirmation and the stamps (E06 T6), as [person] calls them. */
internal fun RecusalLeakIT.minutesCalls(
    p: RecusalLeakFixture.Planted,
    person: com.memo.testsupport.Person,
): List<MvcTestResult> {
    val base = "/api/v1/meetings/${p.meeting.id}/minutes"
    return listOf(
        read(base, person.session),
        send(HttpMethod.POST, "$base/review", person, """{"decision":"AGREE"}""", versioned(0)),
        send(HttpMethod.POST, "$base/confirm", person, "{}", versioned(0)),
        send(HttpMethod.POST, "$base/approvals/${UUID.randomUUID()}/check", person),
        read("$base/approvals", person.session),
        send(HttpMethod.POST, "$base/corrections/${UUID.randomUUID()}/decide", person, """{"decision":"APPROVE"}"""),
    )
}

/**
 * The reader's copy and the comments (E06 T6), and the approved minutes' record, its versions and its check (E06 T7): the
 * copies of a recused reader are MinutesRedactionIT's and MinutesRecordIT's; here nothing.
 */
internal fun RecusalLeakIT.minutesCopyCalls(
    p: RecusalLeakFixture.Planted,
    person: com.memo.testsupport.Person,
): List<MvcTestResult> {
    val base = "/api/v1/meetings/${p.meeting.id}/minutes"
    return listOf(
        read("$base/document", person.session),
        read("$base/comments", person.session),
        send(HttpMethod.POST, "$base/comments", person, """{"kind":"SUGGESTION","itemId":"${p.item}","text":"x"}"""),
        read("$base/record", person.session),
        read("$base/record?version=1", person.session),
        read("$base/records", person.session),
        send(HttpMethod.POST, "$base/records/1/check", person),
        read("$base/corrections", person.session),
    )
}

/**
 * E06 T3's surface, in the shared scenario: the decisions list answers no canary and names no decision of the hidden
 * item, and the items' history and notes are 404. The planted decisions have a test of their
 * own (a decided item freezes its declarations, which the other probes and the last ruling of the scenario change).
 */
internal fun RecusalLeakIT.decisionsProbe(
    p: RecusalLeakFixture.Planted,
    person: com.memo.testsupport.Person,
    planted: Boolean = false,
) {
    val base = "/api/v1/meetings/${p.meeting.id}"
    val list = read("$base/decisions", person.session)
    clean(person, list)
    assertThat(list.response.contentAsString).doesNotContain(CANARY_DISSENT, CANARY_NOTE)
    val decisions = list.json()["decisions"].list().associateBy { UUID.fromString(it["itemId"].asString()) }
    assertThat(decisions.keys).describedAs("the hidden item's decision").doesNotContain(p.hidden)
    if (planted) {
        val mine = decisions.getValue(p.item)
        assertThat(
            mine["viewerRecused"].asBoolean() to mine["text"].asString(),
        ).isEqualTo(true to "It was resolved that the term loan be approved.")
        listOf("vote", "dissent", "abstentions", "tookNoPart", "snapshotId", "recordedBy").forEach {
            assertThat(mine.absent(it)).describedAs("$it of a recused view").isTrue()
        }
    }
    p.both.forEach { item ->
        listOf("$base/items/$item/decision/entries", "$base/items/$item/notes").forEach { uri ->
            val answer = read(uri, person.session)
            assertThat(answer).hasStatus(HttpStatus.NOT_FOUND)
            assertThat(answer.response.contentAsString).doesNotContain(CANARY_DISSENT, CANARY_NOTE, RecusalLeakFixture.CANARY_TITLE)
        }
    }
}

/** E06 T3: a dissent's reason and the secretary's notes on the planted item, which no recused viewer may read. */
internal const val CANARY_DISSENT = "CANARY-DS dissent reason"
internal const val CANARY_NOTE = "CANARY-NT secretary notes"

/** The register and its check (officers; members 403), and one resolution (E06 T4), as [person] reads them. */
internal fun RecusalLeakIT.resolutionReads(
    forum: UUID,
    person: com.memo.testsupport.Person,
): List<MvcTestResult> =
    listOf("/api/v1/forums/$forum/resolutions", "/api/v1/forums/$forum/resolutions/check", "/api/v1/resolutions/${UUID.randomUUID()}")
        .map { read(it, person.session) }

/** A resolution's certified extracts and one extract (E06 T8), as [person] reads them: nothing there, naming nothing. */
internal fun RecusalLeakIT.extractReads(person: com.memo.testsupport.Person): List<MvcTestResult> {
    val resolution = UUID.randomUUID()
    return listOf(
        read("/api/v1/resolutions/$resolution/extracts", person.session),
        read("/api/v1/resolutions/$resolution/extracts/${UUID.randomUUID()}/content", person.session),
    )
}
