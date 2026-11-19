package com.memo.meeting

/**
 * Every read path that can reveal an agenda item or a paper (test plan E03, "Leak-suite skeleton"; ADR-0061, ADR-0062),
 * for the leak suite: [PaperLeakIT] runs one probe per [Surface] for a person outside the rule, and its meta-test fails
 * when an endpoint of the meeting module is in neither [endpoints] nor [officersOnly]. E04 adds the recused-member probe
 * to every surface, and its own surfaces (declarations); E05–E08 add theirs (invitations, minutes, actions, search).
 */
object PaperSurfaceRegistry {
    enum class Surface(
        val what: String,
    ) {
        MEMBERS_AGENDA("A published revision as the viewer reads it: lines, titles, details, papers, consent requests"),
        WHAT_CHANGED("\"What changed since you last looked\" on the members' agenda"),
        REVISION_LIST("The published revisions: numbers, dates, notes"),
        PREVIEW("The draft as one person of the audience would see it if published now"),
        PAPER_CONTENT("A paper's content (PDF or original) through the meeting"),
        FILES_ENDPOINT("The generic files endpoints for a `meeting.paper` file (the purpose's mayRead)"),
        NOTIFICATIONS("The per-recipient notifications of a revision (in-app and email)"),
        MY_MEETINGS("\"My meetings\": the agenda's revision and the changes since the viewer last looked"),
        CONSENT_REQUESTS("Asking to discuss a consent item, and withdrawing (answered with the members' agenda)"),
        MEETING("A meeting's details and a committee's meetings: no item content at all"),
        DECLARATIONS("Declaring an interest in an item, the declarant's own declarations and the review (E04 T1)"),
    }

    /** The meeting module's endpoints that any person of the audience may call, and the surface each one is. */
    val endpoints: Map<String, Surface> =
        mapOf(
            "GET /api/v1/meetings/{meetingId}/agenda" to Surface.MEMBERS_AGENDA,
            "GET /api/v1/meetings/{meetingId}/agenda/revisions" to Surface.REVISION_LIST,
            "GET /api/v1/meetings/{meetingId}/papers/{paperId}/content" to Surface.PAPER_CONTENT,
            "POST /api/v1/meetings/{meetingId}/agenda/items/{itemId}/discussion-requests" to Surface.CONSENT_REQUESTS,
            "POST /api/v1/meetings/{meetingId}/agenda/items/{itemId}/discussion-requests/mine/withdraw" to Surface.CONSENT_REQUESTS,
            "POST /api/v1/meetings/{meetingId}/seen" to Surface.WHAT_CHANGED,
            "GET /api/v1/me/meetings" to Surface.MY_MEETINGS,
            "GET /api/v1/meetings/{meetingId}" to Surface.MEETING,
            "GET /api/v1/forums/{forumId}/meetings" to Surface.MEETING,
            "GET /api/v1/meetings/{meetingId}/declarations/mine" to Surface.DECLARATIONS,
            "POST /api/v1/meetings/{meetingId}/items/{itemId}/declarations" to Surface.DECLARATIONS,
            "POST /api/v1/meetings/{meetingId}/declarations/{declarationId}/intent" to Surface.DECLARATIONS,
            "POST /api/v1/meetings/{meetingId}/declarations/{declarationId}/ruling" to Surface.DECLARATIONS,
            "POST /api/v1/meetings/{meetingId}/declarations/{declarationId}/withdraw" to Surface.DECLARATIONS,
            "POST /api/v1/meetings/{meetingId}/declarations/review" to Surface.DECLARATIONS,
        )

    /**
     * Endpoints only the forum's officers may call (404 or 403 for everyone else, `p4-e03-*.yaml`), with why. Officers see
     * the draft and every paper by role (design E03 §4); E04 narrows them for recusals and gives these a recused probe.
     */
    val officersOnly: Map<String, String> =
        listOf(
            "GET /api/v1/meetings/{meetingId}/agenda/draft",
            "GET /api/v1/meetings/{meetingId}/agenda/publish-preview",
        ).associateWith { "the working draft, for officers" } +
            mapOf("GET /api/v1/meetings/{meetingId}/agenda/preview" to "the preview, for officers (its content is the PREVIEW surface)") +
            listOf(
                "POST /api/v1/meetings/{meetingId}/agenda",
                "POST /api/v1/meetings/{meetingId}/agenda/sections",
                "PATCH /api/v1/meetings/{meetingId}/agenda/sections/{sectionId}",
                "POST /api/v1/meetings/{meetingId}/agenda/sections/{sectionId}/remove",
                "POST /api/v1/meetings/{meetingId}/agenda/moves",
                "POST /api/v1/meetings/{meetingId}/agenda/items",
                "PATCH /api/v1/meetings/{meetingId}/agenda/items/{itemId}",
                "POST /api/v1/meetings/{meetingId}/agenda/items/{itemId}/remove",
                "POST /api/v1/meetings/{meetingId}/agenda/items/{itemId}/papers",
                "PATCH /api/v1/meetings/{meetingId}/agenda/papers/{paperId}",
                "POST /api/v1/meetings/{meetingId}/agenda/papers/{paperId}/remove",
                "POST /api/v1/meetings/{meetingId}/agenda/publish",
            ).associateWith { "the builder: secretaries and forums.manage, answered with the draft" } +
            listOf(
                "POST /api/v1/meetings/{meetingId}/agenda/chair-agreement",
                "POST /api/v1/meetings/{meetingId}/agenda/chair-comments",
                "POST /api/v1/meetings/{meetingId}/agenda/items/{itemId}/late-decision",
                "POST /api/v1/meetings/{meetingId}/agenda/papers/{paperId}/late-decision",
            ).associateWith { "the chair's part (or a secretary on the chair's behalf), answered with the draft" } +
            listOf(
                "POST /api/v1/forums/{forumId}/meetings",
                "PATCH /api/v1/meetings/{meetingId}",
                "POST /api/v1/meetings/{meetingId}/schedule",
                "POST /api/v1/meetings/{meetingId}/reschedule",
                "POST /api/v1/meetings/{meetingId}/start",
                "POST /api/v1/meetings/{meetingId}/end",
                "POST /api/v1/meetings/{meetingId}/cancel",
                "POST /api/v1/meetings/{meetingId}/guests",
                "POST /api/v1/meetings/{meetingId}/guests/{userId}/remove",
            ).associateWith { "running the meeting: secretaries and forums.manage, answered with the meeting (no item content)" }
}
