# E06 Visual workflow builder — design

The builder is a new editor for the **same** definition body (schema 2, ADR-0038), on the **same** lifecycle API (E03): drafts, If-Match, submit, approve with a step-up, send back. It adds three things:
- a diagram and a step list that mirror each other, both editable;
- a simulation of a whole definition against sample values;
- a structural diff shown in the maker-checker review.

The server stays the single authority on what a valid definition is. The choices are recorded in ADR-0042.

## 1. Module boundaries
- **web** (`features/workflows/builder/`): the graph model, layout, diagram, Steps view, panels, diff and simulation UI. Pure TypeScript is kept apart from React so it can be tested without a browser: `model.ts` (body ↔ graph), `edit.ts` (commands and undo), `diff.ts`, `layout.ts`.
- **workflow** (backend):
  - `DefinitionSimulator`, which runs the pure engine in memory;
  - the `simulate` endpoint;
  - the builder `catalogue` endpoint;
  - names for the ids in a version (`labels`).

  No new tables and no new events.
- **memo**: `WorkflowSubject.ineligibleAt(attributes, people)`, i.e. who could not hold a task on an item with these attribute values (inactive, or not cleared for the sample classification). It is the subject-free twin of `ineligible`.
- **org / authz**: unchanged. Pickers for people, units and positions use the directory API (`directory.read`). Roles and queues come through the catalogue, so a workflow author needs neither `authz.roles.manage` nor `workflow.queues.manage`.

## 2. Graph model (web)
- **Nodes:**
  - one per step, keyed by step id (stable, so it is also the diff key);
  - a **Start** node, pointing at `start`;
  - an **End** node, for `"end"`.
- **Edges**, derived from the body and never stored separately:
  - `next` → one edge;
  - a branch's `branches[i]` → an edge labelled with its condition (as words where the condition editor can read it, otherwise the CEL text);
  - `otherwise` → an edge labelled "Otherwise".
  - A parallel step is **one** node with its assignees inside, because the engine treats it as one step with several tasks.
- **Layout:** automatic, top-down, recomputed after every structural change (ADR-0042). Positions are **not stored**: the body stays the only source, diffs show no layout noise, and nothing needs dragging (WCAG 2.5.7). Moving a node by hand is not offered.
- **Round trip:** `toBody(toGraph(body))` equals `body` for every valid body, including the order of steps and unknown fields (kept verbatim). This is property-tested over generated definitions.

## 3. Editing
- **Commands**, each one undoable (the undo stack lives in the page, about 100 entries):
  - add step after X;
  - add branch;
  - connect X → Y;
  - remove step: its incoming edges are re-pointed to its `next` when it has exactly one, otherwise they are left dangling and validation says so;
  - rename;
  - set a property.
- **Panels:** one form per step type, built on the existing form controls.
  - Common fields: name, actions offered, remarks required, forward limit.
  - Assignee: kind, then the matching picker. Person: user search. Position: unit, then position. Supervisor: levels. Unit head: unit or the initiator's, plus levels up. Role: role from the catalogue, plus a unit or the initiator's. Queue: from the catalogue.
  - Parallel: mode (all, any, n of m), n, and 2 to 20 assignees.
  - Notify: recipients and message key.
  - Sub-workflow: a published fragment from the catalogue.
  - Branch: conditions in order, then otherwise.
  - Deadline (on review, approve, parallel, DoA and route): due, working time, reminder before, up to three reminders after, and escalation (after, action, and target when reassigning).
- **Conditions:**
  - The simple form is *attribute · comparison · value* rows joined by "all of" or "any of". Attributes and their types come from the catalogue; comparisons follow the type (`==`, `!=`, `<`, `<=`, `>`, `>=`, and "is one of" for lists). It writes CEL such as `item.classificationRank >= 2 && item.memoTypeId == "…"`.
  - A condition the simple form cannot read opens in the CEL text field, unchanged. The simple form never rewrites a condition it did not write.
- **Validation as you edit:**
  - After 500 ms without a change, the builder posts the unsaved body to the existing `POST …/versions/{n}/validate`. One request is in flight at a time and only the newest answer counts.
  - Each problem's JSON pointer (`/steps/3/sla/remind/before`) is mapped to its step and field: a badge on the node and the row, an error on the field, and a list under the diagram.
  - The server runs the same JSON Schema and semantic checks as on submit (unreachable steps, cycles, dead ends, unknown attributes, SLA rules), so there is one rule set.
- **Save:** an explicit **Save draft** sends `PUT …/versions/{n}` with If-Match.
  - On 412 the builder keeps the local graph and offers to reload (losing the local changes) or copy the JSON.
  - Leaving the page with unsaved changes asks first, through the router's blocker.
  - There is no autosave, which keeps the audit trail to one row per real save.
- **JSON view:** stays as the expert path. Switching from JSON to Diagram parses the text; a parse error keeps the user in JSON.

## 4. Simulation
`POST /api/v1/admin/workflow-definitions/{key}/versions/{number}/simulate`

The request:

```json
{ "body": { … }?, "initiatorId": "uuid", "attributes": { "classificationRank": 2, "memoTypeId": "uuid" },
  "route": [ RouteEntry ]? }
```

