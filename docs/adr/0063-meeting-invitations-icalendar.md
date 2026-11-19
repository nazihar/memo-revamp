---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (the real-client run needs their mailboxes, Phase 4 Q5)
---

# 0063 — Meeting invitations: iCalendar by email from the tenant's relay, RSVP in the app

## Context and problem statement
Committee members live in Outlook, Gmail or Apple Calendar. A meeting scheduled in memo (ADR-0060) must reach their calendars, follow every reschedule and disappear when cancelled, without a calendar API connector (Phase 4 overview A5) and without any hosted service (AGENTS §3.1). Members must also say whether they will attend, because the secretary plans quorum on it. The overview left the library, the iTIP methods, the content rules and the reply path to this epic (ADR-ICAL). Research: `phase-4-forums-meetings/epic-05-invitations-attendance-quorum/research.md §2–§4`.

## Decision drivers
- Accepted by common clients: RFC 5545, 5546 and 6047, and the known behaviour of Outlook, Gmail and Apple Calendar.
- Nothing sensitive leaves the platform in an invite: no agenda, item titles or papers (overview E05); subjects take no parameters (ADR-0030).
- No inbound mail path, no hosted connector, nothing an on-prem install has to run in addition.
- An attendance answer must come from the signed-in member, or be recorded by the secretary with how it was given.
- Older directors: one plain question, no reliance on a calendar's Accept buttons, few emails.

## Considered options
1. **iCalendar REQUEST and CANCEL by email from the tenant's relay, one message per person, RSVP in the app** (chosen).
2. iCalendar by email, with replies (`METHOD:REPLY`) read from an inbound mailbox per tenant.
3. A calendar connector (Microsoft Graph, Google Calendar API) or a hosted broker such as Cronofy, as OnBoard does.
4. No invites; an "Add to calendar" download only.

## Decision outcome
Chosen option 1.

### Library
- **ical4j 4.4.0** (BSD-3-Clause) writes every invite and validates it (strict) before it is queued; a validation failure fails the job and alerts, it never sends a malformed file.
- `net.fortuna.ical4j.timezone.update.enabled=false`: no run-time fetch from tzurl.org. The **VTIMEZONE is built from the JVM's `ZoneRules`** for the meeting's zone (the observances in force from one year before to one year after the meeting), so the invite describes exactly the offsets the app used.
- **biweekly 0.6.8** (BSD-2-Clause) is a **test-only** second parser: every golden file and every generated invite in the tests must parse in both libraries to the same UID, SEQUENCE, METHOD, DTSTART, DTEND and TZID.
- **Spike findings (E05 T1, 2026-11-18; details in the E05 log):** both libraries run on Java 25, and with the updater off nothing connects out. ical4j's validation applies its iTIP rules by METHOD, but accepts a CANCEL without ATTENDEE or with `STATUS:CONFIRMED` and a PUBLISH with ATTENDEE, so the writer adds RFC 5546's rules for the methods memo sends. Given a `ZonedDateTime`, ical4j writes the local time in its own bundled zone data, which can be stale (10:00 in America/Sao_Paulo came out as 11:00), so DTSTART and DTEND carry `java.time`'s local time with the TZID parameter. ical4j folds by characters, so the writer folds at 75 octets itself. The VTIMEZONE uses yearly RRULEs when the zone's current rules govern the window (checked against the JVM's transitions), else (one-off changes) only the observance in force at the start, after the standard one when it is daylight saving, and any change during the meeting: biweekly, and possibly some clients, read the last observance's offset.

### One invitation per meeting
- `UID`: a random UUID (version 4) created when the meeting is first scheduled, stored on the meeting's invitation, never changed, and containing no domain, host or meeting id (RFC 7986 §5.3).
- `SEQUENCE`: starts at 0 and the meeting's counter increments on every change to the start, end, time zone, place, mode, join link or joining details, and on cancellation (RFC 5546 §2.1.4, §3.2.5). Each message carries `max(meeting sequence, the last sequence that person was sent + 1)` for a change, so a person's copies never go backwards, including after an uninvite and re-invite; a plain resend repeats the person's last sequence. Changes to the agenda never touch the invite.
- `DTSTAMP`: the time the message is generated, UTC.
- `DTSTART`/`DTEND` with `TZID` = the meeting's IANA zone and a matching VTIMEZONE.
- `ORGANIZER`: the tenant's sending address (the relay's from address, or the platform's), with `CN` = the committee's name. Never a member or the secretary: Outlook would treat their copy as the organizer's own.
- `ATTENDEE`: only the recipient, `ROLE=REQ-PARTICIPANT` (`OPT-PARTICIPANT` for guests), `PARTSTAT=NEEDS-ACTION`, `RSVP=FALSE`, `CN` = their display name.
- `SUMMARY`: the meeting's title or the committee's name, or the committee type's **neutral title** ("Committee meeting") when the type sets one. `LOCATION`: the place, or "Online meeting". `DESCRIPTION`: how to reply in memo, the link to the meeting page, the join link and joining details; never the agenda, item titles, papers or classification text. `STATUS:CONFIRMED`, or `CANCELLED` on cancellation. With a neutral title, `CLASS:PRIVATE` as well, kept only if the recorded run shows the clients accept it.
- **Methods:** `REQUEST` when a member is first invited and for every update; `CANCEL` with `STATUS:CANCELLED` when the meeting is cancelled; `CANCEL` without `STATUS` to uninvite one person (their term ended, a guest removed, an alternate no longer needed). `PUBLISH` (no ATTENDEE) only for the "Add to my calendar" download on the meeting page. `REPLY`, `COUNTER` and `REFRESH` are never produced or read.

### Who is invited
- Every person in the meeting's audience as MEMBER (roster seats in office on the meeting's date), GUEST, or ALTERNATE (standing in for a member who sent apologies, ADR-0064), with an email address, once the meeting is SCHEDULED.
- Item attendees and presenters are told by E03's agenda notifications and can use "Add to my calendar"; they get no invite, because a three-hour hold for a fifteen-minute item misleads.
- Drafts invite nobody. Held meetings send no more updates.

