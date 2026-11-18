# E03 Meetings and the agenda builder — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **Meetings:**<br>• the `meeting` module's first migration `meeting/V1` and the regenerated module metadata (#151);<br>• `forum/V5` (agenda policy on the type version, `code_locked_at`, placement columns);<br>• the forum events moved to the published package, plus `ForumPolicies`, `SubmissionPlacements` and `Forums.lockCode`;<br>• `WorkingDays.before`;<br>• meetings (create, edit, schedule, reschedule, start, end, cancel) with the state machine, IANA zones, link checks, the classification floor and guests;<br>• the materialised audience, recomputed on `ForumChanged`;<br>• `/me/meetings` and the forum's meetings;<br>• audit; the matrix `p4-e03-meetings.yaml`; cross-tenant tests | L | E02 T5 merged |
| T2 | **The agenda draft:**<br>• `meeting/V2`;<br>• start from the template (presets get an agenda template, checks and the changes view on the forum type) or from an earlier meeting;<br>• sections, items, sub-items and breaks with the invariants; moves; times and totals;<br>• presenters, attendees, restrictions, hidden titles and classifications;<br>• the tray and placing submissions (accept and place, late modes, `ForumSubmissionChanged` keeping items in step);<br>• memo papers and the `meeting.paper` purpose; caps;<br>• audit | L | T1 |
| T3 | **Publishing and revisions:**<br>• `meeting/V3` revision tables;<br>• chair agreement (in the app or on the chair's behalf) and late-paper decisions;<br>• publish (numbers, times, pinned versions, change flags, note, refusals);<br>• the meeting number counter and the forum code lock;<br>• `AgendaPublished` and the async per-recipient notifications;<br>• `MeetingChanged`;<br>• audit | L | T2 |
| T4 | **Papers and the members' view:**<br>• `PaperAccess` (ADR-0061) with `PaperExclusions` and `NoExclusions`;<br>• `FileServing.serve` in files, with its architecture test;<br>• the members' agenda, "What changed since you last looked", the revision list and paper opens (PDF for members, originals for officers), audited;<br>• preview as a member;<br>• consent "Ask to discuss" and withdraw;<br>• the surface registry and the leak-suite skeleton | L | T3 |
| T5 | **Sweeps and volumes:**<br>• `meeting-sweep` (agenda due at the deadline, meeting not marked as held);<br>• the volume generator (3,600 meetings, 180,000 items, 600,000 papers);<br>• `EXPLAIN` for every list query, and the performance ITs against the budgets | M | T4 |
| T6 | **Web, meetings** (after the owner's screen review, flow N):<br>• the Meetings tab on the committee page, scheduling and settings dialogs, the time-zone picker by city, reschedule and cancel;<br>• "My meetings" on the home page and the "My committees" cards;<br>• en/ar | M | T1, E01 T5 |
| T7 | **Web, the agenda builder** (after the owner's screen review, flows O, Q and R, and M's addition):<br>• spike: dnd-kit 6.3.1 / sortable 10.0.0 under React 19.3 StrictMode, then the dependency register rows;<br>• the builder with the Move menu and drag handles, the tray, the item side sheet (details, papers, who can see it), times and the deadline bar, preview as a member, the publish dialog with the change summary, chair agreement and late papers on the chair's side;<br>• the committee type's Agenda tab;<br>• Accept-and-place in E02's triage dialog;<br>• en/ar | L | T4, E02 T4 |
| T8 | **Web, the meeting page** (after the owner's screen review, flow P):<br>• the agenda for members with 44 px targets, two time zones, purposes in words, papers opening in the PDF viewer, "Opened" ticks, restricted and members-only rendering, "What changed", consent "Ask to discuss";<br>• the print style;<br>• en/ar | L | T4 |
| T9 | **Close:**<br>• demo seed: a BRC meeting with a published agenda (revision 2 with a replaced paper), a consent section with one lifted item, a restricted item and a late paper agreed by the chair, and an MCC meeting with the credit proposal placed from the queue;<br>• journeys `meeting-agenda.spec` and `meeting-page.spec`;<br>• the manual keyboard and screen-reader pass over the builder and the meeting page;<br>• runbook `meetings-and-agendas.md`; ASVS rows; log and STATUS | M | all |

**Order:**
- T1 → T2 → T3 → T4 → T5 for the backend.
- T6–T8 wait for the owner's screen review (`ux-review.md`, with E02's flow M) and their backend tasks.
- T9 last.
- Each task is its own PR, merged when CI is green (standing go-ahead, 2026-11-17).

**In parallel:** T5 beside T6 once T4 is merged. E05's invitations can start once T1 is merged (overview §2).

## Acceptance criteria (selection; the full list is in `test-plan.md`)
- **T1:**
  - Given `secretary@` is secretary of the BRC, when they create a meeting on 12 Dec 2026, 10:00–13:00 in `Asia/Dhaka`, online without a link, the answer is 422 at `joinUrl`.
  - With `https://meet.example/abc` the meeting is a DRAFT, and `director@` (a BRC member) gets 404 on it.
  - After scheduling, `director@` sees it in `/me/meetings` with the link.
  - `user@` still gets 404.
- **T1 (states):**
  - Starting a DRAFT answers 409 `WRONG_STATE`.
  - Cancelling a SCHEDULED meeting without a reason answers 422 `REASON_REQUIRED`.
- **T2:**
  - Given an accepted MCC submission and a meeting whose paper deadline has passed with late mode CHAIR_APPROVAL, when the secretary places it, the item is AWAITING_CHAIR and the MCC chair is notified.
  - With late mode BLOCK, the answer is 409 `PAPER_DEADLINE_PASSED` and nothing changes.
- **T2 (moves):**
  - Moving a sub-item under another sub-item answers 409 `ONE_LEVEL`.
  - A move with a stale agenda version answers 412.
  - Two parallel placements of the same submission: exactly one succeeds.
- **T2 (in step):** given a placed submission, when the secretary returns it in the queue, the item disappears from the draft and the submission no longer shows a meeting.
- **T3:**
  - Given a BRC type whose agenda needs the chair's agreement, publishing revision 1 without it answers 409 `CHAIR_AGREEMENT_REQUIRED`.
  - After the secretary records the agreement "by email on 4 Dec", publishing creates revision 1 and numbers the meeting "BRC 1/2026".
  - The members are notified, each once.
- **T3 (revisions):**
  - Given revision 1, when the secretary replaces a paper and moves item 5 to position 3, then publishes with the note "Updated credit paper", revision 2 flags PAPER_REPLACED and MOVED (was 5).
  - Members still read revision 1 until then.
- **T4 (rule):**
  - Given item 6 restricted to two members, a third member sees "6. {title} — papers restricted" and gets 404 on its paper.
  - With "hide the title" on, they see "6. Restricted item".
  - A guest sees nothing of a members-only section.
  - A cancelled meeting's papers answer 404 to members.
- **T4 (consent):**
  - Given a consent item, when `director@` asks to discuss it, every member sees "Lifted for discussion at Demo Director's request", and the secretaries and chair are notified.
  - After the meeting starts, a request answers 409 `WRONG_STATE`.
- **T5:** given the volume set, `GET /me/meetings` and the members' agenda meet their p95 budgets, and every list query's plan uses its index.
- **T7:** with the keyboard only, the secretary moves an item to another section, makes it a sub-item and publishes. axe finds no serious issue in English and Arabic.
- **T8:** with the keyboard only, a director opens the meeting, reads a paper and asks to discuss a consent item. All targets are at least 44 px.
