# E04 Assignees, routing rules and DoA — test plan

Layers per `docs/plan/TESTING_STRATEGY.md`. CI stage: PR unless marked nightly.

| Task | Layer | Tests |
|---|---|---|
| T1 | Integration | `OrgDirectoryIT`: holders on a date (past, present, future assignments; vacancy), a user's position, unit head and ancestors, reporting line 1–5 up and past the top; `RoleHoldersIT`: tenant-wide, scoped to the unit, scoped to an ancestor, not to a sibling |
| T2 | Unit | `ResolutionTest` (engine with a fake resolver): one, several, none with and without fallback; resolution recorded; unresolved never advances |
| T2 | Property | `WorkflowInvariantProperties` widened: invariants 19–22 over generated definitions with resolver kinds and random resolver answers |
| T2 | Integration | `ResolversIT`: each kind against seeded org data; validator problems `UNKNOWN_POSITION`, `UNKNOWN_ROLE`, `UNKNOWN_QUEUE`; reassignment of an unresolved task |
| T3 | Integration | `QueueClaimIT`: claim, act by others refused, release, claim by another, first decision cancels the rest, administrator release; queue admin and membership by position |
| T4 | Integration | `SeparationOfDutiesIT`: SOD-1…SOD-4 at submit and at resolution; SOD-2 switched off; recusal with reason; audit entries |
| T5 | Integration | `SubstitutionIT` (new task to substitute acting for the principal; ended substitution; apply moves open tasks; SOD-3); `StaffChangeIT` (holder change re-resolves; deactivation blocks; lock order with a concurrent action) |
| T6 | Integration | `DoaLifecycleIT` (states, maker ≠ checker, step-up, effective dates, pinning); `DoaRuntimeIT` (two groups in order, `needed` counts, exclusions, no rule → unresolved); `DoaSimulatorIT` (same answer as the runtime for the same inputs) |
| T6 | Property | simulator and runtime agree on generated rule sets and attributes |
| T7 | Integration | `ExtendedRouteIT`: position entries, groups all / any, allowed approvers, DoA check problems, forward limits |
| T8–T9 | Unit (web) | route editor groups and positions, claim / release, recuse dialog, DoA preview; DoA rules editor and simulator; axe on each screen |
| T10 | E2E | position re-resolution after a holder change; DoA memo with two groups; claim and release between two people; a11y |
| T10 | Matrix | `p2-e04-assignees.yaml`: every new endpoint |
