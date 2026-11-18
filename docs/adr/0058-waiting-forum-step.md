---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1, Q3); product owner aradnazihar
---

# 0058 — The waiting forum step: a memo waits for the committee's decision

## Context and problem statement
Phase 4 Q3 decided that a memo escalated to a committee waits for its decision. Today the `escalate-to-forum` step only records an action, publishes an event and walks on (ADR-0038, Phase 2 E03). A credit proposal above the delegated authority could therefore be approved before the committee met. The engine is a pure transition over an instance's state (ADR-0034, ADR-0035), and published definitions pin their running instances (ADR-0038), so the change must not alter what a running instance means. Research: `phase-4-forums-meetings/epic-02-submissions/research.md §3.3, §4`.

## Decision drivers
- The route cannot end before the committee decides; the decision comes back to the memo.
- The engine stays the single writer of instance state, through commands.
- Running instances and published definitions keep their meaning.
- Fixed meanings: a tenant label never changes what an outcome does (ADR-0056).

## Considered options
1. **An active step with no task, resumed by an engine command** (chosen).
2. A synthetic task held by the forum's secretary, which they "approve" for the committee.
3. Keep the pass-through and link the outcome afterwards.

## Decision outcome
Chosen option 1.

- **Definition:** `escalate-to-forum` gains `"wait": boolean`.
  - Absent means `false`, today's pass-through, so every published definition and pinned instance keeps its meaning.
  - The builder writes `true` for new steps. "For information" steps write `false`.
  - A waiting step must have a `next`, as today.
- **Runtime:** entering a waiting step creates a step of the new kind `FORUM`, `ACTIVE`, with no task.
  - Its `resolution` holds `{forum, submissionId}` once the forum module creates the submission.
  - The engine records `ESCALATE_TO_FORUM` and emits `Effect.EscalateToForum(nodeId, forum, stepId, wait = true)`, and the walk stops there.
  - SLA timers on the step work as on other steps. Escalation notifies, and reassignment does not apply.
- **New commands** (system-issued by the forum module, with the forum officer as actor for the record):
  - `ForumDecided(stepId, outcome, remark)`, where `outcome` is APPROVED, APPROVED_WITH_CONDITIONS, NOTED, REJECTED or RETURNED:
    - APPROVED, APPROVED_WITH_CONDITIONS and NOTED: the step is DONE, recorded as `FORUM_DECIDED`, and the walk continues to `next`.
    - REJECTED: the round and instance end as REJECTED, as a reject action would.
    - RETURNED: the instance returns to the initiator, as a return would. Resubmitting starts round N+1, which walks the route again and creates a new submission linked to the earlier one.
  - `ForumDeclined(stepId, remark)`: the committee will not consider it. The memo is returned to the initiator with the reason.
- **Statuses that keep the step waiting:** DEFERRED, REFERRED (a new submission to another forum, linked) and ACCEPTED need no engine command.
- **Initiator actions:** withdrawing the memo while it waits is the existing WITHDRAW. The forum module hears `WorkflowInstanceEnded` and closes the submission as WITHDRAWN.
- **Lists:** a FORUM step shows its forum's name, for example "With the Management Credit Committee". It has no assignee, so no inbox task.
- **Version migration:** an instance waiting at a forum step can move to a newer version only if that version has the same step id with `wait: true` (ADR-0038's "open steps still fit").

### Consequences
- Good: the route cannot finish without the decision. The record shows the committee's decision as its own action. No secretary approves "on behalf of" the committee.
- Good: existing definitions are untouched (absent `wait` means `false`).
- Bad: a new step kind that every list and view must handle (inbox, tracking, simulation, builder). The tests cover each.
- Follow-up: branching on the outcome (CEL `item.forumOutcome`) is not built. The fixed mapping covers the charters found (approve, reject, return). It can be added when a customer needs a different route per outcome.
- Follow-up: E06 issues `ForumDecided` from recorded decisions. E02 provides the command, the service and the secretary's triage.

### Confirmation
- Engine property tests: a waiting step never completes without `ForumDecided`, `ForumDeclined` or a withdrawal. Every outcome reaches its documented state. Pinned instances without `wait` pass through as before.
- `ForumEscalationIT` covers the whole path from submit to resume.
- The builder's round-trip test covers `wait`.

## Pros and cons of the options
- **Option 1:** + correct record, pure engine, no fake tasks; − a new step kind to render everywhere.
- **Option 2:** + reuses tasks; − the secretary appears to decide for the committee, delegation and separation-of-duties rules misfire, and inbox noise.
- **Option 3:** + nothing to build; − breaks Phase 4 Q3 and the exit criterion's chain.
