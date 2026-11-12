---
status: accepted
date: 2026-11-12
deciders: Product owner (delegated gate, Phase 2 Q1), agent
---

# 0040 — Assignee resolution, queues, DoA rules, separation of duties and acting for

## Context and problem statement
E03 definitions name approvers as fixed people. Real approval follows the organisation: the holder of a position, the initiator's supervisor, the head of a unit, whoever the delegation-of-authority (DoA) matrix requires for this amount, or a shared queue such as a secretariat. People change positions, go on leave and leave, and banks must show that nobody approved their own item, nobody approved twice, and interested people stood aside (research R5, R6, R12; phase overview §4 E04, §9 Q3).

## Decision drivers
- Every assignment is explainable afterwards ("resolved to X because…") and reproducible from the recorded inputs.
- The engine stays a pure function (ADR-0034): organisation lookups happen outside it.
- A step is never skipped or approved because nobody was found.
- Configuration that grants authority (the DoA) goes through maker-checker like definitions (ADR-0038).
- Phase 1b owns delegation and acting assignments in the org model; E04 must not wait for it (Q3).

## Considered options
1. **Resolve at task creation through a resolver port; record the resolution on the step** (chosen).
2. Resolve once at submit for the whole definition: cheap, but wrong for long approvals across staff changes, and DoA bands may depend on values set during review.
3. Store only roles on tasks and let the inbox query "who holds this now": always current, but the history cannot say who was asked, and access checks become joins over the org model on every read.
4. DoA inside definitions (as branches): one place, but every band change would need a new definition version and the DoA's own maker-checker and effective dates would be lost.

## Decision outcome
Chosen option 1.
- **Assignee kinds** in definitions (schema 2, additive): `person`, `initiator` (existing), `position`, `supervisor` (the initiator's, `levels` up the reporting lines), `unitHead` (of the initiator's unit or a named unit, `up` levels), `role` (holders of an authorisation role in a unit, walking up), `queue`, and the step type **`doa`**.
- **Resolver port:** the engine's `TransitionContext` gets `resolve(assignee, subject) → Resolution(people, inputs, reason)`. The runner implements it with the new public read facades `org.OrgDirectory` and `common.authz.RoleHolders`. Resolution happens when a step is activated; the result and its inputs are stored on the step (`workflow_step.resolution`) and shown in the history.
- **Several people:** a step resolved to more than one person offers one task to each and is decided by the first; any of them may **claim** it (the others see it claimed) and **release** it. Queues resolve the same way.
- **Nobody:** the step is held by a **blocked task without an assignee** (`UNRESOLVED`), the workflow administrators are alerted, and an administrator assigns it (E01 reassignment). A definition may name a `fallback` assignee to use instead. Never skipped, never auto-approved.
- **DoA:** tenant data in `workflow`, separate from definitions: versions (draft → in review → published, maker ≠ checker, step-up, an **effective-from** date) of rules, each rule a scope (subject type and memo type, unit subtree, an optional CEL condition over item attributes, ADR-0039) and **requirement groups** ("1 of {GM Credit}", "2 of {DMD, AMD}"). A `doa` step activates the matched rule's groups in order, each as a step that needs its count; an instance pins the DoA version in force when it starts. A simulator answers "who approves this?" from the same code.
- **Separation of duties:** a fixed catalogue checked when a route is validated and when each task is created: SOD-1 the initiator never approves (E01); SOD-2 nobody approves twice in one round (tenant switch, default on); SOD-3 a substitute or delegate never acts on an item they initiated or one their principal is excluded from; SOD-4 a declared interest recuses the holder and the step re-resolves without them. A failing rule blocks submit with a reason, or skips that person in resolution; every decision is recorded.
- **Acting for (Q3):** E04 adds dated **substitutions** (principal, substitute, from, to) in `workflow`, created through an administration API; Phase 1b adds the self-service screen and may move them into its delegation model. While one is in force, new tasks for the principal go to the substitute, recorded as acting for the principal (E03's `on_behalf_of`). Acting assignments in the org model, when 1b adds them, resolve as holders of the position.
- **Staff changes:** when a position's holder changes (`OrgChanged`), open tasks resolved from that position are re-resolved to the new holder with a history entry; when a person is deactivated, their open tasks are blocked for reassignment (E01's `INACTIVE`).

### Consequences
- Good: the history explains every assignment; access and inboxes keep working on task rows, not live org queries.
- Good: DoA changes do not need definition versions, and carry their own effective dates and approvals.
- Bad: a resolution is a snapshot; a position change between activation and action is caught only by the re-resolution listener (tested).
- Bad: tasks may now have no assignee (only blocked `UNRESOLVED` ones), so every reader of `assignee` handles null.
- Follow-ups: automatic release of claimed tasks after a time (E05 timers); an SoD exceptions report and overrides with justification and a second administrator (later; out of E04 scope, recorded in the epic log); the related-party register (later).

### Confirmation
`ResolversIT` (each kind against seeded org data, recorded inputs), `QueueClaimIT`, `DoaLifecycleIT` and `DoaSimulatorIT`, `SeparationOfDutiesIT`, `SubstitutionIT`, `StaffChangeIT`, the widened engine invariants (an unresolved step never advances), matrix `p2-e04-assignees.yaml`.
