# E01 First slice — threat model (STRIDE)

## Data flows
1. Browser → edge → web → backend: memo create and edit, route, actions, inbox, history.
2. Backend ↔ files service (in-process): document upload through the `memo.document` purpose, downloads, purpose callbacks.
3. Backend ↔ PostgreSQL: memo and workflow tables, idempotency keys, audit, notifications.
4. Backend → notification outbox → mail relay: assignment and outcome emails.

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | Someone acts on a task that is not theirs (guessing task ids, a stale tab after reassignment) | 1 | The engine checks the actor against the open task's assignee on every action; ids are UUIDv7 but never trusted; reassigned tasks are no longer `OPEN` | V8.2.x | matrix `p2-e01-memo.yaml` (earlier, later, uninvolved approver → 403/404); `ReassignIT` (old assignee's action → 409) |
| S2 | An approval without fresh authentication (stolen unlocked session) | 1 | Step-up on approve per memo type with the approval window (ADR-0036); reassignment needs step-up with the short window | V6.x (L3) | `ApproveStepUpIT` (no factor, stale factor → 401 with window; within window → ok; flag off → ok) |
| T1 | The document changes after an approver approved it, or between rounds without trace | 1, 2 | Upload refused outside `DRAFT`/`RETURNED` by the purpose callback (server-side); each action row binds version id + SHA-256; the files service verifies content against its hash on read; record storage is object-locked | V8.x, V16.x | `DocumentLockIT` (upload in review → 403 through the API), `VersionBindingIT` |
| T2 | History rewritten: an action row edited or deleted, an approval from round 1 counted in round 2 | 3 | `workflow_action` append-only by trigger; gapless `seq`; same-transaction audit in the hash chain; the final-approval check counts only the current round | V16.x (L3) | `ActionLogImmutabilityIT`, property suite (earlier rounds never count) |
| T3 | Double approval or racing actions (double click, two devices, resubmit vs withdraw) leave an inconsistent state | 1, 3 | Required `Idempotency-Key`; instance row lock; guarded updates with `version`; 409 for the loser | V2.x (business logic) | `DoubleActionIT` (latches), `ResubmitWithdrawRaceIT`, idempotency replay and reuse tests |
| T4 | An idempotency key replays another user's result | 1, 3 | Fingerprint includes the actor; key scoped per tenant and operation | V2.x | `IdempotencyActorIT` (same key, other user → 422) |
| R1 | An approver denies having approved, or claims a different document | 1, 3 | Action row and audit entry carry actor, session, IP, device, step-up age, document version + hash | V16.3.x | `WorkflowAuditIT` (every action audited with these fields) |
| I1 | A memo's subject or document is seen by someone not cleared or not involved (inbox, sent box, admin list, notifications, search by id) | 1, 3, 4 | Visibility = participant + clearance, in SQL (`memo_participant` + `classification_rank` filter); `404` for invisible memos; admin list shows "Restricted" when not cleared; inbox shows a placeholder after a clearance drop; files `mayRead` uses the same rule | V8.2.x, V8.4.1 | matrix (uncleared participant, tenant admin not participant → 404), `MemoVisibilityIT`, `CrossTenantMemoIT` |
| I2 | Sensitive content in emails, push, URLs or logs | 4, 1 | Email subjects fixed and generic; email body has a reference and a link only; URLs carry ids only; logs ids only; audit entries without subject or remark text | V14.x | `WorkflowMailContentTest` (no subject or remark in subject or body), `MemoLoggingTest` |
| I3 | Existence of memos in another tenant | 3 | RLS forced on every new table; tenant from the host | V8.4.x | RLS meta-test (automatic), `CrossTenantMemoIT` |
| I4 | A route discloses who is cleared for what (probing the picker) | 1 | The picker answers "not cleared" only for classifications the actor may themselves create; rate-limited (E04 search limit) | V8.x | `RouteValidationIT` (reason codes), existing picker rate-limit test |
| D1 | Huge routes, remarks or histories slow the memo view or actions | 1, 3 | Route ≤ 20 (tenant setting); remark ≤ 4,000 chars; subject ≤ 300; history paged by round | V2.x | validation tests; `MemoPerformanceIT` |
| D2 | A blocked or departed approver leaves a memo stuck unseen | 3 | Clearance and active state checked when each task is created; blocked tasks flagged, initiator and admins notified, admin list of blocked tasks | — (availability of business process) | `BlockedTaskIT` |
| E1 | A tenant admin gains memo content through administration (reassign to self, change clearance) | 1 | Admins do not read memos by role; reassignment to oneself is allowed only if cleared and is audited with a reason; clearance changes are audited (E04) | V8.x | matrix (admin not participant → 404); `ReassignIT` (reassign to self audited) |
| E2 | Mass assignment or field injection (setting status, initiator, instance through the edit API) | 1 | Explicit request DTOs with only editable fields; status changes only through action endpoints | V15.x | `MemoEditIT` (unknown fields refused) |

## Residual risks
- The approval window of 30 minutes (Q5) lets a stolen, unlocked session approve within that time. It is bounded by idle and absolute session timeouts; a tenant can shorten it.
- Tenant admins can reassign a task to someone who then reads the memo. This is the safety valve the master plan asks for; it is audited, notified to both people, and needs step-up. Phase 3 adds maker-checker options for sensitive memo types.
- Earlier approvers keep read access after their round (they are participants). That is intended for accountability; Phase 3's access rules may narrow it per memo type.
