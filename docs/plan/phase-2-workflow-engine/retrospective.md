# Phase 2 — retrospective (2026-11-13)

> **All six epics are built.** E01 and E02 are merged and on dev. E03–E06 are stacked pull requests #41 → #81, each on the one before, waiting for the owner to merge them in order. The phase closes when they are merged, deployed to dev, and the items under "Carried" that need the owner are done.

## Exit status (MASTER_PLAN Phase 2 exit criteria, overview §5)

| Criterion | Status | Evidence |
|---|---|---|
| **First slice:** create with .docx, choose approvers, submit; return; v2; resubmit; round 2 from the first approver; last approval → Approved; one approval per double-click, earlier rounds not counted, document locked during review, cross-tenant isolation, an audit event per action | **Met**, merged and on dev | `memo.spec.ts` on every PR; `MemoReviewIT`, `MemoReassignIT`; double-action and round tests; property-based invariants (`WorkflowInvariantProperties`); `AuditActionCoverageTest` (E01, PR #34) |
| A tenant admin can build, simulate, get approval for and publish a multi-step workflow with conditions, parallel branches, SLAs and escalation, without developer involvement | **Met** in CI (not merged): the exit journey is green on PR #81 (2026-11-13) | `builder.spec.ts`: built in the Steps view (review, branch on rank, parallel committee, director with a deadline and escalation), simulated on both branches, approved by a second administrator with a step-up, used by a memo type, a memo routed to Approved. Plus `DefinitionSimulationIT`, `BuilderCatalogueIT`, the builder's unit, property and component tests |
| In-flight instances survive definition changes (version pinning proven by test) | **Met** in CI (not merged) | `MigrationIT` (preview, per-instance audit), pinning tests in `DefinitionRuntimeIT` (E03) |
| Load: 10,000 active instances with timers per tenant without degraded inbox latency | **Built, not yet measured** | Volume V2 generator (`WorkflowVolumeIT`), Gatling `InboxLoadSimulation`, weekly `load.yml`. GitHub runs scheduled workflows only on the default branch, so the first report comes after merge |
| Full audit of every action, including delegated "acting for" actions | **Met** in CI (not merged) | Audit assertions per action and `AuditActionCoverageTest`; acting-for recorded from the task, never the session (`SubstitutionIT`, `TaskActionsIT`) (E03, E04) |

**Phase-2 special tests** (TESTING_STRATEGY §12):
- first-slice E2E;
- property-based invariants, widened with every command;
- double-action concurrency;
- round counting, version binding and the document lock;
- the clearance re-check on assignment;
- timers and escalations with a controlled clock (`TimersIT`, `EscalationIT`, `MutableClock`);
- version pinning;
- the 10,000-instance load test, which is built and awaits its first run.

**Generic exit items:**
- **Demo script:** D1 in `epic-01-first-slice/demo.md`; the phase demo in `demo.md`.
- **ASVS:** rows added per epic (E02–E06).
- **Usability:** E01 screen flows were reviewed by the owner before build. **The builder walkthrough is carried** (`epic-06-visual-builder/ux-review.md`).
- **Manual accessibility:** axe runs on every new screen, in English and Arabic. **The keyboard and screen-reader pass over the inbox, memo view, route builder and builder is carried.** Its checklist is written in E06's `ux-review.md`.

## What changed versus the plan
- **E02 came early.** ONLYOFFICE editing merged right after E01 and is on dev.
- **Conditions are CEL** with declared attributes and no macros (ADR-0039). A simple form in the builder writes them, and keeps any CEL it cannot show exactly as written.
- **Assignees** gained queues with claim and release, a delegation-of-authority step with its own maker-checker lifecycle, substitution with "acting for", and four separation-of-duties rules (ADR-0040). These came from the owner's industry comparison on 2026-11-09.
- **Durations** are an explicit `SlaDuration` (days and minutes), not `java.time.Duration`. A property test showed days and minutes merging (E05).
- **Simulation runs on the server**, through the same pure engine with the organisation as it is now (ADR-0042). There is no second engine in the browser.
- **The builder stores no positions.** Layout is automatic, so diffs carry no layout noise and nothing needs dragging (WCAG 2.5.7).
- **The builder catalogue** moved to `/api/v1/admin/workflow-builder/catalogue`. Under `workflow-definitions/`, a definition keyed `catalogue` would have shadowed it.
- **The web catalogue gained a part.** The builder's texts are their own part (`builder`), and a page can load more than one part. The Arabic admin part was heading for its 15 KiB limit.
- **The demo gained a second administrator,** `reviewer@demo.example`, so the maker-checker review can be shown and tested end to end.
- **Folded tests:** some planned test classes were folded into others, each noted in its epic log. For example, `DefinitionSimulatorTest` is covered by `DefinitionSimulationIT` and the exit journey.

## What the gates caught
- **Property tests:**
  - working-time arithmetic merged days into minutes (E05);
  - the condition round trip and the diff properties held from the start; the edit-history property ran 100 random command sequences.
- **SpotBugs:** a possible null dereference in the simulation trace (E06 T5).
- **`RlsMetaTest`:** a foreign key without a supporting index (E04).
- **The E2E on the real stack:**
  - diagram connections were named by step ids ("Edge from tell to #end");
  - Playwright counts SVG connections as hidden (E06 T1).
- **Component tests:**
  - controlled number fields turned "3" into "23", and clamping on clear made "2" hard to type (E06 T3, T4);
  - a merge left conflict markers in the Arabic catalogue, caught by typecheck before push (E06 T2).
- **Load tests in CI:** several web test files time out under a full parallel run and pass alone. This is a known flake, still open.

## Carried
- **Owner:**
  - merge #41 → #81 in order;
  - then the agent deploys them to dev and checks them there;
  - the phase demo (`demo.md`) and the D1 demo;
  - the builder usability walkthrough;
  - the manual keyboard and screen-reader pass (checklist in E06 `ux-review.md`).
- **After merge:** the first weekly load report (p95 < 300 ms on V2).
- **Follow-ups:**
  - a sweep for timers dropped after their retry limit (E05);
  - the inbox sorted by due time (E05);
  - a warning level for validation, such as overlapping conditions (E06);
  - saved, rerunnable simulation scenarios (E06);
  - React Flow 13 when stable;
  - the web suite's timeouts under load;
  - the Phase 1a items still listed in STATUS.
