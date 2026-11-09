---
status: accepted
date: 2026-11-09
deciders: Product owner (gate delegated, Phase 2 overview Q1), Claude (agent)
---

# 0035 — Workflow data model: instances, rounds, steps, tasks; guarded transitions with idempotency keys

## Context and problem statement
The first slice must run on the tables the full engine uses (MASTER_PLAN Phase 2: "later features extend it rather than replace it"), with no double actions, rounds in one instance, and a fast inbox. Research R3, R5.

## Decision drivers
- One approval per double click, retry or second device
- Earlier rounds visible, never counted
- E03 adds parallel steps, branches and sub-workflows without a table rewrite
- Inbox p95 < 300 ms at V2 volume (NFR P1, E05)
- Append-only history (AGENTS §3.3)

## Considered options
1. Normalised instance → round → step → task, with an append-only action log, guarded updates and idempotency keys
2. One event-sourced log per instance, state derived on read
3. A single "current step" column on the instance, history only in audit

## Decision outcome
Chosen option 1.

**Tables** (all with `tenant_id`, forced RLS, `tenant_id` first in every index, UUIDv7 ids):
- `workflow_definition` (key, name, built-in flag) and `workflow_definition_version` (number, JSON body, schema version, state; E01 ships one published built-in version, `sequential-approval` v1, whose steps come from the route).
- `workflow_instance`: subject type and id, definition version (pinned), state (`RUNNING`, `APPROVED`, `REJECTED`, `WITHDRAWN`, `RETURNED`), current round number, `version` for optimistic locking, and the denormalised current step and assignees for the inbox.
- `workflow_round`: number, started by, started at, ended by action, ended at, outcome. Exactly one open round while running.
- `workflow_step`: per round, ordinal, type (`APPROVE` in E01), state (`PENDING`, `ACTIVE`, `DONE`, `SKIPPED`), assignee spec (E01: a person). Steps are copied into each round from the route at round start, so a route edited between rounds never changes a past round.
- `workflow_task`: per step, assignee, state (`OPEN`, `COMPLETED`, `CANCELLED`, `REASSIGNED`), created and completed times, `version`. The inbox reads `(tenant_id, assignee_id, state)`.
- `workflow_action`: append-only (trigger refuses update and delete), numbered per instance (`seq`): instance, round, step, task, action (`SUBMIT`, `APPROVE`, `RETURN`, `REJECT`, `WITHDRAW`, `RESUBMIT`, `REASSIGN`), actor, acting-for (null in E01, filled from E04), remark, subject snapshot (JSON from the subject callback; for memos the document version id and SHA-256), occurred at.
- **The route is the subject's input, not a workflow table.** For an initiator-defined route the subject passes the ordered approver list with submit and resubmit; the engine copies it into the new round's steps. The memo stores its route (`memo_route_entry`), editable only in Draft and Returned.

**Transitions:** each action loads the instance `FOR UPDATE`, applies the pure transition function (ADR-0034), and writes with guarded updates (`WHERE id = ? AND state = ? AND version = ?`). Zero rows → `409 conflict` with the current state. The whole action runs inside `system.Idempotency.once` with a **required** `Idempotency-Key`. The stored fingerprint includes the actor, so a key reused by another user is a 422, not a replay.

**Rounds:** submit opens round 1; return ends the round with outcome `RETURNED` and sets the instance `RETURNED`; resubmit opens round N+1 with steps copied from the route passed in, starting at the first step (the only resubmission policy in E01). The final-approval check counts only `APPROVE` actions of the current round's steps.

**Events** (Spring Modulith, carrying `tenantId`): `WorkflowTaskCreated`, `WorkflowTaskCompleted`, `WorkflowRoundEnded`, `WorkflowInstanceEnded`. Subjects and notifications listen to them. The subject's synchronous callback runs before commit, so a veto rolls the action back.

### Consequences
- Good: the history is queryable without replaying events; the inbox is one indexed read; E03's parallel steps are more tasks per step and more steps per round, with no new tables.
- Good: retries replay, races conflict, and both are testable with a latch.
- Bad: the denormalised current-assignee columns must be kept in step with tasks in every transition (one code path, covered by the invariant property tests).
- Follow-ups: E03 adds definition lifecycle states and step types; E04 fills `acting_for`; E05 adds due times to tasks.

### Confirmation
Property tests over random action sequences (only the current assignee acts; earlier rounds never count; Approved only after the last approver of the current round; terminal states are terminal; the denormalised columns equal what the tasks say). Concurrency tests with latches (double click, two devices, resubmit vs withdraw). Trigger test for the append-only action log. RLS meta-test.

## Pros and cons of the options
### Normalised with guarded updates
- Good, because it is queryable, indexable, and fits E03's step types.
- Bad, because there are more tables to keep consistent.

### Event-sourced
- Good, because the history is the source of truth by construction.
- Bad, because the inbox and "who holds it now" need projections anyway, rebuilding state on every action costs, and the audit chain already gives an append-only trail.

### Single current-step column
- Good, because it is simplest for the first slice.
- Bad, because it cannot express rounds, parallel steps or per-step history, so E03 would replace it, which the master plan forbids.

## More information
Research R3 (IETF Idempotency-Key draft 08), R5 (rounds in other products).
