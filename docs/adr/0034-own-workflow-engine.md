---
status: accepted
date: 2026-11-09
deciders: Product owner (gate delegated, Phase 2 overview Q1), Claude (agent)
---

# 0034 — Our own workflow engine on PostgreSQL, subject-agnostic

## Context and problem statement
Phase 2 builds the routing engine that memos use first and agenda submissions, minutes, circular resolutions and action closure use later (MASTER_PLAN Phase 2). We must choose between an embedded BPM engine and our own, and decide how the engine relates to the things it routes. Research: `docs/plan/phase-2-workflow-engine/epic-01-first-slice/research.md` R2.

## Decision drivers
- Forced RLS on every tenant table, `tenant_id` first in every index (AGENTS §3.2)
- Audit in the same transaction, in our hash chain (AGENTS §3.3)
- One tenant-aware scheduler (db-scheduler, ADR-0018) and one tenant-propagation story (ADR-0017)
- Rounds within one instance, document-version binding, clearance re-checks: domain rules no generic engine models
- Admin-editable definitions (E03, E06) simpler than BPMN
- On-prem simplicity and licence (Apache-2.0 or our own)

## Considered options
1. Our own engine on PostgreSQL (jOOQ, plain-Kotlin transition logic)
2. Embedded Flowable 8 (Apache-2.0)
3. Embedded Camunda 7 CE fork (Operaton or CIB seven, Apache-2.0)

## Decision outcome
Chosen option: "Our own engine", because both embedded engines bring their own schema without RLS, their own job executor and their own history outside our audit chain. Making them comply would cost more than the bounded features they save (parallel joins, timers, sub-workflows), which E03–E05 build in our model.

**Module boundary:**
- `workflow` is subject-agnostic. It knows a subject only as `(subjectType, subjectId)`. It owns definitions, instances, rounds, tasks and actions, and it decides who may act on a task. A `WorkflowSubject` callback, registered by the owning module per subject type, answers what the engine cannot: whether a person may see the subject (clearance), and the subject's attributes for conditions (E03). It is also called synchronously in the action transaction, so the subject can veto or record the action (for example, bind the document version).
- `memo` is the first subject. It owns the memo, its documents and the note sheet, and listens to workflow events for its status. The workflow never reads memo tables.
- **Transition logic** is a pure Kotlin function `(instance state, command) → (new state, effects)` with no Spring or database, so property tests run in milliseconds (TESTING_STRATEGY §2). The service layer loads state, applies the function, writes with guarded updates, and emits audit and events in the same transaction.

### Consequences
- Good: one schema, one scheduler, one audit chain. The tables follow our RLS, index and migration rules. Domain rules (rounds, version binding) are first-class.
- Good: mutation and property testing of the core is cheap.
- Bad: we own parallel joins, timers, sub-workflows and definition versioning, and their bugs.
- Follow-ups: E03 (definition model and step types), E05 (timers) extend the same tables. ADR-0035 fixes the data model.

### Confirmation
Spring Modulith `verify()` (no dependency from `workflow` to `memo`); ArchUnit rule that `workflow.engine` has no Spring or jOOQ imports; the property-based invariant suite (E01) and PIT mutation score ≥ 70 % on `workflow` (nightly).

## Pros and cons of the options
### Our own engine
- Good, because it fits RLS, audit, scheduler and domain rules by construction.
- Bad, because the features of a mature engine are ours to build and test.

### Flowable 8
- Good, because it is mature, Apache-2.0 and supports Spring Boot 4 since 8.0.0.
- Bad, because about 80 tables without RLS, multi-tenancy by a filtered column in Java, a second job executor, history outside our chain, and BPMN as the definition language.

### Camunda 7 CE fork
- Good, because it is a widely known engine model.
- Bad, because Camunda 7 CE ended with 7.24 (October 2025); the forks are young; the same mismatches as Flowable.

## More information
Research R2 (sources there, accessed 2026-11-09).
