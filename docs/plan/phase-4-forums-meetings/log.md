# Phase 4 — log

History of the phase, newest last. `STATUS.md` keeps only the current position.

## 2026-11-17
- The owner asked to start Phase 4. This is ahead of the agreed order (Phase 3 E01–E06 → Phase 1b → Phase 3 E07, Phase 3 Q4): E01–E06 are merged, the Phase 1b overview (#141) waits for approval, and E07 has not started. Phase 4 needs nothing from 1b, so its plan can start now; §9 Q2 asks where 1b and E07 go.
- Overview written (`overview.md`): eight epics. E01 forum types and forums → E02 submissions and the escalation link → E03 meetings and the agenda builder → E04 conflicts of interest and recusal → E05 invitations, attendance and quorum → E06 minutes and resolutions → E07 action items → E08 decision register, analytics and close-out. About 24–32 team-weeks. Q1–Q6 sent to the owner.
- Found while reading:
  - The `forum`, `meeting`, `minutes`, `actions`, `voting`, `book` and `signature` modules exist as empty stubs.
  - The workflow's `escalate-to-forum` step does not wait: it publishes `WorkflowEscalatedToForum` and continues (Phase 2 E03 design §2), and nothing consumes the event. The exit criterion's chain needs the decision to come back to the memo, hence Q3 and ADR-ESCALATE.
  - Memo numbering (ADR-0044) lives inside the memo type and `memo_number_counter`. Resolution numbering needs it as a shared service (R9, ADR-RESOLUTION).
  - Mail on dev goes only to Mailpit, so the "accepted by common clients" special test needs real mailboxes (Q5).

## 2026-11-18
- The owner approved the overview and answered Q1–Q6 as recommended ("I'm relying on you on the questions"). They asked that the recommendations rest on industry best practice and user-friendliness. Each epic's research checks them against sourced practice; any change goes through an ADR and is reported.
- Order now: Phase 4 → Phase 1b → Phase 3 E07 (Q2). Next: the E01 plan (forum types and forums).
- E03 plan written under the delegated gate (`epic-03-meetings-agenda/`): research R1 and R2, ADR-0060 (meetings and the agenda model: draft and published revisions, consent sections, chair agreement, late papers) and ADR-0061 (meeting papers behind one access rule with the E04 recusal hook; members read the papers, not the memo, a change from the overview to report to the owner), design, threat model, tasks T1–T9, test plan, and screen flows N–R for the owner with E02's flow M.
- E04 plan written under the delegated gate (`epic-04-conflicts-recusal/`): research R6 (Basel 2015, EBA/GL/2021/05, Companies Act s.177, CGI minute-taking guidance, nine portals), ADR-0062 (per-item declarations; leave-the-room recusal through ADR-0061's hook, fail-closed while the chair rules; the recused member's minutes keep the decision but not the discussion, a change from the overview to report to the owner), design, threat model, tasks T1–T9, test plan with the recusal leak suite, and flows S–U recorded as delegated.
- E05 plan written under the delegated gate (`epic-05-invitations-attendance-quorum/`): research R1, R3 (RFC 5545, 5546, 6047, 7986, Outlook, Gmail and Apple behaviour, four portals) and R4's meeting half (CGI minute-taking guidance, Robert's Rules, the UK model articles on alternates), ADR-0063 (iCalendar invites by email from the tenant's relay with ical4j 4.4.0, one random UID and a sequence per change, one message per person, RSVP in the app) and ADR-0064 (an append-only attendance register, live quorum per meeting and item, snapshots with each decision; amends ADR-0057: alternates count for the number only and never for an item their member is recused from, which answers E04's open question; the quorum override moves to the committee type, a change from the overview to report to the owner), design, threat model, tasks T1–T10, test plan, and flows V–Z recorded as delegated. The recorded run in real clients (T9) needs the owner's Outlook and Gmail mailboxes and an SMTP relay once invitations are on dev.

