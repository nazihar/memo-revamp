# E05 Invitations, attendance and quorum — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. Phase-4 special tests (`§12`): "quorum rules" (property tests over rule × attendance × recusal; live and recorded agree) and "iCalendar invites accepted by common clients" (RFC 5545 validation of every generated file in CI, golden files for request, update and cancel, and a recorded run in Outlook, Gmail and Apple Calendar). CI stages: PR (unit, property, integration, matrix, leak suite, golden files, smoke E2E), nightly (PIT, Jazzer, browsers, visual, pseudo-locale), weekly (load).

## Scope and risks
- **Risks covered:**
  - an invite that a client rejects, duplicates, fails to update or fails to remove (overview risk R3);
  - an invite, email or reminder that carries agenda, item, paper or declaration content;
  - injection into the calendar file or mail headers;
  - sequences going backwards for a person, so an old version wins in their calendar;
  - a flood of updates;
  - an answer recorded in someone's name without trace, or lost between a member and a secretary;
  - an alternate counting twice, for a role, for an independence or for an item their member is recused from;
  - a wrong quorum, or a recorded quorum that differs from what was shown;
  - attendance changed after a decision without trace;
  - a recused member, or their alternate, learning about the item through the new surfaces.
- **Seed:**
  - the E03 and E04 seeds: the BRC meeting with a published agenda, the recused director on item 5, the MCC meeting with the credit proposal;
  - BRC members with roles and attributes: a chair, a vice-chair, five members (three `independent`), `secretary@`; one member with an alternate user; a guest;
  - the BRC type's rule "a majority of voting members, including the chair or a vice-chair, at least 2 independent", `alternatesCount` true, `remoteCounts` true, `recusedCount` false; a second type with `quorumOverride` WITH_REASON;
  - **canaries:** item titles, descriptions, paper titles, extracted text and declaration natures carrying `CANARY-…` strings (the E04 set), checked in every generated `.ics`, email body and in-app text.

