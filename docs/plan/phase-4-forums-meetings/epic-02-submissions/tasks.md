# E02 Submissions and the escalation link — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **The waiting step (workflow):** schema `wait`; `StepKind.FORUM`; the walk stops at a waiting step; commands `ForumDecided` and `ForumDeclined` in the pure engine; `workflow/V16`; published `Workflows.forumDecided/forumDeclined`; event gains `stepId` and `waits`; version-migration fit; FORUM steps in lists, views and simulation; engine property tests | L | — |
| T2 | **Submissions (forum):** `forum/V3` (table, `submission_policy` on the type version); the escalation listener (same transaction, `FORUM_INACTIVE`); `WorkflowInstanceEnded` → WITHDRAWN; triage (accept, defer, return, decline, refer) through the engine commands; `ForumDecisions.decide`; queue and submission API; audit; notifications to the submitter and the secretaries; matrix | L | T1 |
| T3 | **Readers and the memo side:** `memo/V20`, `MemoReaders` (grant and retire `FORUM`), system note-sheet entries for forum events, `GET /memos/{id}/forum-submissions`, "With the X committee" in the inbox and tracking; the waiting reminder in the hourly sweep; the submission policy on the type version and its admin field | M | T2 |
| T4 | **Web** (after the owner's screen review with E03): the Submissions tab with triage dialogs; the forum panel on the memo page; the builder's "Wait for the decision" switch; the committee-type Submissions settings; en/ar | L | T2, T3, E01 T5 |
| T5 | **Close:** the demo "Credit committee paper" type, and the credit workflow escalating above the band to the MCC with `wait: true` (a new published version through maker-checker); journey `committee-escalation.spec`; performance at 50,000 submissions; runbook; ASVS rows; log and STATUS | M | all |

**Order:** T1 → T2 → T3 → T5 (backend); T4 after the owner's review and E01's web. Each task merges as its own PR when CI is green.

## Acceptance criteria (selection)
- **T1:**
  - Given a definition review → escalate-to-forum (`wait`) → approve, when the reviewer approves, the instance has an ACTIVE FORUM step and no open task, and its status is still in review.
  - `ForumDecided(APPROVED)` opens the approve step.
  - `ForumDecided(REJECTED)` ends the instance REJECTED.
  - A definition published before E02 (no `wait`) passes through as before.
- **T2:**
  - Given that escalation, a QUEUED submission exists for the MCC in the same transaction.
  - The MCC secretary accepts it. A plain member gets 404 on the queue.
  - "Return" without a reason answers 422 `REASON_REQUIRED`. With a reason, the memo is RETURNED to its initiator and the submission is RETURNED.
  - Resubmitting creates a new submission whose `previousId` is the first.
- **T3:**
  - While the submission is open, the MCC chair reads the memo. After it closes, they read it only if another way in exists.
  - The memo's note sheet shows "Returned by the Management Credit Committee secretary: reason".
- **T5:** with the keyboard only, a credit officer raises a credit proposal of BDT 6 million. After the head of credit's review it shows "With the Management Credit Committee". The secretary accepts it; a decision recorded through the demo service resumes the route to the director.