## 2026-11-19
- E06 plan written (`epic-06-minutes-resolutions/`): research R1, R5 (CGI minute-taking guidance, Companies Act ss.248–249, SS-1, Basel ¶60, Bangladesh Bank's 2025 dissent notice, seven portals) and R9, ADR-0065 (a decision per item with named dissent, votes and the quorum snapshot; resolution numbers per committee and year in agenda order when the meeting ends; memos move on at the meeting's end or at minutes approval per committee type; the format code shared with memos, not the counters, a change from the overview) and ADR-0066 (minutes from the committee type's .docx template with a locked content control per item, edited in ONLYOFFICE, approved on a fixed path per committee type rather than the workflow engine, a change from the overview; PDF/A record and a redacted copy per reader), design, threat model, tasks T1–T12, test plan, and flows AA–AI with nine recommendations for the owner's review before the web tasks.

## 2026-11-21
- E07 plan written under the delegated gate (`epic-07-action-items/`).
  - **Research:** R1 and R7 (CGI 2024 minuting guidance, NHS and public-body action logs, ICSI, Basel 2015, EBA/GL/2021/05, RBI's 2025 "Minutes and Action Taken Report", IIA 15.2 via ACCA, nine portals, NN/g and WAI on older users).
  - **ADR-0067:** one accountable owner (a person or a position), the whole action on the workflow engine as subject `action-item` with verification by someone else, RAG per committee type with words and an override, follow-up memos linked both ways, and a frozen action taken report under "Matters arising". Two refinements of the overview are reported to the owner.
  - **ADR-0068:** engine additions for subject slots, subject due times with re-planning, subject-bound definitions and the initiator taking part.
  - **The other documents:** design, threat model, tasks T1–T12, test plan with the leak-suite surfaces for actions, and flows AJ–AR recorded as delegated.
- E08 plan written under the delegated gate (`epic-08-register-analytics-close/`): research (auditors and ASA 560, Basel ¶60, Bangladesh Bank on dissent, the UK Code's attendance disclosure, BoardPro, Diligent, OnBoard, useful vs vanity measures, older readers and charts), ADR-0069 (the decision register: `search_decision` behind the search functions, one rule with restricted lines, recusals at query time, exports for auditors) and ADR-0070 (five measures computed live per viewer, tables first), design, threat model, tasks T1–T12 with the phase close-out, test plan with the leak-suite surfaces, flows AS–AW recorded as delegated; four refinements of the overview to report to the owner.

## 2026-11-28 — E08 T12: closing the phase
- **Built; the exit waits on the owner's walkthrough** (`retrospective.md`, `overview.md §10`).
  - Exit criteria 1 and 2 are met with evidence.
  - Criterion 3, a representative committee cycle on dev walked through by the owner (A1), waits for the owner.
- **Master-only workflows run by hand on `master` (21786192).**
  - **The weekly `load`, run 46156574343:**
    - **Passed:** `committee-load` (50 people, 10,690 requests, 0 % failed, p95 62 ms), `minutes-volume` (releasing 60 decisions in 2.0 s against 5 s), `actions-volume`, search V1, import and the V2 Gatling job.
    - **Failed:**
      - `committee-volume`: the plan gate at 3,600 saw a sequential scan for every committee's readers on a word in a fifth of the submissions.
      - `meeting-volume`: two volume classes in one database collided on the generator's fixed ids. This has failed every week since 2026-11-22.
  - **The nightly, run 46156577177:** red since 2026-11-18.
    - `build` was cancelled at its 30-minute limit, so the jobs that need the jar were skipped.
    - Jazzer failed again on `=` in a display name, as every night since 2026-11-19.
    - Lighthouse failed on the first page's script: 301 KB against 250 KB, since 2026-11-19.
    - The earlier run on 2026-11-28 also failed on two English visual baselines from before the "Decisions" menu item.
  - **All fixed in #266.**
  - **T5's three items over budget on a loaded PC, as the CI runners measure them:**
    - the 50-person load: p95 48–62 ms for the register, 272 ms for the analytics overview;
    - releasing 60 decisions: 2,024 ms;
    - indexing a recorded decision: p95 2 ms.
    - All are within budget.
- **Fixes:**
  - **#265:**
    - the web's date defaults use the organisation's `today` from the session;
    - actions waiting for verification offer no update and no new date;
    - `MeetingStatus.CLOSED` stays reserved (ADR-0060 amendment);
    - the "Ends on" locator in `committee-cycle.spec`;
    - runbook corrections.
  - **#266:** the master-only failures above.
- **Dev:**
  - "Demo committee history seeded: 15 meetings" is in the log after #261–#263 deployed.
  - `committee-cycle.spec` passed against dev (all fifteen legs).
  - 16 of the other 17 Phase 4 journey tests passed against dev. `action-items.spec`'s demo check found no BRC A2, because on dev the position Head of Credit Risk's holder has no clearance row, so the seeder refused the owner (`OWNER_NOT_CLEARED`).
  - Journey data is left on dev, noted in the retrospective and not deleted.
