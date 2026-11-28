---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (screen flows N–R reviewed before build)
---

# 0060 — Meetings and the agenda model

## Context and problem statement
Phase 4 E03 brings meetings and agendas. A committee's secretary must turn accepted submissions (ADR-0059) and standing business into an agenda that members can trust: what they read is what the committee will take, and changes after publication are visible. The agenda also anchors later epics: declarations per item (E04), attendance and quorum per item (E05), decisions and minutes (E06), matters arising (E07) and Phase 5's board book. Board portals differ widely: two to four levels, templates or none, live editing or republishing, and consent agendas mostly undocumented. Research: `phase-4-forums-meetings/epic-03-meetings-agenda/research.md §3.1–§3.5, §3.7, §5`.

## Decision drivers
- Members never see a half-edited agenda, and what each published agenda contained stays answerable (AGENTS §3.3).
- The usual practice of company secretaries:
  - papers five working days before;
  - the chair agrees the agenda;
  - late papers at the chair's discretion;
  - consent items that any member can lift.
- Plain for older readers: few levels, stable numbers, times, and purpose in words.
- Configuration over code: templates and policies on the forum type, with no per-tenant branches.
- Fits the module rules: one-way dependencies, published APIs and events (AGENTS §4).

## Considered options
1. **A mutable working draft plus immutable published revisions, three levels (sections, items, sub-items), in the `meeting` module** (chosen).
2. **Live editing of one agenda** (changes visible as soon as saved), with an audit trail. This is simpler, but members can see an agenda mid-edit, nothing marks what a member read before a change, and Phase 5's book versions have nothing to build on.
3. **Versioned rows**: every agenda row carries `valid_from_revision` and `valid_to_revision`. There are no snapshot copies, but every read becomes a range query and the draft mixes with history.
4. **Meetings in the `forum` module.** There would be no new module, but the module, which already holds types, members and submissions, would grow further, and attendance, quorum and minutes would all depend on one large module.

## Decision outcome
Chosen option 1.

