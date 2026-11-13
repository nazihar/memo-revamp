# E03 Definition model and runtime — test plan

Layers per `docs/plan/TESTING_STRATEGY.md`. CI stage: PR unless marked nightly.

| Task | Layer | Tests |
|---|---|---|
| T1 | Unit | `DefinitionValidatorTest`: one positive and one negative case per rule (schema, ids, `next`, reachability, paths to `end`, cycles, limits, `route` count, parallel `n`, fragments, `sign` refused); errors carry JSON pointers |
| T1 | Property | `DefinitionRoundTripProperties` (jqwik): generated valid definitions survive print → parse unchanged; generated graphs with an injected cycle or dead end are always refused |
| T1 | Unit | `DefinitionCompilerTest`: fragment inlining with prefixed ids; schema-1 body upgrade |
| T2 | Unit | `ConditionsTest`: compile success and failures (macro, type, size, undeclared attribute, non-bool), evaluation over each attribute type, missing value → `otherwise` |
| T2 | Fuzz (nightly) | Jazzer target: arbitrary expression text and attribute maps never throw outside the declared errors and finish within the time box |
| T3 | Property | E01's `WorkflowInvariantProperties` widened to generated definitions (route, review, approve, branch, notify): only holders act, terminal states stay terminal, approved only after `end`, earlier rounds never count, `current_assignees` equals the open non-waiting decide tasks plus clarify tasks |
| T3 | Integration | All E01 workflow and memo ITs unchanged; `BranchRoutingIT` (path and recorded `ROUTE`); `NotifyStepIT` (event and notification, no subject in e-mail) |
| T3 | Persistence | Migration V3 on a database with E01 data (backfills, index swap); `JooqCodegenTest` |
| T4 | Unit + property | Parallel completion table (design §4.3), every mode × approve/reject/return; property: a parallel step completes at most once |
| T4 | Concurrency | `ParallelRaceIT`: two simultaneous final approvals → one completion, one 409 (E01 harness) |
| T5 | Integration | `TaskActionsIT`: send back (allowed and refused targets), forward and delegate (eligibility, initiator refused, acting-for recorded), ask/answer (waiting refusal), refer/opinion (opinions cancelled when the step ends), allowed-actions and mandatory-remark refusals |
| T6 | Integration | `ResubmitPoliciesIT` (restart, resume, skip-unchanged with same and changed hash), `RecallCancelIT` (recall allowed and refused; cancel by admin, refused for others) |
| T7 | Integration | `DefinitionLifecycleIT`: every transition allowed and forbidden (state machine), maker ≠ checker, step-up, published immutable, retire refused while a memo type uses it; `VersionPinningIT`; memo type with `FIXED`/`BOTH` policies |
| T8 | Integration | `InstanceMigrationIT`: preview reasons, partial migration, `MIGRATE` rows, audit per instance, idempotent retry |
| T9 | Integration | events published once per transition with `tenantId`; `AuditActionCoverageTest` covers every new audit action |
| all | Authorisation matrix | `p2-e03-workflow.yaml`: every new endpoint × anonymous, user, other-tenant user, tenant admin without step-up, tenant admin with step-up |
| all | Contract | OpenAPI drift check; generated web client |
| T10 | Web unit + a11y | Workflows list/definition/review/migration pages, memo view actions (send back target choice, refer people picker, answer and opinion forms), inbox kinds; axe on each; en/ar and pseudo-locale |
| T11 | E2E | `workflow-definitions.spec.ts`: admin A imports a definition with a branch and a parallel step, admin B approves it, a memo routes through it; `memo-actions.spec.ts`: send back, refer and opinion, ask and answer |
| T11 | Performance | Task action with branch evaluation p95 < 250 ms in `MemoPerformanceIT` style; migration preview of 500 instances < 2 s |

Seed data: two tenant admins (maker and checker) and five staff in the demo tenant; demo definitions "Department review then approvers" (review + route) and "Capital expenditure" (branch + parallel).
