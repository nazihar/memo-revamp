# Test plan — E04 Org directory and authorisation

## Scope and risks
New: org directory (legal entities, units, positions, assignments, reporting lines), roles, grants, classification and clearance, the policy service, the authorisation matrix and coverage meta-test, people picker, admin screens. Highest risks: privilege escalation through scope or roles, stale permissions, tree corruption widening access, overlapping assignments, endpoints without rules. See [design.md](design.md), [threat-model.md](threat-model.md).

## Tests per slice
| Slice | Layer | Test (behaviour) | CI stage |
|---|---|---|---|
| T1 | Unit / property | `PolicyTest` (grant scopes, implicit `user` role, clearance), jqwik `PolicyProperties` (a tenant-wide grant allows every unit; a scoped grant allows exactly its subtree) | PR |
| T1 | Integration | `AuthzBootstrapIT` (idempotent; grants from `tenant_admin`; levels; clearances), `AccessAnnotationIT` (unannotated test handler → 403), `PrivilegeMfaIT`, `GrantCacheIT` (two app contexts) | PR |
| T2 | Authorisation | `AuthorizationMatrixTest` generated from `authz/*.yaml` (every cell), `EndpointCoverageTest` | PR |
| T3 | Integration | `OrgTreeIT` (create, move, cycle, close rules, ancestor consistency), `OrgScopeIT` (inside, outside, scope root), `LegalEntityIT` | PR |
| T4 | Integration / concurrency | `AssignmentIT` (overlap per position and per user, "as of D", correct dates, cancel, range within position), `AssignmentConcurrencyIT` (latch, one winner), `ReportingLineIT` (one line, cycle at overlapping dates), `DeactivationEndsAssignmentsIT`, `OrgHistoryIT` | PR |
| T5 | Integration / property | `RoleAdminIT` (built-in immutable, unknown permission, non-scopable scoped grant rejected, delete in use), `LastAdminIT` (concurrent), `ClassificationIT` (reorder, delete in use, dense ranks), `PolicyConditionIT` (SQL filter = in-memory decision on random grants and rows) | PR |
| T6 | Integration / performance | `DirectorySearchIT` (prefix and word start, NFC, Arabic, limit, inactive excluded), `PickerRateLimitIT`; `OrgPerformanceIT` (V3 volume, budgets of design §11) | PR (perf: nightly) |
| all | Audit | `OrgAuditIT`, `AuthzAuditIT`: every state-changing endpoint emits its event (`assertAudited`) | PR |
| all | Cross-tenant | `OrgCrossTenantIT`, `AuthzCrossTenantIT`: tenant A's units, positions, assignments, roles, grants, levels invisible and immutable from B (404) | PR |
| all | RLS | RLS meta-test covers the new tables automatically | PR |
| all | Contract | OpenAPI drift; generated client compiles | PR |
| T7 | Seed | `DemoTenantSeedIT` (org tree shape, users per role, idempotent) | PR |
| T8–T9 | Component | tree view keyboard navigation and lazy expansion, people picker (debounce, ARIA combobox, empty and error), assignment dialog validation, role form, level reorder, navigation from `/me/access` | PR |
| T10 | E2E | `org.spec.ts` (org journey and history), `access.spec.ts` (grant role with step-up, scoped admin limits), axe on every new screen (light/dark, LTR/RTL) | PR (smoke), nightly (cross-browser) |
| T10 | Visual | org tree, position detail, roles, classification baselines | nightly |

## Authorisation matrix tests
`e03-identity.yaml` (backfilled) and `e04-org-authz.yaml`: endpoint × {anonymous, partial, user, orgAdmin, branchAdmin inside, branchAdmin outside, auditor, tenantAdmin, tenantAdmin stale step-up, deactivated session, other-tenant admin} → allow / 401 / 403 / 404. Every cell is a generated test with seed objects from a shared fixture (two regions, a position in each).

## State-machine tests
Assignment and reporting line: create → ACTIVE, ACTIVE dates changed, ACTIVE → CANCELLED (allowed); edit CANCELLED, un-cancel (forbidden). Units/positions: close with open children/positions (forbidden), close (allowed), reopen (forbidden in 1a).

## Seed data
Demo Bank (design §14) for e2e and local; small builder fixtures per test class (`anOrg()`, `aUnit(parent)`, `aPosition(unit)`, `aGrant(user, role, scope)`); V3 synthetic org volume for performance.

## Security tests
STRIDE → tests per threat-model.md. ASVS rows updated: V8 (authorisation), V2.4 (anti-automation on the picker), V15.4 (concurrency), V16 (audit).

## Accessibility and i18n
Tree view follows the ARIA tree pattern (arrow keys, Home/End, type-ahead); picker follows the ARIA combobox pattern; dates through the E01 date field; every string in English and Arabic; RTL tree indentation mirrored.

## Exploratory charters
1. As a branch admin, try to reach or change anything outside the region, including by moving units and assigning outside users (45 min).
2. Try to create overlapping holders and reporting cycles through date edits (30 min).
3. Revoke and re-grant roles across two browsers and check how fast access changes (20 min).

## Exit checklist
- [ ] All listed tests implemented and green in their stage
- [ ] No new quarantined tests
- [ ] ASVS checklist updated
- [ ] `EXPLAIN` of new list/search queries recorded at V3 volume
