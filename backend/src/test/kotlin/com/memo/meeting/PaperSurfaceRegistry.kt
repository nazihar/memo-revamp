package com.memo.meeting

/**
 * Every read path that can reveal an agenda item or a paper (test plan E03, "Leak-suite skeleton"; ADR-0061, ADR-0062),
 * for the leak suites: [PaperLeakIT] runs one probe per [Surface] for a person outside the rule, and its meta-test fails
 * when an endpoint of the meeting module is in neither [endpoints] nor [officersOnly]. E04 T2 added the recused-member probe
 * to every surface, and its own surfaces (declarations, registers). E04 T5 made it the recusal registry as well
 * ([RecusalLeakIT]): the channels beyond the meeting's endpoints ([Channel]), the forum module's endpoints ([forum]), and the
 * surfaces of later epics still to come ([pending]); its meta-test fails when a controller of [modules] is not registered.
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
        REGISTERS("The meeting register, \"Remind them now\", the register across committees and its export (E04 T4)"),
        LATE_REQUESTS("The late items and papers the viewer decides, and their decision (E04, the vice-chair when the chair is recused)"),
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
            // The decider's late requests (E04 §1.7 as built): anyone who sees the meeting; only what they decide, by the rule.
            "GET /api/v1/meetings/{meetingId}/agenda/late-requests" to Surface.LATE_REQUESTS,
            "POST /api/v1/meetings/{meetingId}/agenda/late-requests/decisions" to Surface.LATE_REQUESTS,
            // The register across committees (E04 T4): forum.read, forums.manage and the committees' officers; 403 to others.
            "GET /api/v1/declarations" to Surface.REGISTERS,
            "GET /api/v1/declarations/export" to Surface.REGISTERS,
            // "Add to my calendar" (E05 T2): the meeting's time, place and link only, never an item (InvitationContentIT).
            "GET /api/v1/meetings/{meetingId}/calendar.ics" to Surface.MEETING,
            // Replies (E05 T3): answers, notes and counts; never an item (an alternate's recusals are AlternateRecusalIT).
            "GET /api/v1/meetings/{meetingId}/rsvp/mine" to Surface.MEETING,
            "PUT /api/v1/meetings/{meetingId}/rsvp/mine" to Surface.MEETING,
            "GET /api/v1/meetings/{meetingId}/rsvps" to Surface.MEETING,
            // Attendance and the live quorum (E05 T4): people, states, times, item ids and numbers; never an item's content.
            "GET /api/v1/meetings/{meetingId}/attendance" to Surface.MEETING,
            "GET /api/v1/meetings/{meetingId}/quorum" to Surface.MEETING,
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
            ).associateWith { "running the meeting: secretaries and forums.manage, answered with the meeting (no item content)" } +
            mapOf(
                "GET /api/v1/meetings/{meetingId}/invitations" to "the invitations panel (E05 T2): officers, people and delivery only",
                "POST /api/v1/meetings/{meetingId}/invitations/resend" to "resending invitations (E05 T2): secretaries and forums.manage",
                "PUT /api/v1/meetings/{meetingId}/rsvps/{userId}" to "recording an answer on someone's behalf (E05 T3): secretaries",
                "GET /api/v1/meetings/{meetingId}/declarations" to
                    "the meeting register (E04 T4): officers, the chair and vice-chair; a recused officer's own row only (REGISTERS probe)",
                "POST /api/v1/meetings/{meetingId}/declarations/remind" to
                    "the declarations reminder (E04 T4): secretaries and forums.manage, names no item (REGISTERS probe)",
                "POST /api/v1/meetings/{meetingId}/attendance/events" to "recording attendance (E05 T4): secretaries and forums.manage",
                "POST /api/v1/meetings/{meetingId}/attendance/arrivals" to
                    "marking arrivals at once (E05 T4): secretaries and forums.manage",
                "POST /api/v1/meetings/{meetingId}/attendance/events/{eventId}/void" to "voiding an attendance event (E05 T4): secretaries",
                "POST /api/v1/meetings/{meetingId}/progress" to "the item in progress (E05 T4): secretaries and forums.manage",
                "GET /api/v1/meetings/{meetingId}/quorum/changes" to "the quorum lost and restored (E05 T4): officers",
            )

    /** The modules whose controllers must all be registered here (test plan E04, "Meta-test"); later epics add theirs. */
    val modules: List<String> =
        listOf("com.memo.meeting", "com.memo.forum", "com.memo.minutes", "com.memo.actions", "com.memo.decision")

    /**
     * What reaches a person about an item beyond the meeting module's endpoints (test plan E04, "Surfaces"): each has a
     * recusal probe in [RecusalLeakIT].
     */
    enum class Channel(
        val what: String,
    ) {
        IN_APP("In-app notifications (`/me/notifications`): publication, changes, declarations, requests"),
        MAIL("Every email of the scenario (agenda, declarations, late papers, invitations and their calendar files), from Mailpit"),
        EXPORTS("The register's XLSX export and the meeting's calendar file"),
        SEARCH("Memo search: meeting papers and their extracted text are never indexed (design E04 §1.7, ADR-0054)"),
        AUDIT("The audit viewer and the verifiable export: declarations carry hashes of the nature, never the text"),
        FORUM_QUEUE("The committee's submissions: officers by role, everyone else by the memo's own rule (design E04 §1.4)"),
    }

    /**
     * The forum module's endpoints and why none of them is an item surface: committee set-up carries no meeting at all;
     * submissions are the memo's cover sheet, which the committee's officers read by role and everyone else by the memo's own
     * rule. A recusal does not change memo access (design E04 §1.4); the meeting register flags a recused officer who still
     * reads the memo (`alsoReadsMemo`), and the [Channel.FORUM_QUEUE] probe checks that a recused member reads no queue.
     */
    val forum: Map<String, String> =
        listOf(
            "GET /api/v1/forums",
            "POST /api/v1/forums",
            "GET /api/v1/forums/{forumId}",
            "PATCH /api/v1/forums/{forumId}",
            "POST /api/v1/forums/{forumId}/dissolve",
            "POST /api/v1/forums/{forumId}/clone",
            "GET /api/v1/forums/{forumId}/policy",
            "GET /api/v1/me/forums",
            "GET /api/v1/forums/{forumId}/members",
            "POST /api/v1/forums/{forumId}/members",
            "PATCH /api/v1/forums/{forumId}/members/{memberId}",
            "POST /api/v1/forums/{forumId}/members/{memberId}/end",
            "POST /api/v1/forums/{forumId}/members/{memberId}/renew",
            "POST /api/v1/forums/{forumId}/members/{memberId}/change-role",
            "POST /api/v1/forums/{forumId}/members/{memberId}/withdraw",
            "POST /api/v1/forums/quorum-preview",
        ).associateWith { "committees and their members: no meeting or item content" } +
            listOf(
                "GET /api/v1/admin/forum-types",
                "GET /api/v1/admin/forum-types/presets",
                "POST /api/v1/admin/forum-types",
                "GET /api/v1/admin/forum-types/{typeId}",
                "POST /api/v1/admin/forum-types/{typeId}/activate",
                "POST /api/v1/admin/forum-types/{typeId}/deactivate",
                "GET /api/v1/admin/forum-types/{typeId}/versions",
                "GET /api/v1/admin/forum-types/{typeId}/versions/{number}",
                "POST /api/v1/admin/forum-types/{typeId}/versions",
                "PUT /api/v1/admin/forum-types/{typeId}/versions/{number}",
                "POST /api/v1/admin/forum-types/{typeId}/versions/{number}/submit",
                "POST /api/v1/admin/forum-types/{typeId}/versions/{number}/approve",
                "POST /api/v1/admin/forum-types/{typeId}/versions/{number}/send-back",
                "GET /api/v1/admin/forum-types/{typeId}/versions/{number}/minutes-template",
                "POST /api/v1/admin/forum-types/{typeId}/versions/{number}/minutes-template/preview",
                "POST /api/v1/admin/forum-types/resolution-numbering/preview",
            ).associateWith { "committee types (tenant administration): no meeting or item content" } +
            listOf(
                "GET /api/v1/forums/{forumId}/submissions",
                "GET /api/v1/forums/{forumId}/submissions/{id}",
                "PATCH /api/v1/forums/{forumId}/submissions/{id}",
                "POST /api/v1/forums/{forumId}/submissions/{id}/accept",
                "POST /api/v1/forums/{forumId}/submissions/{id}/defer",
                "POST /api/v1/forums/{forumId}/submissions/{id}/return",
                "POST /api/v1/forums/{forumId}/submissions/{id}/decline",
                "POST /api/v1/forums/{forumId}/submissions/{id}/refer",
                "GET /api/v1/memos/{memoId}/forum-submissions",
                "GET /api/v1/forums/{forumId}/submissions/ready",
            ).associateWith { "submissions: the memo's cover sheet, by the memo's rule and the officers' role (FORUM_QUEUE probe)" }

    /**
     * A surface of a later epic that reads meeting data and must get its recusal probe when it is built (test plan E04,
     * "Placeholders for later epics"; design E04 §1.7). The meta-test fails while an endpoint under one of [paths] exists
     * and the placeholder is still here: the epic replaces the placeholder with its probe in [RecusalLeakIT] and its
     * endpoints in [endpoints] or [officersOnly].
     */
    data class Pending(
        val epic: String,
        val what: String,
        val paths: List<String>,
    )

    /**
     * E05's attendance and live quorum (E05 T4) were built while T5 was written: their reads are probed in the MEETING
     * surface and their changes as officer probes in [RecusalLeakIT]; who counts for an item a holder is recused from is
     * E05's `PresenceBuilderProperties` and `LiveQuorumIT`.
     */
    val pending: List<Pending> =
        listOf(
            Pending(
                "E06",
                "The item's decision, vote counts and named dissent: a recused member is neither counted nor named (\"took no part\")",
                listOf("/api/v1/meetings/{meetingId}/decisions", "/api/v1/meetings/{meetingId}/items/{itemId}/decision"),
            ),
            Pending(
                "E06",
                "Draft and approved minutes: the recused version (heading, own declaration and decision; no discussion; ADR-0062)",
                listOf("/api/v1/meetings/{meetingId}/minutes", "/api/v1/minutes"),
            ),
            Pending(
                "E06",
                "Resolutions of the item and their numbers",
                listOf("/api/v1/resolutions", "/api/v1/meetings/{meetingId}/resolutions"),
            ),
            Pending(
                "E07",
                "Actions from the item: not shown to a recused person unless they own the action",
                listOf("/api/v1/actions", "/api/v1/me/actions", "/api/v1/meetings/{meetingId}/actions"),
            ),
            Pending(
                "E08",
                "The decision register, `search_decision` and analytics, with their exports: rows carry the item's recused ids",
                listOf("/api/v1/decisions", "/api/v1/decision-register", "/api/v1/analytics"),
            ),
        )
}
