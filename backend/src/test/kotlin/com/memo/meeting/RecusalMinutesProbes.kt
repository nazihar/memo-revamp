package com.memo.meeting

import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.LocalDate
import java.util.UUID

/**
 * The officer probes of the minutes' circulation and the preview "as" (E06 T6) for [RecusalLeakIT]: the recused secretary
 * calls each on the planted (scheduled) meeting and is refused or finds nothing, naming nothing.
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
    )
}

/** The reader's copy and the comments (E06 T6): the copy of a recused reader is MinutesRedactionIT's; here nothing. */
internal fun RecusalLeakIT.minutesCopyCalls(
    p: RecusalLeakFixture.Planted,
    person: com.memo.testsupport.Person,
): List<MvcTestResult> {
    val base = "/api/v1/meetings/${p.meeting.id}/minutes"
    return listOf(
        read("$base/document", person.session),
        read("$base/comments", person.session),
        send(HttpMethod.POST, "$base/comments", person, """{"kind":"SUGGESTION","itemId":"${p.item}","text":"x"}"""),
    )
}

/** The register and its check (officers; members 403), and one resolution (E06 T4), as [person] reads them. */
internal fun RecusalLeakIT.resolutionReads(
    forum: UUID,
    person: com.memo.testsupport.Person,
): List<MvcTestResult> =
    listOf("/api/v1/forums/$forum/resolutions", "/api/v1/forums/$forum/resolutions/check", "/api/v1/resolutions/${UUID.randomUUID()}")
        .map { read(it, person.session) }
