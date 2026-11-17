# E01 Forum types and forums — log

## 2026-11-18
- Plan written under the delegated gate (Phase 4 Q1): research (industry practice from vendor help centres, published bank charters and Basel CGO10; quorum building blocks), ADR-0056 (forum types, forums, membership) and ADR-0057 (quorum rules), design, threat model, tasks T1–T6, test plan, and screen flows J, K and L for the owner.
- Choices that came from the research:
  - Voting is separate from role (BoardPro).
  - Membership drives access (OnBoard reviews).
  - Term reminders at 42 and 1 days (BoardPro), with nothing removed automatically.
  - A structured quorum builder instead of CEL. Every charter found is a conjunction from a small vocabulary.
  - Recused members counting toward quorum is a setting (Exim counts them; the UK model articles do not).
  - Outcome kinds are fixed, with editable labels (Hyland).
  - No maker-checker on membership (none found); secretaries are limited instead.
- **T1 built (#155):** forum types with reviewed versions, presets, the quorum rule and evaluator, the preview, and the two permissions.
  - `QuorumTest` covers the clauses of the cited charters; `QuorumProperties` covers the arithmetic and invariants.
  - `ForumTypesIT`, the matrix, audit coverage, modularity, module metadata and the contract are green locally.
- **Deviations from the plan, recorded here:**
  - The workflow's `EscalationTargets` interface and the escalation-targets endpoint shipped with T1. They were small and already in the tree, and with no implementation every key stays accepted. T3 adds the forum's implementation.
  - T2 and T3 ship as one PR. They share the notifier and the notification kinds, and each local build-test-commit cycle takes about 30 minutes.
  - detekt's limits split two classes. The type store became `ForumTypeStore` (identity) and `ForumTypeVersionStore` (versions), and the controller became `ForumTypesController`, `ForumTypeVersionsController` and `ForumTypeResponses`, as memo types do.
  - The two seat problems (missing with fixed seats, present without) share the code `RANGE`.
  - Membership and term emails say "committee" in plain English until T4 adds the renameable `forum` term.
  - The demo credit workflow's escalation to the Management Credit Committee moves to E02, where the step starts to wait. As a pass-through step in E01 it would show nothing and would change a published definition on dev twice.
