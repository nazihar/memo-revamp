# E03 Meetings and the agenda builder — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, integration, matrix, smoke E2E), nightly (PIT, browsers, visual, pseudo-locale), weekly (load).

## Scope and risks
- **Risks covered:**
  - a paper reaching someone outside its restriction, section or meeting (the main risk; ADR-0061);
  - members seeing drafts;
  - a published revision changing afterwards;
  - lost edits between two secretaries;
  - a submission placed twice or left on an agenda after it was returned;
  - late papers bypassing the committee's rule;
  - wrong times across zones;
  - meeting numbers with gaps;
  - the builder unusable without a mouse.
- **Seed:**
  - the demo committees (E01 T6) and the credit workflow escalating to the MCC (E02 T5);
  - `secretary@` (BRC and MCC secretary), `director@` (BRC member), `head.credit@` (MCC chair);
  - a BRC chair user (seeded in T9);
  - `auditor@` (with `forum.read`), `user@` (no forum);
  - a guest user and an item presenter user (seeded in T9).

## Tests per slice
| Task | Layer | Tests |
|---|---|---|
| T1 | Unit | `MeetingStateTest`: every allowed and forbidden transition of design §1.1; `MeetingFieldsTest`: zone names, `https` links without user-info, location by mode, end after start, at most 7 days, classification floor |
| T1 | Unit + property | `MeetingTimesProperties`: a meeting's local times round-trip through UTC for random zones and dates, including DST gaps and overlaps (a start in a gap is refused with `UNKNOWN_LOCAL_TIME`); `WorkingDaysBeforeProperties`: `before(d, n)` is a working day, n working days lie between it and d, and it is monotonic in n |
| T1 | Integration | `MeetingsIT`: create, edit with If-Match, schedule, reschedule (reason), start, end, cancel (reason), guests, `FORUM_DISSOLVED`; the audience recomputed on a roster change and on a date change; `/me/meetings`; audit for each |
| T1 | Authorisation matrix | `p4-e03-meetings.yaml`: every endpoint × secretary (own forum and another), chair, `forums.manage`, member, former member, guest, item attendee, `auditor@` with `forum.read` (cleared and not cleared), `user@`; 404 for invisible meetings |
| T1 | Persistence/RLS | `MeetingCrossTenantIT`: tenant A cannot read, change or place into tenant B's meetings, agendas, revisions or papers, including through `/me/meetings`; composite foreign keys; no DELETE |
| T1 | Modularity | `ApplicationModules.verify()` with `meeting → forum, memo, files, workflow` and no reverse dependency; module metadata regenerated |
| T2 | Unit | `AgendaRulesTest`: `ONE_LEVEL`, breaks, caps, positions after each move kind, totals and overrun, numbering (continuous, breaks skipped, sub-items `n.1`) |
| T2 | Integration | `AgendaBuilderIT`: start from template and from an earlier meeting (what is and is not copied; restrictions narrowed and listed); sections, items, sub-items and moves; stale version → 412; `FROZEN` after held or cancelled; tabled additions only while in progress |
| T2 | Integration | `AgendaPlacementIT`: place an accepted submission (papers from the memo's document and attachments); accept and place a queued one; the late modes ALLOW, BLOCK and CHAIR_APPROVAL against a deadline computed on a calendar with a holiday; return, decline, refer, defer and withdraw remove the item; removing the item frees the submission; the forum panel shows the meeting |
| T2 | Concurrency | `AgendaConcurrencyIT`: two parallel placements of one submission → one succeeds; two moves with the same version → one 412; the same idempotency key twice → one item |
| T3 | Unit | `AgendaStateTest`: every allowed and forbidden transition of design §1.3 (publish without agreement, while DRAFT, with nothing changed, with an unreadable paper); `ChangeFlagsTest`: each flag from pairs of revisions, restriction changes never shown |
| T3 | Integration | `AgendaPublishIT`: revision 1 with chair agreement (in the app and on the chair's behalf); numbers and times; pinned versions; revision 2 with a note and flags; members still on revision 1 until then; AWAITING_CHAIR items left out; notify off; audit with counts |
| T3 | Concurrency | `MeetingNumberConcurrencyIT`: parallel first publications in one forum give consecutive numbers; a rolled-back publication leaves no gap; the forum's code locks |
| T3 | Integration | `AgendaNotificationsIT`: each audience member is notified once per revision (dedupe key); a person who sees nothing changed is not told; subjects have no parameters; no item title in email |
| T4 | Integration | `PaperAccessIT`: the rule's matrix (viewer source × published or draft × restriction × members-only × hidden title × classification × meeting status × former member × guest after the minutes are approved), checked through each surface in the registry |
| T4 | Integration | `PaperServingIT`: members get the PDF rendition of the pinned version with ranges; originals only for officers; an old revision's version through `?revision=`; one `meeting.paper.opened` per open; the generic files endpoints refuse a `meeting.paper` file outside the rule |
| T4 | Architecture | `PaperAccessArchTest`: only `PaperAccess` and the revision store read the revision tables; only `meeting` calls `FileServing` |
| T4 | Integration | `ConsentRequestIT`: ask, withdraw, ask again; who may ask; closed after the meeting starts; notifications; the flag shown to every member who sees the item |
| T4 | Integration | `WhatChangedIT`: "since you last looked" per user, limited to what that user sees |
| T5 | Performance | `MeetingVolumeIT` (weekly `load`): the budgets of design §8 at 3,600 meetings, 180,000 items and 600,000 papers; `EXPLAIN` checked for every list query |
| T5 | Integration | `MeetingSweepIT` (test clock): the agenda-due reminder once at the deadline, the "not held" flag once, per tenant with the context set |
| T6–T8 | Web unit | Time display in two zones (DST, same zone shows one); the Move menu commands; the builder reducer against the move API; the side sheet's restriction picker limited to the audience; rendering of restricted and hidden items; the consent button states |
| T6–T8 | E2E | `meeting-agenda.spec`: schedule, start from the template, place a submission from the tray, move by keyboard, agree as the chair, publish, replace a paper, publish revision 2. `meeting-page.spec`: a member reads the agenda and a paper, sees "What changed", asks to discuss a consent item; a guest sees no members-only section |
| T6–T8 | Accessibility | axe on every new screen (en, ar, pseudo-locale); the manual keyboard and screen-reader pass (NVDA and VoiceOver) over the builder's moves and dragging announcements, and over the meeting page; target sizes measured (44 px on the meeting page) |
| T6–T8 | i18n | ICU messages for purposes, change flags, late labels and time-zone lines; right-to-left layout of the builder and the meeting page |

## State-machine tests
Every allowed and forbidden transition of the meeting (§1.1) and agenda (§1.3) state machines, plus the late states of an item (§1.4) and the consent request (§1.5).

## Leak-suite skeleton (for E04)
- `PaperSurfaceRegistry` lists every read path that can reveal an item or paper:
  - the members' agenda, the revision list, "What changed" and the preview;
  - paper content (PDF and original) and the generic files endpoint for `meeting.paper`;
  - per-recipient notifications (in-app and email);
  - `/me/meetings` counts.
- `PaperLeakIT` runs one probe per surface for a user outside the rule. It asserts no hidden title, no paper, no count and no notification.
- A meta-test fails when a new controller method in the `meeting` module is missing from the registry.
- E04 adds the recused-member column and its own surfaces.

## Security tests
- 404 for invisible meetings, items and papers.
- Restricted papers absent from lists.
- `https`-only links, rendered without `javascript:`.
- The join link absent for `forum.read`.
- Email subjects without parameters.
- Rate limit on builder writes.

## Exit checklist
- [ ] All of the above green, matrix complete, leak-suite skeleton in place.
- [ ] Journeys on dev.
- [ ] Manual accessibility pass recorded: the agent's keyboard, axe, structure and reflow pass is in `ux-review.md` (T9); the screen-reader pass by a person is still to do.
- [x] ASVS rows, runbook (T9: five rows in `asvs-checklist.md`, `docs/runbooks/meetings-and-agendas.md`).
- [x] Owner's screen review recorded (flows N–R with M, 2026-11-18).
