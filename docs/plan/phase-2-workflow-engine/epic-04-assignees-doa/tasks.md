# E04 Assignees, routing rules and DoA — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **Read facades:** `org.OrgDirectory` (holders on a date, a user's position, unit head, ancestors, reporting line N up) and `common.authz.RoleHolders`; Modulith named interfaces; tests over seeded org data | S | — |
| T2 | **Resolvers:** assignee kinds `position`, `supervisor`, `unitHead`, `role`; the engine's resolver port; `workflow_step.resolution`; nullable assignee and `UNRESOLVED`; `fallback`; validator problems for unknown references; history "resolved to" | L | T1 |
| T3 | **Several candidates, queues, claim and release:** mode-any tasks for multi-person resolutions; queues and members (admin API); `claim`, `release`; inbox "claimed by" | M | T2 |
| T4 | **Separation of duties:** SOD-1…SOD-4 at route validation and resolution; declared interests on memos; `recuse`; exclusions in `resolution`; tenant setting for SOD-2 | M | T2 |
| T5 | **Substitution and staff changes:** `workflow_substitution` (admin API, apply), substitutes at resolution (acting for); re-resolution on `OrgChanged`; blocking on `UserDeactivated` | M | T2, T4 |
| T6 | **DoA:** versions and rules with maker-checker and effective dates; `doa` step (groups in order, `needed`); instance pins the version; simulator API | L | T2, T3 |
| T7 | **Extended routes and policies:** route entries person / position / group; memo-type allowed approvers and DoA check at submit; initiator DoA preview; ad-hoc forward limits | M | T2, T6 |
| T8 | **Web (work):** memo view resolution, claim / release, recuse, interests, DoA preview; route editor with positions and groups; inbox candidates and acting-for | L | T3–T7 |
| T9 | **Web (admin):** DoA versions, rules, review and simulator; queues; memo type allowed approvers and DoA check; substitutions list | M | T3, T5, T6 |
| T10 | **Close:** matrix `p2-e04-assignees.yaml`, E2E journeys (a position-resolved step re-resolving after a holder change; a DoA memo with two groups; claim and release), demo DoA and queue, ASVS rows, runbook, STATUS, epic log | M | all |

T1 first; T3, T4 and T6 can run after T2 in any order; each task merges as its own PR when CI is green.

## Acceptance criteria (selection; full list in test-plan.md)
- **T2:** Given a `supervisor` step with `levels: 2`, the task goes to the holder of the position two reporting lines above the initiator's, and the step's resolution records both positions. Given a vacant position and no fallback, the step is held by an `UNRESOLVED` task and the reassigners are told; the instance never advances past it.
- **T3:** Given a `role` step resolving to three people, each sees the task; when one claims, the others cannot act; after release, any can; the first decision completes the step and cancels the rest.
- **T4:** Given a person who approved step 1 and is the holder resolved for step 3, they are excluded and the step goes to the fallback or is unresolved; given a declared interest, the person is never a candidate; a recusal re-resolves without them.
- **T5:** Given a substitution in force, a new task for the principal goes to the substitute, and the approval records acting for the principal; given a holder change, the open position task moves to the new holder with a history entry.
- **T6:** Given a DoA version published by B after A submitted it, effective today, a memo matching "2 of {DMD, AMD}" needs two approvals from those holders; a memo submitted before the version took effect keeps the old version.
- **T7:** Given a memo type with DoA check on, a route missing a required group is refused at submit with the group named.
