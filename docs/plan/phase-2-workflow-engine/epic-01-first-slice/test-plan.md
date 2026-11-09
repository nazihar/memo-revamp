# Test plan — E01 First slice: sequential memo approval

## Scope and risks
New: the `workflow` module (engine core, tables, service, events), the `memo` module (types, drafts, route, document purpose, inbox, history), three permissions, the approval step-up window, workflow notifications, the memo screens. Highest risks: double actions, earlier rounds counted, approvals not bound to the document seen, memos visible to the wrong people, history that can change. See [design.md](design.md), [threat-model.md](threat-model.md).

## Test infrastructure
- **Builders:** `aMemoType()`, `aDraft(initiator, type, classification)`, `withDocument(bytes)`, `withRoute(vararg approvers)`, `submitted()`, `returnedBy(n, remark)`; `anInstanceState()` generators for jqwik.
- **Concurrency harness:** the existing latch helper (E02/E05) runs two actions to the guarded update and releases them together.
- **Clock:** injected `Clock` for the step-up window tests.
- **DOCX samples** generated at test time (a minimal valid package), English and Arabic text.

## Tests per slice
| Slice | Layer | Test (behaviour) | CI stage |
|---|---|---|---|
| T1 | Unit / property | `TransitionTest` (each allowed transition, each refusal); `WorkflowInvariantProperties` (jqwik: random sequences of approve / return / reject / withdraw / resubmit / reassign by random actors; invariants 2–8; earlier rounds never count; terminal states terminal) | PR |
| T1 | Architecture | `EngineArchitectureTest` (no Spring, jOOQ, system clock in `workflow.engine`) | PR |
| T1 | Mutation | PIT on `workflow` (≥ 70 %) | nightly |
| T2 | Integration | `WorkflowServiceIT` (start, act, events, audit in the same transaction, rollback on subject veto leaves nothing), `ActionLogImmutabilityIT` (trigger), `ActionSeqIT` (gapless under concurrency), `DefinitionSeedIT` (built-in definition for new and existing tenants) | PR |
| T2 | Concurrency | `DoubleActionIT` (same task, two keys → one 200, one 409; same key twice → same response, one row), `IdempotencyActorIT` (same key, other user → 422) | PR |
| T3 | Integration | `MemoDraftIT` (create, edit with `If-Match`, discard, classification floor), `MemoTypeAdminIT`, `MemoDocumentPurposeIT` (DOCX only, record storage, `mayUpload`/`mayRead`), `ClearancesTest` | PR |
| T4 | Integration | `RouteValidationIT` (count, duplicate, self, inactive, uncleared → reason per entry), `SubmitIT` (needs document and route; round 1; first task; participants written), `BlockedTaskIT` (clearance lowered or account deactivated before activation → blocked, notified) | PR |
| T5 | Integration | `FirstSliceFlowIT` (the full D1 scenario at API level), `DocumentLockIT` (upload in review → 403; in returned → ok), `VersionBindingIT` (every action row carries the version + hash current at that moment; round 2 rows carry v2), `RoundCountingIT`, `ApproveStepUpIT` (window, type flag), `WithdrawIT` | PR |
| T5 | Concurrency | `ResubmitWithdrawRaceIT` (exactly one wins; deterministic final state), `ApproveVsWithdrawRaceIT` | PR (key cases), nightly (1,000 iterations) |
| T6 | Integration | `ReassignIT` (reason required, step-up, old task `REASSIGNED`, new task, both notified, audited; old assignee's late action → 409; reassign to an uncleared user → 422) | PR |
| T7 | Integration | `InboxIT` (my tasks only, newest first, keyset, restricted placeholder after clearance drop), `MemoBoxesIT` (drafts, sent with holders, closed), `HistoryIT` (grouped by round, earlier rounds marked) | PR |
| T7 | Performance | `MemoPerformanceIT` (design §10 budgets with 2× headroom; query counts) | PR |
| T8 | Unit / integration | `WorkflowNotificationsIT` (each kind to the right recipients), `WorkflowMailContentTest` (no subject or remark text in email subject or body; Arabic renders) | PR |
| T9 | Component | Vitest + Testing Library: route editor (add, reorder with ↑ ↓ by keyboard, remove, reasons shown), action dialogs (remark required, key created on open, retry after step-up keeps the key), history by round | PR |
| T9 | Accessibility / visual | axe on every new screen, light/dark, LTR/RTL; visual baselines; pseudo-locale overflow | PR |
| T10 | E2E | `memo-first-slice.spec.ts` (the D1 journey with three signed-in users), `memo-reassign.spec.ts`; Chromium every PR, Firefox nightly | PR, nightly |
| all | Audit | `WorkflowAuditIT`: each new action asserted (`AuditActionCoverageTest` enforces it); actor, session, document hash, step-up age present; no subject or remark text in audit `before/after` | PR |
| all | Cross-tenant | `CrossTenantMemoIT` (memos, tasks, history, documents of tenant A invisible from B; B's admin cannot reassign A's task) | PR |
| all | RLS | RLS meta-test covers the eleven new tables automatically | PR |
| all | Authorisation | `AuthorizationMatrixTest` with `p2-e01-memo.yaml`; `EndpointCoverageTest` | PR |
| all | Contract | OpenAPI drift; generated client compiles | PR |
| all | Fuzz | Jazzer target over the action request parsing and remark/route validation | nightly |

## Authorisation matrix tests
`p2-e01-memo.yaml`: every endpoint × {anonymous, partial session, initiator, current approver, earlier approver, later approver, uninvolved user, uncleared participant, tenant admin (not participant), auditor, deactivated session, other-tenant admin} → allow / 401 / 403 / 404. Mutating cells carry sample bodies and `Idempotency-Key`, so the runtime audit check (Phase 1a retrospective) covers them.

## State-machine tests
From design §3: every allowed transition of the memo and the task has a test, and every forbidden one does too: approve / return / reject on a non-open task; by a non-assignee; on a blocked task; resubmit while `IN_REVIEW`; submit without document or route; withdraw after a terminal state; discard after submit; upload in `IN_REVIEW`, `APPROVED`, `REJECTED`, `WITHDRAWN`; edit classification after submit; reassign a completed task.

## Seed data
Two tenants from the existing fixtures plus the demo tenant (design §15). The demo tenant gives three approvers with sign-in and memos in every state.

## Security tests
STRIDE → tests per threat-model.md. ASVS rows updated: V2 (business logic: sequential steps, double actions, limits), V6 (step-up on approve, L3), V8 (authorisation, L3), V14 (no content in email), V16 (audit, L3). ZAP baseline on dev keeps running; the authenticated weekly scan picks up the new endpoints.

## Accessibility and i18n
Every screen keyboard-operable (route reordering without dragging); history and action bar announced correctly; English and Arabic; manual keyboard and screen-reader pass of the first-slice journey before D1.

## Exploratory charters
1. Try to approve something you should not (stale tab after reassignment, another device, a task id from a notification of another user) (45 min).
2. Try to make an approval apply to a document version other than the one shown (upload races at submit and resubmit) (45 min).
3. Try to learn a memo's subject without clearance (inbox, notifications, admin list, history, file download) (30 min).

## Exit checklist
- [ ] All listed tests implemented and green in their stage
- [ ] No new quarantined tests
- [ ] ASVS checklist updated (V2, V6, V8, V14, V16)
- [ ] First-slice journey and reassignment on the critical-journey list
- [ ] D1 demo script written and run on dev
