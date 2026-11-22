package com.memo.meeting

/**
 * Every read path that can reveal an agenda item or a paper (test plan E03, "Leak-suite skeleton"; ADR-0061, ADR-0062),
 * for the leak suites: [PaperLeakIT] runs one probe per [Surface] for a person outside the rule, and its meta-test fails
 * when an endpoint of the meeting module is in neither [endpoints] nor [officersOnly]. E04 T2 added the recused-member probe
 * to every surface, and its own surfaces (declarations, registers). E04 T5 made it the recusal registry as well
 * ([RecusalLeakIT]): the channels beyond the meeting's endpoints ([Channel]), the forum module's endpoints ([forum]), and the
 * surfaces of later epics still to come ([pending]); its meta-test fails when a controller of [modules] is not registered.
 * E05 T5 added the alternate of a recused member to every probe of [PaperLeakIT], and its surfaces (invitations, replies,
 * the register, the quorum, the forecast and snapshots) to both suites. E06 T3 made its decisions placeholder real: the
 * decisions list and history (DECISIONS), recording, consent sections and notes (officers, with recusal columns). E06 T4 made
 * its resolutions placeholder real: the register, its check and one resolution (RESOLUTIONS), and the starting number. E06 T5
 * made the drafting part of its minutes placeholder real: the minutes page (MINUTES), making the draft and filling an item
 * again (officers, with recusal columns); the readers' copies stay a placeholder for T6. E06 T6 made the copies real
 * (MINUTES_COPIES: the reader's copy, the comments), opened the minutes page to the reviewer and the readers, and added the
 * review, the confirmation and the stamps to MINUTES and the circulation's steps and the preview "as" to the officers'. E06
 * T7 made its record placeholder real: the record, its versions and its check (MINUTES_COPIES). E07 T3 made its actions
 * placeholder real: one action, a meeting's actions and "My actions" (ACTIONS), raising, editing, discarding and issuing (officers,
 * with recusal columns or an officer probe). E07 T8 made its tracker placeholder real: the committee's tracker with its
 * totals and its export (TRACKER). E08 T2 made the register part of its placeholder real: the decision register's scope and
 * search (REGISTER), probed in both suites; its [modules] are the real ones (`com.memo.search` with the memo search endpoints
 * listed in [search], and `com.memo.analytics`, whose surface stays a placeholder for T4). E08 T3 added the register's
 * export to REGISTER, and its two files (XLSX and PDF, every cell and the PDF's text) to the EXPORTS channel.
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
        INVITATIONS("The invitation files and emails, \"Add to my calendar\" and the replies (E05 T2, T3): never an item"),
        QUORUM(
            "The attendance register, the quorum of the meeting and of an item, and the snapshots (E05 T4, T5): people and numbers, " +
                "never an item's content; no seats for an item the viewer is recused from, no override reason but for officers",
        ),
        DECISIONS(
            "The decisions of a meeting and an item's history (E06 T3): an item's decision when it is seen in full; for a recused " +
                "person who sees its line the outcome, text and conditions only (no vote, names or notes); nothing of other items",
        ),
        ACTIONS(
            "Action items (E07 T3): one action, a meeting's actions and My actions with their counts; a person recused from the " +
                "item neither sees nor counts its actions unless involved, and then without the item's hidden title or papers",
        ),
        TRACKER(
            "The committee's action tracker, its totals per colour and its XLSX export (E07 T8): rows by the one rule; a person " +
                "recused from an item neither sees, counts nor exports its actions unless involved; members do not export",
        ),
        RESOLUTIONS(
            "The committee's resolution register and its check, and one resolution (E06 T4): a row in full only when its item's " +
                "decision is seen in full, else its number, meeting and item number (\"restricted\"); one resolution as the decision",
        ),
        MINUTES(
            "The minutes page (E06 T5, T6): the committee's secretaries and forums.manage once held, the reviewer while it is with " +
                "them, the readers once circulated (404 to everyone else); stage, people and numbers only, never an item's text; a " +
                "secretary recused from any item gets it without the draft; the review, the confirmation and the stamps",
        ),
        MINUTES_COPIES(
            "A reader's copy of the draft minutes and the comments (E06 T6): the copy of their redaction set (ADR-0066: the recused " +
                "line for an item they declared an interest in, the heading or \"restricted\" for one they may not read); their own " +
                "comments, the officers everyone's but on items they are recused from; once approved (E06 T7), the record or the " +
                "reader's approved copy (a PDF/A record of the same set), its versions and its check",
        ),
        REGISTER(
            "The decision register across committees (E08 T2): its scope, and the search's rows, totals, facets and order; a decision " +
                "the viewer may not see in full is a restricted line (committee, meeting, item number, number and state) that no text, " +
                "outcome, memo type or exception filter matches and no outcome facet counts; nothing above clearance or outside scope. " +
                "Its export (E08 T3): the same rows as XLSX or PDF for officers and oversight (members 403), lines as " +
                "\"Restricted item\", the number check without the reason of a void the viewer may not see in full",
        ),
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
            "GET /api/v1/meetings/{meetingId}/calendar.ics" to Surface.INVITATIONS,
            // Replies (E05 T3): answers, notes and counts; never an item (an alternate's recusals are AlternateRecusalIT).
            "GET /api/v1/meetings/{meetingId}/rsvp/mine" to Surface.INVITATIONS,
            "PUT /api/v1/meetings/{meetingId}/rsvp/mine" to Surface.INVITATIONS,
            "GET /api/v1/meetings/{meetingId}/rsvps" to Surface.INVITATIONS,
            // Attendance and the live quorum (E05 T4, per item T5): people, states, times, item ids and numbers.
            "GET /api/v1/meetings/{meetingId}/attendance" to Surface.QUORUM,
            "GET /api/v1/meetings/{meetingId}/quorum" to Surface.QUORUM,
            // A decision's snapshot (E05 T5): officers and forum.read; the override's reason for officers not recused only.
            "GET /api/v1/meetings/{meetingId}/quorum/snapshots/{snapshotId}" to Surface.QUORUM,
            // Decisions (E06 T3): the list as each person may see it, and an item's history (officers and forum.read, seeing it in full).
            "GET /api/v1/meetings/{meetingId}/decisions" to Surface.DECISIONS,
            "GET /api/v1/meetings/{meetingId}/items/{itemId}/decision/entries" to Surface.DECISIONS,
            // Actions (E07 T3): by the one rule (ActionAccess), on the action's copied item columns with the recusal EXISTS.
            "GET /api/v1/actions/{actionId}" to Surface.ACTIONS,
            "GET /api/v1/meetings/{meetingId}/actions" to Surface.ACTIONS,
            "GET /api/v1/me/actions" to Surface.ACTIONS,
            // E06 T4: the register (officers, forums.manage, forum.read), its check, and one resolution (also its memo's readers).
            "GET /api/v1/forums/{forumId}/resolutions" to Surface.RESOLUTIONS,
            "GET /api/v1/forums/{forumId}/resolutions/check" to Surface.RESOLUTIONS,
            "GET /api/v1/resolutions/{resolutionId}" to Surface.RESOLUTIONS,
            // E06 T5: the minutes page (secretaries and forums.manage while drafting; 404 to everyone else).
            "GET /api/v1/meetings/{meetingId}/minutes" to Surface.MINUTES,
            // E06 T6: the reviewer's answer, the confirmation and the stamps (the chair), the reader's copy and the comments.
            "POST /api/v1/meetings/{meetingId}/minutes/review" to Surface.MINUTES,
            "POST /api/v1/meetings/{meetingId}/minutes/confirm" to Surface.MINUTES,
            "GET /api/v1/meetings/{meetingId}/minutes/approvals" to Surface.MINUTES,
            "POST /api/v1/meetings/{meetingId}/minutes/approvals/{approvalId}/check" to Surface.MINUTES,
            "GET /api/v1/meetings/{meetingId}/minutes/document" to Surface.MINUTES_COPIES,
            "GET /api/v1/meetings/{meetingId}/minutes/comments" to Surface.MINUTES_COPIES,
            "POST /api/v1/meetings/{meetingId}/minutes/comments" to Surface.MINUTES_COPIES,
            // E06 T7: the approved minutes' record (the reader's approved copy), its versions and its check.
            "GET /api/v1/meetings/{meetingId}/minutes/record" to Surface.MINUTES_COPIES,
            "GET /api/v1/meetings/{meetingId}/minutes/records" to Surface.MINUTES_COPIES,
            "POST /api/v1/meetings/{meetingId}/minutes/records/{version}/check" to Surface.MINUTES_COPIES,
            // E07 T4: the timeline, by the same rule.
            "GET /api/v1/actions/{actionId}/entries" to Surface.ACTIONS,
            // E07 T8: the committee's tracker and its export, by the same rule (the totals from the same predicate).
            "GET /api/v1/forums/{forumId}/actions" to Surface.TRACKER,
            "GET /api/v1/forums/{forumId}/actions/export" to Surface.TRACKER,
            // E08 T2: the register (officers and members of their committees, forums.manage and forum.read; 403 to others), by
            // the one rule in search_find_decisions with the viewer's recusals read in the request.
            "GET /api/v1/decision-register/scope" to Surface.REGISTER,
            "POST /api/v1/decision-register/search" to Surface.REGISTER,
            // E08 T3: its export, the same rows by the same rule (officers and oversight; members 403), its files in EXPORTS.
            "POST /api/v1/decision-register/export" to Surface.REGISTER,
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
                "GET /api/v1/meetings/{meetingId}/quorum/forecast" to
                    "the forecast from the replies (E05 T5): officers, numbers only (QUORUM probe)",
                "PUT /api/v1/meetings/{meetingId}/items/{itemId}/decision" to
                    "recording and correcting a decision (E06 T3): secretaries and forums.manage not recused (recusal columns)",
                "POST /api/v1/meetings/{meetingId}/sections/{sectionId}/decision" to
                    "a consent section in one action (E06 T3): secretaries not recused from any of its items (recusal columns)",
                "GET /api/v1/meetings/{meetingId}/items/{itemId}/notes" to
                    "the secretary's notes (E06 T3): secretaries not recused; nobody else knows of them (recusal columns)",
                "PUT /api/v1/meetings/{meetingId}/items/{itemId}/notes" to
                    "saving the notes (E06 T3): secretaries not recused (recusal columns)",
                "GET /api/v1/forums/{forumId}/decisions/to-be-ratified" to
                    "the committee's decisions to be ratified (E06 T3): secretaries; ids, numbers and outcomes only (officer probe)",
                "POST /api/v1/meetings/{meetingId}/actions" to
                    "raising an action (E07 T3): secretaries and forums.manage not recused from the item (recusal columns)",
                "POST /api/v1/meetings/{meetingId}/actions/issue" to
                    "issuing a meeting's drafts (E07 T3): secretaries; none of an item they are recused from (officer probe)",
                "POST /api/v1/forums/{forumId}/actions" to "a direct action (E07 T3): secretaries; no item (officer probe)",
                "PATCH /api/v1/actions/{actionId}" to "editing a draft (E07 T3): secretaries not recused (recusal columns)",
                "POST /api/v1/actions/{actionId}/discard" to "discarding a draft (E07 T3): secretaries not recused (recusal columns)",
                "POST /api/v1/actions/{actionId}/issue" to "issuing a draft (E07 T3): secretaries not recused (recusal columns)",
                "PUT /api/v1/forums/{forumId}/resolution-counters/{period}" to
                    "the starting resolution number (E06 T4): forums.manage with a step-up; no item at all (officer probe)",
                "POST /api/v1/meetings/{meetingId}/minutes/draft" to
                    "making the minutes' draft (E06 T5): secretaries not recused from any item; answered with the page (officer probe)",
                "POST /api/v1/meetings/{meetingId}/minutes/items/{itemId}/regenerate" to
                    "filling an item of the draft again (E06 T5): secretaries not recused from any item (recusal columns)",
                // E07 T4: progress, dates, owners and endings; every one finds the action by the one rule first, so a recused
                // viewer who is not involved gets 404, and a recused officer who is gets 403 RECUSED (ActionStateMachineTest).
                "POST /api/v1/actions/{actionId}/updates" to
                    "a progress update (E07 T4): the owner, contributors and secretaries not recused (recusal columns)",
                "POST /api/v1/actions/{actionId}/started" to
                    "I have started (E07 T4): the owner, or a secretary for them (recusal columns)",
                "POST /api/v1/actions/{actionId}/due-requests" to "asking for a new date (E07 T4): the owner (recusal columns)",
                "POST /api/v1/actions/{actionId}/due-requests/{requestId}/decide" to
                    "answering a date request (E07 T4): a secretary or the chair not recused (recusal columns)",
                "POST /api/v1/actions/{actionId}/due" to
                    "changing the date (E07 T4): secretaries not recused, or the owner (recusal columns)",
                "POST /api/v1/actions/{actionId}/rag" to
                    "overriding the colour (E07 T4): a secretary or the chair not recused (recusal columns)",
                "DELETE /api/v1/actions/{actionId}/rag" to "clearing the override (E07 T4): as overriding (recusal columns)",
                "POST /api/v1/actions/{actionId}/owner" to "a new owner (E07 T4): secretaries not recused (recusal columns)",
                "POST /api/v1/actions/{actionId}/cancel" to "cancelling (E07 T4): a secretary or the chair not recused (recusal columns)",
                "POST /api/v1/actions/{actionId}/reopen" to "reopening (E07 T4): secretaries not recused (recusal columns)",
                // E07 T5: closure; marking done and verifying find the action by the one rule first (404 to a recused viewer).
                "POST /api/v1/actions/{actionId}/done" to "marking as done (E07 T5): whoever holds the owner's task (recusal columns)",
                "POST /api/v1/actions/{actionId}/verify" to
                    "verifying (E07 T5): the verifier pool, never the owner, a contributor or the recused (recusal columns)",
                "POST /api/v1/meetings/{meetingId}/actions/close-agreed" to
                    "closing the actions agreed at a meeting (E07 T5): the verifiers; an unseen action is NOT_FOUND (officer probe)",
            ) +
            listOf(
                "POST /api/v1/meetings/{meetingId}/minutes/send-for-review",
                "POST /api/v1/meetings/{meetingId}/minutes/circulate",
                "PUT /api/v1/meetings/{meetingId}/minutes/comments-until",
                "POST /api/v1/meetings/{meetingId}/minutes/withdraw",
                "POST /api/v1/meetings/{meetingId}/minutes/end-circulation",
                "POST /api/v1/meetings/{meetingId}/minutes/comment-requests",
                "POST /api/v1/meetings/{meetingId}/minutes/comments/{commentId}/response",
            ).associateWith { "the minutes' circulation (E06 T6): secretaries and forums.manage, answered with the page (officer probe)" } +
            mapOf(
                "GET /api/v1/meetings/{meetingId}/minutes/preview" to
                    "another reader's copy (E06 T6): secretaries, their own recusals applying (officer probe)",
            )

    /** The modules whose controllers must all be registered here (test plan E04, "Meta-test"); later epics add theirs. */
    val modules: List<String> =
        listOf("com.memo.meeting", "com.memo.forum", "com.memo.minutes", "com.memo.actions", "com.memo.search", "com.memo.analytics")

    /**
     * The search module's memo search endpoints and why none of them is an item surface: `search_memo` holds memos, their
     * notes and their files only, read by the memo's own rule; meeting papers, their extracted text and decisions are never
     * indexed there (design E04 §1.7, ADR-0054). The [Channel.SEARCH] probe searches every canary of a recused item.
     */
    val search: Map<String, String> =
        listOf(
            "POST /api/v1/search",
            "POST /api/v1/search/suggest",
        ).associateWith { "memo search: memos by the memo's own rule; no paper, agenda item or decision is indexed (SEARCH probe)" } +
            listOf(
                "GET /api/v1/saved-searches",
                "POST /api/v1/saved-searches",
                "PUT /api/v1/saved-searches/{id}",
                "DELETE /api/v1/saved-searches/{id}",
            ).associateWith { "saved memo searches: their owner's criteria only, no result is stored" }

    /** Every endpoint registered here: the surfaces, the officers', the forum module's and the memo search's. */
    val registered: Set<String> get() = endpoints.keys + officersOnly.keys + forum.keys + search.keys

    /**
     * What reaches a person about an item beyond the meeting module's endpoints (test plan E04, "Surfaces"): each has a
     * recusal probe in [RecusalLeakIT].
     */
    enum class Channel(
        val what: String,
    ) {
        IN_APP("In-app notifications (`/me/notifications`): publication, changes, declarations, requests"),
        MAIL("Every email of the scenario (agenda, declarations, late papers, invitations and their calendar files), from Mailpit"),
        EXPORTS(
            "The registers' and the action tracker's XLSX exports (their surfaces' probes), the decision register's XLSX and PDF " +
                "(every cell and the PDF's text, E08 T3) and the meeting's calendar file",
        ),
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
                "GET /api/v1/admin/forum-types/action-definitions",
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
     * E05's attendance and quorum (E05 T4, T5): their reads are probed in the QUORUM surface (the replies and the calendar
     * file in INVITATIONS) and their changes as officer probes in [RecusalLeakIT]; who counts for an item a holder is recused
     * from is E05's `ItemQuorumProperties`, `PresenceBuilderProperties` and `QuorumSnapshotsIT`.
     */
    val pending: List<Pending> =
        listOf(
            Pending(
                "E08",
                "Meeting analytics and the attendance export (T4): measures over the rows the viewer sees in full, with live recusals " +
                    "(the differential probe)",
                listOf("/api/v1/analytics"),
            ),
        )
}
