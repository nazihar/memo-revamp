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
