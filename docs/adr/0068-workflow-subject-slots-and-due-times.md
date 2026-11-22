---
status: accepted
date: 2026-11-21
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); amends ADR-0038 (definitions) and ADR-0041 (timers), additive only
---

# 0068 — Workflow engine: people named by the subject, due times set by the subject, subject-bound definitions

## Context and problem statement
ADR-0067 runs action items on the engine as the subject `action-item`. Today the engine cannot express four things that subject needs:
1. **Who a step goes to, named by the subject.** An action's owner is chosen per action by the secretary (a person or a position). Its verifier is the committee's secretaries or chair, a committee role the organisation model does not know. Assignees today are persons, positions, supervisors, unit heads, org roles and queues fixed in the definition, or the route the initiator supplies at start.
2. **A due time set by the subject.** ADR-0041 counts `sla.due` as a duration from the task's creation. An action is due on a date the meeting set, which can be months away, and that date changes when an extension is agreed.
3. **Rules that hold only for one subject's definitions**, for example that an action definition starts with the owner's step and never offers REJECT.
4. **The initiator taking part.** SOD-1 removes the initiator from every step. The secretary who issues an action may own it or verify others' actions; for actions, independence is "the verifier is not the owner".

## Decision drivers
- Additive only: every stored definition and running instance behaves as before (ADR-0038's pinning, Phase 2's property tests).
- General, not action-specific: minutes, E08 or later subjects can use the same additions.
- No change to who may act on a task, or to the engine's invariants.
- No migration if avoidable.

## Considered options
1. **Four small, general additions behind the `WorkflowSubject` interface** (chosen).
2. Action-specific engine code (a "deadline step").
3. Start each instance with a route of [owner, verifier] (ADR-0040 route entries) and keep timers outside the engine.

## Decision outcome
Chosen option 1, because each addition is optional, general and described by the subject. A definition that uses none of them is unchanged.

1. **Assignee kind `subject` with a `slot`** (`{"kind": "subject", "slot": "owner"}`):
   - The engine asks `WorkflowSubject.slot(subjectId, slot): List<SlotHolder>`, where `SlotHolder` is `Person(userId)` or `Position(positionId)`, when the step activates and whenever it re-resolves.
   - A position is resolved, recorded and re-resolved on holder change exactly as a POSITION assignee.
   - Several people form a pool, as a role or queue does: claims and release as ADR-0040.
   - The usual exclusions (SOD-2, SOD-4, eligibility) and the step's `fallback` apply.
   - A slot may also be an escalation's `to`.
   - The subject declares its slot names (`WorkflowSubject.slots`). The validator refuses unknown slots (`UNKNOWN_SLOT`).
2. **`sla.from: "subject"`** instead of `sla.due`:
   - The task's due time is `WorkflowSubject.dueAt(subjectId, nodeId): Instant?` (null means no due time and no timers).
   - `remind.before`, `remind.after` and `escalate.after` are counted from it in working time as today.
   - **`Workflows.redue(subjectType, subjectId)`** (published) re-reads the due time for the subject's open tasks, cancels their pending timers and plans new ones, in the caller's transaction.
     - A reminder whose time has passed is skipped. A DUE whose time has passed fires at once.
     - It records no engine action. The subject audits the reason.
   - A step that runs again (after a send-back) reads the subject's due time again.
3. **A definition's optional `subject`** (top-level key):
   - Such a definition starts only for that subject type (`WRONG_SUBJECT`).
   - The builder offers that subject's slots and attributes.
   - **`WorkflowSubject.checkDefinition(definition): List<DefinitionProblem>`** (default: none) adds the subject's rules at submit and publish.
   - Definitions without `subject` cannot use `subject` assignees or `sla.from` (`SUBJECT_REQUIRED`).
4. **`WorkflowSubject.initiatorTakesPart`** (default false): when true, SOD-1 does not exclude the initiator from that subject's steps. SOD-2 and SOD-4 still apply.

Also: `WorkflowTaskCreated` gains a nullable `nodeId`, so subjects know a task's step without a query. This is additive in an in-process event.

