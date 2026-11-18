# E05 Invitations, attendance and quorum — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **The iCalendar writer and the mail calendar part:**<br>• spike: ical4j 4.4.0 under Java 25 with `timezone.update.enabled=false`, strict validation and the iTIP rules; the VTIMEZONE built from `ZoneRules`; biweekly 0.6.8 as a test-only parser; the dependency register rows (ical4j, threeten-extra, biweekly);<br>• `InviteWriter` (REQUEST, CANCEL for the meeting, CANCEL to uninvite, PUBLISH) with input hygiene and the golden files;<br>• `notification/V4` (`calendar_enc`, `calendar_method`), `CalendarPart`, `Mail.enqueue(…, calendar)`, `Notifications.notify(…, calendar): NotificationResult`, `MailStatuses`, the `CALENDAR` category, the iMIP MIME layout in `MailDelivery`;<br>• `forum/V6` and the committee type's `meetings` policy (checks, presets, changes view, maker-checker) | L | E03 T1 merged |
| T2 | **Invitations:**<br>• `meeting/V5`; the invitation created at `schedule`; the sequence rules on reschedule, patch and cancel; the audience hook;<br>• the `meeting-invitations` reconciler (debounce, coalescing, uninvite, cancel, per-person sequences);<br>• the notification kinds with en/ar texts; `MEETING_CANCELLED` gains the CANCEL part for invitees;<br>• the invitations panel API, resend with its limits, "Add to my calendar";<br>• audit; the matrix `p4-e05-invitations.yaml`; cross-tenant tests | L | T1, E03 T3 merged (`MeetingChanged`) |
| T3 | **Replies and alternates:**<br>• `meeting/V6`; answers for oneself and on behalf; stale answers after a reschedule; counts and notes by viewer;<br>• alternates: the ALTERNATE audience row, its invitation and release, its refusals; the alternate inherits the member's recusals (E04's anti-join and `Recusals`);<br>• the RSVP fields on `/me/meetings` and the meeting;<br>• audit; the matrix `p4-e05-replies.yaml` | M | T2, E04 T2 merged (for the recusal inheritance) |
| T4 | **Attendance and live meeting quorum:**<br>• `meeting/V7`; the register opened at `start`; events, corrections and their windows; arrivals in bulk; the item in progress; joined-late and left-early flags;<br>• `PresenceBuilder` and the ADR-0064 amendment to `Quorum.evaluate` (alternates for the number only), with ADR-0057's property tests extended;<br>• live meeting-level quorum, its change log and the in-app lost and restored notices;<br>• `MeetingAttendance` for E06; audit; the matrix `p4-e05-attendance.yaml` | L | T3 |
| T5 | **Per-item quorum and snapshots:**<br>• the per-item evaluation with `Recusals` (and alternates of recused members), stepped-out people and `recusedCount`;<br>• the forecast per meeting and item from the replies;<br>• `QuorumSnapshots` (`take`, `find`, `drifted`), `NOT_QUORATE`, the override with the type's policy and its notices;<br>• the property suite (rule × attendance × recusal × alternates; live equals recorded) and PIT;<br>• the leak suite's E05 surfaces (invites, emails, quorum views, snapshots, the register) with recused-member and alternate columns | M | T4, E04 T2 merged |
| T6 | **Reminders and volumes:**<br>• the sweep's reply reminder, meeting reminder (merged with E04's declarations line) and quorum-at-risk warning; the reconciler safety net;<br>• the volume generator (§8 of the design); `EXPLAIN` and the performance ITs | M | T5, E03 T5 merged |
| T7 | **Web, invitations and replies** (flows V and W; delegated, no owner review):<br>• "Will you attend?" on the meeting page and in "My meetings", apologies with the alternate, the stale notice, "Add to my calendar";<br>• the secretary's invitations and replies panel, record on behalf, resend, the forecast;<br>• en/ar; 44 px targets | M | T3, E03 T8 merged |
| T8 | **Web, attendance and quorum** (flows X, Y and Z; delegated):<br>• the attendance register with bulk arrival, arrivals and departures, alternates, "Now: item n", stepped out, corrections;<br>• the live quorum banner and per-item view, quorum lost, the members' summary, the override dialog component for E06;<br>• the committee type's Meetings tab and the quorum sentence's alternates line;<br>• en/ar | L | T4, T5, E01 T4 (type editor) |
| T9 | **The recorded run in real clients** (needs the owner, Phase 4 Q5 (a); see "Owner input" below):<br>• a dedicated test tenant on dev with the owner's mailboxes as users, a committee and a meeting;<br>• send, in order: the invitation, an update (new time), an update (new place and link), an uninvite to one person, a re-invite, the cancellation; plus "Add to my calendar";<br>• the owner records what each client shows at each step (the checklist in `test-plan.md`) and, where something looks wrong, saves the message source ("Show original" in Gmail, "View message source" in Outlook); the agent compares it with the generated file, kept outside the repository with the addresses removed;<br>• fixes for any quirk found (with new golden files), the results in the log and test plan; the relay removed afterwards | S (agent) + about 30 minutes of the owner's time | T2, T3 deployed to dev |
| T10 | **Close:**<br>• demo seed: replies for the seeded BRC meeting (two apologies, one with the alternate standing in, one answer recorded by the secretary "by phone", one stale after a reschedule), a held MCC meeting with attendance (a late arrival, an early departure, a member stepping out for the recused item), quorum lost and restored once, and a snapshot for E06 to use;<br>• journeys `meeting-replies.spec` and `meeting-attendance.spec`;<br>• the manual keyboard and screen-reader pass over flows V and X;<br>• runbook `meeting-invitations.md` (the tenant relay, SPF, DKIM and DMARC, the sending address and replies to it, Google's invitation setting, troubleshooting a missing or duplicated event, the reconciler and its safety net) and a "Attendance and quorum" section in `meetings-and-agendas.md`; ASVS rows; log and STATUS | M | all |

**Order:**
- T1 → T2 → T3 → T4 → T5 → T6 for the backend. T1 can start now (it needs only E03 T1). T2 needs E03 T3's `MeetingChanged`; T3 and T5 need E04 T2's `Recusals` and `DeclarationExclusions`.
- T7 and T8 follow their backend tasks. **The owner delegated these screens** (Phase 4 Q1 sends only the E01, E02/E03 and E06 screens to the owner), so web tasks do not wait for a review.
- T9 as soon as T2 and T3 are on dev; it does not block T4–T8. If the owner has no mailboxes then, it is carried (Phase 4 Q5) and T10 closes without it.
- T10 last.
- Each task is its own PR, merged when CI is green (standing go-ahead, 2026-11-17).

**In parallel:** T1 beside E03 T2–T5 and E04; T6 beside T7 once T5 is merged.

## Owner input: the recorded real-client run (T9)
This is the one real-world input E05 needs (Phase 4 Q5 answer (a)). Nothing is needed before T2 and T3 are on dev; the agent will ask at that point.

**What the owner provides:**
1. **An Outlook mailbox:** Microsoft 365 (a work or test tenant) preferred, or Outlook.com. The owner needs to look at it in **Outlook on the web** and, if available, in **classic Outlook for Windows** (or new Outlook). Tell the agent which of these you will use.
2. **A Gmail mailbox:** a personal `gmail.com` account or Google Workspace, with Google Calendar. Tell the agent its "Add invitations to my calendar" setting (Google Calendar → Settings → Event settings); leave it as it is.
3. **Optional: Apple Calendar** on a Mac, iPhone or iPad, reading one of the two mailboxes (or an iCloud address as a third mailbox).
4. **An SMTP relay the dev stack may use for one run:** host, port, TLS mode (STARTTLS or TLS), a user name and a password (an app password where the provider requires one), and a **from address on a domain that publishes SPF and DKIM** (for example `memo-test@` your company domain, sent through that domain's mail provider), allowed to send to outside addresses. A personal Gmail or Outlook.com account is not a good relay: the from address must match the account, and providers may block automated sending.

**How it is stored (never in the repo):**
- The agent creates a **dedicated test tenant on dev** through the operator console, with two users whose email addresses are your mailboxes (and a third for Apple if used). The demo tenant is not touched, so no demo mail ever goes through your relay.
- **You type the relay password yourself** into that tenant's Administration → Mail settings (step-up required). It is stored encrypted with the tenant's data key, never shown again, logged or audited (ADR-0030). It is never put in the repository, `.env` files, Ansible variables, CI secrets, test fixtures or the chat.
- Your mailbox passwords are never needed: you read the messages yourself.
- The E05 log names the clients ("an Outlook on the web mailbox"), never the addresses.
- After the run, the relay settings are deleted (`DELETE /api/v1/admin/mail-settings`) and the test tenant's users are deactivated.

**Your time:** about 10 minutes to set up the relay and 20 minutes to watch six messages arrive and tick the checklist in `test-plan.md` ("Recorded real-client run"), with a screenshot where something looks wrong.

**If none is available at T9,** the real-client check is carried (Phase 4 Q5) with the golden files and validation as the evidence, and the run is repeated with the first customer's mail system.

## Acceptance criteria (selection; the full list is in `test-plan.md`)
- **T1 (writer):**
  - Given a meeting on 12 Dec 2026 10:00–13:00 `Asia/Dhaka` in the Board Room, when the writer builds the REQUEST for `director@`, the file equals the golden file `request-asia-dhaka.ics` apart from `DTSTAMP`, passes ical4j's strict and iTIP validation, and parses in biweekly with the same UID, SEQUENCE and times.
  - A title containing `Risk;\r\nATTENDEE:mailto:x@evil.example` produces one SUMMARY line, escaped, and no second ATTENDEE.
- **T1 (mail):** given an outbox row with a calendar part, the delivered message is `multipart/mixed` with `multipart/alternative` (plain, HTML, `text/calendar; method=REQUEST; charset=UTF-8`) and an `invite.ics` attachment with the same bytes; the subject has no parameter.
- **T2 (invite):**
  - Given the BRC with seven members and a guest, when `secretary@` schedules the meeting, two minutes later eight messages are queued, each naming only its recipient, all with SEQUENCE 0 and one UID.
  - Three reschedules within one minute produce one UPDATE per person with SEQUENCE 1 (debounced to the latest state).
  - Cancelling sends CANCEL with `STATUS:CANCELLED` and SEQUENCE 2 at once, and no update still pending is sent after it.
- **T2 (audience):** when a member's term ends before the meeting's date, they get a CANCEL without STATUS; when re-appointed, a REQUEST with a higher sequence than that CANCEL.
- **T3 (reply):**
  - Given an invited `director@`, when they answer "No, I send my apologies" with "My alternate will attend", the alternate is in the audience as ALTERNATE, is told and is invited, and the secretaries see the apology.
  - When the meeting is then moved to 14:00, `director@`'s answer shows "for the old time" and the forecast counts it as no answer.
  - An answer recorded by the secretary "by phone" tells `director@` in the app and by email.
- **T3 (recusal):** given `director@` recused from item 5 and their alternate standing in, the alternate gets 404 on item 5's papers.
- **T4 (register):**
  - Given the meeting IN_PROGRESS with apologies from the RSVP prefilled, when the secretary marks "everyone who said yes" present, each gets one ARRIVED event, and the live quorum shows "Quorate: 5 of 7 present, 4 needed".
  - A second ARRIVED for the same person answers 409 `WRONG_STATE`; a stale If-Match answers 412.
  - When two members leave during item 6 and the meeting falls below 4, `meeting.quorum.lost` is audited with item 6 and the chair is told in-app.
- **T4 (alternates):** with the chair absent and their alternate present, a rule needing "the chair or a vice-chair" is not met, and the alternate counts once towards the number.
- **T5 (snapshot):**
  - Given item 5 with two recused members and `recusedCount` false, `take` answers 409 `NOT_QUORATE` with "3 of 4 needed".
  - With the type's `quorumOverride` WITH_REASON and a reason, the snapshot is stored as not quorate, overridden, to be ratified, and the chair is told.
  - A snapshot's stored inputs re-evaluate to its stored result and hash.
- **T6:** given the volume set, the budgets of design §8 are met and every list query's plan uses its index.
- **T7:** with the keyboard only, a director answers "Yes, online", changes it to apologies with the alternate, and downloads "Add to my calendar"; every target is at least 44 px; axe finds no serious issue in English and Arabic.
- **T8:** with the keyboard only, the secretary opens the register, marks arrivals, moves to item 3, records a departure and voids a wrong entry with a reason.
- **T9:** each step of the run's checklist has a recorded result per client, and every difference from the expected result is either fixed with a new golden file or recorded as a known client behaviour in the runbook.