## Tests per slice
| Task | Layer | Tests |
|---|---|---|
| T1 | Unit (golden files) | `InviteWriterGoldenTest`: byte-for-byte against `backend/src/test/resources/ical/` after normalising `DTSTAMP`: `request-asia-dhaka.ics` (no DST), `request-europe-london.ics` (a meeting in the week of the October change), `update-time.ics` (SEQUENCE 1), `update-place-link.ics`, `cancel-meeting.ics` (STATUS:CANCELLED), `uninvite.ics` (no STATUS), `publish-download.ics` (no ATTENDEE), `request-neutral-title.ics`, `request-arabic.ics` (an Arabic committee name and description, UTF-8 folded inside characters correctly) |
| T1 | Unit (validation) | `InviteValidationTest`: every golden file and every file generated in any test passes ical4j strict validation and its iTIP rules for the method (REQUEST: ATTENDEE, ORGANIZER, DTSTART, UID, SEQUENCE, DTSTAMP; CANCEL: as RFC 5546 §3.2.5), has a VTIMEZONE for each TZID, CRLF line ends and no line over 75 octets; a test-only hook collects every generated file in the run so none escapes validation |
| T1 | Unit (second parser) | `InviteCrossParserTest`: every golden and generated file parses in biweekly 0.6.8 to the same METHOD, UID, SEQUENCE, DTSTART, DTEND, TZID, STATUS and ATTENDEE |
| T1 | Unit + property | `InviteWriterProperties`: random titles, places, joining details and names (any Unicode, control characters, `;`, `,`, `\`, CRLF, 2,000-character values) never produce an extra property line or component, round-trip through the parser to the cleaned text, and fold within 75 octets; `VTimezoneProperties`: for random zones and dates, the VTIMEZONE built from `ZoneRules` gives the same UTC instant for DTSTART as `java.time` |
| T1 | Unit | `NoNetworkTest`: building and validating an invite with outbound connections refused (a test socket factory that throws) succeeds; `ical4j.properties` disables timezone updates |
| T1 | Integration | `MailCalendarPartIT` (against a real Mailpit container, as `MailDeliveryIT` does, reading the raw message through Mailpit's API): the delivered MIME structure (`multipart/mixed` → `multipart/alternative` with `text/plain`, `text/html`, `text/calendar; method=REQUEST; charset=UTF-8`, plus `invite.ics` as `application/ics`), the method parameter equals METHOD, the subject has no parameter, the calendar part is encrypted at rest and cleared after sending, a permanent failure keeps nothing |
| T1 | Integration | `ForumTypeMeetingPolicyIT`: the `meetings` policy's checks (`NEUTRAL_TITLE_REQUIRED`, ranges), presets, the changes view and maker-checker; null reads as the defaults |
| T2 | Unit + property | `InvitationSequenceProperties`: random sequences of schedule, reschedule, patch (each field), audience in and out, resend and cancel: per person, the sequences sent never decrease, increase on every change and every CANCEL, a resend repeats the last, the UID never changes, nothing is sent after a CANCEL with STATUS:CANCELLED, and the final state each person holds equals the meeting's |
| T2 | Integration | `InvitationsIT`: the invitation at `schedule`; debounce (three reschedules → one update per person); coalescing after a retry; cancel at once with no later update; audience changes (term ended → uninvite; guest added → invitation; re-appointed → re-invite with a higher sequence); drafts and held meetings send nothing; patches of `kind` and `classificationId` send nothing; the invitations panel with outbox statuses; resend and `RESEND_TOO_SOON`; "Add to my calendar" (PUBLISH, 404 for drafts and cancelled meetings); a member with invitation emails switched off gets the in-app item only; audit |
| T2 | Integration | `InvitationContentIT`: for every message kind, the `.ics`, the email body and the in-app text contain no canary string, no item title, no classification and no cancellation reason text, and name only the recipient as ATTENDEE |
| T2 | Integration (failure) | `InvitationFailureIT`: a writer validation failure fails the task, sends nothing, audits `meeting.invitation.validation_failed` and is retried by the sweep's safety net after the fix; a relay outage leaves PENDING outbox rows shown as "not yet delivered" |
| T2 | Authorisation matrix | `p4-e05-invitations.yaml`: the panel, resend and download × secretary (own forum and another), chair, `forums.manage`, member, alternate, guest, item attendee, former member, `auditor@` with `forum.read`, `user@` |
| T2 | Persistence/RLS | `MeetingCrossTenantIT` gains the invitation tables and endpoints; triggers refuse a changed UID and a decreasing sequence; no DELETE; no UPDATE on messages |
| T3 | Unit | `RsvpRulesTest`: answers by meeting mode (`MODE_NOT_OFFERED`), the window by status, stale by times, `how` required on behalf, note lengths; `AlternateRulesTest`: `ALTERNATE_NONE`, `ALTERNATE_IS_MEMBER`, `ALTERNATE_BUSY` |
| T3 | Integration | `RepliesIT`: answer, change, stale after reschedule and re-answer; on behalf with each `how` and the member told; counts for members, details for officers; apologies with the alternate (audience row, invitation, notice) and release; `/me/meetings` and the meeting with `myAnswer`; history rows; audit with the note's hash |
| T3 | Integration (leak) | `AlternateRecusalIT`: an alternate standing in for a member recused from item 5 sees item 5 as the member would (title line only), gets 404 on its papers, and is listed in no change flag or notification about it; the alternate's own declarations apply too |
| T3 | Concurrency | `RepliesConcurrencyIT`: a member and a secretary answering at once → one 412; the same idempotency key twice → one event |
| T3 | Authorisation matrix | `p4-e05-replies.yaml`: answering for oneself, on behalf, reading answers and counts, across the matrix's columns |
| T4 | Unit (state machine) | `AttendanceStateTest`: every allowed and forbidden event for each person state and each meeting status (design §1.6), including voiding, the HELD correction window and FROZEN after MINUTES_APPROVED and on CANCELLED |
| T4 | Unit + property | `PresenceBuilderProperties` and the extended `QuorumProperties` (ADR-0057's suite): for random rules (counts, basis, thresholds, minimum, required sets, composition with majority, the three switches), rosters (roles, voting, attributes, alternates) and event sequences: **monotonic** (an arrival never makes a quorate meeting not quorate; a departure, a stepping out or a recusal never makes a not-quorate one quorate); **alternates** never satisfy a required set or a composition count, count once per person, and only while their member is absent; **remote** counted only with `remoteCounts`; the evaluation at *t* from the events equals the evaluation from the derived current rows when *t* is now |
| T4 | Integration | `AttendanceIT`: the register opened at `start` with RSVP apologies; bulk arrival; arrivals, mode changes, departures and returns with times and item labels; joined-late and left-early flags; `ALREADY_COUNTED`; `TIME_OUT_OF_RANGE`; the item in progress; corrections after HELD with reasons; `MeetingAttendance` for E06; audit |
| T4 | Integration | `LiveQuorumIT`: the meeting-level result after each event; lost and restored written once per flip with the item in progress, audited, and told in-app to the chair and secretaries only |
| T4 | Unit (PIT nightly) | `PresenceBuilder`, `Quorum` (ADR-0057's PIT target extended) |
| T4 | Authorisation matrix | `p4-e05-attendance.yaml`: recording, voiding, the item in progress, reading the register and the quorum by column |
| T5 | Unit + property | `ItemQuorumProperties`: for random agendas, declarations (RECUSED, AWAITING_RULING, TAKING_PART, WITHDRAWN), alternates and stepping out: with `recusedCount` false a recused member, and a recused member's alternate, never count for that item; with `recusedCount` true the member counts and the alternate still does not stand in; a TAKING_PART or WITHDRAWN declaration changes nothing; **live and recorded agree:** `take` at *t* stores exactly the result of `GET …/quorum?itemId=&at=t`, and the stored inputs re-evaluate to the stored result and SHA-256 |
| T5 | Integration | `QuorumSnapshotsIT`: `take` inside a transaction (as E06 will); `NOT_QUORATE` with unmet conditions; the override allowed only by the type's policy (`OVERRIDE_NOT_ALLOWED`), only by a secretary not recused (403 `RECUSED`), with a reason, stored as to be ratified, audited and told; `drifted` after a correction; a recused officer sees counts only |
| T5 | Integration | `QuorumForecastIT`: meeting and per-item forecasts from answers (stale counted as no answer), alternates asked, and recusals; agreement with E04's planning warning when everyone says yes |
| T5 | Leak suite | `RecusalLeakIT` (E04) gains the E05 surfaces: the `.ics`, the invitation, update, cancel and reminder emails, the in-app items, the replies and counts, the register, the per-item quorum, the forecast and snapshots; columns recused member, awaiting ruling, and **alternate of a recused member**; canary checks on every response |
| T6 | Integration | `MeetingSweepIT` (test clock) gains: the reply reminder on the deadline day from 09:00 in the meeting's zone to those without an answer or with a stale one, once; the meeting reminder the working day before, merged with E04's declarations line, and E04's separate reminder not sent to the same person that hour; the quorum-at-risk warning once per meeting and sequence; the safety net rescheduling a stuck invitation; per tenant with the context set; on a business calendar with a holiday |
| T6 | Performance | `InvitationVolumeIT` (weekly `load`): the budgets of design §8 on the volume set; `EXPLAIN` for the panel, the register, `/me/meetings` with answers and the sweep's window |
| T7 | Web unit | `replies.test.tsx`: the three answers by meeting mode, the alternate option only for a seat with one, the stale notice, changing an answer, the counts; the secretary's panel with delivery states, record on behalf (how required), resend refusals in words; "Add to my calendar" |
| T7 | Accessibility | axe on the meeting page's reply card and the panel in English and Arabic; 44 px targets; the answer buttons as a radio group with a visible label |
| T8 | Web unit | `attendance.test.tsx`: the register's rows and states, bulk arrival, arrival and departure dialogs with time and item, alternates, "Now: item n", stepped out, voiding with a reason; the quorum banner's sentences (each unmet kind) and the per-item view; the override dialog; the Meetings tab of the committee type with maker-checker states |
| T8 | Accessibility | axe on the register and the quorum views; keyboard path through the register; live region announcing "Quorum lost" politely |
| T9 | Manual (recorded run) | The checklist below, in the clients the owner provides |
| T10 | End-to-end | `meeting-replies.spec` (director answers, changes to apologies with the alternate; secretary records an answer by phone; the panel shows both) and `meeting-attendance.spec` (secretary starts the meeting, marks arrivals, moves through items, records a departure that loses quorum, restores it, and sees the per-item view); both in English, `meeting-replies.spec` also in Arabic |
| T10 | Manual | Keyboard and screen-reader pass over flows V and X (NVDA or VoiceOver), recorded in `ux-review.md` |

## Recorded real-client run (T9)
Run once on dev in the dedicated test tenant (`tasks.md`, "Owner input"). For each client the owner marks what they see; "Expected" is what the design intends. Any difference is fixed (new golden file) or recorded in the runbook as known client behaviour.

| Step | Message | Outlook on the web | Outlook for Windows (classic or new) | Gmail and Google Calendar | Apple Calendar (optional) |
|---|---|---|---|---|---|
| 1 | Invitation (REQUEST, seq 0) | Expected: appears as a meeting request with the committee's name, time in the mailbox's zone, place; event on the calendar (tentative or on acceptance); no "response requested" | Same | Expected: invitation card or "Add to calendar"; event added per the "Add invitations" setting (record which) | Expected: invitation in Mail or Calendar's inbox |
| 2 | Update: new time (REQUEST, seq 1) | Expected: the same event moves; no duplicate | Same | Expected: the same event moves; no duplicate (record whether Gmail's "Add to calendar" had been used in step 1) | Same |
| 3 | Update: new place and join link (REQUEST, seq 2) | Expected: place and description updated on the same event | Same | Same | Same |
| 4 | Uninvite this person (CANCEL without STATUS, seq 3) | Expected: the event is removed or marked cancelled | Same | Same | Same |
| 5 | Re-invite (REQUEST, seq 4) | Expected: the event is back, once | Same | Same | Same |
| 6 | Meeting cancelled (CANCEL, STATUS:CANCELLED, seq 5) | Expected: the event is removed or marked cancelled, with the cancellation email | Same | Same | Same |
| 7 | "Add to my calendar" download (PUBLISH) for a new meeting | Expected: opens as an appointment | Same | Expected: imports (record the UID kept or not) | Expected: imports |
| 8 | Neutral title and `CLASS:PRIVATE` | Expected: title "Committee meeting"; marked private | Same | Expected: title shown; private flag ignored or honoured (record) | Record |
| 9 | The reply buttons, if shown, are clicked once | Expected: no reply sent ("no response requested"); nothing changes in memo | Same | Record what Gmail sends; nothing changes in memo | Record |

For each step also record: time zone shown correctly (the mailbox in a zone other than the meeting's if possible), the sender and organiser shown, and whether the message reached the inbox or spam.

## Phase-4 special tests covered here
| Special test (`TESTING_STRATEGY §12`) | Evidence |
|---|---|
| Quorum rules: property tests over rule × attendance × recusal; live and recorded agree | `QuorumProperties` (extended), `PresenceBuilderProperties`, `ItemQuorumProperties`, `QuorumSnapshotsIT`, PIT nightly |
| iCalendar invites accepted by common clients | `InviteValidationTest` (RFC 5545 and iTIP validation of every generated file), `InviteWriterGoldenTest` (request, update, cancel, uninvite, publish), `InviteCrossParserTest`, the recorded run above (or its carried status) |

Also from `TESTING_STRATEGY.md`: the state-machine tests for attendance (and the invitee states through `InvitationSequenceProperties`); **Jazzer nightly** on the writer's inputs (`InviteWriterFuzz`: titles, places, details and names into a full write-validate-parse cycle; no exception other than a refused input, no extra property, no line over 75 octets) and on the `meetings` policy JSON reader; PIT on quorum evaluation.

## CI stages
- **PR:** unit, property (default tries), golden files, validation, cross-parser, integration, matrix, leak suite, migrations on empty and seeded databases, the two journeys' smoke paths.
- **Nightly:** PIT, Jazzer (`InviteWriterFuzz`, the policy reader), property tests with 10× tries, browsers, visual, pseudo-locale.
- **Weekly `load`:** `InvitationVolumeIT`.
- **Once, by hand:** the recorded real-client run (T9).
