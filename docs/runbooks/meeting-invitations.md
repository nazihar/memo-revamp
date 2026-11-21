# Meeting invitations

**When:** a tenant sets up the mail that carries meeting invitations, or someone asks "why is this meeting not in my calendar?", "why is it there twice?", "why did the update not arrive?" or "I clicked Accept, why does the secretary not see it?". Background: ADR-0063 (invitations by iCalendar email from the tenant's relay, answers in the app), ADR-0030 (notifications and email delivery), Phase 4 E05 `design.md` §1.1–§1.5 and §9. Attendance and quorum at the meeting are in [meetings-and-agendas.md](meetings-and-agendas.md).

## How invitations work
- **One email per person.** When a meeting is scheduled, everyone who should hold the event (members on the meeting's date, guests, and alternates standing in) gets their own email with a calendar invitation (`METHOD:REQUEST`). It names only them, so directors' addresses are never shown to each other. Item attendees are told in the app and by the agenda notices, not invited.
- **Who is left out:** a person whose account cannot sign in (not activated, suspended), or who is not cleared for the meeting's classification. They still answer "Will you attend?"; the secretary records their answer (see below). The Invitations and replies tab lists them with "No invitation: they cannot sign in or are not cleared for this meeting".
- **One event, kept up to date.** Every invitation of a meeting carries the same calendar id (a random UID that never changes) and a sequence number that only rises. A new date, time, place or joining link sends an update that moves the same event. A person removed from the audience (term ended, alternate released) gets a cancellation of their copy. A cancelled meeting sends a cancellation to everyone who holds it.
- **What the invitation says:** the meeting's title (or the committee's name), its times with the time zone, the place, the joining link and details, and a link to the meeting page. Never the agenda, item titles, papers, the classification or a cancellation reason. A committee type can use a neutral title instead ("Committee meeting"; Administration → Committee types → Meetings tab).
- **Answers are given in memo, not in the calendar.** The invitation asks for no response (`RSVP=FALSE`): Outlook then sends none. Its first line says "Your calendar's Accept and Decline buttons do not reach the committee secretary". Members answer on the meeting page or a "My meetings" card: "Yes, in person", "Yes, online" or "No, I send my apologies". With apologies they can ask their seat's alternate to attend.
- **"Add to my calendar"** on the meeting page downloads the current version of the event (`METHOD:PUBLISH`) for someone whose mail system held the invitation back.
- **Members may switch invitation emails off** in their notification settings. They then get the in-app item only. The panel says "In memo only (invitation emails switched off)".

## The tenant's relay and sending address
- Invitations go through the **tenant's relay** when one is set (Administration → Email delivery), otherwise the platform's relay (`MEMO_SMTP_HOST`, `MEMO_SMTP_FROM`). Changing the relay needs a step-up. The password is stored encrypted with the tenant's data key and is never shown again, logged or audited. "Send a test email to myself" checks it.
- **The sending address** (the relay's from address, else the platform's `MEMO_SMTP_FROM`, else `memo@` the relay's host) is the invitation's `ORGANIZER`. It must be an address of the organisation's own mail domain, never a member's or a secretary's mailbox. Outlook treats an invitation organised by the recipient as their own meeting and does not add it.
- **Replies to the sending address:** Gmail and Apple Calendar may still show Yes/No buttons and send a reply (`METHOD:REPLY`) to the sending address. memo never reads that mailbox, so a reply changes nothing (a forged reply cannot record an answer either). Use a mailbox that someone reads, or an automatic answer: "This address does not take replies. Please answer on the meeting page in memo, or tell the committee secretary."

### SPF, DKIM and DMARC
Without them invitations land in spam or are refused, and the "known sender" checks of Google and Microsoft fail. Set them up with the mail team for the sending address's domain before the first meeting is scheduled:
- **SPF:** the domain's SPF record includes the relay's sending servers (for example `include:` the provider's record, or the relay's IP addresses). memo connects to the relay; it never sends from its own servers.
- **DKIM:** the relay signs outgoing mail for the domain. memo does not sign; turn on DKIM signing for the sending domain at the relay or mail provider and publish its key.
- **DMARC:** publish a policy for the domain (start with `p=none` and reports, then `quarantine`). The From address (the sending address) must align with the SPF or DKIM domain, so do not use a relay that rewrites the From to its own domain.
- **Check:** send a test email to a Gmail and an Outlook mailbox and read the headers: `spf=pass`, `dkim=pass` and `dmarc=pass`.
- **Joining details:** a forwarded invitation carries the joining link (threat model I3). Prefer meeting rooms with a waiting room or lobby, and do not put passcodes in the joining details.

### Google's "Add invitations to my calendar"
Google Calendar → Settings → Event settings → "Add invitations to my calendar" decides whether an invitation from outside reaches the calendar:
- **From everyone:** the event appears when the email arrives.
- **Only if the sender is known** (in the person's contacts, their organisation, or someone they wrote to before): an invitation from an unknown sending address stays an email until the person opens it and responds. The event is then added; responding in Gmail does not answer in memo.
- **When I respond to the invitation in email:** as above, for every sender.

For a director on Gmail or Google Workspace, either add the sending address to their contacts (it is then a known sender), ask their Workspace administrator to allow the domain, or have them use "Add to my calendar" once. Gmail's own "Add to calendar" link on an email can store the event under a new id, which later updates and cancellations do not reach: prefer opening the invitation or the downloaded file.

## Sending: the reconciler and its safety net
- **The reconciler** (db-scheduler task `meeting-invitations`, one instance per meeting) compares who should hold the event with who holds which version, and sends the difference: invitations, updates, removals and cancellations. A change waits two minutes for more changes (three reschedules in a minute send one update); a cancellation goes at once. A retry finds nothing left to send, so nobody gets a duplicate. Each send is audited (`meeting.invitations.sent`, with who got which kind of message; `meeting.invitations.resent`).
- **Every file is checked before it is queued** (ical4j's strict validation and the iTIP rules of its method). A file that fails is never sent: the task fails, `meeting.invitation.validation_failed` is audited, and the retry or the safety net sends it once the cause is fixed.
- **The safety net:** the hourly `meeting-sweep` first schedules again any meeting whose send is overdue by 15 minutes (a lost job, a restart in the middle). Nothing needs to be done by hand after an outage; the next sweep catches up.
- **Delivery** is the notification outbox's (ADR-0030): retries with backoff, and the relay's answer (sent, or not delivered with the SMTP code) shown on the panel.
- **Resend** (secretaries and `forums.manage`): "Resend…" sends the current version again to one person, everyone who has not replied, or everyone, at most once per person per 10 minutes (`RESEND_TOO_SOON` says when). It does not create a new version.

## Reading the Invitations and replies tab
Officers see it beside the agenda once the meeting is scheduled:
- "Invitations sent …", "A change is waiting to be sent: it goes out within a few minutes", or the cancellation sent;
- the replies counted (yes in person, yes online, apologies, not yet, "for the old time"), the members who said they will attend, and, with the agenda published, the quorum forecast ("If everyone who said yes attends: quorate — 5 of 7 members, 4 needed", and any item that would not be quorate even then);
- per person: role ("Member", "Alternate for …", "Guest"), the reply and note, how it came ("in memo", "by phone, recorded by …"), and the invitation: "Sent … (invitation/update)", "Not delivered: …", "Waiting to be sent", "In memo only", "No invitation: …", "invitation withdrawn", "cancellation".

**Recording an answer** given by email, phone or in person: "Record an answer…" on the person's row; say how they told you (required) and, if useful, a note ("called on 9 December"). The person is told in the app and by email that you recorded it, and can change it. A secretary's own answer is given on the meeting page.

**Answers for the old time:** only a new date or time asks again; a new place or link does not. Members who answered for the old time see "You replied … for the old time" and are asked again; they count as "not yet" in the forecast.

## Reminders
From the hourly sweep, in the meeting's time zone, skipping holidays:
- **The reply reminder** on the reply deadline day from 09:00 (the committee type's "Ask for replies by", in working days before the meeting) to those who have not answered for the current times;
- **The meeting reminder** from 09:00 on the working day before, to those who hold the invitation and did not send apologies; it also carries the declarations line (E04) and, without an answer, "You have not told us yet whether you will attend";
- **Quorum at risk** from 17:00 on the deadline day (or the working day before) when the forecast is not quorate for the meeting or an item, to the secretaries and the chair, once per version of the invitation.

## What to do when …
- **A director has no invitation.** On the panel, check their row:
  - "Waiting to be sent" or "A change is waiting": it goes within two minutes, or at the next hourly sweep;
  - "Not delivered: …" with an SMTP code: the relay refused it (wrong address, mailbox full, policy). Fix the address or the relay, then "Resend the invitation";
  - "In memo only": they switched invitation emails off;
  - "No invitation": their account cannot sign in or is not cleared for the meeting. Activate the account or change the clearance, or record their answer and send them the details another way;
  - "Sent …" but nothing in the mailbox: look in spam and quarantine (SPF, DKIM and DMARC above), and in Gmail check "Add invitations to my calendar". "Add to my calendar" on the meeting page is the quick way round.
- **A director has the event twice.** One copy was not created from the invitation: Gmail's "Add to calendar" link on the email, or an older download kept by a client as a separate appointment. Delete the copy that has no meeting link or does not move with updates; the invitation's copy is updated and cancelled by memo. A meeting cancelled and created again is a new event (a new UID): reschedule instead.
- **An update did not move the event.** Check that the update was sent to them (the panel's last message). If they edited the event in their calendar or saved it under a new id, the update cannot reach it: delete their copy and use "Resend the invitation".
- **A director says they accepted in their calendar.** Accept and Decline in the calendar do not reach memo. Ask them to answer on the meeting page, or record the answer they gave you.
- **A director was uninvited by mistake** (term end recorded on the wrong date, alternate released). Correct the roster or the answer: the next send invites them again with a higher sequence, and their calendar shows the event again.
- **Invitations went out with the wrong time.** Reschedule the meeting (with a reason): one update per person moves the event. Never cancel and recreate it.
- **The relay was down.** Mail waits in the outbox and is retried; the safety net covers lost jobs. After the relay is back, check the panel for "Not delivered" rows and resend to those people.

## Recorded run in real clients
CI checks every file against RFC 5545 and the iTIP rules, against golden files and in a second parser (`InviteValidationTest`, `InviteWriterGoldenTest`, `InviteCrossParserTest`). How Outlook, Gmail and Apple Calendar show them is checked once by hand in real mailboxes (E05 T9, `test-plan.md` "Recorded real-client run"). That run is carried until the owner provides the mailboxes and a relay (Phase 4 Q5), so client behaviours beyond the published documentation quoted above are not yet confirmed. Record what the run finds here.
