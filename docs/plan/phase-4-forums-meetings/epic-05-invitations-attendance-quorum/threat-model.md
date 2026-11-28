# E05 Invitations, attendance and quorum — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter-level where the checklist groups them). Builds on the E03 and E04 threat models and ADR-0030's mail controls.

## Data flows
1. A meeting change (schedule, reschedule, place or link, cancel) or an audience change marks the invitation due; the reconciler builds one iCalendar file per person and queues it through the notification module.
2. The outbox job sends the iMIP message through the tenant's relay (or the platform's) to the person's mailbox, where their mail and calendar clients read it.
3. Clients may send `METHOD:REPLY` mails back to the tenant's sending address; memo does not read them.
4. A member, guest or alternate answers "Will you attend?" in the app; a secretary records an answer given by email or phone; a member asks their alternate to attend.
5. A member downloads "Add to my calendar".
6. During the meeting the secretary records attendance events and the item in progress; the live quorum is evaluated and its changes recorded.
7. E06 takes a quorum snapshot with each decision, or an override with a reason.
8. The sweep sends reply and meeting reminders and the quorum-at-risk warning.
9. For the recorded real-client run (T9), a dedicated test tenant on dev sends through an SMTP relay the owner provides.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone sends fake "memo" invitations to directors (phishing that looks like a committee meeting) | 2 | Out of our control at the recipient; reduced by: invites come only from the tenant's own relay and from address (SPF, DKIM and DMARC in the runbook); the invite holds no credential and its link opens the meeting page behind sign-in and MFA; directors are told in the invite to answer in memo, never by email | V12, V14 |
| S2 | A forged `REPLY` mail claims a director accepted or declined | 3 | Replies are never read (ADR-0063); answers come only from the signed-in user or a secretary's recorded entry | V8 |
| S3 | A secretary records an answer in a member's name that the member never gave | 4 | On-behalf answers need `how` and are recorded with `recordedBy`; the member is told at once; the history is append-only; the member can change it | V8, V16 |
| S4 | A link scanner or mail gateway "clicks" a reply link and records an answer | 2, 4 | No answer links in mail; the email links to the meeting page; answering is a signed-in POST with CSRF protection (RFC 8058 §1 on fetched links) | V3, V4 |
| T1 | Attendance is changed after the meeting to make a decision look quorate | 6, 7 | Events are append-only; a correction is a VOIDED event with a reason, recorded as a correction, audited; the snapshot stores its inputs and their SHA-256 and never changes; `drifted` shows a later correction next to the decision | V16, V2 |
| T2 | Two secretaries record the same person at once, or a double click records two arrivals | 6 | If-Match on the person's row; guarded updates; idempotency keys; ARRIVED while PRESENT is refused | V2 |
| T3 | Injection into the calendar file or the mail headers through a meeting title, place, joining details, a committee or display name (CRLF to add properties, attendees or headers) | 1, 2 | ical4j builds properties from typed values; the writer strips control characters, escapes TEXT and folds lines; subjects take no parameters (ADR-0030); Angus Mail encodes headers; Jazzer on the writer's inputs nightly; golden files | V1, V2 |
| T4 | A malformed invite corrupts a director's calendar or is rejected silently | 1, 2 | Strict ical4j validation before queueing (the job fails instead), the iTIP rules for the method, a second parser (biweekly) in CI, golden files, the recorded run | V1 |
| T5 | The invite's UID or sequence lets a third party update or cancel the event in a director's calendar | 2 | The UID is a random UUID never shown in the app; but any iMIP sender who learns it could forge an update (RFC 6047 §2.2, unsigned mail). Residual; S/MIME signing is a follow-up if a customer asks | V12 |
| R1 | A director denies having sent apologies, or the secretary denies recording them | 4, 6 | Each answer and event records who, how and when, append-only, audited; the member is told when someone else records for them | V16 |
| R2 | A secretary denies overriding a missing quorum | 7 | The override is audited with the actor and a hash of the reason, stored on the snapshot, marked "to be ratified" and told to the chair and the other secretaries | V16 |
| I1 | The invite leaks the agenda, an item title, a paper or the classification to a mailbox, a shared calendar or an assistant | 1, 2, 5 | The writer has no access to agenda data; its input is the meeting's title, place, link and details; canary strings from items, papers and declarations are checked against every generated file and email; a neutral title per committee type; `CLASS:PRIVATE` with it once the run confirms clients honour it | V14 |
| I2 | Directors' email addresses are disclosed to guests or other invitees | 1, 2 | One message per person naming only them as ATTENDEE; no CC or BCC lists | V14 |
| I3 | A forwarded invite gives an outsider the join link and joining details | 2 | Residual by nature: the link is in the invite so directors can join from their calendar. The runbook advises against passcodes in joining details and recommends waiting rooms; the meeting page (behind sign-in) holds the full details | V14 |
| I4 | A recused member's alternate reads the item's papers | 4 | The alternate inherits every recusal of the member they stand for (E04's anti-join extended); the leak suite gains an alternate column | V8 |
| I5 | A recused officer learns who else is recused from an item through the quorum view or a snapshot | 6, 7 | For an item the viewer is recused from, quorum and snapshots return counts only, never names (E04 matrix) | V8, V14 |
| I6 | Members learn other members' private notes to the secretary or who sent apologies | 4 | Members see their own answer and counts; notes and names only for officers | V8 |
| I7 | The "Add to my calendar" file is fetched by someone outside the audience | 5 | The same audience check as the meeting page; 404 otherwise; no-store | V8 |
| I8 | The SMTP password for the recorded run, or the owner's mailbox addresses, end up in the repo, logs, Ansible variables, the chat or a test fixture | 9 | The owner types the password into the test tenant's Mail settings (encrypted with the tenant's data key, never returned or logged, ADR-0030); mailbox addresses live only as users of that tenant; the log names them as "an Outlook mailbox" and "a Gmail mailbox"; the relay is deleted after the run | V13, V14 |
| D1 | Rapid edits flood directors with updates, or a secretary resends repeatedly | 1 | Debounced reconciler (one update per two minutes of changes); resend at most once per person per 10 minutes, rate-limited per meeting | V2 |
| D2 | A large audience or a slow relay blocks the meeting change | 1, 2 | Invitations are queued after the change commits by a background job; the change returns at once (AGENTS §4) | V2 |
| D3 | Live quorum polling loads the server during many meetings | 6 | 15-second polling only on the attendance and quorum views of IN_PROGRESS meetings, conditional requests with ETags, a pure in-memory evaluation | V2 |
| E1 | A member records attendance or an override | 6, 7 | Only secretaries and `forums.manage` record; the override also needs the type's policy and no recusal from the item; matrix tests | V8 |
| E2 | An alternate counts twice, or for a role or an independence they do not have, making a decision look quorate | 6, 7 | ADR-0064: one seat per person (`ALREADY_COUNTED`), number only, never required roles or composition; property tests and PIT | V2 business logic |
| E3 | Quorum is evaluated against a policy changed after the meeting was scheduled | 6, 7 | The type version is pinned per meeting (ADR-0060); the snapshot stores the version and the rule | V2 business logic |
| E4 | A crafted `userId`, `itemId` or `forMemberId` from another meeting or tenant attaches attendance or a reply where the actor has no access | 4, 6 | Checked against the meeting's audience, roster and published items; composite foreign keys; RLS; cross-tenant tests | V8 |

## Residual risks
- **Calendar Accept and Decline:** a director who clicks Accept in their calendar has not answered in memo. The invite's first line, the reminder and the secretary's panel cover it.
- **Mail systems can hold invites back** (Google's "known senders only"); "Add to my calendar" and the runbook cover it, and the recorded run measures it.
- **Unsigned iMIP:** anyone who learns a meeting's UID could send a forged update or cancellation to a director (RFC 6047 §2.2). The UID is random and only in the invite itself; S/MIME is a follow-up.
- **Forwarded invites** carry the join link (I3).
- **The room:** attendance records what the secretary sees; it cannot prove who was on a call.

## Reviewed at phase close
Reviewed at phase close on 2026-11-28 (Phase 4 E08 T11): **every mitigation holds as built**. T4 and I8 cannot be closed until the recorded run in real calendar clients (T9), which is carried for the owner.
- **Found and fixed:**
  - T4 (E05 T10): a meeting starting or ending in the second occurrence of a repeated hour was written as an ambiguous local time. Such meetings are now written in UTC (`DTSTART:…Z`), with property tests across zones (`InviteWriterProperties`).
  - Two biweekly reading limits (Africa/Cairo, Australia/Adelaide) are handled in `InviteChecks`, with the reason given.
- **Changed by the build:**
  - E2 (ADR-0064 amended): a seat present through its alternate counts towards the number and towards a composition majority's denominator, never towards a required role or attribute (`QuorumProperties`, `PresenceBuilderProperties`, `ItemQuorumProperties`).
  - I4: E06 and E07 extend the alternate's inherited recusal to decisions, minutes copies and actions. E08 extends it to the register and analytics (`AlternateRecusalIT`, `RecusalLeakIT`, `AnalyticsLeakIT`).
- **Evidence:**
  - S2: no reader of `METHOD:REPLY` exists. S3: `HOW_REQUIRED` (`RepliesIT`). S4: no answer links in mail (`InvitationContentIT`).
  - T1: no DELETE or UPDATE on the answer history, the attendance events, the quorum change log or the snapshots; snapshots store their inputs' SHA-256 and report `drifted` (`QuorumSnapshotsIT`). T2: `RepliesConcurrencyIT`, `AttendanceIT`.
  - T3, T4: `InviteWriterProperties`, `InviteValidationTest`, `InviteCrossParserTest`, `InviteWriterGoldenTest`, Jazzer `InviteWriterFuzz`. T5: a random UID per meeting and a sequence kept by the server (`InvitationSequenceProperties`).
  - R1, R2: `meeting.rsvp.*`, `meeting.attendance.*`, `meeting.quorum.overridden` (`AuditActionCoverageTest`).
  - I1, I2: `InvitationContentIT`, `InvitationMailIT`, `MailCalendarPartIT`. I5: numbers only for an item the viewer is recused from (`PaperLeakIT` QUORUM). I6: `RepliesIT`. I7: the audience check on `calendar.ics` (`PaperLeakIT` INVITATIONS).
  - I8: no relay password or mailbox address is in the repository. The run has not happened.
  - D1: `RESEND_TOO_SOON`, and the reconciler's one update per two minutes (`InvitationsIT`). D2: sending after commit, in the background. D3: polling every 15 s only while the meeting runs.
  - E1, E4: matrices `p4-e05-attendance.yaml` and `p4-e05-quorum.yaml`, `AttendanceCrossTenantIT`. E3: the pinned type version.
- **Residual risks:** all five confirmed. Unsigned iMIP and forwarded invitations are listed for the pen test as accepted (`docs/security/pen-test-scope.md`, area 5).
- **Carried for the owner:** T9, the recorded run in the owner's Outlook and Gmail mailboxes through an SMTP relay. Until it happens, the golden files and the RFC validation are the evidence.
