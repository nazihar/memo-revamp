# E03 Meetings and the agenda builder — log

## 2026-11-18
- **Plan written under the delegated gate** (Phase 4 Q1):
  - research: R1 versions; R2 from the help centres and reviews of eight portals (Convene, iBabs and Boardvantage publish almost nothing), the CGI and Board Intelligence 2026 board-reporting guidance, BoardSource and Board Intelligence on consent agendas, the bank terms of reference of Bank of Cyprus, Emirates NBD and HSBC, Basel 2015 and ORIC on conflicts, and WCAG 2.2 on dragging;
  - ADR-0060 (meetings and the agenda model) and ADR-0061 (meeting papers and the one access rule);
  - design, threat model, tasks T1–T9, test plan, and screen flows N–R for the owner with E02's flow M.
- **Choices that came from the research:**
  - A working draft with immutable published revisions and "What changed since you last looked" (Boardable, BoardPro, OnBoard).
  - Three levels, with items numbered continuously and renumbered only at publication.
  - The chair agrees the first publication (CGI, Emirates NBD §4.1.3), and the secretary may record it on the chair's behalf.
  - The paper deadline is five working days by default (CGI 2026, Bank of Cyprus §5.2), with E02's late modes enforced at placement.
  - Any member can lift a consent item without a reason or approval (BoardSource, Board Intelligence). No portal documents this inside the software.
  - "Only these people" restrictions on items and papers that survive replacement (against Diligent's re-permissioning complaint). Titles stay visible unless hidden (Admincontrol, Diligent Community).
  - Every move is a command before it is a drag (WCAG 2.2 SC 2.5.7, Diligent's "Move to").
  - dnd-kit 6.3.1 / sortable 10.0.0 pinned rather than the pre-1.0 `@dnd-kit/react`.
- **Changes from the overview, recorded in ADRs:**
  - **Members read the papers, not the memo** (ADR-0061). The overview assumed members would become memo readers through ADR-0050's reader table. That would give them the whole memo file (note sheet, every version, annexes left out of the pack) and put recusal into the memo rule. To report to the owner with the flows.
  - **Meetings live in the `meeting` module** (`AGENTS.md §4`), not in `forum`. The forum module keeps the agenda template on the type version (`forum/V5`) and publishes its events, `ForumPolicies` and `SubmissionPlacements`, so the dependency runs meeting → forum only.
  - The overview's meeting status "draft → scheduled → in progress → held → minutes approved → closed" is kept. E03 builds it up to held and cancelled.
- **Found while reading:**
  - The forum events (`ForumChanged`, `ForumTypePublished`, `ForumSubmissionChanged`) are in `forum.internal`, so no other module can listen to them yet. T1 moves them.
  - The web has no dnd-kit yet: the workflow builder uses React Flow.
  - `FilePurpose.mayRead` sees the file, not the version, so pinned paper versions are served through a new `FileServing` entry point behind the meeting rule.

## 2026-11-18 — T1 meetings (backend)
- **Built:**
  - `forum/V5` (the type version's `agenda` policy column, read from T2; `forum.code_locked_at`; `forum_submission.meeting_id` and `meeting_date` with the tray index) and `meeting/V1` (`meeting`, `meeting_counter`, `meeting_guest`, `meeting_audience`; RLS forced, `tenant_id` first, composite foreign keys, no DELETE for the app role). jOOQ regenerated; module metadata regenerated (meeting now depends on forum).
  - Forum's published API: `ForumChanged`, `ForumTypePublished` and `ForumSubmissionChanged` (now with the status it left) in `com.memo.forum`; `ForumPolicies` moved there with `version` and `published`; `SubmissionPlacements` (`place`, `unplace`, `placed`, audited `forum.submission.placed` and `.unplaced`); `ForumCodes.lock` over `Forums.lockCode` (audited `forum.code_locked`, then `CODE_LOCKED`); `ForumSeat.until`.
  - `WorkingDays.before` on a pure `WorkingDayRules.before`, with property tests.
  - The meeting module: create, edit, schedule, reschedule, start, end and cancel along the state machine, with IANA zones (gaps refused, overlaps take the earlier offset), https links without credentials, place by mode, the classification floor and guests; the materialised audience recomputed on `ForumChanged`, on guests and on the date; `GET /forums/{fid}/meetings` (cursor), `GET /meetings/{id}`, `GET /me/meetings`; the published `Meetings`; `MEETING_CANCELLED` to the audience when a scheduled meeting is cancelled (English and Arabic).
  - Tests: `MeetingStateTest`, `MeetingFieldsTest`, `MeetingTimesProperties`, `WorkingDaysBeforeProperties`, `MeetingsIT`, `MeetingCrossTenantIT`, placements in `ForumSubmissionsIT`, the code lock in `ForumsIT`, `WorkingDays.before` with holidays in `BusinessCalendarIT`; matrix `p4-e03-meetings.yaml`.
- **Deviations from the design** (recorded in `design.md` as "as built"):
  - `Forums` is internal, so the meeting module locks the code through the published `ForumCodes`.
  - `SubmissionPlacements.place` also takes the forum id; `SubmissionStatus` and `SubmissionPurpose` became published types so the event can carry the status.
  - `ForumChanged` was only published for the forum row; it is now also published for every membership change, which the audience needs. A position seat's new holder reaches the audience at the next roster or meeting change (org events do not publish `ForumChanged`); to revisit with E05 if it matters.
  - A draft is rescheduled like a scheduled meeting (times change only through reschedule), without a reason.
  - `meeting_audience` marks rows removed (`removed_at`) instead of deleting them, to keep the no-DELETE rule.
  - `forum.read` holders outside the audience see neither the link nor the location (threat model I5). A classification above the actor's own clearance is refused, and so is a guest not cleared for the meeting.
  - `/me/meetings` lists the meetings of forums where the actor is a secretary or the chair, not every meeting for `forums.manage` holders; recent means 30 days back, at most 100.
- **Left for T2:** the agenda policy on the type version (checks, presets, changes view), placing submissions from the meeting side, the cancellation's effect on agendas and placements, `MeetingChanged`, `meeting.numbered` with the counter.
