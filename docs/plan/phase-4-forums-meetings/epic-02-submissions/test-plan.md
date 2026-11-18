# E02 Submissions and the escalation link — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`.

## Scope and risks
- **Risks covered:**
  - a route finishing without the committee;
  - a referral lost from the queue;
  - double decisions;
  - officers reading memos without a reason;
  - pinned definitions changing meaning.
- **Seed:** the demo committees (E01 T6), the credit workflow with a waiting step (T5), `secretary@`, `head.credit@`, `director@`, `user@`.

## Tests per slice
| Task | Layer | Tests |
|---|---|---|
| T1 | Unit + property | `ForumStepTest`: enter, wait, each outcome, decline, withdraw while waiting, cancel; `ForumStepProperties`: no sequence of task commands completes a waiting step; every outcome reaches its documented state; `wait` absent passes through |
| T1 | Integration | `ForumWaitIT`: the service writes the FORUM step, the event carries `stepId`, `forumDecided` resumes; version migration refuses an instance waiting at a step the target lacks |
| T1 | Unit | Definition schema and builder round-trip with `wait` (web unit) |
| T2 | Integration | `ForumSubmissionsIT`: created with the step (rollback leaves neither); `FORUM_INACTIVE`; each triage action and forbidden transition (`WRONG_STATE`); reasons required; refer to another forum; decide; withdraw and cancel close as WITHDRAWN; lateness against the target date; audit for each |
| T2 | Concurrency | Two parallel accepts with the same version: one wins; the same idempotency key twice: once |
| T2 | Authorisation matrix | `p4-e02-submissions.yaml`: every endpoint × secretary (own and other forum), chair, `forums.manage`, member, initiator, memo reader, other, plus the shared subjects |
| T2 | Persistence/RLS | Cross-tenant: tenant A cannot read or triage tenant B's submissions; no DELETE |
| T3 | Integration | `ForumReadersIT`: officers read while open and lose access on close; clearance still applies; the queue hides subjects from non-readers; note-sheet system entries; the inbox shows the forum's name; the waiting reminder once |
| T4 | Web unit and E2E | Triage dialogs (reason required), the forum panel timeline, the builder switch; `committee-escalation.spec` |
| T5 | Performance | `SubmissionQueuePerformanceIT`: 50,000 submissions, `EXPLAIN` on the queue and memo panel queries |

## State-machine tests
Every allowed and forbidden transition of design §1 (waiting step and submission).

## Security tests
- 404 for an invisible forum or submission.
- No public decide endpoint.
- Email subjects without parameters.

## Exit checklist
- [ ] All of the above green.
- [ ] Matrix complete.
- [ ] Journey on dev.
- [ ] ASVS rows.
- [ ] Runbook.
- [ ] Owner's screen review recorded (with E03).
