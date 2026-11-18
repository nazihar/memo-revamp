# E05 Invitations, attendance and quorum — log

## 2026-11-18
- **Plan written under the delegated gate** (Phase 4 Q1; E05's screens are not among those the owner reviews):
  - research: R1 (ical4j 4.4.0 of 2026-10-02, biweekly 0.6.8 of 2024-01-07, Spring Boot 4.2 still RC2), R3 from RFC 5545, 5546, 6047, 7986 and 8058, Microsoft's MS-OXCICAL and MS-STANOICAL notes, Google's and Apple's help, the OnBoard, Boardable, iDeals and BoardPro help centres, and R4 (E05 half) from CGI's *Minute taking* guidance, Robert's Rules, the UK model articles for public companies (alternates) and New Hampshire's land-use boards;
  - ADR-0063 (invitations by iCalendar email from the tenant's relay, RSVP in the app) and ADR-0064 (attendance and quorum at the meeting; amends ADR-0057);
  - design, threat model, tasks T1–T10, test plan, and flows V–Z recorded as delegated.
- **Choices that came from the research:**
  - **One message per person naming only them, the tenant's sending address as ORGANIZER, `RSVP=FALSE`**: Outlook treats an organizer's own copy differently and sends no meeting response when RSVP is not TRUE; directors' addresses stay private.
  - **REQUEST and CANCEL, never PUBLISH for invites:** only these give update and cancellation semantics in Outlook. PUBLISH only for "Add to my calendar".
  - **A random UUID as the UID** (RFC 7986 §5.3), not the meeting id or a domain.
  - **ical4j with tzurl updates switched off and the VTIMEZONE built from `java.time`:** the updater is a run-time call to a hosted service (AGENTS §3.1) and has hung servers; the app's own zone data must describe the invite.
  - **RSVP in the app, no answer links in email:** unsigned replies "may not be trusted" (RFC 6047), clients disagree on sending them, link scanners fetch links (RFC 8058).
  - **The secretary records answers given by email or phone** (OnBoard's community shows secretaries cannot); **only a new date or time asks again** (OnBoard resets on any change).
  - **The register records what CGI's guidance lists:** in person, remote, alternate, in attendance, apologies, absent, and who was not there for the whole meeting.
  - **Alternates follow the company-law model** (UK model articles art. 25–26): only while the member is absent, once per person, for the number only, never as chair or independent, and never for an item the member is recused from.
- **E04's open question answered:** a recused member's alternate does **not** stand in for that item, and is recused from it with them (ADR-0064). Source: art. 26(2) "subject to the same restrictions as their appointors"; the opposite practice (New Hampshire) uses a pool of alternates, not personal ones.
- **Changes from the overview, recorded in ADR-0064, to report to the owner:**
  - the quorum override is a **committee-type setting** (reviewed by a second administrator), not a tenant setting, and marks the decision "to be ratified at a quorate meeting" (Robert's Rules);
  - an alternate counts for the number only. Today's `Quorum.evaluate` lets an alternate's seat meet a "chair or vice-chair" condition and an "independent" condition, and lets one person count for two seats; T4 changes that.
- **Found while reading:**
  - `MailDelivery` sends text and HTML only; T1 adds the calendar part to the outbox (encrypted) and the iMIP layout.
  - `meeting_audience`'s source CHECK allows MEMBER, GUEST and ATTENDEE; T3 widens it for ALTERNATE.
  - `Notifications.notify` returns nothing; the invitations panel needs the outbox id, so T1 returns a `NotificationResult`.
  - E04's reminder on the working day before and E05's meeting reminder would arrive the same morning; T6 merges them into one message.
  - E05's migrations are `meeting/V5`–`V7` (after E03's V2 and V3 in progress and E04's V4 planned), `forum/V6` and `notification/V4`.
  - The ADR index check needs E04's ADR-0062 (PR #173) merged before this plan's ADR-0063 and ADR-0064 pass on `master`.
- **The one real-world input:** the owner's Outlook and Gmail mailboxes (Apple optional) and an SMTP relay for the recorded run (T9), stored only in a dedicated dev test tenant's encrypted mail settings, asked for when T2 and T3 are on dev (`tasks.md`, "Owner input"). If none is available then, the check is carried (Phase 4 Q5).
- **Uncertain, to settle in T1 or T9:** whether ical4j 4.4.0's iTIP rules cover CANCEL as strictly as RFC 5546's table (otherwise an own rule set in the test); whether `CLASS:PRIVATE` is honoured by the clients (kept only if the run confirms it); how Gmail treats an uninvite without STATUS.