- `body` is optional: an unsaved draft. If it is left out, the stored version is used.
- The body is validated first. If invalid, the answer is 422 with the problems; nothing runs.
- `DefinitionSimulator` builds a `TransitionContext` the way `WorkflowRunner.transition` does, but from the sample:
  - eligibility from `WorkflowSubject.ineligibleAt(attributes, people)`;
  - attributes from the request;
  - the organisation's real resolvers, substitutes, settings and forward limits;
  - the DoA in force today, matched on the sample. This is `DoaMatcher.matchAt(subjectType, attributes, initiator)`, a public twin of the private match that `simulate` already uses;
  - no interests (there is no item).
- It calls `Transition.start`. Then, for as long as the instance runs, it applies the **positive action** (Approve or Recommend) to each open decision task as its holder, oldest first. A parallel step's tasks are all decided. A DoA group is decided by as many people as it needs.
- It stops when the instance ends, when a task is blocked (nobody eligible), or after 500 commands (`SIMULATION_LIMIT`).
- Nothing is written: no ids are persisted, no events, no audit. Ids come from a local generator.

The response:
- `steps[]`, in the order they were reached. Each has:
  - the step id, name and type;
  - an `outcome`: `DECIDED`, `BLOCKED`, `NOTIFIED`, `BRANCHED`, or `PASSED` (automatic);
  - `people` (`Person`: id and name), with `actingFor` when a substitute would get the task;
  - `blockedReason`;
  - for a branch, `branch`: the condition that matched (or "otherwise") and the step it led to;
  - the step's `sla` as written.
- `ended`: `APPROVED`, `BLOCKED` or `LIMIT`.

The page shows the path on the diagram and in the Steps view, and puts the deadline into words. There are no dates, because every simulated step would "start now".

Permission: `workflow.definitions.manage` or `.approve`. Rate limit `RateLimit.WORKFLOW_SIMULATION_PER_USER`: 30 a minute per user. It is a read: no audit row, the same as the DoA simulator.

## 5. Catalogue and labels
- `GET /api/v1/admin/workflow-builder/catalogue`, for `.manage` or `.approve`, returns the following. It is not under `workflow-definitions/`, where `catalogue` could be a definition's key.
  - `subjectTypes`: the type, plus its attributes with their types (from `WorkflowSubject.attributes`);
  - `roles`: id and name;
  - `queues`: key and name;
  - `fragments`: key and name of published fragments;
  - `stepTypes`: those the builder offers. `sign` is left out until Phase 5.
- `VersionResponse` gains `labels: Map<UUID, String>`. It names every person, position, unit and role id the body mentions, so panels and the diff show names without one request per id. Ids that no longer exist are missing from the map, and the page shows "Unknown (id …)".

## 6. Diff (web)
`diff(before, after)` works over the two bodies, with steps matched by id:
- top level: `start`, attributes, resubmit policy;
- per step: added, removed, or changed. A changed step lists the property paths that differ (`assignee`, `sla.due`, `branches[1].condition`, `next`, …) with before and after, labelled for people.

Steps whose id changed show as removed plus added. That is honest, because the engine pins by node id too (E03 migrations).

The **Changes** view colours the diagram (added, removed, changed, each with an icon and a word as well as a colour, WCAG 1.4.1) and lists the changes, grouped by step. A reviewer sees it by default for a version in review, compared with the published version. Any two versions can be compared from the version list.

## 7. Accessibility and language
- The **Steps view** is the complete keyboard and screen-reader path:
  - an ordered list of steps, each a group with a heading;
  - its panel opens inline;
  - "Add step after", "Move", "Remove" and "Leads to" are buttons and selects.
- **Diagram:**
  - nodes and edges are focusable in reading order (React Flow's keyboard support);
  - arrow keys move the selection along the connections;
  - Enter opens the panel;
  - Delete asks before removing;
  - nodes carry an `aria-label` ("Step 3 of 7, Approve, Director, leads to Tell the author");
  - changes are announced in a polite live region;
  - the panel never covers the focused node (WCAG 2.4.11): it docks to the side and the diagram scrolls the node into view.
- **Contrast:** focus rings and edges are at least 3:1 (WCAG 1.4.11).
- **Target size:** controls are at least 24 × 24 px (WCAG 2.5.8).
- **Arabic:** the page is RTL; the diagram stays top-down; panels and lists mirror; names use `dir="auto"`.
- **CSP:** the build serves React Flow's stylesheet as a file under the existing `style-src 'self'`. T1 checks that nothing injects inline `<style>` at runtime.

## 8. API summary

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /api/v1/admin/workflow-builder/catalogue` | `.manage` or `.approve` | Attributes, roles, queues, fragments, step types |
| `POST …/{key}/versions/{n}/simulate` | `.manage` or `.approve` | 422 with problems when invalid; 429 over the limit |
| `GET …/{key}/versions/{n}` | unchanged | Adds `labels` |
| `PUT`, `validate`, `submit`, `approve`, `send-back` | unchanged (E03) | The builder uses them as they are |

## 9. Audit
Nothing new. Draft saves, submits, approvals and send-backs are already audited (`workflow.definition.*`). Simulation and the catalogue are reads.

## 10. Performance
- `catalogue`: under 100 ms with 500 roles, 200 queues and 100 fragments.
- `simulate`: under 300 ms p95 for a 30-step definition, over an organisation of 500 people in 100 units seeded by the test (the synthetic organisation arrives with Phase 1b), one resolver call per step.
- Diagram: layout and render of 100 steps (the schema maximum) under 200 ms in the browser. Validation round trip under 300 ms.

## 11. Data and migration
No migration. Bodies written by the builder are ordinary schema 2 bodies; older bodies open unchanged (unknown fields are kept).