### Meetings
- **Placement:** module `meeting`. A meeting belongs to a forum and pins the forum type version current at creation.
- **Fields:**
  - a kind (ordinary or special);
  - a mode (in person, online, hybrid), a location, and a join link as a plain `https` field, with joining instructions;
  - start and end as instants, with an explicit IANA zone (default the tenant's). A meeting may span up to 7 days;
  - a classification no lower than the forum's.
- **Statuses:** DRAFT → SCHEDULED → IN_PROGRESS → HELD, with CANCELLED from DRAFT or SCHEDULED; MINUTES_APPROVED and CLOSED are reserved for E06.
  - **Amendment (2026-11-28, Phase 4 close):** E06 built HELD → MINUTES_APPROVED (ADR-0066), which left CLOSED to E07, and E07 tracks actions on their own lifecycle and carries them to the next meeting's action taken report (ADR-0067) without closing the meeting. So no Phase 4 action reaches CLOSED, and **MINUTES_APPROVED is a meeting's final state in Phase 4.** CLOSED stays reserved, deliberately: the value stays in the API enum and in the `meeting` table's CHECK, and every reader already treats it as MINUTES_APPROVED (frozen attendance, declarations and decisions; members only for papers; counted as held). A meeting's close has a meaning only with a records step after the minutes (for example the meeting's file archived under its retention policy, `MASTER_PLAN` Phase 5 and 7). Removing the value now would change the published API and the CHECK (expand → migrate → contract) for no behaviour, and adding it back later would do the same again.
- **Number:** the label "`{forum code}` `{n}`/`{yyyy}`" comes from a per-forum, per-year counter locked in the business transaction. It is taken when the agenda is first published, so drafts and meetings cancelled before publication use no number. The forum's code is locked then.
- **Times:** shown in the meeting's zone, with the viewer's own time beside them when the zones differ.

### The agenda
- **Structure:** sections, items and one level of sub-items.
  - **Sections** are unnumbered headings of kind STANDARD or CONSENT, optionally "members only" (in camera). One section "receives submissions" by default.
  - **Items** are numbered continuously through the meeting (sub-items `n.1`), skipping breaks. Their kinds are SUBMISSION (from an accepted submission), STANDING (APOLOGIES, DECLARATIONS, PREVIOUS_MINUTES, MATTERS_ARISING, AOB, filled by E04–E07), ITEM (secretary business) and BREAK.
  - **Each item** has a purpose (for decision, discussion or noting), the resolution sought, a duration (computed start times, section totals, an overrun warning), presenters (users or typed names), attendees for that item only, an optional restriction to named people, a "hide the title" switch, a classification and papers.
- **Draft and revisions:**
  - The secretary edits a working draft.
  - **Publishing** makes revision n: an immutable copy with computed numbers and times, pinned paper file versions, and change flags against revision n−1 (added, removed, moved with the old number, retitled, time changed, paper added, replaced or removed, late, tabled).
  - **Revision 1** needs the chair's agreement when the type's agenda policy says so (default on). The chair agrees in the app, or the secretary records the agreement on the chair's behalf.
  - **Later revisions** need a one-line note. Publishing notifies the audience by default, and the secretary may turn that off for a minor correction.
  - **Members always read the latest revision**, with "What changed since you last looked" computed for them.
  - **The agenda freezes** when the meeting is held or cancelled.
- **Starting an agenda:** from the type version's agenda template (sections and standing items, reviewed with the type through maker-checker), or from an earlier meeting of a forum the secretary serves. A copy takes the structure, titles, purposes, times, presenters, attendees and restrictions (narrowed to the new audience). It never takes papers, submissions, lateness, agreement or requests.
- **Submissions become items:**
  - Placing an accepted submission (or a queued one, which is accepted in the same step) creates a SUBMISSION item from the cover sheet, with the memo's document and attachments as papers. The secretary may leave annexes out of the pack.
  - The forum records the placement through `SubmissionPlacements`. When the submission is returned, declined, referred, deferred or withdrawn, the meeting module removes the item from the draft (`ForumSubmissionChanged`) and asks the secretary to publish a revision if it was published.
- **Paper deadline and late papers:** the deadline is the end of the working day `deadlineWorkingDays` before the meeting (business calendar). The type's late mode (ADR-0059) applies at placement:
  - ALLOW marks the item late;
  - BLOCK refuses it;
  - CHAIR_APPROVAL holds it until the chair agrees.

  During the meeting, additions are marked "tabled". The secretaries are reminded once if the agenda is not published by the deadline.
- **Consent agenda:**
  - Items in a consent section are taken together by one decision (E06).
  - Any member in the audience who sees an item can "Ask to discuss" it until the meeting starts. No reason or approval is needed. The item shows "Lifted for discussion at {name}'s request" at once and is taken after the consent section.
  - The requester may withdraw the request before the meeting.
- **Builder accessibility:** every move exists as a command (Move up, Move down, Move to section, Make a sub-item, Make an item, Move to position). Dragging uses `@dnd-kit/core` 6.3.1 and `@dnd-kit/sortable` 10.0.0, pinned, with the keyboard sensor and translated announcements (WCAG 2.2 SC 2.5.7).
- **Concurrency:** every builder change carries the agenda's `version` (If-Match) and runs under the agenda row lock. Placing a submission twice is refused by a unique index.

### Consequences
- Good:
  - Members never see drafts.
  - Every published agenda can be reproduced, including which file version each person could open.
  - Numbers move only at publication.
  - E04–E07 fill standing items without new structure.
  - Phase 5's book can be compiled per revision.
- Good: templates sit with the policy they belong to and go through the same review, and cloning covers per-committee habits.
- Bad:
  - Each publication copies the agenda (about 300 rows for a large meeting). This is acceptable at these sizes and keeps reads simple.
  - The forum type grows another reviewed field.
- Bad: a consent item lifted by one member cannot be put back without the member withdrawing the request, which is deliberate (BoardSource).
- Follow-ups:
  - E04 implements `PaperExclusions` (ADR-0061) and the declarations standing item.
  - E05 adds alternates to the audience, iCalendar on schedule, reschedule and cancel, and attendance on the apologies item.
  - E06 decides items and consent sections and reads the revisions for the minutes.
  - E07 fills matters arising.
  - Move to `@dnd-kit/react` when it reaches 1.0.

### Confirmation
- `MeetingStateTest` and `AgendaStateTest`: every allowed and forbidden transition of the state machines in `design.md §1.1` and `§1.3`.
- `AgendaBuilderIT`: moves, levels, placements, late modes, chair agreement, publication, revisions and change flags.
- `MeetingNumberConcurrencyIT`: parallel first publications give consecutive numbers, and a rollback leaves no gap.
- The matrix `p4-e03-meetings.yaml`, the cross-tenant tests and the audit coverage test.

## Pros and cons of the options
- **Option 1:** + no drafts leak, + history by revision, + simple reads; − a copy per publication.
- **Option 2:** + simplest; − drafts visible, no "what changed", nothing for books.
- **Option 3:** + no copies; − every read is a range query, and drafts mix with history.
- **Option 4:** + no new module; − one large module for every later epic, against `AGENTS.md §4`.
