# E03 Definition model and runtime — tasks

Vertical slices in order. Each one leaves `master` releasable and E01 behaviour unchanged.

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Definition model:** schema-2 types, JSON Schema, `DefinitionValidator` (every rule of design §2 with JSON pointers), compiler (fragment inlining, schema-1 upgrade), round-trip property test; dependency `com.networknt:json-schema-validator` | M | — |
| T2 | **Conditions:** `dev.cel:cel` with macros off, declared attributes, compile errors with positions, limits, evaluation with recorded inputs; `WorkflowSubject.attributes()` and the memo's catalogue (`memoType`, `classificationRank`); Jazzer fuzz target | M | T1 |
| T3 | **Runtime on compiled definitions:** migration V3; engine state with node ids and route index; advancing through route, review, approve, branch, notify, escalate-to-forum and `end`; `Outcome.actions` as a list; E01 property tests and ITs green on the upgraded built-in; `RECOMMEND` | L | T1, T2 |
| T4 | **Parallel steps:** all / any / n-of-m, reject and return rules, step-completion guard, concurrency test with two simultaneous approvals | M | T3 |
| T5 | **Consultation and hand-over actions:** send back, forward, delegate (acting-for recorded), ask/answer, refer/opinion; per-step allowed actions and mandatory remarks; refusals; memo endpoints and view fields | L | T3 |
| T6 | **Round control:** resubmission policies `resume` and `skip-unchanged` (`CARRY`), recall, cancellation | M | T3 |
| T7 | **Definition lifecycle:** admin API (create, import, new draft, validate, submit, approve with maker-checker and step-up, send back, retire, export), permissions, audit; memo types choose a published definition and `FIXED`/`BOTH` route policies become real; pinning test | L | T1 |
| T8 | **In-flight migration:** preview and execute with per-instance `MIGRATE` and audit | M | T3, T7 |
| T9 | **Events and history:** `WorkflowStepCompleted`, `WorkflowInstanceEnded`, `WorkflowNotifyRequested` (memo sends the notification), `WorkflowEscalatedToForum`, `WorkflowEscalated` type; `current_step_name`; history entries for every new kind | S | T3–T6 |
| T10 | **Web:** admin workflows screens (list, definition, import/export, review, migration), memo type definition choice, memo view actions and panels, inbox task kinds, en/ar | L | T5–T8 |
| T11 | **Close:** matrix `p2-e03-workflow.yaml`, E2E journeys (fixed definition with a branch and a parallel step; send back and refer), demo tenant definitions, ASVS rows, runbook, STATUS, epic log | M | all |

T1 and T2 can run together; T4, T5, T6 and T7 can run in parallel after T3 (T7 needs only T1). Each task merges as its own PR when CI is green.

## Acceptance criteria (selection; full list in test-plan.md)
- **T1:** Given a definition with an unreachable step, a cycle, an unknown `next`, two `route` steps or a condition over an undeclared attribute, validation fails with one error per problem and its JSON pointer. Given any valid definition, parse(print(d)) = d.
- **T2:** Given `item.amount > 1000` with `amount` declared as `int`, it compiles; with a macro (`[1,2].all(x, x > 0)`), a non-boolean result or 1,001 characters, compilation fails with a message naming the problem.
- **T3:** Given E01 data, every E01 test passes unchanged. Given a definition review → branch → approve, the item reaches the approver chosen by the condition and the history has a `ROUTE` row with the inputs.
- **T4:** Given `n-of-m` with n = 2 of 3, when two approve the step completes and the third task is cancelled; when two reject, the instance is rejected; two simultaneous last approvals produce one completion and one 409.
- **T5:** Given a send-back to an earlier step, that step's assignee gets a new task and later steps run again; given a delegation, the delegate's approval is recorded acting for the original holder; given an open clarification, the asker cannot decide until it is answered.
- **T6:** Given `skip-unchanged` and an unchanged document, approvers who approved in round 1 are carried in round 2 with a `CARRY` action; given a changed document, nobody is carried.
- **T7:** Given a version submitted by A, A cannot approve it; B can, with step-up; the previous version is retired; an instance started on the previous version stays on it.
- **T8:** Given instances on v1 and a v2 that removed the step one of them is at, the preview marks that instance not migratable with the reason, and migration moves only the others, each with a `MIGRATE` history row and an audit entry.