### Consequences
- Good:
  - Actions use the engine's timers, substitutes, holder changes, pools, separation of duties and builder unchanged.
  - Memo definitions and instances are untouched.
  - The additions are reusable.
  - No migration: these are JSON keys in the stored definition and code.
- Bad:
  - The schema grows by one optional key per step type and one top-level key.
  - The builder needs a subject picker for new definitions.
  - Long-lived tasks (months) are now normal. The timer rows are the same, and their volume is small (a few per open action).
  - `redue` is a new way to change timers. It is covered by tests and audited by the subject.
- Follow-ups: the builder's simulation (Phase 2 E06) learns slots by asking the subject for example holders (`ineligibleAt`'s twin), or shows "Owner (set per action)".

### Confirmation
- `DefinitionValidatorTest` gains rows for `UNKNOWN_SLOT`, `SUBJECT_REQUIRED` and `WRONG_SUBJECT`, and for a subject's own problems.
- `SubjectSlotIT`: person, position with a holder change, a pool with claim, an escalation to a slot, SOD-2 in a pool.
- `SubjectDueIT`: due from the subject, `redue` forward and back, timers in the past, a send-back reading the date again, on `MutableClock`.
- The engine's property suite and the Phase 2 suites unchanged and green.
- A definition stored before this ADR round-trips unchanged (golden JSON).

## Pros and cons of the options
### 1. General additions behind `WorkflowSubject`
- Good, because they are additive, reusable and testable alone.
- Bad, because there are four changes in one task.

### 2. Action-specific code
- Good, because it is narrow.
- Bad, because the engine knows a business module, against ADR-0034's subject boundary.

### 3. A route of [owner, verifier] with timers outside
- Good, because there is no engine change for the people.
- Bad, because SEND_BACK and RETURN semantics fit badly, and a position owner would not follow its holder after start.
- Bad, because it still leaves the due date outside the engine.

## More information
- ADR-0034, ADR-0035, ADR-0038, ADR-0040, ADR-0041, ADR-0067.
- Design: `phase-4-forums-meetings/epic-07-action-items/design.md §2.2`.

## Amendment (2026-11-22, E07 T4): runs again, and resolving again
Two more general additions, found while building E07 T4. Both are behind `WorkflowSubject` or `Workflows`, and memo types keep today's behaviour.

- **An item that runs again** (`WorkflowSubject.runsAgain`, default false). A reopened action (ADR-0067, design E07 §1.10) gets a new instance, but the engine kept one instance per item for its whole life (design E01 §2, invariant 1; `UNIQUE (tenant_id, subject_type, subject_id)`).
  - `workflow/V17__subject_runs.sql` numbers an item's instances as runs (`run`, 1 for every existing instance). The uniqueness moves to `(tenant_id, subject_type, subject_id, run)`, and a partial unique index keeps at most one running instance per item.
  - `Workflows.start` starts the next run when the subject type runs again and the item's last instance ended. Otherwise it refuses `ALREADY_STARTED`, as before. Every read by subject (`viewOf`, `dueOf`, `redue`, the task descriptions, the holders) follows the newest run.
  - This is expand only: a column with a default, and the constraint replaced. No row is rewritten. An older application beside this one reads an item's instance as before, because no item has a second run until this release reopens an action.
- **Resolving again for the subject** (`WorkflowResolutions.reresolve(subjectType, subjectId)`, a published service beside `Workflows`). When an action's owner or contributors change (design E07 §1.3), each active step of the running instance with an open decision resolves again, with its slot read anew. This is the holder-change path of ADR-0040, recorded as a reassignment with the reason `SUBJECT_CHANGED` (`Reresolve` gained a reason).
  - The new owner gets the owner's task: the person, the position's holder, or their substitute.
  - A contributor added during verification leaves the verifier pool.
  - It needs a signed-in user, who is recorded as the actor.
- **Confirmation:** `CancelReopenIT` (a reopened action on its second run, marked done again), `WorkflowServiceIT` (`ALREADY_STARTED` unchanged for an item that does not run again) and `OwnerChangeIT` (person to position and back; the verifier pool drawn again). The memo and workflow suites run unchanged.
