---
status: accepted
date: 2026-11-11
deciders: Product owner (delegated gate, Phase 2 Q1), agent
---

# 0038 — Workflow definitions: JSON graph, schema, maker-checker lifecycle, pinning and admin migration

## Context and problem statement
E01 runs one built-in definition (sequential approval of the initiator's route). E03 lets tenant administrators author definitions with fixed steps, branches, parallel steps, notifications and reusable fragments, and change them while items are in flight (phase overview §3 E03, research R10, R11). The format, its validation, how versions are approved and what happens to running instances must be decided once, because E04–E06 and every later module build on them.

## Decision drivers
- Definitions are data authored by tenant admins: validated strictly, never executed as code.
- Auditors must be able to see which version an item followed and why it took each path.
- A change to a definition must never alter an item already in approval unless an administrator moves it deliberately.
- E06's visual builder edits the same format; E04 adds assignee resolvers inside it.

## Considered options
1. **JSON graph of steps with `next` pointers, JSON Schema + semantic validation, versions pinned, migration by explicit admin action with per-instance preview** (our engine, ADR-0034).
2. BPMN 2.0 XML authored in a modeller, interpreted by our engine.
3. A linear list of steps with per-step skip conditions only (no graph).

## Decision outcome
Chosen option 1.

- **Format:** `{"schemaVersion": 2, "attributes": [...], "resubmit": ..., "start": "<stepId>", "steps": [...]}`. Each step has a stable `id`, a `type` (`route`, `review`, `approve`, `parallel`, `notify`, `branch`, `subworkflow`, and schema-only `sign` and `escalate-to-forum`), its own properties, and `next` (or `branches` + `otherwise` for `branch`). `end` is the implicit final step. The graph must be acyclic; going back is an action (send back), not an edge.
- **Validation:** JSON Schema (draft 2020-12, `com.networknt:json-schema-validator`) for shape, then semantic checks: unique ids, every `next` resolves, every step reachable from `start`, every path reaches `end`, no cycles, conditions compile to boolean over declared attributes (ADR-0039), at most one `route` step, referenced fragments published, limits on size. Errors carry the JSON pointer of the offending node.
- **Sub-workflows are fragments**, inlined when the parent version is published: the published version stores the authored `body` and a `compiled` body with the fragment's steps copied in under prefixed ids. The runtime only ever reads `compiled`, so a fragment's later versions never change a published parent.
- **Lifecycle:** a version is `DRAFT` → `IN_REVIEW` → `PUBLISHED` → `RETIRED` (or back to `DRAFT` when the checker sends it back). Maker-checker: the person who approves publication is not the person who submitted it. Publishing a version retires the previously published one of the same definition. Built-in definitions are published by the system.
- **Pinning:** an instance keeps the `definition_version_id` it started with, through every round.
- **Migration:** only by an administrator, for chosen instances of one definition, to a newer published version. A preview lists, per instance, whether its current step id exists in the target with the same type and assignee kind, and how the remaining path differs; instances that do not map are refused, not forced. Each migrated instance gets a `MIGRATE` action in its history and an audit entry.
- **Schema evolution of the JSON:** `schemaVersion` is stored per version; bodies with an older schema are upgraded on read by code (E01's schema-1 sequential body becomes a schema-2 body with one `route` step).

### Consequences
- Good: one format for import, export, review, the builder and the runtime; diffs are JSON diffs over stable ids; pinning makes the exit criterion "in-flight instances survive definition changes" testable.
- Good: fragments cannot change running items behind the admin's back.
- Bad: no loops in the graph; repeated review cycles are expressed as send-back actions or rounds.
- Bad: migration maps by step id only; a refactored definition may leave old instances on the old version (acceptable: they finish there).
- Follow-ups: E04 adds assignee resolvers (`assignee.kind`), E05 adds `sla` behaviour (the field is validated now), E06 adds the visual editor and diff screen.

### Confirmation
`DefinitionValidatorTest` (every rule, positive and negative), a property test that serialising and parsing a definition is stable, `DefinitionLifecycleIT` (maker ≠ checker, matrix rows), `VersionPinningIT` and `InstanceMigrationIT` (preview, refusal, per-instance audit).

## Pros and cons of the options
### JSON graph (chosen)
- Good, because it is data with a schema, easy to validate, diff and store in `jsonb`.
- Bad, because we own the semantics and their documentation.
### BPMN 2.0
- Good, because it is a standard with modellers.
- Bad, because the standard is far larger than an approval graph; partial support confuses authors, and BPMN expressions invite scripting.
### Linear list with skip conditions
- Good, because it is simple.
- Bad, because parallel groups and real branches (different approvers per path) do not fit.

## More information
Research: `docs/plan/phase-2-workflow-engine/epic-03-definition-runtime/research.md` (R10, R11). Related: ADR-0034, ADR-0035, ADR-0039.