### Sending
- Through the notification module (ADR-0030): a new `CALENDAR` category (on by default, a member may switch the email off), kinds `MEETING_INVITATION`, `MEETING_UPDATED`, `MEETING_CANCELLED` (the existing kind, now with the CANCEL part) and `MEETING_UNINVITED`, each with an in-app item.
- The outbox carries the generated `.ics` bytes, encrypted like the parameters, and the method. Delivery builds one MIME layout: `multipart/mixed` [ `multipart/alternative` [ `text/plain`, `text/html`, `text/calendar; method=…; charset=UTF-8` ], `invite.ics` (`application/ics`, the same bytes) ] (RFC 6047 §2.4).
- **A reconciler, not a send per change:** each change schedules one `meeting-invitations` job per meeting (instance id = meeting id, two minutes after the last change, carrying `tenantId`). The job compares what each person should hold with what they were last sent and sends only the difference. Three edits in a minute make one update; a retry never sends twice; a cancel overtakes pending updates.
- The secretary sees per person what was sent, when, and the relay's answer (from the outbox), and may resend the current version (rate-limited).

### RSVP in the app
- On the meeting page and from "My meetings": "Will you attend?" with "Yes, in person", "Yes, online" and "No, I send my apologies" (with "My alternate will attend" when the seat has one, ADR-0064). An optional note to the secretary.
- The answer can be changed until the meeting starts. A change of **date or time** marks earlier answers "given for the old time" and asks again; a new room, link or title does not.
- A secretary can record an answer given by email, phone or in person, with how and when; the member is told.
- The invite asks for the answer in memo in its first line. Calendar Accept or Decline buttons, where a client still shows them, send a reply to the tenant's sending address, which memo does not read; the runbook tells tenants to use an address that bounces politely or is monitored.
- No answer links in emails: link scanners follow them (RFC 8058 §1). The email links to the meeting page; answering is a signed-in POST.

### Consequences
- Good:
  - Works with any client that speaks iMIP, from the customer's own relay, with nothing to run or license in addition.
  - The file is validated twice in CI and once before sending; golden files fix the exact bytes for request, update, cancel and uninvite.
  - Directors get one email per real change and never have to use their calendar's buttons.
  - The attendance answer is authenticated and auditable, and secretaries can record what they are told.
- Bad:
  - Replies sent from a calendar are not read; a director who clicks Accept in Outlook has not answered in memo. The invite and the reminder say so, and the secretary sees who has not answered.
  - Google's "known senders only" setting can hold the invite back as an email until the director responds; "Add to my calendar" and the runbook cover it.
  - One message per person means a meeting of 40 sends 40 messages per change (debounced).
  - Some behaviour (Gmail's own "Add to calendar", uninviting one person, `CLASS:PRIVATE`) is proven only by the recorded run in real clients.
- Follow-ups:
  - The recorded run in Outlook, Gmail and Apple Calendar (Phase 4 Q5 (a), E05 T9); carried if the owner has no mailboxes then.
  - Reading `METHOD:REPLY` from an inbound mailbox, or a Graph or Google connector, only as an optional tenant-enabled connector by a later ADR (AGENTS §3.1).
  - S/MIME signing of invites if a customer asks (RFC 6047 §2.2).

### Confirmation
- Golden files (`backend/src/test/resources/ical/`) for REQUEST (first, update after reschedule, update after a new link), CANCEL (meeting cancelled), CANCEL (uninvite one person) and PUBLISH (download), in a zone with daylight saving and one without, compared byte for byte after normalising `DTSTAMP`.
- Every generated file passes ical4j's strict validation and its iTIP rules for its method, and parses in biweekly to the same key properties.
- Property tests: per person, sequences never decrease and increase on every change and every cancel; the UID never changes; no agenda, item or paper text (canary strings) ever appears in a file or an email.
- Jazzer on the writer's inputs (titles, places, join details, names) nightly: no input breaks the format, injects a property or exceeds 75 octets per line.
- The recorded real-client run, with its results in the E05 log and test plan.

## Pros and cons of the options
- **Option 1:** + standard, self-hosted, works on-prem, safe content; − calendar replies ignored, some client behaviour only testable by hand.
- **Option 2:** + calendar buttons would work; − an inbound mail path per tenant, unsigned replies that "may not be trusted" (RFC 6047 §2.2.2), clients that do not send replies, and one more service for on-prem.
- **Option 3:** + true two-way sync; − a hosted service or tenant cloud credentials (AGENTS §3.1), one administrator's mailbox per meeting, and an integration per vendor.
- **Option 4:** + trivial; − no updates or cancellations ever reach calendars, which is the main complaint the epic answers.

## More information
- RFC 5545 <https://www.rfc-editor.org/rfc/rfc5545>, RFC 5546 <https://www.rfc-editor.org/rfc/rfc5546>, RFC 6047 <https://www.rfc-editor.org/rfc/rfc6047>, RFC 7986 <https://www.rfc-editor.org/rfc/rfc7986>, RFC 8058 <https://www.rfc-editor.org/rfc/rfc8058> (accessed 2026-11-18).
- Microsoft MS-OXCICAL and MS-STANOICAL on SEQUENCE and RSVP (research §3.2).
- OnBoard, Boardable and iDeals help centres (research §4).
- ADR-0030 (notifications and email), ADR-0060 (meetings), ADR-0064 (attendance and quorum).
