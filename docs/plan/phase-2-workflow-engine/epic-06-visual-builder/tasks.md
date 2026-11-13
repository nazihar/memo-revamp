# E06 Visual workflow builder — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **Diagram and Steps view (read-only):** the graph model (`model.ts`: body ↔ graph, round trip kept), automatic top-down layout, a read-only React Flow diagram and the Steps view on the version page beside JSON; `labels` on `VersionResponse`; lint rule against `dangerouslySetInnerHTML`; dependency register rows; checked under the CSP | M | — |
| T2 | **Editing core:** commands with undo and redo (`edit.ts`): add after, connect, remove, rename. Palette; Leads-to selects; live validation (debounced `validate`, problems mapped by JSON pointer to steps and fields); Save draft with If-Match (412 keeps local work); unsaved-changes guard; JSON view parse errors | L | T1 |
| T3 | **Panels, part 1 — who and what:** the builder `catalogue` endpoint (attributes, roles, queues, fragments, step types); assignee panel for every kind with pickers (directory for people, units and positions, catalogue for roles and queues); actions and remarks; forward limits; parallel (mode, n, assignees); DoA; route; sub-workflow | L | T2 |
| T4 | **Panels, part 2 — when and where:** branch conditions (attribute · comparison · value rows ↔ CEL, raw CEL kept when not understood); deadline panel (due, working time, reminders, escalation with target); notify (recipients, message) | M | T3 |
| T5 | **Simulation API:** `DefinitionSimulator` (the pure engine in memory, positive path, stops on block or limit), `WorkflowSubject.ineligibleAt`, `DoaMatcher.matchAt`; `POST …/simulate` with 422 on invalid bodies, the `WORKFLOW_SIMULATION_PER_USER` rate limit, matrix and contract | M | — |
| T6 | **Simulation UI:** sample form (declared attributes, initiator, route when the definition has a route step), run, path on the diagram and in the Steps view, people, blocked reasons, branch reasons and deadlines in words | M | T1, T5 |
| T7 | **Diff and review:** `diff.ts` (steps by id, property paths, labels); the Changes view (diagram colours with icons and words, grouped list); the default view for a reviewer of a version in review, against the published one; compare any two versions | M | T1 |
| T8 | **Accessibility and language pass:** keyboard along connections, focus never hidden, live announcements, `aria-label`s on nodes, 24 px targets, contrast; Arabic (RTL panels, `dir="auto"` names); axe on every view; the manual keyboard and screen-reader checklist in `ux-review.md` | M | T2–T7 |
| T9 | **Close (epic and phase):** exit journey E2E (build with branch, parallel, SLA and escalation → simulate → second admin approves → published → a memo routes through it); phase `demo.md`; ASVS rows; runbook; `retrospective.md`; STATUS; phase exit check | M | all |

T1 comes first. T5 can run beside T1–T4 because it is backend only. T2, then T3, then T4. T6 needs T5. T7 needs only T1. T8 and T9 come last. Each task merges as its own PR when CI is green.

**Why this order:** the read-only diagram (T1) proves the model round trip and the layout before anything can write a body with them. Editing (T2) then has one place to change the graph, and the panels (T3, T4) only fill in properties through it. Simulation and diff read bodies, never write them, so they can follow in any order. The accessibility pass needs every view to exist. The exit journey needs everything.

## Acceptance criteria (selection; the full list is in test-plan.md)
- **T1:** Given any valid schema 2 body, when it is turned into the graph and back, then the result equals the original: steps in order, unknown fields kept. Given "Credit facility", the diagram and the Steps view show the same seven nodes (Start, five steps, End) and the same connections.
- **T2:** Given a draft, when the author adds an Approve step after "Credit review" and leaves it unassigned, then within a second a problem appears on the new step's assignee. When the author undoes, the step is gone and the problem with it. Saving with a stale revision keeps the local graph and offers reload or copy.
- **T3:** Given an author with `workflow.definitions.manage` only, when they open the assignee panel, then they can choose a role and a queue (from the catalogue) and a position (from the directory), without `authz.roles.manage` or `workflow.queues.manage`.
- **T4:** Given the condition `item.classificationRank >= 2`, the simple form shows "Classification rank ≥ 2". Given a condition it cannot read, it shows the CEL unchanged, and saving keeps it byte for byte.
- **T5:** Given "Credit facility" and a sample with classification rank 2, the simulation goes review → committee (director and head of finance) → tell, ended `APPROVED`. With rank 0 it goes review → director. Given an approve step on a position nobody holds, the simulation ends `BLOCKED` at that step with the reason. Nothing is written: row counts are unchanged.
- **T7:** Given version 1 and a version 2 that adds an SLA to "Director" and removes "Tell the author", the diff lists "Director · Deadline: none → 2 working days" and "Tell the author · removed".
- **T9:** The builder exit journey passes in CI on a fresh stack.
